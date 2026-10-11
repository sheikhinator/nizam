package com.nizam.app.feature.presence

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.agent.Snapshot
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import kotlinx.coroutines.launch

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) { content() } }
}

@Composable
fun PresenceScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var tab by remember { mutableStateOf("Presence") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Presence", "How much Atlas notices, decides and interrupts")
        ChipRow(listOf("Presence", "Autonomy", "Intentions", "Model", "Review"), tab, { tab = it })
        Spacer(Modifier.height(8.dp))
        when (tab) {
            "Presence" -> {
                var name by remember { mutableStateOf(Presence.atlasName(c)) }
                var aloud by remember { mutableStateOf(Presence.speakAloud(c)) }
                var budget by remember { mutableStateOf(Presence.budgetPerDay(c)) }
                var work by remember { mutableStateOf("") }
                Card {
                    Text("What it can tell right now", style = MaterialTheme.typography.titleMedium)
                    Text(Presence.context(c), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    Text("Inferred from the clock, charging state, your saved places and what's playing — no background " +
                        "sensing, no battery cost.", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Card {
                    Text("Interruption budget", style = MaterialTheme.typography.titleMedium)
                    Text("${Presence.spokenToday(c)} of $budget used today", style = MaterialTheme.typography.bodyMedium)
                    ChipRow(listOf("0", "2", "4", "8"), "$budget", { budget = it.toInt(); Presence.setBudget(c, budget) },
                        labels = { if (it == "0") "Silent" else "$it a day" })
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                        Switch(aloud, { aloud = it; Presence.setSpeakAloud(c, it) }); Spacer(Modifier.width(10.dp))
                        Text("Speak nudges aloud when earphones are in", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Card {
                    Text("What should I be called?", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(name, { name = it; Presence.setAtlasName(c, it) }, singleLine = true,
                        label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                }
                Card {
                    Text("Working memory", style = MaterialTheme.typography.titleMedium)
                    Text("What's live right now — expires by itself after three days.", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(work, { work = it }, label = { Text("e.g. visitors this weekend, deadline Thursday") },
                        modifier = Modifier.fillMaxWidth())
                    Button(enabled = work.isNotBlank(), onClick = { Presence.addWorking(c, work.trim()); work = ""; Feedback.show("Noted") }) { Text("Add") }
                    Presence.working(c).forEach { Text("· ${it.first}", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            "Autonomy" -> {
                Text("How far Atlas may act on its own, per area. Changed any time.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Presence.AREAS.forEach { area ->
                    var level by remember { mutableStateOf(Presence.autonomy(c, area)) }
                    Card {
                        Text(area, style = MaterialTheme.typography.titleMedium)
                        ChipRow(listOf("Never", "Ask first", "Just do it"), level, { level = it; Presence.setAutonomy(c, area, it) })
                    }
                }
            }
            "Intentions" -> {
                var list by remember { mutableStateOf(Presence.intentions(c)) }
                var text by remember { mutableStateOf("") }
                Card {
                    Text("Standing intentions", style = MaterialTheme.typography.titleMedium)
                    Text("State it once. Atlas keeps looking for ways to move it forward, in missions, planning and advice.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(text, { text = it }, label = { Text("e.g. become fluent in Arabic") }, modifier = Modifier.fillMaxWidth())
                    Button(enabled = text.isNotBlank(), onClick = {
                        list = list + text.trim(); Presence.setIntentions(c, list); text = ""; Feedback.show("Intention set")
                    }) { Text("Set it") }
                }
                list.forEach { i ->
                    Card {
                        Text(i, style = MaterialTheme.typography.bodyLarge)
                        Text("remove", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clickable(role = Role.Button) { list = list - i; Presence.setIntentions(c, list) })
                    }
                }
            }
            "Model" -> Card {
                Text("What Atlas thinks it knows about you", style = MaterialTheme.typography.titleMedium)
                Text("Built from evidence, shown to you in full, and correctable — nothing hidden.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        runCatching {
                            val m = Ai.generate(container.settingsRepository,
                                "${Snapshot.of(container)}\n\nWrite a short working model of this person for your own future " +
                                    "reference: what they value, what they avoid, what they respond well to, what they repeatedly " +
                                    "abandon, and how they prefer to be spoken to. Evidence-based, under 200 words, second person.",
                                maxOutputTokens = 600)
                            Presence.setModel(c, m); Feedback.show("Model updated")
                        }.onFailure { Feedback.failed("Model", it) }
                        busy = false
                    }
                }) { Text(if (busy) "Thinking…" else if (Presence.model(c).isBlank()) "Build it" else "Rebuild") }
                Presence.model(c).takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 10.dp))
                }
            }
            else -> {
                val nudges = remember(busy) { Presence.nudges(c).reversed().take(25) }
                Card {
                    Text("Self-review", style = MaterialTheme.typography.titleMedium)
                    Text("Mark which nudges were actually useful. Atlas reviews its own record weekly and adjusts what it " +
                        "raises.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val rated = nudges.filter { it.third >= 0 }
                    if (rated.isNotEmpty()) Text("${rated.count { it.third == 1 } * 100 / rated.size}% rated useful",
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
                nudges.forEach { (i, text, useful) ->
                    Card {
                        Text(text, style = MaterialTheme.typography.bodyMedium)
                        if (useful < 0) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(top = 6.dp)) {
                            Text("useful", color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.clickable(role = Role.Button) { Presence.rateNudge(c, i, true); busy = !busy })
                            Text("not useful", color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable(role = Role.Button) { Presence.rateNudge(c, i, false); busy = !busy })
                        } else Text(if (useful == 1) "marked useful" else "marked not useful",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Spacer(Modifier.height(60.dp))
    }
}
