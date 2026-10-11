package com.nizam.app.feature.deen.library

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.JsonReader
import android.util.JsonToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * The offline Islamic library: Quran editions (Arabic scripts and translations), the hadith books and
 * the tafsirs, downloaded once from open datasets and kept on the phone in one SQLite database.
 *
 * Sources (open, community-maintained): fawazahmed0/quran-api, fawazahmed0/hadith-api and
 * spa5k/tafsir_api on GitHub. Each is fetched from GitHub first and jsDelivr second.
 */
object DeenLibrary {

    enum class Kind(val label: String) { QURAN("Quran"), HADITH("Hadith"), TAFSIR("Tafsir") }

    data class Pack(
        val kind: Kind, val slug: String, val name: String, val author: String, val lang: String,
        val rtl: Boolean = false,
    ) {
        val id get() = "${kind.name.lowercase()}:$slug"
    }

    /** One-tap starter set: the Quran in Arabic, Urdu and English, the two Sahihs, 40 hadith and three tafsirs. */
    val ESSENTIALS = listOf(
        Pack(Kind.QURAN, "ara-quranuthmanihaf", "Quran — Uthmani (Hafs)", "King Fahd Complex script", "Arabic", true),
        Pack(Kind.QURAN, "urd-fatehmuhammadja", "Urdu — Fateh Muhammad Jalandhry", "Fateh Muhammad Jalandhry", "Urdu", true),
        Pack(Kind.QURAN, "eng-ummmuhammad", "English — Saheeh International", "Umm Muhammad", "English"),
        Pack(Kind.QURAN, "eng-muftitaqiusmani", "English — Mufti Taqi Usmani", "Mufti Taqi Usmani", "English"),
        Pack(Kind.HADITH, "eng-bukhari", "Sahih al-Bukhari", "Imam al-Bukhari", "English"),
        Pack(Kind.HADITH, "urd-bukhari", "Sahih al-Bukhari", "Imam al-Bukhari", "Urdu", true),
        Pack(Kind.HADITH, "eng-muslim", "Sahih Muslim", "Imam Muslim", "English"),
        Pack(Kind.HADITH, "urd-muslim", "Sahih Muslim", "Imam Muslim", "Urdu", true),
        Pack(Kind.HADITH, "eng-nawawi", "Forty Hadith of an-Nawawi", "Imam an-Nawawi", "English"),
        Pack(Kind.HADITH, "eng-qudsi", "Forty Hadith Qudsi", "", "English"),
        Pack(Kind.TAFSIR, "ur-tafseer-ibn-e-kaseer", "Tafsir Ibn Kathir", "Ibn Kathir", "Urdu", true),
        Pack(Kind.TAFSIR, "en-tafisr-ibn-kathir", "Tafsir Ibn Kathir (abridged)", "Ibn Kathir", "English"),
        Pack(Kind.TAFSIR, "en-tafsir-maarif-ul-quran", "Maarif-ul-Quran", "Mufti Muhammad Shafi", "English"),
    )

    private const val GH = "https://raw.githubusercontent.com"
    private const val JD = "https://cdn.jsdelivr.net/gh"

    private fun urls(kind: Kind, slug: String, surah: Int = 0): List<String> = when (kind) {
        Kind.QURAN -> listOf("$GH/fawazahmed0/quran-api/1/editions/$slug.min.json", "$JD/fawazahmed0/quran-api@1/editions/$slug.min.json")
        Kind.HADITH -> listOf("$GH/fawazahmed0/hadith-api/1/editions/$slug.min.json", "$JD/fawazahmed0/hadith-api@1/editions/$slug.min.json")
        Kind.TAFSIR -> listOf("$GH/spa5k/tafsir_api/main/tafsir/$slug/$surah.json", "$JD/spa5k/tafsir_api@main/tafsir/$slug/$surah.json")
    }

    // ---------------------------------------------------------------- storage

    private class Db(c: Context) : SQLiteOpenHelper(c, "deen_library.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE packs(id TEXT PRIMARY KEY, kind TEXT, slug TEXT, name TEXT, author TEXT, lang TEXT, rtl INTEGER, done INTEGER, part INTEGER, added INTEGER)")
            db.execSQL("CREATE TABLE ayah(ed TEXT, s INTEGER, a INTEGER, text TEXT, PRIMARY KEY(ed, s, a))")
            db.execSQL("CREATE TABLE tafsir(ed TEXT, s INTEGER, a INTEGER, text TEXT, PRIMARY KEY(ed, s, a))")
            db.execSQL("CREATE TABLE hadith(ed TEXT, n TEXT, ord INTEGER, book INTEGER, text TEXT, grade TEXT, PRIMARY KEY(ed, ord))")
            db.execSQL("CREATE TABLE hbook(ed TEXT, book INTEGER, name TEXT, first TEXT, last TEXT, PRIMARY KEY(ed, book))")
            // unicode61 folds Arabic and Urdu diacritics and case; the plain tokenizer is the fallback
            runCatching { db.execSQL("CREATE VIRTUAL TABLE fts USING fts4(ed, ref, text, tokenize=unicode61)") }
                .onFailure { db.execSQL("CREATE VIRTUAL TABLE fts USING fts4(ed, ref, text)") }
        }
        override fun onUpgrade(db: SQLiteDatabase, o: Int, n: Int) {}
    }

    @Volatile private var helper: Db? = null
    private fun db(c: Context): SQLiteDatabase =
        (helper ?: synchronized(this) { helper ?: Db(c.applicationContext).also { helper = it } }).writableDatabase

    // ---------------------------------------------------------------- catalog

    /** Everything the open datasets offer, cached after the first fetch so the list works offline. */
    suspend fun catalog(c: Context, refresh: Boolean = false): List<Pack> = withContext(Dispatchers.IO) {
        val cache = File(c.filesDir, "deen/catalog.json").apply { parentFile?.mkdirs() }
        if (!refresh && cache.exists()) runCatching { return@withContext parseCatalog(JSONArray(cache.readText())) }
        val out = JSONArray()
        runCatching {
            val q = JSONObject(fetchText(listOf("$GH/fawazahmed0/quran-api/1/editions.json", "$JD/fawazahmed0/quran-api@1/editions.json")))
            q.keys().forEach { k ->
                val e = q.getJSONObject(k)
                out.put(JSONObject().put("k", "QURAN").put("slug", k.replace('_', '-')).put("name", e.optString("author"))
                    .put("author", e.optString("author")).put("lang", e.optString("language")).put("rtl", e.optString("direction") == "rtl"))
            }
        }
        runCatching {
            val h = JSONObject(fetchText(listOf("$GH/fawazahmed0/hadith-api/1/editions.json", "$JD/fawazahmed0/hadith-api@1/editions.json")))
            h.keys().forEach { k ->
                val book = h.getJSONObject(k)
                val coll = book.getJSONArray("collection")
                for (i in 0 until coll.length()) {
                    val e = coll.getJSONObject(i)
                    out.put(JSONObject().put("k", "HADITH").put("slug", e.optString("name")).put("name", book.optString("name"))
                        .put("author", "").put("lang", e.optString("language")).put("rtl", e.optString("direction") == "rtl"))
                }
            }
        }
        runCatching {
            val t = JSONArray(fetchText(listOf("$GH/spa5k/tafsir_api/main/tafsir/editions.json", "$JD/spa5k/tafsir_api@main/tafsir/editions.json")))
            for (i in 0 until t.length()) {
                val e = t.getJSONObject(i)
                val lang = e.optString("language_name").replaceFirstChar { it.uppercase() }
                out.put(JSONObject().put("k", "TAFSIR").put("slug", e.optString("slug")).put("name", e.optString("name"))
                    .put("author", e.optString("author_name")).put("lang", lang).put("rtl", lang in setOf("Urdu", "Arabic", "Persian")))
            }
        }
        if (out.length() == 0) {
            if (cache.exists()) return@withContext parseCatalog(JSONArray(cache.readText()))
            throw IllegalStateException("Couldn't reach the library. Check your internet and try again.")
        }
        cache.writeText(out.toString())
        parseCatalog(out)
    }

    private fun parseCatalog(a: JSONArray): List<Pack> = (0 until a.length()).map { a.getJSONObject(it) }.map {
        Pack(Kind.valueOf(it.getString("k")), it.getString("slug"), it.optString("name"), it.optString("author"), it.optString("lang"), it.optBoolean("rtl"))
    }

    fun installed(c: Context): List<Pack> = db(c).rawQuery("SELECT kind, slug, name, author, lang, rtl FROM packs WHERE done = 1 ORDER BY added", null).use { cur ->
        buildList { while (cur.moveToNext()) add(Pack(Kind.valueOf(cur.getString(0)), cur.getString(1), cur.getString(2), cur.getString(3), cur.getString(4), cur.getInt(5) == 1)) }
    }

    fun has(c: Context, kind: Kind) = installed(c).any { it.kind == kind }

    // ---------------------------------------------------------------- downloads

    data class Progress(val packId: String, val name: String, val fraction: Float, val error: String? = null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val _progress = MutableStateFlow<Map<String, Progress>>(emptyMap())
    val progress: StateFlow<Map<String, Progress>> = _progress.asStateFlow()

    private fun report(p: Progress) { _progress.value = _progress.value + (p.packId to p) }
    private fun clear(id: String) { _progress.value = _progress.value - id }

    fun download(c: Context, packs: List<Pack>) {
        val app = c.applicationContext
        packs.forEach { p ->
            if (jobs[p.id]?.isActive == true) return@forEach
            jobs[p.id] = scope.launch {
                report(Progress(p.id, p.name, 0f))
                runCatching { install(app, p) }
                    .onSuccess { clear(p.id) }
                    .onFailure { e -> report(Progress(p.id, p.name, 0f, e.message ?: "Download failed")) }
            }
        }
    }

    fun cancel(p: Pack) { jobs.remove(p.id)?.cancel(); clear(p.id) }

    fun remove(c: Context, p: Pack) {
        cancel(p)
        val d = db(c)
        d.beginTransaction()
        try {
            when (p.kind) {
                Kind.QURAN -> d.delete("ayah", "ed = ?", arrayOf(p.slug))
                Kind.TAFSIR -> d.delete("tafsir", "ed = ?", arrayOf(p.slug))
                Kind.HADITH -> { d.delete("hadith", "ed = ?", arrayOf(p.slug)); d.delete("hbook", "ed = ?", arrayOf(p.slug)) }
            }
            d.delete("fts", "ed = ?", arrayOf(p.slug))
            d.delete("packs", "id = ?", arrayOf(p.id))
            d.setTransactionSuccessful()
        } finally { d.endTransaction() }
    }

    private suspend fun install(c: Context, p: Pack) {
        val d = db(c)
        d.insertWithOnConflict("packs", null, ContentValues().apply {
            put("id", p.id); put("kind", p.kind.name); put("slug", p.slug); put("name", p.name); put("author", p.author)
            put("lang", p.lang); put("rtl", if (p.rtl) 1 else 0); put("done", 0); put("part", 0); put("added", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
        when (p.kind) {
            Kind.QURAN -> fetchStream(urls(p.kind, p.slug)) { installQuran(d, p.slug, it) }
            Kind.HADITH -> fetchStream(urls(p.kind, p.slug)) { installHadith(d, p.slug, it) }
            Kind.TAFSIR -> {
                // 114 files, one per surah; resumes from the last finished surah
                val from = d.rawQuery("SELECT part FROM packs WHERE id = ?", arrayOf(p.id)).use { if (it.moveToFirst()) it.getInt(0) else 0 }
                for (s in (from + 1)..114) {
                    currentCoroutineContext().ensureActive()
                    val text = fetchText(urls(p.kind, p.slug, s))
                    installTafsirSurah(d, p.slug, s, JSONArray(text))
                    d.execSQL("UPDATE packs SET part = ? WHERE id = ?", arrayOf<Any>(s, p.id))
                    report(Progress(p.id, p.name, s / 114f))
                }
            }
        }
        d.execSQL("UPDATE packs SET done = 1 WHERE id = ?", arrayOf(p.id))
    }

    private fun installQuran(d: SQLiteDatabase, ed: String, input: InputStream) {
        d.beginTransaction()
        try {
            d.delete("ayah", "ed = ?", arrayOf(ed)); d.delete("fts", "ed = ?", arrayOf(ed))
            val ins = d.compileStatement("INSERT OR REPLACE INTO ayah(ed, s, a, text) VALUES (?, ?, ?, ?)")
            val fts = d.compileStatement("INSERT INTO fts(ed, ref, text) VALUES (?, ?, ?)")
            JsonReader(input.reader()).use { r ->
                r.beginObject()
                while (r.hasNext()) {
                    if (r.nextName() != "quran") { r.skipValue(); continue }
                    r.beginArray()
                    while (r.hasNext()) {
                        var s = 0; var a = 0; var t = ""
                        r.beginObject()
                        while (r.hasNext()) when (r.nextName()) {
                            "chapter" -> s = r.nextInt(); "verse" -> a = r.nextInt(); "text" -> t = r.nextString(); else -> r.skipValue()
                        }
                        r.endObject()
                        ins.bindString(1, ed); ins.bindLong(2, s.toLong()); ins.bindLong(3, a.toLong()); ins.bindString(4, t); ins.executeInsert()
                        fts.bindString(1, ed); fts.bindString(2, "q:$s:$a"); fts.bindString(3, t); fts.executeInsert()
                    }
                    r.endArray()
                }
                r.endObject()
            }
            d.setTransactionSuccessful()
        } finally { d.endTransaction() }
    }

    private fun installHadith(d: SQLiteDatabase, ed: String, input: InputStream) {
        d.beginTransaction()
        try {
            d.delete("hadith", "ed = ?", arrayOf(ed)); d.delete("hbook", "ed = ?", arrayOf(ed)); d.delete("fts", "ed = ?", arrayOf(ed))
            val ins = d.compileStatement("INSERT OR REPLACE INTO hadith(ed, n, ord, book, text, grade) VALUES (?, ?, ?, ?, ?, ?)")
            val fts = d.compileStatement("INSERT INTO fts(ed, ref, text) VALUES (?, ?, ?)")
            val sections = mutableMapOf<Int, String>()
            val ranges = mutableMapOf<Int, Pair<String, String>>()
            var ord = 0
            JsonReader(input.reader()).use { r ->
                r.beginObject()
                while (r.hasNext()) when (r.nextName()) {
                    "metadata" -> {
                        r.beginObject()
                        while (r.hasNext()) when (r.nextName()) {
                            "sections" -> { r.beginObject(); while (r.hasNext()) { val k = r.nextName().toIntOrNull() ?: -1; sections[k] = stringOrSkip(r) }; r.endObject() }
                            "section_details" -> {
                                r.beginObject()
                                while (r.hasNext()) {
                                    val k = r.nextName().toIntOrNull() ?: -1
                                    var f = ""; var l = ""
                                    r.beginObject()
                                    while (r.hasNext()) when (r.nextName()) {
                                        "hadithnumber_first" -> f = stringOrSkip(r); "hadithnumber_last" -> l = stringOrSkip(r); else -> r.skipValue()
                                    }
                                    r.endObject(); ranges[k] = f to l
                                }
                                r.endObject()
                            }
                            else -> r.skipValue()
                        }
                        r.endObject()
                    }
                    "hadiths" -> {
                        r.beginArray()
                        while (r.hasNext()) {
                            var n = ""; var t = ""; var book = 0; val grades = mutableListOf<String>()
                            r.beginObject()
                            while (r.hasNext()) when (r.nextName()) {
                                "hadithnumber" -> n = stringOrSkip(r)
                                "text" -> t = stringOrSkip(r)
                                "reference" -> { r.beginObject(); while (r.hasNext()) if (r.nextName() == "book") book = stringOrSkip(r).toDoubleOrNull()?.toInt() ?: 0 else r.skipValue(); r.endObject() }
                                "grades" -> {
                                    r.beginArray()
                                    while (r.hasNext()) {
                                        var who = ""; var g = ""
                                        r.beginObject(); while (r.hasNext()) when (r.nextName()) { "name" -> who = stringOrSkip(r); "grade" -> g = stringOrSkip(r); else -> r.skipValue() }; r.endObject()
                                        if (g.isNotBlank()) grades += if (who.isBlank()) g else "$g ($who)"
                                    }
                                    r.endArray()
                                }
                                else -> r.skipValue()
                            }
                            r.endObject()
                            if (t.isBlank()) continue
                            ord++
                            ins.bindString(1, ed); ins.bindString(2, n.removeSuffix(".0")); ins.bindLong(3, ord.toLong()); ins.bindLong(4, book.toLong())
                            ins.bindString(5, t); ins.bindString(6, grades.joinToString(" · ")); ins.executeInsert()
                            fts.bindString(1, ed); fts.bindString(2, "h:$ord"); fts.bindString(3, t); fts.executeInsert()
                        }
                        r.endArray()
                    }
                    else -> r.skipValue()
                }
                r.endObject()
            }
            sections.filterKeys { it > 0 }.forEach { (k, name) ->
                val (f, l) = ranges[k] ?: ("" to "")
                d.insertWithOnConflict("hbook", null, ContentValues().apply {
                    put("ed", ed); put("book", k); put("name", name.ifBlank { "Book $k" }); put("first", f.removeSuffix(".0")); put("last", l.removeSuffix(".0"))
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            d.setTransactionSuccessful()
        } finally { d.endTransaction() }
    }

    private fun stringOrSkip(r: JsonReader): String = when (r.peek()) {
        JsonToken.STRING, JsonToken.NUMBER -> r.nextString()
        JsonToken.BOOLEAN -> r.nextBoolean().toString()
        JsonToken.NULL -> { r.nextNull(); "" }
        else -> { r.skipValue(); "" }
    }

    private fun installTafsirSurah(d: SQLiteDatabase, ed: String, s: Int, a: JSONArray) {
        d.beginTransaction()
        try {
            val ins = d.compileStatement("INSERT OR REPLACE INTO tafsir(ed, s, a, text) VALUES (?, ?, ?, ?)")
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                val ay = o.optString("ayah").toIntOrNull() ?: continue
                val t = o.optString("text").trim()
                if (t.isEmpty()) continue
                ins.bindString(1, ed); ins.bindLong(2, s.toLong()); ins.bindLong(3, ay.toLong()); ins.bindString(4, t); ins.executeInsert()
            }
            d.setTransactionSuccessful()
        } finally { d.endTransaction() }
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000; readTimeout = 60000; setRequestProperty("User-Agent", "Atlas/1.0")
    }

    private fun fetchText(urls: List<String>): String {
        var last: Exception? = null
        for (u in urls) {
            try {
                val conn = open(u)
                try {
                    if (conn.responseCode in 200..299) return conn.inputStream.bufferedReader().use { it.readText() }
                    last = IllegalStateException("HTTP ${conn.responseCode}")
                } finally { conn.disconnect() }
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("Download failed")
    }

    private fun fetchStream(urls: List<String>, use: (InputStream) -> Unit) {
        var last: Exception? = null
        for (u in urls) {
            try {
                val conn = open(u)
                try {
                    if (conn.responseCode !in 200..299) { last = IllegalStateException("HTTP ${conn.responseCode}"); continue }
                    conn.inputStream.buffered().use(use)
                    return
                } finally { conn.disconnect() }
            } catch (e: Exception) { last = e }
        }
        throw last ?: IllegalStateException("Download failed")
    }

    // ---------------------------------------------------------------- reading

    data class Ayah(val s: Int, val a: Int, val texts: List<Pair<Pack, String>>)

    fun quranPacks(c: Context) = installed(c).filter { it.kind == Kind.QURAN }
        .sortedBy { if (it.lang == "Arabic" && !it.slug.contains("-la")) 0 else 1 }
    fun tafsirPacks(c: Context) = installed(c).filter { it.kind == Kind.TAFSIR }
    fun hadithPacks(c: Context) = installed(c).filter { it.kind == Kind.HADITH }

    fun surah(c: Context, s: Int, editions: List<Pack>): List<Ayah> {
        val d = db(c)
        val byEd = editions.associateWith { p ->
            d.rawQuery("SELECT a, text FROM ayah WHERE ed = ? AND s = ? ORDER BY a", arrayOf(p.slug, s.toString())).use { cur ->
                buildMap { while (cur.moveToNext()) put(cur.getInt(0), cur.getString(1)) }
            }
        }
        val count = Surahs.ayahs(s)
        return (1..count).map { a -> Ayah(s, a, editions.mapNotNull { p -> byEd[p]?.get(a)?.let { p to it } }) }
    }

    fun ayahText(c: Context, ed: String, s: Int, a: Int): String? =
        db(c).rawQuery("SELECT text FROM ayah WHERE ed = ? AND s = ? AND a = ?", arrayOf(ed, s.toString(), a.toString())).use { if (it.moveToFirst()) it.getString(0) else null }

    /** Tafsir for an ayah. Many tafsirs explain a passage at its first ayah, so fall back to the nearest one before. */
    fun tafsir(c: Context, ed: String, s: Int, a: Int): Pair<Int, String>? =
        db(c).rawQuery("SELECT a, text FROM tafsir WHERE ed = ? AND s = ? AND a <= ? ORDER BY a DESC LIMIT 1", arrayOf(ed, s.toString(), a.toString()))
            .use { if (it.moveToFirst()) it.getInt(0) to it.getString(1) else null }

    data class HBook(val book: Int, val name: String, val first: String, val last: String)
    data class Hadith(val ed: String, val n: String, val ord: Int, val book: Int, val text: String, val grade: String)

    fun hadithBooks(c: Context, ed: String): List<HBook> =
        db(c).rawQuery("SELECT book, name, first, last FROM hbook WHERE ed = ? ORDER BY book", arrayOf(ed)).use { cur ->
            buildList { while (cur.moveToNext()) add(HBook(cur.getInt(0), cur.getString(1), cur.getString(2), cur.getString(3))) }
        }

    fun hadiths(c: Context, ed: String, book: Int): List<Hadith> =
        db(c).rawQuery("SELECT n, ord, book, text, grade FROM hadith WHERE ed = ? AND book = ? ORDER BY ord", arrayOf(ed, book.toString())).use { cur ->
            buildList { while (cur.moveToNext()) add(Hadith(ed, cur.getString(0), cur.getInt(1), cur.getInt(2), cur.getString(3), cur.getString(4))) }
        }

    fun hadithAt(c: Context, ed: String, ord: Int): Hadith? =
        db(c).rawQuery("SELECT n, ord, book, text, grade FROM hadith WHERE ed = ? AND ord = ?", arrayOf(ed, ord.toString())).use { cur ->
            if (cur.moveToFirst()) Hadith(ed, cur.getString(0), cur.getInt(1), cur.getInt(2), cur.getString(3), cur.getString(4)) else null
        }

    fun hadithCount(c: Context, ed: String): Int =
        db(c).rawQuery("SELECT COUNT(*) FROM hadith WHERE ed = ?", arrayOf(ed)).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    data class Hit(val ed: String, val ref: String, val snippet: String)

    /** Full-text search across every installed Quran translation and hadith book, entirely offline. */
    fun search(c: Context, query: String, limit: Int = 60): List<Hit> {
        val words = query.trim().split(Regex("\\s+")).filter { it.length > 1 }.map { it.replace("\"", "") }
        if (words.isEmpty()) return emptyList()
        val match = words.joinToString(" ") { "$it*" }
        return runCatching {
            db(c).rawQuery("SELECT ed, ref, snippet(fts, '«', '»', '…', -1, 24) FROM fts WHERE text MATCH ? LIMIT ?", arrayOf(match, limit.toString())).use { cur ->
                buildList { while (cur.moveToNext()) add(Hit(cur.getString(0), cur.getString(1), cur.getString(2))) }
            }
        }.getOrDefault(emptyList())
    }

    fun packName(c: Context, ed: String) = installed(c).firstOrNull { it.slug == ed }?.let { p ->
        if (p.kind == Kind.HADITH) "${p.name} (${p.lang})" else p.name
    } ?: ed
}
