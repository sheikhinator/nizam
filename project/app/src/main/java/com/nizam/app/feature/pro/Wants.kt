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
import androidx.compose.material3.LinearProgressIndicator
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

internal fun rs(v: Double): String = "Rs " + "%,d".format(Math.round(v))
internal fun num(s: String): Double = s.replace(",", "").trim().toDoubleOrNull() ?: 0.0

/**
 * Wants: a cooling-off list for things you feel like buying. Each want waits a set number of days, shows what it costs in
 * hours of your work, and ends as Bought or Skipped. Replaces Impulse Brake and Impulse check.
 * A record: {id, name, price, added, wait (days), status: waiting|bought|skipped, closed, sides}
 */
object Wants {
    fun store(c: Context) = JsonStore(c.applicationContext, "pro_wants")
    private fun p(c: Context) = c.getSharedPreferences("pro", 0)
    fun income(c: Context) = p(c).getFloat("income", 0f).toDouble()
    fun setIncome(c: Context, v: Double) = p(c).edit().putFloat("income", v.toFloat()).apply()
    fun hoursWeek(c: Context) = p(c).getFloat("hours_week", 45f).toDouble()
    fun setHoursWeek(c: Context, v: Double) = p(c).edit().putFloat("hours_week", v.toFloat()).apply()
    /** What one hour of your work earns, or 0 when income isn't set. */
    fun hourly(c: Context): Double { val h = hoursWeek(c) * 4.33; return if (income(c) > 0 && h > 0) income(c) / h else 0.0 }
    fun daysLeft(o: JSONObject): Long = (o.optInt("wait", 7) - (System.currentTimeMillis() - o.optLong("added")) / 86_400_000L).coerceAtLeast(0)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WantsScreen() {
    val c = LocalContext.current
    val scope = rememberSafeScope()
    val m = MaterialTheme.colorScheme
    var list by remember { mutableStateOf(Wants.store(c).all()) }
    fun reload() { list = Wants.store(c).all() }
    fun change(id: String, f: (JSONObject) -> Unit) { Wants.store(c).update(id, f); reload() }
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var wait by remember { mutableStateOf(7) }
    var income by remember { mutableStateOf(Wants.income(c).let { if (it > 0) Math.round(it).toString() else "" }) }
    var hours by remember { mutableStateOf(Math.round(Wants.hoursWeek(c)).toString()) }
    var busyId by remember { mutableStateOf("") }
    val hourly = Wants.hourly(c)
    val waiting = list.filter { it.optString("status", "waiting") == "waiting" }.sortedBy { Wants.daysLeft(it) }
    val skipped = list.filter { it.optString("status") == "skipped" }; val bought = list.filter { it.optString("status") == "bought" }

    AtlasScreen(title = "Wants", subtitle = "Park anything you feel like buying. Decide after it has cooled off.", hub = Hub.Money) {
        item("stats") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stat("Cooling off", waiting.size.toString(), m.primary, Modifier.weight(1f))
                Stat("Kept by skipping", rs(skipped.sumOf { it.optDouble("price", 0.0) }), m.tertiary, Modifier.weight(1.6f))
                Stat("Bought", bought.size.toString(), m.secondary, Modifier.weight(1f))
            }
        }
        item("add") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("What do you want?") }, singleLine = true)
                OutlinedTextField(price, { price = it }, Modifier.fillMaxWidth(), label = { Text("Price (Rs)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(1 to "1 day", 3 to "3 days", 7 to "A week", 30 to "A month").forEach { (d, l) -> RuneChip(l, wait == d) { wait = d } } }
                if (hourly > 0 && num(price) > 0) Text("That is ${"%.1f".format(num(price) / hourly)} hours of your work.", fontSize = 13.sp, color = m.primary)
                Button({ Wants.store(c).add(JSONObject().put("name", name.trim()).put("price", num(price)).put("added", System.currentTimeMillis()).put("wait", wait).put("status", "waiting")); name = ""; price = ""; reload() },
                    enabled = name.isNotBlank() && num(price) > 0) { Text("Park it") }
            }
        }
        if (waiting.isEmpty()) item("we") { Text("Nothing cooling off. Add the next thing you're tempted by.", Modifier.padding(16.dp), color = m.onSurfaceVariant) }
        waiting.forEach { o ->
            val id = o.optString("id")
            item("w_$id") {
                val left = Wants.daysLeft(o); val total = o.optInt("wait", 7).coerceAtLeast(1)
                Card2 {
                    Text(o.optString("name"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(rs(o.optDouble("price", 0.0)) + (if (hourly > 0) " · ${"%.1f".format(o.optDouble("price", 0.0) / hourly)} hours of work" else ""), fontSize = 13.sp, color = m.onSurfaceVariant)
                    LinearProgressIndicator(progress = { 1f - left.toFloat() / total }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                    Text(if (left == 0L) "Cooled off. Decide now." else "Decide in $left day${if (left == 1L) "" else "s"}", fontSize = 12.sp, color = if (left == 0L) m.primary else m.onSurfaceVariant)
                    o.optString("sides").takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton({ change(id) { it.put("status", "skipped").put("closed", System.currentTimeMillis()) } }) { Text("Skip it") }
                        TextButton({ change(id) { it.put("status", "bought").put("closed", System.currentTimeMillis()) } }) { Text(if (left == 0L) "I bought it" else "Buy anyway") }
                        TextButton({ change(id) { it.put("wait", it.optInt("wait", 7) + 7) } }) { Text("+1 week") }
                        TextButton({ busyId = id; scope.launch {
                            val t = O.ai(c, "I want to buy: ${o.optString("name")} for Rs ${o.optDouble("price", 0.0)}." + (if (hourly > 0) " One hour of my work earns Rs ${Math.round(hourly)}." else "") +
                                " Give the honest case for buying it in 2 short points, the honest case against in 2 short points, then one question I should answer before deciding. Plain words, under 90 words.", tokens = 300)
                            change(id) { it.put("sides", t) }; busyId = "" } }, enabled = busyId != id) { Text(if (busyId == id) "Thinking…" else "Both sides") }
                        TextButton({ Wants.store(c).remove(id); reload() }) { Text("Delete", color = m.error) }
                    }
                }
            }
        }
        if (skipped.isNotEmpty() || bought.isNotEmpty()) item("dh") { Text("Decided", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), fontWeight = FontWeight.SemiBold) }
        (skipped + bought).sortedByDescending { it.optLong("closed") }.take(30).forEach { o ->
            item("d_" + o.optString("id")) { Card2 {
                Text(o.optString("name") + " · " + rs(o.optDouble("price", 0.0)), fontSize = 14.sp)
                Row { Text(if (o.optString("status") == "skipped") "Skipped" else "Bought", fontSize = 12.sp, color = m.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp).weight(1f))
                    TextButton({ change(o.optString("id")) { it.put("status", "waiting").put("added", System.currentTimeMillis()) } }) { Text("Park again") } }
            } }
        }
        item("income") {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Your hour of work", fontWeight = FontWeight.SemiBold)
                Text("Used to show every price as hours of your life. Stays on this phone.", fontSize = 12.sp, color = m.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(income, { income = it; Wants.setIncome(c, num(it)) }, Modifier.weight(1.4f), label = { Text("Monthly income (Rs)") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(hours, { hours = it; if (num(it) > 0) Wants.setHoursWeek(c, num(it)) }, Modifier.weight(1f), label = { Text("Hours a week") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                if (hourly > 0) Text("One hour of your work is about ${rs(hourly)}.", fontSize = 13.sp, color = m.primary)
            }
        }
        item("end") { Spacer(Modifier.height(90.dp)) }
    }
}
