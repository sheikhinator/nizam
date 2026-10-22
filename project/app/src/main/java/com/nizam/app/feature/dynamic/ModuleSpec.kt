package com.nizam.app.feature.dynamic

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.nizam.app.core.today
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * A module defined as data rather than code. The generic engine renders any spec, so new modules
 * can be added at runtime — by the user or by the curator agent — with no rebuild.
 */
@Entity(tableName = "module_spec")
data class ModuleSpec(
    @PrimaryKey val key: String,
    val name: String,
    val emoji: String,
    val tagline: String,
    val colorHex: String,
    val fieldsJson: String,
    val listStyle: String = "CARD",     // CARD | ROW | GRID
    val builtIn: Boolean = false,
    val enabled: Boolean = true,
    val sortIndex: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
) {
    val fields: List<SpecField>
        get() = runCatching {
            val array = JSONArray(fieldsJson)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                // models write "number", "Integer", "dropdown"; and sometimes only a label, no key
                val label = o.optString("label").ifBlank { o.optString("name") }
                SpecField(
                    key = o.optString("key").ifBlank { label.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_') },
                    label = label.ifBlank { o.optString("key") },
                    type = normaliseType(o.optString("type", "TEXT")),
                    options = o.optJSONArray("options")?.let { opts ->
                        (0 until opts.length()).map { opts.optString(it) }
                    } ?: emptyList(),
                    suffix = o.optString("suffix"),
                    formula = o.optString("formula"),
                    required = o.optBoolean("required", false),
                    conditionFieldKey = o.optString("conditionFieldKey"),
                    conditionValue = o.optString("conditionValue")
                )
            }.filter { it.key.isNotBlank() }
        }.getOrElse { emptyList() }
}

/** The field types Atlas knows, from any spelling a person or a model uses. */
fun normaliseType(raw: String): String = when (val t = raw.trim().uppercase().replace(' ', '_').replace('-', '_')) {
    "", "STRING", "SHORT_TEXT", "TEXTFIELD", "NAME", "TITLE" -> "TEXT"
    "LONG_TEXT", "PARAGRAPH", "TEXTAREA", "NOTES", "NOTE", "MULTILINE" -> "LONGTEXT"
    "INT", "INTEGER", "FLOAT", "DOUBLE", "DECIMAL", "CURRENCY", "MONEY", "AMOUNT", "PRICE", "NUM", "QUANTITY", "PERCENT" -> "NUMBER"
    "STARS", "STAR", "SCORE" -> "RATING"
    "SELECT", "DROPDOWN", "ENUM", "OPTION", "OPTIONS", "SINGLE_SELECT", "RADIO" -> "CHOICE"
    "MULTISELECT", "MULTI", "CHECKBOXES", "MULTIPLE_CHOICE" -> "MULTI_SELECT"
    "TAG", "LABELS" -> "TAGS"
    "DATETIME", "DATE_TIME", "DAY", "TIME" -> "DATE"
    "BOOLEAN", "CHECKBOX", "TOGGLE", "YES_NO", "YESNO", "SWITCH" -> "BOOL"
    "IMAGE", "PHOTO", "ATTACHMENT", "AUDIO", "VIDEO", "PDF", "MEDIA" -> "FILE"
    "FORMULA", "CALCULATED", "CALC" -> "COMPUTED"
    else -> t
}

data class SpecField(
    val key: String,
    val label: String,
    val type: String,              // TEXT | LONGTEXT | NUMBER | RATING | CHOICE | MULTI_SELECT | TAGS | DATE | BOOL | FILE | COMPUTED
    val options: List<String> = emptyList(),
    val suffix: String = "",
    /** For COMPUTED fields: an expression over the other field keys, e.g. "cost / distance". */
    val formula: String = "",
    val required: Boolean = false,
    val conditionFieldKey: String = "",
    val conditionValue: String = ""
)

/** One record inside a dynamic module. Values are a flat JSON object keyed by field key. */
@Entity(tableName = "module_entry")
data class ModuleEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val moduleKey: String,
    val localDate: String = today(),
    val title: String = "",
    val valuesJson: String = "{}",
    val done: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun value(key: String): String = runCatching {
        JSONObject(valuesJson).optString(key, "")
    }.getOrElse { "" }

    /** Field values that parse as numbers, keyed for formula evaluation. */
    fun numericValues(spec: ModuleSpec): Map<String, Double> =
        spec.fields.mapNotNull { field ->
            (value(field.key).toDoubleOrNull() ?: Units.toNumber(value(field.key), field.suffix))?.let { field.key to it }
        }.toMap()

    fun values(): Map<String, String> = runCatching {
        val o = JSONObject(valuesJson)
        o.keys().asSequence().associateWith { o.optString(it, "") }
    }.getOrElse { emptyMap() }
}

@Dao
interface DynamicDao {
    @Query("SELECT * FROM module_spec WHERE enabled = 1 ORDER BY sortIndex ASC, createdAt ASC")
    fun observeSpecs(): Flow<List<ModuleSpec>>

    @Query("SELECT * FROM module_spec ORDER BY sortIndex ASC")
    fun observeAllSpecs(): Flow<List<ModuleSpec>>

    @Query("SELECT * FROM module_spec WHERE key = :key")
    suspend fun spec(key: String): ModuleSpec?

    @Query("SELECT COUNT(*) FROM module_spec")
    suspend fun specCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSpecs(specs: List<ModuleSpec>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSpec(spec: ModuleSpec)

    @Update
    suspend fun updateSpec(spec: ModuleSpec)

    @Query("SELECT * FROM module_entry WHERE moduleKey = :key ORDER BY createdAt DESC LIMIT 300")
    fun observeEntries(key: String): Flow<List<ModuleEntry>>

    @Query("SELECT * FROM module_entry WHERE moduleKey = :key ORDER BY createdAt DESC")
    suspend fun entriesForMigration(key: String): List<ModuleEntry>

    @Query("SELECT * FROM module_entry ORDER BY createdAt DESC LIMIT 200")
    fun observeRecentEntries(): Flow<List<ModuleEntry>>

    @Query("SELECT * FROM module_entry ORDER BY createdAt DESC")
    fun observeAllEntries(): Flow<List<ModuleEntry>>

    @Query("SELECT * FROM module_entry WHERE title LIKE '%' || :q || '%' OR valuesJson LIKE '%' || :q || '%' ORDER BY createdAt DESC LIMIT 200")
    suspend fun search(q: String): List<ModuleEntry>

    @Query("SELECT COUNT(*) FROM module_entry WHERE moduleKey = :key")
    suspend fun countFor(key: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEntry(entry: ModuleEntry)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntries(entries: List<ModuleEntry>)

    @Update
    suspend fun updateEntry(entry: ModuleEntry)

    @Query("DELETE FROM module_entry WHERE id = :id")
    suspend fun deleteEntry(id: Long)

    @Query("DELETE FROM module_entry WHERE moduleKey = :key AND id IN (:ids)")
    suspend fun deleteEntries(key: String, ids: List<Long>): Int

    @Query("DELETE FROM module_entry WHERE moduleKey = :key")
    suspend fun deleteEntriesFor(key: String)

    @Query("DELETE FROM module_spec WHERE key = :key")
    suspend fun deleteSpec(key: String)

    @Transaction
    suspend fun deleteCustomModule(key: String) {
        val current = spec(key) ?: error("This module no longer exists.")
        require(!current.builtIn) { "Built-in modules cannot be deleted." }
        deleteEntriesFor(key)
        deleteSpec(key)
    }

    @Update
    suspend fun updateEntryRow(entry: ModuleEntry)

    @Transaction
    suspend fun mergeEntries(keep: ModuleEntry, linked: List<ModuleEntry>, removeId: Long) {
        updateEntryRow(keep)
        linked.forEach { updateEntryRow(it) }
        deleteEntry(removeId)
    }

    @Transaction
    suspend fun undoMerge(originals: List<ModuleEntry>, linkedBefore: List<ModuleEntry>) {
        originals.forEach { insertEntry(it) }
        linkedBefore.forEach { updateEntryRow(it) }
    }

    @Transaction
    suspend fun applySchemaMigration(
        expectedSpec: ModuleSpec,
        targetSpec: ModuleSpec,
        expectedEntries: List<ModuleEntry>,
        migratedEntries: List<ModuleEntry>
    ) {
        val currentSpec = spec(expectedSpec.key)
            ?: error("This module no longer exists. Reopen the migration preview.")
        require(currentSpec.fieldsJson == expectedSpec.fieldsJson) {
            "The module schema changed after preview. Reopen the migration preview."
        }
        val currentEntries = entriesForMigration(expectedSpec.key)
        val expectedById = expectedEntries.associateBy { it.id }
        require(
            currentEntries.size == expectedEntries.size &&
                currentEntries.all { expectedById[it.id] == it }
        ) {
            "Records changed after preview. Reopen the migration preview."
        }
        updateSpec(currentSpec.copy(fieldsJson = targetSpec.fieldsJson))
        migratedEntries.forEach { updateEntryRow(it) }
    }
}
