package com.windstrom5.rebit.domain.audiophile

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A coarse spectrogram of a whole track: [columns] time slices x [rows] frequency bands from 0 Hz
 * (row 0) to Nyquist (last row). Levels are bytes: 0 = -120 dBFS ... 255 = 0 dBFS.
 */
class SpectrogramData(
    val columns: Int,
    val rows: Int,
    val sampleRateHz: Int,
    val levels: ByteArray
) {
    val nyquistHz: Int get() = sampleRateHz / 2

    fun level(col: Int, row: Int): Int = levels[col * rows + row].toInt() and 0xFF

    fun db(col: Int, row: Int): Float = level(col, row) / 255f * 120f - 120f
}

/** Radix-2 FFT, in place. Sizes must be powers of two. */
object Fft {
    fun transform(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        require(n > 0 && n and (n - 1) == 0 && im.size == n) { "FFT size must be a power of two" }

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2.0 * PI / len
            val wr = cos(ang)
            val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

/** Result of [SpectrumAccumulator.finish]. */
class SpectrumResult(val spectrogram: SpectrogramData, val bandwidthHz: Int)

/**
 * Takes [columns] evenly spaced FFT windows from a stream of mono samples, so a whole track can be
 * summarised in one decoding pass without keeping the audio. Feed every frame in order with [addFrame].
 *
 * Also tracks, per frequency bin, the second-loudest level seen across all windows: a single click
 * or crackle can't fake "content up to 20 kHz", but real music content shows up in many windows.
 */
class SpectrumAccumulator(
    private val sampleRateHz: Int,
    expectedFrames: Long,
    private val columns: Int = 160,
    private val fftSize: Int = 2048,
    private val rows: Int = 128
) {
    private val half = fftSize / 2
    private val window = DoubleArray(fftSize) { 0.5 - 0.5 * cos(2.0 * PI * it / (fftSize - 1)) }
    private val windowSum = window.sum()

    private val ring = FloatArray(fftSize)
    private var ringPos = 0
    private var frame = 0L

    /** Frame index at which each window ends (needs fftSize frames of history). */
    private val captureAt = LongArray(columns) { i ->
        max((fftSize - 1).toLong(), ((i + 0.5) / columns * expectedFrames).toLong())
    }
    private var nextColumn = 0

    private val levels = ByteArray(columns * rows)
    private val topDb = FloatArray(half) { -200f }
    private val secondDb = FloatArray(half) { -200f }

    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)

    fun addFrame(sample: Float) {
        ring[ringPos] = sample
        ringPos = (ringPos + 1) % fftSize
        while (nextColumn < columns && frame >= captureAt[nextColumn]) {
            capture(nextColumn)
            nextColumn++
        }
        frame++
    }

    private fun capture(col: Int) {
        for (i in 0 until fftSize) {
            re[i] = ring[(ringPos + i) % fftSize] * window[i]
            im[i] = 0.0
        }
        Fft.transform(re, im)

        val perRow = half / rows
        val binDb = FloatArray(half)
        for (k in 0 until half) {
            // 2|X| / sum(window) makes a full-scale sine read 0 dBFS.
            val mag = 2.0 * sqrt(re[k] * re[k] + im[k] * im[k]) / windowSum
            val d = (20.0 * log10(max(mag, 1e-9))).toFloat()
            binDb[k] = d
            if (d > topDb[k]) { secondDb[k] = topDb[k]; topDb[k] = d } else if (d > secondDb[k]) secondDb[k] = d
        }
        for (r in 0 until rows) {
            // Max over the bins in a row keeps narrow tones visible when bins are merged.
            var m = -200f
            for (b in r * perRow until (r + 1) * perRow) if (binDb[b] > m) m = binDb[b]
            levels[col * rows + r] = (((m + 120f) / 120f) * 255f).toInt().coerceIn(0, 255).toByte()
        }
    }

    /** Null if no window was captured (track shorter than one FFT window). */
    fun finish(): SpectrumResult? {
        if (nextColumn == 0) return null
        val data = SpectrogramData(nextColumn, rows, sampleRateHz, levels.copyOf(nextColumn * rows))
        return SpectrumResult(data, bandwidthHz())
    }

    /** Highest frequency whose second-loudest level reaches [BANDWIDTH_THRESHOLD_DB]. */
    private fun bandwidthHz(): Int {
        for (k in half - 1 downTo 1) {
            if (secondDb[k] > BANDWIDTH_THRESHOLD_DB) return (k.toLong() * sampleRateHz / fftSize).toInt()
        }
        return 0
    }

    companion object {
        /** Well above 16-bit quantisation noise (~-125 dB per bin), below the quietest real content. */
        const val BANDWIDTH_THRESHOLD_DB = -85f
    }
}
