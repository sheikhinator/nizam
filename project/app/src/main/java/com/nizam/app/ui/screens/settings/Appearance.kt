package com.nizam.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.outlined.Spellcheck
import androidx.compose.material.icons.outlined.Wallpaper
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Comfort
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.Segmented
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space

/** Look and comfort: how big the text is, how much moves, how dark the dark is, where colour comes from. */
@Composable
fun AppearanceScreen(onOpen: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    val s = Comfort.state(ctx)
    val sizes = listOf("0.9", "1.0", "1.15", "1.3")
    AtlasScreen(title = "Look & comfort", subtitle = "Applies everywhere, straight away") {
        item("studio") { ListRow("Theme studio", supporting = "Design kits, 30+ palettes, make your own theme, glass, 3D, fonts, motion",
            icon = androidx.compose.material.icons.Icons.Outlined.Palette) { onOpen("nx/themestudio") } }
        item("text-h") { SectionHeader("Text size") }
        item("text") {
            Column(Modifier.padding(horizontal = Space.page)) {
                Segmented(sizes, sizes.minByOrNull { kotlin.math.abs(it.toFloat() - s.textScale) }, { v ->
                    Comfort.update(ctx) { it.copy(textScale = v.toFloat()) }
                }, { when (it) { "0.9" -> "Small"; "1.0" -> "Default"; "1.15" -> "Large"; else -> "Largest" } })
                Text("The quick brown fox · تیز بھوری لومڑی", style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = Space.s))
            }
        }
        item("look-h") { SectionHeader("Display") }
        item("oled") {
            ListRow("True black", supporting = "Pure black backgrounds in dark themes. Saves battery on AMOLED screens.",
                icon = Icons.Outlined.DarkMode, trailing = { Switch(s.oled, { v -> Comfort.update(ctx) { it.copy(oled = v) } }) })
        }
        if (android.os.Build.VERSION.SDK_INT >= 31) item("you") {
            ListRow("Colours from wallpaper", supporting = "Material You: accents follow your wallpaper, on top of your theme.",
                icon = Icons.Outlined.Wallpaper, trailing = { Switch(s.wallpaperColours, { v -> Comfort.update(ctx) { it.copy(wallpaperColours = v) } }) })
        }
        item("motion") {
            ListRow("Reduce motion", supporting = "Things appear instantly instead of sliding and bouncing.",
                icon = Icons.Outlined.Animation, trailing = { Switch(s.reduceMotion, { v -> Comfort.update(ctx) { it.copy(reduceMotion = v) } }) })
        }
        item("modes-h") { SectionHeader("Modes") }
        item("zen") {
            ListRow("Zen mode", supporting = "Today shows only the essentials. No counts, no nudges on screen.",
                icon = Icons.Outlined.Animation, trailing = { Switch(s.zen, { v -> Comfort.update(ctx) { it.copy(zen = v) } }) })
        }
        item("elder") {
            ListRow("Elder mode", supporting = "Four big tiles and voice first, with larger text. For parents and grandparents.",
                icon = Icons.Outlined.FormatSize, trailing = { Switch(s.elder, { v -> Comfort.update(ctx) { it.copy(elder = v, textScale = if (v) 1.3f else 1f) } }) })
        }
        item("contrast") {
            ListRow("High contrast", supporting = "Pure black or white text and stronger outlines, easier in sunlight or with weak eyesight.",
                icon = Icons.Outlined.LightMode, trailing = { Switch(s.highContrast, { v -> Comfort.update(ctx) { it.copy(highContrast = v) } }) })
        }
        item("easyread") {
            ListRow("Easy reading", supporting = "Wider letter and line spacing in a plain font. Helps tired eyes and dyslexia.",
                icon = Icons.Outlined.Spellcheck, trailing = { Switch(s.easyRead, { v -> Comfort.update(ctx) { it.copy(easyRead = v) } }) })
        }
        item("bigtouch") {
            ListRow("Big touch targets", supporting = "Makes every button and switch easier to hit. Good for gloves, wet hands or a shaky hand.",
                icon = Icons.Outlined.TouchApp, trailing = { Switch(s.bigTouch, { v -> Comfort.update(ctx) { it.copy(bigTouch = v) } }) })
        }
        item("boldtext") {
            ListRow("Bolder text", supporting = "A heavier weight for body text so it stays readable in bright light.",
                icon = Icons.Outlined.FormatSize, trailing = { Switch(s.boldText, { v -> Comfort.update(ctx) { it.copy(boldText = v) } }) })
        }
        item("warmtint") {
            ListRow("Night warm tint", supporting = "A soft orange tint over Atlas for late-night reading.",
                icon = Icons.Outlined.Bedtime, trailing = { Switch(s.warmTint, { v -> Comfort.update(ctx) { it.copy(warmTint = v) } }) })
        }
        item("awake") {
            ListRow("Keep screen on", supporting = "The screen stays awake while Atlas is open. Uses more battery.",
                icon = Icons.Outlined.WbSunny, trailing = { Switch(s.keepAwake, { v -> Comfort.update(ctx) { it.copy(keepAwake = v) } }) })
        }
        item("ambient") {
            ListRow("Ambient mode when charging", supporting = "Plug in while Atlas is open and it becomes a dim bedside clock with the next prayer.",
                icon = Icons.Outlined.DarkMode, trailing = { Switch(s.ambientOnCharge, { v -> Comfort.update(ctx) { it.copy(ambientOnCharge = v) } }) })
        }
        item("note") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                Text("Themes and the colour studio are in Settings. Text size also follows Android's own font size.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
