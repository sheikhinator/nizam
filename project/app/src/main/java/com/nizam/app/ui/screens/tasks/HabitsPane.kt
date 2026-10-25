package com.nizam.app.ui.screens.tasks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.data.db.Habit
import com.nizam.app.data.db.HabitEntry
import com.nizam.app.ui.components.Feedback
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Habits, overhauled: each one shows its streak, the last seven days (tap any day to fix a missed tick),
 * progress against its weekly target, and its 30-day rate. Tap a habit to change its target or retire it.
 */
@Composable
fun HabitsPane(container: AppContainer, vm: TasksViewModel, habits: List<Habit>) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val today = LocalDate.now()
    val flow = remember { container.habitRepository.entriesSince(today.minusDays(60).toString()) }
    val entries by flow.collectAsState(initial = emptyList<HabitEntry>())
    val done = remember(entries) { entries.groupBy { it.habitId }.mapValues { e -> e.value.map { it.localDate }.toSet() } }
    var editing by remember { mutableStateOf<Habit?>(null) }

    if (habits.isEmpty()) {
        com.nizam.app.ui.components.EmptyState("No habits yet. Add one above, or start with one of these:")
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Read 10 pages", "Walk 30 min", "No sugar", "Quran daily", "Sleep by 11", "Drink 8 glasses", "Gym", "Journal").forEach { h ->
                TaskChip("+ $h", false) { vm.addHabit(h) }
            }
        }
        return
    }
    val allToday = habits.count { (done[it.id] ?: emptySet()).contains(today.toString()) }
    Text("$allToday of ${habits.size} done today", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 6.dp))
    com.nizam.app.ui.design.Group(padding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        habits.forEachIndexed { i, h ->
            val days = done[h.id] ?: emptySet()
            val streak = remember(days) {
                var n = 0; var d = if (today.toString() in days) today else today.minusDays(1)
                while (d.toString() in days && n < 1000) { n++; d = d.minusDays(1) }
                n
            }
            val weekStart = today.minusDays((today.dayOfWeek.value - 1).toLong())
            val thisWeek = days.count { it >= weekStart.toString() }
            val rate30 = days.count { it > today.minusDays(30).toString() } * 100 / 30
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { editing = h }) {
                        Text(h.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text("${if (streak > 0) "🔥 $streak day${if (streak > 1) "s" else ""}" else "No streak yet"} · $thisWeek/${h.targetPerWeek} this week · $rate30% in 30 days",
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    (6 downTo 0).forEach { off ->
                        val d = today.minusDays(off.toLong())
                        val on = d.toString() in days
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(d.dayOfWeek.name.take(1), style = MaterialTheme.typography.labelSmall,
                                color = if (off == 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                            Box(Modifier.padding(top = 2.dp).size(30.dp)
                                .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, CircleShape)
                                .border(1.5.dp, if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                .clickable { scope.launch { runCatching { container.habitRepository.toggle(h.id, d.toString(), on) } } },
                                contentAlignment = Alignment.Center) {
                                Text("${d.dayOfMonth}", style = MaterialTheme.typography.labelSmall,
                                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            if (i < habits.lastIndex) HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    editing?.let { h ->
        var name by remember(h.id) { mutableStateOf(h.name) }
        var target by remember(h.id) { mutableStateOf(h.targetPerWeek) }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Habit") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name") })
                    Text("Times per week", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { (1..7).forEach { n -> TaskChip("$n", target == n) { target = n } } }
                }
            },
            confirmButton = { TextButton(onClick = {
                scope.launch { runCatching { container.habitRepository.update(h.copy(name = name.trim().ifBlank { h.name }, targetPerWeek = target)) } }
                editing = null
            }) { Text("Save") } },
            dismissButton = { TextButton(onClick = {
                scope.launch { runCatching { container.habitRepository.archive(h) } }
                Feedback.show("Habit retired. Its history is kept"); editing = null
            }) { Text("Retire", color = MaterialTheme.colorScheme.error) } })
    }
}
