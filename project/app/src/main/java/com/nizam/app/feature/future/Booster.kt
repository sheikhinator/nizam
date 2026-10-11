package com.nizam.app.feature.future

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.nizam.app.core.ModuleTitle
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedButton

/** Game mode: frees memory, silences the phone, and floats live stats over your game. */
object Booster {
    data class Game(val pkg: String, val label: String)

    fun games(c: Context): List<Game> = c.packageManager.getInstalledApplications(0).filter { a ->
        (a.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && (a.category == ApplicationInfo.CATEGORY_GAME ||
            (a.flags and ApplicationInfo.FLAG_IS_GAME) != 0)
    }.map { Game(it.packageName, c.packageManager.getApplicationLabel(it).toString()) }.sortedBy { it.label.lowercase() }

    fun freeMb(c: Context) = ActivityManager.MemoryInfo().also { c.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.availMem / 1_048_576

    /** Closes other apps' background processes; returns MB freed. */
    fun clean(c: Context, keep: String? = null): Long {
        val before = freeMb(c)
        val am = c.getSystemService(ActivityManager::class.java)
        c.packageManager.getInstalledApplications(0).filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != keep && it.packageName != c.packageName }
            .forEach { runCatching { am.killBackgroundProcesses(it.packageName) } }
        return (freeMb(c) - before).coerceAtLeast(0)
    }

    fun start(c: Context, game: Game?, silence: Boolean, overlay: Boolean): String {
        val freed = clean(c, game?.pkg)
        if (silence) com.nizam.app.feature.phone.PhoneActions.doNotDisturb(c, true)
        if (overlay && Settings.canDrawOverlays(c)) runCatching { c.startForegroundService(Intent(c, GameOverlayService::class.java)) }
        c.getSharedPreferences("booster", 0).edit().putBoolean("on", true).putBoolean("silenced", silence).apply()
        game?.let { g -> c.packageManager.getLaunchIntentForPackage(g.pkg)?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
        return "Freed ${freed} MB" + (if (silence) " · calls and alerts silenced" else "")
    }

    fun stop(c: Context) {
        val p = c.getSharedPreferences("booster", 0)
        if (p.getBoolean("silenced", false)) com.nizam.app.feature.phone.PhoneActions.doNotDisturb(c, false)
        c.stopService(Intent(c, GameOverlayService::class.java))
        p.edit().putBoolean("on", false).apply()
    }
    fun on(c: Context) = c.getSharedPreferences("booster", 0).getBoolean("on", false)
}

/** The small floating readout during a game: time, battery, battery temperature, free memory. */
class GameOverlayService : Service() {
    private var view: TextView? = null
    private val handler = Handler(Looper.getMainLooper())
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("game", "Game mode", NotificationManager.IMPORTANCE_LOW))
        val stop = android.app.PendingIntent.getService(this, 9, Intent(this, GameOverlayService::class.java).setAction("stop"), android.app.PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "game").setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Game mode on")
            .setContentText("Tap End to finish").addAction(Notification.Action.Builder(null, "End", stop).build()).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(12, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(12, n)
        if (intent?.action == "stop") { Booster.stop(this); stopSelf(); return START_NOT_STICKY }
        if (view == null && Settings.canDrawOverlays(this)) {
            val tv = TextView(this).apply {
                setTextColor(android.graphics.Color.WHITE); textSize = 11f; setPadding(18, 8, 18, 8)
                background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = 30f; setColor(0x99000000.toInt()) }
            }
            val lp = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 90 }
            runCatching { getSystemService(WindowManager::class.java).addView(tv, lp); view = tv }
            tick()
        }
        return START_STICKY
    }

    private fun tick() {
        val b = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temp = (b?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val level = b?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val time = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("h:mm"))
        view?.text = "$time  ·  🔋$level%  ·  🌡${"%.0f".format(temp)}°C  ·  ${"%.1f".format(Booster.freeMb(this) / 1024.0)} GB free"
        view?.setTextColor(if (temp >= 43) android.graphics.Color.rgb(255, 120, 100) else android.graphics.Color.WHITE)
        handler.postDelayed({ if (view != null) tick() }, 3000)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        view?.let { runCatching { getSystemService(WindowManager::class.java).removeView(it) } }; view = null
        super.onDestroy()
    }
}

@Composable
fun BoosterScreen() {
    val c = LocalContext.current
    val games = remember { Booster.games(c) }
    var silence by remember { mutableStateOf(true) }
    var overlay by remember { mutableStateOf(true) }
    var on by remember { mutableStateOf(Booster.on(c)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Game booster", "${Booster.freeMb(c) / 1024} GB of memory free right now")
        Group {
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Silence calls and notifications", Modifier.weight(1f)); Switch(silence, { silence = it }) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text("Show stats over the game", Modifier.weight(1f)); Switch(overlay, { overlay = it
                if (it && !Settings.canDrawOverlays(c)) runCatching { c.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))) } }) }
            Row(Modifier.padding(top = 8.dp)) {
                if (on) Button(onClick = { Booster.stop(c); on = false; Feedback.show("Game mode off") }) { Text("End game mode") }
                else Button(onClick = { Feedback.show(Booster.start(c, null, silence, overlay)); on = true }) { Text("Boost now") }
                Spacer(Modifier.padding(4.dp))
                OutlinedButton(onClick = { Feedback.show("Freed ${Booster.clean(c)} MB") }) { Text("Just free memory") }
            }
        }
        Label(if (games.isEmpty()) "No games found" else "Boost and play")
        games.forEach { g ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { Feedback.show(Booster.start(c, g, silence, overlay)); on = true }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                val icon = remember(g.pkg) { runCatching { c.packageManager.getApplicationIcon(g.pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull() }
                icon?.let { Image(it, null, Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))) }
                Text(g.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f).padding(start = 12.dp))
                Text("Play", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
        Text("Your GT 50 Pro also has XOS Game Space for its cooling fan and RGB lights — Atlas works alongside it. " +
            "Frame rate can't be read by apps without root, so Atlas shows temperature and memory instead.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
        Spacer(Modifier.height(40.dp))
    }
}
