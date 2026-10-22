package com.nizam.app.feature.pc

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val BG = Color(0xFF0A0C10)
private val CARD = Color(0xFF14181F)
private val EDGE = Color(0xFF262C36)
private val FG = Color(0xFFE5E9F0)
private val DIM = Color(0xFF8A93A3)
private val TEAL = Color(0xFF4FD1C5)
private val WARN = Color(0xFFF6AD55)

@Composable
fun PcScreen(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val pcs = remember(tick) { PcEnv.all(c) }
    var adding by remember { mutableStateOf(false) }
    val progress by PcEnv.progress.collectAsState()
    val installing = progress != null && progress?.stage !in setOf("Ready", "Couldn't finish")
    // a task from a chat: start the PC and let Atlas work while you watch
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard {
        PcAgent.queued?.let { t -> PcEnv.preferred(c)?.let { pc -> PcAgent.queued = null; PcEnv.start(c, pc.optString("id")); Vnc.connect(); PcAgent.start(container, pc.optString("id"), t) } }
    } }
    Box(Modifier.fillMaxSize().background(BG).statusBarsPadding()) {
        val runningId = PcEnv.running
        when {
            runningId != null -> Desktop(container, runningId) { tick++ }
            adding || installing || pcs.none { it.optBoolean("ready") } && pcs.isEmpty() -> Installer(onDone = { adding = false; tick++ }, onCancel = if (pcs.isNotEmpty()) ({ adding = false }) else null)
            else -> Machines(container, pcs, onAdd = { adding = true }) { tick++ }
        }
    }
}

// ── the list of PCs ─────────────────────────────────────────────────────────────────
@Composable
private fun Machines(container: AppContainer, pcs: List<org.json.JSONObject>, onAdd: () -> Unit, changed: () -> Unit) {
    val c = LocalContext.current
    val setupScope = com.nizam.app.ui.design.rememberSafeScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("🖥  Atlas's PC", color = FG, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
        Text("Full Linux desktops on your phone. Use them yourself, or give Atlas a task and watch it work.", color = DIM, style = MaterialTheme.typography.bodySmall)
        if (PcAgent.queued != null) Text("A task is waiting: “${PcAgent.queued}”. Start a PC and Atlas will begin.", color = WARN, style = MaterialTheme.typography.bodySmall)
        pcs.forEach { p ->
            val id = p.optString("id")
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CARD).border(1.dp, EDGE, RoundedCornerShape(18.dp)).padding(16.dp)) {
                Text(p.optString("name"), color = FG, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text("${p.optString("image")} · ${p.optString("desktop")} · ${p.optString("size")}" + if (!p.optBoolean("ready")) " · setup unfinished" else "", color = DIM, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!p.optBoolean("ready")) Button(onClick = { setupScope.launch { PcEnv.finishSetup(c, id); changed() } }) { Text("Finish setup") }
                    else Button(onClick = {
                        PcEnv.start(c, id); Vnc.connect(); changed()
                        PcAgent.queued?.let { t -> PcAgent.queued = null; PcAgent.start(container, id, t) }
                    }) { Text("Start") }
                    TextButton(onClick = { PcEnv.remove(c, id); changed() }) { Text("Remove", color = Color(0xFFF56565)) }
                }
            }
        }
        OutlinedButton(onClick = onAdd) { Text("+ Install another PC") }
        Text("Tip: in any Atlas chat, switch on 🖥 Use PC and Atlas will do the task here while you watch.", color = DIM, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(40.dp))
    }
}

// ── installing a new PC ─────────────────────────────────────────────────────────────
@Composable
private fun Installer(onDone: () -> Unit, onCancel: (() -> Unit)?) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val progress by PcEnv.progress.collectAsState()
    var distro by remember { mutableStateOf(PcEnv.DISTROS[1]) }
    var custom by remember { mutableStateOf("") }
    var desktop by remember { mutableStateOf("xfce") }
    var size by remember { mutableStateOf("1280x720") }
    val busy = progress != null && progress?.stage !in setOf("Ready", "Couldn't finish")
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(busy) { view.keepScreenOn = busy; onDispose { view.keepScreenOn = false } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (busy) Text("Keep Atlas open on this screen while it installs (the screen stays on).", color = WARN, style = MaterialTheme.typography.labelSmall)
        Text("Set up a PC", color = FG, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        val p = progress
        if (p != null) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(CARD).padding(14.dp)) {
            Text(p.stage, color = if (p.error.isNotBlank()) Color(0xFFF56565) else FG)
            if (p.pct in 0..99) LinearProgressIndicator(progress = { p.pct / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            else if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
            if (p.log.isNotBlank()) Text(p.log.takeLast(1200), color = DIM, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp).heightIn(max = 220.dp))
            if (p.error.isNotBlank()) Text(p.error.takeLast(1500), color = Color(0xFFF56565), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            if (p.stage == "Ready") Button(onClick = onDone, modifier = Modifier.padding(top = 8.dp)) { Text("Done") }
        }
        if (!busy) {
            Text("LINUX", color = DIM, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            PcEnv.DISTROS.forEach { d ->
                val on = d == distro && custom.isBlank()
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (on) TEAL.copy(alpha = 0.15f) else CARD).border(1.dp, if (on) TEAL else EDGE, RoundedCornerShape(14.dp))
                    .clickable { distro = d; custom = "" }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(d.emoji, fontSize = 22.sp, modifier = Modifier.padding(end = 10.dp))
                    Column { Text(d.title, color = FG, fontWeight = FontWeight.SemiBold); Text(d.note, color = DIM, style = MaterialTheme.typography.labelSmall) }
                }
            }
            OutlinedTextField(custom, { custom = it.trim() }, label = { Text("…or any Linux image (must have an ARM64 version)") }, placeholder = { Text("owner/image:tag") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("DESKTOP", color = DIM, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            PcEnv.DESKTOPS.forEach { (k, l) ->
                Text((if (desktop == k) "◉  " else "○  ") + l, color = if (desktop == k) TEAL else FG, modifier = Modifier.fillMaxWidth().clickable { desktop = k }.padding(vertical = 8.dp))
            }
            Text("SCREEN", color = DIM, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PcEnv.SIZES.forEach { s -> Text(s + if (s == "1080x1920") " (portrait)" else "", color = if (size == s) Color.Black else FG,
                    modifier = Modifier.clip(CircleShape).background(if (size == s) TEAL else CARD).clickable { size = s }.padding(horizontal = 12.dp, vertical = 8.dp)) }
            }
            Text("Download: about 40–200 MB for the system, then 0.5–1.5 GB for the desktop. Use Wi-Fi and keep Atlas open while it installs.", color = DIM, style = MaterialTheme.typography.labelSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val image = custom.ifBlank { distro.image }
                    val name = if (custom.isBlank()) distro.title else custom.substringAfterLast('/').substringBefore(':')
                    scope.launch { PcEnv.install(c, name, image, desktop, size) }
                }) { Text("Install") }
                onCancel?.let { TextButton(onClick = it) { Text("Cancel") } }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

// ── the desktop ─────────────────────────────────────────────────────────────────────
/**
 * Two ways to drive it, switchable any time:
 *  • Trackpad (default): drag anywhere to move the pointer, tap = click, double-tap = double-click,
 *    tap-then-drag = hold and drag, two-finger tap = right-click, two-finger drag = scroll, pinch = zoom.
 *  • Touch: tap where you want to click, long-press = right-click, drag = hold and drag, two fingers as above.
 */
@Composable
private fun Desktop(container: AppContainer, id: String, changed: () -> Unit) {
    val c = LocalContext.current
    val kb = LocalSoftwareKeyboardController.current
    val view = androidx.compose.ui.platform.LocalView.current
    val focus = remember { FocusRequester() }
    var typing by remember { mutableStateOf(TextFieldValue(" ", TextRange(1))) }
    var keysOpen by remember { mutableStateOf(false) }
    var trackpad by remember { mutableStateOf(c.getSharedPreferences("pc", 0).getBoolean("trackpad", true)) }
    var full by remember { mutableStateOf(false) }
    var panelOpen by remember { mutableStateOf(true) }
    var taskText by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var mouse by remember { mutableStateOf(Offset(200f, 200f)) }   // pointer position on the PC
    val mods = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    LaunchedEffect(id) { Vnc.connect() }
    // auto-fit: the PC's screen takes the phone's shape (tall in portrait, wide in landscape), live as you rotate
    var autoFit by remember { mutableStateOf(c.getSharedPreferences("pc", 0).getBoolean("autofit", true)) }
    LaunchedEffect(boxSize, autoFit, Vnc.connected, Vnc.extended) {
        if (!Vnc.connected || boxSize.width < 100 || boxSize.height < 100) return@LaunchedEffect
        kotlinx.coroutines.delay(500)
        if (!autoFit) {
            // back to the size chosen at install
            val (fw, fh) = (PcEnv.info(c, id)?.optString("size", "1280x720") ?: "1280x720").split('x').mapNotNull { it.toIntOrNull() }.let { (it.getOrNull(0) ?: 1280) to (it.getOrNull(1) ?: 720) }
            if (fw != Vnc.width || fh != Vnc.height) Thread { Vnc.setSize(fw, fh) }.start()
            return@LaunchedEffect
        }
        val long = (PcEnv.info(c, id)?.optString("size", "1280x720") ?: "1280x720").split('x').mapNotNull { it.toIntOrNull() }.maxOrNull()?.coerceIn(960, 1920) ?: 1280
        val wide = boxSize.width >= boxSize.height
        var w = if (wide) long else (long * boxSize.width / boxSize.height.toFloat()).toInt()
        var h = if (wide) (long * boxSize.height / boxSize.width.toFloat()).toInt() else long
        w = (w / 8 * 8).coerceAtLeast(480); h = (h / 8 * 8).coerceAtLeast(480)
        if (kotlin.math.abs(w - Vnc.width) > 16 || kotlin.math.abs(h - Vnc.height) > 16) { zoom = 1f; pan = Offset.Zero; Thread { Vnc.setSize(w, h) }.start() }
    }
    // the screen stays on while you use the PC
    androidx.compose.runtime.DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    // full screen: landscape, no system bars
    androidx.compose.runtime.DisposableEffect(full) {
        val act = c as? android.app.Activity
        val ctl = act?.let { androidx.core.view.WindowCompat.getInsetsController(it.window, it.window.decorView) }
        if (full) { act?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE; ctl?.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            ctl?.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        onDispose { if (full) { act?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED; ctl?.show(androidx.core.view.WindowInsetsCompat.Type.systemBars()) } }
    }
    fun bg(block: () -> Unit) = Thread { runCatching(block) }.start()
    /** A key with whatever modifiers are switched on, then the modifiers let go. */
    fun press(spec: String) { val combo = (mods.map { it.lowercase() } + spec).joinToString("+"); mods.clear(); bg { Vnc.combo(combo) } }

    Column(Modifier.fillMaxSize().imePadding()) {
        if (!full) Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(PcEnv.info(c, id)?.optString("name").orEmpty(), color = FG, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
            if (!Vnc.connected) Text("connecting… ", color = DIM, style = MaterialTheme.typography.labelSmall)
            Text("⏻", color = Color(0xFFF56565), fontSize = 18.sp, modifier = Modifier.clip(CircleShape).clickable { PcAgent.stop(); PcEnv.stop(c); changed() }.padding(8.dp))
        }
        // ── the screen ──
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black).onSizeChanged { boxSize = it }) {
            val bmp = Vnc.bitmap
            if (bmp != null && Vnc.width > 0 && boxSize.width > 0) {
                val fit = minOf(boxSize.width / Vnc.width.toFloat(), boxSize.height / Vnc.height.toFloat())
                val s = fit * zoom
                val maxPanX = maxOf(0f, (Vnc.width * s - boxSize.width) / 2f); val maxPanY = maxOf(0f, (Vnc.height * s - boxSize.height) / 2f)
                val p0 = Offset(pan.x.coerceIn(-maxPanX, maxPanX), pan.y.coerceIn(-maxPanY, maxPanY))
                val ox = (boxSize.width - Vnc.width * s) / 2f + p0.x
                val oy = (boxSize.height - Vnc.height * s) / 2f + p0.y
                fun toPc(o: Offset) = Offset(((o.x - ox) / s).coerceIn(0f, Vnc.width - 1f), ((o.y - oy) / s).coerceIn(0f, Vnc.height - 1f))
                val agentBusy = PcAgent.running && !PcAgent.paused
                Canvas(Modifier.fillMaxSize().pointerInput(trackpad, s, ox, oy, agentBusy) {
                    if (agentBusy) return@pointerInput
                    var lastTapAt = 0L
                    androidx.compose.foundation.gestures.awaitEachGesture {
                        val first = androidx.compose.foundation.gestures.awaitFirstDown(requireUnconsumed = false)
                        val t0 = System.currentTimeMillis()
                        val start = first.position
                        var maxFingers = 1; var moved = 0f; var dragging = false; var scrollAcc = 0f
                        val tapThenDrag = t0 - lastTapAt < 300
                        var prevSpan = 0f; var pinching = false; var longFired = false
                        if (!trackpad) bg { val q = toPc(start); Vnc.pointer(q.x.toInt(), q.y.toInt(), 0) }
                        while (true) {
                            val ev = awaitPointerEvent()
                            val down = ev.changes.filter { it.pressed }
                            if (down.isEmpty()) break
                            maxFingers = maxOf(maxFingers, down.size)
                            if (down.size >= 2) {
                                val a = down[0].position; val b = down[1].position
                                val span = (a - b).getDistance()
                                val d = (down[0].position - down[0].previousPosition + down[1].position - down[1].previousPosition) / 2f
                                if (prevSpan > 0f && kotlin.math.abs(span - prevSpan) > 6f) pinching = true
                                if (pinching) { zoom = (zoom * (span / prevSpan.coerceAtLeast(1f))).coerceIn(1f, 5f); pan += d }
                                else if (zoom > 1.05f) pan += d
                                else { scrollAcc += d.y; while (kotlin.math.abs(scrollAcc) > 36f) { val dir = if (scrollAcc > 0) -1 else 1; scrollAcc -= -dir * 36f
                                    val q = if (trackpad) mouse else toPc((a + b) / 2f); bg { Vnc.scroll(q.x.toInt(), q.y.toInt(), dir) } } }
                                prevSpan = span; moved += 50f
                                down.forEach { it.consume() }; continue
                            }
                            val ch = down[0]
                            val delta = ch.position - ch.previousPosition
                            moved += delta.getDistance()
                            if (trackpad) {
                                // relative pointer, faster when you move faster
                                val speed = 1.4f / s * (1f + (delta.getDistance() / 18f).coerceAtMost(2.5f))
                                mouse = Offset((mouse.x + delta.x * speed).coerceIn(0f, Vnc.width - 1f), (mouse.y + delta.y * speed).coerceIn(0f, Vnc.height - 1f))
                                if (tapThenDrag && !dragging && moved > 8f) { dragging = true; bg { Vnc.pointer(mouse.x.toInt(), mouse.y.toInt(), 1) } }
                                val m = mouse; val mask = if (dragging) 1 else 0
                                if (moved > 2f) bg { Vnc.pointer(m.x.toInt(), m.y.toInt(), mask) }
                                // keep the pointer in view when zoomed
                                if (zoom > 1.05f) { val sx = ox + m.x * s; val sy = oy + m.y * s
                                    if (sx < 40) pan += Offset(40 - sx, 0f); if (sx > boxSize.width - 40) pan -= Offset(sx - boxSize.width + 40, 0f)
                                    if (sy < 40) pan += Offset(0f, 40 - sy); if (sy > boxSize.height - 40) pan -= Offset(0f, sy - boxSize.height + 40) }
                            } else {
                                val q = toPc(ch.position); mouse = q
                                if (!dragging && moved > 14f) { dragging = true; val st = toPc(start); bg { Vnc.pointer(st.x.toInt(), st.y.toInt(), 1) } }
                                if (dragging) bg { Vnc.pointer(q.x.toInt(), q.y.toInt(), 1) }
                                if (!dragging && !longFired && System.currentTimeMillis() - t0 > 550 && moved < 14f) {
                                    longFired = true; bg { Vnc.click(q.x.toInt(), q.y.toInt(), button = 3) }
                                    view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                                }
                            }
                            ch.consume()
                        }
                        val quick = System.currentTimeMillis() - t0 < 300
                        when {
                            dragging -> { val m = mouse; bg { Vnc.pointer(m.x.toInt(), m.y.toInt(), 0) } }
                            maxFingers >= 2 && !pinching && quick && moved < 120f -> { val m = if (trackpad) mouse else toPc(start); bg { Vnc.click(m.x.toInt(), m.y.toInt(), button = 3) }; view.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK) }
                            maxFingers == 1 && !longFired && moved < 14f && quick -> {
                                val m = if (trackpad) mouse else toPc(start)
                                val dbl = tapThenDrag
                                lastTapAt = if (dbl) 0L else System.currentTimeMillis()
                                bg { if (dbl) { Vnc.click(m.x.toInt(), m.y.toInt()) } else Vnc.click(m.x.toInt(), m.y.toInt()) }
                            }
                        }
                    }
                }) {
                    if (Vnc.frame < 0) return@Canvas  // reading the frame counter redraws on every new picture
                    drawImage(bmp.asImageBitmap(), dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize((Vnc.width * s).toInt(), (Vnc.height * s).toInt()))
                    // your pointer (trackpad) and Atlas's (when it's working)
                    if (trackpad && !agentBusy) {
                        val p = Offset(ox + mouse.x * s, oy + mouse.y * s)
                        val path = androidx.compose.ui.graphics.Path().apply { moveTo(p.x, p.y); lineTo(p.x, p.y + 34f); lineTo(p.x + 9f, p.y + 26f); lineTo(p.x + 16f, p.y + 40f)
                            lineTo(p.x + 22f, p.y + 37f); lineTo(p.x + 15f, p.y + 24f); lineTo(p.x + 26f, p.y + 24f); close() }
                        drawPath(path, Color.White); drawPath(path, Color.Black, style = Stroke(2.5f))
                    }
                    PcAgent.cursor?.let { (x, y) -> if (PcAgent.running) { val p = Offset(ox + x * s, oy + y * s)
                        drawCircle(TEAL.copy(alpha = 0.3f), 26f, p); drawCircle(TEAL, 26f, p, style = Stroke(4f)) } }
                }
                if (agentBusy) Text("Atlas is using the PC · tap ✋ to take over", color = Color.Black, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.TopCenter).padding(6.dp).clip(CircleShape).background(TEAL).padding(horizontal = 10.dp, vertical = 4.dp))
            } else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = TEAL)
                Text("Starting the desktop… " + Vnc.error.takeIf { it.isNotBlank() }.orEmpty().take(80), color = DIM, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 10.dp))
            }
            // hidden field: the phone keyboard types into the PC
            BasicTextField(typing, { v ->
                val old = typing.text; val new = v.text
                if (new.length < old.length) press("backspace")
                else if (new.startsWith(old)) { val add = new.substring(old.length)
                    if (mods.isNotEmpty() && add.length == 1) press(if (add == "\n") "enter" else add) else bg { Vnc.type(add) } }
                typing = TextFieldValue(" ", TextRange(1))
            }, modifier = Modifier.size(1.dp).focusRequester(focus))
            // floating controls on the screen
            Row(Modifier.align(Alignment.BottomEnd).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Fab(if (trackpad) "🖱" else "👆") { trackpad = !trackpad; c.getSharedPreferences("pc", 0).edit().putBoolean("trackpad", trackpad).apply() }
                Fab("⌨") { keysOpen = true; focus.requestFocus(); kb?.show() }
                if (zoom > 1.05f) Fab("1:1") { zoom = 1f; pan = Offset.Zero }
                Fab(if (autoFit) "◱" else "▭") { autoFit = !autoFit; c.getSharedPreferences("pc", 0).edit().putBoolean("autofit", autoFit).apply()
                    android.widget.Toast.makeText(c, if (autoFit) "Screen fits the phone" else "Fixed screen size", android.widget.Toast.LENGTH_SHORT).show() }
                Fab(if (full) "⤡" else "⤢") { full = !full }
                if (full) Fab(if (panelOpen) "▾" else "✳") { panelOpen = !panelOpen }
            }
        }
        // mouse buttons (trackpad) and keys
        if (trackpad) Row(Modifier.fillMaxWidth().background(CARD).padding(horizontal = 6.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Left click" to 1, "Right click" to 3).forEach { (l, b) ->
                Text(l, color = FG, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(EDGE)
                    .clickable { val m = mouse; bg { Vnc.click(m.x.toInt(), m.y.toInt(), button = b) } }.padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            Text("▲", color = FG, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(EDGE).clickable { val m = mouse; bg { Vnc.scroll(m.x.toInt(), m.y.toInt(), -3) } }.padding(horizontal = 14.dp, vertical = 10.dp))
            Text("▼", color = FG, modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(EDGE).clickable { val m = mouse; bg { Vnc.scroll(m.x.toInt(), m.y.toInt(), 3) } }.padding(horizontal = 14.dp, vertical = 10.dp))
        }
        if (keysOpen) Column(Modifier.fillMaxWidth().background(CARD).padding(4.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Ctrl", "Alt", "Shift", "Super").forEach { m -> val on = m in mods
                    Text(m, color = if (on) Color.Black else FG, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) TEAL else EDGE).clickable { if (on) mods.remove(m) else mods.add(m) }.padding(horizontal = 12.dp, vertical = 9.dp)) }
                listOf("Esc" to "esc", "Tab" to "tab", "Enter" to "enter", "⌫" to "backspace", "Del" to "delete", "←" to "left", "↑" to "up", "↓" to "down", "→" to "right",
                    "Home" to "home", "End" to "end", "PgUp" to "pageup", "PgDn" to "pagedown", "F5" to "f5", "F11" to "f11").forEach { (l, k) ->
                    Text(l, color = FG, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(EDGE).clickable { press(k) }.padding(horizontal = 10.dp, vertical = 9.dp)) }
                Text("✕", color = DIM, modifier = Modifier.clickable { keysOpen = false; kb?.hide() }.padding(horizontal = 10.dp, vertical = 9.dp))
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("Copy" to "ctrl+c", "Paste" to "ctrl+v", "Cut" to "ctrl+x", "Undo" to "ctrl+z", "Select all" to "ctrl+a", "Save" to "ctrl+s", "Find" to "ctrl+f",
                    "New tab" to "ctrl+t", "Address bar" to "ctrl+l", "Close" to "ctrl+w", "Switch app" to "alt+tab", "Quit app" to "alt+f4", "Terminal" to "ctrl+alt+t").forEach { (l, k) ->
                    Text(l, color = FG, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(EDGE.copy(alpha = 0.6f)).clickable { mods.clear(); bg { Vnc.combo(k) } }.padding(horizontal = 10.dp, vertical = 8.dp)) }
            }
        }
        // ── Atlas at work ──
        if (!full || panelOpen) Column(Modifier.fillMaxWidth().background(CARD).padding(10.dp)) {
            PcAgent.confirm?.let { q ->
                Text("⚠ Atlas wants to: $q", color = WARN)
                Row { Button(onClick = { PcAgent.answerConfirm(true) }) { Text("Allow") }; TextButton(onClick = { PcAgent.answerConfirm(false) }) { Text("No") } }
            }
            PcAgent.question?.let { q ->
                Text("❓ $q", color = FG)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(answer, { answer = it }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(enabled = answer.isNotBlank(), onClick = { PcAgent.answerQuestion(answer); answer = "" }) { Text("Send") }
                }
            }
            if (PcAgent.running || PcAgent.steps.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (PcAgent.running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = TEAL)
                    Text("  " + (if (PcAgent.running) PcAgent.status else "Finished: ${PcAgent.task}"), color = FG, style = MaterialTheme.typography.labelMedium, maxLines = 2, modifier = Modifier.weight(1f))
                    if (PcAgent.running) {
                        Text(if (PcAgent.paused) "▶ Give back" else "✋ Take over", color = TEAL, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { PcAgent.paused = !PcAgent.paused }.padding(6.dp))
                        Text("■", color = Color(0xFFF56565), modifier = Modifier.clickable { PcAgent.stop() }.padding(6.dp))
                    } else Text("✕", color = DIM, modifier = Modifier.clickable { PcAgent.steps.clear() }.padding(6.dp))
                }
                val lst = rememberLazyListState()
                LaunchedEffect(PcAgent.steps.size) { if (PcAgent.steps.isNotEmpty()) runCatching { lst.scrollToItem(PcAgent.steps.size - 1) } }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 96.dp), state = lst) {
                    items(PcAgent.steps.size) { i -> val st = PcAgent.steps[i]
                        Text(st.text, color = if (st.ok) DIM else Color(0xFFF56565), style = MaterialTheme.typography.labelSmall, maxLines = 3) }
                }
            }
            if (!PcAgent.running) Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(taskText, { taskText = it }, placeholder = { Text("✳ Give Atlas a task on this PC…") }, modifier = Modifier.weight(1f), maxLines = 3)
                Button(enabled = taskText.isNotBlank(), onClick = { PcAgent.start(container, id, taskText); taskText = ""; kb?.hide() }, modifier = Modifier.padding(start = 6.dp)) { Text("Go") }
            }
        }
    }
}

@Composable
private fun Fab(label: String, onClick: () -> Unit) = Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xCC14181F)).border(1.dp, EDGE, CircleShape).clickable { onClick() },
    contentAlignment = Alignment.Center) { Text(label, color = FG, fontSize = 16.sp) }

val PcSpecs: List<com.nizam.app.feature.nx.Spec> get() = listOf(
    com.nizam.app.feature.nx.ScreenSpec("pc", "Atlas's PC", "A full Linux desktop on your phone, any distro; use it yourself or watch Atlas work on it",
        androidx.compose.material.icons.Icons.Outlined.Computer, com.nizam.app.ui.design.Hub.Work,
        "pc computer desktop linux vm virtual machine ubuntu debian kali fedora arch xfce kde vnc remote use pc computer use agent") { c, o -> PcScreen(c, o) },
)
