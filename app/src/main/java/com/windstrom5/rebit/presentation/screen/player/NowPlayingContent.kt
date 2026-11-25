package com.windstrom5.rebit.presentation.screen.player

import androidx.compose.animation.core.animateDpAsState
import android.content.Context
import android.media.AudioManager
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.windstrom5.rebit.data.wikipedia.WikiLoad
import com.windstrom5.rebit.data.wikipedia.WikipediaUiState
import com.windstrom5.rebit.domain.model.EngineType
import com.windstrom5.rebit.presentation.components.ArtworkPalette
import com.windstrom5.rebit.presentation.components.TrackArtwork
import com.windstrom5.rebit.presentation.theme.BackgroundInk
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.theme.SignalBlue
import com.windstrom5.rebit.presentation.theme.SignalTeal
import com.windstrom5.rebit.presentation.theme.StatusGood
import com.windstrom5.rebit.presentation.theme.StatusWarn
import com.windstrom5.rebit.presentation.theme.TelemetryFontFamily
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary
import com.windstrom5.rebit.presentation.viewmodel.PlayerUiState
import com.windstrom5.rebit.sync.SharedListeningRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Every action the player UI can trigger, bundled so the layout composables stay readable. */
@Stable
internal class NowPlayingActions(
    val onPlayPause: () -> Unit,
    val onNext: () -> Unit,
    val onPrevious: () -> Unit,
    val onShuffle: () -> Unit,
    val onSeek: (Long) -> Unit,
    val onToggleLyrics: () -> Unit,
    val onRetryWiki: () -> Unit,
    val onToggleFavorite: () -> Unit,
    val onOpenQueue: () -> Unit,
    val onOpenAudioInfo: () -> Unit,
    val onOpenAbLoop: () -> Unit,
    val onOpenEq: () -> Unit,
    val onOpenDsp: () -> Unit,
    val onOpenKaraoke: () -> Unit,
    val onOpenAi: () -> Unit,
    val onOpenTools: () -> Unit,
    val onOpenShare: () -> Unit,
    /** (step, maxSteps): sets the DAC's hardware volume while USB Direct is active. */
    val onSetHardwareVolumeStep: (Int, Int) -> Unit = { _, _ -> }
)

private const val PAGE_COVER = 0
private const val PAGE_LYRICS = 1
private const val PAGE_WIKI = 2
private const val PAGE_ANALYZER = 3

/**
 * The whole "Now Playing" screen. Portrait: top bar, hero pager (cover / lyrics / Wikipedia),
 * then title, seek bar, transport, quick tools, signal path and volume. Landscape / wide:
 * hero on the left, controls on the right.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun NowPlayingContent(
    state: PlayerUiState,
    wiki: WikipediaUiState,
    palette: ArtworkPalette,
    isFavorite: Boolean,
    analysis: com.windstrom5.rebit.domain.audiophile.TrackAnalysis?,
    actions: NowPlayingActions,
    lyricsPage: @Composable (Modifier, Color) -> Unit,
    modifier: Modifier = Modifier,
    /** Polled every frame by the live level meter. */
    outputLevels: () -> com.windstrom5.rebit.playback.dsp.LevelMeter.Levels? = { null },
    /** Recent output samples for the analyzer page, and their sample rate. */
    recentSamples: (FloatArray, FloatArray) -> Int = { _, _ -> 0 },
    analyzerSampleRate: () -> Int = { 0 }
) {
    val colors = rememberPlayerColors(palette)
    val scope = rememberCoroutineScope()

    val pagerState = rememberPagerState(
        initialPage = if (state.showLyricsView) PAGE_LYRICS else PAGE_COVER,
        pageCount = { 4 }
    )
    var wikiTab by rememberSaveable { mutableIntStateOf(WIKI_TAB_ARTIST) }

    // Pager <-> ViewModel: the VM's showLyricsView flag stays the source of truth for "lyrics are open".
    LaunchedEffect(pagerState.settledPage) {
        val lyricsOpen = pagerState.settledPage == PAGE_LYRICS
        if (lyricsOpen != state.showLyricsView) actions.onToggleLyrics()
    }
    LaunchedEffect(state.showLyricsView) {
        if (state.showLyricsView && pagerState.currentPage != PAGE_LYRICS) {
            pagerState.animateScrollToPage(PAGE_LYRICS)
        } else if (!state.showLyricsView && pagerState.currentPage == PAGE_LYRICS) {
            pagerState.animateScrollToPage(PAGE_COVER)
        }
    }

    fun goTo(page: Int) = scope.launch { pagerState.animateScrollToPage(page) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundInk)
            .drawBehind {
                drawRect(
                    Brush.verticalGradient(
                        0f to colors.glow.copy(alpha = 0.55f),
                        0.5f to colors.deep.copy(alpha = 0.35f),
                        1f to Color.Transparent
                    )
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(colors.accent.copy(alpha = 0.20f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.22f),
                        radius = size.width * 0.8f
                    ),
                    radius = size.width * 0.8f,
                    center = Offset(size.width / 2f, size.height * 0.22f)
                )
            }
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val wide = maxWidth > maxHeight && maxWidth >= 560.dp

            val hero: @Composable (Modifier) -> Unit = { heroModifier ->
                Column(heroModifier, horizontalAlignment = Alignment.CenterHorizontally) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxWidth().weight(1f, fill = true),
                        pageSpacing = 16.dp
                    ) { page ->
                        when (page) {
                            PAGE_COVER -> CoverPage(state, colors)
                            PAGE_LYRICS -> lyricsPage(Modifier.fillMaxSize(), colors.accent)
                            PAGE_ANALYZER -> AnalyzerPage(
                                active = state.playback.isPlaying && pagerState.currentPage == PAGE_ANALYZER,
                                accent = colors.accent,
                                recentSamples = recentSamples,
                                sampleRateHz = analyzerSampleRate,
                                levels = outputLevels
                            )
                            else -> WikipediaPanel(
                                wiki = wiki,
                                tab = wikiTab,
                                onTabChange = { wikiTab = it },
                                accent = colors.accent,
                                onRetry = actions.onRetryWiki,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    HeroTabs(
                        selected = pagerState.currentPage,
                        accent = colors.accent,
                        onSelect = { goTo(it) }
                    )
                }
            }

            val controls: @Composable (Modifier) -> Unit = { controlsModifier ->
                ControlsSection(
                    state = state,
                    wiki = wiki,
                    colors = colors,
                    actions = actions,
                    isFavorite = isFavorite,
                    analysis = analysis,
                    onOpenWiki = {
                        wikiTab = WIKI_TAB_ARTIST
                        goTo(PAGE_WIKI)
                    },
                    outputLevels = outputLevels,
                    modifier = controlsModifier
                )
            }

            if (wide) {
                Column(Modifier.fillMaxSize()) {
                    TopBar(state, colors, actions)
                    Row(
                        Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(32.dp)
                    ) {
                        hero(Modifier.weight(1f).fillMaxHeight())
                        controls(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()))
                    }
                }
            } else {
                // Cover gets ~34% of the height (never wider than the screen), the rest is controls.
                val coverHeight: Dp = minOf(maxWidth - 48.dp, maxHeight * 0.34f).coerceAtLeast(200.dp)
                // Lyrics need room to read: grow the pager to about half the screen while that page is open.
                val heroHeight: Dp by animateDpAsState(
                    if (pagerState.currentPage == PAGE_LYRICS || pagerState.currentPage == PAGE_ANALYZER) maxOf(coverHeight, maxHeight * 0.52f) else coverHeight,
                    label = "hero_height"
                )
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    TopBar(state, colors, actions)
                    hero(Modifier.padding(horizontal = 24.dp).fillMaxWidth().height(heroHeight + 44.dp))
                    Spacer(Modifier.height(8.dp))
                    controls(Modifier.padding(horizontal = 24.dp))
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}

// ─────────────────────────────── Top bar ───────────────────────────────

@Composable
private fun TopBar(state: PlayerUiState, colors: PlayerColors, actions: NowPlayingActions) {
    Row(
        Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "NOW PLAYING",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                letterSpacing = 2.sp
            )
            if (state.queueTotalTracks > 1) {
                Text(
                    "${state.queuePosition} of ${state.queueTotalTracks}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextPrimary.copy(alpha = 0.8f)
                )
            }
        }
        EngineChip(state, onClick = actions.onOpenAudioInfo)
        IconButton(onClick = actions.onOpenQueue) {
            Icon(
                Icons.AutoMirrored.Filled.QueueMusic,
                contentDescription = "Playback queue",
                tint = if (state.playbackQueue.hasQueue) colors.accent else TextSecondary
            )
        }
    }
}

@Composable
private fun EngineChip(state: PlayerUiState, onClick: () -> Unit) {
    val (label, tint) = when {
        state.selectedEngine == EngineType.CUSTOM_USB_DIRECT -> "BIT-PERFECT USB" to SignalTeal
        state.dsdRemasteringEnabled -> "DSD ${state.dsdRemasterTarget.name}" to SignalBlue
        state.selectedEngine == EngineType.ANDROID_HIRES_DIRECT -> "HI-RES DIRECT" to SignalTeal
        state.tubeWarmthEnabled -> "TUBE WARMTH" to HifiBrass
        else -> "ANDROID MIXER" to StatusWarn
    }
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.12f))
            .border(BorderStroke(1.dp, tint.copy(alpha = 0.45f)), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(6.dp))
        Text(
            label,
            fontFamily = TelemetryFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = tint,
            letterSpacing = 0.5.sp,
            maxLines = 1
        )
    }
}

// ─────────────────────────────── Hero ───────────────────────────────

@Composable
private fun CoverPage(state: PlayerUiState, colors: PlayerColors) {
    val track = state.playback.currentTrack
    val scale by animateFloatAsState(
        targetValue = if (state.playback.isPlaying) 1f else 0.9f,
        animationSpec = spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow),
        label = "cover_scale"
    )
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        TrackArtwork(
            uriString = track?.filePath ?: "",
            title = track?.title ?: "No track selected",
            artist = track?.artist,
            isPlaying = state.playback.isPlaying,
            isLarge = true,
            cornerRadius = 28.dp,
            modifier = Modifier
                .fillMaxHeight()
                .aspectRatio(1f)
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .shadow(
                    elevation = 24.dp,
                    shape = RoundedCornerShape(28.dp),
                    ambientColor = colors.accent,
                    spotColor = colors.accent
                )
        )
    }
}

@Composable
private fun HeroTabs(selected: Int, accent: Color, onSelect: (Int) -> Unit) {
    val tabs = listOf(
        Triple("Cover", Icons.Filled.Album, PAGE_COVER),
        Triple("Lyrics", Icons.Filled.Lyrics, PAGE_LYRICS),
        Triple("Wikipedia", Icons.Filled.Public, PAGE_WIKI),
        Triple("Analyzer", Icons.Filled.GraphicEq, PAGE_ANALYZER)
    )
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.07f))
            .padding(3.dp)
    ) {
        tabs.forEach { (label, icon, page) ->
            val sel = selected == page
            val tint = if (sel) accent else TextSecondary
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (sel) accent.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(page) }
                    .padding(horizontal = if (sel) 14.dp else 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(icon, label, tint = tint, modifier = Modifier.size(15.dp))
                if (sel) {
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelLarge, color = tint, maxLines = 1)
                }
            }
        }
    }
}

// ─────────────────────────────── Controls ───────────────────────────────

@Composable
private fun ControlsSection(
    state: PlayerUiState,
    wiki: WikipediaUiState,
    colors: PlayerColors,
    actions: NowPlayingActions,
    isFavorite: Boolean,
    analysis: com.windstrom5.rebit.domain.audiophile.TrackAnalysis?,
    onOpenWiki: () -> Unit,
    outputLevels: () -> com.windstrom5.rebit.playback.dsp.LevelMeter.Levels?,
    modifier: Modifier = Modifier
) {
    val track = state.playback.currentTrack
    val durationMs = (track?.durationMs ?: 1L).coerceAtLeast(1L)
    val path = remember(state.selectedEngine, track?.filePath, state.eqEnabled, state.crossfeedEnabled, state.replayGainEnabled,
        state.normalizationEnabled, state.tubeWarmthEnabled, state.dsdRemasteringEnabled, state.aiState, state.selectedDac) {
        buildSignalPath(state)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TitleBlock(track?.title, track?.artist, wiki, colors, onOpenWiki, isFavorite, track != null, actions.onToggleFavorite)

        val seek = rememberSeekUi(
            trackKey = track?.id,
            backendPosMs = state.playback.positionMs,
            isPlaying = state.playback.isPlaying,
            durationMs = durationMs,
            seekTo = actions.onSeek
        )
        Column {
            SeekBar(
                fraction = seek.displayedMs / durationMs,
                accent = colors.accent,
                onDrag = seek.onDrag,
                onCommit = seek.onCommit
            )
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    formatTime(seek.displayedMs.toLong()),
                    fontFamily = TelemetryFontFamily,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                StatusChips(state, colors, durationMs, analysis)
                Text(
                    formatTime(durationMs),
                    fontFamily = TelemetryFontFamily,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
            }
        }

        Transport(state, colors, actions)
        QuickTools(state, colors, actions)
        SignalPathCard(state, colors, path, outputLevels, actions.onOpenAudioInfo)
        VolumeRow(colors.accent, state, actions.onSetHardwareVolumeStep)
    }
}

@Composable
private fun TitleBlock(
    title: String?,
    artist: String?,
    wiki: WikipediaUiState,
    colors: PlayerColors,
    onOpenWiki: () -> Unit,
    isFavorite: Boolean,
    hasTrack: Boolean,
    onToggleFavorite: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
    Column(Modifier.weight(1f)) {
        Text(
            title ?: "No track selected",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().basicMarquee(iterations = Int.MAX_VALUE, initialDelayMillis = 2000, repeatDelayMillis = 1500, spacing = androidx.compose.foundation.MarqueeSpacing(48.dp))
        )
        Text(
            artist ?: "Pick something from your library",
            style = MaterialTheme.typography.bodyLarge,
            color = TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp)
        )
        // One-line Wikipedia teaser; tapping it opens the full article on the Wikipedia page.
        val ready = wiki.artist as? WikiLoad.Ready
        val description = ready?.summary?.description
        if (description != null) {
            Row(
                Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenWiki)
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Filled.Public, null, tint = colors.accent, modifier = Modifier.size(13.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    description.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, null,
                    tint = colors.accent, modifier = Modifier.size(14.dp)
                )
            }
        }
    }
    if (hasTrack) {
        IconButton(onClick = onToggleFavorite) {
            Icon(
                if (isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                contentDescription = if (isFavorite) "Remove from liked songs" else "Add to liked songs",
                tint = if (isFavorite) Color(0xFFFF6B81) else TextSecondary,
                modifier = Modifier.size(28.dp)
            )
        }
    }
    }
}

@Composable
private fun StatusChips(
    state: PlayerUiState,
    colors: PlayerColors,
    durationMs: Long,
    analysis: com.windstrom5.rebit.domain.audiophile.TrackAnalysis?
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (state.sleepTimer.isEnabled) {
            val s = state.sleepTimer.remainingSeconds
            MiniChip(Icons.Filled.Bedtime, "${s / 60}:${"%02d".format(s % 60)}", colors.accent)
        }
        if (state.abLoop.isEnabled) {
            MiniChip(
                Icons.Filled.Repeat,
                "${formatTime(state.abLoop.pointAMs ?: 0L)}–${formatTime(state.abLoop.pointBMs ?: durationMs)}",
                HifiBrass
            )
        }
        // Only a MEASURED value is shown (the old badge displayed a constant DR14 for every track).
        val dr = analysis?.drScore
        if (dr != null) {
            val c = if (dr >= 14) StatusGood else if (dr >= 10) colors.accent else HifiBrass
            Text(
                "DR$dr",
                fontFamily = TelemetryFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                color = c,
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .background(c.copy(alpha = 0.14f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
    }
}

@Composable
private fun MiniChip(icon: ImageVector, text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(3.dp))
        Text(text, fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = tint, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Transport(state: PlayerUiState, colors: PlayerColors, actions: NowPlayingActions) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = actions.onShuffle, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Filled.Shuffle, "Shuffle",
                tint = if (state.shuffleEnabled) colors.accent else TextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
        IconButton(onClick = actions.onPrevious, enabled = state.hasPrevious, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Filled.SkipPrevious, "Previous track",
                tint = if (state.hasPrevious) TextPrimary else TextSecondary.copy(alpha = 0.3f),
                modifier = Modifier.size(38.dp)
            )
        }
        Surface(
            onClick = actions.onPlayPause,
            shape = CircleShape,
            color = colors.accent,
            shadowElevation = 12.dp,
            modifier = Modifier.size(68.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (state.playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    if (state.playback.isPlaying) "Pause" else "Play",
                    tint = colors.onAccent,
                    modifier = Modifier.size(40.dp)
                )
            }
        }
        IconButton(onClick = actions.onNext, enabled = state.hasNext, modifier = Modifier.size(56.dp)) {
            Icon(
                Icons.Filled.SkipNext, "Next track",
                tint = if (state.hasNext) TextPrimary else TextSecondary.copy(alpha = 0.3f),
                modifier = Modifier.size(38.dp)
            )
        }
        IconButton(onClick = actions.onOpenAbLoop, modifier = Modifier.size(48.dp)) {
            Icon(
                Icons.Filled.Repeat, "A-B repeat",
                tint = if (state.abLoop.isEnabled) HifiBrass else TextSecondary,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@Composable
private fun QuickTools(state: PlayerUiState, colors: PlayerColors, actions: NowPlayingActions) {
    val sharing = state.sharedListening.role != SharedListeningRole.NONE
    val ai = state.aiState
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        ToolTile("EQ", Icons.Filled.Equalizer, state.eqEnabled, colors.accent, actions.onOpenEq, Modifier.weight(1f))
        ToolTile(
            "DSP", Icons.Filled.Tune,
            state.crossfeedEnabled || state.replayGainEnabled || state.normalizationEnabled,
            colors.accent, actions.onOpenDsp, Modifier.weight(1f)
        )
        ToolTile("Karaoke", Icons.Filled.Mic, ai.karaokeModeEnabled, colors.accent, actions.onOpenKaraoke, Modifier.weight(1f))
        ToolTile(
            "AI", Icons.Filled.Psychology,
            ai.isAutoPilotActive || ai.isDseeUpscalerActive || ai.isVocalSuppressionActive,
            colors.accent, actions.onOpenAi, Modifier.weight(1f)
        )
        ToolTile(
            "Tools", Icons.AutoMirrored.Filled.FormatListBulleted,
            state.sleepTimer.isEnabled || state.abLoop.isEnabled || state.playbackQueue.hasQueue,
            colors.accent, actions.onOpenTools, Modifier.weight(1f)
        )
        ToolTile("Share", Icons.Filled.Share, sharing, colors.accent, actions.onOpenShare, Modifier.weight(1f))
    }
}

@Composable
private fun ToolTile(
    label: String,
    icon: ImageVector,
    active: Boolean,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(if (active) accent.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.07f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, label, tint = if (active) accent else TextPrimary.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(4.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (active) accent else TextSecondary,
            maxLines = 1
        )
    }
}

@Composable
private fun SignalPathCard(
    state: PlayerUiState,
    colors: PlayerColors,
    path: com.windstrom5.rebit.domain.audiophile.SignalPath,
    outputLevels: () -> com.windstrom5.rebit.playback.dsp.LevelMeter.Levels?,
    onClick: () -> Unit
) {
    val track = state.playback.currentTrack
    val bitDepth = track?.pcm?.bitDepth ?: 24
    val rate = track?.pcm?.sampleRateHz ?: 48000
    val channels = track?.pcm?.channels ?: 2
    val kbps = (rate.toLong() * bitDepth * channels / 1000).toInt()
    val playing = state.playback.isPlaying
    val processing = state.playback.processing
    val usbDirect = state.playback.engineType == EngineType.CUSTOM_USB_DIRECT
    val bitPerfectNow = playing && usbDirect && processing.isTransparent
    val tint = when {
        bitPerfectNow -> SignalTeal
        path.purity >= 95 -> SignalTeal
        else -> HifiBrass
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.045f),
        border = BorderStroke(1.dp, tint.copy(alpha = 0.28f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.GraphicEq, null, tint = tint, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "$bitDepth-bit · ${"%.1f".format(rate / 1000.0)} kHz · ${track?.format?.name ?: "—"}",
                        fontFamily = TelemetryFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "$kbps kbps · ${path.verdict} · purity ${path.purity}",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight, "Signal path details",
                    tint = TextSecondary, modifier = Modifier.size(18.dp)
                )
            }

            if (track != null) {
                Spacer(Modifier.height(12.dp))
                com.windstrom5.rebit.presentation.components.StereoLevelMeter(
                    levels = outputLevels,
                    active = playing,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(10.dp))
                SignalChainChips(state)
            }
        }
    }
}

/** One chip per thing that touches the samples, in signal order — or a single "Bit-perfect". */
@Composable
private fun SignalChainChips(state: PlayerUiState) {
    val pb = state.playback
    val p = pb.processing
    val usbDirect = pb.engineType == EngineType.CUSTOM_USB_DIRECT
    com.windstrom5.rebit.presentation.components.ChainChipRow(Modifier.fillMaxWidth()) {
        when {
            !pb.isPlaying && p.output == null ->
                com.windstrom5.rebit.presentation.components.ChainChip("Idle", TextSecondary)
            usbDirect && p.isTransparent ->
                com.windstrom5.rebit.presentation.components.ChainChip("Bit-perfect · USB Direct", SignalTeal, led = true)
            p.isTransparent ->
                com.windstrom5.rebit.presentation.components.ChainChip("Transparent · ${p.output ?: "Android mixer"}", SignalBlue, led = true)
            else -> p.output?.let { com.windstrom5.rebit.presentation.components.ChainChip(it, TextSecondary) }
        }
        p.resampling?.let { com.windstrom5.rebit.presentation.components.ChainChip("SRC $it", HifiBrass) }
        p.stages.filter { it != "Limiter" }.forEach {
            com.windstrom5.rebit.presentation.components.ChainChip(it, SignalBlue)
        }
        if ("Limiter" in p.stages) {
            val db = pb.limiterReductionDb
            if (db >= 0.1f) {
                com.windstrom5.rebit.presentation.components.ChainChip("Limiting −${"%.1f".format(db)} dB", StatusWarn, led = true)
            } else {
                com.windstrom5.rebit.presentation.components.ChainChip("Limiter armed", TextSecondary)
            }
        }
        p.dither?.let { com.windstrom5.rebit.presentation.components.ChainChip("Dither $it", HifiBrass) }
        if (pb.gaplessNextReady) {
            com.windstrom5.rebit.presentation.components.ChainChip("Gapless · next primed", SignalTeal, led = true)
        }
    }
}

/**
 * One volume control that always matches what the volume buttons do:
 *  - USB Direct with a DAC that has a volume control: it is the DAC's hardware volume, and follows the phone's
 *    volume buttons and the audio-info panel (they all share one remembered level).
 *  - Otherwise: Android's music volume, kept in step with the buttons and the system volume panel.
 */
@Composable
private fun VolumeRow(accent: Color, state: PlayerUiState, onSetDacStep: (Int, Int) -> Unit) {
    val dacMode = state.hardwareVolumeEnabled &&
        state.selectedEngine == com.windstrom5.rebit.domain.model.EngineType.CUSTOM_USB_DIRECT &&
        state.selectedDac?.supportsHardwareVolume == true
    if (dacMode) {
        DacVolumeRow(accent, state.hardwareVolumeStepIndex, state.hardwareVolumeSteps, onSetDacStep)
    } else {
        SystemVolumeRow(accent)
    }
}

@Composable
private fun SystemVolumeRow(accent: Color) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager }
    var volume by remember { mutableFloatStateOf(audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC)?.toFloat() ?: 10f) }
    val maxVolume = remember { audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.toFloat()?.coerceAtLeast(1f) ?: 15f }

    // Without this the slider only read the volume once and drifted from the phone's volume buttons.
    androidx.compose.runtime.DisposableEffect(audioManager) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                audioManager?.let { volume = it.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() }
            }
        }
        context.contentResolver.registerContentObserver(android.provider.Settings.System.CONTENT_URI, true, observer)
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (volume == 0f) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeDown,
            "Mute",
            tint = TextSecondary,
            modifier = Modifier
                .size(20.dp)
                .clickable {
                    volume = 0f
                    audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                }
        )
        com.windstrom5.rebit.presentation.components.HifiSlider(
            value = volume,
            onValueChange = {
                volume = it
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0)
            },
            accent = accent,
            valueRange = 0f..maxVolume,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        Icon(Icons.AutoMirrored.Filled.VolumeUp, null, tint = TextSecondary, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun DacVolumeRow(accent: Color, stepIndex: Int, steps: Int, onSet: (Int, Int) -> Unit) {
    val max = steps.coerceAtLeast(2)
    var dragging by remember { mutableStateOf(false) }
    var local by remember { mutableFloatStateOf(stepIndex.toFloat()) }
    var lastSent by remember { mutableIntStateOf(stepIndex) }

    // Follow changes made elsewhere (the phone's volume buttons, the audio-info panel) unless mid-drag.
    LaunchedEffect(stepIndex) {
        if (!dragging) {
            local = stepIndex.toFloat()
            lastSent = stepIndex
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (local <= 0f) Icons.AutoMirrored.Filled.VolumeMute else Icons.AutoMirrored.Filled.VolumeDown,
            "Mute DAC",
            tint = TextSecondary,
            modifier = Modifier
                .size(20.dp)
                .clickable {
                    local = 0f
                    lastSent = 0
                    onSet(0, max)
                }
        )
        com.windstrom5.rebit.presentation.components.HifiSlider(
            value = local,
            accent = accent,
            onValueChange = {
                dragging = true
                local = it
                val s = it.toInt()
                // Send while dragging so you hear it, but not on every pixel: each send is a USB command.
                if (kotlin.math.abs(s - lastSent) >= 2) {
                    lastSent = s
                    onSet(s, max)
                }
            },
            onValueChangeFinished = {
                dragging = false
                lastSent = local.toInt()
                onSet(lastSent, max)
            },
            valueRange = 0f..max.toFloat(),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        Text(
            "DAC ${(local / max * 100).toInt()}%",
            fontFamily = TelemetryFontFamily,
            fontSize = 11.sp,
            color = accent,
            modifier = Modifier.width(64.dp)
        )
    }
}

// ─────────────────────────────── Seek smoothing ───────────────────────────────

@Stable
private class SeekUi(
    val displayedMs: Float,
    val onDrag: (Float) -> Unit,
    val onCommit: (Float) -> Unit
)

/**
 * Same behaviour as the previous screen: the backend position only ticks a few times a second, so
 * the bar glides locally at ~25 fps, ignores backend updates while dragging, and holds the target
 * after a seek until the backend catches up (so the bar doesn't snap back).
 */
@Composable
private fun rememberSeekUi(
    trackKey: Any?,
    backendPosMs: Long,
    isPlaying: Boolean,
    durationMs: Long,
    seekTo: (Long) -> Unit
): SeekUi {
    var dragging by remember(trackKey) { mutableStateOf(false) }
    var dragMs by remember(trackKey) { mutableFloatStateOf(0f) }
    var pending by remember(trackKey) { mutableStateOf<Float?>(null) }
    var smooth by remember(trackKey) { mutableFloatStateOf(backendPosMs.toFloat()) }

    LaunchedEffect(backendPosMs, isPlaying) {
        if (!dragging) {
            val backend = backendPosMs.toFloat()
            val target = pending
            if (target != null) {
                if (kotlin.math.abs(backend - target) < 1500f || !isPlaying) {
                    pending = null
                    smooth = backend
                }
            } else {
                smooth = backend
            }
        }
    }
    LaunchedEffect(isPlaying, dragging, pending) {
        if (isPlaying && !dragging && pending == null) {
            var last = System.currentTimeMillis()
            while (isActive) {
                delay(40L)
                val now = System.currentTimeMillis()
                smooth = (smooth + (now - last)).coerceIn(0f, durationMs.toFloat())
                last = now
            }
        }
    }

    val shown = when {
        dragging -> dragMs
        pending != null -> pending ?: 0f
        else -> smooth.coerceIn(0f, durationMs.toFloat())
    }
    return SeekUi(
        displayedMs = shown,
        onDrag = { f -> dragging = true; dragMs = f * durationMs },
        onCommit = { f ->
            val t = f * durationMs
            pending = t
            dragging = false
            seekTo(t.toLong())
        }
    )
}


/** The file → DAC chain for the current state; used by the player card and the Audio Info dialog. */
internal fun buildSignalPath(state: PlayerUiState): com.windstrom5.rebit.domain.audiophile.SignalPath {
    val track = state.playback.currentTrack
    val usbDirect = state.selectedEngine == EngineType.CUSTOM_USB_DIRECT
    val ai = state.aiState
    return com.windstrom5.rebit.domain.audiophile.SignalPathLogic.analyze(
        com.windstrom5.rebit.domain.audiophile.PathInputs(
            sourceFormat = track?.format?.name ?: "—",
            sourceRateHz = track?.pcm?.sampleRateHz ?: 44100,
            sourceBitDepth = track?.pcm?.bitDepth ?: 16,
            usbDirect = usbDirect,
            eq = state.eqEnabled,
            crossfeed = state.crossfeedEnabled,
            replayGain = state.replayGainEnabled,
            normalization = state.normalizationEnabled,
            tubeWarmth = state.tubeWarmthEnabled,
            dsdRemaster = state.dsdRemasteringEnabled,
            aiProcessing = ai.isAutoPilotActive || ai.isDseeUpscalerActive || ai.isVocalSuppressionActive || ai.karaokeModeEnabled,
            dacName = if (usbDirect) state.selectedDac?.let { d ->
                val chip = com.windstrom5.rebit.domain.audiophile.DacChipIdentifier
                    .identify(d.vendorId, d.productId, d.productName, d.manufacturerName).shortLabel
                if (chip != null) "${d.productName} · $chip" else d.productName
            } else null,
            dacRatesHz = if (usbDirect) state.selectedDac?.supportedSampleRates.orEmpty() else emptyList(),
            dacBitDepths = if (usbDirect) state.selectedDac?.supportedBitDepths.orEmpty() else emptyList()
        )
    )
}
