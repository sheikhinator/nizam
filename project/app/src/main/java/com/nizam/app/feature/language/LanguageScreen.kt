package com.nizam.app.feature.language

import android.app.Activity
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.council.TypingDots
import com.nizam.app.feature.council.UserBubble
import com.nizam.app.feature.voice.Speaker
import com.nizam.app.feature.voice.speechIntent
import com.nizam.app.net.Ai
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.Locale

private val LANGS = linkedMapOf(
    "Arabic" to "ar", "Chinese" to "zh-CN", "English" to "en-US", "Turkish" to "tr-TR",
    "French" to "fr-FR", "Spanish" to "es-ES", "German" to "de-DE", "Urdu" to "ur-PK"
)

private data class Turn(val fromUser: Boolean, val text: String, val translation: String = "", val correction: String = "")

/** Conversation practice: it talks back in the language, corrects you gently, and banks new words. */
@Composable
fun LanguageScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var lang by remember { mutableStateOf("Arabic") }
    var level by remember { mutableStateOf("Beginner") }
    val turns = remember { mutableStateListOf<Turn>() }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(0) }
    val speaker = remember { Speaker(context) }
    DisposableEffect(Unit) { onDispose { speaker.release() } }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK)
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { draft = it }
    }

    fun send() {
        val text = draft.trim(); if (text.isBlank() || busy) return
        turns += Turn(true, text); draft = ""
        scope.launch {
            busy = true
            runCatching {
                val raw = Ai.generate(
                    settings = container.settingsRepository,
                    prompt = "Conversation so far:\n" + turns.takeLast(10).joinToString("\n") {
                        (if (it.fromUser) "Learner: " else "You: ") + it.text } +
                        "\n\nReply as a friendly conversation partner in $lang at $level level. Keep it to 1-3 " +
                        "short sentences and end with a question that keeps them talking. If the learner's last " +
                        "message had a mistake, give the corrected sentence and a one-line reason in English; " +
                        "otherwise leave correction empty. List up to 3 useful new words from YOUR reply.\n" +
                        "Return ONLY JSON: {\"reply\":\"...\",\"translation\":\"English\",\"correction\":\"...\"," +
                        "\"words\":[{\"word\":\"...\",\"meaning\":\"...\"}]}",
                    system = "You are a patient native-speaker tutor. You return only valid JSON.",
                    maxOutputTokens = 900
                )
                val o = JSONObject(Ai.cleanJson(raw))
                turns += Turn(false, o.optString("reply"), o.optString("translation"), o.optString("correction"))
                speaker.speak(o.optString("reply"), Locale.forLanguageTag(LANGS.getValue(lang)))
                // new words go into the Vocabulary module, where they get charts and review
                val vocab = container.dynamicRepository.spec("vocab")
                val meaningKey = vocab?.fields?.firstOrNull { it.type == "TEXT" || it.type == "LONGTEXT" }?.key
                o.optJSONArray("words")?.let { a ->
                    for (i in 0 until a.length()) {
                        val w = a.getJSONObject(i)
                        if (vocab != null && meaningKey != null && w.optString("word").isNotBlank()) {
                            container.dynamicRepository.addEntry("vocab", w.optString("word"),
                                mapOf(meaningKey to "${w.optString("meaning")} ($lang)"))
                            saved++
                        }
                    }
                }
            }.onFailure { turns += Turn(false, "Couldn't reply: ${com.nizam.app.ui.components.Feedback.friendly(it)}") }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 18.dp)) {
        ModuleTitle("Language partner", "Speak, get gently corrected, and bank new words")
        ChipRow(LANGS.keys.toList(), lang, { lang = it; turns.clear() })
        ChipRow(listOf("Beginner", "Intermediate", "Advanced"), level, { level = it })
        if (saved > 0) Text("$saved new words saved to Vocabulary", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary)
        LazyColumn(Modifier.weight(1f)) {
            if (turns.isEmpty()) item {
                Text("Say anything — type or tap the mic and speak in $lang. It answers aloud.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp))
            }
            items(turns) { t ->
                if (t.fromUser) UserBubble(t.text)
                else Column(
                    Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp))
                        .clickable(role = Role.Button) { speaker.speak(t.text, Locale.forLanguageTag(LANGS.getValue(lang))) }
                        .padding(14.dp)
                ) {
                    Text(t.text, style = MaterialTheme.typography.titleMedium)
                    if (t.translation.isNotBlank()) Text(t.translation, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (t.correction.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text("✎ ${t.correction}", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (busy) item { Box(Modifier.padding(12.dp)) { TypingDots(MaterialTheme.colorScheme.primary) } }
        }
        Row(
            Modifier.fillMaxWidth().padding(vertical = 10.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp)).padding(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                .clickable(role = Role.Button) { mic.launch(speechIntent(LANGS.getValue(lang))) },
                contentAlignment = Alignment.Center) { Text("🎙") }
            Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                if (draft.isEmpty()) Text("Speak or type in $lang…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                BasicTextField(draft, { draft = it }, maxLines = 4,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), modifier = Modifier.fillMaxWidth())
            }
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                .clickable(role = Role.Button) { send() }, contentAlignment = Alignment.Center) {
                Text("➤", color = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}
