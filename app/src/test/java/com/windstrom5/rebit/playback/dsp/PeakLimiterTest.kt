package com.windstrom5.rebit.playback.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class PeakLimiterTest {

    @Test
    fun `quiet signal passes unchanged, only delayed`() {
        val limiter = PeakLimiter(channels = 2, sampleRateHz = 48_000)
        val frames = 4000
        val input = FloatArray(frames * 2) { (0.5 * sin(2 * PI * 1000 * (it / 2) / 48_000.0)).toFloat() }
        val out = input.copyOf()
        limiter.process(out)
        val d = limiter.latencyFrames
        for (f in d until frames) {
            assertEquals(input[(f - d) * 2], out[f * 2], 0f)
            assertEquals(input[(f - d) * 2 + 1], out[f * 2 + 1], 0f)
        }
        assertEquals(0f, limiter.takeMaxReductionDb(), 0f)
    }

    @Test
    fun `nothing ever exceeds the ceiling`() {
        val limiter = PeakLimiter(channels = 2, sampleRateHz = 44_100)
        // +6 dB hot sine with sharp transients mixed in, processed in odd-sized chunks.
        val frames = 44_100
        val signal = FloatArray(frames * 2) { i ->
            val f = i / 2
            val base = 2.0 * sin(2 * PI * 220 * f / 44_100.0)
            (if (f % 997 == 0) 3.0 else base).toFloat()
        }
        var i = 0
        while (i < signal.size) {
            val end = minOf(signal.size, i + 2 * 333)
            val chunk = signal.copyOfRange(i, end)
            limiter.process(chunk)
            chunk.forEach { assertTrue("sample $it over ceiling", abs(it) <= limiter.ceiling + 1e-6f) }
            i = end
        }
        assertTrue(limiter.takeMaxReductionDb() > 5f)
        assertTrue(limiter.limitedFrames > 0)
    }

    @Test
    fun `gain recovers after a single transient`() {
        val rate = 48_000
        val limiter = PeakLimiter(channels = 1, sampleRateHz = rate, releaseMs = 50.0)
        val x = FloatArray(rate) { 0.25f }
        x[1000] = 4f
        limiter.process(x)
        // Half a second later the 0.25 signal is back to (nearly) full level.
        assertEquals(0.25f, x[rate - 1], 0.002f)
        // Around the transient it was pulled down hard.
        assertTrue(x[1000 + limiter.latencyFrames] <= limiter.ceiling)
    }

    @Test
    fun `gain changes smoothly rather than in a step`() {
        val limiter = PeakLimiter(channels = 1, sampleRateHz = 48_000)
        val x = FloatArray(2000) { 0.5f }
        for (k in 1000 until 1100) x[k] = 2f
        limiter.process(x)
        val d = limiter.latencyFrames
        // Output k carries input k - d. Wherever two neighbouring outputs both come from the
        // steady 0.5 part, the gain may only have moved a little between them — a ramp, not a step.
        var maxStep = 0f
        for (k in d + 1 until x.size) {
            val i = k - d
            val steady = (i - 1 !in 1000 until 1100) && (i !in 1000 until 1100)
            if (steady) maxStep = maxOf(maxStep, abs(x[k] - x[k - 1]))
        }
        assertTrue("largest step on the steady part was $maxStep", maxStep < 0.02f)
        // …and it really did come down to make room for the burst.
        assertTrue(x[999 + d] < 0.3f)
    }
}
