package com.nizam.app.feature.platform

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.agent.Snapshot
import com.nizam.app.feature.insight.Days
import com.nizam.app.feature.scan.recognise
import com.nizam.app.net.Ai
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.time.LocalDate

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) { content() } }
}

@Composable
fun PlatformScreen(container: AppContainer) {
    var tab by remember { mutableStateOf("Screenshots") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Tools", "Screenshots, imports, sketches, desktop view, places")
        ChipRow(listOf("Screenshots", "Import", "Sketch", "Desktop", "Places"), tab, { tab = it })
        Spacer(Modifier.height(8.dp))
        when (tab) {
            "Screenshots" -> ScreenshotInbox(container)
            "Import" -> ImportTab(container)
            "Sketch" -> SketchTab(container)
            "Desktop" -> DesktopTab(container)
            else -> PlacesTab()
        }
        Spacer(Modifier.height(60.dp))
    }
}

// ── Screenshot inbox ──────────────────────────────────────────────────────────
/** Reads recent screenshots on-device and files each one: receipts → Finance, recipes → Recipes, the rest → Notes. */
@Composable
private fun ScreenshotInbox(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val log = remember { mutableStateListOf<String>() }
    var busy by remember { mutableStateOf(false) }
    val prefs = c.getSharedPreferences("shots", 0)

    Text("Atlas reads your recent screenshots on the phone and files each one where it belongs.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!granted) Button(onClick = { ask.launch(perm) }) { Text("Allow access to screenshots") }
    else Button(enabled = !busy, onClick = {
        scope.launch {
            busy = true; log.clear()
            val done = prefs.getStringSet("done", emptySet())!!.toMutableSet()
            val since = System.currentTimeMillis() / 1000 - 14 * 86_400
            val uris = withContext(Dispatchers.IO) {
                val out = mutableListOf<Pair<Long, Uri>>()
                val col = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else MediaStore.Images.Media.DATA
                c.contentResolver.query(col, arrayOf(MediaStore.Images.Media._ID),
                    "$pathCol LIKE ? AND ${MediaStore.Images.Media.DATE_ADDED} >= ?", arrayOf("%Screenshot%", "$since"),
                    "${MediaStore.Images.Media.DATE_ADDED} DESC")?.use { cur ->
                    while (cur.moveToNext() && out.size < 25) { val id = cur.getLong(0); out += id to ContentUris.withAppendedId(col, id) }
                }
                out
            }.filter { "${it.first}" !in done }
            if (uris.isEmpty()) log += "No new screenshots from the last two weeks."
            uris.forEach { (id, uri) ->
                runCatching {
                    val text = recognise(c, uri, chinese = false)
                    if (text.length < 15) { log += "Skipped an image with no readable text."; return@runCatching }
                    val o = JSONObject(Ai.cleanJson(Ai.generate(container.settingsRepository,
                        "Classify this screenshot text and extract. Return ONLY JSON {\"kind\":\"receipt|recipe|note\"," +
                            "\"title\":\"short\",\"amount\":0,\"category\":\"Food|Transport|Bills|Groceries|Shopping|Health|Family|Other\"," +
                            "\"summary\":\"one or two lines\"}\n\n${text.take(3000)}", maxOutputTokens = 400)))
                    when (o.optString("kind")) {
                        "receipt" -> if (o.optDouble("amount", 0.0) > 0) {
                            container.financeRepository.addOn("", o.optDouble("amount"), "EXPENSE", o.optString("category", "Other"), o.optString("title"))
                            log += "💰 Receipt > Finance: ${o.optDouble("amount").toInt()} PKR, ${o.optString("title")}"
                        } else { container.noteRepository.createFull("Screenshot: ${o.optString("title")}", text); log += "🗒 Note: ${o.optString("title")}" }
                        "recipe" -> {
                            if (container.dynamicRepository.spec("recipes") != null)
                                container.dynamicRepository.addEntry("recipes", o.optString("title"), emptyMap())
                            container.noteRepository.createFull("Recipe: ${o.optString("title")}", text)
                            log += "🍲 Recipe > Recipes & Notes: ${o.optString("title")}"
                        }
                        else -> { container.noteRepository.createFull("Screenshot: ${o.optString("title")}", "${o.optString("summary")}\n\n$text"); log += "🗒 Note: ${o.optString("title")}" }
                    }
                    done += "$id"
                }.onFailure { log += "Couldn't process one: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
            }
            prefs.edit().putStringSet("done", done).apply()
            busy = false
        }
    }) { Text(if (busy) "Reading screenshots…" else "Process new screenshots") }
    log.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 2.dp)) }
}

// ── Import from CSV ───────────────────────────────────────────────────────────
private fun parseCsv(text: String): List<List<String>> = text.lines().filter { it.isNotBlank() }.map { line ->
    val out = mutableListOf<String>(); val cur = StringBuilder(); var q = false; var i = 0
    while (i < line.length) {
        val ch = line[i]
        when {
            ch == '"' && q && i + 1 < line.length && line[i + 1] == '"' -> { cur.append('"'); i++ }
            ch == '"' -> q = !q
            ch == ',' && !q -> { out += cur.toString().trim(); cur.clear() }
            else -> cur.append(ch)
        }
        i++
    }
    out += cur.toString().trim(); out
}

@Composable
private fun ImportTab(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var rows by remember { mutableStateOf<List<List<String>>>(emptyList()) }
    var target by remember { mutableStateOf("finance") }
    var modules by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { com.nizam.app.ui.design.guard {
        modules = listOf("finance" to "Finance") + container.dynamicRepository.specs().first().map { it.key to it.name }
    } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { rows = runCatching { parseCsv(c.contentResolver.openInputStream(it)!!.bufferedReader().use { r -> r.readText() }) }.getOrElse { emptyList() } }
    }
    Text("Bring history in from Excel or Google Sheets: save as CSV with a header row, then map it to a module.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = { pick.launch(arrayOf("text/*", "text/csv", "application/vnd.ms-excel", "*/*")) }) { Text("Choose CSV file") }
    if (rows.size >= 2) {
        val header = rows.first()
        Text("${rows.size - 1} rows · columns: ${header.joinToString()}", style = MaterialTheme.typography.labelMedium)
        Text("Import into", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
        ChipRow(modules.map { it.first }, target, { target = it }, labels = { k -> modules.firstOrNull { it.first == k }?.second ?: k })
        Button(onClick = {
            scope.launch {
                val h = header.map { it.lowercase() }
                fun col(vararg names: String) = h.indexOfFirst { name -> names.any { name.contains(it) } }
                var n = 0
                var skipped = 0
                if (target == "finance") {
                    val d = col("date"); val a = col("amount", "value", "price", "cost"); val cat = col("category", "type")
                    val note = col("note", "description", "merchant", "detail"); val kind = col("kind", "income", "expense")
                    rows.drop(1).forEach { r ->
                        val amount = r.getOrNull(a)?.replace(Regex("[^0-9.\\-]"), "")?.toDoubleOrNull()
                        if (amount == null || !amount.isFinite()) { skipped++; return@forEach }
                        val rawDate = r.getOrNull(d).orEmpty().trim()
                        val date = if (rawDate.isBlank()) "" else
                            runCatching { LocalDate.parse(rawDate.take(10)).toString() }.getOrNull()
                        if (date == null) { skipped++; return@forEach }
                        val kindText = r.getOrNull(kind).orEmpty().trim()
                        val k = when {
                            kind < 0 || kindText.isBlank() || kindText.contains("expense", true) -> "EXPENSE"
                            kindText.contains("income", true) -> "INCOME"
                            else -> { skipped++; return@forEach }
                        }
                        val saved = runCatching {
                            container.financeRepository.addOn(
                                date, kotlin.math.abs(amount), k,
                                r.getOrNull(cat).orEmpty().ifBlank { "Other" },
                                r.getOrNull(note).orEmpty()
                            )
                        }.isSuccess
                        if (saved) n++ else skipped++
                    }
                } else {
                    val spec = container.dynamicRepository.spec(target) ?: return@launch
                    val titleCol = col("title", "name", "item").takeIf { it >= 0 } ?: 0
                    val map = spec.fields.associate { f -> f.key to h.indexOfFirst { it == f.key.lowercase() || it == f.label.lowercase() || it.contains(f.label.lowercase()) } }
                        .filterValues { it >= 0 && it != titleCol }
                    rows.drop(1).forEach { r ->
                        val title = r.getOrNull(titleCol).orEmpty(); if (title.isBlank()) return@forEach
                        container.dynamicRepository.addEntry(target, title, map.mapValues { r.getOrNull(it.value).orEmpty() }); n++
                    }
                }
                status = if (target == "finance") {
                    "Imported $n transactions; skipped $skipped invalid or failed rows."
                } else "Imported $n rows."
                rows = emptyList()
            }
        }) { Text("Import") }
    }
    status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
}

// ── Sketch pad ────────────────────────────────────────────────────────────────
private data class Stroke2(val points: List<Offset>, val colour: Color, val width: Float)

@Composable
private fun SketchTab(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    val strokes = remember { mutableStateListOf<Stroke2>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var colour by remember { mutableStateOf(Color(0xFF111111)) }
    var width by remember { mutableStateOf(6f) }
    var canvasSize by remember { mutableStateOf(androidx.compose.ui.geometry.Size.Zero) }
    var status by remember { mutableStateOf<String?>(null) }
    val palette = listOf(Color(0xFF111111), Color(0xFFE06A4C), Color(0xFF4FC3A1), Color(0xFF58A6C9), Color(0xFFE0B14A), Color(0xFF9B8CD6))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        palette.forEach { p -> Box(Modifier.size(32.dp).background(p, CircleShape).clickable(role = Role.Button) { colour = p }) }
    }
    ChipRow(listOf("3", "6", "12", "24"), "${width.toInt()}", { width = it.toFloat() }, labels = { "${it}px" })
    Canvas(Modifier.fillMaxWidth().aspectRatio(0.8f).background(Color.White, RoundedCornerShape(16.dp))
        .pointerInput(colour, width) {
            detectDragGestures(onDragStart = { current = listOf(it) }, onDragEnd = {
                strokes += Stroke2(current, colour, width); current = emptyList()
            }) { change, _ -> current = current + change.position }
        }) {
        canvasSize = size
        fun draw(pts: List<Offset>, col: Color, w: Float) {
            if (pts.size < 2) return
            val p = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
            drawPath(p, col, style = Stroke(w, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        strokes.forEach { draw(it.points, it.colour, it.width) }
        draw(current, colour, width)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
        OutlinedButton(onClick = { if (strokes.isNotEmpty()) strokes.removeAt(strokes.lastIndex) }) { Text("Undo") }
        OutlinedButton(onClick = { strokes.clear() }) { Text("Clear") }
        Button(enabled = strokes.isNotEmpty(), onClick = {
            scope.launch {
                val w = canvasSize.width.toInt().coerceAtLeast(1); val h = canvasSize.height.toInt().coerceAtLeast(1)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val cv = android.graphics.Canvas(bmp); cv.drawColor(android.graphics.Color.WHITE)
                strokes.forEach { s ->
                    val paint = android.graphics.Paint().apply { color = s.colour.toArgb(); strokeWidth = s.width
                        style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND; isAntiAlias = true }
                    val path = android.graphics.Path(); s.points.firstOrNull()?.let { path.moveTo(it.x, it.y) }
                    s.points.drop(1).forEach { path.lineTo(it.x, it.y) }; cv.drawPath(path, paint)
                }
                val dir = File(c.filesDir, "sketches").apply { mkdirs() }
                val file = File(dir, "sketch_${System.currentTimeMillis()}.png")
                withContext(Dispatchers.IO) { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                val uri = FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", file)
                val saved = container.dynamicRepository.spec("gallery") != null
                if (saved) container.dynamicRepository.addEntry("gallery", "Sketch ${LocalDate.now()}", mapOf("file" to "$uri|${file.name}|image/png"))
                status = if (saved) "Saved to Gallery." else "Saved to Atlas storage."
                strokes.clear()
            }
        }) { Text("Save") }
    }
    status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
}

// ── Desktop view over Wi-Fi ───────────────────────────────────────────────────
private fun localIp(): String? = NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
    .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress

/**
 * A tiny web server, alive only while this screen is open, serving your dashboard to a browser on
 * the same Wi-Fi. A random token in the link keeps anyone else on the network out.
 */
@Composable
private fun DesktopTab(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var running by remember { mutableStateOf(false) }
    val token = remember { java.util.UUID.randomUUID().toString().take(8) }
    var server by remember { mutableStateOf<ServerSocket?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { runCatching { server?.close() }; job?.cancel() } }

    suspend fun page(): String {
        val snap = Snapshot.of(container)
        val days = Days.build(container, c, 30).reversed()
        val rows = days.joinToString("") { d ->
            "<tr><td>${d.date}</td>" + listOf("mood", "sleep", "steps", "sets", "study", "prayers", "spend", "focus")
                .joinToString("") { k -> "<td>${d.values[k]?.let { v -> if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v) } ?: ""}</td>" } + "</tr>"
        }
        val esc = { s: String -> s.replace("&", "&amp;").replace("<", "&lt;") }
        return """<!doctype html><html><head><meta charset=utf-8><meta http-equiv=refresh content=60><title>Atlas</title>
<style>body{font:15px system-ui;background:#0B0F1A;color:#EDEFF5;max-width:1100px;margin:30px auto;padding:0 20px}
h1{font-family:Georgia,serif;font-weight:400}pre{white-space:pre-wrap;background:#141A28;padding:18px;border-radius:16px;line-height:1.5}
table{border-collapse:collapse;width:100%;background:#141A28;border-radius:16px;overflow:hidden}td,th{padding:7px 10px;text-align:right;border-bottom:1px solid #1f2638}
th{color:#8C96AC;font-weight:500}td:first-child,th:first-child{text-align:left}</style></head><body>
<h1>Atlas</h1><pre>${esc(snap)}</pre><h2>Last 30 days</h2><table><tr><th>Date</th><th>Mood</th><th>Sleep</th><th>Steps</th><th>Sets</th>
<th>Study</th><th>Prayers</th><th>Spend</th><th>Focus</th></tr>$rows</table><p style=color:#8C96AC>Refreshes every minute · served from your phone</p></body></html>"""
    }

    Text("See your Atlas dashboard in a laptop browser on the same Wi-Fi. Nothing goes to the internet — it's served straight " +
        "from your phone, only while this screen is open.", style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (!running) Button(onClick = {
        runCatching {
            val s = ServerSocket(8765); server = s; running = true
            job = scope.launch(Dispatchers.IO) {
                while (isActive && !s.isClosed) {
                    val sock = runCatching { s.accept() }.getOrNull() ?: break
                    launch {
                        sock.use { so ->
                            val req = so.getInputStream().bufferedReader().readLine().orEmpty()
                            val ok = req.contains("t=$token")
                            val body = if (ok) page() else "<h1>Atlas</h1><p>Wrong or missing link token.</p>"
                            val bytes = body.toByteArray()
                            so.getOutputStream().apply {
                                write(("HTTP/1.1 ${if (ok) "200 OK" else "403 Forbidden"}\r\nContent-Type: text/html; charset=utf-8\r\n" +
                                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                                write(bytes); flush()
                            }
                        }
                    }
                }
            }
        }
    }) { Text("Start desktop view") }
    else {
        Card {
            Text("Open this on your laptop:", style = MaterialTheme.typography.labelMedium)
            Text("http://${localIp() ?: "your-phone-ip"}:8765/?t=$token", style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        OutlinedButton(onClick = { runCatching { server?.close() }; job?.cancel(); running = false }) { Text("Stop") }
    }
}

// ── Places ────────────────────────────────────────────────────────────────────
object PlaceStore {
    private fun f(c: Context) = File(c.filesDir, "places.json")
    fun all(c: Context): JSONArray = runCatching { JSONArray(f(c).readText()) }.getOrElse { JSONArray() }
    fun save(c: Context, a: JSONArray) = f(c).writeText(a.toString())
    fun visits(c: Context): JSONArray = runCatching { JSONArray(File(c.filesDir, "visits.json").readText()) }.getOrElse { JSONArray() }
    fun logVisit(c: Context, name: String, entering: Boolean) {
        val a = visits(c); a.put(JSONObject().put("place", name).put("enter", entering).put("at", System.currentTimeMillis()))
        File(c.filesDir, "visits.json").writeText(a.toString())
    }
    /** Minutes spent at each place today, pairing enter/exit events. */
    fun minutesToday(c: Context): Map<String, Long> {
        val start = LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val ev = visits(c).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.filter { it.optLong("at") >= start }
        val out = HashMap<String, Long>(); val open = HashMap<String, Long>()
        ev.forEach { e -> val p = e.optString("place")
            if (e.optBoolean("enter")) open[p] = e.optLong("at")
            else open.remove(p)?.let { out[p] = (out[p] ?: 0) + (e.optLong("at") - it) / 60_000 } }
        open.forEach { (p, t) -> out[p] = (out[p] ?: 0) + (System.currentTimeMillis() - t) / 60_000 }
        return out
    }
}

class PlaceReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val name = i.getStringExtra("place") ?: return
        val entering = i.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)
        PlaceStore.logVisit(c, name, entering)
        // standing orders can fire on places too: "when I get to the office, …"
        com.nizam.app.feature.jarvis.Timeline.log(c, "place", (if (entering) "Arrived at " else "Left ") + name)
        com.nizam.app.feature.jarvis.StandingOrders.onEvent(c, "place", name, if (entering) "arrive" else "leave",
            (if (entering) "arrived at " else "left ") + name)
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun PlacesTab() {
    val c = LocalContext.current
    var places by remember { mutableStateOf(PlaceStore.all(c)) }
    var name by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    val fine = ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val background = Build.VERSION.SDK_INT < 29 ||
        ContextCompat.checkSelfPermission(c, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    val askFine = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val lm = c.getSystemService(LocationManager::class.java)

    Text("Save places like your gym, masjid or office. Atlas notes when you arrive and leave, so time there counts on its own.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    when {
        !fine -> Button(onClick = { askFine.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("Allow location") }
        !background -> {
            Text("To notice arrivals while Atlas is closed, set Location to “Allow all the time”.", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = {
                c.startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }) { Text("Open app settings") }
        }
    }
    if (fine) Card {
        OutlinedTextField(name, { name = it }, label = { Text("Name this place — you're standing in it") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = name.isNotBlank(), onClick = {
            val loc = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
                .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
            if (loc == null) { status = "No location fix yet — open Maps for a moment, then try again."; return@Button }
            val id = places.length()
            val pi = PendingIntent.getBroadcast(c, 900 + id, Intent(c, PlaceReceiver::class.java).putExtra("place", name.trim()),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            runCatching { lm.addProximityAlert(loc.latitude, loc.longitude, 120f, -1, pi) }
                .onSuccess {
                    places.put(JSONObject().put("name", name.trim()).put("lat", loc.latitude).put("lon", loc.longitude))
                    PlaceStore.save(c, places); places = PlaceStore.all(c); status = "Saved “${name.trim()}”."; name = ""
                }.onFailure { status = "Couldn't watch this place: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
        }) { Text("Save this place") }
    }
    status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    val today = PlaceStore.minutesToday(c)
    (0 until places.length()).map { places.getJSONObject(it) }.forEach { p ->
        Card {
            Text("📍 ${p.optString("name")}", style = MaterialTheme.typography.titleMedium)
            Text("Today: ${today[p.optString("name")] ?: 0} min", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
