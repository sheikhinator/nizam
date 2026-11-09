package com.nizam.app.feature.pro

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.omni.O
import com.nizam.app.feature.rune.RuneChip
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Hub
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.sqrt

/**
 * Sleep: log bed and wake times, and the phone works out hours, sleep debt, how regular you are, and which of your
 * own days go with better or worse sleep. Replaces the Sleep Debt and Sleep regularity calculators and Sleep Cause Hunt.
 * A record: {id, date (the morning you woke, yyyy-MM-dd), bed "HH:mm", wake "HH:mm", q (1-5), note}
 */
object Sleep {
    fun store(c: Context) = JsonStore(c.applicationContext, "pro_sleep")
    private fun p(c: Context) = c.getSharedPreferences("pro", 0)
    fun target(c: Context) = p(c).getFloat("sleep_target", 8f)
    fun setTarget(c: Context, v: Float) = p(c).edit().putFloat("sleep_target", v).apply()

    fun mins(s: String): Int? { val m = Regex("^(\\d{1,2})[:.](\\d{2})$").find(s.trim()) ?: return null; val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt(); return if (h in 0..23 && mi in 0..59) h * 60 + mi else null }
    fun hours(o: JSONObject): Double { val b = mins(o.optString("bed")) ?: return 0.0; val w = mins(o.optString("wake")) ?: return 0.0; return ((w - b + 1440) % 1440) / 60.0 }
    fun fmt(h: Double): String { val m = Math.round(h * 60).toInt(); return "${m / 60}h ${"%02d".format(m % 60)}m" }

    /** 0–100: how steady your bedtime is. 100 = the same time every night; each 10 minutes of spread costs about 6 points. */
    fun regularity(list: List<JSONObject>): Int? {
        val b = list.mapNotNull { mins(it.optString("bed")) }.map { ((it - 720 + 1440) % 1440).toDouble() }   // measured from noon so 23:30 and 00:30 sit next to each other
        if (b.size < 3) return null
        val mean = b.average(); val sd = sqrt(b.sumOf { (it - mean) * (it - mean) } / b.size)
        return (100 - sd * 0.6).toInt().coerceIn(0, 100)
    }

    class Link(val text: String, val diffMin: Int)

    /** Compares your sleep after days with and without something. Only reports a gap of 20+ minutes with at least 4 nights on each side. */
    suspend fun links(container: AppContainer, list: List<JSONObject>): List<Link> {
        val rows = list.takeLast(60).mapNotNull { o ->
            val d = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull() ?: return@mapNotNull null
            val h = hours(o); if (h <= 0) return@mapNotNull null
            h to runCatching { O.day(container, d.minusDays(1)) }.getOrNull()
        }.filter { it.second != null }
        val out = ArrayList<Link>()
        fun test(label: String, yes: (JSONObject) -> Boolean) {
            val a = rows.filter { yes(it.second!!) }.map { it.first }; val b = rows.filter { !yes(it.second!!) }.map { it.first }
            if (a.size < 4 || b.size < 4) return
            val diff = ((a.average() - b.average()) * 60).toInt()
            if (kotlin.math.abs(diff) >= 20) out += Link("After days $label you sleep ${kotlin.math.abs(diff)} minutes ${if (diff > 0) "more" else "less"} (${a.size} nights against ${b.size}).", diff)
        }
        val unlocks = rows.map { it.second!!.optDouble("screen_unlocks", 0.0) }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        val spend = rows.map { it.second!!.optDouble("spent", 0.0) }.sorted().let { if (it.isEmpty()) 0.0 else it[it.size / 2] }
        test("you trained") { it.optInt("gym_sets") > 0 }
        test("with all five prayers") { it.optInt("prayers") >= 5 }
        test("you prayed Fajr") { it.optInt("fajr") > 0 }
        if (unlocks > 0) test("with heavy phone use") { it.optDouble("screen_unlocks", 0.0) > unlocks }
        if (spend > 0) test("you spent more than usual") { it.optDouble("spent", 0.0) > spend }
        test("you finished 3 or more tasks") { it.optInt("tasks_done") >= 3 }
        test("you wrote in your diary") { it.optInt("diary_words") > 0 }
        return out.sortedByDescending { kotlin.math.abs(it.diffMin) }
    }
}

@Composable
fun SleepScreen(container: AppContainer) {
    val c = LocalContext.current
    val m = MaterialTheme.colorScheme
    var list by remember { mutableStateOf(Sleep.store(c).all().sortedBy { it.optString("date") }) }
    fun reload() { list = Sleep.store(c).all().sortedBy { it.optString("date") } }
    var bed by remember { mutableStateOf(list.lastOrNull()?.optString("bed") ?: "23:00") }
    var wake by remember { mutableStateOf(list.lastOrNull()?.optString("wake") ?: "07:00") }
    var q by remember { mutableStateOf(3) }
    var note by remember { mutableStateOf("") }
    var target by remember { mutableStateOf(Sleep.target(c)) }
    var links by remember { mutableStateOf<List<Sleep.Link>?>(null) }
    LaunchedEffect(list.size) { links = runCatching { Sleep.links(container, list) }.getOrDefault(emptyList()) }
    val today = LocalDate.now().toString()
    val week = list.takeLast(7); val valid = Sleep.mins(bed) != null && Sleep.mins(wake) != null
    val avg = if (week.isEmpty()) null else week.map { Sleep.hours(it) }.average()
    val debt = week.sumOf { (target - Sleep.hours(it)).coerceAtLeast(-2.0) }
    val reg = Sleep.regularity(list.takeLast(14))

    AtlasScreen(title = "Sleep", subtitle = "Hours, debt, how regular you are, and what in your own days changes it.", hub = Hub.Health) {
        item("stats") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("7-night average", avg?.let { Sleep.fmt(it) } ?: "–", m.primary, Modifier.weight(1.3f))
                Stat(if (debt >= 0) "Sleep debt" else "Ahead by", if (week.isEmpty()) "–" else Sleep.fmt(kotlin.math.abs(debt)), if (debt > 3) m.error else m.tertiary, Modifier.weight(1.1f))
                Stat("Regularity", reg?.let { "$it/100" } ?: "–", m.secondary, Modifier.weight(1f))
            }
        }
        if (list.size >= 2) item("chart") {
            val bars = list.takeLast(14); val prim = m.primary; val low = m.error; val line = m.onSurfaceVariant
            Column(Modifier.padding(16.dp)) {
                Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                    val mx = maxOf(10.0, bars.maxOf { Sleep.hours(it) }); val step = size.width / bars.size; val bw = step * 0.62f
                    bars.forEachIndexed { i, o -> val h = Sleep.hours(o); val bh = (h / mx * size.height).toFloat()
                        drawRoundRect(if (h < target - 1) low else prim, Offset(step * i + (step - bw) / 2f, size.height - bh), Size(bw, bh), CornerRadius(bw / 3f)) }
                    val ty = size.height - (target / mx * size.height).toFloat()
                    drawLine(line, Offset(0f, ty), Offset(size.width, ty), 2f)
                }
                Text("Last ${bars.size} nights. The line is your ${"%.1f".format(target)}-hour target; red nights fell an hour or more short.", fontSize = 12.sp, color = m.onSurfaceVariant)
            }
        }
        item("add") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Last night", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(bed, { bed = it }, Modifier.weight(1f), label = { Text("Slept at (23:30)") }, singleLine = true, isError = Sleep.mins(bed) == null)
                    OutlinedTextField(wake, { wake = it }, Modifier.weight(1f), label = { Text("Woke at (07:00)") }, singleLine = true, isError = Sleep.mins(wake) == null)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Awful", "Poor", "OK", "Good", "Great").forEachIndexed { i, l -> RuneChip(l, q == i + 1) { q = i + 1 } } }
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note (optional): late tea, noise, nap…") }, singleLine = true)
                if (valid) Text("That is " + Sleep.fmt(((Sleep.mins(wake)!! - Sleep.mins(bed)!! + 1440) % 1440) / 60.0) + ".", fontSize = 13.sp, color = m.primary)
                Button({
                    val rec = JSONObject().put("date", today).put("bed", bed.trim()).put("wake", wake.trim()).put("q", q).put("note", note.trim())
                    val old = Sleep.store(c).all().firstOrNull { it.optString("date") == today }
                    if (old != null) Sleep.store(c).update(old.optString("id")) { it.put("bed", bed.trim()).put("wake", wake.trim()).put("q", q).put("note", note.trim()) } else Sleep.store(c).add(rec)
                    note = ""; reload()
                }, enabled = valid) { Text(if (list.any { it.optString("date") == today }) "Update last night" else "Save last night") }
                Text("Target: ${"%.1f".format(target)} hours", fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(6f, 7f, 7.5f, 8f, 9f).forEach { t -> RuneChip(if (t % 1f == 0f) "${t.toInt()}h" else "${t}h", target == t) { target = t; Sleep.setTarget(c, t) } } }
            }
        }
        item("links") {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("What changes your sleep", fontWeight = FontWeight.SemiBold)
                val l = links
                when {
                    l == null -> Text("Looking…", color = m.onSurfaceVariant, fontSize = 13.sp)
                    l.isEmpty() -> Text("Nothing clear yet. This compares your sleep against your own days in Atlas (training, prayers, phone use, spending, tasks, diary) and only speaks up when there are at least 4 nights on each side and a gap of 20 minutes or more.", color = m.onSurfaceVariant, fontSize = 13.sp)
                    else -> { l.forEach { Text("• " + it.text, fontSize = 14.sp) }; Text("These go together in your data. That doesn't prove one causes the other.", fontSize = 12.sp, color = m.onSurfaceVariant) }
                }
            }
        }
        if (list.isNotEmpty()) item("hh") { Text("Nights", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold) }
        list.reversed().take(30).forEach { o ->
            item("n_" + o.optString("id")) { Card2 {
                Row { Column(Modifier.weight(1f)) {
                    Text(o.optString("date") + " · " + Sleep.fmt(Sleep.hours(o)), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(listOf(o.optString("bed") + " to " + o.optString("wake"), listOf("", "Awful", "Poor", "OK", "Good", "Great").getOrElse(o.optInt("q")) { "" }, o.optString("note")).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = m.onSurfaceVariant) }
                    TextButton({ Sleep.store(c).remove(o.optString("id")); reload() }) { Text("✕") } }
            } }
        }
        item("end") { Spacer(Modifier.height(90.dp)) }
    }
}
