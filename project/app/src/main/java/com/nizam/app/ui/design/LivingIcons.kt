package com.nizam.app.ui.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Living UI: depth, light and motion that follows meaning. Every icon sits in a small glass tile with real
 * depth (lit top, shaded foot, soft tinted shadow) and moves in the way its subject does: money glints,
 * health beats, faith glows slowly, learning sparks, time ticks, weather and travel drift. Motion is read
 * only while drawing, so lists never recompose for it; Reduce motion makes everything still.
 */
enum class Nature { GLINT, BEAT, GLOW, SPARK, TICK, DRIFT, FLOAT }

object Living {
    private val rules: List<Pair<Regex, Nature>> = listOf(
        Regex("payment|wallet|money|receipt|sell|currency|savings|paid|account_?balance|attach_?money|credit|price|shopping|redeem|toll", RegexOption.IGNORE_CASE) to Nature.GLINT,
        Regex("favorite|heart|fitness|monitor_?heart|medication|health|spa|self_?improvement|directions_?run|bloodtype|vaccines", RegexOption.IGNORE_CASE) to Nature.BEAT,
        Regex("mosque|menu_?book|auto_?stories|brightness|nights_?stay|wb_?twilight|star|bedtime", RegexOption.IGNORE_CASE) to Nature.GLOW,
        Regex("school|lightbulb|auto_?awesome|psychology|science|tips|emoji_?objects|bolt|insights|quiz", RegexOption.IGNORE_CASE) to Nature.SPARK,
        Regex("timer|alarm|schedule|hourglass|update|history|av_?timer|event|today|calendar", RegexOption.IGNORE_CASE) to Nature.TICK,
        Regex("cloud|wb_?sunny|air|water|flight|travel|explore|map|navigation|directions|place|public|sailing|commute|luggage", RegexOption.IGNORE_CASE) to Nature.DRIFT,
    )

    /** The nature of an icon, read from its name ("Outlined.Payments" → GLINT). */
    fun natureOf(icon: ImageVector): Nature {
        val n = icon.name.substringAfterLast('.').replace(Regex("([a-z])([A-Z])"), "$1_$2")
        return rules.firstOrNull { it.first.containsMatchIn(n) }?.second ?: Nature.FLOAT
    }
}

/** A shared slow clock (0…1 over [periodMs]) read in draw/layer blocks only. */
@Composable
private fun livingPhase(periodMs: Int): State<Float> {
    val t = rememberInfiniteTransition(label = "living")
    return t.animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing), RepeatMode.Restart), label = "phase")
}

/**
 * An icon in its glass well, alive. Drop-in for the old flat well: same size and tint, now with depth and
 * motion by [nature] (worked out from the icon when not given).
 */
@Composable
fun LivingIcon(
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    shape: Shape = Radius.field,
    nature: Nature = remember(icon) { Living.natureOf(icon) },
    contentDescription: String? = null,
) {
    val bg = MaterialTheme.colorScheme.background
    val still = Motion.still
    val period = when (nature) { Nature.GLINT -> 4200; Nature.BEAT -> 2400; Nature.GLOW -> 4800; Nature.SPARK -> 3600; Nature.TICK -> 2000; Nature.DRIFT -> 5200; Nature.FLOAT -> 3800 }
    val phase = if (still) null else livingPhase(period)
    val top = tint.copy(alpha = 0.30f).compositeOver(bg)
    val foot = tint.copy(alpha = 0.12f).compositeOver(bg)
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                this.shape = shape
                clip = true
                shadowElevation = 3.dp.toPx()
                spotShadowColor = tint
                ambientShadowColor = tint.copy(alpha = 0.4f)
            }
            .drawWithContent {
                // the glass body: lit from above, shaded at the foot
                drawRect(Brush.verticalGradient(listOf(top, foot)))
                // a soft highlight across the top third
                drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), 0f, this.size.height * 0.45f))
                val p = phase?.value ?: 0f
                when (nature) {
                    Nature.GLINT -> if (p < 0.32f) {
                        // a light band sweeping across, then a rest
                        val k = p / 0.32f
                        val x = -this.size.width + k * this.size.width * 3f
                        drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.32f), Color.Transparent),
                            Offset(x, 0f), Offset(x + this.size.width * 0.6f, this.size.height)))
                    }
                    Nature.GLOW -> {
                        val a = 0.10f + 0.16f * (0.5f + 0.5f * sin(p * 2 * PI).toFloat())
                        drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = a), Color.Transparent), center, this.size.minDimension * 0.7f))
                    }
                    else -> Unit
                }
                drawContent()
                if (nature == Nature.SPARK && phase != null) {
                    val tw = sin(p * 2 * PI).toFloat()
                    if (tw > 0f) drawCircle(Color.White.copy(alpha = 0.85f * tw), this.size.minDimension * 0.05f * (0.6f + tw),
                        Offset(this.size.width * 0.78f, this.size.height * 0.24f))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(iconSize).graphicsLayer {
            val p = phase?.value ?: return@graphicsLayer
            when (nature) {
                Nature.BEAT -> {
                    // lub-dub: two quick swells, then rest
                    val s = when { p < 0.12f -> sin(p / 0.12f * PI).toFloat() * 0.12f; p in 0.18f..0.28f -> sin((p - 0.18f) / 0.1f * PI).toFloat() * 0.07f; else -> 0f }
                    scaleX = 1f + s; scaleY = 1f + s
                }
                Nature.TICK -> rotationZ = sin(p * 2 * PI).toFloat() * 5f
                Nature.DRIFT -> translationX = sin(p * 2 * PI).toFloat() * 1.6.dp.toPx()
                Nature.FLOAT -> translationY = sin(p * 2 * PI).toFloat() * 1.dp.toPx()
                Nature.SPARK -> { val s = 1f + 0.04f * sin(p * 2 * PI).toFloat(); scaleX = s; scaleY = s }
                Nature.GLINT, Nature.GLOW -> Unit
            }
        })
    }
}
