package com.nizam.app.feature.memory

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.agent.Snapshot
import com.nizam.app.feature.insight.Days
import com.nizam.app.feature.voice.Speaker
import com.nizam.app.net.Ai
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) { content() } }
}

@Composable
fun MemoryScreen(container: AppContainer) {
    var tab by remember { mutableStateOf("Read later") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Memory", "What you read, learn, write to yourself, and see each day")
        ChipRow(listOf("Read later", "Podcast", "Learn a link", "Letters", "Weekly recap", "Photo a day"), tab, { tab = it })
        Spacer(Modifier.height(8.dp))
        when (tab) {
            "Read later" -> ReadLaterTab()
            "Podcast" -> com.nizam.app.feature.world.PodcastTab(container)
            "Learn a link" -> LearnLinkTab(container)
            "Letters" -> LettersTab()
            "Weekly recap" -> RecapTab(container)
            else -> PhotoTab()
        }
        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun ReadLaterTab() {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var list by remember { mutableStateOf(ReadLater.all(c)) }
    var url by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<Article?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    open?.let { a ->
        Text("< back to list", color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(role = Role.Button) {
                list = list.map { if (it.id == a.id) it.copy(read = true) else it }; ReadLater.save(c, list); open = null
            }.padding(vertical = 8.dp))
        Text(a.title, style = MaterialTheme.typography.headlineSmall)
        Text(a.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))
        Text(a.text, style = MaterialTheme.typography.bodyLarge)
        return
    }
    OutlinedTextField(url, { url = it }, label = { Text("Paste an article link") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(enabled = url.startsWith("http"), onClick = {
        scope.launch {
            status = "Saving…"
            runCatching { ReadLater.add(c, url.trim()) }.onSuccess { list = ReadLater.all(c); url = ""; status = "Saved for offline reading" }
                .onFailure { status = "Couldn't fetch that page: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
        }
    }) { Text("Save") }
    status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
    Text("Tip: share any link into Atlas from your browser, too. Saved articles are searchable in your second brain.",
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    list.forEach { a ->
        Card {
            Text((if (a.read) "" else "● ") + a.title, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.clickable(role = Role.Button) { open = a })
            Text("${a.saved} · ${a.text.length / 1100 + 1} min read", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("remove", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(role = Role.Button) { list = list - a; ReadLater.save(c, list) })
        }
    }
}

/** Articles work fully; for YouTube only the title and description are readable without a transcript API. */
@Composable
private fun LearnLinkTab(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var url by remember { mutableStateOf("") }
    var out by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    OutlinedTextField(url, { url = it }, label = { Text("Link to an article or video") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Button(enabled = url.startsWith("http") && !busy, onClick = {
        scope.launch {
            busy = true
            out = runCatching {
                val (title, text) = Readable.fetch(url.trim())
                val lesson = Ai.generate(container.settingsRepository,
                    "Teach me this from the page \"$title\". Give: a 3-sentence summary, the 5 key ideas, and 5 flashcards " +
                        "as Q/A pairs that test understanding, not trivia. If the page text is too thin to learn from (e.g. a " +
                        "video page), say so and work from the title honestly.\n\nPage text:\n${text.take(14_000)}",
                    maxOutputTokens = 2000)
                container.noteRepository.createFull("Learned: $title", "$lesson\n\nSource: $url")
                "$lesson\n\n✓ Saved to Notes as “Learned: $title”"
            }.getOrElse { "Couldn't learn from that link: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
            busy = false
        }
    }) { Text(if (busy) "Reading…" else "Teach me") }
    Text("Works best with articles. For videos, only the title and description are readable.",
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    out?.let { Card { Text(it) } }
}

@Composable
private fun LettersTab() {
    val c = LocalContext.current
    var list by remember { mutableStateOf(Letters.all(c)) }
    var text by remember { mutableStateOf("") }
    var months by remember { mutableStateOf("6") }
    val today = LocalDate.now().toString()
    Card {
        Text("Write to future you", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(text, { text = it }, label = { Text("Dear future me…") }, modifier = Modifier.fillMaxWidth().height(160.dp))
        ChipRow(listOf("1", "3", "6", "12", "36"), months, { months = it }, labels = { if (it == "36") "3 years" else "${it}mo" })
        Button(enabled = text.isNotBlank(), onClick = {
            list = list + Letter(System.currentTimeMillis(), today, LocalDate.now().plusMonths(months.toLong()).toString(), text.trim())
            Letters.save(c, list); text = ""
        }) { Text("Seal it") }
        Text("Sealed letters can't be read until their day. You'll get a notification when one arrives.",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    list.sortedBy { it.deliver }.forEach { l ->
        Card {
            if (l.deliver <= today) {
                Text("Written ${l.written}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(l.text, style = MaterialTheme.typography.bodyLarge)
            } else Text("✉ Sealed · opens ${l.deliver}", style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun RecapTab(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    val speaker = remember { Speaker(c) }
    DisposableEffect(Unit) { onDispose { speaker.release() } }
    var recap by remember { mutableStateOf(c.getSharedPreferences("recap", 0).getString("text", "").orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    Button(enabled = !busy, onClick = {
        scope.launch {
            busy = true
            recap = runCatching {
                val week = Days.build(container, c, 7).joinToString("\n") { d ->
                    d.date.toString() + ": " + d.values.entries.filter { it.value != null }.joinToString { "${it.key}=${"%.0f".format(it.value)}" } }
                Ai.generate(container.settingsRepository,
                    "${Snapshot.of(container)}\n\nThe last 7 days, day by day:\n$week\n\nWrite a warm, honest two-minute spoken " +
                        "recap of their week, to be read aloud. Plain sentences, no lists or symbols. What went well, what slipped, " +
                        "one pattern worth noticing, and one thing to carry into next week.", maxOutputTokens = 900)
            }.getOrElse { "Couldn't write the recap: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
            c.getSharedPreferences("recap", 0).edit().putString("text", recap).apply()
            busy = false
        }
    }) { Text(if (busy) "Writing…" else "Make this week's recap") }
    if (recap.isNotBlank()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            OutlinedButton(onClick = { speaker.speak(recap) }) { Text("> Listen") }
            OutlinedButton(onClick = { speaker.stop() }) { Text("Stop") }
        }
        Card { Text(recap, style = MaterialTheme.typography.bodyLarge) }
    }
    Text("A reminder arrives every Sunday evening.", style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PhotoTab() {
    val c = LocalContext.current
    val today = LocalDate.now().toString()
    var dates by remember { mutableStateOf(DailyPhoto.dates(c)) }
    val shot = remember { File(c.cacheDir, "daily_shot.jpg") }
    val uri = remember { FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", shot) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) { shot.copyTo(DailyPhoto.file(c, today), overwrite = true); dates = DailyPhoto.dates(c) }
    }
    Button(onClick = { camera.launch(uri) }) { Text(if (today in dates) "Retake today's photo" else "📷 Today's photo") }
    Text("${dates.size} days captured", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        dates.take(60).forEach { d ->
            val bmp = remember(d) {
                runCatching {
                    BitmapFactory.decodeFile(DailyPhoto.file(c, d).path, BitmapFactory.Options().apply { inSampleSize = 8 })?.asImageBitmap()
                }.getOrNull()
            }
            Column(Modifier.width(104.dp)) {
                bmp?.let { Image(it, d, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))) }
                Text(d.takeLast(5), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
