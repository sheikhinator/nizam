package com.nizam.app.ui.style

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Colours of the scientific views: one dark set and one "paper" set. */
class SciPal(val bg: Color, val panel: Color, val line: Color, val dim: Color, val text: Color, val a: Color, val w: Color, val g: Color, val r: Color, val v: Color)

val SciDark = SciPal(Color(0xFF0A0D12), Color(0xFF0F1319), Color(0x1FFFFFFF), Color(0xFF7D8896), Color(0xFFDFE5EE), Color(0xFF3FC3F0), Color(0xFFF2B25C), Color(0xFF5FD38D), Color(0xFFFF6B7A), Color(0xFFB48CFF))
val SciPaper = SciPal(Color(0xFFF4F2EC), Color(0xFFFBFAF6), Color(0x29000000), Color(0xFF6B6F76), Color(0xFF16181C), Color(0xFF0B6FB8), Color(0xFFB9602F), Color(0xFF2E7D4F), Color(0xFFC0392B), Color(0xFF6A4BB5))
val LocalSci = compositionLocalOf { SciDark }

@Composable
fun M(t: String, size: Int = 12, color: Color = LocalSci.current.text, bold: Boolean = false, modifier: Modifier = Modifier, mono: Boolean = true, maxLines: Int = Int.MAX_VALUE, spacing: Float = 0f, serif: Boolean = false) {
    Text(t, modifier, color = color, fontSize = size.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        fontFamily = if (serif) FontFamily.Serif else if (mono) FontFamily.Monospace else FontFamily.Default, maxLines = maxLines, overflow = TextOverflow.Ellipsis, letterSpacing = spacing.sp, lineHeight = (size * 1.4f).sp)
}

@Composable fun Lab(t: String, modifier: Modifier = Modifier, color: Color = LocalSci.current.dim) = M(t.uppercase(), 9, color, modifier = modifier, spacing = 1.4f, maxLines = 1)

/** 0 → 1 once, when this first appears (or [key] changes): charts use it to draw themselves in. */
@Composable
fun rememberDrawIn(key: Any? = Unit, ms: Int = 900, delayMs: Int = 0): State<Float> {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { a.snapTo(0f); a.animateTo(1f, tween(ms, delayMs, FastOutSlowInEasing)) }
    return a.asState()
}

/** 0 → 1 → 0 forever: the heartbeat of every "live" dot and cursor. */
@Composable
fun rememberPulse(ms: Int = 1100): State<Float> = rememberInfiniteTransition(label = "pulse").animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Reverse), label = "p")

/** A number that counts up to its value. */
@Composable
fun Count(target: Float, size: Int = 19, color: Color = LocalSci.current.text, mono: Boolean = false, format: (Float) -> String = { it.toInt().toString() }) {
    val v by animateFloatAsState(target, tween(900, easing = FastOutSlowInEasing), label = "count")
    M(format(v), size, color, bold = true, mono = mono, maxLines = 1)
}

/** A line chart that draws itself in, with a pulsing dot on the newest point. */
@Composable
fun Spark(vals: List<Float>, color: Color, modifier: Modifier = Modifier, fill: Boolean = false) {
    val p by rememberDrawIn(vals); val pulse by rememberPulse()
    Canvas(modifier) {
        if (vals.size < 2) { drawLine(color.copy(alpha = 0.3f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 2f); return@Canvas }
        val mx = vals.max(); val mn = vals.min(); val span = (mx - mn).takeIf { it > 1e-6f } ?: 1f
        val pts = vals.mapIndexed { i, v -> Offset(size.width * i / (vals.size - 1), size.height - 3f - (v - mn) / span * (size.height - 6f)) }
        val upto = p * (pts.size - 1); val whole = upto.toInt().coerceAtMost(pts.size - 2); val frac = upto - whole
        val path = Path().apply { moveTo(pts[0].x, pts[0].y); for (i in 1..whole) lineTo(pts[i].x, pts[i].y) }
        val tip = Offset(pts[whole].x + (pts[whole + 1].x - pts[whole].x) * frac, pts[whole].y + (pts[whole + 1].y - pts[whole].y) * frac)
        path.lineTo(tip.x, tip.y)
        if (fill) drawPath(Path().apply { addPath(path); lineTo(tip.x, size.height); lineTo(0f, size.height); close() }, color.copy(alpha = 0.14f))
        drawPath(path, color, style = Stroke(3f))
        drawCircle(color.copy(alpha = 0.25f * (1f - pulse)), 5f + 9f * pulse, tip); drawCircle(color, 4.5f, tip)
    }
}

/** Bars that grow from the floor; the newest is brightest. */
@Composable
fun BarsChart(vals: List<Float>, color: Color, modifier: Modifier = Modifier) {
    val p by rememberDrawIn(vals)
    Canvas(modifier) {
        if (vals.isEmpty()) return@Canvas
        val mx = vals.max().takeIf { it > 0f } ?: 1f; val step = size.width / vals.size
        vals.forEachIndexed { i, v -> val k = ((p * vals.size) - i * 0.6f).coerceIn(0f, 1f); val h = (v / mx) * size.height * k
            drawRect(color.copy(alpha = if (i == vals.lastIndex) 1f else 0.45f), Offset(step * i + 2f, size.height - h), Size(step - 4f, h.coerceAtLeast(if (v > 0) 2f else 0f))) }
    }
}

/** A filling meter. */
@Composable
fun Meter(frac: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val pal = LocalSci.current
    val v by animateFloatAsState(frac.coerceIn(0f, 1f), tween(1000, easing = FastOutSlowInEasing), label = "meter")
    Canvas(modifier.height(height)) {
        val n = 24; val w = size.width / n
        for (i in 0 until n) drawRect(if (i < v * n) color else pal.line, Offset(i * w + 1f, 0f), Size(w - 2f, size.height))
    }
}

/** Five (or any) little boxes; done ones fill in one after another. */
@Composable
fun Squares(done: List<Boolean>, color: Color = LocalSci.current.a, size: Dp = 11.dp) {
    val pal = LocalSci.current; val p by rememberDrawIn(done)
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        done.forEachIndexed { i, d -> val on = d && p * done.size > i
            Box(Modifier.size(size).background(if (on) color else Color.Transparent).border(1.dp, if (on) color else pal.dim)) }
    }
}

@Composable fun SciBox(modifier: Modifier = Modifier, content: @Composable () -> Unit) { val p = LocalSci.current; Box(modifier.background(p.panel).border(1.dp, p.line)) { content() } }

/** One cell of a bordered strip. */
@Composable
fun RowScope.Cell(weight: Float = 1f, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val p = LocalSci.current
    Column(Modifier.weight(weight).border(0.5.dp, p.line).background(p.panel).let { if (onClick != null) it.clickable(onClick = onClick) else it }.padding(horizontal = 10.dp, vertical = 9.dp)) { content() }
}

/** [a link in brackets] */
@Composable fun Act(label: String, color: Color = LocalSci.current.a, onClick: () -> Unit) = M("[$label]", 12, color, modifier = Modifier.clickable(onClick = onClick).padding(end = 8.dp, top = 3.dp, bottom = 3.dp))

/** The prompt every view ends with. Its cursor blinks. */
@Composable
fun CmdBar(text: String = "> tell atlas what to do", onClick: () -> Unit) {
    val p = LocalSci.current; val blink by rememberPulse(600)
    Row(Modifier.fillMaxWidth().border(1.dp, p.a).background(p.panel).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 11.dp), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Row { M(text, 13, p.dim); M("_", 13, p.a.copy(alpha = if (blink > 0.5f) 1f else 0f)) }
        Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(8.dp).background(p.a.copy(alpha = 0.4f + 0.6f * blink), androidx.compose.foundation.shape.CircleShape)); M(" MIC", 12, p.a) }
    }
}

/** A strip of the twelve hubs. */
@Composable
fun HubStrip(onOpen: (String) -> Unit) {
    val p = LocalSci.current
    Row(Modifier.fillMaxWidth().border(1.dp, p.line).horizontalScroll(rememberScrollState())) {
        com.nizam.app.ui.design.Hub.values().forEach { h -> M(h.name.uppercase(), 10, p.dim, modifier = Modifier.clickable { onOpen(com.nizam.app.ui.nav.Routes.hub(h.name)) }.border(0.5.dp, p.line).padding(horizontal = 12.dp, vertical = 8.dp), spacing = 0.6f) }
    }
}

/** A small outlined robot whose eyes blink: the Being, in this style. */
@Composable
fun SciBot(sizeDp: Dp = 30.dp) {
    val p = LocalSci.current; val blink by rememberInfiniteTransition(label = "blink").animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "b")
    Canvas(Modifier.width(sizeDp).height(sizeDp * 1.35f)) {
        val u = size.width / 34f; fun r(x: Float, y: Float, w: Float, h: Float, c: Color, fillIt: Boolean) = if (fillIt) drawRect(c, Offset(x * u, y * u), Size(w * u, h * u)) else drawRect(c, Offset(x * u, y * u), Size(w * u, h * u), style = Stroke(1.6f * u))
        r(15f, 0f, 4f, 7f, p.dim, true); drawCircle(p.w, 3f * u, Offset(17f * u, 2f * u))
        r(4f, 7f, 26f, 18f, p.a, false); val eye = if (blink > 0.95f) 1f else 5f
        r(10f, 13f + (5f - eye) / 2f, 4f, eye, p.a, true); r(20f, 13f + (5f - eye) / 2f, 4f, eye, p.a, true)
        r(9f, 27f, 16f, 11f, p.a, false); r(11f, 38f, 4f, 7f, p.a, true); r(19f, 38f, 4f, 7f, p.a, true)
    }
}

/** The top bar in the scientific styles: one strip of every space (Today, the hubs, You) plus search. Replaces the classic title and chip row. */
@Composable
fun SciTopBar(names: List<String>, current: Int, searching: Boolean, onPick: (Int) -> Unit, onSearch: () -> Unit) {
    val p = LocalSci.current
    val state = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(current) { runCatching { state.animateScrollToItem((current - 1).coerceAtLeast(0)) } }
    Row(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 6.dp).border(1.dp, p.line).background(p.panel), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.lazy.LazyRow(Modifier.weight(1f), state = state) {
            items(names.size) { i -> val on = i == current
                Column(Modifier.clickable { onPick(i) }.border(0.5.dp, p.line)) {
                    Box(Modifier.width(1.dp).height(0.dp))
                    M(names[i].uppercase(), 10, if (on) p.a else p.dim, bold = on, modifier = Modifier.background(if (on) p.a.copy(alpha = 0.10f) else Color.Transparent).padding(horizontal = 12.dp, vertical = 10.dp), spacing = 0.8f, maxLines = 1)
                }
            }
        }
        M(if (searching) "[x]" else "[find]", 11, p.a, modifier = Modifier.clickable(onClick = onSearch).padding(horizontal = 10.dp, vertical = 9.dp))
    }
}

/** The dock in the scientific styles: four labelled cells. Hold ATLAS to talk. */
@Composable
fun SciDock(modifier: Modifier, onSearch: () -> Unit, onAsk: () -> Unit, onTalk: () -> Unit, onMind: () -> Unit, onAll: () -> Unit) {
    val p = LocalSci.current; val pulse by rememberPulse()
    Row(modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp).fillMaxWidth().height(46.dp).background(p.panel).border(1.dp, p.line)) {
        @Composable fun cell(label: String, color: Color, tap: () -> Unit, hold: (() -> Unit)? = null, dot: Boolean = false) {
            Row(Modifier.weight(1f).height(46.dp).border(0.5.dp, p.line).pointerInput(Unit) { detectTapGestures(onTap = { tap() }, onLongPress = { hold?.invoke() }) }, Arrangement.Center, Alignment.CenterVertically) {
                if (dot) Box(Modifier.size(7.dp).background(p.a.copy(alpha = 0.4f + 0.6f * pulse), androidx.compose.foundation.shape.CircleShape))
                M((if (dot) " " else "") + label, 10, color, bold = dot, spacing = 1f, maxLines = 1)
            }
        }
        cell("FIND", p.dim, onSearch); cell("ATLAS", p.a, onAsk, onTalk, dot = true); cell("MIND", p.dim, onMind); cell("ALL", p.dim, onAll)
    }
}
