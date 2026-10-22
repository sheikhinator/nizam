package com.nizam.app.feature.expertise

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.BarChart
import com.nizam.app.core.LineChart
import com.nizam.app.core.Point
import com.nizam.app.feature.agent.Actions
import com.nizam.app.feature.flows.Feeds
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.Md
import com.nizam.app.feature.nx.ResultActions
import com.nizam.app.feature.nx.Roads
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.Motion
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.StatTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

// ───────────────────────────── Oracle ─────────────────────────────

/**
 * Oracle: ask anything across your whole life. Atlas reads every hub through Roads and everything you saved
 * (notes, files, chats, receipts) through the second brain at once, then answers with a chart, its sources and
 * how sure it is.
 */
object Oracle {
    fun store(c: Context) = JsonStore(c, "oracle_answers")

    data class Answer(val text: String, val chartTitle: String, val points: List<Point>, val sources: List<String>, val confidence: String)

    suspend fun ask(container: AppContainer, question: String, web: Boolean): Answer {
        val c = container.appContext
        runCatching { Roads.warm(container) }
        Pulse.emit(Pulse.Kind.WORKING, "Oracle", question.take(80), Hub.Tools.label)
        val (life, brain) = coroutineScope {
            val a = async(Dispatchers.IO) { Roads.relevant(c, question, 5000) }
            val b = async { runCatching { Actions.run(container, "ask_brain", JSONObject().put("question", question)).summary }.getOrDefault("") }
            a.await() to b.await()
        }
        val raw = Ai.generate(container.settingsRepository,
            "Today is ${LocalDate.now()}.\nThe user's recorded data, shared by them:\n$life\n\nFrom their saved notes, files and chats:\n${brain.take(2500)}\n\n" +
                "Question: $question\n\nReturn ONLY JSON: {\"answer\":\"markdown answer, specific, with numbers and dates\"," +
                "\"chart\":{\"title\":\"what the chart shows, or empty\",\"labels\":[\"...\"],\"values\":[numbers]}," +
                "\"sources\":[\"which parts of Atlas you used, e.g. Money, Prayer, Notes\"],\"confidence\":\"high|medium|low — why\"}. " +
                "Only include a chart when the data has at least 3 comparable numbers. Never invent data.",
            "You are Atlas answering from the user's whole life. ${Lenses.RULES}", 1400, web)
        val o = MindKit.json(raw)
        val ans = if (o == null) Answer(raw, "", emptyList(), emptyList(), "") else {
            val ch = o.optJSONObject("chart")
            val labels = MindKit.strings(ch, "labels")
            val values = ch?.optJSONArray("values")?.let { a -> (0 until a.length()).map { a.optDouble(it, 0.0) } }.orEmpty()
            Answer(o.optString("answer"), ch?.optString("title").orEmpty(),
                labels.zip(values).map { Point(it.first.take(8), it.second) }, MindKit.strings(o, "sources"), o.optString("confidence"))
        }
        store(c).let { st -> st.save((st.all() + JSONObject().put("id", System.currentTimeMillis().toString()).put("q", question)
            .put("text", ans.text).put("at", System.currentTimeMillis())).takeLast(40)) }
        Pulse.emit(Pulse.Kind.DONE, "Oracle answered", question.take(80), Hub.Tools.label)
        return ans
    }
}

@Composable
fun OracleScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var q by remember { mutableStateOf("") }
    var web by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var ans by remember { mutableStateOf<Oracle.Answer?>(null) }
    var version by remember { mutableIntStateOf(0) }
    val past = remember(version) { Oracle.store(ctx).all().sortedByDescending { it.optLong("at") }.take(8) }
    fun go(text: String) {
        q = text; busy = true
        scope.launch {
            runCatching { Oracle.ask(container, text, web) }.onSuccess { ans = it; version++ }.onFailure { Feedback.show(it.message ?: "Oracle couldn't answer") }
            busy = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("Ask anything about your life") }, minLines = 2)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Also search the web", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(web, { web = it })
        }
        Button(onClick = { go(q) }, enabled = q.isNotBlank() && !busy) { Text(if (busy) "Looking across your life…" else "Ask") }
        if (ans == null && !busy) {
            Hint("Try")
            listOf("When do I spend the most, and on what?", "How has my prayer consistency changed this month?",
                "What did I decide about my career, and how is it going?", "Which days do I feel best, and what do they have in common?")
                .forEach { s -> ListRow(s, icon = Icons.Outlined.AutoAwesome) { go(s) } }
        }
        if (busy) com.nizam.app.ui.components.Thinking(label = "Reading every hub")
        ans?.let { a ->
            Group { Md(a.text) }
            if (a.points.size >= 3) {
                Heading(a.chartTitle.ifBlank { "Chart" })
                BarChart(a.points, MaterialTheme.colorScheme.primary, 120)
            }
            if (a.sources.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                a.sources.forEach { Capsule(it, false) {} }
            }
            if (a.confidence.isNotBlank()) Text("Confidence: ${a.confidence}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            ResultActions("Oracle", a.text)
        }
        if (past.isNotEmpty()) {
            Heading("Asked before")
            past.forEach { o -> ListRow(o.optString("q"), icon = Icons.Outlined.History) { ans = Oracle.Answer(o.optString("text"), "", emptyList(), emptyList(), ""); q = o.optString("q") } }
        }
        AlsoHere(open, "Ask my data" to "nx/askdata", "Cited answers" to "nx/citedanswer", "Ask my past" to "nx/askpast",
            "Deep research" to "research", "Second brain" to "brain", "Explain my numbers" to "nx/whynumbers")
    }
}

// ───────────────────────────── Knowledge Base ─────────────────────────────

/**
 * The knowledge base: everything Atlas can cite. Your own documents and pasted pages live here; notes, diary,
 * read-later and every tracker come in through Roads. One search across all of it, and answers that cite.
 */
object Knowledge {
    fun store(c: Context) = JsonStore(c, "kb_docs")

    fun add(c: Context, title: String, text: String, tags: String) {
        store(c).add(JSONObject().put("title", title.trim()).put("text", text.trim()).put("tags", tags.trim()).put("at", System.currentTimeMillis()))
        Feeds.add(c, "Knowledge", "${title.trim()}: ${text.trim().replace('\n', ' ').take(300)}")
        Pulse.emit(Pulse.Kind.CHANGED, "Added to knowledge", title.take(60), Hub.Memory.label)
    }

    data class Hit(val title: String, val text: String, val source: String, val score: Double)

    fun search(c: Context, q: String): List<Hit> {
        val words = q.lowercase().split(Regex("[^a-z0-9؀-ۿ]+")).filter { it.length > 2 }
        if (words.isEmpty()) return emptyList()
        fun score(t: String): Double { val l = t.lowercase(); return words.sumOf { w -> if (l.contains(w)) 1.0 + (l.split(w).size - 2) * 0.2 else 0.0 } }
        val docs = store(c).all().map { Hit(it.optString("title"), it.optString("text"), "Your documents", score(it.optString("title") + " " + it.optString("tags")) * 2 + score(it.optString("text"))) }
        val life = Roads.items(c, 60).map { Hit(it.source, it.text, it.source, score(it.text)) }
        return (docs + life).filter { it.score > 0 }.sortedByDescending { it.score }.take(20)
    }

    suspend fun answer(container: AppContainer, q: String): String {
        val hits = withContext(Dispatchers.IO) { search(container.appContext, q) }
        val body = hits.take(12).mapIndexed { i, h -> "[${i + 1}] ${h.title} (${h.source}): ${h.text.take(700)}" }.joinToString("\n")
        return Ai.generate(container.settingsRepository,
            "The user's knowledge, shared by them:\n${body.ifBlank { "(nothing matches)" }}\n\nQuestion: $q",
            "Answer only from the numbered knowledge, citing like [2]. If it isn't there, say so and suggest what to add. End with 'Sources:' listing the titles used.", 1000)
    }
}

@Composable
fun KnowledgeScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var q by remember { mutableStateOf("") }
    var adding by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var version by remember { mutableIntStateOf(0) }
    var answer by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val docs = remember(version) { Knowledge.store(ctx).all().sortedByDescending { it.optLong("at") } }
    var hits by remember { mutableStateOf<List<Knowledge.Hit>>(emptyList()) }
    LaunchedEffect(q) { com.nizam.app.ui.design.guard { hits = if (q.length < 3) emptyList() else withContext(Dispatchers.IO) { Knowledge.search(ctx, q) } } }

    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            StatTile("Your documents", "${docs.size}", Modifier.weight(1f))
            StatTile("Connected sources", "${remember { Roads.sources(ctx).size }}", Modifier.weight(1f))
        }
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search everything you know") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Button(onClick = { busy = true; scope.launch { runCatching { Knowledge.answer(container, q) }.onSuccess { answer = it }.onFailure { Feedback.show(it.message ?: "Couldn't answer") }; busy = false } },
                enabled = q.isNotBlank() && !busy) { Text(if (busy) "Reading…" else "Ask with sources") }
            OutlinedButton(onClick = { adding = !adding }) { Text(if (adding) "Cancel" else "Add knowledge") }
        }
        if (adding) Group {
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Title") }, singleLine = true)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(top = Space.s), label = { Text("Paste the text, a page or your notes") }, minLines = 5)
            OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth().padding(top = Space.s), label = { Text("Tags (optional)") }, singleLine = true)
            Button(onClick = { Knowledge.add(ctx, title, text, tags); title = ""; text = ""; tags = ""; adding = false; version++; Feedback.show("Atlas can now cite it") },
                Modifier.padding(top = Space.s), enabled = title.isNotBlank() && text.isNotBlank()) { Text("Save") }
        }
        answer?.let { Group { Md(it) }; ResultActions("Knowledge", it) }
        if (hits.isNotEmpty()) {
            Heading("Found ${hits.size}")
            hits.forEach { h -> ListRow(h.title, supporting = h.text.take(120), icon = Icons.Outlined.Article) }
        } else if (docs.isNotEmpty()) {
            Heading("Your documents")
            docs.take(30).forEach { d ->
                ListRow(d.optString("title"), supporting = d.optString("text").take(100), icon = Icons.Outlined.Description, trailing = {
                    Text("✕", Modifier.padding(Space.s), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }) {
                    val id = d.optString("id"); val copy = JSONObject(d.toString())
                    Knowledge.store(ctx).remove(id); version++
                    Feedback.show("Removed ${d.optString("title")}", "Undo") { Knowledge.store(ctx).add(copy); version++ }
                }
            }
        }
        AlsoHere(open, "Second brain" to "brain", "Search by meaning" to "nx/semantic", "Chat with a document" to "nx/docchat",
            "Read later" to "nx/readlater", "Library" to "library", "Notes" to "notes", "Offline library" to "nx/wiki")
    }
}

// ───────────────────────────── Memory Graph ─────────────────────────────

/**
 * The memory graph: the people, places and things that run through your records, and how they connect.
 * Names are found on the phone; Atlas can sort them into people, places and things on request. The people
 * view is the People Map.
 */
object MemoryGraph {
    data class Node(val name: String, val count: Int, var kind: String)
    data class Graph(val nodes: List<Node>, val edges: List<Triple<Int, Int, Int>>)

    private val NOT = setOf("The", "This", "That", "Today", "Tomorrow", "Yesterday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday",
        "Saturday", "Sunday", "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November",
        "December", "Logged", "Rs", "Fajr", "Dhuhr", "Asr", "Maghrib", "Isha", "Mood", "Habit", "Task", "Done", "Open", "Note", "Notes", "And",
        "But", "For", "With", "From", "What", "When", "Where", "How", "Why", "Who", "Atlas", "I", "It", "My", "We", "You", "He", "She", "They",
        "Prayed", "Not", "Late", "On", "In", "At", "To", "Of", "A", "An", "If", "So", "Do", "Did", "Go", "Got", "Get", "Also", "Then", "Yes", "No")

    private fun prefs(c: Context) = c.getSharedPreferences("memory_graph", 0)
    fun kinds(c: Context): Map<String, String> = runCatching { JSONObject(prefs(c).getString("kinds", "{}")!!) }.getOrDefault(JSONObject())
        .let { o -> o.keys().asSequence().associateWith { o.optString(it) } }

    fun build(c: Context, max: Int = 34): Graph {
        val items = Roads.items(c, 80).filter { it.source != "Money" && it.source != "Prayer" }
        val per = items.map { i -> Regex("\\b[A-Z][a-z]{2,}(?: [A-Z][a-z]{2,})?\\b").findAll(i.text).map { it.value }.filter { it.split(' ').first() !in NOT }.toSet() }
        val counts = per.flatten().groupingBy { it }.eachCount().filter { it.value >= 2 }.entries.sortedByDescending { it.value }.take(max)
        val known = kinds(c)
        val nodes = counts.map { Node(it.key, it.value, known[it.key] ?: "thing") }
        val idx = nodes.withIndex().associate { it.value.name to it.index }
        val pairs = HashMap<Pair<Int, Int>, Int>()
        per.forEach { set -> val ids = set.mapNotNull { idx[it] }.sorted()
            for (a in ids.indices) for (b in a + 1 until ids.size) pairs.merge(ids[a] to ids[b], 1, Int::plus) }
        return Graph(nodes, pairs.entries.sortedByDescending { it.value }.take(80).map { Triple(it.key.first, it.key.second, it.value) })
    }

    suspend fun classify(container: AppContainer, names: List<String>) {
        val raw = Ai.generate(container.settingsRepository, "Names: ${names.joinToString(", ")}\nClassify each as person, place or thing. " +
            "Return ONLY JSON {\"Name\":\"person|place|thing\"}.", "Be brief.", 600)
        val o = MindKit.json(raw) ?: return
        val merged = JSONObject(prefs(container.appContext).getString("kinds", "{}")!!)
        o.keys().forEach { merged.put(it, o.optString(it)) }
        prefs(container.appContext).edit().putString("kinds", merged.toString()).apply()
    }

    fun facts(c: Context, name: String): List<Roads.Item> = Roads.items(c, 80).filter { it.text.contains(name) }.take(15)
}

@Composable
fun MemoryGraphScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var graph by remember { mutableStateOf<MemoryGraph.Graph?>(null) }
    var filter by remember { mutableStateOf("all") }
    var picked by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    LaunchedEffect(version) { com.nizam.app.ui.design.guard { runCatching { Roads.warm(container) }; graph = withContext(Dispatchers.IO) { MemoryGraph.build(ctx) } } }
    val g = graph
    val primary = MaterialTheme.colorScheme.primary
    val people = Hub.Social.tint(); val places = Hub.Travel.tint(); val things = Hub.Memory.tint()
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            listOf("all" to "Everything", "person" to "People map", "place" to "Places", "thing" to "Things").forEach { (k, l) -> Capsule(l, filter == k) { filter = k } }
        }
        if (g == null) com.nizam.app.ui.components.Thinking(label = "Reading your records")
        else if (g.nodes.isEmpty()) Hint("Names appear here once they come up a few times in your notes, diary and trackers.")
        else {
            val shown = g.nodes.withIndex().filter { filter == "all" || it.value.kind == filter }.map { it.index }.toSet()
            val t = com.nizam.app.ui.design.rememberLoop(90_000) * 6.2832f
            Canvas(Modifier.fillMaxWidth().height(320.dp).pointerInput(g, shown) {
                detectTapGestures { p ->
                    val n = g.nodes.size; val cx = size.width / 2f; val cy = size.height / 2f
                    g.nodes.forEachIndexed { i, node ->
                        if (i !in shown) return@forEachIndexed
                        val a = i * 2.39996 + t * 0.2; val r = (sqrt((i + 1).toDouble() / n) * (minOf(cx, cy) - 26)).toFloat()
                        val pos = Offset(cx + (cos(a) * r).toFloat(), cy + (sin(a) * r).toFloat())
                        if ((pos - p).getDistance() < 36f) picked = node.name
                    }
                }
            }) {
                val n = g.nodes.size; val cx = size.width / 2f; val cy = size.height / 2f
                val pos = g.nodes.indices.map { i ->
                    val a = i * 2.39996 + t * 0.2; val r = (sqrt((i + 1).toDouble() / n) * (minOf(cx, cy) - 26)).toFloat()
                    Offset(cx + (cos(a) * r).toFloat(), cy + (sin(a) * r).toFloat())
                }
                g.edges.forEach { (a, b, w) -> if (a in shown && b in shown) drawLine(line.copy(alpha = (0.25f + w * 0.1f).coerceAtMost(0.8f)), pos[a], pos[b], strokeWidth = 1f + w * 0.5f) }
                g.nodes.forEachIndexed { i, node ->
                    if (i !in shown) return@forEachIndexed
                    val col = when (node.kind) { "person" -> people; "place" -> places; else -> things }
                    val rad = 8f + ln(node.count.toFloat() + 1) * 7f
                    drawCircle(col.copy(alpha = 0.22f), rad * 1.8f, pos[i])
                    drawCircle(col, rad, pos[i])
                    if (node.name == picked) drawCircle(primary, rad + 5f, pos[i], style = Stroke(3f))
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                g.nodes.withIndex().filter { it.index in shown }.forEach { (_, n) -> Capsule("${n.name} · ${n.count}", picked == n.name) { picked = n.name } }
            }
            OutlinedButton(onClick = {
                busy = true
                scope.launch { runCatching { MemoryGraph.classify(container, g.nodes.map { it.name }) }; busy = false; version++ }
            }, enabled = !busy) { Text(if (busy) "Sorting…" else "Sort into people, places and things") }
        }
        picked?.let { name ->
            Heading(name)
            val f = remember(name) { MemoryGraph.facts(ctx, name) }
            f.forEach { ListRow(it.text.take(140), supporting = it.source, icon = Icons.Outlined.Circle) }
            OutlinedButton(onClick = { Expertise.queueAsk("Tell me everything my records say about $name, and what I might be forgetting."); open("nx/atlastalk") }) {
                Text("Ask Atlas about $name")
            }
        }
        AlsoHere(open, "Knowledge graph" to "nx/kgraph", "What Atlas knows" to "knowsme", "What Atlas learned" to "nx/atlaslearned",
            "People" to "people", "Family tree" to "nx/familytree", "Person page" to "nx/personpage")
    }
}

// ───────────────────────────── Predict ─────────────────────────────

/**
 * Predict: one forecasting engine for every part of life. A daily series from Roads, its trend and the range
 * it usually wanders in, projected 30 days ahead with a band. The TimesFM forecast, Life Forecast and the
 * life simulator stay as deeper views of the same question.
 */
object Predict {
    data class Series(val key: String, val label: String, val unit: String, val value: (List<Roads.Item>, Long, Long) -> Double)

    private val money = Regex("^expense Rs ([\\d,]+)")
    val series = listOf(
        Series("spend", "Spending per day", "Rs") { i, a, b -> i.filter { it.source == "Money" && it.ts in a until b }.sumOf { x -> money.find(x.text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: 0.0 } },
        Series("prayer", "Prayers per day", "of 5") { i, a, b -> i.filter { it.source == "Prayer" && it.ts in a until b }.mapNotNull { Regex("(\\d) of 5").find(it.text)?.groupValues?.get(1)?.toDoubleOrNull() }.maxOrNull() ?: Double.NaN },
        Series("mood", "Mood", "/5") { i, a, b -> i.filter { (it.source == "Mood check-ins" || it.source == "Diary") && it.ts in a until b }.mapNotNull { Regex("mood (\\d+)").find(it.text)?.groupValues?.get(1)?.toDoubleOrNull() }.let { if (it.isEmpty()) Double.NaN else it.average() } },
        Series("habits", "Habit check-ins", "per day") { i, a, b -> i.count { it.source == "Habits" && it.ts in a until b }.toDouble() },
        Series("workouts", "Training entries", "per day") { i, a, b -> i.count { (it.source == "Workouts" || it.source == "Cardio") && it.ts in a until b }.toDouble() },
        Series("tasks", "Tasks finished", "per day") { i, a, b -> i.count { it.source == "Tasks" && it.text.contains("[done]") && it.ts in a until b }.toDouble() },
        Series("all", "Everything logged", "entries") { i, a, b -> i.count { it.ts in a until b }.toDouble() },
    )

    data class Forecast(val history: List<Double>, val ahead: List<Double>, val low: List<Double>, val high: List<Double>, val slope: Double)

    fun run(items: List<Roads.Item>, s: Series, days: Int = 60, horizon: Int = 30): Forecast {
        val day = 86_400_000L
        val start = LocalDate.now().minusDays(days - 1L).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val raw = (0 until days).map { d -> s.value(items, start + d * day, start + (d + 1) * day) }
        // missing days (no record) are filled with the running value so they don't drag the line to zero
        var last = raw.firstOrNull { !it.isNaN() } ?: 0.0
        val h = raw.map { if (it.isNaN()) last else { last = it; it } }
        val n = h.size; val xs = (0 until n).map { it.toDouble() }
        val mx = xs.average(); val my = h.average()
        val slope = xs.zip(h).sumOf { (x, y) -> (x - mx) * (y - my) } / xs.sumOf { (it - mx) * (it - mx) }.coerceAtLeast(1e-9)
        val icept = my - slope * mx
        val resid = sqrt(xs.zip(h).sumOf { (x, y) -> val e = y - (icept + slope * x); e * e } / n.coerceAtLeast(1))
        val ahead = (0 until horizon).map { k -> (icept + slope * (n + k)).coerceAtLeast(0.0) }
        val widen = (0 until horizon).map { k -> resid * (1.0 + k / horizon.toDouble()) }
        return Forecast(h, ahead, ahead.zip(widen).map { (a, w) -> (a - 1.28 * w).coerceAtLeast(0.0) }, ahead.zip(widen).map { (a, w) -> a + 1.28 * w }, slope)
    }
}

@Composable
fun PredictScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var items by remember { mutableStateOf<List<Roads.Item>>(emptyList()) }
    var key by remember { mutableStateOf("spend") }
    var why by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { runCatching { Roads.warm(container) }; items = withContext(Dispatchers.IO) { Roads.items(ctx, 400) } } }
    val s = Predict.series.first { it.key == key }
    val f = remember(items, key) { if (items.isEmpty()) null else Predict.run(items, s) }
    val tint = MaterialTheme.colorScheme.primary
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            Predict.series.forEach { x -> Capsule(x.label, key == x.key) { key = x.key; why = null } }
        }
        if (f == null) com.nizam.app.ui.components.Thinking(label = "Reading 60 days")
        else {
            val fmt: (Double) -> String = { v -> if (s.unit == "Rs") com.nizam.app.feature.nx.rs(v) else "%.1f".format(v) }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                StatTile("Last 7 days", fmt(f.history.takeLast(7).average()), Modifier.weight(1f))
                StatTile("In 30 days", fmt(f.ahead.last()), Modifier.weight(1f), caption = "${fmt(f.low.last())}–${fmt(f.high.last())}")
                StatTile("Trend", if (kotlin.math.abs(f.slope) < 1e-3) "Flat" else if (f.slope > 0) "Rising" else "Falling", Modifier.weight(1f), tint = tint)
            }
            Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                val all = f.history + f.high
                val maxV = (all.maxOrNull() ?: 1.0).coerceAtLeast(1e-6)
                val total = f.history.size + f.ahead.size
                fun x(i: Int) = size.width * i / (total - 1).toFloat()
                fun y(v: Double) = size.height - (v / maxV * size.height * 0.9).toFloat()
                val band = Path().apply {
                    f.high.forEachIndexed { k, v -> val px = x(f.history.size + k); if (k == 0) moveTo(px, y(v)) else lineTo(px, y(v)) }
                    f.low.indices.reversed().forEach { k -> lineTo(x(f.history.size + k), y(f.low[k])) }
                    close()
                }
                drawPath(band, tint.copy(alpha = 0.14f))
                val hist = Path().apply { f.history.forEachIndexed { i, v -> if (i == 0) moveTo(x(i), y(v)) else lineTo(x(i), y(v)) } }
                drawPath(hist, tint, style = Stroke(3f))
                val fut = Path().apply { moveTo(x(f.history.size - 1), y(f.history.last())); f.ahead.forEachIndexed { k, v -> lineTo(x(f.history.size + k), y(v)) } }
                drawPath(fut, tint, style = Stroke(3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))))
                drawLine(faint.copy(alpha = 0.4f), Offset(x(f.history.size - 1), 0f), Offset(x(f.history.size - 1), size.height), 1.5f)
            }
            Hint("Solid: the last 60 days. Dashed: the next 30, with the range it usually wanders in.")
            Button(onClick = {
                busy = true
                scope.launch {
                    runCatching {
                        Ai.generate(container.settingsRepository,
                            "Series: ${s.label} (${s.unit}). Last 14 days: ${f.history.takeLast(14).joinToString { "%.1f".format(it) }}. " +
                                "Forecast in 30 days: ${"%.1f".format(f.ahead.last())} (range ${"%.1f".format(f.low.last())}–${"%.1f".format(f.high.last())}).\n" +
                                "Recent life context, shared by the user:\n${Roads.relevant(ctx, s.label, 2500)}\n\nExplain what drives this trend and the one change that would bend it the right way.",
                            "You are Atlas. ${Lenses.RULES}", 600)
                    }.onSuccess { why = it }.onFailure { Feedback.show(it.message ?: "Couldn't explain") }
                    busy = false
                }
            }, enabled = !busy) { Text(if (busy) "Thinking…" else "Why, and what to change") }
            why?.let { Group { Md(it) } }
        }
        AlsoHere(open, "TimesFM forecast" to "nx/forecastlive", "Life Forecast" to "nx/lifeforecast", "Life simulator" to "lifesim",
            "Cash-flow" to "nx/cashflowforecast", "Rough patch predictor" to "nx/crashpredictor", "Forecasts" to "nx/forecast")
    }
}

val IntelligenceSpecs: List<com.nizam.app.feature.nx.Spec> get() = listOf(
    ScreenSpec("oracle", "Oracle", "Ask anything across your whole life, answered with charts and sources", Icons.Outlined.AutoAwesome, Hub.Tools,
        "ask oracle question data chart my life across everything") { c, o -> OracleScreen(c, o) },
    ScreenSpec("knowledgebase", "Knowledge Base", "Everything Atlas can cite: your documents, notes and every tracker, in one search", Icons.Outlined.LibraryBooks, Hub.Memory,
        "knowledge base documents cite search notes pages rag") { c, o -> KnowledgeScreen(c, o) },
    ScreenSpec("memorygraph", "Memory Graph", "The people, places and things in your life and how they connect; includes the People Map", Icons.Outlined.Hub, Hub.Memory,
        "memory graph people map entities relationships connections places") { c, o -> MemoryGraphScreen(c, o) },
    ScreenSpec("predict", "Predict", "One forecasting engine: any part of life, 30 days ahead, with a band", Icons.Outlined.ShowChart, Hub.Tools,
        "predict forecast trend future projection spending mood prayers") { c, o -> PredictScreen(c, o) },
)
