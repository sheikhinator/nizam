package com.nizam.app.feature.joytime

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/** Everything Joytime knows about talking to the story archive: polite fetching, a cache on the phone, and reading the folder pages. */
object JoyNet {
    const val DEFAULT_BASE = "https://www.nifty.org/nifty/gay/"
    const val SUPPORT_URL = "https://www.nifty.org/support/"

    data class Entry(val name: String, val url: String, val isDir: Boolean, val note: String)
    data class Fetched(val text: String, val status: Int, val fromCache: Boolean, val stale: Boolean = false, val error: String = "")

    private val gate = Mutex()
    private var lastRequest = 0L

    private fun prefs(c: Context) = c.getSharedPreferences("joytime", 0)
    fun base(c: Context): String = prefs(c).getString("base", DEFAULT_BASE).orEmpty().ifBlank { DEFAULT_BASE }.let { if (it.endsWith("/")) it else "$it/" }
    fun setBase(c: Context, v: String) = prefs(c).edit().putString("base", v.trim()).apply()

    private fun cacheDir(c: Context) = File(c.filesDir, "joytime/cache").apply { mkdirs() }
    private fun key(url: String) = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
    fun isCached(c: Context, url: String) = File(cacheDir(c), key(url)).exists()
    fun cacheBytes(c: Context): Long = cacheDir(c).listFiles()?.sumOf { it.length() } ?: 0L
    fun clearCache(c: Context) { cacheDir(c).listFiles()?.forEach { it.delete() } }

    private fun decode(bytes: ByteArray): String {
        // most files are UTF-8; older ones are Windows-1252, so fall back instead of showing broken characters
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) { String(bytes, charset("windows-1252")) }
    }

    /** Returns the page from the cache while it is fresh, otherwise from the network (one request at a time, with a pause between). Falls back to a stale copy when offline. */
    suspend fun fetch(c: Context, url: String, maxAgeMs: Long): Fetched = withContext(Dispatchers.IO) {
        val f = File(cacheDir(c), key(url))
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs) return@withContext Fetched(decode(f.readBytes()), 200, true)
        gate.withLock {
            val wait = 450 - (System.currentTimeMillis() - lastRequest); if (wait > 0) delay(wait)
            try {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12_000; readTimeout = 30_000; instanceFollowRedirects = true
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) Atlas-Joytime/1.0")
                    setRequestProperty("Accept", "text/html,text/plain,*/*")
                    setRequestProperty("Accept-Language", "en")
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    val bytes = conn.inputStream.use { it.readBytes() }.let { if (it.size > 6_000_000) it.copyOf(6_000_000) else it }
                    f.writeBytes(bytes)
                    lastRequest = System.currentTimeMillis()
                    Fetched(decode(bytes), code, false)
                } else {
                    lastRequest = System.currentTimeMillis()
                    if (f.exists()) Fetched(decode(f.readBytes()), code, true, stale = true, error = "HTTP $code") else Fetched("", code, false, error = "The site answered HTTP $code")
                }
            } catch (e: Exception) {
                lastRequest = System.currentTimeMillis()
                if (f.exists()) Fetched(decode(f.readBytes()), 0, true, stale = true, error = e.message.orEmpty())
                else Fetched("", 0, false, error = e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private val ANCHOR = Regex("<a\\s+[^>]*?href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAGS = Regex("<[^>]+>")
    private val SORT_LABELS = setOf("name", "last modified", "size", "description", "parent directory", "up", "back", "home", "index", "top")

    fun stripTags(s: String): String = TAGS.replace(s, " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'")
        .replace(Regex("\\s+"), " ").trim()

    /** Reads a folder page: every link that goes deeper into the archive becomes an entry, with whatever text follows it as its note. */
    fun parseListing(html: String, pageUrl: String): List<Entry> {
        val out = LinkedHashMap<String, Entry>()
        val pageHost = runCatching { URL(pageUrl).host }.getOrDefault("")
        val segments = html.split(Regex("(?i)<br\\s*/?>|</tr>|</li>|</p>|\\r?\\n"))
        for (seg in segments) {
            val m = ANCHOR.find(seg) ?: continue
            val href = m.groupValues[1].trim()
            if (href.isEmpty() || href.startsWith("?") || href.startsWith("#") || href.startsWith("mailto:", true) || href.startsWith("javascript:", true)) continue
            val abs = runCatching { URL(URL(pageUrl), href).toString().substringBefore('#') }.getOrNull() ?: continue
            val u = runCatching { URL(abs) }.getOrNull() ?: continue
            if (u.host.removePrefix("www.") != pageHost.removePrefix("www.")) continue
            val pagePath = runCatching { URL(pageUrl).path }.getOrDefault("")
            if (!u.path.startsWith(pagePath) || u.path.length <= pagePath.length) continue           // only things below this page
            val label = stripTags(m.groupValues[2])
            if (label.lowercase() in SORT_LABELS) continue
            val rest = stripTags(seg.substring(m.range.last + 1))
                .replace(Regex("\\d{4}-\\d{2}-\\d{2}( \\d{2}:\\d{2})?"), "").replace(Regex("\\s\\d+(\\.\\d+)?[KMG]?\\s*$"), "").trim()
            val isDir = abs.endsWith("/")
            val name = label.ifBlank { abs.trimEnd('/').substringAfterLast('/') }
            out.putIfAbsent(abs, Entry(name, abs, isDir, rest))
        }
        return out.values.toList()
    }

    fun pretty(raw: String): String {
        val s = raw.trim().trimEnd('/').substringAfterLast('/').replace(Regex("\\.(txt|html?)$", RegexOption.IGNORE_CASE), "").replace('-', ' ').replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        return if (s == s.lowercase()) s.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } } else s
    }

    /** Natural order, so "part 2" comes before "part 10". */
    val natural = Comparator<String> { a, b ->
        val ra = Regex("\\d+|\\D+").findAll(a.lowercase()).map { it.value }.toList(); val rb = Regex("\\d+|\\D+").findAll(b.lowercase()).map { it.value }.toList()
        var r = 0
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            r = if (x[0].isDigit() && y[0].isDigit()) (x.toBigInteger().compareTo(y.toBigInteger())) else x.compareTo(y)
            if (r != 0) break
        }
        if (r != 0) r else ra.size - rb.size
    }

    /** A plain report of what the site returned, so the parser can be adjusted if the page layout is not what was expected. */
    suspend fun diagnose(c: Context, url: String): String {
        val r = fetch(c, url, 0L)
        val entries = parseListing(r.text, url)
        return buildString {
            appendLine("Address: $url")
            appendLine("Status: ${if (r.status == 0) "no answer" else r.status}${if (r.error.isNotBlank()) " (${r.error})" else ""}")
            appendLine("Downloaded: ${r.text.length} characters")
            appendLine("Entries found: ${entries.size} (${entries.count { it.isDir }} folders, ${entries.count { !it.isDir }} files)")
            entries.take(12).forEach { appendLine("  ${if (it.isDir) "[dir]" else "[file]"} ${it.name}${if (it.note.isNotBlank()) " — ${it.note.take(50)}" else ""}") }
            if (entries.isEmpty() && r.text.isNotBlank()) appendLine("Start of page: " + stripTags(r.text).take(240))
        }
    }
}

/** Keeps anything involving characters under 18 out of Joytime. This stays on; there is no switch for it. */
object JoySafety {
    private val CATEGORY = Regex("(?i)(youth|teen|child|minor|underage|under-age|preteen|pre-teen|boy-?love|kids?\\b)")
    private val WORDS = Regex("(?i)\\b(under[- ]?age|underage|minors?|children|child|kids?|pre-?teen|tween|jailbait|toddler|schoolboys?|teens?|teenagers?|teenage|(?:1[0-7]|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen)[- ]?(?:years?[- ]?old|yrs?[- ]?old|yo|y/o))\\b")
    private val AGE = Regex("(?i)\\b(?:1[0-7]|ten|eleven|twelve|thirteen|fourteen|fifteen|sixteen|seventeen)[- ]?(?:years?[- ]?old|yrs?[- ]?old|yo|y/o)\\b")

    fun blockedName(name: String, note: String) = CATEGORY.containsMatchIn(name) || WORDS.containsMatchIn("$name $note")
    /** Checks the opening of a chapter for a stated age under 18. */
    fun blockedText(text: String) = AGE.containsMatchIn(text.take(12_000))
}

/** Turns an archive text file into clean paragraphs: drops the mailing-list header and donation footer, and rejoins lines that were hard-wrapped. */
object JoyText {
    data class Parsed(val title: String, val author: String, val paragraphs: List<String>)

    private val FOOTER = Regex("(?i)(nifty\\.org/support|donat\\w* to (the )?nifty|nifty archive (is|needs|relies)|please (consider )?(support|donate)[^.]{0,60}nifty)")
    private val HEADER_KEY = Regex("(?i)^(date|from|to|subject|reply-to|message-id|x-[a-z-]+|organization|lines|path|newsgroups|references|content-[a-z-]+|mime-version|title|author|e-?mail|email|website|web|category|keywords|codes?)\\s*:")

    fun parse(rawIn: String): Parsed {
        var raw = rawIn.replace("\r\n", "\n").replace('\r', '\n').replace("\t", "    ")
        if (Regex("(?i)<(html|body|pre|p|br)\\b").containsMatchIn(raw)) {
            raw = raw.replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("(?i)</p>"), "\n\n")
            raw = JoyNet.stripTags(raw.replace("\n", "\u0001")).replace("\u0001", "\n")
            raw = Regex("&#?\\w+;").replace(raw) { m -> when (m.value) { "&nbsp;" -> " "; "&amp;" -> "&"; "&quot;" -> "\""; "&#39;" -> "'"; "&lt;" -> "<"; "&gt;" -> ">"; else -> m.value } }
        }
        val lines = raw.split("\n").toMutableList()
        var title = ""; var author = ""
        // header block at the very top: lines like "Subject:" and "From:"
        var i = 0
        while (i < lines.size && i < 40 && (lines[i].isBlank() || HEADER_KEY.containsMatchIn(lines[i].trim()))) {
            val l = lines[i].trim()
            if (l.startsWith("subject:", true) || l.startsWith("title:", true)) title = l.substringAfter(':').trim()
            if (l.startsWith("from:", true) || l.startsWith("author:", true)) author = l.substringAfter(':').replace(Regex("<[^>]*>"), "").replace(Regex("\\([^)]*\\)"), "").trim()
            i++
        }
        val body = lines.drop(i).joinToString("\n")
        val blocks = body.split(Regex("\\n\\s*\\n")).map { it.trimEnd() }.filter { it.isNotBlank() }
        val paras = ArrayList<String>()
        for (b in blocks) {
            if (FOOTER.containsMatchIn(b)) continue
            val ls = b.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (ls.isEmpty()) continue
            val wrapped = ls.size > 1 && ls.count { it.length in 50..82 } * 10 >= ls.size * 6
            paras += if (wrapped) ls.joinToString(" ") else ls.joinToString("\n")
        }
        return Parsed(title, author, paras.map { it.replace(Regex(" {2,}"), " ") })
    }
}

/** The library: stories you have opened, with where you stopped. Kept on the phone only. */
object JoyStore {
    private fun p(c: Context) = c.getSharedPreferences("joytime", 0)
    private fun all(c: Context): JSONObject = runCatching { JSONObject(p(c).getString("lib", "{}").orEmpty()) }.getOrDefault(JSONObject())
    private fun save(c: Context, o: JSONObject) = p(c).edit().putString("lib", o.toString()).apply()

    fun get(c: Context, url: String): JSONObject? = all(c).optJSONObject(url)
    fun list(c: Context): List<JSONObject> { val o = all(c); return o.keys().asSequence().mapNotNull { o.optJSONObject(it) }.sortedByDescending { it.optLong("t") }.toList() }

    fun touch(c: Context, url: String, title: String, cat: String, chapters: List<String>) {
        val o = all(c); val e = o.optJSONObject(url) ?: JSONObject().put("url", url).put("fav", false).put("ch", 0).put("para", 0).put("pct", 0).put("done", false).put("dl", false)
        e.put("title", title).put("cat", cat).put("chapters", JSONArray(chapters)).put("t", System.currentTimeMillis())
        o.put(url, e); save(c, o)
    }
    fun update(c: Context, url: String, f: (JSONObject) -> Unit) { val o = all(c); val e = o.optJSONObject(url) ?: return; f(e); e.put("t", System.currentTimeMillis()); o.put(url, e); save(c, o) }
    fun progress(c: Context, url: String, chapter: Int, para: Int, pct: Int) = update(c, url) { it.put("ch", chapter).put("para", para).put("pct", pct) }
    fun setFav(c: Context, url: String, v: Boolean) = update(c, url) { it.put("fav", v) }
    fun remove(c: Context, url: String) { val o = all(c); o.remove(url); save(c, o) }
    fun bookmarks(c: Context, url: String): List<Pair<Int, Int>> = get(c, url)?.optJSONArray("bm")?.let { a -> (0 until a.length()).map { i -> a.optJSONArray(i).let { it.optInt(0) to it.optInt(1) } } }.orEmpty()
    fun toggleBookmark(c: Context, url: String, chapter: Int, para: Int) = update(c, url) { e ->
        val cur = bookmarks(c, url).toMutableList(); val hit = cur.indexOfFirst { it.first == chapter && kotlin.math.abs(it.second - para) < 3 }
        if (hit >= 0) cur.removeAt(hit) else cur += chapter to para
        e.put("bm", JSONArray(cur.map { JSONArray(listOf(it.first, it.second)) }))
    }
}
