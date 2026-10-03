// Small building blocks for the streaming path: packet sizing, sample packing
// and the producer/consumer ring buffer. Header-only, no platform dependencies.
#pragma once

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace uac {

// Frames to put in each isochronous packet. For synchronous/adaptive endpoints:
// 48000 Hz at 1000 packets/s gives 48 every time; 44100 Hz gives nine packets
// of 44 then one of 45, so the long-run rate is exact. For asynchronous endpoints
// the DAC's measured rate (from its feedback endpoint) takes over once known.
class PacketScheduler {
public:
    // maxFramesCap: never put more frames in a packet than the endpoint can carry (0 = no cap).
    PacketScheduler(uint32_t sampleRate, uint32_t packetsPerSecond, uint32_t maxFramesCap = 0)
        : rate_(sampleRate), pps_(packetsPerSecond), cap_(maxFramesCap) {}

    uint32_t next() {
        uint32_t frames;
        if (feedbackQ16_ != 0) {
            feedbackAcc_ += feedbackQ16_;
            frames = uint32_t(feedbackAcc_ >> 16);
            feedbackAcc_ &= 0xFFFF;
        } else {
            acc_ += rate_;
            frames = acc_ / pps_;
            acc_ -= frames * pps_;
        }
        return cap_ ? std::min(frames, cap_) : frames;
    }

    // The DAC's measured rate in frames per packet, 16.16 fixed point (0 = use the nominal rate).
    void setFeedback(uint32_t framesPerPacketQ16) { feedbackQ16_ = framesPerPacketQ16; }

    uint32_t nominalQ16() const { return uint32_t((uint64_t(rate_) << 16) / pps_); }
    uint32_t maxFrames() const { return (rate_ + pps_ - 1) / pps_; }

private:
    uint32_t rate_;
    uint32_t pps_;
    uint32_t cap_;
    uint32_t acc_ = 0;
    uint32_t feedbackQ16_ = 0;
    uint64_t feedbackAcc_ = 0;
};

// Picks the DAC rate to play `sourceRate` at, from the rates the DAC supports:
// exact > integer multiple (44.1k -> 88.2k) > nearest higher > nearest lower.
// Returns 0 when `rates` is empty.
inline uint32_t chooseOutputRate(uint32_t sourceRate, const std::vector<uint32_t>& rates) {
    auto score = [&](uint32_t r) {
        if (r == sourceRate) return 4;
        if (sourceRate != 0 && r % sourceRate == 0) return 3;
        return r > sourceRate ? 2 : 1;
    };
    uint32_t pick = 0;
    for (uint32_t r : rates) {
        if (r == 0) continue;
        if (pick == 0 || score(r) > score(pick) ||
            (score(r) == score(pick) && (score(r) >= 2 ? r < pick : r > pick))) {
            pick = r;
        }
    }
    return pick;
}

// Packs 32-bit left-justified little-endian samples into the DAC's subslot size
// by keeping the most significant bytes. Lossless whenever the source bit depth
// is <= subslotBytes * 8.
inline void packSamples(const int32_t* src, uint8_t* dst, size_t samples, uint32_t subslotBytes) {
    if (subslotBytes == 4) {
        std::memcpy(dst, src, samples * 4);
        return;
    }
    const uint8_t* s = reinterpret_cast<const uint8_t*>(src);
    const size_t skip = 4 - subslotBytes;
    for (size_t i = 0; i < samples; ++i) {
        std::memcpy(dst, s + skip, subslotBytes);
        dst += subslotBytes;
        s += 4;
    }
}

// Reduces left-justified int32 samples to `bits` significant bits with TPDF
// dither (±1 LSB triangular), for DACs that cannot take the source bit depth.
class Ditherer {
public:
    explicit Ditherer(uint32_t bits) : shift_(32 - bits) {}

    void process(const int32_t* src, int32_t* dst, size_t samples) {
        const int64_t lsb = int64_t(1) << shift_;
        const int64_t mask = lsb - 1;
        const int64_t maxValue = int64_t(INT32_MAX) & ~mask;
        for (size_t i = 0; i < samples; ++i) {
            const int64_t dither = int64_t(nextRandom() & mask) - int64_t(nextRandom() & mask);
            int64_t v = (int64_t(src[i]) + dither + lsb / 2) & ~mask;  // round to the nearest step
            v = std::clamp<int64_t>(v, INT32_MIN, maxValue);
            dst[i] = int32_t(v);
        }
    }

private:
    uint32_t nextRandom() {  // xorshift32
        state_ ^= state_ << 13;
        state_ ^= state_ >> 17;
        state_ ^= state_ << 5;
        return state_;
    }

    uint32_t shift_;
    uint32_t state_ = 0x9E3779B9u;
};

// Single-producer single-consumer byte ring. Capacity is rounded up to a power of two.
class RingBuffer {
public:
    explicit RingBuffer(size_t minCapacity) {
        size_t cap = 1;
        while (cap < minCapacity) cap <<= 1;
        buf_.resize(cap);
        mask_ = cap - 1;
    }

    size_t capacity() const { return buf_.size(); }
    size_t readable() const { return head_.load(std::memory_order_acquire) - tail_.load(std::memory_order_relaxed); }
    size_t writable() const { return capacity() - (head_.load(std::memory_order_relaxed) - tail_.load(std::memory_order_acquire)); }

    size_t write(const uint8_t* src, size_t n) {
        const size_t head = head_.load(std::memory_order_relaxed);
        n = std::min(n, capacity() - (head - tail_.load(std::memory_order_acquire)));
        copyIn(head, src, n);
        head_.store(head + n, std::memory_order_release);
        return n;
    }

    size_t read(uint8_t* dst, size_t n) {
        const size_t tail = tail_.load(std::memory_order_relaxed);
        n = std::min(n, head_.load(std::memory_order_acquire) - tail);
        copyOut(tail, dst, n);
        tail_.store(tail + n, std::memory_order_release);
        return n;
    }

    // Only safe while neither side is running.
    void clear() { tail_.store(head_.load()); }

private:
    void copyIn(size_t at, const uint8_t* src, size_t n) {
        const size_t off = at & mask_;
        const size_t first = std::min(n, capacity() - off);
        std::memcpy(&buf_[off], src, first);
        std::memcpy(&buf_[0], src + first, n - first);
    }
    void copyOut(size_t at, uint8_t* dst, size_t n) const {
        const size_t off = at & mask_;
        const size_t first = std::min(n, capacity() - off);
        std::memcpy(dst, &buf_[off], first);
        std::memcpy(dst + first, &buf_[0], n - first);
    }

    std::vector<uint8_t> buf_;
    size_t mask_ = 0;
    std::atomic<size_t> head_{0};
    std::atomic<size_t> tail_{0};
};

}  // namespace uac
