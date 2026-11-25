package com.windstrom5.rebit.domain.audiophile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

class SpectrumAnalysisTest {

    private fun tones(t: Double, vararg hz: Double): Double = hz.sumOf { sin(2.0 * PI * it * t) } * 0.15

    /** Interleaved stereo 16-bit little-endian. */
    private fun pcm16(seconds: Int, rate: Int, left: (Double) -> Double, right: (Double) -> Double): ByteArray {
        val frames = seconds * rate
        val out = ByteArray(frames * 4)
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            val l = (left(t) * 32767).roundToInt().coerceIn(-32768, 32767)
            val r = (right(t) * 32767).roundToInt().coerceIn(-32768, 32767)
            out[i * 4] = l.toByte(); out[i * 4 + 1] = (l shr 8).toByte()
            out[i * 4 + 2] = r.toByte(); out[i * 4 + 3] = (r shr 8).toByte()
        }
        return out
    }

    private fun analyze(bytes: ByteArray, rate: Int, bits: Int, seconds: Int): TrackAnalysis? {
        val a = TrackAnalyzer(rate, 2, bits, expectedFrames = seconds.toLong() * rate)
        var off = 0
        while (off < bytes.size) {
            val n = minOf(65_536, bytes.size - off)
            a.feed(bytes.copyOfRange(off, off + n), n)
            off += n
        }
        return a.finish()
    }

    @Test
    fun fft_findsTheBinOfASine() {
        val n = 1024
        val re = DoubleArray(n) { sin(2.0 * PI * 64 * it / n) }
        val im = DoubleArray(n)
        Fft.transform(re, im)
        val mags = DoubleArray(n / 2) { Math.hypot(re[it], im[it]) }
        assertEquals(64, mags.indices.maxByOrNull { mags[it] })
        assertEquals(n / 2.0, mags[64], 1e-6)
    }

    @Test
    fun analyzer_measuresBandwidthOfAContentLimitedSignal() {
        val rate = 44_100
        val x = pcm16(6, rate, { t -> tones(t, 1_000.0, 5_000.0, 10_000.0) }, { t -> tones(t, 1_000.0, 5_000.0, 10_000.0) })
        val r = analyze(x, rate, 16, 6)
        assertNotNull(r)
        r!!
        // nothing above ~10 kHz: highest content within the main lobe + leakage of the 10 kHz tone
        assertTrue("bandwidth ${r.bandwidthHz}", r.bandwidthHz in 9_800..11_000)
        val s = r.spectrogram
        assertNotNull(s)
        assertTrue(s!!.columns > 100)
        assertEquals(22_050, s.nyquistHz)
        // the 5 kHz band row is lit, the 18 kHz band is dark
        val rowAt = { hz: Int -> hz * s.rows / s.nyquistHz }
        assertTrue(s.db(s.columns / 2, rowAt(5_000)) > -40f)
        assertTrue(s.db(s.columns / 2, rowAt(18_000)) < -90f)
    }

    @Test
    fun analyzer_flagsMonoAndComputesMidSideRatio() {
        val rate = 44_100
        val same = { t: Double -> tones(t, 440.0, 2_000.0) }
        val mono = analyze(pcm16(4, rate, same, same), rate, 16, 4)!!
        assertTrue(mono.effectivelyMono)

        // R = half of L: mid = 0.75 L, side = 0.25 L, so energy ratio = 9
        val stereo = analyze(pcm16(4, rate, same, { t -> same(t) * 0.5 }), rate, 16, 4)!!
        assertFalse(stereo.effectivelyMono)
        assertEquals(9.0, stereo.midSideRatio, 0.5)
    }

    @Test
    fun analyzer_detects16BitAudioPaddedInto24Bit() {
        val rate = 44_100
        val frames = 4 * rate
        val out = ByteArray(frames * 6)
        for (i in 0 until frames) {
            val v16 = (tones(i.toDouble() / rate, 440.0, 1_500.0) * 32767).roundToInt()
            val v24 = v16 shl 8 // the low byte is always zero
            for (c in 0..1) {
                val o = i * 6 + c * 3
                out[o] = (v24 and 0xFF).toByte(); out[o + 1] = ((v24 shr 8) and 0xFF).toByte(); out[o + 2] = ((v24 shr 16) and 0xFF).toByte()
            }
        }
        val r = analyze(out, rate, 24, 4)!!
        assertEquals(16, r.effectiveBitDepth)
    }

    @Test
    fun analyzer_fullDepth16BitReports16() {
        val rate = 44_100
        val r = analyze(pcm16(4, rate, { tones(it, 300.0) }, { tones(it, 300.0) }), rate, 16, 4)!!
        assertEquals(16, r.effectiveBitDepth)
    }

    // ── QualityAssessment ──

    private fun analysis(
        bandwidth: Int = 21_000, dr: Int = 12, clipped: Long = 0, mono: Boolean = false,
        ms: Double = 2.5, effBits: Int = 16, total: Long = 10_000_000
    ) = TrackAnalysis(
        drScore = dr, peakDb = -0.1, rmsDb = -12.0, crestDb = 9.8, clippedSamples = clipped, dcOffsetPercent = 0.0,
        analyzedSeconds = 200.0, bandwidthHz = bandwidth, midSideRatio = ms, effectivelyMono = mono,
        effectiveBitDepth = effBits, totalSamples = total
    )

    private fun QualityItemList.find(titlePart: String) = first { it.title.contains(titlePart, ignoreCase = true) }

    @Test
    fun withoutMeasurement_onlyFormatItemsAppear() {
        val items = QualityAssessment.build("FLAC", true, 44_100, 16, null)
        assertEquals(3, items.size)
        assertEquals(QualityLevel.GOOD, items.find("Lossless").level)
        assertTrue(items.find("Sample rate").title.contains("CD standard"))
        assertTrue(items.find("16-bit").title.contains("CD standard"))
    }

    @Test
    fun fullBandwidth_isGood_andACutoffAt16k_warnsAboutLossySources() {
        val full = QualityAssessment.build("FLAC", true, 44_100, 16, analysis(bandwidth = 21_500))
        assertEquals(QualityLevel.GOOD, full.find("spectral").level)

        val cut = QualityAssessment.build("FLAC", true, 44_100, 16, analysis(bandwidth = 16_000))
        val item = cut.find("Limited bandwidth")
        assertEquals(QualityLevel.WARN, item.level)
        assertTrue(item.detail.contains("MP3"))
    }

    @Test
    fun hiResFileWithCdBandwidth_isReportedAsUpsampled() {
        val items = QualityAssessment.build("FLAC", true, 96_000, 24, analysis(bandwidth = 22_000, effBits = 24))
        assertEquals(QualityLevel.INFO, items.find("CD-rate").level)
    }

    @Test
    fun paddedBitDepth_isWarned() {
        val items = QualityAssessment.build("FLAC", true, 48_000, 24, analysis(effBits = 16))
        val item = items.find("container holds 16-bit")
        assertEquals(QualityLevel.WARN, item.level)
    }

    @Test
    fun clippingAndDynamicsAndStereoItems() {
        val minor = QualityAssessment.build("FLAC", true, 44_100, 16, analysis(clipped = 1_100, total = 10_000_000))
        assertEquals("Minor clipping", minor.find("clipping").title)
        assertTrue(minor.find("clipping").detail.startsWith("0.011%"))

        assertEquals(QualityLevel.GOOD, QualityAssessment.build("FLAC", true, 44_100, 16, analysis(dr = 12)).find("dynamic range").level)
        assertEquals(QualityLevel.WARN, QualityAssessment.build("FLAC", true, 44_100, 16, analysis(dr = 5)).find("compressed").level)

        assertTrue(QualityAssessment.build("FLAC", true, 44_100, 16, analysis(mono = true)).any { it.title == "Mono recording" })
        assertTrue(QualityAssessment.build("FLAC", true, 44_100, 16, analysis(ms = 2.51)).find("Stereo balance").detail.contains("2.51"))
    }

    @Test
    fun spectrogramLevels_mapZeroTo255AndMinus120To0() {
        val d = SpectrogramData(1, 2, 44_100, byteArrayOf(0, 255.toByte()))
        assertEquals(-120f, d.db(0, 0), 0.01f)
        assertEquals(0f, d.db(0, 1), 0.01f)
        assertTrue(abs(d.nyquistHz - 22_050) == 0)
    }
}

private typealias QualityItemList = List<QualityItem>
