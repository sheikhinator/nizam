package com.nizam.app.feature.jarvis

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.fmtAmount
import com.nizam.app.ui.components.ChatText
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.components.Thinking
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.launch

@Composable
private fun Toggle(title: String, detail: String, key: String, default: Boolean = false, onChange: (Boolean) -> Unit = {}) {
    val c = LocalContext.current
    var on by remember { mutableStateOf(RadarSettings.on(c, key, default)) }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(on, { on = it; RadarSettings.set(c, key, it); onChange(it) })
    }
}

/** People and money you'd otherwise lose track of. */
@Composable
fun RadarTab(container: AppContainer) {
    val c = LocalContext.current
    Group {
        Toggle("Before you reply", "When someone messages, Atlas shows what's still open between you — promises, tasks.", "recall", true)
    }
    Label("Family — silah rahmi")
    var family by remember { mutableStateOf(RadarSettings.text(c, "family")) }
    var days by remember { mutableStateOf(RadarSettings.text(c, "familyDays", "7")) }
    OutlinedTextField(family, { family = it; RadarSettings.setText(c, "family", it) }, modifier = Modifier.fillMaxWidth(),
        label = { Text("Names, as they appear in your messages") }, placeholder = { Text("Ammi, Abbu, Ayesha, Chacha Rashid") })
    OutlinedTextField(days, { days = it.filter(Char::isDigit); RadarSettings.setText(c, "familyDays", days) }, singleLine = true,
        label = { Text("Nudge after this many days") }, modifier = Modifier.fillMaxWidth())
    val status = remember(family) { Silah.status(c) }
    status.forEach { (name, d) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(if (d == null) "not heard from lately" else if (d == 0L) "today" else "$d days ago",
                style = MaterialTheme.typography.labelMedium,
                color = if (d == null || d >= (days.toLongOrNull() ?: 7)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Text("Counted from messages that reach this phone (needs notification access).", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)

    Label("Bills and subscriptions")
    Group { Toggle("Warn before they renew", "Two days before a recurring charge, so there's time to cancel.", "bills", true) }
    var bills by remember { mutableStateOf<List<Recurring>?>(null) }
    LaunchedEffect(Unit) { bills = runCatching { BillRadar.find(container) }.getOrElse { emptyList() } }
    when {
        bills == null -> Thinking(label = "Looking for recurring charges")
        bills!!.isEmpty() -> Text("Nothing recurring found yet. Charges logged in Finance (or read from bank texts) with the same payee " +
            "and a steady rhythm appear here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> {
            val monthly = bills!!.sumOf { it.amount * 30.0 / it.everyDays.coerceAtLeast(1) }
            Text("About ${fmtAmount(monthly)} a month on autopilot", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 6.dp))
            bills!!.forEach { r ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = MaterialTheme.typography.bodyLarge)
                        Text("every ${r.everyDays} days · next ${r.next}", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(fmtAmount(r.amount), style = com.nizam.app.ui.theme.MonoNumber)
                }
            }
        }
    }
}

/** Things Atlas does on a rhythm: before you wake, while you pray, and at the end of the week. */
@Composable
fun RoutinesTab(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var brief by remember { mutableStateOf(Briefing.today(c)) }
    var busy by remember { mutableStateOf(false) }
    Label("Morning briefing")
    Group {
        Toggle("Prepare it overnight", "Written before Fajr from your day, calendar, bills and anything waiting on you.", "briefing", true)
        if (brief != null) ChatText(brief!!, Modifier.padding(top = 8.dp))
        else if (busy) Thinking(label = "Writing today's briefing")
        Button(enabled = !busy, modifier = Modifier.padding(top = 6.dp), onClick = {
            busy = true
            scope.launch {
                runCatching { Briefing.prepare(c, container) }.onSuccess { brief = it.trim() }.onFailure { Feedback.failed("The briefing", it) }
                busy = false
            }
        }) { Text(if (brief == null) "Write today's briefing" else "Rewrite it") }
    }

    Label("Auto-reply while busy")
    var prayerReply by remember { mutableStateOf(RadarSettings.text(c, "prayerReply", "At prayer right now — I'll get back to you shortly.")) }
    var focusReply by remember { mutableStateOf(RadarSettings.text(c, "focusReply", "In a focus session — I'll reply when I'm done.")) }
    Group {
        Toggle("Reply for me during prayer and focus", "Once per person every two hours, through the message's own reply button.", "autoreply")
        OutlinedTextField(prayerReply, { prayerReply = it; RadarSettings.setText(c, "prayerReply", it) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("During prayer") })
        OutlinedTextField(focusReply, { focusReply = it; RadarSettings.setText(c, "focusReply", it) }, modifier = Modifier.fillMaxWidth(),
            label = { Text("During a focus session") })
    }

    Label("Accountability partner")
    var partner by remember { mutableStateOf(RadarSettings.text(c, "partner")) }
    var preview by remember { mutableStateOf("") }
    Group {
        Text("Every Sunday evening, your week in one line — ready to send on WhatsApp with one tap.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(partner, { partner = it; RadarSettings.setText(c, "partner", it) }, singleLine = true,
            modifier = Modifier.fillMaxWidth(), label = { Text("Their WhatsApp number, with country code") },
            placeholder = { Text("+92 300 1234567") })
        Text("Preview this week's", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp).clickable(role = Role.Button) {
                scope.launch { preview = Accountability.summary(container) }
            })
        if (preview.isNotBlank()) Text(preview, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
    }

    Label("Earn your scroll")
    var earned by remember { mutableIntStateOf(-1) }
    LaunchedEffect(Unit) { earned = runCatching { Earn.balance(container) }.getOrElse { 0 } }
    Group {
        Toggle("Paused apps cost earned minutes", "Opening a paused app spends 15 minutes you earned today: +5 per task, +5 per prayer on time, +10 for adhkar.", "earn")
        if (earned >= 0) Text("Balance today: $earned minutes", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
        Text("Choose which apps are paused in Wellbeing → Pause.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
