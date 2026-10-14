package com.nizam.app.ui.screens.hubs

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.ui.design.ActionTile
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.EmptySpec
import com.nizam.app.ui.design.EmptyState
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.Radius
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.arrive
import com.nizam.app.ui.design.pressable
import com.nizam.app.ui.design.wash
import com.nizam.app.ui.nav.Routes

/** The hubs, grouped the way people think about their day. */
val HUB_GROUPS: List<Pair<String, List<Hub>>> = listOf(
    "Everyday" to listOf(Hub.Money, Hub.Health, Hub.Deen, Hub.Home),
    "Grow & enjoy" to listOf(Hub.Work, Hub.Learn, Hub.Media, Hub.Create),
    "People, places & tools" to listOf(Hub.Social, Hub.Travel, Hub.Memory, Hub.Tools),
).let { g -> g + listOfNotNull(Hub.entries.filter { h -> g.none { h in it.second } }.takeIf { it.isNotEmpty() }?.let { "More" to it }) }

/**
 * The Life tab: a search box, what you used recently, the twelve hubs, and your own trackers.
 * Everything in Atlas is at most two taps from here.
 */
@Composable
fun LifeScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    val specs by container.dynamicRepository.specs().collectAsState(initial = emptyList())
    val tp = remember { ctx.getSharedPreferences("tracker_use", 0) }
    var showAll by rememberSaveable { mutableStateOf(false) }
    val today = remember { java.time.LocalDate.now().toString() }
    val hour = remember { java.time.LocalTime.now().hour }
    val recent = remember { RecentFeatures.recent(ctx).mapNotNull { HubRegistry.byRoute(it) } }
    val results = remember(query) { HubRegistry.search(query) }
    val cols = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.let { w -> if (w >= 840) 4 else if (w >= 600) 3 else 2 }

    AtlasScreen(title = "Life", subtitle = "Everything in Atlas, sorted into hubs. Search finds any tool.",
        actions = {
            Icon(Icons.Outlined.Tune, "Arrange modules", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clip(Radius.chip).pressable { onOpen(Routes.MODULES_CLASSIC) }.padding(Space.s))
        }) {
        item("search") { SearchPill(query, { query = it }, Modifier.padding(horizontal = Space.page)) }

        if (query.isNotBlank()) {
            if (results.isEmpty()) item("none") {
                EmptyState(EmptySpec(Icons.Outlined.SearchOff, "Nothing called that yet",
                    "Try another word, or ask Atlas: it can reach every feature.", "Ask Atlas") { onOpen(Routes.ASSISTANT) })
            }
            results.forEachIndexed { i, f ->
                item("r-${f.route}") {
                    ListRow(f.title, Modifier.arrive(i), supporting = "${f.hub.label} · ${f.blurb}", icon = f.icon, tint = f.hub.tint()) { onOpen(f.route) }
                }
            }
            return@AtlasScreen
        }

        if (recent.isNotEmpty()) {
            item("recent-h") { SectionHeader("Jump back in") }
            item("recent") {
                LazyRow(contentPadding = PaddingValues(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    items(recent, key = { it.route }) { f -> RecentChip(f) { onOpen(f.route) } }
                }
            }
        }

        // twelve hubs in three plain-language groups; more columns on wide screens and in landscape
        val mine = com.nizam.app.feature.modes.Wizard.interests(ctx)
        if (mine.isNotEmpty()) {
            item("mine-h") { SectionHeader("Your suites") }
            mine.chunked(cols).forEachIndexed { row, chunk ->
                item("mine-$row") {
                    Row(Modifier.padding(horizontal = Space.page, vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        chunk.forEachIndexed { i, hub -> HubCard(hub, Modifier.weight(1f).arrive(i)) { onOpen(Routes.hub(hub.name)) } }
                        repeat(cols - chunk.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        val modeInfo = com.nizam.app.feature.modes.AutoModes.info(com.nizam.app.feature.modes.AutoModes.current(ctx))
        if (modeInfo.hubs.isNotEmpty()) {
            item("mode-h") { SectionHeader("For ${modeInfo.name} mode ${modeInfo.emoji}") }
            modeInfo.hubs.chunked(cols).forEachIndexed { row, chunk ->
                item("mode-$row") {
                    Row(Modifier.padding(horizontal = Space.page, vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        chunk.forEachIndexed { i, hub -> HubCard(hub, Modifier.weight(1f).arrive(i)) { onOpen(Routes.hub(hub.name)) } }
                        repeat(cols - chunk.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
        val allowedHubs = com.nizam.app.feature.platform2.Profiles.allowedHubs(ctx)
        // De-cluttered: a hub already shown in "Your suites" or the current mode isn't repeated
        // again in the groups below — each hub appears once, so the browse reads clean.
        val shownHubs = (mine + modeInfo.hubs).toSet()
        HUB_GROUPS.map { (l, hs) -> l to hs.filter { it !in shownHubs && (allowedHubs == null || it in allowedHubs) } }.filter { it.second.isNotEmpty() }.forEach { (label, hubs) ->
            item("hubs-h-$label") { SectionHeader(label) }
            hubs.chunked(cols).forEachIndexed { row, chunk ->
                item("hubs-$label-$row") {
                    Row(Modifier.padding(horizontal = Space.page, vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        chunk.forEachIndexed { i, hub ->
                            HubCard(hub, Modifier.weight(1f).arrive(row * cols + i)) { onOpen(Routes.hub(hub.name)) }
                        }
                        repeat(cols - chunk.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }

        item("trackers-h") { SectionHeader("Your trackers", action = "Manage") { onOpen(Routes.MODULES_CLASSIC) } }
        if (specs.isEmpty()) item("trackers-empty") {
            ListRow("Create a tracker", supporting = "Anything you want to count or log: ask Atlas to build one",
                icon = Icons.Outlined.Add) { onOpen(Routes.ASSISTANT) }
        }
        if (specs.isNotEmpty()) {
            fun relevance(name: String): Int {
                val n = name.lowercase(); var r = tp.getInt("n_" + n, 0) * 2
                if (hour >= 21 || hour < 5) { if ("sleep" in n || "diary" in n || "azkar" in n) r += 6 }
                if (hour in 4..9) { if ("azkar" in n || "water" in n || "gym" in n) r += 6 }
                if (hour in 9..17) { if ("focus" in n || "study" in n || "task" in n) r += 6 }
                return r
            }
            val ordered = specs.sortedByDescending { relevance(it.name) }
            val shown = if (showAll) ordered else ordered.take(9)
            shown.chunked(3).forEachIndexed { row, chunk ->
                item("tt-$row") {
                    Row(Modifier.padding(horizontal = Space.page, vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        chunk.forEach { spec ->
                            val tint = runCatching { Color(android.graphics.Color.parseColor(spec.colorHex)) }.getOrDefault(MaterialTheme.colorScheme.primary)
                            val seen = tp.getString("d_" + spec.name.lowercase(), "") == today
                            Column(Modifier.weight(1f).aspectRatio(1f).clip(Radius.tile).background(wash(tint, 0.16f))
                                .pressable {
                                    tp.edit().putInt("n_" + spec.name.lowercase(), tp.getInt("n_" + spec.name.lowercase(), 0) + 1).putString("d_" + spec.name.lowercase(), today).apply()
                                    onOpen(Routes.dynamic(spec.key))
                                }.padding(Space.m), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                                Text(spec.emoji, style = MaterialTheme.typography.headlineSmall)
                                Spacer(Modifier.height(Space.xs))
                                Text(spec.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (seen) "opened today" else "not today", style = MaterialTheme.typography.labelSmall,
                                    color = if (seen) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                        repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
            if (specs.size > 9) item("tt-more") {
                Text(if (showAll) "Show fewer" else "All trackers (${specs.size})", Modifier.pressable { showAll = !showAll }.padding(horizontal = Space.page, vertical = Space.m),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        item("pad") { Spacer(Modifier.height(Space.xl)) }
    }
}

@Composable
private fun HubCard(hub: Hub, modifier: Modifier, onClick: () -> Unit) {
    val tint = hub.tint()
    val c = MaterialTheme.colorScheme
    val features = HubRegistry.of(hub)
    Column(modifier.aspectRatio(1.18f).clip(Radius.tile)
        .background(Brush.linearGradient(listOf(wash(tint, 0.22f), c.surface)))
        .pressable(onClick = onClick).padding(Space.l)) {
        if (com.nizam.app.ui.design.Style.s.hubIcons == "3D" && com.nizam.app.ui.design.Style.s.art)
            com.nizam.app.ui.design.Art3D(com.nizam.app.ui.design.Art.forHub(hub), 58.dp, float = false, shadow = false)
        else Box(Modifier.size(38.dp).clip(com.nizam.app.ui.design.hubShape(hub)).background(wash(tint, 0.3f)), contentAlignment = Alignment.Center) {
            Icon(hub.icon, null, tint = tint, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.weight(1f))
        Text(hub.label, style = MaterialTheme.typography.titleLarge)
        Text(features.take(3).joinToString(" · ") { it.title }, style = MaterialTheme.typography.bodySmall,
            color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RecentChip(f: Feature, onClick: () -> Unit) {
    val tint = f.hub.tint()
    Row(Modifier.clip(Radius.pill).background(MaterialTheme.colorScheme.surface).pressable(onClick = onClick)
        .padding(start = Space.s, end = Space.l, top = Space.s, bottom = Space.s), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(28.dp).clip(Radius.pill).background(wash(tint, 0.22f)), contentAlignment = Alignment.Center) {
            Icon(f.icon, null, tint = tint, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(Space.s))
        Text(f.title, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** A rounded search field with a clear button. */
@Composable
fun SearchPill(value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, hint: String = "Search every feature") {
    val c = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().height(50.dp).clip(Radius.pill).background(c.surfaceVariant).padding(horizontal = Space.l),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Search, null, tint = c.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(Space.s))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(hint, style = MaterialTheme.typography.bodyLarge, color = c.onSurfaceVariant)
            BasicTextField(value, onChange, singleLine = true, textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.onSurface),
                cursorBrush = SolidColor(c.primary), modifier = Modifier.fillMaxWidth())
        }
        if (value.isNotEmpty()) Icon(Icons.Outlined.Close, "Clear", tint = c.onSurfaceVariant,
            modifier = Modifier.size(20.dp).clip(Radius.pill).pressable { onChange("") })
    }
}

/**
 * One hub: a search box, its big tiles, your favourites here, then everything else in short,
 * collapsible sections (Logs & trackers, Tools, Ask AI…) instead of one long list.
 */
@Composable
fun HubPage(hubName: String, container: AppContainer, onOpen: (String) -> Unit) {
    val hub = Hub.entries.firstOrNull { it.name == hubName } ?: Hub.Tools
    val ctx = LocalContext.current
    var q by rememberSaveable { mutableStateOf("") }
    val top = HubRegistry.top(hub)
    val groups = remember(hub) { HubRegistry.grouped(hub) }
    val favs = remember(hub) { HubRegistry.of(hub).filter { RecentFeatures.uses(ctx, it.route) >= 2 }
        .sortedByDescending { RecentFeatures.uses(ctx, it.route) }.take(6) }
    val open = remember(hub) { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    val cols = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.let { w -> if (w >= 840) 4 else if (w >= 600) 3 else 2 }
    var tab by rememberSaveable(hub.name) { mutableStateOf("Overview") }
    AtlasScreen(title = hub.label, hub = hub, subtitle = "${HubRegistry.of(hub).size} tools · overview, then every tool") {
        item("q") { SearchPill(q, { q = it }, Modifier.padding(horizontal = Space.page), hint = "Search ${hub.label}") }
        if (q.isBlank()) item("tabs") {
            Row(Modifier.padding(horizontal = Space.page, vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                listOf("Overview", "All tools").forEach { t ->
                    androidx.compose.material3.FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t) })
                }
            }
        }
        if (q.isNotBlank()) {
            val hits = HubRegistry.of(hub).filter { f -> listOf(f.title, f.blurb, f.words).any { it.contains(q.trim(), true) } }
            if (hits.isEmpty()) item("none") { ListRow("Nothing here called that", supporting = "Search all of Atlas instead", icon = Icons.Outlined.Search) { onOpen(Routes.SEARCH) } }
            hits.forEach { f -> item("h-${f.route}") { ListRow(f.title, supporting = f.blurb, icon = f.icon, tint = hub.tint()) { onOpen(f.route) } } }
            return@AtlasScreen
        }
        if (tab == "Overview") item("dash") {
            Box(Modifier.padding(horizontal = Space.page, vertical = Space.xs)) { com.nizam.app.feature.dash.Dash.ForHub(hub, container, onOpen) }
        }
        if (tab == "Overview") top.chunked(cols).forEachIndexed { row, chunk ->
            item("top-$row") {
                Row(Modifier.padding(horizontal = Space.page, vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    chunk.forEachIndexed { i, f ->
                        ActionTile(f.icon, f.title, Modifier.weight(1f).arrive(row * cols + i).then(com.nizam.app.ui.design.PeekMod(f.title, f.blurb, hub)), tint = hub.tint(), hint = f.blurb) { onOpen(f.route) }
                    }
                    repeat(cols - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        if (tab == "Overview" && favs.isNotEmpty()) {
            item("fav-h") { SectionHeader("Your favourites here") }
            item("fav") {
                LazyRow(contentPadding = PaddingValues(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    items(favs, key = { it.route }) { f -> RecentChip(f) { onOpen(f.route) } }
                }
            }
        }
        if (tab == "All tools") groups.forEachIndexed { gi, (name, list) ->
            val expanded = open[name] ?: (gi == 0 && list.size <= 8)
            item("g-$name") {
                Row(Modifier.fillMaxWidth().pressable { open[name] = !expanded }.padding(horizontal = Space.page, vertical = Space.m),
                    verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Text("${list.size}  ${if (expanded) "▾" else "▸"}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (expanded) list.sortedByDescending { RecentFeatures.uses(ctx, it.route) }.forEach { f ->
                item("m-${f.route}") {
                    var pinnedNow by remember(f.route) { mutableStateOf(com.nizam.app.feature.modes.Pins.isPinned(ctx, f.route)) }
                    ListRow(f.title, Modifier.then(com.nizam.app.ui.design.PeekMod(f.title, f.blurb, hub)), supporting = f.blurb, icon = f.icon, tint = hub.tint(),
                        trailing = { Text(if (pinnedNow) "📌" else "📍", Modifier.pressable { com.nizam.app.feature.modes.Pins.togglePin(ctx, f.route); pinnedNow = !pinnedNow }.padding(Space.s)) }) { onOpen(f.route) }
                }
            }
        }
        item("pad") { Spacer(Modifier.height(Space.xl)) }
    }
}
