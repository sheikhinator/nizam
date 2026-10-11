package com.nizam.app.feature.brain

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import com.nizam.app.ui.design.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.fmtAmount
import com.nizam.app.feature.insight.FocusStore
import com.nizam.app.feature.insight.HealthCache
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) { content() } }
}

// ── Second brain + decisions ─────────────────────────────────────────────────
@Composable
fun BrainScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by remember { mutableStateOf("Ask") }
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var answer by remember { mutableStateOf<String?>(null) }
    var searchError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var sources by remember { mutableStateOf<List<SourceDocument>>(emptyList()) }
    var sourceLoadError by remember { mutableStateOf<String?>(null) }
    var sourceNotice by remember { mutableStateOf<String?>(null) }
    var sourceToRemove by remember { mutableStateOf<SourceDocument?>(null) }

    val sourcePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            sourceNotice = null
            try {
                val imported = withContext(Dispatchers.IO) { SourceDocuments.importFile(context, uri) }
                val shelf = withContext(Dispatchers.IO) { SourceDocuments.load(context) }
                sources = shelf.documents
                sourceLoadError = shelf.error
                sourceNotice = "Saved ${imported.title} in encrypted local storage."
            } catch (e: Exception) {
                sourceNotice = com.nizam.app.ui.components.Feedback.friendly(e)
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(context) {
        val shelf = withContext(Dispatchers.IO) { SourceDocuments.load(context) }
        sources = shelf.documents
        sourceLoadError = shelf.error
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Second brain", "Everything you've saved, searchable — and a record of your judgment")
        ChipRow(listOf("Ask", "Sources", "Decisions"), tab, { tab = it })
        Spacer(Modifier.height(10.dp))
        if (tab == "Ask") {
            OutlinedTextField(query, { query = it }, label = { Text("What did I conclude about…") },
                modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Row {
                OutlinedButton(enabled = query.isNotBlank() && !busy, onClick = {
                    val searchQuery = query
                    scope.launch {
                        busy = true
                        answer = null
                        searchError = null
                        try {
                            hits = Brain.rankSearch(searchQuery, Brain.corpus(container, context))
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            hits = emptyList()
                            searchError = "Couldn't search: ${com.nizam.app.ui.components.Feedback.friendly(failure)}"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Search") }
                Spacer(Modifier.width(8.dp))
                Button(enabled = query.isNotBlank() && !busy, onClick = {
                    val searchQuery = query
                    scope.launch {
                        busy = true
                        answer = null
                        searchError = null
                        try {
                            val found = Brain.rankSearch(searchQuery, Brain.corpus(container, context))
                            hits = found
                            answer = try {
                                Brain.ask(container, searchQuery, found)
                            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                "Couldn't answer: ${com.nizam.app.ui.components.Feedback.friendly(failure)}"
                            }
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            hits = emptyList()
                            searchError = "Couldn't search: ${com.nizam.app.ui.components.Feedback.friendly(failure)}"
                        } finally {
                            busy = false
                        }
                    }
                }) { Text(if (busy) "Thinking…" else "Ask") }
            }
            Text("Search stays on your phone. Ask sends only the best few matches to your AI.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            searchError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            answer?.let { Card { Text(it, style = MaterialTheme.typography.bodyLarge) } }
            hits.forEachIndexed { i, h ->
                Card {
                    Text("[${i + 1}] ${h.doc.source.uppercase()}${if (h.doc.date.isNotBlank()) " · ${h.doc.date}" else ""}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(h.doc.title, style = MaterialTheme.typography.titleMedium)
                    Text(h.doc.text.take(240), style = MaterialTheme.typography.bodyMedium)
                }
            }
        } else if (tab == "Sources") {
            Card {
                Text("Your source shelf", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Import text-based files up to 20 MB and 20,000 extracted characters (up to 50 sources). Atlas stores their text encrypted on this device. PDFs can still be opened in Library or attached to a chat, but their page text is not indexed here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Button(enabled = !busy, onClick = {
                    sourcePicker.launch(arrayOf(
                        "text/*",
                        "application/json",
                        "application/xml",
                        "application/x-yaml",
                        "application/javascript"
                    ))
                }) {
                    Text(if (busy) "Importing…" else "＋ Import source")
                }
                sourceLoadError?.let {
                    Text("Saved sources could not be opened: $it", color = MaterialTheme.colorScheme.error)
                }
                sourceNotice?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (sources.isEmpty()) {
                Card { Text("No saved sources yet. Imported text appears in local search and can be included in an answer when you ask.") }
            }
            sources.forEach { source ->
                Card {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(source.title, style = MaterialTheme.typography.titleSmall)
                            Text("Added ${source.date}", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { sourceToRemove = source }) { Text("Remove") }
                    }
                    Text(source.text.take(240), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else DecisionsTab()
        Spacer(Modifier.height(60.dp))
    }

    sourceToRemove?.let { source ->
        AlertDialog(
            onDismissRequest = { sourceToRemove = null },
            title = { Text("Remove “${source.title}”?") },
            text = { Text("This removes Atlas's encrypted copy only. The original file will not be changed.") },
            confirmButton = {
                TextButton(onClick = {
                    sourceToRemove = null
                    scope.launch {
                        val removed = try {
                            withContext(Dispatchers.IO) { SourceDocuments.remove(context, source.id) }
                        } catch (e: Exception) {
                            sourceNotice = com.nizam.app.ui.components.Feedback.friendly(e)
                            return@launch
                        }
                        val shelf = withContext(Dispatchers.IO) { SourceDocuments.load(context) }
                        sources = shelf.documents
                        sourceLoadError = shelf.error
                        sourceNotice = if (removed) "Source removed." else "This source was already removed."
                    }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { sourceToRemove = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun DecisionsTab() {
    val context = LocalContext.current
    val store = remember { DecisionStore(context) }
    var list by remember { mutableStateOf(store.all()) }
    var decision by remember { mutableStateOf("") }
    var expect by remember { mutableStateOf("") }
    var confidence by remember { mutableStateOf(70f) }
    var months by remember { mutableStateOf("3") }

    Card {
        Text("Record a decision", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(decision, { decision = it }, label = { Text("What did you decide?") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(expect, { expect = it }, label = { Text("What do you expect will happen?") }, modifier = Modifier.fillMaxWidth())
        Text("How sure are you? ${confidence.toInt()}%", style = MaterialTheme.typography.bodyMedium)
        Slider(confidence, { confidence = it }, valueRange = 50f..100f)
        ChipRow(listOf("1", "3", "6", "12"), months, { months = it }, labels = { "Review in ${it}mo" })
        Spacer(Modifier.height(6.dp))
        Button(enabled = decision.isNotBlank(), onClick = {
            list = list + Decision(System.currentTimeMillis(), LocalDate.now().toString(), decision.trim(), expect.trim(),
                confidence.toInt(), LocalDate.now().plusMonths(months.toLong()).toString())
            store.save(list); decision = ""; expect = ""
        }) { Text("Save") }
    }

    val bands = Calibration.bands(list)
    if (bands.isNotEmpty()) Card {
        Text("Your calibration", style = MaterialTheme.typography.titleMedium)
        bands.forEach { b ->
            Text("When ${b.label} sure > right ${(b.right * 100).toInt()}% of the time (${b.n})", style = MonoNumber)
        }
        Text("Well-calibrated means those two numbers roughly match.", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    list.sortedWith(compareBy<Decision> { it.score.isNotBlank() }.thenByDescending { it.id }).forEach { d ->
        val due = d.score.isBlank() && d.reviewOn <= LocalDate.now().toString()
        var outcome by remember(d.id) { mutableStateOf("") }
        Card {
            Text(d.decision, style = MaterialTheme.typography.titleMedium)
            Text("${d.date} · ${d.confidence}% sure · expected: ${d.expectation.ifBlank { "—" }}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when {
                d.score.isNotBlank() -> Text("${d.score.lowercase().replaceFirstChar { it.uppercase() }} — ${d.outcome}",
                    color = MaterialTheme.colorScheme.primary)
                due -> {
                    Text("Time to review — how did it turn out?", color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(outcome, { outcome = it }, label = { Text("What actually happened") },
                        modifier = Modifier.fillMaxWidth())
                    Row {
                        listOf("RIGHT", "PARTLY", "WRONG").forEach { s ->
                            TextButton(onClick = {
                                list = list.map { if (it.id == d.id) it.copy(outcome = outcome, score = s) else it }
                                store.save(list)
                            }) { Text(s.lowercase().replaceFirstChar { it.uppercase() }) }
                        }
                    }
                }
                else -> Text("Review on ${d.reviewOn}", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

// ── Day view: any past date, and the timeline of recent days ─────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayScreen(container: AppContainer) {
    val context = LocalContext.current
    var date by remember { mutableStateOf(LocalDate.now()) }
    var picking by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var diary by remember { mutableStateOf<List<String>>(emptyList()) }

    LaunchedEffect(date) {
        val k = date.toString(); val zone = ZoneId.systemDefault()
        val out = mutableListOf<Pair<String, String>>()
        container.prayerRepository.day(k).first()?.let { p ->
            out += "🕌 Prayers" to "${listOf(p.fajr, p.dhuhr, p.asr, p.maghrib, p.isha).count { it != "NOT_PRAYED" }}/5"
        }
        container.dietRepository.day(k).first().takeIf { it.isNotEmpty() }?.let {
            out += "🥗 Food" to "${it.sumOf { m -> m.kcal }.toInt()} kcal · ${it.size} items"
        }
        container.gymRepository.sets().first().filter { it.localDate == k }.takeIf { it.isNotEmpty() }?.let {
            out += "🏋️ Gym" to "${it.size} sets · ${it.sumOf { s -> s.weight * s.reps }.toInt()} kg volume"
        }
        container.financeRepository.since(k).first().filter { it.localDate == k }.takeIf { it.isNotEmpty() }?.let {
            out += "💰 Spent" to fmtAmount(it.filter { t -> t.kind == "EXPENSE" }.sumOf { t -> t.amount })
        }
        container.learningRepository.sessions().first().filter { it.localDate == k }.sumOf { it.minutes }
            .takeIf { it > 0 }?.let { out += "🧠 Study" to "$it min" }
        FocusStore(context).minutesByDay()[k]?.let { out += "⏱ Focus" to "$it min" }
        HealthCache(context).load()[k]?.let { (sleep, steps) ->
            sleep?.let { out += "😴 Sleep" to "%.1f h".format(it) }
            steps?.let { out += "👟 Steps" to "${it.toInt()}" }
        }
        container.taskRepository.all().first().filter {
            it.completedAt != null && Instant.ofEpochMilli(it.completedAt).atZone(zone).toLocalDate() == date
        }.takeIf { it.isNotEmpty() }?.let { out += "✅ Tasks done" to it.joinToString(", ") { t -> t.title }.take(80) }
        container.habitRepository.entriesFor(k).first().takeIf { it.isNotEmpty() }?.let { out += "🔁 Habits" to "${it.size} done" }
        DecisionStore(context).all().filter { it.date == k }.forEach { out += "⚖️ Decided" to it.decision }
        diary = container.diaryRepository.entries().first().filter { it.localDate == k }.map { "Mood ${it.mood}/5 — ${it.body}" }
        lines = out
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Timeline", "Step through any day of your life in Atlas")
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { date = date.minusDays(1) }) { Icon(Icons.Filled.KeyboardArrowLeft, "Previous day") }
            Column(Modifier.weight(1f).clickable(role = Role.Button) { picking = true }, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(date.format(DateTimeFormatter.ofPattern("EEEE")), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(date.format(DateTimeFormatter.ofPattern("d MMMM yyyy")), style = MaterialTheme.typography.titleLarge)
                Text("tap to pick a date", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            IconButton(enabled = date < LocalDate.now(), onClick = { date = date.plusDays(1) }) {
                Icon(Icons.Filled.KeyboardArrowRight, "Next day")
            }
        }
        if (date != LocalDate.now()) TextButton(onClick = { date = LocalDate.now() }) { Text("Back to today") }

        if (lines.isEmpty() && diary.isEmpty()) Card { Text("Nothing was logged on this day.") }
        if (lines.isNotEmpty()) Card {
            lines.forEach { (k, v) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                    Text(k, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(v, style = MonoNumber)
                }
            }
        }
        val photo = com.nizam.app.feature.memory.DailyPhoto.file(context, date.toString())
        if (photo.exists()) {
            val bmp = remember(date) { runCatching { android.graphics.BitmapFactory.decodeFile(photo.path,
                android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 })?.asImageBitmap() }.getOrNull() }
            bmp?.let { Card { androidx.compose.foundation.Image(it, "Photo of the day", modifier = Modifier.fillMaxWidth(),
                contentScale = androidx.compose.ui.layout.ContentScale.FillWidth) } }
        }
        diary.forEach { Card { Text("✍️ Diary", style = MaterialTheme.typography.labelMedium); Text(it) } }
        Spacer(Modifier.height(60.dp))
    }

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let {
                        val d = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
                        date = if (d.isAfter(LocalDate.now())) LocalDate.now() else d
                    }
                    picking = false
                }) { Text("Show") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } }
        ) { DatePicker(state) }
    }
}
