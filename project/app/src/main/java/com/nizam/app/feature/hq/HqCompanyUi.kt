package com.nizam.app.feature.hq

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.feature.agent.ApprovalInbox
import com.nizam.app.feature.agent.Crew
import com.nizam.app.feature.wild.AgentMeter
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private fun levelColor(l: Int) = Color(0xFF000000.toInt() or Company.LEVEL_COLORS[l.coerceIn(0, Company.LEVEL_COLORS.size - 1)])

@Composable
private fun Chip(text: String, color: Color, filled: Boolean = true) {
    Text(text, color = if (filled) Color.White else color, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (filled) color else color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 3.dp))
}

@Composable
private fun Kpi(label: String, value: String, sub: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp)) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (sub.isNotBlank()) Text(sub, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun stars(v: Float) = "★".repeat(v.toInt().coerceIn(0, 5)) + (if (v - v.toInt() >= 0.5f) "½" else "")

/** The company at a glance: numbers, people, the task board, office chat, the wiki and the rules. */
@Composable
fun CompanySheet(container: AppContainer, startTab: String, chatter: List<String>, openAgent: (String) -> Unit, openHire: () -> Unit, open: (String) -> Unit) {
    var tab by remember { mutableStateOf(startTab) }
    Text("🏢 Atlas HQ", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("overview" to "📊 Overview", "insights" to "🧭 Insights", "people" to "👥 People", "goals" to "🎯 Goals", "ops" to "💚 Team", "boardroom" to "🏛️ Boardroom", "clients" to "🤝 Clients", "rival" to "⚔️ Rival", "board" to "📋 Board", "chat" to "💬 Chat", "wiki" to "📚 Wiki", "settings" to "⚙️ Rules").forEach { (id, l) ->
            Capsule(l, tab == id) { tab = id }
        }
    }
    Box(Modifier.fillMaxWidth().height(560.dp)) {
        when (tab) {
            "overview" -> Overview(container, openAgent, openHire) { tab = it }
            "insights" -> CompanyInsights(container)
            "people" -> People(container, openAgent, openHire)
            "goals" -> GoalsTab(container)
            "ops" -> PeopleOpsTab(container, openAgent)
            "boardroom" -> BoardroomTab(container)
            "clients" -> ClientsTab(container)
            "rival" -> RivalTab(container)
            "board" -> BoardTab(container, open)
            "chat" -> ChatTab(chatter)
            "wiki" -> WikiTab(container)
            else -> RulesTab(container)
        }
    }
}

@Composable
private fun Overview(container: AppContainer, openAgent: (String) -> Unit, openHire: () -> Unit, goTab: (String) -> Unit) {
    val c = LocalContext.current
    val work = rememberWorkScope()
    val version = Crew.version
    var refresh by remember { mutableIntStateOf(0) }
    val team = remember(version, refresh) { Crew.agents(c) }
    val data = remember(version, refresh) {
        val stats = team.associate { it.id to Company.stats(c, it) }
        val profiles = team.associate { it.id to Company.profile(c, it) }
        Triple(stats, profiles, Company.proposals(c))
    }
    val (stats, profiles, proposals) = data
    val board = remember(version, refresh) { Company.board(c) }
    val today = team.sumOf { AgentMeter.today(c, it.name).first }
    val cap = Company.dailyCap(c)
    val best = team.maxByOrNull { (stats[it.id]?.stars ?: 0f) * 10 + (stats[it.id]?.done ?: 0) }
    Column(Modifier.fillMaxSize2().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Chip(if (Company.open(c)) "● Open ${Company.startHour(c)}:00–${Company.endHour(c)}:00" else "● Closed", if (Company.open(c)) Color(0xFF16A34A) else Color(0xFF6B7280))
            Spacer(Modifier.width(6.dp))
            Text(DateFormat.getDateInstance(DateFormat.FULL).format(Date()), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // ask the room: whoever knows best picks it up
        var ask by remember { mutableStateOf("") }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(ask, { ask = it }, Modifier.weight(1f), placeholder = { Text("🙋 Ask the room…") }, singleLine = true)
            Spacer(Modifier.width(6.dp))
            Button(enabled = ask.isNotBlank(), onClick = { Hq.enqueue(container, ask); Company.say("lead", "", "Boss asked: " + ask.take(60)); ask = ""; Feedback.show("The right person will pick it up") }) { Text("Ask") }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi("Team", "${team.size}", "${team.count { Crew.busyAgents.containsKey(it.id) }} working now", Modifier.weight(1f))
            Kpi("Doing", "${board.doing.size}", "${board.backlog.size} in the backlog", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi("Done (48h)", "${board.done.size}", "${board.review.size} waiting on you", Modifier.weight(1f))
            Kpi("AI today", "%,d".format(today), if (cap > 0) "of %,d tokens".format(cap) else "no daily cap", Modifier.weight(1f))
        }
        if (cap > 0) LinearProgressIndicator(progress = { (today.toFloat() / cap).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        best?.let { b ->
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color(0x22F59E0B)).clickable { openAgent(b.id) }.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("🏆", fontSize = 26.sp); Spacer(Modifier.width(10.dp))
                Column { Text("Star of the week", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant); Text("${b.emoji} ${b.name} · ${stars(stats[b.id]?.stars ?: 0f)}", fontWeight = FontWeight.Bold) }
            }
        }
        // spend by department this week
        Spacer(Modifier.height(14.dp))
        Text("AI spend by department · this week", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        val byDept = Company.DEPTS.map { d -> d to team.filter { profiles[it.id]?.dept == d.id }.sumOf { stats[it.id]?.weekTokens ?: 0 } }
        val max = (byDept.maxOfOrNull { it.second } ?: 1).coerceAtLeast(1)
        byDept.forEach { (d, v) ->
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${d.emoji} ${d.name}", fontSize = 12.sp, modifier = Modifier.width(150.dp), maxLines = 1)
                Box(Modifier.weight(1f).height(10.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceVariant)) {
                    Box(Modifier.fillMaxWidth(v.toFloat() / max).fillMaxHeight().clip(RoundedCornerShape(50)).background(Color(android.graphics.Color.parseColor(d.color))))
                }
                Text(" %,d".format(v), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // what the company suggests
        Spacer(Modifier.height(14.dp))
        Text("Suggestions", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        if (proposals.isEmpty()) Text("Nothing to decide right now.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        proposals.forEach { p ->
            Column(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
                Text(p.text, fontSize = 13.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    when (p.kind) {
                        "promote" -> Button(onClick = { Company.setLevel(c, p.id, (profiles[p.id]?.level ?: 1) + 1); refresh++; Feedback.show("Promoted 🎉") }) { Text("Promote") }
                        "hire" -> Button(onClick = openHire) { Text("Hire") }
                        else -> Button(onClick = { openAgent(p.id) }) { Text("Open profile") }
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Get everyone together", fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedButton(onClick = { work.launch(Dispatchers.IO) { Hq.runStandup(c) }; Feedback.show("Stand-up called ☕") }) { Text("☕ Stand-up") }
            OutlinedButton(onClick = { work.launch(Dispatchers.IO) { Hq.runAllHands(c) }; Feedback.show("All-hands called 🎉") }) { Text("🎉 All-hands") }
            OutlinedButton(onClick = { goTab("board") }) { Text("📋 Board") }
        }
        Spacer(Modifier.height(20.dp))
    }
}

private fun Modifier.fillMaxSize2() = this.fillMaxWidth().fillMaxHeight()

/** The org chart: you, the CEO, then each department's head and team. */
@Composable
private fun People(container: AppContainer, openAgent: (String) -> Unit, openHire: () -> Unit) {
    val c = LocalContext.current
    val version = Crew.version
    val team = remember(version) { Crew.agents(c) }
    val prof = remember(version) { team.associate { it.id to Company.profile(c, it) } }
    val st = remember(version) { team.associate { it.id to Company.stats(c, it) } }
    @Composable
    fun Person(a: Crew.Agent, indent: Int) {
        val p = prof[a.id] ?: return
        val s = st[a.id]
        val busy = Crew.busyAgents.containsKey(a.id)
        Row(Modifier.fillMaxWidth().padding(start = (indent * 18).dp, top = 6.dp).clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).clickable { openAgent(a.id) }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or (a.color and 0xFFFFFF).toInt()).copy(alpha = 0.25f)), contentAlignment = Alignment.Center) { Text(a.emoji, fontSize = 20.sp) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(a.name, fontWeight = FontWeight.Bold); Spacer(Modifier.width(6.dp)); Chip(Company.LEVELS[p.level], levelColor(p.level))
                }
                Text("${a.role} · ${stars(s?.stars ?: 0f)} · ${s?.done ?: 0} done", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(9.dp).clip(CircleShape).background(if (busy) Color(0xFF3B82F6) else Color(0xFF9CA3AF)))
        }
    }
    LazyColumn(Modifier.fillMaxWidth()) {
        item {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x22EAB308)).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("👑", fontSize = 22.sp); Spacer(Modifier.width(10.dp))
                Column { Text("You", fontWeight = FontWeight.Bold); Text("Owner · everyone works for you", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        team.firstOrNull { it.lead }?.let { ceo -> item(ceo.id) { Person(ceo, 1) } }
        Company.DEPTS.forEach { d ->
            val members = team.filter { !it.lead && prof[it.id]?.dept == d.id }.sortedByDescending { prof[it.id]?.level ?: 0 }
            if (members.isEmpty()) return@forEach
            item("h" + d.id) { Text("${d.emoji} ${d.name}", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(start = 36.dp, top = 12.dp)) }
            members.forEachIndexed { i, a -> item(a.id) { Person(a, if (i == 0) 2 else 3) } }
        }
        item { Button(onClick = openHire, modifier = Modifier.padding(top = 14.dp)) { Text("➕ Hire someone") } }
    }
}

/** Backlog → Doing → Review → Done, like a real team board. */
@Composable
private fun BoardTab(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current
    val board = remember(Crew.version) { Company.board(c) }
    Row(Modifier.fillMaxSize2().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(Triple("Backlog", board.backlog, Color(0xFF64748B)), Triple("Doing", board.doing, Color(0xFF3B82F6)),
            Triple("Review", board.review, Color(0xFFF59E0B)), Triple("Done", board.done, Color(0xFF22C55E))).forEach { (name, cards, col) ->
            Column(Modifier.width(220.dp).fillMaxHeight().clip(RoundedCornerShape(16.dp)).background(col.copy(alpha = 0.08f)).padding(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(col)); Spacer(Modifier.width(6.dp))
                    Text("$name · ${cards.size}", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                LazyColumn(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    if (cards.isEmpty()) item { Text("Nothing here.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    items(cards) { card ->
                        Column(Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface)
                            .border(if (card.urgent) 1.5.dp else 0.dp, if (card.urgent) Color(0xFFEF4444) else Color.Transparent, RoundedCornerShape(12.dp))
                            .clickable { if (card.thread != null) open("nx/crew") }.padding(8.dp)) {
                            if (card.urgent) Text("🔥 Urgent", color = Color(0xFFEF4444), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(card.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            Text(card.who + " · " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(card.at)), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatTab(chatter: List<String>) {
    val lines = remember(Crew.version, chatter.size) {
        (Company.chat.toList().map { ch -> Triple(ch.at, ch.from, ch.text) }).sortedBy { it.first }
    }
    val c = LocalContext.current
    val names = remember(Crew.version) { Crew.agents(c).associateBy { it.id } }
    LazyColumn(Modifier.fillMaxWidth()) {
        item { Text("The team channel: handovers, questions and wins, plus a bit of office banter.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (lines.isEmpty() && chatter.isEmpty()) item { Text("Quiet so far today.", modifier = Modifier.padding(top = 10.dp)) }
        items(lines.takeLast(40)) { (at, from, text) ->
            val a = names[from]
            Row(Modifier.padding(top = 8.dp)) {
                Text(a?.emoji ?: "🧭", fontSize = 20.sp); Spacer(Modifier.width(8.dp))
                Column {
                    Text((a?.name ?: "Atlas") + "  ·  " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    Text(text, fontSize = 13.sp)
                }
            }
        }
        items(chatter.takeLast(12)) { line -> Text(line, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
private fun WikiTab(container: AppContainer) {
    val c = LocalContext.current
    val entries = remember(Crew.version) { Company.wiki(c).all().sortedByDescending { it.optLong("at") } }
    var openId by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxWidth()) {
        item { Text("Company memory: meeting minutes, stand-ups, all-hands and people changes. The team reads related notes before new work.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (entries.isEmpty()) item { Text("Nothing written yet — it fills up as the team works.", modifier = Modifier.padding(top = 10.dp)) }
        items(entries.take(80), key = { it.optString("id") }) { e ->
            val id = e.optString("id")
            Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable { openId = if (openId == id) null else id }.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Chip(e.optString("kind"), Color(0xFF6366F1), filled = false); Spacer(Modifier.width(6.dp))
                    Text(e.optString("title"), fontWeight = FontWeight.SemiBold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                }
                Text(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(e.optLong("at"))), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (openId == id) Text(e.optString("body"), fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

@Composable
private fun RulesTab(container: AppContainer) {
    val c = LocalContext.current
    var start by remember { mutableFloatStateOf(Company.startHour(c).toFloat()) }
    var end by remember { mutableFloatStateOf(Company.endHour(c).toFloat()) }
    var flags by remember { mutableIntStateOf(0) }
    var limit by remember { mutableIntStateOf(Hq.limit(c)) }
    var cap by remember { mutableIntStateOf(Company.dailyCap(c)) }
    Column(Modifier.fillMaxSize2().verticalScroll(rememberScrollState())) {
        Text("Office hours: ${start.toInt()}:00 – ${end.toInt()}:00", fontWeight = FontWeight.Bold)
        Text("Outside these hours people go home, unless they're busy on something.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(start, { start = it; Company.setHours(c, it.toInt(), end.toInt()) }, valueRange = 5f..12f, steps = 6)
        Slider(end, { end = it; Company.setHours(c, start.toInt(), it.toInt()) }, valueRange = 15f..24f, steps = 8)
        listOf(
            Triple("standup", "Daily stand-up", "Each morning everyone meets at the table for a one-line update."),
            Triple("allhands", "Friday all-hands", "A weekly recap in the boardroom, filed in the wiki."),
            Triple("quality", "Quality gate", "Critic reviews important work before it reaches you."),
            Triple("idle", "Office life", "Coffee, chats, the whiteboard and the window when there's no work."),
            Triple("escalate", "Knock on your door", "Anyone who needs you waits on the bench outside your office."),
        ).forEach { (k, title, hint) ->
            @Suppress("UNUSED_VARIABLE") val f = flags
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(hint, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Switch(Company.flag(c, k), { Company.setFlag(c, k, it); flags++; Crew.version++ })
            }
        }
        Text("People working at once", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "Paused", 1 to "1", 2 to "2", 3 to "3", 4 to "4").forEach { (n, l) -> Capsule(l, limit == n) { limit = n; Hq.setLimit(c, n) } }
        }
        Text("Company AI budget per day", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
        Text("Shared evenly across the team; anyone who runs out rests until tomorrow.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(0 to "No cap", 100_000 to "100k", 250_000 to "250k", 500_000 to "500k", 1_000_000 to "1M").forEach { (n, l) -> Capsule(l, cap == n) { cap = n; Company.setDailyCap(c, n) } }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Hire a new teammate: describe the job, pick a department, and the CEO interviews for it. */
@Composable
fun HireSheet(container: AppContainer, close: () -> Unit) {
    val work = rememberWorkScope()
    var purpose by remember { mutableStateOf("") }
    var dept by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Text("➕ Hire", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text("Describe the work you need done. The CEO drafts 3–5 specialists and they join as Associates.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedTextField(purpose, { purpose = it }, Modifier.fillMaxWidth().padding(top = 10.dp), placeholder = { Text("e.g. help me run my small online shop") }, maxLines = 4)
    Text("Department", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Capsule("✨ Best fit", dept == null) { dept = null }
        Company.DEPTS.forEach { d -> Capsule("${d.emoji} ${d.name}", dept == d.id) { dept = d.id } }
    }
    Button(enabled = purpose.isNotBlank() && !busy, modifier = Modifier.padding(top = 12.dp), onClick = {
        busy = true
        work.launch {
            runCatching { withContext(Dispatchers.IO) { Company.hire(container, purpose, dept) } }
                .onSuccess { Feedback.show("Welcome ${it.joinToString { a -> a.name }} 🎉"); close() }
                .onFailure { Feedback.failed("Hiring", it) }
            busy = false
        }
    }) { Text(if (busy) "Interviewing…" else "Hire") }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
}

/** Someone's place in the company: level, department, manager, personality, record and what you can do. */
@Composable
fun ProfileBlock(container: AppContainer, id: String, close: () -> Unit) {
    val c = LocalContext.current
    val version = Crew.version
    val a = remember(version, id) { Crew.agents(c).firstOrNull { it.id == id } } ?: return
    val p = remember(version, id) { Company.profile(c, a) }
    val s = remember(version, id) { Company.stats(c, a) }
    val boss = remember(version, id) { Company.manager(c, id)?.let { m -> Crew.agents(c).firstOrNull { it.id == m } } }
    val friends = remember(version, id) { Company.friends(c)[id].orEmpty().mapNotNull { f -> Crew.agents(c).firstOrNull { it.id == f } } }
    var confirm by remember { mutableStateOf(false) }
    val dept = Company.DEPTS.firstOrNull { it.id == p.dept }
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Chip(Company.LEVELS[p.level], levelColor(p.level))
        dept?.let { Chip("${it.emoji} ${it.name}", Color(android.graphics.Color.parseColor(it.color)), filled = false) }
        Chip(Company.persona(p.persona).name, Color(0xFF64748B), filled = false)
    }
    Text("Reports to " + (boss?.let { "${it.emoji} ${it.name}" } ?: "👑 you") + " · joined " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.hiredAt)),
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Kpi("Done", "${s.done}", "", Modifier.weight(1f))
        Kpi("Success", if (s.ok + s.failed == 0) "—" else "${s.ok * 100 / (s.ok + s.failed)}%", "", Modifier.weight(1f))
        Kpi("Rating", "%.1f★".format(s.stars), "", Modifier.weight(1f))
        Kpi("Week", "%,d".format(s.weekTokens), "tokens", Modifier.weight(1f))
    }
    if (friends.isNotEmpty()) Text("Works best with " + friends.joinToString { "${it.emoji} ${it.name}" }, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { Company.rate(c, id, true); Feedback.show("Noted 👍") }) { Text("👍 Good work") }
        OutlinedButton(onClick = { Company.rate(c, id, false); Feedback.show("Noted 👎") }) { Text("👎 Not great") }
        if (!a.lead && p.level < Company.LEVELS.size - 2) OutlinedButton(onClick = { Company.setLevel(c, id, p.level + 1); Feedback.show("${a.name} promoted to ${Company.LEVELS[p.level + 1]} 🎉") }) { Text("⬆ Promote") }
        if (!a.lead && p.level > 0) OutlinedButton(onClick = { Company.setLevel(c, id, p.level - 1); Feedback.show("${a.name} is now ${Company.LEVELS[p.level - 1]}") }) { Text("⬇ Demote") }
        if (!a.lead) TextButton(onClick = { confirm = true }) { Text("Let go", color = Color(0xFFEF4444)) }
    }
    if (!a.lead) {
        Text("Department", fontWeight = FontWeight.Bold, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Company.DEPTS.filter { it.id != "exec" || p.dept == "exec" }.forEach { d ->
                Capsule("${d.emoji} ${d.name}", p.dept == d.id) { Company.save(c, p.copy(dept = d.id)); c.getSharedPreferences("hq", 0).edit().remove("room:$id").apply() }
            }
        }
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("Let ${a.name} go?") }, text = { Text("They'll pack a box and leave. Their past work stays in the threads and the wiki.") },
        confirmButton = { TextButton(onClick = { Company.letGo(c, id); confirm = false; close(); Feedback.show("${a.name} has left the company") }) { Text("Let go", color = Color(0xFFEF4444)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Keep") } })
    @Suppress("UNUSED_VARIABLE") val unused = ApprovalInbox
}
