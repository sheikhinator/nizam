package com.nizam.app.ui.style

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.ui.design.PulseState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Logo Studio: the Atlas mark in the dock, made yours. */
object LogoStudio {
    enum class Shape(val title: String) { GEM("Gem"), REACTOR("Reactor"), SPHERE("Sphere"), BLOB("Blob"), RING("Ring"), HEX("Hex core"),
        CRESCENT("Crescent"), PIXEL("Pixel orb"), KNOT("Constellation"), MONOGRAM("Monogram") }
    enum class Surface(val title: String) { GLASS("Glass"), METAL("Metal"), MATTE("Matte"), NEON("Neon"), WIRE("Wireframe") }
    enum class ColorMode(val title: String) { THEME("Theme"), TIME("Time of day"), CUSTOM("Custom") }

    var shape by mutableStateOf(Shape.GEM); var surface by mutableStateOf(Surface.GLASS); var colorMode by mutableStateOf(ColorMode.THEME)
    var custom by mutableStateOf(0xFF4D9BFF.toInt()); var glow by mutableStateOf(0.6f); var speed by mutableStateOf(1f)
    var moods by mutableStateOf(true); var initials by mutableStateOf("A"); var size by mutableStateOf(1f)
    @Volatile private var loaded = false

    fun load(c: Context) {
        if (loaded) return; loaded = true
        val p = c.getSharedPreferences("logo_studio", 0)
        shape = runCatching { Shape.valueOf(p.getString("shape", "GEM")!!) }.getOrDefault(Shape.GEM)
        surface = runCatching { Surface.valueOf(p.getString("surface", "GLASS")!!) }.getOrDefault(Surface.GLASS)
        colorMode = runCatching { ColorMode.valueOf(p.getString("color", "THEME")!!) }.getOrDefault(ColorMode.THEME)
        custom = p.getInt("custom", custom); glow = p.getFloat("glow", 0.6f); speed = p.getFloat("speed", 1f)
        moods = p.getBoolean("moods", true); initials = p.getString("initials", "A") ?: "A"; size = p.getFloat("size", 1f)
    }
    fun save(c: Context) = c.getSharedPreferences("logo_studio", 0).edit().putString("shape", shape.name).putString("surface", surface.name)
        .putString("color", colorMode.name).putInt("custom", custom).putFloat("glow", glow).putFloat("speed", speed).putBoolean("moods", moods)
        .putString("initials", initials).putFloat("size", size).apply()

    val PALETTE = listOf(0xFF4D9BFF, 0xFF9D7BFF, 0xFF4DFFB8, 0xFFFFB84D, 0xFFFF6B9D, 0xFFE6C068, 0xFF41E1F2, 0xFFFF7043, 0xFFFFFFFF, 0xFF8BC34A).map { it.toInt() }
}

private fun timeColor(): Color = when (java.time.LocalTime.now().hour) {
    in 5..7 -> Color(0xFFFF9C7A); in 8..11 -> Color(0xFF7FD4C1); in 12..15 -> Color(0xFF4D9BFF); in 16..18 -> Color(0xFFFFB84D)
    in 19..21 -> Color(0xFF9D7BFF); else -> Color(0xFF6C7FE0)
}

/** The Atlas mark: whatever shape, surface and colour you chose, alive with what Atlas is doing. */
@Composable
fun AtlasLogo(state: PulseState, modifier: Modifier = Modifier, diameter: Dp = 54.dp) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    LogoStudio.load(ctx)
    val L = LogoStudio
    val base = when (L.colorMode) { LogoStudio.ColorMode.THEME -> MaterialTheme.colorScheme.primary; LogoStudio.ColorMode.TIME -> timeColor(); LogoStudio.ColorMode.CUSTOM -> Color(L.custom) }
    // moods: thinking turns violet and quickens, listening turns green, a prayer is near turns gold
    val color = if (!L.moods) base else when (state) {
        PulseState.THINKING -> Color(0xFF9D7BFF); PulseState.LISTENING -> Color(0xFF4DFFB8); PulseState.UNPROMPTED -> Color(0xFFFFB84D)
        PulseState.PROTECTIVE -> Color(0xFFFF6B9D); else -> base }
    val still = L.speed <= 0.01f || com.nizam.app.ui.design.Motion.still
    val pace = if (L.moods && state == PulseState.THINKING) 2.2f else 1f
    val dur = (6000 / (L.speed.coerceAtLeast(0.05f) * pace)).toInt().coerceIn(600, 120000)
    val t = rememberInfiniteTransition(label = "logo")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(dur, easing = LinearEasing)), label = "spin")
    val breath by t.animateFloat(0.85f, 1f, infiniteRepeatable(tween(dur / 3, easing = LinearEasing), RepeatMode.Reverse), label = "breath")
    val a = if (still) 20f else spin; val b = if (still) 1f else breath
    Canvas(modifier.size(diameter * L.size.coerceIn(0.7f, 1.2f))) { drawLogo(L.shape, L.surface, color, L.glow, a, b, L.initials) }
}

private fun DrawScope.drawLogo(shape: LogoStudio.Shape, surface: LogoStudio.Surface, color: Color, glow: Float, spin: Float, breath: Float, initials: String) {
    val c = Offset(size.width / 2, size.height / 2); val r = size.minDimension / 2 * 0.78f
    if (glow > 0f) drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.45f * glow), Color.Transparent), c, r * 1.35f), r * 1.35f, c)
    val wire = surface == LogoStudio.Surface.WIRE
    fun fill(edge: Color = color): Brush = when (surface) {
        LogoStudio.Surface.GLASS -> Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f), color.copy(alpha = 0.75f), color.copy(alpha = 0.35f)), c - Offset(r * 0.35f, r * 0.4f), r * 1.5f)
        LogoStudio.Surface.METAL -> Brush.linearGradient(listOf(Color.White, color, Color(0xFF2A2F38), color.copy(alpha = 0.9f), Color.White.copy(alpha = 0.8f)), c - Offset(r, r), c + Offset(r, r))
        LogoStudio.Surface.MATTE -> Brush.linearGradient(listOf(edge, edge.copy(alpha = 0.8f)))
        LogoStudio.Surface.NEON -> Brush.radialGradient(listOf(Color.White, edge, edge.copy(alpha = 0.2f)), c, r)
        LogoStudio.Surface.WIRE -> Brush.linearGradient(listOf(Color.Transparent, Color.Transparent))
    }
    val line = Stroke(if (surface == LogoStudio.Surface.NEON) 4f else 2.5f, cap = StrokeCap.Round)
    when (shape) {
        LogoStudio.Shape.GEM -> rotate(spin * 0.25f, c) {
            val top = Offset(c.x, c.y - r * breath); val bot = Offset(c.x, c.y + r * breath)
            val w = r * 0.78f; val sq = sin(spin * PI / 180).toFloat()
            val l = Offset(c.x - w, c.y); val rr = Offset(c.x + w, c.y); val f = Offset(c.x + w * sq * 0.5f, c.y + r * 0.08f)
            listOf(listOf(top, l, f), listOf(top, f, rr), listOf(bot, l, f), listOf(bot, f, rr)).forEachIndexed { i, tri ->
                val p = Path().apply { moveTo(tri[0].x, tri[0].y); lineTo(tri[1].x, tri[1].y); lineTo(tri[2].x, tri[2].y); close() }
                if (!wire) drawPath(p, fill(color.copy(alpha = 1f - i * 0.15f)))
                drawPath(p, Color.White.copy(alpha = if (wire) 0.9f else 0.35f), style = Stroke(1.5f))
            }
        }
        LogoStudio.Shape.REACTOR -> {
            if (!wire) drawCircle(fill(), r * 0.55f * breath, c)
            for (i in 0 until 3) rotate(spin + i * 120f, c) { drawArc(color, 0f, 80f, false, Offset(c.x - r, c.y - r), Size(r * 2, r * 2), style = line) }
            drawCircle(Color.White.copy(alpha = 0.9f), r * 0.14f, c)
        }
        LogoStudio.Shape.SPHERE -> {
            if (wire) { drawCircle(color, r * breath, c, style = line); for (k in 1..3) { val e = r * breath * k / 4; drawOval(color.copy(alpha = 0.6f), Offset(c.x - r * breath, c.y - e / 2), Size(r * 2 * breath, e), style = Stroke(1.5f)) } }
            else { drawCircle(fill(), r * breath, c); drawCircle(Color.White.copy(alpha = 0.5f), r * 0.22f, c - Offset(r * 0.35f, r * 0.38f)) }
        }
        LogoStudio.Shape.BLOB -> {
            val p = Path(); val n = 48
            for (i in 0..n) { val ang = i * 2 * PI / n; val wob = 1f + 0.12f * sin(ang * 3 + spin * PI / 90).toFloat() + 0.06f * cos(ang * 5 - spin * PI / 60).toFloat()
                val x = c.x + (r * 0.9f * wob * cos(ang)).toFloat(); val y = c.y + (r * 0.9f * wob * sin(ang)).toFloat(); if (i == 0) p.moveTo(x, y) else p.lineTo(x, y) }
            p.close(); if (wire) drawPath(p, color, style = line) else drawPath(p, fill())
        }
        LogoStudio.Shape.RING -> {
            drawCircle(color.copy(alpha = 0.25f), r * 0.82f, c, style = Stroke(r * 0.22f))
            drawArc(if (wire) SolidBrush(color) else fill(), spin, 240f, false, Offset(c.x - r * 0.82f, c.y - r * 0.82f), Size(r * 1.64f, r * 1.64f), style = Stroke(r * 0.22f, cap = StrokeCap.Round))
            val ang = (spin + 240) * PI / 180; drawCircle(Color.White, r * 0.12f, Offset(c.x + (r * 0.82f * cos(ang)).toFloat(), c.y + (r * 0.82f * sin(ang)).toFloat()))
        }
        LogoStudio.Shape.HEX -> rotate(spin * 0.3f, c) {
            fun hex(rad: Float) = Path().apply { for (i in 0..5) { val ang = PI / 3 * i - PI / 6; val x = c.x + (rad * cos(ang)).toFloat(); val y = c.y + (rad * sin(ang)).toFloat(); if (i == 0) moveTo(x, y) else lineTo(x, y) }; close() }
            if (!wire) drawPath(hex(r * breath), fill())
            drawPath(hex(r * breath), Color.White.copy(alpha = 0.5f), style = Stroke(2f)); drawPath(hex(r * 0.55f), color, style = line)
            drawCircle(Color.White.copy(alpha = 0.85f), r * 0.16f, c)
        }
        LogoStudio.Shape.CRESCENT -> {
            val p = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r * breath)) }
            val cut = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c + Offset(r * 0.42f, -r * 0.18f), r * 0.86f)) }
            val moon = Path.combine(androidx.compose.ui.graphics.PathOperation.Difference, p, cut)
            if (wire) drawPath(moon, color, style = line) else drawPath(moon, fill())
            val st = Offset(c.x + r * 0.55f, c.y - r * 0.05f); drawCircle(Color.White.copy(alpha = 0.6f + 0.4f * sin(spin * PI / 90).toFloat().coerceAtLeast(0f)), r * 0.09f, st)
        }
        LogoStudio.Shape.PIXEL -> {
            val n = 9; val cell = r * 2 / n
            for (i in 0 until n) for (j in 0 until n) {
                val x = c.x - r + i * cell + cell / 2; val y = c.y - r + j * cell + cell / 2
                val dd = Offset(x, y) - c; val dist = kotlin.math.sqrt(dd.x * dd.x + dd.y * dd.y)
                if (dist <= r * breath) { val shade = (1f - dist / r).coerceIn(0.2f, 1f) * (0.75f + 0.25f * sin((i + j + spin / 30) * 0.9f).toFloat())
                    drawRect(if (wire) color.copy(alpha = 0.3f) else color.copy(alpha = shade), Offset(x - cell / 2 + 0.6f, y - cell / 2 + 0.6f), Size(cell - 1.2f, cell - 1.2f)) }
            }
        }
        LogoStudio.Shape.KNOT -> {
            val pts = (0 until 7).map { i -> val ang = (i * 2 * PI / 7) + spin * PI / 180 * 0.3; val rad = r * (if (i % 2 == 0) 0.85f else 0.5f) * breath
                Offset(c.x + (rad * cos(ang)).toFloat(), c.y + (rad * sin(ang)).toFloat()) }
            pts.forEachIndexed { i, p -> drawLine(color.copy(alpha = 0.6f), p, pts[(i + 3) % pts.size], 1.6f); drawLine(color.copy(alpha = 0.4f), p, c, 1f) }
            pts.forEach { drawCircle(Color.White, r * 0.07f, it); drawCircle(color, r * 0.12f, it, alpha = 0.4f) }
            drawCircle(color, r * 0.16f, c)
        }
        LogoStudio.Shape.MONOGRAM -> {
            if (!wire) drawCircle(fill(), r * breath, c) else drawCircle(color, r * breath, c, style = line)
            drawIntoCanvas { cv ->
                val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    this.color = (if (wire) color else Color.White).toArgb(); textSize = r * 1.0f; textAlign = android.graphics.Paint.Align.CENTER
                    typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.BOLD) }
                cv.nativeCanvas.drawText(initials.take(2).uppercase(), c.x, c.y + r * 0.35f, paint)
            }
        }
    }
}

private fun SolidBrush(c: Color) = Brush.linearGradient(listOf(c, c))

/** The studio screen: shape, surface, colour, glow, speed, moods, and the home-screen icon. */
@Composable
fun LogoStudioScreen() {
    val c = androidx.compose.ui.platform.LocalContext.current
    LogoStudio.load(c)
    val L = LogoStudio
    fun changed() = L.save(c)
    var demo by androidx.compose.runtime.remember { mutableStateOf(PulseState.IDLE) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(28.dp)).background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), MaterialTheme.colorScheme.surface))),
            contentAlignment = Alignment.Center) { AtlasLogo(demo, diameter = 130.dp) }
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(PulseState.IDLE to "Idle", PulseState.THINKING to "Thinking", PulseState.LISTENING to "Listening", PulseState.UNPROMPTED to "Prayer near", PulseState.PROTECTIVE to "Protective").forEach { (s, n) ->
                Pill(n, demo == s) { demo = s } }
        }
        Label("SHAPE")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LogoStudio.Shape.values().forEach { s ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clip(RoundedCornerShape(16.dp))
                    .background(if (L.shape == s) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent)
                    .border(1.dp, if (L.shape == s) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                    .clickable { L.shape = s; changed() }.padding(10.dp)) {
                    Box(Modifier.size(48.dp)) { MiniLogo(s) }
                    Text(s.title, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        if (L.shape == LogoStudio.Shape.MONOGRAM) androidx.compose.material3.OutlinedTextField(L.initials, { L.initials = it.take(2); changed() }, label = { Text("Initials") }, singleLine = true)
        Label("SURFACE")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { LogoStudio.Surface.values().forEach { s -> Pill(s.title, L.surface == s) { L.surface = s; changed() } } }
        Label("COLOUR")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { LogoStudio.ColorMode.values().forEach { m -> Pill(m.title, L.colorMode == m) { L.colorMode = m; changed() } } }
        if (L.colorMode == LogoStudio.ColorMode.CUSTOM) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LogoStudio.PALETTE.forEach { col -> Box(Modifier.size(34.dp).clip(CircleShape).background(Color(col)).border(if (L.custom == col) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape).clickable { L.custom = col; changed() }) }
        }
        Label("GLOW"); Slider(L.glow, { L.glow = it }, onValueChangeFinished = { changed() })
        Label("MOTION " + if (L.speed <= 0.01f) "(still)" else "x" + "%.1f".format(L.speed)); Slider(L.speed, { L.speed = it }, valueRange = 0f..3f, onValueChangeFinished = { changed() })
        Label("SIZE"); Slider(L.size, { L.size = it }, valueRange = 0.7f..1.2f, onValueChangeFinished = { changed() })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Moods", fontWeight = FontWeight.SemiBold); Text("Colour and pace follow what Atlas is doing", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Switch(L.moods, { L.moods = it; changed() })
        }
        Label("HOME-SCREEN ICON")
        com.nizam.app.feature.icon.IconPicker()
    }
}

@Composable
private fun MiniLogo(s: LogoStudio.Shape) {
    Canvas(Modifier.size(48.dp)) { drawLogo(s, LogoStudio.surface, Color(0xFF4D9BFF).let { if (LogoStudio.colorMode == LogoStudio.ColorMode.CUSTOM) Color(LogoStudio.custom) else it }, 0.4f, 30f, 1f, LogoStudio.initials) }
}

@Composable private fun Label(t: String) = Text(t, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Pill(t: String, on: Boolean, onClick: () -> Unit) = Text(t, color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
    style = MaterialTheme.typography.labelLarge, modifier = Modifier.clip(CircleShape).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
        .clickable { onClick() }.padding(horizontal = 14.dp, vertical = 8.dp))
