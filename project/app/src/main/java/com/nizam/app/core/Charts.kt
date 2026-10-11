package com.nizam.app.core

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.nizam.app.ui.design.Motion
import com.nizam.app.ui.theme.MonoNumber
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Charts drawn on a Compose Canvas — no charting dependency, and they inherit the app's theme.
 * They draw themselves in, and you can touch or drag across them to read any value.
 */

data class Point(val label: String, val value: Double)

private fun fmt(v: Double): String = if (kotlin.math.abs(v - v.roundToInt()) < 0.05) "%,d".format(v.roundToInt()) else "%,.1f".format(v)

@Composable
fun LineChart(
    points: List<Point>,
    accent: Color,
    height: Int = 120,
    fill: Boolean = true
) {
    if (points.size < 2) {
        EmptyChart("Two entries and a line appears here.")
        return
    }
    val values = points.map { it.value }
    val minValue = values.min()
    val maxValue = values.max()
    val span = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val reveal = remember(points) { Animatable(0f) }
    LaunchedEffect(points) { reveal.animateTo(1f, Motion.enter()) }
    var touch by remember { mutableStateOf<Int?>(null) }

    Column {
        Box(Modifier.fillMaxWidth().padding(bottom = 2.dp)) {
            val shown = touch?.let { points[it] } ?: points.last()
            Text("${shown.label} · ${fmt(shown.value)}", style = MaterialTheme.typography.labelMedium,
                color = if (touch != null) accent else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Canvas(Modifier.fillMaxWidth().height(height.dp).padding(vertical = 6.dp)
            .pointerInput(points) {
                fun pick(x: Float) { touch = (x / size.width * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex) }
                detectDragGestures(onDragStart = { pick(it.x) }, onDragEnd = { touch = null }, onDragCancel = { touch = null }) { c, _ -> pick(c.position.x) }
            }
            .pointerInput(points) { detectTapGestures { o -> touch = (o.x / size.width * (points.size - 1)).roundToInt().coerceIn(0, points.lastIndex) } }
        ) {
            val w = size.width
            val h = size.height
            val stepX = w / (points.size - 1)
            fun y(v: Double) = h - ((v - minValue) / span * h * 0.9 + h * 0.05).toFloat()

            // Three faint dashed guide lines
            repeat(3) { i ->
                val gy = h * (i + 1) / 4f
                drawLine(grid, Offset(0f, gy), Offset(w, gy), strokeWidth = 1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f)))
            }

            // A smooth curve through the points (monotone-ish cubic)
            val path = Path()
            val pts = points.mapIndexed { i, p -> Offset(i * stepX, y(p.value)) }
            path.moveTo(pts[0].x, pts[0].y)
            for (i in 1 until pts.size) {
                val a = pts[i - 1]; val b = pts[i]
                val cx = (a.x + b.x) / 2
                path.cubicTo(cx, a.y, cx, b.y, b.x, b.y)
            }
            clipRect(right = w * reveal.value) {
                if (fill) {
                    val area = Path().apply { addPath(path); lineTo(w, h); lineTo(0f, h); close() }
                    drawPath(area, Brush.verticalGradient(listOf(accent.copy(alpha = 0.28f), accent.copy(alpha = 0f))))
                }
                drawPath(path, accent, style = Stroke(width = 3.5f, cap = StrokeCap.Round))
            }

            val sel = touch ?: points.lastIndex
            val p = pts[sel]
            if (touch != null) drawLine(ink.copy(alpha = 0.25f), Offset(p.x, 0f), Offset(p.x, h), strokeWidth = 1.5f)
            if (reveal.value > 0.98f || touch != null) {
                drawCircle(accent.copy(alpha = 0.25f), radius = 11f, center = p)
                drawCircle(accent, radius = 5.5f, center = p)
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween) {
            Text(points.first().label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(points.last().label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun BarChart(points: List<Point>, accent: Color, height: Int = 110) {
    if (points.isEmpty()) {
        EmptyChart("Nothing to chart yet.")
        return
    }
    val maxValue = points.maxOf { it.value }.takeIf { it > 0.0 } ?: 1.0
    val grid = MaterialTheme.colorScheme.surfaceVariant
    val grow = remember(points) { Animatable(0f) }
    LaunchedEffect(points) { grow.animateTo(1f, Motion.settle()) }
    var touch by remember { mutableStateOf<Int?>(null) }

    Column {
        touch?.let { i ->
            Text("${points[i].label} · ${fmt(points[i].value)}", style = MaterialTheme.typography.labelMedium, color = accent)
        }
        Canvas(Modifier.fillMaxWidth().height(height.dp).padding(vertical = 6.dp)
            .pointerInput(points) {
                detectTapGestures { o -> val i = (o.x / (size.width / points.size)).toInt().coerceIn(0, points.lastIndex); touch = if (touch == i) null else i }
            }
        ) {
            val w = size.width
            val h = size.height
            val slot = w / points.size
            val barWidth = min(slot * 0.62f, 34f)
            points.forEachIndexed { index, point ->
                val barHeight = max(((point.value / maxValue) * h * grow.value).toFloat(), 3f)
                val x = index * slot + (slot - barWidth) / 2f
                val on = touch == null || touch == index
                drawRoundRect(
                    color = if (point.value > 0) accent.copy(alpha = if (on) 1f else 0.35f) else grid,
                    topLeft = Offset(x, h - barHeight),
                    size = Size(barWidth, barHeight),
                    cornerRadius = CornerRadius(min(barWidth / 2, 8f))
                )
            }
        }
    }
}

/** A year of activity as small squares — the pattern is the message. */
@Composable
fun Heatmap(values: List<Double>, accent: Color, columns: Int = 15) {
    if (values.isEmpty()) {
        EmptyChart("Log a few days and the pattern shows here.")
        return
    }
    val maxValue = values.max().takeIf { it > 0.0 } ?: 1.0
    val empty = MaterialTheme.colorScheme.surfaceVariant
    val rows = (values.size + columns - 1) / columns

    Canvas(Modifier.fillMaxWidth().height((rows * 16).dp).padding(vertical = 4.dp)) {
        val cell = size.width / columns
        val pad = cell * 0.14f
        values.forEachIndexed { index, value ->
            val col = index % columns
            val row = index / columns
            val intensity = (value / maxValue).toFloat().coerceIn(0f, 1f)
            drawRoundRect(
                color = if (value <= 0.0) empty else accent.copy(alpha = 0.25f + intensity * 0.75f),
                topLeft = Offset(col * cell + pad, row * cell + pad),
                size = Size(cell - pad * 2, cell - pad * 2),
                cornerRadius = CornerRadius((cell - pad * 2) * 0.28f)
            )
        }
    }
}

@Composable
fun StatRow(stats: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        stats.forEach { (label, value) ->
            Column(Modifier.weight(1f)) {
                Text(value, style = MonoNumber)
                Text(
                    label.uppercase(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyChart(message: String) {
    Box(Modifier.fillMaxWidth().height(60.dp)) {
        Text(
            message,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
