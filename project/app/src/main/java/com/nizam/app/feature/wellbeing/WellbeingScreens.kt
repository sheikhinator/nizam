package com.nizam.app.feature.wellbeing

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import com.nizam.app.core.MultiChipRow
import com.nizam.app.feature.agent.Snapshot
import com.nizam.app.feature.council.TypingDots
import com.nizam.app.feature.council.UserBubble
import com.nizam.app.feature.insight.Days
import com.nizam.app.feature.insight.METRICS
import com.nizam.app.feature.mind.Values
import com.nizam.app.feature.mind.Wheel
import com.nizam.app.feature.phone.AtlasAccessibilityService
import com.nizam.app.feature.shield.Shield
import com.nizam.app.net.Ai
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) { content() } }
}

/** Gentle mode, shared: no weeds, no guilt nudges. */
object Gentle {
    fun on(c: Context) = c.getSharedPreferences("gentle", 0).getBoolean("on", false)
    fun set(c: Context, v: Boolean) = c.getSharedPreferences("gentle", 0).edit().putBoolean("on", v).apply()
}

/** Focus Mode for Atlas itself: show only the pillars you're working on. */
object FocusPillars {
    val ALL = listOf("Daily", "Body", "Mind", "Money", "Faith", "Tracked", "Tools")
    private fun p(c: Context) = c.getSharedPreferences("pillars", 0)
    fun on(c: Context) = p(c).getBoolean("on", false)
    fun chosen(c: Context): Set<String> = p(c).getStringSet("set", ALL.toSet()) ?: ALL.toSet()
    fun save(c: Context, on: Boolean, set: Set<String>) = p(c).edit().putBoolean("on", on).putStringSet("set", set).apply()
}

// ── Pause settings + gentle mode ──────────────────────────────────────────────
@Composable
fun PauseSettingsScreen() {
    val c = LocalContext.current
    val apps = remember { Shield.apps(c) }
    var chosen by remember { mutableStateOf(Pause.apps(c)) }
    var gentle by remember { mutableStateOf(Gentle.on(c)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Pause before scrolling", "One breath before the feeds — then you decide")
        if (AtlasAccessibilityService.instance == null) Text("Needs Atlas's accessibility service — turn it on in Me > Phone control.",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        val (opened, closed) = Pause.today(c)
        Card {
            Text("Today: $opened pauses · you walked away $closed times", style = MaterialTheme.typography.titleMedium)
            Text("When you open a chosen app, Atlas shows one slow breath first. Say yes and it's yours for 15 minutes.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(gentle, { gentle = it; Gentle.set(c, it) }); Spacer(Modifier.width(10.dp))
                Column {
                    Text("Gentle mode", style = MaterialTheme.typography.titleMedium)
                    Text("No weeds in your town, no guilt-tinged nudges. Showing up is enough.",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        var fadeOn by remember { mutableStateOf(c.getSharedPreferences("fade", 0).getBoolean("on", false)) }
        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(fadeOn, { fadeOn = it; c.getSharedPreferences("fade", 0).edit().putBoolean("on", it)
                    .putStringSet("apps", chosen).apply() }); Spacer(Modifier.width(10.dp))
                Column {
                    Text("Screen fade", style = MaterialTheme.typography.titleMedium)
                    Text("After 20 minutes in these apps, the screen slowly turns grey. Nothing's blocked — the colour just drains out.",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        com.nizam.app.ui.design.Label("Apps to pause")
        apps.forEach { (label, pkg) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(pkg in chosen, { on -> chosen = if (on) chosen + pkg else chosen - pkg; Pause.setApps(c, chosen)
                    c.getSharedPreferences("fade", 0).edit().putStringSet("apps", chosen).apply() })
                Text(label)
            }
        }
        Spacer(Modifier.height(60.dp))
    }
}

// ── Calm: breathing you can feel ──────────────────────────────────────────────
private fun vibrator(c: Context): Vibrator =
    if (Build.VERSION.SDK_INT >= 31) c.getSystemService(VibratorManager::class.java).defaultVibrator
    else @Suppress("DEPRECATION") c.getSystemService(Vibrator::class.java)

@Composable
fun CalmScreen() {
    val c = LocalContext.current
    var running by remember { mutableStateOf(false) }
    var phase by remember { mutableStateOf("Tap start, then close your eyes") }
    val scale = remember { Animatable(0.4f) }
    DisposableEffect(Unit) { onDispose { vibrator(c).cancel() } }
    LaunchedEffect(running) { com.nizam.app.ui.design.guard {
        if (!running) { vibrator(c).cancel(); return@LaunchedEffect }
        val v = vibrator(c); val amp = v.hasAmplitudeControl()
        while (running) {
            phase = "Breathe in"
            // a swelling buzz you can follow with your eyes closed
            v.vibrate(if (amp) VibrationEffect.createWaveform(LongArray(8) { 500 }, IntArray(8) { 20 + it * 25 }, -1)
                else VibrationEffect.createOneShot(250, VibrationEffect.DEFAULT_AMPLITUDE))
            scale.animateTo(1f, tween(4000, easing = LinearEasing))
            phase = "Hold"; delay(4000)
            phase = "Breathe out"
            v.vibrate(if (amp) VibrationEffect.createWaveform(LongArray(12) { 500 }, IntArray(12) { 200 - it * 16 }, -1)
                else VibrationEffect.createWaveform(longArrayOf(0, 120, 400, 120), -1))
            scale.animateTo(0.4f, tween(6000, easing = LinearEasing))
            phase = "Rest"; delay(2000)
        }
    } }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("Calm", style = MaterialTheme.typography.headlineMedium)
        Text("In for 4 · hold 4 · out for 6. Follow the vibration.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(30.dp))
        val col = MaterialTheme.colorScheme.primary
        Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(col.copy(alpha = 0.15f), size.minDimension / 2 * scale.value)
                drawCircle(col, size.minDimension / 2 * scale.value * 0.55f)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(phase, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(24.dp))
        Button(onClick = { running = !running; if (!running) phase = "Well done." }) { Text(if (running) "Stop" else "Start") }
    }
}

// ── Future You ────────────────────────────────────────────────────────────────
@Composable
fun FutureYouScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var path by remember { mutableStateOf("If nothing changes") }
    val turns = remember { mutableStateListOf<Pair<Boolean, String>>() }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val year = LocalDate.now().year + 5

    suspend fun context(): String {
        val days = Days.build(container, c, 60)
        val trends = METRICS.keys.joinToString("; ") { k ->
            val first = days.take(30).mapNotNull { it.values[k] }; val last = days.takeLast(30).mapNotNull { it.values[k] }
            if (first.isEmpty() && last.isEmpty()) "" else "${METRICS[k]}: ${"%.1f".format(first.average().takeIf { !it.isNaN() } ?: 0.0)} > " +
                "${"%.1f".format(last.average().takeIf { !it.isNaN() } ?: 0.0)} (daily avg, previous 30d > last 30d)"
        }.split("; ").filter { it.isNotBlank() }.joinToString("; ")
        val wheel = Wheel.all(c)[YearMonth.now().toString()] ?: Wheel.all(c).values.lastOrNull()
        val weakest = wheel?.minByOrNull { it.value }?.key
        return "${Snapshot.of(container)}\nTrends: $trends\n${Values.forAi(c)}\nWeakest life area: ${weakest ?: "unknown"}"
    }

    fun send(text: String) {
        if (text.isBlank() || busy) return
        turns += true to text; draft = ""
        scope.launch {
            busy = true
            val reply = runCatching {
                Ai.generate(container.settingsRepository,
                    context() + "\n\nConversation:\n" + turns.takeLast(10).joinToString("\n") { (u, t) -> (if (u) "Present me: " else "Future me: ") + t },
                    system = "You are the user themself, in $year — five years from now. " +
                        (if (path == "If nothing changes") "You are where their CURRENT trends lead if nothing changes: extrapolate honestly from the numbers, good and bad. "
                        else "You are who they became after fixing their weakest area and holding their best habits: speak from that earned position, including what it cost. ") +
                        "Speak in first person as them, warmly and specifically, referencing real numbers and habits from their data. " +
                        "Be honest, never cruel, never generic. 2-5 sentences. You are a reflection tool, not a prophecy — if asked, say so.",
                    maxOutputTokens = 600)
            }.getOrElse { "(I couldn't reach you just now: ${com.nizam.app.ui.components.Feedback.friendly(it)})" }
            turns += false to reply; busy = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 18.dp)) {
        ModuleTitle("Future You", "Talk to yourself in $year — built from your real trajectory")
        ChipRow(listOf("If nothing changes", "If I fix my weakest area"), path, { path = it; turns.clear() })
        LazyColumn(Modifier.weight(1f)) {
            if (turns.isEmpty()) item {
                Column(Modifier.padding(vertical = 12.dp)) {
                    listOf("What does a normal Tuesday look like for you?", "What do you wish I'd started sooner?",
                        "Are you proud of me?", "What almost went wrong?").forEach { q ->
                        Text("✦ $q", color = MaterialTheme.colorScheme.primary, modifier = Modifier
                            .clickable(role = Role.Button) { send(q) }.padding(vertical = 8.dp))
                    }
                }
            }
            items(turns) { (user, text) ->
                if (user) UserBubble(text) else Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("You, $year", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        Text(text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
            if (busy) item { Box(Modifier.padding(12.dp)) { TypingDots(MaterialTheme.colorScheme.primary) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
            OutlinedTextField(draft, { draft = it }, label = { Text("Ask your future self…") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(enabled = !busy, onClick = { send(draft) }) { Text("Ask") }
        }
    }
}

// ── Thought reframe (CBT self-help) ───────────────────────────────────────────
private val TRAPS = listOf("All-or-nothing", "Catastrophising", "Mind-reading", "Fortune-telling", "Should statements",
    "Labelling", "Overgeneralising", "Discounting the positive", "Emotional reasoning", "Personalising")

@Composable
fun ReframeScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var situation by remember { mutableStateOf("") }
    var thought by remember { mutableStateOf("") }
    var feeling by remember { mutableStateOf("") }
    var before by remember { mutableStateOf(7f) }
    var traps by remember { mutableStateOf(setOf<String>()) }
    var forIt by remember { mutableStateOf("") }
    var against by remember { mutableStateOf("") }
    var balanced by remember { mutableStateOf("") }
    var after by remember { mutableStateOf(5f) }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf<String?>(null) }
    val file = remember { File(c.filesDir, "reframes.json") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Thought reframe", "Catch a thought, check it, find a fairer version")
        Text("A self-help exercise from CBT — useful for everyday worries, not a substitute for professional support.",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(situation, { situation = it }, label = { Text("1 · What happened?") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(thought, { thought = it }, label = { Text("2 · What went through your mind?") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(feeling, { feeling = it }, label = { Text("3 · What did you feel?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Text("How strong, 0–10: ${before.toInt()}", style = MaterialTheme.typography.labelMedium)
        Slider(before, { before = it }, valueRange = 0f..10f, steps = 9)
        Text("4 · Any thinking traps?", style = MaterialTheme.typography.labelMedium)
        MultiChipRow(TRAPS, traps, { t -> traps = if (t in traps) traps - t else traps + t })
        OutlinedTextField(forIt, { forIt = it }, label = { Text("5 · Evidence the thought is true") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(against, { against = it }, label = { Text("6 · Evidence it isn't the whole story") }, modifier = Modifier.fillMaxWidth())
        OutlinedButton(enabled = thought.isNotBlank() && !busy, onClick = {
            scope.launch {
                busy = true
                balanced = runCatching {
                    Ai.generate(container.settingsRepository,
                        "Situation: $situation\nThought: $thought\nFeeling: $feeling (${before.toInt()}/10)\nTraps noticed: ${traps.joinToString()}\n" +
                            "Evidence for: $forIt\nEvidence against: $against\n\nOffer ONE balanced alternative thought in their voice — " +
                            "realistic, not falsely positive, acknowledging what's true. 1-3 sentences, nothing else.",
                        system = "You help with a CBT thought record. Warm, grounded, brief. If the person expresses thoughts of " +
                            "harming themselves, don't reframe — gently encourage them to reach someone they trust or a crisis helpline now.",
                        maxOutputTokens = 250).trim()
                }.getOrElse { balanced }
                busy = false
            }
        }) { Text(if (busy) "Thinking…" else "Help me find a balanced thought") }
        OutlinedTextField(balanced, { balanced = it }, label = { Text("7 · A more balanced thought") }, modifier = Modifier.fillMaxWidth())
        Text("How strong is the feeling now? ${after.toInt()}", style = MaterialTheme.typography.labelMedium)
        Slider(after, { after = it }, valueRange = 0f..10f, steps = 9)
        Button(enabled = thought.isNotBlank(), onClick = {
            val a = runCatching { JSONArray(file.readText()) }.getOrElse { JSONArray() }
            a.put(JSONObject().put("date", LocalDate.now().toString()).put("thought", thought).put("balanced", balanced)
                .put("before", before.toInt()).put("after", after.toInt()).put("traps", JSONArray(traps.toList())))
            file.writeText(a.toString())
            saved = "Saved. ${before.toInt()} > ${after.toInt()}" + if (after < before) " — that's real movement." else "."
            situation = ""; thought = ""; feeling = ""; traps = emptySet(); forIt = ""; against = ""; balanced = ""
        }) { Text("Save") }
        saved?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        val past = remember(saved) { runCatching { JSONArray(file.readText()) }.getOrElse { JSONArray() } }
        if (past.length() > 0) {
            val trapCounts = (0 until past.length()).flatMap { i -> past.getJSONObject(i).optJSONArray("traps")?.let { t -> (0 until t.length()).map { t.optString(it) } } ?: emptyList() }
                .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3)
            Card {
                Text("${past.length()} reframes saved", style = MaterialTheme.typography.titleMedium)
                if (trapCounts.isNotEmpty()) Text("Your most common traps: " + trapCounts.joinToString { "${it.key} (${it.value})" },
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(60.dp))
    }
}
