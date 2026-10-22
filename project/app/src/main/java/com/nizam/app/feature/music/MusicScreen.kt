package com.nizam.app.feature.music

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import com.nizam.app.ui.design.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.nizam.app.core.ChipRow
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.components.Thinking
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun time(ms: Long): String { val s = (ms / 1000).coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }

/** Album art: web images through Coil, local art decoded off the main thread, a soft note otherwise. */
@Composable
fun TrackArt(t: Track?, modifier: Modifier, corner: Int = 8) {
    val c = LocalContext.current
    var bmp by remember(t?.id, t?.art) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(t?.id, t?.art) { com.nizam.app.ui.design.guard { if (t != null && !t.art.startsWith("http")) bmp = withContext(Dispatchers.IO) { MusicArt.bitmap(c, t) } } }
    Box(modifier.clip(RoundedCornerShape(corner.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        when {
            t != null && t.art.startsWith("http") -> AsyncImage(t.art, t.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            bmp != null -> Image(bmp!!.asImageBitmap(), t?.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else -> Icon(Icons.Filled.MusicNote, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
        }
    }
}

/** Shelves fetched from the internet are kept for half an hour, so flipping tabs doesn't refetch. */
object MusicCache {
    private val data = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, Any>>()
    @Suppress("UNCHECKED_CAST")
    suspend fun <T : Any> get(key: String, ttlMs: Long = 30 * 60_000L, load: suspend () -> T): T {
        data[key]?.let { (at, v) -> if (System.currentTimeMillis() - at < ttlMs) return v as T }
        return load().also { data[key] = System.currentTimeMillis() to it }
    }
}

/** A page of songs: an album, artist, mix, playlist or genre — loaded now or from the internet. */
data class Opened(val title: String, val subtitle: String, val tracks: List<Track>? = null, val art: String = "",
                  val colour: Long? = null, val load: (suspend () -> List<Track>)? = null)

private val GENRE_COLOURS = listOf(0xFFE13300, 0xFF1E3264, 0xFF8D67AB, 0xFF27856A, 0xFFE8115B, 0xFF148A08, 0xFFBA5D07, 0xFF503750,
    0xFF0D73EC, 0xFFAF2896, 0xFF477D95, 0xFFDC148C, 0xFF7358FF, 0xFF8C1932, 0xFF006450, 0xFFE91429, 0xFF777777, 0xFFB49BC8)

private fun greeting() = when (java.time.LocalTime.now().hour) { in 5..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }

@Composable
fun MusicScreen() {
    val c = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(c, perm) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var reload by remember { mutableIntStateOf(0) }
    var tracks by remember { mutableStateOf<List<Track>?>(null) }
    LaunchedEffect(granted, reload) { com.nizam.app.ui.design.guard { tracks = runCatching { MusicLibrary.all(c) }.getOrElse { emptyList() } } }
    var tab by remember { mutableStateOf("Home") }
    var nowPlaying by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Track?>(null) }
    var opened by remember { mutableStateOf<Opened?>(null) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Home" to Icons.Filled.Home, "Search" to Icons.Filled.Search, "Library" to Icons.Filled.LibraryMusic,
                    "Radio" to Icons.Filled.Radio, "More" to Icons.Filled.Add).forEach { (name, icon) ->
                    val on = tab == name && opened == null
                    Row(Modifier.clip(RoundedCornerShape(50)).background(if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(role = Role.Tab) { tab = name; opened = null }.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, null, Modifier.size(18.dp), tint = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(" $name", style = MaterialTheme.typography.labelLarge, color = if (on) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
            Column(Modifier.padding(horizontal = 18.dp)) { DownloadBar() }
            val all = tracks
            if (all == null) { Thinking(label = "Reading your music", modifier = Modifier.padding(18.dp)); return@Column }
            val onMenu: (Track) -> Unit = { editing = it }
            val openPlayer = { nowPlaying = true }
            Box(Modifier.weight(1f)) {
                val o = opened
                when {
                    o != null -> OpenedPage(o, { opened = null }, onMenu, openPlayer)
                    tab == "Home" -> HomeTab(all, granted, { ask.launch(perm) }, openPlayer, { opened = it }, onMenu)
                    tab == "Search" -> SearchTab(all, openPlayer, { opened = it }, onMenu)
                    tab == "Library" -> LibraryTab(all, { opened = it }, onMenu) { reload++ }
                    tab == "Radio" -> RadioTab(openPlayer)
                    else -> DiscoverTab { reload++ }
                }
            }
            MiniPlayer { nowPlaying = true }
        }
        AnimatedVisibility(nowPlaying, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
            NowPlaying(onClose = { nowPlaying = false }, onEdit = { editing = it }, onOpen = { opened = it; nowPlaying = false })
        }
        editing?.let { t -> TrackSheet(t, onClose = { editing = null }, onChanged = { reload++ }) }
    }
    BackHandler(nowPlaying || opened != null) { if (nowPlaying) nowPlaying = false else opened = null }
}

// ── Home ─────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun <T : Any> rememberOnline(key: String, load: suspend () -> T): T? {
    var v by remember(key) { mutableStateOf<T?>(null) }
    LaunchedEffect(key) { com.nizam.app.ui.design.guard { v = runCatching { MusicCache.get(key) { load() } }.getOrNull() } }
    return v
}

@Composable
private fun HomeTab(all: List<Track>, granted: Boolean, askAccess: () -> Unit, openPlayer: () -> Unit, open: (Opened) -> Unit, onMenu: (Track) -> Unit) {
    val c = LocalContext.current
    val byId = remember(all) { all.associateBy { it.id } }
    val recent = remember(all) { MusicLibrary.recent(c).mapNotNull { byId[it] }.take(15) }
    val liked = remember(all) { MusicLibrary.liked(c).mapNotNull { byId[it] } }
    val mixes = remember(all) { Mixes.build(c, all) }
    val albums = remember(all) { val plays = MusicLibrary.plays(c)
        all.filter { it.album.isNotBlank() }.groupBy { it.album }.entries.sortedByDescending { e -> e.value.sumOf { plays[it.id] ?: 0 } }.take(12) }
    val trending = rememberOnline("trending") { Audius.trending() }
    val charts = rememberOnline("charts") { Charts.top() }
    val playlists = rememberOnline("au-playlists") { Audius.trendingPlaylists() }
    val radio = rememberOnline("radio-pk") { Radio.top("PK") }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Text(greeting(), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
            if (!granted) Row(Modifier.padding(horizontal = 18.dp, vertical = 4.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(role = Role.Button, onClick = askAccess).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.LibraryMusic, null); Text("  Allow access to add the music on your phone", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            }
            // quick grid, like Spotify's top of Home
            val quick = buildList {
                if (liked.isNotEmpty()) add(Opened("Liked Songs", "${liked.size} songs", liked, colour = 0xFF4B2FD6))
                mixes.take(3).forEach { add(Opened(it.title, it.subtitle, it.tracks, colour = it.colour)) }
                albums.take(4).forEach { (name, l) -> add(Opened(name, l.first().artist, l.sortedBy { it.trackNo }, art = l.first().art)) }
            }.take(6)
            if (quick.isNotEmpty()) Column(Modifier.padding(horizontal = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                quick.chunked(2).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { q -> Row(Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(role = Role.Button) { open(q) }, verticalAlignment = Alignment.CenterVertically) {
                        Cover(q, Modifier.size(56.dp), 0)
                        Text(q.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 10.dp))
                    } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                } }
            }
            if (all.isNotEmpty()) Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { MusicPlayback.play(c, all.shuffled()); openPlayer() }) { Icon(Icons.Filled.Shuffle, null, Modifier.size(18.dp)); Text("  Shuffle all ${all.size}") }
            }
        }
        if (mixes.isNotEmpty()) item { CardShelf("Made for you", mixes.map { Opened(it.title, it.subtitle, it.tracks, colour = it.colour) }, open) }
        trending?.takeIf { it.isNotEmpty() }?.let { list -> item { Shelf("Trending now", "Full songs from independent artists", list.take(20)) { i -> MusicPlayback.play(c, list, i); openPlayer() } } }
        item {
            SectionTitle("Browse genres", null)
            LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(Audius.GENRES.size) { i -> val g = Audius.GENRES[i]
                    Box(Modifier.size(width = 130.dp, height = 70.dp).clip(RoundedCornerShape(10.dp)).background(Color(GENRE_COLOURS[i % GENRE_COLOURS.size]))
                        .clickable(role = Role.Button) { open(Opened(g, "Trending $g this week", colour = GENRE_COLOURS[i % GENRE_COLOURS.size], load = { Audius.trending(g) })) }
                        .padding(10.dp)) { Text(g, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall) }
                }
            }
        }
        if (recent.isNotEmpty()) item { Shelf("Recently played", null, recent) { i -> MusicPlayback.play(c, recent, i); openPlayer() } }
        playlists?.takeIf { it.isNotEmpty() }?.let { list -> item { CardShelf("Popular playlists", list.take(15).map { p ->
            Opened(p.title, p.subtitle, art = p.art, load = { Audius.playlistTracks(p.id) }) }, open) } }
        charts?.takeIf { it.isNotEmpty() }?.let { list -> item { Shelf("Global Top 50", "30-second previews · tap ⋯ to find the full song", list) { i -> MusicPlayback.play(c, list, i); openPlayer() } } }
        if (albums.isNotEmpty()) item { CardShelf("Your albums", albums.map { (n, l) -> Opened(n, l.first().artist, l.sortedBy { it.trackNo }, art = l.first().art) }, open) }
        radio?.takeIf { it.isNotEmpty() }?.let { list -> item { Shelf("Popular radio in Pakistan", "Live", list.take(15)) { i -> MusicPlayback.play(c, listOf(list[i])); openPlayer() } } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SectionTitle(title: String, sub: String?) {
    Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/** Square cover for a page: its art, or a coloured tile with the title on it, Spotify-mix style. */
@Composable
private fun Cover(o: Opened, modifier: Modifier, corner: Int = 8) {
    val first = o.tracks?.firstOrNull()
    when {
        o.colour != null -> Box(modifier.clip(RoundedCornerShape(corner.dp)).background(Brush.linearGradient(listOf(Color(o.colour), Color(o.colour).copy(alpha = 0.55f)))),
            contentAlignment = Alignment.BottomStart) {
            Text(o.title, color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp, lineHeight = 14.sp, maxLines = 3, modifier = Modifier.padding(6.dp))
        }
        o.art.startsWith("http") -> AsyncImage(o.art, o.title, contentScale = ContentScale.Crop, modifier = modifier.clip(RoundedCornerShape(corner.dp)))
        else -> TrackArt(first?.let { if (o.art.isNotBlank()) it.copy(art = o.art) else it }, modifier, corner)
    }
}

@Composable
private fun CardShelf(title: String, cards: List<Opened>, open: (Opened) -> Unit) {
    SectionTitle(title, null)
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(cards.size) { i -> val o = cards[i]
            Column(Modifier.width(144.dp).clickable(role = Role.Button) { open(o) }) {
                Cover(o, Modifier.size(144.dp), 10)
                Text(o.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text(o.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Shelf(title: String, sub: String?, list: List<Track>, onPlay: (Int) -> Unit) {
    SectionTitle(title, sub)
    LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        items(list.size) { i -> val t = list[i]
            Column(Modifier.width(132.dp).clickable(role = Role.Button) { onPlay(i) }) {
                TrackArt(t, Modifier.size(132.dp), if (t.source == "radio") 66 else 10)
                Text(t.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                Text(t.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

// ── A page: album, artist, mix, playlist, genre ─────────────────────────────────────────────────

@Composable
private fun OpenedPage(o: Opened, onBack: () -> Unit, onMenu: (Track) -> Unit, openPlayer: () -> Unit) {
    val c = LocalContext.current
    var list by remember(o) { mutableStateOf(o.tracks) }
    var failed by remember(o) { mutableStateOf(false) }
    LaunchedEffect(o) { com.nizam.app.ui.design.guard { if (list == null) o.load?.let { l -> list = runCatching { MusicCache.get("page:${o.title}:${o.subtitle}") { l() } }.getOrElse { failed = true; emptyList() } } } }
    val shown = list
    val top = o.colour?.let { Color(it) } ?: MaterialTheme.colorScheme.primary
    LazyColumn(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top.copy(alpha = 0.35f), MaterialTheme.colorScheme.background, MaterialTheme.colorScheme.background)))) {
        item {
            Icon(Icons.Filled.KeyboardArrowDown, "Back", Modifier.padding(12.dp).rotate(90f).size(30.dp).clip(CircleShape).clickable(onClick = onBack))
            Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Cover(o.copy(tracks = shown), Modifier.size(210.dp).shadow(16.dp, RoundedCornerShape(10.dp)), 10)
            }
            Text(o.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 18.dp, end = 18.dp, top = 16.dp))
            Text(listOfNotNull(o.subtitle.ifBlank { null }, shown?.let { l -> "${l.size} songs" + l.sumOf { it.durationMs }.takeIf { it > 0 }?.let { " · ${time(it)}" }.orEmpty() })
                .joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 18.dp))
            Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Shuffle, "Shuffle", Modifier.size(40.dp).clip(CircleShape).clickable { shown?.takeIf { it.isNotEmpty() }?.let { MusicPlayback.play(c, it.shuffled()); openPlayer() } }.padding(8.dp))
                Spacer(Modifier.weight(1f))
                Box(Modifier.size(56.dp).clip(CircleShape).background(Color(0xFF1DB954)).clickable { shown?.takeIf { it.isNotEmpty() }?.let { MusicPlayback.play(c, it); openPlayer() } },
                    contentAlignment = Alignment.Center) { Icon(Icons.Filled.PlayArrow, "Play", tint = Color.Black, modifier = Modifier.size(32.dp)) }
            }
            when {
                shown == null -> Thinking(label = "Loading", modifier = Modifier.padding(18.dp))
                shown.isEmpty() -> Text(if (failed) "Couldn't load this — check your connection." else "Nothing here yet.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(18.dp))
            }
        }
        shown?.let { l -> itemsIndexed(l, key = { i, t -> "$i${t.id}" }) { i, t ->
            Box(Modifier.padding(horizontal = 18.dp)) { TrackRow(t, onClick = { MusicPlayback.play(c, l, i); openPlayer() }, onMenu = { onMenu(t) }) } } }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ── Search ───────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun SearchTab(all: List<Track>, openPlayer: () -> Unit, open: (Opened) -> Unit, onMenu: (Track) -> Unit) {
    val c = LocalContext.current
    var q by remember { mutableStateOf("") }
    var online by remember { mutableStateOf<List<Track>?>(null) }
    var artists by remember { mutableStateOf<List<MusicCard>>(emptyList()) }
    var stations by remember { mutableStateOf<List<Track>>(emptyList()) }
    LaunchedEffect(q) { com.nizam.app.ui.design.guard {
        online = null; artists = emptyList(); stations = emptyList()
        if (q.trim().length < 2) return@LaunchedEffect
        kotlinx.coroutines.delay(350)
        kotlinx.coroutines.coroutineScope {
            launch { online = runCatching { Audius.search(q.trim()) }.getOrElse { emptyList() } }
            launch { artists = runCatching { Audius.artists(q.trim()).take(6) }.getOrElse { emptyList() } }
            launch { stations = runCatching { Radio.search(q.trim()).take(5) }.getOrElse { emptyList() } }
        }
    } }
    val mine = remember(q, all) { if (q.isBlank()) emptyList() else all.filter { it.title.contains(q, true) || it.artist.contains(q, true) || it.album.contains(q, true) }.take(8) }
    val myArtists = remember(q, all) { if (q.isBlank()) emptyList() else all.map { it.artist }.distinct().filter { it.contains(q, true) }.take(4) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        item {
            Text("Search", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
            OutlinedTextField(q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("What do you want to listen to?") })
        }
        if (q.isBlank()) {
            item { Text("Browse all", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 10.dp)) }
            val tiles = Audius.GENRES.mapIndexed { i, g -> Opened(g, "Trending $g this week", colour = GENRE_COLOURS[i % GENRE_COLOURS.size], load = { Audius.trending(g) }) } +
                Opened("New this month", "Fresh uploads rising fast", colour = 0xFF1DB954, load = { Audius.trending(time = "month") }) +
                Opened("All-time hits", "Audius' most played", colour = 0xFFE91429, load = { Audius.trending(time = "allTime") })
            items(tiles.chunked(2).size) { r -> val row = tiles.chunked(2)[r]
                Row(Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { o -> Box(Modifier.weight(1f).height(92.dp).clip(RoundedCornerShape(10.dp)).background(Color(o.colour ?: 0xFF444444))
                        .clickable(role = Role.Button) { open(o) }.padding(12.dp)) {
                        Text(o.title, color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            if (mine.isNotEmpty() || myArtists.isNotEmpty()) item { Label("In your library") }
            items(myArtists.size) { i -> val a = myArtists[i]
                Row(Modifier.fillMaxWidth().clickable { open(Opened(a, "Artist", all.filter { it.artist == a })) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TrackArt(all.firstOrNull { it.artist == a }, Modifier.size(48.dp), 24); Text("  $a", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("Artist", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            items(mine.size) { i -> TrackRow(mine[i], onClick = { MusicPlayback.play(c, mine, i); openPlayer() }, onMenu = { onMenu(mine[i]) }) }
            if (artists.isNotEmpty()) item {
                Label("Artists")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(artists.size) { i -> val a = artists[i]
                        Column(Modifier.width(96.dp).clickable { open(Opened(a.title, a.subtitle, art = a.art, load = { Audius.artistTracks(a.id) })) },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(a.art, a.title, contentScale = ContentScale.Crop, modifier = Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
                            Text(a.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
            item { Label("Songs") }
            val on = online
            if (on == null) item { Thinking(label = "Searching") }
            else if (on.isEmpty()) item { Text("No songs found online. Try More › Internet Archive or Jamendo.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
            else items(on.size) { i -> TrackRow(on[i], onClick = { MusicPlayback.play(c, on, i); openPlayer() }, onMenu = { onMenu(on[i]) }) }
            if (stations.isNotEmpty()) {
                item { Label("Radio stations") }
                items(stations.size) { i -> TrackRow(stations[i], onClick = { MusicPlayback.play(c, listOf(stations[i])); openPlayer() }, onMenu = { onMenu(stations[i]) }) }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ── Library ──────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun LibraryTab(all: List<Track>, open: (Opened) -> Unit, onMenu: (Track) -> Unit, changed: () -> Unit) {
    val c = LocalContext.current
    var filter by remember { mutableStateOf("Playlists") }
    var lists by remember { mutableStateOf(MusicLibrary.playlists(c)) }
    var name by remember { mutableStateOf("") }
    var sortAz by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Your Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text(if (sortAz) "A–Z" else "Recent", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(50)).clickable { sortAz = !sortAz }.padding(8.dp))
        }
        ChipRow(listOf("Playlists", "Songs", "Albums", "Artists", "Downloaded"), filter, { filter = it }, modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp))
        when (filter) {
            "Songs" -> TrackList(if (sortAz) all.sortedBy { it.title.lowercase() } else all, null, null, onMenu, changed)
            "Downloaded" -> TrackList(all.filter { it.source != "device" && !it.uri.startsWith("http") }, "Downloaded", null, onMenu, changed)
            "Albums" -> Groups(all.groupBy { it.album.ifBlank { "Unknown album" } }) { a -> open(Opened(a, all.firstOrNull { it.album == a }?.artist.orEmpty(),
                all.filter { it.album.ifBlank { "Unknown album" } == a }.sortedBy { it.trackNo })) }
            "Artists" -> Groups(all.groupBy { it.artist.ifBlank { "Unknown artist" } }) { a -> open(Opened(a, "Artist", all.filter { it.artist.ifBlank { "Unknown artist" } == a })) }
            else -> LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
                item {
                    val liked = MusicLibrary.liked(c).let { ids -> all.filter { it.id in ids } }
                    LibraryRow(Opened("Liked Songs", "Playlist · ${liked.size} songs", liked, colour = 0xFF4B2FD6)) { open(it) }
                    val downloaded = all.filter { it.source != "device" && !it.uri.startsWith("http") }
                    if (downloaded.isNotEmpty()) LibraryRow(Opened("Downloaded", "${downloaded.size} songs on this phone", downloaded, colour = 0xFF1DB954)) { open(it) }
                    val radios = Radio.favourites(c)
                    if (radios.isNotEmpty()) LibraryRow(Opened("Favourite stations", "${radios.size} live stations", radios, colour = 0xFFE8115B)) { open(it) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                        OutlinedTextField(name, { name = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("New playlist name") })
                        Spacer(Modifier.width(8.dp))
                        Button(enabled = name.isNotBlank(), onClick = {
                            MusicLibrary.savePlaylists(c, lists + Playlist(System.currentTimeMillis(), name.trim(), emptyList()))
                            lists = MusicLibrary.playlists(c); name = ""; Feedback.show("Playlist created — add songs from any song's ⋯ menu")
                        }) { Text("Create") }
                    }
                }
                val shownLists = if (sortAz) lists.sortedBy { it.name.lowercase() } else lists.sortedByDescending { it.id }
                items(shownLists.size) { i -> val p = shownLists[i]
                    val songs = p.trackIds.mapNotNull { id -> all.firstOrNull { it.id == id } }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { LibraryRow(Opened(p.name, "Playlist · ${songs.size} songs", songs)) { open(it) } }
                        Icon(Icons.Filled.MoreVert, "Delete playlist", Modifier.clip(CircleShape).clickable {
                            MusicLibrary.savePlaylists(c, lists - p); lists = MusicLibrary.playlists(c); Feedback.show("Deleted ${p.name}") }.padding(8.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun LibraryRow(o: Opened, open: (Opened) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open(o) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Cover(o, Modifier.size(60.dp), 6)
        Column(Modifier.padding(start = 12.dp)) {
            Text(o.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(o.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

// ── Radio ────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun RadioTab(openPlayer: () -> Unit) {
    val c = LocalContext.current
    var q by remember { mutableStateOf("") }
    var tag by remember { mutableStateOf("Pakistan") }
    var list by remember { mutableStateOf<List<Track>?>(null) }
    var favs by remember { mutableStateOf(Radio.favourites(c)) }
    LaunchedEffect(q, tag) { com.nizam.app.ui.design.guard {
        list = null
        if (q.isNotBlank()) kotlinx.coroutines.delay(400)
        list = runCatching { when {
            q.isNotBlank() -> Radio.search(q.trim())
            tag == "Pakistan" -> MusicCache.get("radio-pk") { Radio.top("PK") }
            tag == "World" -> MusicCache.get("radio-world") { Radio.top() }
            else -> MusicCache.get("radio-$tag") { Radio.tag(tag) }
        } }.getOrElse { emptyList() }
    } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        item {
            Text("Radio", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
            OutlinedTextField(q, { q = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Find a station — FM 100, BBC, Quran…") })
            if (q.isBlank()) ChipRow(listOf("Pakistan", "World") + Radio.TAGS, tag, { tag = it }, modifier = Modifier.padding(vertical = 8.dp))
            if (favs.isNotEmpty() && q.isBlank()) {
                Label("Your stations")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(favs.size) { i -> val s = favs[i]
                        Column(Modifier.width(92.dp).clickable { MusicPlayback.play(c, listOf(s)); openPlayer() }, horizontalAlignment = Alignment.CenterHorizontally) {
                            TrackArt(s, Modifier.size(84.dp), 42)
                            Text(s.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        val l = list
        if (l == null) item { Thinking(label = "Tuning in") }
        else if (l.isEmpty()) item { Text("No stations found.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        else items(l.size) { i -> val s = l[i]
            val fav = favs.any { it.id == s.id }
            Row(Modifier.fillMaxWidth().clickable { MusicPlayback.play(c, listOf(s)); openPlayer() }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TrackArt(s, Modifier.size(52.dp), 26)
                Column(Modifier.weight(1f).padding(start = 12.dp)) {
                    Text(s.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        color = if (MusicPlayback.current?.id == s.id) Color(0xFF1DB954) else MaterialTheme.colorScheme.onSurface)
                    Text(s.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favourite", tint = if (fav) Color(0xFF1DB954) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clip(CircleShape).clickable { Radio.toggleFavourite(c, s); favs = Radio.favourites(c) }.padding(8.dp))
            }
        }
        item { Text("Stations from radio-browser.info, a free community directory.", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp)) }
    }
}

// ── Lists ────────────────────────────────────────────────────────────────────────────────────────

@Composable
private fun TrackList(list: List<Track>, title: String?, onBack: (() -> Unit)?, onMenu: (Track) -> Unit, changed: () -> Unit) {
    val c = LocalContext.current
    var query by remember { mutableStateOf("") }
    val shown = remember(list, query) { if (query.isBlank()) list else list.filter {
        it.title.contains(query, true) || it.artist.contains(query, true) || it.album.contains(query, true) } }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        item {
            if (onBack != null) Text("‹ Back", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable(role = Role.Button) { onBack() }.padding(vertical = 8.dp))
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    TrackArt(list.firstOrNull(), Modifier.size(96.dp), 12)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2)
                        Text("${list.size} songs · ${time(list.sumOf { it.durationMs })}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(enabled = list.isNotEmpty(), onClick = { MusicPlayback.play(c, list) }) { Text("Play") }
                    OutlinedButton(enabled = list.isNotEmpty(), onClick = { MusicPlayback.play(c, list.shuffled()) }) { Text("Shuffle") }
                }
            } else OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Search ${list.size} songs") })
        }
        itemsIndexed(shown, key = { _, t -> t.id }) { i, t -> TrackRow(t, onClick = { MusicPlayback.play(c, shown, i) }, onMenu = { onMenu(t) }) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
fun TrackRow(t: Track, onClick: () -> Unit, onMenu: () -> Unit) {
    val playing = MusicPlayback.current?.id == t.id
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TrackArt(t, Modifier.size(48.dp), 6)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (playing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            Text(listOf(t.artist, t.album).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onMenu) { Icon(Icons.Filled.MoreVert, "More", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
private fun Groups(groups: Map<String, List<Track>>, open: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
        val keys = groups.keys.sortedBy { it.lowercase() }
        items(keys.size) { i -> val k = keys[i]; val list = groups.getValue(k)
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { open(k) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TrackArt(list.first(), Modifier.size(56.dp), 8)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(k, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${list.size} songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// ── Discover: free catalogues, any link, your files, and the big services ───────────────────────

@Composable
private fun DiscoverTab(changed: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var source by remember { mutableStateOf("Internet Archive") }
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Track>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var inside by remember { mutableStateOf<Track?>(null) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch { val n = MusicSources.import(c, uris); Feedback.show("Imported $n songs"); changed() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Group(Modifier.padding(top = 8.dp)) {
            Text("Your files", style = MaterialTheme.typography.titleMedium)
            Text("MP3, M4A, FLAC, OGG, WAV — copied into Atlas with their tags and art.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { importer.launch(arrayOf("audio/*")) }, modifier = Modifier.padding(top = 8.dp)) { Text("Import music") }
        }
        Label("Find music")
        ChipRow(listOf("Internet Archive", "Jamendo", "Any link", "Streaming apps"), source, { source = it; results = null; inside = null })
        if (source == "Jamendo") {
            var key by remember { mutableStateOf(MusicSources.jamendoKey(c)) }
            OutlinedTextField(key, { key = it; MusicSources.setJamendoKey(c, it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("Jamendo client id (free at developer.jamendo.com)") })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text(when (source) {
                "Any link" -> "https://…/song.mp3 or a .m3u playlist"; "Streaming apps" -> "Song, artist or album"; else -> "Artist, song, genre…" }) })
            Spacer(Modifier.width(8.dp))
            Button(enabled = q.isNotBlank() && !busy, onClick = {
                inside = null
                if (source == "Streaming apps") return@Button
                busy = true
                scope.launch {
                    results = runCatching { when (source) {
                        "Jamendo" -> MusicSources.jamendo(c, q)
                        "Any link" -> MusicSources.fromLink(q.trim())
                        else -> MusicSources.archive(q)
                    } }.onFailure { Feedback.failed("Searching", it) }.getOrElse { emptyList() }
                    busy = false
                }
            }) { Text(if (source == "Any link") "Open" else "Search") }
        }
        if (source == "Streaming apps") {
            Text("Search in the service's own app, where your account and subscription live.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
            MusicSources.STREAMING.forEach { (name, pattern) ->
                Text(name, style = MaterialTheme.typography.bodyLarge, color = if (q.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().clickable(enabled = q.isNotBlank(), role = Role.Button) {
                        runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pattern.format(Uri.encode(q)))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    }.padding(vertical = 10.dp))
            }
        }
        if (busy) Thinking(label = "Searching")
        val shown = inside?.let { null } ?: results
        inside?.let { item ->
            Text("‹ Results", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clickable { inside = null }.padding(vertical = 8.dp))
            var files by remember(item.id) { mutableStateOf<List<Track>?>(null) }
            LaunchedEffect(item.id) { com.nizam.app.ui.design.guard { files = runCatching { MusicSources.archiveFiles(item) }.getOrElse { emptyList() } } }
            val fs = files
            if (fs == null) Thinking(label = "Opening ${item.title}")
            else {
                if (fs.isEmpty()) Text("No MP3 files in this item.", style = MaterialTheme.typography.bodyMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (fs.isNotEmpty()) Button(onClick = { MusicPlayback.play(c, fs) }) { Text("Play all") }
                    if (fs.isNotEmpty()) OutlinedButton(onClick = { fs.forEach { MusicSources.download(c, it) }; Feedback.show("Downloading ${fs.size} songs") }) { Text("Download all") }
                }
                fs.forEachIndexed { i, t -> OnlineRow(t, onPlay = { MusicPlayback.play(c, fs, i) }) }
            }
        }
        shown?.let { list ->
            if (list.isEmpty()) Text("Nothing found.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.forEachIndexed { i, t ->
                if (t.source == "archive" && t.uri.isBlank()) OnlineRow(t, onPlay = { inside = t }, openLabel = "Open")
                else OnlineRow(t, onPlay = { MusicPlayback.play(c, list.filter { it.uri.isNotBlank() }, list.filter { it.uri.isNotBlank() }.indexOf(t).coerceAtLeast(0)) })
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun OnlineRow(t: Track, onPlay: () -> Unit, openLabel: String = "Play") {
    val c = LocalContext.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        TrackArt(t, Modifier.size(48.dp), 6)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = onPlay)) {
            Text(t.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(t.artist, if (t.durationMs > 0) time(t.durationMs) else "").filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(openLabel, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clickable(role = Role.Button, onClick = onPlay).padding(8.dp))
        if (t.uri.startsWith("http")) Text("Save", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clickable(role = Role.Button) { MusicSources.download(c, t); Feedback.show("Downloading ${t.title}") }.padding(8.dp))
    }
}

// ── Mini player ─────────────────────────────────────────────────────────────────────────────────

/** The art's main colour — for web art too, via Coil. */
@Composable
private fun rememberTint(t: Track?): Color? {
    val c = LocalContext.current
    var tint by remember(t?.id, t?.art) { mutableStateOf<Color?>(null) }
    LaunchedEffect(t?.id, t?.art) { com.nizam.app.ui.design.guard {
        tint = withContext(Dispatchers.IO) {
            val bmp = if (t != null && t.art.startsWith("http")) runCatching {
                (coil.ImageLoader(c).execute(coil.request.ImageRequest.Builder(c).data(t.art).size(64).allowHardware(false).build()).drawable
                    as? android.graphics.drawable.BitmapDrawable)?.bitmap }.getOrNull() else MusicArt.bitmap(c, t, 64)
            MusicArt.tint(bmp)?.let { Color(it) }
        }
    } }
    return tint
}

@Composable
private fun MiniPlayer(open: () -> Unit) {
    val c = LocalContext.current
    val t = MusicPlayback.current ?: return
    val tint = rememberTint(t)
    val bg = (tint ?: MaterialTheme.colorScheme.surfaceVariant).let { Color(androidx.core.graphics.ColorUtils.blendARGB(it.toArgb(), android.graphics.Color.BLACK, 0.35f)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp).clip(RoundedCornerShape(10.dp)).background(bg).clickable(role = Role.Button, onClick = open)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            TrackArt(t, Modifier.size(42.dp), 6)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (MusicPlayback.isLive) "● Live · ${t.artist}" else t.artist, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f), maxLines = 1)
            }
            var liked by remember(t.id) { mutableStateOf(t.id in MusicLibrary.liked(c)) }
            if (!MusicPlayback.isLive) IconButton(onClick = { MusicLibrary.toggleLike(c, t.id); liked = !liked }) {
                Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Like", tint = if (liked) Color(0xFF1DB954) else Color.White)
            }
            IconButton(onClick = { MusicPlayback.toggle() }) {
                Icon(if (MusicPlayback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (MusicPlayback.isPlaying) "Pause" else "Play", tint = Color.White)
            }
        }
        val d = MusicPlayback.durationMs
        if (!MusicPlayback.isLive) LinearProgressIndicator(progress = { if (d > 0) MusicPlayback.positionMs / d.toFloat() else 0f },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(2.dp), color = Color.White, trackColor = Color.White.copy(alpha = 0.25f))
    }
}

// ── Now playing ─────────────────────────────────────────────────────────────────────────────────

/** Lyrics and queue sit on the dark now-playing backdrop whatever the app theme is. */
@Composable
private fun OnDark(content: @Composable () -> Unit) = MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(onSurface = Color.White,
    onSurfaceVariant = Color.White.copy(alpha = 0.7f), primary = Color(0xFF1DB954)), typography = MaterialTheme.typography, content = content)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun NowPlaying(onClose: () -> Unit, onEdit: (Track) -> Unit, onOpen: (Opened) -> Unit) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    val t = MusicPlayback.current
    var pane by remember { mutableStateOf("Art") }
    var extras by remember { mutableStateOf(false) }
    val tint = rememberTint(t)
    val top = tint?.let { Color(androidx.core.graphics.ColorUtils.blendARGB(it.toArgb(), android.graphics.Color.BLACK, 0.25f)) } ?: Color(0xFF3A3A3A)
    val fg = Color.White
    val artScale by androidx.compose.animation.core.animateFloatAsState(if (MusicPlayback.isPlaying || MusicPlayback.isLive) 1f else 0.86f,
        androidx.compose.animation.core.spring(dampingRatio = 0.6f, stiffness = 300f), label = "art")
    Column(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(top, Color(androidx.core.graphics.ColorUtils.blendARGB(top.toArgb(), android.graphics.Color.BLACK, 0.8f)))))
            .clickable(enabled = false) {}.statusBarsPadding().padding(horizontal = 22.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            IconButton(onClick = onClose) { Icon(Icons.Filled.KeyboardArrowDown, "Close", Modifier.size(30.dp), tint = fg) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(when (t?.source) { "radio" -> "LIVE RADIO"; "audius" -> "PLAYING FROM AUDIUS"; "preview" -> "PREVIEW"; else -> "PLAYING FROM YOUR LIBRARY" },
                    style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f), letterSpacing = 1.sp)
                Text(t?.album?.ifBlank { null } ?: t?.artist.orEmpty(), style = MaterialTheme.typography.labelLarge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (t != null) IconButton(onClick = { onEdit(t) }) { Icon(Icons.Filled.MoreVert, "Edit and more", tint = fg) }
        }
        if (t == null) { Text("Nothing playing", Modifier.padding(24.dp), color = fg); return@Column }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (pane) {
                "Lyrics" -> OnDark { LyricsPane(t) }
                "Queue" -> OnDark { QueuePane() }
                else -> TrackArt(t, Modifier.fillMaxWidth().aspectRatio(1f).graphicsLayer { scaleX = artScale; scaleY = artScale }
                    .shadow(24.dp, RoundedCornerShape(12.dp)), if (t.source == "radio") 200 else 12)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 18.dp)) {
            Column(Modifier.weight(1f)) {
                Text(t.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee())
                Text(t.artist, style = MaterialTheme.typography.bodyLarge, color = fg.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable(enabled = t.artist.isNotBlank() && t.source != "radio") {
                        scope.launch { val mine = MusicLibrary.all(c).filter { it.artist == t.artist }
                            onOpen(if (mine.isNotEmpty()) Opened(t.artist, "Artist", mine) else Opened(t.artist, "Artist", load = { Audius.search(t.artist) })) } })
            }
            if (t.source == "radio") {
                var fav by remember(t.id) { mutableStateOf(Radio.favourites(c).any { it.id == t.id }) }
                IconButton(onClick = { fav = Radio.toggleFavourite(c, t) }) { Icon(if (fav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Favourite station",
                    tint = if (fav) Color(0xFF1DB954) else fg) }
            } else {
                var liked by remember(t.id) { mutableStateOf(t.id in MusicLibrary.liked(c)) }
                IconButton(onClick = { MusicLibrary.toggleLike(c, t.id); liked = !liked; Feedback.show(if (liked) "Added to Liked Songs" else "Removed from Liked Songs") }) {
                    Icon(if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, "Like", tint = if (liked) Color(0xFF1DB954) else fg)
                }
            }
        }
        if (t.source == "preview") Row(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(50)).background(fg.copy(alpha = 0.15f)).clickable {
            scope.launch { val r = runCatching { Audius.search("${t.artist} ${t.title}") }.getOrElse { emptyList() }
                if (r.isNotEmpty()) onOpen(Opened("${t.title}", "Full songs found for ${t.artist}", r)) else Feedback.show("Not on Audius — try your library or More › Internet Archive") }
        }.padding(horizontal = 12.dp, vertical = 6.dp)) { Text("30-second preview · Find the full song", style = MaterialTheme.typography.labelMedium, color = fg) }
        if (MusicPlayback.isLive) {
            Row(Modifier.padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFE91429)))
                Text("  LIVE", color = fg, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
            }
        } else {
            var dragging by remember { mutableStateOf(false) }
            var dragTo by remember { mutableFloatStateOf(0f) }
            val d = MusicPlayback.durationMs.coerceAtLeast(1)
            Slider(value = if (dragging) dragTo else (MusicPlayback.positionMs.toFloat() / d).coerceIn(0f, 1f),
                onValueChange = { dragging = true; dragTo = it },
                onValueChangeFinished = { MusicPlayback.seek((dragTo * d).toLong()); dragging = false },
                colors = SliderDefaults.colors(thumbColor = fg, activeTrackColor = fg, inactiveTrackColor = fg.copy(alpha = 0.25f)))
            Row {
                Text(time(if (dragging) (dragTo * d).toLong() else MusicPlayback.positionMs), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                Text("-" + time((MusicPlayback.durationMs - MusicPlayback.positionMs).coerceAtLeast(0)), style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.7f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { MusicPlayback.shuffleOn(!MusicPlayback.shuffle) }) {
                Icon(Icons.Filled.Shuffle, "Shuffle", tint = if (MusicPlayback.shuffle) Color(0xFF1DB954) else fg.copy(alpha = 0.8f))
            }
            IconButton(onClick = { MusicPlayback.previous() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.SkipPrevious, "Previous", Modifier.size(40.dp), tint = fg) }
            Box(Modifier.size(72.dp).clip(CircleShape).background(fg).clickable(role = Role.Button) { MusicPlayback.toggle() }, contentAlignment = Alignment.Center) {
                Icon(if (MusicPlayback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play or pause", Modifier.size(40.dp), tint = Color.Black)
            }
            IconButton(onClick = { MusicPlayback.next() }, modifier = Modifier.size(56.dp)) { Icon(Icons.Filled.SkipNext, "Next", Modifier.size(40.dp), tint = fg) }
            IconButton(onClick = { MusicPlayback.cycleRepeat() }) {
                Icon(if (MusicPlayback.repeat == Player.REPEAT_MODE_ONE) Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Repeat",
                    tint = if (MusicPlayback.repeat != Player.REPEAT_MODE_OFF) Color(0xFF1DB954) else fg.copy(alpha = 0.8f))
            }
        }
        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            listOf("Art", "Lyrics", "Queue").forEach { p ->
                Text(p, style = MaterialTheme.typography.labelLarge, color = if (pane == p) Color.Black else fg,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (pane == p) fg else fg.copy(alpha = 0.12f)).clickable { pane = p }.padding(horizontal = 14.dp, vertical = 7.dp))
            }
            IconButton(onClick = { extras = true }) {
                Icon(if (MusicPlayback.sleepAt > 0 || MusicPlayback.sleepEndOfTrack) Icons.Filled.Bedtime else Icons.Filled.Equalizer, "Sleep timer, speed and sound",
                    tint = if (MusicPlayback.sleepAt > 0 || MusicPlayback.sleepEndOfTrack || MusicFx.preset >= 0) Color(0xFF1DB954) else fg)
            }
            IconButton(onClick = { c.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "${t.title} — ${t.artist}" + if (t.uri.startsWith("http") && t.source != "preview") "\n${t.uri}" else ""), "Share song")) }) {
                Icon(Icons.Filled.Share, "Share", tint = fg)
            }
        }
    }
    if (extras) androidx.compose.material3.ModalBottomSheet(onDismissRequest = { extras = false }) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 30.dp).verticalScroll(rememberScrollState())) {
            Label("Sleep timer")
            val left = ((MusicPlayback.sleepAt - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
            ChipRow(listOf("Off", "15 min", "30 min", "45 min", "1 hour", "End of song"),
                when { MusicPlayback.sleepEndOfTrack -> "End of song"; MusicPlayback.sleepAt > 0 -> "" ; else -> "Off" },
                { v -> when (v) {
                    "Off" -> MusicPlayback.sleepIn(c, 0)
                    "End of song" -> { MusicPlayback.sleepIn(c, 0); MusicPlayback.sleepEndOfTrack = true }
                    else -> MusicPlayback.sleepIn(c, if (v == "1 hour") 60 else v.substringBefore(' ').toInt())
                }; Feedback.show(if (v == "Off") "Sleep timer off" else "Music stops: $v") })
            if (MusicPlayback.sleepAt > 0) Text("Stops in $left min", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Label("Playback speed", Modifier.padding(top = 14.dp))
            ChipRow(listOf("0.75×", "1×", "1.25×", "1.5×", "2×"), "${MusicPlayback.speed.toString().removeSuffix(".0")}×",
                { MusicPlayback.changeSpeed(it.removeSuffix("×").toFloat()) })
            Label("Equaliser", Modifier.padding(top = 14.dp))
            val presets = MusicFx.presets()
            if (presets.isEmpty()) Text("This phone doesn't offer an equaliser to apps.", style = MaterialTheme.typography.bodySmall)
            else ChipRow(listOf("Off") + presets, if (MusicFx.preset < 0) "Off" else presets.getOrElse(MusicFx.preset) { "Off" },
                { v -> MusicFx.use(c, if (v == "Off") -1 else presets.indexOf(v)) })
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                Column(Modifier.weight(1f)) { Text("Bass boost", style = MaterialTheme.typography.titleSmall) }
                androidx.compose.material3.Switch(MusicFx.bassOn, { MusicFx.boost(c, it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Autoplay", style = MaterialTheme.typography.titleSmall)
                    Text("When your music ends, keep playing similar songs", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Switch(MusicPlayback.autoplay, { MusicPlayback.setAutoplay(c, it) })
            }
            if (t != null && t.source != "radio") OutlinedButton(onClick = {
                extras = false
                scope.launch {
                    val more = Mixes.similar(c, t, setOf(t.id))
                    MusicPlayback.play(c, listOf(t) + more); Feedback.show("${t.title} radio — ${more.size} songs like it")
                }
            }, modifier = Modifier.padding(top = 14.dp)) { Text("Start radio from this song") }
        }
    }
}

@Composable
private fun LyricsPane(t: Track) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var text by remember(t.id) { mutableStateOf(MusicLibrary.lyrics(c, t.id)) }
    var busy by remember(t.id) { mutableStateOf(false) }
    var editing by remember(t.id) { mutableStateOf(false) }
    LaunchedEffect(t.id) { com.nizam.app.ui.design.guard {
        if (text.isBlank()) { busy = true; Lyrics.fetch(t)?.let { text = it; MusicLibrary.setLyrics(c, t.id, it) }; busy = false }
    } }
    if (editing) {
        Column(Modifier.fillMaxSize()) {
            OutlinedTextField(text, { text = it }, modifier = Modifier.weight(1f).fillMaxWidth(), placeholder = { Text("Paste or write lyrics — [mm:ss] timestamps make them follow the song") })
            Button(onClick = { MusicLibrary.setLyrics(c, t.id, text); editing = false }) { Text("Save lyrics") }
        }
        return
    }
    val lines = remember(text) { Lyrics.parse(text) }
    when {
        busy -> Thinking(label = "Finding lyrics")
        text.isBlank() -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No lyrics found for this song.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { scope.launch { busy = true; Lyrics.fetch(t)?.let { text = it; MusicLibrary.setLyrics(c, t.id, it) }; busy = false } }) { Text("Try again") }
                OutlinedButton(onClick = { editing = true }) { Text("Add them") }
            }
        }
        lines.isNotEmpty() -> {
            // time-synced: the current line glows and stays centred
            val pos = MusicPlayback.positionMs
            val at = lines.indexOfLast { it.ms <= pos + 250 }.coerceAtLeast(0)
            val state = rememberLazyListState()
            LaunchedEffect(at) { com.nizam.app.ui.design.guard { state.animateScrollToItem((at - 3).coerceAtLeast(0)) } }
            LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(lines) { i, l ->
                    Text(l.text.ifBlank { "♪" }, fontSize = 24.sp, lineHeight = 32.sp,
                        fontWeight = if (i == at) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (i == at) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f),
                        modifier = Modifier.fillMaxWidth().clickable { MusicPlayback.seek(l.ms) }.padding(vertical = 6.dp))
                }
                item { Text("Edit lyrics", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { editing = true }.padding(vertical = 16.dp)) }
            }
        }
        else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp, lineHeight = 30.sp))
            Text("Edit lyrics", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { editing = true }.padding(vertical = 16.dp))
        }
    }
}

@Composable
private fun QueuePane() {
    LazyColumn(Modifier.fillMaxSize()) {
        itemsIndexed(MusicPlayback.queue) { i, t ->
            Row(Modifier.fillMaxWidth().clickable { MusicPlayback.jump(i) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                TrackArt(t, Modifier.size(40.dp), 6)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
                        color = if (i == MusicPlayback.index) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                    Text(t.artist, maxLines = 1, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (i != MusicPlayback.index) Text("Remove", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { MusicPlayback.removeAt(i) }.padding(8.dp))
            }
        }
    }
}

// ── Track sheet: actions, tag editor, cover, auto-fill ──────────────────────────────────────────

@Composable
private fun TrackSheet(t: Track, onClose: () -> Unit, onChanged: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var title by remember { mutableStateOf(t.title) }
    var artist by remember { mutableStateOf(t.artist) }
    var album by remember { mutableStateOf(t.album) }
    var year by remember { mutableStateOf(t.year) }
    var genre by remember { mutableStateOf(t.genre) }
    var trackNo by remember { mutableStateOf(if (t.trackNo > 0) t.trackNo.toString() else "") }
    var art by remember { mutableStateOf(t.art) }
    var matches by remember { mutableStateOf<List<MetaMatch>?>(null) }
    var menu by remember { mutableStateOf(false) }
    val pickCover = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bytes = withContext(Dispatchers.IO) { c.contentResolver.openInputStream(uri)?.use { it.readBytes() } } ?: return@launch
            art = MusicArt.saveArt(c, t.id, bytes)
        }
    }
    val edited = t.copy(title = title.trim(), artist = artist.trim(), album = album.trim(), year = year.trim(), genre = genre.trim(),
        trackNo = trackNo.toIntOrNull() ?: 0, art = art)
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onClose), contentAlignment = Alignment.BottomCenter) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).background(MaterialTheme.colorScheme.background)
            .clickable(enabled = false) {}.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clickable(role = Role.Button) { pickCover.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    TrackArt(edited, Modifier.size(72.dp), 10)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(t.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text("Tap the art to change it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Actions") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Play next") }, { MusicPlayback.enqueue(c, t, true); menu = false; Feedback.show("Plays next") })
                        DropdownMenuItem({ Text("Add to queue") }, { MusicPlayback.enqueue(c, t, false); menu = false; Feedback.show("Added to queue") })
                        MusicLibrary.playlists(c).forEach { p ->
                            DropdownMenuItem({ Text("Add to ${p.name}") }, { MusicLibrary.addToPlaylist(c, p.id, t.id); menu = false; Feedback.show("Added to ${p.name}") })
                        }
                        DropdownMenuItem({ Text(if (t.id in MusicLibrary.liked(c)) "Unlike" else "Like") }, { MusicLibrary.toggleLike(c, t.id); menu = false })
                        if (t.uri.startsWith("http")) DropdownMenuItem({ Text("Download") }, { MusicSources.download(c, t); menu = false })
                        MusicSources.STREAMING.take(2).forEach { (name, pattern) ->
                            DropdownMenuItem({ Text("Open in $name") }, {
                                runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(pattern.format(Uri.encode("${t.artist} ${t.title}")))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                menu = false })
                        }
                        if (t.source != "device") DropdownMenuItem({ Text("Delete from Atlas", color = MaterialTheme.colorScheme.error) },
                            { MusicLibrary.deleteOwn(c, t); menu = false; onChanged(); onClose() })
                    }
                }
            }
            Label("Tags")
            OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(artist, { artist = it }, singleLine = true, label = { Text("Artist") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(album, { album = it }, singleLine = true, label = { Text("Album") }, modifier = Modifier.fillMaxWidth())
            Row {
                OutlinedTextField(year, { year = it.filter(Char::isDigit).take(4) }, singleLine = true, label = { Text("Year") }, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(trackNo, { trackNo = it.filter(Char::isDigit).take(3) }, singleLine = true, label = { Text("Track") }, modifier = Modifier.weight(1f))
            }
            OutlinedTextField(genre, { genre = it }, singleLine = true, label = { Text("Genre") }, modifier = Modifier.fillMaxWidth())
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = {
                    scope.launch {
                        MusicLibrary.edit(c, edited)
                        MusicPlayback.refreshTrack(edited)
                        // Atlas's own MP3s get the tags written into the file too
                        val path = Uri.parse(edited.uri).path
                        if (edited.source != "device" && path != null && path.endsWith(".mp3", true)) runCatching {
                            val cover = withContext(Dispatchers.IO) { runCatching {
                                if (art.startsWith("http")) java.net.URL(art).readBytes() else if (art.isNotBlank()) c.contentResolver.openInputStream(Uri.parse(art))?.use { it.readBytes() } else null
                            }.getOrNull() }
                            withContext(Dispatchers.IO) { Id3.write(java.io.File(path), edited, cover) }
                        }
                        Feedback.show("Saved"); onChanged(); onClose()
                    }
                }) { Text("Save") }
                OutlinedButton(onClick = {
                    matches = null
                    scope.launch { matches = runCatching { MusicMeta.search("$artist $title".trim()) }.getOrElse { emptyList() } }
                }) { Text("Auto-fill") }
            }
            matches?.let { list ->
                Label("Matches — tap one to use its tags and cover")
                if (list.isEmpty()) Text("No matches. Try editing the title or artist first.", style = MaterialTheme.typography.bodySmall)
                list.forEach { m ->
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                        title = m.title; artist = m.artist; album = m.album; art = m.cover
                        scope.launch { MusicMeta.year(m.artist, m.album).takeIf { it.isNotBlank() }?.let { year = it } }
                        matches = null
                    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        AsyncImage(m.cover, m.album, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)))
                        Spacer(Modifier.width(10.dp))
                        Column { Text(m.title, style = MaterialTheme.typography.bodyMedium); Text("${m.artist} · ${m.album}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
                Text("Metadata and covers from Deezer's public catalogue.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (t.source == "device") Text("Edits to your phone's own files are kept in Atlas; files Atlas downloaded or imported get the tags written in.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
            HorizontalDivider(Modifier.padding(top = 12.dp), color = Color.Transparent)
        }
    }
}

@Composable
private fun DownloadBar() {
    val p by MusicSources.progress.collectAsState()
    val cur = p ?: return
    if (cur.state == "done") return
    Column(Modifier.padding(vertical = 6.dp)) {
        Text(if (cur.state == "failed") "Download failed: ${cur.error}" else "Downloading ${cur.title} · ${cur.percent}%",
            style = MaterialTheme.typography.bodySmall,
            color = if (cur.state == "failed") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        if (cur.state == "downloading") LinearProgressIndicator(progress = { cur.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            trackColor = MaterialTheme.colorScheme.surfaceVariant)
    }
}
