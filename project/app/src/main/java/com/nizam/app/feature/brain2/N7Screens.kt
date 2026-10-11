package com.nizam.app.feature.brain2

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.LineChart
import com.nizam.app.core.Point
import com.nizam.app.feature.phone.AtlasNotificationListener
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
import com.nizam.app.ui.design.StatTile
import com.nizam.app.ui.design.pressable
import kotlinx.coroutines.launch
import org.json.JSONObject

private fun rs(v: Double) = (if (v < 0) "−Rs " else "Rs ") + "%,d".format(kotlin.math.abs(v).toLong())

@Composable
fun PlacesScreen() {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var item by remember { mutableStateOf("") }
    var place by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }
    val found = remember(q, tick) { Places.find(ctx, q) }
    AtlasScreen(title = "Where is it?", subtitle = "Tell Atlas where you put things. Ask later, in any words.", hub = Hub.Home) {
        item("put") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                OutlinedTextField(item, { item = it }, Modifier.fillMaxWidth(), label = { Text("Thing") }, placeholder = { Text("Passport") }, singleLine = true)
                OutlinedTextField(place, { place = it }, Modifier.fillMaxWidth().padding(top = Space.s), label = { Text("Where") }, placeholder = { Text("Blue file, top drawer, bedroom almirah") }, singleLine = true)
                Button(onClick = { Places.remember(ctx, item, place); Feedback.show("Remembered: $item"); item = ""; place = ""; tick++ },
                    enabled = item.isNotBlank() && place.isNotBlank(), modifier = Modifier.padding(top = Space.s)) { Text("Remember") }
            }
        }
        item("q") {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s),
                placeholder = { Text("Search: spare keys, warranty, charger…") }, singleLine = true)
        }
        if (found.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.Place, if (q.isBlank()) "Nothing noted yet" else "Not found",
            "You can also just tell Atlas: \"I put the car papers in the glovebox\".")) }
        found.forEach { o ->
            item(o.optString("id")) {
                ListRow(o.optString("item"), supporting = o.optString("place") + (o.optString("before").takeIf { it.isNotBlank() }?.let { "  ·  was: $it" } ?: ""),
                    icon = Icons.Outlined.Place, tint = Hub.Home.tint(), trailing = {
                        Text("Remove", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(Space.s).pressable { Places.store(ctx).remove(o.optString("id")); tick++ })
                    }) { item = o.optString("item"); place = "" }
            }
        }
    }
}

@Composable
fun CardsScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableStateOf(0) }
    var studying by remember { mutableStateOf<String?>(null) }
    var queue by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var flipped by remember { mutableStateOf(false) }
    var deck by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val decks = remember(tick) { Cards.decks(ctx) }

    val s = studying
    if (s != null) {
        val card = queue.firstOrNull()
        AtlasScreen(title = s, subtitle = if (card == null) "Done for today" else "${queue.size} to go", hub = Hub.Learn, actions = {
            Text("Stop", Modifier.padding(Space.s).pressable { studying = null; tick++ }, style = MaterialTheme.typography.labelLarge)
        }) {
            if (card == null) item("done") { EmptyState(EmptySpec(Icons.Outlined.Style, "All caught up", "Cards come back just before you'd forget them.")) }
            else item(card.optString("id") + flipped) {
                Group(Modifier.padding(horizontal = Space.page, vertical = Space.l).pressable { flipped = !flipped }) {
                    AnimatedContent(flipped, label = "flip") { f ->
                        Column(Modifier.fillMaxWidth().heightIn(min = 180.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (f) card.optString("back") else card.optString("front"), textAlign = TextAlign.Center,
                                style = if (f) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineSmall)
                            if (!f) Text("Tap to see the answer", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.l))
                        }
                    }
                }
                if (flipped) Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    listOf("Forgot", "Hard", "Good", "Easy").forEachIndexed { g, label ->
                        OutlinedButton(onClick = {
                            Cards.review(ctx, card, g); flipped = false
                            queue = if (g == 0) queue.drop(1) + card else queue.drop(1)
                        }, modifier = Modifier.weight(1f)) { Text(label, maxLines = 1) }
                    }
                }
            }
        }
        return
    }

    AtlasScreen(title = "Memory palace", subtitle = "Learn anything for good. Cards return right before you'd forget them.", hub = Hub.Learn) {
        if (decks.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.Style, "No decks yet", "Give Atlas a topic or paste notes below and it makes the cards.")) }
        decks.forEach { (name, cards) ->
            item("d$name") {
                val due = cards.count { it.optLong("due") <= java.time.LocalDate.now().toEpochDay() }
                ListRow(name, supporting = "${cards.size} cards · $due due today", icon = Icons.Outlined.Style, tint = Hub.Learn.tint()) {
                    queue = Cards.due(ctx, name).shuffled(); flipped = false; studying = name
                }
            }
        }
        item("mh") { SectionHeader("Make a deck") }
        item("m") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                OutlinedTextField(deck, { deck = it }, Modifier.fillMaxWidth(), label = { Text("Deck name") }, placeholder = { Text("Surah Al-Mulk meanings") }, singleLine = true)
                OutlinedTextField(source, { source = it }, Modifier.fillMaxWidth().heightIn(min = 100.dp).padding(top = Space.s),
                    placeholder = { Text("A topic (\"MDCAT biology: cell division\") or paste your notes") })
                Button(onClick = {
                    busy = true
                    scope.launch {
                        runCatching { Cards.generate(ctx, container, deck.trim(), source.trim()) }
                            .onSuccess { Feedback.show("$it cards made"); source = ""; tick++ }
                            .onFailure { Feedback.show(it.message ?: "Couldn't make cards") }
                        busy = false
                    }
                }, enabled = deck.isNotBlank() && source.isNotBlank() && !busy, modifier = Modifier.padding(top = Space.s)) { Text(if (busy) "Making cards…" else "Make cards") }
                OutlinedButton(onClick = {
                    // Anki-style import: one card per line, "front ; back" (tab or ; or | separated)
                    val n = source.lines().mapNotNull { l -> l.split('\t', ';', '|').map { it.trim() }.takeIf { it.size >= 2 && it[0].isNotBlank() && it[1].isNotBlank() } }
                        .onEach { Cards.add(ctx, deck.trim(), it[0], it[1]) }.size
                    Feedback.show(if (n == 0) "Paste lines like: front ; back" else "$n cards imported"); if (n > 0) { source = ""; tick++ }
                }, enabled = deck.isNotBlank() && source.isNotBlank(), modifier = Modifier.padding(top = Space.s)) { Text("Import as-is (front ; back per line)") }
            }
        }
    }
}

@Composable
fun TwinScreen(container: AppContainer) {
    var base by remember { mutableStateOf<Twin.Base?>(null) }
    var l by remember { mutableStateOf(Twin.Levers()) }
    var startText by remember { mutableStateOf("") }
    var goalText by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { base = Twin.base(container) }
    val b = base
    AtlasScreen(title = "Financial twin", subtitle = "Your real spending, run forward. Pull a lever and watch the future move.", hub = Hub.Money, loading = b == null) {
        if (b == null) return@AtlasScreen
        val path = Twin.project(b, l)
        item("coin") { com.nizam.app.ui.design.Object3D(remember { com.nizam.app.ui.design.Mesh3D.coin() }, Modifier.fillMaxWidth().height(150.dp), pitch = -0.2f, scale = 0.8f) }
        item("stats") {
            Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                StatTile("In / month", rs(b.income * (1 + l.raise / 100.0)), Modifier.weight(1f))
                StatTile("Out / month", rs(b.spend * (1 - l.spendCut / 100.0)), Modifier.weight(1f))
            }
        }
        item("end") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text("In ${l.months} months", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(rs(path.last()), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
                    color = if (path.last() < 0) MaterialTheme.colorScheme.error else Hub.Money.tint())
                Spacer(Modifier.height(Space.s))
                LineChart(path.mapIndexed { i, v -> Point(if (i % 6 == 0) "${i}m" else "", v) }, Hub.Money.tint(), height = 150)
                path.indexOfFirst { it < 0 }.takeIf { it > 0 }?.let {
                    Text("At this pace you run short in month $it.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.s))
                }
                goalText.replace(",", "").toDoubleOrNull()?.let { g ->
                    val m = Twin.monthsTo(b, l, g)
                    Text(if (m == null) "That goal is out of reach at this pace." else "You reach ${rs(g)} in $m months.",
                        style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.s))
                }
            }
        }
        item("lev") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                @Composable fun lever(label: String, value: String, v: Float, range: ClosedFloatingPointRange<Float>, set: (Float) -> Unit) {
                    Row { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text(value, style = MaterialTheme.typography.labelLarge) }
                    Slider(v, set, valueRange = range)
                }
                lever("Spend less", "${l.spendCut}%", l.spendCut.toFloat(), 0f..50f) { l = l.copy(spendCut = it.toInt()) }
                lever("Raise", "${l.raise}%", l.raise.toFloat(), 0f..100f) { l = l.copy(raise = it.toInt()) }
                lever("Return on savings", "%.0f%% / yr".format(l.returnPct), l.returnPct.toFloat(), 0f..25f) { l = l.copy(returnPct = it.toDouble().let { d -> kotlin.math.round(d) }) }
                lever("Big purchase", rs(l.purchase), (l.purchase / 100_000).toFloat(), 0f..50f) { l = l.copy(purchase = it.toInt() * 100_000.0) }
                if (l.purchase > 0) lever("…in month", "${l.purchaseMonth}", l.purchaseMonth.toFloat(), 1f..l.months.toFloat()) { l = l.copy(purchaseMonth = it.toInt()) }
                lever("Look ahead", "${l.months} months", l.months.toFloat(), 6f..120f) { l = l.copy(months = it.toInt(), purchaseMonth = l.purchaseMonth.coerceAtMost(it.toInt())) }
                OutlinedTextField(startText, { startText = it; l = l.copy(start = it.replace(",", "").toDoubleOrNull() ?: 0.0) }, Modifier.fillMaxWidth(),
                    label = { Text("Savings today (Rs)") }, singleLine = true)
                OutlinedTextField(goalText, { goalText = it }, Modifier.fillMaxWidth().padding(top = Space.s), label = { Text("Goal (Rs), e.g. 15,00,000 for a car") }, singleLine = true)
            }
        }
        if (b.topCats.isNotEmpty()) {
            item("ch") { SectionHeader("Where it goes each month") }
            b.topCats.forEach { (cat, v) -> item("c$cat") { ListRow(cat, supporting = rs(v) + "  ·  cutting 20% saves ${rs(v * 0.2 * 12)} a year") } }
        }
        item("note") {
            Text("Based on your last ${b.days} days of tracked money. Add or auto-log more and the twin gets sharper.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Space.page))
        }
    }
}

@Composable
fun InboxScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val version by AtlasNotificationListener.version.collectAsState()
    val threads = remember(version) { Inbox.threads(ctx) }
    var drafts by remember { mutableStateOf(mapOf<String, String>()) }
    var showNoise by remember { mutableStateOf(false) }
    val labels = listOf("Needs you", "Messages", "Updates", "Noise")
    AtlasScreen(title = "Inbox", subtitle = "Every app's messages in one place, what needs you first", hub = Hub.Social, actions = {
        if (threads.isNotEmpty()) Text("Clear noise", Modifier.padding(Space.s).pressable {
            threads.filter { it.tier == 3 }.flatMap { it.keys }.forEach { AtlasNotificationListener.dismiss(it) }
        }, style = MaterialTheme.typography.labelLarge)
    }) {
        if (!AtlasNotificationListener.connected) item("perm") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text("Atlas needs notification access to gather your messages.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { runCatching { ctx.startActivity(android.content.Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } },
                    modifier = Modifier.padding(top = Space.s)) { Text("Allow") }
            }
        }
        if (threads.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.Inbox, "Inbox zero", "Nothing waiting in any app.")) }
        threads.groupBy { it.tier }.toSortedMap().forEach { (tier, list) ->
            if (tier == 3 && !showNoise) {
                item("noise") { ListRow("${list.size} promotions and updates hidden", supporting = "Tap to show") { showNoise = true } }
                return@forEach
            }
            item("h$tier") { SectionHeader(labels[tier]) }
            list.forEach { t ->
                val id = t.pkg + t.who
                item(id) {
                    Group(Modifier.padding(horizontal = Space.page, vertical = 4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(t.who, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                Text(t.app + " · " + android.text.format.DateUtils.getRelativeTimeSpanString(t.t), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("Open", Modifier.padding(Space.s).pressable { AtlasNotificationListener.open(ctx, t.keys.first()) }, style = MaterialTheme.typography.labelLarge, color = Hub.Social.tint())
                            Text("Done", Modifier.padding(Space.s).pressable { t.keys.forEach { AtlasNotificationListener.dismiss(it) } }, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        t.lines.takeLast(3).forEach { Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 3, modifier = Modifier.padding(top = 2.dp)) }
                        if (t.canReply) {
                            val d = drafts[id]
                            if (d == null) Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                                Capsule("Draft reply", false) {
                                    drafts = drafts + (id to "")
                                    scope.launch { runCatching { Inbox.draft(container, t) }.onSuccess { drafts = drafts + (id to it) }.onFailure { Feedback.show(it.message ?: "Couldn't draft") } }
                                }
                                Capsule("Write", false) { drafts = drafts + (id to " ") }
                            } else {
                                OutlinedTextField(d.trimStart(), { drafts = drafts + (id to it) }, Modifier.fillMaxWidth().padding(top = Space.s), placeholder = { Text("Writing…") })
                                Button(onClick = {
                                    Feedback.show(AtlasNotificationListener.reply(ctx, t.who, d.trim())); drafts = drafts - id
                                }, enabled = d.isNotBlank(), modifier = Modifier.padding(top = Space.s)) { Text("Send") }
                            }
                        }
                    }
                }
            }
        }
    }
}
