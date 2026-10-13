package com.nizam.app.core.spine

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Self — Atlas's one, shared sense of the owner's situation right now.
 *
 * Instead of every feature re-reading each other feature, each organ *contributes* a short slice
 * of itself here ("money", "prayer", "health", "mood", "location"), and the agent, the Council,
 * the Curator and the Reactor all read the same assembled picture. One mind, not many.
 *
 * Contributions are lambdas registered at startup, so `Self` never hard-depends on any repository's
 * API — organs opt in. A slow or throwing contributor is swallowed, never stalls the snapshot.
 */
object Self {
    private val contributors = LinkedHashMap<String, suspend () -> String>()

    /** Register (or replace) one organ's slice of the shared picture. */
    @Synchronized
    fun contribute(key: String, provider: suspend () -> String) { contributors[key] = provider }

    @Synchronized
    fun remove(key: String) { contributors.remove(key) }

    @Synchronized
    fun keys(): Set<String> = contributors.keys.toSet()

    @Synchronized
    private fun providers(): List<Pair<String, suspend () -> String>> = contributors.toList()

    /** Each organ's current slice, blanks dropped. */
    suspend fun snapshot(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for ((key, provider) in providers()) {
            val value = runCatching { provider() }.getOrDefault("").trim()
            if (value.isNotEmpty()) out[key] = value
        }
        return out
    }

    /** A compact, model-ready briefing of the owner's situation — what the agent reads before acting. */
    suspend fun brief(): String {
        val sb = StringBuilder()
        sb.append("Now: ")
            .append(LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm")))
            .append('\n')
        for ((key, value) in snapshot()) {
            sb.append("- ").append(key).append(": ").append(value).append('\n')
        }
        val tail = Pulse.recent(8)
        if (tail.isNotEmpty()) {
            sb.append("Recent: ").append(
                tail.joinToString("; ") { s ->
                    s.domain + "/" + s.kind + (if (s.summary.isNotBlank()) " (" + s.summary + ")" else "")
                }
            )
        }
        return sb.toString().trim()
    }
}
