package com.nizam.app.feature.dynamic

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.delay

/**
 * Plays whatever a module entry is holding. Images, video and audio render in-app; anything else
 * hands off to whichever app on the device knows what to do with it.
 */
@Composable
fun ModuleFileViewer(uriString: String, name: String, mime: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val uri = remember(uriString) { Uri.parse(uriString) }

    when {
        mime.startsWith("image") -> {
            var scale by remember { mutableStateOf(1f) }
            var offsetX by remember { mutableStateOf(0f) }
            var offsetY by remember { mutableStateOf(0f) }
            val bitmap = remember(uriString) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        android.graphics.BitmapFactory.decodeStream(it)
                    }
                }.getOrNull()
            }
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                Header(name, onBack, Color.White)
                Box(
                    Modifier.fillMaxSize().pointerInput(Unit) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            scale = (scale * zoom).coerceIn(1f, 8f)
                            if (scale > 1f) { offsetX += pan.x; offsetY += pan.y }
                            else { offsetX = 0f; offsetY = 0f }
                        }
                    },
                    contentAlignment = Alignment.Center
                ) {
                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = name,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize().graphicsLayer(
                                scaleX = scale, scaleY = scale,
                                translationX = offsetX, translationY = offsetY
                            )
                        )
                    } else Text("Could not open this image.", color = Color.White)
                }
            }
        }

        mime.startsWith("video") -> {
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                Header(name, onBack, Color.White)
                AndroidView(
                    factory = { ctx ->
                        VideoView(ctx).apply {
                            setVideoURI(uri)
                            val controller = MediaController(ctx)
                            controller.setAnchorView(this)
                            setMediaController(controller)
                            setOnPreparedListener { start() }
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        mime.startsWith("audio") -> {
            val player = remember(uriString) {
                runCatching {
                    MediaPlayer().apply { setDataSource(context, uri); prepare() }
                }.getOrNull()
            }
            var playing by remember { mutableStateOf(false) }
            var position by remember { mutableStateOf(0) }
            val duration = player?.duration ?: 0

            DisposableEffect(player) { onDispose { runCatching { player?.release() } } }
            LaunchedEffect(playing) {
                while (playing && player != null) {
                    position = runCatching { player.currentPosition }.getOrDefault(0)
                    if (!player.isPlaying) playing = false
                    delay(400)
                }
            }
            fun clock(ms: Int) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)

            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Header(name, onBack, MaterialTheme.colorScheme.onBackground)
                Spacer(Modifier.height(40.dp))
                Text("♪", style = MaterialTheme.typography.displaySmall,
                    modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                Spacer(Modifier.height(30.dp))
                if (player == null) {
                    Text("Could not play this file.", color = MaterialTheme.colorScheme.error)
                } else {
                    Slider(
                        value = position.toFloat(),
                        onValueChange = { position = it.toInt(); player.seekTo(it.toInt()) },
                        valueRange = 0f..duration.coerceAtLeast(1).toFloat(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(clock(position), style = MonoNumber)
                        Text(clock(duration), style = MonoNumber)
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedButton(onClick = {
                            player.seekTo((player.currentPosition - 10000).coerceAtLeast(0))
                        }) { Text("⏪") }
                        OutlinedButton(onClick = {
                            if (player.isPlaying) { player.pause(); playing = false }
                            else { player.start(); playing = true }
                        }) { Text(if (playing) "⏸  Pause" else ">  Play") }
                        OutlinedButton(onClick = {
                            player.seekTo((player.currentPosition + 10000).coerceAtMost(duration))
                        }) { Text("⏩") }
                    }
                }
            }
        }

        else -> {
            LaunchedEffect(uriString) {
                runCatching {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, mime.ifBlank { "*/*" })
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                    )
                }
                onBack()
            }
            Column(Modifier.fillMaxSize().padding(20.dp)) {
                Header(name, onBack, MaterialTheme.colorScheme.onBackground)
                Text("Opening in another app…", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun Header(name: String, onBack: () -> Unit, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.Filled.ArrowBack, contentDescription = "Back", tint = tint)
        }
        Text(name, style = MaterialTheme.typography.titleMedium, color = tint)
    }
}
