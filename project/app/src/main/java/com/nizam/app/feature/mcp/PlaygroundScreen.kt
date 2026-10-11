package com.nizam.app.feature.mcp

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Playground: connect any MCP server, see what it offers, and use it — by hand or by asking Atlas.
 */
@Composable
fun PlaygroundScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var servers by remember { mutableStateOf(Mcp.servers(c)) }
    var tools by remember { mutableStateOf<List<McpTool>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var openTool by remember { mutableStateOf<McpTool?>(null) }
    var args by remember { mutableStateOf("{}") }
    var ask by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    var parts by remember { mutableStateOf<List<McpPart>>(emptyList()) }
    // a real thread: each exchange is recorded, so follow-ups continue the same conversation
    val thread = remember { mutableStateListOf<Pair<Boolean, String>>() }
    var toolFilter by remember { mutableStateOf("") }

    fun refresh() = scope.launch {
        busy = true
        tools = servers.filter { it.enabled }.flatMap { s ->
            runCatching { Mcp.tools(s, refresh = true) }.getOrElse {
                Feedback.show("${s.name}: ${Feedback.friendly(it)}"); emptyList()
            }
        }
        busy = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Playground", "Connect any MCP server — its tools become Atlas's tools")
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Add a server", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("Server URL (https://…/mcp)") }, modifier = Modifier.fillMaxWidth())
                Text("Some servers take their key in the URL (…/mcp?apikey=KEY) rather than as a token.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(token, { token = it }, singleLine = true, label = { Text("Bearer token (if the server needs one)") },
                    modifier = Modifier.fillMaxWidth())
                Button(enabled = url.startsWith("http") && !busy, onClick = {
                    scope.launch {
                        busy = true
                        val s = McpServer(name.ifBlank { url.substringAfter("//").substringBefore("/") }, url.trim(), token.trim(), true)
                        runCatching { Mcp.connect(s) }.onSuccess { info ->
                            servers = servers.filterNot { it.url == s.url } + s
                            Mcp.save(c, servers); name = ""; url = ""; token = ""
                            Feedback.show("Connected: $info")
                            refresh()
                        }.onFailure { Feedback.show("Couldn't connect: ${Feedback.friendly(it)}"); busy = false }
                    }
                }) { Text(if (busy) "Connecting…" else "Connect") }
                Text("Atlas speaks MCP over HTTP (JSON-RPC, streamable). Local servers on your Wi-Fi work too.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        servers.forEach { s ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium)
                        Text(s.url, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${tools.count { it.server == s.name }} tools", style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    Switch(s.enabled, { on ->
                        servers = servers.map { if (it.url == s.url) it.copy(enabled = on) else it }
                        Mcp.save(c, servers); refresh()
                    })
                    Text("chat", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable(role = Role.Button) {
                            val bots = com.nizam.app.feature.bots.Bots.all(c)
                            val existing = bots.firstOrNull { it.server == s.url }
                            if (existing == null) {
                                com.nizam.app.feature.bots.Bots.save(c, bots + com.nizam.app.feature.bots.Bot(
                                    System.currentTimeMillis(), s.name,
                                    "You work through the ${s.name} connection. Use its tools to do what they ask, " +
                                        "and report what you did with the actual results.",
                                    "🔌", System.currentTimeMillis(), s.url))
                                Feedback.show("Thread created for ${s.name} — find it under Atlas > Bots")
                            } else Feedback.show("${s.name} already has a thread in Atlas > Bots")
                        }.padding(8.dp))
                    Text("✕", color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable(role = Role.Button) {
                            servers = servers - s; Mcp.save(c, servers); refresh(); Feedback.show("Removed ${s.name}")
                        }.padding(8.dp))
                }
            }
        }
        if (servers.isNotEmpty()) OutlinedButton(onClick = { refresh() }) { Text(if (busy) "Loading tools…" else "Refresh tools") }

        if (tools.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 16.dp)) {
                Text("CHAT WITH YOUR TOOLS", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                if (thread.isNotEmpty()) Text("clear", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable(role = Role.Button) { thread.clear(); parts = emptyList() })
            }
            thread.takeLast(12).forEach { (mine, text) ->
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = if (mine) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                ) { Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(ask, { ask = it }, label = { Text("e.g. create an issue titled…") }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(enabled = ask.isNotBlank() && !busy, onClick = {
                    scope.launch {
                        busy = true
                        val message = ask.trim()
                        thread += true to message
                        ask = ""
                        parts = emptyList()
                        container.agentMemory.addTurn(true, message)     // the agent reads this history next turn
                        val reply = runCatching { container.agentLoop.run(message, useWeb = false, autoApprove = true).reply }
                            .getOrElse { "Failed: ${Feedback.friendly(it)}" }
                        container.agentMemory.addTurn(false, reply)
                        thread += false to reply
                        parts = Mcp.lastParts.filter { it.kind != "text" }
                        result = null
                        busy = false
                    }
                }) { Text(if (busy) "…" else "Send") }
            }

            if (parts.isNotEmpty()) Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("RESULT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    parts.forEach { part ->
                        when (part.kind) {
                            "text" -> Text(part.text, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(vertical = 4.dp))
                            "image" -> part.file?.let { f ->
                                val bmp = remember(f.path) {
                                    runCatching { android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()
                                }
                                if (bmp != null) androidx.compose.foundation.Image(bmp, "result image",
                                    contentScale = ContentScale.FillWidth,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                                else Text("(image couldn't be decoded: ${part.mime})", style = MaterialTheme.typography.labelMedium)
                            }
                            "audio" -> part.file?.let { f ->
                                Text("▸ Play audio (${part.mime})", color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable(role = Role.Button) {
                                        runCatching {
                                            com.nizam.app.ui.design.QuickAudio.play(f.path)
                                            Feedback.show("Playing")
                                        }.onFailure { Feedback.show("Couldn't play that audio") }
                                    }.padding(vertical = 8.dp))
                            }
                            "file" -> part.file?.let { f ->
                                Text("${f.name} · ${f.length() / 1024} KB — open", color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.clickable(role = Role.Button) {
                                        runCatching {
                                            val uri = androidx.core.content.FileProvider.getUriForFile(
                                                c, "${c.packageName}.fileprovider", f)
                                            c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW)
                                                .setDataAndType(uri, part.mime.ifBlank { "*/*" })
                                                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                                        }.onFailure { Feedback.show("Nothing on this phone opens ${part.mime}") }
                                    }.padding(vertical = 8.dp))
                            }
                            else -> Text("↗ ${part.uri}", color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable(role = Role.Button) {
                                    runCatching { c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse(part.uri))) }
                                }.padding(vertical = 6.dp))
                        }
                    }
                    Text("copy text", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable(role = Role.Button) {
                            (c.getSystemService(android.content.ClipboardManager::class.java))?.setPrimaryClip(
                                android.content.ClipData.newPlainText("Atlas", parts.filter { it.kind == "text" }.joinToString("\n") { it.text }))
                            Feedback.show("Copied")
                        }.padding(top = 8.dp))
                }
            }
            result?.let {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("RESULT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                        Text("copy", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clickable(role = Role.Button) {
                                (c.getSystemService(android.content.ClipboardManager::class.java))
                                    ?.setPrimaryClip(android.content.ClipData.newPlainText("Atlas", it))
                                Feedback.show("Copied")
                            }.padding(top = 8.dp))
                    }
                }
            }

            Text("TOOLS · ${tools.size}", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 16.dp))
            OutlinedTextField(toolFilter, { toolFilter = it }, singleLine = true, label = { Text("Search tools") },
                modifier = Modifier.fillMaxWidth())
            tools.filter { toolFilter.isBlank() || it.name.contains(toolFilter, true) || it.description.contains(toolFilter, true) }
                .take(40).forEach { t ->
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(role = Role.Button) {
                        openTool = if (openTool?.name == t.name) null else t
                        args = t.schema?.optJSONObject("properties")?.let { props ->
                            JSONObject().apply { props.keys().forEach { k -> put(k, "") } }.toString(2)
                        } ?: "{}"
                    }) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${t.name}  ·  ${t.server}", style = MaterialTheme.typography.titleMedium)
                        if (t.description.isNotBlank()) Text(t.description, style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
                        if (openTool?.name == t.name) {
                            OutlinedTextField(args, { args = it }, label = { Text("Arguments (JSON)") },
                                modifier = Modifier.fillMaxWidth().height(150.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(enabled = !busy, onClick = {
                                    scope.launch {
                                        busy = true
                                        runCatching { Mcp.findAndCallRich(c, t.name, JSONObject(args)) }
                                            .onSuccess { parts = it; result = null }
                                            .onFailure { parts = emptyList(); result = "Failed: ${Feedback.friendly(it)}" }
                                        busy = false
                                    }
                                }) { Text("Run tool") }
                                OutlinedButton(enabled = !busy, onClick = {
                                    scope.launch {
                                        busy = true
                                        result = runCatching {
                                            val filled = Ai.generate(container.settingsRepository,
                                                "Tool: ${t.name}\nDescription: ${t.description}\nJSON schema: ${t.schema}\n" +
                                                    "User wants: ${ask.ifBlank { "a sensible example call" }}\n" +
                                                    "Return ONLY the arguments JSON object.", maxOutputTokens = 500)
                                            args = JSONObject(Ai.cleanJson(filled)).toString(2)
                                            "Arguments filled in — check them, then Run tool."
                                        }.getOrElse { "Failed: ${Feedback.friendly(it)}" }
                                        busy = false
                                    }
                                }) { Text("✦ Fill arguments") }
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(60.dp))
    }
}
