package com.nizam.app.feature.pro

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.omni.O
import com.nizam.app.feature.rune.RuneChip
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.rememberSafeScope
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * Debt-free plan: list what you owe, and the phone works out the real month you finish, the interest you pay,
 * and the order to clear them, two ways. Replaces Debt Escape Route and Debt payoff plan (which only wrote advice).
 * A record: {id, name, balance, rate (% a year, 0 for interest-free), min (monthly payment)}
 */
object DebtFree {
    fun store(c: Context) = JsonStore(c.applicationContext, "pro_debts")
    private fun p(c: Context) = c.getSharedPreferences("pro", 0)
    fun extra(c: Context) = p(c).getFloat("debt_extra", 0f).toDouble()
    fun setExtra(c: Context, v: Double) = p(c).edit().putFloat("debt_extra", v.toFloat()).apply()

    class Debt(val name: String, var bal: Double, val rate: Double, val min: Double)
    class Plan(val months: Int, val interest: Double, val order: List<Pair<String, Int>>, val stuck: List<String>)

    /** Month-by-month payoff. [smallestFirst] true = clear the smallest balance first; false = the highest interest rate first. */
    fun plan(debts: List<JSONObject>, extra: Double, smallestFirst: Boolean): Plan {
        val d = debts.map { Debt(it.optString("name"), it.optDouble("balance", 0.0), it.optDouble("rate", 0.0), it.optDouble("min", 0.0)) }.filter { it.bal > 0 }
        val stuck = d.filter { it.rate > 0 && it.min <= it.bal * it.rate / 1200.0 && extra <= 0 }.map { it.name }
        val order = ArrayList<Pair<String, Int>>(); var interest = 0.0; var month = 0
        while (d.any { it.bal > 0.5 } && month < 600) {
            month++
            var pool = extra
            d.filter { it.bal > 0.5 }.forEach { x -> val i = x.bal * x.rate / 1200.0; interest += i; x.bal += i }
            d.forEach { x -> if (x.bal > 0.5) { val pay = minOf(x.min, x.bal); x.bal -= pay } else pool += x.min }   // a cleared debt's payment rolls on to the next
            while (pool > 0.5) {
                val t = d.filter { it.bal > 0.5 }.let { l -> if (smallestFirst) l.minByOrNull { it.bal } else l.maxByOrNull { it.rate } } ?: break
                val pay = minOf(pool, t.bal); t.bal -= pay; pool -= pay
            }
            d.forEach { x -> if (x.bal <= 0.5 && order.none { it.first == x.name }) order += x.name to month }
        }
        return Plan(if (d.any { it.bal > 0.5 }) -1 else month, interest, order, stuck)
    }

    fun monthLabel(n: Int): String = YearMonth.now().plusMonths(n.toLong()).format(DateTimeFormatter.ofPattern("MMM yyyy"))
    fun span(n: Int): String = if (n < 12) "$n month${if (n == 1) "" else "s"}" else "${n / 12} yr${if (n % 12 > 0) " ${n % 12} mo" else ""}"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DebtFreeScreen() {
    val c = LocalContext.current
    val scope = rememberSafeScope()
    val m = MaterialTheme.colorScheme
    var list by remember { mutableStateOf(DebtFree.store(c).all()) }
    fun reload() { list = DebtFree.store(c).all() }
    var name by remember { mutableStateOf("") }; var bal by remember { mutableStateOf("") }; var rate by remember { mutableStateOf("") }; var min by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf(DebtFree.extra(c).let { if (it > 0) Math.round(it).toString() else "" }) }
    var small by remember { mutableStateOf(true) }
    var payFor by remember { mutableStateOf("") }; var payAmt by remember { mutableStateOf("") }
    var coach by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    val open = list.filter { it.optDouble("balance", 0.0) > 0 }
    val ex = num(extra)
    val a = remember(list, ex) { DebtFree.plan(open, ex, true) }; val b = remember(list, ex) { DebtFree.plan(open, ex, false) }
    val base = remember(list) { DebtFree.plan(open, 0.0, true) }
    val pick = if (small) a else b
    val numKb = KeyboardOptions(keyboardType = KeyboardType.Number)

    AtlasScreen(title = "Debt-free plan", subtitle = "The real month you finish, the interest you pay, and what to clear first.", hub = Hub.Money) {
        item("stats") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("You owe", rs(open.sumOf { it.optDouble("balance", 0.0) }), m.error, Modifier.weight(1.3f))
                Stat("Debt-free", if (open.isEmpty()) "Now" else if (pick.months < 0) "Never" else DebtFree.monthLabel(pick.months), m.primary, Modifier.weight(1.2f))
                Stat("Interest", rs(pick.interest), m.onSurfaceVariant, Modifier.weight(1.2f))
            }
        }
        if (open.isNotEmpty()) item("plan") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(extra, { extra = it; DebtFree.setExtra(c, num(it)) }, Modifier.fillMaxWidth(), label = { Text("Extra you can pay each month (Rs)") }, singleLine = true, keyboardOptions = numKb)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { RuneChip("Smallest first", small) { small = true }; RuneChip("Highest interest first", !small) { small = false } }
                if (pick.months < 0) Text("At these payments this never ends: a payment is smaller than its monthly interest. Raise the payment or add extra.", color = m.error, fontSize = 13.sp)
                else {
                    Text("Done in ${DebtFree.span(pick.months)}, by ${DebtFree.monthLabel(pick.months)}.", fontWeight = FontWeight.SemiBold)
                    if (a.months > 0 && b.months > 0 && (a.months != b.months || Math.abs(a.interest - b.interest) > 1)) Text("Smallest first: ${DebtFree.span(a.months)}, ${rs(a.interest)} interest. Highest interest first: ${DebtFree.span(b.months)}, ${rs(b.interest)} interest.", fontSize = 13.sp, color = m.onSurfaceVariant)
                    if (ex > 0 && base.months > 0) Text("Your extra ${rs(ex)} a month saves ${DebtFree.span((base.months - pick.months).coerceAtLeast(0))} and ${rs((base.interest - pick.interest).coerceAtLeast(0.0))} in interest.", fontSize = 13.sp, color = m.primary)
                    pick.order.forEachIndexed { i, (n, mo) -> Text("${i + 1}. $n cleared in ${DebtFree.monthLabel(mo)}", fontSize = 14.sp) }
                }
                pick.stuck.forEach { Text("$it: the payment doesn't cover its interest, so it grows.", color = m.error, fontSize = 13.sp) }
                TextButton({ busy = true; scope.launch {
                    coach = O.ai(c, "My debts: " + open.joinToString("; ") { "${it.optString("name")} Rs ${it.optDouble("balance")} at ${it.optDouble("rate")}% a year, paying Rs ${it.optDouble("min")} a month" } +
                        ". Extra each month: Rs $ex. Plan: ${if (small) "smallest balance first" else "highest interest first"}, finishing in ${pick.months} months with Rs ${Math.round(pick.interest)} interest. " +
                        "Give 4 short, practical tips for my situation in Pakistan to finish sooner. Do not redo the maths. Avoid suggesting interest-based products. Under 110 words.", tokens = 350); busy = false } }, enabled = !busy) { Text(if (busy) "Thinking…" else "Coach me on this plan") }
                if (coach.isNotBlank()) Text(coach, fontSize = 13.sp)
            }
        }
        open.forEach { o ->
            val id = o.optString("id")
            item("d_$id") { Card2 {
                Text(o.optString("name"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("${rs(o.optDouble("balance", 0.0))} · ${o.optDouble("rate", 0.0).let { if (it == 0.0) "no interest" else "$it% a year" }} · ${rs(o.optDouble("min", 0.0))} a month", fontSize = 13.sp, color = m.onSurfaceVariant)
                if (payFor == id) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(payAmt, { payAmt = it }, Modifier.weight(1f), label = { Text("Paid (Rs)") }, singleLine = true, keyboardOptions = numKb)
                    Button({ DebtFree.store(c).update(id) { it.put("balance", (it.optDouble("balance", 0.0) - num(payAmt)).coerceAtLeast(0.0)) }; payFor = ""; payAmt = ""; reload() }, enabled = num(payAmt) > 0) { Text("Save") }
                }
                FlowRow { TextButton({ payFor = if (payFor == id) "" else id }) { Text("Log a payment") }
                    TextButton({ DebtFree.store(c).remove(id); reload() }) { Text("Delete", color = m.error) } }
            } }
        }
        val cleared = list.filter { it.optDouble("balance", 0.0) <= 0 }
        if (cleared.isNotEmpty()) item("cl") { Text("Cleared: " + cleared.joinToString(", ") { it.optString("name") } + " ✓", Modifier.padding(horizontal = 16.dp, vertical = 6.dp), color = m.primary) }
        item("add") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Add a debt", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name (e.g. Bike instalment, Chachu)") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(bal, { bal = it }, Modifier.weight(1.2f), label = { Text("Owed (Rs)") }, singleLine = true, keyboardOptions = numKb)
                    OutlinedTextField(rate, { rate = it }, Modifier.weight(1f), label = { Text("% a year") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                OutlinedTextField(min, { min = it }, Modifier.fillMaxWidth(), label = { Text("You pay each month (Rs)") }, singleLine = true, keyboardOptions = numKb)
                Text("Leave % empty for an interest-free loan from family or a friend.", fontSize = 12.sp, color = m.onSurfaceVariant)
                Button({ DebtFree.store(c).add(JSONObject().put("name", name.trim()).put("balance", num(bal)).put("rate", num(rate)).put("min", num(min))); name = ""; bal = ""; rate = ""; min = ""; reload() },
                    enabled = name.isNotBlank() && num(bal) > 0 && num(min) > 0) { Text("Add") }
            }
        }
        item("end") { Spacer(Modifier.height(90.dp)) }
    }
}
