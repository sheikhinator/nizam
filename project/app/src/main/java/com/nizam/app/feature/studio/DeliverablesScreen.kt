package com.nizam.app.feature.studio

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.webkit.WebView
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private val IDEAS = listOf(
    Deliverables.Kind.IMAGE to "A cinematic poster of Lahore at night in the rain",
    Deliverables.Kind.THESIS to "Thesis: impact of load-shedding on small businesses in Pakistan",
    Deliverables.Kind.SLIDES to "Pitch deck for a home-baking business in Lahore",
    Deliverables.Kind.SHEET to "Monthly household budget for a family of five on 150,000 PKR",
    Deliverables.Kind.APP to "A tasbih counter app with targets and history",
    Deliverables.Kind.AUDIO to "A 5-minute explainer on how compound interest works",
    Deliverables.Kind.DOCUMENT to "A professional CV for a software engineer with 3 years' experience",
    Deliverables.Kind.CODE to "Python script that renames my photos by date taken",
)

/** The company's output shelf: order anything, watch the team make it, open and share the result. */
@Composable
fun DeliverablesScreen(container: AppContainer) {
    val c = LocalContext.current
    val shelf by Deliverables.shelf.collectAsState()
    val jobs by Deliverables.jobs.collectAsState()
    var open by remember { mutableStateOf<Deliverables.Item?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { Deliverables.load(c) } }

    open?.let { item -> Viewer(container, item, onBack = { open = null }); return }

    LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) { ModuleTitle("Deliverables", "Ask your company for anything. It makes the real file.") }
        item(span = { GridItemSpan(maxLineSpan) }) { OrderBox(container) }
        if (jobs.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { Text("Being made now", style = MaterialTheme.typography.titleSmall) }
            items(jobs.values.toList(), key = { "j" + it.id }, span = { GridItemSpan(maxLineSpan) }) { j -> JobCard(j) }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text(if (shelf.isEmpty()) "Your shelf is empty. Order something above." else "On the shelf · ${shelf.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        }
        items(shelf, key = { it.id }) { item -> ShelfTile(c, item) { open = item } }
    }
}

@Composable
private fun OrderBox(container: AppContainer) {
    var text by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(Deliverables.Kind.AUTO) }
    var deep by remember { mutableStateOf(false) }
    var urdu by remember { mutableStateOf(false) }
    Card(colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp))
                .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer)))
                .padding(16.dp).animateContentSize()
        ) {
            Text("What do you need?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text("A picture, a thesis, a deck, a budget, an app, a podcast… your team plans it, makes it and checks it.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().padding(top = 10.dp), placeholder = { Text("Describe it like you would to a person") }, minLines = 2, maxLines = 6)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Deliverables.Kind.entries.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text("${k.emoji} ${k.label}") }) }
            }
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = deep, onClick = { deep = !deep }, label = { Text(if (deep) "In depth" else "Standard") })
                FilterChip(selected = urdu, onClick = { urdu = !urdu }, label = { Text(if (urdu) "اردو" else "Same language as my request") })
            }
            Button(onClick = {
                Deliverables.order(container, text.trim(), kind, Deliverables.Options(language = if (urdu) "Urdu" else "", depth = if (deep) 3 else 2))
                text = ""
            }, enabled = text.isNotBlank(), modifier = Modifier.padding(top = 10.dp).fillMaxWidth()) { Text("Make it") }
            if (text.isBlank()) {
                Text("Ideas", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(top = 4.dp)) {
                    items(IDEAS) { (k, idea) ->
                        Box(Modifier.width(200.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f))
                            .clickable { text = idea; kind = k }.padding(10.dp)) {
                            Text("${k.emoji}  $idea", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JobCard(j: Deliverables.Job) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(j.kind.emoji, fontSize = 22.sp)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(j.request.lineSequence().first(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
                    Text(if (j.error != null) j.error else "${j.who} · ${j.stage}", style = MaterialTheme.typography.labelMedium,
                        color = if (j.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                if (j.error != null) TextButton(onClick = { Deliverables.dismiss(j.id) }) { Text("Dismiss") }
                else TextButton(onClick = { Deliverables.cancel(j.id) }) { Text("Stop") }
            }
            if (j.error == null) LinearProgressIndicator(progress = { j.fraction.coerceIn(0.02f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

@Composable
private fun ShelfTile(c: android.content.Context, item: Deliverables.Item, onOpen: () -> Unit) {
    val thumb by produceState<Bitmap?>(null, item.id) { value = withContext(Dispatchers.IO) { thumbnail(c, item) } }
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.8f).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                ?: Text(item.kind.emoji, fontSize = 44.sp)
            Text(item.kind.label, Modifier.align(Alignment.TopStart).padding(8.dp).clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 8.dp, vertical = 3.dp), color = Color.White, style = MaterialTheme.typography.labelSmall)
        }
        Column(Modifier.padding(10.dp)) {
            Text(item.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(item.created)), style = MaterialTheme.typography.labelSmall)
        }
    }
}

private fun thumbnail(c: android.content.Context, item: Deliverables.Item): Bitmap? = runCatching {
    val f = item.main(c) ?: return null
    when (f.extension.lowercase()) {
        "jpg", "jpeg", "png" -> BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = 4 })
        "pdf" -> renderPdfPage(f, 0, 360)
        else -> null
    }
}.getOrNull()

private fun renderPdfPage(f: File, index: Int, width: Int): Bitmap? =
    ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
        PdfRenderer(fd).use { r ->
            if (index >= r.pageCount) return null
            r.openPage(index).use { p ->
                val bmp = Bitmap.createBitmap(width, (width * p.height / p.width.toFloat()).toInt(), Bitmap.Config.ARGB_8888)
                bmp.eraseColor(android.graphics.Color.WHITE)
                p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }
    }

private fun pdfPages(f: File): Int = runCatching {
    ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }
}.getOrDefault(0)

// ---------------------------------------------------------------------- viewer

@Composable
private fun Viewer(container: AppContainer, item: Deliverables.Item, onBack: () -> Unit) {
    val c = LocalContext.current
    var file by remember { mutableStateOf(item.main(c)) }
    var notes by remember { mutableStateOf("") }
    var revising by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("← Shelf") }
            Text(item.title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            item.files.forEach { name -> FilterChip(selected = file?.name == name, onClick = { file = File(item.dir(c), name) }, label = { Text(name) }) }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            file?.let { f ->
                OutlinedButton(onClick = { runCatching { c.startActivity(android.content.Intent.createChooser(Deliverables.shareIntent(c, f), "Share").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Share") }
                OutlinedButton(onClick = { runCatching { c.startActivity(Deliverables.openIntent(c, f).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Open with…") }
            }
            OutlinedButton(onClick = { revising = !revising }) { Text("Ask for changes") }
            OutlinedButton(onClick = { Deliverables.delete(c, item); onBack() }) { Text("Delete") }
        }
        if (revising) Column(Modifier.padding(horizontal = 12.dp)) {
            OutlinedTextField(notes, { notes = it }, Modifier.fillMaxWidth(), placeholder = { Text("What should change? e.g. shorter, more formal, add a chapter on…") }, minLines = 2)
            Button(onClick = { Deliverables.revise(container, item, notes); revising = false; onBack() }, enabled = notes.isNotBlank(), modifier = Modifier.padding(vertical = 6.dp)) { Text("Send to the team") }
        }
        if (item.team.isNotEmpty()) Text("Made by ${item.team.joinToString(", ")}", Modifier.padding(horizontal = 16.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall)
        Box(Modifier.fillMaxSize().padding(top = 6.dp)) {
            val f = file
            when (f?.extension?.lowercase()) {
                null -> Text("Missing file", Modifier.padding(16.dp))
                "jpg", "jpeg", "png" -> ImageGallery(item, c)
                "pdf" -> PdfView(f)
                "csv" -> CsvView(f)
                "html" -> HtmlView(f)
                "wav", "mp3" -> AudioView(f, item)
                else -> TextView(f)
            }
        }
    }
}

@Composable
private fun ImageGallery(item: Deliverables.Item, c: android.content.Context) {
    val images = item.files.filter { it.endsWith(".jpg") || it.endsWith(".png") }.map { File(item.dir(c), it) }
    LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items(images) { f ->
            val bmp by produceState<Bitmap?>(null, f) { value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.absolutePath) } }
            Column {
                bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.FillWidth) }
                Row {
                    TextButton(onClick = { runCatching { c.startActivity(android.content.Intent.createChooser(Deliverables.shareIntent(c, f), "Share").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Share this one") }
                    TextButton(onClick = { runCatching { setWallpaper(c, f) } }) { Text("Set as wallpaper") }
                }
            }
        }
        item { Text(item.summary, style = MaterialTheme.typography.bodySmall) }
    }
}

private fun setWallpaper(c: android.content.Context, f: File) {
    android.app.WallpaperManager.getInstance(c).setBitmap(BitmapFactory.decodeFile(f.absolutePath))
    com.nizam.app.ui.components.Feedback.show("Wallpaper set")
}

@Composable
private fun PdfView(f: File) {
    val pages = remember(f) { pdfPages(f) }
    LazyColumn(Modifier.fillMaxSize().background(Color(0xFF2A2D35)), contentPadding = PaddingValues(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items((0 until pages).toList()) { i ->
            val bmp by produceState<Bitmap?>(null, f, i) { value = withContext(Dispatchers.IO) { runCatching { renderPdfPage(f, i, 1080) }.getOrNull() } }
            Box(Modifier.fillMaxWidth().aspectRatio(if (bmp != null) bmp!!.width / bmp!!.height.toFloat() else 0.707f).background(Color.White)) {
                bmp?.let { Image(it.asImageBitmap(), "Page ${i + 1}", Modifier.fillMaxSize()) }
            }
        }
    }
}

@Composable
private fun CsvView(f: File) {
    val rows = remember(f) { Csv.parse(f.readText()) }
    val h = rememberScrollState()
    LazyColumn(Modifier.fillMaxSize().horizontalScroll(h), contentPadding = PaddingValues(12.dp)) {
        items(rows.withIndex().toList()) { (i, r) ->
            Row(Modifier.background(if (i == 0) MaterialTheme.colorScheme.primaryContainer else if (i % 2 == 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f) else Color.Transparent)) {
                r.forEach { cell ->
                    Text(cell, Modifier.width(130.dp).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant).padding(6.dp),
                        style = MaterialTheme.typography.bodySmall, fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal, maxLines = 3)
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun HtmlView(f: File) {
    AndroidView(factory = { ctx ->
        WebView(ctx).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            // the app runs in a blank origin with no network base and no access to Atlas
            loadDataWithBaseURL("https://atlas.local/", f.readText(), "text/html", "utf-8", null)
        }
    }, modifier = Modifier.fillMaxSize())
}

@Composable
private fun AudioView(f: File, item: Deliverables.Item) {
    val c = LocalContext.current
    var playing by remember { mutableStateOf(false) }
    val player = remember(f) { MediaPlayer().apply { setDataSource(f.absolutePath); prepare(); setOnCompletionListener { playing = false } } }
    DisposableEffect(f) { onDispose { player.release() } }
    val script = remember(item) { File(item.dir(c), "script.md").takeIf { it.exists() }?.readText().orEmpty() }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Box(Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(24.dp))
            .background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.tertiary))), contentAlignment = Alignment.Center) {
            Button(onClick = { if (playing) player.pause() else player.start(); playing = !playing }) { Text(if (playing) "⏸ Pause" else "▶ Play · ${player.duration / 60000}:${"%02d".format(player.duration / 1000 % 60)}") }
        }
        Spacer(Modifier.height(12.dp))
        SelectionContainer { Text(script, Modifier.padding(4.dp), style = MaterialTheme.typography.bodyMedium) }
    }
}

@Composable
private fun TextView(f: File) {
    val text = remember(f) { f.readText() }
    LazyColumn(contentPadding = PaddingValues(14.dp)) {
        item {
            SelectionContainer {
                Text(text, style = if (f.extension == "md") MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            }
        }
    }
}

@Suppress("unused") private val keepSize = Modifier.size(1.dp)
