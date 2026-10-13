package com.nizam.app.ui.design

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Look-and-comfort choices that apply across the whole app at once: text size, reduced motion,
 * true black for AMOLED, and colours taken from the wallpaper.
 */
object Comfort {
    data class State(val textScale: Float = 1f, val reduceMotion: Boolean = false, val oled: Boolean = false,
                     val wallpaperColours: Boolean = false, val zen: Boolean = false, val elder: Boolean = false,
                     val ambientOnCharge: Boolean = false,
                     val highContrast: Boolean = false, val easyRead: Boolean = false,
                     val bigTouch: Boolean = false, val keepAwake: Boolean = false, val warmTint: Boolean = false, val boldText: Boolean = false,
                     val calm: Boolean = true)

    private var current by mutableStateOf<State?>(null)

    private fun p(c: Context) = c.getSharedPreferences("comfort", 0)

    private fun load(c: Context) = State(
        textScale = p(c).getFloat("text", 1f),
        reduceMotion = p(c).getBoolean("still", false),
        oled = p(c).getBoolean("oled", false),
        wallpaperColours = p(c).getBoolean("you", false),
        zen = p(c).getBoolean("zen", false),
        elder = p(c).getBoolean("elder", false),
        ambientOnCharge = p(c).getBoolean("ambient", false),
        highContrast = p(c).getBoolean("contrast", false),
        easyRead = p(c).getBoolean("easy", false),
        bigTouch = p(c).getBoolean("bigtouch", false),
        keepAwake = p(c).getBoolean("awake", false),
        warmTint = p(c).getBoolean("warm", false),
        boldText = p(c).getBoolean("bold", false),
        calm = p(c).getBoolean("calm", true)
    )

    /** Read inside composition: screens redraw the moment a setting changes. */
    @Composable
    fun state(c: Context): State = current ?: load(c.applicationContext).also { current = it; Motion.still = it.reduceMotion; Motion.calm = it.calm }

    fun get(c: Context): State = current ?: load(c.applicationContext)

    enum class Preset(val label: String) {
        Minimal("Minimal"), Dashboard("Dashboard"), Elder("Elder-friendly"), Night("Night")
    }

    /** One tap sets several comfort settings together, to match a whole way of using the app. */
    fun applyPreset(c: Context, preset: Preset) {
        update(c) { s -> when (preset) {
            Preset.Minimal -> s.copy(reduceMotion = true, zen = true, textScale = 1f, elder = false, oled = s.oled, bigTouch = false)
            Preset.Dashboard -> s.copy(reduceMotion = false, zen = false, textScale = 1f, elder = false, bigTouch = false)
            Preset.Elder -> s.copy(elder = true, textScale = 1.3f, bigTouch = true, highContrast = true, reduceMotion = true, easyRead = true, boldText = true)
            Preset.Night -> s.copy(oled = true, zen = true, warmTint = true, reduceMotion = true)
        } }
    }

    fun update(c: Context, change: (State) -> State) {
        val next = change(get(c))
        p(c).edit().putFloat("text", next.textScale).putBoolean("still", next.reduceMotion)
            .putBoolean("oled", next.oled).putBoolean("you", next.wallpaperColours)
            .putBoolean("zen", next.zen).putBoolean("elder", next.elder).putBoolean("ambient", next.ambientOnCharge)
            .putBoolean("contrast", next.highContrast).putBoolean("easy", next.easyRead)
            .putBoolean("bigtouch", next.bigTouch).putBoolean("awake", next.keepAwake)
            .putBoolean("warm", next.warmTint).putBoolean("bold", next.boldText)
            .putBoolean("calm", next.calm).apply()
        Motion.still = next.reduceMotion
        Motion.calm = next.calm
        current = next
    }
}
