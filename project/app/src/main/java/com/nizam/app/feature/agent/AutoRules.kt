package com.nizam.app.feature.agent

import androidx.compose.material.icons.outlined.AccountTree

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.feature.prayer.PrayerTimes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

/**
 * Automations v2: rules with a trigger (a time relative to a prayer, every few hours, or a new record
 * in one of your modules), AND/OR conditions, quiet windows, an if/else branch, and an approval policy
 * per step. Failed runs wait in a recovery queue; a map shows what depends on what; any prompt with
 * {placeholders} becomes a run form.
 */
object AutoRules {
    data class Cond(val kind: String, val arg: String)   // dow:1,2,3 · hours:9-17 · field:amount>5000 · contains:urgent
    data class Rule(
        val id: Long, val name: String, val enabled: Boolean,
        val trigger: String, val triggerArg: String,        // prayer:Maghrib-30 · every:3 · record:moduleKey
        val match: String, val conds: List<Cond>,           // ALL | ANY
        val quietFrom: Int, val quietTo: Int,               // hours; -1 = none
        val thenPrompt: String, val elsePrompt: String,
        val thenApproval: String, val elseApproval: String, // ask | auto_low | auto_all
        val lastFired: Long = 0L, val lastSeen: Long = 0L,
    )

    private fun f(c: Context) = java.io.File(c.filesDir, "auto_rules.json")
    private fun failF(c: Context) = java.io.File(c.filesDir, "auto_failures.json")

    fun all(c: Context): List<Rule> = runCatching { JSONArray(f(c).readText()) }.getOrElse { JSONArray() }.let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val cs = o.optJSONArray("conds") ?: JSONArray()
            Rule(o.optLong("id"), o.optString("name"), o.optBoolean("on", true), o.optString("trig"), o.optString("targ"),
                o.optString("match", "ALL"), (0 until cs.length()).map { cs.getJSONObject(it) }.map { Cond(it.optString("k"), it.optString("a")) },
                o.optInt("qf", -1), o.optInt("qt", -1), o.optString("then"), o.optString("else"),
                o.optString("tap", "ask"), o.optString("eap", "ask"), o.optLong("fired"), o.optLong("seen"))
        }
    }

    fun save(c: Context, list: List<Rule>) = f(c).writeText(JSONArray(list.map { r ->
        JSONObject().put("id", r.id).put("name", r.name).put("on", r.enabled).put("trig", r.trigger).put("targ", r.triggerArg)
            .put("match", r.match).put("conds", JSONArray(r.conds.map { JSONObject().put("k", it.kind).put("a", it.arg) }))
            .put("qf", r.quietFrom).put("qt", r.quietTo).put("then", r.thenPrompt).put("else", r.elsePrompt)
            .put("tap", r.thenApproval).put("eap", r.elseApproval).put("fired", r.lastFired).put("seen", r.lastSeen)
    }).toString())

    fun upsert(c: Context, r: Rule) = save(c, all(c).filter { it.id != r.id } + r)

    // ---------------------------------------------------------------- failures

    fun failures(c: Context): List<JSONObject> = runCatching { JSONArray(failF(c).readText()) }.getOrElse { JSONArray() }.let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun saveFailures(c: Context, l: List<JSONObject>) = failF(c).writeText(JSONArray(l.takeLast(50)).toString())
    fun addFailure(c: Context, rule: String, prompt: String, approval: String, why: String) =
        saveFailures(c, failures(c) + JSONObject().put("rule", rule).put("prompt", prompt).put("ap", approval).put("why", why).put("at", System.currentTimeMillis()))
    fun dismissFailure(c: Context, at: Long) = saveFailures(c, failures(c).filter { it.optLong("at") != at })

    // ---------------------------------------------------------------- evaluation

    private fun inQuiet(r: Rule, hour: Int) = r.quietFrom >= 0 && r.quietTo >= 0 &&
        (if (r.quietFrom <= r.quietTo) hour in r.quietFrom until r.quietTo else hour >= r.quietFrom || hour < r.quietTo)

    /** A condition against the moment (and the record that triggered it, if any). */
    fun holds(cond: Cond, now: LocalTime, today: LocalDate, record: Map<String, String>?): Boolean = when (cond.kind) {
        "dow" -> cond.arg.split(',').mapNotNull { it.trim().toIntOrNull() }.contains(today.dayOfWeek.value)
        "hours" -> cond.arg.split('-').mapNotNull { it.trim().toIntOrNull() }.let { it.size == 2 && now.hour in it[0] until it[1] }
        "weekend" -> today.dayOfWeek == DayOfWeek.SATURDAY || today.dayOfWeek == DayOfWeek.SUNDAY
        "friday" -> today.dayOfWeek == DayOfWeek.FRIDAY
        "contains" -> record?.values?.any { it.contains(cond.arg, true) } == true
        "field" -> {
            val m = Regex("""^(\w+)\s*(>=|<=|>|<|=)\s*(.+)$""").find(cond.arg.trim())
            if (m == null || record == null) false else {
                val (k, op, v) = m.destructured
                val l = record[k].orEmpty(); val ln = l.toDoubleOrNull(); val rn = v.trim().toDoubleOrNull()
                val cmp = if (ln != null && rn != null) ln.compareTo(rn) else l.compareTo(v.trim(), true)
                when (op) { ">=" -> cmp >= 0; "<=" -> cmp <= 0; ">" -> cmp > 0; "<" -> cmp < 0; else -> cmp == 0 }
            }
        }
        else -> true
    }

    fun passes(r: Rule, record: Map<String, String>?): Boolean {
        if (r.conds.isEmpty()) return true
        val now = LocalTime.now(); val today = LocalDate.now()
        val results = r.conds.map { holds(it, now, today, record) }
        return if (r.match == "ANY") results.any { it } else results.all { it }
    }

    /** Minutes after midnight for "Maghrib-30", "Fajr+15". */
    suspend fun prayerMinute(container: AppContainer, arg: String): Int? = runCatching {
        val m = Regex("""^(\w+)\s*([+-]\s*\d+)?$""").find(arg.trim()) ?: return null
        val name = m.groupValues[1].lowercase(); val off = m.groupValues[2].replace(" ", "").toIntOrNull() ?: 0
        val s = container.settingsRepository
        val t = PrayerTimes.calculate(date = LocalDate.now(), latitude = s.latitude.first(), longitude = s.longitude.first(),
            method = runCatching { PrayerTimes.Method.valueOf(s.prayerMethod.first()) }.getOrElse { PrayerTimes.Method.KARACHI }, hanafiAsr = s.hanafiAsr.first())
        val h = when (name) { "fajr" -> t.fajr; "sunrise" -> t.sunrise; "zuhr", "dhuhr" -> t.dhuhr; "asr" -> t.asr; "maghrib" -> t.maghrib; "isha" -> t.isha; else -> return null }
        (h * 60).toInt() + off
    }.getOrNull()

    /** Called by the background worker (~every 15 minutes). */
    suspend fun tick(c: Context, container: AppContainer) {
        val nowMs = System.currentTimeMillis(); val nowT = LocalTime.now(); val nowMin = nowT.hour * 60 + nowT.minute
        for (r in all(c).filter { it.enabled }) {
            if (inQuiet(r, nowT.hour)) continue
            var record: Map<String, String>? = null
            val due = when (r.trigger) {
                "prayer" -> prayerMinute(container, r.triggerArg)?.let { m -> nowMin in m until m + 20 && nowMs - r.lastFired > 3_600_000L } ?: false
                "every" -> nowMs - r.lastFired >= (r.triggerArg.toIntOrNull() ?: 3).coerceAtLeast(1) * 3_600_000L
                "record" -> {
                    val newest = runCatching { container.dynamicRepository.entries(r.triggerArg).first() }.getOrDefault(emptyList())
                        .filter { it.createdAt > r.lastSeen }.maxByOrNull { it.createdAt }
                    if (newest != null) { record = newest.values() + ("title" to newest.title); upsert(c, r.copy(lastSeen = newest.createdAt)) }
                    newest != null
                }
                else -> false
            }
            if (!due) continue
            fire(c, container, all(c).first { it.id == r.id }, record)
        }
    }

    suspend fun fire(c: Context, container: AppContainer, r: Rule, record: Map<String, String>?, test: Boolean = false): String {
        val ok = passes(r, record)
        val prompt = (if (ok) r.thenPrompt else r.elsePrompt).let { p -> record?.entries?.fold(p) { acc, (k, v) -> acc.replace("{$k}", v) } ?: p }
        val approval = if (ok) r.thenApproval else r.elseApproval
        if (!test) upsert(c, r.copy(lastFired = System.currentTimeMillis()))
        if (prompt.isBlank()) return if (ok) "Conditions met; nothing to do." else "Conditions not met; no else branch."
        return runCatching {
            val (good, result) = if (test) true to "Would run (${approval}): $prompt" else execute(container, prompt, approval, "Rule · ${r.name}")
            if (!good) addFailure(c, r.name, prompt, approval, result)
            else if (!test) runCatching { Notifications.post(c, Notifications.CHANNEL_NUDGE, (330 + r.id % 20).toInt(), "⚙ ${r.name.take(40)}", result.take(300)) }
            (if (ok) "THEN → " else "ELSE → ") + result
        }.getOrElse { addFailure(c, r.name, prompt, approval, it.message ?: "error"); "Failed: ${it.message}" }
    }

    /** Runs one request as Atlas would for a routine, honouring the approval policy for each action. */
    suspend fun execute(container: AppContainer, prompt: String, approval: String, source: String): Pair<Boolean, String> = runCatching {
        val c = container.appContext
        val out = container.agentLoop.run(prompt, useWeb = true, autoApprove = false)
        val extra = mutableListOf<String>()
        for ((name, args) in out.pendingApproval) {
            val v = Reviewer.review(container, name, args, prompt)
            when {
                v.decision == "block" -> extra += "🛑 ${name.replace('_', ' ')} blocked: ${v.why}"
                Reviewer.mayRunAlone(approval, v) -> Actions.run(container, name, args).let { extra += (if (it.ok) "✓ " else "✕ ") + it.summary }
                else -> { ApprovalInbox.add(c, source, name, args, v, prompt); extra += "⏸ ${name.replace('_', ' ')} waits for your OK" }
            }
        }
        true to (out.reply + if (extra.isEmpty()) "" else "\n" + extra.joinToString("\n")).take(600)
    }.getOrElse { false to "Failed: ${it.message}" }

    /** {placeholders} in a prompt become form fields. */
    fun placeholders(prompt: String) = Regex("""\{(\w+)\}""").findAll(prompt).map { it.groupValues[1] }.distinct().toList()
}

@Composable
fun AutoRulesScreen(container: AppContainer) {
    val c = LocalContext.current
    val work = com.nizam.app.ui.design.rememberWorkScope()
    var tab by remember { mutableStateOf("Rules") }
    var rules by remember { mutableStateOf(AutoRules.all(c)) }
    var tick by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Rules", "New", "Recovery", "Map", "Run form").forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t) }) }
        }
        when (tab) {
            "Rules" -> {
                if (rules.isEmpty()) Text("No rules yet. Make one in New.")
                rules.forEach { r ->
                    var out by remember(r.id) { mutableStateOf<String?>(null) }
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(r.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Switch(r.enabled, { on -> AutoRules.upsert(c, r.copy(enabled = on)); rules = AutoRules.all(c) })
                        }
                        Text(describe(r), style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { work.launch { out = AutoRules.fire(c, container, r, null, test = true) } }) { Text("Dry run") }
                            TextButton(onClick = { AutoRules.save(c, rules.filter { it.id != r.id }); rules = AutoRules.all(c) }) { Text("Delete") }
                        }
                        out?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                    }
                }
            }
            "New" -> NewRule(container) { rules = AutoRules.all(c); tab = "Rules" }
            "Recovery" -> {
                val fails = remember(tick) { AutoRules.failures(c) }
                if (fails.isEmpty()) Text("No failed runs. ✓")
                fails.reversed().forEach { f ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text("${f.optString("rule")}: ${f.optString("why").take(140)}", style = MaterialTheme.typography.bodySmall)
                        Row {
                            TextButton(onClick = { work.launch {
                                val (good, result) = AutoRules.execute(container, f.optString("prompt"), f.optString("ap"), "Retry · ${f.optString("rule")}")
                                if (good) AutoRules.dismissFailure(c, f.optLong("at")); tick++
                                com.nizam.app.ui.components.Feedback.show(if (good) "Retried successfully" else "Still failing: ${result.take(80)}")
                            } }) { Text("Retry") }
                            TextButton(onClick = { AutoRules.dismissFailure(c, f.optLong("at")); tick++ }) { Text("Dismiss") }
                        }
                    }
                }
                val routineFails = Schedules.all(c).filter { j -> j.history.firstOrNull()?.ok == false }
                if (routineFails.isNotEmpty()) Text("Routines whose last run failed", style = MaterialTheme.typography.titleSmall)
                routineFails.forEach { j -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(j.title, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { work.launch { Schedules.runOne(c, container, j); tick++ } }) { Text("Run again") }
                } }
            }
            "Map" -> {
                Text("What runs, and what it depends on", style = MaterialTheme.typography.titleSmall)
                rules.forEach { r -> Text("⚙ ${r.name}\n   ← ${describeTrigger(r)}\n   → ${r.thenPrompt.take(60)}${if (r.elsePrompt.isNotBlank()) "\n   ↳ else ${r.elsePrompt.take(60)}" else ""}", style = MaterialTheme.typography.bodySmall) }
                Schedules.all(c).forEach { j -> Text("⏰ ${j.title}\n   ← ${j.cadence} ${"%02d:%02d".format(j.hour, j.minute)} · ${j.kind}${if (j.kind == "skill") " uses skill \"${j.prompt}\"" else ""}", style = MaterialTheme.typography.bodySmall) }
            }
            else -> RunForm(container)
        }
    }
}

private fun describeTrigger(r: AutoRules.Rule) = when (r.trigger) {
    "prayer" -> "at ${r.triggerArg}"; "every" -> "every ${r.triggerArg} h"; "record" -> "new record in ${r.triggerArg}"; else -> r.trigger
}

private fun describe(r: AutoRules.Rule) = buildString {
    append("When: ${describeTrigger(r)}")
    if (r.conds.isNotEmpty()) append(" · if ${if (r.match == "ANY") "any" else "all"} of ${r.conds.joinToString { "${it.kind} ${it.arg}" }}")
    if (r.quietFrom >= 0) append(" · quiet ${r.quietFrom}:00–${r.quietTo}:00")
    append("\nThen (${r.thenApproval}): ${r.thenPrompt.take(80)}")
    if (r.elsePrompt.isNotBlank()) append("\nElse (${r.elseApproval}): ${r.elsePrompt.take(80)}")
}

@Composable
private fun NewRule(container: AppContainer, done: () -> Unit) {
    val c = LocalContext.current
    var name by remember { mutableStateOf("") }
    var trigger by remember { mutableStateOf("prayer") }
    var arg by remember { mutableStateOf("Maghrib-30") }
    var match by remember { mutableStateOf("ALL") }
    var conds by remember { mutableStateOf("") }
    var quiet by remember { mutableStateOf("") }
    var thenP by remember { mutableStateOf("") }; var elseP by remember { mutableStateOf("") }
    var thenAp by remember { mutableStateOf("ask") }; var elseAp by remember { mutableStateOf("ask") }
    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
    Text("Trigger", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("prayer" to "Prayer time", "every" to "Every N hours", "record" to "New record").forEach { (k, l) -> FilterChip(selected = trigger == k, onClick = { trigger = k
            arg = when (k) { "prayer" -> "Maghrib-30"; "every" -> "3"; else -> "" } }, label = { Text(l) }) }
    }
    OutlinedTextField(arg, { arg = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text(when (trigger) { "prayer" -> "e.g. Maghrib-30, Fajr+10"; "every" -> "Hours"; else -> "Module key (see Module views)" }) })
    Text("Conditions (one per line): dow:5 · hours:9-17 · weekend · friday · contains:urgent · field:amount>5000", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("ALL", "ANY").forEach { m -> FilterChip(selected = match == m, onClick = { match = m }, label = { Text("Match $m") }) } }
    OutlinedTextField(conds, { conds = it }, Modifier.fillMaxWidth(), minLines = 2)
    OutlinedTextField(quiet, { quiet = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Quiet window, e.g. 23-7 (optional)") })
    OutlinedTextField(thenP, { thenP = it }, Modifier.fillMaxWidth(), label = { Text("Then do (a request to Atlas; {field} uses the record)") }, minLines = 2)
    ApprovalRow(thenAp) { thenAp = it }
    OutlinedTextField(elseP, { elseP = it }, Modifier.fillMaxWidth(), label = { Text("Else do (optional)") }, minLines = 1)
    ApprovalRow(elseAp) { elseAp = it }
    Button(onClick = {
        val q = quiet.split('-').mapNotNull { it.trim().toIntOrNull() }
        AutoRules.upsert(c, AutoRules.Rule(System.currentTimeMillis(), name.ifBlank { "Rule" }, true, trigger, arg.trim(), match,
            conds.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { l -> l.split(':', limit = 2).let { AutoRules.Cond(it[0].trim(), it.getOrElse(1) { "" }.trim()) } },
            q.getOrElse(0) { -1 }, q.getOrElse(1) { -1 }, thenP.trim(), elseP.trim(), thenAp, elseAp))
        done()
    }, enabled = thenP.isNotBlank() && (trigger == "prayer" || arg.isNotBlank())) { Text("Save rule") }
}

@Composable
private fun ApprovalRow(v: String, set: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("ask" to "Ask me", "auto_low" to "Low-risk alone", "auto_all" to "Reviewer-approved").forEach { (k, l) -> FilterChip(selected = v == k, onClick = { set(k) }, label = { Text(l, style = MaterialTheme.typography.labelSmall) }) }
    }
}

@Composable
private fun RunForm(container: AppContainer) {
    val c = LocalContext.current
    val work = com.nizam.app.ui.design.rememberWorkScope()
    var template by remember { mutableStateOf("Draft a polite reminder to {person} about {thing} due on {date}") }
    val keys = AutoRules.placeholders(template)
    val values = remember { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    var out by remember { mutableStateOf<String?>(null) }
    Text("Write a request with {placeholders}; fill the form and run it. Save good ones as routines.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(template, { template = it }, Modifier.fillMaxWidth(), minLines = 2)
    keys.forEach { k -> OutlinedTextField(values[k].orEmpty(), { values[k] = it }, Modifier.fillMaxWidth(), label = { Text(k) }, singleLine = true) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val prompt = keys.fold(template) { acc, k -> acc.replace("{$k}", values[k].orEmpty()) }
            work.launch { out = AutoRules.execute(container, prompt, "ask", "Run form").second }
        }, enabled = keys.all { !values[it].isNullOrBlank() }) { Text("Run") }
        OutlinedButton(onClick = {
            Schedules.upsert(c, AgentJob(id = System.currentTimeMillis(), kind = "task", prompt = template, cadence = "daily", hour = 9, enabled = false, name = template.take(40)))
            com.nizam.app.ui.components.Feedback.show("Saved as a paused routine. Open Routines to schedule it.")
        }) { Text("Save as routine") }
    }
    out?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
}

object AutoRulesSpecs {
    val all: List<com.nizam.app.feature.nx.Spec> = listOf(
        com.nizam.app.feature.nx.ScreenSpec("autorules", "Automations+", "Rules on prayer times, schedules or new records, with conditions, quiet hours, if/else, approvals and recovery",
            androidx.compose.material.icons.Icons.Outlined.AccountTree, com.nizam.app.ui.design.Hub.Tools,
            "automation rule trigger condition branch if else quiet hours approval failed retry dependency map run form template") { c2, _ -> AutoRulesScreen(c2) }
    )
}
