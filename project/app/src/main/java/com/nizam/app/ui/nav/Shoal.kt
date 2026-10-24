package com.nizam.app.ui.nav

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.ui.design.LocalTerrain
import com.nizam.app.ui.theme.BodyFamily
import kotlinx.coroutines.delay

@Composable
fun Shoal(
    currentRoute: String?,
    onNavigate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val terrain = LocalTerrain.current
    val items = listOf(
        NavItem("home", Icons.Outlined.Home, "nx/hub"),
        NavItem("memory", Icons.Outlined.AutoAwesome, "nx/constellation"),
        NavItem("core", Icons.Outlined.RadioButtonChecked, "core"),
        NavItem("tasks", Icons.Outlined.CheckCircleOutline, "tasks"),
        NavItem("me", Icons.Outlined.PersonOutline, "me")
    )
    
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(
                    Brush.verticalGradient(
                        listOf(
                            terrain.slate.copy(alpha = 0.6f),
                            terrain.basalt.copy(alpha = 0.8f)
                        )
                    )
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            items.forEach { item ->
                val active = currentRoute == item.route || (item.route == "nx/hub" && currentRoute == "today")
                ShoalItem(item, active) { 
                    if (item.route == "core") {
                        // TODO: Open Dial
                    } else {
                        onNavigate(item.route)
                    }
                }
            }
        }
    }
}

data class NavItem(
    val id: String,
    val icon: ImageVector,
    val route: String
)

@Composable
fun ShoalItem(
    item: NavItem,
    active: Boolean,
    onClick: () -> Unit
) {
    val terrain = LocalTerrain.current
    val scale by animateFloatAsState(
        targetValue = if (active) 1.1f else 1f,
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 400f),
        label = "scale"
    )
    
    Box(
        Modifier
            .size(44.dp)
            .scale(scale)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (active) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(terrain.accent.copy(alpha = 0.15f))
            )
        }
        Icon(
            imageVector = item.icon,
            contentDescription = item.id,
            tint = if (active) terrain.accent else terrain.mist.copy(alpha = 0.6f),
            modifier = Modifier.size(22.dp)
        )
    }
}
