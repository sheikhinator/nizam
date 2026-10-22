package com.nizam.app.feature.next

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import com.nizam.app.AppContainer
import com.nizam.app.feature.prayer.PrayerTimes
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Segmented
import com.nizam.app.ui.design.Space
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * "When are you free?" answered for you: your calendar's busy times and the five prayers are
 * left out, and the open windows go into a message ready to send.
 */
object FreeTimes {
    data class Slot(val start: LocalDateTime, val end: LocalDateTime)

    private fun busy(c: Context, from: LocalDateTime, to: LocalDateTime): List<Slot> {
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return emptyList()
        val z = ZoneId.systemDefault()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
            android.content.ContentUris.appendId(it, from.atZone(z).toInstant().toEpochMilli())
            android.content.ContentUris.appendId(it, to.atZone(z).toInstant().toEpochMilli())
        }.build()
        val out = mutableListOf<Slot>()
        runCatching {
            c.contentResolver.query(uri, arrayOf(CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY),
                null, null, null)?.use { cur ->
                while (cur.moveToNext()) {
                    if (cur.getInt(2) == 1) continue
                    out += Slot(java.time.Instant.ofEpochMilli(cur.getLong(0)).atZone(z).toLocalDateTime(),
                        java.time.Instant.ofEpochMilli(cur.getLong(1)).atZone(z).toLocalDateTime())
                }
            }
        }
        return out
    }

    /** Free windows of at least [minutes] between 9 am and 9 pm over the next [days] days. */
    suspend fun find(c: Context, container: AppContainer, days: Int, minutes: Long): List<Slot> {
        val s = container.settingsRepository
        val lat = s.latitude.first(); val lng = s.longitude.first(); val hanafi = s.hanafiAsr.first()
        val method = runCatching { PrayerTimes.Method.valueOf(s.prayerMethod.first()) }.getOrElse { PrayerTimes.Method.KARACHI }
        val now = LocalDateTime.now()
        val out = mutableListOf<Slot>()
        for (d in 0 until days) {
            val date = LocalDate.now().plusDays(d.toLong())
            val dayStart = maxOf(date.atTime(9, 0), if (d == 0) now.plusMinutes(30).withSecond(0).withNano(0) else date.atTime(9, 0))
            val dayEnd = date.atTime(21, 0)
            if (!dayStart.isBefore(dayEnd)) continue
            val t = PrayerTimes.calculate(date, lat, lng, method, hanafi)
            val blocks = busy(c, date.atStartOfDay(), date.plusDays(1).atStartOfDay()).toMutableList()
            listOf(t.dhuhr, t.asr, t.maghrib, t.isha).filter { !it.isNaN() }.forEach { h ->
                val at = date.atStartOfDay().plusMinutes((h * 60).toLong())
                blocks += Slot(at.minusMinutes(10), at.plusMinutes(30))
            }
            if (date.dayOfWeek == java.time.DayOfWeek.FRIDAY && !t.dhuhr.isNaN())
                date.atStartOfDay().plusMinutes((t.dhuhr * 60).toLong()).let { blocks += Slot(it.minusMinutes(30), it.plusMinutes(60)) }
            var cursor = dayStart
            blocks.sortedBy { it.start }.forEach { b ->
                if (b.end.isBefore(cursor)) return@forEach
                if (java.time.Duration.between(cursor, b.start).toMinutes() >= minutes && b.start.isBefore(dayEnd))
                    out += Slot(cursor, minOf(b.start, dayEnd))
                if (b.end.isAfter(cursor)) cursor = b.end
            }
            if (java.time.Duration.between(cursor, dayEnd).toMinutes() >= minutes) out += Slot(cursor, dayEnd)
        }
        return out
    }

    fun message(slots: List<Slot>): String {
        val day = DateTimeFormatter.ofPattern("EEE d MMM")
        val time = DateTimeFormatter.ofPattern("h:mm a")
        return "I'm free:\n" + slots.take(6).joinToString("\n") { "• ${it.start.format(day)}, ${it.start.format(time)} – ${it.end.format(time)}" } +
            "\nWhich works for you?"
    }
}

@Composable
fun FreeTimesScreen(container: AppContainer) {
    val ctx = LocalContext.current
    var days by remember { mutableStateOf("3") }
    var len by remember { mutableStateOf("60") }
    var slots by remember { mutableStateOf<List<FreeTimes.Slot>?>(null) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { slots = null }
    LaunchedEffect(days, len, slots == null) { com.nizam.app.ui.design.guard {
        if (slots == null) slots = FreeTimes.find(ctx, container, days.toInt(), len.toLong())
    } }
    AtlasScreen(title = "Share free times", subtitle = "Your calendar and prayer times are kept clear") {
        item("opts") {
            Column(Modifier.padding(horizontal = Space.page)) {
                Text("Over the next", style = MaterialTheme.typography.labelMedium)
                Segmented(listOf("1", "3", "7"), days, { days = it; slots = null }, { if (it == "1") "Today" else "$it days" })
                Text("For at least", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = Space.s))
                Segmented(listOf("30", "60", "120"), len, { len = it; slots = null }, { if (it == "120") "2 hours" else "$it min" })
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
                    OutlinedButton(onClick = { perm.launch(Manifest.permission.READ_CALENDAR) }, modifier = Modifier.padding(top = Space.s)) {
                        Text("Include my calendar") }
            }
        }
        val found = slots
        if (found != null) {
            item("h") { SectionHeader(if (found.isEmpty()) "No free windows" else "${found.size} free windows") }
            if (found.isNotEmpty()) item("msg") {
                val msg = FreeTimes.message(found)
                Group(Modifier.padding(horizontal = Space.page)) {
                    SelectionContainer { Text(msg, style = MaterialTheme.typography.bodyLarge) }
                    Row(Modifier.padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Button(onClick = {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, msg), "Send"))
                        }) { Text("Send…") }
                    }
                }
            }
        }
    }
}
