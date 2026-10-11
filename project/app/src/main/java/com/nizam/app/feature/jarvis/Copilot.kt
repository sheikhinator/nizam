package com.nizam.app.feature.jarvis

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.TileService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nizam.app.feature.agent.Converse
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.feature.phone.AtlasAccessibilityService
import com.nizam.app.ui.components.ChatText
import com.nizam.app.ui.components.Composer
import com.nizam.app.ui.components.Thinking
import com.nizam.app.ui.theme.NizamTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Screen co-pilot. Summoned over any app (Quick Settings tile, or long-press the floating bubble),
 * it reads what's on screen and either answers about it right here, or closes and works the app
 * itself — drafting a reply into WhatsApp, filling a form from your profile, pulling dates into
 * your calendar.
 *
 * It never presses Send on your behalf unless you say "send". Banking, payment and password apps
 * are never read or touched.
 */
class CopilotActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = AtlasAccessibilityService.instance
        val (app, screen) = if (service == null) "" to "" else service.readUnderlying()
        val container = Background.container(this)
        setContent {
            val palette by container.settingsRepository.accentHex.collectAsState(initial = "")
            val custom by container.settingsRepository.customPalette.collectAsState(initial = "")
            NizamTheme(paletteKey = palette, customPaletteJson = custom) {
                Box(Modifier.fillMaxSize().clickable(role = Role.Button) { finish() }, contentAlignment = Alignment.BottomCenter) {
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .clickable(enabled = false) {}
                            .navigationBarsPadding().imePadding()
                            .padding(horizontal = 18.dp, vertical = 14.dp)
                    ) {
                        if (service == null) NotOn() else Sheet(app, screen)
                    }
                }
            }
        }
    }

    @Composable
    private fun NotOn() {
        Text("Co-pilot needs screen control", style = MaterialTheme.typography.titleMedium)
        Text("Turn on Atlas in Settings → Accessibility → Installed apps, and switch on screen control in Atlas.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp))
        Text("Open accessibility settings", color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(role = Role.Button) {
                startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                finish()
            }.padding(vertical = 8.dp))
    }

    @Composable
    private fun Sheet(app: String, screen: String) {
        val container = Background.container(this)
        var draft by remember { mutableStateOf("") }
        var answer by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
        val context = "ON THEIR SCREEN RIGHT NOW (app: $app):\n$screen"

        fun act(instruction: String) {
            // The sheet must close first so taps and typing land in the app underneath
            finish()
            Background.scope.launch {
                delay(700)
                val c = applicationContext
                val out = runCatching {
                    container.agentLoop.run(
                        "You are operating $app, which is open on their screen. $context\n\n" +
                            "Their instruction: $instruction\n\nUse read_screen, tap, type_text and scroll to do it inside $app. " +
                            "Read the screen again after each step. Type drafts into the message box but do NOT press send " +
                            "unless they explicitly said to send.",
                        useWeb = false, autoApprove = true
                    ).reply
                }.getOrElse { "Couldn't finish: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
                Notifications.post(c, Notifications.CHANNEL_NUDGE, 480, "Co-pilot · $app", out.take(400))
                Timeline.log(c, "copilot", "Co-pilot in $app: ${instruction.take(60)}", out.take(160))
            }
        }

        fun ask(text: String) {
            if (text.isBlank() || busy) return
            draft = ""; answer = ""; busy = true
            scope.launch {
                val chat = runCatching { Converse.chat(container, text, extraContext = context) { answer += it } }
                    .getOrElse { answer = "Couldn't answer: ${com.nizam.app.ui.components.Feedback.friendly(it)}"; ""  }
                busy = false
                if (chat == null) act(text)     // it needs doing, not answering
            }
        }

        Text("Co-pilot" + if (app.isNotBlank()) " · $app" else "", style = MaterialTheme.typography.titleMedium)
        Text(if (screen.isBlank()) "Nothing readable on screen" else "${screen.lines().size} things on screen",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "Summarise" to { ask("Summarise what's on my screen in a few lines.") },
                "Reply for me" to { act("Write a reply to this conversation in my usual tone and type it into the message box.") },
                "Tasks & dates" to { act("Pull every task, deadline and date from this screen into my tasks and calendar.") },
                "Translate" to { ask("Translate what's on my screen into English (or Urdu if it's already English).") },
                "Explain" to { ask("Explain what I'm looking at and what I should do next.") },
                "Scam check" to { ask("Is this a scam or fraud attempt? Give a verdict and the red flags.") }
            ).forEach { (label, go) ->
                Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                        .clickable(role = Role.Button) { go() }
                        .padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }
        if (answer.isNotBlank() || busy) {
            Column(Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (answer.isNotBlank()) ChatText(answer) else Thinking(label = "Looking at your screen")
            }
            Spacer(Modifier.height(6.dp))
        }
        Composer(
            draft = draft, onDraft = { draft = it }, attachments = emptyList(), onAttachments = {},
            busy = busy, placeholder = "Ask or tell Atlas about this screen…",
            onSend = { ask(draft) }, showConnections = false
        )
    }

    companion object {
        fun open(c: Context) = c.startActivity(Intent(c, CopilotActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
    }
}

/** Quick Settings tile: pull down, tap "Atlas co-pilot", and it opens over whatever you're doing. */
class CopilotTile : TileService() {
    override fun onClick() {
        val intent = Intent(this, CopilotActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
