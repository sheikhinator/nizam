package com.nizam.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nizam.app.ui.design.Motion
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** The chart palette: the theme's own colour first, then companions that stay distinct in light and dark. */
@Composable
internal fun chartColors(): List<Color> {
    val p = MaterialTheme.colorScheme.primary
    return listOf(p, Color(0xFFF2994A), Color(0xFF34C38F), Color(0xFF7B8CFF), Color(0xFFF0628A), Color(0xFFE9C46A), Color(0xFF3DB2D6), Color(0xFFB07CE8))
}

/** Grows from 0 to 1 once, when the visual first appears (instant with Reduce motion). */
@Composable
private fun appear(key: Any, ms: Int = 900): Float {
    val a = remember(key) { Animatable(if (Motion.still) 1f else 0f) }
    LaunchedEffect(key) { if (!Motion.still) a.animateTo(1f, tween(ms, easing = FastOutSlowInEasing)) }
    return a.value
}

internal fun fmt(v: Double, unit: String = ""): String {
    val n = when {
        abs(v) >= 1e7 -> "%.1f cr".format(v / 1e7)
        abs(v) >= 1e5 -> "%.1f lakh".format(v / 1e5)
        abs(v) >= 10_000 -> "%,.0f".format(v)
        abs(v) >= 1000 -> "%,.0f".format(v)
        v % 1.0 == 0.0 -> "%.0f".format(v)
        else -> "%.1f".format(v)
    }
    val u = unit.trim()
    return when {
        u.isEmpty() -> n
        u.lowercase() in setOf("rs", "pkr", "$", "usd", "€", "£", "aed", "sar") -> "$u $n"
        u == "%" -> "$n%"
        else -> "$n $u"
    }
}

/** A visual's frame: soft gradient glass, a title with an icon, and an optional headline figure on the right. */
@Composable
internal fun VisualCard(title: String?, icon: String = "", headline: String? = null, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp))
        .background(Brush.verticalGradient(listOf(cs.primary.copy(alpha = 0.08f), cs.onSurface.copy(alpha = 0.03f))))
        .border(1.dp, cs.outlineVariant.copy(alpha = 0.55f), RoundedCornerShape(20.dp)).padding(16.dp)) {
        if (!title.isNullOrBlank() || headline != null) Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon.isNotEmpty()) Text(icon, modifier = Modifier.padding(end = 8.dp))
            Text(title.orEmpty(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.onSurface,
                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            headline?.let {
                Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = cs.primary,
                    modifier = Modifier.clip(CircleShape).background(cs.primary.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 4.dp))
            }
        }
        content()
    }
}

/** ```chart: bar (columns or bars), line / area (smooth, scrubbable), pie / donut (animated, tappable). */
@Composable
internal fun ChartBlock(d: ChartData) {
    val type = d.type
    val first = d.series.first().second
    val headline = when (type) {
        "pie", "donut", "doughnut" -> "Total ${fmt(first.sum(), d.unit)}"
        "line", "area" -> if (first.size >= 2) {
            val ch = first.last() - first.first()
            (if (ch >= 0) "▲ " else "▼ ") + fmt(abs(ch), d.unit)
        } else null
        else -> null
    }
    val icon = when (type) { "pie", "donut", "doughnut" -> "🍩"; "line", "area" -> "📈"; else -> "📊" }
    VisualCard(d.title.ifBlank { null }, icon, headline) {
        when (type) {
            "pie", "donut", "doughnut" -> Donut(d, hole = type != "pie")
            "line", "area" -> Lines(d)
            else -> if (d.series.size == 1 && first.size <= 7 && d.labels.all { it.length <= 9 }) Columns(d) else Bars(d)
        }
        if (d.series.size > 1) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val colors = chartColors()
            d.series.forEachIndexed { i, (name, _) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(colors[i % colors.size]))
                    Text(" ${name.ifBlank { "Series ${i + 1}" }}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

/** Vertical columns with value labels and a tap-to-highlight. */
@Composable
private fun Columns(d: ChartData) {
    val v = d.series.first().second
    val max = (v.maxOrNull() ?: 1.0).coerceAtLeast(1e-9)
    val grow = appear(d)
    var picked by remember(d) { mutableIntStateOf(v.indices.maxByOrNull { v[it] } ?: 0) }
    val colors = chartColors()
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(190.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        v.forEachIndexed { i, value ->
            val on = i == picked
            val col = colors[0]
            Column(Modifier.weight(1f).fillMaxHeight().clickable { picked = i }, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text(fmt(value, if (on) d.unit else ""), style = MaterialTheme.typography.labelSmall, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) col else cs.onSurfaceVariant, maxLines = 1, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth(0.78f).fillMaxHeight((value / max * 0.78 * grow).toFloat().coerceIn(0.02f, 0.78f))
                    .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                    .background(Brush.verticalGradient(listOf(col.copy(alpha = if (on) 1f else 0.75f), col.copy(alpha = if (on) 0.55f else 0.3f)))))
                Text(d.labels.getOrNull(i) ?: "${i + 1}", style = MaterialTheme.typography.labelSmall, color = if (on) cs.onSurface else cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Horizontal bars (long labels, many items, several series): ranked, with a soft track behind each. */
@Composable
private fun Bars(d: ChartData) {
    val max = d.series.flatMap { it.second }.maxOrNull()?.coerceAtLeast(1e-9) ?: 1.0
    val n = d.series.maxOf { it.second.size }
    val grow = appear(d)
    val colors = chartColors()
    val cs = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        (0 until n).forEach { i ->
            Column {
                Text(d.labels.getOrNull(i) ?: "#${i + 1}", style = MaterialTheme.typography.labelLarge, color = cs.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                d.series.forEachIndexed { si, (_, v) ->
                    val value = v.getOrNull(i) ?: 0.0
                    val col = colors[si % colors.size]
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Box(Modifier.weight(1f).height(16.dp).clip(CircleShape).background(col.copy(alpha = 0.12f))) {
                            Box(Modifier.fillMaxWidth((value / max * grow).toFloat().coerceIn(0.02f, 1f)).height(16.dp).clip(CircleShape)
                                .background(Brush.horizontalGradient(listOf(col.copy(alpha = 0.55f), col))))
                        }
                        Text(fmt(value, d.unit), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold,
                            modifier = Modifier.widthIn(min = 70.dp).padding(start = 10.dp), textAlign = TextAlign.End, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** Smooth lines with a gradient under them, a light grid with values, and touch to read any point. */
@Composable
private fun Lines(d: ChartData) {
    val all = d.series.flatMap { it.second }
    val max = (all.maxOrNull() ?: 1.0); val min = minOf(0.0, all.minOrNull() ?: 0.0)
    val span = (max - min).coerceAtLeast(1e-9)
    val n = d.series.maxOf { it.second.size }
    val grow = appear(d, 1100)
    var touch by remember(d) { mutableStateOf<Int?>(null) }
    val colors = chartColors()
    val cs = MaterialTheme.colorScheme
    val grid = cs.outlineVariant
    Row {
        Column(Modifier.height(170.dp).padding(end = 6.dp), verticalArrangement = Arrangement.SpaceBetween) {
            listOf(max, (max + min) / 2, min).forEach { Text(fmt(it), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant) }
        }
        Box(Modifier.weight(1f).height(170.dp)) {
            Canvas(Modifier.fillMaxWidth().height(170.dp).pointerInput(d) {
                fun pick(x: Float) { touch = ((x / size.width) * (n - 1)).let { kotlin.math.round(it).toInt() }.coerceIn(0, n - 1) }
                detectDragGestures(onDragStart = { pick(it.x) }, onDragEnd = { }, onDrag = { c, _ -> pick(c.position.x) })
            }.pointerInput(d) { detectTapGestures { o -> touch = ((o.x / size.width) * (n - 1)).let { kotlin.math.round(it).toInt() }.coerceIn(0, n - 1) } }) {
                val w = size.width; val h = size.height
                for (g in 0..4) { val y = h * g / 4f; drawLine(grid.copy(alpha = 0.35f), Offset(0f, y), Offset(w, y), 1f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 8f))) }
                fun x(i: Int) = if (n <= 1) w / 2 else w * i / (n - 1).toFloat()
                fun y(v: Double) = (h - (v - min) / span * h * 0.9 - h * 0.05).toFloat()
                d.series.forEachIndexed { si, (_, v) ->
                    if (v.isEmpty()) return@forEachIndexed
                    val col = colors[si % colors.size]
                    val pts = v.mapIndexed { i, value -> Offset(x(i), y(value)) }
                    val path = Path().apply {
                        moveTo(pts[0].x, pts[0].y)
                        for (i in 1 until pts.size) {
                            val p0 = pts[i - 1]; val p1 = pts[i]; val mx = (p0.x + p1.x) / 2
                            cubicTo(mx, p0.y, mx, p1.y, p1.x, p1.y)
                        }
                    }
                    val reveal = w * grow
                    clipRect(right = reveal) {
                        if (d.series.size == 1 || d.type == "area") {
                            val fill = Path().apply { addPath(path); lineTo(pts.last().x, h); lineTo(pts.first().x, h); close() }
                            drawPath(fill, Brush.verticalGradient(listOf(col.copy(alpha = 0.32f), col.copy(alpha = 0.02f))))
                        }
                        drawPath(path, col, style = Stroke(6f, cap = StrokeCap.Round))
                        pts.forEach { p -> drawCircle(cs.surface, 6f, p); drawCircle(col, 4f, p) }
                    }
                    if (grow >= 0.99f) { drawCircle(col.copy(alpha = 0.25f), 16f, pts.last()); drawCircle(col, 7f, pts.last()) }
                }
                touch?.let { i -> drawLine(cs.onSurface.copy(alpha = 0.4f), Offset(x(i), 0f), Offset(x(i), h), 2f) }
            }
            touch?.let { i ->
                val frac = if (n <= 1) 0.5f else i / (n - 1).toFloat()
                Column(Modifier.align(if (frac > 0.6f) Alignment.TopStart else Alignment.TopEnd).clip(RoundedCornerShape(10.dp))
                    .background(cs.surface.copy(alpha = 0.95f)).border(1.dp, cs.outlineVariant, RoundedCornerShape(10.dp)).padding(8.dp)) {
                    Text(d.labels.getOrNull(i) ?: "#${i + 1}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    d.series.forEachIndexed { si, (name, v) ->
                        v.getOrNull(i)?.let { Text((if (name.isNotBlank()) "$name: " else "") + fmt(it, d.unit), style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold, color = colors[si % colors.size]) }
                    }
                }
            }
        }
    }
    // x labels: all when few, otherwise first / middle / last
    val shown = if (d.labels.size <= 6) d.labels.indices.toList() else listOf(0, d.labels.size / 2, d.labels.lastIndex)
    Row(Modifier.fillMaxWidth().padding(top = 6.dp, start = 34.dp)) {
        shown.forEachIndexed { k, i ->
            Text(d.labels.getOrNull(i).orEmpty(), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1,
                modifier = Modifier.weight(1f), textAlign = when (k) { 0 -> TextAlign.Start; shown.lastIndex -> TextAlign.End; else -> TextAlign.Center })
        }
    }
    if (touch == null) Text("Touch the chart to read any point", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant.copy(alpha = 0.7f),
        modifier = Modifier.padding(top = 4.dp))
}

/** Pie or donut: segments sweep in, a tap lifts one out and shows it in the middle. */
@Composable
private fun Donut(d: ChartData, hole: Boolean) {
    val values = d.series.first().second.map { it.coerceAtLeast(0.0) }
    val total = values.sum().coerceAtLeast(1e-9)
    val sweep = appear(d, 1100)
    var picked by remember(d) { mutableStateOf<Int?>(null) }
    val colors = chartColors()
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(164.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(164.dp).pointerInput(d) {
                detectTapGestures { o ->
                    val c = Offset(size.width / 2f, size.height / 2f)
                    val ang = ((Math.toDegrees(atan2((o.y - c.y).toDouble(), (o.x - c.x).toDouble())) + 450) % 360)
                    if (hypot((o.x - c.x).toDouble(), (o.y - c.y).toDouble()) > size.width / 2) { picked = null; return@detectTapGestures }
                    var acc = 0.0
                    values.forEachIndexed { i, v -> val s = v / total * 360; if (ang >= acc && ang < acc + s) { picked = if (picked == i) null else i }; acc += s }
                }
            }) {
                val r = size.minDimension / 2f
                var start = -90f
                val stroke = r * 0.30f
                values.forEachIndexed { i, v ->
                    val s = (v / total * 360 * sweep).toFloat()
                    val lift = if (picked == i) 8f else 0f
                    val mid = Math.toRadians((start + s / 2).toDouble())
                    val dx = (kotlin.math.cos(mid) * lift).toFloat(); val dy = (kotlin.math.sin(mid) * lift).toFloat()
                    val col = colors[i % colors.size].copy(alpha = if (picked == null || picked == i) 1f else 0.4f)
                    if (hole) drawArc(col, start + 1f, (s - 2f).coerceAtLeast(0.5f), false, topLeft = Offset(stroke / 2 + 10f + dx, stroke / 2 + 10f + dy),
                        size = Size(size.width - stroke - 20f, size.height - stroke - 20f), style = Stroke(stroke, cap = StrokeCap.Butt))
                    else drawArc(col, start, s, true, topLeft = Offset(10f + dx, 10f + dy), size = Size(size.width - 20f, size.height - 20f))
                    start += s
                }
            }
            if (hole) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                val i = picked
                Text(if (i == null) fmt(total, d.unit) else "${"%.0f".format(values[i] / total * 100)}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                Text(if (i == null) "Total" else d.labels.getOrNull(i).orEmpty(), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 80.dp))
            }
        }
        Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            values.forEachIndexed { i, v ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (picked == i) colors[i % colors.size].copy(alpha = 0.14f) else Color.Transparent)
                    .clickable { picked = if (picked == i) null else i }.padding(horizontal = 6.dp, vertical = 3.dp)) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(colors[i % colors.size]))
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(d.labels.getOrNull(i) ?: "#${i + 1}", style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${"%.0f".format(v / total * 100)}% · ${fmt(v, d.unit)}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

/** ```stats: key-figure tiles with a soft glow, and ▲ ▼ when a note says up or down. */
@Composable
internal fun StatsBlock(items: List<JSONObject>) {
    val colors = chartColors()
    val cs = MaterialTheme.colorScheme
    val grow = appear(items.size)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEachIndexed { r, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                row.forEachIndexed { c, o ->
                    val col = colors[(r * 2 + c) % colors.size]
                    val note = o.optString("note")
                    val trend = when { note.trimStart().startsWith("+") || note.contains("up", true) -> "▲"; note.trimStart().startsWith("-") || note.contains("down", true) -> "▼"; else -> "" }
                    Box(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp))
                        .background(Brush.linearGradient(listOf(col.copy(alpha = 0.22f), col.copy(alpha = 0.06f))))
                        .border(1.dp, col.copy(alpha = 0.35f), RoundedCornerShape(18.dp))) {
                        Canvas(Modifier.size(70.dp).align(Alignment.TopEnd).offset(18.dp, (-18).dp)) { drawCircle(col.copy(alpha = 0.18f * grow)) }
                        Column(Modifier.padding(14.dp)) {
                            Text(o.optString("label").uppercase(), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, fontWeight = FontWeight.Bold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(o.optString("value"), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = col,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                            if (note.isNotBlank()) Text((if (trend.isNotEmpty()) "$trend " else "") + note.trimStart('+'), style = MaterialTheme.typography.labelSmall,
                                color = when (trend) { "▲" -> Color(0xFF34C38F); "▼" -> Color(0xFFF0628A); else -> cs.onSurfaceVariant }, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** ```timeline: numbered stops on a glowing line, each in its own card. */
@Composable
internal fun TimelineBlock(items: List<JSONObject>) {
    val cs = MaterialTheme.colorScheme
    val colors = chartColors()
    VisualCard(null) {
        items.forEachIndexed { i, o ->
            val col = colors[i % colors.size]
            Row(Modifier.height(IntrinsicSize.Min)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(30.dp)) {
                    Box(Modifier.size(26.dp).clip(CircleShape).background(Brush.radialGradient(listOf(col, col.copy(alpha = 0.6f)))), contentAlignment = Alignment.Center) {
                        Text("${i + 1}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Black, color = Color.White)
                    }
                    if (i < items.lastIndex) Box(Modifier.width(2.dp).weight(1f).background(Brush.verticalGradient(listOf(col.copy(alpha = 0.6f), colors[(i + 1) % colors.size].copy(alpha = 0.6f)))))
                }
                Column(Modifier.weight(1f).padding(start = 12.dp, bottom = if (i < items.lastIndex) 12.dp else 0.dp).clip(RoundedCornerShape(14.dp))
                    .background(cs.onSurface.copy(alpha = 0.04f)).padding(12.dp)) {
                    o.optString("when").takeIf { it.isNotBlank() }?.let { Text(it.uppercase(), style = MaterialTheme.typography.labelSmall, color = col, fontWeight = FontWeight.Bold) }
                    Text(o.optString("title"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    o.optString("detail").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant) }
                }
            }
        }
    }
}

/** ```progress: animated gradient bars toward each target. */
@Composable
internal fun ProgressBlock(items: List<JSONObject>) {
    val colors = chartColors()
    val cs = MaterialTheme.colorScheme
    val grow = appear(items.size)
    VisualCard(null, "🎯") {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items.forEachIndexed { i, o ->
                val v = o.optDouble("value", 0.0).let { if (it.isNaN()) 0.0 else it }
                val max = o.optDouble("max", 100.0).let { if (it.isNaN() || it <= 0) 100.0 else it }
                val f = (v / max).toFloat().coerceIn(0f, 1f)
                val col = colors[i % colors.size]
                Column {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(o.optString("label"), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Text(if (max == 100.0) "${(f * 100).toInt()}%" else "${fmt(v)} / ${fmt(max)}", style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold, color = col)
                    }
                    Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(12.dp).clip(CircleShape).background(col.copy(alpha = 0.14f))) {
                        Box(Modifier.fillMaxWidth(f * grow).height(12.dp).clip(CircleShape).background(Brush.horizontalGradient(listOf(col.copy(alpha = 0.6f), col))))
                    }
                    if (f >= 1f) Text("✓ Reached", style = MaterialTheme.typography.labelSmall, color = Color(0xFF34C38F), modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
    }
}
