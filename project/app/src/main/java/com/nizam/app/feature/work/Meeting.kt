package com.nizam.app.feature.work

import android.app.Activity
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.notes.Note
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

private data class MeetingDecisionDraft(
    val id: String = UUID.randomUUID().toString(),
    val what: String,
    val evidence: String,
    val confirmed: Boolean = false
)

private data class MeetingActionDraft(
    val id: String = UUID.randomUUID().toString(),
    val what: String,
    val evidence: String,
    val speakerDraft: String = "",
    val ownerDraft: String = "",
    val deadlineDraft: String = "",
    val unverifiedOwnerSuggestion: String = "",
    val unverifiedDeadlineSuggestion: String = "",
    val confirmed: Boolean = false,
    val addToTasks: Boolean = false,
    val taskAdded: Boolean = false
)

/** Meeting suggestions remain drafts until exact transcript support and a user confirmation are present. */
@Composable
fun MeetingScreen(container: AppContainer) {
    val context = LocalContext.current
    val store = remember(context) { PeopleCareerStore(context) }
    val scope = rememberCoroutineScope()

    var transcript by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var meetingDate by remember { mutableStateOf(LocalDate.now().toString()) }
    var summary by remember { mutableStateOf("") }
    var riskNotes by remember { mutableStateOf("") }
    var decisions by remember { mutableStateOf(emptyList<MeetingDecisionDraft>()) }
    var actions by remember { mutableStateOf(emptyList<MeetingActionDraft>()) }
    var history by remember { mutableStateOf(emptyList<JSONObject>()) }
    var editingRecordId by remember { mutableStateOf("") }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }
    var savedNoteId by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var taskBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(store) {
        runCatching { withContext(Dispatchers.IO) { store.all(PeopleCareerStore.MEETINGS) } }
            .onSuccess { history = it }
            .onFailure { error = it.message ?: "Meeting records could not be read." }
    }

    val mic = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let {
                transcript = listOf(transcript, it).filter(String::isNotBlank).joinToString("\n")
            }
        }
    }

    fun updateAction(index: Int, transform: (MeetingActionDraft) -> MeetingActionDraft) {
        actions = actions.mapIndexed { i, action ->
            if (i == index) transform(action).copy(confirmed = false, addToTasks = false) else action
        }
    }

    fun updateDecision(index: Int, transform: (MeetingDecisionDraft) -> MeetingDecisionDraft) {
        decisions = decisions.mapIndexed { i, decision ->
            if (i == index) transform(decision).copy(confirmed = false) else decision
        }
    }

    fun loadMeeting(record: JSONObject) {
        editingRecordId = record.optString("id")
        savedNoteId = record.optLong("noteId")
        title = record.optString("title")
        meetingDate = record.optString("date", LocalDate.now().toString())
        transcript = record.optString("transcript")
        summary = record.optString("summary")
        riskNotes = record.optString("risks")
        decisions = readDecisionDrafts(record.optJSONArray("decisions"))
        actions = readActionDrafts(record.optJSONArray("actions"), transcript)
        error = null
    }

    fun newMeeting() {
        editingRecordId = ""
        savedNoteId = 0L
        title = ""
        meetingDate = LocalDate.now().toString()
        transcript = ""
        summary = ""
        riskNotes = ""
        decisions = emptyList()
        actions = emptyList()
        error = null
    }

    fun reloadHistory() {
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { store.all(PeopleCareerStore.MEETINGS) } }
                .onSuccess { history = it }
                .onFailure { error = it.message ?: "Meeting history could not be refreshed." }
        }
    }

    fun saveMeeting() {
        scope.launch {
            busy = true
            error = null
            try {
                val noteTitle = "Meeting: ${title.ifBlank { meetingDate }}"
                val validActions = actions.map { action ->
                    val supported = PeopleCareerRules.attributionSupported(
                        transcript, action.evidence, action.speakerDraft, action.ownerDraft, action.deadlineDraft
                    )
                    action.copy(confirmed = action.confirmed && supported, addToTasks = action.addToTasks && supported && action.confirmed)
                }
                val validDecisions = decisions.map { decision ->
                    decision.copy(confirmed = decision.confirmed && PeopleCareerRules.evidenceExists(transcript, decision.evidence))
                }
                val noteBody = renderMeetingNote(
                    meetingDate, summary, riskNotes, validDecisions, validActions, transcript
                )
                val existingNote = if (savedNoteId > 0) {
                    container.noteRepository.everything().firstOrNull { it.id == savedNoteId }
                } else null
                val noteId = if (existingNote != null) {
                    container.noteRepository.save(existingNote.copy(title = noteTitle, body = noteBody))
                    existingNote.id
                } else {
                    container.noteRepository.createFull(noteTitle, noteBody)
                }
                savedNoteId = noteId

                val draft = meetingRecordJson(
                    id = editingRecordId,
                    noteId = noteId,
                    title = title.trim(),
                    date = meetingDate.trim(),
                    transcript = transcript,
                    summary = summary,
                    risks = riskNotes,
                    decisions = validDecisions,
                    actions = validActions
                )
                editingRecordId = withContext(Dispatchers.IO) {
                    store.upsert(PeopleCareerStore.MEETINGS, draft)
                }
                actions = validActions
                decisions = validDecisions
                reloadHistory()
                Feedback.show("Meeting saved to Notes with its evidence ledger.")
            } catch (failure: Exception) {
                error = failure.message ?: "The meeting could not be saved."
                Feedback.failed("Meeting notes", failure)
            } finally {
                busy = false
            }
        }
    }

    fun createConfirmedTasks() {
        scope.launch {
            taskBusy = true
            error = null
            var added = 0
            try {
                actions.forEachIndexed { index, action ->
                    val supported = PeopleCareerRules.attributionSupported(
                        transcript, action.evidence, action.speakerDraft, action.ownerDraft, action.deadlineDraft
                    )
                    if (action.confirmed && action.addToTasks && !action.taskAdded && supported) {
                        container.taskRepository.add(
                            "📋 ${action.what.trim()}",
                            taskDueEpoch(action.deadlineDraft),
                            1
                        )
                        actions = actions.mapIndexed { i, current ->
                            if (i == index) current.copy(taskAdded = true) else current
                        }
                        val updated = meetingRecordJson(
                            id = editingRecordId,
                            noteId = savedNoteId,
                            title = title.trim(),
                            date = meetingDate.trim(),
                            transcript = transcript,
                            summary = summary,
                            risks = riskNotes,
                            decisions = decisions,
                            actions = actions
                        )
                        editingRecordId = withContext(Dispatchers.IO) {
                            store.upsert(PeopleCareerStore.MEETINGS, updated)
                        }
                        added++
                    }
                }
                reloadHistory()
                Feedback.show(if (added == 1) "Added one confirmed action to Tasks." else "Added $added confirmed actions to Tasks.")
            } catch (failure: Exception) {
                error = failure.message ?: "A task could not be created."
                Feedback.failed("Meeting actions", failure)
            } finally {
                taskBusy = false
            }
        }
    }

    val pendingTasks = actions.any { it.confirmed && it.addToTasks && !it.taskAdded }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Meeting notes", "Capture, review, and confirm evidence")
        OutlinedTextField(
            title, { title = it }, singleLine = true,
            label = { Text("What meeting?") }, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            meetingDate, { meetingDate = it }, singleLine = true,
            label = { Text("Meeting date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth()
        )
        Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { mic.launch(com.nizam.app.feature.voice.speechIntent()) }) { Text("🎙 Capture") }
                OutlinedButton(onClick = { transcript = "" }) { Text("Clear transcript") }
            }
            if (editingRecordId.isNotBlank()) OutlinedButton(onClick = ::newMeeting) { Text("New meeting") }
        }
        OutlinedTextField(
            transcript, { transcript = it },
            label = { Text("Transcript — speak in chunks or paste; speaker labels such as “Alex:” are retained") },
            modifier = Modifier.fillMaxWidth().height(200.dp)
        )
        Button(enabled = transcript.length > 40 && !busy, onClick = {
            scope.launch {
                busy = true
                error = null
                try {
                    val prompt = """
                        Review this captured meeting. Treat it as the only source of truth.
                        Return ONLY JSON with this shape:
                        {"summary":"brief editable draft","decisions":[{"what":"decision text","evidence":"an exact contiguous quote copied from the transcript"}],
                        "actions":[{"what":"action text","evidence":"an exact contiguous quote copied from the transcript",
                        "owner":"only a person explicitly supported by that quote, otherwise empty",
                        "deadline":"only a deadline copied verbatim from that quote, otherwise empty"}],
                        "risks":["brief risk"]}
                        Never invent speakers, owners, deadlines, commitments, or transcript wording.
                        If no exact quote supports an item, omit it. Keep evidence quotes short and exact.
                        Meeting: ${title.ifBlank { "untitled" }}
                        Date: $meetingDate
                        Transcript:
                        ${transcript.take(14000)}
                    """.trimIndent()
                    val raw = Ai.generate(container.settingsRepository, prompt, maxOutputTokens = 2400)
                    val result = JSONObject(Ai.cleanJson(raw))
                    summary = result.optString("summary")
                    decisions = parseDecisionDrafts(result.optJSONArray("decisions"))
                    actions = parseActionDrafts(result.optJSONArray("actions"), transcript)
                    val riskArray = result.optJSONArray("risks")
                    riskNotes = if (riskArray == null) "" else
                        (0 until riskArray.length()).joinToString("\n") { riskArray.optString(it) }.trim()
                    Feedback.show("Review the exact quotes and confirm each item before it is recorded.")
                } catch (failure: Exception) {
                    error = failure.message ?: "Meeting analysis failed."
                    Feedback.failed("Meeting analysis", failure)
                } finally {
                    busy = false
                }
            }
        }) { Text(if (busy) "Analyzing…" else "Extract reviewable decisions & actions") }

        if (summary.isNotBlank() || decisions.isNotEmpty() || actions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Editable meeting summary", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(summary, { summary = it }, label = { Text("AI draft — edit before saving") }, modifier = Modifier.fillMaxWidth().height(100.dp))
            OutlinedTextField(riskNotes, { riskNotes = it }, label = { Text("Risks / open questions (editable)") }, modifier = Modifier.fillMaxWidth().height(80.dp))
        }

        if (decisions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Decision evidence", style = MaterialTheme.typography.titleMedium)
            decisions.forEachIndexed { index, decision ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        OutlinedTextField(
                            decision.what,
                            { value -> updateDecision(index) { it.copy(what = value) } },
                            label = { Text("Decision") },
                            modifier = Modifier.fillMaxWidth().height(76.dp)
                        )
                        OutlinedTextField(
                            decision.evidence,
                            { value -> updateDecision(index) { it.copy(evidence = value) } },
                            label = { Text("Exact transcript quote") },
                            modifier = Modifier.fillMaxWidth().height(76.dp)
                        )
                        val evidenceValid = PeopleCareerRules.evidenceExists(transcript, decision.evidence)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = decision.confirmed,
                                enabled = evidenceValid || decision.confirmed,
                                onCheckedChange = { checked ->
                                    decisions = decisions.mapIndexed { i, item ->
                                        if (i == index) item.copy(confirmed = checked && evidenceValid) else item
                                    }
                                }
                            )
                            Text(if (evidenceValid) "I confirm this decision and quote" else "Quote not found in this transcript")
                        }
                    }
                }
            }
        }

        if (actions.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("Action records — exact evidence required", style = MaterialTheme.typography.titleMedium)
            Text(
                "Speaker, owner, and deadline remain blank in the saved record unless the exact quote supports them and you confirm the action.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            actions.forEachIndexed { index, action ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        OutlinedTextField(
                            action.what,
                            { value -> updateAction(index) { it.copy(what = value) } },
                            label = { Text("Action") },
                            modifier = Modifier.fillMaxWidth().height(76.dp)
                        )
                        OutlinedTextField(
                            action.evidence,
                            { value -> updateAction(index) { it.copy(evidence = value) } },
                            label = { Text("Exact transcript quote") },
                            modifier = Modifier.fillMaxWidth().height(82.dp)
                        )
                        OutlinedTextField(
                            action.speakerDraft,
                            { value -> updateAction(index) { it.copy(speakerDraft = value) } },
                            singleLine = true,
                            label = { Text("Speaker (must match an explicit transcript label)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            action.ownerDraft,
                            { value -> updateAction(index) { it.copy(ownerDraft = value) } },
                            singleLine = true,
                            label = { Text("Owner (must be supported by the quote)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            action.deadlineDraft,
                            { value -> updateAction(index) { it.copy(deadlineDraft = value) } },
                            singleLine = true,
                            label = { Text("Deadline (must appear in the quote)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (action.unverifiedOwnerSuggestion.isNotBlank() || action.unverifiedDeadlineSuggestion.isNotBlank()) {
                            Text(
                                "Unverified AI suggestion (not saved as attribution): " +
                                    listOf(action.unverifiedOwnerSuggestion, action.unverifiedDeadlineSuggestion)
                                        .filter(String::isNotBlank).joinToString(" · "),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        val evidenceValid = PeopleCareerRules.evidenceExists(transcript, action.evidence)
                        val attributionValid = PeopleCareerRules.attributionSupported(
                            transcript, action.evidence, action.speakerDraft, action.ownerDraft, action.deadlineDraft
                        )
                        if (!evidenceValid) {
                            Text("No exact quote match. Attribution and confirmation are disabled.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        } else if (!attributionValid) {
                            Text("One or more attribution fields are not supported by this quote.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = action.confirmed,
                                enabled = attributionValid || action.confirmed,
                                onCheckedChange = { checked ->
                                    actions = actions.mapIndexed { i, item ->
                                        if (i == index) item.copy(confirmed = checked && attributionValid, addToTasks = false) else item
                                    }
                                }
                            )
                            Text(if (attributionValid) "I reviewed and confirm this action" else "Cannot confirm without supported evidence")
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = action.addToTasks,
                                enabled = action.confirmed && attributionValid && !action.taskAdded,
                                onCheckedChange = { checked ->
                                    actions = actions.mapIndexed { i, item ->
                                        if (i == index) item.copy(addToTasks = checked && item.confirmed && attributionValid) else item
                                    }
                                }
                            )
                            Text(
                                when {
                                    action.taskAdded -> "Task already created"
                                    else -> "Select for Tasks (created only after saving and pressing the button below)"
                                }
                            )
                        }
                        if (action.deadlineDraft.isNotBlank() && taskDueEpoch(action.deadlineDraft) == null) {
                            Text(
                                "This deadline stays in the ledger; Tasks accepts a due date only when the quote contains YYYY-MM-DD.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            Button(
                enabled = transcript.isNotBlank() && validIsoDate(meetingDate) && !busy,
                onClick = ::saveMeeting
            ) { Text(if (busy) "Saving…" else if (editingRecordId.isBlank()) "Save meeting and evidence ledger" else "Save corrections") }
            Button(
                enabled = pendingTasks && editingRecordId.isNotBlank() && !busy && !taskBusy,
                onClick = ::createConfirmedTasks
            ) { Text(if (taskBusy) "Adding…" else "Create selected Tasks") }
        }
        if (editingRecordId.isBlank() && actions.any { it.confirmed && it.addToTasks }) {
            Text("Save the meeting first; task creation is a separate, explicit action.", style = MaterialTheme.typography.bodySmall)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }

        if (history.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            Text("Saved meeting records", style = MaterialTheme.typography.titleMedium)
            history.forEach { record ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${record.optString("date")} · ${record.optString("title").ifBlank { "Untitled meeting" }}", style = MaterialTheme.typography.titleSmall)
                        Text(record.optString("summary"), style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { loadMeeting(record) }) { Text("Load and correct") }
                            OutlinedButton(onClick = { pendingDeleteId = record.optString("id") }) { Text("Delete ledger") }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(48.dp))
    }

    pendingDeleteId?.let { recordId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete this meeting evidence ledger?") },
            text = { Text("Only the encrypted ledger record will be deleted. Its Notes entry and any Tasks already created will remain unchanged.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { store.delete(PeopleCareerStore.MEETINGS, recordId) } }
                            .onSuccess {
                                if (editingRecordId == recordId) newMeeting()
                                pendingDeleteId = null
                                reloadHistory()
                                Feedback.show("Meeting evidence ledger deleted.")
                            }
                            .onFailure {
                                error = it.message ?: "The meeting ledger could not be deleted."
                                pendingDeleteId = null
                            }
                    }
                }) { Text("Delete ledger") }
            },
            dismissButton = { TextButton(onClick = { pendingDeleteId = null }) { Text("Cancel") } }
        )
    }
}

private fun parseDecisionDrafts(array: JSONArray?): List<MeetingDecisionDraft> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        when (val item = array.opt(index)) {
            is JSONObject -> item.optString("what").takeIf(String::isNotBlank)?.let { what ->
                MeetingDecisionDraft(what = what, evidence = item.optString("evidence"))
            }
            is String -> item.takeIf(String::isNotBlank)?.let { MeetingDecisionDraft(what = it, evidence = "") }
            else -> null
        }
    }
}

private fun parseActionDrafts(array: JSONArray?, transcript: String): List<MeetingActionDraft> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val what = item.optString("what").trim()
        if (what.isBlank()) return@mapNotNull null
        val evidence = item.optString("evidence").trim()
        val speaker = PeopleCareerRules.explicitSpeakerForEvidence(transcript, evidence)
        val suggestedOwner = item.optString("owner").ifBlank { item.optString("who") }.trim()
        val suggestedDeadline = item.optString("deadline").trim()
        val supported = PeopleCareerRules.attributionSupported(
            transcript, evidence, speaker, suggestedOwner, suggestedDeadline
        )
        MeetingActionDraft(
            what = what,
            evidence = evidence,
            speakerDraft = speaker,
            ownerDraft = suggestedOwner.takeIf { supported }.orEmpty(),
            deadlineDraft = suggestedDeadline.takeIf { supported }.orEmpty(),
            unverifiedOwnerSuggestion = suggestedOwner.takeUnless { supported }.orEmpty(),
            unverifiedDeadlineSuggestion = suggestedDeadline.takeUnless { supported }.orEmpty()
        )
    }
}

private fun readDecisionDrafts(array: JSONArray?): List<MeetingDecisionDraft> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val what = item.optString("what")
        if (what.isBlank()) return@mapNotNull null
        MeetingDecisionDraft(
            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
            what = what,
            evidence = item.optString("evidence"),
            confirmed = item.optBoolean("confirmed")
        )
    }
}

private fun readActionDrafts(array: JSONArray?, transcript: String): List<MeetingActionDraft> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        val evidence = item.optString("evidence")
        val speaker = item.optString("speakerDraft", item.optString("speaker"))
        val owner = item.optString("ownerDraft", item.optString("owner"))
        val deadline = item.optString("deadlineDraft", item.optString("deadline"))
        val confirmed = item.optBoolean("confirmed") &&
            PeopleCareerRules.attributionSupported(transcript, evidence, speaker, owner, deadline)
        MeetingActionDraft(
            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
            what = item.optString("what"),
            evidence = evidence,
            speakerDraft = speaker,
            ownerDraft = owner,
            deadlineDraft = deadline,
            unverifiedOwnerSuggestion = item.optString("unverifiedOwnerSuggestion"),
            unverifiedDeadlineSuggestion = item.optString("unverifiedDeadlineSuggestion"),
            confirmed = confirmed,
            addToTasks = item.optBoolean("addToTasks") && confirmed,
            taskAdded = item.optBoolean("taskAdded")
        )
    }
}

private fun meetingRecordJson(
    id: String,
    noteId: Long,
    title: String,
    date: String,
    transcript: String,
    summary: String,
    risks: String,
    decisions: List<MeetingDecisionDraft>,
    actions: List<MeetingActionDraft>
): JSONObject = JSONObject()
    .put("id", id)
    .put("noteId", noteId)
    .put("title", title)
    .put("date", date)
    .put("transcript", transcript)
    .put("summary", summary)
    .put("summaryProvenance", "AI draft; user-editable")
    .put("risks", risks)
    .put("decisions", JSONArray().apply {
        decisions.forEach { decision ->
            put(
                JSONObject()
                    .put("id", decision.id)
                    .put("what", decision.what)
                    .put("evidence", decision.evidence)
                    .put("confirmed", decision.confirmed && PeopleCareerRules.evidenceExists(transcript, decision.evidence))
                    .put("sourceRef", "meeting-transcript")
            )
        }
    })
    .put("actions", JSONArray().apply {
        actions.forEach { action ->
            val supported = PeopleCareerRules.attributionSupported(
                transcript, action.evidence, action.speakerDraft, action.ownerDraft, action.deadlineDraft
            )
            val confirmed = action.confirmed && supported
            put(
                JSONObject()
                    .put("id", action.id)
                    .put("what", action.what)
                    .put("evidence", action.evidence)
                    .put("speaker", action.speakerDraft.takeIf { confirmed }.orEmpty())
                    .put("speakerDraft", action.speakerDraft)
                    .put("owner", action.ownerDraft.takeIf { confirmed }.orEmpty())
                    .put("ownerDraft", action.ownerDraft)
                    .put("deadline", action.deadlineDraft.takeIf { confirmed }.orEmpty())
                    .put("deadlineDraft", action.deadlineDraft)
                    .put("unverifiedOwnerSuggestion", action.unverifiedOwnerSuggestion)
                    .put("unverifiedDeadlineSuggestion", action.unverifiedDeadlineSuggestion)
                    .put("confirmed", confirmed)
                    .put("addToTasks", action.addToTasks && confirmed)
                    .put("taskAdded", action.taskAdded)
                    .put("sourceRef", "meeting-transcript")
            )
        }
    })
    .put("provenance", "captured transcript; exact quote validation; user confirmation required")

private fun renderMeetingNote(
    date: String,
    summary: String,
    risks: String,
    decisions: List<MeetingDecisionDraft>,
    actions: List<MeetingActionDraft>,
    transcript: String
): String = buildString {
    appendLine("# Meeting record")
    appendLine("Date: $date")
    appendLine()
    appendLine("## Summary")
    appendLine(summary.ifBlank { "No summary entered." })
    if (decisions.isNotEmpty()) {
        appendLine()
        appendLine("## Decisions")
        decisions.forEach { decision ->
            val confirmed = decision.confirmed && PeopleCareerRules.evidenceExists(transcript, decision.evidence)
            appendLine("- ${decision.what} ${if (confirmed) "(confirmed)" else "(unconfirmed draft)"}")
            appendLine("  Evidence: ${decision.evidence.ifBlank { "no exact quote provided" }}")
        }
    }
    if (actions.isNotEmpty()) {
        appendLine()
        appendLine("## Actions")
        actions.forEach { action ->
            val confirmed = action.confirmed && PeopleCareerRules.attributionSupported(
                transcript, action.evidence, action.speakerDraft, action.ownerDraft, action.deadlineDraft
            )
            appendLine("- ${action.what}")
            appendLine("  Status: ${if (confirmed) "user-confirmed" else "unconfirmed draft; no task created"}")
            appendLine("  Speaker: ${action.speakerDraft.takeIf { confirmed }.orEmpty().ifBlank { "not confirmed" }}")
            appendLine("  Owner: ${action.ownerDraft.takeIf { confirmed }.orEmpty().ifBlank { "unassigned" }}")
            appendLine("  Deadline: ${action.deadlineDraft.takeIf { confirmed }.orEmpty().ifBlank { "not recorded" }}")
            appendLine("  Evidence: ${action.evidence.ifBlank { "no exact quote provided" }}")
        }
    }
    val riskLines = risks.lines().map(String::trim).filter(String::isNotBlank)
    if (riskLines.isNotEmpty()) {
        appendLine()
        appendLine("## Risks / open questions")
        riskLines.forEach { appendLine("- $it") }
    }
    appendLine()
    appendLine("## Captured transcript")
    appendLine(transcript)
}

private fun taskDueEpoch(deadline: String): Long? = runCatching {
    LocalDate.parse(deadline.trim()).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrNull()