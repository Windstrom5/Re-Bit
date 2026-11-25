package com.windstrom5.rebit.domain.abx

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlin.random.Random

/** A short excerpt of a library song, decoded to interleaved stereo floats for ABX playback. */
class AbxClip(
    val title: String,
    val subtitle: String?,
    val sampleRate: Int,
    /** Interleaved stereo, -1..1. */
    val samples: FloatArray,
    /** Where in the song the excerpt starts, for display. */
    val startMs: Long
) {
    val frames: Int get() = samples.size / 2
    val durationMs: Long get() = frames * 1000L / sampleRate
}

/** Converts the raw little-endian PCM the app's decoders produce into stereo floats. Pure. */
object AbxPcm {

    fun bytesPerSample(bitDepth: Int): Int = when {
        bitDepth <= 8 -> 1
        bitDepth <= 16 -> 2
        bitDepth <= 24 -> 3
        else -> 4
    }

    /**
     * @param length number of valid bytes in [input]. Mono is duplicated to both ears; with more than
     * two channels only the first two are used.
     */
    fun toStereoFloats(input: ByteArray, length: Int, bitDepth: Int, channels: Int): FloatArray {
        val ch = channels.coerceAtLeast(1)
        val bps = bytesPerSample(bitDepth)
        val frames = length / (bps * ch)
        val out = FloatArray(frames * 2)
        for (f in 0 until frames) {
            val base = f * ch * bps
            val l = sample(input, base, bps)
            val r = if (ch >= 2) sample(input, base + bps, bps) else l
            out[f * 2] = l
            out[f * 2 + 1] = r
        }
        return out
    }

    private fun sample(b: ByteArray, o: Int, bps: Int): Float = when (bps) {
        1 -> (((b[o].toInt() and 0xFF) - 128) / 128.0f)
        2 -> (((b[o + 1].toInt() shl 8) or (b[o].toInt() and 0xFF)).toShort()) / 32768.0f
        3 -> (((b[o + 2].toInt() shl 16) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)) / 8388608.0f)
        else -> (((b[o + 3].toInt() shl 24) or ((b[o + 2].toInt() and 0xFF) shl 16) or
            ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)) / 2147483648.0f)
    }
}

/**
 * 4th-order Butterworth low-pass (two biquads) for interleaved stereo. It keeps its filter state
 * between calls: restarting it on every audio chunk (as the old one-pole filter did) clicks at every
 * chunk boundary, and a click that is only in Sample B gives B away without any real difference.
 */
class StereoLowpass(cutoffHz: Float, sampleRate: Int) {

    private class Section(val b0: Float, val b1: Float, val b2: Float, val a1: Float, val a2: Float) {
        var z1L = 0f; var z2L = 0f; var z1R = 0f; var z2R = 0f
    }

    // Above ~0.49 of the sample rate there is nothing meaningful to filter.
    private val sections: List<Section> =
        if (cutoffHz >= sampleRate * 0.49f) emptyList()
        else listOf(0.5412f, 1.3066f).map { q -> section(cutoffHz, sampleRate, q) }

    private fun section(fc: Float, fs: Int, q: Float): Section {
        val w0 = 2.0 * PI * fc / fs
        val alpha = sin(w0) / (2.0 * q)
        val cosW = cos(w0)
        val a0 = 1.0 + alpha
        return Section(
            b0 = (((1.0 - cosW) / 2.0) / a0).toFloat(),
            b1 = ((1.0 - cosW) / a0).toFloat(),
            b2 = (((1.0 - cosW) / 2.0) / a0).toFloat(),
            a1 = ((-2.0 * cosW) / a0).toFloat(),
            a2 = ((1.0 - alpha) / a0).toFloat()
        )
    }

    fun process(interleaved: FloatArray) {
        for (s in sections) {
            var i = 0
            while (i + 1 < interleaved.size) {
                val xl = interleaved[i]
                val yl = s.b0 * xl + s.z1L
                s.z1L = s.b1 * xl - s.a1 * yl + s.z2L
                s.z2L = s.b2 * xl - s.a2 * yl
                interleaved[i] = yl

                val xr = interleaved[i + 1]
                val yr = s.b0 * xr + s.z1R
                s.z1R = s.b1 * xr - s.a1 * yr + s.z2R
                s.z2R = s.b2 * xr - s.a2 * yr
                interleaved[i + 1] = yr
                i += 2
            }
        }
    }
}

/** What turns the pristine Sample A into Sample B for a test mode. Stateful: use one per playback run. */
class AbxSampleBProcessor(mode: AbxTestMode, sampleRate: Int) {

    private val lowpass: StereoLowpass?
    private val quantBits: Int
    private val dither16: Boolean

    init {
        var cutoff = 0f
        var bits = 0
        var dither = false
        when (mode) {
            AbxTestMode.FLAC_VS_MP3_320 -> { cutoff = 19_500f; bits = 14 }
            AbxTestMode.FLAC_VS_MP3_128 -> { cutoff = 15_800f; bits = 10 }
            AbxTestMode.HIRES_24BIT_VS_16BIT -> dither = true
            AbxTestMode.AI_DSEE_VS_LOSSY -> cutoff = 14_000f
            AbxTestMode.TREBLE_CUT_20K_VS_15K -> cutoff = 15_000f
            AbxTestMode.CUSTOM_LIBRARY_TRACK -> cutoff = 16_000f
        }
        lowpass = if (cutoff > 0f) StereoLowpass(cutoff, sampleRate) else null
        quantBits = bits
        dither16 = dither
    }

    fun process(floats: FloatArray) {
        lowpass?.process(floats)
        if (quantBits > 0) {
            val steps = (1 shl (quantBits - 1)).toFloat()
            for (i in floats.indices) floats[i] = (round(floats[i] * steps) / steps).coerceIn(-1f, 1f)
        }
        if (dither16) {
            val steps = 32768.0f
            val scale = 1.0f / steps
            for (i in floats.indices) {
                val d = (Random.nextFloat() - 0.5f) * scale + (Random.nextFloat() - 0.5f) * scale
                floats[i] = (round((floats[i] + d) * steps) / steps).coerceIn(-1f, 1f)
            }
        }
    }
}
