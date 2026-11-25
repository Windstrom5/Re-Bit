package com.windstrom5.rebit.presentation.screen

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.windstrom5.rebit.domain.audiophile.ChannelSel
import com.windstrom5.rebit.domain.audiophile.SignalConfig
import com.windstrom5.rebit.domain.audiophile.SignalMode
import com.windstrom5.rebit.domain.audiophile.Waveform
import com.windstrom5.rebit.presentation.components.AudioPanel
import com.windstrom5.rebit.presentation.components.ChoiceChip
import com.windstrom5.rebit.presentation.components.EngravedLabel
import com.windstrom5.rebit.presentation.components.LedDot
import com.windstrom5.rebit.presentation.components.ModernTopBar
import com.windstrom5.rebit.presentation.theme.BackgroundInk
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.theme.SignalBlue
import com.windstrom5.rebit.presentation.theme.SignalTeal
import com.windstrom5.rebit.presentation.theme.TelemetryFontFamily
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary
import com.windstrom5.rebit.presentation.viewmodel.PlayerViewModel
import com.windstrom5.rebit.presentation.viewmodel.TestSignalsViewModel
import org.koin.androidx.compose.koinViewModel
import kotlin.math.ln
import kotlin.math.pow

private val FREQ_PRESETS = listOf(30.0, 50.0, 100.0, 440.0, 1000.0, 4000.0, 10_000.0, 16_000.0)

private fun freqLabel(f: Double) = if (f >= 1000) "%.2f kHz".format(f / 1000) else "%.0f Hz".format(f)

/** Slider position (0..1) <-> frequency, logarithmic so every octave gets equal travel. */
private fun posToFreq(p: Float) = SignalConfig.MIN_FREQ * (SignalConfig.MAX_FREQ / SignalConfig.MIN_FREQ).pow(p.toDouble())
private fun freqToPos(f: Double) = (ln(f / SignalConfig.MIN_FREQ) / ln(SignalConfig.MAX_FREQ / SignalConfig.MIN_FREQ)).toFloat().coerceIn(0f, 1f)

/** Test tones, sweep, pink noise, channel and polarity checks for verifying your system. */
@Composable
fun TestSignalsScreen(
    onBack: (() -> Unit)? = null,
    viewModel: TestSignalsViewModel = koinViewModel(),
    playerViewModel: PlayerViewModel = koinViewModel()
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val cfg = ui.config

    // Never leave a test tone running when the screen is left or the app goes to the background.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) viewModel.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            viewModel.stop()
        }
    }

    Scaffold(
        containerColor = BackgroundInk,
        topBar = { ModernTopBar(title = "Listening lab", subtitle = "Test signals for your system", onBack = onBack) }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                AudioPanel {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                        LedDot(HifiBrass, Modifier.padding(top = 5.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text("Start quietly", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                            Text(
                                "Test signals are constant and can be loud. Turn your volume down first; the level here is capped at −6 dBFS. " +
                                    "They play through Android's mixer, so they test your headphones and speakers, not USB-direct output.",
                                style = MaterialTheme.typography.bodySmall, color = TextSecondary
                            )
                        }
                    }
                }
            }

            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(SignalMode.entries) { m ->
                        ChoiceChip(m.label, cfg.mode == m, { viewModel.update { it.copy(mode = m) } }, accent = HifiBrass)
                    }
                }
            }

            item {
                Text(cfg.mode.blurb, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, modifier = Modifier.padding(horizontal = 4.dp))
            }

            if (cfg.mode == SignalMode.TONE) {
                item {
                    AudioPanel {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            EngravedLabel("Frequency")
                            Text(
                                freqLabel(cfg.freqHz),
                                fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Black, fontSize = 34.sp, color = HifiBrass
                            )
                            Slider(
                                value = freqToPos(cfg.freqHz),
                                onValueChange = { p -> viewModel.update { it.copy(freqHz = posToFreq(p)) } },
                                colors = sliderColors()
                            )
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(FREQ_PRESETS) { f ->
                                    ChoiceChip(freqLabel(f), kotlin.math.abs(cfg.freqHz - f) < 0.5, { viewModel.update { it.copy(freqHz = f) } }, accent = SignalBlue)
                                }
                            }
                            EngravedLabel("Waveform")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(Waveform.entries) { w ->
                                    ChoiceChip(w.label, cfg.waveform == w, { viewModel.update { it.copy(waveform = w) } }, accent = SignalBlue)
                                }
                            }
                        }
                    }
                }
            }

            if (cfg.mode == SignalMode.TONE || cfg.mode == SignalMode.PINK_NOISE) {
                item {
                    AudioPanel {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            EngravedLabel("Channels")
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(ChannelSel.entries) { c ->
                                    ChoiceChip(c.label, cfg.channels == c, { viewModel.update { it.copy(channels = c) } }, accent = SignalTeal)
                                }
                            }
                        }
                    }
                }
            }

            item {
                AudioPanel {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { EngravedLabel("Level") }
                            Text(
                                "%.0f dBFS".format(cfg.levelDb),
                                fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, fontSize = 16.sp,
                                color = if (cfg.levelDb > -20.0) Color(0xFFFF6B6B) else TextPrimary
                            )
                        }
                        Slider(
                            value = cfg.levelDb.toFloat(),
                            onValueChange = { v -> viewModel.update { it.copy(levelDb = v.toDouble()) } },
                            valueRange = SignalConfig.MIN_LEVEL_DB.toFloat()..SignalConfig.MAX_LEVEL_DB.toFloat(),
                            colors = sliderColors()
                        )
                        Text("Quiet", style = MaterialTheme.typography.labelSmall, color = TextSecondary)
                    }
                }
            }

            item {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        onClick = {
                            if (ui.playing) viewModel.stop()
                            else {
                                // Two sound sources at once would corrupt the test: pause the music first.
                                if (playerViewModel.uiState.value.playback.isPlaying) playerViewModel.pause()
                                viewModel.start()
                            }
                        },
                        shape = CircleShape,
                        color = if (ui.playing) Color(0xFFFF6B6B) else HifiBrass,
                        modifier = Modifier.size(84.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                if (ui.playing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                                if (ui.playing) "Stop test signal" else "Start test signal",
                                tint = Color(0xFF0B0B10), modifier = Modifier.size(42.dp)
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LedDot(if (ui.playing) SignalTeal else TextSecondary.copy(alpha = 0.4f))
                        Spacer(Modifier.width(8.dp))
                        Text(if (ui.playing) "Playing" else "Stopped", style = MaterialTheme.typography.labelLarge, color = if (ui.playing) SignalTeal else TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = HifiBrass,
    activeTrackColor = HifiBrass.copy(alpha = 0.8f),
    inactiveTrackColor = Color.White.copy(alpha = 0.16f)
)
