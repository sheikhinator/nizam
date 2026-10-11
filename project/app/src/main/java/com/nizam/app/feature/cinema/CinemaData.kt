package com.nizam.app.feature.cinema

import android.content.Context
import com.nizam.app.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder

data class Film(
    val id: Int, val title: String, val year: String, val poster: String?, val backdrop: String?,
    val rating: Double, val overview: String, val genreIds: List<Int> = emptyList()
)
data class Provider(val name: String, val logo: String?, val kind: String)
data class CastMember(val name: String, val role: String, val photo: String?)
data class FilmDetail(
    val film: Film, val runtime: Int, val genres: List<String>, val tagline: String, val imdbId: String?,
    val trailerKey: String?, val cast: List<CastMember>, val director: String, val providers: List<Provider>,
    val providerLink: String?, val similar: List<Film>
)
data class ImdbScores(val imdb: String?, val votes: String?, val rotten: String?, val metacritic: String?)
data class FreeFilm(val id: String, val title: String, val year: String, val description: String) {
    val thumb get() = "https://archive.org/services/img/$id"
}

/** Keys live in plain prefs so the Cinema tab can read them synchronously on first frame. */
object CinemaKeys {
    private fun p(c: Context) = c.getSharedPreferences("cinema", Context.MODE_PRIVATE)
    fun tmdb(c: Context) = p(c).getString("tmdb", "").orEmpty()
    fun omdb(c: Context) = p(c).getString("omdb", "").orEmpty()
    fun region(c: Context) = p(c).getString("region", "PK").orEmpty()
    fun save(c: Context, tmdb: String, omdb: String, region: String) =
        p(c).edit().putString("tmdb", tmdb.trim()).putString("omdb", omdb.trim()).putString("region", region.trim().uppercase()).apply()
}

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
private const val IMG = "https://image.tmdb.org/t/p/"

/** TMDB. Accepts either a v3 API key or a v4 read-access token — people get handed both. */
object Tmdb {
    private suspend fun get(c: Context, path: String, params: String = ""): JSONObject {
        val key = CinemaKeys.tmdb(c)
        if (key.isBlank()) throw IllegalStateException("Add your free TMDB key in Cinema settings (⚙).")
        val bearer = key.length > 60
        val sep = if (path.contains("?")) "&" else "?"
        val url = "https://api.themoviedb.org/3$path$sep" + (if (bearer) "" else "api_key=$key&") + params
        return JSONObject(Http.getJson(url, if (bearer) mapOf("Authorization" to "Bearer $key") else emptyMap()))
    }

    private fun film(o: JSONObject) = Film(
        id = o.optInt("id"), title = o.optString("title", o.optString("name")),
        year = o.optString("release_date", o.optString("first_air_date")).take(4),
        poster = o.optString("poster_path").takeIf { it.isNotBlank() && it != "null" }?.let { "${IMG}w342$it" },
        backdrop = o.optString("backdrop_path").takeIf { it.isNotBlank() && it != "null" }?.let { "${IMG}w780$it" },
        rating = o.optDouble("vote_average", 0.0), overview = o.optString("overview"),
        genreIds = o.optJSONArray("genre_ids")?.let { a -> (0 until a.length()).map { a.optInt(it) } } ?: emptyList()
    )
    private fun films(o: JSONObject) = o.optJSONArray("results")?.let { a -> (0 until a.length()).map { film(a.getJSONObject(it)) } }
        ?.filter { it.title.isNotBlank() } ?: emptyList()

    suspend fun trending(c: Context, page: Int = 1) = films(get(c, "/trending/movie/week", "page=$page"))
    suspend fun popular(c: Context, page: Int = 1) = films(get(c, "/movie/popular", "page=$page"))
    suspend fun topRated(c: Context, page: Int = 1) = films(get(c, "/movie/top_rated", "page=$page"))
    suspend fun upcoming(c: Context, page: Int = 1) = films(get(c, "/movie/now_playing", "page=$page&region=${CinemaKeys.region(c)}"))
    suspend fun search(c: Context, q: String, page: Int = 1) = films(get(c, "/search/movie", "query=${enc(q)}&page=$page&include_adult=false"))

    suspend fun discover(
        c: Context, genre: Int?, fromYear: Int?, toYear: Int?, minRating: Double, sort: String, language: String?, page: Int = 1
    ): List<Film> {
        val p = buildString {
            append("sort_by=$sort&page=$page&include_adult=false&vote_count.gte=150")
            genre?.let { append("&with_genres=$it") }
            fromYear?.let { append("&primary_release_date.gte=$it-01-01") }
            toYear?.let { append("&primary_release_date.lte=$it-12-31") }
            if (minRating > 0) append("&vote_average.gte=$minRating")
            language?.let { append("&with_original_language=$it") }
        }
        return films(get(c, "/discover/movie", p))
    }

    private var genreCache: Map<Int, String> = emptyMap()
    suspend fun genres(c: Context): Map<Int, String> {
        if (genreCache.isNotEmpty()) return genreCache
        val a = get(c, "/genre/movie/list").optJSONArray("genres") ?: JSONArray()
        genreCache = (0 until a.length()).associate { a.getJSONObject(it).let { g -> g.optInt("id") to g.optString("name") } }
        return genreCache
    }

    suspend fun detail(c: Context, id: Int): FilmDetail {
        val o = get(c, "/movie/$id", "append_to_response=videos,credits,external_ids,watch/providers,similar")
        val videos = o.optJSONObject("videos")?.optJSONArray("results") ?: JSONArray()
        val trailer = (0 until videos.length()).map { videos.getJSONObject(it) }
            .filter { it.optString("site") == "YouTube" }
            .sortedByDescending { (if (it.optString("type") == "Trailer") 2 else 0) + (if (it.optBoolean("official")) 1 else 0) }
            .firstOrNull()?.optString("key")
        val credits = o.optJSONObject("credits")
        val castArr = credits?.optJSONArray("cast") ?: JSONArray()
        val cast = (0 until minOf(12, castArr.length())).map { castArr.getJSONObject(it) }.map {
            CastMember(it.optString("name"), it.optString("character"),
                it.optString("profile_path").takeIf { p -> p.isNotBlank() && p != "null" }?.let { p -> "${IMG}w185$p" })
        }
        val crew = credits?.optJSONArray("crew") ?: JSONArray()
        val director = (0 until crew.length()).map { crew.getJSONObject(it) }.firstOrNull { it.optString("job") == "Director" }
            ?.optString("name").orEmpty()
        val region = o.optJSONObject("watch/providers")?.optJSONObject("results")?.optJSONObject(CinemaKeys.region(c))
        val providers = mutableListOf<Provider>()
        listOf("free" to "Free", "ads" to "Free with ads", "flatrate" to "Stream", "rent" to "Rent", "buy" to "Buy").forEach { (k, label) ->
            region?.optJSONArray(k)?.let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { pr ->
                providers += Provider(pr.optString("provider_name"),
                    pr.optString("logo_path").takeIf { it.isNotBlank() }?.let { "${IMG}w92$it" }, label)
            } }
        }
        return FilmDetail(
            film = film(o), runtime = o.optInt("runtime"),
            genres = o.optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("name") } } ?: emptyList(),
            tagline = o.optString("tagline"),
            imdbId = o.optString("imdb_id").takeIf { it.startsWith("tt") }
                ?: o.optJSONObject("external_ids")?.optString("imdb_id")?.takeIf { it.startsWith("tt") },
            trailerKey = trailer, cast = cast, director = director,
            providers = providers.distinctBy { it.name + it.kind }, providerLink = region?.optString("link"),
            similar = o.optJSONObject("similar")?.let { films(it) }?.take(12) ?: emptyList()
        )
    }
}

/** IMDb, Rotten Tomatoes and Metacritic scores via OMDb — the free, legitimate route to IMDb data. */
object Omdb {
    suspend fun scores(c: Context, imdbId: String): ImdbScores? {
        val key = CinemaKeys.omdb(c); if (key.isBlank()) return null
        val o = JSONObject(Http.getJson("https://www.omdbapi.com/?i=$imdbId&apikey=$key"))
        if (o.optString("Response") != "True") return null
        val ratings = o.optJSONArray("Ratings") ?: JSONArray()
        fun src(name: String) = (0 until ratings.length()).map { ratings.getJSONObject(it) }
            .firstOrNull { it.optString("Source") == name }?.optString("Value")
        return ImdbScores(o.optString("imdbRating").takeIf { it != "N/A" }, o.optString("imdbVotes").takeIf { it != "N/A" },
            src("Rotten Tomatoes"), src("Metacritic"))
    }
}

/**
 * The Internet Archive's feature-film collection: public-domain and freely licensed films that play
 * right here in Atlas's own player — no ads, no redirects, no key needed.
 */
object Archive {
    suspend fun search(query: String, page: Int = 1): List<FreeFilm> {
        val q = "collection:(feature_films) AND mediatype:(movies)" + if (query.isNotBlank()) " AND title:(${query.trim()})" else ""
        val url = "https://archive.org/advancedsearch.php?q=${enc(q)}&fl[]=identifier&fl[]=title&fl[]=year&fl[]=description" +
            "&sort[]=downloads+desc&rows=36&page=$page&output=json"
        val docs = JSONObject(Http.getJson(url)).optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
        return (0 until docs.length()).map { docs.getJSONObject(it) }.map {
            FreeFilm(it.optString("identifier"), it.optString("title"), it.optString("year"),
                it.optString("description").replace(Regex("<[^>]+>"), "").take(500))
        }
    }

    /** Best playable file for an item: H.264 MP4 first, then any MP4, then Ogg. */
    suspend fun streamUrl(id: String): String? {
        val files = JSONObject(Http.getJson("https://archive.org/metadata/$id")).optJSONArray("files") ?: return null
        val all = (0 until files.length()).map { files.getJSONObject(it) }
        val pick = all.firstOrNull { it.optString("format").contains("h.264", true) && it.optString("name").endsWith(".mp4", true) }
            ?: all.firstOrNull { it.optString("name").endsWith(".mp4", true) }
            ?: all.firstOrNull { it.optString("name").endsWith(".ogv", true) }
        return pick?.let { "https://archive.org/download/$id/" + enc(it.optString("name")).replace("+", "%20") }
    }

    /** Is a TMDB film also free on the Archive? Title + year must both match to avoid false hits. */
    suspend fun match(title: String, year: String): FreeFilm? = runCatching {
        search("\"${title.replace("\"", "")}\"").firstOrNull { it.year == year || year.isBlank() }
    }.getOrNull()
}

/** Watchlist, your ratings, history, and where you stopped — all local. */
class CinemaStore(c: Context) {
    private val f = File(c.filesDir, "cinema.json")
    private var o: JSONObject = runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
    private fun save() = f.writeText(o.toString())
    private fun arr(k: String) = o.optJSONArray(k) ?: JSONArray().also { o.put(k, it) }

    fun watchlist(): List<JSONObject> = arr("watchlist").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    fun inWatchlist(id: Int) = watchlist().any { it.optInt("id") == id }
    fun toggleWatchlist(f: Film) {
        val kept = watchlist().filter { it.optInt("id") != f.id }
        o.put("watchlist", JSONArray(if (kept.size == watchlist().size)
            kept + JSONObject().put("id", f.id).put("title", f.title).put("year", f.year).put("poster", f.poster ?: "")
                .put("rating", f.rating).put("added", System.currentTimeMillis())
        else kept))
        save()
    }
    fun rate(f: Film, stars: Int) {
        o.put("ratings", (o.optJSONObject("ratings") ?: JSONObject()).put("${f.id}",
            JSONObject().put("title", f.title).put("year", f.year).put("stars", stars).put("at", System.currentTimeMillis())))
        save()
    }
    fun myRating(id: Int) = o.optJSONObject("ratings")?.optJSONObject("$id")?.optInt("stars") ?: 0
    fun ratings(): List<JSONObject> = o.optJSONObject("ratings")?.let { r -> r.keys().asSequence().map { r.getJSONObject(it) }.toList() } ?: emptyList()
    fun logWatched(title: String) {
        val a = arr("history"); a.put(JSONObject().put("title", title).put("at", System.currentTimeMillis())); save()
    }
    fun history(): List<String> = arr("history").let { a -> (0 until a.length()).map { a.getJSONObject(it).optString("title") } }.reversed()
    fun position(url: String) = o.optJSONObject("positions")?.optLong(url.hashCode().toString()) ?: 0L
    fun savePosition(url: String, ms: Long) {
        o.put("positions", (o.optJSONObject("positions") ?: JSONObject()).put(url.hashCode().toString(), ms)); save()
    }

    /** What the AI sees: taste, not just a list. */
    fun summaryForAi(): String = buildString {
        val rated = ratings().sortedByDescending { it.optInt("stars") }
        if (rated.isNotEmpty()) appendLine("Films they've rated (out of 10): " +
            rated.take(20).joinToString("; ") { "${it.optString("title")} (${it.optString("year")}) ${it.optInt("stars")}" })
        val wl = watchlist()
        if (wl.isNotEmpty()) appendLine("On their watchlist: " + wl.take(20).joinToString("; ") { it.optString("title") })
        val h = history()
        if (h.isNotEmpty()) appendLine("Recently watched in Atlas: " + h.take(10).joinToString("; "))
    }
}

/** Hand-off between screens: which film is open, what's queued to play. */
object CinemaNav {
    var filmId: Int = 0
    var playUrl: String = ""
    var playTitle: String = ""
    var metaType: String = "movie"
    var metaId: String = ""
    var playVideoId: String = ""
    var playMeta: Meta? = null
    var playSubs: List<Sub> = emptyList()
    /** Set for torrent sources: the player opens it with Atlas's own engine. */
    var playMagnet: String? = null
    var playFileIdx: Int? = null
    /** Request headers some addons need with their streams. */
    var playHeaders: Map<String, String> = emptyMap()
    /** A saved adaptive stream: read it from the offline cache. */
    var playCached: Boolean = false
    /** "Next episode" from the player: the title page opens this episode's sources when it comes back. */
    var pendingEpisode: String? = null

    /** One place that sets everything the player reads, so no field leaks from the last video. */
    fun play(url: String, title: String, videoId: String, meta: Meta?, subs: List<Sub> = emptyList(), magnet: String? = null,
             fileIdx: Int? = null, headers: Map<String, String> = emptyMap(), cached: Boolean = false) {
        playUrl = url; playTitle = title; playVideoId = videoId; playMeta = meta; playSubs = subs
        playMagnet = magnet; playFileIdx = fileIdx; playHeaders = headers; playCached = cached
    }
}


/**
 * Earn your leisure: every focus minute earns two minutes of Cinema. Off by default; when on,
 * films play only while you have balance, and watching spends it.
 */
object Leisure {
    private fun p(c: Context) = c.getSharedPreferences("leisure", 0)
    fun on(c: Context) = p(c).getBoolean("on", false)
    fun set(c: Context, v: Boolean) = p(c).edit().putBoolean("on", v).apply()
    fun spent(c: Context) = p(c).getInt("spent", 0)
    fun spend(c: Context, minutes: Int) = p(c).edit().putInt("spent", spent(c) + minutes).apply()
    fun balance(c: Context): Int = com.nizam.app.feature.insight.FocusStore(c).minutesByDay().values.sum() * 2 - spent(c)
}
