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

        // Water
        if (Regex("""\b(water|drank|drink)\b""").containsMatchIn(t)) {
            val n = number(t) ?: 250.0
            val ml = if (t.contains("glass") || t.contains("cup")) n * 250 else
                if (t.contains("litre") || t.contains("liter") || n <= 5) n * 1000 else n
            return Hit("log_water", JSONObject().put("ml", ml.toInt()),
                "Logged ${ml.toInt()} ml of water.")
        }

        // Prayer
        val prayers = listOf("fajr", "dhuhr", "zuhr", "asr", "maghrib", "isha")
        prayers.firstOrNull { t.contains(it) }?.let { name ->
            val canonical = if (name == "zuhr") "dhuhr" else name
            val status = when {
                t.contains("jamaah") || t.contains("jamat") || t.contains("congregation") -> "JAMAAH"
                t.contains("late") -> "LATE"
                t.contains("qada") || t.contains("missed") || t.contains("made up") -> "QADA"
                else -> "ON_TIME"
            }
            return Hit(
                "log_prayer",
                JSONObject().put("prayer", canonical).put("status", status),
                "${canonical.replaceFirstChar { it.uppercase() }} marked ${status.lowercase().replace('_', ' ')}."
            )
        }

        // Gym set: "bench 60 x 8" / "log squat 100kg 5 reps"
        Regex("""(?:log |did )?([a-z ]{3,24}?)\s+(\d+(?:\.\d+)?)\s*(?:kg)?\s*(?:x|for|\*)\s*(\d+)""")
            .find(t)?.let { m ->
                val exercise = m.groupValues[1].removePrefix("log ").trim()
                    .replaceFirstChar { it.uppercase() }
                val weight = m.groupValues[2].toDoubleOrNull() ?: return@let
                val reps = m.groupValues[3].toIntOrNull() ?: return@let
                if (exercise.length < 3) return@let
                return Hit(
                    "log_set",
                    JSONObject().put("exercise", exercise).put("weight", weight).put("reps", reps),
                    "Logged $exercise ${weight}kg × $reps."
                )
            }

        // Expense: "spent 500 on food"
        Regex("""spent\s+(\d+(?:\.\d+)?)\s*(?:pkr|rs)?\s*(?:on\s+([a-z ]{2,20}))?""")
            .find(t)?.let { m ->
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
