package com.nizam.app.feature.memory

import android.content.Context
import com.nizam.app.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

private fun Context.f(n: String) = File(filesDir, n)
private fun File.arr() = runCatching { JSONArray(readText()) }.getOrElse { JSONArray() }

data class Article(val id: Long, val url: String, val title: String, val text: String, val saved: String, val read: Boolean)
data class Letter(val id: Long, val written: String, val deliver: String, val text: String)

/** Readable text from a web page: title, and the body with scripts, styles and tags stripped. */
object Readable {
    suspend fun fetch(url: String): Pair<String, String> {
        val html = Http.getText(url)
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)?.groupValues?.get(1)?.trim()?.let(::decode).orEmpty().ifBlank { url }
        val body = html
            .replace(Regex("<(script|style|nav|header|footer|aside|noscript)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " ")
            .replace(Regex("<(br|/p|/div|/h[1-6]|/li)[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<[^>]+>"), " ")
            .let(::decode)
            .lines().map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.length > 40 }   // drop menu crumbs
            .joinToString("\n\n")
        return title to body.take(60_000)
    }
    private fun decode(s: String) = s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&rsquo;", "'").replace("&mdash;", "—")
}

object ReadLater {
    fun all(c: Context) = c.f("readlater.json").arr().let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
        Article(it.optLong("id"), it.optString("url"), it.optString("title"), it.optString("text"), it.optString("saved"), it.optBoolean("read")) } }
    fun save(c: Context, l: List<Article>) = c.f("readlater.json").writeText(JSONArray().apply { l.forEach {
        put(JSONObject().put("id", it.id).put("url", it.url).put("title", it.title).put("text", it.text).put("saved", it.saved).put("read", it.read)) } }.toString())
    suspend fun add(c: Context, url: String): Article {
        val (t, body) = Readable.fetch(url)
        val a = Article(System.currentTimeMillis(), url, t, body, LocalDate.now().toString(), false)
        save(c, listOf(a) + all(c)); return a
    }
}

/** Letters stay sealed — not even shown — until their delivery date. */
object Letters {
    fun all(c: Context) = c.f("letters.json").arr().let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
        Letter(it.optLong("id"), it.optString("written"), it.optString("deliver"), it.optString("text")) } }
    fun save(c: Context, l: List<Letter>) = c.f("letters.json").writeText(JSONArray().apply { l.forEach {
        put(JSONObject().put("id", it.id).put("written", it.written).put("deliver", it.deliver).put("text", it.text)) } }.toString())
    fun dueToday(c: Context) = all(c).filter { it.deliver == LocalDate.now().toString() }
}

object DailyPhoto {
    fun dir(c: Context) = File(c.filesDir, "daily").apply { mkdirs() }
    fun file(c: Context, date: String) = File(dir(c), "$date.jpg")
    fun dates(c: Context) = dir(c).listFiles()?.map { it.nameWithoutExtension }?.sortedDescending() ?: emptyList()
}
