package com.nizam.app.feature.nx

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** "Asr + 40", "before Maghrib", "after Isha": times anchored to today's prayers. */
object PrayerClock {
    private val NAMES = mapOf("fajr" to "Fajr", "sunrise" to "Sunrise", "zuhr" to "Zuhr", "dhuhr" to "Zuhr", "zohar" to "Zuhr", "asr" to "Asr",
        "maghrib" to "Maghrib", "isha" to "Isha", "esha" to "Isha")

    suspend fun prayers(container: AppContainer, date: LocalDate = LocalDate.now()): Map<String, Int> =
        PlanEngine.prayers(container, date).associate { it.label to it.startMin }

    /** Minutes after midnight, or null if the phrase doesn't name a prayer. */
    fun parse(text: String, times: Map<String, Int>): Int? {
        val t = text.lowercase().trim()
        val name = NAMES.entries.firstOrNull { t.contains(it.key) }?.value ?: return null
        val base = times[name] ?: return null
        val n = Regex("(\\d+)\\s*(h|hr|hour|hours|m|min|mins|minutes)?").find(t.substringAfter(NAMES.entries.first { t.contains(it.key) }.key))
        val amount = n?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val mins = if (n?.groupValues?.get(2)?.startsWith("h") == true) amount * 60 else amount
        return when {
            t.startsWith("before") -> base - (if (mins > 0) mins else 15)
            t.contains("-") -> base - mins
            t.startsWith("after") -> base + (if (mins > 0) mins else 20)
            else -> base + mins
        }.coerceIn(0, 24 * 60 - 1)
    }

    fun hm(m: Int): String { val n = ((m % 1440) + 1440) % 1440; return LocalTime.of(n / 60, n % 60).toString() }
}

object Mega6 {
    private fun today() = LocalDay.today().toString()

    val all: List<Spec> by lazy { listOf(
        ScreenSpec("unlockintent", "Why did I open my phone?", "Say what you meant to do, then see what you actually did", Icons.Outlined.PhonelinkLock, Hub.Tools,
            "unlock intent journal mindful phone use why open") { c, open -> UnlockIntent(open) },
        ScreenSpec("habitgenome", "Habit genome", "Which of your habits come before your best days", Icons.Outlined.Biotech, Hub.Health,
            "habit genome correlation best days patterns experiment") { c, open -> HabitGenome() },
        ScreenSpec("prayerclock", "Prayer-anchored time", "Plan with \"Asr + 40\" or \"before Maghrib\"", Icons.Outlined.Schedule, Hub.Deen,
            "prayer anchored time asr maghrib reminder schedule") { c, open -> PrayerClockScreen(c) },
        ScreenSpec("paperatlas", "Paper Atlas", "A printable weekly sheet for your tasks and habits", Icons.Outlined.Print, Hub.Work,
            "paper printable weekly sheet tick boxes offline planner") { c, open -> PaperAtlas(c) },
        ScreenSpec("effortreceipt", "Proof-of-effort receipts", "Signed receipts of habits kept, for an accountability partner", Icons.Outlined.Verified, Hub.Social,
            "proof effort receipt accountability partner streak signed") { c, open -> EffortReceipts() },
        ScreenSpec("attnbudget", "Attention budget", "Distracting apps spend it, focus earns it back", Icons.Outlined.Savings, Hub.Tools,
            "attention budget focus distraction screen time earn spend") { c, open -> AttentionBudget(open) },
    ) }
}

@Composable
private fun Sub(t: String) = Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))

@Composable
private fun UnlockIntent(open: (String) -> Unit) {
    val c = LocalContext.current
    val store = remember { JsonStore(c, "nx_unlockintent") }
    var list by remember { mutableStateOf(store.all()) }
    var custom by remember { mutableStateOf("") }
    val day = LocalDay.today(c).toString()
    fun add(w: String) { store.add(JSONObject().put("d", day).put("t", System.currentTimeMillis()).put("w", w)); list = store.all() }
    val used = remember { if (Allowances.granted(c)) Allowances.usedToday(c) else emptyMap() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("One tap, no judgement. At night Atlas puts what you meant beside what you did.", style = MaterialTheme.typography.bodyMedium)
        listOf("Reply to someone", "Check the time", "Look something up", "Pay / money", "Work", "Bored", "Habit, no reason").chunked(3).forEach { row ->
            Row { row.forEach { w -> TextButton(onClick = { add(w) }) { Text(w) } } } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(custom, { custom = it }, label = { Text("Something else") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (custom.isNotBlank()) { add(custom.trim()); custom = "" } }) { Text("Save") }
        }
        val todays = list.filter { it.optString("d") == day }
        Sub("Today: what you meant")
        todays.groupBy { it.optString("w") }.entries.sortedByDescending { it.value.size }.forEach { (w, e) -> Text("${e.size}× $w") }
        if (todays.isEmpty()) Text("Nothing yet today.", style = MaterialTheme.typography.bodySmall)
        Sub("Today: what you did")
        if (used.isEmpty()) TextButton(onClick = { open("nx/allowances") }) { Text("Allow usage access to compare") }
        else used.filter { it.value > 0 && it.key != c.packageName }.entries.sortedByDescending { it.value }.take(6).forEach { (p, m) -> Text("${Allowances.label(c, p)} · $m min") }
        val idle = todays.count { it.optString("w") in setOf("Bored", "Habit, no reason") }
        if (todays.size >= 3) Text(if (idle * 2 > todays.size) "More than half of today's opens had no purpose. Try leaving the phone in another room for one hour."
            else "Most opens today had a purpose — nice.", style = MaterialTheme.typography.bodySmall)
        val week = list.filter { runCatching { LocalDate.parse(it.optString("d")) }.getOrNull()?.isAfter(LocalDate.now().minusDays(7)) == true }
        if (week.isNotEmpty()) Text("This week: ${week.size} opens logged · ${week.count { it.optString("w") in setOf("Bored", "Habit, no reason") }} without a reason",
            style = MaterialTheme.typography.bodySmall)
    }
}

/** Every log on the phone becomes a daily yes/no signal; one chosen number is the "good day" score. */
@Composable
private fun HabitGenome() {
    val c = LocalContext.current
    val logs = remember { c.filesDir.listFiles().orEmpty().map { it.name }.filter { it.startsWith("nxlog_") && it.endsWith(".json") }.map { it.removePrefix("nxlog_").removeSuffix(".json") } }
    val titles = remember { Nx.all.associate { it.id to it.title } }
    var outcome by remember { mutableStateOf<String?>(null) }
    var field by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<List<String>>(emptyList()) }
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    fun dateOf(o: JSONObject): String? = listOf("d", "date", "day").map { o.optString(it) }.firstOrNull { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
        ?: o.optString("id").take(13).toLongOrNull()?.let { java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Pick the log that measures a good day (mood, energy, readiness…) and the number in it. Atlas checks which other logs tend to appear the day before or on your best days.", style = MaterialTheme.typography.bodyMedium)
        if (logs.size < 2) { Text("Keep at least two logs for a couple of weeks first (for example Mood and Sleep).", style = MaterialTheme.typography.bodySmall); return@Column }
        Sub("Good-day log")
        logs.chunked(3).forEach { r -> Row { r.forEach { id -> TextButton(onClick = { outcome = id; field = null; result = emptyList() }) {
            Text(titles[id] ?: id, fontWeight = if (outcome == id) FontWeight.Bold else FontWeight.Normal) } } } }
        val sample = remember(outcome) { outcome?.let { JsonStore(c, "nxlog_$it", sealed = false).all() }.orEmpty() }
        val numeric = remember(sample) { sample.flatMap { o -> o.keys().asSequence().filter { k -> k != "id" && o.optString(k).toDoubleOrNull() != null }.toList() }.distinct() }
        if (outcome != null) {
            Sub("Number that means \"good\"")
            if (numeric.isEmpty()) Text("This log has no numbers (or is sealed). Pick another.", style = MaterialTheme.typography.bodySmall)
            Row { numeric.take(6).forEach { k -> TextButton(onClick = { field = k }) { Text(k, fontWeight = if (field == k) FontWeight.Bold else FontWeight.Normal) } } }
        }
        if (outcome != null && field != null) TextButton(onClick = { scope.launch { result = withContext(Dispatchers.IO) {
            val score = sample.mapNotNull { o -> dateOf(o)?.let { d -> d to (o.optString(field!!).toDoubleOrNull() ?: return@mapNotNull null) } }
                .groupBy({ it.first }, { it.second }).mapValues { it.value.average() }
            if (score.size < 7) return@withContext listOf("Only ${score.size} scored days — keep logging for a week or two.")
            val avg = score.values.average()
            logs.filter { it != outcome }.mapNotNull { id ->
                val days = runCatching { JsonStore(c, "nxlog_$id").all() }.getOrDefault(emptyList()).mapNotNull { dateOf(it) }.toSet()
                if (days.isEmpty()) return@mapNotNull null
                val withIt = score.filter { (d, _) -> d in days || runCatching { LocalDate.parse(d).minusDays(1).toString() in days }.getOrDefault(false) }
                val without = score - withIt.keys
                if (withIt.size < 3 || without.size < 3) return@mapNotNull null
                val lift = withIt.values.average() - without.values.average()
                val conf = when (minOf(withIt.size, without.size)) { in 3..5 -> "low"; in 6..14 -> "medium"; else -> "good" }
                Triple(titles[id] ?: id, lift, "${withIt.size} vs ${without.size} days, $conf confidence")
            }.sortedByDescending { kotlin.math.abs(it.second) }.take(8).map { (n, l, why) ->
                "${if (l >= 0) "▲" else "▼"} $n: ${"%+.1f".format(l)} on ${field} (avg ${"%.1f".format(avg)}) · $why"
            }.ifEmpty { listOf("No clear pattern yet. More days of logging will help.") } +
                "Try an experiment: do the top ▲ habit for 7 days in a row and watch your average."
        } } }) { Text("Find my patterns") }
        result.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun PrayerClockScreen(container: AppContainer) {
    val c = LocalContext.current
    var times by remember { mutableStateOf(emptyMap<String, Int>()) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { times = PrayerClock.prayers(container) } }
    var phrase by remember { mutableStateOf("Asr + 40") }
    var what by remember { mutableStateOf("") }
    val at = PrayerClock.parse(phrase, times)
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (times.isNotEmpty()) Text(times.entries.joinToString(" · ") { "${it.key} ${PrayerClock.hm(it.value)}" }, style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(phrase, { phrase = it }, label = { Text("When (e.g. after Fajr, Asr + 40, before Maghrib, Isha + 1h)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(what, { what = it }, label = { Text("What") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text(at?.let { "→ today at ${PrayerClock.hm(it)}" } ?: "Name a prayer: Fajr, Zuhr, Asr, Maghrib or Isha.", fontWeight = FontWeight.SemiBold)
        if (at != null) Row {
            TextButton(onClick = { runCatching { c.startActivity(Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, at / 60).putExtra(AlarmClock.EXTRA_MINUTES, at % 60)
                .putExtra(AlarmClock.EXTRA_MESSAGE, what.ifBlank { phrase }).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Set alarm") }
            TextButton(onClick = { scope.launch {
                val due = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + at * 60_000L
                container.taskRepository.add("${what.ifBlank { "Task" }} ($phrase)", due, 1)
                com.nizam.app.ui.components.Feedback.show("Added for ${PrayerClock.hm(at)}") } }) { Text("Add as task") }
        }
    }
}

@Composable
private fun PaperAtlas(container: AppContainer) {
    val c = LocalContext.current; val scope = com.nizam.app.ui.design.rememberSafeScope()
    var habits by remember { mutableStateOf("Fajr on time, Quran 1 page, Walk 20 min, No phone after 11") }
    var status by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("A one-page sheet for the fridge or your desk: this week's open tasks with boxes, and a 7-day grid for your habits. Tick by pen, then mark the same ticks here in Atlas.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(habits, { habits = it }, label = { Text("Habits (comma separated)") }, modifier = Modifier.fillMaxWidth())
        TextButton(onClick = { scope.launch {
            status = runCatching { withContext(Dispatchers.IO) {
                val end = System.currentTimeMillis() + 7 * 86_400_000L
                val tasks = container.taskRepository.openThrough(end).first().take(25)
                val start = LocalDay.today(c); val days = (0..6).map { start.plusDays(it.toLong()).dayOfWeek.name.take(3) }
                val md = buildString {
                    append("## Tasks this week\n\n"); tasks.forEach { append("☐  ${it.title}\n\n") }
                    if (tasks.isEmpty()) append("☐  \n\n☐  \n\n☐  \n\n")
                    append("## Habits\n\n"); append("Habit  |  ${days.joinToString("  ")}\n\n")
                    habits.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEach { h -> append("$h  |  ${days.joinToString("  ") { "☐" }}\n\n") }
                    append("## Notes\n\n\n\n\n")
                }
                val out = File(c.cacheDir, "paper-atlas-${start}.pdf")
                com.nizam.app.feature.studio.DocPdf.render(out, "Week of $start", md, cover = false)
                Share.file(c, out, "application/pdf"); "Ready — print it or save it."
            } }.getOrElse { "Couldn't make the sheet: ${it.message}" }
        } }) { Text("Make this week's sheet") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

/** Receipts are HMAC-signed with a phrase you share with your partner; they can check one without seeing your logs. */
object EffortReceipt {
    fun sign(secret: String, body: String): String {
        val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
    }
    fun make(secret: String, habit: String, days: Int, from: String, to: String): String {
        val body = "ATLAS RECEIPT|$habit|$days days|$from..$to"
        return "$body|${sign(secret, body)}"
    }
    fun check(secret: String, receipt: String): Boolean {
        val i = receipt.trim().lastIndexOf('|'); if (i < 0) return false
        return sign(secret, receipt.trim().substring(0, i)) == receipt.trim().substring(i + 1)
    }
}

@Composable
private fun EffortReceipts() {
    val c = LocalContext.current
    var secret by remember { mutableStateOf(c.getSharedPreferences("effort", 0).getString("s", "").orEmpty()) }
    var habit by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("7") }
    var receipt by remember { mutableStateOf("") }
    var verify by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Agree a shared phrase with your partner once. Your receipt proves the streak came from someone who knows the phrase — no logs, photos or data are shared.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(secret, { secret = it; c.getSharedPreferences("effort", 0).edit().putString("s", it).apply() }, label = { Text("Shared phrase") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(habit, { habit = it }, label = { Text("Habit or promise") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(days, { days = it.filter(Char::isDigit) }, label = { Text("Days kept") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row {
            TextButton(onClick = { val n = days.toIntOrNull() ?: 0; val to = LocalDay.today(c)
                receipt = EffortReceipt.make(secret, habit.trim(), n, to.minusDays((n - 1).coerceAtLeast(0).toLong()).toString(), to.toString()) },
                enabled = secret.length >= 6 && habit.isNotBlank()) { Text("Make receipt") }
            if (receipt.isNotBlank()) TextButton(onClick = { Share.text(c, receipt, "Effort receipt") }) { Text("Send") }
        }
        if (secret.isNotBlank() && secret.length < 6) Text("Use at least 6 characters.", style = MaterialTheme.typography.bodySmall)
        if (receipt.isNotBlank()) Text(receipt, style = MaterialTheme.typography.bodySmall)
        Sub("Check a partner's receipt")
        OutlinedTextField(verify, { verify = it }, label = { Text("Paste receipt") }, modifier = Modifier.fillMaxWidth())
        if (verify.isNotBlank()) Text(if (EffortReceipt.check(secret, verify)) "✅ Genuine — made with your shared phrase" else "❌ Doesn't match your phrase (edited or a different phrase)")
    }
}

/** Daily attention allowance: minutes in apps you mark as distracting spend it; focus sessions logged here earn it back. */
object Attention {
    private fun p(c: Context) = c.getSharedPreferences("attention_budget", 0)
    fun budget(c: Context) = p(c).getInt("budget", 90)
    fun setBudget(c: Context, m: Int) = p(c).edit().putInt("budget", m.coerceIn(15, 600)).apply()
    fun distracting(c: Context): Set<String> = p(c).getStringSet("apps", emptySet()).orEmpty()
    fun toggle(c: Context, pkg: String) = p(c).edit().putStringSet("apps", distracting(c).let { if (pkg in it) it - pkg else it + pkg }).apply()
    fun earned(c: Context): Int = if (p(c).getString("day", "") == LocalDate.now().toString()) p(c).getInt("earned", 0) else 0
    fun earn(c: Context, focusMin: Int) = p(c).edit().putString("day", LocalDate.now().toString()).putInt("earned", earned(c) + focusMin / 2).apply()
    fun left(c: Context, used: Map<String, Int>) = budget(c) + earned(c) - distracting(c).sumOf { used[it] ?: 0 }
}

@Composable
private fun AttentionBudget(open: (String) -> Unit) {
    val c = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val used = remember(tick) { if (Allowances.granted(c)) Allowances.usedToday(c) else emptyMap() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (!Allowances.granted(c)) { TextButton(onClick = { open("nx/allowances") }) { Text("Allow usage access first") }; return@Column }
        val budget = Attention.budget(c); val earned = Attention.earned(c); val left = Attention.left(c, used)
        Text("${maxOf(0, left)} min left", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        LinearProgressIndicator(progress = { (left.coerceAtLeast(0).toFloat() / (budget + earned).coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        Text("Budget $budget · earned +$earned from focus · spent ${budget + earned - left}", style = MaterialTheme.typography.bodySmall)
        if (left < 0) Text("Overdrawn by ${-left} min. A 30-minute focus session earns 15 back.", style = MaterialTheme.typography.bodyMedium)
        Row {
            TextButton(onClick = { Attention.setBudget(c, budget - 15); tick++ }) { Text("Budget −15") }
            TextButton(onClick = { Attention.setBudget(c, budget + 15); tick++ }) { Text("Budget +15") }
            TextButton(onClick = { Attention.earn(c, 30); tick++ }) { Text("I focused 30 min") }
        }
        Sub("Which apps spend it")
        used.filter { it.value > 0 && it.key != c.packageName }.entries.sortedByDescending { it.value }.take(14).forEach { (pkg, m) ->
            val on = pkg in Attention.distracting(c)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${if (on) "💸" else "·"} ${Allowances.label(c, pkg)} · $m min", Modifier.weight(1f))
                TextButton(onClick = { Attention.toggle(c, pkg); tick++ }) { Text(if (on) "Doesn't count" else "Counts") }
            }
        }
    }
}
