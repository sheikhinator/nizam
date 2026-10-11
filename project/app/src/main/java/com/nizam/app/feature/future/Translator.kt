package com.nizam.app.feature.future

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.components.Thinking
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.launch
import java.util.Locale

/** Speak, and the other person hears it in their language — and back. For shops, hospitals, travel, relatives abroad. */
private val TONGUES = linkedMapOf("English" to "en-US", "Urdu" to "ur-PK", "Arabic" to "ar-SA", "Punjabi" to "pa-PK", "Pashto" to "ps-AF",
    "Sindhi" to "sd-PK", "Persian" to "fa-IR", "Turkish" to "tr-TR", "Chinese" to "zh-CN", "Hindi" to "hi-IN", "French" to "fr-FR",
    "German" to "de-DE", "Spanish" to "es-ES", "Italian" to "it-IT", "Russian" to "ru-RU", "Japanese" to "ja-JP", "Korean" to "ko-KR",
    "Malay" to "ms-MY", "Indonesian" to "id-ID", "Portuguese" to "pt-PT", "Bengali" to "bn-BD")

@Composable
fun TranslatorScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    val p = remember { c.getSharedPreferences("translator", 0) }
    var mine by remember { mutableStateOf(p.getString("mine", "Urdu")!!) }
    var theirs by remember { mutableStateOf(p.getString("theirs", "English")!!) }
    var busy by remember { mutableStateOf(false) }
    var showToThem by remember { mutableStateOf<String?>(null) }
    val log = remember { mutableStateListOf<Triple<Boolean, String, String>>() }     // (me?, said, translated)
    val tts = remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) { val t = TextToSpeech(c) { }; tts.value = t; onDispose { t.shutdown() } }
    var speaker by remember { mutableStateOf(true) }

    fun translate(me: Boolean, said: String) {
        val to = if (me) theirs else mine
        busy = true
        scope.launch {
            val out = runCatching { Ai.generate(container.settingsRepository, "Translate into $to. Natural, polite, spoken style. Reply with the translation only:\n\n$said",
                "You are an expert interpreter. Keep names and numbers exact.", 600) }.getOrElse { "(couldn't translate: ${Feedback.friendly(it)})" }.trim()
            log += Triple(me, said, out); busy = false
            if (me) showToThem = out
            if (speaker) tts.value?.let { t -> t.language = Locale.forLanguageTag(TONGUES[to] ?: "en-US"); t.speak(out, TextToSpeech.QUEUE_FLUSH, null, "tr") }
        }
    }
    val listenMine = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { translate(true, it) } }
    val listenTheirs = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { translate(false, it) } }
    fun intent(lang: String, prompt: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, TONGUES[lang]).putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)

    showToThem?.let { t ->
        // full-screen card turned toward the other person
        Box(Modifier.fillMaxSize().background(Color(0xFF0B0F1A)).clickable { showToThem = null }.padding(24.dp), contentAlignment = Alignment.Center) {
            Text(t, color = Color.White, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.rotate(180f))
            Text("Tap to close", color = Color.White.copy(alpha = 0.5f), modifier = Modifier.align(Alignment.BottomCenter))
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        ModuleTitle("Translator", "Speak — they hear it in their language")
        Text("You speak", style = MaterialTheme.typography.labelMedium)
        ChipRow(TONGUES.keys.toList(), mine, { mine = it; p.edit().putString("mine", it).apply() })
        Text("They speak", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
        ChipRow(TONGUES.keys.toList(), theirs, { theirs = it; p.edit().putString("theirs", it).apply() })
        if (log.isNotEmpty()) Text("Share transcript (${log.size})", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp).clickable {
                val t = log.joinToString("\n\n") { (me, said, out) -> (if (me) "Me ($mine): " else "Them ($theirs): ") + said + "\n→ " + out }
                c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Share transcript"))
            })
        LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
            items(log) { (me, said, out) ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (me) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant).padding(12.dp),
                    horizontalAlignment = if (me) Alignment.End else Alignment.Start) {
                    Text(said, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(out, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, modifier = Modifier.clickable { showToThem = out })
                }
            }
        }
        if (busy) Thinking(label = "Translating")
        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf(Triple("🎤 $mine", Color(0xFF1A73E8)) { runCatching { listenMine.launch(intent(mine, "Speak $mine")) }; Unit },
                Triple("🎤 $theirs", Color(0xFF0F9D58)) { runCatching { listenTheirs.launch(intent(theirs, "Speak $theirs")) }; Unit }).forEach { (label, col, go) ->
                Box(Modifier.weight(1f).height(64.dp).clip(RoundedCornerShape(18.dp)).background(col).clickable(enabled = !busy) { go() }, contentAlignment = Alignment.Center) {
                    Text(label, color = Color.White, style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        Text(if (speaker) "🔊 Speaking translations aloud (tap to mute)" else "🔈 Muted", style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clickable { speaker = !speaker }.padding(bottom = 12.dp))
    }
}
