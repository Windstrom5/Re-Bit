package com.windstrom5.rebit.sync

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID

/**
 * Shared Listening (Local P2P Sync)
 * Enables multiple devices to synchronize playback states (play, pause, seek, track change)
 * in real-time over local Wi-Fi / Hotspot using UDP Broadcast for discovery and TCP sockets
 * for reliable low-latency (<20ms) control message streaming. No cloud backend needed!
 */
class SharedListeningManager(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    private val _state = MutableStateFlow(SharedListeningState())
    val state: StateFlow<SharedListeningState> = _state.asStateFlow()

    private var onCommandReceived: ((SyncCommand) -> Unit)? = null

    private var udpBeaconJob: Job? = null
    private var udpDiscoveryJob: Job? = null
    private var tcpServerJob: Job? = null
    private var tcpClientJob: Job? = null

    private val connectedSockets = mutableListOf<Socket>()
    private var clientSocket: Socket? = null

    private var multicastLock: WifiManager.MulticastLock? = null

    private fun acquireMulticastLock() {
        try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("ReBitSharedListening")?.apply {
                setReferenceCounted(true)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire multicast lock: ${e.message}")
        }
    }

    private fun releaseMulticastLock() {
        try {
            if (multicastLock?.isHeld == true) {
                multicastLock?.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release multicast lock: ${e.message}")
        }
    }

    fun startHosting(sessionName: String, onCommand: (SyncCommand) -> Unit) {
        stopAll()
        acquireMulticastLock()
        onCommandReceived = onCommand

        _state.value = _state.value.copy(
            role = SharedListeningRole.HOST,
            isAdvertising = true,
            statusMessage = "Hosting as \"$sessionName\" — broadcasting beacon...",
            lastError = null
        )

        // 1. Start TCP Server on port 18457
        tcpServerJob = scope.launch {
            try {
                val serverSocket = ServerSocket(TCP_PORT)
                Log.i(TAG, "TCP Server listening on port $TCP_PORT")

                while (isActive) {
                    val socket = serverSocket.accept()
                    synchronized(connectedSockets) {
                        connectedSockets.add(socket)
                    }
                    val peerId = socket.inetAddress.hostAddress ?: UUID.randomUUID().toString()
                    val peer = SharedListeningPeer(peerId, "Device (${socket.inetAddress.hostAddress})")

                    _state.value = _state.value.copy(
                        connectedPeers = _state.value.connectedPeers + peer,
                        statusMessage = "Connected to ${peer.displayName}"
                    )

                    // Listen for incoming commands from guest
                    launch {
                        handleClientConnection(socket, peer)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.w(TAG, "TCP Server exception: ${e.message}")
                    _state.value = _state.value.copy(lastError = "Server error: ${e.message}")
                }
            }
        }

        // 2. Start UDP Broadcast Beacon on port 18456
        udpBeaconJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                socket.broadcast = true
                val beaconData = "REBIT_BEACON:$sessionName:$TCP_PORT".toByteArray(Charsets.UTF_8)
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                val packet = DatagramPacket(beaconData, beaconData.size, broadcastAddr, UDP_PORT)

                while (isActive) {
                    socket.send(packet)
                    kotlinx.coroutines.delay(1000)
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.w(TAG, "UDP Beacon exception: ${e.message}")
                }
            } finally {
                socket?.close()
            }
        }
    }

    fun startDiscovery(onCommand: (SyncCommand) -> Unit) {
        stopAll()
        acquireMulticastLock()
        onCommandReceived = onCommand

        _state.value = _state.value.copy(
            role = SharedListeningRole.GUEST,
            isDiscovering = true,
            statusMessage = "Searching for nearby Re:Bit sessions...",
            lastError = null
        )

        udpDiscoveryJob = scope.launch {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(UDP_PORT).apply {
                    broadcast = true
                    reuseAddress = true
                }
                val buffer = ByteArray(1024)
                val packet = DatagramPacket(buffer, buffer.size)

                while (isActive) {
                    socket.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (text.startsWith("REBIT_BEACON:")) {
                        val parts = text.split(":")
                        if (parts.size >= 3) {
                            val hostName = parts[1]
                            val hostIp = packet.address.hostAddress ?: ""
                            val peer = SharedListeningPeer(hostIp, "$hostName ($hostIp)")

                            val current = _state.value.discoveredHosts
                            if (current.none { it.endpointId == hostIp }) {
                                _state.value = _state.value.copy(
                                    discoveredHosts = current + peer
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.w(TAG, "UDP Discovery exception: ${e.message}")
                }
            } finally {
                socket?.close()
            }
        }
    }

    fun joinHost(hostIp: String, localDisplayName: String) {
        _state.value = _state.value.copy(
            statusMessage = "Connecting to $hostIp..."
        )

        tcpClientJob = scope.launch {
            try {
                val socket = Socket(hostIp, TCP_PORT)
                clientSocket = socket

                val peer = SharedListeningPeer(hostIp, "Host ($hostIp)")
                _state.value = _state.value.copy(
                    connectedPeers = listOf(peer),
                    isDiscovering = false,
                    statusMessage = "Connected to Host ($hostIp)"
                )

                val dis = DataInputStream(socket.getInputStream())
                while (isActive) {
                    val length = dis.readInt()
                    if (length <= 0 || length > 65536) break
                    val bytes = ByteArray(length)
                    dis.readFully(bytes)

                    val command = SyncCommand.deserialize(bytes)
                    if (command != null) {
                        onCommandReceived?.invoke(command)
                    }
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.w(TAG, "Client socket error: ${e.message}")
                    _state.value = _state.value.copy(
                        connectedPeers = emptyList(),
                        lastError = "Connection disconnected: ${e.message}"
                    )
                }
            }
        }
    }

    fun broadcastCommand(command: SyncCommand) {
        val bytes = command.serialize()
        scope.launch {
            try {
                // If Host, broadcast to all connected guest sockets
                synchronized(connectedSockets) {
                    val iter = connectedSockets.iterator()
                    while (iter.hasNext()) {
                        val s = iter.next()
                        try {
                            val dos = DataOutputStream(s.getOutputStream())
                            dos.writeInt(bytes.size)
                            dos.write(bytes)
                            dos.flush()
                        } catch (e: Exception) {
                            iter.remove()
                            try { s.close() } catch (_: Exception) {}
                        }
                    }
                }

                // If Guest, send to Host socket
                clientSocket?.let { s ->
                    try {
                        val dos = DataOutputStream(s.getOutputStream())
                        dos.writeInt(bytes.size)
                        dos.write(bytes)
                        dos.flush()
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed sending to host: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "broadcastCommand error: ${e.message}")
            }
        }
    }

    private fun handleClientConnection(socket: Socket, peer: SharedListeningPeer) {
        try {
            val dis = DataInputStream(socket.getInputStream())
            while (scope.isActive) {
                val length = dis.readInt()
                if (length <= 0 || length > 65536) break
                val bytes = ByteArray(length)
                dis.readFully(bytes)

                val command = SyncCommand.deserialize(bytes)
                if (command != null) {
                    onCommandReceived?.invoke(command)
                }
            }
        } catch (e: Exception) {
            // disconnected
        } finally {
            synchronized(connectedSockets) {
                connectedSockets.remove(socket)
            }
            try { socket.close() } catch (_: Exception) {}
            _state.value = _state.value.copy(
                connectedPeers = _state.value.connectedPeers.filterNot { it.endpointId == peer.endpointId },
                statusMessage = "Device ${peer.displayName} disconnected"
            )
        }
    }

    fun stopAll() {
        udpBeaconJob?.cancel()
        udpDiscoveryJob?.cancel()
        tcpServerJob?.cancel()
        tcpClientJob?.cancel()

        udpBeaconJob = null
        udpDiscoveryJob = null
        tcpServerJob = null
        tcpClientJob = null

        synchronized(connectedSockets) {
            connectedSockets.forEach { try { it.close() } catch (_: Exception) {} }
            connectedSockets.clear()
        }
        try { clientSocket?.close() } catch (_: Exception) {}
        clientSocket = null

        releaseMulticastLock()
        _state.value = SharedListeningState()
    }

    fun leaveSession() {
        stopAll()
    }

    companion object {
        private const val TAG = "SharedListeningManager"
        private const val UDP_PORT = 18456
        private const val TCP_PORT = 18457

        fun requiredPermissions(): Array<String> = emptyArray()

        fun hasRequiredPermissions(context: Context): Boolean = true
    }
}
