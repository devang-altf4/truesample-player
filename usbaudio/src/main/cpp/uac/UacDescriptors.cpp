#include "UacDescriptors.h"

#include <algorithm>
#include <cstdio>

namespace uac {
namespace {

constexpr uint8_t kDescInterface = 0x04;
constexpr uint8_t kDescEndpoint = 0x05;
constexpr uint8_t kDescConfiguration = 0x02;
constexpr uint8_t kDescCsInterface = 0x24;
constexpr uint8_t kDescCsEndpoint = 0x25;

constexpr uint8_t kClassAudio = 0x01;
constexpr uint8_t kSubclassControl = 0x01;
constexpr uint8_t kSubclassStreaming = 0x02;
constexpr uint8_t kProtocolUac2 = 0x20;

// AudioControl descriptor subtypes (UAC1 and UAC2 share the first ones)
constexpr uint8_t kAcInputTerminal = 0x02;
constexpr uint8_t kAcOutputTerminal = 0x03;
constexpr uint8_t kAcFeatureUnit = 0x06;
constexpr uint8_t kAc2ClockSource = 0x0A;
constexpr uint8_t kAc2ClockSelector = 0x0B;
constexpr uint8_t kAc2ClockMultiplier = 0x0C;

// AudioStreaming descriptor subtypes
constexpr uint8_t kAsGeneral = 0x01;
constexpr uint8_t kAsFormatType = 0x02;
constexpr uint8_t kFormatTypeI = 0x01;
constexpr uint8_t kEpGeneral = 0x01;

constexpr uint32_t kCommonRates[] = {32000,  44100,  48000,  88200,  96000, 176400,
                                     192000, 352800, 384000, 705600, 768000};

uint16_t u16(const uint8_t* p) { return uint16_t(p[0] | (p[1] << 8)); }
uint32_t u24(const uint8_t* p) { return uint32_t(p[0] | (p[1] << 8) | (p[2] << 16)); }
uint32_t u32(const uint8_t* p) { return uint32_t(p[0]) | (uint32_t(p[1]) << 8) | (uint32_t(p[2]) << 16) | (uint32_t(p[3]) << 24); }

struct StreamingAlt {
    OutputFormat format;
    bool hasFormat = false;
    bool hasOutEndpoint = false;
};

void parseUac1Control(UacDevice& dev, const uint8_t* d, uint8_t len) {
    const uint8_t subtype = d[2];
    if (subtype == kAcInputTerminal && len >= 6) {
        dev.units.push_back({Unit::Kind::InputTerminal, d[3], u16(d + 4), 0, 0});
    } else if (subtype == kAcOutputTerminal && len >= 8) {
        dev.units.push_back({Unit::Kind::OutputTerminal, d[3], u16(d + 4), d[7], 0});
    } else if (subtype == kAcFeatureUnit && len >= 7) {
        FeatureUnit fu;
        fu.unitId = d[3];
        fu.sourceId = d[4];
        const uint8_t controlSize = d[5];
        if (controlSize > 0) {
            const size_t entries = (len - 7) / controlSize;  // master + one per channel
            for (size_t i = 0; i < entries; ++i) {
                const uint8_t controls = d[6 + i * controlSize];
                if (i == 0) {
                    fu.masterMute = controls & 0x01;
                    fu.masterVolume = controls & 0x02;
                } else {
                    fu.channelVolume.push_back(controls & 0x02);
                }
            }
        }
        dev.featureUnits.push_back(fu);
        dev.units.push_back({Unit::Kind::Feature, fu.unitId, 0, fu.sourceId, 0});
    } else if (len >= 4 && subtype > kAcInputTerminal) {
        dev.units.push_back({Unit::Kind::Other, d[3], 0, 0, 0});
    }
}

// UAC2 controls are 2-bit fields; 0b11 means the host may change the value.
bool writable(uint32_t controls, int field) { return ((controls >> (2 * field)) & 0x3) == 0x3; }

void parseUac2Control(UacDevice& dev, const uint8_t* d, uint8_t len) {
    const uint8_t subtype = d[2];
    if (subtype == kAcInputTerminal && len >= 17) {
        dev.units.push_back({Unit::Kind::InputTerminal, d[3], u16(d + 4), 0, d[7]});
    } else if (subtype == kAcOutputTerminal && len >= 12) {
        dev.units.push_back({Unit::Kind::OutputTerminal, d[3], u16(d + 4), d[7], d[8]});
    } else if (subtype == kAcFeatureUnit && len >= 10) {
        FeatureUnit fu;
        fu.unitId = d[3];
        fu.sourceId = d[4];
        const size_t entries = (len - 6) / 4;  // master + one per channel, 4 bytes each
        for (size_t i = 0; i < entries; ++i) {
            const uint32_t controls = u32(d + 5 + 4 * i);
            if (i == 0) {
                fu.masterMute = writable(controls, 0);
                fu.masterVolume = writable(controls, 1);
            } else {
                fu.channelVolume.push_back(writable(controls, 1));
            }
        }
        dev.featureUnits.push_back(fu);
        dev.units.push_back({Unit::Kind::Feature, fu.unitId, 0, fu.sourceId, 0});
    } else if (subtype == kAc2ClockSource && len >= 8) {
        Clock c;
        c.kind = Clock::Kind::Source;
        c.id = d[3];
        c.frequencyWritable = writable(d[5], 0);
        dev.clocks.push_back(c);
    } else if (subtype == kAc2ClockSelector && len >= 7) {
        Clock c;
        c.kind = Clock::Kind::Selector;
        c.id = d[3];
        const uint8_t pins = d[4];
        for (uint8_t i = 0; i < pins && 5u + i < len; ++i) c.inputs.push_back(d[5 + i]);
        if (5u + pins < len) c.selectorWritable = writable(d[5 + pins], 0);
        dev.clocks.push_back(c);
    } else if (subtype == kAc2ClockMultiplier && len >= 7) {
        Clock c;
        c.kind = Clock::Kind::Multiplier;
        c.id = d[3];
        c.inputs.push_back(d[4]);
        dev.clocks.push_back(c);
    } else if (len >= 4 && subtype > kAcInputTerminal) {
        dev.units.push_back({Unit::Kind::Other, d[3], 0, 0, 0});
    }
}

}  // namespace

bool OutputFormat::supportsRate(uint32_t rate) const {
    if (continuousRates) return rate >= minRate && rate <= maxRate;
    return std::find(rates.begin(), rates.end(), rate) != rates.end();
}

UacDevice parseDescriptors(const uint8_t* data, size_t length) {
    UacDevice dev;
    enum class Iface { None, Control, Streaming, Other } iface = Iface::None;
    StreamingAlt alt;
    int configsSeen = 0;

    auto finishAlt = [&]() {
        if (iface == Iface::Streaming && alt.hasFormat && alt.hasOutEndpoint && alt.format.altSetting != 0) {
            dev.outputs.push_back(alt.format);
        }
        alt = StreamingAlt{};
    };

    size_t pos = 0;
    while (pos + 2 <= length) {
        const uint8_t* d = data + pos;
        const uint8_t len = d[0];
        const uint8_t type = d[1];
        if (len < 2 || pos + len > length) break;  // malformed: stop rather than read past the end
        pos += len;

        if (type == kDescConfiguration) {
            if (++configsSeen > 1) break;
            continue;
        }

        if (type == kDescInterface && len >= 9) {
            finishAlt();
            const uint8_t cls = d[5], sub = d[6], proto = d[7];
            if (cls == kClassAudio && sub == kSubclassControl) {
                iface = Iface::Control;
                dev.controlInterface = d[2];
                dev.uacVersion = proto == kProtocolUac2 ? 2 : 1;
            } else if (cls == kClassAudio && sub == kSubclassStreaming) {
                iface = Iface::Streaming;
                alt.format.interfaceNumber = d[2];
                alt.format.altSetting = d[3];
            } else {
                iface = Iface::Other;
            }
            continue;
        }

        if (type == kDescCsInterface && len >= 3 && iface == Iface::Control) {
            if (dev.uacVersion == 1) parseUac1Control(dev, d, len);
            if (dev.uacVersion == 2) parseUac2Control(dev, d, len);
            continue;
        }

        if (type == kDescCsInterface && len >= 3 && iface == Iface::Streaming) {
            const uint8_t subtype = d[2];
            OutputFormat& f = alt.format;
            if (dev.uacVersion == 1) {
                if (subtype == kAsGeneral && len >= 7) {
                    f.terminalLink = d[3];
                    f.formatTag = u16(d + 5);
                } else if (subtype == kAsFormatType && len >= 8 && d[3] == kFormatTypeI) {
                    f.channels = d[4];
                    f.subslotBytes = d[5];
                    f.bitResolution = d[6];
                    const uint8_t freqCount = d[7];
                    if (freqCount == 0 && len >= 14) {
                        f.continuousRates = true;
                        f.minRate = u24(d + 8);
                        f.maxRate = u24(d + 11);
                    } else {
                        for (uint8_t i = 0; i < freqCount && 8u + 3u * (i + 1u) <= len; ++i) {
                            f.rates.push_back(u24(d + 8 + 3 * i));
                        }
                    }
                    alt.hasFormat = true;
                }
            } else if (dev.uacVersion == 2) {
                if (subtype == kAsGeneral && len >= 16) {
                    f.terminalLink = d[3];
                    // bmFormats bit 0 is PCM; DSD/raw alternate settings are skipped for now.
                    f.formatTag = (d[5] == kFormatTypeI && (u32(d + 6) & 0x1)) ? 1 : 0;
                    f.channels = d[10];
                } else if (subtype == kAsFormatType && len >= 6 && d[3] == kFormatTypeI) {
                    f.subslotBytes = d[4];
                    f.bitResolution = d[5];
                    alt.hasFormat = true;  // rates come from the clock source at runtime
                }
            }
            continue;
        }

        if (type == kDescEndpoint && len >= 7 && iface == Iface::Streaming) {
            const uint8_t address = d[2];
            const uint8_t attributes = d[3];
            const bool isochronous = (attributes & 0x03) == 0x01;
            const bool isFeedback = ((attributes >> 4) & 0x03) == 0x01;
            const uint16_t maxPacket = u16(d + 4);
            const uint16_t maxPacketBytes = uint16_t((maxPacket & 0x7FF) * (1 + ((maxPacket >> 11) & 0x03)));
            OutputFormat& f = alt.format;
            if (isochronous && !(address & 0x80) && !isFeedback) {
                f.endpointAddress = address;
                f.maxPacketBytes = maxPacketBytes;
                f.interval = d[6];
                f.syncType = static_cast<SyncType>((attributes >> 2) & 0x03);
                // UAC1 points at its feedback endpoint; UAC2 just lists it next.
                if (len >= 9 && f.syncType == SyncType::Async && d[8] != 0) f.feedbackEndpoint = d[8];
                alt.hasOutEndpoint = true;
            } else if (isochronous && (address & 0x80) && (isFeedback || address == f.feedbackEndpoint)) {
                // UAC1 sync endpoints predate the "feedback" usage bits; match them by address.
                f.feedbackEndpoint = address;
                f.feedbackMaxPacket = maxPacketBytes;
                f.feedbackInterval = d[6];
            }
            continue;
        }

        if (type == kDescCsEndpoint && len >= 4 && iface == Iface::Streaming && alt.hasOutEndpoint) {
            if (d[2] == kEpGeneral && dev.uacVersion == 1) alt.format.sampleRateControl = d[3] & 0x01;
            continue;
        }
    }
    finishAlt();

    // UAC2: each format's rate comes from the clock feeding its USB streaming terminal.
    if (dev.uacVersion == 2) {
        for (OutputFormat& f : dev.outputs) {
            for (const Unit& u : dev.units) {
                if (u.kind == Unit::Kind::InputTerminal && u.id == f.terminalLink) f.clockId = u.clockId;
            }
        }
    }
    return dev;
}

const FeatureUnit* findPlaybackFeatureUnit(const UacDevice& device, const OutputFormat& format) {
    auto findUnit = [&](uint8_t id) -> const Unit* {
        for (const Unit& u : device.units) if (u.id == id) return &u;
        return nullptr;
    };
    auto findFeature = [&](uint8_t id) -> const FeatureUnit* {
        for (const FeatureUnit& f : device.featureUnits) if (f.unitId == id) return &f;
        return nullptr;
    };
    auto hasVolume = [](const FeatureUnit* f) {
        return f && (f->masterVolume ||
                     std::any_of(f->channelVolume.begin(), f->channelVolume.end(), [](bool v) { return v; }));
    };

    // Walk back from every output terminal towards our streaming input terminal.
    for (const Unit& out : device.units) {
        if (out.kind != Unit::Kind::OutputTerminal) continue;
        const FeatureUnit* candidate = nullptr;
        uint8_t id = out.sourceId;
        for (int hops = 0; hops < 16; ++hops) {
            const Unit* u = findUnit(id);
            if (!u) break;
            if (u->kind == Unit::Kind::Feature) {
                if (!candidate && hasVolume(findFeature(u->id))) candidate = findFeature(u->id);
                id = u->sourceId;
                continue;
            }
            if (u->kind == Unit::Kind::InputTerminal && u->id == format.terminalLink && candidate) return candidate;
            break;  // mixer/selector units are not followed yet
        }
    }
    // Fallback: a feature unit fed directly by our streaming terminal.
    for (const FeatureUnit& f : device.featureUnits) {
        if (f.sourceId == format.terminalLink && hasVolume(&f)) return &f;
    }
    return nullptr;
}

const Clock* findClock(const UacDevice& device, uint8_t id) {
    for (const Clock& c : device.clocks) if (c.id == id) return &c;
    return nullptr;
}

uint8_t resolveClockSource(const UacDevice& device, uint8_t clockId,
                           const std::function<int(uint8_t selectorId)>& selectedPin) {
    for (int hops = 0; hops < 8; ++hops) {
        const Clock* c = findClock(device, clockId);
        if (!c) return 0;
        if (c->kind == Clock::Kind::Source) return c->id;
        if (c->inputs.empty()) return 0;
        size_t index = 0;
        if (c->kind == Clock::Kind::Selector && selectedPin) {
            const int pin = selectedPin(c->id);
            if (pin >= 1 && size_t(pin) <= c->inputs.size()) index = size_t(pin) - 1;
        }
        clockId = c->inputs[index];
    }
    return 0;
}

std::vector<uint32_t> ratesFromRange(const uint8_t* data, size_t length) {
    std::vector<uint32_t> rates;
    if (length < 2) return rates;
    const uint16_t ranges = u16(data);
    for (uint16_t i = 0; i < ranges && 2u + 12u * (i + 1u) <= length; ++i) {
        const uint8_t* r = data + 2 + 12 * i;
        const uint32_t lo = u32(r), hi = u32(r + 4), step = u32(r + 8);
        if (lo == hi) {
            rates.push_back(lo);
            continue;
        }
        for (uint32_t rate : kCommonRates) {
            if (rate >= lo && rate <= hi && (step == 0 || (rate - lo) % step == 0)) rates.push_back(rate);
        }
    }
    std::sort(rates.begin(), rates.end());
    rates.erase(std::unique(rates.begin(), rates.end()), rates.end());
    rates.erase(std::remove(rates.begin(), rates.end(), 0u), rates.end());
    return rates;
}

uint32_t decodeFeedback(const uint8_t* data, size_t length, bool highSpeed, uint32_t nominalQ16, int& formatShift) {
    if (length < 3 || nominalQ16 == 0) return 0;
    uint64_t f = length >= 4 ? (u32(data) & 0x0FFFFFFF) : u24(data);
    if (f == 0) return 0;  // devices send 0 until their clock has locked
    if (!highSpeed) f <<= 2;  // full speed sends 10.14; work in 16.16
    const uint64_t nominal = nominalQ16;
    if (formatShift == kUnknownShift) {
        // Some devices use the other speed's format; find the shift that lands near nominal.
        int shift = 0;
        while (f < nominal - nominal / 4 && shift < 8) {
            f <<= 1;
            ++shift;
        }
        while (f > nominal + nominal / 2 && shift > -8) {
            f >>= 1;
            --shift;
        }
        formatShift = shift;
    } else if (formatShift >= 0) {
        f <<= formatShift;
    } else {
        f >>= -formatShift;
    }
    if (f < nominal - nominal / 8 || f > nominal + nominal / 8) return 0;  // implausible: ignore
    return uint32_t(f);
}

std::string describe(const UacDevice& device) {
    std::string s;
    char line[256];
    std::snprintf(line, sizeof line, "USB Audio Class %d, AudioControl interface %d\n",
                  device.uacVersion, device.controlInterface);
    s += line;
    static const char* kSync[] = {"none", "async", "adaptive", "sync"};
    for (const OutputFormat& f : device.outputs) {
        std::snprintf(line, sizeof line,
                      "OUT iface %u alt %u: EP 0x%02x %s, %u ch, %u-bit in %u-byte slots, maxPacket %u B, bInterval %u%s\n",
                      f.interfaceNumber, f.altSetting, f.endpointAddress, kSync[static_cast<int>(f.syncType)],
                      f.channels, f.bitResolution, f.subslotBytes, f.maxPacketBytes, f.interval,
                      f.sampleRateControl ? ", rate control" : "");
        s += line;
        if (f.feedbackEndpoint) {
            std::snprintf(line, sizeof line, "    feedback EP 0x%02x, %u B, bInterval %u\n", f.feedbackEndpoint,
                          f.feedbackMaxPacket, f.feedbackInterval);
            s += line;
        }
        if (f.clockId) {
            std::snprintf(line, sizeof line, "    clock %u\n", f.clockId);
            s += line;
        }
        if (f.continuousRates) {
            std::snprintf(line, sizeof line, "    rates: %u .. %u Hz (continuous)\n", f.minRate, f.maxRate);
            s += line;
        } else {
            s += "    rates:";
            for (uint32_t r : f.rates) {
                std::snprintf(line, sizeof line, " %u", r);
                s += line;
            }
            s += " Hz\n";
        }
    }
    for (const FeatureUnit& f : device.featureUnits) {
        std::snprintf(line, sizeof line, "Feature unit %u (source %u): master volume %s, mute %s, %zu channel(s)\n",
                      f.unitId, f.sourceId, f.masterVolume ? "yes" : "no", f.masterMute ? "yes" : "no",
                      f.channelVolume.size());
        s += line;
    }
    static const char* kClock[] = {"source", "selector", "multiplier"};
    for (const Clock& c : device.clocks) {
        std::snprintf(line, sizeof line, "Clock %u: %s%s\n", c.id, kClock[static_cast<int>(c.kind)],
                      c.kind == Clock::Kind::Source ? (c.frequencyWritable ? ", rate settable" : ", fixed rate") : "");
        s += line;
    }
    return s;
}

}  // namespace uac
