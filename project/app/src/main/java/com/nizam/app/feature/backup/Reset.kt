package com.nizam.app.feature.backup

import android.content.Context
import com.nizam.app.data.db.NizamDatabase
import com.nizam.app.data.prefs.SettingsRepository
import java.io.File

/**
 * Wipes Atlas back to a fresh install. The database is closed before its file is deleted — Room
 * holds it open, and deleting under it leaves a half-written file that crashes the next launch.
 */
object Reset {
    suspend fun everything(context: Context, settings: SettingsRepository, includeVault: Boolean) {
        runCatching { NizamDatabase.get(context).close() }
        val db = context.getDatabasePath("nizam.db")
        listOf(db, File(db.path + "-wal"), File(db.path + "-shm")).forEach { runCatching { it.delete() } }
        settings.wipe()
        listOf("council.json", "town.json").forEach { runCatching { File(context.filesDir, it).delete() } }
        if (includeVault) runCatching { File(context.filesDir, "vault").deleteRecursively() }
        context.cacheDir.listFiles()?.forEach { runCatching { it.deleteRecursively() } }
    }
}
