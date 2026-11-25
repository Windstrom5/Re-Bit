package com.windstrom5.rebit.presentation.screen.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.windstrom5.rebit.domain.model.LyricsData
import com.windstrom5.rebit.domain.model.LyricsProvider
import com.windstrom5.rebit.playback.lyrics.LyricsRomanizer
import com.windstrom5.rebit.playback.lyrics.RomajiDisplayMode
import com.windstrom5.rebit.presentation.components.audioPanel
import com.windstrom5.rebit.presentation.theme.SurfacePanel
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary
import kotlin.math.abs

/** Display order of the script toggle. */
private val SCRIPT_MODES = listOf(
    RomajiDisplayMode.OFF to "Original",
    RomajiDisplayMode.ROMAJI_ONLY to "Romaji",
    RomajiDisplayMode.DUAL to "Both"
)

/**
 * Lyrics page of the player. Synced lyrics focus the current line (large, in the album's accent
 * colour) and fade the rest by distance; tapping any line seeks to it. The toolbar is a single
 * non-wrapping row, and the lyrics source lives in a footer line so a long source name can never
 * squeeze the controls.
 */
@Composable
internal fun LyricsPanel(
    lyrics: LyricsData,
    activeIndex: Int,
    provider: LyricsProvider,
    accent: Color,
    onSeekTo: (Long) -> Unit,
    onOpenSource: () -> Unit,
    onImportLrc: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Shared by the inline panel and the full-screen view so switching view never resets the script mode.
    var modeIndex by rememberSaveable { mutableIntStateOf(2) } // "Both"
    var fullscreen by rememberSaveable { mutableStateOf(false) }

    LyricsPanelBody(
        lyrics, activeIndex, provider, accent, onSeekTo, onOpenSource, onImportLrc,
        modeIndex = modeIndex, onModeChange = { modeIndex = it },
        fullscreen = false, onToggleFullscreen = { fullscreen = true },
        modifier = modifier
    )

    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
        ) {
            Surface(Modifier.fillMaxSize(), color = SurfacePanel) {
                LyricsPanelBody(
                    lyrics, activeIndex, provider, accent, onSeekTo, onOpenSource, onImportLrc,
                    modeIndex = modeIndex, onModeChange = { modeIndex = it },
                    fullscreen = true, onToggleFullscreen = { fullscreen = false },
                    modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                )
            }
        }
    }
}

@Composable
private fun LyricsPanelBody(
    lyrics: LyricsData,
    activeIndex: Int,
    provider: LyricsProvider,
    accent: Color,
    onSeekTo: (Long) -> Unit,
    onOpenSource: () -> Unit,
    onImportLrc: () -> Unit,
    modeIndex: Int,
    onModeChange: (Int) -> Unit,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    modifier: Modifier = Modifier
) {
    val mode = SCRIPT_MODES[modeIndex.coerceIn(0, SCRIPT_MODES.lastIndex)].first
    // Lyrics are the whole point of this page, so text is sized up from the old defaults.
    val scale = if (fullscreen) 1.45f else 1.2f
    var menuOpen by remember { mutableStateOf(false) }

    val isEmpty = lyrics.lines.isEmpty() && lyrics.plainText.isNullOrBlank()
    // The script toggle is only useful when there is non-Latin text to romanize.
    val hasForeign = remember(lyrics) {
        lyrics.lines.take(40).any { LyricsRomanizer.hasNonRomaji(it.text) } ||
            (lyrics.plainText?.lineSequence()?.take(40)?.any { LyricsRomanizer.hasNonRomaji(it) } == true)
    }

    Column(modifier.audioPanel(RoundedCornerShape(28.dp))) {
        // ── Toolbar: one row that cannot wrap ──
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 0.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (hasForeign && !isEmpty) {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.07f)).padding(2.dp)
                ) {
                    SCRIPT_MODES.forEachIndexed { i, (_, label) ->
                        val sel = i == modeIndex
                        Text(
                            label,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                            color = if (sel) accent else TextSecondary,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(if (sel) accent.copy(alpha = 0.20f) else Color.Transparent)
                                .clickable { onModeChange(i) }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onToggleFullscreen) {
                Icon(
                    if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                    if (fullscreen) "Exit full screen" else "Full screen lyrics",
                    tint = TextSecondary
                )
            }
            IconButton(onClick = onOpenSource) { Icon(Icons.Filled.Search, "Find lyrics", tint = TextSecondary) }
            Box {
                IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "More", tint = TextSecondary) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Change lyrics source") }, onClick = { menuOpen = false; onOpenSource() })
                    DropdownMenuItem(text = { Text("Import .lrc file") }, onClick = { menuOpen = false; onImportLrc() })
                }
            }
        }

        // ── Body ──
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            when {
                isEmpty -> EmptyLyrics(accent, onOpenSource, onImportLrc)
                lyrics.isSynchronized -> SyncedLyrics(lyrics, activeIndex, mode, accent, scale, maxHeightFraction = maxHeight, onSeekTo = onSeekTo)
                else -> PlainLyrics(lyrics.plainText.orEmpty(), mode, scale)
            }
            // soft fades at the edges
            Box(
                Modifier.align(Alignment.TopCenter).fillMaxWidth().height(22.dp)
                    .background(Brush.verticalGradient(listOf(SurfacePanel, Color.Transparent)))
            )
            Box(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(48.dp)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, SurfacePanel)))
            )
        }

        // ── Footer: source (tap to change) ──
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpenSource).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Filled.Public, null, tint = accent, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                lyrics.sourceDescription.ifBlank { provider.displayName },
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SyncedLyrics(
    lyrics: LyricsData,
    activeIndex: Int,
    mode: RomajiDisplayMode,
    accent: Color,
    scale: Float,
    maxHeightFraction: androidx.compose.ui.unit.Dp,
    onSeekTo: (Long) -> Unit
) {
    val listState = rememberLazyListState()
    LaunchedEffect(activeIndex) {
        // The top padding below leaves room above, so the active line rests about a third of the way down.
        listState.animateScrollToItem(activeIndex.coerceIn(0, (lyrics.lines.size - 1).coerceAtLeast(0)))
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 18.dp, end = 18.dp,
            top = maxHeightFraction * 0.28f, bottom = maxHeightFraction * 0.6f
        ),
        verticalArrangement = Arrangement.spacedBy((14f * scale).dp)
    ) {
        itemsIndexed(lyrics.lines) { i, line ->
            val distance = if (activeIndex < 0) i + 1 else abs(i - activeIndex)
            LyricLineRow(line.text, distance, mode, accent, scale) { onSeekTo(line.timestampMs) }
        }
    }
}

@Composable
private fun LyricLineRow(text: String, distance: Int, mode: RomajiDisplayMode, accent: Color, scale: Float, onClick: () -> Unit) {
    val alpha by animateFloatAsState(
        when (distance) { 0 -> 1f; 1 -> 0.6f; 2 -> 0.42f; else -> 0.28f }, tween(300), label = "lyric_alpha"
    )
    val size by animateFloatAsState(if (distance == 0) 24f * scale else 19f * scale, tween(250), label = "lyric_size")
    val romaji = remember(text) { if (LyricsRomanizer.hasNonRomaji(text)) LyricsRomanizer.romanize(text) else null }
    val main = if (mode == RomajiDisplayMode.ROMAJI_ONLY) romaji ?: text else text
    val active = distance == 0

    Column(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp)
    ) {
        Text(
            main,
            fontSize = size.sp,
            lineHeight = (size * 1.25f).sp,
            fontWeight = if (active) FontWeight.Bold else FontWeight.SemiBold,
            color = if (active) accent else TextPrimary
        )
        if (mode == RomajiDisplayMode.DUAL && !romaji.isNullOrBlank() && romaji != text) {
            Spacer(Modifier.height(2.dp))
            Text(
                romaji,
                fontSize = (if (active) 15f * scale else 13f * scale).sp,
                fontStyle = FontStyle.Italic,
                color = if (active) accent.copy(alpha = 0.8f) else TextSecondary
            )
        }
    }
}

@Composable
private fun PlainLyrics(raw: String, mode: RomajiDisplayMode, scale: Float) {
    val shown = remember(raw, mode) {
        when (mode) {
            RomajiDisplayMode.ROMAJI_ONLY -> LyricsRomanizer.romanize(raw)
            RomajiDisplayMode.DUAL -> raw.lines().joinToString("\n") { line ->
                if (LyricsRomanizer.hasNonRomaji(line)) "$line\n${LyricsRomanizer.romanize(line)}\n" else line
            }
            RomajiDisplayMode.OFF -> raw
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp)) {
        Text(shown, fontSize = (17f * scale).sp, lineHeight = (28f * scale).sp, color = TextPrimary.copy(alpha = 0.9f))
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun EmptyLyrics(accent: Color, onFind: () -> Unit, onImport: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Filled.Lyrics, null, tint = accent.copy(alpha = 0.8f), modifier = Modifier.size(40.dp))
        Spacer(Modifier.height(10.dp))
        Text("No lyrics yet", style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Text(
            "Search LRCLIB, Netease and Kugou, or import a synced .lrc file.",
            style = MaterialTheme.typography.bodyMedium, color = TextSecondary,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onFind,
                colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = Color(0xFF0B0B10)),
                shape = RoundedCornerShape(50)
            ) { Text("Find lyrics", fontWeight = FontWeight.Bold) }
            OutlinedButton(onClick = onImport, shape = RoundedCornerShape(50)) { Text("Import .lrc") }
        }
    }
}
