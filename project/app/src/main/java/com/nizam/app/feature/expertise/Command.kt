package com.nizam.app.feature.expertise

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.net.Router
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.LychoOrb
import com.nizam.app.ui.design.OrbState
import com.nizam.app.ui.design.pressable
import java.time.LocalDate
import java.time.LocalTime

/** The command room's own palette: deep black, warm gold, quiet greens for what's live. */
private object Cmd {
    val bg = Color(0xFF0A0A0B)
    val card = Color(0xFF151516)
    val line = Color(0xFF2A2A2C)
    val gold = Color(0xFFD4AF37)
    val goldDim = Color(0xFF8C7530)
    val goldLine = Color(0xFF4A3F1E)
    val ink = Color(0xFFF2EFE8)
    val muted = Color(0xFF9A978F)
    val green = Color(0xFF5FCB8A)
    val greenBg = Color(0xFF10251A)
    val label = TextStyle(fontSize = 12.sp, letterSpacing = 3.sp, fontWeight = FontWeight.Medium, fontFamily = FontFamily.Monospace)
}

/**
 * The LYCHO orb's command room, in the layout you liked from LYCHO: one Ask box routed to the best model and
 * the right expertise, which providers are live, the numbers, what you asked lately, and everything the mind
 * can do, with Agents, Chats and Settings one tap away. It is all Atlas underneath.
 */
@Composable
fun CommandRoom(container: AppContainer, open: (String) -> Unit) {
    var tab by remember { mutableStateOf("command") }
    BackHandler(tab != "command") { tab = "command" }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { Expertise.pin(null) } }
    Column(Modifier.fillMaxSize().background(Cmd.bg)) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                "agents" -> ExpertiseScreen(container, open)
                "chats" -> Box(Modifier.fillMaxSize().statusBarsPadding()) { com.nizam.app.feature.agent.CuratorScreen(container) }
                "settings" -> CommandSettings(container, open)
                else -> CommandHome(container, open) { tab = it }
            }
        }
        Row(Modifier.fillMaxWidth().background(Color(0xFF0F0F10)).border(BorderStroke(0.5.dp, Cmd.line)).navigationBarsPadding()
            .padding(vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            listOf(Triple("command", "COMMAND", Icons.Outlined.Hexagon), Triple("agents", "AGENTS", Icons.Outlined.Memory),
                Triple("chats", "CHATS", Icons.Outlined.ChatBubbleOutline), Triple("settings", "SETTINGS", Icons.Outlined.Tune)).forEach { (k, l, ic) ->
                val on = tab == k
                Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable { tab = k }.padding(horizontal = 14.dp, vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(ic, l, tint = if (on) Cmd.gold else Cmd.muted.copy(alpha = 0.6f), modifier = Modifier.size(26.dp))
                    Text(l, style = Cmd.label.copy(fontSize = 10.sp, letterSpacing = 2.sp), color = if (on) Cmd.gold else Cmd.muted.copy(alpha = 0.6f),
                        modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun CommandHome(container: AppContainer, open: (String) -> Unit, go: (String) -> Unit) {
    val ctx = LocalContext.current
    val halo by com.nizam.app.feature.agentic.AgentHalo.state.collectAsState()
    val turns by container.agentMemory.turns().collectAsState(initial = emptyList())
    val name by container.settingsRepository.displayName.collectAsState(initial = "")
    var q by remember { mutableStateOf("") }
    var providers by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    LaunchedEffect(Unit) {
        com.nizam.app.ui.design.guard {
            val live = runCatching { Router.endpoints(container.settingsRepository, ctx, Router.Lane.CHAT) }.getOrDefault(emptyList())
            fun on(word: String) = live.any { it.name.contains(word, true) || it.url.contains(word, true) }
            providers = listOf("GOOGLE GEMINI" to on("gemini"), "GROQ" to on("groq"), "OPENROUTER" to on("openrouter")) +
                (if (live.any { it.kind == "LOCAL" }) listOf("ON THIS PHONE" to true) else emptyList())
        }
    }
    val experts = remember { Expertise.all(ctx).size }
    val today = LocalDate.now().toString()
    val chats = turns.count { it.fromUser }
    val repliesToday = remember(turns.size) { turns.count { !it.fromUser && it.localDate == today } }
    val recent = remember(turns.size) {
        turns.withIndex().filter { it.value.fromUser }.takeLast(4).reversed().map { (i, t) -> t.text to turns.getOrNull(i + 1)?.takeIf { !it.fromUser }?.text.orEmpty() }
    }
    val greeting = when (LocalTime.now().hour) { in 4..11 -> "GOOD MORNING"; in 12..16 -> "GOOD AFTERNOON"; in 17..20 -> "GOOD EVENING"; else -> "GOOD NIGHT" }

    fun ask(text: String) {
        if (text.isBlank()) return
        Expertise.pick(ctx, text)?.let { Expertise.pin(it) }
        Expertise.queueAsk(text.trim(), send = true)
        q = ""
        go("chats")
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 22.dp), verticalAlignment = Alignment.CenterVertically) {
            LychoOrb(if (halo.active) OrbState.Working else OrbState.Idle, diameter = 58.dp)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                Text(greeting, style = Cmd.label, color = Cmd.muted)
                Text(name.ifBlank { "Atlas" }.uppercase(), color = Cmd.ink, fontSize = 30.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Outlined.Shield, "Master panel", tint = Cmd.gold, modifier = Modifier.size(36.dp).clip(CircleShape).clickable { open("nx/engineroom") }.padding(4.dp))
        }

        // Ask: routed to the best model, thinking with the right expertise
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Color(0xFF14130F)).border(1.dp, Cmd.goldLine, RoundedCornerShape(22.dp))
            .padding(20.dp)) {
            Text("ASK LYCHO PRIME", style = Cmd.label, color = Cmd.gold)
            Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 14.dp).heightIn(min = 34.dp)) {
                if (q.isEmpty()) Text("Plan, analyse, write, decide…", color = Cmd.muted.copy(alpha = 0.6f), fontSize = 22.sp, fontFamily = FontFamily.Serif)
                BasicTextField(q, { q = it }, Modifier.fillMaxWidth(),
                    textStyle = TextStyle(color = Cmd.ink, fontSize = 20.sp, fontFamily = FontFamily.Serif), cursorBrush = SolidColor(Cmd.gold),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { ask(q) }))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Expertise.pick(ctx, q)?.let { "Thinking as ${it.name}" } ?: "Routed to the best model automatically",
                    color = Cmd.muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                Box(Modifier.size(56.dp).clip(CircleShape).background(if (q.isBlank()) Cmd.goldDim else Cmd.gold).clickable { ask(q) },
                    contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.ArrowUpward, "Ask", tint = Color(0xFF1A1405))
                }
            }
        }

        Row(Modifier.padding(top = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            providers.forEach { (p, on) ->
                Text("$p • ${if (on) "ON" else "OFF"}", style = Cmd.label.copy(fontSize = 11.sp, letterSpacing = 1.5.sp),
                    color = if (on) Cmd.green else Cmd.muted,
                    modifier = Modifier.clip(CircleShape).background(if (on) Cmd.greenBg else Cmd.card)
                        .border(1.dp, if (on) Cmd.green.copy(alpha = 0.4f) else Cmd.line, CircleShape).clickable { open("keys") }
                        .padding(horizontal = 14.dp, vertical = 7.dp))
            }
        }

        Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("$experts" to "AGENTS", "$chats" to "CHATS", "$repliesToday" to "REPLIES TODAY").forEachIndexed { i, (v, l) ->
                Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(Cmd.card).border(1.dp, Cmd.line, RoundedCornerShape(18.dp))
                    .clickable { go(if (i == 0) "agents" else "chats") }.padding(16.dp)) {
                    Text(v, color = Cmd.gold, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text(l, style = Cmd.label.copy(fontSize = 10.sp, letterSpacing = 2.sp), color = Cmd.muted, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                }
            }
        }

        if (recent.isNotEmpty()) {
            Text("RECENT", style = Cmd.label, color = Cmd.muted, modifier = Modifier.padding(top = 28.dp, bottom = 10.dp))
            recent.forEach { (asked, answer) ->
                Column(Modifier.fillMaxWidth().padding(bottom = 8.dp).clip(RoundedCornerShape(18.dp)).background(Cmd.card)
                    .border(1.dp, Cmd.line, RoundedCornerShape(18.dp)).clickable { go("chats") }.padding(16.dp)) {
                    Text(asked, color = Cmd.ink, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (answer.isNotBlank()) Text(answer.replace(Regex("[#*_`>]"), "").replace('\n', ' '), color = Cmd.muted, fontSize = 14.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        Text("LIVE NOW", style = Cmd.label, color = Cmd.muted, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
        val core = listOf(
            Tile(Icons.Outlined.Memory, "Agents", "$experts specialists across 20 sectors") { go("agents") },
            Tile(Icons.Outlined.ChatBubbleOutline, "Chat", "Talk to any agent, with history") { go("chats") },
            Tile(Icons.Outlined.Shuffle, "Smart Router", "Gemini · Groq · OpenRouter, auto-switching") { open("nx/hybridrouter") },
            Tile(Icons.Outlined.Edit, "Agent Studio", "Build and edit your own agents") { open("nx/expertisestudio") },
            Tile(Icons.Outlined.Shield, "Master Panel", "Owner control room") { open("nx/engineroom") },
            Tile(Icons.Outlined.MonitorHeart, "Pulse", "Everything happening, live") { open("nx/pulse") },
        )
        TileGrid(core)

        Text("ENGINES", style = Cmd.label, color = Cmd.muted, modifier = Modifier.padding(top = 24.dp, bottom = 10.dp))
        TileGrid(MindModules.all.map { sp -> Tile(sp.icon, sp.title, sp.blurb) { open("nx/${sp.id}") } })
        Spacer(Modifier.height(28.dp))
    }
}

private class Tile(val icon: ImageVector, val title: String, val blurb: String, val onClick: () -> Unit)

@Composable
private fun TileGrid(tiles: List<Tile>) {
    tiles.chunked(2).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            row.forEach { t ->
                Column(Modifier.weight(1f).heightIn(min = 132.dp).clip(RoundedCornerShape(20.dp)).background(Cmd.card)
                    .border(1.dp, Cmd.line, RoundedCornerShape(20.dp)).pressable(onClick = t.onClick).padding(18.dp)) {
                    Icon(t.icon, null, tint = Cmd.gold, modifier = Modifier.size(28.dp))
                    Text(t.title, color = Cmd.ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
                    Text(t.blurb, color = Cmd.muted, fontSize = 14.sp, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun CommandSettings(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    var auto by remember { mutableStateOf(Expertise.autoOn(ctx)) }
    var zero by remember { mutableStateOf(ZeroCost.on(ctx)) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).statusBarsPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("SETTINGS", style = Cmd.label, color = Cmd.gold, modifier = Modifier.padding(bottom = 8.dp))
        @Composable fun toggle(title: String, body: String, on: Boolean, set: (Boolean) -> Unit) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Cmd.card).border(1.dp, Cmd.line, RoundedCornerShape(18.dp)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Cmd.ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(body, color = Cmd.muted, fontSize = 13.sp)
                }
                Switch(on, set, colors = SwitchDefaults.colors(checkedTrackColor = Cmd.gold, checkedThumbColor = Color(0xFF1A1405)))
            }
        }
        toggle("Pick the expert on its own", "Atlas thinks like the right expert for each question", auto) { auto = it; Expertise.setAuto(ctx, it) }
        toggle("Zero-cost autopilot", "Free tiers only: paid OpenRouter models are skipped", zero) { zero = it; ZeroCost.set(ctx, it) }
        listOf(
            Triple(Icons.Outlined.Key, "API keys", "keys"), Triple(Icons.Outlined.Shuffle, "AI routing", "nx/hybridrouter"),
            Triple(Icons.Outlined.Memory, "Engine room", "nx/engineroom"), Triple(Icons.Outlined.Tune, "Privacy dial", "nx/privacydial"),
            Triple(Icons.Outlined.Gavel, "Life laws", "nx/lifelaws"), Triple(Icons.Outlined.TheaterComedy, "Personas", "nx/personas"),
            Triple(Icons.Outlined.TaskAlt, "Approvals", "nx/approvalinbox"), Triple(Icons.Outlined.Healing, "Heal", "nx/heal"),
        ).forEach { (ic, t, r) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Cmd.card).border(1.dp, Cmd.line, RoundedCornerShape(18.dp))
                .clickable { open(r) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ic, null, tint = Cmd.gold, modifier = Modifier.size(22.dp))
                Text(t, color = Cmd.ink, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(start = 14.dp))
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, null, tint = Cmd.muted, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.width(1.dp))
    }
}

val CommandSpecs: List<com.nizam.app.feature.nx.Spec> get() = listOf(
    ScreenSpec("command", "Command room", "Ask, agents, chats and every engine, in the LYCHO layout", Icons.Outlined.Hexagon, Hub.Work,
        "command room lycho home prime agents chats master panel") { c, o -> CommandRoom(c, o) },
)
