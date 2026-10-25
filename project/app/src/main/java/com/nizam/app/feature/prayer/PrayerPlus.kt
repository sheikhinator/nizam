package com.nizam.app.feature.prayer

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.core.hourToClock
import com.nizam.app.ui.components.Feedback
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime

/**
 * Prayer, overhauled: the live window you're in and the countdown to the next, the times to avoid,
 * your masjid's jamaat times with a reminder before each, a week at a glance, streaks per prayer,
 * and a qada counter that goes down as you make them up. Nothing here generates religious text.
 */
object PrayerPlus {
    val KEYS = listOf("fajr", "dhuhr", "asr", "maghrib", "isha")
    val NAMES = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")
    private fun p(c: Context) = c.getSharedPreferences("prayer_plus", 0)

    fun jamaat(c: Context, key: String): String = p(c).getString("j_$key", "").orEmpty()
    fun setJamaat(c: Context, key: String, v: String) = p(c).edit().putString("j_$key", v.trim()).apply()
    fun jamaatHour(c: Context, key: String): Double? = parseClock(jamaat(c, key))

    fun madeUp(c: Context) = p(c).getInt("qada_made", 0)
    fun addMadeUp(c: Context, n: Int) = p(c).edit().putInt("qada_made", (madeUp(c) + n).coerceAtLeast(0)).apply()
    fun qadaStart(c: Context): String = p(c).getString("qada_from", null) ?: LocalDate.now().minusDays(29).toString().also { p(c).edit().putString("qada_from", it).apply() }

    fun parseClock(s: String): Double? {
        val m = Regex("""^\s*(\d{1,2})(?::(\d{2}))?\s*(am|pm)?\s*$""", RegexOption.IGNORE_CASE).find(s) ?: return null
        var h = m.groupValues[1].toInt(); val min = m.groupValues[2].toIntOrNull() ?: 0
        val ap = m.groupValues[3].lowercase()
        if (ap == "pm" && h < 12) h += 12; if (ap == "am" && h == 12) h = 0
        if (h !in 0..23 || min !in 0..59) return null
        return h + min / 60.0
    }

    fun statuses(d: PrayerDay?) = listOf(d?.fajr, d?.dhuhr, d?.asr, d?.maghrib, d?.isha).map { it ?: "NOT_PRAYED" }

    /** Consecutive days (ending today, or yesterday if today's isn't due yet) each prayer was logged. */
    fun streaks(month: List<PrayerDay>): List<Int> {
        val by = month.associateBy { it.localDate }
        return KEYS.indices.map { i ->
            var n = 0; var d = LocalDate.now()
            if (statuses(by[d.toString()])[i] == "NOT_PRAYED") d = d.minusDays(1)
            while (statuses(by[d.toString()])[i] != "NOT_PRAYED" && n < 400) { n++; d = d.minusDays(1) }
            n
        }
    }

    /** Remind 10 minutes before each jamaat the person set. Runs from the background loop. */
    fun tick(c: Context, now: Double) {
        val today = LocalDate.now().toString()
        val sent = c.getSharedPreferences("prayer_plus_sent", 0)
        KEYS.forEachIndexed { i, k ->
            val h = jamaatHour(c, k) ?: return@forEachIndexed
            if (now in (h - 0.17)..(h - 0.05) && sent.getString(k, "") != today) {
                sent.edit().putString(k, today).apply()
                com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_PRAYER, 140 + i,
                    "${NAMES[i]} jamaat at ${hourToClock(h)}", "About ten minutes to go.")
            }
        }
    }
}

/** The live hero: which prayer's time it is, how long is left, what's next. */
@Composable
fun PrayerNow(times: PrayerTimes.Times, statuses: List<String>) {
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { while (true) { delay(30_000); tick++ } } }
    val now = remember(tick) { LocalTime.now().let { it.hour + it.minute / 60.0 + it.second / 3600.0 } }
    val t = listOf(times.fajr, times.dhuhr, times.asr, times.maghrib, times.isha)
    if (t.any { it.isNaN() }) return
    val cur = t.indexOfLast { now >= it }.takeIf { it >= 0 && !(it == 0 && now >= times.sunrise) }
    val nextIdx = t.indexOfFirst { it > now }
    val nextHour = if (nextIdx >= 0) t[nextIdx] else t[0] + 24
    val left = ((nextHour - now) * 60).toInt()
    val cs = MaterialTheme.colorScheme
    com.nizam.app.ui.design.Group(Modifier.padding(bottom = 12.dp)) {
        Text(cur?.let { "${PrayerPlus.NAMES[it]} time" } ?: if (now < times.fajr || now >= times.isha) "Night" else "Between prayers",
            style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
        Text("${PrayerPlus.NAMES[if (nextIdx >= 0) nextIdx else 0]} in ${left / 60}h ${left % 60}m", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        cur?.let { i ->
            val start = if (i == 0) times.fajr else t[i]
            val end = if (i == 0) times.sunrise else nextHour
            val frac = ((now - start) / (end - start)).toFloat().coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceVariant)) {
                Box(Modifier.fillMaxWidth(frac.coerceAtLeast(0.01f)).height(6.dp).background(if (frac > 0.8f) Color(0xFFE8A13A) else cs.primary))
            }
            val mins = ((end - now) * 60).toInt()
            Text(if (statuses[i] != "NOT_PRAYED") "✓ ${PrayerPlus.NAMES[i]} logged" else "${mins / 60}h ${mins % 60}m left in ${PrayerPlus.NAMES[i]}'s time",
                style = MaterialTheme.typography.bodySmall, color = if (statuses[i] == "NOT_PRAYED" && frac > 0.8f) cs.error else cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp))
        }
        // approximate times when voluntary prayer is avoided
        Text("Avoid (approx.): sunrise ${hourToClock(times.sunrise)}–${hourToClock(times.sunrise + 0.25)} · zawal ${hourToClock(times.dhuhr - 0.1)}–${hourToClock(times.dhuhr)} · " +
            "before sunset ${hourToClock(times.maghrib - 0.25)}–${hourToClock(times.maghrib)}",
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    }
}

/** Week grid, per-prayer streaks, qada counter and jamaat times. */
@Composable
fun PrayerDepth(month: List<PrayerDay>) {
    val c = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val by = remember(month) { month.associateBy { it.localDate } }
    val today = LocalDate.now()
    com.nizam.app.ui.design.Group(Modifier.padding(top = 20.dp)) {
        Text("This week", fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.width(64.dp)) {
                Text(" ", style = MaterialTheme.typography.labelSmall)
                PrayerPlus.NAMES.forEach { Text(it, style = MaterialTheme.typography.labelSmall, modifier = Modifier.height(20.dp)) }
            }
            (6 downTo 0).forEach { off ->
                val d = today.minusDays(off.toLong())
                val st = PrayerPlus.statuses(by[d.toString()])
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(d.dayOfWeek.name.take(2).lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (off == 0) FontWeight.Bold else FontWeight.Normal)
                    st.forEach { s ->
                        Box(Modifier.height(20.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(12.dp).background(when (s) {
                                "JAMAAH" -> Color(0xFF2E9E6A); "ON_TIME" -> cs.primary; "LATE" -> Color(0xFFE8A13A); "QADA" -> cs.tertiary
                                else -> cs.surfaceVariant }, CircleShape))
                        }
                    }
                }
            }
        }
        Text("● jamaah  ● on time  ● late  ● qada", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))

        val streaks = remember(month) { PrayerPlus.streaks(month) }
        Text("Streaks", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            PrayerPlus.NAMES.forEachIndexed { i, n ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${streaks[i]}${if (streaks[i] >= 3) "🔥" else ""}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(n, style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }

        // qada: prayers not logged since tracking started, minus those you've made up
        var made by remember { mutableStateOf(PrayerPlus.madeUp(c)) }
        val from = remember { PrayerPlus.qadaStart(c) }
        val missed = remember(month) {
            month.filter { it.localDate >= from && it.localDate < today.toString() }.sumOf { d -> PrayerPlus.statuses(d).count { it == "NOT_PRAYED" } } +
                // days with no row at all count as five missed
                generateSequence(LocalDate.parse(from)) { it.plusDays(1) }.takeWhile { it.isBefore(today) }.count { by[it.toString()] == null } * 5
        }
        val owed = (missed - made).coerceAtLeast(0)
        Text("Qada to make up", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("$owed", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { PrayerPlus.addMadeUp(c, 1); made = PrayerPlus.madeUp(c); Feedback.show("Logged one qada made up") }, enabled = owed > 0) { Text("+1 made up") }
            TextButton(onClick = { PrayerPlus.addMadeUp(c, -1); made = PrayerPlus.madeUp(c) }, enabled = made > 0) { Text("Undo") }
        }
        Text("Counted from ${com.nizam.app.core.prettyDate(from)}: prayers not logged on past days. Made up so far: $made.",
            style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)

        // jamaat times
        var editing by remember { mutableStateOf<Int?>(null) }
        var tickJ by remember { mutableStateOf(0) }
        Text("My masjid's jamaat", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
        Text("Set them once; Atlas reminds you ten minutes before each.", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            PrayerPlus.KEYS.forEachIndexed { i, k ->
                val v = remember(tickJ) { PrayerPlus.jamaatHour(c, k) }
                Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable { editing = i }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(v?.let { hourToClock(it) } ?: "Set", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                        color = if (v == null) cs.primary else cs.onSurface)
                    Text(PrayerPlus.NAMES[i], style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                }
            }
        }
        editing?.let { i ->
            var text by remember(i) { mutableStateOf(PrayerPlus.jamaat(c, PrayerPlus.KEYS[i])) }
            AlertDialog(onDismissRequest = { editing = null }, title = { Text("${PrayerPlus.NAMES[i]} jamaat") },
                text = { OutlinedTextField(text, { text = it }, singleLine = true, placeholder = { Text("e.g. 1:30 pm") }) },
                confirmButton = { TextButton(onClick = {
                    if (text.isBlank() || PrayerPlus.parseClock(text) != null) { PrayerPlus.setJamaat(c, PrayerPlus.KEYS[i], text); tickJ++; editing = null }
                    else Feedback.show("Try a time like 1:30 pm")
                }) { Text("Save") } },
                dismissButton = { TextButton(onClick = { PrayerPlus.setJamaat(c, PrayerPlus.KEYS[i], ""); tickJ++; editing = null }) { Text("Clear") } })
        }
    }
}
