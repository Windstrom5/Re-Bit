package com.windstrom5.rebit.data.genre

import com.windstrom5.rebit.data.local.db.dao.ArtistInfoDao
import com.windstrom5.rebit.data.local.db.dao.AudioTrackDao
import com.windstrom5.rebit.data.local.db.entity.ArtistInfoEntity
import com.windstrom5.rebit.data.wikipedia.WikiMatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Fills in a genre for tracks whose files carry no genre tag.
 *
 * Genre from the file's own tag always wins (it is set by the scanner). For the rest, each distinct
 * artist is looked up once on MusicBrainz and the answer is cached in [ArtistInfoEntity], so a library
 * of thousands of songs needs one request per artist, spread out at MusicBrainz's polite rate. Runs in
 * the background, resumes where it left off, and never overwrites a tagged genre.
 */
class GenreRepository(
    private val tracks: AudioTrackDao,
    private val artists: ArtistInfoDao,
    private val client: MusicBrainzClient = MusicBrainzClient(),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    data class Progress(
        val running: Boolean = false,
        val done: Int = 0,
        val total: Int = 0,
        val message: String? = null
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private var job: Job? = null

    /** Starts a background pass unless one is already running. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            try {
                enrich()
            } catch (e: IOException) {
                _progress.value = Progress(message = "Genre lookup paused (${e.message ?: "no connection"}). It will resume next time.")
            }
        }
    }

    /** What we learned about an artist (genre, country), for Wikipedia disambiguation. */
    suspend fun artistInfo(artist: String?): ArtistInfoEntity? {
        val key = keyOf(artist) ?: return null
        return artists.get(key)
    }

    private suspend fun enrich() {
        val pending = tracks.artistsMissingGenre().filterNot { WikiMatcher.isPlaceholderArtist(it) }
        if (pending.isEmpty()) {
            _progress.value = Progress()
            return
        }

        // Artists we already know the answer for are applied instantly, with no network.
        val now = System.currentTimeMillis()
        val lookups = ArrayList<String>()
        for (artist in pending) {
            val cached = artists.get(keyOf(artist) ?: continue)
            when {
                cached?.genre != null -> tracks.applyGenre(artist, cached.genre, SOURCE_AUTO)
                cached != null && now - cached.checkedAtEpochMs < RETRY_MISSING_AFTER_MS -> Unit
                else -> lookups += artist
            }
        }

        val todo = lookups.take(MAX_LOOKUPS_PER_RUN)
        if (todo.isEmpty()) {
            _progress.value = Progress()
            return
        }
        _progress.value = Progress(running = true, total = todo.size)

        todo.forEachIndexed { i, artist ->
            coroutineContext.ensureActive()
            val found = client.find(artist, tracks.sampleTitle(artist))
            val genre = found?.let { GenreLogic.pickGenre(it.genres, it.tags) }
            artists.upsert(
                ArtistInfoEntity(
                    artistKey = keyOf(artist) ?: return@forEachIndexed,
                    mbid = found?.mbid,
                    displayName = found?.name,
                    genre = genre,
                    country = found?.country,
                    checkedAtEpochMs = System.currentTimeMillis()
                )
            )
            if (genre != null) tracks.applyGenre(artist, genre, SOURCE_AUTO)
            _progress.value = Progress(running = true, done = i + 1, total = todo.size)
        }
        _progress.value = Progress()
    }

    private fun keyOf(artist: String?): String? =
        artist?.let { WikiMatcher.normalize(it) }?.takeIf { it.isNotBlank() }

    companion object {
        const val SOURCE_TAG = "TAG"
        const val SOURCE_AUTO = "AUTO"
        private const val MAX_LOOKUPS_PER_RUN = 150
        private const val RETRY_MISSING_AFTER_MS = 30L * 24 * 60 * 60 * 1000
    }
}
