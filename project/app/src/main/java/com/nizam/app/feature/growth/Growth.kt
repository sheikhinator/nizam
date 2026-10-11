package com.nizam.app.feature.growth

import android.content.Context
import com.nizam.app.feature.insight.DayVector
import com.nizam.app.feature.progress.ProgressSummary
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

private fun File.arr() = runCatching { JSONArray(readText()) }.getOrElse { JSONArray() }

// ── Challenges: a metric, a daily target, a finish line ────────────────────────
data class Challenge(val id: Long, val title: String, val metric: String, val target: Double, val start: String, val days: Int)
data class ChallengeProgress(val hit: Int, val elapsed: Int, val done: Boolean, val won: Boolean)

class ChallengeStore(c: Context) {
    private val f = File(c.filesDir, "challenges.json")
    fun all() = f.arr().let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
        Challenge(it.optLong("id"), it.optString("title"), it.optString("metric"), it.optDouble("target"),
            it.optString("start"), it.optInt("days", 30)) } }
    fun save(l: List<Challenge>) = f.writeText(JSONArray().apply { l.forEach {
        put(JSONObject().put("id", it.id).put("title", it.title).put("metric", it.metric)
            .put("target", it.target).put("start", it.start).put("days", it.days)) } }.toString())

    companion object {
        /** A day counts when its value meets the target. Winning means 80% of days — perfection isn't required. */
        fun progress(ch: Challenge, days: List<DayVector>): ChallengeProgress {
            val start = LocalDate.parse(ch.start); val end = start.plusDays(ch.days.toLong())
            val window = days.filter { it.date >= start && it.date < end && it.date <= LocalDate.now() }
            val hit = window.count { (it.values[ch.metric] ?: 0.0) >= ch.target }
            val done = !LocalDate.now().isBefore(end)
            return ChallengeProgress(hit, window.size, done, done && hit >= (ch.days * 0.8).toInt())
        }
    }
}

// ── Habit stacks: new habits chained onto things you already do ───────────────
data class Stack(val id: Long, val anchor: String, val steps: List<String>)

class StackStore(c: Context) {
    private val f = File(c.filesDir, "stacks.json")
    fun all() = f.arr().let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
        val s = o.optJSONArray("steps") ?: JSONArray()
        Stack(o.optLong("id"), o.optString("anchor"), (0 until s.length()).map { s.optString(it) }) } }
    fun save(l: List<Stack>) = f.writeText(JSONArray().apply { l.forEach {
        put(JSONObject().put("id", it.id).put("anchor", it.anchor).put("steps", JSONArray(it.steps))) } }.toString())
    /** Today's completed step indexes per stack. */
    fun doneToday(c: Context): MutableSet<String> =
        c.getSharedPreferences("stacks", Context.MODE_PRIVATE).getStringSet(LocalDate.now().toString(), emptySet())!!.toMutableSet()
    fun markDone(c: Context, key: String, on: Boolean) {
        val p = c.getSharedPreferences("stacks", Context.MODE_PRIVATE); val set = doneToday(c)
        if (on) set += key else set -= key
        p.edit().putStringSet(LocalDate.now().toString(), set).apply()
    }
}

// ── Achievements: derived from what you've actually done, never granted ───────
data class Badge(val emoji: String, val name: String, val how: String, val earned: Boolean)

object Badges {
    fun all(s: ProgressSummary, days: List<DayVector>): List<Badge> {
        fun total(k: String) = days.sumOf { it.values[k] ?: 0.0 }
        fun daysWith(k: String) = days.count { (it.values[k] ?: 0.0) > 0 }
        val fivePrayerDays = days.count { (it.values["prayers"] ?: 0.0) >= 5 }
        return listOf(
            Badge("🌱", "First step", "Earn any XP", s.totalXp > 0),
            Badge("🔥", "One week", "7-day streak", s.bestStreak >= 7),
            Badge("⚡", "Unbroken month", "30-day streak", s.bestStreak >= 30),
            Badge("🕌", "Steadfast", "All five prayers on 7 days", fivePrayerDays >= 7),
            Badge("🌙", "Devoted", "All five prayers on 30 days", fivePrayerDays >= 30),
            Badge("🏋️", "Iron", "Log 100 sets", total("sets") >= 100),
            Badge("🦾", "Forged", "Log 500 sets", total("sets") >= 500),
            Badge("📚", "Scholar", "Study 10 hours", total("study") >= 600),
            Badge("🎓", "Master's path", "Study 100 hours", total("study") >= 6000),
            Badge("⏱", "Deep diver", "Focus 20 hours", total("focus") >= 1200),
            Badge("😴", "Well rested", "Sleep 7h+ on 14 days", days.count { (it.values["sleep"] ?: 0.0) >= 7 } >= 14),
            Badge("👟", "Walker", "10k steps on 10 days", days.count { (it.values["steps"] ?: 0.0) >= 10_000 } >= 10),
            Badge("✍️", "Reflective", "Log mood on 30 days", daysWith("mood") >= 30),
            Badge("⭐", "Level 5", "Reach level 5", s.level >= 5),
            Badge("🏆", "Level 10", "Reach level 10", s.level >= 10),
            Badge("🧊", "Insured", "Hold a streak freeze", s.freezes > 0)
        )
    }
}

/** Shared flag the town reads to know you're in a focus session. */
object FocusFlag {
    fun set(c: Context, until: Long) = c.getSharedPreferences("focus", Context.MODE_PRIVATE).edit().putLong("until", until).apply()
    fun active(c: Context) = System.currentTimeMillis() < c.getSharedPreferences("focus", Context.MODE_PRIVATE).getLong("until", 0)
}
