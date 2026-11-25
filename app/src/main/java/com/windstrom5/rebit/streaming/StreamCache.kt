package com.windstrom5.rebit.streaming

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.coroutineContext

/**
 * Holds the few most recent remote tracks in the app's private cache so the
 * decoders (which need a seekable file) can play them, and so Previous/Next,
 * seeking and replays don't re-download.
 *
 * It is a playback buffer, not a download feature: files live in the app's
 * private cache directory (not visible to other apps or the gallery), have no
 * track names, and only the most recent [MAX_FILES] are kept — older ones are
 * deleted automatically and Android may clear the cache at any time.
 */
class StreamCache(context: Context) {

    private val dir = File(context.cacheDir, "stream_cache").apply { mkdirs() }

    fun fileFor(key: String): File = File(dir, "${key.replace(Regex("[^A-Za-z0-9_-]"), "_")}.flac")

    /** Returns the cached file, downloading [url] first if it isn't there yet. */
    suspend fun fetch(
        key: String,
        url: String,
        onProgress: (bytesRead: Long, totalBytes: Long) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val target = fileFor(key)
        if (target.exists() && target.length() > MIN_VALID_BYTES) {
            target.setLastModified(System.currentTimeMillis())
            return@withContext target
        }
        val partial = File(dir, target.name + ".part")
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        try {
            if (conn.responseCode !in 200..299) {
                throw StreamingException("The stream URL was rejected (HTTP ${conn.responseCode}).", conn.responseCode)
            }
            val total = conn.contentLengthLong
            var done = 0L
            conn.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (total > 0 && done != total) throw StreamingException("The download was cut short. Try again.")
            if (!partial.renameTo(target)) throw StreamingException("Could not store the buffered track.")
            target
        } catch (e: Exception) {
            partial.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    /** Deletes everything except the [MAX_FILES] most recently used tracks. */
    fun trim() {
        dir.listFiles { f -> f.name.endsWith(".part") }?.forEach { it.delete() }
        dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(MAX_FILES)?.forEach { it.delete() }
    }

    fun clear() {
        dir.listFiles()?.forEach { it.delete() }
    }

    private companion object {
        const val MAX_FILES = 4
        const val MIN_VALID_BYTES = 1024L
    }
}
