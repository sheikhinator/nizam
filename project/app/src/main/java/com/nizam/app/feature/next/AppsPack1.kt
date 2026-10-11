package com.nizam.app.feature.next

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Mosque
import androidx.compose.material.icons.outlined.WaterDrop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.nizam.app.AppContainer
import com.nizam.app.core.LineChart
import com.nizam.app.core.Point
import com.nizam.app.net.Http
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.Radius
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Segmented
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.StatTile
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoField
import java.time.temporal.ChronoUnit

// ── Islamic calendar ─────────────────────────────────────────────────────────────────────────────

/** Hijri dates, the year's Islamic events with a countdown, and the sunnah fasting days coming up. */
@Composable
fun IslamicCalendarScreen() {
    val today = LocalDate.now()
    val h = runCatching { HijrahDate.from(today) }.getOrNull()
    val hy = h?.get(ChronoField.YEAR) ?: 1448
    fun g(y: Int, m: Int, d: Int): LocalDate? = runCatching { LocalDate.from(HijrahDate.of(y, m, d)) }.getOrNull()
    val events = listOf(
        Triple("Islamic New Year", 1, 1), Triple("Ashura", 1, 10), Triple("Mawlid an-Nabi", 3, 12), Triple("Isra and Mi'raj", 7, 27),
        Triple("Shab-e-Barat", 8, 15), Triple("Ramadan begins", 9, 1), Triple("Laylat al-Qadr (27th)", 9, 27),
        Triple("Eid al-Fitr", 10, 1), Triple("Day of Arafah", 12, 9), Triple("Eid al-Adha", 12, 10)
    ).mapNotNull { (n, m, d) ->
        val date = g(hy, m, d)?.takeIf { !it.isBefore(today) } ?: g(hy + 1, m, d)
        date?.let { n to it }
    }.sortedBy { it.second }
    val fasts = (0L..30L).map { today.plusDays(it) }.filter { d ->
        val hd = runCatching { HijrahDate.from(d).get(ChronoField.DAY_OF_MONTH) }.getOrDefault(0)
        d.dayOfWeek == java.time.DayOfWeek.MONDAY || d.dayOfWeek == java.time.DayOfWeek.THURSDAY || hd in 13..15
    }.take(6)
    AtlasScreen(title = h?.format(DateTimeFormatter.ofPattern("d MMMM y")) ?: "Islamic calendar", hub = Hub.Deen,
        subtitle = today.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy")) + " · dates can differ by a day with local moon sighting") {
        item("eh") { SectionHeader("Coming up") }
        events.forEach { (n, d) ->
            item(n) {
                val days = ChronoUnit.DAYS.between(today, d)
                ListRow(n, supporting = d.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy")), icon = Icons.Outlined.CalendarMonth, tint = Hub.Deen.tint(),
                    trailing = { Text(if (days == 0L) "Today" else "$days days", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary) })
            }
        }
        item("fh") { SectionHeader("Sunnah fasts ahead") }
        fasts.forEach { d ->
            item("f$d") {
                val hd = runCatching { HijrahDate.from(d).get(ChronoField.DAY_OF_MONTH) }.getOrDefault(0)
                ListRow(d.format(DateTimeFormatter.ofPattern("EEEE d MMM")),
                    supporting = if (hd in 13..15) "Ayyam al-Bid (${hd}th)" else "Monday/Thursday fast")
            }
        }
    }
}

// ── Masjid finder ────────────────────────────────────────────────────────────────────────────────

@Composable
fun MasjidFinderScreen(container: AppContainer) {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<com.nizam.app.feature.maps.Place>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val lat = container.settingsRepository.latitude.first(); val lng = container.settingsRepository.longitude.first()
        val d = 0.03
        list = runCatching {
            com.nizam.app.feature.maps.Geo.nearby("[amenity=place_of_worship][religion=muslim]", doubleArrayOf(lng - d, lat + d, lng + d, lat - d),
                org.osmdroid.util.GeoPoint(lat, lng))
        }.getOrElse { err = Feedback.friendly(it); emptyList() }
    }
    AtlasScreen(title = "Masjid finder", hub = Hub.Deen, subtitle = err ?: list?.let { "${it.size} nearby, nearest first" } ?: "Searching…", loading = list == null) {
        list.orEmpty().forEach { p ->
            item(p.osm + p.name) {
                ListRow(p.name, supporting = listOfNotNull(p.detail.ifBlank { null }, p.tags["opening_hours"]).joinToString(" · ").ifBlank { "Tap for directions" },
                    icon = Icons.Outlined.Mosque, tint = Hub.Deen.tint()) {
                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.point.latitude},${p.point.longitude}?q=${p.point.latitude},${p.point.longitude}(${Uri.encode(p.name)})"))) }
                }
            }
        }
    }
}

// ── Weather ──────────────────────────────────────────────────────────────────────────────────────

/** Open-Meteo forecast plus air quality (smog), no key needed. The last forecast is kept for offline. */
object Weather {
    private fun p(c: Context) = c.getSharedPreferences("weather_cache", 0)
    suspend fun load(c: Context, lat: Double, lng: Double): Pair<JSONObject?, JSONObject?> {
        val f = runCatching {
            Http.getJson("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lng&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
                "&hourly=temperature_2m,precipitation_probability&daily=temperature_2m_max,temperature_2m_min,precipitation_probability_max,weather_code,sunrise,sunset&timezone=auto&forecast_days=7")
                .also { p(c).edit().putString("f", it).apply() }
        }.getOrNull() ?: p(c).getString("f", null)
        val a = runCatching {
            Http.getJson("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lng&current=us_aqi,pm2_5").also { p(c).edit().putString("a", it).apply() }
        }.getOrNull() ?: p(c).getString("a", null)
        return (f?.let { runCatching { JSONObject(it) }.getOrNull() }) to (a?.let { runCatching { JSONObject(it) }.getOrNull() })
    }
    fun describe(code: Int) = when (code) {
        0 -> "Clear"; 1, 2 -> "Partly cloudy"; 3 -> "Cloudy"; 45, 48 -> "Fog"; in 51..57 -> "Drizzle"; in 61..67 -> "Rain"
        in 71..77 -> "Snow"; in 80..82 -> "Showers"; in 95..99 -> "Thunderstorm"; else -> "—" }
    fun aqiText(v: Int) = when { v <= 50 -> "Good"; v <= 100 -> "Moderate"; v <= 150 -> "Unhealthy for sensitive people"; v <= 200 -> "Unhealthy"
        v <= 300 -> "Very unhealthy: wear a mask"; else -> "Hazardous: stay indoors" }
}

@Composable
fun WeatherScreen(container: AppContainer) = com.nizam.app.feature.nx.WeatherProScreen(container)

// ── QR studio ────────────────────────────────────────────────────────────────────────────────────

object Qr {
    fun bitmap(text: String, size: Int = 720): Bitmap? = runCatching {
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565).apply {
            for (x in 0 until size) for (y in 0 until size) setPixel(x, y, if (m[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }.getOrNull()
}

@Composable
fun QrStudioScreen(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    var kind by remember { mutableStateOf("Text") }
    var a by remember { mutableStateOf("") }
    var b by remember { mutableStateOf("") }
    val payload = when (kind) {
        "Wi-Fi" -> if (a.isBlank()) "" else "WIFI:T:WPA;S:$a;P:$b;;"
        "Contact" -> if (a.isBlank()) "" else "BEGIN:VCARD\nVERSION:3.0\nFN:$a\nTEL:$b\nEND:VCARD"
        "WhatsApp" -> if (a.isBlank()) "" else "https://wa.me/${a.filter(Char::isDigit).let { if (it.startsWith("0")) "92" + it.drop(1) else it }}" + if (b.isNotBlank()) "?text=${Uri.encode(b)}" else ""
        else -> a
    }
    val bmp = remember(payload) { if (payload.isBlank()) null else Qr.bitmap(payload) }
    AtlasScreen(title = "QR studio", subtitle = "Make a code for Wi-Fi, a contact, WhatsApp or anything") {
        item("k") { Column(Modifier.padding(horizontal = Space.page)) { Segmented(listOf("Text", "Wi-Fi", "Contact", "WhatsApp"), kind, { kind = it; a = ""; b = "" }, { it }) } }
        item("f") {
            Column(Modifier.padding(horizontal = Space.page)) {
                OutlinedTextField(a, { a = it }, Modifier.fillMaxWidth(), singleLine = kind != "Text",
                    label = { Text(when (kind) { "Wi-Fi" -> "Network name"; "Contact" -> "Name"; "WhatsApp" -> "Number (03xx…)"; else -> "Text or link" }) })
                if (kind != "Text") OutlinedTextField(b, { b = it }, Modifier.fillMaxWidth().padding(top = Space.xs), singleLine = true,
                    label = { Text(when (kind) { "Wi-Fi" -> "Password"; "Contact" -> "Phone"; else -> "Message (optional)" }) })
            }
        }
        if (bmp != null) item("q") {
            Box(Modifier.fillMaxWidth().padding(Space.xl), contentAlignment = Alignment.Center) {
                Image(bmp.asImageBitmap(), "QR code", Modifier.size(260.dp).clip(Radius.card).background(Color.White).padding(Space.m))
            }
        }
        if (bmp != null) item("s") {
            Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Button(onClick = {
                    runCatching {
                        val f = java.io.File(ctx.cacheDir, "qr.png"); f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share QR"))
                    }.onFailure { Feedback.failed("Sharing", it) }
                }) { Text("Share") }
                com.nizam.app.ui.design.OutlinedButton(onClick = { onOpen("scan") }) { Text("Scan a code") }
            }
        }
    }
}
