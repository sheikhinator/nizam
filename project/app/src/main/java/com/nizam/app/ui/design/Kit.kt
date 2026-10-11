package com.nizam.app.ui.design

import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nizam.app.ui.theme.MonoNumber

/*
 * The component kit. New screens are assembled only from these (plus Atlas.kt's buttons, fields,
 * Group, Segmented and Capsule), so every hub looks like one app however many features it holds.
 */

/**
 * The standard screen: a large title that fades into a slim sticky header as you scroll, then the
 * content as a lazy list with page margins. Loading and empty states are part of the template, so
 * no screen ever shows a bare spinner or a blank page.
 */
@Composable
fun AtlasScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    hub: Hub? = null,
    loading: Boolean = false,
    empty: EmptySpec? = null,
    state: LazyListState = rememberLazyListState(),
    actions: @Composable () -> Unit = {},
    content: LazyListScope.() -> Unit
) {
    val collapsed by remember { derivedStateOf { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 120 } }
    Box(modifier.fillMaxSize()) {
        if (hub == Hub.Deen) GeometricPattern(Modifier.fillMaxSize())
        LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = Space.xxl)) {
            item("atlas-title") {
                Column(Modifier.fillMaxWidth().padding(start = Space.page, end = Space.page, top = Space.s, bottom = Space.l)) {
                    if (hub != null) HubMark(hub)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(title, style = if (Style.s.expressive) MaterialTheme.typography.displaySmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Black, letterSpacing = (-0.5).sp)
                            else MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f),
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        actions()
                    }
                    if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.xs))
                }
            }
            when {
                loading -> item("atlas-skeleton") { SkeletonList(Modifier.padding(horizontal = Space.page)) }
                empty != null -> item("atlas-empty") { EmptyState(empty) }
                else -> content()
            }
        }
        AnimatedVisibility(collapsed, enter = fadeIn(Motion.enter()) + slideInVertically(Motion.enter()) { -it / 2 },
            exit = fadeOut(Motion.exit()) + slideOutVertically(Motion.exit()) { -it / 2 }) {
            Box(Modifier.fillMaxWidth().padding(horizontal = Space.m, vertical = Space.xs).clip(Radius.pill)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
                .padding(horizontal = Space.l, vertical = Space.s)) {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** The small coloured mark above a hub screen's title. */
@Composable
fun HubMark(hub: Hub) {
    val tint = hub.tint()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = Space.xs)) {
        Icon(hub.icon, null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(Space.xs))
        Text(hub.label.uppercase(), style = MaterialTheme.typography.labelSmall, color = tint)
    }
}

/** What an empty screen says instead of nothing. */
data class EmptySpec(val icon: ImageVector, val title: String, val body: String, val action: String? = null, val onAction: (() -> Unit)? = null)

@Composable
fun EmptyState(spec: EmptySpec, modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth().padding(horizontal = Space.xxl, vertical = Space.xxxl),
        horizontalAlignment = Alignment.CenterHorizontally) {
        val ctx = androidx.compose.ui.platform.LocalContext.current
        val pet = remember { com.nizam.app.feature.companion.Companion.load(ctx) }
        if (pet.hatched && com.nizam.app.feature.companion.Companion.visible(ctx))
            // the companion keeps empty screens company
            com.nizam.app.feature.companion.Creature(pet, com.nizam.app.feature.companion.Companion.Mood.SLEEPY, 0, Modifier.size(96.dp))
        else if (Style.s.art) Art3D(listOf("sparkles", "crystal_ball", "seedling", "package", "glowing_star", "gem_stone", "rocket", "books", "potted_plant", "magic_wand")
            [kotlin.math.abs(spec.title.hashCode()) % 10], 112.dp)
        else Box(Modifier.size(72.dp).clip(Radius.tile).background(wash(c.primary, 0.16f)), contentAlignment = Alignment.Center) {
            Icon(spec.icon, null, tint = c.primary, modifier = Modifier.size(34.dp))
        }
        Spacer(Modifier.height(Space.l))
        Text(spec.title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(Space.xs))
        Text(spec.body, style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
        if (spec.action != null && spec.onAction != null) {
            Spacer(Modifier.height(Space.l))
            Button(onClick = spec.onAction) { Text(spec.action) }
        }
    }
}

/** A soft light sweeping across placeholder shapes while content loads. */
fun Modifier.shimmer(): Modifier = composed {
    val c = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "shimmer")
    val x by t.animateFloat(-1f, 2f, infiniteRepeatable(tween(1300, easing = LinearEasing), RepeatMode.Restart), label = "x")
    val base = c.surfaceVariant
    val glow = c.onSurface.copy(alpha = 0.06f)
    this.background(base).background(
        Brush.linearGradient(listOf(Color.Transparent, glow, Color.Transparent),
            start = Offset(x * 600f, 0f), end = Offset(x * 600f + 400f, 200f))
    )
}

/** Placeholder rows shaped like the content that's coming. */
@Composable
fun SkeletonList(modifier: Modifier = Modifier, rows: Int = 5) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        repeat(rows) { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(Radius.chip).shimmer())
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Box(Modifier.fillMaxWidth(if (i % 2 == 0) 0.7f else 0.5f).height(14.dp).clip(Radius.chip).shimmer())
                    Spacer(Modifier.height(Space.s))
                    Box(Modifier.fillMaxWidth(0.35f).height(10.dp).clip(Radius.chip).shimmer())
                }
            }
        }
    }
}

/** A section heading with an optional action on the right ("See all"). */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = Space.page).padding(top = Space.xl, bottom = Space.s),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        if (action != null && onAction != null)
            Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(Radius.chip).pressable(onClick = onAction).padding(horizontal = Space.s, vertical = Space.xs))
    }
}

/** A hub's big action tile: tinted icon well, label, optional hint. */
@Composable
fun ActionTile(icon: ImageVector, label: String, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary,
               hint: String? = null, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Column(modifier.clip(Radius.tile).background(c.surface).pressable(onClick = onClick).padding(Space.l)) {
        Box(Modifier.size(40.dp).clip(Radius.field).background(wash(tint, 0.18f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(Space.m))
        Text(label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis)
    }
}

/** A figure with its label; the number rolls when it changes. */
@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, caption: String? = null, tint: Color = MaterialTheme.colorScheme.onSurface) {
    val c = MaterialTheme.colorScheme
    Column(modifier.clip(Radius.card).background(c.surface).padding(Space.l)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
        Spacer(Modifier.height(Space.xs))
        RollingText(value, MaterialTheme.typography.headlineMedium.merge(MonoNumber.copy(fontSize = MaterialTheme.typography.headlineMedium.fontSize)), tint)
        if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
    }
}

/** Text whose characters roll up or down, odometer-style, when the value changes. */
@Composable
fun RollingText(text: String, style: androidx.compose.ui.text.TextStyle, color: Color = MaterialTheme.colorScheme.onSurface) {
    Row {
        text.forEachIndexed { i, ch ->
            AnimatedContent(targetState = ch, transitionSpec = {
                val up = targetState > initialState
                (slideInVertically(Motion.enter()) { if (up) it else -it } + fadeIn(Motion.enter())) togetherWith
                    (slideOutVertically(Motion.exit()) { if (up) -it else it } + fadeOut(Motion.exit()))
            }, label = "roll$i") { c -> Text(c.toString(), style = style, color = color) }
        }
    }
}

/** A row in a list: leading icon well, title, supporting line, and a trailing slot. */
@Composable
fun ListRow(title: String, modifier: Modifier = Modifier, supporting: String? = null, icon: ImageVector? = null,
            tint: Color = MaterialTheme.colorScheme.primary, trailing: @Composable () -> Unit = {}, onClick: (() -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().then(if (onClick != null) Modifier.pressable(onClick = onClick) else Modifier)
        .padding(horizontal = Space.page, vertical = Space.m), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Box(Modifier.size(40.dp).clip(Radius.field).background(wash(tint, 0.16f)), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(Space.m))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** Fades and lifts content in the first time it appears — for cards arriving on a screen. */
fun Modifier.arrive(index: Int = 0): Modifier = composed {
    val t = remember { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(40L * index.coerceAtMost(8))
        t.animateTo(1f, Motion.enter())
    }
    this.graphicsLayer { alpha = t.value; translationY = (1f - t.value) * 24f }
}
