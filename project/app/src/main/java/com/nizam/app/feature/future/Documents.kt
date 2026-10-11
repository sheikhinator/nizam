package com.nizam.app.feature.future

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** Your important papers: photos sealed with the phone's hardware key, details read from them, reminders before they expire. */
object Documents {
    val TYPES = listOf("CNIC", "Driving licence", "Passport", "Vehicle registration", "Degree", "Certificate", "Warranty", "Prescription", "Property", "Insurance", "Other")
    data class Doc(val id: String, val type: String, val title: String, val number: String, val expiry: String, val images: List<String>, val notes: String)

    fun store(c: Context) = SealedList(c, "documents")
    fun dir(c: Context) = File(c.filesDir, "docs").apply { mkdirs() }
    fun all(c: Context) = store(c).all().map { o -> Doc(o.optString("id"), o.optString("type"), o.optString("title"), o.optString("number"),
        o.optString("expiry"), o.optJSONArray("images")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(), o.optString("notes")) }
        .sortedWith(compareBy<Doc> { it.expiry.ifBlank { "9999" } })

    fun save(c: Context, d: Doc) {
        store(c).remove(d.id)
        store(c).add(JSONObject().put("id", d.id).put("type", d.type).put("title", d.title).put("number", d.number).put("expiry", d.expiry)
            .put("images", JSONArray(d.images)).put("notes", d.notes))
    }
    fun delete(c: Context, d: Doc) { d.images.forEach { File(dir(c), it).delete() }; store(c).remove(d.id) }

    suspend fun sealImage(c: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't open that picture")
        val name = "d${System.nanoTime()}.bin"
        File(dir(c), name).writeBytes(Sealed.seal(bytes)); name
    }
    fun image(c: Context, name: String, max: Int = 1600): Bitmap? = runCatching {
        val b = Sealed.open(File(dir(c), name).readBytes())
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeByteArray(b, 0, b.size, o)
        var s = 1; while (o.outWidth / s > max) s *= 2
        BitmapFactory.decodeByteArray(b, 0, b.size, BitmapFactory.Options().apply { inSampleSize = s })
    }.getOrNull()

    /** Reads the text on a document and guesses its number and expiry date. */
    suspend fun read(c: Context, uri: Uri): Triple<String, String, String> {
        val text = runCatching { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromFilePath(c, uri)).await().text }.getOrDefault("")
        val number = Regex("\\b\\d{5}-\\d{7}-\\d\\b").find(text)?.value
            ?: Regex("\\b[A-Z]{2}\\d{7}\\b").find(text)?.value
            ?: Regex("(?:No\\.?|Number|#)\\s*[:.]?\\s*([A-Z0-9-]{6,})", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1).orEmpty()
        val dates = Regex("\\b(\\d{1,2})[./-](\\d{1,2})[./-](\\d{4})\\b").findAll(text).mapNotNull { m ->
            runCatching { LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt()) }.getOrNull() }.toList()
        val expiry = dates.maxOrNull()?.takeIf { it.isAfter(LocalDate.now().minusYears(1)) }?.toString().orEmpty()
        val type = when {
            Regex("\\d{5}-\\d{7}-\\d").containsMatchIn(text) && text.contains("Pakistan", true) -> "CNIC"
            text.contains("passport", true) -> "Passport"
            text.contains("licen", true) -> "Driving licence"
            else -> ""
        }
        return Triple(type, number, expiry)
    }

    /** Daily, from the background check: a nudge 30, 7 and 1 days before anything expires. */
    fun remind(c: Context) {
        val p = c.getSharedPreferences("documents", 0)
        val today = LocalDate.now()
        all(c).filter { it.expiry.isNotBlank() }.forEach { d ->
            val days = runCatching { ChronoUnit.DAYS.between(today, LocalDate.parse(d.expiry)) }.getOrNull() ?: return@forEach
            val mark = "${d.id}:$days"
            if (days in listOf(30L, 7L, 1L, 0L) && !p.getBoolean(mark, false)) {
                com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 700 + (d.id.hashCode() and 0xFF),
                    "${d.title.ifBlank { d.type }} ${if (days == 0L) "expires today" else "expires in $days day${if (days == 1L) "" else "s"}"}",
                    "Open Atlas › Documents for the renewal steps.")
                p.edit().putBoolean(mark, true).apply()
            }
        }
    }

    fun share(c: Context, d: Doc) {
        val uris = ArrayList(d.images.mapIndexedNotNull { i, n ->
            val f = File(c.cacheDir, "doc_${d.type.replace(' ', '_')}_${i + 1}.jpg")
            runCatching { f.writeBytes(Sealed.open(File(dir(c), n).readBytes())); FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", f) }.getOrNull()
        })
        if (uris.isEmpty()) return
        c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND_MULTIPLE).setType("image/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Share ${d.title.ifBlank { d.type }}"))
    }
}

@Composable
fun DocumentsScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var docs by remember { mutableStateOf(Documents.all(c)) }
    var editing by remember { mutableStateOf<Documents.Doc?>(null) }
    var viewing by remember { mutableStateOf<Documents.Doc?>(null) }
    var renewal by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            val (type, number, expiry) = Documents.read(c, uris.first())
            val imgs = uris.map { Documents.sealImage(c, it) }
            editing = Documents.Doc(System.nanoTime().toString(), type.ifBlank { "Other" }, "", number, expiry, imgs, "")
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Documents", "Sealed on this phone — reminders before anything expires")
        Button(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { Text("Add a document") }
        Text("Take photos of both sides first, then add them here — Atlas reads the number and expiry for you.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))

        editing?.let { d ->
            var type by remember(d.id) { mutableStateOf(d.type) }
            var title by remember(d.id) { mutableStateOf(d.title) }
            var number by remember(d.id) { mutableStateOf(d.number) }
            var expiry by remember(d.id) { mutableStateOf(d.expiry) }
            var notes by remember(d.id) { mutableStateOf(d.notes) }
            Group(Modifier.padding(top = 12.dp)) {
                Text("Details", style = MaterialTheme.typography.titleSmall)
                ChipRow(Documents.TYPES, type, { type = it })
                OutlinedTextField(title, { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Name (e.g. My CNIC, Ammi's passport)") })
                OutlinedTextField(number, { number = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Number") })
                OutlinedTextField(expiry, { expiry = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Expiry (YYYY-MM-DD)") })
                OutlinedTextField(notes, { notes = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Notes") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = {
                        if (expiry.isNotBlank() && runCatching { LocalDate.parse(expiry) }.isFailure) { Feedback.show("Expiry should look like 2027-03-14"); return@Button }
                        Documents.save(c, d.copy(type = type, title = title.trim(), number = number.trim(), expiry = expiry.trim(), notes = notes))
                        docs = Documents.all(c); editing = null; Feedback.show("Saved and sealed")
                    }) { Text("Save") }
                    OutlinedButton(onClick = { editing = null }) { Text("Cancel") }
                }
            }
        }

        viewing?.let { d ->
            Group(Modifier.padding(top = 12.dp)) {
                Text(d.title.ifBlank { d.type }, style = MaterialTheme.typography.titleMedium)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    d.images.forEach { n ->
                        var bmp by remember(n) { mutableStateOf<Bitmap?>(null) }
                        LaunchedEffect(n) { bmp = withContext(Dispatchers.IO) { Documents.image(c, n) } }
                        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Fit, modifier = Modifier.height(220.dp).clip(RoundedCornerShape(10.dp))) }
                    }
                }
                if (d.number.isNotBlank()) Text("Number: ${d.number}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 6.dp))
                if (d.notes.isNotBlank()) Text(d.notes, style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                    OutlinedButton(onClick = { Documents.share(c, d) }) { Text("Share") }
                    OutlinedButton(onClick = { editing = d; viewing = null }) { Text("Edit") }
                    OutlinedButton(onClick = {
                        renewal = "…"
                        scope.launch {
                            renewal = runCatching { com.nizam.app.net.Ai.generate(container.settingsRepository,
                                "How do I renew a ${d.type} in Pakistan? Current official steps, where to go or which website/app, documents to bring, fees and time. Short numbered list.",
                                useWeb = true, maxOutputTokens = 700) }.getOrElse { "Couldn't look that up: ${Feedback.friendly(it)}" }
                        }
                    }) { Text("How to renew") }
                    OutlinedButton(onClick = { Documents.delete(c, d); docs = Documents.all(c); viewing = null; Feedback.show("Deleted") }) { Text("Delete") }
                }
                if (renewal.isNotBlank()) Text(renewal, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
        }

        Label(if (docs.isEmpty()) "Nothing here yet" else "${docs.size} documents")
        docs.forEach { d ->
            val days = runCatching { ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(d.expiry)) }.getOrNull()
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { viewing = d; renewal = "" }.padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                var thumb by remember(d.id) { mutableStateOf<Bitmap?>(null) }
                LaunchedEffect(d.id) { thumb = withContext(Dispatchers.IO) { d.images.firstOrNull()?.let { Documents.image(c, it, 200) } } }
                thumb?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))) }
                    ?: Text("🪪", style = MaterialTheme.typography.headlineMedium)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(d.title.ifBlank { d.type }, style = MaterialTheme.typography.bodyLarge)
                    Text(listOfNotNull(d.type.takeIf { d.title.isNotBlank() }, d.number.ifBlank { null }).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                days?.let { Text(when { it < 0 -> "Expired"; it == 0L -> "Today"; it < 60 -> "$it days"; else -> LocalDate.parse(d.expiry).format(DateTimeFormatter.ofPattern("MMM yyyy")) },
                    style = MaterialTheme.typography.labelMedium, color = if (it < 30) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Spacer(Modifier.height(60.dp))
    }
}
