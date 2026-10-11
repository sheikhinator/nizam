package com.nizam.app.net

import org.json.JSONObject

/**
 * Small differences between OpenAI-compatible providers. The big one: reasoning models (gpt-oss,
 * DeepSeek-R1, QwQ, Qwen3, o-series) spend tokens thinking before they write the answer. With a tight
 * token limit the thinking eats everything and the answer comes back empty — so for those we ask for
 * brief, hidden reasoning, leave room for the answer, and strip any <think> text that slips through.
 */
object Compat {
    private val REASONING = Regex("gpt-oss|deepseek-r1|\\br1\\b|qwq|qwen3|\\bo1\\b|\\bo3|\\bo4|reason|think|magistral", RegexOption.IGNORE_CASE)
    fun reasoning(model: String) = REASONING.containsMatchIn(model)

    /** Adds the provider-specific switches that keep reasoning short and out of the answer. */
    fun tune(body: JSONObject, url: String, model: String): JSONObject {
        if (!reasoning(model)) return body
        when {
            url.contains("groq.com") && model.contains("gpt-oss") -> body.put("reasoning_effort", "low").put("include_reasoning", false)
            url.contains("groq.com") -> body.put("reasoning_format", "hidden")
            url.contains("openrouter.ai") -> body.put("reasoning", JSONObject().put("effort", "low").put("exclude", true))
            url.contains("api.openai.com") -> body.put("reasoning_effort", "low")
        }
        // these models reject or ignore a custom temperature
        if (url.contains("api.openai.com")) body.remove("temperature")
        return body
    }

    /** The answer text without any <think>…</think> block. */
    fun clean(text: String): String = text.replace(Regex("<think>[\\s\\S]*?</think>"), "").replace(Regex("^[\\s\\S]*?</think>"), "").trim()
}
