package com.windstrom5.rebit.presentation.viewmodel

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.windstrom5.rebit.data.genre.GenreRepository
import com.windstrom5.rebit.data.settings.SettingsRepository
import com.windstrom5.rebit.domain.model.AudioTrackModel
import com.windstrom5.rebit.domain.model.WatchedFolder
import com.windstrom5.rebit.domain.usecase.AddWatchedFolderUseCase
import com.windstrom5.rebit.domain.usecase.ObserveDacProfilesUseCase
import com.windstrom5.rebit.domain.usecase.ObserveLibraryUseCase
import com.windstrom5.rebit.domain.usecase.ObserveWatchedFoldersUseCase
import com.windstrom5.rebit.domain.usecase.RemoveWatchedFolderUseCase
import com.windstrom5.rebit.domain.usecase.RescanLibraryUseCase
import com.windstrom5.rebit.domain.usecase.SetQueueUseCase
import com.windstrom5.rebit.domain.usecase.StartPlaybackUseCase
import com.windstrom5.rebit.domain.model.EngineType
import com.windstrom5.rebit.usb.UsbDacManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class LibraryTab {
    TRACKS, ALBUMS, ARTISTS, FOLDERS, HIRES_DSD
}

enum class LibrarySortOption {
    TITLE, ARTIST, SAMPLE_RATE, BIT_DEPTH, FORMAT, DATE_ADDED, SMART_SHUFFLE
}

enum class SortDirection {
    ASCENDING, DESCENDING
}

data class LibraryFilterState(
    val query: String = "",
    val sortBy: LibrarySortOption = LibrarySortOption.TITLE,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val activeTab: LibraryTab = LibraryTab.TRACKS,
    val isGridView: Boolean = false
)

data class ScanStatus(
    val isScanning: Boolean = false,
    val lastScanCount: Int? = null,
    val nowPlayingTrackId: Long? = null,
    val error: String? = null,
    val progress: com.windstrom5.rebit.domain.model.ScanProgress = com.windstrom5.rebit.domain.model.ScanProgress()
)

data class LibraryUiState(
    val tracks: List<AudioTrackModel> = emptyList(),
    val scanProgress: com.windstrom5.rebit.domain.model.ScanProgress = com.windstrom5.rebit.domain.model.ScanProgress(),
    val isScanning: Boolean = false,
    val lastScanCount: Int? = null,
    val nowPlayingTrackId: Long? = null,
    val watchedFolders: List<WatchedFolder> = emptyList(),
    val searchQuery: String = "",
    val sortBy: LibrarySortOption = LibrarySortOption.TITLE,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val activeTab: LibraryTab = LibraryTab.TRACKS,
    val isGridView: Boolean = false,
    val selectedTrackForSpecs: AudioTrackModel? = null,
    val selectedTrackForMenu: AudioTrackModel? = null,
    val error: String? = null,
    /** Every genre in the library with its track count, most common first. */
    val genres: List<Pair<String, Int>> = emptyList(),
    val selectedGenre: String? = null,
    val genreProgress: GenreRepository.Progress = GenreRepository.Progress()
)

private data class LibraryExtras(
    val specs: AudioTrackModel?,
    val menu: AudioTrackModel?,
    val genres: List<Pair<String, Int>>,
    val selectedGenre: String?,
    val genreProgress: GenreRepository.Progress
)

class LibraryViewModel(
    observeLibraryUseCase: ObserveLibraryUseCase,
    private val rescanLibraryUseCase: RescanLibraryUseCase,
    private val startPlaybackUseCase: StartPlaybackUseCase,
    observeDacProfilesUseCase: ObserveDacProfilesUseCase,
    private val settingsRepository: SettingsRepository,
    private val addWatchedFolderUseCase: AddWatchedFolderUseCase,
    private val removeWatchedFolderUseCase: RemoveWatchedFolderUseCase,
    observeWatchedFoldersUseCase: ObserveWatchedFoldersUseCase,
    private val usbDacManager: UsbDacManager,
    private val setQueueUseCase: SetQueueUseCase,
    private val genreRepository: GenreRepository
) : ViewModel() {

    private val tracks: StateFlow<List<AudioTrackModel>> = observeLibraryUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val dacProfiles = observeDacProfilesUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val watchedFolders = observeWatchedFoldersUseCase()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _nowPlayingTrackId = MutableStateFlow<Long?>(null)
    private val _searchQuery = MutableStateFlow("")
    private val _sortBy = MutableStateFlow(LibrarySortOption.TITLE)
    private val _sortDirection = MutableStateFlow(SortDirection.ASCENDING)
    private val _error = MutableStateFlow<String?>(null)
    private val _activeTab = MutableStateFlow(LibraryTab.TRACKS)
    private val _isGridView = MutableStateFlow(false)
    private val _selectedTrackForSpecs = MutableStateFlow<AudioTrackModel?>(null)
    private val _selectedTrackForMenu = MutableStateFlow<AudioTrackModel?>(null)
    private val _selectedGenre = MutableStateFlow<String?>(null)

    init {
        rescan()
        // Once a scan has read every tag, look up genres for the tracks whose files have none.
        viewModelScope.launch {
            rescanLibraryUseCase.progress.collect { prog ->
                if (prog.phase == com.windstrom5.rebit.domain.model.ScanPhase.DONE) genreRepository.start()
            }
        }
    }

    // Combine UI configurations
    private val filterState = combine(_searchQuery, _sortBy, _sortDirection, _activeTab, _isGridView) { query, sort, direction, tab, grid ->
        LibraryFilterState(query, sort, direction, tab, grid)
    }

    // Combine scanning and playing states
    private val scanStatus = combine(rescanLibraryUseCase.progress, _nowPlayingTrackId, _error) { prog, nowPlayingId, err ->
        ScanStatus(
            isScanning = prog.isRunning,
            lastScanCount = prog.libraryCount.takeIf { prog.phase == com.windstrom5.rebit.domain.model.ScanPhase.DONE },
            nowPlayingTrackId = nowPlayingId,
            error = err ?: prog.error?.takeIf { prog.phase == com.windstrom5.rebit.domain.model.ScanPhase.FAILED },
            progress = prog
        )
    }

    // Combine track list and search/sorting/tab filters
    private val filteredAndSortedTracks = combine(tracks, filterState, _selectedGenre) { allTracks, filter, genre ->
        val trackList = if (genre == null) allTracks else allTracks.filter { it.genre == genre }
        val tabFiltered = when (filter.activeTab) {
            LibraryTab.TRACKS -> trackList
            LibraryTab.ALBUMS -> trackList
            LibraryTab.ARTISTS -> trackList
            LibraryTab.FOLDERS -> trackList
            LibraryTab.HIRES_DSD -> trackList.filter { it.pcm.sampleRateHz >= 96000 || it.pcm.bitDepth >= 24 }
        }

        val filtered = tabFiltered.filter {
            it.title.contains(filter.query, ignoreCase = true) ||
                (it.artist?.contains(filter.query, ignoreCase = true) ?: false)
        }
        val sorted = when (filter.sortBy) {
            LibrarySortOption.TITLE -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
            LibrarySortOption.ARTIST -> filtered.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.artist ?: "" })
            LibrarySortOption.SAMPLE_RATE -> filtered.sortedBy { it.pcm.sampleRateHz }
            LibrarySortOption.BIT_DEPTH -> filtered.sortedBy { it.pcm.bitDepth }
            LibrarySortOption.FORMAT -> filtered.sortedBy { it.format.name }
            LibrarySortOption.DATE_ADDED -> filtered.sortedBy { it.dateAddedEpochMs }
            LibrarySortOption.SMART_SHUFFLE -> {
                val grouped = filtered.groupBy { it.pcm.sampleRateHz }
                val rates = grouped.keys.sortedDescending()
                val resultList = mutableListOf<AudioTrackModel>()
                val rng = java.util.Random(1337)
                val shuffledGroups = grouped.mapValues { (_, list) -> list.shuffled(rng) }
                val maxGroupSize = shuffledGroups.values.maxOfOrNull { it.size } ?: 0
                for (step in 0 until maxGroupSize) {
                    rates.forEach { rate ->
                        val listForRate = shuffledGroups[rate] ?: emptyList()
                        if (step < listForRate.size) {
                            resultList.add(listForRate[step])
                        }
                    }
                }
                val (highRes, normalRes) = resultList.partition { it.pcm.sampleRateHz >= 96000 || it.pcm.bitDepth >= 24 }
                val finalShuffled = mutableListOf<AudioTrackModel>()
                var hiIdx = 0
                var loIdx = 0
                while (hiIdx < highRes.size || loIdx < normalRes.size) {
                    if (hiIdx < highRes.size) finalShuffled.add(highRes[hiIdx++])
                    if (hiIdx < highRes.size) finalShuffled.add(highRes[hiIdx++])
                    if (loIdx < normalRes.size) finalShuffled.add(normalRes[loIdx++])
                }
                finalShuffled
            }
        }
        if (filter.sortDirection == SortDirection.DESCENDING && filter.sortBy != LibrarySortOption.SMART_SHUFFLE) sorted.reversed() else sorted
    }

    private val genreOptions = tracks.map { list ->
        list.mapNotNull { it.genre }
            .groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }
    }

    private val selectionState = combine(
        _selectedTrackForSpecs, _selectedTrackForMenu, genreOptions, _selectedGenre, genreRepository.progress
    ) { specs, menu, genres, selectedGenre, progress ->
        LibraryExtras(specs, menu, genres, selectedGenre, progress)
    }

    // Final UI state mapping
    val uiState: StateFlow<LibraryUiState> = combine(
        filteredAndSortedTracks,
        watchedFolders,
        filterState,
        scanStatus,
        selectionState
    ) { tracks, folders, filter, status, selection ->
        LibraryUiState(
            tracks = tracks,
            isScanning = status.isScanning,
            scanProgress = status.progress,
            lastScanCount = status.lastScanCount,
            nowPlayingTrackId = status.nowPlayingTrackId,
            watchedFolders = folders,
            searchQuery = filter.query,
            sortBy = filter.sortBy,
            sortDirection = filter.sortDirection,
            activeTab = filter.activeTab,
            isGridView = filter.isGridView,
            selectedTrackForSpecs = selection.specs,
            selectedTrackForMenu = selection.menu,
            error = status.error,
            genres = selection.genres,
            selectedGenre = selection.selectedGenre,
            genreProgress = selection.genreProgress
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), LibraryUiState())

    fun setActiveTab(tab: LibraryTab) {
        _activeTab.value = tab
    }

    /** Null shows every genre. Tapping the selected genre again clears it. */
    fun selectGenre(genre: String?) {
        _selectedGenre.value = if (genre == _selectedGenre.value) null else genre
    }

    /** Looks up genres for tracks that have none (also runs automatically after each scan). */
    fun detectGenres() = genreRepository.start()

    fun toggleGridView() {
        _isGridView.value = !_isGridView.value
    }

    fun setSelectedTrackForSpecs(track: AudioTrackModel?) {
        _selectedTrackForSpecs.value = track
    }

    fun setSelectedTrackForMenu(track: AudioTrackModel?) {
        _selectedTrackForMenu.value = track
    }

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun setSortBy(option: LibrarySortOption) {
        _sortBy.value = option
    }

    fun toggleSortDirection() {
        _sortDirection.value = if (_sortDirection.value == SortDirection.ASCENDING) {
            SortDirection.DESCENDING
        } else {
            SortDirection.ASCENDING
        }
    }

    /** Queues a background (incremental) scan. Progress arrives through [LibraryUiState.scanProgress]. */
    fun rescan() {
        _error.value = null
        viewModelScope.launch {
            try {
                rescanLibraryUseCase()
            } catch (t: Throwable) {
                _error.value = t.message ?: "Couldn't start the scan."
            }
        }
    }

    fun addFolder(uri: Uri, displayName: String) {
        viewModelScope.launch {
            _error.value = null
            try {
                addWatchedFolderUseCase(uri, displayName)
            } catch (t: Throwable) {
                _error.value = t.message ?: "Failed to add folder."
            }
        }
    }

    fun removeFolder(id: Long) {
        viewModelScope.launch {
            _error.value = null
            try {
                removeWatchedFolderUseCase(id)
            } catch (t: Throwable) {
                _error.value = t.message ?: "Failed to remove folder."
            }
        }
    }

    fun playTrack(track: AudioTrackModel) {
        viewModelScope.launch {
            _error.value = null
            try {
                val connectedDevice = usbDacManager.currentDevice
                val dac = if (connectedDevice != null) dacProfiles.value.firstOrNull() else null
                val engine = if (dac == null || connectedDevice == null) EngineType.ANDROID_AUDIOTRACK else settingsRepository.current.defaultEngine
                setQueueUseCase(uiState.value.tracks, track)
                startPlaybackUseCase(track, engine, dac)
                _nowPlayingTrackId.value = track.id
            } catch (t: Throwable) {
                _error.value = t.message ?: "Playback failed to start."
            }
        }
    }

    fun playTrackDirectUsb(track: AudioTrackModel) {
        viewModelScope.launch {
            _error.value = null
            try {
                val connectedDevice = usbDacManager.currentDevice
                val dac = if (connectedDevice != null) dacProfiles.value.firstOrNull() else null
                setQueueUseCase(uiState.value.tracks, track)
                startPlaybackUseCase(track, EngineType.CUSTOM_USB_DIRECT, dac)
                _nowPlayingTrackId.value = track.id
            } catch (t: Throwable) {
                _error.value = t.message ?: "USB Direct Playback failed to start."
            }
        }
    }

    fun playTrackAudioTrack(track: AudioTrackModel) {
        viewModelScope.launch {
            _error.value = null
            try {
                val connectedDevice = usbDacManager.currentDevice
                val dac = if (connectedDevice != null) dacProfiles.value.firstOrNull() else null
                setQueueUseCase(uiState.value.tracks, track)
                startPlaybackUseCase(track, EngineType.ANDROID_AUDIOTRACK, dac)
                _nowPlayingTrackId.value = track.id
            } catch (t: Throwable) {
                _error.value = t.message ?: "AudioTrack Playback failed to start."
            }
        }
    }
}
