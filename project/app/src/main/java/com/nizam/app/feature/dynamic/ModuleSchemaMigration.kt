package com.nizam.app.feature.dynamic

import org.json.JSONObject

data class ModuleFieldTypeChange(
    val label: String,
    val oldType: String,
    val newType: String
)

data class ModuleFieldUpdate(val oldField: SpecField, val newField: SpecField)

data class ModuleSchemaMigrationPlan(
    val expectedSpec: ModuleSpec,
    val targetSpec: ModuleSpec,
    val expectedEntries: List<ModuleEntry>,
    val migratedEntries: List<ModuleEntry>,
    val addedFields: List<SpecField>,
    val removedFields: List<SpecField>,
    val updatedFields: List<ModuleFieldUpdate>,
    val typeChanges: List<ModuleFieldTypeChange>,
    val discardedValueCount: Int,
    val recordsWithRequiredGaps: Int,
    val conversionFailures: List<String>
)

object ModuleSchemaMigration {
    fun plan(
        current: ModuleSpec,
        template: ModuleTemplatePreview,
        records: List<ModuleEntry>
    ): ModuleSchemaMigrationPlan {
        require(!current.builtIn) { "Built-in module schemas cannot be changed from templates." }
        val candidate = ModuleTemplateExchange.toModuleSpec(template, current.sortIndex)
        val target = current.copy(fieldsJson = candidate.fieldsJson)
        val oldFields = current.fields.associateBy { it.key }
        val newFields = target.fields.associateBy { it.key }
        val added = target.fields.filter { it.key !in oldFields }
        val removed = current.fields.filter { it.key !in newFields }
        val updated = target.fields.mapNotNull { field ->
            oldFields[field.key]?.takeIf { it != field }?.let { ModuleFieldUpdate(it, field) }
        }
        val typeChanges = target.fields.mapNotNull { field ->
            oldFields[field.key]?.takeIf { it.type != field.type }?.let {
                ModuleFieldTypeChange(field.label, it.type, field.type)
            }
        }
        val failures = mutableListOf<String>()
        var discarded = 0
        var requiredGaps = 0
        val migrated = records.map { record ->
            val original = record.values()
            val transformed = linkedMapOf<String, String>()
            original.forEach { (key, value) ->
                if (value.isBlank()) return@forEach
                val newField = newFields[key]
                if (newField == null) {
                    discarded++
                    return@forEach
                }
                if (newField.type == "COMPUTED") {
                    discarded++
                    return@forEach
                }
                if (oldFields[key]?.type == "FILE" && newField.type == "FILE") {
                    transformed[key] = value
                    return@forEach
                }
                val sourceValue = if (oldFields[key]?.type == "MULTI_SELECT") {
                    val decoded = StructuredFieldValues.parseMultiSelect(value)
                    if (decoded == null) {
                        failures += "${record.title.ifBlank { "Record #${record.id}" }} · ${newField.label}: invalid stored multi-select value."
                        return@forEach
                    }
                    decoded.joinToString(";")
                } else value
                if (sourceValue.isBlank()) return@forEach
                val normalized = ModuleCsvImport.validateAndNormalizeValue(newField, sourceValue)
                if (normalized == null) {
                    failures += "${record.title.ifBlank { "Record #${record.id}" }} · ${newField.label}: incompatible stored value."
                } else {
                    transformed[key] = normalized
                }
            }
            if (target.fields.any { field ->
                    field.required && field.type != "COMPUTED" &&
                        ModuleFieldConditions.isVisible(field, transformed, target.fields) &&
                        transformed[field.key].isNullOrBlank()
                }
            ) {
                requiredGaps++
            }
            val json = JSONObject().apply { transformed.forEach { (key, value) -> put(key, value) } }
            record.copy(valuesJson = json.toString())
        }
        return ModuleSchemaMigrationPlan(
            expectedSpec = current,
            targetSpec = target,
            expectedEntries = records,
            migratedEntries = migrated,
            addedFields = added,
            removedFields = removed,
            updatedFields = updated,
            typeChanges = typeChanges,
            discardedValueCount = discarded,
            recordsWithRequiredGaps = requiredGaps,
            conversionFailures = failures
        )
    }
}