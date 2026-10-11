package com.nizam.app.feature.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Art3D
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.OutlinedButton
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DAYS = listOf("M", "T", "W", "T", "F", "S", "S")
private fun whenText(ms: Long?): String {
    ms ?: return "paused"
    val d = ms - System.currentTimeMillis()
    val t = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault())
    return when { d < 60_000 -> "due now"; d < 3_600_000 -> "in ${d / 60_000} min"; d < 86_400_000 -> "today ${t.format(DateTimeFormatter.ofPattern("h:mm a"))}"
        else -> t.format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")) }
}
private fun cadenceText(j: AgentJob) = when (j.cadence) {
    "hourly" -> "every hour at :%02d".format(j.minute); "every_n" -> "every ${j.everyHours} hours"
    "weekdays" -> "weekdays at %d:%02d".format(j.hour, j.minute); "weekly" -> j.days.sorted().joinToString("") { DAYS[it - 1] } + " at %d:%02d".format(j.hour, j.minute)
    "monthly" -> "day ${j.dayOfMonth} monthly at %d:%02d".format(j.hour, j.minute); "once" -> "once at %d:%02d".format(j.hour, j.minute)
    else -> "daily at %d:%02d".format(j.hour, j.minute)
}

/** Routines: what Atlas does on its own — with next run, history, pause, test and approval boundaries. */
@Composable
fun SchedulesScreen() {
    val c = LocalContext.current
    val container = remember { (c.applicationContext as com.nizam.app.NizamApplication).container }
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val jobs = remember(tick) { Schedules.all(c).sortedBy { Schedules.nextAt(it) ?: Long.MAX_VALUE } }
    var edit by remember { mutableStateOf<AgentJob?>(null) }
    var expanded by remember { mutableStateOf<Long?>(null) }
    var testing by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); tick++ } }
    edit?.let { j -> RoutineEditor(j, onSave = { Schedules.upsert(c, it); edit = null; tick++ }, onCancel = { edit = null }); return }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Routines", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("${jobs.count { it.enabled }} active · runs in the background, reports back, asks before anything risky",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Art3D("alarm_clock", 60.dp)
        } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { edit = AgentJob(System.currentTimeMillis(), "task", "", "daily", 9) }) { Text("+ New routine") }
        } }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(Triple("Morning brief", "task", "Give me today's plan: prayers, tasks, weather, anything due, and one thing to prioritise"),
                Triple("Dollar watch", "watcher", "Has the US dollar crossed 290 PKR in the open market today?"),
                Triple("Weekly money review", "task", "Review my spending this week and name the three biggest leaks with one fix each"),
                Triple("Inbox zero", "crew", "Go through what's waiting on me and draft replies for anything important")).forEach { (n, k, p) ->
                Capsule(n, false) { edit = AgentJob(System.currentTimeMillis(), k, p, if (n.startsWith("Weekly")) "weekly" else "daily", if (n == "Morning brief") 7 else 9, name = n) }
            }
        } }
        if (jobs.isEmpty()) item { Text("No routines yet. Start from one of the ideas above.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(jobs, key = { it.id }) { j ->
            val next = Schedules.nextAt(j)
            val last = j.history.firstOrNull()
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(if (j.enabled) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.045f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.02f))
                .clickable { expanded = if (expanded == j.id) null else j.id }.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f), MaterialTheme.colorScheme.tertiary.copy(alpha = 0.18f)))),
                        contentAlignment = Alignment.Center) { Text(when (j.kind) { "watcher" -> "👁"; "skill" -> "⚡"; "crew" -> "👥"; else -> "✦" }) }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(j.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${Schedules.KINDS[j.kind]} · ${cadenceText(j)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Next: ${whenText(next)}", style = MaterialTheme.typography.labelMedium, color = if (j.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(j.enabled, { on -> Schedules.upsert(c, j.copy(enabled = on)); tick++; Feedback.show(if (on) "Resumed" else "Paused") })
                }
                // a strip of the last runs: green worked, red failed
                if (j.history.isNotEmpty()) Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    j.history.take(20).reversed().forEach { r -> Box(Modifier.width(10.dp).height(18.dp).clip(RoundedCornerShape(3.dp))
                        .background(if (r.ok) Color(0xFF16A34A).copy(alpha = if (r.test) 0.45f else 0.9f) else MaterialTheme.colorScheme.error)) }
                    Text("  ${j.history.count { it.ok }}/${j.history.size} ok", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                last?.let { Text(it.result, style = MaterialTheme.typography.bodySmall, maxLines = if (expanded == j.id) 20 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp)) }
                AnimatedVisibility(expanded == j.id) {
                    Column(Modifier.padding(top = 10.dp)) {
                        Text("Instruction", style = MaterialTheme.typography.labelLarge); Text(j.prompt, style = MaterialTheme.typography.bodySmall)
                        Text("Approvals: " + when (j.approval) { "ask" -> "always ask me"; "auto_all" -> "auto-run reviewer-approved low/medium actions"; else -> "auto-run reviewer-approved low-risk actions" },
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { testing = j.id; scope.launch { val r = Schedules.runOne(c, container, j, test = true); testing = null; tick++
                                Feedback.show(if (r.ok) "Test run worked" else "Test run failed") } }, enabled = testing == null) {
                                if (testing == j.id) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Test now") }
                            OutlinedButton(onClick = { testing = j.id; scope.launch {
                                val r = runCatching { com.nizam.app.feature.wild.DryRun.explain(container, "Routine (${Schedules.KINDS[j.kind]}, ${j.cadence}): ${j.prompt}\nApproval rule: ${j.approval}") }
                                    .getOrElse { Feedback.friendly(it) }
                                Schedules.upsert(c, (Schedules.all(c).firstOrNull { it.id == j.id } ?: j).let { it.copy(history = listOf(RunRecord(System.currentTimeMillis(), true, "🧪 Dry run: $r", 0, true)) + it.history) })
                                testing = null; tick++ } }, enabled = testing == null) { Text("Dry run") }
                            OutlinedButton(onClick = { edit = j }) { Text("Edit") }
                            OutlinedButton(onClick = { Schedules.save(c, Schedules.all(c).filter { it.id != j.id }); tick++ }) { Text("Delete") }
                        }
                        if (j.history.isNotEmpty()) Text("Run history", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
                        j.history.take(12).forEach { r ->
                            Row(Modifier.padding(top = 4.dp)) {
                                Text(if (r.ok) "✓" else "✕", color = if (r.ok) Color(0xFF16A34A) else MaterialTheme.colorScheme.error, modifier = Modifier.width(18.dp))
                                Column { Text(Instant.ofEpochMilli(r.at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")) +
                                    " · ${r.ms / 1000}s" + if (r.test) " · test" else "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(r.result, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RoutineEditor(j0: AgentJob, onSave: (AgentJob) -> Unit, onCancel: () -> Unit) {
    val c = LocalContext.current
    var j by remember { mutableStateOf(j0) }
    val skills = remember { SkillsPro.all(c).map { it.optString("name") } }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text(if (j0.prompt.isBlank()) "New routine" else "Edit routine", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
        item { OutlinedTextField(j.name, { j = j.copy(name = it) }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true) }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Schedules.KINDS.forEach { (k, l) -> Capsule(l, j.kind == k) { j = j.copy(kind = k) } } } }
        item { Text(when (j.kind) { "watcher" -> "Checks a condition and only tells you when it becomes yes."; "skill" -> "Runs one of your saved skills."
            "crew" -> "Hands a goal to your agent team, which delegates and reports back."; else -> "Atlas does it and reports back." },
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (j.kind == "skill") item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (skills.isEmpty()) Text("No skills saved yet.") else skills.forEach { n -> Capsule(n, j.prompt == n) { j = j.copy(prompt = n) } } } }
        else item { OutlinedTextField(j.prompt, { j = j.copy(prompt = it) }, Modifier.fillMaxWidth(), minLines = 3,
            label = { Text(if (j.kind == "watcher") "What should it watch for?" else "What should it do?") }) }
        item { Text("When", style = MaterialTheme.typography.titleSmall) }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Schedules.CADENCES.forEach { (k, l) -> Capsule(l, j.cadence == k) { j = j.copy(cadence = k) } } } }
        if (j.cadence == "weekly") item { Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..7).forEach { d -> Capsule(DAYS[d - 1], d in j.days) { j = j.copy(days = if (d in j.days) j.days - d else j.days + d) } } } }
        if (j.cadence == "every_n") item { Column { Text("Every ${j.everyHours} hours"); Slider(j.everyHours.toFloat(), { j = j.copy(everyHours = it.toInt()) }, valueRange = 1f..12f, steps = 10) } }
        if (j.cadence == "monthly") item { Column { Text("On day ${j.dayOfMonth}"); Slider(j.dayOfMonth.toFloat(), { j = j.copy(dayOfMonth = it.toInt()) }, valueRange = 1f..28f, steps = 26) } }
        if (j.cadence != "every_n") item { Column {
            Text(if (j.cadence == "hourly") "At minute :%02d".format(j.minute) else "At %d:%02d".format(j.hour, j.minute), style = MaterialTheme.typography.labelLarge)
            if (j.cadence != "hourly") Slider(j.hour.toFloat(), { j = j.copy(hour = it.toInt()) }, valueRange = 0f..23f, steps = 22)
            Slider(j.minute.toFloat(), { j = j.copy(minute = (it.toInt() / 5) * 5) }, valueRange = 0f..55f, steps = 10)
        } }
        item { Text("Before it acts", style = MaterialTheme.typography.titleSmall) }
        item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("ask" to "Always ask me", "auto_low" to "Low-risk alone", "auto_all" to "Reviewer-approved low/medium").forEach { (k, l) -> Capsule(l, j.approval == k) { j = j.copy(approval = k) } } } }
        item { Row(verticalAlignment = Alignment.CenterVertically) { Switch(j.onlyNotifyChanges, { j = j.copy(onlyNotifyChanges = it) })
            Text("  Only notify me when the result changes", style = MaterialTheme.typography.bodyMedium) } }
        item { Text("Next run: ${whenText(Schedules.nextAt(j, System.currentTimeMillis()))}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge) }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { onSave(if (j0.prompt.isBlank()) j.copy(created = System.currentTimeMillis()) else j) }, enabled = j.prompt.isNotBlank()) { Text("Save") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
        } }
    }
}
