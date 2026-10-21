package com.nizam.app.net

import android.content.Context
import com.nizam.app.data.prefs.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Picks the right path and the right model for each message, so simple things are instant and only
 * real work pays for the full agent.
 *
 *  LOCAL — parsed on the phone (LocalCommands): no network at all
 *  CHAT  — a question or conversation: one streamed call on the fastest endpoint, words appear at once
 *  ACT   — something must change in the app, the phone or a connected tool: the full agent loop
 *
 * Every call also fails over: when the chosen provider rate-limits or is down, the next endpoint you
 * have a key for takes the request instead of the user seeing an error.
 */
object Router {

    /** Set once at startup so code without a Context (Ai.generate's failover) can still reach the offline model. */
    @Volatile var appContext: Context? = null

    fun online(c: Context): Boolean = runCatching {
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
    }.getOrElse { true }

    enum class Lane { LOCAL, CHAT, ACT }

    /** One place a request can go. kind is "GEMINI" or "OPENAI" (any OpenAI-compatible API). */
    data class Endpoint(val name: String, val kind: String, val url: String, val key: String, val model: String) {
        val isGroq get() = url.contains("groq.com")
        /** Groq counts declared max_tokens against its per-minute budget, so ask for less there. */
        fun cap(tokens: Int) = when {
            // Groq counts max_tokens against a small per-minute budget (about 8k), so asking for 4k output alone
            // triggers 413/429; its reasoning models get a little thinking room, never more than 2k in total
            isGroq -> if (Compat.reasoning(model)) minOf(tokens + 768, 2048) else minOf(tokens, 1024)
            // reasoning models think before they answer — give them room, or the answer comes back empty
            Compat.reasoning(model) -> (tokens + 2048).coerceAtMost(8000)
            else -> tokens
        }
    }

    private val ACTION = Regex(
        "\\b(open|launch|call|ring|text|sms|message|whatsapp|email|send|reply|set|add|log|record|create|make|build|" +
            "delete|remove|clear|remind|schedule|book|order|pay|play|pause|stop|start|turn|switch|enable|disable|" +
            "navigate|directions|track|mark|complete|finish|rename|change|update|edit|move|install|generate|" +
            "search|find|look up|google|download|share|save|note|alarm|timer|volume|flashlight|torch|dnd|" +
            "theme|icon|palette|module|mission|goal|task|budget|expense|spent|paid|workout|meal|prayed|" +
            "whenever|every time|autopilot|replay|timeline|yesterday|last (?:heard|talked|spoke|messaged|texted)|what happened|" +
            "brief me|briefing|subscriptions?|bills?|zakat|open loops|anything open|family)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Cheap, on-device routing — no model call to decide which model to call. */
    fun classify(text: String, useWeb: Boolean, hasAttachments: Boolean, mcpServers: List<String>): Lane {
        val t = text.lowercase()
        if (useWeb) return Lane.ACT
        if (mcpServers.any { it.isNotBlank() && t.contains(it.lowercase()) }) return Lane.ACT
        if (ACTION.containsMatchIn(t)) return Lane.ACT
        // "can you …" / "please …" requests read as instructions even without a known verb
        if (Regex("^(can you|could you|please|go ahead|do it|yes|yeah|ok do)\\b").containsMatchIn(t.trim())) return Lane.ACT
        return Lane.CHAT
    }

    // ── fast lane: an optional second endpoint used for chat, e.g. Groq or Cerebras ──────────────

    private fun prefs(c: Context) = c.getSharedPreferences("router", 0)
    fun fastLane(c: Context): Endpoint? {
        val p = prefs(c)
        val url = p.getString("fast_url", "").orEmpty()
        val model = p.getString("fast_model", "").orEmpty()
        if (url.isBlank() || model.isBlank()) return null
        return Endpoint("Fast lane", "OPENAI", url.trimEnd('/') + "/chat/completions", p.getString("fast_key", "").orEmpty(), model)
    }
    fun setFastLane(c: Context, url: String, key: String, model: String) =
        prefs(c).edit().putString("fast_url", url.trim()).putString("fast_key", key.trim()).putString("fast_model", model.trim()).apply()
    fun fastLaneParts(c: Context) = Triple(
        prefs(c).getString("fast_url", "").orEmpty(), prefs(c).getString("fast_key", "").orEmpty(), prefs(c).getString("fast_model", "").orEmpty()
    )

    /** Several keys can share one slot — separated by commas, spaces or new lines. Atlas rotates through them. */
    fun keys(raw: String): List<String> {
        val many = raw.split(',', '\n', ';', ' ', '|', '\t').map { it.trim() }.filter { it.length >= 8 }.distinct()
        return many.ifEmpty { listOfNotNull(raw.trim().takeIf { it.isNotBlank() }) }
    }
    fun firstKey(raw: String) = keys(raw).firstOrNull().orEmpty()

    // Tested Sep 2026: the lite models answer in ~1 s with big free allowances; "flash-latest" (3.8 Flash) allows only
    // 20 free requests a day, so it's the last resort.
    val GEMINI_CHAIN = listOf("gemini-flash-lite-latest", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite", "gemini-3-flash-preview", "gemini-flash-latest")
    // Concrete free slugs first — the "openrouter/free" router isn't enabled on every account
    val OPENROUTER_CHAIN = listOf("deepseek/deepseek-chat-v3.1:free", "meta-llama/llama-3.3-70b-instruct:free",
        "qwen/qwen-2.5-72b-instruct:free", "google/gemma-3-27b-it:free", "openrouter/free")

    /**
     * Every endpoint with credentials, expanded to each model × each key, the chosen provider first. For CHAT
     * the fast lane leads. Within a provider: your chosen model on every key, then the next model on every key —
     * a rate limit on one key moves to your next key before it moves to a weaker model.
     */
    suspend fun endpoints(settings: SettingsRepository, c: Context?, lane: Lane = Lane.ACT): List<Endpoint> {
        val ctx = c ?: appContext
        fun expand(name: String, kind: String, url: String, rawKey: String, models: List<String>, maxModels: Int): List<Endpoint> {
            val ks = keys(rawKey).ifEmpty { listOf("") }
            return models.filter { it.isNotBlank() }.distinct().take(maxModels).flatMap { m -> ks.map { k -> Endpoint(name, kind, url, k, m) } }
        }
        val geminiKey = settings.geminiApiKey.first()
        val gemini = if (geminiKey.isBlank()) emptyList() else settings.geminiModel.first().ifBlank { "gemini-flash-lite-latest" }.let { chosen ->
            expand("Gemini", "GEMINI", "https://generativelanguage.googleapis.com/v1beta/models", geminiKey, listOf(chosen) + GEMINI_CHAIN, 5)
        }
        val orKey = settings.openRouterKey.first()
        val openRouter = if (orKey.isBlank()) emptyList() else {
            val chosen = settings.openRouterModel.first().takeUnless { m -> m.isBlank() || m == "openrouter/free" }.orEmpty()
            expand("OpenRouter", "OPENAI", "https://openrouter.ai/api/v1/chat/completions", orKey, listOf(chosen) + OPENROUTER_CHAIN, 5)
        }
        val customUrl = settings.customUrl.first()
        val custom = if (customUrl.isBlank()) emptyList() else {
            val url = customUrl.trimEnd('/').let { if (it.endsWith("/chat/completions")) it else "$it/chat/completions" }
            expand("Custom", "OPENAI", url, settings.customKey.first(),
                ModelLists.chain(ctx, url, settings.customModel.first().ifBlank { "llama-3.3-70b-versatile" }), 3)
        }
        val fast = ctx?.let { fastLane(it) }?.let { f -> expand("Fast lane", "OPENAI", f.url, f.key, ModelLists.chain(ctx, f.url, f.model), 2) }.orEmpty()
        val primary = when (settings.aiProvider.first()) {
            "OPENROUTER" -> openRouter
            "CUSTOM" -> custom
            else -> gemini
        }
        val local = ctx?.takeIf { LocalModel.available(it) }?.let { Endpoint("On this phone", "LOCAL", "", "", LocalModel.file(it)?.name.orEmpty()) }
        val ordered = LinkedHashSet<Endpoint>().apply {
            // No internet: the on-device model answers straight away instead of every provider timing out
            if (local != null && ctx != null && !online(ctx)) add(local)
            if (lane == Lane.CHAT) addAll(fast.filter { it.model == fast.first().model })
            primary.firstOrNull()?.let { p -> addAll(primary.filter { it.model == p.model }) }
            // the other providers' best model before anyone's second-best, so one provider's bad day costs one hop
            listOf(gemini, custom, openRouter, fast).forEach { l -> l.firstOrNull()?.let { add(it) } }
            listOf(gemini, custom, openRouter, fast).forEach { addAll(it) }
            // the free-provider pool (Cerebras, Mistral, GitHub Models, NVIDIA, SambaNova, OpenCode Zen, Pollinations, Ollama…)
            ctx?.let { cx -> Providers.endpoints(cx).forEach { e -> addAll(expand(e.name, e.kind, e.url, e.key, ModelLists.chain(cx, e.url, e.model), 2)) } }
            local?.let { add(it) }
            // last resort, when nothing else answers: Folax, the assistant built into Infinix/Tecno/itel phones
            ctx?.takeIf { quiet.get() != true && folaxBackup(it) && com.nizam.app.feature.jarvis.Folax.available(it) }?.let { add(Endpoint("Folax", "FOLAX", "", "", "folax")) }
        }
        return ordered.toList()
    }

    /** Set (via quiet.asContextElement(true)) for background thinking, which must never take over the screen with Folax. */
    val quiet = ThreadLocal<Boolean>()

    fun folaxBackup(c: Context) = c.getSharedPreferences("local_model", 0).getBoolean("folax_backup", true)
    fun setFolaxBackup(c: Context, on: Boolean) = c.getSharedPreferences("local_model", 0).edit().putBoolean("folax_backup", on).apply()

    /** Worth trying the next provider? Rate limits, overload and timeouts yes; a bad request no. */
    fun shouldFailOver(e: Throwable): Boolean {
        val m = (e.message ?: e.javaClass.simpleName).lowercase()
        return listOf("429", "500", "502", "503", "504", "rate", "quota", "overload", "unavailable", "timeout",
            "timed out", "exhausted", "401", "403", "unable to resolve", "failed to connect", "busy", "rejected",
            "no ai provider", "no gemini", "no openrouter", "no custom endpoint").any { m.contains(it) } ||
            e is java.io.IOException
    }

    /**
     * Opens the TLS connection to the provider before the user hits send. The handshake is a third
     * of first-token latency on mobile networks; keep-alive then reuses the warm socket.
     */
    suspend fun warm(settings: SettingsRepository, c: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val all = endpoints(settings, c, Lane.CHAT)
            // once a day, learn which models each provider offers, so a failing model has a working neighbour to fall back to
            all.filter { it.kind == "OPENAI" }.distinctBy { it.url }.forEach { e -> runCatching { ModelLists.refresh(c, e.url, e.key) } }
            all.filter { it.url.isNotBlank() }.distinctBy { URL(it.url).host }.take(2).forEach { e ->
                val host = URL(e.url).let { "${it.protocol}://${it.host}/" }
                (URL(host).openConnection() as HttpURLConnection).apply {
                    requestMethod = "HEAD"; connectTimeout = 4000; readTimeout = 4000
                    runCatching { responseCode }
                    runCatching { inputStream.close() }
                }
            }
        }
    }
}
