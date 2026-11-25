package com.windstrom5.rebit.playback.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class KeyShifterTest {

    private val rate = 48_000

    /** Dominant frequency by zero-crossing count over the second half of the output. */
    private fun shiftedFrequency(semitones: Int, inputHz: Double): Double {
        val frames = rate * 2
        val x = FloatArray(frames * 2) { i -> (0.5 * sin(2 * PI * inputHz * (i / 2) / rate)).toFloat() }
        val ks = KeyShifter(rate)
        var i = 0
        while (i < x.size) {
            val end = minOf(x.size, i + 2 * 512)
            val chunk = x.copyOfRange(i, end)
            ks.process(chunk, semitones)
            System.arraycopy(chunk, 0, x, i, chunk.size)
            i = end
        }
        var crossings = 0
        val start = frames / 2
        for (t in start + 1 until frames) if ((x[2 * (t - 1)] < 0f) != (x[2 * t] < 0f)) crossings++
        return crossings / 2.0 / ((frames - start) / rate.toDouble())
    }

    @Test
    fun `positive semitones raise the pitch`() {
        val f = shiftedFrequency(+12, 220.0)
        assertEquals("octave up of 220 Hz", 440.0, f, 25.0)
    }

    @Test
    fun `negative semitones lower the pitch`() {
        val f = shiftedFrequency(-12, 440.0)
        assertEquals("octave down of 440 Hz", 220.0, f, 15.0)
    }

    @Test
    fun `small shifts land on the right note`() {
        val f = shiftedFrequency(+2, 440.0)
        // 440 · 2^(2/12) = 493.9 Hz
        assertTrue("got $f", abs(f - 493.9) < 15.0)
    }

    @Test
    fun `zero semitones is a passthrough`() {
        val x = FloatArray(1000) { it * 0.001f }
        val copy = x.copyOf()
        KeyShifter(rate).process(x, 0)
        for (i in x.indices) assertEquals(copy[i], x[i], 0f)
    }
}
