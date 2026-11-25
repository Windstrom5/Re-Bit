package com.windstrom5.rebit.sync

import java.nio.charset.StandardCharsets

/**
 * Messages a Shared Listening host sends to connected guests over Nearby
 * Connections — see [SharedListeningManager]'s class doc comment for the
 * whole feature's scope and honest verification status.
 *
 * Deliberately NOT audio streaming: guests need their own local copy of
 * whatever's playing (matched via [TrackChange]'s fingerprint fields, not a
 * full file hash — see that class's doc comment for why). What's actually
 * transmitted is just these small control messages, which is what keeps
 * this free, backend-less, and low-bandwidth over Bluetooth/Wi-Fi Direct.
 *
 * [hostTimestampMs] on the time-sensitive commands is the host's
 * System.currentTimeMillis() at the moment it sent the message — a guest
 * receiving it can add (now - hostTimestampMs) to the given position to
 * compensate for however long the message took in transit, rather than
 * trusting the raw position as if it arrived instantly.
 */
sealed class SyncCommand {
    data class Play(val positionMs: Long, val hostTimestampMs: Long) : SyncCommand()
    data class Pause(val positionMs: Long) : SyncCommand()
    data class Seek(val positionMs: Long, val hostTimestampMs: Long) : SyncCommand()

    /**
     * Announces a track change. [title]/[artist]/[durationMs] are a "good
     * enough" fingerprint for the guest to find its own local copy via
     * AudioLibraryRepository — not a cryptographic file hash. A real file
     * hash would mean reading the whole (often large, lossless) file just
     * to identify it, which is real I/O cost for something (title, artist,
     * duration match) that's already a reliable match in the vast majority
     * of real libraries. [artist] is nullable because AudioTrackModel's is.
     */
    data class TrackChange(val title: String, val artist: String?, val durationMs: Long) : SyncCommand()

    /** Periodic resync while playing, independent of Play/Pause/Seek, so
     *  clock drift between host and guest doesn't silently accumulate over
     *  a long track — see SharedListeningManager's heartbeat interval. */
    data class Heartbeat(val positionMs: Long, val isPlaying: Boolean, val hostTimestampMs: Long) : SyncCommand()

    fun serialize(): ByteArray {
        val parts = when (this) {
            is Play -> listOf("PLAY", positionMs.toString(), hostTimestampMs.toString())
            is Pause -> listOf("PAUSE", positionMs.toString())
            is Seek -> listOf("SEEK", positionMs.toString(), hostTimestampMs.toString())
            is TrackChange -> listOf("TRACK", title, artist ?: NULL_MARKER, durationMs.toString())
            is Heartbeat -> listOf("BEAT", positionMs.toString(), isPlaying.toString(), hostTimestampMs.toString())
        }
        return parts.joinToString(FIELD_DELIMITER).toByteArray(StandardCharsets.UTF_8)
    }

    companion object {
        private const val FIELD_DELIMITER = "\u0001"
        private const val NULL_MARKER = "\u0002NULL\u0002"

        /** Returns null on any malformed payload rather than throwing —
         *  a corrupt/unexpected message from a peer should be dropped, not
         *  crash the receiving session. */
        fun deserialize(bytes: ByteArray): SyncCommand? {
            return try {
                val parts = String(bytes, StandardCharsets.UTF_8).split(FIELD_DELIMITER)
                when (parts.getOrNull(0)) {
                    "PLAY" -> Play(parts[1].toLong(), parts[2].toLong())
                    "PAUSE" -> Pause(parts[1].toLong())
                    "SEEK" -> Seek(parts[1].toLong(), parts[2].toLong())
                    "TRACK" -> TrackChange(parts[1], parts[2].takeIf { it != NULL_MARKER }, parts[3].toLong())
                    "BEAT" -> Heartbeat(parts[1].toLong(), parts[2].toBoolean(), parts[3].toLong())
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }
    }
}
