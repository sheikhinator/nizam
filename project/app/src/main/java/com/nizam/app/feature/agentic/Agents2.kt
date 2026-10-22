package com.nizam.app.feature.agentic

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.modes.Pins
import com.nizam.app.feature.next.Activity
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.Nx
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.net.Ai
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

// Ideas from the Muse, Grok Bot and Jarvis agents: mission control, trust levels, undo,
// goal to plan, and installable template packs.

private fun ap(c: Context) = c.getSharedPreferences("agentic", 0)

// ───────────────────────── Trust level ─────────────────────────

object AgentTrust {
    /** "strict": ask before every outside action. "normal": each screen decides. "trusted": reversible actions run alone; messages, calls, payments and deletions still wait. */
    fun level(c: Context): String = ap(c).getString("trust_level", if (ap(c).getBoolean("trust_strict", false)) "strict" else "normal").orEmpty()
    fun setLevel(c: Context, v: String) = ap(c).edit().putString("trust_level", v).putBoolean("trust_strict", v == "strict").apply()
    fun strict(c: Context) = level(c) == "strict"
    fun setStrict(c: Context, v: Boolean) = setLevel(c, if (v) "strict" else "normal")
    fun effective(c: Context, requested: Boolean) = if (strict(c)) false else requested
    /** A read-only agent may look things up but can never act outside Atlas, whatever approval it is given. */
    fun readOnly(c: Context, agent: String) = ap(c).getBoolean("ro_$agent", false)
    fun setReadOnly(c: Context, agent: String, v: Boolean) = ap(c).edit().putBoolean("ro_$agent", v).apply()

    private val HIGH_RISK = setOf("call", "send_sms", "email", "reply_notification", "set_pin", "disable_module", "delete_module", "clear_module_entries")
    /** In trusted mode, may this action run without asking? Never for anything that speaks for you, spends or destroys. */
    fun mayActAlone(c: Context, name: String, args: JSONObject): Boolean =
        level(c) == "trusted" && name !in HIGH_RISK && !name.startsWith("delete_") &&
            !(name == "open_in_app" && args.optString("action") in setOf("send", "message", "call", "pay"))
}

// ───────────────────────── Undo for things the agent did ─────────────────────────

object ActionUndo {
    data class Snap(val txn: Long, val task: Long)
    private fun store(c: Context) = JsonStore(c, "undolog")

    suspend fun snap(container: AppContainer): Snap = Snap(
        runCatching { container.financeRepository.recent().first().maxOfOrNull { it.id } }.getOrNull() ?: 0L,
        runCatching { container.taskRepository.all().first().maxOfOrNull { it.id } }.getOrNull() ?: 0L)

    /** Call right after an action: whatever record is new since [s] becomes undoable, and the action is sealed in the agent log. */
    suspend fun record(container: AppContainer, s: Snap, label: String) {
        val c = container.appContext
        runCatching {
            val t = container.financeRepository.recent().first().firstOrNull { it.id > s.txn }
            val k = container.taskRepository.all().first().firstOrNull { it.id > s.task }
            when {
                t != null -> store(c).add(JSONObject().put("t", System.currentTimeMillis()).put("kind", "txn").put("rid", t.id).put("label", label).put("undone", false))
                k != null -> store(c).add(JSONObject().put("t", System.currentTimeMillis()).put("kind", "task").put("rid", k.id).put("label", label).put("undone", false))
            }
            val all = store(c).all(); if (all.size > 40) all.sortedBy { it.optLong("t") }.take(all.size - 40).forEach { store(c).remove(it.optString("id")) }
        }
        Activity.log(c, "Atlas", label, true)
    }

    fun recent(c: Context) = runCatching { store(c).all().sortedByDescending { it.optLong("t") }.take(8) }.getOrDefault(emptyList())

    suspend fun undo(container: AppContainer, e: JSONObject): Boolean {
        val c = container.appContext
        val ok = runCatching {
            when (e.optString("kind")) {
                "txn" -> container.financeRepository.recent().first().firstOrNull { it.id == e.optLong("rid") }?.let { container.financeRepository.remove(it); true } ?: false
                "task" -> container.taskRepository.all().first().firstOrNull { it.id == e.optLong("rid") }?.let { container.taskRepository.softDelete(it); true } ?: false
                else -> false
            }
        }.getOrDefault(false)
        if (ok) {
            store(c).save(store(c).all().map { if (it.optString("id") == e.optString("id")) it.put("undone", true) else it })
            Activity.log(c, "Atlas", "Undid: ${e.optString("label")}", true)
        }
        return ok
    }
}

// ───────────────────────── Mission control ─────────────────────────

@Composable
private fun MissionControlScreen(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var tick by remember { mutableStateOf(0) }
    var level by remember { mutableStateOf(AgentTrust.level(c)) }
    val lastSeen = remember { ap(c).getLong("mc_seen", 0L) }
    val since = remember(tick) { Activity.all(c).filter { it.optLong("t") > lastSeen }.sortedByDescending { it.optLong("t") }.take(30) }
    val undo = remember(tick) { ActionUndo.recent(c) }
    var explain by remember { mutableStateOf<Pair<String, String>?>(null) }
    var msg by remember { mutableStateOf("") }
    val fmt = remember { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { ap(c).edit().putLong("mc_seen", System.currentTimeMillis()).apply() } }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card {
            Text("How much your agents may do alone", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = level == "strict", onClick = { level = "strict"; AgentTrust.setLevel(c, "strict") }, label = { Text("Always ask") })
                FilterChip(selected = level == "normal", onClick = { level = "normal"; AgentTrust.setLevel(c, "normal") }, label = { Text("Normal") })
                FilterChip(selected = level == "trusted", onClick = { level = "trusted"; AgentTrust.setLevel(c, "trusted") }, label = { Text("Trusted") })
            }
            Text(when (level) {
                "strict" -> "Agents prepare anything that changes things outside Atlas and wait for your yes, even if a screen has Auto switched on."
                "trusted" -> "Agents may do reversible things on their own. Messages, calls, payments and deletions always wait for your yes."
                else -> "Agents follow each screen's own setting. Messages, calls and payments wait for your yes unless Auto is on."
            }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("Since you last looked (${since.size})", style = MaterialTheme.typography.titleSmall)
        if (since.isEmpty()) Text("Nothing new. Agents have been quiet.", style = MaterialTheme.typography.bodySmall)
        since.forEach { a ->
            Card {
                Text((if (a.optBoolean("ok")) "✓ " else "✕ ") + a.optString("a") + " · " + fmt.format(java.util.Date(a.optLong("t"))), style = MaterialTheme.typography.labelMedium)
                Text(a.optString("s"), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { scope.launch {
                    explain = a.optString("a") to "Thinking…"
                    explain = a.optString("a") to runCatching { Ai.generate(container.settingsRepository,
                        "An assistant app logged this action: \"${a.optString("a")}: ${a.optString("s")}\" (success: ${a.optBoolean("ok")}).\nIn 3 short lines say what was done, the most likely reason it was done, and how to reverse it. Say plainly if the reason is a guess.",
                        "You explain an assistant's logged action honestly. You only know what the log says.", 300) }.getOrElse { "Couldn't explain: ${it.message}" }
                } }) { Text("Explain") }
            }
        }
        explain?.let { (t, a) -> Card { Text("What and why: $t", style = MaterialTheme.typography.titleSmall); SelectionContainer { Text(a, style = MaterialTheme.typography.bodyMedium) } } }
        Text("Undo", style = MaterialTheme.typography.titleSmall)
        if (undo.isEmpty()) Text("Expenses, income and tasks added from Ask › Do can be undone here.", style = MaterialTheme.typography.bodySmall)
        undo.forEach { e ->
            Card { Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.optString("label"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (e.optBoolean("undone")) Text("undone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else TextButton(onClick = { scope.launch { msg = if (ActionUndo.undo(container, e)) "Undone." else "Couldn't undo that one (it may already be gone)."; tick++ } }) { Text("Undo") }
            } }
        }
        if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TextButton(onClick = { open("nx/agentledger") }) { Text("Ledger") }
            TextButton(onClick = { open("nx/chainlog") }) { Text("Sealed log") }
            TextButton(onClick = { open("nx/hybridrouter") }) { Text("Routing") }
        }
    }
}

// ───────────────────────── Goal to plan ─────────────────────────

@Composable
private fun GoalPlanScreen(container: AppContainer, open: (String) -> Unit) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var goal by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("30") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    val steps = remember { mutableStateListOf<Triple<String, Int, Boolean>>() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Say a goal. Atlas breaks it into at most 8 concrete steps with dates. You review them, then add the ones you want as tasks.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(goal, { goal = it }, label = { Text("e.g. Renew my passport") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(days, { days = it.filter { ch -> ch.isDigit() }.take(3) }, label = { Text("Finish within (days)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = goal.isNotBlank() && !busy, onClick = {
            busy = true; msg = ""; steps.clear()
            scope.launch {
                AgentHalo.begin("Planning: ${goal.take(40)}")
                val o = runCatching { StructuredAi.obj(container,
                    "Goal: \"$goal\". Deadline: ${days.ifBlank { "30" }} days from today. Break it into at most 8 concrete, physical steps in order. steps is an array of {\"title\": short imperative sentence, \"day\": whole number of days from today, between 0 and ${days.ifBlank { "30" }}}. title is the plan name.",
                    listOf("title", "steps"), "You make realistic plans for someone in Pakistan. Name real offices, forms and documents where they apply.", 700) }.getOrNull()
                AgentHalo.end()
                busy = false
                val arr = o?.optJSONArray("steps")
                if (arr == null || arr.length() == 0) msg = "Couldn't make a plan from that. Try rewording the goal."
                else for (i in 0 until minOf(8, arr.length())) { val s = arr.optJSONObject(i) ?: continue; val t = s.optString("title"); if (t.isNotBlank()) steps.add(Triple(t, s.optInt("day", i * 3).coerceIn(0, 365), true)) }
            }
        }) { Text(if (busy) "Planning…" else "Plan it") }
        if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        steps.forEachIndexed { i, (t, d, on) ->
            Card { Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(on, { steps[i] = Triple(t, d, it) })
                Column(Modifier.weight(1f)) { Text(t, style = MaterialTheme.typography.bodyMedium); Text(LocalDate.now().plusDays(d.toLong()).toString(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } }
        }
        if (steps.isNotEmpty()) {
            HoldToConfirm("Hold to add ${steps.count { it.third }} tasks") {
                scope.launch {
                    val chosen = steps.filter { it.third }
                    chosen.forEachIndexed { n, (t, d, _) ->
                        AgentHalo.begin("Adding step ${n + 1} of ${chosen.size}")
                        runCatching { container.taskRepository.add(t, LocalDate.now().plusDays(d.toLong()).atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), 1) }
                        AgentHalo.end()
                    }
                    Activity.log(container.appContext, "Goal plan", "Added ${chosen.size} tasks for: ${goal.take(80)}", true)
                    steps.clear(); msg = "Added ${chosen.size} tasks. They are in your task list."
                }
            }
            TextButton(onClick = { open(com.nizam.app.ui.nav.Routes.TASKS) }) { Text("Open my tasks") }
        }
    }
}

// ───────────────────────── Template packs ─────────────────────────

private data class Pack(val id: String, val name: String, val blurb: String, val tools: List<String>)

private val PACKS = listOf(
    Pack("student", "Student", "Past papers, exam plan, vocabulary, tutor and a focus sprint", listOf("pastpaper", "examplan", "vocab", "tutor", "sprint")),
    Pack("shop", "Shop owner", "Khata, invoices, margin, price book and client profit", listOf("shopkhata", "invoice", "margin", "pricebook", "clientprofit")),
    Pack("ramadan", "Ramadan", "Fasting log, sadqa, zakat, du'a circle and Eid planner", listOf("fasting", "sadqa", "zakatquick", "duacircle", "eidplan")),
    Pack("parent", "New parent", "Baby log, growth, bedtime stories, medicines and pregnancy", listOf("babylog", "kidsgrowth", "kidsstory", "meds", "pregnancy")),
    Pack("freelance", "Freelancer", "Earnings, invoices, scoper, money owed and a time ledger", listOf("freelance", "invoice", "scoper", "receivables", "timeledger")),
    Pack("traveller", "Traveller", "Trip autopilot, readiness, wallet, walking guide and translator", listOf("tripplan", "tripready", "tripwallet", "cityguide", "offlinetranslate")),
)

@Composable
private fun PacksScreen(open: (String) -> Unit) {
    val c = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("A pack pins its tools to your quick-action bar in one tap, so a whole routine is one row away. Pins can be removed any time.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        PACKS.forEach { p ->
            val routes = p.tools.filter { Nx.find(it) != null }.map { "nx/$it" }
            val installed = remember(tick) { routes.isNotEmpty() && routes.all { Pins.isPinned(c, it) } }
            Card {
                Text(p.name, style = MaterialTheme.typography.titleSmall)
                Text(p.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(routes.joinToString(" · ") { r -> Nx.find(r.removePrefix("nx/"))?.title ?: r }, style = MaterialTheme.typography.labelSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (installed) TextButton(onClick = { routes.forEach { if (Pins.isPinned(c, it)) Pins.togglePin(c, it) }; tick++ }) { Text("Remove pins") }
                    else Button(onClick = { routes.forEach { if (!Pins.isPinned(c, it)) Pins.togglePin(c, it) }; tick++ }) { Text("Install") }
                    routes.firstOrNull()?.let { r -> TextButton(onClick = { open(r) }) { Text("Try first tool") } }
                }
            }
        }
        Text("The quick-action bar holds 8 pins, so a new pack may push older pins out.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

object Agents2Specs {
    val all = listOf(
        ScreenSpec("missioncontrol", "Mission control", "What your agents did, why, undo, and how much they may do alone", Icons.Outlined.Dashboard, Hub.Tools,
            "mission control agents overnight undo trust explain approvals activity") { c, open -> MissionControlScreen(c, open) },
        ScreenSpec("goalplan", "Goal to plan", "Say a goal; get up to 8 dated steps, then add them as tasks", Icons.Outlined.Flag, Hub.Work,
            "goal plan steps tasks breakdown project passport muse") { c, open -> GoalPlanScreen(c, open) },
        ScreenSpec("packs", "Template packs", "Install a ready-made set of tools for students, shops, parents, Ramadan and more", Icons.Outlined.Inventory2, Hub.Tools,
            "templates packs bundles install student shop parent ramadan freelancer traveller") { _, open -> PacksScreen(open) },
    )
}
