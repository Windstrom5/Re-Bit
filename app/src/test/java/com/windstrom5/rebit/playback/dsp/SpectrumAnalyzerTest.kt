package com.windstrom5.rebit.playback.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

class SpectrumAnalyzerTest {

    private val rate = 48_000

    private fun sine(freq: Double, amp: Double, n: Int = 4096) =
        FloatArray(n) { (amp * sin(2 * PI * freq * it / rate)).toFloat() }

    private fun band(f: Double) = SpectrumAnalyzer.BAND_CENTERS.indexOfFirst { it == f }

    @Test
    fun `a sine reads its level in its own band`() {
        val a = SpectrumAnalyzer(rate)
        for ((freq, db) in listOf(1000.0 to -6.0, 100.0 to -20.0, 8000.0 to 0.0)) {
            val amp = Math.pow(10.0, db / 20.0)
            val s = sine(freq, amp)
            val r = a.analyze(s, s, s.size)
            assertEquals("$freq Hz", db, r.bandsDb[band(freq)].toDouble(), 1.0)
            // A band an octave away is far below.
            val other = if (freq < 2000) band(freq * 4) else band(freq / 4)
            assertTrue("$freq Hz leaks into band ${SpectrumAnalyzer.BAND_CENTERS[other]}", r.bandsDb[other] < db - 40)
        }
    }

    @Test
    fun `correlation is +1 for mono, -1 for inverted, near 0 for unrelated`() {
        val a = SpectrumAnalyzer(rate)
        val s = sine(440.0, 0.5)
        assertEquals(1f, a.analyze(s, s, s.size).correlation, 1e-4f)
        val inv = FloatArray(s.size) { -s[it] }
        assertEquals(-1f, a.analyze(s, inv, s.size).correlation, 1e-4f)
        val rnd = Random(1)
        val n1 = FloatArray(4096) { rnd.nextFloat() - 0.5f }
        val n2 = FloatArray(4096) { rnd.nextFloat() - 0.5f }
        assertTrue(kotlin.math.abs(a.analyze(n1, n2, 4096).correlation) < 0.1f)
    }

    @Test
    fun `silence reads the floor`() {
        val a = SpectrumAnalyzer(rate)
        val z = FloatArray(4096)
        val r = a.analyze(z, z, 4096)
        assertTrue(r.bandsDb.all { it == SpectrumAnalyzer.FLOOR_DB })
        assertEquals(0f, r.correlation, 0f)
    }

    @Test
    fun `level meter keeps the most recent frames in order`() {
        val m = LevelMeter()
        val first = FloatArray(2 * 3000) { (it / 2).toFloat() }
        val second = FloatArray(2 * 3000) { (3000 + it / 2).toFloat() }
        m.measure(first, first.size, 2)
        m.measure(second, second.size, 2)
        val l = FloatArray(LevelMeter.HISTORY)
        val r = FloatArray(LevelMeter.HISTORY)
        val n = m.copyRecent(l, r)
        assertEquals(LevelMeter.HISTORY, n)
        assertEquals(6000f - LevelMeter.HISTORY, l[0], 0f)
        assertEquals(5999f, l[n - 1], 0f)
    }
}
