// Unit tests for the pure C++ UAC core. Built with the NDK and run on the phone
// by scripts/run-native-tests.sh (no host compiler needed).
#include <cstdio>
#include <cmath>
#include <cstdlib>
#include <functional>
#include <string>
#include <vector>

#include "../../main/cpp/uac/Equalizer.h"
#include "../../main/cpp/uac/Pcm.h"
#include "../../main/cpp/uac/UacDescriptors.h"

static int g_failures = 0;
static int g_checks = 0;

#define CHECK(cond)                                                          \
    do {                                                                     \
        ++g_checks;                                                          \
        if (!(cond)) {                                                       \
            ++g_failures;                                                    \
            std::printf("  FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond);    \
        }                                                                    \
    } while (0)

static void test(const char* name, const std::function<void()>& body) {
    const int before = g_failures;
    body();
    std::printf("%s %s\n", g_failures == before ? "PASS" : "FAIL", name);
}

// UAC1 descriptor stream shaped like the Portronics GHW-123P: AudioControl,
// one OUT streaming interface (sync, 384 B packets), a mic and a HID interface.
// Synthetic variant (24-bit, 44.1/48 kHz, master volume) to cover more parser paths.
static std::vector<uint8_t> ghw123pFixture(uint8_t controlProtocol = 0x00) {
    return {
        // device
        18, 0x01, 0x10, 0x01, 0x00, 0x00, 0x00, 64, 0x20, 0x00, 0x21, 0x0B, 0x00, 0x01, 1, 2, 3, 1,
        // configuration
        9, 0x02, 0x00, 0x00, 4, 1, 0, 0x80, 50,
        // interface 0: AudioControl
        9, 0x04, 0, 0, 0, 0x01, 0x01, controlProtocol, 0,
        10, 0x24, 0x01, 0x00, 0x01, 0x40, 0x00, 2, 1, 2,             // header, 2 streaming ifaces
        12, 0x24, 0x02, 1, 0x01, 0x01, 0, 2, 0x03, 0x00, 0, 0,       // input terminal 1: USB streaming
        10, 0x24, 0x06, 2, 1, 1, 0x03, 0x00, 0x00, 0,                // feature unit 2 <- 1: mute+volume
        9, 0x24, 0x03, 3, 0x02, 0x03, 0, 2, 0,                       // output terminal 3 <- 2: headphones
        12, 0x24, 0x02, 4, 0x01, 0x02, 0, 1, 0x00, 0x00, 0, 0,       // input terminal 4: microphone
        9, 0x24, 0x03, 5, 0x01, 0x01, 0, 4, 0,                       // output terminal 5 <- 4: USB streaming
        // interface 1: playback
        9, 0x04, 1, 0, 0, 0x01, 0x02, 0x00, 0,
        9, 0x04, 1, 1, 1, 0x01, 0x02, 0x00, 0,
        7, 0x24, 0x01, 1, 1, 0x01, 0x00,                             // AS general: terminal 1, PCM
        14, 0x24, 0x02, 0x01, 2, 4, 24, 2, 0x44, 0xAC, 0x00, 0x80, 0xBB, 0x00,  // 2ch 24/32, 44.1k 48k
        9, 0x05, 0x03, 0x0D, 0x80, 0x01, 1, 0, 0,                    // EP 0x03 iso sync, 384 B
        7, 0x25, 0x01, 0x01, 0, 0, 0,                                // sampling freq control
        // interface 2: microphone
        9, 0x04, 2, 0, 0, 0x01, 0x02, 0x00, 0,
        9, 0x04, 2, 1, 1, 0x01, 0x02, 0x00, 0,
        7, 0x24, 0x01, 5, 1, 0x01, 0x00,
        11, 0x24, 0x02, 0x01, 1, 2, 16, 1, 0x80, 0xBB, 0x00,
        9, 0x05, 0x83, 0x0D, 0x64, 0x00, 1, 0, 0,                    // EP 0x83 IN
        7, 0x25, 0x01, 0x01, 0, 0, 0,
        // interface 3: HID
        9, 0x04, 3, 0, 1, 0x03, 0x00, 0x00, 0,
        7, 0x05, 0x82, 0x03, 0x20, 0x00, 1,
    };
}

// Real descriptors captured from the Portronics GHW-123P (VID 0x0020 PID 0x0B21)
// on 2026-10-03. 48 kHz / 16-bit only, per-channel volume, plus a sidetone mixer.
static std::vector<uint8_t> ghw123pRealDescriptors() {
    return {
        0x12, 0x01, 0x00, 0x02, 0x00, 0x00, 0x00, 0x40, 0x20, 0x00, 0x21, 0x0b, 0x00, 0x01, 0x01, 0x02, 0x03, 0x01,
        0x09, 0x02, 0xef, 0x00, 0x04, 0x01, 0x00, 0x80, 0x32,
        0x09, 0x04, 0x00, 0x00, 0x00, 0x01, 0x01, 0x00, 0x00,
        0x0a, 0x24, 0x01, 0x00, 0x01, 0x55, 0x00, 0x02, 0x01, 0x02,
        0x0c, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x02, 0x03, 0x00, 0x00, 0x00,
        0x0d, 0x24, 0x04, 0x06, 0x02, 0x05, 0x05, 0x02, 0x03, 0x00, 0x00, 0x00, 0x00,
        0x0a, 0x24, 0x06, 0x02, 0x01, 0x01, 0x01, 0x02, 0x02, 0x00,
        0x09, 0x24, 0x03, 0x03, 0x01, 0x03, 0x00, 0x02, 0x00,
        0x0c, 0x24, 0x02, 0x04, 0x01, 0x02, 0x00, 0x02, 0x03, 0x00, 0x00, 0x00,
        0x0a, 0x24, 0x06, 0x05, 0x04, 0x01, 0x03, 0x00, 0x00, 0x00,
        0x09, 0x24, 0x03, 0x06, 0x01, 0x01, 0x00, 0x05, 0x00,
        0x09, 0x04, 0x01, 0x00, 0x00, 0x01, 0x02, 0x00, 0x00,
        0x09, 0x04, 0x01, 0x01, 0x01, 0x01, 0x02, 0x00, 0x00,
        0x07, 0x24, 0x01, 0x01, 0x01, 0x01, 0x00,
        0x0b, 0x24, 0x02, 0x01, 0x02, 0x02, 0x10, 0x01, 0x80, 0xbb, 0x00,
        0x09, 0x05, 0x03, 0x0d, 0x80, 0x01, 0x01, 0x00, 0x00,
        0x07, 0x25, 0x01, 0x01, 0x01, 0x01, 0x00,
        0x09, 0x04, 0x02, 0x00, 0x00, 0x01, 0x02, 0x00, 0x00,
        0x09, 0x04, 0x02, 0x01, 0x01, 0x01, 0x02, 0x00, 0x00,
        0x07, 0x24, 0x01, 0x06, 0x01, 0x01, 0x00,
        0x0b, 0x24, 0x02, 0x01, 0x02, 0x02, 0x10, 0x01, 0x80, 0xbb, 0x00,
        0x09, 0x05, 0x83, 0x0d, 0xd0, 0x00, 0x01, 0x00, 0x00,
        0x07, 0x25, 0x01, 0x01, 0x00, 0x00, 0x00,
        0x09, 0x04, 0x03, 0x00, 0x02, 0x03, 0x00, 0x00, 0x00,
        0x09, 0x21, 0x01, 0x02, 0x00, 0x01, 0x22, 0x2f, 0x00,
        0x07, 0x05, 0x82, 0x03, 0x20, 0x00, 0x01,
        0x07, 0x05, 0x02, 0x03, 0x20, 0x00, 0x01,
    };
}

// UAC2 descriptors shaped like a typical hi-res USB-C dongle: high speed, a
// programmable clock behind a clock selector, async OUT endpoint with an explicit
// feedback endpoint, 16-bit and 32-bit alternate settings, and a HID interface.
static std::vector<uint8_t> uac2DongleFixture() {
    return {
        18, 0x01, 0x00, 0x02, 0xEF, 0x02, 0x01, 64, 0x34, 0x12, 0x78, 0x56, 0x00, 0x01, 1, 2, 3, 1,
        9, 0x02, 0x00, 0x00, 3, 1, 0, 0x80, 50,
        8, 0x0B, 0, 2, 0x01, 0x00, 0x20, 0,                                     // interface association
        // interface 0: AudioControl, UAC2
        9, 0x04, 0, 0, 0, 0x01, 0x01, 0x20, 0,
        9, 0x24, 0x01, 0x00, 0x02, 0x04, 0x40, 0x00, 0x00,                       // header
        8, 0x24, 0x0A, 0x29, 0x03, 0x07, 0x00, 0,                                // clock source 0x29, rate settable
        8, 0x24, 0x0B, 0x28, 1, 0x29, 0x03, 0,                                   // clock selector 0x28 -> 0x29
        17, 0x24, 0x02, 0x01, 0x01, 0x01, 0x00, 0x28, 2, 0x03, 0, 0, 0, 0, 0x00, 0x00, 0,  // IT 1 USB streaming
        18, 0x24, 0x06, 0x02, 0x01, 0x0F, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,    // FU 2: master mute+volume
        12, 0x24, 0x03, 0x03, 0x02, 0x03, 0x00, 0x02, 0x28, 0x00, 0x00, 0,       // OT 3 headphones
        // interface 1: playback
        9, 0x04, 1, 0, 0, 0x01, 0x02, 0x20, 0,
        9, 0x04, 1, 1, 2, 0x01, 0x02, 0x20, 0,
        16, 0x24, 0x01, 0x01, 0x00, 0x01, 0x01, 0, 0, 0, 2, 0x03, 0, 0, 0, 0,   // AS general: PCM, 2 ch
        6, 0x24, 0x02, 0x01, 2, 16,                                             // 16-bit in 2 bytes
        7, 0x05, 0x01, 0x05, 0xC4, 0x00, 1,                                     // EP 0x01 iso async, 196 B
        8, 0x25, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
        7, 0x05, 0x81, 0x11, 0x04, 0x00, 4,                                     // feedback EP 0x81
        9, 0x04, 1, 2, 2, 0x01, 0x02, 0x20, 0,
        16, 0x24, 0x01, 0x01, 0x00, 0x01, 0x01, 0, 0, 0, 2, 0x03, 0, 0, 0, 0,
        6, 0x24, 0x02, 0x01, 4, 32,                                             // 32-bit in 4 bytes
        7, 0x05, 0x01, 0x05, 0x88, 0x01, 1,                                     // 392 B
        8, 0x25, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00,
        7, 0x05, 0x81, 0x11, 0x04, 0x00, 4,
        // interface 2: HID buttons
        9, 0x04, 2, 0, 1, 0x03, 0x00, 0x00, 0,
        7, 0x05, 0x83, 0x03, 0x08, 0x00, 4,
    };
}

static void put32(std::vector<uint8_t>& v, uint32_t x) {
    for (int i = 0; i < 4; ++i) v.push_back(uint8_t(x >> (8 * i)));
}

int main() {
    test("parses the real GHW-123P descriptors", [] {
        auto bytes = ghw123pRealDescriptors();
        uac::UacDevice dev = uac::parseDescriptors(bytes.data(), bytes.size());
        CHECK(dev.uacVersion == 1);
        CHECK(dev.controlInterface == 0);
        CHECK(dev.outputs.size() == 1);  // the mic (interface 2) must not appear
        if (dev.outputs.size() != 1) return;
        const uac::OutputFormat& f = dev.outputs[0];
        CHECK(f.interfaceNumber == 1 && f.altSetting == 1);
        CHECK(f.endpointAddress == 0x03);
        CHECK(f.syncType == uac::SyncType::Sync);
        CHECK(f.channels == 2 && f.subslotBytes == 2 && f.bitResolution == 16);
        CHECK(f.maxPacketBytes == 384);
        CHECK(f.supportsRate(48000) && !f.supportsRate(44100));
        CHECK(f.sampleRateControl);
        const uac::FeatureUnit* fu = uac::findPlaybackFeatureUnit(dev, f);
        CHECK(fu != nullptr);
        if (fu) {
            CHECK(fu->unitId == 2);
            CHECK(!fu->masterVolume);
            CHECK(fu->channelVolume.size() == 2 && fu->channelVolume[0] && fu->channelVolume[1]);
        }
    });

    test("parses GHW-123P playback interface", [] {
        auto bytes = ghw123pFixture();
        uac::UacDevice dev = uac::parseDescriptors(bytes.data(), bytes.size());
        CHECK(dev.uacVersion == 1);
        CHECK(dev.controlInterface == 0);
        CHECK(dev.outputs.size() == 1);
        if (dev.outputs.size() != 1) return;
        const uac::OutputFormat& f = dev.outputs[0];
        CHECK(f.interfaceNumber == 1);
        CHECK(f.altSetting == 1);
        CHECK(f.endpointAddress == 0x03);
        CHECK(f.maxPacketBytes == 384);
        CHECK(f.interval == 1);
        CHECK(f.syncType == uac::SyncType::Sync);
        CHECK(f.terminalLink == 1);
        CHECK(f.formatTag == 1);
        CHECK(f.channels == 2);
        CHECK(f.subslotBytes == 4);
        CHECK(f.bitResolution == 24);
        CHECK(f.frameBytes() == 8);
        CHECK(f.sampleRateControl);
        CHECK(f.supportsRate(44100));
        CHECK(f.supportsRate(48000));
        CHECK(!f.supportsRate(96000));
    });

    test("finds the playback volume feature unit", [] {
        auto bytes = ghw123pFixture();
        uac::UacDevice dev = uac::parseDescriptors(bytes.data(), bytes.size());
        if (dev.outputs.empty()) { CHECK(false); return; }
        const uac::FeatureUnit* fu = uac::findPlaybackFeatureUnit(dev, dev.outputs[0]);
        CHECK(fu != nullptr);
        if (fu) {
            CHECK(fu->unitId == 2);
            CHECK(fu->masterVolume);
            CHECK(fu->masterMute);
        }
    });

    test("parses a UAC2 dongle: async endpoint, feedback, clocks", [] {
        auto bytes = uac2DongleFixture();
        uac::UacDevice dev = uac::parseDescriptors(bytes.data(), bytes.size());
        CHECK(dev.uacVersion == 2);
        CHECK(dev.controlInterface == 0);
        CHECK(dev.outputs.size() == 2);
        if (dev.outputs.size() != 2) return;
        const uac::OutputFormat& a = dev.outputs[0];
        CHECK(a.interfaceNumber == 1 && a.altSetting == 1);
        CHECK(a.formatTag == 1);
        CHECK(a.channels == 2 && a.subslotBytes == 2 && a.bitResolution == 16);
        CHECK(a.endpointAddress == 0x01 && a.maxPacketBytes == 196 && a.interval == 1);
        CHECK(a.syncType == uac::SyncType::Async);
        CHECK(a.feedbackEndpoint == 0x81 && a.feedbackMaxPacket == 4 && a.feedbackInterval == 4);
        CHECK(a.terminalLink == 1);
        CHECK(a.clockId == 0x28);
        CHECK(a.rates.empty());  // UAC2 rates come from the clock at runtime
        const uac::OutputFormat& b = dev.outputs[1];
        CHECK(b.subslotBytes == 4 && b.bitResolution == 32 && b.maxPacketBytes == 392);
        CHECK(b.feedbackEndpoint == 0x81);

        CHECK(dev.clocks.size() == 2);
        const uac::Clock* src = uac::findClock(dev, 0x29);
        CHECK(src && src->kind == uac::Clock::Kind::Source && src->frequencyWritable);
        CHECK(uac::resolveClockSource(dev, 0x28, nullptr) == 0x29);
        CHECK(uac::resolveClockSource(dev, 0x28, [](uint8_t) { return 1; }) == 0x29);
        CHECK(uac::resolveClockSource(dev, 0x77, nullptr) == 0);

        const uac::FeatureUnit* fu = uac::findPlaybackFeatureUnit(dev, a);
        CHECK(fu != nullptr);
        if (fu) CHECK(fu->unitId == 2 && fu->masterVolume && fu->masterMute);
    });

    test("UAC2 truncated descriptors never read out of bounds", [] {
        auto bytes = uac2DongleFixture();
        for (size_t n = 0; n < bytes.size(); ++n) uac::parseDescriptors(bytes.data(), n);
        CHECK(true);
    });

    test("ratesFromRange expands discrete and continuous ranges", [] {
        std::vector<uint8_t> discrete = {3, 0};
        for (uint32_t r : {44100u, 48000u, 96000u}) {
            put32(discrete, r);
            put32(discrete, r);
            put32(discrete, 0);
        }
        CHECK((uac::ratesFromRange(discrete.data(), discrete.size()) == std::vector<uint32_t>{44100, 48000, 96000}));

        std::vector<uint8_t> continuous = {1, 0};
        put32(continuous, 8000);
        put32(continuous, 192000);
        put32(continuous, 1);
        CHECK((uac::ratesFromRange(continuous.data(), continuous.size()) ==
               std::vector<uint32_t>{32000, 44100, 48000, 88200, 96000, 176400, 192000}));

        std::vector<uint8_t> truncated = {2, 0, 1, 2, 3};
        CHECK(uac::ratesFromRange(truncated.data(), truncated.size()).empty());
    });

    test("decodeFeedback handles 16.16, 10.14 and mislabelled devices", [] {
        // High speed, 44.1 kHz: 5.5125 frames per microframe.
        const uint32_t nominalHs = uint32_t((uint64_t(44100) << 16) / 8000);
        int shift = uac::kUnknownShift;
        std::vector<uint8_t> v;
        put32(v, nominalHs + 30);  // DAC clock a hair fast
        CHECK(uac::decodeFeedback(v.data(), 4, true, nominalHs, shift) == nominalHs + 30);
        CHECK(shift == 0);

        // A high-speed device that sends 10.14 (seen in the wild): detected as a shift of 2.
        shift = uac::kUnknownShift;
        v.clear();
        put32(v, uint32_t(5.5125 * 16384));
        const uint32_t got = uac::decodeFeedback(v.data(), 4, true, nominalHs, shift);
        CHECK(shift == 2);
        CHECK(got > nominalHs - 16 && got < nominalHs + 16);
        CHECK(uac::decodeFeedback(v.data(), 4, true, nominalHs, shift) == got);  // shift is remembered

        // Full speed, 3-byte 10.14: 44.1 frames per ms.
        const uint32_t nominalFs = uint32_t((uint64_t(44100) << 16) / 1000);
        shift = uac::kUnknownShift;
        const uint32_t q1014 = uint32_t(44.1 * 16384);
        const uint8_t fs[3] = {uint8_t(q1014), uint8_t(q1014 >> 8), uint8_t(q1014 >> 16)};
        const uint32_t fsGot = uac::decodeFeedback(fs, 3, false, nominalFs, shift);
        CHECK(fsGot > nominalFs - 16 && fsGot < nominalFs + 16);

        // Zero (clock not locked) and far-off values are ignored.
        shift = uac::kUnknownShift;
        const uint8_t zero[4] = {0, 0, 0, 0};
        CHECK(uac::decodeFeedback(zero, 4, true, nominalHs, shift) == 0);
        CHECK(shift == uac::kUnknownShift);
        shift = 0;
        v.clear();
        put32(v, nominalHs * 3 / 2);
        CHECK(uac::decodeFeedback(v.data(), 4, true, nominalHs, shift) == 0);
    });

    test("packet scheduler follows DAC feedback and respects the cap", [] {
        uac::PacketScheduler s(44100, 8000, 7);
        uint32_t total = 0;
        for (int i = 0; i < 8000; ++i) total += s.next();
        CHECK(total == 44100);  // nominal: exact

        s.setFeedback(s.nominalQ16() + 6554);  // DAC wants 0.1 frame more per microframe
        total = 0;
        for (int i = 0; i < 8000; ++i) total += s.next();
        CHECK(total >= 44100 + 790 && total <= 44100 + 810);

        s.setFeedback(uint32_t(20) << 16);  // absurd feedback is clamped to what the endpoint carries
        CHECK(s.next() == 7);
    });

    test("truncated descriptors never read out of bounds", [] {
        auto bytes = ghw123pFixture();
        for (size_t n = 0; n < bytes.size(); ++n) uac::parseDescriptors(bytes.data(), n);
        std::vector<uint8_t> bogus = {9, 0x02, 0, 0, 0, 0, 0, 0, 0, 0, 0x04};  // bLength 0 descriptor
        uac::parseDescriptors(bogus.data(), bogus.size());
        CHECK(true);
    });

    test("describe() mentions the format", [] {
        auto bytes = ghw123pFixture();
        std::string s = uac::describe(uac::parseDescriptors(bytes.data(), bytes.size()));
        CHECK(s.find("44100 48000 Hz") != std::string::npos);
        CHECK(s.find("24-bit in 4-byte slots") != std::string::npos);
    });

    test("packet scheduler: 48 kHz is 48 frames every packet", [] {
        uac::PacketScheduler s(48000, 1000);
        bool all48 = true;
        for (int i = 0; i < 1000; ++i) all48 &= s.next() == 48;
        CHECK(all48);
        CHECK(s.maxFrames() == 48);
    });

    test("packet scheduler: 44.1 kHz averages exactly 44.1", [] {
        uac::PacketScheduler s(44100, 1000);
        uint32_t total = 0, n45 = 0;
        for (int i = 0; i < 1000; ++i) {
            uint32_t f = s.next();
            CHECK(f == 44 || f == 45);
            total += f;
            n45 += f == 45;
        }
        CHECK(total == 44100);
        CHECK(n45 == 100);
        CHECK(s.maxFrames() == 45);
    });

    test("packSamples keeps the most significant bytes", [] {
        const int32_t src[2] = {0x12345600, int32_t(0xFEDCBA00)};
        uint8_t out3[6];
        uac::packSamples(src, out3, 2, 3);
        const uint8_t want3[6] = {0x56, 0x34, 0x12, 0xBA, 0xDC, 0xFE};
        CHECK(std::memcmp(out3, want3, 6) == 0);

        const int32_t src16[1] = {0x7FFF0000};
        uint8_t out2[2];
        uac::packSamples(src16, out2, 1, 2);
        CHECK(out2[0] == 0xFF && out2[1] == 0x7F);

        uint8_t out4[8];
        uac::packSamples(src, out4, 2, 4);
        CHECK(std::memcmp(out4, src, 8) == 0);
    });

    test("ditherer reduces 24-bit to 16-bit without bias or overflow", [] {
        uac::Ditherer d(16);
        const int n = 100000;
        std::vector<int32_t> in(n, 0x12345600), out(n);
        d.process(in.data(), out.data(), n);
        bool aligned = true, close = true;
        double sum = 0;
        for (int i = 0; i < n; ++i) {
            aligned &= (out[i] & 0xFFFF) == 0;
            close &= std::abs(int64_t(out[i]) - in[i]) <= 0x18000;  // round + dither <= 1.5 LSB
            sum += double(out[i]) - in[i];
        }
        CHECK(aligned);
        CHECK(close);
        CHECK(std::abs(sum / n) < 0x10000 * 0.02);  // mean error under 2% of an LSB

        const int32_t extremes[2] = {INT32_MAX, INT32_MIN};
        int32_t clipped[2];
        for (int i = 0; i < 1000; ++i) {
            d.process(extremes, clipped, 2);
            CHECK(clipped[0] == 0x7FFF0000);
            CHECK(clipped[1] >= INT32_MIN && clipped[1] <= int32_t(0x80010000));
        }
    });

    test("chooseOutputRate prefers exact, then multiples, then nearest higher", [] {
        CHECK(uac::chooseOutputRate(44100, {48000}) == 48000);                      // the Portronics case
        CHECK(uac::chooseOutputRate(48000, {44100, 48000, 96000}) == 48000);        // exact
        CHECK(uac::chooseOutputRate(44100, {48000, 88200, 96000, 176400}) == 88200);  // smallest multiple
        CHECK(uac::chooseOutputRate(44100, {32000, 96000, 48000}) == 48000);        // nearest higher
        CHECK(uac::chooseOutputRate(192000, {44100, 48000}) == 48000);              // nearest lower
        CHECK(uac::chooseOutputRate(48000, {}) == 0);
    });

    test("equalizer: flat settings are inactive and pass audio unchanged", [] {
        uac::Equalizer eq;
        eq.configure({{uac::EqBand::Type::Peak, 1000, 0.0, 1.0}}, 0.0, 48000, 2);
        CHECK(!eq.active());
        std::vector<float> s = {0.5f, -0.25f, 0.125f, 0.0f};
        eq.process(s.data(), 2);
        CHECK(s[0] == 0.5f && s[1] == -0.25f && s[2] == 0.125f);
    });

    test("equalizer: peak and shelf responses match their settings", [] {
        uac::Equalizer peak;
        peak.configure({{uac::EqBand::Type::Peak, 1000, 6.0, 1.0}}, 0.0, 48000, 2);
        CHECK(peak.active());
        CHECK(std::fabs(peak.responseDb(1000, 48000) - 6.0) < 0.05);
        CHECK(std::fabs(peak.responseDb(60, 48000)) < 0.3);

        uac::Equalizer low;
        low.configure({{uac::EqBand::Type::LowShelf, 100, 6.0, 0.707}}, 0.0, 44100, 2);
        CHECK(std::fabs(low.responseDb(20, 44100) - 6.0) < 0.3);
        CHECK(std::fabs(low.responseDb(10000, 44100)) < 0.1);

        uac::Equalizer high;
        high.configure({{uac::EqBand::Type::HighShelf, 8000, -6.0, 0.707}}, -2.0, 96000, 2);
        CHECK(std::fabs(high.responseDb(30000, 96000) - (-8.0)) < 0.5);
        CHECK(std::fabs(high.responseDb(100, 96000) - (-2.0)) < 0.1);  // preamp applies everywhere
    });

    test("equalizer: a real 1 kHz tone gets the designed boost", [] {
        uac::Equalizer eq;
        eq.configure({{uac::EqBand::Type::Peak, 1000, 6.0, 1.0}}, 0.0, 48000, 2);
        const size_t frames = 48000;
        std::vector<float> s(frames * 2);
        for (size_t i = 0; i < frames; ++i) {
            const float v = 0.25f * float(std::sin(2 * M_PI * 1000 * double(i) / 48000));
            s[2 * i] = v;
            s[2 * i + 1] = v;
        }
        eq.process(s.data(), frames);
        double sumSq = 0;
        for (size_t i = frames / 2; i < frames; ++i) sumSq += double(s[2 * i]) * s[2 * i];  // after settling
        const double rms = std::sqrt(sumSq / double(frames / 2));
        const double gainDb = 20 * std::log10(rms / (0.25 / std::sqrt(2.0)));
        CHECK(std::fabs(gainDb - 6.0) < 0.1);
    });

    test("equalizer: extreme settings stay stable", [] {
        uac::Equalizer eq;
        eq.configure({{uac::EqBand::Type::Peak, 30, 15, 8}, {uac::EqBand::Type::LowShelf, 20, 15, 0.3},
                      {uac::EqBand::Type::HighShelf, 20000, -15, 4}, {uac::EqBand::Type::Peak, 23999, 12, 1}},
                     -15, 48000, 2);
        std::vector<float> s(2 * 48000);
        uint32_t seed = 1;
        for (float& v : s) {
            seed = seed * 1664525u + 1013904223u;
            v = float(int32_t(seed)) / 2147483648.0f;
        }
        eq.process(s.data(), 48000);
        bool finite = true;
        for (float v : s) finite &= std::isfinite(v) && std::fabs(v) < 100.0f;
        CHECK(finite);
    });

    test("ring buffer wraps around and preserves bytes", [] {
        uac::RingBuffer rb(8);
        CHECK(rb.capacity() == 8);
        uint8_t in[6] = {1, 2, 3, 4, 5, 6}, out[8] = {};
        CHECK(rb.write(in, 6) == 6);
        CHECK(rb.read(out, 4) == 4);
        CHECK(rb.write(in, 6) == 6);   // wraps
        CHECK(rb.write(in, 6) == 0);   // full
        CHECK(rb.readable() == 8);
        CHECK(rb.read(out, 8) == 8);
        const uint8_t want[8] = {5, 6, 1, 2, 3, 4, 5, 6};
        CHECK(std::memcmp(out, want, 8) == 0);
        CHECK(rb.readable() == 0);
    });

    std::printf("\n%d checks, %d failures\n", g_checks, g_failures);
    return g_failures == 0 ? 0 : 1;
}
