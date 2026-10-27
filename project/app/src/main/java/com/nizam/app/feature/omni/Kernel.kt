package com.nizam.app.feature.omni

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/**
 * The Atlas Kernel: every Atlas action and every AI tool as one typed function library that any agent can call:
 * Atlas itself, the being, Gemini (AppFunctions), and any MCP client (Claude, Cursor…) through the Atlas Node server.
 * Reads run straight away; anything that changes something goes to the approval inbox first.
 */
object Kernel {
    data class Fn(val name: String, val kind: String, val desc: String, val params: List<String>, val writes: Boolean)

    private val READS = Regex("^(read_|list_|where_is|calculate|search_web|read_link|fact_check|battery|screen_time|dry_run|connector_help|list_apps|read_clipboard)")

    val functions: List<Fn> by lazy {
        val out = mutableListOf<Fn>()
        val re = Regex("(\\b[a-z][a-z_0-9]+)\\s+\\{([^}]*)\\}")
        com.nizam.app.feature.agent.Actions.CATALOGUE.lines().forEach { line ->
            val ms = re.findAll(line).toList(); if (ms.isEmpty()) return@forEach
            val desc = line.substring(ms.last().range.last + 1).trim()
            ms.forEach { m -> val n = m.groupValues[1]
                if (out.none { it.name == n }) out += Fn(n, "action", desc.ifBlank { n.replace('_', ' ') }, m.groupValues[2].split(',').map { it.trim().trimEnd('?', '[', ']').removeSuffix("[]") }.filter { it.isNotBlank() }, !READS.containsMatchIn(n)) }
        }
        com.nizam.app.feature.nx.Nx.all.filterIsInstance<com.nizam.app.feature.nx.AiSpec>().forEach { s ->
            out += Fn("tool.${s.id}", "tool", "${s.title}: ${s.blurb}", s.fields.map { it.key }, false)
        }
        out
    }

    fun find(name: String) = functions.firstOrNull { it.name == name }

    /** Calls one function. [source] says who asked (shown in approvals and the journal). */
    suspend fun call(c: Context, name: String, args: JSONObject, source: String): JSONObject {
        val ct = O.container(c)
        val fn = find(name) ?: return JSONObject().put("ok", false).put("result", "No function called $name. Call kernel_list to see them.")
        if (fn.kind == "tool") {
            val spec = com.nizam.app.feature.nx.Nx.all.filterIsInstance<com.nizam.app.feature.nx.AiSpec>().first { "tool.${it.id}" == name }
            val v = spec.fields.associate { f -> f.key to args.optString(f.key).ifBlank { f.default } }
            val out = O.ai(c, spec.prompt(v), spec.system, minOf(spec.tokens, 2000), spec.web)
            Journal.add(c, source, name, args, true, out.take(400))
            return JSONObject().put("ok", true).put("result", out)
        }
        if (fn.writes) {
            com.nizam.app.feature.agent.ApprovalInbox.add(c, source, name, args, com.nizam.app.feature.agent.Reviewer.Verdict("medium", "ask", "$source wants to ${name.replace('_', ' ')}"), "Kernel call from $source")
            Journal.add(c, source, name, args, true, "Waiting for your approval")
            return JSONObject().put("ok", true).put("waiting", true).put("result", "Sent to the user's approval inbox. It runs when they approve.")
        }
        val r = com.nizam.app.feature.agent.Actions.run(ct, name, args)
        return JSONObject().put("ok", r.ok).put("result", r.summary)
    }

    fun mcpTools(): JSONArray = JSONArray().also { a ->
        functions.forEach { f -> a.put(JSONObject().put("name", f.name.replace('.', '_')).put("description", (if (f.writes) "[needs the user's approval] " else "") + f.desc)
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject().also { p -> f.params.forEach { p.put(it, JSONObject().put("type", "string")) } }))) }
    }
    fun fromMcpName(n: String) = functions.firstOrNull { it.name.replace('.', '_') == n }?.name ?: n
}

/** Every action any agent takes, with a way back where one exists. */
object Journal {
    fun store(c: Context) = JsonStore(c, "omni_journal")
    fun add(c: Context, source: String, fn: String, args: JSONObject, ok: Boolean, result: String, undo: JSONObject? = null) {
        store(c).add(JSONObject().put("t", System.currentTimeMillis()).put("source", source).put("fn", fn).put("args", args).put("ok", ok).put("result", result.take(500))
            .apply { if (undo != null) put("undo", undo) })
        store(c).all().let { if (it.size > 400) store(c).save(it.sortedBy { o -> o.optLong("t") }.takeLast(400)) }
    }

    /** Called after every Atlas action: works out how to reverse it. */
    suspend fun after(ct: AppContainer, action: String, args: JSONObject, ok: Boolean, summary: String) {
        val c = ct.appContext
        val undo: JSONObject? = if (!ok) null else runCatching {
            when (action) {
                "add_task" -> ct.taskRepository.all().first().filter { it.title.equals(args.optString("title"), true) }.maxByOrNull { it.createdAt }?.let { JSONObject().put("kind", "task").put("id", it.id) }
                "log_expense", "log_income", "log_expense_on" -> ct.financeRepository.recent().first().firstOrNull { kotlin.math.abs(it.amount - args.optDouble("amount", -1.0)) < 0.01 }?.let { JSONObject().put("kind", "txn").put("id", it.id) }
                "add_note" -> ct.noteRepository.everything().filter { it.title == args.optString("title") }.maxByOrNull { it.createdAt }?.let { JSONObject().put("kind", "note").put("id", it.id) }
                "remember_place" -> JSONObject().put("kind", "none")
                else -> null
            }
        }.getOrNull()
        add(c, Ghost.source ?: "Atlas", action, args, ok, summary, undo?.takeIf { it.optString("kind") != "none" })
    }

    suspend fun undo(ct: AppContainer, entryId: String): String {
        val e = store(ct.appContext).all().firstOrNull { it.optString("id") == entryId } ?: return "Not found"
        val u = e.optJSONObject("undo") ?: return "This one can't be undone automatically."
        if (e.optBoolean("undone")) return "Already undone"
        val msg = when (u.optString("kind")) {
            "task" -> ct.taskRepository.all().first().firstOrNull { it.id == u.optLong("id") }?.let { ct.taskRepository.save(it.copy(deletedAt = System.currentTimeMillis())); "Task removed" } ?: "Task was already gone"
            "txn" -> ct.financeRepository.recent().first().firstOrNull { it.id == u.optLong("id") }?.let { ct.financeRepository.remove(it); "Entry removed" } ?: "Entry was already gone"
            "note" -> ct.noteRepository.everything().firstOrNull { it.id == u.optLong("id") }?.let { ct.noteRepository.remove(it); "Note removed" } ?: "Note was already gone"
            else -> "Can't undo this kind"
        }
        store(ct.appContext).update(entryId) { it.put("undone", true).put("undo_msg", msg) }
        return msg
    }
}

// ─────────────────────────── Atlas Node: MCP server, agent card (A2A) and the continuity console ───────────────────────────
object Node {
    const val PORT = 8780
    private fun p(c: Context) = c.getSharedPreferences("omni_node", 0)
    fun token(c: Context): String = p(c).getString("token", null) ?: (1..24).map { "abcdefghjkmnpqrstuvwxyz23456789".random() }.joinToString("").also { p(c).edit().putString("token", it).apply() }
    fun resetToken(c: Context) = p(c).edit().remove("token").apply()
    fun ip(c: Context): String = runCatching { @Suppress("DEPRECATION") c.applicationContext.getSystemService(android.net.wifi.WifiManager::class.java).connectionInfo.ipAddress.let {
        "%d.%d.%d.%d".format(it and 0xff, it shr 8 and 0xff, it shr 16 and 0xff, it shr 24 and 0xff) } }.getOrDefault("0.0.0.0")
    var running by mutableStateOf(false)
    /** What you were last doing, so another device can pick it up. */
    fun handoff(c: Context): JSONObject = runCatching { JSONObject(p(c).getString("handoff", "{}")!!) }.getOrDefault(JSONObject())
    fun setHandoff(c: Context, o: JSONObject) = p(c).edit().putString("handoff", o.put("t", System.currentTimeMillis()).toString()).apply()
    val chat = mutableListOf<Pair<String, String>>()
}

class NodeServer(private val c: Context) : NanoHTTPD(Node.PORT) {
    private fun json(o: Any) = newFixedLengthResponse(Response.Status.OK, "application/json", o.toString()).also { it.addHeader("Access-Control-Allow-Origin", "*") }
    private fun body(s: IHTTPSession): String { val m = HashMap<String, String>(); runCatching { s.parseBody(m) }; return m["postData"].orEmpty() }

    override fun serve(s: IHTTPSession): Response {
        val tok = s.headers["authorization"]?.removePrefix("Bearer ")?.trim() ?: s.parameters["k"]?.firstOrNull()
        // the agent card is public, like a business card; everything else needs the token
        if (s.uri == "/.well-known/agent.json" || s.uri == "/.well-known/agent-card.json") return json(AgentCard.card(c))
        if (s.uri == "/a2a" && s.method == Method.POST) return json(runBlocking { AgentCard.inbound(c, body(s)) })
        if (tok != Node.token(c)) return newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "Atlas Node: wrong or missing token")
        return when (s.uri) {
            "/mcp" -> json(runBlocking { mcp(body(s)) })
            "/api/chat" -> { val q = runCatching { JSONObject(body(s)).optString("q") }.getOrDefault("")
                val r = runBlocking { runCatching { O.container(c).agentLoop.run(q, useWeb = true, autoApprove = false) }.getOrNull() }
                r?.pendingApproval?.forEach { (n, a) -> com.nizam.app.feature.agent.ApprovalInbox.add(c, "Continuity", n, a, com.nizam.app.feature.agent.Reviewer.Verdict("medium", "ask", "Asked from another device"), q) }
                val reply = r?.reply ?: "That didn't work."
                synchronized(Node.chat) { Node.chat += q to reply; if (Node.chat.size > 40) Node.chat.removeAt(0) }
                Node.setHandoff(c, JSONObject().put("from", "browser").put("last", q).put("reply", reply.take(400)))
                json(JSONObject().put("reply", reply).put("waiting", r?.pendingApproval?.size ?: 0)) }
            "/api/history" -> json(JSONArray().also { a -> synchronized(Node.chat) { Node.chat.forEach { (q, r) -> a.put(JSONObject().put("q", q).put("r", r)) } } })
            "/api/handoff" -> if (s.method == Method.POST) { val o = runCatching { JSONObject(body(s)) }.getOrDefault(JSONObject())
                    Node.setHandoff(c, o.put("from", "browser")); O.notify(c, 5810, "Continue on your phone", o.optString("last").ifBlank { "Pick up where you left off" }, o.optString("route").ifBlank { null }); json(JSONObject().put("ok", true)) }
                else json(Node.handoff(c))
            "/api/today" -> json(runBlocking { O.day(O.container(c), java.time.LocalDate.now()) })
            "/api/being" -> json(JSONObject().put("name", com.nizam.app.feature.being.Being.name(c)).put("doing", com.nizam.app.feature.being.Being.activity(c))
                .put("icon", com.nizam.app.feature.being.Being.icon(c)))
            "/api/functions" -> json(JSONArray().also { a -> Kernel.functions.forEach { a.put(JSONObject().put("name", it.name).put("desc", it.desc).put("writes", it.writes)) } })
            else -> newFixedLengthResponse(Response.Status.OK, "text/html", CONSOLE.replace("__TOKEN__", Node.token(c)))
        }
    }

    private suspend fun mcp(raw: String): JSONObject {
        val req = runCatching { JSONObject(raw) }.getOrElse { return JSONObject().put("jsonrpc", "2.0").put("error", JSONObject().put("code", -32700).put("message", "Parse error")) }
        val id = req.opt("id"); val m = req.optString("method"); val params = req.optJSONObject("params") ?: JSONObject()
        val result: Any = when (m) {
            "initialize" -> JSONObject().put("protocolVersion", "2025-06-18").put("serverInfo", JSONObject().put("name", "atlas-node").put("version", "1.0"))
                .put("capabilities", JSONObject().put("tools", JSONObject()))
            "tools/list" -> JSONObject().put("tools", Kernel.mcpTools())
            "tools/call" -> { val r = Kernel.call(c, Kernel.fromMcpName(params.optString("name")), params.optJSONObject("arguments") ?: JSONObject(), "MCP client")
                JSONObject().put("content", JSONArray().put(JSONObject().put("type", "text").put("text", r.optString("result")))).put("isError", !r.optBoolean("ok")) }
            "ping" -> JSONObject()
            else -> return JSONObject().put("jsonrpc", "2.0").put("id", id).put("error", JSONObject().put("code", -32601).put("message", "Unknown method $m"))
        }
        return JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result)
    }

    companion object {
        val CONSOLE = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Atlas Continuity</title>
<style>body{font-family:system-ui,sans-serif;background:#0a0d18;color:#e6e8f2;margin:0}main{max-width:820px;margin:auto;padding:20px}h1{font-size:24px;margin:6px 0}
.bar{display:flex;gap:10px;align-items:center;flex-wrap:wrap;color:#9aa3c2;font-size:14px}.card{background:#141a30;border-radius:16px;padding:14px;margin:12px 0}
#log{height:52vh;overflow:auto;display:flex;flex-direction:column;gap:8px}.me{align-self:flex-end;background:#7dd3fc;color:#0a0d18}.it{align-self:flex-start;background:#1e2745}
.b{padding:9px 13px;border-radius:16px;max-width:78%;white-space:pre-wrap}input{flex:1;background:#0a0d18;color:#fff;border:1px solid #2a3355;border-radius:22px;padding:12px 16px;font-size:15px}
button{background:#7dd3fc;color:#0a0d18;border:0;border-radius:22px;padding:11px 18px;font-weight:700;cursor:pointer}.row{display:flex;gap:8px;margin-top:10px}</style></head>
<body><main><h1>✦ Atlas Continuity</h1><div class="bar"><span id="being"></span><span id="handoff"></span></div>
<div class="card"><div id="log"></div><div class="row"><input id="q" placeholder="Carry on where you left off…" onkeydown="if(event.key=='Enter')send()"><button onclick="send()">Send</button></div>
<div class="row"><button onclick="toPhone()" style="background:#1e2745;color:#e6e8f2">Continue on phone ↗</button></div></div>
<div class="card" id="today"></div></main>
<script>const k='__TOKEN__';const H={'Authorization':'Bearer '+k};
function add(t,c){const d=document.createElement('div');d.className='b '+c;d.textContent=t;log.appendChild(d);log.scrollTop=1e9}
fetch('/api/history',{headers:H}).then(r=>r.json()).then(a=>a.forEach(m=>{add(m.q,'me');add(m.r,'it')}));
fetch('/api/handoff',{headers:H}).then(r=>r.json()).then(h=>{if(h.last)handoff.textContent='· Last: '+h.last});
fetch('/api/being',{headers:H}).then(r=>r.json()).then(b=>being.textContent=b.icon+' '+b.name+' is '+b.doing.toLowerCase());
fetch('/api/today',{headers:H}).then(r=>r.json()).then(d=>today.innerHTML='<b>Today</b><br>Tasks done '+(d.tasks_done||0)+' · left '+(d.tasks_open||0)+' · spent Rs '+Math.round(d.spent||0)+' · prayers '+(d.prayers||0)+' · water '+(d.water_ml||0)+' ml');
function send(){const t=q.value.trim();if(!t)return;add(t,'me');q.value='';const w=document.createElement('div');w.className='b it';w.textContent='…';log.appendChild(w);
fetch('/api/chat',{method:'POST',headers:H,body:JSON.stringify({q:t})}).then(r=>r.json()).then(d=>{w.textContent=d.reply+(d.waiting?'\n\n('+d.waiting+' waiting for your OK on the phone)':'')})}
function toPhone(){const last=[...log.querySelectorAll('.me')].pop();fetch('/api/handoff',{method:'POST',headers:H,body:JSON.stringify({last:last?last.textContent:''})}).then(()=>alert('Sent to your phone'))}
</script></body></html>"""
    }
}

class NodeService : Service() {
    companion object { var server: NodeServer? = null }
    override fun onBind(i: Intent?): IBinder? = null
    override fun onStartCommand(i: Intent?, flags: Int, startId: Int): Int {
        if (i?.action == "stop") { server?.stop(); server = null; Node.running = false; stopSelf(); return START_NOT_STICKY }
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("atlas_node", "Atlas Node", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "atlas_node").setSmallIcon(android.R.drawable.ic_menu_share).setContentTitle("Atlas Node is on")
            .setContentText("Other agents and your browser can reach Atlas on this Wi-Fi").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(5800, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(5800, n)
        if (server == null) server = runCatching { NodeServer(applicationContext).also { it.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) } }.getOrNull()
        Node.running = server != null
        return START_STICKY
    }
    override fun onDestroy() { server?.stop(); server = null; Node.running = false; super.onDestroy() }
}

// ─────────────────────────── Agent card (A2A) ───────────────────────────
/** Your agent's public address: other people's agents can ask when you're free, book, quote or negotiate. You approve every yes. */
object AgentCard {
    private fun p(c: Context) = c.getSharedPreferences("omni_card", 0)
    fun rules(c: Context) = p(c).getString("rules", "Share only whether I'm free or busy, never what I'm doing. Meetings on weekdays 10am-6pm. Never agree to payments. Be polite and brief.")!!
    fun setRules(c: Context, r: String) = p(c).edit().putString("rules", r).apply()
    fun owner(c: Context) = p(c).getString("owner", "Atlas user")!!
    fun setOwner(c: Context, n: String) = p(c).edit().putString("owner", n).apply()
    fun inbox(c: Context) = JsonStore(c, "omni_a2a")

    fun card(c: Context) = JSONObject().put("name", "${owner(c)}'s Atlas").put("description", "Personal agent. Can answer availability, take meeting requests, quotes and messages for ${owner(c)}.")
        .put("url", "http://${Node.ip(c)}:${Node.PORT}/a2a").put("version", "1.0").put("protocolVersion", "0.3")
        .put("capabilities", JSONObject().put("streaming", false)).put("defaultInputModes", JSONArray().put("text/plain")).put("defaultOutputModes", JSONArray().put("text/plain"))
        .put("skills", JSONArray().put(JSONObject().put("id", "availability").put("name", "Availability").put("description", "When is the owner free?"))
            .put(JSONObject().put("id", "meeting").put("name", "Meeting request").put("description", "Propose a meeting; the owner approves"))
            .put(JSONObject().put("id", "message").put("name", "Leave a message").put("description", "Pass a message or quote to the owner")))

    /** A message from another agent (A2A message/send, or plain {text}). Atlas answers within your rules; anything binding waits for you. */
    suspend fun inbound(c: Context, raw: String): JSONObject {
        val o = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val text = o.optJSONObject("params")?.optJSONObject("message")?.optJSONArray("parts")?.let { a -> (0 until a.length()).joinToString(" ") { a.optJSONObject(it)?.optString("text").orEmpty() } }
            ?: o.optString("text").ifBlank { raw.take(2000) }
        val from = o.optString("from").ifBlank { "another agent" }
        val cal = runCatching { com.nizam.app.feature.agent.Actions.run(O.container(c), "read_calendar", JSONObject().put("days", 7)).summary }.getOrDefault("")
        val r = O.aiJson(c, "You are the personal agent of ${owner(c)}. Rules set by ${owner(c)}: ${rules(c)}\nTheir calendar next 7 days (busy times only, never reveal titles): ${cal.take(1500)}\n" +
            "Incoming from $from: \"$text\"\nReply as their agent. If it needs the owner's decision (meeting, money, commitment), say you'll confirm and mark needs_owner. " +
            "Output only JSON: {\"reply\":\"...\",\"needs_owner\":true|false,\"summary\":\"one line for the owner\"}", 500) ?: JSONObject().put("reply", "Message received. ${owner(c)} will get back to you.").put("needs_owner", true).put("summary", text.take(120))
        inbox(c).add(JSONObject().put("t", System.currentTimeMillis()).put("from", from).put("text", text).put("reply", r.optString("reply")).put("needs", r.optBoolean("needs_owner")).put("summary", r.optString("summary")))
        O.notify(c, 5820, "Agent message from $from", r.optString("summary").ifBlank { text.take(120) }, "nx/agentcard")
        val reply = r.optString("reply")
        return JSONObject().put("jsonrpc", "2.0").put("id", o.opt("id") ?: 1).put("result", JSONObject().put("kind", "message").put("role", "agent")
            .put("parts", JSONArray().put(JSONObject().put("kind", "text").put("text", reply))))
    }

    /** Talks to someone else's agent at [url] (their agent card's URL). */
    suspend fun send(url: String, text: String, from: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            val body = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "message/send").put("from", from).put("params", JSONObject().put("message",
                JSONObject().put("role", "user").put("messageId", System.nanoTime().toString()).put("parts", JSONArray().put(JSONObject().put("kind", "text").put("text", text)))))
            val r = JSONObject(com.nizam.app.net.Http.postJson(url, body.toString()))
            O.arr(r.optJSONObject("result")?.optJSONArray("parts")).joinToString(" ") { it.optString("text") }.ifBlank { r.toString().take(500) }
        }.getOrElse { "Couldn't reach that agent: ${it.message}" }
    }
}

// ─────────────────────────── screens ───────────────────────────
@Composable
fun KernelScreen() {
    val c = LocalContext.current
    var q by remember { mutableStateOf("") }
    var sel by remember { mutableStateOf<Kernel.Fn?>(null) }
    var args by remember { mutableStateOf("{}") }
    var out by remember { mutableStateOf("") }
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    Column {
        OCard {
            OTitle("Atlas Node")
            OMuted("Turns on a server on this phone. Claude, Cursor or any MCP client on the same Wi-Fi can use every Atlas function; your browser gets the Continuity console; other agents can reach your Agent Card.")
            if (!Node.running) OBtn("Turn on Atlas Node") { androidx.core.content.ContextCompat.startForegroundService(c, Intent(c, NodeService::class.java)) }
            else {
                OText("MCP server:  http://${Node.ip(c)}:${Node.PORT}/mcp")
                OText("Token (Bearer):  ${Node.token(c)}")
                OText("Browser console:  http://${Node.ip(c)}:${Node.PORT}/?k=${Node.token(c)}")
                OText("Agent card:  http://${Node.ip(c)}:${Node.PORT}/.well-known/agent.json")
                ORow { OBtn2("Copy MCP config") { val cfg = JSONObject().put("mcpServers", JSONObject().put("atlas", JSONObject().put("type", "http").put("url", "http://${Node.ip(c)}:${Node.PORT}/mcp")
                        .put("headers", JSONObject().put("Authorization", "Bearer ${Node.token(c)}")))).toString(2)
                    c.getSystemService(android.content.ClipboardManager::class.java).setPrimaryClip(android.content.ClipData.newPlainText("Atlas MCP", cfg)); com.nizam.app.ui.components.Feedback.show("Copied") }
                    OBtn2("Turn off") { c.startService(Intent(c, NodeService::class.java).setAction("stop")) } }
                OMuted("Reads run straight away. Anything that changes something lands in your approval inbox first.")
            }
        }
        OCard { OTitle("${Kernel.functions.size} functions"); OMuted("${Kernel.functions.count { it.kind == "action" }} actions · ${Kernel.functions.count { it.kind == "tool" }} AI tools · ${Kernel.functions.count { it.writes }} need approval")
            OField(q, "Search functions") { q = it } }
        sel?.let { f -> OCard {
            OTitle(f.name); OMuted(f.desc); OMuted("Parameters: ${f.params.joinToString().ifBlank { "none" }}${if (f.writes) " · needs approval" else ""}")
            OField(args, "Arguments (JSON)", 3) { args = it }
            ORow { OBtn("Run") { scope.launch { out = runCatching { Kernel.call(c, f.name, JSONObject(args), "You (Kernel)").optString("result") }.getOrElse { "Bad arguments: ${it.message}" } } }
                OBtn2("Close") { sel = null; out = "" } }
            if (out.isNotBlank()) OMd(out)
        } }
        Kernel.functions.filter { q.isBlank() || it.name.contains(q, true) || it.desc.contains(q, true) }.take(80).forEach { f ->
            OCard(onClick = { sel = f; args = JSONObject().also { o -> f.params.forEach { o.put(it, "") } }.toString() }) {
                OText((if (f.writes) "✋ " else "") + f.name); OMuted(f.desc.take(140)) }
        }
    }
}

@Composable
fun JournalScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var v by remember { mutableIntStateOf(0) }
    val list = remember(v) { Journal.store(c).all().sortedByDescending { it.optLong("t") }.take(150) }
    Column {
        OCard { OTitle("Every action, with a way back"); OMuted("Everything Atlas, its agents and outside agents did. Tap Undo where it's possible (tasks, money entries, notes).") }
        if (list.isEmpty()) OMuted("Nothing yet.")
        list.forEach { e -> OCard {
            OText("${if (e.optBoolean("ok")) "✓" else "✗"} ${e.optString("fn").replace('_', ' ')}  ·  ${e.optString("source")}")
            OMuted(e.optString("result").take(200))
            OMuted(java.text.SimpleDateFormat("d MMM, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(e.optLong("t"))) + if (e.optBoolean("undone")) " · undone (${e.optString("undo_msg")})" else "")
            if (e.optJSONObject("undo") != null && !e.optBoolean("undone")) OBtn2("Undo") { scope.launch { com.nizam.app.ui.components.Feedback.show(Journal.undo(O.container(c), e.optString("id"))); v++ } }
        } }
    }
}

@Composable
fun AgentCardScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var owner by remember { mutableStateOf(AgentCard.owner(c)) }
    var rules by remember { mutableStateOf(AgentCard.rules(c)) }
    var url by remember { mutableStateOf("") }; var msg by remember { mutableStateOf("") }; var reply by remember { mutableStateOf("") }
    var v by remember { mutableIntStateOf(0) }
    Column {
        OCard { OTitle("Your agent's address")
            OMuted("Other people's AI agents (A2A protocol) can ask when you're free, request a meeting, send a quote or a message. Your agent answers within your rules; anything binding waits for you.")
            OText(if (Node.running) "http://${Node.ip(c)}:${Node.PORT}/.well-known/agent.json" else "Turn on Atlas Node (Atlas Kernel screen) to publish it.")
            OField(owner, "Your name") { owner = it; AgentCard.setOwner(c, it) }
            OField(rules, "Rules your agent follows", 3) { rules = it; AgentCard.setRules(c, it) } }
        OCard { OTitle("Talk to another agent"); OField(url, "Their agent URL (…/a2a)") { url = it }; OField(msg, "Message", 2) { msg = it }
            OBtn("Send", url.isNotBlank() && msg.isNotBlank()) { scope.launch { reply = AgentCard.send(url, msg, owner) } }
            if (reply.isNotBlank()) OText(reply) }
        OTitle("Inbox")
        remember(v) { AgentCard.inbox(c).all().sortedByDescending { it.optLong("t") } }.forEach { m -> OCard {
            OText("${if (m.optBoolean("needs")) "✋ " else ""}${m.optString("from")}: ${m.optString("text").take(200)}"); OMuted("Your agent replied: ${m.optString("reply")}") } }
    }
}
