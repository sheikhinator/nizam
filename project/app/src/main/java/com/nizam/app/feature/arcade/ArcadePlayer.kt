package com.nizam.app.feature.arcade

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.view.KeyEvent
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.swordfish.libretrodroid.GLRetroView
import com.swordfish.libretrodroid.GLRetroViewData
import com.swordfish.libretrodroid.ShaderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/** Lets the game be paused (menu open, app in the background) without tearing the emulator down. */
private class PlayerLifecycle : LifecycleOwner {
    private val reg = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = reg
    private var stopped = false
    fun start() { reg.handleLifecycleEvent(Lifecycle.Event.ON_CREATE); reg.handleLifecycleEvent(Lifecycle.Event.ON_START); reg.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
    fun pause() { if (!stopped && reg.currentState == Lifecycle.State.RESUMED) reg.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE) }
    fun resume() { if (!stopped && reg.currentState == Lifecycle.State.STARTED) reg.handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
    fun stop() {
        if (stopped) return
        pause(); stopped = true
        reg.handleLifecycleEvent(Lifecycle.Event.ON_STOP); reg.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}

private fun shaderFor(name: String): ShaderConfig = when (name) {
    "CRT" -> ShaderConfig.CRT; "LCD" -> ShaderConfig.LCD; "Sharp" -> ShaderConfig.Sharp; else -> ShaderConfig.Default
}

private val NO_AUTOSAVE = setOf("n64", "psp")

private fun activityOf(c: Context): Activity? { var x = c; while (x is ContextWrapper) { if (x is Activity) return x; x = x.baseContext }; return null }

/** Consoles that are wider than tall play best sideways; handhelds with a tall screen play best upright. */
private fun defaultRotation(s: ArcadeSystem) = if (s.id in setOf("gb", "nds", "ws")) "port" else "land"

/**
 * The game runs in its own full-screen window on top of everything: no Atlas tabs, no title bars, system bars hidden
 * (swipe from the edge to peek at them), and the screen turns to the way that console is best played.
 */
@Composable
fun ArcadePlayerWindow(game: ArcadeGame, onExit: () -> Unit) {
    var backTick by remember { mutableIntStateOf(0) }
    Dialog(onDismissRequest = { backTick++ },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            if (window != null) {
                WindowCompat.setDecorFitsSystemWindows(window, false)
                window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                window.setBackgroundDrawableResource(android.R.color.black)
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (android.os.Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
                val c = WindowInsetsControllerCompat(window, window.decorView)
                c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        ArcadePlayer(game, backTick, onExit)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArcadePlayer(game: ArcadeGame, backTick: Int, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val system = remember(game.id) { ArcadeSystems.byId(game.system)!! }
    if (!ArcadeSystems.coreReady(ctx, system) || !File(game.path).exists()) {
        Column(Modifier.fillMaxSize().background(Color.Black).padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(if (!File(game.path).exists()) "This game's file is gone. Remove it from the library and add it again." else "The ${system.name} emulator isn't included in this build.", color = Color.White)
            Spacer(Modifier.height(16.dp)); Button(onClick = onExit) { Text("Back to library") }
        }
        return
    }
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("arcade", 0) }
    val owner = remember(game.id) { PlayerLifecycle() }
    var menu by remember { mutableStateOf(false) }
    var shader by remember { mutableStateOf(prefs.getString("shader", "Default") ?: "Default") }
    var speed by remember { mutableIntStateOf(1) }
    var sound by remember { mutableStateOf(true) }
    var note by remember { mutableStateOf("") }
    var problem by remember { mutableStateOf("") }
    var pad by remember { mutableStateOf(prefs.getBoolean("pad", true)) }
    val activity = remember { activityOf(ctx) }
    val originalOrientation = remember { activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    var rotation by remember { mutableStateOf(prefs.getString("rot_" + system.id, null) ?: defaultRotation(system)) }
    LaunchedEffect(rotation) {
        activity?.requestedOrientation = when (rotation) {
            "land" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            "port" -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        }
    }
    DisposableEffect(Unit) { onDispose { activity?.requestedOrientation = originalOrientation } }
    LaunchedEffect(backTick) { if (backTick > 0) menu = !menu }
    val menuState = rememberUpdatedState(menu)
    val started = remember { System.currentTimeMillis() }
    var finished by remember { mutableStateOf(false) }
    val autoFile = remember(game.id) { File(ArcadeLibrary.statesDir(ctx), "${game.id}_auto.state") }
    val sramFile = remember(game.id) { File(ArcadeLibrary.savesDir(ctx), game.id + ".srm") }
    fun stateFile(slot: Int) = File(ArcadeLibrary.statesDir(ctx), "${game.id}_$slot.state")

    val view = remember(game.id) {
        if (system.id == "psp") ArcadeLibrary.ensurePspFiles(ctx)
        val d = GLRetroViewData(ctx).apply {
            coreFilePath = ArcadeSystems.corePath(ctx, system).absolutePath
            gameFilePath = game.path
            systemDirectory = ArcadeLibrary.systemDir(ctx).absolutePath
            savesDirectory = ArcadeLibrary.savesDir(ctx).absolutePath
            saveRAMState = sramFile.takeIf { it.exists() }?.readBytes()
            this.shader = shaderFor(prefs.getString("shader", "Default") ?: "Default")
        }
        prefs.edit().putString("launching", game.title + " (" + system.name + ")").commit()
        GLRetroView(ctx, d).apply { isFocusable = true; isFocusableInTouchMode = true }
    }

    val started1 = remember(game.id) { booleanArrayOf(false) }
    val savedFlag = remember(game.id) { booleanArrayOf(false) }
    // Notes how far closing got, so if the phone's emulator crashes while shutting down we can tell where (shown on the Arcade page).
    fun stage(s: String?) { prefs.edit().apply { if (s == null) remove("exit_stage") else putString("exit_stage", s) }.commit() }
    // Saves progress while the game is still on screen.
    fun saveProgress() {
        if (savedFlag[0]) return
        savedFlag[0] = true
        runCatching {
            stage("pausing"); owner.pause()
            if (started1[0]) {
                // PSP and N64 cores can't snapshot safely; PSP also has no battery save to write (its memory card is written by the core itself)
                if (system.id !in NO_AUTOSAVE) { stage("saving your place"); autoFile.writeBytes(view.serializeState(false)) }
                if (system.id != "psp") { stage("saving the game's memory"); sramFile.writeBytes(view.serializeSRAM(false)) }
            }
        }
    }
    fun leave() {
        if (finished) return
        finished = true
        saveProgress()
        val played = System.currentTimeMillis() - started
        ArcadeLibrary.update(ctx, game.id) { it.last = System.currentTimeMillis(); it.playMs += played }
        // shut the emulator down only after its picture has left the screen, so nothing is still drawing from it
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            stage("stopping the emulator"); owner.stop()
            stage(null); prefs.edit().remove("launching").apply()
        }, 300)
    }

    DisposableEffect(view) {
        owner.lifecycle.addObserver(view)
        owner.start()
        view.requestFocus()
        onDispose { leave() }
    }
    val host = LocalLifecycleOwner.current
    DisposableEffect(host) {
        val o = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_PAUSE) owner.pause()
            else if (e == Lifecycle.Event.ON_RESUME && !menuState.value) owner.resume()
        }
        host.lifecycle.addObserver(o)
        onDispose { host.lifecycle.removeObserver(o) }
    }
    LaunchedEffect(menu) { if (menu) owner.pause() else owner.resume() }
    LaunchedEffect(view) {
        launch { view.getGLRetroErrors().collect { problem = if (it == GLRetroView.ERROR_LOAD_LIBRARY) "This phone couldn't start the ${system.name} emulator." else "This game couldn't be started (error $it)." } }
        view.getGLRetroEvents().first { it is GLRetroView.GLRetroEvents.SurfaceCreated }
        delay(600)
        if (problem.isEmpty()) started1[0] = true
        delay(1200); prefs.edit().remove("launching").apply()
        if (system.id !in NO_AUTOSAVE && autoFile.exists()) {
            val ok = runCatching { view.unserializeState(autoFile.readBytes()) }.getOrDefault(false)
            if (ok) note = "Back where you left off"
        }
    }
    LaunchedEffect(note) { if (note.isNotEmpty()) { delay(2200); note = "" } }


    fun key(code: Int, down: Boolean) { view.sendKeyEvent(if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, code) }
    val landscape = LocalConfiguration.current.screenWidthDp > LocalConfiguration.current.screenHeightDp
    val cfg = LocalConfiguration.current
    val scale = (minOf(cfg.screenWidthDp, cfg.screenHeightDp) / 400f).coerceIn(0.78f, 1.3f)
    val controls: @Composable (Modifier) -> Unit = { m ->
        if (pad) GamePad(system, landscape, scale, m, ::key) { src, x, y -> view.sendMotionEvent(src, x, y) }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val padHeight = if (!landscape && pad) ((if (system.shoulders > 0) 300 else 270) * scale).dp else 0.dp
        AndroidView({ view }, if (landscape) Modifier.fillMaxSize() else Modifier.fillMaxSize().displayCutoutPadding().statusBarsPadding().padding(bottom = padHeight))
        if (landscape) controls(Modifier.fillMaxSize().displayCutoutPadding().navigationBarsPadding().alpha(0.62f))
        else controls(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(padHeight).navigationBarsPadding())
        FilledTonalIconButton(onClick = { menu = true }, modifier = Modifier.align(if (landscape) Alignment.TopCenter else Alignment.TopEnd).displayCutoutPadding().statusBarsPadding().padding(10.dp).size(40.dp).alpha(0.7f)) {
            androidx.compose.material3.Icon(Icons.Filled.Menu, "Menu")
        }
        if (note.isNotEmpty()) Text(note, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 14.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xCC000000)).padding(horizontal = 16.dp, vertical = 8.dp), color = Color.White, fontSize = 13.sp)
        if (problem.isNotEmpty()) Column(Modifier.align(Alignment.Center).padding(32.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xEE111111)).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(problem, color = Color.White); Spacer(Modifier.height(14.dp)); Button(onClick = { saveProgress(); onExit() }) { Text("Back to library") }
        }
    }

    if (menu) Box(Modifier.fillMaxSize().background(Color(0xAA000000)).clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { menu = false }, contentAlignment = Alignment.Center) {
      Surface(onClick = {}, color = Color(0xFF16161C), contentColor = Color.White, shape = RoundedCornerShape(24.dp), modifier = Modifier.widthIn(max = 440.dp).heightIn(max = (cfg.screenHeightDp - 16).dp).padding(8.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(game.title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Button(onClick = { menu = false }, Modifier.fillMaxWidth()) { Text("Resume") }
            Text("Save states", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (slot in 1..3) {
                    val has = stateFile(slot).exists()
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { scope.launch(Dispatchers.Default) { runCatching { stateFile(slot).writeBytes(view.serializeState(false)) }.onSuccess { note = "Saved to slot $slot" }.onFailure { note = "Couldn't save" } }; menu = false }, Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(4.dp)) { Text("Save $slot", fontSize = 12.sp) }
                        OutlinedButton(onClick = { if (has) { scope.launch(Dispatchers.Default) { val ok = runCatching { view.unserializeState(stateFile(slot).readBytes(), false) }.getOrDefault(false); note = if (ok) "Loaded slot $slot" else "Couldn't load" }; menu = false } }, Modifier.fillMaxWidth(), enabled = has,
                            contentPadding = PaddingValues(4.dp)) { Text("Load $slot", fontSize = 12.sp) }
                    }
                }
            }
            Text("Speed", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf(1, 2, 3, 4).forEach { s -> FilterChip(speed == s, { speed = s; view.frameSpeed = s }, label = { Text("${s}x") }) } }
            Text("Screen look", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("Default", "Sharp", "CRT", "LCD").forEach { s -> FilterChip(shader == s, { shader = s; view.shader = shaderFor(s); prefs.edit().putString("shader", s).apply() }, label = { Text(s) }) } }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Sound", Modifier.weight(1f)); Switch(sound, { sound = it; view.audioEnabled = it }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("On-screen controls", Modifier.weight(1f)); Switch(pad, { pad = it; prefs.edit().putBoolean("pad", it).apply() }) }
            Text("Screen turning", fontSize = 13.sp, color = Color.White.copy(alpha = 0.6f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("land" to "Sideways", "port" to "Upright", "auto" to "Free").forEach { (k, label) -> FilterChip(rotation == k, { rotation = k; prefs.edit().putString("rot_" + system.id, k).apply() }, label = { Text(label) }) } }
            OutlinedButton(onClick = { runCatching { view.reset(false) }; menu = false }, Modifier.fillMaxWidth()) { Text("Restart game") }
            Button(onClick = { saveProgress(); onExit() }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB3261E))) { Text("Save and exit") }
        }
      }
    }
}

// ───────────────────────── on-screen controls ─────────────────────────

private const val UP = KeyEvent.KEYCODE_DPAD_UP
private const val DOWN = KeyEvent.KEYCODE_DPAD_DOWN
private const val LEFT = KeyEvent.KEYCODE_DPAD_LEFT
private const val RIGHT = KeyEvent.KEYCODE_DPAD_RIGHT

@Composable
private fun HoldButton(label: String, size: Dp, modifier: Modifier = Modifier, wide: Boolean = false, color: Color = Color(0xFF2B2B33), onKey: (Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box(modifier.then(if (wide) Modifier.size(size * 1.6f, size * 0.62f) else Modifier.size(size))
        .clip(if (wide) RoundedCornerShape(50) else CircleShape).background(if (down) color.copy(alpha = 1f).let { Color(0xFF8E97B8) } else color)
        .border(1.5.dp, Color.White.copy(alpha = 0.28f), if (wide) RoundedCornerShape(50) else CircleShape)
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                down = true; haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); onKey(true)
                do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
                down = false; onKey(false)
            }
        }, contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (wide) 12.sp else (size.value * 0.36f).sp)
    }
}

@Composable
private fun DPad(size: Dp, onKey: (Int, Boolean) -> Unit) {
    val held = remember { mutableStateMapOf<Int, Boolean>() }
    fun set(code: Int, v: Boolean) { if ((held[code] ?: false) != v) { held[code] = v; onKey(code, v) } }
    Box(Modifier.size(size).clip(CircleShape).background(Color(0xFF22222A)).border(1.5.dp, Color.White.copy(alpha = 0.22f), CircleShape)
        .pointerInput(Unit) {
            awaitEachGesture {
                val first = awaitFirstDown(requireUnconsumed = false)
                val c = Offset(this.size.width / 2f, this.size.height / 2f)
                fun update(p: Offset?) {
                    if (p == null) { listOf(UP, DOWN, LEFT, RIGHT).forEach { set(it, false) }; return }
                    val dx = (p.x - c.x) / c.x; val dy = (p.y - c.y) / c.y
                    val dead = 0.22f
                    set(LEFT, dx < -dead && abs(dx) > abs(dy) * 0.5f); set(RIGHT, dx > dead && abs(dx) > abs(dy) * 0.5f)
                    set(UP, dy < -dead && abs(dy) > abs(dx) * 0.5f); set(DOWN, dy > dead && abs(dy) > abs(dx) * 0.5f)
                }
                update(first.position)
                do { val e = awaitPointerEvent(); update(e.changes.firstOrNull { it.pressed }?.position) } while (e.changes.any { it.pressed })
                update(null)
            }
        }, contentAlignment = Alignment.Center) {
        val on = Color(0xFF8E97B8); val off = Color.White.copy(alpha = 0.5f)
        Text("▲", Modifier.align(Alignment.TopCenter).padding(top = 8.dp), color = if (held[UP] == true) on else off, fontSize = 20.sp)
        Text("▼", Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp), color = if (held[DOWN] == true) on else off, fontSize = 20.sp)
        Text("◀", Modifier.align(Alignment.CenterStart).padding(start = 10.dp), color = if (held[LEFT] == true) on else off, fontSize = 20.sp)
        Text("▶", Modifier.align(Alignment.CenterEnd).padding(end = 10.dp), color = if (held[RIGHT] == true) on else off, fontSize = 20.sp)
    }
}

@Composable
private fun Stick(size: Dp, source: Int, onMove: (Int, Float, Float) -> Unit) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    Box(Modifier.size(size).clip(CircleShape).background(Color(0xFF22222A)).border(1.5.dp, Color.White.copy(alpha = 0.22f), CircleShape)
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                val r = this.size.width / 2f
                val c = Offset(r, r)
                do {
                    val e = awaitPointerEvent()
                    val p = e.changes.firstOrNull { it.pressed }?.position
                    if (p != null) {
                        var dx = (p.x - c.x) / r; var dy = (p.y - c.y) / r
                        val len = sqrt(dx * dx + dy * dy); if (len > 1f) { dx /= len; dy /= len }
                        knob = Offset(dx, dy); onMove(source, dx, dy)
                    }
                } while (e.changes.any { it.pressed })
                knob = Offset.Zero; onMove(source, 0f, 0f)
            }
        }, contentAlignment = Alignment.Center) {
        val half = size.value * 0.5f
        Box(Modifier.offset((knob.x * half * 0.55f).dp, (knob.y * half * 0.55f).dp).size(size * 0.42f).clip(CircleShape).background(Color(0xFF8E97B8)))
    }
}

@Composable
private fun GamePad(system: ArcadeSystem, landscape: Boolean, u: Float, modifier: Modifier, key: (Int, Boolean) -> Unit, motion: (Int, Float, Float) -> Unit) {
    val A = KeyEvent.KEYCODE_BUTTON_A; val B = KeyEvent.KEYCODE_BUTTON_B; val X = KeyEvent.KEYCODE_BUTTON_X; val Y = KeyEvent.KEYCODE_BUTTON_Y
    val face = 62.dp * u
    Box(modifier) {
        // shoulders along the top
        if (system.shoulders > 0) {
            Row(Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (system.id == "n64") HoldButton("Z", 44.dp * u, wide = true) { key(KeyEvent.KEYCODE_BUTTON_L2, it) }
                if (system.shoulders == 2) HoldButton("L2", 44.dp * u, wide = true) { key(KeyEvent.KEYCODE_BUTTON_L2, it) }
                HoldButton(if (system.shoulders == 2) "L1" else "L", 44.dp * u, wide = true) { key(KeyEvent.KEYCODE_BUTTON_L1, it) }
            }
            Row(Modifier.align(Alignment.TopEnd).padding(end = 16.dp, top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HoldButton(if (system.shoulders == 2) "R1" else "R", 44.dp * u, wide = true) { key(KeyEvent.KEYCODE_BUTTON_R1, it) }
                if (system.shoulders == 2) HoldButton("R2", 44.dp * u, wide = true) { key(KeyEvent.KEYCODE_BUTTON_R2, it) }
            }
        }
        // left side: stick or d-pad
        Box(Modifier.align(if (landscape) Alignment.CenterStart else Alignment.CenterStart).padding(start = 22.dp, top = if (system.shoulders > 0) 34.dp else 0.dp)) {
            if (system.analog && system.id != "n64") Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Stick(112.dp * u, GLRetroView.MOTION_SOURCE_ANALOG_LEFT, motion); Spacer(Modifier.height(10.dp)); DPad(86.dp * u, key)
            } else if (system.id == "n64") Stick(130.dp * u, GLRetroView.MOTION_SOURCE_ANALOG_LEFT, motion) else DPad(138.dp * u, key)
        }
        // right side: face buttons
        Box(Modifier.align(Alignment.CenterEnd).padding(end = 18.dp, top = if (system.shoulders > 0) 34.dp else 0.dp)) {
            when (system.layout) {
                ArcadeSystem.Layout.ONE -> HoldButton("FIRE", 78.dp * u) { key(A, it) }
                ArcadeSystem.Layout.TWO, ArcadeSystem.Layout.GBA -> Box(Modifier.size(150.dp * u, 118.dp * u)) {
                    HoldButton("B", face, Modifier.align(Alignment.BottomStart)) { key(A, it) }
                    HoldButton("A", face, Modifier.align(Alignment.TopEnd)) { key(B, it) }
                }
                ArcadeSystem.Layout.SEGA3 -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
                    HoldButton("A", 54.dp * u, Modifier.padding(bottom = 0.dp)) { key(X, it) }
                    HoldButton("B", 54.dp * u, Modifier.padding(bottom = 22.dp)) { key(A, it) }
                    HoldButton("C", 54.dp * u, Modifier.padding(bottom = 44.dp)) { key(B, it) }
                }
                ArcadeSystem.Layout.NINTENDO4 -> Diamond(face, "X", "B", "Y", "A", { key(Y, it) }, { key(A, it) }, { key(X, it) }, { key(B, it) })
                ArcadeSystem.Layout.PLAYSTATION -> Diamond(face, "△", "✕", "□", "○", { key(Y, it) }, { key(A, it) }, { key(X, it) }, { key(B, it) })
                ArcadeSystem.Layout.N64 -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        HoldButton("B", 54.dp * u) { key(A, it) }; HoldButton("A", 54.dp * u) { key(B, it) }
                    }
                    Spacer(Modifier.height(10.dp))
                    Stick(104.dp * u, GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, motion)
                }
            }
        }
        if (system.rightStick && system.layout == ArcadeSystem.Layout.PLAYSTATION) {
            if (landscape) Box(Modifier.align(Alignment.BottomEnd).padding(end = 150.dp * u, bottom = 8.dp)) { Stick(76.dp * u, GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, motion) }
            else Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) { Stick(76.dp * u, GLRetroView.MOTION_SOURCE_ANALOG_RIGHT, motion) }
        }
        // start / select: tucked into the bottom corners sideways, centred upright
        if (landscape) {
            Box(Modifier.align(Alignment.BottomStart).padding(start = 22.dp, bottom = 10.dp)) { HoldButton("SELECT", 34.dp * u, wide = true) { key(KeyEvent.KEYCODE_BUTTON_SELECT, it) } }
            Box(Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 10.dp)) { HoldButton("START", 34.dp * u, wide = true) { key(KeyEvent.KEYCODE_BUTTON_START, it) } }
        } else Row(Modifier.align(Alignment.BottomCenter).padding(bottom = if (system.rightStick && system.layout == ArcadeSystem.Layout.PLAYSTATION) 90.dp else 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            HoldButton("SELECT", 40.dp * u, wide = true) { key(KeyEvent.KEYCODE_BUTTON_SELECT, it) }
            HoldButton("START", 40.dp * u, wide = true) { key(KeyEvent.KEYCODE_BUTTON_START, it) }
        }
    }
}

@Composable
private fun Diamond(size: Dp, north: String, south: String, west: String, east: String, n: (Boolean) -> Unit, s: (Boolean) -> Unit, w: (Boolean) -> Unit, e: (Boolean) -> Unit) {
    Box(Modifier.size(size * 2.45f, size * 2.45f)) {
        HoldButton(north, size, Modifier.align(Alignment.TopCenter), onKey = n)
        HoldButton(south, size, Modifier.align(Alignment.BottomCenter), onKey = s)
        HoldButton(west, size, Modifier.align(Alignment.CenterStart), onKey = w)
        HoldButton(east, size, Modifier.align(Alignment.CenterEnd), onKey = e)
    }
}
