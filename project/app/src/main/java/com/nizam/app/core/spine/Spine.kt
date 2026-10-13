package com.nizam.app.core.spine

import android.content.Context

/**
 * Spine — the nervous system of Atlas.
 *
 * Four organs, one body:
 *  - [Pulse]   every feature broadcasts what happens
 *  - [Self]    one shared, live sense of the owner's situation
 *  - [World]   an on-device knowledge graph Atlas reasons across
 *  - [Reactor] turns signals into Atlas noticing, remembering, acting
 *
 * This object is only the wiring: start it once from the Application, then features plug in by
 * emitting to Pulse, contributing to Self, writing to World, and (later) reacting through Reactor.
 * Everything here is additive — it changes nothing that already works.
 */
object Spine {
    @Volatile private var reactor: Reactor? = null

    /** The running Reactor, or null before [start]. */
    fun reactor(): Reactor? = reactor

    /** Bring the organism to life. Idempotent; safe to call from Application.onCreate. */
    fun start(context: Context) {
        synchronized(this) {
            if (reactor != null) return
            reactor = Reactor(context.applicationContext)
        }
        // Clock is always part of the sense of self, with no organ to depend on.
        Self.contribute("clock") {
            java.time.LocalTime.now().let { t ->
                when (t.hour) {
                    in 5..11 -> "morning"; in 12..15 -> "afternoon"
                    in 16..18 -> "evening"; else -> "night"
                }
            }
        }
        reactor?.start()
        // The organism's first reflex: it knows it woke.
        Pulse.emit("system", "awake", "Atlas spine started")
    }
}
