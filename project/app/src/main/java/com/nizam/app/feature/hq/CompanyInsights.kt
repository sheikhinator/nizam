package com.nizam.app.feature.hq

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.agent.ApprovalInbox
import com.nizam.app.feature.agent.Crew
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.wild.AgentMeter
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil
import kotlin.math.floor

private data class WorkSample(
    val threadId: String,
    val goal: String,
    val category: String,
    val status: String,
    val at: Long,
    val toolEvents: Int,
    val toolEvidence: List<ToolEvidence>,
    val failures: List<String>
)

private data class ToolEvidence(val action: String, val target: String, val summary: String, val ok: Boolean)

private fun recordedToolEvidence(event: JSONObject): List<ToolEvidence> {
    val rows = event.optJSONArray("toolEvidence") ?: return emptyList()
    return (0 until rows.length()).mapNotNull { i ->
        val row = rows.optJSONObject(i) ?: return@mapNotNull null
        val action = row.optString("action").takeIf { it.isNotBlank() } ?: return@mapNotNull null
        ToolEvidence(action, row.optString("target"), row.optString("summary").take(220), row.optBoolean("ok"))
    }
}

private fun taskCategory(goal: String): String {
    val text = goal.lowercase()
    return when {
        listOf("budget", "spend", "cost", "invoice", "money", "finance").any { it in text } -> "Finance"
        listOf("write", "draft", "email", "message", "post", "copy").any { it in text } -> "Writing"
        listOf("plan", "schedule", "trip", "week", "event", "calendar").any { it in text } -> "Planning"
        listOf("research", "compare", "find", "source", "investigate").any { it in text } -> "Research"
        listOf("automate", "connector", "app", "device", "code", "setup").any { it in text } -> "Operations"
        else -> "General"
    }
}

private fun samplesFor(agentId: String, threads: List<JSONObject>): List<WorkSample> =
    threads.mapNotNull { thread ->
        val messages = thread.optJSONArray("messages") ?: return@mapNotNull null
        val events = (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }
            .filter { it.optString("from") == agentId }
        if (events.isEmpty()) return@mapNotNull null
        val toolEvidence = events.flatMap(::recordedToolEvidence)
        WorkSample(
            threadId = thread.optString("id"),
            goal = thread.optString("goal", "Untitled assignment").substringAfter("Topic: ").lines().first().take(100),
            category = taskCategory(thread.optString("goal")),
            status = thread.optString("status"),
            at = events.maxOfOrNull { it.optLong("t") } ?: thread.optLong("updated", thread.optLong("created")),
            toolEvents = events.count { it.optString("kind") == "tool" },
            toolEvidence = toolEvidence,
            failures = (events.filter {
                it.optString("kind") == "tool" && (it.optString("text").startsWith("✕") || it.optString("text").contains("FAILED", true))
            }.map { it.optString("text").take(180) } + toolEvidence.filterNot { it.ok }
                .map { "${it.target.ifBlank { it.action }}: ${it.summary}".take(180) }).distinct().take(5)
        )
    }.sortedByDescending { it.at }

private object ExampleReviews {
    private fun store(c: android.content.Context) = JsonStore(c, "company_example_reviews", sealed = true)
    fun all(c: android.content.Context) = store(c).all()
    fun key(threadId: String, agentId: String) = "$threadId:$agentId"
    fun review(c: android.content.Context, sample: WorkSample, agentId: String, rating: String) {
        val s = store(c)
        val record = s.all().firstOrNull {
            it.optString("threadId") == sample.threadId && it.optString("agentId") == agentId
        } ?: JSONObject().put("threadId", sample.threadId).put("agentId", agentId)
        record.put("rating", rating).put("goal", sample.goal).put("at", System.currentTimeMillis())
        s.save(s.all().filterNot { it.optString("threadId") == sample.threadId && it.optString("agentId") == agentId } + record)
    }
}

@Composable
fun CompanyInsights(container: AppContainer) {
    val c = LocalContext.current
    val version = Crew.version
    val team = remember(version) { Crew.agents(c) }
    val profiles = remember(version) { team.associate { it.id to Company.profile(c, it) } }
    val stats = remember(version) { team.associate { it.id to Company.stats(c, it) } }
    val board = remember(version) { Company.board(c) }
    val threads = remember(version) { Crew.threads(c).all().sortedByDescending { it.optLong("updated", it.optLong("created")) } }
    val evidence = remember(version) { team.associate { it.id to samplesFor(it.id, threads) } }
    val reviews = remember(version) { ExampleReviews.all(c).associateBy { ExampleReviews.key(it.optString("threadId"), it.optString("agentId")) } }
    var replayId by remember { mutableStateOf("") }
    var fitQuery by remember { mutableStateOf("") }
    var batchDraft by remember { mutableStateOf("") }
    var heldBatchTasks by remember { mutableStateOf(emptySet<String>()) }
    var batchAssignments by remember { mutableStateOf(emptyMap<String, String>()) }
    var assigningTask by remember { mutableStateOf("") }
    val names = remember(team) { team.associate { it.id to "${it.emoji} ${it.name}" } }
    val weekAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
    val completedWeek = threads.count { it.optString("status") == "done" && it.optLong("updated", it.optLong("created")) >= weekAgo }
    val waiting = board.backlog.size
    val active = board.doing.size
    val review = board.review.size
    val running = threads.count { it.optString("status") == "running" }
    val parallelLimit = Hq.limit(c)
    val approvals = ApprovalInbox.pending(c).size
    val tokensToday = team.sumOf { AgentMeter.today(c, it.name).first }
    val dailyCap = Company.dailyCap(c)
    val batchTasks = batchDraft.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct()
    val readyBatch = batchTasks.filterNot { it in heldBatchTasks }
    val agentWork = waiting + active + readyBatch.size
    val perDay = completedWeek / 7.0
    val estimateDays = if (perDay > 0) ceil(agentWork / perDay).toInt() else null
    val lowDays = estimateDays?.let { floor(it * 0.75).toInt().coerceAtLeast(1) }
    val highDays = estimateDays?.let { ceil(it * 1.5).toInt().coerceAtLeast(lowDays ?: 1) }
    val todayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    val completedToday = threads.count {
        it.optString("status") == "done" && it.optLong("updated", it.optLong("created")) >= todayStart
    }
    val averageTokensPerTask = if (completedToday > 0) ceil(tokensToday.toDouble() / completedToday).toInt() else null
    val batchLowTokens = averageTokensPerTask?.let { floor(it * 0.75).toInt() * readyBatch.size }
    val batchHighTokens = averageTokensPerTask?.let { ceil(it * 1.5).toInt() * readyBatch.size }
    val likelyParallelism = if (parallelLimit == 0) 0 else minOf(readyBatch.size, (parallelLimit - running).coerceAtLeast(0))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("HQ insights", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Read-only estimates from your team’s saved profiles, board and threads.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)

        InsightSection("Expertise map", "Examples come from saved team threads. Configured permissions are not treated as demonstrated skill.") {
            if (team.isEmpty()) Text("No agents on the team yet.")
            team.forEach { agent ->
                val profile = profiles[agent.id]
                val department = Company.DEPTS.firstOrNull { it.id == profile?.dept }?.name ?: "Unassigned"
                val capabilityNames = agent.tools.mapNotNull { Crew.TOOLS[it] }
                val stat = stats[agent.id]
                val examples = evidence[agent.id].orEmpty()
                val commonTypes = examples.groupingBy { it.category }.eachCount().entries.sortedByDescending { it.value }.take(3)
                val reviewedCount = reviews.values.count { it.optString("agentId") == agent.id }
                val observedSteps = examples.flatMap { it.toolEvidence }
                val connectorEvidence = observedSteps.filter { it.action in setOf("api_call", "mcp_call") }
                    .map { "${it.target.ifBlank { it.action.replace('_', ' ') }} · ${it.summary.ifBlank { if (it.ok) "completed" else "failed" }}" }
                    .distinct().take(3)
                val skillEvidence = observedSteps.filter { it.action.contains("skill", true) }
                    .map { "${it.target.ifBlank { "saved skill" }} · ${it.summary.ifBlank { if (it.ok) "completed" else "failed" }}" }
                    .distinct().take(3)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)
                ) {
                    Text("${agent.emoji} ${agent.name} · ${agent.role}", fontWeight = FontWeight.SemiBold)
                    Text("$department · ${profile?.let { Company.LEVELS[it.level.coerceIn(0, Company.LEVELS.lastIndex)] } ?: "Associate"}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Configured: ${capabilityNames.ifEmpty { listOf("Drafts only") }.joinToString()} · ${examples.sumOf { it.toolEvents }} recorded tool events",
                        modifier = Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodySmall)
                    Text("${examples.size} assignment samples · ${commonTypes.joinToString { "${it.key} ${it.value}" }.ifBlank { "no task-type evidence yet" }}",
                        style = MaterialTheme.typography.bodySmall)
                     Text("Observed connectors: ${connectorEvidence.ifEmpty { listOf("no recorded connector calls") }.joinToString(" · ")}",
                         style = MaterialTheme.typography.bodySmall)
                     Text("Observed skills: ${skillEvidence.ifEmpty { listOf("no recorded skill calls") }.joinToString(" · ")}",
                         style = MaterialTheme.typography.bodySmall)
                    Text("${examples.sumOf { it.failures.size }} recorded tool failures · $reviewedCount reviewed examples · " +
                        "${examples.firstOrNull()?.let { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it.at)) } ?: "no recent sample"}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    examples.filter { it.status == "done" }.take(2).forEach { sample ->
                        val currentReview = reviews[ExampleReviews.key(sample.threadId, agent.id)]?.optString("rating").orEmpty()
                        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                            Text("Example: ${sample.goal}", style = MaterialTheme.typography.labelMedium, maxLines = 2)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { ExampleReviews.review(c, sample, agent.id, "helpful"); Crew.version++ }) {
                                    Text(if (currentReview == "helpful") "✓ Helpful" else "Helpful")
                                }
                                OutlinedButton(onClick = { ExampleReviews.review(c, sample, agent.id, "needs_work"); Crew.version++ }) {
                                    Text(if (currentReview == "needs_work") "✓ Needs work" else "Needs work")
                                }
                            }
                        }
                    }
                    val failures = examples.flatMap { it.failures }.take(2)
                    if (failures.isNotEmpty()) Text("Failure notes: ${failures.joinToString(" · ")}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    Text("${stat?.done ?: 0} completed messages · ${Crew.busyAgents[agent.id]?.let { "working now" } ?: "available"}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OutlinedTextField(fitQuery, { fitQuery = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Suggest a fit for an assignment") }, singleLine = true)
            if (fitQuery.isNotBlank()) {
                val category = taskCategory(fitQuery)
                Text("Task type: $category. Suggestions do not route or promote anyone.")
                team.map { agent -> agent to evidence[agent.id].orEmpty().count { it.category == category } }
                    .sortedByDescending { it.second }.take(3).forEach { (agent, count) ->
                        Text("${agent.emoji} ${agent.name} · $count saved examples" +
                            if (count == 0) " · no evidence yet" else "", style = MaterialTheme.typography.bodySmall)
                    }
            }
        }

        InsightSection("Workload forecast", "Ranges use recent throughput and current team settings; estimates are not deadlines or promises.") {
            Text("$waiting queued · $active in progress · $review waiting for review", style = MaterialTheme.typography.titleSmall)
            Text("Parallelism: $running running of ${if (parallelLimit == 0) "paused" else parallelLimit} allowed · $approvals approvals waiting")
            Text("$likelyParallelism of ${readyBatch.size} ready draft tasks can start immediately at the current limit.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (approvals > 0) Text("Pending approvals may delay active work; the wait range does not assume an approval turnaround time.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$completedWeek completed in 7 days (${String.format("%.1f", perDay)} per day)",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (dailyCap > 0) Text("Token budget: $tokensToday of $dailyCap used today · ${(dailyCap - tokensToday).coerceAtLeast(0)} remaining.")
            else Text("No team daily token cap is set, so usage cannot be projected against a budget.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (readyBatch.isNotEmpty()) {
                when {
                    dailyCap <= 0 -> Text("Set a team daily token cap to compare this batch with the chosen budget.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    averageTokensPerTask == null -> Text("Batch token range unavailable: no completed team assignment is recorded today.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> Text("Batch estimate: +$batchLowTokens–$batchHighTokens tokens; projected total ${tokensToday + (batchLowTokens ?: 0)}–${tokensToday + (batchHighTokens ?: 0)} of $dailyCap. " +
                        "Range uses today’s token use divided by $completedToday completed assignment${if (completedToday == 1) "" else "s"}, then applies a 0.75–1.5× range.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (estimateDays == null) {
                Text("Not enough recent completions for a wait estimate.")
            } else {
                Text("Estimated wait range for $agentWork queued, active and ready draft items: $lowDays–$highDays days. " +
                    "Held drafts are excluded. Assumes a similar task mix; approval waits can extend it.", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(
                    progress = { if (agentWork == 0) 1f else (active.toFloat() / agentWork).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("In progress: $active of $agentWork queued, active and drafted items. Review items are excluded from throughput.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(batchDraft, { batchDraft = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("Draft a batch · one task per line") }, minLines = 2)
            Text("Each line is a separate task. Reorder, assign, hold or queue items individually before dispatching the ready batch.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            batchTasks.forEachIndexed { index, task ->
                val held = task in heldBatchTasks
                val assignedName = team.firstOrNull { it.id == batchAssignments[task] }?.let { "${it.emoji} ${it.name}" } ?: "Auto-route"
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text(task, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        OutlinedButton(onClick = {
                            val reordered = batchTasks.toMutableList()
                            val moved = reordered.removeAt(index)
                            reordered.add(index - 1, moved)
                            batchDraft = reordered.joinToString("\n")
                        }, enabled = index > 0) { Text("↑") }
                        OutlinedButton(onClick = {
                            val reordered = batchTasks.toMutableList()
                            val moved = reordered.removeAt(index)
                            reordered.add(index + 1, moved)
                            batchDraft = reordered.joinToString("\n")
                        }, enabled = index < batchTasks.lastIndex) { Text("↓") }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        OutlinedButton(onClick = { assigningTask = if (assigningTask == task) "" else task }) {
                            Text("Assign · $assignedName")
                        }
                        androidx.compose.material3.DropdownMenu(
                            expanded = assigningTask == task,
                            onDismissRequest = { assigningTask = "" }
                        ) {
                            androidx.compose.material3.DropdownMenuItem(text = { Text("Auto-route") }, onClick = {
                                batchAssignments = batchAssignments - task; assigningTask = ""
                            })
                            team.forEach { agent ->
                                androidx.compose.material3.DropdownMenuItem(text = { Text("${agent.emoji} ${agent.name}") }, onClick = {
                                    batchAssignments = batchAssignments + (task to agent.id); assigningTask = ""
                                })
                            }
                        }
                        OutlinedButton(onClick = {
                            heldBatchTasks = if (held) heldBatchTasks - task else heldBatchTasks + task
                        }) { Text(if (held) "Resume" else "Hold") }
                        if (!held) OutlinedButton(onClick = {
                            Hq.enqueue(container, task, batchAssignments[task])
                            val remaining = batchTasks.filterNot { it == task }
                            batchDraft = remaining.joinToString("\n")
                            heldBatchTasks = heldBatchTasks.intersect(remaining.toSet())
                            batchAssignments = batchAssignments.filterKeys { it in remaining }
                        }) { Text("Queue") }
                    }
                }
            }
            Button(enabled = readyBatch.isNotEmpty(), onClick = {
                readyBatch.forEach { Hq.enqueue(container, it, batchAssignments[it]) }
                val remaining = batchTasks.filter { it in heldBatchTasks }
                batchDraft = remaining.joinToString("\n")
                heldBatchTasks = heldBatchTasks.intersect(remaining.toSet())
                batchAssignments = batchAssignments.filterKeys { it in remaining }
            }) { Text("Queue ${readyBatch.size} ready task${if (readyBatch.size == 1) "" else "s"}") }
        }

        InsightSection("Work replay", "Review the recorded event sequence. Nothing is replayed or reconstructed.") {
            if (threads.isEmpty()) Text("No saved work threads yet.")
            threads.take(10).forEach { thread ->
                val id = thread.optString("id")
                val expanded = replayId == id
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant).clickable {
                            replayId = if (expanded) "" else id
                        }.padding(12.dp)
                ) {
                    Text(thread.optString("goal", "Untitled work"), fontWeight = FontWeight.SemiBold, maxLines = 2)
                    Text("${thread.optString("status")} · ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(thread.optLong("updated", thread.optLong("created"))))}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (expanded) {
                        Spacer(Modifier.height(8.dp))
                        val messages = thread.optJSONArray("messages")
                        val rows = if (messages == null) emptyList() else (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }.takeLast(12)
                        if (rows.isEmpty()) Text("No saved messages in this thread.", style = MaterialTheme.typography.bodySmall)
                        rows.forEach { message ->
                            val sender = names[message.optString("from")] ?: message.optString("from").ifBlank { "System" }
                            val kind = message.optString("kind").replace('_', ' ')
                            val at = message.optLong("t")
                            Text("$sender · $kind · ${if (at > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at)) else "time not recorded"}",
                                fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelSmall)
                            Text(message.optString("text").take(500), style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(bottom = 6.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InsightSection(title: String, detail: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        content()
    }
}

@Composable
fun HqReplaySheet(container: AppContainer) {
    val c = LocalContext.current
    val version = Crew.version
    val agents = remember(version) { Crew.agents(c).associate { it.id to "${it.emoji} ${it.name}" } }
    val threads = remember(version) {
        Crew.threads(c).all().sortedByDescending { it.optLong("updated", it.optLong("created")) }
    }
    var selectedId by remember { mutableStateOf("") }
    val selected = threads.firstOrNull { it.optString("id") == selectedId }
    val events = remember(selectedId, selected?.optLong("updated")) {
        val messages = selected?.optJSONArray("messages") ?: return@remember emptyList()
        (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }.sortedBy { it.optLong("t") }
    }
    var cursor by remember(selectedId, events.size) { mutableStateOf(0f) }
    val selectedIndex = cursor.toInt().coerceIn(0, (events.size - 1).coerceAtLeast(0))

    Text("⏪ Work replay", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Text("Recorded events only. Tap an event or scrub the timeline; missing activity is not reconstructed.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (threads.isEmpty()) {
        Text("No saved team assignments yet.")
        return
    }
    Text("Assignments", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    threads.take(12).forEach { thread ->
        val id = thread.optString("id")
        val active = id == selectedId
        OutlinedButton(onClick = { selectedId = id; cursor = 0f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("${if (active) "▶ " else ""}${thread.optString("goal", "Untitled assignment").take(90)} · ${thread.optString("status")}")
        }
    }
    selected?.let { thread ->
        Text("Recorded timeline · ${events.size} events", style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp))
        if (events.isEmpty()) Text("This assignment has no saved events.")
        else {
            if (events.size > 1) {
                androidx.compose.material3.Slider(
                    value = cursor.coerceIn(0f, events.lastIndex.toFloat()),
                    onValueChange = { cursor = it },
                    valueRange = 0f..events.lastIndex.toFloat()
                )
            }
            events.forEachIndexed { index, event ->
                val active = index == selectedIndex
                val kind = event.optString("kind").ifBlank { "event" }.replace('_', ' ')
                val actor = agents[event.optString("from")] ?: event.optString("from").ifBlank { "System" }
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { cursor = index.toFloat() }.padding(10.dp)
                ) {
                    Text("${index + 1}. $actor · $kind", fontWeight = FontWeight.SemiBold)
                    Text(if (event.optLong("t") > 0) DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                        .format(Date(event.optLong("t"))) else "Time not recorded",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (active) {
                        Text(event.optString("text").ifBlank { "No event text was recorded." },
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                        val evidence = event.optJSONArray("toolEvidence")
                        if (evidence != null) (0 until evidence.length()).forEach { i ->
                            val step = evidence.optJSONObject(i) ?: return@forEach
                            Text("${if (step.optBoolean("ok")) "✓" else "✕"} ${step.optString("target").ifBlank { step.optString("action") }}: ${step.optString("summary")}",
                                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp, top = 3.dp))
                        }
                        val legacySteps = event.optJSONArray("steps")
                        if (legacySteps != null && evidence == null) (0 until legacySteps.length()).forEach { i ->
                            Text(legacySteps.optString(i), style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(start = 8.dp, top = 3.dp))
                        }
                        if (event.optString("approval").isNotBlank()) Text("Approval record · ${event.optString("approval")}",
                            style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
        val result = events.lastOrNull { it.optString("kind") == "final" }
        Text("Recorded result", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 10.dp))
        if (result == null) Text("No final result was saved for this assignment.")
        else Text(result.optString("text").ifBlank { "The final event has no saved text." },
            style = MaterialTheme.typography.bodySmall)
    }
}