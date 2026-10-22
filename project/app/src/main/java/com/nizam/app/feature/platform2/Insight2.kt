package com.nizam.app.feature.platform2

import androidx.compose.foundation.lazy.LazyRow

import androidx.compose.foundation.lazy.items

import android.app.usage.UsageStatsManager
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.dash.Dash
import com.nizam.app.feature.dash.DashData
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.PlanEngine
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.num
import com.nizam.app.feature.nx.rs
import com.nizam.app.ui.design.DayTimeline
import com.nizam.app.ui.design.DonutChart
import com.nizam.app.ui.design.GoalRibbon
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.InsightLine
import com.nizam.app.ui.design.KpiTile
import com.nizam.app.ui.design.Panel
import com.nizam.app.ui.design.Slice
import com.nizam.app.ui.design.TrendLine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.sqrt

/** Every daily number Atlas can measure, as date → value, so goals, the time ledger and cause-and-effect can all share them. */
object Metrics {
    val NAMES = listOf("Tasks done", "Prayers logged", "Study minutes", "Gym sets", "XP", "Spending (Rs)", "Income (Rs)", "Sleep hours", "Resting pulse", "Caffeine cups", "Daylight minutes", "Screen time (min)")

    suspend fun series(container: AppContainer, d: DashData, name: String, days: Int = 60): Map<String, Double> = withContext(Dispatchers.IO) {
        val c = container.appContext
        fun fromScore(f: (com.nizam.app.feature.progress.DayScore) -> Double) = d.sum?.last30.orEmpty().associate { it.date to f(it) }
        fun fromLog(store: String, key: String) = runCatching { JsonStore(c, store).all().mapNotNull { o -> o.optString("d").takeIf { it.length == 10 }?.let { it to num(o.optString(key)) } }.toMap() }.getOrDefault(emptyMap())
        when (name) {
            "Tasks done" -> fromScore { it.tasks.toDouble() }; "Prayers logged" -> fromScore { it.prayers.toDouble() }; "Study minutes" -> fromScore { it.studyMinutes.toDouble() }
            "Gym sets" -> fromScore { it.sets.toDouble() }; "XP" -> fromScore { it.xp.toDouble() }
            "Spending (Rs)" -> d.txns.filter { it.kind == "EXPENSE" }.groupBy { it.localDate }.mapValues { (_, v) -> v.sumOf { it.amount } }
            "Income (Rs)" -> d.txns.filter { it.kind == "INCOME" }.groupBy { it.localDate }.mapValues { (_, v) -> v.sumOf { it.amount } }
            "Sleep hours" -> fromLog("nxlog_tempready", "sleep"); "Resting pulse" -> fromLog("nxlog_tempready", "hr")
            "Caffeine cups" -> fromLog("nxlog_caffeine", "cups"); "Daylight minutes" -> fromLog("nxlog_caffeine", "sun")
            "Screen time (min)" -> runCatching {
                val um = c.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                (0 until 14).associate { i -> val day = LocalDate.now().minusDays(i.toLong()); val s = day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                    day.toString() to um.queryAndAggregateUsageStats(s, s + 86_400_000L).values.sumOf { it.totalTimeInForeground } / 60_000.0 } }.getOrDefault(emptyMap())
            else -> emptyMap()
        }
    }
}

/** Goals that read their progress from your real data. */
object Goals {
    private fun store(c: Context) = JsonStore(c, "nx_goals")
    fun all(c: Context) = store(c).all()
    fun add(c: Context, title: String, metric: String, target: Double, deadline: String) =
        store(c).add(JSONObject().put("title", title).put("metric", metric).put("target", target).put("deadline", deadline).put("start", LocalDate.now().toString()).put("manual", 0.0))
    fun setManual(c: Context, id: String, v: Double) = store(c).update(id) { it.put("manual", v) }
    fun remove(c: Context, id: String) = store(c).remove(id)

    suspend fun progress(container: AppContainer, d: DashData, g: JSONObject): Double {
        val metric = g.optString("metric")
        if (metric == "Manual number") return g.optDouble("manual", 0.0)
        val s = Metrics.series(container, d, metric)
        val start = g.optString("start")
        return s.filterKeys { it >= start }.values.sum()
    }
}

@Composable
fun GoalsScreen(container: AppContainer) {
    val c = LocalContext.current
    var data by remember { mutableStateOf<DashData?>(null) }
    var list by remember { mutableStateOf(Goals.all(c)) }
    var prog by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var title by remember { mutableStateOf("") }; var metric by remember { mutableStateOf("Manual number") }; var target by remember { mutableStateOf("") }; var deadline by remember { mutableStateOf(LocalDate.now().plusDays(30).toString()) }
    LaunchedEffect(list.size) { com.nizam.app.ui.design.guard { val d = Dash.load(container); data = d; prog = list.associate { it.optString("id") to Goals.progress(container, d, it) } } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("A goal reads its progress from your real data (tasks, prayers, study, savings…) so you don't update it by hand. Atlas checks weekly whether you're on track.", style = MaterialTheme.typography.bodyMedium)
        list.forEach { g ->
            val id = g.optString("id"); val p = prog[id] ?: 0.0; val t = g.optDouble("target", 1.0).coerceAtLeast(1e-9)
            val start = LocalDate.parse(g.optString("start")); val end = runCatching { LocalDate.parse(g.optString("deadline")) }.getOrDefault(start.plusDays(30))
            val total = ChronoUnit.DAYS.between(start, end).coerceAtLeast(1); val gone = ChronoUnit.DAYS.between(start, LocalDate.now()).coerceIn(0, total)
            val expected = gone.toFloat() / total; val frac = (p / t).toFloat().coerceIn(0f, 1f)
            Panel(g.optString("title")) {
                GoalRibbon(frac, expected)
                Text("${"%,.0f".format(p)} of ${"%,.0f".format(t)} ${g.optString("metric").takeIf { it != "Manual number" }.orEmpty()} · ${(frac * 100).toInt()}% · due ${g.optString("deadline")}", style = MaterialTheme.typography.bodySmall)
                InsightLine(when { frac >= 1f -> "Reached. Well done."; frac + 0.05f >= expected -> "On track."; else -> "Behind by ${"%,.0f".format((expected - frac) * t)} — a push this week would close the gap." })
                Row {
                    if (g.optString("metric") == "Manual number") { var v by remember(id) { mutableStateOf("") }
                        OutlinedTextField(v, { v = it }, label = { Text("Set progress") }, singleLine = true, modifier = Modifier.weight(1f))
                        TextButton(onClick = { v.toDoubleOrNull()?.let { x -> Goals.setManual(c, id, x); list = Goals.all(c)} }) { Text("Save") } }
                    TextButton(onClick = { Goals.remove(c, id); list = Goals.all(c) }) { Text("Delete") }
                }
            }
        }
        Text("New goal", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(title, { title = it }, label = { Text("Goal (e.g. 40 hours of study this month)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            items((listOf("Manual number") + Metrics.NAMES.filter { !it.startsWith("Resting") }).toList()) { m ->
                androidx.compose.material3.FilterChip(selected = metric == m, onClick = { metric = m }, label = { Text(m) }) } }
        OutlinedTextField(target, { target = it }, label = { Text("Target number") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(deadline, { deadline = it }, label = { Text("Deadline (yyyy-mm-dd)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = title.isNotBlank() && (target.toDoubleOrNull() ?: 0.0) > 0, onClick = { Goals.add(c, title, metric, target.toDouble(), deadline); list = Goals.all(c); title = ""; target = "" }) { Text("Add goal") }
    }
}

@Composable
fun TimeLedgerScreen(container: AppContainer) {
    val c = LocalContext.current
    var data by remember { mutableStateOf<DashData?>(null) }
    var parts by remember { mutableStateOf<List<Slice>>(emptyList()) }
    var note by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard {
        val d = Dash.load(container); data = d
        val week = d.sum?.last30.orEmpty().takeLast(7)
        val prayersH = week.sumOf { it.prayers } * 20 / 60.0
        val studyH = week.sumOf { it.studyMinutes } / 60.0
        val cal = withContext(Dispatchers.IO) { (0 until 7).sumOf { off -> runCatching { PlanEngine.events(c, LocalDate.now().minusDays(off.toLong())).sumOf { (it.endMin - it.startMin) } }.getOrDefault(0) } / 60.0 }
        val sleepH = Metrics.series(container, d, "Sleep hours").entries.sortedByDescending { it.key }.take(7).sumOf { it.value }
        val screenH = Metrics.series(container, d, "Screen time (min)").entries.sortedByDescending { it.key }.take(7).sumOf { it.value } / 60.0
        val sl = listOf(Slice("Sleep", sleepH.coerceAtLeast(0.0), Color(0xFF6EA8FE)), Slice("Meetings & events", cal, Color(0xFF4CC9F0)), Slice("Study", studyH, Color(0xFFB388FF)),
            Slice("Prayer", prayersH, Color(0xFF3DDC97)), Slice("Screens", screenH, Color(0xFFFFB454))).filter { it.value > 0.05 }
        parts = sl
        val known = sl.sumOf { it.value }; val free = (7 * 24 - known).coerceAtLeast(0.0)
        note = if (sl.isEmpty()) "Log some sleep, study or events and this fills in." else "Of 168 hours this week, ${"%.0f".format(known)} are accounted for. The rest (${"%.0f".format(free)} h) is open, commuting, meals or things Atlas can't see."
    } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Where your last seven days went, built from your calendar, study log, prayers, sleep log and screen time (if allowed).", style = MaterialTheme.typography.bodyMedium)
        if (parts.isNotEmpty()) Panel("Hours this week") { DonutChart(parts, "HOURS", "%.0f".format(parts.sumOf { it.value })) }
        InsightLine(note)
        val d = data
        if (d != null) { val now = java.time.LocalTime.now(); if (d.blocks.isNotEmpty()) Panel("Today") { DayTimeline(d.blocks, now.hour * 60 + now.minute) } }
    }
}

object Stats {
    fun pearson(a: List<Double>, b: List<Double>): Double? {
        val n = a.size; if (n < 6) return null
        val ma = a.average(); val mb = b.average()
        var sab = 0.0; var saa = 0.0; var sbb = 0.0
        for (i in 0 until n) { val x = a[i] - ma; val y = b[i] - mb; sab += x * y; saa += x * x; sbb += y * y }
        return if (saa == 0.0 || sbb == 0.0) null else sab / sqrt(saa * sbb)
    }
    fun describe(r: Double, n: Int): String {
        val s = when { abs(r) < 0.2 -> "no clear link"; abs(r) < 0.4 -> "a weak link"; abs(r) < 0.7 -> "a moderate link"; else -> "a strong link" }
        val dir = if (abs(r) < 0.2) "" else if (r > 0) ", moving together" else ", moving in opposite directions"
        val caution = if (n < 14) " (only $n days, so treat it as a hint)" else ""
        return "$s$dir$caution. A link is not proof one causes the other."
    }
}

@Composable
fun CauseEffectScreen(container: AppContainer) {
    var a by remember { mutableStateOf("Sleep hours") }; var b by remember { mutableStateOf("Tasks done") }
    var pairs by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var lag by remember { mutableStateOf(0) }
    LaunchedEffect(a, b, lag) { com.nizam.app.ui.design.guard {
        val d = Dash.load(container)
        val sa = Metrics.series(container, d, a); val sb = Metrics.series(container, d, b)
        pairs = sa.entries.mapNotNull { (day, v) -> val next = LocalDate.parse(day).plusDays(lag.toLong()).toString(); sb[next]?.let { v to it } }
    } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Pick two things Atlas tracks and see whether they move together. Choose 'next day' to ask whether today's value shows up in tomorrow's.", style = MaterialTheme.typography.bodyMedium)
        Text("A", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { items(Metrics.NAMES) { m -> androidx.compose.material3.FilterChip(selected = a == m, onClick = { a = m }, label = { Text(m) }) } }
        Text("B", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { items(Metrics.NAMES) { m -> androidx.compose.material3.FilterChip(selected = b == m, onClick = { b = m }, label = { Text(m) }) } }
        Row { TextButton(onClick = { lag = 0 }) { Text("Same day", fontWeight = if (lag == 0) FontWeight.Bold else FontWeight.Normal) }; TextButton(onClick = { lag = 1 }) { Text("Next day", fontWeight = if (lag == 1) FontWeight.Bold else FontWeight.Normal) } }
        val r = Stats.pearson(pairs.map { it.first }, pairs.map { it.second })
        if (pairs.size < 6 || r == null) Text("Not enough overlapping days yet (${pairs.size}). Keep logging both for a week or two.", style = MaterialTheme.typography.bodyMedium)
        else {
            Text("r = ${"%.2f".format(r)} over ${pairs.size} days", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            InsightLine(Stats.describe(r, pairs.size))
            val xs = pairs.map { it.first }; val ys = pairs.map { it.second }
            val cs = MaterialTheme.colorScheme
            Canvas(Modifier.fillMaxWidth().height(160.dp)) {
                val lx = xs.min(); val hx = xs.max(); val ly = ys.min(); val hy = ys.max(); val sx = (hx - lx).takeIf { it > 0 } ?: 1.0; val sy = (hy - ly).takeIf { it > 0 } ?: 1.0
                drawRect(cs.surfaceVariant, size = size)
                pairs.forEach { (x, y) -> drawCircle(cs.primary.copy(alpha = 0.75f), 4.dp.toPx(), Offset(((x - lx) / sx * size.width).toFloat(), (size.height - (y - ly) / sy * size.height).toFloat())) }
                val ma = xs.average(); val mb = ys.average(); val slope = pairs.sumOf { (it.first - ma) * (it.second - mb) } / xs.sumOf { (it - ma) * (it - ma) }.coerceAtLeast(1e-9); val ic = mb - slope * ma
                fun py(x: Double) = (size.height - ((slope * x + ic) - ly) / sy * size.height).toFloat()
                drawLine(cs.secondary, Offset(0f, py(lx)), Offset(size.width, py(hx)), 2.dp.toPx())
            }
        }
    }
}

@Composable
fun ForecastScreen(container: AppContainer) {
    var d by remember { mutableStateOf<DashData?>(null) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { d = Dash.load(container) } }
    val data = d ?: run { Text("Reading your data…"); return }
    val now = LocalDate.now(); val monthStart = now.withDayOfMonth(1).toString()
    val byDay = data.txns.filter { it.kind == "EXPENSE" }.groupBy { it.localDate }.mapValues { (_, v) -> v.sumOf { it.amount } }
    val last30 = (29 downTo 0).map { byDay[now.minusDays(it.toLong()).toString()] ?: 0.0 }
    val mean = last30.average(); val sd = sqrt(last30.sumOf { (it - mean) * (it - mean) } / last30.size)
    val spent = data.txns.filter { it.kind == "EXPENSE" && it.localDate >= monthStart }.sumOf { it.amount }
    val left = now.lengthOfMonth() - now.dayOfMonth
    val expected = spent + mean * left; val spread = sd * sqrt(left.toDouble().coerceAtLeast(1.0))
    val income = data.txns.filter { it.kind == "INCOME" && it.localDate >= monthStart }.sumOf { it.amount }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            KpiTile("Spent so far", spent, Modifier.weight(1f), prefix = "Rs ", higherIsBetter = false)
            KpiTile("Expected month end", expected, Modifier.weight(1f), prefix = "Rs ", higherIsBetter = false)
        }
        InsightLine("Likely range: ${rs(expected - spread)} to ${rs(expected + spread)} (based on your last 30 days; one big bill can move it).")
        if (income > 0) InsightLine("Income so far ${rs(income)}. At this pace you'd end the month ${if (income - expected >= 0) "ahead by ${rs(income - expected)}" else "short by ${rs(expected - income)}"}.")
        Panel("Daily spending, last 30 days") { TrendLine(last30, labels = (29 downTo 0).map { now.minusDays(it.toLong()).toString().takeLast(5) }, format = { "Rs %,.0f".format(it) }) }
        val weekTasks = data.openTasks
        InsightLine("Workload: $weekTasks open tasks. At your recent pace of ${"%.1f".format((data.sum?.last30.orEmpty().takeLast(14).sumOf { it.tasks }) / 14.0)} tasks a day, that clears in about ${if (weekTasks == 0) 0 else (weekTasks / ((data.sum?.last30.orEmpty().takeLast(14).sumOf { it.tasks }) / 14.0).coerceAtLeast(0.1)).toInt()} days.")
    }
}

object Insight2Specs {
    val all = listOf(
        ScreenSpec("goalsengine", "Goals", "Goals that read their own progress from your tasks, prayers, study and money", Icons.Outlined.Flag, Hub.Work, "goals goal engine target progress on track deadline okr personal") { c, _ -> GoalsScreen(c) },
        ScreenSpec("timeledger", "Time ledger", "Where your hours went this week: sleep, meetings, study, prayer, screens", Icons.Outlined.Schedule, Hub.Tools, "time ledger hours week where did my time go tracking sleep screen meetings") { c, _ -> TimeLedgerScreen(c) },
        ScreenSpec("causeeffect", "Cause & effect", "Pick two things and see whether they move together, with a plain-language verdict", Icons.Outlined.QueryStats, Hub.Tools, "correlation cause effect sleep mood caffeine focus spending scatter relationship") { c, _ -> CauseEffectScreen(c) },
        ScreenSpec("forecast", "Forecasts", "Month-end spending as a range, plus how long your task list will take", Icons.Outlined.TrendingUp, Hub.Money, "forecast projection month end spending range uncertainty workload predict") { c, _ -> ForecastScreen(c) },
    )
}
