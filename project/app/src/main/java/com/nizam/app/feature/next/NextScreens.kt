package com.nizam.app.feature.next

import android.app.Activity
import android.app.role.RoleManager
import android.os.Build
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MobileOff
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.phone.AtlasNotificationListener
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasOrb
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.EmptySpec
import com.nizam.app.ui.design.EmptyState
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OrbState
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.Radius
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Segmented
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.wash
import kotlinx.coroutines.launch

/** Catch me up: everything that came in, boiled down to a few lines, plus who's still waiting on you. */
@Composable
fun CatchUpScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val seen = remember { AtlasNotificationListener.recent(60) }
    val waiting = remember { FollowUps.waiting(ctx, minHours = 1) }
    var summary by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    fun summarise() {
        if (seen.isEmpty()) return
        busy = true
        scope.launch {
            val feed = seen.joinToString("\n") { "[${it.app}] ${it.title}: ${it.text.take(160)}" }
            summary = runCatching {
                Ai.generate(container.settingsRepository,
                    "Here are my recent notifications, newest first:\n$feed\n\nCatch me up in at most 5 short lines. Lead with anything that " +
                        "needs a reply or action from me, name people, merge duplicates, skip promotions and system noise.",
                    system = "You are a concise personal assistant. Plain lines, no preamble.", maxOutputTokens = 400)
            }.getOrElse { offlineSummary(seen) }
            busy = false
        }
    }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { summarise() } }

    AtlasScreen(title = "Catch me up", subtitle = "${seen.size} notifications since you last looked") {
        if (!AtlasNotificationListener.connected) item("perm") {
            ListRow("Let Atlas see notifications", supporting = "Needed to catch you up. Nothing is stored on disk.",
                icon = Icons.Outlined.NotificationsNone) {
                runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            }
        }
        if (seen.isEmpty()) item("empty") {
            EmptyState(EmptySpec(Icons.Outlined.NotificationsNone, "All quiet", "Nothing new has come in. Enjoy it."))
        } else item("sum") {
            Group(Modifier.padding(horizontal = Space.page)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AtlasOrb(if (busy) OrbState.Thinking else OrbState.Idle, diameter = 24.dp)
                    Spacer(Modifier.padding(start = Space.s))
                    Text(if (busy) "Reading it all…" else "The short version", style = MaterialTheme.typography.labelLarge)
                }
                Spacer(Modifier.height(Space.s))
                SelectionContainer { Text(summary ?: "", style = MaterialTheme.typography.bodyLarge) }
            }
        }
        if (waiting.isNotEmpty()) {
            item("w-h") { SectionHeader("Waiting on you") }
            waiting.forEach { w ->
                item("w-${w.key}") {
                    ListRow(w.who, supporting = "${w.app} · ${w.hours} h ago · ${w.text}", icon = Icons.Outlined.Forum) {
                        AtlasNotificationListener.open(ctx, w.key)
                    }
                }
            }
        }
        if (seen.isNotEmpty()) {
            item("all-h") { SectionHeader("Everything, by app") }
            seen.groupBy { it.app }.entries.sortedByDescending { it.value.size }.forEach { (app, list) ->
                item("a-$app") {
                    ListRow("$app · ${list.size}", supporting = list.take(3).joinToString(" · ") { it.title.ifBlank { it.text.take(40) } })
                }
            }
        }
    }
}

/** Without AI: counts by app and the people who wrote, so the screen is never empty. */
private fun offlineSummary(seen: List<AtlasNotificationListener.Seen>): String {
    val byApp = seen.groupBy { it.app }.entries.sortedByDescending { it.value.size }.take(5)
    return byApp.joinToString("\n") { (app, l) ->
        val people = l.map { it.title }.filter { it.isNotBlank() }.distinct().take(3).joinToString(", ")
        "$app: ${l.size}" + if (people.isNotBlank()) " — $people" else ""
    }
}

/**
 * Rambler: say it however it comes out (ums, restarts, Urdu and English mixed) and get back a clean
 * message in the tone you want, ready to copy or send.
 */
@Composable
fun RamblerScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var raw by remember { mutableStateOf("") }
    var clean by remember { mutableStateOf("") }
    var tone by remember { mutableStateOf("Friendly") }
    var lang by remember { mutableStateOf("Same") }
    var busy by remember { mutableStateOf(false) }
    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK)
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { raw = (raw + " " + it).trim() }
    }
    fun polish() {
        if (raw.isBlank()) return
        busy = true
        scope.launch {
            clean = runCatching {
                Ai.generate(container.settingsRepository,
                    "Turn this spoken ramble into one clear message I can send. Keep my meaning and details, drop filler words and restarts. " +
                        "Tone: $tone. Language: ${if (lang == "Same") "the same language(s) I used" else lang}.\n\nRamble:\n$raw",
                    system = "Return only the finished message.", maxOutputTokens = 500).trim()
            }.getOrElse { Feedback.failed("Polishing", it); raw }
            busy = false
        }
    }
    AtlasScreen(title = "Rambler", subtitle = "Talk messily. Get a clean message.") {
        item("in") {
            Column(Modifier.padding(horizontal = Space.page)) {
                OutlinedTextField(raw, { raw = it }, Modifier.fillMaxWidth().height(150.dp),
                    placeholder = { Text("Tap the mic and just talk, or type…") })
                Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    OutlinedButton(onClick = { listen.launch(com.nizam.app.feature.voice.speechIntent()) }) { Text("🎙 Speak") }
                    Button(onClick = { polish() }, enabled = raw.isNotBlank() && !busy) { Text(if (busy) "Polishing…" else "Polish it") }
                }
                Text("Tone", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = Space.l))
                Segmented(listOf("Friendly", "Formal", "Short", "Warm"), tone, { tone = it }, { it })
                Text("Language", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = Space.s))
                Segmented(listOf("Same", "English", "Urdu", "Roman Urdu"), lang, { lang = it }, { it })
            }
        }
        if (clean.isNotBlank()) item("out") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.l)) {
                SelectionContainer { Text(clean, style = MaterialTheme.typography.bodyLarge) }
                Row(Modifier.padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    OutlinedButton(onClick = {
                        (ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                            .setPrimaryClip(android.content.ClipData.newPlainText("Message", clean)); Feedback.show("Copied")
                    }) { Text("Copy") }
                    OutlinedButton(onClick = {
                        ctx.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND)
                            .setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, clean), "Send"))
                    }) { Text("Send…") }
                }
            }
        }
    }
}

/** Safety settings for lost mode, the caller check and follow-up nudges. */
@Composable
fun SafetyScreen(onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    var word by remember { mutableStateOf(LostMode.word(ctx)) }
    var owner by remember { mutableStateOf(LostMode.owner(ctx)) }
    var calls by remember { mutableStateOf(CallCheck.on(ctx)) }
    var follow by remember { mutableStateOf(FollowUps.on(ctx)) }
    val smsPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val role = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { }
    val contacts = com.nizam.app.feature.future.Guardian.contacts(ctx)
    AtlasScreen(title = "Safety", subtitle = "Lost phone, suspicious calls, messages waiting on you") {
        item("lost-h") { SectionHeader("Lost mode") }
        item("lost") {
            Column(Modifier.padding(horizontal = Space.page)) {
                Text(if (contacts.isEmpty()) "First add emergency contacts in Guardian — only they can trigger lost mode."
                    else "From ${contacts.joinToString { it.name }}, text this phone:\n" +
                        "ATLAS LOST ${word.ifBlank { "<word>" }} · ATLAS RING ${word.ifBlank { "<word>" }} · ATLAS WHERE ${word.ifBlank { "<word>" }} · ATLAS FOUND ${word.ifBlank { "<word>" }}",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(word, { word = it.filter { ch -> !ch.isWhitespace() }.take(24) }, Modifier.fillMaxWidth().padding(top = Space.s),
                    label = { Text("Secret word") }, singleLine = true)
                OutlinedTextField(owner, { owner = it.take(80) }, Modifier.fillMaxWidth().padding(top = Space.s),
                    label = { Text("Shown on the lost screen (e.g. a number to call)") }, singleLine = true)
                Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Button(onClick = {
                        LostMode.setWord(ctx, word); LostMode.setOwner(ctx, owner)
                        smsPerm.launch(arrayOf(android.Manifest.permission.RECEIVE_SMS, android.Manifest.permission.SEND_SMS,
                            android.Manifest.permission.ACCESS_FINE_LOCATION))
                        Feedback.show("Lost mode ready")
                    }, enabled = word.length >= 4) { Text("Save") }
                    if (contacts.isEmpty()) OutlinedButton(onClick = { onOpen("power/guardian") }) { Text("Open Guardian") }
                }
            }
        }
        item("call-h") { SectionHeader("Caller check") }
        item("call") {
            ListRow("Check incoming calls", supporting = "Names official bank helplines; warns about unknown numbers. Never blocks a call.",
                icon = Icons.Outlined.Call, trailing = { Switch(calls, { calls = it; CallCheck.set(ctx, it) }) })
        }
        if (Build.VERSION.SDK_INT >= 29) item("role") {
            ListRow("Allow Atlas to check calls", supporting = "Android asks once. Atlas never sees call audio.") {
                val rm = ctx.getSystemService(RoleManager::class.java)
                if (rm != null && !rm.isRoleHeld(RoleManager.ROLE_CALL_SCREENING))
                    role.launch(rm.createRequestRoleIntent(RoleManager.ROLE_CALL_SCREENING))
                else Feedback.show("Already allowed")
            }
        }
        item("f-h") { SectionHeader("Follow-ups") }
        item("f") {
            ListRow("Nudge me about unanswered messages", supporting = "Once per person per day, 9 am–9:30 pm, after ${FollowUps.afterHours(ctx)} hours.",
                icon = Icons.Outlined.Forum, trailing = { Switch(follow, { follow = it; FollowUps.set(ctx, it) }) })
        }
    }
}

/** What a finder sees while lost mode is on. The secret word turns it off. */
@Composable
fun LostScreen(onUnlocked: () -> Unit) {
    val ctx = LocalContext.current
    var attempt by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.clip(Radius.tile).background(wash(MaterialTheme.colorScheme.error, 0.2f)).padding(Space.l)) {
            androidx.compose.material3.Icon(Icons.Outlined.MobileOff, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.padding(Space.s))
        }
        Text("This phone is lost", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = Space.l))
        Text(LostMode.owner(ctx).ifBlank { "Please return it. The owner has been sent its location." },
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, modifier = Modifier.padding(top = Space.s))
        OutlinedTextField(attempt, { attempt = it }, Modifier.fillMaxWidth().padding(top = Space.xl), label = { Text("Owner's word") }, singleLine = true)
        Button(onClick = { if (LostMode.unlock(ctx, attempt)) onUnlocked() else Feedback.show("That's not it") },
            modifier = Modifier.padding(top = Space.s)) { Text("Unlock") }
    }
}
