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
import androidx.compose.foundation.horizontalScroll
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
    val tctx = androidx.compose.ui.platform.LocalContext.current
    var simple by remember { mutableStateOf(TaskAlarms.simple(tctx)) }
    var autoPost by remember { mutableStateOf(TaskAlarms.autoPostpone(tctx)) }
    var checkIn by remember { mutableStateOf(TaskAlarms.checkIn(tctx)) }
    androidx.compose.runtime.LaunchedEffect(tasks) { com.nizam.app.ui.design.guard { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { TaskAlarms.sync(tctx, tasks) } } }
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
                label = { Text(if (tab == 0) "New task: try \"call Ali tomorrow 5pm !!\"" else "New habit") },
                trailingIcon = { com.nizam.app.feature.nx.MicButton({ heard -> draft = (draft.trimEnd() + " " + heard).trim() }) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (tab == 0) vm.addTask(draft, if (simple) SimpleDay.day else null) else vm.addHabit(draft)
                    draft = ""
                })
            )
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = {
                if (tab == 0) vm.addTask(draft, if (simple) SimpleDay.day else null) else vm.addHabit(draft)
                draft = ""
            }) {
                Icon(Icons.Filled.Add, contentDescription = if (tab == 0) "Add task" else "Add habit")
            }
        }
        if (tab == 0 && draft.isNotBlank()) {
            val parsed = remember(draft) { TaskParse.parse(draft) }
            if (parsed.chips().isNotEmpty()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                parsed.chips().forEach { c -> TaskChip(c, true) {} }
            }
        }

        Spacer(Modifier.height(10.dp))
        if (tab == 0) Row(Modifier.horizontalScroll(rememberScrollState()).padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TaskChip(if (simple) "✓ Simple mode" else "Simple mode", simple) { simple = !simple; TaskAlarms.setSimple(tctx, simple) }
            TaskChip(if (autoPost) "✓ Auto-postpone" else "Auto-postpone", autoPost) {
                autoPost = !autoPost; TaskAlarms.setAutoPostpone(tctx, autoPost)
                com.nizam.app.ui.components.Feedback.show(if (autoPost) "Unfinished tasks move to the next day on their own" else "Auto-postpone off") }
            TaskChip(if (checkIn < 0) "Daily check-in" else "Check-in ${if (checkIn > 12) checkIn - 12 else checkIn}${if (checkIn >= 12) "pm" else "am"}", checkIn >= 0) {
                checkIn = when (checkIn) { -1 -> 8; 8 -> 9; 9 -> 21; else -> -1 }; TaskAlarms.setCheckIn(tctx, checkIn) }
        }
        if (!simple && (tasks.isNotEmpty() || habits.isNotEmpty())) {
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
            if (simple) SimpleAgenda(container, vm, tasks) else TasksSmartPane(container, vm, tasks)
        } else {
            HabitsPane(container, vm, habits)
        }
        Spacer(Modifier.height(80.dp))
    }
}
