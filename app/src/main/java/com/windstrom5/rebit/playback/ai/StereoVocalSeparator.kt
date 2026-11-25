package com.windstrom5.rebit.playback.ai

import com.windstrom5.rebit.domain.model.KaraokeModeType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Real-time karaoke separation for stereo music: a streaming STFT that removes (or isolates)
 * the *centre-panned, harmonic* part of the mix — which in almost all commercial music is the
 * lead vocal — while leaving everything else alone.
 *
 * What changed from the previous engine, and why it sounds better:
 *
 *  1. **Mid/side, not whole-bin gating.** Each frequency bin is split into Mid = (L+R)/2 and
 *     Side = (L−R)/2. Only the Mid part is turned down; Side passes untouched. The old engine
 *     multiplied the *whole* bin in both channels, so a stereo guitar or pad sharing a bin with
 *     the voice got cut too, which is what made karaoke sound hollow. Now hard-panned and wide
 *     instruments, and the stereo image itself, are preserved exactly.
 *  2. **Drums stay.** A real-time harmonic/percussive split (median filtering — horizontal
 *     across the last frames for harmonic, vertical across frequency for percussive) decides
 *     how much of the centre is sustained/pitched (voice) versus transient (kick, snare,
 *     hi-hat). Only the harmonic share is removed, so centre-panned drums keep their punch.
 *  3. **Reliable "is this centred?"** Panning is measured from cross-spectra averaged over
 *     time (inter-channel coherence), not from a single frame, so reverb tails and dense mixes
 *     don't flicker in and out.
 *  4. **Resolution that scales with the sample rate** — 2048-point FFT up to 48 kHz, 4096 at
 *     88.2/96 kHz, 8192 above — so vocal harmonics stay resolvable at hi-res rates.
 *  5. **Bass and air protected** by a smooth frequency weighting instead of hard bin cut-offs.
 *  6. **Exact reconstruction:** with strength 0 the output is the input, delayed by
 *     [latencyFrames]. One complex FFT carries both channels (L + iR), halving the cost.
 *
 * Still DSP, not a neural network: it removes whatever is centred *and* harmonic, so a
 * centre-panned sustained lead instrument is treated like a voice, and the vocal's stereo
 * reverb (which lives in Side) remains as a faint "ghost". Real Demucs stems via
 * [ExternalStemResolver] remain the reference-quality option.
 *
 * Pure Kotlin, single-threaded (call from the audio thread only), unit-tested on the JVM.
 */
class StereoVocalSeparator(val sampleRateHz: Int) {

    val fftSize: Int = when {
        sampleRateHz <= 50_000 -> 2048
        sampleRateHz <= 100_000 -> 4096
        else -> 8192
    }
    private val hop = fftSize / 4
    private val bins = fftSize / 2 + 1

    /** Output lags input by exactly this many frames (one FFT length). */
    val latencyFrames: Int get() = fftSize

    // sqrt-Hann analysis and synthesis: their product is a periodic Hann, which overlap-adds
    // to exactly 2.0 at 75 % overlap — hence OLA_GAIN.
    private val window = FloatArray(fftSize) { n -> sqrt(0.5 - 0.5 * cos(2.0 * PI * n / fftSize)).toFloat() }

    // ── Streaming buffers ──────────────────────────────────────────────────────────────────
    private val frameL = FloatArray(fftSize)
    private val frameR = FloatArray(fftSize)
    private val accL = FloatArray(fftSize)
    private val accR = FloatArray(fftSize)
    private val readyL = FloatArray(hop)
    private val readyR = FloatArray(hop)
    private var fill = 0

    // ── FFT scratch ────────────────────────────────────────────────────────────────────────
    private val re = FloatArray(fftSize)
    private val im = FloatArray(fftSize)
    private val fft = Fft(fftSize)

    // Per-bin spectra for the current frame.
    private val lRe = FloatArray(bins); private val lIm = FloatArray(bins)
    private val rRe = FloatArray(bins); private val rIm = FloatArray(bins)
    private val midMag = FloatArray(bins)

    // ── Analysis state ─────────────────────────────────────────────────────────────────────
    private val pLL = FloatArray(bins)
    private val pRR = FloatArray(bins)
    private val cLR = FloatArray(bins)
    private val history = Array(HARMONIC_FRAMES) { FloatArray(bins) }
    private var historyPos = 0
    private val vocalMask = FloatArray(bins)
    private val sortScratch = FloatArray(maxOf(HARMONIC_FRAMES, PERCUSSIVE_SPAN * 2 + 1))
    private val binHz = sampleRateHz.toFloat() / fftSize

    /** Fraction of the centre the last frame judged to be vocal (0..1), for a UI meter. */
    @Volatile var lastVocalActivity: Float = 0f
        private set

    /**
     * Processes interleaved stereo [samples] in place (output delayed by [latencyFrames]).
     * @param strength 0 = untouched … 1 = full removal (instrumental) / full isolation (acapella)
     * @param preserveBass keep everything below ~150 Hz (kick and bass fundamentals) intact
     */
    fun process(
        samples: FloatArray,
        mode: KaraokeModeType,
        strength: Float,
        preserveBass: Boolean,
        length: Int = samples.size
    ) {
        val s = strength.coerceIn(0f, 1f)
        var i = 0
        while (i + 1 < length) {
            val base = fftSize - hop + fill
            frameL[base] = samples[i]
            frameR[base] = samples[i + 1]
            samples[i] = readyL[fill]
            samples[i + 1] = readyR[fill]
            fill++
            if (fill == hop) {
                processFrame(mode, s, preserveBass)
                fill = 0
            }
            i += 2
        }
    }

    /** Clears all history — call after a seek, or when karaoke is switched back on. */
    fun reset() {
        frameL.fill(0f); frameR.fill(0f)
        accL.fill(0f); accR.fill(0f)
        readyL.fill(0f); readyR.fill(0f)
        fill = 0
        pLL.fill(0f); pRR.fill(0f); cLR.fill(0f)
        history.forEach { it.fill(0f) }
        historyPos = 0
        vocalMask.fill(0f)
        lastVocalActivity = 0f
    }

    private fun processFrame(mode: KaraokeModeType, strength: Float, preserveBass: Boolean) {
        // 1. Window both channels into one complex signal z = l + i·r and transform once.
        for (n in 0 until fftSize) {
            val w = window[n]
            re[n] = frameL[n] * w
            im[n] = frameR[n] * w
        }
        fft.transform(re, im, inverse = false)

        // 2. Unpack the two real spectra:  L[k] = (Z[k] + Z*[N−k]) / 2,  R[k] = (Z[k] − Z*[N−k]) / 2i
        for (k in 0 until bins) {
            val nk = if (k == 0) 0 else fftSize - k
            val zr = re[k]; val zi = im[k]
            val cr = re[nk]; val ci = -im[nk]
            lRe[k] = (zr + cr) * 0.5f
            lIm[k] = (zi + ci) * 0.5f
            val dr = zr - cr
            val di = zi - ci
            rRe[k] = di * 0.5f
            rIm[k] = -dr * 0.5f
        }

        // 3. Analyse and build the vocal mask.
        if (strength > 0f) analyse(preserveBass) else vocalMask.fill(0f)

        // 4. Apply: only Mid is scaled; Side passes through untouched.
        val isolate = mode == KaraokeModeType.VOCAL_ISOLATION
        for (k in 0 until bins) {
            val mRe = (lRe[k] + rRe[k]) * 0.5f
            val mIm = (lIm[k] + rIm[k]) * 0.5f
            val sRe = (lRe[k] - rRe[k]) * 0.5f
            val sIm = (lIm[k] - rIm[k]) * 0.5f
            val v = vocalMask[k]
            val midGain: Float
            val sideGain: Float
            if (mode == KaraokeModeType.OFF || strength <= 0f) {
                midGain = 1f; sideGain = 1f
            } else if (isolate) {
                // Keep the vocal share of Mid; fade everything else out as strength rises.
                midGain = v + (1f - strength) * (1f - v)
                sideGain = 1f - strength
            } else {
                midGain = 1f - strength * v
                sideGain = 1f
            }
            val oMRe = mRe * midGain; val oMIm = mIm * midGain
            val oSRe = sRe * sideGain; val oSIm = sIm * sideGain
            // L' = M' + S', R' = M' − S'
            lRe[k] = oMRe + oSRe; lIm[k] = oMIm + oSIm
            rRe[k] = oMRe - oSRe; rIm[k] = oMIm - oSIm
        }

        // 5. Repack Y = L' + i·R' (both Hermitian) and inverse-transform once: the real part is
        //    the new left channel, the imaginary part the new right.
        for (k in 0 until bins) {
            re[k] = lRe[k] - rIm[k]
            im[k] = lIm[k] + rRe[k]
        }
        for (k in bins until fftSize) {
            val m = fftSize - k
            // conj(L'[m]) + i·conj(R'[m])
            re[k] = lRe[m] + rIm[m]
            im[k] = -lIm[m] + rRe[m]
        }
        fft.transform(re, im, inverse = true)

        // 6. Overlap-add with the synthesis window; hand out the finished first hop.
        val scale = OLA_GAIN / fftSize
        for (n in 0 until fftSize) {
            val w = window[n] * scale
            accL[n] += re[n] * w
            accR[n] += im[n] * w
        }
        System.arraycopy(accL, 0, readyL, 0, hop)
        System.arraycopy(accR, 0, readyR, 0, hop)
        System.arraycopy(accL, hop, accL, 0, fftSize - hop)
        System.arraycopy(accR, hop, accR, 0, fftSize - hop)
        accL.fill(0f, fftSize - hop, fftSize)
        accR.fill(0f, fftSize - hop, fftSize)

        // 7. Slide the analysis frame forward by one hop.
        System.arraycopy(frameL, hop, frameL, 0, fftSize - hop)
        System.arraycopy(frameR, hop, frameR, 0, fftSize - hop)
    }

    /** Fills [vocalMask] with the per-bin share of Mid judged to be centred, harmonic voice. */
    private fun analyse(preserveBass: Boolean) {
        val hist = history[historyPos]
        for (k in 0 until bins) {
            val lr = lRe[k]; val li = lIm[k]
            val rr = rRe[k]; val ri = rIm[k]
            // Recursive averages of the auto/cross spectra → inter-channel coherence.
            pLL[k] = COH_SMOOTH * pLL[k] + (1f - COH_SMOOTH) * (lr * lr + li * li)
            pRR[k] = COH_SMOOTH * pRR[k] + (1f - COH_SMOOTH) * (rr * rr + ri * ri)
            cLR[k] = COH_SMOOTH * cLR[k] + (1f - COH_SMOOTH) * (lr * rr + li * ri)
            val mr = (lr + rr) * 0.5f
            val mi = (li + ri) * 0.5f
            val mag = sqrt(mr * mr + mi * mi)
            midMag[k] = mag
            hist[k] = mag
        }
        historyPos = (historyPos + 1) % HARMONIC_FRAMES

        val bassFloorHz = if (preserveBass) 120f else 60f
        val bassFullHz = if (preserveBass) 220f else 120f
        var activity = 0f
        var weightSum = 0f
        for (k in 0 until bins) {
            val f = k * binHz
            val band = bandWeight(f, bassFloorHz, bassFullHz)
            if (band <= 0f) {
                vocalMask[k] = 0f
                continue
            }
            // Centred-ness: 1 for identical, in-phase channels; 0 for hard-panned, antiphase or
            // uncorrelated (reverb, wide pads). Sharpened so slightly-off-centre parts survive.
            val coherence = (2f * cLR[k] / (pLL[k] + pRR[k] + EPS)).coerceIn(0f, 1f)
            val centre = smoothstep(0.55f, 0.95f, coherence)

            // Harmonic vs percussive share of the Mid magnitude.
            val harmonic = medianOverTime(k)
            val percussive = medianOverFrequency(k)
            val h2 = harmonic * harmonic
            val harmonicShare = h2 / (h2 + percussive * percussive + EPS)

            val target = centre * smoothstep(0.25f, 0.75f, harmonicShare) * band
            // Fast to engage, slower to let go: avoids "musical noise" flicker on the mask.
            val prev = vocalMask[k]
            val v = if (target > prev) prev + (target - prev) * 0.6f else prev + (target - prev) * 0.25f
            vocalMask[k] = v
            val w = midMag[k]
            activity += v * w
            weightSum += w
        }
        lastVocalActivity = if (weightSum > EPS) (activity / weightSum).coerceIn(0f, 1f) else 0f
    }

    /** Vocal-band weighting: off in the deep bass, full from ~200 Hz to 8 kHz, easing off above. */
    private fun bandWeight(f: Float, bassFloorHz: Float, bassFullHz: Float): Float = when {
        f < bassFloorHz -> 0f
        f < bassFullHz -> smoothstep(bassFloorHz, bassFullHz, f)
        f <= 8_000f -> 1f
        f <= 14_000f -> 1f - 0.6f * smoothstep(8_000f, 14_000f, f)
        else -> 0.4f
    }

    private fun medianOverTime(k: Int): Float {
        for (t in 0 until HARMONIC_FRAMES) sortScratch[t] = history[t][k]
        return median(sortScratch, HARMONIC_FRAMES)
    }

    private fun medianOverFrequency(k: Int): Float {
        var n = 0
        for (j in k - PERCUSSIVE_SPAN..k + PERCUSSIVE_SPAN) {
            if (j in 0 until bins) sortScratch[n++] = midMag[j]
        }
        return median(sortScratch, n)
    }

    companion object {
        /** Frames of history for the harmonic (horizontal) median: ~100 ms at 48 kHz. */
        const val HARMONIC_FRAMES = 9
        /** Bins either side for the percussive (vertical) median. */
        const val PERCUSSIVE_SPAN = 8
        private const val COH_SMOOTH = 0.75f
        private const val OLA_GAIN = 0.5f
        private const val EPS = 1e-12f

        internal fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /** Median of the first [n] values of [a] (partially reorders [a]); insertion sort, n ≤ 17. */
        internal fun median(a: FloatArray, n: Int): Float {
            if (n <= 0) return 0f
            for (i in 1 until n) {
                val v = a[i]
                var j = i - 1
                while (j >= 0 && a[j] > v) { a[j + 1] = a[j]; j-- }
                a[j + 1] = v
            }
            return if (n % 2 == 1) a[n / 2] else 0.5f * (a[n / 2 - 1] + a[n / 2])
        }
    }
}

/** Radix-2 complex FFT with precomputed twiddles and bit-reversal table. */
internal class Fft(private val n: Int) {
    init { require(n > 1 && n and (n - 1) == 0) { "FFT size must be a power of two" } }

    private val cosT = FloatArray(n / 2) { cos(2.0 * PI * it / n).toFloat() }
    private val sinT = FloatArray(n / 2) { sin(2.0 * PI * it / n).toFloat() }
    private val rev = IntArray(n).also { r ->
        val bits = Integer.numberOfTrailingZeros(n)
        for (i in 0 until n) r[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** In place. The inverse is unscaled (caller divides by n). */
    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until n) {
            val j = rev[i]
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        val sign = if (inverse) 1f else -1f
        var size = 2
        while (size <= n) {
            val half = size / 2
            val step = n / size
            var start = 0
            while (start < n) {
                var t = 0
                for (k in 0 until half) {
                    val wr = cosT[t]
                    val wi = sign * sinT[t]
                    val a = start + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                    t += step
                }
                start += size
            }
            size *= 2
        }
    }
}
