package com.nizam.app.feature.dynamic

import org.json.JSONObject

import org.json.JSONArray

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.time.LocalDate
import java.util.Locale

data class CsvImportTable(
    val headers: List<String>,
    val rows: List<List<String>>,
    val rowNumberOffset: Int = 2,
    val sourceFormat: String = "CSV"
)

data class CsvImportRow(
    val index: Int,
    val sourceLine: Int,
    val title: String,
    val values: Map<String, String>,
    val errors: List<String>,
    val possibleDuplicate: String?
)

data class CsvImportReview(
    val rows: List<CsvImportRow>,
    val mappingError: String?
)

object ModuleCsvImport {
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_ROWS = 500
    private const val MAX_COLUMNS = 100
    private const val MAX_CELL_CHARS = 16 * 1024
    const val TITLE_TARGET = "__record_title"

    fun parse(source: String): CsvImportTable {
        require(source.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES) {
            "This CSV is larger than the 2 MB import limit."
        }
        val parsedRows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0

        fun finishCell() {
            require(cell.length <= MAX_CELL_CHARS) { "A CSV cell is too large." }
            row += cell.toString()
            cell.setLength(0)
        }

        fun finishRow() {
            finishCell()
            if (row.any { it.isNotBlank() }) parsedRows += row.toList()
            row.clear()
            require(parsedRows.size <= MAX_ROWS + 1) {
                "A CSV can contain up to $MAX_ROWS data rows."
            }
        }

        while (index < source.length) {
            val char = source[index]
            if (quoted) {
                when {
                    char == '"' && source.getOrNull(index + 1) == '"' -> {
                        cell.append('"')
                        index++
                    }
                    char == '"' -> quoted = false
                    else -> cell.append(char)
                }
            } else {
                when (char) {
                    '"' -> {
                        require(cell.isEmpty()) { "A quote appeared in the middle of an unquoted CSV cell." }
                        quoted = true
                    }
                    ',' -> finishCell()
                    '\n' -> finishRow()
                    '\r' -> {
                        if (source.getOrNull(index + 1) == '\n') index++
                        finishRow()
                    }
                    else -> cell.append(char)
                }
            }
            require(cell.length <= MAX_CELL_CHARS) { "A CSV cell is too large." }
            index++
        }
        require(!quoted) { "The CSV ends inside a quoted cell." }
        if (cell.isNotEmpty() || row.isNotEmpty()) finishRow()
        require(parsedRows.size >= 2) { "The CSV needs a header and at least one data row." }

        val headers = parsedRows.first().mapIndexed { column, raw ->
            (if (column == 0) raw.removePrefix("\uFEFF") else raw).trim()
        }
        require(headers.size in 1..MAX_COLUMNS) { "A CSV can contain up to $MAX_COLUMNS columns." }
        require(headers.any { it.isNotBlank() }) { "The CSV header row is empty." }
        val rows = parsedRows.drop(1).mapIndexed { rowIndex, values ->
            require(values.size <= headers.size) {
                "CSV row ${rowIndex + 2} has more cells than the header."
            }
            values + List(headers.size - values.size) { "" }
        }
        return CsvImportTable(headers, rows)
    }

    fun parseJson(source: String): CsvImportTable {
        require(source.toByteArray(StandardCharsets.UTF_8).size <= MAX_BYTES) {
            "This JSON file is larger than the 2 MB import limit."
        }
        val trimmed = source.trim()
        val records = runCatching {
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val root = JSONObject(trimmed)
                    root.optJSONArray("records") ?: JSONArray().put(root)
                }
                else -> error("Expected a JSON array of records or an object containing a records array.")
            }
        }.getOrElse {
            throw IllegalArgumentException(
                "Use a JSON array of record objects, or an object with a “records” array.",
                it
            )
        }
        require(records.length() in 1..MAX_ROWS) {
            "A JSON import needs 1 to $MAX_ROWS record objects."
        }
        val objects = (0 until records.length()).map { index ->
            require(records.opt(index) is JSONObject) {
                "JSON record ${index + 1} must be an object."
            }
            records.getJSONObject(index)
        }
        val headers = mutableListOf<String>()
        objects.forEach { record ->
            record.keys().asSequence().forEach { key ->
                require(key.isNotBlank() && key.length <= MAX_CELL_CHARS) {
                    "A JSON field name is empty or too long."
                }
                if (key !in headers) headers += key
            }
        }
        require(headers.isNotEmpty() && headers.size <= MAX_COLUMNS) {
            "JSON records must contain 1 to $MAX_COLUMNS fields."
        }
        val rows = objects.mapIndexed { index, record ->
            headers.map { key ->
                val value = jsonCell(record.opt(key))
                require(value.length <= MAX_CELL_CHARS) {
                    "JSON record ${index + 1} contains a value that is too large."
                }
                value
            }
        }
        return CsvImportTable(headers, rows, rowNumberOffset = 1, sourceFormat = "JSON")
    }

    private fun jsonCell(value: Any?): String = when (value) {
        null, JSONObject.NULL -> ""
        is JSONArray -> (0 until value.length()).joinToString("; ") { index ->
            when (val item = value.opt(index)) {
                null, JSONObject.NULL -> ""
                is JSONObject, is JSONArray -> error("Nested JSON values cannot be mapped to a record field.")
                else -> item.toString()
            }
        }
        is JSONObject -> value.toString()
        else -> value.toString()
    }

    fun suggestedTarget(header: String, spec: ModuleSpec): String {
        val normalized = normalize(header)
        if (normalized in setOf("title", "name", "recordname")) return TITLE_TARGET
        return spec.fields.firstOrNull { field ->
            normalize(field.key) == normalized || normalize(field.label) == normalized
        }?.takeIf { it.type != "FILE" && it.type != "COMPUTED" }?.key.orEmpty()
    }

    fun preview(
        table: CsvImportTable,
        spec: ModuleSpec,
        existing: List<ModuleEntry>,
        mappings: Map<Int, String>
    ): CsvImportReview {
        val duplicateTargets = mappings.values.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        val mappingError = when {
            duplicateTargets.isNotEmpty() -> "Each destination can be mapped from one CSV column only."
            mappings.values.none { it.isNotBlank() } -> "Map at least one CSV column before importing."
            else -> null
        }
        val fieldsByKey = spec.fields.associateBy { it.key }
        val rows = table.rows.mapIndexed { rowIndex, cells ->
            val values = linkedMapOf<String, String>()
            val errors = mutableListOf<String>()
            var explicitTitle = ""
            cells.forEachIndexed { column, raw ->
                val target = mappings[column].orEmpty()
                val value = raw.trim()
                if (target.isBlank() || value.isBlank()) return@forEachIndexed
                if (target == TITLE_TARGET) {
                    explicitTitle = value
                } else {
                    val field = fieldsByKey[target]
                    if (field == null) {
                        errors += "A mapped field no longer exists."
                    } else {
                        val checked = validateAndNormalizeValue(field, value)
                        if (checked == null) errors += "${field.label} has an invalid value."
                        else values[field.key] = checked
                    }
                }
            }
            val title = explicitTitle.ifBlank { values.values.firstOrNull().orEmpty() }
            if (title.isBlank()) errors += "Map a record name or provide a non-empty mapped value."
            val visibleRequired = spec.fields.filter { field ->
                field.required && ModuleFieldConditions.isVisible(field, values, spec.fields)
            }
            visibleRequired.filter { !ModuleFieldConditions.hasValue(it, values[it.key]) }.forEach { field ->
                errors += "Required field “${field.label}” is missing."
            }

            val nonBlankValues = values.filterValues { it.isNotBlank() }
            val possibleDuplicate = existing.firstOrNull { entry ->
                (title.isNotBlank() && entry.title.trim().equals(title, ignoreCase = true)) ||
                    (nonBlankValues.isNotEmpty() && nonBlankValues.all { (key, value) ->
                        entry.value(key).trim().equals(value, ignoreCase = true)
                    })
            }?.let { it.title.ifBlank { "Record #${it.id}" } }

            CsvImportRow(
                index = rowIndex,
                sourceLine = rowIndex + table.rowNumberOffset,
                title = title,
                values = values,
                errors = errors.distinct(),
                possibleDuplicate = possibleDuplicate
            )
        }
        return CsvImportReview(rows, mappingError)
    }

    internal fun validateAndNormalizeValue(field: SpecField, value: String): String? = when (field.type) {
        "NUMBER" -> value.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { value }
        "RATING" -> value.toIntOrNull()?.takeIf { it in 1..5 }?.toString()
        "DATE" -> runCatching { LocalDate.parse(value).toString() }.getOrNull()
        "BOOL" -> when (value.lowercase(Locale.ROOT)) {
            "true", "yes", "1" -> "true"
            "false", "no", "0" -> "false"
            else -> null
        }
        "CHOICE" -> field.options.firstOrNull { it.equals(value, ignoreCase = true) }
        "MULTI_SELECT" -> {
            val selected = value.split(';').map(String::trim).filter(String::isNotBlank)
            val canonical = selected.map { item ->
                field.options.firstOrNull { it.equals(item, ignoreCase = true) } ?: return null
            }
            if (canonical.isEmpty() || canonical.distinct().size != canonical.size) null
            else StructuredFieldValues.encodeMultiSelect(canonical)
        }
        "TAGS" -> {
            val tags = value.split(';').map(String::trim)
            if (tags.any { it.isBlank() || it.length > 100 }) null
            else tags.distinctBy { it.lowercase(Locale.ROOT) }.joinToString("; ")
        }
        "FILE", "COMPUTED" -> null
        else -> value
    }

    private fun normalize(value: String) =
        value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
}

@Composable
fun ModuleCsvImportSection(
    spec: ModuleSpec,
    existingEntries: List<ModuleEntry>,
    onImport: suspend (List<Pair<String, Map<String, String>>>) -> Unit
) {
    val context = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val mappings = remember(spec.key) { mutableStateMapOf<Int, String>() }
    val selectedRows = remember(spec.key) { mutableStateMapOf<Int, Boolean>() }
    var table by remember(spec.key) { mutableStateOf<CsvImportTable?>(null) }
    var busy by remember(spec.key) { mutableStateOf(false) }
    var status by remember(spec.key) { mutableStateOf("") }
    var statusIsError by remember(spec.key) { mutableStateOf(false) }
    var openMenu by remember(spec.key) { mutableStateOf<Int?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            status = ""
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Could not read that data file.")
                        input.use(::readBoundedData).let { content ->
                            val mime = context.contentResolver.getType(uri).orEmpty().lowercase(Locale.ROOT)
                            val path = uri.lastPathSegment.orEmpty().lowercase(Locale.ROOT)
                            val trimmed = content.trimStart()
                            val looksJson = (trimmed.startsWith("[") && trimmed.endsWith("]")) ||
                                (trimmed.startsWith("{") && trimmed.endsWith("}"))
                            if (mime.contains("json") || path.endsWith(".json") || looksJson) {
                                ModuleCsvImport.parseJson(content)
                            } else ModuleCsvImport.parse(content)
                        }
                    }
                }.onSuccess { parsed ->
                    table = parsed
                    mappings.clear()
                    parsed.headers.forEachIndexed { column, header ->
                        mappings[column] = ModuleCsvImport.suggestedTarget(header, spec)
                    }
                    selectedRows.clear()
                }.onFailure {
                    table = null
                    status = it.message ?: "The data file could not be read."
                    statusIsError = true
                }
                busy = false
            }
        }
    }

    val mappingSnapshot = mappings.toMap()
    val currentTable = table
    val review = remember(currentTable, mappingSnapshot, existingEntries, spec) {
        currentTable?.let { ModuleCsvImport.preview(it, spec, existingEntries, mappingSnapshot) }
    }
    val selectableRows = review?.rows.orEmpty().filter { it.errors.isEmpty() }
    val selectedCount = selectableRows.count { row ->
        selectedRows[row.index] ?: (row.possibleDuplicate == null)
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(
            enabled = !busy,
            onClick = {
                picker.launch(arrayOf("text/csv", "text/plain", "application/csv", "application/json"))
            }
        ) { Text(if (busy) "Reading data…" else "Import CSV or JSON") }
        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (statusIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        }
    }

    if (currentTable != null && review != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) table = null },
            title = { Text("Review import · ${spec.name}") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 520.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "${currentTable.rows.size} rows · map each column, then select additions to import.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Import only adds new records. Existing records are never updated or deleted. Duplicate checks use the loaded records (up to 300).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    currentTable.headers.forEachIndexed { column, header ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(header.ifBlank { "Column ${column + 1}" }, style = MaterialTheme.typography.labelLarge)
                                Text("${currentTable.sourceFormat} column", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { openMenu = column }) {
                                Text(
                                    when (val target = mappings[column].orEmpty()) {
                                        "" -> "Skip"
                                        ModuleCsvImport.TITLE_TARGET -> "Record name"
                                        else -> spec.fields.firstOrNull { it.key == target }?.label ?: "Skip"
                                    }
                                )
                            }
                            DropdownMenu(
                                expanded = openMenu == column,
                                onDismissRequest = { openMenu = null }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Skip this column") },
                                    onClick = { mappings[column] = ""; openMenu = null }
                                )
                                DropdownMenuItem(
                                    text = { Text("Record name") },
                                    onClick = { mappings[column] = ModuleCsvImport.TITLE_TARGET; openMenu = null }
                                )
                                spec.fields.filter { it.type != "FILE" && it.type != "COMPUTED" }.forEach { field ->
                                    DropdownMenuItem(
                                        text = { Text("${field.label} · ${field.type}") },
                                        onClick = { mappings[column] = field.key; openMenu = null }
                                    )
                                }
                            }
                        }
                    }
                    review.mappingError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                    Text("Rows to review · ${review.rows.size}", style = MaterialTheme.typography.titleSmall)
                    review.rows.forEach { row ->
                        val checked = row.errors.isEmpty() &&
                            (selectedRows[row.index] ?: (row.possibleDuplicate == null))
                        Row(verticalAlignment = Alignment.Top) {
                            Checkbox(
                                checked = checked,
                                enabled = row.errors.isEmpty() && review.mappingError == null && !busy,
                                onCheckedChange = { selectedRows[row.index] = it }
                            )
                            Column(Modifier.weight(1f).padding(top = 10.dp)) {
                                Text("Row ${row.sourceLine} · ${row.title.ifBlank { "No record name" }}",
                                    style = MaterialTheme.typography.bodyMedium)
                                if (row.values.isNotEmpty()) {
                                    Text(
                                        row.values.entries.joinToString(" · ") { (key, value) ->
                                            "${spec.fields.firstOrNull { it.key == key }?.label ?: key}: $value"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                row.possibleDuplicate?.let {
                                    Text("Possible duplicate of “$it” · unchecked by default",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error)
                                }
                                row.errors.forEach { error ->
                                    Text(error, style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                    Text("$selectedCount selected · these will be added as new records.")
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy && review.mappingError == null && selectedCount > 0,
                    onClick = {
                        val chosen = review.rows.filter { row ->
                            row.errors.isEmpty() &&
                                (selectedRows[row.index] ?: (row.possibleDuplicate == null))
                        }.map { it.title to it.values }
                        if (chosen.isNotEmpty()) {
                            busy = true
                            scope.launch {
                                runCatching { onImport(chosen) }
                                    .onSuccess {
                                        status = "Added ${chosen.size} records. Existing records were unchanged."
                                        statusIsError = false
                                        table = null
                                        mappings.clear()
                                        selectedRows.clear()
                                    }
                                    .onFailure {
                                        status = it.message ?: "The import failed; no records were added."
                                        statusIsError = true
                                    }
                                busy = false
                            }
                        }
                    }
                ) { Text(if (busy) "Importing…" else "Add selected ($selectedCount)") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { table = null }) { Text("Cancel") }
            }
        )
    }
}

private fun readBoundedData(input: InputStream): String {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= ModuleCsvImport.MAX_BYTES) { "This file is larger than the 2 MB import limit." }
        output.write(buffer, 0, count)
    }
    return String(output.toByteArray(), StandardCharsets.UTF_8)
}