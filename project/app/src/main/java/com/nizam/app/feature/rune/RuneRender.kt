package com.nizam.app.feature.rune

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Selecting nodes in the studio preview. When null the design runs normally. */
class EditHooks(val selectedId: String?, val onSelect: (String) -> Unit)

class RuneEnv(
    val theme: RuneTheme,
    val vars: SnapshotStateMap<String, String>,
    val metrics: SnapshotStateMap<String, String>,
    val tick: State<Long>,
    val ctx: Context,
    val open: (String) -> Unit,
    val scope: CoroutineScope,
    val edit: EditHooks?,
    val replay: Int,
    val item: String? = null,
    val index: Int = 0
) {
    fun withItem(i: String, n: Int) = RuneEnv(theme, vars, metrics, tick, ctx, open, scope, edit, replay, i, n)
}

/** {{time}} {{date}} {{greeting}} {{battery}} {{m:tasks_open}} {{v:name}} {{item}} … */
object RuneBind {
    private val rx = Regex("\\{\\{([^}]+)\\}\\}")

    fun resolve(s: String, env: RuneEnv): String {
        if (!s.contains("{{")) return s
        env.tick.value
        return rx.replace(s) { m -> value(m.groupValues[1].trim(), env) }
    }

    private fun value(k: String, env: RuneEnv): String {
        val now = LocalDateTime.now()
        val loc = Locale.getDefault()
        return when {
            k == "time" -> now.format(DateTimeFormatter.ofPattern("HH:mm", loc))
            k == "time12" -> now.format(DateTimeFormatter.ofPattern("h:mm a", loc))
            k == "hour" -> now.format(DateTimeFormatter.ofPattern("HH", loc))
            k == "minute" -> now.format(DateTimeFormatter.ofPattern("mm", loc))
            k == "sec" -> now.format(DateTimeFormatter.ofPattern("ss", loc))
            k == "date" -> now.format(DateTimeFormatter.ofPattern("EEE, d MMM", loc))
            k == "day" -> now.format(DateTimeFormatter.ofPattern("EEEE", loc))
            k == "month" -> now.format(DateTimeFormatter.ofPattern("MMMM", loc))
            k == "year" -> now.year.toString()
            k == "hijri" -> runCatching { HijrahDate.now().let { DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.ENGLISH).format(it) } }.getOrDefault("")
            k == "greeting" -> when (now.hour) { in 4..11 -> "Good morning"; in 12..16 -> "Good afternoon"; in 17..20 -> "Good evening"; else -> "Good night" }
            k == "battery" -> battery(env.ctx).toString()
            k == "charging" -> if (charging(env.ctx)) "charging" else ""
            k == "item" -> env.item.orEmpty()
            k == "index" -> env.index.toString()
            k == "n" -> (env.index + 1).toString()
            k.startsWith("m:") -> env.metrics[k.substring(2)] ?: "0"
            k.startsWith("v:") -> env.vars[k.substring(2)] ?: ""
            else -> env.vars[k] ?: ""
        }
    }

    private fun batteryIntent(c: Context): Intent? = runCatching { c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
    fun battery(c: Context): Int { val i = batteryIntent(c) ?: return 0; val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0); val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100); return if (s > 0) l * 100 / s else 0 }
    fun charging(c: Context): Boolean { val i = batteryIntent(c) ?: return false; val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1); return st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL }

    fun truthy(s: String): Boolean = s.isNotBlank() && s != "0" && s != "false" && s != "no"

    fun visible(cond: String, env: RuneEnv): Boolean {
        val c = cond.trim()
        if (c.isEmpty()) return true
        fun side(x: String): String { val t = x.trim(); val r = resolve(t, env); return if (r == t) (env.vars[t] ?: env.metrics[t] ?: t) else r }
        return when {
            c.contains("==") -> side(c.substringBefore("==")) == side(c.substringAfter("=="))
            c.contains("!=") -> side(c.substringBefore("!=")) != side(c.substringAfter("!="))
            c.contains(">") -> (side(c.substringBefore(">")).toFloatOrNull() ?: 0f) > (side(c.substringAfter(">")).toFloatOrNull() ?: 0f)
            c.contains("<") -> (side(c.substringBefore("<")).toFloatOrNull() ?: 0f) < (side(c.substringAfter("<")).toFloatOrNull() ?: 0f)
            c.startsWith("!") -> !truthy(side(c.substring(1)))
            else -> truthy(side(c))
        }
    }
}

/** What a tap does. Several actions can be chained with ;; */
object RuneActions {
    fun run(spec: String, env: RuneEnv) {
        spec.split(";;").map { it.trim() }.filter { it.isNotEmpty() }.forEach { one(RuneBind.resolve(it, env), env) }
    }

    private fun one(a: String, env: RuneEnv) {
        val c = env.ctx
        val head = a.substringBefore(':'); val arg = a.substringAfter(':', "")
        when (head) {
            "open" -> if (arg.isNotBlank()) env.open(if (arg.contains('/')) arg else "nx/$arg")
            "url" -> com.nizam.app.feature.nx.Share.open(c, arg)
            "copy" -> runCatching { (c.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Atlas", arg)); toast(c, "Copied") }
            "share" -> com.nizam.app.feature.nx.Share.text(c, arg)
            "set" -> env.vars[arg.substringBefore('=').trim()] = arg.substringAfter('=', "")
            "toggle" -> env.vars[arg.trim()] = if (RuneBind.truthy(env.vars[arg.trim()].orEmpty())) "0" else "1"
            "inc" -> { val k = arg.substringBefore(':').trim(); val by = arg.substringAfter(':', "1").toFloatOrNull() ?: 1f
                val cur = env.vars[k]?.toFloatOrNull() ?: 0f; val n = cur + by
                env.vars[k] = if (n == n.toInt().toFloat()) n.toInt().toString() else n.toString() }
            "toast" -> toast(c, arg)
            "haptic" -> buzz(c)
            "ask" -> env.scope.launch {
                env.vars["answer"] = "…"
                env.vars["answer"] = com.nizam.app.feature.omni.O.ai(c, arg, "Answer briefly and plainly, in under 60 words.", 300)
            }
        }
    }

    fun toast(c: Context, s: String) { android.widget.Toast.makeText(c, s, android.widget.Toast.LENGTH_SHORT).show() }

    fun buzz(c: Context) {
        runCatching { (c.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).vibrate(VibrationEffect.createOneShot(18, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }
}

object RuneIconMap {
    fun vector(n: String): ImageVector = when (n) {
        "star" -> Icons.Outlined.Star; "heart" -> Icons.Outlined.FavoriteBorder; "home" -> Icons.Outlined.Home; "bolt" -> Icons.Outlined.Bolt
        "moon" -> Icons.Outlined.NightsStay; "sun" -> Icons.Outlined.WbSunny; "cloud" -> Icons.Outlined.Cloud; "mosque" -> Icons.Outlined.Mosque
        "book" -> Icons.Outlined.MenuBook; "check" -> Icons.Outlined.Check; "add" -> Icons.Outlined.Add; "close" -> Icons.Outlined.Close
        "search" -> Icons.Outlined.Search; "settings" -> Icons.Outlined.Settings; "person" -> Icons.Outlined.Person; "group" -> Icons.Outlined.Groups
        "wallet" -> Icons.Outlined.AccountBalanceWallet; "chart" -> Icons.Outlined.BarChart; "timer" -> Icons.Outlined.Timer; "alarm" -> Icons.Outlined.Alarm
        "water" -> Icons.Outlined.WaterDrop; "fitness" -> Icons.Outlined.FitnessCenter; "food" -> Icons.Outlined.Restaurant; "music" -> Icons.Outlined.MusicNote
        "play" -> Icons.Outlined.PlayArrow; "pause" -> Icons.Outlined.Pause; "camera" -> Icons.Outlined.CameraAlt; "mail" -> Icons.Outlined.Mail
        "chat" -> Icons.Outlined.ChatBubbleOutline; "phone" -> Icons.Outlined.Phone; "map" -> Icons.Outlined.Map; "lock" -> Icons.Outlined.Lock
        "bell" -> Icons.Outlined.Notifications; "fire" -> Icons.Outlined.LocalFireDepartment; "leaf" -> Icons.Outlined.Eco; "gift" -> Icons.Outlined.CardGiftcard
        "flag" -> Icons.Outlined.Flag; "bulb" -> Icons.Outlined.Lightbulb; "rocket" -> Icons.Outlined.RocketLaunch; "palette" -> Icons.Outlined.Palette
        else -> Icons.Outlined.Star
    }
}

/** Live Atlas numbers a design can show with {{m:name}}. */
object RuneData {
    suspend fun load(ctx: Context): Map<String, String> = runCatching {
        val c = com.nizam.app.feature.omni.O.container(ctx)
        val d = com.nizam.app.feature.omni.O.day(c, LocalDate.now())
        val out = HashMap<String, String>()
        d.keys().forEach { k ->
            val v = d.opt(k)
            out[k] = when (v) { is Double -> if (v == v.toInt().toDouble()) v.toInt().toString() else "%.1f".format(v); else -> v.toString() }
        }
        out
    }.getOrDefault(emptyMap())
}

// ───────────────────────── the renderer ─────────────────────────

@Composable
fun appRuneTheme(): RuneTheme {
    val m = androidx.compose.material3.MaterialTheme.colorScheme
    return RuneTheme(m.background, m.surfaceVariant, m.onSurface, m.onSurfaceVariant, m.primary, m.secondary, RuneColor.isLight(m.background).not())
}

/** Runs one design. [edit] non-null turns taps into selection (the studio preview). */
@Composable
fun RuneView(doc: JSONObject, open: (String) -> Unit, modifier: Modifier = Modifier, edit: EditHooks? = null, replay: Int = 0) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val appTheme = appRuneTheme()
    val theme = remember(doc.optJSONObject("theme")?.toString(), appTheme) { RuneTheme.fromJson(doc.optJSONObject("theme")) ?: appTheme }
    val varsKey = doc.optJSONObject("vars")?.toString().orEmpty()
    val vars = remember(varsKey) { mutableStateMapOf<String, String>().also { m -> doc.optJSONObject("vars")?.let { o -> o.keys().forEach { k -> m[k] = o.optString(k) } } } }
    val metrics = remember { mutableStateMapOf<String, String>() }
    val tickState = remember { mutableStateOf(0L) }
    val needsFast = remember(doc) { doc.toString().let { it.contains("{{time") || it.contains("{{sec") || it.contains("{{minute") } }
    LaunchedEffect(needsFast) { while (true) { delay(if (needsFast) 1000L else 30000L); tickState.value = System.currentTimeMillis() } }
    LaunchedEffect(Unit) { while (true) { RuneData.load(ctx).forEach { (k, v) -> metrics[k] = v }; delay(60000L) } }
    val env = remember(theme, vars, edit, replay, open) { RuneEnv(theme, vars, metrics, tickState, ctx, open, scope, edit, replay) }
    val root = doc.optJSONObject("root") ?: return
    Box(modifier) { RuneNodeView(root, env, 0, Modifier) }
}

private fun JSONObject.f(k: String, d: Float): Float = if (has(k)) optDouble(k, d.toDouble()).toFloat() else d
private fun JSONObject.b(k: String, d: Boolean = false): Boolean = if (has(k)) optBoolean(k, d) else d
private fun JSONObject.s(k: String, d: String = ""): String = if (has(k)) optString(k, d) else d

private fun hAlign(a: String) = when (a) { "center" -> Alignment.CenterHorizontally; "end" -> Alignment.End; else -> Alignment.Start }
private fun vAlign(a: String) = when (a) { "center" -> Alignment.CenterVertically; "end" -> Alignment.Bottom; else -> Alignment.Top }
private fun boxAlign(a: String) = when (a) {
    "topstart" -> Alignment.TopStart; "top" -> Alignment.TopCenter; "topend" -> Alignment.TopEnd; "start" -> Alignment.CenterStart
    "center" -> Alignment.Center; "end" -> Alignment.CenterEnd; "bottomstart" -> Alignment.BottomStart; "bottom" -> Alignment.BottomCenter
    "bottomend" -> Alignment.BottomEnd; else -> Alignment.TopStart
}

private fun vArr(just: String, gap: Float) = when (just) {
    "center" -> Arrangement.spacedBy(gap.dp, Alignment.CenterVertically); "end" -> Arrangement.spacedBy(gap.dp, Alignment.Bottom)
    "between" -> Arrangement.SpaceBetween; "around" -> Arrangement.SpaceAround; "evenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.spacedBy(gap.dp)
}
private fun hArr(just: String, gap: Float) = when (just) {
    "center" -> Arrangement.spacedBy(gap.dp, Alignment.CenterHorizontally); "end" -> Arrangement.spacedBy(gap.dp, Alignment.End)
    "between" -> Arrangement.SpaceBetween; "around" -> Arrangement.SpaceAround; "evenly" -> Arrangement.SpaceEvenly
    else -> Arrangement.spacedBy(gap.dp)
}

private fun fontFamily(n: String): FontFamily? = when (n) { "serif" -> FontFamily.Serif; "mono" -> FontFamily.Monospace; "cursive" -> FontFamily.Cursive; else -> null }

private fun textColor(n: JSONObject, env: RuneEnv, def: Color): Color = n.s("color").takeIf { it.isNotBlank() }?.let { FillSpec.color(it, env.theme) } ?: def

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RuneNodeView(n: JSONObject, env: RuneEnv, index: Int, outer: Modifier, staggerMs: Int = 0) {
    val t = n.s("t")
    val show = n.s("showIf")
    if (show.isNotEmpty() && env.edit == null && !RuneBind.visible(show, env)) return

    val th = env.theme
    val still = RuneMotionPrefs.still
    val radiusDef = when (t) { "card" -> 22f; "button" -> 16f; "chip" -> 999f; "shape" -> 24f; "image" -> 0f; else -> 0f }
    val r = n.f("radius", radiusDef) * th.radius
    val shape: Shape = if (t == "shape") when (n.s("shape", "circle")) {
        "circle", "ring" -> CircleShape
        "pill" -> RoundedCornerShape(50)
        "blob" -> RoundedCornerShape(topStartPercent = 62, topEndPercent = 38, bottomEndPercent = 70, bottomStartPercent = 30)
        "diamond" -> RoundedCornerShape(4.dp)
        else -> RoundedCornerShape(r.dp)
    } else RoundedCornerShape(r.dp)

    var fillSpec = n.s("fill", when (t) { "card" -> "@card"; "chip" -> "@primary/20"; "button" -> "@primary"; else -> "" })
    var borderW = n.f("borderW", 0f); var borderC = n.s("border")
    var fx = n.s("fx")
    if (n.b("glass")) {
        if (!n.has("fill")) fillSpec = if (th.dark) "#FFFFFF/10" else "#FFFFFF/60"
        if (borderW <= 0f) borderW = 1f
        if (borderC.isEmpty()) borderC = if (th.dark) "#FFFFFF/20" else "#FFFFFF/80"
        if (fx.isEmpty()) fx = "frost"
    }
    if (t == "shape" && n.s("shape") == "ring") { if (borderW <= 0f) borderW = 6f; if (!n.has("fill")) fillSpec = "none"; if (borderC.isEmpty()) borderC = "@primary" }

    val src = remember { MutableInteractionSource() }
    val selected = env.edit?.selectedId == n.s("id")
    val tap = n.s("on"); val hold = n.s("onLong")
    val glowC = n.s("glowC").takeIf { it.isNotBlank() }?.let { FillSpec.color(it, th) } ?: th.primary
    val speed = n.f("fxSpeed", 1f) * RuneMotionPrefs.speed
    val interactive = env.edit != null || tap.isNotEmpty() || hold.isNotEmpty() || t == "button"

    var m: Modifier = outer
    val margin = n.f("margin", 0f); if (margin > 0f) m = m.padding(margin.dp)
    if (n.b("fillW")) m = m.fillMaxWidth(); if (n.b("fillH")) m = m.fillMaxHeight()
    n.f("w", 0f).takeIf { it > 0f }?.let { m = m.width(it.dp) }
    n.f("h", 0f).takeIf { it > 0f }?.let { m = m.height(it.dp) }
    n.f("aspect", 0f).takeIf { it > 0f }?.let { m = m.aspectRatio(it) }
    if (t == "shape") { if (!n.has("w") && !n.has("fillW")) m = m.width(120.dp); if (!n.has("h") && !n.has("fillH") && !n.has("aspect")) m = m.height(120.dp) }
    if (n.has("offX") || n.has("offY")) m = m.offset(n.f("offX", 0f).dp, n.f("offY", 0f).dp)
    val sc = n.f("scale", 1f); val rot = n.f("rotate", 0f) + if (t == "shape" && n.s("shape") == "diamond") 45f else 0f; val al = n.f("alpha", 1f)
    if (sc != 1f || rot != 0f || al != 1f) m = m.graphicsLayer { scaleX = sc; scaleY = sc; rotationZ = rot; alpha = al }
    m = m.runeLoop(n).runeEnter(n, staggerMs, env.replay)
    m = m.runePress(n.s("press").ifEmpty { if (t == "button") "squish" else "" }, src, n.s("spring").ifEmpty { null })
    val glow = n.f("glow", 0f); if (glow > 0f && !still) m = m.runeGlow(glowC, glow, shape)
    val elev = n.f("elev", 0f); if (elev > 0f) m = m.runeGlow(Color.Black, elev, shape)
    if (n.f("blur", 0f) > 0f) m = m.blur(n.f("blur", 0f).dp)
    if (fillSpec.isNotEmpty()) m = m.runeFill(fillSpec, shape, th, speed, still)
    if (fx.isNotEmpty() && !still) m = m.runeFx(fx, shape, th, speed, still)
    if (n.b("clip") || t == "image") m = m.clip(shape)
    if (borderW > 0f) m = m.border(borderW.dp, FillSpec.color(borderC.ifEmpty { "@ink/20" }, th), shape)
    if (interactive) {
        m = m.combinedClickable(interactionSource = src, indication = null,
            onLongClick = { if (env.edit == null && hold.isNotEmpty()) { RuneActions.buzz(env.ctx); RuneActions.run(hold, env) } },
            onClick = {
                val e = env.edit
                if (e != null) e.onSelect(n.s("id"))
                else { if (t == "button" || n.has("press")) RuneActions.buzz(env.ctx); if (tap.isNotEmpty()) RuneActions.run(tap, env) }
            })
    }
    if (selected) m = m.drawWithContent {
        drawContent()
        drawRect(th.primary, style = Stroke(3.dp.toPx()))
        drawRect(th.primary.copy(alpha = 0.10f))
    }
    n.s("desc").takeIf { it.isNotBlank() }?.let { d -> m = m.semantics { contentDescription = d } }
    val padAll = n.f("padding", if (t == "card") 16f else 0f)
    m = m.padding(horizontal = n.f("padH", padAll).dp, vertical = n.f("padV", padAll).dp)

    val kids = RuneModel.kids(n)
    val gap = n.f("gap", if (t == "card") 10f else 0f)
    val stagger = n.f("stagger", 0f).toInt()

    when (t) {
        "column", "card" -> {
            val sm = if (n.b("scroll")) m.verticalScroll(rememberScrollState()) else m
            Column(sm, vArr(n.s("justify"), gap), hAlign(n.s("align", if (t == "card") "start" else "start"))) {
                kids.forEachIndexed { i, k -> RuneNodeView(k, env, i, k.f("weightF", 0f).let { w -> if (w > 0f) Modifier.weight(w) else Modifier }, i * stagger) }
            }
        }
        "vscroll" -> Column(m.verticalScroll(rememberScrollState()), Arrangement.spacedBy(gap.dp), hAlign(n.s("align"))) {
            kids.forEachIndexed { i, k -> RuneNodeView(k, env, i, k.f("weightF", 0f).let { w -> if (w > 0f) Modifier.weight(w) else Modifier }, i * stagger) }
        }
        "row" -> Row(m, hArr(n.s("justify"), gap), vAlign(n.s("align"))) {
            kids.forEachIndexed { i, k -> RuneNodeView(k, env, i, k.f("weightF", 0f).let { w -> if (w > 0f) Modifier.weight(w) else Modifier }, i * stagger) }
        }
        "hscroll" -> Row(m.horizontalScroll(rememberScrollState()), Arrangement.spacedBy(gap.dp), vAlign(n.s("align"))) {
            kids.forEachIndexed { i, k -> RuneNodeView(k, env, i, Modifier, i * stagger) }
        }
        "box" -> Box(m, boxAlign(n.s("boxAlign", "topstart"))) {
            kids.forEachIndexed { i, k -> RuneNodeView(k, env, i, Modifier, i * stagger) }
        }
        "grid" -> {
            val cols = n.f("cols", 2f).toInt().coerceIn(1, 6)
            Column(m, Arrangement.spacedBy(gap.dp)) {
                kids.chunked(cols).forEachIndexed { ri, row ->
                    Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(gap.dp)) {
                        row.forEachIndexed { ci, k -> RuneNodeView(k, env, ri * cols + ci, Modifier.weight(1f), (ri * cols + ci) * stagger) }
                        repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        "repeat" -> {
            val tpl = kids.firstOrNull()
            val items = RuneBind.resolve(n.s("items"), env).split('|').map { it.trim() }.filter { it.isNotEmpty() }
            if (tpl != null) {
                if (n.s("dir") == "row") Row(m, hArr(n.s("justify"), gap), vAlign(n.s("align"))) {
                    items.forEachIndexed { i, it -> RuneNodeView(tpl, env.withItem(it, i), i, Modifier, i * stagger) }
                } else Column(m, vArr(n.s("justify"), gap), hAlign(n.s("align"))) {
                    items.forEachIndexed { i, it -> RuneNodeView(tpl, env.withItem(it, i), i, Modifier, i * stagger) }
                }
            }
        }
        "text" -> {
            val txt = RuneBind.resolve(n.s("text", "Text"), env).let { if (n.b("upper")) it.uppercase() else it }
            val size = n.f("size", 16f)
            val tfill = n.s("tfill")
            val ink = textColor(n, env, th.ink)
            val brush: Brush? = if (tfill.isNotEmpty()) FillSpec.parse(tfill, th)?.let { f -> if (f.colors.size >= 2) Brush.linearGradient(f.colors) else null } else null
            val base = TextStyle(
                fontSize = size.sp, fontWeight = FontWeight(n.f("weight", 400f).toInt().coerceIn(100, 900)),
                fontFamily = fontFamily(n.s("font")), fontStyle = if (n.b("italic")) FontStyle.Italic else FontStyle.Normal,
                letterSpacing = n.f("spacing", 0f).sp, lineHeight = if (n.has("lineH")) (size * n.f("lineH", 1.3f)).sp else TextStyle.Default.lineHeight,
                textAlign = when (n.s("talign")) { "center" -> TextAlign.Center; "end" -> TextAlign.End; else -> TextAlign.Start })
            val style = if (brush != null) base.copy(brush = brush) else base.copy(color = ink)
            val ml = n.f("maxLines", 0f).toInt()
            Text(txt, m, style = style, maxLines = if (ml > 0) ml else Int.MAX_VALUE, overflow = if (ml > 0) TextOverflow.Ellipsis else TextOverflow.Clip)
        }
        "icon" -> {
            val size = n.f("size", 28f)
            val ink = textColor(n, env, th.ink)
            val emoji = n.s("emoji")
            if (emoji.isNotEmpty()) Text(emoji, m, style = TextStyle(fontSize = size.sp))
            else Icon(RuneIconMap.vector(n.s("name", "star")), null, m.size(size.dp), ink)
        }
        "image" -> AsyncImage(RuneBind.resolve(n.s("url"), env), n.s("desc").ifEmpty { null }, m.then(if (n.has("w") || n.has("h") || n.has("fillW")) Modifier else Modifier.size(96.dp)), contentScale = ContentScale.Crop)
        "button" -> {
            val f = FillSpec.parse(fillSpec, th)
            val onFill = if (f != null && f.colors.isNotEmpty()) (if (RuneColor.isLight(f.colors[0])) Color(0xFF101114) else Color.White) else th.ink
            val ink = textColor(n, env, if (n.b("glass") || fillSpec == "none") th.ink else onFill)
            Box(m.padding(horizontal = if (n.has("padH") || n.has("padding")) 0.dp else 20.dp, vertical = if (n.has("padV") || n.has("padding")) 0.dp else 12.dp), Alignment.Center) {
                Text(RuneBind.resolve(n.s("text", "Button"), env).let { if (n.b("upper")) it.uppercase() else it },
                    style = TextStyle(color = ink, fontSize = n.f("size", 15f).sp, fontWeight = FontWeight(n.f("weight", 600f).toInt()), fontFamily = fontFamily(n.s("font")), letterSpacing = n.f("spacing", 0f).sp))
            }
        }
        "chip" -> Box(m.padding(horizontal = 12.dp, vertical = 6.dp), Alignment.Center) {
            Text(RuneBind.resolve(n.s("text", "Chip"), env).let { if (n.b("upper")) it.uppercase() else it },
                style = TextStyle(color = textColor(n, env, th.primary), fontSize = n.f("size", 12f).sp, fontWeight = FontWeight(n.f("weight", 600f).toInt()), letterSpacing = n.f("spacing", 0f).sp))
        }
        "progress" -> RuneProgress(n, env, m)
        "chart" -> RuneChart(n, env, m)
        "toggle" -> {
            val key = n.s("bind", "flag"); val on = RuneBind.truthy(env.vars[key].orEmpty())
            Row(m, Arrangement.spacedBy(10.dp), Alignment.CenterVertically) {
                if (n.s("text").isNotEmpty()) Text(RuneBind.resolve(n.s("text"), env), color = th.ink, fontSize = n.f("size", 15f).sp)
                Switch(on, { env.vars[key] = if (it) "1" else "0"; RuneActions.buzz(env.ctx) }, colors = SwitchDefaults.colors(checkedTrackColor = th.primary, checkedThumbColor = RuneColor.ensure(Color.White, th.primary, 1.5f), uncheckedTrackColor = th.card))
            }
        }
        "slider" -> {
            val key = n.s("bind", "level")
            Slider(env.vars[key]?.toFloatOrNull() ?: 0f, { env.vars[key] = "%.2f".format(Locale.US, it) }, m, colors = SliderDefaults.colors(thumbColor = th.primary, activeTrackColor = th.primary, inactiveTrackColor = th.card))
        }
        "input" -> {
            val key = n.s("bind", "text")
            OutlinedTextField(env.vars[key].orEmpty(), { env.vars[key] = it }, m, placeholder = { Text(n.s("hint", "Type…"), color = th.muted) }, singleLine = true)
        }
        "shape" -> Box(m)
        "divider" -> Box((if (n.has("w")) m else m.fillMaxWidth()).height(max(1f, n.f("h", 1f)).dp).background(FillSpec.color(n.s("fill", "@ink/14"), th)))
        "spacer" -> Spacer(m.then(if (n.has("w") || n.has("h")) Modifier else Modifier.size(12.dp)))
        else -> Unit
    }
}

@Composable
private fun RuneProgress(n: JSONObject, env: RuneEnv, m: Modifier) {
    val th = env.theme
    val raw = RuneBind.resolve(n.s("value", "0.5"), env).trim()
    val frac = (if (raw.contains('/')) { val a = raw.substringBefore('/').trim().toFloatOrNull() ?: 0f; val b = raw.substringAfter('/').trim().toFloatOrNull() ?: 1f; if (b == 0f) 0f else a / b }
        else raw.toFloatOrNull()?.let { if (it > 1f) it / 100f else it } ?: 0f).coerceIn(0f, 1f)
    val v by animateFloatAsState(frac, RuneSprings.spec(n.s("spring", "soft")), label = "p")
    val c = textColor(n, env, th.primary)
    val track = th.ink.copy(alpha = 0.10f)
    val thick = n.f("thick", 10f)
    val label = RuneBind.resolve(n.s("text"), env)
    when (n.s("pstyle", "bar")) {
        "ring", "arc" -> {
            val arc = n.s("pstyle") == "arc"
            val sizeDp = if (n.has("w")) n.f("w", 120f) else 120f
            Box(m.size(sizeDp.dp), Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val st = thick.dp.toPx(); val pad = st / 2f
                    val rect = Size(size.width - st, size.height - st)
                    val start = if (arc) 150f else -90f; val sweep = if (arc) 240f else 360f
                    drawArc(track, start, sweep, false, Offset(pad, pad), rect, style = Stroke(st, cap = StrokeCap.Round))
                    if (v > 0.001f) drawArc(Brush.sweepGradient(listOf(c.copy(alpha = 0.55f), c)), start, sweep * v, false, Offset(pad, pad), rect, style = Stroke(st, cap = StrokeCap.Round))
                }
                if (label.isNotEmpty()) Text(label, color = th.ink, fontSize = n.f("size", 20f).sp, fontWeight = FontWeight.SemiBold)
            }
        }
        "dots" -> {
            val total = (raw.substringAfter('/', "5").trim().toFloatOrNull() ?: 5f).toInt().coerceIn(1, 20)
            Row(m, Arrangement.spacedBy(6.dp)) {
                repeat(total) { i -> Box(Modifier.size(thick.dp).clip(CircleShape).background(if (i < kotlin.math.round(frac * total).toInt()) c else track)) }
            }
        }
        else -> Box(m.then(if (n.has("w") || n.b("fillW")) Modifier else Modifier.fillMaxWidth()).height(thick.dp).clip(RoundedCornerShape(50)).background(track)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(v).clip(RoundedCornerShape(50)).background(Brush.horizontalGradient(listOf(c.copy(alpha = 0.7f), c))))
        }
    }
}

@Composable
private fun RuneChart(n: JSONObject, env: RuneEnv, m: Modifier) {
    val th = env.theme
    val vals = RuneBind.resolve(n.s("values", "3,5,2,8,6,9,4"), env).split(',').mapNotNull { it.trim().toFloatOrNull() }
    val c = textColor(n, env, th.primary)
    val style = n.s("cstyle", "bar")
    val base = if (n.has("h") || n.b("fillH")) m else m.height(90.dp)
    Canvas((if (n.has("w") || n.b("fillW")) base else base.fillMaxWidth())) {
        if (vals.isEmpty()) return@Canvas
        val mx = max(vals.max(), 0.0001f); val w = size.width; val h = size.height
        val step = w / vals.size
        when (style) {
            "line", "area" -> {
                val pts = vals.mapIndexed { i, v -> Offset(step * (i + 0.5f), h - (v / mx) * h * 0.92f - h * 0.04f) }
                val p = Path().apply { pts.forEachIndexed { i, o -> if (i == 0) moveTo(o.x, o.y) else { val pr = pts[i - 1]; val mid = (pr.x + o.x) / 2f; cubicTo(mid, pr.y, mid, o.y, o.x, o.y) } } }
                if (style == "area") {
                    val a = Path().apply { addPath(p); lineTo(pts.last().x, h); lineTo(pts.first().x, h); close() }
                    drawPath(a, Brush.verticalGradient(listOf(c.copy(alpha = 0.4f), Color.Transparent)))
                }
                drawPath(p, c, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
                pts.forEach { drawCircle(c, 3.5.dp.toPx(), it) }
            }
            "dots" -> vals.forEachIndexed { i, v -> drawCircle(c.copy(alpha = 0.35f + 0.65f * v / mx), (3f + 7f * v / mx).dp.toPx(), Offset(step * (i + 0.5f), h / 2f)) }
            else -> vals.forEachIndexed { i, v ->
                val bh = (v / mx) * h; val bw = step * 0.62f
                drawRoundRect(Brush.verticalGradient(listOf(c, c.copy(alpha = 0.45f)), h - bh, h), Offset(step * i + (step - bw) / 2f, h - bh), Size(bw, bh), androidx.compose.ui.geometry.CornerRadius(bw / 3f))
            }
        }
    }
}
