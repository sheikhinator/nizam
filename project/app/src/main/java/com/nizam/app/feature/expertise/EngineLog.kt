package com.nizam.app.feature.expertise

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDate

/**
 * Every model call Atlas makes, for the engine room (absorbed from LYCHO's router log and Master Panel).
 * The last 120 calls stay in memory; daily totals per provider are kept on the phone.
 */
object EngineLog {
    data class Call(val provider: String, val model: String, val ok: Boolean, val ms: Long, val error: String?, val at: Long = System.currentTimeMillis())

    private val _calls = MutableStateFlow<List<Call>>(emptyList())
    val calls: StateFlow<List<Call>> = _calls

    @Volatile var context: Context? = null

    fun record(provider: String, model: String, ok: Boolean, ms: Long, error: String?) {
        _calls.update { (listOf(Call(provider, model, ok, ms, error?.take(160))) + it).take(120) }
        val c = context ?: return
        runCatching {
            val p = c.getSharedPreferences("engine_log", 0)
            val day = LocalDate.now().toString()
            if (p.getString("day", "") != day) { archive(c); p.edit().clear().putString("day", day).apply() }
            val k = provider.replace('|', ' ')
            p.edit()
                .putInt("calls|$k", p.getInt("calls|$k", 0) + 1)
                .putInt("ok|$k", p.getInt("ok|$k", 0) + if (ok) 1 else 0)
                .putLong("ms|$k", p.getLong("ms|$k", 0) + if (ok) ms else 0)
                .apply()
        }
        if (!ok) Pulse.emit(Pulse.Kind.ALERT, "A model didn't answer", "$provider · ${model.substringAfterLast('/')}: ${error.orEmpty().take(80)}")
    }

    data class Day(val provider: String, val calls: Int, val ok: Int, val avgMs: Long)

    /** Yesterday's totals move into a 60-day history (for Analytics, Economy and the zero-cost report). */
    private fun archive(c: Context) {
        val p = c.getSharedPreferences("engine_log", 0)
        val day = p.getString("day", null) ?: return
        val st = com.nizam.app.feature.next.JsonStore(c, "engine_days")
        val rows = p.all.keys.filter { it.startsWith("calls|") }.map { it.removePrefix("calls|") }.map { k ->
            org.json.JSONObject().put("id", "$day|$k").put("day", day).put("provider", k).put("calls", p.getInt("calls|$k", 0))
                .put("ok", p.getInt("ok|$k", 0)).put("ms", p.getLong("ms|$k", 0))
        }
        if (rows.isNotEmpty()) st.save((st.all().filter { it.optString("day") != day } + rows).takeLast(60 * 8))
    }

    /** Daily history plus today, as (day, provider, calls, ok, avgMs). */
    fun history(c: Context): List<Pair<String, Day>> {
        val past = com.nizam.app.feature.next.JsonStore(c, "engine_days").all().map { o ->
            val ok = o.optInt("ok")
            o.optString("day") to Day(o.optString("provider"), o.optInt("calls"), ok, if (ok > 0) o.optLong("ms") / ok else 0)
        }
        return past + today(c).map { LocalDate.now().toString() to it }
    }

    fun today(c: Context): List<Day> {
        val p = c.getSharedPreferences("engine_log", 0)
        if (p.getString("day", "") != LocalDate.now().toString()) return emptyList()
        return p.all.keys.filter { it.startsWith("calls|") }.map { it.removePrefix("calls|") }.map { k ->
            val ok = p.getInt("ok|$k", 0)
            Day(k, p.getInt("calls|$k", 0), ok, if (ok > 0) p.getLong("ms|$k", 0) / ok else 0)
        }.sortedByDescending { it.calls }
    }
}
