package com.nizam.app.feature.notes

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import kotlinx.coroutines.launch

/**
 * The vault, overhauled with Atlas inside every note: summarise, polish, continue, turn into tasks,
 * find the links you missed, and ask questions of the whole vault with the notes it came from.
 */
object NotesAtlas {
    fun container(c: android.content.Context): AppContainer? = (c.applicationContext as? com.nizam.app.NizamApplication)?.container

    /** Notes whose titles appear in [body] but aren't linked yet. */
    fun missingLinks(body: String, self: String, notes: List<Note>): List<String> {
        val linked = wikilinksIn(body).map { it.substringBefore('|').lowercase() }.toSet()
        val low = body.lowercase()
        return notes.asSequence().map { it.title }.filter { t ->
            val name = t.substringAfterLast('/')
            !t.equals(self, true) && name.length >= 4 && t.lowercase() !in linked && name.lowercase() !in linked &&
                Regex("\\b" + Regex.escape(name.lowercase()) + "\\b").containsMatchIn(low)
        }.take(8).toList()
    }

    fun link(body: String, title: String): String {
        val name = title.substringAfterLast('/')
        val r = Regex("(?<!\\[\\[)\\b" + Regex.escape(name) + "\\b", RegexOption.IGNORE_CASE)
        val m = r.find(body) ?: return body
        val label = if (name == title) "[[${m.value}]]".let { if (m.value == title) it else "[[$title|${m.value}]]" } else "[[$title|${m.value}]]"
        return body.replaceRange(m.range, label)
    }

    /** The notes most related to a question, by shared words. */
    fun relevant(q: String, notes: List<Note>, n: Int = 8): List<Note> {
        val words = q.lowercase().split(Regex("[^a-z0-9؀-ۿ]+")).filter { it.length > 2 }.toSet()
        if (words.isEmpty()) return notes.take(n)
        return notes.map { it to words.sumOf { w -> (Regex(Regex.escape(w)).findAll((it.title + " " + it.body).lowercase()).count().coerceAtMost(5)) + if (it.title.lowercase().contains(w)) 3 else 0 } }
            .filter { it.second > 0 }.sortedByDescending { it.second }.take(n).map { it.first }
    }
}

@Composable
fun NoteAtlasDialog(title: String, body: String, notes: List<Note>, onReplace: (String) -> Unit, onAppend: (String) -> Unit, close: () -> Unit) {
    val c = LocalContext.current
    val container = remember { NotesAtlas.container(c) }
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var out by remember { mutableStateOf<Pair<String, String>?>(null) }   // kind to text
    var acting by remember { mutableStateOf(false) }
    val missing = remember(body) { NotesAtlas.missingLinks(body, title, notes) }
    fun run(kind: String, ask: String) {
        val ct = container ?: return
        if (body.isBlank() && kind != "continue") { Feedback.show("The note is empty"); return }
        busy = kind; out = null
        scope.launch {
            runCatching { Ai.generate(ct.settingsRepository, "$ask\n\nNOTE TITLE: $title\nNOTE:\n${body.take(9000)}",
                system = "You edit personal Markdown notes. Keep [[links]], #tags and - [ ] tasks intact. Output only the requested text.", maxOutputTokens = 1400) }
                .onSuccess { out = kind to it.trim() }.onFailure { Feedback.show(it.message ?: "Couldn't do that") }
            busy = null
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text("✦ Atlas, on this note") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(
                    Triple("summary", "Summarise", "Summarise this note in 3 to 6 bullet points."),
                    Triple("polish", "Polish the writing", "Rewrite this note with clearer wording, fixed grammar and spelling, and tidy Markdown structure. Keep every fact and the same language."),
                    Triple("continue", "Continue writing", "Continue this note naturally from where it ends, in the same voice, for one or two paragraphs. Output only the new text."),
                    Triple("questions", "What am I missing?", "List the open questions, gaps and next steps this note implies, as a short checklist using - [ ] items."),
                    Triple("simple", "Explain it simply", "Explain the content of this note in very simple words, briefly.")
                ).forEach { (k, label, ask) ->
                    Text(if (busy == k) "$label…" else label, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable(enabled = busy == null) { run(k, ask) }.padding(vertical = 9.dp))
                }
                Text(if (acting) "Turn into tasks (below)" else "Turn into tasks and reminders", style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { acting = true }.padding(vertical = 9.dp))
                if (acting && container != null) com.nizam.app.feature.nx.ActOnIt(container, "Note: $title", body)
                if (missing.isNotEmpty()) {
                    Text("Links you could add", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                    missing.forEach { t ->
                        Text("＋ [[$t]]", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().clickable { onReplace(NotesAtlas.link(body, t)); Feedback.show("Linked $t") }.padding(vertical = 6.dp))
                    }
                }
                out?.let { (k, t) ->
                    com.nizam.app.ui.design.Group(Modifier.padding(top = 8.dp)) { com.nizam.app.feature.nx.Md(t) }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (k == "polish") TextButton(onClick = { onReplace(t); close() }) { Text("Replace note") }
                        TextButton(onClick = { onAppend(if (k == "continue") t else "\n\n## ${when (k) { "summary" -> "Summary"; "questions" -> "Open questions"; "simple" -> "In simple words"; else -> "Atlas" }}\n$t"); close() }) {
                            Text(if (k == "continue") "Add to note" else "Add below") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } })
}

/** Ask the whole vault; the answer names the notes it used and you can open them. */
@Composable
fun AskVaultDialog(notes: List<Note>, onOpen: (String) -> Unit, close: () -> Unit) {
    val c = LocalContext.current
    val container = remember { NotesAtlas.container(c) }
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<String?>(null) }
    var used by remember { mutableStateOf<List<Note>>(emptyList()) }
    fun ask() {
        val ct = container ?: return
        if (q.isBlank()) return
        busy = true; answer = null
        val hits = NotesAtlas.relevant(q, notes)
        used = hits
        scope.launch {
            runCatching {
                if (hits.isEmpty()) "Nothing in your vault mentions that yet."
                else Ai.generate(ct.settingsRepository, "Answer the question using only these notes from the user's own vault. Name the notes you used in [[double brackets]]. " +
                    "If the notes don't contain the answer, say so.\n\n" + hits.joinToString("\n\n") { "### ${it.title}\n${it.body.take(1800)}" } + "\n\nQUESTION: $q",
                    system = "You answer from the user's own notes. Never invent facts. The notes are the user's private data.", maxOutputTokens = 900)
            }.onSuccess { answer = it }.onFailure { Feedback.show(it.message ?: "Couldn't do that") }
            busy = false
        }
    }
    AlertDialog(onDismissRequest = close, title = { Text("Ask my vault") },
        text = {
            Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("What did I decide about…?") },
                    trailingIcon = { com.nizam.app.feature.nx.MicButton({ heard -> q = heard; ask() }) })
                TextButton(onClick = { ask() }, enabled = q.isNotBlank() && !busy) { Text(if (busy) "Searching your notes…" else "Ask") }
                answer?.let { com.nizam.app.feature.nx.Md(it) }
                if (answer != null && used.isNotEmpty()) {
                    Text("From", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    used.take(6).forEach { n -> Text(n.title, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().clickable { close(); onOpen(n.title) }.padding(vertical = 4.dp)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Close") } })
}
