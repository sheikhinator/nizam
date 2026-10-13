package com.nizam.app.core.spine

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Pulse — the one place everything in Atlas broadcasts to.
 *
 * Every feature emits a [Signal] when something happens ("expense logged", "prayer entered",
 * "arrived home", "mood set"). Nothing is an island after this: any other part of the app, the
 * agent, the Council, the Reactor — can listen. This is the first nerve of the organism.
 *
 * It is a process-wide object on purpose, so a feature can emit with one line and no plumbing,
 * the same way Atlas already uses `SkillsPro`, `AutoRules` and friends. Emission never blocks and
 * never throws: a dropped signal is better than a janky screen.
 */
data class Signal(
    val domain: String,
    val kind: String,
    val summary: String = "",
    val data: JSONObject = JSONObject(),
    val at: Long = System.currentTimeMillis(),
)

object Pulse {
    // extraBufferCapacity lets tryEmit succeed without a collector present; replay = 0 keeps it live.
    private val _events = MutableSharedFlow<Signal>(replay = 0, extraBufferCapacity = 256)
    val events: SharedFlow<Signal> = _events.asSharedFlow()

    private const val MAX_RECENT = 200
    private val recent = ConcurrentLinkedDeque<Signal>()

    /** Broadcast one signal. Safe to call from any thread, any screen. */
    fun emit(signal: Signal) {
        recent.addLast(signal)
        while (recent.size > MAX_RECENT) recent.pollFirst()
        _events.tryEmit(signal)
    }

    /** Convenience: emit without constructing a [Signal] first. */
    fun emit(domain: String, kind: String, summary: String = "", data: JSONObject = JSONObject()) =
        emit(Signal(domain, kind, summary, data))

    /** The last [limit] signals across all domains, oldest → newest. */
    fun recent(limit: Int = 50): List<Signal> =
        recent.toList().let { if (it.size <= limit) it else it.subList(it.size - limit, it.size) }

    /** The last [limit] signals from one domain, oldest → newest. */
    fun recentIn(domain: String, limit: Int = 50): List<Signal> =
        recent.toList().filter { it.domain == domain }.let { if (it.size <= limit) it else it.subList(it.size - limit, it.size) }
}
