package com.nizam.app.feature.next

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nizam.app.AppContainer
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
import com.nizam.app.ui.design.Segmented
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.StatTile
import com.nizam.app.ui.design.pressable
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth

private fun rs(v: Double) = "Rs " + "%,d".format(v.toLong())

private fun shareText(c: Context, text: String) = c.startActivity(Intent.createChooser(
    Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Send"))

// ── Committee (BC) manager ───────────────────────────────────────────────────────────────────────

/**
 * A committee: N members each pay a fixed amount every month; each month one member takes the pot.
 * Atlas tracks who has paid this round, whose turn it is, and sends polite WhatsApp reminders.
 */
object Committees {
    fun store(c: Context) = JsonStore(c, "committees")
    fun members(o: JSONObject): List<String> = o.optJSONArray("members")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
    fun start(o: JSONObject): YearMonth = runCatching { YearMonth.parse(o.optString("start")) }.getOrElse { YearMonth.now() }
    /** 0-based round for this month (the member at that position takes the pot). */
    fun round(o: JSONObject): Int = (java.time.temporal.ChronoUnit.MONTHS.between(start(o), YearMonth.now())).toInt()
    fun paid(o: JSONObject): Set<String> = o.optJSONObject("paid")?.optJSONArray(YearMonth.now().toString())?.let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }.orEmpty()
    /** On collection day, a nudge with who still hasn't paid. */
    fun tick(c: Context, now: Double) {
        if (now !in 10.0..11.0) return
        val today = LocalDate.now()
        val sent = c.getSharedPreferences("committee_sent", 0)
        store(c).all().forEach { o ->
            val day = o.optInt("day", 1).coerceIn(1, 28)
            if (today.dayOfMonth != day || sent.getString(o.optString("id"), "") == today.toString()) return@forEach
            sent.edit().putString(o.optString("id"), today.toString()).apply()
            val unpaid = members(o).filter { it !in paid(o) }
            if (unpaid.isNotEmpty()) com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 420 + (o.optString("id").hashCode() and 15),
                "${o.optString("name")}: collection day", "${unpaid.size} still to pay: ${unpaid.take(4).joinToString()}", route = com.nizam.app.ui.nav.Routes.COMMITTEES)
        }
    }
    fun setPaid(c: Context, id: String, who: String, v: Boolean) = store(c).update(id) { o ->
        val key = YearMonth.now().toString()
        val all = o.optJSONObject("paid") ?: JSONObject()
        val now = paid(o).toMutableSet().apply { if (v) add(who) else remove(who) }
        all.put(key, JSONArray(now.toList())); o.put("paid", all)
    }
}

@Composable
fun CommitteesScreen() {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(Committees.store(ctx).all()) }
    var name by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var people by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    fun reload() { list = Committees.store(ctx).all() }
    AtlasScreen(title = "Committees", subtitle = "BCs: who's paid, whose turn, reminders") {
        if (list.isEmpty() && !adding) item("e") {
            EmptyState(EmptySpec(Icons.Outlined.Groups, "No committees yet", "Add your BC with its members in payout order.", "Add committee") { adding = true })
        }
        list.forEach { o ->
            val id = o.optString("id")
            val members = Committees.members(o)
            val r = Committees.round(o)
            val paid = Committees.paid(o)
            val amt = o.optDouble("amount", 0.0)
            item("c$id") {
                SectionHeader(o.optString("name"), action = "Remind unpaid") {
                    val unpaid = members.filter { it !in paid }
                    shareText(ctx, "Assalam o alaikum! Reminder for ${o.optString("name")} committee: ${rs(amt)} for ${YearMonth.now().month.name.lowercase().replaceFirstChar { it.uppercase() }}. " +
                        "Still pending: ${unpaid.joinToString()}. JazakAllah!")
                }
            }
            item("s$id") {
                Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    StatTile("Pot this month", rs(amt * members.size), Modifier.weight(1f))
                    StatTile("Takes it", members.getOrElse(r) { if (r >= members.size) "Finished" else "—" }, Modifier.weight(1f), caption = "Round ${r + 1} of ${members.size}")
                }
            }
            item("x$id") {
                val me = o.optString("me")
                val myTurn = members.indexOf(me)
                Column(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                    Text("Collected on day ${o.optInt("day", 1)} of each month · ${paid.size}/${members.size} paid" +
                        (if (myTurn >= 0) " · " + when { myTurn == r -> "your turn this month!"; myTurn > r -> "your turn in ${myTurn - r} month${if (myTurn - r > 1) "s" else ""} (${Committees.start(o).plusMonths(myTurn.toLong()).month.name.lowercase().replaceFirstChar { it.uppercase() }})"; else -> "you've received yours" } else ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        androidx.compose.material3.TextButton(onClick = { members.forEach { Committees.setPaid(ctx, id, it, true) }; reload(); com.nizam.app.ui.design.Celebrate.now() }) { Text("Mark all paid") }
                        androidx.compose.material3.TextButton(onClick = {
                            val d = (o.optInt("day", 1) % 28) + 1
                            Committees.store(ctx).update(id) { it.put("day", d) }; reload() }) { Text("Day: ${o.optInt("day", 1)} ↻") }
                        if (members.isNotEmpty()) androidx.compose.material3.TextButton(onClick = {
                            val next = members[(members.indexOf(me) + 1) % members.size]
                            Committees.store(ctx).update(id) { it.put("me", next) }; reload() }) { Text(if (me.isBlank()) "Which one is me?" else "Me: $me ↻") }
                    }
                }
            }
            members.forEachIndexed { i, m ->
                item("m$id$i") {
                    val on = m in paid
                    ListRow(m, supporting = (if (i == r) "Takes the pot this month · " else if (i < r) "Already received · " else "") + if (on) "Paid" else "Not paid yet",
                        icon = if (on) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked, tint = if (on) Hub.Deen.tint() else MaterialTheme.colorScheme.outline) {
                        Committees.setPaid(ctx, id, m, !on); reload()
                        if (!on && Committees.paid(Committees.store(ctx).all().first { it.optString("id") == id }).size == members.size) com.nizam.app.ui.design.Celebrate.now()
                    }
                }
            }
            item("d$id") {
                com.nizam.app.ui.design.HoldToConfirm("delete ${o.optString("name")}", Modifier.fillMaxWidth().padding(horizontal = Space.page)) {
                    Committees.store(ctx).remove(id); reload() }
            }
        }
        if (adding || list.isNotEmpty()) item("add") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                Text("New committee", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Each pays per month (Rs)") }, singleLine = true)
                OutlinedTextField(people, { people = it }, Modifier.fillMaxWidth(), label = { Text("Members in payout order, comma-separated") })
                Button(onClick = {
                    val ms = people.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    if (name.isNotBlank() && ms.size >= 2 && amount.isNotBlank()) {
                        Committees.store(ctx).add(JSONObject().put("name", name.trim()).put("amount", amount.toDouble())
                            .put("members", JSONArray(ms)).put("start", YearMonth.now().toString()))
                        name = ""; amount = ""; people = ""; adding = false; reload()
                    } else Feedback.show("Add a name, amount and at least two members")
                }, modifier = Modifier.padding(top = Space.s)) { Text("Start this month") }
            }
        }
    }
}

// ── Debts: who owes whom ─────────────────────────────────────────────────────────────────────────

object Debts {
    fun store(c: Context) = JsonStore(c, "debts")
    /** On the due date, a nudge: collect what's owed to you, or pay what you owe. */
    fun tick(c: Context, now: Double) {
        if (now !in 10.0..11.0) return
        val today = LocalDate.now().toString()
        val sent = c.getSharedPreferences("debts_sent", 0)
        store(c).all().filter { !it.optBoolean("settled") && it.optString("due") == today && sent.getString(it.optString("id"), "") != today }.forEach { d ->
            sent.edit().putString(d.optString("id"), today).apply()
            val inbound = d.optString("dir") == "in"
            com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 410 + (d.optString("id").hashCode() and 31),
                if (inbound) "${d.optString("who")} owes you ${rs(d.optDouble("amount"))} today" else "Due today: ${rs(d.optDouble("amount"))} to ${d.optString("who")}",
                d.optString("note").ifBlank { "Tap to open Lent & borrowed" }, route = com.nizam.app.ui.nav.Routes.DEBTS)
        }
    }
}

@Composable
fun DebtsScreen() {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf(Debts.store(ctx).all()) }
    var who by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var dir by remember { mutableStateOf("They owe me") }
    var note by remember { mutableStateOf("") }
    var dueIn by remember { mutableStateOf("No date") }
    var paying by remember { mutableStateOf<JSONObject?>(null) }
    var payAmt by remember { mutableStateOf("") }
    var showSettled by remember { mutableStateOf(false) }
    fun reload() { list = Debts.store(ctx).all() }
    val open = list.filter { !it.optBoolean("settled") }
    val owed = open.filter { it.optString("dir") == "in" }.sumOf { it.optDouble("amount") }
    val owe = open.filter { it.optString("dir") == "out" }.sumOf { it.optDouble("amount") }
    AtlasScreen(title = "Lent & borrowed", subtitle = "Loans, udhaar and instalments in one place") {
        item("s") {
            Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                StatTile("Owed to you", rs(owed), Modifier.weight(1f), tint = Hub.Deen.tint())
                StatTile("You owe", rs(owe), Modifier.weight(1f), tint = MaterialTheme.colorScheme.error)
            }
        }
        item("h") { SectionHeader("Open") }
        if (open.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.SwapHoriz, "All square", "Nobody owes anybody. Add one below.")) }
        open.sortedByDescending { it.optDouble("amount") }.forEach { d ->
            item(d.optString("id")) {
                val inbound = d.optString("dir") == "in"
                ListRow("${d.optString("who")} · ${rs(d.optDouble("amount"))}",
                    supporting = (if (inbound) "Owes you" else "You owe") + d.optString("note").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty() +
                        " · since ${d.optString("date")}" + d.optString("due").takeIf { it.isNotBlank() }?.let { due -> if (due < LocalDate.now().toString()) " · ⚠ was due $due" else " · due $due" }.orEmpty() +
                        (d.optJSONArray("payments")?.length()?.takeIf { it > 0 }?.let { n -> " · $n part-payment${if (n > 1) "s" else ""}" } ?: ""), icon = Icons.Outlined.SwapHoriz,
                    tint = if (inbound) Hub.Deen.tint() else MaterialTheme.colorScheme.error,
                    trailing = {
                        Row {
                            if (inbound) Text("Remind", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(Space.s).pressable {
                                    shareText(ctx, "Hi ${d.optString("who")}, just a gentle reminder about the ${rs(d.optDouble("amount"))}" +
                                        d.optString("note").takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty() + ". Whenever convenient, thanks!") })
                            Text("Part", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(Space.s).pressable { paying = d; payAmt = "" })
                            Text("Settle", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(Space.s).pressable {
                                    Debts.store(ctx).update(d.optString("id")) { it.put("settled", true) }; reload(); com.nizam.app.ui.design.Celebrate.now()
                                    Feedback.undo("Settled") { Debts.store(ctx).update(d.optString("id")) { it.put("settled", false) }; reload() } })
                        }
                    })
            }
        }
        val settled = list.filter { it.optBoolean("settled") }
        if (settled.isNotEmpty()) item("sh") {
            SectionHeader("Settled (${settled.size}) · ${rs(settled.sumOf { it.optDouble("original", it.optDouble("amount")) })}", action = if (showSettled) "Hide" else "Show") { showSettled = !showSettled }
        }
        if (showSettled) settled.forEach { d -> item("st" + d.optString("id")) {
            ListRow("${d.optString("who")} · ${rs(d.optDouble("original", d.optDouble("amount")))}", supporting = (if (d.optString("dir") == "in") "Paid you back" else "You paid back") +
                " · from ${d.optString("date")}", icon = Icons.Outlined.CheckCircle, tint = MaterialTheme.colorScheme.outline)
        } }
        paying?.let { d -> item("pay") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text("Part payment: ${d.optString("who")} (${rs(d.optDouble("amount"))} left)", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(payAmt, { payAmt = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.fillMaxWidth(), label = { Text("Amount paid (Rs)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(top = Space.s)) {
                    Button(onClick = {
                        val a = payAmt.toDoubleOrNull() ?: return@Button
                        Debts.store(ctx).update(d.optString("id")) { o ->
                            if (!o.has("original")) o.put("original", o.optDouble("amount"))
                            val left = (o.optDouble("amount") - a).coerceAtLeast(0.0)
                            o.put("amount", left).put("payments", (o.optJSONArray("payments") ?: JSONArray()).put(JSONObject().put("amt", a).put("date", LocalDate.now().toString())))
                            if (left <= 0.0) o.put("settled", true)
                        }
                        paying = null; reload(); Feedback.show("Recorded ${rs(a)}")
                    }) { Text("Record") }
                    androidx.compose.material3.TextButton(onClick = { paying = null }) { Text("Cancel") }
                }
            }
        } }
        item("add") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                Segmented(listOf("They owe me", "I owe them"), dir, { dir = it }, { it })
                OutlinedTextField(who, { who = it }, Modifier.fillMaxWidth(), label = { Text("Who") }, singleLine = true)
                OutlinedTextField(amount, { amount = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.fillMaxWidth(), label = { Text("Amount (Rs)") }, singleLine = true)
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("For (optional)") }, singleLine = true)
                Text("Due", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = Space.s))
                Segmented(listOf("No date", "1 week", "2 weeks", "1 month"), dueIn, { dueIn = it }, { it })
                Button(onClick = {
                    val a = amount.toDoubleOrNull()
                    if (who.isNotBlank() && a != null) {
                        val due = when (dueIn) { "1 week" -> LocalDate.now().plusWeeks(1); "2 weeks" -> LocalDate.now().plusWeeks(2); "1 month" -> LocalDate.now().plusMonths(1); else -> null }
                        Debts.store(ctx).add(JSONObject().put("who", who.trim()).put("amount", a).put("original", a).put("note", note.trim())
                            .put("dir", if (dir == "They owe me") "in" else "out").put("date", LocalDate.now().toString()).put("due", due?.toString() ?: ""))
                        who = ""; amount = ""; note = ""; dueIn = "No date"; reload()
                    }
                }, modifier = Modifier.padding(top = Space.s)) { Text("Add") }
            }
        }
    }
}

// ── Subscriptions ────────────────────────────────────────────────────────────────────────────────

/** Subscriptions you add yourself, with a reminder the day before each renewal. */
object Subs {
    fun store(c: Context) = JsonStore(c, "subs_manual")
    fun tick(c: Context, now: Double) {
        if (now !in 10.0..11.0) return
        val today = LocalDate.now()
        val s = store(c)
        s.all().filter { !it.optBoolean("cancelled") }.forEach { m ->
            var next = runCatching { LocalDate.parse(m.optString("next")) }.getOrNull() ?: return@forEach
            val days = m.optInt("days", 30).toLong()
            var moved = false
            while (next.isBefore(today)) { next = next.plusDays(days); moved = true }
            if (moved) s.update(m.optString("id")) { it.put("next", next.toString()) }
            if (next == today.plusDays(1) && m.optString("warned") != next.toString()) {
                s.update(m.optString("id")) { it.put("warned", next.toString()) }
                com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 430 + (m.optString("id").hashCode() and 15),
                    "${m.optString("name")} renews tomorrow", "${rs(m.optDouble("amount"))}. Still using it?", route = com.nizam.app.ui.nav.Routes.SUBSCRIPTIONS)
            }
        }
    }
}

@Composable
fun SubscriptionsScreen(container: AppContainer) {
    var found by remember { mutableStateOf<List<com.nizam.app.feature.jarvis.Recurring>?>(null) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { found = runCatching { com.nizam.app.feature.jarvis.BillRadar.find(container) }.getOrElse { emptyList() } } }
    val ctx = LocalContext.current
    var manual by remember { mutableStateOf(Subs.store(ctx).all()) }
    var sName by remember { mutableStateOf("") }
    var sAmt by remember { mutableStateOf("") }
    var sEvery by remember { mutableStateOf("Monthly") }
    val manualMonthly = manual.filter { !it.optBoolean("cancelled") }.sumOf { it.optDouble("amount") * 30.0 / it.optInt("days", 30).coerceAtLeast(1) }
    val monthly = found.orEmpty().sumOf { it.amount * 30.0 / it.everyDays.coerceAtLeast(1) } + manualMonthly
    AtlasScreen(title = "Subscriptions", subtitle = "Recurring charges found in your own spending", loading = found == null) {
        item("s") {
            Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                StatTile("Per month", rs(monthly), Modifier.weight(1f))
                StatTile("Per year", rs(monthly * 12), Modifier.weight(1f))
            }
        }
        if (found?.isEmpty() == true) item("e") {
            EmptyState(EmptySpec(Icons.Outlined.Autorenew, "Nothing recurring yet", "Once a charge repeats two or three times at a steady rhythm, it shows up here."))
        }
        found.orEmpty().sortedByDescending { it.amount * 30.0 / it.everyDays }.forEach { r ->
            item(r.name) {
                ListRow(r.name.replaceFirstChar { it.uppercase() }, supporting = "${rs(r.amount)} every ${r.everyDays} days · next ${r.next} · seen ${r.count}×",
                    icon = Icons.Outlined.Autorenew)
            }
        }
        if (manual.isNotEmpty()) item("mh") { SectionHeader("Added by you") }
        manual.sortedBy { it.optString("next") }.forEach { m ->
            item("m" + m.optString("id")) {
                val off = m.optBoolean("cancelled")
                ListRow(m.optString("name") + if (off) " (cancelled)" else "", supporting = "${rs(m.optDouble("amount"))} every ${m.optInt("days")} days · renews ${m.optString("next")} · reminder the day before",
                    icon = Icons.Outlined.Autorenew, trailing = {
                        Text(if (off) "Remove" else "Cancelled", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(Space.s).pressable {
                                if (off) Subs.store(ctx).remove(m.optString("id")) else Subs.store(ctx).update(m.optString("id")) { it.put("cancelled", true) }
                                manual = Subs.store(ctx).all() })
                    })
            }
        }
        item("madd") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text("Add one yourself (Netflix, gym, internet…)", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(sName, { sName = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                OutlinedTextField(sAmt, { sAmt = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.fillMaxWidth(), label = { Text("Amount (Rs)") }, singleLine = true)
                Segmented(listOf("Weekly", "Monthly", "Yearly"), sEvery, { sEvery = it }, { it })
                Button(onClick = {
                    val a = sAmt.toDoubleOrNull()
                    if (sName.isNotBlank() && a != null) {
                        val days = when (sEvery) { "Weekly" -> 7; "Yearly" -> 365; else -> 30 }
                        Subs.store(ctx).add(JSONObject().put("name", sName.trim()).put("amount", a).put("days", days).put("next", LocalDate.now().plusDays(days.toLong()).toString()))
                        sName = ""; sAmt = ""; manual = Subs.store(ctx).all()
                    }
                }, modifier = Modifier.padding(top = Space.s)) { Text("Add") }
            }
        }
        item("tip") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                Text("Not using one? Cancel it in its app or with your bank, then ask Atlas to stop tracking it.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// ── Price tracker ────────────────────────────────────────────────────────────────────────────────

/** Watches product pages (Daraz, PriceOye, OLX and most shops) and tells you when the price drops. */
object PriceWatch {
    fun store(c: Context) = JsonStore(c, "pricewatch")

    /** Finds the price on a product page: structured data first, then the page's meta tags, then the first "Rs" figure. */
    fun extract(html: String): Pair<String, Double?> {
        val title = Regex("<title[^>]*>([^<]{3,160})</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim()?.replace(Regex("\\s+"), " ") ?: "Product"
        val candidates = listOf(
            Regex("\"price\"\\s*:\\s*\"?([0-9][0-9,]*(?:\\.[0-9]+)?)"),
            Regex("product:price:amount\"\\s+content=\"([0-9][0-9,.]*)"),
            Regex("itemprop=\"price\"[^>]*content=\"([0-9][0-9,.]*)"),
            Regex("(?:Rs\\.?|PKR)\\s*([0-9][0-9,]{2,})")
        )
        val price = candidates.firstNotNullOfOrNull { r -> r.find(html)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0 } }
        return title to price
    }

    suspend fun refresh(c: Context, o: JSONObject): Double? = runCatching {
        val html = Http.getText(o.optString("url"), mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"))
        extract(html).second
    }.getOrNull()

    /** Daily from the background check: new lows and targets reached become a notification. */
    suspend fun check(c: Context) {
        val today = LocalDate.now().toString()
        store(c).all().forEach { o ->
            if (o.optString("checked") == today) return@forEach
            val p = refresh(c, o) ?: return@forEach
            val low = o.optDouble("low", p)
            val target = o.optDouble("target", 0.0)
            store(c).update(o.optString("id")) { it.put("price", p).put("checked", today).put("low", minOf(low, p))
                it.put("history", (it.optJSONArray("history") ?: JSONArray()).put(JSONObject().put("d", today).put("p", p))) }
            if ((target > 0 && p <= target) || p < low)
                Notifications.post(c, Notifications.CHANNEL_NUDGE, 800 + (o.optString("id").hashCode() and 0x3F),
                    "Price drop: ${o.optString("title").take(40)}", "Now ${rs(p)}" + (if (p < low) " — lowest yet (was ${rs(low)})" else "") +
                        if (target > 0 && p <= target) " · your target was ${rs(target)}" else "")
        }
    }
}

@Composable
fun PriceWatchScreen() {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var list by remember { mutableStateOf(PriceWatch.store(ctx).all()) }
    var url by remember { mutableStateOf("") }
    var target by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    fun reload() { list = PriceWatch.store(ctx).all() }
    AtlasScreen(title = "Price tracker", subtitle = "Paste a product link. Atlas checks daily and tells you when it drops.") {
        item("add") {
            Column(Modifier.padding(horizontal = Space.page)) {
                OutlinedTextField(url, { url = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Product link") }, singleLine = true)
                OutlinedTextField(target, { target = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Alert me below (Rs, optional)") }, singleLine = true)
                Button(onClick = {
                    if (!url.startsWith("http")) { Feedback.show("Paste a full link starting with http"); return@Button }
                    busy = true
                    scope.launch {
                        val (title, price) = runCatching {
                            PriceWatch.extract(Http.getText(url, mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")))
                        }.getOrElse { "Product" to null }
                        PriceWatch.store(ctx).add(JSONObject().put("url", url).put("title", title).put("price", price ?: 0.0).put("low", price ?: 0.0)
                            .put("target", target.toDoubleOrNull() ?: 0.0).put("checked", LocalDate.now().toString()))
                        if (price == null) Feedback.show("Added. Couldn't read the price yet; Atlas will retry daily.")
                        url = ""; target = ""; busy = false; reload()
                    }
                }, enabled = !busy, modifier = Modifier.padding(top = Space.s)) { Text(if (busy) "Reading page…" else "Watch this") }
            }
        }
        if (list.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.Sell, "Nothing watched", "Works with Daraz, PriceOye, OLX and most shop pages.")) }
        else item("h") { SectionHeader("Watching ${list.size}") }
        list.forEach { o ->
            item(o.optString("id")) {
                val p = o.optDouble("price", 0.0); val low = o.optDouble("low", p)
                ListRow(o.optString("title").take(60), supporting = (if (p > 0) "Now ${rs(p)}" else "Price not read yet") +
                    (if (low in 1.0..<p) " · low ${rs(low)}" else "") + (o.optDouble("target", 0.0).takeIf { it > 0 }?.let { " · target ${rs(it)}" } ?: ""),
                    icon = Icons.Outlined.Sell, trailing = {
                        Text("Remove", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.padding(Space.s).pressable { PriceWatch.store(ctx).remove(o.optString("id")); reload() })
                    }) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(o.optString("url")))) }
                }
            }
        }
    }
}
