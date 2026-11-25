package com.windstrom5.rebit.data.news

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface NewsDao {
    // ── Watchlist ──
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertWatched(artists: List<WatchedArtistEntity>)

    @Query("SELECT * FROM watched_artists ORDER BY name COLLATE NOCASE")
    fun watchedFlow(): Flow<List<WatchedArtistEntity>>

    @Query("SELECT * FROM watched_artists WHERE enabled = 1 ORDER BY lastCheckedMs ASC")
    suspend fun enabledOldestFirst(): List<WatchedArtistEntity>

    @Query("SELECT COUNT(*) FROM watched_artists")
    suspend fun watchedCount(): Int

    @Query("UPDATE watched_artists SET mbid = :mbid, mbidResolved = 1 WHERE name = :name")
    suspend fun setMbid(name: String, mbid: String?)

    @Query("UPDATE watched_artists SET lastCheckedMs = :ms WHERE name = :name")
    suspend fun setChecked(name: String, ms: Long)

    @Query("UPDATE watched_artists SET enabled = :enabled WHERE name = :name")
    suspend fun setEnabled(name: String, enabled: Boolean)

    @Query("SELECT name FROM watched_artists")
    suspend fun watchedNames(): List<String>

    /** Callers pass at most ~500 names so SQLite's bound-variable limit is never hit. */
    @Query("DELETE FROM watched_artists WHERE name IN (:names)")
    suspend fun removeNames(names: List<String>)

    // ── Feed ──
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertFeed(items: List<FeedItemEntity>): List<Long>

    @Query("SELECT * FROM feed_items ORDER BY publishedAtMs DESC LIMIT :limit")
    fun feed(limit: Int): Flow<List<FeedItemEntity>>

    @Query("SELECT COUNT(*) FROM feed_items WHERE read = 0")
    fun unreadCount(): Flow<Int>

    @Query("UPDATE feed_items SET read = 1 WHERE read = 0")
    suspend fun markAllRead()

    @Query("SELECT * FROM feed_items WHERE notified = 0 ORDER BY publishedAtMs DESC")
    suspend fun pendingNotifications(): List<FeedItemEntity>

    @Query("UPDATE feed_items SET notified = 1 WHERE notified = 0")
    suspend fun markAllNotified()

    @Query("DELETE FROM feed_items WHERE publishedAtMs < :before AND read = 1")
    suspend fun pruneOld(before: Long)
}
