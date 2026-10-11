package com.nizam.app.feature.backup

import android.content.Context
import android.net.Uri
import com.nizam.app.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * User-facing backup API. Archive validation and staged restore are kept separate from the
 * Settings UI so imported data cannot touch live stores before review and app restart.
 */
object Backup {

    suspend fun export(
        context: Context,
        target: Uri,
        includeVault: Boolean,
        recoveryPhrase: CharArray? = null
    ): String = BackupArchive.export(context, target, includeVault, recoveryPhrase)

    suspend fun stageRestore(
        context: Context,
        source: Uri,
        recoveryPhrase: CharArray? = null
    ): BackupRestorePreview = BackupArchive.stageRestore(context, source, recoveryPhrase)

    fun queueRestore(context: Context, id: String, selectedPaths: Set<String>) =
        BackupArchive.queueRestore(context, id, selectedPaths)
    fun discardStage(context: Context, id: String) = BackupArchive.discardStage(context, id)
    suspend fun applyPendingRestore(context: Context) = BackupArchive.applyPendingRestore(context)
    fun consumeRestoreStatus(context: Context): String? = BackupArchive.consumeRestoreStatus(context)
    fun canUndoLastRestore(context: Context): Boolean = BackupArchive.canUndoLastRestore(context)
    fun queueUndoLastRestore(context: Context) = BackupArchive.queueUndoLastRestore(context)
    fun lastExportMillis(context: Context): Long? = BackupArchive.lastExportMillis(context)

    /** CSV of one module, for spreadsheets. */
    suspend fun exportModuleCsv(
        context: Context,
        container: AppContainer,
        moduleKey: String,
        target: Uri
    ): String = withContext(Dispatchers.IO) {
        val spec = container.dynamicRepository.spec(moduleKey)
            ?: return@withContext "No module with that key."
        val entries = container.dynamicRepository.entries(moduleKey).first()
        val fields = spec.fields

        fun escape(value: String) = "\"" + value.replace("\"", "\"\"") + "\""

        context.contentResolver.openOutputStream(target)?.use { out ->
            out.bufferedWriter().use { writer ->
                writer.appendLine(
                    (listOf("date", "title") + fields.map { it.label }).joinToString(",") { escape(it) }
                )
                entries.forEach { entry ->
                    writer.appendLine(
                        (listOf(entry.localDate, entry.title) + fields.map { entry.value(it.key) })
                            .joinToString(",") { escape(it) }
                    )
                }
            }
        } ?: return@withContext "Could not write that file."
        "Exported ${entries.size} rows from ${spec.name}."
    }
}
