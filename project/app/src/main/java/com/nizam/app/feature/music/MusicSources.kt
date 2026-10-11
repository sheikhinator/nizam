package com.nizam.app.feature.music

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.nizam.app.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

// ── Album art ────────────────────────────────────────────────────────────────────────────────────

object MusicArt {
    private val cache = object : android.util.LruCache<String, Bitmap>(24) {}

    /** Embedded art, then the album-art URI, then a saved file or web image. Small enough for lists and notifications. */
    fun bitmap(c: Context, t: Track?, size: Int = 512): Bitmap? {
        t ?: return null
        cache.get("${t.id}|${t.art}|$size")?.let { return it }
        val bmp = runCatching {
            when {
                t.art.startsWith("http") -> null                           // web art is drawn by Coil in the UI
                t.art.isNotBlank() -> c.contentResolver.openInputStream(Uri.parse(t.art))?.use { BitmapFactory.decodeStream(it) }
                else -> null
            }
        }.getOrNull() ?: embedded(c, t)
        val scaled = bmp?.let { if (it.width > size) Bitmap.createScaledBitmap(it, size, it.height * size / it.width, true) else it }
        scaled?.let { cache.put("${t.id}|${t.art}|$size", it) }
        return scaled
    }

    fun embedded(c: Context, t: Track): Bitmap? = runCatching {
        if (t.uri.startsWith("http")) return null
        MediaMetadataRetriever().run {
            try {
                setDataSource(c, Uri.parse(t.uri))
                embeddedPicture?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            } finally { release() }
        }
    }.getOrNull()

    /** The dominant colour of the art, for the now-playing backdrop. */
    fun tint(b: Bitmap?): Int? = b?.let { Bitmap.createScaledBitmap(it, 1, 1, true).getPixel(0, 0) }

    /** Saves an image (picked or downloaded) as this track's art and returns its file:// URI. */
    suspend fun saveArt(c: Context, id: String, bytes: ByteArray): String = withContext(Dispatchers.IO) {
        val f = File(MusicLibrary.artDir(c), id.replace(Regex("[^A-Za-z0-9]"), "_") + ".jpg")
        f.writeBytes(bytes); cache.evictAll()
        Uri.fromFile(f).toString()
    }
}

// ── Metadata and covers: Deezer's public catalogue (no key), MusicBrainz as a second opinion ─────

data class MetaMatch(val title: String, val artist: String, val album: String, val cover: String, val year: String, val deezerLink: String)

object MusicMeta {
    suspend fun search(query: String): List<MetaMatch> {
        val o = JSONObject(Http.getJson("https://api.deezer.com/search?q=${enc(query)}&limit=10"))
        val a = o.optJSONArray("data") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { d ->
            MetaMatch(d.optString("title"), d.optJSONObject("artist")?.optString("name").orEmpty(),
                d.optJSONObject("album")?.optString("title").orEmpty(), d.optJSONObject("album")?.optString("cover_xl").orEmpty(),
                "", d.optString("link"))
        }
    }

    /** Best guess for a track: its tags as the query, falling back to the title alone. */
    suspend fun match(t: Track): MetaMatch? =
        runCatching { search("${t.artist.takeUnless { it.startsWith("Unknown") }.orEmpty()} ${t.title}".trim()).firstOrNull() }.getOrNull()
            ?: runCatching { search(t.title).firstOrNull() }.getOrNull()

    suspend fun year(artist: String, album: String): String = runCatching {
        val q = enc("releasegroup:" + '"' + album + '"' + " AND artist:" + '"' + artist + '"')
        val o = JSONObject(Http.getJson("https://musicbrainz.org/ws/2/release-group?query=$q&fmt=json&limit=1",
            mapOf("User-Agent" to "Atlas/1.0 (personal music player)")))
        o.optJSONArray("release-groups")?.optJSONObject(0)?.optString("first-release-date")?.take(4).orEmpty()
    }.getOrElse { "" }
}

// ── Lyrics: LRCLIB (free, open, time-synced where available) ─────────────────────────────────────

data class LyricLine(val ms: Long, val text: String)

object Lyrics {
    /** Synced LRC if LRCLIB has it, else plain lyrics; null when nothing is found. */
    suspend fun fetch(t: Track): String? = runCatching {
        val exact = runCatching {
            JSONObject(Http.getJson("https://lrclib.net/api/get?artist_name=${enc(t.artist)}&track_name=${enc(t.title)}" +
                (if (t.album.isNotBlank()) "&album_name=${enc(t.album)}" else "") +
                (if (t.durationMs > 0) "&duration=${t.durationMs / 1000}" else "")))
        }.getOrNull()
        val o = exact ?: JSONArray(Http.getJson("https://lrclib.net/api/search?q=${enc("${t.artist} ${t.title}")}")).optJSONObject(0)
        o?.optString("syncedLyrics")?.takeIf { it.isNotBlank() && it != "null" } ?: o?.optString("plainLyrics")?.takeIf { it.isNotBlank() && it != "null" }
    }.getOrNull()

    fun parse(lrc: String): List<LyricLine> {
        val tag = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val out = mutableListOf<LyricLine>()
        lrc.lines().forEach { line ->
            val stamps = tag.findAll(line).toList()
            if (stamps.isEmpty()) return@forEach
            val text = line.substring(stamps.last().range.last + 1).trim()
            stamps.forEach { m ->
                val frac = m.groupValues[3].let { if (it.isBlank()) 0 else it.padEnd(3, '0').take(3).toInt() }
                out += LyricLine(m.groupValues[1].toLong() * 60_000 + m.groupValues[2].toLong() * 1000 + frac, text)
            }
        }
        return out.sortedBy { it.ms }
    }
}

// ── Catalogues and downloads ─────────────────────────────────────────────────────────────────────

object MusicSources {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    data class Progress(val title: String, val percent: Int, val state: String, val error: String = "")
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    fun jamendoKey(c: Context) = c.getSharedPreferences("music", 0).getString("jamendo", "").orEmpty()
    fun setJamendoKey(c: Context, k: String) = c.getSharedPreferences("music", 0).edit().putString("jamendo", k.trim()).apply()

    /** Internet Archive audio: live recordings, public-domain and freely licensed music. */
    suspend fun archive(query: String): List<Track> {
        val url = "https://archive.org/advancedsearch.php?q=${enc("($query) AND mediatype:audio")}&fl[]=identifier&fl[]=title&fl[]=creator" +
            "&rows=20&output=json&sort[]=downloads+desc"
        val docs = JSONObject(Http.getJson(url)).optJSONObject("response")?.optJSONArray("docs") ?: JSONArray()
        return (0 until docs.length()).map { docs.getJSONObject(it) }.map { d ->
            val id = d.optString("identifier")
            Track("ia:$id", d.optString("title"), d.opt("creator")?.let { if (it is JSONArray) it.optString(0) else it.toString() }.orEmpty(),
                "Internet Archive", 0, "", "https://archive.org/services/img/$id", "archive")
        }
    }

    /** The audio files inside an Archive item, each a playable, downloadable track. */
    suspend fun archiveFiles(item: Track): List<Track> {
        val id = item.id.removePrefix("ia:")
        val files = JSONObject(Http.getJson("https://archive.org/metadata/$id")).optJSONArray("files") ?: JSONArray()
        return (0 until files.length()).map { files.getJSONObject(it) }
            .filter { it.optString("name").endsWith(".mp3", true) || it.optString("format").contains("MP3", true) }
            .map { f ->
                val name = f.optString("name")
                Track("ia:$id/$name", f.optString("title").ifBlank { name.substringBeforeLast('.') }, f.optString("artist").ifBlank { item.artist },
                    f.optString("album").ifBlank { item.title }, (f.optString("length").toDoubleOrNull() ?: 0.0).times(1000).toLong(),
                    "https://archive.org/download/$id/${enc(name).replace("+", "%20")}", item.art, "archive")
            }
    }

    /** Jamendo: independent artists' music under Creative Commons, free to stream and download. Needs a free client id. */
    suspend fun jamendo(c: Context, query: String): List<Track> {
        val key = jamendoKey(c).ifBlank { throw IllegalStateException("Add a free Jamendo client id (developer.jamendo.com) first") }
        val o = JSONObject(Http.getJson("https://api.jamendo.com/v3.0/tracks/?client_id=$key&format=json&limit=25&search=${enc(query)}&audioformat=mp32"))
        val a = o.optJSONArray("results") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { r ->
            Track("jam:${r.optString("id")}", r.optString("name"), r.optString("artist_name"), r.optString("album_name"),
                r.optLong("duration") * 1000, r.optString("audiodownload").ifBlank { r.optString("audio") }, r.optString("image"), "jamendo")
        }
    }

    /** Any direct audio link, or an .m3u/.m3u8/.pls playlist of them — whatever source you point at. */
    suspend fun fromLink(url: String): List<Track> {
        val lower = url.lowercase().substringBefore('?')
        if (lower.endsWith(".m3u") || lower.endsWith(".m3u8") || lower.endsWith(".pls")) {
            val body = Http.getText(url)
            var pending = ""
            return body.lines().mapNotNull { line ->
                val l = line.trim()
                when {
                    l.startsWith("#EXTINF") -> { pending = l.substringAfter(','); null }
                    l.startsWith("File", true) && l.contains('=') -> l.substringAfter('=').takeIf { it.startsWith("http") }?.let {
                        Track("u:$it", it.substringAfterLast('/'), "", "", 0, it, "", "link") }
                    l.startsWith("http") -> Track("u:$l", pending.ifBlank { l.substringAfterLast('/').substringBefore('?') }, "", "", 0, l, "", "link")
                        .also { pending = "" }
                    else -> null
                }
            }
        }
        return listOf(Track("u:$url", url.substringAfterLast('/').substringBefore('?').substringBeforeLast('.')
            .replace('_', ' ').replace("%20", " "), "", "", 0, url, "", "link"))
    }

    /** Copies picked files into Atlas's music folder, reading their tags. */
    suspend fun import(c: Context, uris: List<Uri>): Int = withContext(Dispatchers.IO) {
        uris.count { uri ->
            runCatching {
                val name = com.nizam.app.core.displayNameFor(c, uri).replace(Regex("[^A-Za-z0-9._ -]"), "_")
                val out = File(MusicLibrary.dir(c), name)
                c.contentResolver.openInputStream(uri)?.use { i -> out.outputStream().use { i.copyTo(it) } } ?: return@runCatching false
                MusicLibrary.addOwn(c, readTags(c, out, "import"))
                true
            }.getOrElse { false }
        }
    }

    fun readTags(c: Context, f: File, source: String): Track {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(f.path)
            fun m(k: Int) = r.extractMetadata(k).orEmpty()
            Track("f:${f.name}", m(MediaMetadataRetriever.METADATA_KEY_TITLE).ifBlank { f.nameWithoutExtension },
                m(MediaMetadataRetriever.METADATA_KEY_ARTIST).ifBlank { "Unknown artist" }, m(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                m(MediaMetadataRetriever.METADATA_KEY_DURATION).toLongOrNull() ?: 0, Uri.fromFile(f).toString(), "", source,
                m(MediaMetadataRetriever.METADATA_KEY_YEAR), m(MediaMetadataRetriever.METADATA_KEY_GENRE),
                m(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).substringBefore('/').toIntOrNull() ?: 0)
        } catch (e: Exception) {
            Track("f:${f.name}", f.nameWithoutExtension, "Unknown artist", "", 0, Uri.fromFile(f).toString(), "", source)
        } finally { runCatching { r.release() } }
    }

    /** Downloads a track to the phone for offline listening, then reads its tags (keeping ones you gave it). */
    fun download(c: Context, t: Track) {
        if (!t.uri.startsWith("http")) return
        val app = c.applicationContext
        scope.launch {
            val ext = t.uri.substringBefore('?').substringAfterLast('.', "mp3").take(4).lowercase().let { if (it in listOf("mp3", "m4a", "ogg", "opus", "flac", "wav", "aac")) it else "mp3" }
            val base = "${t.artist} - ${t.title}".replace(Regex("[^A-Za-z0-9 ._-]"), "").trim().ifBlank { "track_${System.currentTimeMillis()}" }
            val out = File(MusicLibrary.dir(app), "$base.$ext")
            try {
                _progress.value = Progress(t.title, 0, "downloading")
                com.nizam.app.net.Transfers.progress(app, "music:${t.id}", "Music · ${t.title}", -1)
                val conn = (URL(t.uri).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true; connectTimeout = 20000; readTimeout = 60000; setRequestProperty("User-Agent", "Atlas/1.0")
                }
                if (conn.responseCode !in 200..299) throw IllegalStateException("HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong
                conn.inputStream.use { i -> out.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16); var done = 0L; var shown = -1
                    while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != shown) { shown = pct; _progress.value = Progress(t.title, pct, "downloading")
                            com.nizam.app.net.Transfers.progress(app, "music:${t.id}", "Music · ${t.title}", if (total > 0) pct else -1, "${done / 1_048_576} MB") } }
                } }
                conn.disconnect()
                val read = readTags(app, out, "download")
                MusicLibrary.addOwn(app, read.copy(
                    title = t.title.ifBlank { read.title }, artist = t.artist.ifBlank { read.artist },
                    album = t.album.ifBlank { read.album }, art = t.art.ifBlank { read.art }))
                _progress.value = Progress(t.title, 100, "done")
                com.nizam.app.net.Transfers.done(app, "music:${t.id}", t.title, "Saved to Music — plays offline.")
            } catch (e: Exception) {
                out.delete()
                _progress.value = Progress(t.title, 0, "failed", com.nizam.app.ui.components.Feedback.friendly(e))
                com.nizam.app.net.Transfers.failed(app, "music:${t.id}", t.title, com.nizam.app.ui.components.Feedback.friendly(e))
            }
        }
    }

    /** Search the big streaming services in their own apps (or the web if the app isn't installed). */
    val STREAMING = listOf(
        "Spotify" to "https://open.spotify.com/search/%s",
        "YouTube Music" to "https://music.youtube.com/search?q=%s",
        "Deezer" to "https://www.deezer.com/search/%s",
        "SoundCloud" to "https://soundcloud.com/search?q=%s",
        "Apple Music" to "https://music.apple.com/search?term=%s"
    )
}

// ── Writing tags into files Atlas owns (MP3 / ID3v2.3) ───────────────────────────────────────────

/**
 * Rewrites the ID3v2 tag at the start of an MP3: title, artist, album, year, genre, track and the
 * front cover. Only for files in Atlas's own folder — the phone's other music keeps edits as overrides.
 */
object Id3 {
    private fun frame(id: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        val n = body.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte(), 0, 0))
        out.write(body)
        return out.toByteArray()
    }
    // encoding 1 = UTF-16 with BOM, which every reader handles
    private fun text(id: String, v: String) = frame(id, byteArrayOf(1) + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + v.toByteArray(Charsets.UTF_16LE))

    private fun synchsafe(n: Int) = byteArrayOf(((n shr 21) and 0x7F).toByte(), ((n shr 14) and 0x7F).toByte(), ((n shr 7) and 0x7F).toByte(), (n and 0x7F).toByte())

    fun write(f: File, t: Track, cover: ByteArray?) {
        require(f.extension.equals("mp3", true)) { "Tags can be written into MP3 files only" }
        val bytes = f.readBytes()
        // skip an existing ID3v2 tag
        var start = 0
        if (bytes.size > 10 && bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            val size = ((bytes[6].toInt() and 0x7F) shl 21) or ((bytes[7].toInt() and 0x7F) shl 14) or
                ((bytes[8].toInt() and 0x7F) shl 7) or (bytes[9].toInt() and 0x7F)
            start = 10 + size + (if (bytes[5].toInt() and 0x10 != 0) 10 else 0)
        }
        val frames = ByteArrayOutputStream()
        if (t.title.isNotBlank()) frames.write(text("TIT2", t.title))
        if (t.artist.isNotBlank()) frames.write(text("TPE1", t.artist))
        if (t.album.isNotBlank()) frames.write(text("TALB", t.album))
        if (t.year.isNotBlank()) frames.write(text("TYER", t.year))
        if (t.genre.isNotBlank()) frames.write(text("TCON", t.genre))
        if (t.trackNo > 0) frames.write(text("TRCK", t.trackNo.toString()))
        cover?.let { img ->
            val mime = if (img.size > 3 && img[0] == 0x89.toByte()) "image/png" else "image/jpeg"
            frames.write(frame("APIC", byteArrayOf(0) + mime.toByteArray() + byteArrayOf(0, 3, 0) + img))
        }
        val body = frames.toByteArray()
        val tmp = File(f.path + ".tmp")
        tmp.outputStream().use { o ->
            o.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
            o.write(synchsafe(body.size)); o.write(body)
            o.write(bytes, start.coerceAtMost(bytes.size), bytes.size - start.coerceAtMost(bytes.size))
        }
        f.delete(); tmp.renameTo(f)
    }
}
