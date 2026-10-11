package com.nizam.app.feature.planner

import android.content.Context
import com.nizam.app.AppContainer
import com.nizam.app.core.hourToClock
import com.nizam.app.feature.agent.Snapshot
import com.nizam.app.feature.prayer.PrayerTimes
import com.nizam.app.net.Ai
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

data class Block(val start: String, val end: String, val title: String, val kind: String)

/**
 * The auto-planner turns lists into a schedule: prayers are fixed anchors, work hours come from
 * your profile, and tasks and missions are fitted into the gaps — hardest work into your best hours.
 */
object Planner {
    private fun file(c: Context) = File(c.filesDir, "plan.json")

    fun load(c: Context): List<Block> = runCatching {
        val o = JSONObject(file(c).readText())
        if (o.optString("date") != LocalDate.now().toString()) return emptyList()
        val a = o.getJSONArray("blocks")
        (0 until a.length()).map { a.getJSONObject(it).let { b -> Block(b.optString("start"), b.optString("end"), b.optString("title"), b.optString("kind")) } }
    }.getOrElse { emptyList() }

    suspend fun plan(c: AppContainer, context: Context, note: String): List<Block> {
        val s = c.settingsRepository
        val t = PrayerTimes.calculate(LocalDate.now(), s.latitude.first(), s.longitude.first(),
            runCatching { PrayerTimes.Method.valueOf(s.prayerMethod.first()) }.getOrElse { PrayerTimes.Method.KARACHI },
            s.hanafiAsr.first())
        val prayers = listOf("Fajr" to t.fajr, "Dhuhr" to t.dhuhr, "Asr" to t.asr, "Maghrib" to t.maghrib, "Isha" to t.isha)
            .filterNot { it.second.isNaN() }.joinToString(", ") { "${it.first} ${hourToClock(it.second)}" }
        val now = LocalTime.now().withSecond(0).withNano(0)
        val raw = Ai.generate(
            settings = s,
            prompt = """
                ${Snapshot.of(c)}

                Plan the REST of today as time blocks, starting from $now and ending by 23:00.
                Fixed anchors — prayer times today: $prayers. Give each prayer a 15–20 minute block.
                Respect their routine and work hours from their profile if given.
                Fit in their open tasks and today's missions. Put demanding work (study, deep work) in
                morning or early-afternoon blocks, light tasks after Asr, and leave a wind-down before sleep.
                Include short breaks. Don't overfill — a plan with slack gets followed.
                ${if (note.isNotBlank()) "Their note for today: $note" else ""}

                Return ONLY JSON: {"blocks":[{"start":"HH:MM","end":"HH:MM","title":"...","kind":"prayer|work|study|task|break|gym|personal"}]}
            """.trimIndent(),
            system = "You are a practical scheduler. Realistic, humane plans. You return only valid JSON.",
            maxOutputTokens = 2500
        )
        val a = JSONObject(Ai.cleanJson(raw)).optJSONArray("blocks") ?: JSONArray()
        val blocks = (0 until a.length()).map { a.getJSONObject(it) }
            .map { Block(it.optString("start"), it.optString("end"), it.optString("title"), it.optString("kind")) }
            .filter { runCatching { LocalTime.parse(it.start); LocalTime.parse(it.end) }.isSuccess }
            .sortedBy { it.start }
        file(context).writeText(JSONObject().put("date", LocalDate.now().toString()).put("blocks", JSONArray().apply {
            blocks.forEach { put(JSONObject().put("start", it.start).put("end", it.end).put("title", it.title).put("kind", it.kind)) }
        }).toString())
        return blocks
    }
}
