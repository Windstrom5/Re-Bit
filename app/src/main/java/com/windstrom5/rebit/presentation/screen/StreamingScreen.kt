package com.windstrom5.rebit.presentation.screen

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.windstrom5.rebit.presentation.components.ScreenTitle
import com.windstrom5.rebit.presentation.components.audioPanel
import com.windstrom5.rebit.presentation.components.rememberRemoteImage
import com.windstrom5.rebit.presentation.theme.BackgroundInk
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.theme.OutlineStrong
import com.windstrom5.rebit.presentation.theme.OutlineSubtle
import com.windstrom5.rebit.presentation.theme.StatusError
import com.windstrom5.rebit.presentation.theme.SurfacePanel
import com.windstrom5.rebit.presentation.theme.SurfacePanelHigh
import com.windstrom5.rebit.presentation.theme.TelemetryFontFamily
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary
import com.windstrom5.rebit.presentation.theme.TextTertiary
import com.windstrom5.rebit.presentation.viewmodel.StreamingViewModel
import com.windstrom5.rebit.streaming.SpotifyClient
import com.windstrom5.rebit.streaming.StreamTrack
import com.windstrom5.rebit.streaming.StreamingProvider
import org.koin.androidx.compose.koinViewModel

private val QobuzAccent = Color(0xFF2BA4F0)
private val TidalAccent = Color(0xFF22D3EE)
private val SpotifyAccent = Color(0xFF1DB954)

private fun StreamingProvider.accent(): Color = when (this) {
    StreamingProvider.QOBUZ -> QobuzAccent
    StreamingProvider.TIDAL -> TidalAccent
    StreamingProvider.SPOTIFY -> SpotifyAccent
}

@Composable
fun StreamingScreen(
    onBack: () -> Unit,
    viewModel: StreamingViewModel = koinViewModel()
) {
    val context = LocalContext.current
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val signedIn by viewModel.signedIn.collectAsStateWithLifecycle()
    val buffer by viewModel.buffer.collectAsStateWithLifecycle()
    val hiRes by viewModel.preferHiRes.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.openUrl.collect { url ->
            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        }
    }

    val provider = ui.provider
    val accent = provider.accent()
    val isSignedIn = provider in signedIn

    Column(
        Modifier
            .fillMaxSize()
            .background(BackgroundInk)
            .statusBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = TextPrimary)
            }
            ScreenTitle("Streaming", "Your own Qobuz, Tidal & Spotify account")
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StreamingProvider.entries.forEach { p ->
                ProviderChip(
                    provider = p,
                    selected = p == provider,
                    signedIn = p in signedIn,
                    modifier = Modifier.weight(1f),
                    onClick = { viewModel.selectProvider(p) }
                )
            }
        }

        buffer?.let { b ->
            Column(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).audioPanel().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val mb = b.bytesRead / 1_048_576.0
                Text(
                    if (b.totalBytes > 0) "Buffering • %.1f / %.1f MB".format(mb, b.totalBytes / 1_048_576.0)
                    else "Preparing stream…",
                    fontFamily = TelemetryFontFamily, fontSize = 11.sp, color = TextSecondary
                )
                if (b.totalBytes > 0) {
                    LinearProgressIndicator(
                        progress = { b.fraction },
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)),
                        color = accent, trackColor = SurfacePanelHigh
                    )
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth(), color = accent, trackColor = SurfacePanelHigh)
                }
            }
        }

        ui.message?.let { msg ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(StatusError.copy(alpha = 0.12f))
                    .border(BorderStroke(1.dp, StatusError.copy(alpha = 0.4f)), RoundedCornerShape(12.dp))
                    .padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                IconButton(onClick = viewModel::dismissMessage) {
                    Icon(Icons.Filled.Close, "Dismiss", tint = TextSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!isSignedIn) {
                item(key = "setup-${provider.id}") {
                    when (provider) {
                        StreamingProvider.QOBUZ -> QobuzSetupCard(viewModel, ui.signingIn)
                        StreamingProvider.TIDAL -> TidalSetupCard(viewModel, ui.signingIn, ui.tidalCode)
                        StreamingProvider.SPOTIFY -> SpotifySetupCard(viewModel)
                    }
                }
            } else {
                item(key = "search") {
                    SearchBar(
                        query = ui.query, accent = accent, searching = ui.searching,
                        onChange = viewModel::onQueryChange, onSearch = viewModel::search
                    )
                }
                if (provider == StreamingProvider.QOBUZ) {
                    item(key = "quality") {
                        Row(
                            Modifier.fillMaxWidth().audioPanel().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("Prefer Hi-Res", color = TextPrimary, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Up to 24-bit / 192 kHz when your plan and the track allow it. Falls back to CD quality.",
                                    color = TextSecondary, style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Switch(
                                checked = hiRes, onCheckedChange = viewModel::setPreferHiRes,
                                colors = SwitchDefaults.colors(checkedTrackColor = accent)
                            )
                        }
                    }
                }
                if (provider == StreamingProvider.SPOTIFY) {
                    item(key = "spotify-note") {
                        NoticeCard(
                            "Plays in the Spotify app, not bit-perfect",
                            "Spotify never gives other apps its audio, so tracks you pick here are started in the official Spotify app and use Android's normal audio path."
                        )
                    }
                }
                if (ui.searched && ui.results.isEmpty()) {
                    item(key = "empty") {
                        Text("No tracks found.", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                items(ui.results, key = { it.uri }) { t ->
                    TrackRow(t, accent, playing = ui.playingUri == t.uri) { viewModel.play(t) }
                }
                item(key = "signout") {
                    OutlinedButton(
                        onClick = { viewModel.signOut(provider) },
                        border = BorderStroke(1.dp, OutlineSubtle),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Logout, null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Sign out of ${provider.displayName}", color = TextSecondary)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderChip(
    provider: StreamingProvider,
    selected: Boolean,
    signedIn: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val accent = provider.accent()
    Row(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) accent.copy(alpha = 0.16f) else SurfacePanel)
            .border(BorderStroke(1.dp, if (selected) accent else OutlineSubtle), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.width(8.dp))
        Text(
            provider.displayName,
            color = if (selected) accent else TextSecondary,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 13.sp
        )
        if (signedIn) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Filled.Check, "Signed in", tint = accent, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
private fun SearchBar(
    query: String,
    accent: Color,
    searching: Boolean,
    onChange: (String) -> Unit,
    onSearch: () -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        placeholder = { Text("Search tracks, artists, albums", color = TextTertiary) },
        leadingIcon = { Icon(Icons.Filled.Search, null, tint = TextSecondary) },
        trailingIcon = {
            if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = accent)
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
        shape = RoundedCornerShape(14.dp),
        colors = fieldColors(accent),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun TrackRow(track: StreamTrack, accent: Color, playing: Boolean, onClick: () -> Unit) {
    val art = rememberRemoteImage(track.artworkUrl)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (playing) accent.copy(alpha = 0.10f) else SurfacePanel)
            .border(BorderStroke(1.dp, if (playing) accent.copy(alpha = 0.6f) else OutlineSubtle), RoundedCornerShape(14.dp))
            .clickable(enabled = track.streamable, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(52.dp).clip(RoundedCornerShape(10.dp)).background(SurfacePanelHigh),
            contentAlignment = Alignment.Center
        ) {
            if (art != null) {
                Image(art, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Icon(Icons.Filled.MusicNote, null, tint = TextTertiary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title, color = if (track.streamable) TextPrimary else TextTertiary,
                style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                listOfNotNull(track.artist, track.album).joinToString(" • "),
                color = TextSecondary, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(4.dp))
            Text(
                track.qualityLabel, fontFamily = TelemetryFontFamily, fontSize = 10.sp,
                color = if (track.maxBitDepth > 16 || track.maxSampleRateHz > 48_000) HifiBrass else TextTertiary
            )
        }
        Icon(Icons.Filled.PlayArrow, "Play", tint = if (track.streamable) accent else TextTertiary)
    }
}

// ── Sign-in cards ───────────────────────────────────────────────────────────

@Composable
private fun QobuzSetupCard(vm: StreamingViewModel, busy: Boolean) {
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf(vm.savedQobuzAppId) }
    var secret by remember { mutableStateOf("") }
    SetupCard(
        accent = QobuzAccent,
        title = "Connect Qobuz",
        body = "Sign in with your own Qobuz subscription (Studio or Sublime for Hi-Res). You also need the app id and secret Qobuz issued to you. Your password is used once and never stored.",
    ) {
        Field("Email or username", user, { user = it }, QobuzAccent, keyboard = KeyboardType.Email)
        Field("Password", pass, { pass = it }, QobuzAccent, secret = true)
        Field("App id", appId, { appId = it }, QobuzAccent)
        Field("App secret", secret, { secret = it }, QobuzAccent, secret = true)
        PrimaryButton("Sign in", QobuzAccent, busy, enabled = user.isNotBlank() && pass.isNotBlank() && appId.isNotBlank() && secret.isNotBlank()) {
            vm.signInQobuz(user, pass, appId, secret)
            pass = ""
        }
    }
}

@Composable
private fun TidalSetupCard(
    vm: StreamingViewModel,
    busy: Boolean,
    code: com.windstrom5.rebit.streaming.TidalClient.DeviceCode?
) {
    var clientId by remember { mutableStateOf(vm.savedTidalClientId) }
    var secret by remember { mutableStateOf("") }
    SetupCard(
        accent = TidalAccent,
        title = "Connect Tidal",
        body = "Uses Tidal's device sign-in: you approve a short code in your browser, so your password never touches this app. You supply your own Tidal client id. Plays CD-quality lossless FLAC (Hi-Res DASH isn't supported yet).",
    ) {
        if (code == null) {
            Field("Client id", clientId, { clientId = it }, TidalAccent)
            Field("Client secret (if your id has one)", secret, { secret = it }, TidalAccent, secret = true)
            PrimaryButton("Get sign-in code", TidalAccent, busy, enabled = clientId.isNotBlank()) {
                vm.signInTidal(clientId, secret)
            }
        } else {
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SurfacePanelHigh).padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Enter this code on Tidal's page", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                Text(code.userCode, color = TidalAccent, fontFamily = TelemetryFontFamily, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = TidalAccent)
                    Spacer(Modifier.width(8.dp))
                    Text("Waiting for approval…", color = TextSecondary, style = MaterialTheme.typography.bodySmall)
                }
            }
            OutlinedButton(
                onClick = vm::cancelTidalSignIn, shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, OutlineSubtle), modifier = Modifier.fillMaxWidth()
            ) { Text("Cancel", color = TextSecondary) }
        }
    }
}

@Composable
private fun SpotifySetupCard(vm: StreamingViewModel) {
    var clientId by remember { mutableStateOf(vm.savedSpotifyClientId) }
    SetupCard(
        accent = SpotifyAccent,
        title = "Connect Spotify",
        body = "Needs Spotify Premium and a client id from developer.spotify.com. Register this redirect URI in your Spotify app: ${SpotifyClient.REDIRECT_URI}. Spotify tracks play in the Spotify app, not through the USB driver.",
    ) {
        Field("Client id", clientId, { clientId = it }, SpotifyAccent)
        PrimaryButton("Sign in with Spotify", SpotifyAccent, busy = false, enabled = clientId.isNotBlank()) {
            vm.signInSpotify(clientId)
        }
    }
}

@Composable
private fun SetupCard(accent: Color, title: String, body: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().audioPanel(RoundedCornerShape(18.dp)).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(title, color = accent, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(body, color = TextSecondary, style = MaterialTheme.typography.bodySmall, lineHeight = 18.sp)
        content()
        Text(
            "Use only with your own paid account. Streaming services' terms govern how third-party apps may access them — you are responsible for complying with yours.",
            color = TextTertiary, style = MaterialTheme.typography.labelSmall, lineHeight = 14.sp
        )
    }
}

@Composable
private fun NoticeCard(title: String, body: String) {
    Row(
        Modifier.fillMaxWidth().audioPanel().padding(14.dp),
        verticalAlignment = Alignment.Top
    ) {
        Icon(Icons.Filled.Info, null, tint = HifiBrass, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = TextPrimary, style = MaterialTheme.typography.titleSmall)
            Text(body, color = TextSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    accent: Color,
    secret: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboard),
        shape = RoundedCornerShape(12.dp),
        colors = fieldColors(accent),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun fieldColors(accent: Color) = OutlinedTextFieldDefaults.colors(
    focusedTextColor = TextPrimary,
    unfocusedTextColor = TextPrimary,
    focusedBorderColor = accent,
    unfocusedBorderColor = OutlineStrong,
    focusedLabelColor = accent,
    unfocusedLabelColor = TextSecondary,
    cursorColor = accent
)

@Composable
private fun PrimaryButton(text: String, accent: Color, busy: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = accent, contentColor = BackgroundInk),
        modifier = Modifier.fillMaxWidth().height(48.dp)
    ) {
        if (busy) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = BackgroundInk)
        } else {
            Text(text, fontWeight = FontWeight.Bold)
        }
    }
}
