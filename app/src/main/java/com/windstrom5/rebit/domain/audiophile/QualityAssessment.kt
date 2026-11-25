package com.windstrom5.rebit.domain.audiophile

import java.util.Locale

enum class QualityLevel { GOOD, INFO, WARN }

data class QualityItem(val level: QualityLevel, val title: String, val detail: String)

/**
 * Plain-language checklist about a track's technical quality. The first three items only need the
 * file's format; the rest come from the measured analysis (see [TrackAnalyzer]).
 */
object QualityAssessment {

    private fun khz(hz: Number): String = String.format(Locale.US, "%.1f", hz.toDouble() / 1000.0)

    fun build(
        formatName: String,
        isLossless: Boolean,
        sampleRateHz: Int,
        bitDepth: Int,
        a: TrackAnalysis?,
        isDsd: Boolean = false
    ): List<QualityItem> {
        val items = ArrayList<QualityItem>()
        if (isDsd) return buildDsd(formatName, sampleRateHz, a)

        items += if (isLossless) {
            QualityItem(QualityLevel.GOOD, "Lossless format", "$formatName: the file format itself applies no lossy compression.")
        } else {
            QualityItem(QualityLevel.WARN, "Lossy format", "$formatName discards audio detail to save space.")
        }

        items += when {
            sampleRateHz >= 88_200 -> QualityItem(
                QualityLevel.GOOD, "Sample rate: ${khz(sampleRateHz)} kHz (high-resolution)",
                "Above CD standard; the format can carry content up to ${khz(sampleRateHz / 2)} kHz."
            )
            sampleRateHz >= 44_100 -> QualityItem(
                QualityLevel.INFO,
                "Sample rate: ${khz(sampleRateHz)} kHz (${if (sampleRateHz == 44_100) "CD standard" else "video standard"})",
                "Meets or exceeds CD standard."
            )
            else -> QualityItem(QualityLevel.WARN, "Sample rate: ${khz(sampleRateHz)} kHz", "Below CD standard, so high frequencies are limited.")
        }

        val effective = a?.effectiveBitDepth?.takeIf { it > 0 }
        items += when {
            effective != null && bitDepth > 16 && effective <= 16 -> QualityItem(
                QualityLevel.WARN, "$bitDepth-bit container holds $effective-bit audio",
                "The lowest ${bitDepth - effective} bits are always zero, so this is CD-depth audio stored in a larger container."
            )
            effective != null && effective < bitDepth && bitDepth > 16 -> QualityItem(
                QualityLevel.INFO, "$bitDepth-bit container, $effective bits in use",
                "Only the top $effective bits carry signal."
            )
            bitDepth >= 24 -> QualityItem(
                QualityLevel.GOOD, "$bitDepth-bit depth", "~${AudioFacts.theoreticalDynamicRangeDb(bitDepth)} dB theoretical dynamic range, with ample headroom."
            )
            else -> QualityItem(
                QualityLevel.INFO, "$bitDepth-bit depth (CD standard)",
                "~${AudioFacts.theoreticalDynamicRangeDb(bitDepth)} dB dynamic range, sufficient for most listening."
            )
        }

        if (a == null) return items

        // Spectral content
        if (a.bandwidthHz > 0 && sampleRateHz > 0) {
            val nyq = sampleRateHz / 2
            val pct = (a.bandwidthHz * 100.0 / nyq).toInt().coerceIn(0, 100)
            val bw = a.bandwidthHz
            items += when {
                bw >= nyq * 0.95 -> QualityItem(
                    QualityLevel.GOOD, "Full spectral content",
                    "Content extends to ${khz(bw)} kHz ($pct% of Nyquist)."
                )
                nyq > 23_000 && bw in 21_000..22_600 -> QualityItem(
                    QualityLevel.INFO, "CD-rate bandwidth",
                    "Content stops near ${khz(bw)} kHz, the CD limit. The higher sample rate adds no extra bandwidth, so this was probably upsampled from 44.1 kHz."
                )
                bw in 14_000..20_900 -> QualityItem(
                    QualityLevel.WARN, "Limited bandwidth",
                    "Content stops at ${khz(bw)} kHz ($pct% of Nyquist). This pattern is typical of MP3 or AAC audio converted to lossless."
                )
                bw < 14_000 -> QualityItem(
                    QualityLevel.INFO, "Low bandwidth",
                    "Content stops at ${khz(bw)} kHz. It may be a dark recording, an old master or a low-bitrate source."
                )
                else -> QualityItem(QualityLevel.INFO, "Spectral content", "Content extends to ${khz(bw)} kHz ($pct% of Nyquist).")
            }
        }

        // Clipping
        val pctClipped = if (a.totalSamples > 0) a.clippedSamples * 100.0 / a.totalSamples else 0.0
        items += if (!a.hasClipping) {
            QualityItem(QualityLevel.GOOD, "No clipping", "No samples stuck at full scale.")
        } else {
            QualityItem(
                QualityLevel.WARN,
                if (pctClipped < 0.05) "Minor clipping" else "Clipping detected",
                "${String.format(Locale.US, "%.3f", pctClipped)}% of samples at full scale."
            )
        }

        // Dynamic range
        items += when {
            a.drScore >= 14 -> QualityItem(QualityLevel.GOOD, "Excellent dynamic range", "DR${a.drScore}, crest factor ${String.format(Locale.US, "%.1f", a.crestDb)} dB.")
            a.drScore >= 11 -> QualityItem(QualityLevel.GOOD, "Good dynamic range", "DR${a.drScore}, crest factor ${String.format(Locale.US, "%.1f", a.crestDb)} dB.")
            a.drScore >= 8 -> QualityItem(QualityLevel.INFO, "Moderately compressed", "DR${a.drScore}, crest factor ${String.format(Locale.US, "%.1f", a.crestDb)} dB.")
            else -> QualityItem(QualityLevel.WARN, "Heavily compressed", "DR${a.drScore}: loud mastering with little dynamic contrast.")
        }

        // Stereo image
        if (a.effectivelyMono) {
            items += QualityItem(QualityLevel.INFO, "Mono recording", "Both channels carry the same signal.")
        } else if (a.midSideRatio > 0.0) {
            val ratio = String.format(Locale.US, "%.2f", a.midSideRatio)
            items += when {
                a.midSideRatio < 0.3 -> QualityItem(
                    QualityLevel.WARN, "Very wide or out-of-phase stereo",
                    "Mid/side ratio $ratio: more side than centre energy. It may thin out when played in mono."
                )
                a.midSideRatio <= 8.0 -> QualityItem(QualityLevel.INFO, "Stereo balance", "Mid/side ratio $ratio, a typical stereo mix.")
                else -> QualityItem(QualityLevel.INFO, "Narrow stereo image", "Mid/side ratio $ratio: the mix is centred, close to mono.")
            }
        }

        if (a.dcOffsetPercent > 0.5) {
            items += QualityItem(QualityLevel.WARN, "DC offset", "${String.format(Locale.US, "%.2f", a.dcOffsetPercent)}% of full scale; it wastes headroom.")
        }
        items += loudnessItems(a)
        return items
    }

    /** EBU R128 loudness and BS.1770 true-peak findings. */
    fun loudnessItems(a: TrackAnalysis): List<QualityItem> {
        val items = ArrayList<QualityItem>()
        val f = { x: Double -> String.format(Locale.US, "%.1f", x) }
        a.integratedLufs?.let { lufs ->
            val lra = a.loudnessRangeLu?.let { ", loudness range ${f(it)} LU" } ?: ""
            val plr = a.plrDb?.let { ", PLR ${f(it)} dB" } ?: ""
            items += when {
                lufs > -10.0 -> QualityItem(
                    QualityLevel.WARN, "Very loud master (${f(lufs)} LUFS)",
                    "Loudness-war territory$lra$plr. Streaming services turn this down to about −14 LUFS, so the squashing buys nothing."
                )
                lufs > -16.0 -> QualityItem(QualityLevel.INFO, "Typical modern loudness (${f(lufs)} LUFS)", "Integrated loudness$lra$plr.")
                else -> QualityItem(QualityLevel.GOOD, "Dynamic master (${f(lufs)} LUFS)", "Plenty of headroom left for transients$lra$plr.")
            }
        }
        a.truePeakDbtp?.let { tp ->
            items += when {
                tp > 0.0 -> QualityItem(
                    QualityLevel.WARN, "Inter-sample overs (true peak +${f(tp)} dBTP)",
                    "Peaks between the samples exceed full scale. A DAC's reconstruction filter or a lossy encode can clip them; " +
                        "lowering the level ~${f(tp + 0.5)} dB (or ReplayGain with Prevent clipping) avoids it."
                )
                tp > -1.0 -> QualityItem(QualityLevel.INFO, "True peak ${f(tp)} dBTP", "Within 1 dB of full scale — little inter-sample headroom.")
                else -> QualityItem(QualityLevel.GOOD, "True peak ${f(tp)} dBTP", "Comfortable headroom; no inter-sample clipping.")
            }
        }
        return items
    }

    private fun buildDsd(formatName: String, dsdRateHz: Int, a: TrackAnalysis?): List<QualityItem> {
        val items = ArrayList<QualityItem>()
        val label = com.windstrom5.rebit.domain.model.dsdRateLabel(dsdRateHz) ?: "DSD"
        items += QualityItem(
            QualityLevel.GOOD, "$label direct stream digital",
            "$formatName, 1-bit at ${String.format(Locale.US, "%.4f", dsdRateHz / 1_000_000.0)} MHz. Plays bit-perfect as DoP on a DoP DAC over USB Direct."
        )
        if (a == null) return items
        if (a.hasClipping) items += QualityItem(QualityLevel.WARN, "Overload after conversion", "Samples reached full scale in the PCM conversion.")
        items += when {
            a.drScore >= 11 -> QualityItem(QualityLevel.GOOD, "Good dynamic range", "DR${a.drScore} (measured on the PCM conversion).")
            a.drScore >= 8 -> QualityItem(QualityLevel.INFO, "Moderately compressed", "DR${a.drScore} (measured on the PCM conversion).")
            else -> QualityItem(QualityLevel.WARN, "Heavily compressed", "DR${a.drScore} (measured on the PCM conversion).")
        }
        items += loudnessItems(a)
        return items
    }
}
