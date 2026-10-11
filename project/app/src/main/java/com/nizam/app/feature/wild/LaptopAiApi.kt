package com.nizam.app.feature.wild

import android.content.Context
import android.util.Base64
import com.nizam.app.NizamApplication
import com.nizam.app.feature.attach.Attachment
import com.nizam.app.net.Http
import com.nizam.app.net.Health
import com.nizam.app.net.Router
import com.nizam.app.net.Streaming
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.UUID

/** OpenAI-compatible local gateway, available only while the user starts the PIN-protected laptop service. */
internal object LaptopAiApi {
    private const val MAX_BODY_BYTES = 12 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_MESSAGES = 128
    private const val MAX_ATTACHMENTS = 10

    private data class Prompt(val system: String, val text: String, val attachments: List<Attachment>)
    private class RequestError(val status: NanoHTTPD.Response.Status, message: String) : Exception(message)

    fun handlesApiUri(uri: String): Boolean =
        uri.startsWith("/v1/") || uri.startsWith("/v1beta/") ||
            uri in setOf("/api/tags", "/api/version", "/api/show", "/api/chat", "/api/generate", "/api/embed", "/api/embeddings")

    fun serve(context: Context, pin: String, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        if (!authorized(session, pin)) return routeError(session.uri, NanoHTTPD.Response.Status.UNAUTHORIZED, "Missing or incorrect API token.")
        return try {
            when {
                session.uri == "/v1/models" && session.method == NanoHTTPD.Method.GET -> models(context, session)
                session.uri == "/v1/chat/completions" && session.method == NanoHTTPD.Method.POST -> chat(context, session)
                session.uri == "/v1/completions" && session.method == NanoHTTPD.Method.POST -> completion(context, session)
                session.uri == "/v1/embeddings" && session.method == NanoHTTPD.Method.POST -> openAiEmbeddings(context, session)
                session.uri == "/v1/images/generations" && session.method == NanoHTTPD.Method.POST -> LaptopMediaApi.images(session)
                session.uri == "/v1/videos/models" && session.method == NanoHTTPD.Method.GET -> LaptopMediaApi.videoModels(context)
                session.uri == "/v1/videos/generations" && session.method == NanoHTTPD.Method.POST -> LaptopMediaApi.videos(context, session)
                session.uri == "/v1/audio/speech" && session.method == NanoHTTPD.Method.POST -> LaptopMediaApi.speech(context, session)
                session.uri == "/v1/audio/transcriptions" && session.method == NanoHTTPD.Method.POST -> LaptopMediaApi.transcriptions(context, session)
                session.uri == "/v1/responses" && session.method == NanoHTTPD.Method.POST -> responses(context, session)
                session.uri == "/v1/messages" && session.method == NanoHTTPD.Method.POST -> anthropicMessages(context, session)
                session.uri == "/v1beta/models" && session.method == NanoHTTPD.Method.GET -> geminiModels(context)
                session.uri.startsWith("/v1beta/models/") -> gemini(context, session)
                session.uri == "/api/tags" && session.method == NanoHTTPD.Method.GET -> ollamaTags(context)
                session.uri == "/api/version" && session.method == NanoHTTPD.Method.GET ->
                    ollamaJson(JSONObject().put("version", "0.9.9"))
                session.uri == "/api/show" && session.method == NanoHTTPD.Method.POST -> ollamaShow(context, session)
                session.uri == "/api/chat" && session.method == NanoHTTPD.Method.POST -> ollamaChat(context, session)
                session.uri == "/api/generate" && session.method == NanoHTTPD.Method.POST -> ollamaGenerate(context, session)
                session.uri in setOf("/api/embed", "/api/embeddings") && session.method == NanoHTTPD.Method.POST ->
                    ollamaEmbeddings(context, session, session.uri == "/api/embeddings")
                else -> error(NanoHTTPD.Response.Status.NOT_FOUND, "Unsupported local API route.")
            }
        } catch (e: RequestError) {
            routeError(session.uri, e.status, e.message ?: "Invalid request.")
        } catch (e: IllegalArgumentException) {
            routeError(session.uri, NanoHTTPD.Response.Status.BAD_REQUEST, e.message ?: "Invalid request.")
        } catch (e: Exception) {
            routeError(session.uri, NanoHTTPD.Response.Status.INTERNAL_ERROR, e.message?.take(500) ?: "Atlas could not complete the request.")
        }
    }

    private fun routeError(uri: String, status: NanoHTTPD.Response.Status, message: String) =
        if (uri.startsWith("/api/")) ollamaError(status, message) else error(status, message)

    private fun ollamaError(status: NanoHTTPD.Response.Status, message: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8",
            JSONObject().put("error", message).toString())

    private fun ollamaJson(payload: JSONObject): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8", payload.toString())

    private data class EmbeddingResult(val family: String, val vectors: List<JSONArray>)

    private fun openAiEmbeddings(context: Context, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val request = body(session)
        val inputs = embeddingInputs(request.opt("input"))
        val model = request.optString("model", "auto")
        val dimensions = request.optInt("dimensions", 0).takeIf { request.has("dimensions") }
        val result = embed(context, model, inputs, dimensions)
        val data = JSONArray()
        result.vectors.forEachIndexed { index, vector ->
            data.put(JSONObject().put("object", "embedding").put("index", index).put("embedding", vector))
        }
        val tokens = inputs.sumOf(::estimatedTokens)
        return json(NanoHTTPD.Response.Status.OK, JSONObject().put("object", "list").put("data", data)
            .put("model", result.family).put("usage", JSONObject().put("prompt_tokens", tokens).put("total_tokens", tokens)))
    }

    private fun ollamaEmbeddings(context: Context, session: NanoHTTPD.IHTTPSession, legacy: Boolean): NanoHTTPD.Response {
        val request = body(session)
        val input = request.opt("input") ?: request.opt("prompt")
            ?: throw IllegalArgumentException("input is required.")
        val inputs = embeddingInputs(input)
        val model = normalizeOllamaModel(request.optString("model", "auto"))
        val dimensions = request.optInt("dimensions", 0).takeIf { request.has("dimensions") }
        val result = embed(context, model, inputs, dimensions)
        if (legacy) {
            return ollamaJson(JSONObject().put("embedding", result.vectors.firstOrNull() ?: JSONArray()))
        }
        val vectors = JSONArray()
        result.vectors.forEach { vectors.put(it) }
        return ollamaJson(JSONObject().put("model", result.family).put("embeddings", vectors)
            .put("total_duration", 0).put("load_duration", 0)
            .put("prompt_eval_count", inputs.sumOf(::estimatedTokens)))
    }

    private fun embeddingInputs(input: Any?): List<String> {
        val inputs = when (input) {
            is String -> listOf(input)
            is JSONArray -> (0 until input.length()).map { i ->
                input.optString(i).takeIf { input.opt(i) is String }
                    ?: throw IllegalArgumentException("Embedding input arrays must contain only strings.")
            }
            else -> throw IllegalArgumentException("input must be a string or an array of strings.")
        }
        if (inputs.isEmpty() || inputs.size > 64) throw IllegalArgumentException("Provide between 1 and 64 input strings.")
        if (inputs.any { it.isBlank() || it.length > 120_000 }) throw IllegalArgumentException("Each input must be non-empty and no longer than 120,000 characters.")
        return inputs
    }

    private fun embed(context: Context, requestedModel: String, inputs: List<String>, dimensions: Int?): EmbeddingResult {
        val family = requestedModel.removePrefix("models/").ifBlank { "auto" }
        if (family != "auto" && !family.equals("gemini-embedding-001", true)) {
            throw RequestError(NanoHTTPD.Response.Status.NOT_IMPLEMENTED,
                "Embedding family '$family' is not available through Atlas's configured providers.")
        }
        if (dimensions != null && dimensions !in 1..3072) {
            throw IllegalArgumentException("dimensions must be between 1 and 3072 for gemini-embedding-001.")
        }
        val endpoints = geminiEndpoints(context).distinctBy { it.key }
        if (endpoints.isEmpty()) throw IllegalStateException("Add a Gemini API key in Atlas Settings to use embeddings.")
        var lastFailure: Throwable? = null
        for (endpoint in endpoints) {
            try {
                val vectors = inputs.map { text ->
                    val request = JSONObject().put("content", JSONObject()
                        .put("parts", JSONArray().put(JSONObject().put("text", text))))
                    dimensions?.let { request.put("outputDimensionality", it) }
                    val url = "${endpoint.url}/gemini-embedding-001:embedContent?key=${URLEncoder.encode(endpoint.key, "UTF-8")}"
                    val response = JSONObject(runBlocking { Http.postJson(url, request.toString()) })
                    val values = response.optJSONObject("embedding")?.optJSONArray("values")
                        ?: throw IllegalStateException(response.optJSONObject("error")?.optString("message")
                            ?: "Gemini did not return an embedding vector.")
                    values
                }
                return EmbeddingResult("gemini-embedding-001", vectors)
            } catch (e: Exception) {
                lastFailure = e
            }
        }
        throw IllegalStateException(lastFailure?.message?.take(500) ?: "No Gemini embedding endpoint answered.")
    }

    private fun estimatedTokens(text: String): Int =
        ((text.toByteArray(Charsets.UTF_8).size + 3) / 4).coerceAtLeast(1)

    private fun authorized(session: NanoHTTPD.IHTTPSession, pin: String): Boolean {
        if (pin.isBlank()) return false
        val headers = session.headers
        val authorization = headers.entries.firstOrNull { it.key.equals("authorization", true) }?.value.orEmpty()
        val token = Regex("^Bearer\\s+(.+)$", RegexOption.IGNORE_CASE).matchEntire(authorization)?.groupValues?.get(1)
            ?: headers.entries.firstOrNull { it.key.equals("x-api-key", true) || it.key.equals("x-goog-api-key", true) }?.value
            ?: return false
        return token.isNotBlank() && MessageDigest.isEqual(token.toByteArray(Charsets.UTF_8), pin.toByteArray(Charsets.UTF_8))
    }

    private fun models(context: Context, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val c = context.applicationContext
        val app = c as? NizamApplication ?: throw IllegalStateException("Atlas is not ready.")
        val endpoints = runBlocking { Router.endpoints(app.container.settingsRepository, c, Router.Lane.CHAT) }
            .filter { it.kind != "FOLAX" }
        val unique = endpoints.distinctBy { it.model }
        val anthropic = session.headers.keys.any { it.equals("anthropic-version", true) }
        val data = JSONArray()
        if (unique.isNotEmpty()) {
            if (!anthropic) data.put(modelItem("auto", "atlas"))
            if (unique.any(::supportsFusion)) {
                data.put(if (anthropic) JSONObject().put("id", "fusion").put("type", "model")
                    .put("display_name", "fusion").put("created_at", java.time.Instant.now().toString())
                    else modelItem("fusion", "atlas"))
            }
            unique.forEach {
                data.put(if (anthropic) JSONObject().put("id", it.model).put("type", "model")
                    .put("display_name", it.model).put("created_at", java.time.Instant.now().toString())
                    else modelItem(it.model, it.name))
            }
        }
        return if (anthropic) {
            json(NanoHTTPD.Response.Status.OK, JSONObject().put("data", data).put("has_more", false)
                .put("first_id", if (data.length() == 0) JSONObject.NULL else data.getJSONObject(0).optString("id"))
                .put("last_id", if (data.length() == 0) JSONObject.NULL else data.getJSONObject(data.length() - 1).optString("id")))
        } else json(NanoHTTPD.Response.Status.OK, JSONObject().put("object", "list").put("data", data))
    }

    private fun modelItem(id: String, owner: String) = JSONObject()
        .put("id", id).put("object", "model").put("created", 0).put("owned_by", owner)

    private fun ollamaTags(context: Context): NanoHTTPD.Response {
        val endpoints = chatEndpoints(context).distinctBy { it.model }
        val models = JSONArray().put(ollamaModelInfo("auto", "atlas"))
        if (endpoints.any(::supportsFusion)) models.put(ollamaModelInfo("fusion", "atlas"))
        endpoints.forEach { models.put(ollamaModelInfo(it.model, it.kind.lowercase())) }
        return ollamaJson(JSONObject().put("models", models))
    }

    private fun ollamaModelInfo(id: String, family: String): JSONObject = JSONObject()
        .put("name", id).put("model", id).put("modified_at", java.time.Instant.now().toString())
        .put("size", 0).put("digest", "")
        .put("details", JSONObject().put("parent_model", "").put("format", "atlas-local")
            .put("family", family).put("families", JSONArray().put(family))
            .put("parameter_size", "remote").put("quantization_level", "remote"))

    private fun ollamaShow(context: Context, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val request = body(session)
        val requested = normalizeOllamaModel(request.optString("model", request.optString("name")))
        val endpoint = when {
            requested == "auto" -> null
            isFusionModel(requested) && chatEndpoints(context).any(::supportsFusion) -> null
            isFusionModel(requested) -> return ollamaError(NanoHTTPD.Response.Status.NOT_FOUND, "model '$requested' not found")
            else -> chatEndpoints(context).firstOrNull { it.model.equals(requested, true) }
                ?: return ollamaError(NanoHTTPD.Response.Status.NOT_FOUND, "model '$requested' not found")
        }
        val family = endpoint?.kind?.lowercase() ?: "atlas"
        val contextLength = 128000
        val details = endpoint?.let { ollamaModelInfo(it.model, family).getJSONObject("details") }
            ?: ollamaModelInfo("auto", family).getJSONObject("details")
        return ollamaJson(JSONObject()
            .put("license", "").put("modified_at", java.time.Instant.now().toString())
            .put("modelfile", "FROM $requested").put("parameters", "num_ctx $contextLength")
            .put("template", "{{ .Prompt }}").put("details", details)
            .put("model_info", JSONObject().put("general.architecture", family)
                .put("general.context_length", contextLength))
            .put("capabilities", JSONArray().put("completion")))
    }

    private fun normalizeOllamaModel(value: String): String =
        value.trim().removeSuffix(":latest").ifBlank { "auto" }

    private fun chatEndpoints(context: Context): List<Router.Endpoint> {
        val app = context.applicationContext as? NizamApplication ?: throw IllegalStateException("Atlas is not ready.")
        return runBlocking { Router.endpoints(app.container.settingsRepository, context.applicationContext, Router.Lane.CHAT) }
            .filter { it.kind != "FOLAX" }
    }

    private fun ollamaChat(context: Context, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val request = body(session)
        val model = normalizeOllamaModel(request.optString("model", "auto"))
        val messages = request.optJSONArray("messages") ?: JSONArray()
        if (messages.length() == 0) {
            val reason = if (request.optInt("keep_alive", -1) == 0) "unload" else "load"
            return ollamaJson(JSONObject().put("model", model).put("created_at", java.time.Instant.now().toString())
                .put("message", JSONObject().put("role", "assistant").put("content", ""))
                .put("done", true).put("done_reason", reason))
        }
        if ((request.optJSONArray("tools")?.length() ?: 0) > 0) {
            return ollamaError(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "Tool calling is not yet supported by the Atlas local gateway.")
        }
        val parsed = parseOllamaMessages(context, messages)
        val system = ollamaFormatInstruction(request.opt("format"), parsed.system)
        val maxTokens = request.optJSONObject("options")?.optInt("num_predict", 1200)?.coerceIn(1, 8192) ?: 1200
        if (parsed.text.isBlank() && parsed.attachments.isEmpty()) {
            parsed.attachments.forEach { it.file.delete() }
            throw IllegalArgumentException("messages must contain text or an image.")
        }
        if (request.optBoolean("stream", true)) {
            if (isFusionModel(model) && parsed.attachments.isNotEmpty()) {
                parsed.attachments.forEach { it.file.delete() }
                throw RequestError(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "Multimodal requests are not supported for the fusion model yet.")
            }
            return ollamaStream(context, parsed, system, model, maxTokens, chatFormat = true, fusion = fusionOptions(request))
        }
        return try {
            val answer = runText(context, parsed, system, model, maxTokens, fusion = fusionOptions(request))
            val payload = JSONObject().put("model", answer.model).put("created_at", java.time.Instant.now().toString())
                .put("message", JSONObject().put("role", "assistant").put("content", answer.text))
                .put("done", true).put("done_reason", "stop")
            answer.fusion?.let { payload.put("x_fusion", it) }
            ollamaJson(payload)
        } finally {
            parsed.attachments.forEach { it.file.delete() }
        }
    }

    private fun ollamaGenerate(context: Context, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val request = body(session)
        val model = normalizeOllamaModel(request.optString("model", "auto"))
        val rawPrompt = request.optString("prompt")
        val suffix = request.optString("suffix")
        if (rawPrompt.isBlank() && suffix.isBlank()) {
            val reason = if (request.optInt("keep_alive", -1) == 0) "unload" else "load"
            return ollamaJson(JSONObject().put("model", model).put("created_at", java.time.Instant.now().toString())
                .put("response", "").put("done", true).put("done_reason", reason))
        }
        val prompt = if (suffix.isBlank()) rawPrompt
            else "$rawPrompt\n\nComplete the text before this suffix:\n$suffix"
        val system = ollamaFormatInstruction(request.opt("format"), request.optString("system"))
        val maxTokens = request.optJSONObject("options")?.optInt("num_predict", 1200)?.coerceIn(1, 8192) ?: 1200
        val parsed = Prompt("", prompt, emptyList())
        if (request.optBoolean("stream", true)) {
            return ollamaStream(context, parsed, system, model, maxTokens, chatFormat = false, fusion = fusionOptions(request))
        }
        val answer = runText(context, parsed, system, model, maxTokens, fusion = fusionOptions(request))
        val payload = JSONObject().put("model", answer.model).put("created_at", java.time.Instant.now().toString())
            .put("response", answer.text).put("done", true).put("done_reason", "stop").put("context", JSONArray())
        answer.fusion?.let { payload.put("x_fusion", it) }
        return ollamaJson(payload)
    }

    private fun ollamaFormatInstruction(format: Any?, system: String): String {
        val jsonInstruction = when (format) {
            is String -> if (format.equals("json", true)) "Return valid JSON only, without markdown fences." else ""
            is JSONObject -> "Return a JSON object matching this schema: $format"
            else -> ""
        }
        return listOf(system, jsonInstruction).filter { it.isNotBlank() }.joinToString("\n\n")
    }

    private fun parseOllamaMessages(context: Context, messages: JSONArray): Prompt {
        if (messages.length() > MAX_MESSAGES) throw IllegalArgumentException("messages may contain at most $MAX_MESSAGES items.")
        val converted = JSONArray()
        for (i in 0 until messages.length()) {
            val message = messages.optJSONObject(i) ?: throw IllegalArgumentException("Each message must be an object.")
            val role = message.optString("role", "user").lowercase()
            val rawContent = message.opt("content")
            val contentParts = JSONArray()
            when (rawContent) {
                is String -> if (rawContent.isNotBlank()) contentParts.put(JSONObject().put("type", "text").put("text", rawContent))
                is JSONArray -> for (j in 0 until rawContent.length()) contentParts.put(rawContent.get(j))
                null, JSONObject.NULL -> Unit
                else -> throw IllegalArgumentException("Ollama message content must be text or an array.")
            }
            val images = message.optJSONArray("images")
            if (images != null) for (j in 0 until images.length()) {
                val image = images.optString(j)
                if (image.isNotBlank()) contentParts.put(JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", if (image.startsWith("data:", true)) image else "data:image/png;base64,$image")))
            }
            val calls = message.optJSONArray("tool_calls")
            if (calls != null && calls.length() > 0) contentParts.put(JSONObject().put("type", "text")
                .put("text", "Previous tool calls: $calls"))
            val content: Any = if (contentParts.length() == 0) "" else contentParts
            converted.put(JSONObject().put("role", role).put("content", content))
        }
        return parseMessages(context, converted)
    }

    private fun ollamaStream(
        context: Context,
        prompt: Prompt,
        system: String,
        model: String,
        maxTokens: Int,
        chatFormat: Boolean,
        fusion: JSONObject? = null
    ): NanoHTTPD.Response {
        val input = PipedInputStream(64 * 1024)
        val output = PipedOutputStream(input)
        Thread({
            var servedModel = model
            fun emit(payload: JSONObject) {
                output.write((payload.toString() + "\n").toByteArray(Charsets.UTF_8))
                output.flush()
            }
            try {
                runBlocking {
                    streamReply(context, prompt, system, maxTokens, model, fusion,
                        onRouted = { servedModel = it }) { delta ->
                        val chunk = JSONObject().put("model", servedModel).put("created_at", java.time.Instant.now().toString())
                            .put("done", false)
                        if (chatFormat) chunk.put("message", JSONObject().put("role", "assistant").put("content", delta))
                        else chunk.put("response", delta)
                        emit(chunk)
                    }
                }
                val final = JSONObject().put("model", servedModel).put("created_at", java.time.Instant.now().toString())
                    .put("done", true).put("done_reason", "stop")
                if (chatFormat) final.put("message", JSONObject().put("role", "assistant").put("content", ""))
                else final.put("response", "")
                emit(final)
            } catch (e: Throwable) {
                runCatching { emit(JSONObject().put("error", e.message?.take(500) ?: "Atlas could not complete the request.").put("done", true)) }
            } finally {
                prompt.attachments.forEach { it.file.delete() }
                runCatching { output.close() }
            }
        }, "atlas-ollama-stream").apply { isDaemon = true; start() }
        return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "application/x-ndjson", input)
            .apply { addHeader("Cache-Control", "no-cache"); addHeader("X-Accel-Buffering", "no") }
    }

    private fun geminiModels(context: Context): NanoHTTPD.Response {
        val endpoints = geminiEndpoints(context)
        val models = JSONArray()
        endpoints.distinctBy { it.model }.forEach { endpoint ->
            models.put(JSONObject()
                .put("name", "models/${endpoint.model}")
                .put("baseModelId", endpoint.model)
                .put("version", "atlas-local")
                .put("displayName", endpoint.model)
                .put("description", "Available through Atlas on this device.")
                .put("inputTokenLimit", 32768)
                .put("outputTokenLimit", 8192)
                .put("supportedGenerationMethods", JSONArray()
                    .put("generateContent").put("streamGenerateContent").put("countTokens")))
        }
        return json(NanoHTTPD.Response.Status.OK, JSONObject().put("models", models))
    }

    private fun gemini(context: Context, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val route = session.uri.removePrefix("/v1beta/models/")
        val hasAction = ':' in route
        val requestedModel = (if (hasAction) route.substringBeforeLast(':') else route).removePrefix("models/")
        if (requestedModel.isBlank()) return error(NanoHTTPD.Response.Status.BAD_REQUEST, "A Gemini model name is required.")
        if (!hasAction && session.method == NanoHTTPD.Method.GET) {
            val endpoint = resolveGeminiEndpoint(context, requestedModel)
            return json(NanoHTTPD.Response.Status.OK, JSONObject()
                .put("name", "models/${endpoint.model}")
                .put("baseModelId", endpoint.model)
                .put("version", "atlas-local")
                .put("displayName", endpoint.model)
                .put("description", "Available through Atlas on this device.")
                .put("inputTokenLimit", 32768)
                .put("outputTokenLimit", 8192)
                .put("supportedGenerationMethods", JSONArray()
                    .put("generateContent").put("streamGenerateContent").put("countTokens")))
        }
        if (!hasAction || session.method != NanoHTTPD.Method.POST) {
            return error(NanoHTTPD.Response.Status.METHOD_NOT_ALLOWED, "Use POST with a supported Gemini model action.")
        }
        val action = route.substringAfterLast(':')
        val request = body(session)
        val endpoint = resolveGeminiEndpoint(context, requestedModel)
        return when (action) {
            "countTokens" -> geminiCountTokens(endpoint, request)
            "generateContent", "streamGenerateContent" -> {
                val prompt = parseGeminiContents(context, request.optJSONArray("contents")
                    ?: throw IllegalArgumentException("contents must be an array."))
                val system = geminiPartsText(request.optJSONObject("systemInstruction"))
                val config = request.optJSONObject("generationConfig")
                val maxTokens = config?.optInt("maxOutputTokens", 1200)?.coerceIn(1, 8192) ?: 1200
                val mime = config?.optString("responseMimeType").orEmpty()
                val fullSystem = listOf(system, if (mime == "application/json") "Return valid JSON only, without markdown fences." else "")
                    .filter { it.isNotBlank() }.joinToString("\n\n")
                if (prompt.text.isBlank() && prompt.attachments.isEmpty()) {
                    prompt.attachments.forEach { it.file.delete() }
                    throw IllegalArgumentException("contents must contain text or an image.")
                }
                if (action == "streamGenerateContent") {
                    geminiStream(context, prompt, fullSystem, endpoint.model, maxTokens,
                        session.parameters["alt"]?.firstOrNull().equals("sse", true))
                } else try {
                    val answer = runText(context, prompt, fullSystem, endpoint.model, maxTokens, provider = "GEMINI")
                    val candidate = JSONObject()
                        .put("content", JSONObject().put("role", "model")
                            .put("parts", JSONArray().put(JSONObject().put("text", answer.text))))
                        .put("finishReason", "STOP").put("index", 0)
                    json(NanoHTTPD.Response.Status.OK, JSONObject()
                        .put("candidates", JSONArray().put(candidate))
                        .put("modelVersion", answer.model))
                } finally {
                    prompt.attachments.forEach { it.file.delete() }
                }
            }
            else -> error(NanoHTTPD.Response.Status.NOT_FOUND, "Unsupported Gemini model action.")
        }
    }

    private fun geminiCountTokens(endpoint: Router.Endpoint, request: JSONObject): NanoHTTPD.Response {
        val contents = request.optJSONArray("contents")
            ?: throw IllegalArgumentException("contents must be an array.")
        val countRequest = JSONObject().put("contents", contents)
        request.optJSONObject("systemInstruction")?.let { countRequest.put("systemInstruction", it) }
        val url = "${endpoint.url}/${endpoint.model}:countTokens?key=${URLEncoder.encode(endpoint.key, "UTF-8")}"
        val response = runBlocking { Http.postJson(url, countRequest.toString()) }
        val payload = JSONObject(response)
        if (!payload.has("totalTokens")) throw IllegalStateException("Gemini did not return a token count.")
        return json(NanoHTTPD.Response.Status.OK, payload)
    }

    private fun geminiEndpoints(context: Context): List<Router.Endpoint> {
        val app = context.applicationContext as? NizamApplication ?: throw IllegalStateException("Atlas is not ready.")
        return runBlocking { Router.endpoints(app.container.settingsRepository, context.applicationContext, Router.Lane.CHAT) }
            .filter { it.kind.equals("GEMINI", true) }
    }

    private fun resolveGeminiEndpoint(context: Context, requestedModel: String): Router.Endpoint {
        val endpoints = geminiEndpoints(context)
        if (endpoints.isEmpty()) throw IllegalStateException("Add a Gemini API key in Atlas Settings to use the Gemini API.")
        val exact = endpoints.firstOrNull { it.model.equals(requestedModel, true) || it.name.equals(requestedModel, true) }
        if (exact != null) return exact
        if (requestedModel.startsWith("gemini-", true)) return endpoints.first()
        throw IllegalArgumentException("Gemini model “$requestedModel” is not available in Atlas.")
    }

    private fun parseGeminiContents(context: Context, contents: JSONArray): Prompt {
        if (contents.length() == 0 || contents.length() > MAX_MESSAGES) {
            throw IllegalArgumentException("contents must contain 1–$MAX_MESSAGES items.")
        }
        val messages = JSONArray()
        for (i in 0 until contents.length()) {
            val content = contents.optJSONObject(i) ?: throw IllegalArgumentException("Each Gemini content item must be an object.")
            val role = when (content.optString("role", "user").lowercase()) {
                "model", "assistant" -> "assistant"
                "user" -> "user"
                else -> throw IllegalArgumentException("Gemini content role must be user or model.")
            }
            val parts = content.optJSONArray("parts") ?: throw IllegalArgumentException("Each Gemini content item needs parts.")
            val converted = JSONArray()
            for (j in 0 until parts.length()) {
                val part = parts.optJSONObject(j) ?: continue
                when {
                    part.has("text") -> converted.put(JSONObject().put("type", "text").put("text", part.optString("text")))
                    part.has("inlineData") || part.has("inline_data") -> {
                        val image = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data")!!
                        val mime = image.optString("mimeType", image.optString("mime_type", "image/jpeg"))
                        val data = image.optString("data")
                        if (data.isBlank()) throw IllegalArgumentException("Gemini inline image data is empty.")
                        converted.put(JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", "data:$mime;base64,$data")))
                    }
                    part.has("functionCall") || part.has("function_call") -> {
                        val call = part.optJSONObject("functionCall") ?: part.optJSONObject("function_call")!!
                        converted.put(JSONObject().put("type", "text")
                            .put("text", "Function call requested: ${call.optString("name")} ${call.optJSONObject("args") ?: JSONObject()}"))
                    }
                    part.has("functionResponse") || part.has("function_response") -> {
                        val result = part.optJSONObject("functionResponse") ?: part.optJSONObject("function_response")!!
                        converted.put(JSONObject().put("type", "text")
                            .put("text", "Function result: ${result.optString("name")} ${result.opt("response") ?: JSONObject() }"))
                    }
                }
            }
            messages.put(JSONObject().put("role", role).put("content", converted))
        }
        return parseMessages(context, messages)
    }

    private fun geminiPartsText(container: JSONObject?): String {
        val parts = container?.optJSONArray("parts") ?: return ""
        return (0 until parts.length()).mapNotNull { parts.optJSONObject(it)?.optString("text")?.takeIf(String::isNotBlank) }
            .joinToString("\n")
    }

    private fun geminiStream(
        context: Context,
        prompt: Prompt,
        system: String,
        model: String,
        maxTokens: Int,
        sse: Boolean
    ): NanoHTTPD.Response {
        val app = context.applicationContext as? NizamApplication ?: throw IllegalStateException("Atlas is not ready.")
        if (sse) {
            return eventStream(prompt.attachments, "error") { output ->
                runBlocking {
                    Streaming.reply(app.container.settingsRepository, prompt.text, system, maxTokens, prompt.attachments,
                        context.applicationContext, Router.Lane.CHAT, model, allowFolax = false, provider = "GEMINI") { delta ->
                        writeEvent(output, "message", geminiChunk(model, delta, null).toString())
                    }
                }
                writeEvent(output, "message", geminiChunk(model, "", "STOP").toString())
            }
        }
        val input = PipedInputStream(64 * 1024)
        val output = PipedOutputStream(input)
        Thread({
            var first = true
            fun emit(chunk: JSONObject) {
                if (first) {
                    output.write("[".toByteArray(Charsets.UTF_8))
                    first = false
                } else output.write(",".toByteArray(Charsets.UTF_8))
                output.write(chunk.toString().toByteArray(Charsets.UTF_8))
                output.flush()
            }
            try {
                runBlocking {
                    Streaming.reply(app.container.settingsRepository, prompt.text, system, maxTokens, prompt.attachments,
                        context.applicationContext, Router.Lane.CHAT, model, allowFolax = false, provider = "GEMINI") { delta ->
                        emit(geminiChunk(model, delta, null))
                    }
                }
                emit(geminiChunk(model, "", "STOP"))
                output.write("]".toByteArray(Charsets.UTF_8))
            } catch (e: Throwable) {
                runCatching {
                    emit(JSONObject().put("error", JSONObject().put("message", e.message?.take(500) ?: "Gemini request failed.")))
                    output.write("]".toByteArray(Charsets.UTF_8))
                }
            } finally {
                prompt.attachments.forEach { it.file.delete() }
                runCatching { output.close() }
            }
        }, "atlas-gemini-stream").apply { isDaemon = true; start() }
        return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8", input)
            .apply { addHeader("Cache-Control", "no-cache"); addHeader("X-Accel-Buffering", "no") }
    }

    private fun geminiChunk(model: String, text: String, finishReason: String?): JSONObject {
        val candidate = JSONObject().put("content", JSONObject().put("role", "model")
            .put("parts", if (text.isBlank()) JSONArray() else JSONArray().put(JSONObject().put("text", text))))
            .put("index", 0)
        if (finishReason != null) candidate.put("finishReason", finishReason)
        return JSONObject().put("candidates", JSONArray().put(candidate)).put("modelVersion", model)
    }

    private fun chat(context: Context, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val request = body(session)
        val parsed = parseMessages(context, request.optJSONArray("messages")
            ?: throw IllegalArgumentException("messages must be an array."))
        val responseFormat = request.optJSONObject("response_format")?.optString("type").orEmpty()
        val system = (listOf(parsed.system) + if (responseFormat == "json_object") listOf("Return valid JSON only, without markdown fences.") else emptyList())
            .filter { it.isNotBlank() }.joinToString("\n\n")
        if (system.length > 120_000) {
            parsed.attachments.forEach { it.file.delete() }
            throw IllegalArgumentException("System instructions are too long.")
        }
        val model = request.optString("model", "auto").ifBlank { "auto" }
        val maxTokens = request.optInt("max_tokens", 1200).coerceIn(1, 8192)
        if (parsed.text.isBlank() && parsed.attachments.isEmpty()) throw IllegalArgumentException("messages must contain text or an image.")
        val rawTools = request.optJSONArray("tools")
        if (rawTools != null && rawTools.length() > 0) {
            if (isFusionModel(model)) {
                parsed.attachments.forEach { it.file.delete() }
                throw RequestError(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "Function calling is not supported for the fusion model yet.")
            }
            val tools = checkedTools(rawTools)
            if (parsed.attachments.isNotEmpty()) {
                parsed.attachments.forEach { it.file.delete() }
                throw RequestError(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "Function calls with image attachments are not yet supported.")
            }
            if (request.optBoolean("stream")) return streamChatToolRequest(context, parsed, system, model, maxTokens, tools, request.opt("tool_choice"))
            return try {
                val result = runToolRequest(context, parsed, system, model, maxTokens, tools, request.opt("tool_choice"))
                val message = JSONObject().put("role", "assistant")
                    .put("content", result.text.takeIf { it.isNotBlank() } ?: JSONObject.NULL)
                if (result.calls.isNotEmpty()) message.put("tool_calls", JSONArray().apply { result.calls.forEach(::put) })
                json(NanoHTTPD.Response.Status.OK, JSONObject()
                    .put("id", "chatcmpl-${UUID.randomUUID().toString().replace("-", "")}")
                    .put("object", "chat.completion").put("created", System.currentTimeMillis() / 1000)
                    .put("model", result.model).put("choices", JSONArray().put(JSONObject()
                        .put("index", 0).put("message", message)
                        .put("finish_reason", if (result.calls.isNotEmpty()) "tool_calls" else result.finishReason))))
            } finally {
                parsed.attachments.forEach { it.file.delete() }
            }
        }
        if (request.optBoolean("stream")) {
            if (isFusionModel(model) && parsed.attachments.isNotEmpty()) {
                parsed.attachments.forEach { it.file.delete() }
                throw RequestError(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "Multimodal requests are not supported for the fusion model yet.")
            }
            return streamChat(context, parsed, system, model, maxTokens, fusionOptions(request))
        }

        return try {
            val answer = runText(context, parsed, system, model, maxTokens, fusion = fusionOptions(request))
            val id = "chatcmpl-${UUID.randomUUID().toString().replace("-", "")}"
            val message = JSONObject().put("role", "assistant").put("content", answer.text)
            val payload = JSONObject()
                .put("id", id).put("object", "chat.completion").put("created", System.currentTimeMillis() / 1000)
                .put("model", answer.model).put("choices", JSONArray().put(JSONObject()
                    .put("index", 0).put("message", message).put("finish_reason", "stop")))
            answer.fusion?.let { payload.put("x_fusion", it) }
            json(NanoHTTPD.Response.Status.OK, payload)
        } finally {
            parsed.attachments.forEach { it.file.delete() }
        }
    }

    private fun completion(context: Context, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val request = body(session)
        val prompt = request.optString("prompt").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("prompt must be a non-empty string.")
        val model = request.optString("model", "auto").ifBlank { "auto" }
        val maxTokens = request.optInt("max_tokens", 1200).coerceIn(1, 8192)
        if (prompt.length > 120_000) throw IllegalArgumentException("The request text is too long.")
        if (request.optBoolean("stream")) {
            return streamCompletion(context, prompt, model, maxTokens, fusionOptions(request))
        }
        val answer = runText(context, Prompt("", prompt, emptyList()), "", model, maxTokens, fusion = fusionOptions(request))
        val payload = JSONObject()
            .put("id", "cmpl-${UUID.randomUUID().toString().replace("-", "")}")
            .put("object", "text_completion").put("created", System.currentTimeMillis() / 1000)
            .put("model", answer.model).put("choices", JSONArray().put(JSONObject()
                .put("text", answer.text).put("index", 0).put("finish_reason", "stop")))
        answer.fusion?.let { payload.put("x_fusion", it) }
        return json(NanoHTTPD.Response.Status.OK, payload)
    }

    private data class Answer(val text: String, val model: String, val fusion: JSONObject? = null)
    private data class ToolAnswer(val text: String, val calls: List<JSONObject>, val finishReason: String, val model: String)

    private fun runText(
        context: Context, prompt: Prompt, system: String, model: String, maxTokens: Int,
        provider: String? = null, fusion: JSONObject? = null
    ): Answer {
        if (isFusionModel(model)) {
            if (provider != null) throw IllegalArgumentException("The fusion model is available only through chat-compatible routes.")
            if (chatEndpoints(context).none(::supportsFusion)) {
                throw RequestError(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, "Fusion needs a configured remote chat provider.")
            }
            if (prompt.attachments.isNotEmpty()) {
                throw RequestError(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "Multimodal requests are not supported for the fusion model yet.")
            }
            val result = runBlocking { LaptopFusion.run(context, prompt.text, system, maxTokens, fusion) }
            return Answer(result.text, "fusion", result.metadata)
        }
        val app = context.applicationContext as? NizamApplication ?: throw IllegalStateException("Atlas is not ready.")
        val servedModel = arrayOf(model)
        val text = runBlocking {
            Streaming.reply(app.container.settingsRepository, prompt.text, system, maxTokens, prompt.attachments,
                context.applicationContext, Router.Lane.CHAT, model, allowFolax = false,
                provider = provider,
                onRouted = { servedModel[0] = it.model }) { }
        }
        return Answer(text, servedModel[0])
    }

    private suspend fun streamReply(
        context: Context,
        prompt: Prompt,
        system: String,
        maxTokens: Int,
        model: String,
        fusion: JSONObject? = null,
        onRouted: (String) -> Unit = {},
        onDelta: (String) -> Unit
    ): String {
        if (isFusionModel(model)) {
            if (chatEndpoints(context).none(::supportsFusion)) {
                throw RequestError(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, "Fusion needs a configured remote chat provider.")
            }
            if (fusion?.optBoolean("expose_panel", false) == true) {
                throw RequestError(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "Streaming panel metadata is not supported for the fusion model yet.")
            }
            if (prompt.attachments.isNotEmpty()) {
                throw RequestError(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "Multimodal requests are not supported for the fusion model yet.")
            }
            onRouted("fusion")
            return LaptopFusion.run(context, prompt.text, system, maxTokens, fusion, onDelta).text
        }
        val app = context.applicationContext as? NizamApplication ?: throw IllegalStateException("Atlas is not ready.")
        return Streaming.reply(
            app.container.settingsRepository, prompt.text, system, maxTokens, prompt.attachments,
            context.applicationContext, Router.Lane.CHAT, model, allowFolax = false,
            onRouted = { onRouted(it.model) }, onDelta = onDelta
        )
    }

    private fun fusionOptions(request: JSONObject): JSONObject? {
        if (!request.has("fusion") || request.isNull("fusion")) return null
        return request.optJSONObject("fusion")
            ?: throw IllegalArgumentException("fusion must be a JSON object.")
    }

    private fun isFusionModel(model: String): Boolean = model.equals("fusion", true)

    private fun supportsFusion(endpoint: Router.Endpoint): Boolean =
        endpoint.url.isNotBlank() && (endpoint.kind.equals("OPENAI", true) || endpoint.kind.equals("GEMINI", true))

    private fun checkedTools(raw: JSONArray?): JSONArray {
        if (raw == null || raw.length() == 0) throw IllegalArgumentException("At least one function tool is required.")
        if (raw.length() > 64) throw IllegalArgumentException("At most 64 function tools are supported.")
        for (i in 0 until raw.length()) {
            val tool = raw.optJSONObject(i) ?: throw IllegalArgumentException("Each tool must be an object.")
            val fn = tool.optJSONObject("function") ?: throw IllegalArgumentException("Only function tools are supported.")
            if (fn.optString("name").isBlank()) throw IllegalArgumentException("Each function tool needs a name.")
            if (!fn.has("parameters") || fn.opt("parameters") !is JSONObject) {
                throw IllegalArgumentException("Each function tool needs an object schema in parameters.")
            }
        }
        return raw
    }

    private fun anthropicTools(raw: JSONArray): JSONArray {
        val tools = JSONArray()
        for (i in 0 until raw.length()) {
            val tool = raw.optJSONObject(i) ?: throw IllegalArgumentException("Each tool must be an object.")
            val name = tool.optString("name")
            val schema = tool.optJSONObject("input_schema")
            if (name.isBlank() || schema == null) throw IllegalArgumentException("Each Anthropic tool needs name and input_schema.")
            tools.put(functionTool(name, tool.optString("description"), schema))
        }
        return checkedTools(tools)
    }

    private fun anthropicToolChoice(choice: Any?): Any? = when (choice) {
        null, JSONObject.NULL -> "auto"
        is String -> when (choice) {
            "auto" -> "auto"
            "any" -> "required"
            "none" -> "none"
            else -> throw IllegalArgumentException("Unsupported Anthropic tool_choice.")
        }
        is JSONObject -> when (choice.optString("type")) {
            "auto" -> "auto"
            "any" -> "required"
            "tool" -> JSONObject().put("type", "function")
                .put("function", JSONObject().put("name", choice.optString("name")))
            else -> throw IllegalArgumentException("Unsupported Anthropic tool_choice.")
        }
        else -> throw IllegalArgumentException("Unsupported Anthropic tool_choice.")
    }

    private fun responseTools(raw: JSONArray): JSONArray {
        val tools = JSONArray()
        for (i in 0 until raw.length()) {
            val tool = raw.optJSONObject(i) ?: throw IllegalArgumentException("Each tool must be an object.")
            if (tool.optString("type") != "function") throw IllegalArgumentException("Only function tools are supported.")
            tools.put(functionTool(tool.optString("name"), tool.optString("description"), tool.optJSONObject("parameters")
                ?: throw IllegalArgumentException("Each function tool needs an object schema in parameters.")))
        }
        return checkedTools(tools)
    }

    private fun functionTool(name: String, description: String, parameters: JSONObject) =
        JSONObject().put("type", "function").put("function", JSONObject()
            .put("name", name).put("description", description).put("parameters", parameters))

    private fun runToolRequest(
        context: Context,
        prompt: Prompt,
        system: String,
        model: String,
        maxTokens: Int,
        tools: JSONArray,
        toolChoice: Any?
    ): ToolAnswer {
        val app = context.applicationContext as? NizamApplication ?: throw IllegalStateException("Atlas is not ready.")
        val all = runBlocking { Router.endpoints(app.container.settingsRepository, context.applicationContext, Router.Lane.CHAT) }
            .filter { it.kind == "GEMINI" || it.kind == "OPENAI" }
        val wanted = model.trim().takeUnless { it.isBlank() || it.equals("auto", true) || it.startsWith("auto:", true) }
        val matching = if (wanted == null) all else all.filter { it.model.equals(wanted, true) || it.name.equals(wanted, true) }
        if (all.isEmpty()) throw IllegalStateException("No tool-capable AI provider is configured in Atlas.")
        if (matching.isEmpty()) throw IllegalArgumentException("Model “$wanted” is not available for function calling.")
        val endpoints = Health.order(matching).take(8)
        var first: Throwable? = null
        for (endpoint in endpoints) {
            try {
                return if (endpoint.kind == "GEMINI") {
                    runGeminiToolRequest(endpoint, prompt.text, system, maxTokens, tools, toolChoice)
                } else {
                    runOpenAiToolRequest(endpoint, prompt.text, system, maxTokens, tools, toolChoice)
                }
            } catch (e: Exception) {
                Health.fail(endpoint, e)
                if (first == null) first = e
            }
        }
        throw first ?: IllegalStateException("No provider answered the tool request.")
    }

    private fun runOpenAiToolRequest(
        endpoint: Router.Endpoint, prompt: String, system: String, maxTokens: Int, tools: JSONArray, toolChoice: Any?
    ): ToolAnswer {
        val messages = JSONArray()
        if (system.isNotBlank()) messages.put(JSONObject().put("role", "system").put("content", system))
        messages.put(JSONObject().put("role", "user").put("content", prompt))
        val request = JSONObject().put("model", endpoint.model).put("messages", messages)
            .put("tools", tools).put("max_tokens", endpoint.cap(maxTokens))
        toolChoice?.let { request.put("tool_choice", it) }
        val headers = if (endpoint.key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${endpoint.key}")
        val response = JSONObject(runBlocking { Http.postJson(endpoint.url, request.toString(), headers) })
        val choice = response.optJSONArray("choices")?.optJSONObject(0)
            ?: throw IllegalStateException("Provider returned no tool-call choice.")
        val message = choice.optJSONObject("message") ?: throw IllegalStateException("Provider returned no assistant message.")
        val rawCalls = message.optJSONArray("tool_calls")
        val calls = mutableListOf<JSONObject>()
        if (rawCalls != null) for (i in 0 until rawCalls.length()) {
            val call = rawCalls.optJSONObject(i) ?: continue
            val fn = call.optJSONObject("function") ?: continue
            val args = fn.opt("arguments")
            val arguments = if (args is String) args else (args as? JSONObject)?.toString() ?: "{}"
            calls += JSONObject().put("id", call.optString("id").ifBlank { "call_${UUID.randomUUID()}" })
                .put("type", "function").put("function", JSONObject()
                    .put("name", fn.optString("name")).put("arguments", arguments))
        }
        val reason = choice.optString("finish_reason").ifBlank { if (calls.isNotEmpty()) "tool_calls" else "stop" }
        return ToolAnswer(message.optString("content").takeUnless { it == "null" }.orEmpty(), calls, reason, endpoint.model)
    }

    private fun runGeminiToolRequest(
        endpoint: Router.Endpoint, prompt: String, system: String, maxTokens: Int, tools: JSONArray, toolChoice: Any?
    ): ToolAnswer {
        val declarations = JSONArray()
        for (i in 0 until tools.length()) {
            val fn = tools.getJSONObject(i).getJSONObject("function")
            declarations.put(JSONObject().put("name", fn.optString("name")).put("description", fn.optString("description"))
                .put("parameters", fn.optJSONObject("parameters") ?: JSONObject().put("type", "object")))
        }
        val request = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
            .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens))
        if (system.isNotBlank()) request.put("systemInstruction", JSONObject()
            .put("parts", JSONArray().put(JSONObject().put("text", system))))
        val choice = when (toolChoice) {
            is String -> toolChoice
            is JSONObject -> "required"
            else -> "auto"
        }
        val mode = when (choice.lowercase()) {
            "none" -> "NONE"
            "required", "any" -> "ANY"
            else -> "AUTO"
        }
        val toolConfig = JSONObject().put("functionCallingConfig", JSONObject().put("mode", mode))
        if (toolChoice is JSONObject) {
            val fn = toolChoice.optJSONObject("function")?.optString("name").orEmpty()
            if (fn.isNotBlank()) toolConfig.getJSONObject("functionCallingConfig")
                .put("allowedFunctionNames", JSONArray().put(fn))
        }
        request.put("toolConfig", toolConfig)
        val url = "${endpoint.url}/${endpoint.model}:generateContent?key=${URLEncoder.encode(endpoint.key, "UTF-8")}"
        val response = JSONObject(runBlocking { Http.postJson(url, request.toString()) })
        val parts = response.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException(response.optJSONObject("error")?.optString("message") ?: "Gemini returned no tool response.")
        val calls = mutableListOf<JSONObject>()
        val text = StringBuilder()
        for (i in 0 until parts.length()) {
            val part = parts.optJSONObject(i) ?: continue
            val fn = part.optJSONObject("functionCall")
            if (fn == null) text.append(part.optString("text"))
            else calls += JSONObject().put("id", "call_${UUID.randomUUID()}")
                .put("type", "function").put("function", JSONObject()
                    .put("name", fn.optString("name")).put("arguments", fn.optJSONObject("args")?.toString() ?: "{}"))
        }
        return ToolAnswer(text.toString().trim(), calls, if (calls.isNotEmpty()) "tool_calls" else "stop", endpoint.model)
    }

    private fun responses(context: Context, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val request = body(session)
        val parsed = parseInput(context, request.opt("input"))
        val instructions = request.optString("instructions")
        val system = listOf(parsed.system, instructions).filter { it.isNotBlank() }.joinToString("\n\n")
        if (system.length > 120_000) {
            parsed.attachments.forEach { it.file.delete() }
            throw IllegalArgumentException("System instructions are too long.")
        }
        val model = request.optString("model", "auto").ifBlank { "auto" }
        val maxTokens = request.optInt("max_output_tokens", 1200).coerceIn(1, 8192)
        if (parsed.text.isBlank() && parsed.attachments.isEmpty()) throw IllegalArgumentException("input must contain text or an image.")
        val rawTools = request.optJSONArray("tools")
        if (rawTools != null && rawTools.length() > 0) {
            if (isFusionModel(model)) {
                parsed.attachments.forEach { it.file.delete() }
                throw RequestError(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "Function calling is not supported for the fusion model yet.")
            }
            val tools = try { responseTools(rawTools) } catch (e: Exception) {
                parsed.attachments.forEach { it.file.delete() }; throw e
            }
            if (parsed.attachments.isNotEmpty()) {
                parsed.attachments.forEach { it.file.delete() }
                throw RequestError(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "Function calls with image attachments are not yet supported.")
            }
            if (request.optBoolean("stream")) return streamResponsesToolRequest(context, parsed, system, model, maxTokens, tools, request.opt("tool_choice"))
            return try {
                val result = runToolRequest(context, parsed, system, model, maxTokens, tools, request.opt("tool_choice"))
                val output = JSONArray()
                if (result.text.isNotBlank()) output.put(JSONObject().put("id", "msg_${UUID.randomUUID()}")
                    .put("type", "message").put("status", "completed").put("role", "assistant")
                    .put("content", JSONArray().put(JSONObject().put("type", "output_text")
                        .put("text", result.text).put("annotations", JSONArray()))))
                result.calls.forEach { call ->
                    val fn = call.getJSONObject("function")
                    output.put(JSONObject().put("id", "fc_${UUID.randomUUID()}")
                        .put("type", "function_call").put("status", "completed")
                        .put("call_id", call.optString("id")).put("name", fn.optString("name"))
                        .put("arguments", fn.optString("arguments", "{}")))
                }
                json(NanoHTTPD.Response.Status.OK, JSONObject()
                    .put("id", "resp_${UUID.randomUUID().toString().replace("-", "")}")
                    .put("object", "response").put("created_at", System.currentTimeMillis() / 1000)
                    .put("status", "completed").put("model", result.model).put("output", output)
                    .put("output_text", result.text))
            } finally {
                parsed.attachments.forEach { it.file.delete() }
            }
        }
        if (request.optBoolean("stream")) {
            if (isFusionModel(model) && parsed.attachments.isNotEmpty()) {
                parsed.attachments.forEach { it.file.delete() }
                throw RequestError(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "Multimodal requests are not supported for the fusion model yet.")
            }
            return streamResponses(context, parsed, system, model, maxTokens, fusionOptions(request))
        }
        return try {
            val answer = runText(context, parsed, system, model, maxTokens, fusion = fusionOptions(request))
            val itemId = "msg_${UUID.randomUUID().toString().replace("-", "")}"
            val outputText = JSONObject().put("type", "output_text").put("text", answer.text).put("annotations", JSONArray())
            val message = JSONObject().put("id", itemId).put("type", "message").put("status", "completed")
                .put("role", "assistant").put("content", JSONArray().put(outputText))
            val payload = JSONObject()
                .put("id", "resp_${UUID.randomUUID().toString().replace("-", "")}")
                .put("object", "response").put("created_at", System.currentTimeMillis() / 1000)
                .put("status", "completed").put("model", answer.model).put("output", JSONArray().put(message))
                .put("output_text", answer.text)
            answer.fusion?.let { payload.put("x_fusion", it) }
            json(NanoHTTPD.Response.Status.OK, payload)
        } finally {
            parsed.attachments.forEach { it.file.delete() }
        }
    }

    private fun anthropicMessages(context: Context, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val request = body(session)
        val parsed = parseMessages(context, request.optJSONArray("messages")
            ?: throw IllegalArgumentException("messages must be an array."))
        val systemAttachments = mutableListOf<Attachment>()
        val systemText = try {
            contentText(context, request.opt("system"), systemAttachments)
        } catch (e: Exception) {
            systemAttachments.forEach { it.file.delete() }
            parsed.attachments.forEach { it.file.delete() }
            throw e
        }
        if (systemAttachments.isNotEmpty()) {
            systemAttachments.forEach { it.file.delete() }
            parsed.attachments.forEach { it.file.delete() }
            throw IllegalArgumentException("Images must be attached to a user message, not system instructions.")
        }
        val system = listOf(systemText, parsed.system).filter { it.isNotBlank() }.joinToString("\n\n")
        if (system.length > 120_000) {
            parsed.attachments.forEach { it.file.delete() }
            throw IllegalArgumentException("System instructions are too long.")
        }
        val model = request.optString("model", "auto").ifBlank { "auto" }
        val maxTokens = request.optInt("max_tokens", 1200).coerceIn(1, 8192)
        if (parsed.text.isBlank() && parsed.attachments.isEmpty()) {
            parsed.attachments.forEach { it.file.delete() }
            throw IllegalArgumentException("messages must contain text or an image.")
        }
        val rawTools = request.optJSONArray("tools")
        if (rawTools != null && rawTools.length() > 0) {
            if (isFusionModel(model)) {
                parsed.attachments.forEach { it.file.delete() }
                throw RequestError(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "Function calling is not supported for the fusion model yet.")
            }
            val tools = try { anthropicTools(rawTools) } catch (e: Exception) {
                parsed.attachments.forEach { it.file.delete() }; throw e
            }
            if (parsed.attachments.isNotEmpty()) {
                parsed.attachments.forEach { it.file.delete() }
                throw RequestError(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "Function calls with image attachments are not yet supported.")
            }
            if (request.optBoolean("stream")) return streamAnthropicToolRequest(context, parsed, system, model, maxTokens, tools,
                anthropicToolChoice(request.opt("tool_choice")))
            return try {
                val result = runToolRequest(context, parsed, system, model, maxTokens, tools,
                    anthropicToolChoice(request.opt("tool_choice")))
                val content = JSONArray()
                if (result.text.isNotBlank()) content.put(JSONObject().put("type", "text").put("text", result.text))
                result.calls.forEach { call ->
                    val fn = call.getJSONObject("function")
                    val args = runCatching { JSONObject(fn.optString("arguments", "{}")) }.getOrElse { JSONObject() }
                    content.put(JSONObject().put("type", "tool_use").put("id", call.optString("id"))
                        .put("name", fn.optString("name")).put("input", args))
                }
                json(NanoHTTPD.Response.Status.OK, JSONObject()
                    .put("id", "msg_${UUID.randomUUID().toString().replace("-", "")}")
                    .put("type", "message").put("role", "assistant").put("model", result.model)
                    .put("content", content).put("stop_reason", if (result.calls.isNotEmpty()) "tool_use" else "end_turn")
                    .put("stop_sequence", JSONObject.NULL))
            } finally {
                parsed.attachments.forEach { it.file.delete() }
            }
        }
        if (request.optBoolean("stream")) {
            if (isFusionModel(model) && parsed.attachments.isNotEmpty()) {
                parsed.attachments.forEach { it.file.delete() }
                throw RequestError(NanoHTTPD.Response.Status.NOT_IMPLEMENTED, "Multimodal requests are not supported for the fusion model yet.")
            }
            return streamAnthropic(context, parsed, system, model, maxTokens, fusionOptions(request))
        }
        return try {
            val answer = runText(context, parsed, system, model, maxTokens, fusion = fusionOptions(request))
            val payload = JSONObject()
                .put("id", "msg_${UUID.randomUUID().toString().replace("-", "")}")
                .put("type", "message").put("role", "assistant").put("model", answer.model)
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", answer.text)))
                .put("stop_reason", "end_turn").put("stop_sequence", JSONObject.NULL)
            answer.fusion?.let { payload.put("x_fusion", it) }
            json(NanoHTTPD.Response.Status.OK, payload)
        } finally {
            parsed.attachments.forEach { it.file.delete() }
        }
    }

    private fun parseInput(context: Context, input: Any?): Prompt = when (input) {
        is String -> {
            if (input.length > 120_000) throw IllegalArgumentException("The request text is too long.")
            Prompt("", "User: $input", emptyList())
        }
        is JSONArray -> parseMessages(context, input)
        else -> throw IllegalArgumentException("input must be a string or an array of messages.")
    }

    private fun streamResponses(
        context: Context, prompt: Prompt, system: String, model: String, maxTokens: Int, fusion: JSONObject? = null
    ): NanoHTTPD.Response {
        val responseId = "resp_${UUID.randomUUID().toString().replace("-", "")}"
        val itemId = "msg_${UUID.randomUUID().toString().replace("-", "")}"
        return eventStream(prompt.attachments, "error") { output ->
            var servedModel = model
            var sequence = 0
            val text = StringBuilder()
            fun emit(type: String, data: JSONObject) {
                data.put("type", type).put("sequence_number", sequence++)
                writeEvent(output, type, data.toString())
            }
            fun response(status: String, outputItem: JSONObject? = null) = JSONObject()
                .put("id", responseId).put("object", "response").put("created_at", System.currentTimeMillis() / 1000)
                .put("status", status).put("model", servedModel)
                .put("output", JSONArray().apply { outputItem?.let { put(it) } })
            fun item(status: String, value: String? = null): JSONObject {
                val content = JSONArray()
                if (value != null) content.put(JSONObject().put("type", "output_text").put("text", value).put("annotations", JSONArray()))
                return JSONObject().put("id", itemId).put("type", "message").put("status", status)
                    .put("role", "assistant").put("content", content)
            }

            emit("response.created", JSONObject().put("response", response("in_progress")))
            emit("response.in_progress", JSONObject().put("response", response("in_progress")))
            var itemAdded = false
            var partAdded = false
            val answer = runBlocking {
                streamReply(context, prompt, system, maxTokens, model, fusion,
                    onRouted = { servedModel = it }) { delta ->
                    if (!itemAdded) {
                        emit("response.output_item.added", JSONObject().put("output_index", 0).put("item", item("in_progress")))
                        itemAdded = true
                    }
                    if (!partAdded) {
                        emit("response.content_part.added", JSONObject().put("item_id", itemId).put("output_index", 0)
                            .put("content_index", 0).put("part", JSONObject().put("type", "output_text")
                                .put("text", "").put("annotations", JSONArray())))
                        partAdded = true
                    }
                    text.append(delta)
                    emit("response.output_text.delta", JSONObject().put("item_id", itemId).put("output_index", 0)
                        .put("content_index", 0).put("delta", delta))
                }
            }
            if (!itemAdded) emit("response.output_item.added", JSONObject().put("output_index", 0).put("item", item("in_progress")))
            if (!partAdded) emit("response.content_part.added", JSONObject().put("item_id", itemId).put("output_index", 0)
                .put("content_index", 0).put("part", JSONObject().put("type", "output_text")
                    .put("text", "").put("annotations", JSONArray())))
            val finalText = if (answer.isNotEmpty()) answer else text.toString()
            val finalItem = item("completed", finalText)
            val finalPart = finalItem.getJSONArray("content").getJSONObject(0)
            emit("response.output_text.done", JSONObject().put("item_id", itemId).put("output_index", 0)
                .put("content_index", 0).put("text", finalText))
            emit("response.content_part.done", JSONObject().put("item_id", itemId).put("output_index", 0)
                .put("content_index", 0).put("part", finalPart))
            emit("response.output_item.done", JSONObject().put("output_index", 0).put("item", finalItem))
            emit("response.completed", JSONObject().put("response", response("completed", finalItem)))
        }
    }

    private fun streamAnthropic(
        context: Context, prompt: Prompt, system: String, model: String, maxTokens: Int, fusion: JSONObject? = null
    ): NanoHTTPD.Response {
        val messageId = "msg_${UUID.randomUUID().toString().replace("-", "")}"
        return eventStream(prompt.attachments, "error") { output ->
            var servedModel = model
            var started = false
            fun emit(type: String, data: JSONObject) = writeEvent(output, type, data.put("type", type).toString())
            fun startMessage() {
                if (started) return
                emit("message_start", JSONObject().put("message", JSONObject().put("id", messageId)
                    .put("type", "message").put("role", "assistant").put("model", servedModel)
                    .put("content", JSONArray()).put("stop_reason", JSONObject.NULL).put("stop_sequence", JSONObject.NULL)))
                emit("content_block_start", JSONObject().put("index", 0)
                    .put("content_block", JSONObject().put("type", "text").put("text", "")))
                started = true
            }
            val answer = runBlocking {
                streamReply(context, prompt, system, maxTokens, model, fusion,
                    onRouted = { servedModel = it }) { delta ->
                    startMessage()
                    emit("content_block_delta", JSONObject().put("index", 0)
                        .put("delta", JSONObject().put("type", "text_delta").put("text", delta)))
                }
            }
            startMessage()
            emit("content_block_stop", JSONObject().put("index", 0))
            emit("message_delta", JSONObject().put("delta", JSONObject().put("stop_reason", "end_turn")
                .put("stop_sequence", JSONObject.NULL)))
            emit("message_stop", JSONObject())
        }
    }

    private fun streamChatToolRequest(
        context: Context, prompt: Prompt, system: String, model: String, maxTokens: Int, tools: JSONArray, toolChoice: Any?
    ): NanoHTTPD.Response {
        val id = "chatcmpl-${UUID.randomUUID().toString().replace("-", "")}"
        val created = System.currentTimeMillis() / 1000
        return openAiDataStream(prompt.attachments) { output ->
            val result = runToolRequest(context, prompt, system, model, maxTokens, tools, toolChoice)
            fun emit(choice: JSONObject) = writeDataEvent(output, JSONObject().put("id", id)
                .put("object", "chat.completion.chunk").put("created", created).put("model", result.model)
                .put("choices", JSONArray().put(choice)).toString())
            if (result.text.isNotBlank()) emit(JSONObject().put("index", 0).put("delta", JSONObject().put("content", result.text))
                .put("finish_reason", JSONObject.NULL))
            result.calls.forEachIndexed { index, call ->
                val fn = call.getJSONObject("function")
                val delta = JSONObject().put("tool_calls", JSONArray().put(JSONObject().put("index", index)
                    .put("id", call.optString("id")).put("type", "function")
                    .put("function", JSONObject().put("name", fn.optString("name"))
                        .put("arguments", fn.optString("arguments", "{}")))))
                emit(JSONObject().put("index", 0).put("delta", delta).put("finish_reason", JSONObject.NULL))
            }
            emit(JSONObject().put("index", 0).put("delta", JSONObject())
                .put("finish_reason", if (result.calls.isNotEmpty()) "tool_calls" else result.finishReason))
            writeDataEvent(output, "[DONE]")
        }
    }

    private fun streamResponsesToolRequest(
        context: Context, prompt: Prompt, system: String, model: String, maxTokens: Int, tools: JSONArray, toolChoice: Any?
    ): NanoHTTPD.Response {
        val responseId = "resp_${UUID.randomUUID().toString().replace("-", "")}"
        return eventStream(prompt.attachments, "error") { output ->
            var sequence = 0
            fun emit(type: String, data: JSONObject) {
                data.put("type", type).put("sequence_number", sequence++)
                writeEvent(output, type, data.toString())
            }
            val result = runToolRequest(context, prompt, system, model, maxTokens, tools, toolChoice)
            val items = JSONArray()
            fun response(status: String) = JSONObject().put("id", responseId).put("object", "response")
                .put("created_at", System.currentTimeMillis() / 1000).put("status", status)
                .put("model", result.model).put("output", items)
            emit("response.created", JSONObject().put("response", response("in_progress")))
            emit("response.in_progress", JSONObject().put("response", response("in_progress")))
            var outputIndex = 0
            if (result.text.isNotBlank()) {
                val itemId = "msg_${UUID.randomUUID().toString().replace("-", "")}"
                val part = JSONObject().put("type", "output_text").put("text", result.text).put("annotations", JSONArray())
                val item = JSONObject().put("id", itemId).put("type", "message").put("status", "completed")
                    .put("role", "assistant").put("content", JSONArray().put(part))
                items.put(item)
                emit("response.output_item.added", JSONObject().put("output_index", outputIndex).put("item", item))
                emit("response.content_part.added", JSONObject().put("item_id", itemId).put("output_index", outputIndex)
                    .put("content_index", 0).put("part", JSONObject().put("type", "output_text")
                        .put("text", "").put("annotations", JSONArray())))
                emit("response.output_text.delta", JSONObject().put("item_id", itemId).put("output_index", outputIndex)
                    .put("content_index", 0).put("delta", result.text))
                emit("response.output_text.done", JSONObject().put("item_id", itemId).put("output_index", outputIndex)
                    .put("content_index", 0).put("text", result.text))
                emit("response.content_part.done", JSONObject().put("item_id", itemId).put("output_index", outputIndex)
                    .put("content_index", 0).put("part", part))
                emit("response.output_item.done", JSONObject().put("output_index", outputIndex).put("item", item))
                outputIndex++
            }
            result.calls.forEach { call ->
                val fn = call.getJSONObject("function")
                val itemId = "fc_${UUID.randomUUID().toString().replace("-", "")}"
                val arguments = fn.optString("arguments", "{}")
                val item = JSONObject().put("id", itemId).put("type", "function_call").put("status", "completed")
                    .put("call_id", call.optString("id")).put("name", fn.optString("name")).put("arguments", arguments)
                items.put(item)
                emit("response.output_item.added", JSONObject().put("output_index", outputIndex).put("item", item))
                emit("response.function_call_arguments.delta", JSONObject().put("item_id", itemId)
                    .put("output_index", outputIndex).put("delta", arguments))
                emit("response.function_call_arguments.done", JSONObject().put("item_id", itemId)
                    .put("output_index", outputIndex).put("arguments", arguments))
                emit("response.output_item.done", JSONObject().put("output_index", outputIndex).put("item", item))
                outputIndex++
            }
            emit("response.completed", JSONObject().put("response", response("completed")))
        }
    }

    private fun streamAnthropicToolRequest(
        context: Context, prompt: Prompt, system: String, model: String, maxTokens: Int,
        tools: JSONArray, toolChoice: Any?
    ): NanoHTTPD.Response {
        val messageId = "msg_${UUID.randomUUID().toString().replace("-", "")}"
        return eventStream(prompt.attachments, "error") { output ->
            val result = runToolRequest(context, prompt, system, model, maxTokens, tools, toolChoice)
            fun emit(type: String, data: JSONObject) = writeEvent(output, type, data.put("type", type).toString())
            emit("message_start", JSONObject().put("message", JSONObject().put("id", messageId)
                .put("type", "message").put("role", "assistant").put("model", result.model)
                .put("content", JSONArray()).put("stop_reason", JSONObject.NULL).put("stop_sequence", JSONObject.NULL)))
            var index = 0
            if (result.text.isNotBlank()) {
                emit("content_block_start", JSONObject().put("index", index)
                    .put("content_block", JSONObject().put("type", "text").put("text", "")))
                emit("content_block_delta", JSONObject().put("index", index)
                    .put("delta", JSONObject().put("type", "text_delta").put("text", result.text)))
                emit("content_block_stop", JSONObject().put("index", index++))
            }
            result.calls.forEach { call ->
                val fn = call.getJSONObject("function")
                val args = fn.optString("arguments", "{}")
                emit("content_block_start", JSONObject().put("index", index)
                    .put("content_block", JSONObject().put("type", "tool_use").put("id", call.optString("id"))
                        .put("name", fn.optString("name")).put("input", JSONObject())))
                emit("content_block_delta", JSONObject().put("index", index)
                    .put("delta", JSONObject().put("type", "input_json_delta").put("partial_json", args)))
                emit("content_block_stop", JSONObject().put("index", index++))
            }
            emit("message_delta", JSONObject().put("delta", JSONObject()
                .put("stop_reason", if (result.calls.isNotEmpty()) "tool_use" else "end_turn")
                .put("stop_sequence", JSONObject.NULL)))
            emit("message_stop", JSONObject())
        }
    }

    private fun openAiDataStream(attachments: List<Attachment>, action: (PipedOutputStream) -> Unit): NanoHTTPD.Response {
        val input = PipedInputStream(64 * 1024)
        val output = PipedOutputStream(input)
        Thread({
            try {
                action(output)
            } catch (e: Throwable) {
                runCatching { writeDataEvent(output, JSONObject().put("error", JSONObject()
                    .put("message", e.message?.take(500) ?: "Atlas could not complete the request.")
                    .put("type", "server_error")).toString()) }
                runCatching { writeDataEvent(output, "[DONE]") }
            } finally {
                attachments.forEach { it.file.delete() }
                runCatching { output.close() }
            }
        }, "atlas-openai-tool-stream").apply { isDaemon = true; start() }
        return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "text/event-stream", input)
            .apply { addHeader("Cache-Control", "no-cache"); addHeader("X-Accel-Buffering", "no") }
    }

    private fun writeDataEvent(output: PipedOutputStream, payload: String) {
        output.write("data: $payload\n\n".toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun eventStream(
        attachments: List<Attachment>,
        errorEvent: String,
        action: (PipedOutputStream) -> Unit
    ): NanoHTTPD.Response {
        val input = PipedInputStream(64 * 1024)
        val output = PipedOutputStream(input)
        Thread({
            try {
                action(output)
            } catch (e: Throwable) {
                runCatching {
                    val payload = JSONObject().put("type", "error")
                        .put("error", JSONObject().put("type", "server_error")
                            .put("message", e.message?.take(500) ?: "Atlas could not complete the request."))
                    writeEvent(output, errorEvent, payload.toString())
                }
            } finally {
                attachments.forEach { it.file.delete() }
                runCatching { output.close() }
            }
        }, "atlas-local-api-events").apply {
            isDaemon = true
            start()
        }
        return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "text/event-stream", input).apply {
            addHeader("Cache-Control", "no-cache")
            addHeader("X-Accel-Buffering", "no")
        }
    }

    private fun writeEvent(output: PipedOutputStream, event: String, payload: String) {
        output.write("event: $event\ndata: $payload\n\n".toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun streamChat(
        context: Context,
        prompt: Prompt,
        system: String,
        model: String,
        maxTokens: Int,
        fusion: JSONObject? = null
    ): NanoHTTPD.Response = streamText(context, prompt, system, model, maxTokens, completionFormat = false, fusion = fusion)

    private fun streamCompletion(
        context: Context, text: String, model: String, maxTokens: Int, fusion: JSONObject? = null
    ): NanoHTTPD.Response =
        streamText(context, Prompt("", text, emptyList()), "", model, maxTokens, completionFormat = true, fusion = fusion)

    private fun streamText(
        context: Context,
        prompt: Prompt,
        system: String,
        model: String,
        maxTokens: Int,
        completionFormat: Boolean,
        fusion: JSONObject? = null
    ): NanoHTTPD.Response {
        val id = (if (completionFormat) "cmpl-" else "chatcmpl-") + UUID.randomUUID().toString().replace("-", "")
        val created = System.currentTimeMillis() / 1000
        val input = PipedInputStream(64 * 1024)
        val output = PipedOutputStream(input)
        fun emit(payload: String) {
            output.write("data: $payload\n\n".toByteArray(Charsets.UTF_8))
            output.flush()
        }
        Thread({
            var servedModel = model
            try {
                runBlocking {
                    streamReply(context, prompt, system, maxTokens, model, fusion,
                        onRouted = { servedModel = it }) { delta ->
                        val choice = JSONObject().put("index", 0).put("finish_reason", JSONObject.NULL)
                        if (completionFormat) choice.put("text", delta) else choice.put("delta", JSONObject().put("content", delta))
                        emit(JSONObject().put("id", id)
                            .put("object", if (completionFormat) "text_completion" else "chat.completion.chunk")
                            .put("created", created).put("model", servedModel).put("choices", JSONArray().put(choice)).toString())
                    }
                }
                val finalChoice = JSONObject().put("index", 0).put("finish_reason", "stop")
                if (completionFormat) finalChoice.put("text", "") else finalChoice.put("delta", JSONObject())
                emit(JSONObject().put("id", id)
                    .put("object", if (completionFormat) "text_completion" else "chat.completion.chunk")
                    .put("created", created).put("model", servedModel).put("choices", JSONArray().put(finalChoice)).toString())
                emit("[DONE]")
            } catch (e: Throwable) {
                runCatching {
                    emit(JSONObject().put("error", JSONObject().put("message", e.message?.take(500) ?: "Atlas could not complete the request.")
                        .put("type", "server_error")).toString())
                }
            } finally {
                prompt.attachments.forEach { it.file.delete() }
                runCatching { output.close() }
            }
        }, "atlas-local-api-stream").apply {
            isDaemon = true
            start()
        }
        return NanoHTTPD.newChunkedResponse(NanoHTTPD.Response.Status.OK, "text/event-stream", input).apply {
            addHeader("Cache-Control", "no-cache")
            addHeader("X-Accel-Buffering", "no")
        }
    }

    private fun parseMessages(context: Context, messages: JSONArray): Prompt {
        if (messages.length() == 0 || messages.length() > MAX_MESSAGES) throw IllegalArgumentException("messages must contain 1–$MAX_MESSAGES items.")
        val system = mutableListOf<String>()
        val transcript = mutableListOf<String>()
        val attachments = mutableListOf<Attachment>()
        try {
            for (i in 0 until messages.length()) {
                val message = messages.optJSONObject(i) ?: throw IllegalArgumentException("Each message must be an object.")
                val role = message.optString("role").lowercase()
                val text = contentText(context, message.opt("content"), attachments)
                when (role) {
                    "system", "developer" -> if (text.isNotBlank()) system += text
                    "user" -> transcript += "User: $text"
                    "assistant" -> transcript += "Assistant: $text"
                    "tool" -> transcript += "Tool result: $text"
                    else -> throw IllegalArgumentException("Unsupported message role: $role")
                }
                if (attachments.size > MAX_ATTACHMENTS) throw IllegalArgumentException("At most $MAX_ATTACHMENTS images are allowed per request.")
            }
            if (transcript.joinToString("\n\n").length > 120_000) throw IllegalArgumentException("The request text is too long.")
            return Prompt(system.joinToString("\n\n"), transcript.joinToString("\n\n"), attachments)
        } catch (e: Exception) {
            attachments.forEach { it.file.delete() }
            throw e
        }
    }

    private fun contentText(context: Context, content: Any?, attachments: MutableList<Attachment>): String = when (content) {
        is String -> content
        is JSONArray -> buildString {
            for (i in 0 until content.length()) {
                val part = content.optJSONObject(i) ?: continue
                when (part.optString("type")) {
                    "text", "input_text" -> appendLine(part.optString("text"))
                    "image" -> {
                        val source = part.optJSONObject("source") ?: throw IllegalArgumentException("Image content needs a base64 source.")
                        if (!source.optString("type").equals("base64", true)) throw IllegalArgumentException("Only base64 image content is supported.")
                        val mime = source.optString("media_type", "image/jpeg")
                        attachments += imageAttachment(context, "data:$mime;base64,${source.optString("data")}")
                    }
                    "image_url", "input_image" -> {
                        val image = part.optJSONObject("image_url")?.optString("url")
                            ?: part.optString("image_url").takeIf { it.isNotBlank() }
                            ?: part.optString("url")
                        attachments += imageAttachment(context, image)
                    }
                    else -> throw IllegalArgumentException("Unsupported message content type: ${part.optString("type")}")
                }
            }
        }.trim()
        null, JSONObject.NULL -> ""
        else -> throw IllegalArgumentException("message content must be text or an array of content parts.")
    }

    private fun imageAttachment(context: Context, dataUrl: String): Attachment {
        if (!dataUrl.startsWith("data:", true)) {
            throw IllegalArgumentException("Image URLs must be base64 data URLs; remote image URLs are not fetched by the phone.")
        }
        val comma = dataUrl.indexOf(',')
        if (comma < 0) throw IllegalArgumentException("Invalid image data URL.")
        val header = dataUrl.substring(5, comma)
        if (!header.endsWith(";base64", true)) throw IllegalArgumentException("Image data URL must use base64 encoding.")
        val mime = header.substringBefore(';').ifBlank { "image/jpeg" }
        if (!mime.startsWith("image/")) throw IllegalArgumentException("Only image data URLs are supported.")
        val encoded = dataUrl.substring(comma + 1)
        if (encoded.length > MAX_IMAGE_BYTES * 4 / 3 + 16) throw IllegalArgumentException("An image exceeds the 8 MB limit.")
        val bytes = try { Base64.decode(encoded, Base64.DEFAULT) }
        catch (_: IllegalArgumentException) { throw IllegalArgumentException("Invalid base64 image data.") }
        if (bytes.size > MAX_IMAGE_BYTES) throw IllegalArgumentException("An image exceeds the 8 MB limit.")
        val extension = when (mime.lowercase()) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            else -> "jpg"
        }
        val directory = File(context.cacheDir, "attach").apply { mkdirs() }
        val file = File(directory, "api_${UUID.randomUUID()}.$extension")
        file.writeBytes(bytes)
        return Attachment(file.name, mime, file, file.length())
    }

    private fun body(session: NanoHTTPD.IHTTPSession): JSONObject {
        val length = session.headers.entries.firstOrNull { it.key.equals("content-length", true) }?.value?.toLongOrNull() ?: 0L
        if (length > MAX_BODY_BYTES) throw RequestError(NanoHTTPD.Response.Status.BAD_REQUEST, "Request exceeds the 12 MB limit.")
        val fields = HashMap<String, String>()
        try {
            session.parseBody(fields)
        } catch (_: Exception) {
            throw RequestError(NanoHTTPD.Response.Status.BAD_REQUEST, "Request body could not be read.")
        }
        val raw = fields["postData"].orEmpty()
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) throw RequestError(NanoHTTPD.Response.Status.BAD_REQUEST, "Request exceeds the 12 MB limit.")
        if (raw.isBlank()) throw IllegalArgumentException("A JSON request body is required.")
        return try { JSONObject(raw) } catch (_: Exception) { throw IllegalArgumentException("Request body must be valid JSON.") }
    }

    private fun error(status: NanoHTTPD.Response.Status, message: String): NanoHTTPD.Response =
        json(status, JSONObject().put("error", JSONObject().put("message", message).put("type", "invalid_request_error")))

    private fun json(status: NanoHTTPD.Response.Status, payload: JSONObject): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8", payload.toString()).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
            if (status == NanoHTTPD.Response.Status.UNAUTHORIZED) addHeader("WWW-Authenticate", "Bearer")
        }
}