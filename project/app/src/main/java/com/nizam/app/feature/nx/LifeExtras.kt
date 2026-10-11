package com.nizam.app.feature.nx

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nizam.app.AppContainer
import com.nizam.app.net.Ai
import com.nizam.app.net.Http
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Art
import com.nizam.app.ui.design.Art3D
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

object LifeExtras {
    val all: List<Spec> = listOf(
        ScreenSpec("localhelp", "Local help", "Nearest pharmacy, hospital, police, mechanic, fuel, ATM", Icons.Outlined.LocalHospital, Hub.Travel, "near me pharmacy hospital police mechanic petrol atm emergency help") { c, _ -> LocalHelpScreen(c) },
        AiSpec("groupbrain", "Group brain", "Paste a group chat: decisions, plans, who owes whom", Icons.Outlined.Groups, Hub.Social, "group chat whatsapp summary decisions plans owes family",
            listOf(F("chat", "Group chat (export or paste)", K.LONG), F("ask", "What do you need?", K.CHOICE, options = listOf("Summary", "Decisions and plans", "Who owes whom", "Open questions", "Who said what about…"), default = "Summary"),
                F("q", "Anything specific?", optional = true)),
            "You read group chats for someone who missed them. Be concrete: names, dates, amounts. Never invent messages that aren't there.", tokens = 1400) { v ->
            "${v["ask"]}${v["q"]?.takeIf { it.isNotBlank() }?.let { " — $it" } ?: ""}\n\nChat:\n${v["chat"]?.take(30000)}" },
        ScreenSpec("duacircle", "Du'a circle", "Keep a list of who to pray for; share it with family", Icons.Outlined.VolunteerActivism, Hub.Deen, "dua circle pray for family sick exam list share") { _, _ -> DuaCircleScreen() },
        ScreenSpec("moodmusic", "Mood music", "Pick how you feel; get live stations that fit", Icons.Outlined.LibraryMusic, Hub.Media, "mood music radio feel calm sad happy focus") { _, o -> MoodMusicScreen(o) },
        ScreenSpec("timelapse", "Life time-lapse", "Your photos played as a time-lapse, oldest to newest", Icons.Outlined.Timelapse, Hub.Memory, "time lapse photos growing up years slideshow") { _, _ -> TimeLapseScreen() },
        ScreenSpec("offlinevoice", "Offline voice", "Speech and voices that work without internet", Icons.Outlined.RecordVoiceOver, Hub.Tools, "offline speech recognition voice tts no internet download") { _, _ -> OfflineVoiceScreen() },
        ScreenSpec("pausecutter", "Pause cutter", "Removes long silences from a recording", Icons.Outlined.ContentCut, Hub.Create, "silence remover pauses cut audio podcast voice note trim") { _, _ -> PauseCutterScreen() },
        ScreenSpec("dubbing", "Dub a video", "Any video with subtitles, spoken in your language", Icons.Outlined.Translate, Hub.Media, "dub dubbing translate video voice urdu subtitles srt") { c, _ -> DubScreen(c) },
        ScreenSpec("facegroups", "Face groups", "Groups your photos by the faces in them", Icons.Outlined.Face, Hub.Memory, "faces people photos group who") { _, _ -> FaceGroupsScreen() },
        ScreenSpec("freezer", "App freezer", "Freeze apps you don't use (via Shizuku)", Icons.Outlined.AcUnit, Hub.Tools, "freeze disable apps bloatware shizuku battery") { _, _ -> FreezerScreen() },
    )
}

private fun encU(s: String) = URLEncoder.encode(s, "UTF-8")

// ─────────────────────────────── Local help ───────────────────────────────
private val HELP = listOf(
    Triple("Pharmacy", "💊", "[amenity=pharmacy]"), Triple("Hospital", "🏥", "[amenity~\"hospital|clinic\"]"), Triple("Police", "🚓", "[amenity=police]"),
    Triple("Mechanic", "🔧", "[shop~\"car_repair|motorcycle_repair|tyres\"]"), Triple("Fuel", "⛽", "[amenity=fuel]"), Triple("ATM", "🏧", "[amenity~\"atm|bank\"]"),
    Triple("Mosque", "🕌", "[amenity=place_of_worship][religion=muslim]"), Triple("Grocery", "🛒", "[shop~\"supermarket|convenience|general\"]"))

private data class HelpPlace(val name: String, val lat: Double, val lon: Double, val dist: Double, val phone: String?, val hours: String?)

@Composable
fun LocalHelpScreen(container: AppContainer) {
    val c = LocalContext.current
    var kind by remember { mutableStateOf(HELP.first()) }
    var list by remember { mutableStateOf<List<HelpPlace>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(kind) {
        list = null; err = null
        runCatching {
            val lat = container.settingsRepository.latitude.first(); val lon = container.settingsRepository.longitude.first()
            val d = 0.035
            val q = "[out:json][timeout:20];(nwr${kind.third}(${lat - d},${lon - d},${lat + d},${lon + d}););out center tags 60;"
            val body = listOf("https://overpass-api.de/api/interpreter", "https://overpass.kumi.systems/api/interpreter").firstNotNullOfOrNull { h ->
                runCatching { Http.getJson("$h?data=${encU(q)}") }.getOrNull() } ?: error("The map service is busy — try again")
            val els = JSONObject(body).optJSONArray("elements") ?: JSONArray()
            (0 until els.length()).map { els.getJSONObject(it) }.mapNotNull { e ->
                val t = e.optJSONObject("tags") ?: return@mapNotNull null
                val la = if (e.has("lat")) e.optDouble("lat") else e.optJSONObject("center")?.optDouble("lat") ?: return@mapNotNull null
                val lo = if (e.has("lon")) e.optDouble("lon") else e.optJSONObject("center")?.optDouble("lon") ?: return@mapNotNull null
                val dy = (la - lat) * 111.0; val dx = (lo - lon) * 111.0 * cos(Math.toRadians(lat))
                HelpPlace(t.optString("name").ifBlank { t.optString("brand").ifBlank { kind.first } }, la, lo, sqrt(dx * dx + dy * dy),
                    t.optString("phone").ifBlank { t.optString("contact:phone") }.ifBlank { null }, t.optString("opening_hours").ifBlank { null })
            }.sortedBy { it.dist }
        }.onSuccess { list = it }.onFailure { err = Feedback.friendly(it) }
    }
    AtlasScreen(title = "Local help", subtitle = "Within about 4 km of your saved location", hub = Hub.Travel) {
        item("k") { Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HELP.forEach { h -> Capsule("${h.second} ${h.first}", h == kind) { kind = h } } } }
        item("e") { Row(Modifier.padding(Space.page), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { c.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1122"))) }) { Text("🚑 Rescue 1122") }
            OutlinedButton(onClick = { c.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:15"))) }) { Text("🚓 Police 15") }
            OutlinedButton(onClick = { c.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:16"))) }) { Text("🚒 Fire 16") }
        } }
        val l = list
        if (l == null && err == null) item("l") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(Space.page)) }
        err?.let { item("err") { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(Space.page)) } }
        if (l != null && l.isEmpty()) item("n") { Text("Nothing mapped nearby. OpenStreetMap may not have it yet.", modifier = Modifier.padding(Space.page)) }
        l?.forEachIndexed { i, p -> item("p$i") {
            ListRow(p.name, supporting = "%.1f km".format(p.dist) + (p.hours?.let { " · $it" } ?: ""), icon = Icons.Outlined.Place, trailing = {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    p.phone?.let { ph -> Capsule("Call", false) { runCatching { c.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${ph.substringBefore(';').trim()}"))) } } }
                    Capsule("Go", true) { runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${encU(p.name)})"))) } }
                }
            })
        } }
    }
}

// ─────────────────────────────── Du'a circle ───────────────────────────────
@Composable
fun DuaCircleScreen() {
    val c = LocalContext.current
    val store = remember { com.nizam.app.feature.next.JsonStore(c, "duacircle") }
    var list by remember { mutableStateOf(store.all()) }
    var who by remember { mutableStateOf("") }
    var what by remember { mutableStateOf("") }
    fun save(l: List<JSONObject>) { store.save(l); list = store.all() }
    AtlasScreen(title = "Du'a circle", subtitle = "${list.size} people in your du'as · ${list.sumOf { it.optInt("n") }} times prayed", hub = Hub.Deen) {
        item("a") { Group(Modifier.padding(horizontal = Space.page)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Art3D("prayer_beads", 48.dp); Text("Who needs your du'a?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 8.dp)) }
            OutlinedTextField(who, { who = it }, Modifier.fillMaxWidth(), label = { Text("For") }, singleLine = true, placeholder = { Text("Ammi, Ali's exam, Khala in hospital") })
            OutlinedTextField(what, { what = it }, Modifier.fillMaxWidth(), label = { Text("Du'a (optional)") }, minLines = 2)
            Button(onClick = { save(list + JSONObject().put("who", who.trim()).put("what", what.trim()).put("n", 0).put("at", java.time.LocalDate.now().toString())); who = ""; what = "" },
                enabled = who.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) { Text("Add") }
        } }
        if (list.isNotEmpty()) item("s") { Row(Modifier.padding(Space.page), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { Share.text(c, "🤲 Our du'a circle — please remember them:\n\n" + list.joinToString("\n") { "• ${it.optString("who")}" +
                it.optString("what").takeIf { w -> w.isNotBlank() }?.let { w -> " — $w" }.orEmpty() }) }) { Text("Share with family") }
            OutlinedButton(onClick = { Share.text(c, AtlasCard.encode(JSONObject().put("v", 1).put("kind", "List").put("from", "").put("title", "Du'a circle")
                .put("body", list.joinToString("\n") { "Du'a for " + it.optString("who") }))) }) { Text("Send to their Atlas") }
        } }
        list.forEachIndexed { i, o -> item("d$i-${o.optInt("n")}") {
            ListRow(o.optString("who"), supporting = listOf(o.optString("what"), "prayed ${o.optInt("n")}×").filter { it.isNotBlank() }.joinToString(" · "),
                icon = Icons.Outlined.VolunteerActivism, trailing = {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Capsule("🤲 Prayed", false) { save(list.mapIndexed { j, x -> if (j == i) JSONObject(x.toString()).put("n", x.optInt("n") + 1) else x }); Feedback.show("May Allah accept it") }
                        Capsule("✓", false) { save(list.filterIndexed { j, _ -> j != i }); Feedback.show("Removed — alhamdulillah if it was answered") }
                    }
                })
        } }
    }
}

// ─────────────────────────────── Mood music ───────────────────────────────
private val MOOD_TAGS = mapOf("Amazing" to "party", "Loved" to "romantic", "Happy" to "pop", "Good" to "top 40", "Calm" to "chillout", "Okay" to "lounge",
    "Tired" to "ambient", "Anxious" to "meditation", "Sad" to "acoustic", "Angry" to "rock")

@Composable
fun MoodMusicScreen(open: (String) -> Unit) {
    val c = LocalContext.current
    var mood by remember { mutableStateOf<String?>(null) }
    var list by remember { mutableStateOf<List<com.nizam.app.feature.music.Track>?>(null) }
    var quran by remember { mutableStateOf(false) }
    LaunchedEffect(mood, quran) {
        val m = mood ?: return@LaunchedEffect
        list = null
        list = runCatching { com.nizam.app.feature.music.Radio.tag(if (quran) "quran" else MOOD_TAGS[m] ?: "chillout") }.getOrElse { emptyList() }
    }
    AtlasScreen(title = "Mood music", subtitle = mood?.let { "Feeling $it" } ?: "How are you feeling?", hub = Hub.Media) {
        item("m") { Column(Modifier.padding(horizontal = Space.page)) {
            Art.MOODS.chunked(5).forEach { row -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { (art, name) -> Column(Modifier.clip(RoundedCornerShape(16.dp)).background(if (mood == name) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { mood = name }.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Art3D(art, 48.dp, float = mood == name); Text(name, style = MaterialTheme.typography.labelSmall)
                } }
            } }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Switch(quran, { quran = it }); Text("  Quran recitation instead", style = MaterialTheme.typography.bodyMedium)
            }
        } }
        if (mood != null && list == null) item("l") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(Space.page)) }
        list?.let { l ->
            if (l.isNotEmpty()) item("all") { Row(Modifier.padding(horizontal = Space.page, vertical = 8.dp)) {
                Button(onClick = { com.nizam.app.feature.music.MusicPlayback.play(c, l, 0); Feedback.show("Playing ${l.first().title}") }) { Text("▶ Play the mood") } } }
            l.forEachIndexed { i, t -> item("t$i") { ListRow(t.title, supporting = t.artist, icon = Icons.Outlined.Radio) {
                com.nizam.app.feature.music.MusicPlayback.play(c, l, i); Feedback.show("Playing ${t.title}") } } }
        }
    }
}

// ─────────────────────────────── Life time-lapse ───────────────────────────────
private fun takenAt(c: Context, u: Uri): Long = runCatching {
    c.contentResolver.query(u, arrayOf(android.provider.MediaStore.MediaColumns.DATE_TAKEN, android.provider.MediaStore.MediaColumns.DATE_MODIFIED), null, null, null)?.use { cur ->
        if (cur.moveToFirst()) { val a = cur.getLong(0); if (a > 0) a else cur.getLong(1) * 1000 } else 0L }
}.getOrNull() ?: 0L

private fun thumb(c: Context, u: Uri, max: Int): Bitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    c.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, o) }
    var s = 1; while (o.outWidth / s > max * 2 || o.outHeight / s > max * 2) s *= 2
    c.contentResolver.openInputStream(u)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }
}.getOrNull()

@Composable
fun TimeLapseScreen() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var frames by remember { mutableStateOf<List<Pair<Long, Bitmap>>>(emptyList()) }
    var loading by remember { mutableFloatStateOf(-1f) }
    var idx by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var speed by remember { mutableFloatStateOf(4f) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            loading = 0f
            val out = withContext(Dispatchers.IO) { uris.take(400).mapIndexedNotNull { i, u ->
                loading = i / uris.size.toFloat(); thumb(c, u, 900)?.let { takenAt(c, u) to it } }.sortedBy { it.first } }
            frames = out; idx = 0; loading = -1f; playing = true
        }
    }
    LaunchedEffect(playing, speed, frames) {
        while (playing && frames.isNotEmpty() && isActive) { delay((1000 / speed).toLong()); idx = (idx + 1) % frames.size }
    }
    AtlasScreen(title = "Life time-lapse", subtitle = if (frames.isEmpty()) "Pick photos of one person, place or plant over time" else "${frames.size} photos", hub = Hub.Memory) {
        item("p") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pick.launch("image/*") }) { Text(if (frames.isEmpty()) "Pick photos" else "Pick again") }
            if (frames.isNotEmpty()) OutlinedButton(onClick = { playing = !playing }) { Text(if (playing) "Pause" else "Play") }
        } }
        if (loading >= 0f) item("l") { LinearProgressIndicator({ loading }, Modifier.fillMaxWidth().padding(Space.page)) }
        if (frames.isNotEmpty()) {
            item("v") { Box(Modifier.padding(Space.page).fillMaxWidth().aspectRatio(0.8f).clip(RoundedCornerShape(24.dp)).background(androidx.compose.ui.graphics.Color.Black)) {
                Crossfade(idx, animationSpec = tween((600 / speed).toInt().coerceAtLeast(60)), label = "tl") { i ->
                    frames.getOrNull(i)?.let { Image(it.second.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
                val t = frames.getOrNull(idx)?.first ?: 0L
                if (t > 0) Text(java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()).toLocalDate().let { "${it.month.name.take(3)} ${it.year}" },
                    color = androidx.compose.ui.graphics.Color.White, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
                        .clip(RoundedCornerShape(50)).background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.5f)).padding(horizontal = 12.dp, vertical = 4.dp))
            } }
            item("s") { Column(Modifier.padding(horizontal = Space.page)) {
                Text("Speed: ${speed.toInt()} photos a second", style = MaterialTheme.typography.labelMedium)
                Slider(speed, { speed = it }, valueRange = 1f..15f)
                Slider(idx.toFloat(), { playing = false; idx = it.toInt() }, valueRange = 0f..(frames.size - 1).coerceAtLeast(1).toFloat())
            } }
        }
    }
}

// ─────────────────────────────── Offline voice ───────────────────────────────
object OfflineVoice {
    fun prefer(c: Context) = c.getSharedPreferences("voice", 0).getBoolean("offline", false)
    fun setPrefer(c: Context, v: Boolean) = c.getSharedPreferences("voice", 0).edit().putBoolean("offline", v).apply()
}

@Composable
fun OfflineVoiceScreen() {
    val c = LocalContext.current
    var voices by remember { mutableStateOf<List<android.speech.tts.Voice>>(emptyList()) }
    var prefer by remember { mutableStateOf(OfflineVoice.prefer(c)) }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(Unit) {
        var t: TextToSpeech? = null
        t = TextToSpeech(c) { st -> if (st == TextToSpeech.SUCCESS) { voices = runCatching { t?.voices?.filter { !it.isNetworkConnectionRequired }?.sortedBy { it.locale.toLanguageTag() } }.getOrNull().orEmpty() } }
        tts = t
        onDispose { t?.shutdown() }
    }
    val onDevice = if (Build.VERSION.SDK_INT >= 31) runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(c) }.getOrDefault(false) else false
    AtlasScreen(title = "Offline voice", subtitle = "Talk and listen without internet", hub = Hub.Tools) {
        item("r") { Group(Modifier.padding(horizontal = Space.page)) {
            Text("Speech recognition", style = MaterialTheme.typography.titleMedium)
            Text(if (onDevice) "This phone can recognise speech on-device." else "On-device recognition isn't available here; Google's offline pack may still work.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Switch(prefer, { prefer = it; OfflineVoice.setPrefer(c, it) }); Text("  Prefer offline for voice commands", style = MaterialTheme.typography.bodyMedium)
            }
            if (Build.VERSION.SDK_INT >= 33 && onDevice) OutlinedButton(onClick = {
                runCatching { SpeechRecognizer.createOnDeviceSpeechRecognizer(c).apply {
                    triggerModelDownload(com.nizam.app.feature.voice.speechIntent()); Feedback.show("Downloading the offline speech model…") } }
                    .onFailure { Feedback.failed("Model download", it) }
            }, modifier = Modifier.padding(top = 6.dp)) { Text("Download offline speech model") }
        } }
        item("t") { Group(Modifier.padding(Space.page)) {
            Text("Voices", style = MaterialTheme.typography.titleMedium)
            Text("${voices.size} voices work offline on this phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { runCatching { c.startActivity(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Feedback.show("No voice installer on this phone") } }) { Text("Get more voices") }
                OutlinedButton(onClick = { runCatching { c.startActivity(Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Voice settings") }
            }
        } }
        voices.groupBy { it.locale.displayName }.entries.take(60).forEachIndexed { i, (lang, vs) -> item("v$i") {
            ListRow(lang, supporting = "${vs.size} voice${if (vs.size > 1) "s" else ""} · quality ${vs.maxOf { it.quality }}", icon = Icons.Outlined.RecordVoiceOver, trailing = {
                Capsule("▶ Try", false) { tts?.let { t -> t.voice = vs.maxBy { it.quality }; t.speak(if (vs.first().locale.language == "ur") "السلام علیکم، میں ایٹلس ہوں" else "Assalamu alaikum, I'm Atlas, working offline.", TextToSpeech.QUEUE_FLUSH, null, "try") } }
            })
        } }
    }
}

// ─────────────────────────────── Pause cutter ───────────────────────────────
object PauseCutter {
    data class Result(val out: File, val beforeMs: Long, val afterMs: Long)

    /** Decodes any audio file to PCM, drops silent stretches longer than [maxGapMs], writes a WAV. */
    fun run(c: Context, uri: Uri, maxGapMs: Int, threshold: Float, progress: (Float) -> Unit): Result {
        val ex = MediaExtractor(); ex.setDataSource(c, uri, null)
        val track = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else 0L
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0); codec.start()
        var rate = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        val pcm = java.io.ByteArrayOutputStream()
        val info = MediaCodec.BufferInfo(); var inDone = false; var outDone = false
        while (!outDone) {
            if (!inDone) { val i = codec.dequeueInputBuffer(10_000); if (i >= 0) {
                val n = ex.readSampleData(codec.getInputBuffer(i)!!, 0)
                if (n < 0) { codec.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                else { codec.queueInputBuffer(i, 0, n, ex.sampleTime, 0); if (durUs > 0) progress(ex.sampleTime / durUs.toFloat() * 0.8f); ex.advance() } } }
            val o = codec.dequeueOutputBuffer(info, 10_000)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) { codec.outputFormat.let { rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) } }
            else if (o >= 0) {
                val b = codec.getOutputBuffer(o)!!; val arr = ByteArray(info.size); b.position(info.offset); b.get(arr); pcm.write(arr)
                codec.releaseOutputBuffer(o, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
            }
        }
        codec.stop(); codec.release(); ex.release()
        val bytes = pcm.toByteArray()
        val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val frame = (rate * ch / 50).coerceAtLeast(ch)                 // 20 ms
        val nFrames = samples.limit() / frame
        val loud = BooleanArray(nFrames) { f ->
            var sum = 0.0; for (k in 0 until frame) { val v = samples.get(f * frame + k) / 32768.0; sum += v * v }
            sqrt(sum / frame) > threshold
        }
        val keepPad = 8                                                  // 160 ms of air around speech
        val maxGap = maxGapMs / 20
        val keep = BooleanArray(nFrames) { true }
        var f = 0
        while (f < nFrames) {
            if (!loud[f]) { var e = f; while (e < nFrames && !loud[e]) e++
                if (e - f > maxGap) for (k in (f + keepPad) until (e - keepPad).coerceAtLeast(f + keepPad)) keep[k] = false
                f = e } else f++
        }
        progress(0.9f)
        val out = File(c.cacheDir, "share").apply { mkdirs() }.let { File(it, "trimmed_${System.currentTimeMillis()}.wav") }
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0); raf.write(ByteArray(44))
            val bb = ByteBuffer.allocate(frame * 2).order(ByteOrder.LITTLE_ENDIAN)
            var kept = 0L
            for (k in 0 until nFrames) if (keep[k]) { bb.clear(); for (j in 0 until frame) bb.putShort(samples.get(k * frame + j)); raf.write(bb.array()); kept++ }
            val dataLen = kept * frame * 2
            val h = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            h.put("RIFF".toByteArray()).putInt((36 + dataLen).toInt()).put("WAVE".toByteArray()).put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(ch.toShort())
                .putInt(rate).putInt(rate * ch * 2).putShort((ch * 2).toShort()).putShort(16).put("data".toByteArray()).putInt(dataLen.toInt())
            raf.seek(0); raf.write(h.array())
            return Result(out, nFrames * 20L, kept * 20L)
        }
    }
}

@Composable
fun PauseCutterScreen() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var gap by remember { mutableFloatStateOf(600f) }
    var sens by remember { mutableFloatStateOf(0.02f) }
    var prog by remember { mutableFloatStateOf(-1f) }
    var res by remember { mutableStateOf<PauseCutter.Result?>(null) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        u ?: return@rememberLauncherForActivityResult
        scope.launch { prog = 0f; res = null
            runCatching { withContext(Dispatchers.Default) { PauseCutter.run(c, u, gap.toInt(), sens) { prog = it } } }
                .onSuccess { res = it }.onFailure { Feedback.failed("Cutting", it) }
            prog = -1f }
    }
    AtlasScreen(title = "Pause cutter", subtitle = "Tightens voice notes, lectures and podcasts", hub = Hub.Create) {
        item("s") { Column(Modifier.padding(horizontal = Space.page)) {
            Text("Cut silences longer than ${gap.toInt()} ms", style = MaterialTheme.typography.labelMedium)
            Slider(gap, { gap = it }, valueRange = 250f..2000f)
            Text("Silence level: ${"%.3f".format(sens)} (raise it for noisy rooms)", style = MaterialTheme.typography.labelMedium)
            Slider(sens, { sens = it }, valueRange = 0.005f..0.08f)
            Button(onClick = { pick.launch("audio/*") }, enabled = prog < 0f) { Text("Pick a recording") }
            Text("Filler words (um, uh) need a word-level transcript; this cuts pauses only.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        } }
        if (prog >= 0f) item("p") { LinearProgressIndicator({ prog }, Modifier.fillMaxWidth().padding(Space.page)) }
        res?.let { r -> item("r") { Group(Modifier.padding(Space.page)) {
            Text("${r.beforeMs / 1000}s → ${r.afterMs / 1000}s", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Saved ${(r.beforeMs - r.afterMs) / 1000} seconds of silence", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Share.file(c, r.out, "audio/wav") }) { Text("Share / save") }
            }
        } } }
    }
}

// ─────────────────────────────── Dubbing ───────────────────────────────
private data class Cue(val start: Long, val end: Long, var text: String)

private fun parseSrt(s: String): List<Cue> {
    fun t(x: String): Long { val m = Regex("(\\d+):(\\d+):(\\d+)[,.](\\d+)").find(x) ?: return 0
        val (h, mi, se, ms) = m.destructured; return ((h.toLong() * 60 + mi.toLong()) * 60 + se.toLong()) * 1000 + ms.padEnd(3, '0').take(3).toLong() }
    return s.replace("\r", "").split(Regex("\n\\s*\n")).mapNotNull { b ->
        val lines = b.trim().lines(); val ti = lines.indexOfFirst { it.contains("-->") }; if (ti < 0) return@mapNotNull null
        val (a, z) = lines[ti].split("-->").map { it.trim() }
        Cue(t(a), t(z), lines.drop(ti + 1).joinToString(" ").replace(Regex("<[^>]+>"), "").trim()).takeIf { it.text.isNotBlank() }
    }
}

@Composable
fun DubScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var video by remember { mutableStateOf<Uri?>(null) }
    var cues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var lang by remember { mutableStateOf("Urdu") }
    var busy by remember { mutableStateOf<String?>(null) }
    var ready by remember { mutableStateOf(false) }
    var original by remember { mutableFloatStateOf(0.15f) }
    val pickV = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { video = it; ready = false }
    val pickS = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        u ?: return@rememberLauncherForActivityResult
        cues = runCatching { parseSrt(c.contentResolver.openInputStream(u)!!.bufferedReader().readText()) }.getOrElse { emptyList() }
        ready = false; if (cues.isEmpty()) Feedback.show("That doesn't look like an .srt subtitle file")
    }
    AtlasScreen(title = "Dub a video", subtitle = "Subtitles translated and spoken over the video", hub = Hub.Media) {
        item("a") { Column(Modifier.padding(horizontal = Space.page)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickV.launch("video/*") }) { Text(if (video == null) "1 · Pick video" else "✓ Video") }
                OutlinedButton(onClick = { pickS.launch("*/*") }) { Text(if (cues.isEmpty()) "2 · Pick .srt" else "✓ ${cues.size} lines") }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Urdu", "English", "Arabic", "Punjabi", "Hindi").forEach { l -> Capsule(l, l == lang) { lang = l; ready = false } } }
            Button(onClick = { scope.launch {
                busy = "Translating…"
                runCatching {
                    cues.chunked(40).forEachIndexed { bi, chunk ->
                        busy = "Translating ${bi * 40 + chunk.size}/${cues.size}"
                        val raw = Ai.generate(container.settingsRepository, JSONArray(chunk.map { it.text }).toString(),
                            "Translate each subtitle line into natural spoken $lang, short enough to say in the same time. Return ONLY a JSON array of strings, same length and order.", 3000)
                        val arr = JSONArray(Ai.cleanJson(raw)); chunk.forEachIndexed { i, cue -> arr.optString(i).takeIf { it.isNotBlank() }?.let { cue.text = it } }
                    }
                    ready = true
                }.onFailure { Feedback.failed("Translation", it) }
                busy = null } }, enabled = video != null && cues.isNotEmpty() && busy == null, modifier = Modifier.padding(top = 8.dp)) { Text(busy ?: "3 · Translate & dub") }
            Text("Tip: most films' .srt files are free on subtitle sites; Cinema can also fetch them.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        } }
        if (ready && video != null) item("p") { DubPlayer(video!!, cues, lang, original) }
        if (ready) item("v") { Column(Modifier.padding(horizontal = Space.page)) {
            Text("Original sound: ${(original * 100).toInt()}%", style = MaterialTheme.typography.labelMedium); Slider(original, { original = it })
        } }
    }
}

@Composable
private fun DubPlayer(video: Uri, cues: List<Cue>, lang: String, original: Float) {
    val c = LocalContext.current
    val player = remember(video) { androidx.media3.exoplayer.ExoPlayer.Builder(c).build().apply { setMediaItem(androidx.media3.common.MediaItem.fromUri(video)); prepare() } }
    var tts by remember { mutableStateOf<TextToSpeech?>(null) }
    DisposableEffect(video) {
        var t: TextToSpeech? = null
        t = TextToSpeech(c) { st -> if (st == TextToSpeech.SUCCESS) { t?.language = when (lang) { "Urdu" -> java.util.Locale("ur", "PK"); "Arabic" -> java.util.Locale("ar")
            "Punjabi" -> java.util.Locale("pa", "PK"); "Hindi" -> java.util.Locale("hi", "IN"); else -> java.util.Locale.US }; tts = t } }
        onDispose { player.release(); t?.shutdown() }
    }
    LaunchedEffect(original) { player.volume = original }
    LaunchedEffect(player, tts) {
        val t = tts ?: return@LaunchedEffect
        var last = -1
        while (isActive) {
            delay(80)
            if (!player.isPlaying) continue
            val pos = player.currentPosition
            val i = cues.indexOfLast { it.start <= pos }
            if (i >= 0 && i != last && pos < cues[i].end + 400) {
                last = i; val cue = cues[i]
                // speak faster when the line is long for its slot
                val need = cue.text.length * 70f; val slot = (cue.end - cue.start).coerceAtLeast(600)
                t.setSpeechRate((need / slot).coerceIn(0.9f, 1.8f))
                t.speak(cue.text, TextToSpeech.QUEUE_FLUSH, null, "dub$i")
            } else if (i < last) last = i
        }
    }
    AndroidView({ ctx -> androidx.media3.ui.PlayerView(ctx).apply { this.player = player } },
        Modifier.padding(Space.page).fillMaxWidth().aspectRatio(16 / 9f).clip(RoundedCornerShape(18.dp)))
}

// ─────────────────────────────── Face groups ───────────────────────────────
private data class FaceHit(val uri: Uri, val crop: Bitmap, val sig: FloatArray)

private fun faceSig(face: com.google.mlkit.vision.face.Face, crop: Bitmap): FloatArray? {
    val ids = listOf(com.google.mlkit.vision.face.FaceLandmark.LEFT_EYE, com.google.mlkit.vision.face.FaceLandmark.RIGHT_EYE, com.google.mlkit.vision.face.FaceLandmark.NOSE_BASE,
        com.google.mlkit.vision.face.FaceLandmark.MOUTH_LEFT, com.google.mlkit.vision.face.FaceLandmark.MOUTH_RIGHT, com.google.mlkit.vision.face.FaceLandmark.MOUTH_BOTTOM,
        com.google.mlkit.vision.face.FaceLandmark.LEFT_CHEEK, com.google.mlkit.vision.face.FaceLandmark.RIGHT_CHEEK)
    val pts = ids.map { face.getLandmark(it)?.position ?: return null }
    val eye = sqrt(((pts[0].x - pts[1].x) * (pts[0].x - pts[1].x) + (pts[0].y - pts[1].y) * (pts[0].y - pts[1].y)).toDouble()).toFloat().coerceAtLeast(1f)
    val geo = mutableListOf<Float>()
    for (i in pts.indices) for (j in i + 1 until pts.size) {
        val dx = pts[i].x - pts[j].x; val dy = pts[i].y - pts[j].y; geo += sqrt(dx * dx + dy * dy) / eye }
    // a coarse colour signature of the face (skin, hair, glasses) in a 4x4 grid
    val small = Bitmap.createScaledBitmap(crop, 4, 4, true)
    val col = (0 until 16).flatMap { k -> val p = small.getPixel(k % 4, k / 4); listOf(android.graphics.Color.red(p) / 255f, android.graphics.Color.green(p) / 255f, android.graphics.Color.blue(p) / 255f) }
    return (geo.map { it * 2f } + col.map { it * 0.6f }).toFloatArray()
}

private fun dist(a: FloatArray, b: FloatArray): Float { var s = 0f; for (i in a.indices) { val d = a[i] - b[i]; s += d * d }; return sqrt(s) }

@Composable
fun FaceGroupsScreen() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var groups by remember { mutableStateOf<List<List<FaceHit>>>(emptyList()) }
    var prog by remember { mutableFloatStateOf(-1f) }
    var tight by remember { mutableFloatStateOf(1.6f) }
    var hits by remember { mutableStateOf<List<FaceHit>>(emptyList()) }
    val names = remember { c.getSharedPreferences("facegroups", 0) }
    fun cluster(list: List<FaceHit>) {
        val gs = mutableListOf<MutableList<FaceHit>>()
        list.forEach { h -> val best = gs.minByOrNull { g -> g.minOf { dist(it.sig, h.sig) } }
            if (best != null && best.minOf { dist(it.sig, h.sig) } < tight) best += h else gs += mutableListOf(h) }
        groups = gs.sortedByDescending { it.size }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            prog = 0f
            val det = com.google.mlkit.vision.face.FaceDetection.getClient(com.google.mlkit.vision.face.FaceDetectorOptions.Builder()
                .setLandmarkMode(com.google.mlkit.vision.face.FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setPerformanceMode(com.google.mlkit.vision.face.FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE).setMinFaceSize(0.12f).build())
            val out = withContext(Dispatchers.Default) { uris.take(200).flatMapIndexed { i, u ->
                prog = i / uris.size.toFloat()
                val bmp = thumb(c, u, 1000) ?: return@flatMapIndexed emptyList()
                val faces = runCatching { com.google.android.gms.tasks.Tasks.await(det.process(com.google.mlkit.vision.common.InputImage.fromBitmap(bmp, 0))) }.getOrElse { emptyList() }
                faces.mapNotNull { f -> val r = f.boundingBox
                    val x = r.left.coerceIn(0, bmp.width - 1); val y = r.top.coerceIn(0, bmp.height - 1)
                    val w = r.width().coerceAtMost(bmp.width - x); val h = r.height().coerceAtMost(bmp.height - y)
                    if (w < 24 || h < 24) null else { val crop = Bitmap.createBitmap(bmp, x, y, w, h); faceSig(f, crop)?.let { FaceHit(u, Bitmap.createScaledBitmap(crop, 160, 160, true), it) } } }
            } }
            det.close(); hits = out; cluster(out); prog = -1f
        }
    }
    AtlasScreen(title = "Face groups", subtitle = if (groups.isEmpty()) "Everything stays on this phone" else "${hits.size} faces in ${groups.size} groups", hub = Hub.Memory) {
        item("p") { Column(Modifier.padding(horizontal = Space.page)) {
            Button(onClick = { pick.launch("image/*") }, enabled = prog < 0f) { Text("Pick photos to scan") }
            if (hits.isNotEmpty()) { Text("Grouping strictness", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                Slider(tight, { tight = it; cluster(hits) }, valueRange = 0.8f..3f) }
            Text("Groups by face shape and colouring — a rough sort, not recognition. Merge by loosening, split by tightening.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        if (prog >= 0f) item("l") { LinearProgressIndicator({ prog }, Modifier.fillMaxWidth().padding(Space.page)) }
        groups.forEachIndexed { gi, g -> item("g$gi") { Group(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) {
            val key = "g_" + g.first().uri.toString().hashCode()
            var name by remember(key) { mutableStateOf(names.getString(key, "") ?: "") }
            OutlinedTextField(name, { name = it; names.edit().putString(key, it).apply() }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text("Who is this? · ${g.size} photos") })
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                g.take(30).forEach { h -> Image(h.crop.asImageBitmap(), null, Modifier.size(64.dp).clip(CircleShape).clickable {
                    runCatching { c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(h.uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)) } }) }
            }
        } } }
    }
}

// ─────────────────────────────── App freezer (Shizuku) ───────────────────────────────
object Freezer {
    fun running() = runCatching { rikka.shizuku.Shizuku.pingBinder() }.getOrDefault(false)
    fun granted() = runCatching { rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED }.getOrDefault(false)
    fun request() = runCatching { rikka.shizuku.Shizuku.requestPermission(4242) }

    /** Runs a shell command with Shizuku's (adb-level) rights. newProcess is hidden in newer API versions, hence reflection. */
    fun sh(cmd: String): Pair<Int, String> {
        val m = rikka.shizuku.Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
        return p.waitFor() to out.trim()
    }
}

@Composable
fun FreezerScreen() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    val running = remember(tick) { Freezer.running() }
    val granted = remember(tick) { running && Freezer.granted() }
    DisposableEffect(Unit) {
        val l = rikka.shizuku.Shizuku.OnRequestPermissionResultListener { _, _ -> tick++ }
        runCatching { rikka.shizuku.Shizuku.addRequestPermissionResultListener(l) }
        onDispose { runCatching { rikka.shizuku.Shizuku.removeRequestPermissionResultListener(l) } }
    }
    val apps = remember(tick, showSystem) {
        val pm = c.packageManager
        @Suppress("DEPRECATION") pm.getInstalledApplications(android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS)
            .filter { it.packageName != c.packageName && (showSystem || it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM == 0) }
            .map { Triple(it.packageName, pm.getApplicationLabel(it).toString(), it.enabled) }.sortedWith(compareBy({ it.third }, { it.second.lowercase() }))
    }
    AtlasScreen(title = "App freezer", subtitle = "${apps.count { !it.third }} frozen", hub = Hub.Tools) {
        item("s") { Group(Modifier.padding(horizontal = Space.page)) {
            Text(when { !running -> "Shizuku isn't running"; !granted -> "Shizuku is running — allow Atlas"; else -> "Ready: tap an app to freeze or thaw it" }, style = MaterialTheme.typography.titleMedium)
            Text("Frozen apps can't run, notify or use battery, and vanish from the launcher until thawed. Needs the free Shizuku app, started once via wireless debugging.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!running) OutlinedButton(onClick = { Share.open(c, "https://shizuku.rikka.app/download/") }) { Text("Get Shizuku") }
                if (running && !granted) Button(onClick = { Freezer.request() }) { Text("Allow Atlas") }
                OutlinedButton(onClick = { tick++ }) { Text("Refresh") }
            }
        } }
        item("f") { Column(Modifier.padding(Space.page)) {
            OutlinedTextField(filter, { filter = it }, Modifier.fillMaxWidth(), singleLine = true, placeholder = { Text("Search apps") })
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(showSystem, { showSystem = it }); Text("  Show system apps (careful)", style = MaterialTheme.typography.bodySmall) }
        } }
        apps.filter { filter.isBlank() || it.second.contains(filter, true) || it.first.contains(filter, true) }.take(300).forEach { (pkg, label, enabled) -> item(pkg) {
            ListRow(label, supporting = if (enabled) pkg else "❄ frozen · $pkg", icon = if (enabled) Icons.Outlined.Apps else Icons.Outlined.AcUnit,
                trailing = { Capsule(if (enabled) "Freeze" else "Thaw", !enabled) {
                    if (!granted) { Feedback.show("Allow Atlas in Shizuku first"); return@Capsule }
                    scope.launch { val r = withContext(Dispatchers.IO) { runCatching { Freezer.sh(if (enabled) "pm disable-user --user 0 $pkg" else "pm enable $pkg") } }
                        r.onSuccess { (code, out) -> Feedback.show(if (code == 0) (if (enabled) "$label frozen" else "$label thawed") else "Refused: ${out.take(80)}") }
                            .onFailure { Feedback.failed("Freezing", it) }
                        tick++ }
                } })
        } }
    }
}
