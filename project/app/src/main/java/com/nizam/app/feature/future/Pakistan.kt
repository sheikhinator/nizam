package com.nizam.app.feature.future

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.data.prefs.SettingsRepository
import com.nizam.app.net.Ai
import com.nizam.app.net.Http
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.components.Thinking
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** The numbers Pakistan checks every day: currency, gold, fuel, cricket, bills and load-shedding. */
object PakistanData {
    data class Rates(val pkrPer: Map<String, Double>, val goldTola: Double?, val gold10g: Double?, val silverTola: Double?, val at: String)

    suspend fun rates(): Rates {
        val r = JSONObject(Http.getJson("https://open.er-api.com/v6/latest/USD")).getJSONObject("rates")
        val pkr = r.getDouble("PKR")
        val per = listOf("USD", "SAR", "AED", "GBP", "EUR", "CNY", "CAD", "AUD", "OMR", "KWD").associateWith { pkr / r.getDouble(it) }
        // international spot price, converted: 1 tola = 11.6638 g, 1 troy ounce = 31.1035 g
        suspend fun spot(sym: String) = runCatching { JSONObject(Http.getJson("https://api.gold-api.com/price/$sym")).getDouble("price") }.getOrNull()
        val goldOz = spot("XAU"); val silverOz = spot("XAG")
        return Rates(per, goldOz?.let { it / 31.1035 * 11.6638 * pkr }, goldOz?.let { it / 31.1035 * 10 * pkr },
            silverOz?.let { it / 31.1035 * 11.6638 * pkr }, LocalDateTime.now().format(DateTimeFormatter.ofPattern("h:mm a")))
    }

    data class Match(val title: String, val link: String, val pakistan: Boolean)
    suspend fun cricket(): List<Match> {
        val xml = Http.getText("https://static.cricinfo.com/rss/livescores.xml")
        return Regex("<item>.*?<title>(.*?)</title>.*?<link>(.*?)</link>.*?</item>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map {
            val t = it.groupValues[1].replace("&amp;", "&").trim()
            Match(t, it.groupValues[2].trim(), t.contains("Pakistan", true) || Regex("\\b(Lahore Qalandars|Karachi Kings|Islamabad United|Peshawar Zalmi|Quetta Gladiators|Multan Sultans)\\b").containsMatchIn(t))
        }.toList().sortedByDescending { it.pakistan }
    }

    /** Things only a web search knows today (fuel prices, schedules) — asked once a day and kept. */
    suspend fun askToday(c: Context, s: SettingsRepository, key: String, question: String): String {
        val p = c.getSharedPreferences("pakistan", 0)
        val today = LocalDate.now().toString()
        if (p.getString("${key}_day", "") == today) p.getString(key, null)?.let { return it }
        val a = Ai.generate(s, question, "Answer with current facts from the web, briefly, with the date they apply from. If unsure, say so.", 500, useWeb = true)
        p.edit().putString(key, a).putString("${key}_day", today).apply()
        return a
    }

    // ── bills ──────────────────────────────────────────────────────────────────────────────────
    val DISCOS = linkedMapOf("LESCO" to "lesco", "IESCO" to "iesco", "FESCO" to "fesco", "GEPCO" to "gepco", "MEPCO" to "mepco",
        "PESCO" to "pesco", "HESCO" to "hesco", "SEPCO" to "sepco", "QESCO" to "qesco", "TESCO" to "tesco")
    val COMPANIES = DISCOS.keys.toList() + listOf("K-Electric", "SNGPL", "SSGC", "PTCL")
    data class Bill(val id: String, val company: String, val ref: String, val label: String, val due: String, val amount: String)

    fun bills(c: Context): List<Bill> = runCatching { JSONArray(c.getSharedPreferences("pakistan", 0).getString("bills", "[]")) }.getOrElse { JSONArray() }
        .let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Bill(it.optString("id"), it.optString("company"), it.optString("ref"), it.optString("label"), it.optString("due"), it.optString("amount")) } }
    fun saveBills(c: Context, list: List<Bill>) = c.getSharedPreferences("pakistan", 0).edit().putString("bills", JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("company", it.company).put("ref", it.ref).put("label", it.label).put("due", it.due).put("amount", it.amount)) }
    }.toString()).apply()

    fun billUrl(b: Bill): String = DISCOS[b.company]?.let { "https://bill.pitc.com.pk/${it}bill/general?refno=${b.ref.filter(Char::isDigit)}" } ?: when (b.company) {
        "K-Electric" -> "https://www.ke.com.pk/customer-services/know-your-bill/"
        "SNGPL" -> "https://www.sngpl.com.pk/web/viewbill?consumer=${b.ref.filter(Char::isDigit)}&proc=viewbill&contype=NewCon"
        "SSGC" -> "https://viewbill.ssgc.com.pk/web/"
        else -> "https://dbill.ptcl.net.pk/"
    }

    /** Reads the due date and amount off an electricity company's bill page. */
    suspend fun check(b: Bill): Bill {
        if (b.company !in DISCOS) return b
        val html = Http.getText(billUrl(b))
        val text = html.replace(Regex("<[^>]+>"), " ").replace("&nbsp;", " ").replace(Regex("\\s+"), " ")
        val due = Regex("DUE DATE\\s*([0-9]{1,2}[ -][A-Za-z]{3}[ -][0-9]{2,4})", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.let { d ->
            runCatching { LocalDate.parse(d.replace(' ', '-'), DateTimeFormatter.ofPattern(if (d.length > 9) "d-MMM-yyyy" else "d-MMM-yy", java.util.Locale.ENGLISH)).toString() }.getOrNull() }
        val amount = Regex("PAYABLE WITHIN DUE DATE\\s*([0-9,]+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)
        return b.copy(due = due ?: b.due, amount = amount ?: b.amount)
    }

    /** From the daily background check: a nudge two days before, and on, the due date. */
    fun remind(c: Context) {
        val p = c.getSharedPreferences("pakistan", 0)
        bills(c).filter { it.due.isNotBlank() }.forEach { b ->
            val days = runCatching { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(b.due)) }.getOrNull() ?: return@forEach
            val mark = "billnote:${b.id}:${b.due}:$days"
            if ((days == 2L || days == 0L) && !p.getBoolean(mark, false)) {
                com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 800 + (b.id.hashCode() and 0xFF),
                    "${b.label.ifBlank { b.company }} bill due ${if (days == 0L) "today" else "in 2 days"}",
                    (if (b.amount.isNotBlank()) "Rs ${b.amount} · " else "") + "pay by ${b.due} to avoid the late surcharge")
                p.edit().putBoolean(mark, true).apply()
            }
        }
    }
}

@Composable
fun PakistanScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var rates by remember { mutableStateOf<PakistanData.Rates?>(null) }
    var matches by remember { mutableStateOf<List<PakistanData.Match>?>(null) }
    var petrol by remember { mutableStateOf("") }
    var shedding by remember { mutableStateOf("") }
    var bills by remember { mutableStateOf(PakistanData.bills(c)) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { rates = runCatching { PakistanData.rates() }.getOrNull() } }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { while (true) { matches = runCatching { PakistanData.cricket() }.getOrElse { matches ?: emptyList() }; delay(60_000) } } }
    fun rs(v: Double) = "Rs " + "%,.0f".format(v)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Pakistan today", "Rates, fuel, cricket and your bills")

        Label("Currency · open market rates vary a little")
        val r = rates
        if (r == null) Thinking(label = "Getting today's rates") else Group {
            r.pkrPer.entries.chunked(2).forEach { row ->
                Row { row.forEach { (k, v) -> Row(Modifier.weight(1f).padding(vertical = 4.dp)) {
                    Text(k, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 8.dp)); Text(if (v < 10) "Rs %.2f".format(v) else "Rs %.2f".format(v), fontWeight = FontWeight.SemiBold) } } }
            }
            Text("Updated ${r.at}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Label("Gold & silver · international price in rupees (local sarafa adds a premium)")
        r?.let { g -> Group {
            g.goldTola?.let { Text("Gold 24k: ${rs(it)} / tola · ${rs(g.gold10g ?: 0.0)} / 10 g", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold) }
            g.goldTola?.let { Text("22k ≈ ${rs(it * 22 / 24)} / tola · 21k ≈ ${rs(it * 21 / 24)} / tola", style = MaterialTheme.typography.bodyMedium) }
            g.silverTola?.let { Text("Silver: ${rs(it)} / tola", style = MaterialTheme.typography.bodyMedium) }
            if (g.goldTola == null) Text("Gold price service is busy — try again later.", style = MaterialTheme.typography.bodySmall)
        } }

        Label("Fuel & load-shedding")
        Group {
            if (petrol.isBlank()) OutlinedButton(onClick = { petrol = "…"; scope.launch { petrol = runCatching {
                PakistanData.askToday(c, container.settingsRepository, "petrol", "Current official petrol, high-speed diesel and LPG prices per litre/kg in Pakistan, and when they change next.") }
                .getOrElse { "Couldn't check: ${Feedback.friendly(it)}" } } }) { Text("⛽ Today's petrol & diesel price") }
            else Text(petrol, style = MaterialTheme.typography.bodyMedium)
            var area by remember { mutableStateOf(c.getSharedPreferences("pakistan", 0).getString("area", "").orEmpty()) }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                OutlinedTextField(area, { area = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("Your area / feeder, e.g. DHA Phase 5 Lahore") })
                Spacer(Modifier.padding(4.dp))
                Button(enabled = area.isNotBlank(), onClick = {
                    c.getSharedPreferences("pakistan", 0).edit().putString("area", area).apply()
                    shedding = "…"; scope.launch { shedding = runCatching { Ai.generate(container.settingsRepository,
                        "Today's load-shedding schedule for $area, Pakistan (electricity company, hours). If there's no published schedule, say so and give the company's helpline.",
                        useWeb = true, maxOutputTokens = 500) }.getOrElse { "Couldn't check: ${Feedback.friendly(it)}" } }
                }) { Text("Check") }
            }
            if (shedding.isNotBlank()) Text(shedding, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
        }

        Label("Cricket · live")
        val m = matches
        if (m == null) Thinking(label = "Checking scores") else if (m.isEmpty()) Text("No live matches right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Group { m.take(8).forEach { x ->
            Text((if (x.pakistan) "🇵🇰 " else "🏏 ") + x.title, style = MaterialTheme.typography.bodyLarge, fontWeight = if (x.pakistan) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.fillMaxWidth().clickable { runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(x.link))) } }.padding(vertical = 6.dp))
        } }

        Label("Your bills")
        var company by remember { mutableStateOf("LESCO") }
        var ref by remember { mutableStateOf("") }
        var label by remember { mutableStateOf("") }
        Group {
            ChipRow(PakistanData.COMPANIES, company, { company = it })
            OutlinedTextField(ref, { ref = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Reference / consumer number") })
            OutlinedTextField(label, { label = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Name (e.g. Home, Shop)") })
            Button(enabled = ref.length >= 6, onClick = {
                val b = PakistanData.Bill(System.nanoTime().toString(), company, ref.trim(), label.trim(), "", "")
                PakistanData.saveBills(c, bills + b); bills = PakistanData.bills(c); ref = ""; label = ""
                scope.launch { runCatching { PakistanData.check(b) }.onSuccess { nb -> PakistanData.saveBills(c, bills.map { if (it.id == nb.id) nb else it }); bills = PakistanData.bills(c) } }
            }, modifier = Modifier.padding(top = 6.dp)) { Text("Add bill") }
        }
        bills.forEach { b ->
            Group(Modifier.padding(vertical = 4.dp)) {
                Text("${b.label.ifBlank { b.company }} · ${b.company}", style = MaterialTheme.typography.titleSmall)
                Text(listOfNotNull("Ref ${b.ref}", b.amount.ifBlank { null }?.let { "Rs $it" }, b.due.ifBlank { null }?.let { "due $it" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    OutlinedButton(onClick = {
                        c.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ref", b.ref))
                        runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PakistanData.billUrl(b)))) }
                        if (b.company !in PakistanData.DISCOS) Feedback.show("Reference copied — paste it on the page")
                    }) { Text("View bill") }
                    if (b.company in PakistanData.DISCOS) OutlinedButton(onClick = { scope.launch {
                        runCatching { PakistanData.check(b) }.onSuccess { nb -> PakistanData.saveBills(c, bills.map { if (it.id == nb.id) nb else it }); bills = PakistanData.bills(c)
                            Feedback.show(if (nb.due.isNotBlank()) "Due ${nb.due}" else "Couldn't read the due date — open the bill") }
                            .onFailure { Feedback.failed("Checking the bill", it) } } }) { Text("Refresh") }
                    OutlinedButton(onClick = { PakistanData.saveBills(c, bills.filter { it.id != b.id }); bills = PakistanData.bills(c) }) { Text("Remove") }
                }
            }
        }
        Text("Atlas reminds you two days before each due date.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        Spacer(Modifier.height(50.dp))
    }
}
