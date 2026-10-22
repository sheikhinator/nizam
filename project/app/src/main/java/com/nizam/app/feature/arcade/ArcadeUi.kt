package com.nizam.app.feature.arcade

import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** The Arcade's own look: a night-time games room with neon edges. */
private object Neo {
    val bg = Color(0xFF0B0B12)
    val bg2 = Color(0xFF141422)
    val card = Color(0xFF181826)
    val line = Color(0xFF2A2A3D)
    val ink = Color(0xFFF1F0FA)
    val muted = Color(0xFF9C9BB4)
    val violet = Color(0xFF7C5CFF)
    val cyan = Color(0xFF22D3EE)
    val pink = Color(0xFFFF4D8D)
    val green = Color(0xFF34D399)
    val title = Brush.linearGradient(listOf(Color(0xFF22D3EE), Color(0xFF7C5CFF), Color(0xFFFF4D8D)))
}

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

private fun minutes(ms: Long) = when { ms < 60_000 -> "Just started"; ms < 3_600_000 -> "${ms / 60_000} min played"; else -> "%.1f h played".format(ms / 3_600_000.0) }

/** Atlas Arcade: every classic console on your phone, a free games store, and controllers that just work. */
@Composable
fun AtlasArcadeScreen() {
    val ctx = LocalContext.current
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var rev by remember { mutableIntStateOf(0) }
    // coming back from a game: refresh play time, last played and saves
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val o = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) rev++ }
        owner.lifecycle.addObserver(o); onDispose { owner.lifecycle.removeObserver(o) }
    }
    val games = remember(rev) { ArcadeLibrary.list(ctx) }
    val ready = remember(rev) { ArcadeSystems.all.count { ArcadeSystems.coreReady(ctx, it) } }
    val controller = remember(rev) { ArcadeSession.controllerConnected(ctx) }
    MaterialTheme(colorScheme = darkColorScheme(primary = Neo.violet, secondary = Neo.cyan, tertiary = Neo.pink, background = Neo.bg, surface = Neo.card,
        surfaceVariant = Neo.bg2, onSurface = Neo.ink, onSurfaceVariant = Neo.muted, outline = Neo.line, secondaryContainer = Neo.violet.copy(alpha = 0.3f),
        onSecondaryContainer = Neo.ink, error = Neo.pink)) {
    Surface(Modifier.fillMaxSize(), color = Neo.bg, contentColor = Neo.ink) {
        Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Neo.bg2, Neo.bg))).statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, top = 14.dp, end = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ARCADE", style = TextStyle(brush = Neo.title, fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp))
                    Text("${games.size} game${if (games.size == 1) "" else "s"} · $ready of ${ArcadeSystems.all.size} consoles ready", color = Neo.muted, fontSize = 13.sp)
                }
                if (controller) Row(Modifier.clip(CircleShape).background(Neo.green.copy(alpha = 0.14f)).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.SportsEsports, null, tint = Neo.green, modifier = Modifier.size(18.dp))
                    Text(" Controller", color = Neo.green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Row(Modifier.padding(horizontal = 20.dp, vertical = 14.dp).clip(CircleShape).background(Neo.card).border(1.dp, Neo.line, CircleShape).padding(4.dp)) {
                listOf("Play" to Icons.Filled.SportsEsports, "Store" to Icons.Filled.Storefront, "Consoles" to Icons.Filled.Memory).forEachIndexed { i, (t, ic) ->
                    val on = tab == i
                    Row(Modifier.weight(1f).clip(CircleShape).background(if (on) Brush.horizontalGradient(listOf(Neo.violet, Neo.pink)) else Brush.horizontalGradient(listOf(Color.Transparent, Color.Transparent)))
                        .clickable { tab = i }.padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        Icon(ic, null, tint = if (on) Color.White else Neo.muted, modifier = Modifier.size(18.dp))
                        Text(" $t", color = if (on) Color.White else Neo.muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    }
                }
            }
            val prefs = ctx.getSharedPreferences("arcade", 0)
            var crashed by remember { mutableStateOf(prefs.getString("launching", null)) }
            val crashStage = remember { prefs.getString("exit_stage", null) }
            LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { prefs.edit().remove("launching").remove("exit_stage").apply() } }
            crashed?.let { name ->
                Column(Modifier.fillMaxWidth().padding(16.dp, 0.dp, 16.dp, 8.dp).clip(RoundedCornerShape(18.dp)).background(Neo.card).border(1.dp, Neo.pink.copy(alpha = 0.4f), RoundedCornerShape(18.dp)).padding(14.dp)) {
                    Text("The last game closed unexpectedly", fontWeight = FontWeight.SemiBold)
                    Text("$name${crashStage?.let { " · while $it" } ?: ""}. Heavy consoles (PSP, N64, Dreamcast) can run out of memory on some phones; try the game again or a lighter console.",
                        fontSize = 13.sp, color = Neo.muted)
                    TextButton(onClick = { crashed = null }) { Text("OK", color = Neo.cyan) }
                }
            }
            when (tab) {
                0 -> PlayTab(games, { rev++ }, { tab = 1 }, { tab = 2 })
                1 -> Store { rev++ }
                else -> ConsolesTab { rev++ }
            }
        }
    }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun GameCard(g: ArcadeGame, width: androidx.compose.ui.unit.Dp?, onPlay: () -> Unit, onMenu: () -> Unit) {
    val s = sys(g)
    Column((if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).clip(RoundedCornerShape(18.dp)).combinedClickable(onClick = onPlay, onLongClick = onMenu)) {
        Box {
            Cover(g, Modifier.fillMaxWidth().aspectRatio(0.78f).clip(RoundedCornerShape(18.dp)))
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(4.dp).background(Color(s?.color ?: 0xFF444444)))
            if (g.fav) Icon(Icons.Filled.Star, null, Modifier.align(Alignment.TopEnd).padding(6.dp).size(18.dp), tint = Color(0xFFFFC857))
            Text(s?.short ?: "", Modifier.align(Alignment.TopStart).padding(6.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 2.dp),
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Text(g.title, Modifier.padding(top = 6.dp, start = 2.dp), maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Neo.ink)
        if (g.playMs > 60_000) Text(minutes(g.playMs), Modifier.padding(start = 2.dp), fontSize = 11.sp, color = Neo.muted)
    }
}

@Composable
private fun PlayTab(games: List<ArcadeGame>, changed: () -> Unit, toStore: () -> Unit, toConsoles: () -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var q by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<String?>(null) }
    var chooseSystem by remember { mutableStateOf(false) }
    var target by remember { mutableStateOf<ArcadeSystem?>(null) }
    var menuFor by remember { mutableStateOf<ArcadeGame?>(null) }
    var renaming by remember { mutableStateOf<ArcadeGame?>(null) }
    var busy by remember { mutableStateOf("") }
    var coverFor by remember { mutableStateOf<ArcadeGame?>(null) }
    fun play(g: ArcadeGame) = launchGame(ctx, g)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        scope.launch {
            var added = 0; var skipped = 0
            for (u in uris) {
                busy = "Adding ${added + skipped + 1} of ${uris.size}…"
                val name = runCatching { ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } }.getOrNull().orEmpty()
                val size = runCatching { ctx.contentResolver.query(u, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.getLong(0) else 0L } }.getOrNull() ?: 0L
                val matches = ArcadeSystems.byExt(name.substringAfterLast('.', ""))
                val s = target ?: (if (matches.size > 1 && size > 700L * 1024 * 1024) matches.firstOrNull { it.id == "psp" } else null) ?: matches.firstOrNull()
                if (s == null) { skipped++; continue }
                if (ArcadeLibrary.import(ctx, u, s) != null) added++ else skipped++
            }
            busy = ""; changed()
            com.nizam.app.ui.components.Feedback.show(if (skipped == 0) "Added $added game${if (added == 1) "" else "s"}" else "Added $added, skipped $skipped (choose the console first for unusual file names)")
        }
    }
    val coverPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val g = coverFor ?: return@rememberLauncherForActivityResult
        coverFor = null
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val f = withContext(Dispatchers.IO) { runCatching {
                val dir = File(ArcadeLibrary.root(ctx), "covers").apply { mkdirs() }
                dir.listFiles { f -> f.name.startsWith(g.id + "_") }?.forEach { it.delete() }
                val out = File(dir, "${g.id}_${System.currentTimeMillis()}.img")
                ctx.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }; out
            }.getOrNull() }
            if (f != null) { ArcadeLibrary.update(ctx, g.id) { it.cover = f.absolutePath }; changed() }
        }
    }
    val shown = games.filter { (filter == null || it.system == filter || (filter == "fav" && it.fav)) && (q.isBlank() || it.title.contains(q, true)) }

    Box(Modifier.fillMaxSize()) {
        if (games.isEmpty()) Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Filled.SportsEsports, null, Modifier.size(84.dp), tint = Neo.violet)
            Spacer(Modifier.height(14.dp))
            Text("Your games room is empty", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Get free games made by indie creators, or add game files you own. Every console's emulator is built in.",
                textAlign = TextAlign.Center, color = Neo.muted, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(18.dp))
            Button(onClick = toStore, colors = ButtonDefaults.buttonColors(containerColor = Neo.violet)) { Text("Get free games") }
            OutlinedButton(onClick = { chooseSystem = true }, Modifier.padding(top = 8.dp)) { Text("Add my games", color = Neo.ink) }
        } else LazyColumn(contentPadding = PaddingValues(bottom = 110.dp)) {
            val last = games.filter { it.last > 0 }.maxByOrNull { it.last }
            if (last != null && filter == null && q.isBlank()) item("hero") {
                Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(190.dp).clip(RoundedCornerShape(26.dp)).clickable { play(last) }) {
                    Cover(last, Modifier.matchParentSize().blur(18.dp))
                    Box(Modifier.matchParentSize().background(Brush.horizontalGradient(listOf(Color(0xEE0B0B12), Color(0x990B0B12), Color(0x330B0B12)))))
                    Row(Modifier.matchParentSize().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("CONTINUE", color = Neo.cyan, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
                            Text(last.title, fontSize = 22.sp, fontWeight = FontWeight.Black, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${sys(last)?.name ?: ""} · ${minutes(last.playMs)}", color = Neo.muted, fontSize = 12.sp, maxLines = 1)
                            Spacer(Modifier.height(12.dp))
                            Row(Modifier.clip(CircleShape).background(Brush.horizontalGradient(listOf(Neo.violet, Neo.pink))).padding(horizontal = 18.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
                                Text(" Resume", color = Color.White, fontWeight = FontWeight.Bold)
                            }
                        }
                        Cover(last, Modifier.width(104.dp).aspectRatio(0.78f).clip(RoundedCornerShape(16.dp)).border(2.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(16.dp)))
                    }
                }
            }
            item("search") {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(16.dp, 14.dp, 16.dp, 6.dp), placeholder = { Text("Search your games") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true, shape = CircleShape,
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Neo.line, focusedBorderColor = Neo.violet, unfocusedContainerColor = Neo.card, focusedContainerColor = Neo.card))
            }
            item("filters") {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(filter == null, { filter = null }, label = { Text("All · ${games.size}") }) }
                    if (games.any { it.fav }) item { FilterChip(filter == "fav", { filter = if (filter == "fav") null else "fav" }, label = { Text("★ Favourites") }) }
                    items(games.groupBy { it.system }.entries.sortedByDescending { it.value.size }.map { it.key }) { id ->
                        FilterChip(filter == id, { filter = if (filter == id) null else id }, label = { Text("${ArcadeSystems.byId(id)?.short ?: id} · ${games.count { it.system == id }}") })
                    }
                }
            }
            if (filter == null && q.isBlank()) {
                val recent = games.filter { it.last > 0 }.sortedByDescending { it.last }.take(10)
                val favs = games.filter { it.fav }
                val shelves = buildList {
                    if (recent.size > 1) add("Recently played" to recent)
                    if (favs.isNotEmpty()) add("Favourites" to favs)
                    games.groupBy { it.system }.entries.sortedByDescending { it.value.size }.forEach { (id, l) -> add((ArcadeSystems.byId(id)?.name ?: id) to l.sortedBy { it.title }) }
                }
                shelves.forEach { (title, list) ->
                    item("shelf-$title") {
                        Text(title, Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(list, key = { "$title-${it.id}" }) { g -> GameCard(g, 118.dp, { play(g) }, { menuFor = g }) }
                        }
                    }
                }
            } else {
                shown.chunked(3).forEachIndexed { i, row ->
                    item("row-$i") {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            row.forEach { g -> Box(Modifier.weight(1f)) { GameCard(g, null, { play(g) }, { menuFor = g }) } }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
                if (shown.isEmpty()) item("none") { Text("Nothing matches.", Modifier.padding(24.dp), color = Neo.muted) }
            }
        }
        ExtendedFloatingActionButton(onClick = { chooseSystem = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Add games") },
            containerColor = Neo.violet, contentColor = Color.White, modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(16.dp))
        if (busy.isNotEmpty()) Box(Modifier.fillMaxSize().background(Color(0x99000000)), contentAlignment = Alignment.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp); Spacer(Modifier.width(12.dp)); Text(busy, color = Color.White) }
        }
    }
    if (chooseSystem) AlertDialog(onDismissRequest = { chooseSystem = false }, title = { Text("Which console?") },
        text = { LazyColumn {
            item { TextButton(onClick = { target = null; chooseSystem = false; picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text("Detect from the file name", Modifier.fillMaxWidth()) } }
            items(ArcadeSystems.all) { s -> TextButton(onClick = { target = s; chooseSystem = false; picker.launch(arrayOf("*/*")) }, Modifier.fillMaxWidth()) { Text(s.name, Modifier.fillMaxWidth()) } }
        } }, confirmButton = {}, dismissButton = { TextButton(onClick = { chooseSystem = false }) { Text("Cancel") } })
    menuFor?.let { g ->
        ModalBottomSheetLite(onClose = { menuFor = null }) {
            Text(g.title, Modifier.padding(20.dp, 4.dp), fontWeight = FontWeight.Bold)
            Text("${sys(g)?.name ?: ""} · ${minutes(g.playMs)}", Modifier.padding(horizontal = 20.dp), fontSize = 12.sp, color = Neo.muted)
            RowAction(Icons.Filled.PlayArrow, "Play") { menuFor = null; play(g) }
            RowAction(if (g.fav) Icons.Filled.StarBorder else Icons.Filled.Star, if (g.fav) "Remove from favourites" else "Add to favourites") { ArcadeLibrary.update(ctx, g.id) { it.fav = !it.fav }; menuFor = null; changed() }
            RowAction(Icons.Filled.Edit, "Rename") { renaming = g; menuFor = null }
            RowAction(Icons.Filled.Image, "Choose a cover picture") { coverFor = g; menuFor = null; coverPicker.launch("image/*") }
            RowAction(Icons.Filled.Delete, "Delete game and its saves") {
                ArcadeLibrary.remove(ctx, g); menuFor = null; changed()
                com.nizam.app.ui.components.Feedback.show("Deleted ${g.title}")
            }
        }
    }
    renaming?.let { g ->
        var name by remember(g.id) { mutableStateOf(g.title) }
        AlertDialog(onDismissRequest = { renaming = null }, title = { Text("Rename") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { ArcadeLibrary.update(ctx, g.id) { it.title = name.trim().ifBlank { it.title } }; renaming = null; changed() }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModalBottomSheetLite(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, containerColor = Neo.card, contentColor = Neo.ink) { Column(Modifier.padding(bottom = 24.dp), content = content) }
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

// ───────────────────────── consoles: emulators and system files ─────────────────────────

@Composable
private fun ConsolesTab(changed: () -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var rev by remember { mutableIntStateOf(0) }
    val dl by ArcadeCores.progress.collectAsState()
    var all by remember { mutableStateOf(false) }
    val readyCount = remember(rev, dl.size) { ArcadeSystems.all.count { ArcadeSystems.coreReady(ctx, it) } }
    val files = remember(rev) { ArcadeLibrary.systemDir(ctx).walkTopDown().filter { it.isFile }.map { it.relativeTo(ArcadeLibrary.systemDir(ctx)).path }.filter { !it.startsWith("PPSSPP") }.sorted().toList() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        scope.launch(Dispatchers.IO) {
            var n = 0
            uris.forEach { u ->
                runCatching {
                    val name = ctx.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: return@forEach
                    // Dreamcast files live in a "dc" folder; everything else at the top
                    val dest = if (name.startsWith("dc_")) File(ArcadeLibrary.systemDir(ctx), "dc/$name").apply { parentFile?.mkdirs() } else File(ArcadeLibrary.systemDir(ctx), name)
                    ctx.contentResolver.openInputStream(u)!!.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }; n++
                }
            }
            withContext(Dispatchers.Main) { rev++; com.nizam.app.ui.components.Feedback.show("Added $n system file${if (n == 1) "" else "s"}") }
        }
    }
    LazyColumn(contentPadding = PaddingValues(16.dp, 0.dp, 16.dp, 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item("all") {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Brush.linearGradient(listOf(Neo.violet.copy(alpha = 0.35f), Neo.cyan.copy(alpha = 0.18f))))
                .border(1.dp, Neo.line, RoundedCornerShape(22.dp)).padding(18.dp)) {
                Text("$readyCount of ${ArcadeSystems.all.size} consoles ready", fontSize = 20.sp, fontWeight = FontWeight.Black)
                Text(if (readyCount == ArcadeSystems.all.size) "Every emulator is on this phone. Add games and play."
                    else "Emulators normally come built in. Missing ones download once (0.1–16 MB each) and stay on the phone.", color = Neo.muted, fontSize = 13.sp)
                if (readyCount < ArcadeSystems.all.size) Button(onClick = {
                    all = true
                    scope.launch { val (ok, bad) = ArcadeCores.downloadAll(ctx); all = false; rev++; changed()
                        com.nizam.app.ui.components.Feedback.show(if (bad == 0) "Downloaded $ok emulators" else "Downloaded $ok, $bad failed. Check the internet and try again.") }
                }, Modifier.padding(top = 12.dp), enabled = !all, colors = ButtonDefaults.buttonColors(containerColor = Neo.violet)) {
                    Icon(Icons.Filled.Download, null); Text(if (all) "  Downloading…" else "  Download all missing")
                }
            }
        }
        ArcadeSystems.all.groupBy { it.family }.forEach { (family, list) ->
            item("fam-$family") { Text(family.ifBlank { "Other" }.uppercase(), Modifier.padding(top = 10.dp, start = 4.dp), color = Neo.muted, fontSize = 12.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold) }
            items(list, key = { it.id }) { s ->
                val ready = remember(rev, dl.size) { ArcadeSystems.coreReady(ctx, s) }
                val source = remember(rev, dl.size) { ArcadeCores.source(ctx, s) }
                val p = dl[s.id]
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Neo.card).border(1.dp, Neo.line, RoundedCornerShape(18.dp)).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(Color(s.color)), contentAlignment = Alignment.Center) {
                            Text(s.short.take(4), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black)
                        }
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(s.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
                            Text(if (ready) "Ready · $source" else "Not installed", fontSize = 12.sp, color = if (ready) Neo.green else Neo.pink)
                            Text(s.exts.take(5).joinToString(" ") { ".$it" }, fontSize = 11.sp, color = Neo.muted)
                        }
                        when {
                            p != null -> CircularProgressIndicator(progress = { p }, Modifier.size(28.dp), color = Neo.cyan, strokeWidth = 3.dp)
                            !ready -> FilledTonalButton(onClick = { scope.launch { ArcadeCores.download(ctx, s).onFailure { com.nizam.app.ui.components.Feedback.show("Couldn't download: ${it.message}") }; rev++; changed() } },
                                contentPadding = PaddingValues(horizontal = 12.dp)) { Text("Get") }
                            source == "Downloaded" -> TextButton(onClick = { ArcadeCores.remove(ctx, s); rev++ }) { Text("Remove", color = Neo.muted) }
                        }
                    }
                    if (s.note.isNotEmpty()) Text(s.note, Modifier.padding(top = 6.dp), fontSize = 12.sp, color = Neo.muted)
                    if (s.bios.isNotEmpty()) Row(Modifier.padding(top = 8.dp).horizontalScrollSimple(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        s.bios.forEach { b ->
                            val has = File(ArcadeLibrary.systemDir(ctx), b.file).exists()
                            Text((if (has) "✓ " else if (b.required) "! " else "○ ") + b.file.substringAfterLast('/'),
                                Modifier.clip(CircleShape).background(if (has) Neo.green.copy(alpha = 0.15f) else if (b.required) Neo.pink.copy(alpha = 0.15f) else Neo.bg2)
                                    .padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 11.sp,
                                color = if (has) Neo.green else if (b.required) Neo.pink else Neo.muted)
                        }
                    }
                }
            }
        }
        item("bios") {
            Spacer(Modifier.height(8.dp))
            Text("System files (BIOS)", fontSize = 17.sp, fontWeight = FontWeight.Bold)
            Text("Some consoles need their own startup file (marked !), others just run better with one (○). Copy them from your own console; names must match exactly.",
                fontSize = 13.sp, color = Neo.muted)
            OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, Modifier.padding(top = 8.dp)) { Icon(Icons.Filled.UploadFile, null); Spacer(Modifier.width(8.dp)); Text("Add system files", color = Neo.ink) }
        }
        items(files, key = { "f-$it" }) { f -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(f, Modifier.weight(1f), fontSize = 14.sp)
            IconButton(onClick = { File(ArcadeLibrary.systemDir(ctx), f).delete(); rev++ }) { Icon(Icons.Filled.Delete, "Remove", tint = Neo.muted) }
        } }
        item("legal") {
            Text("Play games you own or that their makers share for free. Emulators are open-source cores from the libretro project.", Modifier.padding(top = 10.dp), fontSize = 12.sp, color = Neo.muted)
        }
    }
}

@Composable
private fun Modifier.horizontalScrollSimple(): Modifier = this.then(Modifier.horizontalScroll(rememberScrollState()))
