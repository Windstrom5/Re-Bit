package com.windstrom5.rebit.presentation.screen.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.windstrom5.rebit.playback.dsp.LevelMeter
import com.windstrom5.rebit.playback.dsp.SpectrumAnalyzer
import com.windstrom5.rebit.presentation.components.StereoLevelMeter
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.theme.MeterAmber
import com.windstrom5.rebit.presentation.theme.MeterRed
import com.windstrom5.rebit.presentation.theme.SignalTeal
import com.windstrom5.rebit.presentation.theme.TelemetryFontFamily
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary
import com.windstrom5.rebit.presentation.theme.TextTertiary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val RANGE_DB = 72f
private const val FALL_DB_PER_SEC = 36f
private const val HOLD_NANOS = 1_200_000_000L
private const val FRAME_NANOS = 33_000_000L // ~30 analyses per second

/** Holds the animated analyzer state; values are read inside Canvas draws only. */
private class AnalyzerState(bands: Int) {
    val level = FloatArray(bands) { -RANGE_DB }
    val hold = FloatArray(bands) { -RANGE_DB }
    val holdAt = LongArray(bands)
    var tick by mutableIntStateOf(0) // bumps to trigger a redraw
    var correlation by mutableFloatStateOf(0f)
}

/**
 * Live 1/3-octave real-time analyzer + stereo correlation + L/R meter — the "Analyzer" page of
 * Now Playing. Analysis runs off the main thread on a copy of the engine's recent output.
 */
@Composable
internal fun AnalyzerPage(
    active: Boolean,
    accent: Color,
    recentSamples: (FloatArray, FloatArray) -> Int,
    sampleRateHz: () -> Int,
    levels: () -> LevelMeter.Levels?,
    modifier: Modifier = Modifier
) {
    val bands = SpectrumAnalyzer.BAND_CENTERS.size
    val state = remember { AnalyzerState(bands) }

    LaunchedEffect(active) {
        val bufL = FloatArray(LevelMeter.HISTORY)
        val bufR = FloatArray(LevelMeter.HISTORY)
        var analyzer: SpectrumAnalyzer? = null
        var analyzerRate = 0
        var last = 0L
        var lastAnalysis = 0L
        while (true) {
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(0.1f)
            last = now
            var fresh: SpectrumAnalyzer.Frame? = null
            if (active && now - lastAnalysis >= FRAME_NANOS) {
                lastAnalysis = now
                val rate = sampleRateHz()
                if (rate > 0) {
                    val a = analyzer?.takeIf { analyzerRate == rate }
                        ?: SpectrumAnalyzer(rate).also { analyzer = it; analyzerRate = rate }
                    fresh = withContext(Dispatchers.Default) {
                        val n = recentSamples(bufL, bufR)
                        if (n < 512) null else a.analyze(bufL, bufR, n)
                    }
                }
            }
            val fall = FALL_DB_PER_SEC * dt
            for (b in 0 until bands) {
                val target = fresh?.bandsDb?.get(b)?.coerceAtLeast(-RANGE_DB) ?: -RANGE_DB
                val cur = state.level[b]
                state.level[b] = if (fresh != null && target > cur) target else maxOf(if (fresh != null) target else -RANGE_DB, cur - fall)
                if (state.level[b] >= state.hold[b]) { state.hold[b] = state.level[b]; state.holdAt[b] = now }
                else if (now - state.holdAt[b] > HOLD_NANOS) state.hold[b] = maxOf(state.level[b], state.hold[b] - fall * 1.5f)
            }
            fresh?.let { state.correlation += (it.correlation - state.correlation) * 0.25f }
            if (!active) state.correlation *= 0.9f
            state.tick++
            if (!active && state.level.all { it <= -RANGE_DB + 0.1f } && state.hold.all { it <= -RANGE_DB + 0.1f }) break
        }
    }

    Column(modifier.fillMaxSize().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Real-time analyzer", style = MaterialTheme.typography.titleSmall, color = TextPrimary, modifier = Modifier.weight(1f))
            Text("1/3 octave · dBFS", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextTertiary)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.035f))
                .padding(10.dp)
        ) {
            SpectrumBars(state, accent)
        }
        FrequencyAxis()
        CorrelationMeter(state.correlation)
        StereoLevelMeter(levels = levels, active = active, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SpectrumBars(state: AnalyzerState, accent: Color) {
    Canvas(Modifier.fillMaxSize()) {
        @Suppress("UNUSED_VARIABLE") val t = state.tick // redraw on every tick
        val n = state.level.size
        val gap = 2.dp.toPx()
        val barW = (size.width - gap * (n - 1)) / n
        // grid every 12 dB
        var db = -12f
        while (db > -RANGE_DB) {
            val y = size.height * (-db / RANGE_DB)
            drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
            db -= 12f
        }
        val brush = Brush.verticalGradient(
            0f to MeterRed, 0.10f to MeterAmber, 0.25f to HifiBrass, 0.55f to accent, 1f to accent.copy(alpha = 0.55f)
        )
        for (b in 0 until n) {
            val x = b * (barW + gap)
            val frac = ((state.level[b] + RANGE_DB) / RANGE_DB).coerceIn(0f, 1f)
            val h = size.height * frac
            if (h > 0.5f) {
                drawRoundRect(
                    brush = brush,
                    topLeft = Offset(x, size.height - h),
                    size = Size(barW, h),
                    cornerRadius = CornerRadius(barW / 3f)
                )
            }
            val holdFrac = ((state.hold[b] + RANGE_DB) / RANGE_DB).coerceIn(0f, 1f)
            if (holdFrac > 0.01f) {
                val y = size.height * (1f - holdFrac)
                drawRoundRect(
                    color = TextPrimary.copy(alpha = 0.85f),
                    topLeft = Offset(x, (y - 2.dp.toPx()).coerceAtLeast(0f)),
                    size = Size(barW, 2.dp.toPx()),
                    cornerRadius = CornerRadius(1.dp.toPx())
                )
            }
        }
    }
}

@Composable
private fun FrequencyAxis() {
    // Labels at their band positions (bands are evenly spaced on screen).
    val labels = mapOf(2 to "31", 5 to "63", 8 to "125", 11 to "250", 14 to "500", 17 to "1k", 20 to "2k", 23 to "4k", 26 to "8k", 29 to "16k")
    val n = SpectrumAnalyzer.BAND_CENTERS.size
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
        for (b in 0 until n) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                labels[b]?.let {
                    Text(it, fontFamily = TelemetryFontFamily, fontSize = 9.sp, color = TextTertiary, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

/** −1 … +1 phase-correlation bar: right = mono-compatible, left of centre = phase trouble. */
@Composable
private fun CorrelationMeter(value: Float) {
    val color = when {
        value < 0f -> MeterRed
        value < 0.3f -> MeterAmber
        else -> SignalTeal
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Phase correlation", style = MaterialTheme.typography.labelMedium, color = TextSecondary, modifier = Modifier.weight(1f))
            Text(
                "%+.2f".format(value), fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, fontSize = 12.sp, color = color
            )
        }
        Spacer(Modifier.height(5.dp))
        Canvas(Modifier.fillMaxWidth().height(8.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(Color.White.copy(alpha = 0.07f), size = size, cornerRadius = r)
            val mid = size.width / 2f
            drawLine(Color.White.copy(alpha = 0.25f), Offset(mid, 0f), Offset(mid, size.height), strokeWidth = 1.5f)
            val x = mid + value.coerceIn(-1f, 1f) * mid
            val left = minOf(mid, x)
            drawRoundRect(color.copy(alpha = 0.55f), topLeft = Offset(left, 0f), size = Size(kotlin.math.abs(x - mid), size.height), cornerRadius = r)
            drawCircle(color, radius = size.height * 0.75f, center = Offset(x, size.height / 2))
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            Text("−1 out of phase", fontSize = 9.sp, color = TextTertiary, modifier = Modifier.weight(1f))
            Text("0", fontSize = 9.sp, color = TextTertiary)
            Text("mono +1", fontSize = 9.sp, color = TextTertiary, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}
