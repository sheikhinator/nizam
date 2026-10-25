package com.nizam.app.ui.screens.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.data.db.Task
import java.time.LocalDate

/** Simple mode: one calm agenda. A week strip, the chosen day's tasks, done ones tucked away. */
object SimpleDay { var day by mutableStateOf(LocalDate.now()) }

@Composable
fun SimpleAgenda(container: AppContainer, vm: TasksViewModel, tasks: List<Task>) {
    val today = LocalDate.now()
    var day by remember { mutableStateOf(SimpleDay.day) }
    var weekStart by remember { mutableStateOf(day.minusDays((day.dayOfWeek.value - 1).toLong())) }
    var showDone by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Task?>(null) }
    val cs = MaterialTheme.colorScheme
    val byDay = remember(tasks) { tasks.filter { it.completedAt == null }.groupBy { TaskSmart.date(it) } }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(day.format(java.time.format.DateTimeFormatter.ofPattern("MMMM yyyy")), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        TextButton(onClick = { weekStart = weekStart.minusWeeks(1) }) { Text("‹") }
        TextButton(onClick = { day = today; SimpleDay.day = today; weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong()) }) { Text("Today") }
        TextButton(onClick = { weekStart = weekStart.plusWeeks(1) }) { Text("›") }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        (0..6).forEach { i ->
            val d = weekStart.plusDays(i.toLong())
            val sel = d == day
            val has = (byDay[d]?.isNotEmpty() == true) || (d == today && byDay.keys.any { it != null && it.isBefore(today) })
            Column(Modifier.clip(RoundedCornerShape(14.dp)).background(if (sel) cs.primary else cs.surface.copy(alpha = 0f))
                .clickable { day = d; SimpleDay.day = d }.padding(horizontal = 8.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(d.dayOfWeek.name.take(1), style = MaterialTheme.typography.labelSmall, color = if (sel) cs.onPrimary else cs.onSurfaceVariant)
                Text("${d.dayOfMonth}", style = MaterialTheme.typography.titleMedium, fontWeight = if (d == today) FontWeight.Bold else FontWeight.Normal,
                    color = if (sel) cs.onPrimary else if (d == today) cs.primary else cs.onSurface)
                Box(Modifier.size(5.dp).background(if (has) (if (sel) cs.onPrimary else cs.primary) else cs.surface.copy(alpha = 0f), CircleShape))
            }
        }
    }
    val overdue = if (day == today) tasks.filter { it.completedAt == null && TaskSmart.date(it)?.isBefore(today) == true } else emptyList()
    val list = (overdue + tasks.filter { it.completedAt == null && TaskSmart.date(it) == day }).sortedWith(compareBy<Task> { it.due }.thenByDescending { it.priority })
    val done = tasks.filter { t -> t.completedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate() } == day }
    val someday = if (day == today) tasks.filter { it.completedAt == null && it.due == null } else emptyList()
    if (list.isEmpty() && someday.isEmpty()) Text(if (done.isNotEmpty()) "All caught up! ✨" else "No tasks for this day yet. Add one above.",
        style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(vertical = 18.dp))
    list.forEachIndexed { i, t ->
        TaskRow(t, today, { vm.toggleTask(t) }, { open = t })
        if (i < list.lastIndex) HorizontalDivider(Modifier.padding(start = 48.dp), thickness = 0.5.dp, color = cs.outlineVariant)
    }
    if (someday.isNotEmpty()) {
        Text("Anytime", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        someday.forEach { t -> TaskRow(t, today, { vm.toggleTask(t) }, { open = t }) }
    }
    if (done.isNotEmpty()) {
        TextButton(onClick = { showDone = !showDone }) { Text(if (showDone) "Hide completed (${done.size})" else "Show completed (${done.size})") }
        if (showDone) done.forEach { t -> TaskRow(t, today, { vm.toggleTask(t) }, { open = t }) }
    }
    open?.let { t -> TaskSheet(container, vm, tasks.firstOrNull { it.id == t.id } ?: t) { open = null } }
}
