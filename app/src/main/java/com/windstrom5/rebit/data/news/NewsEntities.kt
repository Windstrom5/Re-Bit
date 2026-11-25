package com.windstrom5.rebit.data.news

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** An artist we follow, derived from the library's artist tags. */
@Entity(tableName = "watched_artists")
data class WatchedArtistEntity(
    @PrimaryKey val name: String,
    val mbid: String?,
    /** True once we asked MusicBrainz (even if nothing matched), so we don't re-ask every sync. */
    val mbidResolved: Boolean,
    val lastCheckedMs: Long,
    val enabled: Boolean,
    val addedAtMs: Long
)

/** One release or headline. [id] is stable ("rg:<mbid>" / "news:<url>") so re-syncs never duplicate. */
@Entity(tableName = "feed_items", indices = [Index("publishedAtMs")])
data class FeedItemEntity(
    @PrimaryKey val id: String,
    val artist: String,
    val kind: String,
    val title: String,
    val subtitle: String?,
    val url: String?,
    val publishedAtMs: Long,
    val discoveredAtMs: Long,
    val notified: Boolean,
    val read: Boolean
)
