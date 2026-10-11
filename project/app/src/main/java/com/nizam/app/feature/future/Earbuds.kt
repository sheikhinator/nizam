package com.nizam.app.feature.future

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.speech.tts.TextToSpeech
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Earbud mode: with earbuds in, Atlas whispers what matters — messages from people (not app spam), in your
 * language — so the phone can stay in your pocket. Music ducks while it speaks. Nothing is read aloud on the speaker.
 */
object Earbuds {
    private val MESSAGING = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.google.android.apps.messaging",
        "com.android.mms", "com.facebook.orca", "org.thoughtcrime.securesms", "com.instagram.android", "com.snapchat.android",
        "com.google.android.gm", "com.microsoft.teams", "com.slack", "com.imo.android.imoim")
    private var tts: TextToSpeech? = null
    private var lastSpoken = 0L

    private fun p(c: Context) = c.getSharedPreferences("earbuds", 0)
    fun on(c: Context) = p(c).getBoolean("on", false)
    fun set(c: Context, v: Boolean) = p(c).edit().putBoolean("on", v).apply()
    fun onlyPeople(c: Context) = p(c).getString("vip", "").orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }
    fun setVip(c: Context, v: String) = p(c).edit().putString("vip", v).apply()

    fun connected(c: Context): Boolean {
        val am = c.getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, 26 /* BLE headset */) }
    }

    fun say(c: Context, text: String) {
        val t = tts ?: TextToSpeech(c.applicationContext) { }.also { tts = it
            it.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()) }
        t.language = if (text.any { it in '؀'..'ۿ' }) Locale("ur", "PK") else Lang.locale(c).let { if (it.language == "ur") Locale.getDefault() else it }
        // give the engine a moment the first time it starts
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ t.speak(text, TextToSpeech.QUEUE_ADD, null, "ear${System.nanoTime()}") }, if (lastSpoken == 0L) 800 else 0)
        lastSpoken = System.currentTimeMillis()
    }

    /** Called for every new notification (from Atlas's notification listener). */
    fun onNotification(c: Context, app: String, pkg: String, title: String, text: String) {
        if (!on(c) || pkg !in MESSAGING || !connected(c)) return
        val am = c.getSystemService(AudioManager::class.java)
        if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION) return
        val vip = onlyPeople(c)
        if (vip.isNotEmpty() && vip.none { title.lowercase().contains(it) }) return
        if (title.contains("messages from", true) || Regex("\\d+ new messages").containsMatchIn(text)) return     // summaries, not messages
        say(c, "$app from $title: ${text.take(160)}")
    }
}

@Composable
fun EarbudsScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var on by remember { mutableStateOf(Earbuds.on(c)) }
    var vip by remember { mutableStateOf(Earbuds.onlyPeople(c).joinToString(", ")) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Earbud mode", if (Earbuds.connected(c)) "Earbuds connected" else "Put your earbuds in")
        Group {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Whisper my messages", style = MaterialTheme.typography.titleSmall)
                    Text("WhatsApp, SMS, Telegram, Gmail… read into your earbuds only — never on the speaker. Needs Atlas's notification access.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(on, { on = it; Earbuds.set(c, it); if (it) Feedback.show("Earbud mode on") })
            }
            OutlinedTextField(vip, { vip = it; Earbuds.setVip(c, it) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                label = { Text("Only these people (optional, comma-separated)") })
        }
        Label("Hands-free")
        Group {
            Text("Say “Hey Atlas” (turn on the wake word in Jarvis) or use Live voice for a real conversation through your earbuds.",
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = {
                scope.launch {
                    val brief = runCatching { Ai.generate(container.settingsRepository,
                        "Give me a spoken 20-second briefing for right now: time of day greeting, anything due today, and one encouraging line. " +
                            "No lists or markdown.\n\n${com.nizam.app.feature.agent.Snapshot.of(container).take(3000)}", maxOutputTokens = 300) }
                        .getOrElse { "I couldn't prepare a briefing just now." }
                    Earbuds.say(c, brief)
                }
            }, modifier = Modifier.padding(top = 8.dp)) { Text("Brief me now") }
        }
        Spacer(Modifier.height(50.dp))
    }
}
