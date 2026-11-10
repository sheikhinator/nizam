package com.nizam.app.feature.rune

import androidx.compose.ui.graphics.Color
import com.nizam.app.ui.theme.AtlasPalette
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Atlas Rune colour engine. Everything is blended and stepped in OKLab (perceptually even), and text colours are
 * pushed until they pass a contrast floor, so any three seed colours you pick give a readable theme.
 */
object RuneColor {
    private fun toLinear(v: Float): Float = if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    private fun fromLinear(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return if (x <= 0.0031308f) x * 12.92f else 1.055f * x.pow(1f / 2.4f) - 0.055f
    }

    /** [L, a, b] */
    fun toLab(c: Color): FloatArray {
        val r = toLinear(c.red); val g = toLinear(c.green); val b = toLinear(c.blue)
        val l = cbrt(0.4122214708f * r + 0.5363325363f * g + 0.0514459929f * b)
        val m = cbrt(0.2119034982f * r + 0.6806995451f * g + 0.1073969566f * b)
        val s = cbrt(0.0883024619f * r + 0.2817188376f * g + 0.6299787005f * b)
        return floatArrayOf(
            0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s
        )
    }

    fun fromLab(L: Float, a: Float, b: Float, alpha: Float = 1f): Color {
        val l_ = L + 0.3963377774f * a + 0.2158037573f * b
        val m_ = L - 0.1055613458f * a - 0.0638541728f * b
        val s_ = L - 0.0894841775f * a - 1.2914855480f * b
        val l = l_ * l_ * l_; val m = m_ * m_ * m_; val s = s_ * s_ * s_
        val r = 4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s
        val g = -1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s
        val bl = -0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s
        return Color(fromLinear(r), fromLinear(g), fromLinear(bl), alpha.coerceIn(0f, 1f))
    }

    /** Lightness 0..1, chroma ~0..0.37, hue in degrees. */
    fun lch(L: Float, C: Float, hDeg: Float, alpha: Float = 1f): Color {
        val h = Math.toRadians(hDeg.toDouble())
        return fromLab(L, (C * cos(h)).toFloat(), (C * sin(h)).toFloat(), alpha)
    }

    fun lchOf(c: Color): FloatArray {
        val lab = toLab(c)
        val ch = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
        var h = Math.toDegrees(atan2(lab[2].toDouble(), lab[1].toDouble())).toFloat()
        if (h < 0) h += 360f
        return floatArrayOf(lab[0], ch, h)
    }

    /** Blend in OKLab, not in sRGB, so mid-points never go muddy. */
    fun mix(a: Color, b: Color, t: Float): Color {
        val x = toLab(a); val y = toLab(b); val k = t.coerceIn(0f, 1f)
        return fromLab(x[0] + (y[0] - x[0]) * k, x[1] + (y[1] - x[1]) * k, x[2] + (y[2] - x[2]) * k, a.alpha + (b.alpha - a.alpha) * k)
    }

    fun withL(c: Color, L: Float): Color { val x = toLab(c); return fromLab(L.coerceIn(0f, 1f), x[1], x[2], c.alpha) }
    fun shiftL(c: Color, d: Float): Color { val x = toLab(c); return fromLab((x[0] + d).coerceIn(0f, 1f), x[1], x[2], c.alpha) }
    fun rotateHue(c: Color, deg: Float): Color { val k = lchOf(c); return lch(k[0], k[1], k[2] + deg, c.alpha) }

    private fun relLum(c: Color): Float = 0.2126f * toLinear(c.red) + 0.7152f * toLinear(c.green) + 0.0722f * toLinear(c.blue)
    fun contrast(a: Color, b: Color): Float {
        val l1 = relLum(a); val l2 = relLum(b)
        return (max(l1, l2) + 0.05f) / (min(l1, l2) + 0.05f)
    }

    /** Nudge [fg] lighter or darker (away from [bg]) until it reaches [floor]:1 contrast. */
    fun ensure(fg: Color, bg: Color, floor: Float): Color {
        if (contrast(fg, bg) >= floor) return fg
        val towardLight = relLum(bg) < 0.4f
        var c = fg
        repeat(40) {
            c = shiftL(c, if (towardLight) 0.02f else -0.02f)
            if (contrast(c, bg) >= floor) return c
        }
        return if (towardLight) Color.White else Color.Black
    }

    fun hex(c: Color): String {
        val a = (c.alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
        val r = (c.red * 255f + 0.5f).toInt().coerceIn(0, 255)
        val g = (c.green * 255f + 0.5f).toInt().coerceIn(0, 255)
        val b = (c.blue * 255f + 0.5f).toInt().coerceIn(0, 255)
        return if (a == 255) "#%02X%02X%02X".format(r, g, b) else "#%02X%02X%02X%02X".format(a, r, g, b)
    }

    fun parse(s: String?, fallback: Color): Color {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return fallback
        val h = if (t.startsWith("#")) t else "#$t"
        return runCatching { Color(android.graphics.Color.parseColor(h)) }.getOrDefault(fallback)
    }

    fun isLight(c: Color) = relLum(c) > 0.35f
}

/** A resolved theme for one design (or for the whole app when a design has none of its own). */
data class RuneTheme(
    val surface: Color, val card: Color, val ink: Color, val muted: Color, val primary: Color, val accent: Color,
    val dark: Boolean, val radius: Float = 1f
) {
    fun token(name: String): Color? = when (name.lowercase()) {
        "surface", "bg", "background" -> surface
        "card" -> card
        "ink", "text", "fg" -> ink
        "muted", "faint" -> muted
        "primary" -> primary
        "accent", "accent2", "secondary" -> accent
        "white" -> Color.White
        "black" -> Color.Black
        "clear", "none", "transparent" -> Color.Transparent
        else -> null
    }

    fun toPalette(label: String = "Rune"): AtlasPalette = AtlasPalette("custom", label, surface, card, ink, muted, primary, accent)

    fun toPaletteJson(label: String = "Rune"): String = JSONObject().put("label", label).apply {
        put("surface", RuneColor.hex(surface)); put("card", RuneColor.hex(card)); put("ink", RuneColor.hex(ink))
        put("inkMuted", RuneColor.hex(muted)); put("primary", RuneColor.hex(primary)); put("accent", RuneColor.hex(accent))
    }.toString()

    companion object {
        /** The whole theme from four choices. Contrast floors: text 7:1, muted 4.5:1, accent 3:1. */
        fun from(bg: Color, text: Color?, accent: Color, accent2: Color, radius: Float = 1f): RuneTheme {
            val dark = !RuneColor.isLight(bg)
            val ink = RuneColor.ensure(text ?: if (dark) Color(0xFFF1F2F6) else Color(0xFF15171C), bg, 7f)
            val card = if (dark) RuneColor.shiftL(bg, 0.045f) else RuneColor.shiftL(bg, -0.025f).let { if (RuneColor.contrast(it, bg) < 1.02f) Color.White else it }
            val muted = RuneColor.ensure(RuneColor.mix(ink, bg, 0.42f), bg, 4.5f)
            val primary = RuneColor.ensure(accent, bg, 3f)
            return RuneTheme(bg, card, ink, muted, primary, accent2, dark, radius)
        }

        fun fromJson(o: JSONObject?): RuneTheme? {
            if (o == null || o.length() == 0) return null
            val bg = RuneColor.parse(o.optString("bg"), Color(0xFF0E0F14))
            val text = o.optString("text").takeIf { it.isNotBlank() }?.let { RuneColor.parse(it, Color.White) }
            val a1 = RuneColor.parse(o.optString("accent"), Color(0xFF5CC8A8))
            val a2 = RuneColor.parse(o.optString("accent2"), RuneColor.rotateHue(a1, 150f))
            return from(bg, text, a1, a2, o.optDouble("radius", 1.0).toFloat())
        }
    }
}

object RunePresets {
    class P(val name: String, val bg: String, val accent: String, val accent2: String, val text: String = "")

    val themes = listOf(
        P("Midnight", "#0B0E17", "#6C8CFF", "#FF7AB6"),
        P("Aurora", "#07120F", "#3DF2B0", "#7A6CFF"),
        P("Ember", "#140E0B", "#FF9A3D", "#FF5A5F"),
        P("Dusk", "#140B1E", "#FF7AA8", "#FFC86B"),
        P("Lagoon", "#06141A", "#31D0D0", "#7AE6FF"),
        P("Paper", "#F6F3EC", "#1E7A66", "#B9602F"),
        P("Snow", "#F4F6FA", "#3D5AFE", "#E91E63"),
        P("Sand", "#F3EADB", "#8A5A2B", "#2F7D6D"),
        P("Mono", "#0A0A0A", "#EDEDED", "#8A8A8A"),
        P("Neon", "#05050A", "#00F0FF", "#FF2BD6"),
        P("Forest", "#0A140D", "#7BD88F", "#E0C36A"),
        P("Rose", "#1A0D12", "#FF8FA3", "#C77DFF")
    )

    fun theme(p: P) = RuneTheme.from(RuneColor.parse(p.bg, Color.Black), p.text.takeIf { it.isNotBlank() }?.let { RuneColor.parse(it, Color.White) },
        RuneColor.parse(p.accent, Color.White), RuneColor.parse(p.accent2, Color.White))

    /** Swatches offered by every colour picker. */
    val swatches = listOf("#FFFFFF", "#F1F2F6", "#9AA0AE", "#4B5163", "#161821", "#000000", "#FF5A5F", "#FF9A3D", "#FFD166", "#7BD88F",
        "#3DF2B0", "#31D0D0", "#6C8CFF", "#7A6CFF", "#C77DFF", "#FF7AB6")
}
