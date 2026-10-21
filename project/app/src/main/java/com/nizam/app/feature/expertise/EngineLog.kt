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
            if (p.getString("day", "") != day) p.edit().clear().putString("day", day).apply()
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

    fun today(c: Context): List<Day> {
        val p = c.getSharedPreferences("engine_log", 0)
        if (p.getString("day", "") != LocalDate.now().toString()) return emptyList()
        return p.all.keys.filter { it.startsWith("calls|") }.map { it.removePrefix("calls|") }.map { k ->
            val ok = p.getInt("ok|$k", 0)
            Day(k, p.getInt("calls|$k", 0), ok, if (ok > 0) p.getLong("ms|$k", 0) / ok else 0)
        }.sortedByDescending { it.calls }
    }
}
