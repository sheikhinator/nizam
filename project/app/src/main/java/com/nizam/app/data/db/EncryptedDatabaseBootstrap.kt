package com.nizam.app.data.db

import android.content.Context
import android.util.AtomicFile
import net.zetetic.database.sqlcipher.SQLiteDatabase as CipherDatabase
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/**
 * Converts existing plaintext Room databases once, before Room opens them. The original remains
 * available until an encrypted copy passes SQLCipher's integrity check and is opened by Room.
 */
internal object EncryptedDatabaseBootstrap {
    private const val READY_FILE = "atlas-db-encryption-ready"
    private const val ENCRYPTING_SUFFIX = ".encrypted-pending"
    private const val PLAINTEXT_SUFFIX = ".plaintext-migration-backup"

    fun prepare(context: Context): ByteArray {
        System.loadLibrary("sqlcipher")
        val database = context.getDatabasePath("nizam.db")
        val encryptedPending = File(database.path + ENCRYPTING_SUFFIX)
        val plaintextBackup = File(database.path + PLAINTEXT_SUFFIX)
        val readyFile = File(context.noBackupFilesDir, READY_FILE)
        var key = DatabaseKey.read(context)

        recoverInterruptedMigration(database, encryptedPending, plaintextBackup, key)

        if (hasAtomicFile(readyFile)) {
            check(database.isFile) {
                "Atlas's encrypted database is missing. Its data was not recreated or deleted."
            }
            if (key != null && isEncrypted(database, key)) return key
            if (isPlaintext(database)) {
                key = key ?: DatabaseKey.create(context)
                migratePlaintext(database, encryptedPending, plaintextBackup, key)
                return key
            }
            if (key == null) error("Atlas's database key is missing. The database was left untouched.")
            error("Atlas could not open or verify its database. The original file was left untouched.")
        }

        if (!database.exists()) return key ?: DatabaseKey.create(context)

        if (key != null && isEncrypted(database, key)) return key
        if (!isPlaintext(database)) {
            error("Atlas could not identify its existing database. It was left untouched.")
        }

        key = key ?: DatabaseKey.create(context)
        migratePlaintext(database, encryptedPending, plaintextBackup, key)
        return key
    }

    fun markReady(context: Context) {
        val marker = File(context.noBackupFilesDir, READY_FILE)
        writeAtomic(marker, "sqlcipher-v1".toByteArray(StandardCharsets.UTF_8))
        val database = context.getDatabasePath("nizam.db")
        val plaintextBackup = File(database.path + PLAINTEXT_SUFFIX)
        if (plaintextBackup.exists()) {
            check(plaintextBackup.delete()) {
                "The database is encrypted, but its temporary plaintext migration copy could not be removed."
            }
        }
    }

    /**
     * Inspect a staged encrypted backup before it can replace the live database. Returns its Room
     * schema version only when SQLCipher can open it with the supplied key and integrity passes.
     */
    fun inspectEncryptedBackup(database: File, key: ByteArray): Int? {
        System.loadLibrary("sqlcipher")
        if (!database.isFile) return null
        return runCatching {
            CipherDatabase.openDatabase(
                database.absolutePath,
                key,
                null,
                CipherDatabase.OPEN_READONLY,
                null
            ).use { opened ->
                if (!checkIntegrity(opened)) {
                    null
                } else {
                    opened.rawQuery("PRAGMA user_version", null).use { cursor ->
                        if (cursor.moveToFirst()) cursor.getInt(0) else null
                    }
                }
            }
        }.getOrNull()
    }

    fun isPlaintextBackup(database: File): Boolean {
        System.loadLibrary("sqlcipher")
        return database.isFile && isPlaintext(database)
    }

    /**
     * Encrypt a legacy plaintext backup in its private staging directory. Keep the original beside
     * it until the encrypted replacement passes an integrity check with the generated key.
     */
    fun encryptPlaintextBackup(database: File, key: ByteArray) {
        System.loadLibrary("sqlcipher")
        require(database.isFile && isPlaintext(database)) {
            "The staged legacy database is not a valid plaintext Room database."
        }
        val parent = database.parentFile ?: error("The staged database has no parent folder.")
        val token = UUID.randomUUID().toString()
        val encryptedPending = File(parent, ".${database.name}.$token.encrypted")
        val plaintextBackup = File(parent, ".${database.name}.$token.plaintext")
        check(!encryptedPending.exists() && !plaintextBackup.exists()) {
            "A temporary legacy database conversion already exists."
        }
        val source = CipherDatabase.openDatabase(
            database.absolutePath,
            byteArrayOf(),
            null,
            CipherDatabase.OPEN_READWRITE,
            null
        )
        try {
            check(checkIntegrity(source)) {
                "The legacy database failed its integrity check and was left unchanged."
            }
            checkpointWal(source)
            source.rawExecSQL(
                "ATTACH DATABASE ? AS atlas_encrypted KEY ?",
                encryptedPending.absolutePath,
                String(key, StandardCharsets.US_ASCII)
            )
            try {
                source.rawQuery("SELECT sqlcipher_export('atlas_encrypted')", null).use { cursor ->
                    check(cursor.moveToFirst()) { "SQLCipher could not encrypt the legacy database." }
                }
            } finally {
                source.rawExecSQL("DETACH DATABASE atlas_encrypted")
            }
        } finally {
            source.close()
        }

        try {
            check(isEncrypted(encryptedPending, key)) {
                "The encrypted legacy database failed verification; the staged original was preserved."
            }
            syncFile(encryptedPending)
            listOf(File(database.path + "-wal"), File(database.path + "-shm")).forEach { sidecar ->
                if (sidecar.exists()) check(sidecar.delete()) {
                    "A legacy database sidecar could not be cleared after checkpointing."
                }
            }
            moveAtomically(database, plaintextBackup)
            try {
                moveAtomically(encryptedPending, database)
                check(isEncrypted(database, key)) {
                    "The encrypted legacy database replacement failed verification."
                }
                check(plaintextBackup.delete()) {
                    "The encrypted staged database is ready, but its temporary plaintext copy could not be removed."
                }
            } catch (failure: Exception) {
                if (database.exists()) check(database.delete()) {
                    "Could not remove the failed staged encrypted database."
                }
                if (plaintextBackup.exists()) moveAtomically(plaintextBackup, database)
                throw IllegalStateException(
                    "Atlas restored the original staged database after encryption failed.",
                    failure
                )
            }
        } finally {
            if (encryptedPending.exists()) encryptedPending.delete()
        }
    }

    private fun migratePlaintext(database: File, encryptedPending: File, plaintextBackup: File, key: ByteArray) {
        if (!encryptedPending.isFile || !isEncrypted(encryptedPending, key)) {
            if (encryptedPending.exists()) check(encryptedPending.delete()) {
                "Could not remove an incomplete encrypted database copy."
            }
            val source = CipherDatabase.openDatabase(
                database.absolutePath,
                byteArrayOf(),
                null,
                CipherDatabase.OPEN_READWRITE,
                null
            )
            try {
                check(checkIntegrity(source)) {
                    "Atlas's existing database failed its integrity check and was preserved."
                }
                checkpointWal(source)
                val passphrase = String(key, StandardCharsets.US_ASCII)
                source.rawExecSQL(
                    "ATTACH DATABASE ? AS atlas_encrypted KEY ?",
                    encryptedPending.absolutePath,
                    passphrase
                )
                try {
                    source.rawQuery("SELECT sqlcipher_export('atlas_encrypted')", null).use { cursor ->
                        check(cursor.moveToFirst()) { "SQLCipher could not export Atlas's database." }
                    }
                } finally {
                    source.rawExecSQL("DETACH DATABASE atlas_encrypted")
                }
            } finally {
                source.close()
            }
        }

        check(isEncrypted(encryptedPending, key)) {
            "The encrypted database copy failed its integrity check; the original was preserved."
        }
        syncFile(encryptedPending)
        removePlaintextSidecars(database)

        check(!plaintextBackup.exists()) {
            "A prior database migration needs recovery before another conversion can run."
        }
        moveAtomically(database, plaintextBackup)
        try {
            moveAtomically(encryptedPending, database)
            check(isEncrypted(database, key)) { "The replacement database failed verification." }
        } catch (failure: Exception) {
            if (database.exists()) database.delete()
            if (!database.exists() && plaintextBackup.exists()) moveAtomically(plaintextBackup, database)
            throw IllegalStateException("Atlas kept the original database because encryption did not complete.", failure)
        }
    }

    private fun recoverInterruptedMigration(
        database: File,
        encryptedPending: File,
        plaintextBackup: File,
        key: ByteArray?
    ) {
        if (!plaintextBackup.exists()) {
            if (!database.exists() && encryptedPending.exists()) {
                check(key != null && isEncrypted(encryptedPending, key)) {
                    "Atlas found a pending database copy but could not verify its key; recovery files were preserved."
                }
                moveAtomically(encryptedPending, database)
                return
            }
            if (database.isFile && key != null && isEncrypted(database, key) && encryptedPending.exists()) {
                check(encryptedPending.delete()) { "Could not remove a stale encryption copy." }
            }
            return
        }

        if (database.isFile && key != null && isEncrypted(database, key)) {
            check(plaintextBackup.delete()) { "Could not remove the old plaintext migration copy." }
            if (encryptedPending.exists()) check(encryptedPending.delete())
            return
        }

        if (key != null && encryptedPending.isFile && isEncrypted(encryptedPending, key)) {
            if (database.exists()) {
                check(database.delete()) { "Could not recover the interrupted database migration." }
            }
            moveAtomically(encryptedPending, database)
            if (isEncrypted(database, key)) {
                check(plaintextBackup.delete()) { "Could not remove the old plaintext migration copy." }
                return
            }
            database.delete()
            moveAtomically(plaintextBackup, database)
            error("Atlas restored the original database after an interrupted encryption migration.")
        }

        if (!database.exists()) {
            moveAtomically(plaintextBackup, database)
            if (encryptedPending.exists()) encryptedPending.delete()
            return
        }

        if (isPlaintext(database)) {
            check(plaintextBackup.delete()) { "Could not remove an obsolete plaintext migration copy." }
            if (encryptedPending.exists()) encryptedPending.delete()
            return
        }

        error("Atlas found an incomplete database migration and preserved its recovery files.")
    }

    private fun isPlaintext(file: File): Boolean = runCatching {
        CipherDatabase.openDatabase(
            file.absolutePath,
            byteArrayOf(),
            null,
            CipherDatabase.OPEN_READONLY,
            null
        ).use { checkIntegrity(it) }
    }.getOrDefault(false)

    private fun isEncrypted(file: File, key: ByteArray): Boolean = runCatching {
        CipherDatabase.openDatabase(
            file.absolutePath,
            key,
            null,
            CipherDatabase.OPEN_READONLY,
            null
        ).use { checkIntegrity(it) }
    }.getOrDefault(false)

    private fun checkIntegrity(database: CipherDatabase): Boolean =
        database.rawQuery("PRAGMA integrity_check", null).use { cursor ->
            cursor.moveToFirst() && cursor.getString(0) == "ok"
        }

    private fun checkpointWal(database: CipherDatabase) {
        database.rawQuery("PRAGMA wal_checkpoint(FULL)", null).use { cursor ->
            check(cursor.moveToFirst() && cursor.getInt(0) == 0) {
                "Atlas could not safely checkpoint its database before encryption."
            }
            val logFrames = cursor.getInt(1)
            val checkpointedFrames = cursor.getInt(2)
            check(logFrames < 0 || checkpointedFrames == logFrames) {
                "Atlas could not checkpoint every database write before encryption."
            }
        }
    }

    private fun removePlaintextSidecars(database: File) {
        listOf(File(database.path + "-wal"), File(database.path + "-shm")).forEach { sidecar ->
            if (sidecar.exists()) check(sidecar.delete()) {
                "A temporary plaintext database sidecar could not be removed."
            }
        }
    }

    private fun syncFile(file: File) {
        FileOutputStream(file, true).use { it.fd.sync() }
    }

    private fun moveAtomically(source: File, target: File) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun hasAtomicFile(file: File): Boolean =
        file.exists() || File(file.path + ".bak").exists()

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        var output: FileOutputStream? = null
        try {
            output = atomic.startWrite()
            output.write(bytes)
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (failure: Exception) {
            output?.let(atomic::failWrite)
            throw IllegalStateException("The database encryption state could not be saved.", failure)
        }
    }
}