// Desktop unit tests for uac_stream_core.h (no Android/JNI needed).
// Build & run:  g++ -std=c++17 -O2 -Wall -Wextra -I.. uac_stream_core_test.cpp -o t && ./t
#include "../uac_stream_core.h"

#include <cmath>
#include <cstdio>
#include <cstdlib>

static int failures = 0;
#define CHECK(cond) do { if (!(cond)) { std::printf("FAIL %s:%d  %s\n", __FILE__, __LINE__, #cond); failures++; } } while (0)
#define CHECK_EQ(a, b) do { auto _a = (a); auto _b = (b); if (!(_a == _b)) { \
    std::printf("FAIL %s:%d  %s == %s  (%lld vs %lld)\n", __FILE__, __LINE__, #a, #b, (long long)_a, (long long)_b); failures++; } } while (0)

using namespace uac;

static void testTiming() {
    // UAC2 high-speed DAC, one packet per microframe.
    auto hs1 = computeTiming(BusSpeed::High, 1, 0x0200, true);
    CHECK_EQ(hs1.packetsPerSecond, 8000);
    CHECK_EQ(hs1.maxPacketBytes, 0x200);
    // High speed, bInterval 4 → every 8 microframes = 1 ms.
    auto hs4 = computeTiming(BusSpeed::High, 4, 0x0100, false);
    CHECK_EQ(hs4.packetsPerSecond, 1000);
    CHECK_EQ(hs4.intervalExponent, 3);
    // Full-speed UAC1.
    auto fs = computeTiming(BusSpeed::Full, 1, 0x00C0, false);
    CHECK_EQ(fs.packetsPerSecond, 1000);
    CHECK_EQ(fs.busIntervalsPerSecond, 1000);
    // High-bandwidth: 1024 bytes × 3 transactions.
    auto hb = computeTiming(BusSpeed::High, 1, 0x1400, true);
    CHECK_EQ(hb.maxPacketBytes, 1024 * 3);
    // Speed unknown (old kernel): UAC2 → high-speed timing, UAC1 → 1 ms packets.
    CHECK_EQ(computeTiming(BusSpeed::Unknown, 1, 0x200, true).packetsPerSecond, 8000);
    CHECK_EQ(computeTiming(BusSpeed::Unknown, 4, 0x100, false).packetsPerSecond, 1000);
    // SuperSpeed uses microframe timing too.
    CHECK_EQ(computeTiming(BusSpeed::Super, 1, 0x400, true).packetsPerSecond, 8000);
}

static void testNominalSizing() {
    struct Case { int rate; BusSpeed speed; int bInterval; };
    const Case cases[] = {
        {44100, BusSpeed::Full, 1}, {48000, BusSpeed::Full, 1}, {44100, BusSpeed::High, 1},
        {88200, BusSpeed::High, 1}, {176400, BusSpeed::High, 1}, {192000, BusSpeed::High, 1},
        {352800, BusSpeed::High, 1}, {768000, BusSpeed::High, 1}, {44100, BusSpeed::High, 4},
    };
    for (const auto& c : cases) {
        auto t = computeTiming(c.speed, c.bInterval, 0x1400, true);
        PacketSizer s(c.rate, t, 8);
        long long total = 0;
        int minF = 1 << 30, maxF = 0;
        for (int i = 0; i < t.packetsPerSecond * 3; i++) {
            int f = s.nextFrames();
            total += f;
            minF = std::min(minF, f);
            maxF = std::max(maxF, f);
        }
        // Exactly 3 seconds of frames — no drift, ever.
        CHECK_EQ(total, 3LL * c.rate);
        CHECK(maxF - minF <= 1);
    }
}

static void put3(uint8_t* b, uint32_t v) { b[0] = v & 0xFF; b[1] = (v >> 8) & 0xFF; b[2] = (v >> 16) & 0xFF; }
static void put4(uint8_t* b, uint32_t v) { put3(b, v); b[3] = (v >> 24) & 0xFF; }

static void testFeedbackDecoding() {
    // UAC1 full speed: 10.14 samples per frame, 3 bytes.
    {
        FeedbackDecoder d(nominalPerBusIntervalQ16(44100, 1000));
        uint8_t b[3];
        put3(b, static_cast<uint32_t>(44.1 * (1 << 14)));
        uint32_t f = d.decode(b, 3);
        CHECK(f != 0);
        CHECK(std::fabs(f / 65536.0 - 44.1) < 0.001);
        CHECK_EQ(d.shift(), 0);
    }
    // UAC2 high speed: 16.16 samples per microframe, 4 bytes.
    {
        FeedbackDecoder d(nominalPerBusIntervalQ16(48000, 8000));
        uint8_t b[4];
        put4(b, static_cast<uint32_t>(6.001 * 65536));
        uint32_t f = d.decode(b, 4);
        CHECK(std::fabs(f / 65536.0 - 6.001) < 0.0001);
    }
    // Quirky device: 10.14 format inside a 4-byte packet on high speed → shift auto-detected.
    {
        FeedbackDecoder d(nominalPerBusIntervalQ16(96000, 8000));
        uint8_t b[4];
        put4(b, static_cast<uint32_t>(12.0 * (1 << 14)));
        uint32_t f = d.decode(b, 4);
        CHECK(std::fabs(f / 65536.0 - 12.0) < 0.001);
        CHECK_EQ(d.shift(), 2);
        put4(b, static_cast<uint32_t>(12.003 * (1 << 14)));
        f = d.decode(b, 4);
        CHECK(std::fabs(f / 65536.0 - 12.003) < 0.001);
    }
    // Garbage is rejected and the shift is re-detected afterwards.
    {
        FeedbackDecoder d(nominalPerBusIntervalQ16(48000, 8000));
        uint8_t b[4];
        put4(b, static_cast<uint32_t>(6.0 * 65536));
        CHECK(d.decode(b, 4) != 0);
        put4(b, static_cast<uint32_t>(2.0 * 65536)); // a third of nominal
        CHECK_EQ(d.decode(b, 4), 0u);
        CHECK(!d.shiftKnown());
        CHECK_EQ(d.rejected(), 1ull);
    }
    // Zero / too-short packets are ignored.
    {
        FeedbackDecoder d(nominalPerBusIntervalQ16(48000, 8000));
        uint8_t z[4] = {0, 0, 0, 0};
        CHECK_EQ(d.decode(z, 4), 0u);
        CHECK_EQ(d.decode(z, 2), 0u);
    }
}

static void testFeedbackDrivenSizing() {
    // DAC clock running 0.1 % fast at 48 kHz on a high-speed bus.
    auto t = computeTiming(BusSpeed::High, 1, 0x200, true);
    PacketSizer s(48000, t, 8);
    double actual = 48048.0;
    s.setFeedback(static_cast<uint32_t>(actual / 8000.0 * 65536.0 + 0.5));
    for (int i = 0; i < 40; i++) s.setFeedback(static_cast<uint32_t>(actual / 8000.0 * 65536.0 + 0.5)); // settle smoothing
    long long total = 0;
    for (int i = 0; i < 8000 * 10; i++) total += s.nextFrames();
    CHECK(std::llabs(total - 480480LL) <= 2);
    CHECK(s.usingFeedback());
    CHECK(std::llabs(s.effectiveRateMilliHz() - 48048000LL) < 2000);

    // bInterval 4 (1 ms packets): feedback per microframe is scaled ×8 per packet.
    auto t4 = computeTiming(BusSpeed::High, 4, 0x200, false);
    PacketSizer s4(44100, t4, 8);
    s4.setFeedback(static_cast<uint32_t>(44100.0 / 8000.0 * 65536.0 + 0.5));
    long long total4 = 0;
    for (int i = 0; i < 1000 * 5; i++) total4 += s4.nextFrames();
    CHECK(std::llabs(total4 - 220500LL) <= 2);

    // Clearing feedback returns to the exact nominal schedule.
    s.setFeedback(0);
    CHECK(!s.usingFeedback());
}

static void testFrameRing() {
    FrameRing r(64, 8); // 8 frames of 8 bytes
    CHECK_EQ(r.capacity(), 64u);
    uint8_t src[100];
    for (int i = 0; i < 100; i++) src[i] = static_cast<uint8_t>(i);

    // A partial frame is accepted by the producer but never handed out.
    CHECK_EQ(r.write(src, 12), 12u);
    uint8_t dst[64] = {0};
    CHECK_EQ(r.readFrames(dst, 4), 1u);
    CHECK(dst[0] == 0 && dst[7] == 7);
    CHECK_EQ(r.availableBytes(), 4u);
    // Completing the frame makes it readable, in order.
    CHECK_EQ(r.write(src + 12, 4), 4u);
    CHECK_EQ(r.readFrames(dst, 4), 1u);
    CHECK(dst[0] == 8 && dst[7] == 15);

    // Wrap-around keeps byte order.
    CHECK_EQ(r.write(src + 16, 64), 64u);
    CHECK_EQ(r.write(src, 1), 0u); // full
    CHECK_EQ(r.readFrames(dst, 8), 8u);
    for (int i = 0; i < 64; i++) CHECK(dst[i] == static_cast<uint8_t>(16 + i));
    CHECK_EQ(r.availableBytes(), 0u);
    CHECK_EQ(r.readFrames(dst, 1), 0u);

    // Many small mixed operations: total order preserved.
    FrameRing big(4096, 6);
    uint64_t wrote = 0, read = 0;
    uint8_t buf[600];
    uint8_t out[600];
    unsigned seed = 1;
    bool orderOk = true;
    for (int iter = 0; iter < 20000; iter++) {
        seed = seed * 1103515245u + 12345u;
        size_t n = (seed >> 16) % 300;
        for (size_t i = 0; i < n; i++) buf[i] = static_cast<uint8_t>((wrote + i) & 0xFF);
        wrote += big.write(buf, n);
        seed = seed * 1103515245u + 12345u;
        size_t frames = (seed >> 16) % 50;
        size_t got = big.readFrames(out, frames);
        for (size_t i = 0; i < got * 6; i++) if (out[i] != static_cast<uint8_t>((read + i) & 0xFF)) orderOk = false;
        read += got * 6;
    }
    CHECK(orderOk);
    CHECK(read % 6 == 0);
}

static void testUrbPlan() {
    auto hs = computeTiming(BusSpeed::High, 1, 0x200, true);
    auto p = planUrbs(hs, 32);
    CHECK_EQ(p.packetsPerUrb, 8);
    CHECK_EQ(p.urbCount, 32);
    auto fs = computeTiming(BusSpeed::Full, 1, 0x200, false);
    auto q = planUrbs(fs, 16);
    CHECK_EQ(q.packetsPerUrb, 1);
    CHECK_EQ(q.urbCount, 16);
    CHECK_EQ(planUrbs(hs, 1).urbCount, 4);      // floor
    CHECK_EQ(planUrbs(hs, 1000).urbCount, 64);  // ceiling
}

int main() {
    testTiming();
    testNominalSizing();
    testFeedbackDecoding();
    testFeedbackDrivenSizing();
    testFrameRing();
    testUrbPlan();
    if (failures == 0) std::printf("ALL PASSED\n");
    return failures == 0 ? 0 : 1;
}
