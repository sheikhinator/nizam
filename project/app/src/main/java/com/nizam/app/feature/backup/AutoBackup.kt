package com.nizam.app.feature.backup

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.feature.future.Sealed
import com.nizam.app.feature.nx.Share
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

/** A weekly encrypted backup written to the app's own folder (kept: the last four), plus a one-tap way to copy it out. */
object AutoBackup {
    private fun p(c: Context) = c.getSharedPreferences("autobackup", 0)
    fun on(c: Context) = p(c).getBoolean("on", false)
    fun setOn(c: Context, v: Boolean) = p(c).edit().putBoolean("on", v).apply()
    fun phrase(c: Context): String = runCatching { Sealed.openText(p(c).getString("phrase", "").orEmpty()) }.getOrDefault("")
    fun setPhrase(c: Context, s: String) = p(c).edit().putString("phrase", Sealed.sealText(s)).apply()
    fun dir(c: Context) = File(c.getExternalFilesDir(null) ?: c.filesDir, "backups").apply { mkdirs() }
    fun files(c: Context) = dir(c).listFiles().orEmpty().filter { it.isFile }.sortedByDescending { it.lastModified() }

    suspend fun run(c: Context): File {
        val ph = phrase(c)
        require(ph.length >= 6) { "Set a backup phrase (6+ characters) first." }
        val f = File(dir(c), "atlas-backup-${LocalDate.now()}.atlasbak")
        Backup.export(c, Uri.fromFile(f), includeVault = false, recoveryPhrase = ph.toCharArray())
        files(c).drop(4).forEach { it.delete() }
        c.getSharedPreferences("backup", 0).edit().putLong("last", System.currentTimeMillis()).apply()
        return f
    }

    /** From the background worker: once a week, if switched on. */
    suspend fun tick(c: Context) {
        if (!on(c)) return
        val last = files(c).firstOrNull()?.lastModified() ?: 0L
        if (System.currentTimeMillis() - last > 7 * 86_400_000L) runCatching { run(c) }
    }
}

@Composable
fun AutoBackupScreen() {
    val c = LocalContext.current; val scope = com.nizam.app.ui.design.rememberSafeScope()
    var on by remember { mutableStateOf(AutoBackup.on(c)) }
    var phrase by remember { mutableStateOf(AutoBackup.phrase(c)) }
    var status by remember { mutableStateOf("") }
    var files by remember { mutableStateOf(AutoBackup.files(c)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Atlas saves an encrypted copy of your data every week. Keep your phrase safe — without it a backup can't be opened. Vault files are not included.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(phrase, { phrase = it; AutoBackup.setPhrase(c, it) }, label = { Text("Backup phrase") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Weekly automatic backup", Modifier.weight(1f)); Switch(on, { on = it; AutoBackup.setOn(c, it) }) }
        TextButton(enabled = phrase.length >= 6, onClick = { scope.launch { status = runCatching { AutoBackup.run(c); files = AutoBackup.files(c); "Backed up." }.getOrElse { "Didn't work: ${it.message}" } } }) { Text("Back up now") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        files.forEach { f -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${f.name} · ${f.length() / 1024} KB", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { Share.file(c, f, "application/octet-stream") }) { Text("Copy out") } } }
        Text("Tip: use Copy out to put a backup in Drive or email, so it survives losing the phone.", style = MaterialTheme.typography.bodySmall)
    }
}
