package com.windstrom5.rebit.domain.audiophile

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Measured properties of a track, computed from its decoded samples. */
data class TrackAnalysis(
    /** Dynamic range, TT-DR method (the one the "DR database" uses). */
    val drScore: Int,
    /** Highest sample value in dBFS (sample peak, not an inter-sample/true peak). */
    val peakDb: Double,
    /** Average level (RMS) over the whole track in dBFS. */
    val rmsDb: Double,
    /** Peak minus RMS, in dB. */
    val crestDb: Double,
    /** Samples sitting at full scale in runs of 3+, i.e. digital clipping. */
    val clippedSamples: Long,
    /** Worst-channel average sample value as a percentage of full scale. */
    val dcOffsetPercent: Double,
    val analyzedSeconds: Double,
    /** Highest frequency with real content, from the spectrum. 0 if not measured. */
    val bandwidthHz: Int = 0,
    /** Mid energy divided by side energy over the whole track. */
    val midSideRatio: Double = 0.0,
    val effectivelyMono: Boolean = false,
    /** Bits that actually carry signal (a 24-bit file of 16-bit audio reports 16). 0 if not measured. */
    val effectiveBitDepth: Int = 0,
    val totalSamples: Long = 0L,
    val spectrogram: SpectrogramData? = null,
    /** EBU R128 integrated loudness (LUFS); null if the track is silent or wasn't measured. */
    val integratedLufs: Double? = null,
    /** EBU Tech 3342 loudness range (LU). */
    val loudnessRangeLu: Double? = null,
    /** BS.1770 true peak (dBTP), including inter-sample peaks. */
    val truePeakDbtp: Double? = null
) {
    /** Peak-to-loudness ratio (dB): true peak minus integrated loudness — a modern DR measure. */
    val plrDb: Double? get() = if (integratedLufs != null && truePeakDbtp != null) truePeakDbtp - integratedLufs else null

    /** True peak above 0 dBTP: the DAC's reconstruction filter (or a lossy encode) will clip. */
    val hasInterSampleOvers: Boolean get() = (truePeakDbtp ?: -99.0) > 0.0

    val headroomDb: Double get() = -peakDb

    val drRating: String
        get() = when {
            drScore >= 14 -> "Excellent dynamics"
            drScore >= 11 -> "Good dynamics"
            drScore >= 8 -> "Moderately compressed"
            else -> "Heavily compressed"
        }

    val hasClipping: Boolean get() = clippedSamples >= 10
}

/**
 * Streaming analyzer for interleaved little-endian signed PCM (16/24/32-bit), the format the
 * app's decoders produce. Feed chunks of any size; call [finish] at the end.
 *
 * DR follows the standard TT-DR procedure: 3-second blocks, per-channel RMS (with the +3 dB
 * "sine = 0 dB" convention) of the loudest 20% of blocks versus the second-highest block peak,
 * averaged across channels and rounded.
 */
class TrackAnalyzer(
    private val sampleRateHz: Int,
    private val channels: Int,
    private val bitDepth: Int,
    /** Frames the track is expected to have; enables the spectrogram. 0 skips it. */
    expectedFrames: Long = 0L
) {

    private val bytesPerSample = bitDepth / 8
    private val frameBytes = channels * bytesPerSample
    private val blockFrames = sampleRateHz * 3
    private val supported = bitDepth in intArrayOf(16, 24, 32) && channels in 1..8 && sampleRateHz > 0

    private val carry = ByteArray(frameBytes.coerceAtLeast(1))
    private var carryLen = 0

    // whole-track accumulators
    private var totalSumSq = 0.0
    private var totalSamples = 0L
    private var globalPeak = 0.0
    private val dcSum = DoubleArray(channels)
    private var framesSeen = 0L
    private val runLength = IntArray(channels)
    private var clipped = 0L

    // current block, per channel
    private val blockSumSq = DoubleArray(channels)
    private val blockPeak = DoubleArray(channels)
    private var blockFilled = 0

    // finished blocks, per channel
    private val blockMeanSq = Array(channels) { ArrayList<Double>() }
    private val blockPeaks = Array(channels) { ArrayList<Double>() }

    private val scale = when (bitDepth) { 16 -> 32768.0; 24 -> 8388608.0; else -> 2147483648.0 }
    private val clipThreshold = 1.0 - 1.0 / scale * 1.5

    // spectrum, stereo image and real bit depth
    private val spectrum: SpectrumAccumulator? =
        if (supported && expectedFrames > 0) SpectrumAccumulator(sampleRateHz, expectedFrames) else null
    private var sumMid = 0.0
    private var sumSide = 0.0
    private var orMask = 0L
    private val loudness: LoudnessMeter? = if (supported) LoudnessMeter(sampleRateHz, channels) else null
    private val frameValues = DoubleArray(channels.coerceAtLeast(1))

    fun feed(data: ByteArray, length: Int = data.size) {
        if (!supported || frameBytes == 0) return
        var pos = 0
        // complete a frame left over from the previous chunk
        if (carryLen > 0) {
            while (carryLen < frameBytes && pos < length) carry[carryLen++] = data[pos++]
            if (carryLen == frameBytes) { processFrame(carry, 0); carryLen = 0 } else return
        }
        while (pos + frameBytes <= length) { processFrame(data, pos); pos += frameBytes }
        while (pos < length) carry[carryLen++] = data[pos++]
    }

    private fun sampleAt(b: ByteArray, o: Int): Double = when (bytesPerSample) {
        2 -> ((b[o].toInt() and 0xFF) or (b[o + 1].toInt() shl 8)).toShort().toDouble()
        3 -> (((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o + 2].toInt() shl 16))).toDouble()
        else -> ((b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
            ((b[o + 2].toInt() and 0xFF) shl 16) or (b[o + 3].toInt() shl 24)).toDouble()
    } / scale

    private fun processFrame(b: ByteArray, offset: Int) {
        var left = 0.0
        var right = 0.0
        for (c in 0 until channels) {
            val x = sampleAt(b, offset + c * bytesPerSample)
            if (c == 0) left = x
            if (c == 1) right = x
            frameValues[c] = x
            orMask = orMask or Math.round(x * scale)
            val a = kotlin.math.abs(x)
            totalSumSq += x * x
            totalSamples++
            if (a > globalPeak) globalPeak = a
            dcSum[c] += x
            if (a >= clipThreshold) {
                runLength[c]++
                if (runLength[c] == 3) clipped += 3 else if (runLength[c] > 3) clipped++
            } else runLength[c] = 0
            blockSumSq[c] += x * x
            if (a > blockPeak[c]) blockPeak[c] = a
        }
        loudness?.addFrame(frameValues)
        if (channels == 1) right = left
        val mid = (left + right) / 2.0
        val side = (left - right) / 2.0
        sumMid += mid * mid
        sumSide += side * side
        spectrum?.addFrame(mid.toFloat())

        framesSeen++
        if (++blockFilled == blockFrames) closeBlock()
    }

    private fun closeBlock() {
        for (c in 0 until channels) {
            blockMeanSq[c].add(blockSumSq[c] / blockFilled)
            blockPeaks[c].add(blockPeak[c])
            blockSumSq[c] = 0.0
            blockPeak[c] = 0.0
        }
        blockFilled = 0
    }

    private fun db(x: Double) = 20.0 * log10(max(x, 1e-12))

    /** Null if the format is unsupported or the track is silent/empty. */
    fun finish(): TrackAnalysis? {
        if (!supported || totalSamples == 0L || globalPeak <= 0.0) return null
        // Very short tracks have no full 3 s block: use what there is as a single block.
        if (blockMeanSq[0].isEmpty() && blockFilled > 0) closeBlock()

        val drs = ArrayList<Double>()
        for (c in 0 until channels) {
            val ms = blockMeanSq[c]
            val pk = blockPeaks[c]
            if (ms.isEmpty()) continue
            val top = ms.sortedDescending().take(max(1, (ms.size * 0.2).roundToInt()))
            val rmsTop = sqrt(2.0 * top.average())
            val peak2nd = pk.sortedDescending().let { if (it.size > 1) it[1] else it[0] }
            if (rmsTop > 0.0 && peak2nd > 0.0) drs += db(peak2nd) - db(rmsTop)
        }
        val dr = if (drs.isEmpty()) 0 else max(0.0, drs.average()).roundToInt()

        val rms = sqrt(totalSumSq / totalSamples)
        val peakDb = db(globalPeak)
        val rmsDb = db(rms)
        val dc = dcSum.maxOf { kotlin.math.abs(it / framesSeen) } * 100.0
        val spec = spectrum?.finish()
        val mono = channels < 2 || sumSide <= sumMid * 1e-9
        val loud = loudness?.finish()
        val effectiveBits = if (orMask == 0L) bitDepth
        else (bitDepth - java.lang.Long.numberOfTrailingZeros(orMask)).coerceIn(1, bitDepth)
        return TrackAnalysis(
            drScore = dr, peakDb = peakDb, rmsDb = rmsDb, crestDb = peakDb - rmsDb,
            clippedSamples = clipped, dcOffsetPercent = dc,
            analyzedSeconds = framesSeen.toDouble() / sampleRateHz,
            bandwidthHz = spec?.bandwidthHz ?: 0,
            midSideRatio = if (mono || sumSide <= 0.0) 0.0 else sumMid / sumSide,
            effectivelyMono = mono,
            effectiveBitDepth = effectiveBits,
            totalSamples = totalSamples,
            spectrogram = spec?.spectrogram,
            integratedLufs = loud?.integratedLufs,
            loudnessRangeLu = loud?.loudnessRangeLu,
            truePeakDbtp = loud?.truePeakDbtp
        )
    }

    companion object {
        /** Bytes of PCM needed per second, to estimate progress from a duration. */
        fun bytesPerSecond(sampleRateHz: Int, channels: Int, bitDepth: Int) = sampleRateHz.toLong() * channels * (bitDepth / 8)
    }
}
