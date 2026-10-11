package com.nizam.app.feature.cinema

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DownloadDone
import androidx.compose.material.icons.outlined.Refresh
import com.nizam.app.ui.design.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nizam.app.core.ChipRow
import com.nizam.app.ui.theme.DisplayFamily
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
private fun PosterCard(title: String, poster: String?, sub: String, width: androidx.compose.ui.unit.Dp? = null, onClick: () -> Unit) {
    Column((if (width != null) Modifier.width(width) else Modifier).clickable(role = Role.Button, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface),
            contentAlignment = Alignment.Center) {
            if (poster != null) AsyncImage(poster, title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(6.dp))
        }
        Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
        if (sub.isNotBlank()) Text(sub, maxLines = 1, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun MediaCenterScreen(onMeta: (String, String) -> Unit, onPlay: () -> Unit, onTmdbFilm: (Int) -> Unit) {
    var tab by remember { mutableStateOf("Board") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Cinema", style = MaterialTheme.typography.headlineMedium.copy(fontFamily = DisplayFamily), modifier = Modifier.weight(1f))
            val ctx = LocalContext.current
            var earn by remember { mutableStateOf(Leisure.on(ctx)) }
            Text(if (earn) "⏳ ${Leisure.balance(ctx).coerceAtLeast(0)} min earned" else "earn mode off",
                style = MaterialTheme.typography.labelMedium, color = if (earn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(role = Role.Button) { earn = !earn; Leisure.set(ctx, earn) }.padding(6.dp))
        }
        Box(Modifier.padding(horizontal = 14.dp)) {
            ChipRow(listOf("Board", "Discover", "Library", "Addons", "TMDB"), tab, { tab = it }, labels = { if (it == "Board") "Home" else it })
        }
        when (tab) {
            "Board" -> Board(onMeta, onPlay)
            "Discover" -> Discover(onMeta)
            "Library" -> LibraryTab(onMeta, onPlay)
            "Addons" -> AddonsTab()
            else -> CinemaScreen(onFilm = onTmdbFilm, onPlay = onPlay)
        }
    }
}

// ── Board: one row per catalog across every installed addon ───────────────────
@Composable
private fun Board(onMeta: (String, String) -> Unit, onPlay: () -> Unit) {
    val c = LocalContext.current
    var rows by remember { mutableStateOf<List<Pair<String, List<Meta>>>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var free by remember { mutableStateOf<List<FreeFilm>>(emptyList()) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        rows = coroutineScope {
            Stremio.addons(c).flatMap { a -> a.catalogs.filter { it.type in listOf("movie", "series", "channel") }.take(4).map { a to it } }
                .take(10).map { (a, cat) -> async { runCatching { "${cat.name} · ${cat.type}" to Stremio.catalog(a, cat).take(20) }.getOrNull() } }
                .awaitAll().filterNotNull().filter { it.second.isNotEmpty() }
        }
        free = runCatching { Archive.search("").take(20) }.getOrElse { emptyList() }
        loading = false
    }
    val cont = remember { Library.continueWatching(c) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 14.dp)) {
        if (cont.isNotEmpty()) {
            com.nizam.app.ui.design.Label("Continue watching", Modifier.padding(top = 10.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                cont.forEach { p ->
                    Column(Modifier.width(110.dp)) {
                        PosterCard(p.optString("name"), p.optString("poster").ifBlank { null }, p.optString("label"), 110.dp) {
                            onMeta(p.optString("type"), p.optString("metaId"))
                        }
                        LinearProgressIndicator(progress = { p.optLong("pos") / p.optLong("dur").coerceAtLeast(1).toFloat() }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if (loading) Text("Loading your addons…", modifier = Modifier.padding(16.dp))
        rows.forEach { (title, metas) ->
            com.nizam.app.ui.design.Label(title, Modifier.padding(top = 14.dp, bottom = 6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                metas.forEach { m -> PosterCard(m.name, m.poster, listOf(m.year, m.rating.takeIf { it.isNotBlank() }?.let { "★ $it" }).filterNotNull().joinToString(" · "), 110.dp) { onMeta(m.type, m.id) } }
            }
        }
        if (free.isNotEmpty()) {
            com.nizam.app.ui.design.Label("Free to watch · Internet Archive", Modifier.padding(top = 14.dp, bottom = 6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                free.forEach { f -> PosterCard(f.title, f.thumb, "> ${f.year}", 110.dp) {
                    scope.launch { Archive.streamUrl(f.id)?.let { CinemaNav.play(it, f.title, "archive:${f.id}", null); onPlay() } }
                } }
            }
        }
        Spacer(Modifier.height(60.dp))
    }
}

// ── Discover: pick an addon catalog, filter by genre, search ──────────────────
@Composable
private fun Discover(onMeta: (String, String) -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var addons by remember { mutableStateOf<List<Addon>>(emptyList()) }
    var type by remember { mutableStateOf("movie") }
    var catKey by remember { mutableStateOf("") }
    var genre by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Meta>>(emptyList()) }
    var skip by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { addons = Stremio.addons(c).filter { it.catalogs.isNotEmpty() } }
    val cats = addons.flatMap { a -> a.catalogs.filter { it.type == type }.map { a to it } }
    val chosen = cats.firstOrNull { "${it.first.name}/${it.second.id}" == catKey } ?: cats.firstOrNull()
    fun load(reset: Boolean) = scope.launch {
        val (a, cat) = chosen ?: return@launch
        if (reset) skip = 0
        val r = runCatching { Stremio.catalog(a, cat, genre.takeIf { it != "All" }, query.takeIf { it.isNotBlank() && cat.searchable }, skip) }.getOrElse { emptyList() }
        results = if (reset) r else (results + r).distinctBy { it.id }
    }
    LaunchedEffect(type, catKey, genre, addons) { if (addons.isNotEmpty()) load(true) }

    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                ChipRow(addons.flatMap { it.types }.distinct().filter { t -> addons.any { a -> a.catalogs.any { it.type == t } } }, type, { type = it; catKey = ""; genre = "All" })
                ChipRow(cats.map { "${it.first.name}/${it.second.id}" }, chosen?.let { "${it.first.name}/${it.second.id}" } ?: "", { catKey = it; genre = "All" },
                    labels = { k -> cats.firstOrNull { "${it.first.name}/${it.second.id}" == k }?.let { "${it.second.name} (${it.first.name})" } ?: k })
                chosen?.second?.genres?.takeIf { it.isNotEmpty() }?.let { g -> ChipRow(listOf("All") + g, genre, { genre = it }) }
                if (chosen?.second?.searchable == true) OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search") },
                    modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { load(true) }))
            }
        }
        items(results, key = { it.id }) { m -> PosterCard(m.name, m.poster, m.year) { onMeta(m.type, m.id) } }
        if (results.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedButton(onClick = { skip += results.size.coerceAtLeast(20); load(false) }) { Text("Load more") }
        }
    }
}

// ── Library: downloads, new-episode calendar, saved titles ────────────────────
@Composable
private fun LibraryTab(onMeta: (String, String) -> Unit, onPlay: () -> Unit) {
    val c = LocalContext.current
    val items = remember { Library.items(c) }
    val downloads by VideoDownloads.items.collectAsState()
    var cal by remember { mutableStateOf<List<Pair<Meta, Episode>>>(emptyList()) }
    LaunchedEffect(Unit) { cal = runCatching { Library.calendar(c) }.getOrElse { emptyList() } }
    LaunchedEffect(Unit) { VideoDownloads.load(c); while (true) { VideoDownloads.sync(c); kotlinx.coroutines.delay(2000) } }
    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (downloads.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = 8.dp)) {
                com.nizam.app.ui.design.Label("Downloads", Modifier.padding(bottom = 6.dp))
                downloads.sortedByDescending { it.added }.forEach { d -> DownloadRow(d, onPlay) }
            }
        }
        if (cal.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.padding(top = 8.dp)) {
                com.nizam.app.ui.design.Label("New episodes", Modifier.padding(bottom = 4.dp))
                cal.forEach { (m, e) ->
                    Text("${e.released.take(10)} · ${m.name} S${e.season}E${e.episode} — ${e.title}", style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable(role = Role.Button) { onMeta("series", m.id) }.padding(vertical = 4.dp))
                }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) { com.nizam.app.ui.design.Label("Saved titles", Modifier.padding(top = 8.dp)) }
        if (items.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Nothing saved yet — tap Add to library on any title, or the download button on a source to keep it for offline.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        }
        items(items, key = { it.optString("id") }) { i -> PosterCard(i.optString("name"), i.optString("poster").ifBlank { null }, i.optString("type")) {
            onMeta(i.optString("type"), i.optString("id")) } }
    }
}

@Composable
private fun DownloadRow(d: VideoDownload, onPlay: () -> Unit) {
    val c = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(enabled = d.done, role = Role.Button) {
        CinemaNav.play(VideoDownloads.playable(d), d.label, d.id, null, d.subs, headers = d.headers, cached = d.kind == "hls"); onPlay()
    }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(44.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surface)) {
            if (d.poster.isNotBlank()) AsyncImage(d.poster, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(d.label, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(when {
                d.done -> "Ready offline" + (if (d.kind == "torrent") " · torrent" else "")
                d.active -> "Saving · ${d.progress}%"
                else -> d.error.ifBlank { "Stopped" }
            }, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                color = if (!d.done && !d.active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (d.active) LinearProgressIndicator(progress = { d.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        if (d.done) Icon(Icons.Filled.PlayArrow, "Play")
        if (!d.done && !d.active) IconButton(onClick = { VideoDownloads.retry(c, d) }) { Icon(Icons.Outlined.Refresh, "Retry") }
        IconButton(onClick = { confirm = true }) { Icon(Icons.Outlined.Delete, "Delete") }
    }
    if (confirm) androidx.compose.material3.AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Delete download?") },
        text = { Text("${d.label} will be removed from this phone. It stays in your library to stream.") },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { confirm = false; VideoDownloads.delete(c, d) }) { Text("Delete") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text("Keep") } })
}

// ── Addons: installed list, add by URL, remove ────────────────────────────────
@Composable
private fun AddonsTab() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var urls by remember { mutableStateOf(Stremio.installedUrls(c)) }
    var addons by remember { mutableStateOf<Map<String, Addon?>>(emptyMap()) }
    var input by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(urls) { addons = urls.associateWith { u -> runCatching { Stremio.manifest(u) }.getOrNull() } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
        Text("Add an addon", style = MaterialTheme.typography.titleMedium)
        Text("Paste a manifest URL or a stremio:// install link. Atlas speaks the Stremio addon protocol, so catalogs, " +
            "metadata, streams and subtitles from any addon appear across Cinema. Any addon works, and every source plays " +
            "right here — direct links, HLS and torrents alike.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(input, { input = it }, singleLine = true, label = { Text("https://…/manifest.json") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = input.isNotBlank(), onClick = {
            scope.launch {
                // any link: try it exactly as pasted, then as a base URL with /manifest.json added
                val raw = input.trim().replace("stremio://", "https://")
                val candidates = listOf(raw, raw.trimEnd('/') + "/manifest.json").distinct()
                var u = candidates.first()
                runCatching {
                    var last: Throwable? = null
                    candidates.firstNotNullOfOrNull { cand -> runCatching { Stremio.manifest(cand) }.onFailure { last = it }.getOrNull()?.also { u = cand } }
                        ?: throw (last ?: IllegalStateException("No manifest found"))
                }.onSuccess { a ->
                    urls = urls + u; Stremio.setInstalled(c, urls); input = ""; status = "Installed ${a.name}"
                }.onFailure { status = "Couldn't read that addon: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
            }
        }) { Text("Install") }
        status?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        com.nizam.app.ui.design.Label("Installed", Modifier.padding(top = 16.dp))
        urls.forEach { u ->
            val a = addons[u]
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text(a?.name ?: "Unreachable", style = MaterialTheme.typography.titleMedium)
                    Text(a?.let { "${it.resources.joinToString()} · ${it.types.joinToString()}" } ?: u, style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    a?.description?.takeIf { it.isNotBlank() }?.let { Text(it.take(160), style = MaterialTheme.typography.bodyMedium) }
                    Text("remove", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable(role = Role.Button) { urls = urls - u; Stremio.setInstalled(c, urls) }.padding(top = 6.dp))
                }
            }
        }
        OutlinedButton(onClick = { urls = DEFAULT_ADDONS; Stremio.setInstalled(c, urls) }, modifier = Modifier.padding(top = 10.dp)) { Text("Restore defaults") }
    }
}

// ── Title page: details, seasons & episodes, sources panel ────────────────────
private val QUALITY = listOf("4K" to Regex("2160p|\\b4k\\b|uhd", RegexOption.IGNORE_CASE), "1080p" to Regex("1080p", RegexOption.IGNORE_CASE),
    "720p" to Regex("720p", RegexOption.IGNORE_CASE), "SD" to Regex("480p|360p|\\bsd\\b|dvdrip|xvid", RegexOption.IGNORE_CASE))
private fun qualityOf(s: StreamOption) = QUALITY.firstOrNull { it.second.containsMatchIn(s.title + " " + s.detail) }?.first ?: ""
private fun sizeOf(s: StreamOption) = Regex("(\\d+(?:[.,]\\d+)?)\\s?(GB|MB)", RegexOption.IGNORE_CASE).find(s.title + " " + s.detail)?.value.orEmpty()
private fun seedsOf(s: StreamOption) = Regex("👤\\s?(\\d+)").find(s.detail)?.groupValues?.get(1)?.toIntOrNull()

@Composable
fun MetaScreen(onPlay: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val type = CinemaNav.metaType; val id = CinemaNav.metaId
    var meta by remember { mutableStateOf<Meta?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var season by remember { mutableStateOf(1) }
    var sourcesFor by remember { mutableStateOf<Episode?>(null) }
    var streams by remember { mutableStateOf<StreamResult?>(null) }
    var loadingSources by remember { mutableStateOf(false) }
    var quality by remember { mutableStateOf("All") }
    var inLib by remember { mutableStateOf(Library.has(c, id)) }
    var freeMatch by remember { mutableStateOf<FreeFilm?>(null) }
    val downloads by VideoDownloads.items.collectAsState()
    LaunchedEffect(Unit) { VideoDownloads.load(c); while (true) { VideoDownloads.sync(c); kotlinx.coroutines.delay(2000) } }

    fun openSources(videoId: String, ep: Episode?) = scope.launch {
        sourcesFor = ep; streams = null; quality = "All"; loadingSources = true
        streams = runCatching { Stremio.streams(c, if (ep != null) "series" else type, videoId) }.getOrElse { StreamResult(emptyList(), emptyList(), 0) }
        loadingSources = false
    }
    LaunchedEffect(id) {
        meta = runCatching { Stremio.meta(c, type, id) }.getOrNull()
        if (meta == null) error = "No addon could describe this title."
        meta?.videos?.firstOrNull()?.let { season = it.season }
        // back from the player's "Next episode": open that episode's sources straight away
        CinemaNav.pendingEpisode?.let { next -> CinemaNav.pendingEpisode = null
            meta?.videos?.firstOrNull { it.id == next }?.let { e -> season = e.season; openSources(e.id, e) } }
        meta?.takeIf { it.type == "movie" }?.let { m -> freeMatch = Archive.match(m.name, m.year.take(4)) }
    }
    suspend fun subsFor(videoId: String) = runCatching { Stremio.subtitles(c, if (videoId.contains(":")) "series" else type, videoId) }.getOrElse { emptyList() }
    fun playSaved(d: VideoDownload) {
        CinemaNav.play(VideoDownloads.playable(d), d.label, d.id, meta, d.subs, headers = d.headers, cached = d.kind == "hls")
        onPlay()
    }
    fun play(s: StreamOption, videoId: String, label: String) = scope.launch {
        when {
            s.url != null || s.magnet != null -> {
                CinemaNav.play(s.url.orEmpty(), label, videoId, meta, subsFor(videoId), s.magnet, s.fileIdx, s.headers)
                onPlay()
            }
            s.ytId != null -> runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${s.ytId}"))) }
            s.externalUrl != null -> runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(s.externalUrl))) }
        }
    }
    fun save(s: StreamOption, videoId: String, label: String) = scope.launch {
        VideoDownloads.start(c, s, videoId, label, meta, subsFor(videoId))
    }
    val m = meta
    if (m == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error ?: "Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant) }; return }
    val savedMovie = downloads.firstOrNull { it.id == m.id }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AsyncImage(m.background ?: m.poster, m.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))))
        }
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text(m.name, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = DisplayFamily))
            Text(listOf(m.year, m.runtime, m.rating.takeIf { it.isNotBlank() }?.let { "IMDb $it" }).filter { !it.isNullOrBlank() }.joinToString(" · "),
                style = MonoNumber, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (m.genres.isNotEmpty()) Text(m.genres.joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    savedMovie?.done == true -> Button(onClick = { playSaved(savedMovie) }) { Text("Play download") }
                    m.type != "series" -> Button(onClick = { openSources(m.id, null) }) { Text("Watch") }
                }
                freeMatch?.let { f -> OutlinedButton(onClick = { scope.launch { Archive.streamUrl(f.id)?.let {
                    CinemaNav.play(it, m.name, m.id, meta, subsFor(m.id)); onPlay() } } }) { Text("Play free") } }
                m.trailer?.let { t -> OutlinedButton(onClick = { runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=$t"))) } }) { Text("Trailer") } }
                OutlinedButton(onClick = { Library.toggle(c, m); inLib = !inLib }) { Text(if (inLib) "In library" else "Add to library") }
            }
            savedMovie?.takeIf { it.active }?.let { d ->
                Text("Saving · ${d.progress}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                LinearProgressIndicator(progress = { d.progress / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
            }
            Text(m.description, style = MaterialTheme.typography.bodyLarge)
            if (m.cast.isNotEmpty()) Text("Cast: ${m.cast.take(8).joinToString()}", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            if (m.director.isNotBlank()) Text("Director: ${m.director}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (m.videos.isNotEmpty()) {
                com.nizam.app.ui.design.Label("Episodes", Modifier.padding(top = 20.dp, bottom = 6.dp))
                ChipRow(m.videos.map { it.season }.distinct().map { "$it" }, "$season", { season = it.toInt() }, labels = { "Season $it" })
                m.videos.filter { it.season == season }.forEach { e ->
                    val (pos, dur) = Library.progress(c, e.id)
                    val saved = downloads.firstOrNull { it.id == e.id }
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { if (saved?.done == true) playSaved(saved) else openSources(e.id, e) }
                        .padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${e.episode}", style = MonoNumber, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(34.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.title.ifBlank { "Episode ${e.episode}" }, style = MaterialTheme.typography.bodyLarge,
                                color = if (sourcesFor?.id == e.id) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                            Text(listOfNotNull(e.released.take(10).ifBlank { null }, if (dur > 0) "${(pos * 100 / dur)}% watched" else null,
                                saved?.let { if (it.done) "Downloaded" else if (it.active) "Saving ${it.progress}%" else "Download failed" })
                                .joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (saved?.done == true) Icon(Icons.Outlined.DownloadDone, "Downloaded", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            if (loadingSources) Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Text("  Asking your addons…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            streams?.let { result ->
                val label = sourcesFor?.let { "${m.name} S${it.season}E${it.episode}" } ?: m.name
                val vid = sourcesFor?.id ?: m.id
                // playable here first, best picture first, then the best-seeded
                val all = result.options.sortedWith(compareBy<StreamOption>({ if (it.url != null || it.magnet != null) 0 else 1 },
                    { QUALITY.indexOfFirst { q -> q.first == qualityOf(it) }.let { i -> if (i < 0) 9 else i } }, { -(seedsOf(it) ?: 0) }))
                val qualities = QUALITY.map { it.first }.filter { q -> all.any { qualityOf(it) == q } }
                val list = if (quality == "All") all else all.filter { qualityOf(it) == quality }
                com.nizam.app.ui.design.Label("Sources" + (sourcesFor?.let { " · S${it.season}E${it.episode}" } ?: ""), Modifier.padding(top = 20.dp, bottom = 6.dp))
                if (qualities.size > 1) ChipRow(listOf("All") + qualities, quality, { quality = it })
                if (all.isEmpty()) Text(when {
                    result.addonsAsked.isEmpty() -> "No stream addons are installed. Add one in Cinema › Addons with its manifest link — " +
                        "any addon works, torrent ones included."
                    else -> "Asked ${result.addonsAsked.joinToString()} — none had a source for this title."
                }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                list.forEach { s ->
                    val canSave = VideoDownloads.kindOf(s) != null
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(role = Role.Button) { play(s, vid, label) }) {
                        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    qualityOf(s).takeIf { it.isNotBlank() }?.let { q ->
                                        Text(q, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimary,
                                            modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.primary).padding(horizontal = 5.dp, vertical = 1.dp))
                                    }
                                    Text(s.addon, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                    listOfNotNull(sizeOf(s).ifBlank { null }, seedsOf(s)?.let { "$it seeds" }, if (s.magnet != null) "torrent" else null)
                                        .takeIf { it.isNotEmpty() }?.let { Text(it.joinToString(" · "), style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                }
                                Text(s.title.replace("\n", " "), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                if (s.detail.isNotBlank()) Text(s.detail.replace("\n", " · ").take(140), style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            if (canSave) IconButton(onClick = { save(s, vid, label) }) { Icon(Icons.Outlined.Download, "Save for offline") }
                            Icon(if (s.url != null || s.magnet != null) Icons.Filled.PlayArrow else Icons.AutoMirrored.Outlined.OpenInNew,
                                if (s.url != null || s.magnet != null) "Play" else "Open", modifier = Modifier.padding(end = 10.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(60.dp))
        }
    }
}
