package com.nizam.app.feature.finale

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.feature.insight.Days
import com.nizam.app.feature.insight.METRICS
import com.nizam.app.ui.components.Feedback
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ── 1. Teach Atlas: corrections it keeps forever ─────────────────────────────
object Teach {
    private fun f(c: Context) = File(c.filesDir, "teach.json")
    fun all(c: Context): List<String> = runCatching { JSONArray(f(c).readText()) }.getOrElse { JSONArray() }
        .let { a -> (0 until a.length()).map { a.optString(it) } }
    fun add(c: Context, lesson: String) = f(c).writeText(JSONArray(all(c) + lesson.trim()).toString())
    fun remove(c: Context, lesson: String) = f(c).writeText(JSONArray(all(c) - lesson).toString())
    /** Handed to every AI, above everything else it knows. */
    fun forAi(c: Context) = all(c).takeIf { it.isNotEmpty() }
        ?.let { "Things they have taught you — follow these exactly, they override your defaults:\n" +
            it.joinToString("\n") { l -> "· $l" } }.orEmpty()
}

// ── 2. Scripts: a sequence you run with one tap ──────────────────────────────
data class Script(val id: Long, val name: String, val steps: List<String>)

object Scripts {
    private fun f(c: Context) = File(c.filesDir, "scripts.json")
    fun all(c: Context): List<Script> = runCatching { JSONArray(f(c).readText()) }.getOrElse { JSONArray() }
        .let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Script(o.optLong("id"), o.optString("name"),
                o.optJSONArray("steps")?.let { s -> (0 until s.length()).map { s.optString(it) } } ?: emptyList()) } }
    fun save(c: Context, list: List<Script>) = f(c).writeText(JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("steps", JSONArray(it.steps))) } }.toString())
}

// ── 3. Ledger of You: one number, and what's moving it ───────────────────────
data class Driver(val label: String, val delta: Int, val detail: String)
data class Ledger(val score: Int, val drivers: List<Driver>)

object LedgerOfYou {
    /**
     * A single honest index, 0-100. Each tracked area contributes what it's worth, measured against
     * your own last 90 days rather than anyone's ideal — so it says whether YOU are drifting or gaining.
     */
    suspend fun compute(container: AppContainer, c: Context): Ledger {
        val days = runCatching { Days.build(container, c, 90) }.getOrElse { emptyList() }
        if (days.size < 7) return Ledger(0, listOf(Driver("Not enough history", 0, "Log a week and this fills in")))
        val recent = days.takeLast(14)
        val baseline = days.dropLast(14).ifEmpty { days }
        val weights = mapOf("prayers" to 22, "sleep" to 18, "focus" to 16, "sets" to 12,
            "study" to 12, "mood" to 12, "steps" to 8)
        var score = 0.0
        val drivers = mutableListOf<Driver>()
        weights.forEach { (metric, weight) ->
            val now = recent.mapNotNull { it.values[metric] }.takeIf { it.isNotEmpty() }?.average() ?: return@forEach
            val before = baseline.mapNotNull { it.values[metric] }.takeIf { it.isNotEmpty() }?.average() ?: now
            val best = days.chunked(14).mapNotNull { chunk ->
                chunk.mapNotNull { it.values[metric] }.takeIf { it.isNotEmpty() }?.average() }.maxOrNull() ?: now
            val share = if (best > 0) (now / best).coerceIn(0.0, 1.0) else 0.0
            score += share * weight
            val change = if (before > 0) ((now - before) / before * 100).toInt() else 0
            drivers += Driver(METRICS[metric] ?: metric, change,
                "${"%.1f".format(now)} a day now vs ${"%.1f".format(before)} before")
        }
        return Ledger(score.toInt().coerceIn(0, 100), drivers.sortedByDescending { kotlin.math.abs(it.delta) }.take(5))
    }
}

@Composable
fun FinaleScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var tab by remember { mutableStateOf("Ledger") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        Text("You", fontSize = 34.sp, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 26.dp))
        com.nizam.app.core.ChipRow(listOf("Ledger", "Teach", "Scripts"), tab, { tab = it })
        Spacer(Modifier.height(16.dp))
        when (tab) {
            "Ledger" -> {
                var ledger by remember { mutableStateOf<Ledger?>(null) }
                LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { ledger = runCatching { LedgerOfYou.compute(container, c) }.getOrNull() } }
                val l = ledger
                if (l == null) Text("Working it out…", style = MaterialTheme.typography.bodyMedium)
                else {
                    Text("${l.score}", fontSize = 76.sp, color = MaterialTheme.colorScheme.primary)
                    Text("out of 100, measured against your own best — not anyone else's",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    LinearProgressIndicator(progress = { l.score / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp))
                    Text("WHAT'S MOVING IT", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    l.drivers.forEach { d ->
                        Column(Modifier.fillMaxWidth()) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(d.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(d.detail, style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Text((if (d.delta > 0) "+" else "") + "${d.delta}%",
                                    color = if (d.delta >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                            }
                            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                        }
                    }
                }
            }
            "Teach" -> {
                var lessons by remember { mutableStateOf(Teach.all(c)) }
                var text by remember { mutableStateOf("") }
                Text("Correct Atlas once and it holds forever. These sit above everything else it knows.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    label = { Text("e.g. never suggest the gym before Fajr") })
                Button(enabled = text.isNotBlank(), onClick = {
                    Teach.add(c, text); lessons = Teach.all(c); text = ""; Feedback.show("Taught")
                }) { Text("Teach it") }
                Spacer(Modifier.height(10.dp))
                lessons.forEach { l ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                            Text(l, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                            Text("forget", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.clickable(role = Role.Button) { Teach.remove(c, l); lessons = Teach.all(c) })
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                    }
                }
            }
            else -> {
                var list by remember { mutableStateOf(Scripts.all(c)) }
                var name by remember { mutableStateOf("") }
                var steps by remember { mutableStateOf("") }
                var running by remember { mutableStateOf<Long?>(null) }
                Text("A sequence you do often, run with one tap. One instruction per line.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name, e.g. Friday close-out") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(steps, { steps = it }, label = { Text("Steps, one per line") },
                    modifier = Modifier.fillMaxWidth().height(140.dp))
                Button(enabled = name.isNotBlank() && steps.isNotBlank(), onClick = {
                    list = list + Script(System.currentTimeMillis(), name.trim(),
                        steps.lines().map { it.trim() }.filter { it.isNotBlank() })
                    Scripts.save(c, list); name = ""; steps = ""; Feedback.show("Script saved")
                }) { Text("Save script") }
                Spacer(Modifier.height(10.dp))
                list.forEach { script ->
                    Column(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(script.name, style = MaterialTheme.typography.bodyLarge)
                                Text("${script.steps.size} steps", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(if (running == script.id) "running…" else "run", color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable(role = Role.Button) {
                                    if (running != null) return@clickable
                                    scope.launch {
                                        running = script.id
                                        var done = 0
                                        script.steps.forEach { step ->
                                            runCatching { container.agentLoop.run(step, useWeb = false, autoApprove = true) }
                                                .onSuccess { done++ }
                                        }
                                        running = null
                                        Feedback.show("${script.name}: $done of ${script.steps.size} steps done")
                                    }
                                }.padding(8.dp))
                            Text("✕", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable(role = Role.Button) { list = list - script; Scripts.save(c, list) }.padding(8.dp))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                    }
                }
            }
        }
        Spacer(Modifier.height(70.dp))
    }
}

/** 4. The command bar: one line at the top of Today that takes anything. */
@Composable
fun CommandBar(container: AppContainer, onOpenChat: () -> Unit) {
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<String?>(null) }
    fun send() {
        val message = text.trim()
        if (message.isBlank() || busy) return
        scope.launch {
            busy = true; text = ""; answer = ""
            val c = container.appContext
            val lane = com.nizam.app.net.Router.classify(message, false, false,
                com.nizam.app.feature.mcp.Mcp.servers(c).filter { it.enabled }.map { it.name })
            // Questions stream straight back; only real actions pay for the agent — and they ask first
            val reply = runCatching {
                com.nizam.app.feature.agent.LocalCommands.tryRun(container, message)
                    ?: (if (lane == com.nizam.app.net.Router.Lane.CHAT)
                        com.nizam.app.feature.agent.Converse.chat(container, message) { answer = (answer ?: "") + it } else null)
                    ?: container.agentLoop.run(message, useWeb = false, autoApprove = false).let { out ->
                        out.reply + if (out.pendingApproval.isNotEmpty())
                            "\n\nNeeds your OK: " + out.pendingApproval.joinToString { it.first } + " — open chat to approve." else ""
                    }
            }.getOrElse { "Couldn't do that: ${Feedback.friendly(it)}" }
            answer = reply
            runCatching { container.agentMemory.addTurn(true, message); container.agentMemory.addTurn(false, reply) }
            busy = false
        }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (busy) "Working on it…" else "Ask Atlas, or log anything") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() })
        )
        if (busy && answer.isNullOrBlank()) com.nizam.app.ui.components.Thinking()
        answer?.takeIf { it.isNotBlank() }?.let {
            com.nizam.app.ui.components.ChatText(it, Modifier.padding(top = 8.dp, start = 4.dp, end = 4.dp))
            if (!busy) Row(horizontalArrangement = Arrangement.spacedBy(18.dp), modifier = Modifier.padding(top = 6.dp, start = 4.dp)) {
                Text("Continue in chat", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(role = Role.Button) { onOpenChat() })
                Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(role = Role.Button) { answer = null })
            }
        }
    }
}
