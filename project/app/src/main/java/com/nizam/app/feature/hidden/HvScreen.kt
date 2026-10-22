package com.nizam.app.feature.hidden

import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.nizam.app.ui.components.Feedback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

// A dark, warm palette of its own — the vault never looks like the rest of Atlas
private val BG = Color(0xFF0C0A0E)
private val CARD = Color(0xFF1A1418)
private val HOT = Color(0xFFFF3D6E)
private val EMBER = Color(0xFFFF8A3D)
private val DIM = Color(0xFF9B8E95)
private val GRAD = Brush.linearGradient(listOf(HOT, EMBER))
val HV_ICONS = listOf("🔥", "🌶", "💋", "🍑", "🍒", "🍆", "🍌", "🥒", "🍓", "🍈", "🥥", "🍯", "💦", "🌊", "😈", "👿", "🥵", "🤤", "😏", "😍",
    "👄", "🫦", "👅", "🖤", "❤️‍🔥", "💜", "💗", "💘", "💞", "🫶", "👙", "🩲", "👠", "🎀", "💄", "🛏", "🚿", "🛁", "🧴", "🔗", "⛓", "🕶", "🍷",
    "🍾", "🌹", "🦋", "🌙", "✨", "💫", "⭐", "💎", "👑", "⚡", "☄️", "🌋", "🎬", "📹", "🎥", "📸", "🏳️‍🌈", "👭", "👬", "👫", "👤", "👥", "🔞")

/** Ready-made categories you apply yourself — Atlas never looks inside the files to guess them. */
val HV_PRESETS = listOf(
    "Who" to listOf("Straight" to "👫", "Gay" to "👬", "Lesbian" to "👭", "Bisexual" to "💜", "Trans" to "🏳️‍⚧️", "Solo female" to "👩", "Solo male" to "👨",
        "Couple" to "💞", "Threesome" to "👥", "Foursome" to "🍀", "Group" to "🎉", "Swingers" to "🔄", "MILF" to "👠", "Mature" to "🍷", "Interracial" to "🤎",
        "Asian" to "🏮", "Latina" to "💃", "Ebony" to "🖤", "Arab" to "🌙", "Desi" to "🪷", "European" to "🇪🇺", "Redhead" to "🦰", "Blonde" to "👱", "Brunette" to "🟤"),
    "Body" to listOf("Ass" to "🍑", "Big ass" to "🍑", "Boobs" to "🍈", "Big boobs" to "🍉", "Small boobs" to "🍒", "Natural" to "🌿", "Curvy" to "🍐", "BBW" to "💗",
        "Petite" to "🌸", "Fit" to "💪", "Muscular" to "🏋", "Hairy" to "🐻", "Shaved" to "🪒", "Tattooed" to "🐉", "Pierced" to "💍", "Legs" to "🦵", "Feet" to "🦶"),
    "Action" to listOf("Sex" to "🔥", "Oral" to "👅", "Blowjob" to "🍆", "Cunnilingus" to "🌮", "69" to "♋", "Handjob" to "✋", "Fingering" to "👆", "Anal" to "🍑",
        "Double penetration" to "✌", "Masturbation" to "💦", "Squirting" to "🌊", "Creampie" to "🥧", "Cumshot" to "💥", "Facial" to "😮", "Deepthroat" to "🫦",
        "Titjob" to "🍈", "Rimming" to "👄", "Kissing" to "💋", "Toys" to "🔌", "Strap-on" to "🔱", "Massage" to "🧴", "Striptease" to "💃"),
    "Position" to listOf("Missionary" to "🛏", "Doggy" to "🐕", "Cowgirl" to "🤠", "Reverse cowgirl" to "🔁", "Standing" to "🧍", "Spooning" to "🥄", "Lotus" to "🪷"),
    "Kink" to listOf("BDSM" to "⛓", "Bondage" to "🪢", "Domination" to "👑", "Submission" to "🙇", "Femdom" to "👸", "Spanking" to "🖐", "Roleplay" to "🎭",
        "Cosplay" to "🦸", "Uniform" to "👔", "Lingerie" to "👙", "Stockings" to "🧦", "Latex" to "🖤", "Leather" to "🧥", "Foot fetish" to "🦶",
        "Exhibitionism" to "👀", "Cuckold" to "🦌", "JOI" to "🗣", "Dirty talk" to "💬", "Edging" to "⏳", "Worship" to "🙏"),
    "Style" to listOf("Amateur" to "📱", "Homemade" to "🏠", "Professional" to "🎬", "POV" to "👁", "Webcam" to "📹", "OnlyFans" to "💙", "Vintage" to "📼",
        "Hentai" to "🎨", "Animated" to "🖌", "VR" to "🥽", "4K" to "💎", "HD" to "📺", "Compilation" to "🎞", "Behind the scenes" to "🎥", "Romantic" to "🌹",
        "Passionate" to "❤️‍🔥", "Sensual" to "🕯", "Rough" to "⚡", "Hardcore" to "🔥", "Softcore" to "☁", "ASMR" to "🎧"),
    "Place" to listOf("Bedroom" to "🛏", "Shower" to "🚿", "Bath" to "🛁", "Pool" to "🏊", "Beach" to "🏖", "Car" to "🚗", "Office" to "🏢", "Hotel" to "🏨",
        "Kitchen" to "🍳", "Couch" to "🛋", "Outdoor" to "🌳", "Gym" to "🏋"),
    "Mood" to listOf("Top rated" to "👑", "Must rewatch" to "❤️‍🔥", "Favourite performer" to "⭐", "Quick" to "⚡", "Long" to "🌙", "Saved for later" to "🔖",
        "Guilty pleasure" to "😈", "Classic" to "🏆"),
)

private val PRESET_ICON: Map<String, String> by lazy { HV_PRESETS.flatMap { it.second }.associate { it.first.lowercase() to it.second } }
fun tagIcon(own: List<Pair<String, String>>, t: String) = own.firstOrNull { it.first.equals(t, true) }?.second ?: PRESET_ICON[t.lowercase()] ?: "🏷"

/** A plain one-line summary built from your own details — no AI: "Couple · POV · Bedroom · 18 min · ★★★★ · watched 6×". */
fun summaryLine(i: Hv.Item, short: Boolean = false): String = (i.tags.take(if (short) 2 else 4) +
    listOfNotNull(i.performers.firstOrNull()?.takeIf { !short }, if (i.video && i.durationMs > 0) "${(i.durationMs / 60_000).coerceAtLeast(1)} min" else null,
        if (i.rating > 0) "★".repeat(i.rating) else null, if (i.views > 0 && !short) "watched ${i.views}×" else null)).joinToString(" · ")

private fun Context.activity(): Activity? = when (this) { is Activity -> this; is ContextWrapper -> baseContext.activity(); else -> null }
private fun dur(ms: Long): String { val s = ms / 1000; return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60) }

/** The hidden vault. Reached only by typing the secret code into Atlas search; never listed, never seen by AI. */
@Composable
fun HvRoot(onExit: () -> Unit) {
    val c = LocalContext.current
    var session by remember { mutableStateOf(Hv.Session.current) }
    fun lockAndLeave() { Hv.lock(c); session = null; onExit() }

    // no screenshots, no screen recording, no preview in the app switcher
    DisposableEffect(Unit) {
        val w = c.activity()?.window
        w?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { w?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    // leave Atlas and it's locked
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && !Hv.expectingReturn) { Hv.lock(c); session = null }
            if (e == Lifecycle.Event.ON_RESUME) Hv.expectingReturn = false
        }
        lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
    }
    // turn the phone face-down and it's gone
    DisposableEffect(Unit) {
        val sm = c.getSystemService(SensorManager::class.java)
        var since = 0L
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (e.values[2] < -8.5f) { if (since == 0L) since = System.currentTimeMillis() else if (System.currentTimeMillis() - since > 400) { since = 0; lockAndLeave() } }
                else since = 0
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_NORMAL) }
        onDispose { sm.unregisterListener(l) }
    }
    BackHandler { lockAndLeave() }
    Box(Modifier.fillMaxSize().background(BG)) {
        val s = session
        when {
            !Hv.isSetUp(c) -> Setup { session = it; Hv.Session.current = it }
            s == null -> PinPad(onOpen = { session = it; Hv.Session.current = it }, onExit = { lockAndLeave() })
            else -> Library(s, onLock = { lockAndLeave() })
        }
    }
}

// ── PIN ─────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun Dots(n: Int) = Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
    repeat(maxOf(4, n)) { i -> Box(Modifier.size(14.dp).clip(CircleShape).background(if (i < n) HOT else Color(0xFF2E262B))) }
}

@Composable
private fun Keypad(onKey: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✓")).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                row.forEach { k ->
                    Box(Modifier.size(72.dp).clip(CircleShape).background(if (k == "✓") HOT.copy(alpha = 0.9f) else CARD).clickable { onKey(k) },
                        contentAlignment = Alignment.Center) { Text(k, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Light) }
                }
            }
        }
    }
}

@Composable
private fun PinPad(onOpen: (Hv.Session) -> Unit, onExit: () -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    fun tryOpen() {
        if (pin.length < 4 || busy) return
        busy = true
        scope.launch {
            val s = withContext(Dispatchers.Default) { Hv.unlock(c, pin) }
            busy = false; pin = ""
            if (s != null) onOpen(s) else msg = if (!Hv.isSetUp(c)) "" else "Wrong PIN" + (Hv.wipeAfter(c).takeIf { it > 0 }?.let { " · ${it - Hv.fails(c)} tries left" } ?: "")
        }
    }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("🔞", fontSize = 40.sp)
        Text("Private", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(28.dp))
        Dots(pin.length)
        Text(if (busy) "Unlocking…" else msg, color = if (msg.startsWith("Wrong")) HOT else DIM, modifier = Modifier.padding(top = 14.dp, bottom = 26.dp).height(20.dp))
        Keypad { k -> when (k) { "⌫" -> pin = pin.dropLast(1); "✓" -> tryOpen(); else -> if (pin.length < 12) { pin += k; msg = "" } }; Unit }
        Text("Leave", color = DIM, modifier = Modifier.padding(top = 28.dp).clickable { onExit() }.padding(10.dp))
    }
}

@Composable
private fun Setup(onDone: (Hv.Session) -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var first by remember { mutableStateOf<String?>(null) }
    var pin by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf("Choose a PIN — 4 to 12 digits") }
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("🔥", fontSize = 44.sp)
        Text("Your private vault", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text("Offline. Encrypted. Invisible. No AI, no internet, no backups — and no way to recover a forgotten PIN.",
            color = DIM, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(16.dp))
        Dots(pin.length)
        Text(msg, color = DIM, modifier = Modifier.padding(top = 14.dp, bottom = 22.dp))
        Keypad { k -> when (k) {
            "⌫" -> pin = pin.dropLast(1)
            "✓" -> if (pin.length >= 4) {
                if (first == null) { first = pin; pin = ""; msg = "Once more to confirm" }
                else if (first == pin) {
                    val chosen = pin
                    scope.launch { val s = withContext(Dispatchers.Default) { Hv.setPin(c, "a", chosen); Hv.unlock(c, chosen) }; if (s != null) onDone(s) }
                } else { first = null; pin = ""; msg = "They didn't match — choose again" }
            }
            else -> if (pin.length < 12) pin += k
        }; Unit }
        Text("Open it any time by typing ${Hv.code(c)} in Atlas search", color = DIM, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 24.dp))
    }
}

// ── the library ───────────────────────────────────────────────────────────────────────────────

@kotlin.OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun Library(s: Hv.Session, onLock: () -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var items by remember { mutableStateOf(s.items()) }
    var tags by remember { mutableStateOf(s.tags()) }
    var filter by remember { mutableStateOf("All") }
    var tab by remember { mutableStateOf("Library") }
    var openAlbum by remember { mutableStateOf<String?>(null) }
    var albums by remember { mutableStateOf(s.albums()) }
    var sort by remember { mutableStateOf("Newest") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var viewing by remember { mutableStateOf<Int?>(null) }
    var sheet by remember { mutableStateOf<String?>(null) }         // add · tag · tags · settings · edit
    var editing by remember { mutableStateOf<Hv.Item?>(null) }
    var importing by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var importPct by remember { mutableFloatStateOf(0f) }
    var toDelete by remember { mutableStateOf<List<Uri>>(emptyList()) }
    fun reload() { items = s.items(); tags = s.tags(); albums = s.albums() }
    BackHandler(enabled = openAlbum != null) { openAlbum = null }

    val shown = remember(items, filter, sort, query, openAlbum) {
        items.filter { i -> openAlbum?.let { it in i.albums } ?: true }.filter { i -> when (filter) {
            "All" -> true; "Videos" -> i.video; "Photos" -> !i.video; "❤ Favourites" -> i.fav
            "Continue" -> i.lastPos > 0; "Unwatched" -> i.views == 0; "★ 4+" -> i.rating >= 4
            else -> filter in i.tags || i.performers.any { it.equals(filter, true) } || i.source.equals(filter, true) } }
            .filter { query.isBlank() || it.name.contains(query, true) || it.tags.any { t -> t.contains(query, true) } ||
                it.performers.any { p -> p.contains(query, true) } || it.source.contains(query, true) || it.notes.contains(query, true) }
            .let { l -> when (sort) { "Longest" -> l.sortedByDescending { it.durationMs }; "Most watched" -> l.sortedByDescending { it.views }
                "Recently watched" -> l.sortedByDescending { it.lastViewed }; "Top rated" -> l.sortedByDescending { it.rating }; "Name" -> l.sortedBy { it.name.lowercase() }; else -> l.sortedByDescending { it.added } } }
    }

    val deleteReq = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { toDelete = emptyList() }
    fun importAll(uris: List<Uri>, fromGallery: Boolean) {
        if (uris.isEmpty()) return
        scope.launch {
            uris.forEachIndexed { i, u ->
                importing = i + 1 to uris.size; importPct = 0f
                runCatching { s.import(u) { done, total -> if (total > 0) importPct = done / total.toFloat() } }.onFailure { Feedback.show("Couldn't add one file") }
            }
            importing = null; reload()
            if (fromGallery) toDelete = uris
        }
    }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { importAll(it, false) }
    var gallery by remember { mutableStateOf(false) }
    var camFile by remember { mutableStateOf<File?>(null) }
    val camPhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        camFile?.let { f -> if (ok) scope.launch { runCatching { s.import(FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", f)) }; f.delete(); reload() } else f.delete() } }
    val camVideo = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        camFile?.let { f -> if (ok) scope.launch { runCatching { s.import(FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", f)) }; f.delete(); reload() } else f.delete() } }
    fun camUri(ext: String): Uri { val f = File(c.cacheDir, "hv_cam_${System.nanoTime()}.$ext"); camFile = f; return FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", f) }

    if (gallery) { GalleryPicker(onCancel = { gallery = false }) { uris -> gallery = false; importAll(uris, true) }; return }
    viewing?.let { start -> Viewer(s, shown, start, onClose = { viewing = null; reload() }, onChanged = { reload() }); return }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        // header
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (s.slot == "b") "Private" else "Private ✦", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
                Text("${items.count { it.video }} videos · ${items.count { !it.video }} photos · ${"%.1f".format(items.sumOf { it.size } / 1_073_741_824.0)} GB sealed",
                    color = DIM, style = MaterialTheme.typography.labelMedium)
            }
            Round("＋") { sheet = "add" }; Spacer(Modifier.width(8.dp))
            Round("📅") { sheet = "calendar" }; Spacer(Modifier.width(8.dp))
            Round("📊") { sheet = "stats" }; Spacer(Modifier.width(8.dp))
            Round("⚙") { sheet = "settings" }; Spacer(Modifier.width(8.dp))
            Round("🔒") { onLock() }
        }
        // search
        Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CARD).padding(horizontal = 14.dp, vertical = 11.dp)) {
            if (query.isEmpty()) Text("Search titles, tags, performers, notes", color = DIM)
            BasicTextField(query, { query = it }, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(HOT), modifier = Modifier.fillMaxWidth())
        }
        Row(Modifier.padding(start = 12.dp, top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("🎞 Library", tab == "Library" && openAlbum == null) { tab = "Library"; openAlbum = null }
            Pill("📁 Albums · ${albums.size}", tab == "Albums" || openAlbum != null) { tab = "Albums"; openAlbum = null }
        }
        openAlbum?.let { a ->
            var renaming by remember(a) { mutableStateOf(false) }
            var newName by remember(a) { mutableStateOf(a) }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Color.White, fontSize = 26.sp, modifier = Modifier.clickable { openAlbum = null }.padding(end = 10.dp))
                Column(Modifier.weight(1f)) {
                    Text("📁 $a", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${items.count { a in it.albums }} items · long-press one and tap 🖼 to make it the cover", color = DIM, style = MaterialTheme.typography.labelSmall)
                }
                Text("✏", color = Color.White, fontSize = 18.sp, modifier = Modifier.clickable { renaming = !renaming }.padding(8.dp))
                Text("🗑", fontSize = 18.sp, modifier = Modifier.clickable { s.deleteAlbum(a); openAlbum = null; tab = "Albums"; reload(); Feedback.show("Album removed — its videos stay in the vault") }.padding(8.dp))
            }
            if (renaming) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field(newName, "Album name") { newName = it } }
                Text("Save", color = HOT, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                    val n = newName.trim(); if (n.isNotBlank() && albums.none { it.first == n }) { s.renameAlbum(a, n); openAlbum = n; reload() }; renaming = false }.padding(12.dp))
            }
        }
        if (tab == "Albums" && openAlbum == null) {
            AlbumsGrid(s, items, albums, onOpen = { openAlbum = it; tab = "Library" }, onChanged = { reload() })
            return@Column
        }
        // filters
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (listOf("All" to "", "Videos" to "🎬", "Photos" to "📸", "❤ Favourites" to "", "Continue" to "⏯", "Unwatched" to "✨", "★ 4+" to "")).forEach { (n, icon) ->
                Pill((if (icon.isNotBlank()) "$icon " else "") + n, n == filter) { filter = n }
            }
            Pill("🏷 Tag list & rules", false) { sheet = "tags" }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Newest", "Top rated", "Longest", "Most watched", "Recently watched", "Name").forEach { o ->
                Text(o, color = if (o == sort) EMBER else DIM, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { sort = o }.padding(6.dp)) }
        }
        importing?.let { (i, n) ->
            Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CARD).padding(14.dp)) {
                Text("Sealing $i of $n…", color = Color.White)
                LinearProgressIndicator(progress = { importPct }, color = HOT, trackColor = Color(0xFF2E262B), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
        }
        if (toDelete.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(CARD).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text("Remove the ${toDelete.size} originals from your gallery?", color = Color.White, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("Remove", color = HOT, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                if (Build.VERSION.SDK_INT >= 30) runCatching {
                    Hv.expectingReturn = true
                    deleteReq.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(c.contentResolver, toDelete).intentSender).build())
                }.onFailure { toDelete = emptyList() } else { toDelete.forEach { u -> runCatching { c.contentResolver.delete(u, null, null) } }; toDelete = emptyList() }
            }.padding(8.dp))
            Text("Keep", color = DIM, modifier = Modifier.clickable { toDelete = emptyList() }.padding(8.dp))
        }

        if (items.isEmpty() && importing == null) {
            Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("🌶", fontSize = 56.sp)
                Text("Nothing here yet", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
                Text("Tap ＋ to bring in videos and photos. They're sealed on this phone and can be wiped from your gallery.",
                    color = DIM, modifier = Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium)
            }
        } else LazyVerticalGrid(GridCells.Fixed(3), Modifier.weight(1f).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(shown, key = { it.id }) { i ->
                val sel = i.id in selected
                Tile(s, i, tags, sel, Modifier.combinedClickable(
                    onClick = { if (selected.isNotEmpty()) selected = if (sel) selected - i.id else selected + i.id else viewing = shown.indexOf(i) },
                    onLongClick = { selected = selected + i.id }))
            }
            item(span = { GridItemSpan(3) }) { Spacer(Modifier.height(90.dp)) }
        }
    }

    // selection bar
    if (selected.isNotEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Row(Modifier.navigationBarsPadding().padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(0xF0221A1F)).padding(8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            Text("${selected.size}", color = Color.White, fontWeight = FontWeight.Bold)
            Action("🏷", "Tag") { sheet = "tag" }
            Action("📁", "Album") { sheet = "album" }
            if (openAlbum != null && selected.size == 1) Action("🖼", "Cover") {
                val a = openAlbum!!; s.saveAlbums(s.albums().map { if (it.first == a) a to selected.first() else it }); reload(); selected = emptySet(); Feedback.show("Cover set") }
            Action("❤", "Fave") { val on = !items.filter { it.id in selected }.all { it.fav }; s.save(s.items().map { if (it.id in selected) it.copy(fav = on) else it }); reload(); selected = emptySet() }
            Action("🗑", "Delete") { s.delete(selected); selected = emptySet(); reload(); Feedback.show("Deleted for good") }
            Action("✕", "Cancel") { selected = emptySet() }
        }
    }

    when (sheet) {
        "add" -> Sheet({ sheet = null }) {
            Title("Bring things in")
            SheetRow("🖼", "From gallery", "Pick videos and photos, then wipe them from the gallery") { sheet = null; gallery = true }
            SheetRow("📁", "From files", "Downloads, a folder, anything") { sheet = null; Hv.expectingReturn = true; pickFiles.launch(arrayOf("video/*", "image/*")) }
            SheetRow("📸", "Camera photo", "Goes straight in — never touches the gallery") { sheet = null; Hv.expectingReturn = true; runCatching { camPhoto.launch(camUri("jpg")) } }
            SheetRow("🎥", "Camera video", "Straight into the vault") { sheet = null; Hv.expectingReturn = true; runCatching { camVideo.launch(camUri("mp4")) } }
        }
        "album" -> AlbumPicker(s, items.filter { it.id in selected }, onDone = { reload(); selected = emptySet(); sheet = null })
        "tag" -> TagPicker(tags, items.filter { it.id in selected }, onApply = { add, remove ->
            s.save(s.items().map { if (it.id in selected) it.copy(tags = (it.tags + add - remove.toSet()).distinct()) else it }); reload(); selected = emptySet(); sheet = null
        }, onClose = { sheet = null })
        "tags" -> TagManager(s, onClose = { reload(); sheet = null })
        "settings" -> VaultSettings(s, onClose = { sheet = null }, onWiped = onLock)
        "stats" -> Stats(items, tags, onClose = { sheet = null })
        "calendar" -> Calendar(s, onClose = { sheet = null })
    }
}

@Composable
private fun Tile(s: Hv.Session, i: Hv.Item, tags: List<Pair<String, String>>, sel: Boolean, modifier: Modifier) {
    var bmp by remember(i.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(i.id) { com.nizam.app.ui.design.guard { bmp = withContext(Dispatchers.IO) { s.thumb(i.id) } } }
    Column(modifier) {
    Box(Modifier.aspectRatio(0.72f).clip(RoundedCornerShape(10.dp)).background(CARD)
        .then(if (sel) Modifier.border(3.dp, HOT, RoundedCornerShape(10.dp)) else Modifier)) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: Text(if (i.video) "🎬" else "📸", modifier = Modifier.align(Alignment.Center), fontSize = 24.sp)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent, Color.Black.copy(alpha = 0.75f)))))
        val flags = remember(i.performers, i.tags) { HvFlags.of(i) }
        Column(Modifier.align(Alignment.TopEnd).padding(5.dp), horizontalAlignment = Alignment.End) {
            if (flags.isNotEmpty()) Text(flags.take(3).joinToString(""), fontSize = 15.sp,
                modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 3.dp))
            if (i.fav) Text("❤", color = HOT, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp, end = 1.dp))
        }
        if (i.rating > 0 && i.views > 0) Text("★${i.rating}", color = EMBER, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.TopStart).padding(6.dp))
        if (i.views == 0) Box(Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(GRAD).padding(horizontal = 5.dp, vertical = 1.dp)) {
            Text("NEW", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black) }
        Row(Modifier.align(Alignment.BottomStart).padding(6.dp)) {
            i.tags.take(3).forEach { t -> Text(tagIcon(tags, t), fontSize = 12.sp) }
        }
        if (i.video) Text(dur(i.durationMs), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
        if (i.video && i.lastPos > 0 && i.durationMs > 0) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp).background(Color(0x55FFFFFF))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth((i.lastPos.toFloat() / i.durationMs).coerceIn(0f, 1f)).background(HOT)) }
        if (sel) Box(Modifier.align(Alignment.Center).size(30.dp).clip(CircleShape).background(HOT), contentAlignment = Alignment.Center) { Text("✓", color = Color.White) }
    }
    Text(i.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, top = 3.dp))
    summaryLine(i, short = true).takeIf { it.isNotBlank() }?.let { Text(it, color = DIM, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)) }
    }
}

// ── viewer ────────────────────────────────────────────────────────────────────────────────────

@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Viewer(s: Hv.Session, list: List<Hv.Item>, start: Int, onClose: () -> Unit, onChanged: () -> Unit) {
    BackHandler { onClose() }
    val pager = rememberPagerState(initialPage = start.coerceIn(0, (list.size - 1).coerceAtLeast(0))) { list.size }
    var chrome by remember { mutableStateOf(true) }
    var tagging by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
            val i = list[page]
            if (i.video) VideoPage(s, i, active = pager.currentPage == page, onTap = { chrome = !chrome }, onChanged = onChanged)
            else PhotoPage(s, i) { chrome = !chrome }
        }
        val cur = list.getOrNull(pager.currentPage)
        if (chrome && cur != null) {
            var fav by remember(cur.id) { mutableStateOf(cur.fav) }
            Row(Modifier.statusBarsPadding().fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = Color.White, fontSize = 30.sp, modifier = Modifier.clickable { onClose() }.padding(horizontal = 8.dp))
                Column(Modifier.weight(1f)) {
                    val live = s.items().firstOrNull { it.id == cur.id } ?: cur
                    Text((HvFlags.of(live).joinToString("").let { if (it.isBlank()) "" else "$it  " }) + live.name, color = Color.White, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { renaming = true })
                    val now = s.items().firstOrNull { it.id == cur.id } ?: cur
                    Text(summaryLine(now).ifBlank { "Tap 🏷 to tag, ✏ for details" }, color = DIM, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (now.notes.isNotBlank()) Text("“${now.notes}”", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodySmall,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.clickable { renaming = true })
                }
                Text(if (fav) "❤" else "♡", color = HOT, fontSize = 24.sp, modifier = Modifier.clickable { fav = !fav; s.update(cur.id) { it.copy(fav = fav) }; onChanged() }.padding(8.dp))
                Text("🏷", fontSize = 22.sp, modifier = Modifier.clickable { tagging = true }.padding(8.dp))
                Text("✏", fontSize = 20.sp, color = Color.White, modifier = Modifier.clickable { renaming = true }.padding(8.dp))
            }
            if (tagging) TagPicker(s.tags(), listOf(s.items().first { it.id == cur.id }), onApply = { add, remove ->
                s.update(cur.id) { it.copy(tags = (it.tags + add - remove.toSet()).distinct()) }; onChanged(); tagging = false }, onClose = { tagging = false })
            if (renaming) DetailsEditor(s, s.items().first { it.id == cur.id }, onDone = { onChanged(); renaming = false })
        }
    }
}

/** Everything about one item, editable: name, performers, studio/source, rating, notes, albums. */
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailsEditor(s: Hv.Session, i: Hv.Item, onDone: () -> Unit) {
    var name by remember(i.id) { mutableStateOf(i.name) }
    var performers by remember(i.id) { mutableStateOf(i.performers.joinToString(", ")) }
    var source by remember(i.id) { mutableStateOf(i.source) }
    var rating by remember(i.id) { mutableIntStateOf(i.rating) }
    var notes by remember(i.id) { mutableStateOf(i.notes) }
    var picked by remember(i.id) { mutableStateOf(i.tags.toSet()) }
    val known = remember { s.items().flatMap { it.performers }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(12) }
    Sheet({ onDone() }) {
        Title("Details")
        Field(name, "Title") { name = it }
        Field(performers, "Performers and origin, e.g. “Lina, Arab” (comma-separated)") { performers = it }
        HvFlags.of(performers.split(',')).takeIf { it.isNotEmpty() }?.let { Text("Shows as ${it.joinToString(" ")}", color = DIM, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp)) }
        if (known.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            known.forEach { k -> Pill("＋ $k", false) { if (!performers.contains(k, true)) performers = listOf(performers.trim().trimEnd(','), k).filter { it.isNotBlank() }.joinToString(", ") } }
        }
        Field(source, "Studio / site / source") { source = it }
        Text("Albums", color = DIM, modifier = Modifier.padding(top = 12.dp))
        var inAlbums by remember(i.id) { mutableStateOf(i.albums.toSet()) }
        var newAlbum by remember(i.id) { mutableStateOf("") }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
            (s.albums().map { it.first } + inAlbums).distinct().forEach { a -> Pill("📁 $a", a in inAlbums) { inAlbums = if (a in inAlbums) inAlbums - a else inAlbums + a } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { Field(newAlbum, "New album (e.g. a performer's name)") { newAlbum = it } }
            Text("Add", color = HOT, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                val n = newAlbum.trim(); if (n.isNotBlank()) { if (s.albums().none { it.first == n }) s.saveAlbums(s.albums() + (n to "")); inAlbums = inAlbums + n; newAlbum = "" } }.padding(12.dp))
        }

        Text("Rating", color = DIM, modifier = Modifier.padding(top = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            (1..5).forEach { n -> Text(if (n <= rating) "★" else "☆", color = if (n <= rating) EMBER else DIM, fontSize = 32.sp,
                modifier = Modifier.clickable { rating = if (rating == n) 0 else n }.padding(2.dp)) }
        }
        Field(notes, "Your commentary (favourite moments, timestamps…)") { notes = it }
        Text("Tags", color = DIM, modifier = Modifier.padding(top = 12.dp))
        TagChooser(s.tags(), picked) { n -> picked = picked.firstOrNull { it.equals(n, true) }?.let { picked - it } ?: (picked + n) }
        Text("${if (i.video) dur(i.durationMs) + " · " else ""}${"%.0f".format(i.size / 1_048_576.0)} MB · ${i.w}×${i.h} · watched ${i.views}× · " +
            "${i.watchMs / 60_000} min total", color = DIM, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 10.dp))
        GradButton("Save") {
            s.update(i.id) { it.copy(name = name.trim().ifBlank { it.name }, performers = performers.split(',').map { p -> p.trim() }.filter { p -> p.isNotBlank() }.distinct(),
                source = source.trim(), rating = rating, notes = notes.trim(), tags = picked.toList(), albums = inAlbums.toList()) }
            onDone()
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoPage(s: Hv.Session, i: Hv.Item, active: Boolean, onTap: () -> Unit, onChanged: () -> Unit) {
    val c = LocalContext.current
    if (!active) { Box(Modifier.fillMaxSize()) { s.thumb(i.id)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) } }; return }
    val player = remember(i.id) {
        val reader = Hv.Reader(s.media(i.id), s.dek)
        ExoPlayer.Builder(c).build().apply {
            setMediaSource(ProgressiveMediaSource.Factory { HvDataSource(reader) }.createMediaSource(MediaItem.fromUri(Uri.parse("hv://${i.id}"))))
            prepare(); if (i.lastPos > 0 && i.lastPos < i.durationMs - 5000) seekTo(i.lastPos); playWhenReady = true
        } to reader
    }
    val watched = remember(i.id) { longArrayOf(0) }
    LaunchedEffect(i.id) { com.nizam.app.ui.design.guard { while (true) { kotlinx.coroutines.delay(1000); if (player.first.isPlaying) watched[0] += 1000 } } }
    DisposableEffect(i.id) {
        onDispose {
            val p = player.first
            val pos = p.currentPosition; val d = p.duration
            s.update(i.id) { it.copy(views = it.views + 1, lastViewed = System.currentTimeMillis(), lastPos = if (d > 0 && pos > d - 5000) 0 else pos,
                watchMs = it.watchMs + watched[0]) }
            p.release(); runCatching { player.second.close() }; onChanged()
        }
    }
    AndroidView({ ctx -> PlayerView(ctx).apply { this.player = player.first; setShowNextButton(false); setShowPreviousButton(false)
        setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { onTap() }) } }, Modifier.fillMaxSize())
}

@Composable
private fun PhotoPage(s: Hv.Session, i: Hv.Item, onTap: () -> Unit) {
    var bmp by remember(i.id) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(i.id) { com.nizam.app.ui.design.guard {
        bmp = withContext(Dispatchers.IO) { runCatching { Hv.Reader(s.media(i.id), s.dek).use { r -> val b = r.readAll()
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }; android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, o)
            var ss = 1; while (o.outWidth / ss > 2400) ss *= 2
            android.graphics.BitmapFactory.decodeByteArray(b, 0, b.size, android.graphics.BitmapFactory.Options().apply { inSampleSize = ss }) } }.getOrNull() }
    } }
    var scale by remember(i.id) { mutableFloatStateOf(1f) }
    var ox by remember(i.id) { mutableFloatStateOf(0f) }
    var oy by remember(i.id) { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize().pointerInput(i.id) {
        detectTransformGestures { _, pan, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 6f); if (scale > 1f) { ox += pan.x; oy += pan.y } else { ox = 0f; oy = 0f } }
    }.clickable { onTap() }, contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().graphicsLayer {
            scaleX = scale; scaleY = scale; translationX = ox; translationY = oy }) } ?: CircularProgressIndicator(color = HOT)
    }
}

// ── tags ──────────────────────────────────────────────────────────────────────────────────────

/** Every tag you can put on a video — your own, then all the ready-made groups — toggled right on the item. */
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChooser(own: List<Pair<String, String>>, picked: Set<String>, onToggle: (String) -> Unit) {
    val presetNames = remember { HV_PRESETS.flatMap { it.second }.map { it.first.lowercase() }.toSet() }
    val custom = own.filter { it.first.lowercase() !in presetNames }
    val groups = (if (custom.isNotEmpty()) listOf("Your own" to custom) else emptyList()) + HV_PRESETS
    groups.forEach { (g, l) ->
        Text(g, color = EMBER, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            l.forEach { (n, ic) -> Pill("$ic $n", picked.any { it.equals(n, true) }) { onToggle(n) } }
        }
    }
}

@Composable
private fun TagPicker(tags: List<Pair<String, String>>, on: List<Hv.Item>, onApply: (List<String>, List<String>) -> Unit, onClose: () -> Unit) {
    val all = remember { (on.flatMap { it.tags } + tags.map { it.first }).distinct() }
    val start = remember { all.filter { t -> on.isNotEmpty() && on.all { t in it.tags } }.toSet() }
    var picked by remember { mutableStateOf(start) }
    Sheet(onClose) {
        Title(if (on.size == 1) "Tags for “${on.first().name}”" else "Tags for ${on.size} videos")
        TagChooser(tags, picked) { n -> picked = picked.firstOrNull { it.equals(n, true) }?.let { picked - it } ?: (picked + n) }
        GradButton("Apply") { onApply((picked - start).toList(), (start - picked).toList()) }
    }
}

@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagManager(s: Hv.Session, onClose: () -> Unit) {
    var tags by remember { mutableStateOf(s.tags()) }
    var rules by remember { mutableStateOf(s.rules()) }
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("🔥") }
    var kw by remember { mutableStateOf("") }
    var kwTag by remember { mutableStateOf("") }
    Sheet(onClose) {
        Title("Your tags")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            tags.forEach { (n, ic) -> Pill("$ic $n  ✕", false) { tags = tags.filter { it.first != n }; s.saveTags(tags) } }
        }
        Text("Quick categories — tap to add", color = DIM, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        HV_PRESETS.forEach { (group, list) ->
            Text(group, color = EMBER, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                list.forEach { (n, ic) ->
                    val have = tags.any { it.first.equals(n, true) }
                    Pill("$ic $n${if (have) " ✓" else ""}", have) {
                        tags = if (have) tags.filter { !it.first.equals(n, true) } else tags + (n to ic); s.saveTags(tags)
                    }
                }
            }
        }
        GradButton("Add all quick categories") {
            tags = (tags + HV_PRESETS.flatMap { it.second }).distinctBy { it.first.lowercase() }; s.saveTags(tags)
        }
        Text("Your own tag", color = DIM, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            HV_ICONS.forEach { ic -> Box(Modifier.size(40.dp).clip(CircleShape).background(if (ic == icon) HOT.copy(alpha = 0.35f) else CARD).clickable { icon = ic },
                contentAlignment = Alignment.Center) { Text(ic, fontSize = 20.sp) } }
        }
        Field(name, "Tag name") { name = it }
        GradButton("Add $icon ${name.trim()}") {
            if (name.isNotBlank() && tags.none { it.first.equals(name.trim(), true) }) { tags = tags + (name.trim() to icon); s.saveTags(tags); name = "" }
        }
        Title("Auto-sort by file name")
        Text("When a file's name contains a word, it gets that tag as it comes in.", color = DIM, style = MaterialTheme.typography.bodySmall)
        rules.forEach { (k, t) -> Text("“$k” → ${tags.firstOrNull { it.first == t }?.second ?: "🏷"} $t   ✕", color = Color.White, modifier = Modifier.clickable {
            rules = rules.filter { it != (k to t) }; s.saveRules(rules) }.padding(vertical = 6.dp)) }
        Field(kw, "Word in the file name") { kw = it }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            (tags.map { it.first } + s.items().flatMap { it.tags }).distinct().forEach { n -> Pill("${tagIcon(tags, n)} $n", n == kwTag) { kwTag = n } }
        }
        GradButton("Add rule") { if (kw.isNotBlank() && kwTag.isNotBlank()) { rules = rules + (kw.trim() to kwTag); s.saveRules(rules); kw = "" } }
        GradButton("Apply rules to everything already here") {
            val r = s.rules()
            s.save(s.items().map { i -> i.copy(tags = (i.tags + r.filter { (k, _) -> i.name.lowercase().contains(k.lowercase()) }.map { it.second }).distinct()) })
            Feedback.show("Rules applied")
        }
    }
}

// ── settings ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun VaultSettings(s: Hv.Session, onClose: () -> Unit, onWiped: () -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var newPin by remember { mutableStateOf("") }
    var decoy by remember { mutableStateOf("") }
    var code by remember { mutableStateOf(Hv.code(c)) }
    var wipe by remember { mutableIntStateOf(Hv.wipeAfter(c)) }
    var confirmWipe by remember { mutableStateOf(false) }
    Sheet(onClose) {
        Title("Vault settings")
        Field(newPin, "New PIN (4–12 digits)") { newPin = it.filter(Char::isDigit).take(12) }
        GradButton("Change PIN") { if (newPin.length >= 4) { val p = newPin; scope.launch { withContext(Dispatchers.Default) { Hv.setPin(c, s.slot, p, s.dek) }; Feedback.show("PIN changed"); newPin = "" } } }
        if (s.slot == "a") {
            Field(decoy, "Decoy PIN — opens a separate, innocent vault") { decoy = it.filter(Char::isDigit).take(12) }
            GradButton(if (Hv.hasDecoy(c)) "Replace decoy PIN" else "Set decoy PIN") {
                if (decoy.length >= 4) { val p = decoy; scope.launch { withContext(Dispatchers.Default) { Hv.setPin(c, "b", p) }; Feedback.show("Decoy PIN set — put some harmless photos in it"); decoy = "" } } }
        }
        Field(code, "Secret code to type in Atlas search") { code = it }
        GradButton("Save code") { if (code.trim().length >= 4) { Hv.setCode(c, code); Feedback.show("Type ${code.trim()} in search to open") } }
        Text("Wipe after wrong PINs", color = DIM, modifier = Modifier.padding(top = 14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
            listOf(0 to "Never", 10 to "10 tries", 20 to "20 tries").forEach { (n, l) -> Pill(l, wipe == n) { wipe = n; Hv.setWipeAfter(c, n) } }
        }
        Text("Locks the moment you leave Atlas or turn the phone face-down. Screenshots and recording are blocked. Nothing here is ever sent anywhere, " +
            "read by Atlas's AI, or included in backups.", color = DIM, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 10.dp))
        if (!confirmWipe) Text("Erase the whole vault…", color = HOT, modifier = Modifier.clickable { confirmWipe = true }.padding(vertical = 10.dp))
        else GradButton("Yes — erase everything forever") { Hv.wipeAll(c); onWiped() }
    }
}

// ── gallery picker (so originals can be removed afterwards) ───────────────────────────────────

private data class G(val uri: Uri, val video: Boolean, val date: Long)

@Composable
private fun GalleryPicker(onCancel: () -> Unit, onPick: (List<Uri>) -> Unit) {
    val c = LocalContext.current
    val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
        else arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    var granted by remember { mutableStateOf(perms.all { androidx.core.content.ContextCompat.checkSelfPermission(c, it) == android.content.pm.PackageManager.PERMISSION_GRANTED }) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = it.values.all { v -> v } }
    var all by remember { mutableStateOf<List<G>>(emptyList()) }
    var picked by remember { mutableStateOf(listOf<Uri>()) }
    var videosOnly by remember { mutableStateOf(true) }
    BackHandler { onCancel() }
    LaunchedEffect(granted) { com.nizam.app.ui.design.guard {
        if (!granted) { ask.launch(perms); return@LaunchedEffect }
        all = withContext(Dispatchers.IO) {
            fun q(base: Uri, video: Boolean) = buildList {
                c.contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED), null, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cur ->
                    while (cur.moveToNext() && size < 3000) add(G(ContentUris.withAppendedId(base, cur.getLong(0)), video, cur.getLong(1))) } }
            (q(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true) + q(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false)).sortedByDescending { it.date }
        }
    } }
    Column(Modifier.fillMaxSize().background(BG).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Color.White, fontSize = 28.sp, modifier = Modifier.clickable { onCancel() }.padding(horizontal = 8.dp))
            Text(if (picked.isEmpty()) "Choose" else "${picked.size} chosen", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Pill("Videos", videosOnly) { videosOnly = true }; Spacer(Modifier.width(6.dp)); Pill("Everything", !videosOnly) { videosOnly = false }
        }
        LazyVerticalGrid(GridCells.Fixed(4), Modifier.weight(1f).padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(all.filter { !videosOnly || it.video }, key = { it.uri.toString() }) { g ->
                var bmp by remember(g.uri) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(g.uri) { com.nizam.app.ui.design.guard { bmp = withContext(Dispatchers.IO) { runCatching {
                    if (Build.VERSION.SDK_INT >= 29) c.contentResolver.loadThumbnail(g.uri, Size(256, 256), null)
                    else MediaStore.Images.Thumbnails.getThumbnail(c.contentResolver, ContentUris.parseId(g.uri), MediaStore.Images.Thumbnails.MINI_KIND, null) }.getOrNull() } } }
                val on = g.uri in picked
                Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).background(CARD).clickable { picked = if (on) picked - g.uri else picked + g.uri }
                    .then(if (on) Modifier.border(3.dp, HOT, RoundedCornerShape(6.dp)) else Modifier)) {
                    bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    if (g.video) Text("▶", color = Color.White, fontSize = 12.sp, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp))
                    if (on) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).clip(CircleShape).background(HOT), contentAlignment = Alignment.Center) {
                        Text("${picked.indexOf(g.uri) + 1}", color = Color.White, fontSize = 10.sp) }
                }
            }
        }
        Box(Modifier.navigationBarsPadding().padding(14.dp)) { GradButton(if (picked.isEmpty()) "Pick some" else "Seal ${picked.size} into the vault") { if (picked.isNotEmpty()) onPick(picked) } }
    }
}

// ── small pieces ──────────────────────────────────────────────────────────────────────────────

@Composable private fun Round(t: String, onClick: () -> Unit) = Box(Modifier.size(40.dp).clip(CircleShape).background(CARD).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
    Text(t, color = Color.White, fontSize = 18.sp) }

@Composable private fun Pill(t: String, on: Boolean, onClick: () -> Unit) = Box(Modifier.clip(RoundedCornerShape(50))
    .background(if (on) GRAD else SolidColor(CARD)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp)) {
    Text(t, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal) }

@Composable private fun Action(icon: String, label: String, onClick: () -> Unit) = Column(Modifier.clickable(onClick = onClick).padding(6.dp),
    horizontalAlignment = Alignment.CenterHorizontally) { Text(icon, fontSize = 20.sp); Text(label, color = DIM, fontSize = 11.sp) }

@Composable private fun Title(t: String) = Text(t, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 8.dp))

@Composable private fun GradButton(t: String, onClick: () -> Unit) = Box(Modifier.padding(top = 10.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(GRAD)
    .clickable(onClick = onClick).padding(14.dp), contentAlignment = Alignment.Center) { Text(t, color = Color.White, fontWeight = FontWeight.Bold) }

@Composable private fun Field(v: String, hint: String, onChange: (String) -> Unit) = Box(Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
    .background(Color(0xFF241C21)).padding(horizontal = 14.dp, vertical = 12.dp)) {
    if (v.isEmpty()) Text(hint, color = DIM)
    BasicTextField(v, onChange, singleLine = true, textStyle = TextStyle(color = Color.White, fontSize = 15.sp), cursorBrush = SolidColor(HOT), modifier = Modifier.fillMaxWidth())
}

@Composable private fun SheetRow(icon: String, title: String, sub: String, onClick: () -> Unit) = Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
    .clickable(onClick = onClick).padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(CARD), contentAlignment = Alignment.Center) { Text(icon, fontSize = 20.sp) }
    Column(Modifier.padding(start = 12.dp)) { Text(title, color = Color.White, fontWeight = FontWeight.SemiBold); Text(sub, color = DIM, style = MaterialTheme.typography.bodySmall) }
}

@Composable
private fun Sheet(onClose: () -> Unit, content: @Composable () -> Unit) {
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(onClick = onClose), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 26.dp, topEnd = 26.dp)).background(Color(0xFF161115)).clickable(enabled = false) {}
            .navigationBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Box(Modifier.align(Alignment.CenterHorizontally).size(width = 40.dp, height = 4.dp).clip(CircleShape).background(Color(0xFF3A3036)))
            content()
            Spacer(Modifier.height(10.dp))
        }
    }
}

/** Your vault in numbers — from your own viewing, no AI. */
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Stats(items: List<Hv.Item>, tags: List<Pair<String, String>>, onClose: () -> Unit) {
    val videos = items.filter { it.video }
    val weekAgo = System.currentTimeMillis() - 7 * 86_400_000L
    fun hours(ms: Long) = "%.1f h".format(ms / 3_600_000.0)
    Sheet(onClose) {
        Title("📊 Your stats")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("🎬" to "${videos.size} videos", "📸" to "${items.size - videos.size} photos", "⏱" to "${hours(videos.sumOf { it.durationMs })} of video",
                "👁" to "${items.sumOf { it.views }} plays", "🔥" to "${hours(items.sumOf { it.watchMs })} watched", "✨" to "${items.count { it.views == 0 }} never watched",
                "❤" to "${items.count { it.fav }} favourites", "🆕" to "${items.count { it.added > weekAgo }} added this week",
                "💾" to "%.1f GB".format(items.sumOf { it.size } / 1_073_741_824.0),
                "★" to (items.filter { it.rating > 0 }.map { it.rating }.average().takeIf { !it.isNaN() }?.let { "%.1f avg rating".format(it) } ?: "no ratings yet")
            ).forEach { (ic, t) -> Column(Modifier.clip(RoundedCornerShape(14.dp)).background(CARD).padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(ic, fontSize = 20.sp); Text(t, color = Color.White, fontWeight = FontWeight.SemiBold) } }
        }
        val top = items.filter { it.views > 0 }.sortedByDescending { it.views }.take(5)
        if (top.isNotEmpty()) { Title("Most watched"); top.forEach { Text("${it.views}×  ${it.name}", color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 3.dp)) } }
        val tagViews = items.flatMap { it.tags }.distinct().map { n -> Triple(n, tagIcon(tags, n), items.filter { n in it.tags }.sumOf { it.views }) }.filter { it.third > 0 }.sortedByDescending { it.third }.take(8)
        if (tagViews.isNotEmpty()) { Title("Top tags")
            tagViews.forEach { (n, ic, v) -> Text("$ic $n · $v plays", color = Color.White, modifier = Modifier.padding(vertical = 3.dp)) } }
        val perf = items.flatMap { i -> i.performers.map { it to i.views } }.groupBy { it.first }.map { it.key to it.value.sumOf { p -> p.second } }
            .sortedByDescending { it.second }.take(8)
        if (perf.isNotEmpty()) { Title("Top performers"); perf.forEach { (n, v) -> Text("💃 $n · $v plays", color = Color.White, modifier = Modifier.padding(vertical = 3.dp)) } }
        val src = items.filter { it.source.isNotBlank() }.groupBy { it.source }.map { it.key to it.value.size }.sortedByDescending { it.second }.take(6)
        if (src.isNotEmpty()) { Title("Sources"); src.forEach { (n, v) -> Text("🎬 $n · $v items", color = Color.White, modifier = Modifier.padding(vertical = 3.dp)) } }
    }
}

/**
 * A private calendar: tap a day to mark it (tap again for more, hold to clear). Counts, streaks and gaps —
 * whether you're tracking or doing a streak challenge. Sealed inside the vault like everything else.
 */
@Composable
private fun Calendar(s: Hv.Session, onClose: () -> Unit) {
    fun load(): Map<String, Int> = s.meta().optJSONObject("cal")?.let { o -> o.keys().asSequence().associateWith { o.optInt(it) } }.orEmpty()
    var days by remember { mutableStateOf(load()) }
    var month by remember { mutableStateOf(java.time.YearMonth.now()) }
    val today = java.time.LocalDate.now()
    fun save(m: Map<String, Int>) { days = m.filterValues { it > 0 }; s.saveMeta(s.meta().put("cal", org.json.JSONObject(days as Map<*, *>))) }
    val marked = days.keys.mapNotNull { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }.sorted()
    val last = marked.lastOrNull()
    val since = last?.let { java.time.temporal.ChronoUnit.DAYS.between(it, today) }
    // the longest run of unmarked days (a streak), and the longest run of marked days in a row
    var longestGap = 0L; var run = 1; var longestRun = if (marked.isEmpty()) 0 else 1
    marked.zipWithNext().forEach { (a, b) -> val g = java.time.temporal.ChronoUnit.DAYS.between(a, b)
        longestGap = maxOf(longestGap, g - 1); if (g == 1L) { run++; longestRun = maxOf(longestRun, run) } else run = 1 }
    since?.let { longestGap = maxOf(longestGap, it) }
    val inMonth = days.filterKeys { it.startsWith(month.toString()) }
    val last30 = days.filterKeys { k -> runCatching { java.time.LocalDate.parse(k) }.getOrNull()?.isAfter(today.minusDays(30)) == true }.values.sum()
    Sheet(onClose) {
        Title("📅 My calendar")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("${since?.let { if (it == 0L) "Today" else "$it days" } ?: "—"}" to "since last", "$longestGap days" to "longest streak",
                "$last30" to "last 30 days").forEach { (v, l) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(CARD).padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(v, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp); Text(l, color = DIM, fontSize = 11.sp) }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = Color.White, fontSize = 26.sp, modifier = Modifier.clickable { month = month.minusMonths(1) }.padding(horizontal = 12.dp))
            Text("${month.month.name.lowercase().replaceFirstChar { it.uppercase() }} ${month.year}", color = Color.White, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("›", color = if (month < java.time.YearMonth.now()) Color.White else DIM, fontSize = 26.sp,
                modifier = Modifier.clickable { if (month < java.time.YearMonth.now()) month = month.plusMonths(1) }.padding(horizontal = 12.dp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp)) { listOf("M", "T", "W", "T", "F", "S", "S").forEach {
            Text(it, color = DIM, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center) } }
        val first = month.atDay(1)
        val lead = first.dayOfWeek.value - 1
        val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        cells.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                week.forEach { d ->
                    Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp), contentAlignment = Alignment.Center) {
                        if (d != null) {
                            val n = days[d.toString()] ?: 0
                            val future = d.isAfter(today)
                            Box(Modifier.fillMaxSize().clip(CircleShape)
                                .background(if (n > 0) GRAD else SolidColor(if (d == today) Color(0xFF3A2A33) else CARD))
                                .pointerInput(d, n) { detectTapGestures(
                                    onTap = { if (!future) save(days + (d.toString() to n + 1)) },
                                    onLongPress = { save(days - d.toString()) }) }, contentAlignment = Alignment.Center) {
                                Text("${d.dayOfMonth}", color = if (future) DIM.copy(alpha = 0.4f) else Color.White, fontSize = 13.sp,
                                    fontWeight = if (n > 0) FontWeight.Bold else FontWeight.Normal)
                                if (n > 1) Text("×$n", color = Color.White, fontSize = 8.sp, modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp))
                            }
                        }
                    }
                }
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        Text("${inMonth.values.sum()} this month · ${inMonth.size} days · longest run of days in a row: $longestRun", color = DIM,
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
        Text("Tap a day to mark it (tap again to add another). Hold to clear.", color = DIM, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
        GradButton("Mark today") { save(days + (today.toString() to (days[today.toString()] ?: 0) + 1)) }
    }
}

/** Albums as big covers: the chosen cover (or the newest item), the name and how much is inside. */
@Composable
private fun AlbumsGrid(s: Hv.Session, items: List<Hv.Item>, albums: List<Pair<String, String>>, onOpen: (String) -> Unit, onChanged: () -> Unit) {
    var name by remember { mutableStateOf("") }
    LazyVerticalGrid(GridCells.Fixed(2), Modifier.fillMaxSize().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(2) }) {
            Column(Modifier.padding(top = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) { Field(name, "New album — a performer, a series, anything") { name = it } }
                    Text("Create", color = HOT, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                        val n = name.trim(); if (n.isNotBlank() && albums.none { it.first.equals(n, true) }) { s.saveAlbums(albums + (n to "")); name = ""; onChanged() } }.padding(12.dp))
                }
                val performers = items.flatMap { it.performers }.distinct().filter { p -> albums.none { it.first.equals(p, true) } }
                if (performers.isNotEmpty()) Text("＋ Make an album for each performer (${performers.size})", color = EMBER, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clickable {
                        s.saveAlbums(albums + performers.map { it to "" })
                        s.save(s.items().map { i -> i.copy(albums = (i.albums + i.performers).distinct()) })
                        onChanged(); Feedback.show("${performers.size} albums made from performers")
                    }.padding(vertical = 10.dp))
                if (albums.isEmpty()) Text("No albums yet. Create one here, or long-press videos in the Library and tap 📁 Album.", color = DIM,
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 20.dp))
            }
        }
        items(albums, key = { it.first }) { (a, cover) ->
            val inside = items.filter { a in it.albums }
            val coverId = cover.takeIf { id -> inside.any { it.id == id } } ?: inside.maxByOrNull { it.added }?.id
            var bmp by remember(coverId) { mutableStateOf<Bitmap?>(null) }
            LaunchedEffect(coverId) { com.nizam.app.ui.design.guard { bmp = coverId?.let { withContext(Dispatchers.IO) { s.thumb(it) } } } }
            Column(Modifier.clip(RoundedCornerShape(14.dp)).clickable { onOpen(a) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(0.9f).clip(RoundedCornerShape(14.dp)).background(CARD)) {
                    bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                        ?: Text("📁", fontSize = 40.sp, modifier = Modifier.align(Alignment.Center))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f)))))
                    val flags = remember(inside) { (HvFlags.of(listOf(a)) + inside.flatMap { HvFlags.of(it) }).distinct().take(4) }
                    if (flags.isNotEmpty()) Text(flags.joinToString(""), fontSize = 18.sp, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
                        .clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.35f)).padding(horizontal = 4.dp))
                    Column(Modifier.align(Alignment.BottomStart).padding(10.dp)) {
                        Text(a, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${inside.count { it.video }} videos · ${inside.count { !it.video }} photos" +
                            (inside.filter { it.video }.sumOf { it.durationMs }.takeIf { it > 0 }?.let { " · ${dur(it)}" } ?: ""), color = DIM, fontSize = 11.sp)
                    }
                }
            }
        }
        item(span = { GridItemSpan(2) }) { Spacer(Modifier.height(60.dp)) }
    }
}

/** Put the selected videos into albums (or take them out), or start a new album with them. */
@kotlin.OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlbumPicker(s: Hv.Session, on: List<Hv.Item>, onDone: () -> Unit) {
    val all = remember { s.albums().map { it.first } }
    val start = remember { all.filter { a -> on.isNotEmpty() && on.all { a in it.albums } }.toSet() }
    var picked by remember { mutableStateOf(start) }
    var name by remember { mutableStateOf("") }
    var list by remember { mutableStateOf(all) }
    Sheet(onDone) {
        Title("Albums for ${on.size} item${if (on.size == 1) "" else "s"}")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            list.forEach { a -> Pill("📁 $a", a in picked) { picked = if (a in picked) picked - a else picked + a } }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            Box(Modifier.weight(1f)) { Field(name, "New album") { name = it } }
            Text("Add", color = HOT, fontWeight = FontWeight.Bold, modifier = Modifier.clickable {
                val n = name.trim(); if (n.isNotBlank() && n !in list) { s.saveAlbums(s.albums() + (n to "")); list = list + n; picked = picked + n; name = "" } }.padding(12.dp))
        }
        GradButton("Save") {
            val ids = on.map { it.id }.toSet(); val add = picked - start; val remove = start - picked
            s.save(s.items().map { i -> if (i.id in ids) i.copy(albums = (i.albums + add - remove).distinct()) else i })
            onDone()
        }
    }
}
