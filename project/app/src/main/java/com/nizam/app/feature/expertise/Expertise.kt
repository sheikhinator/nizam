package com.nizam.app.feature.expertise

import android.content.Context
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/**
 * Atlas's expertise library: 378 kinds of expert thinking (absorbed from LYCHO), all of them Atlas.
 * Nothing here is a separate agent. When a question needs a lawyer, a coach or an accountant, Atlas
 * thinks with that expertise and says so in one small line; memory, voice and personality stay Atlas's own.
 */
object Expertise {
    data class Expert(
        val id: String, val name: String, val sector: String, val role: String,
        val fields: String, val prompt: String?, val heavy: Boolean,
    ) {
        val hub: Hub get() = hubFor(sector)
    }

    /** What Atlas is thinking with right now, for the small "thinking as…" line and the LYCHO orb. */
    private val _current = MutableStateFlow<Expert?>(null)
    val current: StateFlow<Expert?> = _current

    @Volatile private var cache: List<Expert>? = null
    @Volatile internal var appCtx: Context? = null

    /**
     * Expertise chosen by hand (an expert's desk, Swarm, Nexus): Atlas thinks with its full instructions,
     * its trained examples and any improved version Orion wrote, whether or not automatic expertise is on.
     */
    private val _pinned = MutableStateFlow<Expert?>(null)
    val pinned: StateFlow<Expert?> = _pinned
    fun pin(e: Expert?) { _pinned.value = e; _current.value = e }

    /** A first message waiting for the chat box (from an expert's starter prompts). */
    @Volatile private var pendingAsk: String? = null
    fun queueAsk(text: String) { pendingAsk = text }
    fun takeAsk(): String? = pendingAsk.also { pendingAsk = null }

    /** An expert's desk to open next (from Society, World, Forge or Marketplace). */
    @Volatile private var pendingDesk: String? = null
    fun openDesk(id: String) { pendingDesk = id }
    fun takeDesk(c: Context): Expert? = pendingDesk?.let { id -> pendingDesk = null; find(c, id) }

    fun all(c: Context): List<Expert> = cache ?: synchronized(this) {
        appCtx = c.applicationContext
        cache ?: runCatching {
            val text = c.assets.open("expertise.json").bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Expert(
                    o.getString("id"), o.getString("name"), o.getString("sector"), o.getString("role"),
                    o.optString("fields"), o.optString("prompt").ifBlank { null }, o.optBoolean("heavy"),
                )
            }
        }.getOrDefault(emptyList()).let { custom(c) + it }.also { cache = it }
    }

    // ── Expertise Studio: kinds of expertise you write yourself (absorbed from LYCHO's Agent Studio) ──

    fun custom(c: Context): List<Expert> = runCatching {
        val arr = JSONArray(prefs(c).getString("custom", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Expert(o.getString("id"), o.getString("name"), "Yours", o.getString("role"), "", o.optString("prompt").ifBlank { null }, o.optBoolean("heavy"))
        }
    }.getOrDefault(emptyList())

    fun saveCustom(c: Context, id: String?, name: String, role: String, prompt: String, heavy: Boolean): Expert {
        val e = Expert(id ?: "mine_${System.currentTimeMillis()}", name.trim(), "Yours", role.trim(), "", prompt.trim().ifBlank { null }, heavy)
        val list = custom(c).filter { it.id != e.id } + e
        val arr = JSONArray()
        list.forEach { x -> arr.put(org.json.JSONObject().put("id", x.id).put("name", x.name).put("role", x.role).put("prompt", x.prompt ?: "").put("heavy", x.heavy)) }
        prefs(c).edit().putString("custom", arr.toString()).apply()
        cache = null
        Versions.record(c, e.id, e.prompt.orEmpty(), if (id == null) "Created" else "Edited by you")
        Pulse.emit(Pulse.Kind.CHANGED, "New expertise: ${e.name}", "Expertise Studio")
        return e
    }

    fun deleteCustom(c: Context, id: String) {
        val arr = JSONArray()
        custom(c).filter { it.id != id }.forEach { x -> arr.put(org.json.JSONObject().put("id", x.id).put("name", x.name).put("role", x.role).put("prompt", x.prompt ?: "").put("heavy", x.heavy)) }
        prefs(c).edit().putString("custom", arr.toString()).apply()
        cache = null
    }

    fun noteUse(c: Context, e: Expert) {
        val p = prefs(c)
        p.edit().putInt("uses|${e.id}", p.getInt("uses|${e.id}", 0) + 1).apply()
    }

    fun topUsed(c: Context, n: Int = 8): List<Pair<Expert, Int>> {
        val p = prefs(c)
        return all(c).map { it to p.getInt("uses|${it.id}", 0) }.filter { it.second > 0 }.sortedByDescending { it.second }.take(n)
    }

    fun find(c: Context, id: String) = all(c).firstOrNull { it.id == id }

    fun sectors(c: Context): List<Pair<String, Int>> =
        all(c).groupBy { it.sector }.map { it.key to it.value.size }
            .sortedWith(compareBy<Pair<String, Int>> { when (it.first) { "Yours" -> 0; "Core" -> 1; else -> 2 } }.thenBy { it.first })

    fun search(c: Context, q: String, sector: String?): List<Expert> {
        val terms = q.lowercase().split(' ').filter { it.isNotBlank() }
        return all(c).filter { e ->
            (sector == null || e.sector == sector) &&
                terms.all { t -> e.name.lowercase().contains(t) || e.role.lowercase().contains(t) || e.sector.lowercase().contains(t) }
        }
    }

    /** The instruction that makes Atlas think with one expertise, without becoming someone else. */
    fun block(e: Expert): String {
        val base = appCtx?.let { Versions.override(it, e.id) } ?: e.prompt ?: (
            "You are Atlas, thinking with your ${e.name} expertise (${e.sector}). ${e.role} " +
                "Be practical, specific and concise; structure longer answers with short headings or bullets. " +
                "Flag anything with legal, medical or financial risk clearly."
            )
        val facts = if (e.fields.isBlank()) "" else " When useful, end with the key facts (${e.fields.replace(",", ", ")})."
        return "$MARK\n$base$facts\nYou are still Atlas: the same memory, the same voice and the same care. Never call yourself an agent or by another name."
    }

    const val MARK = "ATLAS EXPERTISE"

    private fun prefs(c: Context) = c.getSharedPreferences("expertise", 0)
    fun autoOn(c: Context) = prefs(c).getBoolean("auto", false)
    fun setAuto(c: Context, on: Boolean) = prefs(c).edit().putBoolean("auto", on).apply()

    /**
     * Picks the expertise a question needs, by meaning words, on the phone (no model call).
     * Returns null for everyday questions, so plain Atlas answers them.
     */
    fun pick(c: Context, question: String): Expert? {
        val q = question.lowercase()
        if (q.length < 24 || q.length > 400) return null
        val words = Regex("[a-z]{4,}").findAll(q).map { it.value }.filter { it !in STOP }.toSet()
        if (words.size < 3) return null
        var best: Expert? = null
        var bestScore = 0.0
        for (e in all(c)) {
            if (e.sector == "Core" || e.sector == "Yours" && e.prompt == null) continue
            val hay = (e.name + " " + e.role).lowercase()
            var s = 0.0
            var hits = 0
            // whole words only, so "located" never matches "network locate", "spent" never matches "spending policy"
            val hayWords = wordsOf(e)
            for (w in words) if (w in hayWords) { hits++; s += if (e.name.lowercase().contains(w)) 2.0 else 1.0 }
            if (hits >= 2 && s > bestScore) { bestScore = s; best = e }
        }
        return if (bestScore >= 4.0) best else null
    }

    /** Used by Ai.generate: the expertise block for a foreground question, or "" when plain Atlas is right. */
    fun autoBlock(c: Context?, prompt: String, system: String?): String {
        if (c == null || system?.contains(MARK) == true) return ""
        _pinned.value?.let { e ->
            noteUse(c, e)
            return block(e) + Training.block(c, e.id)
        }
        if (!autoOn(c)) return ""
        val question = lastUserText(prompt)
        // commands ("spent 500 on…", "remind me…") are Atlas's own job: no expertise, no extra words
        if (com.nizam.app.net.Router.classify(question, false, false, emptyList()) == com.nizam.app.net.Router.Lane.ACT) { _current.value = null; return "" }
        val e = pick(c, question) ?: run { _current.value = null; return "" }
        _current.value = e
        noteUse(c, e)
        Pulse.emit(Pulse.Kind.THINKING, "Thinking as ${e.name}", e.sector)
        // a short hint, not the full instructions: keeps every request small and fast
        return "$MARK\nFor this question, answer with the knowledge of a ${e.name.lowercase()} (${e.role.substringBefore('.').lowercase()}). You are still Atlas."
    }

    /** The user's latest words, without Atlas's snapshot, history or data blocks around them. */
    fun lastUserText(prompt: String): String {
        val t = prompt.trimEnd().removeSuffix("You:").removeSuffix("Atlas:").trimEnd()
        // Atlas's agent writes "User's latest message:", its fast path "Them:"; older tools use the others
        val cut = listOf("\nUser's latest message:", "\nThem:", "\nYou:", "\nUser:", "\nuser:", "\nMe:", "\nQuestion:", "QUESTION:")
            .maxOfOrNull { t.lastIndexOf(it) } ?: -1
        val tail = if (cut >= 0) t.substring(cut).substringAfter(':') else t.substringAfterLast("\n\n")
        return tail.trim().takeLast(400)
    }

    private val wordCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()
    private fun wordsOf(e: Expert): Set<String> = wordCache.getOrPut(e.id) {
        (e.name + " " + e.role).lowercase().split(Regex("[^a-z]+")).filter { it.length >= 4 }.toSet()
    }

    fun using(e: Expert?) { _current.value = e }

    fun hubFor(sector: String): Hub = when (sector) {
        "Sales & Marketing" -> Hub.Create
        "Customer Service", "Non-profit & Social" -> Hub.Social
        "Healthcare" -> Hub.Health
        "Legal", "Technology", "Government & Public Sector" -> Hub.Tools
        "Finance & Accounting", "Ecommerce & Retail", "Insurance" -> Hub.Money
        "Real Estate", "Construction & Property", "Agriculture" -> Hub.Home
        "Education" -> Hub.Learn
        "Hospitality", "Logistics & Supply Chain", "Automotive" -> Hub.Travel
        "Media & Entertainment" -> Hub.Media
        else -> Hub.Work
    }

    private val STOP = setOf(
        "what", "when", "where", "which", "with", "would", "could", "should", "about", "there", "their", "this", "that",
        "have", "from", "your", "into", "them", "they", "then", "than", "been", "being", "were", "will", "just", "like",
        "make", "know", "need", "want", "please", "help", "tell", "give", "some", "more", "also", "very", "much", "many",
        "does", "doing", "done", "here", "only", "after", "before", "today", "tomorrow", "atlas",
    )
}
