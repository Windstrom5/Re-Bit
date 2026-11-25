package com.windstrom5.rebit.streaming

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores session tokens and the developer keys the listener typed in, encrypted
 * with an AES-256-GCM key that lives in the Android Keystore (never leaves the
 * secure hardware / TEE on devices that have one).
 *
 * Passwords are never written here — they are used once to obtain a session
 * token and then discarded.
 */
class StreamingCredentialStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("streaming_secure_store", Context.MODE_PRIVATE)

    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val blob = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(name, Base64.encodeToString(blob, Base64.NO_WRAP)).apply()
    }

    fun get(name: String): String? {
        val stored = prefs.getString(name, null) ?: return null
        return try {
            val blob = Base64.decode(stored, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, blob, 0, IV_BYTES))
            String(cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES), Charsets.UTF_8)
        } catch (_: Exception) {
            // Key was invalidated (e.g. lock-screen reset) — treat as signed out.
            prefs.edit().remove(name).apply()
            null
        }
    }

    fun remove(vararg names: String) {
        prefs.edit().apply { names.forEach { remove(it) } }.apply()
    }

    private fun secretKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "bitperfectusb_streaming_key"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

/** Error from a streaming service, with the provider's own message where it gave one. */
class StreamingException(
    message: String,
    val httpCode: Int = -1,
    val errorCode: String? = null
) : Exception(message)

internal object StreamingHttp {
    private const val USER_AGENT = "BitPerfectUSB/1.0 (Android)"

    fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    fun query(vararg params: Pair<String, String>): String =
        params.joinToString("&") { "${enc(it.first)}=${enc(it.second)}" }

    /** One JSON round-trip. Empty bodies (204) come back as an empty object. */
    suspend fun request(
        url: String,
        method: String = "GET",
        headers: Map<String, String> = emptyMap(),
        form: Map<String, String>? = null,
        jsonBody: String? = null
    ): JSONObject = withContext(Dispatchers.IO) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 25_000
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            val body: Pair<String, String>? = when {
                form != null -> query(*form.entries.map { it.key to it.value }.toTypedArray()) to
                    "application/x-www-form-urlencoded"
                jsonBody != null -> jsonBody to "application/json"
                else -> null
            }
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", body.second)
                conn.outputStream.use { it.write(body.first.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val parsed = runCatching { JSONObject(text) }.getOrNull()
                throw StreamingException(
                    message = parsed?.let(::describeError) ?: "HTTP $code",
                    httpCode = code,
                    errorCode = parsed?.optString("error")?.takeIf { it.isNotEmpty() }
                )
            }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    private fun describeError(j: JSONObject): String {
        j.optString("error_description").takeIf { it.isNotEmpty() }?.let { return it }
        j.optString("userMessage").takeIf { it.isNotEmpty() }?.let { return it }
        j.optString("message").takeIf { it.isNotEmpty() }?.let { return it }
        j.optJSONObject("error")?.optString("message")?.takeIf { it.isNotEmpty() }?.let { return it }
        j.optString("error").takeIf { it.isNotEmpty() }?.let { return it }
        return "Request failed"
    }
}

/** `optString` that returns null instead of "" for missing/null keys. */
internal fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
