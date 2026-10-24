package com.nizam.app.ui.design.terrain

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.ui.design.LocalTerrain
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun Dial(
    visible: Boolean,
    onDismiss: () -> Unit,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val terrain = LocalTerrain.current
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 300f),
        label = "dialScale"
    )
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(300),
        label = "dialAlpha"
    )
    
    if (scale > 0.01f) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f * alpha))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .size(280.dp)
                    .scale(scale)
                    .alpha(alpha)
            ) {
                val items = listOf(
                    DialItem(Icons.Outlined.ChatBubbleOutline, "Ask", "ask"),
                    DialItem(Icons.Outlined.CameraAlt, "Capture", "capture"),
                    DialItem(Icons.Outlined.Search, "Find", "search"),
                    DialItem(Icons.Outlined.CheckCircleOutline, "Do", "tasks"),
                    DialItem(Icons.Outlined.AutoAwesome, "Reflect", "nx/constellation"),
                    DialItem(Icons.Outlined.MoreHoriz, "More", "hub")
                )
                
                items.forEachIndexed { index, item ->
                    val angle = (index * 60f) * Math.PI / 180f
                    val radius = 90.dp
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .offset(
                                x = (radius.value * cos(angle)).dp,
                                y = (radius.value * sin(angle)).dp
                            )
                    ) {
                        DialArc(item) { 
                            onNavigate(item.route)
                            onDismiss()
                        }
                    }
                }
                
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(70.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                listOf(terrain.accent.copy(alpha = 0.3f), terrain.basalt)
                            )
                        )
                        .border(width = 1.dp, brush = Brush.radialGradient(listOf(terrain.seam, Color.Transparent)), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Outlined.RadioButtonChecked,
                        contentDescription = "core",
                        tint = terrain.accent,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }
}

data class DialItem(
    val icon: ImageVector,
    val label: String,
    val route: String
)

@Composable
fun DialArc(
    item: DialItem,
    onClick: () -> Unit
) {
    val terrain = LocalTerrain.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(terrain.slate.copy(alpha = 0.8f))
                .border(width = 1.dp, color = terrain.seam, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                item.icon,
                contentDescription = item.label,
                tint = terrain.bone,
                modifier = Modifier.size(22.dp)
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = item.label,
            color = terrain.mist,
            fontSize = 10.sp,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
        )
    }
}
