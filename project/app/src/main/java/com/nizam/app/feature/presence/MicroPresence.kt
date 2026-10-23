package com.nizam.app.feature.presence

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.ui.design.Ascend
import com.nizam.app.ui.design.LivingReactor
import com.nizam.app.ui.design.PulseState
import com.nizam.app.ui.design.reactorGlow
import kotlinx.coroutines.delay

/**
 * Micro-presence: Atlas speaking softly from the edge of the screen.
 * One line, small, gone on its own. It never covers what you are doing.
 */
object MicroPresence {
    @Composable
    fun WhisperBubble(
        message: String,
        onDismiss: () -> Unit,
        onTap: () -> Unit
    ) {
        var visible by remember(message) { mutableStateOf(false) }

        LaunchedEffect(message) {
            visible = true
            delay(8000)
            visible = false
            delay(400)
            onDismiss()
        }

        AnimatedVisibility(
            visible = visible,
            enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
            exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(Color(0xF0101020))
                    .reactorGlow(Ascend.reactorCyan, 0.4f, RoundedCornerShape(50))
                    .clickable { onTap() }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LivingReactor(state = PulseState.WHISPERING, diameter = 26.dp)
                Text(
                    text = message,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}
