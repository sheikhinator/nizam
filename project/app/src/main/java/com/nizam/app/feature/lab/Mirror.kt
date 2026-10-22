package com.nizam.app.feature.lab

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.agent.Snapshot
import com.nizam.app.feature.insight.DayVector
import com.nizam.app.feature.insight.Days
import com.nizam.app.feature.insight.METRICS
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.pow

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) { content() } }
}

/** The uncomfortable half of Atlas: what your own data says about you. */
@Composable
fun MirrorScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var days by remember { mutableStateOf<List<DayVector>>(emptyList()) }
    var tab by remember { mutableStateOf("Compound") }
    var ai by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { days = runCatching { Days.build(container, c, 365) }.getOrElse { emptyList() } } }

    fun askAi(label: String, prompt: suspend () -> String) = scope.launch {
        busy = true; ai = null
        ai = runCatching { Ai.generate(container.settingsRepository, prompt(), maxOutputTokens = 1200) }
            .getOrElse { Feedback.failed(label, it); null }
        busy = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Mirror", "What the evidence says — not what you'd like it to say")
        ChipRow(listOf("Compound", "Time machine", "Silent hours", "Integrity", "Energy debt", "Excuses", "Regret"), tab, { tab = it; ai = null })
        Spacer(Modifier.height(8.dp))

        val recent = days.takeLast(30)
        when (tab) {
            // 1 ── compound interest of habits, with the counterfactual beside it
            "Compound" -> {
                val focusPerDay = recent.mapNotNull { it.values["focus"] }.average().takeIf { !it.isNaN() } ?: 0.0
                val studyPerDay = recent.mapNotNull { it.values["study"] }.average().takeIf { !it.isNaN() } ?: 0.0
                val setsPerDay = recent.mapNotNull { it.values["sets"] }.average().takeIf { !it.isNaN() } ?: 0.0
                Card {
                    Text("If the last 30 days repeat for ten years", style = MaterialTheme.typography.titleMedium)
                    Text("${((focusPerDay + studyPerDay) * 3650 / 60).toInt()} hours of focused work and study",
                        style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Text("${(setsPerDay * 3650).toInt()} sets lifted · ${(recent.count { (it.values["prayers"] ?: 0.0) >= 5 } * 365 / 30 * 10)} full prayer days",
                        style = MonoNumber)
                    Text("That's roughly ${"%.1f".format((focusPerDay + studyPerDay) * 3650 / 60 / 2000)} working years of deliberate effort. " +
                        "Habits don't add up — they compound, and so does their absence.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Card {
                    Text("Counterfactual", style = MaterialTheme.typography.titleMedium)
                    val best = days.chunked(30).maxByOrNull { chunk -> chunk.mapNotNull { it.values["focus"] }.sum() }
                    val bestRate = best?.mapNotNull { it.values["focus"] }?.average()?.takeIf { !it.isNaN() } ?: 0.0
                    val gap = (bestRate - focusPerDay) * 365 / 60
                    Text(if (gap > 1) "At your best month's pace you'd gain ${gap.toInt()} extra focused hours a year — " +
                        "${"%.0f".format(gap / 8)} working days you've already proved you can do."
                    else "You're currently at or above your best month's pace. Hold it.",
                        style = MaterialTheme.typography.bodyLarge)
                }
            }
            // 2 ── time machine: scrub back through any day
            "Time machine" -> {
                var index by remember(days) { mutableStateOf(days.size.toFloat() - 1) }
                val day = days.getOrNull(index.toInt())
                Card {
                    Text(day?.date?.toString() ?: "No data", style = MaterialTheme.typography.titleLarge)
                    Slider(index, { index = it }, valueRange = 0f..(days.size - 1).coerceAtLeast(1).toFloat())
                    day?.values?.filterValues { it != null }?.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth()) {
                            Text(METRICS[k] ?: k, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            Text("%.1f".format(v), style = MonoNumber)
                        }
                    }
                    if (day?.values?.values?.all { it == null } != false) Text("Nothing logged that day.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            // 3 ── the hours nobody measures
            "Silent hours" -> {
                val silent = days.filter { d -> d.values.values.all { it == null || it == 0.0 } }
                Card {
                    Text("${silent.size} silent days in the last year", style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Text("Nothing achieved, nothing logged, no rest taken either — the days that simply went.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val byWeekday = silent.groupingBy { it.date.dayOfWeek.name.lowercase().replaceFirstChar { ch -> ch.uppercase() } }.eachCount()
                        .entries.sortedByDescending { it.value }.take(3)
                    if (byWeekday.isNotEmpty()) Text("Most often: " + byWeekday.joinToString { "${it.key} (${it.value})" },
                        style = MaterialTheme.typography.bodyMedium)
                }
            }
            // 4 ── integrity: said vs did
            "Integrity" -> {
                val promises = runCatching {
                    val a = org.json.JSONArray(java.io.File(c.filesDir, "promises.json").readText())
                    (0 until a.length()).map { a.getJSONObject(it) }
                }.getOrElse { emptyList() }
                val closed = promises.filter { it.optString("state") != "open" }
                val kept = closed.count { it.optString("state") == "kept" }
                val missionDays = recent.count { (it.values["missions"] ?: 0.0) > 0 }
                Card {
                    Text(if (closed.isEmpty()) "No promises closed yet" else "${kept * 100 / closed.size}% of promises kept",
                        style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
                    Text("$missionDays of the last 30 days had a mission completed.", style = MaterialTheme.typography.bodyMedium)
                    Button(enabled = !busy, onClick = {
                        askAi("Integrity") { "${Snapshot.of(container)}\nPromises kept: $kept of ${closed.size}. Mission days: $missionDays/30.\n\n" +
                            "Score this person's integrity — the gap between what they say they'll do and what they do — per area of life " +
                            "(faith, body, work, money, people). One line each with the evidence, then the single area where the gap is widest. " +
                            "Direct, not cruel." }
                    }) { Text(if (busy) "Scoring…" else "Score my follow-through") }
                }
            }
            // 5 ── accumulated deficit, before the crash
            "Energy debt" -> {
                val sleepDebt = recent.mapNotNull { it.values["sleep"] }.sumOf { (7.5 - it).coerceAtLeast(0.0) }
                val heavy = recent.count { (it.values["focus"] ?: 0.0) > 240 || (it.values["sets"] ?: 0.0) > 25 }
                val restDays = recent.count { (it.values["focus"] ?: 0.0) < 30 && (it.values["sets"] ?: 0.0) == 0.0 }
                Card {
                    Text("${"%.1f".format(sleepDebt)} hours of sleep debt", style = MaterialTheme.typography.headlineSmall,
                        color = if (sleepDebt > 20) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                    Text("$heavy heavy days · $restDays genuine rest days in the last 30", style = MonoNumber)
                    Text(when {
                        sleepDebt > 25 && restDays < 3 -> "This is the shape that precedes a crash: debt accumulating with no repayment. Take a day."
                        sleepDebt > 15 -> "Manageable, but it's building. One early night this week clears most of it."
                        else -> "You're roughly square. Nothing owed."
                    }, style = MaterialTheme.typography.bodyLarge)
                }
            }
            // 6 ── the stories you tell before you quit
            "Excuses" -> Card {
                Text("Excuse detector", style = MaterialTheme.typography.titleMedium)
                Text("Atlas reads your diary entries from the days before you dropped a streak, and names the phrases that " +
                    "reliably show up first.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        val entries = runCatching { container.diaryRepository.entries().first().take(120) }.getOrElse { emptyList() }
                        val broke = days.windowed(2)
                            .filter { w -> (w[0].values["prayers"] ?: 0.0) > 0 && (w[1].values["prayers"] ?: 0.0) == 0.0 }
                            .map { w -> w[1].date.toString() }
                        ai = runCatching {
                            Ai.generate(container.settingsRepository,
                                "Diary entries:\n" + entries.joinToString("\n") { "${it.localDate}: ${it.body.take(300)}" } +
                                    "\n\nDays where a streak broke: ${broke.joinToString()}\n\n" +
                                    "Find the specific phrases and reasoning this person uses in the days BEFORE they drop something. " +
                                    "Quote their own words. Then give the three-word warning sign to watch for. If there isn't enough " +
                                    "evidence, say so plainly instead of guessing.", maxOutputTokens = 900)
                        }.getOrElse { Feedback.failed("Excuse detector", it); null }
                        busy = false
                    }
                }) { Text(if (busy) "Reading…" else "Find my patterns") }
            }
            // 7 ── how you'll feel about this in a year
            else -> {
                var decision by remember { mutableStateOf("") }
                Card {
                    Text("Regret forecast", style = MaterialTheme.typography.titleMedium)
                    com.nizam.app.ui.design.OutlinedTextField(decision, { decision = it },
                        label = { Text("What are you about to do?") }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = decision.isNotBlank() && !busy, onClick = {
                        askAi("Regret forecast") { "${Snapshot.of(container)}\n\nThey're considering: ${decision.trim()}\n\n" +
                            "Using how their past similar decisions actually turned out, estimate honestly how they'll feel about this " +
                            "in a year: the likely regret, the likely relief, and the one factor that decides which. Three short paragraphs." }
                    }) { Text(if (busy) "Thinking…" else "Forecast it") }
                }
            }
        }
        ai?.let { Card { Text(it, style = MaterialTheme.typography.bodyMedium) } }
        Spacer(Modifier.height(60.dp))
    }
}
