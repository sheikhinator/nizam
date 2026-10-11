package com.nizam.app.feature.reflect

import androidx.compose.foundation.horizontalScroll

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.agent.Snapshot
import com.nizam.app.feature.brain.Brain
import com.nizam.app.feature.brain.DecisionStore
import com.nizam.app.feature.cinema.CinemaStore
import com.nizam.app.feature.insight.DayVector
import com.nizam.app.feature.insight.Days
import com.nizam.app.feature.insight.METRICS
import com.nizam.app.feature.memory.DailyPhoto
import com.nizam.app.feature.progress.ProgressSummary
import com.nizam.app.feature.progress.Xp
import com.nizam.app.net.Ai
import com.nizam.app.ui.theme.DisplayFamily
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.math.sqrt
import kotlin.random.Random

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) { content() } }
}

@Composable
fun ReflectScreen(container: AppContainer) {
    val c = LocalContext.current
    var tab by remember { mutableStateOf("Wrapped") }
    var days by remember { mutableStateOf<List<DayVector>>(emptyList()) }
    var summary by remember { mutableStateOf<ProgressSummary?>(null) }
    LaunchedEffect(Unit) {
        days = runCatching { Days.build(container, c, 400) }.getOrElse { emptyList() }
        summary = runCatching { Xp.summarise(container) }.getOrNull()
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        ModuleTitle("Reflect", "Your story so far — and where it's heading")
        ChipRow(listOf("Wrapped", "Newspaper", "Life in weeks", "On this day", "Black box", "What if", "Dreams", "Map"), tab, { tab = it })
        Box(Modifier.weight(1f)) {
            when (tab) {
                "Wrapped" -> Wrapped(days, summary)
                "Newspaper" -> Newspaper(container, days)
                "Life in weeks" -> LifeInWeeks(container)
                "On this day" -> OnThisDay(container)
                "Black box" -> BlackBox(container, days)
                "What if" -> WhatIf(days)
                "Dreams" -> Dreams(container)
                else -> KnowledgeMap(container)
            }
        }
    }
}

// ── Wrapped + a shareable stats card ──────────────────────────────────────────
private data class Slide(val big: String, val line: String, val colour: Color)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Wrapped(days: List<DayVector>, s: ProgressSummary?) {
    val c = LocalContext.current
    var span by remember { mutableStateOf("Month") }
    val window = if (span == "Month") days.takeLast(30) else days.takeLast(365)
    fun sum(k: String) = window.sumOf { it.values[k] ?: 0.0 }
    // which parts of your life go into the story — remembered, and nothing outside them is shown or shared
    val prefs = remember { c.getSharedPreferences("wrapped_sources", 0) }
    val allSources = listOf("Progress", "Deen", "Fitness", "Study", "Mood", "Films")
    var sources by remember { mutableStateOf(allSources.filter { prefs.getBoolean(it, true) }.toSet()) }
    val slides = remember(window, s, span, sources) {
        val best = window.maxByOrNull { it.values["mood"] ?: -1.0 }
        val films = CinemaStore(c).history().size
        listOfNotNull(
            ("Progress" to Slide("${s?.level ?: 0}", "your level — ${s?.totalXp ?: 0} XP earned in all", Color(0xFF5B6BA8))),
            ("Progress" to Slide("${s?.bestStreak ?: 0}", "days — your longest streak", Color(0xFFD98E4A))),
            ("Deen" to Slide("${sum("prayers").toInt()}", "prayers logged this ${span.lowercase()}", Color(0xFF2F8A73))),
            ("Fitness" to Slide("${sum("sets").toInt()}", "gym sets, rep by rep", Color(0xFF8C2E1C))),
            ("Study" to Slide("${(sum("study") / 60).toInt()}h", "spent studying", Color(0xFF7A5BA8))),
            ("Study" to Slide("${(sum("focus") / 60).toInt()}h", "of deep focus", Color(0xFF3E7C8A))),
            best?.values?.get("mood")?.let { "Mood" to Slide(best.date.toString().takeLast(5), "your happiest day (mood ${"%.0f".format(it)}/5)", Color(0xFFB57F4A)) },
            ("Films" to Slide("$films", "films watched in Atlas", Color(0xFF8A4F6B))),
            ("Progress" to Slide("${window.count { d -> d.values.values.any { it != null } }}", "days you showed up", Color(0xFF6E8E4E)))
        ).filter { it.first in sources }.map { it.second }.ifEmpty { listOf(Slide("—", "pick at least one source above", Color(0xFF5B6BA8))) }
    }
    val pager = rememberPagerState { slides.size }
    LaunchedEffect(slides.size) { if (pager.currentPage >= slides.size) pager.scrollToPage(0) }
    Column(Modifier.fillMaxSize()) {
        ChipRow(listOf("Month", "Year"), span, { span = it })
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            allSources.forEach { src -> val on = src in sources
                androidx.compose.material3.FilterChip(selected = on, onClick = {
                    sources = if (on) sources - src else sources + src; prefs.edit().putBoolean(src, !on).apply()
                }, label = { Text(src) })
            }
        }
        HorizontalPager(pager, Modifier.weight(1f).padding(vertical = 8.dp)) { i ->
            val sl = slides[i]
            Column(Modifier.fillMaxSize().background(sl.colour, RoundedCornerShape(28.dp)).padding(28.dp),
                verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${i + 1}/${slides.size}", color = Color.White.copy(alpha = 0.6f))
                Text(sl.big, color = Color.White, fontSize = 88.sp, style = MaterialTheme.typography.displaySmall.copy(fontFamily = DisplayFamily))
                Text(sl.line, color = Color.White, fontSize = 20.sp, textAlign = TextAlign.Center)
            }
        }
        Button(onClick = { shareCard(c, "My $span in Atlas", slides.map { it.big + "  " + it.line }) }, modifier = Modifier.fillMaxWidth()) {
            Text("Share as image")
        }
    }
}

/** Draws a stats card to a PNG and opens the share sheet — for WhatsApp status or anywhere. */
fun shareCard(c: Context, title: String, lines: List<String>) {
    val w = 1080; val h = 1920
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val cv = android.graphics.Canvas(bmp); cv.drawColor(android.graphics.Color.rgb(11, 15, 26))
    val t = Paint().apply { color = android.graphics.Color.rgb(237, 239, 245); textSize = 86f; isAntiAlias = true; typeface = Typeface.SERIF }
    val b = Paint().apply { color = android.graphics.Color.rgb(237, 239, 245); textSize = 50f; isAntiAlias = true }
    val a = Paint().apply { color = android.graphics.Color.rgb(79, 195, 161); textSize = 40f; isAntiAlias = true }
    cv.drawText(title, 90f, 260f, t)
    lines.take(10).forEachIndexed { i, l -> cv.drawText(l.take(38), 90f, 460f + i * 120f, b) }
    cv.drawText("✦ made with Atlas", 90f, h - 140f, a)
    val f = File(c.cacheDir, "atlas_card.png"); f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    val uri = FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", f)
    c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

// ── Your week as a newspaper front page ───────────────────────────────────────
@Composable
private fun Newspaper(container: AppContainer, days: List<DayVector>) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var paper by remember { mutableStateOf(c.getSharedPreferences("paper", 0).getString("p", "").orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Button(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                paper = runCatching {
                    Ai.generate(container.settingsRepository,
                        Snapshot.of(container) + "\nLast 7 days: " + days.takeLast(7).joinToString("; ") { d ->
                            d.date.toString() + " " + d.values.filterValues { it != null }.entries.joinToString(",") { "${it.key}=${"%.0f".format(it.value)}" } } +
                            "\n\nWrite this person's week as the front page of a small-town newspaper called THE ATLAS GAZETTE. " +
                            "One big headline, a two-sentence lead story, then 3 short side stories and a one-line weather joke. " +
                            "Playful, affectionate, true to the numbers. Plain text, headlines in CAPS.", maxOutputTokens = 900)
                }.getOrElse { "Couldn't print this week: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
                c.getSharedPreferences("paper", 0).edit().putString("p", paper).apply(); busy = false
            }
        }) { Text(if (busy) "Printing…" else "Print this week's paper") }
        if (paper.isNotBlank()) {
            Column(Modifier.fillMaxWidth().padding(vertical = 10.dp).background(Color(0xFFF3EEE2), RoundedCornerShape(6.dp)).padding(18.dp)) {
                Text("THE ATLAS GAZETTE", color = Color(0xFF1A1814), fontSize = 26.sp, textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineSmall.copy(fontFamily = DisplayFamily), modifier = Modifier.fillMaxWidth())
                Text("Week ending ${LocalDate.now()}", color = Color(0xFF6B6459), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Text(paper, color = Color(0xFF1A1814), style = MaterialTheme.typography.bodyLarge.copy(fontFamily = DisplayFamily))
            }
            OutlinedButton(onClick = { shareCard(c, "The Atlas Gazette", paper.lines().filter { it.isNotBlank() }) }) { Text("Share") }
        }
    }
}

// ── Life in weeks ─────────────────────────────────────────────────────────────
@Composable
private fun LifeInWeeks(container: AppContainer) {
    var age by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        age = runCatching { JSONObject(container.settingsRepository.profileJson.first()).optString("Age").filter(Char::isDigit).toInt() }.getOrNull()
    }
    val lived = (age ?: 0) * 52 + LocalDate.now().dayOfYear / 7
    val colour = MaterialTheme.colorScheme.primary; val empty = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (age == null) Text("Add your age in your Profile to see this.", style = MaterialTheme.typography.bodyMedium)
        Text("Each dot is one week of a 90-year life. Filled: $lived weeks lived. Open: ${90 * 52 - lived} still to spend.",
            style = MaterialTheme.typography.bodyMedium)
        Canvas(Modifier.fillMaxWidth().aspectRatio(52f / 90f).padding(vertical = 8.dp)) {
            val cell = size.width / 52f
            for (i in 0 until 90 * 52) {
                val x = (i % 52) * cell; val y = (i / 52) * cell
                drawCircle(if (i < lived) colour else empty, radius = cell * 0.36f, center = Offset(x + cell / 2, y + cell / 2))
            }
        }
        Text("Not to frighten — to focus. A week is small enough to use well.", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── On this day ───────────────────────────────────────────────────────────────
@Composable
private fun OnThisDay(container: AppContainer) {
    val c = LocalContext.current
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        val today = LocalDate.now()
        val marks = listOf(today.minusWeeks(1) to "A week ago", today.minusMonths(1) to "A month ago", today.minusMonths(6) to "Six months ago") +
            (1..5).map { today.minusYears(it.toLong()) to "$it year${if (it > 1) "s" else ""} ago" }
        val diary = container.diaryRepository.entries().first()
        val decisions = DecisionStore(c).all()
        lines = marks.flatMap { (d, label) ->
            val k = d.toString()
            diary.filter { it.localDate == k }.map { "$label · diary (mood ${it.mood}/5): ${it.body.take(160)}" } +
                decisions.filter { it.date == k }.map { "$label · you decided: ${it.decision}" } +
                (if (DailyPhoto.file(c, k).exists()) listOf("$label · you took a photo of the day") else emptyList())
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (lines.isEmpty()) Text("Nothing from this date yet. As your history grows, a week, a month and years ago will surface here.",
            style = MaterialTheme.typography.bodyMedium)
        lines.forEach { Card { Text(it, style = MaterialTheme.typography.bodyLarge) } }
    }
}

// ── Black box: what came before a bad day ─────────────────────────────────────
@Composable
private fun BlackBox(container: AppContainer, days: List<DayVector>) {
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var target by remember { mutableStateOf(LocalDate.now().minusDays(1)) }
    var story by remember { mutableStateOf<String?>(null) }
    val before = days.filter { it.date < target && it.date >= target.minusDays(3) }
    val deviations = METRICS.keys.mapNotNull { k ->
        val base = days.mapNotNull { it.values[k] }.takeIf { it.size >= 7 }?.average() ?: return@mapNotNull null
        val recent = before.mapNotNull { it.values[k] }.takeIf { it.isNotEmpty() }?.average() ?: return@mapNotNull null
        if (base == 0.0) null else Triple(k, recent, (recent - base) / kotlin.math.abs(base) * 100)
    }.filter { kotlin.math.abs(it.third) >= 20 }.sortedByDescending { kotlin.math.abs(it.third) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Pick a day that went badly. Atlas replays the 72 hours before it against your normal.", style = MaterialTheme.typography.bodyMedium)
        ChipRow((1..7).map { LocalDate.now().minusDays(it.toLong()).toString() }, target.toString(), { target = LocalDate.parse(it) },
            labels = { it.takeLast(5) })
        if (deviations.isEmpty()) Text("Nothing stood out in the 72 hours before — or there isn't enough data yet.", modifier = Modifier.padding(top = 8.dp))
        deviations.forEach { (k, v, pct) ->
            Text("${METRICS[k]}: ${"%.1f".format(v)} — ${if (pct > 0) "▲" else "▼"} ${"%.0f".format(kotlin.math.abs(pct))}% vs your normal",
                style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
        }
        if (deviations.isNotEmpty()) OutlinedButton(onClick = {
            scope.launch {
                story = runCatching { Ai.generate(container.settingsRepository,
                    "Before a bad day on $target, these were off: " + deviations.joinToString { "${METRICS[it.first]} ${"%.0f".format(it.third)}% vs normal" } +
                        ". In 3 sentences: the likely chain of cause, what to watch for next time, and one early-warning habit. Plain, kind.",
                    maxOutputTokens = 300) }.getOrElse { it.message }
            }
        }, modifier = Modifier.padding(top = 8.dp)) { Text("Explain the pattern") }
        story?.let { Card { Text(it) } }
    }
}

// ── What-if simulator ─────────────────────────────────────────────────────────
@Composable
private fun WhatIf(days: List<DayVector>) {
    var metric by remember { mutableStateOf("focus") }
    var change by remember { mutableStateOf(20f) }
    val avg = days.takeLast(30).mapNotNull { it.values[metric] }.takeIf { it.isNotEmpty() }?.average() ?: 0.0
    val newAvg = avg * (1 + change / 100)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        ChipRow(METRICS.keys.toList(), metric, { metric = it }, labels = { METRICS[it] ?: it })
        Text("Change: ${if (change >= 0) "+" else ""}${change.toInt()}%", style = MaterialTheme.typography.titleMedium)
        Slider(change, { change = it }, valueRange = -50f..100f)
        Card {
            Text("Now: ${"%.1f".format(avg)} a day · With the change: ${"%.1f".format(newAvg)} a day", style = MaterialTheme.typography.bodyLarge)
            listOf(30 to "1 month", 91 to "3 months", 182 to "6 months", 365 to "1 year").forEach { (d, label) ->
                val now = avg * d; val then = newAvg * d
                Text("$label: ${"%.0f".format(now)} > ${"%.0f".format(then)}  (${if (then >= now) "+" else ""}${"%.0f".format(then - now)})",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
            if (metric == "focus" || metric == "study") Text("${"%.0f".format((newAvg - avg) * 365 / 60)} extra hours a year — " +
                "roughly ${"%.0f".format((newAvg - avg) * 365 / 60 / 40)} working weeks.", color = MaterialTheme.colorScheme.primary)
            Text("Straight-line projections from your last 30 days. Real life wobbles; the direction is what counts.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ── Dream journal ─────────────────────────────────────────────────────────────
@Composable
private fun Dreams(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    val f = remember { File(c.filesDir, "dreams.json") }
    var list by remember { mutableStateOf(runCatching { JSONArray(f.readText()) }.getOrElse { JSONArray() }) }
    var text by remember { mutableStateOf("") }
    var themes by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("Write it the moment you wake, before it fades. (The voice journal works for this too.)", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(text, { text = it }, label = { Text("Last night I dreamt…") }, modifier = Modifier.fillMaxWidth().height(140.dp))
        Button(enabled = text.isNotBlank(), onClick = {
            val a = JSONArray(list.toString()); a.put(JSONObject().put("date", LocalDate.now().toString()).put("text", text.trim()))
            f.writeText(a.toString()); list = a; text = ""
        }) { Text("Save dream") }
        if (list.length() >= 3) OutlinedButton(onClick = {
            scope.launch { themes = runCatching { Ai.generate(container.settingsRepository,
                "Here are someone's dream notes:\n" + (0 until list.length()).joinToString("\n") { list.getJSONObject(it).let { d -> "${d.optString("date")}: ${d.optString("text")}" } } +
                    "\n\nName the recurring themes, symbols and moods, and how they shift over time. Curious and gentle, not mystical " +
                    "or diagnostic. Under 150 words.", maxOutputTokens = 400) }.getOrElse { it.message } }
        }) { Text("Find recurring themes") }
        themes?.let { Card { Text(it) } }
        (0 until list.length()).reversed().forEach { i -> list.getJSONObject(i).let { d ->
            Card { Text(d.optString("date"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text(d.optString("text")) }
        } }
    }
}

// ── Knowledge map: your second brain as a web ─────────────────────────────────
@Composable
private fun KnowledgeMap(container: AppContainer) {
    val c = LocalContext.current
    data class Node(val title: String, val source: String, var x: Float, var y: Float)
    var nodes by remember { mutableStateOf<List<Node>>(emptyList()) }
    var edges by remember { mutableStateOf<List<Pair<Int, Int>>>(emptyList()) }
    var picked by remember { mutableStateOf<Node?>(null) }
    LaunchedEffect(Unit) {
        val docs = Brain.corpus(container, c).take(70)
        val toks = docs.map { Brain.tokens(it.title + " " + it.text).toSet() }
        val e = mutableListOf<Pair<Int, Int>>()
        for (i in docs.indices) for (j in i + 1 until docs.size) if ((toks[i] intersect toks[j]).size >= 3) e += i to j
        val rnd = Random(3)
        val n = docs.map { Node(it.title.take(40), it.source, rnd.nextFloat(), rnd.nextFloat()) }
        // a few hundred steps of a simple force layout: connected pull together, everything pushes apart
        repeat(250) {
            for (i in n.indices) for (j in n.indices) if (i != j) {
                val dx = n[i].x - n[j].x; val dy = n[i].y - n[j].y; val d2 = (dx * dx + dy * dy).coerceAtLeast(0.0004f)
                n[i].x += dx / d2 * 0.00002f; n[i].y += dy / d2 * 0.00002f
            }
            e.forEach { (a, b) -> val dx = n[b].x - n[a].x; val dy = n[b].y - n[a].y
                n[a].x += dx * 0.01f; n[a].y += dy * 0.01f; n[b].x -= dx * 0.01f; n[b].y -= dy * 0.01f }
            n.forEach { it.x = it.x.coerceIn(0.03f, 0.97f); it.y = it.y.coerceIn(0.03f, 0.97f) }
        }
        nodes = n; edges = e
    }
    val colours = mapOf("Note" to Color(0xFF58A6C9), "Diary" to Color(0xFFE08AA8), "Decision" to Color(0xFFE0B14A), "Verdict" to Color(0xFFB8BCC8),
        "Book" to Color(0xFFB57F4A), "Idea" to Color(0xFF9B8CD6), "Article" to Color(0xFF6FB98A))
    val line = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.25f)
    Column(Modifier.fillMaxSize()) {
        Text("${nodes.size} things you've saved · ${edges.size} connections. Tap a dot.", style = MaterialTheme.typography.labelMedium)
        Canvas(Modifier.fillMaxWidth().weight(1f).pointerInput(nodes) {
            detectTapGestures { p -> picked = nodes.minByOrNull { val dx = it.x * size.width - p.x; val dy = it.y * size.height - p.y; sqrt(dx * dx + dy * dy) } }
        }) {
            edges.forEach { (a, b) -> drawLine(line, Offset(nodes[a].x * size.width, nodes[a].y * size.height),
                Offset(nodes[b].x * size.width, nodes[b].y * size.height), strokeWidth = 2f) }
            nodes.forEach { drawCircle(colours[it.source] ?: Color(0xFF8C96AC), radius = if (it == picked) 16f else 10f,
                center = Offset(it.x * size.width, it.y * size.height)) }
        }
        picked?.let { Card { Text(it.source.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary); Text(it.title) } }
    }
}
