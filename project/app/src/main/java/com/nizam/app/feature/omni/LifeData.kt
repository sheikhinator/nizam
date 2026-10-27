package com.nizam.app.feature.omni

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.feature.next.JsonStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import kotlin.random.Random

private data class Branch(val name: String, val spend: Double, val gym: Double, val pray: Double, val tasks: Double)
private val GREEN = Color(0xFF3FB950); private val RED = Color(0xFFF85149); private val MONO = FontFamily.Monospace

/** Life as a repository: every day is a commit; branches are experiments you run on your own life. */
object LifeGit {
    fun commits(c: Context) = JsonStore(c, "omni_commits")
    fun branches(c: Context) = JsonStore(c, "omni_branches")
    fun hash(o: JSONObject) = Integer.toHexString(o.toString().hashCode()).padStart(7, '0').take(7)

    /** Commits every day you haven't committed yet (up to 60 back). */
    suspend fun commitMissing(c: Context) {
        val have = commits(c).all().map { it.optString("date") }.toSet()
        val ct = O.container(c)
        val add = (1..60).map { LocalDate.now().minusDays(it.toLong()) }.filter { it.toString() !in have }.take(14)
        add.forEach { d -> val o = O.day(ct, d); commits(c).add(o.put("hash", hash(o))) }
    }
    suspend fun history(c: Context, n: Int): List<JSONObject> {
        commitMissing(c)
        val byDate = commits(c).all().associateBy { it.optString("date") }
        return (n downTo 1).mapNotNull { byDate[LocalDate.now().minusDays(it.toLong()).toString()] }
    }
}

private fun mean(l: List<JSONObject>, k: String) = if (l.isEmpty()) 0.0 else l.map { O.num(it, k) }.average()
private fun sum(l: List<JSONObject>, k: String) = l.sumOf { O.num(it, k) }

@Composable
private fun DiffLine(name: String, a: Double, b: Double, goodUp: Boolean) {
    val up = b > a; val same = kotlin.math.abs(b - a) < 0.01
    val good = if (same) null else (up == goodUp)
    Text("${if (same) " " else if (good == true) "+" else "-"} ${name.padEnd(18)} ${O.fmt(a).padStart(8)} → ${O.fmt(b)}",
        Modifier.fillMaxWidth().background(when (good) { true -> GREEN.copy(alpha = 0.14f); false -> RED.copy(alpha = 0.14f); null -> Color.Transparent }).padding(horizontal = 6.dp, vertical = 2.dp),
        color = when (good) { true -> GREEN; false -> RED; null -> MaterialTheme.colorScheme.onSurfaceVariant }, fontFamily = MONO, fontSize = 12.sp)
}

// ─────────────── Life Diff ───────────────
@Composable
fun LifeDiffScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var hist by remember { mutableStateOf<List<JSONObject>?>(null) }
    var blame by remember { mutableStateOf("") }; var blaming by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { hist = LifeGit.history(c, 14) + O.day(O.container(c), LocalDate.now()) } }
    val h = hist
    Column {
        OCard { OTitle("Life diff: last week → this week"); OMuted("Green lines went better, red lines slipped. Tap a day below to run blame on it.") }
        if (h == null) { OBusy(true, "Reading your two weeks…"); return@Column }
        val a = h.dropLast(7).takeLast(7); val b = h.takeLast(7)
        OCard {
            Text("diff --life week-${LocalDate.now().minusDays(13)}..week-${LocalDate.now()}", fontFamily = MONO, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            O.METRICS.filter { it != "tasks_open" }.forEach { k -> if (sum(a, k) + sum(b, k) > 0) DiffLine(O.METRIC_NAMES[k] ?: k, if (k == "mood") mean(a, k) else sum(a, k), if (k == "mood") mean(b, k) else sum(b, k), k in O.GOOD_UP) }
            if (sum(a, "fajr") + sum(b, "fajr") > 0) DiffLine("Fajr prayed", sum(a, "fajr"), sum(b, "fajr"), true)
        }
        OTitle("Blame a day")
        b.forEach { d -> val score = O.num(d, "tasks_done") * 2 + O.num(d, "prayers") + O.num(d, "habits") + O.num(d, "mood") - O.num(d, "spent") / 2000
            OCard(onClick = { blaming = d.optString("date"); blame = ""; scope.launch {
                val i = h.indexOfFirst { it.optString("date") == d.optString("date") }
                blame = O.ai(c, "Run 'git blame' on this day of someone's life: find the most likely chain of causes for how it went, using the days before it. Format as a short chain: cause → effect → effect, then one line of advice.\n" +
                    "Days (oldest first): ${h.subList(maxOf(0, i - 3), i + 1).joinToString("\n")}", "Use only these numbers. Be specific. Under 90 words.", 400) } }) {
                Row { Text(java.time.LocalDate.parse(d.optString("date")).dayOfWeek.name.take(3) + " " + d.optString("date").takeLast(5), Modifier.padding(end = 10.dp), fontFamily = MONO)
                    OBar((score.toFloat() / 25f).coerceIn(0.02f, 1f), if (score > 10) GREEN else if (score > 4) Color(0xFFFFC37A) else RED) }
                if (blaming == d.optString("date")) { if (blame.isBlank()) OBusy(true, "git blame…") else OMd(blame) }
            } }
    }
}

// ─────────────── Life Git ───────────────
@Composable
fun LifeGitScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var v by remember { mutableIntStateOf(0) }
    var hist by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var name by remember { mutableStateOf("") }; var change by remember { mutableStateOf("") }; var days by remember { mutableStateOf("14") }
    LaunchedEffect(v) { com.nizam.app.ui.design.guard { hist = LifeGit.history(c, 60) } }
    val branches = remember(v) { LifeGit.branches(c).all().sortedByDescending { it.optLong("t") } }
    Column {
        OCard { OTitle("git branch  ·  try a new routine"); OMuted("Run an experiment on your own life. Atlas compares the branch with your normal life and tells you to merge (keep it) or revert.")
            OField(name, "Branch name (e.g. no-phone-after-isha)") { name = it.replace(' ', '-') }
            OField(change, "What changes", 2) { change = it }; OField(days, "Days") { days = it.filter(Char::isDigit) }
            OBtn("git checkout -b", name.isNotBlank() && change.isNotBlank()) { LifeGit.branches(c).add(JSONObject().put("t", System.currentTimeMillis()).put("name", name).put("change", change)
                .put("start", LocalDate.now().toString()).put("days", days.toIntOrNull() ?: 14).put("state", "open")); name = ""; change = ""; v++ } }
        branches.forEach { br ->
            val start = LocalDate.parse(br.optString("start")); val len = br.optInt("days", 14)
            val during = hist.filter { LocalDate.parse(it.optString("date")) >= start }
            val before = hist.filter { LocalDate.parse(it.optString("date")) < start }.takeLast(len)
            OCard {
                Text("⎇ ${br.optString("name")}  (${during.size}/$len days)  ${br.optString("state")}", fontFamily = MONO, color = MaterialTheme.colorScheme.primary)
                OMuted(br.optString("change"))
                if (during.isNotEmpty() && before.isNotEmpty()) {
                    O.METRICS.filter { it != "tasks_open" && it != "diary_words" }.forEach { k -> if (sum(before, k) + sum(during, k) > 0) DiffLine(O.METRIC_NAMES[k] ?: k, mean(before, k), mean(during, k), k in O.GOOD_UP) }
                    OMuted("Daily averages: main branch → this branch")
                } else OMuted("Comparison starts tomorrow, once the branch has a day of history.")
                if (br.optString("state") == "open") ORow {
                    OBtn("git merge (keep it)") { LifeGit.branches(c).update(br.optString("id")) { it.put("state", "merged") }; v++ }
                    OBtn2("git revert") { LifeGit.branches(c).update(br.optString("id")) { it.put("state", "reverted") }; v++ } }
            }
        }
        OTitle("git log")
        hist.reversed().take(30).forEach { d ->
            Text("${d.optString("hash")}  ${d.optString("date")}  ✓${d.optInt("tasks_done")} 🕌${d.optInt("prayers")} Rs${O.fmt(O.num(d, "spent"))} 💧${d.optInt("water_ml")} 🏋${d.optInt("gym_sets")}",
                fontFamily = MONO, fontSize = 11.sp, modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}

// ─────────────── Keystone finder ───────────────
@Composable
fun KeystoneScreen() {
    val c = LocalContext.current
    var res by remember { mutableStateOf<List<Triple<String, String, Double>>?>(null) }
    var n by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard {
        val h = LifeGit.history(c, 60).filter { O.num(it, "tasks_done") + O.num(it, "prayers") + O.num(it, "spent") + O.num(it, "habits") > 0 }; n = h.size
        val habits = mapOf("fajr" to { o: JSONObject -> O.num(o, "fajr") > 0 }, "all 5 prayers" to { o: JSONObject -> O.num(o, "prayers") >= 5 }, "gym" to { o: JSONObject -> O.num(o, "gym_sets") > 0 },
            "2L+ water" to { o: JSONObject -> O.num(o, "water_ml") >= 2000 }, "a habit ticked" to { o: JSONObject -> O.num(o, "habits") > 0 }, "writing in your diary" to { o: JSONObject -> O.num(o, "diary_words") > 0 },
            "fewer than 40 unlocks" to { o: JSONObject -> O.num(o, "screen_unlocks") in 1.0..39.0 })
        val outcomes = listOf("tasks_done", "mood", "spent", "prayers", "habits")
        val out = mutableListOf<Triple<String, String, Double>>()
        habits.forEach { (hn, f) -> val yes = h.filter(f); val no = h.filterNot(f); if (yes.size < 3 || no.size < 3) return@forEach
            outcomes.forEach { k -> if ((k == "prayers" && hn.contains("prayer")) || (k == "habits" && hn.contains("habit"))) return@forEach
                val a = mean(yes, k); val b = mean(no, k); if (b <= 0.0 && a <= 0.0) return@forEach
                val lift = if (b == 0.0) 1.0 else (a - b) / b
                val good = if (k == "spent") -lift else lift
                if (good > 0.15) out += Triple(hn, "on days with $hn: ${O.METRIC_NAMES[k]} ${if (lift > 0) "+" else ""}${(lift * 100).toInt()}% (${O.fmt(a)} vs ${O.fmt(b)}, ${yes.size} vs ${no.size} days)", good) } }
        res = out.sortedByDescending { it.third } } }
    Column {
        OCard { OTitle("Keystone finder"); OMuted("Searching your last 60 days for the one small habit that predicts your best days.") }
        val r = res
        if (r == null) OBusy(true, "Crunching…")
        else if (r.isEmpty()) OCard { OText("Not enough history yet ($n days with data). Keep logging prayers, tasks, water and spending; this sharpens every week.") }
        else {
            val top = r.first()
            OCard { Text("🗝 Your keystone: ${top.first}", style = MaterialTheme.typography.titleLarge); OText(top.second)
                OMuted("Correlation, not proof, but it's your own data. Make this your daily anchor.")
                OBtn("Make it my daily anchor") { kotlinx.coroutines.MainScope().launch { com.nizam.app.feature.agent.Actions.run(O.container(c), "add_task", JSONObject().put("title", "Keystone: ${top.first}").put("priority", 2)) }
                    com.nizam.app.ui.components.Feedback.show("Added to today") } }
            OTitle("All the evidence"); r.take(12).forEach { OCard { OText(it.second) } }
        }
    }
}

// ─────────────── Parallel You ───────────────
@Composable
fun ParallelYouScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var horizon by remember { mutableStateOf("90 days") }
    var hist by remember { mutableStateOf<List<JSONObject>?>(null) }
    var plan by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { hist = LifeGit.history(c, 45) } }
    val h = hist
    Column {
        OCard { OTitle("Parallel you"); OMuted("Thousands of simulated futures from your real last 45 days. Each branch is one small change; pick the you you want and Atlas builds the steps.")
            OChips(listOf("30 days", "90 days", "365 days"), horizon) { horizon = it } }
        if (h == null) { OBusy(true, "Simulating…"); return@Column }
        val days = horizon.substringBefore(' ').toInt()
        val pool = h.ifEmpty { listOf(JSONObject()) }
        val branches = listOf(Branch("As you are", 1.0, 1.0, 1.0, 1.0), Branch("Spend 15% less", 0.85, 1.0, 1.0, 1.0), Branch("Gym 3x a week", 1.0, 2.0, 1.0, 1.1),
            Branch("Never miss Fajr", 1.0, 1.0, 1.25, 1.15))
        val sims = remember(h, days) { branches.map { b -> val r = Random(42)
            val finals = (1..1500).map { var save = 0.0; var gym = 0.0; var pr = 0.0; var tk = 0.0
                repeat(days) { val d = pool[r.nextInt(pool.size)]; save += O.num(d, "income") - O.num(d, "spent") * b.spend
                    gym += O.num(d, "gym_sets") * b.gym; pr += minOf(5.0, O.num(d, "prayers") * b.pray); tk += O.num(d, "tasks_done") * b.tasks }
                doubleArrayOf(save, gym, pr, tk) }
            b to (0..3).map { i -> finals.map { it[i] }.sorted().let { s -> Triple(s[s.size / 10], s[s.size / 2], s[s.size * 9 / 10]) } } } }
        sims.forEach { (b, r) -> OCard {
            OTitle("⎇ ${b.name}")
            OText("Money saved: Rs ${O.fmt(r[0].second)}  (likely between ${O.fmt(r[0].first)} and ${O.fmt(r[0].third)})")
            OText("Gym sets: ${O.fmt(r[1].second)} · Prayers: ${O.fmt(r[2].second)} · Tasks done: ${O.fmt(r[3].second)}")
            val col = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(46.dp)) { val lo = r[0].first; val hi = r[0].third; val span = (hi - lo).coerceAtLeast(1.0)
                val base = sims.flatMap { listOf(it.second[0].first, it.second[0].third) }; val mn = base.min(); val mx = base.max(); val sc = (mx - mn).coerceAtLeast(1.0)
                drawRect(col.copy(alpha = 0.25f), Offset(((lo - mn) / sc * size.width).toFloat(), 10f), androidx.compose.ui.geometry.Size(((span) / sc * size.width).toFloat(), 26f))
                drawRect(col, Offset(((r[0].second - mn) / sc * size.width).toFloat() - 2f, 4f), androidx.compose.ui.geometry.Size(4f, 38f)) }
            if (b.name != "As you are") OBtn2("Choose this branch") { plan = ""; scope.launch {
                plan = O.ai(c, "The user chose this future: '${b.name}' over $horizon. Their recent daily average: spent Rs ${O.fmt(mean(pool, "spent"))}, gym sets ${O.fmt(mean(pool, "gym_sets"))}, prayers ${O.fmt(mean(pool, "prayers"))}, tasks ${O.fmt(mean(pool, "tasks_done"))}. " +
                    "Give exactly 3 tiny concrete changes starting tomorrow, one line each, that make this branch happen.", "Short, practical.", 300)
                plan.lines().filter { it.isNotBlank() }.take(3).forEach { l -> com.nizam.app.feature.agent.Actions.run(O.container(c), "add_task", JSONObject().put("title", l.trimStart('-', '*', '1', '2', '3', '.', ' ').take(90)).put("priority", 1)) } } }
        } }
        if (plan.isNotBlank()) OCard { OTitle("Your steps (added to tasks)"); OMd(plan) }
        OMuted("Built by replaying your real days in random order. It assumes tomorrow looks like your last ${pool.size} days.")
    }
}

// ─────────────── Counterfactual replay ───────────────
@Composable
fun CounterfactualScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var date by remember { mutableStateOf(LocalDate.now().minusDays(3).toString()) }
    var whatIf by remember { mutableStateOf("") }; var out by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    Column {
        OCard { OTitle("What if…"); OMuted("Pick a past day and change one thing. Atlas replays that day and the week after it from your real numbers.")
            OField(date, "Day (YYYY-MM-DD)") { date = it }
            OChips((1..7).map { LocalDate.now().minusDays(it.toLong()).toString() }, date) { date = it }
            OField(whatIf, "What if I had… (e.g. slept at 11, skipped the mall)", 2) { whatIf = it }
            OBtn(if (busy) "Replaying…" else "Replay", whatIf.isNotBlank() && !busy) { busy = true; scope.launch {
                val d = runCatching { LocalDate.parse(date) }.getOrDefault(LocalDate.now().minusDays(1))
                val ct = O.container(c); val week = (0..6).map { O.day(ct, d.plusDays(it.toLong())) }.filter { !LocalDate.parse(it.optString("date")).isAfter(LocalDate.now()) }
                out = O.ai(c, "Counterfactual replay. What actually happened, day by day from $date:\n${week.joinToString("\n")}\nChange: on $date the user had $whatIf.\n" +
                    "Replay that day and the following days realistically: what would likely have changed, number by number, and what wouldn't. Then one honest line: was it worth it?",
                    "Ground every claim in the numbers given. Be honest about uncertainty. Use a short timeline format.", 900)
                busy = false } } }
        if (out.isNotBlank()) OCard { OMd(out) }
    }
}

// ─────────────── Done list ───────────────
@Composable
fun DoneListScreen() {
    val c = LocalContext.current
    var items by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard {
        val ct = O.container(c); val l = mutableListOf<String>()
        val zone = java.time.ZoneId.systemDefault(); val start = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        runCatching { ct.taskRepository.all().first().filter { (it.completedAt ?: 0) >= start }.forEach { l += "✓ ${it.title}" } }
        val d = O.day(ct, LocalDate.now())
        if (O.num(d, "prayers") > 0) l += "🕌 Prayed ${d.optInt("prayers")} of 5"
        if (O.num(d, "water_ml") > 0) l += "💧 Drank ${d.optInt("water_ml")} ml"
        if (O.num(d, "gym_sets") > 0) l += "🏋 ${d.optInt("gym_sets")} gym sets"
        if (O.num(d, "habits") > 0) l += "🔁 ${d.optInt("habits")} habits ticked"
        if (O.num(d, "diary_words") > 0) l += "📓 Wrote ${d.optInt("diary_words")} words"
        if (O.num(d, "spent") + O.num(d, "income") > 0) l += "💰 Kept track of your money"
        Journal.store(c).all().filter { it.optLong("t") >= start && it.optBoolean("ok") && it.optString("source") == "Atlas" }.take(10).forEach { l += "✦ ${it.optString("result").take(80)}" }
        items = l } }
    Column {
        OCard { OTitle("Done today"); OMuted("Not your plans. What you actually did, for the days that feel empty.") }
        val i = items
        if (i == null) OBusy(true) else if (i.isEmpty()) OCard { OText("Nothing recorded yet today. Even this counts: you checked in.") }
        else { OCard { i.forEach { OText(it) } }
            OText("That's ${i.size} things. Not nothing.")
            OBtn2("Share") { com.nizam.app.feature.nx.Share.text(c, "Done today:\n" + i.joinToString("\n")) } }
    }
}

// ─────────────── Decision black box ───────────────
object BlackBox {
    fun store(c: Context) = JsonStore(c, "omni_blackbox", sealed = true)
    fun tick(c: Context) { store(c).all().filter { it.optString("state") == "sealed" && it.optString("open") <= LocalDate.now().toString() && !it.optBoolean("notified") }.forEach { d ->
        store(c).update(d.optString("id")) { it.put("notified", true) }
        O.notify(c, 5860, "A decision black box is ready to open", "Recorded ${d.optString("date")}. How did it turn out?", "nx/blackbox") } }
}

@Composable
fun BlackBoxScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var v by remember { mutableIntStateOf(0) }
    var what by remember { mutableStateOf("") }; var know by remember { mutableStateOf("") }; var expect by remember { mutableStateOf("") }
    var conf by remember { mutableStateOf("70") }; var feel by remember { mutableStateOf("") }; var topic by remember { mutableStateOf("Money") }; var months by remember { mutableStateOf("3 months") }
    val all = remember(v) { BlackBox.store(c).all().sortedByDescending { it.optLong("t") } }
    Column {
        OCard { OTitle("Seal a decision"); OMuted("Before a big decision, record what you know, what you expect and how you feel. It stays sealed until the date, then shows how right you were.")
            OField(what, "The decision") { what = it }; OField(know, "What I know right now", 2) { know = it }; OField(expect, "What I expect will happen", 2) { expect = it }
            OField(conf, "How sure (%)") { conf = it.filter(Char::isDigit) }; OField(feel, "How I feel about it") { feel = it }
            OChips(listOf("Money", "People", "Work", "Health", "Faith", "Other"), topic) { topic = it }
            OChips(listOf("1 month", "3 months", "6 months", "1 year"), months) { months = it }
            OBtn("Seal it", what.isNotBlank() && expect.isNotBlank()) {
                val open = LocalDate.now().plusMonths(when (months) { "1 month" -> 1L; "6 months" -> 6L; "1 year" -> 12L; else -> 3L })
                BlackBox.store(c).add(JSONObject().put("t", System.currentTimeMillis()).put("date", LocalDate.now().toString()).put("what", what).put("know", know).put("expect", expect)
                    .put("conf", conf.toIntOrNull() ?: 70).put("feel", feel).put("topic", topic).put("open", open.toString()).put("state", "sealed"))
                what = ""; know = ""; expect = ""; feel = ""; v++ } }
        val judged = all.filter { it.optString("state") == "judged" }
        if (judged.isNotEmpty()) OCard { OTitle("Your judgement score")
            judged.groupBy { it.optString("topic") }.forEach { (t, l) -> val s = l.map { it.optInt("score") }.average().toInt(); OText("$t: $s / 100 (${l.size})"); OBar(s / 100f) } }
        all.forEach { d -> OCard {
            val ready = d.optString("open") <= LocalDate.now().toString()
            OText("${when (d.optString("state")) { "judged" -> "📊"; else -> if (ready) "📬" else "🔒" }} ${d.optString("what")}")
            OMuted("${d.optString("topic")} · sealed ${d.optString("date")} · opens ${d.optString("open")}")
            if (d.optString("state") == "sealed" && ready) {
                var happened by remember(d.optString("id")) { mutableStateOf("") }
                OMuted("You expected: ${d.optString("expect")} (${d.optInt("conf")}% sure). You knew: ${d.optString("know")}. You felt: ${d.optString("feel")}")
                OField(happened, "What actually happened", 2) { happened = it }
                OBtn("Open and judge", happened.isNotBlank()) { scope.launch {
                    val j = O.aiJson(c, "Judge a past decision honestly. Decision: ${d.optString("what")}. Known then: ${d.optString("know")}. Expected: ${d.optString("expect")} at ${d.optInt("conf")}% confidence. Felt: ${d.optString("feel")}. " +
                        "What happened: $happened. Score how good the JUDGEMENT was (not the luck), 0-100, and how calibrated the confidence was. Output only JSON {\"score\":0,\"verdict\":\"two sentences\",\"lesson\":\"one line\"}", 400)
                    BlackBox.store(c).update(d.optString("id")) { it.put("state", "judged").put("happened", happened).put("score", j?.optInt("score") ?: 50).put("verdict", j?.optString("verdict")).put("lesson", j?.optString("lesson")) }; v++ } }
            }
            if (d.optString("state") == "judged") { OText("Score ${d.optInt("score")} · ${d.optString("verdict")}"); OMuted("Lesson: ${d.optString("lesson")}") }
        } }
    }
}
