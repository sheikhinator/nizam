package com.nizam.app.feature.atlaschat

import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Events in the open relay format (NIP-01). Relays are only mailboxes: they hold sealed packets and cannot read them. */
object NostrWire {
    fun esc(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) when {
            ch == '"' -> sb.append("\\\"")
            ch == '\\' -> sb.append("\\\\")
            ch == '\n' -> sb.append("\\n")
            ch == '\r' -> sb.append("\\r")
            ch == '\t' -> sb.append("\\t")
            ch == '\b' -> sb.append("\\b")
            ch == '\u000C' -> sb.append("\\f")
            ch < ' ' -> sb.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
            else -> sb.append(ch)
        }
        return sb.append('"').toString()
    }

    fun idOf(pub: String, createdAt: Long, kind: Int, tags: List<List<String>>, content: String): String {
        val tagJson = tags.joinToString(",", "[", "]") { t -> t.joinToString(",", "[", "]") { esc(it) } }
        val ser = "[0,${esc(pub)},$createdAt,$kind,$tagJson,${esc(content)}]"
        return Prim.sha256(ser.toByteArray(Charsets.UTF_8)).toHex()
    }

    fun build(secret: ByteArray, kind: Int, tags: List<List<String>>, content: String, createdAt: Long = System.currentTimeMillis() / 1000): JSONObject {
        val pub = Bip340.publicKey(secret).toHex()
        val id = idOf(pub, createdAt, kind, tags, content)
        val sig = Bip340.sign(id.fromHex(), secret).toHex()
        return JSONObject().put("id", id).put("pubkey", pub).put("created_at", createdAt).put("kind", kind)
            .put("tags", JSONArray(tags.map { JSONArray(it) })).put("content", content).put("sig", sig)
    }

    fun tagsOf(e: JSONObject): List<List<String>> {
        val a = e.optJSONArray("tags") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { t -> (0 until t.length()).map { t.optString(it) } } }
    }

    fun verify(e: JSONObject): Boolean = runCatching {
        val id = idOf(e.getString("pubkey"), e.getLong("created_at"), e.getInt("kind"), tagsOf(e), e.getString("content"))
        id == e.getString("id") && Bip340.verify(id.fromHex(), e.getString("pubkey").fromHex(), e.getString("sig").fromHex())
    }.getOrDefault(false)
}

/** Where sealed packets travel. The real one is a set of public relays; tests use an in-memory stand-in. */
interface Transport {
    var onEvent: ((subId: String, event: JSONObject) -> Unit)?
    fun start(urls: List<String>)
    fun stop()
    fun publish(event: JSONObject): Int
    fun subscribe(subId: String, filter: JSONObject)
    fun unsubscribe(subId: String)
    suspend fun query(filter: JSONObject, waitMs: Long = 7000): List<JSONObject>
    fun connected(): Int
}

/** A handful of public relays at once. Messages are published to all of them and read from all of them, so no single relay matters. */
class RelayPool(private val onChange: () -> Unit = {}) : Transport {
    override var onEvent: ((subId: String, event: JSONObject) -> Unit)? = null
    private class Conn(val url: String) {
        @Volatile var ws: WebSocket? = null
        @Volatile var open = false
        @Volatile var backoff = 2000L
        val outbox = java.util.concurrent.ConcurrentLinkedQueue<String>()
    }

    private class Query(val events: ConcurrentHashMap<String, JSONObject> = ConcurrentHashMap(), val eose: AtomicInteger = AtomicInteger())

    private val client = OkHttpClient.Builder().pingInterval(25, TimeUnit.SECONDS).readTimeout(0, TimeUnit.MILLISECONDS).connectTimeout(10, TimeUnit.SECONDS).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val conns = ConcurrentHashMap<String, Conn>()
    private val subs = ConcurrentHashMap<String, String>()
    private val queries = ConcurrentHashMap<String, Query>()
    @Volatile private var running = false

    fun openCount() = conns.values.count { it.open }
    override fun connected() = openCount()
    fun total() = conns.size
    fun status(): List<Pair<String, Boolean>> = conns.values.map { it.url to it.open }.sortedBy { it.first }

    override fun start(urls: List<String>) {
        running = true
        urls.forEach { u -> conns.getOrPut(u) { Conn(u) } }
        conns.keys.filter { it !in urls }.forEach { conns.remove(it)?.ws?.close(1000, null) }
        conns.values.forEach { if (it.ws == null) connect(it) }
    }

    override fun stop() {
        running = false
        conns.values.forEach { it.ws?.close(1000, null); it.ws = null; it.open = false }
        onChange()
    }

    private fun connect(c: Conn) {
        if (!running) return
        val req = runCatching { Request.Builder().url(c.url).build() }.getOrNull() ?: return
        c.ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                c.open = true; c.backoff = 2000L
                subs.values.forEach { webSocket.send(it) }
                while (true) { val m = c.outbox.poll() ?: break; webSocket.send(m) }
                onChange()
            }
            override fun onMessage(webSocket: WebSocket, text: String) { handle(text) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { dropped(c, webSocket) }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { dropped(c, webSocket) }
        })
    }

    private fun dropped(c: Conn, ws: WebSocket) {
        if (c.ws !== ws) return
        c.open = false; c.ws = null
        onChange()
        if (!running || !conns.containsKey(c.url)) return
        scope.launch { delay(c.backoff); c.backoff = minOf(c.backoff * 2, 60_000L); if (running && conns.containsKey(c.url) && c.ws == null) connect(c) }
    }

    private fun handle(text: String) {
        val a = runCatching { JSONArray(text) }.getOrNull() ?: return
        when (a.optString(0)) {
            "EVENT" -> {
                val sub = a.optString(1); val ev = a.optJSONObject(2) ?: return
                val q = queries[sub]
                if (q != null) { q.events.putIfAbsent(ev.optString("id"), ev) } else onEvent?.invoke(sub, ev)
            }
            "EOSE" -> queries[a.optString(1)]?.eose?.incrementAndGet()
        }
    }

    private fun sendAll(msg: String, queueIfClosed: Boolean = false): Int {
        var n = 0
        conns.values.forEach { c ->
            val w = c.ws
            if (c.open && w != null && w.send(msg)) n++
            else if (queueIfClosed) { c.outbox.add(msg); while (c.outbox.size > 200) c.outbox.poll() }
        }
        return n
    }

    override fun publish(event: JSONObject): Int = sendAll("[\"EVENT\",${event}]", queueIfClosed = true)

    override fun subscribe(subId: String, filter: JSONObject) {
        val req = "[\"REQ\",${esc(subId)},$filter]"
        subs[subId] = req
        sendAll(req)
    }

    override fun unsubscribe(subId: String) { subs.remove(subId); sendAll("[\"CLOSE\",${esc(subId)}]") }

    private fun esc(s: String) = NostrWire.esc(s)

    /** Ask the relays a question and gather the answers (stops when two relays have finished or time runs out). */
    override suspend fun query(filter: JSONObject, waitMs: Long): List<JSONObject> {
        val sub = "q" + Prim.random(6).toHex()
        val q = Query(); queries[sub] = q
        try {
            val req = "[\"REQ\",${esc(sub)},$filter]"
            var waited = 0L
            sendAll(req)
            while (waited < waitMs) {
                delay(120); waited += 120
                if (waited % 1200L == 0L) sendAll(req)       // relays that connected a moment ago get the question too
                val open = openCount()
                if (open > 0 && q.eose.get() >= minOf(2, open)) break
            }
        } finally { sendAll("[\"CLOSE\",${esc(sub)}]"); queries.remove(sub) }
        return q.events.values.toList()
    }
}
