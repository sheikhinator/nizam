package com.nizam.app.ui.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * Luminex - the evolved glass.
 *
 * A translucent surface with a faint caustic sheen and a hairline edge. It reads as
 * real glass on every Android version because it is light, not a live backdrop blur.
 * One extra gradient, no extra layer, no extra cost worth measuring.
 */
fun Modifier.luminex(
    shape: Shape = Radius.card,
    glow: Color = Ascend.reactorCyan,
    strength: Float = 0.88f
): Modifier = composed {
    val c = MaterialTheme.colorScheme
    val light = c.background.luminance() > 0.5f
    val base = if (light) Color.White else c.surfaceBright
    val body = base.copy(alpha = if (light) strength else strength * 0.9f)
        .compositeOver(c.background.copy(alpha = 0.6f))

    val sheen = Brush.verticalGradient(
        listOf(
            Color.White.copy(alpha = if (light) 0.5f else 0.07f),
            Color.Transparent,
            glow.copy(alpha = if (light) 0.04f else 0.06f)
        )
    )
    val caustic = Brush.horizontalGradient(
        listOf(
            Color.Transparent,
            glow.copy(alpha = if (light) 0.07f else 0.09f),
            Color.Transparent
        )
    )
    val edge = Color.White.copy(alpha = if (light) 0.7f else 0.1f)

    this
        .clip(shape)
        .background(body)
        .background(sheen)
        .background(caustic)
        .border(BorderStroke(0.8.dp, edge), shape)
}
