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
    suspend fun turn(system: String, history: List<Msg>, tools: JSONArray): Turn
}

/** Claude through Anthropic's own API: the strongest option, with prompt caching. */
class ClaudeBrain(private val key: String, private val model: String) : Brain {
    override val name = "Claude"
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
                val input = runCatching { JSONObject(f.optString("arguments").ifBlank { "{}" }) }.getOrDefault(JSONObject())
                calls += Call(c.optString("id").ifBlank { "call_$i" }, f.getString("name"), input)
            }
        }
        return Turn(if (msg.isNull("content")) "" else msg.optString("content"), calls, 0.0)
    }
}

/** The order to try: Claude first when a key is set, then every other AI the app already has connected. */
object Brains {
    suspend fun chain(c: Context): List<Brain> {
        val out = ArrayList<Brain>()
        val key = ChiefStore.claudeKey(c)
        if (key.isNotBlank()) out += ClaudeBrain(key, ChiefStore.model(c))
        // providers added in the provider list
        Providers.endpoints(c).filter { !it.url.contains("anthropic.com") }.forEach { e -> out += OpenAiBrain(e.name, e.url, e.key, e.model) }
        // the app's main AI keys (Gemini, OpenRouter, custom, fast lane), each tried in turn
        val settings = (c.applicationContext as? com.nizam.app.NizamApplication)?.container?.settingsRepository
        if (settings != null) {
            val seen = HashSet<String>()
            runCatching { com.nizam.app.net.Router.endpoints(settings, c, com.nizam.app.net.Router.Lane.ACT) }.getOrDefault(emptyList()).forEach { e ->
                if (e.kind == "LOCAL" || e.key.isBlank() && e.kind == "GEMINI") return@forEach
                val label = e.name + " " + e.model
                if (!seen.add(label) || seen.size > 6) return@forEach
                val url = if (e.kind == "GEMINI") "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions" else e.url
                out += OpenAiBrain(label.trim(), url, e.key, e.model)
            }
        }
        return out
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
