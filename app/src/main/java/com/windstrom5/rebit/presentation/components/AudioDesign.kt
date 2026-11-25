package com.windstrom5.rebit.presentation.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.theme.SignalBlue
import com.windstrom5.rebit.presentation.theme.SignalTeal
import com.windstrom5.rebit.presentation.theme.SurfacePanel
import com.windstrom5.rebit.presentation.theme.SurfacePanelHigh
import com.windstrom5.rebit.presentation.theme.TelemetryFontFamily
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary

/*
 * "Reference grade" design language
 * ─────────────────────────────────
 *  • Machined-faceplate panels: a vertical graphite gradient with a 1px light edge on top,
 *    like the bevel of an anodised aluminium amp front.
 *  • Warm amber (HifiBrass) = Hi-Res / "armed"; cool blue = primary action; teal = lossless/OK.
 *  • LED indicators and segmented LED bars instead of flat progress bars.
 *  • Tabular numerals, sentence-case labels, generous radii.
 */

object AudioPanelDefaults {
    val edgeBrush: Brush = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.White.copy(alpha = 0.04f)))
    val border: BorderStroke = BorderStroke(1.dp, edgeBrush)
    val fill: Brush = Brush.verticalGradient(listOf(SurfacePanelHigh, SurfacePanel))
}

/** Gives any container the faceplate look. */
fun Modifier.audioPanel(shape: Shape = RoundedCornerShape(20.dp)): Modifier =
    this
        .clip(shape)
        .background(AudioPanelDefaults.fill, shape)
        .border(AudioPanelDefaults.border, shape)

@Composable
fun AudioPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(20.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier.audioPanel(shape), content = content)
}

/** Section title with a small amber "engraving" tick, optional subtitle and trailing action. */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).height(18.dp).clip(RoundedCornerShape(2.dp)).background(HifiBrass))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            }
        }
        trailing?.invoke()
    }
}

/** Glowing status LED. */
@Composable
fun LedDot(color: Color, modifier: Modifier = Modifier, size: Dp = 7.dp) {
    Box(
        modifier
            .size(size)
            .shadow(6.dp, CircleShape, ambientColor = color, spotColor = color)
            .clip(CircleShape)
            .background(color)
    )
}

enum class AudioQuality(val label: String, val color: Color) {
    HI_RES("HI-RES", HifiBrass),
    LOSSLESS("LOSSLESS", SignalTeal),
    LOSSY("LOSSY", Color(0xFF8E97A8))
}

/** Hi-Res is >= 24-bit or >= 96 kHz, and every DSD file (stored as 1-bit at its DSD rate). */
fun qualityOf(sampleRateHz: Int, bitDepth: Int): AudioQuality =
    if (bitDepth == 1 || sampleRateHz >= 96_000 || bitDepth >= 24) AudioQuality.HI_RES else AudioQuality.LOSSLESS

fun qualityOf(format: com.windstrom5.rebit.domain.model.AudioFileFormat, sampleRateHz: Int, bitDepth: Int): AudioQuality =
    if (!format.isLossless) AudioQuality.LOSSY else qualityOf(sampleRateHz, bitDepth)

/** "24/96" style spec string; "DSD64" for DSD. */
fun specLabel(sampleRateHz: Int, bitDepth: Int): String {
    if (bitDepth == 1) return com.windstrom5.rebit.domain.model.dsdRateLabel(sampleRateHz) ?: "DSD"
    val khz = sampleRateHz / 1000.0
    val k = if (khz % 1.0 == 0.0) "%.0f".format(khz) else "%.1f".format(khz)
    return "$bitDepth/$k"
}

/** Library-style one-liner: "FLAC 24/96", "DSF · DSD64", "MP3 · 44.1 kHz". */
fun formatSpec(format: com.windstrom5.rebit.domain.model.AudioFileFormat, sampleRateHz: Int, bitDepth: Int): String {
    val khz = sampleRateHz / 1000.0
    val k = if (khz % 1.0 == 0.0) "%.0f".format(khz) else "%.1f".format(khz)
    return when {
        format.isDsd -> "${format.label} · ${specLabel(sampleRateHz, bitDepth)}"
        !format.isLossless -> "${format.label} · $k kHz"
        else -> "${format.label} ${specLabel(sampleRateHz, bitDepth)}"
    }
}

@Composable
fun QualityBadge(quality: AudioQuality, modifier: Modifier = Modifier, detail: String? = null) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(quality.color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LedDot(quality.color, size = 5.dp)
        Spacer(Modifier.width(5.dp))
        Text(
            quality.label + if (detail != null) " · $detail" else "",
            fontFamily = TelemetryFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp,
            color = quality.color,
            maxLines = 1,
            softWrap = false
        )
    }
}

/** Big number tile (Listening time, Plays, Streak ...). */
@Composable
fun MetricTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    accent: Color = SignalBlue
) {
    Column(modifier.audioPanel(RoundedCornerShape(18.dp)).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = TextSecondary, maxLines = 1)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                fontFamily = TelemetryFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                color = accent,
                maxLines = 1
            )
            if (unit != null) {
                Spacer(Modifier.width(3.dp))
                Text(unit, style = MaterialTheme.typography.labelMedium, color = TextSecondary, modifier = Modifier.padding(bottom = 4.dp))
            }
        }
    }
}

/** Pill segmented control (e.g. Today | 7 days | 30 days | All time). */
@Composable
fun SegmentedTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = HifiBrass
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(50))
            .background(Color.White.copy(alpha = 0.06f))
            .padding(3.dp)
    ) {
        options.forEachIndexed { i, label ->
            val sel = i == selectedIndex
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (sel) accent.copy(alpha = 0.18f) else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                    color = if (sel) accent else TextSecondary,
                    maxLines = 1
                )
            }
        }
    }
}

/** Segmented LED bar: [fraction] 0..1 of [segments] lit, like a hardware level meter. */
@Composable
fun LedBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = HifiBrass,
    segments: Int = 14
) {
    Canvas(modifier.fillMaxWidth().height(4.dp)) {
        val gap = 2.dp.toPx()
        val segW = (size.width - gap * (segments - 1)) / segments
        val lit = (fraction.coerceIn(0f, 1f) * segments).let { if (fraction > 0f) maxOf(1, it.toInt()) else 0 }
        repeat(segments) { i ->
            drawRoundRect(
                color = if (i < lit) color else Color.White.copy(alpha = 0.08f),
                topLeft = Offset(i * (segW + gap), 0f),
                size = Size(segW, size.height),
                cornerRadius = CornerRadius(size.height / 2f)
            )
        }
    }
}

/** Bar chart of per-day values, today highlighted. Day labels are drawn by the caller. */
@Composable
fun ActivityBars(
    values: List<Long>,
    modifier: Modifier = Modifier,
    accent: Color = SignalBlue,
    highlightLast: Boolean = true
) {
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Canvas(modifier.fillMaxWidth().height(72.dp)) {
        if (values.isEmpty()) return@Canvas
        val gap = 6.dp.toPx()
        val barW = (size.width - gap * (values.size - 1)) / values.size
        values.forEachIndexed { i, v ->
            val h = (size.height * (v.toFloat() / max)).coerceAtLeast(if (v > 0) 6.dp.toPx() else 3.dp.toPx())
            val isToday = highlightLast && i == values.lastIndex
            drawRoundRect(
                color = when {
                    v == 0L -> Color.White.copy(alpha = 0.07f)
                    isToday -> HifiBrass
                    else -> accent.copy(alpha = 0.75f)
                },
                topLeft = Offset(i * (barW + gap), size.height - h),
                size = Size(barW, h),
                cornerRadius = CornerRadius(barW / 2.2f)
            )
        }
    }
}

/** Compact section label with the amber "engraving" tick, for use inside screens and cards. */
@Composable
fun EngravedLabel(text: String, modifier: Modifier = Modifier, color: Color = HifiBrass) {
    Row(modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(3.dp).height(14.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
    }
}

/** Rounded selectable chip with an optional LED, used for device / rate / option pickers. */
@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color = SignalBlue,
    led: Color? = null
) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (selected) accent.copy(alpha = 0.55f) else Color.Transparent, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (led != null) {
            LedDot(led, size = 6.dp)
            Spacer(Modifier.width(7.dp))
        }
        Text(
            label,
            fontFamily = TelemetryFontFamily,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 12.sp,
            color = if (selected) accent else TextSecondary,
            maxLines = 1
        )
    }
}

/** 0..100 ring. Teal = pristine, amber = processed, red = heavily altered. */
@Composable
fun PurityRing(score: Int, modifier: Modifier = Modifier, ringSize: Dp = 96.dp) {
    val color = when {
        score >= 95 -> SignalTeal
        score >= 70 -> HifiBrass
        else -> Color(0xFFFF6B6B)
    }
    Box(modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(ringSize)) {
            val stroke = ringSize.toPx() * 0.11f
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(Color.White.copy(alpha = 0.08f), -90f, 360f, false, Offset(inset, inset), arc,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
            drawArc(color, -90f, 360f * score.coerceIn(0, 100) / 100f, false, Offset(inset, inset), arc,
                style = androidx.compose.ui.graphics.drawscope.Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        Text(
            "$score",
            fontFamily = TelemetryFontFamily,
            fontWeight = FontWeight.Black,
            fontSize = (ringSize.value * 0.30f).sp,
            color = color
        )
    }
}
