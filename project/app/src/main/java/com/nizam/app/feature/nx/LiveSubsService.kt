package com.nizam.app.feature.nx

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
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.nizam.app.feature.jarvis.Background
import com.nizam.app.net.Ai
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Live translated subtitles over any app: the mic hears what's playing, Android's recogniser
 * turns it into text in the spoken language, and each finished line is translated and shown
 * in a floating bar. Runs as a microphone foreground service so Android doesn't cut it off.
 */
class LiveSubsService : Service() {
    private val wm by lazy { getSystemService(WindowManager::class.java) }
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var bar: LinearLayout? = null
    private var original: TextView? = null
    private var translated: TextView? = null
    private var sr: SpeechRecognizer? = null
    private var from = "zh-CN"
    private var to = "English"
    private var alive = false
    private var seq = 0
    private val offline get() = getSharedPreferences("livesubs", 0).getBoolean("offline", false)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { stopSelf(); return START_NOT_STICKY }
        from = intent?.getStringExtra("from") ?: from
        to = intent?.getStringExtra("to") ?: to
        foreground()
        if (bar == null) show()
        if (!alive) { alive = true; running = true; listen() }
        return START_NOT_STICKY
    }

    private fun foreground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("livesubs", "Live subtitles", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(this, 3, Intent(this, LiveSubsService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "livesubs").setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Live subtitles are on").setContentText("Listening and translating to $to")
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(9, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE) else startForeground(9, n)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun show() {
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((14 * d).toInt(), (8 * d).toInt(), (14 * d).toInt(), (10 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 16 * d; setColor(0xCC000000.toInt()) }
        }
        val o = TextView(this).apply { textSize = 12f; setTextColor(0xFFBBBBBB.toInt()); maxLines = 2 }
        val t = TextView(this).apply { textSize = 18f; setTextColor(0xFFFFFFFF.toInt()); maxLines = 3; text = "Listening…" }
        box.addView(o); box.addView(t)
        val params = WindowManager.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.94f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL; y = (90 * d).toInt() }
        var downY = 0f; var startY = 0
        box.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downY = e.rawY; startY = params.y; true }
                MotionEvent.ACTION_MOVE -> { params.y = startY - (e.rawY - downY).toInt(); wm.updateViewLayout(v, params); true }
                MotionEvent.ACTION_UP -> { if (abs(e.rawY - downY) < 12) o.visibility = if (o.visibility == android.view.View.VISIBLE) android.view.View.GONE else android.view.View.VISIBLE; true }
                else -> false
            }
        }
        runCatching { wm.addView(box, params) }
        bar = box; original = o; translated = t
    }

    private fun listen() {
        if (!alive) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { translated?.text = "Speech recognition isn't available on this phone."; return }
        if (sr == null) sr = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listener) }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, from)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 700L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 500L)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, offline)
        runCatching { sr?.startListening(i) }
    }

    private fun again(delay: Long = 150) { main.postDelayed({ listen() }, delay) }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onError(error: Int) { again(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) 600 else 250) }
        override fun onPartialResults(partial: Bundle?) {
            partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { original?.text = it }
        }
        override fun onResults(results: Bundle?) {
            val line = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            again()
            if (line.isBlank()) return
            original?.text = line
            val my = ++seq
            scope.launch {
                val out = if (from.startsWith("en") && to == "English" || from.startsWith("ur") && to == "Urdu") line
                else (if (offline && to != "Roman Urdu") runCatching {
                    OfflineTranslate.translate(OfflineTranslate.code(from)!!, OfflineTranslate.code(to)!!, line)
                }.getOrNull() else null) ?: runCatching {
                    Ai.generate(Background.container(this@LiveSubsService).settingsRepository,
                        "Translate this spoken line into natural $to subtitles. Reply with the translation only.\n$line",
                        system = "You are a subtitle translator. Short, natural, faithful.", maxOutputTokens = 160)
                }.getOrElse { "…" }
                if (my >= seq - 1) translated?.text = out.trim().trim('"')
            }
        }
    }

    override fun onDestroy() {
        alive = false; running = false
        main.removeCallbacksAndMessages(null)
        runCatching { sr?.destroy() }; sr = null
        bar?.let { runCatching { wm.removeView(it) } }; bar = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        @Volatile var running = false
        fun start(c: Context, from: String, to: String) = c.startForegroundService(Intent(c, LiveSubsService::class.java).putExtra("from", from).putExtra("to", to))
        fun stop(c: Context) = c.startService(Intent(c, LiveSubsService::class.java).setAction("stop"))
    }
}
