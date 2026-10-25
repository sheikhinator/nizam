package com.nizam.app.ui.design

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.mimeTypes
import androidx.compose.ui.draganddrop.toAndroidDragEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Drag anything onto Atlas. In split screen (or on a tablet), drag text from any app (a WhatsApp
 * message, a web page, an email) and drop it anywhere on Atlas: it can become tasks and reminders,
 * a note, or a question.
 */
object Dropped { var text by mutableStateOf<String?>(null) }

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DropZone() {
    val target = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val clip = event.toAndroidDragEvent().clipData ?: return false
                val sb = StringBuilder()
                for (i in 0 until clip.itemCount) clip.getItemAt(i).text?.let { sb.append(it).append('\n') }
                val t = sb.toString().trim()
                if (t.isBlank()) return false
                Dropped.text = t
                return true
            }
        }
    }
    Box(Modifier.fillMaxSize().dragAndDropTarget(shouldStartDragAndDrop = { e -> e.mimeTypes().any { it.startsWith("text/") } }, target = target))
    Dropped.text?.let { DroppedSheet(it) { Dropped.text = null } }
}

@Composable
private fun DroppedSheet(text: String, close: () -> Unit) {
    val c = LocalContext.current
    val container = remember { (c.applicationContext as? com.nizam.app.NizamApplication)?.container }
    val scope = rememberSafeScope()
    var mode by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text("Dropped into Atlas") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {
                Text(text.take(400) + if (text.length > 400) "…" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (container != null) {
                    if (mode == "act") com.nizam.app.feature.nx.ActOnIt(container, "Dropped text", text, Modifier.padding(top = 8.dp))
                    else TextButton(onClick = { mode = "act" }) { Text("⚡ Turn into tasks, reminders, expenses") }
                    TextButton(onClick = {
                        scope.launch { runCatching { container.noteRepository.createFull(text.lines().first().take(60), text) }
                            com.nizam.app.ui.components.Feedback.show("Saved as a note"); close() }
                    }) { Text("✎ Save as a note") }
                    var answer by remember { mutableStateOf<String?>(null) }
                    TextButton(onClick = {
                        answer = "…"
                        scope.launch { answer = runCatching { com.nizam.app.net.Ai.generate(container.settingsRepository,
                            "The user dropped this into Atlas. Explain what it is, what matters in it, and what they might want to do about it. Brief.\n\n${text.take(6000)}",
                            system = "You are Atlas, a practical assistant.", maxOutputTokens = 600) }.getOrElse { it.message ?: "Couldn't answer" } }
                    }) { Text("✦ What is this? What should I do?") }
                    answer?.let { com.nizam.app.feature.nx.Md(it) }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } })
}
