package com.windstrom5.rebit.playback.autoeq

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads and caches AutoEq's real measurements-based presets (github.com/jaakkopasanen/AutoEq,
 * MIT licence). The catalogue index is refreshed weekly; each preset is cached on first use, so
 * anything you've applied keeps working offline.
 */
class AutoEqCatalog(context: Context) {
    private val dir = File(context.applicationContext.filesDir, "autoeq").apply { mkdirs() }
    private val indexFile = File(dir, "index.md")
    private val presetDir = File(dir, "presets").apply { mkdirs() }
    @Volatile private var cached: List<IemEntry>? = null

    companion object {
        private const val INDEX_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000
        private const val USER_AGENT = "BitPerfectUSB/1.0 (Android audiophile player; AutoEq presets)"
    }

    /** Whole catalogue (in-ear and over-ear). Uses the cached index when fresh or when offline. */
    suspend fun entries(forceRefresh: Boolean = false): List<IemEntry> = withContext(Dispatchers.IO) {
        cached?.takeIf { !forceRefresh }?.let { return@withContext it }
        val stale = !indexFile.exists() || System.currentTimeMillis() - indexFile.lastModified() > INDEX_MAX_AGE_MS
        if (forceRefresh || stale) {
            try {
                indexFile.writeText(http(AutoEqIndex.INDEX_URL))
            } catch (e: IOException) {
                if (!indexFile.exists()) throw IOException("Couldn't download the IEM catalogue. Check your connection.", e)
            }
        }
        AutoEqIndex.parse(indexFile.readText()).also { cached = it }
    }

    /** The parametric correction for [entry]; falls back to AutoEq's fixed-band file if needed. */
    suspend fun preset(entry: IemEntry): ParsedParametricEq = withContext(Dispatchers.IO) {
        val file = File(presetDir, sha1(entry.path) + ".txt")
        val text = if (file.exists()) {
            file.readText()
        } else {
            val fetched = try {
                http(AutoEqIndex.presetUrl(entry, "ParametricEQ.txt"))
            } catch (e: IOException) {
                try {
                    http(AutoEqIndex.presetUrl(entry, "FixedBandEQ.txt"))
                } catch (e2: IOException) {
                    throw IOException("No preset available for this measurement (offline, or the file doesn't exist).", e2)
                }
            }
            file.writeText(fetched)
            fetched
        }
        val parsed = ParametricEqText.parse(text)
        if (parsed.filters.isEmpty()) {
            file.delete()
            throw IOException("This preset contains no usable filters.")
        }
        parsed
    }

    private fun http(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
