package com.nizam.app.feature.backup

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.nizam.app.data.db.DatabaseKey
import com.nizam.app.data.db.EncryptedDatabaseBootstrap
import com.nizam.app.data.db.MIGRATIONS
import com.nizam.app.data.db.NizamDatabase
import com.nizam.app.data.prefs.SettingsRepository
import com.nizam.app.feature.future.Sealed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import java.io.PushbackInputStream

data class BackupRestoreFile(val path: String, val size: Long)

data class BackupRestorePreview(
    val id: String,
    val files: List<BackupRestoreFile>,
    val totalBytes: Long,
    val encrypted: Boolean,
    val verified: Boolean
)

/**
 * Archive handling is deliberately two-phase: importing only stages and verifies files. A restore
 * is queued after the user reviews the inventory, then applied before Room and DataStore start.
 */
internal object BackupArchive {
    private const val FORMAT = "ATLAS_BACKUP"
    private const val VERSION = 4
    private const val MANIFEST_NAME = "atlas-manifest.json"
    private const val DATABASE_KEY_PATH = "keys/db-passphrase"
    private const val API_CREDENTIALS_PATH = "keys/api-credentials"
    private const val SETTINGS_PATH = "prefs/nizam_settings.preferences_pb"
    private const val MANIFEST_LIMIT = 4 * 1024 * 1024
    private const val MAX_ENTRIES = 5_000
    private const val MAX_ENTRY_BYTES = 512L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    private const val MAX_ARCHIVE_BYTES = 2_200_000_000L
    private const val SNAPSHOT_SPACE_RESERVE = 16L * 1024 * 1024
    private const val PBKDF2_ITERATIONS = 310_000
    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private val ENCRYPTION_MAGIC = "ATLAS-AES-GCM-BACKUP-V1\n".toByteArray(StandardCharsets.US_ASCII)

    private data class SourceFile(val path: String, val file: File? = null, val inlineBytes: ByteArray? = null) {
        val size: Long get() = file?.length() ?: inlineBytes?.size?.toLong() ?: 0L
    }
    private data class VerifiedFile(
        val path: String,
        val size: Long,
        val sha256: String,
        val sealedStaging: Boolean = false
    )

    suspend fun export(
        context: Context,
        target: Uri,
        includeVault: Boolean,
        recoveryPhrase: CharArray?
    ): String = withContext(Dispatchers.IO) {
        var sources: List<SourceFile> = emptyList()
        try {
            val cursor = NizamDatabase.get(context).openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(FULL)")
            cursor.use {
                check(it.moveToFirst() && it.getInt(0) == 0) {
                    "Atlas could not checkpoint the database safely. Try again when it is idle."
                }
            }

            val credentialBundle = SettingsRepository(context).exportCredentialBundle()
            sources = try {
                collectSources(context, includeVault, credentialBundle)
            } catch (failure: Exception) {
                credentialBundle.fill(0)
                throw failure
            }
            check(sources.any { it.path == "db/nizam.db" }) { "Atlas database file is missing." }
            val tempDir = File(context.noBackupFilesDir, "atlas_backup_exports")
            check(tempDir.exists() || tempDir.mkdirs()) { "Could not prepare a private export staging folder." }
            val temp = File(tempDir, "${UUID.randomUUID()}.sealed")
            try {
                Sealed.sealFile(temp) { fileOut ->
                    if (recoveryPhrase == null) {
                        writeZip(sources, fileOut, encrypted = false)
                    } else {
                        require(recoveryPhrase.size >= 12) { "Use a recovery phrase with at least 12 characters." }
                        writeEncrypted(recoveryPhrase, fileOut) { encryptedOut ->
                            writeZip(sources, encryptedOut, encrypted = true)
                        }
                    }
                }
                val out = context.contentResolver.openOutputStream(target)
                    ?: error("Could not write to that location.")
                out.use { targetOut -> Sealed.openFile(temp).use { it.copyTo(targetOut) } }
                runCatching {
                    writeAtomic(
                        File(context.noBackupFilesDir, LAST_EXPORT_NAME),
                        JSONObject()
                            .put("createdAtMillis", System.currentTimeMillis())
                            .put("fileCount", sources.size)
                            .put("encrypted", recoveryPhrase != null)
                            .put("vaultIncluded", includeVault)
                            .toString()
                    )
                }
            } finally {
                temp.delete()
                recoveryPhrase?.fill('\u0000')
            }
            val sizeKb = (sources.sumOf { it.size } / 1024).coerceAtLeast(1)
            "Backed up ${sources.size} files (about $sizeKb KB)." +
                if (recoveryPhrase == null) " This archive is not encrypted." else " The archive is encrypted; Atlas does not store its recovery phrase."
        } finally {
            sources.forEach { it.inlineBytes?.fill(0) }
            recoveryPhrase?.fill('\u0000')
        }
    }

    suspend fun stageRestore(
        context: Context,
        source: Uri,
        recoveryPhrase: CharArray?
    ): BackupRestorePreview = withContext(Dispatchers.IO) {
        val id = UUID.randomUUID().toString()
        val stageDir = stageDirectory(context, id)
        check(stageDir.mkdirs()) { "Could not prepare a private restore staging folder." }
        try {
            val input = context.contentResolver.openInputStream(source)
                ?: error("Could not read that backup.")
            val detected = try {
                openArchiveStream(input, recoveryPhrase)
            } catch (failure: Exception) {
                input.close()
                throw failure
            }
            val archiveFile = File(stageDir, ".archive.zip.sealed")
            Sealed.sealFile(archiveFile) { archiveOutput ->
                detected.first.use { archiveInput ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = archiveInput.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= MAX_ARCHIVE_BYTES) { "Backup archive exceeds the safe size limit." }
                        archiveOutput.write(buffer, 0, count)
                    }
                }
            }
            val (extractedFiles, manifest) = Sealed.openFile(archiveFile).use { stageZip(stageDir, it) }
            check(archiveFile.delete()) { "Could not remove the temporary protected archive." }
            val verified = manifest != null
            if (manifest != null) {
                require(manifest.optBoolean("encrypted") == detected.second) {
                    "Backup encryption metadata does not match the archive."
                }
                verifyManifest(manifest, extractedFiles)
            }
            check(extractedFiles.any { it.path == "db/nizam.db" }) {
                "This archive does not contain Atlas's database."
            }
            val files = prepareRestorableDatabase(context, stageDir, extractedFiles)

            val plan = JSONObject()
                .put("id", id)
                .put("encrypted", detected.second)
                .put("verified", verified)
                .put("files", JSONArray().apply {
                    files.forEach { item ->
                        put(
                            JSONObject()
                                .put("path", item.path)
                                .put("size", item.size)
                                .put("sha256", item.sha256)
                                .put("sealedStaging", item.sealedStaging)
                        )
                    }
                })
            writeAtomic(File(stageDir, PLAN_NAME), plan.toString())
            BackupRestorePreview(
                id = id,
                files = files.map { BackupRestoreFile(it.path, it.size) },
                totalBytes = files.sumOf { it.size },
                encrypted = detected.second,
                verified = verified
            )
        } catch (failure: Exception) {
            stageDir.deleteRecursively()
            throw IllegalArgumentException(
                failure.message ?: "This backup could not be safely validated.",
                failure
            )
        } finally {
            recoveryPhrase?.fill('\u0000')
        }
    }

    fun discardStage(context: Context, id: String) {
        if (isValidId(id)) stageDirectory(context, id).deleteRecursively()
    }

    fun canUndoLastRestore(context: Context): Boolean = runCatching {
        val id = readLastSnapshotId(context)
        isValidId(id) && File(snapshotDirectory(context, id), SNAPSHOT_NAME).isFile
    }.getOrDefault(false)

    fun queueUndoLastRestore(context: Context) {
        val id = readLastSnapshotId(context)
        require(isValidId(id) && File(snapshotDirectory(context, id), SNAPSHOT_NAME).isFile) {
            "No recoverable pre-restore snapshot is available."
        }
        readJson(File(snapshotDirectory(context, id), SNAPSHOT_NAME))
        val pending = File(restoreRoot(context), PENDING_NAME)
        check(!hasAtomicFile(pending)) { "Another restore is already pending." }
        writeAtomic(pending, JSONObject().put("id", id).put("phase", "UNDO_PENDING").toString())
    }

    fun queueRestore(context: Context, id: String, selectedPaths: Set<String>) {
        require(isValidId(id)) { "Invalid restore plan." }
        val stageDir = stageDirectory(context, id)
        val plan = readJson(File(stageDir, PLAN_NAME))
        val entries = plan.optJSONArray("files")
        require(plan.optString("id") == id && entries != null && entries.length() > 0) {
            "The staged backup is missing or incomplete."
        }
        val available = (0 until entries.length()).map { entries.getJSONObject(it).getString("path") }.toSet()
        require(selectedPaths.isNotEmpty() && available.containsAll(selectedPaths)) {
            "Select at least one file from this backup."
        }
        val databasePaths = available.filter { it.startsWith("db/") }.toSet()
        val selectedDatabasePaths = selectedPaths.intersect(databasePaths)
        require(selectedDatabasePaths.isEmpty() || selectedDatabasePaths == databasePaths) {
            "The database and its sidecar files must be restored together."
        }
        val hasDatabaseKey = DATABASE_KEY_PATH in available
        require(!hasDatabaseKey || (DATABASE_KEY_PATH in selectedPaths) == selectedDatabasePaths.isNotEmpty()) {
            "The database and its encryption key must be restored together."
        }
        if (API_CREDENTIALS_PATH in available) {
            require((API_CREDENTIALS_PATH in selectedPaths) == (SETTINGS_PATH in selectedPaths)) {
                "Saved API credentials and their settings must be restored together."
            }
        }
        val deleteDatabaseSidecars = if (selectedDatabasePaths.isNotEmpty()) {
            setOf("db/nizam.db-wal", "db/nizam.db-shm") - available
        } else emptySet()
        val pending = File(restoreRoot(context), PENDING_NAME)
        check(!hasAtomicFile(pending)) { "Another restore is already pending." }
        writeAtomic(
            pending,
            JSONObject()
                .put("id", id)
                .put("phase", "READY")
                .put("restorePaths", JSONArray().apply { selectedPaths.sorted().forEach(::put) })
                .put("deletePaths", JSONArray().apply { deleteDatabaseSidecars.sorted().forEach(::put) })
                .toString()
        )
    }

    /**
     * Called before constructing AppContainer. If a process died mid-commit, roll back to the
     * pre-restore snapshot before Room or DataStore can touch the partially restored files.
     */
    suspend fun applyPendingRestore(context: Context) {
        val pendingFile = File(restoreRoot(context), PENDING_NAME)
        if (!hasAtomicFile(pendingFile)) return
        val marker = readJson(pendingFile)
        val id = marker.optString("id")
        require(isValidId(id)) { "The pending restore marker is invalid." }
        val phase = marker.optString("phase", "READY")
        require(phase in setOf("READY", "SNAPSHOTTED", "APPLYING", "COMMITTED", "UNDO_PENDING")) {
            "The pending restore state is invalid."
        }
        val snapshot = snapshotDirectory(context, id)

        if (phase == "UNDO_PENDING") {
            check(rollbackSnapshot(context, snapshot)) {
                "Atlas could not finish recovering the previous files. The recovery snapshot was preserved."
            }
            AtomicFile(File(restoreRoot(context), LAST_SNAPSHOT_NAME)).delete()
            writeStatus(context, "Atlas restored the files saved before the most recent restore.")
            AtomicFile(pendingFile).delete()
            runCatching { snapshot.deleteRecursively() }
            return
        }
        if (phase == "COMMITTED") {
            finalizeCommittedRestore(context, id, pendingFile)
            return
        }

        val stageDir = stageDirectory(context, id)
        val plan = readJson(File(stageDir, PLAN_NAME))
        require(plan.optString("id") == id) { "The staged restore plan does not match its marker." }
        val selectedPlan = selectedPlan(plan, marker)

        if (phase == "APPLYING") {
            check(rollbackSnapshot(context, snapshot)) {
                "Atlas could not finish restoring its pre-restore snapshot. The snapshot was preserved."
            }
            writeStatus(context, "The restore was interrupted. Atlas restored the previous files; no imported files were kept.")
            AtomicFile(pendingFile).delete()
            stageDir.deleteRecursively()
            runCatching { snapshot.deleteRecursively() }
            return
        }

        if (phase == "READY") {
            try {
                createSnapshot(context, snapshot, selectedPlan)
                marker.put("phase", "SNAPSHOTTED")
                writeAtomic(pendingFile, marker.toString())
            } catch (failure: Exception) {
                snapshot.deleteRecursively()
                writeStatus(context, "Atlas could not create a pre-restore snapshot, so no files were changed.")
                AtomicFile(pendingFile).delete()
                stageDir.deleteRecursively()
                return
            }
        }

        marker.put("phase", "APPLYING")
        writeAtomic(pendingFile, marker.toString())
        try {
            applyStagedFiles(context, stageDir, selectedPlan)
            marker.put("phase", "COMMITTED")
            writeAtomic(pendingFile, marker.toString())
        } catch (failure: Exception) {
            check(rollbackSnapshot(context, snapshot)) {
                "Restore failed and rollback could not complete. The recovery snapshot was preserved."
            }
            writeStatus(context, "Restore was cancelled after an error. Atlas restored the previous files; no imported files were kept.")
            AtomicFile(pendingFile).delete()
            stageDir.deleteRecursively()
            runCatching { snapshot.deleteRecursively() }
            return
        }
        finalizeCommittedRestore(context, id, pendingFile)
    }

    fun consumeRestoreStatus(context: Context): String? {
        val file = File(restoreRoot(context), STATUS_NAME)
        if (!hasAtomicFile(file)) return null
        val status = runCatching { AtomicFile(file).openRead().bufferedReader().use { it.readText() } }
            .getOrNull()
        AtomicFile(file).delete()
        return status?.takeIf(String::isNotBlank)
    }

    fun lastExportMillis(context: Context): Long? = runCatching {
        readJson(File(context.noBackupFilesDir, LAST_EXPORT_NAME)).getLong("createdAtMillis")
    }.getOrNull()

    private fun collectSources(
        context: Context,
        includeVault: Boolean,
        credentialBundle: ByteArray
    ): List<SourceFile> {
        val sources = mutableListOf<SourceFile>()
        val db = context.getDatabasePath("nizam.db")
        listOf(db, File(db.path + "-wal"), File(db.path + "-shm"))
            .filter { it.isFile }
            .forEach { sources += SourceFile("db/${it.name}", it) }

        addTree(File(context.filesDir, "datastore"), "prefs", sources)
        addTree(File(context.applicationInfo.dataDir, "shared_prefs"), "shared-prefs", sources)

        val filesRoot = context.filesDir.canonicalFile
        val ignoredRoots = setOf("datastore", "vault", "cache", "code_cache", "atlas_backup_exports", "atlas_restore")
        filesRoot.walkTopDown()
            .onEnter { directory -> directory == filesRoot || directory.name !in ignoredRoots }
            .filter { it.isFile }
            .forEach { file ->
                val canonical = file.canonicalFile
                require(canonical.toPath().startsWith(filesRoot.toPath())) { "A private file points outside Atlas storage." }
                sources += SourceFile("files/${file.relativeTo(filesRoot).invariantSeparatorsPath}", file)
            }

        if (includeVault) addTree(File(context.filesDir, "vault"), "vault", sources)
        val databaseKey = DatabaseKey.read(context)
            ?: error("Atlas's database encryption key is missing; no backup was created.")
        try {
            sources += SourceFile(DATABASE_KEY_PATH, inlineBytes = databaseKey)
            sources += SourceFile(API_CREDENTIALS_PATH, inlineBytes = credentialBundle)
            check(sources.map { it.path }.distinct().size == sources.size) { "Two Atlas files map to the same backup path." }
            sources.forEach { require(isAllowedArchivePath(it.path)) { "Atlas found an unsupported file name in private storage." } }
            return sources.sortedBy { it.path }
        } catch (failure: Exception) {
            databaseKey.fill(0)
            throw failure
        }
    }

    private fun addTree(root: File, archiveRoot: String, into: MutableList<SourceFile>) {
        if (!root.exists()) return
        val canonicalRoot = root.canonicalFile
        root.walkTopDown().filter { it.isFile }.forEach { file ->
            require(file.canonicalFile.toPath().startsWith(canonicalRoot.toPath())) {
                "A private file points outside Atlas storage."
            }
            into += SourceFile("$archiveRoot/${file.relativeTo(root).invariantSeparatorsPath}", file)
        }
    }

    private fun writeZip(sources: List<SourceFile>, output: OutputStream, encrypted: Boolean) {
        val manifestEntries = JSONArray()
        var totalBytes = 0L
        ZipOutputStream(output.buffered()).use { zip ->
            sources.forEach { source ->
                require(isAllowedArchivePath(source.path)) { "Unsupported backup path." }
                zip.putNextEntry(ZipEntry(source.path))
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                val input = source.file?.inputStream()?.buffered()
                    ?: source.inlineBytes?.let(::ByteArrayInputStream)
                    ?: error("A backup source is missing.")
                input.use {
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        size += count
                        totalBytes += count
                        require(size <= MAX_ENTRY_BYTES && totalBytes <= MAX_TOTAL_BYTES) {
                            "Atlas data is larger than the safe backup limit."
                        }
                        digest.update(buffer, 0, count)
                        zip.write(buffer, 0, count)
                    }
                }
                zip.closeEntry()
                manifestEntries.put(
                    JSONObject()
                        .put("path", source.path)
                        .put("size", size)
                        .put("sha256", digest.digest().toHex())
                )
            }
            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("version", VERSION)
                .put("createdAtMillis", System.currentTimeMillis())
                .put("encrypted", encrypted)
                .put("entries", manifestEntries)
            zip.putNextEntry(ZipEntry(MANIFEST_NAME))
            zip.write(manifest.toString().toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
    }

    private fun writeEncrypted(
        passphrase: CharArray,
        output: OutputStream,
        write: (OutputStream) -> Unit
    ) {
        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val spec = PBEKeySpec(passphrase, salt, PBKDF2_ITERATIONS, KEY_BYTES * 8)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        spec.clearPassword()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            output.write(ENCRYPTION_MAGIC)
            output.write(salt)
            output.write(iv)
            write(CipherOutputStream(output, cipher))
        } finally {
            keyBytes.fill(0)
            passphrase.fill('\u0000')
        }
    }

    private fun openArchiveStream(input: InputStream, passphrase: CharArray?): Pair<InputStream, Boolean> {
        val pushback = PushbackInputStream(input.buffered(), ENCRYPTION_MAGIC.size)
        val prefix = ByteArray(ENCRYPTION_MAGIC.size)
        var read = 0
        while (read < prefix.size) {
            val count = pushback.read(prefix, read, prefix.size - read)
            if (count < 0) break
            read += count
        }
        if (read != ENCRYPTION_MAGIC.size || !prefix.contentEquals(ENCRYPTION_MAGIC)) {
            if (read > 0) pushback.unread(prefix, 0, read)
            return pushback to false
        }
        val phrase = passphrase ?: error("This backup is encrypted. Enter its recovery phrase and try again.")
        require(phrase.size >= 12) { "The recovery phrase is too short." }
        val salt = readExact(pushback, SALT_BYTES)
        val iv = readExact(pushback, IV_BYTES)
        val spec = PBEKeySpec(phrase, salt, PBKDF2_ITERATIONS, KEY_BYTES * 8)
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded
        spec.clearPassword()
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
            return CipherInputStream(pushback, cipher) to true
        } finally {
            keyBytes.fill(0)
        }
    }

    private fun readExact(input: InputStream, size: Int): ByteArray {
        val result = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(result, offset, size - offset)
            if (count < 0) error("This encrypted backup is incomplete.")
            offset += count
        }
        return result
    }

    private fun createSnapshot(context: Context, snapshotDir: File, plan: JSONObject) {
        if (snapshotDir.exists()) snapshotDir.deleteRecursively()
        check(snapshotDir.mkdirs()) { "Could not prepare the pre-restore snapshot." }
        val files = plan.getJSONArray("files")
        val snapshotEntries = JSONArray()
        val paths = buildList {
            for (index in 0 until files.length()) add(files.getJSONObject(index).getString("path"))
            val deletes = plan.optJSONArray("deletePaths")
            if (deletes != null) for (index in 0 until deletes.length()) add(deletes.getString(index))
        }.distinct()
        var requiredBytes = 0L
        for (path in paths) {
            val target = targetFor(context, path)
            check(!target.exists() || target.isFile) {
                "A restore destination is not a regular file. No files were changed."
            }
            if (target.isFile) {
                val size = target.length()
                check(size <= MAX_TOTAL_BYTES - requiredBytes) {
                    "The pre-restore snapshot is larger than Atlas's safe storage limit."
                }
                requiredBytes += size
            }
        }
        check(snapshotDir.usableSpace >= requiredBytes + SNAPSHOT_SPACE_RESERVE) {
            "There is not enough free space to create a safe undo snapshot. No files were changed."
        }
        for (path in paths) {
            val target = targetFor(context, path)
            val existed = target.isFile
            val item = JSONObject().put("path", path).put("existed", existed)
            if (existed) {
                val snapshotFile = resolveInside(snapshotDir, path)
                check(snapshotFile.parentFile?.exists() == true || snapshotFile.parentFile?.mkdirs() == true) {
                    "Could not prepare the pre-restore snapshot."
                }
                copySynced(target, snapshotFile)
                item.put("size", snapshotFile.length()).put("sha256", sha256(snapshotFile))
            }
            snapshotEntries.put(item)
        }
        writeAtomic(
            File(snapshotDir, SNAPSHOT_NAME),
            JSONObject().put("files", snapshotEntries).toString()
        )
    }

    private suspend fun applyStagedFiles(context: Context, stageDir: File, plan: JSONObject) {
        val files = plan.getJSONArray("files")
        var credentialsToImport: ByteArray? = null
        try {
            for (index in 0 until files.length()) {
                val item = files.getJSONObject(index)
                val path = item.getString("path")
                val stagedFile = resolveInside(stageDir, path)
                val target = targetFor(context, path)
                if (path == DATABASE_KEY_PATH || path == API_CREDENTIALS_PATH) {
                    check(item.optBoolean("sealedStaging") && stagedFile.isFile) {
                        "A staged sensitive key entry is not protected or is missing."
                    }
                    val protectedBytes = stagedFile.readBytes()
                    val keyBytes = try {
                        Sealed.open(protectedBytes)
                    } finally {
                        protectedBytes.fill(0)
                    }
                    var retainedForImport = false
                    try {
                        check(
                            keyBytes.size.toLong() == item.getLong("size") &&
                                sha256(keyBytes) == item.getString("sha256")
                        ) { "A staged sensitive key entry is missing or changed." }
                        if (path == DATABASE_KEY_PATH) {
                            DatabaseKey.store(context, keyBytes)
                        } else {
                            check(credentialsToImport == null) { "Backup contains duplicate saved credentials." }
                            credentialsToImport = keyBytes
                            retainedForImport = true
                        }
                    } finally {
                        if (!retainedForImport) keyBytes.fill(0)
                    }
                } else {
                    check(
                        !item.optBoolean("sealedStaging") &&
                            stagedFile.isFile &&
                            stagedFile.length() == item.getLong("size") &&
                            sha256(stagedFile) == item.getString("sha256")
                    ) {
                        "A staged backup file is missing or changed."
                    }
                    check(target.parentFile?.exists() == true || target.parentFile?.mkdirs() == true) {
                        "Could not prepare the restore destination."
                    }
                    copySynced(stagedFile, target, needsAtomicBackupCleanup(path))
                }
            }
            val deletePaths = plan.optJSONArray("deletePaths") ?: JSONArray()
            for (index in 0 until deletePaths.length()) {
                val target = targetFor(context, deletePaths.getString(index))
                if (target.exists()) check(target.delete()) { "Could not clear an old database sidecar." }
            }
            credentialsToImport?.let {
                SettingsRepository(context).importCredentialBundle(it)
            }
        } finally {
            credentialsToImport?.fill(0)
        }
    }

    private fun rollbackSnapshot(context: Context, snapshotDir: File): Boolean = runCatching {
        val snapshot = readJson(File(snapshotDir, SNAPSHOT_NAME))
        val files = snapshot.getJSONArray("files")
        for (index in files.length() - 1 downTo 0) {
            val item = files.getJSONObject(index)
            val path = item.getString("path")
            val target = targetFor(context, path)
            if (item.getBoolean("existed")) {
                val source = resolveInside(snapshotDir, path)
                check(
                    source.isFile &&
                        source.length() == item.getLong("size") &&
                        sha256(source) == item.getString("sha256")
                ) { "Pre-restore snapshot file is missing or changed." }
                check(target.parentFile?.exists() == true || target.parentFile?.mkdirs() == true) {
                    "Could not prepare snapshot recovery."
                }
                copySynced(source, target, needsAtomicBackupCleanup(path))
            } else {
                if (target.exists()) check(target.delete()) { "Could not remove a newly restored file." }
                val atomicBackup = File(target.path + ".bak")
                if (needsAtomicBackupCleanup(path) && atomicBackup.exists()) {
                    check(atomicBackup.delete()) { "Could not remove an incomplete settings backup." }
                }
            }
        }
        true
    }.getOrDefault(false)

    private fun targetFor(context: Context, archivePath: String): File {
        check(isAllowedArchivePath(archivePath)) { "Unsafe restore path." }
        val parts = archivePath.split('/')
        val base = when (parts.first()) {
            "db" -> {
                val db = context.getDatabasePath("nizam.db")
                check(parts[1] in setOf("nizam.db", "nizam.db-wal", "nizam.db-shm"))
                File(db.parentFile, parts[1])
            }
            "prefs" -> File(context.filesDir, "datastore/${parts.drop(1).joinToString("/")}")
            "shared-prefs" -> File(File(context.applicationInfo.dataDir, "shared_prefs"), parts.drop(1).joinToString("/"))
            "files" -> File(context.filesDir, parts.drop(1).joinToString("/"))
            "vault" -> File(context.filesDir, "vault/${parts.drop(1).joinToString("/")}")
            "keys" -> {
                when (archivePath) {
                    DATABASE_KEY_PATH -> DatabaseKey.storageFile(context)
                    API_CREDENTIALS_PATH -> File(context.noBackupFilesDir, ".atlas-api-credentials-restore")
                    else -> error("Unsupported key entry.")
                }
            }
            else -> error("Unsupported restore path.")
        }
        val root = when (parts.first()) {
            "db" -> context.getDatabasePath("nizam.db").parentFile
            "prefs" -> File(context.filesDir, "datastore")
            "shared-prefs" -> File(context.applicationInfo.dataDir, "shared_prefs")
            "files" -> context.filesDir
            "keys" -> context.noBackupFilesDir
            else -> File(context.filesDir, "vault")
        }.canonicalFile
        check(base.canonicalFile.toPath().startsWith(root.toPath())) { "Restore path escapes Atlas storage." }
        return base
    }

    private fun isAllowedArchivePath(path: String): Boolean {
        if (path.isBlank() || path.length > 1024 || path.startsWith("/") ||
            path.contains('\\') || path.contains(':') || path.any { it.code < 32 }) return false
        val parts = path.split('/')
        if (parts.any { it.isBlank() || it == "." || it == ".." }) return false
        return when (parts.firstOrNull()) {
            "db" -> parts.size == 2 && parts[1] in setOf("nizam.db", "nizam.db-wal", "nizam.db-shm")
            "prefs", "shared-prefs", "vault" -> parts.size >= 2
            "keys" -> path == DATABASE_KEY_PATH || path == API_CREDENTIALS_PATH
            "files" -> parts.size >= 2 && parts[1] !in setOf("datastore", "vault")
            else -> false
        }
    }

    private fun resolveInside(root: File, relative: String): File {
        val result = File(root, relative).canonicalFile
        check(result.toPath().startsWith(root.canonicalFile.toPath())) { "Archive path escapes its staging folder." }
        return result
    }

    private fun copySynced(source: File, target: File, clearAtomicBackup: Boolean = false) {
        val parent = target.parentFile ?: error("Invalid restore destination.")
        val temporary = File(parent, ".${target.name}.${UUID.randomUUID()}.restore")
        try {
            source.inputStream().buffered().use { input ->
                FileOutputStream(temporary).use { output ->
                    input.copyTo(output)
                    output.fd.sync()
                }
            }
            if (clearAtomicBackup) {
                val atomicBackup = File(target.path + ".bak")
                if (atomicBackup.exists()) {
                    check(atomicBackup.delete()) { "Could not clear an incomplete settings write." }
                }
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE
            )
        } finally {
            temporary.delete()
        }
    }

    private fun needsAtomicBackupCleanup(path: String): Boolean =
        path.startsWith("prefs/") || path.startsWith("shared-prefs/") || path.startsWith("keys/")

    private fun selectedPlan(plan: JSONObject, marker: JSONObject): JSONObject {
        val sourceFiles = plan.getJSONArray("files")
        val selectedArray = marker.optJSONArray("restorePaths")
            ?: error("Pending restore is missing its selected files.")
        val selected = (0 until selectedArray.length()).map { selectedArray.getString(it) }.toSet()
        require(selectedArray.length() == selected.size) { "Pending restore contains duplicate selections." }
        val allPaths = (0 until sourceFiles.length()).map { sourceFiles.getJSONObject(it).getString("path") }.toSet()
        val databasePaths = allPaths.filter { it.startsWith("db/") }.toSet()
        val selectedDatabasePaths = selected.intersect(databasePaths)
        require(selectedDatabasePaths.isEmpty() || selectedDatabasePaths == databasePaths) {
            "Pending restore splits the database from its sidecar files."
        }
        require(
            DATABASE_KEY_PATH !in allPaths ||
                (DATABASE_KEY_PATH in selected) == selectedDatabasePaths.isNotEmpty()
        ) { "Pending restore separates the database from its encryption key." }
        require(
            API_CREDENTIALS_PATH !in allPaths ||
                (API_CREDENTIALS_PATH in selected) == (SETTINGS_PATH in selected)
        ) { "Pending restore separates saved API credentials from their settings." }
        val selectedFiles = JSONArray()
        for (index in 0 until sourceFiles.length()) {
            val item = sourceFiles.getJSONObject(index)
            if (item.getString("path") in selected) selectedFiles.put(item)
        }
        require(selectedFiles.length() == selected.size && selected.isNotEmpty()) {
            "Pending restore contains an unknown or empty file selection."
        }
        val deletes = marker.optJSONArray("deletePaths") ?: JSONArray()
        val expectedDeletes = if (selectedDatabasePaths.isNotEmpty()) {
            setOf("db/nizam.db-wal", "db/nizam.db-shm") - databasePaths
        } else emptySet()
        val actualDeletes = (0 until deletes.length()).map { deletes.getString(it) }.toSet()
        require(actualDeletes.size == deletes.length() && actualDeletes == expectedDeletes) {
            "Pending restore contains an invalid database sidecar operation."
        }
        for (index in 0 until deletes.length()) {
            require(deletes.getString(index) in setOf("db/nizam.db-wal", "db/nizam.db-shm")) {
                "Pending restore contains an unsupported deletion."
            }
        }
        return JSONObject().put("files", selectedFiles).put("deletePaths", deletes)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().toHex()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun writeAtomic(file: File, text: String) {
        writeAtomic(file, text.toByteArray(StandardCharsets.UTF_8))
    }

    private fun writeAtomic(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        var stream: FileOutputStream? = null
        try {
            file.parentFile?.let { check(it.exists() || it.mkdirs()) }
            stream = atomic.startWrite()
            stream.write(bytes)
            stream.fd.sync()
            atomic.finishWrite(stream)
        } catch (failure: Exception) {
            stream?.let(atomic::failWrite)
            throw failure
        }
    }

    private fun readJson(file: File): JSONObject =
        JSONObject(AtomicFile(file).openRead().bufferedReader().use { it.readText() })

    private fun readEntryLimited(input: InputStream, limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            check(total <= limit) { "Backup manifest is too large." }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun stageDirectory(context: Context, id: String): File =
        File(File(restoreRoot(context), "staged"), id)

    private fun snapshotDirectory(context: Context, id: String): File =
        File(File(restoreRoot(context), "snapshots"), id)

    private fun restoreRoot(context: Context): File =
        File(context.noBackupFilesDir, "atlas_restore")

    private fun readLastSnapshotId(context: Context): String =
        readJson(File(restoreRoot(context), LAST_SNAPSHOT_NAME)).optString("id")

    private fun finalizeCommittedRestore(context: Context, id: String, pendingFile: File) {
        require(isValidId(id)) { "The committed restore marker is invalid." }
        readJson(File(snapshotDirectory(context, id), SNAPSHOT_NAME))
        writeAtomic(
            File(restoreRoot(context), LAST_SNAPSHOT_NAME),
            JSONObject().put("id", id).put("savedAtMillis", System.currentTimeMillis()).toString()
        )
        writeStatus(
            context,
            "Restore completed. You can undo it from Settings; the selected pre-restore files are kept on this device."
        )
        AtomicFile(pendingFile).delete()
        stageDirectory(context, id).deleteRecursively()
    }

    private fun hasAtomicFile(file: File): Boolean =
        file.exists() || File(file.path + ".bak").exists()

    private fun isValidId(id: String): Boolean =
        id.matches(Regex("[0-9a-fA-F-]{36}"))

    private fun writeStatus(context: Context, value: String) =
        writeAtomic(File(restoreRoot(context), STATUS_NAME), value)

    private fun ByteArray.toHex(): String =
        joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun verifyManifest(manifest: JSONObject, files: List<VerifiedFile>) {
        require(manifest.optString("format") == FORMAT && manifest.optInt("version") in 2..VERSION) {
            "This backup uses an unsupported manifest version."
        }
        val declaredArray = manifest.optJSONArray("entries")
            ?: error("Backup manifest is missing its file inventory.")
        val declared = (0 until declaredArray.length()).associate { index ->
            val item = declaredArray.getJSONObject(index)
            val path = item.getString("path")
            require(isAllowedArchivePath(path) && path != MANIFEST_NAME) { "Manifest contains an unsafe path." }
            val size = item.getLong("size")
            val hash = item.getString("sha256")
            require(size in 0..MAX_ENTRY_BYTES && hash.matches(Regex("[0-9a-f]{64}"))) {
                "Manifest contains invalid file metadata."
            }
            path to (size to hash)
        }
        require(declared.size == declaredArray.length()) { "Manifest contains duplicate file paths." }
        require(declared.keys == files.map { it.path }.toSet()) { "Backup inventory does not match its manifest." }
        files.forEach { file ->
            val metadata = declared[file.path] ?: error("Backup file is missing from the manifest.")
            require(metadata.first == file.size && metadata.second == file.sha256) {
                "Backup verification failed for ${file.path}."
            }
        }
    }

    private fun stageZip(stageDir: File, input: InputStream): Pair<List<VerifiedFile>, JSONObject?> {
        val staged = mutableListOf<VerifiedFile>()
        var manifest: JSONObject? = null
        var totalBytes = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                check(!entry.isDirectory) { "Backup directory entries are not supported." }
                val name = entry.name
                if (name == MANIFEST_NAME) {
                    check(manifest == null) { "Backup contains duplicate manifests." }
                    manifest = JSONObject(String(readEntryLimited(zip, MANIFEST_LIMIT), StandardCharsets.UTF_8))
                    zip.closeEntry()
                    continue
                }
                check(isAllowedArchivePath(name)) { "Backup contains an unsafe or unsupported path." }
                check(staged.none { it.path == name }) { "Backup contains duplicate file paths." }
                check(staged.size < MAX_ENTRIES) { "Backup contains too many files." }
                val destination = resolveInside(stageDir, name)
                check(destination.parentFile?.exists() == true || destination.parentFile?.mkdirs() == true) {
                    "Could not stage a backup file."
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var size = 0L
                val keyBuffer = if (name == DATABASE_KEY_PATH || name == API_CREDENTIALS_PATH) {
                    ByteArrayOutputStream()
                } else null
                val destinationStream: OutputStream = keyBuffer ?: FileOutputStream(destination)
                destinationStream.use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        size += count
                        totalBytes += count
                        check(
                            size <= MAX_ENTRY_BYTES &&
                                totalBytes <= MAX_TOTAL_BYTES &&
                                (name != DATABASE_KEY_PATH || size <= 64L) &&
                                (name != API_CREDENTIALS_PATH ||
                                    size <= SettingsRepository.MAX_CREDENTIAL_BUNDLE_BYTES)
                        ) {
                            "Backup exceeds the safe extraction size limit."
                        }
                        digest.update(buffer, 0, count)
                        out.write(buffer, 0, count)
                    }
                    (out as? FileOutputStream)?.fd?.sync()
                }
                var sealedStaging = false
                if (keyBuffer != null) {
                    val keyBytes = keyBuffer.toByteArray()
                    var protectedBytes: ByteArray? = null
                    try {
                        if (name == DATABASE_KEY_PATH) {
                            check(
                                keyBytes.size == 64 &&
                                    String(keyBytes, StandardCharsets.US_ASCII).matches(Regex("[0-9a-f]{64}"))
                            ) { "Backup contains an invalid database encryption key." }
                        } else {
                            SettingsRepository.validateCredentialBundle(keyBytes)
                        }
                        protectedBytes = Sealed.seal(keyBytes)
                        FileOutputStream(destination).use { out ->
                            out.write(protectedBytes)
                            out.fd.sync()
                        }
                        sealedStaging = true
                    } finally {
                        keyBytes.fill(0)
                        protectedBytes?.fill(0)
                    }
                }
                staged += VerifiedFile(name, size, digest.digest().toHex(), sealedStaging)
                zip.closeEntry()
            }
        }
        return staged to manifest
    }

    /**
     * Verify the SQLCipher key/database pair before presenting a restore plan. Older plaintext
     * backups are converted only inside protected staging, with their original preserved until
     * the encrypted copy is verified.
     */
    private fun prepareRestorableDatabase(
        context: Context,
        stageDir: File,
        extractedFiles: List<VerifiedFile>
    ): List<VerifiedFile> {
        val database = resolveInside(stageDir, "db/nizam.db")
        check(database.isFile) { "This backup does not contain Atlas's database." }

        val archivedKey = extractedFiles.singleOrNull { it.path == DATABASE_KEY_PATH }
        if (archivedKey != null) {
            val key = readStagedSensitiveFile(stageDir, archivedKey)
            try {
                val schemaVersion = EncryptedDatabaseBootstrap.inspectEncryptedBackup(database, key)
                    ?: error("The database failed its key or integrity check. No restore files were changed.")
                requireSupportedRoomSchema(schemaVersion)
            } finally {
                key.fill(0)
            }
            return extractedFiles
        }

        val installedKey = DatabaseKey.read(context)
        if (installedKey != null) {
            try {
                val schemaVersion = EncryptedDatabaseBootstrap.inspectEncryptedBackup(database, installedKey)
                if (schemaVersion != null) {
                    requireSupportedRoomSchema(schemaVersion)
                    return extractedFiles + stageSensitiveDatabaseKey(stageDir, installedKey)
                }
            } finally {
                installedKey.fill(0)
            }
        }

        if (!EncryptedDatabaseBootstrap.isPlaintextBackup(database)) {
            error(
                "This backup has no usable database encryption key and is not a valid legacy plaintext database. " +
                    "The current Atlas data was not changed."
            )
        }

        val generatedKey = DatabaseKey.generate()
        try {
            EncryptedDatabaseBootstrap.encryptPlaintextBackup(database, generatedKey)
            val schemaVersion = EncryptedDatabaseBootstrap.inspectEncryptedBackup(database, generatedKey)
                ?: error("The converted database failed its key or integrity check.")
            requireSupportedRoomSchema(schemaVersion)
            val withoutPlaintextSidecars = extractedFiles.filterNot {
                it.path == "db/nizam.db-wal" || it.path == "db/nizam.db-shm"
            }
            return withoutPlaintextSidecars + stageSensitiveDatabaseKey(stageDir, generatedKey)
        } finally {
            generatedKey.fill(0)
        }
    }

    private fun readStagedSensitiveFile(stageDir: File, item: VerifiedFile): ByteArray {
        check(item.sealedStaging) { "A staged database key is not protected." }
        val stagedFile = resolveInside(stageDir, item.path)
        val protectedBytes = stagedFile.readBytes()
        val value = try {
            Sealed.open(protectedBytes)
        } finally {
            protectedBytes.fill(0)
        }
        if (value.size.toLong() != item.size || sha256(value) != item.sha256) {
            value.fill(0)
            error("The staged database key is missing or changed.")
        }
        return value
    }

    private fun stageSensitiveDatabaseKey(stageDir: File, key: ByteArray): VerifiedFile {
        val path = resolveInside(stageDir, DATABASE_KEY_PATH)
        check(path.parentFile?.exists() == true || path.parentFile?.mkdirs() == true) {
            "Could not protect the restored database key in private staging."
        }
        val protectedBytes = Sealed.seal(key)
        try {
            FileOutputStream(path).use { output ->
                output.write(protectedBytes)
                output.fd.sync()
            }
        } finally {
            protectedBytes.fill(0)
        }
        return VerifiedFile(DATABASE_KEY_PATH, key.size.toLong(), sha256(key), sealedStaging = true)
    }

    private fun requireSupportedRoomSchema(version: Int) {
        val latestSupported = MIGRATIONS.maxOfOrNull { it.endVersion }
            ?: error("Atlas has no registered database migration path.")
        fun reachesLatest(current: Int, visited: Set<Int>): Boolean {
            if (current == latestSupported) return true
            if (current > latestSupported || current in visited) return false
            return MIGRATIONS
                .filter { it.startVersion == current }
                .any { reachesLatest(it.endVersion, visited + current) }
        }
        require(reachesLatest(version, emptySet())) {
            "This backup uses database schema $version, which Atlas cannot safely migrate. " +
                "The current database was not changed."
        }
    }

    private const val PLAN_NAME = "restore-plan.json"
    private const val PENDING_NAME = "pending-restore.json"
    private const val SNAPSHOT_NAME = "snapshot-manifest.json"
    private const val STATUS_NAME = "restore-status.txt"
    private const val LAST_SNAPSHOT_NAME = "last-restore-snapshot.json"
    private const val LAST_EXPORT_NAME = "last-backup-export.json"
}