package com.nizam.app.feature.pc

import android.system.Os
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * Pulls any Linux from Docker Hub (or another OCI registry) for 64-bit ARM and unpacks its layers into a folder,
 * the way proot-distro does: Ubuntu, Debian, Kali, Fedora, openSUSE, Alpine, Rocky, Alma… or any image you name.
 */
object Oci {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).followRedirects(true).build()
    private const val ACCEPT = "application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, " +
        "application/vnd.docker.distribution.manifest.v2+json, application/vnd.oci.image.manifest.v1+json"

    data class Ref(val registry: String, val repo: String, val tag: String)

    /** "ubuntu:24.04", "kalilinux/kali-rolling", "ghcr.io/owner/image:tag" → where to fetch it. */
    fun parse(image: String): Ref {
        var s = image.trim()
        var registry = "registry-1.docker.io"
        val first = s.substringBefore('/')
        if (s.contains('/') && (first.contains('.') || first.contains(':'))) { registry = first; s = s.substringAfter('/') }
        val tag = if (s.substringAfterLast('/').contains(':')) s.substringAfterLast(':') else "latest"
        var repo = s.removeSuffix(":$tag")
        if (registry == "registry-1.docker.io" && !repo.contains('/')) repo = "library/$repo"
        return Ref(registry, repo, tag)
    }

    private fun token(r: Ref): String? = runCatching {
        val url = when (r.registry) {
            "registry-1.docker.io" -> "https://auth.docker.io/token?service=registry.docker.io&scope=repository:${r.repo}:pull"
            "ghcr.io" -> "https://ghcr.io/token?scope=repository:${r.repo}:pull"
            else -> return null
        }
        http.newCall(Request.Builder().url(url).build()).execute().use { JSONObject(it.body!!.string()).let { o -> o.optString("token").ifBlank { o.optString("access_token") } } }
    }.getOrNull()

    private fun get(r: Ref, path: String, tok: String?, accept: String = ACCEPT) =
        http.newCall(Request.Builder().url("https://${r.registry}/v2/${r.repo}/$path").header("Accept", accept)
            .apply { if (!tok.isNullOrBlank()) header("Authorization", "Bearer $tok") }.build()).execute()

    data class Layer(val digest: String, val size: Long, val mediaType: String)

    /** The arm64 image's layers, in order. */
    fun layers(image: String): Pair<Ref, List<Layer>> {
        val r = parse(image); val tok = token(r)
        var m = get(r, "manifests/${r.tag}", tok).use { res ->
            if (!res.isSuccessful) throw IllegalStateException(if (res.code == 404 || res.code == 401) "No image called $image" else "Registry said ${res.code}")
            JSONObject(res.body!!.string())
        }
        m.optJSONArray("manifests")?.let { list ->
            val pick = (0 until list.length()).map { list.getJSONObject(it) }.firstOrNull { val p = it.optJSONObject("platform"); p?.optString("architecture") == "arm64" && p.optString("os") == "linux" }
                ?: throw IllegalStateException("$image has no 64-bit ARM version, so it can't run on this phone.")
            m = get(r, "manifests/${pick.optString("digest")}", tok).use { JSONObject(it.body!!.string()) }
        }
        val ls = m.optJSONArray("layers") ?: throw IllegalStateException("Unexpected image format")
        return r to (0 until ls.length()).map { ls.getJSONObject(it) }.map { Layer(it.optString("digest"), it.optLong("size"), it.optString("mediaType")) }
    }

    /** Downloads and unpacks every layer into [dest]. */
    fun pull(image: String, dest: File, progress: (String, Int) -> Unit) {
        val (r, layers) = layers(image)
        val tok = token(r)
        val total = layers.sumOf { it.size }.coerceAtLeast(1)
        var done = 0L
        layers.forEachIndexed { i, l ->
            get(r, "blobs/${l.digest}", tok, "*/*").use { res ->
                if (!res.isSuccessful) throw IllegalStateException("Download failed (${res.code})")
                val counting = object : FilterInputStream(res.body!!.byteStream()) {
                    var shown = -1
                    private fun tick(n: Int) { if (n > 0) { done += n; val p = (done * 100 / total).toInt().coerceAtMost(99); if (p != shown) { shown = p; progress("Downloading and unpacking layer ${i + 1} of ${layers.size} · ${done / 1_048_576} MB", p) } } }
                    override fun read(): Int { val x = super.read(); if (x >= 0) tick(1); return x }
                    override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); tick(n); return n }
                }
                val buffered = BufferedInputStream(counting, 1 shl 16)
                val stream: InputStream = when {
                    l.mediaType.contains("zstd") -> io.airlift.compress.zstd.ZstdInputStream(buffered)
                    l.mediaType.endsWith(".tar") || l.mediaType.endsWith("rootfs.diff.tar") -> buffered
                    else -> GZIPInputStream(buffered)
                }
                Tar.extract(stream, dest, whiteouts = true)
            }
        }
    }
}

/** A tar reader with GNU long names, pax headers and OCI whiteouts. */
object Tar {
    /** Deletes without ever following a symbolic link into somewhere else. */
    fun rm(f: File) {
        val link = runCatching { android.system.OsConstants.S_ISLNK(Os.lstat(f.path).st_mode) }.getOrDefault(false)
        if (link || !f.isDirectory) { runCatching { Os.remove(f.path) }; return }
        f.listFiles()?.forEach { rm(it) }
        runCatching { Os.remove(f.path) }
    }

    fun extract(input: InputStream, dest: File, whiteouts: Boolean = false, stripComponents: Int = 0) {
        dest.mkdirs()
        val hdr = ByteArray(512)
        var longName: String? = null; var longLink: String? = null; var paxPath: String? = null; var paxLink: String? = null
        val canon = dest.canonicalPath
        val hardlinks = mutableListOf<Pair<File, String>>()
        fun full(b: ByteArray): Boolean { var o = 0; while (o < b.size) { val n = input.read(b, o, b.size - o); if (n < 0) return false; o += n }; return true }
        fun str(off: Int, len: Int): String { var e = off; while (e < off + len && hdr[e] != 0.toByte()) e++; return String(hdr, off, e - off, Charsets.UTF_8) }
        fun oct(off: Int, len: Int): Long { val s = str(off, len).trim(); return if (s.isEmpty()) 0 else s.toLong(8) }
        fun skipN(n: Long) { var left = n; val buf = ByteArray(8192); while (left > 0) { val r = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) break; left -= r } }
        fun pad(size: Long) = skipN((512 - size % 512) % 512)
        fun body(size: Long): ByteArray { val b = ByteArray(size.toInt()); full(b); pad(size); return b }
        while (true) {
            if (!full(hdr) || hdr.all { it == 0.toByte() }) break
            val size = if (hdr[124].toInt() and 0x80 != 0) { var v = 0L; for (i in 125 until 136) v = (v shl 8) or (hdr[i].toLong() and 0xff); v } else oct(124, 12)
            val type = hdr[156].toInt().toChar()
            when (type) {
                'L' -> { longName = String(body(size), Charsets.UTF_8).trimEnd('\u0000'); continue }
                'K' -> { longLink = String(body(size), Charsets.UTF_8).trimEnd('\u0000'); continue }
                'x' -> { String(body(size), Charsets.UTF_8).lineSequence().forEach { l -> val kv = l.substringAfter(' ', "")
                    when { kv.startsWith("path=") -> paxPath = kv.removePrefix("path="); kv.startsWith("linkpath=") -> paxLink = kv.removePrefix("linkpath=") } }; continue }
                'g' -> { body(size); continue }
            }
            val prefix = str(345, 155)
            var name = paxPath ?: longName ?: (if (prefix.isNotEmpty()) "$prefix/${str(0, 100)}" else str(0, 100))
            val link = paxLink ?: longLink ?: str(157, 100)
            longName = null; longLink = null; paxPath = null; paxLink = null
            name = name.removePrefix("./").trimEnd('/')
            if (stripComponents > 0) name = name.split('/').drop(stripComponents).joinToString("/")
            if (name.isBlank()) { skipN(size); pad(size); continue }
            val mode = oct(100, 8).toInt()
            val out = File(dest, name)
            if (!out.canonicalPath.startsWith(canon)) { skipN(size); pad(size); continue }
            val base = out.name
            if (whiteouts && base.startsWith(".wh.")) {
                if (base == ".wh..wh..opq") out.parentFile?.listFiles()?.forEach { rm(it) }
                else rm(File(out.parentFile, base.removePrefix(".wh.")))
                skipN(size); pad(size); continue
            }
            when (type) {
                '5' -> { out.mkdirs(); runCatching { Os.chmod(out.path, (mode and "7777".toInt(8)) or "700".toInt(8)) } }
                '2' -> { out.parentFile?.mkdirs(); runCatching { Os.remove(out.path) }; runCatching { Os.symlink(link, out.path) } }
                '1' -> { out.parentFile?.mkdirs(); hardlinks += out to link.removePrefix("./").let { if (stripComponents > 0) it.split('/').drop(stripComponents).joinToString("/") else it } }
                '0', '\u0000', '7' -> {
                    out.parentFile?.mkdirs(); runCatching { Os.remove(out.path) }
                    out.outputStream().use { o -> var left = size; val buf = ByteArray(1 shl 16)
                        while (left > 0) { val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; o.write(buf, 0, n); left -= n } }
                    pad(size)
                    runCatching { Os.chmod(out.path, (mode and "7777".toInt(8)) or "600".toInt(8)) }
                }
                else -> { skipN(size); pad(size) }
            }
        }
        hardlinks.forEach { (f, target) -> runCatching { val t = File(dest, target); runCatching { Os.remove(f.path) }
            runCatching { Os.link(t.path, f.path) }.getOrElse { t.copyTo(f, true); Os.chmod(f.path, Os.stat(t.path).st_mode and "7777".toInt(8)) } } }
    }
}
