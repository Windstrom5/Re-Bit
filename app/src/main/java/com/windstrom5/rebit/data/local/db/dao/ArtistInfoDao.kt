package com.windstrom5.rebit.data.local.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.windstrom5.rebit.data.local.db.entity.ArtistInfoEntity

@Dao
interface ArtistInfoDao {
    @Query("SELECT * FROM artist_info WHERE artistKey = :key")
    suspend fun get(key: String): ArtistInfoEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(info: ArtistInfoEntity)
}
