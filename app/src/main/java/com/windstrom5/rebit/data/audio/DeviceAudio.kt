package com.windstrom5.rebit.data.audio

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.windstrom5.rebit.domain.audiophile.RouteKind

/** What the phone's audio system is actually doing (read from Android, not assumed). */
data class OutputRoute(
    val label: String,
    val deviceName: String?,
    /** Sample rates the device advertises; empty means "not reported / any". */
    val ratesHz: List<Int>,
    val bitDepths: List<String>,
    val maxChannels: Int,
    /** Bluetooth links are lossy; everything else here is a wired / built-in path. */
    val lossy: Boolean,
    val kind: RouteKind = RouteKind.OTHER
)

object DeviceAudio {

    /** The rate Android's mixer runs at for music. Anything else is resampled on the way in. */
    fun mixerRateHz(): Int = AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC)

    private fun priority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> 0
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG -> 1
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 2
        AudioDeviceInfo.TYPE_HDMI -> 3
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 5
        else -> 4
    }

    private fun kindOf(type: Int): RouteKind = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> RouteKind.USB
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_LINE_ANALOG -> RouteKind.ANALOG
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> RouteKind.BLUETOOTH
        AudioDeviceInfo.TYPE_HDMI -> RouteKind.HDMI
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> RouteKind.SPEAKER
        else -> RouteKind.OTHER
    }

    private fun typeLabel(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB audio device"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "Analog line out"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth (A2DP)"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth (call audio)"
        AudioDeviceInfo.TYPE_HDMI -> "HDMI"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Built-in speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        else -> "Audio output"
    }

    private fun encodingLabel(e: Int): String? = when (e) {
        AudioFormat.ENCODING_PCM_16BIT -> "16-bit"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit"
        AudioFormat.ENCODING_PCM_32BIT -> "32-bit"
        AudioFormat.ENCODING_PCM_FLOAT -> "float"
        else -> null
    }

    /** The output Android will use for music: wired/USB beat Bluetooth, which beats the speaker. */
    fun currentRoute(context: Context): OutputRoute {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val device = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .filter { it.isSink }
            .minByOrNull { priority(it.type) }
            ?: return OutputRoute("Unknown output", null, emptyList(), emptyList(), 2, false)
        return OutputRoute(
            label = typeLabel(device.type),
            deviceName = device.productName?.toString()?.takeIf { it.isNotBlank() && !it.equals("null", true) },
            ratesHz = device.sampleRates.toList().sorted(),
            bitDepths = device.encodings.toList().mapNotNull(::encodingLabel).distinct(),
            maxChannels = device.channelCounts.maxOrNull() ?: 2,
            lossy = device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            kind = kindOf(device.type)
        )
    }
}
