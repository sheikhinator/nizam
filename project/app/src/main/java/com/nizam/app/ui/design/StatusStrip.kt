package com.nizam.app.ui.design

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.BatteryFull
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.SignalCellularAlt
import androidx.compose.material.icons.rounded.SignalCellularOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Umbrella
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Dehaze
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Place
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/** A glanceable strip: time, weather, network and signal, battery, and the next prayer. Refreshes itself. */
@Composable
fun StatusStrip(ambience: Ambience?, nextPrayer: Pair<String, Int>?, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    var now by remember { mutableStateOf(LocalTime.now()) }
    var net by remember { mutableStateOf(network(c)) }
    var battery by remember { mutableStateOf(battery(c)) }
    var place by remember { mutableStateOf<Pair<String, android.location.Location>?>(null) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { while (true) { now = LocalTime.now(); net = network(c); battery = battery(c); delay(15_000) } } }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { while (true) { place = locate(c) ?: place; delay(120_000) } } }
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Pill(Icons.Rounded.Schedule, now.format(DateTimeFormatter.ofPattern("h:mm a")))
        ambience?.let { a ->
            val icon = when (a.weather) { "rain" -> Icons.Rounded.Umbrella; "snow" -> Icons.Rounded.AcUnit; "storm" -> Icons.Rounded.FlashOn
                "fog" -> Icons.Rounded.Dehaze; "cloudy" -> Icons.Rounded.Cloud; else -> if (a.sky == Sky.NIGHT) Icons.Rounded.NightsStay else Icons.Rounded.WbSunny }
            Pill(icon, (a.tempC?.let { "$it°" } ?: "") + " " + a.weather.replaceFirstChar { it.uppercase() })
        }
        place?.let { (name, loc) ->
            Pill(Icons.Rounded.Place, "$name ±${loc.accuracy.toInt()} m", Modifier.clickable {
                runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:${loc.latitude},${loc.longitude}?q=${loc.latitude},${loc.longitude}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } })
        }
        Pill(net.first, net.second)
        Pill(battery.first, battery.second)
        nextPrayer?.let { (n, m) -> if (m >= 0) Pill(Icons.Rounded.DarkMode, "$n ${if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"}") }
    }
}

@Composable
private fun Pill(icon: ImageVector, text: String, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Icon(icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text.trim(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/** Where you are right now: a fresh fix when the phone gives one (else the last known), named by the phone's geocoder. */
@android.annotation.SuppressLint("MissingPermission")
private suspend fun locate(c: Context): Pair<String, android.location.Location>? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    runCatching {
        if (androidx.core.content.ContextCompat.checkSelfPermission(c, android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED &&
            androidx.core.content.ContextCompat.checkSelfPermission(c, android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return@runCatching null
        val lm = c.getSystemService(android.location.LocationManager::class.java)
        val fresh = if (Build.VERSION.SDK_INT >= 30) kotlinx.coroutines.withTimeoutOrNull(8000) {
            kotlinx.coroutines.suspendCancellableCoroutine<android.location.Location?> { k ->
                val provider = if (lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) android.location.LocationManager.GPS_PROVIDER
                    else android.location.LocationManager.NETWORK_PROVIDER
                lm.getCurrentLocation(provider, null, c.mainExecutor) { k.resumeWith(Result.success(it)) }
            }
        } else null
        val loc = fresh ?: listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER, android.location.LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time } ?: return@runCatching null
        @Suppress("DEPRECATION")
        val a = runCatching { android.location.Geocoder(c).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() }.getOrNull()
        val name = listOfNotNull(a?.subLocality ?: a?.thoroughfare, a?.locality ?: a?.subAdminArea).distinct().joinToString(", ")
            .ifBlank { "%.4f, %.4f".format(loc.latitude, loc.longitude) }
        name to loc
    }.getOrNull()
}

private fun network(c: Context): Pair<ImageVector, String> = runCatching {
    val cm = c.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return Icons.Rounded.SignalCellularOff to "Offline"
    val bars = if (Build.VERSION.SDK_INT >= 29 && caps.signalStrength != Int.MIN_VALUE) {
        val s = caps.signalStrength                       // dBm
        when { s >= -60 -> "▂▄▆█"; s >= -75 -> "▂▄▆"; s >= -90 -> "▂▄"; else -> "▂" }
    } else ""
    when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Icons.Rounded.Wifi to "Wi-Fi $bars"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Icons.Rounded.SignalCellularAlt to "Mobile $bars"
        else -> Icons.Rounded.SignalCellularAlt to "Online"
    }
}.getOrElse { Icons.Rounded.SignalCellularAlt to "—" }

private fun battery(c: Context): Pair<ImageVector, String> = runCatching {
    val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val pct = if (level >= 0) level * 100 / scale.coerceAtLeast(1) else -1
    val charging = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1).let { it == BatteryManager.BATTERY_STATUS_CHARGING || it == BatteryManager.BATTERY_STATUS_FULL }
    val icon = when { charging -> Icons.Rounded.BatteryChargingFull; pct >= 80 -> Icons.Rounded.BatteryFull; pct >= 30 -> Icons.Rounded.BatteryStd; else -> Icons.Rounded.BatteryAlert }
    icon to (if (pct >= 0) "$pct%" else "—") + if (charging) " ⚡" else ""
}.getOrElse { Icons.Rounded.BatteryFull to "—" }
