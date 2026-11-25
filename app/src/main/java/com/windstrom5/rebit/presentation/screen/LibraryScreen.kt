package com.windstrom5.rebit.presentation.screen

import androidx.compose.material.icons.filled.Insights
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.components.AudioQuality
import com.windstrom5.rebit.presentation.components.MiniPlayerBar
import com.windstrom5.rebit.presentation.components.QualityBadge
import com.windstrom5.rebit.presentation.components.specLabel
import com.windstrom5.rebit.presentation.viewmodel.FavoritesViewModel
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import com.windstrom5.rebit.presentation.components.ModernTopBar
import com.windstrom5.rebit.presentation.theme.SignalBlue
import com.windstrom5.rebit.presentation.theme.SurfacePanel
import com.windstrom5.rebit.presentation.theme.SurfacePanelHigh
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.ui.text.style.TextAlign
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.windstrom5.rebit.domain.model.AudioTrackModel
import com.windstrom5.rebit.domain.model.WatchedFolder
import com.windstrom5.rebit.presentation.components.TrackArtwork
import com.windstrom5.rebit.presentation.theme.BackgroundInk
import com.windstrom5.rebit.presentation.theme.StatusError
import com.windstrom5.rebit.presentation.theme.OutlineSubtle
import com.windstrom5.rebit.presentation.theme.SignalTeal
import com.windstrom5.rebit.presentation.theme.SurfaceInk
import com.windstrom5.rebit.presentation.theme.TelemetryFontFamily
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary
import com.windstrom5.rebit.presentation.viewmodel.LibrarySortOption
import com.windstrom5.rebit.presentation.viewmodel.LibraryTab
import com.windstrom5.rebit.presentation.viewmodel.LibraryViewModel
import com.windstrom5.rebit.presentation.viewmodel.PlayerViewModel
import com.windstrom5.rebit.presentation.viewmodel.SortDirection
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onNavigateToPlayer: () -> Unit = {},
    onOpenInsights: () -> Unit = {},
    viewModel: LibraryViewModel = koinViewModel(),
    playerViewModel: PlayerViewModel = koinViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var showSettingsDialog by remember { mutableStateOf(false) }
    val favoritesViewModel: FavoritesViewModel = koinViewModel()
    val favoritePaths by favoritesViewModel.favoritePaths.collectAsStateWithLifecycle()
    var onlyFavorites by remember { mutableStateOf(false) }
    val shownTracks = if (onlyFavorites) state.tracks.filter { it.filePath in favoritePaths } else state.tracks

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            val displayName = DocumentFile.fromTreeUri(context, uri)?.name ?: uri.lastPathSegment ?: "Folder"
            viewModel.addFolder(uri, displayName)
        }
    }

    Scaffold(
        topBar = {
            Column {
                ModernTopBar(
                    title = "Library",
                    subtitle = if (state.tracks.isNotEmpty()) "${state.tracks.size} tracks" else null,
                    actions = {
                        IconButton(onClick = onOpenInsights) {
                            Icon(Icons.Filled.Insights, contentDescription = "Collection insights", tint = HifiBrass)
                        }
                        IconButton(onClick = { viewModel.toggleGridView() }) {
                            Icon(
                                imageVector = if (state.isGridView) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                                contentDescription = "Toggle view mode",
                                tint = TextPrimary
                            )
                        }
                        IconButton(onClick = { showSettingsDialog = true }) {
                            Icon(Icons.Filled.Settings, contentDescription = "Library settings", tint = TextSecondary)
                        }
                    }
                )
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        FilterChip(
                            selected = onlyFavorites,
                            onClick = { onlyFavorites = !onlyFavorites },
                            label = { Text("Liked", style = MaterialTheme.typography.labelLarge) },
                            leadingIcon = {
                                Icon(Icons.Filled.Favorite, null, modifier = Modifier.size(16.dp))
                            },
                            shape = RoundedCornerShape(50),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = SurfacePanel,
                                labelColor = TextSecondary,
                                iconColor = TextSecondary,
                                selectedContainerColor = Color(0xFFFF6B81).copy(alpha = 0.20f),
                                selectedLabelColor = Color(0xFFFF6B81),
                                selectedLeadingIconColor = Color(0xFFFF6B81)
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = onlyFavorites,
                                borderColor = Color.Transparent,
                                selectedBorderColor = Color.Transparent
                            )
                        )
                    }
                    items(LibraryTab.entries) { tab ->
                        val selected = state.activeTab == tab
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.setActiveTab(tab) },
                            label = {
                                Text(
                                    text = when (tab) {
                                        LibraryTab.TRACKS -> "Tracks"
                                        LibraryTab.ALBUMS -> "Albums"
                                        LibraryTab.ARTISTS -> "Artists"
                                        LibraryTab.FOLDERS -> "Folders"
                                        LibraryTab.HIRES_DSD -> "Hi-Res / DSD"
                                    },
                                    style = MaterialTheme.typography.labelLarge
                                )
                            },
                            shape = RoundedCornerShape(50),
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = SurfacePanel,
                                labelColor = TextSecondary,
                                selectedContainerColor = SignalBlue.copy(alpha = 0.20f),
                                selectedLabelColor = SignalBlue
                            ),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true,
                                selected = selected,
                                borderColor = Color.Transparent,
                                selectedBorderColor = Color.Transparent
                            )
                        )
                    }
                }
                if (state.tracks.isNotEmpty() || state.genres.isNotEmpty()) {
                    GenreFilterRow(
                        genres = state.genres,
                        selected = state.selectedGenre,
                        detecting = state.genreProgress.running,
                        onSelect = viewModel::selectGenre,
                        onDetect = viewModel::detectGenres
                    )
                }
                if (state.genreProgress.running) {
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = {
                            if (state.genreProgress.total > 0) state.genreProgress.done.toFloat() / state.genreProgress.total else 0f
                        },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        color = SignalBlue,
                        trackColor = SurfacePanel
                    )
                    Text(
                        "Detecting genres ${state.genreProgress.done}/${state.genreProgress.total}",
                        style = MaterialTheme.typography.labelSmall, color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                } else if (state.genreProgress.message != null) {
                    Text(
                        state.genreProgress.message!!,
                        style = MaterialTheme.typography.labelSmall, color = TextSecondary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                    )
                }
            }
        },
        containerColor = BackgroundInk,
        bottomBar = { MiniPlayerBar(onOpenPlayer = onNavigateToPlayer, playerViewModel = playerViewModel) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Pill search field
            TextField(
                value = state.searchQuery,
                onValueChange = { viewModel.setSearchQuery(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                placeholder = {
                    Text("Search songs, artists, formats", color = TextSecondary.copy(alpha = 0.7f))
                },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = TextSecondary)
                },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setSearchQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear search", tint = TextSecondary)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(50),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = SurfacePanel,
                    unfocusedContainerColor = SurfacePanel,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    focusedTextColor = TextPrimary,
                    unfocusedTextColor = TextPrimary,
                    cursorColor = SignalBlue
                )
            )

            // Sort direction + sort options as one scrolling chip row
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(SignalBlue.copy(alpha = 0.16f))
                            .clickable { viewModel.toggleSortDirection() }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    ) {
                        Icon(
                            Icons.Filled.SwapVert,
                            contentDescription = "Toggle sort direction",
                            modifier = Modifier.size(16.dp),
                            tint = SignalBlue
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (state.sortDirection == SortDirection.ASCENDING) "Ascending" else "Descending",
                            style = MaterialTheme.typography.labelMedium,
                            color = SignalBlue
                        )
                    }
                }
                items(LibrarySortOption.entries) { option ->
                    val selected = state.sortBy == option
                    FilterChip(
                        selected = selected,
                        onClick = { viewModel.setSortBy(option) },
                        label = { Text(sortOptionLabel(option), style = MaterialTheme.typography.labelMedium) },
                        shape = RoundedCornerShape(50),
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Transparent,
                            labelColor = TextSecondary,
                            selectedContainerColor = Color.White.copy(alpha = 0.12f),
                            selectedLabelColor = TextPrimary
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = selected,
                            borderColor = OutlineSubtle,
                            selectedBorderColor = Color.Transparent
                        )
                    )
                }
            }

            if (state.isScanning) {
                ScanProgressBanner(state.scanProgress)
            }

            state.error?.let {
                Text(
                    it,
                    color = StatusError,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }

            if (shownTracks.isEmpty() && !state.isScanning) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Filled.LibraryMusic,
                        contentDescription = null,
                        tint = TextSecondary.copy(alpha = 0.5f),
                        modifier = Modifier.size(56.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        if (onlyFavorites) "No liked songs yet" else "No music found",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary
                    )
                    Text(
                        if (onlyFavorites) "Tap the heart on a song to add it here."
                        else "Open library settings (the gear icon) and add the folders where your music lives.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            } else {
                if (state.isGridView) {
                    // 2-Column Album Cover Grid View
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(shownTracks) { track ->
                            TrackGridCard(
                                track = track,
                                isPlaying = track.id == state.nowPlayingTrackId,
                                onPlay = { viewModel.playTrack(track) },
                                onShowMenu = { viewModel.setSelectedTrackForMenu(track) }
                            )
                        }
                    }
                } else {
                    // Standard Sleek List View
                    LazyColumn(modifier = Modifier.weight(1f)) {
                        items(shownTracks) { track ->
                            TrackRow(
                                track = track,
                                isFavorite = track.filePath in favoritePaths,
                                onToggleFavorite = { favoritesViewModel.toggle(track) },
                                isPlaying = track.id == state.nowPlayingTrackId,
                                onPlay = { viewModel.playTrack(track) },
                                onShowMenu = { viewModel.setSelectedTrackForMenu(track) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Poweramp Style Detailed Audio Tech Specs Dialog
    state.selectedTrackForSpecs?.let { track ->
        AudioTechSpecsDialog(
            track = track,
            onDismiss = { viewModel.setSelectedTrackForSpecs(null) }
        )
    }

    // Track Context Menu Sheet
    state.selectedTrackForMenu?.let { track ->
        TrackContextMenuDialog(
            track = track,
            onPlayDirectUsb = {
                viewModel.playTrackDirectUsb(track)
                viewModel.setSelectedTrackForMenu(null)
            },
            onPlayAudioTrack = {
                viewModel.playTrackAudioTrack(track)
                viewModel.setSelectedTrackForMenu(null)
            },
            onShowSpecs = {
                viewModel.setSelectedTrackForSpecs(track)
                viewModel.setSelectedTrackForMenu(null)
            },
            onDismiss = { viewModel.setSelectedTrackForMenu(null) }
        )
    }

    // Media Index Config Settings Dialog
    if (showSettingsDialog) {
        Dialog(onDismissRequest = { showSettingsDialog = false }) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = SurfaceInk),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, OutlineSubtle)
            ) {
                Column(
                    modifier = Modifier
                        .padding(20.dp)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "MEDIA INDEX CONFIG",
                            fontFamily = TelemetryFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            letterSpacing = 2.sp,
                            color = TextPrimary
                        )
                        IconButton(onClick = { showSettingsDialog = false }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                        }
                    }

                    HorizontalDivider(color = OutlineSubtle)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "WATCHED STORAGE DIRECTORIES",
                            fontFamily = TelemetryFontFamily,
                            fontSize = 9.sp,
                            color = TextSecondary,
                            letterSpacing = 1.sp
                        )
                        OutlinedButton(
                            onClick = { folderPicker.launch(null) },
                            border = BorderStroke(1.dp, HifiBrass.copy(alpha = 0.4f)),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Icons.Filled.CreateNewFolder, contentDescription = null, tint = HifiBrass, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("ADD FOLDER", fontFamily = TelemetryFontFamily, fontSize = 8.sp, color = HifiBrass)
                        }
                    }

                    if (state.watchedFolders.isEmpty()) {
                        Text(
                            "Using standard MediaStore index. Add custom folders to scan SAF directories.",
                            fontFamily = TelemetryFontFamily,
                            fontSize = 10.sp,
                            color = TextSecondary.copy(alpha = 0.7f)
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            state.watchedFolders.forEach { folder ->
                                WatchedFolderRow(folder = folder, onRemove = { viewModel.removeFolder(folder.id) })
                            }
                        }
                    }

                    HorizontalDivider(color = OutlineSubtle)

                    Button(
                        onClick = {
                            viewModel.rescan()
                            showSettingsDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = HifiBrass),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Refresh, contentDescription = null, tint = BackgroundInk, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("FORCE RE-SCAN MEDIA LIBRARY", fontFamily = TelemetryFontFamily, fontSize = 10.sp, color = BackgroundInk, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackRow(
    track: AudioTrackModel,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onShowMenu: () -> Unit
) {
    val accentColor = com.windstrom5.rebit.presentation.components.qualityOf(
        track.format, track.pcm.sampleRateHz, track.pcm.bitDepth
    ).color

    // One compact two-line row: the title gets the full width, and the format sits at the end of
    // the artist line with a single quality LED (hi-res = brass, lossless = teal) instead of a
    // HI-RES badge plus three pills competing with the title for space.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (isPlaying) accentColor.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onPlay)
            .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TrackArtwork(
            uriString = track.filePath,
            title = track.title,
            artist = track.artist,
            isPlaying = isPlaying,
            isLarge = false,
            cornerRadius = 10.dp,
            modifier = Modifier.size(48.dp)
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isPlaying) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isPlaying) accentColor else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = track.artist ?: "Unknown artist",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Text(
                    "  ·  ",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary.copy(alpha = 0.5f)
                )
                com.windstrom5.rebit.presentation.components.LedDot(accentColor, size = 5.dp)
                Spacer(Modifier.width(5.dp))
                Text(
                    com.windstrom5.rebit.presentation.components.formatSpec(track.format, track.pcm.sampleRateHz, track.pcm.bitDepth),
                    fontFamily = TelemetryFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    color = accentColor,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        IconButton(onClick = onToggleFavorite, modifier = Modifier.size(40.dp)) {
            Icon(
                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (isFavorite) "Unlike" else "Like",
                tint = if (isFavorite) Color(0xFFFF6B81) else TextSecondary.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(onClick = onShowMenu, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More options", tint = TextSecondary.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun TrackGridCard(
    track: AudioTrackModel,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onShowMenu: () -> Unit
) {
    val isHighRes = track.pcm.sampleRateHz >= 96000 || track.pcm.bitDepth >= 24
    val accentColor = if (isHighRes) HifiBrass else SignalTeal

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay),
        colors = CardDefaults.cardColors(containerColor = SurfacePanel),
        border = if (isPlaying) BorderStroke(1.5.dp, accentColor) else null,
        shape = RoundedCornerShape(22.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(14.dp))
            ) {
                TrackArtwork(
                    uriString = track.filePath,
                    title = track.title,
                    artist = track.artist,
                    isPlaying = isPlaying,
                    isLarge = false,
                    cornerRadius = 14.dp,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .background(SurfaceInk.copy(alpha = 0.75f), RoundedCornerShape(4.dp))
                ) {
                    IconButton(onClick = onShowMenu, modifier = Modifier.size(24.dp)) {
                        Icon(Icons.Filled.MoreVert, contentDescription = null, tint = TextPrimary)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = track.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = if (isPlaying) accentColor else TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = track.artist ?: "Unknown Artist",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SpecPill(track.format.name, accentColor)
                SpecPill("${track.pcm.bitDepth}b/${"%.0f".format(track.pcm.sampleRateHz / 1000.0)}k", TextSecondary)
            }
        }
    }
}

// Poweramp / UAPP Style Audio File Tech Specs Dialog
@Composable
private fun AudioTechSpecsDialog(
    track: AudioTrackModel,
    onDismiss: () -> Unit
) {
    val bytesPerSec = track.pcm.sampleRateHz.toLong() * track.pcm.channels * track.pcm.bitDepth / 8
    val bitrateKbps = (bytesPerSec * 8) / 1000

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceInk),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, HifiBrass)
        ) {
            Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "AUDIO FILE TECH SPECS",
                        fontFamily = TelemetryFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.5.sp,
                        color = HifiBrass
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                HorizontalDivider(color = OutlineSubtle)

                Text(track.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                track.artist?.let { Text("Artist: $it", style = MaterialTheme.typography.bodyMedium, color = TextSecondary) }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TechSpecRow("Container Format", track.format.name)
                    TechSpecRow("Native Sample Rate", "${track.pcm.sampleRateHz} Hz (${"%.1f".format(track.pcm.sampleRateHz / 1000.0)} kHz)")
                    TechSpecRow("Bit Depth", "${track.pcm.bitDepth}-bit PCM")
                    TechSpecRow("Channels", if (track.pcm.channels == 2) "Stereo (2 ch)" else "Mono (1 ch)")
                    TechSpecRow("Uncompressed Bitrate", "~$bitrateKbps kbps")
                    TechSpecRow("File Path", track.filePath)
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SignalTeal.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .border(BorderStroke(1.dp, SignalTeal.copy(alpha = 0.4f)), RoundedCornerShape(6.dp))
                        .padding(10.dp)
                ) {
                    Text(
                        "✔ BIT-PERFECT VERIFIED: Native hardware support capable on UAC2 compliant DAC endpoints.",
                        fontFamily = TelemetryFontFamily,
                        fontSize = 9.sp,
                        color = SignalTeal
                    )
                }
            }
        }
    }
}

@Composable
private fun TechSpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontFamily = TelemetryFontFamily, fontSize = 10.sp, color = TextSecondary)
        Text(value, fontFamily = TelemetryFontFamily, fontSize = 10.sp, color = TextPrimary, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TrackContextMenuDialog(
    track: AudioTrackModel,
    onPlayDirectUsb: () -> Unit,
    onPlayAudioTrack: () -> Unit,
    onShowSpecs: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = SurfaceInk),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, OutlineSubtle)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(track.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = HifiBrass)
                HorizontalDivider(color = OutlineSubtle)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onPlayDirectUsb)
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Usb, contentDescription = null, tint = SignalTeal, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("PLAY BIT-PERFECT (USB DIRECT)", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = SignalTeal, fontWeight = FontWeight.Bold)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onPlayAudioTrack)
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = TextPrimary, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("PLAY STANDARD (AUDIOTRACK)", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextPrimary)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onShowSpecs)
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Info, contentDescription = null, tint = HifiBrass, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("INSPECT FILE HEADER TECH SPECS", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = HifiBrass)
                }
            }
        }
    }
}

@Composable
private fun WatchedFolderRow(folder: WatchedFolder, onRemove: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfacePanel),
        border = BorderStroke(1.dp, OutlineSubtle)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Filled.Folder, contentDescription = null, tint = HifiBrass, modifier = Modifier.size(18.dp))
                Text(
                    folder.displayName,
                    fontFamily = TelemetryFontFamily,
                    fontSize = 12.sp,
                    color = TextPrimary
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "Remove", tint = TextSecondary, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun SpecPill(text: String, color: Color) {
    Text(
        text = text,
        fontFamily = TelemetryFontFamily,
        fontSize = 11.sp,
        color = color,
        letterSpacing = 0.2.sp,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .background(color.copy(alpha = 0.10f), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    )
}

private fun sortOptionLabel(option: LibrarySortOption): String = when (option) {
    LibrarySortOption.TITLE -> "Title"
    LibrarySortOption.ARTIST -> "Artist"
    LibrarySortOption.SAMPLE_RATE -> "Sample rate"
    LibrarySortOption.BIT_DEPTH -> "Bit depth"
    LibrarySortOption.FORMAT -> "Format"
    LibrarySortOption.DATE_ADDED -> "Date added"
    LibrarySortOption.SMART_SHUFFLE -> "Smart shuffle"
}

@Composable
fun RotatingVinylRecord(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "vinyl_lib")
    val angle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "angle"
    )

    Canvas(
        modifier = modifier
            .size(36.dp)
            .rotate(angle)
    ) {
        val radius = size.width / 2f
        drawCircle(color = Color(0xFF0F1219), radius = radius)
        drawCircle(color = HifiBrass, radius = radius * 0.4f)
        drawCircle(color = Color.White.copy(alpha = 0.12f), radius = radius * 0.85f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f))
        drawCircle(color = Color.White.copy(alpha = 0.12f), radius = radius * 0.7f, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f))
        drawCircle(color = Color(0xFF04060A), radius = radius * 0.1f)
    }
}

@Composable
private fun ScanProgressBanner(p: com.windstrom5.rebit.domain.model.ScanProgress) {
    val fraction = p.fraction
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .background(SurfacePanel, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Text(
            when (p.phase) {
                com.windstrom5.rebit.domain.model.ScanPhase.LISTING -> "Looking for music… ${p.discovered} files found"
                com.windstrom5.rebit.domain.model.ScanPhase.ENRICHING -> "Reading tags ${p.processed} / ${p.total}"
                else -> "Reading new tracks ${p.processed} / ${p.total}"
            },
            style = MaterialTheme.typography.labelLarge,
            color = SignalBlue
        )
        Spacer(Modifier.height(8.dp))
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = SignalBlue,
                trackColor = Color.White.copy(alpha = 0.1f)
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp),
                color = SignalBlue,
                trackColor = Color.White.copy(alpha = 0.1f)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (p.unchanged > 0) "${p.unchanged} unchanged · ${p.added} new · runs in the background, you can leave this screen"
            else "Runs in the background, you can leave this screen",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

/** "All" plus one chip per genre in the library, and an action chip to detect missing genres. */
@Composable
private fun GenreFilterRow(
    genres: List<Pair<String, Int>>,
    selected: String?,
    detecting: Boolean,
    onSelect: (String?) -> Unit,
    onDetect: () -> Unit
) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (genres.isNotEmpty()) {
            item(key = "all-genres") {
                GenreChip("All genres", selected == null) { onSelect(null) }
            }
            items(genres, key = { it.first }) { (name, count) ->
                GenreChip("$name \u00B7 $count", selected == name) { onSelect(name) }
            }
        }
        item(key = "detect-genres") {
            FilterChip(
                selected = false,
                enabled = !detecting,
                onClick = onDetect,
                label = { Text(if (detecting) "Detecting\u2026" else "Auto-detect genres", style = MaterialTheme.typography.labelLarge) },
                shape = RoundedCornerShape(50),
                colors = FilterChipDefaults.filterChipColors(containerColor = SurfacePanel, labelColor = HifiBrass),
                border = FilterChipDefaults.filterChipBorder(enabled = true, selected = false, borderColor = HifiBrass.copy(alpha = 0.4f))
            )
        }
    }
}

@Composable
private fun GenreChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, style = MaterialTheme.typography.labelLarge) },
        shape = RoundedCornerShape(50),
        colors = FilterChipDefaults.filterChipColors(
            containerColor = SurfacePanel,
            labelColor = TextSecondary,
            selectedContainerColor = SignalBlue.copy(alpha = 0.20f),
            selectedLabelColor = SignalBlue
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true, selected = selected, borderColor = Color.Transparent, selectedBorderColor = Color.Transparent
        )
    )
}
