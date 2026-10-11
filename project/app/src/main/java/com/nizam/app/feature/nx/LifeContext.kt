package com.nizam.app.feature.nx

import android.content.Context
import com.nizam.app.feature.brain2.PersonalModel
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.net.Router
import java.io.File

/**
 * Gathers what the user has recorded in Atlas into one short block of text, so insight tools can
 * answer from the user's real life instead of guessing. Only used when the user says "Yes" on the
 * tool's own switch. Private (sealed) logs are never included.
 */
object LifeContext {
    const val YES = "Yes"
    private const val MAX_CHARS = 6500
    private const val PER_LOG = 14

    fun digest(use: String?, hint: String = "", c: Context? = Router.appContext): String {
        if (use != YES || c == null) return ""
        return runCatching { build(c, hint) }.getOrDefault("")
    }

    /** Names of the logs that have something in them (for showing the user what will be shared). */
    fun sources(c: Context? = Router.appContext): List<String> {
        c ?: return emptyList()
        return logFiles(c).mapNotNull { (id, spec) -> if (entries(c, id).isNotEmpty()) spec.title else null }
    }

    private fun logFiles(c: Context): List<Pair<String, LogSpec>> =
        (c.filesDir.listFiles() ?: emptyArray<File>()).mapNotNull { f ->
            val n = f.name
            if (!n.startsWith("nxlog_") || !n.endsWith(".json")) return@mapNotNull null
            val id = n.removePrefix("nxlog_").removeSuffix(".json")
            val spec = Nx.find(id) as? LogSpec ?: return@mapNotNull null
            if (spec.sealed) null else id to spec
        }.sortedBy { it.first }

    private fun entries(c: Context, id: String) = runCatching { JsonStore(c, "nxlog_$id").all() }.getOrDefault(emptyList())

    private fun build(c: Context, hint: String): String {
        val routed = Roads.relevant(c, hint, MAX_CHARS - 200)
        if (routed.isNotBlank()) return "The user's own recorded data, shared by them for this question (private, use only to answer):\n" + routed.take(MAX_CHARS)
        return buildLegacy(c)
    }

    private fun buildLegacy(c: Context): String {
        val sb = StringBuilder("The user's own recorded data, shared by them for this question (private, use only to answer):\n")
        PersonalModel.promptBlock(c).takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
        for ((id, spec) in logFiles(c)) {
            val list = entries(c, id).takeLast(PER_LOG)
            if (list.isEmpty()) continue
            sb.append("\n## ").append(spec.title).append('\n')
            for (o in list) {
                val parts = spec.fields.mapNotNull { f -> o.optString(f.key).takeIf { it.isNotBlank() }?.let { "${f.label}: ${it.take(120)}" } }
                if (parts.isNotEmpty()) sb.append("- ").append(parts.joinToString("; ")).append('\n')
            }
            if (sb.length > MAX_CHARS) break
        }
        if (sb.length <= 130) sb.append("(Nothing recorded yet. Say so, and suggest what to start tracking.)")
        return sb.toString().take(MAX_CHARS)
    }
}
