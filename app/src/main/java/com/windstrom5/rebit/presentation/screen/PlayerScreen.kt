package com.windstrom5.rebit.presentation.screen

import com.windstrom5.rebit.presentation.components.AudioPanelDefaults
import android.content.Context
import android.media.AudioManager
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeMute
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Loop
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.VolumeUp
import com.windstrom5.rebit.presentation.components.rememberArtworkPalette
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import com.windstrom5.rebit.domain.model.LyricsProvider
import com.windstrom5.rebit.domain.model.LyricsSearchResult
import com.windstrom5.rebit.playback.autoeq.AutoEqProfile
import com.windstrom5.rebit.playback.autoeq.AutoEqRepository
import com.windstrom5.rebit.playback.lyrics.LyricsRomanizer
import com.windstrom5.rebit.playback.lyrics.RomajiDisplayMode
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.windstrom5.rebit.domain.model.DacProfile
import com.windstrom5.rebit.domain.model.EngineType
import com.windstrom5.rebit.domain.model.TrendPoint
import com.windstrom5.rebit.domain.model.UserSettings
import com.windstrom5.rebit.presentation.components.BitActivityLedGrid
import com.windstrom5.rebit.presentation.dialog.AbxTestDialog
import com.windstrom5.rebit.presentation.dialog.AiNeuralStudioDialog
import com.windstrom5.rebit.presentation.dialog.KaraokeStudioDialog
import com.windstrom5.rebit.presentation.dialog.StudioMasterEqualizerDialog
import androidx.compose.material.icons.filled.Psychology
import com.windstrom5.rebit.domain.model.ReverbPreset
import com.windstrom5.rebit.domain.model.ReplayGainMode
import com.windstrom5.rebit.domain.model.DsdPlaybackMode
import com.windstrom5.rebit.presentation.components.IntegrityGauge
import com.windstrom5.rebit.presentation.components.StatCard
import com.windstrom5.rebit.presentation.components.TrackArtwork
import com.windstrom5.rebit.presentation.components.charts.LineChart
import com.windstrom5.rebit.presentation.theme.BackgroundInk
import com.windstrom5.rebit.presentation.theme.StatusError
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.theme.SignalBlue
import com.windstrom5.rebit.presentation.theme.OutlineSubtle
import com.windstrom5.rebit.presentation.theme.SignalTeal
import com.windstrom5.rebit.presentation.theme.SignalTealDim
import com.windstrom5.rebit.presentation.theme.StatusGood
import com.windstrom5.rebit.presentation.theme.StatusWarn
import com.windstrom5.rebit.presentation.theme.SurfaceInk
import com.windstrom5.rebit.presentation.theme.SurfacePanel
import com.windstrom5.rebit.presentation.theme.SurfacePanelHigh
import com.windstrom5.rebit.presentation.theme.TelemetryFontFamily
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary
import com.windstrom5.rebit.presentation.viewmodel.AudioRoute
import com.windstrom5.rebit.presentation.viewmodel.PlayerUiState
import com.windstrom5.rebit.presentation.viewmodel.PlayerViewModel
import com.windstrom5.rebit.presentation.viewmodel.WikipediaViewModel
import com.windstrom5.rebit.presentation.viewmodel.FavoritesViewModel
import com.windstrom5.rebit.presentation.screen.player.NowPlayingActions
import com.windstrom5.rebit.presentation.screen.player.NowPlayingContent
import org.koin.androidx.compose.koinViewModel
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PlayerScreen(viewModel: PlayerViewModel = koinViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val stemProgress by viewModel.stemExtractionProgress.collectAsStateWithLifecycle()
    val scrollState = rememberScrollState()
    val context = androidx.compose.ui.platform.LocalContext.current

    val lrcPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    stream.bufferedReader(Charsets.UTF_8).readText()
                }
            }.getOrNull()
            if (!text.isNullOrBlank()) {
                viewModel.importCustomLyrics(text)
            }
        }
    }

    // Modals visibility states for feature panels
    var showEqModal by remember { mutableStateOf(false) }
    var showDspModal by remember { mutableStateOf(false) }
    var showVuModal by remember { mutableStateOf(false) }
    var showAudioInfoModal by remember { mutableStateOf(false) }
    var showSleepTimerModal by remember { mutableStateOf(false) }
    var showQueueModal by remember { mutableStateOf(false) }
    var showAbLoopModal by remember { mutableStateOf(false) }
    var showToolsModal by remember { mutableStateOf(false) }
    var showLyricsSourceModal by remember { mutableStateOf(false) }
    var showAutoEqModal by remember { mutableStateOf(false) }
    var showAbxModal by remember { mutableStateOf(false) }
    var showHardwareVolumeModal by remember { mutableStateOf(false) }
    var showAiStudioModal by remember { mutableStateOf(false) }
    var showKaraokeStudioModal by remember { mutableStateOf(false) }
    var showSharedListeningModal by remember { mutableStateOf(false) }

    val isBitPerfect = state.selectedEngine == EngineType.CUSTOM_USB_DIRECT
    val currentTrack = state.playback.currentTrack

    // Dynamic palette extracted from the album artwork (drives the whole screen's colours)
    val palette = rememberArtworkPalette(currentTrack?.filePath ?: "")

    // Wikipedia lookup for the playing song + artist, re-run whenever the track changes
    val wikiViewModel: WikipediaViewModel = koinViewModel()
    val wikiState by wikiViewModel.state.collectAsStateWithLifecycle()
    // Lyrics arrive a moment after the track starts. Their writing system (kana, hangul...) tells the
    // matcher which country's artist is meant, so a late arrival triggers one more lookup.
    val hasLyrics = state.lyrics.lines.isNotEmpty() || !state.lyrics.plainText.isNullOrBlank()
    LaunchedEffect(currentTrack?.id, currentTrack?.title, currentTrack?.artist, currentTrack?.genre, hasLyrics) {
        wikiViewModel.onTrackChanged(
            trackKey = currentTrack?.let { "${it.id}|${it.filePath}" },
            title = currentTrack?.title,
            artist = currentTrack?.artist,
            genre = currentTrack?.genre,
            lyricsSample = state.lyrics.plainText
                ?: state.lyrics.lines.take(12).joinToString("\n") { it.text }.ifBlank { null }
        )
    }

    val analysisViewModel: com.windstrom5.rebit.presentation.viewmodel.TrackAnalysisViewModel = koinViewModel()
    val analysisState by analysisViewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(currentTrack?.filePath) { analysisViewModel.onTrack(currentTrack) }

    val favoritesViewModel: FavoritesViewModel = koinViewModel()
    val favoritePaths by favoritesViewModel.favoritePaths.collectAsStateWithLifecycle()

    val actions = remember(viewModel) {
        NowPlayingActions(
            onPlayPause = {
                val s = viewModel.uiState.value
                when {
                    s.playback.isPlaying -> viewModel.pause()
                    s.playback.isPaused -> viewModel.resume()
                    else -> viewModel.replayCurrentTrack()
                }
            },
            onNext = { viewModel.nextTrack() },
            onPrevious = { viewModel.previousTrack() },
            onShuffle = { viewModel.toggleShuffle() },
            onSeek = { viewModel.seekTo(it) },
            onToggleLyrics = { viewModel.toggleLyricsView() },
            onRetryWiki = { wikiViewModel.retry() },
            onToggleFavorite = { viewModel.uiState.value.playback.currentTrack?.let { favoritesViewModel.toggle(it) } },
            onOpenQueue = { showQueueModal = true },
            onOpenAudioInfo = { showAudioInfoModal = true },
            onOpenAbLoop = { showAbLoopModal = true },
            onOpenEq = { showEqModal = true },
            onOpenDsp = { showDspModal = true },
            onOpenKaraoke = { showKaraokeStudioModal = true },
            onOpenAi = { showAiStudioModal = true },
            onOpenTools = { showToolsModal = true },
            onOpenShare = { showSharedListeningModal = true },
            onSetHardwareVolumeStep = { step, max -> viewModel.setHardwareVolumeStep(step, max) }
        )
    }

    NowPlayingContent(
        outputLevels = viewModel::outputLevels,
        recentSamples = viewModel::recentSamples,
        analyzerSampleRate = viewModel::outputSampleRateHz,
        state = state,
        wiki = wikiState,
        palette = palette,
        isFavorite = currentTrack?.filePath in favoritePaths,
        analysis = analysisState.result,
        actions = actions,
        lyricsPage = { lyricsModifier, accent ->
            com.windstrom5.rebit.presentation.screen.player.LyricsPanel(
                lyrics = state.lyrics,
                activeIndex = state.activeLyricIndex,
                provider = state.lyricsProvider,
                accent = accent,
                onSeekTo = { viewModel.seekTo(it) },
                onOpenSource = { showLyricsSourceModal = true },
                onImportLrc = { lrcPickerLauncher.launch("*/*") },
                modifier = lyricsModifier
            )
        }
    )


    // ── MODAL 1: 10-BAND STUDIO MASTER EQUALIZER CONSOLE ──
    if (showEqModal) {
        StudioMasterEqualizerDialog(
            state = state,
            onDismiss = { showEqModal = false },
            onToggleEq = { viewModel.toggleEq() },
            onUpdateGains = { viewModel.updateEqGains(it) },
            onApplyIemProfile = { profile -> viewModel.applyAutoEqProfile(profile) },
            onResetFlat = { viewModel.resetEqFlat() },
            onSetPreamp = { viewModel.setEqPreampDb(it) },
            onApplyParametric = { viewModel.applyParametricEq(it) }
        )
    }

    // ── MODAL 2: POWERAMP & FOOBAR2000 AUDIOPHILE DSP SUITE MODAL ──
    if (showDspModal) {
        Dialog(
            onDismissRequest = { showDspModal = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.85f)
                    .padding(vertical = 12.dp),
                shape = RoundedCornerShape(22.dp),
                color = SurfaceInk,
                border = BorderStroke(1.dp, HifiBrass.copy(alpha = 0.6f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Tune, contentDescription = null, tint = HifiBrass, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("AUDIOPHILE DSP SUITE", fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = HifiBrass)
                                Text("Poweramp & Foobar2000 Floating-Point Precision Engines", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                        IconButton(onClick = { showDspModal = false }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                        }
                    }

                    HorizontalDivider(color = OutlineSubtle)

                    // ── 1. Poweramp Stereo Soundstage Expander ──
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("STEREO SOUNDSTAGE EXPANDER", fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = SignalTeal)
                                Text("Mid/Side holographic acoustic width matrix", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextSecondary)
                            }
                            Text(
                                if (state.spatialWidth == 0.0f) "MONO" else if (state.spatialWidth == 1.0f) "STANDARD 100%" else "${(state.spatialWidth * 100).toInt()}% WIDE",
                                fontFamily = TelemetryFontFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = HifiBrass
                            )
                        }
                        Slider(
                            value = state.spatialWidth,
                            onValueChange = { viewModel.setSpatialWidth(it) },
                            valueRange = 0.0f..2.0f,
                            steps = 19,
                            modifier = Modifier.fillMaxWidth(),
                            colors = SliderDefaults.colors(thumbColor = SignalTeal, activeTrackColor = SignalTeal)
                        )
                    }

                    HorizontalDivider(color = OutlineSubtle)

                    // ── 2. Poweramp Studio Reverb Simulator ──
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("STUDIO REVERB ENVIRONMENT", fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = HifiBrass)
                                Text("Schroeder acoustic room response modeling", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextSecondary)
                            }
                            Switch(
                                checked = state.reverbEnabled,
                                onCheckedChange = { viewModel.toggleReverb() },
                                colors = SwitchDefaults.colors(checkedThumbColor = HifiBrass, checkedTrackColor = HifiBrass.copy(alpha = 0.25f))
                            )
                        }

                        if (state.reverbEnabled) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ReverbPreset.entries.forEach { preset ->
                                    val isSelected = state.reverbPreset == preset
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { viewModel.setReverbPreset(preset) },
                                        label = { Text(preset.label, fontFamily = TelemetryFontFamily, fontSize = 11.sp, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = HifiBrass.copy(alpha = 0.2f),
                                            selectedLabelColor = HifiBrass
                                        ),
                                        border = FilterChipDefaults.filterChipBorder(
                                            borderColor = OutlineSubtle,
                                            selectedBorderColor = HifiBrass.copy(alpha = 0.6f),
                                            enabled = true,
                                            selected = isSelected
                                        ),
                                        shape = RoundedCornerShape(6.dp),
                                        modifier = Modifier.height(26.dp)
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("WET / DRY", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextSecondary, modifier = Modifier.width(64.dp))
                                Slider(
                                    value = state.reverbWetDry,
                                    onValueChange = { viewModel.setReverbWetDry(it) },
                                    valueRange = 0.0f..1.0f,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(thumbColor = HifiBrass, activeTrackColor = HifiBrass)
                                )
                                Text("${(state.reverbWetDry * 100).toInt()}%", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = HifiBrass)
                            }
                        }
                    }

                    HorizontalDivider(color = OutlineSubtle)

                    // ── 3. Foobar2000 ReplayGain & EBU R128 Normalizer ──
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("REPLAYGAIN (EBU R128)", fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp, color = SignalTeal)
                                Text("Dynamic loudness normalization with anti-clipping", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextSecondary)
                            }
                            Switch(
                                checked = state.replayGainEnabled,
                                onCheckedChange = { viewModel.toggleReplayGain() },
                                colors = SwitchDefaults.colors(checkedThumbColor = SignalTeal, checkedTrackColor = SignalTeal.copy(alpha = 0.25f))
                            )
                        }

                        if (state.replayGainEnabled) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                ReplayGainMode.entries.forEach { mode ->
                                    val isSelected = state.replayGainMode == mode
                                    FilterChip(
                                        selected = isSelected,
                                        onClick = { viewModel.setReplayGainMode(mode) },
                                        label = { Text(mode.label, fontFamily = TelemetryFontFamily, fontSize = 11.sp) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = SignalTeal.copy(alpha = 0.2f),
                                            selectedLabelColor = SignalTeal
                                        ),
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("PREAMP", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextSecondary, modifier = Modifier.width(64.dp))
                                Slider(
                                    value = state.replayGainPreampDb,
                                    onValueChange = { viewModel.setReplayGainPreampDb(it) },
                                    valueRange = -12.0f..12.0f,
                                    steps = 23,
                                    modifier = Modifier.weight(1f),
                                    colors = SliderDefaults.colors(thumbColor = SignalTeal, activeTrackColor = SignalTeal)
                                )
                                Text("${if (state.replayGainPreampDb > 0) "+" else ""}${"%.1f".format(state.replayGainPreampDb)}dB", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = SignalTeal)
                            }
                        }
                    }

                    HorizontalDivider(color = OutlineSubtle)

                    // ── 4. Crossfeed ──
                    DspToggleRow("HEADPHONE CROSSFEED", "Meier natural acoustic room bleed", state.crossfeedEnabled) { viewModel.toggleCrossfeed() }
                    if (state.crossfeedEnabled) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("STRENGTH", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextSecondary, modifier = Modifier.width(64.dp))
                            Slider(
                                value = state.crossfeedStrength,
                                onValueChange = { viewModel.setCrossfeedStrength(it) },
                                valueRange = 0f..1f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(thumbColor = HifiBrass, activeTrackColor = HifiBrass)
                            )
                            Text("%.0f%%".format(state.crossfeedStrength * 100), fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = HifiBrass)
                        }
                    }

                    // ── 5. ABX Listening Comparator Launcher ──
                    OutlinedButton(
                        onClick = {
                            showDspModal = false
                            showAbxModal = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        border = BorderStroke(1.dp, HifiBrass.copy(alpha = 0.8f)),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Headphones, contentDescription = null, tint = HifiBrass, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("LAUNCH FOOBAR2000 ABX BLIND LISTENING TEST", fontFamily = TelemetryFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = HifiBrass)
                        }
                    }
                }
            }
        }
    }

    // ── MODAL 3: MCINTOSH VU METERS & BIT-MATRIX MODAL ──
    if (showVuModal) {
        Dialog(onDismissRequest = { showVuModal = false }) {
            Surface(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.85f).padding(12.dp),
                shape = RoundedCornerShape(18.dp),
                color = SurfaceInk,
                border = BorderStroke(1.dp, SignalTeal.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("ANALOG POWER & TELEMETRY", fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = SignalTeal)
                        IconButton(onClick = { showVuModal = false }) {
                            Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                        }
                    }

                    // Level meters need a real sample tap in the audio engine; the previous needles were driven by
                    // constants (0.6 / 0.58 whenever music played), so they are not shown rather than faked.
                    Text(
                        "Live level metering isn't available yet. The needles that used to be here moved on a fixed value, not your audio.",
                        fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextSecondary
                    )

                    // 24-Bit Bit Activity Matrix
                    Text("24-BIT BIT-ACTIVITY LED MATRIX", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = SignalTeal)
                    BitActivityLedGrid(activeBitMask = state.playback.verification?.activeBitMask ?: 0)

                    // Line Charts
                    if (state.playback.recentHistory.size >= 2) {
                        LineChart(title = "Latency Jitter (ms)", points = state.playback.recentHistory.map { TrendPoint("", it.latencyMs) })
                    }
                }
            }
        }
    }

    // ── MODAL 4: AUDIO INFO (real measured data; see AudioInfoDialog) ──
    if (showAudioInfoModal) {
        com.windstrom5.rebit.presentation.dialog.AudioInfoDialog(
            state = state,
            analysis = analysisState,
            path = com.windstrom5.rebit.presentation.screen.player.buildSignalPath(state),
            onMeasure = { analysisViewModel.measure() },
            onSelectEngine = { viewModel.selectEngine(it) },
            onSetBufferMode = { viewModel.setBufferMode(it) },
            onSetManualBuffer = { viewModel.setManualBufferSize(it) },
            onSetHardwareVolume = { viewModel.setHardwareVolume(it) },
            usbSetup = viewModel.usbSetup.collectAsStateWithLifecycle().value,
            onRetryUsb = { viewModel.retryUsbSetup() },
            onDismiss = { showAudioInfoModal = false }
        )
    }

    // ── MODAL 5: AUDIOPHILE TOOLS & UTILITIES HUB (Repacked Modal) ──
    if (showToolsModal) {
        Dialog(
            onDismissRequest = { showToolsModal = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .wrapContentHeight()
                    .padding(vertical = 12.dp),
                shape = RoundedCornerShape(22.dp),
                color = SurfaceInk,
                border = BorderStroke(1.dp, SignalTeal.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Tune, contentDescription = null, tint = SignalTeal, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                "AUDIOPHILE TOOLS",
                                fontFamily = TelemetryFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = SignalTeal,
                                letterSpacing = 0.4.sp
                            )
                        }
                        IconButton(onClick = { showToolsModal = false }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                        }
                    }

                    Text(
                        "Essential audiophile utilities, queue management, and listening aids.",
                        fontFamily = TelemetryFontFamily,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )

                    HorizontalDivider(color = OutlineSubtle)

                    // Card 1: Up Next Queue
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showToolsModal = false
                                showQueueModal = true
                            },
                        colors = CardDefaults.cardColors(containerColor = BackgroundInk),
                        border = BorderStroke(1.dp, if (state.playbackQueue.hasQueue) HifiBrass.copy(alpha = 0.6f) else OutlineSubtle),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = HifiBrass, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("UP NEXT QUEUE", fontFamily = TelemetryFontFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Text("${state.playbackQueue.tracks.size} tracks in playlist sequence", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextSecondary)
                                }
                            }
                            Text("VIEW ❯", fontFamily = TelemetryFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = HifiBrass)
                        }
                    }

                    // Card 2: Sleep Timer
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showToolsModal = false
                                showSleepTimerModal = true
                            },
                        colors = CardDefaults.cardColors(containerColor = BackgroundInk),
                        border = BorderStroke(1.dp, if (state.sleepTimer.isEnabled) SignalTeal.copy(alpha = 0.6f) else OutlineSubtle),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Bedtime, contentDescription = null, tint = SignalTeal, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("SLEEP TIMER", fontFamily = TelemetryFontFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Text(
                                        if (state.sleepTimer.isEnabled) "Active: ${state.sleepTimer.remainingSeconds / 60}m ${state.sleepTimer.remainingSeconds % 60}s remaining"
                                        else "Soft fade-out auto-pause",
                                        fontFamily = TelemetryFontFamily,
                                        fontSize = 11.sp,
                                        color = if (state.sleepTimer.isEnabled) SignalTeal else TextSecondary
                                    )
                                }
                            }
                            Text("SET ❯", fontFamily = TelemetryFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SignalTeal)
                        }
                    }

                    // Card 3: A-B Repeat Looper
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showToolsModal = false
                                showAbLoopModal = true
                            },
                        colors = CardDefaults.cardColors(containerColor = BackgroundInk),
                        border = BorderStroke(1.dp, if (state.abLoop.isEnabled) SignalTeal.copy(alpha = 0.6f) else OutlineSubtle),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Loop, contentDescription = null, tint = SignalTeal, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("A-B REPEAT LOOPER", fontFamily = TelemetryFontFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Text(
                                        if (state.abLoop.isEnabled) "Loop active between point A & B"
                                        else "Loop audio passages for critical analysis",
                                        fontFamily = TelemetryFontFamily,
                                        fontSize = 11.sp,
                                        color = if (state.abLoop.isEnabled) SignalTeal else TextSecondary
                                    )
                                }
                            }
                            Text("OPEN ❯", fontFamily = TelemetryFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SignalTeal)
                        }
                    }

                    // Card 4: Live VU Meters
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                showToolsModal = false
                                showVuModal = true
                            },
                        colors = CardDefaults.cardColors(containerColor = BackgroundInk),
                        border = AudioPanelDefaults.border,
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Speed, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text("ANALOG VU METERS", fontFamily = TelemetryFontFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                    Text("Dual analog needle & peak telemetry", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextSecondary)
                                }
                            }
                            Text("VIEW ❯", fontFamily = TelemetryFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
                        }
                    }

                    // Card 5: AI Karaoke & Vocal Studio
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                Toast.makeText(context, "AI Karaoke Studio is in Beta (WIP) — Full neural models in upcoming update!", Toast.LENGTH_SHORT).show()
                                showToolsModal = false
                                showKaraokeStudioModal = true
                            },
                        colors = CardDefaults.cardColors(containerColor = BackgroundInk),
                        border = BorderStroke(1.dp, if (state.aiState.karaokeModeEnabled) SignalTeal.copy(alpha = 0.6f) else OutlineSubtle),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Mic, contentDescription = null, tint = SignalTeal, modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("AI KARAOKE STUDIO", fontFamily = TelemetryFontFamily, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = TextPrimary)
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Box(
                                            modifier = Modifier
                                                .background(StatusWarn.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                .border(BorderStroke(0.5.dp, StatusWarn), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text("WIP", fontFamily = TelemetryFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = StatusWarn)
                                        }
                                    }
                                    Text(
                                        if (state.aiState.karaokeModeEnabled) "Active: ${state.aiState.karaokeModeType.label}"
                                        else "Instrumental removal, acapella & key transposer",
                                        fontFamily = TelemetryFontFamily,
                                        fontSize = 11.sp,
                                        color = if (state.aiState.karaokeModeEnabled) SignalTeal else TextSecondary
                                    )
                                }
                            }
                            Text("OPEN ❯", fontFamily = TelemetryFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SignalTeal)
                        }
                    }
                }
            }
        }
    }

    // ── MODAL 6: SLEEP TIMER MODAL ──
    if (showSleepTimerModal) {
        Dialog(
            onDismissRequest = { showSleepTimerModal = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.90f)
                    .wrapContentHeight()
                    .padding(vertical = 12.dp),
                shape = RoundedCornerShape(20.dp),
                color = SurfaceInk,
                border = BorderStroke(1.dp, SignalTeal.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Bedtime, contentDescription = null, tint = SignalTeal, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "SLEEP TIMER",
                                fontFamily = TelemetryFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = SignalTeal,
                                letterSpacing = 0.4.sp
                            )
                        }
                        IconButton(onClick = { showSleepTimerModal = false }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                        }
                    }

                    Text(
                        "Includes graceful 15-second soft volume ramp-down to protect sensitive DAC clocks & headphones from abrupt transient clicks.",
                        fontFamily = TelemetryFontFamily,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )

                    if (state.sleepTimer.isEnabled) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(SignalTeal.copy(alpha = 0.12f), RoundedCornerShape(8.dp))
                                .border(BorderStroke(1.dp, SignalTeal.copy(alpha = 0.4f)), RoundedCornerShape(8.dp))
                                .padding(12.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text("ACTIVE COUNTDOWN", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = SignalTeal)
                                    Text(
                                        "${state.sleepTimer.remainingSeconds / 60}m ${"%02d".format(state.sleepTimer.remainingSeconds % 60)}s remaining",
                                        fontFamily = TelemetryFontFamily,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = TextPrimary
                                    )
                                }
                                OutlinedButton(
                                    onClick = { viewModel.cancelSleepTimer() },
                                    border = BorderStroke(1.dp, StatusError.copy(alpha = 0.6f)),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Text("CANCEL", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = StatusError)
                                }
                            }
                        }
                    }

                    Text("SELECT DURATION", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextSecondary, letterSpacing = 0.4.sp)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(15, 30, 45, 60).forEach { mins ->
                            FilterChip(
                                selected = state.sleepTimer.isEnabled && !state.sleepTimer.isEndOfTrack && state.sleepTimer.totalSeconds == mins * 60,
                                onClick = {
                                    viewModel.setSleepTimer(mins)
                                    showSleepTimerModal = false
                                },
                                label = { Text("${mins}m", fontFamily = TelemetryFontFamily, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = SignalTeal.copy(alpha = 0.2f), selectedLabelColor = SignalTeal),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(90, 120).forEach { mins ->
                            FilterChip(
                                selected = state.sleepTimer.isEnabled && !state.sleepTimer.isEndOfTrack && state.sleepTimer.totalSeconds == mins * 60,
                                onClick = {
                                    viewModel.setSleepTimer(mins)
                                    showSleepTimerModal = false
                                },
                                label = { Text("${mins}m", fontFamily = TelemetryFontFamily, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(selectedContainerColor = SignalTeal.copy(alpha = 0.2f), selectedLabelColor = SignalTeal),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        FilterChip(
                            selected = state.sleepTimer.isEnabled && state.sleepTimer.isEndOfTrack,
                            onClick = {
                                viewModel.setSleepTimer(0, isEndOfTrack = true)
                                showSleepTimerModal = false
                            },
                            label = { Text("END OF TRACK", fontFamily = TelemetryFontFamily, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = SignalTeal.copy(alpha = 0.2f), selectedLabelColor = SignalTeal),
                            modifier = Modifier.weight(2f)
                        )
                    }
                }
            }
        }
    }

    // ── MODAL 6: UP NEXT QUEUE MODAL ──
    if (showQueueModal) {
        val queue = state.playbackQueue
        Dialog(
            onDismissRequest = { showQueueModal = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.80f)
                    .padding(vertical = 12.dp),
                shape = RoundedCornerShape(22.dp),
                color = SurfaceInk,
                border = BorderStroke(1.dp, HifiBrass.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, tint = HifiBrass, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("UP NEXT QUEUE", fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = HifiBrass)
                                Text("${queue.tracks.size} tracks loaded • ${if (queue.shuffleEnabled) "Shuffle On" else "Sequential"}", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextSecondary)
                            }
                        }
                        IconButton(onClick = { showQueueModal = false }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    HorizontalDivider(color = OutlineSubtle)
                    Spacer(modifier = Modifier.height(8.dp))

                    if (queue.tracks.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Queue is empty. Select a song from Library.", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextSecondary)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            itemsIndexed(queue.tracks) { index, track ->
                                val isCurrent = queue.playPosition == index
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.playQueueTrack(track)
                                            showQueueModal = false
                                        },
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isCurrent) SignalTeal.copy(alpha = 0.15f) else BackgroundInk
                                    ),
                                    border = BorderStroke(1.dp, if (isCurrent) SignalTeal.copy(alpha = 0.5f) else OutlineSubtle),
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "${index + 1}",
                                            fontFamily = TelemetryFontFamily,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isCurrent) SignalTeal else TextSecondary,
                                            modifier = Modifier.width(24.dp)
                                        )
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                track.title,
                                                fontFamily = TelemetryFontFamily,
                                                fontSize = 12.sp,
                                                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isCurrent) SignalTeal else TextPrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                "${track.artist ?: "Unknown"} • ${track.format.name} ${track.pcm.bitDepth}b/${track.pcm.sampleRateHz / 1000}k",
                                                fontFamily = TelemetryFontFamily,
                                                fontSize = 11.sp,
                                                color = TextSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            formatPlaybackTime(track.durationMs),
                                            fontFamily = TelemetryFontFamily,
                                            fontSize = 11.sp,
                                            color = TextSecondary
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── MODAL 7: A-B REPEAT LOOPER MODAL ──
    if (showAbLoopModal) {
        val loop = state.abLoop
        val durationMs = (state.playback.currentTrack?.durationMs ?: 1L).coerceAtLeast(1L)
        Dialog(
            onDismissRequest = { showAbLoopModal = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.90f)
                    .wrapContentHeight()
                    .padding(vertical = 12.dp),
                shape = RoundedCornerShape(20.dp),
                color = SurfaceInk,
                border = BorderStroke(1.dp, SignalTeal.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Loop, contentDescription = null, tint = SignalTeal, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("A-B REPEAT LOOPER", fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = SignalTeal, letterSpacing = 0.4.sp)
                        }
                        IconButton(onClick = { showAbLoopModal = false }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                        }
                    }

                    Text("Set Point A and Point B timestamps to repeat a passage for critical listening and audio inspection.", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextSecondary)

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Card(
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(containerColor = BackgroundInk),
                            border = BorderStroke(1.dp, if (loop.pointAMs != null) SignalTeal else OutlineSubtle),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("POINT A", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextSecondary)
                                Text(loop.pointAMs?.let { formatPlaybackTime(it) } ?: "--:--", fontFamily = TelemetryFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = SignalTeal)
                            }
                        }
                        Card(
                            modifier = Modifier.weight(1f),
                            colors = CardDefaults.cardColors(containerColor = BackgroundInk),
                            border = BorderStroke(1.dp, if (loop.pointBMs != null) HifiBrass else OutlineSubtle),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("POINT B", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextSecondary)
                                Text(loop.pointBMs?.let { formatPlaybackTime(it) } ?: "--:--", fontFamily = TelemetryFontFamily, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = HifiBrass)
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { viewModel.setLoopPointA() },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, SignalTeal),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text("SET A (${formatPlaybackTime(state.playback.positionMs)})", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = SignalTeal)
                        }
                        OutlinedButton(
                            onClick = { viewModel.setLoopPointB() },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, HifiBrass),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text("SET B (${formatPlaybackTime(state.playback.positionMs)})", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = HifiBrass)
                        }
                    }

                    if (loop.isEnabled || loop.pointAMs != null || loop.pointBMs != null) {
                        OutlinedButton(
                            onClick = { viewModel.clearAbLoop() },
                            modifier = Modifier.fillMaxWidth(),
                            border = BorderStroke(1.dp, StatusError.copy(alpha = 0.6f)),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text("CLEAR A-B LOOP", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = StatusError)
                        }
                    }
                }
            }
        }
    }

    // ── MODAL 8: FOOBAR2000-STYLE LYRICS PROVIDER & WEB SCRAPER SEARCH MODAL ──
    if (showLyricsSourceModal) {
        val currentTrack = state.playback.currentTrack
        var searchQuery by remember(currentTrack?.id) {
            mutableStateOf(listOfNotNull(currentTrack?.title, currentTrack?.artist).joinToString(" "))
        }

        Dialog(
            onDismissRequest = { showLyricsSourceModal = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.85f)
                    .padding(vertical = 12.dp),
                shape = RoundedCornerShape(22.dp),
                color = SurfaceInk,
                border = BorderStroke(1.dp, SignalTeal.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Public, contentDescription = null, tint = SignalTeal, modifier = Modifier.size(22.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "LYRICS SOURCES & SCRAPER",
                                fontFamily = TelemetryFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = SignalTeal,
                                letterSpacing = 0.4.sp
                            )
                        }
                        IconButton(onClick = { showLyricsSourceModal = false }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                        }
                    }

                    Text(
                        "Auto-fetch & search synchronized lyrics across free online databases (Foobar2000 style).",
                        fontFamily = TelemetryFontFamily,
                        fontSize = 11.sp,
                        color = TextSecondary
                    )

                    // ── Provider Selector Chips ──
                    Text("DEFAULT PROVIDER", fontFamily = TelemetryFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = HifiBrass)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(
                            com.windstrom5.rebit.domain.model.LyricsProvider.AUTO to "Auto",
                            com.windstrom5.rebit.domain.model.LyricsProvider.LRCLIB to "LRCLIB",
                            com.windstrom5.rebit.domain.model.LyricsProvider.NETEASE to "163 Music",
                            com.windstrom5.rebit.domain.model.LyricsProvider.KUGOU to "Kugou"
                        ).forEach { (prov, label) ->
                            val isSelected = state.lyricsProvider == prov
                            FilterChip(
                                selected = isSelected,
                                onClick = { viewModel.setLyricsProvider(prov) },
                                label = { Text(label, fontFamily = TelemetryFontFamily, fontSize = 11.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = SignalTeal.copy(alpha = 0.2f),
                                    selectedLabelColor = SignalTeal
                                ),
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    HorizontalDivider(color = OutlineSubtle)

                    // ── Search Online Bar ──
                    Text("SEARCH ONLINE MATCHES", fontFamily = TelemetryFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SignalTeal)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            placeholder = { Text("Track and artist name...", fontFamily = TelemetryFontFamily, fontSize = 11.sp) },
                            singleLine = true,
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextPrimary),
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = SignalTeal,
                                unfocusedBorderColor = OutlineSubtle
                            )
                        )
                        Button(
                            onClick = { viewModel.searchOnlineLyrics(searchQuery) },
                            enabled = !state.isSearchingLyrics && searchQuery.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = SignalTeal),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.height(52.dp)
                        ) {
                            if (state.isSearchingLyrics) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = BackgroundInk, strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Search, contentDescription = "Search", tint = BackgroundInk)
                            }
                        }
                    }

                    // ── Search Results List ──
                    if (state.onlineSearchResults.isNotEmpty()) {
                        Text(
                            "FOUND ${state.onlineSearchResults.size} RELEASES (TAP TO APPLY):",
                            fontFamily = TelemetryFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = HifiBrass
                        )
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(state.onlineSearchResults) { item ->
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            viewModel.selectLyricsResult(item)
                                            showLyricsSourceModal = false
                                        },
                                    colors = CardDefaults.cardColors(containerColor = BackgroundInk),
                                    border = AudioPanelDefaults.border,
                                    shape = RoundedCornerShape(18.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(item.title, fontFamily = TelemetryFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text("${item.artist} ${item.album?.let { "• $it" } ?: ""}", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column(horizontalAlignment = Alignment.End) {
                                            Text(
                                                if (item.isSynced) "SYNCED" else "PLAIN",
                                                fontFamily = TelemetryFontFamily,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (item.isSynced) SignalTeal else StatusWarn
                                            )
                                            Text(
                                                item.provider.displayName,
                                                fontFamily = TelemetryFontFamily,
                                                fontSize = 11.sp,
                                                color = TextSecondary
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        Spacer(modifier = Modifier.weight(1f))
                    }

                    HorizontalDivider(color = OutlineSubtle)

                    // Quick Actions
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                viewModel.reloadLyricsOnline()
                                showLyricsSourceModal = false
                            },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, SignalTeal),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text("RELOAD AUTO", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = SignalTeal)
                        }
                        OutlinedButton(
                            onClick = {
                                lrcPickerLauncher.launch("*/*")
                                showLyricsSourceModal = false
                            },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, HifiBrass),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text("IMPORT .LRC", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = HifiBrass)
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.deleteCustomLyrics()
                                showLyricsSourceModal = false
                            },
                            modifier = Modifier.weight(1f),
                            border = BorderStroke(1.dp, StatusError),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text("CLEAR", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = StatusError)
                        }
                    }
                }
            }
        }
    }

    // ── MODAL 9: IEM & GENRE PRESET BROWSER (real AutoEq measurements) ──
    if (showAutoEqModal) {
        com.windstrom5.rebit.presentation.dialog.IemPresetBrowserDialog(
            onDismiss = { showAutoEqModal = false },
            onApply = { viewModel.applyParametricEq(it); showAutoEqModal = false }
        )
    }

    // ── MODAL 8: FOOBAR2000 ABX BLIND LISTENING TEST DIALOG ──
    if (showAbxModal) {
        AbxTestDialog(
            onDismiss = { showAbxModal = false },
            onPlaySampleA = {
                // Sample A: 100% Bit-Perfect Direct
                viewModel.selectEngine(EngineType.CUSTOM_USB_DIRECT)
            },
            onPlaySampleB = {
                // Sample B: Android AudioTrack Mixer
                viewModel.selectEngine(EngineType.ANDROID_AUDIOTRACK)
            }
        )
    }

    // ── MODAL 9: ON-DEVICE TENSORFLOW LITE AI NEURAL AUDIO STUDIO ──
    if (showAiStudioModal) {
        AiNeuralStudioDialog(
            aiState = state.aiState,
            onDismiss = { showAiStudioModal = false },
            onToggleAutoPilot = { viewModel.toggleAiAutoPilot(it) },
            onToggleDsee = { viewModel.toggleAiDsee(it) },
            onToggleVocalSuppression = { viewModel.toggleAiVocalSuppression(it) },
            onApplySuggestedEq = { gains ->
                viewModel.applyAiSuggestedEq(gains)
                if (!state.eqEnabled) viewModel.toggleEq()
                showAiStudioModal = false
            }
        )
    }

    // ── MODAL 10: AI KARAOKE & VOCAL ISOLATOR STUDIO CONSOLE ──
    if (showKaraokeStudioModal) {
        val context = androidx.compose.ui.platform.LocalContext.current
        KaraokeStudioDialog(
            aiState = state.aiState,
            scoreState = state.karaokeScore,
            isPlayingExternalStudioStem = state.playback.isPlayingExternalStudioStem,
            onDismiss = { showKaraokeStudioModal = false },
            onSetKaraokeMode = { viewModel.setKaraokeModeType(it) },
            onSetStrength = { viewModel.setKaraokeStrength(it) },
            onSetKeyShift = { viewModel.setKaraokeKeyShift(it) },
            onToggleBassPreservation = { viewModel.toggleKaraokeBassPreservation() },
            onToggleMicScoring = { viewModel.toggleKaraokeMicScoring(context, it) },
            onResetScore = { viewModel.resetKaraokeScore() },
            isInstrumentalCached = viewModel.isStemCached(com.windstrom5.rebit.domain.model.KaraokeModeType.INSTRUMENTAL_ONLY),
            isAcapellaCached = viewModel.isStemCached(com.windstrom5.rebit.domain.model.KaraokeModeType.VOCAL_ISOLATION),
            cacheSizeBytes = viewModel.getStemCacheSizeBytes(),
            stemProgress = stemProgress,
            onExtractInstrumental = { viewModel.extractInstrumentalStem(com.windstrom5.rebit.domain.model.KaraokeModeType.INSTRUMENTAL_ONLY) },
            onExtractAcapella = { viewModel.extractInstrumentalStem(com.windstrom5.rebit.domain.model.KaraokeModeType.VOCAL_ISOLATION) },
            onClearStemCache = { viewModel.clearStemCache() },
            onDismissStemProgress = { viewModel.dismissStemExtractionProgress() },
            lyrics = state.lyrics,
            activeLyricIndex = state.activeLyricIndex,
            positionMs = state.playback.positionMs,
            isPlaying = state.playback.isPlaying,
            lyricsProvider = state.lyricsProvider,
            onImportLrc = { lrcPickerLauncher.launch("*/*") },
            onSeekTo = { viewModel.seekTo(it) },
            onFindLyrics = {
                showKaraokeStudioModal = false
                showLyricsSourceModal = true
            }
        )
    }

    // ── MODAL 11: SHARED LISTENING (P2P SYNC) ──
    if (showSharedListeningModal) {
        com.windstrom5.rebit.presentation.dialog.SharedListeningDialog(
            state = state.sharedListening,
            onDismiss = { showSharedListeningModal = false },
            onStartHosting = { sessionName -> viewModel.startHostingSharedSession(sessionName) },
            onDiscoverSessions = { viewModel.discoverSharedSessions() },
            onJoinHost = { endpointId, localDisplayName -> viewModel.joinSharedSession(endpointId, localDisplayName) },
            onLeaveSession = { viewModel.leaveSharedSession() }
        )
    }
}

@Composable
private fun AudiophileToolButton(
    label: String,
    icon: ImageVector,
    active: Boolean,
    activeColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (active) activeColor.copy(alpha = 0.12f) else SurfacePanel
        ),
        border = BorderStroke(
            1.dp,
            if (active) activeColor.copy(alpha = 0.5f) else OutlineSubtle
        )
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (active) activeColor else TextSecondary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = label,
                fontFamily = TelemetryFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (active) activeColor else TextSecondary,
                letterSpacing = 0.5.sp,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

@Composable
private fun PowerampChainNode(
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    title: String,
    isLast: Boolean = false,
    content: @Composable () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        // Left column: Circular Icon and continuous vertical line with arrow down
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(iconBg),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(16.dp)
                )
            }
            if (!isLast) {
                Spacer(modifier = Modifier.height(3.dp))
                Text(
                    text = "↓",
                    fontFamily = TelemetryFontFamily,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.5f)
                )
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .height(34.dp)
                        .background(Color.White.copy(alpha = 0.25f))
                )
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        // Right column: Title and details
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) 8.dp else 18.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                fontFamily = TelemetryFontFamily,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            content()
        }
    }
}

@Composable
private fun PowerampSmallBadge(text: String, color: Color) {
    Text(
        text = text,
        fontFamily = TelemetryFontFamily,
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        color = color,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.35f)), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun DspToggleRow(title: String, subtitle: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                title,
                fontFamily = TelemetryFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.4.sp,
                color = TextPrimary
            )
            Text(subtitle, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = { onToggle() },
            colors = SwitchDefaults.colors(
                checkedThumbColor = HifiBrass,
                checkedTrackColor = HifiBrass.copy(alpha = 0.15f),
                uncheckedThumbColor = TextSecondary,
                uncheckedTrackColor = OutlineSubtle
            )
        )
    }
}

@Composable
private fun SpecBadge(text: String) {
    Text(
        text = text,
        fontFamily = TelemetryFontFamily,
        fontSize = 11.sp,
        color = SignalTeal,
        letterSpacing = 0.5.sp,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .background(SignalTeal.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

private fun formatPlaybackTime(ms: Long): String {
    val totalSeconds = (ms.coerceAtLeast(0L) / 1000)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

@Composable
private fun LiveFrequencyVisualizer(
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    barCount: Int = 32
) {
    val infiniteTransition = rememberInfiniteTransition(label = "freq_viz")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "phase"
    )

    Canvas(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfacePanel)
    ) {
        val barWidth = size.width / (barCount * 1.5f)
        val gap = barWidth * 0.5f

        for (i in 0 until barCount) {
            val normalizedHeight = if (isPlaying) {
                val freq1 = sin(phase + i * 0.4f) * 0.3f + 0.5f
                val freq2 = sin(phase * 1.7f + i * 0.25f) * 0.2f
                val freq3 = sin(phase * 0.6f + i * 0.6f) * 0.15f
                (freq1 + freq2 + freq3).coerceIn(0.05f, 1f)
            } else {
                0.03f
            }

            val barHeight = normalizedHeight * size.height * 0.85f
            val x = i * (barWidth + gap) + gap
            val y = size.height - barHeight

            drawRoundRect(
                color = SignalTeal.copy(alpha = normalizedHeight * 0.15f),
                topLeft = Offset(x - 1f, y - 1f),
                size = Size(barWidth + 2f, barHeight + 2f),
                cornerRadius = CornerRadius(2f)
            )
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(SignalTeal, SignalTealDim),
                    startY = y,
                    endY = y + barHeight
                ),
                topLeft = Offset(x, y),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(2f)
            )
        }
    }
}

@Composable
private fun AnalogVuMeter(
    amplitude: Float,
    channelLabel: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(95.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(BackgroundInk)
            .border(AudioPanelDefaults.border, RoundedCornerShape(6.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(SignalBlue.copy(alpha = 0.22f), Color.Transparent),
                    startY = 0f,
                    endY = height
                )
            )

            val centerX = width / 2f
            val centerY = height - 8f
            val radius = height * 0.9f

            for (i in 0..12) {
                val angleDeg = -145f + (i * 9f)
                val angleRad = Math.toRadians(angleDeg.toDouble())
                val tickStart = radius - 8f
                val tickEnd = radius + 2f

                val startX = centerX + (tickStart * cos(angleRad)).toFloat()
                val startY = centerY + (tickStart * sin(angleRad)).toFloat()
                val endX = centerX + (tickEnd * cos(angleRad)).toFloat()
                val endY = centerY + (tickEnd * sin(angleRad)).toFloat()

                val tickColor = when {
                    angleDeg > -70f -> StatusError
                    angleDeg > -90f -> StatusWarn
                    else -> TextSecondary.copy(alpha = 0.6f)
                }

                drawLine(
                    color = tickColor,
                    start = Offset(startX, startY),
                    end = Offset(endX, endY),
                    strokeWidth = if (i % 3 == 0) 3f else 1.5f
                )
            }

            val currentAngleDeg = -145f + (amplitude * 90f)
            val needleRad = Math.toRadians(currentAngleDeg.toDouble())
            val needleLength = radius - 5f

            val endX = centerX + (needleLength * cos(needleRad)).toFloat()
            val endY = centerY + (needleLength * sin(needleRad)).toFloat()

            drawLine(
                color = Color.Black.copy(alpha = 0.5f),
                start = Offset(centerX + 2f, centerY + 2f),
                end = Offset(endX + 2f, endY + 2f),
                strokeWidth = 2.5f
            )

            drawLine(
                color = if (currentAngleDeg > -70f) StatusError else HifiBrass,
                start = Offset(centerX, centerY),
                end = Offset(endX, endY),
                strokeWidth = 2f
            )

            drawCircle(color = SurfacePanel, radius = 10f, center = Offset(centerX, centerY))
            drawCircle(color = HifiBrass, radius = 4f, center = Offset(centerX, centerY))
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(bottom = 12.dp),
            verticalArrangement = Arrangement.Bottom,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = channelLabel,
                fontFamily = TelemetryFontFamily,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = TextSecondary.copy(alpha = 0.7f),
                letterSpacing = 0.4.sp
            )
        }
    }
}

@Composable
private fun EngineSwitchButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    selectedColor: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) selectedColor.copy(alpha = 0.15f) else Color.Transparent)
            .border(
                BorderStroke(
                    1.dp,
                    if (selected) selectedColor.copy(alpha = 0.5f) else Color.Transparent
                ),
                RoundedCornerShape(6.dp)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            fontFamily = TelemetryFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = if (selected) selectedColor else TextSecondary,
            letterSpacing = 0.5.sp
        )
    }
}
