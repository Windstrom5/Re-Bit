package com.windstrom5.rebit.playback

import com.windstrom5.rebit.playback.AudioTrackPlaybackEngine.BitDepthMode

/**
 * Simple headphone crossfeed — blends a low-pass-filtered portion of each
 * channel into the other, mimicking (in simplified form) how listening to
 * real stereo speakers delivers some of each channel to both ears, which
 * headphones don't do at all. This is the "DSP Pipeline > Crossfeed" toggle;
 * AudioTrack engine only, same as EQ — bit-perfect USB-direct playback never
 * applies any DSP (see [UsbDirectPlaybackEngine], which never references this
 * class at all).
 *
 * Honest simplification: real reference crossfeed designs (e.g. the
 * Bauer stereophonic-to-binaural / bs2b algorithm, or the classic passive
 * Chu Moy circuit this is functionally descended from) also add a short
 * inter-aural delay (roughly 0.3ms) to the crossfed signal, modeling the time
 * difference between a sound reaching the near vs. far ear. This omits that
 * delay line — it's a real lowpass-filtered blend, genuinely audible and
 * genuinely doing what crossfeed does (softening the hard hard-left/hard-right
 * separation headphones otherwise present), just a simpler implementation
 * than the delay-line versions use.
 *
 * State (the lowpass filter memory) is per playback session — a new instance
 * is created per track, same lifecycle as TransferVerifier/UsbTrafficLog, so
 * filter memory never leaks stale energy from a previous track into the next.
 */
class CrossfeedProcessor(private val strength: Float, sampleRateHz: Int) {

    // Single-pole lowpass, ~700Hz cutoff — in the range typical crossfeed
    // designs use for the "shadowed" cross-signal (real head shadowing
    // increasingly attenuates the signal reaching the far ear above roughly
    // this frequency).
    private val alpha = run {
        val cutoffHz = 700.0
        val rc = 1.0 / (2.0 * Math.PI * cutoffHz)
        val dt = 1.0 / sampleRateHz
        (dt / (rc + dt)).toFloat()
    }

    private var lpfStateL = 0f
    private var lpfStateR = 0f

    /** Processes one interleaved stereo sample pair (already normalized to
     *  [-1, 1] float, regardless of the wire bit depth — the caller converts). */
    fun process(left: Float, right: Float): Pair<Float, Float> {
        lpfStateL += alpha * (left - lpfStateL)
        lpfStateR += alpha * (right - lpfStateR)
        // Gain-compensated so the added crossfeed energy doesn't push peaks
        // past unity — a straight sum would risk clipping on already-hot masters.
        val norm = 1f / (1f + strength)
        val outLeft = (left + strength * lpfStateR) * norm
        val outRight = (right + strength * lpfStateL) * norm
        return outLeft to outRight
    }

    // Inter-aural time difference for the float path: the crossfed (far-ear) signal arrives
    // ~0.3 ms after the direct one, which is what makes crossfeed sound like speakers in front
    // of you rather than a narrower mix. Ring buffers hold the low-passed cross signals.
    private val itdFrames = (sampleRateHz * 0.0003).toInt().coerceAtLeast(1)
    private val delayL = FloatArray(itdFrames)
    private val delayR = FloatArray(itdFrames)
    private var delayPos = 0

    /**
     * Float-domain crossfeed with the inter-aural delay (bs2b-style), in place on interleaved
     * stereo samples. This is the path the DSP chain uses; [processBuffer] is the older
     * integer-buffer variant without the delay.
     */
    fun processFloats(samples: FloatArray, length: Int = samples.size) {
        if (strength <= 0f) return
        val norm = 1f / (1f + strength)
        var i = 0
        while (i + 1 < length) {
            val left = samples[i]
            val right = samples[i + 1]
            lpfStateL += alpha * (left - lpfStateL)
            lpfStateR += alpha * (right - lpfStateR)
            val crossToLeft = delayR[delayPos]
            val crossToRight = delayL[delayPos]
            delayL[delayPos] = lpfStateL
            delayR[delayPos] = lpfStateR
            delayPos = (delayPos + 1) % itdFrames
            samples[i] = (left + strength * crossToLeft) * norm
            samples[i + 1] = (right + strength * crossToRight) * norm
            i += 2
        }
    }

    /**
     * Applies crossfeed in place to a 16-bit or float32 interleaved stereo
     * buffer — same two bit-depth modes [AudioTrackPlaybackEngine.applySoftwareEq]
     * supports, for the same reason: 24-bit-packed/32-bit-int is left as a
     * pass-through bypass for now rather than half-implementing it.
     */
    fun processBuffer(buffer: ByteArray, mode: BitDepthMode) {
        when (mode) {
            BitDepthMode.PCM_16 -> {
                var i = 0
                while (i + 3 < buffer.size) {
                    val left = readS16(buffer, i) / 32768f
                    val right = readS16(buffer, i + 2) / 32768f
                    val (outLeft, outRight) = process(left, right)
                    writeS16(buffer, i, outLeft)
                    writeS16(buffer, i + 2, outRight)
                    i += 4
                }
            }
            BitDepthMode.PCM_FLOAT -> {
                var i = 0
                while (i + 7 < buffer.size) {
                    val left = readF32(buffer, i)
                    val right = readF32(buffer, i + 4)
                    val (outLeft, outRight) = process(left, right)
                    writeF32(buffer, i, outLeft)
                    writeF32(buffer, i + 4, outRight)
                    i += 8
                }
            }
            else -> { /* 24-bit packed / 32-bit int bypass — see class doc. */ }
        }
    }

    private fun readS16(buffer: ByteArray, offset: Int): Int {
        val low = buffer[offset].toInt() and 0xFF
        val high = buffer[offset + 1].toInt() shl 8
        return (high or low).toShort().toInt()
    }

    private fun writeS16(buffer: ByteArray, offset: Int, value: Float) {
        val sample = (value * 32768f).coerceIn(-32768f, 32767f).toInt()
        buffer[offset] = (sample and 0xFF).toByte()
        buffer[offset + 1] = ((sample ushr 8) and 0xFF).toByte()
    }

    private fun readF32(buffer: ByteArray, offset: Int): Float {
        val b0 = buffer[offset].toInt() and 0xFF
        val b1 = buffer[offset + 1].toInt() and 0xFF
        val b2 = buffer[offset + 2].toInt() and 0xFF
        val b3 = buffer[offset + 3].toInt() and 0xFF
        val bits = (b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0
        return java.lang.Float.intBitsToFloat(bits)
    }

    private fun writeF32(buffer: ByteArray, offset: Int, value: Float) {
        val clamped = value.coerceIn(-1.0f, 1.0f)
        val bits = java.lang.Float.floatToRawIntBits(clamped)
        buffer[offset] = (bits and 0xFF).toByte()
        buffer[offset + 1] = ((bits ushr 8) and 0xFF).toByte()
        buffer[offset + 2] = ((bits ushr 16) and 0xFF).toByte()
        buffer[offset + 3] = ((bits ushr 24) and 0xFF).toByte()
    }
}
