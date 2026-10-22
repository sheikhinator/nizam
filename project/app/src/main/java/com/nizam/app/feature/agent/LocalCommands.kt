package com.nizam.app.feature.agent

import com.nizam.app.AppContainer
import org.json.JSONObject

/**
 * Commands parsed on the device. No API call, no quota, no network, instant, works offline.
 * Roughly half of what anyone says to a tracking app is a simple log line — spending an API
 * request on "log 500ml water" is what burns a free tier by lunchtime.
 */
object LocalCommands {

    data class Hit(val action: String, val args: JSONObject, val spoken: String)

    private val numberWords = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10
    )

    private fun number(text: String): Double? {
        Regex("""(\d+(?:\.\d+)?)""").find(text)?.let { return it.groupValues[1].toDoubleOrNull() }
        numberWords.entries.firstOrNull { text.contains(it.key) }?.let { return it.value.toDouble() }
        return null
    }

    fun parse(raw: String): Hit? {
        val t = raw.trim().lowercase()
        if (t.isBlank() || t.length > 120) return null
        // questions are for Atlas to answer, never to log ("how much water should I drink?")
        if (t.endsWith("?") || Regex("""^(what|how|why|when|where|which|who|should|can|could|would|is|are|do|does|did i|tell|explain|kya|kaise|kitna)\b""").containsMatchIn(t)) return null

        // Water
        if (Regex("""\bwater\b""").containsMatchIn(t) && Regex("""\b(drank|drink|had|log|glass|glasses|cup|cups|ml|litre|liter|l)\b""").containsMatchIn(t)) {
            // the number that belongs to the water ("2 glasses", "500 ml", "1.5 litres"), not a time or a date
            val n = Regex("""(\d+(?:\.\d+)?)\s*(glass|glasses|cup|cups|ml|litre|liter|l)\b""").find(t)?.groupValues?.get(1)?.toDoubleOrNull()
                ?: numberWords.entries.firstOrNull { Regex("\\b${it.key}\\b\\s*(glass|cup)").containsMatchIn(t) }?.value?.toDouble() ?: 250.0
            val ml = if (t.contains("glass") || t.contains("cup")) n * 250 else
                if (t.contains("litre") || t.contains("liter") || n <= 5) n * 1000 else n
            return Hit("log_water", JSONObject().put("ml", ml.toInt()),
                "Logged ${ml.toInt()} ml of water.")
        }

        // Prayer
        val prayers = listOf("fajr", "dhuhr", "zuhr", "zohr", "asr", "maghrib", "isha")
        // whole words, and only with a word about praying: "message Aisha" is not Isha, "asr time?" is a question
        val praying = Regex("""\b(prayed|pray|offered|read|parhi|padhi|missed|did|done|logged|log|mark|qada|qaza|jamaat|jamaah|late)\b""").containsMatchIn(t)
        if (praying) prayers.firstOrNull { Regex("\\b$it\\b").containsMatchIn(t) }?.let { name ->
            val canonical = if (name == "zuhr" || name == "zohr") "dhuhr" else name
            val status = when {
                t.contains("jamaah") || t.contains("jamat") || t.contains("jamaat") || t.contains("congregation") -> "JAMAAH"
                t.contains("qada") || t.contains("qaza") || t.contains("made up") -> "QADA"
                t.contains("missed") || t.contains("didn't") || t.contains("did not") || t.contains("skipped") -> "NOT_PRAYED"
                t.contains("late") -> "LATE"
                else -> "ON_TIME"
            }
            return Hit(
                "log_prayer",
                JSONObject().put("prayer", canonical).put("status", status),
                "${canonical.replaceFirstChar { it.uppercase() }} marked ${status.lowercase().replace('_', ' ')}."
            )
        }

        // Gym set: "bench 60 x 8" / "log squat 100kg 5 reps"
        Regex("""(?:log |did )?([a-z ]{3,24}?)\s+(\d+(?:\.\d+)?)\s*(?:kg|kgs)?\s*(?:x|\*|×)\s*(\d+)|(?:log |did )?([a-z ]{3,24}?)\s+(\d+(?:\.\d+)?)\s*kgs?\s*(?:for\s+)?(\d+)\s*reps?""")
            .find(t)?.let { m ->
                val g = if (m.groupValues[1].isNotBlank()) 1 else 4
                val exercise = m.groupValues[g].removePrefix("log ").trim()
                    .replaceFirstChar { it.uppercase() }
                val weight = m.groupValues[g + 1].toDoubleOrNull() ?: return@let
                val reps = m.groupValues[g + 2].toIntOrNull() ?: return@let
                if (exercise.length < 3) return@let
                return Hit(
                    "log_set",
                    JSONObject().put("exercise", exercise).put("weight", weight).put("reps", reps),
                    "Logged $exercise ${weight}kg × $reps."
                )
            }

        // Expense: "spent 500 on food"
        Regex("""spent\s+(?:rs\.?\s*|pkr\s*)?(\d+(?:\.\d+)?)\s*(?:pkr|rs|rupees)?\s*(?:on\s+([a-z ]{2,20}))?""")
            .find(t)?.takeIf { !Regex("""spent\s+[\d.]+\s*(hours?|hrs?|minutes?|mins?|days?|weeks?|months?|years?)\b""").containsMatchIn(t) }?.let { m ->
                val amount = m.groupValues[1].toDoubleOrNull() ?: return@let
                val category = m.groupValues[2].trim().ifBlank { "Other" }
                    .replaceFirstChar { it.uppercase() }
                return Hit(
                    "log_expense",
                    JSONObject().put("amount", amount).put("category", category),
                    "Logged ${amount.toInt()} PKR on $category."
                )
            }

        // Task
        Regex("""^(?:add (?:a )?task|remind me to|todo)[: ]+(.{3,80})$""").find(t)?.let { m ->
            val title = raw.trim().takeLast(m.groupValues[1].length).trim()
            return Hit("add_task", JSONObject().put("title", title), "Task added: $title")
        }

        // Theme
        if (Regex("""\b(theme|palette)\b""").containsMatchIn(t)) {
            com.nizam.app.ui.theme.ATLAS_THEMES.map { it.key }
                .firstOrNull { Regex("\\b$it\\b").containsMatchIn(t) }?.let { key ->
                    return Hit("set_palette", JSONObject().put("key", key), "Theme set to $key.")
                }
        }

        return null
    }

    /** Runs a local command if one matches. Returns null when the model is genuinely needed. */
    suspend fun tryRun(container: AppContainer, message: String): String? {
        val hit = parse(message) ?: return null
        val result = Actions.run(container, hit.action, hit.args)
        return if (result.ok) hit.spoken else null
    }
}
