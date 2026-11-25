package com.windstrom5.rebit.data.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.audiofx.AudioEffect
import android.os.Build
import com.windstrom5.rebit.domain.audiophile.SocNames

/** What this phone's own silicon and audio stack report about themselves. */
data class AndroidChipInfo(
    val socName: String,
    val deviceModel: String,
    val androidVersion: String,
    /** System-wide audio effects registered by the device's audio stack (what Android's mixer path can apply). */
    val systemEffects: List<String>,
    val lowLatency: Boolean,
    val proAudio: Boolean
)

object AndroidChipDetector {

    fun detect(context: Context): AndroidChipInfo {
        val pm = context.packageManager
        val soc = SocNames.friendly(
            socManufacturer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MANUFACTURER else null,
            socModel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
            hardware = Build.HARDWARE,
            board = Build.BOARD
        )
        val effects = runCatching {
            AudioEffect.queryEffects().orEmpty()
                .map { d -> "${d.name} · ${d.implementor}" }
                .distinct()
                .take(8)
        }.getOrDefault(emptyList())

        return AndroidChipInfo(
            socName = soc,
            deviceModel = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            systemEffects = effects,
            lowLatency = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY),
            proAudio = pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)
        )
    }
}
