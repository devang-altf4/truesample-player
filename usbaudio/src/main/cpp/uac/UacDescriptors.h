// Parser for USB Audio Class 1 and 2 descriptors.
// Pure C++ with no Android or libusb dependency so it can be unit-tested.
#pragma once

#include <cstddef>
#include <cstdint>
#include <functional>
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
    uint8_t feedbackEndpoint = 0;  // explicit feedback (IN) endpoint for async sync, 0 when there is none
    uint16_t feedbackMaxPacket = 0;
    uint8_t feedbackInterval = 0;
    uint8_t terminalLink = 0;
    uint16_t formatTag = 0;       // 1 = PCM
    uint8_t channels = 0;
    uint8_t subslotBytes = 0;
    uint8_t bitResolution = 0;
    bool continuousRates = false;
    uint32_t minRate = 0;
    uint32_t maxRate = 0;
    std::vector<uint32_t> rates;  // discrete rates when !continuousRates (UAC2: filled from the clock)
    bool sampleRateControl = false;  // UAC1: rate is set on the endpoint
    uint8_t clockId = 0;             // UAC2: clock entity feeding this format's terminal

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
    uint8_t clockId = 0;   // UAC2 terminals only
};

// UAC2 clock entities. Rates are read from (and set on) a clock source.
struct Clock {
    enum class Kind : uint8_t { Source, Selector, Multiplier };
    Kind kind = Kind::Source;
    uint8_t id = 0;
    std::vector<uint8_t> inputs;  // selector pins / multiplier source
    bool frequencyWritable = false;  // source: host may set the sample rate
    bool selectorWritable = false;   // selector: host may change the pin
};

struct UacDevice {
    int uacVersion = 0;               // 1 or 2; 0 when no AudioControl interface found
    int controlInterface = -1;
    std::vector<OutputFormat> outputs;
    std::vector<FeatureUnit> featureUnits;
    std::vector<Unit> units;
    std::vector<Clock> clocks;
};

// Parses the raw descriptor stream (device + configuration descriptors, as
// returned by UsbDeviceConnection.getRawDescriptors()). Only the first
// configuration is read.
UacDevice parseDescriptors(const uint8_t* data, size_t length);

// Feature unit between the USB streaming terminal of `format` and the output
// terminal, or nullptr when the DAC has no usable volume control.
const FeatureUnit* findPlaybackFeatureUnit(const UacDevice& device, const OutputFormat& format);

const Clock* findClock(const UacDevice& device, uint8_t id);

// Follows selectors and multipliers from `clockId` to the clock source. `selectedPin`
// returns a selector's current 1-based pin (or 0 if unknown, which means pin 1).
// Returns 0 if no clock source is reachable.
uint8_t resolveClockSource(const UacDevice& device, uint8_t clockId,
                           const std::function<int(uint8_t selectorId)>& selectedPin);

// Sample rates from a UAC2 RANGE reply (wNumSubRanges then dMIN/dMAX/dRES per range).
// Continuous ranges are expanded to the common audio rates they contain.
std::vector<uint32_t> ratesFromRange(const uint8_t* data, size_t length);

// Decodes an explicit feedback value into frames per packet as 16.16 fixed point,
// detecting the device's format (10.14 vs 16.16, some devices are off by a shift)
// like Linux's snd-usb-audio. `formatShift` holds the detected shift between calls
// (initialise it to kUnknownShift). Returns 0 for values that are not plausible.
constexpr int kUnknownShift = -100;
uint32_t decodeFeedback(const uint8_t* data, size_t length, bool highSpeed, uint32_t nominalQ16, int& formatShift);

std::string describe(const UacDevice& device);

}  // namespace uac
