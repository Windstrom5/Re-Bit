package com.windstrom5.rebit.presentation.screen

import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.OutlinedTextField
import com.windstrom5.rebit.presentation.components.specLabel
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import com.windstrom5.rebit.data.settings.SettingsRepository
import com.windstrom5.rebit.presentation.dialog.TutorialDialog
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.windstrom5.rebit.data.history.ArtistRankRow
import com.windstrom5.rebit.data.history.RankRange
import com.windstrom5.rebit.data.history.StatsMath
import com.windstrom5.rebit.presentation.components.ActivityBars
import com.windstrom5.rebit.presentation.components.AudioPanel
import com.windstrom5.rebit.presentation.components.audioPanel
import com.windstrom5.rebit.presentation.components.AudioQuality
import com.windstrom5.rebit.presentation.components.LedBar
import com.windstrom5.rebit.presentation.components.LedDot
import com.windstrom5.rebit.presentation.components.MetricTile
import com.windstrom5.rebit.presentation.components.MiniPlayerBar
import com.windstrom5.rebit.presentation.components.QualityBadge
import com.windstrom5.rebit.presentation.components.SectionHeader
import com.windstrom5.rebit.presentation.components.SegmentedTabs
import com.windstrom5.rebit.presentation.components.TrackArtwork
import com.windstrom5.rebit.presentation.components.qualityOf
import com.windstrom5.rebit.presentation.components.rememberRemoteImage
import com.windstrom5.rebit.presentation.theme.BackgroundInk
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.theme.OutlineSubtle
import com.windstrom5.rebit.presentation.theme.SignalBlue
import com.windstrom5.rebit.presentation.theme.SignalTeal
import com.windstrom5.rebit.presentation.theme.TelemetryFontFamily
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary
import com.windstrom5.rebit.presentation.viewmodel.HomeViewModel
import com.windstrom5.rebit.presentation.viewmodel.RankedTrack
import org.koin.androidx.compose.koinViewModel
import java.util.Calendar

private val LikedPink = Color(0xFFFF6B81)

/** Home: your listening, ranked. Top tracks/artists by Today / 7 days / 30 days / All time. */
@Composable
fun HomeScreen(
    onNavigateToPlayer: () -> Unit,
    onOpenWrapped: () -> Unit,
    onOpenNews: () -> Unit,
    onOpenLab: () -> Unit,
    onOpenStreaming: () -> Unit = {},
    viewModel: HomeViewModel = koinViewModel()
) {
    val range by viewModel.range.collectAsStateWithLifecycle()
    val topTracks by viewModel.topTracks.collectAsStateWithLifecycle()
    val topArtists by viewModel.topArtists.collectAsStateWithLifecycle()
    val totals by viewModel.totals.collectAsStateWithLifecycle()
    val recent by viewModel.recent.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val streak by viewModel.streakDays.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val artistImages by viewModel.artistImages.collectAsStateWithLifecycle()
    val newsPreview by viewModel.newsPreview.collectAsStateWithLifecycle()
    val newsUnread by viewModel.newsUnread.collectAsStateWithLifecycle()
    val hiResGems by viewModel.hiResGems.collectAsStateWithLifecycle()
    val rediscover by viewModel.rediscover.collectAsStateWithLifecycle()
    val gear by viewModel.gear.collectAsStateWithLifecycle()
    var editGear by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context) }
    var showTutorial by remember { mutableStateOf(!settingsRepository.hasCompletedTutorial()) }
    var tutorialInitialStep by remember { mutableIntStateOf(0) }

    if (showTutorial) {
        TutorialDialog(
            initialStepIndex = tutorialInitialStep,
            onOpenStreaming = { showTutorial = false; onOpenStreaming() },
            onDismiss = { dontShowAgain ->
                if (dontShowAgain) {
                    settingsRepository.setTutorialCompleted(true)
                }
                showTutorial = false
            }
        )
    }

    val greeting = remember { StatsMath.greeting(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    var confirmReset by remember { mutableStateOf(false) }

    if (editGear) {
        GearDialog(initial = gear, onDismiss = { editGear = false }, onSave = { viewModel.saveGear(it); editGear = false })
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset listening history?") },
            text = { Text("This clears your rankings, streak and activity. Liked songs are kept. It can't be undone.") },
            confirmButton = {
                TextButton(onClick = { viewModel.clearHistory(); confirmReset = false }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } }
        )
    }

    Scaffold(
        containerColor = BackgroundInk,
        bottomBar = { MiniPlayerBar(onOpenPlayer = onNavigateToPlayer) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp)
        ) {
            // ── Greeting ──
            item {
                Column(
                    Modifier
                        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(greeting, style = MaterialTheme.typography.headlineMedium, color = TextPrimary)
                            Text(
                                if (totals.plays == 0 && range == RankRange.ALL) "Play something and your listening shows up here."
                                else "Your listening, ranked.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary
                            )
                        }
                        IconButton(onClick = { tutorialInitialStep = 0; showTutorial = true }) {
                            Icon(Icons.AutoMirrored.Filled.HelpOutline, "Beginner guide", tint = HifiBrass)
                        }
                        IconButton(onClick = onOpenNews) {
                            BadgedBox(badge = { if (newsUnread > 0) Badge { Text(if (newsUnread > 9) "9+" else newsUnread.toString()) } }) {
                                Icon(Icons.Filled.Notifications, "Artist news", tint = TextPrimary)
                            }
                        }
                    }
                }
            }

            // ── Beginner Guide Banner ──
            item {
                BeginnerGuideBanner(
                    onOpenTutorial = { tutorialInitialStep = 0; showTutorial = true },
                    onOpenStreamingFaq = { tutorialInitialStep = 5; showTutorial = true }
                )
            }

            // ── Wrapped ──
            item { WrappedBanner(onClick = onOpenWrapped) }

            // ── Streaming (your own Qobuz / Tidal / Spotify account) ──
            item {
                Row(
                    Modifier
                        .padding(horizontal = 20.dp)
                        .fillMaxWidth()
                        .audioPanel()
                        .clickable(onClick = onOpenStreaming)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Cloud, null, tint = SignalBlue)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Stream with your account", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text(
                            "Qobuz and Tidal lossless through the USB driver. Spotify via its app.",
                            style = MaterialTheme.typography.bodySmall, color = TextSecondary
                        )
                    }
                }
            }

            // ── Range + headline numbers ──
            item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SegmentedTabs(
                        options = RankRange.entries.map { it.label },
                        selectedIndex = range.ordinal,
                        onSelect = { viewModel.setRange(RankRange.entries[it]) }
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        MetricTile("Listening", StatsMath.formatListening(totals.listenedMs), Modifier.weight(1f))
                        MetricTile("Plays", totals.plays.toString(), Modifier.weight(1f), accent = SignalTeal)
                        MetricTile(
                            "Day streak", streak.toString(), Modifier.weight(1f),
                            unit = if (streak == 1) "day" else "days", accent = HifiBrass
                        )
                    }
                }
            }

            // ── Top tracks ──
            item {
                SectionHeader(
                    title = "Top tracks",
                    subtitle = rangeSubtitle(range),
                    trailing = {
                        if (topTracks.any { it.available }) {
                            IconButton(onClick = { viewModel.playAll(topTracks, shuffle = true) }) {
                                Icon(Icons.Filled.Shuffle, "Shuffle top tracks", tint = TextSecondary)
                            }
                            IconButton(onClick = { viewModel.playAll(topTracks, shuffle = false) }) {
                                Icon(Icons.Filled.PlayArrow, "Play top tracks", tint = HifiBrass)
                            }
                        }
                    }
                )
            }
            if (topTracks.isEmpty()) {
                item { EmptyCard("Nothing here yet", "A song counts as a play after you've listened for 30 seconds.") }
            } else {
                val maxPlays = topTracks.first().row.plays.coerceAtLeast(1)
                items(topTracks.take(10), key = { "top-${it.row.filePath}" }) { item ->
                    RankRow(item, maxPlays) { viewModel.play(item, topTracks) }
                }
            }

            // ── Top artists ──
            if (topArtists.isNotEmpty()) {
                item { SectionHeader("Top artists", subtitle = rangeSubtitle(range)) }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(topArtists.withIndex().toList(), key = { it.value.artist }) { (i, a) ->
                            ArtistChip(i + 1, a, artistImages[a.artist])
                        }
                    }
                }
            }

            // ── 14-day activity ──
            item {
                val values = activity.map { it.second }
                val total = values.sum()
                AudioPanel(Modifier.padding(horizontal = 20.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Listening activity", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                                Text("Last 14 days", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                            }
                            Text(
                                StatsMath.formatListening(total),
                                fontFamily = TelemetryFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = SignalBlue
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        ActivityBars(values)
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth()) {
                            val letters = "MTWTFSS"
                            activity.forEach { (day, _) ->
                                Text(
                                    letters[((day + 3) % 7).toInt()].toString(),
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Center,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                        }
                    }
                }
            }

            // ── Recently played ──
            if (recent.isNotEmpty()) {
                item { SectionHeader("Recently played") }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(recent, key = { "recent-${it.row.filePath}" }) { item ->
                            RecentTile(item) { viewModel.play(item, recent) }
                        }
                    }
                }
            }

            // ── Artist news ──
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionHeader(
                        title = "Artist news",
                        subtitle = "New releases and headlines from your library",
                        trailing = { TextButton(onClick = onOpenNews) { Text("See all", color = SignalBlue) } }
                    )
                    if (newsPreview.isEmpty()) {
                        Row(
                            Modifier.padding(horizontal = 20.dp).fillMaxWidth().audioPanel().clickable(onClick = onOpenNews).padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.Notifications, null, tint = HifiBrass)
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Get alerts when artists you listen to release new music.",
                                style = MaterialTheme.typography.bodyMedium, color = TextSecondary
                            )
                        }
                    } else {
                        Column(
                            Modifier.padding(horizontal = 20.dp).fillMaxWidth().audioPanel().clickable(onClick = onOpenNews).padding(vertical = 6.dp)
                        ) {
                            newsPreview.forEach { n ->
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Text(n.artist, style = MaterialTheme.typography.labelLarge, color = if (n.kind == "RELEASE") HifiBrass else SignalTeal)
                                    Text(n.title, style = MaterialTheme.typography.bodyMedium, color = TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }

            // ── Hi-Res gems: owned but never played ──
            if (hiResGems.isNotEmpty()) {
                item { SectionHeader("Hi-Res gems", subtitle = "Top-resolution files you haven't played yet") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(hiResGems, key = { "gem-${it.filePath}" }) { t ->
                            CoverTile(
                                filePath = t.filePath, title = t.title, artist = t.artist, enabled = true,
                                badge = specLabel(t.pcm.sampleRateHz, t.pcm.bitDepth)
                            ) { viewModel.playOne(t, hiResGems) }
                        }
                    }
                }
            }

            // ── Rediscover ──
            if (rediscover.isNotEmpty()) {
                item { SectionHeader("Rediscover", subtitle = "You loved these — it's been a while") }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(rediscover, key = { "redis-${it.row.filePath}" }) { item ->
                            CoverTile(
                                filePath = item.row.filePath, title = item.row.title, artist = item.row.artist,
                                enabled = item.available, badge = "${item.row.plays}× played"
                            ) { viewModel.play(item, rediscover) }
                        }
                    }
                }
            }

            // ── My setup ──
            item { GearCard(gear) { editGear = true } }

            // ── Listening lab ──
            item {
                Row(
                    Modifier.padding(horizontal = 20.dp).fillMaxWidth().audioPanel().clickable(onClick = onOpenLab).padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.GraphicEq, null, tint = HifiBrass)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Listening lab", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                        Text("Test tones, sweep, pink noise, channel and polarity checks", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                    }
                }
            }

            // ── Liked songs ──
            item {
                LikedCard(
                    count = favorites.size,
                    onPlay = { viewModel.playTracksList(favorites, shuffle = false) },
                    onShuffle = { viewModel.playTracksList(favorites, shuffle = true) }
                )
            }

            // ── Quality mix ──
            if (totals.plays > 0) {
                item { QualityMix(hiRes = totals.hiResPlays, total = totals.plays, range = range) }
            }

            // ── Privacy ──
            item {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    TextButton(onClick = { confirmReset = true }) {
                        Text("Reset listening history", color = TextSecondary)
                    }
                }
            }
        }
    }
}

private fun rangeSubtitle(range: RankRange) = when (range) {
    RankRange.TODAY -> "Since midnight"
    RankRange.WEEK -> "Last 7 days"
    RankRange.MONTH -> "Last 30 days"
    RankRange.ALL -> "All time"
}

@Composable
private fun EmptyCard(title: String, body: String) {
    AudioPanel(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
        Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun RankRow(item: RankedTrack, maxPlays: Int, onClick: () -> Unit) {
    val row = item.row
    val alpha = if (item.available) 1f else 0.4f
    val medal = when (item.position) { 1 -> HifiBrass; 2 -> Color(0xFFC9D1E0); 3 -> Color(0xFFD08A5B); else -> TextSecondary }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = item.available, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            item.position.toString(),
            fontFamily = TelemetryFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp,
            color = medal.copy(alpha = alpha),
            textAlign = TextAlign.Center,
            modifier = Modifier.width(30.dp)
        )
        Spacer(Modifier.width(8.dp))
        TrackArtwork(
            uriString = row.filePath,
            title = row.title,
            artist = row.artist,
            isPlaying = false,
            isLarge = false,
            cornerRadius = 12.dp,
            modifier = Modifier.size(52.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.title,
                style = MaterialTheme.typography.titleSmall,
                color = TextPrimary.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                (row.artist ?: "Unknown artist") + if (!item.available) " · file missing" else "",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            LedBar(
                fraction = row.plays.toFloat() / maxPlays,
                color = medal.copy(alpha = alpha),
                modifier = Modifier.fillMaxWidth(0.8f)
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${row.plays} ${if (row.plays == 1) "play" else "plays"}",
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary.copy(alpha = alpha)
            )
            Text(
                StatsMath.formatListening(row.listenedMs),
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary.copy(alpha = alpha)
            )
        }
    }
}

@Composable
private fun ArtistChip(rank: Int, artist: ArtistRankRow, imageUrl: String?) {
    val image = rememberRemoteImage(imageUrl)
    Column(Modifier.width(92.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.BottomEnd) {
            Box(
                Modifier
                    .size(88.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF2A3350), Color(0xFF151A28)))),
                contentAlignment = Alignment.Center
            ) {
                if (image != null) {
                    Image(image, artist.artist, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(
                        artist.artist.trim().take(1).uppercase(),
                        style = MaterialTheme.typography.headlineSmall,
                        color = TextSecondary
                    )
                }
            }
            Text(
                rank.toString(),
                fontFamily = TelemetryFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                color = if (rank == 1) Color(0xFF1A1206) else TextPrimary,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(if (rank == 1) HifiBrass else Color(0xFF2B3243))
                    .padding(horizontal = 7.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            artist.artist,
            style = MaterialTheme.typography.labelLarge,
            color = TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
        Text(
            "${artist.plays} plays",
            style = MaterialTheme.typography.labelSmall,
            color = TextSecondary
        )
    }
}

@Composable
private fun RecentTile(item: RankedTrack, onClick: () -> Unit) {
    val alpha = if (item.available) 1f else 0.4f
    Column(
        Modifier
            .width(116.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = item.available, onClick = onClick)
    ) {
        TrackArtwork(
            uriString = item.row.filePath,
            title = item.row.title,
            artist = item.row.artist,
            isPlaying = false,
            isLarge = false,
            cornerRadius = 16.dp,
            modifier = Modifier.size(116.dp)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            item.row.title,
            style = MaterialTheme.typography.labelLarge,
            color = TextPrimary.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            item.row.artist ?: "Unknown artist",
            style = MaterialTheme.typography.labelMedium,
            color = TextSecondary.copy(alpha = alpha),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun LikedCard(count: Int, onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .audioPanel()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(LikedPink, Color(0xFF7A4DFF)))),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Favorite, null, tint = Color.White)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Liked songs", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
            Text(
                if (count == 0) "Tap the heart in the player to like a song"
                else "$count ${if (count == 1) "song" else "songs"}",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary
            )
        }
        if (count > 0) {
            IconButton(onClick = onShuffle) { Icon(Icons.Filled.Shuffle, "Shuffle liked songs", tint = TextSecondary) }
            IconButton(onClick = onPlay) { Icon(Icons.Filled.PlayArrow, "Play liked songs", tint = HifiBrass) }
        }
    }
}

@Composable
private fun QualityMix(hiRes: Int, total: Int, range: RankRange) {
    val pct = if (total == 0) 0 else (hiRes * 100f / total).toInt()
    AudioPanel(Modifier.padding(horizontal = 20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LedDot(HifiBrass)
                Spacer(Modifier.width(8.dp))
                Text("Audio quality mix", style = MaterialTheme.typography.titleSmall, color = TextPrimary, modifier = Modifier.weight(1f))
                Text(
                    "$pct%",
                    fontFamily = TelemetryFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    color = HifiBrass
                )
            }
            Spacer(Modifier.height(10.dp))
            LedBar(fraction = pct / 100f, color = HifiBrass, segments = 24)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                QualityBadge(AudioQuality.HI_RES)
                Text(
                    "$hiRes of $total plays (${rangeSubtitle(range).lowercase()}) were 24-bit or 96 kHz+",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
private fun WrappedBanner(onClick: () -> Unit) {
    // Same faceplate language as the rest of the app (ink panel, brass glow, LED bars) rather
    // than a streaming-service purple/pink gradient with a lime button.
    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF1C2338), Color(0xFF0E121C))))
            .border(1.dp, Brush.verticalGradient(listOf(HifiBrass.copy(alpha = 0.45f), Color.White.copy(alpha = 0.04f))), shape)
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(
                        listOf(HifiBrass.copy(alpha = 0.22f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(size.width * 0.92f, size.height * 0.08f),
                        radius = size.width * 0.7f
                    ),
                    radius = size.width * 0.7f,
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.92f, size.height * 0.08f)
                )
            }
            .clickable(onClick = onClick)
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                Text("YOUR WRAPPED", color = HifiBrass, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
                Spacer(Modifier.height(6.dp))
                Text("See how you listened", color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Black, lineHeight = 28.sp)
                Spacer(Modifier.height(4.dp))
                Text("Weekly, monthly and yearly recaps you can save as images.", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(14.dp))
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(HifiBrass).padding(horizontal = 18.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Open recap", color = Color(0xFF1A1206), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.width(12.dp))
            // A tiny decorative "spectrum" so the card reads as a recap at a glance.
            com.windstrom5.rebit.presentation.components.ActivityBars(
                values = listOf(3L, 5L, 4L, 7L, 6L, 9L, 8L),
                accent = SignalBlue,
                modifier = Modifier.width(76.dp)
            )
        }
    }
}

@Composable
private fun BeginnerGuideBanner(
    onOpenTutorial: () -> Unit,
    onOpenStreamingFaq: () -> Unit
) {
    Box(
        Modifier
            .padding(horizontal = 20.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        Color(0xFF0F172A),
                        Color(0xFF1E293B)
                    )
                )
            )
            .border(
                BorderStroke(1.dp, HifiBrass.copy(alpha = 0.5f)),
                RoundedCornerShape(24.dp)
            )
            .padding(20.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(HifiBrass.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Tune, null, tint = HifiBrass, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    "FIRST TIME AUDIOPHILE? • QUICKSTART",
                    color = HifiBrass,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = TelemetryFontFamily,
                    letterSpacing = 1.2.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Master Your Bit-Perfect Setup",
                color = TextPrimary,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Learn how USB direct streaming bypasses Android's 48kHz resampler, how to grant USB DAC host access, and streaming FAQ (Spotify, Tidal, Qobuz & free lossless radio).",
                color = TextSecondary,
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 20.sp
            )
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = onOpenTutorial,
                    colors = ButtonDefaults.buttonColors(containerColor = HifiBrass),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Text(
                        "Start Tutorial",
                        color = BackgroundInk,
                        fontWeight = FontWeight.Bold,
                        fontFamily = TelemetryFontFamily,
                        fontSize = 12.sp
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = BackgroundInk, modifier = Modifier.size(14.dp))
                }

                OutlinedButton(
                    onClick = onOpenStreamingFaq,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary),
                    border = BorderStroke(1.dp, OutlineSubtle),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                ) {
                    Icon(Icons.Default.Radio, null, tint = SignalTeal, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Streaming FAQ",
                        color = TextPrimary,
                        fontFamily = TelemetryFontFamily,
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun CoverTile(
    filePath: String,
    title: String,
    artist: String?,
    enabled: Boolean,
    badge: String?,
    onClick: () -> Unit
) {
    val alpha = if (enabled) 1f else 0.4f
    Column(
        Modifier.width(116.dp).clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onClick)
    ) {
        Box {
            TrackArtwork(
                uriString = filePath, title = title, artist = artist,
                isPlaying = false, isLarge = false, cornerRadius = 16.dp, modifier = Modifier.size(116.dp)
            )
            if (badge != null) {
                Text(
                    badge, fontFamily = TelemetryFontFamily, fontWeight = FontWeight.Bold, fontSize = 11.sp,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(50))
                        .background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(title, style = MaterialTheme.typography.labelLarge, color = TextPrimary.copy(alpha = alpha), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(artist ?: "Unknown artist", style = MaterialTheme.typography.labelMedium, color = TextSecondary.copy(alpha = alpha), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun GearCard(gear: com.windstrom5.rebit.data.gear.Gear, onEdit: () -> Unit) {
    AudioPanel(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
        Column(Modifier.clickable(onClick = onEdit).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LedDot(HifiBrass)
                Spacer(Modifier.width(8.dp))
                Text("My setup", style = MaterialTheme.typography.titleSmall, color = TextPrimary, modifier = Modifier.weight(1f))
                Text(if (gear.isEmpty) "Add" else "Edit", style = MaterialTheme.typography.labelLarge, color = SignalBlue)
            }
            Spacer(Modifier.height(8.dp))
            if (gear.isEmpty) {
                Text("Add your headphones, DAC and amp — they appear on your Wrapped card.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            } else {
                listOf("Headphones" to gear.headphones, "DAC" to gear.dac, "Amp" to gear.amp)
                    .filter { it.second.isNotBlank() }
                    .forEach { (k, v) ->
                        Row(Modifier.padding(vertical = 2.dp)) {
                            Text(k, style = MaterialTheme.typography.labelMedium, color = TextSecondary, modifier = Modifier.width(88.dp))
                            Text(v, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        }
                    }
            }
        }
    }
}

@Composable
private fun GearDialog(initial: com.windstrom5.rebit.data.gear.Gear, onDismiss: () -> Unit, onSave: (com.windstrom5.rebit.data.gear.Gear) -> Unit) {
    var hp by remember { mutableStateOf(initial.headphones) }
    var dac by remember { mutableStateOf(initial.dac) }
    var amp by remember { mutableStateOf(initial.amp) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("My setup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(hp, { hp = it }, label = { Text("Headphones / speakers") }, singleLine = true)
                OutlinedTextField(dac, { dac = it }, label = { Text("DAC") }, singleLine = true)
                OutlinedTextField(amp, { amp = it }, label = { Text("Amp") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(com.windstrom5.rebit.data.gear.Gear(hp, dac, amp)) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
