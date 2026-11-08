package com.nizam.app.feature.rune

import android.os.Build
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * A fill is one short string so it is easy to edit by hand, by the studio, or by Atlas:
 *   #RRGGBB  #AARRGGBB  @primary  @card/40 (token at 40% alpha)
 *   lin:135:#112233,@primary   linear gradient at an angle
 *   rad:#fff,#000   sweep:#a,#b,#a   mesh:#a,#b,#c,#d   aurora:#a,#b,#c   liquid:#a,#b,#c   none
 */
class FillSpec(val kind: String, val angle: Float, val colors: List<Color>) {
    val animated get() = kind == "mesh" || kind == "aurora" || kind == "liquid"

    fun brush(size: Size): Brush? {
        if (colors.isEmpty()) return null
        if (colors.size == 1 && kind != "none") return Brush.linearGradient(listOf(colors[0], colors[0]))
        return when (kind) {
            "lin" -> {
                val r = Math.toRadians(angle.toDouble()); val dx = cos(r).toFloat(); val dy = sin(r).toFloat()
                val half = (kotlin.math.abs(dx) * size.width + kotlin.math.abs(dy) * size.height) / 2f
                val c = Offset(size.width / 2f, size.height / 2f)
                Brush.linearGradient(colors, Offset(c.x - dx * half, c.y - dy * half), Offset(c.x + dx * half, c.y + dy * half))
            }
            "rad" -> Brush.radialGradient(colors, Offset(size.width / 2f, size.height / 2f), max(size.width, size.height) * 0.7f)
            "sweep" -> Brush.sweepGradient(colors, Offset(size.width / 2f, size.height / 2f))
            else -> null
        }
    }

    companion object {
        fun color(s: String, theme: RuneTheme): Color {
            var t = s.trim()
            var alpha = -1f
            val slash = t.indexOf('/')
            if (slash > 0) { alpha = (t.substring(slash + 1).toFloatOrNull() ?: 100f) / 100f; t = t.substring(0, slash) }
            val base = if (t.startsWith("@")) theme.token(t.substring(1)) ?: theme.primary else RuneColor.parse(t, Color.Transparent)
            return if (alpha >= 0f) base.copy(alpha = alpha.coerceIn(0f, 1f)) else base
        }

        fun parse(spec: String?, theme: RuneTheme): FillSpec? {
            val s = spec?.trim().orEmpty()
            if (s.isEmpty() || s == "none") return null
            val kind = s.substringBefore(':', "")
            if (kind in setOf("lin", "rad", "sweep", "mesh", "aurora", "liquid")) {
                var rest = s.substringAfter(':')
                var angle = 135f
                if (kind == "lin") {
                    val first = rest.substringBefore(':', "")
                    val a = first.toFloatOrNull()
                    if (a != null && rest.contains(':')) { angle = a; rest = rest.substringAfter(':') }
                }
                val cols = rest.split(',').filter { it.isNotBlank() }.map { color(it, theme) }
                return FillSpec(kind, angle, cols)
            }
            return FillSpec("solid", 0f, listOf(color(s, theme)))
        }

        fun toSpec(kind: String, angle: Float, colors: List<String>): String = when (kind) {
            "solid" -> colors.firstOrNull() ?: "none"
            "none" -> "none"
            "lin" -> "lin:${angle.toInt()}:" + colors.joinToString(",")
            else -> kind + ":" + colors.joinToString(",")
        }
    }
}

object RuneFx {
    const val AGSL = """
layout(color) uniform half4 c1;
layout(color) uniform half4 c2;
layout(color) uniform half4 c3;
uniform float2 resolution;
uniform float time;
half4 main(float2 fc) {
    float2 uv = fc / resolution;
    float2 p = uv * 3.2;
    for (int i = 1; i < 5; i++) {
        float fi = float(i);
        p.x += 0.55 / fi * sin(fi * 3.0 * p.y + time);
        p.y += 0.55 / fi * cos(fi * 3.0 * p.x + time * 1.31);
    }
    float v = 0.5 + 0.5 * sin(p.x + p.y);
    float w = 0.5 + 0.5 * cos(p.x - p.y * 1.3);
    half3 col = mix(c1.rgb, c2.rgb, half(v));
    col = mix(col, c3.rgb, half(w) * 0.65);
    float spec = pow(v, 7.0) * 0.28;
    col += half3(half(spec));
    return half4(col, 1.0);
}
"""

    /** Is the GPU shader path available on this phone? */
    val shaderOk: Boolean get() = Build.VERSION.SDK_INT >= 33
}

/** Draws a [spec] fill behind the content, clipped to [shape]. Animated kinds drift with time. */
fun Modifier.runeFill(spec: String?, shape: Shape, theme: RuneTheme, speed: Float = 1f, still: Boolean = false): Modifier = composed {
    val f = remember(spec, theme) { FillSpec.parse(spec, theme) }
    if (f == null) this
    else {
        val t: State<Float>? = if (f.animated && !still) {
            val tr = rememberInfiniteTransition(label = "rune-fill")
            tr.animateFloat(0f, 1f, infiniteRepeatable(tween((24000 / speed.coerceIn(0.1f, 6f)).toInt(), easing = LinearEasing), RepeatMode.Restart), label = "t")
        } else null
        val agsl = remember(f.kind) { if (f.kind == "liquid" && RuneFx.shaderOk) runCatching { LiquidShader() }.getOrNull() else null }
        this.drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val clip = Path().apply { addOutline(outline) }
            val still0 = f.brush(size)
            onDrawBehind {
                clipPath(clip) {
                    when {
                        f.kind == "solid" -> drawRect(f.colors[0])
                        still0 != null -> drawRect(still0)
                        f.kind == "mesh" -> drawMesh(f.colors, t?.value ?: 0f)
                        f.kind == "aurora" -> drawAurora(f.colors, t?.value ?: 0f)
                        f.kind == "liquid" -> {
                            val ok = agsl?.let { runCatching { it.draw(this, f.colors, (t?.value ?: 0f) * 6.2832f * 3f) }.isSuccess } ?: false
                            if (!ok) drawMesh(f.colors, t?.value ?: 0f)
                        }
                    }
                }
            }
        }
    }
}

private fun DrawScope.drawMesh(colors: List<Color>, t: Float) {
    if (colors.isEmpty()) return
    val w = size.width; val h = size.height
    drawRect(RuneColor.mix(colors[0], Color.Black, 0.35f))
    val r = max(w, h) * 0.78f
    colors.forEachIndexed { i, c ->
        val a = 2f * PI.toFloat() * (t * (1 + (i % 3)) + i * 0.37f)
        val cx = w * (0.5f + 0.42f * cos(a + i)); val cy = h * (0.5f + 0.42f * sin(a * 0.8f + i * 1.3f))
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = 0.95f), c.copy(alpha = 0f)), Offset(cx, cy), r), r, Offset(cx, cy))
    }
}

private fun DrawScope.drawAurora(colors: List<Color>, t: Float) {
    if (colors.isEmpty()) return
    val w = size.width; val h = size.height
    drawRect(RuneColor.mix(colors[0], Color.Black, 0.78f))
    colors.forEachIndexed { i, c ->
        val path = Path()
        val base = h * (0.25f + 0.22f * i)
        val amp = h * 0.10f
        val ph = 2f * PI.toFloat() * (t * (1 + i % 2) + i * 0.3f)
        val steps = 28
        for (s in 0..steps) {
            val x = w * s / steps
            val y = base + amp * sin(x / w * 5.4f + ph) + amp * 0.5f * sin(x / w * 11f - ph * 1.3f)
            if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val band = h * 0.36f
        for (s in steps downTo 0) {
            val x = w * s / steps
            val y = base + band + amp * sin(x / w * 4.2f + ph * 0.8f)
            path.lineTo(x, y)
        }
        path.close()
        drawPath(path, Brush.verticalGradient(listOf(c.copy(alpha = 0f), c.copy(alpha = 0.55f), c.copy(alpha = 0f)), base - amp, base + band + amp))
    }
}

/** GPU liquid surface (Android 13+). Any failure falls back to the mesh. */
private class LiquidShader {
    private val shader = android.graphics.RuntimeShader(RuneFx.AGSL)
    fun draw(scope: DrawScope, colors: List<Color>, time: Float) {
        val c = if (colors.size >= 3) colors else (colors + colors + colors).take(3)
        shader.setFloatUniform("resolution", scope.size.width, scope.size.height)
        shader.setFloatUniform("time", time)
        shader.setColorUniform("c1", c[0].toArgbInt()); shader.setColorUniform("c2", c[1].toArgbInt()); shader.setColorUniform("c3", c[2].toArgbInt())
        scope.drawRect(ShaderBrush(shader))
    }
}

private fun Color.toArgbInt(): Int {
    val a = (alpha * 255f + 0.5f).toInt().coerceIn(0, 255); val r = (red * 255f + 0.5f).toInt().coerceIn(0, 255)
    val g = (green * 255f + 0.5f).toInt().coerceIn(0, 255); val b = (blue * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

/** Surface effects drawn on top of a node: grain, shimmer, scan, sparkle, gloss, vignette. */
fun Modifier.runeFx(kind: String?, shape: Shape, theme: RuneTheme, speed: Float = 1f, still: Boolean = false): Modifier = composed {
    val k = kind.orEmpty()
    if (k.isEmpty() || k == "none") this
    else {
        val moving = k in setOf("shimmer", "scan", "sparkle") && !still
        val t: State<Float>? = if (moving) {
            val tr = rememberInfiniteTransition(label = "rune-fx")
            tr.animateFloat(0f, 1f, infiniteRepeatable(tween((3200 / speed.coerceIn(0.1f, 6f)).toInt(), easing = LinearEasing), RepeatMode.Restart), label = "t")
        } else null
        this.drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            val clip = Path().apply { addOutline(outline) }
            val dots: List<Offset> = when (k) {
                "grain" -> { val rnd = Random(7); List(((size.width * size.height) / 380f).toInt().coerceIn(40, 4200)) { Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height) } }
                "sparkle" -> { val rnd = Random(11); List(26) { Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height) } }
                else -> emptyList()
            }
            onDrawWithContent {
                drawContent()
                clipPath(clip) {
                    val w = size.width; val h = size.height; val v = t?.value ?: 0f
                    when (k) {
                        "grain" -> drawPoints(dots, androidx.compose.ui.graphics.PointMode.Points, Color.White.copy(alpha = if (theme.dark) 0.07f else 0.10f), 1.6f)
                        "shimmer" -> {
                            val x = -w * 0.6f + v * w * 2.2f
                            drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.22f), Color.Transparent), Offset(x, 0f), Offset(x + w * 0.5f, h * 0.4f)))
                        }
                        "scan" -> {
                            val y = v * h
                            drawRect(Brush.verticalGradient(listOf(Color.Transparent, theme.primary.copy(alpha = 0.28f), Color.Transparent), y - h * 0.12f, y + h * 0.12f))
                        }
                        "sparkle" -> dots.forEachIndexed { i, o ->
                            val a = (0.5f + 0.5f * sin(2f * PI.toFloat() * (v + i * 0.13f))).coerceIn(0f, 1f)
                            drawCircle(Color.White.copy(alpha = a * 0.8f), 1.2f + (i % 3), o)
                        }
                        "gloss" -> drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.Transparent, Color.Transparent), 0f, h))
                        "vignette" -> drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)), Offset(w / 2f, h / 2f), max(w, h) * 0.75f))
                        "frost" -> drawRect(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f)), Offset.Zero, Offset(w, h)))
                    }
                }
            }
        }
    }
}

/** A coloured glow under a node (real coloured shadow on Android 9+, plain elevation before). */
fun Modifier.runeGlow(color: Color, radiusDp: Float, shape: Shape): Modifier =
    if (radiusDp <= 0f) this else this.shadow(radiusDp.dp, shape, clip = false, ambientColor = color, spotColor = color)

@Composable
internal fun rememberRuneTime(): State<Float> {
    val tr = rememberInfiniteTransition(label = "rune-clock")
    return tr.animateFloat(0f, 1f, infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart), label = "tick")
}
