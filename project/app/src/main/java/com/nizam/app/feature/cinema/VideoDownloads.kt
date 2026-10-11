package com.nizam.app.feature.cinema

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.media3.common.MediaItem
import androidx.media3.common.StreamKey
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * A film or episode saved for offline viewing. [kind] says how it was fetched:
 * "file" (a direct link, via Android's download manager), "torrent" (Atlas's torrent engine),
 * or "hls" (an adaptive stream, saved segment by segment into Atlas's offline cache).
 */
data class VideoDownload(
    val id: String, val title: String, val label: String, val metaId: String, val type: String, val poster: String,
    val kind: String, val source: String, val fileIdx: Int = -1, val headers: Map<String, String> = emptyMap(),
    val path: String = "", val dmId: Long = -1, val status: String = "queued", val progress: Int = 0,
    val subs: List<Sub> = emptyList(), val error: String = "", val added: Long = System.currentTimeMillis()
) {
    val done get() = status == "done"
    val active get() = status == "queued" || status == "downloading"
}

object VideoDownloads {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val hls = ConcurrentHashMap<String, HlsDownloader>()
    private val _items = MutableStateFlow<List<VideoDownload>>(emptyList())
    val items: StateFlow<List<VideoDownload>> = _items

    private fun f(c: Context) = File(c.filesDir, "video_downloads.json")
    fun moviesDir(c: Context) = File(c.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: c.filesDir, "atlas").apply { mkdirs() }
    private fun subsDir(c: Context) = File(c.filesDir, "video_subs").apply { mkdirs() }

    // ── offline cache for adaptive (HLS) streams ────────────────────────────────────────────────
    @Volatile private var cache: SimpleCache? = null
    @Synchronized fun cache(c: Context): SimpleCache = cache ?: SimpleCache(File(c.filesDir, "video_offline"), NoOpCacheEvictor(),
        StandaloneDatabaseProvider(c.applicationContext)).also { cache = it }

    fun http(headers: Map<String, String>): DefaultHttpDataSource.Factory = DefaultHttpDataSource.Factory()
        .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(20_000).setReadTimeoutMs(30_000)
        .setUserAgent(headers["User-Agent"] ?: "Mozilla/5.0 (Linux; Android) Atlas")
        .setDefaultRequestProperties(headers - "User-Agent")

    /** Reads from the offline cache, never writes — used to play a saved adaptive stream. */
    fun cachedSource(c: Context, headers: Map<String, String>): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(cache(c)).setUpstreamDataSourceFactory(http(headers)).setCacheWriteDataSinkFactory(null)

    // ── records ─────────────────────────────────────────────────────────────────────────────────
    @Synchronized fun load(c: Context): List<VideoDownload> {
        val list = runCatching { JSONArray(f(c).readText()) }.getOrElse { JSONArray() }
            .let { a -> (0 until a.length()).map { fromJson(a.getJSONObject(it)) } }
        _items.value = list
        return list
    }

    @Synchronized private fun put(c: Context, d: VideoDownload) {
        val list = load(c).filter { it.id != d.id } + d
        f(c).writeText(JSONArray().apply { list.forEach { put(toJson(it)) } }.toString())
        _items.value = list
    }

    @Synchronized private fun update(c: Context, id: String, change: (VideoDownload) -> VideoDownload) {
        load(c).firstOrNull { it.id == id }?.let { put(c, change(it)) }
    }

    fun get(c: Context, id: String) = load(c).firstOrNull { it.id == id }

    /** Whether a stream can be saved, and how. Everything with a link or a torrent can. */
    fun kindOf(s: StreamOption): String? = when {
        s.magnet != null -> "torrent"
        s.url == null -> null
        s.url.substringBefore('?').lowercase().endsWith(".m3u8") -> "hls"
        s.url.substringBefore('?').lowercase().endsWith(".mpd") -> null
        else -> "file"
    }

    /** Starts saving [s] for [videoId]. Subtitles come along so it plays fully offline. */
    fun start(c: Context, s: StreamOption, videoId: String, label: String, meta: Meta?, subs: List<Sub>) {
        val app = c.applicationContext
        val kind = kindOf(s) ?: run {
            com.nizam.app.ui.components.Feedback.show("This source is a live DASH stream — it plays, but can't be saved. Try another source.")
            return
        }
        get(app, videoId)?.takeIf { it.active || it.done }?.let {
            com.nizam.app.ui.components.Feedback.show(if (it.done) "Already saved in your library" else "Already downloading"); return
        }
        val d = VideoDownload(videoId, meta?.name ?: label, label, meta?.id ?: videoId, meta?.type ?: "movie", meta?.poster.orEmpty(),
            kind, s.magnet ?: s.url.orEmpty(), s.fileIdx ?: -1, s.headers)
        put(app, d)
        com.nizam.app.ui.components.Feedback.show("Saving $label to your library")
        scope.launch { saveSubs(app, d.id, subs) }
        begin(app, d)
    }

    private fun begin(c: Context, d: VideoDownload) {
        when (d.kind) {
            "file" -> runCatching {
                val ext = d.source.substringBefore('?').substringAfterLast('/').substringAfterLast('.', "mp4").lowercase()
                    .takeIf { it.length in 2..4 && it.all(Char::isLetterOrDigit) } ?: "mp4"
                val name = "${safe(d.id)}.$ext"
                val req = DownloadManager.Request(Uri.parse(d.source))
                    .setTitle(d.label).setDescription("Atlas · saving for offline")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(c, Environment.DIRECTORY_MOVIES, "atlas/$name")
                    .setAllowedOverMetered(true).setAllowedOverRoaming(true)
                d.headers.forEach { (k, v) -> req.addRequestHeader(k, v) }
                val id = c.getSystemService(DownloadManager::class.java).enqueue(req)
                put(c, d.copy(dmId = id, path = File(moviesDir(c), name).absolutePath, status = "downloading", error = ""))
            }.onFailure { e -> put(c, d.copy(status = "failed", error = com.nizam.app.ui.components.Feedback.friendly(e))) }
            else -> {
                put(c, d.copy(status = "downloading", error = ""))
                jobs[d.id]?.cancel()
                jobs[d.id] = scope.launch {
                    runCatching { if (d.kind == "torrent") runTorrent(c, d) else runHls(c, d) }
                        .onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) {
                            val msg = com.nizam.app.ui.components.Feedback.friendly(e)
                            update(c, d.id) { it.copy(status = "failed", error = msg) }
                            com.nizam.app.net.Transfers.failed(c, "video:${d.id}", d.label, msg)
                        } }
                    jobs.remove(d.id)
                }
                com.nizam.app.net.Transfers.progress(c, "video:${d.id}", d.label, -1, if (d.kind == "torrent") "Finding peers…" else "Starting…")
            }
        }
    }

    private suspend fun runTorrent(c: Context, d: VideoDownload) {
        val l = Torrents.open(c, d.source, d.fileIdx.takeIf { it >= 0 }, keep = true)
        update(c, d.id) { it.copy(path = l.path.absolutePath) }
        while (true) {
            val pct = l.percent()
            update(c, d.id) { it.copy(progress = pct, status = "downloading") }
            com.nizam.app.net.Transfers.progress(c, "video:${d.id}", d.label, pct, l.status())
            if (l.complete()) {
                update(c, d.id) { it.copy(progress = 100, status = "done") }
                com.nizam.app.net.Transfers.done(c, "video:${d.id}", d.label, "Saved in Cinema › Library — plays without internet.")
                Torrents.finished(l)
                return
            }
            delay(2000)
        }
    }

    private suspend fun runHls(c: Context, d: VideoDownload) {
        val item = MediaItem.Builder().setUri(d.source).setStreamKeys(pickVariant(d)).build()
        val dl = HlsDownloader(item, CacheDataSource.Factory().setCache(cache(c)).setUpstreamDataSourceFactory(http(d.headers)))
        hls[d.id] = dl
        var shown = -1
        try {
            runInterruptible {
                dl.download { _, _, pct ->
                    val p = pct.toInt().coerceIn(0, 100)
                    if (p != shown) { shown = p; update(c, d.id) { it.copy(progress = p) }
                        com.nizam.app.net.Transfers.progress(c, "video:${d.id}", d.label, p) }
                }
            }
            update(c, d.id) { it.copy(progress = 100, status = "done") }
            com.nizam.app.net.Transfers.done(c, "video:${d.id}", d.label, "Saved in Cinema › Library — plays without internet.")
        } finally { hls.remove(d.id) }
    }

    /** One picture quality (the best up to 1080p) plus the first audio track — not every rendition. */
    private fun pickVariant(d: VideoDownload): List<StreamKey> = runCatching {
        val conn = (URL(d.source).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 30_000; d.headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        val pl = conn.inputStream.use { HlsPlaylistParser().parse(Uri.parse(d.source), it) }
        if (pl !is HlsMultivariantPlaylist || pl.variants.isEmpty()) return@runCatching emptyList()
        val ranked = pl.variants.withIndex().sortedByDescending { it.value.format.bitrate }
        val best = ranked.firstOrNull { it.value.format.height in 1..1080 } ?: ranked.last()
        listOfNotNull(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, best.index),
            if (pl.audios.isNotEmpty()) StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_AUDIO, 0) else null)
    }.getOrElse { emptyList() }

    /** Keeps records honest: finished system downloads are marked done, and interrupted torrents/HLS resume. */
    fun sync(c: Context) {
        val dm = c.getSystemService(DownloadManager::class.java)
        load(c).forEach { d ->
            if (d.kind == "file" && d.active && d.dmId >= 0) {
                runCatching {
                    dm.query(DownloadManager.Query().setFilterById(d.dmId))?.use { cur ->
                        if (!cur.moveToFirst()) { update(c, d.id) { it.copy(status = "failed", error = "The download was cancelled") }; return@use }
                        val st = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                        val got = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                        val total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                        val pct = if (total > 0) (got * 100 / total).toInt() else 0
                        when (st) {
                            DownloadManager.STATUS_SUCCESSFUL -> update(c, d.id) { it.copy(status = "done", progress = 100) }
                            DownloadManager.STATUS_FAILED -> update(c, d.id) { it.copy(status = "failed",
                                error = "The server stopped the download (code ${cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))})") }
                            else -> if (pct != d.progress) update(c, d.id) { it.copy(progress = pct) }
                        }
                    }
                }
            } else if (d.kind != "file" && d.active && jobs[d.id] == null) begin(c, d)
        }
    }

    fun retry(c: Context, d: VideoDownload) {
        if (d.kind == "file") runCatching { c.getSystemService(DownloadManager::class.java).remove(d.dmId) }
        begin(c.applicationContext, d.copy(status = "queued", progress = 0))
    }

    fun delete(c: Context, d: VideoDownload) {
        val app = c.applicationContext
        jobs.remove(d.id)?.cancel()
        hls.remove(d.id)?.cancel()
        com.nizam.app.net.Transfers.cancelled("video:${d.id}")
        when (d.kind) {
            "file" -> { runCatching { app.getSystemService(DownloadManager::class.java).remove(d.dmId) }; File(d.path).delete() }
            "torrent" -> {
                Torrents.find(Torrents.keyOf(d.source))?.let { Torrents.drop(it, deleteFiles = true) }
                if (d.path.isNotBlank()) File(d.path).let { f ->
                    f.delete()
                    // a torrent's own folder goes too, when it only held this film
                    f.parentFile?.takeIf { it != Torrents.dir(app) && it.path.startsWith(Torrents.dir(app).path) }?.let { p ->
                        if (p.walkBottomUp().none { it.isFile }) p.deleteRecursively()
                    }
                }
            }
            "hls" -> scope.launch { runCatching {
                HlsDownloader(MediaItem.fromUri(d.source), CacheDataSource.Factory().setCache(cache(app))
                    .setUpstreamDataSourceFactory(http(d.headers))).remove()
            } }
        }
        d.subs.forEach { s -> Uri.parse(s.url).path?.let { File(it).delete() } }
        synchronized(this) {
            val list = load(app).filter { it.id != d.id }
            f(app).writeText(JSONArray().apply { list.forEach { put(toJson(it)) } }.toString())
            _items.value = list
        }
    }

    /** What the player should open for a saved item — a local file, or the source read from the offline cache. */
    fun playable(d: VideoDownload): String = when {
        d.kind == "hls" -> d.source
        d.path.isNotBlank() -> Uri.fromFile(File(d.path)).toString()
        else -> d.source
    }

    private suspend fun saveSubs(c: Context, id: String, subs: List<Sub>) {
        val pick = (subs.filter { it.lang == "eng" }.take(1) + subs.filter { it.lang != "eng" }.take(3)).distinctBy { it.lang }
        val saved = pick.mapNotNull { s ->
            runCatching {
                val ext = if (s.url.substringBefore('?').endsWith(".vtt")) "vtt" else "srt"
                val out = File(subsDir(c), "${safe(id)}_${s.lang}.$ext")
                (URL(s.url).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 20_000 }
                    .inputStream.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
                Sub(s.lang, Uri.fromFile(out).toString())
            }.getOrNull()
        }
        if (saved.isNotEmpty()) update(c, id) { it.copy(subs = saved) }
    }

    fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

    private fun toJson(d: VideoDownload) = JSONObject().put("id", d.id).put("title", d.title).put("label", d.label)
        .put("metaId", d.metaId).put("type", d.type).put("poster", d.poster).put("kind", d.kind).put("source", d.source)
        .put("fileIdx", d.fileIdx).put("headers", JSONObject(d.headers)).put("path", d.path).put("dmId", d.dmId)
        .put("status", d.status).put("progress", d.progress).put("error", d.error).put("added", d.added)
        .put("subs", JSONArray().apply { d.subs.forEach { put(JSONObject().put("lang", it.lang).put("url", it.url)) } })

    private fun fromJson(o: JSONObject) = VideoDownload(
        o.optString("id"), o.optString("title"), o.optString("label"), o.optString("metaId"), o.optString("type", "movie"),
        o.optString("poster"), o.optString("kind", "file"), o.optString("source"), o.optInt("fileIdx", -1),
        o.optJSONObject("headers")?.let { h -> h.keys().asSequence().associateWith { h.optString(it) } } ?: emptyMap(),
        o.optString("path"), o.optLong("dmId", -1), o.optString("status", "queued"), o.optInt("progress"),
        o.optJSONArray("subs")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Sub(it.optString("lang"), it.optString("url")) } } ?: emptyList(),
        o.optString("error"), o.optLong("added"))
}
