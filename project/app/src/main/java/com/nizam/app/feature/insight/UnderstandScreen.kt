package com.nizam.app.feature.insight

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.phone.PhoneActions
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs

@Composable
fun UnderstandScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var tab by remember { mutableStateOf("Patterns") }
    var days by remember { mutableStateOf<List<DayVector>>(emptyList()) }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(reload) { com.nizam.app.ui.design.guard { days = runCatching { Days.build(container, context, 365) }.getOrElse { emptyList() } } }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Understand yourself", "Patterns from your own data — not advice from a stranger")
        ChipRow(listOf("Patterns", "Experiments", "Year", "Focus"), tab, { tab = it })
        Spacer(Modifier.height(12.dp))
        when (tab) {
            "Patterns" -> Patterns(container, days) { reload++ }
            "Experiments" -> ExperimentsTab(days)
            "Year" -> YearInPixels(days)
            else -> FocusTab { reload++ }
        }
        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) { Column(Modifier.padding(16.dp)) { content() } }
}

@Composable
private fun Patterns(container: AppContainer, days: List<DayVector>, onSynced: () -> Unit) {
    val context = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var status by remember { mutableStateOf<String?>(null) }
    val findings = remember(days) { Correlations.findings(days.takeLast(120)) }
    HealthConnectExportCard(container)
    val ask = rememberLauncherForActivityResult(HealthLink.contract) { granted ->
        if (granted.containsAll(HealthLink.permissions)) scope.launch {
            status = runCatching { HealthLink.sync(context) }.getOrElse { "Sync failed: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }; onSynced()
        } else status = "Health Connect access wasn't granted."
    }
    Card {
        Text("❤️  Health Connect", style = MaterialTheme.typography.titleMedium)
        Text("Steps and sleep from your phone or watch feed straight into these patterns.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = {
            if (!HealthLink.available(context)) { status = "Health Connect isn't installed or up to date on this phone."; return@OutlinedButton }
            scope.launch {
                if (HealthLink.granted(context)) {
                    status = runCatching { HealthLink.sync(context) }.getOrElse { "Sync failed: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }; onSynced()
                } else ask.launch(HealthLink.permissions)
            }
        }) { Text("Connect & sync") }
        status?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary) }
    }
    if (findings.isEmpty()) {
        Card {
            Text("No patterns yet", style = MaterialTheme.typography.titleMedium)
            Text("A finding needs at least 10 days where both things were recorded. Keep logging mood, sleep, " +
                "gym and study — patterns appear on their own.", style = MaterialTheme.typography.bodyMedium)
        }
    }
    findings.forEach { f ->
        Card {
            Text(f.sentence, style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            Text("r = ${"%.2f".format(f.r)} · ${f.n} days", style = MonoNumber,
                color = if (f.r > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
    }
    if (findings.isNotEmpty()) Text(
        "These are correlations, not causes. The way to find out whether one actually drives the other is an experiment.",
        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun ExperimentsTab(days: List<DayVector>) {
    val context = LocalContext.current
    val store = remember { ExperimentStore(context) }
    var list by remember { mutableStateOf(store.all()) }
    var title by remember { mutableStateOf("") }
    var metric by remember { mutableStateOf("mood") }
    var length by remember { mutableStateOf("14") }

    Card {
        Text("Start an experiment", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(title, { title = it }, label = { Text("e.g. No phone after 10pm") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        Text("Measure its effect on", style = MaterialTheme.typography.labelMedium)
        ChipRow(METRICS.keys.toList(), metric, { metric = it }, labels = { METRICS[it] ?: it })
        OutlinedTextField(length, { length = it.filter(Char::isDigit) }, label = { Text("Days") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        Button(enabled = title.isNotBlank(), onClick = {
            list = list + Experiment(System.currentTimeMillis(), title.trim(), "", metric,
                LocalDate.now().toString(), (length.toIntOrNull() ?: 14).coerceIn(3, 90))
            store.save(list); title = ""
        }) { Text("Begin") }
        Text("Atlas compares the experiment against the same number of days just before you started.",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    list.sortedByDescending { it.id }.forEach { e ->
        val r = remember(days, e) { Experiments.evaluate(e, days) }
        val done = !LocalDate.now().isBefore(e.end)
        Card {
            Text(e.title, style = MaterialTheme.typography.titleMedium)
            Text("${METRICS[e.metric]} · ${if (done) "finished" else "${java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), e.end)} days left"}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text("Before: ${r.baseline?.let { "%.1f".format(it) } ?: "—"} (${r.baseDays}d)   " +
                "During: ${r.during?.let { "%.1f".format(it) } ?: "—"} (${r.duringDays}d)", style = MonoNumber)
            r.change?.let { c ->
                Text((if (c >= 0) "^ " else "v ") + "%.0f%%".format(abs(c)) +
                    if (r.duringDays < 5) " — too early to trust" else "",
                    color = if (c >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            Text("remove", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(role = Role.Button) { list = list - e; store.save(list) }.padding(top = 6.dp))
        }
    }
}

@Composable
private fun YearInPixels(days: List<DayVector>) {
    var by by remember { mutableStateOf("mood") }
    ChipRow(listOf("mood", "sleep", "steps", "sets", "study", "prayers"), by, { by = it }, labels = { METRICS[it] ?: it })
    val values = days.map { it.values[by] }
    val max = values.filterNotNull().maxOrNull()?.takeIf { it > 0 } ?: 1.0
    val accent = MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceVariant
    Card {
        Text("The last 365 days", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        // 19 columns ≈ weeks across; each square is one day, brighter = higher
        Canvas(Modifier.fillMaxWidth().aspectRatio(19f / 20f)) {
            val cols = 19; val cell = size.width / cols
            values.forEachIndexed { i, v ->
                val c = i % cols; val r = i / cols
                drawRect(if (v == null) empty else accent.copy(alpha = (0.2f + 0.8f * (v / max).toFloat()).coerceIn(0.2f, 1f)),
                    Offset(c * cell + 1.5f, r * cell + 1.5f), Size(cell - 3f, cell - 3f))
            }
        }
        Text("${values.count { it != null }} of 365 days recorded · grey = nothing logged",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FocusTab(onLogged: () -> Unit) {
    val context = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var minutes by remember { mutableStateOf(25) }
    var endAt by remember { mutableStateOf(0L) }
    var left by remember { mutableStateOf(0L) }
    var label by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(endAt) { com.nizam.app.ui.design.guard {
        while (endAt > 0) {
            left = (endAt - System.currentTimeMillis()).coerceAtLeast(0)
            if (left == 0L) {
                FocusStore(context).log(minutes, label.ifBlank { "Focus" })
                note = if (minutes == 5) "Five minutes done. The hard part was starting — keep going?"
                    else "Done — $minutes minutes logged. Take a break."
                endAt = 0; onLogged()
            }
            delay(500)
        }
    } }
    Card {
        if (endAt == 0L) {
            Text("Focus session", style = MaterialTheme.typography.titleMedium)
            ChipRow(listOf("5", "15", "25", "45", "60", "90"), "$minutes", { minutes = it.toInt() },
                labels = { if (it == "5") "Just 5 min" else "$it min" })
            if (minutes == 5) Text("For the thing you're avoiding. Five minutes only — then decide.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(label, { label = it }, label = { Text("What are you working on?") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                endAt = System.currentTimeMillis() + minutes * 60_000L; note = null
                com.nizam.app.feature.growth.FocusFlag.set(context, endAt)
                if (com.nizam.app.feature.shield.Shield.duringFocus(context)) com.nizam.app.feature.shield.Shield.activate(context, minutes)
                // a system timer too, so you're told when it ends even if you leave Atlas
                PhoneActions.setTimer(context, minutes * 60, "Atlas focus: ${label.ifBlank { "session" }}")
            }) { Text("Start") }
        } else {
            val m = left / 60_000; val s = (left / 1000) % 60
            Text("%02d:%02d".format(m, s), style = MaterialTheme.typography.displaySmall)
            Text(label.ifBlank { "Focusing" }, style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = {
                val done = (minutes - (left / 60_000).toInt()).coerceAtLeast(0)
                if (done > 0) FocusStore(context).log(done, label.ifBlank { "Focus" })
                note = "Stopped early — $done minutes logged."; endAt = 0; onLogged()
                com.nizam.app.feature.shield.Shield.stop(context)
                com.nizam.app.feature.growth.FocusFlag.set(context, 0)
            }) { Text("Stop") }
        }
        note?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium) }
        if (endAt == 0L && note?.startsWith("Five minutes done") == true) {
            Button(onClick = {
                minutes = 25; endAt = System.currentTimeMillis() + 25 * 60_000L; note = null
                com.nizam.app.feature.growth.FocusFlag.set(context, endAt)
            }) { Text("Keep going — 25 more") }
        }
    }
}
