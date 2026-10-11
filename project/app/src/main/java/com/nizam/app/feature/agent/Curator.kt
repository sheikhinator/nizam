package com.nizam.app.feature.agent

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import com.nizam.app.AppContainer
import com.nizam.app.core.today
import com.nizam.app.feature.dynamic.ModuleSpec
import com.nizam.app.net.Ai
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject

/**
 * The curator keeps the app moving: it suggests what to do next in each module, and it can create
 * whole new modules by writing a spec — which the generic engine renders immediately, no rebuild.
 */
@Entity(tableName = "suggestion")
data class Suggestion(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val moduleKey: String,
    val emoji: String,
    val title: String,
    val body: String,
    val actionHint: String = "",
    val localDate: String = today(),
    val dismissed: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface CuratorDao {
    @Query("SELECT * FROM suggestion WHERE dismissed = 0 ORDER BY createdAt DESC LIMIT 60")
    fun observeActive(): Flow<List<Suggestion>>

    @Query("SELECT * FROM suggestion WHERE moduleKey = :key AND dismissed = 0 ORDER BY createdAt DESC LIMIT 10")
    fun observeFor(key: String): Flow<List<Suggestion>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<Suggestion>)

    @Update
    suspend fun update(item: Suggestion)

    @Query("DELETE FROM suggestion WHERE localDate < :before")
    suspend fun purgeBefore(before: String)
}

class CuratorRepository(
    private val dao: CuratorDao,
    private val container: () -> AppContainer
) {
    fun active() = dao.observeActive()
    fun forModule(key: String) = dao.observeFor(key)

    suspend fun dismiss(suggestion: Suggestion) = dao.update(suggestion.copy(dismissed = true))

    /** A compact picture of what the user has and hasn't been doing. */
    private suspend fun situation(): String {
        val c = container()
        val summary = com.nizam.app.feature.progress.Xp.summarise(c)
        val books = c.bookRepository.all().first()
        val courses = c.courseRepository.courses().first()
        val goals = c.missionRepository.goals().first()
        val specs = c.dynamicRepository.specs().first()
        val entries = c.dynamicRepository.recentEntries().first()
        val notes = c.noteRepository.all().first()

        val moduleUse = specs.associate { spec ->
            spec.name to entries.count { it.moduleKey == spec.key }
        }

        return buildString {
            appendLine("Today ${today()}. Level ${summary.level}, streak ${summary.streak}.")
            appendLine("Last 7 days: ${summary.last30.take(7).sumOf { it.studyMinutes }} study minutes, " +
                "${summary.last30.take(7).sumOf { it.sets }} sets, " +
                "${summary.last30.take(7).sumOf { it.prayers }} prayers logged.")
            if (books.isNotEmpty()) {
                appendLine("Books: " + books.take(10).joinToString("; ") { "${it.title} (${it.status})" })
            }
            if (courses.isNotEmpty()) appendLine("Courses: " + courses.joinToString("; ") { it.title })
            if (goals.isNotEmpty()) appendLine("Goals: " + goals.joinToString("; ") { it.title })
            appendLine("Notes written: ${notes.size}")
            appendLine("Module usage (entries logged): " +
                moduleUse.entries.joinToString(", ") { "${it.key}=${it.value}" })
            appendLine("User is in Lahore, Pakistan. Currency PKR.")
        }
    }

    /** Fresh suggestions across modules — specific to this person, not generic advice. */
    suspend fun refreshSuggestions(): Int {
        val c = container()
        val specs = c.dynamicRepository.specs().first()
        val moduleList = (specs.map { "${it.key} (${it.name}: ${it.tagline})" } +
            listOf(
                "books (Library: what to read next)",
                "courses (Learning: what to study next)",
                "gym (Training)",
                "finance (Money)",
                "diet (Food)",
                "prayer (Worship)",
                "notes (Knowledge base)"
            )).joinToString("\n")

        val raw = Ai.generate(
            settings = c.settingsRepository,
            prompt = """
                You are the curator inside this person's personal app. Here is their situation:

                ${situation()}

                Available modules to suggest for:
                $moduleList

                Write 6 to 8 suggestions. Rules:
                - Each one names something specific and doable, not a category. For books, name actual
                  book titles and authors that fit what they already read or want to learn. For courses,
                  name the actual next subject. For training, name the change.
                - Base it on what the data above shows, including what they are NOT using.
                - No praise, no filler, no "consider thinking about". One concrete thing each.
                - Where you recommend something external like a book, say in one clause why it fits them.

                Return ONLY valid JSON:
                {"suggestions":[{"moduleKey":"books","emoji":"📚","title":"short line",
                  "body":"2-3 sentences, specific","actionHint":"what to tap or do"}]}
            """.trimIndent(),
            system = "You are a sharp curator who knows this person's data and makes specific, " +
                "actionable suggestions. You return only valid JSON.",
            maxOutputTokens = 4096
        )

        val array = JSONObject(Ai.cleanJson(raw)).optJSONArray("suggestions") ?: JSONArray()
        val items = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Suggestion(
                moduleKey = o.optString("moduleKey", "general"),
                emoji = o.optString("emoji", "◆"),
                title = o.optString("title"),
                body = o.optString("body"),
                actionHint = o.optString("actionHint")
            )
        }.filter { it.title.isNotBlank() }

        dao.purgeBefore(java.time.LocalDate.now().minusDays(14).toString())
        dao.insertAll(items)
        return items.size
    }

    /** Designs and installs a brand new module from a plain-language description. */
    suspend fun createModule(description: String): ModuleSpec {
        val c = container()
        val existing = c.dynamicRepository.specs().first().map { it.key }

        val raw = Ai.generate(
            settings = c.settingsRepository,
            prompt = """
                Design a new module for this person's personal app.
                What they asked for: $description

                Existing module keys (do not reuse): ${existing.joinToString(", ")}

                A module is a tool, not a notepad. Before choosing fields, work out what this
                person would want to see after 30 entries, then design fields that produce it.

                Rules:
                - 4 to 6 fields. At least one NUMBER with a unit in "suffix" — numbers are what
                  make trends and comparisons possible later.
                - Use CHOICE with real options wherever the answer comes from a known set. Free
                  text cannot be grouped or compared.
                - RATING for subjective judgements, DATE for deadlines and recurrence, FILE when
                  the module holds actual files.
                - Labels are what the user reads: "Litres filled", not "amount".
                - A module whose fields are all TEXT is a failure. Do not produce one.
                - Add a COMPUTED field when two numbers imply a third worth seeing. It carries a
                  "formula" over the other field keys and is calculated, never typed in:
                  {"key":"perKm","label":"Cost per km","type":"COMPUTED","formula":"cost / distance"}

                Example of the standard: a bike fuel module should have litres (NUMBER, suffix L),
                cost (NUMBER, suffix PKR), distance (NUMBER, suffix km), station (CHOICE with real
                Pakistani chains), fuel grade (CHOICE), and a COMPUTED field
                {"key":"perKm","label":"Cost per km","type":"COMPUTED","formula":"cost / distance"}.

                Each entry has a name plus the fields you define.
                Field types available: TEXT, LONGTEXT, NUMBER, RATING (1-5 stars), CHOICE (one
                option), MULTI_SELECT (a list of options), TAGS (free tags separated by semicolons),
                DATE, BOOL, FILE, COMPUTED. Use FILE when the module should hold actual files —
                music, photos, video, documents — it gives the user a file picker and an in-app player. Use 3 to 5 fields — enough to be useful, few enough to fill in
                on a phone in 15 seconds.
                Add "required": true only when a value is essential. To show a field conditionally,
                use "conditionFieldKey" and "conditionValue", referring to another non-FILE,
                non-COMPUTED field. Avoid circular conditions. MULTI_SELECT fields need an
                "options" array.

                Return ONLY valid JSON:
                {
                  "key": "lowercase_single_word",
                  "name": "Display Name",
                  "emoji": "one emoji",
                  "tagline": "under 60 characters, plain and specific",
                  "colorHex": "#RRGGBB",
                  "fields": [
                    {"key":"fieldKey","label":"Label","type":"CHOICE","options":["A","B"],"suffix":"","required":false}
                  ]
                }
            """.trimIndent(),
            system = "You design compact, practical data modules. You return only valid JSON.",
            maxOutputTokens = 2048
        )

        val json = JSONObject(Ai.cleanJson(raw))
        val key = json.optString("key").ifBlank { "custom${System.currentTimeMillis() % 10000}" }
        val spec = ModuleSpec(
            key = key,
            name = json.optString("name", "New module"),
            emoji = json.optString("emoji", "✨"),
            tagline = json.optString("tagline", ""),
            colorHex = json.optString("colorHex", "#4FA694"),
            fieldsJson = (json.optJSONArray("fields") ?: JSONArray()).toString(),
            builtIn = false,
            sortIndex = 100
        )
        c.dynamicRepository.addSpec(spec)
        // Seed it so the module opens with something in it rather than an empty shell.
        runCatching { c.moduleGenerator.generate(spec, "starter examples for this module", 3) }
        return spec
    }
}
