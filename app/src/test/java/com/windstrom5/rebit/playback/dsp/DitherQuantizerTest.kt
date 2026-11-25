package com.windstrom5.rebit.playback.dsp

import com.windstrom5.rebit.domain.model.DitherMode
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class DitherQuantizerTest {

    @Test
    fun `16-bit source survives the float round trip unchanged`() {
        val rnd = Random(7)
        val pcm = ByteArray(4096 * 2) { rnd.nextInt(256).toByte() }
        // Make sure both extremes are in there.
        pcm[0] = 0x00; pcm[1] = 0x80.toByte()   // -32768
        pcm[2] = 0xFF.toByte(); pcm[3] = 0x7F    // +32767
        val floats = PcmCodec.toFloat(pcm, pcm.size, 16)
        val q = DitherQuantizer(16, 2, DitherMode.NOISE_SHAPED, 44_100)
        val back = PcmCodec.fromFloat(floats, floats.size, 16, 2, q, dither = false)
        assertArrayEquals(pcm, back)
    }

    @Test
    fun `24-bit source survives the float round trip unchanged`() {
        val rnd = Random(11)
        val pcm = ByteArray(3000 * 3) { rnd.nextInt(256).toByte() }
        val floats = PcmCodec.toFloat(pcm, pcm.size, 24)
        val q = DitherQuantizer(24, 2, DitherMode.TPDF, 96_000)
        assertArrayEquals(pcm, PcmCodec.fromFloat(floats, floats.size, 24, 2, q, dither = false))
    }

    @Test
    fun `rounding clamps at both rails`() {
        val q = DitherQuantizer(16, 1, DitherMode.OFF, 48_000)
        assertEquals(32767L, q.quantize(1.5f, 0))
        assertEquals(-32768L, q.quantize(-1.5f, 0))
        assertEquals(0L, q.quantize(0.4f / 32768f, 0))
    }

    @Test
    fun `TPDF dither preserves a signal smaller than one LSB on average`() {
        // A constant 0.3 LSB rounds to 0 without dither; with TPDF the mean output is 0.3 LSB.
        val level = 0.3f / 32768f
        val plain = DitherQuantizer(16, 1, DitherMode.OFF, 48_000)
        val dithered = DitherQuantizer(16, 1, DitherMode.TPDF, 48_000)
        val n = 200_000
        var sumPlain = 0.0
        var sumDither = 0.0
        repeat(n) {
            sumPlain += plain.quantize(level, 0)
            sumDither += dithered.quantize(level, 0)
        }
        assertEquals(0.0, sumPlain / n, 1e-9)
        assertEquals(0.3, sumDither / n, 0.02)
    }

    @Test
    fun `TPDF error stays within plus-minus 1_5 LSB`() {
        val q = DitherQuantizer(16, 1, DitherMode.TPDF, 48_000)
        val rnd = Random(3)
        repeat(50_000) {
            val x = rnd.nextFloat() * 1.8f - 0.9f
            val err = q.quantize(x, 0) - x * 32768.0
            assertTrue("error $err", err in -1.5..1.5)
        }
    }

    @Test
    fun `noise shaping moves noise out of the 1-4 kHz band`() {
        val rate = 44_100
        val n = 1 shl 14
        val signal = FloatArray(n) { (0.01 * sin(2 * PI * 440 * it / rate)).toFloat() }

        fun bandNoise(mode: DitherMode, loHz: Double, hiHz: Double): Double {
            val q = DitherQuantizer(16, 1, mode, rate, seed = 12345)
            val err = DoubleArray(n) { q.quantize(signal[it], 0) / 32768.0 - signal[it] }
            // Plain DFT power summed over the band's bins (Hann-windowed).
            var power = 0.0
            val lo = (loHz * n / rate).toInt()
            val hi = (hiHz * n / rate).toInt()
            for (k in lo..hi) {
                var re = 0.0
                var im = 0.0
                for (t in 0 until n) {
                    val w = 0.5 - 0.5 * cos(2 * PI * t / (n - 1))
                    val v = err[t] * w
                    val ang = 2 * PI * k * t / n
                    re += v * cos(ang)
                    im -= v * sin(ang)
                }
                power += re * re + im * im
            }
            return power
        }

        val flatMid = bandNoise(DitherMode.TPDF, 1000.0, 4000.0)
        val shapedMid = bandNoise(DitherMode.NOISE_SHAPED, 1000.0, 4000.0)
        assertTrue("shaped mid-band noise should be well below TPDF ($shapedMid vs $flatMid)", shapedMid < flatMid * 0.5)

        val flatTop = bandNoise(DitherMode.TPDF, 17_000.0, 21_000.0)
        val shapedTop = bandNoise(DitherMode.NOISE_SHAPED, 17_000.0, 21_000.0)
        assertTrue("shaped noise should rise in the top octave", shapedTop > flatTop)
    }

    @Test
    fun `noise shaping is only used at 16 bits and below`() {
        assertTrue(DitherQuantizer(16, 2, DitherMode.NOISE_SHAPED, 48_000).isNoiseShaped)
        assertTrue(!DitherQuantizer(24, 2, DitherMode.NOISE_SHAPED, 48_000).isNoiseShaped)
    }
}
