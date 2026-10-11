package com.nizam.app.feature.jarvis

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * Everything that happens, in order: messages that arrived, apps opened, money spent, prayers,
 * tasks done, orders fired, missions advanced. One small JSON line per event, one file per day,
 * kept for 60 days and never leaving the phone.
 *
 * This is what makes "when did I last hear from Ali?" or "where did my evening go?" answerable.
 */
data class Moment(val at: Long, val kind: String, val title: String, val detail: String) {
    val time: String get() = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0).withSecond(0).toString()
}

object Timeline {
    private const val KEEP_DAYS = 60L
    private fun dir(c: Context) = File(c.filesDir, "timeline").apply { mkdirs() }
    private fun file(c: Context, day: LocalDate) = File(dir(c), "$day.jsonl")

    // Collapse repeats: the same app opened five times in a minute is one moment
    @Volatile private var last = ""
    @Volatile private var lastAt = 0L

    @Synchronized
    fun log(c: Context, kind: String, title: String, detail: String = "") {
        val key = "$kind|$title"
        val now = System.currentTimeMillis()
        if (key == last && now - lastAt < 60_000) return
        last = key; lastAt = now
        runCatching {
            file(c, LocalDate.now()).appendText(
                JSONObject().put("at", now).put("k", kind).put("t", title.take(160)).put("d", detail.take(300)).toString() + "\n")
        }
        // prune once a day, cheaply
        val p = c.getSharedPreferences("timeline", 0)
        val today = LocalDate.now().toString()
        if (p.getString("pruned", "") != today) {
            p.edit().putString("pruned", today).apply()
            val cutoff = LocalDate.now().minusDays(KEEP_DAYS).toString()
            dir(c).listFiles()?.filter { it.name.removeSuffix(".jsonl") < cutoff }?.forEach { it.delete() }
        }
    }

    fun day(c: Context, day: LocalDate): List<Moment> = runCatching {
        file(c, day).readLines().mapNotNull { line ->
            runCatching { JSONObject(line).let { Moment(it.optLong("at"), it.optString("k"), it.optString("t"), it.optString("d")) } }.getOrNull()
        }
    }.getOrElse { emptyList() }

    fun days(c: Context, count: Int): List<Pair<LocalDate, List<Moment>>> =
        (0 until count).map { LocalDate.now().minusDays(it.toLong()) }.map { it to day(c, it) }

    /** Everything matching a word across the kept history, newest first. */
    fun search(c: Context, query: String, limit: Int = 40): List<Pair<LocalDate, Moment>> {
        val q = query.lowercase().split(" ").filter { it.length > 2 }
        if (q.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<LocalDate, Moment>>()
        for (i in 0 until KEEP_DAYS.toInt()) {
            val d = LocalDate.now().minusDays(i.toLong())
            day(c, d).asReversed().forEach { m ->
                val hay = (m.title + " " + m.detail).lowercase()
                if (q.all { hay.contains(it) }) out += d to m
            }
            if (out.size >= limit) break
        }
        return out.take(limit)
    }

    /** A compact text of one day for the AI — counts first, then the moments. */
    fun forAi(c: Context, day: LocalDate = LocalDate.now()): String {
        val m = day(c, day)
        if (m.isEmpty()) return "No timeline recorded for $day."
        val counts = m.groupingBy { it.kind }.eachCount().entries.joinToString { "${it.value} ${it.key}" }
        return "Timeline for $day ($counts):\n" + m.takeLast(160).joinToString("\n") { "${it.time} [${it.kind}] ${it.title}" +
            if (it.detail.isNotBlank()) " — ${it.detail.take(100)}" else "" }
    }

    fun hourNow() = LocalTime.now().hour
}
