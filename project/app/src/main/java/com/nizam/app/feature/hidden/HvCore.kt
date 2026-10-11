package com.nizam.app.feature.hidden

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import android.util.LruCache
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import com.nizam.app.feature.future.Sealed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * The hidden vault's engine. Fully offline: nothing here touches the network or any AI.
 *
 * Keys: a random 256-bit data key per vault, wrapped twice — first with a key derived from your PIN
 * (PBKDF2, 150k rounds), then with the phone's hardware-backed Keystore key. Both are needed to open it,
 * so the files are useless off this phone and useless on it without the PIN. A second, decoy PIN opens a
 * separate vault.
 *
 * Media: files are encrypted in 1 MB chunks (AES-GCM each), so videos stream and seek straight from the
 * encrypted file — a decrypted copy is never written to storage.
 */
object Hv {
    private const val CHUNK = 1 shl 20
    private const val HEADER = 16
    private const val OVER = 28                       // 12-byte nonce + 16-byte tag per chunk

    private fun p(c: Context) = c.getSharedPreferences("hv_meta", 0)
    fun root(c: Context) = File(c.filesDir, ".hv").apply { mkdirs(); File(this, ".nomedia").createNewFile() }
    fun dir(c: Context, slot: String) = File(root(c), slot).apply { mkdirs() }

    fun isSetUp(c: Context) = p(c).contains("wrap_a")
    fun hasDecoy(c: Context) = p(c).contains("wrap_b")
    fun code(c: Context) = p(c).getString("code", "*#1947#")!!
    fun setCode(c: Context, v: String) = p(c).edit().putString("code", v.trim()).apply()
    fun wipeAfter(c: Context) = p(c).getInt("wipe_after", 0)
    fun setWipeAfter(c: Context, n: Int) = p(c).edit().putInt("wipe_after", n).apply()
    fun fails(c: Context) = p(c).getInt("fails", 0)

    private fun pinKey(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(), salt, 150_000, 256)).encoded

    private fun gcm(mode: Int, key: ByteArray, iv: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding").apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv)) }
    private fun rnd(n: Int) = ByteArray(n).also { SecureRandom().nextBytes(it) }
    fun seal(key: ByteArray, plain: ByteArray): ByteArray { val iv = rnd(12); return iv + gcm(Cipher.ENCRYPT_MODE, key, iv).doFinal(plain) }
    fun open(key: ByteArray, blob: ByteArray): ByteArray = gcm(Cipher.DECRYPT_MODE, key, blob.copyOfRange(0, 12)).doFinal(blob, 12, blob.size - 12)

    /** Creates (or replaces) a vault's PIN. slot "a" is the real vault, "b" the decoy. Keeps the data key when re-PINning. */
    fun setPin(c: Context, slot: String, pin: String, existingDek: ByteArray? = null) {
        val dek = existingDek ?: rnd(32)
        val salt = rnd(16)
        val wrapped = Sealed.seal(seal(pinKey(pin, salt), dek))
        p(c).edit().putString("salt_$slot", Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString("wrap_$slot", Base64.encodeToString(wrapped, Base64.NO_WRAP)).apply()
        dir(c, slot)
    }

    private fun tryUnwrap(c: Context, slot: String, pin: String): ByteArray? = runCatching {
        val salt = Base64.decode(p(c).getString("salt_$slot", null) ?: return null, Base64.NO_WRAP)
        val wrapped = Base64.decode(p(c).getString("wrap_$slot", null) ?: return null, Base64.NO_WRAP)
        open(pinKey(pin, salt), Sealed.open(wrapped))
    }.getOrNull()

    /** Opens the vault this PIN belongs to, or counts a failed try (and wipes after too many, if you chose that). */
    fun unlock(c: Context, pin: String): Session? {
        val s = tryUnwrap(c, "a", pin)?.let { Session(c.applicationContext, "a", it) } ?: tryUnwrap(c, "b", pin)?.let { Session(c.applicationContext, "b", it) }
        if (s != null) { p(c).edit().putInt("fails", 0).apply(); return s }
        val n = fails(c) + 1
        p(c).edit().putInt("fails", n).apply()
        if (wipeAfter(c) in 1..n) wipeAll(c)
        return null
    }

    fun wipeAll(c: Context) { root(c).deleteRecursively(); p(c).edit().clear().apply(); Session.current = null }

    // ── chunked media files ────────────────────────────────────────────────────────────────────
    fun encryptTo(input: InputStream, out: File, dek: ByteArray, onBytes: (Long) -> Unit = {}): Long {
        var total = 0L
        RandomAccessFile(out, "rw").use { f ->
            f.setLength(0); f.write(ByteArray(HEADER))
            val buf = ByteArray(CHUNK)
            while (true) {
                var n = 0
                while (n < CHUNK) { val r = input.read(buf, n, CHUNK - n); if (r < 0) break; n += r }
                if (n == 0) break
                val iv = rnd(12)
                f.write(iv); f.write(gcm(Cipher.ENCRYPT_MODE, dek, iv).doFinal(buf, 0, n))
                total += n; onBytes(total)
                if (n < CHUNK) break
            }
            f.seek(0); f.write(byteArrayOf('H'.code.toByte(), 'V'.code.toByte(), '1'.code.toByte(), 0)); f.writeInt(CHUNK); f.writeLong(total)
        }
        return total
    }

    /** Random-access reader over an encrypted media file, decrypting only the chunks being read. */
    class Reader(file: File, private val dek: ByteArray) : AutoCloseable {
        private val f = RandomAccessFile(file, "r")
        val length: Long
        private val chunk: Int
        private var cachedIndex = -1L
        private var cached = ByteArray(0)
        init { f.seek(4); chunk = f.readInt(); length = f.readLong() }
        private fun load(i: Long) {
            if (i == cachedIndex) return
            val start = i * chunk
            val plain = minOf(chunk.toLong(), length - start).toInt()
            val enc = ByteArray(plain + OVER)
            f.seek(HEADER + i * (chunk + OVER)); f.readFully(enc)
            cached = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(128, enc.copyOfRange(0, 12))) }.doFinal(enc, 12, enc.size - 12)
            cachedIndex = i
        }
        @Synchronized fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int {
            if (pos >= length) return -1
            val i = pos / chunk; load(i)
            val within = (pos - i * chunk).toInt()
            val n = minOf(len, cached.size - within)
            System.arraycopy(cached, within, buf, off, n)
            return n
        }
        fun readAll(): ByteArray { val out = ByteArrayOutputStream(); val b = ByteArray(CHUNK); var p = 0L
            while (true) { val n = read(p, b, 0, b.size); if (n <= 0) break; out.write(b, 0, n); p += n }; return out.toByteArray() }
        override fun close() = f.close()
    }

    // ── the library ────────────────────────────────────────────────────────────────────────────
    data class Item(val id: String, val name: String, val video: Boolean, val size: Long, val durationMs: Long, val added: Long,
                    val tags: List<String>, val fav: Boolean, val views: Int, val lastPos: Long, val lastViewed: Long, val w: Int, val h: Int,
                    val performers: List<String> = emptyList(), val source: String = "", val rating: Int = 0, val notes: String = "", val watchMs: Long = 0,
                    val albums: List<String> = emptyList())

    class Session(val c: Context, val slot: String, val dek: ByteArray) {
        companion object { @Volatile var current: Session? = null }
        private val thumbs = LruCache<String, Bitmap>(120)
        private fun indexFile() = File(dir(c, slot), "index.bin")
        private fun metaFile() = File(dir(c, slot), "meta.bin")
        fun media(id: String) = File(dir(c, slot), "m_$id.bin")
        private fun thumbFile(id: String) = File(dir(c, slot), "t_$id.bin")

        @Synchronized fun items(): List<Item> = runCatching {
            val a = JSONArray(String(open(dek, indexFile().readBytes())))
            (0 until a.length()).map { a.getJSONObject(it) }.map { o -> Item(o.optString("id"), o.optString("n"), o.optBoolean("v"), o.optLong("s"),
                o.optLong("d"), o.optLong("a"), o.optJSONArray("t")?.let { t -> (0 until t.length()).map { t.optString(it) } }.orEmpty(),
                o.optBoolean("f"), o.optInt("views"), o.optLong("pos"), o.optLong("lv"), o.optInt("w"), o.optInt("h"),
                o.optJSONArray("pf")?.let { t -> (0 until t.length()).map { t.optString(it) } }.orEmpty(), o.optString("src"), o.optInt("r"), o.optString("no"), o.optLong("wm"),
                o.optJSONArray("al")?.let { t -> (0 until t.length()).map { t.optString(it) } }.orEmpty()) }
        }.getOrElse { emptyList() }

        @Synchronized fun save(list: List<Item>) {
            val a = JSONArray(); list.forEach { i -> a.put(JSONObject().put("id", i.id).put("n", i.name).put("v", i.video).put("s", i.size).put("d", i.durationMs)
                .put("a", i.added).put("t", JSONArray(i.tags)).put("f", i.fav).put("views", i.views).put("pos", i.lastPos).put("lv", i.lastViewed).put("w", i.w).put("h", i.h)
                .put("pf", JSONArray(i.performers)).put("src", i.source).put("r", i.rating).put("no", i.notes).put("wm", i.watchMs).put("al", JSONArray(i.albums))) }
            indexFile().writeBytes(seal(dek, a.toString().toByteArray()))
        }
        fun update(id: String, change: (Item) -> Item) = save(items().map { if (it.id == id) change(it) else it })

        /** Your tag list, their icons, and keyword rules for sorting imports by file name. */
        @Synchronized fun meta(): JSONObject = runCatching { JSONObject(String(open(dek, metaFile().readBytes()))) }.getOrElse { JSONObject() }
        @Synchronized fun saveMeta(o: JSONObject) = metaFile().writeBytes(seal(dek, o.toString().toByteArray()))
        fun tags(): List<Pair<String, String>> = meta().optJSONArray("tags")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }
            .map { it.optString("n") to it.optString("i", "🔥") } }.orEmpty()
        fun saveTags(l: List<Pair<String, String>>) = saveMeta(meta().put("tags", JSONArray().apply { l.forEach { put(JSONObject().put("n", it.first).put("i", it.second)) } }))
        /** Albums: a name and the item whose thumbnail is its cover (blank = the newest item in it). */
        fun albums(): List<Pair<String, String>> = meta().optJSONArray("albums")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }
            .map { it.optString("n") to it.optString("c") } }.orEmpty()
        fun saveAlbums(l: List<Pair<String, String>>) = saveMeta(meta().put("albums", JSONArray().apply { l.forEach { put(JSONObject().put("n", it.first).put("c", it.second)) } }))
        fun renameAlbum(from: String, to: String) {
            saveAlbums(albums().map { if (it.first == from) to to it.second else it })
            save(items().map { i -> if (from in i.albums) i.copy(albums = i.albums.map { if (it == from) to else it }) else i })
        }
        fun deleteAlbum(name: String) {
            saveAlbums(albums().filter { it.first != name })
            save(items().map { i -> if (name in i.albums) i.copy(albums = i.albums - name) else i })
        }

        fun rules(): List<Pair<String, String>> = meta().optJSONArray("rules")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }
            .map { it.optString("k") to it.optString("t") } }.orEmpty()
        fun saveRules(l: List<Pair<String, String>>) = saveMeta(meta().put("rules", JSONArray().apply { l.forEach { put(JSONObject().put("k", it.first).put("t", it.second)) } }))

        fun thumb(id: String): Bitmap? = thumbs.get(id) ?: runCatching {
            val b = open(dek, thumbFile(id).readBytes()); BitmapFactory.decodeByteArray(b, 0, b.size) }.getOrNull()?.also { thumbs.put(id, it) }

        /** Encrypts one file in (with a thumbnail), and applies your keyword rules to its name. */
        suspend fun import(uri: Uri, onProgress: (Long, Long) -> Unit = { _, _ -> }): Item = withContext(Dispatchers.IO) {
            val cr = c.contentResolver
            val type = cr.getType(uri).orEmpty()
            var name = "file"; var size = 0L
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { if (it.moveToFirst()) { name = it.getString(0) ?: name; size = it.getLong(1) } }
            val video = type.startsWith("video") || Regex("\\.(mp4|mkv|webm|mov|3gp|avi|m4v)$", RegexOption.IGNORE_CASE).containsMatchIn(name)
            val id = System.nanoTime().toString(36)
            // thumbnail and details, read from the source before it's sealed
            var dur = 0L; var w = 0; var h = 0
            val bmp: Bitmap? = runCatching {
                if (video) MediaMetadataRetriever().run { try { setDataSource(c, uri)
                    dur = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0
                    w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
                    h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
                    getFrameAtTime(dur * 1000 / 5, MediaMetadataRetriever.OPTION_CLOSEST_SYNC) } finally { release() } }
                else cr.openInputStream(uri)?.use { s -> BitmapFactory.decodeStream(s, null, BitmapFactory.Options().apply { inSampleSize = 4 }) }?.also { w = it.width * 4; h = it.height * 4 }
            }.getOrNull()
            bmp?.let { b ->
                val scale = 360f / maxOf(b.width, b.height)
                val t = if (scale < 1) Bitmap.createScaledBitmap(b, (b.width * scale).toInt(), (b.height * scale).toInt(), true) else b
                val out = ByteArrayOutputStream(); t.compress(Bitmap.CompressFormat.JPEG, 82, out)
                thumbFile(id).writeBytes(seal(dek, out.toByteArray()))
            }
            val written = cr.openInputStream(uri)!!.use { encryptTo(it, media(id), dek) { done -> onProgress(done, size) } }
            val lower = name.lowercase()
            val auto = rules().filter { (k, _) -> k.isNotBlank() && lower.contains(k.lowercase()) }.map { it.second }.distinct()
            val item = Item(id, name.substringBeforeLast('.'), video, written, dur, System.currentTimeMillis(), auto, false, 0, 0, 0, w, h)
            save(items() + item); item
        }

        fun delete(ids: Set<String>) { ids.forEach { media(it).delete(); thumbFile(it).delete(); thumbs.remove(it) }; save(items().filter { it.id !in ids }) }

        /** A temporary plain copy for sharing out of the vault — cleaned up when the vault locks. */
        fun exportToCache(i: Item): File {
            val ext = if (i.video) "mp4" else "jpg"
            val f = File(File(c.cacheDir, "hv_out").apply { mkdirs() }, "${i.name.take(40).replace(Regex("[^A-Za-z0-9_-]"), "_")}.$ext")
            Reader(media(i.id), dek).use { r -> f.writeBytes(r.readAll()) }
            return f
        }
    }

    /** Set while the vault itself opened the camera or file picker — so leaving for that doesn't lock it. */
    @Volatile var expectingReturn = false

    fun lock(c: Context) { Session.current = null; File(c.cacheDir, "hv_out").deleteRecursively() }
}

/** Media3 reads videos straight from the encrypted file through this — seeking included. */
@OptIn(UnstableApi::class)
class HvDataSource(private val reader: Hv.Reader) : BaseDataSource(false) {
    private var uri: Uri? = null
    private var pos = 0L
    private var left = 0L
    private var opened = false
    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri; transferInitializing(dataSpec)
        pos = dataSpec.position
        left = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else reader.length - pos
        opened = true; transferStarted(dataSpec)
        return left
    }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (left <= 0) return C.RESULT_END_OF_INPUT
        val n = reader.read(pos, buffer, offset, minOf(length.toLong(), left).toInt())
        if (n <= 0) return C.RESULT_END_OF_INPUT
        pos += n; left -= n; bytesTransferred(n)
        return n
    }
    override fun getUri(): Uri? = uri
    override fun close() { if (opened) { opened = false; transferEnded() } }
}
