package com.nizam.app.feature.voice

import android.content.Context
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/**
 * Live dictation, offline when the phone supports it: tap to listen, speak, see words land as you go.
 * Android's own speech engine does the work, so there is nothing to download — it mirrors the same
 * "prefer offline" choice already used for the system mic popup.
 */
object Dictate {
    fun available(c: Context) = SpeechRecognizer.isRecognitionAvailable(c)
    fun hasMic(c: Context) = ContextCompat.checkSelfPermission(c, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}

/** A mic button: tap to start listening, tap again (or it auto-stops on silence) to finish. [onResult] gets the heard text, called as it improves. */
@Composable
fun DictateButton(onPartial: (String) -> Unit = {}, onDone: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    var recognizer by remember { mutableStateOf<SpeechRecognizer?>(null) }

    DisposableEffect(Unit) { onDispose { recognizer?.destroy() } }

    fun start() {
        if (!Dictate.available(c) || !Dictate.hasMic(c)) { com.nizam.app.ui.components.Feedback.show("No microphone access or speech engine on this phone"); return }
        val r = SpeechRecognizer.createSpeechRecognizer(c)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(code: Int) { listening = false }
            override fun onResults(b: android.os.Bundle) {
                listening = false
                b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onDone(it) }
            }
            override fun onPartialResults(b: android.os.Bundle) {
                b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { onPartial(it) }
            }
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        val intent = android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, com.nizam.app.feature.future.Lang.speechTag() ?: java.util.Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            if (com.nizam.app.feature.nx.OfflineVoice.prefer(c)) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        runCatching { r.startListening(intent) }.onFailure { listening = false }
        listening = true
    }
    fun stop() { recognizer?.stopListening(); listening = false }

    IconButton(onClick = { if (listening) stop() else start() }, modifier = modifier) {
        Icon(if (listening) Icons.Outlined.Stop else Icons.Outlined.Mic, if (listening) "Stop listening" else "Dictate",
            tint = if (listening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}


/** A small speaker icon: tap to hear [text] read aloud with the phone's own voice, tap again to stop. */
@Composable
fun SpeakButton(text: String, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val speaker = remember { Speaker(c) }
    var speaking by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { speaker.release() } }
    IconButton(onClick = {
        if (speaking) { speaker.stop(); speaking = false } else { speaker.speak(text); speaking = true }
    }, modifier = modifier, enabled = text.isNotBlank()) {
        Icon(if (speaking) Icons.Outlined.Stop else Icons.Outlined.VolumeUp, if (speaking) "Stop reading" else "Read aloud",
            tint = if (speaking) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}
