package com.nizam.app.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.nizam.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Every download in Atlas — offline AI models, music, podcasts, apps, films — reports here.
 * While anything is downloading, one quiet notification shows what and how far (and keeps Atlas
 * alive in the background so big files finish); when each ends you get a "ready" or "failed" note.
 * Screens can read [all] to show the same status in-app.
 */
object Transfers {
    data class Item(val key: String, val title: String, val percent: Int, val detail: String = "") {
        val indeterminate get() = percent < 0
    }

    private val _all = MutableStateFlow<Map<String, Item>>(emptyMap())
    val all: StateFlow<Map<String, Item>> = _all
    const val CHANNEL = "downloads"
    private const val DONE_CHANNEL = "downloads_done"

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads in progress", NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(DONE_CHANNEL, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
    }

    /** [percent] 0–100, or -1 while the size isn't known yet. */
    fun progress(c: Context, key: String, title: String, percent: Int, detail: String = "") {
        val before = _all.value[key]
        _all.value = _all.value + (key to Item(key, title, percent, detail))
        if (before == null) TransferService.start(c.applicationContext)
    }

    fun done(c: Context, key: String, title: String, text: String) = finish(c, key, title, text, ok = true)
    fun failed(c: Context, key: String, title: String, error: String) = finish(c, key, title, error, ok = false)
    fun cancelled(key: String) { _all.value = _all.value - key }

    private fun finish(c: Context, key: String, title: String, text: String, ok: Boolean) {
        _all.value = _all.value - key
        runCatching {
            channels(c)
            val open = PendingIntent.getActivity(c, 0, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(c, DONE_CHANNEL)
                .setSmallIcon(if (ok) android.R.drawable.stat_sys_download_done else android.R.drawable.stat_notify_error)
                .setContentTitle(if (ok) "$title — ready" else "$title — didn't finish")
                .setContentText(text).setStyle(Notification.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(open).build()
            c.getSystemService(NotificationManager::class.java).notify(3000 + (key.hashCode() and 0xfff), n)
        }
    }
}

/** The foreground service behind [Transfers]: alive exactly while something is downloading. */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        post()
        if (ticker == null) ticker = scope.launch {
            while (true) {
                delay(1500)
                if (Transfers.all.value.isEmpty()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); break }
                post()
            }
        }
        return START_NOT_STICKY
    }

    private fun post() {
        Transfers.channels(this)
        val items = Transfers.all.value.values.toList()
        val first = items.firstOrNull()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, Transfers.CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
        if (items.size <= 1) {
            b.setContentTitle(first?.title ?: "Downloading")
                .setContentText(listOfNotNull(first?.percent?.takeIf { it >= 0 }?.let { "$it%" }, first?.detail?.ifBlank { null }).joinToString(" · "))
                .setProgress(100, first?.percent?.coerceAtLeast(0) ?: 0, first?.indeterminate ?: true)
        } else {
            b.setContentTitle("${items.size} downloads")
                .setContentText(items.joinToString(" · ") { it.title })
                .setStyle(Notification.InboxStyle().also { s -> items.take(6).forEach { s.addLine("${it.title} — ${if (it.percent >= 0) "${it.percent}%" else "starting"}") } })
                .setProgress(100, items.filter { it.percent >= 0 }.map { it.percent }.average().takeIf { !it.isNaN() }?.toInt() ?: 0, false)
        }
        val n = b.build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(14, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else startForeground(14, n)
    }

    override fun onDestroy() { scope.coroutineContext[Job]?.cancel(); super.onDestroy() }

    companion object {
        fun start(c: Context) = runCatching { c.startForegroundService(Intent(c, TransferService::class.java)) }
    }
}
