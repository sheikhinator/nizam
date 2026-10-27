package com.nizam.app.feature.omni

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.feature.next.JsonStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Who's acting right now, so the journal can say "Mission: …" instead of "Atlas". */
object Ghost { @Volatile var source: String? = null }

/**
 * Missions: agents that run like processes for days or weeks toward one goal, with a step budget, memory and
 * checkpoints. They live in the background loop (so they survive restarts), and anything that changes something
 * still goes through your approval inbox.
 */
object Ops {
    fun store(c: Context) = JsonStore(c, "omni_ops")

    fun start(c: Context, goal: String, budget: Int = 40, everyMin: Int = 60): JSONObject =
        store(c).add(JSONObject().put("goal", goal).put("state", "running").put("created", System.currentTimeMillis()).put("budget", budget).put("used", 0)
            .put("every", everyMin).put("next", 0L).put("memory", "").put("log", JSONArray()).put("checkpoints", JSONArray()))
    fun set(c: Context, id: String, state: String) = store(c).update(id) { it.put("state", state) }

    suspend fun tick(c: Context) {
        val now = System.currentTimeMillis()
        store(c).all().filter { it.optString("state") == "running" && it.optLong("next") <= now }.take(2).forEach { runCatching { step(c, it) } }
    }

    private val NAMES by lazy { Kernel.functions.filter { it.kind == "action" }.joinToString(", ") { it.name + if (it.writes) "*" else "" } }

    suspend fun step(c: Context, op: JSONObject) {
        val id = op.optString("id")
        if (op.optInt("used") >= op.optInt("budget")) { store(c).update(id) { it.put("state", "paused").put("why", "Step budget used up") }
            O.notify(c, 5830, "Mission paused: budget used", op.optString("goal").take(80), "nx/missions"); return }
        val log = op.optJSONArray("log") ?: JSONArray()
        val recent = (maxOf(0, log.length() - 6) until log.length()).joinToString("\n") { log.optJSONObject(it)?.let { l -> "- ${l.optString("did")} → ${l.optString("result").take(300)}" }.orEmpty() }
        val o = O.aiJson(c, "You run a long mission for the user, one step at a time, over days.\nMission: ${op.optString("goal")}\nIt is ${java.time.LocalDateTime.now().withSecond(0).withNano(0)}.\n" +
            "Your memory notes: ${op.optString("memory").ifBlank { "(none yet)" }}\nRecent steps:\n${recent.ifBlank { "(first step)" }}\n" +
            "You may call ONE function per step. Actions (* = needs the user's approval): $NAMES. Also any AI tool as tool.<id>. search_web {query} and read_link {url} read the internet.\n" +
            "Output only JSON: {\"thought\":\"short\",\"call\":\"function name or empty\",\"args\":{},\"memory\":\"your updated notes, short, keep what matters\"," +
            "\"checkpoint\":\"a milestone reached, or empty\",\"done\":false,\"report\":\"when done: the final result for the user\",\"next_minutes\":60}", 900, web = true)
            ?: run { store(c).update(id) { it.put("next", System.currentTimeMillis() + 30 * 60_000L) }; return }
        var result = ""
        val call = o.optString("call")
        if (call.isNotBlank()) { Ghost.source = "Mission: ${op.optString("goal").take(40)}"
            result = runCatching { Kernel.call(c, call, o.optJSONObject("args") ?: JSONObject(), "Mission: ${op.optString("goal").take(40)}").optString("result") }.getOrElse { "failed: ${it.message}" }
            Ghost.source = null }
        log.put(JSONObject().put("t", System.currentTimeMillis()).put("did", o.optString("thought") + if (call.isNotBlank()) " [$call]" else "").put("result", result.take(800)))
        while (log.length() > 60) log.remove(0)
        val done = o.optBoolean("done")
        store(c).update(id) { x ->
            x.put("used", x.optInt("used") + 1).put("memory", o.optString("memory").take(2000)).put("log", log)
                .put("next", System.currentTimeMillis() + o.optInt("next_minutes", x.optInt("every", 60)).coerceIn(5, 1440) * 60_000L)
            o.optString("checkpoint").takeIf { it.isNotBlank() }?.let { cp -> (x.optJSONArray("checkpoints") ?: JSONArray().also { a -> x.put("checkpoints", a) }).put(JSONObject().put("t", System.currentTimeMillis()).put("text", cp)) }
            if (done) x.put("state", "done").put("report", o.optString("report"))
        }
        o.optString("checkpoint").takeIf { it.isNotBlank() }?.let { O.notify(c, 5831, "Mission checkpoint", it.take(160), "nx/missions") }
        if (done) O.notify(c, 5832, "Mission done: ${op.optString("goal").take(50)}", o.optString("report").take(200), "nx/missions")
    }
}

/** Atlas improves itself: it studies where its agents failed, proposes new rules for itself, tests them, and you approve. */
object Evolve {
    fun store(c: Context) = JsonStore(c, "omni_evolve")
    fun block(c: Context?): String = c?.let { ctx -> store(ctx).all().filter { it.optString("state") == "approved" }.joinToString("\n") { "- " + it.optString("text") } }
        ?.takeIf { it.isNotBlank() }?.let { "Lessons Atlas learned from its own mistakes (approved by the user):\n$it" }.orEmpty()

    suspend fun tick(c: Context) {
        val p = c.getSharedPreferences("omni_evolve", 0)
        if (System.currentTimeMillis() - p.getLong("last", 0) < 7 * 86_400_000L) return
        p.edit().putLong("last", System.currentTimeMillis()).apply()
        run(c)
    }

    suspend fun run(c: Context): Int {
        val week = System.currentTimeMillis() - 7 * 86_400_000L
        val fails = (Journal.store(c).all().filter { !it.optBoolean("ok") && it.optLong("t") > week }.map { "${it.optString("fn")}: ${it.optString("result").take(160)}" } +
            com.nizam.app.feature.next.Activity.all(c).filter { !it.optBoolean("ok") && it.optLong("t") > week }.map { "${it.optString("a")}: ${it.optString("s").take(160)}" }).distinct().take(40)
        val undone = Journal.store(c).all().filter { it.optBoolean("undone") && it.optLong("t") > week }.map { "user undid ${it.optString("fn")}: ${it.optJSONObject("args")}" }.take(15)
        if (fails.isEmpty() && undone.isEmpty()) return 0
        val have = store(c).all().joinToString("\n") { "- " + it.optString("text") }
        val o = O.aiJson(c, "You are Atlas, an AI assistant app, reviewing your own week. Things that went wrong:\n${fails.joinToString("\n")}\nActions the user undid (you got it wrong):\n${undone.joinToString("\n")}\n" +
            "Rules you already have:\n$have\nPropose up to 4 NEW short rules for yourself that would have prevented these. For each, re-check it against the failures (would it have fixed them? any side effects?). " +
            "Output only JSON: {\"rules\":[{\"text\":\"the rule, imperative, one line\",\"why\":\"which failures it fixes\",\"test\":\"how you checked it, result\",\"score\":0-100}]}", 1400) ?: return 0
        var n = 0
        O.arr(o.optJSONArray("rules")).filter { it.optInt("score") >= 50 }.forEach { r -> n++
            store(c).add(JSONObject().put("t", System.currentTimeMillis()).put("text", r.optString("text")).put("why", r.optString("why")).put("test", r.optString("test")).put("score", r.optInt("score")).put("state", "proposed")) }
        if (n > 0) O.notify(c, 5840, "Atlas upgraded itself: $n proposals", "It studied this week's mistakes. Approve the changes you like.", "nx/selfevolve")
        return n
    }
}

// ─────────────────────────── screens ───────────────────────────
@Composable
fun MissionsScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var goal by remember { mutableStateOf("") }; var budget by remember { mutableStateOf("40") }; var every by remember { mutableStateOf("Every hour") }
    var v by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { while (true) { delay(10_000); v++ } } }
    val ops = remember(v) { Ops.store(c).all().sortedByDescending { it.optLong("created") } }
    Column {
        OCard { OTitle("Start a mission")
            OMuted("An agent that keeps working on one goal for days: searching, checking, comparing, reminding. It survives restarts and anything that changes something waits for your OK.")
            OField(goal, "Goal (e.g. find me the cheapest Umrah package for December)", 2) { goal = it }
            OChips(listOf("Every 15 min", "Every hour", "Every 6 hours", "Daily"), every) { every = it }
            OField(budget, "Step budget") { budget = it.filter(Char::isDigit) }
            OBtn("Start", goal.isNotBlank()) { val op = Ops.start(c, goal, budget.toIntOrNull() ?: 40, when (every) { "Every 15 min" -> 15; "Every 6 hours" -> 360; "Daily" -> 1440; else -> 60 })
                scope.launch { runCatching { Ops.step(c, op) }; v++ }; goal = ""; v++ } }
        OTitle("Running and past missions  ·  ${ops.count { it.optString("state") == "running" }} running")
        ops.forEach { m -> val st = m.optString("state")
            OCard(onClick = { open = if (open == m.optString("id")) null else m.optString("id") }) {
                OText("${when (st) { "running" -> "🟢"; "paused" -> "⏸"; "done" -> "✅"; "killed" -> "⛔"; else -> "•" }} ${m.optString("goal")}")
                OBar(m.optInt("used").toFloat() / m.optInt("budget").coerceAtLeast(1)); OMuted("${m.optInt("used")} of ${m.optInt("budget")} steps · $st" +
                    if (st == "running") " · next ${java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(m.optLong("next")))}" else "")
                if (m.optString("report").isNotBlank()) OMd(m.optString("report"))
                if (open == m.optString("id")) {
                    if (m.optString("memory").isNotBlank()) { OMuted("Its notes"); OText(m.optString("memory")) }
                    O.arr(m.optJSONArray("checkpoints")).forEach { OText("🏁 " + it.optString("text")) }
                    O.arr(m.optJSONArray("log")).reversed().take(12).forEach { l -> OMuted("• ${l.optString("did")}"); if (l.optString("result").isNotBlank()) OMuted("   → ${l.optString("result").take(220)}") }
                }
                ORow {
                    if (st == "running") OBtn2("Pause") { Ops.set(c, m.optString("id"), "paused"); v++ }
                    if (st == "paused") OBtn2("Resume") { Ops.set(c, m.optString("id"), "running"); v++ }
                    if (st == "running" || st == "paused") OBtn2("Step now") { scope.launch { Ops.step(c, Ops.store(c).all().first { it.optString("id") == m.optString("id") }); v++ } }
                    if (st != "killed" && st != "done") OBtn2("Kill") { Ops.set(c, m.optString("id"), "killed"); v++ }
                }
            }
        }
    }
}

/** Ghost run: before the agent touches your other apps, it shows you a storyboard of every screen and tap, and the end result. */
@Composable
fun GhostRunScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var task by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    var board by remember { mutableStateOf<JSONObject?>(null) }
    Column {
        OCard { OTitle("Ghost run"); OMuted("Describe something for Atlas to do in your other apps. First you see a storyboard of exactly what it will tap and what will change; it only runs when you say so.")
            OField(task, "Task (e.g. order my usual from foodpanda)", 2) { task = it }
            OBtn(if (busy) "Planning…" else "Show me the plan", task.isNotBlank() && !busy) { busy = true; scope.launch {
                board = O.aiJson(c, "Plan how a phone agent would do this in Android apps, without doing it: \"$task\". Be realistic about the screens of the actual apps. " +
                    "Output only JSON: {\"steps\":[{\"app\":\"app name\",\"screen\":\"what's on screen\",\"action\":\"tap/type/scroll what\",\"risky\":false,\"why\":\"short\"}],\"outcome\":\"what will have changed at the end\",\"stops_for_you\":[\"moments it will ask you first\"],\"verdict\":\"safe|check first|risky\"}", 1400)
                busy = false } } }
        board?.let { b ->
            OCard { OTitle("Storyboard · ${b.optString("verdict")}")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    O.arr(b.optJSONArray("steps")).forEachIndexed { i, s ->
                        Column(Modifier.size(150.dp, 230.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xFF10131F))
                            .border(2.dp, if (s.optBoolean("risky")) Color(0xFFFF6B6B) else Color(0xFF39425E), RoundedCornerShape(18.dp)).padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("${i + 1} · ${s.optString("app")}", color = Color(0xFF8BE9FD), fontSize = 11.sp)
                            Box(Modifier.size(130.dp, 70.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFF1C2236)).padding(6.dp)) { Text(s.optString("screen"), color = Color(0xFFB8C0DA), fontSize = 10.sp) }
                            Text("👆 " + s.optString("action"), color = Color.White, fontSize = 12.sp)
                            Text(s.optString("why"), color = Color(0xFF8E97B8), fontSize = 10.sp)
                            if (s.optBoolean("risky")) Text("✋ asks you first", color = Color(0xFFFF6B6B), fontSize = 11.sp)
                        }
                    }
                }
                OText("End result: " + b.optString("outcome"))
                O.strs(b.optJSONArray("stops_for_you")).forEach { OMuted("✋ $it") }
                ORow { OBtn("Run it") { com.nizam.app.feature.future.AppAgent.start(c, O.container(c), task); com.nizam.app.ui.components.Feedback.show("Running. Every risky tap still asks you.") }
                    OBtn2("Cancel") { board = null } }
            }
        }
        OMuted("Afterwards, everything it did is in the Action journal, with Undo where possible.")
    }
}

@Composable
fun EvolveScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var v by remember { mutableIntStateOf(0) }; var busy by remember { mutableStateOf(false) }
    val list = remember(v) { Evolve.store(c).all().sortedByDescending { it.optLong("t") } }
    Column {
        OCard { OTitle("Self-evolving Atlas")
            OMuted("Every week Atlas studies where its agents failed and what you undid, writes new rules for itself, checks each one against those mistakes, and asks you. Approved rules shape every answer from then on.")
            OBtn(if (busy) "Studying…" else "Review this week now", !busy) { busy = true; scope.launch { val n = Evolve.run(c); busy = false; v++
                com.nizam.app.ui.components.Feedback.show(if (n == 0) "No mistakes worth fixing this week" else "$n proposals") } } }
        OTitle("Changelog")
        if (list.isEmpty()) OMuted("No proposals yet.")
        list.forEach { r -> OCard {
            OText("${when (r.optString("state")) { "approved" -> "✅"; "rejected" -> "✗"; else -> "🧬" }} ${r.optString("text")}")
            OMuted("Fixes: ${r.optString("why")}"); OMuted("Tested: ${r.optString("test")} · score ${r.optInt("score")}")
            if (r.optString("state") == "proposed") ORow { OBtn("Approve") { Evolve.store(c).update(r.optString("id")) { it.put("state", "approved") }; v++ }
                OBtn2("Reject") { Evolve.store(c).update(r.optString("id")) { it.put("state", "rejected") }; v++ } }
            else if (r.optString("state") == "approved") OBtn2("Turn off") { Evolve.store(c).update(r.optString("id")) { it.put("state", "rejected") }; v++ }
        } }
    }
}
