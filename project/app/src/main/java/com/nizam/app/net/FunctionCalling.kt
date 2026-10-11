package com.nizam.app.net

import com.nizam.app.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * Native function calling, instead of asking the model to hand-write JSON in its prose.
 *
 * Every provider validates tool calls against the schema on their side and returns them as
 * structured data. That is the difference between "the model described sending an email" and the
 * email being sent — weak models fail constantly at writing JSON, and never at this.
 *
 * Gemini uses functionDeclarations / functionCall / functionResponse.
 * OpenAI-compatible providers (OpenRouter, Groq, Hugging Face, Ollama) use tools / tool_calls.
 */
data class Fn(val name: String, val description: String, val parameters: JSONObject)
data class ToolRun(val name: String, val args: JSONObject, val result: String)
data class FnOutcome(val reply: String, val calls: List<ToolRun>)

object FunctionCalling {

    /** True when the chosen provider and model can do this properly. */
    suspend fun available(settings: SettingsRepository): Boolean =
        settings.aiProvider.first() in listOf("GEMINI", "OPENROUTER", "CUSTOM")

    /**
     * Tries each key and model in turn. Once a tool has actually run, Atlas stays on that source — switching
     * mid-task would run the same action twice.
     */
    suspend fun run(
        settings: SettingsRepository,
        system: String,
        userMessage: String,
        functions: List<Fn>,
        maxRounds: Int = 6,
        execute: suspend (String, JSONObject) -> String
    ): FnOutcome {
        val all = Health.order(Router.endpoints(settings, Router.appContext).filter { it.kind == "GEMINI" || it.kind == "OPENAI" })
        val ready = all.filter { Health.resting(it) == 0L }.ifEmpty { all.take(2) }
        var first: Throwable? = null
        for (e in ready.take(6)) {
            val calls = mutableListOf<ToolRun>()
            val tracked: suspend (String, JSONObject) -> String = { n, a -> execute(n, a).also { calls += ToolRun(n, a, it) } }
            val t0 = System.currentTimeMillis()
            try {
                val out = if (e.kind == "GEMINI") gemini(e, system, userMessage, functions, maxRounds, tracked)
                    else openAi(e, system, userMessage, functions, maxRounds, tracked)
                Health.ok(e, System.currentTimeMillis() - t0)
                return out
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Health.fail(e, t)
                if (first == null) first = t
                if (calls.isNotEmpty()) return FnOutcome("Done what I could before the connection dropped.", calls)
            }
        }
        throw first ?: Ai.MissingKeyException("No AI provider set up. Add a key in Settings › API keys.")
    }

    // ── Gemini ───────────────────────────────────────────────────────────────
    private suspend fun gemini(
        e: Router.Endpoint, system: String, userMessage: String,
        functions: List<Fn>, maxRounds: Int, execute: suspend (String, JSONObject) -> String
    ): FnOutcome {
        val url = "${e.url}/${e.model}:generateContent?key=${e.key}"
        val contents = JSONArray().put(JSONObject().put("role", "user")
            .put("parts", JSONArray().put(JSONObject().put("text", userMessage))))
        val declarations = JSONArray().apply {
            functions.forEach { put(JSONObject().put("name", it.name)
                .put("description", it.description.take(500)).put("parameters", it.parameters)) }
        }
        val calls = mutableListOf<ToolRun>()
        repeat(maxRounds) {
            val body = JSONObject()
                .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
                .put("contents", contents)
                .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
            val res = JSONObject(Http.postJson(url, body.toString()))
            val parts = res.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?: return FnOutcome(textOf(res), calls)
            val wanted = (0 until parts.length()).mapNotNull { parts.getJSONObject(it).optJSONObject("functionCall") }
            if (wanted.isEmpty()) return FnOutcome(
                (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }.trim(), calls)

            // record the model's turn, then answer every call it made
            contents.put(JSONObject().put("role", "model").put("parts", parts))
            val responses = JSONArray()
            wanted.forEach { call ->
                val name = call.optString("name")
                val args = call.optJSONObject("args") ?: JSONObject()
                val result = runCatching { execute(name, args) }.getOrElse { "Error: ${it.message}" }
                calls += ToolRun(name, args, result)
                responses.put(JSONObject().put("functionResponse", JSONObject().put("name", name)
                    .put("response", JSONObject().put("result", result.take(6000)))))
            }
            contents.put(JSONObject().put("role", "user").put("parts", responses))
        }
        return FnOutcome("I ran out of steps before finishing.", calls)
    }

    private fun textOf(res: JSONObject) = res.optJSONArray("candidates")?.optJSONObject(0)
        ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty()

    // ── OpenAI-compatible ────────────────────────────────────────────────────
    private suspend fun openAi(
        e: Router.Endpoint, system: String, userMessage: String,
        functions: List<Fn>, maxRounds: Int, execute: suspend (String, JSONObject) -> String
    ): FnOutcome {
        val url = e.url
        val key = e.key
        val model = e.model
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", userMessage))
        val tools = JSONArray().apply {
            functions.forEach {
                put(JSONObject().put("type", "function").put("function", JSONObject()
                    .put("name", it.name).put("description", it.description.take(500)).put("parameters", it.parameters)))
            }
        }
        val calls = mutableListOf<ToolRun>()
        repeat(maxRounds) {
            // Groq meters the max_tokens you declare, not what comes back: asking big fails outright
            val cap = if (url.contains("groq.com")) 1024 else 2000
            val body = JSONObject().put("model", model).put("messages", messages)
                .put("tools", tools).put("tool_choice", "auto").put("max_tokens", cap)
            val res = JSONObject(Http.postJson(url, body.toString(), if (key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $key")))
            val message = res.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
                ?: return FnOutcome("No answer from the model.", calls)
            val toolCalls = message.optJSONArray("tool_calls")
            if (toolCalls == null || toolCalls.length() == 0)
                return FnOutcome(message.optString("content").trim(), calls)

            messages.put(message)
            for (i in 0 until toolCalls.length()) {
                val call = toolCalls.getJSONObject(i)
                val fn = call.optJSONObject("function") ?: continue
                val name = fn.optString("name")
                // arguments arrive as a JSON *string* here, unlike Gemini
                val args = runCatching { JSONObject(fn.optString("arguments").ifBlank { "{}" }) }.getOrElse { JSONObject() }
                val result = runCatching { execute(name, args) }.getOrElse { "Error: ${it.message}" }
                calls += ToolRun(name, args, result)
                messages.put(JSONObject().put("role", "tool").put("tool_call_id", call.optString("id"))
                    .put("name", name).put("content", result.take(6000)))
            }
        }
        return FnOutcome("I ran out of steps before finishing.", calls)
    }
}
