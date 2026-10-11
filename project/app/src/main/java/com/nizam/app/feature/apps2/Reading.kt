package com.nizam.app.feature.apps2

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.Md
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Share
import com.nizam.app.feature.nx.Spec
import com.nizam.app.net.Ai
import com.nizam.app.net.Http
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.EmptySpec
import com.nizam.app.ui.design.EmptyState
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.pressable
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

object Reading {
    val all: List<Spec> = listOf(
        ScreenSpec("ebooks", "Free ebooks", "70,000 classic books from Project Gutenberg, read offline", Icons.Outlined.MenuBook, Hub.Learn, "books ebook read novel gutenberg classic") { _, _ -> EbooksScreen() },
        ScreenSpec("manga", "Comics & manga", "Search, follow and read manga (safe-rated only)", Icons.Outlined.AutoStories, Hub.Media, "manga comics mangadex read chapters") { _, _ -> MangaScreen() },
        ScreenSpec("anime", "Anime", "What's airing, top shows, and your watch list", Icons.Outlined.Animation, Hub.Media, "anime myanimelist seasonal watchlist") { _, _ -> AnimeScreen() },
        ScreenSpec("forums", "Forums", "Read Reddit communities, cleanly (no login)", Icons.Outlined.Forum, Hub.Social, "reddit forum community discussion r/pakistan") { c, _ -> ForumsScreen(c) },
    )
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
private fun JSONArray?.objs() = this?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }.orEmpty()

// ─────────────────────────────── Ebooks ───────────────────────────────
@Composable
fun EbooksScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val shelf = remember { JsonStore(ctx, "ebook_shelf") }
    var books by remember { mutableStateOf(shelf.all()) }
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<JSONObject>?>(null) }
    var reading by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }
    val r = reading
    if (r != null) { BookReader(r) { pos -> shelf.update(r.optString("id")) { it.put("pos", pos) }; books = shelf.all(); reading = null }; return }
    LaunchedEffect(Unit) { if (results == null) results = runCatching { JSONObject(Http.getJson("https://gutendex.com/books?sort=popular")).optJSONArray("results").objs() }.getOrDefault(emptyList()) }
    AtlasScreen(title = "Free ebooks", subtitle = "${books.size} on your shelf", hub = Hub.Learn) {
        item("q") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text("Title or author") }, singleLine = true)
            Button(onClick = { busy = true; scope.launch { results = runCatching { JSONObject(Http.getJson("https://gutendex.com/books?search=${enc(q)}")).optJSONArray("results").objs() }.getOrDefault(emptyList()); busy = false } },
                enabled = q.isNotBlank() && !busy) { Text("Search") }
        } }
        if (books.isNotEmpty()) {
            item("sh") { SectionHeader("Your shelf") }
            books.forEach { b -> item("s" + b.optString("id")) {
                val pct = if (b.optInt("len") > 0) b.optInt("pos") * 100 / b.optInt("len") else 0
                ListRow(b.optString("title"), supporting = "${b.optString("author")} · $pct% read", icon = Icons.Outlined.Book, tint = Hub.Learn.tint(), trailing = {
                    Text("✕", Modifier.padding(Space.s).pressable { shelf.remove(b.optString("id")); books = shelf.all() }) }) { reading = b }
            } }
        }
        item("rh") { SectionHeader(if (q.isBlank()) "Popular" else "Results") }
        val list = results
        if (list == null || busy) item("l") { Text("Loading…", Modifier.padding(Space.page)) }
        else if (list.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.MenuBook, "Nothing found", "Try an author like Tolstoy or Iqbal.")) }
        else list.forEach { b -> item("r" + b.optInt("id")) {
            val author = b.optJSONArray("authors").objs().joinToString { it.optString("name") }
            val fmts = b.optJSONObject("formats") ?: JSONObject()
            val txt = fmts.keys().asSequence().firstOrNull { it.startsWith("text/plain") }?.let { fmts.optString(it) }
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(fmts.optString("image/jpeg"), null, Modifier.size(52.dp, 76.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentScale = ContentScale.Crop)
                Column(Modifier.weight(1f).padding(horizontal = Space.m)) {
                    Text(b.optString("title"), style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    Text("$author · ${b.optInt("download_count")} reads", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(if (txt == null) "—" else "Get", Modifier.padding(Space.s).pressable {
                    if (txt == null) return@pressable
                    scope.launch {
                        Feedback.show("Downloading…")
                        runCatching {
                            val body = Http.getText(txt).substringAfter("*** START OF").substringAfter("***").substringBefore("*** END OF").trim()
                            val f = java.io.File(ctx.filesDir, "ebooks").apply { mkdirs() }.let { java.io.File(it, "${b.optInt("id")}.txt") }; f.writeText(body)
                            shelf.add(JSONObject().put("title", b.optString("title")).put("author", author).put("file", f.absolutePath).put("len", body.length).put("pos", 0))
                        }.onSuccess { books = shelf.all(); Feedback.show("On your shelf") }.onFailure { Feedback.show("Couldn't download") }
                    }
                }, color = Hub.Learn.tint(), style = MaterialTheme.typography.labelLarge)
            }
        } }
    }
}

@Composable
private fun BookReader(b: JSONObject, close: (Int) -> Unit) {
    val ctx = LocalContext.current
    val text = remember { runCatching { java.io.File(b.optString("file")).readText() }.getOrDefault("Couldn't open the file.") }
    val pageSize = 2200
    val pages = remember(text) { text.chunked(pageSize) }
    val prefs = remember { ctx.getSharedPreferences("reader", 0) }
    var page by remember { mutableStateOf((b.optInt("pos") / pageSize).coerceIn(0, (pages.size - 1).coerceAtLeast(0))) }
    var fontSize by remember { mutableStateOf(prefs.getFloat("font", 18f)) }
    var serif by remember { mutableStateOf(prefs.getBoolean("serif", true)) }
    AtlasScreen(title = b.optString("title").take(50), subtitle = "Page ${page + 1} of ${pages.size}", hub = Hub.Learn, actions = {
        Text("Close", Modifier.padding(Space.s).pressable { close(page * pageSize) }, style = MaterialTheme.typography.labelLarge) }) {
        item("ctl") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            Capsule("A−", false) { fontSize = (fontSize - 1).coerceAtLeast(13f); prefs.edit().putFloat("font", fontSize).apply() }
            Capsule("A+", false) { fontSize = (fontSize + 1).coerceAtMost(28f); prefs.edit().putFloat("font", fontSize).apply() }
            Capsule(if (serif) "Serif" else "Sans", serif) { serif = !serif; prefs.edit().putBoolean("serif", serif).apply() }
        } }
        item("text$page") { Text(pages.getOrElse(page) { "" }, Modifier.padding(Space.page), fontSize = fontSize.sp, lineHeight = (fontSize * 1.55f).sp,
            fontFamily = if (serif) FontFamily.Serif else FontFamily.Default) }
        item("nav") { Column(Modifier.padding(horizontal = Space.page)) {
            Slider(page.toFloat(), { page = it.toInt() }, valueRange = 0f..(pages.size - 1).coerceAtLeast(1).toFloat())
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedButton(onClick = { page = (page - 1).coerceAtLeast(0) }, modifier = Modifier.weight(1f)) { Text("← Previous") }
                Button(onClick = { page = (page + 1).coerceAtMost(pages.size - 1) }, modifier = Modifier.weight(1f)) { Text("Next →") }
            }
        } }
    }
}

// ─────────────────────────────── Manga ───────────────────────────────
private const val MD = "https://api.mangadex.org"
private val SAFE = "&contentRating[]=safe&contentRating[]=suggestive"

@Composable
fun MangaScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val follows = remember { JsonStore(ctx, "manga_follows") }
    var followed by remember { mutableStateOf(follows.all()) }
    var q by remember { mutableStateOf("") }
    var list by remember { mutableStateOf<List<JSONObject>?>(null) }
    var manga by remember { mutableStateOf<JSONObject?>(null) }
    var chapters by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var pages by remember { mutableStateOf<List<String>?>(null) }
    fun title(m: JSONObject) = m.optJSONObject("attributes")?.optJSONObject("title")?.let { t -> t.optString("en").ifBlank { t.keys().asSequence().firstOrNull()?.let { t.optString(it) } } }.orEmpty()
    fun cover(m: JSONObject): String? = m.optJSONArray("relationships").objs().firstOrNull { it.optString("type") == "cover_art" }?.optJSONObject("attributes")?.optString("fileName")
        ?.let { "https://uploads.mangadex.org/covers/${m.optString("id")}/$it.256.jpg" }
    suspend fun search(term: String) = runCatching {
        JSONObject(Http.getJson("$MD/manga?limit=20&includes[]=cover_art$SAFE" + if (term.isBlank()) "&order[followedCount]=desc" else "&title=${enc(term)}")).optJSONArray("data").objs()
    }.getOrDefault(emptyList())
    LaunchedEffect(Unit) { list = search("") }
    val p = pages
    if (p != null) {
        AtlasScreen(title = manga?.let { title(it) }.orEmpty(), subtitle = "${p.size} pages", hub = Hub.Media, actions = {
            Text("Close", Modifier.padding(Space.s).pressable { pages = null }, style = MaterialTheme.typography.labelLarge) }) {
            p.forEachIndexed { i, u -> item("pg$i") { AsyncImage(u, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) } }
        }
        return
    }
    val m = manga
    if (m != null) {
        AtlasScreen(title = title(m), subtitle = "${chapters.size} English chapters", hub = Hub.Media, actions = {
            Text("Back", Modifier.padding(Space.s).pressable { manga = null }, style = MaterialTheme.typography.labelLarge) }) {
            item("top") { Row(Modifier.padding(horizontal = Space.page)) {
                AsyncImage(cover(m), null, Modifier.size(110.dp, 160.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                Column(Modifier.padding(start = Space.m)) {
                    Text(m.optJSONObject("attributes")?.optJSONObject("description")?.optString("en").orEmpty().take(400), style = MaterialTheme.typography.bodySmall, maxLines = 9)
                    val isF = followed.any { it.optString("mid") == m.optString("id") }
                    Capsule(if (isF) "Following" else "Follow", isF) {
                        if (isF) followed.firstOrNull { it.optString("mid") == m.optString("id") }?.let { follows.remove(it.optString("id")) }
                        else follows.add(JSONObject().put("mid", m.optString("id")).put("title", title(m)).put("cover", cover(m)))
                        followed = follows.all()
                    }
                }
            } }
            chapters.forEach { ch -> item(ch.optString("id")) {
                val a = ch.optJSONObject("attributes") ?: JSONObject()
                ListRow("Chapter ${a.optString("chapter")}", supporting = a.optString("title").ifBlank { null }, icon = Icons.Outlined.AutoStories, tint = Hub.Media.tint()) {
                    scope.launch {
                        pages = runCatching {
                            val s = JSONObject(Http.getJson("$MD/at-home/server/${ch.optString("id")}"))
                            val c = s.getJSONObject("chapter"); val files = c.getJSONArray("dataSaver")
                            (0 until files.length()).map { "${s.optString("baseUrl")}/data-saver/${c.optString("hash")}/${files.getString(it)}" }
                        }.getOrElse { Feedback.show("Couldn't open the chapter"); null }
                    }
                }
            } }
        }
        return
    }
    AtlasScreen(title = "Comics & manga", subtitle = "From MangaDex · safe and suggestive ratings only", hub = Hub.Media) {
        item("q") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text("Search manga") }, singleLine = true)
            Button(onClick = { scope.launch { list = search(q) } }) { Text("Search") }
        } }
        if (followed.isNotEmpty()) {
            item("fh") { SectionHeader("Following") }
            item("f") { LazyRow(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                items(followed.size) { i -> val f = followed[i]
                    Column(Modifier.size(width = 96.dp, height = 170.dp).pressable {
                        scope.launch { runCatching { JSONObject(Http.getJson("$MD/manga/${f.optString("mid")}?includes[]=cover_art")).getJSONObject("data") }.getOrNull()?.let { open ->
                            manga = open; chapters = runCatching { JSONObject(Http.getJson("$MD/manga/${f.optString("mid")}/feed?translatedLanguage[]=en&order[chapter]=desc&limit=100$SAFE")).optJSONArray("data").objs() }.getOrDefault(emptyList()) } }
                    }) {
                        AsyncImage(f.optString("cover"), null, Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                        Text(f.optString("title"), style = MaterialTheme.typography.labelSmall, maxLines = 2)
                    }
                } } }
        }
        item("rh") { SectionHeader(if (q.isBlank()) "Most followed" else "Results") }
        list.orEmpty().forEach { x -> item(x.optString("id")) {
            Row(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 6.dp).pressable {
                manga = x; chapters = emptyList()
                scope.launch { chapters = runCatching { JSONObject(Http.getJson("$MD/manga/${x.optString("id")}/feed?translatedLanguage[]=en&order[chapter]=desc&limit=100$SAFE")).optJSONArray("data").objs() }.getOrDefault(emptyList()) }
            }, verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(cover(x), null, Modifier.size(52.dp, 74.dp).clip(RoundedCornerShape(6.dp)), contentScale = ContentScale.Crop)
                Column(Modifier.padding(start = Space.m)) {
                    Text(title(x), style = MaterialTheme.typography.titleSmall, maxLines = 2)
                    Text(x.optJSONObject("attributes")?.optString("status").orEmpty().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } }
    }
}

// ─────────────────────────────── Anime ───────────────────────────────
@Composable
fun AnimeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val listStore = remember { JsonStore(ctx, "anime_list") }
    var mine by remember { mutableStateOf(listStore.all()) }
    var tab by remember { mutableStateOf("Airing") }
    var q by remember { mutableStateOf("") }
    var shows by remember { mutableStateOf<List<JSONObject>?>(null) }
    var open by remember { mutableStateOf<JSONObject?>(null) }
    LaunchedEffect(tab) {
        if (tab == "My list") return@LaunchedEffect
        shows = null
        shows = runCatching { JSONObject(Http.getJson(when (tab) { "Airing" -> "https://api.jikan.moe/v4/seasons/now?sfw=true&limit=24"; else -> "https://api.jikan.moe/v4/top/anime?sfw=true&limit=24" })).optJSONArray("data").objs() }.getOrDefault(emptyList())
    }
    fun img(a: JSONObject) = a.optJSONObject("images")?.optJSONObject("jpg")?.optString("image_url")
    val o = open
    if (o != null) {
        AtlasScreen(title = o.optString("title_english").ifBlank { o.optString("title") }, subtitle = "${o.optString("type")} · ${o.optInt("episodes")} eps · ★ ${o.optDouble("score", 0.0)}", hub = Hub.Media, actions = {
            Text("Back", Modifier.padding(Space.s).pressable { open = null }, style = MaterialTheme.typography.labelLarge) }) {
            item("i") { AsyncImage(img(o), null, Modifier.fillMaxWidth().padding(Space.page).aspectRatio(0.7f).clip(RoundedCornerShape(16.dp)), contentScale = ContentScale.Crop) }
            item("s") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                listOf("Plan", "Watching", "Done").forEach { st ->
                    val cur = mine.firstOrNull { it.optInt("mal") == o.optInt("mal_id") }
                    Capsule(st, cur?.optString("status") == st) {
                        cur?.let { listStore.remove(it.optString("id")) }
                        listStore.add(JSONObject().put("mal", o.optInt("mal_id")).put("title", o.optString("title_english").ifBlank { o.optString("title") }).put("img", img(o)).put("status", st).put("eps", o.optInt("episodes")).put("seen", 0))
                        mine = listStore.all()
                    }
                } } }
            item("syn") { Text(o.optString("synopsis"), Modifier.padding(Space.page), style = MaterialTheme.typography.bodyMedium) }
            item("g") { Text(o.optJSONArray("genres").objs().joinToString { it.optString("name") }, Modifier.padding(horizontal = Space.page), style = MaterialTheme.typography.labelMedium, color = Hub.Media.tint()) }
        }
        return
    }
    AtlasScreen(title = "Anime", subtitle = "Data from MyAnimeList (via Jikan)", hub = Hub.Media) {
        item("t") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            listOf("Airing", "Top", "My list").forEach { t -> Capsule(t, tab == t) { tab = t } } } }
        item("q") { Row(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(q, { q = it }, Modifier.weight(1f), placeholder = { Text("Search anime") }, singleLine = true)
            Button(onClick = { tab = "Search"; scope.launch { shows = runCatching { JSONObject(Http.getJson("https://api.jikan.moe/v4/anime?sfw=true&limit=20&q=${enc(q)}")).optJSONArray("data").objs() }.getOrDefault(emptyList()) } }, enabled = q.isNotBlank()) { Text("Search") }
        } }
        if (tab == "My list") {
            if (mine.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.Animation, "Your list is empty", "Open a show and tap Plan, Watching or Done.")) }
            mine.groupBy { it.optString("status") }.forEach { (st, l) ->
                item("h$st") { SectionHeader(st) }
                l.forEach { a -> item("m" + a.optString("id")) {
                    ListRow(a.optString("title"), supporting = if (st == "Watching") "Episode ${a.optInt("seen")} of ${a.optInt("eps").takeIf { it > 0 } ?: "?"}" else null, icon = Icons.Outlined.Animation, trailing = {
                        if (st == "Watching") Text("+1 ep", Modifier.padding(Space.s).pressable { listStore.update(a.optString("id")) { it.put("seen", it.optInt("seen") + 1) }; mine = listStore.all() }, color = Hub.Media.tint())
                        else Text("✕", Modifier.padding(Space.s).pressable { listStore.remove(a.optString("id")); mine = listStore.all() })
                    })
                } }
            }
        } else {
            val l = shows
            if (l == null) item("l") { Text("Loading…", Modifier.padding(Space.page)) }
            else l.chunked(3).forEachIndexed { i, row -> item("r$i") { Row(Modifier.padding(horizontal = Space.page, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                row.forEach { a -> Column(Modifier.weight(1f).pressable { open = a }) {
                    AsyncImage(img(a), null, Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop)
                    Text(a.optString("title_english").ifBlank { a.optString("title") }, style = MaterialTheme.typography.labelMedium, maxLines = 2)
                } }
                repeat(3 - row.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
            } } }
        }
    }
}

// ─────────────────────────────── Forums ───────────────────────────────
@Composable
fun ForumsScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = remember { ctx.getSharedPreferences("forums", 0) }
    var subs by remember { mutableStateOf(p.getString("subs", "pakistan,lahore,karachi,islam,technology,worldnews,cricket").orEmpty().split(',').filter { it.isNotBlank() }) }
    var sub by remember { mutableStateOf(subs.first()) }
    var sort by remember { mutableStateOf("hot") }
    var posts by remember { mutableStateOf<List<JSONObject>?>(null) }
    var open by remember { mutableStateOf<JSONObject?>(null) }
    var comments by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    var summary by remember { mutableStateOf<String?>(null) }
    var add by remember { mutableStateOf("") }
    val ua = mapOf("User-Agent" to "android:com.nizam.atlas:1.0 (reader)")
    LaunchedEffect(sub, sort) {
        posts = null
        posts = runCatching { JSONObject(Http.getText("https://www.reddit.com/r/$sub/$sort.json?limit=40&raw_json=1", ua)).getJSONObject("data").optJSONArray("children").objs()
            .map { it.getJSONObject("data") }.filter { !it.optBoolean("over_18") } }.getOrElse { emptyList() }
    }
    val o = open
    if (o != null) {
        LaunchedEffect(o) {
            summary = null
            comments = runCatching { JSONArray(Http.getText("https://www.reddit.com${o.optString("permalink")}.json?limit=60&raw_json=1", ua)).getJSONObject(1).getJSONObject("data")
                .optJSONArray("children").objs().mapNotNull { it.optJSONObject("data") }.filter { it.optString("body").isNotBlank() } }.getOrDefault(emptyList())
        }
        AtlasScreen(title = o.optString("title").take(80), subtitle = "r/${o.optString("subreddit")} · ${o.optInt("score")} points · ${o.optInt("num_comments")} comments", hub = Hub.Social, actions = {
            Text("Back", Modifier.padding(Space.s).pressable { open = null }, style = MaterialTheme.typography.labelLarge) }) {
            item("b") { Column(Modifier.padding(horizontal = Space.page)) {
                if (o.optString("selftext").isNotBlank()) Md(o.optString("selftext").take(6000))
                o.optString("url").takeIf { it.matches(Regex(".*\\.(jpg|jpeg|png|gif|webp)$")) }?.let { AsyncImage(it, null, Modifier.fillMaxWidth().padding(top = Space.s), contentScale = ContentScale.FillWidth) }
                if (!o.optBoolean("is_self")) Text("Open link", Modifier.padding(vertical = Space.s).pressable { Share.open(ctx, o.optString("url")) }, color = Hub.Social.tint())
            } }
            item("sum") { Row(Modifier.padding(Space.page)) { Capsule("Summarise the discussion", false) {
                scope.launch { summary = runCatching { Ai.generate(container.settingsRepository, "Summarise this Reddit thread: the main opinions, points of agreement and disagreement, and any useful facts.\nPost: ${o.optString("title")}\n${o.optString("selftext").take(2000)}\nComments:\n" +
                    comments.take(40).joinToString("\n") { "- " + it.optString("body").take(300) }, maxOutputTokens = 600) }.getOrElse { it.message } }
            } } }
            summary?.let { s -> item("s") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) { Md(s) } } }
            comments.forEach { c -> item(c.optString("id")) { Column(Modifier.padding(horizontal = Space.page, vertical = 6.dp)) {
                Text("${c.optString("author")} · ${c.optInt("score")}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(c.optString("body").take(1500), style = MaterialTheme.typography.bodyMedium)
            } } }
        }
        return
    }
    AtlasScreen(title = "r/$sub", subtitle = "Read-only, no account · adult posts hidden", hub = Hub.Social) {
        item("subs") { LazyRow(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            items(subs.size) { i -> Capsule(subs[i], sub == subs[i]) { sub = subs[i] } } } }
        item("ctl") { Row(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            listOf("hot", "new", "top").forEach { s -> Capsule(s, sort == s) { sort = s } }
            OutlinedTextField(add, { add = it.trim().removePrefix("r/") }, Modifier.weight(1f), placeholder = { Text("add r/…") }, singleLine = true)
            Text("+", Modifier.padding(Space.s).pressable { if (add.isNotBlank()) { subs = (subs + add).distinct(); p.edit().putString("subs", subs.joinToString(",")).apply(); sub = add; add = "" } }, style = MaterialTheme.typography.titleLarge)
        } }
        val l = posts
        if (l == null) item("l") { Text("Loading…", Modifier.padding(Space.page)) }
        else if (l.isEmpty()) item("e") { EmptyState(EmptySpec(Icons.Outlined.Forum, "Nothing here", "The community may be private or the name misspelt.")) }
        else l.forEach { x -> item(x.optString("id")) {
            ListRow(x.optString("title"), supporting = "${x.optInt("score")} ↑ · ${x.optInt("num_comments")} comments · u/${x.optString("author")}", icon = Icons.Outlined.Forum, tint = Hub.Social.tint()) { open = x }
        } }
    }
}
