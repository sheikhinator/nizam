package com.nizam.app.feature.next

import androidx.compose.ui.unit.dp

import kotlinx.coroutines.launch

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.CurrencyExchange
import androidx.compose.material.icons.outlined.LocalHospital
import androidx.compose.material.icons.outlined.LocalPharmacy
import androidx.compose.material.icons.outlined.Luggage
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.net.Http
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.EmptySpec
import com.nizam.app.ui.design.EmptyState
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.StatTile
import com.nizam.app.ui.design.pressable
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit

private fun rs(v: Double) = "Rs " + "%,d".format(v.toLong())

// ── Medicine reminders ───────────────────────────────────────────────────────────────────────────

/** Medicines with their times; a reminder at each dose, a refill warning when the strip runs low. */
object Medicines {
    fun store(c: Context) = JsonStore(c, "medicines")
    private fun tp(c: Context) = c.getSharedPreferences("med_taken", 0)
    fun taken(c: Context, d: LocalDate, id: String, t: LocalTime) = tp(c).getBoolean("$d|$id|$t", false)
    fun setTaken(c: Context, d: LocalDate, id: String, t: LocalTime, v: Boolean) = tp(c).edit().putBoolean("$d|$id|$t", v).apply()
    /** Share of doses ticked over the last 7 days, counting only days after the medicine was added. */
    fun adherence(c: Context, o: JSONObject): Int? {
        val added = java.time.Instant.ofEpochMilli(o.optLong("t", System.currentTimeMillis())).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        var due = 0; var ok = 0
        for (i in 1..7) { val d = LocalDate.now().minusDays(i.toLong()); if (d.isBefore(added)) continue
            times(o).forEach { t -> due++; if (taken(c, d, o.optString("id"), t)) ok++ } }
        return if (due == 0) null else ok * 100 / due
    }
    fun times(o: JSONObject): List<LocalTime> = o.optString("times").split(",").mapNotNull { runCatching { LocalTime.parse(it.trim().padStart(5, '0')) }.getOrNull() }

    fun check(c: Context) {
        val now = LocalTime.now()
        val p = c.getSharedPreferences("med_fired", 0)
        store(c).all().forEach { o ->
            times(o).forEach { t ->
                val mins = ChronoUnit.MINUTES.between(t, now)
                val key = "${LocalDate.now()}|${o.optString("id")}|$t"
                if (mins in 0..14 && !p.getBoolean(key, false)) {
                    p.edit().putBoolean(key, true).apply()
                    val left = o.optInt("left", -1)
                    Notifications.post(c, Notifications.CHANNEL_NUDGE, 820 + (o.optString("id").hashCode() and 0x3F),
                        "Time for ${o.optString("name")}", o.optString("dose").ifBlank { "Your $t dose" } +
                            if (left in 0..5) " · only $left left, time to refill" else "")
                    if (left > 0) store(c).update(o.optString("id")) { it.put("left", left - 1) }
                }
            }
        }
    }
}

@Composable
fun MedicinesScreen() {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(Medicines.store(ctx).all()) }
    var name by remember { mutableStateOf("") }
    var dose by remember { mutableStateOf("") }
    var times by remember { mutableStateOf("08:00, 20:00") }
    var left by remember { mutableStateOf("") }
    fun reload() { list = Medicines.store(ctx).all() }
    AtlasScreen(title = "Medicines", subtitle = "A reminder at every dose, a nudge before you run out") {
        if (list.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.LocalPharmacy, "No medicines", "Add one below with its times.")) }
        list.forEach { o ->
            item(o.optString("id")) {
                ListRow(o.optString("name"), supporting = listOf(o.optString("dose"), o.optString("times"),
                    o.optInt("left", -1).takeIf { it >= 0 }?.let { "$it left" }.orEmpty(),
                    Medicines.adherence(ctx, o)?.let { "$it% taken this week" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                    icon = Icons.Outlined.LocalPharmacy, tint = Hub.Health.tint(), trailing = {
                        Text("Remove", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(Space.s).pressable { Medicines.store(ctx).remove(o.optString("id")); reload() })
                    })
            }
            item("today" + o.optString("id")) {
                var tick by remember { mutableStateOf(0) }
                androidx.compose.foundation.layout.Row(Modifier.padding(start = Space.page + 40.dp, end = Space.page), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    Medicines.times(o).forEach { t ->
                        val on = remember(tick) { Medicines.taken(ctx, LocalDate.now(), o.optString("id"), t) }
                        com.nizam.app.ui.design.Capsule((if (on) "✓ " else "") + t.toString(), on) { Medicines.setTaken(ctx, LocalDate.now(), o.optString("id"), t, !on); tick++ }
                    }
                    if (o.optInt("left", -1) in 0..10) com.nizam.app.ui.design.Capsule("+ Refill 10", false) {
                        Medicines.store(ctx).update(o.optString("id")) { it.put("left", it.optInt("left", 0) + 10) }; reload() }
                }
            }
        }
        item("add") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Medicine") }, singleLine = true)
                OutlinedTextField(dose, { dose = it }, Modifier.fillMaxWidth(), label = { Text("Dose (e.g. 1 tablet after food)") }, singleLine = true)
                OutlinedTextField(times, { times = it }, Modifier.fillMaxWidth(), label = { Text("Times, comma-separated (24h)") }, singleLine = true)
                OutlinedTextField(left, { left = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Tablets left (optional)") }, singleLine = true)
                Button(onClick = {
                    if (name.isNotBlank()) {
                        Medicines.store(ctx).add(JSONObject().put("name", name.trim()).put("dose", dose.trim()).put("times", times.trim()).put("left", left.toIntOrNull() ?: -1).put("t", System.currentTimeMillis()))
                        name = ""; dose = ""; left = ""; reload()
                    }
                }, modifier = Modifier.padding(top = Space.s)) { Text("Add") }
            }
        }
    }
}

// ── Emergency hub ────────────────────────────────────────────────────────────────────────────────

/** Pakistan's emergency numbers, one tap each, working without internet. */
@Composable
fun EmergencyScreen(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val numbers = listOf(
        "Rescue 1122" to "1122", "Police (Madadgar)" to "15", "Edhi ambulance" to "115", "Fire brigade" to "16",
        "Chhipa ambulance" to "1020", "Motorway police" to "130", "Aman Foundation (Karachi)" to "1021",
        "Women's helpline (Punjab)" to "1043", "Child protection" to "1121", "Gas emergency (SNGPL)" to "1199"
    )
    AtlasScreen(title = "Emergency", subtitle = "Tap to call. Works offline.") {
        item("sos") {
            Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Button(onClick = { onOpen("power/guardian") }) { Text("SOS to my contacts") }
                OutlinedButton(onClick = { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=hospital"))) } }) { Text("Nearest hospital") }
            }
        }
        item("offline-help-card") { OfflineHelpCard() }
        item("h") { SectionHeader("Numbers") }
        numbers.forEach { (name, num) ->
            item(num) {
                ListRow(name, supporting = num, icon = if (name.contains("ambulance") || name.contains("1122")) Icons.Outlined.LocalHospital else Icons.Outlined.Phone,
                    tint = MaterialTheme.colorScheme.error) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num"))) }
                }
            }
        }
        item("fa") { SectionHeader("First aid") }
        item("fa2") { ListRow("Offline first-aid guides", supporting = "Bleeding, burns, choking, CPR, snake bite and more") { onOpen("survival") } }
    }
}

// ── Document expiry ──────────────────────────────────────────────────────────────────────────────

/** CNIC, passport, licence, token tax, insurance: a reminder a month before each one lapses. */
object DocExpiry {
    fun store(c: Context) = JsonStore(c, "docexpiry")
    fun check(c: Context) {
        val p = c.getSharedPreferences("doc_fired", 0)
        store(c).all().forEach { o ->
            val d = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull() ?: return@forEach
            val days = ChronoUnit.DAYS.between(LocalDate.now(), d)
            listOf(30L, 7L, 1L).firstOrNull { days in 0..it }?.let { w ->
                val key = "${o.optString("id")}|$w"
                if (!p.getBoolean(key, false)) {
                    p.edit().putBoolean(key, true).apply()
                    Notifications.post(c, Notifications.CHANNEL_NUDGE, 840 + (o.optString("id").hashCode() and 0x1F),
                        "${o.optString("name")} expires in $days day${if (days == 1L) "" else "s"}", "On $d. Start the renewal now to avoid fines or queues.")
                }
            }
        }
    }
}

@Composable
fun DocExpiryScreen() {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(DocExpiry.store(ctx).all()) }
    var name by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    fun reload() { list = DocExpiry.store(ctx).all() }
    AtlasScreen(title = "Expiry dates", subtitle = "Reminders 30, 7 and 1 day before") {
        list.sortedBy { it.optString("date") }.forEach { o ->
            item(o.optString("id")) {
                val d = runCatching { LocalDate.parse(o.optString("date")) }.getOrNull()
                val days = d?.let { ChronoUnit.DAYS.between(LocalDate.now(), it) }
                ListRow(o.optString("name"), supporting = "${o.optString("date")} · " + when {
                    days == null -> "no date"; days < 0 -> "expired ${-days} days ago"; days == 0L -> "expires today"; else -> "in $days days" },
                    icon = Icons.Outlined.Badge, tint = if (days != null && days < 30) MaterialTheme.colorScheme.error else Hub.Tools.tint(), trailing = {
                        Text("Remove", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(Space.s).pressable { DocExpiry.store(ctx).remove(o.optString("id")); reload() })
                    })
            }
            item("r" + o.optString("id")) {
                var how by remember { mutableStateOf<String?>(null) }
                var busy by remember { mutableStateOf(false) }
                val scope = com.nizam.app.ui.design.rememberSafeScope()
                androidx.compose.foundation.layout.Column(Modifier.padding(start = Space.page + 40.dp, end = Space.page)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(1, 5, 10).forEach { y -> com.nizam.app.ui.design.Capsule("Renewed +${y}y", false) {
                            val base = runCatching { LocalDate.parse(o.optString("date")) }.getOrDefault(LocalDate.now()).let { if (it.isBefore(LocalDate.now())) LocalDate.now() else it }
                            DocExpiry.store(ctx).update(o.optString("id")) { it.put("date", base.plusYears(y.toLong()).toString()) }; reload()
                            Feedback.show("Next expiry ${base.plusYears(y.toLong())}") } }
                        com.nizam.app.ui.design.Capsule(if (busy) "…" else "How to renew", how != null) {
                            if (how != null) { how = null; return@Capsule }
                            val ct = (ctx.applicationContext as? com.nizam.app.NizamApplication)?.container ?: return@Capsule
                            busy = true
                            scope.launch {
                                how = runCatching { com.nizam.app.net.Ai.generate(ct.settingsRepository, "How do I renew my ${o.optString("name")} in Pakistan? Steps, documents to bring, current fee, where to go or which website/app, and how long it takes. Short.",
                                    system = "You are a practical Pakistan paperwork guide. Check current details.", maxOutputTokens = 700, useWeb = true) }.getOrElse { it.message ?: "Couldn't look that up" }
                                busy = false
                            }
                        }
                    }
                    how?.let { com.nizam.app.feature.nx.Md(it) }
                }
            }
        }
        item("add") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    listOf("CNIC", "Passport", "Driving licence", "Token tax", "Car insurance").forEach { s ->
                        Text(s, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium, modifier = Modifier.pressable { name = s }.padding(Space.xs))
                    }
                }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Document") }, singleLine = true)
                OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), label = { Text("Expiry (yyyy-mm-dd)") }, singleLine = true)
                Button(onClick = {
                    if (name.isNotBlank() && runCatching { LocalDate.parse(date) }.isSuccess) {
                        DocExpiry.store(ctx).add(JSONObject().put("name", name.trim()).put("date", date.trim())); name = ""; date = ""; reload()
                    } else Feedback.show("Use a date like 2027-03-14")
                }, modifier = Modifier.padding(top = Space.s)) { Text("Add") }
            }
        }
    }
}

// ── Trip & group splitter ────────────────────────────────────────────────────────────────────────

/** Who paid what on a trip or dinner, and the fewest payments to settle it. */
object Splits {
    fun store(c: Context) = JsonStore(c, "splits")

    /** Greedy settlement: biggest debtor pays biggest creditor until everyone's square. */
    fun settle(people: List<String>, paid: Map<String, Double>): List<Triple<String, String, Double>> {
        val total = paid.values.sum(); val share = total / people.size.coerceAtLeast(1)
        val bal = people.associateWith { (paid[it] ?: 0.0) - share }.toMutableMap()
        val out = mutableListOf<Triple<String, String, Double>>()
        repeat(people.size * 2) {
            val d = bal.minByOrNull { it.value } ?: return out
            val cr = bal.maxByOrNull { it.value } ?: return out
            if (d.value > -1 || cr.value < 1) return out
            val amt = minOf(-d.value, cr.value)
            out += Triple(d.key, cr.key, amt)
            bal[d.key] = d.value + amt; bal[cr.key] = cr.value - amt
        }
        return out
    }
}

@Composable
fun SplitsScreen() {
    val ctx = LocalContext.current
    var trips by remember { mutableStateOf(Splits.store(ctx).all()) }
    var name by remember { mutableStateOf("") }
    var people by remember { mutableStateOf("") }
    var who by remember { mutableStateOf("") }
    var amt by remember { mutableStateOf("") }
    var what by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<String?>(null) }
    fun reload() { trips = Splits.store(ctx).all() }
    val trip = trips.firstOrNull { it.optString("id") == open }
    AtlasScreen(title = trip?.optString("name") ?: "Split costs", subtitle = if (trip == null) "Trips, dinners, shared rent" else "Everyone's share and who pays whom") {
        if (trip == null) {
            trips.forEach { t ->
                item(t.optString("id")) {
                    val spent = (t.optJSONArray("items") ?: JSONArray()).let { a -> (0 until a.length()).sumOf { a.getJSONObject(it).optDouble("amt") } }
                    ListRow(t.optString("name"), supporting = "${rs(spent)} · ${(t.optJSONArray("people")?.length() ?: 0)} people", icon = Icons.Outlined.Luggage) { open = t.optString("id") }
                }
            }
            item("new") {
                Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Trip or event") }, singleLine = true)
                    OutlinedTextField(people, { people = it }, Modifier.fillMaxWidth(), label = { Text("People, comma-separated (include yourself)") })
                    Button(onClick = {
                        val ps = people.split(",").map { it.trim() }.filter { it.isNotBlank() }
                        if (name.isNotBlank() && ps.size >= 2) {
                            val o = Splits.store(ctx).add(JSONObject().put("name", name.trim()).put("people", JSONArray(ps)).put("items", JSONArray()))
                            name = ""; people = ""; reload(); open = o.optString("id")
                        }
                    }, modifier = Modifier.padding(top = Space.s)) { Text("Start") }
                }
            }
        } else {
            val ps = trip.optJSONArray("people")!!.let { a -> (0 until a.length()).map { a.getString(it) } }
            val its = (trip.optJSONArray("items") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            val paid = ps.associateWith { p -> its.filter { it.optString("who") == p }.sumOf { it.optDouble("amt") } }
            val moves = Splits.settle(ps, paid)
            item("stats") {
                Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    StatTile("Total", rs(paid.values.sum()), Modifier.weight(1f))
                    StatTile("Each", rs(paid.values.sum() / ps.size), Modifier.weight(1f))
                }
            }
            item("mh") { SectionHeader("Settle up", action = if (moves.isEmpty()) null else "Send") {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                    "${trip.optString("name")} — settle up:\n" + moves.joinToString("\n") { "${it.first} → ${it.second}: ${rs(it.third)}" }), "Send"))
            } }
            if (moves.isEmpty()) item("sq") { ListRow("Everyone's square") }
            moves.forEachIndexed { i, (a, b, x) -> item("mv$i") { ListRow("$a pays $b", supporting = rs(x)) } }
            item("ih") { SectionHeader("Spent") }
            its.forEachIndexed { i, it -> item("it$i") { ListRow("${it.optString("what").ifBlank { "Something" }} · ${rs(it.optDouble("amt"))}", supporting = "Paid by ${it.optString("who")}") } }
            item("add") {
                Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        ps.forEach { p -> com.nizam.app.ui.design.Capsule(p, who == p) { who = p } }
                    }
                    OutlinedTextField(what, { what = it }, Modifier.fillMaxWidth(), label = { Text("For") }, singleLine = true)
                    OutlinedTextField(amt, { amt = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.fillMaxWidth(), label = { Text("Amount") }, singleLine = true)
                    Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Button(onClick = {
                            val a = amt.toDoubleOrNull()
                            if (who in ps && a != null) {
                                Splits.store(ctx).update(trip.optString("id")) { t -> t.put("items", (t.optJSONArray("items") ?: JSONArray()).put(JSONObject().put("who", who).put("amt", a).put("what", what.trim()))) }
                                amt = ""; what = ""; reload()
                            } else Feedback.show("Pick who paid and the amount")
                        }) { Text("Add") }
                        OutlinedButton(onClick = { open = null }) { Text("All trips") }
                    }
                }
            }
        }
    }
}

// ── Rates: dollar and more ───────────────────────────────────────────────────────────────────────

/** Live rupee rates from a free public source, cached so the last known rate shows offline. */
object Rates {
    private fun p(c: Context) = c.getSharedPreferences("rates", 0)
    suspend fun fetch(c: Context): JSONObject? = runCatching {
        val j = JSONObject(Http.getJson("https://open.er-api.com/v6/latest/PKR"))
        p(c).edit().putString("last", j.toString()).apply(); j
    }.getOrNull() ?: runCatching { JSONObject(p(c).getString("last", "")!!) }.getOrNull()
}

@Composable
fun RatesScreen() {
    val ctx = LocalContext.current
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var amount by remember { mutableStateOf("100") }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { data = Rates.fetch(ctx) } }
    val r = data?.optJSONObject("rates")
    val codes = listOf("USD" to "US dollar", "SAR" to "Saudi riyal", "AED" to "UAE dirham", "GBP" to "Pound", "EUR" to "Euro",
        "CAD" to "Canadian dollar", "AUD" to "Australian dollar", "CNY" to "Chinese yuan", "TRY" to "Turkish lira", "MYR" to "Ringgit")
    AtlasScreen(title = "Rates", subtitle = data?.optString("time_last_update_utc")?.take(16)?.let { "Updated $it" } ?: "Loading…", loading = data == null) {
        item("amt") {
            OutlinedTextField(amount, { amount = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.fillMaxWidth().padding(horizontal = Space.page),
                label = { Text("Amount in foreign currency") }, singleLine = true)
        }
        if (r == null && data != null) item("e") { EmptyState(EmptySpec(Icons.Outlined.CurrencyExchange, "No rates", "Connect once and they stay available offline.")) }
        if (r != null) codes.forEach { (code, label) ->
            item(code) {
                val per = r.optDouble(code, 0.0).takeIf { it > 0 }?.let { 1 / it } ?: 0.0
                ListRow("$label ($code)", supporting = "1 $code = Rs ${"%,.2f".format(per)}", icon = Icons.Outlined.CurrencyExchange, trailing = {
                    Text(rs(per * (amount.toDoubleOrNull() ?: 0.0)), style = MaterialTheme.typography.titleMedium)
                })
            }
        }
        item("n") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                Text("Interbank reference rates. The open-market rate at exchange companies can differ by a few rupees.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
