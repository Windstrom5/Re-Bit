package com.windstrom5.rebit.presentation.screen

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.windstrom5.rebit.presentation.theme.BackgroundInk
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.theme.OutlineSubtle
import com.windstrom5.rebit.presentation.theme.SignalTeal
import com.windstrom5.rebit.presentation.theme.SurfacePanel
import com.windstrom5.rebit.presentation.theme.TelemetryFontFamily
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary
import com.windstrom5.rebit.presentation.viewmodel.GamesViewModel
import org.koin.androidx.compose.koinViewModel

/** The Games tab: pick a listening game. */
@Composable
fun GamesScreen(
    onOpenAbx: () -> Unit,
    onOpenEqTrainer: () -> Unit,
    viewModel: GamesViewModel = koinViewModel()
) {
    // Scores change while a game screen is open, so reload whenever the hub appears.
    LaunchedEffect(Unit) { viewModel.refresh() }
    val best by viewModel.eqBest.collectAsStateWithLifecycle()

    Column(
        Modifier
            .fillMaxSize()
            .background(BackgroundInk)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Games", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
            Text("Train your ears with your own music.", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
        }

        GameCard(
            title = "ABX blind test",
            tagline = "Can you hear the difference?",
            description = "Compare a pristine sound against a lossy or filtered copy, then say which one is X. " +
                "Statistics tell you whether you were hearing it or guessing.",
            icon = Icons.Filled.Headphones,
            accent = HifiBrass,
            tags = listOf("Blind test", "6 challenges", "Your songs"),
            footer = null,
            onClick = onOpenAbx
        )

        val bestText = if (best.isEmpty()) "No scores yet. Try your first round!"
        else "Best: " + best.entries.joinToString("  \u00B7  ") { "${it.key.title} ${it.value}%" }
        GameCard(
            title = "EQ ear trainer",
            tagline = "Name the hidden frequency",
            description = "An EQ band is secretly boosted or cut. Switch between EQ and flat, then pick the " +
                "frequency that changed. Four levels, from big and wide down to subtle and narrow.",
            icon = Icons.Filled.Equalizer,
            accent = SignalTeal,
            tags = listOf("NEW", "4 levels", "Pink noise or songs"),
            footer = bestText,
            onClick = onOpenEqTrainer
        )

        Text(
            "Games play real audio through your phone, so start with a low volume.",
            color = TextSecondary, style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun GameCard(
    title: String,
    tagline: String,
    description: String,
    icon: ImageVector,
    accent: Color,
    tags: List<String>,
    footer: String?,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.24f), SurfacePanel)))
            .border(BorderStroke(1.dp, accent.copy(alpha = 0.45f)), RoundedCornerShape(22.dp))
            .clickable(onClick = onClick)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(CircleShape).background(accent.copy(alpha = 0.2f))
                    .border(BorderStroke(1.dp, accent.copy(alpha = 0.6f)), CircleShape),
                contentAlignment = Alignment.Center
            ) { Icon(icon, null, tint = accent, modifier = Modifier.size(28.dp)) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = TextPrimary)
                Text(tagline, style = MaterialTheme.typography.bodySmall, color = accent)
            }
        }
        Text(description, color = TextSecondary, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { tag ->
                Text(
                    tag, fontFamily = TelemetryFontFamily, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                    color = if (tag == "NEW") BackgroundInk else accent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (tag == "NEW") accent else accent.copy(alpha = 0.14f))
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }
        if (footer != null) {
            Text(footer, fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextSecondary)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .height(46.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(accent),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.PlayArrow, null, tint = BackgroundInk, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Text("PLAY", fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Black, fontSize = 13.sp, color = BackgroundInk)
        }
    }
}
