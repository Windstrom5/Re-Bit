package com.windstrom5.rebit.domain.audiophile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

class TrackAnalyzerTest {
    private val sr = 8000

    private fun encode(samples: DoubleArray, bits: Int): ByteArray {
        val bps = bits / 8
        val scale = when (bits) { 16 -> 32768.0; 24 -> 8388608.0; else -> 2147483648.0 }
        val out = ByteArray(samples.size * bps)
        for (i in samples.indices) {
            val v = (samples[i] * scale).roundToLong().coerceIn(-scale.toLong(), scale.toLong() - 1).toInt()
            for (k in 0 until bps) out[i * bps + k] = ((v shr (8 * k)) and 0xFF).toByte()
        }
        return out
    }

    private fun sine(secs: Int, amp: Double, f: Double = 1000.0, ch: Int = 1) =
        DoubleArray(sr * secs * ch) { i -> amp * sin(2 * PI * f * (i / ch) / sr) }

    private fun analyze(s: DoubleArray, ch: Int = 1, bits: Int = 16, chunk: Int = Int.MAX_VALUE): TrackAnalysis? {
        val a = TrackAnalyzer(sr, ch, bits)
        val b = encode(s, bits)
        var p = 0
        while (p < b.size) { val n = min(chunk, b.size - p); a.feed(b.copyOfRange(p, p + n), n); p += n }
        return a.finish()
    }

    @Test fun fullScaleSine() {
        val r = analyze(sine(30, 0.999))!!
        assertEquals(0.0, r.peakDb, 0.05)
        assertEquals(-3.01, r.rmsDb, 0.05)
        assertEquals(3.0, r.crestDb, 0.1)
        assertEquals(0, r.drScore) // a constant tone has no dynamics
    }

    @Test fun headroomIsMinusPeak() = assertEquals(20.0, analyze(sine(30, 0.1))!!.headroomDb, 0.05)

    @Test fun dynamicRangeMatchesHandCalculation() {
        // 10 blocks: 2 loud (A=0.2) and 8 quiet (A=0.02), with a 0.8 spike in each loud block.
        val s = DoubleArray(sr * 30)
        for (i in s.indices) { val blk = i / (sr * 3); s[i] = (if (blk == 2 || blk == 7) 0.2 else 0.02) * sin(2 * PI * 220 * i / sr) }
        s[sr * 3 * 2 + 100] = 0.8; s[sr * 3 * 7 + 100] = 0.8
        assertEquals(12, analyze(s)!!.drScore) // 20·log10(0.8 / 0.2)
    }

    @Test fun aLoneSpikeDoesNotInflateDr() {
        val s = DoubleArray(sr * 30)
        for (i in s.indices) { val blk = i / (sr * 3); s[i] = (if (blk == 2 || blk == 7) 0.2 else 0.02) * sin(2 * PI * 220 * i / sr) }
        s[sr * 3 * 2 + 100] = 0.8 // only ONE spike: the second-highest block peak is used
        assertTrue(analyze(s)!!.drScore < 12)
    }

    @Test fun allBitDepthsAgree() {
        val s = sine(12, 0.3, 330.0)
        val a16 = analyze(s, bits = 16)!!; val a24 = analyze(s, bits = 24)!!; val a32 = analyze(s, bits = 32)!!
        assertEquals(a16.peakDb, a24.peakDb, 0.01)
        assertEquals(a24.rmsDb, a32.rmsDb, 0.01)
    }

    @Test fun chunkBoundariesDoNotMatter() {
        val s = sine(10, 0.3, 500.0, ch = 2)
        assertEquals(analyze(s, 2, 24), analyze(s, 2, 24, chunk = 1001))
    }

    @Test fun detectsClippingAndDc() {
        val clipped = DoubleArray(sr * 10) { i -> (1.6 * sin(2 * PI * 100 * i / sr)).coerceIn(-1.0, 0.99996) }
        assertTrue(analyze(clipped)!!.hasClipping)
        assertFalse(analyze(sine(30, 0.5))!!.hasClipping)
        val dc = analyze(DoubleArray(sr * 10) { i -> 0.1 + 0.3 * sin(2 * PI * 200 * i / sr) })!!
        assertEquals(10.0, dc.dcOffsetPercent, 0.2)
    }

    @Test fun edgeCases() {
        assertNull(analyze(DoubleArray(sr * 5)))                 // silence
        assertNull(TrackAnalyzer(sr, 2, 8).also { it.feed(ByteArray(100)) }.finish()) // unsupported depth
        assertNotNull(analyze(sine(1, 0.5)))                      // shorter than one 3 s block
    }

    @Test fun ratingBands() {
        fun rating(dr: Int) = TrackAnalysis(dr, 0.0, -dr.toDouble(), dr.toDouble(), 0, 0.0, 1.0).drRating
        assertEquals("Excellent dynamics", rating(15))
        assertEquals("Good dynamics", rating(12))
        assertEquals("Moderately compressed", rating(9))
        assertEquals("Heavily compressed", rating(5))
    }
}

class AudioFactsTest {
    @Test fun derivedNumbers() {
        assertEquals(4608L, AudioFacts.nominalBitrateKbps(96000, 24, 2))
        assertEquals(98, AudioFacts.theoreticalDynamicRangeDb(16))
        assertEquals(146, AudioFacts.theoreticalDynamicRangeDb(24))
        assertEquals(22.05, AudioFacts.nyquistKhz(44100), 1e-9)
        assertTrue(AudioFacts.sizePercentOfPcm(30_000_000, 300_000, 44100, 16, 2)!! in 55..60)
        assertNull(AudioFacts.sizePercentOfPcm(0, 1000, 44100, 16, 2))
    }

    @Test fun effectiveBitsRevealPaddedMasters() {
        assertEquals(24, AudioFacts.effectiveBits(0x00FFFFFF, 24))
        assertEquals(16, AudioFacts.effectiveBits(0x00FFFF00, 24)) // 16-bit master inside a 24-bit file
        assertNull(AudioFacts.effectiveBits(0, 24))
    }

    @Test fun bufferLatencyUsesTheRealFormat() {
        assertEquals(10.667, AudioFacts.bufferLatencyMs(2048, 48000, 16, 2), 0.01)
        assertEquals(2.667, AudioFacts.bufferLatencyMs(512, 48000, 16, 2), 0.01)
    }
}
