package com.nizam.app.feature.next

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.nizam.app.AppContainer
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.EmptySpec
import com.nizam.app.ui.design.EmptyState
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Segmented
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.pressable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.time.LocalDateTime
import java.time.ZoneId

private fun send(c: Context, text: String) = c.startActivity(Intent.createChooser(
    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Send"))

// ── Activity feed ────────────────────────────────────────────────────────────────────────────────

/** A receipt for everything Atlas does on your behalf: what, when, and whether it worked. */
object Activity {
    private fun store(c: Context) = JsonStore(c, "activity")
    fun log(c: Context, action: String, summary: String, ok: Boolean) {
        val s = store(c)
        s.save((listOf(JSONObject().put("id", System.nanoTime().toString()).put("a", action).put("s", summary.take(300))
            .put("ok", ok).put("t", System.currentTimeMillis())) + s.all()).take(300))
        if (ok) com.nizam.app.ui.design.EdgeGlow.now()
        runCatching { com.nizam.app.feature.agentic.ChainLog.append(c, action, summary) }
    }
    fun all(c: Context) = store(c).all()
    fun clear(c: Context) = store(c).save(emptyList())
}

@Composable
fun ActivityScreen() {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(Activity.all(ctx)) }
    val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM, h:mm a")
    AtlasScreen(title = "Activity", subtitle = "Everything Atlas did for you", actions = {
        if (list.isNotEmpty()) Text("Clear", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(Space.s).pressable { Activity.clear(ctx); list = emptyList() })
    }) {
        if (list.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.ReceiptLong, "Nothing yet", "When Atlas acts for you (adds, sends, opens, changes), a receipt appears here.")) }
        list.forEach { o ->
            item(o.optString("id")) {
                val ok = o.optBoolean("ok")
                ListRow(o.optString("s").ifBlank { o.optString("a") },
                    supporting = o.optString("a").replace('_', ' ') + " · " + java.time.Instant.ofEpochMilli(o.optLong("t")).atZone(ZoneId.systemDefault()).format(fmt),
                    icon = if (ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                    tint = if (ok) Hub.Deen.tint() else MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ── Capture inbox + event grabber ────────────────────────────────────────────────────────────────

/**
 * Throw anything in — a thought, a flyer photo, a wedding card — and Atlas turns it into a task,
 * a calendar event or a note. Photos are read on the phone first.
 */
object Capture {
    fun store(c: Context) = JsonStore(c, "capture")

    suspend fun understand(container: AppContainer, text: String): JSONObject {
        val today = java.time.LocalDate.now()
        val raw = Ai.generate(container.settingsRepository,
            "Today is $today (${today.dayOfWeek}). Decide what this is and extract it. Reply with JSON only: " +
                "{\"kind\":\"event|task|note\",\"title\":\"...\",\"date\":\"yyyy-mm-dd or empty\",\"time\":\"HH:mm or empty\",\"place\":\"...\",\"details\":\"...\"}\n\n$text",
            system = "You turn messy text into structured items. JSON only.", maxOutputTokens = 300)
        return runCatching { JSONObject(Ai.cleanJson(raw)) }.getOrElse { JSONObject().put("kind", "note").put("title", text.take(60)).put("details", text) }
            .put("t", System.currentTimeMillis())
    }

    fun toCalendar(c: Context, o: JSONObject) {
        val date = runCatching { java.time.LocalDate.parse(o.optString("date")) }.getOrNull()
        val time = runCatching { java.time.LocalTime.parse(o.optString("time")) }.getOrNull()
        val start = (date ?: java.time.LocalDate.now()).atTime(time ?: java.time.LocalTime.of(10, 0))
        val ms = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, o.optString("title"))
            .putExtra(CalendarContract.Events.EVENT_LOCATION, o.optString("place"))
            .putExtra(CalendarContract.Events.DESCRIPTION, o.optString("details"))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, ms)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, ms + 60 * 60_000L)
            .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, time == null)
        runCatching { c.startActivity(i) }.onFailure { Feedback.show("No calendar app found") }
    }
}

@Composable
fun CaptureScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var items by remember { mutableStateOf(Capture.store(ctx).all()) }
    var triage by remember { mutableStateOf(false) }
    fun reload() { items = Capture.store(ctx).all() }
    fun process(input: String) {
        if (input.isBlank()) return
        busy = true
        scope.launch {
            val o = runCatching { Capture.understand(container, input) }.getOrElse {
                JSONObject().put("kind", "note").put("title", input.take(60)).put("details", input) }
            o.put("raw", input.take(1000)).put("at", System.currentTimeMillis())
            Capture.store(ctx).add(o)
            text = ""; busy = false; reload()
        }
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val read = runCatching { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromFilePath(ctx, uri)).await().text }.getOrDefault("")
            if (read.isBlank()) { busy = false; Feedback.show("Couldn't read any text in that photo") } else process(read)
        }
    }
    AtlasScreen(title = "Capture", subtitle = "Throw anything in. Atlas files it.") {
        item("in") {
            Column(Modifier.padding(horizontal = Space.page)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().height(110.dp),
                    placeholder = { Text("\"Ali's walima Saturday 8pm at Pearl Continental\" or \"renew passport\"") })
                Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Button(onClick = { process(text) }, enabled = text.isNotBlank() && !busy) { Text(if (busy) "Filing…" else "File it") }
                    OutlinedButton(onClick = { photo.launch("image/*") }, enabled = !busy) { Text("From a photo") }
                }
            }
        }
        if (items.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.Inbox, "Inbox zero", "Captured things wait here until you act on them.")) }
        if (items.size >= 2) item("deck-h") {
            SectionHeader(if (triage) "Swipe right to act, left when done" else "Triage", action = if (triage) "List view" else "Swipe through") { triage = !triage }
        }
        if (triage && items.isNotEmpty()) item("deck") {
            com.nizam.app.ui.design.SwipeDeck(items.sortedByDescending { it.optLong("at") }, key = { it.optString("id") },
                modifier = Modifier.padding(horizontal = Space.page),
                rightLabel = "Act", leftLabel = "Done",
                onRight = { o ->
                    val id = o.optString("id")
                    if (o.optString("kind") == "event") Capture.toCalendar(ctx, o)
                    else scope.launch { container.taskRepository.add(o.optString("title"), null, 1) }
                    Capture.store(ctx).remove(id); reload()
                },
                onLeft = { o -> Capture.store(ctx).remove(o.optString("id")); reload() }) { o ->
                Group {
                    Text(o.optString("kind", "note").uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(o.optString("title"), style = MaterialTheme.typography.headlineSmall)
                    Text(o.optString("details").ifBlank { o.optString("raw") }.take(240), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (!triage) items.sortedByDescending { it.optLong("at") }.forEach { o ->
            val id = o.optString("id")
            item(id) {
                val kind = o.optString("kind", "note")
                val whenText = listOf(o.optString("date"), o.optString("time")).filter { it.isNotBlank() }.joinToString(" ")
                Group(Modifier.padding(horizontal = Space.page, vertical = Space.xs)) {
                    Text(kind.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(o.optString("title"), style = MaterialTheme.typography.titleMedium)
                    val sub = listOf(whenText, o.optString("place")).filter { it.isNotBlank() }.joinToString(" · ")
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        if (kind == "event") Button(onClick = { Capture.toCalendar(ctx, o); Capture.store(ctx).remove(id); reload() }) { Text("Add to calendar") }
                        if (kind != "event") Button(onClick = {
                            scope.launch {
                                val due = runCatching { java.time.LocalDate.parse(o.optString("date")).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
                                container.taskRepository.add(o.optString("title"), due, 1); Capture.store(ctx).remove(id); reload(); Feedback.show("Added to tasks")
                            }
                        }) { Text("Make a task") }
                        OutlinedButton(onClick = { Capture.store(ctx).remove(id); reload() }) { Text("Done") }
                    }
                }
            }
        }
    }
}

// ── Negotiator ───────────────────────────────────────────────────────────────────────────────────

/** Firm, polite messages for disputes — a wrong bill, a refund, a landlord — with a follow-up reminder. */
@Composable
fun NegotiatorScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var to by remember { mutableStateOf("") }
    var issue by remember { mutableStateOf("") }
    var want by remember { mutableStateOf("") }
    var tone by remember { mutableStateOf("Firm") }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AtlasScreen(title = "Negotiator", subtitle = "Complaints, refunds and disputes, worded to win") {
        item("f") {
            Column(Modifier.padding(horizontal = Space.page)) {
                OutlinedTextField(to, { to = it }, Modifier.fillMaxWidth(), label = { Text("To (company or person)") }, singleLine = true)
                OutlinedTextField(issue, { issue = it }, Modifier.fillMaxWidth().padding(top = Space.xs), label = { Text("What happened") })
                OutlinedTextField(want, { want = it }, Modifier.fillMaxWidth().padding(top = Space.xs), label = { Text("What you want") })
                Segmented(listOf("Polite", "Firm", "Final notice"), tone, { tone = it }, { it })
                Button(onClick = {
                    busy = true
                    scope.launch {
                        draft = runCatching {
                            Ai.generate(container.settingsRepository,
                                "Write a $tone message to $to.\nWhat happened: $issue\nWhat I want: $want\n" +
                                    "Be specific, reference dates/amounts I gave, state a clear deadline (7 days) and the next step if unresolved " +
                                    "(e.g. escalation to the regulator such as PTA, NEPRA, OGRA, SBP, or the consumer court, whichever fits). Keep it under 180 words.",
                                system = "You write effective, courteous consumer complaints. Return only the message.", maxOutputTokens = 500).trim()
                        }.getOrElse { Feedback.failed("Drafting", it); "" }
                        busy = false
                    }
                }, enabled = issue.isNotBlank() && !busy, modifier = Modifier.padding(top = Space.s)) { Text(if (busy) "Drafting…" else "Draft it") }
            }
        }
        if (draft.isNotBlank()) item("d") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                SelectionContainer { Text(draft, style = MaterialTheme.typography.bodyLarge) }
                Row(Modifier.padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Button(onClick = { send(ctx, draft) }) { Text("Send…") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            container.taskRepository.add("Follow up with $to", LocalDateTime.now().plusDays(7).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 2)
                            Feedback.show("I'll remind you in 7 days")
                        }
                    }) { Text("Remind me in 7 days") }
                }
            }
        }
    }
}

// ── Password vault ───────────────────────────────────────────────────────────────────────────────

/** Logins, encrypted with this phone's secure hardware key. Nothing is synced or sent anywhere. */
object Passwords {
    fun store(c: Context) = JsonStore(c, "passwords", sealed = true)
    fun generate(len: Int = 16): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@#%&*?-_"
        val r = java.security.SecureRandom()
        return (1..len).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }
}

@Composable
fun PasswordsScreen() {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(Passwords.store(ctx).all()) }
    var site by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf(setOf<String>()) }
    var q by remember { mutableStateOf("") }
    fun reload() { list = Passwords.store(ctx).all() }
    fun copy(v: String) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cm.setPrimaryClip(android.content.ClipData.newPlainText("Atlas", v))
        Feedback.show("Copied, clears in 30 s")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { cm.setPrimaryClip(android.content.ClipData.newPlainText("", "")) } }, 30_000)
    }
    AtlasScreen(title = "Passwords", subtitle = "Encrypted on this phone. Set an app lock in Settings for extra safety.") {
        item("q") { OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = Space.page), placeholder = { Text("Search") }, singleLine = true) }
        list.filter { q.isBlank() || it.optString("site").contains(q, true) || it.optString("user").contains(q, true) }.sortedBy { it.optString("site").lowercase() }.forEach { o ->
            val id = o.optString("id")
            item(id) {
                ListRow(o.optString("site"), supporting = o.optString("user") + " · " + if (id in shown) o.optString("pass") else "••••••••", icon = Icons.Outlined.Key,
                    trailing = {
                        Row {
                            Text(if (id in shown) "Hide" else "Show", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(Space.s).pressable { shown = if (id in shown) shown - id else shown + id })
                            Text("Copy", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(Space.s).pressable { copy(o.optString("pass")) })
                        }
                    })
            }
        }
        item("add") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                Text("Add a login", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(site, { site = it }, Modifier.fillMaxWidth(), label = { Text("Site or app") }, singleLine = true)
                OutlinedTextField(user, { user = it }, Modifier.fillMaxWidth(), label = { Text("Username or email") }, singleLine = true)
                OutlinedTextField(pass, { pass = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true)
                Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    OutlinedButton(onClick = { pass = Passwords.generate() }) { Text("Generate strong") }
                    Button(onClick = {
                        if (site.isNotBlank() && pass.isNotBlank()) {
                            Passwords.store(ctx).add(JSONObject().put("site", site.trim()).put("user", user.trim()).put("pass", pass))
                            site = ""; user = ""; pass = ""; reload()
                        }
                    }) { Text("Save") }
                }
            }
        }
    }
}

// ── Family board ─────────────────────────────────────────────────────────────────────────────────

/** A household list — groceries, chores, errands — you tick off and send to the family group in one tap. */
@Composable
fun FamilyBoardScreen() {
    val ctx = LocalContext.current
    val store = remember { JsonStore(ctx, "familyboard") }
    var list by remember { mutableStateOf(store.all()) }
    var text by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("Groceries") }
    fun reload() { list = store.all() }
    AtlasScreen(title = "Family board", subtitle = "Groceries, chores and errands for the house") {
        item("add") {
            Column(Modifier.padding(horizontal = Space.page)) {
                Segmented(listOf("Groceries", "Chores", "Errands"), kind, { kind = it }, { it })
                Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Add to $kind") }, singleLine = true)
                    Button(onClick = { if (text.isNotBlank()) { store.add(JSONObject().put("t", text.trim()).put("k", kind)); text = ""; reload() } }) { Text("Add") }
                }
            }
        }
        listOf("Groceries", "Chores", "Errands").forEach { k ->
            val items = list.filter { it.optString("k") == k }
            if (items.isNotEmpty()) {
                item("h$k") {
                    SectionHeader(k, action = "Send list") {
                        send(ctx, "$k:\n" + items.filter { !it.optBoolean("done") }.joinToString("\n") { "• " + it.optString("t") })
                    }
                }
                items.forEach { o ->
                    item(o.optString("id")) {
                        val done = o.optBoolean("done")
                        ListRow(o.optString("t"), icon = if (done) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                            tint = if (done) Hub.Deen.tint() else MaterialTheme.colorScheme.outline,
                            trailing = {
                                Text("Remove", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(Space.s).pressable { store.remove(o.optString("id")); reload() })
                            }) { store.update(o.optString("id")) { it.put("done", !done) }; reload() }
                    }
                }
            }
        }
    }
}
