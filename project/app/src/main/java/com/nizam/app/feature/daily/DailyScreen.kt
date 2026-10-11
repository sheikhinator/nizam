package com.nizam.app.feature.daily

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.agent.Snapshot
import com.nizam.app.feature.phone.PhoneActions
import com.nizam.app.net.Ai
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) { content() } }
}

@Composable
fun DailyScreen(container: AppContainer) {
    var tab by remember { mutableStateOf("Air") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Daily life", "Air, kitchen, trips, upkeep — and help when you need it")
        ChipRow(listOf("Air", "Pantry & meals", "Trips", "SOS"), tab, { tab = it })
        Spacer(Modifier.height(8.dp))
        when (tab) {
            "Air" -> AirTab(container)
            "Pantry & meals" -> PantryTab(container)
            "Trips" -> TripsTab(container)
            else -> SosTab(container)
        }
        Text("Maintenance reminders live in the Life tab (🔧 Maintenance) — set a due date and Atlas reminds you on the day.",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun AirTab(container: AppContainer) {
    val c = LocalContext.current
    var air by remember { mutableStateOf(AirCache.get(c)) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        runCatching {
            Open.air(container.settingsRepository.latitude.first(), container.settingsRepository.longitude.first())
        }.onSuccess { air = it; AirCache.put(c, it) }.onFailure { error = it.message }
    }
    val a = air
    Card {
        if (a == null) Text(error ?: "Checking the air…")
        else {
            val colour = when { a.aqi <= 50 -> Color(0xFF4FC3A1); a.aqi <= 100 -> Color(0xFFE0B14A); a.aqi <= 150 -> Color(0xFFD98E4A)
                a.aqi <= 200 -> Color(0xFFE06A4C); else -> Color(0xFF9C4466) }
            Text("AQI ${a.aqi}", style = MaterialTheme.typography.displaySmall, color = colour)
            Text(Open.aqiLabel(a.aqi), style = MaterialTheme.typography.titleMedium, color = colour)
            Text("PM2.5 ${"%.0f".format(a.pm25)} µg/m³ · ${a.tempC.toInt()}°C, feels ${a.feels.toInt()}°C", style = MonoNumber)
            Spacer(Modifier.height(8.dp))
            Text(when {
                a.aqi > 200 -> "Stay indoors. Keep windows shut, run a purifier if you have one, and mask (N95) if you must go out."
                a.aqi > 150 -> "Skip outdoor exercise today — train indoors. Mask up outside."
                a.aqi > 100 -> "Sensitive? Keep outdoor effort light and short."
                else -> "Good day to be outside."
            }, style = MaterialTheme.typography.bodyLarge)
            if (a.feels >= 40) Text("Extreme heat: drink water through the day and avoid the sun from 11 to 4.",
                style = MaterialTheme.typography.bodyMedium, color = Color(0xFFE06A4C))
        }
    }
    Text("Atlas checks hourly and warns you on bad-air mornings. The Curator also knows today's air when you ask about training.",
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun PantryTab(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var items by remember { mutableStateOf(Pantry.all(c)) }
    var name by remember { mutableStateOf("") }
    var qty by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("7") }
    var plan by remember { mutableStateOf(c.getSharedPreferences("meals", 0).getString("plan", "").orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    val today = LocalDate.now().toString()
    Card {
        Text("Add to pantry", style = MaterialTheme.typography.titleMedium)
        Row {
            OutlinedTextField(name, { name = it }, label = { Text("Item") }, singleLine = true, modifier = Modifier.weight(1.4f))
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(qty, { qty = it }, label = { Text("Qty") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Text("Good for", style = MaterialTheme.typography.labelMedium)
        ChipRow(listOf("2", "4", "7", "14", "30", "180"), days, { days = it }, labels = { "$it days" })
        Button(enabled = name.isNotBlank(), onClick = {
            items = items + PantryItem(System.currentTimeMillis(), name.trim(), qty.trim(), LocalDate.now().plusDays(days.toLong()).toString())
            Pantry.save(c, items); name = ""; qty = ""
        }) { Text("Add") }
    }
    items.sortedBy { it.expires }.forEach { i ->
        val soon = i.expires <= LocalDate.now().plusDays(2).toString()
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(i.name + if (i.qty.isNotBlank()) " · ${i.qty}" else "", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(if (i.expires < today) "expired" else if (soon) "use soon" else i.expires.takeLast(5),
                style = MaterialTheme.typography.labelMedium, color = if (soon) Color(0xFFE06A4C) else MaterialTheme.colorScheme.onSurfaceVariant)
            Text("  ✕", color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(role = Role.Button) { items = items - i; Pantry.save(c, items) })
        }
    }
    Spacer(Modifier.height(8.dp))
    Button(enabled = !busy, onClick = {
        scope.launch {
            busy = true
            plan = runCatching {
                Ai.generate(container.settingsRepository,
                    "${Snapshot.of(container)}\n\nPantry (item · qty · use-by): " +
                        items.joinToString("; ") { "${it.name} · ${it.qty} · ${it.expires}" }.ifBlank { "empty" } +
                        "\n\nPlan the next 7 days of meals (breakfast, lunch, dinner) for them: halal, Pakistani-friendly, hitting " +
                        "their calorie target, and using up the items that expire soonest first. Then give a GROCERY LIST of only " +
                        "what's missing, grouped by aisle, with rough PKR prices. Plain text, compact.",
                    maxOutputTokens = 2500)
            }.getOrElse { "Couldn't plan: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
            c.getSharedPreferences("meals", 0).edit().putString("plan", plan).apply()
            busy = false
        }
    }) { Text(if (busy) "Planning…" else "Plan my week of meals") }
    if (plan.isNotBlank()) {
        Card { Text(plan, style = MaterialTheme.typography.bodyMedium) }
        OutlinedButton(onClick = {
            scope.launch {
                val list = plan.substringAfter("GROCERY", "").lines().map { it.trim().trimStart('-', '•', '*', ' ') }
                    .filter { it.length in 3..60 && !it.endsWith(":") && !it.contains("LIST", true) }
                list.take(40).forEach { container.taskRepository.add("🛒 $it", null, 0) }
            }
        }) { Text("Add grocery list to Tasks") }
    }
}

@Composable
private fun TripsTab(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var trips by remember { mutableStateOf(Trips.all(c)) }
    var dest by remember { mutableStateOf("") }
    var inDays by remember { mutableStateOf("7") }
    var nights by remember { mutableStateOf("3") }
    var purpose by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Card {
        Text("Plan a trip", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(dest, { dest = it }, label = { Text("Where? e.g. Islamabad, Dubai, Istanbul") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(purpose, { purpose = it }, label = { Text("What for? (work, family, holiday…)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row {
            OutlinedTextField(inDays, { inDays = it.filter(Char::isDigit) }, label = { Text("Leaving in (days)") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(nights, { nights = it.filter(Char::isDigit) }, label = { Text("Nights") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Button(enabled = dest.isNotBlank() && !busy, onClick = {
            scope.launch {
                busy = true; error = null
                runCatching {
                    val place = Open.geocode(dest.trim()) ?: throw IllegalStateException("Couldn't find “$dest”.")
                    val start = LocalDate.now().plusDays(inDays.toLongOrNull() ?: 7)
                    val end = start.plusDays(nights.toLongOrNull() ?: 3)
                    val weather = if (start <= LocalDate.now().plusDays(14)) Open.forecast(place.lat, place.lon, start.toString(), end.toString()) else ""
                    val prayers = Open.prayerTimesAt(place.lat, place.lon, start)
                    val plan = Ai.generate(container.settingsRepository,
                        "${Snapshot.of(container)}\n\nTrip: ${place.name}, ${place.country}, $start to $end (${nights} nights). " +
                            "Purpose: ${purpose.ifBlank { "unspecified" }}.\nForecast: ${weather.ifBlank { "too far ahead to forecast" }}\n" +
                            "Prayer times there on arrival day (local time): $prayers\n\n" +
                            "Write: a PACKING LIST fitted to the weather and purpose; a light day-by-day ITINERARY that leaves room for " +
                            "prayers; local currency and the rough exchange rate from PKR; the qibla direction from there in plain words; " +
                            "and 3 practical tips (transport, SIM, halal food). Compact plain text.", maxOutputTokens = 2200)
                    val t = Trip(System.currentTimeMillis(), "${place.name}, ${place.country}", start.toString(), end.toString(),
                        purpose, "Prayer times: $prayers\n${if (weather.isNotBlank()) "Weather: $weather\n" else ""}\n$plan", place.lat, place.lon)
                    trips = listOf(t) + trips; Trips.save(c, trips); dest = ""; purpose = ""
                }.onFailure { error = it.message }
                busy = false
            }
        }) { Text(if (busy) "Planning…" else "Plan it") }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    trips.forEach { t ->
        var open by remember(t.id) { mutableStateOf(false) }
        Card {
            Text("✈ ${t.destination}", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable(role = Role.Button) { open = !open })
            Text("${t.start} > ${t.end}${if (t.purpose.isNotBlank()) " · ${t.purpose}" else ""}", style = MaterialTheme.typography.labelMedium)
            if (open) Text(t.plan, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            Text(if (open) "hide" else "show plan", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(role = Role.Button) { open = !open }.padding(top = 4.dp))
        }
    }
}

@SuppressLint("MissingPermission")
private fun lastLocation(c: Context): android.location.Location? {
    val lm = c.getSystemService(LocationManager::class.java) ?: return null
    return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
}

@Composable
private fun SosTab(container: AppContainer) {
    val c = LocalContext.current
    var contacts by remember { mutableStateOf(Sos.contacts(c).joinToString("\n")) }
    var card by remember { mutableStateOf(Sos.card(c)) }
    var status by remember { mutableStateOf<String?>(null) }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val ready = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(c, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    fun trigger() {
        val list = Sos.contacts(c)
        if (list.isEmpty()) { status = "Add at least one emergency contact first."; return }
        val loc = runCatching { lastLocation(c) }.getOrNull()
        val link = loc?.let { "https://maps.google.com/?q=${it.latitude},${it.longitude}" } ?: "location unavailable"
        val msg = "SOS — I need help. My location: $link (sent from Atlas)"
        val sent = list.map { PhoneActions.sms(c, it, msg, sendDirectly = true) }
        PhoneActions.call(c, list.first())
        status = "Alert sent to ${sent.count { it.ok }} of ${list.size} contacts. Calling ${list.first()}."
    }

    Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(180.dp).background(Color(0xFFD7263D), CircleShape)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { trigger() }, onTap = { status = "Hold the button for a second to send the alert." }) },
            contentAlignment = Alignment.Center) {
            Text("SOS\nhold", color = Color.White, fontSize = 30.sp, textAlign = TextAlign.Center)
        }
    }
    status?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge) }
    Text("Holding sends your live location by SMS to your emergency contacts and calls the first one.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!ready) OutlinedButton(onClick = { perms.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE,
        Manifest.permission.READ_CONTACTS)) }) { Text("Grant location, SMS and call access") }
    Card {
        Text("Emergency contacts", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(contacts, { contacts = it }, label = { Text("One per line — names from your contacts, or numbers") },
            modifier = Modifier.fillMaxWidth())
        Text("Medical card", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        OutlinedTextField(card, { card = it }, label = { Text("Blood group, allergies, medicines, anything a medic should know") },
            modifier = Modifier.fillMaxWidth())
        Button(onClick = { Sos.setContacts(c, contacts.lines()); Sos.setCard(c, card); status = "Saved." }) { Text("Save") }
    }
    if (card.isNotBlank()) Card {
        Text("MEDICAL CARD", style = MaterialTheme.typography.labelMedium, color = Color(0xFFD7263D))
        Text(card, style = MaterialTheme.typography.bodyLarge)
    }
}
