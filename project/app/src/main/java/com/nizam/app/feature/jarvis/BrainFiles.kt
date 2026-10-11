package com.nizam.app.feature.jarvis

import android.content.Context
import com.nizam.app.feature.attach.Attachment
import com.nizam.app.feature.attach.Attachments
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Files you've shared with Atlas, remembered as text: the words of a document, or what the AI
 * read from a photo or PDF. So "what did that PDF say about my visa?" works a month later.
 */
object BrainFiles {
    private fun f(c: Context) = File(c.filesDir, "brain_files.json")

    fun all(c: Context): List<Triple<String, String, String>> = runCatching { JSONArray(f(c).readText()) }.getOrElse { JSONArray() }
        .let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { Triple(it.optString("name"), it.optString("date"), it.optString("text")) } }

    /** [readBack] is what the AI said about the files — used when a file has no extractable text. */
    @Synchronized
    fun remember(c: Context, files: List<Attachment>, readBack: String) {
        if (files.isEmpty()) return
        val a = runCatching { JSONArray(f(c).readText()) }.getOrElse { JSONArray() }
        files.forEach { file ->
            val text = Attachments.textOf(file)?.take(8000) ?: readBack.take(4000)
            if (text.isNotBlank()) a.put(JSONObject().put("name", file.name).put("date", LocalDate.now().toString()).put("text", text))
        }
        // keep the newest 300
        val trimmed = JSONArray().apply { (maxOf(0, a.length() - 300) until a.length()).forEach { put(a.get(it)) } }
        f(c).writeText(trimmed.toString())
    }
}
