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
import androidx.compose.foundation.layout.statusBars
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
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
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
import androidx.compose.runtime.collectAsState
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
import com.nizam.app.ui.design.parallax
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

private val DEFAULT_ORDER = listOf(Hub.Money, Hub.Deen, Hub.Health, Hub.Work, Hub.Memory, Hub.Learn, Hub.Home, Hub.Social, Hub.Media, Hub.Create, Hub.Travel, Hub.Tools)

/** Spaces you actually use rise to the front; an unused one keeps its sensible default spot. Today and You stay fixed ends. */
private fun adaptiveOrder(c: android.content.Context): List<Hub> {
    val score = DEFAULT_ORDER.associateWith { h -> HubRegistry.of(h).sumOf { RecentFeatures.uses(c, it.route) } }
    val any = score.values.any { it > 0 }
    return if (!any) DEFAULT_ORDER else DEFAULT_ORDER.sortedByDescending { score[it] ?: 0 }
}

/**
 * The home of Atlas: every part of your life is a space you swipe to. A strip of chips on top shows
 * where you are, a search box reaches everything, and one orb at the bottom asks Atlas (hold to talk).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SpacesHome(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    // a light tool (an nx/ screen) peeks open in a sheet over the home; anything else leaves the home as before
    var sheetRoute by rememberSaveable { mutableStateOf<String?>(null) }
    fun openOrPeek(r: String) { if (r.startsWith("nx/") && r != "nx/chief" && r != "nx/flows") sheetRoute = r else open(r) }
    val spaces = remember { listOf(Space.Today) + adaptiveOrder(c).map { Space.Life(it) } + Space.You }
    var start by rememberSaveable { mutableStateOf(0) }
    val pager = rememberPagerState(initialPage = start, pageCount = { spaces.size })
    LaunchedEffect(pager.currentPage) { com.nizam.app.ui.design.guard { start = pager.currentPage } }
    var q by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val rail = rememberLazyListState()
    LaunchedEffect(pager.currentPage) { com.nizam.app.ui.design.guard { runCatching { rail.animateScrollToItem((pager.currentPage - 1).coerceAtLeast(0)) } } }
    var tick2 by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { tick2++ } }
    val here = spaces[pager.currentPage]
    val accent = (here as? Space.Life)?.hub?.tint() ?: MaterialTheme.colorScheme.primary

    // Hardware/gesture back: close search, close a peeked tool, or return to Today — never a dead end.
    // Only Today (page 0) lets a plain back through, so leaving the app from elsewhere still needs two steps.
    androidx.activity.compose.BackHandler(enabled = searching || sheetRoute != null || pager.currentPage != 0) {
        when { sheetRoute != null -> sheetRoute = null; searching -> { searching = false; q = "" }
            else -> scope.launch { pager.animateScrollToPage(0) } }
    }

    // each space gets a faint wash of its own colour, so the whole screen — not just its cards — tells you where you are
    val spaceTint by animateColorAsState(accent, label = "spaceTint")
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(wash(spaceTint, 0.07f), MaterialTheme.colorScheme.background.copy(alpha = 0f))))) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                // Today is home: everywhere else gets a real back arrow, not just a chip you have to notice.
                if (pager.currentPage != 0) {
                    Box(Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).pressable { scope.launch { pager.animateScrollToPage(0) } },
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to Today", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Column(Modifier.weight(1f).padding(start = if (pager.currentPage != 0) 4.dp else 0.dp)) {
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
            if (!searching) StoryRail(liveNotices, ::openOrPeek)
            LazyRow(state = rail, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(spaces.size) { i ->
                    val s = spaces[i]; val on = i == pager.currentPage
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
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 140.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (q.isNotBlank() && found.isEmpty()) item { Text("Nothing matches that. Try another word, or ask Atlas.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(found, key = { it.route }) { f -> Wide(f) { searching = false; q = ""; openOrPeek(f.route) } }
                }
            } else {
                HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 0) { page ->
                    when (val s = spaces[page]) {
                        Space.Today -> Box(Modifier.fillMaxSize().padding(bottom = 88.dp)) { TodayScreen(container) { openOrPeek(it) } }
                        Space.You -> Box(Modifier.fillMaxSize().padding(bottom = 88.dp)) { MeScreen(container) { openOrPeek(it) } }
                        is Space.Life -> SpacePage(s.hub, ::openOrPeek)
                    }
                }
            }
        }
        Dock(Modifier.align(Alignment.BottomCenter).parallax(0.3f), accent,
            onAsk = { openOrPeek(com.nizam.app.feature.nx.Nx.route("atlastalk")) }, onTalk = { open(Routes.VOICE) }, onAll = { open(Routes.MODULES) },
            onSearch = { openOrPeek(com.nizam.app.feature.nx.Nx.route("find")) },
            onLycho = { open(com.nizam.app.feature.nx.Nx.route("command")) }, onLychoHold = { open(com.nizam.app.feature.nx.Nx.route("pulse")) }, container = container)
        QuickCaptureFab(container, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 92.dp))
        com.nizam.app.feature.next.ShakeToUndo()
        val peek = sheetRoute
        if (peek != null) {
            val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = false)
            androidx.compose.material3.ModalBottomSheet(onDismissRequest = { sheetRoute = null }, sheetState = sheetState,
                modifier = Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    com.nizam.app.feature.nx.NxScreen(peek.removePrefix("nx/"), container) { next -> if (next.startsWith("nx/")) sheetRoute = next else { sheetRoute = null; open(next) } }
                }
            }
        }
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
    var pinTick by remember { mutableStateOf(0) }
    val pinned = remember(hub, pinTick) { com.nizam.app.feature.modes.Pins.pinned(c).mapNotNull { r -> HubRegistry.byRoute(r)?.takeIf { it.hub == hub } } }
    fun togglePin(route: String) { com.nizam.app.feature.modes.Pins.togglePin(c, route); pinTick++ }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 140.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (pinned.isNotEmpty()) {
            item { Label("Pinned") }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { pinned.take(4).forEach { f -> Chip(f, tint, Modifier.weight(1f), onLongClick = { togglePin(f.route) }) { open(f.route) } }; repeat(4 - pinned.size.coerceAtMost(4)) { Spacer(Modifier.weight(1f)) } } }
        }
        if (recent.isNotEmpty()) {
            item { Label("Jump back in") }
            item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { recent.forEach { f -> Chip(f, tint, Modifier.weight(1f), onLongClick = { togglePin(f.route) }) { open(f.route) } }; repeat(4 - recent.size) { Spacer(Modifier.weight(1f)) } } }
        }
        item { Label("Main · hold a tile to pin it") }
        items(top.chunked(2)) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { f -> Hero(f, tint, Modifier.weight(1f), onLongClick = { togglePin(f.route) }) { open(f.route) } }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        groups.forEach { (name, list) ->
            item(key = "g_$name") { Label(name) }
            items(list.chunked(3)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { f -> Tile(f, tint, Modifier.weight(1f), onLongClick = { togglePin(f.route) }) { open(f.route) } }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable private fun Label(t: String) = Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, start = 4.dp))

@Composable
private fun Hero(f: Feature, tint: Color, modifier: Modifier, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    Column(modifier.height(116.dp).clip(RoundedCornerShape(24.dp))
        .background(Brush.linearGradient(listOf(wash(tint, 0.28f), wash(tint, 0.10f))))
        .pointerInput(f.route) { detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() }) }.padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween) {
        Icon(f.icon, null, tint = tint, modifier = Modifier.size(28.dp))
        Column {
            Text(f.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(f.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Tile(f: Feature, tint: Color, modifier: Modifier, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    Column(modifier.height(92.dp).clip(RoundedCornerShape(20.dp)).background(wash(tint, 0.10f))
        .pointerInput(f.route) { detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() }) }.padding(10.dp),
        verticalArrangement = Arrangement.SpaceBetween) {
        com.nizam.app.ui.design.LivingIcon(f.icon, tint, size = 34.dp, iconSize = 20.dp)
        Text(f.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Chip(f: Feature, tint: Color, modifier: Modifier, onLongClick: () -> Unit = {}, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(wash(tint, 0.14f))
        .pointerInput(f.route) { detectTapGestures(onTap = { onClick() }, onLongPress = { onLongClick() }) }.padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        com.nizam.app.ui.design.LivingIcon(f.icon, tint, size = 30.dp, iconSize = 18.dp)
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

/**
 * The floating dock: search on the left, the Atlas orb (tap to ask, hold to talk), the LYCHO orb beside it
 * (Atlas's mind at work: tap for its expertise, hold for Atlas Pulse), every tool on the right.
 */
@Composable
private fun Dock(modifier: Modifier, accent: Color, onAsk: () -> Unit, onTalk: () -> Unit, onAll: () -> Unit, onSearch: () -> Unit,
                 onLycho: () -> Unit, onLychoHold: () -> Unit, container: com.nizam.app.AppContainer? = null) {
    val halo by com.nizam.app.feature.agentic.AgentHalo.state.collectAsState()
    val expert by com.nizam.app.feature.expertise.Expertise.current.collectAsState()
    val mindState = when { halo.active -> OrbState.Working; expert != null -> OrbState.Thinking; else -> OrbState.Idle }
    Row(modifier.windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 12.dp).height(64.dp)
        .shadow(16.dp, RoundedCornerShape(32.dp)).liquidGlass(RoundedCornerShape(32.dp)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (container != null) com.nizam.app.feature.safe.RefreshButton(container, null, size = 40.dp)
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).pressable { onSearch() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Search, "Search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(56.dp).pointerInput(Unit) { detectTapGestures(onTap = { onAsk() }, onLongPress = { onTalk() }) }, contentAlignment = Alignment.Center) {
            com.nizam.app.ui.design.LivingReactor(state = if (halo.active || expert != null) com.nizam.app.ui.design.PulseState.THINKING else com.nizam.app.feature.atlascore.PresenceManager.pulse(), modifier = Modifier.parallax(0.6f), diameter = 54.dp)
        }
        Box(Modifier.size(56.dp).pointerInput(Unit) { detectTapGestures(onTap = { onLycho() }, onLongPress = { onLychoHold() }) }, contentAlignment = Alignment.Center) {
            com.nizam.app.ui.design.LychoOrb(mindState, Modifier.parallax(0.6f), diameter = 50.dp)
        }
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(22.dp)).pressable { onAll() }, contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Apps, "All tools", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
