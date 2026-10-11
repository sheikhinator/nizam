package com.nizam.app.feature.future

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nizam.app.AppContainer
import com.nizam.app.net.Streaming
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Live voice: a real back-and-forth conversation. Atlas listens, starts speaking as soon as the first sentence
 * of its answer is ready, then listens again — hands-free. Tap the orb to interrupt or pause.
 */
@Composable
fun LiveVoiceScreen(container: AppContainer, compact: Boolean = false) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var phase by remember { mutableStateOf("idle") }              // idle · listening · thinking · speaking
    var partial by remember { mutableStateOf("") }
    val turns = remember { mutableStateListOf<Pair<Boolean, String>>() }   // (you?, text)
    var active by remember { mutableStateOf(false) }

    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    val recognizer = remember { if (SpeechRecognizer.isRecognitionAvailable(c)) SpeechRecognizer.createSpeechRecognizer(c) else null }
    val pending = remember { java.util.concurrent.atomic.AtomicInteger(0) }
    var streamDone by remember { mutableStateOf(true) }
    val spokenText = remember { StringBuilder() }
    var bargeOn by remember { mutableStateOf(false) }
    val bargeAllowed = remember { c.getSharedPreferences("agentic", 0).getBoolean("barge_in", true) }

    fun listen() {
        if (!active || recognizer == null) return
        phase = "listening"; partial = ""
        recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Lang.speechTag(c) ?: Locale.getDefault().toLanguageTag()))
    }

    fun speakPiece(text: String) {
        val t = tts.value ?: return
        val clean = text.replace(Regex("[*#_`>]"), "").trim(); if (clean.isBlank()) return
        t.language = if (clean.any { it in '؀'..'ۿ' }) Locale("ur", "PK") else Lang.locale(c).let { if (it.language == "ur") Locale.getDefault() else it }
        pending.incrementAndGet(); phase = "speaking"; spokenText.append(' ').append(clean.lowercase())
        t.speak(clean, TextToSpeech.QUEUE_ADD, null, "live${System.nanoTime()}")
        // talk-over: keep the ear open while Atlas speaks, so you can cut in without tapping
        if (bargeAllowed && !bargeOn && active && recognizer != null) { bargeOn = true; runCatching { recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM).putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, Lang.speechTag(c) ?: Locale.getDefault().toLanguageTag())) } }
    }

    fun think(said: String) {
        turns += true to said; phase = "thinking"; streamDone = false
        val history = turns.takeLast(10).joinToString("\n") { (me, t) -> (if (me) "Me: " else "Atlas: ") + t }
        scope.launch {
            val buf = StringBuilder(); var spokenTo = 0
            val reply = runCatching {
                Streaming.reply(container.settingsRepository, history, "You are Atlas in a live spoken conversation. Answer in 1–3 short, natural " +
                    "spoken sentences — no lists, no markdown, no emojis. Ask a short follow-up question only when it helps.", 400, context = c) { delta ->
                    buf.append(delta)
                    // speak each sentence the moment it's complete
                    val cut = buf.indexOfLast { it == '.' || it == '?' || it == '!' || it == '۔' || it == '\n' }
                    if (cut >= spokenTo) { val s = buf.substring(spokenTo, cut + 1); spokenTo = cut + 1; scope.launch { speakPiece(s) } }
                }
            }.getOrElse { "Sorry, I couldn't reach my brain just now." }
            if (spokenTo < buf.length) speakPiece(buf.substring(spokenTo)) else if (buf.isEmpty()) speakPiece(reply)
            turns += false to reply
            streamDone = true
            if (pending.get() == 0) listen()
        }
    }

    DisposableEffect(Unit) {
        val t = TextToSpeech(c) { }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { if (pending.decrementAndGet() <= 0) { pending.set(0); android.os.Handler(android.os.Looper.getMainLooper()).post { if (streamDone) listen() } } }
            @Deprecated("") override fun onError(id: String?) { onDone(id) }
        })
        tts.value = t
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(t: Int, b: Bundle?) {}
            override fun onPartialResults(b: Bundle?) {
                val txt = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                if (phase == "speaking") {
                    // only treat it as you if it is not simply Atlas's own voice coming back through the mic
                    val words = txt.lowercase().split(" ").filter { it.length > 2 }
                    val own = words.count { it in spokenText }
                    if (words.size >= 3 && own < words.size / 2) { tts.value?.stop(); pending.set(0); streamDone = true; spokenText.clear(); phase = "listening"; partial = txt }
                } else partial = txt
            }
            override fun onResults(b: Bundle?) {
                bargeOn = false
                if (phase == "speaking") return
                val said = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()
                partial = ""
                if (said.isNotBlank()) think(said) else listen()
            }
            override fun onError(e: Int) {
                // silence or a hiccup: keep listening while the conversation is on
                bargeOn = false
                if (phase == "speaking") return
                if (active && (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT)) listen()
                else if (active) { phase = "idle"; active = false }
            }
        })
        onDispose { active = false; runCatching { recognizer?.destroy() }; t.stop(); t.shutdown() }
    }

    val pulse by rememberInfiniteTransition(label = "orb").animateFloat(0.92f, 1.08f,
        infiniteRepeatable(tween(if (phase == "speaking") 380 else 900), RepeatMode.Reverse), label = "pulse")
    val colours = when (phase) {
        "listening" -> listOf(Color(0xFF4FC3A1), Color(0xFF1A73E8))
        "thinking" -> listOf(Color(0xFFB45FD6), Color(0xFF1A73E8))
        "speaking" -> listOf(Color(0xFFFFB74D), Color(0xFFE8115B))
        else -> listOf(Color(0xFF5F6368), Color(0xFF3C4043))
    }
    val listState = rememberLazyListState()
    LaunchedEffect(turns.size) { if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1) }

    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        if (!compact) Text("Live", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(top = 18.dp))
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
            items(turns) { (me, t) ->
                Text(t, style = MaterialTheme.typography.bodyLarge, textAlign = if (me) TextAlign.End else TextAlign.Start,
                    color = if (me) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }
        }
        Text(when (phase) { "listening" -> partial.ifBlank { "Listening…" }; "thinking" -> "Thinking…"; "speaking" -> "Talk over me or tap to interrupt"; else -> "Tap to start talking" },
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Box(Modifier.size(140.dp).scale(if (phase == "idle") 1f else pulse).clip(CircleShape).background(Brush.radialGradient(colours)).clickable {
            when {
                !granted -> ask.launch(Manifest.permission.RECORD_AUDIO)
                recognizer == null -> com.nizam.app.ui.components.Feedback.show("Speech recognition isn't available on this phone")
                phase == "speaking" -> { tts.value?.stop(); pending.set(0); streamDone = true; listen() }
                active -> { active = false; recognizer.stopListening(); tts.value?.stop(); phase = "idle" }
                else -> { active = true; listen() }
            }
        })
        Text(if (active) "End" else "", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(16.dp).clickable {
            active = false; recognizer?.cancel(); tts.value?.stop(); phase = "idle" })
    }
}
