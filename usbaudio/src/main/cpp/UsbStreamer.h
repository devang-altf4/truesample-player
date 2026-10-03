// Owns the libusb handle for one USB Audio Class 1 DAC and streams PCM to it
// with isochronous transfers, bypassing Android's audio stack.
#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "uac/Pcm.h"
#include "uac/UacDescriptors.h"

struct libusb_context;
struct libusb_device_handle;
struct libusb_transfer;
struct SRC_STATE_tag;

class UsbStreamer {
public:
    struct StreamInfo {
        int outputIndex = -1;
        uint32_t sampleRate = 0;  // source
        uint32_t outputRate = 0;  // what the DAC runs at
        uint32_t deviceRate = 0;  // read back from the DAC, 0 if it cannot report it
        uint32_t subslotBytes = 0;
        uint32_t bitResolution = 0;
        bool bitPerfect = false;
        bool resampled = false;
    };

    struct VolumeRange {
        bool available = false;
        int16_t min = 0, max = 0, res = 0, cur = 0;  // 1/256 dB units
    };

    struct Stats {
        uint64_t framesSent = 0;
        uint64_t underruns = 0;
        uint64_t transferErrors = 0;
        bool disconnected = false;
    };

    // How a source format would be played on this DAC.
    struct Plan {
        int outputIndex = -1;
        uint32_t outputRate = 0;
        uint32_t dacBits = 0;
        bool resampling = false;
        bool lossless = false;  // bit-perfect
    };

    // One way the DAC can run: a playback format at a sample rate.
    struct Mode {
        int outputIndex = -1;
        uint32_t sampleRate = 0;
        uint32_t bitResolution = 0;
        uint32_t subslotBytes = 0;
        uint32_t channels = 0;
    };

    ~UsbStreamer();

    // `fd` comes from UsbDeviceConnection.getFileDescriptor(); the caller keeps it
    // open until close(). The interfaces must already be claimed by Java.
    bool open(int fd, const uint8_t* rawDescriptors, size_t length, std::string& error);
    const uac::UacDevice& device() const { return device_; }

    // Every mode the DAC can actually stream (rate x format, within USB bandwidth).
    std::vector<Mode> modes() const;

    // resampleQuality: 0 = best, 1 = medium, 2 = fast (libsamplerate sinc converters).
    // fixedMode: play every song in this mode; nullptr picks the best mode per song.
    bool start(uint32_t sampleRate, uint32_t sourceBits, uint32_t channels, int resampleQuality, const Mode* fixedMode,
               StreamInfo& info, std::string& error);
    // Works out how start() would play this format, without touching the DAC.
    bool plan(uint32_t sampleRate, uint32_t sourceBits, uint32_t channels, const Mode* fixedMode, Plan& plan,
              std::string& error) const;
    // Blocks until all frames are queued. Returns frames queued, or -1 if the
    // stream stopped or the device went away.
    long write(const int32_t* interleaved, size_t frames);
    void drain();  // waits until queued audio has been sent
    void stop();
    void close();

    VolumeRange volumeRange();
    bool setVolume(int16_t value256);
    Stats stats() const;

private:
    static void onTransferDone(libusb_transfer* transfer);
    static void onFeedbackDone(libusb_transfer* transfer);
    void startFeedback(const uac::OutputFormat& format, uint32_t rate, uint32_t packetsPerSecond);
    void loadUac2Rates();
    std::vector<uint32_t> readClockRates(uint8_t clockSource);
    bool setUac2Rate(size_t outputIndex, uint32_t rate, uint32_t& deviceRate, std::string& error);
    void fillTransfer(libusb_transfer* transfer);
    bool push(const int32_t* interleaved, size_t frames);  // dither, pack, queue for USB
    bool resampleAndPush(size_t frames, bool endOfInput);  // frames already in resampleIn_
    void eventLoop();
    uint32_t packetsPerSecond(const uac::OutputFormat& format) const;
    bool fitsBandwidth(const uac::OutputFormat& format, uint32_t rate) const;
    int controlFeature(uint8_t request, uint8_t control, uint8_t channel, uint8_t* data, uint16_t length);
    std::vector<uint8_t> volumeChannels() const;

    libusb_context* ctx_ = nullptr;
    libusb_device_handle* handle_ = nullptr;
    uac::UacDevice device_;
    const uac::FeatureUnit* featureUnit_ = nullptr;
    std::vector<uint8_t> clockSources_;  // UAC2: clock source per output format
    bool highSpeed_ = false;

    // Async feedback (touched only on the libusb event thread once streaming)
    uint32_t unitsPerPacket_ = 1;
    uint32_t nominalUnitQ16_ = 0;
    int feedbackShift_ = 0;
    std::atomic<bool> feedbackSeen_{false};
    std::vector<std::pair<uint8_t, int16_t>> savedVolume_;  // DAC's own volume per channel, restored on close
    int savedMute_ = -1;
    VolumeRange rangeCache_;  // min/max/res never change, so read them once

    // Active stream
    const uac::OutputFormat* format_ = nullptr;
    std::unique_ptr<uac::PacketScheduler> scheduler_;
    std::unique_ptr<uac::RingBuffer> ring_;
    std::vector<libusb_transfer*> transfers_;
    std::vector<std::vector<uint8_t>> transferBuffers_;
    std::vector<uint8_t> packBuffer_;
    std::unique_ptr<uac::Ditherer> ditherer_;  // set only when the DAC has fewer bits than the source
    std::vector<int32_t> ditherBuffer_;
    uint32_t packetsPerTransfer_ = 0;
    uint32_t frameBytes_ = 0;
    uint32_t sourceSubslot_ = 0;
    uint32_t channels_ = 0;
    size_t maxPushFrames_ = 0;
    SRC_STATE_tag* resampler_ = nullptr;  // set only when the DAC lacks the source rate
    double resampleRatio_ = 1.0;
    std::vector<float> resampleIn_;
    std::vector<float> resampleOut_;
    std::vector<int32_t> resampledInt_;

    std::thread eventThread_;
    std::atomic<bool> eventThreadRun_{false};
    std::atomic<bool> streaming_{false};
    std::atomic<bool> primed_{false};  // stop counting underruns until the first audio arrives
    std::atomic<int> inFlight_{0};

    std::mutex spaceMutex_;
    std::condition_variable spaceCv_;

    std::atomic<uint64_t> framesSent_{0};
    std::atomic<uint64_t> underruns_{0};
    std::atomic<uint64_t> transferErrors_{0};
    std::atomic<bool> disconnected_{false};
};
