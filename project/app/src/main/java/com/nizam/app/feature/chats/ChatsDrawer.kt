package com.nizam.app.feature.chats

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import kotlinx.coroutines.launch
import org.json.JSONObject

private fun ago(t: Long): String {
    val m = (System.currentTimeMillis() - t) / 60_000
    return when { m < 1 -> "now"; m < 60 -> "${m}m"; m < 1440 -> "${m / 60}h"; m < 10080 -> "${m / 1440}d"; else -> "${m / 10080}w" }
}

/**
 * Every conversation, Claude-style: search, Today / Yesterday / earlier, starred on top, labels, projects,
 * the Code shelf and the archive. Long-press a chat to rename, star, label, move, archive or delete it.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ChatsDrawer(container: AppContainer, onNewChat: (projectId: String?) -> Unit, onOpened: () -> Unit) {
    val c = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val tick by ChatHistory.tick.collectAsState()
    var q by remember { mutableStateOf("") }
    var tab by remember { mutableStateOf("chats") }
    var who by remember { mutableStateOf("All") }
    var label by remember { mutableStateOf<String?>(null) }
    var openProject by remember { mutableStateOf<String?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<JSONObject?>(null) }
    var moving by remember { mutableStateOf<JSONObject?>(null) }
    var labelling by remember { mutableStateOf<JSONObject?>(null) }
    var deleting by remember { mutableStateOf<JSONObject?>(null) }
    var editingProject by remember { mutableStateOf<JSONObject?>(null) }
    var creatingProject by remember { mutableStateOf(false) }
    val all = remember(tick) { ChatHistory.all(c).sortedByDescending { it.optLong("updated") } }
    val projects = remember(tick) { ChatHistory.projectList(c) }
    val current = remember(tick) { ChatHistory.currentId(c) }

    fun open(o: JSONObject) {
        if (o.optString("source") == "atlas") scope.launch { ChatHistory.openAtlas(container, o.optString("id")); onOpened() }
        else if (o.optString("source").startsWith("code:")) { com.nizam.app.feature.code.AtlasCode.pending = o.optString("source").removePrefix("code:"); onOpened(); com.nizam.app.ui.screens.search.AtlasNav.go?.invoke(com.nizam.app.feature.nx.Nx.route("atlascode")) }
        else ChatHistory.openTool(c, o.optString("id"))?.let { r -> onOpened(); com.nizam.app.ui.screens.search.AtlasNav.go?.invoke(r) }
    }

    @Composable
    fun ChatRow(o: JSONObject) {
        val id = o.optString("id")
        val isCurrent = id == current
        Box {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (isCurrent) cs.primary.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent)
                .combinedClickable(onClick = { open(o) }, onLongClick = { menuFor = id }).padding(horizontal = 10.dp, vertical = 10.dp)) {
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                    if (o.optBoolean("starred")) Text("⭐ ", style = MaterialTheme.typography.labelSmall)
                    Text(o.optString("title").ifBlank { "Chat" }, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text(ago(o.optLong("updated")), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                }
                androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Text(ChatHistory.emoji(o.optString("label")) + " " + o.optString("label", "Chat"), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    val agent = o.optString("agent")
                    if (agent.isNotBlank()) Text("  ·  $agent", style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace), color = cs.primary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val pid = o.optString("project")
                    if (pid.isNotBlank()) projects.firstOrNull { it.optString("id") == pid }?.let {
                        Text("  ·  ${it.optString("emoji")} ${it.optString("name")}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                ChatHistory.lastLine(o).takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
            }
            DropdownMenu(expanded = menuFor == id, onDismissRequest = { menuFor = null }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menuFor = null; renaming = o })
                DropdownMenuItem(text = { Text(if (o.optBoolean("starred")) "Unstar" else "Star") }, onClick = { menuFor = null; ChatHistory.star(c, id, !o.optBoolean("starred")) })
                DropdownMenuItem(text = { Text("Change label") }, onClick = { menuFor = null; labelling = o })
                DropdownMenuItem(text = { Text("Move to project") }, onClick = { menuFor = null; moving = o })
                DropdownMenuItem(text = { Text(if (o.optBoolean("archived")) "Restore from archive" else "Archive") }, onClick = { menuFor = null; ChatHistory.archive(c, id, !o.optBoolean("archived")) })
                DropdownMenuItem(text = { Text("Delete", color = cs.error) }, onClick = { menuFor = null; deleting = o })
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 16.dp)) {
        // ── header (LYCHO style) ──
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("HISTORY", style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 3.sp), color = cs.primary, fontWeight = FontWeight.Bold)
                Text("Chats", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
            }
            Box(Modifier.size(52.dp).clip(CircleShape).background(cs.primary).clickable { onNewChat(if (tab == "projects") openProject else null) }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Edit, "New chat", tint = cs.onPrimary)
            }
        }
        OutlinedTextField(q, { q = it }, placeholder = { Text("Search all chats") }, leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true,
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(top = 14.dp))
        // ── sections ──
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("chats" to "Chats", "projects" to "Projects", "code" to "💻 Code", "archived" to "Archived").forEach { (k, l) ->
                val on = tab == k
                Text(l, style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium,
                    color = if (on) cs.onPrimary else cs.onSurface,
                    modifier = Modifier.clip(CircleShape).background(if (on) cs.primary else cs.surfaceVariant.copy(alpha = 0.6f))
                        .clickable { tab = k; openProject = null }.padding(horizontal = 14.dp, vertical = 8.dp))
            }
        }

        val visible = all.filter { ChatHistory.matches(it, q) }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when (tab) {
                "chats" -> {
                    item("filters") {
                        val labels = all.filter { !it.optBoolean("archived") }.map { it.optString("label", "Chat") }.distinct()
                        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            (listOf("All", "Atlas", "Agents", "⭐").map { it to (label == null && who == it) } + labels.map { "${ChatHistory.emoji(it)} $it" to (label == it) }).forEach { (t, on) ->
                                Text(t, style = MaterialTheme.typography.labelMedium, color = if (on) cs.primary else cs.onSurfaceVariant,
                                    modifier = Modifier.clip(CircleShape).border(1.dp, if (on) cs.primary else cs.outlineVariant, CircleShape)
                                        .clickable { if (t in setOf("All", "Atlas", "Agents", "⭐")) { who = t; label = null } else { label = t.substringAfter(' '); who = "All" } }
                                        .padding(horizontal = 10.dp, vertical = 5.dp))
                            }
                        }
                    }
                    ChatHistory.suggestion(c)?.let { (word, ids) ->
                        item("suggest") {
                            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(cs.primary.copy(alpha = 0.08f)).padding(12.dp)) {
                                Text("💡 ${ids.size} chats are about “$word”", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                Text("Gather them into a project so Atlas keeps the shared context.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                androidx.compose.foundation.layout.Row {
                                    TextButton(onClick = { val p = ChatHistory.newProject(c, word, "📁"); ids.forEach { ChatHistory.move(c, it, p) }; tab = "projects"; openProject = p }) { Text("Make project") }
                                    TextButton(onClick = { ChatHistory.dismissSuggestion(c, word) }) { Text("Not now") }
                                }
                            }
                        }
                    }
                    val list = visible.filter { !it.optBoolean("archived") }.filter { o ->
                        val agent = o.optString("agent").isNotBlank() || o.optString("source") != "atlas"
                        when { label != null -> o.optString("label", "Chat") == label; who == "Atlas" -> !agent; who == "Agents" -> agent; who == "⭐" -> o.optBoolean("starred"); else -> true }
                    }
                    if (list.isEmpty()) item("empty") { Empty(if (q.isBlank()) "No chats yet. Everything you talk about with Atlas and its agents will gather here." else "Nothing matches “$q”.") }
                    val starred = list.filter { it.optBoolean("starred") }
                    if (starred.isNotEmpty() && who != "⭐") { item("h-star") { Header("Starred") }; starred.forEach { o -> item("s" + o.optString("id")) { ChatRow(o) } } }
                    list.filterNot { it.optBoolean("starred") && who != "⭐" }.groupBy { ChatHistory.bucket(it.optLong("updated")) }.forEach { (b, items) ->
                        item("h-$b") { Header(b) }
                        items.forEach { o -> item(o.optString("id")) { ChatRow(o) } }
                    }
                }
                "projects" -> {
                    val pid = openProject
                    val p = pid?.let { id -> projects.firstOrNull { it.optString("id") == id } }
                    if (p == null) {
                        item("newp") { TextButton(onClick = { creatingProject = true }) { Text("+ New project") } }
                        if (projects.isEmpty()) item("pe") { Empty("Projects keep related chats together, with instructions and notes every chat in them knows about. Long-press a chat → Move to project, or tap + New project.") }
                        projects.forEach { pr -> item(pr.optString("id")) {
                            val n = all.count { it.optString("project") == pr.optString("id") }
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(16.dp)).background(cs.surfaceVariant.copy(alpha = 0.5f))
                                .clickable { openProject = pr.optString("id") }.padding(14.dp)) {
                                Text("${pr.optString("emoji")} ${pr.optString("name")}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("$n chat${if (n == 1) "" else "s"}" + (pr.optString("instructions").takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        } }
                    } else {
                        item("ph") {
                            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                                TextButton(onClick = { openProject = null }) { Text("‹ All projects") }
                                Text("${p.optString("emoji")} ${p.optString("name")}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                                Text(p.optString("instructions").ifBlank { "No instructions yet: tell Atlas what this project is about." }, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                                if (p.optString("notes").isNotBlank()) Text("📌 " + p.optString("notes").take(200), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                                androidx.compose.foundation.layout.Row {
                                    TextButton(onClick = { onNewChat(p.optString("id")) }) { Text("New chat here") }
                                    TextButton(onClick = { editingProject = p }) { Text("Edit") }
                                }
                            }
                        }
                        val inP = visible.filter { it.optString("project") == p.optString("id") }
                        if (inP.isEmpty()) item("pe2") { Empty("No chats in this project yet.") }
                        inP.forEach { o -> item(o.optString("id")) { ChatRow(o) } }
                    }
                }
                "code" -> {
                    item("codehead") {
                        Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(18.dp)).background(cs.primary.copy(alpha = 0.10f)).padding(14.dp)) {
                            Text("✳ Atlas Code", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text("Your coding agent: any GitHub repo, edits with diffs, a real Linux terminal, commits, pull requests and CI. Coding sessions and code chats gather here.",
                                style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                            androidx.compose.foundation.layout.Row {
                                TextButton(onClick = { onOpened(); com.nizam.app.ui.screens.search.AtlasNav.go?.invoke(com.nizam.app.feature.nx.Nx.route("atlascode")) }) { Text("Open Atlas Code") }
                                TextButton(onClick = { onOpened(); com.nizam.app.ui.screens.search.AtlasNav.go?.invoke(com.nizam.app.feature.nx.Nx.route("terminal")) }) { Text("Terminal") }
                            }
                        }
                    }
                    val code = visible.filter { it.optString("label") == "Code" && !it.optBoolean("archived") }
                    if (code.isEmpty()) item("ce") { Empty("Chats about code are labelled 💻 and gather here.") }
                    code.forEach { o -> item(o.optString("id")) { ChatRow(o) } }
                }
                else -> {
                    val arch = visible.filter { it.optBoolean("archived") }
                    if (arch.isEmpty()) item("ae") { Empty("Archived chats stay searchable here. Long-press a chat → Archive.") }
                    arch.forEach { o -> item(o.optString("id")) { ChatRow(o) } }
                }
            }
            item("end") { Spacer(Modifier.height(40.dp)) }
        }
    }

    // ── dialogs ──
    renaming?.let { o ->
        var t by remember(o) { mutableStateOf(o.optString("title")) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename chat") },
            text = { OutlinedTextField(t, { t = it }, singleLine = true) },
            confirmButton = { TextButton(enabled = t.isNotBlank(), onClick = { ChatHistory.rename(c, o.optString("id"), t); renaming = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
    labelling?.let { o ->
        AlertDialog(onDismissRequest = { labelling = null }, title = { Text("Label") }, text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                (ChatHistory.LABELS.map { it.key } + "Chat").forEach { l ->
                    Text("${ChatHistory.emoji(l)}  $l", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth()
                        .clickable { ChatHistory.setLabel(c, o.optString("id"), l); labelling = null }.padding(vertical = 10.dp))
                }
            }
        }, confirmButton = { TextButton(onClick = { labelling = null }) { Text("Close") } })
    }
    moving?.let { o ->
        AlertDialog(onDismissRequest = { moving = null }, title = { Text("Move to project") }, text = {
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                Text("No project", modifier = Modifier.fillMaxWidth().clickable { ChatHistory.move(c, o.optString("id"), null); moving = null }.padding(vertical = 10.dp))
                projects.forEach { p -> Text("${p.optString("emoji")}  ${p.optString("name")}", style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.fillMaxWidth().clickable { ChatHistory.move(c, o.optString("id"), p.optString("id")); moving = null }.padding(vertical = 10.dp)) }
                Text("+ New project…", color = cs.primary, modifier = Modifier.fillMaxWidth().clickable { moving = null; creatingProject = true }.padding(vertical = 10.dp))
            }
        }, confirmButton = { TextButton(onClick = { moving = null }) { Text("Close") } })
    }
    deleting?.let { o ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Delete this chat?") }, text = { Text("“${o.optString("title")}” will be gone for good. Archive keeps it out of the way instead.") },
            confirmButton = { TextButton(onClick = { ChatHistory.delete(c, o.optString("id")); deleting = null }) { Text("Delete", color = cs.error) } },
            dismissButton = { TextButton(onClick = { ChatHistory.archive(c, o.optString("id"), true); deleting = null }) { Text("Archive instead") } })
    }
    if (creatingProject || editingProject != null) {
        val e = editingProject
        var name by remember(e) { mutableStateOf(e?.optString("name").orEmpty()) }
        var emoji by remember(e) { mutableStateOf(e?.optString("emoji")?.ifBlank { null } ?: "📁") }
        var ins by remember(e) { mutableStateOf(e?.optString("instructions").orEmpty()) }
        var notes by remember(e) { mutableStateOf(e?.optString("notes").orEmpty()) }
        AlertDialog(onDismissRequest = { creatingProject = false; editingProject = null }, title = { Text(if (e == null) "New project" else "Edit project") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(emoji, { emoji = it.take(2) }, singleLine = true, modifier = Modifier.width(64.dp))
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                OutlinedTextField(ins, { ins = it }, label = { Text("Instructions for Atlas") }, placeholder = { Text("e.g. I'm launching a gold trading business in Lahore, budget PKR 340k") }, minLines = 3)
                OutlinedTextField(notes, { notes = it }, label = { Text("Pinned notes (optional)") }, minLines = 2)
                if (e != null) TextButton(onClick = { ChatHistory.deleteProject(c, e.optString("id")); editingProject = null; openProject = null }) { Text("Delete project (chats are kept)", color = cs.error) }
            }
        }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            if (e == null) { val id = ChatHistory.newProject(c, name, emoji, ins); if (notes.isNotBlank()) ChatHistory.editProject(c, id, name, emoji, ins, notes); openProject = id; tab = "projects" }
            else ChatHistory.editProject(c, e.optString("id"), name, emoji, ins, notes)
            creatingProject = false; editingProject = null }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { creatingProject = false; editingProject = null }) { Text("Cancel") } })
    }
}

@Composable
private fun Header(t: String) = Text(t.uppercase(), style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), fontWeight = FontWeight.Bold,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp, top = 14.dp, bottom = 4.dp))

@Composable
private fun Empty(t: String) = Text(t, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 10.dp, vertical = 24.dp))
