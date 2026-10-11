package com.nizam.app.feature.iptv

import android.content.Context
import com.nizam.app.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Channel(val name: String, val url: String, val logo: String?, val group: String, val tvgId: String)
data class Playlist(val name: String, val source: String, val channels: Int)

/**
 * M3U / M3U8 playlists. Each channel is an #EXTINF line carrying its name, logo and group, with the
 * stream URL on the line after. Player options (#EXTVLCOPT, #KODIPROP) are ignored.
 */
object M3u {
    private val attr = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(text: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var name = ""; var logo: String? = null; var group = ""; var tvgId = ""
        text.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF", true) -> {
                    val attrs = attr.findAll(line).associate { it.groupValues[1].lowercase() to it.groupValues[2] }
                    logo = attrs["tvg-logo"]?.takeIf { it.isNotBlank() }
                    group = attrs["group-title"].orEmpty().ifBlank { "Ungrouped" }
                    tvgId = attrs["tvg-id"].orEmpty()
                    name = line.substringAfter(",", "").trim()
                }
                line.startsWith("#") || line.isBlank() -> Unit        // other directives and blanks
                else -> {
                    if (name.isNotBlank() || line.startsWith("http")) {
                        out += Channel(name.ifBlank { line.substringAfterLast('/') }, line, logo, group, tvgId)
                    }
                    name = ""; logo = null; group = ""; tvgId = ""
                }
            }
        }
        return out.distinctBy { it.url }
    }

    suspend fun fetch(source: String): List<Channel> = parse(Http.getText(source))
}

class IptvStore(private val c: Context) {
    private val f = File(c.filesDir, "iptv.json")
    private fun load() = runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
    private fun save(o: JSONObject) = f.writeText(o.toString())
    private fun dir() = File(c.filesDir, "playlists").apply { mkdirs() }

    fun playlists(): List<Playlist> = load().optJSONArray("lists")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }
        .map { Playlist(it.optString("name"), it.optString("source"), it.optInt("channels")) } } ?: emptyList()

    /** The parsed channels are cached on disk, so a saved playlist opens instantly and works offline. */
    fun addPlaylist(name: String, source: String, channels: List<Channel>) {
        File(dir(), name.hashCode().toString() + ".json").writeText(JSONArray().apply {
            channels.forEach { put(JSONObject().put("n", it.name).put("u", it.url).put("l", it.logo ?: "").put("g", it.group).put("i", it.tvgId)) }
        }.toString())
        val kept = playlists().filter { it.source != source } + Playlist(name, source, channels.size)
        save(load().put("lists", JSONArray().apply { kept.forEach {
            put(JSONObject().put("name", it.name).put("source", it.source).put("channels", it.channels)) } }))
    }

    fun remove(p: Playlist) {
        File(dir(), p.name.hashCode().toString() + ".json").delete()
        save(load().put("lists", JSONArray().apply { playlists().filter { it.source != p.source }.forEach {
            put(JSONObject().put("name", it.name).put("source", it.source).put("channels", it.channels)) } }))
    }

    fun channels(p: Playlist): List<Channel> = runCatching {
        val a = JSONArray(File(dir(), p.name.hashCode().toString() + ".json").readText())
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Channel(it.optString("n"), it.optString("u"), it.optString("l").ifBlank { null }, it.optString("g"), it.optString("i")) }
    }.getOrElse { emptyList() }

    fun allChannels(): List<Channel> = playlists().flatMap { channels(it) }

    fun favourites(): Set<String> = load().optJSONArray("fav")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
    fun toggleFavourite(url: String) {
        val s = favourites().toMutableSet(); if (!s.add(url)) s.remove(url)
        save(load().put("fav", JSONArray(s.toList())))
    }
    fun recent(): List<String> = load().optJSONArray("recent")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
    fun markWatched(url: String) = save(load().put("recent", JSONArray((listOf(url) + recent().filter { it != url }).take(20))))
}

/** Which channel the player screen should open. */
object IptvNav { var channel: Channel? = null; var list: List<Channel> = emptyList() }
