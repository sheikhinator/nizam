package com.nizam.app.feature.attention

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
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
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.phone.AtlasNotificationListener
import com.nizam.app.feature.shield.Shield
import com.nizam.app.net.Ai
import kotlinx.coroutines.launch

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) { content() } }
}

@Composable
fun AttentionScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var digest by remember { mutableStateOf(Attention.digestOn(c)) }
    var held by remember { mutableStateOf(Attention.heldApps(c)) }
    var wind by remember { mutableStateOf(Attention.windDownHour(c)) }
    var eyes by remember { mutableStateOf(Attention.eyeBreaks(c)) }
    val apps = remember { Shield.apps(c) }
    var showApps by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Attention", "Fewer pings, better replies, a real end to the day")
        if (!AtlasNotificationListener.connected) Text("Digest and smart replies need notification access (Me > Phone control).",
            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(digest, { digest = it; Attention.setDigest(c, it) }); Spacer(Modifier.width(10.dp))
                Text("Notification digest", style = MaterialTheme.typography.titleMedium)
            }
            Text("Notifications from the apps you pick are held silently and delivered as one summary at 1pm, 6pm and 9pm. " +
                "Everything else — calls, family — comes through as normal.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { showApps = !showApps }) { Text("Apps to hold (${held.size})") }
            if (showApps) apps.forEach { (label, pkg) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(pkg in held, { on -> held = if (on) held + pkg else held - pkg; Attention.setHeldApps(c, held) })
                    Text(label)
                }
            }
        }

        Card {
            Text("Wind-down", style = MaterialTheme.typography.titleMedium)
            Text("At this hour: Do Not Disturb on and your focus-shield apps blocked for 2 hours.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChipRow(listOf("Off", "21", "22", "23", "0"), if (wind < 0) "Off" else "$wind",
                { wind = if (it == "Off") -1 else it.toInt(); Attention.setWindDown(c, wind) },
                labels = { if (it == "Off") "Off" else "$it:00" })
        }

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(eyes, { eyes = it; Attention.setEyeBreaks(c, it) }); Spacer(Modifier.width(10.dp))
                Text("Eye breaks", style = MaterialTheme.typography.titleMedium)
            }
            Text("After 20 minutes of continuous screen use: look at something 20 feet away for 20 seconds, and stand up.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Text("SMART REPLIES", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp))
        val replyable = AtlasNotificationListener.recent(20).filter { it.canReply }
        if (replyable.isEmpty()) Text("No messages waiting for a reply.", style = MaterialTheme.typography.bodyMedium)
        replyable.forEach { n ->
            var draft by remember(n.key) { mutableStateOf("") }
            var sent by remember(n.key) { mutableStateOf<String?>(null) }
            Card {
                Text("${n.app} · ${n.title}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(n.text, style = MaterialTheme.typography.bodyMedium)
                if (draft.isNotBlank()) OutlinedTextField(draft, { draft = it }, modifier = Modifier.fillMaxWidth())
                Row {
                    OutlinedButton(onClick = {
                        scope.launch {
                            draft = runCatching {
                                Ai.generate(container.settingsRepository,
                                    "Draft a short reply in the user's own voice to this message from ${n.title}: \"${n.text}\". " +
                                        "Match the language and tone of the message. Reply text only, no quotes.", maxOutputTokens = 200).trim()
                            }.getOrElse { "" }
                        }
                    }) { Text(if (draft.isBlank()) "Draft reply" else "Redraft") }
                    Spacer(Modifier.width(8.dp))
                    if (draft.isNotBlank()) Button(onClick = { sent = AtlasNotificationListener.reply(c, n.title, draft) }) { Text("Send") }
                }
                sent?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            }
        }
        Spacer(Modifier.height(60.dp))
    }
}
