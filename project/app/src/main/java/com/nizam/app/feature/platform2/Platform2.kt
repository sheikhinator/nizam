package com.nizam.app.feature.platform2

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.nizam.app.AppContainer
import com.nizam.app.feature.dynamic.ModuleTemplateExchange
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.feature.nx.PlanEngine
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Share
import com.nizam.app.ui.design.GoalRibbon
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.InsightLine
import com.nizam.app.ui.design.Panel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Moves your logs between your own devices as one passphrase-encrypted file. Nothing goes through a server. */
object SyncBundle {
    private const val MAGIC = "ATLASSYNC1"
    private fun key(pass: String, salt: ByteArray) = SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pass.toCharArray(), salt, 120_000, 256)).encoded, "AES")

    /** Plain (non-sealed) saved logs and trackers only: sealed ones are tied to this phone's secure hardware key. */
    fun stores(c: Context): List<String> = c.filesDir.listFiles().orEmpty().map { it.name }.filter { (it.startsWith("nxlog_") || it.startsWith("nx_")) && it.endsWith(".json") }.map { it.removeSuffix(".json") }

    fun export(c: Context, pass: String): File {
        require(pass.length >= 6) { "Use a passphrase of at least 6 characters." }
        val o = JSONObject(); var n = 0
        stores(c).forEach { name -> val s = JsonStore(c, name); val l = s.all(); if (s.lastReadError == null && l.isNotEmpty()) { o.put(name, JSONArray(l)); n++ } }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }; val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val ct = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(pass, salt), GCMParameterSpec(128, iv)) }.doFinal(o.toString().toByteArray())
        val f = File(c.cacheDir, "share").apply { mkdirs() }.let { File(it, "atlas-sync-${LocalDate.now()}.atlassync") }
        f.writeBytes(MAGIC.toByteArray() + salt + iv + ct); return f
    }

    /** Adds records the other device has that this one lacks (matched by id); never deletes or overwrites. Returns records added. */
    fun import(c: Context, uri: Uri, pass: String): Int {
        val raw = c.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Couldn't open the file.")
        require(raw.size > MAGIC.length + 28 && String(raw, 0, MAGIC.length) == MAGIC) { "That isn't an Atlas sync file." }
        val salt = raw.copyOfRange(MAGIC.length, MAGIC.length + 16); val iv = raw.copyOfRange(MAGIC.length + 16, MAGIC.length + 28)
        val plain = runCatching { Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(pass, salt), GCMParameterSpec(128, iv)) }.doFinal(raw, MAGIC.length + 28, raw.size - MAGIC.length - 28) }.getOrElse { error("Wrong passphrase or damaged file.") }
        val o = JSONObject(String(plain)); var added = 0
        o.keys().forEach { name ->
            if (!name.matches(Regex("[A-Za-z0-9_-]{1,64}")) || !(name.startsWith("nxlog_") || name.startsWith("nx_"))) return@forEach
            val s = JsonStore(c, name); if (s.lastReadError != null) return@forEach
            val have = s.all().map { it.optString("id") }.toSet(); val a = o.getJSONArray(name)
            val fresh = (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("id") !in have }
            if (fresh.isNotEmpty()) { s.save(s.all() + fresh); added += fresh.size }
        }
        return added
    }
}

/** Checks your GitHub releases for a newer Atlas build and installs it. Stable waits 24 hours after a build; Beta takes it at once. */
object Updater {
    data class Info(val tag: String, val published: Long, val url: String, val notes: String, val size: Long)
    private const val API = "https://api.github.com/repos/sheikhinator/nizam/releases?per_page=6"
    fun installedAt(c: Context): Long = runCatching { c.packageManager.getPackageInfo(c.packageName, 0).lastUpdateTime }.getOrDefault(0L)

    suspend fun latest(c: Context): Info? = withContext(Dispatchers.IO) {
        val beta = Labs.on(c, "beta_updates")
        // with a GitHub token saved in Chief, this also works if the repository is private
        val token = com.nizam.app.feature.chief.ChiefStore.githubToken(c)
        val arr = JSONArray(com.nizam.app.net.Http.getJson(API, mapOf("Accept" to "application/vnd.github+json") + (if (token.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token"))))
        (0 until arr.length()).map { arr.getJSONObject(it) }.filter { !it.optBoolean("draft") }.mapNotNull { r ->
            val asset = r.optJSONArray("assets")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") } } ?: return@mapNotNull null
            val t = Instant.parse(r.optString("published_at")).toEpochMilli()
            if (!beta && System.currentTimeMillis() - t < 24 * 3_600_000L) null else Info(r.optString("tag_name"), t, if (token.isBlank()) asset.optString("browser_download_url") else asset.optString("url"), r.optString("body").take(400), asset.optLong("size"))
        }.maxByOrNull { it.published }
    }

    fun newer(c: Context, i: Info) = i.published > installedAt(c) + 120_000L

    suspend fun download(c: Context, i: Info, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val f = File(File(c.cacheDir, "update").apply { mkdirs() }, "Atlas.apk")
        if (i.url.contains("api.github.com")) {
            // private repository: the asset API needs the token, and the redirect to storage must not carry it (OkHttp handles that)
            val token = com.nizam.app.feature.chief.ChiefStore.githubToken(c)
            val req = okhttp3.Request.Builder().url(i.url).header("Accept", "application/octet-stream").header("Authorization", "Bearer $token").build()
            okhttp3.OkHttpClient.Builder().readTimeout(120, java.util.concurrent.TimeUnit.SECONDS).build().newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) throw IllegalStateException("GitHub ${resp.code} while downloading the update.")
                val total = resp.body!!.contentLength().takeIf { it > 0 } ?: i.size
                resp.body!!.byteStream().use { input -> f.outputStream().use { out -> val buf = ByteArray(1 shl 16); var done = 0L
                    while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; if (total > 0) progress((done * 100 / total).toInt()) } } }
            }
            return@withContext f
        }
        val conn = (URL(i.url).openConnection() as HttpURLConnection).apply { instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 60_000 }
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: i.size
        conn.inputStream.use { input -> f.outputStream().use { out -> val buf = ByteArray(1 shl 16); var done = 0L
            while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; if (total > 0) progress((done * 100 / total).toInt()) } } }
        f
    }

    fun install(c: Context, f: File) {
        if (!c.packageManager.canRequestPackageInstalls()) { c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        val uri = FileProvider.getUriForFile(c, c.packageName + ".fileprovider", f)
        c.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Daily from the worker: one quiet notification when something newer is out. */
    suspend fun tick(c: Context) {
        val p = c.getSharedPreferences("updater", 0); val today = LocalDate.now().toString()
        if (p.getString("day", "") == today) return; p.edit().putString("day", today).apply()
        val i = runCatching { latest(c) }.getOrNull() ?: return
        if (newer(c, i) && p.getString("told", "") != i.tag) { p.edit().putString("told", i.tag).apply()
            Notifications.post(c, Notifications.CHANNEL_NUDGE, 7800, "New Atlas version ready", "${i.tag} is out. Open You › Updates to install.") }
    }
}

/** Questions you ask while offline wait here and are answered when the connection returns. */
object AiQueue {
    private fun store(c: Context) = JsonStore(c, "nx_aiqueue")
    fun add(c: Context, prompt: String) = store(c).add(JSONObject().put("q", prompt).put("t", System.currentTimeMillis()).put("a", ""))
    fun all(c: Context) = store(c).all().sortedByDescending { it.optLong("t") }
    fun waiting(c: Context) = runCatching { store(c).all().count { it.optString("a").isBlank() } }.getOrDefault(0)
    suspend fun tick(c: Context, container: AppContainer) {
        if (waiting(c) == 0 || !com.nizam.app.net.Router.online(c)) return
        val s = store(c)
        s.all().filter { it.optString("a").isBlank() }.take(3).forEach { o ->
            val a = runCatching { com.nizam.app.net.Ai.generate(container.settingsRepository, o.optString("q"), null, 900) }.getOrNull() ?: return@forEach
            s.update(o.optString("id")) { it.put("a", a) }
            Notifications.post(c, Notifications.CHANNEL_NUDGE, 7810 + (o.optString("id").hashCode() and 0x3f), "Answer ready", o.optString("q").take(60))
        }
    }
}

@Composable
private fun SyncScreen() {
    val c = LocalContext.current
    var pass by remember { mutableStateOf("") }; var status by remember { mutableStateOf("") }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u: Uri? -> if (u != null) status = runCatching { "Added ${SyncBundle.import(c, u, pass)} records from the other device." }.getOrElse { "Didn't work: ${it.message}" } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Move your logs and trackers between your own phone, tablet or a second phone. Export on one, send the file to yourself (Drive, email, Bluetooth), import on the other. Both need the same passphrase. Records are only ever added, never overwritten. Sealed data (like the baby log) stays on its phone.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(pass, { pass = it }, label = { Text("Passphrase") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row {
            TextButton(enabled = pass.length >= 6, onClick = { status = runCatching { val f = SyncBundle.export(c, pass); Share.file(c, f, "application/octet-stream"); "Exported ${f.length() / 1024} KB." }.getOrElse { "Didn't work: ${it.message}" } }) { Text("Export from this phone") }
            TextButton(enabled = pass.length >= 6, onClick = { pick.launch("*/*") }) { Text("Import a file") }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun UpdatesScreen() {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<Updater.Info?>(null) }; var status by remember { mutableStateOf("Checking…") }; var pct by remember { mutableStateOf(-1) }
    fun check() = scope.launch { status = "Checking…"; val i = runCatching { Updater.latest(c) }.getOrNull(); info = i
        status = when { i == null -> "Couldn't reach GitHub, or no build is published yet."; Updater.newer(c, i) -> "A newer build is available: ${i.tag}"; else -> "You're on the latest build (${i.tag})." } }
    LaunchedEffect(Unit) { check() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Channel: ${if (Labs.on(c, "beta_updates")) "Beta (new builds at once)" else "Stable (builds after 24 hours)"}. Change it in Labs.", style = MaterialTheme.typography.bodySmall)
        Text(status, style = MaterialTheme.typography.titleMedium)
        info?.let { i -> if (i.notes.isNotBlank()) Text(i.notes, style = MaterialTheme.typography.bodySmall)
            if (Updater.newer(c, i)) Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { scope.launch { pct = 0; status = runCatching { val f = Updater.download(c, i) { pct = it }; pct = 100; Updater.install(c, f); "Downloaded. Android will ask you to confirm the install." }.getOrElse { "Download failed: ${it.message}" } } }) { Text("Download and install") }
                if (pct in 0..99) Text("$pct%") } }
        TextButton(onClick = { check() }) { Text("Check again") }
        Text("Your data is kept when you update, because the app is signed with the same key.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AppReport() {
    val c = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val groups = remember(tick) {
        fun size(f: File): Long = if (f.isFile) f.length() else f.listFiles().orEmpty().sumOf { size(it) }
        val files = c.filesDir.listFiles().orEmpty()
        fun sum(pred: (File) -> Boolean) = files.filter(pred).sumOf { size(it) }
        listOf("Logs & trackers" to sum { it.name.startsWith("nxlog_") || it.name.startsWith("nx_") }, "Podcasts & downloads" to sum { it.name == "podcasts" || it.name == "playlists" },
            "Made files (Deliverables)" to sum { it.name == "deliverables" }, "Photos & capsules" to sum { it.name == "daily" || it.name == "capsules" }, "Offline AI & libraries" to sum { it.name.contains("model", true) || it.name.contains("islam", true) || it.name.contains("library", true) },
            "Everything else in files" to sum { true } - sum { it.name.startsWith("nxlog_") || it.name.startsWith("nx_") || it.name in setOf("podcasts", "playlists", "deliverables", "daily", "capsules") || it.name.contains("model", true) || it.name.contains("islam", true) || it.name.contains("library", true) },
            "Cache" to size(c.cacheDir), "Databases" to size(c.getDatabasePath("x").parentFile ?: c.filesDir)).sortedByDescending { it.second }
    }
    val total = groups.sumOf { it.second }.coerceAtLeast(1)
    val bat = remember { c.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Atlas is using ${"%.1f".format(total / 1_048_576.0)} MB on this phone.", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        groups.forEach { (n, b) -> Column { Row { Text(n, Modifier.weight(1f)); Text("${"%.1f".format(b / 1_048_576.0)} MB", style = MaterialTheme.typography.bodySmall) }; GoalRibbon(b.toFloat() / total) } }
        TextButton(onClick = { c.cacheDir.listFiles().orEmpty().forEach { it.deleteRecursively() }; tick++ }) { Text("Clear cache") }
        bat?.let { Text("Battery ${it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)}% · ${it.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0} °C", style = MaterialTheme.typography.bodySmall) }
        InsightLine("Podcasts and made files are the usual big ones. Delete old downloads from their screens to free space.")
    }
}

private val WHATS_NEW = listOf(
    "Four tabs: Today, Ask, Life, You. Ask joins search, chat, quick commands and Atlas's powers.",
    "Modes: Atlas switches between Morning, Work, Worship, Night and more by prayer time, calendar and charger. Change it from the strip on Today.",
    "Dashboards for Today, Money, Health, Deen, Study, AI and System, with gauges, trends and heatmaps. Set sound and motion in Look & sound.",
    "Suites have an Overview and an All tools tab. Every tool is still there.",
    "New: goals, time ledger, forecasts, cause & effect, no-spend challenge, bank statement import, parcel tracker, wardrobe, insurance and many more logs.",
    "Safety: notification inbox with digests, weekly encrypted backup, safe mode after crashes, profiles, hide-amounts and guest mode.")

@Composable
private fun WhatsNew() {
    val c = LocalContext.current
    LaunchedEffect(Unit) { c.getSharedPreferences("whatsnew", 0).edit().putLong("seen", Updater.installedAt(c)).apply() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("What's new in this version", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        WHATS_NEW.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
        Text("How each suite works", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        listOf("Overview shows live figures and your most used tools; All tools lists everything, grouped. Tap 📍 on a tool to pin it to Today.", "Ask › Do understands lines like “spent 800 on lunch” and always shows what it will do before doing it.",
            "Hide a tool you never use from You › Tidy my tools; search still finds it.", "If Atlas ever crashes twice, it opens in Safe mode with a one-tap check.").forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
    }
}
fun whatsNewUnseen(c: Context) = Updater.installedAt(c) > c.getSharedPreferences("whatsnew", 0).getLong("seen", 0L) + 1000L && c.getSharedPreferences("wizard", 0).getBoolean("done", false)

@Composable
private fun CalendarSync(container: AppContainer) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var events by remember { mutableStateOf<List<PlanEngine.Block>>(emptyList()) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { events = runCatching { PlanEngine.events(c) }.getOrDefault(emptyList()) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Atlas reads today's events from your calendar. To put your tasks on the calendar, export them as a calendar file (opens in Google Calendar and others).", style = MaterialTheme.typography.bodyMedium)
        Text("Today", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        if (events.isEmpty()) Text("No events found (or calendar access isn't allowed).", style = MaterialTheme.typography.bodySmall)
        events.forEach { Text("%02d:%02d–%02d:%02d  %s".format(it.startMin / 60, it.startMin % 60, it.endMin / 60, it.endMin % 60, it.label), style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { scope.launch { status = runCatching {
            val tasks = container.taskRepository.all().first().filter { it.completedAt == null && it.due != null }
            val zone = java.time.ZoneId.systemDefault(); val fmt = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd")
            val ics = buildString { append("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Atlas//EN\r\n")
                tasks.forEach { t -> val d = Instant.ofEpochMilli(t.due!!).atZone(zone).toLocalDate()
                    append("BEGIN:VEVENT\r\nUID:atlas-${t.id}@atlas\r\nDTSTAMP:${java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").format(java.time.LocalDateTime.now(java.time.ZoneOffset.UTC))}\r\nDTSTART;VALUE=DATE:${fmt.format(d)}\r\nDTEND;VALUE=DATE:${fmt.format(d.plusDays(1))}\r\nSUMMARY:${t.title.replace(",", "\\,").replace("\n", " ")}\r\nEND:VEVENT\r\n") }
                append("END:VCALENDAR\r\n") }
            val f = File(File(c.cacheDir, "share").apply { mkdirs() }, "atlas-tasks.ics"); f.writeText(ics); Share.file(c, f, "text/calendar"); "Exported ${tasks.size} tasks." }.getOrElse { "Didn't work: ${it.message}" } } }) { Text("Export tasks to calendar (.ics)") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun AtlasApps(container: AppContainer) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var specs by remember { mutableStateOf(emptyList<com.nizam.app.feature.dynamic.ModuleSpec>()) }
    var paste by remember { mutableStateOf("") }; var status by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { specs = container.dynamicRepository.specs().first().filter { !it.builtIn } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Share a tracker you made as a single piece of text. A friend pastes it below and gets the same tracker (fields only, none of your data).", style = MaterialTheme.typography.bodyMedium)
        if (specs.isEmpty()) Text("You haven't made any custom trackers yet. Ask Atlas to build one, then share it from here.", style = MaterialTheme.typography.bodySmall)
        specs.forEach { s -> Row(verticalAlignment = Alignment.CenterVertically) { Text("${s.emoji} ${s.name}", Modifier.weight(1f)); TextButton(onClick = { Share.text(c, ModuleTemplateExchange.encode(s), "Atlas app: ${s.name}") }) { Text("Share") } } }
        OutlinedTextField(paste, { paste = it }, label = { Text("Paste an Atlas app") }, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = paste.isNotBlank(), onClick = { scope.launch { status = runCatching { val pv = ModuleTemplateExchange.parse(paste.trim()); container.dynamicRepository.addSpec(ModuleTemplateExchange.toModuleSpec(pv, 99)); paste = ""; "Added “${pv.name}”." }.getOrElse { "Couldn't import: ${it.message}" } } }) { Text("Add to my trackers") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun QueueScreen() {
    val c = LocalContext.current
    var list by remember { mutableStateOf(AiQueue.all(c)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Questions asked while offline wait here and are answered when you're back online.", style = MaterialTheme.typography.bodyMedium)
        if (list.isEmpty()) Text("Nothing queued.", style = MaterialTheme.typography.bodySmall)
        list.forEach { o -> Column(Modifier.padding(vertical = 4.dp)) { Text(o.optString("q"), fontWeight = FontWeight.SemiBold)
            Text(o.optString("a").ifBlank { "⏳ waiting for a connection" }, style = MaterialTheme.typography.bodySmall) } }
        TextButton(onClick = { JsonStore(c, "nx_aiqueue").save(emptyList()); list = emptyList() }) { Text("Clear") }
    }
}

@Composable
private fun LanguagesScreen() {
    val c = LocalContext.current
    var cur by remember { mutableStateOf(com.nizam.app.feature.future.Lang.mode(c)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("The language Atlas writes in and listens for. Mixed speech (Urdu, English and Roman Urdu together) is understood in every mode.", style = MaterialTheme.typography.bodyMedium)
        com.nizam.app.feature.future.Lang.MODES.forEach { (k, l) -> TextButton(onClick = { com.nizam.app.feature.future.Lang.set(c, k); cur = k }) { Text((if (cur == k) "✓ " else "") + l, fontWeight = if (cur == k) FontWeight.Bold else FontWeight.Normal) } }
        Text("Menus and buttons stay in English or your chosen app language; AI answers and spoken replies follow this setting.", style = MaterialTheme.typography.bodySmall)
    }
}

object Platform2Specs {
    val all = listOf(
        ScreenSpec("devicesync", "Sync between my devices", "Move logs between your phones as one encrypted file, no server", Icons.Outlined.Sync, Hub.Tools, "sync devices phone tablet laptop encrypted bundle export import merge transfer") { _, _ -> SyncScreen() },
        ScreenSpec("updates", "Updates", "Check for a new Atlas build and install it, with Stable and Beta channels", Icons.Outlined.SystemUpdate, Hub.Tools, "update upgrade new version apk install release channel beta stable") { _, _ -> UpdatesScreen() },
        ScreenSpec("appreport", "Speed, storage & battery", "What Atlas uses on this phone, by area, with a one-tap cache clear", Icons.Outlined.Speed, Hub.Tools, "storage battery speed report usage cache clear space size where") { _, _ -> AppReport() },
        ScreenSpec("whatsnew", "What's new & how it works", "A short tour of this version and each suite", Icons.Outlined.NewReleases, Hub.Tools, "whats new help tour guide how it works release notes tips") { _, _ -> WhatsNew() },
        ScreenSpec("calendarsync", "Calendar & tasks", "See today's events and export your tasks to your calendar", Icons.Outlined.CalendarMonth, Hub.Work, "calendar sync events tasks export ics google calendar") { c, _ -> CalendarSync(c) },
        ScreenSpec("atlasapps", "Atlas Apps (share trackers)", "Share a custom tracker as text, or add one a friend sent you", Icons.Outlined.Apps, Hub.Create, "atlas apps share tracker template import export module friends") { c, _ -> AtlasApps(c) },
        ScreenSpec("offlinequeue", "Offline question queue", "Questions asked offline wait here and are answered when you're back online", Icons.Outlined.CloudQueue, Hub.Tools, "offline queue waiting questions ai later no internet") { _, _ -> QueueScreen() },
        ScreenSpec("languages", "Languages", "English, Urdu, Roman Urdu, Punjabi, Sindhi, Pashto, Saraiki and Arabic for AI replies and voice", Icons.Outlined.Language, Hub.Tools, "language urdu punjabi sindhi pashto saraiki arabic roman english voice translate") { _, _ -> LanguagesScreen() },
    )
}

/** Any saved log or tracker as a spreadsheet file. */
@Composable
private fun ExportLogs() {
    val c = LocalContext.current
    val titles = remember { com.nizam.app.feature.nx.Nx.all.associate { it.id to it.title } }
    val names = remember { c.filesDir.listFiles().orEmpty().map { it.name }.filter { it.startsWith("nxlog_") && it.endsWith(".json") }.map { it.removePrefix("nxlog_").removeSuffix(".json") }.sorted() }
    var status by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Pick a log to export as a CSV file for a spreadsheet. Sealed logs (baby, insurance) are skipped because they can only be read inside Atlas.", style = MaterialTheme.typography.bodySmall)
        if (names.isEmpty()) Text("No logs saved yet.")
        names.forEach { n -> TextButton(onClick = { status = runCatching {
            val s = JsonStore(c, "nxlog_$n"); val l = s.all(); require(s.lastReadError == null && l.isNotEmpty()) { "That log is empty or sealed." }
            val cols = l.flatMap { o -> o.keys().asSequence().toList() }.distinct().filter { it != "id" }
            val csv = (listOf(cols.joinToString(",")) + l.map { o -> cols.joinToString(",") { "\"" + o.optString(it).replace("\"", "\"\"") + "\"" } }).joinToString("\n")
            val f = File(File(c.cacheDir, "share").apply { mkdirs() }, "$n.csv"); f.writeText(csv); Share.file(c, f, "text/csv"); "Exported ${l.size} rows." }.getOrElse { "Didn't work: ${it.message}" } }) { Text(titles[n] ?: n) } }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
    }
}

/** A photo from your gallery behind the Today header. Stays on this phone; a dark overlay keeps the text readable. */
object TodayPhoto {
    fun uri(c: Context): Uri? = c.getSharedPreferences("todayphoto", 0).getString("uri", null)?.let { Uri.parse(it) }
    fun set(c: Context, u: Uri?) = c.getSharedPreferences("todayphoto", 0).edit().apply { if (u == null) remove("uri") else putString("uri", u.toString()) }.apply()
}

@Composable
private fun TodayPhotoScreen() {
    val c = LocalContext.current
    var cur by remember { mutableStateOf(TodayPhoto.uri(c)) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { u: Uri? -> if (u != null) { runCatching { c.contentResolver.takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; TodayPhoto.set(c, u); cur = u } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Choose a photo for the top of Today. It stays on this phone.", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { pick.launch(arrayOf("image/*")) }) { Text(if (cur == null) "Choose a photo" else "Change photo") }
        if (cur != null) TextButton(onClick = { TodayPhoto.set(c, null); cur = null }) { Text("Remove photo") }
    }
}

object Platform3Specs {
    val all = listOf(
        ScreenSpec("exportlogs", "Export my logs (CSV)", "Any log or tracker as a spreadsheet file", Icons.Outlined.FileDownload, Hub.Tools, "export csv logs spreadsheet excel download data backup tracker") { _, _ -> ExportLogs() },
        ScreenSpec("todayphoto", "Today header photo", "Put a photo of your choice behind the top of Today", Icons.Outlined.Image, Hub.Tools, "today header photo wallpaper picture background personalise") { _, _ -> TodayPhotoScreen() },
    )
}
