package com.nizam.app.net

import android.content.Context
import com.nizam.app.BuildConfig
import com.nizam.app.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first

/**
 * Every API key Atlas uses, in one place, each with an on/off switch. Keys can come built in (from
 * the build's secrets — see BuildConfig.KEY_*), so a fresh install already has them; you can still
 * change or switch any of them off here. Switching off tucks the key away (it isn't lost) and Atlas
 * behaves as if it isn't set; switching on puts it back.
 */
object ApiKeys {
    class Slot(
        val id: String, val label: String, val what: String, val builtIn: String,
        val read: suspend (Context, SettingsRepository) -> String,
        val write: suspend (Context, SettingsRepository, String) -> Unit
    )

    private fun sp(c: Context, file: String) = c.getSharedPreferences(file, 0)
    private fun spSlot(id: String, label: String, what: String, builtIn: String, file: String, key: String) = Slot(id, label, what, builtIn,
        { c, _ -> sp(c, file).getString(key, "").orEmpty() }, { c, _, v -> sp(c, file).edit().putString(key, v.trim()).apply() })

    val SLOTS = listOf(
        Slot("gemini", "Gemini", "Google's AI — chat, vision, web answers", BuildConfig.KEY_GEMINI,
            { _, s -> s.geminiApiKey.first() }, { _, s, v -> s.setGeminiApiKey(v) }),
        Slot("openrouter", "OpenRouter", "Hundreds of models behind one key", BuildConfig.KEY_OPENROUTER,
            { _, s -> s.openRouterKey.first() }, { _, s, v -> s.setOpenRouterKey(v) }),
        Slot("custom", "Custom provider", "Any OpenAI-compatible endpoint (Groq, DeepSeek, local…)", BuildConfig.KEY_CUSTOM,
            { _, s -> s.customKey.first() }, { _, s, v -> s.setCustomKey(v) }),
        spSlot("fast", "Fast lane", "The quick model for simple questions", BuildConfig.KEY_FAST, "router", "fast_key"),
        Slot("hadith", "Hadith API", "hadithapi.com collections", BuildConfig.KEY_HADITH,
            { _, s -> s.hadithApiKey.first() }, { _, s, v -> s.setHadithApiKey(v) }),
        spSlot("tmdb", "TMDB", "Film details, trending and posters in Cinema", BuildConfig.KEY_TMDB, "cinema", "tmdb"),
        spSlot("omdb", "OMDb", "IMDb, Rotten Tomatoes and Metacritic scores", BuildConfig.KEY_OMDB, "cinema", "omdb"),
        spSlot("jamendo", "Jamendo", "Free music catalogue in Music › Discover", BuildConfig.KEY_JAMENDO, "music", "jamendo"),
        spSlot("tomtom", "TomTom", "Live traffic on Maps (free key at developer.tomtom.com)", BuildConfig.KEY_TOMTOM, "maps", "tomtom"),
        spSlot("hf", "Hugging Face", "Gated offline models (Gemma, Llama)", BuildConfig.KEY_HF, "local_model", "hf_token"),
    )

    private fun state(c: Context) = sp(c, "api_keys")
    fun enabled(c: Context, id: String) = state(c).getBoolean("on_$id", true)

    suspend fun value(c: Context, s: SettingsRepository, slot: Slot): String =
        if (enabled(c, slot.id)) slot.read(c, s) else state(c).getString("off_${slot.id}", "").orEmpty()

    suspend fun set(c: Context, s: SettingsRepository, slot: Slot, v: String) {
        if (enabled(c, slot.id)) slot.write(c, s, v) else state(c).edit().putString("off_${slot.id}", v.trim()).apply()
    }

    suspend fun setEnabled(c: Context, s: SettingsRepository, slot: Slot, on: Boolean) {
        if (on == enabled(c, slot.id)) return
        if (on) { slot.write(c, s, state(c).getString("off_${slot.id}", "").orEmpty()); state(c).edit().remove("off_${slot.id}").putBoolean("on_${slot.id}", true).apply() }
        else { state(c).edit().putString("off_${slot.id}", slot.read(c, s)).putBoolean("on_${slot.id}", false).apply(); slot.write(c, s, "") }
    }

    /** First run of a build that carries keys: fill any empty, switched-on slot with its built-in key (and the endpoints that go with them). */
    suspend fun seed(c: Context, s: SettingsRepository) {
        val marker = "seeded_" + (BuildConfig.KEY_GEMINI + BuildConfig.KEY_OPENROUTER + BuildConfig.KEY_CUSTOM + BuildConfig.KEY_FAST +
            BuildConfig.KEY_TMDB + BuildConfig.KEY_HF).hashCode()
        if (state(c).getBoolean(marker, false)) return
        SLOTS.forEach { slot -> if (slot.builtIn.isNotBlank() && enabled(c, slot.id) && slot.read(c, s).isBlank()) slot.write(c, s, slot.builtIn) }
        if (BuildConfig.KEY_CUSTOM_URL.isNotBlank() && s.customUrl.first().isBlank())
            s.setCustomEndpoint(BuildConfig.KEY_CUSTOM_URL, s.customKey.first(), BuildConfig.KEY_CUSTOM_MODEL)
        if (BuildConfig.KEY_FAST_URL.isNotBlank() && sp(c, "router").getString("fast_url", "").isNullOrBlank())
            sp(c, "router").edit().putString("fast_url", BuildConfig.KEY_FAST_URL).putString("fast_model", BuildConfig.KEY_FAST_MODEL).apply()
        state(c).edit().putBoolean(marker, true).apply()
    }

    fun mask(k: String) = if (k.length <= 8) "•".repeat(k.length) else k.take(4) + "••••" + k.takeLast(4)

    /** Calls each service with its key the way Atlas does. Returns a plain answer: works, or what went wrong. */
    suspend fun test(c: Context, s: SettingsRepository, slot: Slot): String {
        val all = Router.keys(value(c, s, slot))
        if (all.isEmpty()) return "Not set"
        if (all.size == 1) return testOne(c, s, slot, all[0])
        // several keys in one slot: test each, so a dead one is easy to spot
        val results = all.map { k -> mask(k) + ": " + testOne(c, s, slot, k) }
        val ok = results.count { "Works" in it }
        return (if (ok > 0) "Works ✓ — $ok of ${all.size} keys answer" else "Failed: none of ${all.size} keys answer") + "\n" + results.joinToString("\n")
    }

    private suspend fun testOne(c: Context, s: SettingsRepository, slot: Slot, key: String): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        fun chatUrl(base: String) = base.trimEnd('/').let { if (it.endsWith("/chat/completions")) it else "$it/chat/completions" }
        val ask = "Reply with the single word: ready"
        runCatching {
            when (slot.id) {
                "gemini" -> Gemini.raw(key, s.geminiModel.first().ifBlank { "gemini-flash-lite-latest" }, ask, null, 200)
                "openrouter" -> OpenRouter.raw(key, s.openRouterModel.first(), ask, null, 200)
                "custom" -> OpenRouter.raw(key, s.customModel.first(), ask, null, 200, chatUrl(s.customUrl.first()), referer = false)
                "fast" -> c.getSharedPreferences("router", 0).let { p -> OpenRouter.raw(key, p.getString("fast_model", "").orEmpty(), ask, null, 200,
                    chatUrl(p.getString("fast_url", "").orEmpty()), referer = false) }
                "hadith" -> Http.getJson("https://hadithapi.com/api/books?apiKey=$key").take(40)
                "tmdb" -> Http.getJson("https://api.themoviedb.org/3/configuration?api_key=$key").take(40)
                "omdb" -> Http.getJson("https://www.omdbapi.com/?i=tt0111161&apikey=$key").also { if (it.contains("\"Response\":\"False\"")) error(it.take(120)) }
                "jamendo" -> Http.getJson("https://api.jamendo.com/v3.0/tracks/?client_id=$key&limit=1").also { if (it.contains("\"status\":\"failed\"")) error(it.take(120)) }
                "hf" -> Http.getJson("https://huggingface.co/api/whoami-v2", mapOf("Authorization" to "Bearer $key")).take(40)
                "tomtom" -> Http.getJson("https://api.tomtom.com/search/2/geocode/Lahore.json?key=$key&limit=1").take(40)
                else -> "?"
            }
        }.fold({ r -> if (slot.id in listOf("gemini", "openrouter", "custom", "fast")) "Works ✓ — replied “${r.take(40).trim()}”" else "Works ✓" },
            { e -> "Failed: " + com.nizam.app.ui.components.Feedback.friendly(e) + (e.message?.let { m -> if (m.length < 160) " ($m)" else "" } ?: "") })
    }

    /** Models Google has retired for new users, or that have tiny free limits — moved to the fast, generous lite model. */
    private val RETIRED_GEMINI = setOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-2.0-flash", "gemini-2.0-flash-lite",
        "gemini-1.5-flash", "gemini-1.5-pro", "gemini-flash-latest", "gemini-3.8-flash", "gemini-3.7-flash", "gemini-pro-latest")
    suspend fun migrateModels(s: SettingsRepository) {
        if (s.geminiModel.first() in RETIRED_GEMINI) s.setGeminiModel("gemini-flash-lite-latest")
    }

    /**
     * One paste, every key: recognises each key by its shape and puts it where it belongs, then sets
     * Atlas up for speed — Gemini Flash-Lite first (about a second per answer), Groq and OpenRouter
     * behind it if Gemini is busy, and Groq's instant model as the fast lane for quick questions.
     */
    suspend fun importAll(c: Context, s: SettingsRepository, text: String): List<String> {
        val tokens = Regex("[A-Za-z0-9_.\\-$/]{20,}").findAll(text).map { it.value.trim('.', ',') }.toList()
        val done = mutableListOf<String>()
        fun slot(id: String) = SLOTS.first { it.id == id }
        suspend fun put(id: String, v: String, label: String) { if (!enabled(c, id)) setEnabled(c, s, slot(id), true); set(c, s, slot(id), v); done += label }
        var gemini = false; var groq: String? = null
        val found = linkedMapOf<String, MutableList<String>>()
        val labels = mapOf("gemini" to "Gemini", "openrouter" to "OpenRouter", "hf" to "Hugging Face", "custom" to "Groq", "hadith" to "Hadith API")
        for (t in tokens) when {
            t.startsWith("AIza") || t.startsWith("AQ.") -> { found.getOrPut("gemini") { mutableListOf() } += t; gemini = true }
            t.startsWith("sk-or-") -> found.getOrPut("openrouter") { mutableListOf() } += t
            t.startsWith("hf_") -> found.getOrPut("hf") { mutableListOf() } += t
            t.startsWith("gsk_") -> { if (groq == null) groq = t; found.getOrPut("custom") { mutableListOf() } += t }
            t.startsWith("$2y$") -> found.getOrPut("hadith") { mutableListOf() } += t
            else -> Providers.PRESETS.firstOrNull { it.keyPrefix.isNotBlank() && t.startsWith(it.keyPrefix) }?.let { pr ->
                Providers.set(c, pr, on = true, key = (Router.keys(Providers.key(c, pr)) + t).distinct().joinToString(",")); done += pr.name }
        }
        // several keys of one kind are all kept — Atlas rotates through them when one hits its limit
        for ((id, ks) in found) {
            val merged = if (id in listOf("gemini", "openrouter", "custom")) (Router.keys(value(c, s, slot(id))) + ks).distinct().joinToString(",") else ks.last()
            put(id, merged, labels[id].orEmpty() + if (Router.keys(merged).size > 1) " ×${Router.keys(merged).size}" else "")
        }
        groq?.let { k ->
            val allGroq = s.customKey.first()
            if (s.customUrl.first().isBlank() || s.customUrl.first().contains("groq.com"))
                s.setCustomEndpoint("https://api.groq.com/openai/v1", allGroq.ifBlank { k }, s.customModel.first().ifBlank { "llama-3.3-70b-versatile" })
            Router.setFastLane(c, "https://api.groq.com/openai/v1", allGroq.ifBlank { k }, "llama-3.1-8b-instant")
            done += "Fast lane (Groq instant)"
        }
        if (gemini) { s.setGeminiModel("gemini-flash-lite-latest"); s.setProvider("GEMINI") }
        else if (groq != null) s.setProvider("CUSTOM")
        return done
    }
}
