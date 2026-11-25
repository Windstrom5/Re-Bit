package com.windstrom5.rebit.domain.audiophile

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

class TestSignalGeneratorTest {
    private fun gen(cfg: SignalConfig, frames: Int, running: Boolean = true, g: TestSignalGenerator = TestSignalGenerator()): ShortArray {
        val o = ShortArray(frames * 2); g.fill(o, frames, cfg, running); return o
    }
    private fun peak(a: ShortArray, ch: Int, from: Int, to: Int): Double {
        var m = 0; for (i in from until to) m = max(m, abs(a[2 * i + ch].toInt())); return m / 32767.0
    }

    @Test fun levelIsAccurateAndCapped() {
        val a = gen(SignalConfig(levelDb = -20.0), 48000)
        assertEquals(0.1, peak(a, 0, 43200, 48000), 0.003)
        assertTrue(peak(a, 0, 0, 5) < 0.01) // fades in, no click
        assertTrue(peak(gen(SignalConfig(levelDb = 6.0), 48000), 0, 0, 48000) <= 0.5012) // capped at -6 dBFS
    }

    @Test fun channelRouting() {
        val l = gen(SignalConfig(channels = ChannelSel.LEFT), 48000)
        assertTrue(peak(l, 0, 24000, 48000) > 0.01); assertEquals(0.0, peak(l, 1, 0, 48000), 0.0)
        val r = gen(SignalConfig(channels = ChannelSel.RIGHT), 48000)
        assertTrue(peak(r, 1, 24000, 48000) > 0.01); assertEquals(0.0, peak(r, 0, 0, 48000), 0.0)
    }

    @Test fun phaseIsContinuousAcrossBuffers() {
        val cfg = SignalConfig(freqHz = 997.0)
        val whole = gen(cfg, 10000)
        val g = TestSignalGenerator()
        val c1 = ShortArray(2 * 3333); val c2 = ShortArray(2 * 6667)
        g.fill(c1, 3333, cfg, true); g.fill(c2, 6667, cfg, true)
        assertArrayEquals(whole, c1 + c2)
    }

    @Test fun frequencyIsAccurate() {
        val tone = gen(SignalConfig(freqHz = 1000.0), 96000)
        var zc = 0
        for (i in 48001 until 96000) if (tone[2 * (i - 1)] < 0 && tone[2 * i] >= 0) zc++
        assertEquals(1000.0, zc.toDouble(), 2.0)
    }

    @Test fun sweepRises() {
        val sw = gen(SignalConfig(mode = SignalMode.SWEEP, levelDb = -20.0), 20 * 48000)
        fun zcr(from: Int, to: Int): Int { var z = 0; for (i in from + 1 until to) if (sw[2 * (i - 1)] < 0 && sw[2 * i] >= 0) z++; return z }
        assertTrue(zcr(18 * 48000, 19 * 48000) > zcr(48000, 2 * 48000) * 20)
    }

    @Test fun pinkNoiseIsLowFrequencyWeighted() {
        val pn = gen(SignalConfig(mode = SignalMode.PINK_NOISE, levelDb = -12.0), 96000)
        var vs = 0.0; var vd = 0.0
        for (i in 48001 until 96000) {
            val x = pn[2 * i] / 32767.0; val d = x - pn[2 * (i - 1)] / 32767.0
            vs += x * x; vd += d * d
        }
        assertTrue(vd / vs < 1.0) // white noise would be ~2
    }

    @Test fun channelCheckTimeline() {
        val cc = gen(SignalConfig(mode = SignalMode.CHANNEL_CHECK, levelDb = -20.0), 2 * 48000)
        assertTrue(peak(cc, 0, 4800, 22000) > 0.05); assertEquals(0.0, peak(cc, 1, 0, 24000), 0.0)
        assertEquals(0.0, peak(cc, 0, 25000, 47000), 0.0)
        assertTrue(peak(cc, 1, 52000, 70000) > 0.05); assertEquals(0.0, peak(cc, 0, 48000, 72000), 0.0)
    }

    @Test fun polarityFlipsRightChannel() {
        val po = gen(SignalConfig(mode = SignalMode.POLARITY, levelDb = -20.0), 4 * 48000)
        for (i in 24000 until 90000) assertEquals(po[2 * i], po[2 * i + 1])
        for (i in 100000 until 190000) assertTrue(abs(po[2 * i] + po[2 * i + 1]) <= 1)
    }

    @Test fun stopFadesToSilence() {
        val g = TestSignalGenerator()
        g.fill(ShortArray(2 * 48000), 48000, SignalConfig(), true)
        val stop = ShortArray(2 * 4800); g.fill(stop, 4800, SignalConfig(), false)
        assertTrue(peak(stop, 0, 3600, 4800) < 0.001)
    }
}
