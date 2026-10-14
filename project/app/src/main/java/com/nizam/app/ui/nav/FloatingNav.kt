package com.nizam.app.ui.nav

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.ui.design.Motion
import com.nizam.app.ui.design.Radius
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.frost
import com.nizam.app.ui.design.liquidGlass
import com.nizam.app.ui.design.rememberHaptics
import com.nizam.app.ui.design.wash

data class NavTab(val route: String, val label: String, val icon: ImageVector, val idle: ImageVector, val isOrb: Boolean = false)

/**
 * The tab bar: a floating frosted pill with a soft highlight that glides to the chosen tab.
 * Outlined icons when idle, filled when chosen; the label brightens with it.
 */
@Composable
fun FloatingNav(items: List<NavTab>, current: String?, onSelect: (String) -> Unit, onHold: (String) -> Unit = {}, highlightRoute: String? = null) {
    val c = MaterialTheme.colorScheme
    val haptics = rememberHaptics()
    val index = items.indexOfFirst { it.route == current }
    Box(
        Modifier.fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = Space.l, vertical = Space.s)
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(64.dp)
                .shadow(18.dp, Radius.pill, ambientColor = c.scrim.copy(alpha = 0.25f), spotColor = c.scrim.copy(alpha = 0.25f))
                .liquidGlass(Radius.pill)
                .padding(5.dp)
        ) {
            val w = maxWidth / items.size
            // gooey highlight: the leading edge races ahead, the trailing edge catches up, so it stretches like liquid
            val target = w * index.coerceAtLeast(0)
            var last by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(index) }
            val movingRight = index >= last
            androidx.compose.runtime.SideEffect { last = index }
            val fast = if (Motion.still) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring<androidx.compose.ui.unit.Dp>(0.75f, 900f)
            val slow = if (Motion.still) androidx.compose.animation.core.snap() else androidx.compose.animation.core.spring<androidx.compose.ui.unit.Dp>(0.8f, 280f)
            val left by animateDpAsState(target, if (movingRight) slow else fast, label = "navLeft")
            val right by animateDpAsState(target + w, if (movingRight) fast else slow, label = "navRight")
            val shown by animateFloatAsState(if (index >= 0) 1f else 0f, Motion.settle(), label = "navThumbShown")
            Box(Modifier.offset(x = left).width((right - left).coerceAtLeast(0.dp)).fillMaxHeight().scale(shown).clip(Radius.pill).background(wash(c.primary, 0.20f)))
            Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                items.forEach { tab ->
                    val on = tab.route == current
                    val highlighted = tab.route == highlightRoute
                    val tint by animateColorAsState(if (on) c.primary else c.onSurfaceVariant, label = "navTint")
                    val lift by animateFloatAsState(if (on) 1.08f else 1f, Motion.bouncy(), label = "navLift")
                    Column(
                        Modifier.weight(1f).fillMaxHeight()
                            .then(if (highlighted) Modifier.border(2.dp, c.primary, MaterialTheme.shapes.medium) else Modifier)
                            .semantics { role = Role.Tab; selected = on }
                            .pointerInput(tab.route) {
                                detectTapGestures(
                                    onTap = { haptics.tick(); onSelect(tab.route) },
                                    onLongPress = { haptics.long(); onHold(tab.route) }
                                )
                            },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                    ) {
                        if (tab.isOrb) {
                            // The Atlas orb, centre stage: tap to chat with Atlas, hold for voice.
                            com.nizam.app.ui.design.AtlasOrb(com.nizam.app.ui.design.OrbState.Idle, Modifier.scale(lift), diameter = 42.dp)
                        } else {
                            Icon(if (on) tab.icon else tab.idle, tab.label, tint = tint, modifier = Modifier.size(24.dp).scale(lift))
                            Text(tab.label, style = MaterialTheme.typography.labelSmall.copy(
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), color = tint, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
