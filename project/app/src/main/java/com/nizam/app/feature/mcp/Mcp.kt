package com.nizam.app.feature.mcp

import android.content.Context
import com.nizam.app.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Model Context Protocol client. MCP servers speak JSON-RPC 2.0 over HTTP: you initialize, they hand
 * back a session id, then you list tools and call them. Responses come back either as plain JSON or
 * as an SSE stream, so both are handled.
 *
 * Any MCP server you point Atlas at becomes usable in the Playground AND callable by the Curator —
 * which is what makes this open-ended rather than a fixed list of integrations.
 */
data class McpServer(val name: String, val url: String, val token: String, val enabled: Boolean)
data class McpTool(val server: String, val name: String, val description: String, val schema: JSONObject?)

/** One piece of a tool's reply. MCP can return text, images, audio, embedded files or links. */
data class McpPart(
    val kind: String,            // text | image | audio | file | link
    val text: String = "",
    val mime: String = "",
    val file: java.io.File? = null,
    val uri: String = ""
)

object Mcp {
    private val sessions = HashMap<String, String>()          // server url -> session id (Streamable HTTP)
    private val protocolVersions = HashMap<String, String>()  // server url -> agreed protocol version
    private val sseTransports = HashMap<String, SseTransport>()
    private val useSse = HashSet<String>()
    private val toolCache = HashMap<String, List<McpTool>>()
    private val toolCacheAt = HashMap<String, Long>()
    private const val TOOL_TTL = 10 * 60_000L        // ten minutes; servers rarely change tools

    private fun f(c: Context) = File(c.filesDir, "mcp.json")

    fun servers(c: Context): List<McpServer> = runCatching { JSONArray(f(c).readText()) }.getOrElse { JSONArray() }
        .let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            McpServer(it.optString("name"), it.optString("url"), it.optString("token"), it.optBoolean("enabled", true)) } }

    fun save(c: Context, list: List<McpServer>) = f(c).writeText(JSONArray().apply {
        list.forEach { put(JSONObject().put("name", it.name).put("url", it.url).put("token", it.token).put("enabled", it.enabled)) }
    }.toString())

    /** MCP replies can be SSE; the payload is the last `data:` line. */
    private fun payload(raw: String): JSONObject {
        val body = if (raw.contains("data:")) raw.lines().filter { it.startsWith("data:") }
            .joinToString("\n") { it.removePrefix("data:").trim() }.trim().lines().last() else raw.trim()
        return JSONObject(body)
    }

    /** Turns transport failures into something that says what to actually do about it. */
    private fun explain(e: Throwable): Throwable {
        val m = e.message.orEmpty()
        return when {
            m.contains("401") || m.contains("access token", true) || m.contains("unauthor", true) ->
                IllegalStateException("this server needs authentication — either paste a bearer token above, or append " +
                    "your key to the URL, e.g. …/mcp?apikey=YOUR_KEY")
            m.contains("404") -> IllegalStateException("no MCP endpoint at that URL — most servers end in /mcp or /sse")
            m.contains("405") -> IllegalStateException("that URL doesn't accept MCP requests — check you copied the server URL, not its homepage")
            m.contains("Unable to resolve host") -> IllegalStateException("couldn't reach that address — check the URL and your connection")
            else -> e
        }
    }

    /** A server restarted (common with a PC one) drops its session; reconnect once and retry transparently. */
    private suspend fun rpc(server: McpServer, method: String, params: JSONObject?, id: Int = 1): JSONObject = try {
        rpcOnce(server, method, params, id)
    } catch (e: Exception) {
        if (method == "initialize" || method.startsWith("notifications/") || sessions[server.url] == null) throw e
        sessions.remove(server.url); connect(server); rpcOnce(server, method, params, id)
    }

    private suspend fun rpcOnce(server: McpServer, method: String, params: JSONObject?, id: Int = 1): JSONObject {
        // legacy servers get their own transport
        if (server.url in useSse) return sseRpc(server, method, params, id)
        // a notification is a request WITHOUT an id — sending one makes servers error
        val notification = method.startsWith("notifications/")
        val req = JSONObject().put("jsonrpc", "2.0").put("method", method)
        if (!notification) req.put("id", id)
        if (params != null) req.put("params", params)
        val headers = buildMap {
            put("Content-Type", "application/json")
            put("Accept", "application/json, text/event-stream")
            if (server.token.isNotBlank()) put("Authorization", "Bearer ${server.token}")
            sessions[server.url]?.let { put("Mcp-Session-Id", it) }
            // required from 2025-06-18 onward; stricter servers reject requests without it
            protocolVersions[server.url]?.let { put("MCP-Protocol-Version", it) }
        }
        val raw = runCatching {
            Http.postJsonWithHeaders(server.url, req.toString(), headers) { name, value ->
                if (name.equals("Mcp-Session-Id", true)) sessions[server.url] = value    // keep the session alive
            }
        }.getOrElse { throw explain(it) }
        val o = payload(raw)
        o.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message", "MCP error ${it.optInt("code")}")) }
        return o.optJSONObject("result") ?: JSONObject()
    }

    /** Handshake. Must happen before anything else, and returns the server's own name and version. */
    suspend fun connect(server: McpServer): String {
        sessions.remove(server.url); useSse.remove(server.url); sseTransports.remove(server.url)?.close()
        // URLs ending in /sse are legacy by convention; otherwise try modern first and fall back
        if (server.url.trimEnd('/').endsWith("/sse")) useSse.add(server.url)
        val result = runCatching { initialize(server) }.getOrElse { first ->
            if (server.url in useSse) throw explain(first)
            useSse.add(server.url)
            runCatching { initialize(server) }.getOrElse { useSse.remove(server.url); throw explain(first) }
        }
        result.optString("protocolVersion").takeIf { it.isNotBlank() }?.let { protocolVersions[server.url] = it }
        runCatching { rpc(server, "notifications/initialized", JSONObject()) }
        val info = result.optJSONObject("serverInfo")
        return listOfNotNull(info?.optString("name"), info?.optString("version")).filter { it.isNotBlank() }.joinToString(" ")
            .ifBlank { "connected" } + (if (server.url in useSse) " (SSE)" else "")
    }

    private suspend fun initialize(server: McpServer): JSONObject =
        rpc(server, "initialize", JSONObject()
            .put("protocolVersion", "2025-06-18")
            .put("capabilities", JSONObject())
            .put("clientInfo", JSONObject().put("name", "Atlas").put("version", "1.0")))

    suspend fun tools(server: McpServer, refresh: Boolean = false): List<McpTool> {
        if (!refresh && System.currentTimeMillis() - (toolCacheAt[server.url] ?: 0) < TOOL_TTL)
            toolCache[server.url]?.let { return it }
        if (sessions[server.url] == null) connect(server)
        val arr = rpc(server, "tools/list", JSONObject(), id = 2).optJSONArray("tools") ?: JSONArray()
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            McpTool(server.name, it.optString("name"), it.optString("description"), it.optJSONObject("inputSchema"))
        }
        toolCache[server.url] = list
        toolCacheAt[server.url] = System.currentTimeMillis()
        return list
    }

    /** Calls a tool and returns every part of its reply, media included. */
    suspend fun callRich(c: Context, server: McpServer, tool: String, args: JSONObject): List<McpPart> {
        if (sessions[server.url] == null) connect(server)
        val result = rpc(server, "tools/call", JSONObject().put("name", tool).put("arguments", args), id = 3)
        val parts = parts(c, result, tool)
        if (result.optBoolean("isError"))
            throw IllegalStateException(parts.firstOrNull { it.kind == "text" }?.text?.take(300)
                ?: "the tool reported an error")
        // structuredContent is a newer field some servers use instead of text blocks
        if (parts.isEmpty()) result.optJSONObject("structuredContent")?.let {
            return listOf(McpPart("text", it.toString(2)))
        }
        return parts
    }

    private fun parts(c: Context, result: JSONObject, tool: String): List<McpPart> {
        val content = result.optJSONArray("content") ?: return emptyList()
        val dir = java.io.File(c.filesDir, "mcp").apply { mkdirs() }
        return (0 until content.length()).map { content.getJSONObject(it) }.mapNotNull { block ->
            when (block.optString("type")) {
                "text" -> McpPart("text", block.optString("text"))
                "image", "audio" -> {
                    val mime = block.optString("mimeType", if (block.optString("type") == "image") "image/png" else "audio/mpeg")
                    val bytes = runCatching { android.util.Base64.decode(block.optString("data"), android.util.Base64.DEFAULT) }.getOrNull()
                        ?: return@mapNotNull McpPart("text", "(unreadable ${block.optString("type")})")
                    val f = java.io.File(dir, "${tool}_${System.currentTimeMillis()}.${mime.substringAfter('/').substringBefore('+')}")
                    f.writeBytes(bytes)
                    McpPart(block.optString("type"), mime = mime, file = f)
                }
                "resource" -> {
                    val r = block.optJSONObject("resource") ?: return@mapNotNull null
                    val mime = r.optString("mimeType")
                    when {
                        r.has("text") -> McpPart("text", r.optString("text"), mime)
                        r.has("blob") -> {
                            val bytes = runCatching { android.util.Base64.decode(r.optString("blob"), android.util.Base64.DEFAULT) }.getOrNull()
                            val name = r.optString("uri").substringAfterLast('/').ifBlank { "${tool}_${System.currentTimeMillis()}" }
                            val f = java.io.File(dir, name)
                            if (bytes != null) { f.writeBytes(bytes)
                                McpPart(if (mime.startsWith("image")) "image" else "file", mime = mime, file = f, uri = r.optString("uri"))
                            } else null
                        }
                        else -> McpPart("link", r.optString("uri"), mime, uri = r.optString("uri"))
                    }
                }
                "resource_link" -> McpPart("link", block.optString("name"), block.optString("mimeType"), uri = block.optString("uri"))
                else -> McpPart("text", "[${block.optString("type")}]")
            }
        }
    }

    private fun text(result: JSONObject): String {
        val content = result.optJSONArray("content") ?: return result.toString().take(4000)
        return (0 until content.length()).map { content.getJSONObject(it) }.joinToString("\n") { c ->
            when (c.optString("type")) {
                "text" -> c.optString("text")
                "resource" -> c.optJSONObject("resource")?.optString("text").orEmpty()
                else -> "[${c.optString("type")}]"
            }
        }.trim().take(6000)
    }

    /** Remembered so the chat can show media a tool returned. */
    var lastParts: List<McpPart> = emptyList()
        private set
    fun noteParts(p: List<McpPart>) { lastParts = p }
    fun clearParts() { lastParts = emptyList() }

    /**
     * The tools most likely to matter for what was just asked. A server can expose hundreds; putting
     * all of them in the prompt buries the model, so they're scored locally against the message and
     * only the best are offered. This is what makes any MCP server usable without you learning it.
     */
    suspend fun relevant(c: Context, query: String, limit: Int = 20): List<McpTool> {
        val all = allTools(c)
        if (all.size <= limit) return all
        val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
        fun score(t: McpTool): Int {
            val hay = (t.name + " " + t.description).lowercase()
            return words.sumOf { w -> if (hay.contains(w)) (if (t.name.lowercase().contains(w)) 3 else 1).toInt() else 0 }
        }
        val scored = all.map { it to score(it) }.sortedByDescending { it.second }
        val matched = scored.filter { it.second > 0 }.map { it.first }
        // always pad with a spread across servers, so a keyword miss never hides a tool completely
        val spread = all.groupBy { it.server }.flatMap { it.value.take(8) }
        return (matched + spread).distinctBy { it.name }.take(limit)
    }

    /** Full schema for one tool, for when the summary isn't enough. */
    suspend fun schemaOf(c: Context, toolName: String): String {
        val t = allTools(c).firstOrNull { it.name.equals(toolName, true) }
            ?: return "No connected tool called \"$toolName\". Use mcp_tools to see what exists."
        return "${t.name} (${t.server})\n${t.description}\nInput schema:\n${t.schema?.toString(2) ?: "(none declared)"}"
    }

    /** Connected tools as real function declarations, for native function calling. */
    suspend fun functionsFor(c: Context, query: String, limit: Int = 40, serverUrl: String = ""): List<com.nizam.app.net.Fn> =
        runCatching {
            if (serverUrl.isBlank()) relevant(c, query, limit)
            else servers(c).firstOrNull { it.url == serverUrl }?.let { tools(it).take(limit) } ?: emptyList()
        }.getOrElse { emptyList() }.map { t ->
            com.nizam.app.net.Fn(
                name = t.name.take(64).replace(Regex("[^A-Za-z0-9_-]"), "_"),
                description = t.description.ifBlank { "Tool from ${t.server}" },
                parameters = t.schema ?: JSONObject().put("type", "object").put("properties", JSONObject())
            )
        }

    /** The block handed to the agent each turn. */
    suspend fun promptBlock(c: Context, query: String): String {
        val tools = runCatching { relevant(c, query) }.getOrElse { emptyList() }
        if (tools.isEmpty()) return ""
        return "\n── CONNECTED TOOLS you can call right now with mcp_call {tool, args} ──\n" +
            tools.joinToString("\n") { t ->
                val argKeys = t.schema?.optJSONObject("properties")?.keys()?.asSequence()?.toList().orEmpty()
                val required = t.schema?.optJSONArray("required")?.let { r -> (0 until r.length()).map { r.optString(it) } }.orEmpty()
                val props = t.schema?.optJSONObject("properties")
                val argDesc = argKeys.joinToString(", ") { k ->
                    val spec = props?.optJSONObject(k)
                    val type = spec?.optString("type").orEmpty().ifBlank { "any" }
                    val enum = spec?.optJSONArray("enum")?.let { e -> (0 until minOf(6, e.length())).joinToString("|") { e.optString(it) } }
                    "$k:${enum ?: type}${if (k in required) "*" else ""}"
                }
                "${t.name} (${t.server}): ${t.description.take(320)}" +
                    (if (argKeys.isEmpty()) "" else "\n    args: $argDesc")
            } + "\n(* = required. Use mcp_tools only if none of these fit.)\n"
    }

    /** Servers that just failed (a PC that's switched off) are skipped for a few minutes so chat never waits on them. */
    private val downUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    fun isDown(url: String) = (downUntil[url] ?: 0L) > System.currentTimeMillis()

    /** Every tool the AI is allowed to reach, across all enabled servers that are answering. */
    suspend fun allTools(c: Context): List<McpTool> = servers(c).filter { it.enabled && !isDown(it.url) }
        .flatMap { s -> runCatching { tools(s) }.onSuccess { downUntil.remove(s.url) }
            .getOrElse { downUntil[s.url] = System.currentTimeMillis() + 3 * 60_000L; emptyList() } }

    private val WRITE = Regex("(^|[_\\-. ])(send|create|delete|remove|update|post|publish|reply|forward|archive|move|write|upload|add|insert|pay|charge|book|order|invite|share|merge|close|cancel|transfer|trash|edit|execute|submit|approve|refund|deploy)", RegexOption.IGNORE_CASE)
    /** Whether a tool changes something outside (by its name) — those always go through approval. */
    fun writes(tool: String) = !tool.contains("session", true) && !tool.contains("fetch", true) && WRITE.containsMatchIn(tool) || WRITE.containsMatchIn(tool.replace(Regex("([a-z])([A-Z])"), "$1_$2"))

    private val STOP = setOf("what", "with", "that", "this", "from", "have", "please", "about", "your", "mine", "make", "want", "need", "tell", "give", "show", "could", "would", "should", "there", "their", "them", "then", "when", "where", "which", "will", "just", "also", "into", "some")
    /** True when the message mentions something a connected tool (or its server) does. */
    suspend fun hasMatch(c: Context, query: String): Boolean {
        val words = query.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 3 && it !in STOP }
        if (words.isEmpty()) return false
        val tools = runCatching { allTools(c) }.getOrElse { emptyList() }
        if (tools.isEmpty()) return false
        return tools.any { t -> val hay = (t.name.replace('_', ' ') + " " + t.server + " " + t.description.take(200)).lowercase(); words.any { hay.contains(it) } }
    }

    suspend fun findAndCallRich(c: Context, toolName: String, args: JSONObject): List<McpPart> {
        for (s in servers(c).filter { it.enabled }) {
            val match = runCatching { tools(s) }.getOrElse { emptyList() }.firstOrNull { it.name == toolName }
            if (match != null) return callRich(c, s, toolName, args).also { noteParts(it) }
        }
        throw IllegalStateException("No connected MCP server offers a tool called \"$toolName\"")
    }

    suspend fun findAndCall(c: Context, toolName: String, args: JSONObject): String {
        for (s in servers(c).filter { it.enabled }) {
            val match = runCatching { tools(s) }.getOrElse { emptyList() }.firstOrNull { it.name == toolName }
            if (match != null) return callRich(c, s, toolName, args).also { noteParts(it) }.joinToString("\n") { p ->
                when (p.kind) {
                    "text" -> p.text
                    "link" -> "[link: ${p.uri}]"
                    else -> "[${p.kind} saved as ${p.file?.name} — visible in the Playground]"
                }
            }.take(6000)
        }
        throw IllegalStateException("No connected MCP server offers a tool called \"$toolName\"")
    }
    /** Same JSON-RPC messages, different plumbing: POST out, replies arrive on the held-open stream. */
    private suspend fun sseRpc(server: McpServer, method: String, params: JSONObject?, id: Int): JSONObject {
        val transport = sseTransports.getOrPut(server.url) { SseTransport(server.url, server.token) }
        val notification = method.startsWith("notifications/")
        val req = JSONObject().put("jsonrpc", "2.0").put("method", method)
        if (!notification) req.put("id", id)
        if (params != null) req.put("params", params)
        val reply = runCatching { transport.send(req, id, expectReply = !notification) }.getOrElse { throw explain(it) }
        reply.optJSONObject("error")?.let { throw IllegalStateException(it.optString("message", "MCP error")) }
        return reply.optJSONObject("result") ?: JSONObject()
    }

}

/**
 * The legacy HTTP+SSE transport (protocol 2024-11-05), still used by a large share of servers.
 *
 * It splits the conversation in two: the client holds open a GET to /sse, on which the server first
 * sends an `endpoint` event naming a POST URL, and thereafter pushes every response as a `message`
 * event. Requests go out by POST (answered with 202 and an empty body) and their replies arrive on
 * the stream, matched by JSON-RPC id.
 */
class SseTransport(private val base: String, private val token: String) {
    private var postUrl: String? = null
    private val pending = java.util.concurrent.ConcurrentHashMap<Int, kotlinx.coroutines.CompletableDeferred<org.json.JSONObject>>()
    private var reader: Thread? = null
    @Volatile private var closed = false

    fun close() { closed = true; reader?.interrupt(); reader = null }

    /** Opens the stream and waits for the endpoint event that tells us where to POST. */
    suspend fun open(): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val ready = kotlinx.coroutines.CompletableDeferred<String>()
        reader = Thread {
            runCatching {
                val conn = (java.net.URL(base).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "text/event-stream")
                    setRequestProperty("Cache-Control", "no-cache")
                    if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                    connectTimeout = 20000
                    readTimeout = 0                       // the stream stays open
                }
                if (conn.responseCode !in 200..299)
                    throw IllegalStateException("HTTP ${conn.responseCode} opening the SSE stream")
                var event = ""
                conn.inputStream.bufferedReader().use { br ->
                    while (!closed) {
                        val line = br.readLine() ?: break
                        when {
                            line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                            line.startsWith("data:") -> {
                                val data = line.removePrefix("data:").trim()
                                if (event == "endpoint") {
                                    // may be a path or a full URL
                                    val resolved = if (data.startsWith("http")) data
                                    else java.net.URL(java.net.URL(base), data).toString()
                                    postUrl = resolved
                                    if (!ready.isCompleted) ready.complete(resolved)
                                } else runCatching {
                                    val o = org.json.JSONObject(data)
                                    val id = o.optInt("id", -1)
                                    pending.remove(id)?.complete(o)
                                }
                            }
                        }
                    }
                }
            }.onFailure { if (!ready.isCompleted) ready.completeExceptionally(it) }
        }.also { it.isDaemon = true; it.start() }
        kotlinx.coroutines.withTimeout(20_000) { ready.await() }
    }

    /** Sends a request and waits for its reply to arrive on the stream. */
    suspend fun send(message: org.json.JSONObject, id: Int, expectReply: Boolean): org.json.JSONObject {
        val target = postUrl ?: open()
        val deferred = kotlinx.coroutines.CompletableDeferred<org.json.JSONObject>()
        if (expectReply) pending[id] = deferred
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val conn = (java.net.URL(target).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                if (token.isNotBlank()) setRequestProperty("Authorization", "Bearer $token")
                connectTimeout = 20000; readTimeout = 60000
            }
            conn.outputStream.use { it.write(message.toString().toByteArray()) }
            val code = conn.responseCode                     // 202 with an empty body is the normal case
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                pending.remove(id)
                throw IllegalStateException("HTTP $code: ${err.take(200)}")
            }
            conn.disconnect()
        }
        if (!expectReply) return org.json.JSONObject()
        return kotlinx.coroutines.withTimeout(90_000) { deferred.await() }
    }
}
