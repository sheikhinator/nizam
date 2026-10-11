package com.nizam.app.feature.jarvis

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.feature.agent.Converse
import com.nizam.app.ui.components.ChatText
import com.nizam.app.ui.components.Thinking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Replay my day: the timeline (messages, apps, orders, voice, autopilot) merged with what the
 * database knows (money, prayers, tasks, workouts) into one ordered story you can scroll or question.
 */
object Replay {
    /** The day's full picture — timeline moments plus database events that carry a time. */
    suspend fun moments(container: AppContainer, day: LocalDate): List<Moment> {
        val c = container.appContext
        val zone = ZoneId.systemDefault()
        fun onDay(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate() == day
        val out = Timeline.day(c, day).toMutableList()
        runCatching {
            container.financeRepository.since(day.toString()).first().filter { it.localDate == day.toString() }.forEach { t ->
                out += Moment(t.createdAt, "money", (if (t.kind == "INCOME") "Received " else "Spent ") +
                    com.nizam.app.core.fmtAmount(t.amount) + " · ${t.category}", t.note)
            }
        }
        runCatching {
            container.taskRepository.all().first().filter { it.completedAt != null && onDay(it.completedAt!!) }.forEach { t ->
                out += Moment(t.completedAt!!, "task", "Finished: ${t.title}", "")
            }
        }
        runCatching {
            container.gymRepository.sets().first().filter { it.localDate == day.toString() }.groupBy { it.exercise }.forEach { (ex, sets) ->
                out += Moment(sets.minOf { it.createdAt }, "gym", "$ex · ${sets.size} sets", sets.joinToString { "${it.weight}×${it.reps}" })
            }
        }
        runCatching {
            container.prayerRepository.day(day.toString()).first()?.let { p ->
                val prayed = listOf("Fajr" to p.fajr, "Dhuhr" to p.dhuhr, "Asr" to p.asr, "Maghrib" to p.maghrib, "Isha" to p.isha)
                val summary = prayed.joinToString { "${it.first} ${if (it.second == "NOT_PRAYED") "✕" else "✓"}" }
                out += Moment(day.atTime(23, 59).atZone(zone).toInstant().toEpochMilli(), "prayer", "Prayers: $summary", "")
            }
        }
        return out.sortedBy { it.at }
    }

    fun text(day: LocalDate, list: List<Moment>): String =
        "Everything recorded on $day (${day.dayOfWeek}):\n" + list.takeLast(220).joinToString("\n") {
            "${it.time} [${it.kind}] ${it.title}" + if (it.detail.isNotBlank()) " — ${it.detail.take(90)}" else ""
        }
}

private val KIND_ICON = mapOf(
    "message" to "💬", "app" to "📱", "money" to "💸", "task" to "✅", "gym" to "🏋", "prayer" to "🕌",
    "order" to "⚡", "autopilot" to "🧭", "voice" to "🎙", "copilot" to "🪄", "receipt" to "🧾", "call" to "📞"
)

@Composable
fun ReplayTab(container: AppContainer) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberWorkScope()
    var which by remember { mutableStateOf("Today") }
    var filter by remember { mutableStateOf("All") }
    val day = when (which) { "Yesterday" -> LocalDate.now().minusDays(1); "2 days ago" -> LocalDate.now().minusDays(2); else -> LocalDate.now() }
    var moments by remember { mutableStateOf<List<Moment>>(emptyList()) }
    LaunchedEffect(day) { moments = Replay.moments(container, day) }
    var question by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Text("Ask about your days — \"when did I last hear from Ali?\", \"where did my evening go?\", \"what did I spend on food this week?\"",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(question, { question = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Ask your timeline") })
    Button(enabled = question.isNotBlank() && !busy, modifier = Modifier.padding(top = 6.dp), onClick = {
        busy = true; answer = ""
        scope.launch {
            // Recent days in full, plus keyword hits from the whole 60 days for "when did I last…"
            val recent = buildString {
                for (i in 0..2) {
                    val d = LocalDate.now().minusDays(i.toLong())
                    append(Replay.text(d, runCatching { Replay.moments(container, d) }.getOrElse { emptyList() })).append("\n\n")
                }
            }
            val hits = Timeline.search(c, question).joinToString("\n") { (d, m) -> "$d ${m.time} [${m.kind}] ${m.title} — ${m.detail.take(80)}" }
            runCatching {
                Converse.chat(container, question, extraContext = "THEIR TIMELINE (answer only from this; say plainly if it isn't there):\n$recent" +
                    (if (hits.isNotBlank()) "\n\nOlder matches:\n$hits" else "")) { answer += it }
                    ?: run { answer = "That needs an action — ask it in Chat." }
            }.onFailure { answer = "Couldn't answer: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
            busy = false
        }
    }) { Text("Ask") }
    if (busy && answer.isBlank()) Thinking(label = "Reading your timeline")
    if (answer.isNotBlank()) ChatText(answer, Modifier.padding(vertical = 8.dp))

    ChipRow(listOf("Today", "Yesterday", "2 days ago"), which, { which = it }, modifier = Modifier.padding(top = 12.dp))
    val kinds = listOf("All") + moments.map { it.kind }.distinct()
    ChipRow(kinds, filter, { filter = it })
    val shown = moments.filter { filter == "All" || it.kind == filter }
    if (shown.isEmpty()) Text("Nothing recorded yet. The timeline fills in from notifications, apps you open (with screen " +
        "control on), money, prayers, tasks and workouts.", style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
    shown.forEach { m ->
        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Text(m.time, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 48.dp))
            Text(KIND_ICON[m.kind] ?: "•")
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(m.title, style = MaterialTheme.typography.bodyMedium)
                if (m.detail.isNotBlank()) Text(m.detail.take(140), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
