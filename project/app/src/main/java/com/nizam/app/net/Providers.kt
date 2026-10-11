package com.nizam.app.net

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Extra AI providers Atlas can fall back on — all OpenAI-compatible, all with a free tier (and one
 * with no key at all). Nothing hosted is truly unlimited; stacking several generous free tiers means
 * there's nearly always one with room left. For genuinely unlimited use, run a model yourself:
 * Atlas's offline model, or Ollama on your computer (last entry).
 */
object Providers {
    data class Preset(
        val id: String, val name: String, val chatUrl: String, val model: String,
        val needsKey: Boolean, val keyPage: String, val note: String, val keyPrefix: String = ""
    )

    val PRESETS = listOf(
        Preset("claude", "Claude (Anthropic API)", "https://api.anthropic.com/v1/chat/completions", "claude-sonnet-5-5", true,
            "https://console.anthropic.com/settings/keys",
            "Claude as Atlas's brain, the strongest option for planning and acting. Pay-as-you-go on your own Anthropic account. When a key is added it is tried first. Use 'claude-haiku-5-5' as the model for something faster and cheaper.", "sk-ant-"),
        Preset("pollinations", "Pollinations (no key)", "https://text.pollinations.ai/openai", "openai", false, "https://pollinations.ai",
            "Free, no sign-up. Rate-limited per phone, so it's a safety net rather than a workhorse."),
        Preset("freellmapi", "FreeLLMAPI (34 free providers in one)", "http://127.0.0.1:3001/v1/chat/completions", "auto", true,
            "https://github.com/tashfeenahmed/freellmapi",
            "Your own router that stacks the free tiers of 34 providers (up to ~7.4 billion tokens a month once you add a key for each). Run it on your PC (address like http://192.168.1.10:3001) or on this phone in Termux (keep 127.0.0.1:3001), add your provider keys in its dashboard, then paste its unified key here. Model 'auto' lets it pick; 'auto:fast' or 'auto:smart' also work.", "freellmapi-"),
        Preset("cerebras", "Cerebras", "https://api.cerebras.ai/v1/chat/completions", "llama-3.3-70b", true, "https://cloud.cerebras.ai",
            "Very fast. Free tier around a million tokens a day.", "csk-"),
        Preset("mistral", "Mistral", "https://api.mistral.ai/v1/chat/completions", "mistral-small-latest", true, "https://console.mistral.ai/api-keys",
            "Free 'Experiment' plan with generous monthly limits."),
        Preset("github", "GitHub Models", "https://models.github.ai/inference/chat/completions", "openai/gpt-4.1-mini", true,
            "https://github.com/settings/personal-access-tokens", "Free with a GitHub account — use a token with 'Models: read'. Daily request limits.", "github_pat_"),
        Preset("nvidia", "NVIDIA NIM", "https://integrate.api.nvidia.com/v1/chat/completions", "meta/llama-3.3-70b-instruct", true,
            "https://build.nvidia.com", "Free credits for dozens of open models.", "nvapi-"),
        Preset("sambanova", "SambaNova", "https://api.sambanova.ai/v1/chat/completions", "Meta-Llama-3.3-70B-Instruct", true,
            "https://cloud.sambanova.ai/apis", "Fast Llama and DeepSeek models on a free tier."),
        Preset("opencode", "OpenCode Zen", "https://opencode.ai/zen/v1/chat/completions", "", true, "https://opencode.ai/zen",
            "OpenCode's model gateway. Some models are free; leave the model blank and Atlas picks a free one."),
        Preset("hf", "Hugging Face (your HF token)", "https://router.huggingface.co/v1/chat/completions", "meta-llama/Llama-3.1-8B-Instruct", true,
            "https://huggingface.co/settings/tokens", "Uses the Hugging Face token you already added — nothing new to sign up for. Small free monthly allowance.", "hf_"),
        Preset("hermes", "Hermes Agent on your computer", "http://192.168.1.10:8642/v1/chat/completions", "hermes-agent", true,
            "https://hermes-agent.nousresearch.com/docs/user-guide/features/api-server",
            "Nous Research's open-source agent as Atlas's brain. On the PC set API_SERVER_ENABLED=true, API_SERVER_HOST=0.0.0.0 and an API_SERVER_KEY, run it, then paste that key here and your PC's address above."),
        Preset("ollama", "Ollama on your computer (unlimited)", "http://192.168.1.10:11434/v1/chat/completions", "llama3.2", false,
            "https://ollama.com", "Truly unlimited and private: run Ollama on a PC on the same Wi-Fi, then set its address here."),
    )

    private fun p(c: Context) = c.getSharedPreferences("providers", 0)
    private fun state(c: Context, id: String): JSONObject = runCatching { JSONObject(p(c).getString(id, "{}")!!) }.getOrElse { JSONObject() }
    private fun save(c: Context, id: String, o: JSONObject) = p(c).edit().putString(id, o.toString()).apply()

    /** Pollinations is on by default (no key needed); the rest switch on when you add a key. */
    fun on(c: Context, pr: Preset) = state(c, pr.id).optBoolean("on", pr.id == "pollinations" || pr.id == "hf")
    /** Hugging Face reuses the token saved for offline models, so it works without adding anything. */
    fun key(c: Context, pr: Preset) = state(c, pr.id).optString("key").ifBlank {
        if (pr.id == "hf") c.getSharedPreferences("local_model", 0).getString("hf_token", "").orEmpty() else "" }

    /** The provider whose sign-up page was opened last — a key found on the clipboard afterwards is for it. */
    fun pending(c: Context) = p(c).getString("_pending", "").orEmpty()
    fun setPending(c: Context, id: String) = p(c).edit().putString("_pending", id).apply()
    fun model(c: Context, pr: Preset) = state(c, pr.id).optString("model").ifBlank { pr.model }
    fun url(c: Context, pr: Preset) = state(c, pr.id).optString("url").ifBlank { pr.chatUrl }
    fun set(c: Context, pr: Preset, on: Boolean? = null, key: String? = null, model: String? = null, url: String? = null) {
        val o = state(c, pr.id)
        on?.let { o.put("on", it) }; key?.let { o.put("key", it.trim()); if (it.isNotBlank() && !o.has("on")) o.put("on", true) }
        model?.let { o.put("model", it.trim()) }; url?.let { o.put("url", it.trim()) }
        save(c, pr.id, o)
    }

    /** Ready-to-use endpoints, in the order above, for the router's fallback chain. */
    fun endpoints(c: Context): List<Router.Endpoint> = PRESETS.filter { on(c, it) && (!it.needsKey || key(c, it).isNotBlank()) && model(c, it).isNotBlank() }
        .map { Router.Endpoint(it.name, "OPENAI", url(c, it), key(c, it), model(c, it)) }

    /** OpenCode Zen and others without a fixed model: pick a free one from the provider's own list. */
    suspend fun resolveModel(c: Context, pr: Preset): String {
        model(c, pr).takeIf { it.isNotBlank() }?.let { return it }
        val list = runCatching {
            val o = JSONObject(Http.getJson(url(c, pr).substringBeforeLast("/chat/completions") + "/models",
                Router.firstKey(key(c, pr)).let { k -> if (k.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $k") }))
            val a = o.optJSONArray("data") ?: JSONArray()
            (0 until a.length()).map { a.getJSONObject(it).optString("id") }
        }.getOrElse { emptyList() }
        val pick = list.firstOrNull { it.contains("free", true) } ?: list.firstOrNull().orEmpty()
        if (pick.isNotBlank()) set(c, pr, model = pick)
        return pick
    }

    /** One real request, the way Atlas makes it. */
    suspend fun test(c: Context, pr: Preset): String = runCatching {
        val m = resolveModel(c, pr)
        val r = OpenRouter.raw(Router.firstKey(key(c, pr)), m, "Reply with the single word: ready", null, 200, url(c, pr), referer = false)
        "Works ✓ — ${m.ifBlank { "default" }} replied “${r.take(30).trim()}”"
    }.getOrElse { "Failed: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
}
