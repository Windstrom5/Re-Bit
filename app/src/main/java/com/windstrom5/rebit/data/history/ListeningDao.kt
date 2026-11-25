package com.windstrom5.rebit.data.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ListeningDao {

    @Insert
    suspend fun insertPlay(event: PlayEventEntity): Long

    @Query("UPDATE play_events SET listenedMs = :listenedMs WHERE id = :id")
    suspend fun updateListened(id: Long, listenedMs: Long)

    @Query(
        """SELECT filePath, title, artist, COUNT(*) AS plays, SUM(listenedMs) AS listenedMs,
                  MAX(startedAtMs) AS lastPlayedMs
           FROM play_events WHERE startedAtMs >= :since
           GROUP BY filePath
           ORDER BY plays DESC, listenedMs DESC, lastPlayedMs DESC
           LIMIT :limit"""
    )
    fun topTracks(since: Long, limit: Int): Flow<List<TrackRankRow>>

    @Query(
        """SELECT artist, COUNT(*) AS plays, SUM(listenedMs) AS listenedMs
           FROM play_events
           WHERE startedAtMs >= :since AND artist IS NOT NULL AND artist != ''
           GROUP BY artist
           ORDER BY plays DESC, listenedMs DESC
           LIMIT :limit"""
    )
    fun topArtists(since: Long, limit: Int): Flow<List<ArtistRankRow>>

    @Query(
        """SELECT filePath, title, artist, COUNT(*) AS plays, SUM(listenedMs) AS listenedMs,
                  MAX(startedAtMs) AS lastPlayedMs
           FROM play_events
           GROUP BY filePath
           ORDER BY lastPlayedMs DESC
           LIMIT :limit"""
    )
    fun recentlyPlayed(limit: Int): Flow<List<TrackRankRow>>

    @Query(
        """SELECT COUNT(*) AS plays,
                  COALESCE(SUM(listenedMs), 0) AS listenedMs,
                  COUNT(DISTINCT artist) AS artists,
                  COUNT(DISTINCT filePath) AS tracks,
                  COALESCE(SUM(CASE WHEN sampleRateHz >= 96000 OR bitDepth >= 24 THEN 1 ELSE 0 END), 0) AS hiResPlays
           FROM play_events WHERE startedAtMs >= :since"""
    )
    fun totals(since: Long): Flow<RangeTotals>

    @Query(
        """SELECT (startedAtMs + :tzOffsetMs) / 86400000 AS day,
                  SUM(listenedMs) AS listenedMs, COUNT(*) AS plays
           FROM play_events WHERE startedAtMs >= :since
           GROUP BY day ORDER BY day"""
    )
    fun dailyTotals(since: Long, tzOffsetMs: Long): Flow<List<DayTotalRow>>

    @Query(
        """SELECT DISTINCT (startedAtMs + :tzOffsetMs) / 86400000 AS day
           FROM play_events ORDER BY day DESC LIMIT 400"""
    )
    fun listenDays(tzOffsetMs: Long): Flow<List<Long>>

    // ── Smart collections ──

    @Query("SELECT DISTINCT filePath FROM play_events")
    fun playedPaths(): Flow<List<String>>

    /** Songs you played 3+ times but not since [before]: the ones worth coming back to. */
    @Query(
        """SELECT filePath, title, artist, COUNT(*) AS plays, SUM(listenedMs) AS listenedMs,
                  MAX(startedAtMs) AS lastPlayedMs
           FROM play_events
           GROUP BY filePath
           HAVING COUNT(*) >= 3 AND MAX(startedAtMs) < :before
           ORDER BY plays DESC, lastPlayedMs ASC
           LIMIT :limit"""
    )
    fun rediscover(before: Long, limit: Int): Flow<List<TrackRankRow>>

    // ── One-shot queries for the Wrapped recap ──

    @Query(
        """SELECT filePath, title, artist, COUNT(*) AS plays, SUM(listenedMs) AS listenedMs,
                  MAX(startedAtMs) AS lastPlayedMs
           FROM play_events WHERE startedAtMs >= :since
           GROUP BY filePath
           ORDER BY plays DESC, listenedMs DESC, lastPlayedMs DESC
           LIMIT :limit"""
    )
    suspend fun topTracksOnce(since: Long, limit: Int): List<TrackRankRow>

    @Query(
        """SELECT artist, COUNT(*) AS plays, SUM(listenedMs) AS listenedMs
           FROM play_events
           WHERE startedAtMs >= :since AND artist IS NOT NULL AND artist != ''
           GROUP BY artist
           ORDER BY plays DESC, listenedMs DESC
           LIMIT :limit"""
    )
    suspend fun topArtistsOnce(since: Long, limit: Int): List<ArtistRankRow>

    @Query(
        """SELECT COUNT(*) AS plays,
                  COALESCE(SUM(listenedMs), 0) AS listenedMs,
                  COUNT(DISTINCT artist) AS artists,
                  COUNT(DISTINCT filePath) AS tracks,
                  COALESCE(SUM(CASE WHEN sampleRateHz >= 96000 OR bitDepth >= 24 THEN 1 ELSE 0 END), 0) AS hiResPlays
           FROM play_events WHERE startedAtMs >= :since"""
    )
    suspend fun totalsOnce(since: Long): RangeTotals

    /** Plays per local hour of day (bucket 0..23). */
    @Query(
        """SELECT CAST(((startedAtMs + :tzOffsetMs) / 3600000) % 24 AS INTEGER) AS bucket, COUNT(*) AS plays
           FROM play_events WHERE startedAtMs >= :since GROUP BY bucket"""
    )
    suspend fun hourHistogram(since: Long, tzOffsetMs: Long): List<BucketRow>

    /** Plays per local weekday, Monday = 0 .. Sunday = 6 (epoch day 0 was a Thursday). */
    @Query(
        """SELECT CAST(((startedAtMs + :tzOffsetMs) / 86400000 + 3) % 7 AS INTEGER) AS bucket, COUNT(*) AS plays
           FROM play_events WHERE startedAtMs >= :since GROUP BY bucket"""
    )
    suspend fun weekdayHistogram(since: Long, tzOffsetMs: Long): List<BucketRow>

    @Query(
        """SELECT DISTINCT (startedAtMs + :tzOffsetMs) / 86400000 AS day
           FROM play_events WHERE startedAtMs >= :since ORDER BY day"""
    )
    suspend fun listenDaysSince(since: Long, tzOffsetMs: Long): List<Long>

    // ── Favorites ──

    @Query("SELECT * FROM favorites ORDER BY addedAtMs DESC")
    fun favorites(): Flow<List<FavoriteEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addFavorite(favorite: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE filePath = :filePath")
    suspend fun removeFavorite(filePath: String)

    @Query("DELETE FROM play_events")
    suspend fun clearHistory()
}
