package com.nizam.app.feature.cinema

import android.content.Context
import com.nizam.app.net.Http
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.time.LocalDate

/**
 * Stremio's model: the app holds no content. Addons supply catalogs, metadata, streams and subtitles
 * over a small open protocol (manifest.json + /{resource}/{type}/{id}.json). Atlas speaks the same
 * protocol, so the official addons work as-is.
 *
 * No restrictions on which addons you install or what they return: HTTP, HLS and torrent (infoHash/magnet)
 * streams all play inside Atlas — torrents through Atlas's own engine (Torrents.kt). Only YouTube and web links
 * (external pages) open in the browser or the YouTube app, since they aren't video files.
 */
data class Addon(val url: String, val name: String, val description: String, val resources: List<String>,
                 val types: List<String>, val catalogs: List<AddonCatalog>, val idPrefixes: List<String>, val builtIn: Boolean) {
    val base get() = url.removeSuffix("/manifest.json").removeSuffix("/")
}
data class AddonCatalog(val type: String, val id: String, val name: String, val genres: List<String>, val searchable: Boolean)
data class Meta(val id: String, val type: String, val name: String, val poster: String?, val background: String?,
                val description: String, val year: String, val rating: String, val genres: List<String>, val cast: List<String>,
                val director: String, val runtime: String, val trailer: String?, val videos: List<Episode>)
data class Episode(val id: String, val season: Int, val episode: Int, val title: String, val released: String, val thumbnail: String?)
data class StreamOption(val addon: String, val title: String, val detail: String, val url: String?, val externalUrl: String?, val ytId: String?,
                        val magnet: String? = null, val fileIdx: Int? = null, val headers: Map<String, String> = emptyMap())
data class Sub(val lang: String, val url: String)
data class StreamResult(val options: List<StreamOption>, val addonsAsked: List<String>, val torrentsSkipped: Int)

val DEFAULT_ADDONS = listOf(
    "https://v3-cinemeta.strem.io/manifest.json",      // catalogs + metadata for films and series
    "https://opensubtitles-v3.strem.io/manifest.json", // subtitles
    "https://v3-channels.strem.io/manifest.json"       // YouTube channels
)

private fun idPath(id: String) = id.split(":").joinToString(":") { URLEncoder.encode(it, "UTF-8") }
private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

object Stremio {
    private fun p(c: Context) = c.getSharedPreferences("stremio", 0)
    private val manifestCache = HashMap<String, Addon>()

    fun installedUrls(c: Context): List<String> =
        p(c).getString("addons", null)?.split("\n")?.filter { it.isNotBlank() } ?: DEFAULT_ADDONS
    fun setInstalled(c: Context, urls: List<String>) = p(c).edit().putString("addons", urls.distinct().joinToString("\n")).apply()

    suspend fun manifest(url: String): Addon {
        manifestCache[url]?.let { return it }
        val m = JSONObject(Http.getJson(url))
        val resTypes = mutableListOf<String>()
        val res = m.optJSONArray("resources")?.let { a -> (0 until a.length()).map { i ->
            a.opt(i).let { r -> if (r is JSONObject) { r.optJSONArray("types")?.let { t -> (0 until t.length()).forEach { resTypes += t.optString(it) } }
                r.optString("name") } else r.toString() } } } ?: emptyList()
        val cats = m.optJSONArray("catalogs")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { c ->
            val extra = c.optJSONArray("extra") ?: JSONArray()
            val genre = (0 until extra.length()).map { extra.getJSONObject(it) }.firstOrNull { it.optString("name") == "genre" }
            AddonCatalog(c.optString("type"), c.optString("id"), c.optString("name", c.optString("id")),
                genre?.optJSONArray("options")?.let { o -> (0 until o.length()).map { o.optString(it) } } ?: emptyList(),
                (0 until extra.length()).any { extra.getJSONObject(it).optString("name") == "search" })
        } } ?: emptyList()
        val a = Addon(url, m.optString("name").ifBlank { url.substringAfter("//").substringBefore('/') }, m.optString("description"), res,
            ((m.optJSONArray("types")?.let { t -> (0 until t.length()).map { t.optString(it) } } ?: emptyList()) + resTypes).distinct(), cats,
            m.optJSONArray("idPrefixes")?.let { t -> (0 until t.length()).map { t.optString(it) } } ?: emptyList(),
            url in DEFAULT_ADDONS)
        manifestCache[url] = a; return a
    }

    suspend fun addons(c: Context): List<Addon> = coroutineScope {
        installedUrls(c).map { u -> async { runCatching { manifest(u) }.getOrNull() } }.awaitAll().filterNotNull()
    }

    private fun parseMeta(o: JSONObject): Meta {
        val videos = o.optJSONArray("videos")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            Episode(it.optString("id"), it.optInt("season"), it.optInt("episode", it.optInt("number")),
                it.optString("name", it.optString("title")), it.optString("released").take(10), it.optString("thumbnail").ifBlank { null })
        } }?.filter { it.season > 0 }?.sortedWith(compareBy({ it.season }, { it.episode })) ?: emptyList()
        fun list(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
        val trailer = o.optJSONArray("trailerStreams")?.optJSONObject(0)?.optString("ytId")
            ?: o.optJSONArray("trailers")?.optJSONObject(0)?.optString("source")
        return Meta(o.optString("id", o.optString("imdb_id")), o.optString("type"), o.optString("name"),
            o.optString("poster").ifBlank { null }, o.optString("background").ifBlank { null }, o.optString("description"),
            o.optString("releaseInfo", o.optString("year")), o.optString("imdbRating"), list("genres").ifEmpty { list("genre") },
            list("cast"), list("director").joinToString(), o.optString("runtime"), trailer?.ifBlank { null }, videos)
    }

    suspend fun catalog(addon: Addon, cat: AddonCatalog, genre: String? = null, search: String? = null, skip: Int = 0): List<Meta> {
        val extra = listOfNotNull(genre?.let { "genre=${enc(it)}" }, search?.let { "search=${enc(it)}" }, skip.takeIf { it > 0 }?.let { "skip=$it" })
        val url = "${addon.base}/catalog/${cat.type}/${cat.id}" + (if (extra.isEmpty()) "" else "/" + extra.joinToString("&")) + ".json"
        val a = JSONObject(Http.getJson(url)).optJSONArray("metas") ?: return emptyList()
        return (0 until a.length()).map { parseMeta(a.getJSONObject(it)) }.filter { it.name.isNotBlank() }
    }

    /** Metadata from the first addon that serves this id's prefix. */
    suspend fun meta(c: Context, type: String, id: String): Meta? {
        for (a in addons(c).filter { "meta" in it.resources && (it.types.isEmpty() || type in it.types) }) {
            if (a.idPrefixes.isNotEmpty() && a.idPrefixes.none { id.startsWith(it) }) continue
            val m = runCatching { JSONObject(Http.getJson("${a.base}/meta/$type/${idPath(id)}.json")).optJSONObject("meta") }.getOrNull()
            if (m != null) return parseMeta(m)
        }
        return null
    }

    /** Every stream every installed addon offers for this title, gathered in parallel. */
    suspend fun streams(c: Context, type: String, id: String): StreamResult = coroutineScope {
        // every addon that serves streams is asked; one that doesn't declare types is asked anyway
        val asked = addons(c).filter { ("stream" in it.resources || it.resources.isEmpty()) && (it.types.isEmpty() || type in it.types) }
        val results = asked.map { a ->
            async {
                runCatching {
                    val all = (JSONObject(Http.getJson("${a.base}/stream/$type/${idPath(id)}.json")).optJSONArray("streams") ?: JSONArray())
                        .let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) } }
                    all.map { o ->
                        val url = o.optString("url").ifBlank { null }
                        // torrent streams become a magnet link, with the addon's trackers and file index kept
                        val magnet = when {
                            url?.startsWith("magnet:") == true -> url
                            o.has("infoHash") -> buildString {
                                append("magnet:?xt=urn:btih:").append(o.optString("infoHash"))
                                append("&dn=").append(URLEncoder.encode(o.optString("title", o.optString("name")).lines().first(), "UTF-8"))
                                o.optJSONArray("sources")?.let { src -> (0 until src.length()).map { src.optString(it) }
                                    .filter { it.startsWith("tracker:") }.forEach { t -> append("&tr=").append(URLEncoder.encode(t.removePrefix("tracker:"), "UTF-8")) } }
                            }
                            else -> null
                        }
                        // some addons need their own request headers (referer, user agent) to be sent with the stream
                        val headers = o.optJSONObject("behaviorHints")?.optJSONObject("proxyHeaders")?.optJSONObject("request")
                            ?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } } ?: emptyMap()
                        StreamOption(a.name, o.optString("name", a.name), o.optString("title", o.optString("description")),
                            url?.takeIf { magnet == null }, o.optString("externalUrl").ifBlank { null }, o.optString("ytId").ifBlank { null }, magnet,
                            if (o.has("fileIdx") && !o.isNull("fileIdx")) o.optInt("fileIdx") else null, headers)
                    } to 0
                }.getOrElse { emptyList<StreamOption>() to 0 }
            }
        }.awaitAll()
        StreamResult(results.flatMap { it.first }, asked.map { it.name }, results.sumOf { it.second })
    }

    suspend fun subtitles(c: Context, type: String, id: String): List<Sub> = coroutineScope {
        addons(c).filter { "subtitles" in it.resources }.map { a ->
            async { runCatching {
                val arr = JSONObject(Http.getJson("${a.base}/subtitles/$type/${idPath(id)}.json")).optJSONArray("subtitles") ?: JSONArray()
                (0 until arr.length()).map { arr.getJSONObject(it) }.map { Sub(it.optString("lang"), it.optString("url")) }
            }.getOrElse { emptyList() } }
        }.awaitAll().flatten().distinctBy { it.lang }
    }
}

/** Your library: saved titles, where you stopped in each video, and what to continue. */
object Library {
    private fun f(c: Context) = File(c.filesDir, "library.json")
    private fun load(c: Context) = runCatching { JSONObject(f(c).readText()) }.getOrElse { JSONObject() }
    private fun save(c: Context, o: JSONObject) = f(c).writeText(o.toString())

    fun items(c: Context): List<JSONObject> = load(c).optJSONObject("items")?.let { i -> i.keys().asSequence().map { i.getJSONObject(it) }.toList() }
        ?.sortedByDescending { it.optLong("touched") } ?: emptyList()
    fun has(c: Context, id: String) = load(c).optJSONObject("items")?.has(id) == true
    fun toggle(c: Context, m: Meta) {
        val o = load(c); val items = o.optJSONObject("items") ?: JSONObject()
        if (items.has(m.id)) items.remove(m.id)
        else items.put(m.id, JSONObject().put("id", m.id).put("type", m.type).put("name", m.name).put("poster", m.poster ?: "")
            .put("touched", System.currentTimeMillis()))
        save(c, o.put("items", items))
    }
    fun progress(c: Context, videoId: String): Pair<Long, Long> =
        load(c).optJSONObject("progress")?.optJSONObject(videoId)?.let { it.optLong("pos") to it.optLong("dur") } ?: (0L to 0L)
    fun saveProgress(c: Context, meta: Meta?, videoId: String, label: String, pos: Long, dur: Long) {
        val o = load(c); val p = o.optJSONObject("progress") ?: JSONObject()
        p.put(videoId, JSONObject().put("pos", pos).put("dur", dur).put("at", System.currentTimeMillis())
            .put("metaId", meta?.id ?: videoId).put("type", meta?.type ?: "movie").put("name", meta?.name ?: label)
            .put("label", label).put("poster", meta?.poster ?: ""))
        save(c, o.put("progress", p))
    }
    /** Started, not finished, most recent first. */
    fun continueWatching(c: Context): List<JSONObject> = load(c).optJSONObject("progress")?.let { p ->
        p.keys().asSequence().map { k -> p.getJSONObject(k).put("videoId", k) }.toList() }
        ?.filter { val d = it.optLong("dur"); d > 0 && it.optLong("pos") > 60_000 && it.optLong("pos") < d * 0.92 }
        ?.sortedByDescending { it.optLong("at") }?.distinctBy { it.optString("metaId") } ?: emptyList()

    /** New episodes of series in your library, from the last week to the next month. */
    suspend fun calendar(c: Context): List<Pair<Meta, Episode>> {
        val from = LocalDate.now().minusDays(7).toString(); val to = LocalDate.now().plusDays(30).toString()
        return items(c).filter { it.optString("type") == "series" }.take(20).flatMap { item ->
            val m = runCatching { Stremio.meta(c, "series", item.optString("id")) }.getOrNull() ?: return@flatMap emptyList()
            m.videos.filter { it.released in from..to }.map { m to it }
        }.sortedBy { it.second.released }
    }
}
