package com.nizam.app.feature.nx

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.iptv.Channel
import com.nizam.app.feature.iptv.IptvStore
import com.nizam.app.feature.iptv.M3u
import com.nizam.app.feature.iptv.Playlist
import com.nizam.app.feature.podcasts.Episode
import com.nizam.app.feature.podcasts.PodcastStore
import com.nizam.app.feature.podcasts.Rss
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The media desk: check an IPTV playlist for dead channels and keep a clean copy, see what changed
 * when a playlist refreshes, search inside your podcasts' episode notes, keep named episode
 * collections and cinema shelves, and one history across TV, podcasts and films.
 */
object MediaDesk {
    private fun p(c: Context) = c.getSharedPreferences("media_desk", Context.MODE_PRIVATE)

    /** Live if the stream answers with a 2xx/3xx within a few seconds. */
    suspend fun health(channels: List<Channel>, onProgress: (Float) -> Unit): Pair<List<Channel>, List<Channel>> = withContext(Dispatchers.IO) {
        val gate = Semaphore(24)
        var done = 0
        val results = coroutineScope {
            channels.map { ch -> async { gate.withPermit {
                val ok = runCatching {
                    val conn = (URL(ch.url).openConnection() as HttpURLConnection).apply { connectTimeout = 4000; readTimeout = 4000; requestMethod = "GET"
                        setRequestProperty("Range", "bytes=0-1023"); setRequestProperty("User-Agent", "Atlas/1.0") }
                    val code = conn.responseCode; conn.disconnect(); code in 200..399
                }.getOrDefault(false)
                synchronized(this@MediaDesk) { done++; onProgress(done / channels.size.toFloat()) }
                ch to ok
            } } }.awaitAll()
        }
        results.filter { it.second }.map { it.first } to results.filter { !it.second }.map { it.first }
    }

    data class Diff(val added: List<Channel>, val removed: List<Channel>, val fresh: List<Channel>)

    suspend fun diff(store: IptvStore, pl: Playlist): Diff {
        val old = store.channels(pl); val fresh = M3u.fetch(pl.source)
        val oldUrls = old.map { it.url }.toSet(); val newUrls = fresh.map { it.url }.toSet()
        return Diff(fresh.filter { it.url !in oldUrls }, old.filter { it.url !in newUrls }, fresh)
    }

    // ---------------------------------------------------------------- episode collections

    fun collections(c: Context): JSONObject = runCatching { JSONObject(p(c).getString("collections", "{}")!!) }.getOrElse { JSONObject() }
    fun addToCollection(c: Context, name: String, e: Episode) {
        val o = collections(c); val a = o.optJSONArray(name) ?: JSONArray()
        if ((0 until a.length()).none { a.getJSONObject(it).optString("audio") == e.audio })
            a.put(JSONObject().put("title", e.title).put("show", e.show).put("audio", e.audio).put("feed", e.feed))
        o.put(name, a); p(c).edit().putString("collections", o.toString()).apply()
    }

    // ---------------------------------------------------------------- cinema shelves

    fun shelves(c: Context): JSONObject = runCatching { JSONObject(p(c).getString("shelves", "{}")!!) }.getOrElse { JSONObject() }
    fun putOnShelf(c: Context, shelf: String, title: String) {
        val o = shelves(c); val a = o.optJSONArray(shelf) ?: JSONArray()
        if ((0 until a.length()).none { a.optString(it) == title }) a.put(title)
        o.put(shelf, a); p(c).edit().putString("shelves", o.toString()).apply()
    }
}

object MediaDeskSpecs {
    val all: List<Spec> = listOf(
        ScreenSpec("mediadesk", "Media desk", "Clean IPTV playlists, see what changed, search podcasts, collections, shelves and one history",
            Icons.Outlined.VideoLibrary, Hub.Media, "iptv playlist health dead channels m3u refresh podcast search episode collection cinema shelf history") { c, _ -> MediaDeskScreen(c) }
    )
}

@Composable
private fun MediaDeskScreen(container: AppContainer) {
    var tab by remember { mutableStateOf("TV") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("TV", "Podcasts", "Shelves", "History").forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t) }) }
        }
        when (tab) {
            "TV" -> TvPane()
            "Podcasts" -> PodcastPane()
            "Shelves" -> ShelvesPane()
            else -> HistoryPane()
        }
    }
}

@Composable
private fun TvPane() {
    val c = LocalContext.current
    val work = com.nizam.app.ui.design.rememberWorkScope()
    val store = remember { IptvStore(c) }
    var lists by remember { mutableStateOf(store.playlists()) }
    var pick by remember { mutableStateOf(lists.firstOrNull()) }
    var progress by remember { mutableFloatStateOf(-1f) }
    var result by remember { mutableStateOf<Pair<List<Channel>, List<Channel>>?>(null) }
    var diff by remember { mutableStateOf<MediaDesk.Diff?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    if (lists.isEmpty()) { Text("Add an IPTV playlist in the TV app first."); return }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { lists.take(4).forEach { l -> FilterChip(selected = pick == l, onClick = { pick = l; result = null; diff = null }, label = { Text(l.name.take(18)) }) } }
    val pl = pick ?: return
    Text("${pl.name}: ${pl.channels} channels", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { work.launch { progress = 0f; result = MediaDesk.health(store.channels(pl).take(600)) { progress = it }; progress = -1f } }, enabled = progress < 0) { Text("Check channel health") }
        OutlinedButton(onClick = { work.launch { error = null; diff = runCatching { MediaDesk.diff(store, pl) }.onFailure { error = it.message }.getOrNull() } }) { Text("What changed?") }
    }
    if (progress >= 0) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    result?.let { (live, dead) ->
        Text("✅ ${live.size} working · ❌ ${dead.size} dead", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (dead.isNotEmpty()) Button(onClick = {
            store.addPlaylist(pl.name + " (clean)", pl.source + "#clean", live); lists = store.playlists()
            com.nizam.app.ui.components.Feedback.show("Saved \"${pl.name} (clean)\" with ${live.size} working channels")
        }) { Text("Save a clean copy") }
        dead.take(20).forEach { Text("❌ ${it.name}", style = MaterialTheme.typography.bodySmall) }
    }
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    diff?.let { d ->
        Text("Since you saved it: +${d.added.size} new, −${d.removed.size} gone", style = MaterialTheme.typography.titleSmall)
        d.added.take(15).forEach { Text("➕ ${it.name}", style = MaterialTheme.typography.bodySmall) }
        d.removed.take(15).forEach { Text("➖ ${it.name}", style = MaterialTheme.typography.bodySmall) }
        if (d.added.isNotEmpty() || d.removed.isNotEmpty()) Button(onClick = { store.addPlaylist(pl.name, pl.source, d.fresh); lists = store.playlists(); diff = null }) { Text("Apply the update") }
    }
}

@Composable
private fun PodcastPane() {
    val c = LocalContext.current
    val work = com.nizam.app.ui.design.rememberWorkScope()
    val shows = remember { PodcastStore(c).shows() }
    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var collection by remember { mutableStateOf("Favourites") }
    var cols by remember { mutableStateOf(MediaDesk.collections(c)) }
    if (shows.isEmpty()) { Text("Subscribe to some podcasts first."); return }
    OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Search inside episode notes of your ${shows.size} shows") }, singleLine = true)
    Button(onClick = {
        busy = true
        work.launch {
            val words = q.lowercase().split(' ').filter { it.length > 2 }
            hits = runCatching { Rss.loadMany(shows.map { it.feed }) }.getOrDefault(emptyList()).flatMap { it.second }
                .filter { e -> val t = (e.title + " " + e.description).lowercase(); words.all { it in t } }.take(40)
            busy = false
        }
    }, enabled = q.isNotBlank() && !busy) { Text(if (busy) "Searching…" else "Search") }
    OutlinedTextField(collection, { collection = it }, Modifier.fillMaxWidth(), label = { Text("Add results to collection") }, singleLine = true)
    hits.forEach { e ->
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(e.title, style = MaterialTheme.typography.bodyLarge)
            Text("${e.show} · ${e.published.take(16)}", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { MediaDesk.addToCollection(c, collection.trim().ifBlank { "Favourites" }, e); cols = MediaDesk.collections(c) }) { Text("+ $collection") }
        }
    }
    Text("Your collections", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    cols.keys().forEach { name -> val a = cols.getJSONArray(name)
        Text("📁 $name (${a.length()})", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        (0 until a.length()).take(10).forEach { i -> Text("   • ${a.getJSONObject(i).optString("title")}", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun ShelvesPane() {
    val c = LocalContext.current
    val watch = remember { runCatching { com.nizam.app.feature.cinema.CinemaStore(c).watchlist() }.getOrDefault(emptyList()) }
    var shelves by remember { mutableStateOf(MediaDesk.shelves(c)) }
    var shelf by remember { mutableStateOf("Eid with family") }
    var open by remember { mutableStateOf<String?>(null) }
    OutlinedTextField(shelf, { shelf = it }, Modifier.fillMaxWidth(), label = { Text("Shelf name") }, singleLine = true)
    Text("From your watchlist, tap to put on \"$shelf\":", style = MaterialTheme.typography.bodySmall)
    watch.take(40).forEach { f -> val t = f.optString("title")
        Text("🎬 $t", Modifier.fillMaxWidth().clickable { MediaDesk.putOnShelf(c, shelf.trim(), t); shelves = MediaDesk.shelves(c) }.padding(vertical = 4.dp)) }
    if (watch.isEmpty()) Text("Your Cinema watchlist is empty.", style = MaterialTheme.typography.bodySmall)
    Text("Shelves", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    shelves.keys().forEach { name -> val a = shelves.getJSONArray(name)
        Text("📚 $name (${a.length()})", Modifier.clickable { open = if (open == name) null else name }, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        if (open == name) (0 until a.length()).forEach { Text("   • ${a.optString(it)}", style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun HistoryPane() {
    val c = LocalContext.current
    val films = remember { runCatching { com.nizam.app.feature.cinema.CinemaStore(c).history() }.getOrDefault(emptyList()) }
    val tv = remember { runCatching { IptvStore(c).let { s -> val all = s.allChannels(); s.recent().mapNotNull { u -> all.firstOrNull { it.url == u }?.name } } }.getOrDefault(emptyList()) }
    Text("Films and shows", style = MaterialTheme.typography.titleSmall)
    films.take(20).forEach { Text("🎬 $it", style = MaterialTheme.typography.bodyMedium) }
    if (films.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
    Text("TV channels", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    tv.take(20).forEach { Text("📺 $it", style = MaterialTheme.typography.bodyMedium) }
    if (tv.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
}
