package com.nizam.app.feature.nx

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.feature.future.Imagine
import com.nizam.app.feature.next.JsonStore
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

object Studio {
    val all: List<Spec> = listOf(
        ScreenSpec("songid", "Song ID", "What's this song? Listen for 12 seconds", Icons.Outlined.MusicNote, Hub.Media, "shazam song identify music name lyrics") { c, _ -> SongIdScreen(c) },
        ScreenSpec("videostudio", "Video studio", "Captions, translated subtitles, chapters, highlights and trims — for videos, lectures and voice notes", Icons.Outlined.VideoSettings, Hub.Create, "captions srt subtitles chapters clip trim shorts reels video") { c, _ -> VideoStudioScreen(c) },
        ScreenSpec("photomagic", "Photo magic", "Restore old photos, upscale, colourise, erase objects, new background", Icons.Outlined.AutoFixHigh, Hub.Create, "restore old photo upscale enhance colorize remove object eraser background") { c, _ -> PhotoMagicScreen(c) },
        ScreenSpec("cutout", "Cut-out & stickers", "Remove backgrounds on the phone; make WhatsApp stickers", Icons.Outlined.ContentCut, Hub.Create, "remove background sticker whatsapp png transparent rembg") { _, _ -> CutoutScreen() },
        ScreenSpec("offlinetranslate", "Offline translator", "Translate with no internet, 18 languages", Icons.Outlined.Translate, Hub.Travel, "offline translate urdu english chinese arabic no internet") { _, _ -> OfflineTranslatorScreen() },
        ScreenSpec("myvoice", "My voice", "Atlas speaks in your own voice, in any language", Icons.Outlined.RecordVoiceOver, Hub.Create, "voice clone elevenlabs my voice speak dub") { c, _ -> MyVoiceScreen(c) },
        ScreenSpec("pictureit", "Comic & dream pictures", "Your week as a comic; a dream as an image", Icons.Outlined.AutoStories, Hub.Create, "comic week dream image story panels") { c, _ -> PictureItScreen(c) },
        ScreenSpec("sky", "Sky & moon", "Where the sun and moon are, moon phase, next full moon", Icons.Outlined.NightsStay, Hub.Deen, "moon phase sky sun position chand full moon") { c, _ -> SkyScreen(c) },
        ScreenSpec("wiki", "Offline library", "Save Wikipedia articles and packs to read with no internet", Icons.Outlined.LocalLibrary, Hub.Learn, "wikipedia offline kiwix library articles first aid") { _, _ -> WikiScreen() },
    )
}

private fun readBytes(c: Context, uri: Uri): ByteArray = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: ByteArray(0)
private fun loadBitmap(c: Context, uri: Uri): Bitmap? = runCatching {
    val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    c.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
    var s = 1; while (o.outWidth / s > 2400 || o.outHeight / s > 2400) s *= 2
    c.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = s }) }
}.getOrNull()

private fun saveToGallery(c: Context, b: Bitmap, name: String, png: Boolean = false) = runCatching {
    val v = android.content.ContentValues().apply {
        put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name); put(android.provider.MediaStore.Images.Media.MIME_TYPE, if (png) "image/png" else "image/jpeg")
        if (android.os.Build.VERSION.SDK_INT >= 29) put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Atlas")
    }
    val uri = c.contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return@runCatching false
    c.contentResolver.openOutputStream(uri)?.use { b.compress(if (png) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG, 95, it) }
    true
}.getOrDefault(false)

@Composable
private fun rememberRecorder(file: File): Pair<() -> Boolean, () -> Unit> {
    val ctx = LocalContext.current
    var rec by remember { mutableStateOf<android.media.MediaRecorder?>(null) }
    DisposableEffect(Unit) { onDispose { runCatching { rec?.release() } } }
    val start: () -> Boolean = {
        if (androidx.core.content.ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) false
        else runCatching {
            rec = (if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(ctx) else @Suppress("DEPRECATION") android.media.MediaRecorder()).apply {
                setAudioSource(android.media.MediaRecorder.AudioSource.MIC); setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC); setAudioEncodingBitRate(96000); setAudioSamplingRate(44100)
                setOutputFile(file.absolutePath); prepare(); start()
            }; true
        }.getOrDefault(false)
    }
    val stop: () -> Unit = { rec?.let { r -> runCatching { r.stop() }; r.release() }; rec = null }
    return start to stop
}

// ─────────────────────────────── Song ID ───────────────────────────────
@Composable
fun SongIdScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val file = remember { File(ctx.cacheDir, "songid.m4a") }
    val (start, stop) = rememberRecorder(file)
    var secs by remember { mutableStateOf(0) }
    var out by remember { mutableStateOf<String?>(null) }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun identify() {
        out = "Thinking…"
        scope.launch {
            out = runCatching {
                val heard = Multimodal.ask(container.settingsRepository,
                    "Identify this song. Reply as: **Title** — Artist (year), language, film/album if any. If you are not certain, say 'Not sure' and write the exact lyrics you hear, the language and style.",
                    "audio/mp4", file.readBytes(), 600)
                if (heard.contains("not sure", true)) heard + "\n\n---\n" + Ai.generate(container.settingsRepository,
                    "Which song is this? Clues from listening:\n$heard\nSearch the web and give the most likely title and artist with a link.", maxOutputTokens = 400, useWeb = true)
                else heard
            }.getOrElse { it.message ?: "Couldn't identify" }
        }
    }
    AtlasScreen(title = "Song ID", subtitle = "Hold the phone near the music", hub = Hub.Media) {
        item("b") { Button(onClick = {
            if (!start()) { perm.launch(android.Manifest.permission.RECORD_AUDIO); return@Button }
            out = null; secs = 12
            scope.launch { while (secs > 0) { delay(1000); secs-- }; stop(); identify() }
        }, enabled = secs == 0, modifier = Modifier.fillMaxWidth().padding(Space.page).height(72.dp)) { Text(if (secs > 0) "Listening… $secs" else "Listen", fontSize = 20.sp) } }
        out?.let { o -> item("o") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) { Md(o) } } }
        item("n") { Text("Uses your Gemini key to listen. Humming works too, less reliably.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Space.page)) }
    }
}

// ─────────────────────────────── Video studio ───────────────────────────────
@Composable
fun VideoStudioScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var uri by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf("") }
    var durMs by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf<String?>(null) }
    var out by remember { mutableStateOf<String?>(null) }
    var srt by remember { mutableStateOf<String?>(null) }
    var lang by remember { mutableStateOf("Original") }
    var from by remember { mutableStateOf("0") }
    var to by remember { mutableStateOf("30") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u ->
        if (u != null) {
            uri = u; out = null; srt = null
            name = u.lastPathSegment?.substringAfterLast('/') ?: "video"
            durMs = runCatching { android.media.MediaMetadataRetriever().run { setDataSource(ctx, u); val d = extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L; release(); d } }.getOrDefault(0L)
            to = (durMs / 1000).coerceAtMost(30).toString()
        }
    }
    suspend fun audioBytes(u: Uri): ByteArray = withContext(Dispatchers.IO) { SoundTrack.extract(ctx, u).readBytes() }
    fun task(label: String, prompt: String, isSrt: Boolean = false) {
        val u = uri ?: return
        busy = label; out = null
        scope.launch {
            runCatching { Multimodal.ask(container.settingsRepository, prompt, "audio/mp4", audioBytes(u), 8000) }
                .onSuccess { r -> val clean = r.replace("```srt", "").replace("```", "").trim(); if (isSrt) srt = clean; out = if (isSrt) "Captions ready: ${clean.lines().count { it.contains("-->") }} lines." else clean }
                .onFailure { out = it.message }
            busy = null
        }
    }
    AtlasScreen(title = "Video studio", subtitle = if (uri == null) "Pick a video from your phone" else "$name · ${durMs / 60000}:${"%02d".format(durMs / 1000 % 60)}", hub = Hub.Create) {
        item("pick") { Button(onClick = { pick.launch(arrayOf("video/*", "audio/*")) }, modifier = Modifier.padding(horizontal = Space.page)) { Text(if (uri == null) "Choose video or audio" else "Another file") } }
        if (uri != null) {
            item("lang") { Row(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                listOf("Original", "English", "Urdu", "Roman Urdu").forEach { l -> Capsule(l, lang == l) { lang = l } } } }
            item("acts") { Column(Modifier.padding(horizontal = Space.page), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                val tr = if (lang == "Original") "in the spoken language" else "translated into $lang"
                OutlinedButton(onClick = { task("Captions", "Transcribe this audio into SRT subtitles $tr. Lines of at most 42 characters, 1–6 seconds each, exact timestamps HH:MM:SS,mmm. Output only valid SRT.", true) },
                    enabled = busy == null, modifier = Modifier.fillMaxWidth()) { Text("Make captions (.srt)") }
                OutlinedButton(onClick = { task("Chapters", "Create YouTube chapters for this recording: '00:00 Title' per line, first at 00:00, 5–12 chapters, titles in ${if (lang == "Original") "the spoken language" else lang}. Then a 2-line description.") },
                    enabled = busy == null, modifier = Modifier.fillMaxWidth()) { Text("Chapters & description") }
                OutlinedButton(onClick = { task("Highlights", "Find the 3 most engaging 15–45 second moments for Shorts/Reels. For each: start and end in seconds, a hook title, and why. Format: '1. 12–41s — Title — why'.") },
                    enabled = busy == null, modifier = Modifier.fillMaxWidth()) { Text("Find highlights for Shorts") }
                OutlinedButton(onClick = { task("Summary", "Summarise what is said, with key points and any action items, ${if (lang == "Original") "" else "in $lang"}.") },
                    enabled = busy == null, modifier = Modifier.fillMaxWidth()) { Text("Summarise") }
            } }
            busy?.let { b -> item("busy") { Text("$b… (long videos take a minute)", Modifier.padding(Space.page), style = MaterialTheme.typography.labelLarge) } }
            out?.let { o -> item("out") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) { Md(o) } } }
            srt?.let { s -> item("srt") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Button(onClick = { val f = Share.out(ctx, name.substringBeforeLast('.') + ".srt"); f.writeText(s); Share.file(ctx, f, "application/x-subrip") }) { Text("Share .srt") }
                OutlinedButton(onClick = { Share.text(ctx, s) }) { Text("Copy text") }
            } } }
            item("trimh") { SectionHeader("Cut a clip") }
            item("trim") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    OutlinedTextField(from, { from = it }, Modifier.weight(1f), label = { Text("From (seconds)") }, singleLine = true)
                    OutlinedTextField(to, { to = it }, Modifier.weight(1f), label = { Text("To (seconds)") }, singleLine = true)
                }
                Button(onClick = {
                    val s = (num(from) * 1000).toLong(); val e = (num(to) * 1000).toLong()
                    if (e <= s) { Feedback.show("End must be after start"); return@Button }
                    busy = "Cutting"
                    Trim.start(ctx, uri!!, s, e) { f, err -> busy = null; if (f != null) Share.file(ctx, f, "video/mp4") else Feedback.show("Couldn't cut: $err") }
                }, enabled = busy == null, modifier = Modifier.padding(top = Space.s)) { Text("Export clip") }
            } }
        }
        item("n") { Text("Captions, chapters and highlights listen to the sound track with your Gemini key (up to about 20 minutes). Cutting happens on the phone.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Space.page)) }
    }
}

// ─────────────────────────────── Photo magic ───────────────────────────────
private fun enhance(src: Bitmap): Bitmap {
    // offline: 2× (to at most 2400 px) and a gentle unsharp mask
    val f = minOf(2f, 2400f / maxOf(src.width, src.height)).coerceAtLeast(1f)
    val up = Bitmap.createScaledBitmap(src, (src.width * f).toInt(), (src.height * f).toInt(), true)
    val w = up.width; val h = up.height
    val px = IntArray(w * h); up.getPixels(px, 0, w, 0, 0, w, h)
    val out = px.copyOf()
    for (y in 1 until h - 1) for (x in 1 until w - 1) {
        val i = y * w + x
        var r = 0; var g = 0; var b = 0
        for (dy in -1..1) for (dx in -1..1) { val p = px[i + dy * w + dx]; r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255 }
        val c = px[i]
        fun ch(v: Int, avg: Int) = (v + (v - avg) * 0.6f).toInt().coerceIn(0, 255)
        out[i] = (c and -0x1000000) or (ch((c shr 16) and 255, r / 9) shl 16) or (ch((c shr 8) and 255, g / 9) shl 8) or ch(c and 255, b / 9)
    }
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}

@Composable
fun PhotoMagicScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var src by remember { mutableStateOf<Bitmap?>(null) }
    var res by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var extra by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> u?.let { loadBitmap(ctx, it) }?.let { src = it; res = null } }
    fun ai(prompt: String) {
        val s = src ?: return
        busy = true
        scope.launch { runCatching { Multimodal.editImage(container.settingsRepository, prompt, s) }.onSuccess { res = it }.onFailure { Feedback.show(it.message ?: "Failed") }; busy = false }
    }
    AtlasScreen(title = "Photo magic", subtitle = "Pick a photo, then a spell", hub = Hub.Create) {
        item("p") { Button(onClick = { pick.launch("image/*") }, modifier = Modifier.padding(horizontal = Space.page)) { Text(if (src == null) "Choose photo" else "Another photo") } }
        (res ?: src)?.let { b -> item("img") { Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().padding(Space.page).heightIn(max = 420.dp)) } }
        if (src != null) {
            if (res != null) item("cmp") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Button(onClick = { if (saveToGallery(ctx, res!!, "atlas_${System.currentTimeMillis()}.jpg")) Feedback.show("Saved to Pictures/Atlas") }) { Text("Save") }
                OutlinedButton(onClick = { Share.file(ctx, Share.out(ctx, "atlas_photo.jpg").also { f -> f.outputStream().use { res!!.compress(Bitmap.CompressFormat.JPEG, 95, it) } }, "image/jpeg") }) { Text("Share") }
                OutlinedButton(onClick = { src = res; res = null }) { Text("Keep editing") }
            } }
            if (busy) item("busy") { Text("Working the magic…", Modifier.padding(Space.page), style = MaterialTheme.typography.labelLarge) }
            item("h") { SectionHeader("Spells") }
            listOf(
                Triple("Restore old photo", "Repair scratches, tears, fading and noise", "Restore this old photograph: remove scratches, dust, tears and noise, fix fading, sharpen faces naturally. Keep the people and composition exactly the same."),
                Triple("Colourise", "Natural colour for black & white", "Colourise this black-and-white photo with natural, historically plausible colours. Keep everything else identical."),
                Triple("Upscale & sharpen (AI)", "More detail, less blur", "Upscale this photo to higher resolution, recover fine detail and sharpness, reduce blur and compression artefacts. Do not change content."),
                Triple("Studio portrait", "Clean light, soft background", "Turn this into a professional studio portrait: soft flattering light, clean neutral background. Keep the person's face and identity exactly."),
                Triple("Fix lighting", "Brighten dark or backlit shots", "Fix the exposure and lighting: brighten shadows, balance highlights, natural colours. Keep everything else the same."),
            ).forEach { (t, d, p) -> item(t) { ListRow(t, supporting = d, icon = Icons.Outlined.AutoFixHigh, tint = Hub.Create.tint()) { if (!busy) ai(p) } } }
            item("offline") { ListRow("Enhance offline", supporting = "2× size and sharpen, on the phone, no internet", icon = Icons.Outlined.PhotoFilter) {
                val s = src ?: return@ListRow
                busy = true; scope.launch { res = withContext(Dispatchers.Default) { enhance(s) }; busy = false }
            } }
            item("custom") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                OutlinedTextField(extra, { extra = it }, Modifier.fillMaxWidth(), label = { Text("Or describe the change") },
                    placeholder = { Text("Remove the man in the background · make the sky sunset · put me in front of Badshahi Mosque") })
                Button(onClick = { ai("Edit this photo: $extra. Keep everything else unchanged and realistic.") }, enabled = extra.isNotBlank() && !busy, modifier = Modifier.padding(top = Space.s)) { Text("Apply") }
            } }
        }
        item("n") { Text("AI spells use your Gemini key's image model. Photos are sent to Google to edit; never use this for the vault.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Space.page)) }
    }
}

// ─────────────────────────────── Cut-out & stickers ───────────────────────────────
@Composable
fun CutoutScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var fg by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun run(b: Bitmap) { busy = true; scope.launch { runCatching { Cutout.foreground(b) }.onSuccess { fg = it }.onFailure { Feedback.show(it.message ?: "Failed") }; busy = false } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> u?.let { loadBitmap(ctx, it) }?.let { run(it) } }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { b -> b?.let { run(it) } }
    AtlasScreen(title = "Cut-out & stickers", subtitle = if (busy) "Cutting out…" else "Works on the phone — nothing is uploaded", hub = Hub.Create) {
        item("p") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Button(onClick = { pick.launch("image/*") }) { Text("Choose photo") }; OutlinedButton(onClick = { cam.launch(null) }) { Text("Camera") } } }
        fg?.let { f ->
            item("img") { Box(Modifier.fillMaxWidth().padding(Space.page).background(Color(0xFFDDDDDD), androidx.compose.foundation.shape.RoundedCornerShape(16.dp))) {
                Image(f.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 380.dp)) } }
            item("a") { Column(Modifier.padding(horizontal = Space.page), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Button(onClick = { Share.file(ctx, Share.out(ctx, "sticker.webp").also { o -> o.outputStream().use {
                    @Suppress("DEPRECATION") Cutout.sticker(f).compress(if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP, 80, it) } }, "image/webp") },
                    modifier = Modifier.fillMaxWidth()) { Text("Share as sticker") }
                OutlinedButton(onClick = { if (saveToGallery(ctx, f, "cutout_${System.currentTimeMillis()}.png", png = true)) Feedback.show("Saved transparent PNG") }, modifier = Modifier.fillMaxWidth()) { Text("Save transparent PNG") }
                OutlinedButton(onClick = { if (saveToGallery(ctx, Cutout.onColour(f, android.graphics.Color.WHITE), "white_${System.currentTimeMillis()}.jpg")) Feedback.show("Saved on white — ready for passport photo") }, modifier = Modifier.fillMaxWidth()) { Text("Save on white (for ID photos)") }
                OutlinedButton(onClick = { if (saveToGallery(ctx, Cutout.onColour(f, 0xFF2E6FD8.toInt()), "blue_${System.currentTimeMillis()}.jpg")) Feedback.show("Saved on blue") }, modifier = Modifier.fillMaxWidth()) { Text("Save on blue") }
            } }
        }
    }
}

// ─────────────────────────────── Offline translator ───────────────────────────────
@Composable
fun OfflineTranslatorScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val names = OfflineTranslate.LANGS.keys.toList()
    var from by remember { mutableStateOf("English") }
    var to by remember { mutableStateOf("Urdu") }
    var text by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    var have by remember { mutableStateOf<Set<String>>(emptySet()) }
    var busy by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { have = OfflineTranslate.downloaded() }
    fun code(n: String) = OfflineTranslate.LANGS[n]!!
    AtlasScreen(title = "Offline translator", subtitle = "${have.size} languages on this phone", hub = Hub.Travel) {
        item("f") { LazyRow(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) { items(names.size) { i -> Capsule("From " + names[i], from == names[i]) { from = names[i] } } } }
        item("t") { LazyRow(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) { items(names.size) { i -> Capsule("To " + names[i], to == names[i]) { to = names[i] } } } }
        item("in") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 110.dp), placeholder = { Text("Type or paste") })
            Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Button(onClick = {
                    busy = if (code(from) in have && code(to) in have) "Translating" else "Downloading languages (once)"
                    scope.launch {
                        out = runCatching { OfflineTranslate.translate(code(from), code(to), text) }.getOrElse { "Couldn't translate: ${it.message}" }
                        have = OfflineTranslate.downloaded(); busy = null
                    }
                }, enabled = text.isNotBlank() && busy == null) { Text(busy ?: "Translate") }
                OutlinedButton(onClick = { val f = from; from = to; to = f; text = out.ifBlank { text }; out = "" }) { Text("⇄") }
            }
        } }
        if (out.isNotBlank()) item("o") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            Text(out, style = MaterialTheme.typography.titleMedium)
            Row(Modifier.padding(top = Space.s)) { Capsule("Copy", false) { (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("Atlas", out)); Feedback.show("Copied") } }
        } }
        item("h") { SectionHeader("Downloaded languages") }
        names.forEach { n -> item("l$n") {
            val on = code(n) in have
            ListRow(n, supporting = if (on) "On this phone" else "Not downloaded (~30 MB)", icon = Icons.Outlined.Translate, trailing = {
                Text(if (on) "Remove" else "Get", Modifier.padding(Space.s).pressable {
                    scope.launch { runCatching { if (on) OfflineTranslate.delete(code(n)) else { busy = "Downloading $n"; OfflineTranslate.download(code(n)) } }
                        .onFailure { Feedback.show(it.message ?: "Failed") }; have = OfflineTranslate.downloaded(); busy = null }
                }, color = if (on) MaterialTheme.colorScheme.onSurfaceVariant else Hub.Travel.tint())
            })
        } }
    }
}

// ─────────────────────────────── My voice ───────────────────────────────
@Composable
fun MyVoiceScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sample = remember { File(ctx.filesDir, "voice_sample.m4a") }
    val (start, stop) = rememberRecorder(sample)
    var key by remember { mutableStateOf(VoiceClone.key(ctx)) }
    var voice by remember { mutableStateOf(VoiceClone.voice(ctx)) }
    var recording by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf("As written") }
    var busy by remember { mutableStateOf<String?>(null) }
    val player = remember { androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build() }
    DisposableEffect(Unit) { onDispose { player.release() } }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    AtlasScreen(title = "My voice", subtitle = if (voice.isBlank()) "Three steps: key, sample, done" else "Your voice is ready", hub = Hub.Create) {
        item("k") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            Text("1 · ElevenLabs key", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(key, { key = it; VoiceClone.setKey(ctx, it) }, Modifier.fillMaxWidth(), placeholder = { Text("sk_…") }, singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            Text("Voice cloning needs their Starter plan or above. The key stays on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item("s") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            Text("2 · Read anything aloud for about a minute, in a quiet room", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Button(onClick = {
                    if (!recording) { if (start()) recording = true else perm.launch(android.Manifest.permission.RECORD_AUDIO) }
                    else { stop(); recording = false }
                }) { Text(if (recording) "■ Stop" else "● Record sample") }
                OutlinedButton(onClick = {
                    busy = "Creating your voice"
                    scope.launch { runCatching { VoiceClone.create(ctx, sample, "Atlas – me") }.onSuccess { voice = it; Feedback.show("Voice created") }.onFailure { Feedback.show(it.message ?: "Failed") }; busy = null }
                }, enabled = !recording && sample.exists() && key.isNotBlank() && busy == null) { Text("Create voice") }
            }
        } }
        if (voice.isNotBlank()) item("t") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            Text("3 · Type what you want said", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.padding(vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                listOf("As written", "English", "Urdu", "Arabic", "Chinese").forEach { l -> Capsule(l, lang == l) { lang = l } } }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 100.dp))
            Button(onClick = {
                busy = "Speaking"
                scope.launch {
                    runCatching {
                        val say = if (lang == "As written") text else Ai.generate(container.settingsRepository, "Translate into natural spoken $lang. Reply with the translation only:\n$text", maxOutputTokens = 800)
                        VoiceClone.speak(ctx, say)
                    }.onSuccess { f ->
                        player.setMediaItem(androidx.media3.common.MediaItem.fromUri(Uri.fromFile(f))); player.prepare(); player.play()
                        Feedback.show("Playing", "Share") { Share.file(ctx, f, "audio/mpeg") }
                    }.onFailure { Feedback.show(it.message ?: "Failed") }
                    busy = null
                }
            }, enabled = text.isNotBlank() && busy == null, modifier = Modifier.padding(top = Space.s)) { Text(busy ?: "Say it in my voice") }
        } }
        busy?.let { b -> item("b") { Text("$b…", Modifier.padding(Space.page)) } }
    }
}

// ─────────────────────────────── Comic & dream pictures ───────────────────────────────
@Composable
fun PictureItScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf("Comic") }
    var text by remember { mutableStateOf("") }
    var panels by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val style = listOf("Comic book", "Anime", "Watercolour", "Pixel", "Islamic art")
    var st by remember { mutableStateOf("Comic book") }
    AtlasScreen(title = "Comic & dream pictures", subtitle = "Words in, pictures out", hub = Hub.Create) {
        item("m") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            listOf("Comic", "Dream").forEach { m -> Capsule(if (m == "Comic") "Comic of my week" else "Dream to image", mode == m) { mode = m; panels = emptyList() } } } }
        item("s") { LazyRow(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            items(style.size) { i -> Capsule(style[i], st == style[i]) { st = style[i] } } } }
        item("in") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 110.dp),
                placeholder = { Text(if (mode == "Comic") "What happened this week? (Monday exam, Wednesday biryani at Nani's, Friday cricket win…)" else "Describe the dream") })
            Button(onClick = {
                busy = true; panels = emptyList()
                scope.launch {
                    runCatching {
                        if (mode == "Dream") listOf("" to Imagine.url("dreamlike surreal scene, $st style: $text", "Portrait", (0..99999).random()))
                        else {
                            val j = JSONObject(Ai.cleanJson(Ai.generate(container.settingsRepository,
                                "Turn this week into a 4-panel comic. For each panel: a short funny caption (max 12 words) and an image prompt (max 30 words, same main character described consistently).\n$text\n" +
                                    "Reply JSON only: {\"panels\":[{\"caption\":\"...\",\"prompt\":\"...\"}]}", maxOutputTokens = 700)))
                            val a = j.optJSONArray("panels") ?: JSONArray()
                            val seed = (0..99999).random()
                            (0 until minOf(4, a.length())).map { i -> val p = a.getJSONObject(i)
                                p.optString("caption") to Imagine.url("${p.optString("prompt")}, $st style, single comic panel", "Square", seed + i) }
                        }
                    }.onSuccess { panels = it; it.forEach { (_, u) -> Imagine.remember(ctx, u) } }.onFailure { Feedback.show(it.message ?: "Failed") }
                    busy = false
                }
            }, enabled = text.isNotBlank() && !busy, modifier = Modifier.padding(top = Space.s)) { Text(if (busy) "Drawing…" else "Draw it") }
        } }
        panels.forEachIndexed { i, (cap, url) -> item("p$i") { Column(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            coil.compose.AsyncImage(url, null, Modifier.fillMaxWidth().aspectRatio(if (mode == "Dream") 0.68f else 1f).background(MaterialTheme.colorScheme.surfaceVariant))
            if (cap.isNotBlank()) Text("${i + 1}. $cap", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
        } } }
        if (panels.isNotEmpty()) item("sh") { OutlinedButton(onClick = { Share.text(ctx, panels.joinToString("\n\n") { (c, u) -> "$c\n$u" }) }, modifier = Modifier.padding(Space.page)) { Text("Share links") } }
    }
}

// ─────────────────────────────── Sky & moon ───────────────────────────────
private object Astro {
    private fun rad(d: Double) = d * PI / 180
    private fun deg(r: Double) = r * 180 / PI
    private fun norm(d: Double) = ((d % 360) + 360) % 360
    fun daysJ2000(ms: Long) = ms / 86_400_000.0 + 2440587.5 - 2451545.0

    /** Returns (azimuth, altitude) in degrees for right ascension / declination in degrees. */
    fun horizon(raDeg: Double, decDeg: Double, d: Double, lat: Double, lon: Double): Pair<Double, Double> {
        val gmst = norm(280.46061837 + 360.98564736629 * d)
        val h = rad(norm(gmst + lon - raDeg)); val dec = rad(decDeg); val phi = rad(lat)
        val alt = asin(sin(phi) * sin(dec) + cos(phi) * cos(dec) * cos(h))
        val az = atan2(sin(h), cos(h) * sin(phi) - tan(dec) * cos(phi))
        return norm(deg(az) + 180) to deg(alt)
    }
    private fun eclToEq(lonDeg: Double, latDeg: Double, d: Double): Pair<Double, Double> {
        val e = rad(23.439 - 0.0000004 * d); val l = rad(lonDeg); val b = rad(latDeg)
        val ra = atan2(sin(l) * cos(e) - tan(b) * sin(e), cos(l)); val dec = asin(sin(b) * cos(e) + cos(b) * sin(e) * sin(l))
        return norm(deg(ra)) to deg(dec)
    }
    fun sun(d: Double): Pair<Double, Double> {
        val g = rad(norm(357.529 + 0.98560028 * d)); val q = norm(280.459 + 0.98564736 * d)
        return eclToEq(q + 1.915 * sin(g) + 0.020 * sin(2 * g), 0.0, d)
    }
    fun moon(d: Double): Pair<Double, Double> {
        val l = norm(218.316 + 13.176396 * d); val m = rad(norm(134.963 + 13.064993 * d)); val f = rad(norm(93.272 + 13.229350 * d))
        return eclToEq(l + 6.289 * sin(m), 5.128 * sin(f), d)
    }
    const val SYNODIC = 29.530588853
    fun moonAge(ms: Long): Double { val jd = ms / 86_400_000.0 + 2440587.5; return (((jd - 2451550.1) % SYNODIC) + SYNODIC) % SYNODIC }
    fun illumination(age: Double) = (1 - cos(2 * PI * age / SYNODIC)) / 2
    fun phaseName(age: Double) = when { age < 1.84 -> "New moon" to "🌑"; age < 5.53 -> "Waxing crescent" to "🌒"; age < 9.22 -> "First quarter" to "🌓"; age < 12.91 -> "Waxing gibbous" to "🌔"
        age < 16.61 -> "Full moon" to "🌕"; age < 20.30 -> "Waning gibbous" to "🌖"; age < 23.99 -> "Last quarter" to "🌗"; age < 27.68 -> "Waning crescent" to "🌘"; else -> "New moon" to "🌑" }
}

@Composable
fun SkyScreen(container: AppContainer) {
    val ctx = LocalContext.current
    var lat by remember { mutableStateOf(31.55) }; var lon by remember { mutableStateOf(74.34) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var heading by remember { mutableStateOf<Double?>(null) }
    LaunchedEffect(Unit) {
        runCatching { lat = container.settingsRepository.latitude.first(); lon = container.settingsRepository.longitude.first() }
        while (true) { delay(30_000); now = System.currentTimeMillis() }
    }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(android.hardware.SensorManager::class.java)
        val l = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val m = FloatArray(9); android.hardware.SensorManager.getRotationMatrixFromVector(m, e.values); val o = FloatArray(3); android.hardware.SensorManager.getOrientation(m, o)
                heading = (Math.toDegrees(o[0].toDouble()) + 360) % 360
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sm.registerListener(l, it, android.hardware.SensorManager.SENSOR_DELAY_UI) }
        onDispose { sm?.unregisterListener(l) }
    }
    val d = Astro.daysJ2000(now)
    val (sa, sal) = Astro.sun(d).let { (ra, dec) -> Astro.horizon(ra, dec, d, lat, lon) }
    val (ma, mal) = Astro.moon(d).let { (ra, dec) -> Astro.horizon(ra, dec, d, lat, lon) }
    val age = Astro.moonAge(now); val (pname, emoji) = Astro.phaseName(age)
    val toFull = ((14.765 - age + Astro.SYNODIC) % Astro.SYNODIC); val toNew = Astro.SYNODIC - age
    val dirs = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    fun dir(a: Double) = dirs[((a + 22.5) / 45).toInt() % 8]
    AtlasScreen(title = "$emoji $pname", subtitle = "${(Astro.illumination(age) * 100).toInt()}% lit · day ${age.toInt() + 1} of the lunar month", hub = Hub.Deen) {
        item("pointer") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            Box(Modifier.fillMaxWidth().height(230.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(210.dp).rotate(-(heading ?: 0.0).toFloat())) {
                    drawCircle(Color.Gray, style = Stroke(3f))
                    fun mark(az: Double, alt: Double, c: Color) {
                        val r = size.width / 2 * (1 - (alt.coerceIn(0.0, 90.0) / 90.0)).toFloat() * 0.92f
                        val a = Math.toRadians(az - 90)
                        drawCircle(if (alt < 0) c.copy(alpha = 0.3f) else c, radius = 16f, center = Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat()))
                    }
                    drawLine(Color(0xFFD9342B), center, Offset(center.x, 10f), strokeWidth = 5f)
                    mark(sa, sal, Color(0xFFF5B301)); mark(ma, mal, Color(0xFFCFD8E3))
                }
            }
            Text("Hold the phone flat. Red line is north; yellow is the sun, silver the moon (faint = below the horizon).", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        } }
        item("rows") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
            listOf("Moon" to (if (mal > 0) "${mal.toInt()}° up, towards ${dir(ma)} (${ma.toInt()}°)" else "Below the horizon (${dir(ma)})"),
                "Sun" to (if (sal > 0) "${sal.toInt()}° up, towards ${dir(sa)} (${sa.toInt()}°)" else "Below the horizon"),
                "Next full moon" to LocalDate.now().plusDays(toFull.toLong()).toString(),
                "Next new moon" to "${LocalDate.now().plusDays(toNew.toLong())} (crescent sighting 1–2 evenings after)").forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(k, Modifier.weight(0.4f), color = MaterialTheme.colorScheme.onSurfaceVariant); Text(v, Modifier.weight(0.6f), fontWeight = FontWeight.SemiBold) }
            }
        } }
        item("ay") { Text("Ayyam al-Bid (the white days, recommended fasts) fall on the 13th, 14th and 15th of each lunar month. Moon sighting for Islamic dates follows the local committee.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(Space.page)) }
    }
}

// ─────────────────────────────── Offline library ───────────────────────────────
private val PACKS = linkedMapOf(
    "First aid" to listOf("First aid", "Cardiopulmonary resuscitation", "Choking", "Burn", "Snakebite", "Heat stroke", "Dengue fever", "Fracture", "Bleeding", "Dehydration", "Poisoning", "Hypoglycemia"),
    "Pakistan" to listOf("Pakistan", "History of Pakistan", "Muhammad Ali Jinnah", "Muhammad Iqbal", "Lahore", "Karachi", "Islamabad", "Indus River", "K2", "Constitution of Pakistan"),
    "Islam basics" to listOf("Five Pillars of Islam", "Salah", "Zakat", "Sawm", "Hajj", "Quran", "Hadith", "Muhammad", "Islamic calendar", "Eid al-Adha", "Eid al-Fitr"),
    "Home & survival" to listOf("Water purification", "Food preservation", "Earthquake preparedness", "Flood", "Electrical safety", "Solar power", "Gardening"),
)

object Wiki {
    fun store(c: Context) = JsonStore(c, "wiki_offline")
    suspend fun search(q: String, lang: String): List<String> = runCatching {
        val a = JSONArray(Http.getJson("https://$lang.wikipedia.org/w/api.php?action=opensearch&limit=10&format=json&search=" + java.net.URLEncoder.encode(q, "UTF-8")))
        a.getJSONArray(1).let { r -> (0 until r.length()).map { r.getString(it) } }
    }.getOrDefault(emptyList())
    suspend fun save(c: Context, title: String, lang: String): Boolean = runCatching {
        if (store(c).all().any { it.optString("title").equals(title, true) && it.optString("lang") == lang }) return@runCatching true
        val j = JSONObject(Http.getJson("https://$lang.wikipedia.org/w/api.php?action=query&prop=extracts&explaintext=1&redirects=1&format=json&titles=" + java.net.URLEncoder.encode(title, "UTF-8")))
        val pages = j.getJSONObject("query").getJSONObject("pages"); val p = pages.getJSONObject(pages.keys().next())
        val text = p.optString("extract"); if (text.isBlank()) return@runCatching false
        store(c).add(JSONObject().put("title", p.optString("title", title)).put("lang", lang).put("text", text).put("t", System.currentTimeMillis())); true
    }.getOrDefault(false)
}

@Composable
fun WikiScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(Wiki.store(ctx).all()) }
    var q by remember { mutableStateOf("") }
    var lang by remember { mutableStateOf("en") }
    var results by remember { mutableStateOf<List<String>>(emptyList()) }
    var reading by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    val r = reading
    if (r != null) {
        AtlasScreen(title = r.optString("title"), subtitle = "Saved offline · Wikipedia (CC BY-SA)", hub = Hub.Learn, actions = {
            Text("Close", Modifier.padding(Space.s).pressable { reading = null }, style = MaterialTheme.typography.labelLarge) }) {
            r.optString("text").split("\n").filter { it.isNotBlank() }.forEachIndexed { i, para -> item("p$i") {
                val head = para.trim().startsWith("==")
                Text(para.trim().trim('=', ' '), Modifier.padding(horizontal = Space.page, vertical = if (head) 10.dp else 4.dp),
                    style = if (head) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                    textAlign = if (r.optString("lang") == "ur") TextAlign.End else TextAlign.Start)
            } }
        }
        return
    }
    val offlineHits = if (q.isBlank()) saved else saved.filter { it.optString("title").contains(q, true) || it.optString("text").contains(q, true) }
    AtlasScreen(title = "Offline library", subtitle = "${saved.size} articles on this phone" + (busy?.let { " · $it" } ?: ""), hub = Hub.Learn) {
        item("q") { Row(Modifier.padding(horizontal = Space.page), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text("Search saved, or find online") }, singleLine = true)
            Button(onClick = { scope.launch { results = Wiki.search(q, lang) } }, enabled = q.isNotBlank()) { Text("Online") }
        } }
        item("lang") { Row(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Capsule("English", lang == "en") { lang = "en" }; Capsule("اردو", lang == "ur") { lang = "ur" } } }
        if (results.isNotEmpty()) {
            item("rh") { SectionHeader("Tap to save for offline") }
            results.forEach { t -> item("r$t") { ListRow(t, icon = Icons.Outlined.Download, tint = Hub.Learn.tint()) {
                scope.launch { busy = "Saving"; if (Wiki.save(ctx, t, lang)) { saved = Wiki.store(ctx).all(); Feedback.show("Saved: $t") } else Feedback.show("Couldn't save"); busy = null }
            } } }
        }
        item("ph") { SectionHeader("Packs") }
        PACKS.forEach { (name, titles) -> item("pk$name") {
            ListRow(name, supporting = "${titles.size} articles", icon = Icons.Outlined.LibraryBooks, tint = Hub.Learn.tint()) {
                scope.launch { var n = 0; titles.forEachIndexed { i, t -> busy = "Saving ${i + 1}/${titles.size}"; if (Wiki.save(ctx, t, "en")) n++ }
                    saved = Wiki.store(ctx).all(); busy = null; Feedback.show("$name: $n articles saved") }
            }
        } }
        item("sh") { SectionHeader("On this phone") }
        if (offlineHits.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.LocalLibrary, "Nothing saved yet", "Download a pack, or search and save articles.")) }
        offlineHits.sortedBy { it.optString("title") }.forEach { a -> item(a.optString("id")) {
            ListRow(a.optString("title"), supporting = "${a.optString("text").length / 1500 + 1} min read · ${if (a.optString("lang") == "ur") "اردو" else "English"}", icon = Icons.Outlined.Article, trailing = {
                Text("✕", Modifier.padding(Space.s).pressable { Wiki.store(ctx).remove(a.optString("id")); saved = Wiki.store(ctx).all() })
            }) { reading = a }
        } }
    }
}
