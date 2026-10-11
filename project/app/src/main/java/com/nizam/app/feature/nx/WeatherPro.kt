package com.nizam.app.feature.nx

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.net.Http
import com.nizam.app.ui.design.Art
import com.nizam.app.ui.design.Art3D
import com.nizam.app.ui.design.parallax
import com.nizam.app.ui.design.pressable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate

private val CITIES = listOf("Lahore" to (31.55 to 74.34), "Karachi" to (24.86 to 67.01), "Islamabad" to (33.68 to 73.05), "Makkah" to (21.42 to 39.83), "Madinah" to (24.47 to 39.61), "Dubai" to (25.2 to 55.27))

private fun sky(code: Int, night: Boolean): List<Color> = when {
    night -> listOf(Color(0xFF14123A), Color(0xFF2A1F5C), Color(0xFF4B2F7A))
    code >= 95 -> listOf(Color(0xFF232B45), Color(0xFF3B3F6B), Color(0xFF5B4E82))
    code >= 51 -> listOf(Color(0xFF3A4C7A), Color(0xFF5566A0), Color(0xFF7A74B8))
    code >= 3 -> listOf(Color(0xFF4E6A9C), Color(0xFF7890C4), Color(0xFFA7A3D9))
    else -> listOf(Color(0xFF3F7FE0), Color(0xFF6BA5F2), Color(0xFFB8A7F5))
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(26.dp)).background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.20f), Color.White.copy(alpha = 0.08f))))
        .border(BorderStroke(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), Color.White.copy(alpha = 0.08f)))), RoundedCornerShape(26.dp))) { content() }
}

@Composable
fun WeatherProScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = remember { ctx.getSharedPreferences("weather_pro", 0) }
    var here by remember { mutableStateOf<JSONObject?>(null) }
    var cities by remember { mutableStateOf<List<Pair<String, JSONObject?>>>(emptyList()) }
    var extra by remember { mutableStateOf(p.getString("extra", "").orEmpty()) }
    var add by remember { mutableStateOf("") }
    suspend fun fetch(la: Double, lo: Double): JSONObject? = runCatching {
        JSONObject(Http.getJson("https://api.open-meteo.com/v1/forecast?latitude=$la&longitude=$lo&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m,is_day,uv_index" +
            "&hourly=temperature_2m,weather_code,precipitation_probability,is_day&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max,sunrise,sunset&timezone=auto&forecast_days=7"))
    }.getOrNull()
    suspend fun loadCities() {
        val extras = extra.split(';').mapNotNull { e -> e.split('|').takeIf { it.size == 3 }?.let { it[0] to (it[1].toDouble() to it[2].toDouble()) } }
        cities = coroutineScope { (CITIES + extras).map { (n, ll) -> async { n to fetch(ll.first, ll.second) } }.awaitAll() }
    }
    LaunchedEffect(Unit) {
        here = fetch(container.settingsRepository.latitude.first(), container.settingsRepository.longitude.first())
        if (here != null) p.edit().putString("last", here.toString()).apply() else here = p.getString("last", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        loadCities()
    }
    val cur = here?.optJSONObject("current")
    val code = cur?.optInt("weather_code") ?: 0
    val night = cur?.optInt("is_day", 1) == 0
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(sky(code, night)))) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
            item("hero") {
                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Your location", color = Color.White.copy(alpha = 0.8f), fontSize = 16.sp)
                        Text(cur?.let { "${it.optDouble("temperature_2m").toInt()}°" } ?: "—", color = Color.White, fontSize = 96.sp, fontWeight = FontWeight.Thin)
                        Text(com.nizam.app.feature.next.Weather.describe(code), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Medium)
                        here?.optJSONObject("daily")?.let { d -> Text("H:${d.optJSONArray("temperature_2m_max")?.optDouble(0)?.toInt()}°  L:${d.optJSONArray("temperature_2m_min")?.optDouble(0)?.toInt()}°",
                            color = Color.White.copy(alpha = 0.85f), fontSize = 16.sp) }
                    }
                    Art3D(Art.forWeather(code, night), 170.dp)
                }
            }
            here?.optJSONObject("hourly")?.let { h ->
                item("hourly") {
                    val times = h.optJSONArray("time"); val temps = h.optJSONArray("temperature_2m"); val codes = h.optJSONArray("weather_code"); val rain = h.optJSONArray("precipitation_probability"); val day = h.optJSONArray("is_day")
                    val nowKey = java.time.LocalDateTime.now().withMinute(0).toString().take(13)
                    val start = (0 until (times?.length() ?: 0)).firstOrNull { times!!.getString(it).startsWith(nowKey) } ?: 0
                    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Column(Modifier.padding(vertical = 14.dp)) {
                            Text("  Hourly forecast", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
                            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(24) { k -> val i = start + k
                                    val sel = k == 0
                                    Column(Modifier.clip(RoundedCornerShape(30.dp)).background(if (sel) Color.White.copy(alpha = 0.28f) else Color.White.copy(alpha = 0.08f))
                                        .border(BorderStroke(1.dp, Color.White.copy(alpha = if (sel) 0.6f else 0.15f)), RoundedCornerShape(30.dp)).padding(horizontal = 10.dp, vertical = 12.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(if (k == 0) "Now" else times?.optString(i).orEmpty().takeLast(5), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                        Art3D(Art.forWeather(codes?.optInt(i) ?: 0, day?.optInt(i, 1) == 0), 40.dp, float = false, shadow = false)
                                        Text("${rain?.optInt(i) ?: 0}%", color = Color(0xFF9FD8FF), fontSize = 11.sp)
                                        Text("${temps?.optDouble(i)?.toInt() ?: 0}°", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            cur?.let { c ->
                item("details") {
                    val daily = here?.optJSONObject("daily")
                    val tiles = listOf("Feels like" to "${c.optDouble("apparent_temperature").toInt()}°", "Humidity" to "${c.optInt("relative_humidity_2m")}%",
                        "Wind" to "${c.optDouble("wind_speed_10m").toInt()} km/h", "UV index" to c.optDouble("uv_index").let { u -> "${u.toInt()} · ${when { u < 3 -> "Low"; u < 6 -> "Moderate"; u < 8 -> "High"; else -> "Very high" }}" },
                        "Sunrise" to daily?.optJSONArray("sunrise")?.optString(0).orEmpty().takeLast(5), "Sunset" to daily?.optJSONArray("sunset")?.optString(0).orEmpty().takeLast(5))
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        tiles.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(vertical = 5.dp)) {
                            row.forEach { (k, v) -> GlassCard(Modifier.weight(1f)) { Column(Modifier.padding(16.dp)) {
                                Text(k.uppercase(), color = Color.White.copy(alpha = 0.7f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                                Text(v, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp))
                            } } }
                        } }
                    }
                }
            }
            here?.optJSONObject("daily")?.let { d ->
                item("week") {
                    val days = d.optJSONArray("time"); val mx = d.optJSONArray("temperature_2m_max"); val mn = d.optJSONArray("temperature_2m_min"); val codes = d.optJSONArray("weather_code"); val rain = d.optJSONArray("precipitation_probability_max")
                    val lo = (0 until (days?.length() ?: 0)).minOfOrNull { mn!!.optDouble(it) } ?: 0.0; val hi = (0 until (days?.length() ?: 0)).maxOfOrNull { mx!!.optDouble(it) } ?: 1.0
                    GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Column(Modifier.padding(16.dp)) {
                            Text("7-day forecast", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                            (0 until (days?.length() ?: 0)).forEach { i ->
                                val date = LocalDate.parse(days!!.getString(i))
                                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (i == 0) "Today" else date.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }, color = Color.White, fontSize = 16.sp, modifier = Modifier.width(56.dp))
                                    Art3D(Art.forWeather(codes!!.optInt(i), false), 34.dp, float = false, shadow = false)
                                    Text("${rain!!.optInt(i)}%", color = Color(0xFF9FD8FF), fontSize = 12.sp, modifier = Modifier.width(40.dp).padding(start = 4.dp))
                                    Text("${mn!!.optDouble(i).toInt()}°", color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, modifier = Modifier.width(34.dp))
                                    Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color.White.copy(alpha = 0.15f))) {
                                        val a = ((mn.optDouble(i) - lo) / (hi - lo).coerceAtLeast(1.0)).toFloat(); val b = ((mx!!.optDouble(i) - lo) / (hi - lo).coerceAtLeast(1.0)).toFloat()
                                        Row { Spacer(Modifier.weight(a.coerceIn(0.001f, 1f))); Box(Modifier.weight((b - a).coerceIn(0.05f, 1f)).height(6.dp).clip(RoundedCornerShape(3.dp))
                                            .background(Brush.horizontalGradient(listOf(Color(0xFF7FD4FF), Color(0xFFFFC857))))); Spacer(Modifier.weight((1f - b).coerceIn(0.001f, 1f))) }
                                    }
                                    Text("${mx!!.optDouble(i).toInt()}°", color = Color.White, fontSize = 15.sp, modifier = Modifier.padding(start = 8.dp))
                                }
                            }
                        }
                    }
                }
            }
            item("cityh") { Text("Other places", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 14.dp, bottom = 6.dp)) }
            cities.forEach { (name, j) -> item("c$name") {
                val cc = j?.optJSONObject("current"); val cCode = cc?.optInt("weather_code") ?: 0; val cNight = cc?.optInt("is_day", 1) == 0
                GlassCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).parallax(0.4f)) {
                    Box(Modifier.fillMaxWidth().background(Brush.horizontalGradient(sky(cCode, cNight).map { it.copy(alpha = 0.55f) }))) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(cc?.let { "${it.optDouble("temperature_2m").toInt()}°" } ?: "—", color = Color.White, fontSize = 44.sp, fontWeight = FontWeight.Light)
                                Text(name, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                                Text(com.nizam.app.feature.next.Weather.describe(cCode), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                            }
                            Art3D(Art.forWeather(cCode, cNight), 84.dp, float = false)
                        }
                    }
                }
            } }
            item("add") { Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(add, { add = it }, Modifier.weight(1f), placeholder = { Text("Add a city", color = Color.White.copy(alpha = 0.6f)) }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(focusedTextColor = Color.White, unfocusedTextColor = Color.White, focusedBorderColor = Color.White, unfocusedBorderColor = Color.White.copy(alpha = 0.4f)))
                Text("  Add", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.pressable {
                    val q = add.trim(); if (q.isBlank()) return@pressable
                    scope.launch {
                        runCatching {
                            val r = JSONObject(Http.getJson("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + java.net.URLEncoder.encode(q, "UTF-8"))).getJSONArray("results").getJSONObject(0)
                            extra = (extra.split(';').filter { it.isNotBlank() } + "${r.optString("name")}|${r.optDouble("latitude")}|${r.optDouble("longitude")}").joinToString(";")
                            p.edit().putString("extra", extra).apply(); add = ""; loadCities()
                        }.onFailure { com.nizam.app.ui.components.Feedback.show("Couldn't find that city") }
                    }
                }.padding(8.dp))
            } }
            item("credit") { Text("Forecast: Open-Meteo", color = Color.White.copy(alpha = 0.5f), fontSize = 11.sp, modifier = Modifier.padding(20.dp)) }
        }
    }
}
