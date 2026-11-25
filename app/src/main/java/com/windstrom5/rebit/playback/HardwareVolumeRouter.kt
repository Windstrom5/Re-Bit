package com.windstrom5.rebit.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.os.Handler
import android.os.Looper
import com.windstrom5.rebit.data.settings.SettingsRepository
import com.windstrom5.rebit.domain.model.DacProfile
import com.windstrom5.rebit.domain.model.EngineType
import com.windstrom5.rebit.domain.usecase.ObserveDacProfilesUseCase
import com.windstrom5.rebit.usb.UsbDacManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Lets the phone's volume buttons control the DAC's own hardware volume.
 *
 * In USB Direct mode the audio bypasses Android's mixer, so the normal volume keys change nothing. While
 * USB Direct is active and the DAC exposes a volume control, this:
 *  - tells Android the media session has a REMOTE volume (the same mechanism cast devices use). The volume
 *    keys then reach this app even with the screen off, and the system volume panel shows the DAC volume;
 *  - lets the foreground screen handle the keys directly via [adjust].
 * Otherwise the session goes back to normal local volume and the keys behave as usual.
 */
class HardwareVolumeRouter(
    private val usb: UsbDacManager,
    private val settings: SettingsRepository,
    observeDacProfiles: ObserveDacProfilesUseCase,
    @Suppress("UNUSED_PARAMETER") context: Context,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) {
    private val main = Handler(Looper.getMainLooper())
    private val applyMutex = Mutex()

    private val _active = MutableStateFlow(false)
    /** True while the volume keys should drive the DAC's hardware volume. */
    val active: StateFlow<Boolean> = _active.asStateFlow()

    @Volatile private var profile: DacProfile? = null
    @Volatile private var step = -1
    private var appliedStep = -1

    private var session: MediaSession? = null
    private var provider: VolumeProvider? = null
    private var remote = false

    init {
        scope.launch {
            combine(usb.connectedDevice, observeDacProfiles(), settings.settings) { device, profiles, s ->
                val match = device?.let { d -> profiles.firstOrNull { it.vendorId == d.vendorId && it.productId == d.productId } }
                val usable = s.hardwareVolumeEnabled &&
                    s.defaultEngine == EngineType.CUSTOM_USB_DIRECT &&
                    match != null && match.supportsHardwareVolume
                Triple(usable, match, s.hardwareVolumeStepIndex)
            }.collect { (usable, match, storedStep) ->
                profile = match
                // The slider (or anything else) may have changed the level: follow it.
                if (settings.hasStoredHardwareVolume() && storedStep != step) {
                    step = storedStep
                    provider?.currentVolume = storedStep
                }
                _active.value = usable
                main.post { applyRouting() }
            }
        }
    }

    /** Called by the playback service once its media session exists. */
    fun attachSession(mediaSession: MediaSession) {
        session = mediaSession
        main.post { applyRouting() }
    }

    fun detachSession() {
        session = null
        provider = null
        remote = false
    }

    /**
     * One volume-key press. Returns the new level in 0..steps, or null if the keys aren't being routed to
     * the DAC (so the caller should leave them to Android).
     */
    fun adjust(direction: Int): Int? {
        if (!_active.value || direction == 0) return null
        val steps = steps()
        val unit = (steps / KEY_PRESSES_FULL_RANGE).coerceAtLeast(1)
        ensureStep(steps)
        return commit((step + direction * unit).coerceIn(0, steps))
    }

    fun percent(level: Int): Int = level * 100 / steps()

    private fun setStep(level: Int) {
        if (!_active.value) return
        ensureStep(steps())
        commit(level.coerceIn(0, steps()))
    }

    private fun steps() = settings.settings.value.hardwareVolumeSteps.coerceAtLeast(2)

    /**
     * The DAC's real level can't be read back over USB, so only trust a level the app itself set. If it
     * never has, the first key press starts from a modest 30% rather than jumping to a loud default.
     */
    private fun ensureStep(steps: Int) {
        if (step >= 0) return
        step = if (settings.hasStoredHardwareVolume()) {
            settings.settings.value.hardwareVolumeStepIndex.coerceIn(0, steps)
        } else {
            (steps * SAFE_START_FRACTION).toInt()
        }
    }

    private fun commit(next: Int): Int {
        step = next
        settings.setHardwareVolumeStepIndex(next) // also moves the on-screen slider
        provider?.currentVolume = next
        applyToDac()
        return next
    }

    /** Sends the latest level to the DAC. Quick presses coalesce: each run applies the newest level. */
    private fun applyToDac() {
        scope.launch {
            applyMutex.withLock {
                val target = step
                if (target == appliedStep) return@withLock
                val prof = profile ?: return@withLock
                val device = usb.currentDevice ?: return@withLock
                val conn = usb.openConnection(device) ?: return@withLock
                val ok = try {
                    usb.setHardwareVolume(conn, prof, target.toFloat() / steps())
                } finally {
                    try { conn.close() } catch (_: Exception) {}
                }
                if (ok) appliedStep = target
            }
        }
    }

    /** Switches the media session between remote (DAC) and local (Android) volume. Main thread. */
    private fun applyRouting() {
        val s = session ?: return
        if (_active.value) {
            val steps = steps()
            val current = step.takeIf { it >= 0 } ?: settings.settings.value.hardwareVolumeStepIndex.coerceIn(0, steps)
            val existing = provider
            if (!remote || existing == null || existing.maxVolume != steps) {
                val p = object : VolumeProvider(VOLUME_CONTROL_ABSOLUTE, steps, current) {
                    override fun onSetVolumeTo(volume: Int) = setStep(volume)
                    override fun onAdjustVolume(direction: Int) {
                        adjust(direction)
                    }
                }
                provider = p
                s.setPlaybackToRemote(p)
                remote = true
            } else {
                existing.currentVolume = current
            }
        } else if (remote) {
            s.setPlaybackToLocal(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            provider = null
            remote = false
        }
    }

    private companion object {
        /** Key presses to go from silent to full: 20 presses, 5% each, like Android's own volume steps. */
        const val KEY_PRESSES_FULL_RANGE = 20
        const val SAFE_START_FRACTION = 0.30f
    }
}
