package com.nizam.app.feature.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.data.prefs.ThemeMode
import com.nizam.app.ui.design.Art3D
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.Radius
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.Style
import com.nizam.app.ui.design.pressable
import com.nizam.app.ui.design.styledCard
import com.nizam.app.ui.theme.ATLAS_THEMES
import kotlinx.coroutines.launch

object ThemeStudio {
    val all: List<Spec> = listOf(ScreenSpec("themestudio", "Theme studio", "Design kits, 30+ palettes, your own theme, and every look setting", Icons.Outlined.Palette, Hub.Tools,
        "theme style look design kit colours palette glass 3d font corners customise") { c, _ -> ThemeStudioScreen(c) })
}

@Composable
private fun Knob(label: String, options: List<String>, value: String, set: (String) -> Unit) {
    Column(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(top = 4.dp)) { items(options.size) { i -> Capsule(options[i], value == options[i]) { set(options[i]) } } }
    }
}

@Composable
private fun Toggle(label: String, sub: String, on: Boolean, set: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.titleSmall); Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Switch(on, set)
    }
}

@Composable
private fun SliderRow(label: String, v: Float, range: ClosedFloatingPointRange<Float>, set: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = Space.page, vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(v, set, valueRange = range)
    }
}

@Composable
fun ThemeStudioScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val st = Style.s
    fun up(f: (Style.S) -> Style.S) = Style.update(ctx, f)
    // custom theme maker: six colours as hue/saturation/value
    var editing by remember { mutableStateOf("primary") }
    val custom = remember { mutableStateOf(mapOf("surface" to floatArrayOf(250f, 0.45f, 0.14f), "card" to floatArrayOf(250f, 0.40f, 0.22f), "ink" to floatArrayOf(250f, 0.05f, 0.97f),
        "inkMuted" to floatArrayOf(250f, 0.15f, 0.70f), "primary" to floatArrayOf(270f, 0.45f, 0.96f), "accent" to floatArrayOf(30f, 0.60f, 1f))) }
    fun hsv(a: FloatArray) = Color.hsv(a[0], a[1], a[2])
    AtlasScreen(title = "Theme studio", subtitle = "Everything about how Atlas looks. Changes apply instantly.", hub = Hub.Tools) {
        item("preview") {
            Box(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s).styledCard(Radius.card).padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Preview", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text("Assalam o alaikum", style = MaterialTheme.typography.headlineSmall)
                        Text("This is how cards, type and buttons look.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = {}) { Text("Primary") }; OutlinedButton(onClick = {}) { Text("Other") } }
                    }
                    Art3D("sparkles", 84.dp)
                }
            }
        }
        item("kh") { SectionHeader("Design kits") }
        Style.KITS.forEach { k -> item("k" + k.name) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 4.dp).clip(Radius.card)
                .border(if (st.kit == k.name) 2.dp else 0.dp, MaterialTheme.colorScheme.primary, Radius.card).pressable { Style.set(ctx, k.s) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(k.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(k.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (st.kit == k.name) Text("✓", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge)
            }
        } }
        item("mode") { Knob("Light or dark", listOf("Follow phone", "Light", "Dark"), "", {
            scope.launch { container.settingsRepository.setThemeMode(when (it) { "Light" -> ThemeMode.LIGHT; "Dark" -> ThemeMode.DARK; else -> ThemeMode.SYSTEM }) } }) }
        item("ph") { SectionHeader("Colours (${ATLAS_THEMES.size} palettes)") }
        item("pal") {
            Column(Modifier.padding(horizontal = Space.page)) {
                (listOf(null) + ATLAS_THEMES).chunked(4).forEach { row -> Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { t -> Column(Modifier.weight(1f).pressable { scope.launch { container.settingsRepository.setAccent(t?.key ?: "") } }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.size(54.dp).clip(RoundedCornerShape(16.dp)).background(t?.surface ?: MaterialTheme.colorScheme.background)
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            Row { Box(Modifier.size(14.dp).clip(CircleShape).background(t?.primary ?: MaterialTheme.colorScheme.primary))
                                Box(Modifier.width(3.dp)); Box(Modifier.size(14.dp).clip(CircleShape).background(t?.accent ?: MaterialTheme.colorScheme.tertiary)) }
                        }
                        Text(t?.label ?: "Default", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                    } }
                    repeat(4 - row.size) { Box(Modifier.weight(1f)) }
                } }
            }
        }
        item("ch") { SectionHeader("Make your own theme") }
        item("maker") { Column(Modifier.padding(horizontal = Space.page)) {
            val c = custom.value
            Row(Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(14.dp))) {
                listOf("surface", "card", "ink", "inkMuted", "primary", "accent").forEach { k -> Box(Modifier.weight(1f).height(48.dp).background(hsv(c[k]!!)).pressable { editing = k }
                    .border(if (editing == k) 3.dp else 0.dp, Color.White, RoundedCornerShape(0.dp))) }
            }
            Text("Editing: ${mapOf("surface" to "Background", "card" to "Cards", "ink" to "Text", "inkMuted" to "Soft text", "primary" to "Main colour", "accent" to "Accent")[editing]}",
                style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            val cur = c[editing]!!
            listOf("Hue" to (0f..360f), "Saturation" to (0f..1f), "Brightness" to (0f..1f)).forEachIndexed { i, (n, r) ->
                Text(n, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(cur[i], { v -> custom.value = c + (editing to cur.copyOf().also { it[i] = v }) }, valueRange = r)
            }
            Button(onClick = {
                fun hex(k: String) = "#%06X".format(hsv(c[k]!!).toArgb() and 0xFFFFFF)
                val json = org.json.JSONObject().put("label", "My theme").apply { listOf("surface", "card", "ink", "inkMuted", "primary", "accent").forEach { put(it, hex(it)) } }.toString()
                scope.launch { container.settingsRepository.setCustomPalette(json); container.settingsRepository.setAccent("custom") }
            }) { Text("Use my theme") }
        } }
        item("sh") { SectionHeader("Shape & surface") }
        item("corners") { Knob("Corners", Style.CORNERS, st.corners) { v -> up { it.copy(corners = v) } } }
        item("round") { SliderRow("Roundness", st.cornerScale, 0.4f..2f) { v -> up { it.copy(cornerScale = v) } } }
        item("card") { Knob("Cards", Style.CARDS, st.card) { v -> up { it.copy(card = v) } } }
        item("glass") { SliderRow("Glass see-through", st.glass, 0f..1f) { v -> up { it.copy(glass = v) } } }
        item("blur") { SliderRow("Glass blur", st.blur, 8f..60f) { v -> up { it.copy(blur = v) } } }
        item("bg") { Knob("Background art", Style.BACKGROUNDS, st.bg) { v -> up { it.copy(bg = v) } } }
        item("nav") { Knob("Tab bar", Style.NAVS, st.nav) { v -> up { it.copy(nav = v) } } }
        item("th") { SectionHeader("Type & spacing") }
        item("font") { Knob("Font", Style.FONTS, st.font) { v -> up { it.copy(font = v) } } }
        item("dens") { Knob("Density", Style.DENSITIES, st.density) { v -> up { it.copy(density = v) } } }
        item("expr") { Toggle("Expressive headlines", "Big, bold titles", st.expressive) { v -> up { it.copy(expressive = v) } } }
        item("mh") { SectionHeader("Motion, 3D & extras") }
        item("motion") { Knob("Motion", Style.MOTIONS, st.motion) { v ->
            up { it.copy(motion = v) }
            com.nizam.app.ui.design.Comfort.update(ctx) { c -> c.copy(reduceMotion = v == "Off") }
        } }
        item("art") { Toggle("3D art", "Rendered 3D objects on tiles, weather, empty screens", st.art) { v -> up { it.copy(art = v) } } }
        item("hubicons") { Knob("Hub icons", listOf("3D", "Flat"), st.hubIcons) { v -> up { it.copy(hubIcons = v) } } }
        item("tilt") { Toggle("Tilt depth", "Cards and 3D art shift as you move the phone", st.tilt) { v -> up { it.copy(tilt = v) } } }
        item("glow") { Toggle("Glow accents", "Soft light behind highlights", st.glow) { v -> up { it.copy(glow = v) } } }
        item("island") { Toggle("Island", "Live pill at the top for timer, music, prayer, recording", st.island) { v -> up { it.copy(island = v) } } }
        item("sounds") { Toggle("Tap sounds", "A soft tick on every tap", st.sounds) { v -> up { it.copy(sounds = v) } } }
        item("reset") { OutlinedButton(onClick = { Style.set(ctx, Style.S()) }, modifier = Modifier.padding(Space.page)) { Text("Reset to Atlas Glass") } }
    }
}
