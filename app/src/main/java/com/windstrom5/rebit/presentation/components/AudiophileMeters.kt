package com.windstrom5.rebit.presentation.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.windstrom5.rebit.playback.dsp.LevelMeter
import com.windstrom5.rebit.presentation.theme.MeterAmber
import com.windstrom5.rebit.presentation.theme.MeterGreen
import com.windstrom5.rebit.presentation.theme.MeterRed
import com.windstrom5.rebit.presentation.theme.TelemetryFontFamily
import com.windstrom5.rebit.presentation.theme.TextSecondary
import com.windstrom5.rebit.presentation.theme.TextTertiary

/**
 * IEC 60268-18 meter scale: dBFS → 0..1 bar position. Gives the top 20 dB half the bar, where
 * music actually lives, instead of wasting it on the -60 dB region a linear scale over-weights.
 */
fun iecMeterFraction(db: Float): Float {
    val d = db.toDouble()
    val pct = when {
        d < -70.0 -> 0.0
        d < -60.0 -> (d + 70.0) * 0.25
        d < -50.0 -> (d + 60.0) * 0.5 + 2.5
        d < -40.0 -> (d + 50.0) * 0.75 + 7.5
        d < -30.0 -> (d + 40.0) * 1.5 + 15.0
        d < -20.0 -> (d + 30.0) * 2.0 + 30.0
        d < 0.0 -> (d + 20.0) * 2.5 + 50.0
        else -> 100.0
    }
    return (pct / 100.0).toFloat().coerceIn(0f, 1f)
}

private const val SEGMENTS = 36
private const val FALL_DB_PER_SEC = 24f
private const val PEAK_HOLD_NANOS = 1_500_000_000L
private const val CLIP_HOLD_NANOS = 2_000_000_000L

/** Per-channel meter ballistics, updated once per frame. */
private class ChannelBallistics {
    var rmsDb by mutableFloatStateOf(LevelMeter.FLOOR_DB)
    var peakDb by mutableFloatStateOf(LevelMeter.FLOOR_DB)
    var holdDb by mutableFloatStateOf(LevelMeter.FLOOR_DB)
    var holdSetAt = 0L

    fun update(rms: Float, peak: Float, now: Long, dtSec: Float) {
        val fall = FALL_DB_PER_SEC * dtSec
        rmsDb = if (rms >= rmsDb) rms else maxOf(rms, rmsDb - fall)
        peakDb = if (peak >= peakDb) peak else maxOf(peak, peakDb - fall)
        if (peak >= holdDb || now - holdSetAt > PEAK_HOLD_NANOS) {
            if (peak >= holdDb) { holdDb = peak; holdSetAt = now } else holdDb = maxOf(peak, holdDb - fall * 2)
        }
    }
}

/**
 * Hardware-style stereo level meter: segmented LED bars on an IEC scale, RMS as the lit body,
 * the instantaneous peak as a bright cap, a 1.5 s peak-hold tick, and an OVER lamp that latches
 * for 2 s whenever a full-scale sample goes by.
 *
 * [levels] is polled every frame while [active]; drawing reads the animated values only inside
 * the Canvas, so per-frame updates redraw without recomposing the surrounding screen.
 */
@Composable
fun StereoLevelMeter(
    levels: () -> LevelMeter.Levels?,
    active: Boolean,
    modifier: Modifier = Modifier
) {
    val left = remember { ChannelBallistics() }
    val right = remember { ChannelBallistics() }
    var clipUntil by remember { mutableLongStateOf(0L) }
    var nowNanos by remember { mutableLongStateOf(0L) }

    LaunchedEffect(active) {
        var last = 0L
        while (true) {
            withFrameNanos { t ->
                val dt = if (last == 0L) 0f else ((t - last) / 1e9f).coerceAtMost(0.1f)
                last = t
                nowNanos = t
                val l = if (active) levels() else null
                // A reading older than 300 ms means the stream stalled or paused: let the bars fall.
                val fresh = l != null && System.nanoTime() - l.timestampNanos < 300_000_000L
                if (fresh && l != null) {
                    val p = l.peakDb
                    val r = l.rmsDb
                    left.update(r[0], p[0], t, dt)
                    right.update(r.getOrElse(1) { r[0] }, p.getOrElse(1) { p[0] }, t, dt)
                    if (l.clipped) clipUntil = t + CLIP_HOLD_NANOS
                } else {
                    left.update(LevelMeter.FLOOR_DB, LevelMeter.FLOOR_DB, t, dt)
                    right.update(LevelMeter.FLOOR_DB, LevelMeter.FLOOR_DB, t, dt)
                }
            }
            if (!active && left.peakDb <= LevelMeter.FLOOR_DB && right.peakDb <= LevelMeter.FLOOR_DB) break
        }
    }

    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                MeterRow("L", left)
                MeterRow("R", right)
            }
            Spacer(Modifier.width(10.dp))
            val over = nowNanos < clipUntil
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LedDot(if (over) MeterRed else Color.White.copy(alpha = 0.10f), size = 8.dp)
                Spacer(Modifier.height(3.dp))
                Text(
                    "OVER",
                    fontFamily = TelemetryFontFamily,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (over) MeterRed else TextTertiary
                )
            }
        }
        // Scale: positions follow the same IEC mapping as the bars.
        Box(Modifier.fillMaxWidth().padding(start = 16.dp, end = 42.dp, top = 3.dp).height(12.dp)) {
            Canvas(Modifier.fillMaxWidth().height(3.dp)) {
                for (db in SCALE_MARKS) {
                    val x = iecMeterFraction(db.toFloat()) * size.width
                    drawLine(Color.White.copy(alpha = 0.18f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 42.dp)) {
            // Labels laid out by weight so they sit roughly under their ticks at any width.
            val stops = listOf(0f) + SCALE_MARKS.map { iecMeterFraction(it.toFloat()) }
            SCALE_MARKS.forEachIndexed { i, db ->
                val w = (stops[i + 1] - stops[i]).coerceAtLeast(0.001f)
                Box(Modifier.weight(w), contentAlignment = Alignment.CenterEnd) {
                    Text(
                        if (db == 0) "0" else "$db",
                        fontFamily = TelemetryFontFamily,
                        fontSize = 8.sp,
                        color = TextTertiary,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

private val SCALE_MARKS = listOf(-40, -20, -10, -6, -3, 0)

@Composable
private fun MeterRow(label: String, ch: ChannelBallistics) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            fontFamily = TelemetryFontFamily,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = TextSecondary,
            modifier = Modifier.width(16.dp)
        )
        Canvas(Modifier.weight(1f).height(7.dp)) {
            val gap = 1.5.dp.toPx()
            val segW = (size.width - gap * (SEGMENTS - 1)) / SEGMENTS
            val rmsLit = (iecMeterFraction(ch.rmsDb) * SEGMENTS).toInt()
            val peakSeg = (iecMeterFraction(ch.peakDb) * SEGMENTS).toInt() - 1
            val holdSeg = (iecMeterFraction(ch.holdDb) * SEGMENTS).toInt() - 1
            val radius = CornerRadius(1.5.dp.toPx())
            for (i in 0 until SEGMENTS) {
                val pos = (i + 0.5f) / SEGMENTS
                val zone = when {
                    pos > iecMeterFraction(-3f) -> MeterRed
                    pos > iecMeterFraction(-12f) -> MeterAmber
                    else -> MeterGreen
                }
                val color = when {
                    i < rmsLit -> zone
                    i <= peakSeg -> zone.copy(alpha = 0.45f)
                    i == holdSeg && ch.holdDb > LevelMeter.FLOOR_DB + 1f -> zone
                    else -> Color.White.copy(alpha = 0.07f)
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(i * (segW + gap), 0f),
                    size = Size(segW, size.height),
                    cornerRadius = radius
                )
            }
        }
    }
}

/** Small pill used to spell out each stage of the signal chain. */
@Composable
fun ChainChip(text: String, color: Color, modifier: Modifier = Modifier, led: Boolean = false) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.13f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (led) {
            LedDot(color, size = 5.dp)
            Spacer(Modifier.width(5.dp))
        }
        Text(
            text,
            fontFamily = TelemetryFontFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            color = color,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** Wrapping row of [ChainChip]s. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChainChipRow(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) { content() }
}

/**
 * The app's slider: a slim 4 dp track with a round, softly glowing thumb — matching the meter
 * and seek bar, instead of Material 3's tall bar thumb with a gap cut into the track.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HifiSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    onValueChangeFinished: (() -> Unit)? = null,
    enabled: Boolean = true
) {
    val colors = SliderDefaults.colors(
        thumbColor = accent,
        activeTrackColor = accent.copy(alpha = 0.85f),
        inactiveTrackColor = Color.White.copy(alpha = 0.14f),
        disabledThumbColor = TextTertiary,
        disabledActiveTrackColor = TextTertiary
    )
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        onValueChangeFinished = onValueChangeFinished,
        enabled = enabled,
        modifier = modifier,
        colors = colors,
        thumb = {
            Box(
                Modifier
                    .size(16.dp)
                    .shadow(6.dp, CircleShape, ambientColor = accent, spotColor = accent)
                    .clip(CircleShape)
                    .background(if (enabled) accent else TextTertiary)
            )
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                modifier = Modifier.height(4.dp),
                colors = colors,
                enabled = enabled,
                drawStopIndicator = null,
                thumbTrackGapSize = 0.dp
            )
        }
    )
}
