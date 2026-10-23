successfully downloaded text file (SHA: c42264198a067ef3946fb4d8ca2f66fd8a16f419)
package com.nizam.app.feature.atlascore

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalTime

enum class AtlasMood { CALM, FOCUSED, CURIOUS, PROTECTIVE, PLAYFUL, QUIET, WARM }
enum class AtlasEnergy { LOW, RESTING, IDLE, ACTIVE, ENGAGED }
enum class TimeOfDay { DAWN, MORNING, AFTERNOON, GOLDEN, EVENING, NIGHT, LATE_NIGHT }

/**
 * One small, observable picture of how Atlas is doing right now.
 * Everything that wants to feel alive can read this instead of inventing its own clock.
 */
data class AtlasState(
    val heartbeat: Long = System.currentTimeMillis(),
    val mood: AtlasMood = AtlasMood.CALM,
    val energy: AtlasEnergy = AtlasEnergy.IDLE,
    val timeOfDay: TimeOfDay = TimeOfDay.AFTERNOON,
    val thinking: Boolean = false,
    val listening: Boolean = false,
    val whispering: Boolean = false,
    val unprompted: Boolean = false,
    val solitude: Boolean = false,
    val trust: Float = 0.3f,
    val focus: String? = null
)

/**
 * AtlasCore - the quiet nervous system.
 *
 * It runs one cheap loop (a heartbeat every twenty seconds, not every frame), keeps the
 * hour of the day, and lets any screen ask "how is he?" without a service, a wake lock or
 * a network call. Everything here is local and dies with the process.
 */
object AtlasCore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow(AtlasState(timeOfDay = dayPart()))
    val state: StateFlow<AtlasState> = _state.asStateFlow()

    @Volatile private var started = false
    private var prefs: SharedPreferences? = null

    fun initialize(context: Context) {
        if (started) return
        started = true
        val p = context.applicationContext.getSharedPreferences("atlas_core", Context.MODE_PRIVATE)
        prefs = p
        _state.value = _state.value.copy(trust = p.getFloat("trust", 0.3f).coerceIn(0f, 1f))
        scope.launch {
            while (isActive) {
                val part = dayPart()
                val cur = _state.value
                _state.value = cur.copy(
                    heartbeat = System.currentTimeMillis(),
                    timeOfDay = part,
                    mood = moodFor(part),
                    energy = energyFor(part)
                )
                delay(20000)
            }
        }
    }

    fun thinking(on: Boolean) {
        _state.value = _state.value.copy(thinking = on)
    }

    fun listening(on: Boolean) {
        _state.value = _state.value.copy(listening = on)
    }

    fun focus(on: String?) {
        _state.value = _state.value.copy(focus = on)
    }

    fun whisper() {
        pulse(true)
    }

    fun unprompt() {
        pulse(false)
    }

    private fun pulse(whisper: Boolean) {
        scope.launch {
            _state.value = if (whisper) _state.value.copy(whispering = true) else _state.value.copy(unprompted = true)
            delay(if (whisper) 6000 else 8000)
            _state.value = if (whisper) _state.value.copy(whispering = false) else _state.value.copy(unprompted = false)
        }
    }

    fun toggleSolitude() {
        _state.value = _state.value.copy(solitude = !_state.value.solitude)
    }

    /** Called when you actually engage. Trust is local, invisible, and never a score you see. */
    fun noteInteraction() {
        val next = (_state.value.trust + 0.01f).coerceAtMost(1f)
        _state.value = _state.value.copy(trust = next)
        prefs?.edit()?.putFloat("trust", next)?.apply()
    }
}

private fun dayPart(): TimeOfDay {
    val now = LocalTime.now()
    return when {
        now.isBefore(LocalTime.of(5, 0)) -> TimeOfDay.LATE_NIGHT
        now.isBefore(LocalTime.of(7, 0)) -> TimeOfDay.DAWN
        now.isBefore(LocalTime.of(12, 0)) -> TimeOfDay.MORNING
        now.isBefore(LocalTime.of(17, 0)) -> TimeOfDay.AFTERNOON
        now.isBefore(LocalTime.of(19, 0)) -> TimeOfDay.GOLDEN
        now.isBefore(LocalTime.of(22, 0)) -> TimeOfDay.EVENING
        now.isBefore(LocalTime.of(23, 59)) -> TimeOfDay.NIGHT
        else -> TimeOfDay.LATE_NIGHT
    }
}

private fun moodFor(part: TimeOfDay): AtlasMood = when (part) {
    TimeOfDay.DAWN -> AtlasMood.QUIET
    TimeOfDay.MORNING -> AtlasMood.CALM
    TimeOfDay.AFTERNOON -> AtlasMood.FOCUSED
    TimeOfDay.GOLDEN -> AtlasMood.WARM
    TimeOfDay.EVENING -> AtlasMood.CALM
    TimeOfDay.NIGHT -> AtlasMood.QUIET
    TimeOfDay.LATE_NIGHT -> AtlasMood.QUIET
}


private fun energyFor(part: TimeOfDay): AtlasEnergy = when (part) {
    TimeOfDay.DAWN -> AtlasEnergy.RESTING
    TimeOfDay.MORNING -> AtlasEnergy.ACTIVE
    TimeOfDay.AFTERNOON -> AtlasEnergy.ENGAGED
    TimeOfDay.GOLDEN -> AtlasEnergy.ACTIVE
    TimeOfDay.EVENING -> AtlasEnergy.IDLE
    TimeOfDay.NIGHT -> AtlasEnergy.RESTING
    TimeOfDay.LATE_NIGHT -> AtlasEnergy.LOW
}
