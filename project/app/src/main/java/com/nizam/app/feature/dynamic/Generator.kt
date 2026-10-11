package com.nizam.app.feature.dynamic

import com.nizam.app.AppContainer
import com.nizam.app.net.Ai
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * Generation, not just conversation. Every module can ask the AI to produce real entries that
 * match its own field schema — recipes, workouts, vocabulary, ideas — written straight into the
 * module rather than left in a chat bubble.
 */
class ModuleGenerator(private val container: () -> AppContainer) {

    suspend fun generate(spec: ModuleSpec, instruction: String, count: Int = 3): Int {
        val c = container()
        val existing = c.dynamicRepository.entries(spec.key).first().take(15)

        // FILE fields hold a real file the user picks; COMPUTED fields are calculated. The AI
        // must not write either — invented text in a FILE field produced dead ▶ links.
        val fillable = spec.fields.filter { it.type != "FILE" && it.type != "COMPUTED" }
        val fieldSpec = fillable.joinToString("\n") { field ->
            val options = if (field.options.isEmpty()) "" else " — one of: ${field.options.joinToString(", ")}"
            val required = if (field.required) " — required when visible" else ""
            val condition = if (field.conditionFieldKey.isBlank()) "" else
                " — visible only when ${field.conditionFieldKey} = ${field.conditionValue}"
            "  ${field.key} (${field.type})$options$required$condition"
        }

        val prompt = """
            Generate $count new entries for the user's "${spec.name}" module.
            What the module is for: ${spec.tagline}
            What the user asked for: ${instruction.ifBlank { "whatever fits best" }}

            Each entry has a title plus these fields:
            $fieldSpec

            ${if (existing.isEmpty()) "They have no entries yet."
            else "What they already have (do not repeat these):\n" +
                existing.joinToString("\n") { "  " + it.title }}

            Rules:
            - Be specific and usable. A recipe needs real ingredients with quantities and real steps.
              A workout needs real exercises, sets and reps. A vocabulary entry needs a real word.
            - Fit the user's context: Pakistan, Lahore, PKR, halal, locally available ingredients
              and equipment.
            - RATING fields take "1" to "5". NUMBER fields take digits only. CHOICE fields must use
              one of the listed options exactly.
            - MULTI_SELECT fields contain a JSON array of listed options. TAGS fields contain
              semicolon-separated tags. Do not fill fields whose visibility condition is false.

            Return ONLY valid JSON:
            {"entries":[{"title":"...","values":{"choice":"Option","multiSelect":["A","B"],"tags":"one; two"}}]}
        """.trimIndent()

        val raw = Ai.generate(
            settings = c.settingsRepository,
            prompt = prompt,
            system = "You generate practical, specific content that fits a strict field schema. " +
                "You return only valid JSON.",
            maxOutputTokens = 8192
        )

        val array = JSONObject(Ai.cleanJson(raw)).optJSONArray("entries") ?: JSONArray()
        var written = 0
        for (i in 0 until array.length()) {
            val entry = array.getJSONObject(i)
            val title = entry.optString("title")
            if (title.isBlank()) continue
            val values = entry.optJSONObject("values") ?: JSONObject()
            val fieldsByKey = fillable.associateBy { it.key }
            val map = linkedMapOf<String, String>()
            var invalidValue = false
            values.keys().asSequence().filter { it in fieldsByKey }.forEach { key ->
                val field = fieldsByKey.getValue(key)
                val rawValue = values.opt(key)
                val textValue = when (rawValue) {
                    is JSONArray -> (0 until rawValue.length()).joinToString(";") { rawValue.optString(it) }
                    null, JSONObject.NULL -> ""
                    else -> rawValue.toString()
                }
                val normalized = ModuleCsvImport.validateAndNormalizeValue(field, textValue)
                if (normalized == null) invalidValue = true else map[key] = normalized
            }
            if (invalidValue) continue
            val visibleFields = fillable.filter { ModuleFieldConditions.isVisible(it, map, spec.fields) }
            if (visibleFields.any { it.required && map[it.key].isNullOrBlank() }) continue
            val visibleKeys = visibleFields.map { it.key }.toSet()
            c.dynamicRepository.addEntry(spec.key, title, map.filterKeys { it in visibleKeys })
            written++
        }
        return written
    }

    /** A real training session written into the Gym module as individual sets. */
    suspend fun generateWorkout(instruction: String): String {
        val c = container()
        val recent = c.gymRepository.sets().first().take(40)

        val raw = Ai.generate(
            settings = c.settingsRepository,
            prompt = """
                Write today's training session for this person.
                What they asked for: ${instruction.ifBlank { "whatever their programme needs next" }}

                Their recent sets, newest first (date, exercise, kg x reps):
                ${if (recent.isEmpty()) "No history — start conservative and build."
                else recent.joinToString("\n") { "  ${it.localDate} ${it.exercise} ${it.weight}x${it.reps}" }}

                Pick working weights from their actual recent numbers — progress by small increments.
                If there is no history for an exercise, give a sensible conservative starting weight
                for a general trainee. NEVER return 0 for weight on a weighted exercise; for
                bodyweight movements use 0 and put the load in reps.

                Return ONLY valid JSON:
                {"note":"one line on the session's purpose",
                 "sets":[{"exercise":"Bench press","weight":60,"reps":8}]}
                Between 12 and 25 sets total.
            """.trimIndent(),
            system = "You are an experienced strength coach writing a concrete session. " +
                "You return only valid JSON.",
            maxOutputTokens = 4096
        )

        val json = JSONObject(Ai.cleanJson(raw))
        val sets = json.optJSONArray("sets") ?: JSONArray()
        for (i in 0 until sets.length()) {
            val s = sets.getJSONObject(i)
            c.gymRepository.addSet(
                s.optString("exercise"),
                s.optDouble("weight", 0.0),
                s.optInt("reps", 0)
            )
        }
        return json.optString("note", "Session written") + " — ${sets.length()} sets logged"
    }
}
