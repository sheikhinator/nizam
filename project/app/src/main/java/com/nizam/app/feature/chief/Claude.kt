package com.nizam.app.feature.chief

import com.nizam.app.net.Http
import org.json.JSONArray
import org.json.JSONObject

/** Claude's own API (Anthropic Messages), with tools and prompt caching, for the Chief screen. */
object Claude {
    const val SONNET = "claude-sonnet-5-5"
    const val HAIKU = "claude-haiku-5-5"

    data class Usage(val input: Int, val output: Int, val cacheRead: Int, val cacheWrite: Int)
    data class Reply(val content: JSONArray, val stop: String, val usage: Usage)

    /** Rough dollars per million tokens: (input, output). Cache reads cost a tenth, cache writes a quarter more. Only an estimate. */
    private fun price(model: String): Pair<Double, Double> = if (model.contains("haiku")) 1.0 to 5.0 else 3.0 to 15.0

    fun cost(model: String, u: Usage): Double {
        val (i, o) = price(model)
        return (u.input * i + u.cacheRead * i * 0.1 + u.cacheWrite * i * 1.25 + u.output * o) / 1_000_000.0
    }

    suspend fun call(key: String, model: String, system: String, messages: JSONArray, tools: JSONArray, maxTokens: Int = 4096): Reply {
        val sys = JSONArray().put(JSONObject().put("type", "text").put("text", system).put("cache_control", JSONObject().put("type", "ephemeral")))
        val body = JSONObject().put("model", model).put("max_tokens", maxTokens).put("system", sys).put("messages", messages)
        if (tools.length() > 0) body.put("tools", tools)
        val text = Http.postJsonWithHeaders("https://api.anthropic.com/v1/messages", body.toString(),
            mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01", "content-type" to "application/json")) { _, _ -> }
        val o = JSONObject(text)
        val u = o.optJSONObject("usage") ?: JSONObject()
        return Reply(o.optJSONArray("content") ?: JSONArray(), o.optString("stop_reason"),
            Usage(u.optInt("input_tokens"), u.optInt("output_tokens"), u.optInt("cache_read_input_tokens"), u.optInt("cache_creation_input_tokens")))
    }
}
