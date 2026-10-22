package com.nizam.app.ui.design

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nizam.app.ui.theme.MonoNumber

/** One ring of the day: how far along, its colour, what it's called, and the figure beside it. */
data class DayRing(val label: String, val progress: Float, val color: Color, val figure: String, val onClick: () -> Unit)

/**
 * The day at a glance: concentric rings that draw themselves in, with a legend you can tap.
 * Inspired by activity rings, but for a whole day: prayers, tasks, water, movement.
 */
@Composable
fun DayRings(rings: List<DayRing>, modifier: Modifier = Modifier, diameter: Dp = 128.dp) {
    val c = MaterialTheme.colorScheme
    val sweep = remember { Animatable(0f) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { sweep.animateTo(1f, Motion.bouncy()) } }
    Row(modifier.fillMaxWidth().clip(Radius.card).background(c.surface).padding(Space.l),
        verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(diameter)) {
            val stroke = diameter.toPx() / 11f
            rings.forEachIndexed { i, r ->
                val inset = stroke / 2 + i * (stroke + stroke * 0.28f)
                val s = Size(this.size.width - inset * 2, this.size.height - inset * 2)
                val tl = Offset(inset, inset)
                drawArc(r.color.copy(alpha = 0.16f), -90f, 360f, false, tl, s, style = Stroke(stroke))
                val p = (r.progress.coerceIn(0f, 1f) * sweep.value)
                if (p > 0.001f) drawArc(r.color, -90f, 360f * p, false, tl, s, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Spacer(Modifier.width(Space.l))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            rings.forEach { r ->
                Row(Modifier.fillMaxWidth().clip(Radius.chip).pressable(onClick = r.onClick).padding(vertical = Space.xs),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(Radius.pill).background(r.color))
                    Spacer(Modifier.width(Space.s))
                    Text(r.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(r.figure, style = MonoNumber, color = c.onSurfaceVariant)
                }
            }
        }
    }
}

/** Greeting that knows the day: Jummah, Ramadan, Eid, or simply the time. */
object Greeting {
    fun of(name: String, now: java.time.LocalDateTime = java.time.LocalDateTime.now()): String {
        val hijri = runCatching { java.time.chrono.HijrahDate.from(now.toLocalDate()) }.getOrNull()
        val hMonth = hijri?.get(java.time.temporal.ChronoField.MONTH_OF_YEAR) ?: 0
        val hDay = hijri?.get(java.time.temporal.ChronoField.DAY_OF_MONTH) ?: 0
        val base = when {
            hMonth == 10 && hDay <= 3 -> "Eid Mubarak"
            hMonth == 12 && hDay in 10..13 -> "Eid Mubarak"
            hMonth == 9 -> "Ramadan Kareem"
            now.dayOfWeek == java.time.DayOfWeek.FRIDAY && now.hour in 5..16 -> "Jummah Mubarak"
            now.hour < 5 -> "Still up"
            now.hour < 12 -> "Morning"
            now.hour < 17 -> "Afternoon"
            now.hour < 21 -> "Evening"
            else -> "Night"
        }
        return base + if (name.isBlank()) "" else ", $name"
    }
}
