package com.windstrom5.rebit.domain.abx

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class AbxDspTest {

    private fun stereoSine(freq: Float, rate: Int, frames: Int, startFrame: Int = 0): FloatArray {
        val out = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val v = sin(2.0 * PI * freq * (startFrame + i) / rate).toFloat() * 0.5f
            out[i * 2] = v
            out[i * 2 + 1] = v
        }
        return out
    }

    private fun rms(a: FloatArray, from: Int = 0): Float {
        var s = 0.0
        for (i in from until a.size) s += a[i] * a[i]
        return sqrt(s / (a.size - from)).toFloat()
    }

    @Test
    fun lowpass_passesLowTones_andRemovesHighOnes() {
        val rate = 48_000
        val low = stereoSine(1_000f, rate, 4096).also { StereoLowpass(15_000f, rate).process(it) }
        val high = stereoSine(20_000f, rate, 4096).also { StereoLowpass(15_000f, rate).process(it) }
        // skip the first 1024 samples: filter settling
        assertTrue("1 kHz should pass", rms(low, 2048) > 0.33f)
        assertTrue("20 kHz should be strongly attenuated", rms(high, 2048) < 0.08f)
    }

    @Test
    fun lowpass_isContinuousAcrossChunks_noBoundaryClick() {
        // The old filter restarted from zero every chunk and clicked at each boundary.
        val rate = 48_000
        val whole = stereoSine(300f, rate, 4096)
        StereoLowpass(15_000f, rate).process(whole)

        val chunked = stereoSine(300f, rate, 4096)
        val f = StereoLowpass(15_000f, rate)
        val chunkFloats = 1024 * 2
        var off = 0
        while (off < chunked.size) {
            val piece = chunked.copyOfRange(off, off + chunkFloats)
            f.process(piece)
            System.arraycopy(piece, 0, chunked, off, chunkFloats)
            off += chunkFloats
        }
        for (i in whole.indices) assertEquals("sample $i", whole[i], chunked[i], 1e-5f)
    }

    @Test
    fun lowpass_aboveNyquistCutoff_isANoOp() {
        val rate = 44_100
        val x = stereoSine(5_000f, rate, 256)
        val y = x.copyOf()
        StereoLowpass(21_900f, rate).process(y) // > 0.49 * rate
        for (i in x.indices) assertEquals(x[i], y[i], 0f)
    }

    @Test
    fun sampleBProcessor_changesTheSignal_forEveryMode() {
        val rate = 48_000
        for (mode in AbxTestMode.entries) {
            val a = stereoSine(12_000f, rate, 2048)
            val b = a.copyOf()
            AbxSampleBProcessor(mode, rate).process(b)
            var diff = 0f
            for (i in a.indices) diff += abs(a[i] - b[i])
            assertTrue("$mode should alter the signal", diff > 0f)
            assertTrue("$mode stays in range", b.all { it in -1f..1f })
        }
    }

    @Test
    fun pcmConversion_16bit_24bit_32bit_and_mono() {
        // 16-bit stereo: L = +16384 (0.5), R = -16384 (-0.5)
        val b16 = byteArrayOf(0x00, 0x40, 0x00, 0xC0.toByte())
        val f16 = AbxPcm.toStereoFloats(b16, 4, 16, 2)
        assertEquals(0.5f, f16[0], 1e-4f); assertEquals(-0.5f, f16[1], 1e-4f)

        // 24-bit stereo: L = +0x400000 (0.5), R = -0x400000 (-0.5)
        val b24 = byteArrayOf(0x00, 0x00, 0x40, 0x00, 0x00, 0xC0.toByte())
        val f24 = AbxPcm.toStereoFloats(b24, 6, 24, 2)
        assertEquals(0.5f, f24[0], 1e-5f); assertEquals(-0.5f, f24[1], 1e-5f)

        // 32-bit mono is duplicated to both ears
        val b32 = byteArrayOf(0x00, 0x00, 0x00, 0x40)
        val f32 = AbxPcm.toStereoFloats(b32, 4, 32, 1)
        assertEquals(2, f32.size)
        assertEquals(0.5f, f32[0], 1e-6f); assertEquals(f32[0], f32[1], 0f)

        // a trailing partial frame is dropped
        assertEquals(2, AbxPcm.toStereoFloats(byteArrayOf(0, 0, 0, 0, 1), 5, 16, 2).size)
    }

    @Test
    fun clip_reportsFramesAndDuration() {
        val clip = AbxClip("t", null, 48_000, FloatArray(48_000 * 2), 0L)
        assertEquals(48_000, clip.frames)
        assertEquals(1000L, clip.durationMs)
    }
}
