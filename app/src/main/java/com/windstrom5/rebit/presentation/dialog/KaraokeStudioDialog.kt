package com.windstrom5.rebit.presentation.dialog

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.windstrom5.rebit.domain.model.KaraokeModeType
import com.windstrom5.rebit.domain.model.LyricsData
import com.windstrom5.rebit.playback.ai.AiEngineState
import com.windstrom5.rebit.playback.ai.KaraokeScoreState
import com.windstrom5.rebit.playback.ai.LineScoreEntry
import com.windstrom5.rebit.playback.ai.StemExtractionProgress
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.theme.SignalBlue
import com.windstrom5.rebit.presentation.theme.SignalBlueGlow
import com.windstrom5.rebit.presentation.theme.StatusError
import com.windstrom5.rebit.presentation.theme.StatusGood
import com.windstrom5.rebit.presentation.theme.SurfaceInk
import com.windstrom5.rebit.presentation.theme.SurfacePanel
import com.windstrom5.rebit.presentation.theme.SurfacePanelHigh
import com.windstrom5.rebit.presentation.theme.TelemetryFontFamily
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary
import com.windstrom5.rebit.presentation.theme.TextTertiary

/**
 * Karaoke Studio — vocal suppression / acapella isolation, key shift, and
 * live mic scoring.
 *
 * Two separation tiers are shown here, honestly labeled (see
 * [com.windstrom5.rebit.playback.ai.SpectralSeparationEngine] and
 * [com.windstrom5.rebit.playback.ai.ExternalStemResolver] for what each
 * actually is under the hood):
 *  - Studio Quality: a real, externally-separated stem was found for this
 *    track — bit-perfect, zero DSP, the best this app can do.
 *  - Enhanced: no stem found, so a live adaptive-spectral-mask DSP heuristic
 *    is doing the separation in real time — solid, but not neural.
 */
@Composable
fun KaraokeStudioDialog(
    aiState: AiEngineState,
    scoreState: KaraokeScoreState,
    onDismiss: () -> Unit,
    onSetKaraokeMode: (KaraokeModeType) -> Unit,
    onSetStrength: (Float) -> Unit,
    onSetKeyShift: (Int) -> Unit,
    onToggleBassPreservation: () -> Unit,
    onToggleMicScoring: (Boolean) -> Unit,
    onResetScore: () -> Unit,
    isPlayingExternalStudioStem: Boolean = false,
    isInstrumentalCached: Boolean = false,
    isAcapellaCached: Boolean = false,
    cacheSizeBytes: Long = 0L,
    stemProgress: StemExtractionProgress? = null,
    onExtractInstrumental: () -> Unit = {},
    onExtractAcapella: () -> Unit = {},
    onClearStemCache: () -> Unit = {},
    onDismissStemProgress: () -> Unit = {},
    lyrics: LyricsData = LyricsData(),
    activeLyricIndex: Int = -1,
    positionMs: Long = 0L,
    isPlaying: Boolean = false,
    onSeekTo: (Long) -> Unit = {},
    onFindLyrics: () -> Unit = {},
    lyricsProvider: com.windstrom5.rebit.domain.model.LyricsProvider = com.windstrom5.rebit.domain.model.LyricsProvider.AUTO,
    onImportLrc: () -> Unit = {}
) {
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted -> if (isGranted) onToggleMicScoring(true) }

    val isActive = aiState.karaokeModeType != KaraokeModeType.OFF
    val isCurrentModeCached = when (aiState.karaokeModeType) {
        KaraokeModeType.INSTRUMENTAL_ONLY -> isInstrumentalCached
        KaraokeModeType.VOCAL_ISOLATION -> isAcapellaCached
        KaraokeModeType.OFF -> false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f)
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(20.dp),
            color = SurfaceInk,
            border = BorderStroke(1.dp, SignalBlue.copy(alpha = 0.35f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                DialogHeader(onDismiss)

                // The same lyrics panel as the media player: script modes (original / romaji / both),
                // tap a line to seek, find or import lyrics, and a full-screen view. A fixed height is
                // needed because this dialog scrolls and the panel has its own scrolling list.
                com.windstrom5.rebit.presentation.screen.player.LyricsPanel(
                    lyrics = lyrics,
                    activeIndex = activeLyricIndex,
                    provider = lyricsProvider,
                    accent = SignalBlue,
                    onSeekTo = onSeekTo,
                    onOpenSource = onFindLyrics,
                    onImportLrc = onImportLrc,
                    modifier = Modifier.fillMaxWidth().height(360.dp)
                )

                QualityTierCard(
                    isActive = isActive,
                    isExternalStudioStem = isPlayingExternalStudioStem,
                    isOnDeviceCache = isActive && isCurrentModeCached && !isPlayingExternalStudioStem,
                    isRendering = stemProgress != null && !stemProgress.isCompleted && stemProgress.error == null
                )

                ModeSelectorCard(
                    currentMode = aiState.karaokeModeType,
                    onSetKaraokeMode = onSetKaraokeMode
                )

                AnimatedVisibility(visible = isActive) {
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SeparationControlsCard(
                            mode = aiState.karaokeModeType,
                            strength = aiState.karaokeVocalSuppressionStrength,
                            preserveBass = aiState.karaokeBassPreservation,
                            keyShift = aiState.karaokeKeyShiftSemitones,
                            detectedNote = aiState.detectedMusicalNote,
                            onSetStrength = onSetStrength,
                            onToggleBassPreservation = onToggleBassPreservation,
                            onSetKeyShift = onSetKeyShift
                        )

                        StudioStemCard(
                            mode = aiState.karaokeModeType,
                            isInstrumentalCached = isInstrumentalCached,
                            isAcapellaCached = isAcapellaCached,
                            cacheSizeBytes = cacheSizeBytes,
                            stemProgress = stemProgress,
                            onExtractInstrumental = onExtractInstrumental,
                            onExtractAcapella = onExtractAcapella,
                            onClearStemCache = onClearStemCache,
                            onDismissStemProgress = onDismissStemProgress
                        )

                        MicScoringCard(
                            scoreState = scoreState,
                            onToggleMicScoring = { enabled ->
                                if (enabled) {
                                    val granted = ContextCompat.checkSelfPermission(
                                        context, Manifest.permission.RECORD_AUDIO
                                    ) == PackageManager.PERMISSION_GRANTED
                                    if (granted) onToggleMicScoring(true)
                                    else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    onToggleMicScoring(false)
                                }
                            },
                            onResetScore = onResetScore
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}

@Composable
private fun DialogHeader(onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Mic, contentDescription = null, tint = SignalBlue, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.width(10.dp))
            Text("Karaoke Studio", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
        }
    }
}

/** Shows which separation tier is actually driving the current audio, and why. */
@Composable
private fun QualityTierCard(isActive: Boolean, isExternalStudioStem: Boolean, isOnDeviceCache: Boolean, isRendering: Boolean = false) {
    val (accent, title, body) = when {
        !isActive -> Triple(TextTertiary, "Standard stereo", "Turn on a mode below to start removing or isolating vocals.")
        isRendering -> Triple(
            SignalBlue, "Rendering with on-device neural separation...",
            "This track hasn't been separated yet, so a real trained model (not a DSP approximation) is rendering it now — this happens once per track, then it's instant every time after. Live playback continues on the real-time filter in the meantime."
        )
        isExternalStudioStem -> Triple(
            StatusGood, "Studio Quality — bit-perfect",
            "Playing a real, pre-separated stem found for this track. No real-time processing runs at all, so this works even through the Custom USB Direct engine."
        )
        isOnDeviceCache -> Triple(
            StatusGood, "Neural Quality — on-device",
            "Playing a real trained-model separation rendered on this device — genuinely good separation, not a DSP approximation."
        )
        else -> Triple(
            SignalBlue, "Enhanced — real-time DSP",
            "No separated stem for this track (or this device/format can't run the neural model — see the on-device section below for why), so an adaptive spectral filter is separating it live. Solid, but not neural — this is the tier that struggles on dense arrangements with the vocal doubled by another instrument."
        )
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = SurfacePanel),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.4f))
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            if (isRendering) {
                androidx.compose.material3.CircularProgressIndicator(
                    modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = accent
                )
            } else {
                Icon(
                    if (isExternalStudioStem || isOnDeviceCache) Icons.Filled.CheckCircle else Icons.Filled.Info,
                    contentDescription = null, tint = accent, modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Spacer(modifier = Modifier.height(3.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun ModeSelectorCard(currentMode: KaraokeModeType, onSetKaraokeMode: (KaraokeModeType) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfacePanel), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Mode", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfacePanelHigh),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ModeChip("Off", currentMode == KaraokeModeType.OFF, Modifier.weight(1f)) {
                    onSetKaraokeMode(KaraokeModeType.OFF)
                }
                ModeChip("Instrumental", currentMode == KaraokeModeType.INSTRUMENTAL_ONLY, Modifier.weight(1f)) {
                    onSetKaraokeMode(KaraokeModeType.INSTRUMENTAL_ONLY)
                }
                ModeChip("Acapella", currentMode == KaraokeModeType.VOCAL_ISOLATION, Modifier.weight(1f)) {
                    onSetKaraokeMode(KaraokeModeType.VOCAL_ISOLATION)
                }
            }
        }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .padding(3.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) SignalBlue else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) Color(0xFF06090D) else TextSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SeparationControlsCard(
    mode: KaraokeModeType,
    strength: Float,
    preserveBass: Boolean,
    keyShift: Int,
    detectedNote: String,
    onSetStrength: (Float) -> Unit,
    onToggleBassPreservation: () -> Unit,
    onSetKeyShift: (Int) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfacePanel), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        if (mode == KaraokeModeType.VOCAL_ISOLATION) "Isolation strength" else "Vocal removal strength",
                        style = MaterialTheme.typography.titleSmall, color = TextPrimary
                    )
                    Text(
                        "${(strength * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = TelemetryFontFamily,
                        color = SignalBlue
                    )
                }
                Slider(
                    value = strength,
                    onValueChange = onSetStrength,
                    valueRange = 0f..1f,
                    colors = SliderDefaults.colors(
                        thumbColor = SignalBlue,
                        activeTrackColor = SignalBlue,
                        inactiveTrackColor = SurfacePanelHigh
                    )
                )
            }

            HorizontalDivider(color = SurfacePanelHigh)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Preserve bass & kick", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text("Keeps low end below 150Hz untouched", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                Switch(
                    checked = preserveBass,
                    onCheckedChange = { onToggleBassPreservation() },
                    colors = SwitchDefaults.colors(checkedThumbColor = SignalBlue, checkedTrackColor = SignalBlueGlow)
                )
            }

            HorizontalDivider(color = SurfacePanelHigh)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("Key shift", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text(
                        if (detectedNote != "--") "Detected key: $detectedNote" else "Detected key: unavailable",
                        style = MaterialTheme.typography.bodySmall, color = TextSecondary
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onSetKeyShift((keyShift - 1).coerceIn(-6, 6)) },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(SurfacePanelHigh)
                    ) { Icon(Icons.Filled.Remove, contentDescription = "Lower key", tint = TextPrimary, modifier = Modifier.size(18.dp)) }
                    Text(
                        (if (keyShift > 0) "+" else "") + "$keyShift",
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = TelemetryFontFamily,
                        color = TextPrimary,
                        modifier = Modifier.width(44.dp),
                        textAlign = TextAlign.Center
                    )
                    IconButton(
                        onClick = { onSetKeyShift((keyShift + 1).coerceIn(-6, 6)) },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(SurfacePanelHigh)
                    ) { Icon(Icons.Filled.Add, contentDescription = "Raise key", tint = TextPrimary, modifier = Modifier.size(18.dp)) }
                }
            }
        }
    }
}

@Composable
private fun StudioStemCard(
    mode: KaraokeModeType,
    isInstrumentalCached: Boolean,
    isAcapellaCached: Boolean,
    cacheSizeBytes: Long,
    stemProgress: StemExtractionProgress?,
    onExtractInstrumental: () -> Unit,
    onExtractAcapella: () -> Unit,
    onClearStemCache: () -> Unit,
    onDismissStemProgress: () -> Unit
) {
    val isCached = if (mode == KaraokeModeType.VOCAL_ISOLATION) isAcapellaCached else isInstrumentalCached
    val onExtract = if (mode == KaraokeModeType.VOCAL_ISOLATION) onExtractAcapella else onExtractInstrumental

    Card(colors = CardDefaults.cardColors(containerColor = SurfacePanel), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("On-device pre-render", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Text(
                "Renders once and caches the result, instead of processing live every time you play this track. Uses a real trained neural model (Spleeter, on-device) when your device and this track's format support it — genuinely good separation, not a DSP approximation; falls back to the same live filter as Enhanced mode, just pre-computed, for tracks it can't (mono sources, or anything not at 44.1kHz). For the best possible quality regardless of format, the desktop Studio Quality workflow (tools/README_DEMUCS.md) still wins when available — that's picked up automatically and this section becomes unnecessary for it.",
                style = MaterialTheme.typography.bodySmall, color = TextSecondary
            )

            if (stemProgress != null && !stemProgress.isCompleted && stemProgress.error == null) {
                Column(modifier = Modifier.padding(top = 4.dp)) {
                    LinearProgressIndicator(
                        progress = { stemProgress.progressPercent / 100f },
                        modifier = Modifier.fillMaxWidth(),
                        color = SignalBlue,
                        trackColor = SurfacePanelHigh
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(stemProgress.statusMessage, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
            } else if (stemProgress?.error != null) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(stemProgress.error, style = MaterialTheme.typography.bodySmall, color = StatusError, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismissStemProgress, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Filled.Close, contentDescription = "Dismiss", tint = TextSecondary, modifier = Modifier.size(16.dp))
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isCached) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = StatusGood, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Cached (${formatBytes(cacheSizeBytes)})", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                        }
                        StudioActionButton(label = "Clear cache", icon = Icons.Filled.Delete, accent = StatusError, onClick = onClearStemCache)
                    } else {
                        Text("Not yet rendered", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
                        StudioActionButton(label = "Pre-render", icon = Icons.Filled.Download, accent = SignalBlue, onClick = onExtract)
                    }
                }
            }
        }
    }
}

@Composable
private fun StudioActionButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, accent: Color, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(accent.copy(alpha = 0.15f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(15.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = accent)
    }
}

@Composable
private fun MicScoringCard(
    scoreState: KaraokeScoreState,
    onToggleMicScoring: (Boolean) -> Unit,
    onResetScore: () -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = SurfacePanel), shape = RoundedCornerShape(14.dp)) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (scoreState.isMicActive) Icons.Filled.Mic else Icons.Filled.MicOff,
                        contentDescription = null,
                        tint = if (scoreState.isMicActive) HifiBrass else TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sing-along scoring", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                }
                Switch(
                    checked = scoreState.isMicActive,
                    onCheckedChange = onToggleMicScoring,
                    colors = SwitchDefaults.colors(checkedThumbColor = HifiBrass, checkedTrackColor = HifiBrass.copy(alpha = 0.3f))
                )
            }

            AnimatedVisibility(visible = scoreState.isMicActive) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    HorizontalDivider(color = SurfacePanelHigh)

                    if (scoreState.currentLyricText.isNotBlank()) {
                        Column {
                            Text(
                                scoreState.currentLyricText,
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextPrimary,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            LinearProgressIndicator(
                                progress = { scoreState.currentLineAccuracyPercent / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = lineGradeColor(scoreState.currentLineAccuracyPercent.let {
                                    when { it >= 90 -> "PERFECT"; it >= 70 -> "GREAT"; it >= 50 -> "GOOD"; else -> "MISS" }
                                }),
                                trackColor = SurfacePanelHigh
                            )
                        }
                    }

                    if (scoreState.lineScores.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            for (entry in scoreState.lineScores.takeLast(12)) {
                                LineGradeBadge(entry)
                            }
                        }
                    }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        ScoreStat("Grade", scoreState.currentGrade)
                        ScoreStat("Score", scoreState.totalScore.toString())
                        ScoreStat("Combo", "x${scoreState.combo}")
                        ScoreStat("Best", "x${scoreState.maxCombo}")
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "You: ${scoreState.singerNote}  ·  Target: ${scoreState.targetNote}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = TelemetryFontFamily,
                            color = TextSecondary
                        )
                        Text(
                            "Reset",
                            style = MaterialTheme.typography.labelMedium,
                            color = SignalBlue,
                            modifier = Modifier.clickable(onClick = onResetScore)
                        )
                    }
                }
            }
        }
    }
}

private fun lineGradeColor(grade: String): Color = when (grade) {
    "PERFECT" -> StatusGood
    "GREAT" -> SignalBlue
    "GOOD" -> HifiBrass
    else -> StatusError
}

@Composable
private fun LineGradeBadge(entry: LineScoreEntry) {
    val color = lineGradeColor(entry.grade)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            "${entry.accuracyPercent}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = TelemetryFontFamily,
            color = color
        )
    }
}

@Composable
private fun ScoreStat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontFamily = TelemetryFontFamily, color = TextPrimary)
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextTertiary)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.0f KB".format(kb)
    val mb = kb / 1024.0
    return "%.1f MB".format(mb)
}
