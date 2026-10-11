package com.nizam.app.feature.nx

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarViewDay
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import androidx.core.content.ContextCompat
import com.nizam.app.AppContainer
import com.nizam.app.data.db.Task
import com.nizam.app.feature.prayer.PrayerTimes
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * The planning engine: lays today's tasks into the real gaps between your calendar events and the
 * five prayers, reflows missed tasks across the coming days, tracks what blocks what, and warns when
 * a project is drifting.
 */
object PlanEngine {
    private val zone: ZoneId get() = ZoneId.systemDefault()
    private fun p(c: Context) = c.getSharedPreferences("plan_engine", Context.MODE_PRIVATE)

    data class Block(val startMin: Int, val endMin: Int, val label: String, val kind: String, val taskId: Long? = null)

    fun capacity(c: Context) = p(c).getInt("cap", 300)
    fun setCapacity(c: Context, m: Int) = p(c).edit().putInt("cap", m).apply()
    fun dayStart(c: Context) = p(c).getInt("start", 9 * 60)
    fun dayEnd(c: Context) = p(c).getInt("end", 22 * 60)

    /** Today's calendar events as busy blocks (needs calendar permission). */
    fun events(c: Context, date: LocalDate = LocalDate.now()): List<Block> {
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli(); val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { android.content.ContentUris.appendId(it, start); android.content.ContentUris.appendId(it, end) }.build()
        return runCatching {
            c.contentResolver.query(uri, arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.TITLE, CalendarContract.Instances.ALL_DAY), null, null,
                CalendarContract.Instances.BEGIN)?.use { cur ->
                buildList {
                    while (cur.moveToNext()) {
                        if (cur.getInt(3) == 1) continue
                        val b = Instant.ofEpochMilli(cur.getLong(0)).atZone(zone).toLocalTime(); val e = Instant.ofEpochMilli(cur.getLong(1)).atZone(zone).toLocalTime()
                        add(Block(b.toSecondOfDay() / 60, maxOf(b.toSecondOfDay() / 60 + 15, e.toSecondOfDay() / 60), cur.getString(2) ?: "Event", "event"))
                    }
                }
            }
        }.getOrNull().orEmpty()
    }

    /** The five prayers as protected 20-minute blocks. */
    suspend fun prayers(container: AppContainer, date: LocalDate = LocalDate.now()): List<Block> = runCatching {
        val s = container.settingsRepository
        val t = PrayerTimes.calculate(date = date, latitude = s.latitude.first(), longitude = s.longitude.first(),
            method = runCatching { PrayerTimes.Method.valueOf(s.prayerMethod.first()) }.getOrElse { PrayerTimes.Method.KARACHI }, hanafiAsr = s.hanafiAsr.first())
        listOf("Fajr" to t.fajr, "Zuhr" to t.dhuhr, "Asr" to t.asr, "Maghrib" to t.maghrib, "Isha" to t.isha)
            .filter { !it.second.isNaN() }.map { (n, h) -> val m = (h * 60).toInt(); Block(m, m + 20, n, "prayer") }
    }.getOrDefault(emptyList())

    /** Greedy schedule: highest priority and earliest due first, into free gaps, within your daily focus capacity. */
    suspend fun planDay(container: AppContainer, tasks: List<Task>): List<Block> {
        val c = container.appContext
        val busy = (events(c) + prayers(container)).sortedBy { it.startMin }
        val nowMin = LocalTime.now().let { it.hour * 60 + it.minute }
        var cursor = maxOf(dayStart(c), nowMin + 5)
        val end = dayEnd(c)
        var budget = capacity(c)
        val blocked = blockedIds(c, tasks)
        val queue = tasks.filter { it.completedAt == null && it.deletedAt == null && it.id !in blocked }
            .sortedWith(compareByDescending<Task> { it.priority }.thenBy { it.due ?: Long.MAX_VALUE }).toMutableList()
        val out = mutableListOf<Block>()
        while (queue.isNotEmpty() && cursor < end && budget > 0) {
            val t = queue.first()
            val len = (t.estimateMin ?: 30).coerceIn(10, 180)
            val clash = busy.firstOrNull { it.endMin > cursor && it.startMin < cursor + len }
            if (clash != null) { cursor = clash.endMin + 5; continue }
            out += Block(cursor, cursor + len, t.title, "task", t.id)
            cursor += len + 10; budget -= len; queue.removeAt(0)
        }
        return (out + busy.filter { it.endMin > nowMin }).sortedBy { it.startMin }
    }

    suspend fun applyPlan(container: AppContainer, plan: List<Block>, tasks: List<Task>) {
        val today = LocalDate.now()
        plan.filter { it.kind == "task" }.forEach { b ->
            tasks.firstOrNull { it.id == b.taskId }?.let { t ->
                container.taskRepository.save(t.copy(due = today.atStartOfDay(zone).plusMinutes(b.startMin.toLong()).toInstant().toEpochMilli(), updatedAt = System.currentTimeMillis()))
            }
        }
    }

    /** Overdue open tasks, spread across the next days by priority within the daily capacity. */
    suspend fun reflow(container: AppContainer, tasks: List<Task>): Int {
        val c = container.appContext
        val startOfToday = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val late = tasks.filter { it.completedAt == null && it.deletedAt == null && (it.due ?: Long.MAX_VALUE) < startOfToday }
            .sortedByDescending { it.priority }
        var day = LocalDate.now(); var used = 0
        late.forEach { t ->
            val len = t.estimateMin ?: 30
            if (used + len > capacity(c)) { day = day.plusDays(1); used = 0 }
            used += len
            container.taskRepository.save(t.copy(due = day.atTime(LocalTime.of(10, 0)).atZone(zone).toInstant().toEpochMilli(), updatedAt = System.currentTimeMillis()))
        }
        p(c).edit().putLong("last_reflow", System.currentTimeMillis()).apply()
        return late.size
    }

    // ---------------------------------------------------------------- dependencies

    private fun deps(c: Context): JSONObject = runCatching { JSONObject(p(c).getString("deps", "{}")!!) }.getOrElse { JSONObject() }
    fun blockers(c: Context, id: Long): Set<Long> = deps(c).optJSONArray(id.toString())?.let { a -> (0 until a.length()).map { a.getLong(it) }.toSet() } ?: emptySet()
    fun setBlocker(c: Context, id: Long, blocker: Long, on: Boolean) {
        val d = deps(c); val cur = blockers(c, id).toMutableSet()
        if (on && blocker != id) cur += blocker else cur -= blocker
        d.put(id.toString(), org.json.JSONArray(cur.toList())); p(c).edit().putString("deps", d.toString()).apply()
    }
    /** Open tasks still waiting on another open task. */
    fun blockedIds(c: Context, tasks: List<Task>): Set<Long> {
        val open = tasks.filter { it.completedAt == null && it.deletedAt == null }.map { it.id }.toSet()
        return open.filter { id -> blockers(c, id).any { it in open } }.toSet()
    }

    // ---------------------------------------------------------------- project risk

    data class Risk(val name: String, val open: Int, val overdue: Int, val minutesLeft: Int, val level: String, val why: String)

    fun risks(tasks: List<Task>, projectNames: Map<Long, String>): List<Risk> {
        val now = System.currentTimeMillis()
        return tasks.filter { it.deletedAt == null }.groupBy { it.projectId }.mapNotNull { (pid, list) ->
            val open = list.filter { it.completedAt == null }
            if (open.isEmpty()) return@mapNotNull null
            val overdue = open.count { (it.due ?: Long.MAX_VALUE) < now }
            val mins = open.sumOf { it.estimateMin ?: 30 }
            val latest = open.mapNotNull { it.due }.maxOrNull()
            val daysLeft = latest?.let { ((it - now) / 86_400_000L).toInt() }
            val load = if (daysLeft != null && daysLeft > 0) mins / daysLeft else mins
            val done = list.count { it.completedAt != null }
            val level = when { overdue >= 3 || (daysLeft != null && daysLeft <= 1 && mins > 180) -> "High"; overdue > 0 || load > 180 -> "Medium"; else -> "Low" }
            val why = buildList {
                if (overdue > 0) add("$overdue overdue")
                if (daysLeft != null) add("${mins / 60}h left over ${daysLeft.coerceAtLeast(0)} days")
                add("$done of ${list.size} done")
            }.joinToString(" · ")
            Risk(pid?.let { projectNames[it] } ?: "No project", open.size, overdue, mins, level, why)
        }.sortedBy { when (it.level) { "High" -> 0; "Medium" -> 1; else -> 2 } }
    }
}

object PlanEngineSpecs {
    val all: List<Spec> = listOf(
        ScreenSpec("planengine", "Plan engine", "Today laid around your calendar and salah; missed tasks reflowed; blockers and project risk",
            Icons.Outlined.CalendarViewDay, Hub.Work, "plan day schedule calendar reflow overdue dependencies blocked project risk timebox") { c, open -> PlanEngineScreen(c) }
    )
}

@Composable
private fun PlanEngineScreen(container: AppContainer) {
    val c = LocalContext.current
    val work = com.nizam.app.ui.design.rememberWorkScope()
    val tasks by container.taskRepository.all().collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf("Today") }
    var plan by remember { mutableStateOf<List<PlanEngine.Block>>(emptyList()) }
    var cap by remember { mutableIntStateOf(PlanEngine.capacity(c)) }
    var tick by remember { mutableIntStateOf(0) }
    var pickFor by remember { mutableStateOf<Long?>(null) }
    val calPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val hasCal = ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Today", "Reflow", "Blockers", "Risk").forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t) }) }
        }
        when (tab) {
            "Today" -> {
                if (!hasCal) OutlinedButton(onClick = { calPerm.launch(Manifest.permission.READ_CALENDAR) }) { Text("Let me see your calendar (optional)") }
                Text("Focus capacity: ${cap / 60}h ${cap % 60}m", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(120, 240, 300, 420).forEach { m -> FilterChip(selected = cap == m, onClick = { cap = m; PlanEngine.setCapacity(c, m) }, label = { Text("${m / 60}h") }) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { work.launch { plan = PlanEngine.planDay(container, tasks) } }) { Text("Plan my day") }
                    if (plan.any { it.kind == "task" }) OutlinedButton(onClick = { work.launch { PlanEngine.applyPlan(container, plan, tasks); com.nizam.app.ui.components.Feedback.show("Times set on your tasks") } }) { Text("Use this plan") }
                }
                plan.forEach { b ->
                    val color = when (b.kind) { "prayer" -> Hub.Deen.spine; "event" -> Hub.Work.spine; else -> MaterialTheme.colorScheme.primary }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("%02d:%02d".format(b.startMin / 60, b.startMin % 60), Modifier.width(54.dp), style = MaterialTheme.typography.labelMedium)
                        Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(color.copy(alpha = 0.16f)).padding(10.dp)) {
                            Text("${if (b.kind == "prayer") "🕌 " else if (b.kind == "event") "📅 " else ""}${b.label} · ${b.endMin - b.startMin} min", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                if (plan.isEmpty()) Text("Tasks are placed by priority into the gaps between your events and the five prayers, up to your focus capacity.", style = MaterialTheme.typography.bodySmall)
            }
            "Reflow" -> {
                val startOfToday = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val late = tasks.filter { it.completedAt == null && it.deletedAt == null && (it.due ?: Long.MAX_VALUE) < startOfToday }
                Text("${late.size} missed task${if (late.size == 1) "" else "s"}", style = MaterialTheme.typography.titleMedium)
                late.take(12).forEach { Text("• ${it.title}", style = MaterialTheme.typography.bodyMedium) }
                if (late.isNotEmpty()) Button(onClick = { work.launch { val n = PlanEngine.reflow(container, tasks); com.nizam.app.ui.components.Feedback.show("Moved $n tasks across the next days") } }) {
                    Text("Reflow across the coming days")
                } else Text("Nothing missed. 👌", style = MaterialTheme.typography.bodyMedium)
            }
            "Blockers" -> {
                val open = tasks.filter { it.completedAt == null && it.deletedAt == null }
                val blocked = remember(tasks, tick) { PlanEngine.blockedIds(c, tasks) }
                Text("Tap a task, then choose what it's waiting on. Blocked tasks stay out of today's plan.", style = MaterialTheme.typography.bodySmall)
                open.take(40).forEach { t ->
                    Column(Modifier.fillMaxWidth().clickable { pickFor = if (pickFor == t.id) null else t.id }.padding(vertical = 4.dp)) {
                        Text("${if (t.id in blocked) "⛔ " else ""}${t.title}", style = MaterialTheme.typography.bodyLarge, fontWeight = if (pickFor == t.id) FontWeight.SemiBold else FontWeight.Normal)
                        val bs = remember(t.id, tick) { PlanEngine.blockers(c, t.id) }
                        if (bs.isNotEmpty()) Text("Waiting on: " + open.filter { it.id in bs }.joinToString { it.title }, style = MaterialTheme.typography.labelSmall)
                        if (pickFor == t.id) open.filter { it.id != t.id }.take(15).forEach { o ->
                            TextButton(onClick = { PlanEngine.setBlocker(c, t.id, o.id, o.id !in bs); tick++ }) { Text((if (o.id in bs) "✓ " else "+ ") + o.title) }
                        }
                    }
                }
            }
            else -> {
                val risks = remember(tasks) { PlanEngine.risks(tasks, emptyMap()) }
                if (risks.isEmpty()) Text("No open work to assess.")
                risks.forEach { r ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(when (r.level) { "High" -> "🔴"; "Medium" -> "🟠"; else -> "🟢" }, Modifier.padding(end = 8.dp))
                        Column(Modifier.weight(1f)) {
                            Text("${r.name} · ${r.open} open", style = MaterialTheme.typography.bodyLarge)
                            Text(r.why, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}
