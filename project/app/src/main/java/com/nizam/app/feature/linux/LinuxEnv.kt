package com.nizam.app.feature.linux

import android.content.Context
import android.system.Os
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/**
 * A real Linux inside Atlas, without root: Alpine or Ubuntu run under proot (shipped as native libraries,
 * so Android lets them run). Everything lives in Atlas's own storage; the vault and app data are never visible.
 * Your projects folder is shared with Atlas Code at /root/projects.
 */
object LinuxEnv {
    enum class Distro(val id: String, val title: String, val blurb: String, val shell: String) {
        ALPINE("alpine", "Alpine", "Tiny and fast · apk add git python3 nodejs", "/bin/ash"),
        UBUNTU("ubuntu", "Ubuntu", "Familiar · apt install git python3 nodejs", "/bin/bash")
    }

    data class Progress(val distro: String, val stage: String, val pct: Int = -1, val error: String = "")
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    fun base(c: Context) = File(c.filesDir, "linux")
    fun root(c: Context, d: Distro) = File(base(c), d.id)
    fun projects(c: Context) = File(c.filesDir, "workspace").apply { mkdirs() }
    private fun tmp(c: Context) = File(base(c), "tmp").apply { mkdirs() }
    private fun lib(c: Context) = c.applicationInfo.nativeLibraryDir
    fun proot(c: Context) = File(lib(c), "libproot.so")

    fun installed(c: Context, d: Distro) = File(root(c, d), ".atlas-ready").exists()
    fun installedAny(c: Context) = Distro.values().filter { installed(c, it) }
    fun preferred(c: Context): Distro? {
        val p = c.getSharedPreferences("linux", 0).getString("default", null)
        return Distro.values().firstOrNull { it.id == p && installed(c, it) } ?: installedAny(c).firstOrNull()
    }
    fun setPreferred(c: Context, d: Distro) = c.getSharedPreferences("linux", 0).edit().putString("default", d.id).apply()
    fun available(c: Context) = proot(c).exists()

    // ── installing ─────────────────────────────────────────────────────────────
    private fun get(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        instanceFollowRedirects = true; connectTimeout = 20_000; readTimeout = 60_000; setRequestProperty("User-Agent", "Atlas/1.0")
    }

    private fun text(url: String) = get(url).inputStream.bufferedReader().use { it.readText() }

    /** The newest release's download, found at install time so it never goes stale. */
    private fun rootfsUrl(d: Distro): String = when (d) {
        Distro.ALPINE -> runCatching {
            val y = text("https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/latest-releases.yaml")
            val f = Regex("file:\\s*(alpine-minirootfs-[0-9.]+-aarch64\\.tar\\.gz)").find(y)!!.groupValues[1]
            "https://dl-cdn.alpinelinux.org/alpine/latest-stable/releases/aarch64/$f"
        }.getOrDefault("https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.3-aarch64.tar.gz")
        Distro.UBUNTU -> runCatching {
            val dir = "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/"
            val f = Regex("ubuntu-base-24\\.04(\\.\\d+)?-base-arm64\\.tar\\.gz").findAll(text(dir)).map { it.value }.distinct()
                .maxByOrNull { it.substringAfter("24.04").substringBefore("-base").trim('.').toIntOrNull() ?: 0 }!!
            dir + f
        }.getOrDefault("https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.3-base-arm64.tar.gz")
    }

    suspend fun install(c: Context, d: Distro): Boolean = withContext(Dispatchers.IO) {
        val dir = root(c, d)
        try {
            if (!available(c)) error("This build of Atlas doesn't include the Linux runtime.")
            _progress.value = Progress(d.id, "Finding the latest ${d.title}")
            val url = rootfsUrl(d)
            dir.deleteRecursively(); dir.mkdirs()
            val conn = get(url)
            if (conn.responseCode !in 200..299) error("Download failed (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong
            val counting = object : java.io.FilterInputStream(conn.inputStream) {
                var done = 0L; var shown = -1
                private fun tick(n: Int) { if (n > 0) { done += n; val p = if (total > 0) (done * 100 / total).toInt() else -1
                    if (p != shown) { shown = p; _progress.value = Progress(d.id, "Downloading and unpacking ${d.title} · ${done / 1_048_576} MB", p) } } }
                override fun read(): Int { val r = super.read(); if (r >= 0) tick(1); return r }
                override fun read(b: ByteArray, off: Int, len: Int): Int { val n = super.read(b, off, len); tick(n); return n }
            }
            untar(GZIPInputStream(BufferedInputStream(counting, 1 shl 16)), dir)
            _progress.value = Progress(d.id, "Setting up ${d.title}")
            configure(c, d)
            File(dir, ".atlas-ready").writeText(url)
            if (preferred(c) == null) setPreferred(c, d)
            _progress.value = Progress(d.id, "Ready", 100)
            // first packages so git works straight away
            true
        } catch (e: Throwable) {
            _progress.value = Progress(d.id, "Couldn't install", -1, e.message ?: e.javaClass.simpleName)
            false
        }
    }

    fun remove(c: Context, d: Distro) { root(c, d).deleteRecursively() }

    /** DNS, hosts, a friendly prompt, and the fixes package managers need under proot. */
    private fun configure(c: Context, d: Distro) {
        val r = root(c, d)
        File(r, "etc").mkdirs()
        File(r, "etc/resolv.conf").apply { delete(); writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n") }
        File(r, "etc/hosts").writeText("127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n")
        File(r, "root").mkdirs(); File(r, "tmp").mkdirs(); File(r, "root/projects").mkdirs()
        runCatching { Os.chmod(File(r, "tmp").path, "1777".toInt(8)) }
        File(r, "etc/profile.d").mkdirs()
        File(r, "etc/profile.d/atlas.sh").writeText(
            "export PS1='\\[\\e[1;36m\\]atlas\\[\\e[0m\\]:\\[\\e[1;34m\\]\\w\\[\\e[0m\\]\\$ '\n" +
            "export LANG=C.UTF-8\nexport TERM=xterm-256color\ncd \"\${ATLAS_CWD:-/root}\" 2>/dev/null\n")
        if (d == Distro.UBUNTU) {
            File(r, "etc/apt/apt.conf.d").mkdirs()
            File(r, "etc/apt/apt.conf.d/01-atlas").writeText("APT::Sandbox::User \"root\";\nAcquire::Retries \"3\";\n")
            // bash reads ~/.bashrc, not profile.d, for interactive shells
            File(r, "root/.bashrc").appendText("\n[ -f /etc/profile.d/atlas.sh ] && . /etc/profile.d/atlas.sh\n")
        } else {
            File(r, "root/.profile").appendText("\n. /etc/profile.d/atlas.sh\n")
        }
    }

    // ── a small tar reader (ustar, GNU long names, pax) ───────────────────────────
    private fun untar(input: InputStream, dest: File) {
        val hdr = ByteArray(512)
        var longName: String? = null; var longLink: String? = null
        var paxPath: String? = null; var paxLink: String? = null
        val canon = dest.canonicalPath
        val hardlinks = mutableListOf<Pair<File, String>>()
        fun readFully(b: ByteArray): Boolean { var o = 0; while (o < b.size) { val n = input.read(b, o, b.size - o); if (n < 0) return o == 0 && false; o += n }; return true }
        fun str(off: Int, len: Int): String { var e = off; while (e < off + len && hdr[e] != 0.toByte()) e++; return String(hdr, off, e - off, Charsets.UTF_8) }
        fun oct(off: Int, len: Int): Long { val s = str(off, len).trim(); return if (s.isEmpty()) 0 else s.toLong(8) }
        fun body(size: Long): ByteArray { val b = ByteArray(size.toInt()); readFully(b); skipPad(input, size); return b }
        while (true) {
            if (!readFully(hdr)) break
            if (hdr.all { it == 0.toByte() }) break
            val size = if (hdr[124].toInt() and 0x80 != 0) { var v = 0L; for (i in 125 until 136) v = (v shl 8) or (hdr[i].toLong() and 0xff); v } else oct(124, 12)
            val type = hdr[156].toInt().toChar()
            when (type) {
                'L' -> { longName = String(body(size), Charsets.UTF_8).trimEnd('\u0000'); continue }
                'K' -> { longLink = String(body(size), Charsets.UTF_8).trimEnd('\u0000'); continue }
                'x' -> { val pax = String(body(size), Charsets.UTF_8)
                    pax.lineSequence().forEach { l -> val kv = l.substringAfter(' ', ""); when { kv.startsWith("path=") -> paxPath = kv.removePrefix("path="); kv.startsWith("linkpath=") -> paxLink = kv.removePrefix("linkpath=") } }
                    continue }
                'g' -> { body(size); continue }
            }
            val prefix = str(345, 155)
            val name = paxPath ?: longName ?: (if (prefix.isNotEmpty()) "$prefix/${str(0, 100)}" else str(0, 100))
            val link = paxLink ?: longLink ?: str(157, 100)
            longName = null; longLink = null; paxPath = null; paxLink = null
            val mode = oct(100, 8).toInt()
            val out = File(dest, name.removePrefix("./").trimEnd('/'))
            if (!out.canonicalPath.startsWith(canon) && out.path != dest.path) { skip(input, size); continue }
            when (type) {
                '5' -> { out.mkdirs(); runCatching { Os.chmod(out.path, (mode and "7777".toInt(8)) or "700".toInt(8)) } }
                '2' -> { out.parentFile?.mkdirs(); runCatching { out.delete(); Os.symlink(link, out.path) } }
                '1' -> { out.parentFile?.mkdirs(); hardlinks += out to link.removePrefix("./") }
                '0', '\u0000', '7' -> {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o -> var left = size; val buf = ByteArray(1 shl 16)
                        while (left > 0) { val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (n < 0) break; o.write(buf, 0, n); left -= n } }
                    skipPad(input, size)
                    runCatching { Os.chmod(out.path, (mode and "7777".toInt(8)) or "600".toInt(8)) }
                }
                else -> skip(input, size)
            }
        }
        hardlinks.forEach { (f, target) -> runCatching { val t = File(dest, target); f.delete(); runCatching { Os.link(t.path, f.path) }.getOrElse { t.copyTo(f, true); Os.chmod(f.path, Os.stat(t.path).st_mode and "7777".toInt(8)) } } }
    }
    private fun skipPad(input: InputStream, size: Long) { val pad = (512 - size % 512) % 512; var left = pad; while (left > 0) { val n = input.skip(left); if (n <= 0) { if (input.read() < 0) break; left-- } else left -= n } }
    private fun skip(input: InputStream, size: Long) { var left = size + (512 - size % 512) % 512; while (left > 0) { val n = input.skip(left); if (n <= 0) { if (input.read() < 0) break; left-- } else left -= n } }

    // ── running ────────────────────────────────────────────────────────────────
    /** The proot command line that enters [d], with the projects folder at /root/projects. */
    fun command(c: Context, d: Distro, inner: List<String>, cwd: String = "/root"): List<String> {
        val r = root(c, d).path
        return listOf(proot(c).path, "--kill-on-exit", "--link2symlink", "-0", "-r", r,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${tmp(c).path}:/dev/shm",
            "-b", "${projects(c).path}:/root/projects",
            "-w", cwd, "/usr/bin/env", "-i", "HOME=/root", "USER=root", "TERM=xterm-256color", "LANG=C.UTF-8", "ATLAS_CWD=$cwd",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin") + inner
    }

    fun env(c: Context): Map<String, String> = mapOf(
        "PROOT_LOADER" to File(lib(c), "libproot-loader.so").path,
        "PROOT_TMP_DIR" to tmp(c).path,
        "LD_LIBRARY_PATH" to lib(c),
        "HOME" to c.filesDir.path, "TMPDIR" to tmp(c).path)

    fun envArray(c: Context) = env(c).map { "${it.key}=${it.value}" }.toTypedArray()

    data class Run(val code: Int, val out: String, val timedOut: Boolean)

    /** Runs one command (for Atlas Code and the AIs) and returns what it printed. */
    suspend fun run(c: Context, cmd: String, cwd: String = "/root/projects", timeoutSec: Long = 120, distro: Distro? = null, onLine: ((String) -> Unit)? = null): Run = withContext(Dispatchers.IO) {
        val d = distro ?: preferred(c) ?: return@withContext Run(127, "Linux isn't installed yet. Open Terminal and pick Alpine or Ubuntu first.", false)
        val pb = ProcessBuilder(command(c, d, listOf(d.shell, "-lc", cmd), cwd)).redirectErrorStream(true)
        pb.environment().clear(); pb.environment().putAll(env(c))
        val p = pb.start()
        val sb = StringBuilder()
        val reader = Thread {
            runCatching { p.inputStream.bufferedReader().forEachLine { l -> synchronized(sb) { if (sb.length < 200_000) sb.appendLine(l) }; onLine?.invoke(l) } }
        }.apply { start() }
        val done = p.waitFor(timeoutSec, TimeUnit.SECONDS)
        if (!done) p.destroyForcibly()
        reader.join(2000)
        val text = synchronized(sb) { sb.toString() }
        Run(if (done) p.exitValue() else -1, if (text.length > 60_000) text.take(20_000) + "\n…\n" + text.takeLast(38_000) else text, !done)
    }

    /** A folder under /root/projects as seen from Android. */
    fun hostPath(c: Context, linuxPath: String): File? =
        if (linuxPath.startsWith("/root/projects")) File(projects(c), linuxPath.removePrefix("/root/projects").trimStart('/')) else null
}
