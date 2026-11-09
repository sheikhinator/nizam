package com.nizam.app.ui.style

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.feature.sci.LifeGraph
import com.nizam.app.ui.nav.Routes
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * L · your life as a system: a living graph, in the manner of a linked-notes graph view. Nodes push apart, links pull together,
 * and it keeps breathing. Drag a node, drag the space to move around, tap a node to see everything linked to it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GraphToday(x: Sci) {
    val p = LocalSci.current
    val snap = x.snap
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Head(x, "your life as a system · ${x.d.dateLine}")
        if (snap == null) { M("reading your last 30 days…", 12, p.dim); return@Column }
        val nodes = snap.nodes; val n = nodes.size
        val u = androidx.compose.ui.platform.LocalDensity.current.density
        val index = remember(snap) { nodes.mapIndexed { i, nd -> nd.id to i }.toMap() }
        val links = remember(snap) { snap.edges.mapNotNull { e -> val a = index[e.a]; val b = index[e.b]; if (a == null || b == null) null else Triple(a, b, e) } }
        val px = remember(snap) { FloatArray(n) { i -> (cos(i * 2.4) * (46 + i * 8) * u).toFloat() } }
        val py = remember(snap) { FloatArray(n) { i -> (sin(i * 2.4) * (46 + i * 8) * u).toFloat() } }
        val vx = remember(snap) { FloatArray(n) }; val vy = remember(snap) { FloatArray(n) }
        var tick by remember { mutableIntStateOf(0) }
        var sel by remember(snap) { mutableStateOf<Int?>(null) }
        var scale by remember { mutableFloatStateOf(1f) }
        var pan by remember { mutableStateOf(Offset.Zero) }
        var held by remember { mutableIntStateOf(-1) }
        var center by remember { mutableStateOf(Offset.Zero) }
        // the physics: everything repels, links are springs, a soft pull to the middle, and a slow drift so it never freezes
        LaunchedEffect(snap) {
            var t = 0f
            while (true) {
                withFrameNanos { }
                t += 0.016f
                val k = 52f * u
                for (i in 0 until n) { var fx = 0f; var fy = 0f
                    for (j in 0 until n) if (i != j) { val dx = px[i] - px[j]; val dy = py[i] - py[j]; val d2 = (dx * dx + dy * dy).coerceAtLeast(25f * u * u); val f = k * k / d2; val d = sqrt(d2); fx += dx / d * f; fy += dy / d * f }
                    fx -= px[i] * 0.012f; fy -= py[i] * 0.016f
                    fx += sin(t * 0.7f + i) * 0.3f * u; fy += cos(t * 0.6f + i * 1.7f) * 0.3f * u
                    vx[i] = (vx[i] + fx * 0.02f) * 0.86f; vy[i] = (vy[i] + fy * 0.02f) * 0.86f }
                links.forEach { (a, b, e) -> val dx = px[b] - px[a]; val dy = py[b] - py[a]; val d = hypot(dx, dy).coerceAtLeast(1f); val rest = (if (e.kind == "corr") 120f else 58f) * u
                    val f = (d - rest) * 0.012f; val ux = dx / d * f; val uy = dy / d * f; vx[a] += ux; vy[a] += uy; vx[b] -= ux; vy[b] -= uy }
                for (i in 0 until n) if (i != held) { px[i] += vx[i].coerceIn(-6f * u, 6f * u); py[i] += vy[i].coerceIn(-6f * u, 6f * u) }
                tick++
            }
        }
        fun screen(i: Int) = Offset(center.x + pan.x + px[i] * scale, center.y + pan.y + py[i] * scale)
        fun hit(o: Offset): Int { var best = -1; var bd = 26f * u * 26f * u; for (i in 0 until n) { val s = screen(i); val d = (s.x - o.x) * (s.x - o.x) + (s.y - o.y) * (s.y - o.y); if (d < bd * (if (nodes[i].kind == "area") 4f else 1f)) { bd = d / (if (nodes[i].kind == "area") 4f else 1f); best = i } }; return best }
        val measurer = rememberTextMeasurer()
        val near = remember(sel, snap) { sel?.let { s -> links.filter { it.first == s || it.second == s }.map { if (it.first == s) it.second else it.first }.toSet() + s } }
        SciBox(Modifier.fillMaxWidth().weight(1f)) {
            Canvas(Modifier.fillMaxSize()
                .pointerInput(snap) { detectDragGestures(onDragStart = { o -> held = hit(o) }, onDragEnd = { held = -1 }, onDragCancel = { held = -1 }) { ch, d -> ch.consume(); if (held >= 0) { px[held] += d.x / scale; py[held] += d.y / scale; vx[held] = 0f; vy[held] = 0f } else pan += d } }
                .pointerInput(snap) { detectTapGestures { o -> val h = hit(o); sel = if (h < 0 || h == sel) null else h } }) {
                tick
                center = Offset(size.width / 2f, size.height / 2f)
                fun col(kind: String): Color = when (kind) { "area" -> p.a; "task" -> p.v; "promise" -> p.w; "person" -> p.g; "note" -> p.text; else -> p.dim }
                links.forEach { (a, b, e) ->
                    val on = near == null || (a in near && b in near && (a == sel || b == sel))
                    val c = when (e.kind) { "corr" -> if (e.weight > 0) p.g else p.r; "person" -> p.g; "wiki" -> p.a; else -> p.dim }
                    val sa = screen(a); val sb = screen(b)
                    drawLine(c.copy(alpha = if (on) 0.85f else 0.12f), sa, sb, if (e.kind == "corr") 3.5f else 2f, pathEffect = if (e.kind == "corr" && e.weight < 0) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null)
                    if (e.kind == "corr" && on) { val mid = Offset((sa.x + sb.x) / 2f, (sa.y + sb.y) / 2f); val tl = measurer.measure(e.label, TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = c))
                        drawRect(p.panel, Offset(mid.x - tl.size.width / 2f - 4f, mid.y - tl.size.height / 2f), Size(tl.size.width + 8f, tl.size.height.toFloat())); drawText(tl, topLeft = Offset(mid.x - tl.size.width / 2f, mid.y - tl.size.height / 2f)) }
                }
                for (i in 0 until n) {
                    val nd = nodes[i]; val s = screen(i); val on = near == null || i in near; val a = if (on) 1f else 0.2f; val c = col(nd.kind)
                    if (nd.kind == "area") {
                        val name = measurer.measure(nd.label.uppercase(), TextStyle(fontSize = 8.sp, fontFamily = FontFamily.Monospace, color = p.dim.copy(alpha = a), letterSpacing = 1.2.sp))
                        val value = measurer.measure(nd.value.ifBlank { "·" }, TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = p.text.copy(alpha = a)))
                        val w = maxOf(name.size.width, value.size.width) + 22f; val h = name.size.height + value.size.height + 12f
                        drawRect(p.panel, Offset(s.x - w / 2, s.y - h / 2), Size(w, h)); drawRect(c.copy(alpha = a), Offset(s.x - w / 2, s.y - h / 2), Size(w, h), style = Stroke(if (i == sel) 5f else 2.5f))
                        drawText(name, topLeft = Offset(s.x - name.size.width / 2f, s.y - h / 2 + 5f)); drawText(value, topLeft = Offset(s.x - value.size.width / 2f, s.y - h / 2 + 5f + name.size.height))
                    } else {
                        drawCircle(p.panel, 9f, s); drawCircle(c.copy(alpha = a), if (i == sel) 11f else 7f, s)
                        if (on) drawText(measurer.measure(nd.label, TextStyle(fontSize = 9.sp, fontFamily = FontFamily.Monospace, color = p.dim.copy(alpha = a)), maxLines = 1), topLeft = Offset(s.x + 12f, s.y - 12f))
                    }
                }
            }
        }
        val s = sel
        if (s != null && s < n) {
            val nd = nodes[s]
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row { M(nd.label, 14, bold = true, mono = false, maxLines = 1); M("  ${nd.kind}" + if (nd.value.isNotBlank()) " · ${nd.value}" else "", 11, p.dim) }
                val ns = snap.neighbours(nd.id)
                Lab("linked to (${ns.size})")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(2.dp)) { ns.take(10).forEach { (o, e) -> Act(o.label.take(18) + if (e.kind == "corr") " ${e.label}" else "", if (e.kind == "corr") (if (e.weight > 0) p.g else p.r) else p.a) { sel = index[o.id] } } }
                Row { nd.route?.let { r -> Act("open ${nd.label.take(16)}", p.w) { x.open(r) } }; Act("clear") { sel = null } }
            }
        } else Column {
            M("${n} nodes · ${links.size} links. Tap a node to see what it is linked to; drag nodes or the space.", 11, p.dim, mono = false)
            Row { M("── ", 10, p.g); M("move together  ", 10, p.dim); M("- - ", 10, p.r); M("move opposite  ", 10, p.dim); M("● ", 10, p.v); M("task ", 10, p.dim); M("● ", 10, p.w); M("promise ", 10, p.dim); M("● ", 10, p.g); M("person", 10, p.dim) }
            if (snap.corr.isEmpty()) M("No links between areas yet: Atlas draws one only after ${LifeGraph.MIN_DAYS} days where both areas have numbers.", 10, p.dim, mono = false)
        }
        Row { Act("zoom +") { scale = (scale * 1.25f).coerceAtMost(3f) }; Act("zoom −") { scale = (scale / 1.25f).coerceAtLeast(0.4f) }; Act("centre") { pan = Offset.Zero; scale = 1f }; Act("now: " + (x.first?.title?.take(16) ?: "clear"), p.w) { x.open(Routes.TASKS) } }
        CmdBar { x.open(Routes.ASK) }
        Spacer(Modifier.height(96.dp))
    }
}
