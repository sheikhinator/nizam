package com.nizam.app.ui.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/**
 * Living light.
 *
 * These are top level extensions on purpose: any surface can borrow a little life
 * with one call, with no wrapper composable and no allocation of a new layout node.
 */

/** A slow breathing outline that makes a surface feel like it is holding warmth. */
fun Modifier.reactorGlow(
    color: Color = Ascend.reactorCyan,
    intensity: Float = 0.6f,
    shape: Shape = Radius.card
): Modifier = composed {
    val t = rememberInfiniteTransition(label = "reactorGlow")
    val breath by t.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.85f,
        animationSpec = infiniteRepeatable(
            animation = tween(Ascend.inhaleMs, easing = Ascend.glideEase),
            repeatMode = RepeatMode.Reverse
        ),
        label = "reactorBreath"
    )
    this
        .border(1.dp, color.copy(alpha = breath * intensity * 0.55f), shape)
        .background(
            Brush.radialGradient(
                colors = listOf(color.copy(alpha = breath * intensity * 0.13f), Color.Transparent)
            )
        )
}

/** A soft light washing down from the top edge. */
fun Modifier.innerGlow(
    color: Color = Ascend.reactorCyan,
    alpha: Float = 0.12f
): Modifier = composed {
    this.background(
        Brush.verticalGradient(listOf(color.copy(alpha = alpha), Color.Transparent))
    )
}

/** A pulsing halo behind a small element, like a candle behind glass. */
fun Modifier.halo(
    color: Color = Ascend.reactorCyan
): Modifier = composed {
    val t = rememberInfiniteTransition(label = "halo")
    val pulse by t.animateFloat(
        initialValue = 0.28f,
        targetValue = 0.55f,
        animationSpec = infiniteRepeatable(
            animation = tween(2800, easing = Ascend.glideEase),
            repeatMode = RepeatMode.Reverse
        ),
        label = "haloPulse"
    )
    this.drawWithContent {
        drawContent()
        val center = Offset(size.width / 2f, size.height / 2f)
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = pulse), Color.Transparent),
                center = center,
                radius = size.minDimension * 0.75f
            ),
            radius = size.minDimension * 0.75f,
            center = center
        )
    }
}

/** An expanding ring, used while Atlas is thinking. */
fun Modifier.thinkingRipple(
    color: Color = Ascend.reactorViolet
): Modifier = composed {
    val t = rememberInfiniteTransition(label = "ripple")
    val scale by t.animateFloat(
        initialValue = 0.8f,
        targetValue = 1.2f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = Ascend.driftEase),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rippleScale"
    )
    val alpha by t.animateFloat(
        initialValue = 0.55f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1400, easing = Ascend.driftEase),
            repeatMode = RepeatMode.Reverse
        ),
        label = "rippleAlpha"
    )
    this.drawWithContent {
        drawContent()
        drawCircle(
            color = color.copy(alpha = alpha),
            radius = size.minDimension * scale / 2f,
            style = Stroke(width = 1.5.dp.toPx())
        )
    }
}
