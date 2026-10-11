package com.nizam.app.ui.design

import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.spring
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Screen-to-screen motion. Forward: the new screen slides in along one axis while the old one sinks
 * back slightly and fades (shared-axis with depth). Back: the reverse. Between tabs: a fade-through
 * with a small scale, so tabs feel like layers of one place rather than pages in a book.
 */
object NavMotion {
    private const val D = 340
    fun enter(tab: Boolean): EnterTransition = when {
        Motion.still -> fadeIn(tween(1))
        tab -> fadeIn(tween(220, delayMillis = 60)) + scaleIn(tween(280, easing = Motion.arrive), initialScale = 0.96f)
        System.currentTimeMillis() - LastTap.at < 700 && Style.s.motion == "Full" ->
            scaleIn(spring(dampingRatio = 0.82f, stiffness = 380f), initialScale = 0.72f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(LastTap.x, LastTap.y)) + fadeIn(tween(D / 2))
        else -> slideInHorizontally(tween(D, easing = Motion.arrive)) { it / 5 } + fadeIn(tween(D / 2, delayMillis = 40))
    }
    fun exit(tab: Boolean): ExitTransition = when {
        Motion.still -> fadeOut(tween(1))
        tab -> fadeOut(tween(120))
        else -> scaleOut(tween(D, easing = Motion.arrive), targetScale = 0.94f) + fadeOut(tween(D / 2))
    }
    fun popEnter(): EnterTransition =
        if (Motion.still) fadeIn(tween(1)) else scaleIn(tween(D, easing = Motion.arrive), initialScale = 0.94f) + fadeIn(tween(D / 2))
    fun popExit(): ExitTransition =
        if (Motion.still) fadeOut(tween(1)) else slideOutHorizontally(tween(D - 40, easing = Motion.leave)) { it / 4 } + fadeOut(tween(D / 2))
}

/**
 * Celebration: a burst of confetti from the top of the screen for a real milestone.
 * Call [Celebrate.now] from anywhere; the overlay in the app shell draws it.
 */
object Celebrate {
    var burst by mutableStateOf(0)
        private set
    fun now() { if (!Motion.still) burst++ }
}

private data class Bit(val x: Float, val vx: Float, val vy: Float, val spin: Float, val size: Float, val color: Color, val shape: Int)

@Composable
fun CelebrationOverlay() {
    val n = Celebrate.burst
    if (n == 0) return
    val t = remember(n) { Animatable(0f) }
    val c = MaterialTheme.colorScheme
    val palette = listOf(c.primary, c.tertiary, c.secondary, Color(0xFFFFD166), Color(0xFFEF476F), Color(0xFF06D6A0), Color(0xFF118AB2))
    val bits = remember(n) {
        List(90) {
            Bit(Random.nextFloat(), (Random.nextFloat() - 0.5f) * 0.6f, 0.4f + Random.nextFloat() * 0.9f, Random.nextFloat() * 720f,
                6f + Random.nextFloat() * 8f, palette[Random.nextInt(palette.size)], Random.nextInt(3))
        }
    }
    LaunchedEffect(n) { t.snapTo(0f); t.animateTo(1f, tween(2600, easing = LinearEasing)) }
    if (t.value >= 1f) return
    if (Style.s.art) androidx.compose.foundation.layout.Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.Center) {
        val k = (t.value * 5f).coerceAtMost(1f).let { 1f - (1f - it) * (1f - it) * (1f - it) }
        val out = if (t.value > 0.7f) (1f - t.value) / 0.3f else 1f
        Art3D("trophy", androidx.compose.ui.unit.Dp(150f), Modifier.graphicsLayer { scaleX = k; scaleY = k; alpha = out }, float = false)
    }
    Canvas(Modifier.fillMaxSize()) {
        val p = t.value
        val fade = if (p > 0.75f) (1f - p) / 0.25f else 1f
        bits.forEach { b ->
            val x = (b.x + b.vx * p) * size.width
            val y = (-0.05f + b.vy * p + 0.9f * p * p) * size.height
            val ctr = Offset(x, y)
            rotate(b.spin * p, ctr) {
                val col = b.color.copy(alpha = fade)
                when (b.shape) {
                    0 -> drawRect(col, Offset(x - b.size / 2, y - b.size / 4), androidx.compose.ui.geometry.Size(b.size, b.size / 2))
                    1 -> drawCircle(col, b.size / 2.4f, ctr)
                    else -> drawPath(Path().apply { moveTo(x, y - b.size / 2); lineTo(x + b.size / 2, y + b.size / 2); lineTo(x - b.size / 2, y + b.size / 2); close() }, col)
                }
            }
        }
    }
}

/**
 * Liquid pull-to-refresh: pull down at the top of a list and a soft blob stretches after your finger,
 * resists, then snaps; release past the line to refresh.
 */
@Composable
fun LiquidRefresh(refreshing: Boolean, onRefresh: () -> Unit, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val pull = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val threshold = 180f
    var armed by remember { mutableStateOf(false) }
    val conn = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < 0 && pull.value > 0f) {
                    val used = maxOf(available.y, -pull.value)
                    scope.launch { pull.snapTo(pull.value + used) }
                    return Offset(0f, used)
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y > 0 && source == NestedScrollSource.UserInput) {
                    val resisted = available.y * (1f - (pull.value / (threshold * 2.2f)).coerceIn(0f, 0.85f))
                    scope.launch { pull.snapTo(pull.value + resisted) }
                    val now = pull.value + resisted >= threshold
                    if (now && !armed) { armed = true; haptics.tick() } else if (!now) armed = false
                    return Offset(0f, available.y)
                }
                return Offset.Zero
            }
            override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                if (pull.value >= threshold) { haptics.success(); onRefresh() }
                armed = false
                pull.animateTo(0f, Motion.bouncy())
                return androidx.compose.ui.unit.Velocity.Zero
            }
        }
    }
    val c = MaterialTheme.colorScheme
    val spin by androidx.compose.animation.core.rememberInfiniteTransition(label = "lr").animateFloat(0f, 360f,
        androidx.compose.animation.core.infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin")
    Box(modifier.nestedScroll(conn)) {
        content()
        val h = pull.value
        if (h > 1f || refreshing) Canvas(Modifier.fillMaxWidth().height(if (refreshing) 70.dp else (h / 2.2f).dp.coerceAtMost(120.dp))) {
            val w = size.width; val depth = if (refreshing) size.height * 0.7f else size.height
            val path = Path().apply {
                moveTo(w * 0.25f, 0f)
                cubicTo(w * 0.38f, 0f, w * 0.40f, depth, w * 0.5f, depth)
                cubicTo(w * 0.60f, depth, w * 0.62f, 0f, w * 0.75f, 0f)
                close()
            }
            drawPath(path, c.primary.copy(alpha = 0.22f))
            val r = 9.dp.toPx() * (if (refreshing) 1f else (h / threshold).coerceIn(0.3f, 1f))
            drawCircle(c.primary, r, Offset(w / 2, depth - r * 1.4f))
            if (refreshing) {
                val a = spin
                drawCircle(c.surface, r * 0.45f, Offset(w / 2 + cos(Math.toRadians(a.toDouble())).toFloat() * r * 0.4f,
                    depth - r * 1.4f + sin(Math.toRadians(a.toDouble())).toFloat() * r * 0.4f))
            }
        }
    }
}

/** A modifier that reports the pointer pressing and lifting, for hold-to-confirm style controls. */
fun Modifier.onPressHold(onDown: () -> Unit, onUp: () -> Unit): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false); onDown()
        do { val e = awaitPointerEvent() } while (e.changes.any { it.pressed })
        onUp()
    }
}
