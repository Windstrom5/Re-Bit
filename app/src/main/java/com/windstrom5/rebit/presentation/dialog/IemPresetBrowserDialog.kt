package com.windstrom5.rebit.presentation.dialog

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.windstrom5.rebit.playback.autoeq.EqMath
import com.windstrom5.rebit.playback.autoeq.IemEntry
import com.windstrom5.rebit.playback.autoeq.IemEqApply
import com.windstrom5.rebit.playback.autoeq.SoundSignatures
import com.windstrom5.rebit.presentation.components.AudioPanel
import com.windstrom5.rebit.presentation.components.ChoiceChip
import com.windstrom5.rebit.presentation.components.EngravedLabel
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.theme.SignalBlue
import com.windstrom5.rebit.presentation.theme.SignalTeal
import com.windstrom5.rebit.presentation.theme.SurfacePanel
import com.windstrom5.rebit.presentation.theme.TelemetryFontFamily
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary
import com.windstrom5.rebit.presentation.viewmodel.IemPresetViewModel
import org.koin.androidx.compose.koinViewModel
import kotlin.math.abs
import kotlin.math.ln

private val POPULAR = listOf(
    "Moondrop Aria", "Moondrop Chu", "Moondrop Blessing 2", "KZ ZSN Pro",
    "Etymotic ER2XR", "Sennheiser IE 200", "Truthear Hexa", "Final E5000"
)

/**
 * Pick your IEM from AutoEq's real measurement-based catalogue, optionally layer a genre
 * tuning on top, preview the resulting curve, and apply it to the equalizer.
 */
@Composable
fun IemPresetBrowserDialog(
    onDismiss: () -> Unit,
    onApply: (IemEqApply) -> Unit,
    viewModel: IemPresetViewModel = koinViewModel()
) {
    val s by viewModel.state.collectAsStateWithLifecycle()
    val plan = s.plan

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.96f).fillMaxHeight(0.92f).padding(vertical = 8.dp),
            shape = RoundedCornerShape(28.dp),
            color = SurfacePanel
        ) {
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("IEM & genre presets", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                        Text(
                            if (s.catalogSize > 0) "Real measured corrections for ${"%,d".format(s.catalogSize)}+ IEMs (AutoEq)"
                            else "Real measured corrections (AutoEq)",
                            style = MaterialTheme.typography.labelMedium, color = TextSecondary
                        )
                    }
                    IconButton(onClick = onDismiss) { Icon(Icons.Filled.Close, "Close", tint = TextSecondary) }
                }

                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        TextField(
                            value = s.query,
                            onValueChange = viewModel::search,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Search your IEM (e.g. Moondrop Aria)", color = TextSecondary.copy(alpha = 0.7f)) },
                            leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextSecondary) },
                            trailingIcon = {
                                if (s.query.isNotEmpty()) IconButton(onClick = { viewModel.search("") }) {
                                    Icon(Icons.Filled.Close, "Clear", tint = TextSecondary)
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(50),
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = Color.White.copy(alpha = 0.07f),
                                unfocusedContainerColor = Color.White.copy(alpha = 0.07f),
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary,
                                cursorColor = SignalBlue
                            )
                        )
                    }
                    item {
                        ChoiceChip(
                            "Include over-ear headphones",
                            s.includeOverEar,
                            { viewModel.setIncludeOverEar(!s.includeOverEar) },
                            accent = SignalBlue
                        )
                    }

                    if (s.loading) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = HifiBrass)
                                Spacer(Modifier.width(10.dp))
                                Text("Loading the IEM catalogue…", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                            }
                        }
                    }
                    s.error?.let { err ->
                        item {
                            AudioPanel {
                                Column(Modifier.padding(16.dp)) {
                                    Text(err, style = MaterialTheme.typography.bodyMedium, color = Color(0xFFFF6B6B))
                                    TextButton(onClick = { viewModel.load(forceRefresh = true) }) { Text("Retry", color = SignalBlue) }
                                }
                            }
                        }
                    }

                    // ── Model selection ──
                    val group = s.selectedGroup
                    if (group == null) {
                        if (s.query.isBlank()) {
                            item {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text("Popular", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(POPULAR) { name -> ChoiceChip(name, false, { viewModel.search(name) }, accent = HifiBrass) }
                                    }
                                }
                            }
                        } else if (s.results.isEmpty() && !s.loading) {
                            item { Text("No match. Try fewer words or the brand name.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary) }
                        }
                        items(s.results, key = { it.first().path }) { g -> ResultRow(g) { viewModel.selectModel(g) } }
                    } else {
                        item {
                            AudioPanel {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(group.first().name, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                                            Text(
                                                "${group.size} measurement${if (group.size == 1) "" else "s"} available",
                                                style = MaterialTheme.typography.labelMedium, color = TextSecondary
                                            )
                                        }
                                        TextButton(onClick = viewModel::clearSelection) { Text("Change", color = SignalBlue) }
                                    }
                                    if (group.size > 1) {
                                        Text("Measured by", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            items(group, key = { it.path }) { e ->
                                                ChoiceChip(e.sourceLabel, e == s.selectedSource, { viewModel.selectSource(e) }, accent = HifiBrass)
                                            }
                                        }
                                    }
                                    when {
                                        s.presetLoading -> Row(verticalAlignment = Alignment.CenterVertically) {
                                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = HifiBrass)
                                            Spacer(Modifier.width(8.dp))
                                            Text("Downloading preset…", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                                        }
                                        s.presetError != null -> Column {
                                            Text(s.presetError!!, style = MaterialTheme.typography.bodySmall, color = Color(0xFFFF6B6B))
                                            s.selectedSource?.let { src ->
                                                TextButton(onClick = { viewModel.selectSource(src) }) { Text("Retry", color = SignalBlue) }
                                            }
                                        }
                                        s.preset != null -> Text(
                                            "${s.preset!!.filters.size} parametric filters from ${s.selectedSource?.sourceLabel}",
                                            style = MaterialTheme.typography.labelMedium, color = SignalTeal
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── Genre / sound signature ──
                    item {
                        AudioPanel {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                EngravedLabel("Sound signature")
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(SoundSignatures.ALL, key = { it.id }) { sig ->
                                        ChoiceChip(sig.label, s.signatureId == sig.id, { viewModel.setSignature(sig.id) }, accent = SignalBlue)
                                    }
                                }
                                Text(s.signature.blurb, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                if (s.signatureId != "neutral") {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("Strength", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                                        Spacer(Modifier.width(10.dp))
                                        Slider(
                                            value = s.strength,
                                            onValueChange = viewModel::setStrength,
                                            modifier = Modifier.weight(1f),
                                            colors = SliderDefaults.colors(
                                                thumbColor = HifiBrass,
                                                activeTrackColor = HifiBrass.copy(alpha = 0.8f),
                                                inactiveTrackColor = Color.White.copy(alpha = 0.16f)
                                            )
                                        )
                                        Text(
                                            "${(s.strength * 100).toInt()}%",
                                            fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, fontSize = 13.sp, color = TextPrimary
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── Preview ──
                    if (plan != null) {
                        item {
                            AudioPanel {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(Modifier.weight(1f)) { EngravedLabel("Resulting correction") }
                                        Text(
                                            "${plan.filters.size} filters · preamp ${"%.1f".format(plan.preampDb)} dB",
                                            fontFamily = TelemetryFontFamily, fontSize = 12.sp, color = TextSecondary
                                        )
                                    }
                                    EqCurveChart(plan.curve)
                                    FrequencyAxis()
                                }
                            }
                        }
                    }

                    item {
                        Text(
                            "Presets by AutoEq (github.com/jaakkopasanen/AutoEq, MIT licence), built from published " +
                                "frequency-response measurements and aimed at the Harman target. Results vary with unit and ear tip fit. " +
                                "A safe preamp is applied automatically to prevent clipping.",
                            style = MaterialTheme.typography.labelSmall, color = TextSecondary
                        )
                    }
                }

                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
                    Button(
                        onClick = { viewModel.buildApply()?.let(onApply) },
                        enabled = s.canApply && !s.presetLoading,
                        colors = ButtonDefaults.buttonColors(containerColor = HifiBrass, contentColor = Color(0xFF0B0B10)),
                        shape = RoundedCornerShape(50)
                    ) { Text("Apply to equalizer", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun ResultRow(group: List<IemEntry>, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.05f)).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(group.first().name, style = MaterialTheme.typography.bodyLarge, color = TextPrimary)
            Text(
                "${group.size} measurement${if (group.size == 1) "" else "s"} · best: ${group.first().sourceLabel}",
                style = MaterialTheme.typography.labelMedium, color = TextSecondary
            )
        }
    }
}

/** Frequency response (dB vs log frequency), 0 dB line emphasised. */
@Composable
private fun EqCurveChart(curve: DoubleArray) {
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val w = size.width
        val h = size.height
        val range = (curve.maxOf { abs(it) }.coerceIn(6.0, 20.0)) + 2.0
        fun x(i: Int) = w * i / (EqMath.GRID.size - 1)
        fun y(db: Double) = (h / 2f - (db / range * (h / 2f))).toFloat()

        // horizontal grid
        for (db in listOf(-10.0, -5.0, 5.0, 10.0)) {
            if (abs(db) < range) drawLine(Color.White.copy(alpha = 0.06f), Offset(0f, y(db)), Offset(w, y(db)), 1.dp.toPx())
        }
        drawLine(Color.White.copy(alpha = 0.25f), Offset(0f, y(0.0)), Offset(w, y(0.0)), 1.5.dp.toPx())
        // vertical grid at 100 Hz, 1 kHz, 10 kHz
        for (f in listOf(100.0, 1000.0, 10_000.0)) {
            val gx = (ln(f / 20.0) / ln(1000.0) * w).toFloat()
            drawLine(Color.White.copy(alpha = 0.06f), Offset(gx, 0f), Offset(gx, h), 1.dp.toPx())
        }
        val path = Path()
        curve.forEachIndexed { i, db -> if (i == 0) path.moveTo(x(i), y(db)) else path.lineTo(x(i), y(db)) }
        drawPath(path, HifiBrass, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun FrequencyAxis() {
    BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
        val labels = listOf(20.0 to "20", 100.0 to "100", 1000.0 to "1k", 10_000.0 to "10k", 20_000.0 to "20k")
        labels.forEach { (f, label) ->
            val frac = (ln(f / 20.0) / ln(1000.0)).toFloat()
            Text(
                label, style = MaterialTheme.typography.labelSmall, color = TextSecondary,
                modifier = Modifier.offset(x = (maxWidth * frac - 8.dp).coerceAtLeast(0.dp))
            )
        }
    }
}
