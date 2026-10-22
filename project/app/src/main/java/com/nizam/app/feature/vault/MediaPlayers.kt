package com.nizam.app.feature.vault

import android.media.MediaPlayer
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
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.delay
import java.io.File

/** Pinch-to-zoom, drag-to-pan photo viewer. */
@Composable
fun PhotoViewer(file: File, title: String, onBack: () -> Unit) {
    var scale by remember { mutableStateOf(1f) }
    var offsetX by remember { mutableStateOf(0f) }
    var offsetY by remember { mutableStateOf(0f) }

    val bitmap = remember(file.path) {
        runCatching { android.graphics.BitmapFactory.decodeFile(file.path) }.getOrNull()
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back to vault", tint = Color.White)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White,
                modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { scale = 1f; offsetX = 0f; offsetY = 0f }) { Text("Reset") }
            Spacer(Modifier.width(10.dp))
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        scale = (scale * zoom).coerceIn(1f, 8f)
                        if (scale > 1f) {
                            offsetX += pan.x
                            offsetY += pan.y
                        } else {
                            offsetX = 0f; offsetY = 0f
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale, scaleY = scale,
                            translationX = offsetX, translationY = offsetY
                        )
                )
            } else {
                Text("Could not decode this image.", color = Color.White)
            }
        }
    }
}

/** Video playback with the standard transport controls. */
@Composable
fun VideoPlayer(file: File, title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back to vault", tint = Color.White)
            }
            Text(title, style = MaterialTheme.typography.titleMedium, color = Color.White)
        }
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    setVideoPath(file.absolutePath)
                    val controller = MediaController(context)
                    controller.setAnchorView(this)
                    setMediaController(controller)
                    setOnPreparedListener { it.isLooping = false; start() }
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** Audio playback with a seek bar and skip controls. */
@Composable
fun AudioPlayer(file: File, title: String, onBack: () -> Unit) {
    val player = remember(file.path) {
        runCatching {
            MediaPlayer().apply {
                setDataSource(file.absolutePath)
                prepare()
            }
        }.getOrNull()
    }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableStateOf(0) }
    val duration = player?.duration ?: 0

    DisposableEffect(player) {
        onDispose { runCatching { player?.release() } }
    }

    LaunchedEffect(playing) { com.nizam.app.ui.design.guard {
        while (playing && player != null) {
            position = runCatching { player.currentPosition }.getOrDefault(0)
            if (!player.isPlaying) playing = false
            delay(400)
        }
    } }

    fun clock(ms: Int): String {
        val total = ms / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }

    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.Filled.ArrowBack, contentDescription = "Back to vault")
            }
            Text(title, style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(30.dp))
        Text("♪", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(20.dp))

        if (player == null) {
            Text("Could not play this file.", color = MaterialTheme.colorScheme.error)
            return@Column
        }

        Slider(
            value = position.toFloat(),
            onValueChange = { position = it.toInt(); player.seekTo(it.toInt()) },
            valueRange = 0f..(duration.coerceAtLeast(1)).toFloat(),
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(clock(position), style = MonoNumber)
            Text(clock(duration), style = MonoNumber)
        }

        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedButton(onClick = {
                player.seekTo((player.currentPosition - 10000).coerceAtLeast(0))
            }) { Text("⏪ 10s") }
            OutlinedButton(onClick = {
                if (player.isPlaying) { player.pause(); playing = false }
                else { player.start(); playing = true }
            }) { Text(if (playing) "⏸  Pause" else ">  Play") }
            OutlinedButton(onClick = {
                player.seekTo((player.currentPosition + 10000).coerceAtMost(duration))
            }) { Text("10s ⏩") }
        }
    }
}
