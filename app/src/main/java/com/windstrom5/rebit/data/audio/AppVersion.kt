package com.windstrom5.rebit.data.audio

import android.content.Context

/** Version shown in the app, read from the installed package so it always matches the build. */
data class AppVersionInfo(val name: String, val code: Long, val appLabel: String) {
    val isBeta: Boolean get() = name.contains("beta", ignoreCase = true)
    val display: String get() = "v$name (build $code)"
}

object AppVersion {
    @Suppress("DEPRECATION")
    fun read(context: Context): AppVersionInfo {
        val info = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val code: Long = if (info == null) {
            0L
        } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            info.versionCode.toLong()
        }
        return AppVersionInfo(
            name = info?.versionName ?: "unknown",
            code = code,
            appLabel = context.applicationInfo.loadLabel(context.packageManager).toString()
        )
    }
}
