package com.nizam.app.feature.chief

import android.content.Context
import java.io.File
import java.util.zip.ZipInputStream

/**
 * A local copy of the app's source that Chief can read, search and edit. Nothing leaves the phone until the
 * user approves; then only the changed files are pushed.
 */
class Workspace(private val root: File) {
    class Change(val path: String, val original: String?, var content: String)

    val changes = linkedMapOf<String, Change>()
    private val TEXT = setOf("kt", "kts", "xml", "md", "txt", "json", "properties", "gradle", "pro", "yml", "yaml", "toml")

    fun ready() = File(root, "settings.gradle.kts").exists()

    private fun file(rel: String): File {
        val clean = rel.trim().trimStart('/').replace("\\", "/")
        require(!clean.split('/').any { it == ".." }) { "Path outside the project." }
        return File(root, clean)
    }

    fun isProtected(rel: String): Boolean {
        val r = rel.lowercase()
        return r.contains(".github/") || r.contains("keystore") || r.endsWith(".jks") || r.contains("local.properties") || r.contains("secret")
    }

    fun list(rel: String): String {
        val dir = file(rel.ifBlank { "." })
        if (!dir.isDirectory) return "Not a folder: $rel"
        val names = dir.listFiles().orEmpty().sortedWith(compareBy({ !it.isDirectory }, { it.name })).take(200)
            .joinToString("\n") { it.name + if (it.isDirectory) "/" else "" }
        return names.ifBlank { "(empty)" }
    }

    fun read(rel: String, from: Int?, to: Int?): String {
        val f = file(rel)
        if (!f.isFile) return "File not found: $rel"
        val text = changes[normal(rel)]?.content ?: f.readText()
        val lines = text.lines()
        val a = ((from ?: 1) - 1).coerceIn(0, lines.size)
        val b = (to ?: (a + 220)).coerceIn(a, minOf(lines.size, a + 260))
        val out = StringBuilder("$rel (lines ${a + 1}-$b of ${lines.size})\n")
        for (i in a until b) out.append(i + 1).append('\t').append(lines[i].take(220)).append('\n')
        return out.toString().take(14_000)
    }

    fun search(pattern: String, rel: String?): String {
        val re = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrElse { Regex(Regex.escape(pattern), RegexOption.IGNORE_CASE) }
        val base = file(rel?.ifBlank { null } ?: ".")
        val out = ArrayList<String>()
        var scanned = 0
        base.walkTopDown().onEnter { it.name != "build" && it.name != ".git" }.filter { it.isFile && it.extension in TEXT }.forEach { f ->
            if (out.size >= 40 || scanned > 4000) return@forEach
            scanned++
            val path = f.relativeTo(root).path.replace('\\', '/')
            val text = changes[path]?.content ?: runCatching { f.readText() }.getOrNull() ?: return@forEach
            text.lineSequence().forEachIndexed { i, line ->
                if (out.size < 40 && re.containsMatchIn(line)) out += "$path:${i + 1}: ${line.trim().take(160)}"
            }
        }
        return if (out.isEmpty()) "No matches." else out.joinToString("\n")
    }

    private fun normal(rel: String) = rel.trim().trimStart('/').replace("\\", "/")

    fun edit(rel: String, old: String, new: String): String {
        val path = normal(rel)
        if (isProtected(path)) return "That file is protected and cannot be changed."
        val f = file(path)
        if (!f.isFile) return "File not found: $path"
        val current = changes[path]?.content ?: f.readText()
        if (old.isEmpty()) return "The text to replace must not be empty."
        val count = current.split(old).size - 1
        if (count == 0) return "The text to replace was not found. Read the file again and copy the exact lines."
        if (count > 1) return "That text appears $count times. Include more surrounding lines so it is unique."
        val updated = current.replace(old, new)
        val ch = changes[path]
        if (ch == null) changes[path] = Change(path, current, updated) else ch.content = updated
        return "Edited $path."
    }

    fun create(rel: String, content: String): String {
        val path = normal(rel)
        if (isProtected(path)) return "That file is protected and cannot be changed."
        val f = file(path)
        val original = if (f.isFile) (changes[path]?.content ?: f.readText()) else null
        val ch = changes[path]
        if (ch == null) changes[path] = Change(path, original, content) else ch.content = content
        return if (original == null) "Created $path." else "Rewrote $path."
    }

    /** What would be pushed: repo path -> bytes. */
    fun pushable(): Map<String, ByteArray> = changes.values.associate { "project/${it.path}" to it.content.toByteArray(Charsets.UTF_8) }

    companion object {
        fun dir(c: Context) = File(c.cacheDir, "chief/ws")

        /** Unpacks the downloaded repo zip, keeping only the project's text files. */
        fun unpack(zip: File, into: File) {
            into.deleteRecursively(); into.mkdirs()
            val keep = setOf("kt", "kts", "xml", "md", "txt", "json", "properties", "gradle", "pro", "yml", "yaml", "toml")
            ZipInputStream(zip.inputStream().buffered()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    val name = e.name.substringAfter('/', "")
                    if (!e.isDirectory && name.startsWith("project/")) {
                        val rel = name.removePrefix("project/")
                        if (rel.substringAfterLast('.', "") in keep && !rel.split('/').contains("build")) {
                            val out = File(into, rel)
                            if (out.canonicalPath.startsWith(into.canonicalPath)) { out.parentFile?.mkdirs(); out.outputStream().use { zin.copyTo(it) } }
                        }
                    }
                    e = zin.nextEntry
                }
            }
        }
    }
}
