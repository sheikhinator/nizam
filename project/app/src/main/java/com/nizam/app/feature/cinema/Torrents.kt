package com.nizam.app.feature.cinema

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.session_handle
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Atlas's own torrent engine, so torrent sources play inside Cinema like any other stream.
 *
 * A magnet's metadata is fetched from the swarm, only the chosen video file is downloaded, pieces
 * are fetched in order, and a tiny HTTP server on 127.0.0.1 serves the file to the player as it
 * arrives. Seeking asks the swarm for the pieces under the playhead first. Streams that aren't saved
 * to your library are deleted when the player closes; saved ones keep downloading.
 */
object Torrents {
    class Live(val key: String, val handle: TorrentHandle, val info: TorrentInfo, val file: Int, val dir: File) {
        val path: File get() = File(dir, info.files().filePath(file))
        val size: Long get() = info.files().fileSize(file)
        val offset: Long get() = info.files().fileOffset(file)
        val pieceLen: Int get() = info.pieceLength()
        private val lastPiece: Int get() = info.files().lastPieceIndexAtFile(file)
        @Volatile var keep = false
        @Volatile var streams = 0
        @Volatile var closed = false
        @Volatile private var ahead = -1

        fun pieceAt(filePos: Long) = ((offset + filePos) / pieceLen).toInt()

        /** Asks for the next dozen pieces from [piece] with tight deadlines — what's under the playhead first. */
        fun readAhead(piece: Int) {
            if (piece == ahead) return
            ahead = piece
            for (i in 0 until 12) {
                val p = piece + i
                if (p > lastPiece) break
                if (!handle.havePiece(p)) handle.setPieceDeadline(p, 60 + i * 140)
            }
        }

        /** Blocks until [piece] is on disk. */
        fun waitFor(piece: Int, gone: () -> Boolean) {
            readAhead(piece)
            while (!handle.havePiece(piece)) {
                if (closed || gone()) throw IOException("stream closed")
                Thread.sleep(80)
            }
        }

        /** 0–100 for the chosen file. */
        fun percent(): Int = runCatching {
            val done = handle.fileProgress()[file]
            if (size <= 0) 0 else (done * 100 / size).toInt()
        }.getOrDefault(0)

        fun complete() = runCatching { handle.fileProgress()[file] >= size }.getOrDefault(false)

        fun status(): String = runCatching {
            val s = handle.status()
            val rate = s.downloadPayloadRate()
            val speed = if (rate >= 1_048_576) "%.1f MB/s".format(rate / 1_048_576f) else "${rate / 1024} KB/s"
            "${s.numPeers()} peers · $speed · ${percent()}%"
        }.getOrDefault("")
    }

    private var sm: SessionManager? = null
    private val live = ConcurrentHashMap<String, Live>()

    /** Public trackers added to every magnet — more peers found, faster starts. */
    private val TRACKERS = listOf(
        "udp://tracker.opentrackr.org:1337/announce", "udp://open.demonii.com:1337/announce",
        "udp://open.stealth.si:80/announce", "udp://tracker.torrent.eu.org:451/announce",
        "udp://exodus.desync.com:6969/announce", "udp://explodie.org:6969/announce",
        "udp://tracker.openbittorrent.com:6969/announce", "udp://tracker.dler.org:6969/announce"
    )
    private val VIDEO = setOf("mp4", "mkv", "webm", "avi", "mov", "m4v", "ts", "wmv", "flv", "mpg", "mpeg")

    fun dir(c: Context) = File(c.getExternalFilesDir(null) ?: c.filesDir, "torrents").apply { mkdirs() }

    @Synchronized private fun session(): SessionManager = sm ?: SessionManager(false).also { s ->
        val sp = SettingsPack().connectionsLimit(200).activeDownloads(6).activeSeeds(2).activeLimit(12)
        s.start(SessionParams(sp))
        sm = s
    }

    fun keyOf(magnet: String) = Regex("btih:([A-Za-z0-9]+)").find(magnet)?.groupValues?.get(1)?.lowercase() ?: magnet

    private fun withTrackers(magnet: String) = magnet + TRACKERS.joinToString("") { "&tr=" + URLEncoder.encode(it, "UTF-8") }

    /**
     * Opens a torrent (or returns it if it's already running) with only the chosen file wanted.
     * [fileIdx] comes from the addon when it knows which file is the episode; otherwise the biggest video is picked.
     */
    suspend fun open(c: Context, magnet: String, fileIdx: Int?, keep: Boolean): Live = withContext(Dispatchers.IO) {
        val key = keyOf(magnet)
        live[key]?.let { l -> if (keep) l.keep = true; return@withContext l }
        val s = session()
        val dir = dir(c)
        val bytes = s.fetchMagnet(withTrackers(magnet), 90, dir)
            ?: throw IOException("No peers answered for this torrent — try another source")
        val ti = TorrentInfo(bytes)
        val fs = ti.files()
        val all = 0 until fs.numFiles()
        val idx = fileIdx?.takeIf { it in all }
            ?: all.filter { fs.fileName(it).substringAfterLast('.').lowercase() in VIDEO }.maxByOrNull { fs.fileSize(it) }
            ?: all.maxBy { fs.fileSize(it) }
        val wanted = Array(fs.numFiles()) { if (it == idx) Priority.DEFAULT else Priority.IGNORE }
        s.download(ti, dir, null, wanted, null, TorrentFlags.SEQUENTIAL_DOWNLOAD)
        var h: TorrentHandle? = null
        repeat(60) { if (h == null) { h = s.find(ti.infoHash()); if (h == null) delay(100) } }
        val handle = h ?: throw IOException("The torrent engine couldn't start this download")
        handle.prioritizeFiles(wanted)
        handle.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        handle.unsetFlags(TorrentFlags.UPLOAD_MODE)
        handle.resume()
        val l = Live(key, handle, ti, idx, dir).also { it.keep = keep }
        // players read the start and the index at the end before they play, so those come first
        val first = fs.pieceIndexAtFile(idx); val last = fs.lastPieceIndexAtFile(idx)
        (first..minOf(first + 4, last)).forEachIndexed { i, p -> handle.setPieceDeadline(p, 100 + i * 100) }
        (maxOf(first, last - 1)..last).forEach { p -> handle.setPieceDeadline(p, 400) }
        live[key] = l
        l
    }

    fun find(key: String) = live[key]

    /** A player stopped using this torrent. Unsaved streams are removed with their data. */
    fun release(l: Live) {
        l.streams = (l.streams - 1).coerceAtLeast(0)
        if (l.streams == 0 && !l.keep) drop(l, deleteFiles = true)
    }

    /** A saved download finished — stop sharing it (unless someone is watching) and keep the file. */
    fun finished(l: Live) { if (l.streams == 0) drop(l, deleteFiles = false) }

    fun drop(l: Live, deleteFiles: Boolean) {
        l.closed = true
        live.remove(l.key)
        runCatching {
            if (deleteFiles) sm?.remove(l.handle, session_handle.delete_files) else sm?.remove(l.handle)
        }
    }
}

/** Serves torrent files to the player over 127.0.0.1 with range requests, waiting for pieces as they arrive. */
object TorrentServer {
    private var server: ServerSocket? = null
    private val routes = ConcurrentHashMap<String, Torrents.Live>()

    fun url(l: Torrents.Live): String {
        val port = ensure()
        routes[l.key] = l
        return "http://127.0.0.1:$port/${l.key}/${URLEncoder.encode(l.path.name, "UTF-8").replace("+", "%20")}"
    }

    @Synchronized private fun ensure(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(isDaemon = true, name = "atlas-torrent-http") {
            while (!s.isClosed) {
                val sock = runCatching { s.accept() }.getOrNull() ?: break
                thread(isDaemon = true, name = "atlas-torrent-conn") { serve(sock) }
            }
        }
        return s.localPort
    }

    private fun mime(name: String) = when (name.substringAfterLast('.').lowercase()) {
        "mp4", "m4v" -> "video/mp4"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
        "avi" -> "video/x-msvideo"; "mov" -> "video/quicktime"; "ts" -> "video/mp2t"
        else -> "application/octet-stream"
    }

    private fun serve(sock: Socket) {
        runCatching {
            sock.use {
                val input = sock.getInputStream().bufferedReader()
                val request = input.readLine() ?: return
                val headers = generateSequence { input.readLine()?.takeIf { it.isNotEmpty() } }.toList()
                val out = BufferedOutputStream(sock.getOutputStream(), 64 * 1024)
                val key = request.split(" ").getOrNull(1)?.trimStart('/')?.substringBefore('/').orEmpty()
                val l = routes[key]
                if (l == null || l.closed) {
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()); out.flush(); return
                }
                val size = l.size
                val range = headers.firstOrNull { it.startsWith("Range:", true) }?.substringAfter('=')?.trim()
                val start = range?.substringBefore('-')?.trim()?.toLongOrNull()?.coerceIn(0, size - 1) ?: 0L
                val end = range?.substringAfter('-')?.trim()?.toLongOrNull()?.coerceAtMost(size - 1) ?: (size - 1)
                val head = buildString {
                    append(if (range != null) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                    append("Content-Type: ${mime(l.path.name)}\r\nAccept-Ranges: bytes\r\nContent-Length: ${end - start + 1}\r\n")
                    if (range != null) append("Content-Range: bytes $start-$end/$size\r\n")
                    append("Connection: close\r\n\r\n")
                }
                out.write(head.toByteArray())
                if (request.startsWith("HEAD")) { out.flush(); return }
                var pos = start
                val buf = ByteArray(64 * 1024)
                var raf: RandomAccessFile? = null
                try {
                    while (pos <= end) {
                        val piece = l.pieceAt(pos)
                        l.waitFor(piece) { sock.isClosed }
                        if (raf == null) raf = RandomAccessFile(l.path, "r")
                        val pieceEnd = (piece + 1L) * l.pieceLen - l.offset          // exclusive, file-relative
                        val want = minOf(buf.size.toLong(), pieceEnd - pos, end - pos + 1).toInt()
                        raf.seek(pos)
                        val n = raf.read(buf, 0, want)
                        if (n <= 0) { Thread.sleep(40); continue }
                        out.write(buf, 0, n); pos += n
                    }
                    out.flush()
                } finally { runCatching { raf?.close() } }
            }
        }
    }
}
