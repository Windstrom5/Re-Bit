// Portable core of the native USB Audio Class streaming engine.
//
// Everything here is pure logic with no Linux/Android/JNI dependency, so it is unit-tested on a
// desktop toolchain (see tests/uac_stream_core_test.cpp). usb_native_streamer.cpp wires it to
// usbdevfs and JNI.
//
// The design follows the Linux kernel's snd-usb-audio driver, which is the reference for how a
// UAC1/UAC2 host streams to a DAC:
//   - service interval from bus speed + the endpoint's bInterval (2^(bInterval-1) frames or
//     microframes), not guessed from bandwidth;
//   - packet sizes from a phase accumulator, driven either by the exact nominal rate (rational,
//     never drifts) or by the DAC's own asynchronous feedback (Q16.16 frames per packet);
//   - feedback format auto-detection (10.14 vs 16.16 and devices that report it shifted),
//     exactly as snd-usb-audio does it, with out-of-range values rejected.
#pragma once

#include <algorithm>
#include <atomic>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace uac {

// Matches the kernel's enum usb_device_speed values returned by USBDEVFS_GET_SPEED.
enum class BusSpeed : int { Unknown = 0, Low = 1, Full = 2, High = 3, Wireless = 4, Super = 5, SuperPlus = 6 };

struct EndpointTiming {
    int packetsPerSecond;   // data packets (service intervals) per second
    int intervalExponent;   // bInterval - 1: one packet every 2^exp (micro)frames
    int busIntervalsPerSecond; // 1000 (full speed frames) or 8000 (high speed microframes)
    int maxPacketBytes;     // largest single packet, including high-bandwidth multiplier
};

// [isUac2Hint] is only used when the bus speed is unknown (old kernels without
// USBDEVFS_GET_SPEED): UAC2 requires high speed; for UAC1, 1000 packets/s is right both for a
// full-speed device (bInterval 1) and the usual high-speed UAC1 layout (bInterval 4).
inline EndpointTiming computeTiming(BusSpeed speed, int bInterval, int wMaxPacketSize, bool isUac2Hint) {
    int exp = std::clamp(bInterval, 1, 16) - 1;
    bool highSpeedTiming;
    if (speed == BusSpeed::Unknown) {
        highSpeedTiming = isUac2Hint;
        if (!isUac2Hint) {
            // Unknown-speed UAC1: assume one packet per millisecond.
            int raw = wMaxPacketSize & 0x7FF;
            return EndpointTiming{1000, 0, 1000, raw > 0 ? raw : 1};
        }
    } else {
        highSpeedTiming = static_cast<int>(speed) >= static_cast<int>(BusSpeed::High);
    }
    int bus = highSpeedTiming ? 8000 : 1000;
    exp = std::min(exp, highSpeedTiming ? 13 : 9);
    int pps = std::max(1, bus >> exp);
    int bytes = wMaxPacketSize & 0x7FF;
    // High-bandwidth isochronous (high speed only): bits 11-12 = additional transactions.
    int mult = highSpeedTiming ? 1 + ((wMaxPacketSize >> 11) & 0x3) : 1;
    if (mult > 3) mult = 3;
    return EndpointTiming{pps, exp, bus, std::max(1, bytes * mult)};
}

// Nominal frames per *bus interval* (frame or microframe) in Q16.16, rounded — the unit the
// DAC's feedback endpoint reports in. Same arithmetic as snd-usb-audio's
// get_usb_full_speed_rate()/get_usb_high_speed_rate().
inline uint32_t nominalPerBusIntervalQ16(int sampleRate, int busIntervalsPerSecond) {
    uint64_t r = static_cast<uint64_t>(sampleRate) << 16;
    return static_cast<uint32_t>((r + busIntervalsPerSecond / 2) / busIntervalsPerSecond);
}

// Decodes and validates asynchronous feedback values.
class FeedbackDecoder {
public:
    explicit FeedbackDecoder(uint32_t nominalQ16) : nominal_(nominalQ16) {}

    // Raw feedback packet → Q16.16 frames per bus interval, or 0 if it was rejected.
    // 3-byte packets are UAC1 10.14; 4-byte are UAC2 16.16. The first accepted value fixes a
    // binary shift that maps the device's encoding onto the nominal rate (several real devices
    // report 16.16 shifted, or 10.14 on a high-speed bus); a later wildly-off value resets it.
    uint32_t decode(const uint8_t* buf, int len) {
        if (len < 3) return 0;
        uint64_t f;
        if (len == 3) {
            f = (static_cast<uint64_t>(buf[0]) | (static_cast<uint64_t>(buf[1]) << 8) |
                 (static_cast<uint64_t>(buf[2]) << 16)) << 2;
        } else {
            f = (static_cast<uint64_t>(buf[0]) | (static_cast<uint64_t>(buf[1]) << 8) |
                 (static_cast<uint64_t>(buf[2]) << 16) | (static_cast<uint64_t>(buf[3]) << 24)) & 0x0FFFFFFFu;
        }
        if (f == 0) return 0;
        const uint64_t n = nominal_;
        if (!shiftKnown_) {
            int shift = 0;
            while (f < n - n / 4 && shift < 16) { f <<= 1; shift++; }
            while (f > n + n / 2 && shift > -16) { f >>= 1; shift--; }
            shift_ = shift;
            shiftKnown_ = true;
        } else if (shift_ >= 0) {
            f <<= shift_;
        } else {
            f >>= -shift_;
        }
        // Accept within -12.5 % / +50 % of nominal (snd-usb-audio's window); otherwise the shift
        // is probably wrong, so re-detect on the next packet.
        if (f >= n - n / 8 && f <= n + n / 2) {
            accepted_++;
            return static_cast<uint32_t>(f);
        }
        shiftKnown_ = false;
        rejected_++;
        return 0;
    }

    int shift() const { return shift_; }
    bool shiftKnown() const { return shiftKnown_; }
    uint64_t accepted() const { return accepted_; }
    uint64_t rejected() const { return rejected_; }

private:
    uint32_t nominal_;
    bool shiftKnown_ = false;
    int shift_ = 0;
    uint64_t accepted_ = 0;
    uint64_t rejected_ = 0;
};

// Decides how many audio frames go into each packet.
//  - No feedback (synchronous/adaptive endpoints, or "fixed rate" mode): an exact rational
//    accumulator — rate / packetsPerSecond with the remainder carried, so one second always
//    contains exactly `rate` frames (e.g. 44.1 kHz at 1 ms: nine 44s then a 45, repeating).
//  - With feedback: a Q16.16 phase accumulator advanced by the DAC-reported rate, scaled to the
//    packet interval — the DAC's clock paces the stream, so its FIFO never under/overflows.
class PacketSizer {
public:
    PacketSizer(int sampleRate, const EndpointTiming& t, int frameBytes)
        : rate_(sampleRate), pps_(std::max(1, t.packetsPerSecond)), exp_(t.intervalExponent),
          maxFrames_(std::max(1, t.maxPacketBytes / std::max(1, frameBytes))),
          base_(sampleRate / pps_), rem_(sampleRate % pps_) {}

    // Q16.16 frames per bus interval from FeedbackDecoder; 0 returns to the nominal rate.
    void setFeedback(uint32_t perBusIntervalQ16) {
        if (perBusIntervalQ16 == 0) { useFeedback_ = false; return; }
        // Light smoothing: feedback is already averaged by the DAC, this only tames jitter.
        uint64_t perPacket = static_cast<uint64_t>(perBusIntervalQ16) << exp_;
        if (!useFeedback_) {
            fbQ16_ = perPacket;
            phase_ = 0;
        } else {
            const int64_t cur = static_cast<int64_t>(fbQ16_);
            fbQ16_ = static_cast<uint64_t>(cur + (static_cast<int64_t>(perPacket) - cur) / 4);
        }
        useFeedback_ = true;
    }

    bool usingFeedback() const { return useFeedback_; }

    // Current effective rate in milli-Hz (for diagnostics).
    int64_t effectiveRateMilliHz() const {
        if (!useFeedback_) return static_cast<int64_t>(rate_) * 1000;
        return static_cast<int64_t>((static_cast<long double>(fbQ16_) * pps_ * 1000.0L) / 65536.0L);
    }

    int nextFrames() {
        int frames;
        if (useFeedback_) {
            phase_ = (phase_ & 0xFFFF) + fbQ16_;
            frames = static_cast<int>(phase_ >> 16);
        } else {
            acc_ += rem_;
            frames = base_;
            if (acc_ >= pps_) { acc_ -= pps_; frames++; }
        }
        return std::clamp(frames, 0, maxFrames_);
    }

    int maxFrames() const { return maxFrames_; }

private:
    int rate_;
    int pps_;
    int exp_;
    int maxFrames_;
    int base_;
    int rem_;
    int acc_ = 0;
    bool useFeedback_ = false;
    uint64_t fbQ16_ = 0;
    uint64_t phase_ = 0;
};

// Lock-free single-producer / single-consumer byte ring that only ever hands out whole audio
// frames, so an underrun can never shift the channel alignment of the stream.
class FrameRing {
public:
    FrameRing(size_t capacityBytes, int frameBytes)
        : frameBytes_(static_cast<size_t>(std::max(1, frameBytes))),
          cap_(std::max(frameBytes_ * 2, capacityBytes / frameBytes_ * frameBytes_)),
          data_(cap_) {}

    // Producer: copies as many bytes as fit; returns how many were taken.
    size_t write(const uint8_t* src, size_t len) {
        const uint64_t w = w_.load(std::memory_order_relaxed);
        const uint64_t r = r_.load(std::memory_order_acquire);
        const size_t free = cap_ - static_cast<size_t>(w - r);
        const size_t n = std::min(len, free);
        size_t pos = static_cast<size_t>(w % cap_);
        size_t first = std::min(n, cap_ - pos);
        std::memcpy(&data_[pos], src, first);
        if (n > first) std::memcpy(&data_[0], src + first, n - first);
        w_.store(w + n, std::memory_order_release);
        return n;
    }

    // Consumer: copies up to [frames] whole frames into dst; returns frames copied.
    size_t readFrames(uint8_t* dst, size_t frames) {
        const uint64_t r = r_.load(std::memory_order_relaxed);
        const uint64_t w = w_.load(std::memory_order_acquire);
        const size_t availFrames = static_cast<size_t>(w - r) / frameBytes_;
        const size_t nFrames = std::min(frames, availFrames);
        const size_t n = nFrames * frameBytes_;
        size_t pos = static_cast<size_t>(r % cap_);
        size_t first = std::min(n, cap_ - pos);
        std::memcpy(dst, &data_[pos], first);
        if (n > first) std::memcpy(dst + first, &data_[0], n - first);
        r_.store(r + n, std::memory_order_release);
        return nFrames;
    }

    size_t availableBytes() const {
        return static_cast<size_t>(w_.load(std::memory_order_acquire) - r_.load(std::memory_order_acquire));
    }
    size_t freeBytes() const { return cap_ - availableBytes(); }
    size_t capacity() const { return cap_; }
    size_t frameBytes() const { return frameBytes_; }

private:
    size_t frameBytes_;
    size_t cap_;
    std::vector<uint8_t> data_;
    std::atomic<uint64_t> w_{0};
    std::atomic<uint64_t> r_{0};
};

// Number of packets per URB (≈1 ms each) and URBs in flight for a target latency.
struct UrbPlan {
    int packetsPerUrb;
    int urbCount;
};

inline UrbPlan planUrbs(const EndpointTiming& t, int targetLatencyMs) {
    // One URB ≈ 1 ms of packets (at least one packet); the kernel's usbfs limit is 128 packets.
    int perUrb = std::clamp(t.packetsPerSecond / 1000, 1, 128);
    double msPerUrb = 1000.0 * perUrb / t.packetsPerSecond;
    int urbs = static_cast<int>(targetLatencyMs / msPerUrb + 0.5);
    return UrbPlan{perUrb, std::clamp(urbs, 4, 64)};
}

} // namespace uac
