package com.windstrom5.rebit.playback.dsp

/**
 * Conversions between little-endian interleaved integer PCM and float samples (±1.0 full scale).
 *
 * Decoding uses the symmetric 2^(bits-1) scale and [DitherQuantizer] encodes with the same
 * scale, so decode → encode of an untouched signal at the same depth is exact.
 */
object PcmCodec {

    /** Decodes [length] bytes of [bitDepth]-bit signed PCM (8-bit is unsigned, WAV style). */
    fun toFloat(input: ByteArray, length: Int, bitDepth: Int): FloatArray = when (bitDepth) {
        8 -> FloatArray(length) { i -> ((input[i].toInt() and 0xFF) - 128) / 128f }
        24 -> {
            val n = length / 3
            FloatArray(n) { i ->
                val o = i * 3
                val v = (input[o].toInt() and 0xFF) or
                    ((input[o + 1].toInt() and 0xFF) shl 8) or
                    (input[o + 2].toInt() shl 16)
                v * INV_2_23
            }
        }
        32 -> {
            val n = length / 4
            FloatArray(n) { i ->
                val o = i * 4
                val v = (input[o].toInt() and 0xFF) or
                    ((input[o + 1].toInt() and 0xFF) shl 8) or
                    ((input[o + 2].toInt() and 0xFF) shl 16) or
                    (input[o + 3].toInt() shl 24)
                (v * INV_2_31_D).toFloat()
            }
        }
        else -> {
            val n = length / 2
            FloatArray(n) { i ->
                val o = i * 2
                val v = ((input[o + 1].toInt() shl 8) or (input[o].toInt() and 0xFF)).toShort()
                v * INV_2_15
            }
        }
    }

    /**
     * Encodes [samples] as [bitDepth]-bit signed little-endian PCM through [quantizer] (which
     * must have been built for the same depth and channel count).
     * @param dither whether this block needs dither — false for an unprocessed source that
     *   already fits the output depth exactly.
     */
    fun fromFloat(
        samples: FloatArray,
        length: Int,
        bitDepth: Int,
        channels: Int,
        quantizer: DitherQuantizer,
        dither: Boolean
    ): ByteArray {
        require(quantizer.bits == bitDepth) { "quantizer is ${quantizer.bits}-bit, output is $bitDepth-bit" }
        val bytesPerSample = bitDepth / 8
        val out = ByteArray(length * bytesPerSample)
        var o = 0
        for (i in 0 until length) {
            val q = quantizer.quantize(samples[i], i % channels, dither)
            var b = 0
            while (b < bytesPerSample) {
                out[o + b] = ((q ushr (8 * b)) and 0xFF).toByte()
                b++
            }
            o += bytesPerSample
        }
        return out
    }

    /** Float32 little-endian, clamped to ±1 (the limiter, when on, keeps it inside already). */
    fun toFloat32Bytes(samples: FloatArray, length: Int = samples.size): ByteArray {
        val out = ByteArray(length * 4)
        for (i in 0 until length) {
            val bits = java.lang.Float.floatToRawIntBits(samples[i].coerceIn(-1f, 1f))
            val o = i * 4
            out[o] = (bits and 0xFF).toByte()
            out[o + 1] = ((bits ushr 8) and 0xFF).toByte()
            out[o + 2] = ((bits ushr 16) and 0xFF).toByte()
            out[o + 3] = ((bits ushr 24) and 0xFF).toByte()
        }
        return out
    }

    private const val INV_2_15 = 1f / 32768f
    private const val INV_2_23 = 1f / 8388608f
    private const val INV_2_31_D = 1.0 / 2147483648.0
}
