package com.nizam.app.feature.body

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.insight.HealthCache
import com.nizam.app.ui.theme.NizamTheme
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Smart wake. The phone on the mattress feels how much you move; light sleep moves more than deep
 * sleep. In the window before your alarm, a burst of movement means you're near the surface — wake
 * then, not mid-deep-sleep. It's an estimate, like early Sleep Cycle — not a sleep lab.
 *
 * The guaranteed alarm is an AlarmManager alarm-clock at your latest time. Smart wake can only make
 * it EARLIER, never later — so if Android ever kills the tracker, you still wake on time.
 */
object SmartWake {
    private fun p(c: Context) = c.getSharedPreferences("smartwake", 0)
    fun target(c: Context) = p(c).getLong("target", 0)
    fun windowMin(c: Context) = p(c).getInt("window", 30)
    fun active(c: Context) = target(c) > System.currentTimeMillis()

    private fun alarmIntent(c: Context) = PendingIntent.getActivity(c, 501, Intent(c, WakeActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun arm(c: Context, wake: LocalTime, window: Int) {
        var at = LocalDateTime.of(LocalDate.now(), wake)
        if (at.isBefore(LocalDateTime.now())) at = at.plusDays(1)
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        p(c).edit().putLong("target", ms).putInt("window", window).putLong("slept", System.currentTimeMillis()).apply()
        c.getSystemService(AlarmManager::class.java).setAlarmClock(AlarmManager.AlarmClockInfo(ms, alarmIntent(c)), alarmIntent(c))
        c.startForegroundService(Intent(c, SleepService::class.java))
    }

    fun disarm(c: Context, woke: Boolean) {
        if (woke) {   // record the night as sleep hours, unless a watch already did better
            val slept = p(c).getLong("slept", 0)
            if (slept > 0) {
                val hours = (System.currentTimeMillis() - slept) / 3_600_000.0
                val key = LocalDate.now().toString(); val cache = HealthCache(c)
                val all = cache.load().toMutableMap()
                if (all[key]?.first == null && hours in 2.0..14.0) { all[key] = hours to all[key]?.second; cache.save(all) }
            }
        }
        c.getSystemService(AlarmManager::class.java).cancel(alarmIntent(c))
        p(c).edit().putLong("target", 0).apply()
        c.stopService(Intent(c, SleepService::class.java))
    }

    fun wakeNow(c: Context) {
        c.getSystemService(AlarmManager::class.java).cancel(alarmIntent(c))
        c.startActivity(Intent(c, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class SleepService : Service(), SensorEventListener {
    private var last = 0f
    private var bucket = 0f
    private var bucketStart = 0L
    private val minutes = ArrayDeque<Float>()   // movement per minute, the whole night
    private var fired = false

    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("sleep", "Smart wake", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "sleep").setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("Smart wake is on").setContentText("Phone on the mattress, face-down is fine. Sleep well.")
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(8, n)
        val sm = getSystemService(SensorManager::class.java)
        sm.registerListener(this, sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_NORMAL)
        bucketStart = System.currentTimeMillis()
        return START_STICKY
    }

    override fun onSensorChanged(e: SensorEvent) {
        val mag = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
        if (last != 0f) bucket += abs(mag - last)
        last = mag
        val now = System.currentTimeMillis()
        if (now - bucketStart < 60_000) return
        minutes.addLast(bucket); bucket = 0f; bucketStart = now
        val target = SmartWake.target(this)
        if (fired || target == 0L) return
        val inWindow = now >= target - SmartWake.windowMin(this) * 60_000L
        if (inWindow && minutes.size >= 30) {
            // "light sleep" = the last two minutes moved far more than a typical minute tonight
            val median = minutes.sorted()[minutes.size / 2].coerceAtLeast(0.05f)
            val recent = minutes.takeLast(2).average().toFloat()
            if (recent > median * 4f) { fired = true; SmartWake.wakeNow(this) }
        }
    }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onDestroy() { getSystemService(SensorManager::class.java).unregisterListener(this); super.onDestroy() }
}

class WakeActivity : ComponentActivity() {
    private var ring: Ringtone? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        ring = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.also {
            if (Build.VERSION.SDK_INT >= 28) it.isLooping = true; it.play()
        }
        setContent {
            NizamTheme(darkTheme = true) {
                Column(Modifier.fillMaxSize().background(Color(0xFF0B0F1A)).padding(28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("☀️", fontSize = 80.sp)
                    Text(LocalTime.now().let { "%02d:%02d".format(it.hour, it.minute) }, color = Color.White, fontSize = 64.sp)
                    Text("Good morning. You woke from light sleep.", color = Color(0xFFB8BCC8))
                    Spacer(Modifier.height(40.dp))
                    Button(onClick = { ring?.stop(); SmartWake.disarm(this@WakeActivity, woke = true); finish() },
                        modifier = Modifier.fillMaxWidth().height(64.dp)) { Text("I'm up", fontSize = 20.sp) }
                }
            }
        }
    }
    override fun onDestroy() { ring?.stop(); super.onDestroy() }
}

@Composable
fun SmartWakeScreen() {
    val c = LocalContext.current
    var hour by remember { mutableStateOf("5") }
    var minute by remember { mutableStateOf("00") }
    var window by remember { mutableStateOf("30") }
    var active by remember { mutableStateOf(SmartWake.active(c)) }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        ModuleTitle("Smart wake", "Woken in light sleep, never later than your alarm")
        if (active) {
            val t = java.time.Instant.ofEpochMilli(SmartWake.target(c)).atZone(ZoneId.systemDefault()).toLocalTime()
            Text("Armed. Latest wake: %02d:%02d, earliest ${SmartWake.windowMin(c)} min before.".format(t.hour, t.minute),
                style = MaterialTheme.typography.titleMedium)
            Text("Put the phone on your mattress near your pillow. Charging is a good idea.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { SmartWake.disarm(c, woke = false); active = false }) { Text("Cancel") }
        } else {
            Text("Latest wake time", style = MaterialTheme.typography.labelMedium)
            ChipRow((3..9).map { "$it" }, hour, { hour = it }, labels = { "$it" })
            ChipRow(listOf("00", "15", "30", "45"), minute, { minute = it }, labels = { ":$it" })
            Text("Wake window", style = MaterialTheme.typography.labelMedium)
            ChipRow(listOf("15", "20", "30", "45"), window, { window = it }, labels = { "$it min" })
            Button(onClick = { SmartWake.arm(c, LocalTime.of(hour.toInt(), minute.toInt()), window.toInt()); active = true }) {
                Text("Arm for ${hour}:${minute}")
            }
            Text("The alarm at your latest time is guaranteed, even if the tracker is stopped overnight. Smart wake only ever " +
                "makes it earlier. Sleep hours are logged too, if you don't wear a watch.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        }
    }
}
