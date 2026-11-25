package com.windstrom5.rebit.presentation.dialog

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.windstrom5.rebit.sync.SharedListeningManager
import com.windstrom5.rebit.sync.SharedListeningPeer
import com.windstrom5.rebit.sync.SharedListeningRole
import com.windstrom5.rebit.sync.SharedListeningState
import com.windstrom5.rebit.presentation.theme.HifiBrass
import com.windstrom5.rebit.presentation.theme.SignalBlue
import com.windstrom5.rebit.presentation.theme.StatusError
import com.windstrom5.rebit.presentation.theme.StatusGood
import com.windstrom5.rebit.presentation.theme.SurfaceInk
import com.windstrom5.rebit.presentation.theme.SurfacePanel
import com.windstrom5.rebit.presentation.theme.SurfacePanelHigh
import com.windstrom5.rebit.presentation.theme.TextPrimary
import com.windstrom5.rebit.presentation.theme.TextSecondary
import com.windstrom5.rebit.presentation.theme.TextTertiary

/**
 * Shared Listening — start/join a session synced over Bluetooth/Wi-Fi
 * Direct. See SharedListeningManager's class doc comment for exactly what
 * this does and doesn't do (command-sync only, no audio streaming, and its
 * honest verification status — untested on real multi-device hardware).
 */
@Composable
fun SharedListeningDialog(
    state: SharedListeningState,
    onDismiss: () -> Unit,
    onStartHosting: (sessionName: String) -> Unit,
    onDiscoverSessions: () -> Unit,
    onJoinHost: (endpointId: String, localDisplayName: String) -> Unit,
    onLeaveSession: () -> Unit
) {
    val context = LocalContext.current
    var pendingAction by remember { mutableStateOf<PendingAction?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            when (pendingAction) {
                is PendingAction.Host -> onStartHosting((pendingAction as PendingAction.Host).sessionName)
                PendingAction.Discover -> onDiscoverSessions()
                null -> {}
            }
        }
        pendingAction = null
    }

    fun runWithPermissions(action: PendingAction) {
        if (SharedListeningManager.hasRequiredPermissions(context)) {
            when (action) {
                is PendingAction.Host -> onStartHosting(action.sessionName)
                PendingAction.Discover -> onDiscoverSessions()
            }
        } else {
            pendingAction = action
            permissionLauncher.launch(SharedListeningManager.requiredPermissions())
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).padding(vertical = 8.dp),
            shape = RoundedCornerShape(20.dp),
            color = SurfaceInk,
            border = BorderStroke(1.dp, SignalBlue.copy(alpha = 0.35f))
        ) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Wifi, contentDescription = null, tint = SignalBlue, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Shared Listening", style = MaterialTheme.typography.titleLarge, color = TextPrimary)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    "Syncs play/pause/seek with nearby devices over Bluetooth/Wi-Fi Direct — no server, no internet. Each device needs its own local copy of the song; this doesn't stream audio between phones.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))

                state.lastError?.let { error ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = StatusError.copy(alpha = 0.12f)),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
                    ) {
                        Text(error, style = MaterialTheme.typography.bodySmall, color = StatusError, modifier = Modifier.padding(10.dp))
                    }
                }

                when (state.role) {
                    SharedListeningRole.NONE -> IdleContent(
                        onHost = { name -> runWithPermissions(PendingAction.Host(name)) },
                        onDiscover = { runWithPermissions(PendingAction.Discover) }
                    )
                    SharedListeningRole.HOST -> HostContent(state, onLeaveSession)
                    SharedListeningRole.GUEST -> GuestContent(state, onJoinHost, onLeaveSession)
                }
            }
        }
    }
}

private sealed class PendingAction {
    data class Host(val sessionName: String) : PendingAction()
    data object Discover : PendingAction()
}

@Composable
private fun IdleContent(onHost: (String) -> Unit, onDiscover: () -> Unit) {
    var sessionName by remember { mutableStateOf("") }

    Column {
        Text("Host a session", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = sessionName,
            onValueChange = { sessionName = it },
            placeholder = { Text("Session name (e.g. \"Living room\")") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
                focusedBorderColor = SignalBlue, unfocusedBorderColor = SurfacePanelHigh
            ),
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(10.dp))
        Button(
            onClick = { onHost(sessionName.ifBlank { "Karaoke Session" }) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Filled.PersonAdd, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Start Hosting")
        }

        Spacer(modifier = Modifier.height(20.dp))
        HorizontalDivider(color = SurfacePanelHigh)
        Spacer(modifier = Modifier.height(20.dp))

        Text("Join a session", style = MaterialTheme.typography.titleSmall, color = TextPrimary)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Looks for nearby devices already hosting a session.",
            style = MaterialTheme.typography.bodySmall, color = TextSecondary
        )
        Spacer(modifier = Modifier.height(10.dp))
        OutlinedButton(onClick = onDiscover, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Filled.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Search Nearby")
        }
    }
}

@Composable
private fun HostContent(state: SharedListeningState, onLeaveSession: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = StatusGood)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Hosting — visible to nearby devices", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        }
        Spacer(modifier = Modifier.height(14.dp))
        Text(
            "Connected devices (${state.connectedPeers.size})",
            style = MaterialTheme.typography.labelMedium, color = TextTertiary
        )
        Spacer(modifier = Modifier.height(8.dp))
        if (state.connectedPeers.isEmpty()) {
            Text("Nobody's joined yet.", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (peer in state.connectedPeers) {
                    PeerRow(peer)
                }
            }
        }
        Spacer(modifier = Modifier.height(18.dp))
        OutlinedButton(
            onClick = onLeaveSession,
            modifier = Modifier.fillMaxWidth(),
            border = BorderStroke(1.dp, StatusError.copy(alpha = 0.5f))
        ) {
            Icon(Icons.Filled.SensorsOff, contentDescription = null, tint = StatusError, modifier = Modifier.size(18.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Stop Hosting", color = StatusError)
        }
    }
}

@Composable
private fun GuestContent(
    state: SharedListeningState,
    onJoinHost: (String, String) -> Unit,
    onLeaveSession: () -> Unit
) {
    val connectedHost = state.connectedPeers.firstOrNull()
    Column {
        if (connectedHost != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.People, contentDescription = null, tint = StatusGood, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connected to ${connectedHost.displayName}", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            }
            if (state.trackUnavailableLocally) {
                Spacer(modifier = Modifier.height(10.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = HifiBrass.copy(alpha = 0.12f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        "The host's current track isn't in your library — playback will resume automatically once it matches something you have.",
                        style = MaterialTheme.typography.bodySmall, color = HifiBrass, modifier = Modifier.padding(10.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(18.dp))
            OutlinedButton(
                onClick = onLeaveSession,
                modifier = Modifier.fillMaxWidth(),
                border = BorderStroke(1.dp, StatusError.copy(alpha = 0.5f))
            ) {
                Text("Leave Session", color = StatusError)
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = SignalBlue)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Searching for nearby sessions...", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
            }
            Spacer(modifier = Modifier.height(14.dp))
            if (state.discoveredHosts.isEmpty()) {
                Text("Nothing found yet.", style = MaterialTheme.typography.bodySmall, color = TextTertiary)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (host in state.discoveredHosts) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfacePanel)
                                .clickable { onJoinHost(host.endpointId, android.os.Build.MODEL ?: "This device") }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(host.displayName, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                            Text("Join", style = MaterialTheme.typography.labelMedium, color = SignalBlue)
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(14.dp))
            OutlinedButton(onClick = onLeaveSession, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel")
            }
        }
    }
}

@Composable
private fun PeerRow(peer: SharedListeningPeer) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(SurfacePanel)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(StatusGood)
        ) {}
        Spacer(modifier = Modifier.width(10.dp))
        Text(peer.displayName, style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
    }
}
