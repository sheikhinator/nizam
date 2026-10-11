package com.nizam.app.feature.dynamic

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.nizam.app.ui.design.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.AiPanel
import com.nizam.app.core.ChipRow
import com.nizam.app.core.Heatmap
import com.nizam.app.core.LineChart
import com.nizam.app.core.StatRow
import com.nizam.app.core.fmtAmount
import com.nizam.app.core.displayNameFor
import com.nizam.app.core.prettyDate
import com.nizam.app.ui.theme.DisplayFamily
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.launch
import org.json.JSONObject

fun parseColour(hex: String, fallback: Color): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrElse { fallback }

private var pickingField = "file"

@Composable
fun DynamicModuleScreen(
    container: AppContainer,
    moduleKey: String,
    onSettings: (String) -> Unit = {}
) {
    val repository = container.dynamicRepository
    val scope = rememberCoroutineScope()
    val specs by repository.specs().collectAsState(initial = emptyList())
    val spec = specs.firstOrNull { it.key == moduleKey }

    if (spec == null) {
        Column(Modifier.fillMaxSize().padding(20.dp)) {
            Text("Module not found", style = MaterialTheme.typography.titleLarge)
            Text(
                "It may have been disabled in Modules.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        return
    }

    val entries by repository.entries(moduleKey).collectAsState(initial = emptyList())
    val accent = parseColour(spec.colorHex, MaterialTheme.colorScheme.primary)
    val stats = remember(entries, spec.key) { StatsEngine.compute(spec, entries) }
    var showStats by remember(moduleKey) { mutableStateOf(true) }
    var selectingRecords by remember(moduleKey) { mutableStateOf(false) }
    val selectedRecordIds = remember(moduleKey) { mutableStateMapOf<Long, Boolean>() }
    var confirmBulkDelete by remember(moduleKey) { mutableStateOf(false) }
    var bulkDeleteStatus by remember(moduleKey) { mutableStateOf("") }
    val selectedCount = entries.count { selectedRecordIds[it.id] == true }

    var title by remember(moduleKey) { mutableStateOf("") }
    val values = remember(moduleKey) { mutableStateMapOf<String, String>() }
    var expanded by remember(moduleKey) { mutableStateOf(false) }
    var askAi by remember(moduleKey) { mutableStateOf(false) }
    var instruction by remember(moduleKey) { mutableStateOf("") }
    var generating by remember(moduleKey) { mutableStateOf(false) }
    var genStatus by remember(moduleKey) { mutableStateOf<String?>(null) }
    var openFile by remember(moduleKey) { mutableStateOf<Triple<String, String, String>?>(null) }
    val context = LocalContext.current

    // Files are referenced by their content URI with a persisted read grant, so Atlas can reopen
    // them later without copying gigabytes into its own storage.
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val name = displayNameFor(context, uri)
            val mime = context.contentResolver.getType(uri).orEmpty()
            values[pickingField] = "$uri|$name|$mime"
            if (title.isBlank()) title = name.substringBeforeLast('.')
        }
    }

    openFile?.let { (uriString, name, mime) ->
        ModuleFileViewer(
            uriString = uriString, name = name, mime = mime,
            onBack = { openFile = null }
        )
        return
    }

    if (confirmBulkDelete) {
        val selectedEntries = entries.filter { selectedRecordIds[it.id] == true }
        AlertDialog(
            onDismissRequest = { confirmBulkDelete = false },
            title = { Text("Delete ${selectedEntries.size} records?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("This permanently removes only the selected records.")
                    selectedEntries.take(8).forEach { Text("• ${it.title}") }
                    if (selectedEntries.size > 8) Text("and ${selectedEntries.size - 8} more")
                    Text(
                        "Files linked by these records are not deleted from their original locations.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = selectedEntries.isNotEmpty(),
                    onClick = {
                        val ids = selectedEntries.map { it.id }
                        confirmBulkDelete = false
                        scope.launch {
                            try {
                                val deleted = repository.deleteEntries(moduleKey, ids)
                                bulkDeleteStatus = "Deleted $deleted records."
                                selectedRecordIds.clear()
                                selectingRecords = false
                            } catch (error: Exception) {
                                if (error is kotlinx.coroutines.CancellationException) throw error
                                bulkDeleteStatus = error.message ?: "Could not delete the selected records."
                            }
                        }
                    }
                ) { Text("Delete permanently") }
            },
            dismissButton = {
                TextButton(onClick = { confirmBulkDelete = false }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        item {
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(44.dp).background(accent.copy(alpha = 0.16f), RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) { Text(spec.emoji, style = MaterialTheme.typography.titleLarge) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        spec.name,
                        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = DisplayFamily)
                    )
                    Text(
                        spec.tagline,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    "⚙",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(role = Role.Button) { onSettings(moduleKey) }.padding(8.dp)
                )
            }
            Spacer(Modifier.height(14.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = accent.copy(alpha = 0.10f),
                    modifier = Modifier.weight(1f).clickable { expanded = !expanded; askAi = false }
                ) {
                    Text(
                        if (expanded) "✕  Close" else "＋  Add",
                        style = MaterialTheme.typography.titleMedium,
                        color = accent,
                        modifier = Modifier.padding(14.dp)
                    )
                }
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = accent.copy(alpha = 0.18f),
                    modifier = Modifier.weight(1f).clickable { askAi = !askAi; expanded = false }
                ) {
                    Text(
                        if (askAi) "✕  Close" else "✦  Generate",
                        style = MaterialTheme.typography.titleMedium,
                        color = accent,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }

        item {
            ModuleCsvImportSection(spec, entries) { records ->
                repository.addEntries(moduleKey, records)
            }
        }

        if (entries.isNotEmpty()) {
            item {
                Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (selectingRecords) "$selectedCount selected" else "Manage records",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f)
                        )
                        if (!selectingRecords) {
                            TextButton(onClick = {
                                bulkDeleteStatus = ""
                                selectingRecords = true
                            }) { Text("Select") }
                        } else {
                            TextButton(onClick = {
                                entries.forEach { selectedRecordIds[it.id] = true }
                            }) { Text("Select all") }
                            TextButton(onClick = {
                                selectedRecordIds.clear()
                                selectingRecords = false
                            }) { Text("Cancel") }
                        }
                    }
                    if (selectingRecords) {
                        Button(
                            enabled = selectedCount > 0,
                            onClick = { confirmBulkDelete = true }
                        ) { Text("Delete selected ($selectedCount)") }
                    }
                    if (bulkDeleteStatus.isNotBlank()) {
                        Text(bulkDeleteStatus, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (askAi) {
            item {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = instruction,
                    onValueChange = { instruction = it },
                    label = { Text("What should it make? (optional)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        enabled = !generating,
                        onClick = {
                            scope.launch {
                                generating = true; genStatus = null
                                try {
                                    val n = container.moduleGenerator.generate(spec, instruction)
                                    genStatus = "Added $n entries."
                                    instruction = ""
                                } catch (e: Exception) {
                                    genStatus = e.message
                                }
                                generating = false
                            }
                        }
                    ) { Text(if (generating) "Writing…" else "✦  Generate ${spec.name.lowercase()}") }
                    if (generating) {
                        Spacer(Modifier.width(12.dp))
                        CircularProgressIndicator()
                    }
                }
                genStatus?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
            }
        }

        if (expanded) {
            item {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = title, onValueChange = { title = it },
                        label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    val formValues = values.toMap()
                    spec.fields.filterNot { it.type == "COMPUTED" }
                        .filter { ModuleFieldConditions.isVisible(it, formValues, spec.fields) }
                        .forEach { field ->
                        when (field.type) {
                            "CHOICE" -> {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    field.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                ChipRow(
                                    options = field.options,
                                    selected = values[field.key],
                                    onSelect = { values[field.key] = it }
                                )
                            }

                            "MULTI_SELECT" -> {
                                Spacer(Modifier.height(8.dp))
                                Text(field.label, style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                val selected = StructuredFieldValues.decodeMultiSelect(values[field.key].orEmpty()).toSet()
                                field.options.forEach { option ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Checkbox(
                                            checked = option in selected,
                                            onCheckedChange = { checked ->
                                                val updated = if (checked) selected + option else selected - option
                                                values[field.key] = if (updated.isEmpty()) "" else
                                                    StructuredFieldValues.encodeMultiSelect(updated)
                                            }
                                        )
                                        Text(option, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }

                            "TAGS" -> {
                                OutlinedTextField(
                                    value = values[field.key].orEmpty(),
                                    onValueChange = { values[field.key] = it },
                                    label = { Text("${field.label} · separate tags with semicolons") },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }

                            "RATING" -> {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    field.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    (1..5).forEach { n ->
                                        val on = (values[field.key]?.toIntOrNull() ?: 0) >= n
                                        Text(
                                            if (on) "★" else "☆",
                                            style = MaterialTheme.typography.headlineMedium,
                                            color = if (on) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.clickable(role = Role.Button) { values[field.key] = n.toString() }
                                        )
                                    }
                                }
                            }

                            "FILE" -> {
                                Spacer(Modifier.height(8.dp))
                                val picked = values[field.key].orEmpty()
                                Text(
                                    field.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(4.dp))
                                Button(onClick = {
                                    pickingField = field.key
                                    filePicker.launch(arrayOf("*/*"))
                                }) {
                                    Text(
                                        if (picked.isBlank()) "📎  Choose a file"
                                        else "📎  " + picked.split("|").getOrNull(1).orEmpty().take(28)
                                    )
                                }
                            }

                            "BOOL" -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Checkbox(
                                        checked = values[field.key] == "true",
                                        onCheckedChange = { values[field.key] = if (it) "true" else "false" }
                                    )
                                    Text(field.label, style = MaterialTheme.typography.bodyLarge)
                                }
                            }

                            else -> {
                                OutlinedTextField(
                                    value = values[field.key].orEmpty(),
                                    onValueChange = { values[field.key] = it },
                                    label = {
                                        Text(field.label + if (field.suffix.isBlank()) "" else " (${field.suffix})")
                                    },
                                    singleLine = field.type != "LONGTEXT",
                                    keyboardOptions = if (field.type == "NUMBER")
                                        KeyboardOptions(keyboardType = KeyboardType.Decimal)
                                    else KeyboardOptions.Default,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    val missingRequired = spec.fields.filter {
                        it.required && it.type != "COMPUTED" &&
                            ModuleFieldConditions.isVisible(it, values.toMap(), spec.fields) &&
                            !ModuleFieldConditions.hasValue(it, values[it.key])
                    }
                    if (missingRequired.isNotEmpty()) {
                        Text(
                            "Required: ${missingRequired.joinToString { it.label }}",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Button(
                        enabled = title.isNotBlank() && missingRequired.isEmpty(),
                        onClick = {
                            scope.launch {
                                repository.addEntry(moduleKey, title, values.toMap())
                                title = ""; values.clear(); expanded = false
                            }
                        }
                    ) { Text("Save entry") }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }

        if (!stats.isEmpty) {
            item {
                Spacer(Modifier.height(14.dp))
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            Modifier.fillMaxWidth().clickable { showStats = !showStats },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Overview", style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (showStats) "hide" else "show",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        if (showStats) {
                            Spacer(Modifier.height(8.dp))
                            StatRow(
                                listOf(
                                    "entries" to stats.entryCount.toString(),
                                    "per week" to "%.1f".format(stats.perWeek),
                                    "streak" to "${stats.currentStreak}d"
                                )
                            )

                            StatsEngine.ratios(stats).forEach { (label, value) ->
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(label, style = MaterialTheme.typography.bodyMedium)
                                    Text(value, style = MonoNumber, color = accent)
                                }
                            }

                            stats.numerics.forEach { numeric ->
                                Spacer(Modifier.height(14.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        numeric.field.label,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    numeric.changePercent?.let { change ->
                                        Text(
                                            (if (change >= 0) "^ " else "v ") +
                                                "%.0f%%".format(kotlin.math.abs(change)),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = if (change >= 0) accent
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                LineChart(points = numeric.trend, accent = accent)
                                StatRow(
                                    listOf(
                                        "latest" to fmtAmount(numeric.latest) + " " + numeric.field.suffix,
                                        "average" to "%.1f".format(numeric.average),
                                        "best" to fmtAmount(numeric.best),
                                        "total" to fmtAmount(numeric.total)
                                    )
                                )
                            }

                            stats.choices.forEach { choice ->
                                Spacer(Modifier.height(12.dp))
                                Text(choice.field.label, style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.height(4.dp))
                                choice.counts.take(5).forEach { (option, count) ->
                                    Row(
                                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(option, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            "$count  " + "▪".repeat(
                                                (count * 8 / choice.counts.first().second).coerceAtLeast(1)
                                            ),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = accent
                                        )
                                    }
                                }
                            }

                            stats.ratings.forEach { (field, average) ->
                                Spacer(Modifier.height(10.dp))
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(field.label, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "★".repeat(average.toInt()) + "  %.1f".format(average),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = accent
                                    )
                                }
                            }

                            Spacer(Modifier.height(14.dp))
                            Text(
                                "LAST 30 DAYS",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Heatmap(values = stats.activity, accent = accent)
                        }
                    }
                }
            }
        }

        item { ModuleQualityInspectorSection(spec, entries) }
        item { ModulePivotReportSection(spec, entries) }

        item {
            Spacer(Modifier.height(14.dp))
            Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            if (entries.isEmpty()) {
                Spacer(Modifier.height(18.dp))
                Text(
                    "Nothing here yet. ${spec.emoji}  Add your first and this fills up.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        items(entries, key = { it.id }) { entry ->
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
            ) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (selectingRecords) {
                            Checkbox(
                                checked = selectedRecordIds[entry.id] == true,
                                onCheckedChange = { checked ->
                                    if (checked) selectedRecordIds[entry.id] = true
                                    else selectedRecordIds.remove(entry.id)
                                }
                            )
                        }
                        Box(Modifier.size(6.dp).background(accent, CircleShape))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            entry.title,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            prettyDate(entry.localDate),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val filled = spec.fields.mapNotNull { field ->
                        if (field.type == "COMPUTED") {
                            val computed = com.nizam.app.core.Formula.evaluate(
                                field.formula, entry.numericValues(spec)
                            )
                            return@mapNotNull computed?.let { field to "%.2f".format(it) }
                        }
                        val v = entry.value(field.key)
                        if (v.isBlank()) null else field to v
                    }
                    if (filled.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        filled.forEach { (field, v) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Text(
                                    field.label,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.weight(1f)
                                )
                                if (field.type == "FILE") {
                                    val parts = v.split("|")
                                    Text(
                                        ">  " + parts.getOrNull(1).orEmpty().take(24),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = accent,
                                        modifier = Modifier.clickable(role = Role.Button) {
                                            openFile = Triple(
                                                parts.getOrNull(0).orEmpty(),
                                                parts.getOrNull(1).orEmpty(),
                                                parts.getOrNull(2).orEmpty()
                                            )
                                        }
                                    )
                                } else {
                                    Text(
                                        when (field.type) {
                                            "RATING" -> "★".repeat(v.toIntOrNull() ?: 0)
                                            "MULTI_SELECT" -> StructuredFieldValues.decodeMultiSelect(v).joinToString(" · ")
                                            "TAGS" -> StructuredFieldValues.parseTags(v).joinToString(" · ")
                                            else -> v + if (field.suffix.isBlank()) "" else " ${field.suffix}"
                                        },
                                        style = if (field.type == "NUMBER") MonoNumber
                                        else MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (!selectingRecords) {
                        Text(
                            "delete",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable(role = Role.Button) {
                                scope.launch { repository.deleteEntry(entry.id) }
                            }
                        )
                    }
                }
            }
        }

        item {
            Spacer(Modifier.height(18.dp))
            AiPanel(
                settings = container.settingsRepository,
                label = "${spec.emoji}  Ask about ${spec.name.lowercase()}",
                contextPrompt = "The user's ${spec.name} module (${spec.tagline}). Recent entries:\n" +
                    entries.take(25).joinToString("\n") { e ->
                        e.title + " — " + e.values().entries.joinToString(", ") { "${it.key}: ${it.value}" }
                    },
                starterQuestion = "What patterns do you see, and what should I do next?"
            )
            Spacer(Modifier.height(48.dp))
        }
    }
}
