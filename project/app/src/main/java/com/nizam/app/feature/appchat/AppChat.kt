package com.nizam.app.feature.appchat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.feature.agent.ApprovalInbox
import com.nizam.app.feature.agent.Reviewer
import com.nizam.app.feature.chief.ClaudeBadge
import com.nizam.app.feature.chief.EpoxyBadge
import com.nizam.app.feature.chief.badgeTint
import com.nizam.app.feature.connect.Connectors
import com.nizam.app.feature.connect.Service
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.Md
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.feature.phone.AtlasNotificationListener
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Who the user is talking to: a known service, or any app they name. */
private data class Peer(val id: String, val name: String, val emoji: String, val kind: String, val svc: Service?)

private fun peerOf(s: Service) = Peer(s.id, s.name, s.emoji.ifBlank { "💬" }, s.kind, s)

private fun howItWorks(p: Peer): String = when (p.kind) {
    "api", "mcp" -> "It is connected. Use the connector tools with service \"${p.id}\" to read or act. Read freely; anything that posts, sends, deletes or spends must wait for approval."
    "app" -> "It is installed on this phone. You can open it with open_in_app, and you can read its recent notifications below. If something needs the app itself, operate it with the phone app agent."
    "device" -> "It is built into this phone. Use Atlas's own tools for it."
    else -> "There is no direct connection. Use its recent notifications below, open it with open_in_app if it is installed, and say plainly what you cannot do and how it could be connected."
}

private fun notificationsFor(name: String): String {
    val n = runCatching { AtlasNotificationListener.recent(80) }.getOrDefault(emptyList()).filter { it.app.contains(name, true) || name.contains(it.app, true) }.take(10)
    return if (n.isEmpty()) "" else "Recent notifications from $name on this phone:\n" + n.joinToString("\n") { "- ${it.title}: ${it.text}".take(200) }
}

@Composable
fun AppChatScreen(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var peer by remember { mutableStateOf<Peer?>(null) }
    var typed by remember { mutableStateOf("") }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    var expanded by remember { mutableStateOf<Set<String>>(emptySet()) }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 14.dp)) {
        val p = peer
        if (p == null) {
            val connected = remember(tick) { Connectors.connected(c).filter { it.kind == "api" || it.kind == "mcp" }.map(::peerOf) }
            val phone = remember(tick) { Connectors.services(c).filter { it.kind == "app" && Connectors.installed(c, it) }.map(::peerOf) }
            val builtIn = remember(tick) { Connectors.services(c).filter { it.kind == "device" }.map(::peerOf) }
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                ClaudeBadge(38.dp)
                Column(Modifier.padding(start = 10.dp)) {
                    Text("Talk to my apps", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text("Pick an app and chat with it. Atlas's AI does the work inside it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                item {
                    OutlinedTextField(typed, { typed = it }, label = { Text("Any app or service by name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (typed.isNotBlank()) TextButton(onClick = { peer = Peer("custom_" + typed.lowercase().filter { it.isLetterOrDigit() }, typed.trim(), "💬", "other", null); typed = "" }) { Text("Chat with \"${typed.trim()}\"") }
                }
                fun section(title: String, list: List<Peer>) {
                    if (list.isEmpty()) return
                    item { Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)) }
                    items(list.size) { i ->
                        val s = list[i]
                        Row(Modifier.fillMaxWidth().clickable { peer = s }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            EpoxyBadge(40.dp, Modifier, false, badgeTint(s.name), s.emoji)
                            Text(s.name, Modifier.padding(start = 12.dp), fontWeight = FontWeight.Medium)
                        }
                    }
                }
                section("Connected apps", connected)
                section("On this phone", phone)
                section("Built in", builtIn)
                item { Text("Connect more apps in Connectors, or from Flows with \"Connect any app\".", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp)) }
            }
            return@Column
        }

        val store = remember(p.id) { JsonStore(c, "appchat_${p.id}") }
        val msgs = remember(p.id, tick) { store.all().sortedBy { it.optLong("t") }.takeLast(60) }
        LaunchedEffect(msgs.size, busy) { com.nizam.app.ui.design.guard { if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size) } }

        // header: the app's own badge, with the small Claude badge in the corner marking the AI layer
        Row(Modifier.padding(top = 8.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                EpoxyBadge(46.dp, Modifier, busy, badgeTint(p.name), p.emoji)
                ClaudeBadge(20.dp, Modifier.align(Alignment.BottomEnd), busy)
            }
            Column(Modifier.padding(start = 12.dp).weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("Through Atlas AI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { peer = null }) { Text("Switch") }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (msgs.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Ask ${p.name} anything", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    listOf("What's new for me?", "Summarise my notifications", "What can you do here?").forEach { s -> TextButton(onClick = { input = s }) { Text(s) } }
                }
            }
            items(msgs.size) { i ->
                val m = msgs[i]
                if (m.optString("role") == "u") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Box(Modifier.widthIn(max = 300.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) { Text(m.optString("text")) }
                } else Row(Modifier.fillMaxWidth()) {
                    EpoxyBadge(28.dp, Modifier.padding(top = 2.dp, end = 8.dp), false, badgeTint(p.name), p.emoji)
                    Column(Modifier.weight(1f)) {
                        Md(m.optString("text"))
                        val steps = m.optString("steps")
                        if (steps.isNotBlank()) {
                            val id = m.optString("id")
                            Text((if (id in expanded) "▾ " else "▸ ") + "What I did", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { expanded = if (id in expanded) expanded - id else expanded + id }.padding(vertical = 4.dp))
                            if (id in expanded) Text(steps, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (busy) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EpoxyBadge(28.dp, Modifier.padding(end = 8.dp), true, badgeTint(p.name), p.emoji)
                    Text("Working on it…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(input, { input = it }, placeholder = { Text("Message ${p.name}…") }, modifier = Modifier.weight(1f), maxLines = 5, shape = RoundedCornerShape(22.dp), enabled = !busy)
            IconButton(enabled = !busy && input.isNotBlank(), onClick = {
                val text = input.trim(); input = ""
                store.add(JSONObject().put("t", System.currentTimeMillis()).put("role", "u").put("text", text)); tick++
                busy = true
                scope.launch {
                    val prompt = buildString {
                        append("You are Atlas's assistant for the app \"${p.name}\" only, speaking as that app's helper. Keep replies short and plain.\n")
                        append(howItWorks(p)).append("\n")
                        append("Never act in any other app. Anything that posts, sends, deletes, buys or changes something needs the owner's approval, which the system asks for.\n")
                        notificationsFor(p.name).takeIf { it.isNotBlank() }?.let { append("\n").append(it).append("\n") }
                        append("\nThe owner says: ").append(text)
                    }
                    val out = runCatching { container.agentLoop.run(prompt, useWeb = false, autoApprove = false) }
                    val reply: String; val steps: String
                    if (out.isSuccess) {
                        val o = out.getOrThrow()
                        o.pendingApproval.forEach { (n, a) ->
                            ApprovalInbox.add(c, "Chat with ${p.name}", n, a, Reviewer.Verdict("medium", "ask", "From your chat with ${p.name}"), text)
                        }
                        reply = o.reply.ifBlank { "Done." } + if (o.pendingApproval.isNotEmpty()) "\n\n⏸ This needs your OK. It is waiting in your approvals inbox." else ""
                        steps = o.steps.joinToString("\n") { (if (it.ok) "✓ " else "✗ ") + it.summary }
                    } else { reply = "I could not do that: ${out.exceptionOrNull()?.message.orEmpty().take(160)}"; steps = "" }
                    store.add(JSONObject().put("t", System.currentTimeMillis()).put("role", "a").put("text", reply).put("steps", steps))
                    busy = false; tick++
                }
            }, modifier = Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)) {
                Icon(Icons.Outlined.ArrowUpward, "Send", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

object AppChatSpecs {
    val all: List<Spec> = listOf(
        ScreenSpec("appchat", "Talk to my apps", "Chat with any connected or installed app. Atlas's AI does the work inside it", Icons.Outlined.Forum, Hub.Tools,
            "chat app instagram whatsapp talk to apps connectors assistant notifications agent") { container, open -> AppChatScreen(container, open) },
    )
}
