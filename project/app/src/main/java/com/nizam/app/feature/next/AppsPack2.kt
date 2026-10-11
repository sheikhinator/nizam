package com.nizam.app.feature.next

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsCar
import androidx.compose.material.icons.outlined.Inventory2
import androidx.compose.material.icons.outlined.LocalGasStation
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nizam.app.AppContainer
import com.nizam.app.net.Ai
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
import org.json.JSONObject
import java.time.LocalDate

private fun money(v: Double) = "Rs " + "%,d".format(v.toLong())

// ── Vehicle log ──────────────────────────────────────────────────────────────────────────────────

/** Fuel fill-ups and services for your car or bike: real km per litre, cost per km, next oil change. */
@Composable
fun VehicleScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { JsonStore(ctx, "vehicle") }
    var list by remember { mutableStateOf(store.all()) }
    var kind by remember { mutableStateOf("Fuel") }
    var odo by remember { mutableStateOf("") }
    var litres by remember { mutableStateOf("") }
    var cost by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    fun reload() { list = store.all() }
    val fuel = list.filter { it.optString("k") == "Fuel" }.sortedBy { it.optDouble("odo") }
    val kmpl = if (fuel.size >= 2) {
        val km = fuel.last().optDouble("odo") - fuel.first().optDouble("odo")
        val l = fuel.drop(1).sumOf { it.optDouble("l") }
        if (l > 0) km / l else null
    } else null
    val spent = list.sumOf { it.optDouble("cost") }
    val lastOil = list.filter { it.optString("k") == "Service" && it.optString("note").contains("oil", true) }.maxByOrNull { it.optDouble("odo") }
    val lastOdo = list.maxOfOrNull { it.optDouble("odo") } ?: 0.0
    AtlasScreen(title = "Vehicle", subtitle = "Fuel, mileage and services", hub = Hub.Travel) {
        item("s") {
            Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                StatTile("Average", kmpl?.let { "%.1f km/L".format(it) } ?: "—", Modifier.weight(1f))
                StatTile("Spent", money(spent), Modifier.weight(1f))
            }
        }
        lastOil?.let { o ->
            item("oil") {
                val next = o.optDouble("odo") + 3000
                ListRow("Next oil change around ${"%,d".format(next.toLong())} km", supporting = if (lastOdo >= next) "Overdue" else "${"%,d".format((next - lastOdo).toLong())} km to go",
                    icon = Icons.Outlined.Build, tint = if (lastOdo >= next) MaterialTheme.colorScheme.error else Hub.Travel.tint())
            }
        }
        item("add") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                Segmented(listOf("Fuel", "Service", "Other"), kind, { kind = it }, { it })
                OutlinedTextField(odo, { odo = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Odometer (km)") }, singleLine = true)
                if (kind == "Fuel") OutlinedTextField(litres, { litres = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.fillMaxWidth(), label = { Text("Litres") }, singleLine = true)
                OutlinedTextField(cost, { cost = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.fillMaxWidth(), label = { Text("Cost (Rs)") }, singleLine = true)
                if (kind != "Fuel") OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), label = { Text("What (e.g. oil change, tyres, token tax)") }, singleLine = true)
                Button(onClick = {
                    val c = cost.toDoubleOrNull() ?: 0.0
                    store.add(JSONObject().put("k", kind).put("odo", odo.toDoubleOrNull() ?: lastOdo).put("l", litres.toDoubleOrNull() ?: 0.0)
                        .put("cost", c).put("note", note.trim()).put("d", LocalDate.now().toString()))
                    if (c > 0) scope.launch { container.financeRepository.add(c, "EXPENSE", "Transport", if (kind == "Fuel") "Fuel" else note.ifBlank { kind }) }
                    odo = ""; litres = ""; cost = ""; note = ""; reload()
                }, modifier = Modifier.padding(top = Space.s)) { Text("Log it") }
            }
        }
        item("h") { SectionHeader("History") }
        if (list.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.DirectionsCar, "Nothing logged", "Log your next fill-up; two fill-ups give your real average.")) }
        list.sortedByDescending { it.optDouble("odo") }.forEach { o ->
            item(o.optString("id")) {
                ListRow(if (o.optString("k") == "Fuel") "Fuel · ${o.optDouble("l")} L" else o.optString("note").ifBlank { o.optString("k") },
                    supporting = "${o.optString("d")} · ${"%,d".format(o.optDouble("odo").toLong())} km · ${money(o.optDouble("cost"))}",
                    icon = if (o.optString("k") == "Fuel") Icons.Outlined.LocalGasStation else Icons.Outlined.Build, tint = Hub.Travel.tint())
            }
        }
    }
}

// ── Pet care ─────────────────────────────────────────────────────────────────────────────────────

@Composable
fun PetsScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { JsonStore(ctx, "pets") }
    var list by remember { mutableStateOf(store.all()) }
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("") }
    var what by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }
    fun reload() { list = store.all() }
    AtlasScreen(title = "Pets", subtitle = "Vaccines, vet visits, food and weight", hub = Hub.Home) {
        if (list.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.Pets, "No pets yet", "Add your cat, dog, parrot or goat below.")) }
        list.forEach { p ->
            val id = p.optString("id")
            item(id) { SectionHeader("${p.optString("name")} · ${p.optString("kind")}") }
            val care = p.optJSONArray("care")
            (0 until (care?.length() ?: 0)).map { care!!.getJSONObject(it) }.forEachIndexed { i, cItem ->
                item("$id$i") { ListRow(cItem.optString("w"), supporting = cItem.optString("d").let { if (it.isBlank()) "" else "Due $it" }, icon = Icons.Outlined.Pets, tint = Hub.Home.tint()) }
            }
            item("a$id") {
                Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    OutlinedTextField(what, { what = it }, Modifier.weight(1f), placeholder = { Text("Rabies shot, deworming…") }, singleLine = true)
                    OutlinedTextField(due, { due = it }, Modifier.weight(0.8f), placeholder = { Text("yyyy-mm-dd") }, singleLine = true)
                }
                Button(onClick = {
                    if (what.isNotBlank()) {
                        store.update(id) { it.put("care", (it.optJSONArray("care") ?: org.json.JSONArray()).put(JSONObject().put("w", what.trim()).put("d", due.trim()))) }
                        runCatching { java.time.LocalDate.parse(due.trim()) }.getOrNull()?.let { d ->
                            scope.launch { container.taskRepository.add("${p.optString("name")}: ${what.trim()}", d.atTime(10, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), 1) }
                        }
                        what = ""; due = ""; reload()
                    }
                }, modifier = Modifier.padding(horizontal = Space.page, vertical = Space.s)) { Text("Add care item") }
            }
        }
        item("new") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Pet's name") }, singleLine = true)
                OutlinedTextField(kind, { kind = it }, Modifier.fillMaxWidth(), label = { Text("Animal") }, singleLine = true)
                Button(onClick = { if (name.isNotBlank()) { store.add(JSONObject().put("name", name.trim()).put("kind", kind.trim())); name = ""; kind = ""; reload() } },
                    modifier = Modifier.padding(top = Space.s)) { Text("Add pet") }
            }
        }
    }
}

// ── Home inventory ───────────────────────────────────────────────────────────────────────────────

/** What you own, what it cost and when its warranty ends: ready for insurance, moving or selling. */
@Composable
fun InventoryScreen() {
    val ctx = LocalContext.current
    val store = remember { JsonStore(ctx, "inventory") }
    var list by remember { mutableStateOf(store.all()) }
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    var warranty by remember { mutableStateOf("") }
    var room by remember { mutableStateOf("") }
    fun reload() { list = store.all() }
    AtlasScreen(title = "Home inventory", subtitle = "Total value ${money(list.sumOf { it.optDouble("price") })}", hub = Hub.Home) {
        if (list.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.Inventory2, "Nothing listed", "Add your valuables: TV, fridge, AC, laptop, jewellery.")) }
        list.groupBy { it.optString("room").ifBlank { "Other" } }.forEach { (r, items) ->
            item("h$r") { SectionHeader(r) }
            items.forEach { o ->
                item(o.optString("id")) {
                    val w = runCatching { LocalDate.parse(o.optString("warranty")) }.getOrNull()
                    ListRow(o.optString("name"), supporting = money(o.optDouble("price")) + (w?.let { if (it.isBefore(LocalDate.now())) " · warranty ended" else " · warranty until $it" } ?: ""),
                        icon = Icons.Outlined.Inventory2, tint = Hub.Home.tint(), trailing = {
                            Text("Remove", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(Space.s).pressable { store.remove(o.optString("id")); reload() })
                        })
                }
            }
        }
        item("add") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Item") }, singleLine = true)
                OutlinedTextField(price, { price = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(), label = { Text("Price (Rs)") }, singleLine = true)
                OutlinedTextField(room, { room = it }, Modifier.fillMaxWidth(), label = { Text("Room") }, singleLine = true)
                OutlinedTextField(warranty, { warranty = it }, Modifier.fillMaxWidth(), label = { Text("Warranty ends (yyyy-mm-dd, optional)") }, singleLine = true)
                Button(onClick = {
                    if (name.isNotBlank()) { store.add(JSONObject().put("name", name.trim()).put("price", price.toDoubleOrNull() ?: 0.0).put("room", room.trim()).put("warranty", warranty.trim())); name = ""; price = ""; warranty = ""; reload() }
                }, modifier = Modifier.padding(top = Space.s)) { Text("Add") }
            }
        }
    }
}

// ── Travel planner ───────────────────────────────────────────────────────────────────────────────

/** A day-by-day plan, budget and packing list for a trip — Hunza, Murree, Umrah, anywhere. */
@Composable
fun TravelPlannerScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var where by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("4") }
    var who by remember { mutableStateOf("Family") }
    var budget by remember { mutableStateOf("Mid-range") }
    var plan by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    AtlasScreen(title = "Travel planner", subtitle = "Itinerary, budget in rupees and a packing list", hub = Hub.Travel) {
        item("f") {
            Column(Modifier.padding(horizontal = Space.page)) {
                OutlinedTextField(where, { where = it }, Modifier.fillMaxWidth(), label = { Text("Where to") }, singleLine = true)
                OutlinedTextField(days, { days = it.filter(Char::isDigit).take(2) }, Modifier.fillMaxWidth(), label = { Text("Days") }, singleLine = true)
                Segmented(listOf("Solo", "Couple", "Family", "Friends"), who, { who = it }, { it })
                Segmented(listOf("Budget", "Mid-range", "Comfort"), budget, { budget = it }, { it })
                Button(onClick = {
                    busy = true
                    scope.launch {
                        plan = runCatching {
                            Ai.generate(container.settingsRepository,
                                "Plan a ${days}-day trip to $where for $who travelling from Pakistan, $budget budget. Give: a day-by-day itinerary with realistic travel times; " +
                                    "an estimated budget in PKR (transport, stay, food, extras); prayer-friendly timing and halal food notes; weather and road/safety tips for the season " +
                                    "(it is ${LocalDate.now().month}); and a packing checklist. Use short headings and bullet points.",
                                system = "You are an expert Pakistani travel planner. Be practical and specific.", maxOutputTokens = 1400).trim()
                        }.getOrElse { Feedback.failed("Planning", it); "" }
                        busy = false
                    }
                }, enabled = where.isNotBlank() && !busy, modifier = Modifier.padding(top = Space.s)) { Text(if (busy) "Planning…" else "Plan my trip") }
            }
        }
        if (plan.isNotBlank()) item("p") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                SelectionContainer { com.nizam.app.ui.components.ChatText(plan) }
                Row(Modifier.padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    OutlinedButton(onClick = {
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, plan), "Share plan"))
                    }) { Text("Share") }
                    OutlinedButton(onClick = {
                        runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=" + android.net.Uri.encode(where)))) }
                    }) { Text("Open in Maps") }
                }
            }
        }
    }
}
