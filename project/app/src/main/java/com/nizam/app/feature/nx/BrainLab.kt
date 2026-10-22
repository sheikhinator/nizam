package com.nizam.app.feature.nx

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Art3D
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.nativeCanvas
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max
import kotlin.math.sqrt

/** Atlas thinking about its own thinking: repairs, second opinions, confidence, answers before you ask, and a map of what it knows. */
object BrainLab {
    val all: List<Spec> = listOf(
        ScreenSpec("selfrepair", "Self-repair", "Atlas checks itself and fixes what's broken", Icons.Outlined.Healing, Hub.Tools, "self repair fix doctor broken diagnose health check permissions") { c, _ -> SelfRepairScreen(c) },
        ScreenSpec("splitthink", "Split thinking", "A quick take and a careful one, side by side", Icons.Outlined.CallSplit, Hub.Memory, "split thinking second opinion careful quick reasoning") { c, _ -> SplitThinkScreen(c) },
        ScreenSpec("confident", "Confidence answers", "Every answer rated; unsure ones get checked on the web", Icons.Outlined.Verified, Hub.Memory, "confidence routing sure unsure verify fact check") { c, _ -> ConfidentScreen(c) },
        ScreenSpec("receipts", "Evidence Receipts", "Review dated source references, search coverage, and uncertainty", Icons.Outlined.Description, Hub.Learn, "evidence receipts sources coverage uncertainty provenance") { _, _ -> com.nizam.app.feature.brain2.EvidenceReceiptsScreen() },
        ScreenSpec("quickask", "Instant ask", "Starts answering while you type", Icons.Outlined.Bolt, Hub.Memory, "speculative fast instant answer prefetch") { c, _ -> QuickAskScreen(c) },
        ScreenSpec("kgraph", "Knowledge graph", "People, places and ideas from your notes — connected", Icons.Outlined.Hub, Hub.Memory, "knowledge graph map connections people ideas notes") { c, _ -> GraphScreen(c) },
        ScreenSpec("genscreen", "Make a screen", "Describe a tracker or checklist; Atlas builds it", Icons.Outlined.Dashboard, Hub.Create, "generative screen build app tracker checklist counter custom") { c, _ -> GenScreen(c) },
        ScreenSpec("atlascard", "Atlas-to-Atlas", "Send a task, plan or list to someone else's Atlas", Icons.Outlined.SwapHoriz, Hub.Social, "atlas protocol share send receive card qr task plan") { c, _ -> AtlasCardScreen(c) },
        ScreenSpec("zeroui", "Zero-UI mode", "No screens — just talk to Atlas", Icons.Outlined.GraphicEq, Hub.Tools, "zero ui voice only hands free talk") { c, o -> ZeroUiScreen(c, o) },
    )
}

// ─────────────────────────────── Self-repair ───────────────────────────────
private data class Check(val name: String, val ok: Boolean?, val detail: String, val fix: (() -> Unit)?)

private fun brokenJson(c: Context): List<File> = c.filesDir.listFiles()?.filter { it.isFile && it.name.endsWith(".json") && it.length() > 0 }?.filter { f ->
    val t = runCatching { f.readText() }.getOrNull() ?: return@filter false
    val s = t.trimStart()
    // sealed stores are not plain JSON; only judge files that look like JSON
    (s.startsWith("{") || s.startsWith("[")) && runCatching { if (s.startsWith("{")) JSONObject(t) else JSONArray(t) }.isFailure
} ?: emptyList()

private fun dirSize(f: File): Long = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

@Composable
fun SelfRepairScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var checks by remember { mutableStateOf<List<Check>>(emptyList()) }
    var running by remember { mutableStateOf(false) }
    var round by remember { mutableIntStateOf(0) }
    fun open(i: Intent) = runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { Feedback.show("Couldn't open that setting") }
    LaunchedEffect(round) { com.nizam.app.ui.design.guard {
        running = true
        val out = mutableListOf<Check>()
        val ai = runCatching { Ai.generate(container.settingsRepository, "Reply with the single word OK.", maxOutputTokens = 5) }
        out += Check("AI connection", ai.isSuccess, ai.fold({ "Answering normally" }, { Feedback.friendly(it) }),
            if (ai.isFailure) ({ open(Intent(c, com.nizam.app.MainActivity::class.java).putExtra("route", "keys")) }) else null)
        val pm = c.getSystemService(android.os.PowerManager::class.java)
        val batt = pm?.isIgnoringBatteryOptimizations(c.packageName) == true
        out += Check("Background work", batt, if (batt) "Reminders and alarms can run" else "The phone may kill reminders and prayer alerts",
            if (!batt) ({ open(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${c.packageName}"))) }) else null)
        if (Build.VERSION.SDK_INT >= 33) {
            val n = c.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            out += Check("Notifications", n, if (n) "Allowed" else "Atlas can't alert you", if (!n) ({
                open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName)) }) else null)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            val am = c.getSystemService(android.app.AlarmManager::class.java)
            val ex = am?.canScheduleExactAlarms() == true
            out += Check("Exact alarms", ex, if (ex) "Prayer alerts fire on the minute" else "Alerts may arrive late",
                if (!ex) ({ open(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${c.packageName}"))) }) else null)
        }
        val mic = c.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED
        out += Check("Microphone", mic, if (mic) "Voice commands work" else "Voice commands need it", if (!mic) ({
            open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))) }) else null)
        val broken = brokenJson(c)
        out += Check("Saved data", broken.isEmpty(), if (broken.isEmpty()) "Every store reads cleanly" else "${broken.size} damaged: " + broken.joinToString { it.name },
            if (broken.isNotEmpty()) ({ broken.forEach { f -> f.renameTo(File(f.parentFile, f.name + ".broken-" + System.currentTimeMillis())) }
                Feedback.show("Moved ${broken.size} damaged files aside — those screens start fresh"); round++ }) else null)
        val cache = dirSize(c.cacheDir)
        out += Check("Cache", cache < 400L * 1_048_576, "${cache / 1_048_576} MB", if (cache >= 150L * 1_048_576) ({
            c.cacheDir.listFiles()?.forEach { it.deleteRecursively() }; Feedback.show("Cache cleared"); round++ }) else null)
        val free = c.filesDir.usableSpace / 1_048_576
        out += Check("Phone storage", free > 1024, "$free MB free", if (free <= 1024) ({ open(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS)) }) else null)
        val lat = runCatching { container.settingsRepository.latitude.first() }.getOrNull()
        out += Check("Location for prayer times", lat != null && lat != 0.0, if (lat != null && lat != 0.0) "Set" else "Not set — prayer times may be wrong",
            if (lat == null || lat == 0.0) ({ open(Intent(c, com.nizam.app.MainActivity::class.java).putExtra("route", "prayer")) }) else null)
        checks = out; running = false
    } }
    val bad = checks.count { it.ok == false }
    AtlasScreen(title = "Self-repair", subtitle = if (running) "Checking…" else if (bad == 0) "All healthy" else "$bad things to fix", hub = Hub.Tools) {
        item("h") { Group(Modifier.padding(horizontal = Space.page)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Art3D(if (bad == 0 && !running) "check_mark_button" else "hammer_and_wrench", 64.dp)
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(if (running) "Running a check-up" else if (bad == 0) "Atlas is in good shape" else "Found $bad problems",
                        style = MaterialTheme.typography.titleMedium)
                    Text("Connection, permissions, damaged data, storage.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 10.dp))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { checks.filter { it.ok == false }.forEach { it.fix?.invoke() } }, enabled = bad > 0) { Text("Fix everything") }
                OutlinedButton(onClick = { round++ }) { Text("Check again") }
            }
        } }
        checks.forEachIndexed { i, k -> item("c$i") {
            ListRow(k.name, supporting = k.detail, icon = if (k.ok == true) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                tint = if (k.ok == true) Color(0xFF16A34A) else MaterialTheme.colorScheme.error,
                trailing = { if (k.ok == false && k.fix != null) Capsule("Fix", true) { k.fix.invoke(); scope.launch { delay(1500); round++ } } })
        } }
    }
}

// ─────────────────────────────── Split thinking ───────────────────────────────
@Composable
fun SplitThinkScreen(container: AppContainer) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var q by remember { mutableStateOf("") }
    var quick by remember { mutableStateOf<String?>(null) }
    var careful by remember { mutableStateOf<String?>(null) }
    var merged by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    AtlasScreen(title = "Split thinking", subtitle = "Two minds on one question", hub = Hub.Memory) {
        item("in") { Column(Modifier.padding(horizontal = Space.page)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Should I take the job in Dubai or stay in Lahore?") }, minLines = 2)
            Button(onClick = {
                busy = true; quick = null; careful = null; merged = null
                scope.launch {
                    val a = async { runCatching { Ai.generate(container.settingsRepository, q, "Answer instantly from instinct and experience. Two or three sentences. No hedging.", 220) }.getOrElse { Feedback.friendly(it) } }
                    val b = async { runCatching { Ai.generate(container.settingsRepository, q,
                        "Think slowly. List the assumptions, the strongest case each way, what would change your mind, then a clear final answer. Use short headed sections.", 1400) }.getOrElse { Feedback.friendly(it) } }
                    quick = a.await(); careful = b.await(); busy = false
                }
            }, enabled = q.isNotBlank() && !busy, modifier = Modifier.padding(top = 8.dp)) { Text(if (busy) "Thinking both ways…" else "Think") }
        } }
        quick?.let { t -> item("q") { SectionHeader("⚡ Quick take") }; item("qt") { Group(Modifier.padding(horizontal = Space.page)) { Md(t) } } }
        careful?.let { t -> item("c") { SectionHeader("🧠 Careful take") }; item("ct") { Group(Modifier.padding(horizontal = Space.page)) { Md(t) } } }
        if (quick != null && careful != null) item("m") { Column(Modifier.padding(horizontal = Space.page, vertical = 10.dp)) {
            OutlinedButton(onClick = { scope.launch { merged = runCatching { Ai.generate(container.settingsRepository,
                "Question: $q\n\nQuick take: $quick\n\nCareful take: $careful\n\nWhere do they disagree, and which is right? Give one final recommendation in 4 lines.", maxOutputTokens = 500) }.getOrElse { Feedback.friendly(it) } } }) { Text("Reconcile them") }
            merged?.let { Group(Modifier.padding(top = 8.dp)) { Md(it) } }
        } }
    }
}

// ─────────────────────────────── Confidence answers ───────────────────────────────
@Composable
fun ConfidentScreen(container: AppContainer) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var q by remember { mutableStateOf("") }
    var bar by remember { mutableFloatStateOf(70f) }
    var answer by remember { mutableStateOf<String?>(null) }
    var conf by remember { mutableIntStateOf(-1) }
    var why by remember { mutableStateOf("") }
    var checked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    suspend fun ask(web: Boolean) {
        val raw = Ai.generate(container.settingsRepository, q, "Answer the question. Then judge honestly how sure you are. Return ONLY JSON: " +
            "{\"answer\":\"markdown answer\",\"confidence\":0-100,\"why\":\"one line on what makes you sure or unsure\"}", 1200, useWeb = web)
        val o = runCatching { JSONObject(Ai.cleanJson(raw)) }.getOrElse { JSONObject().put("answer", raw).put("confidence", 50).put("why", "Couldn't rate itself") }
        answer = o.optString("answer"); conf = o.optInt("confidence", 50); why = o.optString("why")
    }
    AtlasScreen(title = "Confidence answers", subtitle = "Unsure answers get checked on the web", hub = Hub.Memory) {
        item("in") { Column(Modifier.padding(horizontal = Space.page)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Ask anything") }, minLines = 2)
            Text("Check the web when under ${bar.toInt()}% sure", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
            Slider(bar, { bar = it }, valueRange = 40f..95f)
            Button(onClick = { busy = true; checked = false; answer = null; scope.launch {
                runCatching { ask(false); if (conf in 0 until bar.toInt()) { checked = true; ask(true) } }.onFailure { answer = Feedback.friendly(it) }
                busy = false } }, enabled = q.isNotBlank() && !busy) { Text(if (busy) (if (checked) "Checking the web…" else "Answering…") else "Ask") }
        } }
        answer?.let { a -> item("a") { Group(Modifier.padding(horizontal = Space.page, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val col = if (conf >= 80) Color(0xFF16A34A) else if (conf >= 55) Color(0xFFF59E0B) else MaterialTheme.colorScheme.error
                Box(Modifier.size(54.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawArc(col.copy(alpha = 0.18f), 0f, 360f, false, style = Stroke(6.dp.toPx()))
                        drawArc(col, -90f, 3.6f * conf.coerceIn(0, 100), false, style = Stroke(6.dp.toPx()))
                    }
                    Text("$conf", fontWeight = FontWeight.Bold, color = col)
                }
                Column(Modifier.padding(start = 12.dp)) {
                    Text(if (checked) "Checked on the web" else "From what Atlas knows", style = MaterialTheme.typography.labelLarge)
                    Text(why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp)); Md(a)
        } } }
    }
}

// ─────────────────────────────── Instant ask (speculative) ───────────────────────────────
@Composable
fun QuickAskScreen(container: AppContainer) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var q by remember { mutableStateOf("") }
    val ready = remember { mutableStateMapOf<String, String>() }
    var shown by remember { mutableStateOf<Pair<String, Boolean>?>(null) }
    var busy by remember { mutableStateOf(false) }
    // start answering a second after you stop typing; if you send the same words, the answer is already there
    LaunchedEffect(q) { com.nizam.app.ui.design.guard {
        val t = q.trim(); if (t.length < 12 || ready.containsKey(t)) return@LaunchedEffect
        delay(900)
        runCatching { Ai.generate(container.settingsRepository, t, maxOutputTokens = 900) }.onSuccess { ready[t] = it }
    } }
    AtlasScreen(title = "Instant ask", subtitle = "Starts answering while you type", hub = Hub.Memory) {
        item("in") { Column(Modifier.padding(horizontal = Space.page)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Type a question and pause…") }, minLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Button(onClick = {
                    val t = q.trim(); val hit = ready[t]
                    if (hit != null) shown = hit to true else { busy = true; scope.launch {
                        shown = runCatching { Ai.generate(container.settingsRepository, t, maxOutputTokens = 900) }.getOrElse { Feedback.friendly(it) } to false; busy = false } }
                }, enabled = q.isNotBlank() && !busy) { Text(if (busy) "Answering…" else "Ask") }
                if (ready.containsKey(q.trim())) Text("  ⚡ answer ready", color = Color(0xFF16A34A), style = MaterialTheme.typography.labelLarge)
            }
        } }
        shown?.let { (a, instant) -> item("a") { Group(Modifier.padding(horizontal = Space.page, vertical = 10.dp)) {
            if (instant) Text("⚡ Ready before you pressed Ask", style = MaterialTheme.typography.labelMedium, color = Color(0xFF16A34A))
            Md(a)
        } } }
    }
}

// ─────────────────────────────── Knowledge graph ───────────────────────────────
private data class GNode(val id: String, val type: String, var x: Float, var y: Float)
private data class GEdge(val a: String, val b: String, val rel: String)

private fun layout(nodes: List<GNode>, edges: List<GEdge>) {
    val r = java.util.Random(4)
    nodes.forEach { it.x = r.nextFloat() * 800f; it.y = r.nextFloat() * 800f }
    val byId = nodes.associateBy { it.id }
    repeat(220) {
        for (i in nodes.indices) for (j in i + 1 until nodes.size) {
            val a = nodes[i]; val b = nodes[j]
            val dx = a.x - b.x; val dy = a.y - b.y; val d2 = max(dx * dx + dy * dy, 25f); val f = 5200f / d2; val d = sqrt(d2)
            a.x += dx / d * f; a.y += dy / d * f; b.x -= dx / d * f; b.y -= dy / d * f
        }
        edges.forEach { e -> val a = byId[e.a] ?: return@forEach; val b = byId[e.b] ?: return@forEach
            val dx = b.x - a.x; val dy = b.y - a.y; val d = max(sqrt(dx * dx + dy * dy), 1f); val f = (d - 120f) * 0.02f
            a.x += dx / d * f; a.y += dy / d * f; b.x -= dx / d * f; b.y -= dy / d * f }
        nodes.forEach { it.x += (400f - it.x) * 0.01f; it.y += (400f - it.y) * 0.01f }
    }
}

@Composable
fun GraphScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val file = remember { File(c.filesDir, "kgraph.json") }
    var nodes by remember { mutableStateOf<List<GNode>>(emptyList()) }
    var edges by remember { mutableStateOf<List<GEdge>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var sel by remember { mutableStateOf<String?>(null) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    fun load(o: JSONObject) {
        val n = o.optJSONArray("nodes") ?: JSONArray(); val e = o.optJSONArray("edges") ?: JSONArray()
        val ns = (0 until n.length()).map { n.getJSONObject(it) }.map { GNode(it.optString("id"), it.optString("type"), 0f, 0f) }.filter { it.id.isNotBlank() }.distinctBy { it.id }
        val ids = ns.map { it.id }.toSet()
        val es = (0 until e.length()).map { e.getJSONObject(it) }.map { GEdge(it.optString("a"), it.optString("b"), it.optString("rel")) }.filter { it.a in ids && it.b in ids }
        layout(ns, es); nodes = ns; edges = es
    }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { runCatching { load(JSONObject(file.readText())) } } }
    val colors = mapOf("person" to Color(0xFF6366F1), "place" to Color(0xFF10B981), "idea" to Color(0xFFF59E0B), "project" to Color(0xFFEC4899), "thing" to Color(0xFF0EA5E9))
    AtlasScreen(title = "Knowledge graph", subtitle = if (nodes.isEmpty()) "Built from your notes, diary, chats and memory" else "${nodes.size} things · ${edges.size} links", hub = Hub.Memory) {
        item("b") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { busy = true; scope.launch {
                runCatching {
                    val docs = com.nizam.app.feature.brain.Brain.corpus(container, c).takeLast(60)
                    val text = docs.joinToString("\n---\n") { "[${it.source}] ${it.title}: ${it.text.take(500)}" }.take(24000)
                    val raw = Ai.generate(container.settingsRepository, text, "Extract a knowledge graph of the most important people, places, projects, ideas and things " +
                        "in this person's life and how they connect. At most 45 nodes. Return ONLY JSON {\"nodes\":[{\"id\":\"short name\",\"type\":\"person|place|idea|project|thing\"}]," +
                        "\"edges\":[{\"a\":\"id\",\"b\":\"id\",\"rel\":\"2-4 words\"}]}", 3000)
                    val o = JSONObject(Ai.cleanJson(raw)); file.writeText(o.toString()); load(o)
                }.onFailure { Feedback.failed("Graph", it) }
                busy = false } }, enabled = !busy) { Text(if (busy) "Reading your life…" else if (nodes.isEmpty()) "Build my graph" else "Rebuild") }
        } }
        if (nodes.isNotEmpty()) item("g") {
            Box(Modifier.padding(Space.page).fillMaxWidth().height(460.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))) {
                val on = MaterialTheme.colorScheme.onSurface
                val labelPaint = remember { android.graphics.Paint().apply { isAntiAlias = true; textSize = 26f } }
                Canvas(Modifier.fillMaxSize().pointerInput(nodes) { detectDragGestures { ch, d -> ch.consume(); pan += d } }
                    .pointerInput(nodes, pan) { detectTapGestures { p ->
                        val s = size.width / 900f
                        sel = nodes.minByOrNull { n -> val dx = n.x * s + pan.x + 20f - p.x; val dy = n.y * s + pan.y + 20f - p.y; dx * dx + dy * dy }?.id } }) {
                    val s = size.width / 900f
                    fun pos(n: GNode) = Offset(n.x * s + pan.x + 20f, n.y * s + pan.y + 20f)
                    val byId = nodes.associateBy { it.id }
                    edges.forEach { e -> val a = byId[e.a] ?: return@forEach; val b = byId[e.b] ?: return@forEach
                        val hi = sel == e.a || sel == e.b
                        drawLine(on.copy(alpha = if (hi) 0.6f else 0.12f), pos(a), pos(b), strokeWidth = if (hi) 3f else 1.5f) }
                    nodes.forEach { n -> val col = colors[n.type] ?: Color.Gray
                        drawCircle(col.copy(alpha = if (sel == null || sel == n.id) 1f else 0.45f), if (sel == n.id) 16f else 10f, pos(n)) }
                    labelPaint.color = on.copy(alpha = 0.85f).toArgb()
                    nodes.forEach { n -> if (sel == null || sel == n.id || edges.any { (it.a == sel && it.b == n.id) || (it.b == sel && it.a == n.id) })
                        drawContext.canvas.nativeCanvas.drawText(n.id.take(18), pos(n).x + 14f, pos(n).y + 8f, labelPaint) }
                }
            }
        }
        sel?.let { s -> item("s") { Group(Modifier.padding(horizontal = Space.page)) {
            Text(s, style = MaterialTheme.typography.titleMedium)
            edges.filter { it.a == s || it.b == s }.forEach { e -> Text("• ${if (e.a == s) "${e.rel} → ${e.b}" else "${e.a} → ${e.rel}"}",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clickable { sel = if (e.a == s) e.b else e.a }.padding(vertical = 3.dp)) }
        } } }
        if (nodes.isNotEmpty()) item("k") { Row(Modifier.padding(Space.page), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            colors.forEach { (k, v) -> Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(10.dp).clip(CircleShape).background(v)); Text(" $k", fontSize = 11.sp) } }
        } }
    }
}

// ─────────────────────────────── Generative screens ───────────────────────────────
@Composable
fun GenScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val store = remember { com.nizam.app.feature.next.JsonStore(c, "genscreens") }
    var list by remember { mutableStateOf(store.all()) }
    var openId by remember { mutableStateOf<String?>(null) }
    var ask by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val cur = list.firstOrNull { it.optString("id") == openId }
    if (cur != null) { GenView(cur, onChange = { o -> store.save(list.map { if (it.optString("id") == o.optString("id")) o else it }); list = store.all() }, onBack = { openId = null },
        onDelete = { store.save(list.filter { it.optString("id") != openId }); list = store.all(); openId = null }); return }
    AtlasScreen(title = "Make a screen", subtitle = "Say what you want to track; Atlas builds the screen", hub = Hub.Create) {
        item("in") { Column(Modifier.padding(horizontal = Space.page)) {
            OutlinedTextField(ask, { ask = it }, Modifier.fillMaxWidth(), minLines = 2,
                placeholder = { Text("A Ramadan tracker: fasts, taraweeh, Quran pages, and a checklist for Eid shopping") })
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Gym log with sets", "Baby feeding tracker", "Wedding to-do", "Car service record").forEach { s -> Capsule(s, false) { ask = s } }
            }
            Button(onClick = { busy = true; scope.launch {
                runCatching {
                    val raw = Ai.generate(container.settingsRepository, ask, "Design a small phone screen as JSON. Return ONLY " +
                        "{\"title\":\"...\",\"emoji\":\"one emoji\",\"blocks\":[{\"type\":\"header|text|checklist|counter|input|progress\",\"label\":\"...\",\"items\":[\"...\"],\"target\":number}]}. " +
                        "checklist has items; counter and progress may have a target; input is a free-text field. 4–10 blocks, practical, no fluff.", 1500)
                    val o = JSONObject(Ai.cleanJson(raw)).put("id", System.currentTimeMillis().toString()).put("state", JSONObject())
                    store.add(o); list = store.all(); openId = o.optString("id"); ask = ""
                }.onFailure { Feedback.failed("Building", it) }
                busy = false } }, enabled = ask.isNotBlank() && !busy, modifier = Modifier.padding(top = 8.dp)) { Text(if (busy) "Building…" else "Build it") }
        } }
        if (list.isNotEmpty()) item("h") { SectionHeader("Your screens") }
        list.forEach { o -> item(o.optString("id")) { ListRow(o.optString("emoji") + "  " + o.optString("title"), supporting = "${o.optJSONArray("blocks")?.length() ?: 0} blocks",
            icon = Icons.Outlined.Dashboard) { openId = o.optString("id") } } }
    }
}

@Composable
private fun GenView(o: JSONObject, onChange: (JSONObject) -> Unit, onBack: () -> Unit, onDelete: () -> Unit) {
    var ver by remember { mutableIntStateOf(0) }
    val state = o.optJSONObject("state") ?: JSONObject().also { o.put("state", it) }
    fun save() { onChange(o); ver++ }
    val blocks = o.optJSONArray("blocks") ?: JSONArray()
    AtlasScreen(title = o.optString("emoji") + " " + o.optString("title"), hub = Hub.Create, actions = {
        Text("Delete", color = MaterialTheme.colorScheme.error, modifier = Modifier.clickable { onDelete() }.padding(8.dp))
        Text("Done", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { onBack() }.padding(8.dp)) }) {
        (0 until blocks.length()).forEach { i -> val b = blocks.getJSONObject(i); val key = "b$i"
            item("$key-$ver") { Column(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) {
                when (b.optString("type")) {
                    "header" -> Text(b.optString("label"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
                    "text" -> Text(b.optString("label"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    "checklist" -> Group {
                        Text(b.optString("label"), style = MaterialTheme.typography.titleSmall)
                        val items = b.optJSONArray("items") ?: JSONArray()
                        val done = state.optJSONObject(key) ?: JSONObject()
                        (0 until items.length()).forEach { j -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(done.optBoolean("$j"), { v -> state.put(key, done.put("$j", v)); save() })
                            Text(items.optString(j), style = MaterialTheme.typography.bodyMedium)
                        } }
                    }
                    "counter", "progress" -> Group {
                        val n = state.optInt(key); val target = b.optInt("target", 0)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(b.optString("label"), style = MaterialTheme.typography.titleSmall)
                                Text(if (target > 0) "$n / $target" else "$n", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            }
                            Capsule("−", false) { state.put(key, (n - 1).coerceAtLeast(0)); save() }
                            Spacer(Modifier.width(6.dp))
                            Capsule("+", true) { state.put(key, n + 1); save() }
                        }
                        if (target > 0) LinearProgressIndicator({ (n.toFloat() / target).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    else -> { var t by remember { mutableStateOf(state.optString(key)) }
                        OutlinedTextField(t, { t = it; state.put(key, it); onChange(o) }, Modifier.fillMaxWidth(), label = { Text(b.optString("label")) }) }
                }
            } }
        }
    }
}

// ─────────────────────────────── Atlas-to-Atlas ───────────────────────────────
object AtlasCard {
    const val PREFIX = "ATLAS1:"
    fun encode(o: JSONObject) = PREFIX + android.util.Base64.encodeToString(o.toString().toByteArray(), android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
    fun decode(s: String): JSONObject? = runCatching {
        val t = s.substringAfter(PREFIX, "").trim().takeWhile { !it.isWhitespace() }
        JSONObject(String(android.util.Base64.decode(t, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)))
    }.getOrNull()
}

@Composable
fun AtlasCardScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var kind by remember { mutableStateOf("Task") }
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    var from by remember { mutableStateOf(c.getSharedPreferences("atlascard", 0).getString("from", "").orEmpty()) }
    var made by remember { mutableStateOf<String?>(null) }
    var incoming by remember { mutableStateOf("") }
    val parsed = remember(incoming) { AtlasCard.decode(incoming) }
    AtlasScreen(title = "Atlas-to-Atlas", subtitle = "Cards another Atlas understands", hub = Hub.Social) {
        item("s") { SectionHeader("Send") }
        item("f") { Column(Modifier.padding(horizontal = Space.page)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Task", "Plan", "List", "Note", "Contact").forEach { k -> Capsule(k, k == kind) { kind = k } } }
            OutlinedTextField(from, { from = it; c.getSharedPreferences("atlascard", 0).edit().putString("from", it).apply() }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Your name") }, singleLine = true)
            OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text(when (kind) { "Plan" -> "What and when"; "List" -> "List name"; "Contact" -> "Name"; else -> "Title" }) }, singleLine = true)
            OutlinedTextField(body, { body = it }, Modifier.fillMaxWidth(), label = { Text(when (kind) { "List" -> "One item per line"; "Contact" -> "Number, notes"; else -> "Details" }) }, minLines = 3)
            Button(onClick = { made = AtlasCard.encode(JSONObject().put("v", 1).put("kind", kind).put("from", from).put("title", title).put("body", body).put("at", System.currentTimeMillis())) },
                enabled = title.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) { Text("Make card") }
        } }
        made?.let { m -> item("m") { Group(Modifier.padding(Space.page)) {
            val bmp = remember(m) { com.nizam.app.feature.next.Qr.bitmap(m, 640) }
            bmp?.let { Image(it.asImageBitmap(), "QR", Modifier.size(220.dp).align(Alignment.CenterHorizontally)) }
            Text("Scan with their Atlas, or send the text — any chat app works.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            OutlinedButton(onClick = { Share.text(c, "$kind from $from: $title\n\nOpen with Atlas → Atlas-to-Atlas:\n$m") }, modifier = Modifier.padding(top = 6.dp)) { Text("Share") }
        } } }
        item("r") { SectionHeader("Receive") }
        item("in") { Column(Modifier.padding(horizontal = Space.page)) {
            OutlinedTextField(incoming, { incoming = it }, Modifier.fillMaxWidth(), label = { Text("Paste a message with an ATLAS1: card") }, minLines = 2)
            OutlinedButton(onClick = { val cm = c.getSystemService(android.content.ClipboardManager::class.java)
                incoming = cm?.primaryClip?.getItemAt(0)?.coerceToText(c)?.toString().orEmpty() }, modifier = Modifier.padding(top = 6.dp)) { Text("Paste from clipboard") }
        } }
        parsed?.let { p -> item("p") { Group(Modifier.padding(Space.page)) {
            Text("${p.optString("kind")} from ${p.optString("from").ifBlank { "someone" }}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(p.optString("title"), style = MaterialTheme.typography.titleMedium)
            if (p.optString("body").isNotBlank()) Text(p.optString("body"), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { scope.launch { runCatching {
                when (p.optString("kind")) {
                    "Team" -> Feedback.show("${com.nizam.app.feature.agent.Crew.importTeam(c, p)} agents joined your team")
                    "Masjid" -> Feedback.show("${com.nizam.app.feature.wild.Masjids.merge(c, p)} masjid times updated")
                    "Task" -> container.taskRepository.add(p.optString("title"), null, 1)
                    "List" -> p.optString("body").lines().filter { it.isNotBlank() }.forEach { container.taskRepository.add(it.trim(), null, 0) }
                    else -> container.noteRepository.createFull("${p.optString("kind")}: ${p.optString("title")}", p.optString("body") + "\n\nFrom ${p.optString("from")}")
                }
                Feedback.show("Added to your Atlas"); incoming = ""
            }.onFailure { Feedback.failed("Adding", it) } } }, modifier = Modifier.padding(top = 8.dp)) {
                Text(when (p.optString("kind")) { "Team" -> "Add these agents"; "Masjid" -> "Update masjid times"; "Task" -> "Add task"; "List" -> "Add items as tasks"; else -> "Save to notes" }) }
        } } }
    }
}

// ─────────────────────────────── Zero-UI ───────────────────────────────
@Composable
fun ZeroUiScreen(container: AppContainer, open: (String) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black, cs.primary.copy(alpha = 0.25f).compositeOverBlack())))) {
        com.nizam.app.ui.design.MorphingBlob(Modifier.size(240.dp).align(Alignment.Center), listOf(cs.primary, cs.tertiary, cs.secondary),
            com.nizam.app.ui.design.Island.agentBusy != null)
        Column(Modifier.align(Alignment.TopCenter).padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Just talk.", color = Color.White, style = MaterialTheme.typography.headlineMedium)
            Text("Tap the mic. Atlas answers out loud.", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium)
        }
        com.nizam.app.feature.agent.VoiceCommandBar(container, open, Modifier.align(Alignment.BottomCenter).padding(16.dp).padding(bottom = 24.dp))
    }
}

private fun Color.compositeOverBlack(): Color = Color(red * alpha, green * alpha, blue * alpha, 1f)
