package com.windstrom5.rebit.presentation.screen

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.windstrom5.rebit.domain.abx.AbxRoundRecord
import com.windstrom5.rebit.domain.abx.AbxSample
import com.windstrom5.rebit.domain.abx.AbxTestMode
import com.windstrom5.rebit.domain.abx.AbxTrialState
import com.windstrom5.rebit.presentation.components.LedDot
import com.windstrom5.rebit.presentation.components.ScreenTitle
import com.windstrom5.rebit.presentation.components.audioPanel
import com.windstrom5.rebit.presentation.theme.BackgroundInk
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.theme.OutlineSubtle
import com.windstrom5.rebit.presentation.theme.SignalTeal
import com.windstrom5.rebit.presentation.theme.StatusError
import com.windstrom5.rebit.presentation.theme.StatusWarn
import com.windstrom5.rebit.presentation.theme.SurfacePanel
import com.windstrom5.rebit.presentation.theme.SurfacePanelHigh
import com.windstrom5.rebit.presentation.theme.TelemetryFontFamily
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary
import com.windstrom5.rebit.domain.model.AudioTrackModel
import com.windstrom5.rebit.presentation.theme.TextTertiary
import com.windstrom5.rebit.presentation.viewmodel.AbxUiState
import com.windstrom5.rebit.presentation.viewmodel.AbxViewModel
import kotlinx.coroutines.flow.StateFlow
import org.koin.androidx.compose.koinViewModel

internal val GoodGreen = Color(0xFF44E088)
private val ExpertRose = Color(0xFFFB7185)
private val InsanePurple = Color(0xFFA78BFA)
private val ROUND_CHOICES = listOf(5, 10, 15, 20)

private fun difficultyColor(difficulty: String): Color = when (difficulty) {
    "MODERATE" -> SignalTeal
    "HARD" -> StatusWarn
    "EXPERT" -> ExpertRose
    "INSANE" -> InsanePurple
    else -> HifiBrass
}

private fun modeIcon(mode: AbxTestMode) = when (mode) {
    AbxTestMode.FLAC_VS_MP3_320, AbxTestMode.FLAC_VS_MP3_128 -> Icons.Filled.GraphicEq
    AbxTestMode.HIRES_24BIT_VS_16BIT -> Icons.Filled.Science
    AbxTestMode.AI_DSEE_VS_LOSSY -> Icons.Filled.Psychology
    AbxTestMode.TREBLE_CUT_20K_VS_15K -> Icons.Filled.VolumeUp
    AbxTestMode.CUSTOM_LIBRARY_TRACK -> Icons.Filled.Headphones
}

private fun sampleColor(sample: AbxSample): Color = when (sample) {
    AbxSample.A -> SignalTeal
    AbxSample.B -> StatusWarn
    AbxSample.X -> HifiBrass
    AbxSample.DIFF -> InsanePurple
    AbxSample.NONE -> TextSecondary
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AbxScreen(onBack: (() -> Unit)? = null, viewModel: AbxViewModel = koinViewModel()) {
    // Sound must not keep playing after leaving this screen (the bottom bar can leave it composed in the back stack).
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { viewModel.stopAudio() } }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val trial = state.trialState
    val mode = state.selectedMode
    val haptics = LocalHapticFeedback.current

    // Open the result card once when the test finishes. (Showing it from composition made it
    // reappear instantly after every dismissal while the test was still marked completed.)
    var showResultDialog by remember { mutableStateOf(false) }
    var showSongPicker by remember { mutableStateOf(false) }
    LaunchedEffect(trial.isCompleted) {
        if (trial.isCompleted) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            showResultDialog = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ScreenTitle("ABX blind test", "Can you hear the difference?") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextSecondary)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.startNewGame(mode, trial.totalRounds) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Restart test", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundInk, titleContentColor = TextPrimary)
            )
        },
        containerColor = BackgroundInk
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── Challenge picker ──
            SectionLabel("CHOOSE A CHALLENGE")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(AbxTestMode.entries) { m ->
                    ModeCard(m, selected = m == mode) { viewModel.selectMode(m) }
                }
            }

            ChallengeHero(mode)
            ListeningTip(mode)

            TestAudioCard(
                state = state,
                onPickSong = { showSongPicker = true },
                onNewExcerpt = viewModel::newExcerpt,
                onBuiltIn = viewModel::useBuiltInAudio
            )

            // ── Rounds + progress ──
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("ROUNDS", Modifier.weight(1f))
                ROUND_CHOICES.forEach { n ->
                    val selected = trial.totalRounds == n
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) HifiBrass.copy(alpha = 0.18f) else SurfacePanel)
                            .border(BorderStroke(1.dp, if (selected) HifiBrass else OutlineSubtle), RoundedCornerShape(50))
                            .clickable { viewModel.startNewGame(mode, n) }
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "$n", fontFamily = TelemetryFontFamily, fontSize = 12.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) HifiBrass else TextSecondary
                        )
                    }
                }
            }
            RoundTracker(trial)

            // ── Result of the last vote ──
            FeedbackBanner(state.lastFeedbackMessage)

            // ── Listening console ──
            SectionLabel("LISTENING CONSOLE")
            SpectrumPanel(
                spectrum = viewModel.spectrum,
                color = sampleColor(trial.activePlayingSample),
                active = trial.activePlayingSample != AbxSample.NONE,
                caption = when (trial.activePlayingSample) {
                    AbxSample.A -> "Playing A"
                    AbxSample.B -> "Playing B"
                    AbxSample.X -> "Playing mystery X"
                    AbxSample.DIFF -> "Playing the difference (A − B, +12 dB)"
                    AbxSample.NONE -> "Tap A, B or X to start"
                }
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AbxListeningPad("A", "Sample A", trial.activePlayingSample == AbxSample.A, SignalTeal, Modifier.weight(1f)) {
                    viewModel.selectSample(AbxSample.A)
                }
                AbxListeningPad("B", "Sample B", trial.activePlayingSample == AbxSample.B, StatusWarn, Modifier.weight(1f)) {
                    viewModel.selectSample(AbxSample.B)
                }
                AbxListeningPad("X", "Mystery X", trial.activePlayingSample == AbxSample.X, HifiBrass, Modifier.weight(1f)) {
                    viewModel.selectSample(AbxSample.X)
                }
            }
            OutlinedButton(
                onClick = { if (trial.activePlayingSample == AbxSample.DIFF) viewModel.stopAudio() else viewModel.playDifference() },
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, InsanePurple.copy(alpha = 0.6f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.GraphicEq, null, tint = InsanePurple, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    if (trial.activePlayingSample == AbxSample.DIFF) "Stop difference" else "Hear what was removed (training aid)",
                    color = InsanePurple, fontSize = 12.sp
                )
            }

            // ── Vote ──
            SectionLabel("WHICH SAMPLE IS X?")
            val canVote = state.hasHeardX && !trial.isCompleted
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VoteButton("X = A", SignalTeal, canVote, Modifier.weight(1f)) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.submitGuess(guessIsA = true)
                }
                VoteButton("X = B", StatusWarn, canVote, Modifier.weight(1f)) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.submitGuess(guessIsA = false)
                }
            }
            if (!canVote) {
                Text(
                    when {
                        trial.isCompleted -> "Test complete. Restart to try again."
                        else -> "Listen to Mystery X first, then vote."
                    },
                    color = TextSecondary, fontSize = 11.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                )
            }

            Scorecard(trial, onShowResult = { showResultDialog = true })
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showSongPicker) {
        SongPickerDialog(
            tracks = state.libraryTracks,
            onPick = { track -> showSongPicker = false; viewModel.chooseLibraryTrack(track) },
            onDismiss = { showSongPicker = false }
        )
    }

    if (showResultDialog) {
        ResultDialog(
            mode = mode,
            trial = trial,
            onPlayAgain = { showResultDialog = false; viewModel.startNewGame(mode, trial.totalRounds) },
            onDismiss = { showResultDialog = false }
        )
    }
}

// ─────────────────────────────── Pieces ───────────────────────────────

@Composable
internal fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, fontFamily = TelemetryFontFamily, fontSize = 11.sp, letterSpacing = 1.2.sp, fontWeight = FontWeight.Bold, color = TextSecondary)
}

@Composable
private fun ModeCard(mode: AbxTestMode, selected: Boolean, onClick: () -> Unit) {
    val accent = difficultyColor(mode.difficulty)
    Column(
        Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) accent.copy(alpha = 0.14f) else SurfacePanel)
            .border(BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) accent else OutlineSubtle), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(
            Modifier.size(34.dp).clip(CircleShape).background(accent.copy(alpha = 0.18f)),
            contentAlignment = Alignment.Center
        ) { Icon(modeIcon(mode), null, tint = accent, modifier = Modifier.size(18.dp)) }
        Text(
            mode.title, style = MaterialTheme.typography.labelLarge, color = if (selected) TextPrimary else TextSecondary,
            maxLines = 2, minLines = 2
        )
        Text(mode.difficulty, fontFamily = TelemetryFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = accent)
    }
}

@Composable
private fun ChallengeHero(mode: AbxTestMode) {
    val accent = difficultyColor(mode.difficulty)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.22f), SurfacePanel)))
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.35f)), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(modeIcon(mode), null, tint = accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(mode.title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text(
                mode.difficulty, fontFamily = TelemetryFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = accent,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(accent.copy(alpha = 0.16f)).padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        Text(mode.description, color = TextSecondary, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleTag("A", mode.sampleATitle, SignalTeal)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SampleTag("B", mode.sampleBTitle, StatusWarn)
        }
    }
}

@Composable
private fun SampleTag(letter: String, title: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(20.dp).clip(CircleShape).background(color.copy(alpha = 0.2f)),
            contentAlignment = Alignment.Center
        ) { Text(letter, fontFamily = TelemetryFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Black, color = color) }
        Spacer(Modifier.width(8.dp))
        Text(title, color = TextPrimary, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ListeningTip(mode: AbxTestMode) {
    var expanded by remember(mode) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().audioPanel(RoundedCornerShape(16.dp)).clickable { expanded = !expanded }.padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Info, null, tint = HifiBrass, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
            Text("What to listen for", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Icon(if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown, null, tint = TextSecondary)
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Text(
                mode.whatToListenFor, color = TextSecondary, style = MaterialTheme.typography.bodySmall,
                lineHeight = 18.sp, modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

/** One dot per round: green/red once answered, a brass ring for the round in play. */
@Composable
private fun RoundTracker(trial: AbxTrialState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until trial.totalRounds) {
            val record: AbxRoundRecord? = trial.history.getOrNull(i)
            val current = record == null && i == trial.history.size
            Box(
                Modifier
                    .weight(1f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        when {
                            record?.isCorrect == true -> GoodGreen
                            record != null -> StatusError
                            current -> HifiBrass.copy(alpha = 0.25f)
                            else -> SurfacePanelHigh
                        }
                    )
                    .then(if (current) Modifier.border(BorderStroke(1.5.dp, HifiBrass), RoundedCornerShape(5.dp)) else Modifier)
            )
        }
    }
}

@Composable
private fun FeedbackBanner(message: String?) {
    val verdict = when {
        message == null -> null
        message.startsWith("✓") -> true
        message.startsWith("✗") -> false
        else -> null
    }
    val color = when (verdict) { true -> GoodGreen; false -> StatusError; null -> TextSecondary }
    AnimatedVisibility(message != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(color.copy(alpha = 0.10f))
                .border(BorderStroke(1.dp, color.copy(alpha = 0.45f)), RoundedCornerShape(14.dp))
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                when (verdict) { true -> Icons.Filled.CheckCircle; false -> Icons.Filled.Close; null -> Icons.Filled.Info },
                null, tint = color, modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(10.dp))
            Text(
                (message ?: "").removePrefix("✓ ").removePrefix("✗ "),
                color = if (verdict == null) TextPrimary else color,
                style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp,
                fontWeight = if (verdict == null) FontWeight.Normal else FontWeight.SemiBold
            )
        }
    }
}

/** Live 16-band spectrum from the ABX player. Collected here so only this panel redraws at audio rate. */
@Composable
private fun SpectrumPanel(spectrum: StateFlow<List<Float>>, color: Color, active: Boolean, caption: String) {
    val bands by spectrum.collectAsStateWithLifecycle()
    val tint by animateColorAsStateCompat(color)
    Column(Modifier.fillMaxWidth().audioPanel(RoundedCornerShape(18.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(Modifier.fillMaxWidth().height(76.dp)) {
            val n = bands.size.coerceAtLeast(1)
            val gap = 6.dp.toPx()
            val barW = (size.width - gap * (n - 1)) / n
            bands.forEachIndexed { i, v ->
                val h = v.coerceIn(0.04f, 1f) * size.height
                drawRoundRect(
                    color = tint.copy(alpha = if (active) 0.95f else 0.30f),
                    topLeft = Offset(i * (barW + gap), size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (active) { LedDot(tint, size = 7.dp); Spacer(Modifier.width(8.dp)) }
            Text(caption, fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = if (active) tint else TextSecondary)
        }
    }
}

@Composable
private fun animateColorAsStateCompat(target: Color) =
    androidx.compose.animation.animateColorAsState(target, tween(250), label = "spectrum_tint")

@Composable
private fun AbxListeningPad(
    letter: String,
    subtitle: String,
    isPlaying: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val pulse = rememberInfiniteTransition(label = "pad_pulse").animateFloat(
        initialValue = 1f, targetValue = 1.045f,
        animationSpec = infiniteRepeatable(tween(650, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pad_scale"
    )
    Box(
        modifier = modifier
            .height(124.dp)
            .graphicsLayer { val s = if (isPlaying) pulse.value else 1f; scaleX = s; scaleY = s }
            .audioPanel(RoundedCornerShape(22.dp))
            .then(if (isPlaying) Modifier.background(color.copy(alpha = 0.16f)).border(1.5.dp, color, RoundedCornerShape(22.dp)) else Modifier)
            .clickable { onClick() }
            .padding(8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(if (isPlaying) color else Color.White.copy(alpha = 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                if (isPlaying && letter.isNotEmpty()) {
                    Text(letter, fontFamily = TelemetryFontFamily, fontSize = 22.sp, fontWeight = FontWeight.Black, color = BackgroundInk)
                } else {
                    Text(letter, fontFamily = TelemetryFontFamily, fontSize = 22.sp, fontWeight = FontWeight.Black, color = color)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(subtitle, style = MaterialTheme.typography.labelLarge, color = if (isPlaying) color else TextPrimary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isPlaying) { LedDot(color, size = 6.dp); Spacer(Modifier.width(5.dp)) }
                else Icon(Icons.Filled.PlayArrow, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                Text(if (isPlaying) "Playing" else "Tap", style = MaterialTheme.typography.labelMedium, color = if (isPlaying) color else TextSecondary)
            }
        }
    }
}

@Composable
private fun VoteButton(label: String, color: Color, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = color, contentColor = BackgroundInk,
            disabledContainerColor = color.copy(alpha = 0.18f), disabledContentColor = TextSecondary
        ),
        shape = RoundedCornerShape(16.dp)
    ) {
        Text(label, fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Black, fontSize = 16.sp)
    }
}

@Composable
internal fun AccuracyRing(fraction: Float, color: Color, label: String, diameter: Dp, stroke: Dp) {
    val animated by animateFloatAsState(fraction.coerceIn(0f, 1f), tween(700, easing = FastOutSlowInEasing), label = "ring")
    Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = stroke.toPx()
            val arcSize = Size(this.size.width - w, this.size.height - w)
            val topLeft = Offset(w / 2f, w / 2f)
            drawArc(Color.White.copy(alpha = 0.08f), -90f, 360f, false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
            if (animated > 0f) {
                drawArc(color, -90f, 360f * animated, false, topLeft, arcSize, style = Stroke(w, cap = StrokeCap.Round))
            }
        }
        Text(label, fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Black, fontSize = (diameter.value * 0.24f).sp, color = TextPrimary)
    }
}

@Composable
private fun Scorecard(trial: AbxTrialState, onShowResult: () -> Unit) {
    val done = if (trial.isCompleted) trial.totalRounds else (trial.currentRound - 1).coerceAtLeast(0)
    val significant = trial.pValue < 0.05 && done > 0
    Column(Modifier.fillMaxWidth().audioPanel(RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("SCORECARD", Modifier.weight(1f))
            Text(
                "ROUND ${trial.currentRound.coerceAtMost(trial.totalRounds)} / ${trial.totalRounds}",
                fontFamily = TelemetryFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = HifiBrass
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            AccuracyRing(
                fraction = trial.accuracyPercent / 100f,
                color = if (trial.accuracyPercent >= 80f) GoodGreen else HifiBrass,
                label = "${trial.accuracyPercent.toInt()}%", diameter = 112.dp, stroke = 10.dp
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatRow("Correct", "${trial.correctGuesses} / $done", TextPrimary)
                StatRow("Streak", "${trial.currentStreak}  (best ${trial.maxStreak})", StatusWarn)
                StatRow("p-value", if (done > 0) "%.3f".format(trial.pValue) else "—", if (significant) GoodGreen else TextSecondary)
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background((if (significant) HifiBrass else TextSecondary).copy(alpha = 0.10f))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(trial.earRank, fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = if (significant) HifiBrass else TextPrimary)
            Text(trial.earRankDescription, color = TextSecondary, style = MaterialTheme.typography.bodySmall, lineHeight = 17.sp)
        }
        if (trial.isCompleted) {
            TextButton(onClick = onShowResult, modifier = Modifier.fillMaxWidth()) { Text("View result card", color = HifiBrass) }
        }
    }
}

@Composable
internal fun StatRow(title: String, value: String, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = TextSecondary)
        Text(value, fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = color)
    }
}

@Composable
private fun ResultDialog(mode: AbxTestMode, trial: AbxTrialState, onPlayAgain: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val significant = trial.pValue < 0.05
    val accent = if (significant) HifiBrass else TextSecondary
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).clip(RoundedCornerShape(26.dp)),
            color = SurfacePanel,
            border = BorderStroke(1.dp, accent.copy(alpha = 0.6f))
        ) {
            Column(
                Modifier.background(Brush.verticalGradient(listOf(accent.copy(alpha = 0.18f), SurfacePanel))).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Box(
                    Modifier.size(64.dp).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(accent.copy(alpha = 0.4f), accent.copy(alpha = 0.06f)))),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.EmojiEvents, null, tint = accent, modifier = Modifier.size(34.dp)) }

                Text("TEST COMPLETE", fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp, color = accent)
                Text(mode.title, color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center)

                AccuracyRing(
                    trial.accuracyPercent / 100f,
                    if (trial.accuracyPercent >= 80f) GoodGreen else HifiBrass,
                    "${trial.correctGuesses}/${trial.totalRounds}", diameter = 132.dp, stroke = 12.dp
                )

                Text(trial.earRank, fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = TextPrimary, textAlign = TextAlign.Center)
                Text("p = %.4f".format(trial.pValue), fontFamily = TelemetryFontFamily, fontSize = 12.sp, color = TextSecondary)

                // Per-round strip
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    trial.history.forEach { r ->
                        Box(Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(4.dp)).background(if (r.isCorrect) GoodGreen else StatusError))
                    }
                }

                Text(trial.earRankDescription, color = TextSecondary, fontSize = 12.sp, textAlign = TextAlign.Center, lineHeight = 17.sp)

                Button(
                    onClick = onPlayAgain,
                    colors = ButtonDefaults.buttonColors(containerColor = HifiBrass),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("PLAY AGAIN", fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, color = BackgroundInk) }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            val text = "I scored ${trial.correctGuesses}/${trial.totalRounds} (${trial.accuracyPercent.toInt()}%) " +
                                "on the ReBit ABX blind test: ${mode.title}. p = ${"%.3f".format(trial.pValue)}."
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, text)
                            }
                            context.startActivity(Intent.createChooser(send, "Share result"))
                        },
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, OutlineSubtle),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.Share, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Share", color = TextSecondary)
                    }
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Close", color = TextSecondary) }
                }
            }
        }
    }
}

// ───────────────────────── Library song as test audio ─────────────────────────

internal fun clock(ms: Long): String {
    val total = (ms / 1000).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

@Composable
private fun TestAudioCard(state: AbxUiState, onPickSong: () -> Unit, onNewExcerpt: () -> Unit, onBuiltIn: () -> Unit) {
    val usingSong = state.clipTrack != null
    val needsSong = state.selectedMode == AbxTestMode.CUSTOM_LIBRARY_TRACK
    Column(
        Modifier.fillMaxWidth().audioPanel(RoundedCornerShape(16.dp)).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionLabel("TEST AUDIO")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SourcePill("Built-in music", Icons.Filled.GraphicEq, !usingSong, Modifier.weight(1f), onBuiltIn)
            SourcePill("My library", Icons.Filled.LibraryMusic, usingSong, Modifier.weight(1f), onPickSong)
        }

        if (state.clipLoading) {
            LinearProgressIndicator(Modifier.fillMaxWidth(), color = HifiBrass, trackColor = SurfacePanelHigh)
            Text("Reading an excerpt from your song\u2026", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }

        state.clipTrack?.let { t ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = TextPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        listOfNotNull(t.artist, "20 s excerpt from ${clock(state.clipStartMs)}").joinToString(" \u2022 "),
                        color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1
                    )
                }
                TextButton(onClick = onNewExcerpt) { Text("New excerpt", color = HifiBrass) }
                TextButton(onClick = onPickSong) { Text("Change", color = HifiBrass) }
            }
        }

        if (needsSong && !usingSong && !state.clipLoading) {
            Text(
                "This challenge tests one of your own songs. Tap My library to choose a FLAC or WAV.",
                color = HifiBrass, style = MaterialTheme.typography.bodySmall
            )
        }
        state.clipError?.let { Text(it, color = StatusError, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun SourcePill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) HifiBrass.copy(alpha = 0.16f) else SurfacePanelHigh)
            .border(BorderStroke(1.dp, if (selected) HifiBrass else OutlineSubtle), RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (selected) HifiBrass else TextSecondary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (selected) HifiBrass else TextSecondary, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
internal fun SongPickerDialog(tracks: List<AudioTrackModel>, onPick: (AudioTrackModel) -> Unit, onDismiss: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val shown = remember(tracks, query) {
        val q = query.trim().lowercase()
        (if (q.isEmpty()) tracks else tracks.filter { it.title.lowercase().contains(q) || (it.artist?.lowercase()?.contains(q) == true) })
            .take(300)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.8f).clip(RoundedCornerShape(24.dp)),
            color = SurfacePanel,
            border = BorderStroke(1.dp, OutlineSubtle)
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Choose a song", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = TextSecondary) }
                }
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search title or artist", color = TextTertiary) },
                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextSecondary) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                        focusedBorderColor = HifiBrass, unfocusedBorderColor = OutlineSubtle, cursorColor = HifiBrass
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (tracks.isEmpty()) {
                    Text("No FLAC or WAV songs in your library yet. Scan your library first.", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(shown, key = { it.id }) { t ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(SurfacePanelHigh)
                                .clickable { onPick(t) }
                                .padding(12.dp)
                        ) {
                            Text(t.title, color = TextPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(
                                listOfNotNull(t.artist, "${t.format.name} ${t.pcm.bitDepth}-bit / ${t.pcm.sampleRateHz / 1000.0} kHz").joinToString(" \u2022 "),
                                color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}
