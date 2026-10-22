package com.nizam.app.feature.growth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.insight.DayVector
import com.nizam.app.feature.insight.Days
import com.nizam.app.feature.insight.METRICS
import com.nizam.app.feature.progress.ProgressSummary
import com.nizam.app.feature.progress.Xp
import com.nizam.app.ui.theme.MonoNumber
import java.time.LocalDate

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) { content() } }
}

@Composable
fun GrowthScreen(container: AppContainer) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf("Challenges") }
    var days by remember { mutableStateOf<List<DayVector>>(emptyList()) }
    var summary by remember { mutableStateOf<ProgressSummary?>(null) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard {
        days = runCatching { Days.build(container, context, 365) }.getOrElse { emptyList() }
        summary = runCatching { Xp.summarise(container) }.getOrNull()
    } }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Growth", "Challenges, habit stacks, streak insurance and what you've earned")
        summary?.let { s ->
            Card {
                Text("🔥 ${s.streak}-day streak · best ${s.bestStreak}", style = MaterialTheme.typography.titleMedium)
                Text("🧊 ${s.freezes} streak freeze${if (s.freezes == 1) "" else "s"} banked (max 3)",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Text("Every 7 active days in a row earns a freeze. A missed day spends one instead of breaking your streak.",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ChipRow(listOf("Challenges", "Stacks", "Badges"), tab, { tab = it })
        when (tab) {
            "Challenges" -> Challenges(days)
            "Stacks" -> Stacks()
            else -> summary?.let { BadgeGrid(it, days) }
        }
        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun Challenges(days: List<DayVector>) {
    val c = LocalContext.current
    val store = remember { ChallengeStore(c) }
    var list by remember { mutableStateOf(store.all()) }
    var title by remember { mutableStateOf("") }
    var metric by remember { mutableStateOf("prayers") }
    var target by remember { mutableStateOf("5") }
    var length by remember { mutableStateOf("30") }
    Card {
        Text("New challenge", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(title, { title = it }, label = { Text("e.g. 30 days of all five prayers") }, modifier = Modifier.fillMaxWidth())
        ChipRow(METRICS.keys.filter { it != "spend" }, metric, { metric = it }, labels = { METRICS[it] ?: it })
        Row {
            OutlinedTextField(target, { target = it.filter { ch -> ch.isDigit() || ch == '.' } },
                label = { Text("Daily target") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(length, { length = it.filter(Char::isDigit) }, label = { Text("Days") },
                singleLine = true, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Button(enabled = title.isNotBlank(), onClick = {
            list = list + Challenge(System.currentTimeMillis(), title.trim(), metric, target.toDoubleOrNull() ?: 1.0,
                LocalDate.now().toString(), (length.toIntOrNull() ?: 30).coerceIn(3, 100))
            store.save(list); title = ""
        }) { Text("Start") }
    }
    list.sortedByDescending { it.id }.forEach { ch ->
        val p = ChallengeStore.progress(ch, days)
        Card {
            Text(ch.title, style = MaterialTheme.typography.titleMedium)
            Text("${METRICS[ch.metric]} ≥ ${ch.target.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }} a day",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(progress = { p.hit / ch.days.toFloat() }, modifier = Modifier.fillMaxWidth())
            Text("${p.hit} of ${ch.days} days hit · day ${p.elapsed}", style = MonoNumber)
            if (p.done) Text(if (p.won) "🏆 Challenge won" else "Finished — ${p.hit}/${ch.days}. Winning needs 80%.",
                color = MaterialTheme.colorScheme.primary)
            Text("remove", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(role = Role.Button) { list = list - ch; store.save(list) }.padding(top = 4.dp))
        }
    }
}

@Composable
private fun Stacks() {
    val c = LocalContext.current
    val store = remember { StackStore(c) }
    var list by remember { mutableStateOf(store.all()) }
    var done by remember { mutableStateOf(store.doneToday(c).toSet()) }
    var anchor by remember { mutableStateOf("") }
    var steps by remember { mutableStateOf("") }
    Card {
        Text("New habit stack", style = MaterialTheme.typography.titleMedium)
        Text("Chain new habits onto something you already do every day.", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(anchor, { anchor = it }, label = { Text("After I… (e.g. pray Fajr)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(steps, { steps = it }, label = { Text("I will… (one per line)") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = anchor.isNotBlank() && steps.isNotBlank(), onClick = {
            list = list + Stack(System.currentTimeMillis(), anchor.trim(), steps.lines().map { it.trim() }.filter { it.isNotBlank() })
            store.save(list); anchor = ""; steps = ""
        }) { Text("Save stack") }
    }
    list.forEach { st ->
        Card {
            Text("After I ${st.anchor}…", style = MaterialTheme.typography.titleMedium)
            st.steps.forEachIndexed { i, step ->
                val key = "${st.id}:$i"
                // each link unlocks once the previous one is done — that's the stack
                val unlocked = i == 0 || "${st.id}:${i - 1}" in done
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (unlocked) 1f else 0.4f)) {
                    Checkbox(key in done, { on -> if (unlocked) { store.markDone(c, key, on); done = store.doneToday(c).toSet() } })
                    Text("· $step", style = MaterialTheme.typography.bodyLarge)
                }
            }
            if (st.steps.indices.all { "${st.id}:$it" in done }) Text("✓ Stack complete today", color = MaterialTheme.colorScheme.primary)
            Text("remove", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(role = Role.Button) { list = list - st; store.save(list) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BadgeGrid(s: ProgressSummary, days: List<DayVector>) {
    val badges = remember(s, days) { Badges.all(s, days) }
    Text("${badges.count { it.earned }} of ${badges.size} earned", style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(vertical = 8.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        badges.forEach { b ->
            Surface(shape = MaterialTheme.shapes.medium,
                color = if (b.earned) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(104.dp).alpha(if (b.earned) 1f else 0.45f)) {
                Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(b.emoji, style = MaterialTheme.typography.headlineMedium)
                    Text(b.name, style = MaterialTheme.typography.labelMedium)
                    Text(b.how, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
