package com.nizam.app.feature.jarvis

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.nizam.app.feature.agent.Converse
import com.nizam.app.feature.agent.LocalCommands
import com.nizam.app.net.Router
import kotlinx.coroutines.launch

/**
 * "Atlas, …" — hands-free. Android's own recogniser listens in short windows while the screen is on
 * (preferring the on-device model, so audio stays on the phone where supported), and only acts when a
 * phrase starts with the wake word. "Atlas, how much did I spend this week?" is answered aloud;
 * "Atlas" on its own gets "Yes?" and waits for the command.
 *
 * Honest limits: this uses more battery than a dedicated wake-word chip, pauses whenever the screen
 * is off, and some phones play a short tone each time listening restarts.
 */
class WakeService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var awaitingCommand = false
    private var speaking = false
    private var screenOn = true
    private var stopped = false

    private val screen = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            screenOn = i.action == Intent.ACTION_SCREEN_ON
            if (screenOn) listenSoon(300) else recognizer?.cancel()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop") { setOn(this, false); stopSelf(); return START_NOT_STICKY }
        if (!foreground()) { stopSelf(); return START_NOT_STICKY }
        if (recognizer == null) {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) { stopSelf(); return START_NOT_STICKY }
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply { setRecognitionListener(listener) }
            tts = TextToSpeech(this) { }.apply {
                setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) { speaking = true }
                    override fun onDone(id: String?) { speaking = false; listenSoon(250) }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { speaking = false; listenSoon(250) }
                })
            }
            val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED)
            else registerReceiver(screen, filter)
            listenSoon(200)
        }
        return START_STICKY
    }

    private fun foreground(): Boolean = runCatching {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("wake", "Listening for \"Atlas\"", NotificationManager.IMPORTANCE_MIN))
        val stop = PendingIntent.getService(this, 2, Intent(this, WakeService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "wake")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Listening for \"Atlas…\"")
            .setContentText("Say \"Atlas\" and what you need. Paused while the screen is off.")
            .addAction(Notification.Action.Builder(null, "Stop listening", stop).build())
            .setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 30) startForeground(8, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        else startForeground(8, n)
        true
    }.getOrElse { false }   // Android refuses a microphone service started from the background

    private fun listenSoon(ms: Long) {
        main.removeCallbacks(listenNow)
        main.postDelayed(listenNow, ms)
    }

    private val listenNow = Runnable {
        if (stopped || speaking || !screenOn) return@Runnable
        runCatching {
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, if (awaitingCommand) 1500L else 900L)
            })
        }.onFailure { listenSoon(2000) }
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val heard = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
            handle(heard)
        }
        override fun onError(error: Int) {
            // No speech / no match / timeout are the normal idle loop; busy means back off a little
            listenSoon(if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) 1500 else 350)
            if (awaitingCommand && (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH)) awaitingCommand = false
        }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private val WAKE = Regex("\\b(hey |ok |okay )?(atlas|atlus|atlass|atlis)\\b[,.!]?\\s*", RegexOption.IGNORE_CASE)

    private fun handle(heard: List<String>) {
        if (awaitingCommand) {
            awaitingCommand = false
            heard.firstOrNull()?.takeIf { it.isNotBlank() }?.let { run(it) } ?: listenSoon(300)
            return
        }
        val hit = heard.firstOrNull { WAKE.containsMatchIn(it) }
        if (hit == null) { listenSoon(200); return }
        val command = hit.substring(WAKE.find(hit)!!.range.last + 1).trim()
        if (command.length < 2) { awaitingCommand = true; say("Yes?") } else run(command)
    }

    private fun run(command: String) {
        val c = applicationContext
        Background.scope.launch {
            val container = Background.container(c)
            val lane = Router.classify(command, false, false,
                com.nizam.app.feature.mcp.Mcp.servers(c).filter { it.enabled }.map { it.name })
            val reply = runCatching {
                LocalCommands.tryRun(container, command)
                    ?: (if (lane == Router.Lane.CHAT) Converse.chat(container, command + "\n(Answer in two or three spoken sentences.)") { } else null)
                    ?: container.agentLoop.run(command, useWeb = false, autoApprove = false).let { out ->
                        out.reply + if (out.pendingApproval.isNotEmpty()) " I need your OK on screen for that." else ""
                    }
            }.getOrElse { "Sorry, that didn't work: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
            container.agentMemory.addTurn(true, "🎙 $command")
            container.agentMemory.addTurn(false, reply)
            Timeline.log(c, "voice", "Asked Atlas: ${command.take(80)}", reply.take(160))
            main.post { say(reply) }
        }
    }

    private fun say(text: String) {
        recognizer?.cancel()
        speaking = true
        val spoken = text.lines().filterNot { it.trimStart().startsWith("✓") || it.trimStart().startsWith("✕") }
            .joinToString(" ").replace(Regex("[*#`_]"), "").take(600)
        tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "wake") ?: run { speaking = false; listenSoon(300) }
    }

    override fun onDestroy() {
        stopped = true
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(screen) }
        runCatching { recognizer?.destroy() }
        runCatching { tts?.shutdown() }
        super.onDestroy()
    }

    companion object {
        fun isOn(c: Context) = c.getSharedPreferences("wake", 0).getBoolean("on", false)
        fun setOn(c: Context, on: Boolean) = c.getSharedPreferences("wake", 0).edit().putBoolean("on", on).apply()
        fun start(c: Context) { setOn(c, true); runCatching { c.startForegroundService(Intent(c, WakeService::class.java)) } }
        fun stop(c: Context) { setOn(c, false); c.stopService(Intent(c, WakeService::class.java)) }
    }
}
