package com.nizam.app.feature.rune

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.sin

/** Motion settings that apply to every Rune design. Stored in preferences, editable in the studio. */
object RuneMotionPrefs {
    @Volatile var speed = 1f          // 0.25x .. 3x, scales every duration
    @Volatile var effects = true      // shaders, grain, glow, loops
    @Volatile var loaded = false

    fun load(c: Context) {
        val p = c.getSharedPreferences("rune", 0)
        speed = p.getFloat("speed", 1f).coerceIn(0.25f, 3f)
        effects = p.getBoolean("effects", true)
        loaded = true
    }

    fun save(c: Context) {
        c.getSharedPreferences("rune", 0).edit().putFloat("speed", speed).putBoolean("effects", effects).apply()
    }

    val still: Boolean get() = !effects || com.nizam.app.ui.design.Motion.still
}

object RuneSprings {
    val names = listOf("snappy", "soft", "bouncy", "slow", "heavy", "elastic", "instant")

    fun <T> spec(name: String?, speed: Float = RuneMotionPrefs.speed): AnimationSpec<T> = when (name) {
        "soft" -> spring(0.9f, Spring.StiffnessLow * speed)
        "bouncy" -> spring(0.45f, Spring.StiffnessMediumLow * speed)
        "slow" -> spring(1f, 60f * speed)
        "heavy" -> spring(0.7f, 120f * speed)
        "elastic" -> spring(0.28f, 220f * speed)
        "instant" -> snap()
        else -> spring(0.75f, Spring.StiffnessMedium * speed)
    }
}

object RuneAnimNames {
    val enter = listOf("none", "fade", "rise", "drop", "left", "right", "scale", "pop", "flip", "spin", "bounce", "blurzoom", "swing")
    val loop = listOf("none", "float", "pulse", "breathe", "spin", "wiggle", "swing", "heartbeat", "glow", "drift")
    val press = listOf("none", "squish", "lift", "dip", "glow", "tilt")
}

/** Entrance animation: plays when the node first appears, or again when [replay] changes. */
fun Modifier.runeEnter(node: JSONObject, indexDelayMs: Int, replay: Int): Modifier = composed {
    val kind = node.optString("enter", "none")
    if (kind == "none" || RuneMotionPrefs.still) this
    else {
        val ms = (node.optInt("enterMs", 520) / RuneMotionPrefs.speed).toInt().coerceAtLeast(60)
        val delayMs = ((node.optInt("enterDelay", 0) + indexDelayMs) / RuneMotionPrefs.speed).toInt()
        val p = remember(replay, kind) { Animatable(0f) }
        LaunchedEffect(replay, kind) {
            p.snapTo(0f)
            delay(delayMs.toLong())
            val springy = kind in setOf("pop", "bounce", "swing", "flip", "spin")
            val spec: AnimationSpec<Float> = if (springy) RuneSprings.spec(node.optString("spring", "bouncy")) else tween(ms, easing = FastOutSlowInEasing)
            p.animateTo(1f, spec)
        }
        val amt = node.optDouble("enterAmt", 1.0).toFloat().coerceIn(0f, 4f)
        this.graphicsLayer {
            val v = p.value; val inv = 1f - v
            val d = density
            when (kind) {
                "fade" -> alpha = v.coerceIn(0f, 1f)
                "rise" -> { alpha = v.coerceIn(0f, 1f); translationY = 36f * d * inv * amt }
                "drop" -> { alpha = v.coerceIn(0f, 1f); translationY = -36f * d * inv * amt }
                "left" -> { alpha = v.coerceIn(0f, 1f); translationX = -56f * d * inv * amt }
                "right" -> { alpha = v.coerceIn(0f, 1f); translationX = 56f * d * inv * amt }
                "scale" -> { alpha = v.coerceIn(0f, 1f); scaleX = 0.8f + 0.2f * v; scaleY = scaleX }
                "pop" -> { alpha = (v * 3f).coerceIn(0f, 1f); scaleX = v; scaleY = v }
                "bounce" -> { alpha = (v * 4f).coerceIn(0f, 1f); translationY = -90f * d * inv * amt }
                "flip" -> { alpha = (v * 2.5f).coerceIn(0f, 1f); rotationX = -90f * inv; cameraDistance = 12f * d }
                "spin" -> { alpha = v.coerceIn(0f, 1f); rotationZ = -180f * inv * amt; scaleX = 0.4f + 0.6f * v; scaleY = scaleX }
                "blurzoom" -> { alpha = v.coerceIn(0f, 1f); scaleX = 1.25f - 0.25f * v; scaleY = scaleX }
                "swing" -> { alpha = (v * 3f).coerceIn(0f, 1f); rotationZ = 25f * inv * amt; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f) }
            }
        }
    }
}

/** Endless animation: float, pulse, breathe, spin, wiggle, swing, heartbeat, glow, drift. */
fun Modifier.runeLoop(node: JSONObject): Modifier = composed {
    val kind = node.optString("loop", "none")
    if (kind == "none" || RuneMotionPrefs.still) this
    else {
        val ms = (node.optInt("loopMs", 3000) / RuneMotionPrefs.speed).toInt().coerceAtLeast(200)
        val amt = node.optDouble("loopAmt", 1.0).toFloat().coerceIn(0f, 4f)
        val tr = rememberInfiniteTransition(label = "rune-loop")
        val t by tr.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Restart), label = "t")
        this.graphicsLayer {
            val w = sin(2f * PI.toFloat() * t); val d = density
            when (kind) {
                "float" -> translationY = -10f * d * amt * w
                "pulse" -> { val s = 1f + 0.06f * amt * (0.5f + 0.5f * w); scaleX = s; scaleY = s }
                "breathe" -> alpha = 1f - 0.35f * amt.coerceAtMost(2f) * (0.5f - 0.5f * w)
                "spin" -> rotationZ = 360f * t
                "wiggle" -> rotationZ = 4f * amt * w
                "swing" -> { rotationZ = 10f * amt * w; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f) }
                "heartbeat" -> { val b = if (t < 0.15f) sin(t / 0.15f * PI.toFloat()) else if (t in 0.25f..0.4f) 0.6f * sin((t - 0.25f) / 0.15f * PI.toFloat()) else 0f
                    val s = 1f + 0.12f * amt * b; scaleX = s; scaleY = s }
                "glow" -> alpha = 0.7f + 0.3f * (0.5f + 0.5f * w)
                "drift" -> { translationX = 14f * d * amt * w; translationY = 8f * d * amt * sin(2f * PI.toFloat() * t * 2f) }
            }
        }
    }
}

/** Press feedback (squish, lift, dip, glow, tilt) driven by a spring. */
fun Modifier.runePress(kind: String?, source: MutableInteractionSource, springName: String?): Modifier = composed {
    val k = kind.orEmpty()
    if (k.isEmpty() || k == "none") this
    else {
        val down by source.collectIsPressedAsState()
        val spec = remember(springName) { RuneSprings.spec<Float>(springName ?: "snappy") }
        val v by androidx.compose.animation.core.animateFloatAsState(if (down) 1f else 0f, spec, label = "press")
        this.graphicsLayer {
            when (k) {
                "squish" -> { scaleX = 1f - 0.07f * v; scaleY = 1f - 0.10f * v }
                "lift" -> { translationY = -6f * density * v; scaleX = 1f + 0.03f * v; scaleY = scaleX }
                "dip" -> { scaleX = 1f - 0.04f * v; scaleY = scaleX; alpha = 1f - 0.2f * v }
                "glow" -> { alpha = 1f - 0.1f * v; scaleX = 1f + 0.02f * v; scaleY = scaleX }
                "tilt" -> { rotationZ = -3f * v; scaleX = 1f - 0.03f * v; scaleY = scaleX }
            }
        }
    }
}
