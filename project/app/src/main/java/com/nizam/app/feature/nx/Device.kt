package com.nizam.app.feature.nx

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nizam.app.AppContainer
import com.nizam.app.net.Http
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.EmptySpec
import com.nizam.app.ui.design.EmptyState
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

object Device {
    val all: List<Spec> = listOf(
        ScreenSpec("sensors", "Sensor tools", "Compass & qibla, level, light, metal detector, sound meter, altitude", Icons.Outlined.Sensors, Hub.Tools, "compass level lux metal detector decibel noise altitude qibla") { c, _ -> SensorScreen(c) },
        ScreenSpec("battery", "Battery doctor", "Health, temperature, real capacity, charging speed", Icons.Outlined.BatteryChargingFull, Hub.Tools, "battery health drain charging mah") { _, _ -> BatteryScreen() },
        ScreenSpec("storage", "Storage cleaner", "What's filling your phone; biggest files first", Icons.Outlined.Storage, Hub.Tools, "storage space clean cache big files") { _, _ -> StorageScreen() },
        ScreenSpec("netdoctor", "Wi-Fi & network doctor", "Speed test, ping, DNS, signal and public IP", Icons.Outlined.Wifi, Hub.Tools, "wifi internet speed test ping slow network ptcl") { _, _ -> NetScreen() },
        ScreenSpec("irremote", "TV remote", "Control Samsung, LG and Sony TVs with the IR blaster", Icons.Outlined.SettingsRemote, Hub.Home, "ir remote tv control infrared") { _, _ -> IrScreen() },
        ScreenSpec("powercut", "Power-cut mode", "Torch, SOS, screen light and battery stretch", Icons.Outlined.FlashlightOn, Hub.Home, "light torch load shedding bijli gayi sos") { _, open -> PowerCutScreen(open) },
        ScreenSpec("apps", "App manager", "Trackers, risky permissions, debloat, uninstall", Icons.Outlined.Apps, Hub.Tools, "apps trackers permissions debloat uninstall privacy dns") { _, _ -> AppManagerScreen() },
        ScreenSpec("dataguard", "Data guard", "Which apps eat your mobile data", Icons.Outlined.DataUsage, Hub.Tools, "mobile data usage mb gb package") { _, _ -> DataGuardScreen() },
    )
}

@Composable
private fun Big(value: String, label: String, color: Color = MaterialTheme.colorScheme.onSurface) {
    Column(Modifier.fillMaxWidth().padding(vertical = Space.l), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold, color = color, fontFamily = FontFamily.Monospace)
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun rememberSensor(type: Int, active: Boolean): FloatArray? {
    val ctx = LocalContext.current
    var v by remember(type) { mutableStateOf<FloatArray?>(null) }
    DisposableEffect(type, active) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val s = sm?.getDefaultSensor(type)
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) { v = e.values.clone() }
            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        if (active && s != null) sm?.registerListener(l, s, SensorManager.SENSOR_DELAY_UI)
        onDispose { sm?.unregisterListener(l) }
    }
    return v
}

// ─────────────────────────────── Sensors ───────────────────────────────
@Composable
fun SensorScreen(container: AppContainer) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf("Compass") }
    val sm = remember { ctx.getSystemService(SensorManager::class.java) }
    val tabs = listOf("Compass", "Level", "Light", "Metal", "Sound", "Altitude")
    var qibla by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        val la = runCatching { container.settingsRepository.latitude.first() }.getOrDefault(31.55)
        val lo = runCatching { container.settingsRepository.longitude.first() }.getOrDefault(74.34)
        val k1 = Math.toRadians(21.4225); val k2 = Math.toRadians(39.8262); val p1 = Math.toRadians(la); val l1 = Math.toRadians(lo)
        qibla = (Math.toDegrees(atan2(sin(k2 - l1), cos(p1) * kotlin.math.tan(k1) - sin(p1) * cos(k2 - l1))) + 360) % 360
    }
    val rot = rememberSensor(Sensor.TYPE_ROTATION_VECTOR, tab == "Compass")
    val acc = rememberSensor(Sensor.TYPE_ACCELEROMETER, tab == "Level")
    val light = rememberSensor(Sensor.TYPE_LIGHT, tab == "Light")
    val mag = rememberSensor(Sensor.TYPE_MAGNETIC_FIELD, tab == "Metal")
    val pres = rememberSensor(Sensor.TYPE_PRESSURE, tab == "Altitude")
    AtlasScreen(title = "Sensor tools", subtitle = "Your phone's own sensors, no internet", hub = Hub.Tools) {
        item("tabs") { androidx.compose.foundation.lazy.LazyRow(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            items(tabs.size) { i -> Capsule(tabs[i], tab == tabs[i]) { tab = tabs[i] } } } }
        item("body") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                when (tab) {
                    "Compass" -> {
                        val az = rot?.let { r -> val m = FloatArray(9); SensorManager.getRotationMatrixFromVector(m, r); val o = FloatArray(3); SensorManager.getOrientation(m, o)
                            ((Math.toDegrees(o[0].toDouble()) + 360) % 360) }
                        if (az == null) Text("No compass sensor found.") else {
                            val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
                            Big("${az.toInt()}° ${dirs[((az + 22.5) / 45).toInt() % 8]}", "Keep the phone flat, away from metal")
                            Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
                                Canvas(Modifier.size(200.dp).rotate(-az.toFloat())) {
                                    drawCircle(Color.Gray, style = Stroke(3f))
                                    drawLine(Color(0xFFD9342B), center, Offset(center.x, 12f), strokeWidth = 8f)
                                    qibla?.let { q -> val a = Math.toRadians(q - 90); drawLine(Color(0xFF3FA37C), center,
                                        Offset(center.x + (size.width / 2 - 14) * cos(a).toFloat(), center.y + (size.width / 2 - 14) * sin(a).toFloat()), strokeWidth = 10f) }
                                }
                            }
                            qibla?.let { q -> val off = ((q - az + 540) % 360) - 180
                                Text("Qibla ${q.toInt()}° (green) — " + if (kotlin.math.abs(off) < 4) "you're facing it ✓" else "turn ${if (off > 0) "right" else "left"} ${kotlin.math.abs(off).toInt()}°",
                                    style = MaterialTheme.typography.titleMedium, color = Hub.Deen.tint()) }
                        }
                    }
                    "Level" -> {
                        val a = acc
                        if (a == null) Text("No accelerometer found.") else {
                            val pitch = Math.toDegrees(atan2(a[1].toDouble(), a[2].toDouble())); val roll = Math.toDegrees(atan2(a[0].toDouble(), a[2].toDouble()))
                            val flat = kotlin.math.abs(pitch) < 1 && kotlin.math.abs(roll) < 1
                            Big("%.1f° / %.1f°".format(roll, pitch), "Side-to-side / front-to-back", if (flat) Hub.Tools.tint() else MaterialTheme.colorScheme.onSurface)
                            Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                                Canvas(Modifier.size(150.dp)) {
                                    drawCircle(Color.Gray, style = Stroke(3f)); drawCircle(Color.Gray, radius = 18f, style = Stroke(2f))
                                    drawCircle(if (flat) Color(0xFF3FA37C) else Color(0xFFE0A030), radius = 16f,
                                        center = Offset(center.x - (roll.toFloat() * 3).coerceIn(-70f, 70f), center.y + (pitch.toFloat() * 3).coerceIn(-70f, 70f)))
                                }
                            }
                        }
                    }
                    "Light" -> light?.let { l -> val lux = l[0]
                        Big("${lux.toInt()} lux", when { lux < 50 -> "Too dim to read — strains eyes"; lux < 300 -> "Fine for moving around, dim for study"; lux < 750 -> "Good for reading and study"; lux < 5000 -> "Bright indoors"; else -> "Daylight" })
                    } ?: Text("Point the top of the phone at the light.")
                    "Metal" -> mag?.let { m -> val t = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
                        Big("${t.toInt()} µT", if (t > 90) "Metal nearby! (wiring, nails, pipes)" else "Normal Earth field is 25–65 µT. Sweep slowly along the wall.",
                            if (t > 90) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    } ?: Text("No magnetometer found.")
                    "Sound" -> SoundMeter()
                    "Altitude" -> pres?.let { p -> Big("${SensorManager.getAltitude(SensorManager.PRESSURE_STANDARD_ATMOSPHERE, p[0]).toInt()} m", "Air pressure ${"%.1f".format(p[0])} hPa (altitude is approximate)") }
                        ?: Text("This phone has no barometer.")
                }
            }
        }
        if (sm == null) item("x") { Text("Sensors unavailable.") }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun SoundMeter() {
    val ctx = LocalContext.current
    var db by remember { mutableFloatStateOf(0f) }
    var peak by remember { mutableFloatStateOf(0f) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (!granted) { Button(onClick = { perm.launch(android.Manifest.permission.RECORD_AUDIO) }) { Text("Allow microphone") }; return }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val rate = 44100
            val size = android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            val rec = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, size)
            val buf = ShortArray(size)
            try {
                rec.startRecording()
                while (isActive) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        var sum = 0.0; for (i in 0 until n) sum += buf[i] * buf[i].toDouble()
                        val rms = sqrt(sum / n).coerceAtLeast(1.0)
                        val v = (20 * log10(rms / 32767.0) + 94).toFloat().coerceIn(20f, 120f)
                        db = db * 0.7f + v * 0.3f; if (db > peak) peak = db
                    }
                }
            } finally { runCatching { rec.stop() }; rec.release() }
        }
    }
    Big("${db.toInt()} dB", when { db < 40 -> "Quiet room"; db < 60 -> "Normal talk"; db < 75 -> "Busy street"; db < 85 -> "Loud — limit long exposure"; else -> "Harmful over time — protect your ears" } + " · peak ${peak.toInt()}",
        if (db >= 85) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    Text("Approximate; phone mics aren't calibrated.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ─────────────────────────────── Battery ───────────────────────────────
@Composable
fun BatteryScreen() {
    val ctx = LocalContext.current
    var i by remember { mutableStateOf<Intent?>(null) }
    var now by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)); now++; delay(2000) } }
    val bm = remember { ctx.getSystemService(BatteryManager::class.java) }
    val b = i
    AtlasScreen(title = "Battery doctor", subtitle = "Live, refreshed every 2 seconds", hub = Hub.Tools, loading = b == null) {
        if (b == null) return@AtlasScreen
        val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / b.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val temp = b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0
        val volt = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000.0
        val health = when (b.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) { BatteryManager.BATTERY_HEALTH_GOOD -> "Good"; BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"; BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over-voltage"; BatteryManager.BATTERY_HEALTH_COLD -> "Cold"; else -> "Unknown" }
        val plugged = when (b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) { BatteryManager.BATTERY_PLUGGED_AC -> "Charger"; BatteryManager.BATTERY_PLUGGED_USB -> "USB"; BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"; else -> "On battery" }
        val cur = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) ?: 0
        val ma = if (kotlin.math.abs(cur) > 20000) cur / 1000 else cur
        val counter = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: 0
        val capacity = if (counter > 0 && level > 0) counter / 1000 * 100 / level else 0
        item("big") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            Big("$level%", "$plugged · ${if (ma != 0) "${kotlin.math.abs(ma)} mA ${if (ma > 0) "in" else "out"}" else "current unavailable"}", if (level < 20) MaterialTheme.colorScheme.error else Hub.Tools.tint())
        } }
        item("rows") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            listOf("Health" to health, "Temperature" to "$temp °C", "Voltage" to "$volt V", "Estimated full capacity" to if (capacity > 0) "$capacity mAh" else "—",
                "Technology" to b.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY).orEmpty()).forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(k, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v, fontWeight = FontWeight.SemiBold) }
            }
        } }
        item("tips") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            Text("Advice", style = MaterialTheme.typography.titleSmall)
            val tips = buildList {
                if (temp > 40) add("It's hot (${temp}°C). Stop gaming or fast-charging, take the cover off, keep it out of the sun.")
                if (level > 85 && plugged != "On battery") add("Unplug around 80–85% to slow battery wear.")
                if (level < 20) add("Turn on battery saver and lower screen brightness.")
                add("Keep it between 20% and 80% most days; overnight charging at 100% ages the battery fastest.")
                add("Game booster and Focus shield also cut background drain.")
            }
            tips.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
        } }
    }
}

// ─────────────────────────────── Storage ───────────────────────────────
@Composable
fun StorageScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var big by remember { mutableStateOf<List<Triple<String, Long, android.net.Uri>>?>(null) }
    var cache by remember { mutableStateOf(0L) }
    val st = remember { StatFs(Environment.getDataDirectory().path) }
    val total = st.totalBytes; val free = st.availableBytes
    fun gb(b: Long) = "%.1f GB".format(b / 1e9)
    fun mb(b: Long) = if (b > 1e9) gb(b) else "%.0f MB".format(b / 1e6)
    fun dirSize(f: java.io.File): Long = if (f.isFile) f.length() else f.listFiles().orEmpty().sumOf { dirSize(it) }
    LaunchedEffect(Unit) {
        cache = withContext(Dispatchers.IO) { dirSize(ctx.cacheDir) }
        big = withContext(Dispatchers.IO) {
            runCatching {
                val out = mutableListOf<Triple<String, Long, android.net.Uri>>()
                listOf(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI).forEach { base ->
                    ctx.contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE),
                        "${MediaStore.MediaColumns.SIZE} > ?", arrayOf((30L * 1024 * 1024).toString()), "${MediaStore.MediaColumns.SIZE} DESC")?.use { c ->
                        while (c.moveToNext() && out.size < 60) out += Triple(c.getString(1) ?: "file", c.getLong(2), android.content.ContentUris.withAppendedId(base, c.getLong(0)))
                    }
                }
                out.sortedByDescending { it.second }.take(30)
            }.getOrDefault(emptyList())
        }
    }
    AtlasScreen(title = "Storage", subtitle = "${gb(free)} free of ${gb(total)}", hub = Hub.Tools) {
        item("bar") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            val used = (total - free).toFloat() / total
            Box(Modifier.fillMaxWidth().height(14.dp).background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(7.dp))) {
                Box(Modifier.fillMaxWidth(used).height(14.dp).background(if (used > 0.9f) MaterialTheme.colorScheme.error else Hub.Tools.tint(), androidx.compose.foundation.shape.RoundedCornerShape(7.dp)))
            }
            Text("${(used * 100).toInt()}% used" + if (used > 0.9f) " — the phone slows down past 90%" else "", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.s))
        } }
        item("cache") { ListRow("Atlas's own cache", supporting = mb(cache), icon = Icons.Outlined.CleaningServices, trailing = {
            Text("Clear", Modifier.padding(Space.s).pressable { scope.launch { withContext(Dispatchers.IO) { ctx.cacheDir.listFiles().orEmpty().forEach { it.deleteRecursively() } }; cache = 0; Feedback.show("Cleared") } }, color = Hub.Tools.tint())
        }) }
        item("sys") { ListRow("Other apps' caches and junk", supporting = "Open Android's storage settings", icon = Icons.Outlined.Settings) {
            runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } }
        item("bh") { SectionHeader("Biggest files") }
        val list = big
        if (list == null) item("l") { Text("Scanning…", Modifier.padding(Space.page)) }
        else if (list.isEmpty()) item("n") { Text("No files over 30 MB found (or media permission not granted).", Modifier.padding(Space.page)) }
        else list.forEach { (n, s, uri) -> item(uri.toString()) {
            ListRow(n, supporting = mb(s), icon = Icons.Outlined.InsertDriveFile) {
                runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }
        } }
    }
}

// ─────────────────────────────── Network doctor ───────────────────────────────
@Composable
fun NetScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var lines by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var speed by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    suspend fun time(block: suspend () -> Unit): Long { val t = System.nanoTime(); block(); return (System.nanoTime() - t) / 1_000_000 }
    @Suppress("DEPRECATION")
    fun basics(): List<Pair<String, String>> {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        val kind = when { caps == null -> "Offline"; caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"; caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"; else -> "Other" }
        val out = mutableListOf("Connection" to kind)
        if (kind == "Wi-Fi") runCatching {
            val wi = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager).connectionInfo
            val q = android.net.wifi.WifiManager.calculateSignalLevel(wi.rssi, 5)
            out += "Signal" to "${wi.rssi} dBm (${listOf("very weak", "weak", "fair", "good", "excellent")[q.coerceIn(0, 4)]})"
            out += "Link speed" to "${wi.linkSpeed} Mbps"
            out += "Band" to if (wi.frequency > 4900) "5 GHz (faster, shorter range)" else "2.4 GHz (longer range, slower)"
        }
        caps?.let { out += "Estimated bandwidth" to "${it.linkDownstreamBandwidthKbps / 1000} Mbps down" }
        return out
    }
    AtlasScreen(title = "Network doctor", subtitle = "Why is the internet slow?", hub = Hub.Tools) {
        item("go") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Button(onClick = {
                busy = true; lines = basics()
                scope.launch {
                    val extra = mutableListOf<Pair<String, String>>()
                    extra += "DNS lookup" to runCatching { "${time { withContext(Dispatchers.IO) { java.net.InetAddress.getByName("google.com") } }} ms" }.getOrElse { "failed — DNS problem" }
                    extra += "Ping Google" to runCatching { "${time { Http.getText("https://www.google.com/generate_204") }} ms" }.getOrElse { "failed" }
                    extra += "Ping Cloudflare" to runCatching { "${time { Http.getText("https://1.1.1.1/cdn-cgi/trace") }} ms" }.getOrElse { "failed" }
                    extra += "Public IP" to runCatching { Http.getText("https://api.ipify.org").trim() }.getOrElse { "unknown" }
                    lines = lines + extra; busy = false
                }
            }, enabled = !busy) { Text(if (busy) "Checking…" else "Diagnose") }
            OutlinedButton(onClick = {
                speed = "Testing download…"
                scope.launch {
                    speed = runCatching {
                        val bytes = 15_000_000
                        val ms = time { withContext(Dispatchers.IO) {
                            val c = java.net.URL("https://speed.cloudflare.com/__down?bytes=$bytes").openConnection(); c.connectTimeout = 10000; c.readTimeout = 30000
                            c.getInputStream().use { s -> val b = ByteArray(64 * 1024); while (s.read(b) >= 0) { } }
                        } }
                        "%.1f Mbps download".format(bytes * 8 / 1e6 / (ms / 1000.0))
                    }.getOrElse { "Speed test failed: ${it.message?.take(60)}" }
                }
            }) { Text("Speed test") }
        } }
        speed?.let { s -> item("sp") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) { Text(s, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) } } }
        lines.forEach { (k, v) -> item(k) { Row(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 6.dp)) {
            Text(k, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v, fontWeight = FontWeight.SemiBold) } } }
        if (lines.isNotEmpty()) item("adv") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            Text("If it's slow", style = MaterialTheme.typography.titleSmall)
            listOf("Weak signal: move closer to the router or raise it off the floor.", "DNS slow or failing: set Private DNS to dns.google or one.one.one.one.",
                "Pings fine but speed low: restart the router; too many devices; ask your ISP (PTCL, Nayatel, StormFiber) to check the line.",
                "5 GHz is faster in the same room; 2.4 GHz reaches further.").forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
        } }
    }
}

// ─────────────────────────────── IR remote ───────────────────────────────
private object Ir {
    fun nec(code: Long, samsung: Boolean = false): IntArray {
        val p = mutableListOf(if (samsung) 4500 else 9000, 4500)
        for (i in 31 downTo 0) { p += 560; p += if ((code shr i) and 1L == 1L) 1690 else 560 }
        p += 560; p += 40000
        return p.toIntArray()
    }
    fun sony(cmd: Int, dev: Int = 1): IntArray {
        val frame = mutableListOf(2400, 600)
        val bits = (0 until 7).map { (cmd shr it) and 1 } + (0 until 5).map { (dev shr it) and 1 }
        bits.forEach { frame += if (it == 1) 1200 else 600; frame += 600 }
        val used = frame.sum(); frame[frame.lastIndex] = 600 + (45000 - used).coerceAtLeast(0)
        return (frame + frame + frame).toIntArray()
    }
    val brands = linkedMapOf(
        "Samsung" to mapOf("Power" to 0xE0E040BFL, "Vol +" to 0xE0E0E01FL, "Vol −" to 0xE0E0D02FL, "Ch +" to 0xE0E048B7L, "Ch −" to 0xE0E008F7L, "Mute" to 0xE0E0F00FL, "Source" to 0xE0E0807FL,
            "OK" to 0xE0E016E9L, "Up" to 0xE0E006F9L, "Down" to 0xE0E08679L, "Left" to 0xE0E0A659L, "Right" to 0xE0E046B9L, "Back" to 0xE0E01AE5L, "Home" to 0xE0E09E61L),
        "LG" to mapOf("Power" to 0x20DF10EFL, "Vol +" to 0x20DF40BFL, "Vol −" to 0x20DFC03FL, "Ch +" to 0x20DF00FFL, "Ch −" to 0x20DF807FL, "Mute" to 0x20DF906FL, "Source" to 0x20DFD02FL,
            "OK" to 0x20DF22DDL, "Up" to 0x20DF02FDL, "Down" to 0x20DF827DL, "Left" to 0x20DFE01FL, "Right" to 0x20DF609FL, "Back" to 0x20DF14EBL, "Home" to 0x20DF3EC1L),
        "Sony" to mapOf("Power" to 21L, "Vol +" to 18L, "Vol −" to 19L, "Ch +" to 16L, "Ch −" to 17L, "Mute" to 20L, "Source" to 37L, "OK" to 101L, "Up" to 116L, "Down" to 117L, "Left" to 52L, "Right" to 51L, "Back" to 99L, "Home" to 96L),
    )
}

@Composable
fun IrScreen() {
    val ctx = LocalContext.current
    val ir = remember { ctx.getSystemService(Context.CONSUMER_IR_SERVICE) as? android.hardware.ConsumerIrManager }
    val p = remember { ctx.getSharedPreferences("irremote", 0) }
    var brand by remember { mutableStateOf(p.getString("brand", "Samsung")!!) }
    val has = ir?.hasIrEmitter() == true
    fun send(k: String) {
        val code = Ir.brands[brand]?.get(k) ?: return
        runCatching {
            if (brand == "Sony") ir!!.transmit(40000, Ir.sony(code.toInt())) else ir!!.transmit(38000, Ir.nec(code, samsung = brand == "Samsung"))
        }.onFailure { Feedback.show("Couldn't send: ${it.message}") }
    }
    AtlasScreen(title = "TV remote", subtitle = if (has) "Point the top of the phone at the TV" else "This phone has no IR blaster", hub = Hub.Home) {
        item("b") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Ir.brands.keys.forEach { b -> Capsule(b, brand == b) { brand = b; p.edit().putString("brand", b).apply() } } } }
        if (!has) { item("e") { EmptyState(EmptySpec(Icons.Outlined.SettingsRemote, "No IR blaster", "Many Infinix, Xiaomi and Tecno phones have one; this one doesn't report it.")) }; return@AtlasScreen }
        item("pad") { Column(Modifier.padding(Space.page), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            listOf(listOf("Power", "Mute", "Source"), listOf("Home", "Up", "Back"), listOf("Left", "OK", "Right"), listOf("Vol −", "Down", "Vol +"), listOf("Ch −", "", "Ch +")).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    row.forEach { k -> if (k.isBlank()) Box(Modifier.weight(1f)) else
                        Button(onClick = { send(k) }, modifier = Modifier.weight(1f).height(58.dp)) { Text(k, fontSize = 16.sp) } }
                }
            }
        } }
        item("n") { Text("Other brands (TCL, Haier, Orient, EcoStar) use different codes; Samsung and LG codes work on many rebadged TVs — try them.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Space.page)) }
    }
}

// ─────────────────────────────── Power-cut mode ───────────────────────────────
@Composable
fun PowerCutScreen(open: (String) -> Unit) {
    val ctx = LocalContext.current
    val cam = remember { ctx.getSystemService(android.hardware.camera2.CameraManager::class.java) }
    val torchId = remember { runCatching { cam?.cameraIdList?.firstOrNull { cam.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } }.getOrNull() }
    var torch by remember { mutableStateOf(false) }
    var sos by remember { mutableStateOf(false) }
    var lamp by remember { mutableStateOf(false) }
    fun set(on: Boolean) { runCatching { torchId?.let { cam?.setTorchMode(it, on) } } }
    LaunchedEffect(sos) {
        if (sos) while (true) {
            for (d in listOf(200, 200, 200, 600, 600, 600, 200, 200, 200)) { set(true); delay(d.toLong()); set(false); delay(200) }
            delay(1200)
        }
    }
    DisposableEffect(Unit) { onDispose { set(false) } }
    if (lamp) {
        val activity = ctx as? android.app.Activity
        DisposableEffect(Unit) {
            val w = activity?.window; val old = w?.attributes?.screenBrightness
            w?.attributes = w?.attributes?.apply { screenBrightness = 1f }
            onDispose { w?.attributes = w?.attributes?.apply { screenBrightness = old ?: -1f } }
        }
        Box(Modifier.fillMaxSize().background(Color(0xFFFFF4E0)).pressable { lamp = false }, contentAlignment = Alignment.BottomCenter) {
            Text("Tap to close", Modifier.padding(40.dp), color = Color.Gray)
        }
        return
    }
    AtlasScreen(title = "Power-cut mode", subtitle = "Light and battery when the bijli goes", hub = Hub.Home) {
        item("t") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Button(onClick = { sos = false; torch = !torch; set(torch) }, modifier = Modifier.weight(1f).height(72.dp), enabled = torchId != null) { Text(if (torch) "Torch off" else "Torch on", fontSize = 18.sp) }
            OutlinedButton(onClick = { torch = false; sos = !sos; if (!sos) set(false) }, modifier = Modifier.weight(1f).height(72.dp), enabled = torchId != null) { Text(if (sos) "Stop SOS" else "SOS signal") }
        } }
        item("lamp") { ListRow("Screen lamp", supporting = "Warm white full-screen light, softer than the torch", icon = Icons.Outlined.LightMode) { lamp = true } }
        item("ls") { ListRow("Load-shedding schedule", supporting = "When it's coming back", icon = Icons.Outlined.Schedule) { open("loadshedding") } }
        item("saver") { ListRow("Battery saver", supporting = "Open Android's battery saver", icon = Icons.Outlined.BatterySaver) {
            runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } }
        item("tips") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            Text("Stretch the battery", style = MaterialTheme.typography.titleSmall)
            listOf("Turn off mobile data when you don't need it; 4G searching for signal drains fastest.", "Lower brightness; dark mode helps on AMOLED screens.",
                "Close games and camera; they use the most power.", "Keep a power bank charged whenever the UPS is on.", "The torch uses little power; the screen lamp uses more.")
                .forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
        } }
    }
}

// ─────────────────────────────── App manager ───────────────────────────────
private val TRACKERS = listOf(
    "com.google.firebase.analytics" to "Google Firebase Analytics", "com.google.android.gms.ads" to "Google AdMob", "com.google.android.gms.measurement" to "Google Analytics",
    "com.facebook" to "Facebook SDK", "com.appsflyer" to "AppsFlyer", "com.adjust" to "Adjust", "io.branch" to "Branch", "com.applovin" to "AppLovin",
    "com.unity3d.ads" to "Unity Ads", "com.ironsource" to "ironSource", "com.mbridge" to "Mintegral", "com.bytedance" to "Pangle (ByteDance)", "com.moengage" to "MoEngage",
    "com.clevertap" to "CleverTap", "com.onesignal" to "OneSignal", "com.startapp" to "StartApp", "com.inmobi" to "InMobi", "com.chartboost" to "Chartboost",
    "com.vungle" to "Vungle", "com.flurry" to "Flurry", "com.huawei.hms.analytics" to "Huawei Analytics", "com.yandex.metrica" to "Yandex AppMetrica", "com.amplitude" to "Amplitude",
    "com.mixpanel" to "Mixpanel", "com.segment" to "Segment", "com.smaato" to "Smaato", "com.tapjoy" to "Tapjoy", "com.criteo" to "Criteo",
)
private val RISKY = mapOf("android.permission.READ_SMS" to "SMS", "android.permission.RECEIVE_SMS" to "SMS", "android.permission.READ_CALL_LOG" to "Call log", "android.permission.READ_CONTACTS" to "Contacts",
    "android.permission.ACCESS_FINE_LOCATION" to "Location", "android.permission.ACCESS_BACKGROUND_LOCATION" to "Background location", "android.permission.RECORD_AUDIO" to "Microphone",
    "android.permission.CAMERA" to "Camera", "android.permission.READ_PHONE_STATE" to "Phone ID", "android.permission.READ_MEDIA_IMAGES" to "Photos", "android.permission.BODY_SENSORS" to "Body sensors")

private data class AppInfo(val label: String, val pkg: String, val system: Boolean, val trackers: List<String>, val risky: List<String>)

@Composable
fun AppManagerScreen() {
    val ctx = LocalContext.current
    var apps by remember { mutableStateOf<List<AppInfo>?>(null) }
    var showSystem by remember { mutableStateOf(false) }
    var q by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<AppInfo?>(null) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            @Suppress("DEPRECATION")
            val flags = PackageManager.GET_PERMISSIONS or PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
            pm.getInstalledApplications(0).mapNotNull { ai ->
                val info: PackageInfo = runCatching { @Suppress("DEPRECATION") pm.getPackageInfo(ai.packageName, flags) }.getOrNull()
                    ?: runCatching { @Suppress("DEPRECATION") pm.getPackageInfo(ai.packageName, PackageManager.GET_PERMISSIONS) }.getOrNull() ?: return@mapNotNull null
                val comps = (info.activities.orEmpty().map { it.name } + info.services.orEmpty().map { it.name } + info.receivers.orEmpty().map { it.name } + info.providers.orEmpty().map { it.name })
                val tr = TRACKERS.filter { (prefix, _) -> comps.any { it.startsWith(prefix) } }.map { it.second }.distinct()
                val perms = info.requestedPermissions.orEmpty(); val fl = info.requestedPermissionsFlags ?: IntArray(0)
                val granted = perms.indices.filter { it < fl.size && fl[it] and android.content.pm.PackageInfo.REQUESTED_PERMISSION_GRANTED != 0 }.map { perms[it] }
                AppInfo(pm.getApplicationLabel(ai).toString(), ai.packageName, ai.flags and ApplicationInfo.FLAG_SYSTEM != 0, tr, granted.mapNotNull { RISKY[it] }.distinct())
            }.sortedWith(compareByDescending<AppInfo> { it.trackers.size + it.risky.size }.thenBy { it.label.lowercase() })
        }
    }
    val o = open
    if (o != null) {
        AtlasScreen(title = o.label, subtitle = o.pkg, hub = Hub.Tools, actions = { Text("Close", Modifier.padding(Space.s).pressable { open = null }, style = MaterialTheme.typography.labelLarge) }) {
            item("t") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text("Trackers found (${o.trackers.size})", style = MaterialTheme.typography.titleSmall)
                Text(o.trackers.joinToString(", ").ifBlank { "None of the known tracker SDKs" }, style = MaterialTheme.typography.bodyMedium)
                Text("Sensitive access granted", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Space.l))
                Text(o.risky.joinToString(", ").ifBlank { "None" }, style = MaterialTheme.typography.bodyMedium)
            } }
            item("a") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Button(onClick = { ctx.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${o.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Permissions") }
                if (!o.system) OutlinedButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:${o.pkg}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Uninstall") }
            } }
            if (o.system) item("adb") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text("Debloat (from a computer with ADB)", style = MaterialTheme.typography.titleSmall)
                val cmd = "adb shell pm uninstall -k --user 0 ${o.pkg}"
                Text(cmd, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = Space.s))
                Text("Removes it for your user only; a factory reset brings it back. Don't remove things you don't recognise.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Capsule("Copy command", false) {
                    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("adb", cmd)); Feedback.show("Copied")
                }
            } }
        }
        return
    }
    val shown = apps.orEmpty().filter { (showSystem || !it.system) && (q.isBlank() || it.label.contains(q, true) || it.pkg.contains(q, true)) }
    AtlasScreen(title = "App manager", subtitle = apps?.let { l -> "${l.count { !it.system }} apps · ${l.count { !it.system && it.trackers.isNotEmpty() }} with trackers" } ?: "Scanning…",
        hub = Hub.Tools, loading = apps == null) {
        item("dns") { ListRow("Block ads & trackers everywhere", supporting = "Set Private DNS to dns.adguard-dns.com (tap to copy, then open settings)", icon = Icons.Outlined.Shield, tint = Hub.Tools.tint()) {
            (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("dns", "dns.adguard-dns.com"))
            Feedback.show("Copied — Network › Private DNS › Hostname")
            runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_WIRELESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        } }
        item("f") { Row(Modifier.padding(horizontal = Space.page, vertical = Space.s), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text("Search apps") }, singleLine = true)
            Capsule("System", showSystem) { showSystem = !showSystem }
        } }
        shown.forEach { a -> item(a.pkg) {
            ListRow(a.label, supporting = listOfNotNull(if (a.trackers.isNotEmpty()) "${a.trackers.size} tracker${if (a.trackers.size == 1) "" else "s"}" else null,
                a.risky.takeIf { it.isNotEmpty() }?.joinToString(", "), if (a.system) "system" else null).joinToString(" · ").ifBlank { "Clean" },
                icon = Icons.Outlined.Android, tint = if (a.trackers.size >= 3) MaterialTheme.colorScheme.error else Hub.Tools.tint()) { open = a }
        } }
    }
}

// ─────────────────────────────── Data guard ───────────────────────────────
@Composable
fun DataGuardScreen() {
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf<List<Triple<String, Long, Long>>?>(null) }
    var needs by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        @Suppress("DEPRECATION")
        val mode = ops.checkOpNoThrow(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), ctx.packageName)
        if (mode != android.app.AppOpsManager.MODE_ALLOWED) { needs = true; rows = emptyList(); return@LaunchedEffect }
        needs = false
        rows = withContext(Dispatchers.IO) {
            runCatching {
                val nsm = ctx.getSystemService(android.app.usage.NetworkStatsManager::class.java)
                val start = java.time.LocalDate.now().withDayOfMonth(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                val end = System.currentTimeMillis()
                fun byUid(type: Int): Map<Int, Long> {
                    val m = HashMap<Int, Long>()
                    @Suppress("DEPRECATION") val s = nsm.querySummary(type, null, start, end)
                    val b = android.app.usage.NetworkStats.Bucket()
                    while (s.hasNextBucket()) { s.getNextBucket(b); m[b.uid] = (m[b.uid] ?: 0) + b.rxBytes + b.txBytes }
                    s.close(); return m
                }
                @Suppress("DEPRECATION") val mobile = byUid(android.net.ConnectivityManager.TYPE_MOBILE)
                @Suppress("DEPRECATION") val wifi = byUid(android.net.ConnectivityManager.TYPE_WIFI)
                val pm = ctx.packageManager
                (mobile.keys + wifi.keys).distinct().map { uid ->
                    val name = pm.getPackagesForUid(uid)?.firstOrNull()?.let { p -> runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p) }
                        ?: when (uid) { 0 -> "System"; 1000 -> "Android system"; -4 -> "Removed apps"; -5 -> "Tethering"; else -> "uid $uid" }
                    Triple(name, mobile[uid] ?: 0L, wifi[uid] ?: 0L)
                }.sortedByDescending { it.second }.filter { it.second + it.third > 1_000_000 }
            }.getOrDefault(emptyList())
        }
    }
    fun mb(b: Long) = if (b > 1e9) "%.2f GB".format(b / 1e9) else "%.0f MB".format(b / 1e6)
    val list = rows
    AtlasScreen(title = "Data guard", subtitle = list?.let { l -> "This month: ${mb(l.sumOf { it.second })} mobile · ${mb(l.sumOf { it.third })} Wi-Fi" } ?: "Counting…", hub = Hub.Tools, loading = list == null) {
        if (needs) item("p") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            Text("Atlas needs Usage access to read data use per app.", style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { ctx.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }, modifier = Modifier.padding(top = Space.s)) { Text("Allow") }
            OutlinedButton(onClick = { reload++ }, modifier = Modifier.padding(top = Space.s)) { Text("I've allowed it") }
        } }
        list.orEmpty().forEach { (n, m, w) -> item(n) { ListRow(n, supporting = "Mobile ${mb(m)} · Wi-Fi ${mb(w)}", icon = Icons.Outlined.DataUsage,
            tint = if (m > 1_000_000_000) MaterialTheme.colorScheme.error else Hub.Tools.tint()) } }
    }
}
