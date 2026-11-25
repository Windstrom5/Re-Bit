package com.windstrom5.rebit.playback.dsp

import com.windstrom5.rebit.playback.ai.Fft
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * 1/3-octave real-time analyzer (the 31 ISO bands, 20 Hz – 20 kHz) plus a stereo phase
 * correlation readout, computed from a window of recent output samples.
 *
 * Calibration: a full-scale sine reads 0 dB in its band, so the display is in dBFS and agrees
 * with the level meter. Pure Kotlin; one instance per UI consumer (not thread-safe).
 */
class SpectrumAnalyzer(private val sampleRateHz: Int, val size: Int = 4096) {

    init { require(size > 0 && size and (size - 1) == 0) { "size must be a power of two" } }

    private val fft = Fft(size)
    private val window = FloatArray(size) { (0.5 - 0.5 * cos(2 * PI * it / size)).toFloat() }
    private val windowPower = window.sumOf { (it * it).toDouble() }
    private val re = FloatArray(size)
    private val im = FloatArray(size)
    private val bins = size / 2 + 1
    private val binHz = sampleRateHz.toDouble() / size

    /** [first, last] FFT bin of each band (bands above Nyquist are empty). */
    private val bandRanges: Array<IntArray> = Array(BAND_CENTERS.size) { b ->
        val lo = BAND_CENTERS[b] / 2.0.pow(1.0 / 6.0)
        val hi = BAND_CENTERS[b] * 2.0.pow(1.0 / 6.0)
        val first = max(1, kotlin.math.ceil(lo / binHz).toInt())
        val last = minOf(bins - 1, kotlin.math.floor(hi / binHz).toInt())
        intArrayOf(first, last)
    }

    data class Frame(
        /** dBFS per band, [BAND_CENTERS] order; [FLOOR_DB] for empty/silent bands. */
        val bandsDb: FloatArray,
        /** −1 (out of phase) … 0 (unrelated / very wide) … +1 (mono). */
        val correlation: Float
    )

    /** Analyses the most recent [size] frames of [left]/[right] (shorter input is zero-padded). */
    fun analyze(left: FloatArray, right: FloatArray, frames: Int): Frame {
        val n = minOf(frames, size)
        var lr = 0.0; var ll = 0.0; var rr = 0.0
        for (i in 0 until size) {
            if (i < n) {
                val l = left[i]; val r = right[i]
                lr += l * r; ll += l * l; rr += r * r
                re[i] = (l + r) * 0.5f * window[i]
            } else re[i] = 0f
            im[i] = 0f
        }
        fft.transform(re, im, inverse = false)

        val bands = FloatArray(BAND_CENTERS.size)
        val norm = 2.0 / (size * windowPower) // one-sided power → mean square
        for (b in bands.indices) {
            val (first, last) = bandRanges[b].let { it[0] to it[1] }
            if (first > last) {
                // Band narrower than one bin: use the nearest bin.
                val k = (BAND_CENTERS[b] / binHz).toInt().coerceIn(1, bins - 1)
                bands[b] = toDb(2.0 * (re[k] * re[k] + im[k] * im[k]) * norm)
                continue
            }
            var p = 0.0
            for (k in first..last) p += re[k] * re[k] + im[k] * im[k]
            bands[b] = toDb(2.0 * p * norm)
        }
        val corr = if (ll > 1e-12 && rr > 1e-12) (lr / sqrt(ll * rr)).toFloat().coerceIn(-1f, 1f) else 0f
        return Frame(bands, corr)
    }

    private fun toDb(meanSquareTimes2: Double): Float =
        if (meanSquareTimes2 <= 1e-14) FLOOR_DB else max(FLOOR_DB.toDouble(), 10.0 * log10(meanSquareTimes2)).toFloat()

    companion object {
        const val FLOOR_DB = -96f

        /** ISO 266 1/3-octave centre frequencies, 20 Hz – 20 kHz. */
        val BAND_CENTERS = doubleArrayOf(
            20.0, 25.0, 31.5, 40.0, 50.0, 63.0, 80.0, 100.0, 125.0, 160.0, 200.0, 250.0, 315.0, 400.0, 500.0,
            630.0, 800.0, 1000.0, 1250.0, 1600.0, 2000.0, 2500.0, 3150.0, 4000.0, 5000.0, 6300.0, 8000.0,
            10000.0, 12500.0, 16000.0, 20000.0
        )
    }
}
