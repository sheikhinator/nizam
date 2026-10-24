package com.nizam.app.ui.design.terrain

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.ui.design.LocalTerrain
import com.nizam.app.ui.theme.DisplayFamily
import com.nizam.app.ui.theme.BodyFamily

@Composable
fun Shard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    bleed: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val terrain = LocalTerrain.current
    val a = accent ?: terrain.accent
    val shape = RoundedCornerShape(
        topStart = 28.dp,
        topEnd = 6.dp,
        bottomEnd = 28.dp,
        bottomStart = 6.dp
    )
    val m = if (onClick != null) modifier.clickable { onClick() } else modifier
    Box(
        m
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    listOf(
                        terrain.slate.copy(alpha = 0.8f),
                        terrain.basalt.copy(alpha = 0.9f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        terrain.seam,
                        Color.Transparent
                    )
                ),
                shape = shape
            )
    ) {
        if (bleed) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(a.copy(alpha = 0.08f), Color.Transparent),
                            radius = 200f
                        )
                    )
            )
        }
        Column(
            Modifier.padding(22.dp),
            content = content
        )
    }
}

@Composable
fun Monolith(
    number: String,
    label: String,
    modifier: Modifier = Modifier,
    accent: Color? = null
) {
    val terrain = LocalTerrain.current
    val a = accent ?: terrain.accent
    Column(modifier) {
        Text(
            text = number,
            color = terrain.bone,
            fontFamily = DisplayFamily,
            fontWeight = FontWeight.SemiBold,
            fontSize = androidx.compose.ui.unit.TextUnit(48f, androidx.compose.ui.unit.TextUnitType.Sp)
        )
        Text(
            text = label.uppercase(),
            color = terrain.mist,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp,
            letterSpacing = 0.15.em
        )
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .width(24.dp)
                .height(1.dp)
                .background(a)
        )
    }
}

@Composable
fun LedgerRow(
    title: String,
    subtitle: String? = null,
    trailing: String? = null,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val terrain = LocalTerrain.current
    val m = if (onClick != null) modifier.clickable { onClick() } else modifier
    Row(
        m
            .fillMaxWidth()
            .padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = terrain.bone,
                fontFamily = BodyFamily,
                fontSize = 15.sp
            )
            subtitle?.let {
                Text(
                    text = it,
                    color = terrain.mist,
                    fontFamily = BodyFamily,
                    fontSize = 13.sp
                )
            }
        }
        trailing?.let {
            Text(
                text = it,
                color = terrain.mist,
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = 11.sp
            )
        }
    }
}
