package com.nizam.app.feature.bots

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Bots, after Rakazo's model: each bot is one persistent thread with its own brief, memory and
 * history. You don't start conversations — you return to them.
 *
 * The one thing not carried over is the Linux sandbox; on a phone a bot's "computer" is Atlas
 * itself: its actions, the browser, phone control and whatever MCP servers you've connected.
 */
data class Bot(val id: Long, val name: String, val brief: String, val emoji: String, val lastUsed: Long,
               val server: String = "")     // bound to one MCP server, or empty for all of Atlas
data class Turn(val fromUser: Boolean, val text: String, val at: Long)

object Bots {
    private fun f(c: Context) = File(c.filesDir, "bots.json")
    private fun thread(c: Context, id: Long) = File(c.filesDir, "bot_$id.json")

    fun all(c: Context): List<Bot> = runCatching { JSONArray(f(c).readText()) }.getOrElse { JSONArray() }
        .let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Bot(o.optLong("id"), o.optString("name"), o.optString("brief"), o.optString("emoji", "✦"),
                o.optLong("lastUsed"), o.optString("server")) } }
        .sortedByDescending { it.lastUsed }

    fun save(c: Context, list: List<Bot>) = f(c).writeText(JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("brief", it.brief)
            .put("emoji", it.emoji).put("lastUsed", it.lastUsed).put("server", it.server)) } }.toString())

    fun touch(c: Context, id: Long) = save(c, all(c).map { if (it.id == id) it.copy(lastUsed = System.currentTimeMillis()) else it })

    fun turns(c: Context, id: Long): List<Turn> = runCatching { JSONArray(thread(c, id).readText()) }.getOrElse { JSONArray() }
        .let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map {
            Turn(it.optBoolean("me"), it.optString("t"), it.optLong("at")) } }

    fun addTurn(c: Context, id: Long, fromUser: Boolean, text: String) {
        val a = runCatching { JSONArray(thread(c, id).readText()) }.getOrElse { JSONArray() }
        a.put(JSONObject().put("me", fromUser).put("t", text).put("at", System.currentTimeMillis()))
        thread(c, id).writeText(a.toString())
        touch(c, id)
    }

    fun clear(c: Context, id: Long) = thread(c, id).writeText("[]")
    fun delete(c: Context, id: Long) { thread(c, id).delete(); save(c, all(c).filterNot { it.id == id }) }

    /** A bot's own history, as context for its next reply. */
    fun update(c: Context, bot: Bot) = save(c, all(c).map { if (it.id == bot.id) bot else it })

    fun contextFor(c: Context, bot: Bot): String {
        val recent = turns(c, bot.id).takeLast(14)
        val pack = runCatching { com.nizam.app.feature.nx.AiLab.pack(c, bot.id) }.getOrDefault("")
        return "You are \"${bot.name}\". ${bot.brief}\n" + (if (pack.isBlank()) "" else "Reference pack (always true for this bot):\n$pack\n") +
            (if (recent.isEmpty()) "This thread is new." else
                "This thread so far:\n" + recent.joinToString("\n") { (if (it.fromUser) "Them: " else "You: ") + it.text })
    }
}
