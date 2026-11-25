package com.windstrom5.rebit.playback.dsp

import android.content.Context
import android.net.Uri
import android.util.Log
import com.windstrom5.rebit.domain.model.AudioFileFormat
import com.windstrom5.rebit.domain.model.ResamplerQuality
import com.windstrom5.rebit.playback.AudioDecoders
import kotlin.math.PI
import kotlin.math.cos

/**
 * Loads an impulse-response file (WAV, FLAC or AIFF; mono or stereo — further channels are
 * ignored) and prepares it for [ConvolutionEngine] at a given output rate: decoded to float,
 * resampled with the mastering-quality sinc if its rate differs, and capped at
 * [ConvolutionEngine.MAX_TAPS] with a short fade so truncation doesn't click.
 *
 * Results are cached per (file, rate), so changing tracks doesn't reload the IR.
 */
object ImpulseResponseLoader {

    data class Ir(val left: FloatArray, val right: FloatArray, val sourceRateHz: Int, val channels: Int) {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    private val cache = object : LinkedHashMap<String, Ir>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Ir>?) = size > 3
    }

    fun load(context: Context, uri: String, outputRateHz: Int): Ir? {
        val key = "$uri@$outputRateHz"
        synchronized(cache) { cache[key]?.let { return it } }
        val ir = try { decode(context, uri, outputRateHz) } catch (e: Exception) {
            Log.w("ImpulseResponseLoader", "Couldn't load impulse response: ${e.message}")
            null
        } ?: return null
        synchronized(cache) { cache[key] = ir }
        return ir
    }

    private fun sniffFormat(context: Context, uri: String): AudioFileFormat? {
        val head = ByteArray(4)
        context.contentResolver.openInputStream(Uri.parse(uri))?.use { it.read(head) } ?: return null
        return when (String(head, Charsets.US_ASCII)) {
            "RIFF" -> AudioFileFormat.WAV
            "fLaC" -> AudioFileFormat.FLAC
            "FORM" -> AudioFileFormat.AIFF
            else -> null
        }
    }

    private fun decode(context: Context, uri: String, outputRateHz: Int): Ir? {
        val format = sniffFormat(context, uri) ?: return null
        val decoder = AudioDecoders.create(format, context)
        try {
            val pcm = decoder.open(uri)
            val ch = pcm.channels
            val maxSourceFrames = (ConvolutionEngine.MAX_TAPS.toLong() * pcm.sampleRateHz / outputRateHz + 64).toInt()
            val left = FloatArray(maxSourceFrames)
            val right = FloatArray(maxSourceFrames)
            var frames = 0
            val buf = ByteArray(64 * 1024 - (64 * 1024) % ((pcm.bitDepth / 8) * ch))
            while (frames < maxSourceFrames) {
                val n = decoder.read(buf)
                if (n < 0) break
                if (n == 0) continue
                val f = PcmCodec.toFloat(buf, n, pcm.bitDepth)
                val got = f.size / ch
                for (i in 0 until got) {
                    if (frames >= maxSourceFrames) break
                    left[frames] = f[i * ch]
                    right[frames] = if (ch > 1) f[i * ch + 1] else f[i * ch]
                    frames++
                }
            }
            if (frames == 0) return null
            var l = left.copyOf(frames)
            var r = right.copyOf(frames)
            if (pcm.sampleRateHz != outputRateHz) {
                val inter = FloatArray(frames * 2) { if (it % 2 == 0) l[it / 2] else r[it / 2] }
                val rs = SincResampler(2, pcm.sampleRateHz, outputRateHz, ResamplerQuality.MASTERING)
                val out = rs.process(inter) + rs.flush()
                l = FloatArray(out.size / 2) { out[2 * it] }
                r = FloatArray(out.size / 2) { out[2 * it + 1] }
            }
            if (l.size > ConvolutionEngine.MAX_TAPS) {
                l = fadeOut(l.copyOf(ConvolutionEngine.MAX_TAPS))
                r = fadeOut(r.copyOf(ConvolutionEngine.MAX_TAPS))
            }
            return Ir(l, r, pcm.sampleRateHz, ch)
        } finally {
            runCatching { decoder.close() }
        }
    }

    private fun fadeOut(x: FloatArray): FloatArray {
        val len = (x.size / 20).coerceAtLeast(16)
        for (i in 0 until len) {
            val g = 0.5f + 0.5f * cos(PI * i / len).toFloat()
            x[x.size - len + i] *= g
        }
        return x
    }
}
