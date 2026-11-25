package com.windstrom5.rebit.domain.audiophile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Cases from EBU Tech 3341 (loudness) and 3342 (loudness range) conformance material. */
class LoudnessMeterTest {

    private fun feedSine(m: LoudnessMeter, rate: Int, seconds: Double, dbfs: Double, freq: Double = 1000.0, phase: Double = 0.0) {
        val amp = 10.0.pow(dbfs / 20.0)
        val frame = DoubleArray(2)
        val n = (rate * seconds).toInt()
        for (t in 0 until n) {
            val v = amp * sin(2 * PI * freq * t / rate + phase)
            frame[0] = v; frame[1] = v
            m.addFrame(frame)
        }
    }

    @Test
    fun `3341 case 1 - stereo 1 kHz at -23 dBFS reads -23 LUFS`() {
        for (rate in intArrayOf(44_100, 48_000, 96_000)) {
            val m = LoudnessMeter(rate, 2)
            feedSine(m, rate, 20.0, -23.0)
            assertEquals("at $rate Hz", -23.0, m.finish().integratedLufs!!, 0.1)
        }
    }

    @Test
    fun `3341 case 2 - stereo 1 kHz at -33 dBFS reads -33 LUFS`() {
        val m = LoudnessMeter(48_000, 2)
        feedSine(m, 48_000, 20.0, -33.0)
        assertEquals(-33.0, m.finish().integratedLufs!!, 0.1)
    }

    @Test
    fun `3341 case 3 - gating ignores the quiet parts`() {
        // 10 s at -36, 60 s at -23, 10 s at -36  →  -23 LUFS
        val m = LoudnessMeter(48_000, 2)
        feedSine(m, 48_000, 10.0, -36.0)
        feedSine(m, 48_000, 60.0, -23.0)
        feedSine(m, 48_000, 10.0, -36.0)
        assertEquals(-23.0, m.finish().integratedLufs!!, 0.1)
    }

    @Test
    fun `3341 case 5 - absolute gate drops near-silence`() {
        // 20 s at -26, 20.1 s at -20, 20 s at -26 → -23 (the -72 part of the original is below the gate)
        val m = LoudnessMeter(48_000, 2)
        feedSine(m, 48_000, 20.0, -26.0)
        feedSine(m, 48_000, 20.1, -20.0)
        feedSine(m, 48_000, 20.0, -26.0)
        assertEquals(-23.0, m.finish().integratedLufs!!, 0.1)
    }

    @Test
    fun `3342 case 1 - 10 dB step gives about 10 LU of loudness range`() {
        val m = LoudnessMeter(48_000, 2)
        feedSine(m, 48_000, 20.0, -20.0)
        feedSine(m, 48_000, 20.0, -30.0)
        assertEquals(10.0, m.finish().loudnessRangeLu!!, 1.0)
    }

    @Test
    fun `3342 case 2 - 5 dB step gives about 5 LU`() {
        val m = LoudnessMeter(48_000, 2)
        feedSine(m, 48_000, 20.0, -20.0)
        feedSine(m, 48_000, 20.0, -15.0)
        assertEquals(5.0, m.finish().loudnessRangeLu!!, 1.0)
    }

    @Test
    fun `true peak catches the peak between samples`() {
        // fs/4 sine with a 45° phase: every sample lands at ±0.707 of the real peak.
        val rate = 48_000
        val m = LoudnessMeter(rate, 2)
        feedSine(m, rate, 2.0, 0.0, freq = rate / 4.0, phase = PI / 4)
        val r = m.finish()
        assertEquals(-3.01, r.samplePeakDbfs, 0.05)
        assertTrue("true peak ${r.truePeakDbtp} should be close to 0 dBTP", r.truePeakDbtp > -0.6 && r.truePeakDbtp < 0.3)
    }

    @Test
    fun `silence has no integrated loudness`() {
        val m = LoudnessMeter(44_100, 2)
        val zero = DoubleArray(2)
        repeat(44_100 * 5) { m.addFrame(zero) }
        assertNull(m.finish().integratedLufs)
    }
}
