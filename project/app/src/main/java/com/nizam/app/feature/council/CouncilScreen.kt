package com.nizam.app.feature.council

import android.app.Activity
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.agent.Actions
import com.nizam.app.feature.voice.speechIntent
import com.nizam.app.net.Ai
import com.nizam.app.ui.theme.DisplayFamily
import com.nizam.app.ui.theme.MonoFamily
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CouncilScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { CouncilStore(context) }
    val engine = remember { CouncilEngine { container } }

    val messages = remember { mutableStateListOf<CouncilMessage>().apply { addAll(store.load()) } }
    var draft by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(CouncilMode.AUTO) }
    var mentioned by remember { mutableStateOf<List<String>>(emptyList()) }
    var convening by remember { mutableStateOf(false) }
    var typing by remember { mutableStateOf<Advisor?>(null) }
    var speaking by remember { mutableStateOf(setOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    val doneActions = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, typing, convening) {
        val count = messages.size + (if (typing != null || convening) 1 else 0) + 1
        if (count > 1) listState.animateScrollToItem(count - 1)
    }

    val listener = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                ?.let { draft = it }
        }
    }

    fun ask(question: String) {
        if (question.isBlank() || convening) return
        // @Name in the text addresses that advisor directly, same as tapping their seat
        val tagged = Council.seats.filter { question.contains("@${it.name}", true) }.map { it.id }
        val targets = (mentioned + tagged).distinct()
        messages.add(CouncilMessage(System.nanoTime(), "USER", question.trim()))
        store.save(messages)
        draft = ""; mentioned = emptyList(); error = null
        scope.launch {
            convening = true
            val session = runCatching {
                engine.convene(question, mode, targets, messages.dropLast(1))
            }.getOrElse { e ->
                convening = false
                error = when (e) {
                    is Ai.MissingKeyException -> e.message
                    else -> "The council couldn't convene: ${com.nizam.app.ui.components.Feedback.friendly(e)}"
                }
                return@launch
            }
            convening = false
            speaking = session.route.toSet()

            // Reveal one voice at a time. Each advisor "thinks" in proportion to what they're about
            // to say, so a long argument takes longer to arrive than a one-line agreement.
            for (turn in session.turns) {
                val advisor = Council.byId(turn.speaker) ?: Council.ARBITER
                typing = advisor
                delay((650L + turn.text.length * 6L).coerceAtMost(2600L))
                typing = null
                messages.add(turn)
                store.save(messages)
                delay(220)
            }
            speaking = emptySet()
        }
    }

    fun runAction(a: ProposedAction) {
        scope.launch {
            val result = Actions.run(container, a.action, a.args)
            if (result.ok) doneActions.add(a.action + a.args.toString())
            messages.add(
                CouncilMessage(
                    System.nanoTime(), "ARBITER",
                    (if (result.ok) "Done — " else "Couldn't do that — ") + result.summary,
                    kind = "SYSTEM"
                )
            )
            store.save(messages)
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {

        // ── Chamber header: the seats ──────────────────────────────────────────
        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("The Council", style = MaterialTheme.typography.headlineMedium.copy(fontFamily = DisplayFamily))
                    Text(
                        when {
                            convening -> "Convening…"
                            typing != null -> "${typing!!.name} has the floor"
                            else -> "Six advisors. One verdict."
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (messages.isNotEmpty()) {
                    Text(
                        "clear",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable(role = Role.Button) {
                                messages.clear(); store.clear(); doneActions.clear()
                            }
                            .padding(8.dp)
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            // Seats: tap one to address it directly. Lit when speaking this round.
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Council.seats.forEach { advisor ->
                    val selected = advisor.id in mentioned
                    val live = advisor.id in speaking || typing?.id == advisor.id
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.clickable(role = Role.Button) {
                            mentioned = if (selected) mentioned - advisor.id else mentioned + advisor.id
                        }
                    ) {
                        Box {
                            PixelAvatar(advisor, 44.dp, glow = selected || live)
                            if (live) {
                                Box(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .size(10.dp)
                                        .background(advisor.colour, CircleShape)
                                        .border(2.dp, MaterialTheme.colorScheme.background, CircleShape)
                                )
                            }
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            advisor.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) advisor.colour else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    CouncilMode.AUTO to "⚡ Auto",
                    CouncilMode.FULL to "◉ Full council",
                    CouncilMode.DEEP to "◈ Deep"
                ).forEach { (m, label) ->
                    val on = mode == m
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .background(
                                if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(20.dp)
                            )
                            .clickable(role = Role.Button) { mode = m }
                            .padding(horizontal = 12.dp, vertical = 7.dp)
                    )
                }
            }
            if (mode == CouncilMode.DEEP) {
                Text(
                    "Deep: each advisor answers independently, then the Arbiter weighs them. ~7 AI calls.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        // ── The floor ──────────────────────────────────────────────────────────
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 14.dp)
        ) {
            if (messages.isEmpty() && !convening) {
                item { EmptyChamber(onPick = { ask(it) }) }
            }
            items(messages, key = { it.id }) { m ->
                AnimatedVisibility(
                    visible = true,
                    enter = fadeIn() + slideInVertically { it / 3 }
                ) {
                    when {
                        m.speaker == "USER" -> UserBubble(m.text)
                        m.kind == "VERDICT" -> VerdictCard(m, { runAction(it) }, doneActions.toSet())
                        m.kind == "SYSTEM" -> Text(
                            m.text,
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 44.dp)
                        )
                        else -> Column {
                            AdvisorBubble(m) { a -> mentioned = listOf(a.id) }
                            if (m.actions.isNotEmpty()) {
                                Box(Modifier.padding(start = 44.dp)) {
                                    ActionRow(m.actions, { runAction(it) }, doneActions.toSet())
                                }
                            }
                        }
                    }
                }
            }
            if (convening) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 14.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Council.seats.forEach { a ->
                            Box(Modifier.padding(horizontal = 3.dp)) { PixelAvatar(a, 26.dp) }
                        }
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        TypingDots(MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        if (mode == CouncilMode.DEEP) "Each advisor is deliberating alone…"
                        else "The council is convening…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }
            typing?.let { a ->
                item(key = "typing") {
                    TypingBubble(a, if (a.id == "ARBITER") "is weighing the council" else "is thinking")
                }
            }
            error?.let { e ->
                item {
                    Text(
                        e, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }

        // ── Addressing + input ─────────────────────────────────────────────────
        if (mentioned.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("To ", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                mentioned.mapNotNull { Council.byId(it) }.forEach { a ->
                    Text(
                        "@${a.name}  ",
                        style = MaterialTheme.typography.labelMedium,
                        color = a.colour
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "everyone",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(role = Role.Button) { mentioned = emptyList() }
                )
            }
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(28.dp))
                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(28.dp))
                .padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape)
                    .clickable(role = Role.Button) { listener.launch(speechIntent()) },
                contentAlignment = Alignment.Center
            ) { Text("🎙", style = MaterialTheme.typography.titleMedium) }

            Box(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                if (draft.isEmpty()) {
                    Text(
                        if (mentioned.isEmpty()) "Bring a decision to the council…"
                        else "Ask ${mentioned.mapNotNull { Council.byId(it)?.name }.joinToString(" & ")}…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    maxLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            val canSend = draft.isNotBlank() && !convening && typing == null
            Box(
                Modifier
                    .size(40.dp)
                    .background(
                        if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        CircleShape
                    )
                    .clickable(enabled = canSend, role = Role.Button) { ask(draft) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "↑",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (canSend) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun EmptyChamber(onPick: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 20.dp, bottom = 10.dp)) {
        Text(
            "Bring them anything you're weighing. They'll argue it from six directions, " +
                "and the Arbiter will tell you what to do — and what it couldn't settle.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(18.dp))
        Council.seats.forEach { a ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                PixelAvatar(a, 30.dp)
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("${a.name} · ${a.title}", style = MaterialTheme.typography.bodyMedium, color = a.colour)
                    Text(
                        a.lens.substringBefore('.') + ".",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("TRY", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOf(
            "Should I take a second job on weekends?",
            "I want to buy a motorbike on instalments. Should I?",
            "Am I spending my evenings well?",
            "@Ledger can I afford a 60,000 course right now?"
        ).forEach { idea ->
            Row(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { onPick(idea) }.padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Filled.ArrowForward, contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(idea, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
