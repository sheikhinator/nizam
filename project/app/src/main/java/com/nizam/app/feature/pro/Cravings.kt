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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.rune.RuneChip
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId

/**
 * Cravings: ride an urge out with a 10-minute timer, then record whether it passed. The phone shows your win rate,
 * the hours and triggers that hit hardest. Replaces the Craving Log and Craving Tracker Insight (old entries are imported once).
 * A record: {id, what, t, trig, gave (bool), mins}
 */
object Cravings {
    fun store(c: Context) = JsonStore(c.applicationContext, "pro_cravings")
    const val SURF_SECONDS = 600
    fun all(c: Context): List<JSONObject> {
        val p = c.getSharedPreferences("pro", 0)
        if (!p.getBoolean("cravings_migrated", false)) {
            runCatching { JsonStore(c.applicationContext, "nxlog_cravinglog").all().forEach { o ->
                store(c).add(JSONObject().put("what", o.optString("what")).put("t", o.optLong("t", System.currentTimeMillis())).put("trig", o.optString("trig")).put("gave", o.optString("gave") == "Yes").put("mins", num(o.optString("min")))) } }
            p.edit().putBoolean("cravings_migrated", true).apply()
        }
        return store(c).all().sortedBy { it.optLong("t") }
    }
    fun hour(o: JSONObject) = Instant.ofEpochMilli(o.optLong("t")).atZone(ZoneId.systemDefault()).hour
    /** Wins in a row, counting back from the latest. */
    fun streak(list: List<JSONObject>) = list.reversed().takeWhile { !it.optBoolean("gave") }.size
    val STEPS = listOf("Breathe in for 4, out for 6. Ten times.", "Drink a full glass of water.", "Stand up and walk to another room.", "Name what set this off.", "It peaks, then it drops. You're past the worst.", "Do one small useful thing for two minutes.")
}

@Composable
fun CravingsScreen() {
    val c = LocalContext.current
    val m = MaterialTheme.colorScheme
    var list by remember { mutableStateOf(Cravings.all(c)) }
    fun reload() { list = Cravings.all(c) }
    var what by remember { mutableStateOf(list.lastOrNull()?.optString("what").orEmpty()) }
    var trig by remember { mutableStateOf("") }
    var startedAt by remember { mutableStateOf(0L) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(startedAt) { while (startedAt > 0) { now = System.currentTimeMillis(); delay(1000) } }
    val elapsed = if (startedAt > 0) ((now - startedAt) / 1000).toInt().coerceAtLeast(0) else 0
    fun finish(gave: Boolean) { Cravings.store(c).add(JSONObject().put("what", what.trim()).put("t", startedAt).put("trig", trig.trim()).put("gave", gave).put("mins", elapsed / 60.0)); startedAt = 0; trig = ""; reload() }
    val wins = list.count { !it.optBoolean("gave") }
    val topTrig = list.map { it.optString("trig").trim().lowercase() }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().maxByOrNull { it.value }
    val byHour = IntArray(24).also { a -> list.forEach { a[Cravings.hour(it)]++ } }
    val hardHour = if (list.size >= 3) byHour.indices.maxByOrNull { byHour[it] } else null
    val things = list.map { it.optString("what").trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.takeLast(5)

    AtlasScreen(title = "Cravings", subtitle = "Ride the urge out for ten minutes. Most pass.", hub = Hub.Health) {
        item("stats") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Beaten", if (list.isEmpty()) "–" else "${wins * 100 / list.size}%", m.primary, Modifier.weight(1f))
                Stat("Wins in a row", Cravings.streak(list).toString(), m.tertiary, Modifier.weight(1f))
                Stat("Hardest hour", hardHour?.let { "%02d:00".format(it) } ?: "–", m.secondary, Modifier.weight(1f))
            }
        }
        item("surf") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (startedAt == 0L) {
                    OutlinedTextField(what, { what = it }, Modifier.fillMaxWidth(), label = { Text("What is the craving? (sugar, a smoke, scrolling…)") }, singleLine = true)
                    if (things.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { things.forEach { t -> RuneChip(t, what == t) { what = t } } }
                    Button({ startedAt = System.currentTimeMillis(); now = startedAt }, Modifier.fillMaxWidth(), enabled = what.isNotBlank()) { Text("I have a craving now: start 10 minutes") }
                } else {
                    val left = (Cravings.SURF_SECONDS - elapsed).coerceAtLeast(0)
                    Text(if (left > 0) "%d:%02d".format(left / 60, left % 60) else "Ten minutes done", fontSize = 44.sp, fontWeight = FontWeight.Bold, color = m.primary)
                    LinearProgressIndicator(progress = { (elapsed.toFloat() / Cravings.SURF_SECONDS).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                    Text(Cravings.STEPS[(elapsed / 100).coerceIn(0, Cravings.STEPS.size - 1)], fontSize = 16.sp)
                    OutlinedTextField(trig, { trig = it }, Modifier.fillMaxWidth(), label = { Text("What set it off? (bored, stressed, after food…)") }, singleLine = true)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button({ finish(false) }, Modifier.weight(1f)) { Text("It passed") }
                        OutlinedButton({ finish(true) }, Modifier.weight(1f)) { Text("I gave in") }
                    }
                    Text("Giving in is logged without blame. The record is what helps next time.", fontSize = 12.sp, color = m.onSurfaceVariant)
                }
            }
        }
        if (list.size >= 3) item("pattern") {
            val bar = m.primary
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("When they hit", fontWeight = FontWeight.SemiBold)
                Canvas(Modifier.fillMaxWidth().height(70.dp)) { val mx = byHour.max().coerceAtLeast(1); val step = size.width / 24f
                    byHour.forEachIndexed { h, n -> val bh = n.toFloat() / mx * size.height; if (n > 0) drawRoundRect(bar, Offset(step * h + step * 0.15f, size.height - bh), Size(step * 0.7f, bh), CornerRadius(4f)) } }
                Text("Midnight on the left, 11 pm on the right.", fontSize = 12.sp, color = m.onSurfaceVariant)
                topTrig?.let { Text("Most common trigger: ${it.key} (${it.value} times).", fontSize = 14.sp) }
                val passed = list.filter { !it.optBoolean("gave") && it.optDouble("mins", 0.0) > 0 }
                if (passed.size >= 3) Text("When you hold out, it passes in about ${"%.0f".format(passed.map { it.optDouble("mins") }.average())} minutes.", fontSize = 14.sp)
            }
        }
        list.reversed().take(30).forEach { o ->
            item("c_" + o.optString("id")) { Card2 { Row {
                Column(Modifier.weight(1f)) {
                    Text(o.optString("what").ifBlank { "Craving" } + " · " + (if (o.optBoolean("gave")) "gave in" else "passed"), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (o.optBoolean("gave")) m.onSurface else m.primary)
                    Text(listOf(Instant.ofEpochMilli(o.optLong("t")).atZone(ZoneId.systemDefault()).let { "%s %02d:%02d".format(it.toLocalDate(), it.hour, it.minute) }, o.optString("trig")).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, color = m.onSurfaceVariant) }
                TextButton({ Cravings.store(c).remove(o.optString("id")); reload() }) { Text("✕") } } } }
        }
        item("end") { Spacer(Modifier.height(90.dp)) }
    }
}
