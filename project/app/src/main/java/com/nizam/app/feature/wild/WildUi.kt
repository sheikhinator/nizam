package com.nizam.app.feature.wild

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
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
import com.nizam.app.ui.design.rememberTilt
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.cos
import kotlin.math.sin

// ─────────────────────────────── Haptic language ───────────────────────────────
/** A vibration for each kind of news, so you know what happened without looking. */
object Haptics {
    val KINDS = linkedMapOf(
        "prayer" to ("Prayer" to longArrayOf(0, 400, 150, 120, 150, 400)),
        "money" to ("Money" to longArrayOf(0, 60, 80, 60)),
        "family" to ("Family" to longArrayOf(0, 90, 120, 90, 500, 90, 120, 90)),
        "urgent" to ("Urgent" to longArrayOf(0, 70, 50, 70, 50, 70, 50, 70, 50, 70)),
        "done" to ("Done" to longArrayOf(0, 35)),
        "agent" to ("Atlas needs you" to longArrayOf(0, 200, 100, 60, 100, 60)))
    private fun p(c: Context) = c.getSharedPreferences("haptics", 0)
    fun on(c: Context) = p(c).getBoolean("on", true)
    fun setOn(c: Context, v: Boolean) = p(c).edit().putBoolean("on", v).apply()

    private fun vibrator(c: Context): Vibrator? = if (Build.VERSION.SDK_INT >= 31) c.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") c.getSystemService(Vibrator::class.java)

    fun play(c: Context, kind: String, force: Boolean = false) {
        if (!force && !on(c)) return
        val pattern = KINDS[kind]?.second ?: return
        runCatching { vibrator(c)?.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
    }

    /** Picks the pattern for a notification from its words. */
    fun forNotification(c: Context, title: String, body: String) {
        val t = (title + " " + body).lowercase()
        val kind = when {
            listOf("fajr", "dhuhr", "asr", "maghrib", "isha", "prayer", "jummah", "namaz").any { it in t } -> "prayer"
            listOf("sos", "urgent", "scam", "crash", "fire", "emergency", "blocked").any { it in t } -> "urgent"
            listOf("approve", "needs your ok", "question", "waiting on you").any { it in t } -> "agent"
            listOf("rs ", "pkr", "spent", "salary", "bill", "debited", "credited", "payment").any { it in t } -> "money"
            listOf("ammi", "abbu", "mama", "papa", "family", "wife", "husband", "bhai", "baji").any { it in t } -> "family"
            listOf("done", "finished", "✅", "saved").any { it in t } -> "done"
            else -> null
        }
        kind?.let { play(c, it) }
    }
}

// ─────────────────────────────── Focus lens ───────────────────────────────
/** One thing at a time: everything else dims, the thing you're doing stays lit. */
object FocusLens {
    var on by mutableStateOf(false)
    var what by mutableStateOf("")
    var until by mutableLongStateOf(0L)
    fun start(task: String, minutes: Int) { what = task; until = System.currentTimeMillis() + minutes * 60_000L; on = true }
    fun stop() { on = false }
}

@Composable
fun FocusLensOverlay() {
    if (!FocusLens.on) return
    var left by remember { mutableLongStateOf(FocusLens.until - System.currentTimeMillis()) }
    LaunchedEffect(FocusLens.until) { while (true) { left = FocusLens.until - System.currentTimeMillis(); if (left <= 0) { FocusLens.stop(); break }; delay(1000) } }
    val pulse = rememberInfiniteTransition(label = "lens").animateFloat(0.55f, 0.72f, infiniteRepeatable(tween(3000), RepeatMode.Reverse), label = "p").value
    Box(Modifier.fillMaxSize()) {
        // a soft vignette that dims the edges of everything; touches pass straight through
        Canvas(Modifier.fillMaxSize()) {
            drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = pulse)), center = Offset(size.width / 2, size.height * 0.42f),
                radius = size.maxDimension * 0.62f))
        }
        Row(Modifier.align(Alignment.TopCenter).padding(top = 36.dp).clip(RoundedCornerShape(50))
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("◉ NOW  ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            Text(FocusLens.what.take(40), color = Color.White, fontSize = 14.sp, maxLines = 1)
            Text("  %d:%02d".format(left / 60000, (left / 1000) % 60), color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
            Text("  ✕", color = Color.White, modifier = Modifier.clickable { FocusLens.stop() })
        }
    }
}

// ─────────────────────────────── Holographic card ───────────────────────────────
/** A foil trading-card surface: tilts with the phone, a rainbow sheen and glare slide across it. */
@Composable
fun HoloCard(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val (tx, ty) = rememberTilt()
    val drift = rememberInfiniteTransition(label = "holo").animateFloat(0f, 1f, infiniteRepeatable(tween(6000), RepeatMode.Reverse), label = "d").value
    Box(modifier.graphicsLayer {
        rotationY = tx * 14f; rotationX = -ty * 10f; cameraDistance = 14f * density
        compositingStrategy = CompositingStrategy.Offscreen
    }.clip(RoundedCornerShape(26.dp)).drawWithContent {
        drawContent()
        val shift = (tx + 1f) / 2f * 0.6f + drift * 0.4f
        drawRect(Brush.linearGradient(listOf(Color(0x55FF0080), Color(0x55FFD000), Color(0x5500FFB0), Color(0x5500A0FF), Color(0x55B000FF), Color(0x55FF0080)),
            start = Offset(size.width * (shift - 0.5f), 0f), end = Offset(size.width * (shift + 1f), size.height)), blendMode = BlendMode.Overlay)
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.38f), Color.Transparent),
            center = Offset(size.width * (0.5f + tx * 0.45f), size.height * (0.4f - ty * 0.45f)), radius = size.minDimension * 0.7f),
            center = Offset(size.width * (0.5f + tx * 0.45f), size.height * (0.4f - ty * 0.45f)), radius = size.minDimension * 0.7f, blendMode = BlendMode.Screen)
    }.border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = 0.7f), Color.White.copy(alpha = 0.1f))), RoundedCornerShape(26.dp)), content = content)
}

object WildUi {
    val all: List<Spec> = listOf(
        ScreenSpec("canvas", "Atlas Canvas", "Your whole life on one zoomable space", Icons.Outlined.Dashboard, Hub.Tools, "canvas zoom map all features overview space") { _, o -> CanvasScreen(o) },
        ScreenSpec("timemachine", "Time machine", "Scrub back to any day: money, tasks, notes, moods", Icons.Outlined.History, Hub.Memory, "time machine rewind past day history scrub") { c, _ -> TimeMachineScreen(c) },
        ScreenSpec("haptics", "Haptic language", "A vibration for each kind of news", Icons.Outlined.Vibration, Hub.Tools, "vibration haptic pattern feel notification") { _, _ -> HapticsScreen() },
        ScreenSpec("focuslens", "Focus lens", "Dim everything except what you're doing", Icons.Outlined.CenterFocusStrong, Hub.Work, "focus lens concentrate one thing dim") { _, _ -> FocusLensScreen() },
        ScreenSpec("atlasid", "Atlas ID", "Your holographic card: level, streaks, badges", Icons.Outlined.Badge, Hub.Memory, "id card holographic profile level badges") { c, _ -> AtlasIdScreen(c) },
        ScreenSpec("talk", "Conversation mode", "Just talk — interrupt any time", Icons.Outlined.RecordVoiceOver, Hub.Tools, "talk conversation voice mode call chat speak") { c, o -> TalkScreen(c, o) },
        ScreenSpec("drawcmd", "Draw to command", "Swipe from the edge, draw a shape, Atlas acts", Icons.Outlined.Gesture, Hub.Tools, "draw gesture shape command edge swipe shortcut") { c, _ -> DrawCommandScreen(c) },
        ScreenSpec("livewall", "Living wallpaper", "Your day painted on the home screen", Icons.Outlined.Wallpaper, Hub.Create, "wallpaper live home screen prayer arc sky pet") { _, _ -> LiveWallpaperScreen() },
    )
}

@Composable
fun HapticsScreen() {
    val c = LocalContext.current
    var on by remember { mutableStateOf(Haptics.on(c)) }
    AtlasScreen(title = "Haptic language", subtitle = "Feel what happened without looking", hub = Hub.Tools) {
        item("t") { Row(Modifier.padding(horizontal = Space.page), verticalAlignment = Alignment.CenterVertically) {
            Switch(on, { on = it; Haptics.setOn(c, it) }); Text("  Use patterns for Atlas notifications") } }
        Haptics.KINDS.forEach { (k, v) -> item(k) { ListRow(v.first, supporting = "Tap to feel it", icon = Icons.Outlined.Vibration) { Haptics.play(c, k, force = true) } } }
    }
}

@Composable
fun FocusLensScreen() {
    var what by remember { mutableStateOf("") }
    var mins by remember { mutableFloatStateOf(25f) }
    AtlasScreen(title = "Focus lens", subtitle = "Everything else fades while you work", hub = Hub.Work) {
        item("a") { Column(Modifier.padding(horizontal = Space.page)) {
            Art3D("hourglass_done", 90.dp)
            OutlinedTextField(what, { what = it }, Modifier.fillMaxWidth(), label = { Text("The one thing") }, singleLine = true)
            Text("${mins.toInt()} minutes", modifier = Modifier.padding(top = 8.dp)); Slider(mins, { mins = it }, valueRange = 5f..120f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { FocusLens.start(what.ifBlank { "Deep work" }, mins.toInt()); Feedback.show("Lens on") }) { Text("Start") }
                if (FocusLens.on) OutlinedButton(onClick = { FocusLens.stop() }) { Text("End") }
            }
        } }
    }
}

// ─────────────────────────────── Atlas ID (holographic) ───────────────────────────────
@Composable
fun AtlasIdScreen(container: AppContainer) {
    val c = LocalContext.current
    val x by androidx.compose.runtime.produceState<com.nizam.app.feature.progress.ProgressSummary?>(null) { value = runCatching { com.nizam.app.feature.progress.Xp.summarise(container) }.getOrNull() }
    val name by container.settingsRepository.displayName.collectAsState(initial = "")
    AtlasScreen(title = "Atlas ID", subtitle = "Tilt your phone", hub = Hub.Memory) {
        item("card") { HoloCard(Modifier.padding(Space.page).fillMaxWidth().aspectRatio(1.586f)) {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFF1E1B4B), Color(0xFF312E81), Color(0xFF0F766E)))).padding(20.dp)) {
                Column {
                    Text("ATLAS · MEMBER", color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, letterSpacing = 2.sp)
                    Text(name.ifBlank { "You" }, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
                    Text("Level ${x?.level ?: 1} · ${x?.streak ?: 0}-day streak", color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp)
                }
                Art3D("glowing_star", 72.dp, Modifier.align(Alignment.TopEnd))
                Text("Since ${LocalDate.now().year}", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomStart))
                Text("✦ ${com.nizam.app.feature.nx.Nx.all.size} powers", color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp, modifier = Modifier.align(Alignment.BottomEnd))
            }
        } }
        item("n") { Text("The card shifts colour as you tilt it — like a foil trading card.", modifier = Modifier.padding(horizontal = Space.page), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// ─────────────────────────────── Atlas Canvas ───────────────────────────────
@Composable
fun CanvasScreen(open: (String) -> Unit) {
    val features = remember { com.nizam.app.ui.screens.hubs.HubRegistry.all.distinctBy { it.route } }
    val hubs = remember { Hub.entries.filter { h -> features.any { it.hub == h } } }
    var scale by remember { mutableFloatStateOf(0.35f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    // hubs on a big ring; each hub's tools orbit it
    val ring = 1100f
    val place = remember {
        val m = HashMap<String, Offset>()
        hubs.forEachIndexed { i, h ->
            val a = i * 2 * Math.PI / hubs.size
            val hc = Offset((ring * cos(a)).toFloat(), (ring * sin(a)).toFloat()); m["hub:" + h.name] = hc
            features.filter { it.hub == h }.forEachIndexed { j, f ->
                val r = 230f + (j / 12) * 150f; val b = (j % 12) * 2 * Math.PI / 12 + (j / 12) * 0.26
                m[f.route] = hc + Offset((r * cos(b)).toFloat(), (r * sin(b)).toFloat())
            }
        }
        m
    }
    Box(Modifier.fillMaxSize().background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), MaterialTheme.colorScheme.background)))
        .pointerInput(Unit) { detectTransformGestures { _, p, zoom, _ -> scale = (scale * zoom).coerceIn(0.12f, 2.5f); pan += p } }) {
        Box(Modifier.align(Alignment.Center).graphicsLayer { scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y }) {
            hubs.forEach { h -> val o = place["hub:" + h.name]!!
                Box(Modifier.offset { IntOffset((o.x - 110).toInt(), (o.y - 110).toInt()) }.size(220.dp / 2.6f * 2.6f).clip(CircleShape)
                    .background(Brush.radialGradient(listOf(h.spine.copy(alpha = 0.9f), h.spine.copy(alpha = 0.3f)))), contentAlignment = Alignment.Center) {
                    Text(h.label, color = Color.White, fontSize = if (scale < 0.4f) 30.sp else 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
            }
            if (scale > 0.28f) features.forEach { f -> val o = place[f.route] ?: return@forEach
                Column(Modifier.offset { IntOffset((o.x - 60).toInt(), (o.y - 36).toInt()) }.width(120.dp).clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)).border(1.dp, f.hub.spine.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
                    .clickable { open(f.route) }.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(f.icon, null, tint = f.hub.spine, modifier = Modifier.size(22.dp))
                    Text(f.title, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
        Text("Pinch to zoom · drag to move · ${features.size} tools", modifier = Modifier.align(Alignment.BottomCenter).padding(24.dp).clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f)).padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.labelMedium)
    }
}

// ─────────────────────────────── Time machine ───────────────────────────────
@Composable
fun TimeMachineScreen(container: AppContainer) {
    val c = LocalContext.current
    var back by remember { mutableIntStateOf(1) }
    val day = LocalDate.now().minusDays(back.toLong())
    var loaded by remember { mutableStateOf(false) }
    var txns by remember { mutableStateOf<List<com.nizam.app.feature.finance.Txn>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<com.nizam.app.data.db.Task>>(emptyList()) }
    var notes by remember { mutableStateOf<List<com.nizam.app.feature.notes.Note>>(emptyList()) }
    var diary by remember { mutableStateOf<List<com.nizam.app.feature.diary.DiaryEntry>>(emptyList()) }
    LaunchedEffect(Unit) {
        txns = runCatching { container.financeRepository.since(LocalDate.now().minusDays(400).toString()).first() }.getOrElse { emptyList() }
        tasks = runCatching { container.taskRepository.all().first() }.getOrElse { emptyList() }
        notes = runCatching { container.noteRepository.all().first() }.getOrElse { emptyList() }
        diary = runCatching { container.diaryRepository.entries().first() }.getOrElse { emptyList() }
        loaded = true
    }
    fun sameDay(ms: Long?) = ms != null && Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate() == day
    val dayTx = txns.filter { it.localDate == day.toString() }
    val done = tasks.filter { sameDay(it.completedAt) }
    val made = notes.filter { sameDay(it.createdAt) }
    val entry = diary.firstOrNull { it.localDate == day.toString() }
    val mood = remember(day) { c.getSharedPreferences("mood_wheel", 0).getString("today_$day", null) }
    val acts = remember(day) { runCatching { com.nizam.app.feature.next.Activity.all(c).filter { sameDay(it.optLong("t")) } }.getOrDefault(emptyList()) }
    AtlasScreen(title = "Time machine", subtitle = day.format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")), hub = Hub.Memory) {
        item("dial") { Column(Modifier.padding(horizontal = Space.page)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹", fontSize = 32.sp, modifier = Modifier.clickable { back = (back + 1).coerceAtMost(365) }.padding(8.dp))
                Slider((365 - back).toFloat(), { back = 365 - it.toInt() }, valueRange = 0f..364f, modifier = Modifier.weight(1f))
                Text("›", fontSize = 32.sp, modifier = Modifier.clickable { back = (back - 1).coerceAtLeast(0) }.padding(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1 to "Yesterday", 7 to "A week ago", 30 to "A month ago", 365 to "A year ago").forEach { (d, l) -> Capsule(l, back == d) { back = d } }
            }
        } }
        if (!loaded) item("l") { Text("Opening the archive…", modifier = Modifier.padding(Space.page)) }
        else {
            item("m") { Group(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) {
                Text("💸 Money", style = MaterialTheme.typography.titleSmall)
                val out = dayTx.filter { it.kind == "EXPENSE" }.sumOf { it.amount }; val inc = dayTx.filter { it.kind == "INCOME" }.sumOf { it.amount }
                Text(if (dayTx.isEmpty()) "Nothing recorded." else "Spent Rs %,d".format(out.toLong()) + if (inc > 0) " · earned Rs %,d".format(inc.toLong()) else "",
                    style = MaterialTheme.typography.bodyMedium)
                dayTx.take(6).forEach { Text("• ${it.category}${if (it.note.isNotBlank()) " — ${it.note.take(40)}" else ""}: Rs %,d".format(it.amount.toLong()), style = MaterialTheme.typography.bodySmall) }
            } }
            item("t") { Group(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) {
                Text("✅ Done that day", style = MaterialTheme.typography.titleSmall)
                if (done.isEmpty()) Text("No tasks ticked off.", style = MaterialTheme.typography.bodyMedium)
                done.take(8).forEach { Text("• ${it.title}", style = MaterialTheme.typography.bodySmall) }
            } }
            if (mood != null || entry != null) item("d") { Group(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) {
                Text("📔 How it felt", style = MaterialTheme.typography.titleSmall)
                mood?.let { Text("Mood: $it", style = MaterialTheme.typography.bodyMedium) }
                entry?.let { Text(it.body.take(600), style = MaterialTheme.typography.bodySmall) }
            } }
            if (made.isNotEmpty()) item("n") { Group(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) {
                Text("📝 Notes you wrote", style = MaterialTheme.typography.titleSmall)
                made.take(6).forEach { Text("• ${it.title}", style = MaterialTheme.typography.bodySmall) }
            } }
            if (acts.isNotEmpty()) item("a") { Group(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) {
                Text("🤖 What Atlas did (${acts.size})", style = MaterialTheme.typography.titleSmall)
                acts.take(8).forEach { Text("• ${it.optString("s").take(80)}", style = MaterialTheme.typography.bodySmall) }
            } }
        }
    }
}
