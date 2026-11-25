package com.windstrom5.rebit.playback.dsd

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DSD → PCM: a linear-phase FIR low-pass decimator applied directly to the 1-bit stream.
 *
 * Every DSD rate is converted to **88.2 kHz** (DSD64 ÷32, DSD128 ÷64, DSD256 ÷128): above the
 * point where DSD's shaped noise starts rising steeply, and well within what any output path
 * can play. The filter (Kaiser-windowed sinc, β 8 ≈ 80 dB stopband) passes everything to 20 kHz
 * flat, starts rolling off near 30 kHz, and strongly rejects the ultrasonic noise that would
 * otherwise alias into the audio band.
 *
 * Speed trick used by every serious converter: because each input sample is ±1, the
 * contribution of 8 consecutive taps to the output depends only on the 8 bits of one DSD byte.
 * So the filter is pre-computed into one 256-entry table per byte position and each output
 * sample costs one table lookup per byte of filter length (e.g. 96 additions at DSD64), not
 * hundreds of multiplies.
 *
 * Level: unity gain, so the SACD reference level (50 % modulation) lands at −6 dBFS and the
 * format's allowed peaks above it can't clip.
 */
class DsdToPcmConverter(val dsdRateHz: Int, val channels: Int) {

    /** DSD samples per PCM sample. */
    val decimation: Int = max(32, 32 * (dsdRateHz / 2_822_400))
    val outputRateHz: Int = dsdRateHz / decimation
    private val bytesPerOutput = decimation / 8

    /** Filter length in DSD bytes (8 taps each): longer for higher DSD rates, capped for CPU. */
    private val filterBytes: Int = (96 * max(1, dsdRateHz / 2_822_400)).coerceAtMost(256)
    private val tables: Array<FloatArray> = buildTables()

    // Per-channel ring of the most recent filterBytes DSD bytes.
    private val history = Array(channels) { ByteArray(filterBytes) }
    private var head = 0          // index of the newest byte
    private var phase = 0         // bytes since the last output

    /** Output samples (interleaved floats, ±1 = full modulation) for [frames] DSD frames. */
    fun process(dsd: ByteArray, frames: Int): FloatArray {
        val outFrames = (phase + frames) / bytesPerOutput
        val out = FloatArray(outFrames * channels)
        var o = 0
        for (f in 0 until frames) {
            head = if (head + 1 == filterBytes) 0 else head + 1
            val base = f * channels
            for (c in 0 until channels) history[c][head] = dsd[base + c]
            phase++
            if (phase == bytesPerOutput) {
                phase = 0
                for (c in 0 until channels) {
                    val h = history[c]
                    var acc = 0f
                    var idx = head
                    for (g in 0 until filterBytes) {
                        acc += tables[g][h[idx].toInt() and 0xFF]
                        idx = if (idx == 0) filterBytes - 1 else idx - 1
                    }
                    out[o++] = acc
                }
            }
        }
        return out
    }

    fun reset() {
        history.forEach { it.fill(SILENCE) }
        head = 0
        phase = 0
    }

    init { reset() }

    /**
     * tables[g][b]: contribution of byte value b sitting g bytes back (g = 0 newest). Within a
     * byte, bit 7 is the oldest sample, so bit k is (7 − k) samples older than bit 7… i.e. the
     * tap index for bit k of byte g is 8g + k (bit 0 = newest sample of that byte).
     */
    private fun buildTables(): Array<FloatArray> {
        val taps = filterBytes * 8
        val cutoff = 30_000.0 / dsdRateHz // cycles per DSD sample
        val beta = 8.0
        val i0b = besselI0(beta)
        val h = DoubleArray(taps)
        val mid = (taps - 1) / 2.0
        var sum = 0.0
        for (n in 0 until taps) {
            val x = n - mid
            val sinc = if (x == 0.0) 2 * cutoff else sin(2 * PI * cutoff * x) / (PI * x)
            val r = x / mid
            val w = besselI0(beta * sqrt(max(0.0, 1 - r * r))) / i0b
            h[n] = sinc * w
            sum += h[n]
        }
        for (n in 0 until taps) h[n] /= sum // unity DC gain
        return Array(filterBytes) { g ->
            FloatArray(256) { b ->
                var acc = 0.0
                for (k in 0 until 8) {
                    val bit = (b shr k) and 1
                    acc += h[8 * g + k] * (if (bit == 1) 1.0 else -1.0)
                }
                acc.toFloat()
            }
        }
    }

    companion object {
        /** The DSD idle pattern (equal ones and zeros). */
        const val SILENCE: Byte = 0x69

        private fun besselI0(x: Double): Double {
            var sum = 1.0
            var term = 1.0
            val half = x / 2
            for (k in 1 until 50) {
                term *= (half / k) * (half / k)
                sum += term
                if (term < sum * 1e-16) break
            }
            return sum
        }
    }
}
