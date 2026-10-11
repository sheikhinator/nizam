package com.nizam.app.ui.screens.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nizam.app.AppContainer
import com.nizam.app.core.Heatmap
import com.nizam.app.core.StatRow
import com.nizam.app.ui.components.EmptyState
import com.nizam.app.ui.components.StreakDot
import com.nizam.app.ui.theme.Spine

@Composable
fun TasksScreen(container: AppContainer) {
    val vm: TasksViewModel = viewModel(
        factory = TasksViewModel.factory(container.taskRepository, container.habitRepository)
    )
    val tasks by vm.tasks.collectAsState()
    val habits by vm.habits.collectAsState()
    val entries by vm.todayEntries.collectAsState()

    val completionActivity = remember(tasks) {
        // one pass: bucket completions by local day (not UTC — a task done at 2am belongs to that morning)
        val zone = java.time.ZoneId.systemDefault()
        val start = java.time.LocalDate.now().minusDays(29)
        val counts = IntArray(30)
        tasks.forEach { t ->
            val at = t.completedAt ?: return@forEach
            val i = java.time.temporal.ChronoUnit.DAYS.between(start, java.time.Instant.ofEpochMilli(at).atZone(zone).toLocalDate()).toInt()
            if (i in 0..29) counts[i]++
        }
        counts.map { it.toDouble() }
    }
    val completedRecently = completionActivity.sum().toInt()

    var tab by remember { mutableStateOf(0) }
    var draft by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("Tasks", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))

        com.nizam.app.core.ChipRow(listOf("Tasks", "Habits"), if (tab == 0) "Tasks" else "Habits", { tab = if (it == "Tasks") 0 else 1 })

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text(if (tab == 0) "New task" else "New habit") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (tab == 0) vm.addTask(draft) else vm.addHabit(draft)
                    draft = ""
                })
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = {
                if (tab == 0) vm.addTask(draft) else vm.addHabit(draft)
                draft = ""
            }) {
                Icon(Icons.Filled.Add, contentDescription = if (tab == 0) "Add task" else "Add habit")
            }
        }

        Spacer(Modifier.height(10.dp))
        if (tasks.isNotEmpty() || habits.isNotEmpty()) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(14.dp)) {
                    StatRow(
                        listOf(
                            "open" to tasks.count { it.completedAt == null }.toString(),
                            "done 30d" to completedRecently.toString(),
                            "habits today" to "${entries.size}/${habits.size}"
                        )
                    )
                    Spacer(Modifier.height(6.dp))
                    Text("COMPLETIONS, LAST 30 DAYS", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Heatmap(values = completionActivity, accent = Spine.Tasks)
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (tab == 0) {
            if (tasks.isEmpty()) {
                EmptyState("No tasks yet — add your first above.")
            } else {
                // open first, soonest due first (undated after); finished at the bottom
                var showAllDone by remember { mutableStateOf(false) }
                val all = remember(tasks) { tasks.sortedWith(compareBy<com.nizam.app.data.db.Task>({ it.completedAt != null },
                    { it.due ?: Long.MAX_VALUE }, { -it.createdAt })) }
                // long histories stay smooth: open tasks plus the 25 most recent finished ones unless asked
                val doneCount = all.count { it.completedAt != null }
                val sorted = if (showAllDone || doneCount <= 25) all
                    else all.filter { it.completedAt == null } + all.filter { it.completedAt != null }.sortedByDescending { it.completedAt }.take(25)
                val zone = java.time.ZoneId.systemDefault()
                com.nizam.app.ui.design.Group(padding = androidx.compose.foundation.layout.PaddingValues(start = 4.dp, end = 4.dp)) {
                    sorted.forEachIndexed { i, task ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = task.completedAt != null,
                                onCheckedChange = { vm.toggleTask(task) }
                            )
                            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                                Text(
                                    text = task.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    textDecoration = if (task.completedAt != null) TextDecoration.LineThrough else null,
                                    color = if (task.completedAt != null)
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    else MaterialTheme.colorScheme.onSurface
                                )
                                task.due?.takeIf { task.completedAt == null }?.let { due ->
                                    val d = java.time.Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
                                    val today = java.time.LocalDate.now()
                                    val overdue = d.isBefore(today)
                                    Text(when {
                                        d == today -> "Today"
                                        d == today.plusDays(1) -> "Tomorrow"
                                        overdue -> "Overdue · " + d.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
                                        else -> d.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))
                                    }, style = MaterialTheme.typography.labelMedium,
                                        color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = { vm.deleteTask(task) }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete task",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                            }
                        }
                        if (i < sorted.lastIndex) Divider(Modifier.padding(start = 48.dp), thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                if (doneCount > 25) androidx.compose.material3.TextButton(onClick = { showAllDone = !showAllDone }) {
                    Text(if (showAllDone) "Hide older finished tasks" else "Show ${doneCount - 25} older finished tasks")
                }
            }
        } else {
            if (habits.isEmpty()) {
                EmptyState("No habits yet — add one above and tap the dot each day you do it.")
            } else {
                Column(Modifier.fillMaxWidth()) {
                    habits.forEach { habit ->
                        val done = entries.any { it.habitId == habit.id }
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    habit.name,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = if (done) FontWeight.SemiBold else FontWeight.Normal
                                )
                                Text(
                                    if (done) "Done today" else "Not yet today",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { vm.toggleHabit(habit.id, done) }) {
                                StreakDot(filled = done, color = Spine.Tasks)
                            }
                        }
                        Divider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(80.dp))
    }
}
