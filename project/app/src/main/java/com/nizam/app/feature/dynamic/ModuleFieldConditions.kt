package com.nizam.app.feature.dynamic

object ModuleFieldConditions {
    fun hasValue(field: SpecField, value: String?): Boolean = when (field.type) {
        "MULTI_SELECT" -> StructuredFieldValues.parseMultiSelect(value.orEmpty())?.isNotEmpty() == true
        "TAGS" -> StructuredFieldValues.parseTags(value.orEmpty()).isNotEmpty()
        else -> !value.isNullOrBlank()
    }

    fun isVisible(field: SpecField, values: Map<String, String>, fields: List<SpecField>): Boolean {
        if (field.conditionFieldKey.isBlank()) return true
        val source = fields.firstOrNull { it.key == field.conditionFieldKey } ?: return false
        val expected = field.conditionValue
        if (expected.isBlank()) return false
        val actual = values[source.key].orEmpty()
        return when (source.type) {
            "MULTI_SELECT" -> StructuredFieldValues.decodeMultiSelect(actual)
                .any { it.equals(expected, ignoreCase = true) }
            "TAGS" -> StructuredFieldValues.parseTags(actual)
                .any { it.equals(expected, ignoreCase = true) }
            else -> actual.equals(expected, ignoreCase = true)
        }
    }

    fun hasConditionCycle(fields: List<SpecField>): Boolean {
        val byKey = fields.associateBy { it.key }
        val visiting = mutableSetOf<String>()
        val visited = mutableSetOf<String>()
        fun visit(key: String): Boolean {
            if (key in visiting) return true
            if (key in visited) return false
            visiting += key
            val dependency = byKey[key]?.conditionFieldKey.orEmpty()
            if (dependency.isNotBlank() && byKey.containsKey(dependency) && visit(dependency)) return true
            visiting -= key
            visited += key
            return false
        }
        return fields.any { visit(it.key) }
    }
}