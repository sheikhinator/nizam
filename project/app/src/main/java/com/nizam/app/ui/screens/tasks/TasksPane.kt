package com.nizam.app.ui.screens.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.data.db.Task
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun TaskChip(text: String, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(text, Modifier.clip50().background(if (on) cs.primary.copy(alpha = 0.16f) else cs.surfaceVariant.copy(alpha = 0.6f))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge, color = if (on) cs.primary else cs.onSurfaceVariant)
}

private fun Modifier.clip50() = this.clip(RoundedCornerShape(50))

private fun prioColor(p: Int, base: Color): Color = when (p) { 2 -> Color(0xFFE5484D); 0 -> base.copy(alpha = 0.35f); else -> base }

/** The task list, overhauled: smart views, repeats, estimates, steps, a planner and a brain dump. */
@Composable
fun TasksSmartPane(container: AppContainer, vm: TasksViewModel, tasks: List<Task>) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val today = LocalDate.now()
    val counts = remember(tasks) { TaskSmart.View.values().associateWith { TaskSmart.filter(tasks, it, today).size } }
    var view by remember { mutableStateOf(if ((counts[TaskSmart.View.TODAY] ?: 0) > 0) TaskSmart.View.TODAY else TaskSmart.View.ALL) }
    val shown = remember(tasks, view) { TaskSmart.filter(tasks, view, today) }
    var open by remember { mutableStateOf<Task?>(null) }
    var plan by remember { mutableStateOf<String?>(null) }
    var planning by remember { mutableStateOf(false) }
    var dump by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val found = remember(tasks, query) { if (query.isBlank()) emptyList() else tasks.filter { it.title.contains(query.trim(), true) || it.notes.contains(query.trim(), true) } }

    OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(bottom = 6.dp), singleLine = true, placeholder = { Text("Search tasks") })
    if (query.isNotBlank()) {
        if (found.isEmpty()) Text("No matching tasks", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        found.take(30).forEach { t -> TaskRow(t, today, { vm.toggleTask(t) }, { open = t }) }
        open?.let { t -> TaskSheet(container, vm, tasks.firstOrNull { it.id == t.id } ?: t) { open = null } }
        return
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TaskSmart.View.values().forEach { v ->
            val n = counts[v] ?: 0
            if (v == TaskSmart.View.OVERDUE && n == 0) return@forEach
            TaskChip(v.label + if (n > 0 && v != TaskSmart.View.DONE) " $n" else "", view == v) { view = v }
        }
    }
    Spacer(Modifier.size(8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TaskChip(if (planning) "Planning…" else "✦ Plan my day", plan != null) {
            if (plan != null) { plan = null; return@TaskChip }
            if (planning) return@TaskChip
            planning = true
            scope.launch {
                runCatching { planDay(container, tasks) }.onSuccess { plan = it }.onFailure { Feedback.show(it.message ?: "Couldn't plan right now") }
                planning = false
            }
        }
        TaskChip("🧠 Brain dump", false) { dump = true }
        val overdue = counts[TaskSmart.View.OVERDUE] ?: 0
        if (overdue > 0) TaskChip("Move $overdue overdue to today", false) {
            TaskSmart.filter(tasks, TaskSmart.View.OVERDUE, today).forEach { vm.save(TaskSmart.withDate(it, today)) }
            Feedback.show("Moved to today")
        }
    }
    plan?.let { p ->
        com.nizam.app.ui.design.Group(Modifier.padding(top = 8.dp)) {
            Text("Your day, planned", fontWeight = FontWeight.SemiBold)
            com.nizam.app.feature.nx.Md(p)
        }
    }
    Spacer(Modifier.size(8.dp))
    if (shown.isEmpty()) {
        com.nizam.app.ui.components.EmptyState(when (view) {
            TaskSmart.View.TODAY -> "Nothing due today. Add one above, or pull something from Upcoming."
            TaskSmart.View.DONE -> "Nothing finished yet."
            else -> "Nothing here."
        })
    } else com.nizam.app.ui.design.Group(padding = androidx.compose.foundation.layout.PaddingValues(start = 4.dp, end = 8.dp)) {
        shown.forEachIndexed { i, task ->
            TaskRow(task, today, { vm.toggleTask(task) }, { open = task })
            if (i < shown.lastIndex) HorizontalDivider(Modifier.padding(start = 48.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    open?.let { t -> TaskSheet(container, vm, tasks.firstOrNull { it.id == t.id } ?: t) { open = null } }
    if (dump) BrainDump(container) { dump = false }
}

@Composable
internal fun TaskRow(task: Task, today: LocalDate, toggle: () -> Unit, open: () -> Unit) {
    val done = task.completedAt != null
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clickable(onClick = open), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = done, onCheckedChange = { toggle() })
        Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
            Text(task.title, style = MaterialTheme.typography.bodyLarge,
                textDecoration = if (done) TextDecoration.LineThrough else null,
                color = if (done) cs.onSurfaceVariant else cs.onSurface)
            val d = TaskSmart.date(task)
            val steps = TaskSmart.steps(task.notes)
            val bits = listOfNotNull(
                d?.takeIf { !done }?.let { (if (it.isBefore(today)) "Overdue · " else "") + TaskSmart.dayLabel(it, today) + (TaskSmart.timeOf(task)?.let { t -> " " + TaskSmart.clock(t) } ?: "") },
                task.recurrenceRule?.let { "↻ " + TaskSmart.ruleLabel(it) },
                task.estimateMin?.let { "~" + TaskSmart.mins(it) },
                steps.takeIf { it.isNotEmpty() }?.let { s -> "${s.count { it.first }}/${s.size} steps" })
            if (bits.isNotEmpty()) Text(bits.joinToString("  ·  "), style = MaterialTheme.typography.labelMedium,
                color = if (d != null && d.isBefore(today) && !done) cs.error else cs.onSurfaceVariant)
        }
        if (!done && task.priority != 1) Box(Modifier.size(8.dp).background(prioColor(task.priority, cs.primary), CircleShape))
        Spacer(Modifier.width(8.dp))
    }
}

/** Everything about one task: edit, date, priority, repeat, estimate, steps, break it down, delete. */
@Composable
internal fun TaskSheet(container: AppContainer, vm: TasksViewModel, task: Task, close: () -> Unit) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var title by remember(task.id) { mutableStateOf(task.title) }
    var body by remember(task.id) { mutableStateOf(TaskSmart.notesBody(task.notes)) }
    val steps = remember(task.id) { mutableStateListOf<Pair<Boolean, String>>().apply { addAll(TaskSmart.steps(task.notes)) } }
    var date by remember(task.id) { mutableStateOf(TaskSmart.date(task)) }
    var prio by remember(task.id) { mutableStateOf(task.priority) }
    var rule by remember(task.id) { mutableStateOf(task.recurrenceRule) }
    var est by remember(task.id) { mutableStateOf(task.estimateMin) }
    var time by remember(task.id) { mutableStateOf(TaskSmart.timeOf(task)) }
    var newStep by remember(task.id) { mutableStateOf("") }
    var breaking by remember(task.id) { mutableStateOf(false) }
    val today = LocalDate.now()
    fun build(): Task = TaskSmart.withDateTime(task, date, time).copy(title = title.trim().ifBlank { task.title }, notes = TaskSmart.compose(body, steps.toList()),
        priority = prio, recurrenceRule = rule, estimateMin = est)
    AlertDialog(onDismissRequest = { vm.save(build()); close() },
        title = { Text("Task") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") })
                Label("When")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Today" to today, "Tomorrow" to today.plusDays(1), "Next week" to today.plusWeeks(1).with(java.time.DayOfWeek.MONDAY), "No date" to null)
                        .forEach { (l, d) -> TaskChip(l, date == d) { date = d } }
                }
                date?.let { Text(TaskSmart.dayLabel(it, today) + (time?.let { t -> " at " + TaskSmart.clock(t) } ?: ""), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (date != null) {
                    Label("Remind me at")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(null, java.time.LocalTime.of(8, 0), java.time.LocalTime.of(10, 0), java.time.LocalTime.of(13, 0), java.time.LocalTime.of(16, 0),
                            java.time.LocalTime.of(18, 0), java.time.LocalTime.of(20, 0), java.time.LocalTime.of(22, 0))
                            .forEach { t -> TaskChip(t?.let { TaskSmart.clock(it) } ?: "No time", time == t) { time = t } }
                    }
                    var custom by remember(task.id) { mutableStateOf("") }
                    OutlinedTextField(custom, { v -> custom = v; Regex("^(\\d{1,2}):(\\d{2})$").find(v.trim())?.let { m ->
                        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt(); if (h in 0..23 && mi in 0..59) time = java.time.LocalTime.of(h, mi) } },
                        Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Or type a time, e.g. 17:45") })
                }
                Label("Postpone")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TaskSmart.POSTPONE.forEach { (l, f) -> TaskChip(l, false) {
                        val at = f(java.time.LocalDateTime.now())
                        vm.save(TaskSmart.withDateTime(build(), at.toLocalDate(), at.toLocalTime().withSecond(0).withNano(0)))
                        Feedback.show("Postponed to ${TaskSmart.dayLabel(at.toLocalDate())} ${TaskSmart.clock(at.toLocalTime())}"); close()
                    } }
                }
                Label("Priority")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(2 to "High", 1 to "Normal", 0 to "Low").forEach { (p, l) -> TaskChip(l, prio == p) { prio = p } } }
                Label("Repeat")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null, "daily", "weekdays", "weekly", "monthly", "yearly").forEach { r -> TaskChip(r?.let { TaskSmart.ruleLabel(it) } ?: "Never", rule == r) { rule = r } }
                }
                Label("How long")
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(null, 5, 15, 30, 60, 120).forEach { m -> TaskChip(m?.let { TaskSmart.mins(it) } ?: "—", est == m) { est = m } }
                }
                Label("Steps")
                steps.forEachIndexed { i, (d, s) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(d, { steps[i] = it to s })
                        Text(s, Modifier.weight(1f), textDecoration = if (d) TextDecoration.LineThrough else null)
                        Text("✕", Modifier.clickable { steps.removeAt(i) }.padding(6.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newStep, { newStep = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Add a step") })
                    TextButton(onClick = { if (newStep.isNotBlank()) { steps += false to newStep.trim(); newStep = "" } }) { Text("Add") }
                }
                TaskChip(if (breaking) "Breaking it down…" else "✦ Break it down for me", false) {
                    if (breaking) return@TaskChip
                    breaking = true
                    scope.launch {
                        runCatching {
                            val r = Ai.generate(container.settingsRepository, "Break this task into 3 to 7 small, concrete steps, each doable in one sitting. " +
                                "Output only the steps, one per line, no numbering.\nTask: $title" + (if (body.isNotBlank()) "\nNotes: $body" else ""),
                                system = "You are a practical planner. Output only the steps.", maxOutputTokens = 400)
                            r.lines().map { it.trim().trimStart('-', '*', '•', ' ').replace(Regex("^\\d+[.)]\\s*"), "") }.filter { it.length in 2..140 }.take(8)
                        }.onSuccess { l -> steps.addAll(l.map { false to it }) }.onFailure { Feedback.show(it.message ?: "Couldn't do that") }
                        breaking = false
                    }
                }
                OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), label = { Text("Notes") }, minLines = 2)
            }
        },
        confirmButton = { TextButton(onClick = { vm.save(build()); close() }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { vm.deleteTask(task); close() }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = { vm.save(TaskSmart.withDate(build(), (date ?: today).plusDays(1))); Feedback.show("Moved to the next day"); close() }) { Text("+1 day") }
            }
        })
}

@Composable
private fun Label(t: String) = Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

/** Pour out everything on your mind; Atlas turns it into tasks you tick to keep. */
@Composable
private fun BrainDump(container: AppContainer, close: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    AlertDialog(onDismissRequest = close, title = { Text("Brain dump") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Type or say everything on your mind, messy is fine. Atlas sorts it into tasks, reminders and notes.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(text, { text = it; ready = false }, Modifier.fillMaxWidth(), minLines = 4,
                    trailingIcon = { com.nizam.app.feature.nx.MicButton({ heard -> text = (text.trimEnd() + " " + heard).trim(); ready = false }) })
                if (text.isNotBlank()) {
                    if (!ready) TaskChip("Sort it", false) { ready = true }
                    else com.nizam.app.feature.nx.ActOnIt(container, "Brain dump", text)
                }
            }
        },
        confirmButton = { TextButton(onClick = close) { Text("Done") } })
}

private suspend fun planDay(container: AppContainer, tasks: List<Task>): String {
    val s = container.settingsRepository
    val today = LocalDate.now()
    val pt = runCatching {
        com.nizam.app.feature.prayer.PrayerTimes.calculate(today, s.latitude.first(), s.longitude.first(),
            runCatching { com.nizam.app.feature.prayer.PrayerTimes.Method.valueOf(s.prayerMethod.first()) }.getOrElse { com.nizam.app.feature.prayer.PrayerTimes.Method.KARACHI },
            s.hanafiAsr.first())
    }.getOrNull()
    val prayers = pt?.let { t -> listOf("Fajr" to t.fajr, "Dhuhr" to t.dhuhr, "Asr" to t.asr, "Maghrib" to t.maghrib, "Isha" to t.isha)
        .filter { !it.second.isNaN() }.joinToString(", ") { "${it.first} ${com.nizam.app.core.hourToClock(it.second)}" } }.orEmpty()
    val list = (TaskSmart.filter(tasks, TaskSmart.View.TODAY, today) + TaskSmart.filter(tasks, TaskSmart.View.SOMEDAY, today).take(5)).distinctBy { it.id }
    if (list.isEmpty()) return "Nothing on your list for today. Add a few tasks and I'll fit them around your day."
    val now = java.time.LocalTime.now().withSecond(0).withNano(0)
    val lines = list.joinToString("\n") { t ->
        "- ${t.title}" + listOfNotNull(when (t.priority) { 2 -> "high priority"; 0 -> "low priority"; else -> null }, t.estimateMin?.let { "${it} min" },
            TaskSmart.timeOf(t)?.let { "fixed at ${TaskSmart.clock(it)}" }, TaskSmart.date(t)?.takeIf { it.isBefore(today) }?.let { "overdue" }).joinToString(", ").let { if (it.isBlank()) "" else " ($it)" }
    }
    return Ai.generate(s, "It's now ${TaskSmart.clock(now)} on ${today.dayOfWeek.name.lowercase()}. Plan the rest of my day as a short timetable.\n" +
        (if (prayers.isNotBlank()) "Prayer times today (leave room for each): $prayers\n" else "") +
        "My tasks:\n$lines\n\nRules: start from now, high priority and overdue first, guess sensible durations when none given, add short breaks, " +
        "and put anything that won't fit under 'Tomorrow'. Format: one line per block like '3:00 pm – 3:30 pm · Task'. End with one line of encouragement.",
        system = "You are a calm, realistic day planner.", maxOutputTokens = 800)
}
