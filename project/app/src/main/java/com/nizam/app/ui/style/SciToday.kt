package com.nizam.app.ui.style

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.data.db.Task
import com.nizam.app.feature.being.Being
import com.nizam.app.feature.pro.Promises
import com.nizam.app.feature.pro.Sleep
import com.nizam.app.feature.rune.RuneBind
import com.nizam.app.feature.sci.LifeGraph
import com.nizam.app.ui.design.rememberSafeScope
import com.nizam.app.ui.nav.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Everything a scientific view needs: today's numbers, the life graph, your open tasks, the clock, and how to act. */
class Sci(val d: TodayData, val snap: LifeGraph.Snapshot?, val container: AppContainer, val open: (String) -> Unit, val tasks: List<Task>, val now: LocalTime, val scope: CoroutineScope, val c: Context) {
    val first: Task? get() = tasks.firstOrNull()
    fun done(t: Task) { scope.launch { runCatching { container.taskRepository.toggleComplete(t) } } }
    fun later(t: Task) { scope.launch { runCatching { container.taskRepository.save(t.copy(due = System.currentTimeMillis() + 3_600_000L)) } } }
    val clock: String get() = "%02d:%02d:%02d".format(now.hour, now.minute, now.second)
    val prayersDone: Int get() = d.prayers.count { it.second }
    val beingLine: String get() = if (Being.born(c)) Being.name(c) + " · " + Being.activity(c).lowercase() else "being: not hatched yet"
    fun s(key: String): List<Float> = snap?.series(key) ?: emptyList()
}

class Area(val key: String, val label: String, val value: String, val num: Float, val note: String, val route: String, val series: List<Float>, val color: Color, val frac: Float, val bars: Boolean = false)

private fun Sci.areas(p: SciPal): List<Area> {
    val sleepH = snap?.days?.lastOrNull()?.takeIf { it.has("sleep") }?.optDouble("sleep")
    val habits = snap?.today("habits")?.toInt() ?: 0
    val owed = runCatching { Promises.all(c).filter { Promises.open(it) } }.getOrDefault(emptyList())
    return listOf(
        Area("prayers", "Prayers", "$prayersDone / 5", prayersDone.toFloat(), d.nextPrayer?.let { "${it.first} in ${countdown(it.second)}" } ?: "all times passed", Routes.PRAYER, s("prayers"), p.a, prayersDone / 5f, true),
        Area("tasks", "Tasks", "${d.openCount} open", d.openCount.toFloat(), "${d.doneToday} done today", Routes.TASKS, s("tasks_done"), p.v, if (d.openCount + d.doneToday == 0) 0f else d.doneToday.toFloat() / (d.openCount + d.doneToday), true),
        Area("spent", "Spent today", money(d.spentToday, d.currency), d.spentToday.toFloat(), "month " + money(d.spentMonth, d.currency), Routes.FINANCE, s("spent"), p.w, if (d.spentMonth <= 0) 0f else (d.spentToday / d.spentMonth).toFloat()),
        Area("sleep", "Sleep", sleepH?.let { Sleep.fmt(it) } ?: "not logged", (sleepH ?: 0.0).toFloat(), "target ${"%.1f".format(Sleep.target(c))} h", "nx/sleep", s("sleep"), p.a, ((sleepH ?: 0.0) / Sleep.target(c)).toFloat()),
        Area("water", "Water", "${d.waterMl} ml", d.waterMl.toFloat(), "of ${d.waterTarget} ml", Routes.DIET, s("water_ml"), p.a, if (d.waterTarget <= 0) 0f else d.waterMl.toFloat() / d.waterTarget, true),
        Area("food", "Food", "${d.kcal} kcal", d.kcal.toFloat(), "of ${d.kcalTarget}", Routes.DIET, emptyList(), p.g, if (d.kcalTarget <= 0) 0f else d.kcal.toFloat() / d.kcalTarget),
        Area("gym", "Gym", "${d.sets} sets", d.sets.toFloat(), "today", Routes.GYM, s("gym_sets"), p.g, (d.sets / 12f), true),
        Area("habits", "Habits", "$habits done", habits.toFloat(), "today", "habits2", s("habits"), p.v, habits / 5f),
        Area("promises", "Promises", "${owed.size} open", owed.size.toFloat(), owed.count { Promises.overdue(it) }.let { if (it > 0) "$it overdue" else "none overdue" }, "nx/promises", emptyList(), p.w, 0f)
    )
}

@Composable
fun SciToday(style: UiStyle, d: TodayData, container: AppContainer, onOpen: (String) -> Unit) {
    val c = LocalContext.current
    val scope = rememberSafeScope()
    val pal = if (style == UiStyle.PAPER) SciPaper else SciDark
    val snap by produceState(LifeGraph.cached, d.openCount, d.doneToday, d.spentToday, d.waterMl) { value = runCatching { LifeGraph.load(container, c, force = value != null) }.getOrNull() ?: value }
    val all by container.taskRepository.all().collectAsState(initial = emptyList())
    val now by produceState(LocalTime.now()) { while (true) { delay(1000); value = LocalTime.now() } }
    val x = Sci(d, snap, container, onOpen, all.filter { it.completedAt == null && it.deletedAt == null }.sortedBy { it.due ?: Long.MAX_VALUE }, now, scope, c)
    CompositionLocalProvider(LocalSci provides pal) {
        Box(Modifier.fillMaxSize().background(pal.bg)) {
            if (style == UiStyle.GRAPH) GraphToday(x)
            else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                when (style) {
                    UiStyle.NOTEBOOK -> NotebookToday(x); UiStyle.INSTRUMENT -> InstrumentToday(x); UiStyle.PAPER -> PaperToday(x); UiStyle.HEATGRID -> HeatToday(x)
                    UiStyle.TERMINAL -> TerminalToday(x); UiStyle.GITLOG -> GitToday(x); UiStyle.CELLS -> CellsToday(x); else -> ProcessToday(x)
                }
                Spacer(Modifier.height(120.dp))
            }
        }
    }
}

// ───────────────────────── shared blocks ─────────────────────────

@Composable
internal fun Head(x: Sci, label: String, title: String = "Today", serif: Boolean = false) {
    val p = LocalSci.current
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.Bottom) {
        Column(Modifier.weight(1f)) { Lab(label); M(title, 30, bold = !serif, mono = false, serif = serif, maxLines = 1) }
        Column(horizontalAlignment = Alignment.End) { M(x.clock, 12, p.a); M("bat ${RuneBind.battery(x.c)}%" + if (x.d.unread > 0) " · ${x.d.unread} new" else "", 10, p.dim) }
    }
}

@Composable
internal fun BeingLine(x: Sci, modifier: Modifier = Modifier) {
    val p = LocalSci.current
    Row(modifier.clickable { x.open("nx/being") }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SciBot(22.dp); M(x.beingLine, 11, p.dim, maxLines = 2)
    }
}

/** What to do now: your first open task, with real buttons. */
@Composable
internal fun NowBlock(x: Sci, doneWord: String = "done") {
    val p = LocalSci.current; val t = x.first
    if (t == null) { M("Nothing open. You're clear.", 13, p.g, mono = false); Act("add a task") { x.open(Routes.TASKS) } }
    else Column {
        M(t.title, 14, bold = true, mono = false, maxLines = 2, modifier = Modifier.clickable { x.open(Routes.TASKS) })
        Row { Act(doneWord, p.g) { x.done(t) }; Act("+1h") { x.later(t) }; Act("open") { x.open(Routes.TASKS) } }
    }
}

@Composable
internal fun Suggest(x: Sci) {
    Row { Act("log water") { x.open(Routes.DIET) }; Act("next prayer") { x.open(Routes.PRAYER) }; Act("add expense") { x.open(Routes.FINANCE) }; Act("ask") { x.open(Routes.ASK) } }
}

@Composable
internal fun Notice(x: Sci) {
    val p = LocalSci.current
    x.d.insights.firstOrNull()?.let { (hd, body) -> Row(Modifier.fillMaxWidth().border(1.dp, p.line).clickable { x.open(Routes.INSIGHTS) }.padding(9.dp)) { M("! ", 11, p.w); M("$hd. $body", 11, p.dim, maxLines = 2) } }
}

@Composable
private fun AreaChart(a: Area, modifier: Modifier) { if (a.series.size < 2) Meter(a.frac, a.color, modifier, 8.dp) else if (a.bars) BarsChart(a.series, a.color, modifier) else Spark(a.series, a.color, modifier, fill = a.key == "sleep") }

private fun corrText(c: LifeGraph.Corr): String = "${LifeGraph.NAMES[c.a]} and ${LifeGraph.NAMES[c.b]} ${if (c.r > 0) "rise and fall together" else "move in opposite directions"}"

// ───────────────────────── D · lab notebook ─────────────────────────

@Composable
private fun NotebookToday(x: Sci) {
    val p = LocalSci.current
    Head(x, x.d.dateLine)
    BeingLine(x)
    HubStrip(x.open); CmdBar { x.open(Routes.ASK) }; Suggest(x)
    x.areas(p).forEach { a ->
        Column(Modifier.fillMaxWidth().clickable { x.open(a.route) }) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
            Row(Modifier.padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(128.dp)) { Lab(a.label); M(a.value, 18, bold = true, mono = false, maxLines = 1) }
                M(a.note, 10, p.dim, modifier = Modifier.weight(1f).padding(horizontal = 6.dp), maxLines = 2)
                AreaChart(a, Modifier.width(92.dp).height(24.dp))
            }
        }
    }
    Notice(x)
}

// ───────────────────────── E · instrument panel ─────────────────────────

@Composable
internal fun DayRing(x: Sci, modifier: Modifier = Modifier) {
    val p = LocalSci.current; val grow by rememberDrawIn(Unit, 1200); val pulse by rememberPulse()
    val frac = (x.now.toSecondOfDay() / 86400f)
    val next = x.d.nextPrayer
    Box(modifier.clickable { x.open(Routes.PRAYER) }, Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(10.dp)) {
            val st = 9.dp.toPx(); val r = size.minDimension / 2f - st; val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(p.line, r, c, style = Stroke(st))
            for (i in 0 until 24) { val a = (i / 24f) * 2f * PI.toFloat() - PI.toFloat() / 2f; val r0 = r + st * 0.7f; val r1 = r + st * (if (i % 6 == 0) 1.5f else 1.05f)
                drawLine(p.dim.copy(alpha = if (i % 6 == 0) 0.9f else 0.4f), Offset(c.x + cos(a) * r0, c.y + sin(a) * r0), Offset(c.x + cos(a) * r1, c.y + sin(a) * r1), 2f) }
            drawArc(p.a, -90f, 360f * frac * grow, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = Stroke(st, cap = StrokeCap.Butt))
            val na = frac * grow * 2f * PI.toFloat() - PI.toFloat() / 2f; val np = Offset(c.x + cos(na) * r, c.y + sin(na) * r)
            drawCircle(p.w.copy(alpha = 0.3f * (1f - pulse)), st * (0.8f + pulse), np); drawCircle(p.w, st * 0.62f, np)
            if (next != null) { val pa = ((x.now.toSecondOfDay() + next.second * 60) % 86400 / 86400f) * 2f * PI.toFloat() - PI.toFloat() / 2f; val pp = Offset(c.x + cos(pa) * r, c.y + sin(pa) * r)
                drawCircle(p.panel, st * 0.5f, pp); drawCircle(p.w, st * 0.5f, pp, style = Stroke(3f)) }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            M(next?.let { countdown(it.second) } ?: x.clock.take(5), 20, bold = true, mono = false)
            Lab(next?.let { "to " + it.first } ?: "today")
        }
    }
}

@Composable
private fun Tiles(x: Sci, fig: Boolean = false) {
    val p = LocalSci.current
    x.areas(p).chunked(3).forEachIndexed { r, row ->
        Row(Modifier.fillMaxWidth()) {
            row.forEachIndexed { i, a -> Cell(onClick = { x.open(a.route) }) { Lab(if (fig) "Fig ${r * 3 + i + 1} · ${a.label}" else a.label); M(a.value, 16, bold = true, mono = false, maxLines = 1); AreaChart(a, Modifier.fillMaxWidth().height(22.dp).padding(top = 4.dp)) } }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun InstrumentToday(x: Sci) {
    val p = LocalSci.current
    Head(x, x.d.dateLine); HubStrip(x.open)
    SciBox(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DayRing(x, Modifier.size(156.dp))
            Column(Modifier.weight(1f).padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Lab("Being"); BeingLine(x)
                Lab("Prayers today"); Row(Modifier.clickable { x.open(Routes.PRAYER) }) { Squares(x.d.prayers.map { it.second }) }
                Lab("Now", color = p.w); NowBlock(x)
            }
        }
    }
    Tiles(x)
    CmdBar { x.open(Routes.ASK) }; Suggest(x); Notice(x)
}

// ───────────────────────── F · paper report ─────────────────────────

@Composable
private fun PaperToday(x: Sci) {
    val p = LocalSci.current; val d = x.d
    Lab("Atlas · daily report · ${d.dateLine} · ${x.clock.take(5)}")
    M("Today, in one place", 28, serif = true, mono = false)
    M("${d.greeting}. ${x.prayersDone} of 5 prayers are done, ${d.openCount} task${if (d.openCount == 1) " is" else "s are"} open and ${money(d.spentToday, d.currency)} has been spent today." +
        (d.nextPrayer?.let { " ${it.first} is in ${countdown(it.second)}." } ?: ""), 14, p.dim, serif = true, mono = false)
    BeingLine(x); HubStrip(x.open)
    Tiles(x, fig = true)
    SciBox(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp)) { Lab("Next action"); NowBlock(x) } }
    CmdBar { x.open(Routes.ASK) }; Suggest(x)
    x.snap?.corr?.firstOrNull()?.let { c -> M("Note. ${corrText(c)} in your last ${c.n} days (r = ${"%+.2f".format(c.r)}). This is a link, not proof of cause.", 12, p.dim, serif = true, mono = false, modifier = Modifier.clickable { x.open("nx/sleep") }) }
    Notice(x)
}

// ───────────────────────── G · heat grid ─────────────────────────

@Composable
private fun HeatToday(x: Sci) {
    val p = LocalSci.current; val grow by rememberDrawIn(x.snap?.at, 1100)
    Head(x, x.d.dateLine); HubStrip(x.open)
    Row(Modifier.fillMaxWidth()) {
        Cell(onClick = { x.open(Routes.PRAYER) }) { Lab("Next prayer"); M(x.d.nextPrayer?.first ?: "–", 16, bold = true, mono = false); M(x.d.nextPrayer?.let { "T−" + countdown(it.second) } ?: "", 10, p.w) }
        Cell(1.4f) { Lab("Now", color = p.w); NowBlock(x) }
    }
    SciBox(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val dates = x.snap?.dates?.takeLast(7).orEmpty()
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) { Lab("Last 7 days · brighter = more"); Lab(dates.joinToString(" ") { it.dayOfWeek.name.take(1) }) }
            if (x.snap == null) M("reading your last 30 days…", 11, p.dim)
            listOf("prayers" to p.a, "tasks_done" to p.v, "habits" to p.v, "water_ml" to p.a, "sleep" to p.a, "gym_sets" to p.g, "spent" to p.w, "screen_unlocks" to p.r).forEachIndexed { ri, (k, col) ->
                val vals = x.s(k); val mx = vals.maxOrNull()?.takeIf { it > 0f } ?: 1f
                Row(Modifier.fillMaxWidth().clickable { x.open(LifeGraph.ROUTES[k] ?: Routes.INSIGHTS) }, verticalAlignment = Alignment.CenterVertically) {
                    Lab(LifeGraph.NAMES[k] ?: k, Modifier.width(64.dp))
                    vals.forEachIndexed { i, v -> val k2 = ((grow * 9f) - ri * 0.5f - i * 0.4f).coerceIn(0f, 1f)
                        Box(Modifier.weight(1f).height(24.dp).padding(end = 3.dp).background(col.copy(alpha = (0.08f + 0.92f * (v / mx)) * k2)).let { if (i == vals.lastIndex) it.border(1.dp, p.text) else it }) }
                }
            }
        }
    }
    Tiles(x)
    BeingLine(x); CmdBar { x.open(Routes.ASK) }; Suggest(x); Notice(x)
}

// ───────────────────────── H · terminal ─────────────────────────

@Composable
private fun TerminalToday(x: Sci) {
    val p = LocalSci.current
    Row { M("atlas@you ", 12, p.dim); M("~/today ", 12, p.a); M(x.clock, 12, p.dim) }
    Row { M("$ ", 12, p.g); M("atlas status", 12) }
    M("date     ${x.d.dateLine}", 12, p.dim, maxLines = 1)
    Row(Modifier.clickable { x.open("nx/being") }) { M("being    ", 12, p.dim); M(x.beingLine, 12, p.a, maxLines = 1) }
    M("AREA        NOW            LEVEL", 11, p.dim, modifier = Modifier.padding(top = 4.dp))
    x.areas(p).forEach { a ->
        Row(Modifier.fillMaxWidth().clickable { x.open(a.route) }, verticalAlignment = Alignment.CenterVertically) {
            M(a.label.lowercase().take(11).padEnd(12), 12); M(a.value.take(14).padEnd(15), 12, a.color)
            Meter(a.frac, a.color, Modifier.width(72.dp), 9.dp); M("  " + a.note, 10, p.dim, maxLines = 1)
        }
    }
    Row(Modifier.padding(top = 4.dp)) { M("$ ", 12, p.g); M("atlas next", 12) }
    Row { M("→ ", 12, p.w); Column { NowBlock(x) } }
    x.d.nextPrayer?.let { Row(Modifier.clickable { x.open(Routes.PRAYER) }) { M("→ ", 12, p.w); M("${it.first.lowercase()} in ${countdown(it.second)}", 12) } }
    x.tasks.drop(1).take(3).forEach { t -> Row(Modifier.clickable { x.open(Routes.TASKS) }) { M("  ", 12); M("· " + t.title, 12, p.dim, maxLines = 1) } }
    Row(Modifier.padding(top = 4.dp)) { M("$ ", 12, p.g); M("atlas hint", 12) }
    x.snap?.corr?.firstOrNull()?.let { c -> M("${corrText(c).lowercase()} (r=${"%+.2f".format(c.r)}, n=${c.n})", 11, p.dim) } ?: M("no links between areas yet: needs ${LifeGraph.MIN_DAYS} days of numbers", 11, p.dim)
    Row { M("try: ", 11, p.dim); Suggest(x) }
    HubStrip(x.open)
    CmdBar("$ ") { x.open(Routes.ASK) }
}

// ───────────────────────── I · git log ─────────────────────────

@Composable
private fun Commit(lane: Int, color: Color, state: Int, hash: String, title: String, sub: String, onClick: () -> Unit, extra: @Composable () -> Unit = {}) {
    val p = LocalSci.current; val pulse by rememberPulse()
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Canvas(Modifier.width(50.dp).height(48.dp)) {
            listOf(p.a, p.w, p.v).forEachIndexed { i, c -> drawLine(c.copy(alpha = 0.35f), Offset(10.dp.toPx() + i * 14.dp.toPx(), 0f), Offset(10.dp.toPx() + i * 14.dp.toPx(), size.height), 3f) }
            val o = Offset(10.dp.toPx() + lane * 14.dp.toPx(), 16.dp.toPx())
            drawCircle(p.bg, 8.dp.toPx(), o)
            when (state) { 0 -> drawCircle(color, 5.dp.toPx(), o, style = Stroke(4f)); 1 -> { drawCircle(color.copy(alpha = 0.3f * (1f - pulse)), (6 + 8 * pulse).dp.toPx(), o); drawCircle(color, 6.dp.toPx(), o) }; else -> drawCircle(color, 5.dp.toPx(), o) }
        }
        Column(Modifier.weight(1f)) { M(hash, 10, if (state == 1) p.w else p.dim); M(title, 13, bold = true, mono = false, maxLines = 1); if (sub.isNotBlank()) M(sub, 10, p.dim, maxLines = 1); extra() }
    }
}

private fun hash(s: String) = "%04x".format(s.hashCode() and 0xFFFF)

@Composable
private fun GitToday(x: Sci) {
    val p = LocalSci.current; val d = x.d
    Head(x, "git log --graph · ${d.dateLine}")
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) { M("● deen", 10, p.a); M("● money · body", 10, p.w); M("● work", 10, p.v) }
    BeingLine(x)
    Lab("staged · not done yet")
    d.prayers.filter { !it.second }.reversed().forEach { (n, _) -> Commit(0, p.a, 0, "····", n, if (d.nextPrayer?.first == n) "deen · in ${countdown(d.nextPrayer.second)}" else "deen", { x.open(Routes.PRAYER) }) }
    x.tasks.drop(1).take(4).reversed().forEach { t -> Commit(2, p.v, 0, "····", t.title, "work · open", { x.open(Routes.TASKS) }) }
    Lab("HEAD → now ${x.clock.take(5)}", color = p.w)
    val f = x.first
    if (f != null) Commit(2, p.v, 1, "wip (HEAD)", f.title, "work", { x.open(Routes.TASKS) }) { Row { Act("commit", p.g) { x.done(f) }; Act("+1h") { x.later(f) } } }
    else Commit(2, p.g, 1, "HEAD", "Working tree clean", "nothing open", { x.open(Routes.TASKS) })
    Lab("committed · done today")
    if (d.doneToday > 0) Commit(2, p.v, 2, hash("t${d.doneToday}"), "${d.doneToday} task${if (d.doneToday == 1) "" else "s"} closed", "work", { x.open(Routes.TASKS) })
    if (d.spentToday > 0) Commit(1, p.w, 2, hash("s${d.spentToday}"), "Spent ${money(d.spentToday, d.currency)}", "money · month ${money(d.spentMonth, d.currency)}", { x.open(Routes.FINANCE) })
    if (d.sets > 0) Commit(1, p.w, 2, hash("g${d.sets}"), "Gym · ${d.sets} sets", "body", { x.open(Routes.GYM) })
    if (d.waterMl > 0) Commit(1, p.w, 2, hash("w${d.waterMl}"), "Water · ${d.waterMl} ml", "body · of ${d.waterTarget}", { x.open(Routes.DIET) })
    d.prayers.filter { it.second }.reversed().forEach { (n, _) -> Commit(0, p.a, 2, hash(n + d.dateLine), "$n prayed", "deen", { x.open(Routes.PRAYER) }) }
    val y = x.snap?.days?.dropLast(1)?.lastOrNull()
    if (y != null) M("yesterday: ${y.optInt("prayers")} prayers · ${y.optInt("tasks_done")} tasks · ${money(y.optDouble("spent", 0.0), d.currency)}", 10, p.dim, modifier = Modifier.clickable { x.open(Routes.INSIGHTS) })
    HubStrip(x.open); CmdBar { x.open(Routes.ASK) }; Suggest(x)
}

// ───────────────────────── J · notebook cells ─────────────────────────

@Composable
private fun CodeCell(n: Int, code: String, content: @Composable () -> Unit) {
    val p = LocalSci.current
    SciBox(Modifier.fillMaxWidth()) { Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 9.dp, vertical = 6.dp)) { M("In [$n]: ", 11, p.a); M(code, 11, p.v) }
        Box(Modifier.fillMaxWidth().height(1.dp).background(p.line))
        Column(Modifier.padding(9.dp)) { content() }
    } }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CellsToday(x: Sci) {
    val p = LocalSci.current; val d = x.d
    Head(x, "today.ipynb · ${d.dateLine}")
    Row(verticalAlignment = Alignment.CenterVertically) { val pulse by rememberPulse(); Box(Modifier.size(7.dp).background(p.g.copy(alpha = 0.4f + 0.6f * pulse), CircleShape)); M(" kernel: atlas · live", 10, p.dim) }
    CodeCell(1, "atlas.now()") { NowBlock(x); d.nextPrayer?.let { M("then ${it.first} in ${countdown(it.second)}", 11, p.dim, modifier = Modifier.clickable { x.open(Routes.PRAYER) }) } }
    CodeCell(2, "prayers.today()") { Row(Modifier.clickable { x.open(Routes.PRAYER) }, verticalAlignment = Alignment.CenterVertically) { Squares(d.prayers.map { it.second }); M("  " + d.prayers.joinToString(" ") { (n, ok) -> n.lowercase() + if (ok) "✓" else "" }, 10, p.dim, maxLines = 2) } }
    CodeCell(3, "money.spent(days=7)") { Row(Modifier.fillMaxWidth().clickable { x.open(Routes.FINANCE) }, Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Column { Count(d.spentToday.toFloat(), 20) { money(it.toDouble(), d.currency) }; M("month " + money(d.spentMonth, d.currency), 10, p.dim) }; Spark(x.s("spent"), p.w, Modifier.width(120.dp).height(30.dp)) } }
    CodeCell(4, "atlas.infer()") {
        val c = x.snap?.corr?.firstOrNull()
        if (c == null) M(if (x.snap == null) "reading your last 30 days…" else "No finding yet. Atlas needs ${LifeGraph.MIN_DAYS} days where two areas both have numbers before it will say they are linked.", 12, p.dim, mono = false)
        else Column(Modifier.clickable { x.open(LifeGraph.ROUTES[c.a] ?: Routes.INSIGHTS) }) {
            Row { M("finding  ", 11, p.g); M(corrText(c), 12, mono = false) }
            Row(verticalAlignment = Alignment.CenterVertically) { M("strength ", 10, p.dim); Meter(abs(c.r), if (c.r > 0) p.g else p.r, Modifier.width(120.dp), 8.dp); M("  r=${"%+.2f".format(c.r)} · n=${c.n} days", 10, p.dim) }
            M("a link in your own data, not proof of cause", 10, p.dim)
        }
    }
    CodeCell(5, "day.table()") { FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { x.areas(p).forEach { a -> Row(Modifier.clickable { x.open(a.route) }) { M(a.label.lowercase() + " ", 11, p.dim); M(a.value, 11, a.color) } } } }
    CodeCell(6, "being.status()") { BeingLine(x) }
    CmdBar("In [7]: ask or run anything") { x.open(Routes.ASK) }; Suggest(x); HubStrip(x.open)
}

// ───────────────────────── K · process monitor ─────────────────────────

@Composable
private fun ProcessToday(x: Sci) {
    val p = LocalSci.current; val d = x.d
    Lab("atlas top · ${d.dateLine} · ${x.clock} · up ${x.now.hour}h ${x.now.minute}m")
    x.areas(p).filter { it.key in setOf("prayers", "tasks", "water", "food", "sleep") }.forEach { a ->
        Row(Modifier.fillMaxWidth().clickable { x.open(a.route) }, verticalAlignment = Alignment.CenterVertically) { M(a.label.lowercase().padEnd(9), 12); M("[", 12, p.dim); Meter(a.frac, a.color, Modifier.weight(1f), 11.dp); M("] ", 12, p.dim); M(a.value, 11, maxLines = 1) }
    }
    BeingLine(x)
    Row(Modifier.fillMaxWidth().background(p.a).padding(horizontal = 4.dp, vertical = 2.dp)) { M(" PID  AREA    STATE     NAME", 11, p.bg, bold = true) }
    var pid = 100
    @Composable fun Proc(area: String, state: String, col: Color, name: String, route: String, dim: Boolean = false) { pid++
        Row(Modifier.fillMaxWidth().clickable { x.open(route) }.padding(horizontal = 4.dp)) { M(" $pid  ${area.padEnd(7)} ", 11, if (dim) p.dim else p.text); M(state.padEnd(9) + " ", 11, col); M(name, 11, if (dim) p.dim else p.text, maxLines = 1) } }
    x.first?.let { Proc("work", "RUNNING", p.w, it.title, Routes.TASKS) }
    x.tasks.drop(1).take(4).forEach { Proc("work", "READY", p.text, it.title, Routes.TASKS) }
    runCatching { Promises.all(x.c).filter { Promises.overdue(it) } }.getOrDefault(emptyList()).take(3).forEach { Proc("social", "BLOCKED", p.r, "promise: " + it.optString("text"), "nx/promises") }
    d.prayers.filter { !it.second }.forEach { (n, _) -> Proc("deen", "SLEEPING", p.a, n.lowercase() + if (d.nextPrayer?.first == n) " (wakes in ${countdown(d.nextPrayer.second)})" else "", Routes.PRAYER) }
    d.prayers.filter { it.second }.forEach { (n, _) -> Proc("deen", "DONE", p.dim, n.lowercase(), Routes.PRAYER, true) }
    if (d.doneToday > 0) Proc("work", "DONE", p.dim, "${d.doneToday} tasks closed", Routes.TASKS, true)
    if (d.sets > 0) Proc("body", "DONE", p.dim, "gym ${d.sets} sets", Routes.GYM, true)
    x.first?.let { f -> Row(Modifier.padding(top = 4.dp)) { M("running: ", 11, p.dim); Act("done", p.g) { x.done(f) }; Act("snooze 1h") { x.later(f) }; Act("open") { x.open(Routes.TASKS) } } }
    HubStrip(x.open); CmdBar { x.open(Routes.ASK) }; Suggest(x); Notice(x)
}
