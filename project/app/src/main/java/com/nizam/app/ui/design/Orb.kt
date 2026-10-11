package com.nizam.app.ui.design

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** What Atlas is doing right now; the orb shows it at a glance, the same everywhere. */
enum class OrbState { Idle, Listening, Thinking, Speaking, Working }

/**
 * The Atlas orb: a soft glowing sphere with slow inner currents. It breathes when idle, swells with
 * your voice when listening, swirls while thinking, pulses while speaking, and circles while it works
 * on something in the background.
 */
@Composable
fun AtlasOrb(state: OrbState, modifier: Modifier = Modifier, diameter: Dp = 56.dp, level: Float = 0f) {
    val c = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "orb")
    val breathe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (state == OrbState.Speaking) 520 else 2600), RepeatMode.Reverse), label = "breathe")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(when (state) {
        OrbState.Thinking -> 1400; OrbState.Working -> 2200; else -> 9000 }, easing = LinearEasing)), label = "spin")
    val core by animateColorAsState(when (state) {
        OrbState.Listening -> c.tertiary
        OrbState.Thinking, OrbState.Working -> c.primary
        OrbState.Speaking -> c.secondary
        OrbState.Idle -> c.primary
    }, label = "orbCore")
    val edge = c.tertiary
    Canvas(modifier.size(diameter)) {
        val r = size.minDimension / 2
        val ctr = Offset(size.width / 2, size.height / 2)
        val swell = when (state) {
            OrbState.Listening -> 0.86f + level.coerceIn(0f, 1f) * 0.14f
            OrbState.Speaking -> 0.88f + breathe * 0.1f
            OrbState.Idle -> 0.9f + breathe * 0.05f
            else -> 0.92f
        }
        // outer glow
        drawCircle(Brush.radialGradient(listOf(core.copy(alpha = 0.35f), Color.Transparent), ctr, r), r, ctr)
        // body
        val body = r * swell * 0.78f
        drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), core, edge.copy(alpha = 0.9f)),
            Offset(ctr.x - body * 0.3f, ctr.y - body * 0.35f), body * 1.4f), body, ctr)
        // inner currents: two soft blobs orbiting inside the sphere
        rotate(spin, ctr) {
            listOf(0.0, PI).forEachIndexed { i, a ->
                val d = body * 0.42f
                val p = Offset(ctr.x + (cos(a) * d).toFloat(), ctr.y + (sin(a) * d).toFloat())
                drawCircle(Brush.radialGradient(listOf((if (i == 0) edge else Color.White).copy(alpha = 0.45f), Color.Transparent), p, body * 0.6f), body * 0.6f, p)
            }
        }
        // working: a thin ring tracing around the sphere
        if (state == OrbState.Working || state == OrbState.Thinking) {
            drawArc(core.copy(alpha = 0.8f), spin, 90f, false, Offset(ctr.x - r * 0.95f, ctr.y - r * 0.95f),
                androidx.compose.ui.geometry.Size(r * 1.9f, r * 1.9f), style = Stroke(r * 0.06f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
    }
}
