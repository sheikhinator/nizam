package com.nizam.app.feature.jarvis

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.agent.Converse
import com.nizam.app.feature.insight.Days
import com.nizam.app.ui.components.ChatText
import com.nizam.app.ui.components.Thinking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * The future-self simulator. Not a chat and not a guess: your real daily averages from the last
 * 60 days, run forward 1, 5 and 10 years — and the same run with one habit changed, side by side.
 * Small daily differences are invisible; compounded over a decade they're the whole story.
 */
data class Baseline(
    val studyMin: Double, val steps: Double, val sets: Double, val sleepH: Double,
    val prayers: Double, val focusMin: Double, val spendPerDay: Double, val incomePerDay: Double, val daysOfData: Int
)

data class Projection(
    val studyHours: Double, val km: Double, val sets: Double, val prayersOnTime: Double,
    val focusHours: Double, val saved: Double, val booksEquivalent: Double
)

object LifeSim {
    suspend fun baseline(container: AppContainer): Baseline {
        val days = Days.build(container, container.appContext, 60)
        fun avg(k: String, fallback: Double = 0.0): Double {
            val v = days.mapNotNull { it.values[k] }
            return if (v.isEmpty()) fallback else v.sum() / days.size.coerceAtLeast(1)   // missing days count as zero effort
        }
        fun avgRecorded(k: String, fallback: Double): Double = days.mapNotNull { it.values[k] }.let { if (it.isEmpty()) fallback else it.average() }
        val from = java.time.LocalDate.now().minusDays(59).toString()
        val income = runCatching { container.financeRepository.since(from).first().filter { it.kind == "INCOME" }.sumOf { it.amount } / 60.0 }.getOrElse { 0.0 }
        return Baseline(avg("study"), avgRecorded("steps", 4000.0), avg("sets"), avgRecorded("sleep", 7.0),
            avgRecorded("prayers", 0.0), avg("focus"), avg("spend"), income,
            days.count { d -> d.values.values.any { it != null } })
    }

    /** Runs a baseline forward; savings compound monthly at [rate] a year (a conservative real return). */
    fun project(b: Baseline, years: Int, rate: Double = 0.07): Projection {
        val d = 365.0 * years
        val monthly = (b.incomePerDay - b.spendPerDay) * 30.4
        val r = rate / 12; val n = years * 12
        val saved = if (monthly <= 0) monthly * n else if (r == 0.0) monthly * n else monthly * (((1 + r).pow(n) - 1) / r)
        val readingHours = b.studyMin * d / 60 * 0.3            // a share of study time as reading
        return Projection(
            studyHours = b.studyMin * d / 60, km = b.steps * 0.00075 * d, sets = b.sets * d,
            prayersOnTime = b.prayers * d, focusHours = b.focusMin * d / 60, saved = saved,
            booksEquivalent = readingHours / 6.0
        )
    }

    fun fmt(v: Double): String = when {
        kotlin.math.abs(v) >= 1_000_000 -> "%.1fM".format(v / 1_000_000)
        kotlin.math.abs(v) >= 10_000 -> "%.0fk".format(v / 1000)
        kotlin.math.abs(v) >= 100 -> "%.0f".format(v)
        else -> "%.1f".format(v)
    }
}

@Composable
fun LifeSimScreen(container: AppContainer) {
    val scope = com.nizam.app.ui.design.rememberWorkScope()
    var base by remember { mutableStateOf<Baseline?>(null) }
    LaunchedEffect(Unit) { base = runCatching { LifeSim.baseline(container) }.getOrNull() }
    var years by remember { mutableStateOf("5") }
    var studyPlus by remember { mutableFloatStateOf(0f) }
    var stepsPlus by remember { mutableFloatStateOf(0f) }
    var setsPlus by remember { mutableFloatStateOf(0f) }
    var spendCut by remember { mutableFloatStateOf(0f) }
    var prayersPlus by remember { mutableFloatStateOf(0f) }
    var story by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Life simulator", "Your real averages, run forward — and the same life with one habit changed")
        val b = base
        if (b == null) { Thinking(label = "Reading 60 days of your data"); return@Column }
        Text("From ${b.daysOfData} days with data: ${"%.0f".format(b.studyMin)} min study, ${"%.0f".format(b.steps)} steps, " +
            "${"%.1f".format(b.sets)} gym sets, ${"%.1f".format(b.prayers)} prayers logged and ${LifeSim.fmt(b.spendPerDay)} spent a day.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (b.daysOfData < 14) Text("⚠ Under two weeks of data — treat this as a sketch until more days are logged.",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 4.dp))

        Text("CHANGE ONE THING", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 16.dp))
        Lever("Study", "+${studyPlus.toInt()} min a day", studyPlus, 0f..120f) { studyPlus = it }
        Lever("Walking", "+${stepsPlus.toInt()} steps a day", stepsPlus, 0f..8000f) { stepsPlus = it }
        Lever("Gym", "+${"%.1f".format(setsPlus)} sets a day", setsPlus, 0f..6f) { setsPlus = it }
        Lever("Spending", "−${spendCut.toInt()}% a day", spendCut, 0f..40f) { spendCut = it }
        Lever("Prayer", "+${"%.1f".format(prayersPlus)} prayers on time a day", prayersPlus, 0f..(5f - b.prayers.toFloat()).coerceAtLeast(0.5f)) { prayersPlus = it }

        val changed = b.copy(studyMin = b.studyMin + studyPlus, steps = b.steps + stepsPlus, sets = b.sets + setsPlus,
            spendPerDay = b.spendPerDay * (1 - spendCut / 100.0), prayers = (b.prayers + prayersPlus).coerceAtMost(5.0))
        ChipRow(listOf("1", "5", "10"), years, { years = it }, labels = { "$it year${if (it == "1") "" else "s"}" },
            modifier = Modifier.padding(top = 12.dp))
        val y = years.toInt()
        val now = LifeSim.project(b, y); val alt = LifeSim.project(changed, y)
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Text("", Modifier.weight(1.3f))
            Text("As you are", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("With the change", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        }
        listOf(
            Triple("Hours studied", now.studyHours, alt.studyHours),
            Triple("Km walked", now.km, alt.km),
            Triple("Gym sets", now.sets, alt.sets),
            Triple("Prayers on time", now.prayersOnTime, alt.prayersOnTime),
            Triple("Saved (7%/yr)", now.saved, alt.saved),
            Triple("Books' worth read", now.booksEquivalent, alt.booksEquivalent)
        ).forEach { (label, a, c) ->
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Text(label, Modifier.weight(1.3f), style = MaterialTheme.typography.bodyMedium)
                Text(LifeSim.fmt(a), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(LifeSim.fmt(c), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (c != a) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (c != a) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
        }
        if (alt.studyHours >= 1000 && now.studyHours < 1000) Text("That change crosses 1,000 hours — the point where a skill becomes a profession.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))

        Button(enabled = !busy, modifier = Modifier.padding(top = 14.dp), onClick = {
            busy = true; story = ""
            scope.launch {
                val facts = "Projection over $y years. As they are: " + listOf(now).joinToString { p ->
                    "study ${LifeSim.fmt(p.studyHours)}h, walked ${LifeSim.fmt(p.km)}km, ${LifeSim.fmt(p.sets)} sets, " +
                        "${LifeSim.fmt(p.prayersOnTime)} prayers on time, saved ${LifeSim.fmt(p.saved)}" } +
                    ". With the change (+${studyPlus.toInt()} min study, +${stepsPlus.toInt()} steps, +${"%.1f".format(setsPlus)} sets, " +
                    "−${spendCut.toInt()}% spending, +${"%.1f".format(prayersPlus)} prayers): study ${LifeSim.fmt(alt.studyHours)}h, " +
                    "walked ${LifeSim.fmt(alt.km)}km, ${LifeSim.fmt(alt.sets)} sets, ${LifeSim.fmt(alt.prayersOnTime)} prayers on time, saved ${LifeSim.fmt(alt.saved)}."
                runCatching {
                    Converse.chat(container, "Describe, in first person as my future self $y years from now, what my life looks like on " +
                        "each path. Use these numbers exactly; be concrete about what they buy (skills, health, money, deen). Two short paragraphs.",
                        extraContext = facts) { story += it }
                }.onFailure { story = "Couldn't write it: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
                busy = false
            }
        }) { Text("Tell me both stories") }
        if (busy && story.isBlank()) Thinking(label = "Imagining both futures")
        if (story.isNotBlank()) ChatText(story, Modifier.padding(vertical = 10.dp))
        Text("A reflection tool, not a prophecy — it assumes today's averages hold.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun Lever(name: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    }
    Slider(v, onChange, valueRange = range)
}
