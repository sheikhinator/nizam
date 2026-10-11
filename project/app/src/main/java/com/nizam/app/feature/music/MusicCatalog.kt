package com.nizam.app.feature.music

import android.content.Context
import com.nizam.app.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

/** An online playlist or artist card on Home and Search. */
data class MusicCard(val id: String, val title: String, val subtitle: String, val art: String, val kind: String)

/**
 * Audius: an open music network with millions of full-length songs from independent artists — free to
 * stream, no account or key. Atlas's main catalogue for music you don't already own.
 */
object Audius {
    @Volatile private var host: String? = null
    private const val APP = "app_name=Atlas"

    private suspend fun host(): String {
        host?.let { return it }
        val list = runCatching { JSONObject(Http.getJson("https://api.audius.co")).optJSONArray("data") }.getOrNull()
        val h = list?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.startsWith("https") }.shuffled().firstOrNull() }
            ?: "https://discoveryprovider.audius.co"
        host = h
        return h
    }

    private suspend fun get(path: String): JSONArray {
        val h = host()
        return runCatching { JSONObject(Http.getJson("$h/v1/$path${if ('?' in path) "&" else "?"}$APP")).optJSONArray("data") ?: JSONArray() }
            .getOrElse { host = null; JSONObject(Http.getJson("${host()}/v1/$path${if ('?' in path) "&" else "?"}$APP")).optJSONArray("data") ?: JSONArray() }
    }

    private fun art(o: JSONObject?) = o?.let { it.optString("480x480").ifBlank { it.optString("1000x1000") }.ifBlank { it.optString("150x150") } }.orEmpty()

    private suspend fun tracks(a: JSONArray): List<Track> {
        val h = host()
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optBoolean("is_streamable", true) }.map { t ->
            Track("au:${t.optString("id")}", t.optString("title"), t.optJSONObject("user")?.optString("name").orEmpty(),
                "", t.optLong("duration") * 1000, "$h/v1/tracks/${t.optString("id")}/stream?$APP", art(t.optJSONObject("artwork")),
                "audius", t.optString("release_date").take(4), t.optString("genre"))
        }
    }

    val GENRES = listOf("Electronic", "Hip-Hop/Rap", "Pop", "R&B/Soul", "Lo-Fi", "Alternative", "Rock", "Ambient", "Acoustic",
        "Jazz", "Classical", "World", "Latin", "Dancehall", "Soundtrack", "Funk", "Folk", "Country")

    suspend fun search(q: String): List<Track> = tracks(get("tracks/search?query=${enc(q)}"))
    suspend fun trending(genre: String? = null, time: String = "week"): List<Track> =
        tracks(get("tracks/trending?time=$time" + (genre?.let { "&genre=${enc(it)}" } ?: "")))
    suspend fun artists(q: String): List<MusicCard> {
        val a = get("users/search?query=${enc(q)}")
        return (0 until a.length()).map { a.getJSONObject(it) }.map { u ->
            MusicCard("au-user:${u.optString("id")}", u.optString("name"), "${u.optInt("follower_count")} followers · Artist",
                art(u.optJSONObject("profile_picture")), "artist")
        }
    }
    suspend fun artistTracks(id: String): List<Track> = tracks(get("users/${id.removePrefix("au-user:")}/tracks?sort=plays&limit=40"))
    suspend fun trendingPlaylists(): List<MusicCard> {
        val a = get("playlists/trending?time=week")
        return (0 until a.length()).map { a.getJSONObject(it) }.map { p ->
            MusicCard("au-pl:${p.optString("id")}", p.optString("playlist_name"), "Playlist · ${p.optJSONObject("user")?.optString("name").orEmpty()}",
                art(p.optJSONObject("artwork")), "playlist")
        }
    }
    suspend fun playlistTracks(id: String): List<Track> = tracks(get("playlists/${id.removePrefix("au-pl:")}/tracks"))
}

/** Deezer's public charts: what the world is playing, as 30-second previews (full songs come from your library or Audius). */
object Charts {
    suspend fun top(): List<Track> {
        val a = JSONObject(Http.getJson("https://api.deezer.com/chart/0/tracks?limit=50")).optJSONArray("data") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("preview").isNotBlank() }.map { d ->
            Track("dz:${d.optLong("id")}", d.optString("title"), d.optJSONObject("artist")?.optString("name").orEmpty(),
                d.optJSONObject("album")?.optString("title").orEmpty(), 30_000, d.optString("preview"),
                d.optJSONObject("album")?.optString("cover_xl").orEmpty(), "preview")
        }
    }
}

/** Live radio from around the world (radio-browser.info, community-run, no key). */
object Radio {
    private const val API = "https://de1.api.radio-browser.info/json/stations/search"
    val TAGS = listOf("Quran", "News", "Pop", "Lo-fi", "Jazz", "Classical", "Bollywood", "Talk", "Rock", "Chill", "Sufi", "Naat")

    private fun parse(body: String): List<Track> {
        val a = JSONArray(body)
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("url_resolved").startsWith("http") }.map { s ->
            Track("radio:${s.optString("stationuuid")}", s.optString("name").trim(),
                listOf(s.optString("country"), s.optString("tags").split(',').take(2).joinToString(", ")).filter { it.isNotBlank() }.joinToString(" · "),
                "Live radio", 0, s.optString("url_resolved"), s.optString("favicon"), "radio", genre = s.optString("tags").substringBefore(','))
        }.distinctBy { it.title.lowercase() }
    }
    private suspend fun q(params: String) = parse(Http.getJson("$API?$params&hidebroken=true&order=clickcount&reverse=true&limit=40",
        mapOf("User-Agent" to "Atlas/1.0")))

    suspend fun top(country: String? = null) = q(country?.let { "countrycode=$it" } ?: "has_extended_info=false")
    suspend fun tag(t: String) = q("tag=${enc(t.lowercase())}")
    suspend fun search(name: String) = q("name=${enc(name)}")

    fun favourites(c: Context): List<Track> = runCatching {
        val a = JSONArray(c.getSharedPreferences("music", 0).getString("radio_favs", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Track(o.optString("id"), o.optString("title"), o.optString("artist"), "Live radio", 0, o.optString("uri"), o.optString("art"), "radio") }
    }.getOrElse { emptyList() }
    fun toggleFavourite(c: Context, t: Track): Boolean {
        val now = favourites(c)
        val on = now.none { it.id == t.id }
        val list = if (on) listOf(t) + now else now.filter { it.id != t.id }
        c.getSharedPreferences("music", 0).edit().putString("radio_favs", JSONArray().apply {
            list.forEach { put(JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist).put("uri", it.uri).put("art", it.art)) }
        }.toString()).apply()
        return on
    }
}

/** Spotify-style mixes built from your own listening: Daily Mixes by artist, On Repeat, Forgotten favourites. */
object Mixes {
    data class Mix(val title: String, val subtitle: String, val tracks: List<Track>, val colour: Long)
    private val COLOURS = listOf(0xFF1DB954, 0xFFE8115B, 0xFF509BF5, 0xFFF59B23, 0xFFAF2896, 0xFF148A08, 0xFF8D67AB, 0xFFE91429)

    fun build(c: Context, all: List<Track>): List<Mix> {
        if (all.isEmpty()) return emptyList()
        val plays = MusicLibrary.plays(c)
        val liked = MusicLibrary.liked(c)
        fun score(t: Track) = (plays[t.id] ?: 0) * 2 + if (t.id in liked) 5 else 0
        val byArtist = all.filter { it.artist.isNotBlank() && !it.artist.startsWith("Unknown") }.groupBy { it.artist }
        val topArtists = byArtist.entries.sortedByDescending { e -> e.value.sumOf { score(it) } * 10 + e.value.size }.map { it.key }
        val out = mutableListOf<Mix>()
        // each Daily Mix: two or three artists you love, with their songs shuffled together
        topArtists.chunked(3).take(4).forEachIndexed { i, group ->
            val tracks = group.flatMap { byArtist.getValue(it) }.shuffled().take(40)
            if (tracks.size >= 4) out += Mix("Daily Mix ${i + 1}", group.joinToString(", "), tracks, COLOURS[i % COLOURS.size])
        }
        val onRepeat = plays.entries.sortedByDescending { it.value }.mapNotNull { e -> all.firstOrNull { it.id == e.key } }.take(30)
        if (onRepeat.size >= 4) out += Mix("On Repeat", "Songs you can't stop playing", onRepeat, COLOURS[4])
        val recent = MusicLibrary.recent(c).toSet()
        val forgotten = all.filter { (plays[it.id] ?: 0) >= 2 && it.id !in recent }.shuffled().take(30)
        if (forgotten.size >= 4) out += Mix("Rediscover", "Old favourites you haven't played lately", forgotten, COLOURS[5])
        val fresh = all.filter { (plays[it.id] ?: 0) == 0 }.shuffled().take(30)
        if (fresh.size >= 6) out += Mix("Never played", "Songs in your library waiting for a first listen", fresh, COLOURS[6])
        all.filter { it.genre.isNotBlank() }.groupBy { it.genre }.entries.sortedByDescending { it.value.size }.take(2).forEach { (g, l) ->
            if (l.size >= 6) out += Mix("$g Mix", "The $g in your library", l.shuffled().take(40), COLOURS[7])
        }
        return out
    }

    /** More like this: for Autoplay when the queue ends, and for "Start radio" on a song. */
    suspend fun similar(c: Context, seed: Track, exclude: Set<String>): List<Track> {
        val online = if (seed.source == "audius" || seed.source == "preview")
            runCatching { (if (seed.genre.isNotBlank()) Audius.trending(seed.genre) else Audius.search(seed.artist)) + Audius.search(seed.artist) }.getOrElse { emptyList() }
        else emptyList()
        val lib = runCatching { MusicLibrary.all(c) }.getOrElse { emptyList() }
        val local = lib.filter { it.artist == seed.artist && it.id != seed.id }.shuffled() +
            lib.filter { seed.genre.isNotBlank() && it.genre == seed.genre }.shuffled() + lib.shuffled()
        return (online + local).filter { it.id !in exclude && it.id != seed.id && it.uri.isNotBlank() }.distinctBy { it.id }.take(15)
    }
}
