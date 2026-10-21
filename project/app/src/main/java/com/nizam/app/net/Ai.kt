package com.nizam.app.net

import com.nizam.app.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * One entry point for every AI call in the app.
 *
 * Handles the two things that actually break AI features in practice:
 *  - transient overload (HTTP 429/503/500) → exponential backoff and retry
 *  - a specific model being unavailable → fall through a chain of alternatives
 *
 * Supports Google Gemini and OpenRouter. OpenRouter's `openrouter/free` router picks a working
 * free model per request, which is the most reliable zero-cost option.
 */
object Ai {

    class MissingKeyException(message: String) : Exception(message)

    enum class Provider { GEMINI, OPENROUTER, CUSTOM }

    /** Ceilings that free tiers actually accept. Asking for 32k is what made create_goal fail. */
    private const val GEMINI_CEILING = 8192
    private const val OPENROUTER_CEILING = 4096

    /**
     * Every AI call goes through here: a repeat of a recent question is answered from memory, the same
     * question asked twice at once goes out once, background work waits its turn (and has a daily
     * allowance), and the request itself runs across every key and model you have until one answers.
     */
    suspend fun generate(
        settings: SettingsRepository,
        prompt: String,
        system: String? = null,
        maxOutputTokens: Int = 1200,
        useWeb: Boolean = false
    ): String {
        val bg = Engine.isBackground()
        val prompt = com.nizam.app.feature.agentic.Compact.prompt(Router.appContext, prompt)
        // the chosen language (Urdu, Roman Urdu) and the privacy shield apply to every request
        // what Atlas knows about you (and has learned), for things you asked for directly
        val model = if (bg) "" else runCatching { Router.appContext?.let { com.nizam.app.feature.brain2.PersonalModel.promptBlock(it) + com.nizam.app.feature.nx.Specials.soul(it).let { soul -> if (soul.isBlank()) "" else "\n\nHow the user wants you to be:\n$soul" } } ?: "" }.getOrDefault("")
        // if the user allowed it, the part of their own recorded life that matches this question
        val dataBlock = if (bg || prompt.contains("recorded data, shared by them")) "" else runCatching {
            Router.appContext?.let { com.nizam.app.feature.nx.Roads.chatContext(it, prompt) }
        }.getOrNull().orEmpty()
        // one mind: when a question needs an expert, Atlas thinks with that expertise (still Atlas, same memory and voice)
        val expertise = if (bg) "" else runCatching { com.nizam.app.feature.expertise.Expertise.autoBlock(Router.appContext, prompt, system) }.getOrDefault("")
        val laws = if (bg) "" else runCatching { com.nizam.app.feature.expertise.Laws.block(Router.appContext) }.getOrDefault("")
        val system = listOfNotNull(system, expertise.ifBlank { null }, laws.ifBlank { null }, model.ifBlank { null }, dataBlock.ifBlank { null }, com.nizam.app.feature.future.Lang.instruction().ifBlank { null }).joinToString("\n\n").ifBlank { null }
        val tag = kotlin.coroutines.coroutineContext[com.nizam.app.feature.wild.AgentMeter.Tag]?.name
        val expert = if (expertise.isBlank()) null else com.nizam.app.feature.expertise.Expertise.current.value
        val heavy = !bg && com.nizam.app.feature.expertise.Tiers.isHeavy(prompt, expert?.heavy == true)
        com.nizam.app.feature.agentic.AgentHalo.begin(tag?.let { "$it is working" } ?: if (bg) "Working in the background"
            else expert?.let { "Atlas is thinking as ${it.name}" } ?: "Atlas is thinking")
        if (!bg) com.nizam.app.feature.agentic.MemorySources.record(Router.appContext, prompt)
        try { return AnswerCache.get(AnswerCache.key(prompt, system, maxOutputTokens, useWeb), if (bg) 30 * 60_000L else if (useWeb) 60_000L else 3 * 60_000L) {
            com.nizam.app.feature.agentic.AgentHalo.check()
            if (bg && !Budget.allowBackground()) throw IllegalStateException("Atlas has used today's background AI allowance — it resets at midnight.")
            withContext(if (heavy) com.nizam.app.feature.expertise.Tiers.Heavy() else kotlin.coroutines.EmptyCoroutineContext) { Engine.limited {
                val list = com.nizam.app.feature.expertise.ZeroCost.filter(Router.appContext, com.nizam.app.feature.agentic.Hybrid.apply(com.nizam.app.feature.nx.Sensitivity.route(Router.appContext, prompt, Router.endpoints(settings, Router.appContext, Router.Lane.ACT)),
                    com.nizam.app.feature.agentic.Hybrid.mode(Router.appContext, tag)))
                if (list.isEmpty()) throw MissingKeyException("No AI provider set up. Add a key in Settings › API keys.")
                // too big for Groq's per-minute token limit? go straight to the long-context providers
                val sized = com.nizam.app.feature.expertise.Tiers.fitToSize(list, com.nizam.app.feature.expertise.Tiers.estimateTokens(prompt, system, maxOutputTokens))
                try {
                    Engine.run(sized) { e ->
                        // online sources get placeholders instead of numbers, IDs and emails; the phone's own model sees everything
                        if (e.kind == "LOCAL" || e.kind == "FOLAX" || !com.nizam.app.feature.future.Privacy.on()) call(e, prompt, system, maxOutputTokens, useWeb)
                        else { val m = com.nizam.app.feature.future.Privacy.Map()
                            com.nizam.app.feature.future.Privacy.restore(call(e, com.nizam.app.feature.future.Privacy.redact(prompt, m)!!,
                                com.nizam.app.feature.future.Privacy.redact(system, m), maxOutputTokens, useWeb), m) }
                    }
                } catch (t: Exception) {
                    if (t is kotlinx.coroutines.CancellationException || t is MissingKeyException) throw t
                    throw friendly(t)
                }
            } }
        } } finally { com.nizam.app.feature.agentic.AgentHalo.end() }
    }

    private fun tooManyTokens(e: Exception) = e.message.orEmpty().let { it.contains("token", true) && (it.contains("max", true) || it.contains("limit", true)) }

    /** One request to one endpoint, the way that endpoint wants it. */
    suspend fun call(e: Router.Endpoint, prompt: String, system: String?, maxOutputTokens: Int, useWeb: Boolean = false): String =
        callRaw(e, prompt, system, maxOutputTokens, useWeb).also { out ->
            // book the usage to whichever agent asked (the budget meter)
            runCatching { com.nizam.app.feature.wild.AgentMeter.record(Router.appContext,
                kotlin.coroutines.coroutineContext[com.nizam.app.feature.wild.AgentMeter.Tag]?.name, prompt.length + (system?.length ?: 0), out.length) }
        }

    private suspend fun callRaw(e: Router.Endpoint, prompt: String, system: String?, maxOutputTokens: Int, useWeb: Boolean = false): String = when (e.kind) {
        "LOCAL" -> LocalModel.generate(Router.appContext!!, prompt, system, maxOutputTokens)
        "FOLAX" -> com.nizam.app.feature.jarvis.Folax.ask(Router.appContext!!, prompt.takeLast(1500))
        "GEMINI" -> {
            val budget = maxOutputTokens.coerceAtMost(GEMINI_CEILING)
            try { Gemini.raw(e.key, e.model, prompt, system, budget, useWeb) }
            catch (x: Exception) { if (budget > 2048 && tooManyTokens(x)) Gemini.raw(e.key, e.model, prompt, system, 2048, useWeb) else throw x }
        }
        else -> {
            val or = e.url.contains("openrouter.ai")
            // ":free" and ":online" cannot be stacked — swap the suffix instead of appending
            val slug = when {
                !or || !useWeb -> e.model
                e.model.endsWith(":online") -> e.model
                e.model.endsWith(":free") -> e.model.removeSuffix(":free") + ":online"
                else -> "${e.model}:online"
            }
            val budget = e.cap(maxOutputTokens.coerceAtMost(if (or) OPENROUTER_CEILING else 8192))
            try { OpenRouter.raw(e.key, slug, prompt, system, budget, endpoint = e.url, referer = or) }
            catch (x: Exception) { if (budget > 1500 && tooManyTokens(x)) OpenRouter.raw(e.key, slug, prompt, system, 1500, endpoint = e.url, referer = or) else throw x }
        }
    }

    private fun friendly(error: Exception?, model: String = ""): Exception {
        if (error is MissingKeyException) return error
        val message = error?.message.orEmpty()
        val tag = if (model.isBlank()) "" else " [$model]" 
        return when {
            message.contains("503") || message.contains("UNAVAILABLE", true) ->
                Exception(
                    "Every source is busy right now — Atlas tried each key and model you have. Wait a minute, " +
                        "or add another free provider in Settings › API keys."
                )
            message.contains("429") ->
                Exception("Every key you have has hit its limit for now. Add a second key (comma-separated) or a free provider in Settings › API keys.")
            message.contains("401") || message.contains("403") ->
                Exception(
                    "That API key was rejected. For OpenRouter, check the key is active and that " +
                        "free models are enabled in your account privacy settings."
                )
            message.contains("404") ->
                Exception(
                    "That model doesn't exist for your key. Tap \"Fetch models for my key\" in " +
                        "Settings and pick one from the list."
                )
            message.contains("402") ->
                Exception("OpenRouter says this model needs credits. Pick a model ending in :free.")
            message.contains("token", true) && message.contains("max", true) ->
                Exception("The request asked for more output than this model allows$tag. " +
                    "Atlas retries smaller automatically — if you see this, the retry failed too.")
            message.isBlank() -> Exception(
                "${error?.javaClass?.simpleName ?: "Unknown error"}$tag — no message. " +
                    "If this says NetworkOnMainThreadException, it is a bug in Atlas, not your key."
            )
            // Show the provider's own words. Guessing at causes is what sent us chasing ghosts.
            else -> Exception("$tag ${message.take(400)}")
        }
    }

    /** Strips markdown fences so model output can be parsed as JSON. */
    fun cleanJson(raw: String): String {
        var s = Compat.clean(raw)
        if (s.startsWith("```")) {
            s = s.substringAfter('\n', "")
            s = s.substringBeforeLast("```")
        }
        val start = s.indexOfFirst { it == '{' || it == '[' }
        val end = s.indexOfLast { it == '}' || it == ']' }
        return if (start >= 0 && end > start) s.substring(start, end + 1) else s
    }

    /** Model lists for the picker in Settings. */
    suspend fun listModels(settings: SettingsRepository): List<String> {
        val provider = runCatching { Provider.valueOf(settings.aiProvider.first()) }
            .getOrElse { Provider.GEMINI }
        return when (provider) {
            Provider.GEMINI -> Gemini.listModels(Router.firstKey(settings.geminiApiKey.first()))
            Provider.OPENROUTER -> OpenRouter.listFreeModels()
            Provider.CUSTOM -> OpenRouter.listCustomModels(
                settings.customUrl.first(), Router.firstKey(settings.customKey.first())
            )
        }
    }
}

/** OpenRouter speaks the OpenAI chat-completions format. */
object OpenRouter {

    suspend fun raw(
        apiKey: String,
        model: String,
        prompt: String,
        system: String?,
        maxOutputTokens: Int,
        endpoint: String = "https://openrouter.ai/api/v1/chat/completions",
        referer: Boolean = true
    ): String = withContext(Dispatchers.IO) {
        val messages = JSONArray()
        if (!system.isNullOrBlank()) {
            messages.put(JSONObject().put("role", "system").put("content", system))
        }
        messages.put(JSONObject().put("role", "user").put("content", prompt))

        val body = Compat.tune(JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("max_tokens", if (Compat.reasoning(model)) (maxOutputTokens + 2048).coerceAtMost(8000) else maxOutputTokens)
            .put("temperature", 0.7), endpoint, model)
            .toString()

        val headers = mutableMapOf<String, String>()
        if (apiKey.isNotBlank()) headers["Authorization"] = "Bearer $apiKey"
        if (referer) {
            headers["HTTP-Referer"] = "https://github.com/atlas-app"
            headers["X-Title"] = "Atlas"
        }
        val response = Http.postJson(url = endpoint, body = body, headers = headers)
        val root = JSONObject(response)
        root.optJSONObject("error")?.let {
            throw IllegalStateException(it.optString("message", "OpenRouter error"))
        }
        val choices = root.optJSONArray("choices")
            ?: throw IllegalStateException("OpenRouter returned no choices.")
        if (choices.length() == 0) throw IllegalStateException("OpenRouter returned nothing.")
        val content = Compat.clean(choices.getJSONObject(0).optJSONObject("message")?.optString("content").orEmpty())
        if (content.isBlank()) throw IllegalStateException("The provider returned an empty message.")
        content
    }

    /** Lists models from any OpenAI-compatible endpoint. */
    suspend fun listCustomModels(baseUrl: String, apiKey: String): List<String> = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext emptyList()
        val response = Http.getJson(
            baseUrl.trimEnd('/') + "/models",
            if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey")
        )
        val data = JSONObject(response).optJSONArray("data") ?: return@withContext emptyList()
        (0 until data.length()).mapNotNull { data.getJSONObject(it).optString("id").ifBlank { null } }
    }

    /** Free models, newest first — no key needed to read the catalogue. */
    suspend fun listFreeModels(): List<String> = withContext(Dispatchers.IO) {
        val response = Http.getJson("https://openrouter.ai/api/v1/models")
        val data = JSONObject(response).optJSONArray("data") ?: return@withContext emptyList()
        val out = mutableListOf("openrouter/free")
        for (i in 0 until data.length()) {
            val m = data.getJSONObject(i)
            val id = m.optString("id")
            val pricing = m.optJSONObject("pricing")
            val promptPrice = pricing?.optString("prompt", "1") ?: "1"
            val completionPrice = pricing?.optString("completion", "1") ?: "1"
            val free = promptPrice.toDoubleOrNull() == 0.0 && completionPrice.toDoubleOrNull() == 0.0
            if (free && id.isNotBlank() && id != "openrouter/free") out.add(id)
        }
        out.distinct()
    }
}
