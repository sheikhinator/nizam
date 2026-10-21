package com.nizam.app.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The living scene behind each hub screen's title: quiet, crisp and true to the hub's nature. Coins glint
 * for Money, a geometric star turns for Deen, a heartbeat runs through Health, pages and stars drift for
 * Learn, an equaliser plays for Media, and so on. It fades out under the title and stops with Reduce motion.
 */
@Composable
fun HubScene(hub: Hub, modifier: Modifier = Modifier) {
    val tint = hub.tint()
    val t = rememberLoop(when (hub) { Hub.Health -> 2400; Hub.Media -> 1600; else -> 14_000 })
    Canvas(modifier) {
        val a = 0.16f
        drawRect(Brush.radialGradient(listOf(tint.copy(alpha = 0.16f), Color.Transparent), Offset(size.width * 0.85f, size.height * 0.1f), size.width * 0.7f))
        when (hub) {
            Hub.Money -> coins(tint, t, a)
            Hub.Deen -> star(tint, t, a)
            Hub.Health -> heartbeat(tint, t, a)
            Hub.Learn -> sparks(tint, t, a)
            Hub.Home -> windowGlow(tint, t, a)
            Hub.Media -> equaliser(tint, t, a)
            Hub.Memory -> bubbles(tint, t, a)
            Hub.Work -> grid(tint, t, a)
            Hub.Social -> orbits(tint, t, a)
            Hub.Tools -> gear(tint, t, a)
            Hub.Create -> blobs(tint, t, a)
            Hub.Travel -> clouds(tint, t, a)
        }
    }
}

private val TAU = (2 * PI).toFloat()

private fun DrawScope.coins(c: Color, t: Float, a: Float) {
    repeat(7) { i ->
        val x = size.width * (0.45f + 0.08f * i)
        val y = size.height * (0.2f + 0.5f * ((t + i * 0.17f) % 1f))
        val squash = 0.35f + 0.65f * kotlin.math.abs(cos((t * 2 + i * 0.3f) * TAU))
        drawOval(c.copy(alpha = a * 1.4f), Offset(x - 9f * squash, y - 9f), Size(18f * squash, 18f))
        if (squash > 0.9f) drawCircle(Color.White.copy(alpha = 0.35f), 2.5f, Offset(x - 3f, y - 4f))
    }
}

private fun DrawScope.star(c: Color, t: Float, a: Float) {
    val cx = size.width * 0.82f; val cy = size.height * 0.45f; val r = size.height * 0.42f
    rotate(t * 360f / 4, Offset(cx, cy)) {
        val p = Path()
        for (k in 0 until 16) { val ang = PI / 8 * k; val rr = if (k % 2 == 0) r else r * 0.62f
            val x = cx + (cos(ang) * rr).toFloat(); val y = cy + (sin(ang) * rr).toFloat(); if (k == 0) p.moveTo(x, y) else p.lineTo(x, y) }
        p.close()
        drawPath(p, c.copy(alpha = a * 1.6f), style = Stroke(2f))
        drawCircle(c.copy(alpha = a), r * 0.3f, Offset(cx, cy), style = Stroke(1.5f))
    }
}

private fun DrawScope.heartbeat(c: Color, t: Float, a: Float) {
    val y0 = size.height * 0.55f; val p = Path(); val w = size.width
    p.moveTo(0f, y0)
    var x = 0f
    while (x < w) {
        val k = ((x / w) - t + 1f) % 1f
        val spike = when { k < 0.04f -> -k * 900f; k < 0.07f -> -(0.07f - k) * 1200f + 20f; k < 0.1f -> (k - 0.07f) * 400f; else -> 0f }
        p.lineTo(x, y0 + spike.coerceIn(-size.height * 0.4f, size.height * 0.3f)); x += 4f
    }
    drawPath(p, c.copy(alpha = a * 2f), style = Stroke(2.5f))
}

private fun DrawScope.sparks(c: Color, t: Float, a: Float) {
    repeat(14) { i ->
        val ph = (t + i * 0.071f) % 1f
        val x = size.width * ((i * 0.137f + 0.3f) % 1f); val y = size.height * (1f - ph)
        val tw = 0.5f + 0.5f * sin((t * 3 + i) * TAU)
        drawCircle(c.copy(alpha = a * 1.5f * tw), 2f + 2f * tw, Offset(x, y))
    }
}

private fun DrawScope.windowGlow(c: Color, t: Float, a: Float) {
    repeat(3) { i ->
        val x = size.width * (0.55f + i * 0.13f); val h = size.height * 0.5f
        val on = 0.6f + 0.4f * sin((t + i * 0.3f) * TAU)
        drawRoundRect(c.copy(alpha = a * on * 1.6f), Offset(x, size.height * 0.2f), Size(size.width * 0.09f, h), CornerRadius(6f))
        drawLine(c.copy(alpha = a), Offset(x + size.width * 0.045f, size.height * 0.2f), Offset(x + size.width * 0.045f, size.height * 0.2f + h), 1.5f)
    }
}

private fun DrawScope.equaliser(c: Color, t: Float, a: Float) {
    val n = 18; val bw = size.width * 0.5f / n
    repeat(n) { i ->
        val h = size.height * (0.15f + 0.5f * kotlin.math.abs(sin((t * 2 + i * 0.23f) * TAU) * cos((t + i * 0.11f) * TAU)))
        drawRoundRect(c.copy(alpha = a * 1.7f), Offset(size.width * 0.48f + i * bw, size.height * 0.8f - h), Size(bw * 0.6f, h), CornerRadius(3f))
    }
}

private fun DrawScope.bubbles(c: Color, t: Float, a: Float) {
    repeat(9) { i ->
        val ph = (t + i * 0.11f) % 1f
        val x = size.width * (0.4f + 0.06f * i) + sin((ph + i) * TAU) * 10f
        drawCircle(c.copy(alpha = a * 1.4f * (1f - ph)), 6f + i * 1.5f, Offset(x, size.height * (0.95f - ph * 0.8f)), style = Stroke(1.5f))
    }
}

private fun DrawScope.grid(c: Color, t: Float, a: Float) {
    val step = 28f; val off = t * step
    var x = size.width * 0.4f + off % step
    while (x < size.width) { drawLine(c.copy(alpha = a), Offset(x, 0f), Offset(x, size.height), 1f); x += step }
    var y = off % step
    while (y < size.height) { drawLine(c.copy(alpha = a), Offset(size.width * 0.4f, y), Offset(size.width, y), 1f); y += step }
    drawCircle(c.copy(alpha = a * 2.5f), 4f, Offset(size.width * (0.45f + 0.5f * t), size.height * 0.5f))
}

private fun DrawScope.orbits(c: Color, t: Float, a: Float) {
    val cx = size.width * 0.8f; val cy = size.height * 0.45f
    repeat(3) { i ->
        val r = size.height * (0.18f + i * 0.12f)
        drawCircle(c.copy(alpha = a), r, Offset(cx, cy), style = Stroke(1.2f))
        val ang = (t * (1 + i * 0.5f) + i * 0.33f) * TAU
        drawCircle(c.copy(alpha = a * 2.5f), 5f, Offset(cx + cos(ang) * r, cy + sin(ang) * r))
    }
}

private fun DrawScope.gear(c: Color, t: Float, a: Float) {
    val cx = size.width * 0.84f; val cy = size.height * 0.45f; val r = size.height * 0.3f
    rotate(t * 360f, Offset(cx, cy)) {
        drawCircle(c.copy(alpha = a * 1.5f), r, Offset(cx, cy), style = Stroke(6f))
        repeat(10) { k -> val ang = k * TAU / 10
            drawLine(c.copy(alpha = a * 1.5f), Offset(cx + cos(ang) * r, cy + sin(ang) * r), Offset(cx + cos(ang) * (r + 10f), cy + sin(ang) * (r + 10f)), 7f) }
    }
}

private fun DrawScope.blobs(c: Color, t: Float, a: Float) {
    repeat(3) { i ->
        val ang = (t + i / 3f) * TAU
        drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a * 2f), Color.Transparent),
            Offset(size.width * (0.7f + 0.12f * cos(ang)), size.height * (0.45f + 0.2f * sin(ang))), size.height * 0.45f),
            size.height * 0.45f, Offset(size.width * (0.7f + 0.12f * cos(ang)), size.height * (0.45f + 0.2f * sin(ang))))
    }
}

private fun DrawScope.clouds(c: Color, t: Float, a: Float) {
    repeat(3) { i ->
        val x = ((t + i * 0.33f) % 1f) * (size.width * 1.2f) - size.width * 0.1f
        val y = size.height * (0.25f + i * 0.2f)
        listOf(0f to 0f, 14f to -6f, 28f to 0f).forEach { (dx, dy) -> drawCircle(c.copy(alpha = a * 1.2f), 12f, Offset(x + dx, y + dy)) }
    }
    val p = Path().apply { moveTo(size.width * 0.45f, size.height * 0.85f); quadraticTo(size.width * 0.75f, size.height * 0.05f, size.width, size.height * 0.4f) }
    drawPath(p, c.copy(alpha = a), style = Stroke(1.5f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f), -t * 160f)))
}
