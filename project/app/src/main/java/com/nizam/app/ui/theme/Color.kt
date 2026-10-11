package com.nizam.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb

// Atlas runs dark by default: deep navy-black with a warm amber signal colour.
// Light
val SurfaceLight = Color(0xFFF5F4F1)
val CardLight = Color(0xFFFFFFFF)
val InkLight = Color(0xFF14171F)
val InkMutedLight = Color(0xFF5E6472)
val PrimaryLight = Color(0xFF1F6F63)
val AccentLight = Color(0xFFC2652F)
val DangerLight = Color(0xFFB3453A)

// Dark
val SurfaceDark = Color(0xFF0B0F1A)
val CardDark = Color(0xFF141A28)
val InkDark = Color(0xFFEDEFF5)
val InkMutedDark = Color(0xFF8C96AC)
val PrimaryDark = Color(0xFF4FC3A1)
val AccentDark = Color(0xFFE0603A)
val DangerDark = Color(0xFFE0685C)

// Module spine colours
object Spine {
    val Tasks = Color(0xFF2E7D6F)
    val Prayer = Color(0xFF1F6F63)
    val Finance = Color(0xFFC9832B)
    val Diet = Color(0xFF6B8E4E)
    val Gym = Color(0xFF3E5C76)
    val Notes = Color(0xFF4A5468)
    val Library = Color(0xFF8A5A3B)
    val Diary = Color(0xFF6E4A7E)
    val Scripture = Color(0xFF2C4A42)
    val Learning = Color(0xFF3A4A8C)
    val Survival = Color(0xFFA44A28)
}


/**
 * Selectable themes. Each is a full palette, not just an accent swap, so switching actually
 * changes the feel of the app rather than recolouring one button.
 */
data class AtlasPalette(
    val key: String,
    val label: String,
    val surface: Color,
    val card: Color,
    val ink: Color,
    val inkMuted: Color,
    val primary: Color,
    val accent: Color
)

val ATLAS_THEMES = listOf(
    AtlasPalette(
        "void", "Void",
        Color(0xFF0B0F1A), Color(0xFF141A28), Color(0xFFEDEFF5), Color(0xFF8C96AC),
        Color(0xFF4FC3A1), Color(0xFFE0603A)
    ),
    AtlasPalette(
        "ember", "Ember",
        Color(0xFF14100D), Color(0xFF201A15), Color(0xFFF2E9E1), Color(0xFF9E8F82),
        Color(0xFFD98E4A), Color(0xFFE06A4C)
    ),
    AtlasPalette(
        "moss", "Moss",
        Color(0xFF0C130F), Color(0xFF161F19), Color(0xFFE8EFE8), Color(0xFF8AA08F),
        Color(0xFF6FB98A), Color(0xFFC9A227)
    ),
    AtlasPalette(
        "ink", "Ink",
        Color(0xFF0D0D10), Color(0xFF17171C), Color(0xFFEDEDF0), Color(0xFF8E8E99),
        Color(0xFF9B8CD6), Color(0xFFE0B14A)
    ),
    AtlasPalette(
        "paper", "Paper",
        Color(0xFFF5F2EA), Color(0xFFFFFDF8), Color(0xFF1A1814), Color(0xFF6B6459),
        Color(0xFF2F6F5E), Color(0xFFB4562C)
    ),
    AtlasPalette(
        "steel", "Steel",
        Color(0xFF0F1418), Color(0xFF1A2229), Color(0xFFE6EDF3), Color(0xFF8595A3),
        Color(0xFF58A6C9), Color(0xFFE08A3C)
    ),
    AtlasPalette(
        "ocean", "Ocean",
        Color(0xFF0A1622), Color(0xFF12212F), Color(0xFFE4EEF5), Color(0xFF86A0B3),
        Color(0xFF4FA3E0), Color(0xFF2EC4B6)
    ),
    AtlasPalette(
        "royal", "Royal",
        Color(0xFF0B0E1F), Color(0xFF151A33), Color(0xFFEAEBF7), Color(0xFF8A8FB5),
        Color(0xFFD4A24C), Color(0xFF4F6BE0)
    ),
    AtlasPalette(
        "noir", "Noir",
        Color(0xFF000000), Color(0xFF0E0E0E), Color(0xFFF2F2F2), Color(0xFF8A8A8A),
        Color(0xFFEDEDED), Color(0xFFD7263D)
    ),
    AtlasPalette(
        "crimson", "Crimson",
        Color(0xFF120708), Color(0xFF1E0E10), Color(0xFFF3E6E6), Color(0xFFA38789),
        Color(0xFFD7263D), Color(0xFFF2A65A)
    ),
    AtlasPalette(
        "rose", "Rose",
        Color(0xFF170D12), Color(0xFF24151C), Color(0xFFF5E6EC), Color(0xFFA88894),
        Color(0xFFE86A9A), Color(0xFFF2A65A)
    ),
    AtlasPalette(
        "dusk", "Dusk",
        Color(0xFF16121F), Color(0xFF221B2E), Color(0xFFEEE8F5), Color(0xFF9A8FAD),
        Color(0xFFB38CD6), Color(0xFFF28C28)
    ),
    AtlasPalette(
        "matrix", "Matrix",
        Color(0xFF050A06), Color(0xFF0C140E), Color(0xFFD9F5DD), Color(0xFF7FA487),
        Color(0xFF3DDC84), Color(0xFFA3D94A)
    ),
    AtlasPalette(
        "coffee", "Coffee",
        Color(0xFF17110C), Color(0xFF231A13), Color(0xFFF0E6DA), Color(0xFFA3927E),
        Color(0xFFC08552), Color(0xFFE3C28A)
    ),
    AtlasPalette(
        "snow", "Snow",
        Color(0xFFFAFAFB), Color(0xFFFFFFFF), Color(0xFF15171C), Color(0xFF60656F),
        Color(0xFF2F5BD3), Color(0xFFE0603A)
    ),
    AtlasPalette(
        "sand", "Sand",
        Color(0xFFF3EADB), Color(0xFFFBF6EE), Color(0xFF2A2118), Color(0xFF7A6A58),
        Color(0xFFA0642A), Color(0xFF2F6F5E)
    ),
    AtlasPalette(
        "sakura", "Sakura",
        Color(0xFFFBF0F3), Color(0xFFFFFFFF), Color(0xFF2A1A20), Color(0xFF7D6570),
        Color(0xFFD2557F), Color(0xFF3E8E63)
    ),
    AtlasPalette("luminous", "Luminous", Color(0xFF07080D), Color(0xFF12141C), Color(0xFFF1F3FA), Color(0xFF8E95AB), Color(0xFF6EE7F9), Color(0xFFB388FF)),
    AtlasPalette("amethyst", "Amethyst", Color(0xFF1A1033), Color(0xFF261A47), Color(0xFFF1EBFF), Color(0xFFA99BC9), Color(0xFFB794F6), Color(0xFFFFB86B)),
    AtlasPalette("aurora", "Aurora", Color(0xFF071A1C), Color(0xFF0F2629), Color(0xFFE6FBF7), Color(0xFF87ABA6), Color(0xFF34E0A1), Color(0xFF8B7CFF)),
    AtlasPalette("sunset", "Sunset", Color(0xFF1C0F16), Color(0xFF2A1720), Color(0xFFFBEDEF), Color(0xFFB0929A), Color(0xFFFF7A59), Color(0xFFFFC857)),
    AtlasPalette("emerald", "Emerald Deen", Color(0xFF06140F), Color(0xFF0E211A), Color(0xFFE9F6EF), Color(0xFF86A597), Color(0xFF2FBF71), Color(0xFFD4AF37)),
    AtlasPalette("midnight", "Midnight Blue", Color(0xFF060B1E), Color(0xFF0E1630), Color(0xFFE8EEFF), Color(0xFF8792B8), Color(0xFF5B8CFF), Color(0xFF7DF9FF)),
    AtlasPalette("neon", "Neon", Color(0xFF050507), Color(0xFF101014), Color(0xFFF5F5FF), Color(0xFF8A8AA0), Color(0xFFFF2E97), Color(0xFF00F0FF)),
    AtlasPalette("lavender", "Lavender (light)", Color(0xFFF4F1FB), Color(0xFFFFFFFF), Color(0xFF1E1830), Color(0xFF6D6585), Color(0xFF7B5CD6), Color(0xFFE0766A)),
    AtlasPalette("sky", "Sky (light)", Color(0xFFEFF6FC), Color(0xFFFFFFFF), Color(0xFF0F1E2C), Color(0xFF5C7285), Color(0xFF1E88E5), Color(0xFFFF8A3D)),
    AtlasPalette("peach", "Peach (light)", Color(0xFFFFF3EC), Color(0xFFFFFFFF), Color(0xFF2B1B14), Color(0xFF7D6358), Color(0xFFE8704A), Color(0xFF2E8B83)),
    AtlasPalette("instruments", "Instruments (navy)", Color(0xFF0A1128), Color(0xFF111B3A), Color(0xFFE6ECFF), Color(0xFF8A97C2), Color(0xFF4CC9F0), Color(0xFFFFB454)),
    AtlasPalette("cbsafe", "Colour-blind safe", Color(0xFF0B1220), Color(0xFF131C2E), Color(0xFFF2F4F8), Color(0xFF9AA6BC), Color(0xFF56B4E9), Color(0xFFE69F00)),
    AtlasPalette("labpaper", "Lab paper (light)", Color(0xFFF3F6FB), Color(0xFFFFFFFF), Color(0xFF0E1A33), Color(0xFF55627F), Color(0xFF1560BD), Color(0xFFD9822B)),
    AtlasPalette(
        "mint", "Mint",
        Color(0xFFEEF7F2), Color(0xFFFFFFFF), Color(0xFF14231B), Color(0xFF5D7568),
        Color(0xFF1F8A63), Color(0xFFC9832B)
    )
)

fun paletteFor(key: String): AtlasPalette =
    ATLAS_THEMES.firstOrNull { it.key == key } ?: ATLAS_THEMES.first()


/** A palette the agent invented, stored as JSON. */
fun customPaletteFrom(json: String): AtlasPalette? = runCatching {
    val o = org.json.JSONObject(json)
    fun c(key: String, fallback: Long): Color {
        val raw = o.optString(key).trim()
        val hex = when {
            raw.isBlank() -> return Color(fallback)
            raw.startsWith("#") -> raw
            else -> "#$raw"          // models routinely omit the leading hash
        }
        return runCatching { Color(android.graphics.Color.parseColor(hex)) }
            .getOrElse { Color(fallback) }
    }
    AtlasPalette(
        key = "custom",
        label = o.optString("label", "Custom"),
        surface = c("surface", 0xFF0B0F1A),
        card = c("card", 0xFF141A28),
        ink = c("ink", 0xFFEDEFF5),
        inkMuted = c("inkMuted", 0xFF8C96AC),
        primary = c("primary", 0xFF4FC3A1),
        accent = c("accent", 0xFFE0603A)
    )
}.getOrNull()


/**
 * A whole palette from the three colours people actually choose: the page, the buttons and the
 * highlights. Card, text and muted text are derived so contrast always holds.
 */
fun buildPalette(surface: Color, primary: Color, accent: Color, label: String = "Custom"): AtlasPalette {
    val light = surface.luminance() > 0.5f
    val ink = if (light) Color(0xFF15171C) else Color(0xFFEDEFF5)
    return AtlasPalette(
        key = "custom", label = label,
        surface = surface,
        card = mix(surface, Color.White, if (light) 0.6f else 0.06f),
        ink = ink,
        inkMuted = mix(ink, surface, 0.42f),
        primary = primary,
        accent = accent
    )
}

fun mix(a: Color, b: Color, t: Float) = Color(
    red = a.red + (b.red - a.red) * t, green = a.green + (b.green - a.green) * t, blue = a.blue + (b.blue - a.blue) * t
)

fun Color.hex(): String = String.format("#%06X", 0xFFFFFF and this.toArgb())

fun AtlasPalette.toJson(): String = org.json.JSONObject()
    .put("label", label).put("surface", surface.hex()).put("card", card.hex()).put("ink", ink.hex())
    .put("inkMuted", inkMuted.hex()).put("primary", primary.hex()).put("accent", accent.hex()).toString()
