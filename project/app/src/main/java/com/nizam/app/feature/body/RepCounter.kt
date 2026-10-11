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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.feature.voice.Speaker
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2

/** Angle at b, in degrees, formed by a-b-c. */
private fun angle(a: PoseLandmark, b: PoseLandmark, c: PoseLandmark): Double {
    val r = atan2((c.position.y - b.position.y).toDouble(), (c.position.x - b.position.x).toDouble()) -
        atan2((a.position.y - b.position.y).toDouble(), (a.position.x - b.position.x).toDouble())
    var deg = abs(Math.toDegrees(r)); if (deg > 180) deg = 360 - deg; return deg
}

/** Which joint each exercise bends, and the angles that count as "down" and "up". */
private data class Move(val name: String, val joint: String, val down: Double, val up: Double, val fullDepth: Double)
private val MOVES = listOf(
    Move("Squat", "knee", 110.0, 160.0, 95.0),
    Move("Push-up", "elbow", 100.0, 155.0, 90.0),
    Move("Bicep curl", "elbow", 60.0, 140.0, 45.0),
    Move("Lunge", "knee", 110.0, 160.0, 95.0),
    Move("Shoulder press", "elbow", 95.0, 160.0, 80.0)
)

/**
 * Counts reps from the camera using on-device pose detection. A rep is one full trip down past the
 * "down" angle and back up past "up". Reps that never reach full depth are counted but flagged.
 */
@Composable
fun RepCounterScreen(container: AppContainer) {
    val c = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(c, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var move by remember { mutableStateOf(MOVES.first()) }
    var reps by remember { mutableStateOf(0) }
    var shallow by remember { mutableStateOf(0) }
    var cue by remember { mutableStateOf("Stand side-on to the camera, whole body in frame") }
    var weight by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf<String?>(null) }
    val speaker = remember { Speaker(c) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val detector = remember {
        PoseDetection.getClient(PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build())
    }
    DisposableEffect(Unit) { onDispose { detector.close(); executor.shutdown(); speaker.release() } }

    if (!granted) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
            Text("The rep counter watches your form through the camera. Nothing is recorded or sent anywhere — " +
                "the pose model runs on your phone.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(12.dp)); Button(onClick = { ask.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        }
        return
    }

    // state machine shared with the analyzer thread
    val state = remember { object { var goingDown = false; var minAngle = 180.0 } }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { ctx ->
            val view = PreviewView(ctx)
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
                analysis.setAnalyzer(executor) { proxy ->
                    val media = proxy.image
                    if (media == null) { proxy.close(); return@setAnalyzer }
                    detector.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                        .addOnSuccessListener { pose ->
                            val m = move
                            fun lm(t: Int) = pose.getPoseLandmark(t)?.takeIf { it.inFrameLikelihood > 0.5f }
                            val a = if (m.joint == "knee") listOfNotNull(
                                    listOf(lm(PoseLandmark.LEFT_HIP), lm(PoseLandmark.LEFT_KNEE), lm(PoseLandmark.LEFT_ANKLE)),
                                    listOf(lm(PoseLandmark.RIGHT_HIP), lm(PoseLandmark.RIGHT_KNEE), lm(PoseLandmark.RIGHT_ANKLE)))
                                else listOfNotNull(
                                    listOf(lm(PoseLandmark.LEFT_SHOULDER), lm(PoseLandmark.LEFT_ELBOW), lm(PoseLandmark.LEFT_WRIST)),
                                    listOf(lm(PoseLandmark.RIGHT_SHOULDER), lm(PoseLandmark.RIGHT_ELBOW), lm(PoseLandmark.RIGHT_WRIST)))
                            val visible = a.firstOrNull { tri -> tri.all { it != null } }
                            if (visible == null) { cue = "Step back — I can't see your ${m.joint}s"; return@addOnSuccessListener }
                            val deg = angle(visible[0]!!, visible[1]!!, visible[2]!!)
                            state.minAngle = minOf(state.minAngle, deg)
                            if (!state.goingDown && deg < m.down) { state.goingDown = true; cue = "Down…" }
                            if (state.goingDown && deg > m.up) {
                                state.goingDown = false; reps++
                                val full = state.minAngle <= m.fullDepth
                                if (!full) shallow++
                                cue = if (full) "Good rep" else "Rep $reps — go a little deeper"
                                speaker.speak(if (full) "$reps" else "$reps. Deeper")
                                state.minAngle = 180.0
                            }
                        }
                        .addOnCompleteListener { proxy.close() }
                }
                runCatching {
                    provider.unbindAll()
                    provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
                }
            }, ContextCompat.getMainExecutor(ctx))
            view
        }, modifier = Modifier.fillMaxSize())

        Column(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(14.dp)
            .background(Color(0xCC0B0F1A), RoundedCornerShape(18.dp)).padding(12.dp)) {
            ChipRow(MOVES.map { it.name }, move.name, { n -> move = MOVES.first { it.name == n }; reps = 0; shallow = 0 })
            Row(verticalAlignment = Alignment.Bottom) {
                Text("$reps", color = Color.White, fontSize = 72.sp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(move.name.lowercase() + "s", color = Color(0xFFB8BCC8))
                    if (shallow > 0) Text("$shallow not full depth", color = Color(0xFFE0B14A))
                }
            }
            Text(cue, color = Color(0xFF4FC3A1))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(14.dp)
            .background(Color(0xCC0B0F1A), RoundedCornerShape(18.dp)).padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(weight, { weight = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("kg (0 = bodyweight)") },
                    singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(enabled = reps > 0, onClick = {
                    scope.launch {
                        container.gymRepository.addSet(move.name, weight.toDoubleOrNull() ?: 0.0, reps)
                        saved = "Logged ${move.name}: $reps reps"; speaker.speak("Set logged. $reps reps."); reps = 0; shallow = 0
                    }
                }) { Text("Log set") }
                Spacer(Modifier.width(6.dp))
                OutlinedButton(onClick = { reps = 0; shallow = 0 }) { Text("Reset") }
            }
            saved?.let { Text(it, color = Color.White) }
        }
    }
}
