package com.nizam.app.feature.solitude

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.feature.atlascore.AtlasCore
import com.nizam.app.ui.design.Ascend
import com.nizam.app.ui.design.LivingReactor
import com.nizam.app.ui.design.PulseState

/**
 * Solitude - a room where nothing is asked of you.
 *
 * Turning it on tells Atlas to stop speaking first. The reactor stays, breathing, so
 * the room is not empty; it is simply quiet on purpose.
 */
@Composable
fun SolitudeScreen() {
    val state by AtlasCore.state.collectAsState()
    val on = state.solitude

    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    if (on) listOf(Color(0xFF04060C), Color(0xFF0A0C16), Color(0xFF04050A))
                    else listOf(Color(0xFF0B0E18), Color(0xFF131728), Color(0xFF0B0E18))
                )
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 30.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            LivingReactor(
                state = if (on) PulseState.IDLE else PulseState.WHISPERING,
                diameter = 148.dp
            )
            Spacer(Modifier.height(38.dp))
            Text(
                "Solitude",
                color = Color.White.copy(alpha = 0.94f),
                fontSize = 30.sp,
                fontWeight = FontWeight.Light
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (on) "The door is closed. Atlas will not speak first."
                else "A room with the lights low and no one asking anything of you.",
                color = Color.White.copy(alpha = 0.55f),
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 21.sp
            )
            Spacer(Modifier.height(40.dp))
            Button(
                onClick = { AtlasCore.toggleSolitude() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (on) Color.White.copy(alpha = 0.1f) else Ascend.reactorIce,
                    contentColor = if (on) Color.White.copy(alpha = 0.8f) else Color(0xFF06070C)
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (on) "Leave solitude" else "Enter solitude")
            }
            Spacer(Modifier.height(22.dp))
            Text(
                "Nothing is switched off. Prayers, alarms and the things you set still happen. Only the unprompted voice goes quiet.",
                color = Color.White.copy(alpha = 0.35f),
                fontSize = 11.sp,
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
                lineHeight = 17.sp
            )
        }
    }
}
