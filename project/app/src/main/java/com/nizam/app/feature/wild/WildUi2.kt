package com.nizam.app.feature.wild

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.gesture.Gesture
import android.gesture.GestureLibraries
import android.gesture.GestureLibrary
import android.gesture.GestureOverlayView
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AColor
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nizam.app.AppContainer
import com.nizam.app.NizamApplication
import com.nizam.app.feature.agent.AgentLoop
import com.nizam.app.feature.phone.AtlasAccessibilityService
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ─────────────────────────────── Conversation mode ───────────────────────────────
/**
 * A spoken conversation: Atlas listens, answers out loud, then listens again. Tap anywhere to interrupt it
 * mid-sentence. Anything risky it prepared waits for a spoken "yes" or "no".
 */
@Composable
fun TalkScreen(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var state by remember { mutableStateOf("idle") }          // idle | listening | thinking | speaking
    var heard by remember { mutableStateOf("") }
    var said by remember { mutableStateOf("Tap anywhere and talk.") }
    var level by remember { mutableFloatStateOf(0f) }
    var pending by remember { mutableStateOf<List<Pair<String, org.json.JSONObject>>>(emptyList()) }
    val main = remember { Handler(Looper.getMainLooper()) }
    val rec = remember { if (SpeechRecognizer.isRecognitionAvailable(c)) SpeechRecognizer.createSpeechRecognizer(c) else null }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    val holder = remember { arrayOfNulls<() -> Unit>(1) }            // listen(), set below so TTS can call it back

    fun speak(text: String) {
        said = text; state = "speaking"
        tts?.speak(text.lines().filterNot { it.trimStart().startsWith("✓") || it.trimStart().startsWith("✕") }.joinToString(" ").take(900),
            TextToSpeech.QUEUE_FLUSH, null, "talk")
    }
    fun handle(q: String) {
        heard = q; state = "thinking"
        scope.launch {
            val lower = q.lowercase().trim()
            if (pending.isNotEmpty() && Regex("^(yes|haan|han|ji|ok|okay|do it|go ahead|sure)\\b").containsMatchIn(lower)) {
                val done = pending.map { (n, a) -> com.nizam.app.feature.agent.Actions.run(container, n, a) }
                pending = emptyList(); speak(done.joinToString(". ") { it.summary }); return@launch
            }
            if (pending.isNotEmpty() && Regex("^(no|nahi|nah|cancel|stop|don't)").containsMatchIn(lower)) { pending = emptyList(); speak("Okay, I won't."); return@launch }
            val out = runCatching { container.agentLoop.run(q, useWeb = false, autoApprove = false) }
                .getOrElse { AgentLoop.Outcome("Sorry, that didn't work: ${Feedback.friendly(it)}", emptyList()) }
            pending = out.pendingApproval
            runCatching { container.agentMemory.addTurn(true, q); container.agentMemory.addTurn(false, out.reply) }
            speak(out.reply + if (pending.isNotEmpty()) " Before I do that — ${pending.joinToString { it.first.replace('_', ' ') }} — should I go ahead?" else "")
        }
    }
    fun listen() {
        val r = rec ?: run { said = "This phone has no speech recogniser."; return }
        tts?.stop()
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { state = "listening"; heard = "" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) { level = ((v + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { level = 0f }
            override fun onError(e: Int) { state = "idle"; level = 0f }
            override fun onResults(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { handle(it) } ?: run { state = "idle" } }
            override fun onPartialResults(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { heard = it } }
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        runCatching { r.startListening(com.nizam.app.feature.voice.speechIntent().putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)) }
    }
    holder[0] = { listen() }
    DisposableEffect(Unit) {
        var t: TextToSpeech? = null
        t = TextToSpeech(c) { st -> if (st == TextToSpeech.SUCCESS) {
            t?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { main.post { if (state == "speaking") holder[0]?.invoke() } }      // the conversation continues
                @Deprecated("old API") override fun onError(id: String?) { main.post { state = "idle" } }
            })
            tts = t
        } }
        onDispose { rec?.destroy(); t?.shutdown() }
    }
    val swell by animateFloatAsState(if (state == "listening") 1f + level * 0.35f else if (state == "speaking") 1.08f else 1f, label = "s")
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1020), cs.primary.copy(alpha = 0.35f), Color(0xFF0B1020))))
        .clickable {
            when (state) { "speaking" -> { tts?.stop(); listen() }; "listening" -> { rec?.stopListening(); state = "idle" }; "thinking" -> {}; else -> listen() }
        }) {
        Column(Modifier.align(Alignment.TopCenter).padding(top = 48.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(when (state) { "listening" -> "Listening…"; "thinking" -> "Thinking…"; "speaking" -> "Tap to interrupt"; else -> "Tap to talk" },
                color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge)
            if (heard.isNotBlank()) Text("“$heard”", color = Color.White, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 10.dp))
        }
        Box(Modifier.align(Alignment.Center).size((200 * swell).dp)) {
            com.nizam.app.ui.design.MorphingBlob(Modifier.fillMaxSize(), listOf(cs.primary, cs.tertiary, cs.secondary), state == "thinking")
        }
        Canvas(Modifier.align(Alignment.Center).size(280.dp)) {
            if (state == "listening") drawCircle(Color.White.copy(alpha = 0.25f + level * 0.5f), radius = size.minDimension / 2 * (0.8f + level * 0.2f), style = Stroke(3.dp.toPx()))
        }
        Text(said, color = Color.White, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, maxLines = 8,
            modifier = Modifier.align(Alignment.BottomCenter).padding(28.dp).padding(bottom = 30.dp))
    }
}

// ─────────────────────────────── Draw to command ───────────────────────────────
/**
 * A thin handle on the screen edge (drawn by Atlas's screen-control service, so it works over any app). Touch it,
 * draw a shape you taught Atlas, and the command bound to that shape runs.
 */
object DrawCommand {
    private var library: GestureLibrary? = null
    fun lib(c: Context): GestureLibrary = library ?: GestureLibraries.fromFile(File(c.filesDir, "gestures")).also { it.load(); library = it }
    private fun p(c: Context) = c.getSharedPreferences("draw_cmds", 0)
    fun commands(c: Context): Map<String, String> = p(c).all.filterKeys { it.startsWith("cmd:") }.mapKeys { it.key.removePrefix("cmd:") }.mapValues { it.value.toString() }
    fun enabled(c: Context) = p(c).getBoolean("on", false)
    fun setEnabled(c: Context, v: Boolean) {
        p(c).edit().putBoolean("on", v).apply()
        AtlasAccessibilityService.instance?.let { if (v) attach(it) else detach(it) }
    }
    fun add(c: Context, name: String, g: Gesture, command: String) {
        val l = lib(c); l.removeEntry(name); l.addGesture(name, g); l.save()
        p(c).edit().putString("cmd:$name", command).apply()
    }
    fun remove(c: Context, name: String) { lib(c).removeEntry(name); lib(c).save(); p(c).edit().remove("cmd:$name").apply() }

    private var handle: View? = null
    private var pad: GestureOverlayView? = null

    fun attach(svc: AccessibilityService) {
        if (!enabled(svc) || handle != null) return
        val wm = svc.getSystemService(WindowManager::class.java)
        val v = View(svc).apply {
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 24f; setColor(AColor.argb(70, 120, 120, 255)) }
            setOnTouchListener { _, e -> if (e.action == MotionEvent.ACTION_DOWN) showPad(svc); true }
        }
        val lp = WindowManager.LayoutParams(22, (svc.resources.displayMetrics.heightPixels * 0.28f).toInt(), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
            .apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        runCatching { wm.addView(v, lp); handle = v }
    }
    fun detach(svc: AccessibilityService) {
        val wm = svc.getSystemService(WindowManager::class.java)
        handle?.let { runCatching { wm.removeView(it) } }; handle = null
        pad?.let { runCatching { wm.removeView(it) } }; pad = null
    }

    private fun showPad(svc: AccessibilityService) {
        if (pad != null) return
        val wm = svc.getSystemService(WindowManager::class.java)
        val g = GestureOverlayView(svc).apply {
            setBackgroundColor(AColor.argb(110, 10, 12, 30)); gestureColor = AColor.rgb(129, 140, 248); gestureStrokeWidth = 14f
            gestureStrokeType = GestureOverlayView.GESTURE_STROKE_TYPE_MULTIPLE; fadeOffset = 700
            addOnGesturePerformedListener { _, gesture ->
                hidePad(svc)
                val best = lib(svc).recognize(gesture).firstOrNull { it.score > 2.0 }
                if (best == null) Toast.makeText(svc, "Didn't recognise that shape", Toast.LENGTH_SHORT).show()
                else run(svc, best.name)
            }
        }
        val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)
        runCatching { wm.addView(g, lp); pad = g }
        Handler(Looper.getMainLooper()).postDelayed({ hidePad(svc) }, 6000)
    }
    private fun hidePad(svc: AccessibilityService) { pad?.let { p -> runCatching { svc.getSystemService(WindowManager::class.java).removeView(p) } }; pad = null }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    fun run(c: Context, name: String) {
        val cmd = commands(c)[name] ?: return
        Toast.makeText(c, "✦ $name → $cmd", Toast.LENGTH_SHORT).show()
        Haptics.play(c, "done")
        val container = (c.applicationContext as? NizamApplication)?.container ?: return
        scope.launch {
            val sk = com.nizam.app.feature.agent.SkillsPro.find(c, cmd)
            val msg = if (sk != null) com.nizam.app.feature.agent.SkillsPro.runBackground(container, sk, emptyMap(), 0, "Drawn: $name", "ask").log.lastOrNull().orEmpty()
                else runCatching { container.agentLoop.run(cmd, useWeb = false, autoApprove = false).let { o ->
                    o.pendingApproval.forEach { (n, a) -> com.nizam.app.feature.agent.ApprovalInbox.add(c, "Drawn: $name", n, a,
                        com.nizam.app.feature.agent.Reviewer.Verdict("medium", "ask", "Drawn command"), cmd) }
                    o.reply } }.getOrElse { Feedback.friendly(it) }
            withContext(Dispatchers.Main) { Toast.makeText(c, msg.take(160), Toast.LENGTH_LONG).show() }
        }
    }
}

@Composable
fun DrawCommandScreen(container: AppContainer) {
    val c = LocalContext.current
    var on by remember { mutableStateOf(DrawCommand.enabled(c)) }
    var last by remember { mutableStateOf<Gesture?>(null) }
    var name by remember { mutableStateOf("") }
    var cmd by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    val list = remember(tick) { DrawCommand.commands(c) }
    AtlasScreen(title = "Draw to command", subtitle = "Touch the glowing edge handle, draw, done", hub = Hub.Tools) {
        item("on") { Column(Modifier.padding(horizontal = Space.page)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(on, { on = it; DrawCommand.setEnabled(c, it) }); Text("  Show the edge handle over every app") }
            if (AtlasAccessibilityService.instance == null) Text("Turn on Atlas screen control in Accessibility settings first.", color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        } }
        item("h") { SectionHeader("Teach a shape") }
        item("pad") { Box(Modifier.padding(horizontal = Space.page).fillMaxWidth().height(260.dp).clip(RoundedCornerShape(24.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))) {
            AndroidView({ ctx -> GestureOverlayView(ctx).apply {
                gestureStrokeType = GestureOverlayView.GESTURE_STROKE_TYPE_MULTIPLE; fadeOffset = 1500; gestureColor = AColor.rgb(99, 102, 241); gestureStrokeWidth = 12f
                addOnGesturePerformedListener { _, g -> last = g }
            } }, Modifier.fillMaxSize())
            if (last == null) Text("Draw here — a letter, a circle, a zigzag", modifier = Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item("f") { Column(Modifier.padding(Space.page)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name (e.g. A for Ammi)") }, singleLine = true)
            OutlinedTextField(cmd, { cmd = it }, Modifier.fillMaxWidth(), label = { Text("What it does — a skill name or any command") }, placeholder = { Text("message Ammi on WhatsApp: on my way") })
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("start a 25 minute focus", "log a glass of water", "what's my next prayer", "turn on do not disturb").forEach { s -> Capsule(s, false) { cmd = s } }
            }
            Button(onClick = { DrawCommand.add(c, name.trim(), last!!, cmd.trim()); name = ""; cmd = ""; last = null; tick++; Feedback.show("Shape saved — draw it twice more for accuracy") },
                enabled = last != null && name.isNotBlank() && cmd.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) { Text("Save shape") }
            Text("Tip: save the same name 2–3 times with slightly different drawings; Atlas learns the variations.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        } }
        if (list.isNotEmpty()) item("l") { SectionHeader("Your shapes") }
        list.forEach { (n, command) -> item("s$n") { ListRow(n, supporting = command, icon = Icons.Outlined.Gesture, trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Capsule("Run", false) { DrawCommand.run(c, n) }
                Capsule("✕", false) { DrawCommand.remove(c, n); tick++ }
            } }) } }
    }
}

// ─────────────────────────────── Living wallpaper ───────────────────────────────
@Composable
fun LiveWallpaperScreen() {
    val c = LocalContext.current
    AtlasScreen(title = "Living wallpaper", subtitle = "Your day, painted on the home screen", hub = Hub.Create) {
        item("a") { Group(Modifier.padding(horizontal = Space.page)) {
            Text("The sky follows the real time of day. The sun travels the prayer arc from Fajr to Isha, the next prayer counts down, " +
                "a ring fills as you finish today's tasks, and your pet walks along the bottom.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                val i = Intent(android.app.WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(android.app.WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, ComponentName(c, AtlasWallpaper::class.java))
                runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure {
                    runCatching { c.startActivity(Intent(android.app.WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            }, modifier = Modifier.padding(top = 10.dp)) { Text("Set as wallpaper") }
        } }
    }
}

/** The living wallpaper itself: redraws each minute, animates the pet briefly whenever the home screen appears. */
class AtlasWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = SkyEngine()

    inner class SkyEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private var frames = 0
        private var petX = 0.1f
        @Volatile private var data: Triple<List<Pair<String, Double>>, Pair<Int, Int>, String> = Triple(emptyList(), 0 to 0, "🦊")
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val tick = object : Runnable { override fun run() { draw(); if (visible) handler.postDelayed(this, if (frames > 0) 66L else 60_000L) } }

        private fun load() = Thread {
            runCatching {
                val app = (applicationContext as NizamApplication).container
                val s = app.settingsRepository
                val t = runBlocking { com.nizam.app.feature.prayer.PrayerTimes.calculate(LocalDate.now(), s.latitude.first(), s.longitude.first(),
                    runCatching { com.nizam.app.feature.prayer.PrayerTimes.Method.valueOf(s.prayerMethod.first()) }.getOrElse { com.nizam.app.feature.prayer.PrayerTimes.Method.KARACHI },
                    s.hanafiAsr.first()) }
                val tasks = runBlocking { app.taskRepository.all().first() }
                val today = LocalDate.now()
                val due = tasks.filter { it.deletedAt == null && (it.completedAt == null || java.time.Instant.ofEpochMilli(it.completedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate() == today) }
                val pet = com.nizam.app.feature.companion.Companion.load(applicationContext)
                val emoji = mapOf("Fox" to "🦊", "Owl" to "🦉", "Cat" to "🐱", "Dragon" to "🐉", "Axolotl" to "🦎", "Penguin" to "🐧", "Bunny" to "🐰", "Slime" to "🟢",
                    "Panda" to "🐼", "Lion" to "🦁", "Turtle" to "🐢", "Parrot" to "🦜", "Hamster" to "🐹")[pet.species] ?: "🥚"
                data = Triple(listOf("Fajr" to t.fajr, "Dhuhr" to t.dhuhr, "Asr" to t.asr, "Maghrib" to t.maghrib, "Isha" to t.isha),
                    due.count { it.completedAt != null } to due.size, if (pet.hatched) emoji else "🥚")
                handler.post { draw() }
            }
        }.start()

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) { frames = 90; load(); handler.removeCallbacks(tick); handler.post(tick) } else handler.removeCallbacks(tick)
        }
        override fun onSurfaceDestroyed(holder: SurfaceHolder) { visible = false; handler.removeCallbacks(tick); super.onSurfaceDestroyed(holder) }

        private fun draw() {
            val h = surfaceHolder; val canvas: AndroidCanvas = runCatching { h.lockCanvas() }.getOrNull() ?: return
            try {
                val w = canvas.width.toFloat(); val ht = canvas.height.toFloat()
                val now = LocalTime.now().let { it.hour + it.minute / 60.0 }
                val (prayers, taskCount, petEmoji) = data
                val sky = when { now < 5 || now >= 20 -> intArrayOf(0xFF070B1E.toInt(), 0xFF1B1F4B.toInt())
                    now < 7 -> intArrayOf(0xFF2B1B4B.toInt(), 0xFFF59E8B.toInt()); now < 17 -> intArrayOf(0xFF3B82F6.toInt(), 0xFFBFDBFE.toInt())
                    else -> intArrayOf(0xFF4C1D95.toInt(), 0xFFF97316.toInt()) }
                paint.shader = LinearGradient(0f, 0f, 0f, ht, sky[0], sky[1], Shader.TileMode.CLAMP); canvas.drawRect(0f, 0f, w, ht, paint); paint.shader = null
                // the prayer arc
                val cx = w / 2; val cy = ht * 0.62f; val r = w * 0.42f
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = AColor.argb(90, 255, 255, 255)
                canvas.drawArc(cx - r, cy - r, cx + r, cy + r, 180f, 180f, false, paint); paint.style = Paint.Style.FILL
                val first = prayers.firstOrNull()?.second ?: 5.0; val last = prayers.lastOrNull()?.second ?: 20.0
                fun pos(t: Double): Pair<Float, Float> { val f = ((t - first) / (last - first)).coerceIn(0.0, 1.0); val a = PI + f * PI
                    return (cx + r * cos(a)).toFloat() to (cy + r * sin(a)).toFloat() }
                paint.textSize = w * 0.032f; paint.textAlign = Paint.Align.CENTER
                prayers.forEach { (n, t) -> val (x, y) = pos(t); paint.color = if (t < now) AColor.argb(220, 255, 255, 255) else AColor.argb(120, 255, 255, 255)
                    canvas.drawCircle(x, y, 9f, paint); canvas.drawText(n, x, y + 42f, paint) }
                // sun or moon
                val (sx, sy) = pos(now)
                val sun = now in first..last
                paint.shader = RadialGradient(sx, sy, w * 0.09f, if (sun) AColor.argb(200, 253, 224, 71) else AColor.argb(160, 226, 232, 240), AColor.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawCircle(sx, sy, w * 0.09f, paint); paint.shader = null
                paint.color = if (sun) AColor.rgb(253, 224, 71) else AColor.rgb(226, 232, 240); canvas.drawCircle(sx, sy, w * 0.035f, paint)
                // next prayer, big
                val next = prayers.firstOrNull { it.second > now }
                paint.color = AColor.WHITE; paint.textSize = w * 0.075f
                canvas.drawText(next?.first ?: "Isha prayed", cx, ht * 0.22f, paint)
                paint.textSize = w * 0.04f; paint.color = AColor.argb(200, 255, 255, 255)
                next?.let { val m = ((it.second - now) * 60).toInt(); canvas.drawText(if (m >= 60) "in ${m / 60}h ${m % 60}m" else "in $m min", cx, ht * 0.22f + w * 0.07f, paint) }
                // today's task ring
                val (done, total) = taskCount
                val rx = w * 0.14f; val ry = ht * 0.8f; val rr = w * 0.07f
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 10f; paint.color = AColor.argb(60, 255, 255, 255); canvas.drawCircle(rx, ry, rr, paint)
                paint.color = AColor.rgb(52, 211, 153); canvas.drawArc(rx - rr, ry - rr, rx + rr, ry + rr, -90f, if (total == 0) 360f else 360f * done / total, false, paint)
                paint.style = Paint.Style.FILL; paint.color = AColor.WHITE; paint.textSize = w * 0.035f
                canvas.drawText("$done/$total", rx, ry + w * 0.012f, paint)
                // the pet, strolling
                if (frames > 0) { frames--; petX += 0.004f; if (petX > 0.95f) petX = 0.05f }
                paint.textSize = w * 0.09f; canvas.drawText(petEmoji, w * petX, ht * 0.93f + (if (frames % 10 < 5) 0f else -6f), paint)
            } finally { runCatching { h.unlockCanvasAndPost(canvas) } }
        }
    }
}
