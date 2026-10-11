package com.nizam.app.feature.presence

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * The layer that makes Atlas feel present rather than reactive: what it can infer right now, what it
 * is allowed to do about it, what it has been asked to keep working toward, and how often it may
 * interrupt. Everything here is decided on-device; the AI is only called when something warrants it.
 */
object Presence {
    private fun p(c: Context) = c.getSharedPreferences("presence", 0)
    private fun f(c: Context, n: String) = File(c.filesDir, n)

    // ── autonomy dial, per area ──
    val AREAS = listOf("Logging", "Reminders", "Messaging", "Money", "Phone control")
    fun autonomy(c: Context, area: String) = p(c).getString("auto_$area", "Ask first")!!
    fun setAutonomy(c: Context, area: String, level: String) = p(c).edit().putString("auto_$area", level).apply()

    // ── interruption budget ──
    fun budgetPerDay(c: Context) = p(c).getInt("budget", 4)
    fun setBudget(c: Context, n: Int) = p(c).edit().putInt("budget", n).apply()
    fun spokenToday(c: Context): Int {
        val today = LocalDate.now().toString()
        return if (p(c).getString("spokeDay", "") == today) p(c).getInt("spokeCount", 0) else 0
    }
    fun canSpeak(c: Context) = spokenToday(c) < budgetPerDay(c)
    fun noteSpoke(c: Context) = p(c).edit().putString("spokeDay", LocalDate.now().toString())
        .putInt("spokeCount", spokenToday(c) + 1).apply()

    fun speakAloud(c: Context) = p(c).getBoolean("aloud", false)
    fun setSpeakAloud(c: Context, v: Boolean) = p(c).edit().putBoolean("aloud", v).apply()

    // ── how it addresses you, and what it's called ──
    fun atlasName(c: Context) = p(c).getString("name", "Atlas")!!
    fun setAtlasName(c: Context, n: String) = p(c).edit().putString("name", n.ifBlank { "Atlas" }).apply()

    // ── standing intentions: stated once, pursued indefinitely ──
    fun intentions(c: Context): List<String> = runCatching { JSONArray(f(c, "intentions.json").readText()) }
        .getOrElse { JSONArray() }.let { a -> (0 until a.length()).map { a.optString(it) } }
    fun setIntentions(c: Context, list: List<String>) = f(c, "intentions.json").writeText(JSONArray(list).toString())

    // ── working memory: what's live right now, separate from long-term data ──
    fun working(c: Context): List<Pair<String, Long>> = runCatching { JSONArray(f(c, "working.json").readText()) }
        .getOrElse { JSONArray() }.let { a -> (0 until a.length()).map { a.getJSONObject(it) }
            .map { it.optString("t") to it.optLong("at") } }
        .filter { System.currentTimeMillis() - it.second < 3 * 86_400_000L }      // expires after three days
    fun addWorking(c: Context, text: String) {
        val list = working(c) + (text to System.currentTimeMillis())
        f(c, "working.json").writeText(JSONArray().apply {
            list.takeLast(12).forEach { put(JSONObject().put("t", it.first).put("at", it.second)) } }.toString())
    }
    fun clearWorking(c: Context) = f(c, "working.json").writeText("[]")

    // ── what it inferred about right now, without any sensors that cost battery ──
    fun context(c: Context): String {
        val hour = LocalTime.now().hour
        val charging = runCatching {
            c.getSystemService(BatteryManager::class.java).isCharging
        }.getOrElse { false }
        val awake = runCatching { c.getSystemService(PowerManager::class.java).isInteractive }.getOrElse { true }
        val place = runCatching { com.nizam.app.feature.platform.PlaceStore.minutesToday(c).maxByOrNull { it.value }?.key }.getOrNull()
        val focusing = com.nizam.app.feature.growth.FocusFlag.active(c)
        val listening = com.nizam.app.feature.podcasts.Playback.isPlaying
        return listOfNotNull(
            when (hour) { in 5..8 -> "early morning"; in 9..12 -> "morning"; in 13..16 -> "afternoon"
                in 17..20 -> "evening"; in 21..23 -> "night"; else -> "late night" },
            if (charging) "phone charging" else null,
            if (!awake) "screen off" else null,
            place?.let { "spent most of today at $it" },
            if (focusing) "in a focus session" else null,
            if (listening) "listening to a podcast" else null
        ).joinToString(", ")
    }

    // ── the model of you, drafted from evidence and visible to you ──
    fun model(c: Context) = p(c).getString("model", "").orEmpty()
    fun setModel(c: Context, m: String) = p(c).edit().putString("model", m).apply()

    // ── nudges it made, and whether they were any use ──
    fun logNudge(c: Context, text: String) {
        val a = runCatching { JSONArray(f(c, "nudges.json").readText()) }.getOrElse { JSONArray() }
        a.put(JSONObject().put("t", text).put("at", System.currentTimeMillis()).put("useful", -1))
        f(c, "nudges.json").writeText(a.toString())
    }
    fun nudges(c: Context): List<Triple<Int, String, Int>> = runCatching { JSONArray(f(c, "nudges.json").readText()) }
        .getOrElse { JSONArray() }.let { a -> (0 until a.length()).map { Triple(it, a.getJSONObject(it).optString("t"), a.getJSONObject(it).optInt("useful", -1)) } }
    fun rateNudge(c: Context, index: Int, useful: Boolean) {
        val a = runCatching { JSONArray(f(c, "nudges.json").readText()) }.getOrElse { return }
        if (index < a.length()) { a.getJSONObject(index).put("useful", if (useful) 1 else 0); f(c, "nudges.json").writeText(a.toString()) }
    }

    /** Everything the AI should know about the moment it's speaking into. */
    fun forAi(c: Context): String = buildString {
        appendLine("Right now: ${context(c)}. You are called ${atlasName(c)}.")
        working(c).takeIf { it.isNotEmpty() }?.let { appendLine("Live context: " + it.joinToString("; ") { w -> w.first }) }
        intentions(c).takeIf { it.isNotEmpty() }?.let {
            appendLine("Standing intentions (keep finding ways to move these forward): " + it.joinToString("; ")) }
        model(c).takeIf { it.isNotBlank() }?.let { appendLine("What you've learned about them: ${it.take(700)}") }
        val tone = when {
            com.nizam.app.feature.body.Energy.today(c) == "Empty" -> "They're running on empty today — be gentler, shorter, and don't pile on."
            com.nizam.app.feature.body.Energy.today(c) == "Full" -> "They have energy today — you can be brisk and ambitious."
            else -> null
        }
        tone?.let { appendLine(it) }
        appendLine("Autonomy: " + AREAS.joinToString { "$it=${autonomy(c, it)}" } +
            ". Where it says 'Just do it', act without asking; where it says 'Ask first', propose and wait.")
    }
}
