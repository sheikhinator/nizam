package com.nizam.app.feature.future

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.OutlinedTextField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * Search your photos by what's in them — "receipts", "CNIC", "beach 2025", "cat", "whiteboard" — found by
 * on-device vision (labels and text in each photo). Nothing is uploaded; the index lives only on this phone.
 */
object PhotoIndex {
    data class Hit(val id: Long, val uri: Uri, val date: Long, val labels: List<String>, val text: String, val album: String)
    val progress = MutableStateFlow(0 to 0)                 // done, total
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private fun file(c: Context) = File(c.filesDir, "photo_index.json")
    @Volatile private var cache: JSONObject? = null

    @Synchronized fun index(c: Context): JSONObject = cache ?: runCatching { JSONObject(file(c).readText()) }.getOrElse { JSONObject() }.also { cache = it }
    @Synchronized private fun persist(c: Context) { cache?.let { file(c).writeText(it.toString()) } }

    fun running() = job?.isActive == true

    fun start(c: Context) {
        if (running()) return
        val app = c.applicationContext
        job = scope.launch {
            val idx = index(app)
            val photos = mutableListOf<Triple<Long, Long, String>>()
            app.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media.BUCKET_DISPLAY_NAME),
                null, null, "${MediaStore.Images.Media.DATE_TAKEN} DESC")?.use { cur ->
                while (cur.moveToNext() && photos.size < 6000) photos += Triple(cur.getLong(0), cur.getLong(1), cur.getString(2).orEmpty())
            }
            val todo = photos.filter { !idx.has(it.first.toString()) }
            val labeler = ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.6f).build())
            val reader = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            var done = photos.size - todo.size
            progress.value = done to photos.size
            for ((id, date, album) in todo) {
                if (!isActive) break
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                runCatching {
                    val bmp: Bitmap = if (Build.VERSION.SDK_INT >= 29) app.contentResolver.loadThumbnail(uri, Size(640, 640), null)
                        else MediaStore.Images.Thumbnails.getThumbnail(app.contentResolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
                    val img = InputImage.fromBitmap(bmp, 0)
                    val labels = runCatching { labeler.process(img).await().map { it.text } }.getOrDefault(emptyList())
                    val text = runCatching { reader.process(img).await().text.replace(Regex("\\s+"), " ").take(600) }.getOrDefault("")
                    synchronized(this@PhotoIndex) { idx.put(id.toString(), JSONObject().put("l", labels.joinToString(",")).put("t", text).put("d", date).put("a", album)) }
                }
                done++
                if (done % 25 == 0) { progress.value = done to photos.size; persist(app) }
            }
            progress.value = done to photos.size; persist(app)
        }
    }

    private val SYN = mapOf(
        "receipt" to listOf("total", "amount", "rs", "invoice", "bill", "receipt", "qty", "tax"),
        "bill" to listOf("bill", "amount", "due date", "units", "lesco", "sngpl", "total"),
        "cnic" to listOf("identity card", "national identity", "pakistan", "cnic", "holder"),
        "id" to listOf("identity", "card", "cnic", "license", "licence", "passport"),
        "passport" to listOf("passport", "nationality", "place of birth"),
        "document" to listOf("document", "paper", "text"),
        "food" to listOf("food", "dish", "meal", "cuisine", "fast food", "dessert"),
        "people" to listOf("person", "selfie", "smile", "crowd", "team"),
        "me" to listOf("selfie", "smile", "person"),
        "car" to listOf("car", "vehicle", "wheel", "motorcycle"),
        "beach" to listOf("beach", "sea", "ocean", "sand", "waves"),
        "mountain" to listOf("mountain", "hill", "snow", "sky", "cliff"),
        "pet" to listOf("cat", "dog", "pet", "bird"),
        "screenshot" to listOf("screenshot"),
        "whiteboard" to listOf("whiteboard", "blackboard", "handwriting", "text"),
        "notes" to listOf("paper", "handwriting", "text", "notebook"),
    )
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    fun search(c: Context, query: String): List<Hit> {
        val idx = index(c)
        val words = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val year = words.firstOrNull { it.matches(Regex("(19|20)\\d{2}")) }?.toInt()
        val month = words.firstNotNullOfOrNull { w -> MONTHS.indexOfFirst { w.startsWith(it) }.takeIf { it >= 0 } }
        val terms = words.filter { it != year?.toString() && MONTHS.none { m -> it.startsWith(m) } && it !in setOf("photo", "photos", "pic", "pics", "picture", "of", "my", "the", "in", "from") }
        val out = mutableListOf<Pair<Hit, Int>>()
        synchronized(this) {
            idx.keys().forEach { k ->
                val o = idx.getJSONObject(k)
                val date = o.optLong("d")
                val dt = Instant.ofEpochMilli(date).atZone(ZoneId.systemDefault())
                if (year != null && dt.year != year) return@forEach
                if (month != null && dt.monthValue != month + 1) return@forEach
                val labels = o.optString("l").lowercase(); val text = o.optString("t").lowercase(); val album = o.optString("a").lowercase()
                var score = if (terms.isEmpty()) 1 else 0
                for (t in terms) {
                    val keys = listOf(t) + SYN[t.removeSuffix("s")].orEmpty()
                    if (keys.any { labels.contains(it) }) score += 3
                    if (keys.any { text.contains(it) }) score += 2
                    if (album.contains(t)) score += 2
                }
                if (score > 0) out += Hit(k.toLong(), ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, k.toLong()), date,
                    labels.split(',').filter { it.isNotBlank() }, text, album) to score
            }
        }
        return out.sortedWith(compareByDescending<Pair<Hit, Int>> { it.second }.thenByDescending { it.first.date }).take(300).map { it.first }
    }
}

@Composable
fun PhotoSearchScreen() {
    val c = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val progress by PhotoIndex.progress.collectAsState()
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<PhotoIndex.Hit>>(emptyList()) }
    LaunchedEffect(granted) { if (granted) PhotoIndex.start(c) else ask.launch(perm) }
    LaunchedEffect(query, progress.first / 200) { hits = if (query.isBlank()) emptyList() else PhotoIndex.search(c, query) }
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        item(span = { GridItemSpan(3) }) {
            Column {
                ModuleTitle("Photo search", "Describe a photo — Atlas finds it. All on this phone.")
                if (!granted) Button(onClick = { ask.launch(perm) }) { Text("Allow access to photos") }
                OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("receipts, CNIC, beach 2025, whiteboard…") })
                ChipRow(listOf("Receipts", "CNIC", "Documents", "Screenshots", "Food", "Pets", "Cars", "People", "Notes"), null, { query = it.lowercase() })
                val (done, total) = progress
                if (total > 0 && done < total) {
                    Text("Learning your photos: $done of $total (searchable as it goes)", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                    LinearProgressIndicator(progress = { done / total.toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp))
                }
                if (query.isNotBlank()) Text("${hits.size} found", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(vertical = 6.dp))
            }
        }
        items(hits, key = { it.id }) { h ->
            AsyncImage(h.uri, h.labels.joinToString(), contentScale = ContentScale.Crop, modifier = Modifier.aspectRatio(1f).clip(RoundedCornerShape(6.dp)).clickable {
                runCatching { c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(h.uri, "image/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
            })
        }
    }
}
