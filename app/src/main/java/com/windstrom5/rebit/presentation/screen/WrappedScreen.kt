package com.windstrom5.rebit.presentation.screen

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.windstrom5.rebit.data.history.PersonaMath
import com.windstrom5.rebit.data.history.StatsMath
import com.windstrom5.rebit.data.history.WrappedData
import com.windstrom5.rebit.data.history.WrappedPeriod
import com.windstrom5.rebit.presentation.components.ActivityBars
import com.windstrom5.rebit.presentation.components.ImageExporter
import com.windstrom5.rebit.presentation.components.SegmentedTabs
import com.windstrom5.rebit.presentation.components.TrackArtwork
import com.windstrom5.rebit.presentation.components.rememberRemoteImage
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.viewmodel.WrappedViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.androidx.compose.koinViewModel

private const val PAGE_COUNT = 10
private const val PAGE_MS = 6000f
private val Lime = Color(0xFFD7FF3C)
private val Ink = Color(0xFF0B0B10)

/**
 * "Wrapped": a story-style recap of your listening. Tap right/left to move, hold to pause,
 * and save or share any card (or all of them) as PNG.
 */
@Composable
fun WrappedScreen(
    onClose: () -> Unit,
    viewModel: WrappedViewModel = koinViewModel()
) {
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val data = ui.data
    val pageCount = if (data?.hasData == true) PAGE_COUNT else 1
    val pagerState = rememberPagerState(pageCount = { pageCount })
    val layers = List(PAGE_COUNT) { rememberGraphicsLayer() }

    var paused by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }

    LaunchedEffect(ui.period) { pagerState.scrollToPage(0) }

    // Auto-advance: fill the story bar over PAGE_MS, pausing while pressed or exporting.
    LaunchedEffect(pagerState.currentPage, pageCount) {
        progress = 0f
        var last = withFrameNanos { it }
        while (progress < 1f) {
            val now = withFrameNanos { it }
            if (!paused && !exporting) progress = (progress + (now - last) / 1_000_000f / PAGE_MS).coerceAtMost(1f)
            last = now
        }
        if (pagerState.currentPage < pageCount - 1) pagerState.animateScrollToPage(pagerState.currentPage + 1)
    }

    suspend fun capture(page: Int): Bitmap =
        layers[page].toImageBitmap().asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false)

    fun fileName(page: Int) = "wrapped_${ui.period.name.lowercase()}_${page + 1}"

    fun saveOrShare(bitmaps: List<Pair<Int, Bitmap>>) {
        val saved = bitmaps.map { (page, bmp) -> ImageExporter.saveToGallery(ctx, bmp, fileName(page)) }
        if (saved.all { it != null }) {
            Toast.makeText(ctx, "Saved ${saved.size} image${if (saved.size == 1) "" else "s"} to Pictures/BitPerfect", Toast.LENGTH_LONG).show()
        } else {
            // Older Android (or a write failure): hand the images to the share sheet instead.
            ImageExporter.share(ctx, bitmaps.map { (page, bmp) -> ImageExporter.cacheForSharing(ctx, bmp, fileName(page)) })
        }
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(top = 8.dp, bottom = 12.dp)
    ) {
        // ── Header: close + period ──
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = Color.White) }
            SegmentedTabs(
                options = WrappedPeriod.entries.map { it.label },
                selectedIndex = ui.period.ordinal,
                onSelect = { viewModel.setPeriod(WrappedPeriod.entries[it]) },
                modifier = Modifier.weight(1f).padding(end = 8.dp),
                accent = Lime
            )
        }

        // ── Story card (9:16) ──
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            val w = minOf(maxWidth, maxHeight * 9f / 16f)
            Box(Modifier.width(w).height(w * 16f / 9f).clip(RoundedCornerShape(28.dp))) {
                if (ui.loading || data == null) {
                    Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Lime)
                    }
                } else {
                    HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                        Box(
                            Modifier
                                .fillMaxSize()
                                .pointerInput(page) {
                                    detectTapGestures(
                                        onPress = { paused = true; tryAwaitRelease(); paused = false },
                                        onTap = { offset ->
                                            scope.launch {
                                                val cur = pagerState.currentPage
                                                if (offset.x < size.width * 0.3f) {
                                                    if (cur > 0) pagerState.animateScrollToPage(cur - 1)
                                                } else if (cur < pageCount - 1) {
                                                    pagerState.animateScrollToPage(cur + 1)
                                                }
                                            }
                                        }
                                    )
                                }
                                .drawWithContent {
                                    layers[page].record { this@drawWithContent.drawContent() }
                                    drawLayer(layers[page])
                                }
                        ) {
                            WrappedPage(page, data, ui.topArtistImageUrl, ui.gearLine)
                        }
                    }
                    // Story progress bars (overlay, not part of the exported image)
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        repeat(pageCount) { i ->
                            val fill = when {
                                i < pagerState.currentPage -> 1f
                                i == pagerState.currentPage -> progress
                                else -> 0f
                            }
                            Box(
                                Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp))
                                    .background(Color.White.copy(alpha = 0.3f))
                            ) {
                                Box(Modifier.fillMaxHeight().fillMaxWidth(fill).background(Color.White))
                            }
                        }
                    }
                }
            }
        }

        // ── Actions ──
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val enabled = data != null && !ui.loading && !exporting
            ActionPill("Save PNG", Icons.Filled.Download, enabled, Lime, Ink) {
                scope.launch {
                    exporting = true
                    try {
                        val p = pagerState.currentPage
                        saveOrShare(listOf(p to capture(p)))
                    } finally { exporting = false }
                }
            }
            ActionPill("Share", Icons.Filled.Share, enabled, Color.White.copy(alpha = 0.12f), Color.White) {
                scope.launch {
                    exporting = true
                    try {
                        val p = pagerState.currentPage
                        ImageExporter.share(ctx, listOf(ImageExporter.cacheForSharing(ctx, capture(p), fileName(p))))
                    } finally { exporting = false }
                }
            }
            if (pageCount > 1) {
                ActionPill("All", Icons.Filled.PhotoLibrary, enabled, Color.White.copy(alpha = 0.12f), Color.White) {
                    scope.launch {
                        exporting = true
                        try {
                            val shots = ArrayList<Pair<Int, Bitmap>>()
                            for (i in 0 until pageCount) {
                                pagerState.scrollToPage(i)
                                delay(900) // let the page compose and settle before capturing
                                shots += i to capture(i)
                            }
                            saveOrShare(shots)
                        } finally { exporting = false }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean, bg: Color, fg: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .alpha(if (enabled) 1f else 0.4f)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = fg, fontWeight = FontWeight.Bold)
    }
}

// ───────────────────────────── Cards ─────────────────────────────

@Composable
private fun WrappedPage(index: Int, data: WrappedData, artistImageUrl: String?, gearLine: String?) {
    if (!data.hasData) { EmptyCard(); return }
    when (index) {
        0 -> IntroCard(data)
        1 -> MinutesCard(data)
        2 -> TopArtistCard(data, artistImageUrl)
        3 -> TopSongCard(data)
        4 -> TopSongsListCard(data)
        5 -> TopArtistsListCard(data)
        6 -> HiResCard(data)
        7 -> PersonaCard(data)
        8 -> StreakCard(data)
        else -> SummaryCard(data, artistImageUrl, gearLine)
    }
}

@Composable
private fun CardFrame(brush: Brush, content: @Composable BoxScope.() -> Unit) {
    Box(Modifier.fillMaxSize().background(brush), content = content)
}

@Composable
private fun CardColumn(
    modifier: Modifier = Modifier,
    horizontalFill: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        modifier.fillMaxSize().padding(start = 28.dp, end = 28.dp, top = 56.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = if (horizontalFill) Alignment.CenterHorizontally else Alignment.Start,
        content = content
    )
}

@Composable
private fun Kicker(text: String, color: Color) {
    Text(text.uppercase(), color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
}

@Composable
private fun Giant(text: String, color: Color, size: TextUnit = 56.sp, maxLines: Int = 3) {
    Text(
        text, color = color, fontSize = size, fontWeight = FontWeight.Black,
        lineHeight = size * 1.0f, letterSpacing = (-1.5).sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun Brand(color: Color, modifier: Modifier = Modifier) {
    Text("BITPERFECT USB", color = color.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = modifier)
}

@Composable
private fun Blobs(color: Color) {
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(color.copy(alpha = 0.10f), radius = size.width * 0.65f, center = Offset(size.width * 1.0f, size.height * 0.05f))
        drawCircle(color.copy(alpha = 0.07f), radius = size.width * 0.5f, center = Offset(0f, size.height * 0.95f))
    }
}

@Composable
private fun EmptyCard() = CardFrame(Brush.verticalGradient(listOf(Color(0xFF1A1040), Ink))) {
    CardColumn {
        Giant("No plays yet", Color.White, 44.sp)
        Spacer(Modifier.height(12.dp))
        Text(
            "Listen to a few songs (30 seconds each counts as a play) and come back for your recap.",
            color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp
        )
    }
}

@Composable
private fun IntroCard(d: WrappedData) = CardFrame(Brush.linearGradient(listOf(Color(0xFF3B1C8C), Color(0xFFE0338A)))) {
    Blobs(Color.White)
    CardColumn {
        Brand(Color.White)
        Spacer(Modifier.height(28.dp))
        Giant("Your", Color.White, 64.sp, 1)
        Giant(d.period.adjective, Lime, 64.sp, 1)
        Giant("Wrapped", Color.White, 64.sp, 1)
        Spacer(Modifier.height(20.dp))
        Text("Tap to see how you listened.", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
    }
}

@Composable
private fun MinutesCard(d: WrappedData) = CardFrame(Brush.linearGradient(listOf(Color(0xFFFF8A3D), Color(0xFFFF3D6E)))) {
    Blobs(Color.White)
    CardColumn {
        Kicker("You listened for", Color.White.copy(alpha = 0.9f))
        Spacer(Modifier.height(8.dp))
        Giant("%,d".format(d.minutes), Ink, 96.sp, 1)
        Giant("minutes", Color.White, 40.sp, 1)
        Spacer(Modifier.height(20.dp))
        val albums = StatsMath.albumsEquivalent(d.totals.listenedMs)
        if (albums >= 1) {
            Text("That's about $albums album${if (albums == 1) "" else "s"} back to back.", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
        }
        Text(
            "${d.totals.plays} plays · ${d.totals.tracks} songs · ${d.totals.artists} artists",
            color = Ink.copy(alpha = 0.8f), fontSize = 15.sp, fontWeight = FontWeight.Medium
        )
    }
}

@Composable
private fun TopArtistCard(d: WrappedData, imageUrl: String?) = CardFrame(Brush.linearGradient(listOf(Color(0xFF1E3CFF), Color(0xFF7A4DFF)))) {
    Blobs(Color.White)
    val a = d.topArtists.firstOrNull()
    CardColumn {
        Kicker("Your top artist", Color.White.copy(alpha = 0.9f))
        Spacer(Modifier.height(20.dp))
        val img = rememberRemoteImage(imageUrl)
        Box(Modifier.size(200.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            if (img != null) Image(img, a?.artist, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(a?.artist?.take(1)?.uppercase() ?: "?", color = Color.White, fontSize = 80.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(24.dp))
        Giant(a?.artist ?: "—", Lime, 46.sp, 2)
        Spacer(Modifier.height(8.dp))
        if (a != null) Text("${a.plays} plays · ${StatsMath.formatListening(a.listenedMs)}", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun TopSongCard(d: WrappedData) = CardFrame(Brush.linearGradient(listOf(Lime, Color(0xFF8CF0A0)))) {
    val t = d.topTracks.firstOrNull()
    CardColumn {
        Kicker("Your #1 song", Ink.copy(alpha = 0.7f))
        Spacer(Modifier.height(20.dp))
        if (t != null) {
            TrackArtwork(
                uriString = t.filePath, title = t.title, artist = t.artist,
                isPlaying = false, isLarge = true, cornerRadius = 24.dp,
                modifier = Modifier.size(230.dp)
            )
            Spacer(Modifier.height(22.dp))
            Giant(t.title, Ink, 36.sp, 3)
            Spacer(Modifier.height(6.dp))
            Text(t.artist ?: "Unknown artist", color = Ink.copy(alpha = 0.75f), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(10.dp))
            Text("Played ${t.plays} time${if (t.plays == 1) "" else "s"}", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun TopSongsListCard(d: WrappedData) = CardFrame(Brush.verticalGradient(listOf(Color(0xFF1B1F5E), Color(0xFF3A0F4F)))) {
    CardColumn {
        Kicker("Your top songs", Lime)
        Spacer(Modifier.height(18.dp))
        d.topTracks.forEachIndexed { i, t ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = if (i == 0) Lime else Color.White.copy(alpha = 0.6f), fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(38.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(t.artist ?: "Unknown artist", color = Color.White.copy(alpha = 0.65f), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text("${t.plays}×", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun TopArtistsListCard(d: WrappedData) = CardFrame(Brush.linearGradient(listOf(Color(0xFF00C2A8), Color(0xFF0B3D91)))) {
    Blobs(Color.White)
    CardColumn {
        Kicker("Your top artists", Color.White)
        Spacer(Modifier.height(18.dp))
        d.topArtists.forEachIndexed { i, a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", color = if (i == 0) Lime else Color.White.copy(alpha = 0.7f), fontSize = 30.sp, fontWeight = FontWeight.Black, modifier = Modifier.width(38.dp))
                Text(
                    a.artist, color = Color.White, fontSize = if (i == 0) 26.sp else 20.sp, fontWeight = FontWeight.Black,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
                )
                Text("${a.plays}", color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HiResCard(d: WrappedData) = CardFrame(Brush.verticalGradient(listOf(Color(0xFF16110A), Ink))) {
    CardColumn(horizontalFill = true) {
        Kicker("Audio quality", HifiBrass)
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(220.dp).align(Alignment.CenterHorizontally), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 22.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(Color.White.copy(alpha = 0.08f), -90f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                drawArc(HifiBrass, -90f, 360f * d.hiResPercent / 100f, false, Offset(inset, inset), arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Text("${d.hiResPercent}%", color = HifiBrass, fontSize = 56.sp, fontWeight = FontWeight.Black)
        }
        Spacer(Modifier.height(26.dp))
        Text(
            if (d.hiResPercent >= 50) "Hi-Res listener" else "Lossless loyalist",
            color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "${d.totals.hiResPlays} of ${d.totals.plays} plays were 24-bit or 96 kHz and up. Every play was lossless.",
            color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.CenterHorizontally)
        )
    }
}

@Composable
private fun PersonaCard(d: WrappedData) = CardFrame(Brush.linearGradient(listOf(Color(0xFF1A1040), Color(0xFF4B2A8F)))) {
    Blobs(Color.White)
    CardColumn {
        Kicker("Your listening personality", Lime)
        Spacer(Modifier.height(10.dp))
        Giant(d.persona.title, Color.White, 52.sp, 2)
        Spacer(Modifier.height(8.dp))
        Text(d.persona.blurb, color = Color.White.copy(alpha = 0.8f), fontSize = 17.sp)
        Spacer(Modifier.height(28.dp))
        ActivityBars(values = d.hourPlays.map { it.toLong() }, accent = Lime, highlightLast = false)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("12 AM", "6 AM", "12 PM", "6 PM").forEach { Text(it, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp) }
        }
        PersonaMath.peakHour(d.hourPlays.toIntArray())?.let {
            Spacer(Modifier.height(12.dp))
            Text("Peak hour: ${PersonaMath.hourLabel(it)}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StreakCard(d: WrappedData) = CardFrame(Brush.linearGradient(listOf(Color(0xFFFF3D3D), Color(0xFFFF9A3D)))) {
    Blobs(Color.White)
    CardColumn {
        Kicker("Longest streak", Color.White.copy(alpha = 0.9f))
        Spacer(Modifier.height(6.dp))
        Giant("${d.longestStreak}", Ink, 110.sp, 1)
        Giant(if (d.longestStreak == 1) "day in a row" else "days in a row", Color.White, 30.sp, 1)
        Spacer(Modifier.height(28.dp))
        ActivityBars(values = d.weekdayPlays.map { it.toLong() }, accent = Color.White, highlightLast = false)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth()) {
            "MTWTFSS".forEach { Text(it.toString(), modifier = Modifier.weight(1f), textAlign = TextAlign.Center, color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
        val busiest = d.weekdayPlays.withIndex().maxByOrNull { it.value }?.takeIf { it.value > 0 }?.index
        if (busiest != null) {
            Spacer(Modifier.height(14.dp))
            Text("${PersonaMath.WEEKDAYS[busiest]}s are your day.", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** The one built to be shared: everything important on a single card. */
@Composable
private fun SummaryCard(d: WrappedData, imageUrl: String?, gearLine: String?) = CardFrame(Brush.linearGradient(listOf(Color(0xFF241055), Color(0xFF0B0B10)))) {
    Blobs(Lime)
    CardColumn {
        Brand(Color.White)
        Spacer(Modifier.height(10.dp))
        Giant("My ${d.period.adjective}", Color.White, 34.sp, 1)
        Giant("Wrapped", Lime, 54.sp, 1)
        Spacer(Modifier.height(22.dp))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Kicker("Top artist", Color.White.copy(alpha = 0.6f))
                Text(d.topArtists.firstOrNull()?.artist ?: "—", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Column(Modifier.weight(1f)) {
                Kicker("Minutes", Color.White.copy(alpha = 0.6f))
                Text("%,d".format(d.minutes), color = Lime, fontSize = 20.sp, fontWeight = FontWeight.Black)
            }
        }
        Spacer(Modifier.height(20.dp))
        Kicker("Top songs", Color.White.copy(alpha = 0.6f))
        Spacer(Modifier.height(6.dp))
        d.topTracks.take(3).forEachIndexed { i, t ->
            Text("${i + 1}  ${t.title}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 3.dp))
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatChip("${d.hiResPercent}% Hi-Res")
            StatChip("${d.longestStreak}-day streak")
            StatChip(d.persona.title)
        }
        if (gearLine != null) {
            Spacer(Modifier.height(14.dp))
            Text("Listening on", color = Color.White.copy(alpha = 0.55f), fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
            Text(gearLine, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun StatChip(text: String) {
    Text(
        text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.14f)).padding(horizontal = 10.dp, vertical = 6.dp)
    )
}
