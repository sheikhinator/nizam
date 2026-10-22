package com.nizam.app.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A squircle: corners that ease into the straight edges (continuous curvature), the way iOS and
 * premium hardware round things. Reads softer and more expensive than a plain circular corner.
 */
class Squircle(private val radius: Dp, private val smooth: Float = 0.6f) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = minOf(with(density) { radius.toPx() }, size.minDimension / 2)
        val e = r * (1 + smooth)                      // how far the curve reaches along each edge
        val w = size.width; val h = size.height
        val ex = minOf(e, w / 2); val ey = minOf(e, h / 2)
        val p = Path().apply {
            moveTo(ex, 0f)
            lineTo(w - ex, 0f)
            cubicTo(w - ex + ex * 0.55f, 0f, w, ey - ey * 0.55f, w, ey)
            lineTo(w, h - ey)
            cubicTo(w, h - ey + ey * 0.55f, w - ex + ex * 0.55f, h, w - ex, h)
            lineTo(ex, h)
            cubicTo(ex - ex * 0.55f, h, 0f, h - ey + ey * 0.55f, 0f, h - ey)
            lineTo(0f, ey)
            cubicTo(0f, ey - ey * 0.55f, ex - ex * 0.55f, 0f, ex, 0f)
            close()
        }
        return Outline.Generic(p)
    }
}

/** Edge glow: the screen's edges pulse once in the accent colour when Atlas finishes something. */
object EdgeGlow {
    var pulse by mutableStateOf(0)
        private set
    fun now() { if (!Motion.still) pulse++ }
}

@Composable
fun EdgeGlowOverlay() {
    val n = EdgeGlow.pulse
    if (n == 0) return
    val a = remember(n) { Animatable(0f) }
    LaunchedEffect(n) { com.nizam.app.ui.design.guard { a.snapTo(0f); a.animateTo(1f, tween(900, easing = LinearEasing)) } }
    if (a.value >= 1f) return
    val col = MaterialTheme.colorScheme.primary
    val alpha = sin(a.value * PI).toFloat() * 0.55f
    Canvas(Modifier.fillMaxSize()) {
        val g = 28.dp.toPx()
        drawRect(Brush.verticalGradient(listOf(col.copy(alpha = alpha), Color.Transparent), 0f, g), size = Size(size.width, g))
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, col.copy(alpha = alpha)), size.height - g, size.height),
            topLeft = Offset(0f, size.height - g), size = Size(size.width, g))
        drawRect(Brush.horizontalGradient(listOf(col.copy(alpha = alpha), Color.Transparent), 0f, g), size = Size(g, size.height))
        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, col.copy(alpha = alpha)), size.width - g, size.width),
            topLeft = Offset(size.width - g, 0f), size = Size(g, size.height))
    }
}

/**
 * Hold to confirm: press and hold while a ring fills; let go early and nothing happens.
 * Replaces "Are you sure?" dialogs for destructive actions.
 */
@Composable
fun HoldToConfirm(label: String, modifier: Modifier = Modifier, danger: Boolean = true, holdMs: Int = 900, onConfirm: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val tint = if (danger) c.error else c.primary
    val progress = remember { Animatable(0f) }
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val haptics = rememberHaptics()
    Box(modifier.height(52.dp).clip(Radius.pill).background(wash(tint, 0.14f))
        .onPressHold(
            onDown = { haptics.press(); scope.launch { progress.animateTo(1f, tween(holdMs, easing = LinearEasing)); if (progress.value >= 1f) { haptics.success(); onConfirm(); progress.snapTo(0f) } } },
            onUp = { scope.launch { if (progress.value < 1f) progress.animateTo(0f, tween(200)) } }
        ), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().graphicsLayer { scaleX = progress.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) }
            .background(wash(tint, 0.32f)))
        Text(if (progress.value > 0f) "Keep holding…" else "Hold to $label", color = tint, style = MaterialTheme.typography.labelLarge)
    }
}

/** Slide to confirm: drag the thumb to the end to send or pay. Impossible to trigger by accident. */
@Composable
fun SlideToConfirm(label: String, modifier: Modifier = Modifier, onConfirm: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val x = remember { Animatable(0f) }
    BoxWithConstraints(modifier.fillMaxWidth().height(58.dp).clip(Radius.pill).background(c.surfaceVariant)) {
        val thumb = 50.dp
        val max = with(androidx.compose.ui.platform.LocalDensity.current) { (maxWidth - thumb - 8.dp).toPx() }
        Text(label, Modifier.align(Alignment.Center).graphicsLayer { alpha = 1f - (x.value / max).coerceIn(0f, 1f) },
            style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
        Box(Modifier.padding(4.dp).offset { IntOffset(x.value.roundToInt(), 0) }.size(thumb).clip(Radius.pill).background(c.primary)
            .pointerInput(max) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (x.value > max * 0.88f) { x.animateTo(max, tween(120)); haptics.success(); onConfirm(); x.animateTo(0f, tween(400)) }
                            else x.animateTo(0f, Motion.bouncy())
                        }
                    }
                ) { _, d -> scope.launch { x.snapTo((x.value + d).coerceIn(0f, max)) } }
            }, contentAlignment = Alignment.Center) {
            Text("›", color = c.onPrimary, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

/** A tiny trend line for list rows. */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier.size(width = 64.dp, height = 22.dp), color: Color = MaterialTheme.colorScheme.primary) {
    if (values.size < 2) return
    val lo = values.min(); val span = (values.max() - lo).takeIf { it > 0 } ?: 1.0
    Canvas(modifier) {
        val step = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val px = i * step; val py = size.height - ((v - lo) / span * size.height).toFloat()
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        drawCircle(color, 2.5.dp.toPx(), Offset((values.size - 1) * step, size.height - ((values.last() - lo) / span * size.height).toFloat()))
    }
}

/** A gauge: a needle on a half-dial, e.g. how much of the month's budget is left. */
@Composable
fun Gauge(fraction: Float, label: String, value: String, modifier: Modifier = Modifier, diameter: Dp = 180.dp) {
    val c = MaterialTheme.colorScheme
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), Motion.bouncy(), label = "gauge")
    val col = when { f < 0.2f -> c.error; f < 0.45f -> Color(0xFFE0A33A); else -> c.primary }
    Box(modifier.size(width = diameter, height = diameter * 0.62f), contentAlignment = Alignment.BottomCenter) {
        Canvas(Modifier.size(diameter)) {
            val stroke = 14.dp.toPx()
            val tl = Offset(stroke, stroke); val s = Size(size.width - stroke * 2, size.height - stroke * 2)
            drawArc(c.surfaceVariant, 180f, 180f, false, tl, s, style = Stroke(stroke, cap = StrokeCap.Round))
            drawArc(col, 180f, 180f * f, false, tl, s, style = Stroke(stroke, cap = StrokeCap.Round))
            val ang = Math.toRadians((180.0 + 180.0 * f))
            val ctr = Offset(size.width / 2, size.height / 2)
            val len = size.width / 2 - stroke * 2.2f
            drawLine(c.onSurface, ctr, Offset(ctr.x + (cos(ang) * len).toFloat(), ctr.y + (sin(ang) * len).toFloat()), 3.dp.toPx(), StrokeCap.Round)
            drawCircle(c.onSurface, 6.dp.toPx(), ctr)
        }
        androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall)
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
        }
    }
}

/**
 * A swipe-to-triage deck: the top card follows your finger and tilts; fling right to keep/act,
 * left to dismiss. The next card rises from behind.
 */
@Composable
fun <T> SwipeDeck(items: List<T>, key: (T) -> String, modifier: Modifier = Modifier, onRight: (T) -> Unit, onLeft: (T) -> Unit,
                  rightLabel: String = "Keep", leftLabel: String = "Dismiss", card: @Composable (T) -> Unit) {
    val top = items.firstOrNull() ?: return
    val second = items.getOrNull(1)
    val dx = remember(key(top)) { Animatable(0f) }
    val dy = remember(key(top)) { Animatable(0f) }
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val haptics = rememberHaptics()
    val c = MaterialTheme.colorScheme
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val w = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }
        if (second != null) Box(Modifier.fillMaxWidth().graphicsLayer {
            val lift = (kotlin.math.abs(dx.value) / (w * 0.5f)).coerceIn(0f, 1f)
            scaleX = 0.94f + 0.06f * lift; scaleY = scaleX; translationY = 18f * (1 - lift)
        }) { card(second) }
        Box(Modifier.fillMaxWidth()
            .graphicsLayer { translationX = dx.value; translationY = dy.value; rotationZ = dx.value / w * 14f }
            .pointerInput(key(top)) {
                detectDragGestures(onDragEnd = {
                    scope.launch {
                        when {
                            dx.value > w * 0.3f -> { haptics.success(); dx.animateTo(w * 1.4f, tween(220)); onRight(top) }
                            dx.value < -w * 0.3f -> { haptics.tick(); dx.animateTo(-w * 1.4f, tween(220)); onLeft(top) }
                            else -> { launch { dx.animateTo(0f, Motion.bouncy()) }; dy.animateTo(0f, Motion.bouncy()) }
                        }
                    }
                }) { ch, d -> ch.consume(); scope.launch { dx.snapTo(dx.value + d.x); dy.snapTo(dy.value + d.y * 0.3f) } }
            }) {
            card(top)
            val k = (dx.value / (w * 0.3f)).coerceIn(-1f, 1f)
            if (k > 0.05f) Text(rightLabel, Modifier.align(Alignment.TopStart).padding(Space.l).graphicsLayer { alpha = k },
                color = c.primary, style = MaterialTheme.typography.titleLarge)
            if (k < -0.05f) Text(leftLabel, Modifier.align(Alignment.TopEnd).padding(Space.l).graphicsLayer { alpha = -k },
                color = c.error, style = MaterialTheme.typography.titleLarge)
        }
    }
}
