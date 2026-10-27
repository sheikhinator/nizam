package com.nizam.app.feature.omni

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.drawToBitmap
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.ui.design.Style
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import kotlin.coroutines.resume

// ─────────────── X-ray lens ───────────────
object XRay { var on by mutableStateOf(false) }

/** A magnifying glass you drag over anything in Atlas; let go and it tells you where that number came from and what it means. */
@Composable
fun XRayOverlay(route: String?) {
    if (!XRay.on) return
    val c = LocalContext.current; val view = LocalView.current; val dens = LocalDensity.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var x by remember { mutableFloatStateOf(300f) }; var y by remember { mutableFloatStateOf(900f) }
    var out by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    val r = with(dens) { 46.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.offset { IntOffset((x - r).toInt(), (y - r).toInt()) }.size(92.dp).clip(CircleShape).background(Color(0x2A8BE9FD)).border(4.dp, Color(0xFF8BE9FD), CircleShape)
            .pointerInput(Unit) { detectDragGestures(onDragEnd = {
                busy = true; out = ""
                scope.launch {
                    val bmp = runCatching { val full = view.rootView.drawToBitmap(); val w = (r * 3.2f).toInt(); val h = (r * 1.6f).toInt()
                        Bitmap.createBitmap(full, (x - w / 2).toInt().coerceIn(0, full.width - w), (y - h / 2).toInt().coerceIn(0, full.height - h), w, h) }.getOrNull()
                    val text = bmp?.let { b -> withContext(Dispatchers.IO) { kotlinx.coroutines.suspendCancellableCoroutine<String> { cont ->
                        com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS).process(com.google.mlkit.vision.common.InputImage.fromBitmap(b, 0))
                            .addOnSuccessListener { cont.resume(it.text) }.addOnFailureListener { cont.resume("") } } } }.orEmpty()
                    out = if (text.isBlank()) "Nothing readable under the lens. Drop it right on a number or word." else O.ai(c,
                        "The user dropped an X-ray lens on this part of their Atlas app screen (${route?.let { com.nizam.app.ui.design.Feel.label(it) } ?: "a screen"}): \"$text\".\n" +
                            "Explain in under 80 words: what this is, where the number or word comes from in their own records, how it has changed lately, and what it means for them.", "Be specific to their data. Plain text.", 300)
                    busy = false } }) { ch, d -> ch.consume(); x += d.x; y += d.y; out = "" } }, contentAlignment = Alignment.Center) { Text("🔍", fontSize = 26.sp) }
        if (busy || out.isNotBlank()) Column(Modifier.align(Alignment.BottomCenter).padding(16.dp, 16.dp, 16.dp, 110.dp).widthIn(max = 420.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xF0101422)).padding(14.dp)) {
            Text(if (busy) "X-raying…" else out, color = Color.White, fontSize = 14.sp)
            if (!busy) Text("Close lens", Modifier.padding(top = 8.dp).androidxClick { XRay.on = false; out = "" }, color = Color(0xFF8BE9FD))
        }
    }
}
private fun Modifier.androidxClick(f: () -> Unit) = this.then(Modifier.pointerInput(Unit) { detectTapGestures { f() } })

@Composable
fun XRayScreen() {
    Column {
        OCard { OTitle("X-ray lens"); OMuted("Turn it on, then drag the lens over any number or word anywhere in Atlas and let go. Atlas reads what's under it and explains where it came from and what it means for you.")
            Row { OText("Lens on  "); Switch(XRay.on, { XRay.on = it }) } }
    }
}

// ─────────────── Breathing interface ───────────────
object Breathing {
    private fun p(c: Context) = c.getSharedPreferences("omni_breathing", 0)
    fun on(c: Context) = p(c).getBoolean("on", false)
    fun setOn(c: Context, v: Boolean) { p(c).edit().putBoolean("on", v).apply(); if (!v) base?.let { Style.s = it; base = null } }
    var base: Style.S? = null
    var mood by mutableStateOf("steady")
    var tint by mutableStateOf(Color.Transparent)

    /** Reads your state and the hour, and reshapes the app around it. */
    fun adapt(c: Context) {
        if (!on(c)) return
        val b = base ?: Style.s.also { base = it }
        val h = LocalTime.now().hour; val m = LocalTime.now().minute
        val (score, _) = Aura.now(c)
        val focused = runCatching { com.nizam.app.feature.shield.Shield.isActive(c) }.getOrDefault(false)
        mood = when { focused -> "focused"; score >= 55 -> "stressed"; else -> "steady" }
        Style.s = when (mood) {
            "stressed" -> b.copy(cornerScale = (b.cornerScale * 1.4f).coerceAtMost(1.8f), density = "Airy", blur = b.blur + 10f)
            "focused" -> b.copy(cornerScale = b.cornerScale * 0.7f, density = "Compact", glow = false)
            else -> b
        }
        tint = when {
            h == 17 && m >= 30 || h == 18 -> Color(0xFFFF8A3D).copy(alpha = 0.07f)   // around maghrib: warm
            h >= 22 || h < 4 -> Color(0xFF000000).copy(alpha = 0.18f)                 // late night: dim
            h in 4..5 -> Color(0xFF3D5AFE).copy(alpha = 0.06f)                         // near fajr: cool and soft
            mood == "stressed" -> Color(0xFF4FC3A1).copy(alpha = 0.05f)
            else -> Color.Transparent
        }
    }
}

/** The breathing layer: a barely-there tint over everything, and the shape changes above. */
@Composable
fun BreathingOverlay() {
    val c = LocalContext.current
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { while (true) { runCatching { Breathing.adapt(c) }; delay(60_000) } } }
    if (Breathing.on(c) && Breathing.tint != Color.Transparent) Box(Modifier.fillMaxSize().background(Breathing.tint))
}

@Composable
fun BreathingScreen() {
    val c = LocalContext.current
    var on by remember { mutableStateOf(Breathing.on(c)) }
    Column {
        OCard { OTitle("Breathing interface"); OMuted("Atlas slowly changes shape with you: softer, rounder and calmer when you're stressed, sharper and denser when you're focused, warm around Maghrib, dim late at night, cool before Fajr. No theme switching.")
            Row { OText("Let Atlas breathe  "); Switch(on, { on = it; Breathing.setOn(c, it); Breathing.adapt(c) }) }
            OMuted("Right now: ${Breathing.mood}. It reads the Phone aura sense (turn that on too for the stress part).") }
    }
}

// ─────────────── Atlas Link ───────────────
/** Atlas to Atlas, phone to phone, no internet: share notes, lists and tools, and let your beings visit each other. */
object Link {
    private const val SERVICE = "com.nizam.atlas.link"
    val peers = androidx.compose.runtime.mutableStateMapOf<String, String>()
    val log = mutableStateListOf<String>()
    var on by mutableStateOf(false)
    private var app: Context? = null
    fun inbox(c: Context) = JsonStore(c, "omni_link")

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, p: Payload) {
            val c = app ?: return
            val o = runCatching { JSONObject(String(p.asBytes() ?: return)) }.getOrNull() ?: return
            inbox(c).add(o.put("t", System.currentTimeMillis()).put("peer", peers[id] ?: "nearby"))
            val from = peers[id] ?: "a nearby Atlas"
            when (o.optString("type")) {
                "note" -> kotlinx.coroutines.MainScope().launch { runCatching { O.container(c).noteRepository.createFull("📡 ${o.optString("title")}", o.optString("body")) } }
                "tool" -> runCatching { o.optJSONObject("def")?.let { com.nizam.app.feature.nx.Forge.install(c, it) } }
                "being" -> { com.nizam.app.feature.being.Being.write(c, "visitor", "${o.optString("name")} visited from $from's phone! ${o.optString("says")}")
                    com.nizam.app.feature.being.Being.say(c, "We had a visitor! ${o.optString("name")} came over.", "excited", voice = false) }
            }
            log.add(0, "⬇ $from sent ${o.optString("type")}: ${o.optString("title", o.optString("name"))}")
            O.notify(c, 5920, "📡 From $from", "${o.optString("type")}: ${o.optString("title", o.optString("name"))}", "nx/link")
        }
        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {}
    }
    private val life = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) { peers[id] = info.endpointName; app?.let { Nearby.getConnectionsClient(it).acceptConnection(id, payloads) } }
        override fun onConnectionResult(id: String, r: ConnectionResolution) { if (!r.status.isSuccess) peers.remove(id) else log.add(0, "🔗 Linked with ${peers[id]}") }
        override fun onDisconnected(id: String) { peers.remove(id) }
    }
    private val disc = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) { app?.let { Nearby.getConnectionsClient(it).requestConnection(name, id, life) } }
        override fun onEndpointLost(id: String) {}
    }
    private var name = "Atlas"
    fun start(c: Context, me: String) { app = c.applicationContext; name = me.ifBlank { "Atlas" }
        val cl = Nearby.getConnectionsClient(c)
        cl.startAdvertising(name, SERVICE, life, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
        cl.startDiscovery(SERVICE, disc, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()); on = true }
    fun stop(c: Context) { val cl = Nearby.getConnectionsClient(c); cl.stopAllEndpoints(); cl.stopAdvertising(); cl.stopDiscovery(); peers.clear(); on = false }
    fun send(c: Context, o: JSONObject) { if (peers.isEmpty()) { com.nizam.app.ui.components.Feedback.show("No linked phone nearby yet"); return }
        Nearby.getConnectionsClient(c).sendPayload(peers.keys.toList(), Payload.fromBytes(o.toString().toByteArray())); log.add(0, "⬆ Sent ${o.optString("type")}: ${o.optString("title", o.optString("name"))}") }
}

@Composable
fun LinkScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var me by remember { mutableStateOf(c.getSharedPreferences("omni_link", 0).getString("me", "Sheikh")!!) }
    var notes by remember { mutableStateOf<List<com.nizam.app.feature.notes.Note>>(emptyList()) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { notes = O.container(c).noteRepository.everything().take(15) } }
    val perms = buildList { add(Manifest.permission.ACCESS_FINE_LOCATION); if (android.os.Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT) }
        if (android.os.Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES) }.toTypedArray()
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r -> if (r.values.all { it }) runCatching { Link.start(c, me) }.onFailure { com.nizam.app.ui.components.Feedback.show("Couldn't start: ${it.message}") }
        else com.nizam.app.ui.components.Feedback.show("Nearby permissions are needed") }
    Column {
        OCard { OTitle("Atlas Link"); OMuted("Two phones with Atlas talk directly over Bluetooth and Wi-Fi, no internet. Share notes, lists and your Forge tools, and send your being to visit.")
            OField(me, "Your name on the link") { me = it; c.getSharedPreferences("omni_link", 0).edit().putString("me", it).apply() }
            if (!Link.on) OBtn("Start linking") { ask.launch(perms) } else ORow { OText("🔗 ${Link.peers.values.joinToString().ifBlank { "Looking for nearby Atlas…" }}"); OBtn2("Stop") { Link.stop(c) } } }
        if (Link.on) {
            OCard { OTitle("Send a note"); notes.forEach { n -> OCard(onClick = { Link.send(c, JSONObject().put("type", "note").put("title", n.title).put("body", n.body)) }) { OText("📝 ${n.title}") } } }
            val tools = remember { JsonStore(c, "forge_tools").all() }
            if (tools.isNotEmpty()) OCard { OTitle("Share a tool you made"); tools.forEach { t -> OCard(onClick = { Link.send(c, JSONObject().put("type", "tool").put("title", t.optString("title")).put("def", t)) }) { OText("🛠 ${t.optString("title")}") } } }
            if (com.nizam.app.feature.being.Being.born(c)) OBtn("🤖 Send ${com.nizam.app.feature.being.Being.name(c)} to visit") {
                Link.send(c, JSONObject().put("type", "being").put("name", com.nizam.app.feature.being.Being.name(c)).put("says", "Hi! I'm ${com.nizam.app.feature.being.Being.name(c)}. Nice place.").put("look", com.nizam.app.feature.being.Body.look(c).json()))
                com.nizam.app.feature.being.Being.write(c, "visit", "Went to visit ${Link.peers.values.firstOrNull() ?: "a friend"}'s phone!") }
        }
        Link.log.take(20).forEach { OMuted(it) }
    }
}

// ─────────────── Monthly documentary ───────────────
@Composable
fun MonthMovieScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var month by remember { mutableStateOf(LocalDate.now().withDayOfMonth(1)) }
    var slides by remember { mutableStateOf<List<Pair<android.net.Uri?, String>>>(emptyList()) }
    var at by remember { mutableIntStateOf(-1) }; var busy by remember { mutableStateOf(false) }
    fun build() { busy = true; scope.launch {
        val start = month.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(); val end = month.plusMonths(1).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val photos = withContext(Dispatchers.IO) { runCatching { val out = mutableListOf<android.net.Uri>()
            c.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN),
                "${MediaStore.Images.Media.DATE_TAKEN} >= ? AND ${MediaStore.Images.Media.DATE_TAKEN} < ?", arrayOf(start.toString(), end.toString()), "${MediaStore.Images.Media.DATE_TAKEN} ASC")?.use { cur ->
                while (cur.moveToNext()) out += ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cur.getLong(0)) }
            out }.getOrDefault(emptyList()) }
        val pick = if (photos.size <= 10) photos else (0 until 10).map { photos[it * photos.size / 10] }
        val days = (0 until month.lengthOfMonth()).map { month.plusDays(it.toLong()) }.filter { !it.isAfter(LocalDate.now()) }
        val ct = O.container(c); val stats = days.map { O.day(ct, it) }
        val being = runCatching { com.nizam.app.feature.being.Being.journal(c).all().filter { it.optLong("t") in start until end }.takeLast(6).joinToString(" | ") { it.optString("text").take(80) } }.getOrDefault("")
        val o = O.aiJson(c, "Write the narration for a short documentary film of this person's month (${month.month.name.lowercase()} ${month.year}). There are ${maxOf(pick.size, 6)} scenes. " +
            "Their numbers: tasks done ${stats.sumOf { O.num(it, "tasks_done") }.toInt()}, prayers ${stats.sumOf { O.num(it, "prayers") }.toInt()}, spent Rs ${O.fmt(stats.sumOf { O.num(it, "spent") })}, gym sets ${stats.sumOf { O.num(it, "gym_sets") }.toInt()}, " +
            "water ${O.fmt(stats.sumOf { O.num(it, "water_ml") } / 1000)} litres. Their little robot's diary this month: $being\n" +
            "Warm, cinematic, honest, like a nature documentary narrator. One or two sentences per scene, first scene an opening, last a closing line. Output only JSON {\"title\":\"\",\"scenes\":[\"\"]}", 1200)
        val lines = O.strs(o?.optJSONArray("scenes")).ifEmpty { listOf("A month went by.") }
        slides = lines.mapIndexed { i, l -> pick.getOrNull(if (pick.isEmpty()) -1 else i * pick.size / lines.size) to l }
        busy = false; at = 0
        runCatching { val avg = JSONObject().put("date", month.toString()); listOf("tasks_done", "prayers", "spent", "gym_sets", "water_ml", "habits", "mood").forEach { k -> avg.put(k, stats.map { O.num(it, k) }.average()) }
            O.play(c, DaySound.compose(avg, 8 * lines.size)) } } }
    LaunchedEffect(at) { com.nizam.app.ui.design.guard { if (at in slides.indices) { com.nizam.app.feature.being.BeingVoice.speak(c, slides[at].second, "calm"); delay(8000); at++ } } }
    val photoPerm = rememberPermission(if (android.os.Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE) { build() }
    Column {
        OCard { OTitle("Month movie"); OMuted("Your month as a short narrated film: your photos, your numbers, your being's best moments, with a soundtrack composed from the month itself.")
            OChips((0..5).map { LocalDate.now().withDayOfMonth(1).minusMonths(it.toLong()).toString().take(7) }, month.toString().take(7)) { month = LocalDate.parse("$it-01") }
            OBtn(if (busy) "Editing your film…" else "🎬 Make the film", !busy) { photoPerm() } }
        if (at in slides.indices) {
            val (uri, line) = slides[at]
            val bmp = remember(uri) { uri?.let { u -> runCatching { c.contentResolver.openInputStream(u)?.use { android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }) } }.getOrNull() } }
            val zoom = remember(at) { Animatable(1f) }
            LaunchedEffect(at) { com.nizam.app.ui.design.guard { zoom.animateTo(1.15f, tween(8000, easing = LinearEasing)) } }
            Box(Modifier.fillMaxWidth().height(420.dp).clip(RoundedCornerShape(20.dp)).background(Color.Black)) {
                if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value }, contentScale = ContentScale.Crop)
                else Text("✦", Modifier.align(Alignment.Center), color = Color.White, fontSize = 64.sp)
                Box(Modifier.fillMaxWidth().align(Alignment.BottomCenter).background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000)))).padding(18.dp)) {
                    Text(line, color = Color.White, fontSize = 17.sp) }
            }
            OMuted("Scene ${at + 1} of ${slides.size}")
        } else if (slides.isNotEmpty()) OBtn2("▶ Watch again") { at = 0 }
    }
}
