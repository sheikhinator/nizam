package com.nizam.app.feature.future

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Secrets sealed with a key that lives in the phone's secure hardware (Android Keystore): 2FA seeds,
 * documents, recall memory. The key can't be copied off the phone, so a stolen backup is unreadable.
 */
object Sealed {
    private const val ALIAS = "atlas_sealed_v1"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    fun seal(bytes: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return c.iv + c.doFinal(bytes)
    }

    fun open(blob: ByteArray): ByteArray {
        require(blob.size >= 28) { "The locally sealed data is incomplete." }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12))) }
        return c.doFinal(blob, 12, blob.size - 12)
    }

    /** Encrypts a large temporary file without holding its plaintext contents in memory. */
    fun sealFile(file: File, write: (OutputStream) -> Unit) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val iv = cipher.iv
        require(iv.size == 12) { "Unsupported local encryption IV." }
        FileOutputStream(file).use { output ->
            output.write(iv)
            CipherOutputStream(output, cipher).use(write)
        }
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    /** Opens a locally sealed file. The caller must read to EOF to validate its GCM tag. */
    fun openFile(file: File): InputStream {
        require(file.isFile && file.length() >= 28L) { "The locally sealed file is incomplete." }
        val input = BufferedInputStream(FileInputStream(file))
        try {
            val iv = ByteArray(12)
            var offset = 0
            while (offset < iv.size) {
                val count = input.read(iv, offset, iv.size - offset)
                if (count < 0) error("The locally sealed file is incomplete.")
                offset += count
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                .apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
            return CipherInputStream(input, cipher)
        } catch (failure: Exception) {
            input.close()
            throw failure
        }
    }

    fun sealText(s: String): String =
        Base64.encodeToString(seal(s.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)

    fun openTextStrict(s: String): String =
        String(open(Base64.decode(s, Base64.NO_WRAP)), StandardCharsets.UTF_8)

    /** Lenient accessor retained for legacy callers; durable stores should use openTextStrict. */
    fun openText(s: String): String = runCatching { openTextStrict(s) }.getOrDefault("")
}

/** A small encrypted list of JSON records, kept in the app's private storage. */
class SealedList(private val c: Context, private val name: String) {
    init {
        require(name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid sealed store name." }
    }

    private val file get() = java.io.File(c.filesDir, "sealed_$name.bin")
    private val lastGoodFile get() = java.io.File(c.filesDir, "sealed_$name.bin.last-good")
    private val atomicFile get() = AtomicFile(file)
    private val lastGoodAtomicFile get() = AtomicFile(lastGoodFile)

    @Volatile
    var lastReadError: String? = null
        private set

    fun all(): List<JSONObject> = synchronized(PROCESS_LOCK) {
        if (!hasPrimaryFile()) {
            lastReadError = null
            return@synchronized emptyList()
        }
        runCatching {
            decode(readPrimaryBytes()).also { lastReadError = null }
        }.getOrElse { failure ->
            lastReadError = failure.message ?: "This encrypted Atlas store could not be read."
            emptyList()
        }
    }

    fun save(list: List<JSONObject>) = synchronized(PROCESS_LOCK) {
        if (!hasPrimaryFile() && hasLastGoodFile()) {
            throw IllegalStateException("The encrypted store is missing; recover its last valid copy before saving.")
        }
        val previousBytes = if (hasPrimaryFile()) {
            try {
                readPrimaryBytes().also(::decode)
            } catch (failure: Exception) {
                lastReadError = failure.message ?: "This encrypted Atlas store could not be read."
                throw IllegalStateException("Recover this encrypted store before saving new data.", failure)
            }
        } else null

        val plaintext = JSONArray().apply { list.forEach { put(it) } }
            .toString()
            .toByteArray(StandardCharsets.UTF_8)
        require(plaintext.size <= MAX_STORE_BYTES) { "This encrypted store exceeds its safe size limit." }
        val encrypted = try {
            Sealed.seal(plaintext)
        } finally {
            plaintext.fill(0)
        }
        try {
            require(encrypted.size <= MAX_STORE_BYTES) { "This encrypted store exceeds its safe size limit." }
            previousBytes?.let { writeAtomically(lastGoodAtomicFile, it) }
            writeAtomically(atomicFile, encrypted)
            lastReadError = null
        } finally {
            encrypted.fill(0)
        }
    }

    fun recoverLastGood(): Boolean = synchronized(PROCESS_LOCK) {
        if (!lastGoodFile.exists() && !File(lastGoodFile.path + ".bak").exists()) return@synchronized false
        val bytes = lastGoodAtomicFile.openRead().use { it.readBytes() }
        decode(bytes)
        writeAtomically(atomicFile, bytes)
        lastReadError = null
        true
    }

    fun add(o: JSONObject) = save(all() + o)
    fun remove(id: String) = save(all().filter { it.optString("id") != id })
    fun update(id: String, change: (JSONObject) -> Unit) = save(all().map { if (it.optString("id") == id) it.also(change) else it })

    private fun hasPrimaryFile(): Boolean =
        file.exists() || File(file.path + ".bak").exists()

    private fun hasLastGoodFile(): Boolean =
        lastGoodFile.exists() || File(lastGoodFile.path + ".bak").exists()

    private fun readPrimaryBytes(): ByteArray =
        atomicFile.openRead().use { input ->
            val bytes = input.readBytes()
            require(bytes.size <= MAX_STORE_BYTES) { "This encrypted store exceeds its safe size limit." }
            bytes
        }

    private fun decode(bytes: ByteArray): List<JSONObject> {
        val plaintext = Sealed.open(bytes)
        return try {
            val array = JSONArray(String(plaintext, StandardCharsets.UTF_8))
            (0 until array.length()).map { array.getJSONObject(it) }
        } finally {
            plaintext.fill(0)
        }
    }

    private fun writeAtomically(target: AtomicFile, bytes: ByteArray) {
        var output: FileOutputStream? = null
        try {
            output = target.startWrite()
            output.write(bytes)
            output.fd.sync()
            target.finishWrite(output)
        } catch (failure: Exception) {
            output?.let(target::failWrite)
            throw IllegalStateException("This encrypted Atlas store could not be saved.", failure)
        }
    }

    companion object {
        private const val MAX_STORE_BYTES = 64 * 1024 * 1024
        private val PROCESS_LOCK = Any()
    }
}
