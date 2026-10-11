package com.nizam.app.feature.ask

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.agent.CuratorScreen
import com.nizam.app.ui.nav.Routes
import com.nizam.app.ui.screens.hub.AtlasHub
import com.nizam.app.ui.screens.hubs.HubRegistry
import com.nizam.app.ui.screens.search.SearchScreen
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * The Ask tab: one place for finding things, talking to Atlas and getting things done.
 * "Do" understands a few plain commands and always shows what it is about to do before doing it.
 */
sealed class Intent {
    data class Expense(val amount: Double, val category: String, val note: String) : Intent()
    data class Income(val amount: Double, val note: String) : Intent()
    data class AddTask(val title: String, val due: LocalDate?) : Intent()
    data class OpenTool(val route: String, val title: String) : Intent()
}

object Commands {
    private val EXPENSE = Regex("^(?:spent|spend|paid|pay|kharcha)\\s+(?:rs\\.?\\s*)?([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:rs|rupees)?\\s*(?:on|for|to)?\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val INCOME = Regex("^(?:received|earned|got|income)\\s+(?:rs\\.?\\s*)?([0-9][0-9,]*(?:\\.[0-9]+)?)\\s*(?:rs|rupees)?\\s*(?:from|for)?\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val TASK = Regex("^(?:add task|new task|task|remind me to|todo)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val OPEN = Regex("^(?:open|go to|show)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val CATEGORIES = mapOf("food" to "Food", "lunch" to "Food", "dinner" to "Food", "chai" to "Food", "petrol" to "Transport", "fuel" to "Transport", "uber" to "Transport",
        "careem" to "Transport", "bill" to "Bills", "electricity" to "Bills", "rent" to "Rent", "grocery" to "Groceries", "groceries" to "Groceries", "medicine" to "Health")

    private val EXPENSE_RU = Regex("^(?:kharcha|kharch|kharcha kiya|kharch kiya)\\s+(?:rs\\.?\\s*)?([0-9][0-9,]*)\\s*(?:rupay|rupee|rs)?\\s*(?:ka|ke|ki|pe|par)?\\s*(.*)$", RegexOption.IGNORE_CASE)
    private val EXPENSE_RU2 = Regex("^([0-9][0-9,]*)\\s*(?:rupay|rupee|rs)\\s+(.+?)\\s+(?:pe|par|ka|ki)\\s+(?:kharch|kharcha)(?:\\s+kiye|\\s+kiya)?$", RegexOption.IGNORE_CASE)
    private val TASK_RU = Regex("^(?:task add karo|task add kar|task banao|yaad dilao|yaad dilana|mujhe yaad dilana)\\s+(.+)$", RegexOption.IGNORE_CASE)
    private val OPEN_RU = Regex("^(?:kholo|khol do|dikhao)\\s+(.+)$|^(.+?)\\s+(?:kholo|khol do|dikhao)$", RegexOption.IGNORE_CASE)

    fun parse(text: String): Intent? {
        val t = text.trim()
        EXPENSE_RU.find(t)?.let { m -> val amt = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null; val note = m.groupValues[2].trim()
            return Intent.Expense(amt, CATEGORIES.entries.firstOrNull { note.contains(it.key, true) }?.value ?: "Other", note) }
        EXPENSE_RU2.find(t)?.let { m -> val amt = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null; val note = m.groupValues[2].trim()
            return Intent.Expense(amt, CATEGORIES.entries.firstOrNull { note.contains(it.key, true) }?.value ?: "Other", note) }
        TASK_RU.find(t)?.let { m -> var title = m.groupValues[1].trim(); var due: LocalDate? = null
            if (title.endsWith(" kal", true)) { due = LocalDate.now().plusDays(1); title = title.dropLast(4).trim() } else if (title.endsWith(" aaj", true)) { due = LocalDate.now(); title = title.dropLast(4).trim() }
            return Intent.AddTask(title, due) }
        OPEN_RU.find(t)?.let { m -> val q = (m.groupValues[1].ifBlank { m.groupValues[2] }).trim(); HubRegistry.search(q).firstOrNull()?.let { return Intent.OpenTool(it.route, it.title) } }
        EXPENSE.find(t)?.let { m -> val amt = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null; val note = m.groupValues[2].trim()
            val cat = CATEGORIES.entries.firstOrNull { note.contains(it.key, true) }?.value ?: "Other"; return Intent.Expense(amt, cat, note) }
        INCOME.find(t)?.let { m -> val amt = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null; return Intent.Income(amt, m.groupValues[2].trim()) }
        TASK.find(t)?.let { m -> var title = m.groupValues[1].trim(); var due: LocalDate? = null
            if (title.endsWith(" tomorrow", true)) { due = LocalDate.now().plusDays(1); title = title.dropLast(9).trim() } else if (title.endsWith(" today", true)) { due = LocalDate.now(); title = title.dropLast(6).trim() }
            return Intent.AddTask(title, due) }
        OPEN.find(t)?.let { m -> HubRegistry.search(m.groupValues[1]).firstOrNull()?.let { return Intent.OpenTool(it.route, it.title) } }
        return null
    }

    fun describe(i: Intent) = when (i) {
        is Intent.Expense -> "Log an expense of Rs ${"%,.0f".format(i.amount)} in ${i.category}${if (i.note.isNotBlank()) " (${i.note})" else ""}"
        is Intent.Income -> "Log income of Rs ${"%,.0f".format(i.amount)}${if (i.note.isNotBlank()) " from ${i.note}" else ""}"
        is Intent.AddTask -> "Add the task \"${i.title}\"${i.due?.let { " for ${if (it == LocalDate.now()) "today" else "tomorrow"}" }.orEmpty()}"
        is Intent.OpenTool -> "Open ${i.title}"
    }

    suspend fun run(container: AppContainer, i: Intent, open: (String) -> Unit) {
        val snap = com.nizam.app.feature.agentic.ActionUndo.snap(container)
        when (i) {
            is Intent.Expense -> container.financeRepository.add(i.amount, "EXPENSE", i.category, i.note)
            is Intent.Income -> container.financeRepository.add(i.amount, "INCOME", "Income", i.note)
            is Intent.AddTask -> container.taskRepository.add(i.title, i.due?.atTime(18, 0)?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli(), 1)
            is Intent.OpenTool -> open(i.route)
        }
        if (i !is Intent.OpenTool) com.nizam.app.feature.agentic.ActionUndo.record(container, snap, describe(i))
    }
}

@Composable
fun AskScreen(container: AppContainer, onOpen: (String) -> Unit) {
    var tab by rememberSaveable { mutableStateOf("Find") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Find", "Talk", "Do", "Powers").forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t) }) }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                "Find" -> SearchScreen(container, onOpen)
                "Talk" -> CuratorScreen(container)
                "Do" -> DoPane(container, onOpen)
                else -> AtlasHub(onOpen)
            }
        }
    }
}

@Composable
private fun DoPane(container: AppContainer, onOpen: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf<Intent?>(null) }
    var status by remember { mutableStateOf("") }
    val recent = remember { mutableStateOf(listOf<String>()) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("Tell Atlas what to do", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 8.dp))
            Text("Try: spent 800 on lunch · add task call Ali tomorrow · received 50000 salary · open pantry", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val speak = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { r ->
                r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { text = it; pending = Commands.parse(it) } }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it; status = ""; pending = Commands.parse(it) }, label = { Text("What should I do?") }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    com.nizam.app.feature.platform2.PermLog.note(ctx, "Microphone", "Ask voice command")
                    runCatching { speak.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .also { i -> com.nizam.app.feature.future.Lang.speechTag(ctx)?.let { i.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, it) } }) } }) { Text("🎙") }
            }
        }
        item {
            val p = pending
            if (p != null) Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("I'll do this:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(Commands.describe(p), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.nizam.app.feature.agentic.HoldToConfirm(onConfirm = { scope.launch { status = runCatching { Commands.run(container, p, onOpen); recent.value = listOf(Commands.describe(p)) + recent.value.take(5); "Done." }.getOrElse { "Couldn't: ${it.message}" }
                        text = ""; pending = null } })
                    TextButton(onClick = { pending = null; text = "" }) { Text("Cancel") }
                }
            } else if (text.length > 3) {
                Text("I don't know that command. Ask me in the Talk tab instead.", style = MaterialTheme.typography.bodySmall)
                val qctx = androidx.compose.ui.platform.LocalContext.current
                Row { TextButton(onClick = { onOpen(Routes.ASSISTANT) }) { Text("Open Talk") }
                    TextButton(onClick = { com.nizam.app.feature.platform2.AiQueue.add(qctx, text); status = "Queued. You'll get the answer when there's a connection."; text = "" }) { Text("Answer it later") } }
                val waiting = com.nizam.app.feature.platform2.AiQueue.waiting(qctx)
                if (waiting > 0) TextButton(onClick = { onOpen("nx/offlinequeue") }) { Text("$waiting waiting") }
            }
            if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodyMedium)
        }
        if (recent.value.isNotEmpty()) {
            item { Text("Done just now", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            items(recent.value) { Text("✓ $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}
