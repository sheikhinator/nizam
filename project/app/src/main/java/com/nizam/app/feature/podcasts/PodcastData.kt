package com.nizam.app.feature.podcasts

import android.content.Context
import android.util.Xml
import com.nizam.app.net.Http
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.net.URLEncoder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue

data class Show(val feed: String, val title: String, val author: String, val image: String?, val description: String = "",
                val episodeCount: Int = 0, val sources: String = "")
data class Episode(
    val id: String, val feed: String, val show: String, val title: String, val audio: String,
    val published: String, val durationSec: Int, val description: String, val image: String?
)

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

/** A small disk cache so every list and feed shows instantly the second time, then refreshes quietly. */
object PodcastCache {
    private var dir: File? = null
    fun init(c: Context) { if (dir == null) dir = File(c.cacheDir, "podcast_cache").apply { mkdirs() } }
    private fun f(key: String) = dir?.let { File(it, key.hashCode().toUInt().toString(16) + "_" + key.length) }
    fun put(key: String, text: String) { runCatching { f(key)?.writeText(text) } }
    fun get(key: String, maxAgeMs: Long = Long.MAX_VALUE): String? = f(key)?.takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < maxAgeMs }
        ?.let { runCatching { it.readText() }.getOrNull() }
    fun age(key: String): Long = f(key)?.takeIf { it.exists() }?.let { System.currentTimeMillis() - it.lastModified() } ?: Long.MAX_VALUE
    fun size(): Long = dir?.listFiles()?.sumOf { it.length() } ?: 0L
    fun clear() { dir?.listFiles()?.forEach { it.delete() } }

    fun showsToJson(list: List<Show>) = JSONArray().apply { list.forEach { put(JSONObject().put("feed", it.feed).put("title", it.title)
        .put("author", it.author).put("image", it.image ?: "").put("d", it.description).put("n", it.episodeCount).put("src", it.sources)) } }.toString()
    fun showsFromJson(s: String?): List<Show> = runCatching { val a = JSONArray(s ?: return emptyList())
        (0 until a.length()).map { a.getJSONObject(it) }.map { Show(it.optString("feed"), it.optString("title"), it.optString("author"),
            it.optString("image").ifBlank { null }, it.optString("d"), it.optInt("n"), it.optString("src")) } }.getOrElse { emptyList() }
}

/**
 * Discovery across several free directories at once — Apple, fyyd, gpodder and (with your own key) Podcast Index.
 * Every source runs in parallel with its own short timeout, results stream in as each one answers, and everything is cached.
 */
object PodcastDirectory {
    private const val SOURCE_TIMEOUT = 6_000L

    /** Apple chart genres: label to genre id. */
    val GENRES = listOf("Religion" to 1314, "News" to 1489, "Business" to 1321, "Technology" to 1318, "Comedy" to 1303,
        "Education" to 1304, "Health" to 1512, "Society" to 1324, "History" to 1487, "Science" to 1533, "Sports" to 1545,
        "True Crime" to 1488, "Arts" to 1301, "Kids" to 1305, "TV & Film" to 1309, "Music" to 1310)

    private fun genres(o: JSONObject) = o.optJSONArray("genres")?.let { g -> (0 until g.length()).map { g.optString(it) }
        .filter { it != "Podcasts" }.joinToString(" · ") }.orEmpty()

    private suspend fun itunes(q: String, limit: Int): List<Show> {
        val r = JSONObject(Http.getJson("https://itunes.apple.com/search?media=podcast&limit=$limit&term=${enc(q)}"))
            .optJSONArray("results") ?: JSONArray()
        return (0 until r.length()).map { r.getJSONObject(it) }.mapNotNull { o ->
            val feed = o.optString("feedUrl"); if (feed.isBlank()) return@mapNotNull null
            Show(feed, o.optString("collectionName"), o.optString("artistName"),
                o.optString("artworkUrl600").ifBlank { o.optString("artworkUrl100") }.ifBlank { null }, genres(o), o.optInt("trackCount"), "Apple")
        }
    }

    private suspend fun fyyd(q: String, limit: Int): List<Show> {
        val d = JSONObject(Http.getJson("https://api.fyyd.de/0.2/search/podcast?title=${enc(q)}&count=$limit")).optJSONArray("data") ?: return emptyList()
        return (0 until d.length()).map { d.getJSONObject(it) }.mapNotNull { o ->
            val feed = o.optString("xmlURL"); if (!feed.startsWith("http")) return@mapNotNull null
            Show(feed, o.optString("title"), o.optString("author"), o.optString("imgURL").ifBlank { null },
                o.optString("description").replace(Regex("<[^>]+>"), "").take(200), o.optInt("episode_count"), "fyyd")
        }
    }

    private suspend fun gpodder(q: String, limit: Int): List<Show> {
        val a = JSONArray(Http.getJson("https://gpodder.net/search.json?q=${enc(q)}"))
        return (0 until minOf(a.length(), limit)).map { a.getJSONObject(it) }.mapNotNull { o ->
            val feed = o.optString("url"); if (!feed.startsWith("http")) return@mapNotNull null
            Show(feed, o.optString("title"), o.optString("author"), o.optString("scaled_logo_url").ifBlank { o.optString("logo_url") }.ifBlank { null },
                o.optString("description").take(200), 0, "gpodder")
        }
    }

    /** Podcast Index needs a free key and secret, which live only on this phone. */
    private suspend fun podcastIndex(c: Context, q: String, limit: Int): List<Show> {
        val keys = PodcastSources.indexKeys(c) ?: return emptyList()
        val now = (System.currentTimeMillis() / 1000).toString()
        val hash = java.security.MessageDigest.getInstance("SHA-1").digest((keys.first + keys.second + now).toByteArray())
            .joinToString("") { "%02x".format(it) }
        val o = JSONObject(Http.getJson("https://api.podcastindex.org/api/1.0/search/byterm?q=${enc(q)}&max=$limit",
            mapOf("X-Auth-Key" to keys.first, "X-Auth-Date" to now, "Authorization" to hash)))
        val f = o.optJSONArray("feeds") ?: return emptyList()
        return (0 until f.length()).map { f.getJSONObject(it) }.mapNotNull { x ->
            val feed = x.optString("url"); if (!feed.startsWith("http")) return@mapNotNull null
            Show(feed, x.optString("title"), x.optString("author"), x.optString("artwork").ifBlank { x.optString("image") }.ifBlank { null },
                x.optString("description").take(200), x.optInt("episodeCount"), "Podcast Index")
        }
    }

    private fun norm(s: Show) = s.feed.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')
    private fun titleKey(s: Show) = s.title.lowercase().filter { it.isLetterOrDigit() }

    /** Merges shows from several sources: same feed or same title+author become one entry that remembers where it was found. */
    fun merge(lists: List<List<Show>>): List<Show> {
        val out = LinkedHashMap<String, Show>(); val byTitle = HashMap<String, String>()
        // interleave so the first screen of results mixes every source's best hits
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) for (l in lists) {
            val s = l.getOrNull(i) ?: continue
            val key = byTitle[titleKey(s) + "|" + s.author.lowercase().take(12)] ?: norm(s)
            val prev = out[key]
            out[key] = if (prev == null) s else prev.copy(
                image = prev.image ?: s.image, description = prev.description.ifBlank { s.description },
                episodeCount = maxOf(prev.episodeCount, s.episodeCount),
                sources = (prev.sources.split(" · ") + s.sources).filter { it.isNotBlank() }.distinct().joinToString(" · "))
            byTitle[titleKey(s) + "|" + s.author.lowercase().take(12)] = key
        }
        return out.values.toList()
    }

    /** Streams merged results: the fastest directory answers first, the rest fold in as they arrive. */
    fun searchFlow(c: Context, query: String, limit: Int = 25): Flow<List<Show>> = channelFlow {
        PodcastCache.init(c)
        val key = "search:" + query.trim().lowercase()
        PodcastCache.showsFromJson(PodcastCache.get(key, 3 * 86_400_000L)).takeIf { it.isNotEmpty() }?.let { send(it) }
        val lock = Mutex(); val got = mutableListOf<List<Show>>()
        val sources: List<suspend () -> List<Show>> = listOf({ itunes(query, limit) }, { fyyd(query, limit) },
            { gpodder(query, limit) }, { podcastIndex(c, query, limit) })
        coroutineScope {
            sources.forEach { src -> launch {
                val r = withTimeoutOrNull(SOURCE_TIMEOUT) { runCatching { src() }.getOrNull() }.orEmpty()
                if (r.isNotEmpty()) lock.withLock { got += r; val m = merge(got); send(m); PodcastCache.put(key, PodcastCache.showsToJson(m)) }
            } }
        }
    }

    suspend fun search(query: String, limit: Int = 25): List<Show> = coroutineScope {
        merge(listOf(async { withTimeoutOrNull(SOURCE_TIMEOUT) { runCatching { itunes(query, limit) }.getOrNull() }.orEmpty() },
            async { withTimeoutOrNull(SOURCE_TIMEOUT) { runCatching { fyyd(query, limit) }.getOrNull() }.orEmpty() },
            async { withTimeoutOrNull(SOURCE_TIMEOUT) { runCatching { gpodder(query, limit) }.getOrNull() }.orEmpty() }).awaitAll())
    }

    /** Fills in feed URLs for many Apple ids in one request instead of one call per show. */
    private suspend fun lookupMany(ids: List<String>): Map<String, JSONObject> {
        if (ids.isEmpty()) return emptyMap()
        val r = JSONObject(Http.getJson("https://itunes.apple.com/lookup?id=${ids.joinToString(",")}")).optJSONArray("results") ?: return emptyMap()
        return (0 until r.length()).map { r.getJSONObject(it) }.associateBy { it.optLong("collectionId").toString() }
    }

    /** A chart, already resolved to real feeds. Cached for six hours; stale cache is still returned when offline. */
    suspend fun chart(c: Context, country: String = "pk", genre: Int? = null, limit: Int = 30, force: Boolean = false): List<Show> {
        PodcastCache.init(c)
        val key = "chart:$country:${genre ?: 0}:$limit"
        if (!force) PodcastCache.showsFromJson(PodcastCache.get(key, 6 * 3_600_000L)).takeIf { it.isNotEmpty() }?.let { return it }
        val fresh = withTimeoutOrNull(9_000) { runCatching {
            val url = "https://itunes.apple.com/${country.lowercase()}/rss/toppodcasts/limit=$limit" + (genre?.let { "/genre=$it" } ?: "") + "/json"
            val entries = JSONObject(Http.getJson(url)).optJSONObject("feed")?.optJSONArray("entry") ?: JSONArray()
            val raw = (0 until entries.length()).map { entries.getJSONObject(it) }.mapNotNull { e ->
                val id = e.optJSONObject("id")?.optJSONObject("attributes")?.optString("im:id") ?: return@mapNotNull null
                Triple(id, e.optJSONObject("im:name")?.optString("label").orEmpty(), e.optJSONObject("im:artist")?.optString("label").orEmpty())
            }
            val looked = lookupMany(raw.map { it.first })
            raw.mapNotNull { (id, name, artist) ->
                val o = looked[id]; val feed = o?.optString("feedUrl").orEmpty()
                if (feed.isBlank()) null else Show(feed, name, artist,
                    o!!.optString("artworkUrl600").ifBlank { o.optString("artworkUrl100") }.ifBlank { null }, genres(o), o.optInt("trackCount"), "Apple")
            }
        }.getOrNull() }
        if (!fresh.isNullOrEmpty()) { PodcastCache.put(key, PodcastCache.showsToJson(fresh)); return fresh }
        return PodcastCache.showsFromJson(PodcastCache.get(key))
    }

    /** gpodder's community toplist — a second opinion on what people actually subscribe to. */
    suspend fun communityTop(c: Context, limit: Int = 30): List<Show> {
        PodcastCache.init(c)
        val key = "gpodder:top:$limit"
        PodcastCache.showsFromJson(PodcastCache.get(key, 24 * 3_600_000L)).takeIf { it.isNotEmpty() }?.let { return it }
        val fresh = withTimeoutOrNull(8_000) { runCatching {
            val a = JSONArray(Http.getJson("https://gpodder.net/toplist/$limit.json"))
            (0 until a.length()).map { a.getJSONObject(it) }.mapNotNull { o ->
                val feed = o.optString("url"); if (!feed.startsWith("http")) null
                else Show(feed, o.optString("title"), o.optString("author"), o.optString("scaled_logo_url").ifBlank { o.optString("logo_url") }.ifBlank { null },
                    o.optString("description").take(200), 0, "gpodder")
            }
        }.getOrNull() }
        if (!fresh.isNullOrEmpty()) { PodcastCache.put(key, PodcastCache.showsToJson(fresh)); return fresh }
        return PodcastCache.showsFromJson(PodcastCache.get(key))
    }

    // kept for older callers
    suspend fun charts(country: String = "pk", limit: Int = 30): List<Show> = emptyList()
    suspend fun resolve(show: Show): Show = show
}

/** Optional extra sources the person switches on themselves. Keys never leave the phone and never go in the repo. */
object PodcastSources {
    private fun f(c: Context) = File(c.filesDir, "podcast_sources.json")
    private fun load(c: Context) = runCatching { JSONObject(f(c).readText()) }.getOrElse { JSONObject() }
    fun indexKeys(c: Context): Pair<String, String>? = load(c).let { o ->
        val k = o.optString("piKey"); val s = o.optString("piSecret"); if (k.isBlank() || s.isBlank()) null else k to s }
    fun setIndexKeys(c: Context, key: String, secret: String) = f(c).writeText(load(c).put("piKey", key.trim()).put("piSecret", secret.trim()).toString())
    fun country(c: Context): String = load(c).optString("country").ifBlank { "pk" }
    fun setCountry(c: Context, v: String) = f(c).writeText(load(c).put("country", v).toString())
}

/** Reads a podcast RSS feed into a show and its episodes. Parsed feeds are cached so a show opens instantly next time. */
object Rss {
    private fun seconds(raw: String): Int {
        val t = raw.trim(); if (t.isBlank()) return 0
        return if (t.contains(":")) t.split(":").map { it.trim().toDoubleOrNull()?.toInt() ?: 0 }
            .fold(0) { acc, v -> acc * 60 + v } else t.toDoubleOrNull()?.toInt() ?: 0
    }
    private val TAGS = Regex("<[^>]+>")
    private val SPACES = Regex("\\s+")
    private fun clean(s: String) = TAGS.replace(s, " ").replace("&nbsp;", " ").replace("&amp;", "&").let { SPACES.replace(it, " ") }.trim()

    /** The last parse of this feed, if any — instant, no network. */
    fun cached(feed: String): Pair<Show, List<Episode>>? = PodcastCache.get("feed:$feed")?.let { runCatching { parse(feed, it) }.getOrNull() }
    fun cacheAge(feed: String) = PodcastCache.age("feed:$feed")

    suspend fun load(feed: String): Pair<Show, List<Episode>> {
        val xml = Http.getText(feed)
        val parsed = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { parse(feed, xml) }
        PodcastCache.put("feed:$feed", xml)
        return parsed
    }

    /** Loads many feeds at once — used to build the "new from your shows" list. */
    suspend fun loadMany(feeds: List<String>, maxAgeMs: Long = 3 * 3_600_000L): List<Pair<Show, List<Episode>>> = coroutineScope {
        feeds.map { f -> async {
            if (cacheAge(f) < maxAgeMs) cached(f) else withTimeoutOrNull(12_000) { runCatching { load(f) }.getOrNull() } ?: cached(f)
        } }.awaitAll().filterNotNull()
    }

    fun parse(feed: String, xml: String): Pair<Show, List<Episode>> {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            setInput(xml.reader())
        }
        var showTitle = ""; var showAuthor = ""; var showImage: String? = null; var showDesc = ""
        val episodes = mutableListOf<Episode>()
        var inItem = false
        var title = ""; var audio = ""; var published = ""; var duration = ""; var desc = ""; var image: String? = null; var guid = ""
        val text = StringBuilder()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase()
                    when {
                        name == "item" -> { inItem = true; title = ""; audio = ""; published = ""; duration = ""; desc = ""; image = null; guid = "" }
                        name == "enclosure" && inItem -> audio = parser.getAttributeValue(null, "url").orEmpty()
                        name == "itunes:image" -> {
                            val href = parser.getAttributeValue(null, "href")
                            if (inItem) image = href else if (showImage == null) showImage = href
                        }
                    }
                    text.setLength(0)
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> text.append(parser.text)
                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase()
                    if (inItem) when (name) {
                        "title" -> if (title.isBlank()) title = text.toString().trim()
                        "pubdate" -> published = text.toString().trim()
                        "itunes:duration" -> duration = text.toString()
                        "description", "itunes:summary", "content:encoded" -> if (desc.isBlank()) desc = clean(text.take(12000).toString())
                        "guid" -> guid = text.toString().trim()
                        "item" -> {
                            inItem = false
                            if (audio.isNotBlank()) episodes += Episode(guid.ifBlank { audio }, feed, showTitle, title, audio,
                                published.take(16), seconds(duration), desc.take(4000), image)
                        }
                    } else when (name) {
                        "title" -> if (showTitle.isBlank()) showTitle = text.toString().trim()
                        "itunes:author" -> if (showAuthor.isBlank()) showAuthor = text.toString().trim()
                        "description" -> if (showDesc.isBlank()) showDesc = clean(text.toString())
                        "url" -> { val v = text.toString().trim()
                            if (showImage == null && v.startsWith("http") && v.contains(Regex("\\.(jpg|jpeg|png|webp)", RegexOption.IGNORE_CASE))) showImage = v }
                    }
                }
            }
            event = parser.next()
        }
        val show = Show(feed, showTitle, showAuthor, showImage, showDesc, episodes.size)
        return show to episodes.map { it.copy(show = showTitle, image = it.image ?: showImage) }
    }
}

/** Live download progress per episode id (0..100, -1 = starting), read by the download buttons. */
object PodcastDl { val pct = androidx.compose.runtime.mutableStateMapOf<String, Int>(); var version by androidx.compose.runtime.mutableIntStateOf(0) }

/** Subscriptions, progress, Up Next, stars and downloads — all local. */
class PodcastStore(private val c: Context) {
    private val f = File(c.filesDir, "podcasts.json")
    private fun load() = runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
    private fun save(o: JSONObject) = f.writeText(o.toString())

    fun shows(): List<Show> = load().optJSONArray("shows")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
        Show(it.optString("feed"), it.optString("title"), it.optString("author"), it.optString("image").ifBlank { null },
            it.optString("description"), it.optInt("count")) } } ?: emptyList()
    fun subscribed(feed: String) = shows().any { it.feed == feed }
    fun toggle(show: Show) {
        val kept = shows().filter { it.feed != show.feed }
        val list = if (kept.size == shows().size) kept + show else kept
        save(load().put("shows", JSONArray().apply { list.forEach {
            put(JSONObject().put("feed", it.feed).put("title", it.title).put("author", it.author)
                .put("image", it.image ?: "").put("description", it.description).put("count", it.episodeCount)) } }))
    }

    fun position(id: String) = load().optJSONObject("pos")?.optJSONObject(id)?.optLong("pos") ?: 0L
    fun duration(id: String) = load().optJSONObject("pos")?.optJSONObject(id)?.optLong("dur") ?: 0L
    fun played(id: String): Boolean { val d = duration(id); return d > 0 && position(id) > d * 0.95 }
    fun savePosition(ep: Episode, pos: Long, dur: Long) {
        val o = load(); val p = o.optJSONObject("pos") ?: JSONObject()
        p.put(ep.id, JSONObject().put("pos", pos).put("dur", dur).put("at", System.currentTimeMillis())
            .put("title", ep.title).put("show", ep.show).put("audio", ep.audio).put("image", ep.image ?: "").put("feed", ep.feed))
        save(o.put("pos", p))
    }
    fun inProgress(): List<JSONObject> = load().optJSONObject("pos")?.let { p -> p.keys().asSequence().map { p.getJSONObject(it).put("id", it) }.toList() }
        ?.filter { val d = it.optLong("dur"); d > 0 && it.optLong("pos") > 30_000 && it.optLong("pos") < d * 0.95 }
        ?.sortedByDescending { it.optLong("at") } ?: emptyList()

    fun starred(): Set<String> = load().optJSONArray("starred")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
    fun star(id: String) { val s = starred().toMutableSet(); if (!s.add(id)) s.remove(id); save(load().put("starred", JSONArray(s.toList()))) }

    fun queue(): List<Episode> = load().optJSONArray("queue")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { ep(it) } } ?: emptyList()
    fun setQueue(list: List<Episode>) = save(load().put("queue", JSONArray().apply { list.forEach { put(json(it)) } }))
    fun enqueue(e: Episode, next: Boolean) {
        val q = queue().filter { it.id != e.id }
        setQueue(if (next) listOf(e) + q else q + e)
    }

    private fun json(e: Episode) = JSONObject().put("id", e.id).put("feed", e.feed).put("show", e.show).put("title", e.title)
        .put("audio", e.audio).put("published", e.published).put("dur", e.durationSec).put("desc", e.description).put("image", e.image ?: "")
    private fun ep(o: JSONObject) = Episode(o.optString("id"), o.optString("feed"), o.optString("show"), o.optString("title"),
        o.optString("audio"), o.optString("published"), o.optInt("dur"), o.optString("desc"), o.optString("image").ifBlank { null })

    fun downloadDir() = File(c.filesDir, "podcasts").apply { mkdirs() }
    fun downloaded(e: Episode) = File(downloadDir(), e.id.hashCode().toString() + ".mp3").takeIf { it.exists() }
    suspend fun download(e: Episode): File = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val out = File(downloadDir(), e.id.hashCode().toString() + ".mp3")
        val part = File(downloadDir(), e.id.hashCode().toString() + ".part")      // a half-finished file never counts as downloaded
        val key = "podcast:${e.id.hashCode()}"
        try {
            val conn = (java.net.URL(e.audio).openConnection() as java.net.HttpURLConnection).apply {
                instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 60_000; setRequestProperty("User-Agent", "Atlas/1.0")
            }
            if (conn.responseCode !in 200..299) throw IllegalStateException("The podcast server said HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong
            com.nizam.app.net.Transfers.progress(c, key, "Podcast · ${e.title}", -1); PodcastDl.pct[e.id] = -1
            conn.inputStream.use { input -> part.outputStream().use { o ->
                val buf = ByteArray(1 shl 16); var done = 0L; var shown = -1
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    o.write(buf, 0, n); done += n
                    val pct = if (total > 0) (done * 100 / total).toInt() else -1
                    if (pct != shown) { shown = pct; PodcastDl.pct[e.id] = pct; com.nizam.app.net.Transfers.progress(c, key, "Podcast · ${e.title}", pct, "${done / 1_048_576} MB") }
                }
            } }
            part.renameTo(out); rememberDownload(e); PodcastDl.pct.remove(e.id); PodcastDl.version++
            com.nizam.app.net.Transfers.done(c, key, e.title, "Downloaded — plays offline in Podcasts.")
        } catch (ex: Exception) {
            part.delete(); PodcastDl.pct.remove(e.id)
            com.nizam.app.net.Transfers.failed(c, key, e.title, com.nizam.app.ui.components.Feedback.friendly(ex))
            throw ex
        }
        out
    }
    @Synchronized private fun rememberDownload(e: Episode) { val o = load(); save(o.put("dls", (o.optJSONObject("dls") ?: JSONObject()).put(e.id, json(e)))) }
    @Synchronized fun forgetDownload(id: String) { val o = load(); o.optJSONObject("dls")?.remove(id); save(o) }
    /** Downloaded episodes with their titles, newest download first. */
    fun downloadedEpisodes(): List<Episode> = load().optJSONObject("dls")?.let { d -> d.keys().asSequence().map { ep(d.getJSONObject(it)) }.toList() }
        ?.filter { downloaded(it) != null }?.reversed() ?: emptyList()
    fun downloads(): List<File> = downloadDir().listFiles()?.toList() ?: emptyList()

    // ── bookmarks: a moment plus your own note ──
    fun bookmarks(episodeId: String): List<Pair<Long, String>> =
        load().optJSONObject("marks")?.optJSONArray(episodeId)?.let { a ->
            (0 until a.length()).map { a.getJSONObject(it) }.map { it.optLong("at") to it.optString("note") }
        }?.sortedBy { it.first } ?: emptyList()
    fun addBookmark(episodeId: String, at: Long, note: String) {
        val o = load(); val marks = o.optJSONObject("marks") ?: JSONObject()
        val list = marks.optJSONArray(episodeId) ?: JSONArray()
        marks.put(episodeId, list.put(JSONObject().put("at", at).put("note", note)))
        save(o.put("marks", marks))
    }
    fun allBookmarks(): List<Triple<String, Long, String>> = load().optJSONObject("marks")?.let { m ->
        m.keys().asSequence().flatMap { id -> bookmarks(id).map { Triple(id, it.first, it.second) } }.toList()
    } ?: emptyList()

    // ── per-show playback settings ──
    fun showSpeed(feed: String) = load().optJSONObject("showspeed")?.optDouble(feed, 0.0)?.toFloat() ?: 0f
    fun setShowSpeed(feed: String, v: Float) {
        val o = load(); save(o.put("showspeed", (o.optJSONObject("showspeed") ?: JSONObject()).put(feed, v.toDouble())))
    }
    fun skipIntro(feed: String) = load().optJSONObject("skipintro")?.optInt(feed, 0) ?: 0
    fun setSkipIntro(feed: String, seconds: Int) {
        val o = load(); save(o.put("skipintro", (o.optJSONObject("skipintro") ?: JSONObject()).put(feed, seconds)))
    }
    fun autoDownload(feed: String) = load().optJSONArray("autodl")?.let { a -> (0 until a.length()).any { a.optString(it) == feed } } ?: false
    fun toggleAutoDownload(feed: String) {
        val set = (load().optJSONArray("autodl")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()).toMutableSet()
        if (!set.add(feed)) set.remove(feed)
        save(load().put("autodl", JSONArray(set.toList())))
    }

    // ── listening stats ──
    fun addListened(seconds: Int, atSpeed: Float) {
        val o = load(); val st = o.optJSONObject("stats") ?: JSONObject()
        st.put("seconds", st.optLong("seconds") + seconds)
        st.put("saved", st.optLong("saved") + (seconds * (atSpeed - 1f)).toLong().coerceAtLeast(0))
        save(o.put("stats", st))
    }
    fun stats(): Pair<Long, Long> = load().optJSONObject("stats")?.let { it.optLong("seconds") to it.optLong("saved") } ?: (0L to 0L)
}

/** How automatic downloads behave: how many per show, whether played ones are removed, mobile data, and a storage cap. */
object PodcastPolicy {
    private fun p(c: Context) = c.getSharedPreferences("podpolicy", 0)
    fun keep(c: Context, feed: String) = p(c).getInt("keep:$feed", p(c).getInt("keep", 2))
    fun setKeep(c: Context, feed: String?, n: Int) = p(c).edit().putInt(if (feed == null) "keep" else "keep:$feed", n.coerceIn(1, 20)).apply()
    fun deletePlayed(c: Context) = p(c).getBoolean("delplayed", true)
    fun mobileData(c: Context) = p(c).getBoolean("mobile", false)
    fun capMb(c: Context) = p(c).getInt("cap", 1500)
    fun set(c: Context, deletePlayed: Boolean = deletePlayed(c), mobile: Boolean = mobileData(c), cap: Int = capMb(c)) =
        p(c).edit().putBoolean("delplayed", deletePlayed).putBoolean("mobile", mobile).putInt("cap", cap.coerceIn(100, 50_000)).apply()
    fun usedMb(store: PodcastStore) = store.downloads().sumOf { it.length() } / 1_048_576
    fun roomFor(c: Context, store: PodcastStore) = usedMb(store) < capMb(c)

    /** Removes played downloads (if asked), extras beyond each show's keep count, then the oldest until under the cap. */
    fun tidy(c: Context, store: PodcastStore) {
        val eps = store.downloadedEpisodes()
        fun drop(e: Episode) { store.downloaded(e)?.delete(); store.forgetDownload(e.id) }
        if (deletePlayed(c)) eps.filter { store.played(it.id) }.forEach(::drop)
        store.downloadedEpisodes().groupBy { it.feed }.forEach { (feed, l) -> if (store.autoDownload(feed)) l.drop(keep(c, feed)).forEach(::drop) }
        val left = store.downloadedEpisodes().toMutableList()
        while (usedMb(store) > capMb(c) && left.isNotEmpty()) drop(left.removeAt(left.lastIndex))
    }
}
