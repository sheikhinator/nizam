package com.nizam.app.feature.dynamic

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nizam.app.core.Formula
import java.util.Locale

data class PivotBucket(
    val label: String,
    val recordCount: Int,
    val numericCount: Int,
    val total: Double,
    val average: Double?
)

object ModulePivotReports {
    const val DATE_GROUP = "__date"

    fun compute(
        spec: ModuleSpec,
        entries: List<ModuleEntry>,
        groupKey: String,
        metricKey: String
    ): List<PivotBucket> {
        val field = spec.fields.firstOrNull { it.key == groupKey }
        if (groupKey != DATE_GROUP && field?.type != "CHOICE") return emptyList()
        val metric = spec.fields.firstOrNull {
            it.key == metricKey && (it.type == "NUMBER" || it.type == "COMPUTED")
        }
        if (metricKey.isNotBlank() && metric == null) return emptyList()

        data class Accumulator(var records: Int = 0, var numericCount: Int = 0, var total: Double = 0.0)
        val grouped = linkedMapOf<String, Accumulator>()
        entries.forEach { entry ->
            val label = when {
                groupKey == DATE_GROUP -> entry.localDate
                else -> entry.value(groupKey)
            }.ifBlank { "(No value)" }
            val bucket = grouped.getOrPut(label) { Accumulator() }
            bucket.records++
            val numeric = when (metric?.type) {
                "COMPUTED" -> Formula.evaluate(metric.formula, entry.numericValues(spec))
                "NUMBER" -> entry.value(metric.key).toDoubleOrNull()
                else -> null
            }?.takeIf { it.isFinite() }
            if (numeric != null) {
                bucket.numericCount++
                bucket.total += numeric
            }
        }
        return grouped.map { (label, value) ->
            PivotBucket(
                label = label,
                recordCount = value.records,
                numericCount = value.numericCount,
                total = value.total,
                average = if (value.numericCount == 0) null else value.total / value.numericCount
            )
        }.sortedByDescending { it.recordCount }
    }
}

@Composable
fun ModulePivotReportSection(spec: ModuleSpec, entries: List<ModuleEntry>) {
    val choices = remember(spec) { spec.fields.filter { it.type == "CHOICE" } }
    val metrics = remember(spec) { spec.fields.filter { it.type == "NUMBER" || it.type == "COMPUTED" } }
    var groupKey by remember(spec.key) { mutableStateOf(ModulePivotReports.DATE_GROUP) }
    var metricKey by remember(spec.key) { mutableStateOf("") }
    var average by remember(spec.key) { mutableStateOf(false) }
    val buckets = remember(spec, entries, groupKey, metricKey) {
        ModulePivotReports.compute(spec, entries, groupKey, metricKey)
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Pivot report", style = MaterialTheme.typography.titleMedium)
            Text(
                "Group loaded records by date or a choice field. Numeric totals and averages use values present in each group.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = groupKey == ModulePivotReports.DATE_GROUP,
                    onClick = { groupKey = ModulePivotReports.DATE_GROUP },
                    label = { Text("Date") }
                )
                choices.forEach { field ->
                    FilterChip(
                        selected = groupKey == field.key,
                        onClick = { groupKey = field.key },
                        label = { Text(field.label) }
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = metricKey.isBlank(),
                    onClick = { metricKey = "" },
                    label = { Text("Count records") }
                )
                metrics.forEach { field ->
                    FilterChip(
                        selected = metricKey == field.key,
                        onClick = { metricKey = field.key },
                        label = { Text(field.label) }
                    )
                }
            }
            if (metricKey.isNotBlank()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !average, onClick = { average = false }, label = { Text("Sum") })
                    FilterChip(selected = average, onClick = { average = true }, label = { Text("Average") })
                }
            }
            if (buckets.isEmpty()) {
                Text("No records to group yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                Spacer(Modifier.height(2.dp))
                buckets.take(30).forEach { bucket ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(bucket.label, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f))
                        val summary = when {
                            metricKey.isBlank() -> "${bucket.recordCount} records"
                            bucket.average == null -> "${bucket.recordCount} records · no numeric values"
                            average -> "${"%.2f".format(Locale.ROOT, bucket.average)} avg · ${bucket.numericCount} values"
                            else -> "${"%.2f".format(Locale.ROOT, bucket.total)} total · ${bucket.numericCount} values"
                        }
                        Text(summary, style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (buckets.size > 30) {
                    Text("Showing 30 of ${buckets.size} groups.", style = MaterialTheme.typography.labelSmall)
                }
            }
            Text(
                "Report uses up to ${entries.size} loaded records on this device.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}