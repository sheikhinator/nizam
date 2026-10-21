package com.nizam.app.feature.expertise

import android.content.Context
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.ui.design.Hub
import org.json.JSONObject

/**
 * Evolve: every version of every kind of expertise Atlas thinks with. Orion's improvements, your own edits
 * and Forge's new expertise all land here, and any version can be restored with one tap.
 */
object Versions {
    fun store(c: Context) = JsonStore(c, "expertise_versions")

    fun record(c: Context, expertId: String, prompt: String, why: String) {
        if (prompt.isBlank()) return
        store(c).add(JSONObject().put("expert", expertId).put("prompt", prompt).put("why", why.take(200)).put("at", System.currentTimeMillis()))
    }

    fun of(c: Context, expertId: String): List<JSONObject> =
        store(c).all().filter { it.optString("expert") == expertId }.sortedByDescending { it.optLong("at") }

    /** Everything that has more than its original version, newest change first. */
    fun changed(c: Context): List<Pair<String, List<JSONObject>>> =
        store(c).all().groupBy { it.optString("expert") }.map { it.key to it.value.sortedByDescending { o -> o.optLong("at") } }
            .sortedByDescending { it.second.first().optLong("at") }

    private fun prefs(c: Context) = c.getSharedPreferences("expertise_override", 0)

    /** The improved instructions in use for a built-in expertise, if Orion or you replaced them. */
    fun override(c: Context, expertId: String): String? = prefs(c).getString(expertId, null)?.ifBlank { null }

    fun apply(c: Context, expertId: String, prompt: String, why: String) {
        prefs(c).edit().putString(expertId, prompt).apply()
        record(c, expertId, prompt, why)
        Pulse.emit(Pulse.Kind.CHANGED, "Expertise updated", why, Hub.Work.label)
    }

    /** Back to the original: no override at all. */
    fun reset(c: Context, expertId: String) {
        prefs(c).edit().remove(expertId).apply()
        Pulse.emit(Pulse.Kind.CHANGED, "Expertise restored to its original", "", Hub.Work.label)
    }
}

/**
 * Training: worked examples Atlas learns an expertise from. Any good reply can be kept as an example; the
 * last few go with that expertise's instructions so the next answer follows the same shape.
 */
object Training {
    fun store(c: Context) = JsonStore(c, "expertise_examples")

    fun add(c: Context, expertId: String, question: String, answer: String) {
        store(c).add(JSONObject().put("expert", expertId).put("q", question.take(600)).put("a", answer.take(1800)).put("at", System.currentTimeMillis()))
        Pulse.emit(Pulse.Kind.CHANGED, "New worked example", "Training", Hub.Learn.label)
    }

    fun of(c: Context, expertId: String) = store(c).all().filter { it.optString("expert") == expertId }.sortedByDescending { it.optLong("at") }

    fun count(c: Context) = store(c).all().groupingBy { it.optString("expert") }.eachCount()

    /** Up to three examples, kept short so requests stay fast. */
    fun block(c: Context, expertId: String): String {
        val ex = of(c, expertId).take(3)
        if (ex.isEmpty()) return ""
        return "\n\nWorked examples the user approved (follow their shape and depth):\n" + ex.joinToString("\n\n") {
            "Q: ${it.optString("q").take(300)}\nA: ${it.optString("a").take(700)}"
        }
    }
}

/**
 * Life Laws: the user's own rules in plain words ("never schedule after Isha", "if I skip gym twice, move
 * Friday's cinema to Sunday"). They go with every request Atlas makes for the user, so the planner, the chat,
 * Nexus and every tool keep them.
 */
object Laws {
    fun store(c: Context) = JsonStore(c, "life_laws")
    fun all(c: Context) = store(c).all()
    fun active(c: Context) = all(c).filter { it.optBoolean("on", true) }

    fun add(c: Context, text: String) {
        store(c).add(JSONObject().put("text", text.trim()).put("on", true).put("at", System.currentTimeMillis()))
        Pulse.emit(Pulse.Kind.CHANGED, "New life law", text.take(80), Hub.Tools.label)
    }

    fun toggle(c: Context, id: String, on: Boolean) = store(c).update(id) { it.put("on", on) }
    fun remove(c: Context, id: String) = store(c).remove(id)

    @Volatile private var cached: Pair<Long, String>? = null

    /** For Ai.generate: the rules, read at most every 20 seconds. */
    fun block(c: Context?): String {
        c ?: return ""
        cached?.let { if (System.currentTimeMillis() - it.first < 20_000) return it.second }
        val laws = runCatching { active(c).map { it.optString("text") }.filter { it.isNotBlank() } }.getOrDefault(emptyList())
        val text = if (laws.isEmpty()) "" else "The user's life laws. Never plan, suggest or do anything that breaks them; " +
            "if a request would, say which law it breaks and offer a way that keeps it:\n" + laws.joinToString("\n") { "- $it" }
        cached = System.currentTimeMillis() to text
        return text
    }

    fun invalidate() { cached = null }
}

/**
 * Personas: the ways Atlas can speak. Atlas's built-in six stay; you can add your own (a tone, a register, a
 * habit of ending with a question). Atlas stays Atlas in every one, with the same memory.
 */
object Personas {
    fun store(c: Context) = JsonStore(c, "personas")
    fun custom(c: Context) = store(c).all()

    fun add(c: Context, name: String, style: String) {
        store(c).add(JSONObject().put("name", name.trim()).put("style", style.trim()))
        Pulse.emit(Pulse.Kind.CHANGED, "New way of speaking: ${name.trim()}", "Personas", Hub.Create.label)
    }

    fun remove(c: Context, id: String) = store(c).remove(id)

    fun names(c: Context?): List<String> = c?.let { runCatching { custom(it).map { o -> o.optString("name") } }.getOrNull() }.orEmpty()

    fun styleOf(c: Context?, name: String): String? = c?.let { ctx ->
        runCatching { custom(ctx).firstOrNull { it.optString("name") == name }?.optString("style") }.getOrNull()
    }
}

/**
 * Privacy dial per hub: what Atlas may read in each part of your life, changed live. A hub switched off is
 * invisible to every insight, lens, Oracle answer and chat, everywhere at once.
 */
object PrivacyDial {
    private fun prefs(c: Context) = c.getSharedPreferences("privacy_dial", 0)
    fun allowed(c: Context, hub: Hub) = prefs(c).getBoolean(hub.name, true)
    fun set(c: Context, hub: Hub, on: Boolean) {
        prefs(c).edit().putBoolean(hub.name, on).apply()
        Pulse.emit(Pulse.Kind.CHANGED, "${hub.label} is ${if (on) "readable" else "private"} now", "Privacy dial", Hub.Tools.label)
    }
    fun blocked(c: Context): Set<Hub> = Hub.entries.filter { !allowed(c, it) }.toSet()
}
