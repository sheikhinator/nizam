package com.nizam.app.ui.design

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * Living Glass — the merged language. Three worlds in one material:
 *   · Glass  (the skin)   — the frosted surface every card already uses (see frost / styledCard).
 *   · Strata (the clarity)— DepthCard's left spine and Gauge's fill, so a card reads as a layer.
 *   · Bloom  (the pulse)  — LivingNudge's warm gradient and breathing dot.
 * All of it honours Motion.calm: under Calm the pulse and fills hold still, never removed.
 */

/**
 * A glass card that reads as a stratum: a soft accent spine down its leading edge, and an optional
 * gauge-fill under the content. Drop-in sibling of Group — same glass, more legible depth.
 */
@Composable
fun DepthCard(
    spine: Color,
    modifier: Modifier = Modifier,
    gauge: Float? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().styledCard(Radius.card).height(IntrinsicSize.Min)
    ) {
        // the depth spine — brightest at the top, fading down, like a core sample
        Box(
            Modifier.padding(start = 12.dp, top = 15.dp, bottom = 15.dp)
                .width(3.dp).fillMaxHeight().clip(Radius.pill)
                .background(Brush.verticalGradient(listOf(spine, spine.copy(alpha = 0.35f))))
        )
        Column(Modifier.weight(1f).padding(start = 13.dp, top = 15.dp, end = 16.dp, bottom = 15.dp)) {
            content()
            gauge?.let { Spacer(Modifier.height(12.dp)); Gauge(it, spine) }
        }
    }
}

/** A thin gauge-fill: the track plus an accent fill that eases in (still under Calm). */
@Composable
fun Gauge(progress: Float, color: Color, modifier: Modifier = Modifier) {
    val target = progress.coerceIn(0f, 1f)
    val fill by if (Motion.still || Motion.calm) androidx.compose.runtime.remember(target) { androidx.compose.runtime.mutableFloatStateOf(target) }
    else animateFloatAsState(target, tween(1100, easing = Motion.arrive), label = "gauge")
    Box(
        modifier.fillMaxWidth().height(4.dp).clip(Radius.pill)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f))
    ) {
        Box(
            Modifier.fillMaxWidth(fill).fillMaxHeight().clip(Radius.pill)
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.7f), color)))
        )
    }
}

/**
 * A warm, living nudge: Atlas noticing something and offering. A soft two-tone gradient pill with a
 * gently pulsing dot — Bloom's warmth, dialled to quiet. The pulse stills under Calm.
 */
@Composable
fun LivingNudge(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.tertiary,
    onClick: (() -> Unit)? = null,
) {
    val c = MaterialTheme.colorScheme
    val base = Modifier.fillMaxWidth().clip(Radius.pill)
        .background(Brush.horizontalGradient(listOf(tint.copy(alpha = 0.22f), c.primary.copy(alpha = 0.16f))))
    val clickable = if (onClick != null) base.pressable(haptic = true, onClick = onClick) else base
    Row(
        modifier.then(clickable).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp)
    ) {
        PulseDot(tint)
        Text(
            text, style = MaterialTheme.typography.bodyMedium, color = c.onSurface,
            fontWeight = FontWeight.Medium, maxLines = 2
        )
    }
}

/** A small dot with a slow halo that breathes outward — the sign Atlas is alive and watching for you. */
@Composable
fun PulseDot(color: Color, modifier: Modifier = Modifier) {
    val still = Motion.still || Motion.calm
    val halo by if (still) androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0.5f) }
    else rememberInfiniteTransition(label = "pulse").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2400, easing = Motion.arrive), RepeatMode.Restart), label = "halo"
    )
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        if (!still) Box(
            Modifier.size((8 + halo * 8).dp).clip(RoundedCornerShape(50))
                .background(color.copy(alpha = 0.35f * (1f - halo)))
        )
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(color))
    }
}
