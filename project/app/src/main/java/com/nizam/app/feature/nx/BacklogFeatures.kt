package com.nizam.app.feature.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.AppContainer
import com.nizam.app.feature.dynamic.ModuleEntry
import com.nizam.app.feature.dynamic.ModuleSpec
import com.nizam.app.feature.agent.SkillsPro
import com.nizam.app.ui.nav.Routes
import com.nizam.app.ui.design.Hub
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

object BacklogFeatures {
    val all: List<Spec> = listOf(
        ScreenSpec("comparemerge", "Universal Compare & Merge Studio", "Compare two versions, resolve field conflicts, and save the merged result", Icons.Outlined.Extension, Hub.Tools,
            "compare merge diff reconcile versions documents records") { c, _ -> CompareMergeScreen(c) },
        ScreenSpec("lifeeventworkspace", "Life-event workspace", "Turn a major change into a private plan, milestones and budget", Icons.Outlined.Extension, Hub.Home,
            "life event workspace moving job wedding baby caregiving plan milestones") { c, open -> LifeEventWorkspaceScreen(c, open) },
        ScreenSpec("modesnavigation", "Modes & Navigation", "Choose a life mode, shape Today, group modules, and manage quick access", Icons.Outlined.Extension, Hub.Tools,
            "modes navigation zen elder accessibility shortcuts language cards groups pinned destinations saved searches") { c, open ->
            ModeNavigationScreen(c, open)
        }
    )
}

/** Temporary, non-modal guide: the highlighted real tab remains tappable beneath the card. */
@Composable
fun GuidedWalkthroughOverlay(
    tabs: List<Pair<String, String>>,
    step: Int,
    paused: Boolean,
    onStep: (Int) -> Unit,
    onPaused: (Boolean) -> Unit,
    onExit: () -> Unit
) {
    val context = LocalContext.current
    val speech = remember(context) { android.speech.tts.TextToSpeech(context, null) }
    DisposableEffect(speech) { onDispose { speech.stop(); speech.shutdown() } }
    val active = tabs.getOrNull(step) ?: return
    val instruction = if (paused) "Paused. Resume when you want to continue." else
        "Tap the highlighted ${active.second} tab. The guide follows your selection; Next skips this step."
    Popup(alignment = Alignment.TopCenter, onDismissRequest = onExit, properties = PopupProperties(focusable = false)) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(18.dp))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Show me how · ${step + 1} of ${tabs.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text("Highlighted control: ${active.second} tab", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(instruction, style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = {
                    speech.speak(instruction, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "atlas-tour")
                }) { Text("Listen") }
                TextButton(onClick = { onPaused(!paused) }) { Text(if (paused) "Resume" else "Pause") }
                TextButton(onClick = onExit) { Text("Exit") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    if (step <= 0) onExit() else onStep(step - 1)
                }) { Text("Previous") }
                Button(onClick = {
                    if (step + 1 >= tabs.size) onExit() else onStep(step + 1)
                }) { Text(if (step + 1 >= tabs.size) "Finish" else "Next") }
            }
        }
    }
}

@Composable
private fun BacklogCard(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) { content() }
}

private data class LinkedRecordUpdate(val before: ModuleEntry, val after: ModuleEntry, val fields: List<String>)

private fun encodeEntry(entry: ModuleEntry) = JSONObject()
    .put("id", entry.id).put("moduleKey", entry.moduleKey).put("localDate", entry.localDate)
    .put("title", entry.title).put("valuesJson", entry.valuesJson).put("done", entry.done).put("createdAt", entry.createdAt)

private fun decodeEntry(value: JSONObject) = ModuleEntry(
    id = value.optLong("id"),
    moduleKey = value.optString("moduleKey"),
    localDate = value.optString("localDate"),
    title = value.optString("title"),
    valuesJson = value.optString("valuesJson", "{}"),
    done = value.optBoolean("done"),
    createdAt = value.optLong("createdAt")
)

private fun explicitLinkValue(value: String, from: ModuleEntry, to: ModuleEntry): String? = when (value) {
    from.id.toString() -> to.id.toString()
    "#${from.id}" -> "#${to.id}"
    "${from.moduleKey}:${from.id}" -> "${to.moduleKey}:${to.id}"
    else -> null
}

private fun duplicateScore(a: ModuleEntry, b: ModuleEntry, spec: ModuleSpec): Double {
    val left = a.title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()
    val right = b.title.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 }.toSet()
    val union = left union right
    val titleOverlap = if (union.isEmpty()) 0.0 else (left intersect right).size.toDouble() / union.size
    val shared = spec.fields.map { it.key }.count { a.value(it).isNotBlank() && b.value(it).isNotBlank() }
    val same = spec.fields.count { it.key.let { key -> a.value(key).isNotBlank() && a.value(key) == b.value(key) } }
    val fieldOverlap = if (shared == 0) 0.0 else same.toDouble() / shared
    return titleOverlap * 0.7 + fieldOverlap * 0.3
}

@Composable
fun CompareMergeScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val specs by container.dynamicRepository.allSpecs().collectAsState(initial = emptyList())
    var moduleKey by remember { mutableStateOf("") }
    LaunchedEffect(specs) { com.nizam.app.ui.design.guard { if (moduleKey.isBlank() && specs.isNotEmpty()) moduleKey = specs.first().key } }
    val entries by remember(container, moduleKey) {
        if (moduleKey.isBlank()) flowOf(emptyList()) else container.dynamicRepository.entries(moduleKey)
    }.collectAsState(initial = emptyList())
    val allEntries by container.dynamicRepository.allEntries().collectAsState(initial = emptyList())
    val spec = specs.firstOrNull { it.key == moduleKey }
    var aId by remember(moduleKey) { mutableStateOf<Long?>(null) }
    var bId by remember(moduleKey) { mutableStateOf<Long?>(null) }
    val a = entries.firstOrNull { it.id == aId }
    val b = entries.firstOrNull { it.id == bId }
    val choices = remember(aId, bId) { androidx.compose.runtime.mutableStateMapOf<String, Long>() }
    val undoStore = remember(context) { JsonStore(context, "compare_merge_undo", sealed = true) }
    var undoRecord by remember { mutableStateOf(undoStore.all().firstOrNull()) }
    var moduleMenu by remember { mutableStateOf(false) }
    var aMenu by remember(moduleKey) { mutableStateOf(false) }
    var bMenu by remember(moduleKey) { mutableStateOf(false) }
    var applyLinked by remember(aId, bId) { mutableStateOf(true) }
    var confirmMerge by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf("") }

    val duplicatePairs = remember(entries, spec) {
        val module = spec
        if (module == null) emptyList() else entries.take(70).flatMapIndexed { index, first ->
            entries.drop(index + 1).map { second -> Triple(first, second, duplicateScore(first, second, module)) }
        }.filter { it.third >= 0.35 }.sortedByDescending { it.third }.take(4)
    }
    val fieldKeys = if (spec == null) emptyList() else
        (spec.fields.map { it.key } + a?.values().orEmpty().keys + b?.values().orEmpty().keys).distinct()
    val unresolved = buildList {
        if (a != null && b != null && a.title.isNotBlank() && b.title.isNotBlank() && a.title != b.title && "__title" !in choices) add("__title")
        if (a != null && b != null) fieldKeys.forEach { key ->
            if (a.value(key).isNotBlank() && b.value(key).isNotBlank() && a.value(key) != b.value(key) && key !in choices) add(key)
        }
    }
    val linkUpdates = remember(allEntries, a, b) {
        if (a == null || b == null) emptyList() else allEntries.filter { it.id != a.id && it.id != b.id }.mapNotNull { entry ->
            val before = entry.values()
            val after = before.mapValues { (_, value) -> explicitLinkValue(value, b, a) ?: value }
            val affected = before.filter { (key, value) -> explicitLinkValue(value, b, a) != null }.keys.toList()
            if (affected.isEmpty()) null else LinkedRecordUpdate(entry, entry.copy(valuesJson = JSONObject(after).toString()), affected)
        }
    }
    val commitLinks = if (applyLinked) linkUpdates else emptyList()
    val mergedValues = if (a == null || b == null) emptyMap() else fieldKeys.mapNotNull { key ->
        val av = a.value(key)
        val bv = b.value(key)
        val value = when {
            av.isBlank() -> bv
            bv.isBlank() || av == bv -> av
            choices[key] == a.id -> av
            choices[key] == b.id -> bv
            else -> ""
        }
        if (value.isBlank()) null else key to value
    }.toMap()
    val mergedTitle = if (a == null || b == null) "" else when {
        a.title == b.title || b.title.isBlank() -> a.title
        a.title.isBlank() -> b.title
        choices["__title"] == a.id -> a.title
        choices["__title"] == b.id -> b.title
        else -> ""
    }
    val ready = a != null && b != null && a.id != b.id && unresolved.isEmpty()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Universal Compare & Merge Studio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Choose two records from any installed built-in or custom module. Nothing merges automatically; every field and linked-record change is shown before commit.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = { moduleMenu = true }) {
            Text(spec?.let { "${it.emoji} ${it.name}" } ?: "Choose module")
        }
        DropdownMenu(expanded = moduleMenu, onDismissRequest = { moduleMenu = false }) {
            specs.forEach { module ->
                DropdownMenuItem(text = { Text("${module.emoji} ${module.name}") }, onClick = {
                    moduleKey = module.key; aId = null; bId = null; moduleMenu = false
                })
            }
        }
        if (spec != null) {
            Text("Likely duplicates · suggestions use title similarity and matching field values only.")
            if (duplicatePairs.isEmpty()) Text("No likely pair found in the latest ${entries.size} records. You can still select any two records.")
            duplicatePairs.forEach { (first, second, score) ->
                OutlinedButton(onClick = { aId = first.id; bId = second.id }) {
                    Text("${first.title.ifBlank { "Entry #${first.id}" }} ↔ ${second.title.ifBlank { "Entry #${second.id}" }} · ${"%.0f".format(score * 100)}%")
                }
            }
            TextButton(onClick = { aMenu = true }) { Text("Record A · ${a?.title?.ifBlank { "Choose a record" } ?: "Choose a record"}") }
            DropdownMenu(expanded = aMenu, onDismissRequest = { aMenu = false }) {
                entries.take(100).forEach { entry -> DropdownMenuItem(
                    text = { Text("${entry.title.ifBlank { "Entry #${entry.id}" }} · ${entry.localDate}") },
                    onClick = { aId = entry.id; aMenu = false }
                ) }
            }
            TextButton(onClick = { bMenu = true }) { Text("Record B · ${b?.title?.ifBlank { "Choose a record" } ?: "Choose a record"}") }
            DropdownMenu(expanded = bMenu, onDismissRequest = { bMenu = false }) {
                entries.take(100).forEach { entry -> DropdownMenuItem(
                    text = { Text("${entry.title.ifBlank { "Entry #${entry.id}" }} · ${entry.localDate}") },
                    onClick = { bId = entry.id; bMenu = false }
                ) }
            }
        } else if (specs.isEmpty()) {
            Text("No dynamic modules are installed yet.")
        }

        if (a != null && b != null && a.id == b.id) Text("Choose two different records.", color = MaterialTheme.colorScheme.error)
        if (a != null && b != null && a.id != b.id && spec != null) {
            BacklogCard {
                Text("Record title", fontWeight = FontWeight.Bold)
                Text("A · ${a.title} · ${spec.name} · ${a.localDate} · ID ${a.id}")
                Text("B · ${b.title} · ${spec.name} · ${b.localDate} · ID ${b.id}")
                if (a.title != b.title && a.title.isNotBlank() && b.title.isNotBlank()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { choices["__title"] = a.id }) { Text(if (choices["__title"] == a.id) "✓ Keep A title" else "Keep A title") }
                        OutlinedButton(onClick = { choices["__title"] = b.id }) { Text(if (choices["__title"] == b.id) "✓ Keep B title" else "Keep B title") }
                    }
                }
            }
            fieldKeys.forEach { key ->
                val av = a.value(key); val bv = b.value(key)
                val label = spec.fields.firstOrNull { it.key == key }?.label ?: key
                BacklogCard {
                    Text(label, fontWeight = FontWeight.Bold)
                    Text("A · ${a.title} · ${a.localDate} · #${a.id}: ${av.ifBlank { "(empty)" }}")
                    Text("B · ${b.title} · ${b.localDate} · #${b.id}: ${bv.ifBlank { "(empty)" }}")
                    if (av.isNotBlank() && bv.isNotBlank() && av != bv) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { choices[key] = a.id }) { Text(if (choices[key] == a.id) "✓ Keep A" else "Keep A") }
                            OutlinedButton(onClick = { choices[key] = b.id }) { Text(if (choices[key] == b.id) "✓ Keep B" else "Keep B") }
                        }
                    } else {
                        Text("Merged value · ${av.ifBlank { bv }.ifBlank { "(empty)" }}", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Text("Linked-record preview · ${linkUpdates.size} record${if (linkUpdates.size == 1) "" else "s"} reference B by an explicit record ID.")
            if (linkUpdates.isEmpty()) Text("No explicit ID links were found in ${allEntries.size} module records.")
            linkUpdates.forEach { change ->
                Text("• ${specs.firstOrNull { it.key == change.before.moduleKey }?.name ?: change.before.moduleKey} · " +
                    "${change.before.title.ifBlank { "Entry #${change.before.id}" }} · fields ${change.fields.joinToString()}",
                    style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = applyLinked, onCheckedChange = { applyLinked = it })
                Text("Update these links to Record A")
            }
            BacklogCard {
                Text("Merge preview · ${if (mergedTitle.isBlank()) "choose a title" else mergedTitle}", fontWeight = FontWeight.Bold)
                Text(mergedValues.entries.joinToString("\n") { "${spec.fields.firstOrNull { field -> field.key == it.key }?.label ?: it.key}: ${it.value}" }
                    .ifBlank { "No field values to merge." }, style = MaterialTheme.typography.bodySmall)
                Text("${commitLinks.size} linked records will change · Record B will be removed. Undo is available after the transaction.",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (unresolved.isNotEmpty()) Text("Choose a side for ${unresolved.size} conflicting field${if (unresolved.size == 1) "" else "s"} first.",
                color = MaterialTheme.colorScheme.error)
            Button(onClick = { confirmMerge = true }, enabled = ready) { Text("Review and merge into Record A") }
        }
        if (notice.isNotBlank()) Text(notice, color = MaterialTheme.colorScheme.primary)
        undoRecord?.let { undo ->
            OutlinedButton(onClick = {
                val originals = listOf(decodeEntry(undo.optJSONObject("keep") ?: JSONObject()), decodeEntry(undo.optJSONObject("remove") ?: JSONObject()))
                val linkedArray = undo.optJSONArray("linkedBefore") ?: JSONArray()
                val linkedBefore = (0 until linkedArray.length()).mapNotNull { linkedArray.optJSONObject(it)?.let(::decodeEntry) }
                scope.launch {
                    runCatching { container.dynamicRepository.undoMerge(originals, linkedBefore) }
                        .onSuccess { undoStore.save(emptyList()); undoRecord = null; aId = null; bId = null; notice = "The merge was undone." }
                        .onFailure { notice = "Undo failed: ${it.message ?: "storage error"}" }
                }
            }) { Text("Undo last merge") }
        }
    }
    if (confirmMerge && a != null && b != null) AlertDialog(
        onDismissRequest = { confirmMerge = false },
        title = { Text("Merge ${b.title.ifBlank { "Record B" }} into ${a.title.ifBlank { "Record A" }}?") },
        text = { Text("This will update Record A, remove Record B, and ${if (commitLinks.isEmpty()) "leave linked records unchanged" else "update ${commitLinks.size} linked record(s)"}. No other records are changed. You can undo this transaction.") },
        confirmButton = {
            TextButton(onClick = {
                confirmMerge = false
                val originalA = a
                val originalB = b
                val merged = originalA.copy(title = mergedTitle, valuesJson = JSONObject(mergedValues).toString(), done = originalA.done || originalB.done)
                val undo = undoStore.add(JSONObject().put("keep", encodeEntry(originalA)).put("remove", encodeEntry(originalB))
                    .put("linkedBefore", JSONArray(commitLinks.map { encodeEntry(it.before) })))
                scope.launch {
                    runCatching { container.dynamicRepository.mergeEntries(merged, commitLinks.map { it.after }, originalB.id) }
                        .onSuccess {
                            undoRecord = undoStore.all().firstOrNull()
                            aId = null; bId = null; notice = "Records merged. Use Undo last merge to restore the original records and links."
                        }
                        .onFailure {
                            undoStore.remove(undo.optString("id"))
                            notice = "Merge failed; no changes were committed: ${it.message ?: "storage error"}"
                        }
                }
            }) { Text("Merge records") }
        },
        dismissButton = { TextButton(onClick = { confirmMerge = false }) { Text("Cancel") } }
    )
}

private val EVENT_TEMPLATES = linkedMapOf(
    "Moving home" to listOf("Set a move date", "Confirm a budget", "Sort and pack essentials", "Arrange utilities and address updates", "Plan first-week meals and help"),
    "New job" to listOf("Confirm offer and start date", "Prepare documents", "Plan commute or remote setup", "Review monthly budget", "Set first-month goals"),
    "Wedding" to listOf("Set date and guest range", "Create a budget", "Choose venue and key vendors", "Track documents and invitations", "Plan the week before"),
    "New baby" to listOf("Plan appointments and support", "Prepare essential supplies", "Set a family budget", "Arrange leave and paperwork", "Make a first-week support plan"),
    "Caregiving" to listOf("List immediate care needs", "Gather appointments and contacts", "Agree who can help", "Track expected costs", "Schedule a review date"),
    "Ramadan" to listOf("Set the month’s priorities", "Plan suhoor and iftar routines", "Coordinate family and community commitments", "Review zakat and giving", "Plan Eid preparations"),
    "Side business launch" to listOf("Define the first offer", "Set a launch budget", "Prepare the first customer workflow", "List compliance and setup tasks", "Choose a first-month measure")
)

@Composable
fun LifeEventWorkspaceScreen(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current
    val store = remember(c) { JsonStore(c, "life_event_workspaces", sealed = true) }
    val specs by container.dynamicRepository.allSpecs().collectAsState(initial = emptyList())
    val skills = remember(c) { SkillsPro.all(c) }
    var events by remember { mutableStateOf(store.all().sortedByDescending { it.optLong("created") }) }
    var activeId by rememberSaveable { mutableStateOf("") }
    var title by rememberSaveable { mutableStateOf("") }
    var eventType by rememberSaveable { mutableStateOf(EVENT_TEMPLATES.keys.first()) }
    var date by rememberSaveable { mutableStateOf("") }
    var budget by rememberSaveable { mutableStateOf("") }
    var newMilestone by rememberSaveable { mutableStateOf("") }
    var confirmArchive by rememberSaveable { mutableStateOf(false) }
    var recordPicker by rememberSaveable { mutableStateOf(false) }
    var skillPicker by rememberSaveable { mutableStateOf(false) }
    var moduleMenu by rememberSaveable { mutableStateOf(false) }
    var entryMenu by rememberSaveable { mutableStateOf(false) }
    var linkModuleKey by rememberSaveable { mutableStateOf("") }
    var linkRecordId by rememberSaveable(linkModuleKey) { mutableStateOf<Long?>(null) }
    val linkEntries by remember(container, linkModuleKey) {
        if (linkModuleKey.isBlank()) flowOf(emptyList()) else container.dynamicRepository.entries(linkModuleKey)
    }.collectAsState(initial = emptyList())
    fun refresh() { events = store.all().sortedByDescending { it.optLong("created") } }
    val active = events.firstOrNull { it.optString("id") == activeId }
    val archived = active?.optString("status") == "archived"
    fun pinLink(link: JSONObject) {
        if (archived) return
        val event = events.firstOrNull { it.optString("id") == activeId } ?: return
        val links = event.optJSONArray("links") ?: JSONArray()
        val duplicate = (0 until links.length()).any { index ->
            val old = links.optJSONObject(index) ?: JSONObject()
            old.optString("kind") == link.optString("kind") && old.optString("key") == link.optString("key")
        }
        if (!duplicate) {
            val updated = JSONArray()
            (0 until links.length()).forEach { index -> updated.put(JSONObject((links.optJSONObject(index) ?: JSONObject()).toString())) }
            updated.put(link)
            store.update(activeId) { it.put("links", updated) }
        }
        refresh()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Life-event workspace", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Keep the plan, checklist, dates and budget for a major change together. Workspaces are optional and encrypted on this device.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (active == null) {
            Text("Start a workspace", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(title, { title = it.take(100) }, modifier = Modifier.fillMaxWidth(), label = { Text("What is changing?") }, singleLine = true)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                EVENT_TEMPLATES.keys.forEach { kind ->
                    OutlinedButton(onClick = { eventType = kind }) { Text(if (eventType == kind) "✓ $kind" else kind) }
                }
            }
            OutlinedTextField(date, { date = it.take(40) }, modifier = Modifier.fillMaxWidth(), label = { Text("Important date (optional)") }, singleLine = true)
            OutlinedTextField(budget, { budget = it.take(60) }, modifier = Modifier.fillMaxWidth(), label = { Text("Budget or cost notes (optional)") }, singleLine = true)
            Button(onClick = {
                if (title.isNotBlank()) {
                    val steps = JSONArray().apply {
                        EVENT_TEMPLATES[eventType].orEmpty().forEach { put(JSONObject().put("title", it).put("done", false)) }
                    }
                    val created = store.add(JSONObject().put("title", title.trim()).put("type", eventType).put("date", date.trim())
                        .put("budget", budget.trim()).put("milestones", steps).put("links", JSONArray())
                        .put("status", "active").put("created", System.currentTimeMillis()))
                    activeId = created.optString("id")
                    title = ""; date = ""; budget = ""
                    refresh()
                }
            }, enabled = title.isNotBlank()) { Text("Create workspace") }
            val activeEvents = events.filter { it.optString("status", "active") != "archived" }
            val archivedEvents = events.filter { it.optString("status") == "archived" }
            Text("Active workspaces · ${activeEvents.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (activeEvents.isEmpty()) Text("No active event workspaces.")
            activeEvents.forEach { event ->
                BacklogCard {
                    Text("${event.optString("type")} · ${event.optString("title")}", fontWeight = FontWeight.SemiBold)
                    val milestones = event.optJSONArray("milestones") ?: JSONArray()
                    val done = (0 until milestones.length()).count { milestones.optJSONObject(it)?.optBoolean("done") == true }
                    val links = event.optJSONArray("links")?.length() ?: 0
                    Text("$done of ${milestones.length()} milestones complete · $links pinned links · ${event.optString("date").ifBlank { "no date set" }}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { activeId = event.optString("id") }) { Text("Open workspace") }
                }
            }
            Text("Dated archive · ${archivedEvents.size}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (archivedEvents.isEmpty()) Text("Closed events will appear here with their archive date.")
            archivedEvents.forEach { event ->
                BacklogCard {
                    Text("${event.optString("title")} · ${event.optString("type")}", fontWeight = FontWeight.SemiBold)
                    Text("Archived ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(event.optLong("archivedAt")))}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { activeId = event.optString("id") }) { Text("View archive") }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { activeId = "" }) { Text("‹ All workspaces") }
                if (!archived) TextButton(onClick = { confirmArchive = true }) { Text("Close & archive") }
            }
            BacklogCard {
                Text(active.optString("title"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(active.optString("type") + active.optString("date").takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty())
                Text(active.optString("budget").ifBlank { "No budget note yet." }, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (archived) Text("Archived ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(active.optLong("archivedAt")))}",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("Pinned links · data stays in its original module", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val links = active.optJSONArray("links") ?: JSONArray()
            if (links.length() == 0) Text("No links pinned yet.")
            (0 until links.length()).forEach { index ->
                val link = links.optJSONObject(index) ?: JSONObject()
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable { link.optString("route").takeIf { it.isNotBlank() }?.let(open) }) {
                        Text(link.optString("title"), fontWeight = FontWeight.SemiBold)
                        Text(when (link.optString("kind")) {
                            "record" -> "${specs.firstOrNull { it.key == link.optString("moduleKey") }?.name ?: link.optString("moduleKey")} · record #${link.optLong("recordId")} · ${link.optString("date")}"
                            "skill" -> "Saved Agent skill"
                            else -> "Atlas area"
                        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (!archived) TextButton(onClick = {
                        val updated = JSONArray()
                        (0 until links.length()).filter { it != index }.forEach { i ->
                            updated.put(JSONObject((links.optJSONObject(i) ?: JSONObject()).toString()))
                        }
                        store.update(activeId) { it.put("links", updated) }
                        refresh()
                    }) { Text("Unpin") }
                }
            }
            if (!archived) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Tasks" to Routes.TASKS, "Budget" to Routes.FINANCE, "People" to Routes.PEOPLE, "Notes" to Routes.NOTES).forEach { (label, route) ->
                        OutlinedButton(onClick = { pinLink(JSONObject().put("kind", "screen").put("key", route).put("title", label).put("route", route)) }) {
                            Text("Pin $label")
                        }
                    }
                    OutlinedButton(onClick = { skillPicker = true }) { Text("Pin Agent skill") }
                }
                OutlinedButton(onClick = { recordPicker = !recordPicker }) {
                    Text(if (recordPicker) "Hide record picker" else "Link an existing record")
                }
                if (recordPicker) {
                    Text("Choose an existing module record; the source remains in place and no data is copied.")
                    OutlinedButton(onClick = { moduleMenu = true }) {
                        Text(specs.firstOrNull { it.key == linkModuleKey }?.let { "${it.emoji} ${it.name}" } ?: "Choose module")
                    }
                    DropdownMenu(expanded = moduleMenu, onDismissRequest = { moduleMenu = false }) {
                        specs.forEach { module ->
                            DropdownMenuItem(text = { Text("${module.emoji} ${module.name}") }, onClick = {
                                linkModuleKey = module.key
                                linkRecordId = null
                                moduleMenu = false
                            })
                        }
                    }
                    OutlinedButton(onClick = { entryMenu = true }, enabled = linkModuleKey.isNotBlank()) {
                        Text(linkEntries.firstOrNull { it.id == linkRecordId }?.let { it.title.ifBlank { "Record #${it.id}" } } ?: "Choose record")
                    }
                    DropdownMenu(expanded = entryMenu, onDismissRequest = { entryMenu = false }) {
                        linkEntries.take(100).forEach { entry ->
                            DropdownMenuItem(text = { Text("${entry.title.ifBlank { "Record #${entry.id}" }} · ${entry.localDate}") }, onClick = {
                                linkRecordId = entry.id
                                entryMenu = false
                            })
                        }
                    }
                    Button(enabled = linkRecordId != null, onClick = {
                        val entry = linkEntries.firstOrNull { it.id == linkRecordId } ?: return@Button
                        val module = specs.firstOrNull { it.key == linkModuleKey } ?: return@Button
                        pinLink(JSONObject().put("kind", "record").put("key", "${module.key}:${entry.id}")
                            .put("moduleKey", module.key).put("recordId", entry.id)
                            .put("title", entry.title.ifBlank { "Record #${entry.id}" }).put("date", entry.localDate)
                            .put("route", Routes.dynamic(module.key)))
                        recordPicker = false
                    }) { Text("Pin record link") }
                }
            }
            Text("Milestones", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val milestones = active.optJSONArray("milestones") ?: JSONArray()
            (0 until milestones.length()).forEach { index ->
                val milestone = milestones.optJSONObject(index) ?: JSONObject()
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Checkbox(checked = milestone.optBoolean("done"), enabled = !archived, onCheckedChange = { checked ->
                        val updated = JSONArray()
                        (0 until milestones.length()).forEach { i ->
                            val old = milestones.optJSONObject(i) ?: JSONObject()
                            updated.put(if (i == index) JSONObject(old.toString()).put("done", checked) else JSONObject(old.toString()))
                        }
                        store.update(activeId) { it.put("milestones", updated) }
                        refresh()
                    })
                    Text(milestone.optString("title"), modifier = Modifier.padding(top = 11.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newMilestone, { newMilestone = it.take(120) }, modifier = Modifier.weight(1f), label = { Text("Add a milestone") }, singleLine = true, enabled = !archived)
                Button(onClick = {
                    val updated = JSONArray()
                    (0 until milestones.length()).forEach { i -> updated.put(JSONObject((milestones.optJSONObject(i) ?: JSONObject()).toString())) }
                    updated.put(JSONObject().put("title", newMilestone.trim()).put("done", false))
                    store.update(activeId) { it.put("milestones", updated) }
                    newMilestone = ""
                    refresh()
                }, enabled = newMilestone.isNotBlank() && !archived) { Text("Add") }
            }
        }
    }
    if (skillPicker && active != null && !archived) AlertDialog(
        onDismissRequest = { skillPicker = false },
        title = { Text("Pin a saved Agent skill") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                if (skills.isEmpty()) Text("No saved skills yet. Create one in Agent Skills first.")
                skills.take(40).forEach { skill ->
                    TextButton(onClick = {
                        pinLink(JSONObject().put("kind", "skill").put("key", skill.optString("id"))
                            .put("title", skill.optString("name", "Untitled skill")).put("skillId", skill.optString("id"))
                            .put("route", Routes.SKILLS))
                        skillPicker = false
                    }) { Text(skill.optString("name", "Untitled skill")) }
                }
            }
        },
        confirmButton = { TextButton(onClick = { skillPicker = false }) { Text("Done") } }
    )
    if (confirmArchive && active != null && !archived) AlertDialog(
        onDismissRequest = { confirmArchive = false },
        title = { Text("Close and archive this event?") },
        text = { Text("“${active.optString("title")}” will move to the dated archive. Its milestones and pinned links stay available for reference.") },
        confirmButton = {
            TextButton(onClick = {
                store.update(activeId) { it.put("status", "archived").put("archivedAt", System.currentTimeMillis()) }
                activeId = ""
                confirmArchive = false
                refresh()
            }) { Text("Close and archive") }
        },
        dismissButton = { TextButton(onClick = { confirmArchive = false }) { Text("Keep open") } }
    )
}