package com.windstrom5.rebit.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.windstrom5.rebit.data.history.ArtistRankRow
import com.windstrom5.rebit.data.history.ListeningRepository
import com.windstrom5.rebit.data.history.RangeTotals
import com.windstrom5.rebit.data.history.RankRange
import com.windstrom5.rebit.data.history.TrackRankRow
import com.windstrom5.rebit.data.gear.Gear
import com.windstrom5.rebit.data.gear.GearPrefs
import com.windstrom5.rebit.data.news.ArtistFeedRepository
import com.windstrom5.rebit.domain.audiophile.LibraryInsights
import com.windstrom5.rebit.domain.audiophile.ResolutionTier
import com.windstrom5.rebit.data.news.FeedItemEntity
import com.windstrom5.rebit.data.wikipedia.WikiLookup
import com.windstrom5.rebit.data.wikipedia.WikipediaRepository
import com.windstrom5.rebit.domain.model.AudioTrackModel
import com.windstrom5.rebit.domain.usecase.ObserveLibraryUseCase
import com.windstrom5.rebit.domain.usecase.PlayTracksUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A ranked row joined with the live library, so unavailable (moved/deleted) files can be greyed out. */
data class RankedTrack(
    val position: Int,
    val row: TrackRankRow,
    val track: AudioTrackModel?
) {
    val available: Boolean get() = track != null
}

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val listening: ListeningRepository,
    observeLibrary: ObserveLibraryUseCase,
    private val playTracks: PlayTracksUseCase,
    private val wikipedia: WikipediaRepository,
    feedRepository: ArtistFeedRepository,
    private val gearPrefs: GearPrefs
) : ViewModel() {

    private val _range = MutableStateFlow(RankRange.WEEK)
    val range: StateFlow<RankRange> = _range

    private val libraryByPath: StateFlow<Map<String, AudioTrackModel>> = observeLibrary()
        .map { list -> list.associateBy { it.filePath } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val topTracks: StateFlow<List<RankedTrack>> = _range
        .flatMapLatest { listening.topTracks(it, limit = 20) }
        .combine(libraryByPath) { rows, lib ->
            rows.mapIndexed { i, r -> RankedTrack(i + 1, r, lib[r.filePath]) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val topArtists: StateFlow<List<ArtistRankRow>> = _range
        .flatMapLatest { listening.topArtists(it, limit = 12) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totals: StateFlow<RangeTotals> = _range
        .flatMapLatest { listening.totals(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RangeTotals())

    val recent: StateFlow<List<RankedTrack>> = listening.recentlyPlayed(15)
        .combine(libraryByPath) { rows, lib ->
            rows.mapIndexed { i, r -> RankedTrack(i + 1, r, lib[r.filePath]) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Last 14 local days: (epoch-day, listened ms). */
    val activity: StateFlow<List<Pair<Long, Long>>> = listening.activity(14)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val streakDays: StateFlow<Int> = listening.streakDays()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** Liked songs that still exist in the library, newest like first. */
    val favorites: StateFlow<List<AudioTrackModel>> = listening.favorites()
        .combine(libraryByPath) { favs, lib -> favs.mapNotNull { lib[it.filePath] } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── Smart collections ──
    private val playedPaths = listening.playedPaths()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Hi-Res files you own but have never played, highest resolution first. */
    val hiResGems: StateFlow<List<AudioTrackModel>> = libraryByPath
        .combine(playedPaths) { lib, played ->
            val seen = played.toHashSet()
            lib.values
                .filter { it.filePath !in seen }
                .filter {
                    LibraryInsights.tierOf(it.pcm.sampleRateHz, it.pcm.bitDepth).let { t ->
                        t == ResolutionTier.HI_RES || t == ResolutionTier.DSD_RATE
                    }
                }
                .sortedByDescending { it.pcm.sampleRateHz.toLong() * it.pcm.bitDepth }
                .take(12)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Songs you used to play a lot but haven't touched in a while. */
    val rediscover: StateFlow<List<RankedTrack>> = listening.rediscover(12)
        .combine(libraryByPath) { rows, lib -> rows.mapIndexed { i, r -> RankedTrack(i + 1, r, lib[r.filePath]) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // ── My setup ──
    private val _gear = MutableStateFlow(gearPrefs.load())
    val gear: StateFlow<Gear> = _gear
    fun saveGear(g: Gear) { gearPrefs.save(g); _gear.value = gearPrefs.load() }

    fun playOne(track: AudioTrackModel, queue: List<AudioTrackModel>) {
        viewModelScope.launch { playTracks(queue.ifEmpty { listOf(track) }, track) }
    }

    /** Latest artist releases/news for the Home preview, plus the unread count for the bell. */
    val newsPreview: StateFlow<List<FeedItemEntity>> = feedRepository.feed(3)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val newsUnread: StateFlow<Int> = feedRepository.unreadCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    // ── Artist portraits from Wikipedia (name -> thumbnail url, null = none found) ──
    private val _artistImages = MutableStateFlow<Map<String, String?>>(emptyMap())
    val artistImages: StateFlow<Map<String, String?>> = _artistImages

    init {
        viewModelScope.launch {
            topArtists.collect { artists ->
                for (a in artists) {
                    if (_artistImages.value.containsKey(a.artist)) continue
                    val result = wikipedia.lookupArtist(a.artist)
                    // A network failure is not "no image": leave it unset so a later visit retries.
                    if (result !is WikiLookup.Failed) {
                        val url = (result as? WikiLookup.Found)?.summary?.thumbnailUrl
                        _artistImages.update { it + (a.artist to url) }
                    }
                }
            }
        }
    }

    fun setRange(range: RankRange) { _range.value = range }

    fun clearHistory() { viewModelScope.launch { listening.clearHistory() } }

    fun play(item: RankedTrack, queue: List<RankedTrack>) {
        val track = item.track ?: return
        val q = queue.mapNotNull { it.track }
        viewModelScope.launch { playTracks(q.ifEmpty { listOf(track) }, track) }
    }

    fun playAll(items: List<RankedTrack>, shuffle: Boolean) {
        val tracks = items.mapNotNull { it.track }
        if (tracks.isEmpty()) return
        val ordered = if (shuffle) tracks.shuffled() else tracks
        viewModelScope.launch { playTracks(ordered, ordered.first()) }
    }

    fun playTracksList(tracks: List<AudioTrackModel>, shuffle: Boolean) {
        if (tracks.isEmpty()) return
        val ordered = if (shuffle) tracks.shuffled() else tracks
        viewModelScope.launch { playTracks(ordered, ordered.first()) }
    }
}
