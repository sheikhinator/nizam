package com.nizam.app.feature.dash

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.deen.HifzStore
import com.nizam.app.feature.finance.Txn
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.PlanEngine
import com.nizam.app.feature.progress.ProgressSummary
import com.nizam.app.feature.progress.Xp
import com.nizam.app.feature.wild.AgentMeter
import com.nizam.app.ui.design.Block
import com.nizam.app.ui.design.CalendarHeat
import com.nizam.app.ui.design.DayTimeline
import com.nizam.app.ui.design.DialGauge
import com.nizam.app.ui.design.DonutChart
import com.nizam.app.ui.design.GoalRibbon
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.InsightLine
import com.nizam.app.ui.design.KpiTile
import com.nizam.app.ui.design.Panel
import com.nizam.app.ui.design.Slice
import com.nizam.app.ui.design.SoundFx
import com.nizam.app.ui.design.TrendLine
import com.nizam.app.ui.design.pressable
import com.nizam.app.ui.screens.hubs.HubRegistry
import com.nizam.app.ui.screens.hubs.RecentFeatures
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/** Everything the dashboards read, gathered once per open on a background thread. */
data class DashData(
    val sum: ProgressSummary?,
    val txns: List<Txn>,
    val openTasks: Int,
    val blocks: List<Block>,
)

object Dash {
    private val PALETTE = listOf(Color(0xFF4CC9F0), Color(0xFFFFB454), Color(0xFF3DDC97), Color(0xFFB388FF), Color(0xFFFF6B6B), Color(0xFF6EA8FE), Color(0xFFF7D44C))

    suspend fun load(container: AppContainer): DashData = withContext(Dispatchers.IO) {
        val c = container.appContext
        val sum = runCatching { Xp.summarise(container) }.getOrNull()
        val txns = runCatching { container.financeRepository.since(LocalDate.now().minusDays(70).toString()).first() }.getOrDefault(emptyList())
        val open = runCatching { container.taskRepository.all().first().count { it.completedAt == null } }.getOrDefault(0)
        val blocks = runCatching {
            (PlanEngine.prayers(container) + PlanEngine.events(c)).map { b ->
                Block(b.startMin, b.endMin, b.label, if (b.kind == "prayer") Color(0xFF3DDC97) else Color(0xFF4CC9F0)) }
        }.getOrDefault(emptyList())
        DashData(sum, txns, open, blocks)
    }

    private fun pct(now: Double, before: Double): Double? = if (before <= 0.0) null else (now - before) / before * 100

    private fun spendByDay(t: List<Txn>): Map<String, Double> = t.filter { it.kind == "EXPENSE" }.groupBy { it.localDate }.mapValues { (_, v) -> v.sumOf { it.amount } }

    /** The last [n] days ending [offset] days ago, one figure per day. */
    private fun daily(map: Map<String, Double>, n: Int, offset: Int = 0) = (n - 1 + offset downTo offset).map { map[LocalDate.now().minusDays(it.toLong()).toString()] ?: 0.0 }

    @Composable
    fun Today(d: DashData, onOpen: (String) -> Unit = {}) {
        val c = LocalContext.current
        val s = d.sum; val today = s?.today; val last = s?.last30.orEmpty()
        val spend = spendByDay(d.txns)
        val spentToday = spend[LocalDate.now().toString()] ?: 0.0
        val yday = last.getOrNull(last.size - 2)
        LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { SoundFx.boot(c) } }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiTile("Prayers", (today?.prayers ?: 0).toDouble(), Modifier.weight(1f), suffix = " / 5", trend = last.map { it.prayers.toDouble() })
                KpiTile("Tasks done", (today?.tasks ?: 0).toDouble(), Modifier.weight(1f), delta = pct((today?.tasks ?: 0).toDouble(), (yday?.tasks ?: 0).toDouble()), trend = last.map { it.tasks.toDouble() })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiTile("Spent today", spentToday, Modifier.weight(1f), prefix = "Rs ", higherIsBetter = false, trend = daily(spend, 14))
                KpiTile("XP today", (today?.xp ?: 0).toDouble(), Modifier.weight(1f), delta = pct((today?.xp ?: 0).toDouble(), (yday?.xp ?: 0).toDouble()), trend = last.map { it.xp.toDouble() })
            }
            if (d.blocks.isNotEmpty()) Panel("Your day") {
                val now = LocalTime.now(); DayTimeline(d.blocks, now.hour * 60 + now.minute)
                val next = d.blocks.filter { it.startMin > now.hour * 60 + now.minute }.minByOrNull { it.startMin }
                if (next != null) InsightLine("Next: ${next.label} at %02d:%02d".format(next.startMin / 60, next.startMin % 60))
            }
            val avg = last.takeLast(14).map { it.xp }.average().takeIf { !it.isNaN() } ?: 0.0
            val frac = if (avg <= 0) 0.5f else ((today?.xp ?: 0) / (avg * 1.5)).toFloat()
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${d.openTasks} open tasks · streak ${s?.streak ?: 0} days (best ${s?.bestStreak ?: 0})", style = MaterialTheme.typography.titleSmall)
                InsightLine(if (frac >= 1f) "You're ahead of your usual pace today." else "Below your usual pace so far; a small task would lift it.")
                Text("More dashboards", Modifier.pressable { onOpen("nx/dashboards") }.padding(vertical = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }

    @Composable
    fun Money(d: DashData, onOpen: (String) -> Unit = {}) {
        val spend = spendByDay(d.txns)
        val now = LocalDate.now(); val monthStart = now.withDayOfMonth(1)
        val month = d.txns.filter { it.localDate >= monthStart.toString() }
        val spent = month.filter { it.kind == "EXPENSE" }.sumOf { it.amount }; val income = month.filter { it.kind == "INCOME" }.sumOf { it.amount }
        val prevStart = monthStart.minusMonths(1)
        val prevSpent = d.txns.filter { it.kind == "EXPENSE" && it.localDate >= prevStart.toString() && it.localDate < monthStart.toString() }.sumOf { it.amount }
        val cat = month.filter { it.kind == "EXPENSE" }.groupBy { it.category }.mapValues { it.value.sumOf { t -> t.amount } }.entries.sortedByDescending { it.value }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiTile("Spent · month", spent, Modifier.weight(1f), prefix = "Rs ", higherIsBetter = false, delta = pct(spent, prevSpent * (now.dayOfMonth / now.lengthOfMonth().toDouble())), trend = daily(spend, 30))
                KpiTile("Income · month", income, Modifier.weight(1f), prefix = "Rs ")
            }
            Panel("Daily spending · 30 days vs previous 30") {
                TrendLine(daily(spend, 30), ghost = daily(spend, 30, 30), labels = (29 downTo 0).map { now.minusDays(it.toLong()).toString().takeLast(5) }, format = { "Rs %,.0f".format(it) })
            }
            if (cat.isNotEmpty()) Panel("Where it went") {
                DonutChart(cat.take(6).mapIndexed { i, e -> Slice(e.key, e.value, PALETTE[i % PALETTE.size]) }, "SPENT", "%,d".format(spent.toInt()))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                val f = if (prevSpent <= 0) 0.5f else (spent / prevSpent).toFloat()
                DialGauge(f, "VS LAST MONTH", "${(f * 100).toInt()}%", Modifier.weight(1f), diameter = 140.dp, higherIsBetter = false)
                InsightLine(when { prevSpent <= 0 -> "Not enough history yet."; spent > prevSpent -> "Already past last month's total."
                    else -> "${"%,d".format((prevSpent - spent).toInt())} left before you match last month." }, Modifier.weight(1f))
            }
            val projected = if (now.dayOfMonth > 3) spent / now.dayOfMonth * now.lengthOfMonth() else null
            projected?.let { InsightLine("At this pace the month ends near Rs ${"%,d".format(it.toInt())} (range ±15%).") }
        }
    }

    @Composable
    fun Deen(d: DashData, onOpen: (String) -> Unit = {}) {
        val c = LocalContext.current; val last = d.sum?.last30.orEmpty()
        val hifz = remember { HifzStore.all(c) }
        val dueCount = hifz.count { !HifzStore.due(it).isAfter(LocalDate.now()) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiTile("Prayers · 30d", last.sumOf { it.prayers }.toDouble(), Modifier.weight(1f), trend = last.map { it.prayers.toDouble() })
                KpiTile("Hifz due", dueCount.toDouble(), Modifier.weight(1f), higherIsBetter = false)
            }
            Panel("Prayer consistency · last 30 days") { CalendarHeat(last.map { it.prayers.toDouble() }, rows = 5); InsightLine("Each square is a day; darker means more of the five prayers.") }
            val full = last.count { it.prayers >= 5 }
            Panel("Full days") { GoalRibbon(full / 30f, expected = last.size.coerceAtLeast(1) / 30f); InsightLine("$full of the last ${last.size} days had all five prayers.") }
            if (hifz.isNotEmpty()) Panel("Hifz") { Text("${hifz.sumOf { it.memorised }} ayat memorised across ${hifz.size} surahs", style = MaterialTheme.typography.bodyMedium) }
        }
    }

    @Composable
    fun Health(d: DashData, onOpen: (String) -> Unit = {}) {
        val c = LocalContext.current; val last = d.sum?.last30.orEmpty()
        val logs = remember { runCatching { JsonStore(c, "nxlog_tempready").all().sortedBy { it.optString("d") }.takeLast(30) }.getOrDefault(emptyList()) }
        fun series(k: String) = logs.mapNotNull { it.optString(k).toDoubleOrNull() }.filter { it > 0 }
        val pulse = series("hr"); val sleep = series("sleep"); val temp = series("temp")
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiTile("Gym sets · 30d", last.sumOf { it.sets }.toDouble(), Modifier.weight(1f), trend = last.map { it.sets.toDouble() })
                KpiTile("Food logged", last.count { it.loggedFood }.toDouble(), Modifier.weight(1f), suffix = " days")
            }
            if (sleep.size > 1) Panel("Sleep · hours") { TrendLine(sleep, format = { "%.1f h".format(it) }); InsightLine("Average ${"%.1f".format(sleep.average())} h; aim for 7 or more.") }
            if (pulse.size > 1) Panel("Resting pulse") { TrendLine(pulse, accent = Color(0xFFFF6B6B), format = { "${it.toInt()} bpm" }) }
            if (temp.size > 1) Panel("Temperature") { TrendLine(temp, accent = Color(0xFFFFB454), format = { "%.1f °C".format(it) }) }
            Panel("Active days · 30") { CalendarHeat(last.map { (it.sets + if (it.loggedFood) 1 else 0).toDouble() }, rows = 5) }
            if (sleep.isEmpty() && pulse.isEmpty()) InsightLine("Log a morning check in Temperature & pulse to see sleep, pulse and temperature trends here.")
        }
    }

    @Composable
    fun Study(d: DashData, onOpen: (String) -> Unit = {}) {
        val last = d.sum?.last30.orEmpty()
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiTile("Study hours · 30d", last.sumOf { it.studyMinutes } / 60.0, Modifier.weight(1f), decimals = 1, trend = last.map { it.studyMinutes.toDouble() })
                KpiTile("Lessons", last.sumOf { it.lessons }.toDouble(), Modifier.weight(1f), trend = last.map { it.lessons.toDouble() })
            }
            Panel("Study minutes per day") { TrendLine(last.map { it.studyMinutes.toDouble() }, labels = last.map { it.date.takeLast(5) }, format = { "${it.toInt()} min" }) }
            Panel("Study days") { CalendarHeat(last.map { it.studyMinutes.toDouble() }, rows = 5) }
        }
    }

    @Composable
    fun Ai(d: DashData, onOpen: (String) -> Unit = {}) {
        val c = LocalContext.current
        val rows = remember { AgentMeter.names(c).map { it to AgentMeter.today(c, it).first }.filter { it.second > 0 } }
        val week = remember { AgentMeter.names(c).sumOf { AgentMeter.week(c, it) } }
        val total = rows.sumOf { it.second }
        val keys = remember { runCatching { com.nizam.app.net.ApiKeys.SLOTS.count { com.nizam.app.net.ApiKeys.enabled(c, it.id) } }.getOrDefault(0) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiTile("Tokens today", total.toDouble(), Modifier.weight(1f))
                KpiTile("Tokens · 7d", week.toDouble(), Modifier.weight(1f))
                KpiTile("AI keys on", keys.toDouble(), Modifier.weight(1f))
            }
            if (rows.isNotEmpty()) Panel("By agent today") { DonutChart(rows.sortedByDescending { it.second }.take(6).mapIndexed { i, r -> Slice(r.first, r.second.toDouble(), PALETTE[i % PALETTE.size]) }, "TOKENS", "%,d".format(total)) }
            else InsightLine("No agent usage recorded today.")
        }
    }

    @Composable
    fun System(d: DashData, onOpen: (String) -> Unit = {}) {
        val c = LocalContext.current
        val free = c.filesDir.usableSpace.toDouble(); val tot = c.filesDir.totalSpace.toDouble().coerceAtLeast(1.0)
        val checks = remember { runCatching { JsonStore(c, "nx_selfcheck").all().takeLast(10) }.getOrDefault(emptyList()) }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                DialGauge((free / tot).toFloat(), "STORAGE FREE", "${"%.1f".format(free / 1_073_741_824.0)} GB", Modifier.weight(1f), diameter = 140.dp)
                Column(Modifier.weight(1f)) {
                    KpiTile("Saved files", (c.filesDir.listFiles()?.size ?: 0).toDouble(), Modifier.fillMaxWidth())
                }
            }
            if (checks.isNotEmpty()) Panel("Self-Check history") { CalendarHeat(checks.map { (it.optInt("bad") * 2 + it.optInt("warn")).toDouble() }, rows = 1, accent = Color(0xFFFF6B6B)) }
            InsightLine("Open Self-Check for one-tap repairs.")
        }
    }

    /** Fallback for suites without their own data: how many tools, and which you actually use. */
    @Composable
    fun Generic(hub: Hub, onOpen: (String) -> Unit = {}) {
        val c = LocalContext.current
        val feats = remember(hub) { HubRegistry.of(hub) }
        val used = remember(hub) { feats.map { it to RecentFeatures.uses(c, it.route) }.filter { it.second > 0 }.sortedByDescending { it.second } }
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiTile("Tools", feats.size.toDouble(), Modifier.weight(1f))
                KpiTile("You use", used.size.toDouble(), Modifier.weight(1f))
            }
            if (used.isNotEmpty()) Panel("Most used here") {
                DonutChart(used.take(5).mapIndexed { i, u -> Slice(u.first.title, u.second.toDouble(), PALETTE[i % PALETTE.size]) }, "OPENS", used.sumOf { it.second }.toString())
            } else InsightLine("Open a few tools and your most-used ones will appear here.")
        }
    }

    /** The dashboard that belongs at the top of a suite. */
    @Composable
    fun ForHub(hub: Hub, container: AppContainer, onOpen: (String) -> Unit) {
        var data by remember { mutableStateOf<DashData?>(null) }
        LaunchedEffect(hub) { com.nizam.app.ui.design.guard { data = load(container) } }
        val d = data ?: run { Text("Reading your data…", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall); return }
        when (hub) {
            Hub.Money -> Money(d, onOpen)
            Hub.Deen -> Deen(d, onOpen)
            Hub.Health -> Health(d, onOpen)
            Hub.Learn -> Study(d, onOpen)
            Hub.Tools -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { Ai(d, onOpen); System(d, onOpen) }
            else -> Generic(hub, onOpen)
        }
    }
}
