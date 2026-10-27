package com.nizam.app.feature.omni

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.nizam.app.feature.next.JsonStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

fun omniDir(c: Context, sub: String) = File(c.filesDir, "omni/$sub").apply { mkdirs() }

/** Asks for one permission, then runs [then]. */
@Composable
fun rememberPermission(perm: String, then: () -> Unit): () -> Unit {
    val c = LocalContext.current
    val l = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) then() else com.nizam.app.ui.components.Feedback.show("Permission needed for this") }
    return { if (ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED) then() else l.launch(perm) }
}

/** Takes a full-size photo into Atlas's private folder. */
@Composable
fun rememberPhoto(name: () -> String, onPhoto: (File) -> Unit): () -> Unit {
    val c = LocalContext.current
    var target by remember { mutableStateOf<File?>(null) }
    val l = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> target?.takeIf { ok && it.length() > 0 }?.let(onPhoto) }
    val shoot = { val f = File(omniDir(c, "photos"), name()); target = f
        runCatching { l.launch(FileProvider.getUriForFile(c, c.packageName + ".fileprovider", f)) }.onFailure { com.nizam.app.ui.components.Feedback.show("No camera app") }; Unit }
    return rememberPermission(Manifest.permission.CAMERA) { shoot() }
}
fun loadBmp(f: File, max: Int = 1280): Bitmap? = runCatching {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(f.path, o)
    var s = 1; while (o.outWidth / s > max * 2) s *= 2
    BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
}.getOrNull()

// ─────────────── Sound of your day ───────────────
object DaySound {
    /** Your day as music: prayers ring as bells, spending is the bass, gym sets are the beat, water chimes, mood picks the key. */
    fun compose(d: JSONObject, seconds: Int = 32): ByteArray {
        val rate = 22050; val n = rate * seconds; val buf = FloatArray(n)
        val major = O.num(d, "mood").let { it == 0.0 || it >= 3.0 }
        val scale = if (major) intArrayOf(0, 2, 4, 7, 9, 12) else intArrayOf(0, 3, 5, 7, 10, 12)
        val root = 220.0 * Math.pow(2.0, (O.num(d, "tasks_done").toInt() % 5) / 12.0)
        fun f(step: Int, oct: Int = 0) = root * Math.pow(2.0, (scale[((step % scale.size) + scale.size) % scale.size] + 12 * oct) / 12.0)
        fun tone(at: Double, freq: Double, dur: Double, amp: Double, decay: Double, harm: Boolean = false) {
            val s0 = (at * rate).toInt(); val len = (dur * rate).toInt()
            for (i in 0 until len) { val idx = s0 + i; if (idx !in 0 until n) break; val t = i.toDouble() / rate
                val env = exp(-t * decay) * minOf(1.0, t * 200)
                var v = sin(2 * PI * freq * t); if (harm) v += 0.4 * sin(2 * PI * freq * 2.76 * t) + 0.2 * sin(2 * PI * freq * 5.4 * t)
                buf[idx] += (v * env * amp).toFloat() }
        }
        val tempo = 0.5 - minOf(0.2, O.num(d, "tasks_done") * 0.02) // more done, faster
        val beats = (seconds / tempo).toInt()
        // pad: the key of the day
        for (b in 0 until beats step 8) { tone(b * tempo, f(0, -1), tempo * 8, 0.08, 0.4); tone(b * tempo, f(2, -1), tempo * 8, 0.05, 0.4) }
        // melody: habits and diary words
        val notes = (O.num(d, "habits") + O.num(d, "diary_words") / 40).toInt().coerceIn(4, 40)
        val r = kotlin.random.Random(d.optString("date").hashCode())
        repeat(notes) { tone(r.nextInt(beats) * tempo, f(r.nextInt(6), 1), tempo * 1.5, 0.08, 3.0) }
        // bells: each prayer prayed, at its place in the day
        listOf(0.08, 0.38, 0.55, 0.72, 0.86).take(O.num(d, "prayers").toInt().coerceIn(0, 5)).forEach { p -> tone(p * seconds, f(4, 2), 3.5, 0.22, 1.2, harm = true) }
        // bass: spending
        val spends = (O.num(d, "spent") / 500).toInt().coerceIn(0, 24)
        repeat(spends) { i -> tone((i * beats / maxOf(1, spends)) * tempo, f(0, -2), tempo, 0.18, 5.0) }
        // beat: gym sets
        repeat(O.num(d, "gym_sets").toInt().coerceIn(0, 40)) { i -> val at = (i * 2) * tempo; for (j in 0 until (0.05 * rate).toInt()) { val idx = (at * rate).toInt() + j; if (idx in 0 until n) buf[idx] += ((r.nextFloat() - 0.5f) * 0.25f * exp(-j / 300.0).toFloat()) } }
        // chimes: water glasses
        repeat((O.num(d, "water_ml") / 250).toInt().coerceIn(0, 16)) { tone(r.nextDouble() * seconds, f(r.nextInt(6), 2), 1.5, 0.06, 2.5, harm = true) }
        val peak = buf.maxOf { kotlin.math.abs(it) }.coerceAtLeast(0.001f)
        val pcm = ByteArray(n * 2); for (i in 0 until n) { val s = (buf[i] / peak * 0.85f * 32767).toInt().toShort(); pcm[i * 2] = (s.toInt() and 0xff).toByte(); pcm[i * 2 + 1] = (s.toInt() shr 8 and 0xff).toByte() }
        return O.wav(pcm, rate)
    }
}

@Composable
fun DaySoundScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var date by remember { mutableStateOf(LocalDate.now().toString()) }; var other by remember { mutableStateOf(LocalDate.now().minusDays(7).toString()) }
    var busy by remember { mutableStateOf(false) }; var info by remember { mutableStateOf("") }
    fun playDay(d: String) { busy = true; scope.launch { val o = O.day(O.container(c), LocalDate.parse(d))
        val w = withContext(Dispatchers.Default) { DaySound.compose(o) }; O.play(c, w); busy = false
        info = "$d: ${o.optInt("prayers")} bells, ${(O.num(o, "spent") / 500).toInt()} bass notes, ${o.optInt("gym_sets")} beats, ${(O.num(o, "water_ml") / 250).toInt()} chimes, ${if (O.num(o, "mood").let { it == 0.0 || it >= 3 }) "major" else "minor"} key" } }
    Column {
        OCard { OTitle("The sound of your day"); OMuted("Your day turned into 30 seconds of music: prayers are bells, spending is the bass, gym sets are the beat, water chimes, your mood picks the key, getting things done speeds it up.")
            OChips((0..6).map { LocalDate.now().minusDays(it.toLong()).toString() }, date) { date = it }
            OBtn(if (busy) "Composing…" else "▶ Play this day", !busy) { playDay(date) } }
        OCard { OTitle("How do two days sound?"); OChips((7..13).map { LocalDate.now().minusDays(it.toLong()).toString() }, other) { other = it }
            ORow { OBtn2("▶ $date") { playDay(date) }; OBtn2("▶ $other") { playDay(other) } } }
        if (info.isNotBlank()) OMuted(info)
    }
}

// ─────────────── Atlas Echo ───────────────
object Echo {
    fun store(c: Context) = JsonStore(c, "omni_echo")
    fun tick(c: Context) {
        val p = c.getSharedPreferences("omni_echo", 0); if (!p.getBoolean("on", false)) return
        val today = LocalDate.now().toString(); val h = java.time.LocalTime.now().hour
        if (p.getString("asked", "") == today || store(c).all().any { it.optString("date") == today }) return
        val at = p.getInt("at_$today", -1).takeIf { it >= 0 } ?: (11 + kotlin.random.Random.nextInt(9)).also { p.edit().putInt("at_$today", it).apply() }
        if (h >= at) { p.edit().putString("asked", today).apply(); O.notify(c, 5870, "🎧 Today's echo", "Tap to keep 5 seconds of how today sounds.", "nx/echo") }
    }
}

@Composable
fun EchoScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val p = c.getSharedPreferences("omni_echo", 0)
    var on by remember { mutableStateOf(p.getBoolean("on", false)) }
    var v by remember { mutableIntStateOf(0) }; var rec by remember { mutableStateOf(false) }
    val record = rememberPermission(Manifest.permission.RECORD_AUDIO) { rec = true; scope.launch {
        val w = O.recordWav(5); val f = File(omniDir(c, "echo"), "${LocalDate.now()}-${System.currentTimeMillis() % 100000}.wav"); f.writeBytes(w)
        val o = Echo.store(c).add(JSONObject().put("t", System.currentTimeMillis()).put("date", LocalDate.now().toString()).put("file", f.path)); rec = false; v++
        val d = O.hear(c, "Describe the ambience of this 5-second sound in under 10 words, like a caption (e.g. 'rain on the window, distant azaan').", w)
        if (!d.startsWith("Couldn't") && !d.startsWith("Listening needs")) Echo.store(c).update(o.optString("id")) { it.put("caption", d.trim()) }; v++ } }
    val list = remember(v) { Echo.store(c).all().sortedByDescending { it.optLong("t") } }
    Column {
        OCard { OTitle("Atlas Echo"); OMuted("Five seconds of the world around you, once a day. After a year it's an album of your life.")
            Row { OText("Remind me once a day  "); androidx.compose.material3.Switch(on, { on = it; p.edit().putBoolean("on", it).apply() }) }
            OBtn(if (rec) "Listening…" else "🎙 Capture today's echo", !rec) { record() } }
        if (list.isNotEmpty()) OBtn2("▶ Play the album") { scope.launch { list.reversed().forEach { e -> runCatching { O.play(c, File(e.optString("file")).readBytes()) }; kotlinx.coroutines.delay(5600) } } }
        list.forEach { e -> OCard(onClick = { runCatching { O.play(c, File(e.optString("file")).readBytes()) } }) { OText("▶ ${e.optString("date")}"); if (e.optString("caption").isNotBlank()) OMuted(e.optString("caption")) } }
    }
}

// ─────────────── Sound ID ───────────────
@Composable
fun SoundIdScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var mode by remember { mutableStateOf("Anything") }; var busy by remember { mutableStateOf(false) }; var out by remember { mutableStateOf("") }
    val listen = rememberPermission(Manifest.permission.RECORD_AUDIO) { busy = true; out = ""; scope.launch {
        val w = O.recordWav(8)
        out = O.hear(c, when (mode) {
            "Bird or animal" -> "Identify the bird or animal making this sound. Give the most likely species, confidence, and one fun fact. If unsure, say what it could be."
            "Song" -> "Identify this song or music: title and artist if you can, otherwise genre, instruments and mood."
            "Car or machine" -> "This is a car, AC, fridge, fan or other machine. Describe the sound, the most likely problems it points to, how urgent, and what to tell the mechanic. Be careful and say when it sounds normal."
            else -> "What is this sound? Identify it as specifically as you can (animal, bird, song, machine, place) with confidence."
        }, w); busy = false } }
    Column {
        OCard { OTitle("Sound ID"); OMuted("Hold the phone near the sound. Atlas listens for 8 seconds.")
            OChips(listOf("Anything", "Bird or animal", "Song", "Car or machine"), mode) { mode = it }
            OBtn(if (busy) "Listening…" else "🎧 Listen", !busy) { listen() } }
        if (out.isNotBlank()) OCard { OMd(out) }
    }
}

// ─────────────── Conversation coach ───────────────
object Rec {
    @Volatile var stop = false
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun until(maxSec: Int, onSec: (Int) -> Unit): ByteArray = withContext(Dispatchers.IO) {
        stop = false
        val rate = 16000; val min = android.media.AudioRecord.getMinBufferSize(rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT)
        val r = android.media.AudioRecord(android.media.MediaRecorder.AudioSource.MIC, rate, android.media.AudioFormat.CHANNEL_IN_MONO, android.media.AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 32000))
        val out = java.io.ByteArrayOutputStream(); val b = ByteArray(4096); r.startRecording(); val t0 = System.currentTimeMillis(); var last = -1
        while (!stop && System.currentTimeMillis() - t0 < maxSec * 1000L) { val n = r.read(b, 0, b.size); if (n > 0) out.write(b, 0, n); val s = ((System.currentTimeMillis() - t0) / 1000).toInt(); if (s != last) { last = s; onSec(s) } }
        r.stop(); r.release(); O.wav(out.toByteArray(), rate)
    }
}

@Composable
fun ConvoCoachScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var kind by remember { mutableStateOf("Meeting") }; var rec by remember { mutableStateOf(false) }; var sec by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }; var out by remember { mutableStateOf("") }
    val start = rememberPermission(Manifest.permission.RECORD_AUDIO) { rec = true; out = ""; scope.launch {
        val w = Rec.until(480) { sec = it }; rec = false; busy = true
        out = O.hear(c, "This is a recorded $kind. The phone's owner is the speaker who is closest/loudest. Coach them:\n1. Talk-time split (rough %).\n2. Moments they interrupted or were interrupted.\n" +
            "3. Where they were clear, and where they rambled or hedged (quote briefly).\n4. Tone overall.\n5. Three things to say or do better next time, concrete.\nUse short headings. Be kind and honest.", w)
        busy = false } }
    Column {
        OCard { OTitle("Conversation coach"); OMuted("Record an important conversation (with everyone's consent). Afterwards Atlas shows talk time, interruptions, clarity, tone, and what to say better. Up to 8 minutes; nothing is kept.")
            OChips(listOf("Meeting", "Interview", "Hard talk", "Presentation", "Sales call"), kind) { kind = it }
            if (!rec) OBtn(if (busy) "Analysing…" else "● Start recording", !busy) { start() }
            else { OText("● Recording ${sec / 60}:${"%02d".format(sec % 60)}"); OBtn("■ Stop and analyse") { Rec.stop = true } } }
        if (out.isNotBlank()) OCard { OMd(out) }
    }
}

// ─────────────── Morning mirror ───────────────
@Composable
fun MorningMirrorScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var busy by remember { mutableStateOf(false) }; var out by remember { mutableStateOf("") }
    val shoot = rememberPhoto({ "mirror.jpg" }) { f -> busy = true; scope.launch {
        val bmp = loadBmp(f, 900); f.delete()
        val look = bmp?.let { O.see(c, "This is a morning selfie. Gently note: posture, how rested or tired the person looks, the light in the room. 3 short lines, kind, no judgement about appearance.", it) } ?: ""
        val ct = O.container(c); val d = O.day(ct, LocalDate.now())
        val open = runCatching { ct.taskRepository.all().first().filter { it.completedAt == null && it.deletedAt == null }.take(6).map { it.title } }.getOrDefault(emptyList())
        out = O.ai(c, "Morning check-in. What the camera noticed: $look\nNext prayer: ${com.nizam.app.ui.design.Island.nextPrayer?.let { "${it.first} in ${it.second} min" } ?: "unknown"}. Today so far: $d. Open tasks: ${open.joinToString()}.\n" +
            "Speak like a calm co-pilot: one line on how they seem, one small body tip (water, posture, light), then today's plan in 3 lines. Under 90 words. Plain text.", "Warm, brief, spoken.", 300)
        busy = false; com.nizam.app.feature.being.BeingVoice.speak(c, out, "calm") } }
    Column {
        OCard { OTitle("Morning mirror"); OMuted("Take a selfie in the morning. Atlas reads posture, tiredness and light, then talks you through the day like a calm co-pilot. The photo is deleted straight away.")
            OBtn(if (busy) "Looking…" else "🪞 Open the mirror", !busy) { shoot() } }
        if (out.isNotBlank()) OCard { OText(out); OBtn2("🔊 Again") { com.nizam.app.feature.being.BeingVoice.speak(c, out, "calm") } }
    }
}

// ─────────────── Fridge brain ───────────────
@Composable
fun FridgeBrainScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var busy by remember { mutableStateOf(false) }; var res by remember { mutableStateOf<JSONObject?>(null) }; var raw by remember { mutableStateOf("") }
    val shoot = rememberPhoto({ "fridge.jpg" }) { f -> busy = true; scope.launch {
        val bmp = loadBmp(f, 1280)
        raw = bmp?.let { O.see(c, "Look inside this fridge or kitchen shelf. Output only JSON: {\"items\":[{\"name\":\"\",\"state\":\"fresh|use soon|going off\",\"days_left\":0}],\"tonight\":{\"dish\":\"a Pakistani or simple home meal that uses what's going off first\",\"uses\":[\"\"],\"steps\":[\"\"]},\"buy\":[\"what's missing for a few staple meals\"]}", it) } ?: ""
        res = com.nizam.app.feature.nx.EngineX.json(raw); busy = false } }
    Column {
        OCard { OTitle("Fridge brain"); OMuted("Snap your fridge or shelf. Atlas lists what's there, what goes off first, and tonight's meal that uses it up.")
            OBtn(if (busy) "Looking…" else "📷 Snap the fridge", !busy) { shoot() } }
        res?.let { r ->
            OCard { OTitle("What's in there")
                O.arr(r.optJSONArray("items")).sortedBy { it.optInt("days_left", 99) }.forEach { i -> OText("${when (i.optString("state")) { "going off" -> "🔴"; "use soon" -> "🟡"; else -> "🟢" }} ${i.optString("name")}${if (i.optInt("days_left", -1) >= 0) " · ~${i.optInt("days_left")} days" else ""}") } }
            r.optJSONObject("tonight")?.let { t -> OCard { OTitle("Tonight: ${t.optString("dish")}"); OMuted("Uses: " + O.strs(t.optJSONArray("uses")).joinToString())
                O.strs(t.optJSONArray("steps")).forEachIndexed { i, s -> OText("${i + 1}. $s") } } }
            val buy = O.strs(r.optJSONArray("buy")); if (buy.isNotEmpty()) OCard { OTitle("Worth buying"); buy.forEach { OText("• $it") }
                OBtn2("Add to tasks") { scope.launch { com.nizam.app.feature.agent.Actions.run(O.container(c), "add_task", JSONObject().put("title", "Buy: " + buy.joinToString(", ").take(100))) } } }
        } ?: if (raw.isNotBlank()) OCard { OMd(raw) } else Unit
    }
}

// ─────────────── Candid me ───────────────
object Candid {
    fun store(c: Context) = JsonStore(c, "omni_candid")
    fun tick(c: Context) {
        val p = c.getSharedPreferences("omni_candid", 0); if (!p.getBoolean("on", false)) return
        val today = LocalDate.now().toString(); if (p.getString("asked", "") == today) return
        val at = p.getInt("at_$today", -1).takeIf { it >= 0 } ?: (10 * 60 + kotlin.random.Random.nextInt(11 * 60)).also { p.edit().putInt("at_$today", it).apply() }
        val now = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
        if (now >= at) { p.edit().putString("asked", today).putLong("asked_t", System.currentTimeMillis()).apply(); O.notify(c, 5880, "📸 Candid moment! 2 minutes", "Snap whatever's happening right now, front and back. No posing.", "nx/candid") }
    }
}

@Composable
fun CandidScreen() {
    val c = LocalContext.current
    val p = c.getSharedPreferences("omni_candid", 0)
    var on by remember { mutableStateOf(p.getBoolean("on", false)) }
    var v by remember { mutableIntStateOf(0) }
    var back by remember { mutableStateOf<String?>(null) }
    val today = LocalDate.now().toString()
    val late = System.currentTimeMillis() - p.getLong("asked_t", 0) > 2 * 60_000L && p.getString("asked", "") == today
    val front = rememberPhoto({ "candid-$today-front.jpg" }) { f -> Candid.store(c).add(JSONObject().put("t", System.currentTimeMillis()).put("date", today).put("back", back).put("front", f.path).put("late", late)); back = null; v++ }
    val shootBack = rememberPhoto({ "candid-$today-back.jpg" }) { f -> back = f.path }
    val list = remember(v) { Candid.store(c).all().sortedByDescending { it.optLong("t") } }
    Column {
        OCard { OTitle("Candid me"); OMuted("Once a day at a random moment you get 2 minutes to capture what's really happening: back camera, then you. A year of unposed life.")
            Row { OText("Surprise me daily  "); androidx.compose.material3.Switch(on, { on = it; p.edit().putBoolean("on", it).apply() }) }
            if (back == null) OBtn("1 · Back camera: the scene") { shootBack() } else OBtn("2 · Now flip to you") { front() } }
        list.forEach { e -> OCard { OMuted("${e.optString("date")}${if (e.optBoolean("late")) " · late" else ""}")
            Row { listOf(e.optString("back"), e.optString("front")).filter { it.isNotBlank() }.forEach { path ->
                val bmp = remember(path) { loadBmp(File(path), 500) }
                bmp?.let { Image(it.asImageBitmap(), null, Modifier.size(150.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Crop) } } } } }
    }
}
