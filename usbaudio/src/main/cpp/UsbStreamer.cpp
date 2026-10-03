#include "UsbStreamer.h"

#include <libusb.h>
#include <sys/resource.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cstring>

#include "Log.h"
#include "samplerate.h"

namespace {

void onLibusbLog(libusb_context*, enum libusb_log_level level, const char* message) {
    const size_t len = std::strlen(message);
    const int length = int(len > 0 && message[len - 1] == '\n' ? len - 1 : len);
    ualog::write(level == LIBUSB_LOG_LEVEL_ERROR ? ANDROID_LOG_ERROR : ANDROID_LOG_WARN, "libusb: %.*s", length,
                 message);
}

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
    libusb_set_log_cb(ctx_, onLibusbLog, LIBUSB_LOG_CB_CONTEXT);
    libusb_set_option(ctx_, LIBUSB_OPTION_LOG_LEVEL, LIBUSB_LOG_LEVEL_WARNING);
    rc = libusb_wrap_sys_device(ctx_, static_cast<intptr_t>(fd), &handle_);
    if (rc != LIBUSB_SUCCESS) {
        error = std::string("libusb could not open the DAC: ") + libusb_error_name(rc);
        close();
        return false;
    }

    // Java already force-claimed these (detaching Android's driver); this only
    // updates libusb's own bookkeeping so alt-setting changes are allowed.
    rc = libusb_claim_interface(handle_, device_.controlInterface);
    LOGI("claim interface %d: %s", device_.controlInterface, libusb_error_name(rc));
    for (const uac::OutputFormat& f : device_.outputs) {
        rc = libusb_claim_interface(handle_, f.interfaceNumber);
        LOGI("claim interface %u: %s", f.interfaceNumber, libusb_error_name(rc));
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

bool UsbStreamer::start(uint32_t sampleRate, uint32_t sourceBits, uint32_t channels, int resampleQuality,
                        StreamInfo& info, std::string& error) {
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

    auto fits = [&](const uac::OutputFormat& f, uint32_t rate) {
        if (f.formatTag != 1 || f.channels != channels || !f.supportsRate(rate)) return false;
        const uint32_t maxFrames = uac::PacketScheduler(rate, packetsPerSecond(f)).maxFrames();
        return maxFrames * f.frameBytes() <= f.maxPacketBytes;
    };

    // Play at the source rate when the DAC has it; otherwise resample to the best rate it does have.
    std::vector<uint32_t> candidates;
    for (const uac::OutputFormat& f : device_.outputs) {
        if (f.continuousRates) {
            candidates.insert(candidates.end(), {std::clamp(sampleRate, f.minRate, f.maxRate), f.minRate, f.maxRate});
        } else {
            candidates.insert(candidates.end(), f.rates.begin(), f.rates.end());
        }
    }
    candidates.erase(std::remove_if(candidates.begin(), candidates.end(),
                                    [&](uint32_t r) {
                                        return std::none_of(device_.outputs.begin(), device_.outputs.end(),
                                                            [&](const uac::OutputFormat& f) { return fits(f, r); });
                                    }),
                     candidates.end());
    const uint32_t outRate = uac::chooseOutputRate(sampleRate, candidates);
    if (outRate == 0) {
        char msg[160];
        std::snprintf(msg, sizeof msg, "The DAC has no %u-channel output.", channels);
        error = msg;
        return false;
    }
    const bool resampling = outRate != sampleRate;

    // Prefer the smallest format that holds the source losslessly; otherwise the
    // deepest one available, with dither.
    int best = -1;
    int deepest = -1;
    for (size_t i = 0; i < device_.outputs.size(); ++i) {
        const uac::OutputFormat& f = device_.outputs[i];
        if (!fits(f, outRate)) continue;
        if (deepest < 0 || f.bitResolution > device_.outputs[deepest].bitResolution) deepest = int(i);
        if (f.bitResolution < sourceBits) continue;
        if (best < 0 || f.subslotBytes < device_.outputs[best].subslotBytes) best = int(i);
    }
    const bool lossless = best >= 0 && !resampling;
    if (resampling || best < 0) best = deepest;

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
        uint8_t data[3] = {uint8_t(outRate), uint8_t(outRate >> 8), uint8_t(outRate >> 16)};
        rc = libusb_control_transfer(handle_, kReqOutEndpoint, kSetCur, kSamplingFreqControl << 8,
                                     f.endpointAddress, data, 3, 1000);
        if (rc < 0) LOGW("SET_CUR sample rate failed: %s", libusb_error_name(rc));
        uint8_t back[3] = {};
        rc = libusb_control_transfer(handle_, kReqInEndpoint, kGetCur, kSamplingFreqControl << 8,
                                     f.endpointAddress, back, 3, 1000);
        if (rc == 3) deviceRate = uint32_t(back[0] | (back[1] << 8) | (back[2] << 16));
    }
    if (deviceRate != 0 && deviceRate != outRate) {
        char msg[128];
        std::snprintf(msg, sizeof msg, "The DAC was asked for %u Hz but switched to %u Hz.", outRate, deviceRate);
        error = msg;
        libusb_set_interface_alt_setting(handle_, f.interfaceNumber, 0);
        format_ = nullptr;
        return false;
    }

    // The previous stream's playback thread has finished (see UsbAudioOutput), so its
    // resampler can be replaced safely here.
    if (resampler_) resampler_ = src_delete(resampler_);
    const size_t chunkFrames = sampleRate / 100;  // 10 ms of source audio per processing step
    if (resampling) {
        int err = 0;
        resampler_ = src_new(std::clamp(resampleQuality, 0, 2), int(channels), &err);
        if (!resampler_) {
            error = std::string("Could not create the resampler: ") + src_strerror(err);
            libusb_set_interface_alt_setting(handle_, f.interfaceNumber, 0);
            format_ = nullptr;
            return false;
        }
        resampleRatio_ = double(outRate) / sampleRate;
        maxPushFrames_ = size_t(double(chunkFrames) * resampleRatio_) + 64;
        resampleIn_.resize(chunkFrames * channels);
        resampleOut_.resize(maxPushFrames_ * channels);
        resampledInt_.resize(maxPushFrames_ * channels);
    } else {
        maxPushFrames_ = chunkFrames;
    }

    channels_ = channels;
    const uint32_t pps = packetsPerSecond(f);
    scheduler_ = std::make_unique<uac::PacketScheduler>(outRate, pps);
    frameBytes_ = f.frameBytes();
    sourceSubslot_ = f.subslotBytes;
    packetsPerTransfer_ = std::max<uint32_t>(1, pps * kTransferMillis / 1000);
    ring_ = std::make_unique<uac::RingBuffer>(size_t(outRate) * frameBytes_ * kRingMillis / 1000);
    packBuffer_.resize(maxPushFrames_ * frameBytes_);
    if (lossless || f.bitResolution >= 32) {
        ditherer_.reset();
    } else {
        ditherer_ = std::make_unique<uac::Ditherer>(f.bitResolution);
        ditherBuffer_.resize(maxPushFrames_ * f.channels);
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
    info.outputRate = outRate;
    info.deviceRate = deviceRate;
    info.subslotBytes = f.subslotBytes;
    info.bitResolution = f.bitResolution;
    info.bitPerfect = lossless;
    info.resampled = resampling;
    LOGI("streaming %u Hz %u-bit source -> %u Hz, alt %u (%u-bit in %u-byte slots)%s%s, %u packets/s", sampleRate,
         sourceBits, outRate, f.altSetting, f.bitResolution, f.subslotBytes, resampling ? ", resampled" : "",
         ditherer_ ? ", dithered" : "", pps);
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
    const size_t channels = channels_;
    const size_t chunkFrames = resampler_ ? resampleIn_.size() / channels : maxPushFrames_;
    for (size_t done = 0; done < frames;) {
        const size_t n = std::min(chunkFrames, frames - done);
        const int32_t* src = pcm + done * channels;
        bool ok;
        if (resampler_) {
            src_int_to_float_array(src, resampleIn_.data(), int(n * channels));
            ok = resampleAndPush(n, false);
        } else {
            ok = push(src, n);
        }
        if (!ok) return -1;
        done += n;
    }
    return long(frames);
}

bool UsbStreamer::resampleAndPush(size_t frames, bool endOfInput) {
    const long channels = long(channels_);
    SRC_DATA d{};
    d.data_in = resampleIn_.data();
    d.input_frames = long(frames);
    d.data_out = resampleOut_.data();
    d.output_frames = long(resampleOut_.size()) / channels;
    d.end_of_input = endOfInput ? 1 : 0;
    d.src_ratio = resampleRatio_;
    while (true) {
        const int err = src_process(resampler_, &d);
        if (err != 0) {
            LOGE("resampler failed: %s", src_strerror(err));
            return false;
        }
        if (d.output_frames_gen > 0) {
            src_float_to_int_array(resampleOut_.data(), resampledInt_.data(), int(d.output_frames_gen * channels));
            if (!push(resampledInt_.data(), size_t(d.output_frames_gen))) return false;
        }
        d.data_in += d.input_frames_used * channels;
        d.input_frames -= d.input_frames_used;
        const bool progressed = d.input_frames_used > 0 || d.output_frames_gen > 0;
        if (!progressed || (d.input_frames == 0 && !endOfInput)) return true;
    }
}

bool UsbStreamer::push(const int32_t* src, size_t frames) {
    const size_t channels = channels_;
    if (ditherer_) {
        ditherer_->process(src, ditherBuffer_.data(), frames * channels);
        src = ditherBuffer_.data();
    }
    uac::packSamples(src, packBuffer_.data(), frames * channels, sourceSubslot_);
    const uint8_t* p = packBuffer_.data();
    size_t left = frames * frameBytes_;
    while (left > 0) {
        if (!streaming_) return false;
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
    return true;
}

void UsbStreamer::drain() {
    if (resampler_ && streaming_) resampleAndPush(0, true);  // flush the resampler's tail
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
        for (const uac::OutputFormat& f : device_.outputs) {
            const int rc = libusb_release_interface(handle_, f.interfaceNumber);
            LOGI("release interface %u: %s", f.interfaceNumber, libusb_error_name(rc));
        }
        if (device_.controlInterface >= 0) {
            int rc = libusb_release_interface(handle_, device_.controlInterface);
            LOGI("release interface %d: %s", device_.controlInterface, libusb_error_name(rc));
            if (!disconnected_) {
                rc = libusb_attach_kernel_driver(handle_, device_.controlInterface);
                LOGI("reattach Android's driver to interface %d: %s", device_.controlInterface,
                     libusb_error_name(rc));
            }
        }
        libusb_close(handle_);  // does not close the wrapped fd; Java closes the connection
        handle_ = nullptr;
    }
    if (ctx_) {
        libusb_exit(ctx_);
        ctx_ = nullptr;
    }
    featureUnit_ = nullptr;
    if (resampler_) resampler_ = src_delete(resampler_);
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
