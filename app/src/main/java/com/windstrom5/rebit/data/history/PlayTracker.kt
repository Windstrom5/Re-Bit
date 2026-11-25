package com.windstrom5.rebit.data.history

import com.windstrom5.rebit.domain.model.AudioTrackModel
import com.windstrom5.rebit.domain.model.PlaybackState
import com.windstrom5.rebit.domain.usecase.ObservePlaybackStateUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Turns the live playback state into listening history. App-scoped (started from
 * BitPerfectApp) so plays are recorded whichever screen is open.
 *
 * A play is recorded once the listener has actually heard [StatsMath.countThresholdMs] of the
 * track (30 s), then its listened time is topped up every 10 s and on track change.
 */
class PlayTracker(
    private val repository: ListeningRepository,
    private val observePlayback: ObservePlaybackStateUseCase,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private class Session(val track: AudioTrackModel, val startedAtMs: Long) {
        val timer = ListenTimer()
        var eventId: Long? = null
        var lastWrittenMs = 0L
        var lastPositionMs = 0L
    }

    private var session: Session? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            observePlayback().collect { onState(it) }
        }
    }

    private suspend fun onState(state: PlaybackState) {
        val track = state.currentTrack
        var s = session

        // New track, or the same track restarted (repeat / replay): close out and begin afresh.
        val restarted = s != null && track != null && track.filePath == s.track.filePath &&
            state.positionMs < 3_000L && s.lastPositionMs - state.positionMs > 5_000L
        if (s == null || track == null || track.filePath != s.track.filePath || restarted) {
            finish(s)
            s = if (track != null) Session(track, System.currentTimeMillis()) else null
            session = s
        }
        if (s == null) return

        s.lastPositionMs = state.positionMs
        s.timer.tick(state.isPlaying, System.currentTimeMillis())

        val listened = s.timer.listenedMs
        val id = s.eventId
        if (id == null) {
            if (listened >= StatsMath.countThresholdMs(s.track.durationMs)) {
                s.eventId = repository.recordPlay(
                    PlayEventEntity(
                        filePath = s.track.filePath,
                        title = s.track.title,
                        artist = s.track.artist,
                        format = s.track.format.name,
                        sampleRateHz = s.track.pcm.sampleRateHz,
                        bitDepth = s.track.pcm.bitDepth,
                        trackDurationMs = s.track.durationMs,
                        startedAtMs = s.startedAtMs,
                        listenedMs = listened
                    )
                )
                s.lastWrittenMs = listened
            }
        } else if (listened - s.lastWrittenMs >= 10_000L) {
            repository.updateListened(id, listened)
            s.lastWrittenMs = listened
        }
    }

    private suspend fun finish(s: Session?) {
        val id = s?.eventId ?: return
        if (s.timer.listenedMs > s.lastWrittenMs) repository.updateListened(id, s.timer.listenedMs)
    }
}
