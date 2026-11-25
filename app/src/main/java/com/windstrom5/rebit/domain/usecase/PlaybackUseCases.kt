package com.windstrom5.rebit.domain.usecase

import com.windstrom5.rebit.domain.engine.PlaybackIntegrityEngine
import com.windstrom5.rebit.domain.model.AudioTrackModel
import com.windstrom5.rebit.domain.model.DacProfile
import com.windstrom5.rebit.domain.model.EngineType
import com.windstrom5.rebit.domain.model.IntegrityResult
import com.windstrom5.rebit.domain.model.PlaybackQueue
import com.windstrom5.rebit.domain.model.PlaybackState
import com.windstrom5.rebit.playback.PlaybackController
import kotlinx.coroutines.flow.StateFlow

class StartPlaybackUseCase(
    private val controller: PlaybackController
) {
    suspend operator fun invoke(track: AudioTrackModel, engineType: EngineType, dac: DacProfile?) =
        controller.start(track, engineType, dac)
}

class StopPlaybackUseCase(private val controller: PlaybackController) {
    suspend operator fun invoke() = controller.stop()
}

class SeekPlaybackUseCase(private val controller: PlaybackController) {
    suspend operator fun invoke(positionMs: Long) = controller.seekTo(positionMs)
}

class ObservePlaybackStateUseCase(private val controller: PlaybackController) {
    operator fun invoke(): StateFlow<PlaybackState> = controller.state

    /** Latest output level (peak/RMS per channel) for the live meter; null when nothing plays. */
    fun outputLevels(): com.windstrom5.rebit.playback.dsp.LevelMeter.Levels? = controller.levelSnapshot()

    fun recentSamples(outL: FloatArray, outR: FloatArray): Int = controller.recentSamples(outL, outR)

    fun outputSampleRateHz(): Int = controller.outputSampleRateHz()
}

/** RO2 support: on-demand integrity check, independent of whether playback is active. */
class VerifyIntegrityUseCase(private val engine: PlaybackIntegrityEngine) {
    operator fun invoke(track: AudioTrackModel, dac: DacProfile?, engineType: EngineType): IntegrityResult =
        engine.evaluate(track.pcm, dac, engineType)
}

class PausePlaybackUseCase(private val controller: PlaybackController) {
    suspend operator fun invoke() = controller.pause()
}

class ResumePlaybackUseCase(private val controller: PlaybackController) {
    suspend operator fun invoke() = controller.resume()
}

class NextTrackUseCase(private val controller: PlaybackController) {
    suspend operator fun invoke() = controller.nextTrack()
}

class PreviousTrackUseCase(private val controller: PlaybackController) {
    suspend operator fun invoke() = controller.previousTrack()
}

class ToggleShuffleUseCase(private val controller: PlaybackController) {
    operator fun invoke() = controller.toggleShuffle()
}

/** Sets the Next/Previous queue — see PlaybackController.setQueue. Doesn't itself
 *  start playback; pair with [StartPlaybackUseCase]. */
class SetQueueUseCase(private val controller: PlaybackController) {
    operator fun invoke(tracks: List<AudioTrackModel>, startAt: AudioTrackModel) = controller.setQueue(tracks, startAt)
}

class ObserveQueueUseCase(private val controller: PlaybackController) {
    operator fun invoke(): StateFlow<PlaybackQueue> = controller.queue
}
