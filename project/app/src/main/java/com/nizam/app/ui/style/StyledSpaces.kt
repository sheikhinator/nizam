package com.nizam.app.ui.style

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.parallax
import com.nizam.app.ui.design.wash
import com.nizam.app.ui.screens.hubs.Feature
import com.nizam.app.ui.screens.hubs.HubRegistry
import com.nizam.app.ui.screens.hubs.RecentFeatures
import kotlin.math.absoluteValue

/** A life space (Money, Deen, Work…) drawn in the chosen style: same tools, same groups. */
@Composable
fun StyledSpacePage(style: UiStyle, hub: Hub, open: (String) -> Unit) {
    val c = LocalContext.current
    val tint = hub.tint()
    val top = remember(hub) { HubRegistry.top(hub) }
    val groups = remember(hub) { HubRegistry.grouped(hub) }
    var pinTick by remember { mutableStateOf(0) }
    val pinned = remember(hub, pinTick) { com.nizam.app.feature.modes.Pins.pinned(c).mapNotNull { r -> HubRegistry.byRoute(r)?.takeIf { it.hub == hub } } }
    val recent = remember(hub) { RecentFeatures.recent(c).mapNotNull { r -> HubRegistry.byRoute(r)?.takeIf { it.hub == hub } }.take(6) }
    fun pin(r: String) { com.nizam.app.feature.modes.Pins.togglePin(c, r); pinTick++ }
    when (style) {
        UiStyle.DECK -> DeckSpace(tint, top, groups, (pinned + recent).distinctBy { it.route }, open, ::pin)
        UiStyle.BRIDGE -> BridgeSpace(hub, top, groups, open, ::pin)
        UiStyle.MAGAZINE -> MagazineSpace(hub, tint, top, groups, open, ::pin)
        else -> LayersSpace(tint, top, groups, (pinned + recent).distinctBy { it.route }, open, ::pin)
    }
}

private fun Modifier.taps(key: String, onClick: () -> Unit, onLong: () -> Unit) = pointerInput(key) { detectTapGestures(onTap = { onClick() }, onLongPress = { onLong() }) }

// ── Deck: the main tools as a deck of tall cards, each group as a hand you scroll ──
@Composable
private fun DeckSpace(tint: Color, top: List<Feature>, groups: List<Pair<String, List<Feature>>>, quick: List<Feature>, open: (String) -> Unit, pin: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 140.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (top.isNotEmpty()) item("deck") {
            val pager = rememberPagerState(pageCount = { top.size })
            HorizontalPager(pager, Modifier.fillMaxWidth().height(220.dp).padding(top = 6.dp), contentPadding = PaddingValues(horizontal = 56.dp), pageSpacing = 6.dp) { p ->
                val off = (pager.currentPage - p) + pager.currentPageOffsetFraction
                val o = off.absoluteValue.coerceIn(0f, 1f)
                val f = top[p]
                Column(Modifier.fillMaxSize().graphicsLayer { scaleX = 1f - 0.1f * o; scaleY = 1f - 0.1f * o; rotationY = off * 16f; alpha = 1f - 0.3f * o; cameraDistance = 14f * density }
                    .shadow(14.dp, RoundedCornerShape(26.dp)).clip(RoundedCornerShape(26.dp))
                    .background(Brush.linearGradient(listOf(wash(tint, 0.5f), wash(tint, 0.16f)))).taps(f.route, { open(f.route) }, { pin(f.route) }).padding(20.dp)) {
                    Icon(f.icon, null, tint = tint, modifier = Modifier.size(34.dp))
                    Spacer(Modifier.weight(1f))
                    Text(f.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2)
                    Text(f.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (quick.isNotEmpty()) item("quick") { Hand("Pinned & recent", tint, quick, open, pin) }
        groups.forEach { (name, list) -> item("g_$name") { Hand(name, tint, list, open, pin) } }
    }
}

@Composable
private fun Hand(name: String, tint: Color, list: List<Feature>, open: (String) -> Unit, pin: (String) -> Unit) {
    Column {
        Text(name.uppercase(), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, bottom = 6.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy((-14).dp)) {
            items(list, key = { it.route }) { f ->
                Column(Modifier.size(112.dp, 150.dp).graphicsLayer { rotationZ = ((f.route.hashCode() % 5) - 2) * 1.2f }.shadow(10.dp, RoundedCornerShape(18.dp))
                    .clip(RoundedCornerShape(18.dp)).background(Brush.verticalGradient(listOf(wash(tint, 0.34f), MaterialTheme.colorScheme.surface)))
                    .border(1.dp, tint.copy(alpha = 0.25f), RoundedCornerShape(18.dp)).taps(f.route, { open(f.route) }, { pin(f.route) }).padding(12.dp)) {
                    Icon(f.icon, null, tint = tint, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.weight(1f))
                    Text(f.title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

// ── Bridge: every tool is a module on the console; it glows when it has something for you ──
@Composable
private fun BridgeSpace(hub: Hub, top: List<Feature>, groups: List<Pair<String, List<Feature>>>, open: (String) -> Unit, pin: (String) -> Unit) {
    val K = StyleKit
    val tint = hub.tint()
    LazyColumn(Modifier.fillMaxSize().background(K.bridgeInk), contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 140.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Text("STATION ${hub.label.uppercase()} · ${top.size + groups.sumOf { it.second.size }} MODULES ONLINE", fontFamily = K.mono, fontSize = 11.sp, color = K.bridgeDim, letterSpacing = 1.sp) }
        val all = listOf("PRIMARY" to top) + groups.map { it.first.uppercase() to it.second }
        all.forEach { (name, list) ->
            if (list.isEmpty()) return@forEach
            item("h_$name") { Text("// $name", fontFamily = K.mono, fontSize = 11.sp, color = tint, modifier = Modifier.padding(top = 8.dp)) }
            items(list.chunked(3), key = { r -> name + r.first().route }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { f ->
                        Column(Modifier.weight(1f).height(86.dp).clip(RoundedCornerShape(6.dp)).background(K.bridgePanel).border(1.dp, tint.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                            .taps(f.route, { open(f.route) }, { pin(f.route) }).padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(f.icon, null, tint = tint, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.weight(1f))
                                Box(Modifier.size(5.dp).clip(CircleShape).background(K.bridgeLime))
                            }
                            Spacer(Modifier.weight(1f))
                            Text(f.title.uppercase(), fontFamily = K.mono, fontSize = 10.sp, color = K.bridgeText, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

// ── Magazine: the space as a table of contents ──
@Composable
private fun MagazineSpace(hub: Hub, tint: Color, top: List<Feature>, groups: List<Pair<String, List<Feature>>>, open: (String) -> Unit, pin: (String) -> Unit) {
    val serif = StyleKit.serif
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val pages = remember(top, groups) { (top.drop(1) + groups.flatMap { it.second }).mapIndexed { i, f -> f.route to i + 1 }.toMap() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 140.dp)) {
        item {
            Text("THE ${hub.label.uppercase()} SECTION", fontFamily = serif, fontSize = 12.sp, letterSpacing = 2.sp, color = tint, fontWeight = FontWeight.Bold)
            Box(Modifier.padding(vertical = 6.dp).fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.onSurface))
        }
        if (top.isNotEmpty()) item("feature") {
            val f = top.first()
            Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(4.dp)).background(wash(tint, 0.14f)).taps(f.route, { open(f.route) }, { pin(f.route) }).padding(16.dp)) {
                Text("COVER STORY", fontFamily = serif, fontSize = 10.sp, letterSpacing = 2.sp, color = tint)
                Text(f.title, fontFamily = serif, fontSize = 32.sp, fontWeight = FontWeight.Black, lineHeight = 36.sp)
                Text(f.blurb, fontFamily = serif, fontStyle = FontStyle.Italic, fontSize = 15.sp, color = muted)
            }
        }
        val sections = listOf("Features" to top.drop(1)) + groups
        sections.forEach { (name, list) ->
            if (list.isEmpty()) return@forEach
            item("s_$name") { Text(name, fontFamily = serif, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)) }
            items(list, key = { name + it.route }) { f ->
                val n = pages[f.route] ?: 0
                Row(Modifier.fillMaxWidth().taps(f.route, { open(f.route) }, { pin(f.route) }).padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(f.title, fontFamily = serif, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(f.blurb, fontFamily = serif, fontSize = 13.sp, color = muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text("%02d".format(n % 100), fontFamily = serif, fontStyle = FontStyle.Italic, color = tint, modifier = Modifier.padding(start = 12.dp))
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(muted.copy(alpha = 0.2f)))
            }
        }
    }
}

// ── Layers: tools float at different depths; what you use most sits nearest ──
@Composable
private fun LayersSpace(tint: Color, top: List<Feature>, groups: List<Pair<String, List<Feature>>>, quick: List<Feature>, open: (String) -> Unit, pin: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 140.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val near = (quick + top).distinctBy { it.route }
        items(near.chunked(2), key = { "n" + it.first().route }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { f -> Tile(f, tint, Modifier.weight(1f).height(124.dp), 1.2f, 20.dp, open, pin) }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        groups.forEach { (name, list) ->
            item("g_$name") { Text(name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp)) }
            items(list.chunked(3), key = { name + it.first().route }) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { f -> Tile(f, tint, Modifier.weight(1f).height(96.dp), 0.6f, 8.dp, open, pin) }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun Tile(f: Feature, tint: Color, modifier: Modifier, depth: Float, elevation: androidx.compose.ui.unit.Dp, open: (String) -> Unit, pin: (String) -> Unit) {
    Column(modifier.parallax(depth).shadow(elevation, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))
        .background(MaterialTheme.colorScheme.surface).background(Brush.linearGradient(listOf(tint.copy(alpha = 0.2f), Color.Transparent)))
        .taps(f.route, { open(f.route) }, { pin(f.route) }).padding(12.dp)) {
        Icon(f.icon, null, tint = tint, modifier = Modifier.size(if (depth > 1f) 28.dp else 22.dp))
        Spacer(Modifier.weight(1f))
        Text(f.title, style = if (depth > 1f) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
