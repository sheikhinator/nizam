package com.nizam.app.feature.agent

import com.nizam.app.AppContainer
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/**
 * Maps what a model writes onto exactly what Atlas's actions expect, before any action runs. Models write
 * "breakfast" for "Breakfast", "~280" or "280 kcal" for 280, "food" for "name", "Fajr" for "fajr", "on time"
 * for ON_TIME, "tomorrow 5pm" for a date and time, a module's name for its key. Without this, each of those
 * failed validation (or, worse, silently landed in the wrong place). Every action goes through here once.
 */
object ArgFix {
    /** Other names models use for each action's keys. */
    private val ALIASES: Map<String, Map<String, List<String>>> = mapOf(
        "log_food" to mapOf("name" to listOf("food", "item", "title", "description", "meal_name", "dish", "foodName"),
            "kcal" to listOf("calories", "cal", "kcals", "energy", "calorie"), "protein" to listOf("protein_g", "proteinGrams", "proteins"),
            "slot" to listOf("meal", "mealType", "meal_type", "mealSlot", "time", "category")),
        "log_expense" to mapOf("amount" to listOf("price", "cost", "value", "rs", "pkr", "total", "spent"), "note" to listOf("description", "item", "title", "for", "details", "merchant"),
            "category" to listOf("type", "cat")),
        "log_income" to mapOf("amount" to listOf("value", "rs", "pkr", "total", "earned"), "note" to listOf("description", "source", "title", "from"), "category" to listOf("type")),
        "log_expense_on" to mapOf("amount" to listOf("price", "cost", "value", "total"), "note" to listOf("description", "item", "title"), "date" to listOf("day", "on")),
        "log_set" to mapOf("exercise" to listOf("name", "lift", "movement"), "weight" to listOf("kg", "weight_kg", "load", "weightKg"), "reps" to listOf("repetitions", "rep", "count")),
        "log_water" to mapOf("ml" to listOf("amount", "water", "milliliters", "millilitres", "volume")),
        "log_prayer" to mapOf("prayer" to listOf("name", "salah", "salat", "namaz"), "status" to listOf("state", "how")),
        "log_study" to mapOf("minutes" to listOf("duration", "mins", "time")),
        "add_task" to mapOf("title" to listOf("name", "task", "text", "description", "todo")),
        "complete_task" to mapOf("title" to listOf("name", "task", "text")),
        "rename_task" to mapOf("title" to listOf("newTitle", "name", "new_title")),
        "add_habit" to mapOf("name" to listOf("title", "habit"), "targetPerWeek" to listOf("frequency", "timesPerWeek", "perWeek", "target")),
        "add_note" to mapOf("title" to listOf("name", "subject", "heading"), "body" to listOf("content", "text", "note", "description")),
        "add_quote" to mapOf("text" to listOf("quote", "content"), "source" to listOf("author", "by")),
        "add_diary" to mapOf("body" to listOf("text", "entry", "content", "note"), "mood" to listOf("feeling", "rating")),
        "add_identity" to mapOf("text" to listOf("statement", "identity")),
        "create_goal" to mapOf("title" to listOf("goal", "name"), "why" to listOf("reason", "motivation"), "deadline" to listOf("by", "date", "due")),
        "set_targets" to mapOf("kcal" to listOf("calories", "calorieTarget"), "waterMl" to listOf("water", "waterTarget", "ml")),
        "set_theme" to mapOf("mode" to listOf("theme", "value")),
        "set_alarm" to mapOf("label" to listOf("title", "name", "note")),
        "set_timer" to mapOf("seconds" to listOf("duration", "secs"), "label" to listOf("title", "name")),
        "add_calendar_event" to mapOf("title" to listOf("name", "event", "summary"), "date" to listOf("day", "on"), "time" to listOf("start", "at", "startTime"),
            "durationMinutes" to listOf("duration", "minutes", "length"), "location" to listOf("place", "where")),
        "send_sms" to mapOf("who" to listOf("to", "contact", "number", "recipient"), "body" to listOf("message", "text")),
        "whatsapp" to mapOf("who" to listOf("to", "contact", "number", "recipient"), "body" to listOf("message", "text")),
        "call" to mapOf("who" to listOf("to", "contact", "number", "name")),
        "email" to mapOf("to" to listOf("recipient", "address"), "body" to listOf("message", "text")),
        "navigate" to mapOf("destination" to listOf("to", "place", "address", "where")),
        "open_app" to mapOf("name" to listOf("app", "package")),
        "remember_place" to mapOf("item" to listOf("thing", "object", "what"), "place" to listOf("location", "where")),
        "where_is" to mapOf("item" to listOf("thing", "object", "what")),
        "log_receipt" to mapOf("total" to listOf("amount", "price", "cost"), "merchant" to listOf("store", "shop", "vendor")),
        "edit_transaction" to mapOf("date" to listOf("day")),
        "shield_on" to mapOf("minutes" to listOf("duration", "mins")),
    )

    private val NUMBERS = setOf("amount", "kcal", "protein", "weight", "reps", "ml", "minutes", "mood", "priority", "targetPerWeek", "days",
        "count", "total", "warrantyMonths", "returnDays", "weeklyHours", "weeks", "waterMl", "trackId", "hour", "minute", "seconds",
        "durationMinutes", "percent", "id", "limit", "from")
    private val BOOLEANS = setOf("on", "auto", "send", "keepTime", "hanafiAsr")
    private val MODULE_ACTIONS = setOf("add_entry", "disable_module", "enable_module", "rename_module", "restyle_module", "clear_module_entries",
        "delete_module", "generate_entries", "read_module", "add_computed_field", "list_entries")

    suspend fun fix(container: AppContainer, action: String, raw: JSONObject): JSONObject {
        val a = JSONObject(raw.toString())
        // keys
        ALIASES[action]?.forEach { (key, others) ->
            if (!a.has(key) || a.optString(key).isBlank()) others.firstOrNull { a.has(it) && a.optString(it).isNotBlank() }?.let { a.put(key, a.get(it)) }
        }
        // numbers written as words or with units
        a.keys().asSequence().toList().forEach { k ->
            if (k in NUMBERS) { val v = a.opt(k); if (v is String) number(v)?.let { n -> a.put(k, if (n == Math.floor(n) && k !in setOf("amount", "kcal", "protein", "weight", "total")) n.toLong() else n) } }
            if (k in BOOLEANS) { val v = a.opt(k); if (v is String) a.put(k, v.trim().lowercase() in setOf("true", "yes", "on", "1", "y")) }
        }
        when (action) {
            "log_food" -> {
                a.put("slot", slot(a.optString("slot")))
                a.put("name", a.optString("name").trim().ifBlank { "Food" }.take(120))
                fixNonNegative(a, "kcal"); fixNonNegative(a, "protein")
            }
            "log_expense", "log_income", "log_expense_on" -> {
                fixNonNegative(a, "amount")
                a.put("category", a.optString("category").trim().ifBlank { "Other" }.replaceFirstChar { it.uppercase() })
                if (action == "log_expense_on") a.put("date", date(a.optString("date")) ?: LocalDate.now().toString())
            }
            "edit_transaction" -> if (a.has("date")) date(a.optString("date"))?.let { a.put("date", it) }
            "log_set" -> { fixNonNegative(a, "weight"); if (a.optInt("reps", 0) <= 0) a.put("reps", 1) }
            "log_water" -> {
                val glasses = number(raw.optString("glasses"))
                if (glasses != null && glasses > 0) a.put("ml", (glasses * 250).toInt())
                val ml = a.optDouble("ml", 250.0).let { if (it.isNaN()) 250.0 else it }
                a.put("ml", (if (ml in 0.1..10.0) ml * 1000 else ml).toInt().coerceIn(1, 10_000))   // "0.5" litres
            }
            "log_prayer" -> {
                prayer(a.optString("prayer"))?.let { a.put("prayer", it) }
                a.put("status", status(a.optString("status")))
            }
            "add_diary" -> a.put("mood", mood(raw.opt("mood") ?: raw.opt("feeling")))
            "add_task" -> {
                val p = raw.opt("priority")
                a.put("priority", when (p) { is Number -> p.toInt().coerceIn(0, 3); is String -> when (p.trim().lowercase()) {
                    "high", "urgent", "important", "p1" -> 3; "medium", "normal", "p2" -> 2; "low", "p3" -> 1; else -> number(p)?.toInt()?.coerceIn(0, 3) ?: 1 }; else -> 1 })
            }
            "add_habit" -> if (a.optInt("targetPerWeek", 0) !in 1..7) a.put("targetPerWeek", when (raw.optString("frequency").lowercase()) { "daily", "every day" -> 7; "weekly" -> 1; else -> 7 })
            "set_theme" -> a.put("mode", when (a.optString("mode").trim().lowercase()) { "dark", "night", "black" -> "DARK"; "light", "day", "white" -> "LIGHT"; else -> "SYSTEM" })
            "set_prayer_method" -> a.put("method", a.optString("method").uppercase().replace(' ', '_').replace("-", "_").let { m ->
                listOf("KARACHI", "MWL", "ISNA", "EGYPT", "UMM_AL_QURA").firstOrNull { m.contains(it) } ?: when {
                    m.contains("MUSLIM_WORLD") -> "MWL"; m.contains("MAKKAH") || m.contains("MECCA") || m.contains("UMM") -> "UMM_AL_QURA"; else -> "KARACHI" } })
            "add_calendar_event" -> {
                val (d, t) = dateTime(a.optString("date"), a.optString("time"))
                a.put("date", d); if (t != null) a.put("time", t)
                if (a.optInt("durationMinutes", 0) <= 0) a.put("durationMinutes", 60)
            }
            "set_alarm" -> {
                val txt = raw.optString("time").ifBlank { if (raw.opt("hour") is String) raw.optString("hour") else "" }
                if (txt.isNotBlank()) time(txt)?.let { t -> a.put("hour", t.hour); a.put("minute", t.minute) }
                a.put("hour", a.optInt("hour", 7).coerceIn(0, 23)); a.put("minute", a.optInt("minute", 0).coerceIn(0, 59))
                // repeat days as Calendar numbers (1 = Sunday … 7 = Saturday), from names or numbers
                a.optJSONArray("days")?.let { arr ->
                    val names = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
                    val out = JSONArray()
                    for (i in 0 until arr.length()) {
                        val v = arr.opt(i)
                        val n = when (v) { is Number -> v.toInt(); is String -> names.indexOfFirst { v.trim().lowercase().startsWith(it) }.let { k -> if (k >= 0) k + 1 else v.toIntOrNull() ?: -1 }; else -> -1 }
                        if (n in 1..7) out.put(n)
                    }
                    a.put("days", out)
                }
            }
            "set_timer" -> {
                val txt = raw.optString("duration").ifBlank { raw.optString("seconds") }
                val mins = number(raw.optString("minutes"))
                if (mins != null && mins > 0 && !raw.has("seconds")) a.put("seconds", (mins * 60).toInt())
                else if (Regex("min", RegexOption.IGNORE_CASE).containsMatchIn(txt)) number(txt)?.let { a.put("seconds", (it * 60).toInt()) }
                else if (Regex("hour|hr", RegexOption.IGNORE_CASE).containsMatchIn(txt)) number(txt)?.let { a.put("seconds", (it * 3600).toInt()) }
            }
            "replay_day" -> if (a.has("date")) date(a.optString("date"))?.let { a.put("date", it) }
            "log_receipt" -> { a.put("date", date(a.optString("date")) ?: LocalDate.now().toString()); fixNonNegative(a, "total") }
            "set_volume", "set_brightness" -> a.put("percent", a.optInt("percent", 50).coerceIn(0, 100))
        }
        if (action in MODULE_ACTIONS) moduleKey(container, a)
        if (action == "add_entry") entryValues(container, a)
        return a
    }

    private fun fixNonNegative(a: JSONObject, k: String) {
        val v = a.optDouble(k, 0.0)
        a.put(k, if (v.isNaN() || v.isInfinite()) 0.0 else kotlin.math.abs(v))
    }

    /** "~280", "280 kcal", "Rs 1,200", "2.5k", "1.5 lakh", "250-300" (the middle), "two" → numbers. */
    fun number(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val t = s.trim().lowercase().replace(",", "").replace("~", "").replace("≈", "").replace("about", "").replace("around", "")
        val words = mapOf("zero" to 0, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8,
            "nine" to 9, "ten" to 10, "a" to 1, "an" to 1, "half" to 0)
        words[t.trim()]?.let { return it.toDouble() }
        Regex("(-?\\d+(?:\\.\\d+)?)\\s*(?:-|to|–)\\s*(\\d+(?:\\.\\d+)?)").find(t)?.let { m ->
            return (m.groupValues[1].toDouble() + m.groupValues[2].toDouble()) / 2
        }
        val m = Regex("(-?\\d+(?:\\.\\d+)?)\\s*(k|thousand|lakh|lac|crore|cr|m|million)?\\b").find(t) ?: return null
        val n = m.groupValues[1].toDoubleOrNull() ?: return null
        return n * when (m.groupValues[2]) { "k", "thousand" -> 1e3; "lakh", "lac" -> 1e5; "crore", "cr" -> 1e7; "m", "million" -> 1e6; else -> 1.0 }
    }

    /** Breakfast, Lunch, Dinner or Snack: from any spelling, else from the time of day. */
    fun slot(s: String): String {
        val t = s.trim().lowercase()
        return when {
            t.startsWith("break") || t.contains("morning") || t.contains("nashta") || t.contains("sehri") || t.contains("suhoor") -> "Breakfast"
            t.startsWith("lunch") || t.contains("afternoon") || t.contains("dopahar") -> "Lunch"
            t.startsWith("dinner") || t.startsWith("supper") || t.contains("night") || t.contains("evening") || t.contains("iftar") || t.contains("raat") -> "Dinner"
            t.startsWith("snack") || t.contains("tea") -> "Snack"
            else -> when (LocalTime.now().hour) { in 4..10 -> "Breakfast"; in 11..15 -> "Lunch"; in 16..17 -> "Snack"; in 18..23 -> "Dinner"; else -> "Snack" }
        }
    }

    /** fajr, dhuhr, asr, maghrib or isha. Any other value would silently land on isha, so unknown names return null. */
    fun prayer(s: String): String? {
        val t = s.trim().lowercase().replace(" ", "")
        return when {
            t.startsWith("faj") || t == "subh" || t.startsWith("sub") -> "fajr"
            t.startsWith("dh") || t.startsWith("zu") || t.startsWith("zo") || t.startsWith("duh") || t.startsWith("jum") -> "dhuhr"
            t.startsWith("as") -> "asr"
            t.startsWith("mag") || t.startsWith("mgh") -> "maghrib"
            t.startsWith("is") || t.startsWith("es") -> "isha"
            else -> null
        }
    }

    fun status(s: String): String {
        val t = s.trim().lowercase().replace('_', ' ')
        return when {
            t.isBlank() || t.contains("on time") || t == "ontime" || t == "prayed" || t == "done" || t == "yes" || t == "true" -> "ON_TIME"
            t.contains("jama") || t.contains("congreg") || t.contains("masjid") || t.contains("mosque") -> "JAMAAH"
            t.contains("late") -> "LATE"
            t.contains("qad") || t.contains("qaz") || t.contains("makeup") || t.contains("make up") -> "QADA"
            t.contains("miss") || t.contains("not") || t.contains("skip") || t == "no" || t == "false" -> "NOT_PRAYED"
            else -> "ON_TIME"
        }
    }

    /** 1–5. Words ("good", "low") and 1–10 scales become 1–5. */
    fun mood(v: Any?): Int {
        when (v) {
            is Number -> { val n = v.toDouble(); return (if (n > 5) n / 2 else n).toInt().coerceIn(1, 5) }
            is String -> {
                number(v)?.let { n -> return (if (n > 5) n / 2 else n).toInt().coerceIn(1, 5) }
                val t = v.lowercase()
                return when {
                    listOf("great", "amazing", "excellent", "awesome", "happy", "fantastic").any { t.contains(it) } -> 5
                    listOf("good", "fine", "well", "positive").any { t.contains(it) } -> 4
                    listOf("bad", "sad", "low", "down", "tired", "stressed", "anxious").any { t.contains(it) } -> 2
                    listOf("awful", "terrible", "depressed", "worst").any { t.contains(it) } -> 1
                    else -> 3
                }
            }
            else -> return 3
        }
    }

    /** YYYY-MM-DD from "today", "tomorrow", "yesterday", "next monday", "22/10/2026", "22 Oct", "2026-10-22T…". */
    fun date(s: String?): String? {
        val t = s?.trim()?.lowercase().orEmpty()
        if (t.isBlank()) return null
        val today = LocalDate.now()
        when {
            t.startsWith("today") || t == "now" || t == "tonight" -> return today.toString()
            t.startsWith("tomorrow") || t.startsWith("kal") -> return today.plusDays(1).toString()
            t.startsWith("yesterday") -> return today.minusDays(1).toString()
            t.startsWith("day after") -> return today.plusDays(2).toString()
        }
        Regex("in (\\d+) (day|week|month)s?").find(t)?.let { m -> val n = m.groupValues[1].toLong()
            return when (m.groupValues[2]) { "day" -> today.plusDays(n); "week" -> today.plusWeeks(n); else -> today.plusMonths(n) }.toString() }
        java.time.DayOfWeek.entries.firstOrNull { t.contains(it.name.lowercase()) }?.let { dow ->
            var d = today.plusDays(1); while (d.dayOfWeek != dow) d = d.plusDays(1); return d.toString() }
        runCatching { return LocalDate.parse(t.take(10)).toString() }
        Regex("(\\d{1,2})[/.-](\\d{1,2})[/.-](\\d{2,4})").find(t)?.let { m ->
            val y = m.groupValues[3].toInt().let { if (it < 100) 2000 + it else it }
            return runCatching { LocalDate.of(y, m.groupValues[2].toInt(), m.groupValues[1].toInt()).toString() }.getOrNull()
        }
        val months = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
        Regex("(\\d{1,2})\\s*([a-z]{3})[a-z]*\\.?\\s*(\\d{4})?|([a-z]{3})[a-z]*\\.?\\s*(\\d{1,2})(?:st|nd|rd|th)?,?\\s*(\\d{4})?").find(t)?.let { m ->
            val (day, mon, yr) = if (m.groupValues[1].isNotBlank()) Triple(m.groupValues[1], m.groupValues[2], m.groupValues[3]) else Triple(m.groupValues[5], m.groupValues[4], m.groupValues[6])
            val mi = months.indexOf(mon.take(3))
            if (mi >= 0) return runCatching {
                var d = LocalDate.of(yr.toIntOrNull() ?: today.year, mi + 1, day.toInt())
                if (yr.isBlank() && d.isBefore(today.minusDays(60))) d = d.plusYears(1)
                d.toString()
            }.getOrNull()
        }
        return null
    }

    /** HH:mm from "5pm", "5:30 PM", "17:00:00", "half past 5", "noon". */
    fun time(s: String?): LocalTime? {
        val t = s?.trim()?.lowercase().orEmpty()
        if (t.isBlank()) return null
        if (t.contains("noon")) return LocalTime.of(12, 0)
        if (t.contains("midnight")) return LocalTime.of(0, 0)
        val m = Regex("(\\d{1,2})(?::(\\d{2}))?(?::\\d{2})?\\s*(am|pm|a\\.m\\.|p\\.m\\.)?").find(t) ?: return null
        var h = m.groupValues[1].toInt(); val min = m.groupValues[2].toIntOrNull() ?: 0
        val ap = m.groupValues[3]
        if (ap.startsWith("p") && h < 12) h += 12
        if (ap.startsWith("a") && h == 12) h = 0
        if (ap.isBlank() && h in 1..6 && (t.contains("evening") || t.contains("night") || t.contains("shaam"))) h += 12
        return runCatching { LocalTime.of(h.coerceIn(0, 23), min.coerceIn(0, 59)) }.getOrNull()
    }

    private fun dateTime(d: String, t: String): Pair<String, String?> {
        val day = date(d) ?: date(t) ?: LocalDate.now().toString()
        val at = time(t) ?: if (d.contains(":") || Regex("\\d\\s*(am|pm)").containsMatchIn(d.lowercase())) time(d) else null
        return day to at?.toString()
    }

    /** A module's key from its key, its name, or something close to its name. */
    private suspend fun moduleKey(container: AppContainer, a: JSONObject) {
        val given = a.optString("moduleKey").ifBlank { a.optString("module").ifBlank { a.optString("name") } }.trim()
        if (given.isBlank()) return
        if (container.dynamicRepository.spec(given) != null) { a.put("moduleKey", given); return }
        val all = runCatching { container.dynamicRepository.allSpecs().first() }.getOrDefault(emptyList())
        val low = given.lowercase()
        val hit = all.firstOrNull { it.key.equals(given, true) } ?: all.firstOrNull { it.name.equals(given, true) }
            ?: all.firstOrNull { it.name.lowercase().contains(low) || low.contains(it.name.lowercase()) }
            ?: all.firstOrNull { it.key.lowercase() == low.replace(Regex("[^a-z0-9]"), "") }
        hit?.let { a.put("moduleKey", it.key) }
    }

    /** Entry values keyed by each field's key, even when the model used the field's label; numbers and yes/no cleaned. */
    private suspend fun entryValues(container: AppContainer, a: JSONObject) {
        val spec = container.dynamicRepository.spec(a.optString("moduleKey")) ?: return
        val values = a.optJSONObject("values") ?: return
        val out = JSONObject()
        values.keys().forEach { k ->
            val f = spec.fields.firstOrNull { it.key == k } ?: spec.fields.firstOrNull { it.key.equals(k, true) || it.label.equals(k, true) }
                ?: spec.fields.firstOrNull { it.label.lowercase().replace(Regex("[^a-z0-9]"), "") == k.lowercase().replace(Regex("[^a-z0-9]"), "") }
            val v = values.opt(k)
            val clean: Any? = when (f?.type) {
                "NUMBER", "RATING" -> if (v is String) number(v) ?: v else v
                "BOOL" -> if (v is String) (v.trim().lowercase() in setOf("true", "yes", "y", "1", "done")) else v
                "DATE" -> if (v is String) date(v) ?: v else v
                else -> v
            }
            out.put(f?.key ?: k, clean)
        }
        a.put("values", out)
        if (a.optString("title").isBlank()) a.put("title", out.keys().asSequence().firstOrNull()?.let { out.optString(it) }.orEmpty().take(80).ifBlank { spec.name })
    }

    @Suppress("unused") private fun arr(a: JSONObject, k: String): JSONArray? = a.optJSONArray(k)
}
