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
                    Button(enabled = p.optBoolean("ready") || p.optString("desktop") == "none", onClick = {
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
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
            OutlinedTextField(custom, { custom = it.trim() }, label = { Text("…or any image (e.g. linuxmintd/mint22-amd64 won't work; needs arm64)") }, placeholder = { Text("owner/image:tag") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
@Composable
private fun Desktop(container: AppContainer, id: String, changed: () -> Unit) {
    val c = LocalContext.current
    val kb = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    var typing by remember { mutableStateOf(TextFieldValue(" ", TextRange(1))) }
    var showKeys by remember { mutableStateOf(false) }
    var taskText by remember { mutableStateOf("") }
    var answer by remember { mutableStateOf("") }
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    LaunchedEffect(id) { Vnc.connect() }
    Column(Modifier.fillMaxSize().imePadding()) {
        // top bar
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("🖥", modifier = Modifier.padding(end = 6.dp))
            Text(PcEnv.info(c, id)?.optString("name").orEmpty(), color = FG, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (!Vnc.connected) Text("connecting…", color = DIM, style = MaterialTheme.typography.labelSmall)
            Text("⌨", color = FG, fontSize = 18.sp, modifier = Modifier.clip(CircleShape).clickable { focus.requestFocus(); kb?.show(); showKeys = true }.padding(8.dp))
            Text("⏻", color = Color(0xFFF56565), fontSize = 18.sp, modifier = Modifier.clip(CircleShape).clickable { PcAgent.stop(); PcEnv.stop(c); changed() }.padding(8.dp))
        }
        // the screen
        Box(Modifier.fillMaxWidth().weight(1f).background(Color.Black).onSizeChanged { boxSize = it }) {
            val bmp = Vnc.bitmap
            if (bmp != null && Vnc.width > 0) {
                val fit = minOf(boxSize.width / Vnc.width.toFloat(), boxSize.height / Vnc.height.toFloat()).takeIf { it > 0 } ?: 1f
                val s = fit * zoom
                val ox = (boxSize.width - Vnc.width * s) / 2f + pan.x
                val oy = (boxSize.height - Vnc.height * s) / 2f + pan.y
                fun toPc(o: Offset) = (((o.x - ox) / s).toInt()) to (((o.y - oy) / s).toInt())
                Canvas(Modifier.fillMaxSize()
                    .pointerInput(s, ox, oy) {
                        detectTapGestures(
                            onTap = { o -> if (!PcAgent.running) { val (x, y) = toPc(o); Thread { Vnc.click(x, y) }.start() } },
                            onDoubleTap = { o -> if (!PcAgent.running) { val (x, y) = toPc(o); Thread { Vnc.click(x, y, times = 2) }.start() } },
                            onLongPress = { o -> if (!PcAgent.running) { val (x, y) = toPc(o); Thread { Vnc.click(x, y, button = 3) }.start() } })
                    }
                    .pointerInput(s, ox, oy) {
                        detectTransformGestures { centroid, p, z, _ ->
                            if (z != 1f) { zoom = (zoom * z).coerceIn(1f, 4f); pan += p }
                            else if (zoom > 1.01f) pan += p
                            else if (!PcAgent.running) { val (x, y) = toPc(centroid); Thread { Vnc.scroll(x, y, if (p.y < 0) 1 else -1) }.start() }
                        }
                    }) {
                    if (Vnc.frame < 0) return@Canvas  // reading the frame counter redraws on every new picture
                    drawImage(bmp.asImageBitmap(), dstOffset = IntOffset(ox.toInt(), oy.toInt()), dstSize = IntSize((Vnc.width * s).toInt(), (Vnc.height * s).toInt()))
                    PcAgent.cursor?.let { (x, y) ->
                        val p = Offset(ox + x * s, oy + y * s)
                        drawCircle(TEAL.copy(alpha = 0.35f), 22f, p); drawCircle(TEAL, 22f, p, style = Stroke(4f))
                    }
                }
            } else Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = TEAL)
                Text("Starting the desktop… " + Vnc.error.takeIf { it.isNotBlank() }.orEmpty().take(80), color = DIM, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 10.dp))
            }
            // hidden field that turns the phone keyboard into PC keystrokes
            BasicTextField(typing, { v ->
                val old = typing.text; val new = v.text
                Thread {
                    if (new.length < old.length) repeat(old.length - new.length) { Vnc.tap(0xff08) }
                    else if (new.startsWith(old)) Vnc.type(new.substring(old.length))
                }.start()
                typing = TextFieldValue(" ", TextRange(1))
            }, modifier = Modifier.size(1.dp).focusRequester(focus))
        }
        if (showKeys) Row(Modifier.fillMaxWidth().background(CARD).horizontalScroll(rememberScrollState()).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Esc" to "esc", "Tab" to "tab", "Enter" to "enter", "⌫" to "backspace", "←" to "left", "↑" to "up", "↓" to "down", "→" to "right",
                "Ctrl+C" to "ctrl+c", "Ctrl+V" to "ctrl+v", "Ctrl+L" to "ctrl+l", "Alt+Tab" to "alt+tab", "Alt+F4" to "alt+f4", "Super" to "super", "F5" to "f5").forEach { (l, k) ->
                Text(l, color = FG, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(EDGE).clickable { Thread { Vnc.combo(k) }.start() }.padding(horizontal = 10.dp, vertical = 8.dp))
            }
        }
        // Atlas at work
        Column(Modifier.fillMaxWidth().background(CARD).padding(10.dp)) {
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
                        Text(if (PcAgent.paused) "▶ Resume" else "✋ Take over", color = TEAL, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { PcAgent.paused = !PcAgent.paused }.padding(6.dp))
                        Text("■", color = Color(0xFFF56565), modifier = Modifier.clickable { PcAgent.stop() }.padding(6.dp))
                    }
                }
                val lst = rememberLazyListState()
                LaunchedEffect(PcAgent.steps.size) { if (PcAgent.steps.isNotEmpty()) runCatching { lst.scrollToItem(PcAgent.steps.size - 1) } }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 110.dp), state = lst) {
                    items(PcAgent.steps.size) { i -> val st = PcAgent.steps[i]
                        Text(st.text, color = if (st.ok) DIM else Color(0xFFF56565), style = MaterialTheme.typography.labelSmall, maxLines = 3) }
                }
            }
            if (!PcAgent.running) Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(taskText, { taskText = it }, placeholder = { Text("Give Atlas a task on this PC…") }, modifier = Modifier.weight(1f), maxLines = 3)
                Button(enabled = taskText.isNotBlank(), onClick = { PcAgent.start(container, id, taskText); taskText = "" }, modifier = Modifier.padding(start = 6.dp)) { Text("Go") }
            }
        }
    }
}


val PcSpecs: List<com.nizam.app.feature.nx.Spec> get() = listOf(
    com.nizam.app.feature.nx.ScreenSpec("pc", "Atlas's PC", "A full Linux desktop on your phone, any distro; use it yourself or watch Atlas work on it",
        androidx.compose.material.icons.Icons.Outlined.Computer, com.nizam.app.ui.design.Hub.Work,
        "pc computer desktop linux vm virtual machine ubuntu debian kali fedora arch xfce kde vnc remote use pc computer use agent") { c, o -> PcScreen(c, o) },
)
