package com.nizam.app.feature.next

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets

/**
 * A small list of records kept in one JSON file on the phone. Enough for committees, debts,
 * watched prices, the capture inbox and the family board, without a database migration each time.
 * [sealed] files are encrypted with the phone's secure hardware key.
 */
class JsonStore(private val c: Context, private val name: String, private val sealed: Boolean = false) {
    init {
        require(name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid JSON store name." }
    }

    private val file get() = File(c.filesDir, "$name.json")
    private val lastGoodFile get() = File(c.filesDir, "$name.json.last-good")
    private val atomicFile get() = AtomicFile(file)
    private val lastGoodAtomicFile get() = AtomicFile(lastGoodFile)

    @Volatile
    var lastReadError: String? = null
        private set

    /**
     * Returns an empty list only for a store that has never been written. Corrupt or undecryptable
     * data remains untouched, is surfaced through [lastReadError], and cannot be overwritten by a
     * later add/update/remove call.
     */
    fun all(): List<JSONObject> = synchronized(PROCESS_LOCK) {
        if (!hasPrimaryFile()) {
            lastReadError = null
            return@synchronized emptyList()
        }
        runCatching {
            val records = decode(readPrimaryBytes())
            lastReadError = null
            records
        }.getOrElse { failure ->
            lastReadError = failure.message ?: "This Atlas store could not be read."
            emptyList()
        }
    }

    fun save(list: List<JSONObject>) = synchronized(PROCESS_LOCK) {
        val hasPrimary = hasPrimaryFile()
        if (!hasPrimary && hasLastGoodFile()) {
            throw IllegalStateException("The main store is missing; recover its last valid copy before saving.")
        }

        val currentBytes = if (hasPrimary) {
            try {
                readPrimaryBytes().also { decode(it) }
            } catch (failure: Exception) {
                lastReadError = failure.message ?: "This Atlas store could not be read."
                throw IllegalStateException("Recover this Atlas store before saving new data.", failure)
            }
        } else null
        val plain = FILE_HEADER + JSONArray().apply { list.forEach { put(it) } }.toString()
        val stored = if (sealed) {
            com.nizam.app.feature.future.Sealed.sealText(plain)
        } else plain
        val bytes = stored.toByteArray(StandardCharsets.UTF_8)
        require(bytes.size <= MAX_STORE_BYTES) { "This JSON store exceeds its safe size limit." }

        // Preserve only a fully readable previous generation; AtomicFile handles interrupted writes.
        currentBytes?.let { writeAtomically(lastGoodAtomicFile, it) }
        writeAtomically(atomicFile, bytes)
        lastReadError = null
    }

    /** Explicit recovery path for a Trust Center or Self-Check action; never auto-restores. */
    fun recoverLastGood(): Boolean = synchronized(PROCESS_LOCK) {
        if (!lastGoodFile.exists() && !File(lastGoodFile.path + ".bak").exists()) return@synchronized false
        val bytes = lastGoodAtomicFile.openRead().use { it.readBytes() }
        decode(bytes)
        writeAtomically(atomicFile, bytes)
        lastReadError = null
        true
    }

    private fun hasPrimaryFile(): Boolean =
        file.exists() || File(file.path + ".bak").exists()

    private fun hasLastGoodFile(): Boolean =
        lastGoodFile.exists() || File(lastGoodFile.path + ".bak").exists()

    private fun readPrimaryBytes(): ByteArray =
        atomicFile.openRead().use { input ->
            val bytes = input.readBytes()
            require(bytes.size <= MAX_STORE_BYTES) { "This JSON store exceeds its safe size limit." }
            bytes
        }

    private fun decode(bytes: ByteArray): List<JSONObject> {
        val stored = String(bytes, StandardCharsets.UTF_8)
        val text = if (sealed) com.nizam.app.feature.future.Sealed.openTextStrict(stored) else stored
        val json = if (text.startsWith(FILE_HEADER)) text.removePrefix(FILE_HEADER) else text
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getJSONObject(it) }
    }

    private fun writeAtomically(target: AtomicFile, bytes: ByteArray) {
        var stream: FileOutputStream? = null
        try {
            stream = target.startWrite()
            stream.write(bytes)
            stream.fd.sync()
            target.finishWrite(stream)
        } catch (failure: Exception) {
            stream?.let(target::failWrite)
            throw IllegalStateException("This Atlas store could not be saved.", failure)
        }
    }

    fun add(o: JSONObject): JSONObject {
        if (!o.has("id")) o.put("id", System.currentTimeMillis().toString() + (0..999).random())
        save(all() + o); return o
    }
    fun update(id: String, change: (JSONObject) -> Unit) = save(all().map { if (it.optString("id") == id) it.also(change) else it })
    fun remove(id: String) = save(all().filter { it.optString("id") != id })

    companion object {
        private const val FILE_HEADER = "ATLAS_JSON_STORE_V1\n"
        private const val MAX_STORE_BYTES = 64 * 1024 * 1024
        private val PROCESS_LOCK = Any()
    }
}
