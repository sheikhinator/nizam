package com.nizam.app.feature.nx

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.brain2.Calc
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.voice.Dictate
import com.nizam.app.feature.voice.DictateButton
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * The engine overhaul. Every ask-tool, log, calculator and chat in Atlas is drawn by four shared
 * engines; the powers here plug into those engines, so all of them gain them at once:
 * knowing you (opt-in, per tool), voice everywhere, turning any answer into real tasks/expenses/notes
 * (you tick what to add), follow-up questions, "what changed since last time", runs on a schedule,
 * trends, streaks, search, CSV export, pattern finding and reminders on every log, saved scenarios
 * and what-if sweeps on every calculator.
 */
object EngineX {
    private fun knowsPrefs(c: Context) = c.getSharedPreferences("nx_knows", 0)
    fun knows(c: Context, id: String) = knowsPrefs(c).getBoolean(id, false)
    fun setKnows(c: Context, id: String, on: Boolean) = knowsPrefs(c).edit().putBoolean(id, on).apply()

    /** What Atlas knows that matters for [hint], only when the person switched it on for this tool. Never includes sealed logs. */
    suspend fun context(c: Context, id: String, hint: String): String =
        if (!knows(c, id)) "" else withContext(Dispatchers.IO) { LifeContext.digest(LifeContext.YES, hint, c) }

    /** Pulls the first JSON object out of a model reply. */
    fun json(text: String): JSONObject? {
        val a = text.indexOf('{'); val b = text.lastIndexOf('}')
        if (a < 0 || b <= a) return null
        return runCatching { JSONObject(text.substring(a, b + 1)) }.getOrNull()
    }

    fun dayOf(o: JSONObject, spec: LogSpec): LocalDate {
        spec.fields.firstOrNull { it.kind == K.DATE }?.let { f -> runCatching { return LocalDate.parse(o.optString(f.key).take(10)) } }
        val t = o.optLong("t", 0L)
        return if (t > 0) java.time.Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()).toLocalDate() else LocalDate.now()
    }
}

/** 1234567 → "12.3 lakh"-style short numbers for small cards. */
fun short(v: Double): String {
    val a = kotlin.math.abs(v); val sign = if (v < 0) "−" else ""
    return sign + when {
        a >= 10_000_000 -> "%.1f cr".format(a / 10_000_000)
        a >= 100_000 -> "%.1f lakh".format(a / 100_000)
        a >= 10_000 -> "%.0fk".format(a / 1000)
        a >= 100 -> "%,.0f".format(a)
        a == kotlin.math.floor(a) -> "%.0f".format(a)
        else -> "%.1f".format(a)
    }
}

// ───────────────────────────── Voice everywhere ─────────────────────────────

/** A mic that asks for microphone permission the first time, then dictates. */
@Composable
fun MicButton(onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    var granted by remember { mutableStateOf(Dictate.hasMic(c)) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        if (ok) Feedback.show("Mic ready. Tap it again to speak")
    }
    if (granted) DictateButton(onDone = onText, modifier = modifier)
    else IconButton(onClick = { runCatching { ask.launch(android.Manifest.permission.RECORD_AUDIO) } }, modifier = modifier) {
        Icon(Icons.Outlined.Mic, "Allow microphone", tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ───────────────────────────── Knows me ─────────────────────────────

@Composable
fun KnowsMeSwitch(id: String, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    var on by remember(id) { mutableStateOf(EngineX.knows(c, id)) }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Use what Atlas knows about me", style = MaterialTheme.typography.bodyMedium)
            Text(if (on) "Answers from your own records (private logs never included)" else "Off: a general answer",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(on, { on = it; EngineX.setKnows(c, id, it) })
    }
}

// ───────────────────────────── Turn into actions ─────────────────────────────

class Act(val type: String, val title: String, val amount: Double, val category: String, val due: String)

object Acts {
    private val TYPES = setOf("task", "reminder", "expense", "income", "note", "habit")

    suspend fun extract(container: AppContainer, text: String): List<Act> {
        val today = LocalDate.now()
        val raw = Ai.generate(container.settingsRepository,
            "Today is $today. From the text below, list the concrete things the user could save into their life app. " +
                "Output only JSON: {\"actions\":[{\"type\":\"task|reminder|expense|income|note|habit\",\"title\":\"short, starts with a verb for tasks\"," +
                "\"amount\":0,\"category\":\"\",\"due\":\"YYYY-MM-DD or empty\"}]}. At most 8. Use expense or income only when the text states a real amount " +
                "the user spent or earned. Use note for information worth keeping. Use habit only for something to repeat daily. If nothing fits, return an empty list.\n\nTEXT:\n" +
                text.take(6000),
            system = "Output only JSON. No commentary.", maxOutputTokens = 900)
        val arr = EngineX.json(raw)?.optJSONArray("actions") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val type = o.optString("type").lowercase().trim()
            val title = o.optString("title").trim()
            if (type !in TYPES || title.isBlank()) null
            else Act(type, title.take(160), o.optDouble("amount", 0.0).takeIf { !it.isNaN() } ?: 0.0, o.optString("category").take(40), o.optString("due").take(10))
        }.take(8)
    }

    private fun dueMillis(d: String): Long? = runCatching { LocalDate.parse(d).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()

    /** Saves one action the person ticked. */
    suspend fun apply(container: AppContainer, a: Act, source: String) {
        when (a.type) {
            "task" -> container.taskRepository.add(a.title, dueMillis(a.due), 1)
            "reminder" -> container.taskRepository.add(a.title, dueMillis(a.due) ?: (System.currentTimeMillis() + 3_600_000L), 2)
            "expense" -> if (a.amount > 0) container.financeRepository.add(a.amount, "EXPENSE", a.category.ifBlank { "Other" }, a.title)
            "income" -> if (a.amount > 0) container.financeRepository.add(a.amount, "INCOME", a.category.ifBlank { "Other" }, a.title)
            "note" -> container.noteRepository.createFull(a.title, "${a.title}\n\nFrom $source")
            "habit" -> container.habitRepository.addHabit(a.title, 7)
        }
    }

    fun emoji(t: String) = when (t) { "task" -> "☑"; "reminder" -> "⏰"; "expense" -> "−₨"; "income" -> "+₨"; "note" -> "✎"; else -> "↻" }
}

/** "Turn into actions": Atlas proposes, you tick what to keep, nothing is added until you tap Add. */
@Composable
fun ActOnIt(container: AppContainer, source: String, text: String, modifier: Modifier = Modifier) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var busy by remember(text) { mutableStateOf(false) }
    val acts = remember(text) { mutableStateListOf<Act>() }
    val picked = remember(text) { mutableStateListOf<Boolean>() }
    var done by remember(text) { mutableStateOf(false) }
    Column(modifier) {
        if (acts.isEmpty()) {
            Capsule(if (busy) "Finding actions…" else "⚡ Turn into actions", false) {
                if (busy) return@Capsule
                busy = true
                scope.launch {
                    runCatching { Acts.extract(container, text) }
                        .onSuccess { l -> acts.clear(); picked.clear(); acts.addAll(l); picked.addAll(l.map { true }); if (l.isEmpty()) Feedback.show("Nothing here to turn into a task or entry") }
                        .onFailure { Feedback.show(it.message ?: "Couldn't do that") }
                    busy = false
                }
            }
        } else if (!done) {
            Group(Modifier.padding(vertical = Space.s)) {
                Text("Add to Atlas?", fontWeight = FontWeight.SemiBold)
                Text("Tick what you want. Nothing is saved until you tap Add.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                acts.forEachIndexed { i, a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(picked.getOrElse(i) { false }, { picked[i] = it })
                        Text(Acts.emoji(a.type), Modifier.padding(end = 6.dp), color = MaterialTheme.colorScheme.primary)
                        Column(Modifier.weight(1f)) {
                            Text(a.title, style = MaterialTheme.typography.bodyMedium)
                            val extra = listOfNotNull(a.type.replaceFirstChar { it.uppercase() },
                                if (a.amount > 0) rs(a.amount) else null, a.category.takeIf { it.isNotBlank() }, a.due.takeIf { it.isNotBlank() })
                            Text(extra.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                val n = picked.count { it }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Button(onClick = {
                        busy = true
                        val chosen = acts.filterIndexed { i, _ -> picked.getOrElse(i) { false } }
                        scope.launch {
                            var ok = 0
                            chosen.forEach { a -> if (runCatching { Acts.apply(container, a, source) }.isSuccess) ok++ }
                            Feedback.show("Added $ok to Atlas")
                            done = true; busy = false
                        }
                    }, enabled = n > 0 && !busy) { Text(if (busy) "Adding…" else "Add $n") }
                    TextButton(onClick = { acts.clear(); picked.clear() }) { Text("Cancel") }
                }
            }
        } else Text("✓ Added to Atlas", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
}

// ───────────────────────────── Follow-up questions ─────────────────────────────

@Composable
fun FollowUp(container: AppContainer, system: String, base: String, modifier: Modifier = Modifier) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val thread = remember(base) { mutableStateListOf<Pair<String, String>>() }
    var q by remember(base) { mutableStateOf("") }
    var busy by remember(base) { mutableStateOf(false) }
    fun ask(text: String) {
        if (text.isBlank() || busy) return
        busy = true; q = ""
        val convo = thread.takeLast(4).joinToString("\n\n") { "User: ${it.first}\nYou: ${it.second}" }
        scope.launch {
            runCatching {
                Ai.generate(container.settingsRepository, "Your earlier answer:\n${base.take(5000)}\n\n$convo\n\nUser follow-up: $text\n\nAnswer the follow-up directly and briefly.",
                    system = system, maxOutputTokens = 900)
            }.onSuccess { thread += text to it }.onFailure { Feedback.show(it.message ?: "Couldn't do that") }
            busy = false
        }
    }
    Column(modifier) {
        thread.forEach { (qq, a) ->
            Text("You: $qq", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = Space.s))
            Md(a)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text(if (busy) "Thinking…" else "Ask a follow-up") }, maxLines = 3,
                trailingIcon = { MicButton({ ask(it) }) })
            TextButton(onClick = { ask(q.trim()) }, enabled = q.isNotBlank() && !busy) { Text("Ask") }
        }
    }
}

// ───────────────────────────── Runs on a schedule + log reminders ─────────────────────────────

object AutoRuns {
    private fun p(c: Context) = c.getSharedPreferences("nx_autorun", 0)
    private fun remind(c: Context) = c.getSharedPreferences("nx_logremind", 0)

    fun every(c: Context, id: String): String = runCatching { JSONObject(p(c).getString(id, "{}")!!).optString("every", "off") }.getOrDefault("off")

    fun set(c: Context, id: String, every: String, inputs: Map<String, String>) {
        if (every == "off") { p(c).edit().remove(id).apply(); return }
        p(c).edit().putString(id, JSONObject().put("every", every).put("in", JSONObject(inputs)).put("last", LocalDate.now().toString()).toString()).apply()
    }

    fun reminderHour(c: Context, id: String) = remind(c).getInt(id, -1)
    fun setReminder(c: Context, id: String, hour: Int) = if (hour < 0) remind(c).edit().remove(id).apply() else remind(c).edit().putInt(id, hour).apply()

    /** Called by the background loop. Morning runs of scheduled tools (at most 3 per pass) and log reminders. */
    suspend fun tick(c: Context, container: AppContainer, now: Double) {
        val today = LocalDate.now()
        // log reminders: once a day at the chosen hour, only if nothing was logged today
        remind(c).all.forEach { (id, v) ->
            val hour = (v as? Int) ?: return@forEach
            if (now < hour || now > hour + 0.5) return@forEach
            val key = "sent_$id"
            val sp = c.getSharedPreferences("nx_logremind_sent", 0)
            if (sp.getString(key, "") == today.toString()) return@forEach
            val spec = Nx.find(id) as? LogSpec ?: return@forEach
            val logged = JsonStore(c, "nxlog_$id", spec.sealed).all().any { EngineX.dayOf(it, spec) == today }
            sp.edit().putString(key, today.toString()).apply()
            if (!logged) Notifications.post(c, Notifications.CHANNEL_NUDGE, 330 + (id.hashCode() and 31), spec.title, "Nothing logged today yet. Takes ten seconds.")
        }
        if (now !in 7.0..10.0) return
        var ran = 0
        for ((id, v) in p(c).all) {
            if (ran >= 3) break
            val o = runCatching { JSONObject(v as String) }.getOrNull() ?: continue
            val last = runCatching { LocalDate.parse(o.optString("last")) }.getOrDefault(today.minusDays(30))
            val due = when (o.optString("every")) { "daily" -> last < today; "weekly" -> !last.plusDays(7).isAfter(today); else -> false }
            if (!due) continue
            val spec = Nx.find(id) as? AiSpec ?: continue
            val ins = o.optJSONObject("in") ?: JSONObject()
            val map = ins.keys().asSequence().associateWith { ins.optString(it) }
            o.put("last", today.toString()); p(c).edit().putString(id, o.toString()).apply()
            ran++
            runCatching {
                val prompt = spec.prompt(map)
                val ctx = EngineX.context(c, id, prompt)
                val r = Ai.generate(container.settingsRepository, if (ctx.isBlank()) prompt else "$ctx\n\n$prompt", system = spec.system, maxOutputTokens = spec.tokens, useWeb = spec.web)
                val store = JsonStore(c, "nx_$id")
                store.add(JSONObject().put("in", "Scheduled · " + map.values.filter { it.isNotBlank() }.joinToString(" · ").take(100)).put("out", r).put("t", System.currentTimeMillis()))
                store.all().let { all -> if (all.size > 25) store.save(all.takeLast(25)) }
                Notifications.post(c, Notifications.CHANNEL_NUDGE, 360 + (id.hashCode() and 31), spec.title,
                    com.nizam.app.ui.components.RichFormat.plain(r).replace(Regex("\\s+"), " ").take(220))
            }
        }
    }
}

@Composable
fun ScheduleChips(id: String, inputs: () -> Map<String, String>, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    var every by remember(id) { mutableStateOf(AutoRuns.every(c, id)) }
    Column(modifier) {
        Text("Run this for me automatically", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(top = 4.dp)) {
            val opts = listOf("off" to "Off", "daily" to "Every morning", "weekly" to "Every week")
            items(opts.size) { i ->
                val (k, label) = opts[i]
                Capsule(label, every == k) {
                    every = k; AutoRuns.set(c, id, k, inputs())
                    if (k != "off") Feedback.show("Done. The result will arrive as a notification and stay under Earlier")
                }
            }
        }
    }
}

// ───────────────────────────── Logs: trends, streaks, search, export, patterns, voice ─────────────────────────────

object LogX {
    class Stats(val field: F?, val week: Double, val lastWeek: Double, val avg: Double, val count: Int, val streak: Int, val daily: List<Double>, val labels: List<String>)

    fun stats(spec: LogSpec, list: List<JSONObject>): Stats {
        val nf = spec.fields.firstOrNull { it.kind == K.NUMBER }
        val today = LocalDate.now()
        val byDay = HashMap<LocalDate, Double>()
        val days = HashSet<LocalDate>()
        list.forEach { o ->
            val d = EngineX.dayOf(o, spec); days += d
            byDay[d] = (byDay[d] ?: 0.0) + (nf?.let { num(o.optString(it.key)) } ?: 1.0)
        }
        fun sumBetween(from: LocalDate, to: LocalDate) = byDay.filterKeys { !it.isBefore(from) && !it.isAfter(to) }.values.sum()
        val week = sumBetween(today.minusDays(6), today)
        val lastWeek = sumBetween(today.minusDays(13), today.minusDays(7))
        val nums = if (nf != null) list.map { num(it.optString(nf.key)) } else emptyList()
        var streak = 0
        var d = if (today in days) today else today.minusDays(1)
        while (d in days) { streak++; d = d.minusDays(1) }
        val daily = (29 downTo 0).map { byDay[today.minusDays(it.toLong())] ?: 0.0 }
        val labels = (29 downTo 0).map { today.minusDays(it.toLong()).let { x -> "${x.dayOfMonth} ${x.month.name.take(3).lowercase().replaceFirstChar { c -> c.uppercase() }}" } }
        return Stats(nf, week, lastWeek, if (nums.isEmpty()) 0.0 else nums.average(), list.size, streak, daily, labels)
    }

    fun csv(spec: LogSpec, list: List<JSONObject>): String {
        fun esc(s: String) = if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
        val head = spec.fields.joinToString(",") { esc(it.label) } + ",Saved"
        val rows = list.map { o -> spec.fields.joinToString(",") { esc(o.optString(it.key)) } + "," + EngineX.dayOf(o, spec) }
        return (listOf(head) + rows).joinToString("\n")
    }

    fun asText(spec: LogSpec, list: List<JSONObject>, n: Int = 60) = list.take(n).joinToString("\n") { o ->
        "- " + EngineX.dayOf(o, spec) + ": " + spec.fields.mapNotNull { f -> o.optString(f.key).takeIf { it.isNotBlank() && f.kind != K.DATE }?.let { "${f.label}: ${it.take(100)}" } }.joinToString("; ")
    }

    /** Turns something the person said ("spent 450 on petrol yesterday") into this log's fields. */
    suspend fun fill(container: AppContainer, spec: LogSpec, said: String): Map<String, String> {
        val desc = spec.fields.joinToString("\n") { f ->
            "- ${f.key}: ${f.label} (${f.kind.name.lowercase()}${if (f.options.isNotEmpty()) ", one of: " + f.options.joinToString(" | ") else ""})"
        }
        val raw = Ai.generate(container.settingsRepository,
            "Today is ${LocalDate.now()}. Fill this log entry from what the user said. Fields:\n$desc\n\nDates as YYYY-MM-DD. Numbers as plain digits. " +
                "For a choice field use exactly one of its options. Leave unknown fields empty. Output only JSON {\"key\":\"value\"}.\n\nThe user said: $said",
            system = "Output only JSON. No commentary.", maxOutputTokens = 400)
        val o = EngineX.json(raw) ?: return emptyMap()
        return spec.fields.associate { f -> f.key to o.optString(f.key).let { v -> if (f.kind == K.CHOICE && v !in f.options) "" else v } }.filterValues { it.isNotBlank() }
    }
}

@Composable
fun LogStatsCard(spec: LogSpec, list: List<JSONObject>, modifier: Modifier = Modifier) {
    val s = remember(list) { LogX.stats(spec, list) }
    val unit = s.field?.label ?: "Entries"
    Group(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("This week", short(s.week))
            Stat("Last week", short(s.lastWeek))
            if (s.field != null) Stat("Average", short(s.avg)) else Stat("All time", s.count.toString())
            Stat("Streak", if (s.streak > 0) "${s.streak}d 🔥" else "0d")
        }
        if (s.lastWeek > 0) {
            val ch = (s.week - s.lastWeek) / s.lastWeek * 100
            Text("${unit}: ${if (ch >= 0) "up" else "down"} ${"%.0f".format(kotlin.math.abs(ch))}% on last week",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        if (s.daily.count { it != 0.0 } >= 2) com.nizam.app.ui.design.TrendLine(s.daily, Modifier.fillMaxWidth().padding(top = Space.s), labels = s.labels, height = 90.dp)
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The tools row on every log: say it, same as last, find patterns, export, daily reminder. */
@Composable
fun LogTools(spec: LogSpec, list: List<JSONObject>, container: AppContainer?, onFill: (Map<String, String>) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var busy by remember { mutableStateOf(false) }
    var patterns by remember { mutableStateOf<String?>(null) }
    var hour by remember(spec.id) { mutableStateOf(AutoRuns.reminderHour(c, spec.id)) }
    Column(modifier) {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            if (container != null) item("say") {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(50)).padding(start = 12.dp)) {
                    Text(if (busy) "Filling…" else "Say it", style = MaterialTheme.typography.labelLarge)
                    MicButton({ said ->
                        busy = true
                        scope.launch {
                            runCatching { LogX.fill(container, spec, said) }.onSuccess { m -> if (m.isEmpty()) Feedback.show("Couldn't match that to this log") else onFill(m) }
                                .onFailure { Feedback.show(it.message ?: "Couldn't do that") }
                            busy = false
                        }
                    })
                }
            }
            if (list.isNotEmpty()) item("same") {
                Capsule("Same as last", false) {
                    val o = list.first()
                    onFill(spec.fields.associate { f -> f.key to if (f.kind == K.DATE) LocalDate.now().toString() else o.optString(f.key) })
                }
            }
            if (container != null && list.size >= 3) item("pat") {
                Capsule(if (patterns == null && busy) "Looking…" else "Find patterns", patterns != null) {
                    if (patterns != null) { patterns = null; return@Capsule }
                    busy = true
                    scope.launch {
                        runCatching {
                            Ai.generate(container.settingsRepository, "These are entries from the user's \"${spec.title}\" log, newest first:\n" + LogX.asText(spec, list) +
                                "\n\nFind the real patterns: trends, what tends to happen on which days, anything unusual, and one practical suggestion. Use only these entries. Short bullet points.",
                                system = "You are a sharp, kind analyst. Never invent data.", maxOutputTokens = 700)
                        }.onSuccess { patterns = it }.onFailure { Feedback.show(it.message ?: "Couldn't do that") }
                        busy = false
                    }
                }
            }
            if (list.isNotEmpty()) item("csv") {
                Capsule("Export", false) {
                    runCatching {
                        val f = Share.out(c, spec.title.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifBlank { "Atlas log" } + ".csv")
                        f.writeText(LogX.csv(spec, list)); Share.file(c, f, "text/csv")
                    }.onFailure { Feedback.show("Couldn't export") }
                }
            }
            if (!spec.sealed) item("rem") {
                val opts = listOf(-1 to "No reminder", 9 to "Remind 9am", 14 to "Remind 2pm", 21 to "Remind 9pm")
                val cur = opts.firstOrNull { it.first == hour } ?: opts[0]
                Capsule("⏰ " + cur.second.removePrefix("Remind "), hour >= 0) {
                    val next = opts[(opts.indexOf(cur) + 1) % opts.size]
                    hour = next.first; AutoRuns.setReminder(c, spec.id, next.first)
                    Feedback.show(if (next.first < 0) "Reminder off" else "I'll nudge you at ${next.second.removePrefix("Remind ")} on days you haven't logged")
                }
            }
        }
        patterns?.let { Group(Modifier.padding(top = Space.s)) { Md(it) } }
    }
}

// ───────────────────────────── Calculators: scenarios and what-if ─────────────────────────────

object CalcX {
    private fun store(c: Context, id: String) = JsonStore(c, "nxcalc_scen_$id")
    fun scenarios(c: Context, id: String) = store(c, id).all()
    fun save(c: Context, id: String, values: Map<String, String>, results: List<Pair<String, String>>) {
        val s = store(c, id)
        val n = s.all().size + 1
        s.add(JSONObject().put("name", "Scenario $n").put("in", JSONObject(values)).put("out", JSONObject(results.toMap())).put("t", System.currentTimeMillis()))
        s.all().let { all -> if (all.size > 12) s.save(all.takeLast(12)) }
    }
    fun remove(c: Context, id: String, sid: String) = store(c, id).remove(sid)
}

@Composable
fun CalcPowers(spec: CalcSpec, values: MutableMap<String, String>, results: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    var scen by remember(spec.id) { mutableStateOf(CalcX.scenarios(c, spec.id)) }
    val numeric = spec.inputs.filter { it.kind == K.NUMBER }
    var sweep by remember(spec.id) { mutableStateOf<String?>(null) }
    Column(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Capsule("Save scenario", false) { CalcX.save(c, spec.id, values.toMap(), results); scen = CalcX.scenarios(c, spec.id); Feedback.show("Saved. Change the numbers and compare") }
        }
        if (scen.isNotEmpty()) Group(Modifier.padding(top = Space.s)) {
            Text("Compare scenarios", fontWeight = FontWeight.SemiBold)
            val last = results.lastOrNull()?.first
            scen.forEach { o ->
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    val ins = o.optJSONObject("in") ?: JSONObject()
                    Column(Modifier.weight(1f)) {
                        Text(o.optString("name"), style = MaterialTheme.typography.bodyMedium)
                        Text(spec.inputs.take(3).joinToString(" · ") { "${it.label}: ${ins.optString(it.key)}" }, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Text(last?.let { o.optJSONObject("out")?.optString(it) }.orEmpty(), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    TextButton(onClick = { spec.inputs.forEach { f -> values[f.key] = ins.optString(f.key, values[f.key].orEmpty()) } }) { Text("Load") }
                    TextButton(onClick = { CalcX.remove(c, spec.id, o.optString("id")); scen = CalcX.scenarios(c, spec.id) }) { Text("Remove") }
                }
            }
        }
        if (numeric.isNotEmpty() && spec.outputs.isNotEmpty()) {
            Text("What if…", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.s))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(top = 4.dp)) {
                items(numeric.size) { i -> val f = numeric[i]; Capsule(f.label, sweep == f.key) { sweep = if (sweep == f.key) null else f.key } }
            }
            sweep?.let { key ->
                val f = numeric.first { it.key == key }
                val base = num(values[key])
                val (outLabel, expr) = spec.outputs.last()
                val steps = (0..10).map { 0.5 + it * 0.1 }
                val series = steps.map { m ->
                    val vars = spec.inputs.associate { x -> x.key.lowercase() to (if (x.kind == K.CHOICE) x.options.indexOf(values[x.key]).coerceAtLeast(0).toDouble()
                        else if (x.key == key) base * m else num(values[x.key])) }
                    runCatching { Calc.eval(expr, vars) }.getOrDefault(0.0)
                }
                if (base == 0.0) Text("Enter ${f.label} first.", style = MaterialTheme.typography.bodySmall)
                else Group(Modifier.padding(top = Space.s)) {
                    Text("$outLabel as ${f.label} moves from −50% to +50%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    com.nizam.app.ui.design.TrendLine(series, Modifier.fillMaxWidth(), labels = steps.map { m -> "${f.label} ${short(base * m)}" }, height = 100.dp,
                        format = { short(it) })
                }
            }
        }
    }
}
