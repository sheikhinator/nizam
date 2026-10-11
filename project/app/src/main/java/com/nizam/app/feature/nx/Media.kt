package com.nizam.app.feature.nx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.nizam.app.AppContainer
import com.nizam.app.feature.brain2.Injection
import com.nizam.app.feature.brain2.Places
import com.nizam.app.net.Ai
import com.nizam.app.net.Http
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.EmptySpec
import com.nizam.app.ui.design.EmptyState
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object Media {
    val all: List<Spec> = listOf(
        ScreenSpec("docchat", "Chat with a document", "PDFs, photos of pages, text files: ask anything", Icons.Outlined.FileOpen, Hub.Learn, "pdf document ask chat file ocr scan book table handwriting") { c, _ -> DocChatScreen(c) },
        ScreenSpec("roomscan", "Room scan", "Photo a room; Atlas remembers what's in it", Icons.Outlined.CameraAlt, Hub.Home, "scan room objects inventory where") { _, _ -> RoomScanScreen() },
        ScreenSpec("scrub", "Photo privacy scrub", "Remove location and camera details before sharing", Icons.Outlined.LocationOff, Hub.Tools, "exif metadata gps privacy photo") { _, _ -> ScrubScreen() },
        ScreenSpec("passport", "Passport photo", "35×45 mm, single or a 4×6 print sheet", Icons.Outlined.Face, Hub.Tools, "passport photo id visa cnic picture") { _, _ -> PassportScreen() },
        ScreenSpec("whiteboard", "Whiteboard & signature", "Sketch, explain, or sign; save as image", Icons.Outlined.Draw, Hub.Create, "draw sketch signature whiteboard") { _, _ -> WhiteboardScreen() },
        ScreenSpec("voicefx", "Voice changer", "Record, then play back as chipmunk, giant, slow-mo", Icons.Outlined.GraphicEq, Hub.Create, "voice changer funny meme pitch") { _, _ -> VoiceFxScreen() },
        ScreenSpec("food", "Food scanner", "Barcode to ingredients, additives and nutrition", Icons.Outlined.QrCodeScanner, Hub.Health, "barcode food nutrition open food facts halal") { c, _ -> FoodScreen(c) },
        ScreenSpec("export", "Portable Atlas", "Take your data with you as one file", Icons.Outlined.Archive, Hub.Tools, "export data backup portable life os") { _, _ -> ExportScreen() },
        ScreenSpec("livesubs", "Live subtitles", "Translated subtitles over any video or call audio", Icons.Outlined.Subtitles, Hub.Media, "subtitles translate chinese video captions live") { c, _ -> LiveSubsScreen(c) },
    )
}

private fun decode(c: Context, uri: Uri, max: Int = 2000): Bitmap? = runCatching {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
    var sample = 1; while (opts.outWidth / sample > max || opts.outHeight / sample > max) sample *= 2
    val bmp = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
    val rot = c.contentResolver.openInputStream(uri)?.use { android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1) } ?: 1
    val deg = when (rot) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
    if (deg == 0f) bmp else Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
}.getOrNull()

private fun savePng(c: Context, b: Bitmap, name: String, jpeg: Boolean = false): File =
    Share.out(c, name).also { f -> f.outputStream().use { b.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 92, it) } }

// ─────────────────────────────── Document chat ───────────────────────────────
object DocText {
    suspend fun extract(c: Context, uri: Uri, progress: (String) -> Unit): String = withContext(Dispatchers.IO) {
        val type = c.contentResolver.getType(uri).orEmpty()
        val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        when {
            type.startsWith("text") || type.contains("json") || type.contains("csv") -> c.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
            type.startsWith("image") -> decode(c, uri)?.let { ocr.process(InputImage.fromBitmap(it, 0)).await().text }.orEmpty()
            type.contains("pdf") -> {
                val pfd = c.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext ""
                val r = PdfRenderer(pfd)
                val sb = StringBuilder()
                try {
                    val n = minOf(r.pageCount, 40)
                    for (i in 0 until n) {
                        progress("Reading page ${i + 1} of $n")
                        val page = r.openPage(i)
                        val bmp = Bitmap.createBitmap(page.width * 2, page.height * 2, Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        page.close()
                        val t = runCatching { ocr.process(InputImage.fromBitmap(bmp, 0)).await().text }.getOrDefault("")
                        bmp.recycle()
                        sb.append("— page ${i + 1} —\n").append(t).append("\n\n")
                    }
                } finally { r.close(); pfd.close() }
                sb.toString()
            }
            else -> c.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
        }
    }
}

@Composable
fun DocChatScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val msgs = remember { mutableStateListOf<Pair<Boolean, String>>() }
    var input by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            name = uri.lastPathSegment?.substringAfterLast('/') ?: "Document"; msgs.clear(); status = "Reading…"
            scope.launch {
                text = runCatching { DocText.extract(ctx, uri) { status = it } }.getOrElse { "" }
                status = if (text.isBlank()) "Couldn't read any text from that file." else "${text.split(Regex("\\s+")).size} words read. Ask away."
            }
        }
    }
    fun ask(q: String) {
        msgs += true to q; input = ""
        scope.launch {
            val a = runCatching {
                Ai.generate(container.settingsRepository, "Document:\n${Injection.guard(text.take(28000))}\n\nPrevious:\n" +
                    msgs.dropLast(1).takeLast(6).joinToString("\n") { (me, t) -> (if (me) "Q: " else "A: ") + t.take(400) } + "\n\nQuestion: $q",
                    system = "Answer only from the document. Quote the relevant line and give the page if shown. Say plainly when the document doesn't say.", maxOutputTokens = 900)
            }.getOrElse { it.message ?: "Failed" }
            msgs += false to a
        }
    }
    AtlasScreen(title = name ?: "Chat with a document", subtitle = status ?: "PDF (up to 40 pages), a photo of a page, or a text file", hub = Hub.Learn) {
        item("pick") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Button(onClick = { pick.launch(arrayOf("application/pdf", "image/*", "text/*")) }) { Text(if (name == null) "Choose file" else "Another file") }
            if (text.isNotBlank()) OutlinedButton(onClick = { Share.text(ctx, text, name ?: "Text") }) { Text("Copy text out") }
        } }
        if (text.isNotBlank()) {
            if (msgs.isEmpty()) item("quick") { Row(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                listOf("Summarise it", "Key dates and amounts", "Tables as CSV", "What should I do?").forEach { q -> Capsule(q, false) { ask(q) } }
            } }
            msgs.forEachIndexed { i, (me, t) -> item("m$i") {
                Group(Modifier.padding(horizontal = Space.page, vertical = 4.dp)) {
                    if (me) Text(t, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) else Md(t)
                }
            } }
            item("in") { Row(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(input, { input = it }, Modifier.weight(1f), placeholder = { Text("Ask about the document") })
                Button(onClick = { ask(input.trim()) }, enabled = input.isNotBlank()) { Text("Ask") }
            } }
        }
    }
}

// ─────────────────────────────── Room scan ───────────────────────────────
@Composable
fun RoomScanScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var bmp by remember { mutableStateOf<Bitmap?>(null) }
    var labels by remember { mutableStateOf<List<String>>(emptyList()) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var room by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf("") }
    fun analyse(b: Bitmap) {
        bmp = b
        scope.launch {
            labels = runCatching { ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.55f).build()).process(InputImage.fromBitmap(b, 0)).await().map { it.text } }.getOrDefault(emptyList())
            picked = labels.toSet()
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { b -> if (b != null) analyse(b) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> u?.let { decode(ctx, it, 1200) }?.let { analyse(it) } }
    AtlasScreen(title = "Room scan", subtitle = "What's in this room goes into \"Where is it?\"", hub = Hub.Home) {
        item("b") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Button(onClick = { camera.launch(null) }) { Text("Take photo") }
            OutlinedButton(onClick = { gallery.launch("image/*") }) { Text("From gallery") }
        } }
        bmp?.let { b -> item("img") { Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().padding(Space.page).heightIn(max = 260.dp)) } }
        if (bmp != null) {
            item("room") { OutlinedTextField(room, { room = it }, Modifier.fillMaxWidth().padding(horizontal = Space.page), label = { Text("Which room / spot?") }, placeholder = { Text("Store room, top shelf") }, singleLine = true) }
            if (labels.isEmpty()) item("none") { Text("Nothing recognised automatically — add items below.", Modifier.padding(Space.page)) }
            labels.forEach { l -> item("l$l") {
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.page).pressable { picked = if (l in picked) picked - l else picked + l }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(l in picked, null); Text(l, style = MaterialTheme.typography.bodyLarge)
                }
            } }
            item("extra") { OutlinedTextField(extra, { extra = it }, Modifier.fillMaxWidth().padding(Space.page), label = { Text("Other things here (comma separated)") }) }
            item("save") { Button(onClick = {
                val all = picked + extra.split(',').map { it.trim() }.filter { it.isNotBlank() }
                all.forEach { Places.remember(ctx, it, room.trim()) }
                Feedback.show("${all.size} things remembered in $room"); bmp = null; labels = emptyList(); extra = ""
            }, enabled = room.isNotBlank() && (picked.isNotEmpty() || extra.isNotBlank()), modifier = Modifier.padding(horizontal = Space.page)) { Text("Remember these") } }
        }
    }
}

// ─────────────────────────────── Privacy scrub ───────────────────────────────
@Composable
fun ScrubScreen() {
    val ctx = LocalContext.current
    var report by remember { mutableStateOf<List<String>>(emptyList()) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        val out = mutableListOf<File>(); val rep = mutableListOf<String>()
        uris.forEachIndexed { i, u ->
            runCatching {
                val found = ctx.contentResolver.openInputStream(u)?.use { s -> val e = android.media.ExifInterface(s)
                    listOfNotNull(FloatArray(2).let { ll -> @Suppress("DEPRECATION") if (e.getLatLong(ll)) "location ${"%.4f".format(ll[0])}, ${"%.4f".format(ll[1])}" else null }, e.getAttribute(android.media.ExifInterface.TAG_MODEL)?.let { "camera $it" },
                        e.getAttribute(android.media.ExifInterface.TAG_DATETIME)?.let { "taken $it" }) }.orEmpty()
                val b = decode(ctx, u, 4096) ?: return@runCatching
                out += savePng(ctx, b, "clean_${i + 1}.jpg", jpeg = true)
                rep += "Photo ${i + 1}: " + if (found.isEmpty()) "no hidden details" else "removed " + found.joinToString(", ")
            }
        }
        files = out; report = rep
    }
    AtlasScreen(title = "Photo privacy scrub", subtitle = "Photos can carry your home's location. This strips it.", hub = Hub.Tools) {
        item("b") { Button(onClick = { pick.launch("image/*") }, modifier = Modifier.padding(horizontal = Space.page)) { Text("Choose photos") } }
        report.forEachIndexed { i, r -> item("r$i") { ListRow(r, icon = Icons.Outlined.LocationOff, tint = Hub.Tools.tint()) } }
        if (files.isNotEmpty()) item("s") { Button(onClick = {
            val uris = ArrayList(files.map { androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", it) })
            ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND_MULTIPLE).setType("image/jpeg")
                .putParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, uris).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share clean photos")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }, modifier = Modifier.padding(Space.page)) { Text("Share ${files.size} clean photo${if (files.size == 1) "" else "s"}") } }
    }
}

// ─────────────────────────────── Passport photo ───────────────────────────────
@Composable
fun PassportScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var crop by remember { mutableStateOf<Bitmap?>(null) }
    var msg by remember { mutableStateOf("Use a plain light wall, face the camera, even light.") }
    fun make(src: Bitmap) {
        scope.launch {
            val faces = runCatching { FaceDetection.getClient(FaceDetectorOptions.Builder().setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).build())
                .process(InputImage.fromBitmap(src, 0)).await() }.getOrDefault(emptyList())
            val f = faces.maxByOrNull { it.boundingBox.width() }
            if (f == null) { msg = "No face found. Try a clearer, front-facing photo."; return@launch }
            // face box ≈ chin to hairline; a passport head is ~70% of the 45 mm height
            val box = f.boundingBox
            val h = (box.height() * 1.25f / 0.70f)
            val w = h * 35f / 45f
            val cx = box.exactCenterX(); val top = box.top - box.height() * 0.45f
            val l = (cx - w / 2).toInt().coerceAtLeast(0); val t = top.toInt().coerceAtLeast(0)
            val cw = w.toInt().coerceAtMost(src.width - l); val ch = h.toInt().coerceAtMost(src.height - t)
            if (cw < 50 || ch < 50) { msg = "Move back a little so your shoulders show."; return@launch }
            crop = Bitmap.createScaledBitmap(Bitmap.createBitmap(src, l, t, cw, ch), 413, 531, true)
            msg = "35×45 mm at 300 dpi. Check your eyes are level and the background is plain."
        }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { b -> if (b != null) make(b) }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> u?.let { decode(ctx, it, 3000) }?.let { make(it) } }
    AtlasScreen(title = "Passport photo", subtitle = msg, hub = Hub.Tools) {
        item("b") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Button(onClick = { gallery.launch("image/*") }) { Text("Choose photo") }
            OutlinedButton(onClick = { camera.launch(null) }) { Text("Camera") }
        } }
        crop?.let { c -> item("img") {
            Image(c.asImageBitmap(), null, Modifier.padding(Space.page).size(width = 175.dp, height = 225.dp))
        }
        item("save") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Button(onClick = { Share.file(ctx, savePng(ctx, c, "passport_photo.jpg", jpeg = true), "image/jpeg") }) { Text("Single photo") }
            OutlinedButton(onClick = {
                val sheet = Bitmap.createBitmap(1200, 1800, Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(sheet); cv.drawColor(android.graphics.Color.WHITE)
                for (r in 0 until 3) for (col in 0 until 2) cv.drawBitmap(c, 110f + col * (413 + 150), 60f + r * (531 + 60), null)
                Share.file(ctx, savePng(ctx, sheet, "passport_sheet_4x6.jpg", jpeg = true), "image/jpeg")
            }) { Text("4×6 print sheet (6)") }
        } } }
    }
}

// ─────────────────────────────── Whiteboard / signature ───────────────────────────────
@Composable
fun WhiteboardScreen() {
    val ctx = LocalContext.current
    val strokes = remember { mutableStateListOf<Pair<Color, List<Offset>>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var color by remember { mutableStateOf(Color.Black) }
    var signature by remember { mutableStateOf(false) }
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    val colors = listOf(Color.Black, Color(0xFF1E5BD8), Color(0xFFD9342B), Color(0xFF2E9E5B))
    AtlasScreen(title = "Whiteboard", subtitle = if (signature) "Sign inside the box — saves with a clear background" else "Draw with your finger", hub = Hub.Create) {
        item("tools") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            Capsule("Board", !signature) { signature = false }; Capsule("Signature", signature) { signature = true; color = Color(0xFF1E3A8A) }
            colors.forEach { c -> Box(Modifier.size(28.dp).background(c, androidx.compose.foundation.shape.CircleShape).pressable { color = c }) }
        } }
        item("canvas") {
            Canvas(Modifier.fillMaxWidth().padding(Space.page).aspectRatio(if (signature) 2.4f else 0.8f).background(Color.White, androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                .pointerInput(Unit) {
                    detectDragGestures(onDragStart = { current = listOf(it) }, onDragEnd = { strokes += color to current; current = emptyList() }) { ch, _ -> current = current + ch.position }
                }) {
                canvasSize = this.size
                fun draw(pts: List<Offset>, c: Color) {
                    if (pts.size < 2) return
                    val p = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                    drawPath(p, c, style = Stroke(width = if (signature) 5f else 7f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                strokes.forEach { (c, pts) -> draw(pts, c) }
                draw(current, color)
            }
        }
        item("act") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            OutlinedButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }) { Text("Undo") }
            OutlinedButton(onClick = { strokes.clear() }) { Text("Clear") }
            Button(onClick = {
                if (canvasSize.width <= 0f) return@Button
                val b = Bitmap.createBitmap(canvasSize.width.toInt(), canvasSize.height.toInt(), Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(b); if (!signature) cv.drawColor(android.graphics.Color.WHITE)
                strokes.forEach { (c, pts) ->
                    val paint = android.graphics.Paint().apply { this.color = c.toArgb(); strokeWidth = if (signature) 5f else 7f; style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND; isAntiAlias = true }
                    val path = android.graphics.Path(); pts.firstOrNull()?.let { path.moveTo(it.x, it.y) }; pts.drop(1).forEach { path.lineTo(it.x, it.y) }; cv.drawPath(path, paint)
                }
                Share.file(ctx, savePng(ctx, b, if (signature) "signature.png" else "whiteboard.png"), "image/png")
            }, enabled = strokes.isNotEmpty()) { Text("Save / share") }
        } }
    }
}

// ─────────────────────────────── Voice changer ───────────────────────────────
@Composable
fun VoiceFxScreen() {
    val ctx = LocalContext.current
    val file = remember { File(ctx.cacheDir, "voicefx.m4a") }
    var rec by remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    var has by remember { mutableStateOf(file.exists()) }
    val player = remember { androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) { onDispose { player.release(); runCatching { rec?.release() } } }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun play(speed: Float, pitch: Float) {
        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(file)))
        player.playbackParameters = androidx.media3.common.PlaybackParameters(speed, pitch)
        player.prepare(); player.play()
    }
    AtlasScreen(title = "Voice changer", subtitle = "Say something silly, then pick a voice", hub = Hub.Create) {
        item("rec") { Button(onClick = {
            if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                perm.launch(android.Manifest.permission.RECORD_AUDIO); return@Button }
            val r = rec
            if (r == null) {
                rec = (if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(ctx) else @Suppress("DEPRECATION") android.media.MediaRecorder()).apply {
                    setAudioSource(android.media.MediaRecorder.AudioSource.MIC); setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                    setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC); setOutputFile(file.absolutePath); prepare(); start()
                }
            } else { runCatching { r.stop() }; r.release(); rec = null; has = true }
        }, modifier = Modifier.fillMaxWidth().padding(Space.page).height(64.dp)) { Text(if (rec == null) "● Record" else "■ Stop") } }
        if (has && rec == null) {
            item("h") { SectionHeader("Play as") }
            listOf(Triple("Chipmunk", 1.2f, 1.9f), Triple("Giant", 0.85f, 0.55f), Triple("Robot-ish", 1f, 0.75f), Triple("Slow-mo", 0.6f, 0.8f),
                Triple("Fast talker", 1.8f, 1.1f), Triple("Normal", 1f, 1f)).forEach { (n, s, p) ->
                item(n) { ListRow(n, icon = Icons.Outlined.GraphicEq, tint = Hub.Create.tint()) { play(s, p) } }
            }
        }
    }
}

// ─────────────────────────────── Food scanner ───────────────────────────────
@Composable
fun FoodScreen(container: AppContainer) {
    val scope = rememberCoroutineScope()
    var code by remember { mutableStateOf("") }
    var out by remember { mutableStateOf<String?>(null) }
    var ingredients by remember { mutableStateOf("") }
    var verdict by remember { mutableStateOf<String?>(null) }
    fun lookup(c: String) {
        code = c; out = "Looking up…"; verdict = null
        scope.launch {
            out = runCatching {
                val j = JSONObject(Http.getJson("https://world.openfoodfacts.org/api/v2/product/$c.json"))
                val p = j.optJSONObject("product") ?: return@runCatching "Not in the Open Food Facts database yet."
                ingredients = p.optString("ingredients_text_en").ifBlank { p.optString("ingredients_text") }
                val n = p.optJSONObject("nutriments") ?: JSONObject()
                buildString {
                    append("## ${p.optString("product_name").ifBlank { "Unknown product" }}\n${p.optString("brands")}\n\n")
                    p.optString("nutriscore_grade").takeIf { it.isNotBlank() && it != "unknown" }?.let { append("**Nutri-Score ${it.uppercase()}** · ") }
                    p.optInt("nova_group").takeIf { it > 0 }?.let { append("NOVA $it (${if (it == 4) "ultra-processed" else "processed level $it"})") }
                    append("\n\nPer 100 g: ${n.optDouble("energy-kcal_100g", 0.0).toInt()} kcal · sugar ${n.optDouble("sugars_100g", 0.0)} g · fat ${n.optDouble("fat_100g", 0.0)} g · salt ${n.optDouble("salt_100g", 0.0)} g\n\n")
                    p.optJSONArray("additives_tags")?.let { a -> if (a.length() > 0) append("**Additives:** ${(0 until a.length()).joinToString { a.getString(it).substringAfter(':').uppercase() }}\n\n") }
                    p.optString("allergens").takeIf { it.isNotBlank() }?.let { append("**Allergens:** ${it.replace("en:", "")}\n\n") }
                    if (ingredients.isNotBlank()) append("**Ingredients:** $ingredients")
                }
            }.getOrElse { "Couldn't look it up: ${it.message}" }
        }
    }
    val scan = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { b ->
        if (b != null) scope.launch {
            val v = runCatching { com.google.mlkit.vision.barcode.BarcodeScanning.getClient().process(InputImage.fromBitmap(b, 0)).await().firstOrNull()?.rawValue }.getOrNull()
            if (v != null) lookup(v) else Feedback.show("No barcode seen — fill the frame with it")
        }
    }
    AtlasScreen(title = "Food scanner", subtitle = "From the open Open Food Facts database", hub = Hub.Health) {
        item("in") { Row(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(code, { code = it.filter { c -> c.isDigit() } }, Modifier.weight(1f), placeholder = { Text("Barcode number") }, singleLine = true)
            Button(onClick = { lookup(code) }, enabled = code.length >= 8) { Text("Look up") }
        } }
        item("scan") { OutlinedButton(onClick = { scan.launch(null) }, modifier = Modifier.padding(horizontal = Space.page)) { Text("Scan with camera") } }
        out?.let { o -> item("o") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) { Md(o) } } }
        if (ingredients.isNotBlank()) item("ai") { Button(onClick = {
            scope.launch { verdict = runCatching { Ai.generate(container.settingsRepository, "Ingredients: $ingredients\nExplain additives, halal concerns (gelatin, E120, E471, alcohol), allergens, and an overall healthy/occasional/avoid verdict. Short.", maxOutputTokens = 600) }.getOrElse { it.message } }
        }, modifier = Modifier.padding(horizontal = Space.page)) { Text("Halal & health check") } }
        verdict?.let { v -> item("v") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) { Md(v) } } }
    }
}

// ─────────────────────────────── Portable Atlas ───────────────────────────────
@Composable
fun ExportScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    AtlasScreen(title = "Portable Atlas", subtitle = "Your lists, logs and notes in one zip you own", hub = Hub.Tools) {
        item("what") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            Text("Included: ledgers, trackers, places, cards, goals, read-later and other everyday data as readable JSON.", style = MaterialTheme.typography.bodyMedium)
            Text("Never included: the hidden vault, the private browser, passwords, encrypted items and API keys.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = Space.s))
        } }
        item("go") { Button(onClick = {
            busy = true
            scope.launch {
                runCatching {
                    val f = withContext(Dispatchers.IO) {
                        val out = Share.out(ctx, "atlas-export-${java.time.LocalDate.now()}.zip")
                        val skip = Regex("(?i)vault|hidden|private|password|secret|key|sealed|browser|clipboard|dream|letters")
                        ZipOutputStream(out.outputStream()).use { z ->
                            ctx.filesDir.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(".json") && !skip.containsMatchIn(it.name) }.forEach { f ->
                                val t = f.readText()
                                if (t.trimStart().startsWith("[") || t.trimStart().startsWith("{")) { z.putNextEntry(ZipEntry(f.name)); z.write(t.toByteArray()); z.closeEntry() }
                            }
                        }
                        out
                    }
                    Share.file(ctx, f, "application/zip")
                }.onFailure { Feedback.show("Export failed: ${it.message}") }
                busy = false
            }
        }, enabled = !busy, modifier = Modifier.padding(Space.page)) { Text(if (busy) "Packing…" else "Export") } }
    }
}

// ─────────────────────────────── Live subtitles ───────────────────────────────
@Composable
fun LiveSubsScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val p = remember { ctx.getSharedPreferences("livesubs", 0) }
    var from by remember { mutableStateOf(p.getString("from", "zh-CN")!!) }
    var to by remember { mutableStateOf(p.getString("to", "English")!!) }
    var running by remember { mutableStateOf(LiveSubsService.running) }
    var offline by remember { mutableStateOf(p.getBoolean("offline", false)) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val langs = listOf("zh-CN" to "Chinese", "ja-JP" to "Japanese", "ko-KR" to "Korean", "hi-IN" to "Hindi", "ar-SA" to "Arabic", "tr-TR" to "Turkish",
        "es-ES" to "Spanish", "fr-FR" to "French", "de-DE" to "German", "ru-RU" to "Russian", "en-US" to "English", "ur-PK" to "Urdu")
    AtlasScreen(title = "Live subtitles", subtitle = "Play a video in any app. Atlas listens and shows translated subtitles on top.", hub = Hub.Media) {
        item("fh") { SectionHeader("They speak") }
        item("f") { androidx.compose.foundation.lazy.LazyRow(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            items(langs.size) { i -> val (code, n) = langs[i]; Capsule(n, from == code) { from = code; p.edit().putString("from", code).apply() } } } }
        item("th") { SectionHeader("Show me") }
        item("t") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            listOf("English", "Urdu", "Roman Urdu").forEach { l -> Capsule(l, to == l) { to = l; p.edit().putString("to", l).apply() } } } }
        item("off") { Row(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            Text("Work offline (downloads the languages once; Roman Urdu still uses AI)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            androidx.compose.material3.Switch(offline, { offline = it; p.edit().putBoolean("offline", it).apply() })
        } }
        item("go") { Button(onClick = {
            if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                perm.launch(android.Manifest.permission.RECORD_AUDIO); return@Button }
            if (!android.provider.Settings.canDrawOverlays(ctx)) {
                ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)); return@Button }
            if (running) LiveSubsService.stop(ctx) else LiveSubsService.start(ctx, from, to)
            running = !running
        }, modifier = Modifier.fillMaxWidth().padding(Space.page).height(56.dp)) { Text(if (running) "Stop subtitles" else "Start subtitles") } }
        item("how") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            Text("How it works", style = MaterialTheme.typography.titleSmall)
            Text("• Keep the phone's speaker on (not headphones) so the mic can hear the video.\n• Drag the subtitle bar anywhere; tap it to hide the original line.\n" +
                "• Recognition for the spoken language must be available in Google speech services (Settings › Speech › Offline languages helps).\n" +
                "• Translation uses your AI keys; lines arrive about a second behind.", style = MaterialTheme.typography.bodyMedium)
        } }
    }
}
