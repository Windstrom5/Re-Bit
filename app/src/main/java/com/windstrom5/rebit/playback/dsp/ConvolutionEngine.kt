package com.windstrom5.rebit.playback.dsp

import com.windstrom5.rebit.playback.ai.Fft
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Stereo FIR convolution for room / headphone correction impulse responses, using uniformly
 * partitioned overlap-save in the frequency domain (the standard real-time approach used by
 * BruteFIR, CamillaDSP and convolver plugins).
 *
 *  - The IR is cut into [blockSize]-sample partitions, each pre-transformed once.
 *  - Every block of input is transformed once and kept in a frequency-domain delay line; the
 *    output block is the sum of (input spectrum × partition spectrum) over all partitions.
 *  - Both channels share one complex FFT (left in the real part, right in the imaginary part),
 *    halving the transform cost.
 *
 * Cost per sample is roughly (IR length / blockSize) complex multiply-adds per channel, so a
 * 65 536-tap IR at blockSize 1024 is ~64 per sample. Latency is exactly [blockSize] frames.
 * The IR is normalised so its loudest frequency has unity gain: correction never adds level.
 *
 * Pure Kotlin, single-threaded (audio thread only).
 */
class ConvolutionEngine(
    irLeft: FloatArray,
    irRight: FloatArray = irLeft,
    val blockSize: Int = 1024
) {
    init {
        require(blockSize > 0 && blockSize and (blockSize - 1) == 0) { "blockSize must be a power of two" }
        require(irLeft.isNotEmpty() && irRight.isNotEmpty()) { "empty impulse response" }
    }

    private val n = blockSize * 2
    private val half = blockSize + 1
    private val fft = Fft(n)
    val partitions: Int = (max(irLeft.size, irRight.size) + blockSize - 1) / blockSize

    // Partition spectra (half spectrum, bins 0..B): [p][k]
    private val hLre = Array(partitions) { FloatArray(half) }
    private val hLim = Array(partitions) { FloatArray(half) }
    private val hRre = Array(partitions) { FloatArray(half) }
    private val hRim = Array(partitions) { FloatArray(half) }

    // Frequency-domain delay line of input spectra
    private val xLre = Array(partitions) { FloatArray(half) }
    private val xLim = Array(partitions) { FloatArray(half) }
    private val xRre = Array(partitions) { FloatArray(half) }
    private val xRim = Array(partitions) { FloatArray(half) }
    private var fdlHead = 0

    // Time-domain streaming buffers
    private val frameL = FloatArray(n)
    private val frameR = FloatArray(n)
    private val readyL = FloatArray(blockSize)
    private val readyR = FloatArray(blockSize)
    private var fill = 0

    private val re = FloatArray(n)
    private val im = FloatArray(n)
    private val accLre = FloatArray(half)
    private val accLim = FloatArray(half)
    private val accRre = FloatArray(half)
    private val accRim = FloatArray(half)

    /** Linear gain applied to the IR so its peak frequency response is 0 dB. */
    val normalisationGain: Float

    init {
        for (p in 0 until partitions) {
            transformPair(irLeft, irRight, p * blockSize, re, im)
            unpack(re, im, hLre[p], hLim[p], hRre[p], hRim[p])
        }
        // Peak magnitude of the full response, from the summed partitions on a fine grid:
        // evaluate by transforming the whole IR once (zero-padded to a power of two).
        normalisationGain = 1f / max(1e-6f, peakMagnitude(irLeft, irRight))
        for (p in 0 until partitions) {
            for (k in 0 until half) {
                hLre[p][k] *= normalisationGain; hLim[p][k] *= normalisationGain
                hRre[p][k] *= normalisationGain; hRim[p][k] *= normalisationGain
            }
        }
    }

    /** Convolves interleaved stereo [samples] in place (output delayed by [blockSize] frames). */
    fun process(samples: FloatArray, length: Int = samples.size) {
        var i = 0
        while (i + 1 < length) {
            frameL[blockSize + fill] = samples[i]
            frameR[blockSize + fill] = samples[i + 1]
            samples[i] = readyL[fill]
            samples[i + 1] = readyR[fill]
            if (++fill == blockSize) {
                processBlock()
                fill = 0
            }
            i += 2
        }
    }

    fun reset() {
        frameL.fill(0f); frameR.fill(0f); readyL.fill(0f); readyR.fill(0f)
        for (p in 0 until partitions) { xLre[p].fill(0f); xLim[p].fill(0f); xRre[p].fill(0f); xRim[p].fill(0f) }
        fill = 0
    }

    private fun processBlock() {
        // Input spectrum of [previous block | new block] → newest slot of the delay line.
        fdlHead = if (fdlHead == 0) partitions - 1 else fdlHead - 1
        for (t in 0 until n) { re[t] = frameL[t]; im[t] = frameR[t] }
        fft.transform(re, im, inverse = false)
        unpack(re, im, xLre[fdlHead], xLim[fdlHead], xRre[fdlHead], xRim[fdlHead])

        accLre.fill(0f); accLim.fill(0f); accRre.fill(0f); accRim.fill(0f)
        var slot = fdlHead
        for (p in 0 until partitions) {
            val aLr = xLre[slot]; val aLi = xLim[slot]; val aRr = xRre[slot]; val aRi = xRim[slot]
            val bLr = hLre[p]; val bLi = hLim[p]; val bRr = hRre[p]; val bRi = hRim[p]
            for (k in 0 until half) {
                accLre[k] += aLr[k] * bLr[k] - aLi[k] * bLi[k]
                accLim[k] += aLr[k] * bLi[k] + aLi[k] * bLr[k]
                accRre[k] += aRr[k] * bRr[k] - aRi[k] * bRi[k]
                accRim[k] += aRr[k] * bRi[k] + aRi[k] * bRr[k]
            }
            slot = if (slot == partitions - 1) 0 else slot + 1
        }

        // Pack Y = Y_L + i·Y_R (both Hermitian) and inverse-transform once.
        for (k in 0 until half) {
            re[k] = accLre[k] - accRim[k]
            im[k] = accLim[k] + accRre[k]
        }
        for (k in half until n) {
            val m = n - k
            re[k] = accLre[m] + accRim[m]
            im[k] = -accLim[m] + accRre[m]
        }
        fft.transform(re, im, inverse = true)
        val scale = 1f / n
        for (t in 0 until blockSize) {
            readyL[t] = re[blockSize + t] * scale
            readyR[t] = im[blockSize + t] * scale
        }
        // Slide: the new block becomes the "previous" half of the next frame.
        System.arraycopy(frameL, blockSize, frameL, 0, blockSize)
        System.arraycopy(frameR, blockSize, frameR, 0, blockSize)
    }

    /** z = left + i·right for one zero-padded partition, transformed. */
    private fun transformPair(l: FloatArray, r: FloatArray, from: Int, outRe: FloatArray, outIm: FloatArray) {
        for (t in 0 until n) {
            val idx = from + t
            outRe[t] = if (t < blockSize && idx < l.size) l[idx] else 0f
            outIm[t] = if (t < blockSize && idx < r.size) r[idx] else 0f
        }
        fft.transform(outRe, outIm, inverse = false)
    }

    /** Splits the transform of (a + i·b) into the half spectra of a and b. */
    private fun unpack(zr: FloatArray, zi: FloatArray, aRe: FloatArray, aIm: FloatArray, bRe: FloatArray, bIm: FloatArray) {
        for (k in 0 until half) {
            val nk = if (k == 0) 0 else n - k
            val cr = zr[nk]; val ci = -zi[nk]
            aRe[k] = (zr[k] + cr) * 0.5f
            aIm[k] = (zi[k] + ci) * 0.5f
            val dr = zr[k] - cr; val di = zi[k] - ci
            bRe[k] = di * 0.5f
            bIm[k] = -dr * 0.5f
        }
    }

    private fun peakMagnitude(l: FloatArray, r: FloatArray): Float {
        var size = 1
        while (size < max(l.size, r.size) * 2) size = size shl 1
        size = size.coerceAtLeast(1024)
        val f = Fft(size)
        val zr = FloatArray(size)
        val zi = FloatArray(size)
        for (t in l.indices) zr[t] = l[t]
        for (t in r.indices) zi[t] = r[t]
        f.transform(zr, zi, inverse = false)
        var peak = 0f
        for (k in 0..size / 2) {
            val nk = if (k == 0) 0 else size - k
            val ar = (zr[k] + zr[nk]) * 0.5f; val ai = (zi[k] - zi[nk]) * 0.5f
            val br = (zi[k] + zi[nk]) * 0.5f; val bi = -(zr[k] - zr[nk]) * 0.5f
            peak = max(peak, max(sqrt(ar * ar + ai * ai), sqrt(br * br + bi * bi)))
        }
        return peak
    }

    companion object {
        /** Longest IR accepted (taps at the output rate); longer ones are faded and truncated. */
        const val MAX_TAPS = 65_536
    }
}
