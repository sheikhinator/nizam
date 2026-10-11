package com.nizam.app.feature.wild

import android.content.Context
import android.view.accessibility.AccessibilityEvent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.Md
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

object AgentPlus {
    val all: List<Spec> = listOf(
        ScreenSpec("watchlearn", "Watch & learn", "Atlas spots things you repeat and offers to automate them", Icons.Outlined.Visibility, Hub.Tools, "watch learn repeat automate suggest skill pattern") { _, o -> WatchLearnScreen(o) },
        ScreenSpec("lifeops", "Overnight Life Ops", "What changed while you slept", Icons.Outlined.NightsStay, Hub.Work, "overnight night report morning reconcile life ops") { c, _ -> LifeOpsScreen(c) },
        ScreenSpec("negotiator", "Negotiator", "Bargains for you — every message waits for your OK", Icons.Outlined.Handshake, Hub.Money, "negotiate bargain haggle price landlord seller olx") { c, _ -> NegotiatorScreen(c) },
        ScreenSpec("pricehunter", "Price hunter", "Watches every shop for what you want", Icons.Outlined.TrackChanges, Hub.Money, "price hunter drop deal daraz olx priceoye alert cheap") { c, _ -> PriceHunterScreen(c) },
        ScreenSpec("debate", "Model debate", "Three AIs answer; see where they disagree", Icons.Outlined.Forum, Hub.Memory, "debate models compare ai disagree second opinion") { c, _ -> DebateScreen(c) },
        ScreenSpec("memtime", "Memory time machine", "What did you believe back then?", Icons.Outlined.History, Hub.Memory, "memory past belief then now changed what did i think") { c, _ -> MemoryTimeScreen(c) },
        ScreenSpec("agentbudget", "Agent budget", "AI usage per agent, with limits", Icons.Outlined.Savings, Hub.Tools, "budget tokens usage cost agents limit meter") { _, _ -> AgentBudgetScreen() },
        ScreenSpec("handoff", "Send to PC", "Hand a job to Hermes on your computer", Icons.Outlined.Computer, Hub.Work, "handoff pc computer hermes desktop send task") { c, _ -> HandoffScreen(c) },
    )
}

// ─────────────────────────────── Agent budget meter ───────────────────────────────
/** Counts AI use per agent. Every request made inside [Tag] is booked to that agent. */
object AgentMeter {
    class Tag(val name: String) : AbstractCoroutineContextElement(Key) { companion object Key : CoroutineContext.Key<Tag> }
    private fun p(c: Context) = c.getSharedPreferences("agent_meter", 0)
    private fun day() = LocalDate.now().toString()

    fun record(c: Context?, who: String?, inChars: Int, outChars: Int) {
        c ?: return
        val name = who ?: "Atlas"
        val tokens = (inChars + outChars) / 4
        val pr = p(c); val k = "${day()}|$name"
        pr.edit().putInt("t:$k", pr.getInt("t:$k", 0) + tokens).putInt("r:$k", pr.getInt("r:$k", 0) + 1)
            .putStringSet("names", (pr.getStringSet("names", emptySet()) ?: emptySet()) + name).apply()
    }
    fun names(c: Context): Set<String> = p(c).getStringSet("names", emptySet()) ?: emptySet()
    fun today(c: Context, name: String): Pair<Int, Int> = p(c).getInt("t:${day()}|$name", 0) to p(c).getInt("r:${day()}|$name", 0)
    fun week(c: Context, name: String): Int = (0..6).sumOf { p(c).getInt("t:${LocalDate.now().minusDays(it.toLong())}|$name", 0) }
    fun cap(c: Context, name: String) = p(c).getInt("cap:$name", 0)                 // tokens a day; 0 = no limit
    fun setCap(c: Context, name: String, v: Int) = p(c).edit().putInt("cap:$name", v).apply()
    fun overCap(c: Context, name: String) = cap(c, name).let { it > 0 && today(c, name).first >= it }
    fun pricePerMillion(c: Context) = p(c).getFloat("ppm", 0f)
    fun setPrice(c: Context, v: Float) = p(c).edit().putFloat("ppm", v).apply()
}

@Composable
fun AgentBudgetScreen() {
    val c = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val names = remember(tick) { AgentMeter.names(c).sortedByDescending { AgentMeter.today(c, it).first } }
    var price by remember { mutableStateOf(AgentMeter.pricePerMillion(c).takeIf { it > 0 }?.toString() ?: "") }
    AtlasScreen(title = "Agent budget", subtitle = "Estimated from the words sent and received", hub = Hub.Tools) {
        item("p") { Column(Modifier.padding(horizontal = Space.page)) {
            OutlinedTextField(price, { price = it; AgentMeter.setPrice(c, it.toFloatOrNull() ?: 0f) }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Your cost per million tokens in Rs (optional)") })
        } }
        if (names.isEmpty()) item("e") { Text("No AI use recorded yet.", modifier = Modifier.padding(Space.page)) }
        names.forEach { n -> item(n) { Group(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) {
            val (t, r) = AgentMeter.today(c, n); val cap = AgentMeter.cap(c, n); val ppm = AgentMeter.pricePerMillion(c)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(n, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("%,d tokens · $r calls today".format(t), style = MaterialTheme.typography.labelMedium)
            }
            if (cap > 0) LinearProgressIndicator({ (t.toFloat() / cap).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 6.dp),
                color = if (t >= cap) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text("This week %,d tokens".format(AgentMeter.week(c, n)) + (if (ppm > 0) " · ≈ Rs %.0f".format(AgentMeter.week(c, n) / 1_000_000f * ppm) else ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0 to "No limit", 20_000 to "20k/day", 100_000 to "100k/day", 500_000 to "500k/day").forEach { (v, l) -> Capsule(l, cap == v) { AgentMeter.setCap(c, n, v); tick++ } }
            }
        } } }
    }
}

// ─────────────────────────────── Watch & learn ───────────────────────────────
/** Notices tap sequences you repeat in other apps and offers to turn them into a skill. Labels only; never text you type. */
object WatchLearn {
    private fun p(c: Context) = c.getSharedPreferences("watchlearn", 0)
    fun on(c: Context) = p(c).getBoolean("on", false)
    fun setOn(c: Context, v: Boolean) {
        p(c).edit().putBoolean("on", v).apply()
        com.nizam.app.feature.phone.AtlasAccessibilityService.instance?.let { svc -> runCatching {
            val info = svc.serviceInfo
            info.eventTypes = if (v) info.eventTypes or AccessibilityEvent.TYPE_VIEW_CLICKED else info.eventTypes and AccessibilityEvent.TYPE_VIEW_CLICKED.inv()
            svc.serviceInfo = info
        } }
    }
    fun store(c: Context) = JsonStore(c, "watchlearn_suggestions")
    private val skip = listOf("bank", "wallet", "pay", "jazzcash", "easypaisa", "password", "authenticator", "sadapay", "nayapay")
    private var pkg = ""; private val seq = mutableListOf<String>(); private var last = 0L

    fun onEvent(c: Context, e: AccessibilityEvent) {
        if (!on(c)) return
        val p = e.packageName?.toString() ?: return
        if (p == c.packageName || skip.any { p.lowercase().contains(it) } || p.contains("launcher") || p.contains("systemui")) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> if (p != pkg) { flush(c); pkg = p }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (System.currentTimeMillis() - last > 120_000) flush(c)
                last = System.currentTimeMillis()
                val label = (e.text?.joinToString(" ")?.trim().orEmpty().ifBlank { e.contentDescription?.toString().orEmpty() }).take(40)
                if (label.isNotBlank() && seq.size < 12) seq += label
            }
        }
    }

    private fun flush(c: Context) {
        if (seq.size >= 3 && pkg.isNotBlank()) {
            val key = pkg + "|" + seq.take(6).joinToString(">")
            val n = p(c).getInt("n:$key", 0) + 1
            p(c).edit().putInt("n:$key", n).apply()
            if (n == 3 && store(c).all().none { it.optString("key") == key }) {
                val label = runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrElse { pkg }
                store(c).add(JSONObject().put("key", key).put("app", label).put("pkg", pkg).put("steps", JSONArray(seq.take(8))).put("at", System.currentTimeMillis()))
                runCatching { com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 4600,
                    "You've done this 3 times in $label", "Tap Watch & learn to make it one step: ${seq.take(4).joinToString(" → ")}") }
            }
        }
        seq.clear()
    }
}

@Composable
fun WatchLearnScreen(open: (String) -> Unit) {
    val c = LocalContext.current
    var on by remember { mutableStateOf(WatchLearn.on(c)) }
    var tick by remember { mutableIntStateOf(0) }
    val list = remember(tick) { WatchLearn.store(c).all().sortedByDescending { it.optLong("at") } }
    AtlasScreen(title = "Watch & learn", subtitle = "Spots what you repeat, offers to do it for you", hub = Hub.Tools) {
        item("t") { Column(Modifier.padding(horizontal = Space.page)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(on, { on = it; WatchLearn.setOn(c, it) }); Text("  Notice repeated tap sequences") }
            Text("Only button labels are noted, never what you type. Banking, wallet and password apps are ignored. Needs screen control on.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        if (list.isEmpty()) item("e") { Text("Nothing spotted yet — do something the same way three times.", modifier = Modifier.padding(Space.page),
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
        list.forEach { o -> item(o.optString("id")) { Group(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) {
            Text("In ${o.optString("app")}", style = MaterialTheme.typography.titleSmall)
            val s = o.optJSONArray("steps") ?: JSONArray()
            Text((0 until s.length()).joinToString(" → ") { s.optString(it) }, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val steps = listOf(JSONObject().put("k", "ui").put("op", "open").put("app", o.optString("app")).put("pkg", o.optString("pkg"))) +
                        (0 until s.length()).map { JSONObject().put("k", "ui").put("op", "tap").put("text", s.optString(it)) }
                    com.nizam.app.feature.agent.SkillsPro.save(c, JSONObject().put("name", "${o.optString("app")} routine").put("desc", "Learned by watching you")
                        .put("source", "demo").put("steps", JSONArray(steps)))
                    WatchLearn.store(c).remove(o.optString("id")); tick++; Feedback.show("Saved as a skill — review it in Skills"); open("skills")
                }) { Text("Make it a skill") }
                OutlinedButton(onClick = { WatchLearn.store(c).remove(o.optString("id")); tick++ }) { Text("Dismiss") }
            }
        } } }
    }
}

// ─────────────────────────────── Dry run ───────────────────────────────
object DryRun {
    /** Explains exactly what something would do, step by step, without doing any of it. */
    suspend fun explain(container: AppContainer, what: String): String = Ai.generate(container.settingsRepository, what,
        "Simulate this automation WITHOUT doing anything. List, step by step, what would happen, what would change (messages sent, money moved, " +
            "files touched, apps opened), what could go wrong, and which steps would need approval. End with a one-line verdict: safe / check first / risky.", 1200)
}

// ─────────────────────────────── Overnight Life Ops ───────────────────────────────
object LifeOps {
    fun store(c: Context) = JsonStore(c, "lifeops")
    suspend fun nightly(c: Context, container: AppContainer, force: Boolean = false) {
        val key = LocalDate.now().toString()
        if (!force && store(c).all().any { it.optString("date") == key }) return
        val y = LocalDate.now().minusDays(1)
        val txns = runCatching { container.financeRepository.since(y.minusDays(30).toString()).first() }.getOrElse { emptyList() }
        val spentY = txns.filter { it.kind == "EXPENSE" && it.localDate == y.toString() }
        val month = txns.filter { it.kind == "EXPENSE" && it.localDate >= y.withDayOfMonth(1).toString() }.sumOf { it.amount }
        val tasks = runCatching { container.taskRepository.all().first() }.getOrElse { emptyList() }.filter { it.deletedAt == null }
        val overdue = tasks.filter { it.completedAt == null && (it.due ?: Long.MAX_VALUE) < System.currentTimeMillis() }
        val waiting = com.nizam.app.feature.agent.ApprovalInbox.pending(c)
        val failed = com.nizam.app.feature.agent.Schedules.all(c).filter { j -> j.history.firstOrNull()?.let { !it.ok && it.at > System.currentTimeMillis() - 86_400_000 } == true }
        val bills = runCatching { com.nizam.app.feature.agent.Actions.run(container, "find_bills", JSONObject()).summary }.getOrDefault("")
        val facts = buildString {
            appendLine("Yesterday's spending: Rs %,d over ${spentY.size} items (${spentY.take(8).joinToString { it.category + " " + it.amount.toLong() }}). Month so far Rs %,d.".format(spentY.sumOf { it.amount }.toLong(), month.toLong()))
            appendLine("Overdue tasks (${overdue.size}): ${overdue.take(10).joinToString { it.title }}")
            appendLine("Waiting for approval: ${waiting.size} (${waiting.take(5).joinToString { it.optString("action") }})")
            appendLine("Routines that failed: ${failed.joinToString { it.title }}")
            appendLine("Bills and subscriptions: ${bills.take(800)}")
        }
        val report = runCatching { Ai.generate(container.settingsRepository, facts,
            "You are the user's overnight operations manager. Write a short morning report: what changed, what needs attention today (max 5, most important first), " +
                "and 2–3 concrete fixes Atlas can do with one tap (reschedule, cancel, pay reminder). Warm, brief, markdown bullets.", 900) }.getOrElse { facts }
        store(c).add(JSONObject().put("date", key).put("report", report).put("facts", facts).put("at", System.currentTimeMillis()))
        store(c).save(store(c).all().takeLast(30))
        runCatching { com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 4700,
            "🌙 While you slept", report.lines().firstOrNull { it.isNotBlank() }?.take(160) ?: "Your morning report is ready") }
    }
}

@Composable
fun LifeOpsScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val list = remember(tick) { LifeOps.store(c).all().sortedByDescending { it.optLong("at") } }
    AtlasScreen(title = "Overnight Life Ops", subtitle = "Runs between 2 and 5 am; the report waits for you", hub = Hub.Work) {
        item("b") { Row(Modifier.padding(horizontal = Space.page)) {
            Button(onClick = { busy = true; scope.launch { runCatching { LifeOps.nightly(c, container, force = true) }.onFailure { Feedback.failed("Report", it) }; busy = false; tick++ } },
                enabled = !busy) { Text(if (busy) "Working…" else "Run it now") }
        } }
        list.forEach { o -> item(o.optString("id")) { Group(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) {
            Text("🌙 ${o.optString("date")}", style = MaterialTheme.typography.titleSmall); Md(o.optString("report"))
        } } }
    }
}

// ─────────────────────────────── Negotiator ───────────────────────────────
@Composable
fun NegotiatorScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { JsonStore(c, "negotiations") }
    var list by remember { mutableStateOf(store.all()) }
    var openId by remember { mutableStateOf<String?>(null) }
    var what by remember { mutableStateOf("") }; var goal by remember { mutableStateOf("") }; var walk by remember { mutableStateOf("") }
    var who by remember { mutableStateOf("") }; var style by remember { mutableStateOf("Friendly but firm") }
    val cur = list.firstOrNull { it.optString("id") == openId }
    if (cur != null) { NegotiationThread(container, cur, { o -> store.save(store.all().map { if (it.optString("id") == o.optString("id")) o else it }); list = store.all() }) { openId = null }; return }
    AtlasScreen(title = "Negotiator", subtitle = "Atlas drafts every move; you approve each message", hub = Hub.Money) {
        item("f") { Column(Modifier.padding(horizontal = Space.page)) {
            OutlinedTextField(what, { what = it }, Modifier.fillMaxWidth(), label = { Text("What's being negotiated") }, placeholder = { Text("Used Corolla 2016 on OLX listed at 32 lakh") })
            OutlinedTextField(goal, { goal = it }, Modifier.fillMaxWidth(), label = { Text("Your target") }, singleLine = true, placeholder = { Text("28 lakh") })
            OutlinedTextField(walk, { walk = it }, Modifier.fillMaxWidth(), label = { Text("Walk-away point (kept private)") }, singleLine = true, placeholder = { Text("30 lakh") })
            OutlinedTextField(who, { who = it }, Modifier.fillMaxWidth(), label = { Text("Their WhatsApp number (optional)") }, singleLine = true)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Friendly but firm", "Very polite", "Tough", "Urdu").forEach { s -> Capsule(s, style == s) { style = s } } }
            Button(onClick = { val o = store.add(JSONObject().put("what", what).put("goal", goal).put("walk", walk).put("who", who).put("style", style).put("msgs", JSONArray()))
                list = store.all(); openId = o.optString("id"); what = ""; goal = ""; walk = "" }, enabled = what.isNotBlank() && goal.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) { Text("Start") }
        } }
        list.forEach { o -> item(o.optString("id")) { ListRow(o.optString("what").take(50), supporting = "Target ${o.optString("goal")}", icon = Icons.Outlined.Handshake) { openId = o.optString("id") } } }
    }
}

@Composable
private fun NegotiationThread(container: AppContainer, o: JSONObject, save: (JSONObject) -> Unit, back: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var theirs by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf("") }
    var coach by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var ver by remember { mutableIntStateOf(0) }
    val msgs = remember(ver) { (o.optJSONArray("msgs") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }
    fun add(from: String, text: String) { o.put("msgs", (o.optJSONArray("msgs") ?: JSONArray()).put(JSONObject().put("from", from).put("text", text))); save(o); ver++ }
    AtlasScreen(title = o.optString("what").take(40), subtitle = "Target ${o.optString("goal")} · walk away at ${o.optString("walk").ifBlank { "—" }}", hub = Hub.Money) {
        item("b2") { OutlinedButton(onClick = back, modifier = Modifier.padding(horizontal = Space.page)) { Text("‹ Back") } }
        msgs.forEachIndexed { i, m -> item("m$i") { Text((if (m.optString("from") == "me") "You: " else "Them: ") + m.optString("text"),
            modifier = Modifier.padding(horizontal = Space.page, vertical = 4.dp), style = MaterialTheme.typography.bodyMedium,
            color = if (m.optString("from") == "me") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) } }
        item("in") { Column(Modifier.padding(Space.page)) {
            OutlinedTextField(theirs, { theirs = it }, Modifier.fillMaxWidth(), label = { Text("Paste their latest message") }, minLines = 2)
            Button(onClick = { busy = true; if (theirs.isNotBlank()) { add("them", theirs.trim()); theirs = "" }
                scope.launch {
                    val raw = runCatching { Ai.generate(container.settingsRepository, "Deal: ${o.optString("what")}\nMy target: ${o.optString("goal")}\nMy private walk-away: ${o.optString("walk")}\n" +
                        "Conversation:\n" + msgs.joinToString("\n") { (if (it.optString("from") == "me") "Me: " else "Them: ") + it.optString("text") } +
                        "\n\nReturn ONLY JSON {\"message\":\"my next message, ${o.optString("style")}\",\"coach\":\"one line of strategy for me\"}",
                        "You are a skilled, honest negotiator. Never reveal the walk-away point. Never lie or invent facts. Anchor, trade concessions, stay respectful.", 500) }.getOrElse { "" }
                    val j = runCatching { JSONObject(Ai.cleanJson(raw)) }.getOrNull()
                    draft = j?.optString("message").orEmpty().ifBlank { raw }; coach = j?.optString("coach").orEmpty(); busy = false
                } }, enabled = !busy, modifier = Modifier.padding(top = 6.dp)) { Text(if (busy) "Thinking…" else "Suggest my next move") }
            if (coach.isNotBlank()) Text("🧭 $coach", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            if (draft.isNotBlank()) {
                OutlinedTextField(draft, { draft = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Your message (edit freely)") }, minLines = 2)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { add("me", draft)
                        if (o.optString("who").isNotBlank()) com.nizam.app.feature.connect.Connectors.openInApp(c, JSONObject().put("app", "whatsapp").put("action", "send").put("to", o.optString("who")).put("text", draft))
                        else com.nizam.app.feature.nx.Share.text(c, draft)
                        draft = "" }) { Text("Approve & send") }
                    OutlinedButton(onClick = { add("me", draft); draft = "" }) { Text("I sent it myself") }
                }
            }
        } }
    }
}

// ─────────────────────────────── Price hunter ───────────────────────────────
object PriceHunter {
    fun store(c: Context) = JsonStore(c, "pricehunter")

    /** Finds the lowest current price for [query] across Pakistani shops — through Scrapling on your PC when connected, otherwise web search. */
    suspend fun hunt(c: Context, container: AppContainer, query: String): JSONObject {
        val scraped = runCatching {
            if (com.nizam.app.feature.mcp.Mcp.allTools(c).none { it.name == "bulk_get" }) null
            else {
                val urls = listOf("https://www.daraz.pk/catalog/?q=", "https://priceoye.pk/search?q=", "https://www.olx.com.pk/items/q-").map { it + java.net.URLEncoder.encode(query, "UTF-8") }
                withTimeoutOrNull(90_000) { com.nizam.app.feature.mcp.Mcp.findAndCall(c, "bulk_get", JSONObject().put("urls", JSONArray(urls)).put("extraction_type", "text")) }
            }
        }.getOrNull()
        val raw = Ai.generate(container.settingsRepository,
            "Product: $query\n" + (scraped?.let { "Search results scraped from shops:\n${it.take(9000)}" } ?: "Search the web for current prices in Pakistan (Daraz, PriceOye, OLX, iShopping, Telemart, brand stores)."),
            "Return ONLY JSON {\"best\":number in PKR or 0,\"where\":\"shop\",\"link\":\"url or empty\",\"offers\":[{\"shop\":\"\",\"price\":0,\"note\":\"new/used/condition\"}]} — only real prices you actually saw.",
            1000, useWeb = scraped == null)
        return JSONObject(Ai.cleanJson(raw)).put("via", if (scraped != null) "Scrapling" else "web search")
    }

    suspend fun check(c: Context, container: AppContainer) {
        val today = LocalDate.now().toString()
        store(c).all().filter { it.optString("checked") != today }.take(5).forEach { w ->
            runCatching {
                val r = hunt(c, container, w.optString("q"))
                val best = r.optDouble("best", 0.0)
                val target = w.optDouble("target", 0.0)
                val low = w.optDouble("low", 0.0).takeIf { it > 0 } ?: Double.MAX_VALUE
                store(c).update(w.optString("id")) { it.put("checked", today).put("best", best).put("where", r.optString("where")).put("link", r.optString("link"))
                    .put("low", if (best > 0) minOf(low, best) else it.optDouble("low", 0.0))
                    .put("hist", (it.optJSONArray("hist") ?: JSONArray()).put(JSONObject().put("d", today).put("p", best))) }
                if (best > 0 && (best <= target || best < low))
                    com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 4800 + (w.optString("id").hashCode() % 50),
                        "🎯 ${w.optString("q").take(40)}: Rs %,d".format(best.toLong()), "at ${r.optString("where")}" + if (best <= target) " — at or under your target!" else " — lowest yet")
            }
        }
    }
}

@Composable
fun PriceHunterScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val list = remember(tick) { PriceHunter.store(c).all() }
    var q by remember { mutableStateOf("") }; var target by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf<String?>(null) }
    AtlasScreen(title = "Price hunter", subtitle = "Checks daily across shops; Scrapling makes it sharper", hub = Hub.Money) {
        item("f") { Column(Modifier.padding(horizontal = Space.page)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("What do you want?") }, placeholder = { Text("Samsung A55 256GB") }, singleLine = true)
            OutlinedTextField(target, { target = it.filter { ch -> ch.isDigit() } }, Modifier.fillMaxWidth(), label = { Text("Alert me at or under (Rs)") }, singleLine = true)
            Button(onClick = { PriceHunter.store(c).add(JSONObject().put("q", q.trim()).put("target", target.toDoubleOrNull() ?: 0.0)); q = ""; target = ""; tick++ },
                enabled = q.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) { Text("Hunt it") }
        } }
        list.forEach { w -> item(w.optString("id") + tick) { Group(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(w.optString("q"), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (w.optDouble("best", 0.0) > 0) Text("Rs %,d".format(w.optDouble("best").toLong()), fontWeight = FontWeight.Bold,
                    color = if (w.optDouble("best") <= w.optDouble("target", 0.0)) Color(0xFF16A34A) else MaterialTheme.colorScheme.onSurface)
            }
            Text(listOf(w.optString("where"), w.optDouble("target", 0.0).takeIf { it > 0 }?.let { "target Rs %,d".format(it.toLong()) } ?: "",
                w.optDouble("low", 0.0).takeIf { it > 0 }?.let { "lowest Rs %,d".format(it.toLong()) } ?: "").filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Capsule(if (busy == w.optString("id")) "Checking…" else "Check now", false) { if (busy == null) { busy = w.optString("id"); scope.launch {
                    runCatching { val r = PriceHunter.hunt(c, container, w.optString("q")); PriceHunter.store(c).update(w.optString("id")) { it.put("best", r.optDouble("best", 0.0))
                        .put("where", r.optString("where")).put("link", r.optString("link")).put("offers", r.optJSONArray("offers")) }; Feedback.show("Checked via ${r.optString("via")}") }
                        .onFailure { Feedback.failed("Checking", it) }; busy = null; tick++ } } }
                if (w.optString("link").startsWith("http")) Capsule("Open", false) { com.nizam.app.feature.nx.Share.open(c, w.optString("link")) }
                Capsule("✕", false) { PriceHunter.store(c).remove(w.optString("id")); tick++ }
            }
            w.optJSONArray("offers")?.let { a -> (0 until minOf(a.length(), 5)).forEach { i -> val x = a.getJSONObject(i)
                Text("• ${x.optString("shop")}: Rs %,d ${x.optString("note")}".format(x.optDouble("price").toLong()), style = MaterialTheme.typography.bodySmall) } }
        } } }
    }
}

// ─────────────────────────────── Model debate ───────────────────────────────
@Composable
fun DebateScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var q by remember { mutableStateOf("") }
    var answers by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var verdict by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AtlasScreen(title = "Model debate", subtitle = "Different AIs, one question", hub = Hub.Memory) {
        item("f") { Column(Modifier.padding(horizontal = Space.page)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), minLines = 2, placeholder = { Text("Is it better to buy or rent a home in Lahore right now?") })
            Button(onClick = { busy = true; answers = emptyList(); verdict = ""; scope.launch {
                runCatching {
                    val eps = com.nizam.app.net.Router.endpoints(container.settingsRepository, c).filter { it.kind != "LOCAL" && it.kind != "FOLAX" }
                        .distinctBy { it.name + it.model }.take(3)
                    if (eps.size < 2) throw IllegalStateException("Add at least two AI providers in Settings › API keys to hold a debate.")
                    val res = coroutineScope { eps.map { e -> async { (e.name + " · " + e.model.substringAfterLast('/')) to
                        (runCatching { Ai.call(e, q, "Answer directly with your honest view and your main reason. Under 150 words.", 500) }.getOrElse { "(no answer: ${Feedback.friendly(it)})" }) } }.awaitAll() }
                    answers = res
                    verdict = Ai.generate(container.settingsRepository, "Question: $q\n\n" + res.joinToString("\n\n") { "${it.first}:\n${it.second}" },
                        "Compare these answers. Give: ✅ where they agree, ⚔️ where they disagree and why, 🧭 which reasoning is strongest, and a final answer. Markdown, brief.", 900)
                }.onFailure { verdict = Feedback.friendly(it) }
                busy = false } }, enabled = q.isNotBlank() && !busy, modifier = Modifier.padding(top = 8.dp)) { Text(if (busy) "Debating…" else "Start the debate") }
        } }
        answers.forEachIndexed { i, (who, a) -> item("a$i") { Group(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) { Text(who, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary); Md(a) } } }
        if (verdict.isNotBlank()) item("v") { Group(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) { Text("The verdict", style = MaterialTheme.typography.titleSmall); Md(verdict) } }
    }
}

// ─────────────────────────────── Memory time machine ───────────────────────────────
object MemorySnapshots {
    fun store(c: Context) = JsonStore(c, "memory_snapshots")
    suspend fun take(c: Context, container: AppContainer) {
        val d = LocalDate.now().toString()
        if (store(c).all().any { it.optString("d") == d }) return
        val facts = com.nizam.app.feature.brain2.PersonalModel.facts(c) + com.nizam.app.feature.brain2.PersonalModel.lessons(c)
        val notes = runCatching { container.agentMemory.notes().first().map { it.kind + ": " + it.text } }.getOrElse { emptyList() }
        store(c).add(JSONObject().put("d", d).put("facts", JSONArray((facts + notes).take(200))))
        store(c).save(store(c).all().takeLast(400))
    }
}

@Composable
fun MemoryTimeScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { runCatching { MemorySnapshots.take(c, container) } }
    val snaps = remember { MemorySnapshots.store(c).all().sortedBy { it.optString("d") } }
    var pick by remember { mutableIntStateOf(0) }
    var q by remember { mutableStateOf("") }
    var ans by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AtlasScreen(title = "Memory time machine", subtitle = "Atlas keeps a daily snapshot of what it believes about you", hub = Hub.Memory) {
        if (snaps.size < 2) item("e") { Text("Snapshots start today. Come back in a few days to compare then and now.", modifier = Modifier.padding(Space.page)) }
        else {
            val then = snaps[pick.coerceIn(0, snaps.lastIndex)]; val now = snaps.last()
            fun set(o: JSONObject) = (o.optJSONArray("facts") ?: JSONArray()).let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }
            item("p") { Column(Modifier.padding(horizontal = Space.page)) {
                Text("Compare with ${then.optString("d")}", style = MaterialTheme.typography.titleSmall)
                androidx.compose.material3.Slider(pick.toFloat(), { pick = it.toInt() }, valueRange = 0f..(snaps.size - 1).coerceAtLeast(1).toFloat())
            } }
            val gone = set(then) - set(now); val new = set(now) - set(then)
            item("g") { Group(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) {
                Text("Changed since then", style = MaterialTheme.typography.titleSmall)
                if (gone.isEmpty() && new.isEmpty()) Text("Nothing — you were the same person.", style = MaterialTheme.typography.bodySmall)
                new.take(12).forEach { Text("＋ $it", style = MaterialTheme.typography.bodySmall, color = Color(0xFF16A34A)) }
                gone.take(12).forEach { Text("－ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            } }
            item("q") { Column(Modifier.padding(Space.page)) {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("What did I think about my career then?") })
                Button(onClick = { busy = true; scope.launch {
                    ans = runCatching { Ai.generate(container.settingsRepository, "Then (${then.optString("d")}):\n${set(then).joinToString("\n")}\n\nNow:\n${set(now).joinToString("\n")}\n\nQuestion: $q",
                        "Answer from these two snapshots of what the assistant knew about the user. Say clearly what was true then vs now. Don't invent.", 700) }.getOrElse { Feedback.friendly(it) }
                    busy = false } }, enabled = q.isNotBlank() && !busy, modifier = Modifier.padding(top = 6.dp)) { Text(if (busy) "Remembering…" else "Ask") }
                if (ans.isNotBlank()) Md(ans)
            } }
        }
    }
}

// ─────────────────────────────── Phone → PC handoff ───────────────────────────────
object Handoff {
    fun store(c: Context) = JsonStore(c, "handoffs")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    fun send(c: Context, container: AppContainer, task: String): String {
        val o = store(c).add(JSONObject().put("task", task).put("state", "working").put("at", System.currentTimeMillis()))
        scope.launch {
            val r = com.nizam.app.feature.connect.Connectors.act(container, "ask_hermes", JSONObject().put("task", task))
            store(c).update(o.optString("id")) { it.put("state", if (r.first) "done" else "failed").put("result", r.second) }
            if (r.first) runCatching { container.noteRepository.createFull("PC: ${task.take(50)}", r.second) }
            runCatching { com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 4900,
                if (r.first) "💻 Your PC finished" else "💻 PC job failed", task.take(60) + " — " + r.second.take(120)) }
        }
        return o.optString("id")
    }
}

@Composable
fun HandoffScreen(container: AppContainer) {
    val c = LocalContext.current
    var task by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5000); tick++ } }
    val list = remember(tick) { Handoff.store(c).all().sortedByDescending { it.optLong("at") } }
    val ready = remember { com.nizam.app.feature.connect.Connectors.values(c, "hermes") != null }
    AtlasScreen(title = "Send to PC", subtitle = if (ready) "Hermes on your computer does the heavy lifting" else "Connect Hermes Agent in Connectors first", hub = Hub.Work) {
        item("f") { Column(Modifier.padding(horizontal = Space.page)) {
            OutlinedTextField(task, { task = it }, Modifier.fillMaxWidth(), minLines = 3, placeholder = { Text("Research the 5 best budget laptops for coding in Pakistan, compare them in a table and save it as a file on my desktop") })
            Button(onClick = { Handoff.send(c, container, task.trim()); task = ""; tick++; Feedback.show("Sent — you'll get a notification when it's done") },
                enabled = ready && task.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) { Text("Send to my PC") }
        } }
        list.forEach { o -> item(o.optString("id") + o.optString("state")) { Group(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("task").take(80), style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (o.optString("state") == "working") CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(if (o.optString("state") == "done") "✓" else "✕", color = if (o.optString("state") == "done") Color(0xFF16A34A) else MaterialTheme.colorScheme.error)
            }
            if (o.optString("result").isNotBlank()) Md(o.optString("result").take(3000))
        } } }
    }
}
