package com.nizam.app.feature.dynamic

import org.json.JSONArray
import java.util.Locale

object StructuredFieldValues {
    fun parseMultiSelect(value: String): List<String>? = runCatching {
        val array = JSONArray(value)
        val items = (0 until array.length()).map { array.opt(it) }
        require(items.all { it is String }) { "Multi-select values must be strings." }
        items.map { it as String }.filter { it.isNotBlank() }
    }.getOrNull()

    fun decodeMultiSelect(value: String): List<String> = parseMultiSelect(value).orEmpty()

    fun encodeMultiSelect(values: Iterable<String>): String =
        JSONArray().apply { values.forEach { put(it) } }.toString()

    fun parseTags(value: String): List<String> =
        value.split(';').map(String::trim).filter(String::isNotBlank)

    fun hasDuplicateTags(value: String): Boolean {
        val tags = parseTags(value).map { it.lowercase(Locale.ROOT) }
        return tags.distinct().size != tags.size
    }
}