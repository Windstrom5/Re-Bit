package com.windstrom5.rebit.domain.audiophile

/** The few fields insights need from a track. */
data class TrackSpec(
    val sampleRateHz: Int,
    val bitDepth: Int,
    val format: String,
    val durationMs: Long,
    val fileSizeBytes: Long,
    val label: String = ""
)

enum class ResolutionTier(val label: String, val hint: String) {
    DSD_RATE("Studio 192 kHz+", "192 kHz and above"),
    HI_RES("Hi-Res", "24-bit or 96 kHz and above"),
    CD("CD quality", "16-bit at 44.1 or 48 kHz"),
    OTHER("Other", "Lower or unusual rates")
}

data class Slice(val label: String, val count: Int, val fraction: Float)

data class LibraryInsightsData(
    val trackCount: Int,
    val totalDurationMs: Long,
    val totalBytes: Long,
    val tiers: List<Slice>,
    val sampleRates: List<Slice>,
    val bitDepths: List<Slice>,
    val formats: List<Slice>,
    val hiResPercent: Int,
    val bestTrack: TrackSpec?
) {
    val avgSizeMb: Float get() = if (trackCount == 0) 0f else totalBytes / trackCount / 1_048_576f
}

object LibraryInsights {

    fun tierOf(rateHz: Int, bits: Int): ResolutionTier = when {
        rateHz >= 192_000 -> ResolutionTier.DSD_RATE
        rateHz >= 96_000 || bits >= 24 -> ResolutionTier.HI_RES
        bits == 16 && (rateHz == 44_100 || rateHz == 48_000) -> ResolutionTier.CD
        else -> ResolutionTier.OTHER
    }

    private fun <K> slices(tracks: List<TrackSpec>, key: (TrackSpec) -> K, label: (K) -> String, order: Comparator<K>): List<Slice> {
        if (tracks.isEmpty()) return emptyList()
        return tracks.groupingBy(key).eachCount().entries
            .sortedWith { a, b -> order.compare(a.key, b.key) }
            .map { Slice(label(it.key), it.value, it.value.toFloat() / tracks.size) }
    }

    fun compute(tracks: List<TrackSpec>): LibraryInsightsData {
        val hiRes = tracks.count { tierOf(it.sampleRateHz, it.bitDepth).let { t -> t == ResolutionTier.HI_RES || t == ResolutionTier.DSD_RATE } }
        return LibraryInsightsData(
            trackCount = tracks.size,
            totalDurationMs = tracks.sumOf { it.durationMs },
            totalBytes = tracks.sumOf { it.fileSizeBytes },
            tiers = slices(tracks, { tierOf(it.sampleRateHz, it.bitDepth) }, { it.label }, compareBy { it.ordinal }),
            sampleRates = slices(tracks, { it.sampleRateHz }, { SignalPathLogic.khz(it) }, compareBy { it }),
            bitDepths = slices(tracks, { it.bitDepth }, { "$it-bit" }, compareBy { it }),
            formats = slices(tracks, { it.format }, { it }, compareBy { it }),
            hiResPercent = if (tracks.isEmpty()) 0 else (hiRes * 100f / tracks.size).toInt(),
            bestTrack = tracks.maxByOrNull { it.sampleRateHz.toLong() * it.bitDepth }
        )
    }

    fun formatSize(bytes: Long): String = when {
        bytes >= 1L shl 30 -> "%.1f GB".format(bytes / (1L shl 30).toDouble())
        else -> "%.0f MB".format(bytes / 1_048_576.0)
    }
}
