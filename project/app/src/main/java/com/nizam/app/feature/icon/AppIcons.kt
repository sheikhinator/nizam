package com.nizam.app.feature.icon

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Launcher icon variants, the way Telegram and Meta's apps do it: one activity-alias per look, and
 * exactly one enabled at a time. The shape never changes — only its colour.
 *
 * Android removes and re-adds the launcher entry when this switches, so the icon can take a few
 * seconds to update, and some launchers need a moment or a restart.
 */
data class IconVariant(val key: String, val name: String, val ink: Long, val background: Long)

object AppIcons {
    val VARIANTS = listOf(
        IconVariant("default", "Original", 0xFFE0603A, 0xFF0B0F1A),
        IconVariant("gold", "Gold", 0xFFD4A24C, 0xFF141008),
        IconVariant("crimson", "Crimson", 0xFFD7263D, 0xFF12060A),
        IconVariant("mono", "Monochrome", 0xFFEDEFF5, 0xFF0B0F1A),
        IconVariant("emerald", "Emerald", 0xFF4FC3A1, 0xFF06140F),
        IconVariant("ivory", "Ivory", 0xFF1A1814, 0xFFF3EEE2),
        IconVariant("violet", "Violet", 0xFF9B8CD6, 0xFF0E0A18)
    )

    /** Presets plus every generated ink × background combo. */
    val ALL: List<IconVariant> get() = VARIANTS + IconCombos.VARIANTS

    private fun alias(c: Context, key: String) =
        if (key == "default") ComponentName(c, "com.nizam.app.MainActivity")
        else ComponentName(c, "com.nizam.app.IconAlias" + key.replaceFirstChar { it.uppercase() })

    fun current(c: Context) = c.getSharedPreferences("icon", 0).getString("variant", "default")!!

    fun find(key: String): IconVariant? = ALL.firstOrNull { it.key == key }

    /** Enables the chosen alias and disables the rest — leaving none enabled would hide Atlas entirely. */
    fun apply(c: Context, key: String): Boolean {
        val chosen = find(key) ?: return false
        val pm = c.packageManager
        runCatching {
            pm.setComponentEnabledSetting(alias(c, chosen.key),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            ALL.filter { it.key != chosen.key }.forEach {
                // skip ones already off: 80+ package-manager writes per switch is needlessly slow
                val component = alias(c, it.key)
                val state = pm.getComponentEnabledSetting(component)
                val on = state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED ||
                    (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && it.key == "default")
                if (on) pm.setComponentEnabledSetting(component,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        }.onFailure { return false }
        c.getSharedPreferences("icon", 0).edit().putString("variant", chosen.key).apply()
        return true
    }

    // Colour words → ink / background keys. Order in the sentence decides the role: the first colour
    // is the mark, the second the background ("gold on black", "red and black").
    private val INK_WORDS = linkedMapOf(
        "coral" to "coral", "original" to "coral", "gold" to "gold", "yellow" to "gold", "amber" to "gold",
        "crimson" to "crimson", "red" to "crimson", "blood" to "crimson", "white" to "white",
        "emerald" to "emerald", "green" to "emerald", "violet" to "violet", "purple" to "violet",
        "sky" to "sky", "blue" to "sky", "teal" to "teal", "cyan" to "teal", "turquoise" to "teal",
        "rose" to "rose", "pink" to "rose", "orange" to "orange", "lime" to "lime", "silver" to "silver",
        "grey" to "silver", "gray" to "silver", "black" to "black", "navy" to "navy"
    )
    private val BG_WORDS = linkedMapOf(
        "midnight" to "midnight", "black" to "black", "dark" to "black", "charcoal" to "charcoal",
        "grey" to "charcoal", "gray" to "charcoal", "navy" to "navy", "blue" to "navy", "wine" to "wine",
        "red" to "wine", "maroon" to "wine", "burgundy" to "wine", "forest" to "forest", "green" to "forest",
        "cream" to "cream", "ivory" to "cream", "beige" to "cream", "white" to "white", "light" to "cream"
    )

    /** Matches a spoken description to a variant: "make the icon gold", "red and black", "gold on navy". */
    fun match(text: String): IconVariant? {
        val t = text.lowercase()
        ALL.firstOrNull { t.trim() == it.key || t.trim() == it.name.lowercase() }?.let { return it }
        // Colour words in the order they appear
        val words = Regex("[a-z]+").findAll(t).map { it.value }
            .filter { it in INK_WORDS || it in BG_WORDS }.distinct().toList()
        if (words.size >= 2) {
            val ink = INK_WORDS[words[0]]
            val bg = BG_WORDS[words[1]]
            if (ink != null && bg != null) {
                find(IconCombos.key(ink, bg))?.let { return it }
                // unreadable pairing — try it the other way round ("black and gold" → gold on black)
                val ink2 = INK_WORDS[words[1]]; val bg2 = BG_WORDS[words[0]]
                if (ink2 != null && bg2 != null) find(IconCombos.key(ink2, bg2))?.let { return it }
            }
        }
        return VARIANTS.firstOrNull { t.contains(it.key) || t.contains(it.name.lowercase()) }
            ?: when {
                t.contains("red") || t.contains("blood") -> VARIANTS.first { it.key == "crimson" }
                t.contains("black") && t.contains("white") -> VARIANTS.first { it.key == "mono" }
                t.contains("green") -> VARIANTS.first { it.key == "emerald" }
                t.contains("purple") -> VARIANTS.first { it.key == "violet" }
                t.contains("white") || t.contains("light") || t.contains("cream") -> VARIANTS.first { it.key == "ivory" }
                t.contains("yellow") || t.contains("amber") -> VARIANTS.first { it.key == "gold" }
                t.contains("original") || t.contains("reset") -> VARIANTS.first { it.key == "default" }
                else -> words.firstOrNull()?.let { w -> INK_WORDS[w]?.let { find(IconCombos.key(it, "midnight")) } }
            }
    }
}
