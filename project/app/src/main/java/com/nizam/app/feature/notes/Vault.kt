package com.nizam.app.feature.notes

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarToday
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nizam.app.AppContainer
import com.nizam.app.core.vmFactory
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.theme.DisplayFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt

/**
 * Notes, built like Obsidian: a vault of Markdown notes in folders ("Folder/Sub/Note" titles), a
 * live editor with a formatting bar and [[link]] autocomplete, reading view, backlinks and unlinked
 * mentions, an outline, tags, properties, a graph of how everything connects (whole vault or local),
 * daily notes, templates, starred notes, a quick switcher, full-text search, and .md import/export.
 */
object VaultNav { @Volatile var open: String? = null }          // lets search, the agent and links open a note by title

@Composable
fun NotesScreen(container: AppContainer) {
    val vm: NotesViewModel = viewModel(factory = vmFactory { NotesViewModel(container.noteRepository) })
    val notes by vm.notes.collectAsState()
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val c = LocalContext.current
    var openId by remember { mutableStateOf<Long?>(null) }
    var view by remember { mutableStateOf("Files") }
    var tagFilter by remember { mutableStateOf<String?>(null) }
    var switcher by remember { mutableStateOf(false) }

    fun openTitle(title: String) = scope.launch { openId = vm.ensure(title) }
    fun daily() = scope.launch {
        val d = LocalDate.now().toString()
        val title = "Daily/$d"
        val tpl = notes.firstOrNull { it.title.equals("Templates/Daily", true) }?.body
        openId = vm.ensure(title, fill(tpl ?: "# ${LocalDate.now().format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy"))}\n\n## Tasks\n- [ ] \n\n## Notes\n\n", d))
    }
    LaunchedEffect(notes.isNotEmpty()) { com.nizam.app.ui.design.guard { VaultNav.open?.let { VaultNav.open = null; openTitle(it) } } }

    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch { runCatching {
            c.contentResolver.openOutputStream(uri)?.use { os -> java.util.zip.ZipOutputStream(os).use { zip ->
                notes.forEach { n -> zip.putNextEntry(java.util.zip.ZipEntry(n.title.replace(Regex("[\\\\:*?\"<>|]"), "_") + ".md")); zip.write(n.body.toByteArray()); zip.closeEntry() }
            } }
        }.onSuccess { Feedback.show("Vault exported — ${notes.size} notes as Markdown") }.onFailure { Feedback.failed("Exporting", it) } }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch {
            var n = 0
            uris.forEach { u -> runCatching {
                val name = com.nizam.app.core.displayNameFor(c, u).removeSuffix(".md").removeSuffix(".markdown").removeSuffix(".txt")
                val text = c.contentResolver.openInputStream(u)?.bufferedReader()?.readText().orEmpty()
                vm.ensure(name, text); n++
            } }
            Feedback.show("Imported $n notes")
        }
    }

    val open = notes.firstOrNull { it.id == openId }
    AnimatedContent(open, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "vault") { note ->
        if (note != null) Editor(note, notes, vm, onBack = { openId = null }, onOpen = { openTitle(it) },
            onTag = { tagFilter = it; view = "Tags"; openId = null })
        else Column(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Vault", style = MaterialTheme.typography.headlineMedium.copy(fontFamily = DisplayFamily))
                    Text("${notes.size} notes · ${notes.sumOf { Md.WIKI.findAll(it.body).count() }} links", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { switcher = true }) { Icon(Icons.Outlined.Search, "Quick switcher") }
                IconButton(onClick = { daily() }) { Icon(Icons.Outlined.CalendarToday, "Today's daily note") }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("New template") }, { menu = false; openTitle("Templates/Untitled template") })
                        DropdownMenuItem({ Text("Import .md files") }, { menu = false; import.launch(arrayOf("text/*", "application/octet-stream")) })
                        DropdownMenuItem({ Text("Export vault (.zip)") }, { menu = false; export.launch("Atlas vault ${LocalDate.now()}.zip") })
                    }
                }
            }
            com.nizam.app.core.ChipRow(listOf("Files", "Starred", "Tags", "Graph"), view, { view = it; if (it != "Tags") tagFilter = null })
            when (view) {
                "Files" -> FileTree(notes, { openId = it.id }, vm)
                "Starred" -> NoteList(notes.filter { it.pinned }, "Star notes from their menu to keep them here.") { openId = it.id }
                "Tags" -> TagsPane(notes, tagFilter, { tagFilter = it }) { openId = it.id }
                else -> Graph(notes, null, Modifier.fillMaxSize()) { openTitle(it) }
            }
        }
    }
    if (open == null) Box(Modifier.fillMaxSize()) {
        androidx.compose.material3.FloatingActionButton(onClick = { scope.launch {
            var n = 1; while (notes.any { it.title.equals("Untitled${if (n > 1) " $n" else ""}", true) }) n++
            openId = vm.ensure("Untitled${if (n > 1) " $n" else ""}")
        } }, modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp)) { Icon(Icons.Outlined.Add, "New note") }
    }
    if (switcher) QuickSwitcher(notes, { switcher = false }) { switcher = false; openTitle(it) }
}

/** {{date}}, {{time}} and {{title}} in templates. */
private fun fill(tpl: String, title: String) = tpl.replace("{{date}}", LocalDate.now().toString())
    .replace("{{time}}", LocalTime.now().withSecond(0).withNano(0).toString()).replace("{{title}}", title.substringAfterLast('/'))

// ── file explorer ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun FileTree(notes: List<Note>, onOpen: (Note) -> Unit, vm: NotesViewModel) {
    var collapsed by remember { mutableStateOf(setOf<String>()) }
    val sorted = notes.sortedBy { it.title.lowercase() }
    val folders = sorted.flatMap { n -> n.title.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" } }.distinct().sorted()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
        if (notes.isEmpty()) Text("An empty vault. Tap + for a note, or the calendar for today's daily note. Link notes with [[double brackets]] — " +
            "use Folder/Note as a title to file it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 12.dp))
        fun visible(path: String) = path.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" }.none { it in collapsed }
        val rows = (folders.map { it to null } + sorted.map { it.title to it }).sortedWith(compareBy({ it.first.substringBeforeLast('/', "").lowercase() },
            { if (it.second == null) 0 else 1 }, { it.first.lowercase() }))
        // folders before notes at each level, then alphabetical
        val ordered = mutableListOf<Pair<String, Note?>>()
        fun addLevel(parent: String) {
            rows.filter { it.first.substringBeforeLast('/', "") == parent && it.second == null }.forEach { f -> ordered += f; addLevel(f.first) }
            rows.filter { it.first.substringBeforeLast('/', "") == parent && it.second != null }.forEach { ordered += it }
        }
        addLevel("")
        ordered.filter { visible(it.first) }.forEach { (path, note) ->
            val depth = path.count { it == '/' }
            if (note == null) Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                collapsed = if (path in collapsed) collapsed - path else collapsed + path }.padding(start = (depth * 16).dp, top = 9.dp, bottom = 9.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(if (path in collapsed) Icons.Outlined.ChevronRight else Icons.Outlined.ExpandMore, null, Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Outlined.Folder, null, Modifier.padding(horizontal = 6.dp).size(18.dp), tint = MaterialTheme.colorScheme.primary)
                Text(path.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge)
            } else NoteRow(note, depth, onOpen, vm)
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun NoteRow(note: Note, depth: Int, onOpen: (Note) -> Unit, vm: NotesViewModel) {
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf<String?>(null) }
    Box {
        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(note) }.padding(start = (depth * 16 + 24).dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Description, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(note.title.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 8.dp))
            if (note.pinned) Icon(Icons.Outlined.Star, "Starred", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            IconButton(onClick = { menu = true }, modifier = Modifier.size(36.dp)) { Icon(Icons.Outlined.MoreVert, "Note menu", Modifier.size(18.dp)) }
        }
        DropdownMenu(menu, { menu = false }) {
            DropdownMenuItem({ Text(if (note.pinned) "Unstar" else "Star") }, { menu = false; vm.save(note.copy(pinned = !note.pinned)) })
            DropdownMenuItem({ Text("Rename or move") }, { menu = false; rename = note.title })
            DropdownMenuItem({ Text("Delete", color = MaterialTheme.colorScheme.error) }, { menu = false; vm.delete(note); Feedback.show("Deleted ${note.title}") })
        }
    }
    rename?.let { r ->
        androidx.compose.material3.AlertDialog(onDismissRequest = { rename = null },
            title = { Text("Rename or move") },
            text = { Column {
                OutlinedTextField(r, { rename = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Use Folder/Name to move it. Links to it are updated everywhere.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            } },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { vm.rename(note, r); rename = null; Feedback.show("Renamed — links updated") }) { Text("Save") } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { rename = null }) { Text("Cancel") } })
    }
}

@Composable
private fun NoteList(list: List<Note>, empty: String, onOpen: (Note) -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
        if (list.isEmpty()) Text(empty, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
        list.forEach { n ->
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(n) }.padding(vertical = 10.dp)) {
                Text(n.title.substringAfterLast('/'), style = MaterialTheme.typography.bodyLarge)
                Text(listOfNotNull(n.title.substringBeforeLast('/', "").ifBlank { null }, Md.frontmatter(n.body).second.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.take(80))
                    .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun TagsPane(notes: List<Note>, selected: String?, onSelect: (String?) -> Unit, onOpen: (Note) -> Unit) {
    val counts = notes.flatMap { Md.tags(it.body) }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 8.dp)) {
        if (counts.isEmpty()) Text("No tags yet — write #anything in a note.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            counts.forEach { (t, n) -> Text("#$t  $n", style = MaterialTheme.typography.labelMedium,
                color = if (t == selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (t == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                    .clickable { onSelect(if (t == selected) null else t) }.padding(horizontal = 12.dp, vertical = 6.dp)) }
        }
        selected?.let { t -> NoteList(notes.filter { t in Md.tags(it.body) || Md.tags(it.body).any { x -> x.startsWith("$t/") } }, "", onOpen) }
    }
}

// ── quick switcher (and full-text search) ───────────────────────────────────────────────────

@Composable
private fun QuickSwitcher(notes: List<Note>, onClose: () -> Unit, onOpen: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surface).padding(14.dp)) {
            OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Find or create a note…") }, modifier = Modifier.fillMaxWidth())
            val t = q.trim()
            val byTitle = notes.filter { t.isBlank() || it.title.contains(t, true) }.sortedBy { if (it.title.startsWith(t, true)) 0 else 1 }.take(8)
            val byText = if (t.length < 2) emptyList() else notes.filter { it !in byTitle && it.body.contains(t, true) }.take(6)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
                byTitle.forEach { n -> Text(n.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.fillMaxWidth().clickable { onOpen(n.title) }.padding(vertical = 9.dp)) }
                if (byText.isNotEmpty()) Label("In note text", Modifier.padding(top = 8.dp))
                byText.forEach { n ->
                    val at = n.body.indexOf(t, ignoreCase = true)
                    Column(Modifier.fillMaxWidth().clickable { onOpen(n.title) }.padding(vertical = 8.dp)) {
                        Text(n.title, style = MaterialTheme.typography.bodyLarge)
                        Text("…" + n.body.substring((at - 30).coerceAtLeast(0), (at + t.length + 50).coerceAtMost(n.body.length)).replace("\n", " ") + "…",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                }
                if (t.isNotBlank() && notes.none { it.title.equals(t, true) })
                    Text("Create “$t”", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.fillMaxWidth().clickable { onOpen(t) }.padding(vertical = 10.dp))
            }
        }
    }
}

// ── editor ──────────────────────────────────────────────────────────────────────────────────

@Composable
private fun Editor(note: Note, notes: List<Note>, vm: NotesViewModel, onBack: () -> Unit, onOpen: (String) -> Unit, onTag: (String) -> Unit) {
    val c = LocalContext.current
    var reading by remember(note.id) { mutableStateOf(note.body.isNotBlank()) }
    var text by remember(note.id) { mutableStateOf(TextFieldValue(note.body, TextRange(note.body.length))) }
    var title by remember(note.id) { mutableStateOf(note.title) }
    var outline by remember { mutableStateOf(false) }
    var local by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var templates by remember { mutableStateOf(false) }
    BackHandler { vm.save(note.copy(body = text.text)); if (title.trim() != note.title) vm.rename(note, title); onBack() }
    // autosave shortly after you stop typing
    LaunchedEffect(text.text) { com.nizam.app.ui.design.guard { if (text.text != note.body) { delay(600); vm.save(note.copy(body = text.text)) } } }

    fun toggleTask(line: Int) {
        val lines = text.text.lines().toMutableList()
        if (line !in lines.indices) return
        val m = Regex("\\[([ xX])]").find(lines[line]) ?: return
        lines[line] = lines[line].replaceRange(m.range, if (m.groupValues[1] == " ") "[x]" else "[ ]")
        text = TextFieldValue(lines.joinToString("\n")); vm.save(note.copy(body = text.text))
    }
    fun insert(before: String, after: String = "") {
        val s = text.selection; val t = text.text
        val sel = t.substring(s.min, s.max)
        val out = t.substring(0, s.min) + before + sel + after + t.substring(s.max)
        text = TextFieldValue(out, TextRange(s.min + before.length + sel.length))
    }
    fun linePrefix(p: String) {
        val t = text.text; val start = t.lastIndexOf('\n', (text.selection.min - 1).coerceAtLeast(0)).let { if (it < 0 || text.selection.min == 0) 0 else it + 1 }
        text = TextFieldValue(t.substring(0, start) + p + t.substring(start), TextRange(text.selection.min + p.length))
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.save(note.copy(body = text.text)); if (title.trim() != note.title) vm.rename(note, title); onBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to vault") }
            Text(note.title.substringBeforeLast('/', "Vault"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            androidx.compose.material3.TextButton(onClick = { if (!reading) vm.save(note.copy(body = text.text)); reading = !reading }) {
                Text(if (reading) "Edit" else "Read") }
            IconButton(onClick = { vm.save(note.copy(pinned = !note.pinned)) }) {
                Icon(if (note.pinned) Icons.Outlined.Star else Icons.Outlined.StarBorder, "Star") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem({ Text("Outline") }, { menu = false; outline = true })
                    DropdownMenuItem({ Text("Local graph") }, { menu = false; local = true })
                    DropdownMenuItem({ Text("Insert template") }, { menu = false; templates = true })
                    DropdownMenuItem({ Text("Share as Markdown") }, { menu = false
                        c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/markdown").putExtra(Intent.EXTRA_SUBJECT, note.title)
                            .putExtra(Intent.EXTRA_TEXT, text.text), "Share note")) })
                    DropdownMenuItem({ Text("Delete", color = MaterialTheme.colorScheme.error) }, { menu = false; vm.delete(note); onBack() })
                }
            }
        }
        val words = text.text.split(Regex("\\s+")).count { it.isNotBlank() }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
            TextField(title.substringAfterLast('/'), { v -> title = (note.title.substringBeforeLast('/', "").let { if (it.isBlank()) "" else "$it/" }) + v.replace("/", "-") },
                textStyle = MaterialTheme.typography.headlineSmall.copy(fontFamily = DisplayFamily), singleLine = true,
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth())
            TagRow(Md.tags(text.text), onTag)
            if (reading) {
                MarkdownView(text.text, onLink = onOpen, onTag = onTag,
                    onUrl = { runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(it))) } },
                    onToggleTask = ::toggleTask, embed = { t -> notes.firstOrNull { it.title.equals(t, true) }?.body })
                if (text.text.isBlank()) Text("Empty — tap Edit to write.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                TextField(text, { text = it }, modifier = Modifier.fillMaxWidth(),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Default),
                    placeholder = { Text("Write in Markdown. [[ links a note, # tags it, - [ ] makes a task.") },
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent))
                // [[ autocomplete: typing after an open [[ suggests notes
                val before = text.text.substring(0, text.selection.min)
                val open = before.lastIndexOf("[[")
                val partial = if (open >= 0 && !before.substring(open).contains("]]") && !before.substring(open).contains('\n')) before.substring(open + 2) else null
                if (partial != null) {
                    val hits = notes.filter { it.id != note.id && it.title.contains(partial, true) }.take(6)
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                        (hits.map { it.title } + listOfNotNull(partial.takeIf { p -> p.isNotBlank() && notes.none { it.title.equals(p, true) } })).forEach { h ->
                            Text(if (notes.any { it.title == h }) h else "Link new note “$h”", style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().clickable {
                                    val t = text.text
                                    val out = t.substring(0, open) + "[[" + h + "]]" + t.substring(text.selection.min).removePrefix("]]")
                                    text = TextFieldValue(out, TextRange(open + h.length + 4))
                                }.padding(horizontal = 12.dp, vertical = 9.dp))
                        }
                    }
                }
            }
            Backlinks(note, notes, onOpen)
            Text("$words words · edited ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(note.updatedAt))}",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp))
            Spacer(Modifier.height(40.dp))
        }
        if (!reading) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp)) {
            listOf("[[ ]]" to { insert("[[", "]]") }, "#" to { insert("#") }, "B" to { insert("**", "**") }, "I" to { insert("*", "*") },
                "H" to { linePrefix("## ") }, "☐" to { linePrefix("- [ ] ") }, "•" to { linePrefix("- ") }, "1." to { linePrefix("1. ") },
                "❝" to { linePrefix("> ") }, "</>" to { insert("`", "`") }, "==" to { insert("==", "==") }, "~~" to { insert("~~", "~~") },
                "—" to { insert("\n---\n") }, "Callout" to { linePrefix("> [!note] ") }, "Date" to { insert(LocalDate.now().toString()) })
                .forEach { (label, act) -> androidx.compose.material3.TextButton(onClick = act) { Text(label, fontSize = 15.sp) } }
        }
    }

    if (outline) androidx.compose.material3.AlertDialog(onDismissRequest = { outline = false }, confirmButton = {},
        title = { Text("Outline") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            val hs = Md.headings(text.text)
            if (hs.isEmpty()) Text("No headings. Start a line with # to make one.")
            hs.forEach { (lvl, h) -> Text(h, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = ((lvl - 1) * 14).dp, top = 6.dp, bottom = 6.dp)) }
        } })
    if (local) androidx.compose.material3.AlertDialog(onDismissRequest = { local = false }, confirmButton = {},
        title = { Text("Local graph") },
        text = { Graph(notes, note.title, Modifier.fillMaxWidth().height(380.dp)) { local = false; onOpen(it) } })
    if (templates) androidx.compose.material3.AlertDialog(onDismissRequest = { templates = false }, confirmButton = {},
        title = { Text("Insert template") },
        text = { Column {
            val tpls = notes.filter { it.title.startsWith("Templates/", true) }
            if (tpls.isEmpty()) Text("Make notes in a Templates folder (e.g. Templates/Meeting). {{date}}, {{time}} and {{title}} fill in.")
            tpls.forEach { t -> Text(t.title.removePrefix("Templates/"), style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().clickable { templates = false; reading = false; insert(fill(t.body, note.title)) }.padding(vertical = 10.dp)) }
        } })
}

@Composable
private fun Backlinks(note: Note, notes: List<Note>, onOpen: (String) -> Unit) {
    val name = note.title; val short = name.substringAfterLast('/')
    val linked = notes.filter { n -> n.id != note.id && Md.WIKI.findAll(n.body).any { it.groupValues[1].trim().equals(name, true) || it.groupValues[1].trim().equals(short, true) } }
    val mentions = if (short.length < 3) emptyList() else notes.filter { n -> n.id != note.id && n !in linked && n.body.contains(short, true) }
    if (linked.isEmpty() && mentions.isEmpty()) return
    HorizontalDivider(Modifier.padding(top = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
    if (linked.isNotEmpty()) {
        Label("Linked mentions · ${linked.size}", Modifier.padding(top = 12.dp))
        linked.forEach { n -> Snippet(n, short) { onOpen(n.title) } }
    }
    if (mentions.isNotEmpty()) {
        Label("Unlinked mentions · ${mentions.size}", Modifier.padding(top = 12.dp))
        mentions.take(10).forEach { n -> Snippet(n, short) { onOpen(n.title) } }
    }
}

@Composable
private fun Snippet(n: Note, term: String, onClick: () -> Unit) {
    val at = n.body.indexOf(term, ignoreCase = true).coerceAtLeast(0)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        .clickable(onClick = onClick).padding(10.dp)) {
        Text(n.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(n.body.substring((at - 40).coerceAtLeast(0), (at + 80).coerceAtMost(n.body.length)).replace("\n", " "),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
    }
}

// ── graph view ──────────────────────────────────────────────────────────────────────────────

@Composable
fun Graph(notes: List<Note>, focus: String?, modifier: Modifier, onOpen: (String) -> Unit) {
    val g = remember(notes, focus) { buildGraph(notes, focus) }
    val n = g.nodes.size
    // force layout: linked notes pull together, every note pushes the others away
    val pos = remember(g) {
        val rnd = java.util.Random(42)
        Array(n) { Offset((rnd.nextFloat() - 0.5f) * 600f, (rnd.nextFloat() - 0.5f) * 600f) }.also { p ->
            val degree = IntArray(n).also { d -> g.edges.forEach { d[it.first]++; d[it.second]++ } }
            repeat(if (n > 300) 80 else 220) { step ->
                val force = Array(n) { Offset.Zero }
                for (i in 0 until n) for (j in i + 1 until n) {
                    val d = p[i] - p[j]; val dist2 = (d.x * d.x + d.y * d.y).coerceAtLeast(25f)
                    val f = d * (5200f / dist2)
                    force[i] += f; force[j] -= f
                }
                g.edges.forEach { (a, b) -> val d = p[b] - p[a]; force[a] += d * 0.035f; force[b] -= d * 0.035f }
                for (i in 0 until n) force[i] -= p[i] * (if (degree[i] == 0) 0.02f else 0.008f)
                val cool = 1f - step / 260f
                for (i in 0 until n) { val f = force[i]; val len = sqrt(f.x * f.x + f.y * f.y); p[i] += if (len > 30f) f * (30f / len) * cool else f * cool }
            }
        }
    }
    val degree = remember(g) { IntArray(n).also { d -> g.edges.forEach { d[it.first]++; d[it.second]++ } } }
    var zoom by remember(g) { mutableFloatStateOf(1f) }
    var pan by remember(g) { mutableStateOf(Offset.Zero) }
    val measurer = rememberTextMeasurer()
    val cs = MaterialTheme.colorScheme
    val labelStyle = TextStyle(fontSize = 10.sp, color = cs.onSurfaceVariant)
    if (n == 0) { Box(modifier, contentAlignment = Alignment.Center) { Text("Link notes with [[ ]] to see your graph grow.", color = cs.onSurfaceVariant) }; return }
    Canvas(modifier.clip(RoundedCornerShape(12.dp))
        .pointerInput(g) { detectTransformGestures { _, p, z, _ -> pan += p; zoom = (zoom * z).coerceIn(0.3f, 4f) } }
        .pointerInput(g, "tap") { detectTapGestures { tap ->
            val centre = Offset(size.width / 2f, size.height / 2f) + pan
            val hit = (0 until n).minByOrNull { ((pos[it] * zoom + centre) - tap).getDistance() }
            if (hit != null && ((pos[hit] * zoom + centre) - tap).getDistance() < 40f) onOpen(g.nodes[hit])
        } }) {
        val centre = Offset(size.width / 2f, size.height / 2f) + pan
        g.edges.forEach { (a, b) -> drawLine(cs.outlineVariant, pos[a] * zoom + centre, pos[b] * zoom + centre, strokeWidth = 1.2f) }
        for (i in 0 until n) {
            val at = pos[i] * zoom + centre
            val r = (5f + degree[i] * 1.6f).coerceAtMost(18f) * zoom.coerceIn(0.6f, 1.6f)
            val isFocus = focus != null && g.nodes[i].equals(focus, true)
            drawCircle(if (i in g.missing) cs.outline.copy(alpha = 0.5f) else if (isFocus) cs.tertiary else cs.primary, r, at)
            if (zoom > 0.7f || degree[i] >= 3 || isFocus) drawText(measurer, g.nodes[i].substringAfterLast('/').take(24), at + Offset(-r, r + 3f), labelStyle)
        }
    }
}
