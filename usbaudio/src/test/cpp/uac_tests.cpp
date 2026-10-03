// Unit tests for the pure C++ UAC core. Built with the NDK and run on the phone
// by scripts/run-native-tests.sh (no host compiler needed).
#include <cstdio>
#include <cstdlib>
#include <functional>
#include <string>
#include <vector>

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

    test("flags UAC2 devices and does not misparse them", [] {
        auto bytes = ghw123pFixture(0x20);
        uac::UacDevice dev = uac::parseDescriptors(bytes.data(), bytes.size());
        CHECK(dev.uacVersion == 2);
        CHECK(dev.outputs.empty());
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
