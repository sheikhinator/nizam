package com.nizam.app.feature.deen

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

private fun store(c: Context) = c.getSharedPreferences("deen_plus", Context.MODE_PRIVATE)
private fun list(c: Context, key: String): MutableList<JSONObject> =
    JSONArray(store(c).getString(key, "[]") ?: "[]").let { a -> (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() }
private fun save(c: Context, key: String, items: List<JSONObject>) = store(c).edit().putString(key, JSONArray(items).toString()).apply()

// ------------------------------------------------------------------ 99 Names: one a week, with practice

@Composable
fun NamesTab() {
    val c = LocalContext.current
    val week = (ChronoUnit.WEEKS.between(LocalDate.of(2024, 1, 1), LocalDate.now()) % 99).toInt()
    val focus = DeenData.NAMES[week]
    var learned by remember { mutableStateOf(store(c).getStringSet("names_learned", emptySet())!!.toSet()) }
    var open by remember { mutableStateOf<Int?>(null) }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("This week's Name · ${focus.n} of 99", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(focus.arabic, fontSize = 40.sp, modifier = Modifier.padding(vertical = 8.dp))
            Text(focus.translit, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(focus.meaning, style = MaterialTheme.typography.bodyLarge)
            Text("Call on Him by this Name in your duas this week, and look for where you see it in your life.",
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        }
    }
    Text("${learned.size} of 99 learned", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    DeenData.NAMES.forEach { n ->
        val done = n.n.toString() in learned
        Row(Modifier.fillMaxWidth().clickable { open = if (open == n.n) null else n.n }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
                Text("${n.n}", style = MaterialTheme.typography.labelMedium, color = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface)
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(n.translit, style = MaterialTheme.typography.bodyLarge)
                Text(n.meaning, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(n.arabic, fontSize = 22.sp)
        }
        if (open == n.n) Row(Modifier.padding(start = 46.dp, bottom = 6.dp)) {
            TextButton(onClick = {
                learned = if (done) learned - n.n.toString() else learned + n.n.toString()
                store(c).edit().putStringSet("names_learned", learned).apply()
            }) { Text(if (done) "Mark as not learned" else "I've memorised this Name") }
        }
    }
}

// ------------------------------------------------------------------ Sunnahs: all of them, with a daily check

@Composable
fun SunnahsTab() {
    val c = LocalContext.current
    val today = LocalDate.now().toString()
    var done by remember { mutableStateOf(store(c).getStringSet("sunnah_$today", emptySet())!!.toSet()) }
    val featured = DeenData.SUNNAHS[(LocalDate.now().toEpochDay() % DeenData.SUNNAHS.size).toInt()]
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text("Revive today", style = MaterialTheme.typography.labelMedium)
            Text(featured.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(featured.how, style = MaterialTheme.typography.bodyMedium)
        }
    }
    Text("Done today: ${done.size} of ${DeenData.SUNNAHS.size}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
    DeenData.SUNNAHS.forEach { s ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Checkbox(s.title in done, {
                done = if (it) done + s.title else done - s.title
                store(c).edit().putStringSet("sunnah_$today", done).apply()
            })
            Column(Modifier.weight(1f)) {
                Text(s.title, style = MaterialTheme.typography.bodyLarge)
                Text(s.how, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ------------------------------------------------------------------ My duas: what you've asked for, and when it was answered

@Composable
fun MyDuasTab() {
    val c = LocalContext.current
    var items by remember { mutableStateOf(list(c, "my_duas")) }
    var text by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("Waiting") }
    fun commit(n: MutableList<JSONObject>) { save(c, "my_duas", n); items = n }

    Text("Keep the things you're asking Allah for. Mark them when they're answered, and look back at how He answered.",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(top = 8.dp), placeholder = { Text("What are you asking for?") }, minLines = 2)
    Button(onClick = {
        commit((items + JSONObject().put("t", text.trim()).put("at", System.currentTimeMillis()).put("ans", 0L)).toMutableList()); text = ""
    }, enabled = text.isNotBlank(), modifier = Modifier.padding(top = 6.dp)) { Text("Add to my duas") }
    Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("Waiting", "Answered", "All").forEach { f -> FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f) }) }
    }
    val answered = items.count { it.optLong("ans") > 0 }
    Text("$answered answered of ${items.size}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 6.dp))
    items.withIndex().reversed().filter { (_, o) -> when (filter) { "Waiting" -> o.optLong("ans") == 0L; "Answered" -> o.optLong("ans") > 0; else -> true } }
        .forEach { (i, o) ->
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(o.optString("t"), style = MaterialTheme.typography.bodyLarge)
                    val since = ChronoUnit.DAYS.between(java.time.Instant.ofEpochMilli(o.optLong("at")).atZone(java.time.ZoneId.systemDefault()).toLocalDate(), LocalDate.now())
                    Text(if (o.optLong("ans") > 0) "✓ Answered · ${o.optString("how").ifBlank { "Alhamdulillah" }}" else "Asking for $since day${if (since == 1L) "" else "s"}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Row {
                        if (o.optLong("ans") == 0L) TextButton(onClick = {
                            val n = items.toMutableList(); n[i] = JSONObject(o.toString()).put("ans", System.currentTimeMillis()); commit(n)
                        }) { Text("Answered") }
                        TextButton(onClick = { val n = items.toMutableList(); n.removeAt(i); commit(n) }) { Text("Remove") }
                    }
                }
            }
        }
}

// ------------------------------------------------------------------ Deeds: a private ledger and the sadaqah jariyah that keeps counting

@Composable
fun DeedsTab() {
    val c = LocalContext.current
    var deeds by remember { mutableStateOf(list(c, "deeds")) }
    var jariyah by remember { mutableStateOf(list(c, "jariyah")) }
    var text by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var kind by remember { mutableIntStateOf(0) }
    val kinds = listOf("Sadaqah", "Kindness", "Helped someone", "Sadaqah jariyah")

    Text("Private: only you see this. A reminder to keep going, not to show.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        kinds.forEachIndexed { i, k -> FilterChip(selected = kind == i, onClick = { kind = i }, label = { Text(k) }) }
    }
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(top = 6.dp),
        placeholder = { Text(if (kind == 3) "e.g. water cooler at the masjid, a well, a Quran for a madrasa" else "What did you do?") })
    if (kind == 0 || kind == 3) OutlinedTextField(amount, { amount = it.filter { ch -> ch.isDigit() } }, Modifier.fillMaxWidth().padding(top = 6.dp), placeholder = { Text("Amount in PKR (optional)") }, singleLine = true)
    Button(onClick = {
        val o = JSONObject().put("t", text.trim()).put("k", kinds[kind]).put("pkr", amount.toLongOrNull() ?: 0L).put("at", System.currentTimeMillis())
        if (kind == 3) { jariyah = (jariyah + o).toMutableList(); save(c, "jariyah", jariyah) } else { deeds = (deeds + o).toMutableList(); save(c, "deeds", deeds) }
        text = ""; amount = ""
    }, enabled = text.isNotBlank(), modifier = Modifier.padding(top = 6.dp)) { Text("Record") }

    val month = LocalDate.now().withDayOfMonth(1)
    val thisMonth = deeds.filter { java.time.Instant.ofEpochMilli(it.optLong("at")).atZone(java.time.ZoneId.systemDefault()).toLocalDate() >= month }
    Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Stat("This month", "${thisMonth.size} deeds", Modifier.weight(1f))
        Stat("Given", "Rs ${"%,d".format(thisMonth.sumOf { it.optLong("pkr") })}", Modifier.weight(1f))
        Stat("Jariyah", "${jariyah.size} running", Modifier.weight(1f))
    }
    if (jariyah.isNotEmpty()) {
        Text("Still earning for you", style = MaterialTheme.typography.titleSmall)
        jariyah.forEach { j ->
            val days = ChronoUnit.DAYS.between(java.time.Instant.ofEpochMilli(j.optLong("at")).atZone(java.time.ZoneId.systemDefault()).toLocalDate(), LocalDate.now())
            Text("🌱 ${j.optString("t")} · $days days and counting", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp))
        }
        Spacer(Modifier.height(10.dp))
    }
    if (deeds.isNotEmpty()) Text("Recent", style = MaterialTheme.typography.titleSmall)
    deeds.takeLast(20).reversed().forEach { d ->
        Text("• ${d.optString("k")}: ${d.optString("t")}${if (d.optLong("pkr") > 0) " · Rs ${d.optLong("pkr")}" else ""}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------ Seerah: the Prophet's ﷺ life as a timeline

@Composable
fun SeerahTab() {
    var open by remember { mutableStateOf<Int?>(null) }
    Text("The life of the Prophet Muhammad ﷺ", style = MaterialTheme.typography.titleMedium)
    Spacer(Modifier.height(10.dp))
    DeenData.SEERAH.forEachIndexed { i, e ->
        Row(Modifier.fillMaxWidth().clickable { open = if (open == i) null else i }) {
            Column(Modifier.width(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(if (e.year.contains("AH")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary))
                if (i < DeenData.SEERAH.lastIndex) Box(Modifier.width(2.dp).height(if (open == i) 96.dp else 48.dp).background(MaterialTheme.colorScheme.outlineVariant))
            }
            Column(Modifier.weight(1f).padding(start = 8.dp, bottom = 8.dp)) {
                Text(e.year, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(e.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                if (open == i) Text(e.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    Text("Tap a moment to read more. Years are approximate.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
