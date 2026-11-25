package com.windstrom5.rebit.data.local.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/** What an online lookup told us about an artist. A null [genre] is a cached "nothing found". */
@Entity(tableName = "artist_info")
data class ArtistInfoEntity(
    @PrimaryKey val artistKey: String,
    val mbid: String?,
    val displayName: String?,
    val genre: String?,
    val country: String?,
    val checkedAtEpochMs: Long
)
