package com.nizam.app.feature.music

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One track, wherever it lives: the phone's own music (MediaStore), a file Atlas downloaded or
 * imported, or a stream from a free catalogue. Edits you make (tags, art, lyrics) are kept as
 * overrides keyed by id, so they work even for files Atlas isn't allowed to rewrite.
 */
data class Track(
    val id: String, val title: String, val artist: String, val album: String, val durationMs: Long,
    val uri: String, val art: String = "", val source: String = "device", val year: String = "", val genre: String = "",
    val trackNo: Int = 0
)

data class Playlist(val id: Long, val name: String, val trackIds: List<String>)

object MusicLibrary {
    fun dir(c: Context) = File(c.filesDir, "music").apply { mkdirs() }
    fun artDir(c: Context) = File(c.filesDir, "music_art").apply { mkdirs() }
    private fun meta(c: Context) = File(c.filesDir, "music_meta.json")
    private fun load(c: Context) = runCatching { JSONObject(meta(c).readText()) }.getOrElse { JSONObject() }
    @Synchronized private fun save(c: Context, o: JSONObject) = meta(c).writeText(o.toString())

    // ── device music ────────────────────────────────────────────────────────────────────────────

    suspend fun device(c: Context): List<Track> = withContext(Dispatchers.IO) {
        val out = mutableListOf<Track>()
        val cols = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.YEAR, MediaStore.Audio.Media.TRACK)
        runCatching {
            c.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cols,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} > 30000", null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE")?.use { cur ->
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                    val art = ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), cur.getLong(5))
                    out += Track("ms:$id", cur.getString(1).orEmpty(), cur.getString(2).orEmpty().replace("<unknown>", "Unknown artist"),
                        cur.getString(3).orEmpty(), cur.getLong(4), uri.toString(), art.toString(), "device",
                        cur.getInt(6).takeIf { it > 0 }?.toString().orEmpty(), "", cur.getInt(7) % 1000)
                }
            }
        }
        out
    }

    // ── Atlas's own files (downloaded or imported) ───────────────────────────────────────────────

    fun ownTracks(c: Context): List<Track> {
        val o = load(c).optJSONObject("own") ?: JSONObject()
        return o.keys().asSequence().mapNotNull { k -> o.optJSONObject(k)?.let { fromJson(it) } }
            .filter { it.uri.startsWith("http") || File(Uri.parse(it.uri).path ?: "").exists() }.toList()
    }

    fun addOwn(c: Context, t: Track) {
        val o = load(c); val own = o.optJSONObject("own") ?: JSONObject()
        own.put(t.id, toJson(t)); save(c, o.put("own", own))
    }

    fun deleteOwn(c: Context, t: Track) {
        val o = load(c); o.optJSONObject("own")?.remove(t.id); save(c, o)
        Uri.parse(t.uri).path?.let { p -> if (p.startsWith(dir(c).path)) File(p).delete() }
    }

    // ── everything, with your edits applied ──────────────────────────────────────────────────────

    suspend fun all(c: Context): List<Track> {
        val edits = load(c).optJSONObject("edits") ?: JSONObject()
        return (device(c) + ownTracks(c)).map { t -> edits.optJSONObject(t.id)?.let { applyEdit(t, it) } ?: t }
    }

    private fun applyEdit(t: Track, e: JSONObject) = t.copy(
        title = e.optString("title").ifBlank { t.title }, artist = e.optString("artist").ifBlank { t.artist },
        album = e.optString("album").ifBlank { t.album }, art = e.optString("art").ifBlank { t.art },
        year = e.optString("year").ifBlank { t.year }, genre = e.optString("genre").ifBlank { t.genre },
        trackNo = e.optInt("trackNo", t.trackNo))

    fun edit(c: Context, t: Track) {
        val o = load(c); val edits = o.optJSONObject("edits") ?: JSONObject()
        edits.put(t.id, JSONObject().put("title", t.title).put("artist", t.artist).put("album", t.album).put("art", t.art)
            .put("year", t.year).put("genre", t.genre).put("trackNo", t.trackNo))
        save(c, o.put("edits", edits))
        if (t.source != "device") addOwn(c, t)
    }

    // ── lyrics (your own or fetched), likes, plays, playlists ────────────────────────────────────

    fun lyrics(c: Context, id: String) = load(c).optJSONObject("lyrics")?.optString(id).orEmpty()
    fun setLyrics(c: Context, id: String, text: String) {
        val o = load(c); save(c, o.put("lyrics", (o.optJSONObject("lyrics") ?: JSONObject()).put(id, text)))
    }

    fun liked(c: Context): Set<String> = load(c).optJSONArray("liked")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
    fun toggleLike(c: Context, id: String) {
        val now = liked(c).let { if (id in it) it - id else it + id }
        save(c, load(c).put("liked", JSONArray(now.toList())))
    }

    fun played(c: Context, id: String) {
        val o = load(c)
        val recent = (listOf(id) + (o.optJSONArray("recent")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()))
            .distinct().take(60)
        val counts = o.optJSONObject("plays") ?: JSONObject()
        counts.put(id, counts.optInt(id) + 1)
        save(c, o.put("recent", JSONArray(recent)).put("plays", counts))
    }
    fun recent(c: Context): List<String> = load(c).optJSONArray("recent")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
    fun plays(c: Context): Map<String, Int> = load(c).optJSONObject("plays")?.let { p -> p.keys().asSequence().associateWith { p.optInt(it) } } ?: emptyMap()

    fun playlists(c: Context): List<Playlist> = load(c).optJSONArray("playlists")?.let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { p ->
            Playlist(p.optLong("id"), p.optString("name"), p.optJSONArray("tracks")?.let { t -> (0 until t.length()).map { t.optString(it) } } ?: emptyList())
        }
    } ?: emptyList()

    fun savePlaylists(c: Context, list: List<Playlist>) = save(c, load(c).put("playlists", JSONArray().apply {
        list.forEach { p -> put(JSONObject().put("id", p.id).put("name", p.name).put("tracks", JSONArray(p.trackIds))) }
    }))

    fun addToPlaylist(c: Context, playlistId: Long, trackId: String) = savePlaylists(c, playlists(c).map {
        if (it.id == playlistId && trackId !in it.trackIds) it.copy(trackIds = it.trackIds + trackId) else it })

    private fun toJson(t: Track) = JSONObject().put("id", t.id).put("title", t.title).put("artist", t.artist).put("album", t.album)
        .put("durationMs", t.durationMs).put("uri", t.uri).put("art", t.art).put("source", t.source).put("year", t.year)
        .put("genre", t.genre).put("trackNo", t.trackNo)

    private fun fromJson(o: JSONObject) = Track(o.optString("id"), o.optString("title"), o.optString("artist"), o.optString("album"),
        o.optLong("durationMs"), o.optString("uri"), o.optString("art"), o.optString("source", "download"), o.optString("year"),
        o.optString("genre"), o.optInt("trackNo"))
}
