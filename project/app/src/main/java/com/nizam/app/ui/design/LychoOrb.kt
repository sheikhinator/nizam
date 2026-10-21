package com.nizam.app.ui.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class OrbNode(val x: Float, val y: Float, val z: Float, val big: Boolean)

/**
 * The LYCHO mark as a living 3D sphere: Atlas's mind at work. A dark glossy globe holds the six-spoke mark,
 * which turns slowly in three dimensions so the nodes catch the light. It shares the Atlas orb's states:
 * it breathes when idle, swirls while thinking and traces a ring while Atlas works in the background.
 * Colours come from the active theme, so it always sits naturally beside the Atlas blob.
 */
@Composable
fun LychoOrb(state: OrbState, modifier: Modifier = Modifier, diameter: Dp = 52.dp) {
    val c = MaterialTheme.colorScheme
    val still = Motion.still
    val t = rememberInfiniteTransition(label = "lycho")
    val turn by t.animateFloat(0f, 360f, infiniteRepeatable(tween(when (state) {
        OrbState.Thinking -> 3200; OrbState.Working -> 4800; OrbState.Speaking -> 7000; else -> 16000 }, easing = LinearEasing)), label = "turn")
    val breathe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (state == OrbState.Speaking) 520 else 2600), RepeatMode.Reverse), label = "breathe")
    val ring by t.animateFloat(0f, 360f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "ring")
    val gold = lerp(c.primary, Color(0xFFC9A84C), 0.55f)
    val glow = if (state == OrbState.Idle) 0.22f else 0.42f
    Canvas(modifier.size(diameter).semantics { contentDescription = "LYCHO, Atlas's mind at work" }) {
        val r = size.minDimension / 2
        val ctr = Offset(size.width / 2, size.height / 2)
        val swell = if (still) 0.92f else 0.9f + breathe * (if (state == OrbState.Speaking) 0.08f else 0.04f)
        val body = r * swell * 0.8f

        // outer glow in the theme's colour
        drawCircle(Brush.radialGradient(listOf(gold.copy(alpha = glow), Color.Transparent), ctr, r), r, ctr)
        // the glossy dark globe, lit from the top left
        drawCircle(
            Brush.radialGradient(
                listOf(lerp(Color(0xFF2A2A30), gold, 0.18f), Color(0xFF121216), Color(0xFF050507)),
                Offset(ctr.x - body * 0.35f, ctr.y - body * 0.4f), body * 1.5f,
            ), body, ctr,
        )
        // a thin rim, brighter where the light falls
        drawCircle(Brush.sweepGradient(listOf(gold.copy(alpha = 0.55f), gold.copy(alpha = 0.08f), gold.copy(alpha = 0.3f), gold.copy(alpha = 0.55f)), ctr),
            body, ctr, style = Stroke(body * 0.035f))

        // the six-spoke mark, turning about the vertical axis with a slight forward tilt
        val angle = if (still) 0.6f else (turn * PI / 180).toFloat()
        val tilt = 0.32f
        val spokeLen = body * 0.74f
        val pts = listOf(90.0, 30.0, -30.0, -90.0, -150.0, 150.0).mapIndexed { i, deg ->
            val a = deg * PI / 180
            val x0 = cos(a).toFloat(); val y0 = -sin(a).toFloat()
            val x1 = x0 * cos(angle); val z1 = x0 * sin(angle)
            val y2 = y0 * cos(tilt) - z1 * sin(tilt); val z2 = y0 * sin(tilt) + z1 * cos(tilt)
            OrbNode(ctr.x + x1 * spokeLen, ctr.y + y2 * spokeLen, z2, i == 0 || i == 3)
        }
        // far spokes first so near ones draw over them
        pts.sortedBy { it.z }.forEach { p ->
            val near = (p.z + 1f) / 2f // 0 far … 1 near
            val a = 0.35f + near * 0.65f
            drawLine(gold.copy(alpha = a), ctr, Offset(p.x, p.y), strokeWidth = body * (0.035f + near * 0.025f), cap = StrokeCap.Round)
            val nr = body * (if (p.big) 0.11f else 0.095f) * (0.75f + near * 0.4f)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f * a), gold.copy(alpha = a), gold.copy(alpha = a * 0.7f)),
                Offset(p.x - nr * 0.35f, p.y - nr * 0.35f), nr * 1.6f), nr, Offset(p.x, p.y))
        }
        // the core
        val core = body * 0.12f
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), gold, gold.copy(alpha = 0.8f)),
            Offset(ctr.x - core * 0.3f, ctr.y - core * 0.3f), core * 1.6f), core, ctr)
        // specular highlight on the glass
        drawOval(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.32f), Color.Transparent),
            Offset(ctr.x - body * 0.38f, ctr.y - body * 0.5f), body * 0.42f),
            Offset(ctr.x - body * 0.7f, ctr.y - body * 0.8f), Size(body * 0.7f, body * 0.45f))
        // working or thinking: a ring tracing around the globe, like the Atlas orb
        if (state == OrbState.Working || state == OrbState.Thinking) {
            drawArc(gold.copy(alpha = 0.85f), if (still) 0f else ring, 90f, false, Offset(ctr.x - r * 0.95f, ctr.y - r * 0.95f),
                Size(r * 1.9f, r * 1.9f), style = Stroke(r * 0.06f, cap = StrokeCap.Round))
        }
    }
}
