package com.nizam.app.feature.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.nizam.app.AppContainer
import com.nizam.app.ui.components.Feedback
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.sin

/**
 * The command bar on Today: tap the mic and say what you want ("remind me to call Ammi at 5", "log 2 glasses of water",
 * "open prayer times", "how much did I spend this week") — or type it. Atlas runs it through the same agent as chat,
 * reads the answer back, and anything risky waits for a tap.
 */
@Composable
fun VoiceCommandBar(container: AppContainer, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf("") }
    var listening by remember { mutableStateOf(false) }
    var partial by remember { mutableStateOf("") }
    var level by remember { mutableFloatStateOf(0f) }
    var busy by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf<String?>(null) }
    var steps by remember { mutableStateOf<List<AgentLoop.Step>>(emptyList()) }
    var pending by remember { mutableStateOf<List<Pair<String, JSONObject>>>(emptyList()) }
    var speakBack by remember { mutableStateOf(true) }
    val speaker = remember { com.nizam.app.feature.voice.Speaker(c) }
    val recognizer = remember { if (SpeechRecognizer.isRecognitionAvailable(c)) SpeechRecognizer.createSpeechRecognizer(c) else null }
    DisposableEffect(Unit) { onDispose { recognizer?.destroy(); speaker.release() } }

    fun send(cmd: String) {
        val q = cmd.trim(); if (q.isBlank() || busy) return
        text = ""; partial = ""; focus.clearFocus(); busy = true; reply = null; steps = emptyList(); pending = emptyList()
        com.nizam.app.ui.design.Island.agentBusy = q.take(28)
        scope.launch {
            runCatching { container.agentMemory.addTurn(true, q) }
            val out = runCatching { container.agentLoop.run(q, useWeb = false, autoApprove = false) }
                .getOrElse { AgentLoop.Outcome("That didn't work: ${Feedback.friendly(it)}", emptyList()) }
            reply = out.reply; steps = out.steps; pending = out.pendingApproval
            runCatching { container.agentMemory.addTurn(false, out.reply + if (out.steps.isEmpty()) "" else "\n\n" +
                out.steps.joinToString("\n") { (if (it.ok) "✓ " else "✕ ") + it.summary }) }
            if (speakBack) speaker.speak(out.reply)
            busy = false; com.nizam.app.ui.design.Island.agentBusy = null
        }
    }

    // the system dialog is the fallback when on-device recognition isn't available
    val dialog = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { send(it) }
    }
    fun listen() {
        speaker.stop()
        val rec = recognizer
        if (rec == null) { runCatching { dialog.launch(com.nizam.app.feature.voice.speechIntent()) }.onFailure { Feedback.show("No speech recogniser on this phone") }; return }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { listening = true; partial = "" }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) { level = ((rms + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { listening = false }
            override fun onError(e: Int) {
                listening = false; level = 0f
                if (e == SpeechRecognizer.ERROR_NO_MATCH || e == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) Feedback.show("Didn't catch that — tap and try again")
                else if (e == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) Feedback.show("Atlas needs the microphone permission")
            }
            override fun onResults(b: Bundle?) {
                listening = false; level = 0f
                b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { send(it) }
            }
            override fun onPartialResults(b: Bundle?) { b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { partial = it } }
            override fun onEvent(t: Int, b: Bundle?) {}
        })
        val intent = com.nizam.app.feature.voice.speechIntent().putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.packageName)
        runCatching { rec.startListening(intent) }.onFailure { Feedback.failed("Listening", it) }
    }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) listen() else Feedback.show("Atlas needs the microphone to hear commands")
    }
    fun tapMic() {
        com.nizam.app.ui.design.UiSound.tick()
        if (listening) { recognizer?.stopListening(); listening = false; return }
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) listen()
        else mic.launch(Manifest.permission.RECORD_AUDIO)
    }

    val shape = RoundedCornerShape(28.dp)
    val brand = Brush.linearGradient(listOf(cs.primary, cs.tertiary))
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().shadow(if (listening) 14.dp else 4.dp, shape, ambientColor = cs.primary, spotColor = cs.primary).clip(shape)
            .background(cs.surface).border(1.dp, if (listening) brand else Brush.linearGradient(listOf(cs.outline.copy(alpha = 0.25f), cs.outline.copy(alpha = 0.25f))), shape)
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                when {
                    listening -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Wave(level, Modifier.width(46.dp).height(22.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(partial.ifBlank { "Listening…" }, style = MaterialTheme.typography.bodyLarge, maxLines = 2,
                            color = if (partial.isBlank()) cs.onSurfaceVariant else cs.onSurface)
                    }
                    busy -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp))
                        Text("Atlas is on it…", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                    }
                    else -> {
                        if (text.isEmpty()) Text("Tell Atlas what to do…", style = MaterialTheme.typography.bodyLarge, color = cs.onSurfaceVariant)
                        BasicTextField(text, { text = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface),
                            cursorBrush = SolidColor(cs.primary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send(text) }), modifier = Modifier.fillMaxWidth())
                    }
                }
            }
            if (text.isNotBlank() && !listening) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(cs.onSurface.copy(alpha = 0.08f)).clickable(role = Role.Button) { send(text) },
                    contentAlignment = Alignment.Center) { Icon(Icons.Outlined.ArrowUpward, "Send", tint = cs.onSurface) }
                Spacer(Modifier.width(6.dp))
            }
            MicOrb(listening, level) { tapMic() }
        }
        if (!listening && !busy && reply == null && text.isEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Log 2 glasses of water", "What's my next prayer?", "Add task: pay the bill", "How much did I spend today?", "Play a podcast", "Open weather")
                .forEach { s -> Text(s, style = MaterialTheme.typography.labelMedium, color = cs.primary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(cs.primary.copy(alpha = 0.08f)).clickable(role = Role.Button) { send(s) }
                        .padding(horizontal = 12.dp, vertical = 6.dp)) }
        }
        AnimatedVisibility(reply != null) {
            Column(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(cs.primary.copy(alpha = 0.07f)).padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.nizam.app.ui.design.Art3D("robot", 28.dp, float = false, shadow = false)
                    Text("Atlas", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.padding(start = 6.dp).weight(1f))
                    Text(if (speakBack) "🔊" else "🔇", modifier = Modifier.clip(CircleShape).clickable { speakBack = !speakBack; if (!speakBack) speaker.stop() }.padding(6.dp))
                    Icon(Icons.Outlined.Close, "Dismiss", Modifier.size(20.dp).clip(CircleShape).clickable { reply = null; steps = emptyList(); pending = emptyList(); speaker.stop() },
                        tint = cs.onSurfaceVariant)
                }
                Text(reply.orEmpty(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp), maxLines = 12)
                steps.forEach { st -> Text((if (st.ok) "✓ " else "✕ ") + st.summary, style = MaterialTheme.typography.labelMedium,
                    color = if (st.ok) Color(0xFF16A34A) else cs.error, modifier = Modifier.padding(top = 3.dp)) }
                if (pending.isNotEmpty()) {
                    Text("Needs your OK:", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    pending.forEach { (name, args) ->
                        val detail = args.keys().asSequence().joinToString(", ") { k -> "$k: ${args.optString(k).take(50)}" }
                        Text("• " + name.replace('_', ' ') + if (detail.isNotBlank()) " · $detail" else "", style = MaterialTheme.typography.labelMedium,
                            color = cs.onSurfaceVariant)
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Approve", color = Color.White, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clip(RoundedCornerShape(50)).background(brand)
                            .clickable(role = Role.Button) {
                                val todo = pending; pending = emptyList()
                                scope.launch {
                                    val done = todo.map { (n, a) -> runCatching { Actions.run(container, n, a) }.getOrElse { Actions.Result(false, Feedback.friendly(it)) } }
                                    steps = steps + done.map { AgentLoop.Step("approved", it.summary, it.ok) }
                                    runCatching { container.agentMemory.addTurn(false, done.joinToString("\n") { (if (it.ok) "✓ " else "✕ ") + it.summary }) }
                                }
                            }.padding(horizontal = 16.dp, vertical = 8.dp))
                        Text("Cancel", style = MaterialTheme.typography.labelLarge, modifier = Modifier.clip(RoundedCornerShape(50))
                            .background(cs.onSurface.copy(alpha = 0.07f)).clickable(role = Role.Button) { pending = emptyList() }.padding(horizontal = 16.dp, vertical = 8.dp))
                    }
                }
                Text("Continue in chat →", style = MaterialTheme.typography.labelMedium, color = cs.primary,
                    modifier = Modifier.padding(top = 8.dp).clickable(role = Role.Button) { onOpen(com.nizam.app.ui.nav.Routes.ASSISTANT) })
            }
        }
    }
}

/** The mic: a gradient orb that swells with your voice and pulses while it listens. */
@Composable
private fun MicOrb(listening: Boolean, level: Float, onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    val pulse = if (listening) rememberInfiniteTransition(label = "mic").animateFloat(0f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Restart), label = "p").value else 0f
    val swell by animateFloatAsState(if (listening) 1f + level * 0.18f else 1f, label = "swell")
    Box(Modifier.size(52.dp).clickable(role = Role.Button, onClick = onClick), contentAlignment = Alignment.Center) {
        if (listening) Canvas(Modifier.fillMaxSize()) {
            drawCircle(cs.primary.copy(alpha = (1f - pulse) * 0.35f), radius = size.minDimension / 2f * (0.8f + pulse * 0.5f))
        }
        Box(Modifier.size(46.dp).scale(swell).shadow(8.dp, CircleShape, ambientColor = cs.primary, spotColor = cs.primary).clip(CircleShape)
            .background(Brush.linearGradient(listOf(cs.primary, cs.tertiary))), contentAlignment = Alignment.Center) {
            Icon(if (listening) Icons.Outlined.Stop else Icons.Filled.Mic, if (listening) "Stop listening" else "Speak a command",
                tint = Color.White, modifier = Modifier.size(24.dp))
        }
    }
}

/** Five bars that move with the loudness of your voice. */
@Composable
private fun Wave(level: Float, modifier: Modifier) {
    val cs = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "w").animateFloat(0f, 6.283f, infiniteRepeatable(tween(900)), label = "t").value
    Canvas(modifier) {
        val n = 5; val gap = size.width / (n * 2f)
        repeat(n) { i ->
            val h = size.height * (0.25f + 0.75f * (0.35f + 0.65f * level) * (0.5f + 0.5f * sin(t + i * 1.1f)))
            drawRoundRect(cs.primary, Offset(gap * (i * 2 + 0.5f), (size.height - h) / 2f), Size(gap, h), CornerRadius(gap / 2, gap / 2))
        }
    }
}
