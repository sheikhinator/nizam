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

    fun isHeavy(prompt: String, expertHeavy: Boolean): Boolean {
        if (expertHeavy) return true
        val tail = prompt.takeLast(700)
        return HEAVY.containsMatchIn(tail) || tail.length >= 650 && tail.count { it == '\n' } < 3
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
        if (!heavy) list else list.sortedByDescending { if (it.kind == "LOCAL" || it.kind == "FOLAX") -1 else strength(it.model) }
}
