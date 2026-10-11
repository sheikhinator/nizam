package com.nizam.app.feature.future

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.components.Thinking
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/** Pictures from words, through Pollinations' free image service (no account, no key). */
object Imagine {
    val SIZES = linkedMapOf("Square" to (1024 to 1024), "Portrait" to (832 to 1216), "Landscape" to (1216 to 832), "Wallpaper" to (1080 to 2340))
    val STYLES = linkedMapOf("Any" to "", "Photo" to ", photorealistic, natural light, 35mm, highly detailed",
        "Anime" to ", anime style, vibrant, clean line art", "3D" to ", 3D render, soft studio lighting, octane",
        "Watercolour" to ", watercolour painting, paper texture", "Islamic art" to ", Islamic geometric art, arabesque, gold and lapis",
        "Poster" to ", bold graphic poster design, typography", "Pixel" to ", pixel art, 16-bit", "Sketch" to ", pencil sketch, cross-hatching")

    fun url(prompt: String, size: String, seed: Int): String {
        val (w, h) = SIZES[size] ?: (1024 to 1024)
        return "https://image.pollinations.ai/prompt/${URLEncoder.encode(prompt, "UTF-8").replace("+", "%20")}" +
            "?width=$w&height=$h&seed=$seed&nologo=true&enhance=true"
    }

    fun history(c: Context) = c.getSharedPreferences("imagine", 0).getString("h", "").orEmpty().lines().filter { it.startsWith("http") }
    fun remember(c: Context, u: String) = c.getSharedPreferences("imagine", 0).edit().putString("h", (listOf(u) + history(c).filter { it != u }).take(30).joinToString("\n")).apply()
    /** Removes one generation from Recent (the picture in the gallery, if it was saved, is untouched). */
    fun forget(c: Context, u: String) = c.getSharedPreferences("imagine", 0).edit().putString("h", history(c).filter { it != u }.joinToString("\n")).apply()
    fun forgetAll(c: Context) = c.getSharedPreferences("imagine", 0).edit().remove("h").apply()

    /** Downloads the picture into Pictures/Atlas. */
    suspend fun save(c: Context, url: String): Uri = withContext(Dispatchers.IO) {
        val bytes = java.net.URL(url).readBytes()
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "atlas_${System.currentTimeMillis()}.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Atlas")
        }
        val uri = c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't save to Pictures")
        c.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
        uri
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun ImagineScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var prompt by remember { mutableStateOf("") }
    var size by remember { mutableStateOf("Square") }
    var style by remember { mutableStateOf("Any") }
    var shown by remember { mutableStateOf<String?>(null) }
    var improving by remember { mutableStateOf(false) }
    var history by remember { mutableStateOf(Imagine.history(c)) }
    fun make() {
        if (prompt.isBlank()) return
        val u = Imagine.url(prompt.trim() + Imagine.STYLES[style].orEmpty(), size, (1..999_999).random())
        shown = u; Imagine.remember(c, u); history = Imagine.history(c)
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Imagine", "Describe anything — Atlas paints it")
        OutlinedTextField(prompt, { prompt = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
            placeholder = { Text("A misty morning at Badshahi Mosque, golden light…") })
        Label("Style"); ChipRow(Imagine.STYLES.keys.toList(), style, { style = it })
        Label("Shape"); ChipRow(Imagine.SIZES.keys.toList(), size, { size = it })
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = prompt.isNotBlank(), onClick = { make() }) { Text("Create") }
            OutlinedButton(enabled = prompt.isNotBlank() && !improving, onClick = {
                improving = true
                scope.launch {
                    runCatching { Ai.generate(container.settingsRepository, "Rewrite this as one vivid image prompt under 60 words — subject, setting, light, " +
                        "mood, camera. Reply with the prompt only.\n\n$prompt", maxOutputTokens = 200) }
                        .onSuccess { prompt = it.trim().trim('"') }.onFailure { Feedback.failed("Improving the prompt", it) }
                    improving = false
                }
            }) { Text(if (improving) "Improving…" else "✨ Improve prompt") }
        }
        shown?.let { u ->
            val (w, h) = Imagine.SIZES[size] ?: (1024 to 1024)
            SubcomposeAsyncImage(u, prompt, contentScale = ContentScale.Fit,
                loading = { Thinking(label = "Painting — about 10 seconds") },
                error = { Text("The image service is busy — tap Create again.", color = MaterialTheme.colorScheme.error) },
                modifier = Modifier.padding(top = 16.dp).fillMaxWidth().aspectRatio(w.toFloat() / h).clip(RoundedCornerShape(16.dp)))
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { runCatching { Imagine.save(c, u) }.onSuccess { Feedback.show("Saved to Pictures/Atlas") }
                    .onFailure { Feedback.failed("Saving", it) } } }) { Text("Save") }
                OutlinedButton(onClick = { scope.launch { runCatching { Imagine.save(c, u) }.onSuccess { uri ->
                    c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share picture")) }.onFailure { Feedback.failed("Sharing", it) } } }) { Text("Share") }
                OutlinedButton(onClick = { make() }) { Text("Another") }
                OutlinedButton(onClick = { scope.launch { runCatching {
                    val uri = Imagine.save(c, u)
                    withContext(Dispatchers.IO) { android.app.WallpaperManager.getInstance(c).setStream(c.contentResolver.openInputStream(uri)) }
                }.onSuccess { Feedback.show("Wallpaper set") }.onFailure { Feedback.failed("Setting wallpaper", it) } } }) { Text("Wallpaper") }
            }
        }
        if (history.isNotEmpty()) {
            var confirmClear by remember { mutableStateOf(false) }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Label("Recent")
                Spacer(Modifier.weight(1f))
                Text(if (confirmClear) "Tap to confirm" else "Clear all",
                    color = if (confirmClear) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable {
                        if (confirmClear) { Imagine.forgetAll(c); history = emptyList(); if (shown != null) shown = null; confirmClear = false }
                        else confirmClear = true
                    }.padding(8.dp))
            }
            Text("Long-press an image to remove it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                history.forEach { u ->
                    Box {
                        coil.compose.AsyncImage(u, null, contentScale = ContentScale.Crop,
                            modifier = Modifier.size(96.dp).clip(RoundedCornerShape(12.dp))
                                .combinedClickable(
                                    onClick = { shown = u },
                                    onLongClick = {
                                        Imagine.forget(c, u); if (shown == u) shown = null; history = Imagine.history(c)
                                        Feedback.undo("Removed from Recent") { Imagine.remember(c, u); history = Imagine.history(c) }
                                    }))
                        Box(Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(4.dp).size(22.dp)
                            .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
                            .clickable {
                                Imagine.forget(c, u); if (shown == u) shown = null; history = Imagine.history(c)
                                Feedback.undo("Removed from Recent") { Imagine.remember(c, u); history = Imagine.history(c) }
                            }, contentAlignment = androidx.compose.ui.Alignment.Center) {
                            Text("✕", color = Color.White, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
        Text("Images by Pollinations.ai — free and open.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
        Spacer(Modifier.height(40.dp))
    }
}
