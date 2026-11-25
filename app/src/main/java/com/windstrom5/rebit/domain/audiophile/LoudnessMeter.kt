package com.windstrom5.rebit.domain.audiophile

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * EBU R128 / ITU-R BS.1770-4 loudness measurement, streaming, for any sample rate:
 *
 *  - **Integrated loudness (LUFS)** — K-weighted mean-square over 400 ms blocks with 75 %
 *    overlap, gated at −70 LUFS absolute and −10 LU relative.
 *  - **Loudness range (LU)** — EBU Tech 3342: distribution of 3 s short-term loudness, gated at
 *    −70 LUFS absolute and −20 LU relative; LRA = 95th − 10th percentile.
 *  - **True peak (dBTP)** — BS.1770 Annex 2: the signal oversampled (×4 below 96 kHz, ×2 below
 *    192 kHz) so peaks *between* samples — the ones that clip a DAC's reconstruction filter and
 *    lossy encoders — are caught, not just sample peaks.
 *
 * Feed de-interleaved frames with [addFrame]; read results from [finish].
 */
class LoudnessMeter(private val sampleRateHz: Int, private val channels: Int) {

    // ── K-weighting: pre-filter (high shelf) + RLB high-pass, per channel ─────────────────
    private val shelfB: DoubleArray
    private val shelfA: DoubleArray
    private val hpB = doubleArrayOf(1.0, -2.0, 1.0)
    private val hpA: DoubleArray

    init {
        // libebur128's closed-form coefficients, valid at any sample rate.
        var f0 = 1681.974450955533
        val g = 3.999843853973347
        var q = 0.7071752369554196
        var k = tan(PI * f0 / sampleRateHz)
        val vh = 10.0.pow(g / 20.0)
        val vb = vh.pow(0.4996667741545416)
        var a0 = 1.0 + k / q + k * k
        shelfB = doubleArrayOf((vh + vb * k / q + k * k) / a0, 2.0 * (k * k - vh) / a0, (vh - vb * k / q + k * k) / a0)
        shelfA = doubleArrayOf(1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
        f0 = 38.13547087602444
        q = 0.5003270373238773
        k = tan(PI * f0 / sampleRateHz)
        a0 = 1.0 + k / q + k * k
        hpA = doubleArrayOf(1.0, 2.0 * (k * k - 1.0) / a0, (1.0 - k / q + k * k) / a0)
    }

    private val z1 = Array(channels) { DoubleArray(4) } // shelf x1 x2 y1 y2
    private val z2 = Array(channels) { DoubleArray(4) } // high-pass x1 x2 y1 y2
    private val weights = DoubleArray(channels) { ch -> if (channels >= 5 && (ch == 4 || ch == 5)) 1.41 else if (channels >= 6 && ch == 3) 0.0 else 1.0 }

    // ── 100 ms sub-blocks of weighted energy ────────────────────────────────────────────────
    private val subBlockFrames = max(1, sampleRateHz / 10)
    private var subFill = 0
    private var subSum = 0.0
    private val recent = ArrayDeque<Double>() // last 30 sub-blocks (3 s)
    private val momentary = ArrayList<Double>() // 400 ms block mean energies
    private val shortTerm = ArrayList<Double>() // 3 s block mean energies

    // ── true peak ────────────────────────────────────────────────────────────────────────
    private val oversample = when {
        sampleRateHz < 96_000 -> 4
        sampleRateHz < 192_000 -> 2
        else -> 1
    }
    private val tpTaps = 12 // per phase
    private val tpPhases: Array<DoubleArray> = buildInterpolator(oversample, tpTaps)
    private val tpHistory = Array(channels) { DoubleArray(tpTaps) }
    private var tpPos = 0
    private var truePeak = 0.0
    private var samplePeak = 0.0

    fun addFrame(frame: DoubleArray) {
        var e = 0.0
        for (ch in 0 until channels) {
            val x = frame[ch]
            val ax = abs(x)
            if (ax > samplePeak) samplePeak = ax
            // true-peak interpolation on the raw signal
            val h = tpHistory[ch]
            h[tpPos] = x
            if (oversample == 1) {
                if (ax > truePeak) truePeak = ax
            } else {
                for (p in tpPhases) {
                    var acc = 0.0
                    var idx = tpPos
                    for (t in 0 until tpTaps) {
                        acc += p[t] * h[idx]
                        idx = if (idx == 0) tpTaps - 1 else idx - 1
                    }
                    val a = abs(acc)
                    if (a > truePeak) truePeak = a
                }
            }
            // K-weighting
            val s = z1[ch]
            val y1 = shelfB[0] * x + shelfB[1] * s[0] + shelfB[2] * s[1] - shelfA[1] * s[2] - shelfA[2] * s[3]
            s[1] = s[0]; s[0] = x; s[3] = s[2]; s[2] = y1
            val r = z2[ch]
            val y2 = hpB[0] * y1 + hpB[1] * r[0] + hpB[2] * r[1] - hpA[1] * r[2] - hpA[2] * r[3]
            r[1] = r[0]; r[0] = y1; r[3] = r[2]; r[2] = y2
            e += weights[ch] * y2 * y2
        }
        tpPos = (tpPos + 1) % tpTaps
        subSum += e
        if (++subFill == subBlockFrames) closeSubBlock()
    }

    private fun closeSubBlock() {
        recent.addLast(subSum / subFill)
        if (recent.size > 30) recent.removeFirst()
        subSum = 0.0
        subFill = 0
        if (recent.size >= 4) momentary += recent.takeLast(4).average()
        if (recent.size >= 30) shortTerm += recent.average()
    }

    data class Result(
        val integratedLufs: Double?,
        val loudnessRangeLu: Double?,
        val truePeakDbtp: Double,
        val samplePeakDbfs: Double
    )

    fun finish(): Result {
        val integrated = gatedLoudness(momentary, relativeGateLu = 10.0)
        val lra = loudnessRange(shortTerm)
        return Result(integrated, lra, toDb(truePeak), toDb(samplePeak))
    }

    companion object {
        private const val ABS_GATE_LUFS = -70.0

        fun energyToLufs(e: Double): Double = -0.691 + 10.0 * log10(max(e, 1e-20))
        private fun lufsToEnergy(l: Double): Double = 10.0.pow((l + 0.691) / 10.0)
        private fun toDb(x: Double): Double = 20.0 * log10(max(x, 1e-10))

        internal fun gatedLoudness(blocks: List<Double>, relativeGateLu: Double): Double? {
            val absGate = lufsToEnergy(ABS_GATE_LUFS)
            val aboveAbs = blocks.filter { it > absGate }
            if (aboveAbs.isEmpty()) return null
            val relGate = aboveAbs.average() * 10.0.pow(-relativeGateLu / 10.0)
            val gated = aboveAbs.filter { it > relGate }
            if (gated.isEmpty()) return null
            return energyToLufs(gated.average())
        }

        internal fun loudnessRange(shortTerm: List<Double>): Double? {
            val absGate = lufsToEnergy(ABS_GATE_LUFS)
            val aboveAbs = shortTerm.filter { it > absGate }
            if (aboveAbs.size < 2) return null
            val relGate = aboveAbs.average() * 10.0.pow(-20.0 / 10.0)
            val values = aboveAbs.filter { it > relGate }.map { energyToLufs(it) }.sorted()
            if (values.size < 2) return null
            fun pct(p: Double) = values[((values.size - 1) * p).toInt().coerceIn(0, values.size - 1)]
            return pct(0.95) - pct(0.10)
        }

        /** Polyphase interpolation filter for true-peak oversampling (Kaiser-windowed sinc). */
        private fun buildInterpolator(factor: Int, tapsPerPhase: Int): Array<DoubleArray> {
            if (factor == 1) return emptyArray()
            val n = factor * tapsPerPhase
            val mid = (n - 1) / 2.0
            val h = DoubleArray(n) { i ->
                val x = (i - mid) / factor
                val sinc = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)
                val r = (i - mid) / mid
                val w = besselI0(7.0 * sqrt(max(0.0, 1 - r * r))) / besselI0(7.0)
                sinc * w
            }
            // Each phase is every factor-th tap; normalise each to unity DC gain.
            return Array(factor) { p ->
                val taps = DoubleArray(tapsPerPhase) { t -> h[p + t * factor] }
                val s = taps.sum()
                for (t in taps.indices) taps[t] /= s
                taps
            }
        }

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            for (k in 1 until 40) {
                term *= (x / (2 * k)) * (x / (2 * k))
                sum += term
                if (term < 1e-15 * sum) break
            }
            return sum
        }
    }
}
