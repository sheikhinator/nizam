package com.nizam.app.feature.nx

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.nizam.app.AppContainer
import com.nizam.app.feature.brain2.Calc
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.notes.MarkdownView
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.EmptySpec
import com.nizam.app.ui.design.EmptyState
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.pressable
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class K { TEXT, LONG, NUMBER, DATE, CHOICE }

/** One input on a tool: a line, a paragraph, a number (lakh/crore understood), a date or a choice. */
data class F(val key: String, val label: String, val kind: K = K.TEXT, val hint: String = "", val options: List<String> = emptyList(), val default: String = "", val optional: Boolean = false)

sealed class Spec(val id: String, val title: String, val blurb: String, val icon: ImageVector, val hub: Hub, val words: String)

/** Ask-and-answer tool: fill the fields, get one written result (saved, shareable, readable aloud, printable). */
class AiSpec(id: String, title: String, blurb: String, icon: ImageVector, hub: Hub, words: String = "",
             val fields: List<F>, val system: String, val web: Boolean = false, val button: String = "Go", val tokens: Int = 1400,
             val prompt: (Map<String, String>) -> String) : Spec(id, title, blurb, icon, hub, words)

/** A conversation with a character or expert. [setup] fields shape who they are. */
class ChatSpec(id: String, title: String, blurb: String, icon: ImageVector, hub: Hub, words: String = "",
               val setup: List<F> = emptyList(), val web: Boolean = false, val opener: String = "", val persistent: Boolean = false,
               val persona: (Map<String, String>) -> String) : Spec(id, title, blurb, icon, hub, words)

/** A ledger or log kept on the phone. [summary] is the line at the top; [line] titles each entry. */
class LogSpec(id: String, title: String, blurb: String, icon: ImageVector, hub: Hub, words: String = "",
              val fields: List<F>, val sealed: Boolean = false,
              val line: (JSONObject) -> Pair<String, String> = { o -> o.optString("_0") to "" },
              val summary: (List<JSONObject>) -> List<String> = { emptyList() }) : Spec(id, title, blurb, icon, hub, words)

/** A calculator: inputs become variables, each output is an expression worked out on the phone. */
class CalcSpec(id: String, title: String, blurb: String, icon: ImageVector, hub: Hub, words: String = "",
               val inputs: List<F>, val outputs: List<Pair<String, String>>, val note: String = "") : Spec(id, title, blurb, icon, hub, words)

/** Useful official links in one place. */
class LinkSpec(id: String, title: String, blurb: String, icon: ImageVector, hub: Hub, words: String = "",
               val links: List<Triple<String, String, String>>) : Spec(id, title, blurb, icon, hub, words)

/** A hand-built screen. */
class ScreenSpec(id: String, title: String, blurb: String, icon: ImageVector, hub: Hub, words: String = "",
                 val content: @Composable (AppContainer, (String) -> Unit) -> Unit) : Spec(id, title, blurb, icon, hub, words)

object Share {
    fun text(c: Context, text: String, subject: String = "Atlas") {
        c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_SUBJECT, subject)
            .putExtra(Intent.EXTRA_TEXT, text), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun file(c: Context, f: File, mime: String) {
        val uri = FileProvider.getUriForFile(c, c.packageName + ".fileprovider", f)
        c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    fun out(c: Context, name: String) = File(c.cacheDir, "share").apply { mkdirs() }.let { File(it, name) }
    fun open(c: Context, url: String) = runCatching {
        c.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Plain, clean A4 PDFs from text: letters, CVs, invoices, reports. */
object Pdf {
    fun fromText(c: Context, title: String, body: String): File {
        val doc = PdfDocument()
        val w = 595; val h = 842; val m = 50f
        val head = Paint().apply { textSize = 18f; isFakeBoldText = true; isAntiAlias = true }
        val p = Paint().apply { textSize = 11f; isAntiAlias = true }
        val bold = Paint(p).apply { isFakeBoldText = true }
        var page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
        var y = m + 10
        var n = 1
        fun newPage() { doc.finishPage(page); n++; page = doc.startPage(PdfDocument.PageInfo.Builder(w, h, n).create()); y = m }
        if (title.isNotBlank()) { page.canvas.drawText(title, m, y, head); y += 30 }
        body.lines().forEach { raw ->
            val isHead = raw.startsWith("#")
            val line = raw.trimStart('#', ' ').replace("**", "").replace(Regex("^[-*] "), "• ")
            val paint = if (isHead) bold else p
            if (line.isBlank()) { y += 8; return@forEach }
            val words = line.split(' ')
            var cur = StringBuilder()
            for (word in words) {
                val tryLine = if (cur.isEmpty()) word else "$cur $word"
                if (paint.measureText(tryLine) > w - 2 * m && cur.isNotEmpty()) {
                    if (y > h - m) newPage()
                    page.canvas.drawText(cur.toString(), m, y, paint); y += 16; cur = StringBuilder(word)
                } else cur = StringBuilder(tryLine)
            }
            if (cur.isNotEmpty()) { if (y > h - m) newPage(); page.canvas.drawText(cur.toString(), m, y, paint); y += if (isHead) 20 else 16 }
        }
        doc.finishPage(page)
        val f = Share.out(c, title.ifBlank { "Atlas" }.replace(Regex("[^A-Za-z0-9 _-]"), "").take(40).trim().ifBlank { "Atlas" } + ".pdf")
        f.outputStream().use { doc.writeTo(it) }
        doc.close()
        return f
    }
}

@Composable
fun FieldInput(f: F, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    when (f.kind) {
        K.CHOICE -> Column(modifier) {
            Text(f.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(top = 4.dp)) {
                items(f.options.size) { i -> val o = f.options[i]; Capsule(o, value == o) { onChange(o) } }
            }
        }
        else -> OutlinedTextField(value, onChange, modifier.fillMaxWidth().let { if (f.kind == K.LONG) it.heightIn(min = 110.dp) else it },
            label = { Text(f.label + if (f.optional) " (optional)" else "") },
            placeholder = if (f.hint.isBlank()) null else { { Text(f.hint) } },
            singleLine = f.kind != K.LONG,
            keyboardOptions = if (f.kind == K.NUMBER) KeyboardOptions(keyboardType = KeyboardType.Text) else KeyboardOptions.Default)
    }
}

@Composable
private fun rememberSpeaker(): com.nizam.app.feature.voice.Speaker {
    val ctx = LocalContext.current
    val s = remember { com.nizam.app.feature.voice.Speaker(ctx) }
    DisposableEffect(Unit) { onDispose { s.release() } }
    return s
}

@Composable
fun ResultActions(title: String, raw: String) {
    val text = com.nizam.app.ui.components.RichFormat.plain(raw)
    val ctx = LocalContext.current
    val speaker = rememberSpeaker()
    Row(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Capsule("Copy", false) {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText(title, text))
            Feedback.show("Copied")
        }
        Capsule("Share", false) { Share.text(ctx, text, title) }
        Capsule("Listen", false) { speaker.speak(text.replace(Regex("[#*_`>]"), "")) }
        Capsule("PDF", false) { runCatching { Share.file(ctx, Pdf.fromText(ctx, title, text), "application/pdf") }.onFailure { Feedback.show("Couldn't make the PDF") } }
    }
}

@Composable
fun Md(text: String) {
    val ctx = LocalContext.current
    SelectionContainer { com.nizam.app.ui.components.RichText(text, onUrl = { Share.open(ctx, it) }) }
}

@Composable
fun AiToolScreen(spec: AiSpec, container: AppContainer) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val learnedPrefs = remember { ctx.getSharedPreferences("nxlearn_${spec.id}", 0) }
    // the tool remembers your last choices and numbers, so the same question is faster next time
    val values = remember { mutableStateMapOf<String, String>().apply {
        spec.fields.forEach { f -> put(f.key, if (f.kind == K.CHOICE || f.kind == K.NUMBER) learnedPrefs.getString(f.key, null) ?: Mind.shared(ctx, f) ?: f.default
            else Mind.shared(ctx, f) ?: f.default) } } }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    val store = remember { JsonStore(ctx, "nx_${spec.id}") }
    var history by remember { mutableStateOf(store.all().reversed()) }
    val ready = spec.fields.all { it.optional || values[it.key].orEmpty().isNotBlank() }
    AtlasScreen(title = spec.title, subtitle = spec.blurb, hub = spec.hub) {
        item("form") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                spec.fields.forEach { f -> FieldInput(f, values[f.key].orEmpty(), { values[f.key] = it }, Modifier.padding(bottom = Space.s)) }
                Button(onClick = {
                    busy = true; result = null
                    val snapshot = values.toMap()
                    spec.fields.filter { it.kind == K.CHOICE || it.kind == K.NUMBER }.forEach { learnedPrefs.edit().putString(it.key, snapshot[it.key].orEmpty()).apply() }
                    spec.fields.forEach { Mind.remember(ctx, it, snapshot[it.key].orEmpty()) }
                    scope.launch {
                        runCatching {
                            if (snapshot["use"] == LifeContext.YES) Roads.warm(container)
                            Ai.generate(container.settingsRepository, spec.prompt(snapshot), system = spec.system, maxOutputTokens = spec.tokens, useWeb = spec.web)
                        }
                            .onSuccess { r ->
                                result = r
                                store.add(JSONObject().put("in", snapshot.values.filter { it.isNotBlank() }.joinToString(" · ").take(120)).put("out", r).put("t", System.currentTimeMillis()))
                                store.all().let { all -> if (all.size > 25) store.save(all.takeLast(25)) }
                                history = store.all().reversed()
                            }
                            .onFailure { Feedback.show(it.message ?: "Couldn't do that") }
                        busy = false
                    }
                }, enabled = ready && !busy) { Text(if (busy) "Working…" else spec.button) }
            }
        }
        result?.let { r ->
            item("out") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) { Md(r) } }
            item("act") { ResultActions(spec.title, r) }
            item("thumbs") {
                Row(Modifier.padding(horizontal = Space.page), verticalAlignment = Alignment.CenterVertically) {
                    Text("Was this helpful?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    androidx.compose.material3.TextButton(onClick = { Mind.rate(ctx, spec.id, spec.title, true); Feedback.show("Thanks, noted") }) { Text("👍") }
                    androidx.compose.material3.TextButton(onClick = { Mind.rate(ctx, spec.id, spec.title, false); Feedback.show("Noted, I will aim to be more specific") }) { Text("👎") }
                }
            }
            item("refine") {
                Row(Modifier.padding(horizontal = Space.page)) {
                    listOf("Shorter" to "Rewrite this much shorter, keeping only what matters.",
                        "Simpler" to "Rewrite this in very simple words.",
                        "Next steps" to "Turn this into a short numbered list of next steps, with the first one doable today.").forEach { (label, ask) ->
                        androidx.compose.material3.TextButton(onClick = {
                            busy = true
                            // the app learns how you like answers: after three of the same request it remembers it
                            val pp = ctx.getSharedPreferences("mind_pref", 0)
                            val n = pp.getInt(label, 0) + 1
                            pp.edit().putInt(label, n).apply()
                            if (n == 3) when (label) {
                                "Shorter" -> com.nizam.app.feature.brain2.PersonalModel.addFact(ctx, "Prefers short answers")
                                "Simpler" -> com.nizam.app.feature.brain2.PersonalModel.addFact(ctx, "Prefers simple words over technical ones")
                                else -> com.nizam.app.feature.brain2.PersonalModel.addFact(ctx, "Likes answers turned into concrete next steps")
                            }
                            scope.launch {
                                runCatching { Ai.generate(container.settingsRepository, "$ask\n\n$r", system = spec.system, maxOutputTokens = spec.tokens) }
                                    .onSuccess { result = it }.onFailure { Feedback.show(it.message ?: "Couldn't do that") }
                                busy = false
                            }
                        }, enabled = !busy) { Text(label) }
                    }
                }
            }
        }
        if (history.isNotEmpty()) item("hh") { SectionHeader("Earlier") }
        history.forEach { h ->
            item(h.optString("id")) {
                ListRow(h.optString("in").ifBlank { spec.title }, supporting = h.optString("out").replace(Regex("[#*_]"), "").take(90), icon = Icons.Outlined.History,
                    trailing = { Text("✕", Modifier.padding(Space.s).pressable { store.remove(h.optString("id")); history = store.all().reversed() }, color = MaterialTheme.colorScheme.onSurfaceVariant) }) {
                    result = h.optString("out")
                }
            }
        }
    }
}

@Composable
fun ChatToolScreen(spec: ChatSpec, container: AppContainer) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val storyStore = remember(spec.id) {
        if (spec.persistent) JsonStore(ctx, "nx_story_${spec.id}", sealed = true) else null
    }
    val setup = remember(spec.id) { mutableStateMapOf<String, String>().apply { spec.setup.forEach { put(it.key, it.default) } } }
    // a chat reopened from history comes back with its messages
    val restored = remember(spec.id) { if (spec.persistent) null else com.nizam.app.feature.chats.ChatHistory.takeRestore(spec.id) }
    val historySession = remember(spec.id) { restored?.first ?: System.currentTimeMillis().toString() }
    var started by remember(spec.id) { mutableStateOf(spec.setup.isEmpty() || restored != null) }
    val msgs = remember(spec.id) {
        mutableStateListOf<Pair<Boolean, String>>().apply {
            if (restored != null) addAll(restored.second)
            else if (spec.setup.isEmpty() && spec.opener.isNotBlank()) add(false to spec.opener)
        }
    }
    var input by remember(spec.id) { mutableStateOf("") }
    var busy by remember(spec.id) { mutableStateOf(false) }
    var activeStoryId by remember(spec.id) { mutableStateOf<String?>(null) }
    var deleteStoryId by remember(spec.id) { mutableStateOf<String?>(null) }
    var savedStories by remember(spec.id) {
        mutableStateOf(storyStore?.all()?.sortedByDescending { it.optLong("updatedAt") }?.take(MAX_STORY_SESSIONS).orEmpty())
    }

    fun refreshStories() {
        val store = storyStore ?: return
        val all = store.all().sortedWith(
            compareByDescending<JSONObject> { it.optLong("updatedAt") }.thenByDescending { it.optString("id") }
        )
        if (all.size > MAX_STORY_SESSIONS) {
            runCatching { store.save(all.take(MAX_STORY_SESSIONS)) }
                .onFailure { Feedback.show(it.message ?: "Couldn't trim older stories.") }
        }
        savedStories = store.all().sortedByDescending { it.optLong("updatedAt") }.take(MAX_STORY_SESSIONS)
    }

    fun storyTitle(record: JSONObject): String {
        val savedSetup = record.optJSONObject("setup")
        return record.optString("title").ifBlank {
            listOf(savedSetup?.optString("hero").orEmpty(), savedSetup?.optString("genre").orEmpty())
                .filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Untitled story" }
        }
    }

    fun persistStory(): Boolean {
        if (!spec.persistent) return true
        val store = storyStore ?: return false
        val id = activeStoryId ?: return false
        return runCatching {
            val savedMessages = JSONArray().apply {
                msgs.takeLast(MAX_STORY_MESSAGES).forEach { (me, text) ->
                    put(JSONObject().put("me", me).put("text", text))
                }
            }
            store.update(id) { record ->
                record.put("setup", JSONObject(setup.toMap()))
                    .put("messages", savedMessages)
                    .put("updatedAt", System.currentTimeMillis())
            }
            refreshStories()
        }.onFailure {
            Feedback.show(it.message ?: "Couldn't save this story on the device.")
        }.isSuccess
    }

    fun resumeStory(record: JSONObject) {
        val savedSetup = record.optJSONObject("setup") ?: JSONObject()
        setup.clear()
        spec.setup.forEach { field -> setup[field.key] = savedSetup.optString(field.key, field.default) }
        msgs.clear()
        val savedMessages = record.optJSONArray("messages") ?: JSONArray()
        val first = (savedMessages.length() - MAX_STORY_MESSAGES).coerceAtLeast(0)
        for (i in first until savedMessages.length()) {
            val message = savedMessages.optJSONObject(i) ?: continue
            msgs += message.optBoolean("me") to message.optString("text")
        }
        activeStoryId = record.optString("id").takeIf { it.isNotBlank() }
        input = ""
        started = true
    }

    fun leaveStory() {
        activeStoryId = null
        msgs.clear()
        input = ""
        busy = false
        setup.clear()
        spec.setup.forEach { setup[it.key] = it.default }
        started = false
    }

    fun send(text: String) {
        if (text.isBlank()) return
        if (spec.persistent && text.length > 4000) {
            Feedback.show("Keep each story message under 4,000 characters.")
            return
        }
        msgs += true to text
        input = ""
        if (spec.persistent && !persistStory()) {
            msgs.removeAt(msgs.lastIndex)
            input = text
            return
        }
        if (spec.persistent) while (msgs.size > MAX_STORY_MESSAGES) msgs.removeAt(0)
        busy = true
        val storyAtSend = activeStoryId
        val convo = msgs.takeLast(16).joinToString("\n") { (me, t) -> (if (me) "User: " else "You: ") + t }
        scope.launch {
            runCatching {
                Ai.generate(container.settingsRepository, "$convo\nYou:", system = spec.persona(setup.toMap()) +
                    "\nStay in role. Reply conversationally, usually under 150 words. Match the user's language (English, Urdu or Roman Urdu).",
                    maxOutputTokens = 700, useWeb = spec.web)
            }.onSuccess {
                if (!spec.persistent || activeStoryId == storyAtSend) {
                    msgs += false to it.trim().removePrefix("You:").trim()
                    // kept in the chat history drawer (stories keep their own shelf)
                    if (!spec.persistent) runCatching { com.nizam.app.feature.chats.ChatHistory.syncTool(ctx, spec.id, spec.title, historySession, msgs.toList()) }
                    if (spec.persistent && persistStory()) {
                        while (msgs.size > MAX_STORY_MESSAGES) msgs.removeAt(0)
                    }
                }
            }.onFailure {
                if (!spec.persistent || activeStoryId == storyAtSend) Feedback.show(it.message ?: "No reply")
            }
            if (!spec.persistent || activeStoryId == storyAtSend) busy = false
        }
    }

    fun beginConversation() {
        if (spec.persistent) {
            val store = storyStore ?: return
            val hero = setup["hero"].orEmpty().trim()
            val genre = setup["genre"].orEmpty().trim()
            val title = listOf(hero, genre).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "Untitled story" }
            val now = System.currentTimeMillis()
            val record = runCatching {
                store.add(JSONObject()
                    .put("title", title)
                    .put("setup", JSONObject(setup.toMap()))
                    .put("messages", JSONArray())
                    .put("createdAt", now)
                    .put("updatedAt", now))
            }.onFailure {
                Feedback.show(it.message ?: "Couldn't save a new story on this device.")
            }.getOrNull() ?: return
            activeStoryId = record.optString("id")
            refreshStories()
        }
        started = true
        if (spec.opener.isNotBlank()) {
            val firstSetupValue = spec.setup.firstOrNull()?.let { setup[it.key].orEmpty() }.orEmpty()
            send(spec.opener.replace("{0}", firstSetupValue))
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(com.nizam.app.ui.components.LocalChatAsk provides { t: String -> send(t) }) {
    AtlasScreen(title = spec.title + (spec.setup.firstOrNull()?.let { setup[it.key]?.takeIf { v -> started && v.isNotBlank() }?.let { v -> " · $v" } } ?: ""),
        subtitle = spec.blurb, hub = spec.hub, actions = {
            if (spec.persistent && started && !busy) Text("Stories", Modifier.padding(Space.s).pressable { leaveStory() },
                style = MaterialTheme.typography.labelLarge, color = spec.hub.tint())
            if (started && msgs.isNotEmpty()) Text("Share", Modifier.padding(Space.s).pressable {
                Share.text(ctx, msgs.joinToString("\n\n") { (me, t) -> (if (me) "Me: " else "${spec.title}: ") + t }, spec.title)
            }, style = MaterialTheme.typography.labelLarge)
        }) {
        if (!started) {
            item("setup") {
                Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                    spec.setup.forEach { f -> FieldInput(f, setup[f.key].orEmpty(), { setup[f.key] = it }, Modifier.padding(bottom = Space.s)) }
                    Button(onClick = { beginConversation() },
                        enabled = spec.setup.all { it.optional || setup[it.key].orEmpty().isNotBlank() }) {
                        Text(if (spec.persistent) "Start a new story" else "Begin")
                    }
                }
            }
            if (spec.persistent) {
                item("local-note") {
                    Text("Saved stories stay encrypted on this device.", Modifier.padding(horizontal = Space.page, vertical = Space.s),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                storyStore?.lastReadError?.let { error ->
                    item("story-store-error") {
                        Text("Saved stories could not be opened and have not been overwritten. $error",
                            Modifier.padding(horizontal = Space.page, vertical = Space.s), color = MaterialTheme.colorScheme.error)
                    }
                }
                if (savedStories.isNotEmpty()) {
                    item("saved-stories-heading") { SectionHeader("Continue a story") }
                    savedStories.forEach { story ->
                        val id = story.optString("id")
                        val count = story.optJSONArray("messages")?.length() ?: 0
                        val updated = story.optLong("updatedAt")
                        val whenText = if (updated > 0L) android.text.format.DateUtils.getRelativeTimeSpanString(updated).toString() else "Saved"
                        item("story-$id") {
                            ListRow(
                                storyTitle(story),
                                supporting = "$count saved messages · $whenText",
                                icon = Icons.Outlined.History,
                                trailing = {
                                    Text("Delete", Modifier.padding(Space.s).pressable { deleteStoryId = id },
                                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                                }
                            ) { resumeStory(story) }
                        }
                    }
                }
            }
        } else {
            msgs.forEachIndexed { i, (me, t) ->
                item("m$i") {
                    Box(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 4.dp), contentAlignment = if (me) Alignment.CenterEnd else Alignment.CenterStart) {
                        // your words in a bubble; Atlas's answer full width, so tables, charts and diagrams have room
                        if (me) Box(Modifier.widthIn(max = 320.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(18.dp))
                            .padding(horizontal = 14.dp, vertical = 10.dp)) { Text(t, style = MaterialTheme.typography.bodyMedium) }
                        else Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Md(t) }
                    }
                }
            }
            if (busy) item("typing") { Text("…", Modifier.padding(horizontal = Space.page), style = MaterialTheme.typography.titleLarge) }
            item("in") {
                Row(Modifier.padding(horizontal = Space.page, vertical = Space.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Say something") }, maxLines = 4)
                    Button(onClick = { send(input.trim()) }, enabled = input.isNotBlank() && !busy) { Text("Send") }
                }
            }
        }
    }
    val deletingStory = savedStories.firstOrNull { it.optString("id") == deleteStoryId }
    val store = storyStore
    if (deletingStory != null && store != null) {
        AlertDialog(
            onDismissRequest = { deleteStoryId = null },
            title = { Text("Delete this story?") },
            text = { Text("This removes the saved story and its turns from this device. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    runCatching { store.remove(deleteStoryId.orEmpty()) }
                        .onSuccess { deleteStoryId = null; refreshStories() }
                        .onFailure { Feedback.show(it.message ?: "Couldn't delete this story.") }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteStoryId = null }) { Text("Keep story") } }
        )
    }
    }
}

private const val MAX_STORY_SESSIONS = 24
private const val MAX_STORY_MESSAGES = 160

/** Reads a number the way people type it: "2.5 lakh", "45,000", "3k". */
fun num(s: String?): Double = s?.takeIf { it.isNotBlank() }?.let { runCatching { Calc.eval(it) }.getOrNull() } ?: 0.0
fun rs(v: Double) = if (com.nizam.app.feature.platform2.AmountPrivacy.hidden()) "Rs ••••" else (if (v < 0) "−Rs " else "Rs ") + "%,d".format(kotlin.math.abs(v).toLong())

@Composable
fun LogToolScreen(spec: LogSpec) {
    val ctx = LocalContext.current
    val store = remember { JsonStore(ctx, "nxlog_${spec.id}", spec.sealed) }
    var list by remember { mutableStateOf(store.all().reversed()) }
    val values = remember { mutableStateMapOf<String, String>().apply { spec.fields.forEach { put(it.key, it.default.ifBlank { if (it.kind == K.DATE) java.time.LocalDate.now().toString() else "" }) } } }
    var adding by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<String?>(null) }
    fun reset() { spec.fields.forEach { values[it.key] = it.default.ifBlank { if (it.kind == K.DATE) java.time.LocalDate.now().toString() else "" } }; editing = null }
    val summary = remember(list) { runCatching { spec.summary(list) }.getOrDefault(emptyList()) }
    AtlasScreen(title = spec.title, subtitle = spec.blurb, hub = spec.hub, actions = {
        Text(if (adding) "Close" else "Add", Modifier.padding(Space.s).pressable { adding = !adding; if (!adding) reset() }, style = MaterialTheme.typography.labelLarge, color = spec.hub.tint())
    }) {
        if (summary.isNotEmpty()) item("sum") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                summary.forEachIndexed { i, s -> Text(s, style = if (i == 0) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.padding(vertical = 2.dp)) }
            }
        }
        if (adding || list.isEmpty()) item("form") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                spec.fields.forEach { f -> FieldInput(f, values[f.key].orEmpty(), { values[f.key] = it }, Modifier.padding(bottom = Space.s)) }
                Button(onClick = {
                    val o = JSONObject(); spec.fields.forEachIndexed { i, f -> o.put(f.key, values[f.key].orEmpty().trim()); if (i == 0) o.put("_0", values[f.key].orEmpty().trim()) }
                    val id = editing
                    // notice when a new number is unusual compared with the person's own history
                    if (id == null) spec.fields.firstOrNull { it.kind == K.NUMBER }?.let { nf ->
                        val prior = list.mapNotNull { it.optString(nf.key).toDoubleOrNull() }
                        val now = o.optString(nf.key).toDoubleOrNull()
                        if (now != null && prior.size >= 4) {
                            val avg = prior.average()
                            if (avg != 0.0 && kotlin.math.abs(now - avg) / kotlin.math.abs(avg) > 0.4)
                                Feedback.show("That's ${if (now > avg) "higher" else "lower"} than your usual (about ${"%,.1f".format(avg)})")
                        }
                    }
                    if (id != null) store.update(id) { e -> o.keys().forEach { k -> e.put(k, o.get(k)) } } else store.add(o.put("t", System.currentTimeMillis()))
                    list = store.all().reversed(); reset(); adding = false
                }, enabled = spec.fields.all { it.optional || values[it.key].orEmpty().isNotBlank() }) { Text(if (editing != null) "Save" else "Add") }
            }
        }
        if (list.isEmpty() && !adding) item("e") { EmptyState(EmptySpec(spec.icon, "Nothing yet", "Add the first entry above.")) }
        list.forEach { o ->
            item(o.optString("id")) {
                val (a, b) = runCatching { spec.line(o) }.getOrDefault(o.optString("_0") to "")
                ListRow(a, supporting = b.ifBlank { null }, icon = spec.icon, tint = spec.hub.tint(), trailing = {
                    Text("✕", Modifier.padding(Space.s).pressable { store.remove(o.optString("id")); list = store.all().reversed() }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }) {
                    spec.fields.forEach { f -> values[f.key] = o.optString(f.key) }; editing = o.optString("id"); adding = true
                }
            }
        }
    }
}

@Composable
fun CalcToolScreen(spec: CalcSpec, container: AppContainer? = null) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var why by remember { mutableStateOf<String?>(null) }
    var thinking by remember { mutableStateOf(false) }
    val prefs = remember { ctx.getSharedPreferences("nxcalc_${spec.id}", 0) }
    val values = remember { mutableStateMapOf<String, String>().apply { spec.inputs.forEach { put(it.key, prefs.getString(it.key, null) ?: Mind.shared(ctx, it) ?: it.default) } } }
    val vars = spec.inputs.associate { f ->
        f.key.lowercase() to (if (f.kind == K.CHOICE) f.options.indexOf(values[f.key]).coerceAtLeast(0).toDouble() else num(values[f.key]))
    }
    val results = spec.outputs.map { (label, expr) ->
        label to runCatching { Calc.fmt(Calc.eval(expr, vars)) }.getOrElse { "—" }
    }
    AtlasScreen(title = spec.title, subtitle = spec.blurb, hub = spec.hub) {
        item("in") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                spec.inputs.forEach { f -> FieldInput(f, values[f.key].orEmpty(), { values[f.key] = it; prefs.edit().putString(f.key, it).apply(); Mind.remember(ctx, f, it) }, Modifier.padding(bottom = Space.s)) }
            }
        }
        item("out") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                results.forEachIndexed { i, (label, v) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(v, style = if (i == results.lastIndex) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold, color = if (i == results.lastIndex) spec.hub.tint() else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        item("share") { Row(Modifier.padding(horizontal = Space.page)) { Capsule("Share result", false) {
            Share.text(ctx, spec.title + "\n" + spec.inputs.joinToString("\n") { "${it.label}: ${values[it.key]}" } + "\n\n" + results.joinToString("\n") { "${it.first}: ${it.second}" })
        } } }
        if (container != null) item("explain") {
            Column(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Button(onClick = {
                    thinking = true; why = null
                    val facts = spec.inputs.joinToString("; ") { "${it.label}: ${values[it.key]}" } + ". Results: " + results.joinToString("; ") { "${it.first} = ${it.second}" }
                    scope.launch {
                        runCatching { Ai.generate(container.settingsRepository, "Explain these numbers in plain words for someone in Pakistan, say what they mean for a real decision, and give one useful tip. Be brief. ${spec.title}. $facts", system = "You are a clear, practical helper. No jargon.", maxOutputTokens = 500) }
                            .onSuccess { why = it }.onFailure { Feedback.show(it.message ?: "Couldn't do that") }
                        thinking = false
                    }
                }, enabled = !thinking) { Text(if (thinking) "Thinking…" else "What does this mean?") }
                why?.let { Group(Modifier.padding(top = Space.s)) { Md(it) } }
            }
        }
        if (spec.note.isNotBlank()) item("note") {
            Text(spec.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Space.page))
        }
    }
}

@Composable
fun LinkToolScreen(spec: LinkSpec) {
    val ctx = LocalContext.current
    AtlasScreen(title = spec.title, subtitle = spec.blurb, hub = spec.hub) {
        spec.links.forEach { (t, d, url) -> item(url) { ListRow(t, supporting = d, icon = spec.icon, tint = spec.hub.tint()) { Share.open(ctx, url) } } }
    }
}

@Composable
fun NxScreen(id: String, container: AppContainer, open: (String) -> Unit) {
    val spec = Nx.find(id)
    val ctx = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(id) { com.nizam.app.ui.design.guard { Mind.opened(ctx, id) } }
    val withMind = spec != null && id !in SELF_WRAPPED && !(spec is LogSpec && spec.sealed)
    Box(Modifier.fillMaxSize()) {
        NxBody(id, container, open)
        if (withMind && spec != null) MindDock(spec, open, Modifier.align(Alignment.BottomEnd).padding(end = 14.dp, bottom = 14.dp))
    }
}

@Composable
private fun NxBody(id: String, container: AppContainer, open: (String) -> Unit) {
    when (val s = Nx.find(id)) {
        is AiSpec -> AiToolScreen(s, container)
        is ChatSpec -> ChatToolScreen(s, container)
        is LogSpec -> LogToolScreen(s)
        is CalcSpec -> CalcToolScreen(s, container)
        is LinkSpec -> LinkToolScreen(s)
        is ScreenSpec -> if (s.id in SELF_WRAPPED) s.content(container, open)
            else AtlasScreen(title = s.title, subtitle = s.blurb, hub = s.hub) {
                item("screen-${s.id}") { Box(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s)) { s.content(container, open) } }
                item("screen-end") { androidx.compose.foundation.layout.Spacer(Modifier.height(Space.xxxl)) }
            }
        null -> AtlasScreen(title = "Not found") { item("x") { Text("This tool isn't available.", Modifier.padding(Space.page)) } }
    }
}

internal fun LazyListScope.gap(key: String) = item(key) { Box(Modifier.padding(Space.s)) }
