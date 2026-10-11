package com.nizam.app.feature.attach

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import com.nizam.app.data.prefs.SettingsRepository
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile

/** A file the user attached to a message. Always a private copy in the cache, never the original. */
data class Attachment(val name: String, val mime: String, val file: File, val size: Long) {
    val isImage get() = mime.startsWith("image/")
    val isPdf get() = mime == "application/pdf" || name.endsWith(".pdf", true)
    val isOffice get() = OFFICE.any { name.endsWith(it, true) }
    val isText get() = mime.startsWith("text/") || TEXT_EXT.any { name.endsWith(it, true) } ||
        mime in setOf("application/json", "application/xml", "application/javascript", "application/x-yaml")

    fun sizeLabel(): String = when {
        size >= 1_048_576 -> String.format("%.1f MB", size / 1_048_576.0)
        size >= 1024 -> "${size / 1024} KB"
        else -> "$size B"
    }

    companion object {
        private val OFFICE = listOf(".docx", ".pptx", ".xlsx")
        private val TEXT_EXT = listOf(
            ".txt", ".md", ".csv", ".tsv", ".json", ".xml", ".yaml", ".yml", ".log", ".html", ".htm", ".kt", ".java",
            ".py", ".js", ".ts", ".tsx", ".jsx", ".c", ".cpp", ".h", ".cs", ".go", ".rs", ".swift", ".rb", ".php",
            ".sql", ".sh", ".gradle", ".kts", ".ini", ".toml", ".srt", ".vtt", ".css"
        )
    }
}

/**
 * Turning attachments into something every provider understands.
 *
 *  - images   → native image input on Gemini and OpenAI-compatible providers
 *  - PDFs     → native document input on Gemini; page images on OpenAI-compatible providers
 *  - text/code, Word, PowerPoint, Excel → extracted text, which works on every model
 */
object Attachments {
    private const val MAX_BYTES = 20L * 1024 * 1024
    private const val MAX_TEXT = 60_000
    private const val PDF_PAGES_AS_IMAGES = 6

    private fun dir(c: Context) = File(c.cacheDir, "attach").apply { mkdirs() }

    /** Copies a picked file into the cache. Big photos are shrunk on the way in. */
    fun fromUri(c: Context, uri: Uri): Attachment? = runCatching {
        val cr = c.contentResolver
        var name = "file"
        var size = -1L
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cur ->
            if (cur.moveToFirst()) {
                cur.getString(0)?.let { name = it }
                if (!cur.isNull(1)) size = cur.getLong(1)
            }
        }
        if (size > MAX_BYTES) throw IllegalArgumentException("$name is over 20 MB")
        val mime = cr.getType(uri) ?: guessMime(name)
        if (mime.startsWith("image/") && !mime.contains("gif")) {
            val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@runCatching null
            return@runCatching fromBitmap(c, bmp, name.substringBeforeLast('.') + ".jpg")
        }
        val out = File(dir(c), "${System.currentTimeMillis()}_${name.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
        cr.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: return@runCatching null
        if (out.length() > MAX_BYTES) { out.delete(); throw IllegalArgumentException("$name is over 20 MB") }
        Attachment(name, mime, out, out.length())
    }.getOrNull()

    fun fromBitmap(c: Context, bmp: Bitmap, name: String = "photo_${System.currentTimeMillis()}.jpg"): Attachment {
        val scale = 1600f / maxOf(bmp.width, bmp.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = File(dir(c), "${System.currentTimeMillis()}_$name")
        out.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return Attachment(name, "image/jpeg", out, out.length())
    }

    private fun guessMime(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "pdf" -> "application/pdf"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        "txt", "md", "csv", "log" -> "text/plain"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }

    /** One line naming the files, stored with the message so the thread shows what was sent. */
    fun label(list: List<Attachment>): String = if (list.isEmpty()) "" else "📎 " + list.joinToString(" · ") { it.name }

    // ── text extraction ──────────────────────────────────────────────────────────────────────────

    fun textOf(a: Attachment): String? = runCatching {
        when {
            a.isText -> a.file.readText().take(MAX_TEXT)
            a.isOffice -> officeText(a.file).take(MAX_TEXT)
            else -> null
        }
    }.getOrNull()

    /** Word, PowerPoint and Excel files are zips of XML; the words live in a handful of tags. */
    private fun officeText(f: File): String = ZipFile(f).use { zip ->
        val entries = zip.entries().toList()
            .filter { e ->
                e.name == "word/document.xml" || e.name == "xl/sharedStrings.xml" ||
                    (e.name.startsWith("ppt/slides/slide") && e.name.endsWith(".xml"))
            }
            .sortedBy { e -> Regex("\\d+").find(e.name)?.value?.toIntOrNull() ?: 0 }
        entries.joinToString("\n\n") { e ->
            val xml = zip.getInputStream(e).bufferedReader().use { it.readText() }
            xml.replace(Regex("</w:p>|</a:p>|</si>"), "\n")
                .let { Regex("<(?:w:t|a:t|t)(?:\\s[^>]*)?>([^<]*)</(?:w:t|a:t|t)>").findAll(it) }
                .joinToString("") { m -> m.groupValues[1] + " " }
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        }
    }

    private fun textBlock(list: List<Attachment>): String = list.mapNotNull { a ->
        textOf(a)?.let { "── ${a.name} ──\n$it" }
    }.joinToString("\n\n")

    private fun b64(f: File) = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)

    private fun bitmapB64(bmp: Bitmap): String {
        val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** PDF pages as images, for providers without native PDF input. */
    private fun pdfPages(f: File, max: Int = PDF_PAGES_AS_IMAGES): List<String> = runCatching {
        ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { r ->
                (0 until minOf(r.pageCount, max)).map { i ->
                    r.openPage(i).use { page ->
                        val scale = 1400f / maxOf(page.width, page.height)
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bitmapB64(bmp)
                    }
                }
            }
        }
    }.getOrElse { emptyList() }

    // ── provider payloads ────────────────────────────────────────────────────────────────────────

    /** The prompt with any extracted text appended — what a text-only path sees. */
    fun promptWithText(prompt: String, list: List<Attachment>): String {
        val text = textBlock(list)
        return if (text.isBlank()) prompt else "$prompt\n\nAttached files:\n$text"
    }

    /** Gemini `parts`: text first, then native image and PDF data. */
    fun geminiParts(prompt: String, list: List<Attachment>): JSONArray {
        val parts = JSONArray().put(JSONObject().put("text", promptWithText(prompt, list)))
        list.forEach { a ->
            when {
                a.isImage -> parts.put(JSONObject().put("inline_data",
                    JSONObject().put("mime_type", a.mime).put("data", b64(a.file))))
                a.isPdf -> parts.put(JSONObject().put("inline_data",
                    JSONObject().put("mime_type", "application/pdf").put("data", b64(a.file))))
            }
        }
        return parts
    }

    /** OpenAI-style `content` array: text, then images (PDFs rendered page by page). */
    fun openAiContent(prompt: String, list: List<Attachment>): JSONArray {
        val content = JSONArray().put(JSONObject().put("type", "text").put("text", promptWithText(prompt, list)))
        fun image(data: String, mime: String = "image/jpeg") = content.put(JSONObject().put("type", "image_url")
            .put("image_url", JSONObject().put("url", "data:$mime;base64,$data")))
        list.forEach { a ->
            when {
                a.isImage -> image(b64(a.file), a.mime)
                a.isPdf -> pdfPages(a.file).forEach { image(it) }
            }
        }
        return content
    }

    fun needsVision(list: List<Attachment>) = list.any { it.isImage || it.isPdf }

    /**
     * For the agent path, which is text-only: have a vision-capable call read the files first and
     * hand the agent notes. Text files skip this — they're inlined as they are.
     */
    suspend fun digest(settings: SettingsRepository, list: List<Attachment>, question: String): String {
        if (list.isEmpty()) return ""
        val text = textBlock(list)
        val visual = list.filter { it.isImage || it.isPdf }
        val notes = if (visual.isEmpty()) "" else runCatching {
            com.nizam.app.net.Streaming.reply(
                settings,
                prompt = "The user attached these files and said: \"${question.ifBlank { "(no message)" }}\".\n" +
                    "Read them carefully. Transcribe all text, pull out numbers, dates, tables and names, and describe " +
                    "anything visual that matters. Write notes another assistant will act on — don't just summarise.",
                system = "You read documents and images precisely.",
                maxTokens = 1800,
                attachments = visual
            ) { }
        }.getOrElse { "(Couldn't read ${visual.joinToString { it.name }}: ${it.message?.take(120)} — " +
            "pick a model that accepts images and PDFs.)" }
        return buildString {
            if (notes.isNotBlank()) append("What the attached files contain:\n").append(notes)
            if (text.isNotBlank()) { if (isNotEmpty()) append("\n\n"); append("Attached files:\n").append(text) }
        }
    }
}
