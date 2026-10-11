package com.nizam.app.feature.world

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.agent.Snapshot
import com.nizam.app.feature.council.TypingDots
import com.nizam.app.feature.council.UserBubble
import com.nizam.app.feature.memory.ReadLater
import com.nizam.app.feature.voice.Speaker
import com.nizam.app.net.Ai
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.temporal.IsoFields

private fun Context.arr(n: String) = runCatching { JSONArray(File(filesDir, n).readText()) }.getOrElse { JSONArray() }
private fun Context.put(n: String, a: JSONArray) = File(filesDir, n).writeText(a.toString())

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) { content() } }
}

/** The quarter's theme. Every AI reads it and leans its advice that way. */
object Season {
    fun theme(c: Context) = c.getSharedPreferences("season", 0).getString(key(), "").orEmpty()
    fun set(c: Context, t: String) = c.getSharedPreferences("season", 0).edit().putString(key(), t).apply()
    private fun key() = LocalDate.now().let { "${it.year}-Q${it.get(IsoFields.QUARTER_OF_YEAR)}" }
    fun forAi(c: Context) = theme(c).takeIf { it.isNotBlank() }?.let { "This quarter they've named the '$it' season — lean advice and missions toward it." }.orEmpty()
}

@Composable
fun PeopleScreen(container: AppContainer, onOpen: (String) -> Unit) {
    var tab by remember { mutableStateOf("Word kept") }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        ModuleTitle("People & practice", "Promises, mentors, hard conversations, and the season you're in")
        ChipRow(listOf("Word kept", "Mentor", "Sparring", "Season", "Serendipity", "Legacy"), tab, { tab = it })
        Box(Modifier.weight(1f)) {
            when (tab) {
                "Word kept" -> WordKept()
                "Mentor" -> Chat(container, "mentor")
                "Sparring" -> Chat(container, "sparring")
                "Season" -> SeasonTab()
                "Serendipity" -> Serendipity(container)
                else -> LegacyTab()
            }
        }
    }
}

// ── Word kept ─────────────────────────────────────────────────────────────────
@Composable
private fun WordKept() {
    val c = LocalContext.current
    var list by remember { mutableStateOf(c.arr("promises.json")) }
    var what by remember { mutableStateOf("") }
    var who by remember { mutableStateOf("") }
    var days by remember { mutableStateOf("1") }
    fun save(a: JSONArray) { c.put("promises.json", a); list = JSONArray(a.toString()) }
    val all = (0 until list.length()).map { list.getJSONObject(it) }
    val closed = all.filter { it.optString("state") != "open" }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (closed.isNotEmpty()) Text("Reliability: ${closed.count { it.optString("state") == "kept" } * 100 / closed.size}% of ${closed.size} promises kept",
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Card {
            OutlinedTextField(what, { what = it }, label = { Text("I said I'd… (send the file, visit on Sunday)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(who, { who = it }, label = { Text("To whom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ChipRow(listOf("0", "1", "3", "7"), days, { days = it }, labels = { if (it == "0") "today" else "in $it days" })
            Button(enabled = what.isNotBlank(), onClick = {
                val a = JSONArray(list.toString()); a.put(JSONObject().put("what", what.trim()).put("who", who.trim())
                    .put("due", LocalDate.now().plusDays(days.toLong()).toString()).put("state", "open")); save(a); what = ""; who = ""
            }) { Text("Keep my word") }
        }
        all.withIndex().reversed().forEach { (i, p) ->
            Card {
                Text(p.optString("what"), style = MaterialTheme.typography.titleMedium)
                Text("to ${p.optString("who").ifBlank { "someone" }} · due ${p.optString("due")}", style = MaterialTheme.typography.labelMedium)
                if (p.optString("state") == "open") Row {
                    OutlinedButton(onClick = { val a = JSONArray(list.toString()); a.getJSONObject(i).put("state", "kept"); save(a) }) { Text("Kept ✓") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = { val a = JSONArray(list.toString()); a.getJSONObject(i).put("state", "broken"); save(a) }) { Text("Missed") }
                } else Text(if (p.optString("state") == "kept") "Kept" else "Missed", color = MaterialTheme.colorScheme.primary)
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

// ── Mentor + sparring partner share one chat ──────────────────────────────────
@Composable
private fun Chat(container: AppContainer, mode: String) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    val turns = remember(mode) { mutableStateListOf<Pair<Boolean, String>>() }
    var draft by remember { mutableStateOf("") }
    var style by remember { mutableStateOf(if (mode == "mentor") "Strategic" else "Salary negotiation") }
    var busy by remember { mutableStateOf(false) }
    val options = if (mode == "mentor") listOf("Tough", "Warm", "Strategic")
        else listOf("Salary negotiation", "Difficult family talk", "Bargaining with a supplier", "Job interview", "Saying no to a request")
    fun send(text: String, scoreMe: Boolean = false) {
        if ((text.isBlank() && !scoreMe) || busy) return
        if (!scoreMe) turns += true to text
        draft = ""
        scope.launch {
            busy = true
            val system = if (mode == "mentor")
                "You are the user's weekly mentor, style: $style. Structure the session: review last week from their data and any homework they mention, " +
                    "probe what slipped and why, then end with exactly three concrete tasks for the coming week. Speak directly, 3-6 sentences per turn."
            else if (scoreMe)
                "You were role-playing '$style' with the user. Now step out of character and score them: what worked, what cost them, " +
                    "one line they should have said, and a score out of 10. Honest, specific, brief."
            else "Role-play '$style' realistically: you are the other side, with your own interests, pushback and pressure. Stay in " +
                "character, 1-3 sentences, never coach them mid-scene. Start the scene if the conversation is empty."
            val reply = runCatching { Ai.generate(container.settingsRepository,
                (if (mode == "mentor") Snapshot.of(container) + "\n\n" else "") + "Conversation:\n" +
                    turns.takeLast(14).joinToString("\n") { (u, t) -> (if (u) "User: " else "You: ") + t } + if (scoreMe) "\n\nScore the user now." else "",
                system = system, maxOutputTokens = 700) }.getOrElse { "(${com.nizam.app.ui.components.Feedback.friendly(it)})" }
            turns += false to reply; busy = false
            if (mode == "mentor" && reply.contains("1.") ) {
                // keep the homework: pull numbered lines into Tasks so next week's session can review them
                reply.lines().filter { Regex("^\\s*[1-3][.)]").containsMatchIn(it) }.take(3)
                    .forEach { container.taskRepository.add("🎓 " + it.trim().drop(2).trim(), null, 1) }
            }
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        ChipRow(options, style, { style = it; turns.clear() })
        LazyColumn(Modifier.weight(1f)) {
            if (turns.isEmpty()) item {
                OutlinedButton(onClick = { send(if (mode == "mentor") "Let's start this week's session." else "Begin the scene.") }) {
                    Text(if (mode == "mentor") "Start this week's session" else "Start the scene")
                }
            }
            items(turns) { (u, t) -> if (u) UserBubble(t) else Card { Text(t) } }
            if (busy) item { Box(Modifier.padding(12.dp)) { TypingDots(MaterialTheme.colorScheme.primary) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedTextField(draft, { draft = it }, label = { Text("Your reply") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Button(onClick = { send(draft) }) { Text("Send") }
        }
        if (mode == "sparring" && turns.size >= 4) OutlinedButton(onClick = { send("", scoreMe = true) }) { Text("Score me") }
    }
}

@Composable
private fun SeasonTab() {
    val c = LocalContext.current
    var theme by remember { mutableStateOf(Season.theme(c)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Name this quarter. Missions, the Council and the Curator will all lean toward it.", style = MaterialTheme.typography.bodyMedium)
        ChipRow(listOf("Discipline", "Health", "Faith", "Wealth", "Learning", "Family", "Rest", "Craft"), theme, { theme = it; Season.set(c, it) })
        OutlinedTextField(theme, { theme = it; Season.set(c, it) }, label = { Text("Or name your own season") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (theme.isNotBlank()) Card { Text("🌗 This is your Season of $theme", style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun Serendipity(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    val week = LocalDate.now().let { "${it.year}-W${it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}" }
    val p = c.getSharedPreferences("serendipity", 0)
    var ideas by remember { mutableStateOf(p.getString(week, "").orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Once a week, three small new experiences near you — so life doesn't shrink to the same four places.",
            style = MaterialTheme.typography.bodyMedium)
        Button(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                ideas = runCatching { Ai.generate(container.settingsRepository, Snapshot.of(container) +
                    "\n\nSuggest three small, specific, doable new experiences for this week in their city: a kind of food they haven't mentioned, " +
                    "a place (park, museum, old bazaar, bookshop, masjid in another neighbourhood), and one social or creative thing. " +
                    "Budget-friendly, halal, one line each with why.", maxOutputTokens = 400) }.getOrElse { it.message.orEmpty() }
                p.edit().putString(week, ideas).apply(); busy = false
            }
        }) { Text(if (busy) "Thinking…" else if (ideas.isBlank()) "This week's three" else "Different ideas") }
        if (ideas.isNotBlank()) Card { Text(ideas) }
    }
}

/**
 * Legacy vault: letters released to people you choose only if you stop opening Atlas for a period you
 * set — after several warnings first. Delivery is by SMS from this phone.
 */
object Legacy {
    fun p(c: Context) = c.getSharedPreferences("legacy", 0)
    fun touch(c: Context) = p(c).edit().putLong("seen", System.currentTimeMillis()).apply()
}

@Composable
private fun LegacyTab() {
    val c = LocalContext.current
    val p = Legacy.p(c)
    var on by remember { mutableStateOf(p.getBoolean("on", false)) }
    var days by remember { mutableStateOf(p.getInt("days", 60).toString()) }
    var to by remember { mutableStateOf(p.getString("to", "").orEmpty()) }
    var letter by remember { mutableStateOf(p.getString("letter", "").orEmpty()) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("If you ever stop opening Atlas for a long time, it can deliver a message to people you choose. You'll be warned " +
            "7, 3 and 1 day before — opening Atlas once cancels everything.", style = MaterialTheme.typography.bodyMedium)
        ChipRow(listOf("30", "60", "90", "180"), days, { days = it }, labels = { "$it days" })
        OutlinedTextField(to, { to = it }, label = { Text("Deliver to (contact names or numbers, one per line)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(letter, { letter = it }, label = { Text("Your message") }, modifier = Modifier.fillMaxWidth().height(200.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = {
                on = !on; p.edit().putBoolean("on", on).putInt("days", days.toIntOrNull() ?: 60).putString("to", to).putString("letter", letter)
                    .putLong("seen", System.currentTimeMillis()).putStringSet("warned", emptySet()).apply()
            }) { Text(if (on) "Turn off" else "Arm legacy vault") }
            Spacer(Modifier.width(10.dp))
            Text(if (on) "Armed · ${days} days" else "Off", color = MaterialTheme.colorScheme.primary)
        }
        Text("Needs SMS permission (Me > Phone control). Nothing is sent while you keep using Atlas.",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── Night companion ───────────────────────────────────────────────────────────
@Composable
fun NightCompanionScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    val speaker = remember { Speaker(c) }
    DisposableEffect(Unit) { onDispose { speaker.release() } }
    val turns = remember { mutableStateListOf<Pair<Boolean, String>>() }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var voice by remember { mutableStateOf(false) }
    fun send(t: String) {
        if (t.isBlank() || busy) return
        turns += true to t; draft = ""
        scope.launch {
            busy = true
            val r = runCatching { Ai.generate(container.settingsRepository,
                "Conversation so far:\n" + turns.takeLast(12).joinToString("\n") { (u, x) -> (if (u) "Them: " else "You: ") + x },
                system = "It's night and they're winding down. Be a calm, gentle companion who remembers the conversation: help them set " +
                    "down the day, notice one good thing, and release tomorrow's worries until morning. Short, soft replies (1-3 sentences). " +
                    "This is reflection, not therapy — if they're in real distress, gently encourage them to reach someone they trust or a helpline.",
                maxOutputTokens = 300) }.getOrElse { "…" }
            turns += false to r; if (voice) speaker.speak(r); busy = false
        }
    }
    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 18.dp)) {
        ModuleTitle("Night companion", "Set the day down before you sleep")
        Text(if (voice) "🔊 reading replies aloud" else "🔇 silent", color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(role = Role.Button) { voice = !voice })
        LazyColumn(Modifier.weight(1f)) {
            if (turns.isEmpty()) item {
                listOf("Today was a lot.", "I can't switch my brain off.", "Something good happened today.").forEach { s ->
                    Text("✦ $s", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(role = Role.Button) { send(s) }.padding(vertical = 8.dp))
                }
            }
            items(turns) { (u, t) -> if (u) UserBubble(t) else Card { Text(t) } }
            if (busy) item { Box(Modifier.padding(12.dp)) { TypingDots(MaterialTheme.colorScheme.primary) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedTextField(draft, { draft = it }, label = { Text("How was today?") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp)); Button(onClick = { send(draft) }) { Text("Send") }
        }
    }
}

// ── Reading as a podcast ──────────────────────────────────────────────────────
@Composable
fun PodcastTab(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    val speaker = remember { Speaker(c) }
    DisposableEffect(Unit) { onDispose { speaker.release() } }
    var script by remember { mutableStateOf(c.getSharedPreferences("podcast", 0).getString("s", "").orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    Column {
        Text("Turns your unread saved articles into a short spoken episode for the commute.", style = MaterialTheme.typography.bodyMedium)
        Button(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                val unread = ReadLater.all(c).filter { !it.read }.take(4)
                script = if (unread.isEmpty()) "Nothing unread in Read later yet." else runCatching {
                    Ai.generate(container.settingsRepository, "Write a 4-minute solo podcast script, spoken style, no headings or symbols, " +
                        "covering these articles: the key idea of each, why it matters to this listener, and one thing to try. Warm intro, crisp outro.\n\n" +
                        unread.joinToString("\n\n") { "${it.title}\n${it.text.take(5000)}" }, maxOutputTokens = 1400)
                }.getOrElse { it.message.orEmpty() }
                c.getSharedPreferences("podcast", 0).edit().putString("s", script).apply(); busy = false
            }
        }) { Text(if (busy) "Recording…" else "Make an episode") }
        if (script.isNotBlank()) {
            Row { OutlinedButton(onClick = { speaker.speak(script) }) { Text("> Listen") }; Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { speaker.stop() }) { Text("Stop") } }
            Card { Text(script) }
        }
    }
}
