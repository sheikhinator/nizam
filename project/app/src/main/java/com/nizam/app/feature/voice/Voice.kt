package com.nizam.app.feature.voice

import android.content.Context
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Voice both ways, using Android's own engines — no dependency, no network, no cost.
 */
class Speaker(context: Context) {
    private val ctx = context.applicationContext
    private var ready = false
    private val tts = TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
    }

    fun speak(text: String, locale: Locale? = null) {
        if (!ready || text.isBlank()) return
        runCatching {
            // Urdu script gets the Urdu voice whatever the setting; English stays English
            tts.language = locale ?: if (text.any { it in '\u0600'..'\u06FF' }) Locale("ur", "PK") else com.nizam.app.feature.future.Lang.locale(ctx).let {
                if (it.language == "ur") Locale.getDefault() else it }
            // Strip the action receipts — reading "✓ Task added" aloud is noise.
            val spoken = text.lines()
                .filterNot { it.trimStart().startsWith("✓") || it.trimStart().startsWith("✕") }
                .joinToString(" ")
                .take(1200)
            tts.speak(spoken, TextToSpeech.QUEUE_FLUSH, null, "atlas")
        }
    }

    fun stop() = runCatching { tts.stop() }
    fun release() = runCatching { tts.stop(); tts.shutdown() }
}

fun speechIntent(languageTag: String? = null): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag ?: com.nizam.app.feature.future.Lang.speechTag() ?: Locale.getDefault().toLanguageTag())
    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Atlas")
    com.nizam.app.net.Router.appContext?.let { if (com.nizam.app.feature.nx.OfflineVoice.prefer(it)) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true) }
}
