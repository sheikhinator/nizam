package com.nizam.app.feature.future

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.nizam.app.ui.design.Button
import java.util.concurrent.Executors

/** Holds the newest camera frame so a screen can "take" it for Atlas to look at. */
class FrameGrab { @Volatile var latest: Bitmap? = null }

/**
 * The live camera as a composable: back or front, an optional analyser for every frame (QR codes, text),
 * and the newest frame kept in [grab] for the AI to look at. Asks for camera permission itself.
 */
@Composable
fun CameraView(modifier: Modifier, back: Boolean = true, grab: FrameGrab? = null, analyze: ((ImageProxy) -> Unit)? = null) {
    val c = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(c, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        Column(modifier.padding(24.dp)) {
            Text("Atlas needs the camera for this.", style = MaterialTheme.typography.bodyLarge)
            Button(onClick = { ask.launch(Manifest.permission.CAMERA) }, modifier = Modifier.padding(top = 10.dp)) { Text("Allow camera") }
        }
        return
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }
    AndroidView(factory = { ctx ->
        val view = PreviewView(ctx)
        val future = ProcessCameraProvider.getInstance(ctx)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            var n = 0
            analysis.setAnalyzer(executor) { proxy ->
                // keep a frame about twice a second for the AI; hand every frame to the analyser
                if (grab != null && n++ % 15 == 0) runCatching {
                    val b = proxy.toBitmap()
                    val m = android.graphics.Matrix().apply { postRotate(proxy.imageInfo.rotationDegrees.toFloat()) }
                    grab.latest = Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
                }
                if (analyze != null) analyze(proxy) else proxy.close()
            }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(owner, if (back) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
            }
        }, ContextCompat.getMainExecutor(ctx))
        view
    }, modifier = modifier)
}

/** A camera that reports the first QR code it sees. */
@Composable
fun QrScanner(modifier: Modifier, onCode: (String) -> Unit) {
    val scanner = remember { BarcodeScanning.getClient() }
    var done by remember { mutableStateOf(false) }
    CameraView(modifier, back = true) { proxy ->
        val media = proxy.image
        if (media == null || done) { proxy.close(); return@CameraView }
        scanner.process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
            .addOnSuccessListener { codes -> codes.firstNotNullOfOrNull { it.rawValue }?.let { if (!done) { done = true; onCode(it) } } }
            .addOnCompleteListener { proxy.close() }
    }
}
