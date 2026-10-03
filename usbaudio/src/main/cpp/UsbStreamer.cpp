#include "UsbStreamer.h"

#include <android/log.h>
#include <libusb.h>
#include <sys/resource.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>

#define LOG_TAG "UsbAudio"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kTransfersInFlight = 8;
constexpr uint32_t kTransferMillis = 10;   // audio per transfer
constexpr uint32_t kRingMillis = 500;      // decoded audio buffered ahead of USB
constexpr int16_t kStartVolume = -30 * 256;  // quiet start: IEMs are sensitive

// UAC1 class requests
constexpr uint8_t kSetCur = 0x01;
constexpr uint8_t kGetCur = 0x81;
constexpr uint8_t kGetMin = 0x82;
constexpr uint8_t kGetMax = 0x83;
constexpr uint8_t kGetRes = 0x84;
constexpr uint8_t kMuteControl = 0x01;
constexpr uint8_t kVolumeControl = 0x02;
constexpr uint8_t kSamplingFreqControl = 0x01;

constexpr uint8_t kReqOutInterface = 0x21;
constexpr uint8_t kReqInInterface = 0xA1;
constexpr uint8_t kReqOutEndpoint = 0x22;
constexpr uint8_t kReqInEndpoint = 0xA2;

int16_t le16(const uint8_t* p) { return int16_t(p[0] | (p[1] << 8)); }

}  // namespace

UsbStreamer::~UsbStreamer() { close(); }

bool UsbStreamer::open(int fd, const uint8_t* raw, size_t length, std::string& error) {
    device_ = uac::parseDescriptors(raw, length);
    if (device_.uacVersion == 0) {
        error = "This USB device is not a USB Audio device.";
        return false;
    }
    if (device_.uacVersion == 2) {
        error = "This is a USB Audio Class 2 DAC. UAC2 support arrives in milestone 2.";
        return false;
    }
    if (device_.outputs.empty()) {
        error = "The DAC reports no playback formats.";
        return false;
    }

    libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
    int rc = libusb_init_context(&ctx_, nullptr, 0);
    if (rc != LIBUSB_SUCCESS) {
        error = std::string("libusb init failed: ") + libusb_error_name(rc);
        ctx_ = nullptr;
        return false;
    }
    rc = libusb_wrap_sys_device(ctx_, static_cast<intptr_t>(fd), &handle_);
    if (rc != LIBUSB_SUCCESS) {
        error = std::string("libusb could not open the DAC: ") + libusb_error_name(rc);
        close();
        return false;
    }

    // Java already force-claimed these (detaching Android's driver); this only
    // updates libusb's own bookkeeping so alt-setting changes are allowed.
    libusb_claim_interface(handle_, device_.controlInterface);
    for (const uac::OutputFormat& f : device_.outputs) {
        rc = libusb_claim_interface(handle_, f.interfaceNumber);
        if (rc != LIBUSB_SUCCESS && rc != LIBUSB_ERROR_BUSY) {
            error = std::string("Could not claim the streaming interface: ") + libusb_error_name(rc);
            close();
            return false;
        }
    }

    eventThreadRun_ = true;
    eventThread_ = std::thread(&UsbStreamer::eventLoop, this);

    featureUnit_ = uac::findPlaybackFeatureUnit(device_, device_.outputs[0]);
    if (featureUnit_) {
        if (featureUnit_->masterMute) {
            uint8_t off = 0;
            controlFeature(kSetCur, kMuteControl, 0, &off, 1);
        }
        setVolume(kStartVolume);
    }
    LOGI("opened DAC:\n%s", uac::describe(device_).c_str());
    return true;
}

bool UsbStreamer::start(uint32_t sampleRate, uint32_t sourceBits, uint32_t channels, StreamInfo& info,
                        std::string& error) {
    stop();
    if (!handle_) {
        error = "DAC is not open.";
        return false;
    }

    const int speed = libusb_get_device_speed(libusb_get_device(handle_));

    auto packetsPerSecond = [&](const uac::OutputFormat& f) {
        const uint32_t shift = std::clamp<uint32_t>(f.interval, 1, 16) - 1;
        return (speed >= LIBUSB_SPEED_HIGH ? 8000u : 1000u) >> std::min<uint32_t>(shift, 3);
    };

    // Prefer the smallest format that holds the source losslessly; otherwise the
    // deepest one available, with dither.
    int best = -1;
    int deepest = -1;
    bool rateOk = false, channelsOk = false;
    for (size_t i = 0; i < device_.outputs.size(); ++i) {
        const uac::OutputFormat& f = device_.outputs[i];
        if (f.formatTag != 1 || f.channels != channels) continue;
        channelsOk = true;
        if (!f.supportsRate(sampleRate)) continue;
        const uint32_t maxFrames = uac::PacketScheduler(sampleRate, packetsPerSecond(f)).maxFrames();
        if (maxFrames * f.frameBytes() > f.maxPacketBytes) continue;
        rateOk = true;
        if (deepest < 0 || f.bitResolution > device_.outputs[deepest].bitResolution) deepest = int(i);
        if (f.bitResolution < sourceBits) continue;
        if (best < 0 || f.subslotBytes < device_.outputs[best].subslotBytes) best = int(i);
    }
    const bool lossless = best >= 0;
    if (!lossless) best = deepest;
    if (best < 0) {
        char msg[160];
        if (!channelsOk) {
            std::snprintf(msg, sizeof msg, "The DAC has no %u-channel output.", channels);
        } else {
            std::snprintf(msg, sizeof msg, "The DAC cannot play %u Hz (resampling is not built yet).", sampleRate);
        }
        error = msg;
        return false;
    }

    format_ = &device_.outputs[best];
    const uac::OutputFormat& f = *format_;
    int rc = libusb_set_interface_alt_setting(handle_, f.interfaceNumber, f.altSetting);
    if (rc != LIBUSB_SUCCESS) {
        error = std::string("Could not select the DAC's playback mode: ") + libusb_error_name(rc);
        format_ = nullptr;
        return false;
    }

    uint32_t deviceRate = 0;
    if (f.sampleRateControl || f.continuousRates || f.rates.size() > 1) {
        uint8_t data[3] = {uint8_t(sampleRate), uint8_t(sampleRate >> 8), uint8_t(sampleRate >> 16)};
        rc = libusb_control_transfer(handle_, kReqOutEndpoint, kSetCur, kSamplingFreqControl << 8,
                                     f.endpointAddress, data, 3, 1000);
        if (rc < 0) LOGW("SET_CUR sample rate failed: %s", libusb_error_name(rc));
        uint8_t back[3] = {};
        rc = libusb_control_transfer(handle_, kReqInEndpoint, kGetCur, kSamplingFreqControl << 8,
                                     f.endpointAddress, back, 3, 1000);
        if (rc == 3) deviceRate = uint32_t(back[0] | (back[1] << 8) | (back[2] << 16));
    }
    if (deviceRate != 0 && deviceRate != sampleRate) {
        char msg[128];
        std::snprintf(msg, sizeof msg, "The DAC was asked for %u Hz but switched to %u Hz.", sampleRate, deviceRate);
        error = msg;
        libusb_set_interface_alt_setting(handle_, f.interfaceNumber, 0);
        format_ = nullptr;
        return false;
    }

    const uint32_t pps = packetsPerSecond(f);
    scheduler_ = std::make_unique<uac::PacketScheduler>(sampleRate, pps);
    frameBytes_ = f.frameBytes();
    sourceSubslot_ = f.subslotBytes;
    packetsPerTransfer_ = std::max<uint32_t>(1, pps * kTransferMillis / 1000);
    ring_ = std::make_unique<uac::RingBuffer>(size_t(sampleRate) * frameBytes_ * kRingMillis / 1000);
    packBuffer_.resize(size_t(sampleRate / 100) * frameBytes_);  // 10 ms per pack step
    if (lossless) {
        ditherer_.reset();
    } else {
        ditherer_ = std::make_unique<uac::Ditherer>(f.bitResolution);
        ditherBuffer_.resize(size_t(sampleRate / 100) * f.channels);
    }

    const size_t transferBytes = size_t(packetsPerTransfer_) * scheduler_->maxFrames() * frameBytes_;
    transferBuffers_.assign(kTransfersInFlight, std::vector<uint8_t>(transferBytes));
    for (int i = 0; i < kTransfersInFlight; ++i) {
        libusb_transfer* t = libusb_alloc_transfer(int(packetsPerTransfer_));
        libusb_fill_iso_transfer(t, handle_, f.endpointAddress, transferBuffers_[i].data(), int(transferBytes),
                                 int(packetsPerTransfer_), &UsbStreamer::onTransferDone, this, 0);
        transfers_.push_back(t);
    }

    framesSent_ = 0;
    underruns_ = 0;
    transferErrors_ = 0;
    primed_ = false;
    streaming_ = true;
    for (libusb_transfer* t : transfers_) {
        fillTransfer(t);
        ++inFlight_;
        rc = libusb_submit_transfer(t);
        if (rc != LIBUSB_SUCCESS) {
            --inFlight_;
            error = std::string("Could not start USB streaming: ") + libusb_error_name(rc);
            stop();
            return false;
        }
    }

    info.outputIndex = best;
    info.sampleRate = sampleRate;
    info.deviceRate = deviceRate;
    info.subslotBytes = f.subslotBytes;
    info.bitResolution = f.bitResolution;
    info.bitPerfect = lossless;
    LOGI("streaming %u Hz, %u-bit source -> alt %u (%u-bit in %u-byte slots%s), %u packets/s, %u packets/transfer",
         sampleRate, sourceBits, f.altSetting, f.bitResolution, f.subslotBytes, lossless ? "" : ", dithered", pps,
         packetsPerTransfer_);
    return true;
}

void UsbStreamer::fillTransfer(libusb_transfer* t) {
    uint8_t* buf = t->buffer;
    size_t offset = 0;
    for (int i = 0; i < t->num_iso_packets; ++i) {
        const size_t want = size_t(scheduler_->next()) * frameBytes_;
        const size_t got = ring_->read(buf + offset, want);
        if (got < want) {
            std::fill(buf + offset + got, buf + offset + want, uint8_t(0));  // silence
            if (primed_) ++underruns_;
        }
        framesSent_ += got / frameBytes_;
        t->iso_packet_desc[i].length = static_cast<unsigned int>(want);
        offset += want;
    }
    spaceCv_.notify_one();
}

void UsbStreamer::onTransferDone(libusb_transfer* t) {
    auto* self = static_cast<UsbStreamer*>(t->user_data);
    bool resubmit = false;
    switch (t->status) {
        case LIBUSB_TRANSFER_COMPLETED:
            resubmit = self->streaming_;
            break;
        case LIBUSB_TRANSFER_NO_DEVICE:
            self->disconnected_ = true;
            self->streaming_ = false;
            break;
        case LIBUSB_TRANSFER_CANCELLED:
            break;
        default:
            ++self->transferErrors_;
            resubmit = self->streaming_;
            break;
    }
    if (resubmit) {
        self->fillTransfer(t);
        const int rc = libusb_submit_transfer(t);
        if (rc == LIBUSB_SUCCESS) return;
        LOGE("resubmit failed: %s", libusb_error_name(rc));
        if (rc == LIBUSB_ERROR_NO_DEVICE) self->disconnected_ = true;
        self->streaming_ = false;
    }
    --self->inFlight_;
    self->spaceCv_.notify_all();
}

void UsbStreamer::eventLoop() {
    setpriority(PRIO_PROCESS, 0, -19);  // ANDROID_PRIORITY_URGENT_AUDIO
    while (eventThreadRun_) {
        timeval tv{0, 100 * 1000};
        libusb_handle_events_timeout_completed(ctx_, &tv, nullptr);
    }
}

long UsbStreamer::write(const int32_t* pcm, size_t frames) {
    if (!streaming_) return -1;
    const size_t channels = format_->channels;
    const size_t chunkFrames = packBuffer_.size() / frameBytes_;
    size_t done = 0;
    while (done < frames) {
        const size_t n = std::min(chunkFrames, frames - done);
        const int32_t* src = pcm + done * channels;
        if (ditherer_) {
            ditherer_->process(src, ditherBuffer_.data(), n * channels);
            src = ditherBuffer_.data();
        }
        uac::packSamples(src, packBuffer_.data(), n * channels, sourceSubslot_);
        const uint8_t* p = packBuffer_.data();
        size_t left = n * frameBytes_;
        while (left > 0) {
            if (!streaming_) return -1;
            size_t space = ring_->writable();
            space -= space % frameBytes_;
            const size_t w = ring_->write(p, std::min(left, space));
            if (w > 0) {
                primed_ = true;
                p += w;
                left -= w;
                continue;
            }
            std::unique_lock<std::mutex> lock(spaceMutex_);
            spaceCv_.wait_for(lock, std::chrono::milliseconds(20));
        }
        done += n;
    }
    return long(done);
}

void UsbStreamer::drain() {
    primed_ = false;  // the ring running dry from here on is the end of the track, not an underrun
    while (streaming_ && ring_ && ring_->readable() > 0) {
        std::unique_lock<std::mutex> lock(spaceMutex_);
        spaceCv_.wait_for(lock, std::chrono::milliseconds(20));
    }
    // Let the transfers already queued on the bus play out.
    if (streaming_) std::this_thread::sleep_for(std::chrono::milliseconds(kTransfersInFlight * kTransferMillis + 20));
}

void UsbStreamer::stop() {
    streaming_ = false;
    for (libusb_transfer* t : transfers_) libusb_cancel_transfer(t);
    for (int i = 0; i < 200 && inFlight_ > 0; ++i) std::this_thread::sleep_for(std::chrono::milliseconds(5));
    if (inFlight_ > 0) {
        // Freeing transfers (or their buffers) that libusb still owns would crash; leak them instead.
        LOGE("%d transfers did not finish cancelling", inFlight_.load());
        new std::vector<std::vector<uint8_t>>(std::move(transferBuffers_));
        transfers_.clear();
    }
    for (libusb_transfer* t : transfers_) libusb_free_transfer(t);
    transfers_.clear();
    transferBuffers_.clear();
    if (format_ && handle_ && !disconnected_) {
        libusb_set_interface_alt_setting(handle_, format_->interfaceNumber, 0);
    }
    format_ = nullptr;
    primed_ = false;
}

void UsbStreamer::close() {
    stop();
    if (eventThread_.joinable()) {
        eventThreadRun_ = false;
        libusb_interrupt_event_handler(ctx_);
        eventThread_.join();
    }
    if (handle_) {
        // Hand the DAC back to Android's own USB audio driver.
        for (const uac::OutputFormat& f : device_.outputs) libusb_release_interface(handle_, f.interfaceNumber);
        if (device_.controlInterface >= 0) {
            libusb_release_interface(handle_, device_.controlInterface);
            if (!disconnected_) libusb_attach_kernel_driver(handle_, device_.controlInterface);
        }
        libusb_close(handle_);  // does not close the wrapped fd; Java closes the connection
        handle_ = nullptr;
    }
    if (ctx_) {
        libusb_exit(ctx_);
        ctx_ = nullptr;
    }
    featureUnit_ = nullptr;
}

int UsbStreamer::controlFeature(uint8_t request, uint8_t control, uint8_t channel, uint8_t* data, uint16_t length) {
    if (!handle_ || !featureUnit_) return LIBUSB_ERROR_NOT_SUPPORTED;
    const uint8_t type = (request & 0x80) ? kReqInInterface : kReqOutInterface;
    return libusb_control_transfer(handle_, type, request, uint16_t((control << 8) | channel),
                                   uint16_t((featureUnit_->unitId << 8) | device_.controlInterface), data, length,
                                   1000);
}

std::vector<uint8_t> UsbStreamer::volumeChannels() const {
    std::vector<uint8_t> channels;
    if (!featureUnit_) return channels;
    if (featureUnit_->masterVolume) {
        channels.push_back(0);
    } else {
        for (size_t i = 0; i < featureUnit_->channelVolume.size(); ++i) {
            if (featureUnit_->channelVolume[i]) channels.push_back(uint8_t(i + 1));
        }
    }
    return channels;
}

UsbStreamer::VolumeRange UsbStreamer::volumeRange() {
    VolumeRange r;
    const std::vector<uint8_t> channels = volumeChannels();
    if (channels.empty()) return r;
    uint8_t buf[2];
    const uint8_t ch = channels[0];
    if (controlFeature(kGetMin, kVolumeControl, ch, buf, 2) != 2) return r;
    r.min = le16(buf);
    if (controlFeature(kGetMax, kVolumeControl, ch, buf, 2) != 2) return r;
    r.max = le16(buf);
    if (controlFeature(kGetRes, kVolumeControl, ch, buf, 2) == 2) r.res = le16(buf);
    if (controlFeature(kGetCur, kVolumeControl, ch, buf, 2) == 2) r.cur = le16(buf);
    r.available = r.max > r.min;
    return r;
}

bool UsbStreamer::setVolume(int16_t value) {
    const VolumeRange range = volumeRange();
    if (!range.available) return false;
    value = std::clamp(value, range.min, range.max);
    uint8_t buf[2] = {uint8_t(value), uint8_t(uint16_t(value) >> 8)};
    bool ok = true;
    for (uint8_t ch : volumeChannels()) ok &= controlFeature(kSetCur, kVolumeControl, ch, buf, 2) == 2;
    return ok;
}

UsbStreamer::Stats UsbStreamer::stats() const {
    return Stats{framesSent_.load(), underruns_.load(), transferErrors_.load(), disconnected_.load()};
}
