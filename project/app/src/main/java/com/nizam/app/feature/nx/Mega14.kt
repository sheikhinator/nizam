package com.nizam.app.feature.nx

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.os.PowerManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.core.content.ContextCompat
import com.nizam.app.AppContainer
import com.nizam.app.feature.finance.Txn
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.SoundFx
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.random.Random

/** Reminders tied to a place. They use the phone's last known location, only when you've allowed location, and never record a trail. */
object PlaceReminders {
    private fun store(c: Context) = JsonStore(c, "nx_placereminders")
    fun all(c: Context) = store(c).all()
    fun add(c: Context, name: String, text: String, lat: Double, lng: Double, radius: Int) =
        store(c).add(JSONObject().put("name", name).put("text", text).put("lat", lat).put("lng", lng).put("r", radius).put("last", ""))
    fun remove(c: Context, id: String) = store(c).remove(id)
    fun allowed(c: Context) = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun here(c: Context): Location? {
        if (!allowed(c)) return null
        com.nizam.app.feature.platform2.PermLog.note(c, "Location", "place reminder check")
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.filter { System.currentTimeMillis() - it.time < 30 * 60_000L }.maxByOrNull { it.time }
    }

    fun tick(c: Context) {
        val list = all(c); if (list.isEmpty()) return
        val loc = here(c) ?: return
        list.forEach { o ->
            val r = FloatArray(1); Location.distanceBetween(loc.latitude, loc.longitude, o.optDouble("lat"), o.optDouble("lng"), r)
            if (r[0] <= o.optInt("r", 150) && o.optString("last") != LocalDate.now().toString()) {
                store(c).update(o.optString("id")) { it.put("last", LocalDate.now().toString()) }
                Notifications.post(c, Notifications.CHANNEL_NUDGE, 7700 + (o.optString("id").hashCode() and 0xff), "📍 ${o.optString("name")}", o.optString("text"))
            }
        }
    }
}

/** 20-20-20 eye breaks: while the screen is on in the daytime, an occasional nudge to look away. Quiet in Worship, Focus and Night. */
object EyeBreaks {
    private fun p(c: Context) = c.getSharedPreferences("eyebreaks", 0)
    fun on(c: Context) = p(c).getBoolean("on", false)
    fun set(c: Context, v: Boolean) = p(c).edit().putBoolean("on", v).apply()
    private val TIPS = listOf("Look at something 20 feet away for 20 seconds 👀", "Blink slowly ten times, then roll your shoulders back", "Stand up and stretch your neck and wrists for 30 seconds", "Sit tall: feet flat, screen at eye level, shoulders relaxed")
    fun tick(c: Context) {
        if (!on(c)) return
        val hour = java.time.LocalTime.now().hour
        if (hour !in 8..21 || com.nizam.app.feature.modes.AutoModes.isQuiet(c)) return
        if (!(c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive) return
        val last = p(c).getLong("last", 0L); if (System.currentTimeMillis() - last < 45 * 60_000L) return
        val n = p(c).getInt("n", 0); p(c).edit().putLong("last", System.currentTimeMillis()).putInt("n", n + 1).apply()
        Notifications.post(c, Notifications.CHANNEL_NUDGE, 7710, "Quick break", TIPS[n % TIPS.size])
    }
}

/** Statement lines → transactions, for CSV exports and (via on-device OCR) PDF or photo statements. */
object Statements {
    data class Line(val date: LocalDate, val desc: String, val amount: Double, val income: Boolean, var category: String, var take: Boolean = true, var dup: Boolean = false)
    private val FORMATS = listOf("yyyy-MM-dd", "dd/MM/yyyy", "dd-MM-yyyy", "d/M/yyyy", "d-M-yyyy", "dd-MMM-yyyy", "dd MMM yyyy", "d MMM yyyy", "dd/MM/yy", "dd-MMM-yy", "MM/dd/yyyy")
        .map { DateTimeFormatter.ofPattern(it, Locale.ENGLISH) }
    fun date(s: String): LocalDate? { val t = s.trim().replace(Regex("\\s+"), " "); return FORMATS.firstNotNullOfOrNull { f -> runCatching { LocalDate.parse(t, f) }.getOrNull() } }
    private val CATS = listOf("Groceries" to "imtiaz|carrefour|metro|naheed|al-fatah|grocery|kiryana|utility store", "Food" to "kfc|mcdonald|pizza|foodpanda|restaurant|cafe|burger|biryani|hardee|dominos|cheezious",
        "Transport" to "careem|uber|bykea|indrive|petrol|pso|shell|attock|total parco|toll|motorway", "Bills" to "k-electric|kelectric|lesco|iesco|fesco|mepco|sngpl|ssgc|sui|ptcl|nayatel|stormfiber|jazz|zong|telenor|ufone|wapda",
        "Shopping" to "daraz|khaadi|outfitters|sapphire|gul ahmed|alkaram|bata|servis|breakout", "Health" to "pharmacy|hospital|clinic|chughtai|shaukat|aga khan|medicine|doctor|lab", "Education" to "school|university|tuition|academy|college|fee",
        "Transfer" to "ibft|raast|funds transfer|transfer|1link|atm")
    fun categorize(d: String): String = CATS.firstOrNull { Regex(it.second, RegexOption.IGNORE_CASE).containsMatchIn(d) }?.first ?: "Other"
    private val INCOME = Regex("salary|credit|deposit|received|refund|cashback|profit|remittance|inward", RegexOption.IGNORE_CASE)

    private fun splitCsv(line: String): List<String> { val out = mutableListOf<String>(); val sb = StringBuilder(); var q = false
        for (ch in line) when { ch == '"' -> q = !q; ch == ',' && !q -> { out += sb.toString().trim(); sb.clear() }; else -> sb.append(ch) }
        out += sb.toString().trim(); return out }
    private fun amt(s: String) = s.replace(",", "").replace("Rs", "", true).replace("PKR", "", true).trim().toDoubleOrNull()

    fun fromCsv(text: String): List<Line> {
        val rows = text.lines().filter { it.isNotBlank() }.map(::splitCsv)
        val hi = rows.indexOfFirst { r -> r.any { it.contains("date", true) } && r.size >= 3 }.takeIf { it >= 0 } ?: return emptyList()
        val h = rows[hi].map { it.lowercase() }
        fun col(vararg k: String) = h.indexOfFirst { c -> k.any { c.contains(it) } }
        val cd = col("date"); val cdesc = col("description", "narration", "details", "particular", "remarks"); val cdeb = col("debit", "withdraw", "dr"); val ccre = col("credit", "deposit", "cr"); val cam = col("amount")
        return rows.drop(hi + 1).mapNotNull { r ->
            val d = r.getOrNull(cd)?.let(::date) ?: return@mapNotNull null
            val desc = r.getOrNull(cdesc).orEmpty()
            val deb = r.getOrNull(cdeb)?.let(::amt) ?: 0.0; val cre = r.getOrNull(ccre)?.let(::amt) ?: 0.0; val one = r.getOrNull(cam)?.let(::amt)
            val (a, inc) = when { cdeb >= 0 && deb > 0 -> deb to false; ccre >= 0 && cre > 0 -> cre to true; one != null -> (kotlin.math.abs(one)) to (one > 0 && INCOME.containsMatchIn(desc) || one > 0 && cdeb < 0 && false); else -> return@mapNotNull null }
            if (a <= 0) null else Line(d, desc.take(80), a, inc, if (inc) "Income" else categorize(desc))
        }
    }

    /** Text from a PDF or photo: dates followed by a description and amounts; the balance column, when present, decides debit vs credit. */
    fun fromText(text: String): List<Line> {
        val re = Regex("(\\d{4}-\\d{2}-\\d{2}|\\d{1,2}[-/ .](?:\\d{1,2}|[A-Za-z]{3})[-/ .]\\d{2,4})\\s+(.*)")
        var prevBal: Double? = null; val out = mutableListOf<Line>()
        text.lines().forEach { raw ->
            val m = re.find(raw.trim()) ?: return@forEach
            val d = date(m.groupValues[1].replace('.', '/')) ?: return@forEach
            val rest = m.groupValues[2]
            val amounts = Regex("-?[0-9][0-9,]*\\.[0-9]{2}").findAll(rest).map { it.value }.toList()
            if (amounts.isEmpty()) return@forEach
            val desc = rest.substringBefore(amounts.first()).trim().take(80)
            val first = amt(amounts.first()) ?: return@forEach
            val bal = if (amounts.size >= 2) amt(amounts.last()) else null
            val inc = when { prevBal != null && bal != null -> bal > prevBal!!; Regex("\\bcr\\b", RegexOption.IGNORE_CASE).containsMatchIn(rest) -> true; Regex("\\bdr\\b", RegexOption.IGNORE_CASE).containsMatchIn(rest) -> false; else -> INCOME.containsMatchIn(desc) }
            if (bal != null) prevBal = bal
            if (kotlin.math.abs(first) > 0) out += Line(d, desc, kotlin.math.abs(first), inc, if (inc) "Income" else categorize(desc))
        }
        return out
    }
}

object Mega14 {
    val all: List<Spec> by lazy { listOf(
        ScreenSpec("placereminders", "Place reminders", "\"Remind me when I reach the market\": uses location only if you allow it", Icons.Outlined.PinDrop, Hub.Tools,
            "place location reminder geofence arrive near market home office remind me when i reach") { _, _ -> PlaceRemindersScreen() },
        ScreenSpec("eyebreaks", "Eye & posture breaks", "A light 20-20-20 nudge while you're on the phone or laptop", Icons.Outlined.Visibility, Hub.Health,
            "eye break 20 20 20 posture stretch screen strain neck wrists reminder") { _, _ -> EyeBreaksScreen() },
        ScreenSpec("pregnancy", "Pregnancy week by week", "Due date, trimester and what to expect, kept private on this phone", Icons.Outlined.PregnantWoman, Hub.Health,
            "pregnancy week due date trimester milestone expecting antenatal scan") { _, _ -> PregnancyScreen() },
        ScreenSpec("nospend", "No-spend challenge", "Pick a category or everything, set the days and watch the streak and savings", Icons.Outlined.MoneyOff, Hub.Money,
            "no spend challenge streak saving category eating out shopping frugal") { c, _ -> NoSpendScreen(c) },
        ScreenSpec("statementimport", "Bank statement import", "Drop a CSV, PDF or photo of a statement and sort every line into your categories", Icons.Outlined.UploadFile, Hub.Money,
            "bank statement import csv pdf upload transactions hbl ubl meezan mcb alfalah bulk categorize") { c, _ -> StatementScreen(c) },
        ScreenSpec("roundup", "Round-up savings", "Round each expense up to the next Rs 50 or 100 and see what you'd have saved", Icons.Outlined.Savings, Hub.Money,
            "round up savings spare change save automatically pot goal") { c, _ -> RoundUpScreen(c) },
        ScreenSpec("mathtrain", "Mental-math trainer", "Timed drills that get harder as you improve", Icons.Outlined.Calculate, Hub.Learn,
            "mental math arithmetic drill speed practice tables multiply brain training") { _, _ -> MathTrainer() },
        ScreenSpec("typingpractice", "Typing practice", "English and Roman Urdu sentences with speed and accuracy history", Icons.Outlined.Keyboard, Hub.Learn,
            "typing practice speed wpm accuracy keyboard roman urdu english test") { _, _ -> TypingPractice() },
    ) }
}

@Composable
private fun PlaceRemindersScreen() {
    val c = LocalContext.current
    var list by remember { mutableStateOf(PlaceReminders.all(c)) }
    var name by remember { mutableStateOf("") }; var text by remember { mutableStateOf("") }; var radius by remember { mutableStateOf("150") }
    var granted by remember { mutableStateOf(PlaceReminders.allowed(c)) }
    var status by remember { mutableStateOf("") }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Atlas checks the phone's last known location in the background and tells you once a day when you're near a saved place. Nothing is sent anywhere and no trail is kept.", style = MaterialTheme.typography.bodyMedium)
        if (!granted) TextButton(onClick = { ask.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("Allow location") }
        OutlinedTextField(name, { name = it }, label = { Text("Place name (e.g. Sunday market)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(text, { text = it }, label = { Text("Remind me to…") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(radius, { radius = it.filter(Char::isDigit) }, label = { Text("Radius (metres)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = granted && name.isNotBlank() && text.isNotBlank(), onClick = {
            val l = PlaceReminders.here(c)
            if (l == null) status = "No recent location yet. Open Maps for a moment, then try again while you're at the place."
            else { PlaceReminders.add(c, name, text, l.latitude, l.longitude, radius.toIntOrNull() ?: 150); list = PlaceReminders.all(c); name = ""; text = ""; status = "Saved at your current spot." }
        }) { Text("Save this spot (where I am now)") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        list.forEach { o -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("📍 ${o.optString("name")} · ${o.optInt("r")} m — ${o.optString("text")}", Modifier.weight(1f))
            TextButton(onClick = { PlaceReminders.remove(c, o.optString("id")); list = PlaceReminders.all(c) }) { Text("✕") } } }
    }
}

@Composable
private fun EyeBreaksScreen() {
    val c = LocalContext.current
    var on by remember { mutableStateOf(EyeBreaks.on(c)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Every 45 minutes or so while the screen is on (8am–10pm), Atlas suggests a break: look 20 feet away for 20 seconds, blink, stretch, sit tall. It stays quiet in Worship, Focus, Night and driving modes.", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Remind me", Modifier.weight(1f)); Switch(on, { on = it; EyeBreaks.set(c, it) }) }
    }
}

@Composable
private fun PregnancyScreen() {
    val c = LocalContext.current
    val p = remember { c.getSharedPreferences("pregnancy", 0) }
    var lmp by remember { mutableStateOf(runCatching { com.nizam.app.feature.future.Sealed.openText(p.getString("lmp", "").orEmpty()) }.getOrDefault("")) }
    val d = runCatching { LocalDate.parse(lmp) }.getOrNull()
    val MILESTONES = mapOf(4 to "Pregnancy test usually positive; start folic acid if you haven't.", 8 to "Heartbeat visible on scan; first antenatal visit.", 12 to "End of the first trimester; the dating or NT scan.",
        16 to "You may feel the first movements soon.", 20 to "Anomaly scan (the 'big scan'); halfway.", 24 to "Baby is viable; glucose test around now.", 28 to "Third trimester begins; count kicks daily.",
        32 to "Baby is gaining weight fast; plan the hospital bag.", 36 to "Weekly checks; baby is nearly full term.", 40 to "Due date. Call your doctor if labour signs begin.")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("This is general information, not medical advice. Your date stays encrypted on this phone.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(lmp, { lmp = it; if (runCatching { LocalDate.parse(it) }.isSuccess) p.edit().putString("lmp", com.nizam.app.feature.future.Sealed.sealText(it)).apply() }, label = { Text("First day of last period (yyyy-mm-dd)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (d != null) {
            val days = ChronoUnit.DAYS.between(d, LocalDate.now()).toInt(); val wk = days / 7
            if (days < 0 || wk > 42) Text("That date doesn't look right.") else {
                Text("Week $wk, day ${days % 7} · ${when { wk < 13 -> "first"; wk < 28 -> "second"; else -> "third" }} trimester", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("Due around ${d.plusDays(280)} (${(280 - days).coerceAtLeast(0)} days to go)")
                MILESTONES.entries.filter { it.key <= wk + 4 && it.key >= wk - 1 }.forEach { (w, t) -> Text("Week $w: $t", style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun NoSpendScreen(container: AppContainer) {
    val c = LocalContext.current
    val p = remember { c.getSharedPreferences("nospend", 0) }
    var start by remember { mutableStateOf(p.getString("start", "").orEmpty()) }
    var cat by remember { mutableStateOf(p.getString("cat", "All").orEmpty()) }
    var goal by remember { mutableStateOf(p.getInt("goal", 7)) }
    var txns by remember { mutableStateOf<List<Txn>>(emptyList()) }
    val cats = listOf("All", "Food", "Shopping", "Transport", "Groceries", "Bills", "Other")
    LaunchedEffect(start) { com.nizam.app.ui.design.guard { txns = runCatching { container.financeRepository.since(LocalDate.now().minusDays(60).toString()).first() }.getOrDefault(emptyList()) } }
    fun matches(t: Txn) = t.kind == "EXPENSE" && (cat == "All" || t.category.equals(cat, true))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Category", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row(Modifier.padding(vertical = 2.dp)) { cats.take(4).forEach { k -> TextButton(onClick = { cat = k; p.edit().putString("cat", k).apply() }) { Text(k, fontWeight = if (cat == k) FontWeight.Bold else FontWeight.Normal) } } }
        Row { cats.drop(4).forEach { k -> TextButton(onClick = { cat = k; p.edit().putString("cat", k).apply() }) { Text(k, fontWeight = if (cat == k) FontWeight.Bold else FontWeight.Normal) } } }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Goal: $goal days", Modifier.weight(1f))
            TextButton(onClick = { goal = (goal - 1).coerceAtLeast(1); p.edit().putInt("goal", goal).apply() }) { Text("−") }
            TextButton(onClick = { goal += 1; p.edit().putInt("goal", goal).apply() }) { Text("+") } }
        if (start.isBlank()) TextButton(onClick = {
            val before = txns.filter { matches(it) && it.localDate < LocalDate.now().toString() }.sumOf { it.amount } / 30.0
            start = LocalDate.now().toString(); p.edit().putString("start", start).putFloat("base", before.toFloat()).apply() }) { Text("Start today") }
        else {
            val s = LocalDate.parse(start); val total = ChronoUnit.DAYS.between(s, LocalDate.now()).toInt() + 1
            val spentDays = txns.filter { matches(it) && it.localDate >= start }.map { it.localDate }.toSet()
            var streak = 0; var d = LocalDate.now(); while (!d.isBefore(s) && d.toString() !in spentDays) { streak++; d = d.minusDays(1) }
            val clean = total - spentDays.size; val saved = clean * p.getFloat("base", 0f)
            Text("Streak: $streak days · clean days $clean of $total", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            com.nizam.app.ui.design.GoalRibbon((streak / goal.toFloat()).coerceAtMost(1f))
            Text("Estimated saved: ${rs(saved.toDouble())} (your usual daily spend in this category × clean days)", style = MaterialTheme.typography.bodyMedium)
            if (spentDays.contains(LocalDate.now().toString())) Text("You spent today — the streak restarts tomorrow. One slip isn't failure.", style = MaterialTheme.typography.bodySmall)
            if (streak >= goal) { Text("🎉 Goal reached!"); LaunchedEffect(streak) { com.nizam.app.ui.design.guard { SoundFx.chime(c) } } }
            TextButton(onClick = { p.edit().remove("start").apply(); start = "" }) { Text("Restart") }
        }
    }
}

@Composable
private fun StatementScreen(container: AppContainer) {
    val c = LocalContext.current; val scope = com.nizam.app.ui.design.rememberSafeScope()
    var lines by remember { mutableStateOf<List<Statements.Line>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u: Uri? -> if (u != null) scope.launch {
        busy = true; status = "Reading the statement…"
        val parsed = runCatching {
            val type = c.contentResolver.getType(u).orEmpty()
            val text = if (type.contains("csv") || type.startsWith("text") || (u.lastPathSegment ?: "").endsWith(".csv", true)) c.contentResolver.openInputStream(u)?.bufferedReader()?.use { it.readText() }.orEmpty()
                       else DocText.extract(c, u) { status = it }
            val csv = Statements.fromCsv(text); if (csv.isNotEmpty()) csv else Statements.fromText(text)
        }.getOrElse { status = "Couldn't read it: ${it.message}"; emptyList() }
        val existing = runCatching { container.financeRepository.since(LocalDate.now().minusDays(400).toString()).first() }.getOrDefault(emptyList())
        parsed.forEach { l -> if (existing.any { it.localDate == l.date.toString() && kotlin.math.abs(it.amount - l.amount) < 0.01 }) { l.dup = true; l.take = false } }
        lines = parsed.take(500); busy = false
        if (status.startsWith("Reading")) status = if (parsed.isEmpty()) "No transactions found. Try the CSV export from your bank's app, or a clearer scan." else "Found ${parsed.size} lines. Untick anything that's wrong, then import."
    } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Everything is read on this phone. Lines that match an expense you already logged (same day and amount) start unticked.", style = MaterialTheme.typography.bodyMedium)
        TextButton(enabled = !busy, onClick = { pick.launch("*/*") }) { Text("Choose a CSV, PDF or photo") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        if (lines.isNotEmpty()) {
            val chosen = lines.filter { it.take }
            Text("${chosen.size} selected · out ${rs(chosen.filter { !it.income }.sumOf { it.amount })} · in ${rs(chosen.filter { it.income }.sumOf { it.amount })}", fontWeight = FontWeight.SemiBold)
            TextButton(enabled = chosen.isNotEmpty(), onClick = { scope.launch {
                var n = 0; chosen.forEach { l -> runCatching { container.financeRepository.restore(Txn(amount = l.amount, kind = if (l.income) "INCOME" else "EXPENSE", category = l.category, localDate = l.date.toString(), note = "Statement: ${l.desc}")); n++ } }
                status = "Imported $n transactions."; lines = emptyList() } }) { Text("Import selected") }
            lines.take(200).forEachIndexed { i, l -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(l.take, { v -> lines = lines.toMutableList().also { it[i] = l.copy().also { x -> x.take = v; x.dup = l.dup } } })
                Column(Modifier.weight(1f)) { Text("${if (l.income) "＋" else "−"} ${rs(l.amount)} · ${l.category}${if (l.dup) " · looks already logged" else ""}", style = MaterialTheme.typography.bodyMedium)
                    Text("${l.date} · ${l.desc}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) } } }
        }
    }
}

@Composable
private fun RoundUpScreen(container: AppContainer) {
    val c = LocalContext.current
    val p = remember { c.getSharedPreferences("roundup", 0) }
    var step by remember { mutableStateOf(p.getInt("step", 50)) }
    var since by remember { mutableStateOf(p.getString("since", LocalDate.now().withDayOfMonth(1).toString()).orEmpty()) }
    var txns by remember { mutableStateOf<List<Txn>>(emptyList()) }
    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(since, tick) { com.nizam.app.ui.design.guard { txns = runCatching { container.financeRepository.since(since).first() }.getOrDefault(emptyList()) } }
    val ex = txns.filter { it.kind == "EXPENSE" && it.localDate >= since }
    val pot = ex.sumOf { val r = it.amount % step; if (r == 0.0) 0.0 else step - r }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Atlas pretends each expense was rounded up to the next Rs $step. When you decide to move that amount into savings, tap the button and the pot resets.", style = MaterialTheme.typography.bodyMedium)
        Row { listOf(10, 50, 100).forEach { s -> TextButton(onClick = { step = s; p.edit().putInt("step", s).apply() }) { Text("Rs $s", fontWeight = if (step == s) FontWeight.Bold else FontWeight.Normal) } } }
        com.nizam.app.ui.design.CountUp(pot, prefix = "Rs ", style = MaterialTheme.typography.displaySmall)
        Text("from ${ex.size} expenses since $since", style = MaterialTheme.typography.bodySmall)
        TextButton(enabled = pot > 0, onClick = { p.edit().putString("since", LocalDate.now().plusDays(0).toString()).apply(); since = LocalDate.now().toString(); tick++; SoundFx.done(c) }) { Text("I've moved this to savings") }
    }
}

@Composable
private fun MathTrainer() {
    val c = LocalContext.current
    val p = remember { c.getSharedPreferences("mathtrain", 0) }
    var level by remember { mutableStateOf(p.getInt("level", 1)) }
    var running by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf(0) }; var score by remember { mutableStateOf(0) }
    var a by remember { mutableStateOf(0) }; var b by remember { mutableStateOf(0) }; var op by remember { mutableStateOf('+') }
    var answer by remember { mutableStateOf("") }; var startAt by remember { mutableStateOf(0L) }; var result by remember { mutableStateOf("") }
    fun next() { val hi = when (level) { 1 -> 10; 2 -> 20; 3 -> 50; 4 -> 100; else -> 200 }
        op = listOf('+', '-', '×').let { if (level >= 2) it.random() else listOf('+', '-').random() }
        if (op == '×') { a = Random.nextInt(2, if (level < 3) 10 else 13); b = Random.nextInt(2, if (level < 4) 10 else 20) } else { a = Random.nextInt(1, hi + 1); b = Random.nextInt(1, hi + 1); if (op == '-' && b > a) { val t = a; a = b; b = t } } }
    fun correct() = when (op) { '+' -> a + b; '-' -> a - b; else -> a * b }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Level $level", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { level = (level - 1).coerceAtLeast(1) }) { Text("−") }; TextButton(onClick = { level = (level + 1).coerceAtMost(5) }) { Text("+") } }
        Text("Best: ${p.getInt("best$level", 0)} / 20 · fastest ${p.getInt("fast$level", 0).let { if (it == 0) "—" else "${it}s" }}", style = MaterialTheme.typography.bodySmall)
        if (!running) {
            if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.titleMedium)
            TextButton(onClick = { running = true; q = 0; score = 0; startAt = System.currentTimeMillis(); next(); answer = ""; result = "" }) { Text("Start 20 questions") }
        } else {
            Text("${q + 1} / 20", style = MaterialTheme.typography.labelMedium)
            Text("$a $op $b = ?", style = MaterialTheme.typography.displaySmall.copy(fontFamily = FontFamilyMono))
            OutlinedTextField(answer, { v -> answer = v.filter { it.isDigit() || it == '-' }
                if (answer.toIntOrNull() == correct()) { score++; SoundFx.tick(c); q++; answer = ""
                    if (q >= 20) { running = false; val secs = ((System.currentTimeMillis() - startAt) / 1000).toInt()
                        if (score > p.getInt("best$level", 0)) p.edit().putInt("best$level", score).apply(); if (p.getInt("fast$level", 0) == 0 || secs < p.getInt("fast$level", 0)) p.edit().putInt("fast$level", secs).apply()
                        result = "Score $score / 20 in ${secs}s" + if (score >= 18 && level < 5) " — try level ${level + 1}!" else ""; p.edit().putInt("level", level).apply(); SoundFx.chime(c) } else next() } },
                singleLine = true, label = { Text("Answer") }, modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { q++; answer = ""; if (q >= 20) { running = false; result = "Score $score / 20" } else next() }) { Text("Skip") }
        }
    }
}
private val FontFamilyMono = androidx.compose.ui.text.font.FontFamily.Monospace

@Composable
private fun TypingPractice() {
    val c = LocalContext.current
    val p = remember { c.getSharedPreferences("typing", 0) }
    val EN = listOf("The quick brown fox jumps over the lazy dog near the old market.", "Practice makes progress, and progress makes confidence.", "Please send me the report before the meeting begins tomorrow morning.", "A calm mind finishes more work than a hurried one.")
    val RU = listOf("Aaj mausam bohat acha hai, chalo bahar chalte hain.", "Kal subah paanch baje uth kar namaz parhni hai insha Allah.", "Mujhe apni kitab dene ke liye shukriya, main kal wapas kar doon ga.", "Mehnat karne wale kabhi khali haath nahin lautte.")
    var lang by remember { mutableStateOf("English") }
    var target by remember { mutableStateOf(EN.random()) }
    var typed by remember { mutableStateOf("") }
    var startAt by remember { mutableStateOf(0L) }
    var result by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row { listOf("English", "Roman Urdu").forEach { l -> TextButton(onClick = { lang = l; target = (if (l == "English") EN else RU).random(); typed = ""; result = "" }) { Text(l, fontWeight = if (lang == l) FontWeight.Bold else FontWeight.Normal) } } }
        Text("Best: ${p.getInt("best$lang", 0)} WPM", style = MaterialTheme.typography.bodySmall)
        Text(buildString { append(target) }, style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(typed, { v -> if (typed.isEmpty() && v.isNotEmpty()) startAt = System.currentTimeMillis(); typed = v
            if (v.length >= target.length) { val mins = ((System.currentTimeMillis() - startAt) / 60000.0).coerceAtLeast(1.0 / 600)
                val ok = target.indices.count { it < v.length && v[it] == target[it] }; val acc = ok * 100 / target.length; val wpm = (target.split(" ").size / mins).toInt()
                result = "$wpm WPM · $acc% accurate"; if (acc >= 90 && wpm > p.getInt("best$lang", 0)) { p.edit().putInt("best$lang", wpm).apply(); SoundFx.chime(c) } else SoundFx.done(c)
                target = (if (lang == "English") EN else RU).random(); typed = "" } }, label = { Text("Type here") }, modifier = Modifier.fillMaxWidth())
        if (result.isNotBlank()) Text(result, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}
