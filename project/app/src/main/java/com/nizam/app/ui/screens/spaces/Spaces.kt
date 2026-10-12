package com.nizam.app.ui.screens.spaces

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.ui.design.AtlasOrb
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.OrbState
import com.nizam.app.ui.design.liquidGlass
import com.nizam.app.ui.design.pressable
import com.nizam.app.ui.design.wash
import com.nizam.app.ui.nav.Routes
import com.nizam.app.ui.screens.hubs.Feature
import com.nizam.app.ui.screens.hubs.HubRegistry
import com.nizam.app.ui.screens.hubs.RecentFeatures
import com.nizam.app.ui.screens.me.MeScreen
import com.nizam.app.ui.screens.today.TodayScreen
import kotlinx.coroutines.launch

/** One page of the home: Today, a life space, or You. */
private sealed class Space(val name: String, val icon: ImageVector) {
    object Today : Space("Today", Icons.Outlined.Today)
    class Life(val hub: Hub) : Space(hub.label, hub.icon)
    object You : Space("You", Icons.Outlined.Person)
}

private val ORDER = listOf(Hub.Money, Hub.Deen, Hub.Health, Hub.Work, Hub.Memory, Hub.Learn, Hub.Home, Hub.Social, Hub.Media, Hub.Create, Hub.Travel, Hub.Tools)
private val SPACES: List<Space> = listOf(Space.Today) + ORDER.map { Space.Life(it) } + Space.You

/**
 * The home of Atlas: every part of your life is a space you swipe to. A strip of chips on top shows
 * where you are, a search box reaches everything, and one orb at the bottom asks Atlas (hold to talk).
 */
@Composable
fun SpacesHome(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var start by rememberSaveable { mutableStateOf(0) }
    val pager = rememberPagerState(initialPage = start, pageCount = { SPACES.size })
    LaunchedEffect(pager.currentPage) { start = pager.currentPage }
    var q by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val rail = rememberLazyListState()
    LaunchedEffect(pager.currentPage) { runCatching { rail.animateScrollToItem((pager.currentPage - 1).coerceAtLeast(0)) } }
    var tick2 by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { tick2++ }
    val here = SPACES[pager.currentPage]
    val accent = (here as? Space.Life)?.hub?.tint() ?: MaterialTheme.colorScheme.primary

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(here.name, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = accent)
                    Text(subtitle(here), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).background(wash(accent, 0.16f)).pressable { searching = !searching; if (!searching) q = "" },
                    contentAlignment = Alignment.Center) {
                    Icon(if (searching) Icons.Outlined.Close else Icons.Outlined.Search, "Search", tint = accent)
                }
            }
            StatusIsland()
            val liveNotices = remember(tick2) { runCatching { com.nizam.app.feature.nx.Noticer.scan(c) }.getOrDefault(emptyList()) }
            if (!searching) StoryRail(liveNotices, open)
            LazyRow(state = rail, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SPACES.size) { i ->
                    val s = SPACES[i]; val on = i == pager.currentPage
                    val tint = (s as? Space.Life)?.hub?.tint() ?: MaterialTheme.colorScheme.primary
                    val bg by animateColorAsState(if (on) wash(tint, 0.24f) else wash(MaterialTheme.colorScheme.onSurface, 0.06f), label = "chip")
                    Row(Modifier.clip(RoundedCornerShape(20.dp)).background(bg).pressable { scope.launch { pager.animateScrollToPage(i) } }
                        .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(s.icon, null, tint = if (on) tint else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                        if (on) { Spacer(Modifier.size(6.dp)); Text(s.name, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = tint) }
                    }
                }
            }
            if (searching) {
                OutlinedTextField(q, { q = it }, singleLine = true, placeholder = { Text("Search every tool in Atlas") },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                val found = remember(q) { HubRegistry.search(q).take(40) }
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 110.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (q.isNotBlank() && found.isEmpty()) item { Text("Nothing matches that. Try another word, or ask Atlas.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(found, key = { it.route }) { f -> Wide(f) { searching = false; q = ""; open(f.route) } }
                }
            } else {
                HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 0) { page ->
                    when (val s = SPACES[page]) {
                        Space.Today -> Box(Modifier.fillMaxSize().padding(bottom = 88.dp)) { TodayScreen(container) { open(it) } }
                        Space.You -> Box(Modifier.fillMaxSize().padding(bottom = 88.dp)) { MeScreen(container) { open(it) } }
                        is Space.Life -> SpacePage(s.hub, open)
                    }
                }
            }
        }
        Dock(Modifier.align(Alignment.BottomCenter), accent,
            onAsk = { open(Routes.ASK) }, onTalk = { open(Routes.VOICE) }, onAll = { open(Routes.MODULES) },
            onSearch = { searching = true })
        QuickCaptureFab(container, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 92.dp))
    }
}

private fun subtitle(s: Space) = when (s) {
    Space.Today -> "Your day, in one place"
    Space.You -> "Settings, look and what Atlas knows"
    is Space.Life -> "${HubRegistry.of(s.hub).size} tools · swipe for the next space"
}

@Composable
private fun SpacePage(hub: Hub, open: (String) -> Unit) {
    val c = LocalContext.current
    val tint = hub.tint()
    val top = remember(hub) { HubRegistry.top(hub) }
    val groups = remember(hub) { HubRegistry.grouped(hub) }
    val recent = remember(hub) { RecentFeatures.recent(c).mapNotNull { r -> HubRegistry.byRoute(r)?.takeIf { it.hub == hub } }.take(4) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (recent.isNotEmpty()) {
            item { Label("Jump back in") }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { recent.forEach { f -> Chip(f, tint, Modifier.weight(1f)) { open(f.route) } }; repeat(4 - recent.size) { Spacer(Modifier.weight(1f)) } } }
        }
        item { Label("Main") }
        items(top.chunked(2)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { f -> Hero(f, tint, Modifier.weight(1f)) { open(f.route) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        groups.forEach { (name, list) ->
            item(key = "g_$name") { Label(name) }
            items(list.chunked(3)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { f -> Tile(f, tint, Modifier.weight(1f)) { open(f.route) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable private fun Label(t: String) = Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp))

@Composable
private fun Hero(f: Feature, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.height(116.dp).clip(RoundedCornerShape(24.dp))
        .background(Brush.linearGradient(listOf(wash(tint, 0.28f), wash(tint, 0.10f)))).pressable { onClick() }.padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween) {
        Icon(f.icon, null, tint = tint, modifier = Modifier.size(28.dp))
        Column {
            Text(f.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(f.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Tile(f: Feature, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.height(92.dp).clip(RoundedCornerShape(20.dp)).background(wash(tint, 0.10f)).pressable { onClick() }.padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween) {
        Icon(f.icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Text(f.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Chip(f: Feature, tint: Color, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(wash(tint, 0.14f)).pressable { onClick() }.padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(f.icon, null, tint = tint, modifier = Modifier.size(20.dp))
        Text(f.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Wide(f: Feature, onClick: () -> Unit) {
    val tint = f.hub.tint()
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(wash(tint, 0.10f)).pressable { onClick() }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(f.icon, null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(f.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${f.hub.label} · ${f.blurb}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** The floating dock: search on the left, the Atlas orb in the middle (tap to ask, hold to talk), every tool on the right. */
@Composable
private fun Dock(modifier: Modifier, accent: Color, onAsk: () -> Unit, onTalk: () -> Unit, onAll: () -> Unit, onSearch: () -> Unit) {
    Row(modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 12.dp).height(64.dp)
        .shadow(16.dp, RoundedCornerShape(32.dp)).liquidGlass(RoundedCornerShape(32.dp)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).pressable { onSearch() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Search, "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(56.dp).pointerInput(Unit) { detectTapGestures(onTap = { onAsk() }, onLongPress = { onTalk() }) }, contentAlignment = Alignment.Center) {
            AtlasOrb(OrbState.Idle, diameter = 52.dp)
        }
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).pressable { onAll() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Apps, "All tools", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
