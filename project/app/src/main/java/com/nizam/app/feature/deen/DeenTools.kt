package com.nizam.app.feature.deen

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.fmtAmount
import com.nizam.app.core.hourToClock
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.feature.prayer.PrayerTimes
import com.nizam.app.net.Ai
import com.nizam.app.net.Http
import com.nizam.app.ui.components.ChatText
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.components.Thinking
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField

// ── Ramadan ──────────────────────────────────────────────────────────────────────────────────────

object Ramadan {
    fun hijri(d: LocalDate = LocalDate.now()) = runCatching { HijrahDate.from(d) }.getOrNull()
    fun inRamadan(d: LocalDate = LocalDate.now()) = hijri(d)?.get(ChronoField.MONTH_OF_YEAR) == 9
    fun dayOf(d: LocalDate = LocalDate.now()) = hijri(d)?.get(ChronoField.DAY_OF_MONTH) ?: 0
    fun year(d: LocalDate = LocalDate.now()) = hijri(d)?.get(ChronoField.YEAR) ?: 0

    /** Days until 1 Ramadan (by the Umm al-Qura calendar — local moon sighting may differ by a day). */
    fun daysUntil(): Int {
        var d = LocalDate.now()
        repeat(400) { i -> if (inRamadan(d) && dayOf(d) == 1) return i; d = d.plusDays(1) }
        return -1
    }

    private fun p(c: Context) = c.getSharedPreferences("ramadan", 0)
    fun fast(c: Context, day: Int) = p(c).getString("${year()}|fast|$day", "").orEmpty()
    fun setFast(c: Context, day: Int, v: String) = p(c).edit().putString("${year()}|fast|$day", v).apply()
    fun juz(c: Context, n: Int) = p(c).getBoolean("${year()}|juz|$n", false)
    fun setJuz(c: Context, n: Int, v: Boolean) = p(c).edit().putBoolean("${year()}|juz|$n", v).apply()
    fun remindersOn(c: Context) = p(c).getBoolean("remind", true)
    fun setReminders(c: Context, on: Boolean) = p(c).edit().putBoolean("remind", on).apply()

    /** From the background cycle: a nudge before suhoor ends and before iftar, once each per day. */
    suspend fun remind(c: Context, container: AppContainer, now: Double) {
        if (!inRamadan() || !remindersOn(c)) return
        val s = container.settingsRepository
        val t = PrayerTimes.calculate(LocalDate.now(), s.latitude.first(), s.longitude.first(),
            runCatching { PrayerTimes.Method.valueOf(s.prayerMethod.first()) }.getOrElse { PrayerTimes.Method.KARACHI }, s.hanafiAsr.first())
        val key = LocalDate.now().toString()
        val sent = p(c)
        if (!t.fajr.isNaN() && now in (t.fajr - 0.75)..t.fajr && !sent.getBoolean("$key|suhoor", false)) {
            Notifications.post(c, Notifications.CHANNEL_PRAYER, 610, "Suhoor ends at ${hourToClock(t.fajr)}",
                "Day ${dayOf()} of Ramadan. Eat, drink water, and make your intention.")
            sent.edit().putBoolean("$key|suhoor", true).apply()
        }
        if (!t.maghrib.isNaN() && now in (t.maghrib - 0.3)..t.maghrib && !sent.getBoolean("$key|iftar", false)) {
            Notifications.post(c, Notifications.CHANNEL_PRAYER, 611, "Iftar at ${hourToClock(t.maghrib)}",
                "Almost there. The dua at iftar is answered — make it count.")
            sent.edit().putBoolean("$key|iftar", true).apply()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RamadanTab(container: AppContainer) {
    val c = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    var times by remember { mutableStateOf<PrayerTimes.Times?>(null) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard {
        val s = container.settingsRepository
        times = PrayerTimes.calculate(LocalDate.now(), s.latitude.first(), s.longitude.first(),
            runCatching { PrayerTimes.Method.valueOf(s.prayerMethod.first()) }.getOrElse { PrayerTimes.Method.KARACHI }, s.hanafiAsr.first())
    } }
    if (!Ramadan.inRamadan()) {
        val n = Ramadan.daysUntil()
        Group {
            Text(if (n > 0) "$n days until Ramadan" else "Ramadan", style = MaterialTheme.typography.headlineSmall)
            Text("By the Umm al-Qura calendar; your local moon sighting may differ by a day. This tab becomes your Ramadan " +
                "companion — suhoor and iftar times, a fasting log, a juz-by-juz khatm, and reminders.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        }
        return
    }
    val day = Ramadan.dayOf()
    Group {
        Text("Ramadan · day $day", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        times?.let { t ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Column2("Suhoor ends", hourToClock(t.fajr), Modifier.weight(1f))
                Column2("Iftar", hourToClock(t.maghrib), Modifier.weight(1f))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Suhoor and iftar reminders", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(Ramadan.remindersOn(c).also { tick }, { Ramadan.setReminders(c, it); tick++ })
    }
    Label("Today's fast")
    ChipRow(listOf("Fasted", "Missed", "Excused"), Ramadan.fast(c, day).also { tick }.ifBlank { null }, { Ramadan.setFast(c, day, it); tick++ })
    Label("This month")
    FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        (1..30).forEach { d ->
            val v = Ramadan.fast(c, d).also { tick }
            Box(Modifier.size(28.dp).clip(CircleShape).background(when (v) {
                "Fasted" -> MaterialTheme.colorScheme.primary
                "Missed" -> MaterialTheme.colorScheme.error.copy(alpha = 0.6f)
                "Excused" -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }), contentAlignment = Alignment.Center) {
                Text("$d", style = MaterialTheme.typography.labelSmall,
                    color = if (v == "Fasted") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    val missed = (1..30).count { Ramadan.fast(c, it) == "Missed" || Ramadan.fast(c, it) == "Excused" }
    if (missed > 0) Text("$missed to make up after Ramadan.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    Label("Khatm — ${(1..30).count { Ramadan.juz(c, it).also { tick } }} of 30 juz")
    FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
        (1..30).forEach { n ->
            com.nizam.app.ui.design.Capsule("$n", Ramadan.juz(c, n).also { tick }) {
                Ramadan.setJuz(c, n, !Ramadan.juz(c, n)); tick++
                if (Ramadan.juz(c, n)) com.nizam.app.feature.jarvis.Barakah.tick(c, LocalDate.now(), "quran", true)
            }
        }
    }
}

@Composable
private fun Column2(label: String, value: String, modifier: Modifier) = androidx.compose.foundation.layout.Column(modifier) {
    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(value, style = MaterialTheme.typography.headlineMedium)
}

// ── Zakat ────────────────────────────────────────────────────────────────────────────────────────

object Zakat {
    const val GOLD_NISAB_G = 87.48
    const val SILVER_NISAB_G = 612.36
    private fun p(c: Context) = c.getSharedPreferences("zakat", 0)
    fun get(c: Context, k: String) = p(c).getString(k, "").orEmpty()
    fun set(c: Context, k: String, v: String) = p(c).edit().putString(k, v).apply()

    /** The zakat year is a lunar year: 354 days from the day your wealth first reached nisab. */
    fun dueDate(c: Context): LocalDate? = runCatching { LocalDate.parse(get(c, "hawl")).plusDays(354) }.getOrNull()

    fun remind(c: Context) {
        val due = dueDate(c) ?: return
        val days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), due)
        val key = "reminded|$due"
        if (days in 0..3 && !p(c).getBoolean(key, false)) {
            Notifications.post(c, Notifications.CHANNEL_NUDGE, 612, "Zakat is due ${if (days == 0L) "today" else "in $days days"}",
                "Your zakat year completes on $due. Open Deen → Zakat to work out the amount.")
            p(c).edit().putBoolean(key, true).apply()
        }
    }
}

@Composable
fun ZakatTab(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    val fields = listOf("cash" to "Cash and bank balances", "gold" to "Gold you own (grams)", "silver" to "Silver you own (grams)",
        "invest" to "Shares, business stock, crypto held to sell", "owed" to "Money owed to you (likely to be repaid)",
        "debts" to "Debts and bills due now")
    val values = remember { fields.associate { (k, _) -> k to mutableStateOf(Zakat.get(c, k)) } }
    var goldRate by remember { mutableStateOf(Zakat.get(c, "goldRate")) }
    var silverRate by remember { mutableStateOf(Zakat.get(c, "silverRate")) }
    var basis by remember { mutableStateOf(Zakat.get(c, "basis").ifBlank { "Silver" }) }
    var hawl by remember { mutableStateOf(Zakat.get(c, "hawl")) }
    var busy by remember { mutableStateOf(false) }
    fun n(k: String) = values[k]?.value?.replace(",", "")?.toDoubleOrNull() ?: 0.0
    val g = goldRate.replace(",", "").toDoubleOrNull() ?: 0.0
    val s = silverRate.replace(",", "").toDoubleOrNull() ?: 0.0
    val wealth = n("cash") + n("gold") * g + n("silver") * s + n("invest") + n("owed") - n("debts")
    val nisab = if (basis == "Gold") Zakat.GOLD_NISAB_G * g else Zakat.SILVER_NISAB_G * s
    val owes = nisab > 0 && wealth >= nisab

    Hint("Enter what you hold today. Nothing leaves your phone except the optional rate lookup.")
    fields.forEach { (k, label) ->
        OutlinedTextField(values[k]!!.value, { values[k]!!.value = it; Zakat.set(c, k, it) }, singleLine = true,
            label = { Text(label) }, modifier = Modifier.fillMaxWidth(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal))
    }
    Label("Rates per gram (PKR)")
    Row {
        OutlinedTextField(goldRate, { goldRate = it; Zakat.set(c, "goldRate", it) }, singleLine = true, label = { Text("Gold 24k") }, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        OutlinedTextField(silverRate, { silverRate = it; Zakat.set(c, "silverRate", it) }, singleLine = true, label = { Text("Silver") }, modifier = Modifier.weight(1f))
    }
    OutlinedButton(enabled = !busy, onClick = {
        busy = true
        scope.launch {
            runCatching {
                val raw = Ai.generate(container.settingsRepository,
                    "Today's gold (24 karat) and silver prices in Pakistan, per gram, in PKR. Reply with JSON only: {\"gold\":number,\"silver\":number,\"source\":\"site\"}",
                    "You look up current market prices and return exact numbers with the source.", 300, useWeb = true)
                JSONObject(Ai.cleanJson(raw))
            }.onSuccess { o ->
                if (o.optDouble("gold", 0.0) > 0) { goldRate = "%.0f".format(o.optDouble("gold")); Zakat.set(c, "goldRate", goldRate) }
                if (o.optDouble("silver", 0.0) > 0) { silverRate = "%.0f".format(o.optDouble("silver")); Zakat.set(c, "silverRate", silverRate) }
                Feedback.show("Rates from ${o.optString("source", "the web")} — check them against a local jeweller")
            }.onFailure { Feedback.failed("Looking up rates", it) }
            busy = false
        }
    }) { Text(if (busy) "Looking up…" else "Look up today's rates") }
    Label("Nisab")
    ChipRow(listOf("Silver", "Gold"), basis, { basis = it; Zakat.set(c, "basis", it) }, labels = { "$it standard" })
    Hint("Many Hanafi scholars use the silver standard (lower, so more people give); others use gold. Follow your scholar.")

    Group(Modifier.padding(vertical = 10.dp)) {
        Text("Zakatable wealth ${fmtAmount(wealth)}", style = MaterialTheme.typography.titleMedium)
        Text(if (nisab > 0) "Nisab ${fmtAmount(nisab)} (${basis.lowercase()})" else "Add the ${basis.lowercase()} rate to work out nisab",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (owes) "Zakat: ${fmtAmount(wealth * 0.025)}" else if (nisab > 0) "Below nisab — no zakat due on this" else "",
            style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
    }
    OutlinedTextField(hawl, { hawl = it; Zakat.set(c, "hawl", it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        label = { Text("Your zakat year began (YYYY-MM-DD)") })
    Zakat.dueDate(c)?.let { Text("Due on $it — Atlas will remind you three days before.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant) }
    if (owes) Button(modifier = Modifier.padding(top = 10.dp), onClick = {
        scope.launch {
            container.financeRepository.add(wealth * 0.025, "EXPENSE", "Zakat", "Zakat ${LocalDate.now().year}")
            Zakat.set(c, "hawl", LocalDate.now().toString()); hawl = LocalDate.now().toString()
            com.nizam.app.feature.jarvis.Barakah.tick(c, LocalDate.now(), "charity", true)
            Feedback.show("Recorded in Finance. Next zakat year starts today.")
        }
    }) { Text("I've paid it — record it") }
    Hint("A calculator, not a fatwa. For jewellery worn, business specifics or debts, ask a scholar you trust.")
}

// ── Ask, answered only from the Quran's own words ────────────────────────────────────────────────

data class Verse(val surah: Int, val ayah: Int, val surahName: String, val text: String)

object AskDeen {
    /** Searches the Sahih International translation on alquran.cloud for each keyword. */
    suspend fun verses(keywords: List<String>): List<Verse> = keywords.take(3).flatMap { k ->
        runCatching {
            val o = JSONObject(Http.getJson("https://api.alquran.cloud/v1/search/${java.net.URLEncoder.encode(k, "UTF-8")}/all/en.sahih"))
            val m = o.optJSONObject("data")?.optJSONArray("matches") ?: JSONArray()
            (0 until minOf(m.length(), 4)).map { m.getJSONObject(it) }.map { v ->
                Verse(v.optJSONObject("surah")?.optInt("number") ?: 0, v.optInt("numberInSurah"),
                    v.optJSONObject("surah")?.optString("englishName").orEmpty(), v.optString("text"))
            }
        }.getOrElse { emptyList() }
    }.distinctBy { it.surah to it.ayah }.take(8)
}

@Composable
fun AskTab(container: AppContainer) {
    val scope = rememberWorkScope()
    var q by remember { mutableStateOf("") }
    var verses by remember { mutableStateOf<List<Verse>>(emptyList()) }
    var answer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Hint("Ask about something in the Quran. Atlas finds the verses first and only explains what they say — it never invents " +
        "a verse, a hadith or a ruling.")
    OutlinedTextField(q, { q = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, placeholder = { Text("What does the Quran say about patience?") })
    Button(enabled = q.isNotBlank() && !busy, modifier = Modifier.padding(top = 6.dp), onClick = {
        busy = true; verses = emptyList(); answer = ""
        scope.launch {
            runCatching {
                val kw = JSONObject(Ai.cleanJson(Ai.generate(container.settingsRepository,
                    "Give 1 to 3 single English search words that would find Quran verses (Sahih International) relevant to: \"$q\". " +
                        "JSON only: {\"words\":[\"...\"]}", "You pick precise search terms.", 120)))
                    .optJSONArray("words")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: listOf(q)
                verses = AskDeen.verses(kw)
                if (verses.isEmpty()) answer = "No verses matched those words. Try asking it differently."
                else com.nizam.app.net.Streaming.reply(container.settingsRepository,
                    "Question: $q\n\nThese are the ONLY sources you may use:\n" + verses.joinToString("\n") { "[${it.surah}:${it.ayah}] ${it.text}" } +
                        "\n\nExplain what these verses say about the question in a few sentences, citing [surah:ayah]. Do not add verses, " +
                        "hadith or rulings that aren't here. If they don't answer it, say so and suggest asking a scholar.",
                    "You explain Quran verses faithfully and humbly.", 700, context = container.appContext) { answer += it }
            }.onFailure { answer = "Couldn't search: ${Feedback.friendly(it)}" }
            busy = false
        }
    }) { Text("Ask") }
    if (busy && verses.isEmpty()) Thinking(label = "Finding the verses")
    verses.forEach { v ->
        Group(Modifier.padding(vertical = 5.dp)) {
            Text("${v.surahName} ${v.surah}:${v.ayah}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(v.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
    }
    if (answer.isNotBlank()) {
        ChatText(answer, Modifier.padding(vertical = 10.dp))
        Hint("Translations: Sahih International, via alquran.cloud. This is not a fatwa — for rulings, ask a qualified scholar.")
    }
}
