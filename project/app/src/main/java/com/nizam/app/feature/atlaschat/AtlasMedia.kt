package com.nizam.app.feature.atlaschat

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Media for Atlas Messenger. Blobs (video, files, big photos) are encrypted on the phone with a
 * one-time AES-256-GCM key, the ciphertext is stored on a public Blossom host, and only the key —
 * carried inside the already end-to-end-encrypted message — lets the recipient decrypt it. The host
 * only ever sees random bytes.
 */
object AtlasMedia {
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()

    // Public Blossom servers; tried in order until one accepts the blob.
    private val SERVERS = listOf("https://blossom.band", "https://blossom.primal.net", "https://cdn.satellite.earth", "https://nostr.download")

    /** AES-256-GCM encrypt. Returns the ciphertext and the key/iv as hex. */
    fun encrypt(data: ByteArray): Triple<ByteArray, String, String> {
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return Triple(c.doFinal(data), key.toHex(), iv.toHex())
    }

    fun decrypt(cipher: ByteArray, keyHex: String, ivHex: String): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyHex.fromHex(), "AES"), GCMParameterSpec(128, ivHex.fromHex()))
        return c.doFinal(cipher)
    }

    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    /** Uploads the (already encrypted) bytes to a Blossom host and returns the blob URL, or null. */
    suspend fun upload(cipher: ByteArray, contentType: String = "application/octet-stream"): String? = withContext(Dispatchers.IO) {
        val hashHex = sha256(cipher).toHex()
        val expiration = (System.currentTimeMillis() / 1000 + 3600).toString()
        val auth = NostrWire.build(
            Bip340.newSecretKey(), 24242,
            listOf(listOf("t", "upload"), listOf("x", hashHex), listOf("expiration", expiration)), "Upload"
        )
        val authHeader = "Nostr " + Base64.getEncoder().encodeToString(auth.toString().toByteArray(Charsets.UTF_8))
        for (s in SERVERS) {
            val url = runCatching {
                val req = Request.Builder().url("$s/upload")
                    .put(cipher.toRequestBody(contentType.toMediaTypeOrNull()))
                    .header("Authorization", authHeader).build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    val body = r.body?.string().orEmpty()
                    runCatching { JSONObject(body).optString("url") }.getOrNull()?.ifEmpty { null } ?: "$s/$hashHex"
                }
            }.getOrNull()
            if (url != null) return@withContext url
        }
        null
    }

    suspend fun download(url: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching { http.newCall(Request.Builder().url(url).build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null } }.getOrNull()
    }
}
