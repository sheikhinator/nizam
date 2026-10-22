package com.nizam.app.ui.screens.settings

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
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nizam.app.data.prefs.SettingsRepository
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.theme.AtlasPalette
import com.nizam.app.ui.theme.buildPalette
import com.nizam.app.ui.theme.customPaletteFrom
import com.nizam.app.ui.theme.hex
import com.nizam.app.ui.theme.paletteFor
import com.nizam.app.ui.theme.toJson
import kotlinx.coroutines.launch

private val QUICK = listOf(
    0xFF000000, 0xFF0B0F1A, 0xFF0B1B3A, 0xFF1E1E1E, 0xFF2A0A12, 0xFF0A2018, 0xFF1A1030, 0xFF3A2A1A,
    0xFFFFFFFF, 0xFFF3EEE2, 0xFFFBF0F3, 0xFFEEF7F2, 0xFFEAF2FB, 0xFFF5F4F1,
    0xFFD7263D, 0xFFE0603A, 0xFFF28C28, 0xFFD4A24C, 0xFFF2D24A, 0xFFA3D94A, 0xFF3DDC84, 0xFF4FC3A1,
    0xFF2EC4B6, 0xFF4FA3E0, 0xFF2F5BD3, 0xFF4F6BE0, 0xFF9B8CD6, 0xFFB38CD6, 0xFFE86A9A, 0xFFC08552,
    0xFFB8BEC8, 0xFF8A8A8A
).map { Color(it) }

private fun hsv(c: Color): FloatArray = FloatArray(3).also { android.graphics.Color.colorToHSV(c.toArgb(), it) }
private fun fromHsv(h: Float, s: Float, v: Float) = Color(android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)))
private fun parseHex(raw: String): Color? = runCatching {
    val t = raw.trim().removePrefix("#")
    if (t.length != 6) null else Color(android.graphics.Color.parseColor("#$t"))
}.getOrNull()

/**
 * Any colour, any combination: pick the page, the buttons and the highlights; the rest is derived
 * so text stays readable. Saved as the "custom" palette — the same slot Atlas writes to when you
 * describe a theme in words.
 */
@Composable
fun ThemeStudio(settings: SettingsRepository, currentKey: String, customJson: String) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val start: AtlasPalette = remember(currentKey, customJson) {
        if (currentKey == "custom") customPaletteFrom(customJson) ?: paletteFor("void")
        else paletteFor(currentKey.ifBlank { "void" })
    }
    var surface by remember(start) { mutableStateOf(start.surface) }
    var primary by remember(start) { mutableStateOf(start.primary) }
    var accent by remember(start) { mutableStateOf(start.accent) }
    var target by remember { mutableStateOf(0) }       // 0 background, 1 primary, 2 accent
    val chosen = when (target) { 0 -> surface; 1 -> primary; else -> accent }
    fun set(c: Color) { when (target) { 0 -> surface = c; 1 -> primary = c; else -> accent = c } }

    var h by remember { mutableStateOf(0f) }
    var s by remember { mutableStateOf(0f) }
    var v by remember { mutableStateOf(0f) }
    var hexDraft by remember { mutableStateOf("") }
    // Re-seed the sliders whenever the target changes, so they always describe the colour you're editing
    LaunchedEffect(target, start) { com.nizam.app.ui.design.guard {
        val a = hsv(chosen); h = a[0]; s = a[1]; v = a[2]; hexDraft = chosen.hex()
    } }
    fun fromSliders() { val c = fromHsv(h, s, v); set(c); hexDraft = c.hex() }

    val preview = buildPalette(surface, primary, accent)

    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        // Live preview: a tiny Atlas screen in the palette being built
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(preview.surface).padding(14.dp)) {
            Column {
                Text("Today", style = MaterialTheme.typography.titleLarge, color = preview.ink)
                Text("How your theme will look", style = MaterialTheme.typography.labelMedium, color = preview.inkMuted)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(preview.card).padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(preview.accent))
                        Spacer(Modifier.width(8.dp))
                        Text("Fajr · 3 tasks · 1,240 kcal", color = preview.ink, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        Box(Modifier.clip(RoundedCornerShape(50)).background(preview.primary).padding(horizontal = 12.dp, vertical = 5.dp)) {
                            Text("Open", color = preview.surface, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }

        // Which colour you're editing
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Background" to surface, "Primary" to primary, "Accent" to accent).forEachIndexed { i, (label, c) ->
                Row(
                    Modifier.clip(RoundedCornerShape(50))
                        .border(if (target == i) 2.dp else 1.dp,
                            if (target == i) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                            RoundedCornerShape(50))
                        .clickable(role = Role.Tab) { target = i }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(c)
                        .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f), CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        // Quick swatches
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QUICK.forEach { c ->
                Box(Modifier.size(30.dp).clip(CircleShape).background(c)
                    .border(if (c == chosen) 2.5.dp else 1.dp,
                        if (c == chosen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                        CircleShape)
                    .clickable(role = Role.Button) {
                        set(c); val a = hsv(c); h = a[0]; s = a[1]; v = a[2]; hexDraft = c.hex()
                    })
            }
        }

        // Any colour at all
        GradientBar(Brush.horizontalGradient((0..6).map { fromHsv(it * 60f, 1f, 1f) }))
        Slider(h, { h = it; fromSliders() }, valueRange = 0f..360f)
        GradientBar(Brush.horizontalGradient(listOf(fromHsv(h, 0f, v), fromHsv(h, 1f, v))))
        Slider(s, { s = it; fromSliders() })
        GradientBar(Brush.horizontalGradient(listOf(Color.Black, fromHsv(h, s, 1f))))
        Slider(v, { v = it; fromSliders() })

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(hexDraft, { raw ->
                hexDraft = raw
                parseHex(raw)?.let { c -> set(c); val a = hsv(c); h = a[0]; s = a[1]; v = a[2] }
            }, singleLine = true, label = { Text("Hex") }, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(10.dp))
            Button(onClick = {
                scope.launch {
                    settings.setCustomPalette(preview.toJson())
                    settings.setAccent("custom")
                    Feedback.show("Your theme is on")
                }
            }) { Text("Apply theme") }
        }
    }
}

@Composable
private fun GradientBar(brush: Brush) {
    Canvas(Modifier.fillMaxWidth().height(6.dp).padding(horizontal = 10.dp).clip(RoundedCornerShape(3.dp))) {
        drawRect(brush)
    }
}
