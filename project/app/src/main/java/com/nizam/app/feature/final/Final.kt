package com.nizam.app.feature.final

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.agent.Snapshot
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) { content() } }
}

/** The last group: writing, people, work help and the long view. */
@Composable
fun StudioScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var tab by remember { mutableStateOf("Book") }
    var out by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var input by remember { mutableStateOf("") }

    fun run(label: String, saveAs: String? = null, tokens: Int = 1600, prompt: suspend () -> String) = scope.launch {
        busy = true; out = null
        runCatching { Ai.generate(container.settingsRepository, prompt(), maxOutputTokens = tokens) }
            .onSuccess {
                out = it
                if (saveAs != null) { container.noteRepository.createFull(saveAs, it); Feedback.show("Saved to Notes") }
            }.onFailure { Feedback.failed(label, it) }
        busy = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Studio", "Your book, your people, your hard conversations")
        ChipRow(listOf("Book", "Legacy", "Negotiate", "Drafts", "Triage", "People", "Last hour"), tab, { tab = it; out = null })
        Spacer(Modifier.height(8.dp))
        when (tab) {
            // Your life as a book — a chapter a month, in your voice
            "Book" -> Card {
                Text("Your life as a book", style = MaterialTheme.typography.titleMedium)
                Text("A chapter for the month just gone, written from your diary, decisions, photos and data — in your voice. " +
                    "Twelve of them make a year you didn't have to sit down and write.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        val month = YearMonth.now().minusMonths(1)
                        val diary = runCatching { container.diaryRepository.entries().first()
                            .filter { it.localDate.startsWith(month.toString()) } }.getOrElse { emptyList() }
                        run("Chapter", saveAs = "Chapter: $month", tokens = 2000) { "${Snapshot.of(container)}\n\nDiary from $month:\n" +
                            diary.joinToString("\n") { "${it.localDate} (mood ${it.mood}/5): ${it.body.take(400)}" } +
                            "\n\nWrite the chapter for $month of this person's life — narrative, first person, in their own plain " +
                            "voice. What happened, what changed in them, what they were quietly working on. 500-700 words. " +
                            "Truthful to the record; no invented events." }
                        busy = false
                    }
                }) { Text(if (busy) "Writing…" else "Write last month's chapter") }
            }
            // What you'd leave behind
            "Legacy" -> Card {
                Text("Inheritance engine", style = MaterialTheme.typography.titleMedium)
                Text("Everything you've learned, distilled into something your children could actually use — written as it " +
                    "accumulates, not at the end.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(enabled = !busy, onClick = {
                    run("Inheritance", saveAs = "Inheritance · ${LocalDate.now()}", tokens = 2000) { "${Snapshot.of(container)}\n\nFrom everything known about this person — their decisions, " +
                        "values, mistakes, habits and what worked — write the distilled body of knowledge they would want to pass " +
                        "on: hard-won lessons, principles they live by, warnings, and what they'd want remembered. Their voice, " +
                        "addressed to their children. Under 700 words, specific rather than generic." }
                }) { Text(if (busy) "Distilling…" else "Write it") }
            }
            // Negotiation prep
            "Negotiate" -> Card {
                Text("Negotiation prep", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(input, { input = it }, label = { Text("What are you negotiating, and with whom?") },
                    modifier = Modifier.fillMaxWidth())
                Button(enabled = input.isNotBlank() && !busy, onClick = {
                    run("Negotiation prep") { "${Snapshot.of(container)}\n\nNegotiation: ${input.trim()}\n\n" +
                        "Prepare them: their position and its real strength; the other side's likely position and pressures; " +
                        "three openings they can use verbatim; the concessions to trade and the ones to hold; a walk-away line; " +
                        "and the two questions that reveal most. Concrete, Pakistani business context where relevant." }
                }) { Text(if (busy) "Preparing…" else "Prepare me") }
            }
            // Email and message drafts from a spoken gist
            "Drafts" -> Card {
                Text("Draft from a gist", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(input, { input = it }, label = { Text("Say roughly what you mean") },
                    modifier = Modifier.fillMaxWidth().height(120.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Email", "WhatsApp", "Formal letter").forEach { kind ->
                        OutlinedButton(enabled = input.isNotBlank() && !busy, onClick = {
                            run("Draft", tokens = 800) { "Write this as a $kind in the user's own voice — direct, warm, no padding, no " +
                                "corporate filler. Give a subject line if it's an email.\n\nGist: ${input.trim()}" }
                        }) { Text(kind) }
                    }
                }
            }
            // Inbox zero: triage what's waiting
            "Triage" -> Card {
                Text("Triage", style = MaterialTheme.typography.titleMedium)
                Text("Sorts what's waiting into what needs a reply, what can wait, and what's noise.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(enabled = !busy, onClick = {
                    val recent = com.nizam.app.feature.phone.AtlasNotificationListener.recent(30)
                    if (recent.isEmpty()) { Feedback.show("No notifications to triage — grant notification access in Phone control") }
                    else run("Triage", tokens = 1400) { "Notifications waiting:\n" + recent.joinToString("\n") { "${it.app}: ${it.title} — ${it.text.take(120)}" } +
                        "\n\nSort into: NEEDS A REPLY (with who and why), CAN WAIT, and NOISE. Then draft one-line replies for the " +
                        "first group. Be decisive." }
                }) { Text(if (busy) "Sorting…" else "Triage what's waiting") }
            }
            // Relationship health from the People module
            "People" -> {
                var report by remember { mutableStateOf<String?>(null) }
                Card {
                    Text("Relationship health", style = MaterialTheme.typography.titleMedium)
                    Text("Who you've drifted from, ranked by how long it's been — from your People module.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            val people = runCatching { container.dynamicRepository.entries("people").first() }.getOrElse { emptyList() }
                            val lines = people.map { p ->
                                val last = p.value("lastContact")
                                val days = runCatching { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(last), LocalDate.now()) }.getOrNull()
                                Triple(p.title, days, p.value("relation"))
                            }.sortedByDescending { it.second ?: 0L }
                            report = if (lines.isEmpty()) "No one in your People module yet."
                            else lines.joinToString("\n") { (name, days, rel) ->
                                "${name}${if (rel.isNotBlank()) " ($rel)" else ""}: " +
                                    (days?.let { "$it days since you last spoke" } ?: "no date recorded")
                            }
                            val overdue = lines.filter { (it.second ?: 0L) > 30 }
                            if (overdue.isNotEmpty()) overdue.take(3).forEach {
                                container.taskRepository.add("📞 Reach out to ${it.first}", null, 0)
                            }
                            if (overdue.isNotEmpty()) Feedback.show("${overdue.size} overdue · top 3 added to Tasks")
                            busy = false
                        }
                    }) { Text(if (busy) "Checking…" else "Check my people") }
                    report?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
            // One day a year
            else -> Card {
                Text("The last hour", style = MaterialTheme.typography.titleMedium)
                Text("If today were your last, what would you do differently? Answer honestly, then Atlas compares it with how " +
                    "you actually spent the day.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(input, { input = it }, label = { Text("What would you do?") },
                    modifier = Modifier.fillMaxWidth().height(120.dp))
                Button(enabled = input.isNotBlank() && !busy, onClick = {
                    run("The last hour", saveAs = "The last hour · ${LocalDate.now()}", tokens = 900) { "${Snapshot.of(container)}\n\nThey were asked what they'd do if today were their last " +
                        "day, and answered:\n${input.trim()}\n\nCompare that honestly with how they actually spend their days " +
                        "according to the data. Name the gap in one paragraph, then one change worth making this week. Gentle, " +
                        "serious, never preachy." }
                }) { Text(if (busy) "Thinking…" else "Compare with my real days") }
            }
        }
        out?.let { Card { Text(it, style = MaterialTheme.typography.bodyLarge) } }
        Spacer(Modifier.height(60.dp))
    }
}

// ── Automation rules: if this, then that, across every module ────────────────
data class Rule(val id: Long, val metric: String, val op: String, val value: Double, val action: String, val text: String)

object Rules {
    private fun f(c: android.content.Context) = File(c.filesDir, "rules.json")
    fun all(c: android.content.Context): List<Rule> = runCatching { JSONArray(f(c).readText()) }.getOrElse { JSONArray() }
        .let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            Rule(it.optLong("id"), it.optString("metric"), it.optString("op"), it.optDouble("value"),
                it.optString("action"), it.optString("text")) } }
    fun save(c: android.content.Context, list: List<Rule>) = f(c).writeText(JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("metric", it.metric).put("op", it.op)
            .put("value", it.value).put("action", it.action).put("text", it.text)) } }.toString())

    /** Evaluated once a day by the worker, entirely on-device. */
    suspend fun evaluate(c: android.content.Context, container: AppContainer) {
        val rules = all(c); if (rules.isEmpty()) return
        val p = c.getSharedPreferences("rules_state", 0)
        val today = LocalDate.now().toString(); if (p.getString("day", "") == today) return
        val days = runCatching { com.nizam.app.feature.insight.Days.build(container, c, 7) }.getOrElse { return }
        val yesterday = days.lastOrNull { it.date < LocalDate.now() } ?: return
        rules.forEach { r ->
            val v = yesterday.values[r.metric] ?: return@forEach
            val hit = when (r.op) { "<" -> v < r.value; ">" -> v > r.value; else -> v == r.value }
            if (!hit) return@forEach
            when (r.action) {
                "notify" -> com.nizam.app.feature.notify.Notifications.post(c,
                    com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, (270 + r.id % 20).toInt(), "Rule", r.text)
                "task" -> container.taskRepository.add(r.text, null, 1)
                "mission" -> container.taskRepository.add("🎯 " + r.text, null, 2)
            }
        }
        p.edit().putString("day", today).apply()
    }
}

@Composable
fun RulesScreen(container: AppContainer) {
    val c = LocalContext.current
    var rules by remember { mutableStateOf(Rules.all(c)) }
    var metric by remember { mutableStateOf("sleep") }
    var op by remember { mutableStateOf("<") }
    var value by remember { mutableStateOf("6") }
    var action by remember { mutableStateOf("notify") }
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Rules", "If this happens, do that — no coding, runs on your phone")
        Card {
            ChipRow(com.nizam.app.feature.insight.METRICS.keys.toList(), metric, { metric = it },
                labels = { com.nizam.app.feature.insight.METRICS[it] ?: it })
            ChipRow(listOf("<", ">"), op, { op = it }, labels = { if (it == "<") "is under" else "is over" })
            OutlinedTextField(value, { value = it.filter { ch -> ch.isDigit() || ch == '.' } }, singleLine = true,
                label = { Text("Value") }, modifier = Modifier.fillMaxWidth())
            ChipRow(listOf("notify", "task", "mission"), action, { action = it },
                labels = { mapOf("notify" to "Tell me", "task" to "Add a task", "mission" to "Add a priority task")[it] ?: it })
            OutlinedTextField(text, { text = it }, label = { Text("What should it say or add?") }, modifier = Modifier.fillMaxWidth())
            Button(enabled = text.isNotBlank(), onClick = {
                rules = rules + Rule(System.currentTimeMillis(), metric, op, value.toDoubleOrNull() ?: 0.0, action, text.trim())
                Rules.save(c, rules); text = ""; Feedback.show("Rule saved")
            }) { Text("Add rule") }
        }
        rules.forEach { r ->
            Card {
                Text("If ${com.nizam.app.feature.insight.METRICS[r.metric] ?: r.metric} ${if (r.op == "<") "is under" else "is over"} " +
                    "${r.value.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }} > ${r.action}: ${r.text}",
                    style = MaterialTheme.typography.bodyLarge)
                Text("remove", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(role = Role.Button) { rules = rules - r; Rules.save(c, rules) })
            }
        }
        Spacer(Modifier.height(60.dp))
    }
}
