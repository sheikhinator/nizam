package com.nizam.app.ui.design

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/** What Atlas is doing right now, expressed as light. */
enum class PulseState { IDLE, THINKING, LISTENING, WHISPERING, UNPROMPTED, PROTECTIVE }

/**
 * The living reactor: Atlas's mind rendered as a small breathing light.
 *
 * Idle it breathes slowly. Thinking it grows tendrils that rotate. Listening it leans
 * green. Whispering and unprompted shift hue without ever blinking, so it can sit in a
 * corner for hours without becoming a notification light.
 */
@Composable
fun LivingReactor(
    modifier: Modifier = Modifier,
    state: PulseState = PulseState.IDLE,
    diameter: Dp = 48.dp
) {
    val t = rememberInfiniteTransition(label = "livingReactor")

    val breath by t.animateFloat(
        initialValue = 0.62f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = when (state) {
                    PulseState.IDLE -> 3200
                    PulseState.THINKING -> 1500
                    PulseState.LISTENING -> 1900
                    PulseState.WHISPERING -> 2600
                    PulseState.UNPROMPTED -> 2300
                    PulseState.PROTECTIVE -> 2100
                },
                easing = Ascend.glideEase
            ),
            repeatMode = RepeatMode.Reverse
        ),
        label = "reactorBreath"
    )

    val rotation by t.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(20000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "reactorRotation"
    )

    val color = when (state) {
        PulseState.IDLE -> Ascend.reactorCyan
        PulseState.THINKING -> Ascend.reactorViolet
        PulseState.LISTENING -> Ascend.reactorEmerald
        PulseState.WHISPERING -> Ascend.reactorIce
        PulseState.UNPROMPTED -> Ascend.reactorAmber
        PulseState.PROTECTIVE -> Ascend.reactorRose
    }

    Canvas(modifier = modifier.size(diameter)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val radius = size.minDimension / 2f

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = breath * 0.32f), Color.Transparent),
                center = center,
                radius = radius * 1.6f
            ),
            radius = radius * 1.6f,
            center = center
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(
                    color.copy(alpha = breath * 0.9f),
                    color.copy(alpha = breath * 0.32f),
                    Color.Transparent
                ),
                center = center
            ),
            radius = radius * 0.65f * breath,
            center = center
        )

        if (state == PulseState.THINKING || state == PulseState.LISTENING || state == PulseState.UNPROMPTED) {
            for (i in 0 until 3) {
                val angle = Math.toRadians((rotation + i * 120f).toDouble())
                val start = Offset(
                    center.x + radius * 0.65f * cos(angle).toFloat(),
                    center.y + radius * 0.65f * sin(angle).toFloat()
                )
                val end = Offset(
                    center.x + radius * 1.25f * cos(angle).toFloat(),
                    center.y + radius * 1.25f * sin(angle).toFloat()
                )
                drawLine(
                    brush = Brush.linearGradient(
                        colors = listOf(color.copy(alpha = breath * 0.55f), Color.Transparent),
                        start = start,
                        end = end
                    ),
                    start = start,
                    end = end,
                    strokeWidth = 1.5.dp.toPx()
                )
            }
        }

        drawCircle(
            color = color.copy(alpha = breath * 0.85f),
            radius = radius * 0.22f,
            center = center
        )
    }
}
