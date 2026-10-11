package com.nizam.app.feature.cinema

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import com.nizam.app.ui.design.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.nizam.app.core.ChipRow
import com.nizam.app.ui.theme.DisplayFamily
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val SORTS = linkedMapOf("popularity.desc" to "Popular", "vote_average.desc" to "Top rated",
    "primary_release_date.desc" to "Newest", "revenue.desc" to "Box office")
private val DECADES = linkedMapOf("Any" to (null to null), "2020s" to (2020 to 2029), "2010s" to (2010 to 2019),
    "2000s" to (2000 to 2009), "90s" to (1990 to 1999), "80s" to (1980 to 1989), "Classics" to (1920 to 1979))
private val LANGS = linkedMapOf("Any" to null, "English" to "en", "Urdu" to "ur", "Hindi" to "hi", "Korean" to "ko",
    "Japanese" to "ja", "Turkish" to "tr", "Arabic" to "ar", "French" to "fr", "Chinese" to "zh", "Spanish" to "es")

@Composable
private fun Poster(url: String?, title: String, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(url, title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(8.dp))
    }
}

@Composable
fun CinemaScreen(onFilm: (Int) -> Unit, onPlay: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val skipped = remember { c.getSharedPreferences("cinema_setup", 0).getBoolean("skipped", false) }
    var keySetup by remember { mutableStateOf(CinemaKeys.tmdb(c).isBlank() && !skipped) }
    // without a TMDB key only the free shelf can load — start there instead of on an error
    var shelf by remember { mutableStateOf(if (CinemaKeys.tmdb(c).isBlank()) "Free to watch" else "Trending") }
    var query by remember { mutableStateOf("") }
    var films by remember { mutableStateOf<List<Film>>(emptyList()) }
    var free by remember { mutableStateOf<List<FreeFilm>>(emptyList()) }
    var genres by remember { mutableStateOf<Map<Int, String>>(emptyMap()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(1) }
    // filters
    var showFilters by remember { mutableStateOf(false) }
    var genre by remember { mutableStateOf<Int?>(null) }
    var decade by remember { mutableStateOf("Any") }
    var lang by remember { mutableStateOf("Any") }
    var minRating by remember { mutableStateOf(0f) }
    var sort by remember { mutableStateOf("popularity.desc") }
    val store = remember { CinemaStore(c) }

    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    // Each new request cancels the last, so a slow earlier shelf can't overwrite the one you switched to
    fun load(reset: Boolean = true) { job?.cancel(); job = scope.launch {
        if (reset) page = 1
        loading = true; error = null
        runCatching {
            if (shelf == "Free to watch") {
                val r = Archive.search(query, page); free = if (reset) r else free + r
            } else if (shelf == "Watchlist") {
                films = store.watchlist().map {
                    Film(it.optInt("id"), it.optString("title"), it.optString("year"),
                        it.optString("poster").ifBlank { null }, null, it.optDouble("rating"), "")
                }
            } else {
                if (genres.isEmpty()) genres = runCatching { Tmdb.genres(c) }.getOrElse { emptyMap() }
                val (from, to) = DECADES.getValue(decade)
                val r = when {
                    query.isNotBlank() -> Tmdb.search(c, query, page)
                    shelf == "Discover" -> Tmdb.discover(c, genre, from, to, minRating.toDouble(), sort, LANGS[lang], page)
                    shelf == "Top rated" -> Tmdb.topRated(c, page)
                    shelf == "In cinemas" -> Tmdb.upcoming(c, page)
                    shelf == "Popular" -> Tmdb.popular(c, page)
                    else -> Tmdb.trending(c, page)
                }
                films = if (reset) r else (films + r).distinctBy { it.id }
            }
        }.onFailure { if (it !is kotlinx.coroutines.CancellationException) error = com.nizam.app.ui.components.Feedback.friendly(it) }
        loading = false
    } }
    LaunchedEffect(shelf, keySetup) { if (!keySetup || shelf == "Free to watch") load() }

    if (keySetup) { KeySetup(onDone = { keySetup = false }); return }

    LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize().padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 14.dp)) {
                    Text("Cinema", style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = { keySetup = true }) { Icon(Icons.Filled.Settings, "Cinema settings") }
                }
                OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    label = { Text(if (shelf == "Free to watch") "Search free films" else "Search any film") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { load() }))
                Spacer(Modifier.height(8.dp))
                ChipRow(listOf("Trending", "Popular", "Top rated", "In cinemas", "Discover", "Free to watch", "Watchlist"),
                    shelf, { shelf = it; if (it == "Discover") showFilters = true })
                if (shelf == "Discover" || showFilters && shelf != "Free to watch" && shelf != "Watchlist") {
                    Text(if (showFilters) "Hide filters" else "Filters", color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable(role = Role.Button) { showFilters = !showFilters }.padding(vertical = 6.dp))
                    if (showFilters) {
                        Text("Genre", style = MaterialTheme.typography.labelMedium)
                        ChipRow(listOf("Any") + genres.values.sorted(), genre?.let { genres[it] } ?: "Any",
                            { name -> genre = genres.entries.firstOrNull { it.value == name }?.key })
                        Text("Era", style = MaterialTheme.typography.labelMedium)
                        ChipRow(DECADES.keys.toList(), decade, { decade = it })
                        Text("Language", style = MaterialTheme.typography.labelMedium)
                        ChipRow(LANGS.keys.toList(), lang, { lang = it })
                        Text("Minimum rating: ${"%.1f".format(minRating)}", style = MaterialTheme.typography.labelMedium)
                        Slider(minRating, { minRating = it }, valueRange = 0f..9f, steps = 17)
                        ChipRow(SORTS.keys.toList(), sort, { sort = it }, labels = { SORTS[it] ?: it })
                        Button(onClick = { shelf = "Discover"; load() }) { Text("Apply") }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
                if (shelf == "Free to watch") Text("Public-domain and freely licensed films from the Internet Archive — " +
                    "they play right here.", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                if (shelf == "Watchlist" && films.isEmpty() && !loading) Text("Nothing saved yet — tap ＋ on any film.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
        }
        if (shelf == "Free to watch") {
            items(free, key = { it.id }) { f ->
                Column(Modifier.clickable(role = Role.Button) {
                    scope.launch {
                        loading = true
                        val url = runCatching { Archive.streamUrl(f.id) }.getOrNull()
                        loading = false
                        if (url != null) { CinemaNav.play(url, f.title, url, null); onPlay() }
                        else error = "No playable file for “${f.title}”."
                    }
                }) {
                    Poster(f.thumb, f.title)
                    Text(f.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    Text("> ${f.year}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        } else {
            items(films, key = { it.id }) { f ->
                Column(Modifier.clickable(role = Role.Button) { onFilm(f.id) }) {
                    Poster(f.poster, f.title)
                    Text(f.title, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                    Text("★ ${"%.1f".format(f.rating)} · ${f.year}", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (shelf != "Watchlist" && (films.isNotEmpty() || free.isNotEmpty())) item(span = { GridItemSpan(maxLineSpan) }) {
            OutlinedButton(enabled = !loading, onClick = { page++; load(reset = false) }, modifier = Modifier.padding(vertical = 12.dp)) {
                Text(if (loading) "Loading…" else "Load more")
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            Text("Film data from TMDB. This product uses the TMDB API but is not endorsed or certified by TMDB.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 40.dp))
        }
    }
}

@Composable
private fun KeySetup(onDone: () -> Unit) {
    val c = LocalContext.current
    var tmdb by remember { mutableStateOf(CinemaKeys.tmdb(c)) }
    var omdb by remember { mutableStateOf(CinemaKeys.omdb(c)) }
    var region by remember { mutableStateOf(CinemaKeys.region(c)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("Cinema setup", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(8.dp))
        Text("TMDB key — free, takes two minutes at themoviedb.org > Settings > API. Either the API key or the " +
            "read-access token works.", style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(tmdb, { tmdb = it }, label = { Text("TMDB key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        Text("OMDb key (optional) — adds IMDb, Rotten Tomatoes and Metacritic scores. Free at omdbapi.com.",
            style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(omdb, { omdb = it }, label = { Text("OMDb key") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(region, { region = it.take(2) }, label = { Text("Country for \"where to watch\" (PK)") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = tmdb.isNotBlank(), onClick = { CinemaKeys.save(c, tmdb, omdb, region.ifBlank { "PK" }); onDone() }) { Text("Save") }
            OutlinedButton(onClick = { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.themoviedb.org/settings/api"))) }) {
                Text("Get a TMDB key")
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("Without a key you can still browse and play free films from the Internet Archive.",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedButton(onClick = {
            CinemaKeys.save(c, "", omdb, region)
            c.getSharedPreferences("cinema_setup", 0).edit().putBoolean("skipped", true).apply()
            onDone()
        }) { Text("Skip for now") }
    }
}

@Composable
fun FilmScreen(onFilm: (Int) -> Unit, onPlay: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { CinemaStore(c) }
    var d by remember { mutableStateOf<FilmDetail?>(null) }
    var scores by remember { mutableStateOf<ImdbScores?>(null) }
    var free by remember { mutableStateOf<FreeFilm?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var saved by remember { mutableStateOf(false) }
    var mine by remember { mutableStateOf(0) }
    val id = CinemaNav.filmId

    LaunchedEffect(id) {
        d = null; scores = null; free = null
        runCatching { Tmdb.detail(c, id) }.onSuccess { det ->
            d = det; saved = store.inWatchlist(det.film.id); mine = store.myRating(det.film.id)
            det.imdbId?.let { imdb -> scores = runCatching { Omdb.scores(c, imdb) }.getOrNull() }
            free = Archive.match(det.film.title, det.film.year)
        }.onFailure { error = "Couldn't load this film: " + com.nizam.app.ui.components.Feedback.friendly(it) }
    }

    val det = d
    if (det == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(error ?: "Loading…") }
        return
    }
    val f = det.film
    fun open(url: String) = runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            AsyncImage(f.backdrop ?: f.poster, f.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.background))))
        }
        Column(Modifier.padding(horizontal = 18.dp)) {
            Row {
                Poster(f.poster, f.title, Modifier.width(96.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.title, style = MaterialTheme.typography.headlineSmall)
                    Text(listOfNotNull(f.year, det.runtime.takeIf { it > 0 }?.let { "${it / 60}h ${it % 60}m" },
                        det.director.takeIf { it.isNotBlank() }?.let { "dir. $it" }).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(det.genres.joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("TMDB ${"%.1f".format(f.rating)}", style = MonoNumber)
                        scores?.imdb?.let { Text("IMDb $it", style = MonoNumber, color = Color(0xFFE0B14A)) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        scores?.rotten?.let { Text("🍅 $it", style = MaterialTheme.typography.labelMedium) }
                        scores?.metacritic?.let { Text("Ⓜ $it", style = MaterialTheme.typography.labelMedium) }
                    }
                }
            }
            if (det.tagline.isNotBlank()) Text("“${det.tagline}”", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))

            Spacer(Modifier.height(12.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                free?.let { ff ->
                    Button(onClick = {
                        scope.launch {
                            Archive.streamUrl(ff.id)?.let { CinemaNav.play(it, f.title, it, null); onPlay() }
                        }
                    }) { Text("> Play free") }
                }
                det.trailerKey?.let { k -> OutlinedButton(onClick = { open("https://www.youtube.com/watch?v=$k") }) { Text("Trailer") } }
                OutlinedButton(onClick = { store.toggleWatchlist(f); saved = !saved }) { Text(if (saved) "✓ Watchlist" else "＋ Watchlist") }
                det.imdbId?.let { OutlinedButton(onClick = { open("https://www.imdb.com/title/$it/") }) { Text("IMDb") } }
            }

            Text("Your rating", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 12.dp))
            Row {
                (1..10).forEach { n ->
                    Text(if (n <= mine) "★" else "☆", style = MaterialTheme.typography.titleLarge,
                        color = if (n <= mine) Color(0xFFE0B14A) else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable(role = Role.Button) { mine = n; store.rate(f, n) }.padding(2.dp))
                }
            }

            Text(f.overview, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 10.dp))

            Text("WHERE TO WATCH (${CinemaKeys.region(c)})", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
            if (det.providers.isEmpty() && free == null) Text("Not on any service in your region right now.",
                style = MaterialTheme.typography.bodyMedium)
            det.providers.groupBy { it.kind }.forEach { (kind, list) ->
                Text(kind, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    list.forEach { p ->
                        Column(Modifier.width(64.dp).clickable(role = Role.Button) { det.providerLink?.let { open(it) } },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(p.logo, p.name, modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)))
                            Text(p.name, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            if (det.providers.isNotEmpty()) Text("Availability by JustWatch", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            if (det.cast.isNotEmpty()) {
                Text("CAST", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    det.cast.forEach { m ->
                        Column(Modifier.width(76.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            AsyncImage(m.photo, m.name, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(64.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface))
                            Text(m.name, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(m.role, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (det.similar.isNotEmpty()) {
                Text("MORE LIKE THIS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    det.similar.forEach { s ->
                        Column(Modifier.width(100.dp).clickable(role = Role.Button) { onFilm(s.id) }) {
                            Poster(s.poster, s.title)
                            Text(s.title, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            Spacer(Modifier.height(60.dp))
        }
    }
}
