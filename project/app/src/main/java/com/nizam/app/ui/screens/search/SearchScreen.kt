package com.nizam.app.ui.screens.search

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nizam.app.AppContainer
import com.nizam.app.core.vmFactory
import com.nizam.app.feature.diary.DiaryViewModel
import com.nizam.app.feature.notes.NotesViewModel
import com.nizam.app.feature.dynamic.ModuleEntry
import com.nizam.app.feature.dynamic.ModuleSpec
import com.nizam.app.feature.nx.ModeNavigationStore
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.nav.Routes
import com.nizam.app.ui.screens.tasks.TasksViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class Dest(val emoji: String, val name: String, val route: String, val words: String = "")

/** Lets the agent (or anything outside the UI) move Atlas to a screen. Set by the app's navigation host. */
object AtlasNav { @Volatile var go: ((String) -> Unit)? = null }

val DESTINATIONS = listOf(
    Dest("⚖️", "Council", Routes.COUNCIL),
    Dest("✅", "Tasks", Routes.TASKS),
    Dest("🕌", "Prayer", Routes.PRAYER),
    Dest("🤲", "Deen", Routes.DEEN),
    Dest("📖", "Quran", Routes.QURAN),
    Dest("📜", "Hadith", Routes.HADITH),
    Dest("💰", "Finance", Routes.FINANCE),
    Dest("🥗", "Diet", Routes.DIET),
    Dest("🏋️", "Gym", Routes.GYM),
    Dest("🎓", "Courses", Routes.COURSES),
    Dest("🧠", "Learning", Routes.LEARNING),
    Dest("🗒️", "Notes", Routes.NOTES),
    Dest("📚", "Library", Routes.LIBRARY),
    Dest("✍️", "Diary", Routes.DIARY),
    Dest("💬", "Quotes", Routes.QUOTES),
    Dest("🎥", "Rep counter", Routes.REPS),
    Dest("📸", "Snap plate", Routes.PLATE),
    Dest("🎧", "Voice coach", Routes.COACH),
    Dest("🗣", "Language", Routes.LANGUAGE),
    Dest("🌤", "Daily life", Routes.DAILY),
    Dest("🏘", "Pixel Town", Routes.TOWN),
    Dest("🔒", "Vault", Routes.VAULT),
    Dest("🛠", "Tools", Routes.TOOLS),
    Dest("🏦", "Auto-logging", Routes.AUTOMONEY),
    Dest("📷", "Scanner", Routes.SCAN),
    Dest("🧰", "Toolkit", Routes.TOOLKIT),
    Dest("🌐", "Browser", Routes.BROWSER),
    Dest("📰", "News", Routes.NEWS),
    Dest("⛑️", "Survival", Routes.SURVIVAL),
    Dest("🎬", "Cinema", Routes.CINEMA),
    Dest("🎵", "Music", Routes.MUSIC),
    Dest("🎙", "Podcasts", Routes.PODCASTS),
    Dest("📺", "Live TV", Routes.IPTV),
    Dest("🔌", "Playground", Routes.PLAYGROUND),
    Dest("🛍", "App store", Routes.APPSTORE),
    Dest("⚙️", "Settings", Routes.SETTINGS, "api keys theme providers"),
    Dest("🧠", "Offline models", Routes.MODELS, "ai download local llm"),
    Dest("🔑", "API keys", Routes.KEYS, "keys tokens gemini openrouter tmdb switch"),
    Dest("🐣", "Companion", Routes.COMPANION, "buddy pet creature customise"),
    Dest("🔒", "Privacy & security", Routes.SECURITY, "lock encrypt privacy screenshot"),
    Dest("🧘", "Mind", Routes.CALM, "meditate meditation calm breathe sleep sounds"),
    Dest("💬", "Chat with Atlas", Routes.ASSISTANT, "ask ai assistant think deepsearch imagine contemplate"),
    Dest("🤖", "Jarvis", Routes.JARVIS, "standing orders autopilot routines replay hands-free"),
    Dest("🔎", "Search", Routes.SEARCH, "find"),
    Dest("🎛️", "Modes & Navigation", "nx/modesnavigation",
        "modes navigation zen elder accessibility shortcuts language today cards groups pinned destinations saved searches"),
    Dest("🧭", "Maps", Routes.MAPS, "map directions navigate traffic location places qibla")
) + listOf(Dest("✨", "Superpowers", Routes.POWERS, "new features powers tools")) +
    com.nizam.app.feature.future.Powers.ALL.map { Dest(it.emoji, it.name, "power/${it.id}", it.words) }

private data class Hit(
    val title: String,
    val sub: String,
    val open: () -> Unit,
    val peek: (() -> Unit)? = null
)

private data class DynamicRecordPeek(
    val spec: ModuleSpec,
    val entry: ModuleEntry,
    val matchedField: String
)

/**
 * One search for everything: Atlas's own screens and apps, tasks, notes, diary, your music, your
 * films and series, the apps on this phone — plus "Ask Atlas" and "Search the web" for anything else.
 * What you typed stays in the box (and in recent searches) until you clear it.
 */
@Composable
fun SearchScreen(container: AppContainer, onOpen: (String) -> Unit = {}) {
    val c = LocalContext.current
    val tasksVm: TasksViewModel = viewModel(key = "searchTasks",
        factory = TasksViewModel.factory(container.taskRepository, container.habitRepository))
    val notesVm: NotesViewModel = viewModel(key = "searchNotes", factory = vmFactory { NotesViewModel(container.noteRepository) })
    val diaryVm: DiaryViewModel = viewModel(key = "searchDiary", factory = vmFactory { DiaryViewModel(container.diaryRepository) })
    val tasks by tasksVm.tasks.collectAsState()
    val notes by notesVm.notes.collectAsState()
    val entries by diaryVm.entries.collectAsState()
    val dynamicSpecs by container.dynamicRepository.specs().collectAsState(initial = emptyList())
    var dynamicEntries by remember { mutableStateOf(emptyList<com.nizam.app.feature.dynamic.ModuleEntry>()) }
    val modeStore = remember(c) { ModeNavigationStore.get(c) }
    val modeState by modeStore.state.collectAsState()
    val scope = com.nizam.app.ui.design.rememberSafeScope()

    val prefs = remember { c.getSharedPreferences("search", 0) }
    var query by rememberSaveable { mutableStateOf(prefs.getString("last", "").orEmpty()) }
    var filterModule by rememberSaveable { mutableStateOf("") }
    var selectedRecordModuleKey by rememberSaveable { mutableStateOf("") }
    var selectedRecordId by rememberSaveable { mutableStateOf(0L) }
    var selectedMatchedField by rememberSaveable { mutableStateOf("") }
    val selectedRecord = remember(
        selectedRecordModuleKey,
        selectedRecordId,
        selectedMatchedField,
        dynamicSpecs,
        dynamicEntries
    ) {
        val spec = dynamicSpecs.firstOrNull { it.key == selectedRecordModuleKey }
        val entry = dynamicEntries.firstOrNull {
            it.moduleKey == selectedRecordModuleKey && it.id == selectedRecordId
        }
        if (selectedRecordId > 0L && spec != null && entry != null) {
            DynamicRecordPeek(spec, entry, selectedMatchedField)
        } else null
    }
    var commandPaletteOpen by rememberSaveable { mutableStateOf(false) }
    var searchNotice by rememberSaveable { mutableStateOf("") }
    var recent by remember { mutableStateOf(prefs.getString("recent", "").orEmpty().split("\n").filter { it.isNotBlank() }) }
    fun keep(q: String) {
        val t = q.trim(); if (t.isBlank()) return
        recent = (listOf(t) + recent.filter { !it.equals(t, true) }).take(12)
        prefs.edit().putString("recent", recent.joinToString("\n")).putString("last", t).apply()
    }
    var tracks by remember { mutableStateOf<List<com.nizam.app.feature.music.Track>>(emptyList()) }
    var phoneApps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard {
        tracks = runCatching { com.nizam.app.feature.music.MusicLibrary.all(c) }.getOrElse { emptyList() }
        phoneApps = withContext(Dispatchers.IO) { runCatching {
            val pm = c.packageManager
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }.distinctBy { it.second }
        }.getOrElse { emptyList() } }
    } }
    val films = remember { com.nizam.app.feature.cinema.Library.items(c) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { runCatching { focus.requestFocus() } } }

    val q = query.trim()
    LaunchedEffect(q) { com.nizam.app.ui.design.guard {
        // let typing settle, then ask the database for matches
        if (q.isBlank()) { dynamicEntries = emptyList(); return@LaunchedEffect }
        kotlinx.coroutines.delay(140)
        dynamicEntries = runCatching { container.dynamicRepository.search(q) }.getOrDefault(emptyList())
    } }
    fun go(route: String) {
        keep(q)
        scope.launch { runCatching { modeStore.recordRecent(route) } }
        onOpen(route)
    }
    val groups: List<Pair<String, List<Hit>>> = if (q.isBlank()) emptyList() else listOf(
        "Go to" to DESTINATIONS.filter { it.name.contains(q, true) || it.words.contains(q, true) }
            .map { d -> Hit("${d.emoji}  ${d.name}", "", { go(d.route) }) },
        "Tasks" to tasks.filter { it.title.contains(q, true) }.take(8).map { t -> Hit(t.title, "Task", { go(Routes.TASKS) }) },
        "Notes" to notes.filter { it.title.contains(q, true) || it.body.contains(q, true) }.take(8)
            .map { n -> Hit(n.title.ifBlank { n.body.take(40) }, n.body.take(80).replace("\n", " "), {
                com.nizam.app.feature.notes.VaultNav.open = n.title; go(Routes.NOTES) }) },
        "Diary" to entries.filter { it.body.contains(q, true) }.take(6)
            .map { e -> Hit(e.localDate, e.body.take(90).replace("\n", " "), { go(Routes.DIARY) }) },
        "Music" to tracks.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }.take(8)
            .map { t -> Hit(t.title, listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "), {
                keep(q); com.nizam.app.feature.music.MusicPlayback.play(c, listOf(t)); onOpen(Routes.MUSIC) }) },
        "Films & series" to films.filter { it.optString("name").contains(q, true) }.take(8).map { f -> Hit(f.optString("name"), f.optString("type"), {
            com.nizam.app.feature.cinema.CinemaNav.metaType = f.optString("type"); com.nizam.app.feature.cinema.CinemaNav.metaId = f.optString("id")
            go(Routes.META) }) },
        "Apps on this phone" to phoneApps.filter { it.first.contains(q, true) }.take(6).map { (label, pkg) -> Hit(label, "Open app", {
            keep(q); c.packageManager.getLaunchIntentForPackage(pkg)?.let { runCatching { c.startActivity(it) } } }) },
    ).filter { it.second.isNotEmpty() }
    val specsByKey = remember(dynamicSpecs) { dynamicSpecs.associateBy { it.key } }
    val dynamicHits = if (q.isBlank()) emptyList() else dynamicEntries.asSequence()
        .filter { filterModule.isBlank() || it.moduleKey == filterModule }
        .mapNotNull { entry ->
            val spec = specsByKey[entry.moduleKey] ?: return@mapNotNull null
            val matchingField = spec.fields.firstNotNullOfOrNull { field ->
                entry.value(field.key).takeIf { it.contains(q, true) }?.let { "${field.label}: $it" }
            }
            val titleMatches = entry.title.contains(q, true)
            if (!titleMatches && matchingField == null) return@mapNotNull null
            val matchedText = matchingField ?: "Title: ${entry.title}"
            Hit(
                title = entry.title.ifBlank { "${spec.name} · ${entry.localDate}" },
                sub = "${spec.name} · ${entry.localDate} · ${matchedText.take(100)}",
                open = { go(Routes.dynamic(spec.key)) },
                peek = {
                    selectedRecordModuleKey = spec.key
                    selectedRecordId = entry.id
                    selectedMatchedField = matchedText
                }
            )
        }.take(30).toList()
    val resultGroups = (if (filterModule.isBlank()) groups else emptyList()) +
        listOf("Custom records" to dynamicHits).filter { it.second.isNotEmpty() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = query, onValueChange = {
                // the only door to the private vault: its secret code, typed here — never saved to recent searches
                if (it.trim() == com.nizam.app.feature.hidden.Hv.code(c)) { query = ""; onOpen("hv") } else query = it
            },
            placeholder = { Text("Search Atlas, your phone, the web") }, singleLine = true,
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = ""; prefs.edit().putString("last", "").apply() }) {
                Icon(Icons.Outlined.Close, "Clear") } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { keep(q) }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus).onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.K) {
                    commandPaletteOpen = true
                    true
                } else false
            }
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            FilterChip(selected = filterModule.isBlank(), onClick = { filterModule = "" }, label = { Text("All records") })
            dynamicSpecs.take(12).forEach { spec ->
                FilterChip(
                    selected = filterModule == spec.key,
                    onClick = { filterModule = if (filterModule == spec.key) "" else spec.key },
                    label = { Text(spec.name) }
                )
            }
        }

        if (q.isBlank()) {
            if (modeState.savedSearches.isNotEmpty()) {
                Label("Saved search views", Modifier.padding(top = 14.dp))
                modeState.savedSearches.sortedByDescending { it.pinned }.forEach { saved ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable(role = Role.Button) {
                            query = saved.query
                            filterModule = saved.moduleKey
                        }.padding(vertical = 8.dp)) {
                            Text(saved.title, style = MaterialTheme.typography.bodyLarge)
                            Text(saved.query, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        }
                        TextButton(onClick = {
                            scope.launch { runCatching { modeStore.setSearchPinned(saved.id, !saved.pinned) } }
                        }) { Text(if (saved.pinned) "Unpin" else "Pin") }
                        TextButton(onClick = {
                            scope.launch { runCatching { modeStore.deleteSearch(saved.id) } }
                        }) { Text("Remove") }
                    }
                }
            }
            if (recent.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label("Recent", Modifier.weight(1f))
                    Text("Clear", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(role = Role.Button) { recent = emptyList(); prefs.edit().remove("recent").apply() }.padding(6.dp))
                }
                recent.forEach { r -> Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { query = r }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp)); Text(r, style = MaterialTheme.typography.bodyLarge)
                } }
            } else Text("Screens, tasks, notes, diary, music, films, the apps on your phone — or ask Atlas anything.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(96.dp))
            return@Column
        }

        if (modeState.storageError != null) {
            Text(modeState.storageError!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } else {
            TextButton(onClick = {
                scope.launch {
                    runCatching { modeStore.saveSearch(q, q, filterModule) }
                        .onSuccess { searchNotice = "Saved this search view on the device." }
                        .onFailure { searchNotice = it.message ?: "Search view could not be saved." }
                }
            }) { Text("Save this search view") }
        }
        searchNotice.takeIf(String::isNotBlank)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // always there: hand it to Atlas, or to the web
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.nizam.app.ui.design.OutlinedButton(onClick = { keep(q); com.nizam.app.feature.agent.AskAtlas.put(q); onOpen(Routes.ASSISTANT) }) {
                Text("Ask Atlas") }
            com.nizam.app.ui.design.OutlinedButton(onClick = { keep(q)
                runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(q)))) } }) {
                Text("Search the web") }
            com.nizam.app.ui.design.OutlinedButton(onClick = { keep(q); com.nizam.app.feature.maps.MapsNav.query = q; onOpen(Routes.MAPS) }) { Text("On the map") }
        }
        resultGroups.forEach { (title, hits) ->
            Label(title, Modifier.padding(top = 18.dp, bottom = 2.dp))
            hits.forEach { h ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = h.open).padding(vertical = 4.dp)) {
                        Text(h.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (h.sub.isNotBlank()) Text(h.sub, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    h.peek?.let { preview ->
                        TextButton(onClick = preview) { Text("Peek") }
                    }
                }
            }
        }
        if (resultGroups.isEmpty()) Text("Nothing in Atlas matches \"$q\" — ask Atlas or search the web above.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
        Spacer(Modifier.height(96.dp))
    }

    selectedRecord?.let { preview ->
        AlertDialog(
            onDismissRequest = {
                selectedRecordModuleKey = ""
                selectedRecordId = 0L
                selectedMatchedField = ""
            },
            title = { Text(preview.entry.title.ifBlank { "Untitled record" }) },
            text = {
                Column(Modifier.height(360.dp).verticalScroll(rememberScrollState())) {
                    Text("${preview.spec.name} · ${preview.entry.localDate} · record #${preview.entry.id}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Matched: ${preview.matchedField}", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 8.dp))
                    preview.entry.values().forEach { (key, value) ->
                        val label = preview.spec.fields.firstOrNull { it.key == key }?.label ?: key
                        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(value.ifBlank { "—" }, style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(bottom = 8.dp))
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    selectedRecordModuleKey = ""
                    selectedRecordId = 0L
                    selectedMatchedField = ""
                    onOpen(Routes.dynamic(preview.spec.key))
                }) {
                    Text("Open module")
                }
            },
            dismissButton = { TextButton(onClick = {
                selectedRecordModuleKey = ""
                selectedRecordId = 0L
                selectedMatchedField = ""
            }) { Text("Close") } }
        )
    }
    AtlasCommandPalette(
        visible = commandPaletteOpen,
        onDismiss = { commandPaletteOpen = false },
        onOpen = { route ->
            commandPaletteOpen = false
            go(route)
        },
        extraDestinations = dynamicSpecs.map { Dest(it.emoji, it.name, Routes.dynamic(it.key), it.name) }
    )
}

/** Reusable overlay for the app shell; Search also opens it directly with Ctrl+K. */
@Composable
fun AtlasCommandPalette(
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit,
    extraDestinations: List<Dest> = emptyList()
) {
    if (!visible) return
    var filter by remember { mutableStateOf("") }
    val paletteFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { runCatching { paletteFocus.requestFocus() } } }
    val destinations = (DESTINATIONS + extraDestinations).distinctBy { it.route }
        .filter { filter.isBlank() || it.name.contains(filter, true) || it.words.contains(filter, true) }
        .take(24)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to…") },
        text = {
            Column {
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    label = { Text("Find a screen or module") },
                    modifier = Modifier.fillMaxWidth().focusRequester(paletteFocus)
                )
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    destinations.forEach { destination ->
                        TextButton(onClick = { onOpen(destination.route) }, modifier = Modifier.fillMaxWidth()) {
                            Text("${destination.emoji}  ${destination.name}", modifier = Modifier.fillMaxWidth())
                        }
                    }
                    if (destinations.isEmpty()) {
                        Text("No destinations match.", style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(12.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } }
    )
}
