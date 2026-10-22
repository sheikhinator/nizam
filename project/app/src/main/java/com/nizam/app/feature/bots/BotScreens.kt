package com.nizam.app.feature.bots

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import com.nizam.app.ui.design.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.feature.council.TypingDots
import com.nizam.app.ui.components.Feedback
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

object BotNav { var openId: Long = 0 }

private val PRESETS = listOf(
    Triple("🧭", "Work", "You handle this person's job: planning, drafts, decisions and follow-ups at work."),
    Triple("💰", "Money", "You handle money: spending, budgets, savings and anything financial."),
    Triple("📚", "Study", "You are a patient tutor for whatever they're learning right now."),
    Triple("🛠", "Project", "You are working with them on one specific project, start to finish.")
)

/** The bot list: one row per thread, most recent first. */
@Composable
fun BotsScreen(onOpen: (Long) -> Unit) {
    val c = LocalContext.current
    var bots by remember { mutableStateOf(Bots.all(c)) }
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var brief by remember { mutableStateOf("") }
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 26.dp)) {
                Text("Bots", fontSize = 34.sp, style = MaterialTheme.typography.displaySmall)
                Text("Each one is a thread that remembers — you return to it, you don't restart it",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))
                Text(if (adding) "cancel" else "+ new bot", color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(role = Role.Button) { adding = !adding }.padding(vertical = 8.dp))
                if (adding) {
                    OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(brief, { brief = it }, label = { Text("What is it for?") },
                        modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                        PRESETS.forEach { (emoji, preset, text) ->
                            Text("$emoji $preset", color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.clickable(role = Role.Button) { name = preset; brief = text }.padding(6.dp))
                        }
                    }
                    Button(enabled = name.isNotBlank(), onClick = {
                        bots = bots + Bot(System.currentTimeMillis(), name.trim(),
                            brief.trim().ifBlank { "A general assistant for ${name.trim()}." }, "✦", System.currentTimeMillis())
                        Bots.save(c, bots); bots = Bots.all(c); name = ""; brief = ""; adding = false
                        Feedback.show("Bot created")
                    }) { Text("Create") }
                }
                Spacer(Modifier.height(10.dp))
            }
        }
        items(bots) { bot ->
            val last = Bots.turns(c, bot.id).lastOrNull()
            Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(bot.id) }) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), CircleShape),
                        contentAlignment = Alignment.Center) { Text(bot.emoji) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(bot.name, fontSize = 17.sp)
                        Text(last?.text?.take(60) ?: bot.brief.take(60), maxLines = 1,
                            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    last?.let {
                        Text(Instant.ofEpochMilli(it.at).atZone(ZoneId.systemDefault()).toLocalDate().toString().takeLast(5),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
            }
        }
        if (bots.isEmpty()) item {
            Text("No bots yet. Create one for work, money, study or a project.",
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp))
        }
        item { Spacer(Modifier.height(80.dp)) }
    }
}

/** One bot, one thread. It has Atlas's full toolset, including your connected MCP servers. */
@Composable
fun BotThreadScreen(container: AppContainer, onBack: () -> Unit = {}) {
    val c = LocalContext.current
    // NOT rememberCoroutineScope: that scope is cancelled when the screen recomposes or you navigate,
    // which killed replies mid-flight with "the coroutine scope left the composition".
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    val bot = remember { Bots.all(c).firstOrNull { it.id == BotNav.openId } }
    var turns by remember { mutableStateOf(bot?.let { Bots.turns(c, it.id) } ?: emptyList()) }
    var streaming by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var attachments by remember { mutableStateOf<List<com.nizam.app.feature.attach.Attachment>>(emptyList()) }
    var busyLabel by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    LaunchedEffect(turns.size) { com.nizam.app.ui.design.guard { if (turns.isNotEmpty()) listState.animateScrollToItem(turns.size - 1) } }
    if (bot == null) { Text("Bot not found", Modifier.padding(20.dp)); return }

    fun send() {
        val text = draft.trim()
        val files = attachments
        if ((text.isBlank() && files.isEmpty()) || busy) return
        draft = ""; attachments = emptyList()
        val label = com.nizam.app.feature.attach.Attachments.label(files)
        Bots.addTurn(c, bot.id, true, listOf(text, label).filter { it.isNotBlank() }.joinToString("\n")); turns = Bots.turns(c, bot.id)
        val ask = text.ifBlank { "Look at what I attached and help me with it." }
        scope.launch {
            busy = true; streaming = ""
            // A plain conversational turn streams, so words appear as they're written. Anything that
            // might need tools goes through the agent, which can't stream mid-flight.
            // A bot bound to one server always works through its tools; otherwise the router decides
            val toolMode = com.nizam.app.feature.nx.AiLab.tools(c, bot.id)
            val needsTools = toolMode != "none" && bot.server.isNotBlank() || toolMode != "none" && com.nizam.app.net.Router.classify(text, false, files.isNotEmpty(),
                com.nizam.app.feature.mcp.Mcp.servers(c).filter { it.enabled }.map { it.name }) == com.nizam.app.net.Router.Lane.ACT
            val reply = runCatching {
                if (needsTools) {
                    // The agent is text-only: files are read first and passed along as notes
                    val notes = if (files.isEmpty()) "" else {
                        busyLabel = "Reading ${if (files.size == 1) files[0].name else "${files.size} files"}"
                        com.nizam.app.feature.attach.Attachments.digest(container.settingsRepository, files, ask)
                            .also { busyLabel = null }
                    }
                    val learning = com.nizam.app.feature.nx.Apprentice.learning(c, bot.id)
                    container.agentLoop.run(Bots.contextFor(c, bot) + com.nizam.app.feature.nx.Apprentice.note(c, bot.id) + "\n\nThem: $ask" +
                        (if (notes.isBlank()) "" else "\n\n$notes"), useWeb = false, autoApprove = toolMode == "all" && !learning).reply
                        .also { if (learning) com.nizam.app.feature.nx.Apprentice.recordRun(c, bot.id) }
                }
                // A plain turn streams, and files go to the model directly — images and PDFs natively
                else com.nizam.app.net.Streaming.reply(
                    container.settingsRepository,
                    prompt = Bots.contextFor(c, bot) + "\n\nThem: $ask",
                    system = "You are this person's bot. Answer directly and briefly. Use markdown when it helps.",
                    maxTokens = if (files.isEmpty()) 1200 else 2400,
                    attachments = files,
                    context = c
                ) { piece -> streaming += piece }
            }.getOrElse { "Couldn't answer: ${Feedback.friendly(it)}" }
            streaming = ""
            if (files.isNotEmpty()) runCatching { com.nizam.app.feature.jarvis.BrainFiles.remember(c, files, reply) }
            Bots.addTurn(c, bot.id, false, reply); turns = Bots.turns(c, bot.id)
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${bot.emoji}  ${bot.name}", fontSize = 20.sp)
                Text(bot.brief, maxLines = 1, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("edit", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(role = Role.Button) { editing = !editing }.padding(8.dp))
            Text("clear", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(role = Role.Button) { Bots.clear(c, bot.id); turns = emptyList() }.padding(8.dp))
        }
        if (editing) {
            var newName by remember { mutableStateOf(bot.name) }
            var newBrief by remember { mutableStateOf(bot.brief) }
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text("Name") },
                    modifier = Modifier.fillMaxWidth())
                OutlinedTextField(newBrief, { newBrief = it }, label = { Text("What is it for?") },
                    modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = {
                        Bots.update(c, bot.copy(name = newName.trim(), brief = newBrief.trim()))
                        editing = false; Feedback.show("Saved — reopen to see the new name")
                    }) { Text("Save") }
                    Text("delete bot", color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable(role = Role.Button) {
                            Bots.delete(c, bot.id); onBack(); Feedback.show("Bot deleted")
                        }.padding(vertical = 12.dp))
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
        LazyColumn(Modifier.weight(1f).padding(horizontal = 16.dp), state = listState) {
            items(turns) { t ->
                if (t.fromUser) com.nizam.app.feature.council.UserBubble(t.text)
                else BotReply(bot, t.text)
            }
            if (streaming.isNotBlank()) item { BotReply(bot, streaming) }
            else if (busy) item { com.nizam.app.ui.components.Thinking(label = busyLabel, modifier = Modifier.padding(start = 36.dp)) }
            item { Spacer(Modifier.height(8.dp)) }
        }
        com.nizam.app.ui.components.Composer(
            draft = draft, onDraft = { draft = it },
            attachments = attachments, onAttachments = { attachments = it },
            busy = busy,
            placeholder = "Message ${bot.name}",
            onSend = { send() },
            modifier = Modifier.padding(horizontal = 12.dp),
            // a bot bound to one server shows just that connection's thread; the sheet still lets you toggle all
            showConnections = true
        )
    }
}

/** An assistant turn, Claude-style: no bubble, a small name line, rendered markdown, and copy. */
@Composable
private fun BotReply(bot: Bot, text: String) {
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Box(Modifier.size(26.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center) { Text(bot.emoji, fontSize = 13.sp) }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(bot.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            com.nizam.app.ui.components.ChatText(text)
            Text("copy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(role = Role.Button) {
                    clipboard.setText(androidx.compose.ui.text.AnnotatedString(text)); Feedback.show("Copied")
                }.padding(top = 6.dp))
        }
    }
}
