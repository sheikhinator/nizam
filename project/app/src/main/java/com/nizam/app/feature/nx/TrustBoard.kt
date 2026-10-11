package com.nizam.app.feature.nx

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PrivacyTip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.feature.brain2.PersonalModel
import com.nizam.app.ui.design.Hub
import java.io.File

/** One kept item on the phone: a log, a list or a store, with the files that hold it. */
internal data class TrustRow(val label: String, val size: String, val note: String, val files: List<File>) {
    fun delete() = files.forEach { runCatching { it.delete() } }
}

private val PROTECTED = listOf("key", "setting", "secret", "token", "vault_master", "prefs")

private fun pretty(base: String): Pair<String, String> {
    if (base.startsWith("nxlog_")) {
        val spec = Nx.find(base.removePrefix("nxlog_"))
        if (spec != null) return spec.title to (if ((spec as? LogSpec)?.sealed == true) "private log" else "log")
    }
    val spec = Nx.find(base.removePrefix("nx_"))
    if (spec != null) return spec.title to "history"
    val clean = base.removePrefix("nx_").removePrefix("nxlog_").replace('_', ' ').trim()
    return clean.replaceFirstChar { it.uppercase() } to "stored list"
}

private fun kb(bytes: Long) = if (bytes < 1024) "$bytes B" else "%.1f KB".format(bytes / 1024.0)

internal fun trustRows(c: Context): List<TrustRow> {
    val all = (c.filesDir.listFiles() ?: emptyArray<File>()).filter { it.isFile && (it.name.endsWith(".json") || it.name.endsWith(".json.last-good")) }
    return all.groupBy { it.name.removeSuffix(".last-good").removeSuffix(".json") }
        .filterKeys { base -> PROTECTED.none { base.contains(it, ignoreCase = true) } }
        .map { (base, files) ->
            val (label, note) = pretty(base)
            TrustRow(label, kb(files.sumOf { it.length() }), note, files)
        }
        .filter { r -> r.files.any { it.length() > 4 } }
        .sortedBy { it.label.lowercase() }
}

@Composable
internal fun TrustBoard() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val rows = remember(tick) { trustRows(ctx) }
    val learned = remember(tick) { PersonalModel.facts(ctx).size + PersonalModel.lessons(ctx).size }
    var ask by remember { mutableStateOf<TrustRow?>(null) }
    var wipe by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Everything below is kept only on this phone. Delete any item and it is gone for good.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("What Atlas has learned about you", fontWeight = FontWeight.SemiBold)
                Text("$learned things it remembers to help you", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { wipe = true }, enabled = learned > 0) { Text("Forget all") }
        }
        if (rows.isEmpty()) Text("Nothing is stored yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.label, fontWeight = FontWeight.SemiBold)
                    Text("${r.note} · ${r.size}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { ask = r }) { Text("Delete") }
            }
        }
        Text("Passwords, keys and settings are not listed here so they cannot be wiped by accident.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    ask?.let { r ->
        AlertDialog(onDismissRequest = { ask = null },
            title = { Text("Delete ${r.label}?") },
            text = { Text("This removes it from this phone and cannot be undone.") },
            confirmButton = { TextButton(onClick = { r.delete(); ask = null; tick++ }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { ask = null }) { Text("Keep") } })
    }
    if (wipe) {
        AlertDialog(onDismissRequest = { wipe = false },
            title = { Text("Forget everything it learned?") },
            text = { Text("Atlas will stop using the facts and lessons it saved about you. Your logs stay.") },
            confirmButton = { TextButton(onClick = { PersonalModel.forgetAll(ctx); wipe = false; tick++ }) { Text("Forget") } },
            dismissButton = { TextButton(onClick = { wipe = false }) { Text("Keep") } })
    }
}

object Stage3 {
    val all: List<Spec> = listOf(
        ScreenSpec("trustboard", "Trust Dashboard", "See what Atlas keeps about you and delete any of it", Icons.Outlined.PrivacyTip, Hub.Tools,
            "privacy data delete forget what atlas knows stored receipt") { _, _ -> TrustBoard() },
    ) + Mega18.all
}
