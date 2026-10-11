package com.nizam.app.feature.nx

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.UserHandle
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

object System2 {
    val all: List<Spec> = listOf(
        ScreenSpec("recorder", "Screen recorder", "Record the screen, with or without your voice", Icons.Outlined.Videocam, Hub.Tools, "screen record capture video tutorial") { _, _ -> RecorderScreen() },
        ScreenSpec("appfirewall", "App firewall", "Cut any app off the internet — no root", Icons.Outlined.Security, Hub.Tools, "firewall block internet app data vpn netguard") { _, _ -> AppFirewallScreen() },
        ScreenSpec("walkie", "Walkie-talkie", "Talk phone-to-phone on the same Wi-Fi or hotspot — no internet", Icons.Outlined.SettingsVoice, Hub.Social, "walkie talkie push to talk offline intercom") { _, _ -> WalkieScreen() },
        ScreenSpec("intruder", "Intruder alert", "Wrong PIN attempts texted to your emergency contacts with location", Icons.Outlined.GppMaybe, Hub.Tools, "anti theft wrong pin password intruder stolen") { _, _ -> IntruderScreen() },
    )
}

// ─────────────────────────────── Screen recorder ───────────────────────────────
class ScreenRecordService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var recorder: MediaRecorder? = null
    private var file: File? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { finish(); stopSelf(); return START_NOT_STICKY }
        val code = intent?.getIntExtra("code", 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra("data", Intent::class.java) else @Suppress("DEPRECATION") intent?.getParcelableExtra("data")
        val mic = intent?.getBooleanExtra("mic", false) == true
        if (data == null) { stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("rec", "Screen recording", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 5, Intent(this, ScreenRecordService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "rec").setSmallIcon(android.R.drawable.presence_video_online).setContentTitle("Recording the screen")
            .setContentText("Tap Stop to save it to Movies/Atlas").addAction(Notification.Action.Builder(null, "Stop", stop).build()).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(11, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION or (if (mic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0))
        else startForeground(11, n)
        runCatching { begin(code, data, mic) }.onFailure { Feedback.show("Couldn't record: ${it.message}"); stopSelf() }
        return START_NOT_STICKY
    }

    private fun begin(code: Int, data: Intent, mic: Boolean) {
        val m = resources.displayMetrics
        val scale = minOf(1f, 1280f / maxOf(m.widthPixels, m.heightPixels))
        val w = ((m.widthPixels * scale).toInt() / 2) * 2; val h = ((m.heightPixels * scale).toInt() / 2) * 2
        val out = File(cacheDir, "screen_${System.currentTimeMillis()}.mp4"); file = out
        recorder = (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()).apply {
            if (mic) setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (mic) { setAudioEncoder(MediaRecorder.AudioEncoder.AAC); setAudioEncodingBitRate(128000); setAudioSamplingRate(44100) }
            setVideoSize(w, h); setVideoFrameRate(30); setVideoEncodingBitRate(6_000_000)
            setOutputFile(out.absolutePath); prepare()
        }
        val p = getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, data) ?: run { recorder?.release(); recorder = null; stopSelf(); return }
        p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { finish(); stopSelf() } }, android.os.Handler(mainLooper))
        projection = p
        display = p.createVirtualDisplay("atlas-rec", w, h, m.densityDpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, recorder!!.surface, null, null)
        recorder!!.start(); running = true
    }

    private fun finish() {
        if (!running) return
        running = false
        runCatching { recorder?.stop() }; runCatching { recorder?.release() }; recorder = null
        runCatching { display?.release() }; display = null
        runCatching { projection?.stop() }; projection = null
        val f = file ?: return
        runCatching {
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, f.name); put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= 29) put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Atlas")
            }
            contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)?.let { u -> contentResolver.openOutputStream(u)?.use { o -> f.inputStream().use { it.copyTo(o) } } }
            f.delete()
            Feedback.show("Saved to Movies/Atlas")
        }
    }

    override fun onDestroy() { finish(); super.onDestroy() }

    companion object { @Volatile var running = false }
}

@Composable
fun RecorderScreen() {
    val ctx = LocalContext.current
    var mic by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(ScreenRecordService.running) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val capture = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK && r.data != null) {
            ContextCompat.startForegroundService(ctx, Intent(ctx, ScreenRecordService::class.java).putExtra("code", r.resultCode).putExtra("data", r.data).putExtra("mic", mic))
            running = true
            (ctx as? Activity)?.moveTaskToBack(true)
        }
    }
    AtlasScreen(title = "Screen recorder", subtitle = if (running) "Recording — stop from the notification" else "Saved to Movies/Atlas when you stop", hub = Hub.Tools) {
        item("mic") { Row(Modifier.fillMaxWidth().padding(horizontal = Space.page), verticalAlignment = Alignment.CenterVertically) {
            Text("Record my voice too", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Switch(mic, { v -> if (v && ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) perm.launch(android.Manifest.permission.RECORD_AUDIO); mic = v })
        } }
        item("go") { Button(onClick = {
            if (running) { ctx.startService(Intent(ctx, ScreenRecordService::class.java).setAction("stop")); running = false }
            else capture.launch(ctx.getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent())
        }, modifier = Modifier.fillMaxWidth().padding(Space.page).height(64.dp)) { Text(if (running) "■ Stop and save" else "● Start recording") } }
        item("n") { Text("Android asks each time before recording starts. Banking apps and DRM video show black — that's the app's protection.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Space.page)) }
    }
}

// ─────────────────────────────── App firewall (VPN that drops blocked apps) ───────────────────────────────
class FirewallVpn : VpnService() {
    private var tun: ParcelFileDescriptor? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { close(); stopSelf(); return START_NOT_STICKY }
        close()
        val blocked = blocked(this)
        if (blocked.isEmpty()) { stopSelf(); return START_NOT_STICKY }
        // Only the blocked apps are routed into this tunnel, and nothing ever reads it: their traffic goes nowhere.
        val b = Builder().setSession("Atlas app firewall").addAddress("10.215.0.1", 32).addRoute("0.0.0.0", 0)
            .addAddress("fd00:215::1", 128).addRoute("::", 0).setBlocking(false)
        blocked.forEach { runCatching { b.addAllowedApplication(it) } }
        tun = runCatching { b.establish() }.getOrNull()
        on = tun != null
        return START_STICKY
    }
    private fun close() { runCatching { tun?.close() }; tun = null; on = false }
    override fun onRevoke() { close(); stopSelf() }
    override fun onDestroy() { close(); super.onDestroy() }

    companion object {
        @Volatile var on = false
        fun blocked(c: Context): Set<String> = c.getSharedPreferences("firewall_apps", 0).getStringSet("blocked", emptySet()).orEmpty()
        fun setBlocked(c: Context, s: Set<String>) = c.getSharedPreferences("firewall_apps", 0).edit().putStringSet("blocked", s).apply()
        fun apply(c: Context) = c.startService(Intent(c, FirewallVpn::class.java))
        fun stop(c: Context) = c.startService(Intent(c, FirewallVpn::class.java).setAction("stop"))
    }
}

@Composable
fun AppFirewallScreen() {
    val ctx = LocalContext.current
    var blocked by remember { mutableStateOf(FirewallVpn.blocked(ctx)) }
    var on by remember { mutableStateOf(FirewallVpn.on) }
    var q by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = ctx.packageManager
            pm.getInstalledApplications(0).filter { it.flags and ApplicationInfo.FLAG_SYSTEM == 0 && it.packageName != ctx.packageName }
                .map { pm.getApplicationLabel(it).toString() to it.packageName }.sortedBy { it.first.lowercase() }
        }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) { FirewallVpn.apply(ctx); on = true }
    }
    fun start() { val i = VpnService.prepare(ctx); if (i != null) consent.launch(i) else { FirewallVpn.apply(ctx); on = true } }
    AtlasScreen(title = "App firewall", subtitle = "${blocked.size} app${if (blocked.size == 1) "" else "s"} blocked · ${if (on) "on" else "off"}", hub = Hub.Tools) {
        item("sw") { Row(Modifier.fillMaxWidth().padding(horizontal = Space.page), verticalAlignment = Alignment.CenterVertically) {
            Column2("Firewall", "Uses Android's VPN slot on the phone; nothing leaves your device", Modifier.weight(1f))
            Switch(on, { v -> if (v) start() else { FirewallVpn.stop(ctx); on = false } }, enabled = blocked.isNotEmpty())
        } }
        item("q") { OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s), placeholder = { Text("Search apps") }, singleLine = true) }
        item("h") { SectionHeader("Tap to block or allow") }
        apps.filter { q.isBlank() || it.first.contains(q, true) }.forEach { (label, pkg) -> item(pkg) {
            val isBlocked = pkg in blocked
            ListRow(label, supporting = if (isBlocked) "Blocked from the internet" else "Allowed", icon = if (isBlocked) Icons.Outlined.Block else Icons.Outlined.Public,
                tint = if (isBlocked) MaterialTheme.colorScheme.error else Hub.Tools.tint()) {
                blocked = if (isBlocked) blocked - pkg else blocked + pkg
                FirewallVpn.setBlocked(ctx, blocked)
                if (on) { if (blocked.isEmpty()) { FirewallVpn.stop(ctx); on = false } else FirewallVpn.apply(ctx) }
            }
        } }
        item("n") { Text("Android allows one VPN at a time — turning on another VPN turns this off. Set it as Always-on VPN in Settings to keep it running after restarts.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Space.page)) }
    }
}

@Composable
private fun Column2(title: String, sub: String, modifier: Modifier) = androidx.compose.foundation.layout.Column(modifier) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

// ─────────────────────────────── Walkie-talkie ───────────────────────────────
@Composable
fun WalkieScreen() {
    val ctx = LocalContext.current
    var channel by remember { mutableStateOf(1) }
    var talking by remember { mutableStateOf(false) }
    var heard by remember { mutableStateOf(0L) }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val me = remember { (Math.random() * Int.MAX_VALUE).toInt() }
    val rate = 16000
    // listen for the whole time the screen is open
    LaunchedEffect(channel) {
        withContext(Dispatchers.IO) {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val lock = wifi.createMulticastLock("atlas-walkie").apply { setReferenceCounted(false); acquire() }
            val sock = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 500; bind(java.net.InetSocketAddress(50500 + channel)) }
            val min = android.media.AudioTrack.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_OUT_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
            val track = android.media.AudioTrack(android.media.AudioManager.STREAM_MUSIC, rate, android.media.AudioFormat.CHANNEL_OUT_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 8192), android.media.AudioTrack.MODE_STREAM)
            track.play()
            val buf = ByteArray(4096)
            try {
                while (isActive) {
                    val pk = DatagramPacket(buf, buf.size)
                    try { sock.receive(pk) } catch (_: java.net.SocketTimeoutException) { continue }
                    if (pk.length <= 4) continue
                    val from = java.nio.ByteBuffer.wrap(buf, 0, 4).int
                    if (from == me) continue
                    track.write(buf, 4, pk.length - 4); heard = System.currentTimeMillis()
                }
            } finally { runCatching { track.stop() }; track.release(); sock.close(); lock.release() }
        }
    }
    // send while the button is held
    LaunchedEffect(talking, channel) {
        if (!talking || !granted) return@LaunchedEffect
        withContext(Dispatchers.IO) {
            val min = android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
            @Suppress("MissingPermission")
            val rec = android.media.AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, android.media.AudioFormat.CHANNEL_IN_MONO,
                android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096))
            val sock = DatagramSocket().apply { broadcast = true }
            val target = InetAddress.getByName("255.255.255.255")
            val frame = ByteArray(640)
            val pkt = ByteArray(644); java.nio.ByteBuffer.wrap(pkt, 0, 4).putInt(me)
            try {
                rec.startRecording()
                while (isActive) {
                    val n = rec.read(frame, 0, frame.size); if (n <= 0) continue
                    System.arraycopy(frame, 0, pkt, 4, n)
                    runCatching { sock.send(DatagramPacket(pkt, n + 4, target, 50500 + channel)) }
                }
            } finally { runCatching { rec.stop() }; rec.release(); sock.close() }
        }
    }
    val live = System.currentTimeMillis() - heard < 600
    AtlasScreen(title = "Walkie-talkie", subtitle = "Channel $channel · everyone on the same Wi-Fi or hotspot with Atlas open hears you", hub = Hub.Social) {
        item("ch") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            (1..6).forEach { c -> Capsule("$c", channel == c) { channel = c } } } }
        item("btn") { Box(Modifier.fillMaxWidth().padding(Space.xl), contentAlignment = Alignment.Center) {
            Box(Modifier.size(200.dp).background(if (talking) MaterialTheme.colorScheme.error else Hub.Social.tint(), CircleShape)
                .pointerInput(granted) { detectTapGestures(onPress = {
                    if (!granted) { perm.launch(android.Manifest.permission.RECORD_AUDIO); return@detectTapGestures }
                    talking = true; tryAwaitRelease(); talking = false }) },
                contentAlignment = Alignment.Center) {
                Text(if (talking) "Talking…" else "Hold to talk", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onPrimary)
            }
        } }
        item("st") { Text(if (live) "🔊 Someone is talking" else "Listening on channel $channel", Modifier.fillMaxWidth().padding(Space.page), style = MaterialTheme.typography.titleMedium) }
        item("n") { Text("No internet used. Works across a home Wi-Fi or one phone's hotspot. Some public Wi-Fi blocks phone-to-phone traffic.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Space.page)) }
    }
}

// ─────────────────────────────── Intruder alert ───────────────────────────────
class IntruderAdmin : DeviceAdminReceiver() {
    override fun onPasswordFailed(context: Context, intent: Intent, user: UserHandle) {
        val p = context.getSharedPreferences("intruder", 0)
        val n = p.getInt("streak", 0) + 1
        val log = (p.getString("log", "").orEmpty().lines().filter { it.isNotBlank() } + java.time.LocalDateTime.now().toString().take(16)).takeLast(50)
        p.edit().putInt("streak", n).putString("log", log.joinToString("\n")).apply()
        val limit = p.getInt("limit", 3)
        if (n == limit && p.getBoolean("sms", true)) runCatching {
            val lm = context.getSystemService(android.location.LocationManager::class.java)
            @Suppress("MissingPermission")
            val loc = listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            val msg = "Atlas alert: $n wrong unlock attempts on my phone at ${java.time.LocalTime.now().toString().take(5)}." +
                (loc?.let { " Last location: https://maps.google.com/?q=${it.latitude},${it.longitude}" } ?: "")
            val sms = if (Build.VERSION.SDK_INT >= 31) context.getSystemService(android.telephony.SmsManager::class.java) else @Suppress("DEPRECATION") android.telephony.SmsManager.getDefault()
            com.nizam.app.feature.daily.Sos.contacts(context).forEach { sms.sendTextMessage(it, null, msg, null, null) }
        }
    }
    override fun onPasswordSucceeded(context: Context, intent: Intent, user: UserHandle) {
        context.getSharedPreferences("intruder", 0).edit().putInt("streak", 0).apply()
    }
}

@Composable
fun IntruderScreen() {
    val ctx = LocalContext.current
    val p = remember { ctx.getSharedPreferences("intruder", 0) }
    val dpm = remember { ctx.getSystemService(DevicePolicyManager::class.java) }
    val comp = remember { ComponentName(ctx, IntruderAdmin::class.java) }
    var active by remember { mutableStateOf(dpm.isAdminActive(comp)) }
    var limit by remember { mutableStateOf(p.getInt("limit", 3)) }
    val enable = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { active = dpm.isAdminActive(comp) }
    val log = p.getString("log", "").orEmpty().lines().filter { it.isNotBlank() }.reversed()
    val contacts = com.nizam.app.feature.daily.Sos.contacts(ctx)
    AtlasScreen(title = "Intruder alert", subtitle = if (active) "Watching for wrong PINs" else "Off", hub = Hub.Tools) {
        item("on") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            if (!active) {
                Text("Atlas needs 'device admin' permission only to be told when an unlock fails. It can't lock, wipe or change anything.", style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { enable.launch(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "Lets Atlas notice wrong unlock attempts and alert your emergency contacts.")) }, modifier = Modifier.padding(top = Space.s)) { Text("Turn on") }
            } else {
                Text("After $limit wrong attempts in a row, your emergency contacts get an SMS with the phone's last location.", style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    listOf(2, 3, 5).forEach { n -> Capsule("$n tries", limit == n) { limit = n; p.edit().putInt("limit", n).apply() } }
                }
                Text(if (contacts.isEmpty()) "⚠ No emergency contacts yet — add them in Daily › SOS." else "Will text: ${contacts.joinToString()}",
                    style = MaterialTheme.typography.bodySmall, color = if (contacts.isEmpty()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.s))
                OutlinedButton(onClick = { dpm.removeActiveAdmin(comp); active = false }, modifier = Modifier.padding(top = Space.s)) { Text("Turn off") }
            }
        } }
        item("h") { SectionHeader("Wrong attempts") }
        if (log.isEmpty()) item("e") { Text("None recorded.", Modifier.padding(Space.page)) }
        log.forEachIndexed { i, t -> item("l$i") { ListRow(t.replace('T', ' '), icon = Icons.Outlined.GppMaybe, tint = MaterialTheme.colorScheme.error) } }
        item("n") { Text("Android no longer lets apps secretly take a photo from the lock screen, so Atlas sends location instead. Lost mode can also ring and locate the phone.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Space.page)) }
    }
}
