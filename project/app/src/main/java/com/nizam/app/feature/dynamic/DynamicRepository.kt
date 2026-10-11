package com.nizam.app.feature.dynamic

import com.nizam.app.core.today
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

class DynamicRepository(private val dao: DynamicDao) {

    fun specs(): Flow<List<ModuleSpec>> = dao.observeSpecs()
    fun allSpecs(): Flow<List<ModuleSpec>> = dao.observeAllSpecs()
    fun entries(key: String): Flow<List<ModuleEntry>> = dao.observeEntries(key)
    fun recentEntries(): Flow<List<ModuleEntry>> = dao.observeRecentEntries()
    fun allEntries(): Flow<List<ModuleEntry>> = dao.observeAllEntries()
    /** Search done by the database, newest 200 matches, instead of loading every record. */
    suspend fun search(q: String): List<ModuleEntry> = dao.search(q.replace("%", "").replace("_", " ").trim())

    /** Adds any built-in module this install doesn't have yet — so new built-ins reach existing users. */
    suspend fun seedIfEmpty() {
        val missing = Builtins.specs.filter { spec(it.key) == null }
        if (missing.isNotEmpty()) dao.insertSpecs(missing)
    }

    suspend fun addEntry(moduleKey: String, title: String, values: Map<String, String>) {
        val json = JSONObject()
        values.filterValues { it.isNotBlank() }.forEach { (k, v) -> json.put(k, v) }
        dao.insertEntry(
            ModuleEntry(
                moduleKey = moduleKey,
                title = title.trim(),
                localDate = today(),
                valuesJson = json.toString()
            )
        )
    }

    suspend fun addEntries(moduleKey: String, records: List<Pair<String, Map<String, String>>>) {
        require(records.isNotEmpty() && records.size <= ModuleCsvImport.MAX_ROWS) {
            "Choose between 1 and ${ModuleCsvImport.MAX_ROWS} records to import."
        }
        val entries = records.map { (title, values) ->
            require(title.isNotBlank()) { "Every imported record needs a name." }
            val json = JSONObject()
            values.filterValues { it.isNotBlank() }.forEach { (key, value) -> json.put(key, value) }
            ModuleEntry(
                moduleKey = moduleKey,
                title = title.trim(),
                localDate = today(),
                valuesJson = json.toString()
            )
        }
        dao.insertEntries(entries)
    }

    /** Writes an edited record back (same id replaces it). */
    suspend fun saveEntry(entry: ModuleEntry) = dao.insertEntry(entry)

    suspend fun deleteEntry(id: Long) = dao.deleteEntry(id)

    suspend fun deleteEntries(moduleKey: String, ids: List<Long>): Int {
        val uniqueIds = ids.distinct()
        require(uniqueIds.isNotEmpty() && uniqueIds.size <= 300) {
            "Select between 1 and 300 loaded records."
        }
        return dao.deleteEntries(moduleKey, uniqueIds)
    }

    suspend fun entriesForMigration(moduleKey: String): List<ModuleEntry> =
        dao.entriesForMigration(moduleKey)

    suspend fun applySchemaMigration(plan: ModuleSchemaMigrationPlan) {
        require(plan.conversionFailures.isEmpty()) {
            "Resolve the incompatible values shown in the preview before applying this migration."
        }
        dao.applySchemaMigration(
            plan.expectedSpec,
            plan.targetSpec,
            plan.expectedEntries,
            plan.migratedEntries
        )
    }

    suspend fun setEnabled(spec: ModuleSpec, enabled: Boolean) =
        dao.updateSpec(spec.copy(enabled = enabled))

    suspend fun addSpec(spec: ModuleSpec) {
        require(!spec.builtIn) { "Only built-in seeding can create built-in modules." }
        require(spec.key.matches(Regex("[A-Za-z0-9_-]{1,64}"))) {
            "The module key contains unsupported characters."
        }
        ModuleTemplateExchange.encode(spec)
        require(dao.spec(spec.key) == null) { "A module with this key already exists." }
        dao.insertSpec(spec)
    }

    suspend fun spec(key: String) = dao.spec(key)

    suspend fun countFor(key: String) = dao.countFor(key)

    suspend fun updateSpec(spec: ModuleSpec) = dao.updateSpec(spec)

    suspend fun deleteModule(spec: ModuleSpec) = dao.deleteCustomModule(spec.key)

    suspend fun clearEntries(spec: ModuleSpec) = dao.deleteEntriesFor(spec.key)

    suspend fun reorder(specs: List<ModuleSpec>) {
        specs.forEachIndexed { index, spec -> dao.updateSpec(spec.copy(sortIndex = index)) }
    }

    suspend fun updateEntry(entry: ModuleEntry, title: String, values: Map<String, String>) {
        val json = org.json.JSONObject()
        values.filterValues { it.isNotBlank() }.forEach { (k, v) -> json.put(k, v) }
        dao.updateEntryRow(entry.copy(title = title.trim(), valuesJson = json.toString()))
    }

    suspend fun mergeEntries(keep: ModuleEntry, linked: List<ModuleEntry>, removeId: Long) =
        dao.mergeEntries(keep, linked, removeId)

    suspend fun undoMerge(originals: List<ModuleEntry>, linkedBefore: List<ModuleEntry>) =
        dao.undoMerge(originals, linkedBefore)
}
