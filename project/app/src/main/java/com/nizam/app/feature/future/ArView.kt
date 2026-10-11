package com.nizam.app.feature.future

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nizam.app.feature.maps.Geo
import com.nizam.app.feature.maps.MapPrefs
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** Camera view with the world labelled: the Qibla, your saved places and anything you search for, floating where they really are. */
private data class ArTarget(val name: String, val lat: Double, val lon: Double, val colour: Color, val big: Boolean = false)

@SuppressLint("MissingPermission")
@Composable
fun ArViewScreen() {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = it.values.any { v -> v } }
    LaunchedEffect(Unit) { if (!granted) ask.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }
    var azimuth by remember { mutableFloatStateOf(0f) }
    var pitch by remember { mutableFloatStateOf(0f) }
    var here by remember { mutableStateOf<Location?>(null) }
    var extra by remember { mutableStateOf<ArTarget?>(null) }
    var query by remember { mutableStateOf("") }

    DisposableEffect(Unit) {
        val sm = c.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val rot = FloatArray(9); val remapped = FloatArray(9); val o = FloatArray(3)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                // phone held upright, camera looking ahead
                SensorManager.remapCoordinateSystem(rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
                SensorManager.getOrientation(remapped, o)
                val a = ((Math.toDegrees(o[0].toDouble()) + 360) % 360).toFloat()
                var d = a - azimuth; if (d > 180) d -= 360; if (d < -180) d += 360
                azimuth = (azimuth + d * 0.15f + 360) % 360                       // smoothed
                pitch = Math.toDegrees(o[1].toDouble()).toFloat()
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sm.unregisterListener(l) }
    }
    DisposableEffect(granted) {
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val l = LocationListener { here = it }
        if (granted) runCatching {
            (lm.getLastKnownLocation(LocationManager.GPS_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER))?.let { here = it }
            listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { lm.isProviderEnabled(it) }
                .forEach { lm.requestLocationUpdates(it, 2000L, 3f, l) }
        }
        onDispose { runCatching { lm.removeUpdates(l) } }
    }

    val targets = remember(extra) {
        listOf(ArTarget("🕋 Qibla", 21.4225, 39.8262, Color(0xFF3DAA6D), big = true)) +
            MapPrefs.saved(c).map { ArTarget((when (it.kind) { "Home" -> "🏠 "; "Work" -> "💼 "; "Parking" -> "🚗 "; else -> "📍 " }) + it.name,
                it.point.latitude, it.point.longitude, Color(0xFF8AB4F8)) } + listOfNotNull(extra)
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraView(Modifier.fillMaxSize(), back = true)
        Canvas(Modifier.fillMaxSize()) {
            val me = here ?: return@Canvas
            val fov = 60f
            targets.forEach { t ->
                val res = FloatArray(2); Location.distanceBetween(me.latitude, me.longitude, t.lat, t.lon, res)
                val bearing = (res[1] + 360) % 360
                var rel = bearing - azimuth; if (rel > 180) rel -= 360; if (rel < -180) rel += 360
                val dist = res[0].toDouble()
                val label = "${t.name}  ·  ${Geo.distance(dist, MapPrefs.miles(c))}"
                val paint = android.graphics.Paint().apply { color = android.graphics.Color.WHITE; textSize = if (t.big) 52f else 40f; isAntiAlias = true; isFakeBoldText = t.big }
                val bg = android.graphics.Paint().apply { color = android.graphics.Color.argb(170, 0, 0, 0); isAntiAlias = true }
                if (kotlin.math.abs(rel) <= fov / 2) {
                    val x = size.width / 2 + rel / (fov / 2) * size.width / 2
                    // nearer places sit lower, far ones higher — and the horizon follows the phone's tilt
                    val y = size.height * 0.45f + pitch * 12f - (kotlin.math.ln(1 + dist / 100).toFloat() * 30f)
                    drawCircle(t.colour, if (t.big) 26f else 16f, Offset(x, y))
                    drawIntoCanvas { cv ->
                        val w = paint.measureText(label)
                        cv.nativeCanvas.drawRoundRect(x - w / 2 - 20, y - 110, x + w / 2 + 20, y - 40, 30f, 30f, bg)
                        cv.nativeCanvas.drawText(label, x - w / 2, y - 60, paint)
                    }
                } else if (t.big || t == extra) {
                    // off screen: an arrow at the edge showing which way to turn
                    val left = rel < 0
                    val x = if (left) 60f else size.width - 60f; val y = size.height * 0.5f
                    val p = Path().apply { if (left) { moveTo(x - 30, y); lineTo(x + 20, y - 40); lineTo(x + 20, y + 40) } else { moveTo(x + 30, y); lineTo(x - 20, y - 40); lineTo(x - 20, y + 40) }; close() }
                    drawPath(p, t.colour)
                    drawIntoCanvas { cv -> cv.nativeCanvas.drawText("${t.name} ${kotlin.math.abs(rel).roundToInt()}°", if (left) 20f else size.width - 20f - paint.measureText("${t.name} ${kotlin.math.abs(rel).roundToInt()}°"), y + 90, paint) }
                }
            }
        }
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(12.dp).fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text("${azimuth.roundToInt()}° ${listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((azimuth + 22.5f) / 45).toInt() % 8]}  ·  " +
                    (here?.let { "GPS ±${it.accuracy.roundToInt()} m" } ?: "finding you…"), color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.5f)),
                    placeholder = { Text("Find a place to point at…", color = Color.White.copy(alpha = 0.7f)) })
                Text("Go", color = Color.White, modifier = Modifier.clickable(enabled = query.isNotBlank()) {
                    scope.launch {
                        val near = here?.let { org.osmdroid.util.GeoPoint(it.latitude, it.longitude) }
                        Geo.suggest(query.trim(), near).firstOrNull()?.let { p -> extra = ArTarget("⭐ ${p.name}", p.point.latitude, p.point.longitude, Color(0xFFFFB74D)) }
                            ?: com.nizam.app.ui.components.Feedback.show("Couldn't find that")
                    }
                }.padding(12.dp))
            }
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hold the phone upright and turn slowly. Save places in Maps to see them here.", color = Color.White,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.55f)).padding(10.dp))
        }
    }
}
