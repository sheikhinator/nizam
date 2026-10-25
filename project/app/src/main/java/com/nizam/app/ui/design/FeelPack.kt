package com.nizam.app.ui.design

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.TouchApp
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Feel pack: small things that change how Atlas handles, all opt-in from "Feel & shortcuts".
 * - Quick-switch ring: a little handle you can drag anywhere; tap it for a ring of your recent screens.
 * - One-hand mode: long-press the handle and everything slides down within thumb reach.
 * - Morning reveal: the first open of the day rises like a sunrise with your greeting and the day ahead.
 * - Easter eggs: a few hidden delights for people who poke around.
 */
object Feel {
    private fun p(c: Context) = c.getSharedPreferences("feel", 0)
    var ring by mutableStateOf(false)
        private set
    var oneHand by mutableStateOf(false)
    var reveal by mutableStateOf(false)
        private set
    var ringOpen by mutableStateOf(false)
    private var loaded = false
    fun load(c: Context) {
        if (loaded) return; loaded = true
        ring = p(c).getBoolean("ring", false); reveal = p(c).getBoolean("reveal", false)
        handleX = p(c).getFloat("hx", 1f); handleY = p(c).getFloat("hy", 0.62f)
    }
    fun setRing(c: Context, v: Boolean) { ring = v; p(c).edit().putBoolean("ring", v).apply(); if (!v) oneHand = false }
    fun setReveal(c: Context, v: Boolean) { reveal = v; p(c).edit().putBoolean("reveal", v).apply() }
    var handleX by mutableFloatStateOf(1f)
    var handleY by mutableFloatStateOf(0.62f)
    fun saveHandle(c: Context) = p(c).edit().putFloat("hx", handleX).putFloat("hy", handleY).apply()
    fun revealDue(c: Context): Boolean = reveal && p(c).getString("revealed", "") != LocalDate.now().toString() && LocalTime.now().hour in 4..12
    fun markRevealed(c: Context) = p(c).edit().putString("revealed", LocalDate.now().toString()).apply()

    /** Shown title for a route in the ring. */
    fun label(route: String): String {
        com.nizam.app.ui.screens.hubs.HubRegistry.byRoute(route)?.let { return it.title }
        if (route.startsWith("nx/")) com.nizam.app.feature.nx.Nx.find(route.removePrefix("nx/"))?.let { return it.title }
        return route.substringAfterLast('/').replace('_', ' ').replaceFirstChar { it.uppercase() }
    }
}

/** Drawn over the whole app by the shell. */
@Composable
fun FeelOverlay(currentRoute: String?) {
    val c = LocalContext.current
    LaunchedEffect(Unit) { guard { Feel.load(c) } }
    if (Feel.ring) QuickHandle()
    if (Feel.ringOpen) QuickRing(currentRoute)
    if (Feel.reveal) MorningReveal()
}

@Composable
private fun QuickHandle() {
    val c = LocalContext.current
    val dens = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wPx = with(dens) { maxWidth.toPx() }; val hPx = with(dens) { maxHeight.toPx() }
        val size = 40.dp; val sPx = with(dens) { size.toPx() }
        val x = (Feel.handleX * (wPx - sPx)).roundToInt(); val y = (Feel.handleY * (hPx - sPx)).roundToInt()
        Box(Modifier.offset { IntOffset(x, y) }.size(size).alpha(0.82f)
            .background(Brush.radialGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))), CircleShape)
            .pointerInput(Unit) {
                detectDragGestures(onDragEnd = {
                    Feel.handleX = if (Feel.handleX > 0.5f) 1f else 0f   // snap to the nearer side
                    Feel.saveHandle(c)
                }) { change, drag ->
                    change.consume()
                    Feel.handleX = (Feel.handleX + drag.x / (wPx - sPx)).coerceIn(0f, 1f)
                    Feel.handleY = (Feel.handleY + drag.y / (hPx - sPx)).coerceIn(0.05f, 0.95f)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { Feel.ringOpen = true }, onLongPress = {
                    Feel.oneHand = !Feel.oneHand
                    com.nizam.app.ui.components.Feedback.show(if (Feel.oneHand) "One-hand mode: hold the handle again to exit" else "One-hand mode off")
                })
            }, contentAlignment = Alignment.Center) {
            Text(if (Feel.oneHand) "⇣" else "◎", color = MaterialTheme.colorScheme.onPrimary, fontSize = 18.sp)
        }
    }
}

@Composable
private fun QuickRing(currentRoute: String?) {
    val c = LocalContext.current
    val recents = remember { com.nizam.app.ui.screens.hubs.RecentFeatures.recent(c).filter { it != currentRoute }.distinct().take(7) }
    val items = listOf("today" to "Today") + recents.map { it to Feel.label(it) }
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { guard { anim.animateTo(1f, tween(260, easing = FastOutSlowInEasing)) } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f * anim.value)).clickable { Feel.ringOpen = false }, contentAlignment = Alignment.Center) {
        val radius = 120.dp
        Box(Modifier.size(radius * 2 + 90.dp), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Jump to", color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.labelLarge)
                Text("tap outside to close", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.labelSmall)
            }
            items.forEachIndexed { i, (route, label) ->
                val a = -Math.PI / 2 + 2 * Math.PI * i / items.size
                val r = radius * anim.value
                Box(Modifier.offset(x = (r.value * cos(a).toFloat()).dp, y = (r.value * sin(a).toFloat()).dp).width(92.dp)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f), RoundedCornerShape(16.dp))
                    .clickable { Feel.ringOpen = false; com.nizam.app.ui.screens.search.AtlasNav.go?.invoke(route) }
                    .padding(horizontal = 8.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                    Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                }
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (Feel.oneHand) "Exit one-hand" else "One-hand mode", color = Color.White,
                modifier = Modifier.background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(50)).clickable { Feel.oneHand = !Feel.oneHand; Feel.ringOpen = false }
                    .padding(horizontal = 16.dp, vertical = 8.dp))
            Text("Search", color = Color.White,
                modifier = Modifier.background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(50)).clickable { Feel.ringOpen = false; com.nizam.app.ui.screens.search.AtlasNav.go?.invoke("search") }
                    .padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

/** The first open of the morning: a sunrise sweeps up with your greeting, then gets out of the way. */
@Composable
private fun MorningReveal() {
    val c = LocalContext.current
    var show by remember { mutableStateOf(Feel.revealDue(c)) }
    if (!show) return
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { guard {
        Feel.markRevealed(c)
        rise.animateTo(1f, tween(1600, easing = FastOutSlowInEasing))
        kotlinx.coroutines.delay(1600)
        show = false
    } }
    val name = remember { runCatching { com.nizam.app.feature.presence.Presence.atlasName(c) }.getOrDefault("Atlas") }
    AnimatedVisibility(show, enter = fadeIn(), exit = fadeOut(tween(500))) {
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B1230), Color(0xFF3B2A5A), Color(0xFFF2A65A))))
            .clickable { show = false }, contentAlignment = Alignment.Center) {
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val h = maxHeight
                Box(Modifier.align(Alignment.BottomCenter).offset(y = h * (0.35f - 0.5f * rise.value)).size(220.dp)
                    .background(Brush.radialGradient(listOf(Color(0xFFFFE6A8), Color(0xFFFFB45A), Color.Transparent)), CircleShape))
            }
            Column(Modifier.alpha(rise.value), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (LocalTime.now().hour < 12) "Good morning" else "Good day", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.SemiBold)
                Text(LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM")), color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp,
                    modifier = Modifier.padding(top = 6.dp))
                Text("$name is ready. Tap to begin.", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp, modifier = Modifier.padding(top = 18.dp))
            }
        }
    }
}

/** Easter eggs: tap the Atlas mark seven times quickly for a surprise. */
object EasterEggs {
    private var taps = 0; private var first = 0L
    fun logoTap(c: Context) {
        val now = System.currentTimeMillis()
        if (now - first > 2500) { first = now; taps = 0 }
        taps++
        if (taps == 7) {
            taps = 0
            Celebrate.now()
            val lines = listOf("You found me. Bismillah, let's make today count.", "Secret unlocked: you're the kind of person who taps things seven times.",
                "Atlas carries the world. You carry Atlas. Fair trade.", "Shh. The best features are the ones you discover.")
            com.nizam.app.ui.components.Feedback.show(lines.random())
            c.getSharedPreferences("feel", 0).edit().putInt("eggs", c.getSharedPreferences("feel", 0).getInt("eggs", 0) + 1).apply()
        }
    }
}

@Composable
fun FeelSettingsScreen() {
    val c = LocalContext.current
    LaunchedEffect(Unit) { guard { Feel.load(c) } }
    var tick by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(
            Triple("Quick-switch handle", "A small handle you can drag anywhere. Tap: ring of your recent screens. Hold: one-hand mode.", Feel.ring),
            Triple("Morning reveal", "The first open of each morning rises like a sunrise with your greeting.", Feel.reveal)
        ).forEachIndexed { i, (t, d, on) ->
            Group {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t, fontWeight = FontWeight.SemiBold)
                        Text(d, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(on, { v -> if (i == 0) Feel.setRing(c, v) else Feel.setReveal(c, v); tick++ })
                }
            }
        }
        Text("Also worth knowing: shake the phone to undo, swipe from the edge and draw to command (Draw to command), and the Atlas mark hides a secret if you tap it enough.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (tick < 0) Text("")
    }
}

val FeelSpecs: List<com.nizam.app.feature.nx.Spec> get() = listOf(
    com.nizam.app.feature.nx.ScreenSpec("feel", "Feel & shortcuts", "Quick-switch ring, one-hand mode, morning reveal and hidden extras",
        androidx.compose.material.icons.Icons.Outlined.TouchApp, Hub.Tools, "feel shortcuts quick switch ring recent one hand reach morning reveal easter egg gestures") { _, _ -> FeelSettingsScreen() })
