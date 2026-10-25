package com.nizam.app.ui.screens.tasks

import com.nizam.app.data.db.Task
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/**
 * Understands a task the way people type or say it:
 * "call Ali tomorrow 5pm !! ~20m", "pay rent every month 1st", "gym every weekday 7am", "kal bank jana".
 * Everything recognised is pulled out of the title and shown back as chips before saving.
 */
object TaskParse {
    data class Parsed(val title: String, val date: LocalDate?, val time: LocalTime?, val priority: Int, val rule: String?, val estimate: Int?) {
        fun due(): Long? = date?.let { d -> LocalDateTime.of(d, time ?: LocalTime.of(9, 0)).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }
        fun chips(): List<String> = listOfNotNull(
            date?.let { TaskSmart.dayLabel(it) + (time?.let { t -> " " + TaskSmart.clock(t) } ?: "") },
            when (priority) { 2 -> "High priority"; 0 -> "Low priority"; else -> null },
            rule?.let { "↻ " + TaskSmart.ruleLabel(it) },
            estimate?.let { "~${TaskSmart.mins(it)}" })
    }

    private val days = mapOf("monday" to DayOfWeek.MONDAY, "mon" to DayOfWeek.MONDAY, "tuesday" to DayOfWeek.TUESDAY, "tue" to DayOfWeek.TUESDAY,
        "wednesday" to DayOfWeek.WEDNESDAY, "wed" to DayOfWeek.WEDNESDAY, "thursday" to DayOfWeek.THURSDAY, "thu" to DayOfWeek.THURSDAY,
        "friday" to DayOfWeek.FRIDAY, "fri" to DayOfWeek.FRIDAY, "jumma" to DayOfWeek.FRIDAY, "saturday" to DayOfWeek.SATURDAY, "sat" to DayOfWeek.SATURDAY,
        "sunday" to DayOfWeek.SUNDAY, "sun" to DayOfWeek.SUNDAY)
    private val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

    fun parse(input: String, today: LocalDate = LocalDate.now()): Parsed {
        var s = " " + input.trim() + " "
        var date: LocalDate? = null
        var time: LocalTime? = null
        var priority = 1
        var rule: String? = null
        var est: Int? = null
        fun cut(r: Regex, f: (MatchResult) -> Unit) { r.find(s)?.let { m -> f(m); s = s.removeRange(m.range).let { " $it " } } }
        val i = RegexOption.IGNORE_CASE

        cut(Regex("""\s(!{2,}|!high|!urgent|urgent|asap|zaroori)\s""", i)) { priority = 2 }
        cut(Regex("""\s(!low|!later|someday)\s""", i)) { priority = 0 }
        cut(Regex("""\s!\s""")) { priority = 2 }
        cut(Regex("""\s~\s?(\d+(?:\.\d+)?)\s?(h|hr|hrs|hours?|m|min|mins|minutes?)\s""", i)) { m ->
            val n = m.groupValues[1].toDouble(); est = if (m.groupValues[2].startsWith("h", true)) (n * 60).toInt() else n.toInt()
        }
        cut(Regex("""\s(every\s?day|daily|roz|har\s?roz)\s""", i)) { rule = "daily" }
        cut(Regex("""\s(every\s?weekday|weekdays)\s""", i)) { rule = "weekdays" }
        cut(Regex("""\s(every\s?week|weekly)\s""", i)) { rule = "weekly" }
        cut(Regex("""\s(every\s?month|monthly)\s""", i)) { rule = "monthly" }
        cut(Regex("""\s(every\s?year|yearly|annually)\s""", i)) { rule = "yearly" }
        cut(Regex("""\severy\s(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun|jumma)\s""", i)) { m ->
            rule = "weekly"; date = today.with(TemporalAdjusters.nextOrSame(days.getValue(m.groupValues[1].lowercase())))
        }
        // time
        cut(Regex("""\s(?:at\s)?(\d{1,2})(?::(\d{2}))?\s?(am|pm)\s""", i)) { m ->
            var h = m.groupValues[1].toInt() % 12; if (m.groupValues[3].equals("pm", true)) h += 12
            time = LocalTime.of(h.coerceIn(0, 23), (m.groupValues[2].toIntOrNull() ?: 0).coerceIn(0, 59))
        }
        if (time == null) cut(Regex("""\s(?:at\s)?([01]?\d|2[0-3]):([0-5]\d)\s""")) { m -> time = LocalTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt()) }
        cut(Regex("""\s(tonight|aaj\s?raat)\s""", i)) { date = today; if (time == null) time = LocalTime.of(20, 0) }
        cut(Regex("""\s(this\s)?(morning|subah)\s""", i)) { if (time == null) time = LocalTime.of(9, 0) }
        cut(Regex("""\s(this\s)?(evening|shaam)\s""", i)) { if (time == null) time = LocalTime.of(18, 0) }
        // date
        if (date == null) cut(Regex("""\s(today|aaj)\s""", i)) { date = today }
        if (date == null) cut(Regex("""\s(day after tomorrow|parson)\s""", i)) { date = today.plusDays(2) }
        if (date == null) cut(Regex("""\s(tomorrow|tmrw|tmr|kal)\s""", i)) { date = today.plusDays(1) }
        if (date == null) cut(Regex("""\snext\sweek\s""", i)) { date = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)) }
        if (date == null) cut(Regex("""\snext\smonth\s""", i)) { date = today.plusMonths(1).withDayOfMonth(1) }
        if (date == null) cut(Regex("""\sin\s(\d{1,3})\s(days?|weeks?)\s""", i)) { m ->
            val n = m.groupValues[1].toLong(); date = if (m.groupValues[2].startsWith("w", true)) today.plusWeeks(n) else today.plusDays(n)
        }
        if (date == null) cut(Regex("""\s(?:on\s|next\s)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday|mon|tue|wed|thu|fri|sat|sun|jumma)\s""", i)) { m ->
            date = today.with(TemporalAdjusters.next(days.getValue(m.groupValues[1].lowercase())))
        }
        if (date == null) cut(Regex("""\s(?:on\s)?(\d{1,2})(?:st|nd|rd|th)?\s(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\s""", i)) { m ->
            date = monthDay(today, months.indexOf(m.groupValues[2].lowercase().take(3)) + 1, m.groupValues[1].toInt())
        }
        if (date == null) cut(Regex("""\s(?:on\s)?(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\s(\d{1,2})(?:st|nd|rd|th)?\s""", i)) { m ->
            date = monthDay(today, months.indexOf(m.groupValues[1].lowercase().take(3)) + 1, m.groupValues[2].toInt())
        }
        if (date == null) cut(Regex("""\s(?:on\s)?(\d{1,2})/(\d{1,2})\s""")) { m -> date = monthDay(today, m.groupValues[2].toInt(), m.groupValues[1].toInt()) }
        if (date == null && rule == "monthly") cut(Regex("""\s(?:on\s)?(?:the\s)?(\d{1,2})(?:st|nd|rd|th)\s""", i)) { m ->
            val d = m.groupValues[1].toInt().coerceIn(1, 28); date = if (today.dayOfMonth <= d) today.withDayOfMonth(d) else today.plusMonths(1).withDayOfMonth(d)
        }
        if (date == null && time != null) date = if (time!! > LocalTime.now()) today else today.plusDays(1)
        if (date == null && rule != null) date = if (rule == "weekdays" && today.dayOfWeek.value >= 6) today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)) else today
        val title = s.replace(Regex("\\s+"), " ").trim().trimEnd(',', '-', '.').trim()
        return Parsed(title.ifBlank { input.trim() }, date, time, priority, rule, est)
    }

    private fun monthDay(today: LocalDate, month: Int, day: Int): LocalDate? = runCatching {
        val d = LocalDate.of(today.year, month.coerceIn(1, 12), day.coerceIn(1, 31))
        if (d.isBefore(today)) d.plusYears(1) else d
    }.getOrNull()
}

object TaskSmart {
    private val zone: ZoneId get() = ZoneId.systemDefault()

    fun date(t: Task): LocalDate? = t.due?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }
    fun timeOf(t: Task): LocalTime? = t.due?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }?.takeIf { it != LocalTime.of(9, 0) }

    fun dayLabel(d: LocalDate, today: LocalDate = LocalDate.now()): String = when {
        d == today -> "Today"
        d == today.plusDays(1) -> "Tomorrow"
        d == today.minusDays(1) -> "Yesterday"
        d.isAfter(today) && d.isBefore(today.plusDays(7)) -> d.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
        else -> d.format(java.time.format.DateTimeFormatter.ofPattern(if (d.year == today.year) "EEE d MMM" else "d MMM yyyy"))
    }
    fun clock(t: LocalTime): String = t.format(java.time.format.DateTimeFormatter.ofPattern("h:mm a")).lowercase()
    fun mins(m: Int): String = if (m >= 60) "${m / 60}h" + (if (m % 60 > 0) " ${m % 60}m" else "") else "${m}m"
    fun ruleLabel(r: String) = when (r) { "daily" -> "Every day"; "weekdays" -> "Weekdays"; "weekly" -> "Every week"; "monthly" -> "Every month"; "yearly" -> "Every year"; else -> r }

    /** When a repeating task is ticked, the next one appears on its own. */
    fun next(t: Task): Task? {
        val rule = t.recurrenceRule ?: return null
        val now = LocalDateTime.now()
        val base = t.due?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDateTime() } ?: now
        fun step(x: LocalDateTime): LocalDateTime = when (rule) {
            "daily" -> x.plusDays(1)
            "weekdays" -> x.plusDays(if (x.dayOfWeek == DayOfWeek.FRIDAY) 3 else if (x.dayOfWeek == DayOfWeek.SATURDAY) 2 else 1)
            "weekly" -> x.plusWeeks(1)
            "monthly" -> x.plusMonths(1)
            "yearly" -> x.plusYears(1)
            else -> x.plusDays(1)
        }
        var n = step(base)
        var guard = 0
        while (n.toLocalDate().isBefore(now.toLocalDate()) && guard++ < 400) n = step(n)
        return t.copy(id = 0, due = n.atZone(zone).toInstant().toEpochMilli(), completedAt = null, createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(), deletedAt = null)
    }

    fun withDate(t: Task, d: LocalDate?): Task {
        val time = timeOf(t) ?: LocalTime.of(9, 0)
        return t.copy(due = d?.let { LocalDateTime.of(it, time).atZone(zone).toInstant().toEpochMilli() }, updatedAt = System.currentTimeMillis())
    }

    enum class View(val label: String) { TODAY("Today"), UPCOMING("Upcoming"), OVERDUE("Overdue"), SOMEDAY("Someday"), ALL("All"), DONE("Done") }

    fun filter(all: List<Task>, v: View, today: LocalDate = LocalDate.now()): List<Task> {
        val open = all.filter { it.completedAt == null }
        val prio = compareByDescending<Task> { it.priority }
        return when (v) {
            View.TODAY -> open.filter { date(it)?.let { d -> !d.isAfter(today) } == true }.sortedWith(compareBy<Task> { date(it) == today }.then(prio).thenBy { it.due })
            View.UPCOMING -> open.filter { date(it)?.isAfter(today) == true }.sortedBy { it.due }
            View.OVERDUE -> open.filter { date(it)?.isBefore(today) == true }.sortedBy { it.due }
            View.SOMEDAY -> open.filter { it.due == null }.sortedWith(prio.thenByDescending { it.createdAt })
            View.ALL -> open.sortedWith(compareBy<Task> { it.due == null }.thenBy { it.due }.then(prio))
            View.DONE -> all.filter { it.completedAt != null }.sortedByDescending { it.completedAt }.take(80)
        }
    }

    /** Checklist lines kept in the task's notes ("- [ ] step"), so steps need no new storage. */
    fun steps(notes: String): List<Pair<Boolean, String>> = notes.lines().mapNotNull { l ->
        Regex("""^- \[( |x)] (.+)$""").find(l.trim())?.let { (it.groupValues[1] == "x") to it.groupValues[2] }
    }
    fun notesBody(notes: String) = notes.lines().filterNot { Regex("""^- \[( |x)] """).containsMatchIn(it.trim()) }.joinToString("\n").trim()
    fun compose(body: String, steps: List<Pair<Boolean, String>>) =
        (listOf(body.trim()).filter { it.isNotBlank() } + steps.map { (d, s) -> "- [${if (d) "x" else " "}] $s" }).joinToString("\n")
}
