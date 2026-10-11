package com.nizam.app.feature.library

import com.nizam.app.net.Http
import org.json.JSONObject
import java.net.URLEncoder

/** Open Library: free, no key, real covers and author data for almost any book. */
data class BookMatch(val title: String, val author: String, val year: String, val cover: String?, val key: String)

object Covers {
    suspend fun search(query: String, limit: Int = 12): List<BookMatch> {
        val url = "https://openlibrary.org/search.json?limit=$limit&fields=title,author_name,first_publish_year,cover_i,key" +
            "&q=" + URLEncoder.encode(query.trim(), "UTF-8").replace("+", "%20")
        val docs = JSONObject(Http.getJson(url)).optJSONArray("docs") ?: return emptyList()
        return (0 until docs.length()).map { docs.getJSONObject(it) }.map {
            BookMatch(it.optString("title"), it.optJSONArray("author_name")?.optString(0).orEmpty(),
                it.optInt("first_publish_year").takeIf { y -> y > 0 }?.toString().orEmpty(),
                it.optInt("cover_i").takeIf { id -> id > 0 }?.let { id -> "https://covers.openlibrary.org/b/id/$id-L.jpg" },
                it.optString("key"))
        }.filter { it.title.isNotBlank() }
    }

    /** Best-guess cover for a title already in the library. */
    suspend fun find(title: String, author: String): String? =
        runCatching { search("$title $author".trim(), 3).firstOrNull { it.cover != null }?.cover }.getOrNull()
}
