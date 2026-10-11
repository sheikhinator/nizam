package com.nizam.app.ui.design

import android.content.Context
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import kotlin.math.PI
import kotlin.math.sin

/**
 * The living sky: Atlas's backdrop follows the real world — dawn, day, golden hour, dusk and night;
 * the weather outside (rain falls, snow drifts, clouds pass, fog, lightning); and the calendar
 * (lanterns and a crescent in Ramadan, confetti on Eid, a soft green glow on Jumu'ah).
 */
enum class Sky { DAWN, DAY, GOLDEN, DUSK, NIGHT }

data class Ambience(val sky: Sky, val weather: String, val tempC: Int?, val event: String?, val season: String? = null) {
    val label: String get() = listOfNotNull(
        when (event) { "ramadan" -> "Ramadan"; "eid" -> "Eid Mubarak"; "jummah" -> "Jumu'ah"; else -> null },
        when (weather) { "rain" -> "rain"; "snow" -> "snow"; "storm" -> "storm"; "fog" -> "fog"; "cloudy" -> "cloudy"; else -> null },
        tempC?.let { "$it°" }
    ).joinToString(" · ")
}

/** The sky the app is showing right now, for screens (and the companion) that want to react to it. */
object CurrentAmbience { var value: Ambience? by mutableStateOf(null) }

object AmbienceEngine {
    fun sky(t: LocalTime = LocalTime.now()): Sky = when (t.hour) {
        in 5..6 -> Sky.DAWN; in 7..15 -> Sky.DAY; 16, 17 -> Sky.GOLDEN; 18, 19 -> Sky.DUSK; else -> Sky.NIGHT
    }

    fun event(d: LocalDate = LocalDate.now()): String? {
        val h = runCatching { HijrahDate.from(d) }.getOrNull()
        val hm = h?.get(ChronoField.MONTH_OF_YEAR); val hd = h?.get(ChronoField.DAY_OF_MONTH)
        return when {
            hm == 10 && hd != null && hd <= 3 -> "eid"
            hm == 12 && hd != null && hd in 10..13 -> "eid"
            hm == 9 -> "ramadan"
            d.dayOfWeek == java.time.DayOfWeek.FRIDAY -> "jummah"
            else -> null
        }
    }

    /** Pakistan's seasons (monsoon included); null when seasons are switched off. */
    fun season(c: Context?, d: LocalDate = LocalDate.now()): String? {
        if (c != null && !c.getSharedPreferences("ambience", 0).getBoolean("seasons", true)) return null
        return when (d.monthValue) { 3, 4 -> "spring"; 5, 6 -> "summer"; 7, 8, 9 -> "monsoon"; 10, 11 -> "autumn"; else -> "winter" }
    }
    /** Calm: seasons tint the sky but nothing moves or falls. */
    fun calm(c: Context) = c.getSharedPreferences("ambience", 0).getBoolean("calm", false)
    fun setSeasons(c: Context, seasons: Boolean, calm: Boolean) =
        c.getSharedPreferences("ambience", 0).edit().putBoolean("seasons", seasons).putBoolean("calm", calm).apply()

    fun on(c: Context) = c.getSharedPreferences("ambience", 0).getBoolean("living", true)
    fun setOn(c: Context, v: Boolean) = c.getSharedPreferences("ambience", 0).edit().putBoolean("living", v).apply()

    /** Current weather from Open-Meteo (free, no key), cached for 30 minutes. */
    suspend fun weather(c: Context, lat: Double, lng: Double): Pair<String, Int?> = withContext(Dispatchers.IO) {
        val p = c.getSharedPreferences("ambience", 0)
        if (System.currentTimeMillis() - p.getLong("at", 0) < 30 * 60_000) return@withContext p.getString("w", "clear").orEmpty() to p.getInt("t", 999).takeIf { it != 999 }
        runCatching {
            val o = JSONObject(com.nizam.app.net.Http.getJson(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng&current=temperature_2m,weather_code")).getJSONObject("current")
            val code = o.optInt("weather_code")
            val w = when (code) { 0, 1 -> "clear"; 2, 3 -> "cloudy"; 45, 48 -> "fog"; in 51..67, in 80..82 -> "rain"
                in 71..77, 85, 86 -> "snow"; in 95..99 -> "storm"; else -> "clear" }
            val t = o.optDouble("temperature_2m").takeIf { !it.isNaN() }?.toInt()
            p.edit().putLong("at", System.currentTimeMillis()).putString("w", w).putInt("t", t ?: 999).apply()
            w to t
        }.getOrElse { p.getString("w", "clear").orEmpty() to null }
    }
}

@Composable
fun rememberAmbience(lat: Double, lng: Double): Ambience {
    val c = LocalContext.current
    var a by remember { mutableStateOf(Ambience(AmbienceEngine.sky(), "clear", null, AmbienceEngine.event(), AmbienceEngine.season(c))) }
    LaunchedEffect(lat, lng) {
        while (true) {
            val (w, t) = AmbienceEngine.weather(c, lat, lng)
            a = Ambience(AmbienceEngine.sky(), w, t, AmbienceEngine.event(), AmbienceEngine.season(c))
            delay(10 * 60_000L)
        }
    }
    return a
}

private fun skyColours(s: Sky, dark: Boolean): List<Color> = when (s) {
    Sky.DAWN -> listOf(Color(0xFFFFB38A), Color(0xFFF7D9C4), Color.Transparent)
    Sky.DAY -> if (dark) listOf(Color(0xFF2C5A8A), Color(0xFF1C3350), Color.Transparent) else listOf(Color(0xFF8CC8FF), Color(0xFFD6ECFF), Color.Transparent)
    Sky.GOLDEN -> listOf(Color(0xFFFFB347), Color(0xFFFFE0A8), Color.Transparent)
    Sky.DUSK -> listOf(Color(0xFF6A4C93), Color(0xFFE08E9E), Color.Transparent)
    Sky.NIGHT -> listOf(Color(0xFF0B1030), Color(0xFF1A2150), Color.Transparent)
}

/** Draws the sky. [strength] 0–1: how present it is (a whisper behind every screen, stronger on Today). */
@Composable
fun AmbientSky(a: Ambience, modifier: Modifier, strength: Float = 1f, dark: Boolean = true, animate: Boolean = true) {
    val calm = AmbienceEngine.calm(LocalContext.current)
    // Moving only where it's the centrepiece (Today); a still frame elsewhere, so it costs no battery
    var clock = 0.3f; var fast = 0.5f; var sway = 0f
    if (animate) {
        val t = rememberInfiniteTransition(label = "sky")
        clock = t.animateFloat(0f, 1f, infiniteRepeatable(tween(12_000, easing = LinearEasing)), label = "clock").value
        fast = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1_400, easing = LinearEasing)), label = "fast").value
        sway = t.animateFloat(-1f, 1f, infiniteRepeatable(tween(2_600), RepeatMode.Reverse), label = "sway").value
    }
    val seeds = remember { List(60) { Triple(Math.random().toFloat(), Math.random().toFloat(), Math.random().toFloat()) } }
    Canvas(modifier) {
        val k = strength.coerceIn(0f, 1f)
        drawRect(Brush.verticalGradient(skyColours(a.sky, dark).map { it.copy(alpha = it.alpha * 0.55f * k) }))
        // sun or moon
        // kept to the right edge so it never sits behind a title like a stray dot
        val arc = when (a.sky) { Sky.DAWN -> 0.84f; Sky.DAY -> 0.86f; Sky.GOLDEN -> 0.9f; Sky.DUSK -> 0.93f; Sky.NIGHT -> 0.88f }
        val body = Offset(size.width * arc, size.height * 0.12f)
        if (a.weather !in listOf("rain", "storm", "fog")) {
            if (a.sky == Sky.NIGHT) {
                drawCircle(Color(0xFFF4F1DE).copy(alpha = 0.18f * k), 46f, body)
                crescent(body, 22f, Color(0xFFF4F1DE).copy(alpha = 0.9f * k))
                seeds.take(40).forEach { (x, y, p) ->                 // twinkling stars
                    val tw = (sin((clock * 2 * PI + p * 10).toFloat()) + 1f) / 2f
                    drawCircle(Color.White.copy(alpha = (0.25f + 0.6f * tw) * k), 1.2f + p * 1.6f, Offset(x * size.width, y * size.height * 0.8f))
                }
            } else {
                val glow = if (a.sky == Sky.DAY) Color(0xFFFFF3B0) else Color(0xFFFFC870)
                drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.32f * k), Color.Transparent), body, 160f), 160f, body)
                drawCircle(glow.copy(alpha = 0.35f * k), 16f, body)
            }
        }
        // clouds drift across
        if (a.weather in listOf("cloudy", "rain", "storm", "snow") || a.sky == Sky.DAWN) {
            val n = if (a.weather == "cloudy" || a.weather == "rain" || a.weather == "storm") 5 else 2
            repeat(n) { i ->
                val x = ((clock + i / n.toFloat()) % 1f) * (size.width + 260f) - 130f
                cloud(Offset(x, size.height * (0.15f + 0.12f * (i % 3))), 34f + (i % 2) * 14f,
                    (if (a.sky == Sky.NIGHT) Color(0xFF8A93B8) else Color.White).copy(alpha = (if (a.weather == "storm") 0.5f else 0.35f) * k))
            }
        }
        when (a.weather) {
            "rain", "storm" -> seeds.forEach { (x, y, p) ->
                val yy = ((y + fast * (0.9f + p)) % 1f) * size.height
                drawLine(Color(0xFFA8C8FF).copy(alpha = 0.45f * k), Offset(x * size.width, yy), Offset(x * size.width - 4f, yy + 14f), 1.6f)
            }
            "snow" -> seeds.forEach { (x, y, p) ->
                val yy = ((y + clock * (1.2f + p)) % 1f) * size.height
                drawCircle(Color.White.copy(alpha = 0.7f * k), 1.8f + p * 2f, Offset(x * size.width + sway * 10f * p, yy))
            }
            "fog" -> repeat(3) { i -> drawRect(Color.White.copy(alpha = 0.10f * k), Offset(0f, size.height * (0.25f + i * 0.18f) + sway * 6f), Size(size.width, 26f)) }
        }
        if (a.weather == "storm" && fast > 0.96f && seeds[0].first > 0.3f) drawRect(Color.White.copy(alpha = 0.18f * k))
        // the calendar
        when (a.event) {
            "ramadan" -> repeat(4) { i ->                         // hanging lanterns, gently swinging
                val x = size.width * (0.14f + i * 0.24f); val len = 34f + (i % 2) * 22f
                rotate(sway * 6f * (if (i % 2 == 0) 1 else -1), Offset(x, 0f)) {
                    drawLine(Color(0xFFD4A24C).copy(alpha = 0.7f * k), Offset(x, 0f), Offset(x, len), 1.5f)
                    drawRoundRect(Color(0xFFD4A24C).copy(alpha = 0.85f * k), Offset(x - 8f, len), Size(16f, 24f), androidx.compose.ui.geometry.CornerRadius(6f))
                    drawCircle(Color(0xFFFFE08A).copy(alpha = 0.5f * k), 16f, Offset(x, len + 12f))
                }
            }
            "eid" -> seeds.forEach { (x, y, p) ->                   // confetti
                val yy = ((y + clock * (0.8f + p)) % 1f) * size.height
                val c = listOf(Color(0xFFE0603A), Color(0xFF4FC3A1), Color(0xFFD4A24C), Color(0xFF9B8CD6))[(p * 4).toInt().coerceIn(0, 3)]
                rotate(clock * 360f * (p + 0.2f), Offset(x * size.width, yy)) { drawRect(c.copy(alpha = 0.75f * k), Offset(x * size.width, yy), Size(6f, 10f)) }
            }
            "jummah" -> drawRect(Brush.radialGradient(listOf(Color(0xFF3DAA6D).copy(alpha = 0.16f * k), Color.Transparent),
                Offset(size.width * 0.5f, 0f), size.width * 0.8f))
        }
        // the season: a tint always, and (unless calm) a few things drifting
        val tint = when (a.season) { "spring" -> Color(0xFFF6B6C8); "summer" -> Color(0xFFFFB25A); "monsoon" -> Color(0xFF5FB98A)
            "autumn" -> Color(0xFFD9853B); "winter" -> Color(0xFF9EC9F0); else -> null }
        if (tint != null) drawRect(Brush.verticalGradient(listOf(tint.copy(alpha = 0.10f * k), Color.Transparent)))
        if (!calm && a.event == null) when (a.season) {
            "spring" -> seeds.take(14).forEach { (x, y, p) ->           // blossom petals
                val yy = ((y + clock * (0.5f + p)) % 1f) * size.height
                drawOval(Color(0xFFF6B6C8).copy(alpha = 0.55f * k), Offset(x * size.width + sway * 14f * p, yy), Size(7f, 4.5f))
            }
            "summer" -> repeat(3) { i ->                                   // heat shimmer
                drawRect(Color(0xFFFFD08A).copy(alpha = 0.05f * k), Offset(0f, size.height * (0.55f + i * 0.12f) + sway * 4f), Size(size.width, 10f))
            }
            "monsoon" -> if (a.weather !in listOf("rain", "storm")) seeds.take(18).forEach { (x, y, p) ->   // light drizzle
                val yy = ((y + fast * 0.6f * (0.9f + p)) % 1f) * size.height
                drawLine(Color(0xFFA8D8C0).copy(alpha = 0.28f * k), Offset(x * size.width, yy), Offset(x * size.width - 2f, yy + 8f), 1.2f)
            }
            "autumn" -> seeds.take(10).forEach { (x, y, p) ->           // falling leaves
                val yy = ((y + clock * (0.6f + p)) % 1f) * size.height
                rotate(clock * 360f * (p + 0.3f), Offset(x * size.width, yy)) {
                    drawOval(Color(0xFFD9853B).copy(alpha = 0.6f * k), Offset(x * size.width + sway * 18f * p, yy), Size(9f, 5f)) }
            }
            "winter" -> if (a.weather != "snow") seeds.take(16).forEach { (x, y, p) ->   // frost motes
                val yy = ((y + clock * 0.4f * (0.6f + p)) % 1f) * size.height
                drawCircle(Color.White.copy(alpha = 0.35f * k), 1.2f + p, Offset(x * size.width + sway * 8f * p, yy))
            }
        }
    }
}

private fun DrawScope.crescent(c: Offset, r: Float, color: Color) {
    val p = Path().apply {
        addOval(androidx.compose.ui.geometry.Rect(c, r))
        val cut = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c + Offset(r * 0.45f, -r * 0.2f), r * 0.9f)) }
        op(this, cut, androidx.compose.ui.graphics.PathOperation.Difference)
    }
    drawPath(p, color)
}

private fun DrawScope.cloud(at: Offset, r: Float, color: Color) {
    drawCircle(color, r, at); drawCircle(color, r * 0.8f, at + Offset(r * 0.9f, r * 0.15f))
    drawCircle(color, r * 0.7f, at + Offset(-r * 0.85f, r * 0.2f)); drawCircle(color, r * 0.9f, at + Offset(r * 0.2f, -r * 0.45f))
    drawRect(color, at + Offset(-r * 0.85f, 0f), Size(r * 1.75f, r * 0.9f))
}
