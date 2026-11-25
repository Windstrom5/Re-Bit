package com.windstrom5.rebit.presentation.screen.player

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.windstrom5.rebit.data.wikipedia.WikiLoad
import com.windstrom5.rebit.data.wikipedia.WikiSummary
import com.windstrom5.rebit.data.wikipedia.WikipediaUiState
import com.windstrom5.rebit.presentation.components.rememberRemoteImage
import com.windstrom5.rebit.presentation.theme.TelemetryFontFamily
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary

internal const val WIKI_TAB_ARTIST = 0
internal const val WIKI_TAB_SONG = 1

/** The "Wikipedia" page of the hero pager: artist and song articles for whatever is playing. */
@Composable
internal fun WikipediaPanel(
    wiki: WikipediaUiState,
    tab: Int,
    onTabChange: (Int) -> Unit,
    accent: Color,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(28.dp),
        color = Color.Black.copy(alpha = 0.30f),
        border = com.windstrom5.rebit.presentation.components.AudioPanelDefaults.border
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WikiTab("Artist", Icons.Filled.Person, tab == WIKI_TAB_ARTIST, accent) { onTabChange(WIKI_TAB_ARTIST) }
                Spacer(Modifier.width(8.dp))
                WikiTab("Song", Icons.Filled.MusicNote, tab == WIKI_TAB_SONG, accent) { onTabChange(WIKI_TAB_SONG) }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Filled.Public, null, tint = TextSecondary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text("Wikipedia", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val isArtist = tab == WIKI_TAB_ARTIST
                WikiEntry(
                    load = if (isArtist) wiki.artist else wiki.song,
                    isArtist = isArtist,
                    accent = accent,
                    onRetry = onRetry
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Text from Wikipedia · CC BY-SA 4.0",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun WikiTab(label: String, icon: ImageVector, selected: Boolean, accent: Color, onClick: () -> Unit) {
    val tint = if (selected) accent else TextSecondary
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) accent.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.05f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
private fun WikiEntry(load: WikiLoad, isArtist: Boolean, accent: Color, onRetry: () -> Unit) {
    val what = if (isArtist) "artist" else "song"
    val uri = LocalUriHandler.current
    when (load) {
        WikiLoad.Idle -> WikiMessage(Icons.Filled.Public, "Play a track to see its Wikipedia entry.")
        WikiLoad.Loading -> WikiSkeleton()
        is WikiLoad.Ready -> WikiArticle(load.summary, isArtist, accent)
        is WikiLoad.Missing -> WikiMessage(
            icon = Icons.Filled.Search,
            text = "No Wikipedia article found for this $what.",
            actionLabel = load.searchUrl?.let { "Search Wikipedia" },
            onAction = { load.searchUrl?.let { runCatching { uri.openUri(it) } } },
            accent = accent
        )
        is WikiLoad.Error -> WikiMessage(
            icon = Icons.Filled.CloudOff,
            text = "Couldn't reach Wikipedia.",
            actionLabel = "Retry",
            actionIcon = Icons.Filled.Refresh,
            onAction = onRetry,
            accent = accent
        )
    }
}

@Composable
private fun WikiArticle(s: WikiSummary, isArtist: Boolean, accent: Color) {
    val uri = LocalUriHandler.current
    var expanded by remember(s.pageUrl) { mutableStateOf(false) }
    val image = rememberRemoteImage(s.thumbnailUrl)
    val thumbShape = if (isArtist) CircleShape else RoundedCornerShape(14.dp)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.Top) {
            Box(
                Modifier.size(72.dp).clip(thumbShape).background(Color.White.copy(alpha = 0.06f)),
                contentAlignment = Alignment.Center
            ) {
                if (image != null) {
                    Image(image, s.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(
                        if (isArtist) Icons.Filled.Person else Icons.Filled.MusicNote,
                        null, tint = TextSecondary, modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    s.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                s.description?.let {
                    Text(
                        it.replaceFirstChar { c -> c.uppercase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = accent,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            s.extract.trim(),
            style = MaterialTheme.typography.bodyMedium,
            color = TextPrimary.copy(alpha = 0.88f),
            maxLines = if (expanded) Int.MAX_VALUE else 7,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.clickable { expanded = !expanded }
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Show less" else "Read more", color = accent)
            }
            Spacer(Modifier.weight(1f))
            if (s.pageUrl.isNotBlank()) {
                OutlinedButton(onClick = { runCatching { uri.openUri(s.pageUrl) } }) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Open")
                }
            }
        }
    }
}

@Composable
private fun WikiMessage(
    icon: ImageVector,
    text: String,
    actionLabel: String? = null,
    actionIcon: ImageVector? = null,
    onAction: () -> Unit = {},
    accent: Color = TextSecondary
) {
    Column(
        Modifier.fillMaxSize().padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, null, tint = TextSecondary, modifier = Modifier.size(32.dp))
        Spacer(Modifier.height(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, textAlign = TextAlign.Center)
        if (actionLabel != null) {
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = onAction) {
                if (actionIcon != null) {
                    Icon(actionIcon, null, modifier = Modifier.size(16.dp), tint = accent)
                    Spacer(Modifier.width(6.dp))
                }
                Text(actionLabel, color = accent)
            }
        }
    }
}

@Composable
private fun WikiSkeleton() {
    val alpha by rememberInfiniteTransition(label = "wiki_skeleton").animateFloat(
        initialValue = 0.25f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "wiki_skeleton_alpha"
    )
    val bar = Color.White.copy(alpha = alpha * 0.25f)
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(bar))
            Spacer(Modifier.width(12.dp))
            Column {
                Box(Modifier.width(150.dp).height(16.dp).clip(RoundedCornerShape(4.dp)).background(bar))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.width(100.dp).height(12.dp).clip(RoundedCornerShape(4.dp)).background(bar))
            }
        }
        Spacer(Modifier.height(16.dp))
        listOf(1f, 0.95f, 1f, 0.85f, 0.6f).forEach { w ->
            Box(Modifier.fillMaxWidth(w).height(12.dp).clip(RoundedCornerShape(4.dp)).background(bar))
            Spacer(Modifier.height(8.dp))
        }
    }
}
