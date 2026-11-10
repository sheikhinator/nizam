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
    val beingLine: String get() = if (Being.born(c)) Being.name(c) + " · " + Being.activity(c).lowercase() else "not hatched yet · tap to hatch"
    fun s(key: String): List<Float> = snap?.series(key) ?: emptyList()
}

class Area(val key: String, val label: String, val value: String, val num: Float, val note: String, val route: String, val series: List<Float>, val color: Color, val frac: Float, val bars: Boolean = false)

internal fun Sci.areas(p: SciPal): List<Area> {
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
    val pal = LocalSci.current
    val snap by produceState(LifeGraph.cached, d.openCount, d.doneToday, d.spentToday, d.waterMl) { value = runCatching { LifeGraph.load(container, c, force = value != null) }.getOrNull() ?: value }
    val all by container.taskRepository.all().collectAsState(initial = emptyList())
    val now by produceState(LocalTime.now()) { while (true) { delay(1000); value = LocalTime.now() } }
    val x = Sci(d, snap, container, onOpen, all.filter { it.completedAt == null && it.deletedAt == null }.sortedBy { it.due ?: Long.MAX_VALUE }, now, scope, c)
    run {
        Box(Modifier.fillMaxSize().background(pal.bg)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) { LabToday(x); Spacer(Modifier.height(120.dp)) }
        }
    }
}

// ───────────────────────── shared blocks ─────────────────────────

@Composable
internal fun Head(x: Sci, label: String, title: String = x.d.greeting, serif: Boolean = false) {
    val p = LocalSci.current
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.Bottom) {
        Column(Modifier.weight(1f)) { Lab(label); M(title, 26, bold = !serif, mono = false, serif = serif, maxLines = 1) }
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
internal fun AreaChart(a: Area, modifier: Modifier) { if (a.series.size < 2) Meter(a.frac, a.color, modifier, 8.dp) else if (a.bars) BarsChart(a.series, a.color, modifier) else Spark(a.series, a.color, modifier, fill = a.key == "sleep") }

internal fun corrText(c: LifeGraph.Corr): String = "${LifeGraph.NAMES[c.a]} and ${LifeGraph.NAMES[c.b]} ${if (c.r > 0) "rise and fall together" else "move in opposite directions"}"

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
internal fun Tiles(x: Sci, fig: Boolean = false) {
    val p = LocalSci.current
    x.areas(p).chunked(3).forEachIndexed { r, row ->
        Row(Modifier.fillMaxWidth()) {
            row.forEachIndexed { i, a -> Cell(onClick = { x.open(a.route) }) { Lab(if (fig) "Fig ${r * 3 + i + 1} · ${a.label}" else a.label); M(a.value, 16, bold = true, mono = false, maxLines = 1); AreaChart(a, Modifier.fillMaxWidth().height(22.dp).padding(top = 4.dp)) } }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

