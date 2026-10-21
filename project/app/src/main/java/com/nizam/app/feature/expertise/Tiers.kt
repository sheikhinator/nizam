package com.nizam.app.feature.expertise

import com.nizam.app.net.Router
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Model tiers (absorbed from LYCHO's router). Atlas's engine already ranks endpoints by health, rests
 * rate-limited ones and hedges slow calls; tiers add one thing on top: heavy questions (analysis, plans,
 * code, long writing, heavy expertise) try the strongest models first, while everyday questions keep
 * the fast, generous ones. Health still decides between models of the same strength.
 */
object Tiers {
    /** Marks a request as heavy for the engine; read in Engine.run. */
    class Heavy : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<Heavy>
    }

    private val HEAVY = Regex(
        "\\b(analy[sz]e|analysis|strategy|strategic|roadmap|compare|evaluate|debug|code|algorithm|architecture|prove|" +
            "calculate|forecast|step[- ]by[- ]step|in detail|deep dive|research|report|essay|contract|business plan|" +
            "financial model|why does|optimi[sz]e|thesis|proposal)\\b",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Heavy only when the user's own words ask for deep work (or a heavy expertise was chosen by hand).
     * The prompt carries Atlas's snapshot and history, so its length says nothing about the question.
     */
    fun isHeavy(prompt: String, expertHeavy: Boolean): Boolean {
        if (expertHeavy) return true
        return HEAVY.containsMatchIn(Expertise.lastUserText(prompt))
    }

    /**
     * Size-aware routing. Groq's free tier allows roughly 6–12k tokens a minute per model (about 8k for the big
     * ones), so a long Atlas prompt there fails with 413 "request too large" or 429 and only adds seconds.
     * Requests that won't fit are sent to the long-context providers first; Groq stays last as a backup.
     */
    /** Input tokens of a request (about 3.6 characters a token for mixed English/Urdu text). */
    fun estimateTokens(prompt: String, system: String?, @Suppress("UNUSED_PARAMETER") maxOut: Int): Int =
        ((prompt.length + (system?.length ?: 0)) / 3.6).toInt()

    fun fitToSize(list: List<Router.Endpoint>, tokens: Int): List<Router.Endpoint> {
        // ~8k a minute minus up to 2k of output leaves room for about 5.5k of input
        val limit = 5500
        if (tokens <= limit) return list
        val (small, rest) = list.partition { it.isGroq }
        return if (rest.isEmpty()) list else rest + small
    }

    /** 2 = strongest, 1 = balanced, 0 = fast. Patterns, so new model names keep working. */
    fun strength(model: String): Int {
        val m = model.lowercase()
        return when {
            Regex("lite|8b|instant|mini|nano|small|gemma-3-(1|4)b").containsMatchIn(m) -> 0
            Regex("pro\\b|-pro|opus|120b|235b|405b|r1|kimi|70b|72b|deepseek-chat|deepseek-v|flash-latest|sonnet|gpt-5|gpt-4").containsMatchIn(m) -> 2
            else -> 1
        }
    }

    /** Stable re-order: strongest first for heavy work; health order is kept within each strength. */
    fun order(list: List<Router.Endpoint>, heavy: Boolean): List<Router.Endpoint> =
        // Groq caps request size and rate hard, so its big models are never moved ahead: a long Atlas prompt
        // there fails with 413/429 and only adds delay. Strong Gemini/OpenRouter models move up; health decides the rest.
        if (!heavy) list else list.sortedByDescending { if (it.kind == "LOCAL" || it.kind == "FOLAX" || it.isGroq) 0 else strength(it.model) }
}
