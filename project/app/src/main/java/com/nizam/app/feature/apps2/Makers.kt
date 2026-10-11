package com.nizam.app.feature.apps2

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nizam.app.AppContainer
import com.nizam.app.feature.brain2.Calc
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.Md
import com.nizam.app.feature.nx.Multimodal
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Share
import com.nizam.app.feature.nx.Spec
import com.nizam.app.feature.nx.num
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object Makers {
    val all: List<Spec> = listOf(
        ScreenSpec("sheets", "Sheets", "Spreadsheets with formulas: =SUM(A1:A9), =B2*12%", Icons.Outlined.TableChart, Hub.Work, "excel spreadsheet sheet table budget formula office") { _, _ -> SheetsScreen() },
        ScreenSpec("photoedit", "Photo editor", "Light, colour, filters, crop for posts, captions", Icons.Outlined.Tune, Hub.Create, "photo editor filter crop brightness instagram") { _, _ -> PhotoEditorScreen() },
        ScreenSpec("videoedit", "Video editor", "Trim, mute, rotate, speed, shrink for WhatsApp", Icons.Outlined.Movie, Hub.Create, "video editor trim mute rotate speed compress") { _, _ -> VideoEditorScreen() },
        ScreenSpec("audiostudio", "Audio studio", "Record, keep, play at any speed, transcribe", Icons.Outlined.Mic, Hub.Create, "voice recorder audio record lecture transcribe") { c, _ -> AudioStudioScreen(c) },
        ScreenSpec("files", "Files", "Browse any folder, find big files, share or delete", Icons.Outlined.Folder, Hub.Tools, "files file manager drive folder documents downloads") { _, _ -> FilesScreen() },
        ScreenSpec("myshorts", "My shorts", "Your own short videos as a swipe feed", Icons.Outlined.Slideshow, Hub.Media, "shorts reels short videos feed") { _, _ -> ShortsScreen() },
    )
}

private fun toGallery(c: Context, b: Bitmap, name: String) = runCatching {
    val v = android.content.ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (android.os.Build.VERSION.SDK_INT >= 29) put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Atlas")
    }
    c.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)?.let { u -> c.contentResolver.openOutputStream(u)?.use { b.compress(Bitmap.CompressFormat.JPEG, 95, it) } } != null
}.getOrDefault(false)

// ─────────────────────────────── Sheets ───────────────────────────────
private const val COLS = 8
private const val ROWS = 40

object SheetEngine {
    private val RANGE = Regex("([A-Ha-h])(\\d+):([A-Ha-h])(\\d+)")
    private val REF = Regex("(?<![A-Za-z])([A-Ha-h])(\\d+)(?![\\d(])")
    fun value(cells: Map<String, String>, key: String, seen: Set<String> = emptySet()): String {
        val raw = cells[key].orEmpty()
        if (!raw.startsWith("=")) return raw
        if (key in seen) return "#CYCLE"
        return runCatching {
            fun num(k: String) = value(cells, k.uppercase(), seen + key).replace(",", "").toDoubleOrNull() ?: 0.0
            var expr = raw.drop(1)
            expr = RANGE.replace(expr) { m ->
                val c1 = m.groupValues[1].uppercase()[0]; val r1 = m.groupValues[2].toInt(); val c2 = m.groupValues[3].uppercase()[0]; val r2 = m.groupValues[4].toInt()
                (minOf(c1, c2)..maxOf(c1, c2)).flatMap { c -> (minOf(r1, r2)..maxOf(r1, r2)).map { r -> num("$c$r").toString() } }.joinToString(",")
            }
            expr = REF.replace(expr) { m -> num(m.groupValues[1] + m.groupValues[2]).toString() }
            Calc.fmt(Calc.eval(expr.replace("average", "avg", true)))
        }.getOrElse { "#ERR" }
    }
}

@Composable
fun SheetsScreen() {
    val ctx = LocalContext.current
    val store = remember { JsonStore(ctx, "sheets") }
    var sheets by remember { mutableStateOf(store.all()) }
    var openId by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf("") }
    val id = openId
    if (id != null) {
        val doc = remember(id) { store.all().firstOrNull { it.optString("id") == id } ?: JSONObject() }
        val cells = remember(id) { mutableStateMapOf<String, String>().apply { doc.optJSONObject("cells")?.let { j -> j.keys().forEach { k -> put(k, j.optString(k)) } } } }
        var sel by remember { mutableStateOf("A1") }
        var edit by remember { mutableStateOf(cells["A1"].orEmpty()) }
        fun save() { store.update(id) { it.put("cells", JSONObject(cells.toMap() as Map<*, *>)) } }
        AtlasScreen(title = doc.optString("name", "Sheet"), subtitle = "Tap a cell, type a value or a formula", hub = Hub.Work, actions = {
            Text("Done", Modifier.padding(Space.s).pressable { save(); sheets = store.all(); openId = null }, style = MaterialTheme.typography.labelLarge) }) {
            item("bar") { Row(Modifier.padding(horizontal = Space.page), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(sel, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                OutlinedTextField(edit, { edit = it; if (it.isBlank()) cells.remove(sel) else cells[sel] = it; save() }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("123 · text · =SUM(A1:A5) · =B2*1.18") })
            } }
            item("fx") { LazyRow(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                val fx = listOf("=SUM(", "=AVG(", "=MIN(", "=MAX(", "=ROUND(", ":", "*", "/", "%"); items(fx.size) { i -> Capsule(fx[i], false) { edit += fx[i]; cells[sel] = edit; save() } } } }
            item("grid") {
                Column(Modifier.padding(horizontal = Space.page).horizontalScroll(rememberScrollState())) {
                    Row { Box(Modifier.width(36.dp)); ('A' until 'A' + COLS).forEach { c -> Text("$c", Modifier.width(96.dp).padding(4.dp), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                    (1..ROWS).forEach { r -> Row {
                        Text("$r", Modifier.width(36.dp).padding(4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                        ('A' until 'A' + COLS).forEach { c -> val k = "$c$r"
                            val shown = SheetEngine.value(cells, k)
                            Text(shown, Modifier.width(96.dp).height(34.dp).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant)
                                .background(if (k == sel) Hub.Work.tint().copy(alpha = 0.18f) else Color.Transparent)
                                .pressable { sel = k; edit = cells[k].orEmpty() }.padding(horizontal = 4.dp, vertical = 6.dp),
                                maxLines = 1, style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (cells[k].orEmpty().startsWith("=")) FontWeight.SemiBold else FontWeight.Normal)
                        }
                    } }
                }
            }
            item("exp") { Row(Modifier.padding(Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedButton(onClick = {
                    val csv = (1..ROWS).map { r -> ('A' until 'A' + COLS).joinToString(",") { c -> SheetEngine.value(cells, "$c$r").let { v -> if (v.contains(',')) "\"$v\"" else v } } }
                        .dropLastWhile { it.replace(",", "").isBlank() }.joinToString("\n")
                    val f = Share.out(ctx, doc.optString("name", "sheet").replace(Regex("[^A-Za-z0-9 _-]"), "") + ".csv"); f.writeText(csv); Share.file(ctx, f, "text/csv")
                }) { Text("Export CSV") }
                OutlinedButton(onClick = { cells.clear(); save(); edit = "" }) { Text("Clear") }
            } }
        }
        return
    }
    AtlasScreen(title = "Sheets", subtitle = "${sheets.size} sheets", hub = Hub.Work) {
        item("new") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(newName, { newName = it }, Modifier.weight(1f), placeholder = { Text("New sheet name") }, singleLine = true)
            Button(onClick = { val o = store.add(JSONObject().put("name", newName.trim()).put("cells", JSONObject())); newName = ""; sheets = store.all(); openId = o.optString("id") }, enabled = newName.isNotBlank()) { Text("Create") }
        } }
        item("tpl") { LazyRow(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            val t = listOf("Monthly budget" to mapOf("A1" to "Item", "B1" to "Amount", "A2" to "Rent", "A3" to "Groceries", "A4" to "Bills", "A5" to "Fuel", "A7" to "Total", "B7" to "=SUM(B2:B6)"),
                "Shop stock" to mapOf("A1" to "Item", "B1" to "Qty", "C1" to "Price", "D1" to "Value", "D2" to "=B2*C2", "D3" to "=B3*C3", "A10" to "Total", "D10" to "=SUM(D2:D9)"),
                "Marks" to mapOf("A1" to "Subject", "B1" to "Marks", "C1" to "Out of", "A9" to "Percentage", "B9" to "=SUM(B2:B8)/SUM(C2:C8)*100"))
            items(t.size) { i -> Capsule(t[i].first, false) { val o = store.add(JSONObject().put("name", t[i].first).put("cells", JSONObject(t[i].second))); sheets = store.all(); openId = o.optString("id") } } } }
        if (sheets.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.TableChart, "No sheets yet", "Start from a template above.")) }
        sheets.forEach { s -> item(s.optString("id")) { ListRow(s.optString("name"), supporting = "${s.optJSONObject("cells")?.length() ?: 0} cells", icon = Icons.Outlined.TableChart, tint = Hub.Work.tint(), trailing = {
            Text("✕", Modifier.padding(Space.s).pressable { store.remove(s.optString("id")); sheets = store.all() }) }) { openId = s.optString("id") } } }
    }
}

// ─────────────────────────────── Photo editor ───────────────────────────────
private fun matrix(bright: Float, contrast: Float, sat: Float, warm: Float, filter: String): FloatArray {
    val cm = android.graphics.ColorMatrix()
    cm.setSaturation(when (filter) { "B&W" -> 0f; "Vivid" -> sat * 1.4f; else -> sat })
    val t = (1 - contrast) * 128 + bright
    cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(contrast, 0f, 0f, 0f, t, 0f, contrast, 0f, 0f, t, 0f, 0f, contrast, 0f, t, 0f, 0f, 0f, 1f, 0f)))
    cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, warm, 0f, 1f, 0f, 0f, warm * 0.3f, 0f, 0f, 1f, 0f, -warm, 0f, 0f, 0f, 1f, 0f)))
    if (filter == "Sepia") cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(0.393f, 0.769f, 0.189f, 0f, 0f, 0.349f, 0.686f, 0.168f, 0f, 0f, 0.272f, 0.534f, 0.131f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
    if (filter == "Cool") cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(0.9f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1.15f, 0f, 10f, 0f, 0f, 0f, 1f, 0f)))
    if (filter == "Fade") cm.postConcat(android.graphics.ColorMatrix(floatArrayOf(0.85f, 0f, 0f, 0f, 30f, 0f, 0.85f, 0f, 0f, 30f, 0f, 0f, 0.85f, 0f, 30f, 0f, 0f, 0f, 1f, 0f)))
    return cm.array
}

@Composable
fun PhotoEditorScreen() {
    val ctx = LocalContext.current
    var src by remember { mutableStateOf<Bitmap?>(null) }
    var bright by remember { mutableStateOf(0f) }; var contrast by remember { mutableStateOf(1f) }
    var sat by remember { mutableStateOf(1f) }; var warm by remember { mutableStateOf(0f) }
    var filter by remember { mutableStateOf("None") }; var crop by remember { mutableStateOf("Original") }
    var caption by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        u?.let { runCatching { ctx.contentResolver.openInputStream(it)?.use { s -> BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply { inSampleSize = 2 }) } }.getOrNull() }?.let { src = it }
    }
    fun cropped(b: Bitmap): Bitmap {
        val r = when (crop) { "1:1" -> 1f; "4:5" -> 0.8f; "9:16" -> 9f / 16f; "16:9" -> 16f / 9f; else -> return b }
        val w: Int; val h: Int
        if (b.width.toFloat() / b.height > r) { h = b.height; w = (h * r).toInt() } else { w = b.width; h = (w / r).toInt() }
        return Bitmap.createBitmap(b, (b.width - w) / 2, (b.height - h) / 2, w, h)
    }
    fun render(): Bitmap {
        val b = cropped(src!!)
        val out = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        cv.drawBitmap(b, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix(bright, contrast, sat, warm, filter)) })
        if (caption.isNotBlank()) {
            val p = Paint().apply { color = android.graphics.Color.WHITE; textSize = out.width / 14f; isAntiAlias = true; isFakeBoldText = true; setShadowLayer(8f, 0f, 2f, android.graphics.Color.BLACK); textAlign = Paint.Align.CENTER }
            cv.drawText(caption, out.width / 2f, out.height - out.height / 14f, p)
        }
        return out
    }
    AtlasScreen(title = "Photo editor", subtitle = if (src == null) "Pick a photo" else "Changes preview live", hub = Hub.Create) {
        item("p") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Button(onClick = { pick.launch("image/*") }) { Text(if (src == null) "Choose photo" else "Another") }
            if (src != null) OutlinedButton(onClick = { src = android.graphics.Matrix().apply { postRotate(90f) }.let { m -> Bitmap.createBitmap(src!!, 0, 0, src!!.width, src!!.height, m, true) } }) { Text("Rotate") }
        } }
        src?.let { b -> item("img") {
            val shown = remember(b, crop) { cropped(b) }
            Box(Modifier.fillMaxWidth().padding(Space.page)) {
                Image(shown.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 420.dp), colorFilter = ColorFilter.colorMatrix(ColorMatrix(matrix(bright, contrast, sat, warm, filter))))
                if (caption.isNotBlank()) Text(caption, Modifier.align(Alignment.BottomCenter).padding(12.dp), color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
        item("f") { LazyRow(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            val fs = listOf("None", "Vivid", "B&W", "Sepia", "Cool", "Fade"); items(fs.size) { i -> Capsule(fs[i], filter == fs[i]) { filter = fs[i] } } } }
        item("c") { LazyRow(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            val cs = listOf("Original", "1:1", "4:5", "9:16", "16:9"); items(cs.size) { i -> Capsule(cs[i], crop == cs[i]) { crop = cs[i] } } } }
        item("s") { Column(Modifier.padding(horizontal = Space.page)) {
            Text("Brightness"); Slider(bright, { bright = it }, valueRange = -80f..80f)
            Text("Contrast"); Slider(contrast, { contrast = it }, valueRange = 0.6f..1.6f)
            Text("Saturation"); Slider(sat, { sat = it }, valueRange = 0f..2f)
            Text("Warmth"); Slider(warm, { warm = it }, valueRange = -40f..40f)
            OutlinedTextField(caption, { caption = it }, Modifier.fillMaxWidth(), label = { Text("Caption on the photo (optional)") }, singleLine = true)
        } }
        item("save") { Row(Modifier.padding(Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Button(onClick = { if (toGallery(ctx, render(), "edit_${System.currentTimeMillis()}.jpg")) Feedback.show("Saved to Pictures/Atlas") }) { Text("Save") }
            OutlinedButton(onClick = { val f = Share.out(ctx, "photo.jpg"); f.outputStream().use { render().compress(Bitmap.CompressFormat.JPEG, 92, it) }; Share.file(ctx, f, "image/jpeg") }) { Text("Share") }
            OutlinedButton(onClick = { bright = 0f; contrast = 1f; sat = 1f; warm = 0f; filter = "None"; crop = "Original"; caption = "" }) { Text("Reset") }
        } }
        }
    }
}

// ─────────────────────────────── Video editor ───────────────────────────────
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private fun exportVideo(c: Context, uri: Uri, startMs: Long, endMs: Long, mute: Boolean, rotate: Int, speed: Float, shrink: Boolean, done: (File?, String?) -> Unit) {
    val out = Share.out(c, "edit_${System.currentTimeMillis()}.mp4")
    val item = androidx.media3.common.MediaItem.Builder().setUri(uri).setClippingConfiguration(
        androidx.media3.common.MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).apply { if (endMs > startMs) setEndPositionMs(endMs) }.build()).build()
    val video = mutableListOf<androidx.media3.common.Effect>()
    if (rotate != 0) video += androidx.media3.effect.ScaleAndRotateTransformation.Builder().setRotationDegrees(rotate.toFloat()).build()
    if (shrink) video += androidx.media3.effect.Presentation.createForHeight(720)
    if (speed != 1f) video += androidx.media3.effect.SpeedChangeEffect(speed)
    val audio = mutableListOf<androidx.media3.common.audio.AudioProcessor>()
    if (speed != 1f && !mute) audio += androidx.media3.common.audio.SonicAudioProcessor().apply { setSpeed(speed); setPitch(1f) }
    val edited = androidx.media3.transformer.EditedMediaItem.Builder(item).setRemoveAudio(mute)
        .setEffects(androidx.media3.transformer.Effects(audio, video)).build()
    androidx.media3.transformer.Transformer.Builder(c).addListener(object : androidx.media3.transformer.Transformer.Listener {
        override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: androidx.media3.transformer.ExportResult) { done(out, null) }
        override fun onError(composition: androidx.media3.transformer.Composition, exportResult: androidx.media3.transformer.ExportResult, exportException: androidx.media3.transformer.ExportException) { done(null, exportException.message) }
    }).build().start(edited, out.absolutePath)
}

@Composable
fun VideoEditorScreen() {
    val ctx = LocalContext.current
    var uri by remember { mutableStateOf<Uri?>(null) }
    var dur by remember { mutableStateOf(0L) }
    var range by remember { mutableStateOf(0f..1f) }
    var mute by remember { mutableStateOf(false) }
    var rotate by remember { mutableIntStateOf(0) }
    var speed by remember { mutableStateOf(1f) }
    var shrink by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        if (u != null) { uri = u; range = 0f..1f
            dur = runCatching { android.media.MediaMetadataRetriever().run { setDataSource(ctx, u); val d = extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L; release(); d } }.getOrDefault(0L) }
    }
    fun t(f: Float) = (dur * f / 1000).toInt().let { "${it / 60}:${"%02d".format(it % 60)}" }
    AtlasScreen(title = "Video editor", subtitle = if (uri == null) "Pick a video" else "Clip ${t(range.start)} – ${t(range.endInclusive)} of ${t(1f)}", hub = Hub.Create) {
        item("p") { Button(onClick = { pick.launch("video/*") }, modifier = Modifier.padding(horizontal = Space.page)) { Text(if (uri == null) "Choose video" else "Another video") } }
        if (uri != null) {
            item("trim") { Column(Modifier.padding(horizontal = Space.page)) {
                Text("Keep this part", style = MaterialTheme.typography.titleSmall)
                androidx.compose.material3.RangeSlider(range, { range = it }, valueRange = 0f..1f)
            } }
            item("opts") { Column(Modifier.padding(horizontal = Space.page)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Remove sound", Modifier.weight(1f)); Switch(mute, { mute = it }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Shrink to 720p (for WhatsApp)", Modifier.weight(1f)); Switch(shrink, { shrink = it }) }
                Row(Modifier.padding(vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) { listOf(0, 90, 180, 270).forEach { r -> Capsule("$r°", rotate == r) { rotate = r } } }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) { listOf(0.5f, 1f, 1.5f, 2f).forEach { s -> Capsule("${s}×", speed == s) { speed = s } } }
            } }
            item("go") { Button(onClick = {
                busy = true
                exportVideo(ctx, uri!!, (dur * range.start).toLong(), (dur * range.endInclusive).toLong(), mute, rotate, speed, shrink) { f, err ->
                    busy = false; if (f != null) Share.file(ctx, f, "video/mp4") else Feedback.show("Couldn't export: $err") }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(Space.page)) { Text(if (busy) "Exporting…" else "Export & share") } }
        }
    }
}

// ─────────────────────────────── Audio studio ───────────────────────────────
@Composable
fun AudioStudioScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val dir = remember { File(ctx.filesDir, "recordings").apply { mkdirs() } }
    var files by remember { mutableStateOf(dir.listFiles().orEmpty().sortedByDescending { it.lastModified() }) }
    var rec by remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    var cur by remember { mutableStateOf<File?>(null) }
    var secs by remember { mutableIntStateOf(0) }
    var speed by remember { mutableStateOf(1f) }
    var text by remember { mutableStateOf<Pair<String, String>?>(null) }
    val player = remember { androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) { onDispose { player.release(); runCatching { rec?.release() } } }
    LaunchedEffect(rec) { secs = 0; while (rec != null) { delay(1000); secs++ } }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    AtlasScreen(title = "Audio studio", subtitle = "${files.size} recordings", hub = Hub.Create) {
        item("rec") { Button(onClick = {
            val r = rec
            if (r == null) {
                if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) { perm.launch(android.Manifest.permission.RECORD_AUDIO); return@Button }
                val f = File(dir, "Recording ${java.time.LocalDateTime.now().toString().take(16).replace('T', ' ').replace(':', '.')}.m4a"); cur = f
                rec = runCatching { (if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(ctx) else @Suppress("DEPRECATION") android.media.MediaRecorder()).apply {
                    setAudioSource(android.media.MediaRecorder.AudioSource.MIC); setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4); setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                    setAudioEncodingBitRate(128000); setAudioSamplingRate(44100); setOutputFile(f.absolutePath); prepare(); start() } }.getOrNull()
            } else { runCatching { r.stop() }; r.release(); rec = null; files = dir.listFiles().orEmpty().sortedByDescending { it.lastModified() } }
        }, modifier = Modifier.fillMaxWidth().padding(Space.page).height(64.dp)) { Text(if (rec == null) "● Record" else "■ Stop  ${secs / 60}:${"%02d".format(secs % 60)}") } }
        item("sp") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Text("Play speed", Modifier.align(Alignment.CenterVertically)); listOf(0.75f, 1f, 1.25f, 1.5f, 2f).forEach { s -> Capsule("${s}×", speed == s) { speed = s; player.setPlaybackSpeed(s) } } } }
        text?.let { (n, t) -> item("t") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) { Text(n, style = MaterialTheme.typography.labelLarge); Md(t) } } }
        if (files.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.Mic, "No recordings", "Lectures, meetings, ideas, voice notes.")) }
        files.forEach { f -> item(f.name) {
            ListRow(f.nameWithoutExtension, supporting = "${f.length() / 1024} KB", icon = Icons.Outlined.GraphicEq, tint = Hub.Create.tint(), trailing = { Row {
                Text("Text", Modifier.padding(Space.s).pressable {
                    text = f.nameWithoutExtension to "Transcribing…"
                    scope.launch { text = f.nameWithoutExtension to runCatching { Multimodal.ask(container.settingsRepository,
                        "Transcribe this recording accurately in its language. Then add '---' and a short summary with any action items.", "audio/mp4", f.readBytes(), 8000) }.getOrElse { it.message ?: "Failed" } }
                }, color = Hub.Create.tint())
                Text("Share", Modifier.padding(Space.s).pressable { val c = Share.out(ctx, f.name); f.copyTo(c, true); Share.file(ctx, c, "audio/mp4") })
                Text("✕", Modifier.padding(Space.s).pressable { f.delete(); files = dir.listFiles().orEmpty().sortedByDescending { it.lastModified() } })
            } }) { player.setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(f))); player.setPlaybackSpeed(speed); player.prepare(); player.play() }
        } }
    }
}

// ─────────────────────────────── Files ───────────────────────────────
private data class Doc(val id: String, val name: String, val mime: String, val size: Long, val modified: Long) { val dir get() = mime == DocumentsContract.Document.MIME_TYPE_DIR }

@Composable
fun FilesScreen() {
    val ctx = LocalContext.current
    val p = remember { ctx.getSharedPreferences("files_app", 0) }
    var tree by remember { mutableStateOf(p.getString("tree", null)?.let { Uri.parse(it) }) }
    var stack by remember { mutableStateOf(listOf<Pair<String, String>>()) }
    var items by remember { mutableStateOf<List<Doc>>(emptyList()) }
    var sort by remember { mutableStateOf("Name") }
    var reload by remember { mutableIntStateOf(0) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { u ->
        if (u != null) { runCatching { ctx.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            p.edit().putString("tree", u.toString()).apply(); tree = u; stack = emptyList() }
    }
    val t = tree
    LaunchedEffect(t, stack, reload) {
        if (t == null) return@LaunchedEffect
        val parent = stack.lastOrNull()?.first ?: DocumentsContract.getTreeDocumentId(t)
        items = withContext(Dispatchers.IO) { runCatching {
            val u = DocumentsContract.buildChildDocumentsUriUsingTree(t, parent)
            val out = mutableListOf<Doc>()
            ctx.contentResolver.query(u, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE, DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                while (c.moveToNext()) out += Doc(c.getString(0), c.getString(1) ?: "?", c.getString(2) ?: "", c.getLong(3), c.getLong(4)) }
            out
        }.getOrDefault(emptyList()) }
    }
    val sorted = when (sort) { "Size" -> items.sortedByDescending { it.size }; "Newest" -> items.sortedByDescending { it.modified }; else -> items.sortedWith(compareBy<Doc>({ !it.dir }, { it.name.lowercase() })) }
    fun mb(b: Long) = if (b > 1e9) "%.1f GB".format(b / 1e9) else if (b > 1e6) "%.1f MB".format(b / 1e6) else "${b / 1024} KB"
    AtlasScreen(title = stack.lastOrNull()?.second ?: "Files", subtitle = if (t == null) "Choose a folder to browse" else "${items.size} items", hub = Hub.Tools, actions = {
        if (stack.isNotEmpty()) Text("Up", Modifier.padding(Space.s).pressable { stack = stack.dropLast(1) }, style = MaterialTheme.typography.labelLarge) }) {
        item("pick") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Button(onClick = { pick.launch(null) }) { Text(if (t == null) "Choose folder" else "Another folder") }
            listOf("Name", "Size", "Newest").forEach { s -> Capsule(s, sort == s) { sort = s } }
        } }
        sorted.forEach { d -> item(d.id) {
            ListRow(d.name, supporting = if (d.dir) "Folder" else "${mb(d.size)} · ${java.text.DateFormat.getDateInstance().format(java.util.Date(d.modified))}",
                icon = if (d.dir) Icons.Outlined.Folder else when { d.mime.startsWith("image") -> Icons.Outlined.Image; d.mime.startsWith("video") -> Icons.Outlined.Movie
                    d.mime.startsWith("audio") -> Icons.Outlined.MusicNote; d.mime.contains("pdf") -> Icons.Outlined.PictureAsPdf; else -> Icons.Outlined.InsertDriveFile },
                tint = Hub.Tools.tint(), trailing = { if (!d.dir) Row {
                    Text("Share", Modifier.padding(Space.s).pressable { val u = DocumentsContract.buildDocumentUriUsingTree(t, d.id)
                        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType(d.mime.ifBlank { "*/*" }).putExtra(Intent.EXTRA_STREAM, u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) })
                    Text("✕", Modifier.padding(Space.s).pressable { runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, DocumentsContract.buildDocumentUriUsingTree(t, d.id)) }.onSuccess { reload++ }.onFailure { Feedback.show("Couldn't delete") } })
                } }) {
                if (d.dir) stack = stack + (d.id to d.name)
                else runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(DocumentsContract.buildDocumentUriUsingTree(t, d.id), d.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Feedback.show("No app can open this") }
            }
        } }
    }
}

// ─────────────────────────────── My shorts ───────────────────────────────
@Composable
fun ShortsScreen() {
    val ctx = LocalContext.current
    var vids by remember { mutableStateOf<List<Uri>?>(null) }
    LaunchedEffect(Unit) {
        vids = withContext(Dispatchers.IO) { runCatching {
            val out = mutableListOf<Uri>()
            ctx.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Video.Media._ID), "${MediaStore.Video.Media.DURATION} <= ?", arrayOf("90000"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC")?.use { c -> while (c.moveToNext() && out.size < 200) out += android.content.ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)) }
            out
        }.getOrDefault(emptyList()) }
    }
    val list = vids
    if (list == null || list.isEmpty()) {
        AtlasScreen(title = "My shorts", subtitle = "Short videos on your phone", hub = Hub.Media, loading = list == null) {
            item("e") { EmptyState(EmptySpec(Icons.Outlined.Slideshow, "No short videos found", "Videos under 90 seconds appear here. Allow access to videos if asked.")) }
        }
        return
    }
    val player = remember { androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build().apply { repeatMode = androidx.media3.common.Player.REPEAT_MODE_ONE } }
    DisposableEffect(Unit) { onDispose { player.release() } }
    val pager = rememberPagerState { list.size }
    LaunchedEffect(pager.currentPage) { player.setMediaItem(androidx.media3.common.MediaItem.fromUri(list[pager.currentPage])); player.prepare(); player.play() }
    VerticalPager(pager, Modifier.fillMaxSize().background(Color.Black)) { page ->
        Box(Modifier.fillMaxSize()) {
            if (page == pager.currentPage) AndroidView({ c -> androidx.media3.ui.PlayerView(c).apply { useController = false; this.player = player } }, Modifier.fillMaxSize()
                .pressable { if (player.isPlaying) player.pause() else player.play() })
            Text("${page + 1} / ${list.size}", Modifier.align(Alignment.TopEnd).padding(24.dp), color = Color.White)
        }
    }
}
