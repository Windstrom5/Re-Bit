package com.windstrom5.rebit.playback.autoeq

import com.windstrom5.rebit.domain.model.EqFilter
import com.windstrom5.rebit.domain.model.EqFilterType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Filter math shared by the preset browser (previews, preamp, graphic fit). Uses the same RBJ
 * Audio-EQ-Cookbook biquads as the playback engine, so what you preview is what you hear.
 */
object EqMath {
    const val DEFAULT_FS = 48_000.0

    /** 20 Hz – 20 kHz, log spaced. */
    val GRID: DoubleArray = DoubleArray(160) { i -> 20.0 * (1000.0).pow(i / 159.0) }

    /** Normalised biquad [b0, b1, b2, a1, a2] for [f] at sample rate [fs]. */
    fun coefficients(f: EqFilter, fs: Double = DEFAULT_FS): DoubleArray {
        val fc = f.fcHz.toDouble().coerceIn(10.0, fs * 0.48)
        val q = f.q.toDouble().coerceIn(0.1, 20.0)
        val a = 10.0.pow(f.gainDb.toDouble() / 40.0)
        val w0 = 2.0 * PI * fc / fs
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / (2.0 * q)
        val b0: Double; val b1: Double; val b2: Double; val a0: Double; val a1: Double; val a2: Double
        when (f.type) {
            EqFilterType.PEAK -> {
                b0 = 1 + alpha * a; b1 = -2 * cosW; b2 = 1 - alpha * a
                a0 = 1 + alpha / a; a1 = -2 * cosW; a2 = 1 - alpha / a
            }
            EqFilterType.LOW_SHELF -> {
                val s = sqrt(a); val t = 2 * s * alpha
                b0 = a * ((a + 1) - (a - 1) * cosW + t)
                b1 = 2 * a * ((a - 1) - (a + 1) * cosW)
                b2 = a * ((a + 1) - (a - 1) * cosW - t)
                a0 = (a + 1) + (a - 1) * cosW + t
                a1 = -2 * ((a - 1) + (a + 1) * cosW)
                a2 = (a + 1) + (a - 1) * cosW - t
            }
            EqFilterType.HIGH_SHELF -> {
                val s = sqrt(a); val t = 2 * s * alpha
                b0 = a * ((a + 1) + (a - 1) * cosW + t)
                b1 = -2 * a * ((a - 1) + (a + 1) * cosW)
                b2 = a * ((a + 1) + (a - 1) * cosW - t)
                a0 = (a + 1) - (a - 1) * cosW + t
                a1 = 2 * ((a - 1) - (a + 1) * cosW)
                a2 = (a + 1) - (a - 1) * cosW - t
            }
        }
        return doubleArrayOf(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)
    }

    /** Magnitude (dB) of one filter at [freqHz]. */
    fun filterDb(f: EqFilter, freqHz: Double, fs: Double = DEFAULT_FS): Double {
        if (f.gainDb == 0f) return 0.0
        val c = coefficients(f, fs)
        val w = 2.0 * PI * freqHz / fs
        val cw = cos(w); val sw = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = c[0] + c[1] * cw + c[2] * c2
        val ni = -(c[1] * sw + c[2] * s2)
        val dr = 1 + c[3] * cw + c[4] * c2
        val di = -(c[3] * sw + c[4] * s2)
        val num = nr * nr + ni * ni
        val den = dr * dr + di * di
        return 10.0 * log10(num / den)
    }

    /** Combined response of a filter chain (dB add up for cascaded filters). */
    fun responseDb(filters: List<EqFilter>, freqHz: Double, fs: Double = DEFAULT_FS): Double =
        filters.sumOf { filterDb(it, freqHz, fs) }

    fun curve(filters: List<EqFilter>, grid: DoubleArray = GRID, fs: Double = DEFAULT_FS): DoubleArray =
        DoubleArray(grid.size) { responseDb(filters, grid[it], fs) }

    /**
     * Preamp that keeps the loudest boost from clipping: minus the response peak plus a small margin.
     * Zero when the chain never boosts.
     */
    fun safePreampDb(filters: List<EqFilter>, marginDb: Double = 0.3): Float {
        val peak = curve(filters).maxOrNull() ?: 0.0
        return if (peak > 0.0) (-(peak + marginDb)).toFloat() else 0f
    }

    // ───────────── Graphic (10-band) fit ─────────────

    private val BAND_FREQS = doubleArrayOf(31.25, 62.5, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    private const val BAND_Q = 1.4142f

    /** The playback engine's 10-band layout: low shelf, 8 peaks, high shelf, all Q 1.41. */
    fun graphicBandFilters(gains: List<Float>): List<EqFilter> = BAND_FREQS.indices.map { i ->
        val type = when (i) { 0 -> EqFilterType.LOW_SHELF; 9 -> EqFilterType.HIGH_SHELF; else -> EqFilterType.PEAK }
        EqFilter(type, BAND_FREQS[i].toFloat(), gains.getOrElse(i) { 0f }, BAND_Q)
    }

    private fun weight(f: Double): Double = when {
        f < 30.0 -> 0.25
        f > 14_000.0 -> 0.35
        f > 10_000.0 -> 0.7
        else -> 1.0
    }

    /**
     * Best 10 graphic-band gains approximating [target] (dB at [GRID] points). Used to keep the
     * sliders meaningful after a parametric preset is loaded. Gauss–Newton on the true biquad
     * responses, so overlapping bands and the end shelves are accounted for.
     */
    fun fitGraphic(target: DoubleArray, fs: Double = DEFAULT_FS): List<Float> {
        val n = 10
        val g = DoubleArray(n) { j ->
            // start: target sampled at the band centre
            val idx = GRID.indices.minByOrNull { abs(GRID[it] - BAND_FREQS[j]) } ?: 0
            target[idx].coerceIn(-12.0, 12.0)
        }
        val delta = 0.25
        val bands = graphicBandFilters(List(n) { 0f }) // built once, not per evaluation
        fun bandDb(j: Int, gain: Double, f: Double) = filterDb(bands[j].copy(gainDb = gain.toFloat()), f, fs)

        repeat(12) {
            val m = GRID.size
            val resid = DoubleArray(m) { k -> target[k] - (0 until n).sumOf { j -> bandDb(j, g[j], GRID[k]) } }
            val jac = Array(m) { k -> DoubleArray(n) { j -> (bandDb(j, g[j] + delta, GRID[k]) - bandDb(j, g[j], GRID[k])) / delta } }
            val ata = Array(n) { DoubleArray(n) }
            val atb = DoubleArray(n)
            for (k in 0 until m) {
                val w = weight(GRID[k])
                for (a in 0 until n) {
                    atb[a] += w * jac[k][a] * resid[k]
                    for (b in 0 until n) ata[a][b] += w * jac[k][a] * jac[k][b]
                }
            }
            for (a in 0 until n) ata[a][a] += 1e-3
            val step = solve(ata, atb) ?: return@repeat
            for (j in 0 until n) g[j] = (g[j] + step[j]).coerceIn(-12.0, 12.0)
        }
        return g.map { (Math.round(it * 10.0) / 10.0).toFloat() }
    }

    /** Gaussian elimination with partial pivoting; null if singular. */
    private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        val m = Array(n) { i -> DoubleArray(n + 1) { j -> if (j < n) a[i][j] else b[i] } }
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(m[r][col]) > abs(m[piv][col])) piv = r
            if (abs(m[piv][col]) < 1e-12) return null
            val tmp = m[col]; m[col] = m[piv]; m[piv] = tmp
            for (r in col + 1 until n) {
                val f = m[r][col] / m[col][col]
                for (c in col until n + 1) m[r][c] -= f * m[col][c]
            }
        }
        val x = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            var s = m[i][n]
            for (j in i + 1 until n) s -= m[i][j] * x[j]
            x[i] = s / m[i][i]
        }
        return x
    }

    fun rmsError(a: DoubleArray, b: DoubleArray, fromHz: Double = 30.0, toHz: Double = 12_000.0): Double {
        var s = 0.0; var c = 0
        for (k in GRID.indices) if (GRID[k] in fromHz..toHz) { val d = a[k] - b[k]; s += d * d; c++ }
        return sqrt(s / max(1, c))
    }
}
