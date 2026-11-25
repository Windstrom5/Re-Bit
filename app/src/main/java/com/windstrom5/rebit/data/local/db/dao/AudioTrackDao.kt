package com.windstrom5.rebit.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.windstrom5.rebit.data.local.db.entity.AudioTrackEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AudioTrackDao {

    @Query("SELECT * FROM audio_tracks ORDER BY title ASC")
    fun observeAll(): Flow<List<AudioTrackEntity>>

    @Query("SELECT * FROM audio_tracks WHERE id = :id")
    suspend fun getById(id: Long): AudioTrackEntity?

    @Query("SELECT * FROM audio_tracks")
    suspend fun getAllEntities(): List<AudioTrackEntity>

    @Query("SELECT filePath FROM audio_tracks")
    suspend fun getAllPaths(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<AudioTrackEntity>)

    @Query("SELECT COUNT(*) FROM audio_tracks")
    suspend fun count(): Int

    /**
     * Replaces the old `NOT IN (:allExistingPaths)` prune, which bound one SQL variable per song and
     * failed past SQLite's 999-variable limit on many Android versions. Callers pass chunks of <= 500.
     */
    @Query("DELETE FROM audio_tracks WHERE filePath IN (:paths)")
    suspend fun deleteByPaths(paths: List<String>)

    @Query("SELECT * FROM audio_tracks WHERE filePath IN (:paths)")
    suspend fun getByPaths(paths: List<String>): List<AudioTrackEntity>

    @Query("SELECT DISTINCT artist FROM audio_tracks WHERE genre IS NULL AND artist IS NOT NULL")
    suspend fun artistsMissingGenre(): List<String>

    @Query("SELECT title FROM audio_tracks WHERE artist = :artist ORDER BY id LIMIT 1")
    suspend fun sampleTitle(artist: String): String?

    /** Only fills tracks that have no genre, so a genre tag in the file is never overwritten. */
    @Query("UPDATE audio_tracks SET genre = :genre, genreSource = :source WHERE artist = :artist AND genre IS NULL")
    suspend fun applyGenre(artist: String, genre: String, source: String)

    @Query("UPDATE audio_tracks SET title = :title, artist = :artist WHERE id = :id")
    suspend fun updateTags(id: Long, title: String, artist: String?)
}
