package com.nizam.app.feature.jarvis

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.components.Thinking
import kotlinx.coroutines.launch

/** Everything that makes Atlas act without being asked twice: orders, autopilots, and hands-free. */
@Composable
fun JarvisScreen(container: AppContainer) {
    var tab by remember { mutableStateOf("Orders") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Jarvis", "Atlas acting on its own — on events, on goals, and on your voice")
        ChipRow(listOf("Orders", "Autopilot", "Radar", "Routines", "Hands-free", "Replay"), tab, { tab = it })
        Spacer(Modifier.height(8.dp))
        when (tab) {
            "Orders" -> OrdersTab()
            "Autopilot" -> AutopilotTab(container)
            "Radar" -> RadarTab(container)
            "Routines" -> RoutinesTab(container)
            "Hands-free" -> HandsFreeTab()
            else -> ReplayTab(container)
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))

@Composable
private fun OrdersTab() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberWorkScope()
    var orders by remember { mutableStateOf(StandingOrders.all(c)) }
    var text by remember { mutableStateOf("") }
    var auto by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    Hint("Tell Atlas what to do whenever something happens. It watches your notifications (bank texts, WhatsApp, " +
        "email) and the apps you open. Matching happens on the phone; the AI only wakes when an order fires.")
    OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth(), minLines = 2,
        label = { Text("Whenever…") },
        placeholder = { Text("Whenever my bank texts about a spend over 10,000, log it and warn me if I'm over budget") })
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        Switch(auto, { auto = it })
        Spacer(Modifier.width(10.dp))
        Text(if (auto) "Acts on its own, including messages and taps" else "Asks before anything that speaks or acts for you",
            style = MaterialTheme.typography.labelMedium)
    }
    Button(enabled = text.isNotBlank() && !busy, onClick = {
        busy = true
        scope.launch {
            runCatching { StandingOrders.compile(c, text, auto) }
                .onSuccess { o -> StandingOrders.save(c, StandingOrders.all(c) + o); orders = StandingOrders.all(c); text = ""; Feedback.show("Order is live") }
                .onFailure { Feedback.failed("Setting up the order", it) }
            busy = false
        }
    }) { Text(if (busy) "Understanding…" else "Make it an order") }
    listOf(
        "When Ali messages me on WhatsApp, tell me in one line what he wants",
        "Whenever I open Instagram after 11pm, remind me Fajr is early",
        "When an email mentions an invoice or payment due, add a task with the date"
    ).forEach { idea ->
        Text("✦  $idea", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { text = idea }.padding(vertical = 6.dp))
    }
    if (!com.nizam.app.feature.phone.AtlasNotificationListener.connected)
        Hint("⚠ Notification access is off, so message-based orders can't fire. Grant it in Phone control.")
    orders.forEach { o ->
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), modifier = Modifier.padding(top = 12.dp))
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(o.text, style = MaterialTheme.typography.titleSmall)
                Text(buildString {
                    append(if (o.event == "app_open") "on app open" else "on notification")
                    if (o.apps.isNotEmpty()) append(" · " + o.apps.joinToString())
                    if (o.keywords.isNotEmpty()) append(" · \"" + o.keywords.take(4).joinToString("\", \"") + "\"")
                    if (o.minAmount > 0) append(" · ≥ ${o.minAmount.toLong()}")
                    if (o.fromHour != 0 || o.toHour != 24) append(" · ${o.fromHour}:00–${o.toHour}:00")
                    append(" · fired ${o.fired}×")
                    if (o.auto) append(" · acts alone")
                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(o.enabled, { on ->
                StandingOrders.save(c, StandingOrders.all(c).map { if (it.id == o.id) it.copy(enabled = on) else it })
                orders = StandingOrders.all(c)
            })
        }
        if (o.lastResult.isNotBlank()) Text("Last: " + o.lastResult, style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp))
        Text("remove", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clickable(role = Role.Button) {
                StandingOrders.save(c, StandingOrders.all(c).filterNot { it.id == o.id }); orders = StandingOrders.all(c)
            }.padding(top = 6.dp))
    }
}

@Composable
private fun AutopilotTab(container: AppContainer) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberWorkScope()
    var pilots by remember { mutableStateOf(Autopilot.all(c)) }
    var goal by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Hint("Give Atlas an outcome. It writes the plan, then works one step every background cycle — searching, " +
        "comparing, adding tasks — and stops to ask when it needs your call.")
    OutlinedTextField(goal, { goal = it }, modifier = Modifier.fillMaxWidth(), minLines = 2, label = { Text("Goal") },
        placeholder = { Text("Plan a 5-day Hunza trip for two under 80,000 in October") })
    Row {
        Button(enabled = goal.isNotBlank() && !busy, onClick = {
            busy = true
            scope.launch {
                runCatching { Autopilot.start(c, goal) }
                    .onSuccess { goal = ""; pilots = Autopilot.all(c); Feedback.show("Autopilot planned — first step runs now") }
                    .onFailure { Feedback.failed("Planning", it) }
                // first step straight away so it feels alive
                runCatching { Autopilot.runDue(c, container, force = true) }
                pilots = Autopilot.all(c); busy = false
            }
        }) { Text(if (busy) "Working…" else "Start autopilot") }
    }
    if (busy) Thinking()
    pilots.sortedByDescending { it.created }.forEach { p ->
        var open by remember(p.id) { mutableStateOf(p.status != "done") }
        var reply by remember(p.id) { mutableStateOf("") }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), modifier = Modifier.padding(top = 14.dp))
        Column(Modifier.fillMaxWidth().clickable(role = Role.Button) { open = !open }.padding(top = 10.dp)) {
            Text(p.goal, style = MaterialTheme.typography.titleSmall)
            Text(when (p.status) { "waiting" -> "Waiting for you"; "done" -> "Finished"; "paused" -> "Paused"; else -> "Running" } +
                " · ${p.steps.count { it.status == "done" }}/${p.steps.size} steps",
                style = MaterialTheme.typography.labelSmall,
                color = if (p.status == "waiting") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            LinearProgressIndicator(progress = { p.progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        if (open) {
            p.steps.forEachIndexed { i, s ->
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                    androidx.compose.foundation.layout.Box(Modifier.padding(top = 5.dp).width(8.dp).height(8.dp).background(
                        when (s.status) {
                            "done" -> MaterialTheme.colorScheme.primary
                            "waiting" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f)
                        }, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("${i + 1}. ${s.title}", style = MaterialTheme.typography.bodyMedium)
                        if (s.result.isNotBlank()) com.nizam.app.ui.components.ChatText(s.result.take(1500))
                    }
                }
            }
            if (p.status == "waiting") {
                Text(p.question, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 10.dp))
                OutlinedTextField(reply, { reply = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Your answer") })
                Button(enabled = reply.isNotBlank(), onClick = {
                    Autopilot.answer(c, p.id, reply); reply = ""
                    scope.launch { runCatching { Autopilot.runDue(c, container, force = true) }; pilots = Autopilot.all(c) }
                    pilots = Autopilot.all(c)
                }) { Text("Continue") }
            }
            Row(Modifier.padding(top = 8.dp)) {
                if (p.status == "running") Text("run next step now", color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable(role = Role.Button) {
                        scope.launch { busy = true; runCatching { Autopilot.runDue(c, container, force = true) }; pilots = Autopilot.all(c); busy = false }
                    }.padding(end = 18.dp))
                if (p.status == "running" || p.status == "paused") Text(if (p.status == "paused") "resume" else "pause",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(role = Role.Button) { Autopilot.pause(c, p.id, p.status != "paused"); pilots = Autopilot.all(c) }
                        .padding(end = 18.dp))
                Text("delete", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clickable(role = Role.Button) { Autopilot.delete(c, p.id); pilots = Autopilot.all(c) })
            }
        }
    }
}

@Composable
private fun HandsFreeTab() {
    val c = LocalContext.current
    var wake by remember { mutableStateOf(WakeService.isOn(c)) }
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) { WakeService.start(c); wake = true } else Feedback.show("Microphone permission is needed to hear \"Atlas\"")
    }
    Text("\"Atlas…\" wake word", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
    Hint("Say \"Atlas, how much did I spend this week?\" or \"Atlas, remind me to call Ammi at 8\" — answered aloud. " +
        "Listens only while the screen is on, prefers the on-device speech model, and shows a notification while active. " +
        "It costs some battery, and some phones play a short tone when listening restarts.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(wake, { on ->
            if (!on) { WakeService.stop(c); wake = false }
            else if (ContextCompat.checkSelfPermission(c, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                WakeService.start(c); wake = true
            } else mic.launch(Manifest.permission.RECORD_AUDIO)
        })
        Spacer(Modifier.width(10.dp))
        Text(if (wake) "Listening for \"Atlas\"" else "Off", style = MaterialTheme.typography.labelLarge)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f), modifier = Modifier.padding(vertical = 16.dp))
    Text("Screen co-pilot", style = MaterialTheme.typography.titleMedium)
    Hint("Over any app: summarise it, reply for you in your tone, pull tasks and dates out, translate, or check for a scam. " +
        "Open it from the Quick Settings tile \"Atlas co-pilot\" (edit your quick settings to add it), or long-press the floating " +
        "bubble. It needs screen control on, never presses Send unless you say so, and never touches banking or password apps.")
    Button(onClick = { CopilotActivity.open(c) }) { Text("Try the co-pilot now") }
}
