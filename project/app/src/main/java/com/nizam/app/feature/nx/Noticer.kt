package com.nizam.app.feature.nx

import android.content.Context
import java.time.LocalDate
import java.time.ZoneId

/**
 * Atlas looking after you: simple, local rules that read the roads and point out what needs attention.
 * It never acts on its own. It only suggests, and each suggestion opens the tool that helps.
 * The user can switch it off.
 */
object Noticer {
    data class Notice(val id: String, val title: String, val detail: String, val route: String, val level: Int)

    private const val DAY = 86_400_000L
    private fun prefs(c: Context) = c.getSharedPreferences("noticer", 0)
    fun enabled(c: Context) = prefs(c).getBoolean("on", true)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean("on", on).apply()

    private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
    private val MOOD = Regex("""mood (\d+)""")
    private val PRAYED = Regex("""(\d) of 5 prayed""")
    private val RUPEES = Regex("""Rs ([0-9,]+)""")

    private fun dayMillis(s: String): Long? =
        runCatching { LocalDate.parse(s).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()

    /**
     * One thing worth saying right now, at most once a day per hint, and only the important ones.
     * Used by the background loop, which already limits how often Atlas may interrupt.
     */
    suspend fun nudge(c: Context, container: com.nizam.app.AppContainer): String? {
        if (!enabled(c)) return null
        Roads.warm(container)
        val p = prefs(c)
        val day = LocalDate.now().toString()
        val told = if (p.getString("day", "") == day) p.getStringSet("told", emptySet()).orEmpty() else emptySet()
        val n = scan(c).firstOrNull { it.level >= 2 && it.id !in told } ?: return null
        p.edit().putString("day", day).putStringSet("told", told + n.id).apply()
        return "${n.title}. ${n.detail}"
    }

    fun scan(c: Context): List<Notice> {
        if (!enabled(c)) return emptyList()
        val items = Roads.items(c, 200)
        if (items.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val out = mutableListOf<Notice>()
        fun by(src: String) = items.filter { it.source == src }
        fun ageDays(ts: Long) = ((now - ts) / DAY).toInt()

        // mood dipping
        val moods = by("Mood check-ins").mapNotNull { MOOD.find(it.text)?.groupValues?.get(1)?.toIntOrNull() }
        if (moods.size >= 6) {
            val recent = moods.take(3).average(); val before = moods.drop(3).average()
            if (before > 0 && recent < before * 0.75) out += Notice("mood", "Your mood has dipped",
                "Your last three check-ins are lower than before. A small reset might help.", "nx/hardday", 2)
        }
        // prayers slipping
        val prayed = by("Prayer").filter { ageDays(it.ts) <= 7 }.mapNotNull { PRAYED.find(it.text)?.groupValues?.get(1)?.toIntOrNull() }
        if (prayed.size >= 3 && prayed.average() < 3.0) out += Notice("prayer", "Prayers have slipped this week",
            "You marked about ${"%.1f".format(prayed.average())} of 5 on average. A gentle plan for the day could help.", "nx/prayerplanner", 2)
        // spending jump
        fun spent(from: Long, to: Long) = by("Money").filter { it.text.startsWith("expense") && it.ts in from until to }
            .sumOf { RUPEES.find(it.text)?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull() ?: 0.0 }
        val thisWeek = spent(today - 6 * DAY, today + DAY); val lastWeek = spent(today - 13 * DAY, today - 6 * DAY)
        if (lastWeek > 0 && thisWeek > lastWeek * 1.3) out += Notice("spend", "Spending is up this week",
            "About Rs ${"%,.0f".format(thisWeek)} versus Rs ${"%,.0f".format(lastWeek)} the week before.", "nx/budgetfix", 2)
        // overdue tasks
        val overdue = by("Tasks").count { t -> t.text.contains("[open]") && DATE.find(t.text)?.value?.let { d -> (dayMillis(d) ?: Long.MAX_VALUE) < today } == true }
        if (overdue >= 3) out += Notice("tasks", "$overdue tasks are overdue", "Sort what to do, move or drop.", "nx/honestplanner", 2)
        // a habit gone quiet
        val habitDays = by("Habits").groupBy { it.text.substringBefore(" done on") }
        habitDays.forEach { (name, list) ->
            if (list.size >= 3 && ageDays(list.maxOf { it.ts }) >= 5) out += Notice("habit_$name", "\"$name\" has gone quiet",
                "Last done ${ageDays(list.maxOf { it.ts })} days ago. Want to find what is blocking it?", "nx/habitmechanic", 1)
        }
        // no exercise
        val moved = by("Workouts") + by("Cardio")
        if (moved.isNotEmpty() && ageDays(moved.maxOf { it.ts }) >= 10) out += Notice("move", "No workout for ${ageDays(moved.maxOf { it.ts })} days",
            "A short home session could restart it.", "nx/homeworkout", 1)
        // diary silence
        val diary = by("Diary")
        if (diary.isNotEmpty() && ageDays(diary.maxOf { it.ts }) >= 7) out += Notice("diary", "You have not written in a week",
            "A few prompts can make it easy.", "nx/journalprompts", 0)
        // promises due soon and follow-ups due
        by("Promises Made").forEach { p ->
            val due = DATE.find(p.text.substringAfter("Due:", ""))?.value?.let { dayMillis(it) }
            if (due != null && p.text.contains("Status: Open") && due - today <= 2 * DAY)
                out += Notice("promise_${p.text.hashCode()}", "A promise is due soon", p.text.take(110), "nx/promisekeeper", 2)
        }
        by("Waiting On").forEach { w ->
            val chase = DATE.find(w.text.substringAfter("Chase on:", ""))?.value?.let { dayMillis(it) }
            if (chase != null && chase <= today) out += Notice("wait_${w.text.hashCode()}", "Time to follow up", w.text.take(110), "nx/waitwatch", 1)
        }
        // once a week, a look-back on what Atlas has learned, to be corrected
        if (LocalDate.now().dayOfWeek == java.time.DayOfWeek.SUNDAY) out += Notice("weekly", "Your weekly look-back is ready",
            "See what Atlas has learned about you this week and correct anything it got wrong.", "nx/atlaslearned", 1)
        // everything gone quiet
        val last = items.maxOf { it.ts }
        if (last > 0 && ageDays(last) >= 4) out += Notice("quiet", "Nothing recorded for ${ageDays(last)} days",
            "Pick up where you left off?", "nx/pickup", 0)
        return out.sortedByDescending { it.level }
    }
}
