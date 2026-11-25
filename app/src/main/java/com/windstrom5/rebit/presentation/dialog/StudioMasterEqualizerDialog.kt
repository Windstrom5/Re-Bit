package com.windstrom5.rebit.presentation.dialog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.windstrom5.rebit.domain.model.UserSettings
import com.windstrom5.rebit.playback.autoeq.AutoEqProfile
import com.windstrom5.rebit.playback.autoeq.AutoEqRepository
import com.windstrom5.rebit.presentation.theme.*
import com.windstrom5.rebit.presentation.viewmodel.PlayerUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private val SPECTRUM_COLORS = listOf(
    Color(0xFF8B5CF6), // 31 Hz Sub-bass (Violet)
    Color(0xFF6366F1), // 62 Hz Deep bass (Indigo)
    Color(0xFF3B82F6), // 125 Hz Mid-bass (Blue)
    Color(0xFF06B6D4), // 250 Hz Upper-bass / warmth (Cyan)
    Color(0xFF10B981), // 500 Hz Low midrange (Emerald)
    Color(0xFF14B8A6), // 1 kHz Center vocal midrange (Teal)
    Color(0xFFF59E0B), // 2 kHz Upper vocal clarity (Amber)
    Color(0xFFEAB308), // 4 kHz Presence / bite (Yellow)
    Color(0xFFF97316), // 8 kHz Treble sparkle (Orange)
    Color(0xFFEC4899)  // 16 kHz Air & brilliance (Pink)
)

private val STUDIO_PRESETS = listOf(
    "FLAT" to listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    "HARMAN 2019" to listOf(4.5f, 3.8f, 2.0f, 0.5f, 0.0f, 0.4f, 1.2f, 1.8f, -1.8f, 0.8f),
    "IEF NEUTRAL" to listOf(1.5f, 1.0f, 0.4f, 0.0f, 0.0f, 0.2f, 0.6f, 0.8f, -1.0f, 0.4f),
    "BASS BOOST (+5dB)" to listOf(5.5f, 4.5f, 3.0f, 1.2f, 0.0f, -0.2f, 0.0f, 0.5f, 1.0f, 0.5f),
    "VOCAL AIR" to listOf(0.5f, 0.5f, 0.2f, 0.0f, 1.2f, 2.4f, 2.0f, 1.2f, -1.0f, 1.0f),
    "TREBLE SPARKLE" to listOf(0.8f, 0.5f, 0.0f, 0.0f, 0.0f, 0.4f, 1.2f, 2.5f, 2.8f, 2.2f),
    "V-SHAPED" to listOf(4.0f, 3.2f, 1.5f, -0.4f, -0.8f, -0.2f, 1.0f, 2.2f, 2.8f, 1.6f),
    "WARM TUBE" to listOf(3.2f, 2.8f, 2.0f, 1.2f, 0.6f, 0.0f, -0.5f, -1.0f, -1.8f, -2.5f),
    "ACOUSTIC" to listOf(2.0f, 1.5f, 0.8f, 0.2f, 0.5f, 1.0f, 1.5f, 1.2f, 0.5f, 0.8f)
)

@Composable
fun StudioMasterEqualizerDialog(
    state: PlayerUiState,
    onDismiss: () -> Unit,
    onToggleEq: () -> Unit,
    onUpdateGains: (List<Float>) -> Unit,
    onApplyIemProfile: (AutoEqProfile) -> Unit,
    onResetFlat: () -> Unit,
    onSetPreamp: (Float) -> Unit,
    onApplyParametric: (com.windstrom5.rebit.playback.autoeq.IemEqApply) -> Unit = {}
) {
    var showAutoEqBrowser by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.96f)
                .fillMaxHeight(0.90f)
                .padding(vertical = 8.dp),
            shape = RoundedCornerShape(24.dp),
            color = SurfacePanelHigh,
            border = BorderStroke(1.2.dp, HifiBrass.copy(alpha = 0.55f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // ── HEADER ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(HifiBrass.copy(alpha = 0.15f), CircleShape)
                                .border(BorderStroke(1.dp, HifiBrass.copy(alpha = 0.4f)), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.GraphicEq,
                                contentDescription = null,
                                tint = HifiBrass,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "STUDIO MASTER EQUALIZER",
                                    fontFamily = TelemetryFontFamily,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.5.sp,
                                    color = HifiBrass,
                                    letterSpacing = 0.6.sp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(if (state.eqEnabled) SignalTeal else TextTertiary, CircleShape)
                                )
                            }
                            Text(
                                "10-Band Precision Audiophile Filter Bank • 31Hz – 16kHz",
                                fontFamily = TelemetryFontFamily,
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (state.eqEnabled) "ENABLED" else "BYPASSED",
                            fontFamily = TelemetryFontFamily,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (state.eqEnabled) SignalTeal else TextTertiary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Switch(
                            checked = state.eqEnabled,
                            onCheckedChange = { onToggleEq() },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = SignalTeal,
                                checkedTrackColor = SignalTeal.copy(alpha = 0.3f),
                                uncheckedThumbColor = TextTertiary,
                                uncheckedTrackColor = OutlineSubtle
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                HorizontalDivider(color = OutlineSubtle, thickness = 1.dp)

                // ── ACTIVE PROFILE & QUICK ACTION ROW ──
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Profile chip
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (state.selectedIemProfile != null) SignalTeal.copy(alpha = 0.12f) else SurfacePanel,
                        border = BorderStroke(1.dp, if (state.selectedIemProfile != null) SignalTeal.copy(alpha = 0.6f) else OutlineSubtle)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Headphones,
                                contentDescription = null,
                                tint = if (state.selectedIemProfile != null) SignalTeal else TextSecondary,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = state.selectedIemProfile?.let {
                                    if (state.eqFilterCount > 0) "IEM: $it · exact ${state.eqFilterCount}-filter EQ" else "IEM: $it"
                                } ?: "PRESET: CUSTOM / MANUAL",
                                fontFamily = TelemetryFontFamily,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (state.selectedIemProfile != null) SignalTeal else TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }

                    // AutoEQ Browser & Reset buttons
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = { showAutoEqBrowser = true },
                            colors = ButtonDefaults.buttonColors(containerColor = SignalBlue.copy(alpha = 0.18f)),
                            border = BorderStroke(1.dp, SignalBlue.copy(alpha = 0.7f)),
                            shape = RoundedCornerShape(18.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Icons.Filled.Headphones, contentDescription = null, tint = SignalBlue, modifier = Modifier.size(13.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("IEM & GENRE PRESETS", fontFamily = TelemetryFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SignalBlue)
                        }

                        OutlinedButton(
                            onClick = onResetFlat,
                            border = BorderStroke(1.dp, HifiBrass.copy(alpha = 0.45f)),
                            shape = RoundedCornerShape(18.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                            modifier = Modifier.height(28.dp)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, tint = HifiBrass, modifier = Modifier.size(12.dp))
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("RESET 0dB", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = HifiBrass)
                        }
                    }
                }

                if (state.eqEnabled) {
                    // ── INTERACTIVE FREQUENCY RESPONSE SPLINE CURVE CANVAS ──
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(145.dp)
                            .background(BackgroundInk, RoundedCornerShape(14.dp))
                            .border(BorderStroke(1.dp, OutlineStrong), RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Canvas(
                            modifier = Modifier
                                .fillMaxSize()
                                .pointerInput(state.eqGains) {
                                    detectTapGestures { offset ->
                                        val w = size.width.toFloat()
                                        val h = size.height.toFloat()
                                        val bandCount = UserSettings.EQ_BANDS.size
                                        val stepX = w / (bandCount - 1).coerceAtLeast(1)
                                        val nearestBand = ((offset.x / stepX).roundToInt()).coerceIn(0, bandCount - 1)
                                        val midY = h / 2f
                                        val clampedY = offset.y.coerceIn(10f, h - 16f)
                                        val rawGain = 12f - (clampedY / h) * 24f
                                        val roundedGain = (rawGain * 2f).roundToInt() / 2f
                                        val newGains = state.eqGains.toMutableList()
                                        newGains[nearestBand] = roundedGain.coerceIn(-12f, 12f)
                                        onUpdateGains(newGains)
                                    }
                                }
                        ) {
                            val w = size.width
                            val h = size.height
                            val midY = h / 2f
                            val bottomY = h - 14f

                            // dB Horizontal grid lines (+12dB, +6dB, 0dB, -6dB, -12dB)
                            val gridAlpha = 0.18f
                            drawLine(color = OutlineSubtle.copy(alpha = gridAlpha), start = Offset(0f, 10f), end = Offset(w, 10f), strokeWidth = 1f)
                            drawLine(color = OutlineSubtle.copy(alpha = gridAlpha), start = Offset(0f, (midY + 10f) / 2f), end = Offset(w, (midY + 10f) / 2f), strokeWidth = 0.8f)
                            // 0dB baseline glowing line
                            drawLine(color = HifiBrass.copy(alpha = 0.55f), start = Offset(0f, midY), end = Offset(w, midY), strokeWidth = 1.4f)
                            drawLine(color = OutlineSubtle.copy(alpha = gridAlpha), start = Offset(0f, (midY + bottomY) / 2f), end = Offset(w, (midY + bottomY) / 2f), strokeWidth = 0.8f)
                            drawLine(color = OutlineSubtle.copy(alpha = gridAlpha), start = Offset(0f, bottomY), end = Offset(w, bottomY), strokeWidth = 1f)

                            val gains = state.eqGains
                            val bandCount = UserSettings.EQ_BANDS.size
                            val stepX = w / (bandCount - 1).coerceAtLeast(1)

                            val points = mutableListOf<Offset>()
                            for (i in 0 until bandCount) {
                                val g = gains.getOrElse(i) { 0f }.coerceIn(-12f, 12f)
                                val y = midY - (g / 12f) * ((midY - 14f).coerceAtLeast(10f))
                                points.add(Offset(i * stepX, y))

                                // Vertical frequency guideline
                                drawLine(
                                    color = if (g != 0f) SPECTRUM_COLORS[i % SPECTRUM_COLORS.size].copy(alpha = 0.22f) else OutlineSubtle.copy(alpha = 0.10f),
                                    start = Offset(i * stepX, 10f),
                                    end = Offset(i * stepX, bottomY),
                                    strokeWidth = 1f
                                )
                            }

                            if (points.isNotEmpty()) {
                                val strokePath = Path()
                                val fillPath = Path()

                                strokePath.moveTo(points[0].x, points[0].y)
                                fillPath.moveTo(points[0].x, bottomY)
                                fillPath.lineTo(points[0].x, points[0].y)

                                for (i in 0 until points.size - 1) {
                                    val p0 = points[i]
                                    val p1 = points[i + 1]
                                    val cx = (p0.x + p1.x) / 2f
                                    strokePath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                                    fillPath.cubicTo(cx, p0.y, cx, p1.y, p1.x, p1.y)
                                }

                                fillPath.lineTo(points.last().x, bottomY)
                                fillPath.close()

                                // Gradient fill under curve
                                drawPath(
                                    path = fillPath,
                                    brush = Brush.verticalGradient(
                                        colors = listOf(
                                            SignalTeal.copy(alpha = 0.32f),
                                            HifiBrass.copy(alpha = 0.12f),
                                            Color.Transparent
                                        ),
                                        startY = 10f,
                                        endY = bottomY
                                    )
                                )

                                // Bloom glow
                                drawPath(
                                    path = strokePath,
                                    color = SignalTeal.copy(alpha = 0.28f),
                                    style = Stroke(width = 6f)
                                )

                                // Crisp inner spline line
                                drawPath(
                                    path = strokePath,
                                    brush = Brush.horizontalGradient(
                                        colors = listOf(
                                            SignalTeal,
                                            HifiBrass,
                                            SignalBlue,
                                            SignalTeal
                                        )
                                    ),
                                    style = Stroke(width = 2.6f)
                                )

                                // Frequency nodes
                                for (i in points.indices) {
                                    val pt = points[i]
                                    val g = gains.getOrElse(i) { 0f }
                                    val active = g != 0f
                                    val nodeColor = SPECTRUM_COLORS[i % SPECTRUM_COLORS.size]
                                    if (active) {
                                        drawCircle(color = nodeColor.copy(alpha = 0.35f), radius = 7.5f, center = pt)
                                    }
                                    drawCircle(color = if (active) nodeColor else TextSecondary, radius = 4f, center = pt)
                                    drawCircle(color = BackgroundInk, radius = 2f, center = pt)
                                }
                            }
                        }

                        // dB readout markers
                        Text("+12dB", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextTertiary, modifier = Modifier.align(Alignment.TopStart))
                        Text("  0dB", fontFamily = TelemetryFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = HifiBrass.copy(alpha = 0.8f), modifier = Modifier.align(Alignment.CenterStart))
                        Text("-12dB", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextTertiary, modifier = Modifier.align(Alignment.BottomStart).padding(bottom = 6.dp))

                        Text("+12", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextTertiary, modifier = Modifier.align(Alignment.TopEnd))
                        Text("  0", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = HifiBrass.copy(alpha = 0.6f), modifier = Modifier.align(Alignment.CenterEnd))
                        Text("-12", fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextTertiary, modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 6.dp))
                    }

                    // ── QUICK SOUND SIGNATURE CHIPS ──
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        STUDIO_PRESETS.forEach { (presetName, presetGains) ->
                            val isMatch = state.eqGains == presetGains
                            OutlinedButton(
                                onClick = { onUpdateGains(presetGains) },
                                modifier = Modifier.height(26.dp),
                                contentPadding = PaddingValues(horizontal = 9.dp, vertical = 0.dp),
                                border = BorderStroke(1.dp, if (isMatch) SignalTeal else OutlineSubtle),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = if (isMatch) SignalTeal.copy(alpha = 0.15f) else Color.Transparent
                                ),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = presetName,
                                    fontFamily = TelemetryFontFamily,
                                    fontSize = 11.sp,
                                    fontWeight = if (isMatch) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isMatch) SignalTeal else TextSecondary
                                )
                            }
                        }
                    }

                    // ── 10-CHANNEL STUDIO FADER RACK (HORIZONTAL SCROLL FOR COMFORT) ──
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                        color = BackgroundInk,
                        shape = RoundedCornerShape(14.dp),
                        border = BorderStroke(1.dp, OutlineStrong)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(rememberScrollState())
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            UserSettings.EQ_BANDS.forEachIndexed { bandIndex, frequencyHz ->
                                val gain = state.eqGains.getOrElse(bandIndex) { 0f }
                                val freqLabel = if (frequencyHz >= 1000) "${frequencyHz / 1000}k" else "${frequencyHz}"
                                val bandColor = SPECTRUM_COLORS[bandIndex % SPECTRUM_COLORS.size]

                                Column(
                                    modifier = Modifier
                                        .width(48.dp)
                                        .fillMaxHeight(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    // Band Frequency Chip
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = bandColor.copy(alpha = 0.15f),
                                        border = BorderStroke(0.8.dp, bandColor.copy(alpha = 0.5f))
                                    ) {
                                        Text(
                                            text = freqLabel,
                                            fontFamily = TelemetryFontFamily,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = bandColor,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }

                                    // dB Readout Pill
                                    Text(
                                        text = if (gain > 0) "+${"%.1f".format(gain)}" else if (gain < 0) "%.1f".format(gain) else "0.0",
                                        fontFamily = TelemetryFontFamily,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = when {
                                            gain > 0f -> SignalTeal
                                            gain < 0f -> HifiBrass
                                            else -> TextSecondary.copy(alpha = 0.7f)
                                        },
                                        textAlign = TextAlign.Center
                                    )

                                    // Nudge Up (+0.5dB) button
                                    IconButton(
                                        onClick = {
                                            val newGains = state.eqGains.toMutableList()
                                            newGains[bandIndex] = (gain + 0.5f).coerceIn(-12f, 12f)
                                            onUpdateGains(newGains)
                                        },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "+0.5dB", tint = TextSecondary, modifier = Modifier.size(16.dp))
                                    }

                                    // Vertical Fader Strip
                                    val faderHeight = 110.dp
                                    Box(
                                        modifier = Modifier
                                            .width(36.dp)
                                            .height(faderHeight)
                                            .pointerInput(bandIndex, gain) {
                                                detectTapGestures(
                                                    onDoubleTap = {
                                                        // Double-tap to zero out this band
                                                        val newGains = state.eqGains.toMutableList()
                                                        newGains[bandIndex] = 0f
                                                        onUpdateGains(newGains)
                                                    }
                                                )
                                            }
                                            .pointerInput(bandIndex) {
                                                val heightPx = size.height.toFloat()
                                                awaitPointerEventScope {
                                                    while (true) {
                                                        val event = awaitPointerEvent()
                                                        event.changes.forEach { pointer ->
                                                            if (pointer.pressed) {
                                                                val clampedY = pointer.position.y.coerceIn(0f, heightPx)
                                                                val raw = 12f - (clampedY / heightPx) * 24f
                                                                val stepped = if (abs(raw) < 0.3f) 0f
                                                                else (raw * 2f).roundToInt() / 2f
                                                                val newGains = state.eqGains.toMutableList()
                                                                newGains[bandIndex] = stepped.coerceIn(-12f, 12f)
                                                                onUpdateGains(newGains)
                                                                pointer.consume()
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                    ) {
                                        Canvas(modifier = Modifier.fillMaxSize()) {
                                            val w = size.width
                                            val h = size.height
                                            val centerX = w / 2f
                                            val midY = h / 2f

                                            // Recessed track slot
                                            val trackWidth = 5f
                                            drawRoundRect(
                                                color = Color(0xFF141720),
                                                topLeft = Offset(centerX - trackWidth / 2f, 4f),
                                                size = Size(trackWidth, h - 8f),
                                                cornerRadius = CornerRadius(2.5f)
                                            )
                                            drawRoundRect(
                                                color = OutlineSubtle,
                                                topLeft = Offset(centerX - trackWidth / 2f, 4f),
                                                size = Size(trackWidth, h - 8f),
                                                cornerRadius = CornerRadius(2.5f),
                                                style = Stroke(width = 0.8f)
                                            )

                                            // Zero-notch center marker
                                            drawLine(
                                                color = HifiBrass.copy(alpha = 0.65f),
                                                start = Offset(centerX - 9f, midY),
                                                end = Offset(centerX + 9f, midY),
                                                strokeWidth = 1.2f
                                            )

                                            // Thumb position
                                            val thumbY = midY - (gain / 12f) * (midY - 10f)

                                            // LED fill from center to thumb
                                            if (gain != 0f) {
                                                val topY = if (gain > 0f) thumbY else midY
                                                val botY = if (gain > 0f) midY else thumbY
                                                val fillColor = if (gain > 0f) SignalTeal else HifiBrass
                                                drawRoundRect(
                                                    brush = Brush.verticalGradient(
                                                        colors = listOf(
                                                            fillColor.copy(alpha = 0.8f),
                                                            fillColor.copy(alpha = 0.3f)
                                                        ),
                                                        startY = topY,
                                                        endY = botY
                                                    ),
                                                    topLeft = Offset(centerX - trackWidth / 2f, topY),
                                                    size = Size(trackWidth, (botY - topY).coerceAtLeast(1f)),
                                                    cornerRadius = CornerRadius(2.5f)
                                                )
                                            }

                                            // Brushed metallic fader knob
                                            val knobWidth = 26f
                                            val knobHeight = 16f
                                            val knobColor = if (gain > 0f) SignalTeal else if (gain < 0f) HifiBrass else Color(0xFFB0B7C3)

                                            // Drop glow
                                            drawRoundRect(
                                                color = knobColor.copy(alpha = 0.25f),
                                                topLeft = Offset(centerX - knobWidth / 2f - 2f, thumbY - knobHeight / 2f - 2f),
                                                size = Size(knobWidth + 4f, knobHeight + 4f),
                                                cornerRadius = CornerRadius(4f)
                                            )

                                            // Knob body
                                            drawRoundRect(
                                                brush = Brush.verticalGradient(
                                                    colors = listOf(
                                                        Color(0xFF2C3240),
                                                        Color(0xFF181C24)
                                                    ),
                                                    startY = thumbY - knobHeight / 2f,
                                                    endY = thumbY + knobHeight / 2f
                                                ),
                                                topLeft = Offset(centerX - knobWidth / 2f, thumbY - knobHeight / 2f),
                                                size = Size(knobWidth, knobHeight),
                                                cornerRadius = CornerRadius(3.5f)
                                            )
                                            drawRoundRect(
                                                color = knobColor.copy(alpha = 0.8f),
                                                topLeft = Offset(centerX - knobWidth / 2f, thumbY - knobHeight / 2f),
                                                size = Size(knobWidth, knobHeight),
                                                cornerRadius = CornerRadius(3.5f),
                                                style = Stroke(width = 1f)
                                            )

                                            // Center tactile neon groove line
                                            drawLine(
                                                color = knobColor,
                                                start = Offset(centerX - 7f, thumbY),
                                                end = Offset(centerX + 7f, thumbY),
                                                strokeWidth = 1.8f
                                            )
                                        }
                                    }

                                    // Nudge Down (-0.5dB) button
                                    IconButton(
                                        onClick = {
                                            val newGains = state.eqGains.toMutableList()
                                            newGains[bandIndex] = (gain - 0.5f).coerceIn(-12f, 12f)
                                            onUpdateGains(newGains)
                                        },
                                        modifier = Modifier.size(20.dp)
                                    ) {
                                        Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "-0.5dB", tint = TextSecondary, modifier = Modifier.size(16.dp))
                                    }
                                }
                            }
                        }
                    }
                } else {
                    // Bypassed info view
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .background(SurfacePanel, CircleShape)
                                .border(BorderStroke(1.dp, OutlineStrong), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.GraphicEq, contentDescription = null, tint = HifiBrass.copy(alpha = 0.6f), modifier = Modifier.size(28.dp))
                        }
                        Spacer(modifier = Modifier.height(14.dp))
                        Text(
                            text = "EQUALIZER IS BYPASSED DIRECT",
                            fontFamily = TelemetryFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            color = HifiBrass
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Bit-perfect pass-through to DAC is active. Enable the equalizer to apply 10-band precision studio DSP and AutoEQ IEM target calibration curves.",
                            fontFamily = TelemetryFontFamily,
                            fontSize = 11.sp,
                            color = TextSecondary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = onToggleEq,
                            colors = ButtonDefaults.buttonColors(containerColor = SignalTeal),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("ENGAGE EQUALIZER ENGINE", fontFamily = TelemetryFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TextOnAccent)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showAutoEqBrowser = true },
                            border = BorderStroke(1.dp, SignalBlue.copy(alpha = 0.7f)),
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Icon(Icons.Filled.Headphones, contentDescription = null, tint = SignalBlue, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("BROWSE IEM & GENRE PRESETS", fontFamily = TelemetryFontFamily, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = SignalBlue)
                        }
                    }
                }
            }
        }
    }

    // ── IEM & GENRE PRESET BROWSER (real AutoEq measurements) ──
    if (showAutoEqBrowser) {
        IemPresetBrowserDialog(
            onDismiss = { showAutoEqBrowser = false },
            onApply = { onApplyParametric(it); showAutoEqBrowser = false }
        )
    }
}
