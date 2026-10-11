package com.nizam.app.feature.deen

import android.content.Context
import com.nizam.app.net.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Hisn al-Muslim (Fortress of the Muslim), fetched from hisnmuslim.com's own published data and
 * kept on the phone. Dua text is NEVER written by a model — like the Quran and hadith modules,
 * Atlas only ever shows it verbatim from the source.
 */
data class Dhikr(val id: String, val arabic: String, val translation: String, val note: String, val repeat: Int, val audio: String)
data class DuaChapter(val id: Int, val title: String, val audio: String, val items: List<Dhikr>)

object Duas {
    private val SOURCES = listOf(
        "https://www.hisnmuslim.com/api/en/husn_en.json",
        "http://www.hisnmuslim.com/api/en/husn_en.json",
        // a public mirror of the same file, used only if the site is unreachable
        "https://raw.githubusercontent.com/wafaaelmaandy/Hisn-Muslim-Json/master/husn_en.json"
    )
    private fun f(c: Context) = File(c.filesDir, "hisn_en.json")
    @Volatile private var cache: List<DuaChapter>? = null

    fun cached(c: Context): List<DuaChapter> = cache ?: runCatching { parse(f(c).readText()) }.getOrElse { emptyList() }.also {
        if (it.isNotEmpty()) cache = it
    }

    /** Downloads once, then works offline. */
    suspend fun load(c: Context): List<DuaChapter> {
        cached(c).takeIf { it.isNotEmpty() }?.let { return it }
        var last: Throwable? = null
        for (url in SOURCES) {
            val raw = runCatching { Http.getText(url) }.onFailure { last = it }.getOrNull() ?: continue
            val list = runCatching { parse(raw) }.getOrNull() ?: continue
            if (list.isNotEmpty()) { f(c).writeText(raw); cache = list; return list }
        }
        throw IllegalStateException("Couldn't download the duas: ${last?.message?.take(120) ?: "no connection"}")
    }

    private fun parse(raw: String): List<DuaChapter> {
        val arr = JSONObject(raw.trimStart('﻿')).optJSONArray("English") ?: JSONArray()
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { ch ->
            val t = ch.optJSONArray("TEXT") ?: JSONArray()
            DuaChapter(ch.optInt("ID"), ch.optString("TITLE").trim(), ch.optString("AUDIO_URL"),
                (0 until t.length()).map { t.getJSONObject(it) }.map { d ->
                    Dhikr(d.optString("ID"), d.optString("ARABIC_TEXT").trim(), d.optString("TRANSLATED_TEXT").trim(),
                        d.optString("LANGUAGE_ARABIC_TRANSLATED_TEXT").trim(), d.optString("REPEAT").toIntOrNull()?.coerceAtLeast(1) ?: 1,
                        d.optString("AUDIO"))
                })
        }
    }

    /** Chapters that fit this moment — morning adhkar after Fajr, evening after Asr, sleep at night. */
    fun forNow(list: List<DuaChapter>, hour: Int = java.time.LocalTime.now().hour): List<DuaChapter> {
        val want = when (hour) {
            in 4..10 -> listOf("morning and evening", "wake up", "leaving the home")
            in 15..19 -> listOf("morning and evening", "entering the home")
            in 21..23, in 0..3 -> listOf("before sleeping", "stir in the night", "afraid to go to sleep")
            else -> listOf("worry and grief", "after completing the prayer", "leaving the home")
        }
        return want.mapNotNull { w -> list.firstOrNull { it.title.contains(w, ignoreCase = true) } }.distinct()
    }

    /** For the AI: chapter titles only, so it can point you to the right one without ever quoting text. */
    fun titlesForAi(c: Context) = cached(c).joinToString("; ") { "${it.id}: ${it.title}" }

    // ── tasbih counts, kept per day ──
    private fun p(c: Context) = c.getSharedPreferences("dhikr", 0)
    fun addCount(c: Context, phrase: String, n: Int = 1) {
        val key = "${java.time.LocalDate.now()}|$phrase"
        p(c).edit().putInt(key, p(c).getInt(key, 0) + n).putInt("total|$phrase", p(c).getInt("total|$phrase", 0) + n).apply()
    }
    fun today(c: Context, phrase: String) = p(c).getInt("${java.time.LocalDate.now()}|$phrase", 0)
    fun total(c: Context, phrase: String) = p(c).getInt("total|$phrase", 0)
}
