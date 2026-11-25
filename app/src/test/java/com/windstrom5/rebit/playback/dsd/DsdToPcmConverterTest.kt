package com.windstrom5.rebit.playback.dsd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class DsdToPcmConverterTest {

    /** 2nd-order sigma-delta modulator → interleaved MSB-first DSD frames (one byte/channel). */
    private fun modulate(signal: (ch: Int, t: Int) -> Double, channels: Int, bytes: Int): ByteArray {
        val out = ByteArray(bytes * channels)
        val i1 = DoubleArray(channels)
        val i2 = DoubleArray(channels)
        val y = DoubleArray(channels) { 1.0 }
        for (b in 0 until bytes) for (ch in 0 until channels) {
            var v = 0
            for (bit in 0 until 8) {
                val x = signal(ch, b * 8 + bit)
                i1[ch] += x - y[ch]
                i2[ch] += i1[ch] - y[ch]
                y[ch] = if (i2[ch] >= 0) 1.0 else -1.0
                v = (v shl 1) or (if (y[ch] > 0) 1 else 0) // first sample → MSB
            }
            out[b * channels + ch] = v.toByte()
        }
        return out
    }

    @Test
    fun `DSD64 sine comes back as an 88_2 kHz sine at the same level`() {
        val dsdRate = 2_822_400
        val amp = 0.5
        val f = 1000.0
        val bytes = dsdRate / 8 // one second per channel
        val dsd = modulate({ ch, t -> amp * sin(2 * PI * f * t / dsdRate + ch * 0.5) }, 2, bytes)

        val conv = DsdToPcmConverter(dsdRate, 2)
        assertEquals(88_200, conv.outputRateHz)
        // Feed in odd-sized chunks like a decoder would.
        val outs = ArrayList<Float>()
        var pos = 0
        while (pos < bytes) {
            val n = minOf(1234, bytes - pos)
            conv.process(dsd.copyOfRange(pos * 2, (pos + n) * 2), n).forEach { outs += it }
            pos += n
        }
        val pcm = outs.toFloatArray()
        assertEquals(88_200 * 2, pcm.size)

        // Skip the filter warm-up, then fit against the ideal sine (allowing for the filter's delay).
        val start = 4000
        var bestCorr = 0.0
        var bestRms = 0.0
        for (delay in 0..40) {
            var dot = 0.0; var e1 = 0.0; var e2 = 0.0
            for (n in start until 80_000) {
                val ideal = amp * sin(2 * PI * f * (n - delay) / 88_200.0)
                val v = pcm[2 * n].toDouble()
                dot += v * ideal; e1 += v * v; e2 += ideal * ideal
            }
            val corr = dot / sqrt(e1 * e2)
            if (corr > bestCorr) { bestCorr = corr; bestRms = sqrt(e1 / (80_000 - start)) }
        }
        assertTrue("correlation $bestCorr", bestCorr > 0.995)
        assertEquals(amp / sqrt(2.0), bestRms, 0.02) // same level (unity gain)
    }

    @Test
    fun `rates and decimation`() {
        assertEquals(88_200, DsdToPcmConverter(5_644_800, 2).outputRateHz)
        assertEquals(64, DsdToPcmConverter(5_644_800, 2).decimation)
        assertEquals(88_200, DsdToPcmConverter(11_289_600, 2).outputRateHz)
    }

    @Test
    fun `idle pattern decodes to near silence`() {
        val conv = DsdToPcmConverter(2_822_400, 1)
        val out = conv.process(ByteArray(40_000) { DsdToPcmConverter.SILENCE }, 40_000)
        val tail = out.copyOfRange(out.size / 2, out.size)
        assertTrue(tail.all { abs(it) < 0.01f })
    }
}
