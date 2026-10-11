package com.nizam.app.feature.brain

import android.content.Context
import android.net.Uri
import com.nizam.app.feature.attach.Attachments
import com.nizam.app.feature.next.JsonStore
import org.json.JSONObject
import java.time.LocalDate

data class SourceDocument(
    val id: String,
    val title: String,
    val date: String,
    val text: String
)

data class SourceShelf(
    val documents: List<SourceDocument>,
    val error: String? = null
)

object SourceDocuments {
    private const val MAX_DOCUMENTS = 50
    private const val MAX_SOURCE_CHARS = 20_000

    private fun store(context: Context) = JsonStore(context, "brain_sources", sealed = true)

    fun load(context: Context): SourceShelf {
        val sourceStore = store(context)
        val documents = sourceStore.all()
            .map { record ->
                SourceDocument(
                    id = record.optString("id"),
                    title = record.optString("title"),
                    date = record.optString("date"),
                    text = record.optString("text")
                )
            }
            .sortedByDescending { it.date }
        return SourceShelf(documents, sourceStore.lastReadError)
    }

    fun all(context: Context): List<SourceDocument> = load(context).documents

    fun importFile(context: Context, uri: Uri): SourceDocument {
        val sourceStore = store(context)
        val records = sourceStore.all()
        sourceStore.lastReadError?.let {
            throw IllegalStateException("Saved sources could not be read. Recover them before importing more.")
        }
        require(records.size < MAX_DOCUMENTS) { "The source shelf is full. Remove a source before adding another." }

        val attachment = Attachments.fromUri(context, uri)
            ?: throw IllegalArgumentException("Atlas could not read this file. Choose a text-based file under 20 MB.")
        try {
            require(attachment.isText) {
                "This source type is not text-searchable. Choose a text, Markdown, CSV, JSON, or similar text file."
            }
            val text = Attachments.textOf(attachment)?.trim().orEmpty()
            require(text.isNotBlank()) { "This file did not contain readable text." }
            require(text.length <= MAX_SOURCE_CHARS) {
                "This file has more than 20,000 extracted characters. Split it into smaller sources."
            }
            val saved = sourceStore.add(
                JSONObject()
                    .put("title", attachment.name.take(160))
                    .put("date", LocalDate.now().toString())
                    .put("text", text)
            )
            return SourceDocument(
                id = saved.optString("id"),
                title = saved.optString("title"),
                date = saved.optString("date"),
                text = saved.optString("text")
            )
        } finally {
            attachment.file.delete()
        }
    }

    fun remove(context: Context, id: String): Boolean {
        val sourceStore = store(context)
        val records = sourceStore.all()
        sourceStore.lastReadError?.let {
            throw IllegalStateException("Saved sources could not be read. Recover them before removing anything.")
        }
        if (records.none { it.optString("id") == id }) return false
        sourceStore.remove(id)
        return true
    }
}