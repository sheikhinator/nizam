package com.nizam.app.ui.design

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The look system. A "kit" is a whole design language (Material Expressive, Liquid Glass, Untitled,
 * Glow, …); every individual knob can then be overridden. All of it applies app-wide at once
 * because every screen draws with the same tokens.
 */
object Style {
    data class S(
        val kit: String = "Atlas Glass", val corners: String = "Squircle", val card: String = "Glass", val glass: Float = 0.6f,
        val bg: String = "Aurora", val font: String = "System", val density: String = "Comfortable", val tilt: Boolean = true,
        val art: Boolean = true, val motion: String = "Full", val island: Boolean = true, val sounds: Boolean = false,
        val nav: String = "Glass", val hubIcons: String = "3D", val expressive: Boolean = true, val glow: Boolean = true,
        val cornerScale: Float = 1f, val blur: Float = 28f,
    )
    data class Kit(val name: String, val blurb: String, val s: S)

    val KITS = listOf(
        Kit("Atlas Glass", "Liquid glass over living colour, 3D art, expressive type", S()),
        Kit("Material Expressive", "Google's 2026 language: bold shapes, springy motion, tonal colour",
            S(kit = "Material Expressive", corners = "Round", card = "Tonal", glass = 0.3f, bg = "Gradient", cornerScale = 1.35f, nav = "Solid", glow = false)),
        Kit("Liquid Glass", "Apple-style clear glass: see-through layers that bend the light",
            S(kit = "Liquid Glass", corners = "Squircle", card = "Glass", glass = 0.85f, bg = "Mesh", cornerScale = 1.2f, blur = 40f)),
        Kit("Untitled Clean", "Crisp, calm and minimal, like the best SaaS apps",
            S(kit = "Untitled Clean", corners = "Round", card = "Outline", glass = 0.2f, bg = "Plain", cornerScale = 0.7f, expressive = false, glow = false, art = true)),
        Kit("Glow Neon", "Dark, luminous gradients with glowing edges",
            S(kit = "Glow Neon", corners = "Squircle", card = "Glow", glass = 0.55f, bg = "Stars", cornerScale = 1.1f, glow = true)),
        Kit("Clay 3D", "Soft, puffy cards and big 3D objects, like a playful illustration",
            S(kit = "Clay 3D", corners = "Squircle", card = "Clay", glass = 0.3f, bg = "Gradient", cornerScale = 1.5f)),
        Kit("Classic Flat", "Simple solid cards, no effects — fastest and quietest",
            S(kit = "Classic Flat", corners = "Round", card = "Solid", glass = 0f, bg = "Plain", tilt = false, expressive = false, glow = false, nav = "Solid", hubIcons = "Flat")),
    )

    val CORNERS = listOf("Squircle", "Round", "Sharp")
    val CARDS = listOf("Glass", "Solid", "Outline", "Tonal", "Glow", "Clay", "Elevated")
    val BACKGROUNDS = listOf("Aurora", "Mesh", "Gradient", "Stars", "Waves", "Plain")
    val FONTS = listOf("System", "Serif", "Mono", "Condensed")
    val DENSITIES = listOf("Compact", "Comfortable", "Airy")
    val MOTIONS = listOf("Full", "Calm", "Off")
    val NAVS = listOf("Glass", "Solid", "Minimal")

    var s by mutableStateOf(S())
        private set
    private var loaded = false
    private fun p(c: Context) = c.getSharedPreferences("style", 0)

    fun load(c: Context) {
        if (loaded) return; loaded = true
        val o = runCatching { org.json.JSONObject(p(c).getString("s", "{}")!!) }.getOrElse { org.json.JSONObject() }
        val d = S()
        s = S(o.optString("kit", d.kit), o.optString("corners", d.corners), o.optString("card", d.card), o.optDouble("glass", d.glass.toDouble()).toFloat(),
            o.optString("bg", d.bg), o.optString("font", d.font), o.optString("density", d.density), o.optBoolean("tilt", d.tilt), o.optBoolean("art", d.art),
            o.optString("motion", d.motion), o.optBoolean("island", d.island), o.optBoolean("sounds", d.sounds), o.optString("nav", d.nav),
            o.optString("hubIcons", d.hubIcons), o.optBoolean("expressive", d.expressive), o.optBoolean("glow", d.glow),
            o.optDouble("cornerScale", d.cornerScale.toDouble()).toFloat(), o.optDouble("blur", d.blur.toDouble()).toFloat())
        applyMotion()
    }
    fun set(c: Context, n: S) {
        s = n; applyMotion()
        p(c).edit().putString("s", org.json.JSONObject().put("kit", n.kit).put("corners", n.corners).put("card", n.card).put("glass", n.glass.toDouble())
            .put("bg", n.bg).put("font", n.font).put("density", n.density).put("tilt", n.tilt).put("art", n.art).put("motion", n.motion)
            .put("island", n.island).put("sounds", n.sounds).put("nav", n.nav).put("hubIcons", n.hubIcons).put("expressive", n.expressive)
            .put("glow", n.glow).put("cornerScale", n.cornerScale.toDouble()).put("blur", n.blur.toDouble()).toString()).apply()
    }
    fun update(c: Context, change: (S) -> S) = set(c, change(s))
    private fun applyMotion() { if (s.motion == "Off") Motion.still = true }

    /** Corner radius for a base size, following the corner style and roundness slider. */
    fun radius(base: Dp): Dp = when (s.corners) { "Sharp" -> base * 0.35f; else -> base * s.cornerScale }.coerceAtMost(40.dp)
    fun shape(base: Dp): Shape = if (s.corners == "Squircle") Squircle(radius(base)) else RoundedCornerShape(radius(base))
    val pad: Dp get() = when (s.density) { "Compact" -> 12.dp; "Airy" -> 20.dp; else -> 16.dp }
}

@Composable
fun rememberStyle(): Style.S { val c = LocalContext.current; Style.load(c); return Style.s }

// ─────────────────────────────── Card surfaces ───────────────────────────────
/** Draws a card in the chosen style. Used by Group and other containers. */
fun Modifier.styledCard(shape: Shape): Modifier = composed {
    val st = Style.s
    val c = MaterialTheme.colorScheme
    val light = c.background.luminance() > 0.5f
    when (st.card) {
        "Glass" -> this.frost(shape, strength = (1f - st.glass * 0.55f).coerceIn(0.35f, 0.95f))
        "Outline" -> this.clip(shape).border(BorderStroke(1.dp, c.outline.copy(alpha = if (light) 0.9f else 0.6f)), shape)
        "Tonal" -> this.clip(shape).background(c.primary.copy(alpha = if (light) 0.07f else 0.10f).compositeOver(c.surface))
        "Glow" -> this.drawBehind {
            drawRoundRect(Brush.linearGradient(listOf(c.primary.copy(alpha = 0.35f), c.tertiary.copy(alpha = 0.25f))), cornerRadius = androidx.compose.ui.geometry.CornerRadius(24f), size = size)
        }.padding(1.dp).clip(shape).background(c.surface.copy(alpha = 0.92f))
        "Clay" -> this.shadow(10.dp, shape, ambientColor = c.primary.copy(alpha = 0.25f), spotColor = c.primary.copy(alpha = 0.3f)).clip(shape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (light) 0.9f else 0.10f).compositeOver(c.surface), c.surface)))
        "Elevated" -> this.shadow(6.dp, shape).clip(shape).background(c.surface)
        else -> this.clip(shape).background(c.surface)
    }
}

// ─────────────────────────────── Backgrounds ───────────────────────────────
@Composable
fun StyleBackground(modifier: Modifier = Modifier) {
    val st = Style.s
    if (st.bg == "Plain") return
    val c = MaterialTheme.colorScheme
    val light = c.background.luminance() > 0.5f
    val a = if (light) 0.10f else 0.22f
    // Calm keeps the warm themed backdrop but holds it still — quiet, not removed.
    val t = if (Motion.still || Motion.calm || st.bg !in listOf("Aurora", "Waves", "Stars")) null else rememberInfiniteTransition(label = "bg")
    val phase = t?.animateFloat(0f, 1f, infiniteRepeatable(tween(18000, easing = LinearEasing)), label = "p")?.value ?: 0.25f
    Canvas(modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        when (st.bg) {
            "Gradient" -> drawRect(Brush.verticalGradient(listOf(c.primary.copy(alpha = a), c.tertiary.copy(alpha = a * 0.5f), Color.Transparent), endY = h * 0.7f))
            "Mesh" -> {
                drawCircle(Brush.radialGradient(listOf(c.primary.copy(alpha = a), Color.Transparent), Offset(w * 0.1f, h * 0.05f), w * 0.9f), w * 0.9f, Offset(w * 0.1f, h * 0.05f))
                drawCircle(Brush.radialGradient(listOf(c.tertiary.copy(alpha = a * 0.9f), Color.Transparent), Offset(w * 0.95f, h * 0.35f), w * 0.8f), w * 0.8f, Offset(w * 0.95f, h * 0.35f))
                drawCircle(Brush.radialGradient(listOf(c.secondary.copy(alpha = a * 0.6f), Color.Transparent), Offset(w * 0.3f, h * 0.85f), w * 0.9f), w * 0.9f, Offset(w * 0.3f, h * 0.85f))
            }
            "Aurora" -> repeat(3) { i ->
                val ph = (phase + i / 3f) * 2 * PI.toFloat()
                val cx = w * (0.5f + 0.4f * sin(ph + i)); val cy = h * (0.18f + 0.12f * cos(ph * 0.7f + i * 2))
                val col = listOf(c.primary, c.tertiary, c.secondary)[i].copy(alpha = a * 0.9f)
                drawCircle(Brush.radialGradient(listOf(col, Color.Transparent), Offset(cx, cy), w * 0.75f), w * 0.75f, Offset(cx, cy))
            }
            "Stars" -> {
                drawRect(Brush.verticalGradient(listOf(c.primary.copy(alpha = a * 0.7f), Color.Transparent), endY = h * 0.6f))
                val r = java.util.Random(11)
                repeat(70) { val tw = 0.35f + 0.65f * abs(sin((phase * 6 + r.nextFloat()) * PI.toFloat()))
                    drawCircle(Color.White.copy(alpha = (if (light) 0.25f else 0.7f) * tw * r.nextFloat()), 1f + r.nextFloat() * 2.2f, Offset(r.nextFloat() * w, r.nextFloat() * h * 0.7f)) }
            }
            "Waves" -> repeat(3) { i ->
                val path = Path(); val base = h * (0.12f + i * 0.08f)
                path.moveTo(0f, 0f); path.lineTo(0f, base)
                var x = 0f; while (x <= w) { path.lineTo(x, base + sin((x / w * 2 * PI + phase * 2 * PI + i).toFloat()) * 26f); x += 12f }
                path.lineTo(w, 0f); path.close()
                drawPath(path, listOf(c.primary, c.tertiary, c.secondary)[i].copy(alpha = a * 0.55f))
            }
        }
    }
}

// ─────────────────────────────── True glass (blurs what's behind) ───────────────────────────────
class Backdrop { var layer: GraphicsLayer? = null; var origin by mutableStateOf(Offset.Zero); var frame by mutableIntStateOf(0) }
val LocalBackdrop = staticCompositionLocalOf<Backdrop?> { null }

@Composable
fun rememberBackdrop(): Backdrop { val l = rememberGraphicsLayer(); val b = remember { Backdrop() }; b.layer = l; return b }

/** Marks the content that glass surfaces blur. */
fun Modifier.backdropSource(b: Backdrop): Modifier = this.onGloballyPositioned { b.origin = it.positionInRoot() }.drawWithContent {
    val l = b.layer
    if (l == null) drawContent() else { l.record { this@drawWithContent.drawContent() }; drawLayer(l); b.frame++ }
}

/** Liquid glass: the content behind, blurred, then a tint, a top sheen and a bright rim. */
fun Modifier.liquidGlass(shape: Shape): Modifier = composed {
    val b = LocalBackdrop.current
    val st = Style.s
    val c = MaterialTheme.colorScheme
    val light = c.background.luminance() > 0.5f
    var pos by remember { mutableStateOf(Offset.Zero) }
    val blurLayer = rememberGraphicsLayer()
    val density = LocalDensity.current
    if (b == null || android.os.Build.VERSION.SDK_INT < 31 || st.glass <= 0.05f || st.nav != "Glass") return@composed (if (st.nav == "Minimal") this.clip(shape) else this.frost(shape, 0.9f))
    val blurPx = with(density) { st.blur.dp.toPx() }
    this.onGloballyPositioned { pos = it.positionInRoot() }.clip(shape).drawBehind {
        b.frame // re-draw whenever the content behind changes
        val src = b.layer
        if (src != null) {
            blurLayer.renderEffect = BlurEffect(blurPx, blurPx, TileMode.Clamp)
            blurLayer.record { translate(-(pos.x - b.origin.x), -(pos.y - b.origin.y)) { drawLayer(src) } }
            drawLayer(blurLayer)
        }
        val tint = (if (light) Color.White else c.surface).copy(alpha = (0.55f - st.glass * 0.4f).coerceIn(0.12f, 0.6f))
        drawRect(tint)
        drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (light) 0.45f else 0.10f), Color.Transparent), endY = size.height * 0.6f))
    }.border(BorderStroke(0.8.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (light) 0.8f else 0.28f), Color.White.copy(alpha = if (light) 0.25f else 0.05f)))), shape)
}

// ─────────────────────────────── Tilt (gyroscope parallax) ───────────────────────────────
object Tilt {
    var x by mutableFloatStateOf(0f)   // -1..1 left/right
    var y by mutableFloatStateOf(0f)   // -1..1 back/forward
    private var users = 0
    private var listener: SensorEventListener? = null
    fun acquire(c: Context) {
        users++
        if (listener != null) return
        val sm = c.getSystemService(SensorManager::class.java) ?: return
        val s = sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                x = x * 0.85f + (-e.values[0] / 9.81f).coerceIn(-1f, 1f) * 0.15f
                y = y * 0.85f + ((e.values[1] / 9.81f) - 0.6f).coerceIn(-1f, 1f) * 0.15f
            }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        sm.registerListener(l, s, SensorManager.SENSOR_DELAY_UI); listener = l
    }
    fun release(c: Context) {
        users = (users - 1).coerceAtLeast(0)
        if (users == 0) { listener?.let { c.getSystemService(SensorManager::class.java)?.unregisterListener(it) }; listener = null }
    }
}

@Composable
fun rememberTilt(): Pair<Float, Float> {
    val ctx = LocalContext.current
    val on = Style.s.tilt && !Motion.still
    DisposableEffect(on) { if (on) Tilt.acquire(ctx.applicationContext); onDispose { if (on) Tilt.release(ctx.applicationContext) } }
    return if (on) Tilt.x to Tilt.y else 0f to 0f
}

/** Tilts and shifts with the phone for a sense of depth. */
fun Modifier.parallax(depth: Float = 1f): Modifier = composed {
    val (tx, ty) = rememberTilt()
    this.graphicsLayer { rotationY = tx * 8f * depth; rotationX = -ty * 6f * depth; translationX = tx * 10f * depth; translationY = ty * 8f * depth; cameraDistance = 16f * density }
}

// ─────────────────────────────── 3D art ───────────────────────────────
object Art {
    fun id(c: Context, name: String): Int = c.resources.getIdentifier("art_$name", "drawable", c.packageName)
    fun forHub(h: Hub) = when (h) {
        Hub.Money -> "money_bag"; Hub.Deen -> "mosque"; Hub.Health -> "sparkling_heart"; Hub.Learn -> "graduation_cap"; Hub.Home -> "house_with_garden"
        Hub.Media -> "clapper_board"; Hub.Memory -> "light_bulb"; Hub.Work -> "briefcase"; Hub.Social -> "speech_balloon"; Hub.Tools -> "hammer_and_wrench"
        Hub.Create -> "artist_palette"; Hub.Travel -> "airplane"
    }
    /** WMO weather codes (Open-Meteo) to a 3D sky object. */
    fun forWeather(code: Int, night: Boolean): String = when (code) {
        0 -> if (night) "crescent_moon" else "sun"
        1 -> if (night) "crescent_moon" else "sun_behind_small_cloud"
        2 -> if (night) "cloud" else "sun_behind_cloud"
        3 -> "cloud"; 45, 48 -> "fog"
        51, 53, 55, 56, 57 -> "sun_behind_rain_cloud"
        61, 63, 65, 66, 67, 80, 81, 82 -> "cloud_with_rain"
        71, 73, 75, 77, 85, 86 -> "cloud_with_snow"
        95, 96, 99 -> "cloud_with_lightning_and_rain"
        else -> "sun_behind_cloud"
    }
    fun moon(age: Double): String = when { age < 1.84 -> "new_moon"; age < 5.53 -> "waxing_crescent_moon"; age < 9.22 -> "first_quarter_moon"; age < 12.91 -> "waxing_gibbous_moon"
        age < 16.61 -> "full_moon"; age < 20.30 -> "waning_gibbous_moon"; age < 23.99 -> "last_quarter_moon"; age < 27.68 -> "waning_crescent_moon"; else -> "new_moon" }
    val MOODS = listOf("star_struck" to "Amazing", "smiling_face_with_hearts" to "Loved", "grinning_face_with_big_eyes" to "Happy", "smiling_face_with_smiling_eyes" to "Good",
        "relieved_face" to "Calm", "neutral_face" to "Okay", "sleeping_face" to "Tired", "anxious_face_with_sweat" to "Anxious", "pensive_face" to "Sad", "angry_face" to "Angry")
}

/** A curated 3D object: floats gently, tilts with the phone, casts a soft shadow. Falls back quietly if art is off. */
@Composable
fun Art3D(name: String, size: Dp, modifier: Modifier = Modifier, float: Boolean = true, shadow: Boolean = true) {
    val ctx = LocalContext.current
    if (!Style.s.art) return
    val id = remember(name) { Art.id(ctx, name) }
    if (id == 0) return
    val bob = if (float && !Motion.still) rememberInfiniteTransition(label = "bob").animateFloat(-1f, 1f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "b").value else 0f
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (shadow) Canvas(Modifier.fillMaxSize()) {
            drawOval(Color.Black.copy(alpha = 0.18f - bob * 0.04f), Offset(this.size.width * (0.22f + bob * 0.02f), this.size.height * 0.86f), Size(this.size.width * (0.56f - bob * 0.04f), this.size.height * 0.09f))
        }
        Image(painterResource(id), null, Modifier.fillMaxSize().padding(size * 0.06f).graphicsLayer { translationY = bob * size.toPx() * 0.035f }.parallax(0.8f))
    }
}

// ─────────────────────────────── Morphing shapes ───────────────────────────────
/** Shapes described by radius-by-angle, so any two can morph smoothly into each other. */
object Polar {
    fun circle(@Suppress("UNUSED_PARAMETER") a: Double) = 1.0
    fun squircle(a: Double) = 1.0 / (abs(cos(a)).pow(4.0) + abs(sin(a)).pow(4.0)).pow(0.25) * 0.86
    fun scallop(n: Int, depth: Double) = { a: Double -> 1.0 - depth + depth * cos(n * a).let { (it + 1) / 2 } }
    fun star(n: Int) = { a: Double -> 0.72 + 0.28 * cos(n * a).let { if (it > 0) it.pow(0.6) else -(-it).pow(0.6) } }
    val cookie = scallop(9, 0.10); val flower = scallop(6, 0.22); val clover = scallop(4, 0.26); val sun = star(8); val gem = scallop(5, 0.16)
    val ALL: List<(Double) -> Double> = listOf(::circle, ::squircle, cookie, flower, clover, sun, gem)
}

class MorphShape(private val a: (Double) -> Double, private val b: (Double) -> Double, private val t: Float, private val rotation: Float = 0f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val p = Path(); val cx = size.width / 2; val cy = size.height / 2; val r = minOf(cx, cy)
        val n = 96
        for (i in 0..n) {
            val ang = i * 2 * PI / n
            val rr = (a(ang) * (1 - t) + b(ang) * t).coerceAtMost(1.0) * r
            val x = cx + (rr * cos(ang + rotation)).toFloat(); val y = cy + (rr * sin(ang + rotation)).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close(); return Outline.Generic(p)
    }
}

/** Each hub's signature silhouette. */
fun hubShape(h: Hub): Shape = MorphShape(Polar.ALL[(h.ordinal % (Polar.ALL.size - 1)) + 1], Polar.ALL[(h.ordinal % (Polar.ALL.size - 1)) + 1], 0f)

/** A blob that keeps morphing through the shape family — the Atlas orb while it thinks. */
@Composable
fun MorphingBlob(modifier: Modifier, colors: List<Color>, busy: Boolean) {
    val t = rememberInfiniteTransition(label = "morph")
    val k by t.animateFloat(0f, Polar.ALL.size.toFloat(), infiniteRepeatable(tween(if (busy) 5200 else 14000, easing = LinearEasing)), label = "k")
    val spin by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(if (busy) 6000 else 24000, easing = LinearEasing)), label = "s")
    val i = k.toInt() % Polar.ALL.size
    val shape = if (Motion.still) MorphShape(Polar.cookie, Polar.cookie, 0f) else MorphShape(Polar.ALL[i], Polar.ALL[(i + 1) % Polar.ALL.size], k - k.toInt(), spin)
    Box(modifier.clip(shape).background(Brush.sweepGradient(colors + colors.first())))
}

// ─────────────────────────────── Tap origin (screens grow from what you tapped) ───────────────────────────────
object LastTap { var x = 0.5f; var y = 0.5f; var at = 0L }

// ─────────────────────────────── UI sounds ───────────────────────────────
object UiSound {
    private var track: android.media.AudioTrack? = null
    private fun make(): android.media.AudioTrack? = runCatching {
        val rate = 44100; val n = rate / 60
        val pcm = ShortArray(n) { i -> val env = (1 - i.toDouble() / n).pow(3.0); (sin(2 * PI * 1850 * i / rate) * env * 0.28 * Short.MAX_VALUE).toInt().toShort() }
        android.media.AudioTrack.Builder()
            .setAudioAttributes(android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            .setAudioFormat(android.media.AudioFormat.Builder().setSampleRate(rate).setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
            .setTransferMode(android.media.AudioTrack.MODE_STATIC).setBufferSizeInBytes(n * 2).build().also { it.write(pcm, 0, n) }
    }.getOrNull()
    fun tick() {
        if (!Style.s.sounds) return
        runCatching { val t = track ?: make().also { track = it } ?: return; t.stop(); t.reloadStaticData(); t.play() }
    }
}

// ─────────────────────────────── The island ───────────────────────────────
object Island {
    var nextPrayer by mutableStateOf<Pair<String, Int>?>(null)
    var agentBusy by mutableStateOf<String?>(null)
    var hqNeeds by mutableStateOf(0)
}

/** A small pill at the top that grows into whatever is live: timer, music, prayer, recording, subtitles, the agent. */
@Composable
fun IslandPill(modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    if (!Style.s.island) return
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { var n = 0; while (true) {
        kotlinx.coroutines.delay(1000); now = System.currentTimeMillis()
        if (n++ % 6 == 0) Island.hqNeeds = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.nizam.app.feature.hq.Hq.quickNeeds(ctx) }
    } }
    val track = com.nizam.app.feature.music.MusicPlayback.current
    val timerLeft = (FocusTimer.endsAt - now).coerceAtLeast(0)
    val prayer = Island.nextPrayer?.takeIf { it.second in 0..15 }
    val rec = com.nizam.app.feature.nx.ScreenRecordService.running
    val subs = com.nizam.app.feature.nx.LiveSubsService.running
    val agent = Island.agentBusy
    val item: Triple<String, String, String>? = when {
        rec -> Triple("⏺", "Recording screen", "nx/recorder")
        agent != null -> Triple("✦", agent, "atlas")
        Island.hqNeeds > 0 -> Triple("✋", "${Island.hqNeeds} agent${if (Island.hqNeeds > 1) "s" else ""} need${if (Island.hqNeeds > 1) "" else "s"} you", "hq")
        timerLeft > 0 -> Triple("⏱", "${timerLeft / 60000}:${"%02d".format(timerLeft / 1000 % 60)}", "timer")
        prayer != null -> Triple("🕌", "${prayer.first} in ${prayer.second} min", "prayer")
        subs -> Triple("💬", "Live subtitles", "nx/livesubs")
        track != null && com.nizam.app.feature.music.MusicPlayback.isPlaying -> Triple("♪", track.title.take(26), "music")
        else -> null
    }
    AnimatedVisibility(item != null, modifier, enter = fadeIn() + scaleIn(spring(dampingRatio = 0.6f), initialScale = 0.4f), exit = fadeOut() + scaleOut(targetScale = 0.4f)) {
        val (icon, label, route) = item ?: Triple("", "", "")
        Row(Modifier.clip(RoundedCornerShape(50)).background(Color(0xF0101114)).pressable { onOpen(route) }.animateContentSize(spring(dampingRatio = 0.7f))
            .padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(icon, color = Color.White)
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (rec || agent != null) { Spacer(Modifier.width(8.dp)); Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(if (rec) Color(0xFFFF4D4D) else Color(0xFF4FC3A1))) }
        }
    }
}

// ─────────────────────────────── Peek & pop ───────────────────────────────
object Peek { var item by mutableStateOf<Triple<String, String, Hub?>?>(null) }

/** Hold anything to preview it; let go to close. The tap underneath is cancelled while peeking. */
fun Modifier.peek(title: String, blurb: String, hub: Hub?): Modifier = this.pointerInput(title) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = androidx.compose.ui.input.pointer.PointerEventPass.Initial)
        val t0 = System.currentTimeMillis(); var shown = false; var moved = false
        while (true) {
            val e = withTimeoutOrNull(50) { awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial) }
            if (e == null) {
                if (!shown && !moved && System.currentTimeMillis() - t0 > 450) { shown = true; Peek.item = Triple(title, blurb, hub) }
                continue
            }
            if (shown) e.changes.forEach { it.consume() }
            else if (e.changes.any { (it.position - down.position).getDistance() > 24f }) moved = true
            if (e.changes.none { it.pressed }) break
        }
        if (shown) Peek.item = null
    }
}

@Composable
fun PeekOverlay() {
    val it = Peek.item ?: return
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.layout.Column(Modifier.padding(32.dp).frost(Radius.card, 0.95f).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            it.third?.let { h -> Art3D(Art.forHub(h), 110.dp) }
            Text(it.first, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(it.second, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            Text("Let go to close · tap to open", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

// ─────────────────────────────── Spotlight tips ───────────────────────────────
object Tips {
    private val TIPS = mapOf(
        "today" to "Tap Edit on “Your day” to rearrange tiles. Swipe the smart stack for what matters now.",
        "modules" to "Hold any feature to peek at it without opening. Hubs now have 3D icons.",
        "atlashub" to "Hold the centre tab anywhere to talk to Atlas.",
        "appearance" to "Open Theme studio for design kits, 30+ palettes and your own theme.",
        "weather" to "Add any city at the bottom. Tilt your phone — the 3D sky moves with you.",
    )
    fun tip(c: Context, route: String?): String? = route?.let { r -> TIPS[r]?.takeIf { !c.getSharedPreferences("tips", 0).getBoolean(r, false) } }
    fun done(c: Context, route: String) = c.getSharedPreferences("tips", 0).edit().putBoolean(route, true).apply()
}

@Composable
fun TipCard(route: String?, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var gone by remember(route) { mutableStateOf(false) }
    val t = remember(route) { Tips.tip(ctx, route) }
    AnimatedVisibility(t != null && !gone, modifier, enter = fadeIn() + scaleIn(initialScale = 0.9f), exit = fadeOut()) {
        Row(Modifier.padding(horizontal = 16.dp).frost(Radius.card, 0.95f).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("💡", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(10.dp))
            Text(t.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("Got it", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.pressable { gone = true; route?.let { Tips.done(ctx, it) } }.padding(8.dp))
        }
    }
}

/** Non-extension form, so it can be used fully qualified from anywhere. */
fun PeekMod(title: String, blurb: String, hub: Hub?): Modifier = Modifier.peek(title, blurb, hub)
