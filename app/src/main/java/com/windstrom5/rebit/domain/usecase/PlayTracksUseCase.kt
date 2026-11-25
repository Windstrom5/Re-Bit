package com.windstrom5.rebit.domain.usecase

import com.windstrom5.rebit.data.settings.SettingsRepository
import com.windstrom5.rebit.domain.model.AudioTrackModel
import com.windstrom5.rebit.domain.model.EngineType
import com.windstrom5.rebit.usb.UsbDacManager
import kotlinx.coroutines.flow.first

/**
 * Starts playback of [start] with [queue] as the play queue, choosing the engine exactly like
 * LibraryViewModel.playTrack: the configured default engine when a DAC is attached, otherwise
 * the Android AudioTrack path. Used by screens that play from somewhere other than the library
 * list (Home rankings, recently played, favorites).
 */
class PlayTracksUseCase(
    private val startPlayback: StartPlaybackUseCase,
    private val setQueue: SetQueueUseCase,
    private val observeDacProfiles: ObserveDacProfilesUseCase,
    private val settings: SettingsRepository,
    private val usbDacManager: UsbDacManager
) {
    suspend operator fun invoke(queue: List<AudioTrackModel>, start: AudioTrackModel) {
        val connected = usbDacManager.currentDevice
        val dac = if (connected != null) observeDacProfiles().first().firstOrNull() else null
        val engine = if (dac == null || connected == null) EngineType.ANDROID_AUDIOTRACK else settings.current.defaultEngine
        setQueue(queue, start)
        startPlayback(start, engine, dac)
    }
}
