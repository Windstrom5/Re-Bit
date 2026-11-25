package com.windstrom5.rebit.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import com.windstrom5.rebit.domain.model.DacProfile
import com.windstrom5.rebit.domain.model.PcmFormat
import com.windstrom5.rebit.domain.model.UsbStreamingOption
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow

/** Everything needed to stream to one activated (interface, altsetting) pair. */
data class ActivatedStreamingEndpoint(
    val usbInterface: UsbInterface,
    val dataEndpoint: UsbEndpoint,
    val feedbackEndpoint: UsbEndpoint?,
    val option: UsbStreamingOption
)

/**
 * Registers for ACTION_USB_DEVICE_ATTACHED / DETACHED, requests runtime permission
 * via UsbManager.requestPermission(), and hands out UsbDeviceConnections for
 * capability analysis and playback (Section 3.3, UsbDacManager).
 *
 * Detection is intentionally two-tiered rather than a single hard filter:
 * [allDevices] lists *everything* Android's USB host stack currently sees,
 * completely unfiltered, and [connectedDevice] is a best-guess auto-selection
 * from that list (preferring anything that declares a USB Audio Class
 * interface). A heuristic that silently hides devices it doesn't recognize is
 * exactly how one specific dongle ends up looking "not detected" when it's
 * really just an auto-selection miss — the fix is to always let the real,
 * unfiltered list be visible and manually selectable, never gated behind the
 * heuristic being right.
 */
class UsbDacManager(private val context: Context) {

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _allDevices = MutableStateFlow<List<UsbDevice>>(emptyList())
    /** Every USB device Android's host stack currently reports — unfiltered. */
    val allDevices: StateFlow<List<UsbDevice>> = _allDevices

    private val _connectedDevice = MutableStateFlow<UsbDevice?>(null)
    /** The device currently selected for analysis/playback — auto-picked, but overridable. */
    val connectedDevice: StateFlow<UsbDevice?> = _connectedDevice

    val currentDevice: UsbDevice? get() = _connectedDevice.value

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    refreshDeviceList()
                    val device = intent.getUsbDeviceExtra()
                    // Auto-select on attach only if nothing is currently selected, so plugging
                    // in a second device doesn't yank the user away from one they picked.
                    if (device != null && _connectedDevice.value == null) {
                        _connectedDevice.value = device
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    refreshDeviceList()
                    val device = intent.getUsbDeviceExtra()
                    if (device != null && device.deviceId == _connectedDevice.value?.deviceId) {
                        _connectedDevice.value = _allDevices.value.firstOrNull()
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = intent.getUsbDeviceExtra()
                    _permissionResult.tryEmitEvent(device to granted)
                }
            }
        }
    }

    private val _permissionResult = SinglePermissionEventBus()

    fun start() {
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        rescanDevices()
    }

    fun stop() {
        try { context.unregisterReceiver(receiver) } catch (_: IllegalArgumentException) { /* not registered */ }
    }

    /**
     * Re-queries the USB host stack from scratch and refreshes both [allDevices] and,
     * if nothing is currently selected, [connectedDevice]. Exposed for a manual
     * "Rescan" action — attach broadcasts can occasionally be missed (timing races
     * around process start, some OEM skins), so a user-triggerable re-query is a
     * cheap, reliable fallback rather than only ever trusting the broadcast stream.
     */
    fun rescanDevices() {
        refreshDeviceList()
        if (_connectedDevice.value == null) {
            _connectedDevice.value = pickBestGuess(_allDevices.value)
        }
    }

    /** Manually selects which attached device to treat as the active DAC. */
    fun selectDevice(device: UsbDevice) {
        _connectedDevice.value = device
    }

    private fun refreshDeviceList() {
        _allDevices.value = usbManager.deviceList.values.toList()
    }

    private fun pickBestGuess(devices: List<UsbDevice>): UsbDevice? =
        devices.firstOrNull { looksLikeAudioDevice(it) } ?: devices.firstOrNull()

    /**
     * Best-effort hint only — used to sort/highlight likely candidates in the UI,
     * never to hide a device from [allDevices]. Checks every interface's declared
     * class rather than the device-level class, since composite USB Audio devices
     * commonly declare bDeviceClass=0 ("defined at interface level") with the
     * actual Audio class living on the interface descriptors instead.
     */
    fun looksLikeAudioDevice(device: UsbDevice): Boolean =
        (0 until device.interfaceCount).any { device.getInterface(it).interfaceClass == UsbConstants.USB_CLASS_AUDIO }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /** Requests permission and suspends until the user responds (or times out via cancellation). */
    fun observePermissionRequest(device: UsbDevice): Flow<Boolean> = callbackFlow {
        if (usbManager.hasPermission(device)) {
            trySend(true)
            close()
            return@callbackFlow
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(ACTION_USB_PERMISSION).setPackage(context.packageName), flags
        )
        val job = _permissionResult.subscribe { (respondedDevice, granted) ->
            if (respondedDevice?.deviceId == device.deviceId) {
                trySend(granted)
                close()
            }
        }
        usbManager.requestPermission(device, pendingIntent)
        awaitClose { job() }
    }

    fun openConnection(device: UsbDevice): UsbDeviceConnection? =
        if (usbManager.hasPermission(device)) usbManager.openDevice(device) else null

    /**
     * Activates the exact alternate setting matching [format] and returns everything
     * needed to stream to it. This is the piece a "just find an isochronous endpoint"
     * implementation skips: audio streaming interfaces sit at alternate-setting 0
     * (zero bandwidth, no endpoints) until explicitly switched via SET_INTERFACE to
     * the altsetting that actually carries the desired format (Section 3.4).
     *
     * Requires [profile] to already have been built by DacCapabilityAnalyzer — you
     * cannot know which altsetting to activate without having parsed them first.
     */
    fun activateStreamingOption(
        device: UsbDevice,
        connection: UsbDeviceConnection,
        profile: DacProfile,
        format: PcmFormat,
        protocolPreference: com.windstrom5.rebit.domain.model.UsbProtocolPreference =
            com.windstrom5.rebit.domain.model.UsbProtocolPreference.AUTO
    ): ActivatedStreamingEndpoint? {
        val option = profile.findExactStreamingOption(format, protocolPreference) ?: return null

        val targetInterface = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == option.interfaceNumber && it.alternateSetting == option.alternateSetting }
            ?: return null

        if (!connection.claimInterface(targetInterface, true)) return null
        if (!connection.setInterface(targetInterface)) return null

        var dataEndpoint: UsbEndpoint? = null
        var feedbackEndpoint: UsbEndpoint? = null
        for (e in 0 until targetInterface.endpointCount) {
            val ep = targetInterface.getEndpoint(e)
            if (ep.type != UsbConstants.USB_ENDPOINT_XFER_ISOC) continue
            if (ep.direction == UsbConstants.USB_DIR_OUT) dataEndpoint = ep
            else if (ep.direction == UsbConstants.USB_DIR_IN) feedbackEndpoint = ep
        }
        val data = dataEndpoint ?: return null

        return ActivatedStreamingEndpoint(targetInterface, data, feedbackEndpoint, option)
    }

    /**
     * Switches back to alternate-setting 0 (idle/zero-bandwidth) before releasing the
     * interface, so the DAC drops out of streaming mode cleanly and frees USB bandwidth
     * for other devices — the same pattern real UAC drivers follow on stop.
     */
    fun deactivateStreamingOption(device: UsbDevice, connection: UsbDeviceConnection, activated: ActivatedStreamingEndpoint) {
        val idleAltSetting = (0 until device.interfaceCount)
            .map { device.getInterface(it) }
            .firstOrNull { it.id == activated.usbInterface.id && it.alternateSetting == 0 }
        if (idleAltSetting != null) {
            try { connection.setInterface(idleAltSetting) } catch (_: Throwable) { /* best-effort cleanup */ }
        }
        connection.releaseInterface(activated.usbInterface)
    }

    private fun Intent.getUsbDeviceExtra(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    /**
     * Sets the DAC's own hardware volume via a USB Audio Class Feature Unit
     * SET_CUR request (Control Selector = VOLUME_CONTROL, 0x02).
     * [volumePercent] is 0.0f..1.0f.
     *
     * This is the *hardware* volume path: it tells the DAC's own chip to
     * attenuate, rather than scaling the PCM samples in software. That
     * distinction is the whole point for bit-perfect playback — software
     * attenuation would alter the sample values this app promises to deliver
     * untouched, while a Feature Unit request leaves the bitstream intact.
     *
     * Addressing comes from [profile]'s parsed descriptors rather than
     * guesswork. The previous version hardcoded interface 0 and tried a
     * fixed list of candidate wIndex values (0x0200, 0x0500, 0x0100,
     * 0x0000), hoping one matched — that fails on any DAC whose
     * AudioControl interface isn't 0 or whose Feature Unit ID isn't 1, 2 or
     * 5, which is a large share of real devices. It also had the data
     * format wrong for UAC1 (see below), so even a lucky wIndex hit would
     * have set a wrong volume.
     *
     * Volume data format differs between the two protocol versions:
     *  - UAC1: 1 byte, a signed value in 1/256 dB units, but the request
     *    carries only the high byte — effectively whole dB, range -127..0.
     *  - UAC2: 2 bytes little-endian, signed 16-bit in 1/256 dB (Q7.8),
     *    so -60dB is -60*256 = -15360.
     * The old code sent 2 bytes for everything with a value that was
     * already shifted left by 8 *and* masked to 16 bits, which only
     * coincidentally resembled the UAC2 layout.
     *
     * Returns false (without sending anything) when the DAC exposes no
     * volume Feature Unit at all — see [DacProfile.supportsHardwareVolume];
     * that's a legitimate, common hardware configuration, not an error.
     */
    fun setHardwareVolume(
        connection: UsbDeviceConnection,
        profile: DacProfile,
        volumePercent: Float
    ): Boolean {
        val featureUnitId = profile.volumeFeatureUnitId
        val acInterface = profile.audioControlInterfaceNumber
        if (featureUnitId == null || acInterface < 0) return false

        val clamped = volumePercent.coerceIn(0.0f, 1.0f)
        // Perceptual mapping: volume sliders are linear, hearing is
        // logarithmic, so map the slider position to a dB attenuation
        // curve. Floor at -60dB rather than the format's -127dB minimum —
        // below roughly -60dB is inaudible anyway, and spending most of the
        // slider's travel on inaudible values makes it feel broken.
        val volDb = if (clamped <= 0.001f) MIN_VOLUME_DB else (MIN_VOLUME_DB * (1.0 - clamped)).toInt().coerceIn(MIN_VOLUME_DB, 0)

        val data: ByteArray
        val requestType: Int
        if (profile.isUac2) {
            val q78 = (volDb * 256).coerceIn(-32768, 32767)
            data = byteArrayOf((q78 and 0xFF).toByte(), ((q78 shr 8) and 0xFF).toByte())
            requestType = UsbAudioConstants.REQTYPE_CLASS_INTERFACE_OUT
        } else {
            data = byteArrayOf((volDb and 0xFF).toByte())
            requestType = UsbAudioConstants.REQTYPE_CLASS_INTERFACE_OUT
        }

        // wValue = (Control Selector << 8) | Channel Number. Channel 0 is the
        // master channel — the one a single "device volume" slider should
        // move. wIndex = (Entity ID << 8) | Interface, the same convention
        // already used successfully for clock control elsewhere in this module.
        val wValue = (VOLUME_CONTROL_SELECTOR shl 8) or MASTER_CHANNEL
        val wIndex = (featureUnitId shl 8) or acInterface

        return try {
            val res = connection.controlTransfer(
                requestType, UsbAudioConstants.REQUEST_CUR, wValue, wIndex, data, data.size, 500
            )
            if (res < 0) {
                Log.w(TAG, "Hardware volume SET_CUR rejected by DAC (featureUnit=$featureUnitId, acInterface=$acInterface, ${volDb}dB, result=$res)")
                false
            } else {
                true
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Hardware volume SET_CUR failed: ${t.message}", t)
            false
        }
    }

    companion object {
        const val ACTION_USB_PERMISSION = "com.windstrom5.rebit.USB_PERMISSION"
        private const val TAG = "UsbDacManager"
        private const val VOLUME_CONTROL_SELECTOR = 0x02
        private const val MASTER_CHANNEL = 0x00
        /** Practical floor for the volume curve — see setHardwareVolume. */
        private const val MIN_VOLUME_DB = -60
    }
}


/**
 * Minimal single-event pub/sub used to bridge the permission-result broadcast
 * back into the suspend/Flow world without pulling in a full EventBus dependency.
 */
private class SinglePermissionEventBus {
    private val listeners = mutableListOf<(Pair<UsbDevice?, Boolean>) -> Unit>()

    fun subscribe(listener: (Pair<UsbDevice?, Boolean>) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun tryEmitEvent(event: Pair<UsbDevice?, Boolean>) {
        listeners.toList().forEach { it(event) }
    }
}
