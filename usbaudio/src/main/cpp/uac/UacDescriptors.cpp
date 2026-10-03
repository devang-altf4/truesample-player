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

// AudioControl descriptor subtypes (UAC1)
constexpr uint8_t kAcInputTerminal = 0x02;
constexpr uint8_t kAcOutputTerminal = 0x03;
constexpr uint8_t kAcFeatureUnit = 0x06;

// AudioStreaming descriptor subtypes
constexpr uint8_t kAsGeneral = 0x01;
constexpr uint8_t kAsFormatType = 0x02;
constexpr uint8_t kFormatTypeI = 0x01;
constexpr uint8_t kEpGeneral = 0x01;

uint16_t u16(const uint8_t* p) { return uint16_t(p[0] | (p[1] << 8)); }
uint32_t u24(const uint8_t* p) { return uint32_t(p[0] | (p[1] << 8) | (p[2] << 16)); }

struct StreamingAlt {
    OutputFormat format;
    bool hasFormat = false;
    bool hasOutEndpoint = false;
};

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

        if (type == kDescCsInterface && len >= 3 && iface == Iface::Control && dev.uacVersion == 1) {
            const uint8_t subtype = d[2];
            if (subtype == kAcInputTerminal && len >= 6) {
                dev.units.push_back({Unit::Kind::InputTerminal, d[3], u16(d + 4), 0});
            } else if (subtype == kAcOutputTerminal && len >= 8) {
                dev.units.push_back({Unit::Kind::OutputTerminal, d[3], u16(d + 4), d[7]});
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
                dev.units.push_back({Unit::Kind::Feature, fu.unitId, 0, fu.sourceId});
            } else if (len >= 4 && subtype > kAcInputTerminal) {
                dev.units.push_back({Unit::Kind::Other, d[3], 0, 0});
            }
            continue;
        }

        if (type == kDescCsInterface && len >= 3 && iface == Iface::Streaming) {
            const uint8_t subtype = d[2];
            if (subtype == kAsGeneral && len >= 7 && dev.uacVersion == 1) {
                alt.format.terminalLink = d[3];
                alt.format.formatTag = u16(d + 5);
            } else if (subtype == kAsFormatType && len >= 8 && d[3] == kFormatTypeI && dev.uacVersion == 1) {
                OutputFormat& f = alt.format;
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
            continue;
        }

        if (type == kDescEndpoint && len >= 7 && iface == Iface::Streaming) {
            const uint8_t address = d[2];
            const uint8_t attributes = d[3];
            const bool isochronous = (attributes & 0x03) == 0x01;
            const bool isFeedback = ((attributes >> 4) & 0x03) == 0x01;
            if (isochronous && !(address & 0x80) && !isFeedback) {
                OutputFormat& f = alt.format;
                const uint16_t maxPacket = u16(d + 4);
                f.endpointAddress = address;
                f.maxPacketBytes = uint16_t((maxPacket & 0x7FF) * (1 + ((maxPacket >> 11) & 0x03)));
                f.interval = d[6];
                f.syncType = static_cast<SyncType>((attributes >> 2) & 0x03);
                if (len >= 9 && f.syncType == SyncType::Async) f.feedbackEndpoint = d[8];
                alt.hasOutEndpoint = true;
            }
            continue;
        }

        if (type == kDescCsEndpoint && len >= 4 && iface == Iface::Streaming && alt.hasOutEndpoint) {
            if (d[2] == kEpGeneral) alt.format.sampleRateControl = d[3] & 0x01;
            continue;
        }
    }
    finishAlt();
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
            break;  // mixer/selector units are not followed in M1
        }
    }
    // Fallback: a feature unit fed directly by our streaming terminal.
    for (const FeatureUnit& f : device.featureUnits) {
        if (f.sourceId == format.terminalLink && hasVolume(&f)) return &f;
    }
    return nullptr;
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
    return s;
}

}  // namespace uac
