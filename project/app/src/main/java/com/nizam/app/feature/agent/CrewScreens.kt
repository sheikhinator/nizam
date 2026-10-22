package com.nizam.app.feature.agent

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.feature.nx.Md
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Art3D
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.OutlinedButton
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private fun ago(t: Long): String { val m = (System.currentTimeMillis() - t) / 60000
    return when { m < 1 -> "now"; m < 60 -> "${m}m"; m < 1440 -> "${m / 60}h"; else -> "${m / 1440}d" } }

@Composable
fun statusColor(s: String): Color = when (s) {
    "running" -> Color(0xFF6366F1); "done" -> Color(0xFF16A34A); "waiting", "needs you" -> Color(0xFFF59E0B)
    "failed" -> MaterialTheme.colorScheme.error; else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Composable
private fun Avatar(emoji: String, color: Long, size: Int = 36, pulse: Boolean = false) {
    val a = if (pulse) rememberInfiniteTransition(label = "p").animateFloat(0.35f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "a").value else 1f
    Box(Modifier.size(size.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(color).copy(alpha = 0.95f * a), Color(color).copy(alpha = 0.55f * a)))),
        contentAlignment = Alignment.Center) { Text(emoji, fontSize = (size * 0.5f).sp) }
}

/** Agent teams: threads, the team itself, and everything waiting on you. */
@Composable
fun CrewScreen(container: AppContainer) {
    val c = LocalContext.current
    var tab by remember { mutableStateOf("Threads") }
    var open by remember { mutableStateOf<String?>(null) }
    val v = Crew.version
    open?.let { id -> ThreadScreen(container, id) { open = null }; return }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Agent teams", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("${remember(v) { Crew.agents(c).size }} agents · they delegate, work in parallel and report back",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Art3D("robot", 64.dp)
        }
        val waiting = remember(v, tab) { ApprovalInbox.pending(c).size }
        Row(Modifier.padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Threads", "Team", "Approvals" + if (waiting > 0) " · $waiting" else "").forEach { t -> Capsule(t, tab == t.substringBefore(" ·")) { tab = t.substringBefore(" ·") } }
        }
        when (tab) {
            "Team" -> TeamTab(container)
            "Approvals" -> ApprovalsTab(container)
            else -> ThreadsTab(container) { open = it }
        }
    }
}

@Composable
private fun ThreadsTab(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current
    val v = Crew.version
    var goal by remember { mutableStateOf("") }
    val threads = remember(v) { Crew.threads(c).all().sortedByDescending { it.optLong("updated", it.optLong("created")) } }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)))).padding(16.dp)) {
                Text("Give the team a goal", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(goal, { goal = it }, Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 2,
                    placeholder = { Text("Plan a 4-day Hunza trip for 2 under 120k, book-ready, and put it in my calendar") })
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Compare 3 phones under 150k and pick one", "Plan my week around prayers and deadlines", "Research a side business I can start with 50k",
                        "Find why my spending went up this month and fix it", "Draft replies to everything waiting in my inbox").forEach { s -> Capsule(s, false) { goal = s } }
                }
                Button(onClick = { val id = Crew.start(container, goal); goal = ""; open(id) }, enabled = goal.isNotBlank(), modifier = Modifier.padding(top = 10.dp)) { Text("Start the team") }
            }
        }
        if (threads.isEmpty()) item { Text("No threads yet. The lead breaks your goal down, hands pieces to specialists and brings back one answer.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(threads, key = { it.optString("id") }) { t ->
            val msgs = t.optJSONArray("messages") ?: JSONArray()
            val last = if (msgs.length() > 0) msgs.getJSONObject(msgs.length() - 1) else null
            val st = t.optString("status"); val who = Crew.working[t.optString("id")]
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                .clickable { open(t.optString("id")) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(statusColor(st)))
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(t.optString("goal"), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(who?.let { "$it is working…" } ?: last?.optString("text")?.take(90).orEmpty(), style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(st, style = MaterialTheme.typography.labelSmall, color = statusColor(st))
                    Text(ago(t.optLong("updated", t.optLong("created"))), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ThreadScreen(container: AppContainer, id: String, back: () -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val v = Crew.version
    val t = remember(v) { Crew.thread(c, id) } ?: run { back(); return }
    val team = remember(v) { Crew.agents(c).associateBy { it.id } }
    val msgs = remember(v) { (t.optJSONArray("messages") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }
    val list = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    val working = Crew.working[id]
    LaunchedEffect(msgs.size) { com.nizam.app.ui.design.guard { if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size) } }
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", fontSize = 30.sp, modifier = Modifier.clickable { back() }.padding(horizontal = 8.dp))
            Column(Modifier.weight(1f)) {
                Text(t.optString("goal"), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(t.optString("status") + (working?.let { " · $it is working" } ?: ""), style = MaterialTheme.typography.labelMedium, color = statusColor(t.optString("status")))
            }
            if (working != null) Icon(Icons.Outlined.Stop, "Stop", Modifier.clip(CircleShape).clickable { Crew.stop(c, id) }.padding(8.dp), tint = MaterialTheme.colorScheme.error)
        }
        // who's on this job
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            msgs.map { it.optString("from") }.distinct().mapNotNull { team[it] }.forEach { a ->
                Row(Modifier.clip(RoundedCornerShape(50)).background(Color(a.color).copy(alpha = 0.14f)).padding(end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(a.emoji, a.color, 24, pulse = working == a.name); Text(" ${a.name}", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(msgs.size) { i -> Bubble(container, msgs[i], team) }
            if (working != null) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val a = team.values.firstOrNull { it.name == working }
                    if (a != null) Avatar(a.emoji, a.color, 28, pulse = true)
                    Text("  $working is working…", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val cs = MaterialTheme.colorScheme
        Row(Modifier.padding(12.dp).fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(cs.onSurface.copy(alpha = 0.06f)).padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                if (text.isEmpty()) Text("Steer the team, or @Name someone", color = cs.onSurfaceVariant)
                BasicTextField(text, { text = it }, textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface), cursorBrush = SolidColor(cs.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { if (text.isNotBlank()) { Crew.say(container, id, text); text = "" } }),
                    modifier = Modifier.fillMaxWidth())
            }
            Box(Modifier.size(42.dp).clip(CircleShape).background(Brush.linearGradient(listOf(cs.primary, cs.tertiary)))
                .clickable { if (text.isNotBlank()) { Crew.say(container, id, text); text = "" } }, contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.ArrowUpward, "Send", tint = Color.White)
            }
        }
    }
}

@Composable
private fun Bubble(container: AppContainer, m: JSONObject, team: Map<String, Crew.Agent>) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val cs = MaterialTheme.colorScheme
    val from = m.optString("from"); val kind = m.optString("kind")
    val a = team[from]
    if (from == "you") {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(m.optString("text"), color = Color.White, modifier = Modifier.fillMaxWidth(0.85f).clip(RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp))
                .background(Brush.linearGradient(listOf(cs.primary, cs.tertiary))).padding(12.dp))
        }
        return
    }
    if (from == "system") { Text(m.optString("text"), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp)); return }
    Row(verticalAlignment = Alignment.Top) {
        Avatar(a?.emoji ?: "🤖", a?.color ?: 0xFF6366F1)
        Column(Modifier.padding(start = 8.dp).weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a?.name ?: from, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color(a?.color ?: 0xFF6366F1))
                val to = team[m.optString("to")]
                if (kind == "delegate" && to != null) Text("  → ${to.emoji} ${to.name}", style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                Text("  ${ago(m.optLong("t"))}", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
            }
            val shape = RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp)
            when (kind) {
                "final" -> Column(Modifier.fillMaxWidth().clip(shape).background(Brush.linearGradient(listOf(Color(0xFF16A34A).copy(alpha = 0.16f), cs.primary.copy(alpha = 0.08f))))
                    .border(1.dp, Color(0xFF16A34A).copy(alpha = 0.4f), shape).padding(12.dp)) {
                    Text("✅ Final answer", style = MaterialTheme.typography.labelLarge, color = Color(0xFF16A34A)); Md(m.optString("text"))
                    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Capsule("Save to Notes", false) { scope.launch { container.noteRepository.createFull("Team: " + m.optString("text").lines().first().take(50), m.optString("text")); Feedback.show("Saved") } }
                        Capsule("Share", false) { com.nizam.app.feature.nx.Share.text(c, m.optString("text")) }
                    }
                }
                "delegate" -> Text(m.optString("text"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth().clip(shape)
                    .border(1.dp, Color(a?.color ?: 0xFF6366F1).copy(alpha = 0.35f), shape).padding(12.dp))
                "tool" -> { var open by remember { mutableStateOf(false) }
                    Column(Modifier.fillMaxWidth().clip(shape).background(cs.onSurface.copy(alpha = 0.05f)).clickable { open = !open }.padding(12.dp)) {
                        Text("🛠 " + m.optString("instruction").ifBlank { "Used tools" }.take(120), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                        Text(m.optString("text"), style = MaterialTheme.typography.bodyMedium, maxLines = if (open) 50 else 4, overflow = TextOverflow.Ellipsis)
                        AnimatedVisibility(open) { Column { m.optJSONArray("steps")?.let { s -> (0 until s.length()).forEach { Text(s.optString(it), style = MaterialTheme.typography.labelMedium) } } } }
                    }
                }
                "approval" -> {
                    val id = m.optString("approval")
                    val item = remember(Crew.version) { ApprovalInbox.store(c).all().firstOrNull { it.optString("id") == id } }
                    ApprovalCard(container, item, m.optString("risk"), m.optString("why"), m.optString("args"))
                }
                "hire" -> Text(m.optString("text"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.clip(shape).background(Color(0xFF8B5CF6).copy(alpha = 0.12f)).padding(12.dp))
                "question" -> Column(Modifier.fillMaxWidth().clip(shape).background(Color(0xFFF59E0B).copy(alpha = 0.14f)).padding(12.dp)) {
                    Text("❓ Needs you", style = MaterialTheme.typography.labelLarge, color = Color(0xFFB45309)); Md(m.optString("text")) }
                else -> Box(Modifier.fillMaxWidth().clip(shape).background(cs.onSurface.copy(alpha = 0.05f)).padding(12.dp)) { Md(m.optString("text")) }
            }
        }
    }
}

@Composable
fun ApprovalCard(container: AppContainer, item: JSONObject?, risk: String, why: String, args: String) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val cs = MaterialTheme.colorScheme
    val state = item?.optString("state") ?: "done"
    val rc = when (risk) { "high" -> cs.error; "medium" -> Color(0xFFF59E0B); else -> Color(0xFF16A34A) }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.5.dp, rc.copy(alpha = 0.5f), RoundedCornerShape(18.dp)).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item?.optString("action")?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: "Action", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${risk.uppercase()} RISK", style = MaterialTheme.typography.labelSmall, color = rc, fontWeight = FontWeight.Bold)
        }
        Text("🧐 Reviewer: $why", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        if (args.isNotBlank()) Text(args.take(300), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
        if (state == "waiting" && item != null) Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { busy = true; scope.launch { Feedback.show(ApprovalInbox.decide(container, item.optString("id"), true)); busy = false; Crew.version++ } }, enabled = !busy) {
                if (busy) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("Approve") }
            OutlinedButton(onClick = { scope.launch { ApprovalInbox.decide(container, item.optString("id"), false); Crew.version++ } }) { Text("Decline") }
        } else Text(when (state) { "done" -> "✓ Approved — ${item?.optString("result").orEmpty()}"; "declined" -> "✕ Declined"; "failed" -> "✕ Failed — ${item?.optString("result").orEmpty()}"; else -> state },
            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun ApprovalsTab(container: AppContainer) {
    val c = LocalContext.current
    val v = Crew.version
    var policy by remember { mutableStateOf(Reviewer.policy(c)) }
    val all = remember(v) { ApprovalInbox.store(c).all().sortedByDescending { it.optLong("at") } }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f)).padding(14.dp)) {
                Text("Auto-review", style = MaterialTheme.typography.titleSmall)
                Text("A reviewer model checks every action that changes something outside Atlas. Choose what may run without asking:",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("ask" to "Always ask", "auto_low" to "Low-risk alone", "auto_all" to "Reviewer-approved low/medium").forEach { (k, l) ->
                        Capsule(l, policy == k) { policy = k; Reviewer.setPolicy(c, k) } }
                }
                Text("Money, deleting and publishing always wait for you. Keys and passwords are hidden from threads.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (all.isEmpty()) item { Text("Nothing has needed your OK yet.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(all.take(60), key = { it.optString("id") }) { o ->
            Column { Text("${o.optString("source")} · ${ago(o.optLong("at"))}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ApprovalCard(container, o, o.optString("risk"), o.optString("why"), Secrets.redact(o.optJSONObject("args")?.toString().orEmpty())) }
        }
    }
}

@Composable
private fun TeamTab(container: AppContainer) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val v = Crew.version
    val agents = remember(v) { Crew.agents(c) }
    var edit by remember { mutableStateOf<Crew.Agent?>(null) }
    var purpose by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    edit?.let { a -> AgentEditor(a, onSave = { Crew.saveAgent(c, it); edit = null }, onDelete = { if (!a.lead) Crew.deleteAgent(c, a.id); edit = null }, onCancel = { edit = null }); return }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { edit = Crew.Agent("a" + System.currentTimeMillis(), "", "✨", "", "", setOf("web"), Crew.newColor(c), false) }) { Text("+ New agent") }
            OutlinedButton(onClick = { Crew.resetTeam(c) }) { Text("Reset team") }
            OutlinedButton(onClick = { com.nizam.app.feature.nx.Share.text(c, "My Atlas agent team — open Atlas → Atlas-to-Atlas → Receive:\n" + Crew.exportTeam(c)) }) { Text("Share team") }
        } }
        item { Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)).padding(14.dp)) {
            Text("Hire specialists for a kind of work", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(purpose, { purpose = it }, Modifier.fillMaxWidth().padding(top = 6.dp), placeholder = { Text("Running my online clothing shop") }, singleLine = true)
            Button(onClick = { busy = true; scope.launch { runCatching { Crew.design(container, purpose) }.onSuccess { Feedback.show("${it.size} agents joined") }
                .onFailure { Feedback.failed("Hiring", it) }; busy = false; purpose = "" } }, enabled = purpose.isNotBlank() && !busy, modifier = Modifier.padding(top = 6.dp)) {
                Text(if (busy) "Hiring…" else "Design them") }
        } }
        items(agents, key = { it.id }) { a ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color(a.color).copy(alpha = 0.08f)).clickable { edit = a }.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Avatar(a.emoji, a.color, 46)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(a.name + if (a.lead) "  · lead" else "", style = MaterialTheme.typography.titleSmall)
                    Text(a.role, style = MaterialTheme.typography.labelMedium, color = Color(a.color))
                    Text(a.tools.joinToString(" · ").ifBlank { "drafts only" } + if (a.createdBy != "you" && a.createdBy != "Atlas") " · hired by ${a.createdBy}" else "",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AgentEditor(a: Crew.Agent, onSave: (Crew.Agent) -> Unit, onDelete: () -> Unit, onCancel: () -> Unit) {
    var name by remember { mutableStateOf(a.name) }
    var emoji by remember { mutableStateOf(a.emoji) }
    var role by remember { mutableStateOf(a.role) }
    var ins by remember { mutableStateOf(a.instructions) }
    var tools by remember { mutableStateOf(a.tools) }
    LazyColumn(contentPadding = PaddingValues(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Row(verticalAlignment = Alignment.CenterVertically) { Avatar(emoji, a.color, 56); Spacer(Modifier.width(12.dp))
            Text(if (a.name.isBlank()) "New agent" else "Edit ${a.name}", style = MaterialTheme.typography.titleLarge) } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(emoji, { emoji = it.take(2) }, Modifier.width(80.dp), label = { Text("Emoji") }, singleLine = true)
            OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text("Name") }, singleLine = true) } }
        item { OutlinedTextField(role, { role = it }, Modifier.fillMaxWidth(), label = { Text("Role") }, singleLine = true) }
        item { OutlinedTextField(ins, { ins = it }, Modifier.fillMaxWidth(), label = { Text("How they work") }, minLines = 3) }
        item { Text("What they may use", style = MaterialTheme.typography.titleSmall) }
        item { Column(verticalArrangement = Arrangement.spacedBy(6.dp)) { Crew.TOOLS.forEach { (k, l) ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(if (k in tools) Color(a.color).copy(alpha = 0.14f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.04f))
                .clickable { tools = if (k in tools) tools - k else tools + k }.padding(12.dp)) { Text(if (k in tools) "✓  $l" else "    $l") } } } }
        item { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { onSave(a.copy(name = name.trim(), emoji = emoji.ifBlank { "✨" }, role = role.trim(), instructions = ins.trim(), tools = tools)) }, enabled = name.isNotBlank()) { Text("Save") }
            OutlinedButton(onClick = onCancel) { Text("Cancel") }
            if (!a.lead && a.name.isNotBlank()) OutlinedButton(onClick = onDelete) { Text("Remove") }
        } }
    }
}
