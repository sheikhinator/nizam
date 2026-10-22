package com.nizam.app.feature.backup

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import com.nizam.app.ui.theme.DisplayFamily
import kotlinx.coroutines.launch

data class SharedPayload(val text: String?, val imageUri: Uri?)

fun sharedPayloadFrom(intent: Intent?): SharedPayload? {
    if (intent?.action == Intent.ACTION_PROCESS_TEXT) {
        // text selected in any app, sent via the "Atlas" item in the selection menu
        val picked = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        return if (picked.isNullOrBlank()) null else SharedPayload(picked, null)
    }
    if (intent?.action != Intent.ACTION_SEND) return null
    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
    @Suppress("DEPRECATION")
    val image = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
    if (text.isNullOrBlank() && image == null) return null
    return SharedPayload(text, image)
}

/** Where shared text and images land. Capture shouldn't require opening the app and navigating. */
@Composable
fun ShareInboxScreen(container: AppContainer, payload: SharedPayload, onDone: () -> Unit) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var title by remember { mutableStateOf(payload.text?.lines()?.firstOrNull()?.take(60).orEmpty()) }
    var body by remember { mutableStateOf(payload.text.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        ModuleTitle("Shared to Atlas", "Where should this go?")

        OutlinedTextField(
            value = title, onValueChange = { title = it },
            label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = body, onValueChange = { body = it },
            label = { Text("Content") }, modifier = Modifier.fillMaxWidth().height(160.dp)
        )

        Spacer(Modifier.height(14.dp))

        if (payload.imageUri != null) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "An image was shared. Send it to the Vault to store it encrypted — " +
                        "you'll need your passphrase.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(14.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
        }

        listOf(
            "💡  Ideas" to "ideas",
            "🔤  Vocabulary" to "vocab",
            "🎬  Watchlist" to "watchlist",
            "📍  Places" to "places"
        ).chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (label, key) ->
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                container.dynamicRepository.addEntry(
                                    key, title.ifBlank { "Shared" }, mapOf("detail" to body)
                                )
                                status = "Saved to $label"
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        val link = Regex("https?://\\S+").find(body)?.value
        if (link != null) {
            Button(onClick = {
                scope.launch {
                    status = runCatching { com.nizam.app.feature.memory.ReadLater.add(context, link); "Saved to Read later" }
                        .getOrElse { "Couldn't fetch it: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("📖  Read later") }
            Spacer(Modifier.height(8.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.launch {
                    container.noteRepository.createFull(title.ifBlank { "Shared note" }, body)
                    status = "Saved to Notes"
                }
            }, modifier = Modifier.weight(1f)) { Text("🗒️  Notes") }
            Button(onClick = {
                scope.launch {
                    container.agentMemory.remember("Shared with Atlas: $body", "stated")
                    status = "Handed to the Curator"
                }
            }, modifier = Modifier.weight(1f)) { Text("✦  Curator") }
        }

        status?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(16.dp))
        OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Open Atlas")
        }
    }
}
