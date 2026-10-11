package com.nizam.app.feature.flows

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/** One generic web call, used by flows. */
object FlowNet {
    private val client = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    fun parseLines(text: String, sep: Char): Map<String, String> = text.lines().mapNotNull { l ->
        val i = l.indexOf(sep); if (i <= 0) null else l.substring(0, i).trim() to l.substring(i + 1).trim()
    }.toMap()

    suspend fun request(method: String, url: String, headers: Map<String, String>, body: String?): Pair<Int, String> = withContext(Dispatchers.IO) {
        require(url.startsWith("http://") || url.startsWith("https://")) { "The web address must start with http or https." }
        val m = method.ifBlank { "GET" }.uppercase()
        val json = (headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value ?: "application/json").toMediaType()
        val rb = when {
            m == "GET" || m == "HEAD" -> null
            body.isNullOrEmpty() -> "".toRequestBody(json)
            else -> body.toRequestBody(json)
        }
        val req = Request.Builder().url(url).method(m, rb).header("User-Agent", "Atlas-Flows").apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        client.newCall(req).execute().use { resp -> resp.code to (resp.body?.string().orEmpty().take(60_000)) }
    }
}

/** Relay channels: a free, private message channel (ntfy) that other apps can post to and Atlas listens on. */
object Relay {
    private fun prefs(c: android.content.Context) = c.getSharedPreferences("flows", 0)
    fun base(c: android.content.Context) = prefs(c).getString("relay_base", "https://ntfy.sh").orEmpty().trimEnd('/')
    fun setBase(c: android.content.Context, v: String) = prefs(c).edit().putString("relay_base", v.trim()).apply()
    fun suggestTopic(): String = "atlas-" + java.util.UUID.randomUUID().toString().replace("-", "").take(14)

    data class Message(val topic: String, val title: String, val text: String)

    /** New messages on [topic] since the last check. */
    suspend fun poll(c: android.content.Context, topic: String): List<Message> {
        if (!topic.matches(Regex("[A-Za-z0-9_-]{4,64}"))) return emptyList()
        val p = prefs(c)
        val since = p.getString("since_$topic", null) ?: ((System.currentTimeMillis() / 1000) - 600).toString()
        val (code, body) = FlowNet.request("GET", "${base(c)}/$topic/json?poll=1&since=$since", emptyMap(), null)
        if (code !in 200..299) return emptyList()
        val out = ArrayList<Message>(); var last = since
        body.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val o = runCatching { org.json.JSONObject(line) }.getOrNull() ?: return@forEach
            if (o.optString("event") == "message") { out += Message(topic, o.optString("title"), o.optString("message")); last = o.optString("id", last) }
        }
        p.edit().putString("since_$topic", last).apply()
        return out
    }

    suspend fun send(c: android.content.Context, topic: String, title: String, message: String): String {
        require(topic.matches(Regex("[A-Za-z0-9_-]{4,64}"))) { "Channel names use letters, numbers, - and _ (4 to 64 characters)." }
        val h = HashMap<String, String>().apply { put("Content-Type", "text/plain"); if (title.isNotBlank()) put("Title", title.take(120)) }
        val (code, _) = FlowNet.request("POST", "${base(c)}/$topic", h, message)
        check(code in 200..299) { "The relay answered $code." }
        return "sent"
    }
}

/** Things flows add to the shared data layer, so insights and hints can use them. */
object Feeds {
    private fun store(c: android.content.Context) = com.nizam.app.feature.next.JsonStore(c, "roads_ext")
    fun add(c: android.content.Context, source: String, text: String) {
        val s = store(c)
        s.add(org.json.JSONObject().put("t", System.currentTimeMillis()).put("source", source.take(40)).put("text", text.take(400)))
        s.all().let { l -> if (l.size > 400) l.sortedBy { it.optLong("t") }.take(l.size - 400).forEach { s.remove(it.optString("id")) } }
    }
    fun items(c: android.content.Context): List<com.nizam.app.feature.nx.Roads.Item> =
        runCatching { store(c).all().map { com.nizam.app.feature.nx.Roads.Item(it.optString("source"), com.nizam.app.ui.design.Hub.Tools, it.optLong("t"), it.optString("text")) } }.getOrDefault(emptyList())
}
