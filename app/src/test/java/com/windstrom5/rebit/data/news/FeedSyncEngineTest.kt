package com.windstrom5.rebit.data.news

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class FeedSyncEngineTest {
    private val now = 1_800_000_000_000L
    private val day = 86_400_000L

    private class FakeStore(val artists: List<FollowedArtist>) : FeedStore {
        val items = linkedMapOf<String, FeedItem>()
        val checked = mutableMapOf<String, Long>()
        val mbids = mutableMapOf<String, String?>()
        override suspend fun followed() = artists.map {
            it.copy(
                lastCheckedMs = checked[it.name] ?: it.lastCheckedMs,
                mbid = if (mbids.containsKey(it.name)) mbids[it.name] else it.mbid,
                mbidResolved = it.mbidResolved || mbids.containsKey(it.name)
            )
        }
        override suspend fun setMbid(name: String, mbid: String?) { mbids[name] = mbid }
        override suspend fun setChecked(name: String, ms: Long) { checked[name] = ms }
        override suspend fun insert(items: List<FeedItem>) { items.forEach { this.items.putIfAbsent(it.id, it) } }
    }

    private class FakeReleases(val groups: Map<String, List<MbReleaseGroup>>, val fail: Boolean = false) : ReleaseSource {
        var findCalls = 0
        override suspend fun findArtistId(name: String): String? {
            findCalls++
            if (fail) throw IOException("offline")
            return if (name in groups) "mb-$name" else null
        }
        override suspend fun releaseGroups(mbid: String): List<MbReleaseGroup> {
            if (fail) throw IOException("offline")
            return groups[mbid.removePrefix("mb-")].orEmpty()
        }
    }

    private class FakeNews(val articles: List<NewsArticle> = emptyList()) : NewsSource {
        override suspend fun search(query: String, timespan: String) = articles
    }

    @Test fun firstSyncIsSilentThenNewItemsNotify() = runBlocking {
        val store = FakeStore(listOf(FollowedArtist("Muse", null, false, 0L)))
        val groups = listOf(
            MbReleaseGroup("a", "Old Album", "Album", now - 400 * day),
            MbReleaseGroup("b", "Fresh Single", "Single", now - 5 * day),
            MbReleaseGroup("c", "Announced EP", "EP", now + 30 * day),
            MbReleaseGroup("d", "Far Future", "Album", now + 400 * day)
        )
        val news = FakeNews(
            listOf(
                NewsArticle("http://x/1", "Muse announce new tour dates", "nme.com", now - day),
                NewsArticle("http://x/2", "Queen Elizabeth news", null, now)
            )
        )
        FeedSyncEngine(store, FakeReleases(mapOf("Muse" to groups)), news).sync(now)

        assertEquals(setOf("rg:b", "rg:c", "news:http://x/1"), store.items.keys)
        assertTrue("first sync must be silent", store.items.values.all { it.notified })

        // A later sync: a brand-new release appears and should be notifiable.
        val later = FakeReleases(
            mapOf("Muse" to listOf(MbReleaseGroup("b", "Fresh Single", "Single", now - 5 * day), MbReleaseGroup("e", "Surprise Album", "Album", now + day)))
        )
        FeedSyncEngine(store, later, FakeNews()).sync(now + day)
        assertFalse(store.items.getValue("rg:e").notified)
        assertTrue(store.items.getValue("rg:b").notified)
        assertEquals(0, later.findCalls) // MBID was cached
    }

    @Test fun offlineArtistIsRetriedNotMarkedChecked() = runBlocking {
        val store = FakeStore(listOf(FollowedArtist("Radiohead", null, false, 0L)))
        FeedSyncEngine(store, FakeReleases(emptyMap(), fail = true), FakeNews()).sync(now)
        assertTrue(store.checked.isEmpty())
    }

    @Test fun unmatchedArtistIsResolvedOnce() = runBlocking {
        val store = FakeStore(listOf(FollowedArtist("Nobody Band", null, false, 0L)))
        FeedSyncEngine(store, FakeReleases(emptyMap()), FakeNews()).sync(now)
        assertTrue(store.mbids.containsKey("Nobody Band"))
        assertEquals(now, store.checked["Nobody Band"])
    }

    @Test fun workPerPassIsBoundedOldestFirst() = runBlocking {
        val many = (1..50).map { FollowedArtist("Artist$it", "mb-Artist$it", true, it.toLong()) }
        val store = FakeStore(many)
        FeedSyncEngine(store, FakeReleases(many.associate { it.name to emptyList() }), FakeNews(), maxReleaseArtists = 30).sync(now)
        assertEquals(30, store.checked.size)
        assertTrue("Artist1" in store.checked)
        assertFalse("Artist31" in store.checked)
    }
}
