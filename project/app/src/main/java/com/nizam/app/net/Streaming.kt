package com.nizam.app.net

import android.content.Context
import com.nizam.app.data.prefs.SettingsRepository
import com.nizam.app.feature.attach.Attachment
import com.nizam.app.feature.attach.Attachments
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Streamed replies. The wait is the same; the *felt* wait is not — words appear as they're generated
 * instead of after the whole answer lands.
 *
 * Gemini streams from :streamGenerateContent?alt=sse; OpenAI-compatible providers from the same
 * chat/completions endpoint with stream=true. Both use SSE framing, with different payload shapes.
 *
 * Endpoints come from the Router, in order. If one fails before any text has arrived (rate limit,
 * outage, bad key) the next one takes over, so a busy free tier costs a moment rather than an error.
 */
object Streaming {

    suspend fun reply(
        settings: SettingsRepository,
        prompt: String,
        system: String = "",
        maxTokens: Int = 1200,
        attachments: List<Attachment> = emptyList(),
        context: Context? = null,
        lane: Router.Lane = Router.Lane.CHAT,
        model: String? = null,
        allowFolax: Boolean = true,
        provider: String? = null,
        endpointUrl: String? = null,
        onRouted: (Router.Endpoint) -> Unit = {},
        onDelta: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val system = listOf(system, com.nizam.app.feature.future.Lang.instruction()).filter { it.isNotBlank() }.joinToString("\n\n")
        val configured = Router.endpoints(settings, context, lane).filter {
            (allowFolax || it.kind != "FOLAX") &&
                (provider == null || it.kind.equals(provider, true)) &&
                (endpointUrl == null || it.url.equals(endpointUrl, true))
        }
        val requestedModel = model?.trim()?.takeIf { it.isNotBlank() && !it.equals("auto", true) }
        val matching = requestedModel?.let { wanted ->
            configured.filter { it.model.equals(wanted, true) || it.name.equals(wanted, true) }
        } ?: configured
        if (configured.isEmpty()) throw Ai.MissingKeyException("No AI provider set up. Add a key in Settings › API keys.")
        if (matching.isEmpty()) throw IllegalArgumentException("Model “$requestedModel” is not available in Atlas.")
        // a request too big for Groq's per-minute token limit goes to the long-context providers first
        val all = com.nizam.app.feature.expertise.Tiers.fitToSize(Health.order(matching),
            com.nizam.app.feature.expertise.Tiers.estimateTokens(prompt, system, maxTokens))
        // resting keys and models (just rate-limited, rejected or missing) are skipped while a ready one exists
        val ready = all.filter { Health.resting(it) == 0L }
        val endpoints = if (ready.isNotEmpty()) ready + all.filter { it !in ready }.take(1) else all.take(3)
        var first: Throwable? = null
        val badHosts = mutableSetOf<String>()
        for (e in endpoints) {
            val host = e.url.substringAfter("://").substringBefore('/')
            if (host.isNotBlank() && host in badHosts) continue
            var produced = false
            val t0 = System.currentTimeMillis()
            try {
                onRouted(e)
                val shield = e.kind != "LOCAL" && e.kind != "FOLAX" && com.nizam.app.feature.future.Privacy.on()
                val m = com.nizam.app.feature.future.Privacy.Map()
                val restorer = com.nizam.app.feature.future.Privacy.Restorer(m) { piece -> produced = true; onDelta(piece) }
                val answer = if (!shield) stream(e, prompt, system, maxTokens, attachments) { piece -> produced = true; onDelta(piece) }
                    else stream(e, com.nizam.app.feature.future.Privacy.redact(prompt, m)!!, com.nizam.app.feature.future.Privacy.redact(system, m)!!,
                        maxTokens, attachments) { piece -> restorer.push(piece) }.also { restorer.flush() }
                Health.ok(e, System.currentTimeMillis() - t0)
                runCatching { com.nizam.app.feature.expertise.EngineLog.record(e.name, e.model, true, System.currentTimeMillis() - t0, null) }
                return@withContext if (shield) com.nizam.app.feature.future.Privacy.restore(answer, m) else answer
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                if (first == null) first = t
                Health.fail(e, t)
                runCatching { com.nizam.app.feature.expertise.EngineLog.record(e.name, e.model, false, System.currentTimeMillis() - t0, t.message) }
                // Once words are on screen, switching model mid-answer would splice two voices — stop here.
                if (produced) throw t
                val m = t.message.orEmpty()
                // a request one provider calls malformed, its other keys and models will too
                if (m.contains("HTTP 400") && !m.contains("model", true) && !m.contains("key", true)) badHosts += host
            }
        }
        throw first ?: IllegalStateException("No provider answered.")
    }

    private fun stream(
        e: Router.Endpoint, prompt: String, system: String, maxTokens: Int,
        attachments: List<Attachment>, onDelta: (String) -> Unit
    ): String = when (e.kind) {
        "GEMINI" -> gemini(e, prompt, system, maxTokens, attachments, onDelta)
        // on-device: no streaming API needed — a small model answers in one go
        "LOCAL" -> LocalModel.generate(Router.appContext ?: throw IllegalStateException("Offline model not ready"),
            Attachments.promptWithText(prompt, attachments), system, maxTokens, onDelta)
        // Folax has no API: Atlas asks it on screen and reads the answer (the backup when nothing else works)
        "FOLAX" -> kotlinx.coroutines.runBlocking { com.nizam.app.feature.jarvis.Folax.ask(Router.appContext
            ?: throw IllegalStateException("Folax not ready"), prompt.takeLast(1500)) }.also { onDelta(it) }
        else -> openAi(e, prompt, system, maxTokens, attachments, onDelta)
    }

    private fun sse(url: String, body: String, headers: Map<String, String>, onLine: (String) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 8000
            readTimeout = 45000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                throw IllegalStateException("HTTP ${conn.responseCode}: ${err.take(300)}")
            }
            conn.inputStream.bufferedReader().use { r ->
                while (true) {
                    val line = r.readLine() ?: break
                    if (line.startsWith("data:")) onLine(line.removePrefix("data:").trim())
                }
            }
        } catch (t: Throwable) {
            conn.disconnect(); throw t
        }
        // no disconnect() on success: the socket goes back to the pool, so the next request skips the TLS handshake
    }

    private fun gemini(
        e: Router.Endpoint, prompt: String, system: String, maxTokens: Int,
        attachments: List<Attachment>, onDelta: (String) -> Unit
    ): String {
        val url = "${e.url}/${e.model}:streamGenerateContent?alt=sse&key=${e.key}"
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", if (attachments.isEmpty()) JSONArray().put(JSONObject().put("text", prompt))
                    else Attachments.geminiParts(prompt, attachments))))
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens).put("temperature", 0.7))
        if (system.isNotBlank()) body.put("systemInstruction",
            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        val full = StringBuilder()
        sse(url, body.toString(), emptyMap()) { data ->
            runCatching {
                val o = JSONObject(data)
                o.optJSONObject("error")?.let { err -> throw IllegalStateException(err.optString("message", "Gemini error")) }
                val parts = o.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts") ?: return@runCatching
                for (i in 0 until parts.length()) {
                    val piece = parts.getJSONObject(i).optString("text")
                    if (piece.isNotEmpty()) { full.append(piece); onDelta(piece) }
                }
            }.onFailure { if (it is IllegalStateException) throw it }
        }
        val answer = Compat.clean(full.toString())
        if (answer.isBlank()) throw IllegalStateException("${e.name} returned an empty answer.")
        return answer
    }

    private fun openAi(
        e: Router.Endpoint, prompt: String, system: String, maxTokens: Int,
        attachments: List<Attachment>, onDelta: (String) -> Unit
    ): String {
        val messages = JSONArray()
        if (system.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", system))
        messages.put(JSONObject().put("role", "user").put("content",
            if (attachments.isEmpty()) prompt
            // images and PDF pages go as image parts; a text-only model still gets extracted text
            else if (Attachments.needsVision(attachments)) Attachments.openAiContent(prompt, attachments)
            else Attachments.promptWithText(prompt, attachments)))
        val body = JSONObject().put("model", e.model).put("messages", messages)
            .put("max_tokens", e.cap(maxTokens)).put("stream", true).put("temperature", 0.7)
        Compat.tune(body, e.url, e.model)
        val headers = mutableMapOf<String, String>()
        if (e.key.isNotBlank()) headers["Authorization"] = "Bearer ${e.key}"
        if (e.url.contains("openrouter.ai")) { headers["HTTP-Referer"] = "https://github.com/atlas-app"; headers["X-Title"] = "Atlas" }
        val full = StringBuilder()
        sse(e.url, body.toString(), headers) { data ->
            if (data == "[DONE]") return@sse
            runCatching {
                val o = JSONObject(data)
                o.optJSONObject("error")?.let { err -> throw IllegalStateException(err.optString("message", "provider error")) }
                val piece = o.optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("delta")?.optString("content").orEmpty()
                if (piece.isNotEmpty() && piece != "null") { full.append(piece); onDelta(piece) }
            }.onFailure { if (it is IllegalStateException) throw it }
        }
        val answer = Compat.clean(full.toString())
        if (answer.isBlank()) throw IllegalStateException("${e.name} returned an empty answer.")
        return answer
    }
}
