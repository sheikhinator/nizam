package com.nizam.app.feature.chief

import android.content.Context
import com.nizam.app.net.Http
import com.nizam.app.net.Providers
import org.json.JSONArray
import org.json.JSONObject

/** One tool the AI asked to run. */
data class Call(val id: String, val name: String, val input: JSONObject)

/** The conversation in one neutral shape, so any AI provider can continue it. */
sealed class Msg {
    data class User(val text: String) : Msg()
    data class Asst(val text: String, val calls: List<Call>) : Msg()
    data class Results(val items: List<Pair<String, String>>) : Msg()   // (call id, result text)
}

data class Turn(val text: String, val calls: List<Call>, val cost: Double)

interface Brain {
    val name: String
    /** How capable it is at code, 0 to 100 (a guess from its name). */
    val tier: Int
    suspend fun turn(system: String, history: List<Msg>, tools: JSONArray): Turn
}

val Brain.strong get() = tier >= 80

/** Rough ranking of models by name, so the strongest connected AI is tried first. */
object Tiers {
    private val TOP = Regex("gpt-5|gpt-4\\.1|gpt-4o(?!-mini)|\\bo3\\b|\\bo4\\b|gemini-(2\\.5|3)[a-z.-]*pro|kimi-k2|glm-4\\.[5-9]|deepseek-(v3|r1|chat|reasoner)|qwen3?-coder|qwen3-(235|480)|405b|llama-4|mistral-large|command-a")
    private val MID = Regex("70b|120b|gemini-(2\\.5|3)[a-z.-]*flash(?!-lite)|gpt-oss|mixtral|mistral-medium|qwen3-(32|30)|llama-3\\.3|gemini-flash-latest")
    private val SMALL = Regex("flash-lite|(^|[^0-9a-z])(1|3|7|8|9|12|14)b\\b|mini|small|tiny|nano")
    fun of(label: String, model: String): Int {
        val m = "$label $model".lowercase()
        return when {
            "claude" in m && "haiku" !in m -> 100
            "claude" in m -> 70
            TOP.containsMatchIn(m) -> 85
            MID.containsMatchIn(m) -> 60
            SMALL.containsMatchIn(m) -> 25
            else -> 45
        }
    }
}

/**
 * Keeps each AI inside its limits: a rolling request window per AI, a cool-down after a refusal, and a note of
 * which ones actually succeed on code, so the best ones are tried first next time.
 */
object Throttle {
    private val until = HashMap<String, Long>()
    private val recent = HashMap<String, ArrayDeque<Long>>()

    fun ready(name: String) = (until[name] ?: 0L) <= System.currentTimeMillis()
    fun cool(name: String, ms: Long) { until[name] = System.currentTimeMillis() + ms }
    fun waitUntil(): Long = until.values.minOrNull()?.minus(System.currentTimeMillis())?.coerceAtLeast(0) ?: 0L

    /** Waits if this AI has been asked too often in the last minute. */
    suspend fun pace(name: String, perMinute: Int) {
        if (perMinute <= 0) return
        val q = recent.getOrPut(name) { ArrayDeque() }
        val now = System.currentTimeMillis()
        while (q.isNotEmpty() && now - q.first() > 60_000L) q.removeFirst()
        if (q.size >= perMinute) kotlinx.coroutines.delay((60_000L - (now - q.first())).coerceIn(500L, 30_000L))
        q.addLast(System.currentTimeMillis())
    }

    /** How long to rest an AI after an error, by what the error says. */
    fun restFor(message: String): Long {
        val m = message.lowercase()
        return when {
            "credit balance" in m || "insufficient" in m || "billing" in m || "401" in m || "403" in m || "api key" in m -> 60 * 60_000L
            "429" in m || "rate" in m || "quota" in m || "too many" in m || "tokens per" in m || "413" in m || "capacity" in m || "overloaded" in m || "529" in m -> 75_000L
            else -> 2 * 60_000L
        }
    }
}

/** Fixes the commonest ways weaker models get a tool call wrong: calls written as plain text, or arguments with small JSON mistakes. */
object ToolRepair {
    fun args(raw: String): JSONObject {
        val t = raw.trim().ifBlank { "{}" }
        runCatching { return JSONObject(t) }
        val a = t.indexOf('{'); val b = t.lastIndexOf('}')
        if (a >= 0 && b > a) {
            val core = t.substring(a, b + 1).replace(Regex(",\\s*([}\\]])"), "$1")
            runCatching { return JSONObject(core) }
            runCatching { return JSONObject(core.replace('\'', '"')) }
        }
        return JSONObject()
    }

    /** Finds tool calls a model wrote inside its text instead of using the proper channel. */
    fun fromText(text: String, names: Set<String>): List<Call> {
        val out = ArrayList<Call>()
        var i = 0
        while (i < text.length) {
            val a = text.indexOf('{', i); if (a < 0) break
            var depth = 0; var end = -1
            for (j in a until text.length) { if (text[j] == '{') depth++ else if (text[j] == '}') { depth--; if (depth == 0) { end = j; break } } }
            if (end < 0) break
            val o = runCatching { JSONObject(text.substring(a, end + 1)) }.getOrNull()
            if (o != null) {
                val name = o.optString("name").ifBlank { o.optString("tool") }.ifBlank { o.optString("function") }
                if (name in names) {
                    val input = o.optJSONObject("arguments") ?: o.optJSONObject("input") ?: o.optJSONObject("parameters")
                        ?: o.optString("arguments").takeIf { it.isNotBlank() }?.let { args(it) } ?: JSONObject()
                    out += Call("txt_${out.size}_${a}", name, input)
                }
            }
            i = end + 1
        }
        return out
    }
}

/** Claude through Anthropic's own API: the strongest option, with prompt caching. */
class ClaudeBrain(private val key: String, private val model: String) : Brain {
    override val name = if (model.contains("haiku")) "Claude Haiku" else "Claude"
    override val tier = Tiers.of("claude", model)
    override suspend fun turn(system: String, history: List<Msg>, tools: JSONArray): Turn {
        val messages = JSONArray()
        for (m in history) when (m) {
            is Msg.User -> messages.put(JSONObject().put("role", "user").put("content", JSONArray().put(JSONObject().put("type", "text").put("text", m.text))))
            is Msg.Asst -> {
                val blocks = JSONArray()
                if (m.text.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", m.text))
                m.calls.forEach { blocks.put(JSONObject().put("type", "tool_use").put("id", it.id).put("name", it.name).put("input", it.input)) }
                messages.put(JSONObject().put("role", "assistant").put("content", blocks))
            }
            is Msg.Results -> {
                val blocks = JSONArray()
                m.items.forEach { (id, text) -> blocks.put(JSONObject().put("type", "tool_result").put("tool_use_id", id).put("content", text)) }
                messages.put(JSONObject().put("role", "user").put("content", blocks))
            }
        }
        val r = Claude.call(key, model, system, messages, tools)
        val calls = ArrayList<Call>(); val text = StringBuilder()
        for (i in 0 until r.content.length()) {
            val b = r.content.getJSONObject(i)
            when (b.optString("type")) {
                "text" -> text.append(b.optString("text"))
                "tool_use" -> calls += Call(b.optString("id"), b.optString("name"), b.optJSONObject("input") ?: JSONObject())
            }
        }
        return Turn(text.toString(), calls, Claude.cost(model, r.usage))
    }
}

/** Any other connected AI that speaks the common chat format with tools (Groq, Mistral, Cerebras, Gemini-compatible gateways...). */
class OpenAiBrain(override val name: String, private val url: String, private val key: String, private val model: String) : Brain {
    override val tier = Tiers.of(name, model)
    override suspend fun turn(system: String, history: List<Msg>, tools: JSONArray): Turn {
        val messages = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (m in history) when (m) {
            is Msg.User -> messages.put(JSONObject().put("role", "user").put("content", m.text))
            is Msg.Asst -> {
                val o = JSONObject().put("role", "assistant").put("content", m.text.ifBlank { JSONObject.NULL })
                if (m.calls.isNotEmpty()) o.put("tool_calls", JSONArray().also { arr ->
                    m.calls.forEach { arr.put(JSONObject().put("id", it.id).put("type", "function").put("function", JSONObject().put("name", it.name).put("arguments", it.input.toString()))) } })
                messages.put(o)
            }
            is Msg.Results -> m.items.forEach { (id, text) -> messages.put(JSONObject().put("role", "tool").put("tool_call_id", id).put("content", text)) }
        }
        val fnTools = JSONArray()
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            fnTools.put(JSONObject().put("type", "function").put("function",
                JSONObject().put("name", t.getString("name")).put("description", t.optString("description")).put("parameters", t.getJSONObject("input_schema"))))
        }
        val body = JSONObject().put("model", model).put("messages", messages).put("tools", fnTools).put("max_tokens", 3000)
        val headers = HashMap<String, String>().apply { put("content-type", "application/json"); if (key.isNotBlank()) put("Authorization", "Bearer $key") }
        val text = Http.postJsonWithHeaders(url, body.toString(), headers) { _, _ -> }
        val msg = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        val calls = ArrayList<Call>()
        msg.optJSONArray("tool_calls")?.let { arr ->
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i); val f = c.getJSONObject("function")
                val input = ToolRepair.args(f.optString("arguments"))
                calls += Call(c.optString("id").ifBlank { "call_$i" }, f.getString("name"), input)
            }
        }
        val said = if (msg.isNull("content")) "" else msg.optString("content")
        // some models write the tool call as text; recover it so the work is not lost
        if (calls.isEmpty() && said.contains("{")) {
            val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }.toSet()
            calls += ToolRepair.fromText(said, names)
        }
        return Turn(said, calls, 0.0)
    }
}

/** The order to try: Claude first when a key is set, then every other AI the app already has connected. */
object Brains {
    suspend fun chain(c: Context): List<Brain> {
        val out = ArrayList<Brain>()
        val key = ChiefStore.claudeKey(c)
        if (key.isNotBlank()) {
            out += ClaudeBrain(key, ChiefStore.model(c))
            if (ChiefStore.smart(c)) out += ClaudeBrain(key, Claude.HAIKU)
        }
        // providers added in the provider list
        Providers.endpoints(c).filter { !it.url.contains("anthropic.com") }.forEach { e -> out += OpenAiBrain(e.name, e.url, e.key, e.model) }
        // the app's main AI keys (Gemini, OpenRouter, custom, fast lane), each tried in turn
        val settings = (c.applicationContext as? com.nizam.app.NizamApplication)?.container?.settingsRepository
        if (settings != null) {
            val seen = HashSet<String>()
            runCatching { com.nizam.app.net.Router.endpoints(settings, c, com.nizam.app.net.Router.Lane.ACT) }.getOrDefault(emptyList()).forEach { e ->
                if (e.kind == "LOCAL" || (e.key.isBlank() && e.kind == "GEMINI")) return@forEach
                val label = (e.name + " " + e.model).trim()
                if (!seen.add(label) || seen.size > 8) return@forEach
                val url = if (e.kind == "GEMINI") "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions" else e.url
                out += OpenAiBrain(label, url, e.key, e.model)
            }
        }
        // strongest first; among equals, the ones that have worked best for this person
        return out.sortedWith(compareByDescending<Brain> { it.tier }.thenByDescending { ChiefStore.brainScore(c, it.name) })
    }
}

/** Saves and restores a conversation so a job can continue after the app is closed or an AI runs out. */
object MsgJson {
    fun toJson(list: List<Msg>): JSONArray = JSONArray().also { arr ->
        list.forEach { m ->
            arr.put(when (m) {
                is Msg.User -> JSONObject().put("t", "u").put("text", m.text)
                is Msg.Asst -> JSONObject().put("t", "a").put("text", m.text).put("calls", JSONArray().also { ca ->
                    m.calls.forEach { ca.put(JSONObject().put("id", it.id).put("name", it.name).put("input", it.input)) } })
                is Msg.Results -> JSONObject().put("t", "r").put("items", JSONArray().also { ia ->
                    m.items.forEach { ia.put(JSONObject().put("id", it.first).put("text", it.second)) } })
            })
        }
    }

    fun fromJson(arr: JSONArray): MutableList<Msg> {
        val out = ArrayList<Msg>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            when (o.optString("t")) {
                "u" -> out += Msg.User(o.optString("text"))
                "a" -> out += Msg.Asst(o.optString("text"), o.optJSONArray("calls")?.let { ca ->
                    (0 until ca.length()).map { ca.getJSONObject(it) }.map { Call(it.optString("id"), it.optString("name"), it.optJSONObject("input") ?: JSONObject()) }
                } ?: emptyList())
                "r" -> out += Msg.Results(o.optJSONArray("items")?.let { ia ->
                    (0 until ia.length()).map { ia.getJSONObject(it) }.map { it.optString("id") to it.optString("text") }
                } ?: emptyList())
            }
        }
        return out
    }
}
