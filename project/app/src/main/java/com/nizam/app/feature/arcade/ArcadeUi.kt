package com.nizam.app.feature.arcade

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun sys(g: ArcadeGame) = ArcadeSystems.byId(g.system)

@Composable
private fun Cover(g: ArcadeGame, modifier: Modifier = Modifier) {
    val s = sys(g)
    val base = Color(s?.color ?: 0xFF444444)
    Box(modifier.background(Brush.linearGradient(listOf(base, base.copy(alpha = 0.45f), Color(0xFF15151B)))), contentAlignment = Alignment.Center) {
        Text(s?.short ?: "?", color = Color.White.copy(alpha = 0.85f), fontWeight = FontWeight.Black, fontSize = 22.sp)
        if (g.cover != null) AsyncImage(g.cover, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.None)
    }
}

/** Atlas Arcade: play classic games on your phone. */
@Composable
fun AtlasArcadeScreen() {
    val ctx = LocalContext.current
    var playing by remember { mutableStateOf<ArcadeGame?>(null) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var rev by remember { mutableIntStateOf(0) }
    val p = playing
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 12.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Arcade", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("Classic games on your phone", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                listOf("My games" to Icons.Filled.SportsEsports, "Free games" to Icons.Filled.Storefront, "Emulators" to Icons.Filled.Memory).forEachIndexed { i, (t, ic) ->
                    Tab(tab == i, { tab = i }, text = { Text(t) }, icon = { Icon(ic, null) })
                }
            }
            val prefs = ctx.getSharedPreferences("arcade", 0)
            var crashed by remember { mutableStateOf(prefs.getString("launching", null)) }
            val crashStage = remember { prefs.getString("exit_stage", null) }
            LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { prefs.edit().remove("launching").remove("exit_stage").apply() } }
            crashed?.let { name ->
                Card(Modifier.fillMaxWidth().padding(16.dp, 8.dp)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("The last game closed unexpectedly", fontWeight = FontWeight.SemiBold)
                        if (crashStage != null) Text("It stopped while: $crashStage.", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
                        Text("$name. Heavy consoles like PSP and N64 can run out of memory on some phones. If it keeps happening, try a lighter console or tell me the game and console.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { crashed = null }) { Text("OK") }
                    }
                }
            }
            when (tab) {
                0 -> Library(rev, { rev++ }) { playing = it }
                1 -> Store { rev++ }
                else -> EmulatorsPage(ctx)
            }
        }
    }
    if (p != null) ArcadePlayerWindow(p) { playing = null; rev++ }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Library(rev: Int, changed: () -> Unit, play: (ArcadeGame) -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var local by remember { mutableIntStateOf(0) }
    val games = remember(rev, local) { ArcadeLibrary.list(ctx).sortedByDescending { it.last } }
    var filter by remember { mutableStateOf("all") }
    var chooseSystem by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<ArcadeSystem?>(null) }
    var menuFor by remember { mutableStateOf<ArcadeGame?>(null) }
    var busy by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var added = 0; var skipped = 0
            for (u in uris) {
                busy = "Adding games…"
                val name = ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }.orEmpty()
                val size = ctx.contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } ?: 0L
                val matches = ArcadeSystems.byExt(name.substringAfterLast('.', ""))
                val s = target ?: (if (matches.size > 1 && size > 700L * 1024 * 1024) matches.firstOrNull { it.id == "psp" } else null) ?: matches.firstOrNull()
                if (s == null) { skipped++; continue }
                if (ArcadeLibrary.import(ctx, u, s) != null) added++ else skipped++
            }
            busy = ""; local++; changed()
            android.widget.Toast.makeText(ctx, if (skipped == 0) "Added $added game${if (added == 1) "" else "s"}" else "Added $added, skipped $skipped (unknown type or couldn't copy). Pick the console first for unusual file names.", android.widget.Toast.LENGTH_LONG).show()
        }
    }
    val shown = games.filter { filter == "all" || it.system == filter || (filter == "fav" && it.fav) }
    Box(Modifier.fillMaxSize()) {
        if (games.isEmpty()) Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Filled.SportsEsports, null, Modifier.size(68.dp), tint = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(12.dp))
            Text("No games yet", style = MaterialTheme.typography.titleMedium)
            Text("Get free games from the Free games tab, or add game files you own with the + button.", textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Column(Modifier.fillMaxSize()) {
            games.firstOrNull { it.last > 0 }?.let { last ->
                Card(onClick = { play(last) }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Cover(last, Modifier.size(84.dp))
                        Column(Modifier.weight(1f).padding(14.dp)) {
                            Text("Continue playing", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(last.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(sys(last)?.name.orEmpty() + if (last.playMs > 60_000) " · ${last.playMs / 60_000} min played" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Filled.PlayCircle, null, Modifier.padding(end = 16.dp).size(40.dp), tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(filter == "all", { filter = "all" }, label = { Text("All") }) }
                if (games.any { it.fav }) item { FilterChip(filter == "fav", { filter = "fav" }, label = { Text("★ Favourites") }) }
                items(games.map { it.system }.distinct()) { id -> FilterChip(filter == id, { filter = id }, label = { Text(ArcadeSystems.byId(id)?.short ?: id) }) }
            }
            LazyVerticalGrid(GridCells.Adaptive(124.dp), contentPadding = PaddingValues(16.dp, 12.dp, 16.dp, 96.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shown, key = { it.id }) { g ->
                    Column(Modifier.clip(RoundedCornerShape(16.dp)).combinedClickable(onClick = { play(g) }, onLongClick = { menuFor = g })) {
                        Box {
                            Cover(g, Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)))
                            if (g.fav) Icon(Icons.Filled.Star, null, Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp), tint = Color(0xFFFFC857))
                        }
                        Text(g.title, Modifier.padding(top = 6.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        ExtendedFloatingActionButton(onClick = { chooseSystem = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add games") }, modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp))
        if (busy.isNotEmpty()) Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text(busy, color = Color.White) } }
    }
    if (chooseSystem) AlertDialog(onDismissRequest = { chooseSystem = false }, title = { Text("Which console?") },
        text = { LazyColumn { 
            item { TextButton(onClick = { target = null; chooseSystem = false; picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text("Detect from the file name", Modifier.fillMaxWidth()) } }
            items(ArcadeSystems.all) { s -> TextButton(onClick = { target = s; chooseSystem = false; picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text(s.name, Modifier.fillMaxWidth()) } }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { chooseSystem = false }) { Text("Cancel") } })
    menuFor?.let { g ->
        ModalBottomSheetLite(onClose = { menuFor = null }) {
            Text(g.title, Modifier.padding(20.dp, 4.dp), fontWeight = FontWeight.Bold)
            RowAction(if (g.fav) Icons.Filled.StarBorder else Icons.Filled.Star, if (g.fav) "Remove from favourites" else "Add to favourites") { ArcadeLibrary.update(ctx, g.id) { it.fav = !it.fav }; menuFor = null; local++ }
            RowAction(Icons.Filled.Delete, "Delete game and its saves") { ArcadeLibrary.remove(ctx, g); menuFor = null; local++; changed() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModalBottomSheetLite(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose) { Column(Modifier.padding(bottom = 24.dp), content = content) }
}

@Composable
private fun RowAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(20.dp, 14.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null); Spacer(Modifier.width(16.dp)); Text(label) }
}

// ───────────────────────── the free games store ─────────────────────────

@Composable
private fun Store(changed: () -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var platform by rememberSaveable { mutableStateOf("GBC") }
    var q by remember { mutableStateOf("") }
    val items = remember { mutableStateListOf<HomebrewHub.Entry>() }
    var page by remember { mutableIntStateOf(1) }
    var pages by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var detail by remember { mutableStateOf<HomebrewHub.Entry?>(null) }
    val progress = remember { mutableStateMapOf<String, Float>() }
    var installedRev by remember { mutableIntStateOf(0) }
    val installed = remember(installedRev) { ArcadeLibrary.list(ctx).map { it.id }.toSet() }

    fun load(reset: Boolean) {
        if (loading && !reset) return
        loading = true; error = ""
        if (reset) { items.clear(); page = 1 }
        val asked = platform; val askedPage = page
        scope.launch {
            runCatching { HomebrewHub.search(asked, askedPage) }.onSuccess { (list, total) ->
                if (asked == platform) { items.addAll(list.filter { n -> items.none { it.slug == n.slug } }); pages = total }
            }.onFailure { if (asked == platform) error = "Couldn't reach the store. Check your internet and try again." }
            if (asked == platform) loading = false
        }
    }
    LaunchedEffect(platform) { com.nizam.app.ui.design.guard { load(true) } }

    fun install(e: HomebrewHub.Entry) {
        val key = e.slug + e.platform
        if (progress.containsKey(key)) return
        progress[key] = 0f
        scope.launch {
            runCatching { HomebrewHub.install(ctx, e) { progress[key] = it } }.onFailure { error = "Couldn't download ${e.title}: ${it.message}" }
            progress.remove(key); installedRev++; changed()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text("Games made by independent creators and shared for free through Homebrew Hub.", Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("GB" to "Game Boy", "GBC" to "Game Boy Color", "GBA" to "GBA", "NES" to "NES").forEach { (id, label) -> FilterChip(platform == id, { platform = id }, label = { Text(label) }) }
        }
        OutlinedTextField(q, { q = it }, placeholder = { Text("Search these games") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true, shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(16.dp, 8.dp))
        val shown = items.filter { q.isBlank() || it.title.contains(q, true) || it.tags.any { t -> t.contains(q, true) } }
        if (error.isNotEmpty()) Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) { Text(error, Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall); TextButton(onClick = { load(items.isEmpty()) }) { Text("Retry") } }
        LazyVerticalGrid(GridCells.Adaptive(150.dp), contentPadding = PaddingValues(16.dp, 4.dp, 16.dp, 32.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            items(shown, key = { it.slug + it.platform }) { e ->
                val key = e.slug + e.platform
                val id = e.slug + "-" + e.platform.lowercase()
                val have = id in installed
                Column(Modifier.clip(RoundedCornerShape(16.dp)).clickable { detail = e }) {
                    Box(Modifier.fillMaxWidth().aspectRatio(1.1f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1B1B22)), contentAlignment = Alignment.Center) {
                        Text(e.platform, color = Color.White.copy(alpha = 0.5f), fontWeight = FontWeight.Black, fontSize = 20.sp)
                        HomebrewHub.coverUrl(e)?.let { AsyncImage(it, null, Modifier.matchParentSize(), contentScale = ContentScale.Crop, filterQuality = FilterQuality.None) }
                    }
                    Text(e.title, Modifier.padding(top = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                    Text(e.developer, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val pr = progress[key]
                    when {
                        pr != null -> LinearProgressIndicator(progress = { pr }, Modifier.fillMaxWidth().padding(top = 6.dp))
                        have -> Text("✓ In your library", Modifier.padding(top = 4.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        else -> FilledTonalButton(onClick = { install(e) }, Modifier.fillMaxWidth().padding(top = 4.dp), contentPadding = PaddingValues(4.dp)) { Text("Get") }
                    }
                }
            }
            item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().padding(8.dp), contentAlignment = Alignment.Center) {
                    if (loading) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    else if (page < pages) OutlinedButton(onClick = { page++; load(false) }) { Text("Load more") }
                }
            }
        }
    }
    detail?.let { e ->
        val id = e.slug + "-" + e.platform.lowercase()
        AlertDialog(onDismissRequest = { detail = null }, title = { Text(e.title) },
            text = { Column(Modifier.heightIn(max = 380.dp).verticalScrollSimple()) {
                HomebrewHub.coverUrl(e)?.let { AsyncImage(it, null, Modifier.fillMaxWidth().heightIn(max = 180.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit, filterQuality = FilterQuality.None) }
                Text("by ${e.developer}", Modifier.padding(top = 10.dp), fontWeight = FontWeight.SemiBold)
                if (e.tags.isNotEmpty()) Text(e.tags.take(6).joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(e.description.ifBlank { "No description." }, Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
            } },
            confirmButton = { if (id in installed) TextButton(onClick = { detail = null }) { Text("In your library") } else Button(onClick = { install(e); detail = null }) { Text("Get this game") } },
            dismissButton = { TextButton(onClick = { detail = null }) { Text("Close") } })
    }
}

@Composable
private fun Modifier.verticalScrollSimple(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))

// ───────────────────────── emulators & system files ─────────────────────────

@Composable
private fun EmulatorsPage(ctx: android.content.Context) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var rev by remember { mutableIntStateOf(0) }
    val files = remember(rev) { ArcadeLibrary.systemDir(ctx).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch(Dispatchers.IO) {
            uris.forEach { u ->
                runCatching {
                    val name = ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return@forEach
                    ctx.contentResolver.openInputStream(u)!!.use { i -> File(ArcadeLibrary.systemDir(ctx), name).outputStream().use { o -> i.copyTo(o) } }
                }
            }
            withContext(Dispatchers.Main) { rev++ }
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Built-in emulators", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
        items(ArcadeSystems.all) { s ->
            val ready = ArcadeSystems.coreReady(ctx, s)
            Card { Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(Color(s.color))); Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(s.name, fontWeight = FontWeight.SemiBold)
                    Text(if (ready) "Ready" else "Not included in this build", style = MaterialTheme.typography.labelSmall, color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
                    if (s.note.isNotEmpty()) Text(s.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(s.exts.take(4).joinToString(" ") { ".$it" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } }
        }
        item {
            Spacer(Modifier.height(6.dp))
            Text("System files (BIOS)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Some consoles run better with the console's own startup file, for example PlayStation scph5501.bin. Copy yours here from your own console.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.padding(top = 8.dp)) { Icon(Icons.Filled.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Add system files") }
        }
        items(files) { f -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = { f.delete(); rev++ }) { Icon(Icons.Filled.Delete, "Remove") }
        } }
        item {
            Spacer(Modifier.height(10.dp))
            Text("Play only games you own or that their makers share for free. Emulator cores come from the open-source libretro project.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
