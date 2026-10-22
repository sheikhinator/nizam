package com.nizam.app.feature.nx

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.feature.future.Sealed
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.podcasts.PodcastPolicy
import com.nizam.app.feature.podcasts.PodcastStore
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Time capsules: a letter with photos and a voice note, encrypted on the phone and unreadable until its day. */
object Capsules {
    private fun store(c: Context) = JsonStore(c, "nx_capsules", sealed = true)
    private fun dir(c: Context) = File(c.filesDir, "capsules").apply { mkdirs() }
    fun all(c: Context) = store(c).all().sortedBy { it.optString("open") }
    fun isOpen(o: JSONObject) = runCatching { !LocalDate.parse(o.optString("open")).isAfter(LocalDate.now()) }.getOrDefault(false)

    suspend fun create(c: Context, title: String, text: String, open: LocalDate, media: List<Pair<Uri, String>>) = withContext(Dispatchers.IO) {
        val names = media.mapIndexed { i, (uri, kind) ->
            val f = File(dir(c), "${System.currentTimeMillis()}_$i.bin")
            c.contentResolver.openInputStream(uri)?.use { input -> Sealed.sealFile(f) { out -> input.copyTo(out) } }
            JSONObject().put("f", f.name).put("k", kind)
        }
        store(c).add(JSONObject().put("title", title).put("text", text).put("open", open.toString()).put("made", LocalDate.now().toString())
            .put("media", org.json.JSONArray().apply { names.forEach { put(it) } }))
    }

    /** Decrypts one attachment into the cache so it can be viewed or played; only for opened capsules. */
    suspend fun reveal(c: Context, o: JSONObject, i: Int): File? = withContext(Dispatchers.IO) {
        if (!isOpen(o)) return@withContext null
        val m = o.optJSONArray("media")?.optJSONObject(i) ?: return@withContext null
        val src = File(dir(c), m.optString("f")); if (!src.isFile) return@withContext null
        val out = File(c.cacheDir, "capsule_${m.optString("f")}" + if (m.optString("k") == "audio") ".m4a" else ".jpg")
        Sealed.openFile(src).use { input -> out.outputStream().use { input.copyTo(it) } }; out
    }

    fun delete(c: Context, o: JSONObject) {
        o.optJSONArray("media")?.let { a -> (0 until a.length()).forEach { File(dir(c), a.getJSONObject(it).optString("f")).delete() } }
        store(c).remove(o.optString("id"))
    }
}

object Mega7 {
    val all: List<Spec> by lazy { listOf(
        ScreenSpec("capsule", "Time capsules", "A letter with photos and a voice note, sealed until the day you choose", Icons.Outlined.Inventory2, Hub.Memory,
            "time capsule sealed letter future self photos voice note open date") { c, open -> CapsuleScreen() },
        ScreenSpec("podpolicy", "Podcast downloads", "How many episodes to keep, mobile data and a storage cap", Icons.Outlined.DownloadForOffline, Hub.Media,
            "podcast download policy storage keep delete played mobile data") { c, open -> PodPolicyScreen() },
    ) }
}

@Composable
private fun CapsuleScreen() {
    val c = LocalContext.current; val scope = com.nizam.app.ui.design.rememberSafeScope()
    var list by remember { mutableStateOf(Capsules.all(c)) }
    var title by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    var years by remember { mutableStateOf("1") }
    var media by remember { mutableStateOf(listOf<Pair<Uri, String>>()) }
    var status by remember { mutableStateOf("") }
    var shown by remember { mutableStateOf<Map<String, List<File>>>(emptyMap()) }
    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(5)) { l -> media = media + l.map { it to "image" } }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u -> if (u != null) media = media + (u to "audio") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Everything is encrypted with this phone's secure key. Atlas won't open a capsule before its day — not even for you.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(text, { text = it }, label = { Text("Your letter") }, modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Open in")
            listOf("1" to "1 month", "6" to "6 months", "12" to "1 year", "60" to "5 years").forEach { (m, l) ->
                TextButton(onClick = { years = m }) { Text(l, fontWeight = if (years == m) FontWeight.Bold else FontWeight.Normal) } }
        }
        Row {
            TextButton(onClick = { pickPhoto.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("+ Photos") }
            TextButton(onClick = { pickAudio.launch("audio/*") }) { Text("+ Voice note") }
            if (media.isNotEmpty()) Text("${media.size} attached", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
        }
        TextButton(enabled = text.isNotBlank() || media.isNotEmpty(), onClick = { scope.launch {
            status = runCatching { Capsules.create(c, title.ifBlank { "Capsule" }, text, LocalDate.now().plusMonths(years.toLong()), media)
                title = ""; text = ""; media = emptyList(); list = Capsules.all(c); "Sealed. See you then." }.getOrElse { "Couldn't seal it: ${it.message}" }
        } }) { Text("Seal capsule") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        list.forEach { o ->
            val id = o.optString("id"); val opened = Capsules.isOpen(o); val n = o.optJSONArray("media")?.length() ?: 0
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Text("${if (opened) "📬" else "🔒"} ${o.optString("title")}", fontWeight = FontWeight.SemiBold)
                Text("Sealed ${o.optString("made")} · opens ${o.optString("open")}${if (n > 0) " · $n attachments" else ""}", style = MaterialTheme.typography.bodySmall)
                if (opened) {
                    Text(o.optString("text"), style = MaterialTheme.typography.bodyMedium)
                    val files = shown[id]
                    if (files == null && n > 0) TextButton(onClick = { scope.launch { shown = shown + (id to (0 until n).mapNotNull { Capsules.reveal(c, o, it) }) } }) { Text("Show attachments") }
                    files?.forEach { f ->
                        if (f.extension == "jpg") BitmapFactory.decodeFile(f.path)?.let { b ->
                            Image(b.asImageBitmap(), null, Modifier.fillMaxWidth().heightIn(max = 260.dp), contentScale = ContentScale.Fit) }
                        else TextButton(onClick = { Share.file(c, f, "audio/*") }) { Text("▶ Play voice note") }
                    }
                }
                TextButton(onClick = { Capsules.delete(c, o); list = Capsules.all(c) }) { Text(if (opened) "Delete" else "Destroy unopened") }
            }
        }
    }
}

@Composable
private fun PodPolicyScreen() {
    val c = LocalContext.current
    val store = remember { PodcastStore(c) }
    var tick by remember { mutableStateOf(0) }
    val keep = remember(tick) { PodcastPolicy.keep(c, "") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Shows marked auto-download follow these rules on the morning check.", style = MaterialTheme.typography.bodyMedium)
        Text("Using ${remember(tick) { PodcastPolicy.usedMb(store) }} MB of ${PodcastPolicy.capMb(c)} MB", fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Keep per show: $keep", Modifier.weight(1f))
            TextButton(onClick = { PodcastPolicy.setKeep(c, null, keep - 1); tick++ }) { Text("−") }
            TextButton(onClick = { PodcastPolicy.setKeep(c, null, keep + 1); tick++ }) { Text("+") } }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Storage cap", Modifier.weight(1f))
            listOf(500, 1500, 5000).forEach { mb -> TextButton(onClick = { PodcastPolicy.set(c, cap = mb); tick++ }) {
                Text(if (mb >= 1000) "${mb / 1000.0} GB" else "$mb MB", fontWeight = if (PodcastPolicy.capMb(c) == mb) FontWeight.Bold else FontWeight.Normal) } } }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Delete episodes once played", Modifier.weight(1f))
            Switch(PodcastPolicy.deletePlayed(c), { PodcastPolicy.set(c, deletePlayed = it); tick++ }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Also download on mobile data", Modifier.weight(1f))
            Switch(PodcastPolicy.mobileData(c), { PodcastPolicy.set(c, mobile = it); tick++ }) }
        val shows = remember(tick) { store.shows().filter { store.autoDownload(it.feed) } }
        if (shows.isNotEmpty()) Text("Per show", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        shows.forEach { s -> val k = PodcastPolicy.keep(c, s.feed)
            Row(verticalAlignment = Alignment.CenterVertically) { Text("${s.title} · keep $k", Modifier.weight(1f))
                TextButton(onClick = { PodcastPolicy.setKeep(c, s.feed, k - 1); tick++ }) { Text("−") }
                TextButton(onClick = { PodcastPolicy.setKeep(c, s.feed, k + 1); tick++ }) { Text("+") } } }
        TextButton(onClick = { kotlin.concurrent.thread { PodcastPolicy.tidy(c, store) }; tick++ }) { Text("Tidy now") }
    }
}
