package com.nizam.app.feature.nx

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.feature.brain2.Calc
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.notify.Notifications
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate
import java.time.temporal.ChronoUnit

object Specials {
    val all: List<Spec> by lazy {
        listOf(
            ScreenSpec("qaza", "Qaza tracker", "Missed prayers and fasts, counted down", Icons.Outlined.Mosque, Hub.Deen, "qaza namaz missed prayer roza") { _, _ -> QazaScreen() },
            ScreenSpec("cricket", "Cricket scorer", "Gali cricket to proper matches", Icons.Outlined.SportsCricket, Hub.Social, "cricket score runs overs tape ball") { _, _ -> CricketScreen() },
            ScreenSpec("party", "Party games", "Dumb charades, would you rather, most likely to", Icons.Outlined.Celebration, Hub.Social, "games charades family party fun") { _, _ -> PartyScreen() },
            ScreenSpec("polls", "Polls", "Ask the group; share to WhatsApp; count votes", Icons.Outlined.Poll, Hub.Social, "poll vote group decide") { _, _ -> PollScreen() },
            ScreenSpec("speedread", "Speed reading", "Read 2–3× faster, one word at a time", Icons.Outlined.Speed, Hub.Learn, "read fast rsvp") { _, _ -> SpeedReadScreen() },
            ScreenSpec("memgame", "Memory trainer", "Grow your digit span, level by level", Icons.Outlined.Memory, Hub.Learn, "brain memory game training") { _, _ -> MemoryGameScreen() },
            ScreenSpec("clipboard", "Clipboard brain", "Everything you copied, searchable, encrypted", Icons.Outlined.ContentPaste, Hub.Tools, "clipboard copy paste history") { c, _ -> ClipboardScreen(c) },
            ScreenSpec("letters2future", "Letters to future you", "Sealed until the day you choose", Icons.Outlined.MarkEmailUnread, Hub.Memory, "time capsule future self letter") { _, _ -> FutureLettersScreen() },
            ScreenSpec("heartbeat", "Heartbeat checklist", "Your daily non-negotiables; nudged at night", Icons.Outlined.Checklist, Hub.Work, "daily checklist routine non negotiables") { _, _ -> HeartbeatScreen() },
            ScreenSpec("soul", "Atlas's soul", "How Atlas talks to you, in your own words", Icons.Outlined.Face, Hub.Tools, "personality style persona system prompt") { _, _ -> SoulScreen() },
            ScreenSpec("arena", "Model arena", "Same question to two models; you judge", Icons.Outlined.CompareArrows, Hub.Tools, "compare ai models test") { c, _ -> ArenaScreen(c) },
            ScreenSpec("webwatch", "Website watch", "Get told when a page changes", Icons.Outlined.Visibility, Hub.Tools, "monitor website change result admission notice") { _, _ -> WebWatchScreen() },
            ScreenSpec("readlater", "Read later", "Save articles; read clean, offline", Icons.Outlined.BookmarkAdd, Hub.Learn, "pocket article save reader") { c, _ -> ReadLaterScreen(c) },
            ScreenSpec("dictionary", "Dictionary", "Meanings, pronunciation and Urdu", Icons.Outlined.Book, Hub.Learn, "meaning word dictionary lughat") { c, _ -> DictionaryScreen(c) },
            ScreenSpec("breach", "Password breach check", "Has a password leaked? Checked privately", Icons.Outlined.Password, Hub.Tools, "pwned leaked hacked password") { _, _ -> BreachScreen() },
            ScreenSpec("air", "Air quality", "Smog and AQI now and next 2 days", Icons.Outlined.Air, Hub.Health, "smog aqi pollution pm2.5") { c, _ -> AirScreen(c) },
            ScreenSpec("invoice", "Invoice maker", "Quotes and invoices as PDF", Icons.Outlined.ReceiptLong, Hub.Work, "invoice bill quotation pdf business") { _, _ -> InvoiceScreen() },
            ScreenSpec("qaida", "Qaida", "Arabic letters with sounds", Icons.Outlined.MenuBook, Hub.Deen, "qaida noorani arabic alphabet kids") { _, _ -> QaidaScreen() },
        ) + Media.all
    }

    // ── helpers shared with the catalogue and the background worker ──
    fun upcoming(l: List<JSONObject>): List<Pair<JSONObject, Long>> {
        val today = LocalDate.now()
        return l.mapNotNull { o ->
            val d = runCatching { LocalDate.parse(o.optString("d")) }.getOrNull() ?: return@mapNotNull null
            var next = runCatching { d.withYear(today.year) }.getOrElse { d.withYear(today.year).withDayOfMonth(28) }
            if (next.isBefore(today)) next = next.plusYears(1)
            o to ChronoUnit.DAYS.between(today, next)
        }.sortedBy { it.second }
    }

    fun soul(c: Context): String = c.getSharedPreferences("soul", 0).getString("text", "").orEmpty()

    /** Called by the background worker; [now] is the hour as a fraction. */
    suspend fun tick(c: Context, now: Double) {
        val p = c.getSharedPreferences("nx_tick", 0)
        val today = LocalDate.now().toString()
        fun once(key: String, block: () -> Unit) { if (p.getString(key, "") != today) { p.edit().putString(key, today).apply(); block() } }
        if (now in 9.0..11.0) once("bday") {
            upcoming(JsonStore(c, "nxlog_birthdays").all()).filter { it.second == 0L }.forEach { (o, _) ->
                Notifications.post(c, Notifications.CHANNEL_NUDGE, 300 + (o.optString("name").hashCode() and 0xff), "${o.optString("name")}'s ${o.optString("kind").lowercase()} is today", "Send a wish — Greeting cards can write one.")
            }
            val tomorrow = LocalDate.now().plusDays(1).dayOfMonth
            JsonStore(c, "nxlog_bills").all().filter { num(it.optString("day")).toInt() == tomorrow }.forEach {
                Notifications.post(c, Notifications.CHANNEL_NUDGE, 600 + (it.optString("bill").hashCode() and 0xff), "${it.optString("bill")} is due tomorrow", "Usually ${rs(num(it.optString("amt")))}.")
            }
            JsonStore(c, "future_letters").all().filter { it.optString("open") == today && !it.optBoolean("told") }.forEach { o ->
                Notifications.post(c, Notifications.CHANNEL_NUDGE, 700, "A letter from your past self", "Written ${o.optString("written")}. Open Letters to future you.")
                JsonStore(c, "future_letters").update(o.optString("id")) { it.put("told", true) }
            }
        }
        if (now in 21.0..22.5) once("heartbeat") {
            val items = JsonStore(c, "heartbeat").all()
            val done = c.getSharedPreferences("heartbeat_done", 0).getStringSet(today, emptySet()).orEmpty()
            val left = items.filter { it.optString("id") !in done }
            if (items.isNotEmpty() && left.isNotEmpty()) Notifications.post(c, Notifications.CHANNEL_NUDGE, 710, "${left.size} of today's non-negotiables left", left.joinToString(" · ") { it.optString("t") })
        }
        if (System.currentTimeMillis() - p.getLong("webwatch_t", 0) > 3 * 3_600_000L) {
            p.edit().putLong("webwatch_t", System.currentTimeMillis()).apply()
            WebWatch.checkAll(c, notify = true)
        }
    }
}

// ─────────────────────────────── Qaza ───────────────────────────────
@Composable
fun QazaScreen() {
    val ctx = LocalContext.current
    val p = remember { ctx.getSharedPreferences("qaza", 0) }
    val names = listOf("Fajr", "Zuhr", "Asr", "Maghrib", "Isha", "Witr", "Roza")
    var tick by remember { mutableIntStateOf(0) }
    var years by remember { mutableStateOf("") }
    val counts = remember(tick) { names.associateWith { p.getInt(it, 0) } }
    AtlasScreen(title = "Qaza tracker", subtitle = "Tap − each time you make one up. May Allah accept.", hub = Hub.Deen) {
        item("total") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text("${counts.filterKeys { it != "Roza" }.values.sum()} prayers · ${counts["Roza"]} fasts left", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                val made = p.getInt("made", 0)
                if (made > 0) Text("$made made up so far", style = MaterialTheme.typography.bodyMedium, color = Hub.Deen.tint())
            }
        }
        names.forEach { n ->
            item(n) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(n, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(onClick = { if ((counts[n] ?: 0) > 0) { p.edit().putInt(n, counts[n]!! - 1).putInt("made", p.getInt("made", 0) + 1).apply(); tick++ } }) { Text("−") }
                    Text("${counts[n]}", Modifier.padding(horizontal = Space.l), style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace)
                    OutlinedButton(onClick = { p.edit().putInt(n, (counts[n] ?: 0) + 1).apply(); tick++ }) { Text("+") }
                }
            }
        }
        item("est") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                Text("Don't know the count? Estimate from years missed.", style = MaterialTheme.typography.bodyMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    OutlinedTextField(years, { years = it }, Modifier.weight(1f), label = { Text("Years") }, singleLine = true)
                    Button(onClick = {
                        val d = (num(years) * 365).toInt()
                        names.filter { it != "Roza" }.forEach { p.edit().putInt(it, p.getInt(it, 0) + d).apply() }
                        p.edit().putInt("Roza", p.getInt("Roza", 0) + (num(years) * 30).toInt()).apply(); years = ""; tick++
                    }, enabled = num(years) > 0) { Text("Add") }
                }
            }
        }
    }
}

// ─────────────────────────────── Cricket ───────────────────────────────
@Composable
fun CricketScreen() {
    val ctx = LocalContext.current
    val p = remember { ctx.getSharedPreferences("cricket", 0) }
    var balls by remember { mutableStateOf(p.getString("balls", "").orEmpty().split(',').filter { it.isNotBlank() }) }
    var target by remember { mutableIntStateOf(p.getInt("target", 0)) }
    var overs by remember { mutableStateOf(p.getString("overs", "10").orEmpty()) }
    fun save() { p.edit().putString("balls", balls.joinToString(",")).putInt("target", target).putString("overs", overs).apply() }
    val runs = balls.sumOf { b -> when { b == "W" -> 0; b.startsWith("Wd") || b.startsWith("Nb") -> 1 + (b.drop(2).toIntOrNull() ?: 0); else -> b.toIntOrNull() ?: 0 } }
    val wkts = balls.count { it == "W" }
    val legal = balls.count { !it.startsWith("Wd") && !it.startsWith("Nb") }
    val ov = "${legal / 6}.${legal % 6}"
    val rr = if (legal > 0) runs * 6.0 / legal else 0.0
    AtlasScreen(title = "$runs/$wkts", subtitle = "Overs $ov of $overs · run rate ${"%.2f".format(rr)}" + if (target > 0) " · need ${target - runs} off ${(num(overs) * 6).toInt() - legal}" else "", hub = Hub.Social) {
        item("last") {
            Text(balls.takeLast(12).joinToString("  "), Modifier.padding(horizontal = Space.page), style = MaterialTheme.typography.titleMedium, fontFamily = FontFamily.Monospace)
        }
        item("pad") {
            Column(Modifier.padding(horizontal = Space.page, vertical = Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                listOf(listOf("0", "1", "2", "3"), listOf("4", "6", "W", "Wd"), listOf("Nb", "Nb4", "Nb6", "Undo")).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        row.forEach { b ->
                            Button(onClick = {
                                balls = if (b == "Undo") balls.dropLast(1) else balls + (if (b.startsWith("Nb") && b.length > 2) "Nb" + b.drop(2) else b)
                                save()
                            }, modifier = Modifier.weight(1f).height(56.dp)) { Text(b, fontSize = 18.sp) }
                        }
                    }
                }
            }
        }
        item("set") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                OutlinedTextField(overs, { overs = it; save() }, Modifier.fillMaxWidth(), label = { Text("Overs per side") }, singleLine = true)
                Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Capsule("Innings over → chase", false) { target = runs + 1; balls = emptyList(); save() }
                    Capsule("New match", false) { target = 0; balls = emptyList(); save() }
                    Capsule("Share", false) { Share.text(ctx, "Score: $runs/$wkts in $ov overs" + if (target > 0) " (target $target)" else "") }
                }
            }
        }
    }
}

// ─────────────────────────────── Party games ───────────────────────────────
private val CHARADES = listOf("Sholay", "Waar", "The Legend of Maula Jatt", "Jawani Phir Nahi Ani", "Titanic", "Frozen", "Lion King", "3 Idiots", "Dangal", "Home Alone",
    "Biryani", "Rickshaw", "Load-shedding", "Cricket", "Chai", "Gol gappay", "Wedding dholki", "Traffic jam", "Pressure cooker", "Umbrella", "Swimming", "Selfie",
    "Mehndi", "Kite flying", "Taxi driver", "Doctor", "Minar-e-Pakistan", "Badshahi Mosque", "Karachi beach", "Hunza", "Mango", "Samosa", "Spider-Man", "Batman", "Harry Potter")
private val RATHER = listOf("Would you rather live without chai or without biryani?", "Would you rather be able to fly or be invisible?", "Would you rather have no internet for a month or no AC in June?",
    "Would you rather always be 10 minutes late or 20 minutes early?", "Would you rather speak every language or play every instrument?", "Would you rather live in Hunza or Karachi?",
    "Would you rather eat only sweet or only spicy forever?", "Would you rather meet your great-grandparents or your great-grandchildren?", "Would you rather have a pause button or a rewind button for life?",
    "Would you rather give up your phone for a week or sweets for a year?")
private val LIKELY = listOf("Who is most likely to fall asleep at a wedding?", "Who is most likely to become famous?", "Who is most likely to forget a birthday?", "Who is most likely to win an argument?",
    "Who is most likely to eat the last samosa?", "Who is most likely to get lost with Google Maps on?", "Who is most likely to cry at a movie?", "Who is most likely to start a business?",
    "Who is most likely to be late to their own wedding?", "Who is most likely to survive on a desert island?")

@Composable
fun PartyScreen() {
    var mode by remember { mutableStateOf("Charades") }
    var card by remember { mutableStateOf("Tap Next") }
    var secs by remember { mutableIntStateOf(0) }
    LaunchedEffect(secs) { if (secs > 0) { delay(1000); secs-- } }
    fun next() { card = when (mode) { "Charades" -> CHARADES.random(); "Would you rather" -> RATHER.random(); else -> LIKELY.random() }; if (mode == "Charades") secs = 60 }
    AtlasScreen(title = "Party games", subtitle = "Pass the phone around", hub = Hub.Social) {
        item("mode") {
            Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                listOf("Charades", "Would you rather", "Most likely").forEach { m -> Capsule(m, mode == m) { mode = m; card = "Tap Next"; secs = 0 } }
            }
        }
        item("card") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l).pressable { next() }) {
                Column(Modifier.fillMaxWidth().heightIn(min = 220.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(card, textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    if (mode == "Charades" && secs > 0) Text("$secs", style = MaterialTheme.typography.displaySmall, color = if (secs <= 10) MaterialTheme.colorScheme.error else Hub.Social.tint(), modifier = Modifier.padding(top = Space.l))
                }
            }
        }
        item("next") { Button(onClick = { next() }, modifier = Modifier.fillMaxWidth().padding(horizontal = Space.page)) { Text("Next") } }
    }
}

// ─────────────────────────────── Polls ───────────────────────────────
@Composable
fun PollScreen() {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var opts by remember { mutableStateOf("") }
    var votes by remember { mutableStateOf(mapOf<Int, Int>()) }
    val options = opts.lines().map { it.trim() }.filter { it.isNotBlank() }
    AtlasScreen(title = "Polls", subtitle = "Ask the family group, then tally the replies", hub = Hub.Social) {
        item("f") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("Question") }, placeholder = { Text("Where for Sunday dinner?") })
                OutlinedTextField(opts, { opts = it; votes = emptyMap() }, Modifier.fillMaxWidth().heightIn(min = 100.dp).padding(top = Space.s), label = { Text("Options, one per line") })
                Button(onClick = { Share.text(ctx, "📊 *$q*\n\n" + options.mapIndexed { i, o -> "${i + 1}. $o" }.joinToString("\n") + "\n\nReply with the number 👇") },
                    enabled = q.isNotBlank() && options.size >= 2, modifier = Modifier.padding(top = Space.s)) { Text("Share to WhatsApp") }
            }
        }
        if (options.size >= 2) {
            item("th") { SectionHeader("Tally replies") }
            options.forEachIndexed { i, o ->
                item("o$i") {
                    val v = votes[i] ?: 0; val total = votes.values.sum().coerceAtLeast(1)
                    ListRow("${i + 1}. $o", supporting = "$v vote${if (v == 1) "" else "s"} · ${v * 100 / total}%", trailing = {
                        Text("+1", Modifier.padding(Space.s).pressable { votes = votes + (i to v + 1) }, style = MaterialTheme.typography.titleMedium, color = Hub.Social.tint())
                    })
                }
            }
        }
    }
}

// ─────────────────────────────── Speed reading ───────────────────────────────
@Composable
fun SpeedReadScreen() {
    var text by remember { mutableStateOf("") }
    var wpm by remember { mutableStateOf(350f) }
    var pos by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    val words = remember(text) { text.split(Regex("\\s+")).filter { it.isNotBlank() } }
    LaunchedEffect(playing, pos, wpm) {
        if (playing && pos < words.size) {
            val w = words[pos]
            delay((60_000 / wpm).toLong() * (if (w.endsWith('.') || w.endsWith(',') || w.length > 9) 2 else 1))
            pos++
        } else if (pos >= words.size) playing = false
    }
    AtlasScreen(title = "Speed reading", subtitle = "Eyes still, words come to you", hub = Hub.Learn) {
        item("show") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Column(Modifier.fillMaxWidth().heightIn(min = 140.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    val w = words.getOrNull(pos).orEmpty()
                    val pivot = (w.length - 1).coerceAtLeast(0) / 3
                    Row {
                        Text(w.take(pivot), style = MaterialTheme.typography.displaySmall)
                        Text(w.drop(pivot).take(1), style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.error)
                        Text(w.drop(pivot + 1), style = MaterialTheme.typography.displaySmall)
                    }
                    Text(if (words.isEmpty()) "Paste text below" else "${pos.coerceAtMost(words.size)} / ${words.size} · ${wpm.toInt()} wpm", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item("ctl") {
            Column(Modifier.padding(horizontal = Space.page)) {
                Slider(wpm, { wpm = it }, valueRange = 150f..900f)
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Button(onClick = { if (pos >= words.size) pos = 0; playing = !playing }, enabled = words.isNotEmpty()) { Text(if (playing) "Pause" else "Read") }
                    OutlinedButton(onClick = { pos = (pos - 10).coerceAtLeast(0) }) { Text("Back 10") }
                    OutlinedButton(onClick = { pos = 0; playing = false }) { Text("Restart") }
                }
            }
        }
        item("t") { OutlinedTextField(text, { text = it; pos = 0; playing = false }, Modifier.fillMaxWidth().heightIn(min = 140.dp).padding(Space.page), placeholder = { Text("Paste an article or chapter") }) }
    }
}

// ─────────────────────────────── Memory trainer ───────────────────────────────
@Composable
fun MemoryGameScreen() {
    val ctx = LocalContext.current
    val p = remember { ctx.getSharedPreferences("memgame", 0) }
    var level by remember { mutableIntStateOf(4) }
    var secret by remember { mutableStateOf("") }
    var showing by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("Remember the number, then type it back.") }
    LaunchedEffect(showing) { if (showing) { delay(700L + level * 450L); showing = false } }
    AtlasScreen(title = "Memory trainer", subtitle = "Best: ${p.getInt("best", 0)} digits", hub = Hub.Learn) {
        item("g") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Column(Modifier.fillMaxWidth().heightIn(min = 120.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (showing) secret else if (secret.isEmpty()) "Level $level" else "?".repeat(level), style = MaterialTheme.typography.displaySmall, fontFamily = FontFamily.Monospace)
                    Text(msg, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
                if (!showing && secret.isNotEmpty()) OutlinedTextField(answer, { answer = it.filter { c -> c.isDigit() } }, Modifier.fillMaxWidth(), label = { Text("Type it") }, singleLine = true)
                Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Button(onClick = { secret = (1..level).map { (0..9).random() }.joinToString(""); answer = ""; showing = true; msg = "Look…" }, enabled = !showing) { Text("Show") }
                    OutlinedButton(onClick = {
                        if (answer == secret) { msg = "Correct! Level up."; if (level > p.getInt("best", 0)) p.edit().putInt("best", level).apply(); level++ }
                        else { msg = "It was $secret. Try again."; level = (level - 1).coerceAtLeast(3) }
                        secret = ""
                    }, enabled = !showing && secret.isNotEmpty() && answer.isNotEmpty()) { Text("Check") }
                }
            }
        }
    }
}

// ─────────────────────────────── Clipboard brain ───────────────────────────────
@Composable
fun ClipboardScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { JsonStore(ctx, "clipboard_brain", sealed = true) }
    var list by remember { mutableStateOf(store.all().reversed()) }
    var q by remember { mutableStateOf("") }
    var ans by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val t = runCatching { cm.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString() }.getOrNull()
        if (!t.isNullOrBlank() && store.all().lastOrNull()?.optString("t") != t) {
            store.add(JSONObject().put("t", t.take(5000)).put("at", System.currentTimeMillis()))
            store.all().let { if (it.size > 200) store.save(it.takeLast(200)) }
            list = store.all().reversed()
        }
    }
    val shown = list.filter { q.isBlank() || it.optString("t").contains(q, true) }
    AtlasScreen(title = "Clipboard brain", subtitle = "Opening this screen saves what you copied. Encrypted on this phone.", hub = Hub.Tools, actions = {
        Text("Clear", Modifier.padding(Space.s).pressable { store.save(emptyList()); list = emptyList() }, style = MaterialTheme.typography.labelLarge)
    }) {
        item("q") { OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = Space.page), placeholder = { Text("Search what you copied") }, singleLine = true) }
        ans?.let { a -> item("a") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) { Md(a) } } }
        if (shown.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.ContentPaste, "Nothing yet", "Copy something anywhere, then open this screen.")) }
        shown.forEach { o ->
            item(o.optString("id")) {
                ListRow(o.optString("t").take(140), supporting = android.text.format.DateUtils.getRelativeTimeSpanString(o.optLong("at")).toString(), icon = Icons.Outlined.ContentPaste, trailing = {
                    Row {
                        Text("AI", Modifier.padding(Space.s).pressable {
                            scope.launch { ans = runCatching { Ai.generate(container.settingsRepository, "I copied this. Say what it is and do the most useful thing with it (summarise, translate to English, explain, extract a date/number/address):\n\n${o.optString("t").take(3000)}", maxOutputTokens = 600) }.getOrElse { it.message } }
                        }, color = Hub.Tools.tint())
                        Text("✕", Modifier.padding(Space.s).pressable { store.remove(o.optString("id")); list = store.all().reversed() }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }) {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Atlas", o.optString("t")))
                    Feedback.show("Copied again")
                }
            }
        }
    }
}

// ─────────────────────────────── Letters to future you ───────────────────────────────
@Composable
fun FutureLettersScreen() {
    val ctx = LocalContext.current
    val store = remember { JsonStore(ctx, "future_letters", sealed = true) }
    var list by remember { mutableStateOf(store.all()) }
    var text by remember { mutableStateOf("") }
    var open by remember { mutableStateOf(LocalDate.now().plusYears(1).toString()) }
    var reading by remember { mutableStateOf<JSONObject?>(null) }
    val today = LocalDate.now()
    AtlasScreen(title = "Letters to future you", subtitle = "Write now. It stays sealed until the day comes.", hub = Hub.Memory) {
        reading?.let { r -> item("r") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            Text("Written ${r.optString("written")}", style = MaterialTheme.typography.labelLarge, color = Hub.Memory.tint())
            Text(r.optString("text"), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Space.s))
        } } }
        item("w") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp), placeholder = { Text("Dear future me…") })
                Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    listOf("6 months" to 6L, "1 year" to 12L, "5 years" to 60L).forEach { (l, m) -> Capsule(l, open == today.plusMonths(m).toString()) { open = today.plusMonths(m).toString() } }
                }
                OutlinedTextField(open, { open = it }, Modifier.fillMaxWidth().padding(top = Space.s), label = { Text("Open on (yyyy-mm-dd)") }, singleLine = true)
                Button(onClick = {
                    store.add(JSONObject().put("text", text).put("open", open).put("written", today.toString())); text = ""; list = store.all(); Feedback.show("Sealed until $open")
                }, enabled = text.isNotBlank() && runCatching { LocalDate.parse(open).isAfter(today) }.getOrDefault(false), modifier = Modifier.padding(top = Space.s)) { Text("Seal it") }
            }
        }
        list.sortedBy { it.optString("open") }.forEach { o ->
            item(o.optString("id")) {
                val ready = runCatching { !LocalDate.parse(o.optString("open")).isAfter(today) }.getOrDefault(false)
                ListRow(if (ready) "Ready to open" else "Sealed", supporting = "Written ${o.optString("written")} · opens ${o.optString("open")}",
                    icon = if (ready) Icons.Outlined.MarkEmailUnread else Icons.Outlined.Lock, tint = Hub.Memory.tint()) { if (ready) reading = o else Feedback.show("Not yet — patience is part of it") }
            }
        }
    }
}

// ─────────────────────────────── Heartbeat checklist ───────────────────────────────
@Composable
fun HeartbeatScreen() {
    val ctx = LocalContext.current
    val store = remember { JsonStore(ctx, "heartbeat") }
    val doneP = remember { ctx.getSharedPreferences("heartbeat_done", 0) }
    val today = LocalDate.now().toString()
    var items by remember { mutableStateOf(store.all()) }
    var done by remember { mutableStateOf(doneP.getStringSet(today, emptySet()).orEmpty()) }
    var add by remember { mutableStateOf("") }
    val streak = remember(done) {
        var n = 0; var d = LocalDate.now().minusDays(1)
        while (items.isNotEmpty() && doneP.getStringSet(d.toString(), emptySet()).orEmpty().size >= items.size) { n++; d = d.minusDays(1) }
        n + if (items.isNotEmpty() && done.size >= items.size) 1 else 0
    }
    AtlasScreen(title = "Heartbeat", subtitle = "${done.size}/${items.size} today · ${streak}-day streak", hub = Hub.Work) {
        items.forEach { o ->
            item(o.optString("id")) {
                val on = o.optString("id") in done
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.page).pressable {
                    done = if (on) done - o.optString("id") else done + o.optString("id"); doneP.edit().putStringSet(today, done).apply()
                }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(on, null)
                    Text(o.optString("t"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text("✕", Modifier.padding(Space.s).pressable { store.remove(o.optString("id")); items = store.all() }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item("add") {
            Row(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(add, { add = it }, Modifier.weight(1f), placeholder = { Text("5 prayers, 20 min walk, read 10 pages…") }, singleLine = true)
                Button(onClick = { store.add(JSONObject().put("t", add.trim())); add = ""; items = store.all() }, enabled = add.isNotBlank()) { Text("Add") }
            }
        }
    }
}

// ─────────────────────────────── Soul file ───────────────────────────────
@Composable
fun SoulScreen() {
    val ctx = LocalContext.current
    val p = remember { ctx.getSharedPreferences("soul", 0) }
    var text by remember { mutableStateOf(p.getString("text", "").orEmpty()) }
    AtlasScreen(title = "Atlas's soul", subtitle = "Written in your words, read before every answer", hub = Hub.Tools) {
        item("t") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 200.dp),
                    placeholder = { Text("e.g. Be direct, no fluff. Call me Rabee. Mix a little Roman Urdu. Remind me of Allah when I'm stressed. Never lecture me about screen time.") })
                Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Button(onClick = { p.edit().putString("text", text.trim().take(1500)).apply(); Feedback.show("Atlas will talk this way now") }) { Text("Save") }
                    OutlinedButton(onClick = { text = ""; p.edit().remove("text").apply() }) { Text("Reset") }
                }
            }
        }
        item("ideas") { SectionHeader("Ideas") }
        listOf("Keep answers under 5 lines unless I ask for more.", "Use Urdu script when I write in Urdu.", "Be warm and encouraging, like a big brother.",
            "Challenge me when my plan has a flaw.", "Prefer Islamic perspectives when relevant.").forEach { s ->
            item(s) { ListRow(s, icon = Icons.Outlined.Add) { text = (text.trim() + "\n" + s).trim() } }
        }
    }
}

// ─────────────────────────────── Model arena ───────────────────────────────
@Composable
fun ArenaScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = remember { ctx.getSharedPreferences("arena", 0) }
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var res by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var voted by remember { mutableStateOf(false) }
    AtlasScreen(title = "Model arena", subtitle = "Two of your AI sources answer blind. Pick the better one.", hub = Hub.Tools) {
        item("q") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Ask anything") }, minLines = 2)
                Button(onClick = {
                    busy = true; res = emptyList(); voted = false
                    scope.launch {
                        val eps = runCatching { com.nizam.app.net.Router.endpoints(container.settingsRepository, ctx) }.getOrDefault(emptyList())
                            .distinctBy { it.kind + it.model }.shuffled().take(2)
                        if (eps.size < 2) Feedback.show("Add a second AI key or model in Settings to compare")
                        else res = eps.map { e ->
                            val ans = runCatching { Ai.call(e, q, null, 900) }.getOrElse { "(failed: ${it.message?.take(80)})" }
                            (e.name + " · " + e.model) to ans
                        }
                        busy = false
                    }
                }, enabled = q.isNotBlank() && !busy, modifier = Modifier.padding(top = Space.s)) { Text(if (busy) "Asking both…" else "Compare") }
            }
        }
        res.forEachIndexed { i, (who, ans) ->
            item("r$i") {
                Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                    Text(if (voted) who else "Answer ${"AB"[i]}", style = MaterialTheme.typography.labelLarge, color = Hub.Tools.tint())
                    Md(ans)
                    if (!voted) Button(onClick = { p.edit().putInt(who, p.getInt(who, 0) + 1).apply(); voted = true }, modifier = Modifier.padding(top = Space.s)) { Text("This one is better") }
                }
            }
        }
        val board = p.all.mapNotNull { (k, v) -> (v as? Int)?.let { k to it } }.sortedByDescending { it.second }
        if (board.isNotEmpty()) {
            item("bh") { SectionHeader("Your leaderboard") }
            board.forEach { (k, v) -> item("b$k") { ListRow(k, supporting = "$v win${if (v == 1) "" else "s"}", icon = Icons.Outlined.EmojiEvents) } }
        }
    }
}

// ─────────────────────────────── Website watch ───────────────────────────────
object WebWatch {
    fun store(c: Context) = JsonStore(c, "webwatch")
    fun clean(html: String) = html.replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ").replace(Regex("<[^>]+>"), " ")
        .replace(Regex("&nbsp;|&amp;|&#\\d+;"), " ").replace(Regex("\\s+"), " ").trim()
    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    suspend fun check(c: Context, o: JSONObject, notify: Boolean): String {
        val text = clean(Http.getText(o.optString("url"))).let { t -> o.optString("find").takeIf { it.isNotBlank() }?.let { f -> if (t.contains(f, true)) "FOUND: $f" else "not yet" } ?: t }
        val h = hash(text)
        val old = o.optString("h")
        val changed = old.isNotBlank() && old != h
        store(c).update(o.optString("id")) { it.put("h", h).put("snip", text.take(300)).put("checked", System.currentTimeMillis()).apply { if (changed) put("changedAt", System.currentTimeMillis()) } }
        if (changed && notify) Notifications.post(c, Notifications.CHANNEL_NUDGE, 800 + (o.optString("url").hashCode() and 0xff), "Changed: ${o.optString("name")}", text.take(140))
        return if (old.isBlank()) "Watching" else if (changed) "Changed!" else "No change"
    }
    suspend fun checkAll(c: Context, notify: Boolean) = store(c).all().forEach { runCatching { check(c, it, notify) } }
}

@Composable
fun WebWatchScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf(WebWatch.store(ctx).all()) }
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var find by remember { mutableStateOf("") }
    AtlasScreen(title = "Website watch", subtitle = "Results, admissions, notices, stock — checked every few hours", hub = Hub.Tools) {
        item("f") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, placeholder = { Text("BISE result page") }, singleLine = true)
                OutlinedTextField(url, { url = it.trim() }, Modifier.fillMaxWidth().padding(top = Space.s), label = { Text("Web address") }, singleLine = true)
                OutlinedTextField(find, { find = it }, Modifier.fillMaxWidth().padding(top = Space.s), label = { Text("Tell me when this text appears (optional)") }, singleLine = true)
                Button(onClick = {
                    val o = WebWatch.store(ctx).add(JSONObject().put("name", name.ifBlank { url }).put("url", if (url.startsWith("http")) url else "https://$url").put("find", find))
                    scope.launch { val r = runCatching { WebWatch.check(ctx, o, false) }.getOrElse { "Couldn't open it: ${it.message}" }; Feedback.show(r); list = WebWatch.store(ctx).all() }
                    url = ""; name = ""; find = ""
                }, enabled = url.isNotBlank(), modifier = Modifier.padding(top = Space.s)) { Text("Watch") }
            }
        }
        list.forEach { o ->
            item(o.optString("id")) {
                val changed = o.optLong("changedAt")
                ListRow(o.optString("name"), supporting = (if (changed > 0) "Changed " + android.text.format.DateUtils.getRelativeTimeSpanString(changed) + " · " else "") + o.optString("snip").take(80),
                    icon = Icons.Outlined.Visibility, tint = if (changed > System.currentTimeMillis() - 86_400_000L) MaterialTheme.colorScheme.error else Hub.Tools.tint(), trailing = {
                        Row {
                            Text("Check", Modifier.padding(Space.s).pressable { scope.launch { Feedback.show(runCatching { WebWatch.check(ctx, o, false) }.getOrElse { "Failed" }); list = WebWatch.store(ctx).all() } }, color = Hub.Tools.tint())
                            Text("✕", Modifier.padding(Space.s).pressable { WebWatch.store(ctx).remove(o.optString("id")); list = WebWatch.store(ctx).all() })
                        }
                    }) { Share.open(ctx, o.optString("url")) }
            }
        }
    }
}

// ─────────────────────────────── Read later ───────────────────────────────
@Composable
fun ReadLaterScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { JsonStore(ctx, "read_later") }
    var list by remember { mutableStateOf(store.all().reversed()) }
    var url by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    val o = open
    if (o != null) {
        AtlasScreen(title = o.optString("title").take(70), subtitle = o.optString("url").take(60), hub = Hub.Learn, actions = {
            Text("Close", Modifier.padding(Space.s).pressable { open = null }, style = MaterialTheme.typography.labelLarge)
        }) {
            item("sum") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Capsule("Summarise", false) { scope.launch { val s = runCatching { Ai.generate(container.settingsRepository, "Summarise in 6 bullets:\n" + o.optString("body").take(12000), maxOutputTokens = 500) }.getOrElse { it.message.orEmpty() }; open = JSONObject(o.toString()).put("summary", s) } }
                Capsule("Open original", false) { Share.open(ctx, o.optString("url")) }
                Capsule(if (o.optBoolean("read")) "Mark unread" else "Mark read", false) { store.update(o.optString("id")) { it.put("read", !o.optBoolean("read")) }; list = store.all().reversed(); open = null }
            } }
            o.optString("summary").takeIf { it.isNotBlank() }?.let { s -> item("s") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) { Md(s) } } }
            o.optString("body").split("\n\n").filter { it.isNotBlank() }.forEachIndexed { i, para ->
                item("p$i") { Text(para, Modifier.padding(horizontal = Space.page, vertical = 6.dp), style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp)) }
            }
        }
        return
    }
    AtlasScreen(title = "Read later", subtitle = "${list.count { !it.optBoolean("read") }} unread · saved for offline", hub = Hub.Learn) {
        item("add") {
            Row(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(url, { url = it.trim() }, Modifier.weight(1f), placeholder = { Text("Paste a link") }, singleLine = true)
                Button(onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            val u = if (url.startsWith("http")) url else "https://$url"
                            val html = Http.getText(u)
                            val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.groupValues?.get(1)?.trim()?.let { WebWatch.clean(it) } ?: u
                            val paras = Regex("(?is)<p[^>]*>(.*?)</p>").findAll(html).map { WebWatch.clean(it.groupValues[1]) }.filter { it.length > 40 }.toList()
                            store.add(JSONObject().put("url", u).put("title", title).put("body", paras.joinToString("\n\n").ifBlank { WebWatch.clean(html).take(20000) }).put("t", System.currentTimeMillis()))
                        }.onSuccess { url = ""; list = store.all().reversed() }.onFailure { Feedback.show("Couldn't save: ${it.message?.take(80)}") }
                        busy = false
                    }
                }, enabled = url.isNotBlank() && !busy) { Text(if (busy) "…" else "Save") }
            }
        }
        if (list.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.BookmarkAdd, "Nothing saved", "Paste a link above, or share one to Atlas.")) }
        list.forEach { a ->
            item(a.optString("id")) {
                val mins = (a.optString("body").split(' ').size / 230).coerceAtLeast(1)
                ListRow(a.optString("title"), supporting = "$mins min read" + if (a.optBoolean("read")) " · read" else "", icon = Icons.Outlined.Article,
                    tint = if (a.optBoolean("read")) MaterialTheme.colorScheme.onSurfaceVariant else Hub.Learn.tint(), trailing = {
                        Text("✕", Modifier.padding(Space.s).pressable { store.remove(a.optString("id")); list = store.all().reversed() })
                    }) { open = a }
            }
        }
    }
}

// ─────────────────────────────── Dictionary ───────────────────────────────
@Composable
fun DictionaryScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var w by remember { mutableStateOf("") }
    var out by remember { mutableStateOf<String?>(null) }
    var urdu by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AtlasScreen(title = "Dictionary", subtitle = "English meanings, examples and the Urdu word", hub = Hub.Learn) {
        item("q") {
            Row(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(w, { w = it }, Modifier.weight(1f), placeholder = { Text("Word") }, singleLine = true)
                Button(onClick = {
                    busy = true; out = null; urdu = null
                    scope.launch {
                        out = runCatching {
                            val a = JSONArray(Http.getJson("https://api.dictionaryapi.dev/api/v2/entries/en/" + java.net.URLEncoder.encode(w.trim().lowercase(), "UTF-8")))
                            val e = a.getJSONObject(0)
                            buildString {
                                append("## ${e.optString("word")}  ${e.optString("phonetic")}\n")
                                val ms = e.optJSONArray("meanings") ?: JSONArray()
                                for (i in 0 until ms.length()) { val m = ms.getJSONObject(i); append("\n**${m.optString("partOfSpeech")}**\n")
                                    val ds = m.optJSONArray("definitions") ?: JSONArray()
                                    for (j in 0 until minOf(3, ds.length())) { val d = ds.getJSONObject(j); append("- ${d.optString("definition")}"); d.optString("example").takeIf { it.isNotBlank() }?.let { append(" _\"$it\"_") }; append("\n") }
                                    val syn = m.optJSONArray("synonyms"); if (syn != null && syn.length() > 0) append("Similar: ${(0 until minOf(6, syn.length())).joinToString { syn.getString(it) }}\n")
                                }
                            }
                        }.getOrElse { "No entry found for \"$w\"." }
                        urdu = runCatching { Ai.generate(container.settingsRepository, "Urdu meaning of the English word \"${w.trim()}\": give the Urdu word(s) in Urdu script, Roman Urdu, and one Urdu example sentence. 3 lines.", maxOutputTokens = 150) }.getOrNull()
                        busy = false
                    }
                }, enabled = w.isNotBlank() && !busy) { Text("Look up") }
            }
        }
        out?.let { item("o") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) { Md(it) } } }
        urdu?.let { item("u") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) { Text("اردو", style = MaterialTheme.typography.labelLarge, color = Hub.Learn.tint()); Text(it, style = MaterialTheme.typography.bodyLarge) } } }
    }
}

// ─────────────────────────────── Breach check ───────────────────────────────
@Composable
fun BreachScreen() {
    val scope = rememberCoroutineScope()
    var pw by remember { mutableStateOf("") }
    var out by remember { mutableStateOf<String?>(null) }
    AtlasScreen(title = "Password breach check", subtitle = "Only the first 5 characters of a scrambled hash leave the phone", hub = Hub.Tools) {
        item("f") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                OutlinedTextField(pw, { pw = it }, Modifier.fillMaxWidth(), label = { Text("Password") }, singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
                Button(onClick = {
                    scope.launch {
                        out = runCatching {
                            val h = MessageDigest.getInstance("SHA-1").digest(pw.toByteArray()).joinToString("") { "%02X".format(it) }
                            val body = Http.getText("https://api.pwnedpasswords.com/range/" + h.take(5))
                            val n = body.lineSequence().firstOrNull { it.startsWith(h.drop(5)) }?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
                            if (n > 0) "⚠️ Found in $n leaks. Change it everywhere you use it." else "✓ Not found in known leaks. Still use a unique one per site."
                        }.getOrElse { "Couldn't check: ${it.message}" }
                        pw = ""
                    }
                }, enabled = pw.isNotEmpty(), modifier = Modifier.padding(top = Space.s)) { Text("Check") }
                out?.let { Text(it, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Space.l)) }
            }
        }
    }
}

// ─────────────────────────────── Air quality ───────────────────────────────
@Composable
fun AirScreen(container: AppContainer) {
    var data by remember { mutableStateOf<JSONObject?>(null) }
    var city by remember { mutableStateOf("Here") }
    val cities = linkedMapOf("Here" to (0.0 to 0.0), "Lahore" to (31.55 to 74.34), "Karachi" to (24.86 to 67.01), "Islamabad" to (33.68 to 73.05),
        "Faisalabad" to (31.42 to 73.08), "Multan" to (30.2 to 71.47), "Peshawar" to (34.01 to 71.58), "Quetta" to (30.18 to 66.99))
    LaunchedEffect(city) {
        data = null
        val (la, lo) = if (city == "Here") runCatching { container.settingsRepository.latitude.first() to container.settingsRepository.longitude.first() }.getOrDefault(31.55 to 74.34) else cities[city]!!
        data = runCatching { JSONObject(Http.getJson("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$la&longitude=$lo&current=us_aqi,pm2_5,pm10&hourly=us_aqi&forecast_days=2&timezone=auto")) }.getOrElse { JSONObject().put("err", it.message) }
    }
    fun band(a: Int) = when { a <= 50 -> "Good" to "Enjoy the outdoors"; a <= 100 -> "Moderate" to "Sensitive people: limit long outdoor effort"; a <= 150 -> "Unhealthy for sensitive groups" to "Kids, elders, asthma: stay in; masks outside"
        a <= 200 -> "Unhealthy" to "Everyone: N95 outside, keep windows shut"; a <= 300 -> "Very unhealthy" to "Stay indoors, run a purifier"; else -> "Hazardous" to "Avoid going out at all" }
    val d = data
    AtlasScreen(title = "Air quality", subtitle = city, hub = Hub.Health, loading = d == null) {
        item("c") { androidx.compose.foundation.lazy.LazyRow(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            items(cities.keys.toList().size) { i -> val k = cities.keys.toList()[i]; Capsule(k, city == k) { city = k } } } }
        if (d == null) return@AtlasScreen
        val cur = d.optJSONObject("current")
        if (cur == null) { item("e") { Text("Couldn't load air data. ${d.optString("err")}", Modifier.padding(Space.page)) }; return@AtlasScreen }
        val aqi = cur.optInt("us_aqi"); val (label, advice) = band(aqi)
        item("now") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text("$aqi", style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = if (aqi > 150) MaterialTheme.colorScheme.error else Hub.Health.tint())
                Text(label, style = MaterialTheme.typography.titleLarge)
                Text(advice, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
                Text("PM2.5 ${cur.optDouble("pm2_5")} · PM10 ${cur.optDouble("pm10")} µg/m³", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.s))
            }
        }
        val hourly = d.optJSONObject("hourly")
        val vals = hourly?.optJSONArray("us_aqi"); val times = hourly?.optJSONArray("time")
        if (vals != null && times != null) {
            item("chart") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text("Next 48 hours", style = MaterialTheme.typography.labelLarge)
                com.nizam.app.core.LineChart((0 until vals.length()).map { com.nizam.app.core.Point(times.optString(it).takeLast(5), vals.optDouble(it, 0.0)) }, Hub.Health.tint(), height = 130)
                val best = (0 until vals.length()).minByOrNull { vals.optDouble(it, 999.0) }
                if (best != null) Text("Cleanest air: ${times.optString(best).replace('T', ' ')} (AQI ${vals.optInt(best)})", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.s))
            } }
        }
    }
}

// ─────────────────────────────── Invoice ───────────────────────────────
@Composable
fun InvoiceScreen() {
    val ctx = LocalContext.current
    val p = remember { ctx.getSharedPreferences("invoice", 0) }
    var biz by remember { mutableStateOf(p.getString("biz", "").orEmpty()) }
    var client by remember { mutableStateOf("") }
    var items by remember { mutableStateOf("") }
    var tax by remember { mutableStateOf("0") }
    var disc by remember { mutableStateOf("0") }
    var kind by remember { mutableStateOf("Invoice") }
    val no = p.getInt("no", 1)
    val rows = items.lines().mapNotNull { l -> val parts = l.split(',').map { it.trim() }
        if (parts.size < 3 || parts[0].isBlank()) null else Triple(parts[0], num(parts[1]), num(parts[2])) }
    val sub = rows.sumOf { it.second * it.third }
    val total = (sub - num(disc)) * (1 + num(tax) / 100)
    AtlasScreen(title = "Invoice maker", subtitle = "$kind #$no · ${rs(total)}", hub = Hub.Work) {
        item("f") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) { listOf("Invoice", "Quotation", "Receipt").forEach { k -> Capsule(k, kind == k) { kind = k } } }
                OutlinedTextField(biz, { biz = it; p.edit().putString("biz", it).apply() }, Modifier.fillMaxWidth().padding(top = Space.s), label = { Text("Your business (name, phone, address)") }, minLines = 2)
                OutlinedTextField(client, { client = it }, Modifier.fillMaxWidth().padding(top = Space.s), label = { Text("Bill to") })
                OutlinedTextField(items, { items = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(top = Space.s), label = { Text("Items: name, qty, price — one per line") },
                    placeholder = { Text("Website design, 1, 45000\nHosting (1 year), 1, 8000") })
                Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    OutlinedTextField(disc, { disc = it }, Modifier.weight(1f), label = { Text("Discount (Rs)") }, singleLine = true)
                    OutlinedTextField(tax, { tax = it }, Modifier.weight(1f), label = { Text("Tax %") }, singleLine = true)
                }
            }
        }
        item("t") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                rows.forEach { (n, q, pr) -> Row { Text("$n × ${Calc.fmt(q)}", Modifier.weight(1f)); Text(rs(q * pr)) } }
                Text("Subtotal ${rs(sub)} · Total ${rs(total)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = Space.s))
                Button(onClick = {
                    val body = buildString {
                        append(biz.trim()); append("\n\n")
                        append("$kind #$no    Date: ${LocalDate.now()}\n\n**Bill to:** ${client.trim()}\n\n")
                        rows.forEachIndexed { i, (n, q, pr) -> append("${i + 1}. $n — ${Calc.fmt(q)} × ${rs(pr)} = ${rs(q * pr)}\n") }
                        append("\nSubtotal: ${rs(sub)}\n")
                        if (num(disc) > 0) append("Discount: −${rs(num(disc))}\n")
                        if (num(tax) > 0) append("Tax (${tax}%): ${rs((sub - num(disc)) * num(tax) / 100)}\n")
                        append("\n# Total: ${rs(total)}\n\nThank you for your business.")
                    }
                    runCatching { Share.file(ctx, Pdf.fromText(ctx, "$kind $no", body), "application/pdf"); p.edit().putInt("no", no + 1).apply() }.onFailure { Feedback.show("Couldn't make the PDF") }
                }, enabled = rows.isNotEmpty() && client.isNotBlank(), modifier = Modifier.padding(top = Space.s)) { Text("Make PDF") }
            }
        }
    }
}

// ─────────────────────────────── Qaida ───────────────────────────────
private val LETTERS = listOf("ا" to "Alif", "ب" to "Ba", "ت" to "Ta", "ث" to "Sa", "ج" to "Jeem", "ح" to "Ha", "خ" to "Kha", "د" to "Dal", "ذ" to "Zal", "ر" to "Ra",
    "ز" to "Zay", "س" to "Seen", "ش" to "Sheen", "ص" to "Suad", "ض" to "Duad", "ط" to "Toa", "ظ" to "Zoa", "ع" to "Ain", "غ" to "Ghain", "ف" to "Fa",
    "ق" to "Qaf", "ك" to "Kaf", "ل" to "Lam", "م" to "Meem", "ن" to "Noon", "و" to "Wao", "ه" to "Ha", "ء" to "Hamza", "ي" to "Ya")

@Composable
fun QaidaScreen() {
    val ctx = LocalContext.current
    val tts = remember { android.speech.tts.TextToSpeech(ctx) { } }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { tts.shutdown() } }
    var picked by remember { mutableStateOf<Pair<String, String>?>(null) }
    var haraka by remember { mutableStateOf("") }
    AtlasScreen(title = "Qaida", subtitle = "Tap a letter to hear it. Add zabar, zer or pesh.", hub = Hub.Deen) {
        item("h") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            listOf("" to "Letter", "َ" to "Zabar", "ِ" to "Zer", "ُ" to "Pesh", "ً" to "Do zabar").forEach { (h, l) -> Capsule(l, haraka == h) { haraka = h } } } }
        picked?.let { (l, n) -> item("big") {
            Column(Modifier.fillMaxWidth().padding(Space.page), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(l + haraka, fontSize = 96.sp, color = Hub.Deen.tint()); Text(n, style = MaterialTheme.typography.titleLarge)
            } } }
        item("grid") {
            LazyVerticalGrid(GridCells.Fixed(5), Modifier.fillMaxWidth().height(460.dp).padding(horizontal = Space.page), userScrollEnabled = false) {
                items(LETTERS.size) { i -> val (l, n) = LETTERS[LETTERS.size - 1 - i]
                    Column(Modifier.padding(4.dp).pressable {
                        picked = l to n
                        runCatching { tts.language = java.util.Locale("ar"); tts.speak(l + haraka, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "q") }
                    }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(l, fontSize = 36.sp); Text(n, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item("note") { Text("Arabic voice needs the Arabic language pack in your phone's text-to-speech settings.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Space.page)) }
    }
}

