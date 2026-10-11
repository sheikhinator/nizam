package com.nizam.app.feature.presence

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import com.nizam.app.MainActivity
import kotlin.math.abs

/**
 * A small Atlas bubble that floats over every app. Tap → the Curator opens, ready to listen.
 * Drag it anywhere; drag it to the bottom of the screen to dismiss it.
 * Runs as a foreground service because Android kills background overlays within minutes.
 */
class BubbleService : Service() {
    private var bubble: TextView? = null
    private val wm by lazy { getSystemService(WindowManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        startForeground()
        if (bubble == null && Settings.canDrawOverlays(this)) show()
        return START_STICKY
    }

    private fun startForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("bubble", "Atlas bubble", NotificationManager.IMPORTANCE_MIN))
        val stop = PendingIntent.getService(this, 1, Intent(this, BubbleService::class.java).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "bubble")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Atlas bubble is on")
            .setContentText("Tap the bubble to talk to Atlas · hold it to circle anything on screen")
            .addAction(Notification.Action.Builder(null, "Hide bubble", stop).build())
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(7, n)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        val size = (56 * resources.displayMetrics.density).toInt()
        val view = TextView(this).apply {
            text = "✦"; textSize = 24f; gravity = Gravity.CENTER; setTextColor(0xFF0B0F1A.toInt())
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF4FC3A1.toInt()) }
            elevation = 12f; contentDescription = "Open Atlas"
        }
        val params = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 20; y = 400 }

        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; true }
                MotionEvent.ACTION_MOVE -> {
                    params.x = startX + (e.rawX - downX).toInt(); params.y = startY + (e.rawY - downY).toInt()
                    wm.updateViewLayout(v, params); true
                }
                MotionEvent.ACTION_UP -> {
                    val moved = abs(e.rawX - downX) + abs(e.rawY - downY)
                    if (moved < 20 && e.eventTime - e.downTime > 550) {
                        // long-press: Circle to Search on whatever app is open (the bubble hides for the capture);
                        // without screen control, the co-pilot instead
                        v.visibility = android.view.View.INVISIBLE
                        v.postDelayed({
                            val started = com.nizam.app.feature.future.CircleNow.start(this)
                            v.postDelayed({ v.visibility = android.view.View.VISIBLE }, 400)
                            if (!started) com.nizam.app.feature.jarvis.CopilotActivity.open(this)
                        }, 180)
                    } else if (moved < 20) {
                        startActivity(Intent(this, MainActivity::class.java)
                            .setAction(MainActivity.ACTION_OPEN_CURATOR)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                    } else if (e.rawY > resources.displayMetrics.heightPixels * 0.9f) {
                        stopSelf()   // dragged to the bottom edge: dismiss
                    }
                    true
                }
                else -> false
            }
        }
        wm.addView(view, params); bubble = view
    }

    override fun onDestroy() {
        bubble?.let { runCatching { wm.removeView(it) } }; bubble = null
        super.onDestroy()
    }

    companion object {
        fun start(c: Context) = c.startForegroundService(Intent(c, BubbleService::class.java))
        fun stop(c: Context) = c.startService(Intent(c, BubbleService::class.java).setAction("stop"))
    }
}
