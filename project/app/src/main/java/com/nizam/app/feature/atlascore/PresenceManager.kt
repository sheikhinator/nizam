package com.nizam.app.feature.atlascore

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.nizam.app.ui.design.Ascend
import com.nizam.app.ui.design.PulseState

/** Turns AtlasCore state into the single thing the UI needs: what the light should do. */
object PresenceManager {
    @Composable
    fun pulse(): PulseState {
        val s by AtlasCore.state.collectAsState()
        return when {
            s.whispering -> PulseState.WHISPERING
            s.unprompted -> PulseState.UNPROMPTED
            s.listening -> PulseState.LISTENING
            s.thinking -> PulseState.THINKING
            s.mood == AtlasMood.PROTECTIVE -> PulseState.PROTECTIVE
            else -> PulseState.IDLE
        }
    }

    @Composable
    fun color(): Color = when (pulse()) {
        PulseState.IDLE -> Ascend.reactorCyan
        PulseState.THINKING -> Ascend.reactorViolet
        PulseState.LISTENING -> Ascend.reactorEmerald
        PulseState.WHISPERING -> Ascend.reactorIce
        PulseState.UNPROMPTED -> Ascend.reactorAmber
        PulseState.PROTECTIVE -> Ascend.reactorRose
    }
}
