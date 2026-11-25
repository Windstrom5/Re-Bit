package com.windstrom5.rebit.data.news

import java.io.IOException

enum class FeedKind { RELEASE, NEWS }

data class MbReleaseGroup(val id: String, val title: String, val primaryType: String?, val firstReleaseMs: Long?)
data class NewsArticle(val url: String, val title: String, val domain: String?, val publishedMs: Long?)

/** Plain models so the engine is independent of Room and testable on the JVM. */
data class FollowedArtist(val name: String, val mbid: String?, val mbidResolved: Boolean, val lastCheckedMs: Long)

data class FeedItem(
    val id: String,
    val artist: String,
    val kind: FeedKind,
    val title: String,
    val url: String?,
    val publishedAtMs: Long,
    val discoveredAtMs: Long,
    val notified: Boolean
)

interface FeedStore {
    /** Enabled artists, least-recently-checked first. */
    suspend fun followed(): List<FollowedArtist>
    suspend fun setMbid(name: String, mbid: String?)
    suspend fun setChecked(name: String, ms: Long)
    suspend fun insert(items: List<FeedItem>)
}

interface ReleaseSource {
    suspend fun findArtistId(name: String): String?
    suspend fun releaseGroups(mbid: String): List<MbReleaseGroup>
}

interface NewsSource {
    suspend fun search(query: String, timespan: String): List<NewsArticle>
}

data class SyncOptions(val releases: Boolean = true, val news: Boolean = true)

/**
 * One sync pass. Designed around three rules:
 *  1. First time we see an artist, its existing releases/headlines are stored but NOT notified
 *     (otherwise connecting a 300-artist library would fire hundreds of notifications).
 *  2. Items have stable ids, so a re-sync never duplicates or re-notifies.
 *  3. Work per pass is bounded (oldest-checked artists first), so big libraries rotate through
 *     over several runs while respecting MusicBrainz (1 req/s) and GDELT (1 req/5 s).
 */
class FeedSyncEngine(
    private val store: FeedStore,
    private val releases: ReleaseSource,
    private val news: NewsSource,
    private val maxReleaseArtists: Int = 30,
    private val newsBatchSize: Int = 8,
    private val maxNewsBatches: Int = 6
) {
    companion object {
        const val DAY = 86_400_000L
        const val RECENT_RELEASE_WINDOW = 120 * DAY
        const val UPCOMING_WINDOW = 90 * DAY
    }

    suspend fun sync(nowMs: Long, options: SyncOptions = SyncOptions()): Int {
        val artists = store.followed()
        if (artists.isEmpty()) return 0
        val firstTime = artists.filter { it.lastCheckedMs == 0L }.map { it.name }.toSet()
        var stored = 0

        if (options.releases) {
            for (a in artists.take(maxReleaseArtists)) {
                try {
                    var mbid = a.mbid
                    if (!a.mbidResolved) {
                        mbid = releases.findArtistId(a.name)
                        store.setMbid(a.name, mbid)
                    }
                    if (mbid != null) {
                        val items = releases.releaseGroups(mbid)
                            .filter { g ->
                                val t = g.firstReleaseMs
                                t != null && t >= nowMs - RECENT_RELEASE_WINDOW && t <= nowMs + UPCOMING_WINDOW
                            }
                            .map { g ->
                                FeedItem(
                                    id = NewsLogic.releaseId(g.id),
                                    artist = a.name,
                                    kind = FeedKind.RELEASE,
                                    title = NewsLogic.releaseLine(g.primaryType, g.title),
                                    url = "https://musicbrainz.org/release-group/${g.id}",
                                    publishedAtMs = g.firstReleaseMs!!,
                                    discoveredAtMs = nowMs,
                                    notified = a.name in firstTime
                                )
                            }
                        store.insert(items)
                        stored += items.size
                    }
                    store.setChecked(a.name, nowMs)
                } catch (e: IOException) {
                    // Offline or rate-limited: leave lastChecked alone so this artist is retried first next time.
                }
            }
        }

        if (options.news) {
            val names = artists.map { it.name }.filter { it.length >= 3 }
            for (batch in names.chunked(newsBatchSize).take(maxNewsBatches)) {
                val query = NewsLogic.gdeltQuery(batch) ?: continue
                val timespan = if (batch.any { it in firstTime }) "7d" else "3d"
                val articles = try {
                    news.search(query, timespan)
                } catch (e: IOException) {
                    continue
                }
                val items = articles.mapNotNull { art ->
                    val who = NewsLogic.attribute(art.title, art.domain, batch) ?: return@mapNotNull null
                    FeedItem(
                        id = NewsLogic.newsId(art.url),
                        artist = who,
                        kind = FeedKind.NEWS,
                        title = art.title,
                        url = art.url,
                        publishedAtMs = art.publishedMs ?: nowMs,
                        discoveredAtMs = nowMs,
                        notified = who in firstTime
                    )
                }
                store.insert(items)
                stored += items.size
            }
        }
        return stored
    }
}
