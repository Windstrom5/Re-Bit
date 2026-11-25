package com.windstrom5.rebit.data.history

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One counted play. Keyed by [filePath] (stable across library rescans, unlike audio_tracks.id)
 * and carries a snapshot of the metadata so history survives the file being moved or removed.
 */
@Entity(
    tableName = "play_events",
    indices = [Index("startedAtMs"), Index("filePath")]
)
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val filePath: String,
    val title: String,
    val artist: String?,
    val format: String,
    val sampleRateHz: Int,
    val bitDepth: Int,
    val trackDurationMs: Long,
    val startedAtMs: Long,
    val listenedMs: Long
)

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey val filePath: String,
    val title: String,
    val artist: String?,
    val addedAtMs: Long
)

// ── Query result rows (Room maps columns by name) ──

data class TrackRankRow(
    val filePath: String,
    val title: String,
    val artist: String?,
    val plays: Int,
    val listenedMs: Long,
    val lastPlayedMs: Long
)

data class ArtistRankRow(val artist: String, val plays: Int, val listenedMs: Long)

data class DayTotalRow(val day: Long, val listenedMs: Long, val plays: Int)

data class RangeTotals(
    val plays: Int = 0,
    val listenedMs: Long = 0L,
    val artists: Int = 0,
    val tracks: Int = 0,
    val hiResPlays: Int = 0
)

data class BucketRow(val bucket: Int, val plays: Int)

/** Everything the Wrapped cards need, computed once per period. */
data class WrappedData(
    val period: WrappedPeriod,
    val totals: RangeTotals,
    val topTracks: List<TrackRankRow>,
    val topArtists: List<ArtistRankRow>,
    val hourPlays: List<Int>,
    val weekdayPlays: List<Int>,
    val longestStreak: Int,
    val persona: ListeningPersona
) {
    val hasData: Boolean get() = totals.plays > 0
    val minutes: Long get() = totals.listenedMs / 60_000
    val hiResPercent: Int get() = if (totals.plays == 0) 0 else (totals.hiResPlays * 100f / totals.plays).toInt()
}
