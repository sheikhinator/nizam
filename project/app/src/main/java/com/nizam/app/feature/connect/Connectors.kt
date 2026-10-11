package com.nizam.app.feature.connect

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.nizam.app.AppContainer
import com.nizam.app.feature.mcp.Mcp
import com.nizam.app.feature.mcp.McpServer
import com.nizam.app.feature.next.JsonStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Connections to outside services. Keys are stored sealed with the phone's hardware keystore and are never
 * shown to the model — Atlas adds them to requests itself, so a prompt can't leak what it never sees.
 */
object Connectors {
    var version by mutableIntStateOf(0)
    private fun store(c: Context) = JsonStore(c, "connectors", sealed = true)
    /** Agents like Hermes can work for minutes on one request. */
    private val slow by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.MINUTES).build() }
    private val http by lazy { OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(45, TimeUnit.SECONDS).build() }

    /** The catalogue plus any custom APIs the user added. */
    fun services(c: Context): List<Service> = Catalog.all + custom(c)

    fun custom(c: Context): List<Service> = store(c).all().filter { it.optBoolean("custom") }.map { o ->
        Service(o.optString("id"), o.optString("name"), "Custom", "🔧", "api", o.optString("base"), o.optString("auth"), o.optString("test"), "", o.optString("notes"))
    }
    fun addCustom(c: Context, name: String, base: String, auth: String, test: String, notes: String, token: String) {
        val id = "custom_" + name.lowercase().filter { it.isLetterOrDigit() }.take(20) + System.currentTimeMillis() % 1000
        store(c).add(JSONObject().put("id", id).put("custom", true).put("name", name).put("base", base.trimEnd('/')).put("auth", auth).put("test", test)
            .put("notes", notes).put("values", JSONObject().put("token", token)).put("connectedAt", System.currentTimeMillis()))
        version++
    }

    fun values(c: Context, id: String): Map<String, String>? = store(c).all().firstOrNull { it.optString("id") == id && !it.optBoolean("off") }
        ?.optJSONObject("values")?.let { v -> v.keys().asSequence().associateWith { v.optString(it) } }
    fun isConnected(c: Context, s: Service): Boolean = when (s.kind) {
        "device" -> true
        "app" -> installed(c, s)
        "hub" -> hubConnected(c)
        else -> values(c, s.id) != null || (s.kind == "api" && s.fields.isEmpty())
    }
    fun hubConnected(c: Context) = listOf("zapier", "composio", "pipedream", "make").any { values(c, it) != null }
    fun installed(c: Context, s: Service): Boolean = s.pkg.isBlank() || runCatching { c.packageManager.getPackageInfo(s.pkg, 0); true }.getOrDefault(false)
    fun connected(c: Context): List<Service> = services(c).filter { it.kind in setOf("api", "mcp") && (values(c, it.id) != null || (it.kind == "api" && it.fields.isEmpty())) }
    fun connectedNames(c: Context): String = connected(c).joinToString { it.name }

    private fun fill(t: String, v: Map<String, String>) = Regex("\\{([a-zA-Z_]+)\\}").replace(t) { m -> v[m.groupValues[1]]?.trimEnd('/') ?: m.value }

    /** Saves the values, and for MCP services adds the server so its tools reach the agent. */
    suspend fun connect(c: Context, s: Service, v: Map<String, String>): Pair<Boolean, String> {
        val clean = v.mapValues { it.value.trim() } + s.fields.filter { v[it.key].isNullOrBlank() && it.default.isNotBlank() }.associate { it.key to it.default }
        val missing = s.fields.filter { clean[it.key].isNullOrBlank() && !it.label.contains("optional", true) }
        if (missing.isNotEmpty()) return false to "Fill in: ${missing.joinToString { it.label }}"
        val s2 = store(c)
        s2.save(s2.all().filter { it.optString("id") != s.id } + JSONObject().put("id", s.id).put("values", JSONObject(clean as Map<*, *>)).put("connectedAt", System.currentTimeMillis()))
        version++
        if (s.kind == "mcp") {
            val url = fill(s.base, clean)
            val server = McpServer(s.name, url, if (s.base.contains("{token}")) "" else clean["token"].orEmpty(), true)
            Mcp.save(c, Mcp.servers(c).filter { it.name != s.name } + server)
            return runCatching { Mcp.connect(server) }.fold({ true to "Connected — $it" }, { false to "Saved, but the server didn't answer: ${com.nizam.app.ui.components.Feedback.friendly(it)}" })
        }
        return test(c, s)
    }

    fun disconnect(c: Context, s: Service) {
        store(c).remove(s.id); version++
        if (s.kind == "mcp") Mcp.save(c, Mcp.servers(c).filter { it.name != s.name })
    }

    suspend fun test(c: Context, s: Service): Pair<Boolean, String> {
        if (s.test.isBlank()) return true to "Saved. (This one can't be tested without sending something.)"
        val parts = s.test.split(" ", limit = 3)
        val (method, path, body) = if (parts[0] in setOf("GET", "POST", "PUT")) Triple(parts[0], parts.getOrElse(1) { "" }, parts.getOrNull(2)) else Triple("GET", s.test, null)
        return runCatching { call(c, s, method, path, emptyMap(), body) }.fold(
            { (code, text) -> (code in 200..299) to (if (code in 200..299) "Connected ✓ ${summarise(text)}" else "The service said $code: ${text.take(160)}") },
            { false to com.nizam.app.ui.components.Feedback.friendly(it) })
    }

    /** Checks every connected service at once — for the health panel. */
    suspend fun testAll(c: Context): Map<String, Pair<Boolean, String>> = coroutineScope {
        val jobs: List<Pair<String, Deferred<Pair<Boolean, String>>>> = connected(c).filter { it.fields.isNotEmpty() }.map { s ->
            s.id to async { checkOne(c, s) }
        }
        jobs.associate { (id, d) -> id to d.await() }
    }

    private suspend fun checkOne(c: Context, s: Service): Pair<Boolean, String> =
        withTimeoutOrNull(20_000L) {
            if (s.kind == "mcp") {
                val server = Mcp.servers(c).firstOrNull { it.name == s.name }
                if (server == null) false to "Server entry missing — reconnect"
                else runCatching { Mcp.connect(server); true to "${Mcp.tools(server, refresh = true).size} tools ready" }
                    .getOrElse { false to "Not answering — ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
            } else test(c, s)
        } ?: (false to "Timed out — is the device on and on the same Wi-Fi?")

    private fun summarise(t: String) = runCatching { val o = JSONObject(t)
        listOf("name", "login", "username", "email", "display_name", "real_name", "user").firstNotNullOfOrNull { k -> o.optString(k).takeIf { it.isNotBlank() && it.length < 60 } }
            ?.let { "as $it" } ?: "" }.getOrDefault("")

    /** One HTTP call with auth added. Returns status and body (trimmed). */
    suspend fun call(c: Context, s: Service, method: String, path: String, query: Map<String, String>, body: String?, long: Boolean = false): Pair<Int, String> = withContext(Dispatchers.IO) {
        val v = values(c, s.id) ?: (if (s.fields.isEmpty()) emptyMap() else error("${s.name} isn't connected"))
        val base = fill(s.base, v).trimEnd('/')
        val p = fill(path, v)
        val qs = mutableListOf<String>()
        val rb = Request.Builder()
        s.auth.split(';').map { it.trim() }.filter { it.isNotBlank() }.forEach { part ->
            when {
                part.startsWith("H:") -> { val kv = part.removePrefix("H:"); rb.header(kv.substringBefore('='), fill(kv.substringAfter('='), v)) }
                part.startsWith("Q:") -> qs += fill(part.removePrefix("Q:"), v).split('&').map { kv -> kv.substringBefore('=') + "=" + URLEncoder.encode(kv.substringAfter('='), "UTF-8") }
                part.startsWith("B:") -> rb.header("Authorization", okhttp3.Credentials.basic(fill(part.removePrefix("B:"), v).substringBefore(':'),
                    fill(part.removePrefix("B:"), v).substringAfter(':')))
            }
        }
        query.forEach { (k, x) -> qs += URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(x, "UTF-8") }
        val url = (if (p.startsWith("http")) p else base + (if (p.isBlank() || p.startsWith("/") || p.startsWith("?")) p else "/$p")).let { u ->
            if (qs.isEmpty()) u else u + (if (u.contains('?')) "&" else "?") + qs.joinToString("&") }
        // a key must only ever travel to its own service
        val hostOk = runCatching { Uri.parse(url).host == Uri.parse(base).host || p.startsWith(base) }.getOrDefault(false)
        if (!hostOk) error("Refused: that URL isn't ${s.name}'s")
        val m = method.uppercase()
        val isForm = s.id == "stripe" || s.id == "twilio"
        val reqBody = when {
            m == "GET" || m == "DELETE" && body.isNullOrBlank() -> null
            isForm && body != null && body.trim().startsWith("{") -> JSONObject(body).let { o -> o.keys().asSequence().joinToString("&") { k ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(o.optString(k), "UTF-8") } }.toRequestBody("application/x-www-form-urlencoded".toMediaType())
            body == null || body == "null" -> (if (body == "null") "null" else "").toRequestBody("application/json".toMediaType())
            else -> body.toRequestBody((if (body.trim().startsWith("{") || body.trim().startsWith("[")) "application/json" else "text/plain").toMediaType())
        }
        rb.url(url).header("User-Agent", "Atlas/1.0").method(m, reqBody)
        (if (long) slow else http).newCall(rb.build()).execute().use { r -> r.code to (r.body?.string().orEmpty()).take(12000) }
    }

    /** Some APIs read with POST (search endpoints, GraphQL queries); those don't need approval. */
    fun isRead(method: String, path: String, body: String?): Boolean {
        val m = method.uppercase()
        if (m == "GET" || m == "HEAD") return true
        if (m != "POST") return false
        val b = body.orEmpty()
        if (b.contains("mutation", true)) return false
        return Regex("(search|query|/graphql|lookup|list_|/list$|get_|getinfo)", RegexOption.IGNORE_CASE).containsMatchIn(path) ||
            (path.isBlank() || path == "/") && Regex("\"query\"\\s*:\\s*\"\\s*(query|\\{)").containsMatchIn(b)
    }

    fun promptBlock(c: Context): String {
        val l = connected(c).filter { it.kind == "api" }
        if (l.isEmpty()) return "\n\nNo services are connected yet. If the user asks for one, use connector_help {service}."
        return "\n\nConnected services (use api_call; Atlas adds the login):\n" + l.joinToString("\n") { s ->
            "- ${s.id} (${s.name}) base ${fill(s.base, values(c, s.id).orEmpty().mapValues { if (it.key == "token") "…" else it.value })}" +
                if (s.notes.isNotBlank()) " — ${s.notes.take(260)}" else ""
        }
    }

    private fun find(c: Context, q: String): Service? = services(c).let { l ->
        l.firstOrNull { it.id.equals(q, true) } ?: l.firstOrNull { it.name.equals(q, true) } ?: l.firstOrNull { it.name.contains(q, true) || q.contains(it.name, true) } }

    /** The agent's entry point for api_call, list_connectors, open_in_app and connector_help. */
    suspend fun act(container: AppContainer, action: String, a: JSONObject): Pair<Boolean, String> {
        val c = container.appContext
        return when (action) {
            "list_connectors" -> true to (connected(c).joinToString("\n") { "${it.id}: ${it.name}" }.ifBlank { "Nothing connected yet." } +
                "\nPhone apps: " + services(c).filter { it.kind == "app" && installed(c, it) }.joinToString { it.name })
            "connector_help" -> {
                val s = find(c, a.optString("service")) ?: return true to "I don't have a ready connector for “${a.optString("service")}”. It can still be reached through Zapier, " +
                    "Composio or Pipedream (Connectors → Automation), or added as a custom API with its key."
                true to when (s.kind) {
                    "hub" -> "${s.name} only allows sign-in apps, so it connects through Zapier, Composio or Pipedream: connect one of those once in Connectors, add ${s.name} there, and its actions appear for me."
                    "app" -> "${s.name} is a phone app — I open it straight to the right screen${if (installed(c, s)) "" else " once it's installed"}."
                    "device" -> "${s.name} is built in: ${s.notes}"
                    else -> "Open Connectors → ${s.name}. You'll need: ${s.fields.joinToString { it.label }}. Get it here: ${s.tokenUrl}"
                }
            }
            "open_in_app" -> openInApp(c, a)
            "ask_hermes" -> {
                val s = services(c).first { it.id == "hermes" }
                if (values(c, "hermes") == null) return false to "Hermes Agent isn't connected. Open Connectors → Hermes Agent and add your PC's address and API_SERVER_KEY."
                val body = JSONObject().put("model", "hermes-agent").put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", a.optString("task")))).toString()
                runCatching { call(c, s, "POST", "/chat/completions", emptyMap(), body, long = true) }.fold({ (code, text) ->
                    if (code !in 200..299) false to "Hermes said $code: ${text.take(200)}"
                    else true to "Hermes: " + com.nizam.app.feature.brain2.Injection.guard(runCatching { JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content") }
                        .getOrDefault(text).take(6000)) }, { false to "Hermes: ${com.nizam.app.ui.components.Feedback.friendly(it)}" })
            }
            else -> {
                val s = find(c, a.optString("service")) ?: return false to "No connected service called “${a.optString("service")}”. list_connectors shows what's connected."
                if (s.kind != "api") return false to "${s.name} isn't a direct API connector."
                if (values(c, s.id) == null && s.fields.isNotEmpty()) return false to "${s.name} isn't connected yet. Open Connectors → ${s.name}."
                val q = a.optJSONObject("query")?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } } ?: emptyMap()
                val body = a.opt("body")?.let { if (it is JSONObject || it is org.json.JSONArray) it.toString() else it.toString() }
                runCatching { call(c, s, a.optString("method", "GET"), a.optString("path"), q, body) }.fold(
                    { (code, text) -> (code in 200..299) to "HTTP $code from ${s.name}:\n" + com.nizam.app.feature.brain2.Injection.guard(text.take(6000)) },
                    { false to "${s.name}: ${com.nizam.app.ui.components.Feedback.friendly(it)}" })
            }
        }
    }

    fun openInApp(c: Context, a: JSONObject): Pair<Boolean, String> {
        val s = find(c, a.optString("app"))?.takeIf { it.kind == "app" } ?: return false to "I don't know an app called “${a.optString("app")}”."
        val action = a.optString("action").ifBlank { "open" }
        val tpl = s.links[action] ?: s.links.entries.firstOrNull { it.key.contains(action, true) || action.contains(it.key, true) }?.value ?: s.links["open"] ?: ""
        val enc = { k: String -> URLEncoder.encode(a.optString(k), "UTF-8").replace("+", "%20") }
        val to = a.optString("to").filter { it.isDigit() || it == '+' }.let { d -> if (s.id.startsWith("whatsapp") && d.startsWith("0")) "92" + d.drop(1) else d.removePrefix("+") }
        val uri = tpl.replace("{to}", if (tpl.startsWith("tel:") || tpl.startsWith("smsto:") || s.id.startsWith("whatsapp")) to else enc("to"))
            .replace("{text}", enc("text")).replace("{query}", if (action == "open_url") a.optString("query") else enc("query")).replace("{subject}", enc("subject"))
        val i = if (uri.isBlank()) c.packageManager.getLaunchIntentForPackage(s.pkg) ?: return false to "${s.name} isn't installed."
            else Intent(if (uri.startsWith("tel:")) Intent.ACTION_DIAL else if (uri.startsWith("smsto:")) Intent.ACTION_SENDTO else Intent.ACTION_VIEW, Uri.parse(uri.replace("?body=", "?body=")))
                .apply { if (uri.startsWith("smsto:")) { data = Uri.parse(uri.substringBefore('?')); putExtra("sms_body", a.optString("text")) }
                    if (s.pkg.isNotBlank() && installed(c, s)) setPackage(s.pkg) }
        return runCatching { c.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true to "Opened ${s.name}${if (action != "open") " ($action)" else ""}." }
            .getOrElse { runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true to "Opened in the browser." }
                .getOrElse { false to "Couldn't open ${s.name}." } }
    }
}
