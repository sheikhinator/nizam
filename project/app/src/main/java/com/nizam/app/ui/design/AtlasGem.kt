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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

private class V3(val x: Float, val y: Float, val z: Float)

/**
 * The Atlas mark, alive: the faceted diamond from the app icon, built as a real 3D gem (an octahedron) that
 * turns slowly so its facets catch the light, with the glowing inner aperture turning the other way inside it.
 * Seen face-on it is exactly the icon. It breathes when idle, spins up and brightens while Atlas thinks, and
 * stands still (face-on) with Reduce motion.
 */
@Composable
fun AtlasGem(state: OrbState, modifier: Modifier = Modifier, size: Dp = 54.dp, accent: Color = MaterialTheme.colorScheme.primary) {
    val still = Motion.still
    val t = rememberInfiniteTransition(label = "gem")
    val spin by t.animateFloat(0f, 1f, infiniteRepeatable(tween(when (state) {
        OrbState.Thinking, OrbState.Working -> 3600; OrbState.Listening, OrbState.Speaking -> 6000; else -> 14000 }, easing = LinearEasing)), label = "spin")
    val breathe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (state == OrbState.Idle) 2800 else 700), RepeatMode.Reverse), label = "breathe")
    val shimmer by t.animateFloat(0f, 1f, infiniteRepeatable(tween(4200, easing = LinearEasing)), label = "shimmer")
    val core = lerp(accent, Color(0xFFF2A65A), 0.55f)
    val busy = state != OrbState.Idle
    Canvas(modifier.size(size).semantics { contentDescription = "Atlas" }) {
        val h = this.size.minDimension
        val ctr = Offset(this.size.width / 2, this.size.height / 2)
        val swell = if (still) 1f else 0.96f + breathe * (if (busy) 0.07f else 0.035f)
        val half = h * 0.40f * swell              // top to centre
        val waist = half * 0.75f                  // the icon's diamond is 24 wide for 32 tall

        // glow behind, stronger while Atlas works
        drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = if (busy) 0.45f else 0.24f), Color.Transparent), ctr, h * 0.5f), h * 0.5f, ctr)

        val yaw = if (still) 0f else spin * 2f * Math.PI.toFloat()
        val tilt = if (still) 0f else 0.22f + 0.06f * sin(spin * 4f * Math.PI.toFloat())
        drawGem(ctr, half, waist, yaw, tilt, accent, 1f, shimmer, still)
        // the inner aperture: a small gem turning the other way, glowing
        val pulse = if (still) 1f else 0.85f + 0.3f * breathe
        drawCircle(Brush.radialGradient(listOf(core.copy(alpha = 0.55f * pulse), Color.Transparent), ctr, half * 0.55f), half * 0.55f, ctr)
        drawGem(ctr, half * 0.31f * pulse, waist * 0.31f * pulse, -yaw * 1.6f, tilt, core, 1.15f, shimmer, still, solid = true)
    }
}

private fun rot(v: V3, yaw: Float, tilt: Float): V3 {
    val x1 = v.x * cos(yaw) + v.z * sin(yaw)
    val z1 = -v.x * sin(yaw) + v.z * cos(yaw)
    val y2 = v.y * cos(tilt) - z1 * sin(tilt)
    val z2 = v.y * sin(tilt) + z1 * cos(tilt)
    return V3(x1, y2, z2)
}

/** One octahedron: 8 shaded facets drawn back to front, bright edges, the horizontal cleave and a moving glint. */
private fun DrawScope.drawGem(ctr: Offset, half: Float, waist: Float, yaw: Float, tilt: Float, tint: Color, light: Float,
                              shimmer: Float, still: Boolean, solid: Boolean = false) {
    // face-on (yaw 0) the left/right points are the icon's side corners; the front/back points sit on the axis
    val top = V3(0f, -half, 0f); val bottom = V3(0f, half, 0f)
    val eq = listOf(V3(waist, 0f, 0f), V3(0f, 0f, waist), V3(-waist, 0f, 0f), V3(0f, 0f, -waist))
    val pts = (listOf(top, bottom) + eq).map { rot(it, yaw, tilt) }
    val tp = pts[0]; val bp = pts[1]; val e = pts.subList(2, 6)
    val faces = (0 until 4).flatMap { i -> listOf(Triple(tp, e[i], e[(i + 1) % 4]) to true, Triple(bp, e[(i + 1) % 4], e[i]) to false) }
    val lx = -0.45f; val ly = -0.65f; val lz = -0.6f
    val ln = sqrt(lx * lx + ly * ly + lz * lz)
    val dark = lerp(Color(0xFF0E0E12), tint, 0.18f)
    faces.sortedByDescending { (f, _) -> (f.first.z + f.second.z + f.third.z) }.forEach { (f, upper) ->
        val ax = f.second.x - f.first.x; val ay = f.second.y - f.first.y; val az = f.second.z - f.first.z
        val bx = f.third.x - f.first.x; val by = f.third.y - f.first.y; val bz = f.third.z - f.first.z
        var nx = ay * bz - az * by; var ny = az * bx - ax * bz; var nz = ax * by - ay * bx
        val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-4f); nx /= nl; ny /= nl; nz /= nl
        val facing = nz < 0f
        val lambert = ((nx * lx + ny * ly + nz * lz) / ln).coerceIn(0f, 1f)
        val base = if (upper) 0.55f else 0.32f
        val shade = (base + lambert * 0.6f * light).coerceIn(0f, 1f)
        val p = Path().apply {
            moveTo(ctr.x + f.first.x, ctr.y + f.first.y); lineTo(ctr.x + f.second.x, ctr.y + f.second.y); lineTo(ctr.x + f.third.x, ctr.y + f.third.y); close()
        }
        val alpha = when { solid -> if (facing) 1f else 0.6f; facing -> 0.82f; else -> 0.28f }
        drawPath(p, Brush.linearGradient(listOf(lerp(dark, tint, shade), lerp(dark, tint, shade * 0.55f)),
            Offset(ctr.x + f.first.x, ctr.y + f.first.y), Offset(ctr.x + (f.second.x + f.third.x) / 2, ctr.y + (f.second.y + f.third.y) / 2)), alpha = alpha)
        if (facing) drawPath(p, lerp(tint, Color.White, 0.35f).copy(alpha = if (solid) 0.5f else 0.85f), style = Stroke(if (solid) 1f else 1.6f, join = StrokeJoin.Round))
    }
    if (!solid) {
        // the horizontal cleave of the icon, around the waist
        val ring = Path().apply { e.forEachIndexed { i, v -> if (i == 0) moveTo(ctr.x + v.x, ctr.y + v.y) else lineTo(ctr.x + v.x, ctr.y + v.y) }; close() }
        drawPath(ring, tint.copy(alpha = 0.9f), style = Stroke(1.8f, join = StrokeJoin.Round))
        // a glint that slides down the front edges
        if (!still) {
            val k = shimmer
            val front = e.maxByOrNull { -it.z } ?: e[0]
            val gx = ctr.x + tp.x + (front.x - tp.x) * k; val gy = ctr.y + tp.y + (front.y - tp.y) * k
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.7f * (1f - k)), Color.Transparent), Offset(gx, gy), half * 0.22f), half * 0.22f, Offset(gx, gy))
        }
    }
}
