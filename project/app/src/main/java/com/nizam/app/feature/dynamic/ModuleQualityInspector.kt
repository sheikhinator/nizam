package com.nizam.app.feature.dynamic

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import java.time.LocalDate

data class ModuleQualityIssue(
    val record: String,
    val field: String,
    val reason: String
)

data class ModuleQualityReport(
    val checkedRecords: Int,
    val issues: List<ModuleQualityIssue>
)

object ModuleQualityInspector {
    private val supportedTypes = setOf(
        "TEXT", "LONGTEXT", "NUMBER", "DATE", "CHOICE", "MULTI_SELECT", "TAGS",
        "RATING", "BOOL", "FILE", "COMPUTED"
    )

    fun inspect(spec: ModuleSpec, entries: List<ModuleEntry>): ModuleQualityReport {
        val issues = mutableListOf<ModuleQualityIssue>()
        spec.fields.filter { it.type !in supportedTypes }.forEach { field ->
            issues += ModuleQualityIssue("Module schema", field.label, "Unsupported field type “${field.type}”.")
        }
        val knownKeys = spec.fields.map { it.key }.toSet()
        entries.forEach entryLoop@{ entry ->
            val label = entry.title.ifBlank { "Record #${entry.id}" }
            val parsed = runCatching { JSONObject(entry.valuesJson) }.getOrNull()
            if (parsed == null) {
                issues += ModuleQualityIssue(label, "Record values", "Stored field data is not valid JSON.")
                return@entryLoop
            }
            val storedKeys = parsed.keys().asSequence().toSet()
            val recordValues = spec.fields.associate { it.key to parsed.optString(it.key).trim() }
            (storedKeys - knownKeys).forEach { key ->
                if (parsed.optString(key).isNotBlank()) {
                    issues += ModuleQualityIssue(label, key, "This value has no matching field in the current schema.")
                }
            }
            spec.fields.forEach fieldLoop@{ field ->
                if (!ModuleFieldConditions.isVisible(field, recordValues, spec.fields)) return@fieldLoop
                val value = recordValues[field.key].orEmpty()
                if (!ModuleFieldConditions.hasValue(field, value)) {
                    if (field.required && field.type != "COMPUTED") {
                        issues += ModuleQualityIssue(label, field.label, "Required value is missing.")
                    }
                    return@fieldLoop
                }
                val valid = when (field.type) {
                    "NUMBER" -> value.toDoubleOrNull()?.isFinite() == true
                    "DATE" -> runCatching { LocalDate.parse(value) }.isSuccess
                    "CHOICE" -> field.options.any { it == value }
                    "MULTI_SELECT" -> {
                        val selected = StructuredFieldValues.parseMultiSelect(value)
                        selected != null && selected.distinct().size == selected.size &&
                            selected.all { item -> field.options.any { it == item } }
                    }
                    "TAGS" -> StructuredFieldValues.parseTags(value).isNotEmpty() &&
                        !StructuredFieldValues.hasDuplicateTags(value) &&
                        StructuredFieldValues.parseTags(value).all { it.length <= 100 } &&
                        value.split(';').all { it.isNotBlank() }
                    "RATING" -> value.toIntOrNull()?.let { it in 1..5 } == true
                    "BOOL" -> value == "true" || value == "false"
                    "COMPUTED" -> FormulaCheck.canEvaluate(field, entry, spec)
                    else -> true
                }
                if (!valid) issues += ModuleQualityIssue(label, field.label, "Value “${value.take(60)}” does not match this field type.")
            }
        }
        return ModuleQualityReport(entries.size, issues)
    }
}

private object FormulaCheck {
    fun canEvaluate(field: SpecField, entry: ModuleEntry, spec: ModuleSpec) =
        com.nizam.app.core.Formula.evaluate(field.formula, entry.numericValues(spec)) != null
}

@Composable
fun ModuleQualityInspectorSection(spec: ModuleSpec, entries: List<ModuleEntry>) {
    val report = remember(spec, entries) { ModuleQualityInspector.inspect(spec, entries) }
    var expanded by remember(spec.key) { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row {
                Text(
                    "Quality check · ${report.issues.size} issue${if (report.issues.size == 1) "" else "s"}",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "Hide" else "Review")
                }
            }
            Text(
                "Read-only check of ${report.checkedRecords} loaded records. No data is changed. Required fields are checked only when their visibility conditions match.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (expanded) {
                if (report.issues.isEmpty()) {
                    Text("No malformed values or orphaned fields found.")
                } else {
                    report.issues.take(40).forEach { issue ->
                        Text(
                            "${issue.record} · ${issue.field}: ${issue.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (report.issues.size > 40) {
                        Text("Showing 40 of ${report.issues.size} issues.")
                    }
                }
            }
        }
    }
}