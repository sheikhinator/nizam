package com.nizam.app.feature.nx

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.finance.Txn
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * The money desk: accounts with real balances and transfers between them, other currencies, a
 * correction desk, reconciliation against your bank's number, a 90-day cash-flow flight plan and
 * subscription price-change alerts.
 */
object MoneyDesk {
    private fun p(c: Context) = c.getSharedPreferences("money_desk", Context.MODE_PRIVATE)
    const val TRANSFER = "Transfer"

    fun opening(c: Context, account: String) = p(c).getFloat("open_$account", 0f).toDouble()
    fun setOpening(c: Context, account: String, v: Double) = p(c).edit().putFloat("open_$account", v.toFloat()).apply()
    fun extraAccounts(c: Context) = p(c).getStringSet("accounts", setOf("Cash", "Bank", "JazzCash", "Easypaisa"))!!.toSortedSet()
    fun addAccount(c: Context, name: String) = p(c).edit().putStringSet("accounts", extraAccounts(c) + name.trim()).apply()

    fun accounts(c: Context, txns: List<Txn>) = (extraAccounts(c) + txns.map { it.account }).filter { it.isNotBlank() }.toSortedSet()

    fun balance(c: Context, txns: List<Txn>, account: String) =
        opening(c, account) + txns.filter { it.account == account }.sumOf { if (it.kind == "INCOME") it.amount else -it.amount }

    /** Monthly averages over the last 90 days, ignoring transfers. */
    fun averages(txns: List<Txn>): Pair<Double, Double> {
        val since = LocalDate.now().minusDays(90).toString()
        val recent = txns.filter { it.localDate >= since && it.category != TRANSFER }
        val inc = recent.filter { it.kind == "INCOME" }.sumOf { it.amount } / 3
        val exp = recent.filter { it.kind == "EXPENSE" }.sumOf { it.amount } / 3
        return inc to exp
    }

    data class Change(val what: String, val before: Double, val now: Double)

    /** Repeating charges (same category and note each month) whose amount moved by 5% or more. */
    fun priceChanges(txns: List<Txn>): List<Change> =
        txns.filter { it.kind == "EXPENSE" && it.category != TRANSFER && it.note.isNotBlank() }
            .groupBy { it.category.lowercase() + "|" + it.note.lowercase().replace(Regex("[0-9]"), "").trim().take(24) }
            .mapNotNull { (_, list) ->
                val byMonth = list.sortedBy { it.localDate }.groupBy { it.localDate.take(7) }
                if (byMonth.size < 2) return@mapNotNull null
                val months = byMonth.keys.sorted()
                val last = byMonth[months.last()]!!.sumOf { it.amount }; val prev = byMonth[months[months.size - 2]]!!.sumOf { it.amount }
                if (prev > 0 && kotlin.math.abs(last - prev) / prev >= 0.05) Change(list.last().note.take(40), prev, last) else null
            }
}

object MoneyDeskSpecs {
    val all: List<Spec> = listOf(
        ScreenSpec("moneydesk", "Money desk", "Accounts and transfers, currencies, corrections, reconciliation, 90-day cash flow",
            Icons.Outlined.AccountBalance, Hub.Money, "account balance transfer currency dollar correction edit reconcile bank balance cash flow forecast subscription price increase") { c, _ -> MoneyDeskScreen(c) }
    )
}

@Composable
private fun MoneyDeskScreen(container: AppContainer) {
    val c = LocalContext.current
    val work = com.nizam.app.ui.design.rememberWorkScope()
    val repo = container.financeRepository
    val txns by repo.since(LocalDate.now().minusYears(2).toString()).collectAsState(initial = emptyList())
    var tab by remember { mutableStateOf("Accounts") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Accounts", "Fix", "Flight plan").forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t) }) }
        }
        when (tab) {
            "Accounts" -> AccountsPane(c, txns, repo, work)
            "Fix" -> FixPane(txns, repo, work)
            else -> FlightPane(c, txns)
        }
    }
}

@Composable
private fun AccountsPane(c: Context, txns: List<Txn>, repo: com.nizam.app.feature.finance.FinanceRepository, work: kotlinx.coroutines.CoroutineScope) {
    var accounts by remember(txns) { mutableStateOf(MoneyDesk.accounts(c, txns).toList()) }
    var from by remember { mutableStateOf("Bank") }
    var to by remember { mutableStateOf("Cash") }
    var amt by remember { mutableStateOf("") }
    var cur by remember { mutableStateOf("PKR") }
    var rate by remember { mutableStateOf("") }
    var newAcc by remember { mutableStateOf("") }
    var actual by remember { mutableStateOf("") }
    var reconcileFor by remember { mutableStateOf<String?>(null) }

    val total = accounts.sumOf { MoneyDesk.balance(c, txns, it) }
    Text("Net across accounts: ${rs(total)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    accounts.forEach { a ->
        val bal = MoneyDesk.balance(c, txns, a)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).clickable { reconcileFor = if (reconcileFor == a) null else a }.padding(12.dp)) {
            Row { Text(a, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge); Text(rs(bal), fontWeight = FontWeight.SemiBold) }
            if (reconcileFor == a) {
                Text("Reconcile: what does your bank or wallet actually show?", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                OutlinedTextField(actual, { actual = it }, Modifier.fillMaxWidth(), placeholder = { Text("Actual balance") }, singleLine = true)
                val actualV = num(actual)
                if (actual.isNotBlank()) {
                    val diff = actualV - bal
                    Text(if (kotlin.math.abs(diff) < 1) "Matches. ✓" else "Off by ${rs(diff)}", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (kotlin.math.abs(diff) >= 1) OutlinedButton(onClick = {
                            work.launch { repo.restore(Txn(amount = kotlin.math.abs(diff), kind = if (diff > 0) "INCOME" else "EXPENSE", category = "Adjustment", account = a,
                                localDate = LocalDate.now().toString(), note = "Reconciled with actual balance")) }
                            actual = ""
                        }) { Text("Add an adjustment") }
                        TextButton(onClick = { MoneyDesk.setOpening(c, a, MoneyDesk.opening(c, a) + diff); actual = "" }) { Text("Set as starting balance") }
                    }
                }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(newAcc, { newAcc = it }, Modifier.weight(1f), placeholder = { Text("New account, e.g. Meezan, Sadapay") }, singleLine = true)
        TextButton(onClick = { MoneyDesk.addAccount(c, newAcc); accounts = (accounts + newAcc.trim()).distinct().sorted(); newAcc = "" }, enabled = newAcc.isNotBlank()) { Text("Add") }
    }

    Text("Move money", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Text("From", Modifier.width(44.dp)); accounts.take(6).forEach { a -> FilterChip(selected = from == a, onClick = { from = a }, label = { Text(a) }) } }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Text("To", Modifier.width(44.dp)); accounts.take(6).forEach { a -> FilterChip(selected = to == a, onClick = { to = a }, label = { Text(a) }) } }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(amt, { amt = it }, Modifier.weight(1f), placeholder = { Text("Amount") }, singleLine = true)
        listOf("PKR", "USD", "AED", "SAR", "GBP").forEach { x -> FilterChip(selected = cur == x, onClick = { cur = x }, label = { Text(x) }) }
    }
    if (cur != "PKR") OutlinedTextField(rate, { rate = it }, Modifier.fillMaxWidth(), placeholder = { Text("Rate: 1 $cur = ? PKR") }, singleLine = true)
    val pkr = num(amt) * (if (cur == "PKR") 1.0 else num(rate))
    Button(onClick = {
        val note = if (cur == "PKR") "" else "($cur ${amt.trim()} @ ${rate.trim()})"
        work.launch {
            val d = LocalDate.now().toString()
            repo.restore(Txn(amount = pkr, kind = "EXPENSE", category = MoneyDesk.TRANSFER, account = from, localDate = d, note = "→ $to $note".trim()))
            repo.restore(Txn(amount = pkr, kind = "INCOME", category = MoneyDesk.TRANSFER, account = to, localDate = d, note = "← $from $note".trim()))
            com.nizam.app.ui.components.Feedback.show("Moved ${rs(pkr)} from $from to $to")
        }
        amt = ""
    }, enabled = from != to && pkr > 0) { Text("Transfer ${rs(pkr)}") }
    Text("Transfers are logged as a matched pair and left out of your spending and income totals here.", style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun FixPane(txns: List<Txn>, repo: com.nizam.app.feature.finance.FinanceRepository, work: kotlinx.coroutines.CoroutineScope) {
    var editing by remember { mutableStateOf<Txn?>(null) }
    var amount by remember { mutableStateOf("") }
    var category by remember { mutableStateOf("") }
    var date by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    Text("Tap any entry to correct it. Wrong amount, category, date or account: fix it here and every total updates.", style = MaterialTheme.typography.bodySmall)
    txns.sortedByDescending { it.localDate + it.createdAt }.take(60).forEach { t ->
        Column(Modifier.fillMaxWidth().clickable {
            editing = if (editing?.id == t.id) null else t
            amount = t.amount.toString(); category = t.category; date = t.localDate; account = t.account; note = t.note
        }.padding(vertical = 6.dp)) {
            Row {
                Text("${t.localDate} · ${t.category}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text((if (t.kind == "INCOME") "+" else "−") + rs(t.amount).removePrefix("Rs ").let { "Rs $it" }, fontWeight = FontWeight.Medium)
            }
            if (t.note.isNotBlank() || t.account != "Cash") Text("${t.account} · ${t.note}", style = MaterialTheme.typography.labelSmall)
            if (editing?.id == t.id) {
                OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), label = { Text("Amount") }, singleLine = true)
                OutlinedTextField(category, { category = it }, Modifier.fillMaxWidth(), label = { Text("Category") }, singleLine = true)
                OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), label = { Text("Date (YYYY-MM-DD)") }, singleLine = true)
                OutlinedTextField(account, { account = it }, Modifier.fillMaxWidth(), label = { Text("Account") }, singleLine = true)
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("Note") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        val fixed = t.copy(amount = num(amount).takeIf { it > 0 } ?: t.amount, category = category.ifBlank { t.category },
                            localDate = runCatching { LocalDate.parse(date.trim()).toString() }.getOrDefault(t.localDate), account = account.ifBlank { t.account }, note = note)
                        work.launch { runCatching { repo.edit(fixed) }.onSuccess { com.nizam.app.ui.components.Feedback.show("Corrected") }.onFailure { com.nizam.app.ui.components.Feedback.show(it.message ?: "Couldn't save") } }
                        editing = null
                    }) { Text("Save correction") }
                    TextButton(onClick = { work.launch { repo.remove(t) }; editing = null }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun FlightPane(c: Context, txns: List<Txn>) {
    val (inc, exp) = MoneyDesk.averages(txns)
    val now = MoneyDesk.accounts(c, txns).sumOf { MoneyDesk.balance(c, txns, it) }
    val net = inc - exp
    Text("90-day flight plan", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text("From your last 90 days: about ${rs(inc)} in and ${rs(exp)} out each month (transfers excluded).", style = MaterialTheme.typography.bodySmall)
    val points = (0..12).map { w -> now + net * w * 7 / 30.0 }
    val maxAbs = points.maxOf { kotlin.math.abs(it) }.coerceAtLeast(1.0)
    Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
        points.forEach { v ->
            Box(Modifier.weight(1f).height((8 + 100 * kotlin.math.abs(v) / maxAbs).dp).clip(RoundedCornerShape(6.dp))
                .background(if (v >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error))
        }
    }
    listOf(30, 60, 90).forEach { d ->
        val v = now + net * d / 30.0
        Text("In $d days: ${rs(v)}${if (v < 0) "  ⚠️ below zero" else ""}", style = MaterialTheme.typography.bodyLarge)
    }
    if (net < 0) Text("You're spending ${rs(-net)} a month more than comes in. At this pace savings run out in ${if (now > 0) "${(now / -net * 30).toInt()} days" else "now"}.",
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

    val changes = MoneyDesk.priceChanges(txns)
    Text("Price changes in repeating charges", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
    if (changes.isEmpty()) Text("No repeating charge changed price last month.", style = MaterialTheme.typography.bodySmall)
    changes.forEach { ch -> Text("${if (ch.now > ch.before) "📈" else "📉"} ${ch.what}: ${rs(ch.before)} → ${rs(ch.now)}", style = MaterialTheme.typography.bodyMedium) }
    val days = ChronoUnit.DAYS.between(LocalDate.now().withDayOfMonth(1), LocalDate.now()) + 1
    Text("Day $days of the month.", style = MaterialTheme.typography.labelSmall)
}
