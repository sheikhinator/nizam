package com.nizam.app.feature.icon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nizam.app.ui.components.Feedback

/** The Atlas diamond, drawn the way the launcher draws it, so the preview is the real thing. */
@Composable
fun AtlasMark(ink: Color, background: Color, size: Dp, selected: Boolean = false) {
    Box(
        Modifier.size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(background)
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(size * 0.28f)) else Modifier)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val u = this.size.width / 108f
            fun p(x: Float, y: Float) = Offset(x * u, y * u)
            val outer = Path().apply { moveTo(54 * u, 22 * u); lineTo(78 * u, 54 * u); lineTo(54 * u, 86 * u); lineTo(30 * u, 54 * u); close() }
            val upper = Path().apply { moveTo(54 * u, 22 * u); lineTo(66 * u, 54 * u); lineTo(42 * u, 54 * u); close() }
            val lower = Path().apply { moveTo(42 * u, 54 * u); lineTo(66 * u, 54 * u); lineTo(54 * u, 86 * u); close() }
            val inner = Path().apply { moveTo(54 * u, 44 * u); lineTo(62 * u, 54 * u); lineTo(54 * u, 64 * u); lineTo(46 * u, 54 * u); close() }
            drawPath(upper, ink.copy(alpha = 0.28f))
            drawPath(lower, ink.copy(alpha = 0.14f))
            drawPath(outer, ink, style = Stroke(width = 3.2f * u))
            drawLine(ink.copy(alpha = 0.85f), p(30f, 54f), p(78f, 54f), strokeWidth = 2.2f * u)
            drawPath(inner, lerpToWhite(ink))
        }
    }
}

private fun lerpToWhite(c: Color) = Color(
    red = c.red + (1f - c.red) * 0.4f, green = c.green + (1f - c.green) * 0.4f, blue = c.blue + (1f - c.blue) * 0.4f
)

@Composable
fun IconPicker() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(AppIcons.current(context)) }

    fun choose(v: IconVariant) {
        if (AppIcons.apply(context, v.key)) {
            current = v.key
            Feedback.show("Icon set to ${v.name}")
        } else Feedback.show("This launcher wouldn't accept the change")
    }

    Text("PRESETS", style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        AppIcons.VARIANTS.forEach { v ->
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(role = Role.Button) { choose(v) }) {
                AtlasMark(Color(v.ink), Color(v.background), 56.dp, selected = current == v.key)
                Spacer(Modifier.height(4.dp))
                Text(v.name, style = MaterialTheme.typography.labelSmall,
                    color = if (current == v.key) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    // ── Build your own: pick the mark's colour and the background's ──
    val start = AppIcons.find(current)?.takeIf { it.key.startsWith("x_") }?.key?.split("_")
    var ink by remember { mutableStateOf(start?.getOrNull(1) ?: "gold") }
    var bg by remember { mutableStateOf(start?.getOrNull(2) ?: "black") }
    val comboKey = IconCombos.key(ink, bg)
    val combo = AppIcons.find(comboKey)
    val inkColor = Color(IconCombos.INKS.first { it.key == ink }.color)
    val bgColor = Color(IconCombos.BACKGROUNDS.first { it.key == bg }.color)

    Text("BUILD YOUR OWN", style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        AtlasMark(inkColor, bgColor, 72.dp, selected = current == comboKey)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(combo?.name ?: "${IconCombos.INKS.first { it.key == ink }.name} on ${IconCombos.BACKGROUNDS.first { it.key == bg }.name}",
                style = MaterialTheme.typography.titleMedium)
            Text(
                if (combo == null) "Too little contrast to read on a home screen — pick another pairing"
                else if (current == comboKey) "This is your icon now" else "Tap apply to use it",
                style = MaterialTheme.typography.labelMedium,
                color = if (combo == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Button(enabled = combo != null && current != comboKey, onClick = { combo?.let { choose(it) } }) { Text("Apply") }
    }

    Text("Mark", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SwatchRow(IconCombos.INKS.map { Triple(it.key, it.name, Color(it.color)) }, ink) { ink = it }
    Text("Background", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    SwatchRow(IconCombos.BACKGROUNDS.map { Triple(it.key, it.name, Color(it.color)) }, bg) { bg = it }
    Text("${AppIcons.ALL.size} icons in all. Or just ask Atlas: \"make the icon gold on navy\".",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun SwatchRow(options: List<Triple<String, String, Color>>, selected: String, onPick: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        options.forEach { (key, name, color) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.clickable(role = Role.Button) { onPick(key) }) {
                Box(
                    Modifier.size(34.dp).clip(CircleShape).background(color)
                        .border(
                            if (key == selected) 2.5.dp else 1.dp,
                            if (key == selected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f),
                            CircleShape
                        )
                )
                Text(name, style = MaterialTheme.typography.labelSmall,
                    color = if (key == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
