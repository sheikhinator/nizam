package com.nizam.app.feature.presence

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.phone.AtlasNotificationListener
import com.nizam.app.feature.phone.PhoneActions
import com.nizam.app.feature.phone.PhoneActions2
import com.nizam.app.feature.voice.Speaker
import com.nizam.app.feature.voice.speechIntent
import com.nizam.app.net.Ai
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) { content() } }
}

// ── Voice hub: bubble toggle + the three voice tools ─────────────────────────
@Composable
fun VoiceHubScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val c = LocalContext.current
    var status by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Voice & presence", "Atlas from anywhere, hands-free when you need it")
        Card {
            Text("Floating bubble", style = MaterialTheme.typography.titleMedium)
            Text("A small ✦ that floats over every app. Tap it to talk to Atlas; drag it to the bottom to dismiss.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (!Settings.canDrawOverlays(c)) {
                        c.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        status = "Allow “display over other apps” for Atlas, then tap Show again."
                    } else { BubbleService.start(c); status = "Bubble on." }
                }) { Text("Show bubble") }
                OutlinedButton(onClick = { BubbleService.stop(c); status = "Bubble off." }) { Text("Hide") }
            }
            status?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
        }
        Card {
            Text("\"Hey Atlas\"", style = MaterialTheme.typography.titleMedium)
            Text("Set Atlas as your default assistant (Me > Phone control) and long-press the power button — the Curator " +
                "opens ready to listen, from any screen, even the lock screen.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        listOf(
            Triple("🚗", "Driving mode", "drive"), Triple("📓", "Voice journal", "voicejournal"),
            Triple("🎤", "Rehearsal coach", "rehearse")
        ).forEach { (e, name, route) ->
            Card { Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(route) }, verticalAlignment = Alignment.CenterVertically) {
                Text("$e  $name", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f)); Text("Open", color = MaterialTheme.colorScheme.primary)
            } }
        }
        Spacer(Modifier.height(60.dp))
    }
}

// ── Driving mode: huge targets, voice out, nothing to read ───────────────────
@Composable
fun DrivingScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    val speaker = remember { Speaker(c) }
    // Only touch DND if access was already granted — never bounce a driver into Settings
    val dndOk = remember { c.getSystemService(android.app.NotificationManager::class.java).isNotificationPolicyAccessGranted }
    DisposableEffect(Unit) {
        if (dndOk) PhoneActions.doNotDisturb(c, true)
        onDispose { speaker.release(); if (dndOk) PhoneActions.doNotDisturb(c, false) }
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && said != null) scope.launch {
            val out = runCatching { container.agentLoop.run(said, useWeb = false, autoApprove = false) }.getOrNull()
            speaker.speak(out?.reply ?: "Sorry, I couldn't do that.")
        }
    }
    @Composable
    fun Big(label: String, colour: Color, modifier: Modifier, onClick: () -> Unit) {
        Box(modifier.padding(6.dp).background(colour, RoundedCornerShape(24.dp)).clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center) {
            Text(label, fontSize = 22.sp, color = Color(0xFF0B0F1A), textAlign = TextAlign.Center)
        }
    }
    Column(Modifier.fillMaxSize().background(Color.Black).padding(10.dp)) {
        Text("DRIVING", color = Color(0xFF8C96AC), style = MaterialTheme.typography.labelMedium.copy(fontFamily = com.nizam.app.ui.theme.MonoFamily),
            modifier = Modifier.padding(8.dp))
        Big("🎙\nSpeak", Color(0xFF4FC3A1), Modifier.fillMaxWidth().weight(1.3f)) { mic.launch(speechIntent()) }
        Row(Modifier.weight(1f)) {
            Big("⏯\nMusic", Color(0xFF9B8CD6), Modifier.weight(1f).fillMaxSize()) { PhoneActions2.media(c, "toggle") }
            Big("⏭\nNext", Color(0xFF58A6C9), Modifier.weight(1f).fillMaxSize()) { PhoneActions2.media(c, "next") }
        }
        Row(Modifier.weight(1f)) {
            Big("🔔\nRead messages", Color(0xFFE0B14A), Modifier.weight(1f).fillMaxSize()) {
                val n = AtlasNotificationListener.recent(5)
                speaker.speak(if (!AtlasNotificationListener.connected) "Notification access is off."
                    else if (n.isEmpty()) "No new notifications."
                    else n.joinToString(". ") { "${it.app}: ${it.title}, ${it.text.take(120)}" })
            }
            Big("🏠\nHome", Color(0xFFE08AA8), Modifier.weight(1f).fillMaxSize()) { PhoneActions.navigate(c, "home", "drive") }
        }
        Text(if (dndOk) "Do Not Disturb is on while this screen is open." else "Grant Do Not Disturb access in Phone control to silence calls while driving.", color = Color(0xFF8C96AC),
            style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp))
    }
}

// ── Voice journal: speak freely, get a filed entry and extracted tasks ────────
@Composable
fun VoiceJournalScreen(container: AppContainer) {
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var transcript by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
            if (r.resultCode == Activity.RESULT_OK) transcript = (transcript + " " + it).trim()
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Voice journal", "Talk it out. Atlas files the entry and pulls out your to-dos")
        Button(onClick = { mic.launch(speechIntent()) }, modifier = Modifier.fillMaxWidth().height(64.dp)) {
            Text(if (transcript.isBlank()) "🎙  Start talking" else "🎙  Keep talking")
        }
        Text("Each tap records until you pause. Tap again to add more.", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (transcript.isNotBlank()) {
            OutlinedTextField(transcript, { transcript = it }, label = { Text("What you said") }, modifier = Modifier.fillMaxWidth())
            Button(enabled = !busy, onClick = {
                scope.launch {
                    busy = true
                    runCatching {
                        val raw = Ai.generate(container.settingsRepository,
                            "This is a spoken journal entry, transcribed. Clean it into readable first-person prose " +
                                "(fix transcription errors, keep their words and meaning). Rate their mood 1-5. " +
                                "Name up to 3 emotions. Extract concrete to-dos they mentioned, if any.\n" +
                                "Return ONLY JSON: {\"entry\":\"...\",\"mood\":3,\"emotions\":[\"...\"],\"tasks\":[\"...\"]}\n\n$transcript",
                            maxOutputTokens = 1500)
                        val o = JSONObject(Ai.cleanJson(raw))
                        val emotions = o.optJSONArray("emotions")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
                        container.diaryRepository.addEntry(o.optString("entry", transcript), o.optInt("mood", 3).coerceIn(1, 5), emotions)
                        val tasks = o.optJSONArray("tasks")?.let { a -> (0 until a.length()).map { a.optString(it) } }?.filter { it.isNotBlank() } ?: emptyList()
                        tasks.forEach { container.taskRepository.add(it, null, 1) }
                        result = "Saved to Diary · mood ${o.optInt("mood", 3)}/5" +
                            (if (emotions.isNotEmpty()) " · ${emotions.joinToString()}" else "") +
                            (if (tasks.isNotEmpty()) "\nTasks added: ${tasks.joinToString("; ")}" else "")
                        transcript = ""
                    }.onFailure { result = "Couldn't file it: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
                    busy = false
                }
            }) { Text(if (busy) "Filing…" else "File this entry") }
        }
        result?.let { Card { Text(it) } }
        Spacer(Modifier.height(60.dp))
    }
}

// ── Rehearsal coach: pace, fillers, structure ─────────────────────────────────
private val FILLERS = listOf("um", "uh", "erm", "like", "basically", "actually", "literally", "you know", "i mean", "sort of", "kind of", "so yeah")

@Composable
fun RehearsalScreen(container: AppContainer) {
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var topic by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(0L) }
    var transcript by remember { mutableStateOf("") }
    var seconds by remember { mutableStateOf(0L) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (r.resultCode == Activity.RESULT_OK && said != null) {
            transcript = (transcript + " " + said).trim()
            seconds += ((System.currentTimeMillis() - started) / 1000).coerceAtLeast(1)
        }
    }
    val words = transcript.split(Regex("\\s+")).filter { it.isNotBlank() }
    val lower = " " + transcript.lowercase() + " "
    val fillerCounts = FILLERS.associateWith { f -> Regex("\\b${Regex.escape(f)}\\b").findAll(lower).count() }.filterValues { it > 0 }
    val wpm = if (seconds > 0) words.size * 60 / seconds else 0

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Rehearsal coach", "Practise out loud — pace, fillers and structure")
        OutlinedTextField(topic, { topic = it }, label = { Text("What are you rehearsing? (interview answer, presentation…)") },
            modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(onClick = { started = System.currentTimeMillis(); mic.launch(speechIntent()) }, modifier = Modifier.fillMaxWidth()) {
            Text(if (transcript.isBlank()) "🎤  Start speaking" else "🎤  Continue")
        }
        if (transcript.isNotBlank()) {
            Card {
                Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                    Column { Text("$wpm", style = MonoNumber); Text("words/min", style = MaterialTheme.typography.labelMedium) }
                    Column { Text("${words.size}", style = MonoNumber); Text("words", style = MaterialTheme.typography.labelMedium) }
                    Column { Text("${fillerCounts.values.sum()}", style = MonoNumber); Text("fillers", style = MaterialTheme.typography.labelMedium) }
                }
                Text(when {
                    wpm == 0L -> ""
                    wpm < 110 -> "A little slow — aim for 130–160 wpm."
                    wpm > 170 -> "Fast — slow down so points land. Aim for 130–160 wpm."
                    else -> "Good pace."
                }, color = MaterialTheme.colorScheme.primary)
                if (fillerCounts.isNotEmpty()) Text("Fillers: " + fillerCounts.entries.joinToString { "\"${it.key}\" ×${it.value}" },
                    style = MaterialTheme.typography.bodyMedium)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        feedback = runCatching {
                            Ai.generate(container.settingsRepository,
                                "Coach this spoken rehearsal${if (topic.isNotBlank()) " for: $topic" else ""}. Pace was $wpm wpm, " +
                                    "${fillerCounts.values.sum()} filler words. Give: 1) the strongest line, 2) the structure as they " +
                                    "delivered it vs a tighter structure, 3) three specific fixes, 4) a rewritten 2-sentence opening. " +
                                    "Be direct and concrete.\n\nTranscript:\n$transcript", maxOutputTokens = 1500)
                        }.getOrElse { "Couldn't get feedback: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
                        busy = false
                    }
                }) { Text(if (busy) "Listening back…" else "Get coaching") }
                OutlinedButton(onClick = { transcript = ""; seconds = 0; feedback = null }) { Text("Start over") }
            }
            feedback?.let { Card { Text(it) } }
        }
        Spacer(Modifier.height(60.dp))
    }
}
