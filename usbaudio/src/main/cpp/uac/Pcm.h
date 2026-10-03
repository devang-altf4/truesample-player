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

// Frames to put in each isochronous packet of a synchronous/adaptive endpoint.
// 48000 Hz at 1000 packets/s gives 48 every time; 44100 Hz gives nine packets
// of 44 then one of 45, so the long-run rate is exact.
class PacketScheduler {
public:
    PacketScheduler(uint32_t sampleRate, uint32_t packetsPerSecond)
        : rate_(sampleRate), pps_(packetsPerSecond) {}

    uint32_t next() {
        acc_ += rate_;
        const uint32_t frames = acc_ / pps_;
        acc_ -= frames * pps_;
        return frames;
    }

    uint32_t maxFrames() const { return (rate_ + pps_ - 1) / pps_; }

private:
    uint32_t rate_;
    uint32_t pps_;
    uint32_t acc_ = 0;
};

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
