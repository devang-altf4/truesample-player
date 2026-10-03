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

class UsbStreamer {
public:
    struct StreamInfo {
        int outputIndex = -1;
        uint32_t sampleRate = 0;
        uint32_t deviceRate = 0;  // read back from the DAC, 0 if it cannot report it
        uint32_t subslotBytes = 0;
        uint32_t bitResolution = 0;
        bool bitPerfect = false;
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

    ~UsbStreamer();

    // `fd` comes from UsbDeviceConnection.getFileDescriptor(); the caller keeps it
    // open until close(). The interfaces must already be claimed by Java.
    bool open(int fd, const uint8_t* rawDescriptors, size_t length, std::string& error);
    const uac::UacDevice& device() const { return device_; }

    bool start(uint32_t sampleRate, uint32_t sourceBits, uint32_t channels, StreamInfo& info, std::string& error);
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
    void fillTransfer(libusb_transfer* transfer);
    void eventLoop();
    int controlFeature(uint8_t request, uint8_t control, uint8_t channel, uint8_t* data, uint16_t length);
    std::vector<uint8_t> volumeChannels() const;

    libusb_context* ctx_ = nullptr;
    libusb_device_handle* handle_ = nullptr;
    uac::UacDevice device_;
    const uac::FeatureUnit* featureUnit_ = nullptr;

    // Active stream
    const uac::OutputFormat* format_ = nullptr;
    std::unique_ptr<uac::PacketScheduler> scheduler_;
    std::unique_ptr<uac::RingBuffer> ring_;
    std::vector<libusb_transfer*> transfers_;
    std::vector<std::vector<uint8_t>> transferBuffers_;
    std::vector<uint8_t> packBuffer_;
    uint32_t packetsPerTransfer_ = 0;
    uint32_t frameBytes_ = 0;
    uint32_t sourceSubslot_ = 0;

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
