package com.windstrom5.rebit.data.usb

import android.hardware.usb.UsbDevice
import com.windstrom5.rebit.data.settings.SettingsRepository
import com.windstrom5.rebit.domain.model.EngineType
import com.windstrom5.rebit.domain.repository.DacProfileRepository
import com.windstrom5.rebit.domain.usecase.AnalyzeDacUseCase
import com.windstrom5.rebit.usb.UsbDacManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class UsbSetupPhase { NO_DEVICE, WAITING_PERMISSION, ANALYZING, READY, FAILED }

data class UsbSetupState(
    val phase: UsbSetupPhase = UsbSetupPhase.NO_DEVICE,
    val deviceName: String? = null,
    val message: String? = null
)

/**
 * Makes USB Direct work without the listener having to visit the DAC screen first.
 *
 * USB Direct needs a saved capability profile for the connected DAC (built by reading its
 * USB descriptors) and USB permission. Until both existed, the engine silently stayed on the
 * Android mixer, so tapping "USB Direct" appeared to do nothing. This watches for a USB
 * audio device and, when one appears: asks for permission, analyzes it if it has no profile
 * yet, saves the profile, and switches the engine to USB Direct. Progress and failures are
 * exposed through [state] so the UI can say what is happening.
 */
class UsbAutoSetup(
    private val usb: UsbDacManager,
    private val analyze: AnalyzeDacUseCase,
    private val profiles: DacProfileRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) {
    private val _state = MutableStateFlow(UsbSetupState())
    val state: StateFlow<UsbSetupState> = _state.asStateFlow()

    private var job: Job? = null

    /** Begin watching. Call once at app start; a DAC that is already plugged in is handled too. */
    fun start() {
        scope.launch {
            usb.connectedDevice.collect { device ->
                job?.cancel()
                if (device == null || !usb.looksLikeAudioDevice(device)) {
                    _state.value = UsbSetupState()
                } else {
                    job = scope.launch { setUp(device) }
                }
            }
        }
    }

    /** Re-runs setup for the current device (the "Retry" button, or after the user taps USB Direct). */
    fun retry() {
        usb.rescanDevices()
        val device = usb.currentDevice
        if (device == null || !usb.looksLikeAudioDevice(device)) {
            _state.value = UsbSetupState(
                UsbSetupPhase.FAILED, null,
                "No USB DAC found. Plug it in and tap Retry."
            )
            return
        }
        job?.cancel()
        job = scope.launch { setUp(device) }
    }

    /** Called when the user explicitly picks USB Direct: make sure setup is done or under way. */
    fun ensureReady() {
        when (_state.value.phase) {
            UsbSetupPhase.READY, UsbSetupPhase.WAITING_PERMISSION, UsbSetupPhase.ANALYZING -> Unit
            UsbSetupPhase.NO_DEVICE, UsbSetupPhase.FAILED -> retry()
        }
    }

    private suspend fun setUp(device: UsbDevice) {
        val name = device.productName?.takeIf { it.isNotBlank() } ?: "USB audio device"
        try {
            if (!usb.hasPermission(device)) {
                _state.value = UsbSetupState(
                    UsbSetupPhase.WAITING_PERMISSION, name, "Tap Allow on Android's USB prompt."
                )
                val granted = withTimeoutOrNull(PERMISSION_TIMEOUT_MS) {
                    usb.observePermissionRequest(device).first()
                }
                if (granted != true) {
                    fail(name, if (granted == false) "USB permission was denied." else "USB permission request timed out.")
                    return
                }
            }

            if (profiles.findByUsbIds(device.vendorId, device.productId) == null) {
                _state.value = UsbSetupState(UsbSetupPhase.ANALYZING, name, "Reading the DAC's capabilities…")
                val result = analyze()
                if (result.isFailure) {
                    fail(
                        name,
                        result.exceptionOrNull()?.message
                            ?: "Couldn't read this DAC. It may not be USB Audio Class compliant."
                    )
                    return
                }
            }

            settings.setDefaultEngine(EngineType.CUSTOM_USB_DIRECT)
            _state.value = UsbSetupState(UsbSetupPhase.READY, name)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            fail(name, t.message ?: "USB setup failed.")
        }
    }

    private fun fail(name: String, message: String) {
        _state.value = UsbSetupState(UsbSetupPhase.FAILED, name, message)
    }

    private companion object {
        const val PERMISSION_TIMEOUT_MS = 60_000L
    }
}
