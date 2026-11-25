package com.windstrom5.rebit.data.news

import com.windstrom5.rebit.domain.usecase.ObserveLibraryUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Follows every artist found in the library, pulls new releases (MusicBrainz) and headlines
 * (GDELT), stores them, and raises notifications for anything new.
 */
class ArtistFeedRepository(
    private val dao: NewsDao,
    private val observeLibrary: ObserveLibraryUseCase,
    private val musicBrainz: MusicBrainzClient,
    private val gdelt: GdeltClient,
    private val prefs: NewsPrefs,
    private val notifier: NewsNotifier
) {
    private val syncMutex = Mutex()
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    fun feed(limit: Int = 100): Flow<List<FeedItemEntity>> = dao.feed(limit)
    fun unreadCount(): Flow<Int> = dao.unreadCount()
    fun followedArtists(): Flow<List<WatchedArtistEntity>> = dao.watchedFlow()

    suspend fun markAllRead() = dao.markAllRead()
    suspend fun setArtistEnabled(name: String, enabled: Boolean) = dao.setEnabled(name, enabled)

    /** Mirrors the library's artist tags into the follow list (adds new ones, drops vanished ones). */
    suspend fun syncWatchlist() {
        val tags = observeLibrary().first().map { it.artist }
        val wanted = NewsLogic.followList(tags)
        if (wanted.isEmpty()) return // never wipe the list because of a transient empty library
        val now = System.currentTimeMillis()
        dao.insertWatched(wanted.map { WatchedArtistEntity(it, null, false, 0L, true, now) })
        val gone = dao.watchedNames().filter { existing -> wanted.none { it.equals(existing, ignoreCase = false) } }
        gone.chunked(500).forEach { dao.removeNames(it) }
    }

    /** @return number of items stored this pass. No-op if a sync is already running. */
    suspend fun runSync(): Int {
        if (!syncMutex.tryLock()) return 0
        _syncing.value = true
        try {
            syncWatchlist()
            val engine = FeedSyncEngine(
                store = object : FeedStore {
                    override suspend fun followed() = dao.enabledOldestFirst().map {
                        FollowedArtist(it.name, it.mbid, it.mbidResolved, it.lastCheckedMs)
                    }
                    override suspend fun setMbid(name: String, mbid: String?) = dao.setMbid(name, mbid)
                    override suspend fun setChecked(name: String, ms: Long) = dao.setChecked(name, ms)
                    override suspend fun insert(items: List<FeedItem>) {
                        dao.insertFeed(items.map {
                            FeedItemEntity(
                                id = it.id, artist = it.artist, kind = it.kind.name, title = it.title,
                                subtitle = null, url = it.url, publishedAtMs = it.publishedAtMs,
                                discoveredAtMs = it.discoveredAtMs, notified = it.notified, read = it.notified
                            )
                        })
                    }
                },
                releases = object : ReleaseSource {
                    override suspend fun findArtistId(name: String) = musicBrainz.findArtistId(name)
                    override suspend fun releaseGroups(mbid: String) = musicBrainz.releaseGroups(mbid)
                },
                news = object : NewsSource {
                    override suspend fun search(query: String, timespan: String) = gdelt.search(query, timespan)
                }
            )
            val now = System.currentTimeMillis()
            val stored = engine.sync(now, SyncOptions(releases = prefs.releasesEnabled, news = prefs.newsEnabled))

            val pending = dao.pendingNotifications()
            if (pending.isNotEmpty() && prefs.notificationsEnabled) notifier.show(pending)
            // Mark handled even when notifications are off, so turning them on later doesn't dump old items.
            dao.markAllNotified()
            dao.pruneOld(now - 90 * FeedSyncEngine.DAY)
            prefs.lastSyncMs = now
            return stored
        } finally {
            _syncing.value = false
            syncMutex.unlock()
        }
    }
}
