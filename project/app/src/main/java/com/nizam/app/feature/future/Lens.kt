package com.nizam.app.feature.future

import android.app.Activity
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.net.Vision
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.components.Thinking
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Point the camera and talk: Atlas looks at what you're pointing at and answers. */
private val LENS_MODES = linkedMapOf(
    "What is this?" to "What is this? Identify it precisely and give the most useful things to know. Be brief.",
    "Read & translate" to "Read all the text you can see and translate it into %LANG%. Keep the layout.",
    "Medicine" to "Identify this medicine (name, salt/generic, strength). What is it for, usual adult dose, key warnings and what not to mix it with. Remind me to follow my doctor.",
    "Homework" to "Solve or explain this problem step by step, simply, like a good teacher.",
    "Plant & animal" to "Identify this plant or animal. Is it safe? How to care for it or what to know.",
    "Food" to "What food is this? Roughly how many calories and grams of protein, carbs and fat per serving?",
    "Fix it" to "What's wrong here and how do I fix it, step by step? Say when to call a professional.",
    "Price check" to "What is this product exactly (brand, model)? Roughly what does it cost new in Pakistan in rupees?",
)

@Composable
fun LensScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    val grab = remember { FrameGrab() }
    var mode by remember { mutableStateOf("What is this?") }
    var answer by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var speak by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf(false) }
    val speaker = remember { com.nizam.app.feature.voice.Speaker(c) }
    DisposableEffect(Unit) { onDispose { speaker.release() } }
    fun ask(question: String? = null) {
        val frame = grab.latest ?: run { Feedback.show("Hold on — the camera is starting"); return }
        busy = true
        val lang = if (Lang.mode(c) == "en") "English" else "Urdu"
        val prompt = (question ?: LENS_MODES.getValue(mode)).replace("%LANG%", lang) + "\n" + Lang.instruction(c)
        scope.launch {
            answer = runCatching { Vision.ask(container.settingsRepository, prompt, frame) }
                .getOrElse { "Couldn't look: ${Feedback.friendly(it)}" }
            busy = false
            if (speak) speaker.speak(answer)
        }
    }
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { ask(it) }
    }
    // live mode: a fresh description every 8 seconds, spoken — for walking around or for low vision
    LaunchedEffect(live) { com.nizam.app.ui.design.guard {
        while (live) {
            delay(8000)
            if (!busy && grab.latest != null) ask("In one short sentence, what's in front of me now? Mention anything important or dangerous first.")
        }
    } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        CameraView(Modifier.fillMaxSize(), back = true, grab = grab)
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .background(Color.Black.copy(alpha = 0.72f)).padding(14.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LENS_MODES.keys.forEach { m ->
                    Text(m, color = if (m == mode) Color.Black else Color.White, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (m == mode) Color.White else Color.White.copy(alpha = 0.15f))
                            .clickable { mode = m }.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
            if (busy) Thinking(label = "Looking", modifier = Modifier.padding(top = 8.dp))
            if (answer.isNotBlank()) Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(top = 10.dp)) {
                SelectionContainer { Text(answer, color = Color.White, style = MaterialTheme.typography.bodyLarge) }
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Text(if (speak) "🔊" else "🔈", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clickable { speak = !speak; if (!speak) speaker.stop() }.padding(8.dp))
                Text("🎤", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clickable {
                    runCatching { listen.launch(com.nizam.app.feature.voice.speechIntent()) } }.padding(8.dp))
                Box(Modifier.size(72.dp).clip(CircleShape).background(Color.White).clickable(enabled = !busy) { ask() }, contentAlignment = Alignment.Center) {
                    Box(Modifier.size(60.dp).clip(CircleShape).background(Color(0xFF8AB4F8)))
                }
                Text(if (live) "⏸" else "👁", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clickable {
                    live = !live; if (live) { speak = true; Feedback.show("Live: describing what's ahead every few seconds") } }.padding(8.dp))
                Text("✕", color = Color.White, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.clickable { answer = ""; speaker.stop() }.padding(8.dp))
            }
        }
    }
}
