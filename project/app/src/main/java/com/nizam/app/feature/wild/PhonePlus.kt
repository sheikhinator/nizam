package com.nizam.app.feature.wild

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.nfc.tech.NdefFormatable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telephony.TelephonyManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import com.nizam.app.AppContainer
import com.nizam.app.NizamApplication
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.screens.hubs.HubRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

object PhonePlus {
    val all: List<Spec> = listOf(
        ScreenSpec("triggers", "NFC, Bluetooth & Wi-Fi triggers", "Tap a sticker to open a context or run a skill", Icons.Outlined.Nfc, Hub.Tools, "nfc tag sticker bluetooth car wifi home trigger automation context key") { c, _ -> TriggersScreen(c) },
        ScreenSpec("simdata", "SIM & data manager", "Bundles, balance and expiry for Jazz, Zong, Telenor, Ufone", Icons.Outlined.SimCard, Hub.Tools, "sim data bundle balance jazz zong telenor ufone mb gb expiry ussd") { c, _ -> SimScreen(c) },
        ScreenSpec("outage", "Load-shedding prep", "Before the power goes: charge, download, save battery", Icons.Outlined.PowerOff, Hub.Home, "load shedding outage power cut prepare charge download") { c, _ -> OutageScreen(c) },
        ScreenSpec("warranty", "Warranty vault", "Photograph a receipt; Atlas tracks the warranty", Icons.Outlined.VerifiedUser, Hub.Money, "warranty receipt guarantee expiry claim bill") { c, _ -> WarrantyScreen(c) },
        ScreenSpec("callscreen", "Call screening", "Unknown callers silenced and explained", Icons.Outlined.PhoneCallback, Hub.Social, "call screening spam unknown caller block silence") { _, _ -> CallScreenScreen() },
        ScreenSpec("watch", "Watch companion", "Ask Atlas and see prayers from any smartwatch", Icons.Outlined.Watch, Hub.Tools, "watch wear os smartwatch wrist companion") { _, _ -> WatchScreen() },
    )
}

private fun Context.activity(): Activity? { var x: Context? = this; while (x is ContextWrapper && x !is Activity) x = x.baseContext; return x as? Activity }

/** Runs a command the same way everywhere: a saved skill by name, otherwise the agent (risky parts go to the approval inbox). */
object CommandRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    fun run(c: Context, label: String, cmd: String, notify: Boolean = true) {
        val container = (c.applicationContext as? NizamApplication)?.container ?: return
        scope.launch {
            val sk = com.nizam.app.feature.agent.SkillsPro.find(c, cmd)
            val msg = if (sk != null) com.nizam.app.feature.agent.SkillsPro.runBackground(container, sk, emptyMap(), 0, label, "ask").log.lastOrNull().orEmpty()
                else runCatching { container.agentLoop.run(cmd, useWeb = false, autoApprove = false).let { o ->
                    o.pendingApproval.forEach { (n, a) -> com.nizam.app.feature.agent.ApprovalInbox.add(c, label, n, a,
                        com.nizam.app.feature.agent.Reviewer.Verdict("medium", "ask", "From a trigger: $label"), cmd) }
                    o.reply } }.getOrElse { com.nizam.app.ui.components.Feedback.friendly(it) }
            if (notify) runCatching { com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 5000 + (label.hashCode() % 50),
                "⚡ $label", msg.take(200)) }
        }
    }
}

// ─────────────────────────────── Triggers: NFC, Bluetooth, Wi-Fi ───────────────────────────────
object Triggers {
    fun store(c: Context) = JsonStore(c, "phone_triggers")
    fun fire(c: Context, kind: String, match: String, event: String) {
        store(c).all().filter { it.optString("kind") == kind && it.optString("event", "connect") == event &&
            (it.optString("match").equals(match, true) || (kind != "nfc" && match.contains(it.optString("match"), true) && it.optString("match").isNotBlank())) }
            .forEach { t -> store(c).update(t.optString("id")) { it.put("last", System.currentTimeMillis()) }
                if (t.optString("action") == "context") openContext(c, t.optString("route"))
                else CommandRunner.run(c, when (kind) { "nfc" -> "NFC tag ${t.optString("match")}"; "bt" -> "${t.optString("match")} ${event}ed"; else -> "Wi-Fi ${t.optString("match")}" }, t.optString("command")) }
    }
    /** Open a chosen Atlas destination when navigation is already available, or launch Atlas onto it. */
    fun openContext(c: Context, route: String) {
        if (route.isBlank()) return
        val navigate = com.nizam.app.ui.screens.search.AtlasNav.go
        if (navigate != null) {
            runCatching { navigate(route) }
        } else {
            runCatching {
                c.startActivity(Intent(c, com.nizam.app.MainActivity::class.java)
                    .putExtra("route", route)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            }
        }
    }
    /** Called by the background worker: notices joining or leaving a Wi-Fi network. */
    fun wifiTick(c: Context) {
        val ssid = runCatching { @Suppress("DEPRECATION") c.applicationContext.getSystemService(WifiManager::class.java).connectionInfo.ssid.trim('"') }
            .getOrNull()?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: ""
        val p = c.getSharedPreferences("phone_triggers", 0); val last = p.getString("ssid", "") ?: ""
        if (ssid == last) return
        p.edit().putString("ssid", ssid).apply()
        if (last.isNotBlank()) fire(c, "wifi", last, "disconnect")
        if (ssid.isNotBlank()) fire(c, "wifi", ssid, "connect")
    }
    /** The tag URI Atlas writes: tapping the phone on it opens Atlas and runs the bound command. */
    fun tagUri(name: String) = "atlas://tag/" + Uri.encode(name)
    fun write(tag: Tag, name: String): String {
        val msg = NdefMessage(arrayOf(NdefRecord.createUri(tagUri(name))))
        Ndef.get(tag)?.let { n -> n.connect(); try { if (!n.isWritable) return "That tag is read-only."; n.writeNdefMessage(msg); return "Tag written ✓" } finally { runCatching { n.close() } } }
        NdefFormatable.get(tag)?.let { f -> f.connect(); try { f.format(msg); return "Tag formatted and written ✓" } finally { runCatching { f.close() } } }
        return "This tag type isn't supported — use an NTAG213/215 sticker."
    }
}

/** Bluetooth connections (your car, earbuds, speaker) arrive here even when Atlas is closed. */
class TriggerReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val dev: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            else @Suppress("DEPRECATION") i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
        val name = runCatching { if (Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)
            dev?.name else null }.getOrNull() ?: return
        when (i.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> Triggers.fire(c, "bt", name, "connect")
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> Triggers.fire(c, "bt", name, "disconnect")
        }
    }
}

@Composable
fun TriggersScreen(container: AppContainer) {
    val c = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val list = remember(tick) { Triggers.store(c).all() }
    var kind by remember { mutableStateOf("nfc") }; var match by remember { mutableStateOf("") }; var event by remember { mutableStateOf("connect") }; var cmd by remember { mutableStateOf("") }
    var actionMode by remember { mutableStateOf("command") }
    var contextQuery by remember { mutableStateOf("") }
    var contextRoute by remember { mutableStateOf("") }
    var writing by remember { mutableStateOf<String?>(null) }
    val nfc = remember { NfcAdapter.getDefaultAdapter(c) }
    val customModules by container.dynamicRepository.allSpecs().collectAsState(initial = emptyList())
    val contextDestinations = remember(customModules) {
        (HubRegistry.all.map { it.route to it.title } +
            customModules.filter { it.enabled }.map { com.nizam.app.ui.nav.Routes.dynamic(it.key) to it.name })
            .distinctBy { it.first }
            .filter { it.first.isNotBlank() && !it.first.contains("{") }
            .sortedBy { it.second.lowercase() }
    }
    val contextMatches = remember(contextDestinations, contextQuery) {
        if (contextQuery.isBlank()) contextDestinations.take(8)
        else contextDestinations.filter { (route, title) ->
            title.contains(contextQuery, ignoreCase = true) || route.contains(contextQuery, ignoreCase = true)
        }.take(8)
    }
    val btPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val paired = remember(tick) { runCatching { if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) emptyList()
        else c.getSystemService(android.bluetooth.BluetoothManager::class.java)?.adapter?.bondedDevices?.mapNotNull { it.name }.orEmpty() }.getOrDefault(emptyList()) }
    DisposableEffect(writing) {
        val act = c.activity(); val name = writing
        if (act != null && nfc != null && name != null) nfc.enableReaderMode(act, { tag ->
            val r = runCatching { Triggers.write(tag, name) }.getOrElse { "Couldn't write: ${it.message}" }
            Handler(Looper.getMainLooper()).post { Feedback.show(r); writing = null }
        }, NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or NfcAdapter.FLAG_READER_NFC_V, null)
        onDispose { if (act != null) runCatching { nfc?.disableReaderMode(act) } }
    }
    AtlasScreen(title = "Triggers", subtitle = "Things in the real world that make Atlas act", hub = Hub.Tools) {
        item("k") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("nfc" to "📇 NFC tag", "bt" to "🔵 Bluetooth", "wifi" to "📶 Wi-Fi").forEach { (k, l) -> Capsule(l, kind == k) {
                kind = k; match = ""; if (k != "nfc") actionMode = "command"
            } } } }
        item("f") { Column(Modifier.padding(Space.page)) {
            when (kind) {
                "nfc" -> { OutlinedTextField(match, { match = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Tag name (e.g. front door, bedside, car)") })
                    if (nfc == null) Text("This phone has no NFC.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                "bt" -> { if (Build.VERSION.SDK_INT >= 31 && paired.isEmpty()) OutlinedButton(onClick = { btPerm.launch(Manifest.permission.BLUETOOTH_CONNECT) }) { Text("Allow Bluetooth device names") }
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { paired.forEach { n -> Capsule(n, match == n) { match = n } } }
                    OutlinedTextField(match, { match = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Device name") }) }
                else -> OutlinedTextField(match, { match = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Wi-Fi name (checked every ~15 min)") })
            }
            if (kind != "nfc") Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Capsule("When it connects", event == "connect") { event = "connect" }; Capsule("When it disconnects", event == "disconnect") { event = "disconnect" } }
            if (kind == "nfc") {
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Capsule("Run a skill or command", actionMode == "command") {
                        actionMode = "command"; contextRoute = ""; contextQuery = ""
                    }
                    Capsule("Open a context", actionMode == "context") {
                        actionMode = "context"; cmd = ""; contextQuery = ""; contextRoute = ""
                    }
                }
            }
            if (kind == "nfc" && actionMode == "context") {
                OutlinedTextField(contextQuery, { contextQuery = it; contextRoute = "" },
                    Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Find an Atlas screen") },
                    placeholder = { Text("Search by screen or module name") }, singleLine = true)
                if (contextRoute.isNotBlank()) {
                    val selectedTitle = contextDestinations.firstOrNull { it.first == contextRoute }?.second ?: contextRoute
                    Text("Selected context · $selectedTitle", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
                }
                contextMatches.forEach { (route, title) ->
                    OutlinedButton(onClick = { contextRoute = route; contextQuery = title },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Text(title)
                    }
                }
                if (contextMatches.isEmpty()) Text("No matching screen. Try a shorter search.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                OutlinedTextField(cmd, { cmd = it }, Modifier.fillMaxWidth().padding(top = 6.dp), label = { Text("Then do (a skill name or any command)") },
                    placeholder = { Text(if (kind == "bt") "start driving mode: read my messages aloud and open Google Maps home" else "turn on focus for 30 minutes") })
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val contextAction = kind == "nfc" && actionMode == "context"
                Button(onClick = {
                    val trigger = JSONObject().put("kind", kind).put("match", match.trim())
                        .put("event", if (kind == "nfc") "connect" else event)
                        .put("action", if (contextAction) "context" else "command")
                        .put("command", if (contextAction) "" else cmd.trim())
                    if (contextAction) trigger.put("route", contextRoute)
                    Triggers.store(c).add(trigger)
                    tick++; Feedback.show("Trigger saved"); if (kind == "nfc") writing = match.trim()
                    cmd = ""; contextRoute = ""; contextQuery = ""
                }, enabled = match.isNotBlank() && (if (contextAction) contextRoute.isNotBlank() else cmd.isNotBlank()) &&
                    (kind != "nfc" || nfc != null)) {
                    Text(if (kind == "nfc") "Save & write tag" else "Save") }
            }
            if (writing != null) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp); Text("  Hold a blank NFC sticker to the back of the phone…")
                Text("  Cancel", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp).clickable { writing = null }, style = MaterialTheme.typography.labelLarge) }
        } }
        list.forEach { t -> item(t.optString("id")) { ListRow("${when (t.optString("kind")) { "nfc" -> "📇"; "bt" -> "🔵"; else -> "📶" }} ${t.optString("match")}" +
            if (t.optString("kind") != "nfc") " · ${t.optString("event")}" else "",
            supporting = if (t.optString("action") == "context") "Open context · " +
                (HubRegistry.byRoute(t.optString("route"))?.title
                    ?: customModules.firstOrNull { com.nizam.app.ui.nav.Routes.dynamic(it.key) == t.optString("route") }?.name
                    ?: t.optString("route")) else t.optString("command"),
            icon = Icons.Outlined.Bolt, trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (t.optString("kind") == "nfc") Capsule("Rewrite", false) { writing = t.optString("match") }
                Capsule("Run", false) {
                    if (t.optString("action") == "context") Triggers.openContext(c, t.optString("route"))
                    else CommandRunner.run(c, t.optString("match"), t.optString("command"))
                }
                Capsule("✕", false) { Triggers.store(c).remove(t.optString("id")); tick++ }
            } }) } }
    }
}

// ─────────────────────────────── SIM & data ───────────────────────────────
object SimData {
    fun store(c: Context) = JsonStore(c, "sim_packages")
    private val senders = listOf("jazz", "zong", "telenor", "ufone", "warid", "onic", "4444", "310", "6000", "7000", "8558", "345")

    /** Reads recent operator texts and pulls out bundles, remaining data and expiry dates. */
    suspend fun scan(c: Context, container: AppContainer): Int {
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) throw IllegalStateException("Atlas needs SMS permission to read operator messages")
        val texts = mutableListOf<String>()
        c.contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"), null, null, "date DESC")?.use { cur ->
            while (cur.moveToNext() && texts.size < 25) {
                val a = cur.getString(0).orEmpty().lowercase(); if (senders.any { a.contains(it) }) texts += "[${java.time.Instant.ofEpochMilli(cur.getLong(2)).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}] ${cur.getString(1)}"
            }
        }
        if (texts.isEmpty()) return 0
        val raw = Ai.generate(container.settingsRepository, texts.joinToString("\n"),
            "From these Pakistani mobile operator texts, list the user's CURRENT bundles. Return ONLY JSON [{\"operator\":\"\",\"name\":\"bundle name\",\"remaining\":\"e.g. 3.2 GB / 450 mins\",\"expires\":\"YYYY-MM-DD or empty\"}]. " +
                "Only the latest state of each bundle; skip promos.", 800)
        val a = JSONArray(com.nizam.app.net.Ai.cleanJson(raw))
        store(c).save((0 until a.length()).map { a.getJSONObject(it).put("from", "sms") } + store(c).all().filter { it.optString("from") != "sms" })
        return a.length()
    }

    fun checkExpiry(c: Context) {
        val tomorrow = LocalDate.now().plusDays(1).toString()
        store(c).all().filter { it.optString("expires") == tomorrow && !it.optBoolean("warned") }.forEach { o ->
            store(c).update(o.optString("id")) { it.put("warned", true) }
            runCatching { com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 5100,
                "📶 ${o.optString("operator")} bundle ends tomorrow", "${o.optString("name")} · ${o.optString("remaining")} left") }
        }
    }
}

@Composable
fun SimScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val list = remember(tick) { SimData.store(c).all() }
    var busy by remember { mutableStateOf(false) }
    var ussd by remember { mutableStateOf(c.getSharedPreferences("sim", 0).getString("ussd", "*111#") ?: "*111#") }
    var reply by remember { mutableStateOf("") }
    val sms = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    AtlasScreen(title = "SIM & data", subtitle = "Know what's left before it runs out", hub = Hub.Tools) {
        item("b") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (ContextCompat.checkSelfPermission(c, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) sms.launch(Manifest.permission.READ_SMS)
                else { busy = true; scope.launch { runCatching { SimData.scan(c, container) }.onSuccess { Feedback.show("Found $it bundles in your texts") }.onFailure { Feedback.failed("Reading", it) }; busy = false; tick++ } }
            }, enabled = !busy) { Text(if (busy) "Reading texts…" else "Read my operator texts") }
        } }
        item("u") { Column(Modifier.padding(Space.page)) {
            Text("Check by USSD", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(ussd, { ussd = it; c.getSharedPreferences("sim", 0).edit().putString("ussd", it).apply() }, Modifier.weight(1f), singleLine = true, label = { Text("Your operator's balance code") })
                Button(onClick = {
                    if (ContextCompat.checkSelfPermission(c, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) { Feedback.show("Allow phone calls for Atlas to dial USSD"); return@Button }
                    reply = "Dialling…"
                    runCatching { c.getSystemService(TelephonyManager::class.java).sendUssdRequest(ussd, object : TelephonyManager.UssdResponseCallback() {
                        override fun onReceiveUssdResponse(t: TelephonyManager, req: String, res: CharSequence) { reply = res.toString() }
                        override fun onReceiveUssdResponseFailed(t: TelephonyManager, req: String, code: Int) { reply = "The network didn't answer (code $code)." }
                    }, Handler(Looper.getMainLooper())) }.onFailure { reply = Feedback.friendly(it) }
                }, modifier = Modifier.padding(start = 8.dp)) { Text("Check") }
            }
            if (reply.isNotBlank()) Text(reply, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            Text("Codes differ by operator and change often — check your operator's site for the current one.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        if (list.isEmpty()) item("e") { Text("No bundles yet.", modifier = Modifier.padding(Space.page)) }
        list.forEach { o -> item(o.optString("id")) { ListRow("${o.optString("operator")} · ${o.optString("name")}", supporting = listOf(o.optString("remaining"),
            o.optString("expires").takeIf { it.isNotBlank() }?.let { "ends $it" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "), icon = Icons.Outlined.SimCard) } }
    }
}

// ─────────────────────────────── Load-shedding prep ───────────────────────────────
object Outage {
    fun devices(c: Context): List<String> = c.getSharedPreferences("outage", 0).getString("devices", "Phone,Power bank,Laptop,UPS")!!.split(',').filter { it.isNotBlank() }
    fun setDevices(c: Context, l: List<String>) = c.getSharedPreferences("outage", 0).edit().putString("devices", l.joinToString(",")).apply()

    /** An hour before a scheduled cut: remind to charge, and pre-download the latest episodes of your shows. */
    suspend fun check(c: Context) {
        val next = com.nizam.app.feature.next.LoadShedding.next(c) ?: return
        val mins = java.time.Duration.between(java.time.LocalTime.now(), next.first).toMinutes()
        val key = "${LocalDate.now()}-${next.first}"
        val p = c.getSharedPreferences("outage", 0)
        if (mins !in 1..60 || p.getString("done", "") == key) return
        p.edit().putString("done", key).apply()
        val battery = c.getSystemService(android.os.BatteryManager::class.java)?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        runCatching { com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 5200,
            "🔌 Power cut in $mins min (${next.first}–${next.second})", "Phone at $battery%. Charge: ${devices(c).joinToString()}. Downloading your latest podcast episodes now.") }
        prefetch(c)
    }

    suspend fun prefetch(c: Context): Int {
        val store = com.nizam.app.feature.podcasts.PodcastStore(c)
        com.nizam.app.feature.podcasts.PodcastCache.init(c)
        var n = 0
        com.nizam.app.feature.podcasts.Rss.loadMany(store.shows().map { it.feed }).forEach { (_, eps) ->
            eps.firstOrNull()?.let { e -> if (store.downloaded(e) == null && n < 4) { runCatching { store.download(e); n++ } } }
        }
        return n
    }
}

@Composable
fun OutageScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var devices by remember { mutableStateOf(Outage.devices(c).joinToString(", ")) }
    val next = remember { com.nizam.app.feature.next.LoadShedding.next(c) }
    var busy by remember { mutableStateOf(false) }
    AtlasScreen(title = "Load-shedding prep", subtitle = next?.let { "Next cut ${it.first}–${it.second}" } ?: "Set your schedule in Load-shedding", hub = Hub.Home) {
        item("a") { Column(Modifier.padding(horizontal = Space.page)) {
            Text("An hour before each cut, Atlas reminds you what to charge and downloads the newest episodes of your podcasts so you have something to listen to.",
                style = MaterialTheme.typography.bodyMedium)
            OutlinedTextField(devices, { devices = it; Outage.setDevices(c, it.split(',').map { d -> d.trim() }) }, Modifier.fillMaxWidth().padding(top = 8.dp), label = { Text("Things to charge") })
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { busy = true; scope.launch { val n = runCatching { Outage.prefetch(c) }.getOrDefault(0); Feedback.show("Downloaded $n episodes"); busy = false } }, enabled = !busy) {
                    Text(if (busy) "Downloading…" else "Prepare now") }
                OutlinedButton(onClick = { runCatching { c.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Battery saver") }
            }
        } }
    }
}

// ─────────────────────────────── Warranty vault ───────────────────────────────
object Warranty {
    fun store(c: Context) = JsonStore(c, "warranties")
    fun check(c: Context) {
        val soon = LocalDate.now().plusDays(30).toString()
        store(c).all().filter { it.optString("until").isNotBlank() && it.optString("until") <= soon && it.optString("until") >= LocalDate.now().toString() && !it.optBoolean("warned") }.forEach { o ->
            store(c).update(o.optString("id")) { it.put("warned", true) }
            runCatching { com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 5300,
                "🧾 Warranty ending: ${o.optString("item").take(40)}", "Covered until ${o.optString("until")} — claim anything wrong before then (${o.optString("shop")})") }
        }
    }
}

@Composable
fun WarrantyScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val list = remember(tick) { Warranty.store(c).all().sortedBy { it.optString("until") } }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { u ->
        u ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            runCatching {
                val file = File(c.filesDir, "warranty").apply { mkdirs() }.let { File(it, "r${System.currentTimeMillis()}.jpg") }
                c.contentResolver.openInputStream(u)!!.use { i -> file.outputStream().use { i.copyTo(it) } }
                val text = com.google.mlkit.vision.text.TextRecognition.getClient(com.google.mlkit.vision.text.latin.TextRecognizerOptions.DEFAULT_OPTIONS)
                    .process(com.google.mlkit.vision.common.InputImage.fromFilePath(c, u)).await().text
                val raw = Ai.generate(container.settingsRepository, text.take(5000),
                    "This is a receipt or warranty card. Return ONLY JSON {\"item\":\"\",\"shop\":\"\",\"price\":0,\"bought\":\"YYYY-MM-DD\",\"months\":12}. " +
                        "months = warranty length; if not printed, use the usual warranty for that product in Pakistan and say so in \"note\".", 400)
                val o = JSONObject(com.nizam.app.net.Ai.cleanJson(raw))
                val until = runCatching { LocalDate.parse(o.optString("bought")).plusMonths(o.optLong("months", 12)).toString() }.getOrDefault("")
                Warranty.store(c).add(o.put("until", until).put("photo", file.absolutePath))
            }.onSuccess { Feedback.show("Saved — Atlas will remind you a month before it ends") }.onFailure { Feedback.failed("Reading the receipt", it) }
            busy = false; tick++
        }
    }
    AtlasScreen(title = "Warranty vault", subtitle = "${list.size} things covered", hub = Hub.Money) {
        item("b") { Row(Modifier.padding(horizontal = Space.page)) { Button(onClick = { pick.launch("image/*") }, enabled = !busy) { Text(if (busy) "Reading…" else "Add a receipt photo") } } }
        list.forEach { o -> item(o.optString("id")) { Group(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) {
            val left = runCatching { java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), LocalDate.parse(o.optString("until"))) }.getOrNull()
            Text(o.optString("item"), style = MaterialTheme.typography.titleSmall)
            Text("${o.optString("shop")} · bought ${o.optString("bought")} · Rs %,d".format(o.optDouble("price").toLong()), style = MaterialTheme.typography.bodySmall)
            Text(when { left == null -> "Warranty date unknown"; left < 0 -> "Warranty ended ${o.optString("until")}"; else -> "Covered for $left more days (until ${o.optString("until")})" },
                color = when { left == null -> MaterialTheme.colorScheme.onSurfaceVariant; left < 0 -> MaterialTheme.colorScheme.error; left < 30 -> Color(0xFFF59E0B); else -> Color(0xFF16A34A) },
                style = MaterialTheme.typography.labelLarge)
            if (o.optString("note").isNotBlank()) Text(o.optString("note"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Capsule("Photo", false) { runCatching { val f = File(o.optString("photo")); com.nizam.app.feature.nx.Share.file(c, f, "image/jpeg") } }
                Capsule("✕", false) { Warranty.store(c).remove(o.optString("id")); tick++ }
            }
        } } }
    }
}

// ─────────────────────────────── Call screening ───────────────────────────────
/**
 * Android lets a call-screening app see each incoming call before it rings. Contacts ring normally; numbers you blocked
 * are rejected; unknown numbers (optionally) ring silently while Atlas tells you who it might be.
 */
class AtlasCallScreening : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        val number = details.handle?.schemeSpecificPart.orEmpty()
        val p = getSharedPreferences("callscreen", 0)
        val known = runCatching { contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { it.moveToFirst() } ?: false }.getOrDefault(false)
        val blocked = (p.getStringSet("blocked", emptySet()) ?: emptySet()).any { number.endsWith(it.takeLast(10)) }
        val r = CallResponse.Builder()
        when {
            blocked -> r.setDisallowCall(true).setRejectCall(true).setSkipCallLog(false).setSkipNotification(true)
            !known && p.getBoolean("silence", true) && number.isNotBlank() -> {
                if (Build.VERSION.SDK_INT >= 29) r.setSilenceCall(true)
                val hint = when { number.startsWith("+92") || number.startsWith("03") -> "Pakistani mobile"; number.startsWith("042") || number.startsWith("021") || number.startsWith("051") -> "Landline"
                    number.startsWith("+") -> "International — be careful"; else -> "Unknown" }
                runCatching { com.nizam.app.feature.notify.Notifications.post(this, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 5400,
                    "🔕 Unknown caller: $number", "$hint. Ringing silently — answer from the call screen if you want. Truecaller: truecaller.com/search/pk/${number.filter { it.isDigit() }}") }
            }
        }
        respondToCall(details, r.build())
    }
}

@Composable
fun CallScreenScreen() {
    val c = LocalContext.current
    val p = remember { c.getSharedPreferences("callscreen", 0) }
    var silence by remember { mutableStateOf(p.getBoolean("silence", true)) }
    var block by remember { mutableStateOf("") }
    var tick by remember { mutableIntStateOf(0) }
    val blocked = remember(tick) { (p.getStringSet("blocked", emptySet()) ?: emptySet()).toList() }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    val held = remember(tick) { Build.VERSION.SDK_INT >= 29 && c.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_CALL_SCREENING) == true }
    AtlasScreen(title = "Call screening", subtitle = if (held) "Atlas screens your calls" else "Make Atlas your call screener", hub = Hub.Social) {
        item("r") { Column(Modifier.padding(horizontal = Space.page)) {
            if (!held && Build.VERSION.SDK_INT >= 29) Button(onClick = { runCatching { role.launch(c.getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING)) } }) {
                Text("Let Atlas screen calls") }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Switch(silence, { silence = it; p.edit().putBoolean("silence", it).apply() }); Text("  Ring silently for numbers not in my contacts") }
            Text("Contacts always ring. Android doesn't let apps talk to callers for you, so Atlas silences and explains instead.", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        item("b") { Column(Modifier.padding(Space.page)) {
            Text("Blocked numbers", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(block, { block = it }, Modifier.weight(1f), singleLine = true, label = { Text("Number") })
                Button(onClick = { p.edit().putStringSet("blocked", blocked.toSet() + block.filter { it.isDigit() }).apply(); block = ""; tick++ }, enabled = block.count { it.isDigit() } >= 7,
                    modifier = Modifier.padding(start = 8.dp)) { Text("Block") }
            }
            blocked.forEach { n -> Row(verticalAlignment = Alignment.CenterVertically) { Text(n, modifier = Modifier.weight(1f)); Capsule("Unblock", false) {
                p.edit().putStringSet("blocked", blocked.toSet() - n).apply(); tick++ } } }
        } }
    }
}

// ─────────────────────────────── Watch companion ───────────────────────────────
/** Any smartwatch that mirrors phone notifications gets an Atlas card: next prayer, and a reply box to ask Atlas anything. */
object WatchBridge {
    const val CHANNEL = "atlas_watch"
    const val KEY = "atlas_ask"
    fun on(c: Context) = c.getSharedPreferences("watch", 0).getBoolean("on", false)
    fun setOn(c: Context, v: Boolean) { c.getSharedPreferences("watch", 0).edit().putBoolean("on", v).apply(); if (v) post(c, null) else c.getSystemService(NotificationManager::class.java).cancel(5500) }

    fun post(c: Context, answer: String?) {
        if (!on(c)) return
        val nm = c.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Watch companion", NotificationManager.IMPORTANCE_LOW))
        val reply = PendingIntent.getBroadcast(c, 5501, Intent(c, WatchReceiver::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
        val action = NotificationCompat.Action.Builder(android.R.drawable.ic_btn_speak_now, "Ask Atlas", reply)
            .addRemoteInput(RemoteInput.Builder(KEY).setLabel("Ask Atlas").build()).setAllowGeneratedReplies(true).build()
        val nextPrayer = com.nizam.app.ui.design.Island.nextPrayer?.let { "${it.first} in ${if (it.second >= 60) "${it.second / 60}h ${it.second % 60}m" else "${it.second} min"}" } ?: "Atlas"
        val n = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle(nextPrayer)
            .setContentText(answer ?: "Reply to ask Atlas anything").setStyle(NotificationCompat.BigTextStyle().bigText(answer ?: "Reply to ask Atlas anything"))
            .setOngoing(answer == null).setOnlyAlertOnce(answer == null).addAction(action).build()
        nm.notify(5500, n)
    }
}

class WatchReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val q = RemoteInput.getResultsFromIntent(i)?.getCharSequence(WatchBridge.KEY)?.toString() ?: return
        val container = (c.applicationContext as? NizamApplication)?.container ?: return
        val done = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val out = runCatching { container.agentLoop.run(q, useWeb = false, autoApprove = false) }.getOrNull()
                out?.pendingApproval?.forEach { (n, a) -> com.nizam.app.feature.agent.ApprovalInbox.add(c, "Watch", n, a,
                    com.nizam.app.feature.agent.Reviewer.Verdict("medium", "ask", "Asked from the watch"), q) }
                WatchBridge.post(c, out?.reply?.take(500) ?: "Sorry — that didn't work.")
            } finally { done.finish() }
        }
    }
}

@Composable
fun WatchScreen() {
    val c = LocalContext.current
    var on by remember { mutableStateOf(WatchBridge.on(c)) }
    AtlasScreen(title = "Watch companion", subtitle = "Works with any watch that shows phone notifications", hub = Hub.Tools) {
        item("a") { Column(Modifier.padding(horizontal = Space.page)) {
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(on, { on = it; WatchBridge.setOn(c, it) }); Text("  Show the Atlas card on my watch") }
            Text("The card shows your next prayer. Reply to it by voice or keyboard on the watch and Atlas answers right there. Risky actions wait in the approval inbox.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        } }
    }
}
