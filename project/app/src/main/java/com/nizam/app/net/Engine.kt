package com.nizam.app.net

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * What Atlas knows about each key and model from its own calls, so it stops spending requests on
 * ones that just failed:
 *  - a rejected key (401/403) rests for 30 minutes, on every model
 *  - a model the provider doesn't have (404) rests for 12 hours, remembered across restarts
 *  - a rate limit (429) rests that key-and-model for as long as the provider asks (a minute by default,
 *    an hour when the daily allowance is gone)
 *  - an outage (5xx, timeouts) rests that endpoint for 20 seconds
 * Healthy endpoints are tried first; resting ones only when nothing else is left.
 */
object Health {
    private val until = ConcurrentHashMap<String, Long>()
    private val why = ConcurrentHashMap<String, String>()
    private val speed = ConcurrentHashMap<String, Long>()
    private val wins = ConcurrentHashMap<String, Int>()
    private val fails = ConcurrentHashMap<String, Int>()
    private var loaded = false

    private fun host(e: Router.Endpoint) = e.url.substringAfter("://").substringBefore('/').ifBlank { e.kind }
    private fun keyTag(k: String) = if (k.isBlank()) "-" else k.takeLast(6)
    fun keyId(e: Router.Endpoint) = "k|${host(e)}|${keyTag(e.key)}"
    fun modelId(e: Router.Endpoint) = "m|${host(e)}|${e.model}"
    fun endId(e: Router.Endpoint) = "e|${host(e)}|${keyTag(e.key)}|${e.model}"

    private fun prefs(c: Context?) = c?.getSharedPreferences("ai_health", 0)

    private fun load() {
        if (loaded) return
        loaded = true
        prefs(Router.appContext)?.all?.forEach { (k, v) -> (v as? Long)?.let { if (it > System.currentTimeMillis()) until[k] = it } }
    }

    private fun rest(id: String, ms: Long, reason: String, persist: Boolean = false) {
        val t = System.currentTimeMillis() + ms
        until[id] = maxOf(until[id] ?: 0, t); why[id] = reason
        if (persist) prefs(Router.appContext)?.edit()?.putLong(id, t)?.apply()
    }

    /** Milliseconds this endpoint still has to rest (0 = ready). */
    fun resting(e: Router.Endpoint): Long {
        load()
        val now = System.currentTimeMillis()
        return listOf(keyId(e), modelId(e), endId(e), "h|${host(e)}").maxOf { (until[it] ?: 0) - now }.coerceAtLeast(0)
    }

    fun ok(e: Router.Endpoint, ms: Long) {
        val id = endId(e)
        speed[id] = speed[id]?.let { (it * 2 + ms) / 3 } ?: ms
        wins[e.name] = (wins[e.name] ?: 0) + 1
        until.remove(id)
    }

    fun fail(e: Router.Endpoint, t: Throwable) {
        if (e.kind == "LOCAL" || e.kind == "FOLAX") return
        fails[e.name] = (fails[e.name] ?: 0) + 1
        val m = (t.message ?: t.javaClass.simpleName).lowercase()
        when {
            "401" in m || "403" in m || "invalid api key" in m || "api key not valid" in m || "unauthorized" in m || "permission" in m ->
                rest(keyId(e), 30 * 60_000L, "key rejected")
            "404" in m || "not found" in m || "does not exist" in m || "decommissioned" in m || "no longer" in m || "not supported" in m ->
                rest(modelId(e), 12 * 3600_000L, "model unavailable", persist = true)
            "402" in m || "credits" in m -> rest(modelId(e), 12 * 3600_000L, "needs credits", persist = true)
            "429" in m || "rate" in m || "quota" in m || "exhausted" in m -> {
                val daily = "per day" in m || "perday" in m || "daily" in m || "requests per day" in m
                val asked = Regex("(?:retry in|retrydelay\"?:?\\s*\"?|try again in)\\s*([0-9.]+)\\s*(ms|s|m)?").find(m)?.let {
                    val n = it.groupValues[1].toDoubleOrNull() ?: 0.0
                    when (it.groupValues[2]) { "ms" -> n.toLong(); "m" -> (n * 60_000).toLong(); else -> (n * 1000).toLong() }
                }
                rest(endId(e), when { daily -> 3600_000L; asked != null && asked > 0 -> asked.coerceIn(5_000, 3600_000); else -> 60_000L },
                    if (daily) "daily limit reached" else "rate limited", persist = daily)
            }
            t is java.net.UnknownHostException || "unable to resolve" in m -> rest("h|${host(e)}", 15_000L, "unreachable")
            else -> rest(endId(e), 20_000L, "busy")
        }
    }

    /** Healthy first (the router's order kept), then resting ones by how soon they're back. */
    fun order(list: List<Router.Endpoint>): List<Router.Endpoint> {
        val (ready, waiting) = list.partition { resting(it) == 0L }
        return ready + waiting.sortedBy { resting(it) }
    }

    /** One line per endpoint for the Keys screen: which are ready, which are resting and why. */
    fun report(list: List<Router.Endpoint>): List<String> = list.map { e ->
        val r = resting(e)
        val reason = listOf(keyId(e), modelId(e), endId(e), "h|${host(e)}").firstNotNullOfOrNull { why[it]?.takeIf { _ -> (until[it] ?: 0) > System.currentTimeMillis() } }
        "${e.name} · ${e.model} · key …${keyTag(e.key)} — " + if (r == 0L) (speed[endId(e)]?.let { "ready, ~${it} ms" } ?: "ready")
            else "${reason ?: "resting"}, back in ${if (r > 90_000) "${r / 60_000} min" else "${r / 1000} s"}"
    }

    fun stats(): String = wins.keys.plus(fails.keys).distinct().joinToString(" · ") { "$it ${wins[it] ?: 0}✓ ${fails[it] ?: 0}✗" }

    fun clear() { until.clear(); why.clear(); prefs(Router.appContext)?.edit()?.clear()?.apply() }
}

/**
 * Runs one AI request across every endpoint you have — each key, each model, each provider — until one
 * answers. Interactive requests are *hedged*: if the first source hasn't answered in a few seconds, a
 * second one from a different provider starts too and whichever finishes first wins.
 */
object Engine {
    /** Background work gets two lanes; what you're waiting on gets four. Stops a burst of screens from tripping rate limits. */
    private val background = Semaphore(2)
    private val foreground = Semaphore(4)

    fun isBackground() = Router.quiet.get() == true

    suspend fun <T> limited(block: suspend () -> T): T = (if (isBackground()) background else foreground).withPermit { block() }

    suspend fun <T> run(
        candidates: List<Router.Endpoint>,
        hedgeAfterMs: Long? = if (isBackground()) null else 7_000L,
        call: suspend (Router.Endpoint) -> T
    ): T {
        val ordered = Health.order(candidates)
        // Don't spend quota on endpoints that are resting when a ready one exists
        val ready = ordered.filter { Health.resting(it) == 0L }
        val queue = ArrayDeque(if (ready.isNotEmpty()) ready + ordered.filter { it !in ready }.take(1) else ordered.take(3))
        if (queue.isEmpty()) throw Ai.MissingKeyException("No AI provider set up. Add a key in Settings › API keys.")
        // Calls run detached from this coroutine, so a hedge that loses (a blocking socket read) never holds up the winner
        val scope = CoroutineScope(currentCoroutineContext().minusKey(Job) + SupervisorJob())
        val results = Channel<Pair<Router.Endpoint, Result<T>>>(Channel.UNLIMITED)
        var running = 0
        var hedged = false
        var first: Throwable? = null
        var busyRound = 0
        val skipHosts = mutableSetOf<String>()
        fun host(e: Router.Endpoint) = e.url.substringAfter("://").substringBefore('/').ifBlank { e.kind }

        fun next(differentFrom: Router.Endpoint? = null): Router.Endpoint? {
            val pick = queue.firstOrNull { e -> host(e) !in skipHosts && (differentFrom == null || host(e) != host(differentFrom)) && e.kind != "FOLAX" && e.kind != "LOCAL" }
                ?: if (differentFrom == null) queue.firstOrNull { host(it) !in skipHosts } else null
            pick ?: return null
            queue.remove(pick); running++
            scope.launch {
                val t0 = System.currentTimeMillis()
                val r = try { Result.success(call(pick)) } catch (t: Throwable) { if (t is CancellationException) throw t; Result.failure(t) }
                if (r.isSuccess) Health.ok(pick, System.currentTimeMillis() - t0)
                results.send(pick to r)
            }
            return pick
        }

        try {
            var lead = next()
            while (running > 0) {
                val msg = if (hedgeAfterMs != null && !hedged && running == 1 && queue.isNotEmpty())
                    withTimeoutOrNull(hedgeAfterMs) { results.receive() } else results.receive()
                if (msg == null) { hedged = true; next(differentFrom = lead); continue }
                running--
                val (e, r) = msg
                if (r.isSuccess) return r.getOrThrow()
                val t = r.exceptionOrNull()!!
                if (first == null) first = t
                Health.fail(e, t)
                val m = t.message.orEmpty()
                // A request this provider refuses as malformed will be refused by its other keys and models too
                if (m.contains("HTTP 400") && !m.contains("model", true) && !m.contains("key", true)) skipHosts += host(e)
                if (running == 0) {
                    lead = next()
                    if (lead == null) {
                        // everything was merely busy: one short pause and a single second round on the best two
                        val transient = m.contains("503") || m.contains("overload", true) || m.contains("timeout", true)
                        if (transient && busyRound == 0) {
                            busyRound++; delay(1500)
                            Health.order(candidates).filter { host(it) !in skipHosts && it.kind != "FOLAX" }.take(2).forEach { queue.add(it) }
                            lead = next()
                        }
                    }
                }
            }
        } finally {
            scope.cancel()
        }
        throw first ?: IllegalStateException("No provider answered.")
    }
}

/**
 * Remembers recent answers and shares in-flight ones: when two screens (or one screen twice) ask the
 * same thing, only one request goes out. Background answers are kept for half an hour, the rest for three
 * minutes, so reopening a screen doesn't cost a fresh call.
 */
object AnswerCache {
    private data class Hit(val text: String, val at: Long)
    private val done = object : LinkedHashMap<String, Hit>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Hit>?) = size > 80
    }
    private val inflight = ConcurrentHashMap<String, CompletableDeferred<String>>()

    fun key(vararg parts: Any?) = parts.joinToString("\u0001").hashCode().toString() + ":" + parts.joinToString("").length

    suspend fun get(key: String, ttlMs: Long, make: suspend () -> String): String {
        synchronized(done) { done[key]?.takeIf { System.currentTimeMillis() - it.at < ttlMs }?.let { return it.text } }
        inflight[key]?.let { return it.await() }
        val d = CompletableDeferred<String>()
        val existing = inflight.putIfAbsent(key, d)
        if (existing != null) return existing.await()
        return try {
            val r = make()
            synchronized(done) { done[key] = Hit(r, System.currentTimeMillis()) }
            d.complete(r); r
        } catch (t: Throwable) {
            d.completeExceptionally(t); throw t
        } finally { inflight.remove(key) }
    }

    fun clear() = synchronized(done) { done.clear() }
}

/** How many background AI calls Atlas may make a day, so ambient features never eat the quota you chat with. */
object Budget {
    private const val DAILY_BACKGROUND = 80
    private fun p() = Router.appContext?.getSharedPreferences("ai_budget", 0)
    fun allowBackground(): Boolean {
        val p = p() ?: return true
        val day = java.time.LocalDate.now().toString()
        val used = if (p.getString("day", "") == day) p.getInt("used", 0) else 0
        if (used >= DAILY_BACKGROUND) return false
        p.edit().putString("day", day).putInt("used", used + 1).apply()
        return true
    }
    fun usedToday(): Int = p()?.let { if (it.getString("day", "") == java.time.LocalDate.now().toString()) it.getInt("used", 0) else 0 } ?: 0
    const val LIMIT = DAILY_BACKGROUND
}

/**
 * Each provider's own model list, so when one model fails Atlas moves to another that provider has — from a
 * short tested list per provider, plus what the provider's /models says (fetched at most once a day).
 */
object ModelLists {
    private val KNOWN = mapOf(
        "api.groq.com" to listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant", "openai/gpt-oss-120b", "qwen/qwen3-32b"),
        "api.cerebras.ai" to listOf("llama-3.3-70b", "qwen-3-32b", "llama3.1-8b", "gpt-oss-120b"),
        "api.mistral.ai" to listOf("mistral-small-latest", "open-mistral-nemo", "ministral-8b-latest"),
        "models.github.ai" to listOf("openai/gpt-4.1-mini", "openai/gpt-4o-mini", "meta/Llama-3.3-70B-Instruct"),
        "integrate.api.nvidia.com" to listOf("meta/llama-3.3-70b-instruct", "meta/llama-3.1-8b-instruct", "mistralai/mistral-small-24b-instruct"),
        "api.sambanova.ai" to listOf("Meta-Llama-3.3-70B-Instruct", "Meta-Llama-3.1-8B-Instruct", "DeepSeek-V3-0324"),
        "text.pollinations.ai" to listOf("openai", "mistral"),
        "api.deepseek.com" to listOf("deepseek-chat"),
        "router.huggingface.co" to listOf("meta-llama/Llama-3.3-70B-Instruct", "Qwen/Qwen2.5-72B-Instruct"),
    )
    private fun host(url: String) = url.substringAfter("://").substringBefore('/').substringBefore(':')
    private fun p(c: Context) = c.getSharedPreferences("model_lists", 0)

    fun chain(c: Context?, url: String, chosen: String): List<String> {
        val h = host(url)
        val learned = c?.let { p(it).getString(h, "").orEmpty().split('\n').filter { m -> m.isNotBlank() } }.orEmpty()
        return (listOf(chosen) + KNOWN[h].orEmpty() + learned).filter { it.isNotBlank() }.distinct()
    }

    private val CHAT_HINT = Regex("instruct|chat|llama|qwen|gemma|mistral|deepseek|gpt|free|nemo|phi", RegexOption.IGNORE_CASE)
    private val NOT_CHAT = Regex("embed|whisper|tts|guard|rerank|moderation|vision-only|audio|image|dall|flux|sdxl|transcribe", RegexOption.IGNORE_CASE)

    suspend fun refresh(c: Context, chatUrl: String, key: String) {
        val h = host(chatUrl)
        val last = p(c).getLong("t_$h", 0)
        if (System.currentTimeMillis() - last < 24 * 3600_000L || h.isBlank() || h == "openrouter.ai") return
        p(c).edit().putLong("t_$h", System.currentTimeMillis()).apply()
        val ids = runCatching {
            val o = org.json.JSONObject(Http.getJson(chatUrl.substringBeforeLast("/chat/completions") + "/models",
                if (key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $key")))
            val a = o.optJSONArray("data") ?: org.json.JSONArray()
            (0 until a.length()).map { a.getJSONObject(it).optString("id") }
        }.getOrElse { return }
        val good = ids.filter { CHAT_HINT.containsMatchIn(it) && !NOT_CHAT.containsMatchIn(it) }
            .sortedByDescending { (if ("free" in it) 2 else 0) + (if ("instruct" in it || "chat" in it) 1 else 0) }.take(6)
        p(c).edit().putString(h, good.joinToString("\n")).apply()
    }
}
