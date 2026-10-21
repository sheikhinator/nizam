package com.nizam.app.feature.expertise

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.update

/**
 * Atlas Pulse: one event stream for the whole app. Anything that changes (a log, an answer, a background
 * job, a choice of expertise) is announced once here; screens that show it listen and redraw together, so
 * no two screens ever disagree. The LYCHO orb in the dock is its visible heartbeat.
 */
object Pulse {
    enum class Kind { THINKING, WORKING, DONE, CHANGED, ALERT }

    data class Event(val kind: Kind, val title: String, val detail: String = "", val hub: String? = null, val at: Long = System.currentTimeMillis())

    private val _events = MutableSharedFlow<Event>(replay = 0, extraBufferCapacity = 64)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private val _recent = MutableStateFlow<List<Event>>(emptyList())
    /** The last 60 events, newest first, for the Pulse screen. */
    val recent: StateFlow<List<Event>> = _recent

    fun emit(kind: Kind, title: String, detail: String = "", hub: String? = null) {
        val e = Event(kind, title, detail, hub)
        _events.tryEmit(e)
        _recent.update { (listOf(e) + it).take(60) }
    }
}
