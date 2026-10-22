package com.nizam.app.feature.arcade

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/** One console. Its emulator is a libretro core: built into the app, or downloaded once from the libretro buildbot. */
class ArcadeSystem(
    val id: String, val name: String, val short: String, val exts: Set<String>, val core: String, val color: Long,
    val layout: Layout, val analog: Boolean = false, val rightStick: Boolean = false, val shoulders: Int = 0, val touch: Boolean = false,
    val note: String = "", val bios: List<Bios> = emptyList(), val family: String = "",
) {
    enum class Layout { ONE, TWO, NINTENDO4, PLAYSTATION, SEGA3, GBA, N64 }
    /** A console's own startup file; [required] ones must be present for any game to start. */
    class Bios(val file: String, val what: String, val required: Boolean = false)
}

object ArcadeSystems {
    val all = listOf(
        ArcadeSystem("gb", "Game Boy / Color", "GB", setOf("gb", "gbc", "sgb", "dmg"), "gambatte", 0xFF8BAC0F, ArcadeSystem.Layout.TWO, family = "Nintendo"),
        ArcadeSystem("gba", "Game Boy Advance", "GBA", setOf("gba", "agb"), "mgba", 0xFF5B5BD6, ArcadeSystem.Layout.GBA, shoulders = 1, family = "Nintendo",
            bios = listOf(ArcadeSystem.Bios("gba_bios.bin", "Optional: the real boot screen"))),
        ArcadeSystem("nes", "Nintendo (NES / Famicom)", "NES", setOf("nes", "unf", "unif", "fds"), "fceumm", 0xFFD64545, ArcadeSystem.Layout.TWO, family = "Nintendo",
            bios = listOf(ArcadeSystem.Bios("disksys.rom", "Needed for Famicom Disk System (.fds) games only"))),
        ArcadeSystem("snes", "Super Nintendo", "SNES", setOf("sfc", "smc", "swc", "fig", "bs"), "snes9x", 0xFF8A7BD6, ArcadeSystem.Layout.NINTENDO4, shoulders = 1, family = "Nintendo"),
        ArcadeSystem("n64", "Nintendo 64", "N64", setOf("n64", "z64", "v64"), "mupen64plus_next_gles3", 0xFF2FA36B, ArcadeSystem.Layout.N64, analog = true, rightStick = true, shoulders = 1,
            note = "Heavy: best on a fast phone.", family = "Nintendo"),
        ArcadeSystem("nds", "Nintendo DS", "NDS", setOf("nds"), "melonds", 0xFF4FA3D6, ArcadeSystem.Layout.NINTENDO4, shoulders = 1, touch = true,
            note = "Tap the lower screen to touch.", family = "Nintendo",
            bios = listOf(ArcadeSystem.Bios("bios7.bin", "Optional"), ArcadeSystem.Bios("bios9.bin", "Optional"), ArcadeSystem.Bios("firmware.bin", "Optional"))),
        ArcadeSystem("vb", "Virtual Boy", "VB", setOf("vb", "vboy"), "mednafen_vb", 0xFFC0392B, ArcadeSystem.Layout.NINTENDO4, shoulders = 1, family = "Nintendo"),
        ArcadeSystem("psx", "PlayStation", "PS1", setOf("cue", "chd", "pbp", "m3u", "img", "ccd", "exe"), "pcsx_rearmed", 0xFF8E97B8, ArcadeSystem.Layout.PLAYSTATION, analog = true, rightStick = true, shoulders = 2,
            note = "Use one .chd, .pbp or .cue file per game.", family = "Sony",
            bios = listOf(ArcadeSystem.Bios("scph5501.bin", "Optional: more games work, real boot screen"), ArcadeSystem.Bios("scph1001.bin", "Optional"))),
        ArcadeSystem("psp", "PSP", "PSP", setOf("iso", "cso", "pbp", "elf"), "ppsspp", 0xFF3FA3C7, ArcadeSystem.Layout.PLAYSTATION, analog = true, shoulders = 1,
            note = "Heavy: best on a fast phone.", family = "Sony"),
        ArcadeSystem("md", "Sega Genesis / Master System / Game Gear", "SEGA", setOf("md", "gen", "smd", "sms", "gg", "sg", "68k"), "genesis_plus_gx", 0xFF3D6FD6, ArcadeSystem.Layout.SEGA3, family = "Sega"),
        ArcadeSystem("scd", "Sega CD / 32X", "32X", setOf("32x"), "picodrive", 0xFF2F5BB5, ArcadeSystem.Layout.SEGA3, family = "Sega",
            note = "For Sega CD discs, choose this console when you add the game.",
            bios = listOf(ArcadeSystem.Bios("bios_CD_U.bin", "Needed for Sega CD (US)"), ArcadeSystem.Bios("bios_CD_E.bin", "Sega CD (Europe)"), ArcadeSystem.Bios("bios_CD_J.bin", "Mega CD (Japan)"))),
        ArcadeSystem("saturn", "Sega Saturn", "SAT", setOf("ss"), "yabasanshiro", 0xFF6F6FA8, ArcadeSystem.Layout.SEGA3, shoulders = 1, family = "Sega",
            note = "Choose this console when you add a Saturn disc.", bios = listOf(ArcadeSystem.Bios("saturn_bios.bin", "Needed", required = true))),
        ArcadeSystem("dc", "Dreamcast", "DC", setOf("cdi", "gdi"), "flycast", 0xFFE0702B, ArcadeSystem.Layout.PLAYSTATION, analog = true, shoulders = 2, family = "Sega",
            note = "Heavy: best on a fast phone.", bios = listOf(ArcadeSystem.Bios("dc/dc_boot.bin", "Recommended"), ArcadeSystem.Bios("dc/dc_flash.bin", "Recommended"))),
        ArcadeSystem("arcade", "Arcade (Neo Geo, CPS1/2/3 and more)", "ARC", setOf("zip", "7z"), "fbneo", 0xFFE53970, ArcadeSystem.Layout.NINTENDO4, shoulders = 1, family = "Arcade",
            note = "Keep arcade games zipped, with the MAME/FBNeo name. SELECT inserts a coin.",
            bios = listOf(ArcadeSystem.Bios("neogeo.zip", "Needed for Neo Geo games"))),
        ArcadeSystem("pce", "PC Engine / TurboGrafx-16", "PCE", setOf("pce", "sgx"), "mednafen_pce_fast", 0xFFE38B4F, ArcadeSystem.Layout.TWO, family = "NEC"),
        ArcadeSystem("a2600", "Atari 2600", "2600", setOf("a26", "bin"), "stella", 0xFFC98B5A, ArcadeSystem.Layout.ONE, family = "Atari"),
        ArcadeSystem("a7800", "Atari 7800", "7800", setOf("a78"), "prosystem", 0xFFB3784F, ArcadeSystem.Layout.TWO, family = "Atari"),
        ArcadeSystem("lynx", "Atari Lynx", "LYNX", setOf("lnx"), "handy", 0xFFD6A24C, ArcadeSystem.Layout.TWO, family = "Atari",
            bios = listOf(ArcadeSystem.Bios("lynxboot.img", "Needed", required = true))),
        ArcadeSystem("ngp", "Neo Geo Pocket / Color", "NGP", setOf("ngp", "ngc"), "mednafen_ngp", 0xFF4D7A9E, ArcadeSystem.Layout.TWO, family = "SNK"),
        ArcadeSystem("ws", "WonderSwan / Color", "WS", setOf("ws", "wsc"), "mednafen_wswan", 0xFF6AA8D8, ArcadeSystem.Layout.TWO, family = "Bandai"),
        ArcadeSystem("msx", "MSX / MSX2", "MSX", setOf("mx1", "mx2", "dsk", "cas", "rom"), "bluemsx", 0xFF7A8F5A, ArcadeSystem.Layout.TWO, family = "Computers"),
    )
    fun byId(id: String) = all.firstOrNull { it.id == id }
    fun byExt(ext: String) = all.filter { ext.lowercase(Locale.ROOT) in it.exts }
    fun corePath(c: Context, s: ArcadeSystem): File = ArcadeCores.path(c, s)
    fun coreReady(c: Context, s: ArcadeSystem) = ArcadeCores.ready(c, s)
    fun biosMissing(c: Context, s: ArcadeSystem) = s.bios.filter { it.required && !File(ArcadeLibrary.systemDir(c), it.file).exists() }
}

/**
 * The emulator cores. Every core ships inside the app (the build puts them in the APK); if one is ever missing, it is
 * downloaded once from the libretro buildbot into the app's own storage and loaded from there.
 */
object ArcadeCores {
    private const val BUILDBOT = "https://buildbot.libretro.com/nightly/android/latest/arm64-v8a"
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private fun fileName(s: ArcadeSystem) = "lib${s.core}_libretro_android.so"
    fun bundled(c: Context, s: ArcadeSystem) = File(c.applicationInfo.nativeLibraryDir, fileName(s))
    fun downloadedDir(c: Context) = File(c.filesDir, "arcade/cores").apply { mkdirs() }
    fun downloaded(c: Context, s: ArcadeSystem) = File(downloadedDir(c), fileName(s))
    fun path(c: Context, s: ArcadeSystem): File = bundled(c, s).takeIf { it.exists() } ?: downloaded(c, s)
    fun ready(c: Context, s: ArcadeSystem) = bundled(c, s).exists() || downloaded(c, s).let { it.exists() && it.length() > 10_000 }
    fun source(c: Context, s: ArcadeSystem) = when { bundled(c, s).exists() -> "Built in"; downloaded(c, s).exists() -> "Downloaded"; else -> "Not installed" }

    /** Live download progress per console id (0..1), for every screen that shows it. */
    val progress = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Float>>(emptyMap())

    /** Downloads one core (about 0.1–16 MB) and makes it ready. */
    suspend fun download(c: Context, s: ArcadeSystem): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            progress.value = progress.value + (s.id to 0f)
            val tmp = File(downloadedDir(c), "${s.core}.zip.part")
            http.newCall(Request.Builder().url("$BUILDBOT/${s.core}_libretro_android.so.zip").header("User-Agent", "Atlas-Arcade/2.0").build()).execute().use { r ->
                if (!r.isSuccessful) error("The emulator server answered ${r.code}")
                val body = r.body!!; val total = body.contentLength().coerceAtLeast(1L); var done = 0L
                body.byteStream().use { i -> tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n
                        progress.value = progress.value + (s.id to (done.toFloat() / total).coerceIn(0f, 0.98f)) }
                } }
            }
            val out = downloaded(c, s); val part = File(out.path + ".new")
            ZipInputStream(tmp.inputStream()).use { z ->
                var e = z.nextEntry
                while (e != null) { if (!e.isDirectory && e.name.endsWith(".so")) { part.outputStream().use { z.copyTo(it) }; break }; e = z.nextEntry }
            }
            tmp.delete()
            if (!part.exists() || part.length() < 10_000) error("The download didn't contain the emulator")
            out.delete(); part.renameTo(out)
            Unit
        }.also { progress.value = progress.value - s.id }
    }

    /** Every console that isn't ready yet, one after another. Returns how many succeeded and failed. */
    suspend fun downloadAll(c: Context): Pair<Int, Int> {
        var ok = 0; var bad = 0
        for (s in ArcadeSystems.all.filter { !ready(c, it) }) if (download(c, s).isSuccess) ok++ else bad++
        return ok to bad
    }

    fun remove(c: Context, s: ArcadeSystem) { downloaded(c, s).delete() }
}

class ArcadeGame(val id: String, var title: String, val system: String, val path: String, var fav: Boolean = false, var last: Long = 0L, var playMs: Long = 0L, var cover: String? = null) {
    fun toJson() = JSONObject().put("id", id).put("t", title).put("s", system).put("p", path).put("f", fav).put("l", last).put("pm", playMs).put("c", cover ?: "")
    companion object {
        fun fromJson(o: JSONObject) = ArcadeGame(o.getString("id"), o.getString("t"), o.getString("s"), o.getString("p"), o.optBoolean("f"), o.optLong("l"), o.optLong("pm"), o.optString("c").ifEmpty { null })
    }
}

/** The games on this phone. Only the list lives in preferences; the game files are in the app's own storage. */
object ArcadeLibrary {
    private const val PREFS = "arcade"
    fun root(c: Context) = File(c.filesDir, "arcade").apply { mkdirs() }
    fun romsDir(c: Context, system: String) = File(root(c), "roms/$system").apply { mkdirs() }
    fun systemDir(c: Context) = File(root(c), "system").apply { mkdirs() }
    fun savesDir(c: Context) = File(root(c), "saves").apply { mkdirs() }
    fun statesDir(c: Context) = File(root(c), "states").apply { mkdirs() }

    /** The PSP emulator needs its own fonts, on-screen atlas and per-game compatibility list in the system folder. They ship inside the app; unpack them once. */
    fun ensurePspFiles(c: Context) {
        val dir = systemDir(c)
        if (File(dir, "PPSSPP/ppge_atlas.zim").exists()) return
        runCatching {
            val base = dir.canonicalPath + File.separator
            c.assets.open("PPSSPP.zip").use { raw ->
                java.util.zip.ZipInputStream(raw).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val out = File(dir, e.name)
                        if (out.canonicalPath.startsWith(base)) {
                            if (e.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); out.outputStream().use { z.copyTo(it) } }
                        }
                        e = z.nextEntry
                    }
                }
            }
        }
    }

    fun list(c: Context): MutableList<ArcadeGame> = runCatching {
        val a = JSONArray(c.getSharedPreferences(PREFS, 0).getString("lib", "[]"))
        (0 until a.length()).map { ArcadeGame.fromJson(a.getJSONObject(it)) }.filter { File(it.path).exists() }.toMutableList()
    }.getOrDefault(mutableListOf())

    fun save(c: Context, games: List<ArcadeGame>) {
        c.getSharedPreferences(PREFS, 0).edit().putString("lib", JSONArray(games.map { it.toJson() }).toString()).apply()
    }

    fun add(c: Context, g: ArcadeGame) {
        val l = list(c); l.removeAll { it.id == g.id || it.path == g.path }; l.add(g); save(c, l)
    }

    fun update(c: Context, id: String, f: (ArcadeGame) -> Unit) {
        val l = list(c); l.firstOrNull { it.id == id }?.let { f(it); save(c, l) }
    }

    fun remove(c: Context, g: ArcadeGame) {
        File(g.path).delete()
        statesDir(c).listFiles { f -> f.name.startsWith(g.id + "_") }?.forEach { it.delete() }
        File(savesDir(c), g.id + ".srm").delete()
        save(c, list(c).filter { it.id != g.id })
    }

    private fun unzipConsoleGame(c: Context, zip: File): Pair<ArcadeSystem, File>? = runCatching {
        java.util.zip.ZipFile(zip).use { z ->
            val entries = z.entries().toList().filter { !it.isDirectory }
            val hit = entries.firstNotNullOfOrNull { e ->
                val ext = e.name.substringAfterLast('.', "").lowercase(Locale.ROOT)
                ArcadeSystems.all.firstOrNull { it.id != "arcade" && ext in it.exts && ext !in setOf("bin", "rom", "img") }?.let { it to e }
            } ?: return null
            val (sys, e) = hit
            val out = File(romsDir(c, sys.id), e.name.substringAfterLast('/'))
            z.getInputStream(e).use { i -> out.outputStream().use { o -> i.copyTo(o) } }
            sys to out
        }
    }.getOrNull()

    fun cleanTitle(file: String): String = file.substringBeforeLast('.').replace('_', ' ').replace(Regex("\\s*[\\[(][^\\])]*[\\])]"), "").replace(Regex("\\s+"), " ").trim().ifEmpty { file }

    /** Copies a game file picked from the phone's storage into the library. */
    suspend fun import(c: Context, uri: Uri, system: ArcadeSystem): ArcadeGame? = withContext(Dispatchers.IO) {
        runCatching {
            val name = c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur -> if (cur.moveToFirst()) cur.getString(0) else null } ?: "game"
            var sys = system
            var dest = File(romsDir(c, sys.id), name.replace('/', '_'))
            c.contentResolver.openInputStream(uri)!!.use { i -> dest.outputStream().use { o -> i.copyTo(o, 256 * 1024) } }
            // a zipped console game (not an arcade set): unpack the game inside and file it under its own console
            if (sys.id == "arcade" && name.lowercase(Locale.ROOT).endsWith(".zip")) unzipConsoleGame(c, dest)?.let { (s2, f) -> dest.delete(); sys = s2; dest = f }
            val g = ArcadeGame(Integer.toHexString(dest.absolutePath.hashCode()) + System.currentTimeMillis().toString(16), cleanTitle(name), sys.id, dest.absolutePath)
            add(c, g); g
        }.getOrNull()
    }
}

/**
 * The free games store: Homebrew Hub (hh.gbdev.io), a public catalogue of games that people made themselves and share freely
 * for Game Boy, Game Boy Color, Game Boy Advance and NES.
 */
object HomebrewHub {
    private const val BASE = "https://hh3.gbdev.io"
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()

    class Entry(val slug: String, val title: String, val platform: String, val developer: String, val description: String, val basepath: String,
                val file: String, val cover: String?, val tags: List<String>)

    val platforms = listOf("GB" to "gb", "GBC" to "gb", "GBA" to "gba", "NES" to "nes")

    fun systemFor(platform: String) = platforms.firstOrNull { it.first == platform }?.second ?: "gb"

    suspend fun search(platform: String, page: Int, perPage: Int = 48): Pair<List<Entry>, Int> = withContext(Dispatchers.IO) {
        val url = "$BASE/api/search?platform=$platform&results=$perPage&page=$page&sort=firstadded_date&order=desc"
        http.newCall(Request.Builder().url(url).header("User-Agent", "Atlas-Arcade/1.0").build()).execute().use { r ->
            if (!r.isSuccessful) error("The store answered ${r.code}")
            val o = JSONObject(r.body!!.string())
            val arr = o.optJSONArray("entries") ?: JSONArray()
            val list = (0 until arr.length()).mapNotNull { runCatching { parse(arr.getJSONObject(it)) }.getOrNull() }
            list to o.optInt("page_total", 1)
        }
    }

    private fun parse(e: JSONObject): Entry? {
        val files = e.optJSONArray("files") ?: return null
        var pick: String? = null
        for (i in 0 until files.length()) {
            val f = files.getJSONObject(i)
            if (f.optBoolean("playable", true) && (f.optBoolean("default") || pick == null)) pick = f.optString("filename")
        }
        val dev = e.opt("developer").let { d -> if (d is JSONArray) (0 until d.length()).joinToString(", ") { d.optString(it) } else d?.toString().orEmpty() }
        val shots = e.optJSONArray("screenshots")
        val cover = shots?.let { s -> (0 until s.length()).map { s.optString(it) }.firstOrNull { it.isNotEmpty() } }
        val tags = e.optJSONArray("tags")?.let { t -> (0 until t.length()).map { t.optString(it) } } ?: emptyList()
        return Entry(e.getString("slug"), e.getString("title"), e.optString("platform"), dev, e.optString("description"), e.optString("basepath"), pick ?: return null, cover, tags)
    }

    fun coverUrl(e: Entry) = e.cover?.let { "$BASE/static/${e.basepath}/entries/${e.slug}/$it" }
    fun fileUrl(e: Entry) = "$BASE/static/${e.basepath}/entries/${e.slug}/${e.file}"

    /** Downloads a game into the library and returns it. */
    suspend fun install(c: Context, e: Entry, progress: (Float) -> Unit): ArcadeGame = withContext(Dispatchers.IO) {
        val sys = ArcadeSystems.byId(systemFor(e.platform))!!
        val outDir = ArcadeLibrary.romsDir(c, sys.id)
        val ext = e.file.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val tmp = File(outDir, "${e.slug}.part")
        http.newCall(Request.Builder().url(fileUrl(e)).header("User-Agent", "Atlas-Arcade/1.0").build()).execute().use { r ->
            if (!r.isSuccessful) error("Download failed (${r.code})")
            val body = r.body!!; val total = body.contentLength().coerceAtLeast(1L)
            var done = 0L
            body.byteStream().use { i -> tmp.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; progress((done.toFloat() / total).coerceIn(0f, 1f)) }
            } }
        }
        var rom = File(outDir, "${e.slug}.${if (ext == "zip") sys.exts.first() else ext}")
        if (ext == "zip") {
            ZipInputStream(tmp.inputStream()).use { z ->
                var entry = z.nextEntry
                while (entry != null) {
                    val n = entry.name.substringAfterLast('/').lowercase(Locale.ROOT)
                    if (!entry.isDirectory && n.substringAfterLast('.', "") in sys.exts) { rom = File(outDir, "${e.slug}.${n.substringAfterLast('.')}"); rom.outputStream().use { z.copyTo(it) }; break }
                    entry = z.nextEntry
                }
            }
            tmp.delete()
        } else { rom.delete(); tmp.renameTo(rom) }
        if (!rom.exists()) error("No game file found in the download")
        val g = ArcadeGame(e.slug + "-" + e.platform.lowercase(Locale.ROOT), e.title, sys.id, rom.absolutePath, cover = coverUrl(e))
        ArcadeLibrary.add(c, g); g
    }
}
