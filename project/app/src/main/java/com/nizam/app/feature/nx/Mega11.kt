package com.nizam.app.feature.nx

import android.content.Context
import android.graphics.Bitmap
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Notes pinned to a place: the place is recognised by what the camera sees there (on-device labels), not by GPS. */
object PlaceNotes {
    private fun store(c: Context) = JsonStore(c, "nx_placenotes")
    fun all(c: Context) = store(c).all()
    suspend fun labels(b: Bitmap): Set<String> = runCatching {
        ImageLabeling.getClient(ImageLabelerOptions.Builder().setConfidenceThreshold(0.5f).build()).process(InputImage.fromBitmap(b, 0)).await()
            .map { it.text.lowercase() }.toSet() }.getOrDefault(emptySet())
    fun add(c: Context, place: String, note: String, labels: Set<String>) =
        store(c).add(JSONObject().put("place", place).put("note", note).put("labels", JSONArray(labels.toList())).put("at", System.currentTimeMillis()))
    fun remove(c: Context, id: String) = store(c).remove(id)
    private fun labelsOf(o: JSONObject) = o.optJSONArray("labels")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() }.orEmpty()
    /** Notes whose place shares the most labels with what the camera sees now (at least two in common, or one if the place has few). */
    fun match(c: Context, seen: Set<String>): List<Pair<JSONObject, Int>> = all(c).map { it to (labelsOf(it) intersect seen).size }
        .filter { (o, n) -> n >= minOf(2, labelsOf(o).size).coerceAtLeast(1) }.sortedByDescending { it.second }
}

/**
 * Household lists shared between family phones on the same Wi-Fi: no server, no accounts.
 * Phones find each other with network service discovery; every message is signed with the household phrase.
 */
object Household {
    private const val TYPE = "_atlashome._tcp."
    private fun store(c: Context) = JsonStore(c, "nx_household")
    private fun p(c: Context) = c.getSharedPreferences("household", 0)
    fun phrase(c: Context) = p(c).getString("phrase", "").orEmpty()
    fun setPhrase(c: Context, s: String) = p(c).edit().putString("phrase", s).apply()
    fun me(c: Context) = p(c).getString("me", android.os.Build.MODEL).orEmpty()
    fun setMe(c: Context, s: String) = p(c).edit().putString("me", s).apply()
    fun items(c: Context) = store(c).all().filter { !it.optBoolean("deleted") }

    fun add(c: Context, text: String, kind: String) = store(c).add(JSONObject().put("uid", java.util.UUID.randomUUID().toString()).put("text", text)
        .put("kind", kind).put("done", false).put("by", me(c)).put("updated", System.currentTimeMillis()))
    fun change(c: Context, uid: String, f: (JSONObject) -> Unit) = store(c).all().firstOrNull { it.optString("uid") == uid }?.let { o ->
        store(c).update(o.optString("id")) { f(it); it.put("updated", System.currentTimeMillis()).put("by", me(c)) } }

    private fun sign(c: Context, body: String): String {
        val mac = Mac.getInstance("HmacSHA256"); mac.init(SecretKeySpec(("atlas-home:" + phrase(c)).toByteArray(), "HmacSHA256"))
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun envelope(c: Context): String { val body = JSONArray(store(c).all().map { JSONObject(it.toString()).apply { remove("id") } }).toString()
        return JSONObject().put("body", body).put("mac", sign(c, body)).toString() }
    /** Merges a peer's list: newest change to each item wins. Returns how many items changed here. */
    private fun merge(c: Context, line: String): Int {
        val o = JSONObject(line); val body = o.getString("body")
        require(sign(c, body) == o.getString("mac")) { "Different household phrase" }
        val mine = store(c).all().associateBy { it.optString("uid") }
        val theirs = JSONArray(body).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        var n = 0
        theirs.forEach { t -> val m = mine[t.optString("uid")]
            if (m == null) { store(c).add(t); n++ }
            else if (t.optLong("updated") > m.optLong("updated")) { store(c).update(m.optString("id")) { x -> t.keys().forEach { k -> x.put(k, t.get(k)) } }; n++ } }
        return n
    }

    class Session(private val c: Context, private val onEvent: (String) -> Unit) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private var server: ServerSocket? = null
        private val nsd = c.getSystemService(Context.NSD_SERVICE) as NsdManager
        private var reg: NsdManager.RegistrationListener? = null
        private var disc: NsdManager.DiscoveryListener? = null
        private var loop: Job? = null

        fun start() {
            if (phrase(c).length < 6) { onEvent("Set a household phrase (6+ characters) first."); return }
            val ss = ServerSocket(0).also { server = it }
            loop = scope.launch {
                while (!ss.isClosed) runCatching { ss.accept().use { s ->
                    s.soTimeout = 8000
                    val line = s.getInputStream().bufferedReader().readLine() ?: return@use
                    val n = runCatching { merge(c, line) }.getOrElse { onEvent("A phone with a different phrase tried to sync."); return@use }
                    s.getOutputStream().bufferedWriter().apply { write(envelope(c)); newLine(); flush() }
                    if (n > 0) onEvent("Got $n changes from family")
                } }
            }
            reg = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(i: NsdServiceInfo) {}
                override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) { onEvent("Couldn't announce this phone on Wi-Fi ($e)") }
                override fun onServiceUnregistered(i: NsdServiceInfo) {}
                override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
            }
            nsd.registerService(NsdServiceInfo().apply { serviceName = "Atlas-" + me(c).take(20); serviceType = TYPE; port = ss.localPort }, NsdManager.PROTOCOL_DNS_SD, reg)
            disc = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) { onEvent("Looking for family phones on this Wi-Fi…") }
                override fun onServiceFound(i: NsdServiceInfo) {
                    if (i.serviceName == "Atlas-" + me(c).take(20)) return
                    @Suppress("DEPRECATION") nsd.resolveService(i, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(i: NsdServiceInfo, e: Int) {}
                        override fun onServiceResolved(r: NsdServiceInfo) { @Suppress("DEPRECATION") val host = r.host ?: return; syncWith(host, r.port, r.serviceName) }
                    })
                }
                override fun onServiceLost(i: NsdServiceInfo) {}
                override fun onDiscoveryStopped(t: String) {}
                override fun onStartDiscoveryFailed(t: String, e: Int) { onEvent("Discovery failed ($e)") }
                override fun onStopDiscoveryFailed(t: String, e: Int) {}
            }
            nsd.discoverServices(TYPE, NsdManager.PROTOCOL_DNS_SD, disc)
        }

        fun syncWith(host: java.net.InetAddress, port: Int, name: String) = scope.launch {
            runCatching { Socket().use { s ->
                s.connect(InetSocketAddress(host, port), 5000); s.soTimeout = 8000
                s.getOutputStream().bufferedWriter().apply { write(envelope(c)); newLine(); flush() }
                val reply = s.getInputStream().bufferedReader().readLine() ?: return@use
                val n = merge(c, reply)
                onEvent("Synced with ${name.removePrefix("Atlas-")}${if (n > 0) " · $n changes" else ""}")
            } }.onFailure { onEvent("Couldn't sync with ${name.removePrefix("Atlas-")}: ${it.message}") }
        }

        fun stop() {
            runCatching { reg?.let { nsd.unregisterService(it) } }; runCatching { disc?.let { nsd.stopServiceDiscovery(it) } }
            runCatching { server?.close() }; loop?.cancel(); scope.cancel()
        }
    }
}

object Mega11 {
    val all: List<Spec> by lazy { listOf(
        ScreenSpec("whynumbers", "Explain my numbers", "Exactly which records made today's XP, streak and level", Icons.Outlined.Calculate, Hub.Tools,
            "explain number xp score streak level where from breakdown") { c, open -> WhyNumbers(c) },
        ScreenSpec("placenotes", "Notes on places", "Point the camera at a spot to leave or read notes there", Icons.Outlined.PinDrop, Hub.Home,
            "spatial sticky notes place camera remember watered filter changed") { c, open -> PlaceNotesScreen() },
        ScreenSpec("household", "Household lists", "Chores and groceries shared with family phones on the same Wi-Fi", Icons.Outlined.Wifi, Hub.Home,
            "household mesh family chores grocery shared list wifi local no server") { c, open -> HouseholdScreen() },
    ) }
}

@Composable
private fun WhyNumbers(container: AppContainer) {
    var s by remember { mutableStateOf<com.nizam.app.feature.progress.ProgressSummary?>(null) }
    LaunchedEffect(Unit) { s = runCatching { com.nizam.app.feature.progress.Xp.summarise(container) }.getOrNull() }
    val x = com.nizam.app.feature.progress.Xp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val sum = s ?: run { Text("Adding it up…"); return@Column }
        val d = sum.today
        Text("Today's ${d.xp} XP", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        listOf(Triple("Tasks finished", d.tasks, x.TASK), Triple("Habits checked", d.habits, x.HABIT), Triple("Prayers logged", d.prayers, x.PRAYER),
            Triple("Gym sets", d.sets, x.SET), Triple("Study (per 15 min)", d.studyMinutes / 15, x.STUDY_PER_15MIN), Triple("Lessons", d.lessons, x.LESSON),
            Triple("Diary written", if (d.wroteDiary) 1 else 0, x.DIARY), Triple("Food logged", if (d.loggedFood) 1 else 0, x.FOOD_LOGGED))
            .forEach { (l, n, per) -> Row { Text(l, Modifier.weight(1f)); Text("$n × $per = ${n * per}", fontWeight = if (n > 0) FontWeight.SemiBold else FontWeight.Normal) } }
        Text("Streak ${sum.streak} days", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Text("A day counts when it earns any XP. Your last 30 days: " + sum.last30.takeLast(30).joinToString("") { if (it.active) "■" else "□" }, style = MaterialTheme.typography.bodySmall)
        if (sum.freezes > 0) Text("${sum.freezes} streak freezes saved a missed day.", style = MaterialTheme.typography.bodySmall)
        Text("Level ${sum.level} · ${sum.title}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Text("${sum.totalXp} XP in the last 90 days · ${sum.levelProgress} of ${sum.levelNeed} toward the next level", style = MaterialTheme.typography.bodySmall)
        Text("Nothing is stored separately: every number is recomputed from your tasks, habits, prayers, gym, study, diary and food logs.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PlaceNotesScreen() {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var seen by remember { mutableStateOf<Set<String>>(emptySet()) }
    var mode by remember { mutableStateOf("read") }
    var place by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var list by remember { mutableStateOf(PlaceNotes.all(c)) }
    var status by remember { mutableStateOf("") }
    val cam = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { b ->
        if (b != null) scope.launch { status = "Looking…"; seen = PlaceNotes.labels(b); status = if (seen.isEmpty()) "Couldn't make out the spot — try more light." else "" } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row { listOf("read" to "Read notes here", "leave" to "Leave a note").forEach { (k, l) ->
            TextButton(onClick = { mode = k; seen = emptySet() }) { Text(l, fontWeight = if (mode == k) FontWeight.Bold else FontWeight.Normal) } } }
        TextButton(onClick = { cam.launch(null) }) { Text("📷 Point at the spot") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        if (seen.isNotEmpty()) Text("I see: ${seen.take(8).joinToString()}", style = MaterialTheme.typography.bodySmall)
        if (mode == "leave" && seen.isNotEmpty()) {
            OutlinedTextField(place, { place = it }, label = { Text("Name this spot (e.g. balcony plants)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(note, { note = it }, label = { Text("Note (e.g. watered today)") }, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = note.isNotBlank(), onClick = { PlaceNotes.add(c, place.ifBlank { seen.first() }, note, seen); list = PlaceNotes.all(c); note = ""; status = "Pinned." }) { Text("Pin note here") }
        }
        if (mode == "read" && seen.isNotEmpty()) {
            val hits = PlaceNotes.match(c, seen)
            if (hits.isEmpty()) Text("No notes for this spot yet.", style = MaterialTheme.typography.bodyMedium)
            hits.forEach { (o, _) -> Text("📌 ${o.optString("place")}: ${o.optString("note")} · ${ago(o.optLong("at"))}", style = MaterialTheme.typography.bodyMedium) }
        }
        if (list.isNotEmpty()) Text("All notes", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        list.sortedByDescending { it.optLong("at") }.forEach { o -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${o.optString("place")}: ${o.optString("note")} · ${ago(o.optLong("at"))}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { PlaceNotes.remove(c, o.optString("id")); list = PlaceNotes.all(c) }) { Text("✕") } } }
    }
}

private fun ago(t: Long): String { val d = ((System.currentTimeMillis() - t) / 86_400_000L).toInt()
    return when (d) { 0 -> "today"; 1 -> "yesterday"; else -> "$d days ago" } }

@Composable
private fun HouseholdScreen() {
    val c = LocalContext.current
    var phrase by remember { mutableStateOf(Household.phrase(c)) }
    var me by remember { mutableStateOf(Household.me(c)) }
    var items by remember { mutableStateOf(Household.items(c)) }
    var draft by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf("Grocery") }
    var log by remember { mutableStateOf(listOf<String>()) }
    var live by remember { mutableStateOf(false) }
    val session = remember { Household.Session(c.applicationContext) { e -> log = (listOf(e) + log).take(6) } }
    DisposableEffect(Unit) { onDispose { session.stop() } }
    LaunchedEffect(live) { while (live) { kotlinx.coroutines.delay(2000); items = Household.items(c) } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Everyone at home uses the same phrase. While this screen is open on two phones on the same Wi-Fi, their lists merge — no internet or accounts.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(phrase, { phrase = it; Household.setPhrase(c, it) }, label = { Text("Household phrase") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(me, { me = it; Household.setMe(c, it) }, label = { Text("Your name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = !live && phrase.length >= 6, onClick = { session.start(); live = true }) { Text(if (live) "Syncing on this Wi-Fi" else "Start syncing") }
        log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            listOf("Grocery", "Chore").forEach { k -> TextButton(onClick = { kind = k }) { Text(k, fontWeight = if (kind == k) FontWeight.Bold else FontWeight.Normal) } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(draft, { draft = it }, label = { Text("Add ${kind.lowercase()}") }, singleLine = true, modifier = Modifier.weight(1f))
            TextButton(onClick = { if (draft.isNotBlank()) { Household.add(c, draft.trim(), kind); draft = ""; items = Household.items(c) } }) { Text("Add") }
        }
        listOf("Grocery", "Chore").forEach { k -> val l = items.filter { it.optString("kind") == k }
            if (l.isNotEmpty()) Text(if (k == "Grocery") "🛒 Groceries" else "🧹 Chores", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            l.sortedBy { it.optBoolean("done") }.forEach { o -> Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(o.optBoolean("done"), { v -> Household.change(c, o.optString("uid")) { it.put("done", v) }; items = Household.items(c) })
                Text(o.optString("text"), Modifier.weight(1f)); Text(o.optString("by"), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { Household.change(c, o.optString("uid")) { it.put("deleted", true) }; items = Household.items(c) }) { Text("✕") }
            } } }
    }
}
