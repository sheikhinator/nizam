package com.nizam.app.feature.mind

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private fun Context.json(name: String) = File(filesDir, name)
private fun File.obj() = runCatching { JSONObject(readText()) }.getOrElse { JSONObject() }
private fun File.arr() = runCatching { JSONArray(readText()) }.getOrElse { JSONArray() }

val WHEEL_AREAS = listOf("Faith", "Health", "Money", "Career", "Family", "Friends", "Growth", "Fun")

/** Life wheel: one rating per area per month. */
object Wheel {
    fun all(c: Context): Map<String, Map<String, Int>> = c.json("wheel.json").obj().let { o ->
        o.keys().asSequence().associateWith { m -> o.getJSONObject(m).let { r -> WHEEL_AREAS.associateWith { r.optInt(it, 0) } } }
    }
    fun save(c: Context, month: String, scores: Map<String, Int>) {
        val f = c.json("wheel.json"); val o = f.obj()
        o.put(month, JSONObject().apply { scores.forEach { (k, v) -> put(k, v) } }); f.writeText(o.toString())
    }
}

/** Core values. Every AI in Atlas reads these and checks decisions against them. */
object Values {
    fun all(c: Context): List<Pair<String, String>> = c.json("values.json").arr().let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("name") to it.optString("why") } }
    fun save(c: Context, list: List<Pair<String, String>>) = c.json("values.json").writeText(
        JSONArray().apply { list.forEach { put(JSONObject().put("name", it.first).put("why", it.second)) } }.toString())
    fun forAi(c: Context): String = all(c).takeIf { it.isNotEmpty() }?.let { l ->
        "Their core values (check advice and decisions against these; name any conflict plainly): " +
            l.joinToString("; ") { if (it.second.isBlank()) it.first else "${it.first} — ${it.second}" }
    }.orEmpty()
}

data class Idea(val id: Long, val title: String, val note: String, val stage: String, val research: String)
val IDEA_STAGES = listOf("Seed", "Researched", "Testing", "Go", "Parked", "Dropped")

object Ideas {
    fun all(c: Context): List<Idea> = c.json("ideas.json").arr().let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
        Idea(it.optLong("id"), it.optString("title"), it.optString("note"), it.optString("stage", "Seed"), it.optString("research")) } }
    fun save(c: Context, l: List<Idea>) = c.json("ideas.json").writeText(JSONArray().apply { l.forEach {
        put(JSONObject().put("id", it.id).put("title", it.title).put("note", it.note).put("stage", it.stage).put("research", it.research)) } }.toString())
}
