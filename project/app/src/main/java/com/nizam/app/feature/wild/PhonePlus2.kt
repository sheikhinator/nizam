package com.nizam.app.feature.wild

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
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
import com.nizam.app.AppContainer
import com.nizam.app.NizamApplication
import com.nizam.app.feature.nx.Md
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.Space
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.UUID

object PhonePlus2 {
    val all: List<Spec> = listOf(
        ScreenSpec("mesh", "Mesh chat", "Message nearby Atlas phones with no internet or signal", Icons.Outlined.Hub, Hub.Social, "mesh offline chat nearby bluetooth no internet outage") { _, _ -> MeshScreen() },
        ScreenSpec("obd", "Car health", "Read your car's engine and fuel through an OBD-II dongle", Icons.Outlined.DirectionsCar, Hub.Travel, "car obd engine check light fuel rpm diagnostic elm327") { c, _ -> ObdScreen(c) },
        ScreenSpec("laptop", "Laptop dashboard", "Open Atlas in your computer's browser — plus clipboard bridge", Icons.Outlined.Laptop, Hub.Tools, "laptop computer browser dashboard web clipboard copy paste pc") { _, _ -> LaptopScreen() },
    )
}

// ─────────────────────────────── Mesh chat (Nearby Connections) ───────────────────────────────
/**
 * Phones find each other over Bluetooth and peer Wi-Fi — no router, no SIM. Every message is relayed on by each phone
 * that hears it (once), so a chain of phones can carry a message further than any one can reach.
 */
object Mesh {
    private const val SERVICE = "com.nizam.atlas.mesh"
    val peers = mutableStateMapOf<String, String>()
    val messages = mutableStateListOf<Triple<String, String, Long>>()
    var running by mutableStateOf(false)
    private val seen = HashSet<String>()
    private var name = "Atlas"
    private var appCtx: Context? = null

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(id: String, p: Payload) {
            val o = runCatching { JSONObject(String(p.asBytes() ?: return)) }.getOrNull() ?: return
            val mid = o.optString("id"); if (!seen.add(mid)) return
            messages += Triple(o.optString("from"), o.optString("text"), o.optLong("t"))
            relay(o, except = id)
            appCtx?.let { runCatching { com.nizam.app.feature.notify.Notifications.post(it, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 5600,
                "📡 ${o.optString("from")}", o.optString("text").take(200)) } }
        }
        override fun onPayloadTransferUpdate(id: String, u: PayloadTransferUpdate) {}
    }
    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(id: String, info: ConnectionInfo) { peers[id] = info.endpointName; appCtx?.let { Nearby.getConnectionsClient(it).acceptConnection(id, payloads) } }
        override fun onConnectionResult(id: String, r: ConnectionResolution) { if (!r.status.isSuccess) peers.remove(id) }
        override fun onDisconnected(id: String) { peers.remove(id) }
    }
    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(id: String, info: DiscoveredEndpointInfo) { appCtx?.let { Nearby.getConnectionsClient(it).requestConnection(name, id, lifecycle) } }
        override fun onEndpointLost(id: String) {}
    }

    fun start(c: Context, me: String) {
        appCtx = c.applicationContext; name = me.ifBlank { "Atlas" }
        val cl = Nearby.getConnectionsClient(c)
        cl.startAdvertising(name, SERVICE, lifecycle, AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
        cl.startDiscovery(SERVICE, discovery, DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build())
        running = true
    }
    fun stop(c: Context) { Nearby.getConnectionsClient(c).stopAllEndpoints(); Nearby.getConnectionsClient(c).stopAdvertising(); Nearby.getConnectionsClient(c).stopDiscovery(); peers.clear(); running = false }

    fun send(c: Context, text: String) {
        val o = JSONObject().put("id", UUID.randomUUID().toString()).put("from", name).put("text", text).put("t", System.currentTimeMillis())
        seen.add(o.optString("id")); messages += Triple("You", text, o.optLong("t"))
        relay(o, except = null)
    }
    private fun relay(o: JSONObject, except: String?) {
        val c = appCtx ?: return
        val targets = peers.keys.filter { it != except }
        if (targets.isNotEmpty()) Nearby.getConnectionsClient(c).sendPayload(targets, Payload.fromBytes(o.toString().toByteArray()))
    }
}

@Composable
fun MeshScreen() {
    val c = LocalContext.current
    var me by remember { mutableStateOf(c.getSharedPreferences("mesh", 0).getString("name", "") ?: "") }
    var text by remember { mutableStateOf("") }
    val needed = remember { buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_SCAN); add(Manifest.permission.BLUETOOTH_ADVERTISE); add(Manifest.permission.BLUETOOTH_CONNECT) }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }.toTypedArray() }
    val perms = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) runCatching { Mesh.start(c, me) }.onFailure { Feedback.failed("Mesh", it) } else Feedback.show("Mesh needs Bluetooth and nearby-device permissions") }
    AtlasScreen(title = "Mesh chat", subtitle = if (Mesh.running) "${Mesh.peers.size} phones in range" else "Works with no internet and no signal", hub = Hub.Social) {
        item("s") { Column(Modifier.padding(horizontal = Space.page)) {
            OutlinedTextField(me, { me = it; c.getSharedPreferences("mesh", 0).edit().putString("name", it).apply() }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Your name on the mesh") })
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!Mesh.running) Button(onClick = { perms.launch(needed) }, enabled = me.isNotBlank()) { Text("Join the mesh") }
                else OutlinedButton(onClick = { Mesh.stop(c) }) { Text("Leave") }
            }
            if (Mesh.peers.isNotEmpty()) Text("Near you: " + Mesh.peers.values.joinToString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 6.dp))
            Text("Each phone passes messages on, so the chat reaches further than one phone can. Everyone needs Atlas with the mesh open.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
        } }
        Mesh.messages.forEachIndexed { i, (from, t, _) -> item("m$i") { Text("$from: $t", modifier = Modifier.padding(horizontal = Space.page, vertical = 3.dp),
            color = if (from == "You") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface) } }
        if (Mesh.running) item("in") { Row(Modifier.padding(Space.page), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(text, { text = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Message everyone nearby") })
            Button(onClick = { Mesh.send(c, text.trim()); text = "" }, enabled = text.isNotBlank(), modifier = Modifier.padding(start = 8.dp)) { Text("Send") }
        } }
    }
}

// ─────────────────────────────── Car health (OBD-II over Bluetooth) ───────────────────────────────
object Obd {
    private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var socket: BluetoothSocket? = null

    fun connect(c: Context, address: String) {
        val adapter = c.getSystemService(BluetoothManager::class.java).adapter
        val dev = adapter.getRemoteDevice(address)
        runCatching { adapter.cancelDiscovery() }
        socket = dev.createRfcommSocketToServiceRecord(SPP).also { it.connect() }
        listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATSP0").forEach { cmd(it); Thread.sleep(300) }
    }
    fun close() { runCatching { socket?.close() }; socket = null }
    val connected get() = socket?.isConnected == true

    fun cmd(c: String): String {
        val s = socket ?: throw IllegalStateException("Not connected")
        s.outputStream.write((c + "\r").toByteArray()); s.outputStream.flush()
        val sb = StringBuilder(); val input = s.inputStream; val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < 3000) { if (input.available() > 0) { val ch = input.read().toChar(); if (ch == '>') break; sb.append(ch) } else Thread.sleep(20) }
        return sb.toString().replace("SEARCHING...", "").trim()
    }
    private fun bytes(r: String, pid: String): List<Int> = r.replace(" ", "").substringAfter("41$pid", "").chunked(2).mapNotNull { it.toIntOrNull(16) }

    fun live(): Map<String, String> = buildMap {
        runCatching { val b = bytes(cmd("010C"), "0C"); if (b.size >= 2) put("Engine RPM", "${(b[0] * 256 + b[1]) / 4}") }
        runCatching { val b = bytes(cmd("010D"), "0D"); if (b.isNotEmpty()) put("Speed", "${b[0]} km/h") }
        runCatching { val b = bytes(cmd("0105"), "05"); if (b.isNotEmpty()) put("Coolant", "${b[0] - 40} °C") }
        runCatching { val b = bytes(cmd("012F"), "2F"); if (b.isNotEmpty()) put("Fuel level", "${b[0] * 100 / 255}%") }
        runCatching { val b = bytes(cmd("0142"), "42"); if (b.size >= 2) put("Battery", "%.1f V".format((b[0] * 256 + b[1]) / 1000.0)) }
        runCatching { val b = bytes(cmd("0104"), "04"); if (b.isNotEmpty()) put("Engine load", "${b[0] * 100 / 255}%") }
    }
    /** Stored trouble codes (the check-engine light), e.g. P0301. */
    fun codes(): List<String> {
        val r = cmd("03").replace(" ", "").replace("\r", "").replace("\n", "").substringAfter("43", "")
        return r.chunked(4).filter { it.length == 4 && it != "0000" }.map { h ->
            val first = h[0].digitToInt(16)
            "PCBU"[first shr 2] + ((first and 3).toString()) + h.substring(1)
        }
    }
}

@Composable
fun ObdScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var tick by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf(if (Obd.connected) "Connected" else "Not connected") }
    var values by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var codes by remember { mutableStateOf<List<String>>(emptyList()) }
    var explain by remember { mutableStateOf("") }
    val perm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }
    val devices = remember(tick) { runCatching {
        if (Build.VERSION.SDK_INT >= 31 && ContextCompat.checkSelfPermission(c, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) emptyList()
        else c.getSystemService(BluetoothManager::class.java).adapter.bondedDevices.map { (it.name ?: it.address) to it.address } }.getOrDefault(emptyList()) }
    LaunchedEffect(Obd.connected, status) { com.nizam.app.ui.design.guard { while (Obd.connected && isActive) { values = withContext(Dispatchers.IO) { runCatching { Obd.live() }.getOrDefault(values) }; delay(2000) } } }
    DisposableEffect(Unit) { onDispose { } }
    AtlasScreen(title = "Car health", subtitle = status, hub = Hub.Travel) {
        item("d") { Column(Modifier.padding(horizontal = Space.page)) {
            Text("Plug a Bluetooth OBD-II (ELM327) dongle into the port under the dashboard, pair it in Bluetooth settings (PIN usually 1234), then pick it here.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (Build.VERSION.SDK_INT >= 31 && devices.isEmpty()) OutlinedButton(onClick = { perm.launch(Manifest.permission.BLUETOOTH_CONNECT) }, modifier = Modifier.padding(top = 6.dp)) { Text("Allow Bluetooth") }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                devices.forEach { (n, addr) -> Capsule(n, false) { status = "Connecting to $n…"; scope.launch {
                    status = withContext(Dispatchers.IO) { runCatching { Obd.connect(c, addr); "Connected to $n" }.getOrElse { "Couldn't connect: ${Feedback.friendly(it)}" } } } } }
            }
        } }
        if (values.isNotEmpty()) item("v") { Group(Modifier.padding(Space.page)) {
            values.forEach { (k, v) -> Row { Text(k, modifier = Modifier.weight(1f)); Text(v, fontWeight = FontWeight.Bold, fontSize = 18.sp) } }
        } }
        if (Obd.connected) item("c") { Column(Modifier.padding(horizontal = Space.page)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { codes = withContext(Dispatchers.IO) { runCatching { Obd.codes() }.getOrDefault(emptyList()) }
                    explain = if (codes.isEmpty()) "No stored fault codes — the engine computer is happy." else runCatching { Ai.generate(container.settingsRepository, codes.joinToString(),
                        "Explain these OBD-II trouble codes for a car owner in Pakistan: what each means, how serious (drive on / get it checked this week / stop now), likely cause, and rough repair cost in PKR. Brief.", 900) }
                        .getOrElse { Feedback.friendly(it) } } }) { Text("Read fault codes") }
                OutlinedButton(onClick = { Obd.close(); status = "Disconnected"; values = emptyMap() }) { Text("Disconnect") }
            }
            if (codes.isNotEmpty()) Text("Codes: " + codes.joinToString(), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            if (explain.isNotBlank()) Md(explain)
        } }
    }
}

// ─────────────────────────────── Laptop dashboard + clipboard bridge ───────────────────────────────
/** A tiny web server on the phone. Open the address on any computer on the same Wi-Fi; the PIN keeps others out. */
class LaptopServer(private val c: Context, port: Int, private val pin: String) : NanoHTTPD(port) {
    override fun serve(s: IHTTPSession): Response {
        if (LaptopAiApi.handlesApiUri(s.uri)) return LaptopAiApi.serve(c, pin, s)
        if (s.parameters["k"]?.firstOrNull() != pin) return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Wrong or missing PIN")
        val container = (c.applicationContext as NizamApplication).container
        return when (s.uri) {
            "/api/today" -> json(runBlocking { today(container) })
            "/api/ask" -> { val body = HashMap<String, String>(); runCatching { s.parseBody(body) }
                val q = s.parameters["q"]?.firstOrNull() ?: body["postData"]?.let { runCatching { JSONObject(it).optString("q") }.getOrNull() } ?: ""
                val out = runBlocking { runCatching { container.agentLoop.run(q, useWeb = false, autoApprove = false) }.getOrNull() }
                out?.pendingApproval?.forEach { (n, a) -> com.nizam.app.feature.agent.ApprovalInbox.add(c, "Laptop", n, a, com.nizam.app.feature.agent.Reviewer.Verdict("medium", "ask", "Asked from the laptop"), q) }
                json(JSONObject().put("reply", out?.reply ?: "That didn't work.").put("waiting", out?.pendingApproval?.size ?: 0)) }
            "/api/clip" -> if (s.method == Method.POST) { val body = HashMap<String, String>(); runCatching { s.parseBody(body) }
                    val t = body["postData"].orEmpty()
                    android.os.Handler(android.os.Looper.getMainLooper()).post { c.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("From PC", t)) }
                    json(JSONObject().put("ok", true)) }
                else json(JSONObject().put("text", Clip.last))
            else -> newFixedLengthResponse(Response.Status.OK, "text/html", PAGE.replace("__PIN__", pin))
        }
    }
    private fun json(o: JSONObject) = newFixedLengthResponse(Response.Status.OK, "application/json", o.toString())
    private suspend fun today(container: AppContainer): JSONObject {
        val tasks = runCatching { container.taskRepository.all().first().filter { it.completedAt == null && it.deletedAt == null }.take(12).map { it.title } }.getOrDefault(emptyList())
        val d = java.time.LocalDate.now().toString()
        val spent = runCatching { container.financeRepository.since(d).first().filter { it.kind == "EXPENSE" }.sumOf { it.amount } }.getOrDefault(0.0)
        return JSONObject().put("tasks", org.json.JSONArray(tasks)).put("spent", spent.toLong())
            .put("prayer", com.nizam.app.ui.design.Island.nextPrayer?.let { "${it.first} in ${it.second} min" } ?: "")
    }
    companion object {
        val PAGE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Atlas</title>
<style>body{font-family:system-ui,sans-serif;background:#0b1020;color:#e5e7eb;margin:0;padding:24px;max-width:900px;margin:auto}
h1{font-size:28px}.card{background:#151b33;border-radius:18px;padding:18px;margin:12px 0}input,textarea{width:100%;box-sizing:border-box;background:#0b1020;color:#fff;border:1px solid #334;border-radius:12px;padding:12px;font-size:15px}
button{background:linear-gradient(90deg,#6366f1,#14b8a6);color:#fff;border:0;border-radius:12px;padding:10px 18px;font-size:15px;margin-top:8px;cursor:pointer}#reply{white-space:pre-wrap;margin-top:12px}</style></head>
<body><h1>✦ Atlas</h1><div class="card" id="today">Loading…</div>
<div class="card"><b>Ask Atlas</b><input id="q" placeholder="Add a task, check spending, anything…" onkeydown="if(event.key=='Enter')ask()"><button onclick="ask()">Send</button><div id="reply"></div></div>
<div class="card"><b>Clipboard bridge</b><textarea id="clip" rows="3" placeholder="Paste here to send to the phone"></textarea>
<button onclick="toPhone()">Send to phone</button> <button onclick="fromPhone()">Get phone's last copy</button></div>
<script>const k='__PIN__';
fetch('/api/today?k='+k).then(r=>r.json()).then(d=>{today.innerHTML='<b>Next prayer:</b> '+(d.prayer||'—')+'<br><b>Spent today:</b> Rs '+d.spent.toLocaleString()+'<br><b>Open tasks:</b><ul>'+d.tasks.map(t=>'<li>'+t+'</li>').join('')+'</ul>'});
function ask(){reply.textContent='…';fetch('/api/ask?k='+k,{method:'POST',body:JSON.stringify({q:q.value})}).then(r=>r.json()).then(d=>{reply.textContent=d.reply+(d.waiting?'\n\n('+d.waiting+' action(s) wait for your OK on the phone)':'');q.value=''})}
function toPhone(){fetch('/api/clip?k='+k,{method:'POST',body:clip.value}).then(()=>alert('Sent to phone clipboard'))}
function fromPhone(){fetch('/api/clip?k='+k).then(r=>r.json()).then(d=>{clip.value=d.text||'(nothing yet — copy something on the phone with Atlas open)'})}
</script></body></html>"""
    }
}

/** The phone's last copied text. Android only lets the app on screen read the clipboard, so this updates while Atlas is open. */
object Clip { @Volatile var last: String = "" ; fun capture(c: Context) { runCatching { c.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.coerceToText(c)?.toString()?.let { if (it.isNotBlank()) last = it } } } }

class LaptopService : Service() {
    companion object { var server: LaptopServer? = null; var pin: String = ""; var running by mutableStateOf(false) }
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == "stop") { server?.stop(); server = null; running = false; stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("laptop", "Laptop dashboard", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "laptop").setSmallIcon(android.R.drawable.ic_menu_share).setContentTitle("Atlas is serving your laptop")
            .setContentText("Open the address shown in Atlas on your computer").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(5700, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(5700, n)
        if (server == null) { pin = (10000000..99999999).random().toString(); server = LaptopServer(applicationContext, 8765, pin).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) } }
        running = true
        return START_STICKY
    }
    override fun onDestroy() { server?.stop(); server = null; running = false; super.onDestroy() }
}

@Composable
fun LaptopScreen() {
    val c = LocalContext.current
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { Clip.capture(c) } }
    val ip = remember { runCatching { @Suppress("DEPRECATION") c.applicationContext.getSystemService(WifiManager::class.java).connectionInfo.ipAddress.let {
        "%d.%d.%d.%d".format(it and 0xff, it shr 8 and 0xff, it shr 16 and 0xff, it shr 24 and 0xff) } }.getOrDefault("") }
    AtlasScreen(title = "Laptop dashboard", subtitle = "Atlas in your computer's browser, served from this phone", hub = Hub.Tools) {
        item("a") { Column(Modifier.padding(horizontal = Space.page)) {
            if (!LaptopService.running) Button(onClick = { ContextCompat.startForegroundService(c, Intent(c, LaptopService::class.java)) }) { Text("Start") }
            else {
                Group {
                    Text("On your computer (same Wi-Fi), open:", style = MaterialTheme.typography.bodyMedium)
                    Text("http://$ip:8765/?k=${LaptopService.pin}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text("The PIN changes every time you start it. Keep this on a trusted Wi-Fi network. Prompts may go to your configured AI provider; use a local model for private requests.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("OpenAI-compatible API: http://$ip:8765/v1 · use the same PIN as a Bearer token; choose model “auto” or a model shown by /v1/models. Video: POST /v1/videos/generations; list models at /v1/videos/models. Video uses your Hugging Face Inference Providers credits.",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { Clip.capture(c); Feedback.show(if (Clip.last.isBlank()) "Clipboard is empty" else "Phone clipboard ready for the laptop") }) { Text("Share my clipboard") }
                    OutlinedButton(onClick = { c.startService(Intent(c, LaptopService::class.java).setAction("stop")) }) { Text("Stop") }
                }
            }
        } }
    }
}
