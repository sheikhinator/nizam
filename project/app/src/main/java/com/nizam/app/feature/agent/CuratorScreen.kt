package com.nizam.app.feature.agent

import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.People
import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.Refresh
import com.nizam.app.ui.design.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.Tones
import com.nizam.app.feature.voice.Speaker
import com.nizam.app.feature.voice.speechIntent
import com.nizam.app.ui.theme.DisplayFamily
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
fun CuratorScreen(container: AppContainer) {
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    val context = LocalContext.current
    val turns by container.agentMemory.turns().collectAsState(initial = emptyList())
    val suggestions by container.curatorRepository.active().collectAsState(initial = emptyList())

    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var useWeb by remember { mutableStateOf(false) }
    var speakBack by remember { mutableStateOf(false) }
    var autoApprove by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<List<Pair<String, JSONObject>>>(emptyList()) }
    var lastSteps by remember { mutableStateOf<List<AgentLoop.Step>>(emptyList()) }

    val speaker = remember { Speaker(context) }
    DisposableEffect(Unit) { onDispose { speaker.release() } }

    val listener = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val spoken = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) draft = spoken
        }
    }

    var media by remember { mutableStateOf<List<com.nizam.app.feature.mcp.McpPart>>(emptyList()) }
    var attachments by remember { mutableStateOf<List<com.nizam.app.feature.attach.Attachment>>(emptyList()) }
    var busyLabel by remember { mutableStateOf<String?>(null) }
    var streaming by remember { mutableStateOf("") }
    var reviewMessage by remember { mutableStateOf<String?>(null) }
    var reviewFiles by remember { mutableStateOf<List<com.nizam.app.feature.attach.Attachment>>(emptyList()) }
    var reviewParts by remember { mutableStateOf<List<RunContextPart>>(emptyList()) }

    // Open the connection to the AI and build the app snapshot before the first message is typed
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching { com.nizam.app.net.Router.warm(container.settingsRepository, context) }
        runCatching { Snapshot.of(container) }
    }

    fun performSend(message: String, files: List<com.nizam.app.feature.attach.Attachment>, contextParts: List<RunContextPart>) {
        if (message.isBlank() && files.isEmpty()) return
        scope.launch {
            busy = true
            val includedFiles = if (contextParts.firstOrNull { it.id == "attachments" }?.included == true) files else emptyList()
            val label = if (includedFiles.isEmpty()) "" else com.nizam.app.feature.attach.Attachments.label(includedFiles)
            val resumePendingCheckpoint = contextParts.firstOrNull { it.id == "assumption_checkpoint" }?.included == true
            val explicitSaveDirective = listOf("remember this", "save this", "make this my preference")
                .any { message.contains(it, ignoreCase = true) }
            val allowLearning = !ChatModes.private && (!resumePendingCheckpoint || explicitSaveDirective)
            if (AssumptionCheckpoint.pending(context) != null && !resumePendingCheckpoint) {
                AssumptionCheckpoint.dismiss(context)
            }
            if (!ChatModes.private) {
                container.agentMemory.addTurn(true, listOf(message.trim(), label).filter { it.isNotBlank() }.joinToString("\n"))
            }
            draft = ""
            attachments = emptyList()
            val servers = com.nizam.app.feature.mcp.Mcp.servers(context).filter { it.enabled }.map { it.name }
            val lane = com.nizam.app.net.Router.classify(message, useWeb, includedFiles.isNotEmpty(), servers)
            // Fast lane: questions stream straight back, files included, with no agent round-trips
            val imagePrompt = when { message.startsWith("/imagine ", true) -> message.substringAfter(' '); ChatModes.imagine -> message; else -> null }
            val chatReply: String? = if (resumePendingCheckpoint) null else if (imagePrompt != null) runCatching {
                busyLabel = "Imagining"
                val f = ChatModes.imagine(context, imagePrompt)
                com.nizam.app.feature.mcp.Mcp.noteParts(listOf(com.nizam.app.feature.mcp.McpPart("image", mime = "image/jpeg", file = f)))
                busyLabel = null
                "Here's “${imagePrompt.take(80)}”. Tap the image to save or share it."
            }.getOrElse { busyLabel = null; "Couldn't make that image: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
            else if (ChatModes.contemplate) runCatching { ChatModes.contemplate(container, message) { busyLabel = it }.also { busyLabel = null } }
                .getOrElse { busyLabel = null; null }
            else if (ChatModes.deep) runCatching { ChatModes.deepSearch(container, message) { busyLabel = it }.also { busyLabel = null } }
                .getOrElse { busyLabel = null; null }
            else if (lane != com.nizam.app.net.Router.Lane.CHAT) null else runCatching {
                LocalCommands.tryRun(container, message) ?: Converse.chat(container, message, includedFiles,
                    contextParts = contextParts) { streaming += it }
            }.getOrNull()
            streaming = ""
            val outcome = if (chatReply != null) AgentLoop.Outcome(chatReply, emptyList()) else runCatching {
                // The agent is text-only, so files are read first and handed over as notes.
                var runParts = contextParts
                val request = if (includedFiles.isEmpty()) message else {
                    busyLabel = "Reading ${if (includedFiles.size == 1) includedFiles[0].name else "${includedFiles.size} files"}"
                    val notes = com.nizam.app.feature.attach.Attachments.digest(container.settingsRepository, includedFiles, message)
                    busyLabel = null
                    runParts = contextParts + RunContextPart("attachment_text", "Extracted attachment context",
                        "Generated from the files you approved for this run", "Generated immediately before this run", notes)
                    message.ifBlank { "Look at what I attached and help me with it." } + "\n\n" + notes
                }
                container.agentLoop.run(request, useWeb, autoApprove, runParts)
            }.getOrElse { e ->
                AgentLoop.Outcome("That failed: ${com.nizam.app.ui.components.Feedback.friendly(e)}", emptyList())
            }
            lastSteps = outcome.steps
            pending = outcome.pendingApproval
            // The model sometimes narrates an action it never took ("Settings opened.") — catch it.
            // only a first-person past-tense claim counts — "opening the link" is not a claim
            val claimed = Regex(
                "\\b(i(?:'ve| have)?)\\s+(opened|set|sent|called|added|created|logged|deleted|started|scheduled)\\b|" +
                    "^(done|opened|added|created|logged|deleted)\\b",
                setOf(RegexOption.IGNORE_CASE, RegexOption.MULTILINE)
            ).containsMatchIn(outcome.reply)
            val unbacked = claimed && outcome.steps.isEmpty() && outcome.pendingApproval.isEmpty()
            val body = buildString {
                append(outcome.reply)
                if (unbacked) append("\n\n⚠ No action was actually run — try asking again more directly.")
                if (outcome.steps.isNotEmpty()) {
                    append("\n\n")
                    append(outcome.steps.joinToString("\n") {
                        (if (it.ok) "✓ " else "✕ ") + it.summary
                    })
                }
            }
            if (!ChatModes.private) container.agentMemory.addTurn(false, body)
            if (allowLearning) Learner.learn(container, message, outcome.reply)
            if (allowLearning && includedFiles.isNotEmpty()) runCatching {
                com.nizam.app.feature.jarvis.BrainFiles.remember(context, includedFiles, outcome.reply)
            }
            media = com.nizam.app.feature.mcp.Mcp.lastParts.filter { it.kind != "text" }
            if (speakBack) speaker.speak(outcome.reply)
            busy = false
        }
    }
    fun send(message: String) {
        val files = attachments
        if (message.isBlank() && files.isEmpty()) return
        scope.launch {
            busy = true
            busyLabel = "Preparing context preview"
            val prepared = runCatching { RunContextPreview.prepare(container, message, files) }.getOrElse {
                listOf(RunContextPart("message", "Your message", "Typed by you for this run", "Current",
                    message.ifBlank { "Look at what I attached and help me with it." }, editable = false))
            }
            reviewMessage = message
            reviewFiles = files
            reviewParts = prepared
            busyLabel = null
            busy = false
        }
    }

    // A question handed over from Search ("Ask Atlas") is reviewed before its first network request.
    androidx.compose.runtime.LaunchedEffect(Unit) { AskAtlas.take()?.let { send(it) } }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Atlas", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            if (turns.isNotEmpty()) Text("New chat", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(role = Role.Button) { scope.launch { container.agentMemory.clearChat() } }
                    .padding(8.dp))
        }

        if (media.isNotEmpty()) MediaStrip(media) { media = emptyList() }

        if (pending.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            com.nizam.app.ui.design.Group {
                Column {
                    Text("Before I do this", style = MaterialTheme.typography.titleMedium)
                    pending.forEach { (name, args) ->
                        // readable, not raw JSON: "send sms · who: Ammi, body: …"
                        val detail = args.keys().asSequence().joinToString(", ") { k -> "$k: ${args.optString(k).take(60)}" }
                        Text(name.replace('_', ' ').replaceFirstChar { it.uppercase() } + if (detail.isNotBlank()) " · $detail" else "",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            scope.launch {
                                val done = pending.map { (name, args) ->
                                    val r = Actions.run(container, name, args)
                                    (if (r.ok) "✓ " else "✕ ") + r.summary
                                }
                                container.agentMemory.addTurn(false, done.joinToString("\n"))
                                pending = emptyList()
                            }
                        }) { Text("Approve") }
                        OutlinedButton(onClick = { pending = emptyList() }) { Text("Cancel") }
                    }
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        val listState = androidx.compose.foundation.lazy.rememberLazyListState()
        androidx.compose.runtime.LaunchedEffect(turns.size, streaming.length / 60, busy) {
            val n = listState.layoutInfo.totalItemsCount
            if (n > 0) listState.animateScrollToItem(n - 1)
        }
        LazyColumn(Modifier.weight(1f), state = listState) {
            if (turns.isEmpty()) {
                item {
                    Text("Ask anything, or tell Atlas what to do. It can act in the app, on your phone and in your connected tools.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 14.dp))
                    suggestions.take(3).forEach { s ->
                        com.nizam.app.ui.design.Group(Modifier.padding(bottom = 8.dp)) {
                            Text(s.title, style = MaterialTheme.typography.titleMedium)
                            Text(s.body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    com.nizam.app.ui.design.Label("Try")
                    listOf(
                        "What should I do today?",
                        "Build me a module to track my bike's fuel",
                        "Make the theme deep blue and gold",
                        "Search the web for a good beginner Arabic course"
                    ).forEach { idea ->
                        Text(
                            idea,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.fillMaxWidth()
                                .clickable { send(idea) }.padding(vertical = 10.dp)
                        )
                    }
                    Text("Refresh suggestions", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(role = Role.Button) {
                            scope.launch {
                                busy = true
                                runCatching { container.curatorRepository.refreshSuggestions() }
                                busy = false
                            }
                        }.padding(vertical = 12.dp))
                }
            }

            itemsIndexed(turns, key = { _, t -> t.id }) { i, turn ->
                if (turn.fromUser) {
                    com.nizam.app.feature.council.UserBubble(turn.text)
                } else {
                    val asked = turns.subList(0, i).lastOrNull { it.fromUser }?.text
                    AtlasReply(turn.text, onRetry = if (asked != null && i == turns.lastIndex && !busy) ({ send(asked) }) else null)
                }
            }
            if (streaming.isNotBlank()) {
                item { AtlasReply(streaming, live = true) }
            } else if (busy) {
                item {
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        com.nizam.app.ui.components.Thinking(label = busyLabel)
                    }
                }
            }
        }

        com.nizam.app.core.ChipRow(ChatModes.PERSONAS, ChatModes.persona, { ChatModes.persona = it },
            modifier = androidx.compose.ui.Modifier.padding(horizontal = 12.dp))
        com.nizam.app.ui.components.Composer(
            draft = draft, onDraft = { draft = it },
            attachments = attachments, onAttachments = { attachments = it },
            busy = busy,
            placeholder = if (attachments.isEmpty()) "Tell Atlas what to do…" else "Ask about these files…",
            onSend = { send(draft) },
            onMic = { listener.launch(speechIntent()) },
            toggles = listOf(
                com.nizam.app.ui.components.ComposerToggle("Web", "Search the web while answering",
                    androidx.compose.material.icons.Icons.Filled.Language, useWeb) { useWeb = it },
                com.nizam.app.ui.components.ComposerToggle("Voice", "Read replies aloud",
                    androidx.compose.material.icons.Icons.Filled.VolumeUp, speakBack) { speakBack = it; if (!it) speaker.stop() },
                com.nizam.app.ui.components.ComposerToggle("Auto", "Run actions without asking first",
                    androidx.compose.material.icons.Icons.Filled.Bolt, autoApprove) { autoApprove = it },
                com.nizam.app.ui.components.ComposerToggle("Think", "Reason it through before answering",
                    androidx.compose.material.icons.Icons.Filled.Psychology, ChatModes.think) { ChatModes.think = it },
                com.nizam.app.ui.components.ComposerToggle("DeepSearch", "Many web searches, one sourced report",
                    androidx.compose.material.icons.Icons.Filled.TravelExplore, ChatModes.deep) { ChatModes.deep = it },
                com.nizam.app.ui.components.ComposerToggle("Contemplate", "Several minds in parallel, weighed into one answer",
                    androidx.compose.material.icons.Icons.Filled.People, ChatModes.contemplate) { ChatModes.contemplate = it },
                com.nizam.app.ui.components.ComposerToggle("Imagine", "Turn your words into an image",
                    androidx.compose.material.icons.Icons.Filled.Brush, ChatModes.imagine) { ChatModes.imagine = it },
                com.nizam.app.ui.components.ComposerToggle("Private", "Nothing from this chat is remembered or learned",
                    androidx.compose.material.icons.Icons.Filled.VisibilityOff, ChatModes.private) { ChatModes.private = it }
            )
        )
    }
    if (reviewMessage != null) RunContextPreviewDialog(
        parts = reviewParts,
        onChange = { id, transform -> reviewParts = reviewParts.map { part -> if (part.id == id) transform(part) else part } },
        onCancel = { reviewMessage = null },
        onContinue = {
            val message = reviewMessage.orEmpty()
            val files = reviewFiles
            val parts = reviewParts
            reviewMessage = null
            performSend(message, files, parts)
        }
    )
}


/**
 * An Atlas turn: no bubble, the orb and name, then the answer. Underneath, quiet actions:
 * copy, share, read aloud, and ask again. Text can be selected.
 */
@Composable
private fun AtlasReply(text: String, live: Boolean = false, onRetry: (() -> Unit)? = null) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.nizam.app.ui.design.AtlasOrb(if (live) com.nizam.app.ui.design.OrbState.Speaking else com.nizam.app.ui.design.OrbState.Idle, diameter = 20.dp)
            Spacer(Modifier.width(6.dp))
            Text("Atlas", style = MaterialTheme.typography.labelMedium, color = c.primary)
        }
        Spacer(Modifier.height(4.dp))
        val glow = if (live) androidx.compose.animation.core.rememberInfiniteTransition(label = "stream").animateFloat(0.72f, 1f,
            androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(700), androidx.compose.animation.core.RepeatMode.Reverse), label = "a").value else 1f
        androidx.compose.foundation.text.selection.SelectionContainer(Modifier.graphicsLayer { alpha = glow }) { com.nizam.app.ui.components.ChatText(text) }
        if (!live && text.isNotBlank()) Row(Modifier.padding(top = 6.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)) {
            ReplyAction(androidx.compose.material.icons.Icons.Outlined.ContentCopy, "Copy") {
                val cm = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Atlas", text))
                com.nizam.app.ui.components.Feedback.show("Copied")
            }
            ReplyAction(androidx.compose.material.icons.Icons.Outlined.Share, "Share") {
                ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                    .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text), "Share"))
            }
            ReplyAction(androidx.compose.material.icons.Icons.Outlined.VolumeUp, "Read aloud") { ReadAloud.say(ctx, text) }
            if (onRetry != null) ReplyAction(androidx.compose.material.icons.Icons.Outlined.Refresh, "Ask again", onRetry)
        }
    }
}

@Composable
private fun ReplyAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    androidx.compose.material3.Icon(icon, label, tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(34.dp).clip(androidx.compose.foundation.shape.CircleShape)
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick).padding(8.dp))
}

/** One shared voice for reading replies aloud on request. */
private object ReadAloud {
    private var tts: android.speech.tts.TextToSpeech? = null
    fun say(c: android.content.Context, text: String) {
        val clean = text.replace(Regex("[*#`>_\\[\\]]"), "").take(3900)
        tts?.let { if (it.isSpeaking) { it.stop(); return }; it.speak(clean, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "atlas") ; return }
        tts = android.speech.tts.TextToSpeech(c.applicationContext) { ok ->
            if (ok == android.speech.tts.TextToSpeech.SUCCESS) tts?.speak(clean, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "atlas")
        }
    }
}

/** Images, audio and files a connected tool returned with its answer. */
@androidx.compose.runtime.Composable
private fun MediaStrip(parts: List<com.nizam.app.feature.mcp.McpPart>, onDismiss: () -> Unit) {
    val c = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("FROM THE TOOL", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            parts.forEach { part ->
                when (part.kind) {
                    "image" -> part.file?.let { f ->
                        val bmp = androidx.compose.runtime.remember(f.path) {
                            runCatching {
                                android.graphics.BitmapFactory.decodeFile(f.path)
                                    ?.let { bm -> bm.asImageBitmap() }
                            }.getOrNull()
                        }
                        bmp?.let {
                            androidx.compose.foundation.Image(it, "tool image",
                                contentScale = androidx.compose.ui.layout.ContentScale.FillWidth,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                        }
                    }
                    "audio" -> part.file?.let { f ->
                        Text("▸ Play audio", color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable(role = Role.Button) {
                                com.nizam.app.ui.design.QuickAudio.play(f.path)
                            }.padding(vertical = 8.dp))
                    }
                    else -> Text(part.file?.name ?: part.uri, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(role = Role.Button) {
                            runCatching {
                                val uri = part.file?.let {
                                    androidx.core.content.FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", it)
                                } ?: android.net.Uri.parse(part.uri)
                                c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW)
                                    .setDataAndType(uri, part.mime.ifBlank { "*/*" })
                                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION))
                            }
                        }.padding(vertical = 6.dp))
                }
            }
            Text("dismiss", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(role = Role.Button) { onDismiss() }.padding(top = 6.dp))
        }
    }
}


/** Hands a question to the chat screen from elsewhere (Search, the command bar). */
object AskAtlas {
    @Volatile private var pending: String? = null
    fun put(q: String) { pending = q }
    fun take(): String? = pending.also { pending = null }
}
