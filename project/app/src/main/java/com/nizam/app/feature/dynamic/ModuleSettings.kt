package com.nizam.app.feature.dynamic

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets

private val EMOJI_CHOICES = listOf(
    "✨", "🌙", "⏱️", "🤝", "🗂️", "🔁", "🏅", "📿", "📍", "🍲", "💡", "🔤", "📦", "🔧",
    "🎬", "🤲", "🚴", "🎧", "🧪", "🌱", "🧿", "🗺️", "🪙", "🎨", "🧩", "⚡", "🔥", "🫀"
)

private val COLOUR_CHOICES = listOf(
    "#4FA694", "#E0603A", "#5B6BA8", "#B57F4A", "#8A4F6B", "#6E8E4E",
    "#C9A227", "#7A5BA8", "#3E7C8A", "#A85D3C", "#6B7280", "#2F7A66"
)

@Composable
fun ModuleSettingsScreen(container: AppContainer, moduleKey: String, onDeleted: () -> Unit) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val specs by container.dynamicRepository.allSpecs().collectAsState(initial = emptyList())
    val spec = specs.firstOrNull { it.key == moduleKey }

    if (spec == null) {
        Text("Module not found", modifier = Modifier.padding(20.dp))
        return
    }

    var name by remember(spec.key) { mutableStateOf(spec.name) }
    var tagline by remember(spec.key) { mutableStateOf(spec.tagline) }
    var emoji by remember(spec.key) { mutableStateOf(spec.emoji) }
    var colour by remember(spec.key) { mutableStateOf(spec.colorHex) }
    var enabled by remember(spec.key) { mutableStateOf(spec.enabled) }
    var confirmDelete by remember(spec.key) { mutableStateOf(false) }
    var confirmClear by remember(spec.key) { mutableStateOf(false) }
    var status by remember(spec.key) { mutableStateOf<String?>(null) }
    var templatePreview by remember(spec.key) { mutableStateOf<ModuleTemplatePreview?>(null) }
    var migrationPlan by remember(spec.key) { mutableStateOf<ModuleSchemaMigrationPlan?>(null) }
    var templateBusy by remember(spec.key) { mutableStateOf(false) }
    var exportSpec by remember { mutableStateOf<ModuleSpec?>(null) }
    val context = LocalContext.current

    val exportTemplate = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val selectedSpec = exportSpec
        exportSpec = null
        if (uri != null && selectedSpec != null) {
            templateBusy = true
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver.openOutputStream(uri)
                            ?: error("Could not create the template file.")
                        output.use {
                            it.write(
                                ModuleTemplateExchange.encode(selectedSpec)
                                    .toByteArray(StandardCharsets.UTF_8)
                            )
                        }
                    }
                }.onSuccess {
                    status = "Template exported. No records or attachments were included."
                }.onFailure {
                    status = it.message ?: "The module template could not be exported."
                }
                templateBusy = false
            }
        }
    }

    val importTemplate = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            templateBusy = true
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val input = context.contentResolver.openInputStream(uri)
                            ?: error("Could not read that template.")
                        val encoded = input.use(::readBoundedTemplate)
                        val preview = ModuleTemplateExchange.parse(encoded)
                        val plan = if (spec.builtIn) null else {
                            val records = container.dynamicRepository.entriesForMigration(spec.key)
                            ModuleSchemaMigration.plan(spec, preview, records)
                        }
                        preview to plan
                    }
                }.onSuccess { (preview, plan) ->
                    templatePreview = preview
                    migrationPlan = plan
                    status = "Review the template before importing it."
                }.onFailure {
                    status = it.message ?: "The module template could not be read."
                }
                templateBusy = false
            }
        }
    }

    fun save() {
        scope.launch {
            container.dynamicRepository.updateSpec(
                spec.copy(
                    name = name.trim().ifBlank { spec.name },
                    tagline = tagline.trim(),
                    emoji = emoji,
                    colorHex = colour,
                    enabled = enabled
                )
            )
            status = "Saved"
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("$emoji  ${spec.name}", "Module settings")

        OutlinedTextField(
            value = name, onValueChange = { name = it },
            label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = tagline, onValueChange = { tagline = it },
            label = { Text("Tagline") }, modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(14.dp))
        Text("Icon", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        EMOJI_CHOICES.chunked(7).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                row.forEach { option ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = if (emoji == option) parseColour(colour, MaterialTheme.colorScheme.primary)
                            .copy(alpha = 0.22f)
                        else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.clickable(role = Role.Button) { emoji = option }
                    ) {
                        Text(option, modifier = Modifier.padding(10.dp),
                            style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        Text("Colour", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        COLOUR_CHOICES.chunked(6).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { hex ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = parseColour(hex, MaterialTheme.colorScheme.primary),
                        modifier = Modifier
                            .width(44.dp).height(32.dp)
                            .clickable { colour = hex }
                    ) {
                        if (colour == hex) {
                            Text("✓", modifier = Modifier.padding(6.dp),
                                style = MaterialTheme.typography.titleMedium)
                        } else Text("")
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = enabled, onCheckedChange = { enabled = it })
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Show in Modules", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Hiding keeps your entries — it just takes the tile off the grid.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { save() }) { Text("Save changes") }
            OutlinedButton(onClick = {
                scope.launch {
                    val ordered = specs.sortedBy { it.sortIndex }.toMutableList()
                    val index = ordered.indexOfFirst { it.key == spec.key }
                    if (index > 0) {
                        val item = ordered.removeAt(index)
                        ordered.add(index - 1, item)
                        container.dynamicRepository.reorder(ordered)
                        status = "Moved up"
                    }
                }
            }) { Text("↑  Move up") }
        }

        Spacer(Modifier.height(18.dp))
        Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
        Spacer(Modifier.height(14.dp))
        Text("Module templates", style = MaterialTheme.typography.titleMedium)
        Text(
            "Templates contain the schema and permission declaration, never this module’s records or attachments. " +
                "The example shown during review is generated from the field types.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !spec.builtIn && !templateBusy,
                onClick = {
                    exportSpec = spec
                    exportTemplate.launch(ModuleTemplateExchange.suggestedFileName(spec.name))
                }
            ) { Text(if (templateBusy) "Working…" else "Export template") }
            OutlinedButton(
                enabled = !templateBusy,
                onClick = { importTemplate.launch(arrayOf("application/json", "text/*")) }
            ) { Text("Import new module") }
        }
        if (spec.builtIn) {
            Text(
                "Built-in modules cannot be exported as user-created templates.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }

        Spacer(Modifier.height(20.dp))
        Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
        Spacer(Modifier.height(14.dp))

        Text("Fields", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        spec.fields.forEach { field ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(field.label, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f))
                Text(field.type, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            "To change fields, ask the Curator to build a new version — it designs the schema.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(20.dp))
        Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
        Spacer(Modifier.height(14.dp))

        if (!confirmClear) {
            OutlinedButton(onClick = { confirmClear = true }) { Text("Clear all entries") }
        } else {
            Text("Delete every entry in ${spec.name}? The module stays.",
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        container.dynamicRepository.clearEntries(spec)
                        confirmClear = false; status = "Entries cleared"
                    }
                }) { Text("Clear them") }
                OutlinedButton(onClick = { confirmClear = false }) { Text("Cancel") }
            }
        }

        Spacer(Modifier.height(12.dp))
        if (spec.builtIn) {
            Text(
                "Built-in modules cannot be deleted. Hide this tile from Modules if you don't use it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (!confirmDelete) {
            Text(
                "delete this module",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable(role = Role.Button) { confirmDelete = true }
            )
        } else {
            Text(
                "Delete ${spec.name} and everything in it? This cannot be undone.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    scope.launch {
                        container.dynamicRepository.deleteModule(spec)
                        onDeleted()
                    }
                }) { Text("Delete it") }
                OutlinedButton(onClick = { confirmDelete = false }) { Text("Keep it") }
            }
        }
        Spacer(Modifier.height(60.dp))
    }

    templatePreview?.let { preview ->
        AlertDialog(
            onDismissRequest = {
                if (!templateBusy) {
                    templatePreview = null
                    migrationPlan = null
                }
            },
            title = { Text("Review module template") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 440.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "${preview.emoji}  ${preview.name}",
                        style = MaterialTheme.typography.titleMedium
                    )
                    if (preview.tagline.isNotBlank()) {
                        Text(preview.tagline, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("${preview.fields.size} fields · ${preview.listStyle.lowercase()} layout")
                    Text("Permissions requested: none")
                    Text(
                        if (spec.builtIn) {
                            "No existing records or attachments are copied. The example values are synthetic and will not be saved."
                        } else {
                            "Importing as new copies no records. Applying here changes existing records only after review; examples are synthetic."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    preview.fields.forEach { field ->
                        Text("${field.label} · ${field.type}", style = MaterialTheme.typography.labelLarge)
                        Text(
                            preview.syntheticExample[field.key].orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    if (spec.builtIn) {
                        Text(
                            "Built-in schemas cannot be updated here. You can still import this as a new module.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    migrationPlan?.let { plan ->
                        Spacer(Modifier.height(10.dp))
                        Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
                        Text("Schema change preview for ${spec.name}", style = MaterialTheme.typography.titleSmall)
                        Text("${plan.expectedEntries.size} existing records checked.")
                        val hasFieldChanges = plan.addedFields.isNotEmpty() ||
                            plan.removedFields.isNotEmpty() || plan.updatedFields.isNotEmpty()
                        if (!hasFieldChanges) Text("No field differences.")
                        plan.addedFields.take(8).forEach { Text("Add: ${it.label} · ${it.type}") }
                        plan.removedFields.take(8).forEach { Text("Remove: ${it.label} · ${it.type}") }
                        if (plan.removedFields.any { it.type == "FILE" }) {
                            Text(
                                "Removing a file field removes its record references; source files are not deleted.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        plan.updatedFields.take(8).forEach { change ->
                            val detail = if (change.oldField.type != change.newField.type) {
                                "${change.oldField.type} → ${change.newField.type}"
                            } else {
                                "options or field settings change"
                            }
                            Text("Update: ${change.oldField.label} · $detail")
                        }
                        if (plan.addedFields.size + plan.removedFields.size + plan.updatedFields.size > 8) {
                            Text("Additional field changes are included in the full migration.")
                        }
                        Text(
                            if (plan.discardedValueCount == 0) "No stored values will be discarded."
                            else "${plan.discardedValueCount} stored values will be discarded if applied.",
                            color = if (plan.discardedValueCount == 0) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.error
                        )
                        if (plan.recordsWithRequiredGaps > 0) {
                            Text(
                                "${plan.recordsWithRequiredGaps} records will need values for newly required fields.",
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        plan.conversionFailures.take(6).forEach {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        if (plan.conversionFailures.size > 6) {
                            Text("${plan.conversionFailures.size} incompatible values block applying this migration.")
                        } else if (plan.conversionFailures.isNotEmpty()) {
                            Text("Correct the incompatible values before applying. Preview made no changes.")
                        }
                        Text(
                            "Applying changes requires confirmation and updates the schema and records together.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(
                        enabled = !templateBusy,
                        onClick = {
                            val imported = templatePreview ?: return@TextButton
                            val newSpec = ModuleTemplateExchange.toModuleSpec(
                                imported,
                                sortIndex = (specs.maxOfOrNull { it.sortIndex } ?: -1) + 1
                            )
                            templateBusy = true
                            scope.launch {
                                runCatching {
                                    withContext(Dispatchers.IO) {
                                        check(container.dynamicRepository.spec(newSpec.key) == null) {
                                            "A module with this key already exists. Review the template again."
                                        }
                                        container.dynamicRepository.addSpec(newSpec)
                                    }
                                }.onSuccess {
                                    status = "Imported ${newSpec.name} as a new module. No records were copied."
                                    templatePreview = null
                                    migrationPlan = null
                                }.onFailure {
                                    status = it.message ?: "The module template could not be imported."
                                }
                                templateBusy = false
                            }
                        }
                    ) { Text(if (templateBusy) "Importing…" else "Import new") }
                    if (!spec.builtIn) {
                        TextButton(
                            enabled = !templateBusy && migrationPlan?.conversionFailures?.isEmpty() == true,
                            onClick = {
                                val plan = migrationPlan
                                if (plan != null) {
                                    templateBusy = true
                                    scope.launch {
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                container.dynamicRepository.applySchemaMigration(plan)
                                            }
                                        }.onSuccess {
                                            status = "Updated the schema for ${plan.migratedEntries.size} records; " +
                                                "${plan.discardedValueCount} stored values discarded."
                                            templatePreview = null
                                            migrationPlan = null
                                        }.onFailure {
                                            status = it.message ?: "The schema migration could not be applied."
                                        }
                                        templateBusy = false
                                    }
                                }
                            }
                        ) { Text(if (templateBusy) "Applying…" else "Apply schema") }
                    }
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !templateBusy,
                    onClick = {
                        templatePreview = null
                        migrationPlan = null
                    }
                ) { Text("Cancel") }
            }
        )
    }
}

private fun readBoundedTemplate(input: InputStream): String {
    val bytes = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val count = input.read(buffer)
        if (count < 0) break
        total += count
        require(total <= ModuleTemplateExchange.MAX_TEMPLATE_BYTES) {
            "This template is larger than the safe import limit."
        }
        bytes.write(buffer, 0, count)
    }
    return String(bytes.toByteArray(), StandardCharsets.UTF_8)
}
