package com.nizam.app.ui.style

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.feature.sci.LifeGraph
import com.nizam.app.ui.nav.Routes
import com.nizam.app.ui.screens.hubs.Feature
import com.nizam.app.ui.screens.hubs.HubRegistry
import com.nizam.app.ui.screens.hubs.RecentFeatures
import kotlin.math.abs

/** One KPI: label, number, and how it compares with your own 7-day average. good: true green, false red, null grey. */
@Composable
private fun Kpi(label: String, value: String, delta: String, good: Boolean?, onClick: () -> Unit, scope: androidx.compose.foundation.layout.RowScope) {
    val p = LocalSci.current
    with(scope) { Cell(onClick = onClick) { Lab(label); M(value, 19, bold = true, mono = false, maxLines = 1); M((if (good == true) "▲ " else if (good == false) "▼ " else "· ") + delta, 10, if (good == true) p.g else if (good == false) p.r else p.dim, maxLines = 1) } }
}

private fun avg(l: List<Float>): Float? = l.dropLast(1).takeIf { it.isNotEmpty() }?.average()?.toFloat()

/** Today's score, 0–100: the mean of what can be measured today (prayers, tasks, water). Shown with its parts so it is never a mystery number. */
private fun score(prayers: Float, done: Float, open: Float, water: Float, waterTarget: Float): Int {
    val parts = ArrayList<Float>(); parts += (prayers / 5f).coerceIn(0f, 1f)
    if (done + open > 0f) parts += done / (done + open)
    if (waterTarget > 0f) parts += (water / waterTarget).coerceIn(0f, 1f)
    return (parts.average() * 100).toInt()
}

/** Atlas Lab · Today: the one scientific dashboard. */
@Composable
internal fun LabToday(x: Sci) {
    val p = LocalSci.current; val d = x.d; val snap = x.snap
    Head(x, d.dateLine)
    // KPIs against your own week
    val today = score(x.prayersDone.toFloat(), d.doneToday.toFloat(), d.openCount.toFloat(), d.waterMl.toFloat(), d.waterTarget.toFloat())
    val past = snap?.days?.dropLast(1)?.takeLast(6)?.map { score(it.optDouble("prayers", 0.0).toFloat(), it.optDouble("tasks_done", 0.0).toFloat(), it.optDouble("tasks_open", 0.0).toFloat(), it.optDouble("water_ml", 0.0).toFloat(), d.waterTarget.toFloat()) }
    val pastAvg = past?.takeIf { it.isNotEmpty() }?.average()?.toInt()
    val prAvg = avg(x.s("prayers")); val spAvg = avg(x.s("spent"))
    Row(Modifier.fillMaxWidth()) {
        Kpi("Day score", "$today", pastAvg?.let { "${abs(today - it)} vs week" } ?: "prayers·tasks·water", pastAvg?.let { today >= it }, { x.open(Routes.INSIGHTS) }, this)
        Kpi("Prayers", "${x.prayersDone} / 5", prAvg?.let { "avg ${"%.1f".format(it)}" } ?: "today", prAvg?.let { x.prayersDone >= it }, { x.open(Routes.PRAYER) }, this)
        Kpi("Open", "${d.openCount}", "${d.doneToday} done", if (d.openCount == 0) true else null, { x.open(Routes.TASKS) }, this)
        Kpi("Spent", if (d.spentToday >= 10000) "%.1fk".format(d.spentToday / 1000) else "%,.0f".format(d.spentToday), spAvg?.takeIf { it > 0f }?.let { "${abs(((d.spentToday - it) / it * 100).toInt())}% ${if (d.spentToday <= it) "under" else "over"}" } ?: d.currency, spAvg?.takeIf { it > 0f }?.let { d.spentToday <= it }, { x.open(Routes.FINANCE) }, this)
    }
    // the day: ring, now, then, being
    SciBox(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DayRing(x, Modifier.size(150.dp))
            Column(Modifier.weight(1f)) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) { Lab("Now", color = p.w); NowBlock(x) }
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
                Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Lab("Then")
                    d.nextPrayer?.let { M("${it.first.lowercase()} in ${countdown(it.second)}", 11, p.dim, modifier = Modifier.clickable { x.open(Routes.PRAYER) }, maxLines = 1) }
                    x.tasks.drop(1).take(2).forEach { t -> M("· " + t.title, 11, p.dim, modifier = Modifier.clickable { x.open(Routes.TASKS) }, maxLines = 1) }
                    if (d.nextPrayer == null && x.tasks.size < 2) M("nothing queued", 11, p.dim)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
                BeingLine(x, Modifier.padding(horizontal = 10.dp, vertical = 8.dp))
            }
        }
    }
    Lab("Trends · 7 days"); Tiles(x)
    // week at a glance
    Lab("Week at a glance · brighter = more")
    val grow by rememberDrawIn(snap?.at, 1100)
    SciBox(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (snap == null) M("reading your last 30 days…", 11, p.dim)
            Row(Modifier.fillMaxWidth()) { Lab("", Modifier.width(64.dp)); snap?.dates?.takeLast(7)?.forEach { dt -> Lab(dt.dayOfWeek.name.take(1), Modifier.weight(1f)) } }
            listOf("prayers" to p.a, "tasks_done" to p.v, "habits" to p.v, "water_ml" to p.a, "sleep" to p.a, "gym_sets" to p.g, "spent" to p.w, "screen_unlocks" to p.r).forEachIndexed { ri, (k, col) ->
                val vals = x.s(k); val mx = vals.maxOrNull()?.takeIf { it > 0f } ?: 1f
                Row(Modifier.fillMaxWidth().clickable { x.open(LifeGraph.ROUTES[k] ?: Routes.INSIGHTS) }, verticalAlignment = Alignment.CenterVertically) {
                    Lab(LifeGraph.NAMES[k] ?: k, Modifier.width(64.dp))
                    vals.forEachIndexed { i, v -> val k2 = ((grow * 9f) - ri * 0.5f - i * 0.4f).coerceIn(0f, 1f)
                        Box(Modifier.weight(1f).height(20.dp).padding(end = 3.dp).background(col.copy(alpha = (0.08f + 0.92f * (v / mx)) * k2)).let { if (i == vals.lastIndex) it.border(1.dp, p.text) else it }) }
                }
            }
        }
    }
    // what moves what
    Lab("What moves what · from your own data")
    SciBox(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val links = snap?.corr.orEmpty().take(4)
            if (links.isEmpty()) M(if (snap == null) "reading…" else "No links yet. Atlas shows one only after ${LifeGraph.MIN_DAYS} days where two areas both have numbers.", 12, p.dim, mono = false)
            links.forEach { c -> Row(Modifier.fillMaxWidth().clickable { x.open(LifeGraph.ROUTES[c.a] ?: Routes.INSIGHTS) }, verticalAlignment = Alignment.CenterVertically) {
                M((LifeGraph.NAMES[c.a] + " ↔ " + LifeGraph.NAMES[c.b]).padEnd(18), 11, modifier = Modifier.width(132.dp), maxLines = 1)
                Meter(abs(c.r), if (c.r > 0) p.g else p.r, Modifier.weight(1f), 8.dp); M("  ${"%+.2f".format(c.r)} · ${c.n}d", 10, p.dim) } }
            if (links.isNotEmpty()) M("green: rise together · red: move opposite. Links, not proof of cause.", 10, p.dim, mono = false)
            Act("open system graph") { x.open("nx/lifegraph") }
        }
    }
    CmdBar { x.open(Routes.ASK) }; Suggest(x); Notice(x)
}

/** Which live numbers belong to which hub. */
private val HUB_METRICS = mapOf("Money" to listOf("spent"), "Deen" to listOf("prayers"), "Health" to listOf("sleep", "water_ml", "gym_sets"), "Work" to listOf("tasks_done", "habits"), "Memory" to listOf("mood"), "Tools" to listOf("screen_unlocks"))

@Composable
private fun ToolRow(f: Feature, open: (String) -> Unit) {
    val p = LocalSci.current
    Row(Modifier.fillMaxWidth().background(p.panel).border(0.5.dp, p.line).clickable { open(f.route) }.padding(horizontal = 10.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { M(f.title, 13, bold = true, mono = false, maxLines = 1); if (f.blurb.isNotBlank()) M(f.blurb, 10, p.dim, mono = false, maxLines = 1) }
        M("›", 14, p.a)
    }
}

/** Atlas Lab · a hub (Money, Deen, Work…): its live numbers, then every tool as a row. */
@Composable
fun LabHub(hub: com.nizam.app.ui.design.Hub, container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current
    val top = remember(hub) { HubRegistry.top(hub) }
    val groups = remember(hub) { HubRegistry.grouped(hub) }
    val recent = remember(hub) { RecentFeatures.recent(c).mapNotNull { r -> HubRegistry.byRoute(r)?.takeIf { it.hub == hub } }.take(4) }
    val snap by produceState(LifeGraph.cached) { value = runCatching { LifeGraph.load(container, c) }.getOrNull() ?: value }
    val keys = HUB_METRICS[hub.name].orEmpty()
    run {
        val p = LocalSci.current
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            item("head") { Column(Modifier.padding(bottom = 8.dp)) { Lab("hub · ${top.size + groups.sumOf { it.second.size }} tools · ${groups.size} groups"); M(hub.name, 26, bold = true, mono = false) } }
            if (keys.isNotEmpty()) item("kpi") {
                Row(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
                    keys.forEach { k -> val s = snap?.series(k) ?: emptyList(); val col = when (k) { "spent" -> p.w; "gym_sets" -> p.g; "mood", "habits", "tasks_done" -> p.v; "screen_unlocks" -> p.r; else -> p.a }
                        Cell(onClick = { open(LifeGraph.ROUTES[k] ?: Routes.INSIGHTS) }) {
                            Lab((LifeGraph.NAMES[k] ?: k) + " · 7 d"); M(snap?.nodes?.firstOrNull { it.id == "m:$k" }?.value ?: "…", 18, bold = true, mono = false, maxLines = 1)
                            if (s.size >= 2) Spark(s, col, Modifier.fillMaxWidth().height(26.dp).padding(top = 4.dp), fill = true) else M("no history yet", 10, p.dim) } }
                }
            }
            if (recent.isNotEmpty()) { item("rh") { Lab("Recent", Modifier.padding(top = 4.dp, bottom = 4.dp)) }; items(recent.size, key = { "r" + recent[it].route }) { ToolRow(recent[it], open) } }
            if (top.isNotEmpty()) { item("th") { Lab("Main", Modifier.padding(top = 12.dp, bottom = 4.dp)) }; items(top.size, key = { "t" + top[it].route }) { ToolRow(top[it], open) } }
            groups.forEach { (name, list) ->
                item("g$name") { Lab("$name · ${list.size}", Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                items(list.size, key = { "g" + name + list[it].route }) { ToolRow(list[it], open) }
            }
        }
    }
}

val SciSpecs: List<Spec> get() = listOf(
    ScreenSpec("lifegraph", "System graph", "A living graph of your areas, tasks, promises, people and linked notes", Icons.Outlined.Hub, com.nizam.app.ui.design.Hub.Memory,
        "graph system links network connections obsidian life map correlation") { ct, open -> LifeGraphScreen(ct, open) }
)
