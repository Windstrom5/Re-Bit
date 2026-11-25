package com.windstrom5.rebit.presentation.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.windstrom5.rebit.data.wikipedia.WikipediaClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTP image loader + memory cache (the project has no Coil/Glide dependency). */
object RemoteImageCache {
    private val cache = object : LruCache<String, Bitmap>(((Runtime.getRuntime().maxMemory() / 1024) / 16).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    suspend fun load(url: String): ImageBitmap? {
        cache.get(url)?.let { return it.asImageBitmap() }
        return withContext(Dispatchers.IO) {
            runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", WikipediaClient.USER_AGENT)
                }
                try {
                    if (conn.responseCode !in 200..299) return@runCatching null
                    conn.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally {
                    conn.disconnect()
                }
            }.getOrNull()?.also { cache.put(url, it) }?.asImageBitmap()
        }
    }
}

@Composable
fun rememberRemoteImage(url: String?): ImageBitmap? {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        image = if (url.isNullOrBlank()) null else RemoteImageCache.load(url)
    }
    return image
}
