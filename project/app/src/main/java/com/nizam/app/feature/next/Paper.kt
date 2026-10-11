package com.nizam.app.feature.next

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import coil.compose.AsyncImage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.nizam.app.AppContainer
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.EmptySpec
import com.nizam.app.ui.design.EmptyState
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.Radius
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private suspend fun readText(c: Context, uri: Uri): String = runCatching {
    TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(InputImage.fromFilePath(c, uri)).await().text
}.getOrDefault("")

// ── Screenshot organiser ─────────────────────────────────────────────────────────────────────────

/** Sorts your screenshots by what's in them, read on the phone. Results are cached so each is read once. */
object Screenshots {
    val KINDS = listOf("Receipts", "Tickets", "Codes", "Chats", "Links", "Other")
    data class Shot(val id: Long, val uri: Uri, val kind: String, val text: String, val date: Long)

    private fun cache(c: Context) = c.getSharedPreferences("shot_kinds", 0)

    fun classify(t: String): String {
        val x = t.lowercase()
        return when {
            Regex("(rs\\.?|pkr|total|amount|paid|balance)\\s*[:]?\\s*[0-9,]{2,}").containsMatchIn(x) -> "Receipts"
            listOf("pnr", "booking", "boarding", "seat", "flight", "ticket", "e-ticket", "departure").any { x.contains(it) } -> "Tickets"
            (x.contains("code") || x.contains("otp") || x.contains("pin")) && Regex("\\b\\d{4,8}\\b").containsMatchIn(x) -> "Codes"
            listOf("typing", "online", "last seen", "whatsapp", "delivered", "seen").any { x.contains(it) } -> "Chats"
            Regex("https?://|www\\.").containsMatchIn(x) -> "Links"
            else -> "Other"
        }
    }

    suspend fun load(c: Context, limit: Int = 150, onProgress: (Int, Int) -> Unit): List<Shot> = withContext(Dispatchers.IO) {
        val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val rows = mutableListOf<Pair<Long, Long>>()
        val sel = if (Build.VERSION.SDK_INT >= 29) "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?" else "${MediaStore.MediaColumns.DATA} LIKE ?"
        c.contentResolver.query(base, arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED), sel, arrayOf("%Screenshot%"),
            "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cur -> while (cur.moveToNext() && rows.size < limit) rows += cur.getLong(0) to cur.getLong(1) }
        rows.mapIndexed { i, (id, date) ->
            onProgress(i + 1, rows.size)
            val uri = ContentUris.withAppendedId(base, id)
            val cached = cache(c).getString("$id", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
            if (cached != null) Shot(id, uri, cached.optString("k"), cached.optString("t"), date)
            else {
                val t = readText(c, uri)
                val k = classify(t)
                cache(c).edit().putString("$id", JSONObject().put("k", k).put("t", t.take(600)).toString()).apply()
                Shot(id, uri, k, t, date)
            }
        }
    }
}

@Composable
fun ScreenshotsScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var shots by remember { mutableStateOf<List<Screenshots.Shot>?>(null) }
    var progress by remember { mutableStateOf(0 to 0) }
    var kind by remember { mutableStateOf("Receipts") }
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<Long>()) }
    val deleter = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        shots = shots?.filter { it.id !in picked }; picked = emptySet()
    }
    LaunchedEffect(granted) { if (granted) shots = Screenshots.load(ctx) { a, b -> progress = a to b } else ask.launch(perm) }
    val list = shots.orEmpty().filter { query.isBlank() || it.text.contains(query, true) }.filter { query.isNotBlank() || it.kind == kind }
    AtlasScreen(title = "Screenshots", subtitle = if (shots == null && granted) "Reading ${progress.first} of ${progress.second}…"
        else "${shots?.size ?: 0} sorted by what's in them", loading = shots == null && granted) {
        item("q") {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = Space.page), singleLine = true,
                placeholder = { Text("Search inside screenshots") })
        }
        item("k") {
            LazyRow(contentPadding = PaddingValues(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                items(Screenshots.KINDS) { k -> Capsule("$k · ${shots.orEmpty().count { it.kind == k }}", k == kind && query.isBlank()) { kind = k; query = "" } }
            }
        }
        if (picked.isNotEmpty() && Build.VERSION.SDK_INT >= 30) item("del") {
            Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Button(onClick = {
                    val uris = shots.orEmpty().filter { it.id in picked }.map { it.uri }
                    deleter.launch(IntentSenderRequest.Builder(MediaStore.createDeleteRequest(ctx.contentResolver, uris).intentSender).build())
                }) { Text("Delete ${picked.size}") }
                OutlinedButton(onClick = { picked = emptySet() }) { Text("Cancel") }
            }
        }
        if (shots != null && list.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.Image, "Nothing here", "No screenshots in this group.")) }
        list.chunked(3).forEachIndexed { r, row ->
            item("r$r") {
                Row(Modifier.padding(horizontal = Space.page, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { s ->
                        val on = s.id in picked
                        AsyncImage(s.uri, null, contentScale = ContentScale.Crop,
                            modifier = Modifier.weight(1f).aspectRatio(0.56f).clip(Radius.field)
                                .then(if (on) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, Radius.field) else Modifier)
                                .pressable { picked = if (on) picked - s.id else picked + s.id })
                    }
                    repeat(3 - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// ── Bill scanner ─────────────────────────────────────────────────────────────────────────────────

/** Reads the amount and due date off a utility bill photo (WAPDA, SNGPL, PTCL and the like). */
object BillReader {
    data class Bill(val company: String, val amount: Double?, val due: LocalDate?)

    private val companies = listOf("LESCO", "K-Electric", "KE", "IESCO", "FESCO", "MEPCO", "PESCO", "GEPCO", "HESCO", "QESCO", "SEPCO", "TESCO",
        "SNGPL", "SSGC", "PTCL", "WASA", "Nayatel", "StormFiber", "Zong", "Jazz", "Telenor", "Ufone")

    fun parse(t: String): Bill {
        val company = companies.firstOrNull { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(t) } ?: "Bill"
        val amount = Regex("(?i)(payable within due date|amount payable|total payable|net payable|payable|amount due|total)[^0-9]{0,30}([0-9][0-9,]{1,9}(?:\\.[0-9]{1,2})?)")
            .findAll(t).mapNotNull { it.groupValues[2].replace(",", "").toDoubleOrNull() }.filter { it in 50.0..5_000_000.0 }.firstOrNull()
        val dueText = Regex("(?i)due\\s*date[^0-9A-Za-z]{0,8}([0-9]{1,2}[-/ ][A-Za-z]{3}[-/ ][0-9]{2,4}|[0-9]{1,2}[-/][0-9]{1,2}[-/][0-9]{2,4})").find(t)?.groupValues?.get(1)
        val due = dueText?.let { d ->
            listOf("d-MMM-yy", "d-MMM-yyyy", "d MMM yyyy", "d MMM yy", "d/M/yyyy", "d-M-yyyy", "d/M/yy", "d-M-yy").firstNotNullOfOrNull { f ->
                runCatching { LocalDate.parse(d.replace(Regex("\\s+"), " "), DateTimeFormatter.ofPattern(f, Locale.ENGLISH)) }.getOrNull()
            }
        }
        return Bill(company, amount, due)
    }
}

@Composable
fun BillScanScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var bill by remember { mutableStateOf<BillReader.Bill?>(null) }
    var busy by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var due by remember { mutableStateOf("") }
    fun read(uri: Uri?) {
        uri ?: return
        busy = true
        scope.launch {
            text = readText(ctx, uri)
            bill = BillReader.parse(text).also { b ->
                amount = b.amount?.let { "%.0f".format(it) }.orEmpty(); due = b.due?.toString().orEmpty()
            }
            busy = false
        }
    }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { read(it) }
    AtlasScreen(title = "Bill scanner", subtitle = "Photo a bill: amount, due date, reminder") {
        item("pick") {
            Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Button(onClick = { pick.launch("image/*") }, enabled = !busy) { Text(if (busy) "Reading…" else "Choose bill photo") }
            }
        }
        bill?.let { b ->
            item("r") {
                Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                    Text(b.company, style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(amount, { amount = it.filter { ch -> ch.isDigit() || ch == '.' } }, Modifier.fillMaxWidth().padding(top = Space.s),
                        label = { Text("Amount (Rs)") }, singleLine = true)
                    OutlinedTextField(due, { due = it }, Modifier.fillMaxWidth().padding(top = Space.s), label = { Text("Due date (yyyy-mm-dd)") }, singleLine = true)
                    Row(Modifier.padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Button(onClick = {
                            val d = runCatching { LocalDate.parse(due) }.getOrNull()
                            scope.launch {
                                container.taskRepository.add("Pay ${b.company} bill" + (amount.takeIf { it.isNotBlank() }?.let { " · Rs $it" } ?: ""),
                                    d?.minusDays(1)?.atTime(10, 0)?.atZone(java.time.ZoneId.systemDefault())?.toInstant()?.toEpochMilli(), 2)
                                Feedback.show("Reminder set" + (d?.let { " for ${it.minusDays(1)}" } ?: ""))
                            }
                        }) { Text("Remind me") }
                        OutlinedButton(onClick = {
                            amount.toDoubleOrNull()?.let { a -> scope.launch { container.financeRepository.add(a, "EXPENSE", "Bills", "${b.company} bill"); Feedback.show("Logged as paid") } }
                        }) { Text("Log as paid") }
                    }
                }
            }
            item("raw-h") { SectionHeader("What Atlas read") }
            item("raw") { SelectionContainer { Text(text.take(1500), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = Space.page)) } }
        }
    }
}
