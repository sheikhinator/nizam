package com.nizam.app.feature.expertise

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nizam.app.AppContainer
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.StatTile
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/** Expertise Studio: write a kind of expertise of your own; Atlas can draft its instructions for you. */
@Composable
fun ExpertiseStudio(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var editing by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var prompt by remember { mutableStateOf("") }
    var heavy by remember { mutableStateOf(false) }
    var drafting by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    val mine = remember(version) { Expertise.custom(ctx) }

    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        OutlinedTextField(role, { role = it }, Modifier.fillMaxWidth(), label = { Text("What it's for") }, minLines = 2)
        OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), label = { Text("How Atlas should think") }, minLines = 6)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Needs deep thinking", style = MaterialTheme.typography.titleSmall)
                Text("Uses the strongest models first", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(heavy, { heavy = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            OutlinedButton(onClick = {
                drafting = true
                scope.launch {
                    runCatching {
                        Ai.generate(container.settingsRepository,
                            "Expertise name: $name\nWhat it's for: $role",
                            system = "${Expertise.MARK}\nWrite the instructions for one kind of expertise Atlas can think with. Second person, 120 to 200 words: " +
                                "the role, four or five concrete capabilities, how to structure answers, when to flag risk, and to reply in the user's language. " +
                                "Atlas stays Atlas: never give it another name. Output only the instructions.",
                            maxOutputTokens = 600)
                    }.onSuccess { prompt = it.trim() }.onFailure { Feedback.show(it.message ?: "Couldn't draft it") }
                    drafting = false
                }
            }, enabled = !drafting && name.isNotBlank() && role.isNotBlank()) { Text(if (drafting) "Writing…" else "Write with Atlas") }
            Button(onClick = {
                Expertise.saveCustom(ctx, editing, name, role, prompt, heavy)
                Feedback.show("Saved. Atlas can now think as $name.")
                editing = null; name = ""; role = ""; prompt = ""; heavy = false; version++
            }, enabled = name.isNotBlank() && role.isNotBlank()) { Text(if (editing == null) "Save" else "Update") }
        }
        if (mine.isNotEmpty()) {
            Spacer(Modifier.height(Space.m))
            Text("Yours", style = MaterialTheme.typography.titleMedium)
            mine.forEach { e ->
                Group {
                    Text(e.name, style = MaterialTheme.typography.titleSmall)
                    Text(e.role, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(top = Space.s)) {
                        OutlinedButton(onClick = { editing = e.id; name = e.name; role = e.role; prompt = e.prompt.orEmpty(); heavy = e.heavy }) { Text("Edit") }
                        OutlinedButton(onClick = {
                            Expertise.deleteCustom(ctx, e.id); version++
                            Feedback.show("Removed ${e.name}", "Undo") { Expertise.saveCustom(ctx, e.id, e.name, e.role, e.prompt.orEmpty(), e.heavy); version++ }
                        }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

/** The engine room: Atlas's own machinery at a glance (absorbed from LYCHO's Master Panel). */
@Composable
fun EngineRoom() {
    val ctx = LocalContext.current
    val calls by EngineLog.calls.collectAsState()
    val today = remember(calls.size) { EngineLog.today(ctx) }
    val top = remember(calls.size) { Expertise.topUsed(ctx) }
    val fmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val total = today.sumOf { it.calls }
    val ok = today.sumOf { it.ok }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            StatTile("Calls today", "$total", Modifier.weight(1f))
            StatTile("Answered", if (total == 0) "—" else "${ok * 100 / total}%", Modifier.weight(1f))
            StatTile("Expertise", "${Expertise.all(ctx).size}", Modifier.weight(1f))
        }
        if (today.isNotEmpty()) {
            Text("By provider today", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Space.m))
            today.forEach { d ->
                Text("${d.provider} · ${d.calls} calls · ${if (d.calls > 0) d.ok * 100 / d.calls else 0}% · ${if (d.avgMs > 0) "%.1f s".format(d.avgMs / 1000.0) else "—"}",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (top.isNotEmpty()) {
            Text("Expertise Atlas uses most", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Space.m))
            top.forEach { (e, n) -> Text("${e.name} · $n", style = MaterialTheme.typography.bodyMedium) }
        }
        Text("Recent model calls", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Space.m))
        if (calls.isEmpty()) Text("No calls since Atlas opened.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        calls.take(40).forEach { cl ->
            Text("${fmt.format(Date(cl.at))}  ${if (cl.ok) "✓" else "✕"}  ${cl.provider} · ${cl.model.substringAfterLast('/')} · ${"%.1f s".format(cl.ms / 1000.0)}" +
                (cl.error?.let { "\n   $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = if (cl.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
        }
    }
}
