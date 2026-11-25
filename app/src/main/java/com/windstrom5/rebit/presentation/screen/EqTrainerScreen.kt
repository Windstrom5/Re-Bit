package com.windstrom5.rebit.presentation.screen

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.windstrom5.rebit.domain.eqtrainer.EqAnswerResult
import com.windstrom5.rebit.domain.eqtrainer.EqDifficulty
import com.windstrom5.rebit.domain.eqtrainer.EqGameState
import com.windstrom5.rebit.domain.eqtrainer.EqMode
import com.windstrom5.rebit.domain.eqtrainer.PeakingBiquad
import com.windstrom5.rebit.domain.eqtrainer.formatHz
import com.windstrom5.rebit.domain.eqtrainer.shortHz
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
import com.windstrom5.rebit.presentation.viewmodel.EqTrainerViewModel
import org.koin.androidx.compose.koinViewModel
import kotlin.math.abs
import kotlin.math.log10

private val RoseHard = Color(0xFFFB7185)
private val SkyBlue = Color(0xFF60A5FA)
private val ROUNDS = listOf(5, 10, 15, 20)

private fun difficultyColor(d: EqDifficulty): Color = when (d) {
    EqDifficulty.BEGINNER -> SignalTeal
    EqDifficulty.EASY -> GoodGreen
    EqDifficulty.MEDIUM -> StatusWarn
    EqDifficulty.HARD -> RoseHard
}

/** Low frequencies teal, mids brass, upper mids blue, highs rose, so the answer grid reads like a keyboard. */
private fun bandColor(hz: Int): Color = when {
    hz < 250 -> SignalTeal
    hz < 2000 -> HifiBrass
    hz < 6000 -> SkyBlue
    else -> RoseHard
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EqTrainerScreen(onBack: () -> Unit, viewModel: EqTrainerViewModel = koinViewModel()) {
    // The test sound must stop when this screen goes away.
    DisposableEffect(Unit) { onDispose { viewModel.stopAudio() } }

    val ui by viewModel.state.collectAsStateWithLifecycle()
    val game = ui.game
    val haptics = LocalHapticFeedback.current
    var showResult by remember { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }

    LaunchedEffect(game.finished) {
        if (game.finished) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            showResult = true
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { ScreenTitle("EQ ear trainer", "Name the hidden frequency") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = TextSecondary)
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.startNewGame() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Restart", tint = TextSecondary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundInk, titleContentColor = TextPrimary)
            )
        },
        containerColor = BackgroundInk
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // ── Level ──
            SectionLabel("DIFFICULTY")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(EqDifficulty.entries) { d ->
                    DifficultyCard(d, selected = d == game.difficulty) { viewModel.startNewGame(difficulty = d) }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EqMode.entries.forEach { m ->
                    Pill(m.title, selected = m == game.mode) { viewModel.startNewGame(mode = m) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("ROUNDS", Modifier.weight(1f))
                ROUNDS.forEach { n -> Pill("$n", selected = n == game.totalRounds) { viewModel.startNewGame(rounds = n) } }
            }

            // ── Test sound ──
            Column(
                Modifier.fillMaxWidth().audioPanel(RoundedCornerShape(16.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SectionLabel("TEST SOUND")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SourcePill("Pink noise", Icons.Filled.GraphicEq, ui.clipTrack == null, Modifier.weight(1f), viewModel::usePinkNoise)
                    SourcePill("My library", Icons.Filled.LibraryMusic, ui.clipTrack != null, Modifier.weight(1f)) { showPicker = true }
                }
                if (ui.clipLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = SignalTeal, trackColor = SurfacePanelHigh)
                    Text("Reading an excerpt from your song\u2026", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
                ui.clipTrack?.let { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.title, color = TextPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(
                                listOfNotNull(t.artist, "20 s excerpt from ${clock(ui.clipStartMs)}").joinToString(" \u2022 "),
                                color = TextSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 1
                            )
                        }
                        TextButton(onClick = viewModel::newExcerpt) { Text("New excerpt", color = SignalTeal) }
                    }
                }
                if (ui.clipTrack == null && !ui.clipLoading) {
                    Text(
                        "Pink noise is the classic choice: it has energy at every frequency, so any band you change is easy to notice.",
                        color = TextSecondary, style = MaterialTheme.typography.bodySmall
                    )
                }
                ui.clipError?.let { Text(it, color = StatusError, style = MaterialTheme.typography.bodySmall) }
            }

            // ── Progress ──
            RoundDots(game)

            // ── Curve ──
            CurveCard(game, ui.lastResult)

            // ── Listening controls ──
            Column(
                Modifier.fillMaxWidth().audioPanel(RoundedCornerShape(20.dp)).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(if (ui.playing) StatusError else SignalTeal)
                            .clickable(enabled = !ui.clipLoading && !game.finished) { viewModel.togglePlay() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            if (ui.playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                            contentDescription = if (ui.playing) "Stop" else "Play",
                            tint = BackgroundInk, modifier = Modifier.size(38.dp)
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ToggleChip("EQ", ui.eqOn, SignalTeal, Modifier.weight(1f)) { viewModel.setEqOn(true) }
                            ToggleChip("FLAT", !ui.eqOn, HifiBrass, Modifier.weight(1f)) { viewModel.setEqOn(false) }
                        }
                        game.current?.let { q ->
                            Text(
                                "Hidden band: ${if (q.isBoost) "boost +" else "cut \u2212"}${abs(q.gainDb).toInt()} dB",
                                fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextSecondary
                            )
                        }
                    }
                }
                Text(
                    if (ui.playing) "Switch between EQ and FLAT to hear what changed, then pick the frequency below."
                    else "Press play. Start with the volume low; the sound fades in.",
                    color = TextSecondary, style = MaterialTheme.typography.bodySmall
                )
            }

            // ── Answer ──
            SectionLabel("WHICH FREQUENCY WAS CHANGED?")
            val canAnswer = ui.playing && !game.finished && !ui.clipLoading
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                game.difficulty.frequencies.forEach { hz ->
                    FreqButton(hz, canAnswer) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        viewModel.answer(hz)
                    }
                }
            }
            if (!canAnswer && !game.finished) {
                Text("Press play first. Answers unlock while the sound is playing.", color = TextSecondary, fontSize = 11.sp)
            }

            ui.lastResult?.let { FeedbackBanner(it) }

            Scorecard(game)
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPicker) {
        SongPickerDialog(
            tracks = ui.libraryTracks,
            onPick = { track -> showPicker = false; viewModel.chooseLibraryTrack(track) },
            onDismiss = { showPicker = false }
        )
    }
    if (showResult && game.finished) {
        EqResultDialog(
            game = game,
            newBest = ui.newBest,
            onPlayAgain = { showResult = false; viewModel.startNewGame() },
            onDismiss = { showResult = false }
        )
    }
}

// ─────────────────────────────── Pieces ───────────────────────────────

@Composable
private fun DifficultyCard(d: EqDifficulty, selected: Boolean, onClick: () -> Unit) {
    val accent = difficultyColor(d)
    Column(
        Modifier
            .width(150.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) accent.copy(alpha = 0.14f) else SurfacePanel)
            .border(BorderStroke(if (selected) 1.5.dp else 1.dp, if (selected) accent else OutlineSubtle), RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(d.title, color = if (selected) accent else TextPrimary, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            "\u00B1${d.gainDb.toInt()} dB \u00B7 Q ${d.q}", fontFamily = TelemetryFontFamily, fontSize = 10.sp, color = accent
        )
        Text("${d.frequencies.size} bands", fontFamily = TelemetryFontFamily, fontSize = 10.sp, color = TextSecondary)
        Text(d.blurb, color = TextSecondary, style = MaterialTheme.typography.bodySmall, lineHeight = 16.sp, maxLines = 3, minLines = 3)
    }
}

@Composable
private fun Pill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) HifiBrass.copy(alpha = 0.18f) else SurfacePanel)
            .border(BorderStroke(1.dp, if (selected) HifiBrass else OutlineSubtle), RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(
            label, fontFamily = TelemetryFontFamily, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) HifiBrass else TextSecondary
        )
    }
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, accent: Color, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) accent else SurfacePanelHigh)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label, fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Black, fontSize = 13.sp,
            color = if (selected) BackgroundInk else TextSecondary
        )
    }
}

@Composable
private fun FreqButton(hz: Int, enabled: Boolean, onClick: () -> Unit) {
    val c = bandColor(hz)
    Box(
        Modifier
            .width(70.dp)
            .height(52.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.copy(alpha = if (enabled) 0.16f else 0.06f))
            .border(BorderStroke(1.dp, c.copy(alpha = if (enabled) 0.65f else 0.2f)), RoundedCornerShape(14.dp))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            shortHz(hz), fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, fontSize = 15.sp,
            color = c.copy(alpha = if (enabled) 1f else 0.4f)
        )
    }
}

/** One bar per round: green exact, amber one band away, red wrong, brass outline for the round in play. */
@Composable
private fun RoundDots(game: EqGameState) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        for (i in 0 until game.totalRounds) {
            val r = game.history.getOrNull(i)
            val current = r == null && i == game.history.size
            Box(
                Modifier
                    .weight(1f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(
                        when {
                            r?.correct == true -> GoodGreen
                            r != null && r.bandsOff == 1 -> StatusWarn
                            r != null -> StatusError
                            current -> HifiBrass.copy(alpha = 0.25f)
                            else -> SurfacePanelHigh
                        }
                    )
                    .then(if (current) Modifier.border(BorderStroke(1.5.dp, HifiBrass), RoundedCornerShape(5.dp)) else Modifier)
            )
        }
    }
}

/**
 * Frequency axis (20 Hz to 20 kHz, logarithmic) with a tick for every choice. After a guess it draws the
 * EQ curve that was really applied and marks what you picked, so each round teaches something.
 */
@Composable
private fun CurveCard(game: EqGameState, last: EqAnswerResult?) {
    val measurer = rememberTextMeasurer()
    val labelStyle = remember { TextStyle(color = TextSecondary, fontSize = 9.sp, fontFamily = TelemetryFontFamily) }
    val choices = game.difficulty.frequencies
    val answerBand = remember(last) { last?.let { PeakingBiquad(it.question.frequencyHz.toFloat(), it.question.gainDb, it.question.q, 48_000) } }
    val accent = when {
        last == null -> TextSecondary
        last.correct -> GoodGreen
        last.bandsOff == 1 -> StatusWarn
        else -> StatusError
    }

    Column(Modifier.fillMaxWidth().audioPanel(RoundedCornerShape(18.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("LAST ANSWER, REVEALED", Modifier.weight(1f))
            if (last != null) {
                Text(
                    "${formatHz(last.question.frequencyHz)} ${if (last.question.isBoost) "+" else "\u2212"}${abs(last.question.gainDb).toInt()} dB",
                    fontFamily = TelemetryFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent
                )
            }
        }
        Canvas(Modifier.fillMaxWidth().height(130.dp)) {
            val w = size.width
            val h = size.height
            val padBottom = 14.dp.toPx()
            val plotH = h - padBottom
            val lo = log10(20.0)
            val hi = log10(20_000.0)
            fun x(hz: Double) = (((log10(hz) - lo) / (hi - lo)) * w).toFloat()
            val range = 15.0 // dB shown above and below the centre line
            fun y(db: Double) = (plotH / 2f - (db / range).toFloat() * (plotH / 2f - 6.dp.toPx()))

            // grid
            for (hz in listOf(100.0, 1000.0, 10_000.0)) {
                drawLine(Color.White.copy(alpha = 0.08f), Offset(x(hz), 0f), Offset(x(hz), plotH), 1.dp.toPx())
            }
            drawLine(Color.White.copy(alpha = 0.18f), Offset(0f, y(0.0)), Offset(w, y(0.0)), 1.dp.toPx())
            for ((hz, label) in listOf(100.0 to "100", 1000.0 to "1k", 10_000.0 to "10k")) {
                val t = measurer.measure(label, labelStyle)
                drawText(t, topLeft = Offset(x(hz) - t.size.width / 2f, h - t.size.height))
            }

            // a tick for each answer choice
            for (c in choices) {
                drawLine(bandColor(c).copy(alpha = 0.55f), Offset(x(c.toDouble()), plotH - 8.dp.toPx()), Offset(x(c.toDouble()), plotH), 2.dp.toPx())
            }

            val band = answerBand
            if (band != null && last != null) {
                val line = Path()
                val fill = Path()
                val n = 160
                for (i in 0..n) {
                    val hz = Math.pow(10.0, lo + (hi - lo) * i / n)
                    val px = x(hz)
                    val py = y(band.magnitudeDb(hz).coerceIn(-range, range))
                    if (i == 0) { line.moveTo(px, py); fill.moveTo(px, y(0.0)); fill.lineTo(px, py) } else { line.lineTo(px, py); fill.lineTo(px, py) }
                }
                fill.lineTo(w, y(0.0)); fill.close()
                drawPath(fill, accent.copy(alpha = 0.16f))
                drawPath(line, accent, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (!last.correct) {
                    val gx = x(last.guessedHz.toDouble())
                    drawLine(
                        Color.White.copy(alpha = 0.7f), Offset(gx, 0f), Offset(gx, plotH), 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                    )
                }
            }
        }
        Text(
            if (last == null) "After each guess, the curve that was really applied shows up here."
            else if (last.correct) "The curve shows the band that was boosted or cut."
            else "Dashed line: your guess (${formatHz(last.guessedHz)}). The curve is the real band.",
            color = TextSecondary, style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun FeedbackBanner(r: EqAnswerResult) {
    val color = when {
        r.correct -> GoodGreen
        r.bandsOff == 1 -> StatusWarn
        else -> StatusError
    }
    val what = "${formatHz(r.question.frequencyHz)} (${if (r.question.isBoost) "boost" else "cut"} ${abs(r.question.gainDb).toInt()} dB)"
    val text = when {
        r.correct -> "Correct! It was $what. +${r.points} points."
        r.bandsOff == 1 -> "So close: it was $what, you chose ${formatHz(r.guessedHz)}. +${r.points} points for being one band away."
        else -> "It was $what. You chose ${formatHz(r.guessedHz)}."
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(color.copy(alpha = 0.10f))
            .border(BorderStroke(1.dp, color.copy(alpha = 0.45f)), RoundedCornerShape(14.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(if (r.correct) Icons.Filled.CheckCircle else if (r.bandsOff == 1) Icons.Filled.Info else Icons.Filled.Close, null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(text, color = color, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, lineHeight = 18.sp)
    }
}

@Composable
private fun Scorecard(game: EqGameState) {
    Column(Modifier.fillMaxWidth().audioPanel(RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("SCORECARD", Modifier.weight(1f))
            Text(
                "ROUND ${game.round} / ${game.totalRounds}",
                fontFamily = TelemetryFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SignalTeal
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            AccuracyRing(
                fraction = game.accuracyPercent / 100f,
                color = if (game.accuracyPercent >= 75) GoodGreen else SignalTeal,
                label = "${game.accuracyPercent}%", diameter = 100.dp, stroke = 9.dp
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatRow("Score", "${game.score} / ${game.maxScore}", TextPrimary)
                StatRow("Correct", "${game.correctCount} / ${game.history.size}", GoodGreen)
                StatRow("Streak", "${game.streak}  (best ${game.bestStreak})", StatusWarn)
            }
        }
    }
}

@Composable
private fun EqResultDialog(game: EqGameState, newBest: Boolean, onPlayAgain: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val accent = if (game.accuracyPercent >= 75) HifiBrass else SignalTeal
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
                    Modifier.size(64.dp).clip(CircleShape).background(Brush.radialGradient(listOf(accent.copy(alpha = 0.4f), accent.copy(alpha = 0.06f)))),
                    contentAlignment = Alignment.Center
                ) { Icon(Icons.Filled.EmojiEvents, null, tint = accent, modifier = Modifier.size(34.dp)) }

                Text("ROUND COMPLETE", fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, letterSpacing = 2.sp, color = accent)
                Text("${game.difficulty.title} \u00B7 ${game.mode.title}", color = TextSecondary, fontSize = 12.sp)
                if (newBest) {
                    Text(
                        "NEW PERSONAL BEST", fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Black, fontSize = 11.sp, color = BackgroundInk,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(HifiBrass).padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }

                AccuracyRing(game.accuracyPercent / 100f, if (game.accuracyPercent >= 75) GoodGreen else SignalTeal, "${game.correctCount}/${game.totalRounds}", 132.dp, 12.dp)
                Text(game.rank, fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary, textAlign = TextAlign.Center)
                Text("Score ${game.score} of ${game.maxScore}  \u00B7  best streak ${game.bestStreak}", color = TextSecondary, fontSize = 12.sp)

                // Which bands you find hard
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    SectionLabel("HIT RATE BY BAND")
                    Row(Modifier.fillMaxWidth().height(70.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
                        game.perBand.forEach { (hz, stat) ->
                            val (hits, tries) = stat
                            val frac = if (tries == 0) 0f else hits.toFloat() / tries
                            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                                Box(
                                    Modifier.fillMaxWidth().fillMaxHeight(if (tries == 0) 0.05f else (0.12f + 0.88f * frac))
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (tries == 0) SurfacePanelHigh else bandColor(hz).copy(alpha = 0.35f + 0.65f * frac))
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        game.perBand.keys.forEach { hz ->
                            Text(shortHz(hz), Modifier.weight(1f), fontFamily = TelemetryFontFamily, fontSize = 7.sp, color = TextSecondary, textAlign = TextAlign.Center, maxLines = 1)
                        }
                    }
                }

                Button(
                    onClick = onPlayAgain,
                    colors = ButtonDefaults.buttonColors(containerColor = accent),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) { Text("PLAY AGAIN", fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, color = BackgroundInk) }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = {
                            val text = "I scored ${game.correctCount}/${game.totalRounds} (${game.accuracyPercent}%) on the ReBit EQ ear trainer " +
                                "(${game.difficulty.title}, ${game.mode.title}): ${game.rank}."
                            val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
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
