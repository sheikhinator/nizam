package com.nizam.app.feature.companion

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.feature.next.JsonStore
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

/** User-authored moments, kept separate from the pet's automatically learned chat notes. */
object CompanionMemories {
    private fun store(c: Context) = JsonStore(c, "pet_memory_capsules", sealed = true)

    fun forPet(c: Context): List<String> =
        store(c).all().sortedBy { it.optLong("at") }.takeLast(12).map {
            val title = it.optString("title").trim()
            val body = it.optString("body").trim()
            if (title.isBlank()) body else "$title: $body"
        }.filter { it.isNotBlank() }

    fun forAi(c: Context): String =
        forPet(c).joinToString("\n") { "• $it" }
            .ifBlank { "No user-pinned moments." }
}

@Composable
fun CompanionMemoriesScreen() {
    val c = LocalContext.current
    val store = remember(c) { JsonStore(c, "pet_memory_capsules", sealed = true) }
    var memories by remember { mutableStateOf(store.all().sortedByDescending { it.optLong("at") }) }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }

    fun refresh() {
        memories = store.all().sortedByDescending { it.optLong("at") }
    }
    fun clearEditor() {
        title = ""
        body = ""
        editingId = ""
    }
    fun saveMemory() {
        if (title.isBlank() || body.isBlank()) return
        if (editingId.isBlank()) {
            store.add(JSONObject().put("title", title.trim()).put("body", body.trim().take(800)).put("at", System.currentTimeMillis()))
        } else {
            store.update(editingId) {
                it.put("title", title.trim())
                it.put("body", body.trim().take(800))
                it.put("at", System.currentTimeMillis())
            }
        }
        clearEditor()
        refresh()
    }

    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Pet memory capsules", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(
            "Pin only the moments you want ${Companion.load(c).name} to refer back to. These are separate from chat notes and stored encrypted on this device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(if (editingId.isBlank()) "Pin a moment" else "Edit pinned moment", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(title, { title = it.take(80) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Short title") }, singleLine = true)
            OutlinedTextField(body, { body = it.take(800) }, modifier = Modifier.fillMaxWidth(),
                label = { Text("What should your pet remember?") }, minLines = 3)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::saveMemory, enabled = title.isNotBlank() && body.isNotBlank()) {
                    Text(if (editingId.isBlank()) "Pin moment" else "Save changes")
                }
                if (editingId.isNotBlank()) OutlinedButton(onClick = ::clearEditor) { Text("Cancel") }
            }
        }
        Text("Pinned moments · ${memories.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (memories.isEmpty()) {
            Text("Nothing pinned yet. Your pet won’t invent a capsule on its own.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        memories.forEach { memory ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp)
            ) {
                Text(memory.optString("title"), fontWeight = FontWeight.Bold)
                Text(memory.optString("body"), modifier = Modifier.padding(top = 4.dp))
                Text(
                    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(memory.optLong("at"))),
                    modifier = Modifier.padding(top = 6.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 4.dp)) {
                    Text("Edit", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable {
                        editingId = memory.optString("id")
                        title = memory.optString("title")
                        body = memory.optString("body")
                    }.padding(vertical = 6.dp))
                    Text("Delete", color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable {
                        deleting = memory
                    }.padding(vertical = 6.dp))
                }
            }
        }
    }

    deleting?.let { memory ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete this capsule?") },
            text = { Text("“${memory.optString("title")}” will no longer be available to your pet.") },
            confirmButton = {
                TextButton(onClick = {
                    store.remove(memory.optString("id"))
                    deleting = null
                    if (editingId == memory.optString("id")) clearEditor()
                    refresh()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Keep it") } }
        )
    }
}