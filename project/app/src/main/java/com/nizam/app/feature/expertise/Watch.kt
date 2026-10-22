package com.nizam.app.feature.expertise

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.nizam.app.core.BarChart
import com.nizam.app.core.Heatmap
import com.nizam.app.core.Point
import com.nizam.app.feature.agent.ApprovalCard
import com.nizam.app.feature.agent.ApprovalInbox
import com.nizam.app.feature.agentic.AgentHalo
import com.nizam.app.feature.flows.Feeds
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.Md
import com.nizam.app.feature.nx.Mind
import com.nizam.app.feature.nx.Nx
import com.nizam.app.feature.nx.ResultActions
import com.nizam.app.feature.nx.Roads
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Share
import com.nizam.app.net.Ai
import com.nizam.app.net.Health
import com.nizam.app.net.Router
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.StatTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

// ───────────────────────────── Heal ─────────────────────────────

/** Heal: finds failing models, keys and stuck jobs, tests them, and fixes what it can (or says exactly how). */
object Heal {
    data class Check(val name: String, val ok: Boolean, val detail: String, val fix: String? = null, val route: String? = null)

    suspend fun run(container: AppContainer, live: Boolean): List<Check> {
        val c = container.appContext
        val out = mutableListOf<Check>()
        val eps = runCatching { Router.endpoints(container.settingsRepository, c, Router.Lane.CHAT) }.getOrDefault(emptyList())
        if (eps.isEmpty()) out += Check("AI keys", false, "No AI provider is set up.", "Add a Gemini, Groq or OpenRouter key", "keys")
        val seen = HashSet<String>()
        for (e in eps) {
            if (!seen.add(e.name + e.key.takeLast(6))) continue
            val rest = Health.resting(e)
            if (!live) { out += Check("${e.name} · ${e.model.substringAfterLast('/')}", rest <= 0, if (rest > 0) "Resting ${rest / 60_000 + 1} min after errors" else "Ready"); continue }
            val t0 = System.currentTimeMillis()
            val r = runCatching { Ai.call(e, "Reply with OK.", null, 8) }
            val ms = System.currentTimeMillis() - t0
            val err = r.exceptionOrNull()?.message.orEmpty()
            out += when {
                r.isSuccess -> Check("${e.name} · ${e.model.substringAfterLast('/')}", true, "Answered in ${"%.1f".format(ms / 1000.0)} s")
                err.contains("401") || err.contains("403") || err.contains("invalid", true) ->
                    Check("${e.name} key", false, "The key was refused.", "Replace the key in Settings › API keys", "keys")
                err.contains("429") || err.contains("quota", true) -> Check("${e.name}", false, "Out of free quota for now; Atlas uses the others meanwhile.", "Nothing to do: it rests and comes back")
                err.contains("404") -> Check("${e.name} · ${e.model}", false, "This model no longer exists.", "Pick another model in Models", "models")
                else -> Check("${e.name}", false, err.take(120).ifBlank { "Didn't answer" })
            }
            EngineLog.record(e.name, e.model, r.isSuccess, ms, err.ifBlank { null })
        }
        // approvals stuck mid-way after a crash go back to waiting
        val stuck = ApprovalInbox.store(c).all().filter { it.optString("state") == "executing" && System.currentTimeMillis() - it.optLong("at") > 10 * 60_000L }
        stuck.forEach { s -> ApprovalInbox.store(c).update(s.optString("id")) { it.put("state", "waiting") } }
        out += Check("Approval inbox", true, if (stuck.isEmpty()) "${ApprovalInbox.pending(c).size} waiting, nothing stuck" else "Put ${stuck.size} stuck items back to waiting")
        // Atlas's own stores: recover any that fail to read
        listOf("expertise_versions", "expertise_examples", "life_laws", "personas", "kb_docs", "mind_runs", "scout_findings", "pulse_log").forEach { n ->
            val st = JsonStore(c, n)
            val ok = runCatching { st.all(); true }.getOrDefault(false)
            if (!ok) { val rec = st.recoverLastGood(); out += Check("Saved data: $n", rec, if (rec) "Recovered the last good copy" else "Couldn't read it") }
        }
        val failsToday = EngineLog.today(c).filter { it.calls > 0 && it.ok * 100 / it.calls < 50 }
        failsToday.forEach { out += Check("${it.provider} today", false, "Only ${it.ok} of ${it.calls} calls answered", "Heal has rested it; test again later") }
        Pulse.emit(if (out.any { !it.ok }) Pulse.Kind.ALERT else Pulse.Kind.DONE, "Heal checked Atlas", "${out.count { !it.ok }} issues", Hub.Tools.label)
        return out
    }
}

@Composable
fun HealScreen(container: AppContainer, open: (String) -> Unit) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var checks by remember { mutableStateOf<List<Heal.Check>?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { checks = runCatching { Heal.run(container, false) }.getOrNull() } }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Button(onClick = { busy = true; scope.launch { checks = runCatching { Heal.run(container, true) }.getOrNull(); busy = false } }, enabled = !busy) {
            Text(if (busy) "Testing every model…" else "Test everything now")
        }
        checks?.let { list ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                StatTile("Healthy", "${list.count { it.ok }}", Modifier.weight(1f))
                StatTile("Needs you", "${list.count { !it.ok }}", Modifier.weight(1f), tint = if (list.any { !it.ok }) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
            list.sortedBy { it.ok }.forEach { ch ->
                ListRow(ch.name, supporting = listOfNotNull(ch.detail, ch.fix).joinToString(" · "),
                    icon = if (ch.ok) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                    tint = if (ch.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    onClick = ch.route?.let { r -> { open(r) } })
            }
        }
        AlsoHere(open, "Self-Check" to "nx/selfcheck", "Self-repair" to "nx/selfrepair", "Brain lab" to "nx/brainlab", "Crash log" to "nx/crashlog", "Engine room" to "nx/engineroom")
    }
}

// ───────────────────────────── Analytics & Economy ─────────────────────────────

@Composable
fun AnalyticsScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    var pulse by remember { mutableStateOf<List<Int>>(emptyList()) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { runCatching { Roads.warm(container) }; pulse = withContext(Dispatchers.IO) { Roads.pulse(ctx, 30) } } }
    val hist = remember { EngineLog.history(ctx) }
    val byDay = hist.groupBy { it.first }.toSortedMap().entries.toList().takeLast(14)
    val byProvider = hist.groupBy { it.second.provider }.map { (p, l) -> Triple(p, l.sumOf { it.second.calls }, l.sumOf { it.second.ok }) }.sortedByDescending { it.second }
    val tools = remember { Mind.counts(ctx).entries.sortedByDescending { it.value }.take(8).mapNotNull { (id, n) -> Nx.find(id)?.let { it.title to n } } }
    val experts = remember { Expertise.topUsed(ctx, 8) }
    val tint = MaterialTheme.colorScheme.primary
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Heading("Your life, logged · 30 days")
        if (pulse.isNotEmpty()) Heatmap(pulse.map { it.toDouble() }, tint, 15)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            StatTile("Entries · 30 days", "${pulse.sum()}", Modifier.weight(1f))
            StatTile("Active days", "${pulse.count { it > 0 }}", Modifier.weight(1f))
        }
        Heading("Atlas's thinking · 14 days")
        if (byDay.isEmpty()) Hint("Model calls appear here from today.")
        else BarChart(byDay.map { (d, l) -> Point(d.takeLast(2), l.sumOf { it.second.calls }.toDouble()) }, tint, 110)
        byProvider.forEach { (p, n, ok) -> ListRow(p, supporting = "$n calls · ${if (n > 0) ok * 100 / n else 0}% answered", icon = Icons.Outlined.Memory) }
        if (experts.isNotEmpty()) { Heading("Expertise used most"); experts.forEach { (e, n) -> ListRow(e.name, supporting = "$n times · ${e.sector}", icon = e.hub.icon, tint = e.hub.tint()) } }
        if (tools.isNotEmpty()) { Heading("Tools opened most"); tools.forEach { (t, n) -> ListRow(t, supporting = "$n opens", icon = Icons.Outlined.TouchApp) } }
        AlsoHere(open, "Dashboards" to "nx/dashboards", "Insights" to "insights", "Understand" to "understand", "Year in numbers" to "nx/yearinnumbers", "Engine room" to "nx/engineroom")
    }
}

/** Zero-cost autopilot: keeps every AI call on free tiers. */
object ZeroCost {
    private fun prefs(c: Context) = c.getSharedPreferences("zero_cost", 0)
    fun on(c: Context?) = c?.let { prefs(it).getBoolean("on", true) } ?: true
    fun set(c: Context, v: Boolean) = prefs(c).edit().putBoolean("on", v).apply()
    /** OpenRouter models without ":free" can cost money; Gemini and Groq keys are free tiers unless billing was added. */
    fun free(e: Router.Endpoint) = !e.url.contains("openrouter.ai") || e.model.contains(":free")
    fun filter(c: Context?, list: List<Router.Endpoint>): List<Router.Endpoint> {
        if (!on(c)) return list
        val f = list.filter { free(it) }
        return f.ifEmpty { list }
    }
    /** Rough daily free limits, for the budget bars. */
    fun dailyLimit(provider: String): Int = when {
        provider.contains("gemini", true) -> 1000
        provider.contains("groq", true) -> 1000
        provider.contains("openrouter", true) -> 50
        else -> 500
    }
}

@Composable
fun EconomyScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var zero by remember { mutableStateOf(ZeroCost.on(ctx)) }
    val today = remember { EngineLog.today(ctx) }
    val month = remember { EngineLog.history(ctx).filter { it.first.startsWith(LocalDate.now().toString().take(7)) } }
    var report by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Group {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Zero-cost autopilot", style = MaterialTheme.typography.titleMedium)
                    Hint("Keeps every AI call on free tiers: paid OpenRouter models are skipped while free ones answer.")
                }
                Switch(zero, { zero = it; ZeroCost.set(ctx, it) })
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            StatTile("Calls this month", "${month.sumOf { it.second.calls }}", Modifier.weight(1f))
            StatTile("Spent", if (zero) "Rs 0" else "—", Modifier.weight(1f), caption = if (zero) "free tiers only" else "check provider billing")
        }
        Heading("Today's free allowance")
        if (today.isEmpty()) Hint("No calls yet today.")
        today.forEach { d ->
            val lim = ZeroCost.dailyLimit(d.provider)
            ListRow(d.provider, supporting = "${d.calls} of about $lim free calls · ${if (d.calls > 0) d.ok * 100 / d.calls else 0}% answered", icon = Icons.Outlined.Savings)
            androidx.compose.material3.LinearProgressIndicator({ (d.calls / lim.toFloat()).coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
        }
        OutlinedButton(onClick = {
            busy = true
            scope.launch {
                runCatching {
                    val byP = month.groupBy { it.second.provider }.map { (p, l) -> "$p: ${l.sumOf { it.second.calls }} calls, ${l.sumOf { it.second.ok }} answered" }
                    Ai.generate(container.settingsRepository, "AI use this month by provider:\n${byP.joinToString("\n")}\nZero-cost autopilot: ${if (zero) "on" else "off"}.\n" +
                        "Write a short monthly AI cost report: what was used, that it stayed free (only if autopilot is on), and one way to make Atlas faster within free limits.", null, 400)
                }.onSuccess { report = it }.onFailure { Feedback.show(it.message ?: "Couldn't write it") }
                busy = false
            }
        }, enabled = !busy) { Text(if (busy) "Writing…" else "Monthly AI cost report") }
        report?.let { Group { Md(it) } }
        AlsoHere(open, "Agent budget" to "nx/agentbudget", "Agent ledger" to "nx/agentledger", "AI routing" to "nx/hybridrouter", "Models" to "models", "API keys" to "keys")
    }
}

// ───────────────────────────── Audit & Approvals ─────────────────────────────

@Composable
fun AuditScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var kind by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    val log = remember(version) { Pulse.log(ctx).all().sortedByDescending { it.optLong("at") } }
    val done = remember(version) { ApprovalInbox.store(ctx).all().filter { it.optString("state") == "done" }.sortedByDescending { it.optLong("at") } }
    val chains = remember(done) { done.filter { it.optString("action") == "add_task" }.groupBy { it.optString("source") + "|" + it.optLong("at") / (10 * 60_000L) }.values.filter { it.isNotEmpty() }.take(5) }
    val fmt = remember { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            StatTile("Events kept", "${log.size}", Modifier.weight(1f))
            StatTile("Actions approved", "${done.size}", Modifier.weight(1f))
            StatTile("Waiting", "${ApprovalInbox.pending(ctx).size}", Modifier.weight(1f))
        }
        if (chains.isNotEmpty()) {
            Heading("Undo a whole chain")
            chains.forEach { chain ->
                ListRow("${chain.size} tasks from ${chain.first().optString("source")}", supporting = fmt.format(java.util.Date(chain.first().optLong("at"))), icon = Icons.Outlined.Undo, trailing = {
                    Text("Undo", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(Space.s))
                }) {
                    scope.launch {
                        val titles = chain.map { it.optJSONObject("args")?.optString("title").orEmpty() }.toSet()
                        val tasks = container.taskRepository.all().first().filter { it.title in titles && it.deletedAt == null && it.completedAt == null }
                        tasks.forEach { container.taskRepository.softDelete(it) }
                        chain.forEach { o -> ApprovalInbox.store(ctx).update(o.optString("id")) { it.put("state", "undone") } }
                        Pulse.emit(Pulse.Kind.CHANGED, "Undid ${tasks.size} tasks", "Chain undo", Hub.Work.label)
                        Feedback.show("Undid ${tasks.size} tasks"); version++
                    }
                }
            }
        }
        Heading("Everything Atlas did")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            Capsule("All", kind == null) { kind = null }
            Pulse.Kind.entries.filter { it != Pulse.Kind.THINKING }.forEach { k -> Capsule(k.name.lowercase().replaceFirstChar { it.uppercase() }, kind == k.name) { kind = k.name } }
        }
        log.filter { kind == null || it.optString("kind") == kind }.take(120).forEach { o ->
            ListRow(o.optString("title"), supporting = listOf(fmt.format(java.util.Date(o.optLong("at"))), o.optString("detail"), o.optString("hub")).filter { it.isNotBlank() }.joinToString(" · "),
                icon = when (o.optString("kind")) { "ALERT" -> Icons.Outlined.ErrorOutline; "CHANGED" -> Icons.Outlined.Edit; "DONE" -> Icons.Outlined.CheckCircle; else -> Icons.Outlined.Bolt })
        }
        AlsoHere(open, "Approvals" to "nx/approvalinbox", "Tamper-evident log" to "nx/chainlog", "Agent ledger" to "nx/agentledger",
            "Why did Atlas do that" to "nx/whyatlas", "Activity" to "activity", "Pulse" to "nx/pulse")
    }
}

@Composable
fun ApprovalsScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var version by remember { mutableIntStateOf(0) }
    val pending = remember(version, com.nizam.app.feature.agent.Crew.version) { ApprovalInbox.pending(ctx).sortedByDescending { it.optLong("at") } }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        if (pending.isEmpty()) Hint("Nothing is waiting. Anything Atlas wants to change in your life shows up here first.")
        else {
            val low = pending.filter { it.optString("risk") == "low" }
            if (low.size > 1) Button(onClick = { scope.launch { low.forEach { runCatching { ApprovalInbox.decide(container, it.optString("id"), true) } }; version++; Feedback.show("Approved ${low.size}") } }) {
                Text("Approve all ${low.size} low-risk")
            }
        }
        pending.forEach { o ->
            ApprovalCard(container, o, o.optString("risk"), o.optString("why"), o.optJSONObject("args")?.toString(2)?.take(500).orEmpty())
        }
        LaunchedEffect(com.nizam.app.feature.agent.Crew.version) { com.nizam.app.ui.design.guard { version++ } }
        AlsoHere(open, "Audit" to "nx/audit", "Agent teams" to "nx/crew", "App firewall" to "nx/appfirewall")
    }
}

// ───────────────────────────── Scout & Sense ─────────────────────────────

object Scout {
    fun topics(c: Context) = JsonStore(c, "scout_topics")
    fun findings(c: Context) = JsonStore(c, "scout_findings")
    private fun prefs(c: Context) = c.getSharedPreferences("scout", 0)

    suspend fun run(container: AppContainer): Int {
        val c = container.appContext
        var n = 0
        for (t in topics(c).all()) {
            val topic = t.optString("topic")
            val text = runCatching { Ai.generate(container.settingsRepository, "What is new in the last 48 hours about: $topic (focus on Pakistan when relevant)? " +
                "Give 3-5 bullet points with dates and numbers, then 'Why it matters to me' in one line. If nothing new, say 'Nothing new'.", "Be factual; cite sources at the end.", 700, useWeb = true) }.getOrNull() ?: continue
            findings(c).let { st -> st.save((st.all() + JSONObject().put("id", "${System.currentTimeMillis()}$n").put("topic", topic).put("text", text).put("at", System.currentTimeMillis())).takeLast(120)) }
            if (!text.contains("Nothing new", true)) Feeds.add(c, "Scout", "$topic: ${text.replace('\n', ' ').take(300)}")
            n++
        }
        prefs(c).edit().putString("last", LocalDate.now().toString()).apply()
        if (n > 0) Pulse.emit(Pulse.Kind.DONE, "Scout checked $n topics", "", Hub.Learn.label)
        return n
    }

    /** Once a day in the background, after 7 am. */
    suspend fun daily(c: Context) {
        if (java.time.LocalTime.now().hour < 7 || prefs(c).getString("last", "") == LocalDate.now().toString() || topics(c).all().isEmpty()) return
        val container = (c.applicationContext as? com.nizam.app.NizamApplication)?.container ?: return
        withContext(Router.quiet.asContextElement(true)) { runCatching { run(container) } }
    }
}

@Composable
fun ScoutScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var version by remember { mutableIntStateOf(0) }
    val topics = remember(version) { Scout.topics(ctx).all() }
    val found = remember(version) { Scout.findings(ctx).all().sortedByDescending { it.optLong("at") }.take(20) }
    var t by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Hint("Scout watches topics for you once a day and files what's new where Atlas can use it (Today, Oracle, Sense).")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(t, { t = it }, Modifier.weight(1f), placeholder = { Text("A topic (petrol price, my field, a team…)") }, singleLine = true)
            Button(onClick = { Scout.topics(ctx).add(JSONObject().put("topic", t.trim())); t = ""; version++ }, Modifier.padding(start = Space.s), enabled = t.isNotBlank()) { Text("Watch") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            topics.forEach { o -> Capsule("${o.optString("topic")}  ✕", true) { Scout.topics(ctx).remove(o.optString("id")); version++ } }
        }
        Button(onClick = { busy = true; scope.launch { runCatching { Scout.run(container) }.onFailure { Feedback.show(it.message ?: "Scout stopped") }; version++; busy = false } },
            enabled = topics.isNotEmpty() && !busy) { Text(if (busy) "Scouting…" else "Scout now") }
        found.forEach { f -> Group { Text(f.optString("topic"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary); Md(f.optString("text")) } }
        AlsoHere(open, "News" to "news", "My newspaper" to "nx/newspaper", "Website watch" to "nx/webwatch", "News side by side" to "nx/newscompare", "Pakistan" to "loadshedding")
    }
}

/** Sense: Atlas notices what changed in your own data and in what Scout found, and says it calmly: three at most, most important first. */
object Sense {
    data class Signal(val title: String, val detail: String, val weight: Int, val route: String)

    fun read(c: Context): List<Signal> {
        val i = Roads.items(c, 200)
        val L = LifeStats
        val out = mutableListOf<Signal>()
        val week = L.spent(i, 7); val month = L.spent(i, 30)
        if (month > 0 && week > month / 30 * 7 * 1.4) out += Signal("Spending is running high", "This week ${L.rs(week)}, about ${((week / (month / 30 * 7) - 1) * 100).toInt()}% above your usual week.", 3, "finance")
        val p7 = L.prayerRate(i, 7); val p30 = L.prayerRate(i, 30)
        if (p7 != null && p30 != null && p7 < p30 - 12) out += Signal("Prayers slipped this week", "$p7% this week against $p30% this month.", 3, "prayer")
        val m7 = L.moodAvg(i, 7); val m30 = L.moodAvg(i, 30)
        if (m7 != null && m30 != null && m7 < m30 - 0.6) out += Signal("Mood is lower than usual", "%.1f this week against %.1f this month. Be gentle with yourself.".format(m7, m30), 2, "nx/calmbuddy")
        if (L.count(i, "Workouts", 30) + L.count(i, "Cardio", 30) > 3 && L.count(i, "Workouts", 7) + L.count(i, "Cardio", 7) == 0) out += Signal("No training this week", "You trained in the last month but not in the last 7 days.", 1, "gym")
        val (open, _) = L.tasks(i)
        if (open > 25) out += Signal("$open tasks are open", "A short clear-out would lighten the load.", 1, "tasks")
        if (L.count(i, "Habits", 3) == 0 && L.count(i, "Habits", 30) > 10) out += Signal("Habits paused for 3 days", "Pick the easiest one back up today.", 2, "habits2")
        i.filter { it.source == "Scout" && System.currentTimeMillis() - it.ts < 86_400_000L }.take(2).forEach { out += Signal("New from Scout", it.text.take(140), 1, "nx/scout") }
        return out.sortedByDescending { it.weight }
    }

    private fun prefs(c: Context) = c.getSharedPreferences("sense", 0)

    /** At most one calm notification a day, only for something that matters. */
    fun daily(c: Context) {
        if (prefs(c).getString("last", "") == LocalDate.now().toString() || java.time.LocalTime.now().hour !in 10..20) return
        val top = runCatching { read(c) }.getOrDefault(emptyList()).firstOrNull { it.weight >= 3 } ?: return
        prefs(c).edit().putString("last", LocalDate.now().toString()).apply()
        runCatching { com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 4710, top.title, top.detail) }
        Pulse.emit(Pulse.Kind.ALERT, top.title, top.detail)
    }
}

@Composable
fun SenseScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    var signals by remember { mutableStateOf<List<Sense.Signal>?>(null) }
    var all by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { runCatching { Roads.warm(container) }; signals = withContext(Dispatchers.IO) { Sense.read(ctx) } } }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        val s = signals
        if (s == null) com.nizam.app.ui.components.Thinking(label = "Noticing")
        else if (s.isEmpty()) Hint("Nothing needs your attention. Atlas keeps watching quietly.")
        else {
            (if (all) s else s.take(3)).forEach { x -> ListRow(x.title, supporting = x.detail, icon = if (x.weight >= 3) Icons.Outlined.PriorityHigh else Icons.Outlined.Visibility) { open(x.route) } }
            if (s.size > 3 && !all) OutlinedButton(onClick = { all = true }) { Text("Show ${s.size - 3} more") }
        }
        Hint("Calm rules: three at most, most important first, and at most one notification a day.")
        AlsoHere(open, "Noticer" to "nx/noticer", "Radar" to "radar", "Stress signals" to "nx/stresssignals", "Attention" to "attention", "Scout" to "nx/scout")
    }
}

// ───────────────────────────── Channels, Gateway, Portal, Voice ─────────────────────────────

@Composable
fun ChannelsScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var incoming by remember { mutableStateOf("") }
    var intent by remember { mutableStateOf("") }
    var channel by remember { mutableStateOf("WhatsApp") }
    var draft by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val drafts = remember { JsonStore(ctx, "channel_drafts") }
    var version by remember { mutableIntStateOf(0) }
    val past = remember(version) { drafts.all().sortedByDescending { it.optLong("at") }.take(8) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Hint("Paste or share a message into Atlas; it drafts the reply in your voice. You always send it yourself: Atlas never sends SMS or messages on its own.")
        OutlinedTextField(incoming, { incoming = it }, Modifier.fillMaxWidth(), label = { Text("The message you got") }, minLines = 3)
        OutlinedTextField(intent, { intent = it }, Modifier.fillMaxWidth(), label = { Text("What you want to say (optional)") }, minLines = 1)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            listOf("WhatsApp", "SMS", "Email", "Messenger", "LinkedIn").forEach { ch -> Capsule(ch, channel == ch) { channel = ch } }
        }
        Button(onClick = {
            busy = true
            scope.launch {
                runCatching {
                    val e = Expertise.pick(ctx, incoming + " " + intent)
                    val voice = runCatching { com.nizam.app.feature.nx.Specials.soul(ctx) }.getOrDefault("")
                    Ai.generate(container.settingsRepository, "Message received:\n$incoming\n\nWhat I want to say: ${intent.ifBlank { "reply well" }}\nChannel: $channel",
                        "Draft the user's reply in their own voice for $channel (short for WhatsApp/SMS, a subject line for email). Match the language of the message (Urdu, Roman Urdu or English). " +
                            "Output only the reply.${if (voice.isNotBlank()) "\nTheir style: $voice" else ""}" + (e?.let { "\n" + Expertise.block(it) } ?: ""), 500)
                }.onSuccess { d -> draft = d; drafts.add(JSONObject().put("in", incoming.take(300)).put("out", d).put("channel", channel).put("at", System.currentTimeMillis())); version++ }
                    .onFailure { Feedback.show(it.message ?: "Couldn't draft") }
                busy = false
            }
        }, enabled = incoming.isNotBlank() && !busy) { Text(if (busy) "Drafting…" else "Draft my reply") }
        draft?.let { d ->
            Group { Md(d) }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Button(onClick = { Share.text(ctx, d, "Reply") }) { Text("Send it myself") }
                OutlinedButton(onClick = { incoming = d; draft = null }) { Text("Rework") }
            }
        }
        if (past.isNotEmpty()) { Heading("Recent drafts"); past.forEach { o -> ListRow(o.optString("out").take(90), supporting = o.optString("channel"), icon = Icons.Outlined.Drafts) { draft = o.optString("out") } } }
        AlsoHere(open, "Reply coach" to "nx/replycoach", "Cool-down draft" to "nx/cooldownqueue", "Proxy reply" to "nx/proxyreply", "Messenger" to "nx/atlaschat",
            "Talk to my apps" to "nx/appchat", "Unified inbox" to "uniinbox")
    }
}

@Composable
fun GatewayScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val servers = remember { com.nizam.app.feature.mcp.Mcp.servers(ctx) }
    val tools = remember { mutableStateOf<Map<String, Int>>(emptyMap()) }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Hint("Every connector and MCP tool is open to every kind of expertise: in chat, Nexus and Swarm, Atlas can call them, always through approvals and the firewall.")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            StatTile("MCP servers", "${servers.size}", Modifier.weight(1f))
            StatTile("Switched on", "${servers.count { it.enabled }}", Modifier.weight(1f))
            StatTile("Tools", if (tools.value.isEmpty()) "—" else "${tools.value.values.sum()}", Modifier.weight(1f))
        }
        if (servers.isEmpty()) Hint("No MCP servers yet. Add one in MCP, or connect apps in Connectors.")
        servers.forEach { s -> ListRow(s.name, supporting = "${s.url.take(60)} · ${if (s.enabled) "on" else "off"}${tools.value[s.name]?.let { " · $it tools" } ?: ""}", icon = Icons.Outlined.Cable) }
        OutlinedButton(onClick = {
            busy = true
            scope.launch {
                tools.value = servers.filter { it.enabled }.associate { s -> s.name to runCatching { com.nizam.app.feature.mcp.Mcp.tools(s, true).size }.getOrDefault(0) }
                busy = false
            }
        }, enabled = servers.isNotEmpty() && !busy) { Text(if (busy) "Checking…" else "Check every server") }
        AlsoHere(open, "MCP" to "nx/mcpmap", "Connectors" to "nx/connectors", "Atlas tools for other agents" to "nx/toolserver", "Laptop" to "nx/laptop", "Playground" to "playground")
    }
}

/** Portal: export expertise, readings and runs as files you can share; import expertise others share with you. */
@Composable
fun PortalScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    var paste by remember { mutableStateOf("") }
    val mine = remember { Expertise.custom(ctx) }
    val runs = remember { Record.runs(ctx).all().sortedByDescending { it.optLong("at") }.take(10) }
    fun html(title: String, body: String): File {
        val esc = body.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
        val f = File(ctx.cacheDir, "atlas_${System.currentTimeMillis()}.html")
        f.writeText("<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'><title>$title</title>" +
            "<body style='font-family:sans-serif;max-width:720px;margin:24px auto;padding:0 16px;line-height:1.5;background:#0f1115;color:#e8e6e1'>" +
            "<h1 style='color:#e9c46a'>$title</h1><div>$esc</div><p style='opacity:.5;margin-top:40px'>Made with Atlas</p></body>")
        return f
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Heading("Share your expertise")
        if (mine.isEmpty()) Hint("Expertise you write in the Studio or Forge can be shared from here.")
        mine.forEach { e ->
            ListRow(e.name, supporting = e.role, icon = Icons.Outlined.IosShare) {
                val o = JSONObject().put("atlas_expertise", 1).put("name", e.name).put("role", e.role).put("prompt", e.prompt ?: "").put("heavy", e.heavy)
                    .put("examples", org.json.JSONArray(Training.of(ctx, e.id).take(5).map { JSONObject().put("q", it.optString("q")).put("a", it.optString("a")) }))
                Share.text(ctx, o.toString(), "Atlas expertise: ${e.name}")
            }
        }
        Heading("Import expertise")
        OutlinedTextField(paste, { paste = it }, Modifier.fillMaxWidth(), label = { Text("Paste what someone shared") }, minLines = 3)
        Button(onClick = {
            runCatching {
                val o = JSONObject(paste.trim())
                val e = Expertise.saveCustom(ctx, null, o.getString("name"), o.optString("role"), o.optString("prompt"), o.optBoolean("heavy"))
                o.optJSONArray("examples")?.let { a -> for (k in 0 until a.length()) a.optJSONObject(k)?.let { x -> Training.add(ctx, e.id, x.optString("q"), x.optString("a")) } }
                e.name
            }.onSuccess { Feedback.show("Atlas can now think as $it"); paste = "" }.onFailure { Feedback.show("That isn't shared Atlas expertise") }
        }, enabled = paste.isNotBlank()) { Text("Import") }
        if (runs.isNotEmpty()) {
            Heading("Share a page")
            runs.forEach { r -> ListRow("${r.optString("kind")}: ${r.optString("q").take(70)}", icon = Icons.Outlined.Article) {
                Share.file(ctx, html("${r.optString("kind")} · ${r.optString("q").take(60)}", r.optString("text")), "text/html") } }
        }
        AlsoHere(open, "Portable Atlas" to "nx/export", "Deliverables" to "deliverables", "Atlas Circle" to "atlas-circle", "Export my logs" to "nx/exportlogs")
    }
}

@Composable
fun VoiceHubScreen(container: AppContainer, open: (String) -> Unit) {
    var chosen by remember { mutableStateOf(emptyList<Expertise.Expert>()) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Hint("Talk to any kind of expertise hands-free, in Urdu or English. Pick one, then speak; Atlas answers aloud as that expert.")
        ExpertPicker(chosen, 1) { chosen = it }
        Button(onClick = { Expertise.pin(chosen.firstOrNull()); open("voice") }) { Text(if (chosen.isEmpty()) "Talk to Atlas" else "Talk to ${chosen.first().name}") }
        Hint("The expertise stays with the conversation until you open another desk or clear it here.")
        OutlinedButton(onClick = { Expertise.pin(null); chosen = emptyList() }) { Text("Clear the expertise") }
        AlsoHere(open, "Voice" to "voice", "Conversation mode" to "nx/talk", "Live subtitles" to "nx/livesubs", "Voice to draft" to "nx/voicedraft",
            "Offline voice" to "nx/offlinevoice", "Translator" to "nx/offlinetranslate")
    }
}

val WatchSpecs: List<com.nizam.app.feature.nx.Spec> get() = listOf(
    ScreenSpec("heal", "Heal", "Finds failing models, keys and stuck jobs, and fixes them", Icons.Outlined.Healing, Hub.Tools,
        "heal fix repair diagnose broken models keys health check") { c, o -> HealScreen(c, o) },
    ScreenSpec("analytics", "Analytics", "Your life logged and Atlas's own thinking, side by side", Icons.Outlined.Insights, Hub.Tools,
        "analytics usage stats charts dashboard ai calls") { c, o -> AnalyticsScreen(c, o) },
    ScreenSpec("economy", "Economy", "AI budgets per provider and the zero-cost autopilot", Icons.Outlined.Savings, Hub.Tools,
        "economy budget tokens cost free tier zero cost autopilot") { c, o -> EconomyScreen(c, o) },
    ScreenSpec("audit", "Audit & Observe", "Everything Atlas did, step by step; undo a whole chain", Icons.Outlined.ReceiptLong, Hub.Tools,
        "audit observe trace log receipts undo chain history") { c, o -> AuditScreen(c, o) },
    ScreenSpec("approvalinbox", "Approvals", "Everything Atlas wants to change, waiting for your yes", Icons.Outlined.TaskAlt, Hub.Tools,
        "approvals approve inbox pending actions firewall") { c, o -> ApprovalsScreen(c, o) },
    ScreenSpec("scout", "Scout", "Watches topics daily and files what's new where Atlas can use it", Icons.Outlined.TravelExplore, Hub.Learn,
        "scout watch topics news monitor feeds daily") { c, o -> ScoutScreen(c, o) },
    ScreenSpec("sense", "Sense", "What changed in your life that's worth knowing, said calmly", Icons.Outlined.Sensors, Hub.Tools,
        "sense alerts notice anomalies signals changes") { c, o -> SenseScreen(c, o) },
    ScreenSpec("channels", "Channels", "Share a message in; Atlas drafts your reply for you to send", Icons.Outlined.Forum, Hub.Social,
        "channels whatsapp sms email reply draft share message") { c, o -> ChannelsScreen(c, o) },
    ScreenSpec("gateway", "Gateway & MCP", "Every connector and MCP tool, open to every expertise", Icons.Outlined.Cable, Hub.Tools,
        "gateway mcp connectors tools api integrations") { c, o -> GatewayScreen(c, o) },
    ScreenSpec("portal", "Portal", "Share expertise and results as files; import what others share", Icons.Outlined.IosShare, Hub.Tools,
        "portal share export import pages html expertise") { c, o -> PortalScreen(c, o) },
    ScreenSpec("voicehub", "Talk to an expert", "Any expertise, hands-free, in Urdu or English", Icons.Outlined.RecordVoiceOver, Hub.Tools,
        "voice talk expert hands free speak urdu") { c, o -> VoiceHubScreen(c, o) },
)
