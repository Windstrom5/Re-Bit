package com.windstrom5.rebit.sync

enum class SharedListeningRole { NONE, HOST, GUEST }

/** One other device, either found while browsing for a host to join, or
 *  actually connected (as host-sees-guest or guest-sees-host). */
data class SharedListeningPeer(val endpointId: String, val displayName: String)

data class SharedListeningState(
    val role: SharedListeningRole = SharedListeningRole.NONE,
    val isAdvertising: Boolean = false,
    val isDiscovering: Boolean = false,
    val connectedPeers: List<SharedListeningPeer> = emptyList(),
    /** Hosts found while browsing — only populated in the GUEST role, before
     *  connecting to one of them. */
    val discoveredHosts: List<SharedListeningPeer> = emptyList(),
    val statusMessage: String = "",
    val lastError: String? = null,
    /** Set on a GUEST when the host's most recent TrackChange couldn't be
     *  matched to anything in this device's own local library — see
     *  SharedListeningManager's doc comment on why this can't stream audio
     *  itself. Surfaced so the UI can say "doesn't have this song locally"
     *  rather than silently doing nothing. */
    val trackUnavailableLocally: Boolean = false
)
