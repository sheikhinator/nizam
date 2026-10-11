package com.nizam.app.feature.next

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.nizam.app.feature.future.CameraView
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Radius
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.frost
import com.nizam.app.ui.design.rememberHaptics

/**
 * Magic Capture: point the camera at people and Atlas takes the shot itself at the moment everyone
 * is smiling with their eyes open. Nothing leaves the phone; photos go to Pictures/Atlas.
 */
object MagicCapture {
    fun good(faces: List<Face>, needSmile: Boolean): Boolean = faces.isNotEmpty() && faces.all { f ->
        (f.leftEyeOpenProbability ?: 1f) > 0.55f && (f.rightEyeOpenProbability ?: 1f) > 0.55f &&
            (!needSmile || (f.smilingProbability ?: 0f) > 0.7f)
    }

    fun save(c: Context, b: Bitmap): Boolean = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "Atlas_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= 29) { put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Atlas"); put(MediaStore.Images.Media.IS_PENDING, 1) }
        }
        val uri = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
        c.contentResolver.openOutputStream(uri)?.use { b.compress(Bitmap.CompressFormat.JPEG, 94, it) }
        if (Build.VERSION.SDK_INT >= 29) c.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
        true
    }.getOrDefault(false)
}

@Composable
fun MagicCaptureScreen() {
    val ctx = LocalContext.current
    val haptics = rememberHaptics()
    var front by remember { mutableStateOf(false) }
    var smile by remember { mutableStateOf(true) }
    var taken by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf("Looking for faces…") }
    var last by remember { mutableStateOf(0L) }
    val detector = remember {
        FaceDetection.getClient(FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL).build())
    }
    DisposableEffect(Unit) { onDispose { detector.close() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        androidx.compose.runtime.key(front) {
            CameraView(Modifier.fillMaxSize(), back = !front) { proxy ->
                val media = proxy.image
                if (media == null) { proxy.close(); return@CameraView }
                detector.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { faces ->
                        status = when {
                            faces.isEmpty() -> "Looking for faces…"
                            MagicCapture.good(faces, smile) -> "Perfect"
                            smile -> "${faces.size} face${if (faces.size > 1) "s" else ""} · waiting for smiles"
                            else -> "${faces.size} face${if (faces.size > 1) "s" else ""} · waiting for open eyes"
                        }
                        val now = System.currentTimeMillis()
                        if (MagicCapture.good(faces, smile) && now - last > 2500) {
                            last = now
                            val raw = proxy.toBitmap()
                            val m = android.graphics.Matrix().apply {
                                postRotate(proxy.imageInfo.rotationDegrees.toFloat()); if (front) postScale(-1f, 1f)
                            }
                            val b = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
                            if (MagicCapture.save(ctx, b)) { taken++; haptics.success() }
                        }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(Space.l).frost(Radius.card).padding(Space.l),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(status, style = MaterialTheme.typography.titleMedium)
            Text("$taken taken · saved to Pictures/Atlas", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Capsule(if (front) "Front camera" else "Back camera", false) { front = !front }
                Capsule("Wait for smiles", smile) { smile = !smile }
            }
        }
    }
}
