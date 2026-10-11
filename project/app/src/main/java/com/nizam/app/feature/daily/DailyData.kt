package com.nizam.app.feature.daily

import android.content.Context
import com.nizam.app.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private fun Context.f(n: String) = File(filesDir, n)
private fun File.arr() = runCatching { JSONArray(readText()) }.getOrElse { JSONArray() }

data class PantryItem(val id: Long, val name: String, val qty: String, val expires: String)
data class Place(val name: String, val lat: Double, val lon: Double, val country: String)
data class Trip(val id: Long, val destination: String, val start: String, val end: String, val purpose: String, val plan: String,
                val lat: Double, val lon: Double)

object Pantry {
    fun all(c: Context) = c.f("pantry.json").arr().let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
        PantryItem(it.optLong("id"), it.optString("name"), it.optString("qty"), it.optString("expires")) } }
    fun save(c: Context, l: List<PantryItem>) = c.f("pantry.json").writeText(JSONArray().apply { l.forEach {
        put(JSONObject().put("id", it.id).put("name", it.name).put("qty", it.qty).put("expires", it.expires)) } }.toString())
}

object Trips {
    fun all(c: Context) = c.f("trips.json").arr().let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
        Trip(it.optLong("id"), it.optString("destination"), it.optString("start"), it.optString("end"), it.optString("purpose"),
            it.optString("plan"), it.optDouble("lat"), it.optDouble("lon")) } }
    fun save(c: Context, l: List<Trip>) = c.f("trips.json").writeText(JSONArray().apply { l.forEach {
        put(JSONObject().put("id", it.id).put("destination", it.destination).put("start", it.start).put("end", it.end)
            .put("purpose", it.purpose).put("plan", it.plan).put("lat", it.lat).put("lon", it.lon)) } }.toString())
}

/** Free, keyless public services: Open-Meteo for places, weather and air; AlAdhan for prayer times anywhere. */
object Open {
    suspend fun geocode(name: String): Place? {
        val o = JSONObject(Http.getJson("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + URLEncoder.encode(name, "UTF-8")))
        val r = o.optJSONArray("results")?.optJSONObject(0) ?: return null
        return Place(r.optString("name"), r.optDouble("latitude"), r.optDouble("longitude"), r.optString("country"))
    }

    data class Air(val aqi: Int, val pm25: Double, val tempC: Double, val feels: Double, val at: Long)

    suspend fun air(lat: Double, lon: Double): Air {
        val a = JSONObject(Http.getJson("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&current=us_aqi,pm2_5"))
            .optJSONObject("current")
        val w = JSONObject(Http.getJson("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,apparent_temperature"))
            .optJSONObject("current")
        return Air(a?.optInt("us_aqi") ?: -1, a?.optDouble("pm2_5") ?: 0.0, w?.optDouble("temperature_2m") ?: 0.0,
            w?.optDouble("apparent_temperature") ?: 0.0, System.currentTimeMillis())
    }

    suspend fun forecast(lat: Double, lon: Double, start: String, end: String): String = runCatching {
        val o = JSONObject(Http.getJson("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max&start_date=$start&end_date=$end&timezone=auto"))
            .optJSONObject("daily") ?: return ""
        val d = o.optJSONArray("time") ?: return ""
        (0 until d.length()).joinToString("; ") { i ->
            "${d.optString(i)}: ${o.optJSONArray("temperature_2m_min")?.optDouble(i)?.toInt()}–${o.optJSONArray("temperature_2m_max")?.optDouble(i)?.toInt()}°C, " +
                "rain ${o.optJSONArray("precipitation_probability_max")?.optInt(i)}%"
        }
    }.getOrElse { "" }

    /** Prayer times in the destination's own local time — method 1 is Karachi (University of Islamic Sciences). */
    suspend fun prayerTimesAt(lat: Double, lon: Double, date: LocalDate): String = runCatching {
        val d = date.format(DateTimeFormatter.ofPattern("dd-MM-yyyy"))
        val t = JSONObject(Http.getJson("https://api.aladhan.com/v1/timings/$d?latitude=$lat&longitude=$lon&method=1&school=1"))
            .optJSONObject("data")?.optJSONObject("timings") ?: return ""
        listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha").joinToString(" · ") { "$it ${t.optString(it)}" }
    }.getOrElse { "" }

    fun aqiLabel(aqi: Int) = when {
        aqi < 0 -> "unknown"; aqi <= 50 -> "Good"; aqi <= 100 -> "Moderate"; aqi <= 150 -> "Unhealthy for sensitive groups"
        aqi <= 200 -> "Unhealthy"; aqi <= 300 -> "Very unhealthy"; else -> "Hazardous"
    }
}

/** Latest air reading, cached so the Curator and Today can use it without a network call each time. */
object AirCache {
    fun get(c: Context): Open.Air? = c.getSharedPreferences("air", 0).let { p ->
        if (!p.contains("aqi")) null else Open.Air(p.getInt("aqi", -1), p.getFloat("pm", 0f).toDouble(),
            p.getFloat("t", 0f).toDouble(), p.getFloat("f", 0f).toDouble(), p.getLong("at", 0))
    }
    fun put(c: Context, a: Open.Air) = c.getSharedPreferences("air", 0).edit().putInt("aqi", a.aqi).putFloat("pm", a.pm25.toFloat())
        .putFloat("t", a.tempC.toFloat()).putFloat("f", a.feels.toFloat()).putLong("at", a.at).apply()
    fun forAi(c: Context) = get(c)?.takeIf { System.currentTimeMillis() - it.at < 3 * 3_600_000L }?.let {
        "Outside right now: ${it.tempC.toInt()}°C (feels ${it.feels.toInt()}°C), air quality AQI ${it.aqi} — ${Open.aqiLabel(it.aqi)}. " +
            "If AQI is over 150, steer them away from outdoor exercise."
    }.orEmpty()
}

object Sos {
    private fun p(c: Context) = c.getSharedPreferences("sos", 0)
    fun contacts(c: Context) = p(c).getString("contacts", "").orEmpty().split("\n").map { it.trim() }.filter { it.isNotBlank() }
    fun setContacts(c: Context, list: List<String>) = p(c).edit().putString("contacts", list.joinToString("\n")).apply()
    fun card(c: Context) = p(c).getString("card", "").orEmpty()
    fun setCard(c: Context, s: String) = p(c).edit().putString("card", s).apply()
    fun instructions(c: Context) = p(c).getString("instructions", "").orEmpty()
    fun setInstructions(c: Context, s: String) = p(c).edit().putString("instructions", s).apply()
}
