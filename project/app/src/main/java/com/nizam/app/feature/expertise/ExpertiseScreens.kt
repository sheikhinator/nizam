package com.nizam.app.feature.expertise

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nizam.app.AppContainer
import com.nizam.app.feature.agentic.AgentHalo
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.EmptySpec
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import java.text.DateFormat
import java.util.Date

/** Registered in the catalogue, so search, hubs and Ask can all reach them. */
object ExpertiseSpecs {
    val all = listOf(
        ScreenSpec("find", "Find", "Records, screens, your phone and the web, with quick filters", Icons.Outlined.Search, Hub.Tools,
            "find search records sleep focus wins azkar everything") { c, open -> com.nizam.app.ui.screens.search.SearchScreen(c, open) },
        ScreenSpec("atlastalk", "Talk to Atlas", "Ask, plan or get things done in one conversation", Icons.Outlined.AutoAwesome, Hub.Tools,
            "talk chat ask atlas curator assistant do powers command") { c, _ -> com.nizam.app.feature.agent.CuratorScreen(c) },
        ScreenSpec("expertise", "Atlas expertise", "378 kinds of expert thinking, all one Atlas", Icons.Outlined.Psychology, Hub.Work,
            "agents expertise expert specialist lawyer accountant coach consultant advisor lycho workforce think as") { c, open -> ExpertiseScreen(c, open) },
        ScreenSpec("expertisestudio", "Expertise Studio", "Write a kind of expertise of your own for Atlas to think with", Icons.Outlined.EditNote, Hub.Work,
            "agent studio create agent custom expertise build my own expert persona lycho") { c, _ -> ExpertiseStudio(c) },
        ScreenSpec("engineroom", "Engine room", "Atlas's machinery: model calls, providers, expertise use", Icons.Outlined.Memory, Hub.Tools,
            "master panel engine room router log model calls providers ai usage lycho owner") { _, _ -> EngineRoom() },
        ScreenSpec("pulse", "Atlas Pulse", "Everything Atlas is thinking and doing, live", Icons.Outlined.MonitorHeart, Hub.Tools,
            "pulse activity live events nervous system what is atlas doing lycho") { _, _ -> PulseScreen() },
    )
}

@Composable
fun ExpertiseScreen(container: AppContainer, open: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    var sector by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf(Expertise.takeDesk(ctx)) }
    var auto by remember { mutableStateOf(Expertise.autoOn(ctx)) }
    val sectors = remember { Expertise.sectors(ctx) }

    val desk = chosen
    if (desk != null) {
        ExpertDesk(desk, container, open) { chosen = null }
        return
    }

    val list = remember(q, sector) { Expertise.search(ctx, q, sector) }
    AtlasScreen(title = "Atlas expertise", subtitle = "One Atlas, thinking like the expert each question needs", hub = Hub.Work,
        actions = { IconButton(onClick = { open(com.nizam.app.feature.nx.Nx.route("expertisestudio")) }) { Icon(Icons.Outlined.Add, "Write your own expertise") } },
        empty = if (list.isEmpty()) EmptySpec(Icons.Outlined.Search, "Nothing matches", "Try a different word, or clear the sector.") else null) {
        item("auto") {
            Group(Modifier.padding(horizontal = Space.page)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Choose expertise on its own", style = MaterialTheme.typography.titleMedium)
                        Text("Off by default for the fastest replies. When on, Atlas thinks like the right expert and tells you which. Also in the chat box as Expert.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(auto, { auto = it; Expertise.setAuto(ctx, it) })
                }
            }
        }
        item("search") {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = Space.s),
                placeholder = { Text("Search ${Expertise.all(ctx).size} kinds of expertise") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) }, singleLine = true)
        }
        item("sectors") {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = Space.page, vertical = Space.xs),
                horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Capsule("All", sector == null) { sector = null }
                sectors.forEach { (s, n) -> Capsule("$s · $n", sector == s) { sector = if (sector == s) null else s } }
            }
        }
        items(list.size, key = { list[it].id }) { i ->
            val e = list[i]
            ListRow(e.name, supporting = e.role, icon = if (e.sector == "Core") Icons.Outlined.AutoAwesome else e.hub.icon,
                tint = e.hub.tint(), onClick = { chosen = e })
        }
    }
}

@Composable
fun PulseScreen() {
    val events by Pulse.recent.collectAsState()
    val halo by AgentHalo.state.collectAsState()
    val thinking by Expertise.current.collectAsState()
    val fmt = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    AtlasScreen(title = "Atlas Pulse", subtitle = "What Atlas is thinking and doing, everywhere, as it happens", hub = Hub.Tools,
        empty = if (events.isEmpty() && !halo.active) EmptySpec(Icons.Outlined.MonitorHeart, "All quiet",
            "Ask Atlas something or let a routine run, and its activity appears here live.") else null) {
        if (halo.active) item("now") {
            Group(Modifier.padding(horizontal = Space.page)) {
                Text("Now", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(halo.label, style = MaterialTheme.typography.titleMedium)
                thinking?.let { Text("Thinking as ${it.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                halo.steps.dropLast(1).takeLast(5).forEach {
                    Text("· $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (events.isNotEmpty()) item("recent") { SectionHeader("Recent", Modifier.padding(horizontal = Space.page)) }
        items(events.size, key = { "${events[it].at}-$it" }) { i ->
            val e = events[i]
            ListRow(e.title, supporting = listOf(fmt.format(Date(e.at)), e.detail).filter { it.isNotBlank() }.joinToString(" · "),
                icon = when (e.kind) { Pulse.Kind.ALERT -> Icons.Outlined.MonitorHeart; else -> Icons.Outlined.AutoAwesome })
        }
    }
}
