package com.nizam.app.feature.chief

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private val RAY_LENGTHS = floatArrayOf(1f, .78f, .9f, .74f, .96f, .8f, 1f, .76f, .92f, .8f, .94f, .74f)

/**
 * A round, embossed badge in Claude's warm orange with a raised asterisk and a glossy epoxy dome over it.
 * While [thinking] the rays turn slowly and a glint sweeps across the dome.
 */
@Composable
fun ClaudeBadge(size: Dp = 40.dp, modifier: Modifier = Modifier, thinking: Boolean = false) =
    EpoxyBadge(size, modifier, thinking, Color(0xFFDF7D58), null)

/** A pleasant colour for any app, picked from its name so it stays the same every time. */
fun badgeTint(name: String): Color =
    Color(android.graphics.Color.HSVToColor(floatArrayOf(((name.lowercase().hashCode() % 360) + 360) % 360f, 0.58f, 0.86f)))

/**
 * The same glossy epoxy badge in any colour, with either the raised asterisk or an emoji at its centre.
 * Apps use their own colour and emoji; the small Claude badge in the corner marks the AI layer.
 */
@Composable
fun EpoxyBadge(size: Dp, modifier: Modifier, thinking: Boolean, base: Color, glyph: String?) {
    val light = androidx.compose.ui.graphics.lerp(base, Color.White, 0.45f)
    val dark = androidx.compose.ui.graphics.lerp(base, Color.Black, 0.38f)
    val face = androidx.compose.ui.graphics.lerp(Color.White, base, 0.10f)
    val faceLow = androidx.compose.ui.graphics.lerp(Color.White, base, 0.34f)
    val t = rememberInfiniteTransition(label = "claude-badge")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(16_000, easing = LinearEasing)), label = "spin")
    val glint by t.animateFloat(-0.4f, 1.4f, infiniteRepeatable(tween(2_300, easing = LinearEasing), RepeatMode.Restart), label = "glint")
    androidx.compose.foundation.layout.Box(modifier.size(size), contentAlignment = androidx.compose.ui.Alignment.Center) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val c = s / 2f
        val r = s / 2f * 0.92f
        val center = Offset(c, c)

        // soft shadow under the badge
        listOf(0.16f to 0.05f, 0.10f to 0.035f, 0.06f to 0.02f).forEach { (a, off) ->
            drawCircle(Color(0xFF1A0A05).copy(alpha = a), r * 1.02f, Offset(c, c + s * off))
        }
        // body
        drawCircle(Brush.radialGradient(
            0f to light, 0.45f to base, 1f to dark,
            center = Offset(c * 0.75f, c * 0.65f), radius = r * 1.25f), r, center)
        // bevelled rim: lit from the top left, shaded at the bottom right
        drawCircle(Brush.linearGradient(listOf(Color(0xF2FFE6D2), Color.Transparent, Color(0xBF5A190A)), Offset.Zero, Offset(s, s)),
            r - s * 0.018f, center, style = Stroke(s * 0.035f))
        // a shallow recess around the emblem
        drawCircle(Color(0x59501408), r * 0.84f, center, style = Stroke(s * 0.012f))
        drawCircle(Color(0x59FFDCC8), r * 0.84f + s * 0.012f, center, style = Stroke(s * 0.012f))

        // the raised asterisk: shadow, highlight, then the face
        val w = s * 0.062f
        val turn = if (thinking) spin else 0f
        fun rays(dx: Float, dy: Float, brush: Brush) {
            for (i in 0 until 12) {
                val a = ((-PI / 2) + i * PI / 6 + turn * PI / 180).toFloat()
                val len = r * 0.62f * RAY_LENGTHS[i]
                drawLine(brush, Offset(c + dx + cos(a) * r * 0.07f, c + dy + sin(a) * r * 0.07f),
                    Offset(c + dx + cos(a) * len, c + dy + sin(a) * len), strokeWidth = w, cap = StrokeCap.Round)
            }
        }
        if (glyph == null) {
            rays(s * 0.012f, s * 0.02f, Brush.linearGradient(listOf(dark.copy(alpha = 0.6f), dark.copy(alpha = 0.6f))))
            rays(-s * 0.007f, -s * 0.011f, Brush.linearGradient(listOf(Color(0xD9FFFFFF), Color(0xD9FFFFFF))))
            rays(0f, 0f, Brush.linearGradient(listOf(face, faceLow), Offset(c - r * 0.5f, c - r * 0.6f), Offset(c + r * 0.5f, c + r * 0.6f)))
        }

        // the epoxy dome
        val dome = Path().apply { addOval(Rect(center, r)) }
        clipPath(dome) {
            drawOval(Brush.verticalGradient(listOf(Color(0x9EFFFFFF), Color(0x05FFFFFF)), startY = c - r, endY = c),
                topLeft = Offset(c - r * 0.8f, c - r), size = Size(r * 1.6f, r * 1.0f))
            drawCircle(Brush.radialGradient(listOf(Color(0x59FFBE96), Color.Transparent), Offset(c, c + r * 0.95f), r * 0.8f), r * 0.8f, Offset(c, c + r * 0.95f))
            rotate(-34f, Offset(c * 0.62f, c * 0.44f)) {
                drawOval(Color(0xE6FFFFFF), Offset(c * 0.62f - r * 0.09f, c * 0.44f - r * 0.05f), Size(r * 0.18f, r * 0.10f))
            }
            drawCircle(Color(0x59FFFFFF), r * 0.035f, Offset(c * 1.28f, c * 1.55f))
            if (thinking) glintSweep(s, glint, center)
        }
    }
    if (glyph != null) androidx.compose.material3.Text(glyph, fontSize = (size.value * 0.46f).sp)
    }
}

private fun DrawScope.glintSweep(s: Float, progress: Float, center: Offset) {
    val x = s * progress
    rotate(30f, center) {
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color(0x66FFFFFF), Color.Transparent), startX = x - s * 0.18f, endX = x + s * 0.18f),
            Offset(-s, -s), Size(s * 3f, s * 3f))
    }
}
