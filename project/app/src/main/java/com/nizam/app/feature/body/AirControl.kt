package com.nizam.app.feature.body

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material.icons.outlined.Gesture
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.core.content.ContextCompat
import com.nizam.app.AppContainer
import com.nizam.app.feature.voice.Speaker
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import java.util.concurrent.Executors

/**
 * Air control: use Atlas without touching the phone (flour on your hands, wudu, working out). The front
 * camera watches for one of three moves, on the phone only, nothing recorded:
 * right hand up = next, left hand up = back, both hands up = read it aloud / stop.
 * Works with any note or any text: recipes, duas you're learning, a speech, workout steps.
 */
private fun pages(text: String, size: Int = 320): List<String> {
    val out = ArrayList<String>(); val sb = StringBuilder()
    text.split(Regex("(?<=[.!?\\n])\\s+")).forEach { s ->
        if (sb.length + s.length > size && sb.isNotEmpty()) { out += sb.toString().trim(); sb.clear() }
        sb.append(s).append(' ')
    }
    if (sb.isNotBlank()) out += sb.toString().trim()
    return out.ifEmpty { listOf(text) }
}

@Composable
fun AirControlScreen(container: AppContainer) {
    val c = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(c, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var text by remember { mutableStateOf("") }
    var started by remember { mutableStateOf(false) }
    val notes by produceState(initialValue = emptyList<com.nizam.app.feature.notes.Note>()) {
        value = runCatching { container.noteRepository.everything().sortedByDescending { it.updatedAt }.take(12) }.getOrDefault(emptyList())
    }
    if (!started) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Air control", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text("Hands busy? Read and move through anything without touching the phone.", style = MaterialTheme.typography.bodyMedium)
            Text("✋ right hand up: next   ·   left hand up: back   ·   🙌 both: read aloud / stop", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 4, placeholder = { Text("Paste a recipe, steps, a speech…") })
            if (notes.isNotEmpty()) {
                Text("Or pick a note", fontWeight = FontWeight.SemiBold)
                notes.forEach { n -> Text("• " + n.title, color = MaterialTheme.colorScheme.primary, modifier = Modifier.fillMaxWidth().clickable { text = n.body.ifBlank { n.title } }.padding(vertical = 4.dp)) }
            }
            if (!granted) Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera (stays on the phone)") }
            else Button(onClick = { started = true }, enabled = text.isNotBlank()) { Text("Start hands-free") }
        }
        return
    }
    AirReader(pages(text)) { started = false }
}

@Composable
private fun AirReader(pages: List<String>, close: () -> Unit) {
    val c = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var page by remember { mutableIntStateOf(0) }
    var cue by remember { mutableStateOf("Raise a hand to turn the page") }
    var speak by remember { mutableStateOf(true) }
    val speaker = remember { Speaker(c) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val detector = remember { com.google.mlkit.vision.pose.PoseDetection.getClient(
        com.google.mlkit.vision.pose.defaults.PoseDetectorOptions.Builder().setDetectorMode(com.google.mlkit.vision.pose.defaults.PoseDetectorOptions.STREAM_MODE).build()) }
    val gate = remember { object { var last = 0L; var hold = "" ; var since = 0L } }
    DisposableEffect(Unit) { onDispose { runCatching { detector.close() }; executor.shutdown(); speaker.release() } }
    fun act(g: String) {
        when (g) {
            "next" -> if (page < pages.lastIndex) { page++; cue = "Next →"; if (speak) speaker.speak(pages[page]) } else cue = "That's the end"
            "back" -> if (page > 0) { page--; cue = "← Back"; if (speak) speaker.speak(pages[page]) }
            "both" -> { speak = !speak; if (speak) { speaker.speak(pages[page]); cue = "Reading aloud" } else { speaker.stop(); cue = "Quiet" } }
        }
    }
    Column(Modifier.fillMaxSize().statusBarsPadding().padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(110.dp, 140.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black)) {
                AndroidView(factory = { ctx ->
                    val view = PreviewView(ctx)
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        runCatching {
                            val provider = future.get()
                            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                            analysis.setAnalyzer(executor) { proxy ->
                                val media = proxy.image
                                if (media == null) { proxy.close(); return@setAnalyzer }
                                detector.process(com.google.mlkit.vision.common.InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                                    .addOnSuccessListener { pose ->
                                        fun lm(t: Int) = pose.getPoseLandmark(t)?.takeIf { it.inFrameLikelihood > 0.6f }
                                        val nose = lm(com.google.mlkit.vision.pose.PoseLandmark.NOSE)
                                        // front camera is mirrored: the person's right hand appears on the image's left landmark set
                                        val rw = lm(com.google.mlkit.vision.pose.PoseLandmark.RIGHT_WRIST); val lw = lm(com.google.mlkit.vision.pose.PoseLandmark.LEFT_WRIST)
                                        val y0 = nose?.position?.y
                                        val rUp = y0 != null && rw != null && rw.position.y < y0
                                        val lUp = y0 != null && lw != null && lw.position.y < y0
                                        val g = when { rUp && lUp -> "both"; rUp -> "next"; lUp -> "back"; else -> "" }
                                        val now = System.currentTimeMillis()
                                        if (g != gate.hold) { gate.hold = g; gate.since = now }
                                        // a move counts once it's held for a moment, then rests for a second and a half
                                        if (g.isNotEmpty() && now - gate.since > 350 && now - gate.last > 1500) { gate.last = now; act(g) }
                                    }
                                    .addOnCompleteListener { proxy.close() }
                            }
                            provider.unbindAll()
                            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    view
                }, modifier = Modifier.fillMaxSize())
            }
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(cue, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                Text("Page ${page + 1} of ${pages.size}", style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Read aloud", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Switch(speak, { speak = it; if (!it) speaker.stop() })
                }
            }
        }
        Text(pages[page], fontSize = 24.sp, lineHeight = 34.sp, modifier = Modifier.weight(1f).padding(top = 16.dp).verticalScroll(rememberScrollState()))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { act("back") }) { Text("← Back") }
            TextButton(onClick = close) { Text("Done") }
            TextButton(onClick = { act("next") }) { Text("Next →") }
        }
    }
}

val AirSpecs: List<com.nizam.app.feature.nx.Spec> get() = listOf(
    com.nizam.app.feature.nx.ScreenSpec("aircontrol", "Air control", "Hands-free: raise a hand to turn pages and read aloud, for recipes, duas, speeches and steps",
        androidx.compose.material.icons.Icons.Outlined.Gesture, com.nizam.app.ui.design.Hub.Tools,
        "air control hands free gesture wave camera raise hand next page recipe cooking read aloud") { container, _ -> AirControlScreen(container) })
