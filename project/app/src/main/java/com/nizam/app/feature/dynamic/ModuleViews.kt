package com.nizam.app.feature.dynamic

import androidx.compose.material.icons.outlined.ViewKanban

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Views and rules for your own modules: see records as a list, a board grouped by any choice field
 * (moving cards between stages, with allowed moves as a workflow), a calendar by date or a gallery;
 * link records across modules and roll their numbers up; and keep cross-field rules that flag
 * records breaking them.
 */
object ModuleRules {
    private fun p(c: Context) = c.getSharedPreferences("module_rules", Context.MODE_PRIVATE)
    private fun obj(c: Context, k: String) = runCatching { JSONObject(p(c).getString(k, "{}")!!) }.getOrElse { JSONObject() }

    // workflow: stage → allowed next stages, per module+field
    fun transitions(c: Context, module: String, field: String): Map<String, List<String>> = obj(c, "wf_${module}_$field").let { o ->
        o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> (0 until a.length()).map { a.optString(it) } } } }
    fun setTransitions(c: Context, module: String, field: String, map: Map<String, List<String>>) =
        p(c).edit().putString("wf_${module}_$field", JSONObject(map.mapValues { JSONArray(it.value) }).toString()).apply()
    fun canMove(c: Context, module: String, field: String, from: String, to: String): Boolean =
        transitions(c, module, field)[from]?.contains(to) ?: true

    // validation rules: "a <= b", "a > 0", "end >= start"
    fun rules(c: Context, module: String): List<String> = p(c).getString("rules_$module", "")!!.lines().map { it.trim() }.filter { it.isNotEmpty() }
    fun setRules(c: Context, module: String, rules: List<String>) = p(c).edit().putString("rules_$module", rules.joinToString("\n")).apply()

    private val RULE = Regex("""^([a-zA-Z0-9_]+)\s*(<=|>=|<|>|==|!=)\s*([a-zA-Z0-9_.\-]+)$""")

    /** The rules this record breaks. Numbers and dates (YYYY-MM-DD) both compare. */
    fun violations(spec: ModuleSpec, e: ModuleEntry, rules: List<String>): List<String> = rules.mapNotNull { r ->
        val m = RULE.find(r) ?: return@mapNotNull null
        val (a, op, b) = m.destructured
        val left = e.value(a); val right = e.value(b).ifBlank { b }
        if (left.isBlank()) return@mapNotNull null
        val cmp = run {
            val ln = left.toDoubleOrNull(); val rn = right.toDoubleOrNull()
            if (ln != null && rn != null) ln.compareTo(rn) else left.compareTo(right)
        }
        val ok = when (op) { "<=" -> cmp <= 0; ">=" -> cmp >= 0; "<" -> cmp < 0; ">" -> cmp > 0; "==" -> cmp == 0; else -> cmp != 0 }
        if (ok) null else "$r (${spec.fields.firstOrNull { it.key == a }?.label ?: a} = $left)"
    }

    // links: field in module A holds the title of a record in module B
    fun link(c: Context, module: String): Triple<String, String, String>? = obj(c, "link_$module").let { o ->
        if (!o.has("field")) null else Triple(o.optString("field"), o.optString("target"), o.optString("metric")) }
    fun setLink(c: Context, module: String, field: String, target: String, metric: String) =
        p(c).edit().putString("link_$module", JSONObject().put("field", field).put("target", target).put("metric", metric).toString()).apply()
}

@Composable
fun ModuleViewsScreen(container: AppContainer) {
    val c = LocalContext.current
    val work = com.nizam.app.ui.design.rememberWorkScope()
    val repo = container.dynamicRepository
    val specs by repo.specs().collectAsState(initial = emptyList())
    var key by remember { mutableStateOf<String?>(null) }
    val spec = specs.firstOrNull { it.key == key } ?: specs.firstOrNull()
    if (spec == null) { Text("No modules yet."); return }
    val entries by repo.entries(spec.key).collectAsState(initial = emptyList())
    val all by repo.allEntries().collectAsState(initial = emptyList())
    var view by remember { mutableStateOf("Board") }
    var tick by remember { mutableIntStateOf(0) }
    val choices = spec.fields.filter { it.type == "CHOICE" }
    var groupBy by remember(spec.key) { mutableStateOf(choices.firstOrNull()?.key) }
    val dateField = spec.fields.firstOrNull { it.type == "DATE" }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            specs.forEach { s -> FilterChip(selected = s.key == spec.key, onClick = { key = s.key }, label = { Text("${s.emoji} ${s.name}") }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Board", "Calendar", "Gallery", "Links", "Rules").forEach { v -> FilterChip(selected = view == v, onClick = { view = v }, label = { Text(v) }) }
        }
        when (view) {
            "Board" -> {
                if (choices.isEmpty()) { Text("Add a choice field (like Status) to this module to see it as a board."); return@Column }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { choices.forEach { f -> FilterChip(selected = groupBy == f.key, onClick = { groupBy = f.key }, label = { Text(f.label) }) } }
                val field = choices.first { it.key == groupBy }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    (field.options + "").forEach { stage ->
                        val cards = entries.filter { it.value(field.key) == stage }
                        if (stage.isBlank() && cards.isEmpty()) return@forEach
                        Column(Modifier.width(220.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("${stage.ifBlank { "No ${field.label.lowercase()}" }} · ${cards.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            cards.take(30).forEach { e ->
                                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(8.dp)) {
                                    Text(e.title.ifBlank { "Record #${e.id}" }, style = MaterialTheme.typography.bodyMedium)
                                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                                        field.options.filter { it != stage && ModuleRules.canMove(c, spec.key, field.key, stage, it) }.forEach { to ->
                                            TextButton(onClick = {
                                                val v = JSONObject(e.valuesJson).put(field.key, to)
                                                work.launch { repo.saveEntry(e.copy(valuesJson = v.toString())) }
                                            }) { Text("→ $to", style = MaterialTheme.typography.labelSmall) }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                WorkflowEditor(c, spec, field) { tick++ }
            }
            "Calendar" -> {
                val byDate = entries.groupBy { (dateField?.let { f -> it.value(f.key) }?.takeIf { d -> d.isNotBlank() } ?: it.localDate).take(10) }.toSortedMap(compareByDescending { it })
                Text("By ${dateField?.label ?: "entry date"}", style = MaterialTheme.typography.labelMedium)
                byDate.entries.take(60).forEach { (d, list) ->
                    Text(d, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
                    list.forEach { Text("• ${it.title.ifBlank { "Record #${it.id}" }}", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            "Gallery" -> {
                entries.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pair.forEach { e ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(parseColour(spec.colorHex, MaterialTheme.colorScheme.primary).copy(alpha = 0.14f)).padding(12.dp)) {
                                Text(spec.emoji + " " + e.title.ifBlank { "Record #${e.id}" }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                                spec.fields.take(3).forEach { f -> e.value(f.key).takeIf { it.isNotBlank() }?.let { Text("${f.label}: $it${f.suffix}", style = MaterialTheme.typography.labelSmall) } }
                            }
                        }
                        if (pair.size == 1) Column(Modifier.weight(1f)) {}
                    }
                }
            }
            "Links" -> LinksPane(c, spec, specs, entries, all)
            else -> RulesPane(c, spec, entries)
        }
    }
}

@Composable
private fun WorkflowEditor(c: Context, spec: ModuleSpec, field: SpecField, changed: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    TextButton(onClick = { open = !open }) { Text(if (open) "Hide workflow" else "Workflow: which moves are allowed?") }
    if (!open) return
    var map by remember(spec.key, field.key) { mutableStateOf(ModuleRules.transitions(c, spec.key, field.key)) }
    field.options.forEach { from ->
        Text("From \"$from\" to:", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            field.options.filter { it != from }.forEach { to ->
                val allowed = map[from]?.contains(to) ?: true
                FilterChip(selected = allowed, onClick = {
                    val cur = (map[from] ?: field.options.filter { it != from }).toMutableList()
                    if (allowed) cur.remove(to) else cur.add(to)
                    map = map + (from to cur); ModuleRules.setTransitions(c, spec.key, field.key, map); changed()
                }, label = { Text(to) })
            }
        }
    }
}

@Composable
private fun LinksPane(c: Context, spec: ModuleSpec, specs: List<ModuleSpec>, entries: List<ModuleEntry>, all: List<ModuleEntry>) {
    val saved = remember(spec.key) { ModuleRules.link(c, spec.key) }
    var field by remember(spec.key) { mutableStateOf(saved?.first ?: spec.fields.firstOrNull { it.type == "TEXT" }?.key.orEmpty()) }
    var target by remember(spec.key) { mutableStateOf(saved?.second ?: specs.firstOrNull { it.key != spec.key }?.key.orEmpty()) }
    val targetSpec = specs.firstOrNull { it.key == target }
    var metric by remember(spec.key) { mutableStateOf(saved?.third ?: "") }
    Text("Link a field to records in another module (it holds their titles), then roll their numbers up.", style = MaterialTheme.typography.bodySmall)
    Text("Field in ${spec.name}", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        spec.fields.filter { it.type == "TEXT" || it.type == "CHOICE" }.forEach { f -> FilterChip(selected = field == f.key, onClick = { field = f.key }, label = { Text(f.label) }) }
    }
    Text("Points to module", style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        specs.filter { it.key != spec.key }.forEach { s -> FilterChip(selected = target == s.key, onClick = { target = s.key }, label = { Text("${s.emoji} ${s.name}") }) }
    }
    if (targetSpec != null) {
        Text("Roll up (sum) from ${targetSpec.name}", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(selected = metric.isBlank(), onClick = { metric = "" }, label = { Text("Count") })
            targetSpec.fields.filter { it.type == "NUMBER" || it.type == "COMPUTED" || it.type == "RATING" }.forEach { f -> FilterChip(selected = metric == f.key, onClick = { metric = f.key }, label = { Text(f.label) }) }
        }
        Button(onClick = { ModuleRules.setLink(c, spec.key, field, target, metric) }) { Text("Save link") }
        val targets = all.filter { it.moduleKey == target }
        // reverse view: for each record here, the linked record's rollup; and per target, how many here point at it
        Text("Linked", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        entries.take(40).forEach { e ->
            val ref = e.value(field)
            val hits = targets.filter { it.title.equals(ref, true) }
            if (ref.isNotBlank()) Text("${e.title.ifBlank { "#${e.id}" }} → $ref ${if (hits.isEmpty()) "(not found)" else "· " + (if (metric.isBlank()) "${hits.size} record(s)" else "${targetSpec.fields.firstOrNull { it.key == metric }?.label}: ${hits.sumOf { it.value(metric).toDoubleOrNull() ?: 0.0 }}")}",
                style = MaterialTheme.typography.bodySmall)
        }
        Text("Roll-up by ${targetSpec.name}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
        targets.take(30).forEach { t ->
            val pointing = entries.filter { it.value(field).equals(t.title, true) }
            if (pointing.isNotEmpty()) Text("${t.title}: ${pointing.size} in ${spec.name}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RulesPane(c: Context, spec: ModuleSpec, entries: List<ModuleEntry>) {
    var text by remember(spec.key) { mutableStateOf(ModuleRules.rules(c, spec.key).joinToString("\n")) }
    Text("Rules, one per line, using field keys: ${spec.fields.joinToString { it.key }}", style = MaterialTheme.typography.bodySmall)
    Text("Examples: end >= start · spent <= budget · rating > 0", style = MaterialTheme.typography.labelSmall)
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 3)
    Button(onClick = { ModuleRules.setRules(c, spec.key, text.lines()) }) { Text("Save rules") }
    val rules = ModuleRules.rules(c, spec.key)
    val bad = entries.mapNotNull { e -> ModuleRules.violations(spec, e, rules).takeIf { it.isNotEmpty() }?.let { e to it } }
    Text(if (rules.isEmpty()) "No rules yet." else if (bad.isEmpty()) "All ${entries.size} records follow the rules. ✓" else "${bad.size} records break a rule",
        style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
    bad.take(40).forEach { (e, v) -> Text("⚠ ${e.title.ifBlank { "#${e.id}" }}: ${v.joinToString("; ")}", style = MaterialTheme.typography.bodySmall) }
}

object ModuleViewsSpecs {
    val all: List<com.nizam.app.feature.nx.Spec> = listOf(
        com.nizam.app.feature.nx.ScreenSpec("moduleviews", "Module views & rules", "Boards, calendar and gallery for your modules; workflows, links, roll-ups and rules",
            androidx.compose.material.icons.Icons.Outlined.ViewKanban, com.nizam.app.ui.design.Hub.Tools,
            "module kanban board calendar gallery workflow stages link rollup validation rules custom tracker") { c, _ -> ModuleViewsScreen(c) }
    )
}
