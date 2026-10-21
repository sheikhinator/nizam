package com.nizam.app.feature.agentic

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.brain2.PersonalModel
import com.nizam.app.feature.dash.Dash
import com.nizam.app.feature.next.Activity
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.Nx
import com.nizam.app.feature.nx.PlanEngine
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.platform2.PermLog
import com.nizam.app.feature.widget.MyWidget
import com.nizam.app.feature.wild.AgentMeter
import com.nizam.app.net.Ai
import com.nizam.app.net.Router
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.nav.Routes
import com.nizam.app.ui.screens.hubs.HubRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicInteger

// ───────────────────────── 1. The Halo: what the agent is doing, always visible ─────────────────────────

/** Tracks every AI action in flight so a small pill can say what Atlas is doing, and let you stop it. */
object AgentHalo {
    data class State(val active: Boolean = false, val label: String = "", val steps: List<String> = emptyList())
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state
    @Volatile var halted = false
    private val depth = AtomicInteger(0)

    fun begin(label: String) {
        if (depth.getAndIncrement() == 0) { halted = false; _state.value = State(true, label, listOf(label)); com.nizam.app.feature.expertise.Pulse.emit(com.nizam.app.feature.expertise.Pulse.Kind.WORKING, label) } else step(label)
    }
    fun step(text: String) { _state.update { it.copy(label = text, steps = (it.steps + text).takeLast(8)) } }
    fun end() { if (depth.updateAndGet { maxOf(0, it - 1) } == 0) { val was = _state.value.label; _state.value = State(); if (was.isNotBlank()) com.nizam.app.feature.expertise.Pulse.emit(com.nizam.app.feature.expertise.Pulse.Kind.DONE, "Done: $was") } }
    fun stop() { halted = true; step("Stopping…") }
    fun check() { if (halted) throw IllegalStateException("Stopped from the Atlas pill.") }
}

private fun prefs(c: Context) = c.getSharedPreferences("agentic", 0)

@Composable
fun HaloHost(modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val st by AgentHalo.state.collectAsState()
    var show by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(false) }
    val enabled = remember { prefs(c).getBoolean("halo", true) }
    LaunchedEffect(st.active) {
        if (st.active) { delay(900); show = enabled && AgentHalo.state.value.active } else { show = false; expanded = false }
    }
    val pulse = rememberInfiniteTransition(label = "halo").animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "dot")
    AnimatedVisibility(show, modifier.statusBarsPadding().padding(top = 6.dp),
        enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
        Column(Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xEE0F1419)).clickable { expanded = !expanded }
            .padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).alpha(pulse.value).clip(CircleShape).background(Color(0xFF4CC9F0)))
                Spacer(Modifier.width(8.dp))
                Text(st.label.ifBlank { "Atlas is working" }, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                Spacer(Modifier.width(14.dp))
                Text("Stop", color = Color(0xFFFF6B6B), style = MaterialTheme.typography.labelLarge, modifier = Modifier.clickable { AgentHalo.stop() })
            }
            if (expanded) st.steps.forEach { Text("• $it", color = Color(0xFFB8C2CC), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 3.dp)) }
        }
    }
}

// ───────────────────────── 2. Hold to confirm ─────────────────────────

/** Anything that spends, sends or deletes is confirmed by holding the button, so it never fires by a stray tap. */
@Composable
fun HoldToConfirm(label: String = "Hold to confirm", modifier: Modifier = Modifier, onConfirm: () -> Unit) {
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(0f) }
    val haptic = LocalHapticFeedback.current
    val shape = RoundedCornerShape(24.dp)
    val c = MaterialTheme.colorScheme
    Box(modifier.clip(shape).background(c.primaryContainer).semantics { onClick(label) { onConfirm(); true } }
        .pointerInput(Unit) {
            detectTapGestures(onPress = {
                val job = scope.launch {
                    progress.animateTo(1f, tween(900))
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onConfirm(); progress.snapTo(0f)
                }
                tryAwaitRelease(); job.cancel(); scope.launch { progress.snapTo(0f) }
            })
        }) {
        Box(Modifier.matchParentSize()) { Box(Modifier.fillMaxWidth(progress.value.coerceIn(0.001f, 1f)).fillMaxHeight().background(c.primary.copy(alpha = 0.35f))) }
        Text(label, Modifier.padding(horizontal = 22.dp, vertical = 12.dp), style = MaterialTheme.typography.labelLarge, color = c.onPrimaryContainer)
    }
}

// ───────────────────────── 3. Where a request may go: on-device, cloud, cheapest ─────────────────────────

object Hybrid {
    enum class Mode(val label: String, val blurb: String) {
        AUTO("Automatic", "Atlas picks: the phone's own model when offline, otherwise your best provider."),
        PREFER_LOCAL("Prefer on-device", "Try the model on this phone first, fall back to the cloud."),
        ONLY_LOCAL("Only on-device", "Nothing leaves the phone. Fails if no offline model is installed."),
        PREFER_CLOUD("Prefer cloud", "Use your online providers first, the phone's model last."),
        ONLY_CLOUD("Only cloud", "Never use the phone's model."),
        CHEAPEST("Cheapest first", "On-device, then free models, then paid ones.")
    }
    fun mode(c: Context?, tag: String?): Mode {
        c ?: return Mode.AUTO
        val p = prefs(c)
        val raw = (tag?.let { p.getString("hyb_$it", null) }) ?: p.getString("hyb_global", "AUTO")
        return runCatching { Mode.valueOf(raw ?: "AUTO") }.getOrDefault(Mode.AUTO)
    }
    fun set(c: Context, tag: String?, m: Mode?) = prefs(c).edit().apply { val k = "hyb_${tag ?: "global"}"; if (m == null) remove(k) else putString(k, m.name) }.apply()

    private fun local(e: Router.Endpoint) = e.kind == "LOCAL" || e.kind == "FOLAX"
    private fun cost(e: Router.Endpoint) = when {
        local(e) -> 0
        e.model.endsWith(":free") || e.model.contains("flash-lite") -> 1
        e.kind == "GEMINI" -> 2
        else -> 3
    }

    fun apply(list: List<Router.Endpoint>, mode: Mode): List<Router.Endpoint> = when (mode) {
        Mode.AUTO -> list
        Mode.PREFER_LOCAL -> list.sortedBy { if (local(it)) 0 else 1 }
        Mode.ONLY_LOCAL -> list.filter { local(it) }.ifEmpty { throw Ai.MissingKeyException("On-device only is on, but no offline model is installed. Install one in AI models, or change the routing.") }
        Mode.PREFER_CLOUD -> list.sortedBy { if (local(it)) 1 else 0 }
        Mode.ONLY_CLOUD -> list.filter { !local(it) }.ifEmpty { list }
        Mode.CHEAPEST -> list.sortedBy { cost(it) }
    }
}

// ───────────────────────── 4. Structured output: typed answers, retried once ─────────────────────────

object StructuredAi {
    /** Asks for a JSON object with exactly [keys]. Retries once with a stricter reminder; null if it still isn't valid. */
    suspend fun obj(container: AppContainer, instruction: String, keys: List<String>, system: String? = null, tokens: Int = 600): JSONObject? {
        for (attempt in 0..1) {
            val raw = Ai.generate(container.settingsRepository,
                instruction + "\nReply with ONLY a JSON object that has exactly these keys: ${keys.joinToString()}." +
                    if (attempt > 0) " Your previous reply was not valid JSON. Reply with JSON only, no prose, no code fences." else "",
                system, tokens)
            val o = runCatching { JSONObject(Ai.cleanJson(raw)) }.getOrNull()
            if (o != null && keys.all { o.has(it) }) return o
        }
        return null
    }
}

// ───────────────────────── 5. Memory sources: why did it say that? ─────────────────────────

object MemorySources {
    private fun store(c: Context) = JsonStore(c, "memsources")
    fun record(c: Context?, prompt: String) {
        c ?: return
        runCatching {
            val facts = PersonalModel.facts(c).takeLast(25); val lessons = PersonalModel.lessons(c).takeLast(12)
            if (facts.isEmpty() && lessons.isEmpty()) return
            val s = store(c)
            s.add(JSONObject().put("t", System.currentTimeMillis()).put("q", prompt.lineSequence().firstOrNull().orEmpty().take(70))
                .put("facts", JSONArray(facts)).put("lessons", JSONArray(lessons)))
            val all = s.all()
            if (all.size > 30) all.sortedBy { it.optLong("t") }.take(all.size - 30).forEach { s.remove(it.optString("id")) }
        }
    }
    fun recent(c: Context) = runCatching { store(c).all().sortedByDescending { it.optLong("t") } }.getOrDefault(emptyList())
}

// ───────────────────────── 6. Generative UI: answers as native cards ─────────────────────────

private fun safeRoute(r: String): String? = when {
    r.startsWith("nx/") && Nx.find(r.removePrefix("nx/")) != null -> r
    HubRegistry.byRoute(r) != null -> r
    else -> null
}

@Composable
internal fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
}

@Composable
internal fun GenUi(comps: JSONArray, open: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (i in 0 until comps.length()) {
            val o = comps.optJSONObject(i) ?: continue
            when (o.optString("type")) {
                "stat" -> Card {
                    Text(o.optString("label").uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(o.optString("value"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                }
                "text" -> Text(o.optString("text"), style = MaterialTheme.typography.bodyMedium)
                "list" -> Card {
                    o.optString("title").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                    val items = o.optJSONArray("items") ?: JSONArray()
                    for (j in 0 until items.length()) Text("• " + items.optString(j), style = MaterialTheme.typography.bodyMedium)
                }
                "bar" -> Card {
                    o.optString("title").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                    val items = o.optJSONArray("items") ?: JSONArray()
                    var hi = 1e-9
                    for (j in 0 until items.length()) hi = maxOf(hi, items.optJSONObject(j)?.optDouble("value", 0.0) ?: 0.0)
                    for (j in 0 until items.length()) {
                        val row = items.optJSONObject(j) ?: continue
                        val v = row.optDouble("value", 0.0)
                        Text("${row.optString("label")}  ${"%,.0f".format(v)}", style = MaterialTheme.typography.labelMedium)
                        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            Box(Modifier.fillMaxWidth((v / hi).toFloat().coerceIn(0.02f, 1f)).height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary))
                        }
                    }
                }
                "button" -> {
                    val r = safeRoute(o.optString("route"))
                    if (r != null) Button(onClick = { open(r) }) { Text(o.optString("label").ifBlank { "Open" }) }
                }
            }
        }
    }
}

@Composable
private fun GenUiScreen(container: AppContainer, open: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var ask by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var comps by remember { mutableStateOf<JSONArray?>(null) }
    var title by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Ask for a view of your day, money or tasks and Atlas builds it as cards, not paragraphs.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(ask, { ask = it }, label = { Text("e.g. How is my week going?") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = ask.isNotBlank() && !busy, onClick = {
            busy = true; err = ""
            scope.launch {
                val r = runCatching {
                    val d = Dash.load(container)
                    val spent7 = d.txns.filter { it.kind == "EXPENSE" && it.localDate >= LocalDate.now().minusDays(7).toString() }.sumOf { it.amount }
                    val ctx = "Open tasks: ${d.openTasks}. Spent in the last 7 days: Rs ${spent7.toLong()}. Streak: ${d.sum?.streak ?: 0} days."
                    StructuredAi.obj(container,
                        "The user asks: \"$ask\"\nTheir data: $ctx\nBuild a small native screen answering it.\n" +
                            "components is an array of objects. Allowed types: {\"type\":\"stat\",\"label\",\"value\"}, {\"type\":\"text\",\"text\"}, " +
                            "{\"type\":\"list\",\"title\",\"items\":[strings]}, {\"type\":\"bar\",\"title\",\"items\":[{\"label\",\"value\":number}]}, " +
                            "{\"type\":\"button\",\"label\",\"route\"} where route is one of nx/planengine, nx/dashboards, nx/moneydesk, nx/energyplan. Use at most 6 components. Never invent numbers beyond the data given.",
                        listOf("title", "components"), "You design compact, honest mobile screens.", 700)
                }.getOrNull()
                busy = false
                if (r == null) err = "Couldn't build that. Check your AI key or try different words." else { title = r.optString("title"); comps = r.optJSONArray("components") }
            }
        }) { Text(if (busy) "Building…" else "Build it") }
        if (err.isNotBlank()) Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        if (title.isNotBlank()) Text(title, style = MaterialTheme.typography.titleLarge)
        comps?.let { GenUi(it, open) }
    }
}

// ───────────────────────── 7. Energy-aware planner ─────────────────────────

object EnergyPlanner {
    data class Result(val headline: String, val slots: List<String>, val peakHours: List<Int>)
    private fun hm(m: Int) = "%02d:%02d".format(m / 60, m % 60)

    suspend fun compute(container: AppContainer): Result = withContext(Dispatchers.IO) {
        val c = container.appContext
        val zone = ZoneId.systemDefault()
        val cutoff = System.currentTimeMillis() - 30L * 86_400_000L
        val hist = IntArray(24)
        runCatching { container.taskRepository.all().first() }.getOrDefault(emptyList()).forEach { t ->
            val done = t.completedAt ?: 0L
            if (done > cutoff) hist[Instant.ofEpochMilli(done).atZone(zone).hour]++
        }
        val peaks = hist.withIndex().filter { it.value > 0 }.sortedByDescending { it.value }.take(3).map { it.index }.sorted()
        val sleep = runCatching { JsonStore(c, "nxlog_tempready").all().maxByOrNull { it.optString("d") }?.optString("sleep")?.toDoubleOrNull() }.getOrNull()
        val busy = runCatching { PlanEngine.prayers(container) + PlanEngine.events(c) }.getOrDefault(emptyList()).sortedBy { it.startMin }
        val now = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
        val start = maxOf(PlanEngine.dayStart(c), now); val end = PlanEngine.dayEnd(c)
        val gaps = ArrayList<Pair<Int, Int>>()
        var cur = start
        for (b in busy) { if (b.startMin > cur) gaps += cur to minOf(b.startMin, end); cur = maxOf(cur, b.endMin) }
        if (end > cur) gaps += cur to end
        val shortSleep = sleep != null && sleep < 6.0
        val ranked = gaps.filter { it.second - it.first >= 45 }.map { g ->
            val hours = (g.first / 60)..((g.second - 1) / 60)
            val score = hours.sumOf { h -> hist.getOrElse(h) { 0 } } * 100 / maxOf(1, hours.count())
            Triple(g, score, shortSleep && g.first < 10 * 60)
        }.sortedByDescending { it.second - (if (it.third) 50 else 0) }.take(3)
        val slots = ranked.map { (g, s, tired) ->
            "${hm(g.first)}–${hm(g.second)}  ·  " + when {
                tired -> "light tasks only (short sleep)"
                s > 0 -> "deep work: you finish most tasks around here"
                else -> "free block: good for one focused task"
            }
        }
        val head = buildString {
            if (peaks.isNotEmpty()) append("You complete most tasks around ${peaks.joinToString(", ") { hm(it * 60) }}. ") else append("Complete a few tasks and Atlas learns your best hours. ")
            if (sleep != null) append("Last logged sleep: ${"%.1f".format(sleep)} h. ")
            if (shortSleep) append("Short sleep, so hard work is pushed later.")
        }
        Result(head.trim(), slots, peaks)
    }
}

@Composable
private fun EnergyPlanScreen(container: AppContainer, open: (String) -> Unit) {
    var res by remember { mutableStateOf<EnergyPlanner.Result?>(null) }
    LaunchedEffect(Unit) { res = runCatching { EnergyPlanner.compute(container) }.getOrNull() }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val r = res
        if (r == null) Text("Reading your day…", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            Card { Text("Your rhythm", style = MaterialTheme.typography.titleSmall); Text(r.headline, style = MaterialTheme.typography.bodyMedium) }
            Text("Best blocks left today", style = MaterialTheme.typography.titleSmall)
            if (r.slots.isEmpty()) Text("No free block of 45 minutes or more is left around your prayers and events.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            r.slots.forEach { s -> Card(Modifier.clickable { open(Routes.TIMER) }) { Text(s, style = MaterialTheme.typography.bodyMedium); Text("Tap to start a focus timer", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) } }
            TextButton(onClick = { open("nx/planengine") }) { Text("Open the full day planner") }
        }
    }
}

// ───────────────────────── 8. Atlas tools for other agents (deep links + manifest) ─────────────────────────

object ToolServer {
    fun enabled(c: Context) = prefs(c).getBoolean("deeplinks", true)
    fun manifest(): String {
        val arr = JSONArray()
        Nx.all.forEach { arr.put(JSONObject().put("name", it.id).put("title", it.title).put("description", it.blurb).put("open", "atlas://open/nx/${it.id}")) }
        return JSONObject().put("app", "Atlas").put("kind", "open-screen tools").put("tools", arr).toString(2)
    }
    /** atlas://open/nx/<id> or atlas://open/hub/<Name> → an in-app route, only for screens that exist. */
    fun routeFrom(c: Context, i: Intent?): String? {
        val u = i?.data ?: return null
        if (u.scheme != "atlas" || u.host != "open" || !enabled(c)) return null
        val path = u.path.orEmpty().removePrefix("/")
        return when {
            path.startsWith("nx/") && Nx.find(path.removePrefix("nx/")) != null -> path
            path.startsWith("hub/") -> path
            else -> null
        }
    }
}

@Composable
private fun ToolServerScreen() {
    val c = LocalContext.current
    var on by remember { mutableStateOf(ToolServer.enabled(c)) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Let other assistants and apps open Atlas tools by link, for example from a note, a shortcut or another agent.", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Allow atlas://open links", Modifier.weight(1f)); Switch(on, { on = it; prefs(c).edit().putBoolean("deeplinks", it).apply() })
        }
        Card {
            Text("${Nx.all.size} tools available", style = MaterialTheme.typography.titleSmall)
            Text("Example: atlas://open/nx/planengine", style = MaterialTheme.typography.bodySmall)
            Text("Links only open screens. They never log, spend, send or delete anything by themselves.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Atlas tools", ToolServer.manifest()))
                com.nizam.app.ui.components.Feedback.show("Tool list copied")
            }) { Text("Copy tool list (JSON)") }
            TextButton(onClick = { com.nizam.app.feature.nx.Share.text(c, ToolServer.manifest(), "Atlas tools") }) { Text("Share") }
        }
        Text("Note: this is a link-based version. Android's own AppFunctions API is not wired in yet.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ───────────────────────── 9. Widget from a sentence ─────────────────────────

@Composable
private fun WidgetSentenceScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var said by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<JSONObject?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Describe the widget you want in one sentence.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(said, { said = it }, label = { Text("e.g. prayer times, today's spend and open tasks") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = said.isNotBlank() && !busy, onClick = {
            busy = true; msg = ""
            scope.launch {
                val keys = MyWidget.SOURCES.keys.joinToString()
                val o = runCatching { StructuredAi.obj(container, "Design a 4-line home-screen widget for: \"$said\". Each line must be one of: $keys (use none for an empty line). title is at most 12 characters.",
                    listOf("title", "l0", "l1", "l2", "l3"), tokens = 200) }.getOrNull()
                busy = false
                if (o == null) msg = "Couldn't understand that. Try naming things like prayer, spent, tasks, water."
                else plan = o
            }
        }) { Text(if (busy) "Designing…" else "Design it") }
        plan?.let { p ->
            Card {
                Text(p.optString("title").uppercase(), style = MaterialTheme.typography.labelLarge)
                (0..3).forEach { i -> Text(MyWidget.SOURCES[p.optString("l$i")] ?: "Nothing", style = MaterialTheme.typography.bodyMedium) }
            }
            Button(onClick = {
                MyWidget.setTitle(c, p.optString("title").ifBlank { "ATLAS" })
                (0..3).forEach { i -> MyWidget.setLine(c, i, p.optString("l$i").takeIf { it in MyWidget.SOURCES.keys } ?: "none") }
                scope.launch { runCatching { MyWidget.refresh(c) } }
                msg = "Saved. Long-press your home screen › Widgets › My Atlas to place it."
            }) { Text("Use this layout") }
        }
        if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
}

// ───────────────────────── 10. Memory sources screen ─────────────────────────

@Composable
private fun MemorySourcesScreen() {
    val c = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val facts = remember(tick) { PersonalModel.facts(c) }
    val recent = remember(tick) { MemorySources.recent(c) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Atlas adds what it knows about you to answers it writes for you. Here is exactly what, and you can make it forget any of it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("What Atlas remembers (${facts.size})", style = MaterialTheme.typography.titleSmall)
        if (facts.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
        facts.forEach { f -> Card { Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { PersonalModel.forget(c, f); tick++ }) { Text("Forget") }
        } } }
        Text("Recent answers and what they used", style = MaterialTheme.typography.titleSmall)
        if (recent.isEmpty()) Text("Ask Atlas something and the facts it used will be listed here.", style = MaterialTheme.typography.bodySmall)
        recent.take(12).forEach { r ->
            var open by remember { mutableStateOf(false) }
            Card(Modifier.clickable { open = !open }) {
                Text("“${r.optString("q")}”", style = MaterialTheme.typography.bodyMedium)
                val n = (r.optJSONArray("facts")?.length() ?: 0); val l = (r.optJSONArray("lessons")?.length() ?: 0)
                Text("Used $n facts and $l lessons about you" + if (open) "" else " (tap to see)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (open) {
                    val fa = r.optJSONArray("facts") ?: JSONArray(); val la = r.optJSONArray("lessons") ?: JSONArray()
                    for (i in 0 until fa.length()) Text("• " + fa.optString(i), style = MaterialTheme.typography.bodySmall)
                    for (i in 0 until la.length()) Text("↻ " + la.optString(i), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ───────────────────────── 11. Hybrid routing screen ─────────────────────────

@Composable
private fun HybridScreen() {
    val c = LocalContext.current
    var cur by remember { mutableStateOf(Hybrid.mode(c, null)) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Choose where Atlas sends AI requests. Individual agents can override this in Agent ledger.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Hybrid.Mode.values().forEach { m ->
            Card(Modifier.clickable { cur = m; Hybrid.set(c, null, m) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    if (cur == m) Text("✓ selected", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                }
                Text(m.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("Sensitive prompts still stay on the phone when Atlas detects them, whatever you pick here.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ───────────────────────── 12. Agent ledger: who did what, and how each may run ─────────────────────────

@Composable
private fun AgentLedgerScreen() {
    val c = LocalContext.current
    var halo by remember { mutableStateOf(prefs(c).getBoolean("halo", true)) }
    var barge by remember { mutableStateOf(prefs(c).getBoolean("barge_in", true)) }
    var tick by remember { mutableStateOf(0) }
    val names = remember(tick) { (AgentMeter.names(c) + "Atlas").sorted() }
    val acts = remember(tick) { Activity.all(c).sortedByDescending { it.optLong("t") }.take(25) }
    val perms = remember(tick) { PermLog.all(c).take(15) }
    val fmt = remember { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card {
            Text("Controls", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Show the live pill while Atlas works (restart to apply)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(halo, { halo = it; prefs(c).edit().putBoolean("halo", it).apply() }) }
            Text("Prompt compaction has saved about ${Compact.savedTokens(c)} tokens so far", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Talk over Atlas in live voice", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(barge, { barge = it; prefs(c).edit().putBoolean("barge_in", it).apply() }) }
        }
        Text("Agents", style = MaterialTheme.typography.titleSmall)
        names.forEach { n ->
            val (t, r) = AgentMeter.today(c, n)
            var m by remember(tick) { mutableStateOf(Hybrid.mode(c, n)) }
            Card {
                Text(n, style = MaterialTheme.typography.titleSmall)
                Text("Today: $r requests, about $t tokens · this week: about ${AgentMeter.week(c, n)} tokens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                var ro by remember(tick) { mutableStateOf(AgentTrust.readOnly(c, n)) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Read-only (may look, never act)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall); Switch(ro, { ro = it; AgentTrust.setReadOnly(c, n, it) }) }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Hybrid.Mode.values().forEach { mode -> FilterChip(selected = m == mode, onClick = { m = mode; Hybrid.set(c, n, mode) }, label = { Text(mode.label) }) }
                }
            }
        }
        Text("What agents did", style = MaterialTheme.typography.titleSmall)
        if (acts.isEmpty()) Text("Nothing logged yet.", style = MaterialTheme.typography.bodySmall)
        acts.forEach { a -> Text((if (a.optBoolean("ok")) "✓ " else "✕ ") + fmt.format(java.util.Date(a.optLong("t"))) + " · " + a.optString("a") + ": " + a.optString("s"), style = MaterialTheme.typography.bodySmall) }
        Text("Permissions touched", style = MaterialTheme.typography.titleSmall)
        if (perms.isEmpty()) Text("None recorded.", style = MaterialTheme.typography.bodySmall)
        perms.forEach { p -> Text(fmt.format(java.util.Date(p.optLong("t"))) + " · " + p.optString("kind") + ": " + p.optString("why"), style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { tick++ }) { Text("Refresh") }
    }
}

// ───────────────────────── registration ─────────────────────────

object AgenticSpecs {
    val all = listOf(
        ScreenSpec("genui", "Answer as cards", "Ask for a view and get native cards, charts and buttons instead of paragraphs", Icons.Outlined.AutoAwesome, Hub.Tools,
            "generative ui cards answer dashboard on demand") { c, open -> GenUiScreen(c, open) },
        ScreenSpec("energyplan", "Energy-aware planner", "Finds your best focus blocks from when you finish tasks, your sleep and your prayers", Icons.Outlined.Bolt, Hub.Work,
            "energy planner focus best time deep work sleep rhythm") { c, open -> EnergyPlanScreen(c, open) },
        ScreenSpec("toolserver", "Atlas tools for other agents", "Open any Atlas tool by link, or copy the full tool list for another assistant", Icons.Outlined.Extension, Hub.Tools,
            "mcp appfunctions deep link tools agents share manifest") { _, _ -> ToolServerScreen() },
        ScreenSpec("widgetsentence", "Widget from a sentence", "Say what you want on your home screen and Atlas lays the widget out", Icons.Outlined.Widgets, Hub.Tools,
            "create my widget sentence home screen design") { c, _ -> WidgetSentenceScreen(c) },
        ScreenSpec("memorysources", "Why this answer?", "See exactly which remembered facts shape Atlas's answers, and make it forget any", Icons.Outlined.Memory, Hub.Memory,
            "memory sources why answer remembered forget privacy facts") { _, _ -> MemorySourcesScreen() },
        ScreenSpec("hybridrouter", "AI routing", "On-device, cloud or cheapest first: choose where requests go", Icons.Outlined.Tune, Hub.Tools,
            "hybrid router on device cloud offline cheapest model routing") { _, _ -> HybridScreen() },
        ScreenSpec("agentledger", "Agent ledger", "Per-agent usage, actions, permissions touched, routing, and the live pill controls", Icons.Outlined.History, Hub.Tools,
            "agent ledger permissions activity halo pill barge in voice controls") { _, _ -> AgentLedgerScreen() },
    )
}
