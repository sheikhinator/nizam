package com.nizam.app.data.db

import android.content.Context
import android.util.AtomicFile
import com.nizam.app.feature.future.Sealed
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom

/**
 * The SQLCipher passphrase is generated locally and wrapped by the Android Keystore key.
 * A missing or unreadable key is never replaced automatically when a database already exists.
 */
internal object DatabaseKey {
    private const val FILE_NAME = "atlas-db-passphrase.sealed"
    private const val RAW_KEY_BYTES = 32
    private const val ENCODED_KEY_BYTES = RAW_KEY_BYTES * 2

    fun storageFile(context: Context): File =
        File(context.noBackupFilesDir, FILE_NAME)

    fun read(context: Context): ByteArray? {
        val file = storageFile(context)
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        val encrypted = AtomicFile(file).openRead().use { it.readBytes() }
        val key = Sealed.open(encrypted)
        requireValid(key)
        return key
    }

    fun create(context: Context): ByteArray {
        val encoded = generate()
        try {
            store(context, encoded)
            return encoded
        } catch (failure: Exception) {
            encoded.fill(0)
            throw failure
        }
    }

    fun generate(): ByteArray {
        val randomBytes = ByteArray(RAW_KEY_BYTES).also(SecureRandom()::nextBytes)
        return try {
            randomBytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
                .toByteArray(StandardCharsets.US_ASCII)
        } finally {
            randomBytes.fill(0)
        }
    }

    fun store(context: Context, key: ByteArray) {
        requireValid(key)
        val encrypted = Sealed.seal(key)
        val atomic = AtomicFile(storageFile(context))
        var output: FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(encrypted)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            output?.let(atomic::failWrite)
            throw IllegalStateException("The database encryption key could not be saved.", failure)
        } finally {
            encrypted.fill(0)
        }
    }

    private fun requireValid(key: ByteArray) {
        require(key.size == ENCODED_KEY_BYTES && String(key, StandardCharsets.US_ASCII).matches(Regex("[0-9a-f]{64}"))) {
            "The stored database encryption key is invalid."
        }
    }
}