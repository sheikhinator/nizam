package com.nizam.app.core.spine

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Reactor — the part that makes Atlas feel alive.
 *
 * It listens to [Pulse] and lets registered observers decide whether Atlas should notice, remember,
 * or act. For now it only fans signals out to observers (and keeps the organism's heartbeat going);
 * the judgement layer — consult [Self], apply the owner's AutoRules, act within a trust budget and
 * leave a receipt — plugs in here as observers, so aliveness grows without this class changing.
 *
 * Observers are isolated: one that throws never stops the others or the stream.
 */
class Reactor(@Suppress("unused") private val appContext: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var started = false
    private val observers = ArrayList<suspend (Signal) -> Unit>()

    /** Register a reaction. Called before [start]; safe to add more later. */
    @Synchronized
    fun onSignal(observer: suspend (Signal) -> Unit) { observers.add(observer) }

    @Synchronized
    private fun observersSnapshot(): List<suspend (Signal) -> Unit> = observers.toList()

    /** Begin listening. Idempotent. */
    fun start() {
        synchronized(this) { if (started) return; started = true }
        scope.launch {
            Pulse.events.collect { signal ->
                for (observer in observersSnapshot()) {
                    runCatching { observer(signal) }
                }
            }
        }
    }
}
