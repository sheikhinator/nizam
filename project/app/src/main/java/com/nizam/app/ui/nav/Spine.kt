package com.nizam.app.ui.nav

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.nizam.app.ui.design.LocalTerrain

@Composable
fun Spine(
    modifier: Modifier = Modifier
) {
    val terrain = LocalTerrain.current
    Box(
        modifier = modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(
                Brush.verticalGradient(
                    listOf(
                        terrain.seam,
                        Color.Transparent,
                        terrain.seam.copy(alpha = 0.3f)
                    )
                )
            )
    )
}
