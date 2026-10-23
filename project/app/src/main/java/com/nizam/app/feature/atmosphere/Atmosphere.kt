package com.nizam.app.feature.atmosphere

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.nizam.app.feature.atlascore.AtlasCore
import com.nizam.app.feature.atlascore.TimeOfDay

/**
 * Atmosphere - the room Atlas is in.
 *
 * A whisper of light layered over the real theme background, following the hour.
 * Contrast is never touched: it is felt more than seen, and it works in light mode too.
 */
object Atmosphere {
    fun accent(time: TimeOfDay): Color = when (time) {
        TimeOfDay.DAWN -> Color(0xFFFF9C7A)
        TimeOfDay.MORNING -> Color(0xFF7FD4C1)
        TimeOfDay.AFTERNOON -> Color(0xFF4D9BFF)
        TimeOfDay.GOLDEN -> Color(0xFFFFB84D)
        TimeOfDay.EVENING -> Color(0xFF9D7BFF)
        TimeOfDay.NIGHT -> Color(0xFF6C7FE0)
        TimeOfDay.LATE_NIGHT -> Color(0xFF5B6BC0)
    }

    fun warmth(time: TimeOfDay): Float = when (time) {
        TimeOfDay.GOLDEN -> 0.16f
        TimeOfDay.DAWN -> 0.13f
        TimeOfDay.MORNING -> 0.09f
        TimeOfDay.AFTERNOON -> 0.07f
        TimeOfDay.EVENING -> 0.10f
        TimeOfDay.NIGHT -> 0.06f
        TimeOfDay.LATE_NIGHT -> 0.04f
    }
}

@Composable
fun currentAtmosphereAccent(): Color {
    val state by AtlasCore.state.collectAsState()
    return Atmosphere.accent(state.timeOfDay)
}

@Composable
fun AtmosphereBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val state by AtlasCore.state.collectAsState()
    val accent = Atmosphere.accent(state.timeOfDay)
    val warmth = Atmosphere.warmth(state.timeOfDay)
    Box(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(accent.copy(alpha = warmth), Color.Transparent, accent.copy(alpha = warmth * 0.35f))
                    )
                )
        )
        content()
    }
}
