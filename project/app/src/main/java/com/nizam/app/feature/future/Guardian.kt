package com.nizam.app.feature.future

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.phone.PhoneActions
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Guardian: a scam shield on your messages, SOS to family, crash and fall detection, and alerts for elders' phones. */
object Guardian {
    data class Contact(val name: String, val phone: String)
    data class Verdict(val score: Int, val reasons: List<String>)

    private fun p(c: Context) = c.getSharedPreferences("guardian", 0)
    fun contacts(c: Context): List<Contact> = runCatching { JSONArray(p(c).getString("contacts", "[]")) }.getOrElse { JSONArray() }
        .let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Contact(it.optString("n"), it.optString("p")) } }
    fun saveContacts(c: Context, l: List<Contact>) = p(c).edit().putString("contacts", JSONArray().apply { l.forEach { put(JSONObject().put("n", it.name).put("p", it.phone)) } }.toString()).apply()
    fun shield(c: Context) = p(c).getBoolean("shield", true)
    fun setShield(c: Context, v: Boolean) = p(c).edit().putBoolean("shield", v).apply()
    fun familyAlerts(c: Context) = p(c).getBoolean("family", false)
    fun setFamilyAlerts(c: Context, v: Boolean) = p(c).edit().putBoolean("family", v).apply()
    fun crash(c: Context) = p(c).getBoolean("crash", false)
    fun setCrash(c: Context, v: Boolean) { p(c).edit().putBoolean("crash", v).apply(); if (v) GuardianService.start(c) else c.stopService(Intent(c, GuardianService::class.java)) }
    fun warnings(c: Context): List<String> = p(c).getString("log", "").orEmpty().split("\n\u0001").filter { it.isNotBlank() }
    private fun logWarning(c: Context, s: String) = p(c).edit().putString("log", (listOf(s) + warnings(c)).take(40).joinToString("\n\u0001")).apply()

    // ── scam shield ────────────────────────────────────────────────────────────────────────────
    private val SIGNS = listOf(
        Regex("\\b(won|winner|lottery|prize|lucky draw|inaam|jeet)\\b", RegexOption.IGNORE_CASE) to "promises a prize",
        Regex("\\b(bisp|benazir|ehsaas|8171)\\b", RegexOption.IGNORE_CASE) to "uses BISP/Ehsaas — a favourite of scammers",
        Regex("\\b(otp|code|pin|password)\\b.{0,40}\\b(share|send|batayen|bata|forward|tell)\\b|\\b(share|send|bata)\\b.{0,40}\\b(otp|code|pin)\\b", RegexOption.IGNORE_CASE) to "asks for a code/OTP — no real company ever does",
        Regex("\\b(account|sim|wallet|jazzcash|easypaisa)\\b.{0,40}\\b(blocked|suspended|band|close|expire|verify)\\b", RegexOption.IGNORE_CASE) to "says your account will be blocked",
        Regex("\\b(urgent|immediately|within 24 hours|foran|abhi)\\b", RegexOption.IGNORE_CASE) to "rushes you",
        Regex("\\b(send|transfer|bhej)\\b.{0,30}\\b(money|rs|pkr|rupees|paise|amount)\\b", RegexOption.IGNORE_CASE) to "asks you to send money",
        Regex("\\b(new number|mera naya number|this is my new)\\b", RegexOption.IGNORE_CASE) to "claims to be someone on a new number",
        Regex("\\b(fbr|customs|pta|parcel|courier)\\b.{0,40}\\b(fine|fee|held|pay|charges)\\b", RegexOption.IGNORE_CASE) to "demands a fee for a parcel/fine",
        Regex("\\b(investment|profit|double|crypto|trading)\\b.{0,40}\\b(guaranteed|daily|%|percent)\\b", RegexOption.IGNORE_CASE) to "promises guaranteed profit",
        Regex("\\b(army|police|fia|court|arrest)\\b.{0,40}\\b(call|contact|case|warrant)\\b", RegexOption.IGNORE_CASE) to "threatens with police/FIA",
    )
    private val URL = Regex("(https?://\\S+|\\b[\\w-]+\\.(?:xyz|top|click|icu|live|info|online|site|shop|link|tk|ml|ga|cf)\\b\\S*)", RegexOption.IGNORE_CASE)
    private val SHORT = listOf("bit.ly", "tinyurl", "t.ly", "cutt.ly", "is.gd", "rb.gy", "shorturl", "t.co", "ow.ly")

    fun check(text: String): Verdict {
        val reasons = SIGNS.filter { it.first.containsMatchIn(text) }.map { it.second }.toMutableList()
        URL.findAll(text).map { it.value.lowercase() }.forEach { u ->
            when {
                SHORT.any { u.contains(it) } -> reasons += "hides its link behind a short URL"
                Regex("https?://\\d+\\.\\d+\\.\\d+\\.\\d+").containsMatchIn(u) -> reasons += "links to a bare IP address"
                Regex("(jazzcash|easypaisa|bisp|hbl|meezan|ubl|daraz|nadra)[-.]").containsMatchIn(u) && !Regex("\\.(com\\.pk|gov\\.pk|pk)(/|$)").containsMatchIn(u) -> reasons += "a lookalike of an official site"
                Regex("\\.(xyz|top|click|icu|tk|ml|ga|cf)\\b").containsMatchIn(u) -> reasons += "uses a throwaway web address"
            }
        }
        val score = reasons.distinct().size * 30 + if (URL.containsMatchIn(text) && reasons.isNotEmpty()) 20 else 0
        return Verdict(score.coerceAtMost(100), reasons.distinct())
    }

    private var lastFamily = 0L
    fun onMessage(c: Context, app: String, from: String, text: String) {
        if (!shield(c)) return
        val v = check(text)
        if (v.score < 60) return
        val line = "$app · $from: ${text.take(90)} — ${v.reasons.joinToString(", ")}"
        logWarning(c, "${java.time.LocalDateTime.now().toString().take(16).replace('T', ' ')}  $line")
        com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 1300 + (from.hashCode() and 0xFF),
            "⚠️ Likely scam from $from", "It ${v.reasons.first()}. Don't send money, codes or tap its links.")
        if (familyAlerts(c) && System.currentTimeMillis() - lastFamily > 3_600_000) {
            lastFamily = System.currentTimeMillis()
            contacts(c).take(2).forEach { PhoneActions.sms(c, it.phone, "Atlas guardian: this phone just got a likely scam message from $from (${v.reasons.first()}). You may want to check in.", sendDirectly = true) }
        }
    }

    // ── SOS ────────────────────────────────────────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    fun where(c: Context): String {
        val lm = c.getSystemService(LocationManager::class.java)
        val loc = runCatching { listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time } }.getOrNull()
        return loc?.let { "https://maps.google.com/?q=${it.latitude},${it.longitude}" } ?: "(location unavailable)"
    }

    fun sos(c: Context, why: String = "I need help"): String {
        val list = contacts(c)
        if (list.isEmpty()) return "Add emergency contacts first"
        val msg = "🆘 $why. My location: ${where(c)} — sent by Atlas SOS"
        list.forEach { PhoneActions.sms(c, it.phone, msg, sendDirectly = true) }
        runCatching { c.startActivity(Intent(Intent.ACTION_CALL, android.net.Uri.parse("tel:${list.first().phone}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        logWarning(c, "${java.time.LocalDateTime.now().toString().take(16).replace('T', ' ')}  SOS sent to ${list.joinToString { it.name }}")
        return "SOS sent to ${list.size} contact${if (list.size == 1) "" else "s"}, calling ${list.first().name}"
    }
}

/** Watches for a hard fall or crash (free-fall then a big impact, or a violent jolt), then counts down 30 s before SOS. */
class GuardianService : Service(), SensorEventListener {
    private var freeFallAt = 0L
    private var counting = false
    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: android.media.Ringtone? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ok") { cancelCountdown(); return START_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("guardian", "Guardian", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("guardian_alert", "Guardian alerts", NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(this, "guardian").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Guardian is on").setContentText("Crash and fall detection").build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(21, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(21, n)
        val sm = getSystemService(SensorManager::class.java)
        sm.unregisterListener(this)
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        return START_STICKY
    }

    override fun onSensorChanged(e: SensorEvent) {
        val g = kotlin.math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / SensorManager.GRAVITY_EARTH
        val now = System.currentTimeMillis()
        if (g < 0.35f) { if (freeFallAt == 0L) freeFallAt = now }
        else if (freeFallAt != 0L && now - freeFallAt > 1500) freeFallAt = 0L
        val fell = freeFallAt != 0L && now - freeFallAt in 200..1500 && g > 3.0f
        val crashed = g > 6.0f                                     // a violent jolt, like a collision
        if ((fell || crashed) && !counting) { freeFallAt = 0L; startCountdown(if (crashed) "a crash" else "a fall") }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun startCountdown(what: String) {
        counting = true
        val ok = PendingIntent.getService(this, 3, Intent(this, GuardianService::class.java).setAction("ok"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "guardian_alert").setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Are you OK? Atlas detected $what").setContentText("SOS goes to your emergency contacts in 30 seconds")
            .addAction(Notification.Action.Builder(null, "I'm OK", ok).build()).setOngoing(true).build()
        getSystemService(NotificationManager::class.java).notify(22, n)
        runCatching { ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
            audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build(); play() } }
        runCatching { getSystemService(Vibrator::class.java).vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0)) }
        handler.postDelayed({ if (counting) { stopAlarm(); counting = false; getSystemService(NotificationManager::class.java).cancel(22); Guardian.sos(this, "Atlas detected $what and I didn't respond") } }, 30_000)
    }
    private fun stopAlarm() { runCatching { ringtone?.stop() }; runCatching { getSystemService(Vibrator::class.java).cancel() } }
    private fun cancelCountdown() { counting = false; handler.removeCallbacksAndMessages(null); stopAlarm(); getSystemService(NotificationManager::class.java).cancel(22) }

    override fun onDestroy() { getSystemService(SensorManager::class.java).unregisterListener(this); cancelCountdown(); super.onDestroy() }

    companion object { fun start(c: Context) = runCatching { c.startForegroundService(Intent(c, GuardianService::class.java)) } }
}

@Composable
fun GuardianScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var contacts by remember { mutableStateOf(Guardian.contacts(c)) }
    var shield by remember { mutableStateOf(Guardian.shield(c)) }
    var family by remember { mutableStateOf(Guardian.familyAlerts(c)) }
    var crash by remember { mutableStateOf(Guardian.crash(c)) }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var check by remember { mutableStateOf("") }
    var verdict by remember { mutableStateOf("") }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    fun need() = perms.launch(arrayOf(Manifest.permission.SEND_SMS, Manifest.permission.CALL_PHONE, Manifest.permission.ACCESS_FINE_LOCATION))
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Guardian", "Protection for you and your family")
        // hold-to-send, so a pocket tap never triggers it
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(150.dp).clip(CircleShape).background(Color(0xFFD93025)).pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    if (ContextCompat.checkSelfPermission(c, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) need()
                    else Feedback.show(Guardian.sos(c))
                }, onTap = { Feedback.show("Hold for 1 second to send SOS") })
            }, contentAlignment = Alignment.Center) { Text("SOS", color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Black) }
        }
        Text("Hold to text your location to your emergency contacts and call the first one.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())

        Label("Emergency contacts")
        Group {
            contacts.forEach { ct -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                Text("${ct.name} · ${ct.phone}", Modifier.weight(1f))
                Text("✕", modifier = Modifier.padding(8.dp).pointerInput(ct) { detectTapGestures { contacts = contacts - ct; Guardian.saveContacts(c, contacts) } })
            } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("Name") })
                Spacer(Modifier.size(6.dp))
                OutlinedTextField(phone, { phone = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("03xx…") })
            }
            Button(enabled = name.isNotBlank() && phone.count { it.isDigit() } >= 10, onClick = {
                contacts = contacts + Guardian.Contact(name.trim(), phone.trim()); Guardian.saveContacts(c, contacts); name = ""; phone = ""; need()
            }, modifier = Modifier.padding(top = 6.dp)) { Text("Add") }
        }

        Label("Protection")
        Group {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("Scam shield", style = MaterialTheme.typography.titleSmall)
                    Text("Warns you about fake prize, BISP, JazzCash/Easypaisa, OTP and “new number” messages", style = MaterialTheme.typography.bodySmall) }
                Switch(shield, { shield = it; Guardian.setShield(c, it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) { Text("Tell my family", style = MaterialTheme.typography.titleSmall)
                    Text("For a parent's phone: texts the first two contacts when a scam arrives here", style = MaterialTheme.typography.bodySmall) }
                Switch(family, { family = it; Guardian.setFamilyAlerts(c, it); if (it) need() })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Column(Modifier.weight(1f)) { Text("Crash & fall detection", style = MaterialTheme.typography.titleSmall)
                    Text("A hard fall or crash starts a 30-second alarm; if you don't tap “I'm OK”, SOS goes out", style = MaterialTheme.typography.bodySmall) }
                Switch(crash, { crash = it; Guardian.setCrash(c, it); if (it) need() })
            }
        }

        Label("Check a message or link")
        Group {
            OutlinedTextField(check, { check = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, placeholder = { Text("Paste a suspicious SMS, WhatsApp message or link") })
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = check.isNotBlank(), onClick = {
                    val v = Guardian.check(check)
                    verdict = if (v.score >= 60) "⚠️ Likely a scam: ${v.reasons.joinToString("; ")}." else if (v.reasons.isNotEmpty()) "Be careful: ${v.reasons.joinToString("; ")}." else "No common scam signs — asking Atlas for a closer look…"
                    scope.launch { runCatching { Ai.generate(container.settingsRepository, "Is this message or link a scam? Answer: verdict (Scam / Suspicious / Looks fine), " +
                        "then 2-3 short reasons and what to do. Pakistan context (JazzCash, Easypaisa, BISP, FBR, PTA).\n\n$check", maxOutputTokens = 350) }
                        .onSuccess { verdict += "\n\n$it" } }
                }) { Text("Check") }
            }
            if (verdict.isNotBlank()) Text(verdict, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
        val log = Guardian.warnings(c)
        if (log.isNotEmpty()) { Label("Recent warnings"); Group { log.take(10).forEach { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 3.dp)) } } }
        Spacer(Modifier.height(50.dp))
    }
}
