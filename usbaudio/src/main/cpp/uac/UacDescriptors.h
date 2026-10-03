// Parser for USB Audio Class descriptors (UAC1 fully, UAC2 detected only).
// Pure C++ with no Android or libusb dependency so it can be unit-tested.
#pragma once

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace uac {

enum class SyncType : uint8_t { None = 0, Async = 1, Adaptive = 2, Sync = 3 };

// One playback (OUT) alternate setting of an AudioStreaming interface.
struct OutputFormat {
    uint8_t interfaceNumber = 0;
    uint8_t altSetting = 0;
    uint8_t endpointAddress = 0;
    uint16_t maxPacketBytes = 0;  // per service interval, high-bandwidth multiplier applied
    uint8_t interval = 1;         // bInterval
    SyncType syncType = SyncType::None;
    uint8_t feedbackEndpoint = 0; // 0 when there is none
    uint8_t terminalLink = 0;
    uint16_t formatTag = 0;       // 1 = PCM
    uint8_t channels = 0;
    uint8_t subslotBytes = 0;
    uint8_t bitResolution = 0;
    bool continuousRates = false;
    uint32_t minRate = 0;
    uint32_t maxRate = 0;
    std::vector<uint32_t> rates;  // discrete rates when !continuousRates
    bool sampleRateControl = false;

    bool supportsRate(uint32_t rate) const;
    uint32_t frameBytes() const { return uint32_t(channels) * subslotBytes; }
};

struct FeatureUnit {
    uint8_t unitId = 0;
    uint8_t sourceId = 0;
    bool masterMute = false;
    bool masterVolume = false;
    std::vector<bool> channelVolume;  // [0] = channel 1
};

struct Unit {
    enum class Kind : uint8_t { InputTerminal, OutputTerminal, Feature, Other };
    Kind kind = Kind::Other;
    uint8_t id = 0;
    uint16_t terminalType = 0;
    uint8_t sourceId = 0;  // output terminal / feature unit only
};

struct UacDevice {
    int uacVersion = 0;               // 1 or 2; 0 when no AudioControl interface found
    int controlInterface = -1;
    std::vector<OutputFormat> outputs;
    std::vector<FeatureUnit> featureUnits;
    std::vector<Unit> units;
};

// Parses the raw descriptor stream (device + configuration descriptors, as
// returned by UsbDeviceConnection.getRawDescriptors()). Only the first
// configuration is read.
UacDevice parseDescriptors(const uint8_t* data, size_t length);

// Feature unit between the USB streaming terminal of `format` and the output
// terminal, or nullptr when the DAC has no usable volume control.
const FeatureUnit* findPlaybackFeatureUnit(const UacDevice& device, const OutputFormat& format);

std::string describe(const UacDevice& device);

}  // namespace uac
