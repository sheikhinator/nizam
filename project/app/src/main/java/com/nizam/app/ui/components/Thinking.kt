package com.nizam.app.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * What Atlas shows while it works. "Loading…" tells you nothing; this says what stage it's at and
 * keeps moving, so a slow model feels like progress rather than a frozen screen.
 */
private val STAGES = listOf(
    "Reading your context" to 0,
    "Thinking it through" to 1800,
    "Choosing the right tool" to 4500,
    "Working on it" to 7000,
    "Still going — the model is slow today" to 14000,
    "Nearly there" to 22000
)

@Composable
fun Thinking(label: String? = null, modifier: Modifier = Modifier) {
    var elapsed by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(500); elapsed += 500 } }
    val stage = label ?: STAGES.last { elapsed >= it.second }.first
    Row(modifier.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        com.nizam.app.ui.design.AtlasOrb(com.nizam.app.ui.design.OrbState.Thinking, diameter = 22.dp)
        Spacer(Modifier.width(8.dp))
        Text(stage, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.alpha(0.9f))
        if (elapsed > 9000) {
            Spacer(Modifier.width(8.dp))
            Text("${elapsed / 1000}s", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
        }
    }
}
