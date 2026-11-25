package com.windstrom5.rebit.playback.dsp

import com.windstrom5.rebit.domain.model.ResamplerQuality
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Band-limited sample-rate converter: a polyphase windowed-sinc interpolator (Kaiser window),
 * the same family of design SoX, libsamplerate and r8brain use.
 *
 * Why not linear interpolation (what this replaced): linear interpolation has no anti-alias
 * filter, so downsampling 96 kHz → 48 kHz folds everything between 24 and 48 kHz straight back
 * into the audible band, and its own frequency response droops audibly at the top octave.
 * A windowed sinc is a proper low-pass at the *lower* of the two Nyquist frequencies:
 *
 *  - **Passband**: flat up to `rolloff × min(inRate, outRate) / 2`.
 *  - **Stopband**: Kaiser β sets the attenuation (β 6 ≈ 60 dB, 9 ≈ 90 dB, 12 ≈ 120 dB).
 *  - **Arbitrary ratios** (44.1 ↔ 48 kHz, 192 → 44.1 kHz): the kernel is tabulated at
 *    [PHASES] sub-sample phases and linearly interpolated between them, so any ratio works
 *    without a rational-factor search.
 *
 * Streaming: input arrives in chunks of interleaved frames. The class keeps the last
 * `halfTaps` frames of history plus any not-yet-consumed frames between calls, so chunk
 * boundaries are seamless (no clicks, no dropped frames, exact long-term rate).
 * Call [flush] once at end of stream to emit the tail still held in the filter.
 *
 * Pure Kotlin, no Android dependencies — unit-tested on the JVM.
 */
class SincResampler(
    private val channels: Int,
    val inRate: Int,
    val outRate: Int,
    quality: ResamplerQuality = ResamplerQuality.BALANCED
) {
    init {
        require(channels > 0) { "channels must be > 0" }
        require(inRate > 0 && outRate > 0) { "rates must be > 0" }
    }

    /** Input frames advanced per output frame. */
    private val step: Double = inRate.toDouble() / outRate.toDouble()

    /** < 1 when downsampling: the cutoff (and so the kernel's time scale) shrinks with it. */
    private val bandScale: Double = min(1.0, outRate.toDouble() / inRate.toDouble())
    private val cutoff: Double = bandScale * quality.rolloff

    /** Kernel half-width in *input* frames — widened when downsampling to keep the same
     *  number of zero crossings at the lower cutoff. */
    val halfTaps: Int = ceil(quality.zeroCrossings / bandScale).toInt().coerceAtLeast(2)

    /** h(x) for x = 0, 1/PHASES, 2/PHASES … halfTaps (one extra guard point). */
    private val table: FloatArray = buildTable(quality.kaiserBeta)

    /** Per-output-frame kernel weights, reused across calls to avoid allocation. */
    private val weights = FloatArray(halfTaps * 2)

    /** Interleaved input frames waiting to be (fully) consumed, starting with history. */
    private var buffer = FloatArray((halfTaps * 2 + 4096) * channels)
    private var bufferedFrames = halfTaps // starts with halfTaps frames of silence as history

    /** Position (in input frames, relative to buffer start) of the next output frame. */
    private var position: Double = halfTaps.toDouble()

    val isPassthrough: Boolean get() = inRate == outRate

    /** Resamples interleaved [input] (a whole number of frames) and returns interleaved output. */
    fun process(input: FloatArray): FloatArray = process(input, input.size)

    fun process(input: FloatArray, length: Int): FloatArray {
        if (isPassthrough) return if (length == input.size) input else input.copyOf(length)
        val frames = length / channels
        if (frames > 0) append(input, frames)
        return render(drain = false)
    }

    /** Emits everything still held inside the filter (pads the future with silence). */
    fun flush(): FloatArray {
        if (isPassthrough) return FloatArray(0)
        val padding = FloatArray((halfTaps + 1) * channels)
        append(padding, halfTaps + 1)
        return render(drain = true)
    }

    /** Clears all state, e.g. after a seek. */
    fun reset() {
        buffer.fill(0f)
        bufferedFrames = halfTaps
        position = halfTaps.toDouble()
    }

    private fun append(input: FloatArray, frames: Int) {
        val needed = (bufferedFrames + frames) * channels
        if (needed > buffer.size) {
            buffer = buffer.copyOf(maxOf(needed, buffer.size * 2))
        }
        System.arraycopy(input, 0, buffer, bufferedFrames * channels, frames * channels)
        bufferedFrames += frames
    }

    private fun render(drain: Boolean): FloatArray {
        // An output frame at `position` needs input frames up to floor(position) + halfTaps.
        val lastUsable = bufferedFrames - 1 - halfTaps
        val available = if (position > lastUsable) 0 else (floor((lastUsable - position) / step).toInt() + 1)
        val out = FloatArray(available * channels)

        val taps = halfTaps * 2
        val w = weights
        var pos = position
        var o = 0
        repeat(available) {
            val center = floor(pos).toInt()
            val frac = pos - center
            val first = center - halfTaps + 1
            // The kernel weights depend only on the fractional position, so they're computed
            // once per output frame and shared by every channel.
            for (j in 0 until taps) w[j] = kernel(abs(first + j - center - frac))
            for (ch in 0 until channels) {
                var acc = 0.0f
                var idx = first * channels + ch
                for (j in 0 until taps) {
                    acc += buffer[idx] * w[j]
                    idx += channels
                }
                out[o + ch] = acc
            }
            o += channels
            pos += step
        }
        position = pos

        // Drop frames no future output can reference, keeping halfTaps frames of history.
        val keepFrom = (floor(position).toInt() - halfTaps + 1).coerceAtLeast(0)
        if (keepFrom > 0) {
            val remaining = bufferedFrames - keepFrom
            System.arraycopy(buffer, keepFrom * channels, buffer, 0, remaining * channels)
            bufferedFrames = remaining
            position -= keepFrom
        }
        if (drain) reset()
        return out
    }

    /** Interpolated kernel lookup for distance [x] (input frames) from the output instant. */
    private fun kernel(x: Double): Float {
        if (x >= halfTaps) return 0f
        val idx = x * PHASES
        val i = idx.toInt()
        val f = (idx - i).toFloat()
        val a = table[i]
        return a + f * (table[i + 1] - a)
    }

    private fun buildTable(beta: Double): FloatArray {
        val n = halfTaps * PHASES
        val t = FloatArray(n + 2)
        val i0Beta = besselI0(beta)
        for (i in 0..n + 1) {
            val x = i.toDouble() / PHASES
            if (x >= halfTaps) { t[i] = 0f; continue }
            val sinc = if (x == 0.0) 1.0 else sin(PI * cutoff * x) / (PI * cutoff * x)
            val r = x / halfTaps
            val window = besselI0(beta * sqrt(1.0 - r * r)) / i0Beta
            t[i] = (cutoff * sinc * window).toFloat()
        }
        return t
    }

    companion object {
        /** Sub-sample kernel resolution. 512 phases keeps table-interpolation error well
         *  below the 120 dB stopband of the highest quality setting. */
        const val PHASES = 512

        /** Zeroth-order modified Bessel function of the first kind (series expansion). */
        internal fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val halfX = x / 2.0
            var k = 1
            while (k < 64) {
                term *= (halfX / k) * (halfX / k)
                sum += term
                if (term < sum * 1e-17) break
                k++
            }
            return sum
        }
    }
}
