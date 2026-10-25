package com.nizam.app.feature.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Diary, overhauled: a writing streak, a mood calendar, "on this day" from months and years ago,
 * and how your mood moves with the rest of your life (gym days, all five prayers, heavy spending),
 * all worked out on the phone from what you already logged.
 */
val MORE_PROMPTS = PROMPTS + listOf(
    "What gave you energy today, and what drained it?",
    "Who did you think about most today, and why?",
    "What's one thing you're avoiding?",
    "What would make tomorrow a good day?",
    "What surprised you today?",
    "Describe today in three words, then explain them.",
    "What did you do today that future you will thank you for?")

fun moodColor(m: Double, empty: Color): Color = when {
    m <= 0 -> empty
    m < 1.8 -> Color(0xFFD9534F)
    m < 2.6 -> Color(0xFFE8A13A)
    m < 3.4 -> Color(0xFFC9B458)
    m < 4.2 -> Color(0xFF7DBE6B)
    else -> Color(0xFF2E9E6A)
}

@Composable
fun DiaryBoost(container: AppContainer, entries: List<DiaryEntry>, checkIns: List<MoodCheckIn>) {
    val cs = MaterialTheme.colorScheme
    val today = LocalDate.now()
    val moodBy = remember(entries, checkIns) {
        (entries.map { it.localDate to it.mood.toDouble() } + checkIns.map { it.localDate to it.mood.toDouble() })
            .groupBy({ it.first }, { it.second }).mapValues { it.value.average() }
    }
    val written = remember(entries) { entries.map { it.localDate }.toSet() }
    val streak = remember(written) {
        var n = 0; var d = if (today.toString() in written) today else today.minusDays(1)
        while (d.toString() in written && n < 1000) { n++; d = d.minusDays(1) }
        n
    }
    val words = remember(entries) { entries.sumOf { e -> e.body.split(Regex("\\s+")).count { it.isNotBlank() } } }

    com.nizam.app.ui.design.Group(Modifier.padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Stat("${streak}d${if (streak >= 3) " 🔥" else ""}", "writing streak")
            Stat("%,d".format(words), "words written")
            Stat("${written.size}", "days written")
        }
        // 5-week mood calendar
        Text("Mood calendar", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        val start = today.minusDays(34).let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
        }
        var d = start
        while (!d.isAfter(today)) {
            val weekStart = d
            Row(Modifier.fillMaxWidth()) {
                (0..6).forEach { i ->
                    val day = weekStart.plusDays(i.toLong())
                    Box(Modifier.weight(1f).padding(2.dp).aspectRatio(1f).background(
                        if (day.isAfter(today)) Color.Transparent else moodColor(moodBy[day.toString()] ?: 0.0, cs.surfaceVariant.copy(alpha = 0.6f)),
                        RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                        if (!day.isAfter(today)) Text("${day.dayOfMonth}", style = MaterialTheme.typography.labelSmall,
                            color = if (day.toString() in written) Color.White else cs.onSurfaceVariant)
                    }
                }
            }
            d = d.plusDays(7)
        }
        Text("Colour is mood (red low → green high); white numbers are days you wrote.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)

        // on this day
        val past = remember(entries) {
            entries.filter { e -> runCatching { LocalDate.parse(e.localDate) }.getOrNull()?.let { x -> x.dayOfMonth == today.dayOfMonth && x.isBefore(today) && (x.monthValue != today.monthValue || x.year != today.year) } == true }
                .take(3)
        }
        if (past.isNotEmpty()) {
            Text("On this day", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            past.forEach { e ->
                val x = LocalDate.parse(e.localDate)
                val ago = if (x.year != today.year) "${today.year - x.year} year${if (today.year - x.year > 1) "s" else ""} ago" else "${today.monthValue - x.monthValue} month${if (today.monthValue - x.monthValue > 1) "s" else ""} ago"
                Text("$ago · mood ${e.mood}", style = MaterialTheme.typography.labelMedium, color = cs.primary, modifier = Modifier.padding(top = 4.dp))
                Text(e.body.take(220) + if (e.body.length > 220) "…" else "", style = MaterialTheme.typography.bodySmall)
            }
        }

        // how mood moves with the rest of life
        val links by produceState(initialValue = emptyList<String>(), moodBy) {
            value = runCatching { lifeLinks(container, moodBy) }.getOrDefault(emptyList())
        }
        if (links.isNotEmpty()) {
            Text("What moves your mood", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            links.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp)) }
        }
    }
}

@Composable
private fun Stat(v: String, l: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(v, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(l, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Average mood on days with something versus days without it. Needs at least 3 days on each side. */
private suspend fun lifeLinks(container: AppContainer, moodBy: Map<String, Double>): List<String> {
    if (moodBy.size < 6) return emptyList()
    val from = LocalDate.now().minusDays(90).toString()
    val gymDays = container.gymRepository.sets().first().filter { it.localDate >= from }.map { it.localDate }.toSet()
    val prayerFull = container.prayerRepository.since(from).first()
        .filter { d -> listOf(d.fajr, d.dhuhr, d.asr, d.maghrib, d.isha).none { it == "NOT_PRAYED" } }.map { it.localDate }.toSet()
    val spendBy = container.financeRepository.since(from).first().filter { it.kind == "EXPENSE" }.groupBy { it.localDate }.mapValues { e -> e.value.sumOf { it.amount } }
    val spendMed = spendBy.values.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
    val out = ArrayList<String>()
    fun compare(label: String, yes: Set<String>, phrase: String) {
        val a = moodBy.filterKeys { it in yes }.values; val b = moodBy.filterKeys { it !in yes }.values
        if (a.size >= 3 && b.size >= 3) {
            val diff = a.average() - b.average()
            if (kotlin.math.abs(diff) >= 0.3) out += "$label: mood ${"%.1f".format(a.average())} vs ${"%.1f".format(b.average())} $phrase"
        }
    }
    compare("Gym days", gymDays, "on days without")
    compare("All five prayers", prayerFull, "on other days")
    if (spendMed > 0) compare("Heavy spending days", spendBy.filterValues { it > spendMed * 2 }.keys, "on other days")
    val dow = moodBy.entries.mapNotNull { (k, v) -> runCatching { LocalDate.parse(k).dayOfWeek to v }.getOrNull() }.groupBy({ it.first }, { it.second })
        .filter { it.value.size >= 2 }.mapValues { it.value.average() }
    if (dow.size >= 4) {
        val lo = dow.minByOrNull { it.value }!!
        out += "Lowest day of the week: ${lo.key.name.lowercase().replaceFirstChar { it.uppercase() }} (${"%.1f".format(lo.value)})"
    }
    return out
}
