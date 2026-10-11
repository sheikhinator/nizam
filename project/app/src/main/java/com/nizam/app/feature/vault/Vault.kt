package com.nizam.app.feature.vault

import android.content.Context
import android.net.Uri
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Entity(tableName = "vault_item")
data class VaultItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val mime: String,
    val fileName: String,
    val sizeBytes: Long,
    val addedAt: Long = System.currentTimeMillis()
)

@Dao
interface VaultDao {
    @Query("SELECT * FROM vault_item ORDER BY addedAt DESC")
    fun observeItems(): Flow<List<VaultItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: VaultItem)

    @Query("DELETE FROM vault_item WHERE id = :id")
    suspend fun delete(id: Long)
}

/**
 * AES-256-GCM encryption with a key derived from the user's passphrase (PBKDF2, 120k iterations).
 * The passphrase is never stored — lose it and the files are gone, which is the point.
 * Encrypted blobs live in the app's private storage, so nothing appears in the gallery.
 */
object VaultCrypto {

    private const val ITERATIONS = 120_000
    private const val KEY_BITS = 256
    private const val GCM_TAG_BITS = 128

    fun deriveKey(passphrase: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    /** Layout: [16-byte salt][12-byte IV][ciphertext+tag] */
    suspend fun encryptInto(
        context: Context,
        source: Uri,
        passphrase: String,
        outFile: File
    ): Long = withContext(Dispatchers.IO) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val key = deriveKey(passphrase, salt)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))

        context.contentResolver.openInputStream(source).use { input ->
            requireNotNull(input) { "Could not read that file." }
            outFile.outputStream().use { raw ->
                raw.write(salt)
                raw.write(iv)
                javax.crypto.CipherOutputStream(raw, cipher).use { encrypted ->
                    input.copyTo(encrypted, 64 * 1024)
                }
            }
        }
        outFile.length()
    }

    suspend fun decryptToCache(
        file: File,
        passphrase: String,
        cacheFile: File
    ): File = withContext(Dispatchers.IO) {
        file.inputStream().use { raw ->
            val salt = ByteArray(16).also { raw.read(it) }
            val iv = ByteArray(12).also { raw.read(it) }
            val key = deriveKey(passphrase, salt)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            javax.crypto.CipherInputStream(raw, cipher).use { decrypted ->
                cacheFile.outputStream().use { out -> decrypted.copyTo(out, 64 * 1024) }
            }
        }
        cacheFile
    }

    fun vaultDir(context: Context): File =
        File(context.filesDir, "vault").apply { if (!exists()) mkdirs() }
}

class VaultRepository(private val dao: VaultDao) {
    fun items() = dao.observeItems()
    suspend fun add(item: VaultItem) = dao.insert(item)
    suspend fun remove(item: VaultItem, context: Context) {
        runCatching { File(VaultCrypto.vaultDir(context), item.fileName).delete() }
        dao.delete(item.id)
    }
}
