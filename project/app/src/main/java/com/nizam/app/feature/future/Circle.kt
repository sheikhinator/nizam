package com.nizam.app.feature.future

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.nizam.app.feature.jarvis.Background
import com.nizam.app.net.Vision
import com.nizam.app.ui.components.Thinking
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.theme.NizamTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * Circle to Search, Atlas-style: long-press the power button (with Atlas as the digital assistant), draw
 * around anything on screen, and ask — what is it, translate, explain, find it to buy, copy its text.
 */
object CircleShot {
    fun file(c: Context) = File(c.cacheDir, "circle_shot.png")
    fun save(c: Context, b: Bitmap, screenText: String) {
        file(c).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        c.getSharedPreferences("circle", 0).edit().putString("text", screenText.take(4000)).apply()
    }
    fun open(c: Context) = c.startActivity(Intent(c, CircleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
}

class CircleActivity : ComponentActivity() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val shot = runCatching { BitmapFactory.decodeFile(CircleShot.file(this).path) }.getOrNull()
        val screenText = getSharedPreferences("circle", 0).getString("text", "").orEmpty()
        val container = Background.container(this)
        setContent {
            NizamTheme(darkTheme = true) {
                if (shot == null) { finish(); return@NizamTheme }
                var box by remember { mutableStateOf(IntSize.Zero) }
                val points = remember { mutableStateListOf<Offset>() }
                var crop by remember { mutableStateOf<Bitmap?>(null) }
                var answer by remember { mutableStateOf("") }
                var busy by remember { mutableStateOf(false) }
                var question by remember { mutableStateOf("") }

                fun region(): Bitmap {
                    if (points.size < 4 || box.width == 0) return shot
                    val sx = shot.width / box.width.toFloat(); val sy = shot.height / box.height.toFloat()
                    val l = (points.minOf { it.x } * sx).toInt().coerceIn(0, shot.width - 1)
                    val t = (points.minOf { it.y } * sy).toInt().coerceIn(0, shot.height - 1)
                    val r = (points.maxOf { it.x } * sx).toInt().coerceIn(l + 1, shot.width)
                    val b = (points.maxOf { it.y } * sy).toInt().coerceIn(t + 1, shot.height)
                    return Bitmap.createBitmap(shot, l, t, r - l, b - t)
                }
                fun ask(prompt: String) {
                    busy = true; answer = ""
                    val img = crop ?: region()
                    scope.launch {
                        answer = runCatching {
                            Vision.ask(container.settingsRepository, prompt + (if (screenText.isNotBlank()) "\n\n(Text on the screen, for context: ${screenText.take(1500)})" else "") +
                                "\n" + Lang.instruction(), img)
                        }.getOrElse { "Couldn't look at it: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
                        busy = false
                    }
                }
                fun searchWeb(site: String?) {
                    busy = true
                    scope.launch {
                        val q = runCatching { Vision.ask(container.settingsRepository, "Give only a short web search query (max 8 words) that would find exactly what's in this image. No quotes.", crop ?: region()) }
                            .getOrDefault("").trim().lines().first().trim('"')
                        busy = false
                        val url = when (site) { "daraz" -> "https://www.daraz.pk/catalog/?q=${Uri.encode(q)}"; else -> "https://www.google.com/search?q=${Uri.encode(q)}" }
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
                    }
                }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Image(shot.asImageBitmap(), null, contentScale = ContentScale.FillBounds,
                        modifier = Modifier.fillMaxSize().onSizeChanged { box = it }
                            .pointerInput(Unit) {
                                detectDragGestures(onDragStart = { points.clear(); crop = null; answer = ""; points.add(it) },
                                    onDrag = { ch, _ -> points.add(ch.position) },
                                    onDragEnd = { crop = region() })
                            })
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(Color.Black.copy(alpha = if (points.isEmpty()) 0.25f else 0.1f))
                        if (points.size > 1) {
                            val p = Path().apply { moveTo(points[0].x, points[0].y); points.drop(1).forEach { lineTo(it.x, it.y) } }
                            drawPath(p, Color.White, style = Stroke(width = 14f, cap = StrokeCap.Round))
                            drawPath(p, Color(0xFF8AB4F8), style = Stroke(width = 8f, cap = StrokeCap.Round))
                        }
                    }
                    Text(if (points.isEmpty()) "Circle anything, or ask about the whole screen" else "", color = Color.White,
                        style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp)
                            .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 16.dp, vertical = 8.dp))
                    Text("✕", color = Color.White, style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).clickable { finish() }.padding(8.dp))

                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                        .background(MaterialTheme.colorScheme.background).navigationBarsPadding().imePadding().padding(16.dp)) {
                        crop?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.heightIn(max = 110.dp).clip(RoundedCornerShape(10.dp))) }
                        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                "Do it for me" to {
                                    val goal = question.ifBlank { "Help me finish what I'm doing on this screen" }
                                    finish(); AppAgent.start(this@CircleActivity, container, goal)
                                },
                                "What is this?" to { ask("What is this? Identify it precisely and tell me the most useful things to know.") },
                                "Explain" to { ask("Explain this simply, step by step.") },
                                "Translate" to { ask("Translate all the text here into ${if (Lang.mode(this@CircleActivity) == "en") "English" else "Urdu"}. Keep the layout.") },
                                "Search web" to { searchWeb(null) },
                                "Find on Daraz" to { searchWeb("daraz") },
                                "Copy text" to {
                                    scope.launch {
                                        val t = runCatching { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromBitmap(crop ?: region(), 0)).await().text }.getOrDefault("")
                                        getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("text", t))
                                        answer = if (t.isBlank()) "No text found there." else "Copied:\n$t"
                                    }
                                },
                                "Save to notes" to {
                                    scope.launch {
                                        val what = answer.ifBlank { runCatching { Vision.ask(container.settingsRepository, "Describe what this shows in 2-3 lines, keeping any names, numbers and text.", crop ?: region()) }.getOrDefault("") }
                                        container.noteRepository.createFull("Inbox/Circled ${java.time.LocalDateTime.now().toString().take(16).replace('T', ' ')}", what)
                                        answer = "Saved to Notes › Inbox."
                                    }
                                },
                            ).forEach { (label, action) ->
                                Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.clip(RoundedCornerShape(50))
                                    .background(MaterialTheme.colorScheme.surfaceVariant).clickable { action() }.padding(horizontal = 14.dp, vertical = 9.dp))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(question, { question = it }, singleLine = true, modifier = Modifier.weight(1f),
                                placeholder = { Text(if (crop != null) "Ask about what you circled" else "Ask about this screen, or anything") })
                            Text("Ask", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.clickable(enabled = question.isNotBlank()) { ask(question); question = "" }.padding(12.dp))
                        }
                        if (busy) Thinking(label = "Looking")
                        if (answer.isNotBlank()) Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
                            SelectionContainer { Text(answer, style = MaterialTheme.typography.bodyLarge) }
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel(); super.onDestroy() }
}

/**
 * The other way in, without changing the default assistant: the floating bubble or Superpowers screen asks
 * the accessibility service (screen control) for a screenshot, then opens Circle to Search on it.
 */
object CircleNow {
    fun start(c: Context): Boolean {
        val s = com.nizam.app.feature.phone.AtlasAccessibilityService.instance ?: return false
        if (android.os.Build.VERSION.SDK_INT < 30) return false
        s.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, c.mainExecutor, object : android.accessibilityservice.AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(r: android.accessibilityservice.AccessibilityService.ScreenshotResult) {
                val b = Bitmap.wrapHardwareBuffer(r.hardwareBuffer, r.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                r.hardwareBuffer.close()
                if (b != null) { CircleShot.save(c, b, runCatching { s.readScreen() }.getOrDefault("")); CircleShot.open(c) }
            }
            override fun onFailure(errorCode: Int) { com.nizam.app.ui.components.Feedback.show("This screen can't be captured (it's protected)") }
        })
        return true
    }
}

/** How to reach Circle to Search, and a one-tap setup. */
@androidx.compose.runtime.Composable
fun CircleSetupScreen() {
    val c = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        com.nizam.app.core.ModuleTitle("Circle to Search", "Circle anything on screen, in any app")
        com.nizam.app.ui.design.Group {
            Text("Way 1 · Long-press the power button", style = MaterialTheme.typography.titleSmall)
            Text("Make Atlas your digital assistant (Settings › Apps › Default apps › Digital assistant app › Atlas), and allow “Use screenshot”. " +
                "Then hold the power button anywhere — the screen freezes and you circle what you want.", style = MaterialTheme.typography.bodyMedium)
            com.nizam.app.ui.design.Button(onClick = { runCatching { c.startActivity(Intent(android.provider.Settings.ACTION_VOICE_INPUT_SETTINGS)) }
                .onFailure { runCatching { c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) } } },
                modifier = Modifier.padding(top = 8.dp)) { Text("Open assistant settings") }
        }
        com.nizam.app.ui.design.Group(Modifier.padding(top = 10.dp)) {
            Text("Way 2 · Hold the Atlas bubble", style = MaterialTheme.typography.titleSmall)
            Text("Turn on the floating bubble and screen control; then press and hold the bubble over any app.", style = MaterialTheme.typography.bodyMedium)
            com.nizam.app.ui.design.Button(onClick = {
                if (android.provider.Settings.canDrawOverlays(c)) { com.nizam.app.feature.presence.BubbleService.start(c); com.nizam.app.ui.components.Feedback.show("Bubble on") }
                else runCatching { c.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))) }
            }, modifier = Modifier.padding(top = 8.dp)) { Text("Turn on the bubble") }
            if (com.nizam.app.feature.phone.AtlasAccessibilityService.instance == null)
                com.nizam.app.ui.design.OutlinedButton(onClick = { runCatching { c.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)) } },
                    modifier = Modifier.padding(top = 6.dp)) { Text("Turn on screen control") }
        }
        Text("What you circle is sent to your AI provider only when you ask about it. Banking apps block screenshots, so they can't be circled.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
    }
}
