package com.windstrom5.rebit.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.windstrom5.rebit.data.genre.GenreLogic
import com.windstrom5.rebit.data.genre.GenreRepository
import com.windstrom5.rebit.data.genre.ScriptHint
import com.windstrom5.rebit.data.wikipedia.WikiHints
import com.windstrom5.rebit.data.wikipedia.WikiLoad
import com.windstrom5.rebit.data.wikipedia.WikiLookup
import com.windstrom5.rebit.data.wikipedia.WikiMatcher
import com.windstrom5.rebit.data.wikipedia.WikipediaRepository
import com.windstrom5.rebit.data.wikipedia.WikipediaUiState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Looks up the playing song and its artist on Wikipedia whenever the track changes.
 * Kept separate from PlayerViewModel so playback state is untouched by network work.
 */
class WikipediaViewModel(
    private val repository: WikipediaRepository,
    private val genreRepository: GenreRepository
) : ViewModel() {

    private val _state = MutableStateFlow(WikipediaUiState())
    val state: StateFlow<WikipediaUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var lastGenre: String? = null
    private var lyricsScript: ScriptHint? = null

    fun onTrackChanged(
        trackKey: String?,
        title: String?,
        artist: String?,
        genre: String? = null,
        lyricsSample: String? = null
    ) {
        val foundScript = GenreLogic.scriptOf(lyricsSample)
        if (trackKey == _state.value.trackKey) {
            // Same song, but its lyrics just arrived in Japanese/Korean/Chinese: look again with that hint.
            if (foundScript != null && lyricsScript == null && trackKey != null && !title.isNullOrBlank()) {
                lyricsScript = foundScript
                job?.cancel()
                load(trackKey, title, artist)
            }
            return
        }
        lastGenre = genre
        lyricsScript = foundScript
        job?.cancel()
        if (trackKey == null || title.isNullOrBlank()) {
            _state.value = WikipediaUiState()
            return
        }
        load(trackKey, title, artist)
    }

    /** Re-run the lookup for the current track (used by the Retry button). */
    fun retry() {
        val s = _state.value
        val key = s.trackKey ?: return
        val title = s.songTitle ?: return
        job?.cancel()
        load(key, title, s.artistName)
    }

    private fun load(key: String, title: String, artist: String?) {
        val hasArtist = !WikiMatcher.isPlaceholderArtist(artist)
        _state.value = WikipediaUiState(
            trackKey = key,
            artistName = artist,
            songTitle = title,
            artist = if (hasArtist) WikiLoad.Loading else WikiLoad.Missing(null),
            song = WikiLoad.Loading
        )
        job = viewModelScope.launch {
            // Skipping through a playlist cancels this before any request is sent.
            delay(350)
            val hints = hintsFor(title, artist)
            if (hasArtist) launch {
                val r = repository.lookupArtist(artist, hints)
                _state.update { if (it.trackKey == key) it.copy(artist = r.toLoad(artist.orEmpty())) else it }
            }
            launch {
                val r = repository.lookupSong(title, artist, hints)
                val q = WikiMatcher.cleanSongTitle(title) + if (hasArtist) " $artist" else ""
                _state.update { if (it.trackKey == key) it.copy(song = r.toLoad(q)) else it }
            }
        }
    }

    /**
     * Genre (from the file's tag or the automatic lookup), the artist's country, and the writing system
     * of the title, artist name or lyrics. These pick the right article when several share a name.
     */
    private suspend fun hintsFor(title: String, artist: String?): WikiHints {
        val info = runCatching { genreRepository.artistInfo(artist) }.getOrNull()
        val script = GenreLogic.scriptOf(title) ?: GenreLogic.scriptOf(artist) ?: lyricsScript
        return GenreLogic.wikiHints(lastGenre ?: info?.genre, info?.country, script)
    }

    private fun WikiLookup.toLoad(searchQuery: String): WikiLoad = when (this) {
        is WikiLookup.Found -> WikiLoad.Ready(summary)
        WikiLookup.NotFound -> WikiLoad.Missing(repository.searchUrl(searchQuery))
        is WikiLookup.Failed -> WikiLoad.Error(message)
    }
}
