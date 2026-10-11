package com.nizam.app.feature.nx

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.dynamic.ModuleSpec
import com.nizam.app.feature.mcp.Mcp
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Turns what a connected MCP tool returns (a JSON list) into records in one of your modules.
 * Only read-style tools are allowed; the mapping is saved so the same import can be re-run in one tap.
 */
object McpMapper {
    private fun p(c: Context) = c.getSharedPreferences("mcpmap", 0)
    fun saved(c: Context): List<JSONObject> = runCatching { JSONArray(p(c).getString("maps", "[]")) }.getOrElse { JSONArray() }
        .let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    fun save(c: Context, m: JSONObject) {
        val l = saved(c).filter { it.optString("name") != m.optString("name") } + m
        p(c).edit().putString("maps", JSONArray(l).toString()).apply()
    }
    fun delete(c: Context, name: String) = p(c).edit().putString("maps", JSONArray(saved(c).filter { it.optString("name") != name }).toString()).apply()

    /** The first JSON array found in the tool's text output (top level, or the first array-valued key). */
    fun rows(text: String): List<JSONObject> {
        val t = text.trim()
        val arr: JSONArray? = runCatching { JSONArray(t) }.getOrNull()
            ?: runCatching { JSONObject(t).let { o -> o.keys().asSequence().mapNotNull { o.optJSONArray(it) }.firstOrNull() } }.getOrNull()
            ?: Regex("\\[[\\s\\S]*]").find(t)?.value?.let { runCatching { JSONArray(it) }.getOrNull() }
        return arr?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()
    }

    fun flatKeys(rows: List<JSONObject>) = rows.take(20).flatMap { it.keys().asSequence().toList() }.distinct()

    /** Field key ← JSON key, guessed by matching names. */
    fun guess(spec: ModuleSpec, keys: List<String>): Map<String, String> {
        fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
        return spec.fields.mapNotNull { f -> keys.firstOrNull { norm(it) == norm(f.key) || norm(it) == norm(f.label) }
            ?.let { f.key to it } }.toMap()
    }

    suspend fun call(c: Context, tool: String, args: JSONObject): String {
        require(!Mcp.writes(tool)) { "\"$tool\" looks like it changes something. Only read tools can feed a module." }
        return Mcp.findAndCallRich(c, tool, args).filter { it.kind == "text" }.joinToString("\n") { it.text }
    }

    /** Imports rows, skipping titles already in the module. Returns how many were added. */
    suspend fun run(container: AppContainer, m: JSONObject): Int {
        val c = container.appContext; val repo = container.dynamicRepository
        val rows = rows(call(c, m.getString("tool"), JSONObject(m.optString("args", "{}"))))
        val map = m.getJSONObject("map"); val titleKey = m.optString("title")
        val have = repo.entriesForMigration(m.getString("module")).map { it.title.trim().lowercase() }.toSet()
        val recs = rows.mapNotNull { r ->
            val title = r.opt(titleKey)?.toString()?.trim().orEmpty()
            if (title.isBlank() || title.lowercase() in have) null
            else title.take(200) to map.keys().asSequence().associateWith { k -> r.opt(map.getString(k))?.toString().orEmpty().take(2000) }
        }.distinctBy { it.first.lowercase() }.take(500)
        if (recs.isNotEmpty()) repo.addEntries(m.getString("module"), recs)
        return recs.size
    }
}

object Mega8 {
    val all: List<Spec> by lazy { listOf(
        ScreenSpec("mcpmap", "Connector → module", "Pull a list from a connected MCP tool straight into one of your modules", Icons.Outlined.Input, Hub.Tools,
            "mcp connector import module map fields sync tool records") { c, open -> McpMapScreen(c, open) },
    ) }
}

@Composable
private fun McpMapScreen(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var tools by remember { mutableStateOf(emptyList<String>()) }
    var specs by remember { mutableStateOf(emptyList<ModuleSpec>()) }
    var tool by remember { mutableStateOf("") }
    var args by remember { mutableStateOf("{}") }
    var rows by remember { mutableStateOf(emptyList<JSONObject>()) }
    var module by remember { mutableStateOf<ModuleSpec?>(null) }
    var map by remember { mutableStateOf(emptyMap<String, String>()) }
    var titleKey by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(McpMapper.saved(c)) }
    LaunchedEffect(Unit) {
        tools = runCatching { Mcp.allTools(c).map { it.name }.filter { !Mcp.writes(it) } }.getOrDefault(emptyList())
        specs = container.dynamicRepository.specs().first()
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (saved.isNotEmpty()) {
            Text("Saved imports", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            saved.forEach { m -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${m.optString("name")} · ${m.optString("tool")} → ${m.optString("module")}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { scope.launch { status = runCatching { "Added ${McpMapper.run(container, m)} new records" }.getOrElse { "Didn't work: ${it.message}" } } }) { Text("Run") }
                TextButton(onClick = { McpMapper.delete(c, m.optString("name")); saved = McpMapper.saved(c) }) { Text("✕") }
            } }
        }
        if (tools.isEmpty()) { Text("No read tools from connected MCP servers yet.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { open("playground") }) { Text("Connect a server") } }
        else {
            Text("1 · Tool", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            tools.take(24).chunked(2).forEach { r -> Row { r.forEach { t -> TextButton(onClick = { tool = t }) {
                Text(t, fontWeight = if (t == tool) FontWeight.Bold else FontWeight.Normal, maxLines = 1) } } } }
            OutlinedTextField(args, { args = it }, label = { Text("Arguments (JSON)") }, modifier = Modifier.fillMaxWidth())
            TextButton(enabled = tool.isNotBlank(), onClick = { scope.launch {
                status = "Calling $tool…"
                status = runCatching { rows = McpMapper.rows(McpMapper.call(c, tool, JSONObject(args.ifBlank { "{}" })))
                    if (rows.isEmpty()) "That tool didn't return a list." else "Got ${rows.size} rows with: ${McpMapper.flatKeys(rows).take(12).joinToString()}" }
                    .getOrElse { "Didn't work: ${it.message}" }
            } }) { Text("Preview") }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        if (rows.isNotEmpty()) {
            val keys = McpMapper.flatKeys(rows)
            Text("2 · Into module", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            specs.chunked(3).forEach { r -> Row { r.forEach { s -> TextButton(onClick = { module = s; map = McpMapper.guess(s, keys); titleKey = keys.firstOrNull { k -> k.lowercase() in setOf("title", "name", "subject", "summary") } ?: keys.first() }) {
                Text("${s.emoji} ${s.name}", fontWeight = if (module?.key == s.key) FontWeight.Bold else FontWeight.Normal, maxLines = 1) } } } }
            module?.let { s ->
                Text("3 · Match fields (tap to cycle)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Record name ←", Modifier.weight(1f))
                    TextButton(onClick = { titleKey = keys[(keys.indexOf(titleKey) + 1) % keys.size] }) { Text(titleKey) } }
                s.fields.forEach { f -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${f.label} ←", Modifier.weight(1f))
                    TextButton(onClick = { val cur = map[f.key]; val i = if (cur == null) 0 else keys.indexOf(cur) + 1
                        map = if (i >= keys.size) map - f.key else map + (f.key to keys[i]) }) { Text(map[f.key] ?: "—") }
                } }
                Text("Example: ${rows.first().opt(titleKey)}", style = MaterialTheme.typography.bodySmall)
                var name by remember(s.key, tool) { mutableStateOf("$tool → ${s.name}") }
                OutlinedTextField(name, { name = it }, label = { Text("Save as") }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                TextButton(onClick = { scope.launch {
                    val m = JSONObject().put("name", name).put("tool", tool).put("args", args.ifBlank { "{}" }).put("module", s.key).put("title", titleKey)
                        .put("map", JSONObject(map as Map<*, *>))
                    McpMapper.save(c, m); saved = McpMapper.saved(c)
                    status = runCatching { "Added ${McpMapper.run(container, m)} new records to ${s.name}" }.getOrElse { "Didn't work: ${it.message}" }
                } }) { Text("Import and save") }
            }
        }
    }
}
