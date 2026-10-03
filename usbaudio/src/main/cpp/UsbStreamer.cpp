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
constexpr int kFeedbackTransfers = 2;
constexpr int kFeedbackPackets = 4;
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

// UAC2 class requests (bRequest; direction comes from bmRequestType)
constexpr uint8_t kUac2Cur = 0x01;
constexpr uint8_t kUac2Range = 0x02;
constexpr uint8_t kClockSelectorControl = 0x01;

constexpr uint8_t kReqOutInterface = 0x21;
constexpr uint8_t kReqInInterface = 0xA1;
constexpr uint8_t kReqOutEndpoint = 0x22;
constexpr uint8_t kReqInEndpoint = 0xA2;

int16_t le16(const uint8_t* p) { return int16_t(p[0] | (p[1] << 8)); }
uint32_t le32(const uint8_t* p) {
    return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24);
}

}  // namespace

UsbStreamer::~UsbStreamer() { close(); }

bool UsbStreamer::open(int fd, const uint8_t* raw, size_t length, std::string& error) {
    device_ = uac::parseDescriptors(raw, length);
    if (device_.uacVersion == 0) {
        error = "This USB device is not a USB Audio device.";
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

    highSpeed_ = libusb_get_device_speed(libusb_get_device(handle_)) >= LIBUSB_SPEED_HIGH;
    if (device_.uacVersion == 2) {
        loadUac2Rates();
        if (std::all_of(device_.outputs.begin(), device_.outputs.end(),
                        [](const uac::OutputFormat& f) { return f.rates.empty(); })) {
            error = "The DAC did not report which sample rates it supports.";
            close();
            return false;
        }
    }

    featureUnit_ = uac::findPlaybackFeatureUnit(device_, device_.outputs[0]);
    if (featureUnit_) {
        // Remember the DAC's own settings: they live in the DAC and would otherwise
        // stay changed (e.g. quieter) after we hand it back to Android.
        savedVolume_.clear();
        for (uint8_t ch : volumeChannels()) {
            uint8_t buf[2];
            if (controlFeature(kGetCur, kVolumeControl, ch, buf, 2) == 2) savedVolume_.push_back({ch, le16(buf)});
        }
        savedMute_ = -1;
        if (featureUnit_->masterMute) {
            uint8_t mute = 0;
            if (controlFeature(kGetCur, kMuteControl, 0, &mute, 1) == 1) savedMute_ = mute;
            uint8_t off = 0;
            controlFeature(kSetCur, kMuteControl, 0, &off, 1);
        }
        for (const auto& [ch, value] : savedVolume_) LOGI("DAC volume channel %u was %.1f dB", ch, value / 256.0);
        rangeCache_ = volumeRange();
        setVolume(kStartVolume);
    }
    LOGI("opened DAC:\n%s", uac::describe(device_).c_str());
    return true;
}

bool UsbStreamer::start(uint32_t sampleRate, uint32_t sourceBits, uint32_t channels, int resampleQuality,
                        const Mode* fixedMode, StreamInfo& info, std::string& error) {
    stop();
    if (!handle_) {
        error = "DAC is not open.";
        return false;
    }

    Plan p;
    if (!plan(sampleRate, sourceBits, channels, fixedMode, p, error)) return false;
    const int best = p.outputIndex;
    const uint32_t outRate = p.outputRate;
    const bool resampling = p.resampling;
    const bool lossless = p.lossless;

    format_ = &device_.outputs[best];
    const uac::OutputFormat& f = *format_;
    uint32_t deviceRate = 0;
    // UAC2: the rate lives on the clock and is changed while the interface is idle (alt 0).
    if (device_.uacVersion == 2 && !setUac2Rate(size_t(best), outRate, deviceRate, error)) {
        format_ = nullptr;
        return false;
    }
    int rc = libusb_set_interface_alt_setting(handle_, f.interfaceNumber, f.altSetting);
    if (rc != LIBUSB_SUCCESS) {
        error = std::string("Could not select the DAC's playback mode: ") + libusb_error_name(rc);
        format_ = nullptr;
        return false;
    }

    if (device_.uacVersion == 1 && (f.sampleRateControl || f.continuousRates || f.rates.size() > 1)) {
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
    frameBytes_ = f.frameBytes();
    // Async DACs may ask for a little more than nominal; never exceed what a packet carries.
    const uint32_t capFrames = std::max<uint32_t>(f.maxPacketBytes / frameBytes_, 1);
    scheduler_ = std::make_unique<uac::PacketScheduler>(outRate, pps, capFrames);
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

    const size_t transferBytes = size_t(packetsPerTransfer_) * capFrames * frameBytes_;
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
    if (f.syncType == uac::SyncType::Async) {
        if (f.feedbackEndpoint) {
            startFeedback(f, outRate, pps);
        } else {
            LOGW("async endpoint without a feedback endpoint (implicit feedback is not supported yet): "
                 "streaming at the nominal rate");
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

bool UsbStreamer::plan(uint32_t sampleRate, uint32_t sourceBits, uint32_t channels, const Mode* fixedMode,
                       Plan& out, std::string& error) const {
    if (!handle_) {
        error = "DAC is not open.";
        return false;
    }
    auto fits = [&](const uac::OutputFormat& f, uint32_t rate) {
        return f.channels == channels && fitsBandwidth(f, rate);
    };

    // A mode the user picked: convert every song to it.
    if (fixedMode) {
        const int i = fixedMode->outputIndex;
        if (i < 0 || size_t(i) >= device_.outputs.size() || !fits(device_.outputs[size_t(i)], fixedMode->sampleRate)) {
            char msg[160];
            std::snprintf(msg, sizeof msg, "The chosen output mode can't play this %u-channel song.", channels);
            error = msg;
            return false;
        }
        const uac::OutputFormat& f = device_.outputs[size_t(i)];
        out.outputIndex = i;
        out.outputRate = fixedMode->sampleRate;
        out.resampling = out.outputRate != sampleRate;
        out.lossless = !out.resampling && f.bitResolution >= sourceBits;
        out.dacBits = f.bitResolution;
        return true;
    }

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
    out.lossless = best >= 0 && !resampling;
    out.outputIndex = (resampling || best < 0) ? deepest : best;
    out.outputRate = outRate;
    out.resampling = resampling;
    out.dacBits = device_.outputs[out.outputIndex].bitResolution;
    return true;
}

bool UsbStreamer::fitsBandwidth(const uac::OutputFormat& f, uint32_t rate) const {
    if (f.formatTag != 1 || !f.supportsRate(rate)) return false;
    const uint32_t maxFrames = uac::PacketScheduler(rate, packetsPerSecond(f)).maxFrames();
    return maxFrames * f.frameBytes() <= f.maxPacketBytes;
}

std::vector<UsbStreamer::Mode> UsbStreamer::modes() const {
    static const uint32_t kCommonRates[] = {44100, 48000, 88200, 96000, 176400, 192000, 352800, 384000, 705600, 768000};
    std::vector<Mode> result;
    if (!handle_) return result;
    for (size_t i = 0; i < device_.outputs.size(); ++i) {
        const uac::OutputFormat& f = device_.outputs[i];
        std::vector<uint32_t> rates = f.rates;
        if (f.continuousRates) {
            for (uint32_t r : kCommonRates) {
                if (r >= f.minRate && r <= f.maxRate) rates.push_back(r);
            }
        }
        for (uint32_t r : rates) {
            if (fitsBandwidth(f, r)) result.push_back({int(i), r, f.bitResolution, f.subslotBytes, f.channels});
        }
    }
    return result;
}

void UsbStreamer::loadUac2Rates() {
    auto selectedPin = [this](uint8_t selector) -> int {
        uint8_t pin = 0;
        const int rc = libusb_control_transfer(handle_, kReqInInterface, kUac2Cur, kClockSelectorControl << 8,
                                               uint16_t((selector << 8) | device_.controlInterface), &pin, 1, 1000);
        return rc == 1 ? pin : 0;
    };
    clockSources_.assign(device_.outputs.size(), 0);
    std::vector<std::pair<uint8_t, std::vector<uint32_t>>> cache;
    for (size_t i = 0; i < device_.outputs.size(); ++i) {
        uac::OutputFormat& f = device_.outputs[i];
        const uint8_t source = uac::resolveClockSource(device_, f.clockId, selectedPin);
        clockSources_[i] = source;
        if (source == 0) {
            LOGW("alt %u: no clock source reachable from clock %u", f.altSetting, f.clockId);
            continue;
        }
        auto it = std::find_if(cache.begin(), cache.end(), [&](const auto& e) { return e.first == source; });
        if (it == cache.end()) {
            cache.push_back({source, readClockRates(source)});
            it = cache.end() - 1;
        }
        f.rates = it->second;
    }
}

std::vector<uint32_t> UsbStreamer::readClockRates(uint8_t source) {
    const uint16_t index = uint16_t((source << 8) | device_.controlInterface);
    // RANGE: first the number of sub-ranges, then all of them (as Linux does).
    uint8_t head[2] = {};
    int rc = libusb_control_transfer(handle_, kReqInInterface, kUac2Range, kSamplingFreqControl << 8, index, head,
                                     2, 1000);
    if (rc == 2) {
        const size_t ranges = size_t(head[0] | (head[1] << 8));
        std::vector<uint8_t> buf(2 + 12 * ranges);
        rc = libusb_control_transfer(handle_, kReqInInterface, kUac2Range, kSamplingFreqControl << 8, index,
                                     buf.data(), uint16_t(buf.size()), 1000);
        if (rc >= 2) {
            std::vector<uint32_t> rates = uac::ratesFromRange(buf.data(), size_t(rc));
            if (!rates.empty()) return rates;
        }
    }
    LOGW("clock %u: RANGE request failed (%s), using its current rate", source,
         rc < 0 ? libusb_error_name(rc) : "short reply");
    uint8_t cur[4] = {};
    rc = libusb_control_transfer(handle_, kReqInInterface, kUac2Cur, kSamplingFreqControl << 8, index, cur, 4, 1000);
    if (rc == 4 && le32(cur) != 0) return {le32(cur)};
    return {};
}

bool UsbStreamer::setUac2Rate(size_t outputIndex, uint32_t rate, uint32_t& deviceRate, std::string& error) {
    const uint8_t source = outputIndex < clockSources_.size() ? clockSources_[outputIndex] : 0;
    if (source == 0) {
        error = "The DAC's clock could not be found.";
        return false;
    }
    const uint16_t index = uint16_t((source << 8) | device_.controlInterface);
    auto readRate = [&]() -> uint32_t {
        uint8_t cur[4] = {};
        const int rc = libusb_control_transfer(handle_, kReqInInterface, kUac2Cur, kSamplingFreqControl << 8, index,
                                               cur, 4, 1000);
        return rc == 4 ? le32(cur) : 0;
    };
    uint32_t current = readRate();
    if (current != rate) {
        const uac::Clock* clock = uac::findClock(device_, source);
        if (clock && !clock->frequencyWritable && current != 0) {
            char msg[128];
            std::snprintf(msg, sizeof msg, "The DAC's clock is fixed at %u Hz.", current);
            error = msg;
            return false;
        }
        uint8_t data[4] = {uint8_t(rate), uint8_t(rate >> 8), uint8_t(rate >> 16), uint8_t(rate >> 24)};
        const int rc = libusb_control_transfer(handle_, kReqOutInterface, kUac2Cur, kSamplingFreqControl << 8, index,
                                               data, 4, 1000);
        if (rc < 0) LOGW("clock %u: setting %u Hz failed: %s", source, rate, libusb_error_name(rc));
        std::this_thread::sleep_for(std::chrono::milliseconds(20));  // let the DAC's clock relock
        current = readRate();
    }
    deviceRate = current;
    if (current != 0 && current != rate) {
        char msg[128];
        std::snprintf(msg, sizeof msg, "The DAC was asked for %u Hz but its clock runs at %u Hz.", rate, current);
        error = msg;
        return false;
    }
    LOGI("clock %u at %u Hz", source, current);
    return true;
}

void UsbStreamer::startFeedback(const uac::OutputFormat& f, uint32_t rate, uint32_t pps) {
    // Feedback is per (micro)frame; a data packet may span several when bInterval > 1.
    const uint32_t unitsPerSecond = highSpeed_ ? 8000 : 1000;
    unitsPerPacket_ = std::max<uint32_t>(1, unitsPerSecond / pps);
    nominalUnitQ16_ = uint32_t((uint64_t(rate) << 16) / unitsPerSecond);
    feedbackShift_ = uac::kUnknownShift;
    feedbackSeen_ = false;
    const int packetSize = f.feedbackMaxPacket ? f.feedbackMaxPacket : (highSpeed_ ? 4 : 3);
    for (int i = 0; i < kFeedbackTransfers; ++i) {
        transferBuffers_.emplace_back(size_t(packetSize) * kFeedbackPackets);
        libusb_transfer* t = libusb_alloc_transfer(kFeedbackPackets);
        libusb_fill_iso_transfer(t, handle_, f.feedbackEndpoint, transferBuffers_.back().data(),
                                 packetSize * kFeedbackPackets, kFeedbackPackets, &UsbStreamer::onFeedbackDone, this,
                                 0);
        libusb_set_iso_packet_lengths(t, unsigned(packetSize));
        transfers_.push_back(t);
        ++inFlight_;
        const int rc = libusb_submit_transfer(t);
        if (rc != LIBUSB_SUCCESS) {
            --inFlight_;
            LOGW("feedback endpoint 0x%02x: submit failed (%s), streaming at the nominal rate", f.feedbackEndpoint,
                 libusb_error_name(rc));
            return;
        }
    }
    LOGI("async: listening to feedback endpoint 0x%02x", f.feedbackEndpoint);
}

void UsbStreamer::onFeedbackDone(libusb_transfer* t) {
    auto* self = static_cast<UsbStreamer*>(t->user_data);
    bool resubmit = false;
    switch (t->status) {
        case LIBUSB_TRANSFER_COMPLETED:
            for (int i = 0; i < t->num_iso_packets; ++i) {
                const libusb_iso_packet_descriptor& p = t->iso_packet_desc[i];
                if (p.status != LIBUSB_TRANSFER_COMPLETED || p.actual_length < 3) continue;
                const uint32_t perUnit = uac::decodeFeedback(libusb_get_iso_packet_buffer(t, unsigned(i)),
                                                             p.actual_length, self->highSpeed_, self->nominalUnitQ16_,
                                                             self->feedbackShift_);
                if (perUnit == 0) continue;
                self->scheduler_->setFeedback(perUnit * self->unitsPerPacket_);
                if (!self->feedbackSeen_.exchange(true)) {
                    LOGI("async feedback: %.4f frames per packet (nominal %.4f, format shift %d)",
                         perUnit * self->unitsPerPacket_ / 65536.0,
                         self->nominalUnitQ16_ * self->unitsPerPacket_ / 65536.0, self->feedbackShift_);
                }
            }
            resubmit = self->streaming_;
            break;
        case LIBUSB_TRANSFER_NO_DEVICE:
            self->disconnected_ = true;
            self->streaming_ = false;
            break;
        case LIBUSB_TRANSFER_CANCELLED:
            break;
        default:
            resubmit = self->streaming_;
            break;
    }
    if (resubmit && libusb_submit_transfer(t) == LIBUSB_SUCCESS) return;
    --self->inFlight_;
    self->spaceCv_.notify_all();
}

uint32_t UsbStreamer::packetsPerSecond(const uac::OutputFormat& f) const {
    const int speed = libusb_get_device_speed(libusb_get_device(handle_));
    const uint32_t shift = std::clamp<uint32_t>(f.interval, 1, 16) - 1;
    return (speed >= LIBUSB_SPEED_HIGH ? 8000u : 1000u) >> std::min<uint32_t>(shift, 3);
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
        if (featureUnit_ && !disconnected_) {
            for (const auto& [ch, value] : savedVolume_) {
                uint8_t buf[2] = {uint8_t(value), uint8_t(uint16_t(value) >> 8)};
                const int rc = controlFeature(kSetCur, kVolumeControl, ch, buf, 2);
                LOGI("restore DAC volume channel %u to %.1f dB: %s", ch, value / 256.0,
                     rc == 2 ? "ok" : libusb_error_name(rc));
            }
            if (savedMute_ >= 0) {
                uint8_t mute = uint8_t(savedMute_);
                controlFeature(kSetCur, kMuteControl, 0, &mute, 1);
            }
        }
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
    if (device_.uacVersion == 2 && request == kGetCur) request = kUac2Cur;  // UAC2 GET CUR is 0x01 + IN
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
    if (device_.uacVersion == 2) {
        // RANGE: wNumSubRanges, then wMIN wMAX wRES (first sub-range is enough, as in Linux).
        uint8_t range[8] = {};
        const int rc = libusb_control_transfer(handle_, kReqInInterface, kUac2Range, uint16_t(kVolumeControl << 8 | ch),
                                               uint16_t((featureUnit_->unitId << 8) | device_.controlInterface),
                                               range, sizeof range, 1000);
        if (rc < 8) return r;
        r.min = le16(range + 2);
        r.max = le16(range + 4);
        r.res = le16(range + 6);
        if (controlFeature(kGetCur, kVolumeControl, ch, buf, 2) == 2) r.cur = le16(buf);
        r.available = r.max > r.min;
        return r;
    }
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
    // The range is cached, so moving the slider costs no extra USB round trips.
    const VolumeRange range = rangeCache_.available ? rangeCache_ : volumeRange();
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
