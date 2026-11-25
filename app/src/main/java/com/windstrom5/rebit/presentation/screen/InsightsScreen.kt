package com.windstrom5.rebit.presentation.screen

import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.windstrom5.rebit.domain.audiophile.LibraryInsights
import com.windstrom5.rebit.domain.audiophile.LibraryInsightsData
import com.windstrom5.rebit.domain.audiophile.ResolutionTier
import com.windstrom5.rebit.domain.audiophile.Slice
import com.windstrom5.rebit.data.history.StatsMath
import com.windstrom5.rebit.presentation.components.AudioPanel
import com.windstrom5.rebit.presentation.components.EngravedLabel
import com.windstrom5.rebit.presentation.components.LedBar
import com.windstrom5.rebit.presentation.components.MetricTile
import com.windstrom5.rebit.presentation.components.ModernTopBar
import com.windstrom5.rebit.presentation.theme.BackgroundInk
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.theme.SignalBlue
import com.windstrom5.rebit.presentation.theme.SignalTeal
import com.windstrom5.rebit.presentation.theme.TelemetryFontFamily
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary
import com.windstrom5.rebit.presentation.viewmodel.InsightsViewModel
import org.koin.androidx.compose.koinViewModel

/** How good is the collection? Resolution tiers, sample rates, bit depths, formats, size. */
@Composable
fun InsightsScreen(onBack: () -> Unit, viewModel: InsightsViewModel = koinViewModel()) {
    val data by viewModel.insights.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = BackgroundInk,
        topBar = {
            ModernTopBar(
                title = "Collection",
                onBack = onBack,
                subtitle = data?.takeIf { it.trackCount > 0 }?.let { "${it.trackCount} tracks analysed" },
                actions = {}
            )
        }
    ) { padding ->
        val d = data
        if (d == null || d.trackCount == 0) {
            Box(Modifier.fillMaxSize().padding(padding).padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (d == null) "Analysing your library…" else "Scan your library to see how your collection breaks down.",
                    style = MaterialTheme.typography.bodyLarge, color = TextSecondary
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item { Hero(d) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricTile("Playtime", StatsMath.formatListening(d.totalDurationMs), Modifier.weight(1f))
                    MetricTile("Size", LibraryInsights.formatSize(d.totalBytes), Modifier.weight(1f), accent = SignalTeal)
                    MetricTile("Avg file", "%.0f".format(d.avgSizeMb), Modifier.weight(1f), unit = "MB", accent = HifiBrass)
                }
            }
            item { Distribution("Resolution tiers", d.tiers, tierColor = true) }
            item { Distribution("Sample rate", d.sampleRates) }
            item { Distribution("Bit depth", d.bitDepths) }
            item { Distribution("Formats", d.formats) }
            d.bestTrack?.let { best ->
                item {
                    AudioPanel {
                        Column(Modifier.padding(16.dp)) {
                            EngravedLabel("Highest resolution")
                            Spacer(Modifier.height(6.dp))
                            Text(best.label, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
                            Text(
                                "${best.bitDepth}-bit · ${com.windstrom5.rebit.domain.audiophile.SignalPathLogic.khz(best.sampleRateHz)} · ${best.format}",
                                fontFamily = TelemetryFontFamily, fontSize = 13.sp, color = HifiBrass, fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Hero(d: LibraryInsightsData) {
    AudioPanel {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(104.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(104.dp)) {
                    val stroke = 12.dp.toPx()
                    val arc = Size(size.width - stroke, size.height - stroke)
                    val o = Offset(stroke / 2, stroke / 2)
                    drawArc(Color.White.copy(alpha = 0.08f), -90f, 360f, false, o, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                    drawArc(HifiBrass, -90f, 360f * d.hiResPercent / 100f, false, o, arc, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Text("${d.hiResPercent}%", fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Black, fontSize = 26.sp, color = HifiBrass)
            }
            Spacer(Modifier.width(18.dp))
            Column {
                Text("Hi-Res share", style = MaterialTheme.typography.labelLarge, color = TextSecondary)
                Text(
                    when {
                        d.hiResPercent >= 60 -> "An audiophile library"
                        d.hiResPercent >= 25 -> "A healthy mix"
                        d.hiResPercent > 0 -> "Mostly CD quality"
                        else -> "All CD quality"
                    },
                    style = MaterialTheme.typography.titleLarge, color = TextPrimary
                )
                Text("24-bit or 96 kHz and above", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun Distribution(title: String, slices: List<Slice>, tierColor: Boolean = false) {
    AudioPanel {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            EngravedLabel(title)
            val max = slices.maxOfOrNull { it.fraction }?.coerceAtLeast(0.01f) ?: 1f
            slices.forEach { s ->
                val color = if (tierColor) when (s.label) {
                    ResolutionTier.HI_RES.label, ResolutionTier.DSD_RATE.label -> HifiBrass
                    ResolutionTier.CD.label -> SignalBlue
                    else -> TextSecondary
                } else SignalBlue
                Column {
                    Row(Modifier.fillMaxWidth()) {
                        Text(s.label, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, modifier = Modifier.weight(1f))
                        Text(
                            "${s.count} · ${(s.fraction * 100).toInt()}%",
                            fontFamily = TelemetryFontFamily, fontSize = 12.sp, color = TextSecondary
                        )
                    }
                    Spacer(Modifier.height(5.dp))
                    LedBar(fraction = s.fraction / max, color = color, segments = 24)
                }
            }
        }
    }
}
