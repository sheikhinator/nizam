package com.nizam.app.feature.finance

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.fmtAmount
import com.nizam.app.feature.nx.MicButton
import com.nizam.app.ui.components.Feedback
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Money, overhauled: type or say "450 petrol", "salary 1.2 lakh", "kal 2k groceries" and it's logged;
 * budgets with live bars, safe-to-spend per day, this month against last, your expensive weekday,
 * what looks like a subscription, search and edit for every entry.
 */
object MoneyParse {
    data class Parsed(val amount: Double, val kind: String, val category: String, val date: LocalDate, val note: String)

    private val cats = linkedMapOf(
        "Food" to listOf("food", "lunch", "dinner", "breakfast", "khana", "restaurant", "pizza", "burger", "biryani", "chai", "tea", "coffee", "snack", "foodpanda", "cafe"),
        "Transport" to listOf("petrol", "fuel", "careem", "uber", "indrive", "bykea", "rickshaw", "taxi", "bus", "metro", "parking", "toll", "diesel"),
        "Bills" to listOf("bill", "electricity", "bijli", "gas", "water", "internet", "wifi", "mobile", "load", "package", "rent", "kiraya"),
        "Groceries" to listOf("grocery", "groceries", "sabzi", "fruit", "milk", "doodh", "atta", "ration", "carrefour", "imtiaz", "bread"),
        "Shopping" to listOf("shopping", "clothes", "shoes", "daraz", "amazon", "shirt", "gift", "mall"),
        "Health" to listOf("medicine", "doctor", "pharmacy", "hospital", "dawai", "clinic", "lab", "gym"),
        "Family" to listOf("family", "ammi", "abbu", "kids", "school", "fees", "wife", "brother", "sister"),
        "Charity" to listOf("sadaqah", "zakat", "charity", "donation", "khairat"))
    private val incomeWords = Regex("\\b(salary|income|received|got paid|earned|bonus|refund|tankhwa|profit|freelance)\\b", RegexOption.IGNORE_CASE)

    fun parse(text: String, known: List<String> = emptyList()): Parsed? {
        val low = " " + text.lowercase().replace(",", "") + " "
        val m = Regex("""(?:rs\.?\s*|pkr\s*)?(\d+(?:\.\d+)?)\s*(k|thousand|hazar|lakh|lac|crore|cr)?\b""").find(low) ?: return null
        var amt = m.groupValues[1].toDouble()
        amt *= when (m.groupValues[2]) { "k", "thousand", "hazar" -> 1_000.0; "lakh", "lac" -> 100_000.0; "crore", "cr" -> 10_000_000.0; else -> 1.0 }
        if (amt <= 0 || amt > 999_999_999) return null
        val kind = if (incomeWords.containsMatchIn(low)) "INCOME" else "EXPENSE"
        val date = when {
            Regex("\\b(yesterday|kal)\\b").containsMatchIn(low) -> LocalDate.now().minusDays(1)
            Regex("\\b(day before yesterday|parson)\\b").containsMatchIn(low) -> LocalDate.now().minusDays(2)
            else -> LocalDate.now()
        }
        val cat = known.firstOrNull { k -> k.length > 2 && Regex("\\b" + Regex.escape(k.lowercase()) + "\\b").containsMatchIn(low) }
            ?: cats.entries.firstOrNull { (_, ws) -> ws.any { w -> Regex("\\b$w").containsMatchIn(low) } }?.key
            ?: if (kind == "INCOME") "Income" else "Other"
        val note = text.replace(Regex("(?i)(rs\\.?|pkr)"), "").replace(m.value.trim(), "", ignoreCase = true)
            .replace(Regex("(?i)\\b(spent|on|for|paid|yesterday|kal|today|aaj|parson)\\b"), " ").replace(Regex("\\s+"), " ").trim()
        return Parsed(Math.round(amt * 100) / 100.0, kind, cat, date, note.take(80))
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Text(text, Modifier.clip(RoundedCornerShape(50)).background(if (on) cs.primary.copy(alpha = 0.16f) else cs.surfaceVariant.copy(alpha = 0.6f))
        .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp),
        style = MaterialTheme.typography.labelLarge, color = if (on) cs.primary else cs.onSurfaceVariant)
}

/** One line to log money, by typing or by voice. */
@Composable
fun QuickMoney(container: AppContainer, categories: List<String>) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var line by remember { mutableStateOf("") }
    val parsed = remember(line, categories) { if (line.isBlank()) null else MoneyParse.parse(line, categories) }
    fun save() {
        val p = parsed ?: return
        scope.launch {
            runCatching { container.financeRepository.addOn(p.date.toString(), p.amount, p.kind, p.category, p.note) }
                .onSuccess { Feedback.show((if (p.kind == "INCOME") "+" else "−") + fmtAmount(p.amount) + " · " + p.category); line = "" }
                .onFailure { Feedback.show(it.message ?: "Couldn't save") }
        }
    }
    com.nizam.app.ui.design.Group(Modifier.padding(vertical = 8.dp)) {
        Text("Quick log", fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(line, { line = it }, Modifier.weight(1f), singleLine = true,
                placeholder = { Text("\"450 petrol\", \"salary 1.2 lakh\", \"kal 2k groceries\"") },
                trailingIcon = { MicButton({ heard -> line = heard }) })
            TextButton(onClick = { save() }, enabled = parsed != null) { Text("Add") }
        }
        parsed?.let { p ->
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip((if (p.kind == "INCOME") "+ " else "− ") + fmtAmount(p.amount), true) {}
                Chip(p.category, true) {}
                Chip(if (p.date == LocalDate.now()) "Today" else com.nizam.app.core.prettyDate(p.date.toString()), true) {}
                if (p.note.isNotBlank()) Chip(p.note, false) {}
            }
        }
    }
}

/** Budgets that fill up as you spend, safe-to-spend, last month, weekdays, subscriptions. */
@Composable
fun MoneyInsights(container: AppContainer, month: List<Txn>, categories: List<String>) {
    val budgetsFlow = remember { container.financeRepository.budgets() }
    val budgets by budgetsFlow.collectAsState(initial = emptyList())
    val prevStart = remember { LocalDate.now().minusMonths(1).withDayOfMonth(1).toString() }
    val sinceFlow = remember { container.financeRepository.since(LocalDate.now().minusMonths(3).withDayOfMonth(1).toString()) }
    val since by sinceFlow.collectAsState(initial = emptyList())
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var editing by remember { mutableStateOf<String?>(null) }
    val today = LocalDate.now()
    val spentBy = remember(month) { month.filter { it.kind == "EXPENSE" }.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } } }
    val curStart = today.withDayOfMonth(1).toString()
    val prevSameDay = remember(since) {
        val cut = today.minusMonths(1).toString()
        since.filter { it.kind == "EXPENSE" && it.localDate >= prevStart && it.localDate <= cut && it.localDate < curStart }.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } }
    }
    val income = month.filter { it.kind == "INCOME" }.sumOf { it.amount }
    val spent = spentBy.values.sum()
    val daysLeft = today.lengthOfMonth() - today.dayOfMonth + 1
    val cs = MaterialTheme.colorScheme

    com.nizam.app.ui.design.Group(Modifier.padding(vertical = 8.dp)) {
        Text("Money brain", fontWeight = FontWeight.SemiBold)
        // safe to spend
        val budgetTotal = budgets.sumOf { it.monthlyAmount }
        val pool = when { income > 0 -> income - spent; budgetTotal > 0 -> budgetTotal - spent; else -> null }
        pool?.let { left ->
            val perDay = left / daysLeft
            Text(if (left > 0) "Safe to spend: ${fmtAmount(perDay)} a day for the next $daysLeft days" else "Over by ${fmtAmount(-left)} this month",
                style = MaterialTheme.typography.titleMedium, color = if (left > 0) cs.primary else cs.error, modifier = Modifier.padding(top = 6.dp))
            Text(if (income > 0) "From this month's income minus what's spent" else "From your budgets minus what's spent",
                style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
        }
        // vs last month, same point in the month
        val prevTotal = prevSameDay.values.sum()
        if (prevTotal > 0) {
            val ch = (spent - prevTotal) / prevTotal * 100
            Text("${if (ch >= 0) "▲" else "▼"} ${"%.0f".format(kotlin.math.abs(ch))}% vs the same point last month (${fmtAmount(prevTotal)})",
                style = MaterialTheme.typography.bodyMedium, color = if (ch > 10) cs.error else cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            val jump = spentBy.mapNotNull { (k, v) -> val p = prevSameDay[k] ?: 0.0; if (v - p > 0) k to (v - p) else null }.maxByOrNull { it.second }
            jump?.let { (k, d) -> if (d > prevTotal * 0.05) Text("Biggest rise: $k (+${fmtAmount(d)})", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
        }
        // weekday pattern over the last three months
        val byDow = remember(since) { since.filter { it.kind == "EXPENSE" }.groupBy { runCatching { LocalDate.parse(it.localDate).dayOfWeek }.getOrNull() }
            .filterKeys { it != null }.mapValues { e -> e.value.sumOf { it.amount } } }
        if (byDow.size >= 4) byDow.maxByOrNull { it.value }?.let { (d, _) ->
            Text("You spend most on ${d!!.name.lowercase().replaceFirstChar { it.uppercase() }}s", style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        }
        // looks like a subscription: same category, similar amount, in at least two different months
        val subs = remember(since) {
            since.filter { it.kind == "EXPENSE" }.groupBy { it.category to Math.round(it.amount / 50.0) }
                .filter { (_, l) -> l.map { it.localDate.take(7) }.distinct().size >= 2 && l.first().amount >= 200 }
                .map { (k, l) -> k.first to l.maxOf { it.amount } }.distinct().take(4)
        }
        if (subs.isNotEmpty()) {
            Text("Looks recurring", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            subs.forEach { (c, a) -> Text("• $c · about ${fmtAmount(a)} a month", style = MaterialTheme.typography.bodySmall) }
        }
        // budgets
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Budgets", style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
            TextButton(onClick = { editing = "" }) { Text("+ Set a budget") }
        }
        budgets.filter { it.monthlyAmount > 0 }.sortedByDescending { (spentBy[it.category] ?: 0.0) / it.monthlyAmount }.forEach { b ->
            val used = spentBy[b.category] ?: 0.0
            val frac = (used / b.monthlyAmount).toFloat()
            val col = when { frac >= 1f -> cs.error; frac >= 0.8f -> Color(0xFFE8A13A); else -> cs.primary }
            Column(Modifier.fillMaxWidth().clickable { editing = b.category }.padding(vertical = 5.dp)) {
                Row(Modifier.fillMaxWidth()) {
                    Text(b.category, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text("${fmtAmount(used)} / ${fmtAmount(b.monthlyAmount)}", style = MaterialTheme.typography.bodySmall, color = col)
                }
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(cs.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(frac.coerceIn(0.01f, 1f)).height(6.dp).background(col))
                }
                if (frac >= 0.8f) Text(if (frac >= 1f) "Over budget" else "${fmtAmount(b.monthlyAmount - used)} left · ${fmtAmount((b.monthlyAmount - used) / daysLeft)} a day",
                    style = MaterialTheme.typography.labelSmall, color = col)
            }
        }
        if (budgets.none { it.monthlyAmount > 0 }) Text("Set a monthly limit for a category and watch it fill as you spend.",
            style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }

    editing?.let { cat0 ->
        var cat by remember(cat0) { mutableStateOf(cat0.ifBlank { categories.firstOrNull { c -> budgets.none { it.category == c } } ?: "Food" }) }
        var amt by remember(cat0) { mutableStateOf(budgets.firstOrNull { it.category == cat0 }?.monthlyAmount?.let { "%.0f".format(it) } ?: "") }
        AlertDialog(onDismissRequest = { editing = null }, title = { Text("Monthly budget") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        (categories + DEFAULT_CATEGORIES).distinct().forEach { c -> Chip(c, c == cat) { cat = c } }
                    }
                    OutlinedTextField(amt, { amt = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Limit for $cat (Rs)") })
                    // a suggestion from what you actually spent last month
                    val last = prevSameDay[cat]
                    if (last != null) Text("Last month by now: ${fmtAmount(last)}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = {
                val v = amt.toDoubleOrNull() ?: 0.0
                scope.launch { runCatching { container.financeRepository.setBudget(cat, v) }.onFailure { Feedback.show(it.message ?: "Couldn't save") } }
                editing = null
            }) { Text("Save") } },
            dismissButton = { Row {
                if (cat0.isNotBlank()) TextButton(onClick = { scope.launch { runCatching { container.financeRepository.setBudget(cat0, 0.0) } }; editing = null }) { Text("Remove") }
                TextButton(onClick = { editing = null }) { Text("Cancel") }
            } })
    }
}

/** Search box and category filter for the transaction list. */
@Composable
fun TxnFilter(query: String, onQuery: (String) -> Unit, cat: String?, onCat: (String?) -> Unit, categories: List<String>) {
    Column(Modifier.padding(vertical = 6.dp)) {
        OutlinedTextField(query, onQuery, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search amounts, notes, categories") })
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Chip("All", cat == null) { onCat(null) }
            categories.forEach { c -> Chip(c, cat == c) { onCat(if (cat == c) null else c) } }
        }
    }
}

fun filterTxns(list: List<Txn>, query: String, cat: String?): List<Txn> {
    val q = query.trim().lowercase()
    return list.filter { t -> (cat == null || t.category == cat) &&
        (q.isEmpty() || t.note.lowercase().contains(q) || t.category.lowercase().contains(q) || "%.0f".format(t.amount).contains(q) || t.localDate.contains(q)) }
}

/** Edit any entry: amount, category, note, date, income or expense. */
@Composable
fun TxnEditor(container: AppContainer, txn: Txn, categories: List<String>, close: () -> Unit) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var amount by remember(txn.id) { mutableStateOf("%.2f".format(txn.amount).removeSuffix(".00")) }
    var cat by remember(txn.id) { mutableStateOf(txn.category) }
    var note by remember(txn.id) { mutableStateOf(txn.note) }
    var date by remember(txn.id) { mutableStateOf(txn.localDate) }
    var kind by remember(txn.id) { mutableStateOf(txn.kind) }
    AlertDialog(onDismissRequest = close, title = { Text("Edit entry") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { Chip("Expense", kind == "EXPENSE") { kind = "EXPENSE" }; Chip("Income", kind == "INCOME") { kind = "INCOME" } }
                OutlinedTextField(amount, { amount = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Amount") })
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (listOf(txn.category) + categories + DEFAULT_CATEGORIES).distinct().forEach { c -> Chip(c, c == cat) { cat = c } }
                }
                OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Note") })
                OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Date (YYYY-MM-DD)") })
            }
        },
        confirmButton = { TextButton(onClick = {
            val v = amount.replace(",", "").toDoubleOrNull()
            if (v == null || v <= 0) { Feedback.show("Enter a positive amount"); return@TextButton }
            scope.launch {
                runCatching { container.financeRepository.edit(txn.copy(amount = v, category = cat.ifBlank { "Other" }, note = note.trim(), localDate = date.trim(), kind = kind)) }
                    .onSuccess { close() }.onFailure { Feedback.show(it.message ?: "Couldn't save") }
            }
        }) { Text("Save") } },
        dismissButton = { TextButton(onClick = close) { Text("Cancel") } })
}
