package com.nizam.app.feature.apps2

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import android.provider.ContactsContract
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.AiSpec
import com.nizam.app.feature.nx.F
import com.nizam.app.feature.nx.K
import com.nizam.app.feature.nx.LogSpec
import com.nizam.app.feature.nx.Md
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Share
import com.nizam.app.feature.nx.Spec
import com.nizam.app.feature.nx.num
import com.nizam.app.feature.nx.rs
import com.nizam.app.net.Ai
import com.nizam.app.net.Http
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.EmptySpec
import com.nizam.app.ui.design.EmptyState
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

object Life2 {
    val all: List<Spec> = listOf(
        ScreenSpec("calendar", "Calendar", "Your phone's calendar: month view, agenda, quick add", Icons.Outlined.CalendarMonth, Hub.Work, "calendar events schedule meeting agenda") { _, _ -> CalendarScreen() },
        ScreenSpec("contactsplus", "Contacts+", "People with notes, tags, quick WhatsApp, duplicate finder", Icons.Outlined.Contacts, Hub.Social, "contacts phone book notes tags duplicate whatsapp") { _, _ -> ContactsPlusScreen() },
        ScreenSpec("cycle", "Cycle tracker", "Period predictions and fertile window — private, encrypted", Icons.Outlined.WaterDrop, Hub.Health, "period cycle menstrual ovulation fertile women") { _, _ -> CycleScreen() },
        ScreenSpec("sports", "Sports centre", "Football fixtures and results, cricket live", Icons.Outlined.SportsSoccer, Hub.Media, "football cricket fixtures results premier league psl") { c, _ -> SportsScreen(c) },
        LogSpec("sleeplog", "Sleep diary", "Bedtime, wake time, how you slept — averages and trend", Icons.Outlined.Bedtime, Hub.Health, "sleep diary insomnia bedtime",
            listOf(F("d", "Night of", K.DATE), F("bed", "Bedtime (HH:MM)", default = "23:30"), F("wake", "Woke (HH:MM)", default = "07:00"),
                F("q", "How was it", K.CHOICE, options = listOf("Great", "OK", "Poor"), default = "OK"), F("note", "Note", optional = true)),
            line = { o -> "${o.optString("d")} · ${hrs(o)} h" to "${o.optString("bed")}–${o.optString("wake")} · ${o.optString("q")} ${o.optString("note")}" },
            summary = { l -> val last7 = l.take(7).map { hrs(it) }.filter { it > 0 }
                listOf(if (last7.isEmpty()) "Log a few nights" else "Average ${"%.1f".format(last7.average())} h over the last ${last7.size} nights",
                    "Poor nights this week: ${l.take(7).count { it.optString("q") == "Poor" }}",
                    if (last7.isNotEmpty() && last7.average() < 6.5) "Adults need 7–9 h. Try a fixed wake time, and screens off after Isha." else "") }),
        LogSpec("healthrec", "Health records", "Tests, prescriptions, vaccines and visits — encrypted", Icons.Outlined.MedicalInformation, Hub.Health, "medical records lab report prescription vaccine doctor",
            listOf(F("kind", "Type", K.CHOICE, options = listOf("Lab test", "Prescription", "Vaccine", "Visit", "Surgery", "Allergy"), default = "Lab test"),
                F("what", "What"), F("d", "Date", K.DATE), F("who", "Doctor / lab", optional = true), F("result", "Result / notes", K.LONG, optional = true)),
            sealed = true, line = { o -> "${o.optString("kind")} · ${o.optString("what")}" to "${o.optString("d")} ${o.optString("who")} ${o.optString("result").take(80)}" },
            summary = { l -> listOf("${l.size} records", l.filter { it.optString("kind") == "Allergy" }.joinToString { it.optString("what") }.let { if (it.isBlank()) "" else "Allergies: $it" }) }),
        LogSpec("garden", "Garden", "Plants and when they need water or feed", Icons.Outlined.Yard, Hub.Home, "plants garden watering money plant balcony",
            listOf(F("plant", "Plant"), F("every", "Water every (days)", K.NUMBER, default = "3"), F("last", "Last watered", K.DATE), F("where", "Where", optional = true)),
            line = { o -> o.optString("plant") to "Every ${o.optString("every")} days · last ${o.optString("last")} ${o.optString("where")}" },
            summary = { l -> l.mapNotNull { o -> runCatching { LocalDate.parse(o.optString("last")).plusDays(num(o.optString("every")).toLong()) }.getOrNull()?.let { o to it } }
                .filter { !it.second.isAfter(LocalDate.now()) }.map { "Water today: ${it.first.optString("plant")}" }.ifEmpty { listOf("Everyone's watered 🌱") } }),
        LogSpec("portfolio", "Investments", "Shares, funds, gold and savings: cost vs value", Icons.Outlined.ShowChart, Hub.Money, "psx stocks shares mutual fund portfolio invest",
            listOf(F("name", "Holding", hint = "ENGRO, Meezan fund, gold"), F("qty", "Quantity", K.NUMBER), F("cost", "Buy price each", K.NUMBER), F("now", "Price now", K.NUMBER)),
            line = { o -> val q = num(o.optString("qty")); val pl = (num(o.optString("now")) - num(o.optString("cost"))) * q
                o.optString("name") to "${o.optString("qty")} × ${rs(num(o.optString("now")))} = ${rs(num(o.optString("now")) * q)} · ${if (pl >= 0) "+" else ""}${rs(pl)}" },
            summary = { l -> val cost = l.sumOf { num(it.optString("cost")) * num(it.optString("qty")) }; val v = l.sumOf { num(it.optString("now")) * num(it.optString("qty")) }
                listOf("Value ${rs(v)}", "Invested ${rs(cost)} · ${if (v >= cost) "up" else "down"} ${rs(kotlin.math.abs(v - cost))} (${if (cost > 0) ((v - cost) / cost * 100).toInt() else 0}%)") }),
        AiSpec("marketcheck", "Market check", "PSX, gold, dollar and your holdings, today", Icons.Outlined.TrendingUp, Hub.Money, "psx kse100 market stocks today",
            listOf(F("what", "Stocks or funds to check", optional = true, hint = "ENGRO, LUCK, Meezan Islamic Fund")),
            "You report current Pakistani market data with the time and source: KSE-100 level and change, the named stocks' prices, gold per tola, open-market dollar. No advice to buy or sell.",
            web = true, button = "Check") { v -> "Market update now. Also these: ${v["what"]}" },
        AiSpec("shop", "Smart shopper", "Best price in Pakistan, real reviews, what to buy", Icons.Outlined.ShoppingBag, Hub.Money, "buy compare price daraz priceoye review best phone",
            listOf(F("what", "What do you want to buy?"), F("budget", "Budget (Rs)", K.NUMBER, optional = true), F("need", "What matters most", optional = true)),
            "You are an honest shopping advisor for Pakistan. Compare current prices on Daraz, PriceOye, Telemart, official stores and markets, with links. Summarise real reviews, warn about fakes and warranty issues, and recommend one best buy and one budget pick.",
            web = true, button = "Find the best buy") { v -> "Buy: ${v["what"]}. Budget: Rs ${v["budget"]}. Priorities: ${v["need"]}." },
        AiSpec("events", "Events near me", "Concerts, exhibitions, talks, sports and family days", Icons.Outlined.Event, Hub.Social, "events concerts things to do weekend city",
            listOf(F("city", "City", default = "Lahore"), F("kind", "Kind", K.CHOICE, options = listOf("Anything", "Family", "Music", "Food", "Tech & talks", "Sports", "Art", "Religious"), default = "Anything"),
                F("when", "When", K.CHOICE, options = listOf("This weekend", "This week", "This month"), default = "This weekend")),
            "You find real upcoming events with dates, venues, ticket prices and links. Only list events you found sources for.", web = true, button = "Find events") { v ->
            "${v["kind"]} events in ${v["city"]}, Pakistan, ${v["when"]}." },
    )
    fun hrs(o: JSONObject): Double = runCatching {
        val b = java.time.LocalTime.parse(o.optString("bed").padStart(5, '0')); val w = java.time.LocalTime.parse(o.optString("wake").padStart(5, '0'))
        val m = ChronoUnit.MINUTES.between(b, w).let { if (it < 0) it + 1440 else it }; m / 60.0
    }.getOrDefault(0.0).let { Math.round(it * 10) / 10.0 }
}

// ─────────────────────────────── Calendar ───────────────────────────────
private data class Ev(val id: Long, val title: String, val start: Long, val end: Long, val allDay: Boolean, val where: String, val colour: Int)

@Composable
fun CalendarScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = it[Manifest.permission.READ_CALENDAR] == true }
    var month by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1)) }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var events by remember { mutableStateOf<List<Ev>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    var time by remember { mutableStateOf("10:00") }
    var reload by remember { mutableIntStateOf(0) }
    val zone = ZoneId.systemDefault()
    LaunchedEffect(granted, month, reload) {
        if (!granted) return@LaunchedEffect
        events = withContext(Dispatchers.IO) { runCatching {
            val from = month.minusDays(7).atStartOfDay(zone).toInstant().toEpochMilli(); val to = month.plusMonths(1).plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also { ContentUris.appendId(it, from); ContentUris.appendId(it, to) }.build()
            val out = mutableListOf<Ev>()
            ctx.contentResolver.query(uri, arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END,
                CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.DISPLAY_COLOR), null, null, CalendarContract.Instances.BEGIN)?.use { c ->
                while (c.moveToNext()) out += Ev(c.getLong(0), c.getString(1) ?: "(no title)", c.getLong(2), c.getLong(3), c.getInt(4) == 1, c.getString(5) ?: "", c.getInt(6))
            }
            out
        }.getOrDefault(emptyList()) }
    }
    fun dateOf(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val byDay = events.groupBy { dateOf(it.start) }
    AtlasScreen(title = month.month.name.lowercase().replaceFirstChar { it.uppercase() } + " ${month.year}", subtitle = "${events.count { dateOf(it.start).month == month.month }} events this month", hub = Hub.Work, actions = {
        Row { Text("‹", Modifier.padding(Space.s).pressable { month = month.minusMonths(1) }, style = MaterialTheme.typography.titleLarge)
            Text("›", Modifier.padding(Space.s).pressable { month = month.plusMonths(1) }, style = MaterialTheme.typography.titleLarge) }
    }) {
        if (!granted) { item("p") { Button(onClick = { perm.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }, modifier = Modifier.padding(Space.page)) { Text("Allow calendar access") } }; return@AtlasScreen }
        item("grid") { Column(Modifier.padding(horizontal = Space.page)) {
            Row { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
            val start = month.minusDays((month.dayOfWeek.value - 1).toLong())
            (0 until 6).forEach { w -> Row {
                (0 until 7).forEach { d -> val date = start.plusDays((w * 7 + d).toLong()); val inMonth = date.month == month.month
                    val sel = date == day; val today = date == LocalDate.now(); val n = byDay[date]?.size ?: 0
                    Column(Modifier.weight(1f).aspectRatio(1f).padding(2.dp).clip(RoundedCornerShape(10.dp))
                        .background(if (sel) Hub.Work.tint() else Color.Transparent).pressable { day = date }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Text("${date.dayOfMonth}", color = when { sel -> Color.White; !inMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f); else -> MaterialTheme.colorScheme.onSurface },
                            fontWeight = if (today) FontWeight.Bold else FontWeight.Normal)
                        if (n > 0) Box(Modifier.size(5.dp).background(if (sel) Color.White else Hub.Work.tint(), CircleShape))
                    }
                } } }
        } }
        item("dh") { SectionHeader(day.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() } + ", ${day.dayOfMonth} ${day.month.name.lowercase().replaceFirstChar { it.uppercase() }}") }
        val list = byDay[day].orEmpty()
        if (list.isEmpty()) item("none") { Text("Nothing planned.", Modifier.padding(horizontal = Space.page), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        list.forEach { e -> item("e${e.id}${e.start}") {
            val t = if (e.allDay) "All day" else java.time.Instant.ofEpochMilli(e.start).atZone(zone).toLocalTime().toString().take(5) + "–" + java.time.Instant.ofEpochMilli(e.end).atZone(zone).toLocalTime().toString().take(5)
            ListRow(e.title, supporting = t + if (e.where.isNotBlank()) " · ${e.where}" else "", icon = Icons.Outlined.Event, tint = Color(e.colour or 0xFF000000.toInt())) {
                ctx.startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, e.id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        } }
        item("add") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            Text("Add to ${day}", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedTextField(title, { title = it }, Modifier.weight(2f), placeholder = { Text("What") }, singleLine = true)
                OutlinedTextField(time, { time = it }, Modifier.weight(1f), placeholder = { Text("HH:MM") }, singleLine = true)
            }
            Button(onClick = {
                scope.launch { runCatching { withContext(Dispatchers.IO) {
                    val calId = ctx.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, arrayOf(CalendarContract.Calendars._ID),
                        "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}", null, "${CalendarContract.Calendars.IS_PRIMARY} DESC")?.use { if (it.moveToFirst()) it.getLong(0) else null }
                        ?: throw IllegalStateException("No writable calendar on this phone")
                    val t0 = day.atTime(java.time.LocalTime.parse(time.padStart(5, '0'))).atZone(zone).toInstant().toEpochMilli()
                    ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, ContentValues().apply {
                        put(CalendarContract.Events.CALENDAR_ID, calId); put(CalendarContract.Events.TITLE, title); put(CalendarContract.Events.DTSTART, t0)
                        put(CalendarContract.Events.DTEND, t0 + 3_600_000); put(CalendarContract.Events.EVENT_TIMEZONE, zone.id) })
                } }.onSuccess { title = ""; reload++; Feedback.show("Added") }.onFailure { Feedback.show(it.message ?: "Couldn't add (allow calendar write access)") } }
            }, enabled = title.isNotBlank(), modifier = Modifier.padding(top = Space.s)) { Text("Add event") }
        } }
    }
}

// ─────────────────────────────── Contacts+ ───────────────────────────────
private data class Person(val id: Long, val name: String, val phones: List<String>)

@Composable
fun ContactsPlusScreen() {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var people by remember { mutableStateOf<List<Person>?>(null) }
    var q by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("All") }
    var open by remember { mutableStateOf<Person?>(null) }
    val notes = remember { ctx.getSharedPreferences("contact_notes", 0) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(granted) {
        if (!granted) return@LaunchedEffect
        people = withContext(Dispatchers.IO) { runCatching {
            val m = LinkedHashMap<Long, Pair<String, MutableList<String>>>()
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)?.use { c ->
                while (c.moveToNext()) { val id = c.getLong(0); val e = m.getOrPut(id) { (c.getString(1) ?: "?") to mutableListOf() }
                    val n = (c.getString(2) ?: "").replace(Regex("[\\s-]"), ""); if (n !in e.second) e.second += n }
            }
            m.map { (id, v) -> Person(id, v.first, v.second) }
        }.getOrDefault(emptyList()) }
    }
    fun norm(n: String) = n.replace(Regex("[^0-9]"), "").takeLast(10)
    val o = open
    if (o != null) {
        var note by remember(o.id) { mutableStateOf(notes.getString("n${o.id}", "").orEmpty()) }
        var tags by remember(o.id) { mutableStateOf(notes.getString("t${o.id}", "").orEmpty()) }
        AtlasScreen(title = o.name, subtitle = o.phones.joinToString(), hub = Hub.Social, actions = {
            Text("Back", Modifier.padding(Space.s).pressable { open = null; tick++ }, style = MaterialTheme.typography.labelLarge) }) {
            o.phones.forEach { ph -> item(ph) { Row(Modifier.padding(horizontal = Space.page, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                Text(ph, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Capsule("Call", false) { ctx.startActivity(Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:$ph")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                Capsule("WhatsApp", false) { Share.open(ctx, "https://wa.me/" + norm(ph).let { if (it.length == 10) "92$it" else it }) }
                Capsule("SMS", false) { ctx.startActivity(Intent(Intent.ACTION_SENDTO, android.net.Uri.parse("smsto:$ph")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            } } }
            item("tags") { OutlinedTextField(tags, { tags = it; notes.edit().putString("t${o.id}", it).apply() }, Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s),
                label = { Text("Tags (comma separated)") }, placeholder = { Text("family, office, plumber") }, singleLine = true) }
            item("note") { OutlinedTextField(note, { note = it; notes.edit().putString("n${o.id}", it).apply() }, Modifier.fillMaxWidth().padding(horizontal = Space.page),
                label = { Text("Notes") }, placeholder = { Text("Kids' names, where you met, what they like…") }, minLines = 4) }
            item("edit") { OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(ContactsContract.Contacts.CONTENT_URI, o.id)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                modifier = Modifier.padding(Space.page)) { Text("Open in Contacts") } }
        }
        return
    }
    val all = people.orEmpty()
    val tagOf = { p: Person -> notes.getString("t${p.id}", "").orEmpty() }
    val dupes = remember(all) { all.groupBy { it.phones.firstOrNull()?.let { n -> norm(n) } ?: "" }.filter { it.key.length >= 7 && it.value.size > 1 }.values.toList() }
    val tags = remember(all, tick) { all.flatMap { tagOf(it).split(',').map { t -> t.trim() }.filter { t -> t.isNotBlank() } }.distinct() }
    AtlasScreen(title = "Contacts+", subtitle = "${all.size} people · ${dupes.size} possible duplicates", hub = Hub.Social, loading = granted && people == null) {
        if (!granted) { item("p") { Button(onClick = { perm.launch(Manifest.permission.READ_CONTACTS) }, modifier = Modifier.padding(Space.page)) { Text("Allow contacts access") } }; return@AtlasScreen }
        item("q") { OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = Space.page), placeholder = { Text("Name, number, tag or note") }, singleLine = true) }
        item("tabs") { androidx.compose.foundation.lazy.LazyRow(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            val ts = listOf("All", "Duplicates") + tags; items(ts.size) { i -> Capsule(ts[i], tab == ts[i]) { tab = ts[i] } } } }
        if (tab == "Duplicates") {
            if (dupes.isEmpty()) item("nd") { EmptyState(EmptySpec(Icons.Outlined.Contacts, "No duplicates", "Every number belongs to one contact.")) }
            dupes.forEachIndexed { i, g -> item("d$i") { Group(Modifier.padding(horizontal = Space.page, vertical = 4.dp)) {
                Text(g.first().phones.first(), style = MaterialTheme.typography.labelLarge, color = Hub.Social.tint())
                g.forEach { p -> Text("• ${p.name}", Modifier.pressable { open = p }.padding(vertical = 2.dp)) }
                Text("Tap a name to open and merge it in your Contacts app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } } }
        } else all.filter { p -> (tab == "All" || tagOf(p).split(',').any { it.trim() == tab }) &&
            (q.isBlank() || p.name.contains(q, true) || p.phones.any { it.contains(q) } || tagOf(p).contains(q, true) || notes.getString("n${p.id}", "").orEmpty().contains(q, true)) }
            .take(300).forEach { p -> item("c${p.id}") {
                ListRow(p.name, supporting = (p.phones.firstOrNull() ?: "") + tagOf(p).let { if (it.isBlank()) "" else " · $it" }, icon = Icons.Outlined.Person, tint = Hub.Social.tint()) { open = p }
            } }
    }
}

// ─────────────────────────────── Cycle tracker ───────────────────────────────
@Composable
fun CycleScreen() {
    val ctx = LocalContext.current
    val store = remember { JsonStore(ctx, "cycle", sealed = true) }
    var starts by remember { mutableStateOf(store.all().mapNotNull { runCatching { LocalDate.parse(it.optString("d")) }.getOrNull() }.sortedDescending()) }
    var add by remember { mutableStateOf(LocalDate.now().toString()) }
    val lengths = starts.zipWithNext { a, b -> ChronoUnit.DAYS.between(b, a).toInt() }.filter { it in 18..45 }
    val avg = if (lengths.isEmpty()) 28 else lengths.take(6).average().toInt()
    val last = starts.firstOrNull()
    val next = last?.plusDays(avg.toLong())
    val ovu = next?.minusDays(14)
    val today = LocalDate.now()
    AtlasScreen(title = "Cycle tracker", subtitle = "Encrypted on this phone · never shared", hub = Hub.Health) {
        item("sum") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            if (last == null) Text("Log the first day of your last period to begin.", style = MaterialTheme.typography.bodyLarge)
            else {
                val day = ChronoUnit.DAYS.between(last, today).toInt() + 1
                Text("Day $day of about $avg", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Hub.Health.tint())
                Text("Next period expected ${next} (in ${ChronoUnit.DAYS.between(today, next!!)} days)", style = MaterialTheme.typography.bodyLarge)
                Text("Likely fertile window ${ovu!!.minusDays(5)} – ${ovu.plusDays(1)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                if (lengths.size >= 3) Text("Cycles range ${lengths.min()}–${lengths.max()} days" + if (lengths.max() - lengths.min() > 9) " — quite irregular; worth mentioning to a doctor." else ".",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
        } }
        item("add") { Row(Modifier.padding(horizontal = Space.page), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            OutlinedTextField(add, { add = it }, Modifier.weight(1f), label = { Text("Period started (yyyy-mm-dd)") }, singleLine = true)
            Button(onClick = { runCatching { LocalDate.parse(add) }.getOrNull()?.let { d -> store.add(JSONObject().put("d", d.toString())); starts = (starts + d).distinct().sortedDescending() } ?: Feedback.show("Use yyyy-mm-dd") }) { Text("Log") }
        } }
        item("h") { SectionHeader("History") }
        starts.forEachIndexed { i, d -> item("s$d") { ListRow(d.toString(), supporting = lengths.getOrNull(i)?.let { "$it-day cycle" }, icon = Icons.Outlined.WaterDrop, tint = Hub.Health.tint(), trailing = {
            Text("✕", Modifier.padding(Space.s).pressable { store.all().filter { it.optString("d") == d.toString() }.forEach { store.remove(it.optString("id")) }; starts = starts - d })
        }) } }
        item("n") { Text("Predictions are estimates, not contraception. Irregular or very painful cycles are worth a doctor's visit.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Space.page)) }
    }
}

// ─────────────────────────────── Sports centre ───────────────────────────────
private val LEAGUES = linkedMapOf("Premier League" to 4328, "La Liga" to 4335, "Champions League" to 4480, "Serie A" to 4332, "Bundesliga" to 4331)

@Composable
fun SportsScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("Cricket") }
    var league by remember { mutableStateOf("Premier League") }
    var next by remember { mutableStateOf<List<JSONObject>?>(null) }
    var past by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var cricket by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tab, league) {
        if (tab == "Football") {
            next = null
            val id = LEAGUES[league]
            next = runCatching { JSONObject(Http.getJson("https://www.thesportsdb.com/api/v1/json/3/eventsnextleague.php?id=$id")).optJSONArray("events").objs() }.getOrDefault(emptyList())
            past = runCatching { JSONObject(Http.getJson("https://www.thesportsdb.com/api/v1/json/3/eventspastleague.php?id=$id")).optJSONArray("events").objs() }.getOrDefault(emptyList())
        } else if (cricket == null) {
            cricket = runCatching { Ai.generate(container.settingsRepository, "Live and today's cricket: Pakistan matches first, then PSL/international/major T20 leagues. Scores with overs, status, and upcoming fixtures in PKT. Mark the time you checked.",
                maxOutputTokens = 700, useWeb = true) }.getOrElse { it.message }
        }
    }
    AtlasScreen(title = "Sports centre", subtitle = if (tab == "Football") league else "Cricket", hub = Hub.Media) {
        item("t") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            listOf("Cricket", "Football").forEach { t -> Capsule(t, tab == t) { tab = t } } } }
        if (tab == "Cricket") {
            item("c") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) { Md(cricket ?: "Checking…") } }
            item("r") { OutlinedButton(onClick = { cricket = null; scope.launch { cricket = runCatching { Ai.generate(container.settingsRepository, "Latest cricket scores now, Pakistan first. PKT times.", maxOutputTokens = 600, useWeb = true) }.getOrElse { it.message } } },
                modifier = Modifier.padding(horizontal = Space.page)) { Text("Refresh") } }
        } else {
            item("l") { androidx.compose.foundation.lazy.LazyRow(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                val ls = LEAGUES.keys.toList(); items(ls.size) { i -> Capsule(ls[i], league == ls[i]) { league = ls[i] } } } }
            item("nh") { SectionHeader("Coming up") }
            if (next == null) item("ld") { Text("Loading…", Modifier.padding(Space.page)) }
            next.orEmpty().forEach { e -> item("n" + e.optString("idEvent")) {
                ListRow(e.optString("strEvent"), supporting = "${e.optString("dateEvent")} ${e.optString("strTime").take(5)} UTC · ${e.optString("strVenue")}", icon = Icons.Outlined.SportsSoccer, tint = Hub.Media.tint()) } }
            item("ph") { SectionHeader("Results") }
            past.forEach { e -> item("p" + e.optString("idEvent")) {
                ListRow("${e.optString("strHomeTeam")} ${e.optString("intHomeScore")} – ${e.optString("intAwayScore")} ${e.optString("strAwayTeam")}", supporting = e.optString("dateEvent"), icon = Icons.Outlined.EmojiEvents) } }
        }
    }
}

private fun org.json.JSONArray?.objs() = this?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
