package com.nizam.app.feature.work

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.nizam.app.feature.dynamic.ModuleEntry
import com.nizam.app.feature.finance.Txn
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

@Composable
internal fun CareerPage(
    career: List<ModuleEntry>,
    skills: List<ModuleEntry>,
    records: WorkRecordsState,
    requestDelete: (Pair<String, String>) -> Unit,
    export: (String, String) -> Unit,
    exportStatus: String?
) {
    var storyTitle by remember { mutableStateOf("") }
    var storyQuestion by remember { mutableStateOf("") }
    var storySkill by remember { mutableStateOf("") }
    var storyCareerId by remember { mutableStateOf("") }
    var situation by remember { mutableStateOf("") }
    var task by remember { mutableStateOf("") }
    var action by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var editingStoryId by remember { mutableStateOf("") }

    var linkedSkillId by remember { mutableStateOf("") }
    var linkedCareerId by remember { mutableStateOf("") }
    var proofNote by remember { mutableStateOf("") }
    var editingLinkId by remember { mutableStateOf("") }

    var selectedExportIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var redactTerms by remember { mutableStateOf("") }
    var redactExport by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf("") }
    var previewBuilt by remember { mutableStateOf(false) }

    val orderedCareer = career.sortedByDescending { it.localDate }
    val stories = records.list(PeopleCareerStore.INTERVIEW_STORIES).sortedByDescending { it.optLong("updatedAt") }
    val links = records.list(PeopleCareerStore.SKILL_EVIDENCE).sortedByDescending { it.optLong("updatedAt") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("Career evidence and interview stories", style = MaterialTheme.typography.titleLarge)
        Text(
            "Career wins, skills, projects, and attached files stay in their existing Atlas records. This workspace links to the records you select.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Text("Evidence from Career", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
        if (orderedCareer.isEmpty()) EmptyHint("No Career records yet. Add wins, skills, or projects in the existing Career module.")
        orderedCareer.take(40).forEach { entry ->
            val file = entry.value("file")
            Text(
                "${entry.localDate} · ${dynamicTitle(entry)} · ${entry.value("type").ifBlank { "Career record" }}\n" +
                    "Impact: ${entry.value("impact").ifBlank { "not entered" }}\n" +
                    "Skills: ${entry.value("skills").ifBlank { "not entered" }} · " +
                    "Evidence: ${if (file.isBlank()) "none linked" else "file on Career record #${entry.id}"}",
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(16.dp))
        Text(if (editingStoryId.isBlank()) "Interview story bank" else "Correct interview story", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(storyTitle, { storyTitle = it }, singleLine = true, label = { Text("Story title") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(storyQuestion, { storyQuestion = it }, label = { Text("Interview question or competency") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(storySkill, { storySkill = it }, singleLine = true, label = { Text("Skill / competency") }, modifier = Modifier.fillMaxWidth())
        RecordPicker(
            "Source Career record",
            orderedCareer.map { it.id.toString() to "${dynamicTitle(it)} · ${it.localDate}" },
            storyCareerId,
            { storyCareerId = it }
        )
        OutlinedTextField(situation, { situation = it }, label = { Text("Situation") }, modifier = Modifier.fillMaxWidth().height(76.dp))
        OutlinedTextField(task, { task = it }, label = { Text("Task") }, modifier = Modifier.fillMaxWidth().height(76.dp))
        OutlinedTextField(action, { action = it }, label = { Text("Your action") }, modifier = Modifier.fillMaxWidth().height(88.dp))
        OutlinedTextField(result, { result = it }, label = { Text("Result / evidence") }, modifier = Modifier.fillMaxWidth().height(88.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = storyTitle.isNotBlank() && storyQuestion.isNotBlank() && storyCareerId.isNotBlank() &&
                    situation.isNotBlank() && task.isNotBlank() && action.isNotBlank() && result.isNotBlank() && !records.loading,
                onClick = {
                    val source = orderedCareer.firstOrNull { it.id.toString() == storyCareerId }
                    val draft = JSONObject()
                        .put("id", editingStoryId)
                        .put("title", storyTitle.trim())
                        .put("question", storyQuestion.trim())
                        .put("skill", storySkill.trim())
                        .put("careerEntryId", storyCareerId)
                        .put("sourceRef", "career:$storyCareerId")
                        .put("sourceTitle", source?.let(::dynamicTitle).orEmpty())
                        .put("situation", situation.trim())
                        .put("task", task.trim())
                        .put("action", action.trim())
                        .put("result", result.trim())
                        .put("provenance", "user-entered, linked to Career #$storyCareerId")
                    records.save(PeopleCareerStore.INTERVIEW_STORIES, draft) {
                        storyTitle = ""; storyQuestion = ""; storySkill = ""; storyCareerId = ""
                        situation = ""; task = ""; action = ""; result = ""; editingStoryId = ""
                    }
                }
            ) { Text(if (editingStoryId.isBlank()) "Save story" else "Save correction") }
            if (editingStoryId.isNotBlank()) {
                OutlinedButton(onClick = {
                    storyTitle = ""; storyQuestion = ""; storySkill = ""; storyCareerId = ""
                    situation = ""; task = ""; action = ""; result = ""; editingStoryId = ""
                }) { Text("Cancel edit") }
            }
        }
        if (stories.isEmpty()) EmptyHint("No interview stories yet.")
        stories.forEach { story ->
            val source = career.firstOrNull { it.id.toString() == story.optString("careerEntryId") }
            WorkRecordCard(
                title = "${story.optString("title")} · ${story.optString("skill").ifBlank { "skill not tagged" }}",
                detail = buildString {
                    appendLine("Question: ${story.optString("question")}")
                    appendLine("STAR: ${story.optString("situation")} / ${story.optString("task")} / ${story.optString("action")} / ${story.optString("result")}")
                    append("Source: ${source?.let(::dynamicTitle) ?: "missing Career record #${story.optString("careerEntryId")}"}")
                },
                provenance = provenanceLabel(story),
                onEdit = {
                    editingStoryId = story.optString("id")
                    storyTitle = story.optString("title")
                    storyQuestion = story.optString("question")
                    storySkill = story.optString("skill")
                    storyCareerId = story.optString("careerEntryId")
                    situation = story.optString("situation")
                    task = story.optString("task")
                    action = story.optString("action")
                    result = story.optString("result")
                },
                onDelete = { requestDelete(PeopleCareerStore.INTERVIEW_STORIES to story.optString("id")) }
            )
        }

        Spacer(Modifier.height(18.dp))
        Text("Skill evidence links", style = MaterialTheme.typography.titleMedium)
        Text(
            "A skill with no linked Career record remains self-assessed; creating a link never changes its level.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        RecordPicker(
            "Skill Tree record",
            skills.map { it.id.toString() to dynamicTitle(it) },
            linkedSkillId,
            { linkedSkillId = it }
        )
        RecordPicker(
            "Supporting Career record",
            orderedCareer.map { it.id.toString() to "${dynamicTitle(it)} · ${it.localDate}" },
            linkedCareerId,
            { linkedCareerId = it }
        )
        OutlinedTextField(proofNote, { proofNote = it }, label = { Text("What does this record demonstrate?") }, modifier = Modifier.fillMaxWidth().height(76.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = linkedSkillId.isNotBlank() && linkedCareerId.isNotBlank() && proofNote.isNotBlank() && !records.loading,
                onClick = {
                    val skill = skills.firstOrNull { it.id.toString() == linkedSkillId }
                    val evidence = orderedCareer.firstOrNull { it.id.toString() == linkedCareerId }
                    records.save(
                        PeopleCareerStore.SKILL_EVIDENCE,
                        JSONObject()
                            .put("id", editingLinkId)
                            .put("skillEntryId", linkedSkillId)
                            .put("skillTitle", skill?.let(::dynamicTitle).orEmpty())
                            .put("careerEntryId", linkedCareerId)
                            .put("careerTitle", evidence?.let(::dynamicTitle).orEmpty())
                            .put("proofNote", proofNote.trim())
                            .put("sourceRefs", "skills:${linkedSkillId}|career:${linkedCareerId}")
                            .put("provenance", "user-linked source records")
                    ) {
                        linkedSkillId = ""; linkedCareerId = ""; proofNote = ""; editingLinkId = ""
                    }
                }
            ) { Text(if (editingLinkId.isBlank()) "Link evidence" else "Save correction") }
            if (editingLinkId.isNotBlank()) {
                OutlinedButton(onClick = { editingLinkId = ""; linkedSkillId = ""; linkedCareerId = ""; proofNote = "" }) {
                    Text("Cancel edit")
                }
            }
        }
        if (links.isEmpty()) EmptyHint("No proof links yet; skills without evidence remain visibly self-assessed.")
        links.forEach { link ->
            val skill = skills.firstOrNull { it.id.toString() == link.optString("skillEntryId") }
            val evidence = career.firstOrNull { it.id.toString() == link.optString("careerEntryId") }
            WorkRecordCard(
                title = "${skill?.let(::dynamicTitle) ?: link.optString("skillTitle", "Missing skill")} ← " +
                    (evidence?.let(::dynamicTitle) ?: "missing Career record #${link.optString("careerEntryId")}"),
                detail = link.optString("proofNote"),
                provenance = provenanceLabel(link),
                onEdit = {
                    editingLinkId = link.optString("id")
                    linkedSkillId = link.optString("skillEntryId")
                    linkedCareerId = link.optString("careerEntryId")
                    proofNote = link.optString("proofNote")
                },
                onDelete = { requestDelete(PeopleCareerStore.SKILL_EVIDENCE to link.optString("id")) }
            )
        }

        Spacer(Modifier.height(18.dp))
        Text("Career evidence packet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Select the Career entries to include. The export lists source record IDs instead of exposing raw file URIs.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        orderedCareer.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = entry.id in selectedExportIds,
                    onCheckedChange = { checked ->
                        selectedExportIds = if (checked) selectedExportIds + entry.id else selectedExportIds - entry.id
                        previewBuilt = false
                    }
                )
                Text("${dynamicTitle(entry)} · ${entry.localDate}", style = MaterialTheme.typography.bodySmall)
            }
        }
        OutlinedTextField(
            redactTerms,
            { redactTerms = it; previewBuilt = false },
            label = { Text("Terms to redact before export (comma separated)") },
            modifier = Modifier.fillMaxWidth().height(76.dp)
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = redactExport, onCheckedChange = { redactExport = it; previewBuilt = false })
            Text("Apply these redactions to the packet preview")
        }
        if (redactExport && filterRedactionTerms(redactTerms).isEmpty()) {
            Text(
                "No redaction terms are set. Review names and details carefully before saving.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Button(
            enabled = selectedExportIds.isNotEmpty(),
            onClick = {
                val selected = orderedCareer.filter { it.id in selectedExportIds }
                val raw = buildCareerPacket(selected, stories, links, skills)
                preview = if (redactExport) PeopleCareerRules.redact(raw, filterRedactionTerms(redactTerms)) else raw
                previewBuilt = true
            }
        ) { Text("Build editable preview") }
        if (previewBuilt) {
            Text("Review and edit the complete packet before saving it.", style = MaterialTheme.typography.labelMedium)
            OutlinedTextField(
                preview,
                { preview = it },
                label = { Text("Career packet preview") },
                modifier = Modifier.fillMaxWidth().height(260.dp)
            )
            Button(
                enabled = preview.isNotBlank(),
                onClick = { export("atlas-career-packet.md", preview) }
            ) { Text("Save reviewed packet…") }
        }
        exportStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Spacer(Modifier.height(40.dp))
    }
}

private fun buildCareerPacket(
    selected: List<ModuleEntry>,
    stories: List<JSONObject>,
    links: List<JSONObject>,
    skills: List<ModuleEntry>
): String = buildString {
    appendLine("# Career evidence packet")
    appendLine("Prepared ${LocalDate.now()}. Review all statements and source links before sharing.")
    appendLine("Only the records selected in Atlas are included.")
    selected.forEach { entry ->
        appendLine()
        appendLine("## ${dynamicTitle(entry)}")
        appendLine("- Source: Career record #${entry.id}, dated ${entry.localDate}")
        appendLine("- Type: ${entry.value("type").ifBlank { "not recorded" }}")
        appendLine("- Impact: ${entry.value("impact").ifBlank { "not recorded" }}")
        appendLine("- Skills: ${entry.value("skills").ifBlank { "not recorded" }}")
        appendLine("- Hours invested: ${entry.value("hours").ifBlank { "not recorded" }}")
        appendLine("- Evidence file: ${if (entry.value("file").isBlank()) "not linked" else "attached to source record #${entry.id} (not copied into this text file)"}")
        stories.filter { it.optString("careerEntryId") == entry.id.toString() }.forEach { story ->
            appendLine()
            appendLine("### Interview story: ${story.optString("title")}")
            appendLine("- Question: ${story.optString("question")}")
            appendLine("- Skill: ${story.optString("skill")}")
            appendLine("- Situation: ${story.optString("situation")}")
            appendLine("- Task: ${story.optString("task")}")
            appendLine("- Action: ${story.optString("action")}")
            appendLine("- Result: ${story.optString("result")}")
        }
        links.filter { it.optString("careerEntryId") == entry.id.toString() }.forEach { link ->
            val skill = skills.firstOrNull { it.id.toString() == link.optString("skillEntryId") }
            appendLine("- Skill proof: ${skill?.let(::dynamicTitle) ?: link.optString("skillTitle")} — ${link.optString("proofNote")} " +
                "(Skill Tree #${link.optString("skillEntryId")}, Career #${entry.id})")
        }
        appendLine("- Supporting source IDs: career:${entry.id}")
    }
    appendLine()
    appendLine("All narrative fields are user-entered; confirm details and redact identifying information as needed.")
}

@Composable
internal fun ClientsPage(
    people: List<ModuleEntry>,
    projects: List<ModuleEntry>,
    shifts: List<ModuleEntry>,
    career: List<ModuleEntry>,
    transactions: List<Txn>,
    records: WorkRecordsState,
    requestDelete: (Pair<String, String>) -> Unit
) {
    var clientName by remember { mutableStateOf("") }
    var organization by remember { mutableStateOf("") }
    var contactPersonId by remember { mutableStateOf("") }
    var clientNotes by remember { mutableStateOf("") }
    var editingClientId by remember { mutableStateOf("") }

    var engagementTitle by remember { mutableStateOf("") }
    var engagementClientId by remember { mutableStateOf("") }
    var projectId by remember { mutableStateOf("") }
    var scopeText by remember { mutableStateOf("") }
    var deliverables by remember { mutableStateOf("") }
    var revisionLimit by remember { mutableStateOf("") }
    var approvals by remember { mutableStateOf("") }
    var renewalDate by remember { mutableStateOf("") }
    var editingEngagementId by remember { mutableStateOf("") }
    var linkEngagementId by remember { mutableStateOf("") }
    var incomeIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var expenseIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var workRefs by remember { mutableStateOf<Set<String>>(emptySet()) }

    val clients = records.list(PeopleCareerStore.CLIENTS).sortedByDescending { it.optLong("updatedAt") }
    val engagements = records.list(PeopleCareerStore.ENGAGEMENTS).sortedByDescending { it.optLong("updatedAt") }
    val workEntries = (shifts + career).distinctBy { "${it.moduleKey}:${it.id}" }
    val transactionById = transactions.associateBy { it.id }
    val workByRef = workEntries.associateBy { "${it.moduleKey}:${it.id}" }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("Clients and engagements", style = MaterialTheme.typography.titleLarge)
        Text(
            "Client profiles and engagement runbooks are separate records. A contact, project, Finance transaction, or work log is linked only by selecting its record.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text("Client profile", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(clientName, { clientName = it }, singleLine = true, label = { Text("Client / client name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(organization, { organization = it }, singleLine = true, label = { Text("Organization") }, modifier = Modifier.fillMaxWidth())
        RecordPicker(
            "Optional linked Atlas contact",
            listOf("" to "No contact linked") + people.map { it.id.toString() to it.title },
            contactPersonId,
            { contactPersonId = it }
        )
        OutlinedTextField(clientNotes, { clientNotes = it }, label = { Text("Contact / working notes") }, modifier = Modifier.fillMaxWidth().height(72.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = clientName.isNotBlank() && !records.loading,
                onClick = {
                    records.save(
                        PeopleCareerStore.CLIENTS,
                        JSONObject()
                            .put("id", editingClientId)
                            .put("name", clientName.trim())
                            .put("organization", organization.trim())
                            .put("contactPersonEntryId", contactPersonId)
                            .put("notes", clientNotes.trim())
                            .put("sourceRef", if (contactPersonId.isBlank()) "" else "people:$contactPersonId")
                            .put("provenance", "user-entered")
                    ) {
                        clientName = ""; organization = ""; contactPersonId = ""; clientNotes = ""; editingClientId = ""
                    }
                }
            ) { Text(if (editingClientId.isBlank()) "Save client" else "Save correction") }
            if (editingClientId.isNotBlank()) {
                OutlinedButton(onClick = { clientName = ""; organization = ""; contactPersonId = ""; clientNotes = ""; editingClientId = "" }) {
                    Text("Cancel edit")
                }
            }
        }
        clients.forEach { client ->
            val person = people.firstOrNull { it.id.toString() == client.optString("contactPersonEntryId") }
            WorkRecordCard(
                title = "${client.optString("name")}${client.optString("organization").let { if (it.isBlank()) "" else " · $it" }}",
                detail = "${person?.let(::dynamicTitle) ?: if (client.optString("contactPersonEntryId").isBlank()) "No contact linked" else "Missing contact #${client.optString("contactPersonEntryId")}"}\n${client.optString("notes")}",
                provenance = provenanceLabel(client),
                onEdit = {
                    editingClientId = client.optString("id")
                    clientName = client.optString("name")
                    organization = client.optString("organization")
                    contactPersonId = client.optString("contactPersonEntryId")
                    clientNotes = client.optString("notes")
                },
                onDelete = { requestDelete(PeopleCareerStore.CLIENTS to client.optString("id")) }
            )
        }

        Spacer(Modifier.height(18.dp))
        Text("Engagement runbook", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(engagementTitle, { engagementTitle = it }, singleLine = true, label = { Text("Engagement name") }, modifier = Modifier.fillMaxWidth())
        RecordPicker(
            "Client",
            clients.map { it.optString("id") to it.optString("name") },
            engagementClientId,
            { engagementClientId = it }
        )
        RecordPicker(
            "Optional linked Atlas project",
            listOf("" to "No project linked") + projects.map { it.id.toString() to dynamicTitle(it) },
            projectId,
            { projectId = it }
        )
        OutlinedTextField(scopeText, { scopeText = it }, label = { Text("Agreed scope") }, modifier = Modifier.fillMaxWidth().height(88.dp))
        OutlinedTextField(deliverables, { deliverables = it }, label = { Text("Deliverables") }, modifier = Modifier.fillMaxWidth().height(88.dp))
        OutlinedTextField(revisionLimit, { revisionLimit = it }, singleLine = true, label = { Text("Revision limit") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(approvals, { approvals = it }, label = { Text("Approval checkpoints") }, modifier = Modifier.fillMaxWidth().height(72.dp))
        OutlinedTextField(renewalDate, { renewalDate = it }, singleLine = true, label = { Text("Renewal date (YYYY-MM-DD, optional)") }, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = engagementTitle.isNotBlank() && engagementClientId.isNotBlank() &&
                    scopeText.isNotBlank() && (renewalDate.isBlank() || validIsoDate(renewalDate)) && !records.loading,
                onClick = {
                    val existing = engagements.firstOrNull { it.optString("id") == editingEngagementId }
                    val draft = JSONObject()
                        .put("id", editingEngagementId)
                        .put("title", engagementTitle.trim())
                        .put("clientId", engagementClientId)
                        .put("projectEntryId", projectId)
                        .put("scope", scopeText.trim())
                        .put("deliverables", deliverables.trim())
                        .put("revisionLimit", revisionLimit.trim())
                        .put("approvalCheckpoints", approvals.trim())
                        .put("renewalDate", renewalDate.trim())
                        .put("incomeTxnIds", JSONArray(stringArray(existing ?: JSONObject(), "incomeTxnIds")))
                        .put("expenseTxnIds", JSONArray(stringArray(existing ?: JSONObject(), "expenseTxnIds")))
                        .put("workRefs", JSONArray(stringArray(existing ?: JSONObject(), "workRefs")))
                        .put("sourceRef", if (projectId.isBlank()) "client:$engagementClientId" else "project:$projectId")
                        .put("provenance", "user-entered runbook; source records linked by ID")
                    records.save(PeopleCareerStore.ENGAGEMENTS, draft) {
                        engagementTitle = ""; engagementClientId = ""; projectId = ""; scopeText = ""
                        deliverables = ""; revisionLimit = ""; approvals = ""; renewalDate = ""; editingEngagementId = ""
                    }
                }
            ) { Text(if (editingEngagementId.isBlank()) "Save runbook" else "Save correction") }
            if (editingEngagementId.isNotBlank()) {
                OutlinedButton(onClick = { editingEngagementId = ""; engagementTitle = ""; engagementClientId = ""; projectId = ""; scopeText = ""; deliverables = ""; revisionLimit = ""; approvals = ""; renewalDate = "" }) {
                    Text("Cancel edit")
                }
            }
        }

        Text("Runbooks and recorded profitability", Modifier.padding(top = 14.dp), style = MaterialTheme.typography.titleMedium)
        if (engagements.isEmpty()) EmptyHint("No engagements yet.")
        engagements.forEach { engagement ->
            val client = clients.firstOrNull { it.optString("id") == engagement.optString("clientId") }
            val project = projects.firstOrNull { it.id.toString() == engagement.optString("projectEntryId") }
            val linkedIncome = longArray(engagement, "incomeTxnIds").mapNotNull(transactionById::get)
                .filter { it.kind == "INCOME" }
            val linkedExpenses = longArray(engagement, "expenseTxnIds").mapNotNull(transactionById::get)
                .filter { it.kind == "EXPENSE" }
            val linkedWork = stringArray(engagement, "workRefs").mapNotNull(workByRef::get)
            val income = linkedIncome.sumOf { it.amount }
            val cost = linkedExpenses.sumOf { it.amount }
            val hours = linkedWork.sumOf { it.value("hours").toDoubleOrNull() ?: 0.0 }
            WorkRecordCard(
                title = "${client?.optString("name") ?: "Missing client #${engagement.optString("clientId")}"} · ${engagement.optString("title")}",
                detail = buildString {
                    appendLine("Scope: ${engagement.optString("scope")}")
                    appendLine("Deliverables: ${engagement.optString("deliverables").ifBlank { "not entered" }}")
                    appendLine("Revisions: ${engagement.optString("revisionLimit").ifBlank { "not specified" }} · approvals: ${engagement.optString("approvalCheckpoints").ifBlank { "not specified" }}")
                    appendLine("Renewal: ${engagement.optString("renewalDate").ifBlank { "not recorded" }} · project: ${project?.let(::dynamicTitle) ?: "none or source missing"}")
                    appendLine("Linked income ${money(income)} · expenses ${money(cost)} · margin ${money(income - cost)} · logged hours ${hours}")
                    appendLine("Margin per logged hour: ${if (hours > 0) money((income - cost) / hours) else "n/a"}")
                    appendLine("Sources: ${linkedIncome.size} income, ${linkedExpenses.size} expenses, ${linkedWork.size} work records")
                    append("Calculation uses only the selected Finance and work-hour records; unlinked records are excluded.")
                },
                provenance = provenanceLabel(engagement),
                onEdit = {
                    editingEngagementId = engagement.optString("id")
                    engagementTitle = engagement.optString("title")
                    engagementClientId = engagement.optString("clientId")
                    projectId = engagement.optString("projectEntryId")
                    scopeText = engagement.optString("scope")
                    deliverables = engagement.optString("deliverables")
                    revisionLimit = engagement.optString("revisionLimit")
                    approvals = engagement.optString("approvalCheckpoints")
                    renewalDate = engagement.optString("renewalDate")
                },
                onDelete = { requestDelete(PeopleCareerStore.ENGAGEMENTS to engagement.optString("id")) }
            )
            Row {
                OutlinedButton(onClick = {
                    linkEngagementId = engagement.optString("id")
                    incomeIds = longArray(engagement, "incomeTxnIds").toSet()
                    expenseIds = longArray(engagement, "expenseTxnIds").toSet()
                    workRefs = stringArray(engagement, "workRefs").toSet()
                }) { Text(if (linkEngagementId == engagement.optString("id")) "Editing source links" else "Manage profitability links") }
            }
        }

        if (linkEngagementId.isNotBlank()) {
            val active = engagements.firstOrNull { it.optString("id") == linkEngagementId }
            if (active != null) {
                Spacer(Modifier.height(8.dp))
                Text("Link source records to ${active.optString("title")}", style = MaterialTheme.typography.titleSmall)
                Text("Only these explicitly selected records contribute to the totals.", style = MaterialTheme.typography.bodySmall)
                transactions.take(80).forEach { txn ->
                    val target = if (txn.kind == "INCOME") incomeIds else expenseIds
                    val checked = txn.id in target
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = {
                                if (txn.kind == "INCOME") incomeIds = toggled(incomeIds, txn.id)
                                else expenseIds = toggled(expenseIds, txn.id)
                            }
                        )
                        Text(
                            "${txn.localDate} · ${txn.kind} · ${money(txn.amount)} · ${txn.category} · Finance #${txn.id}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                workEntries.take(80).forEach { entry ->
                    val ref = "${entry.moduleKey}:${entry.id}"
                    val hours = entry.value("hours").toDoubleOrNull() ?: 0.0
                    if (hours > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Checkbox(checked = ref in workRefs, onCheckedChange = { workRefs = toggled(workRefs, ref) })
                            Text("${entry.localDate} · ${dynamicTitle(entry)} · $hours h · ${entry.moduleKey} #${entry.id}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !records.loading,
                        onClick = {
                            val updated = JSONObject(active.toString())
                                .put("incomeTxnIds", longArrayJson(incomeIds))
                                .put("expenseTxnIds", longArrayJson(expenseIds))
                                .put("workRefs", stringArrayJson(workRefs))
                                .put("provenance", "user-linked Finance and work records")
                            records.save(PeopleCareerStore.ENGAGEMENTS, updated) { linkEngagementId = "" }
                        }
                    ) { Text("Save selected source links") }
                    OutlinedButton(onClick = { linkEngagementId = "" }) { Text("Cancel") }
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
internal fun ClaimsPage(
    transactions: List<Txn>,
    documents: List<ModuleEntry>,
    records: WorkRecordsState,
    requestDelete: (Pair<String, String>) -> Unit,
    export: (String, String) -> Unit,
    exportStatus: String?
) {
    val expenses = transactions.filter { it.kind == "EXPENSE" }.take(100)
    var title by remember { mutableStateOf("") }
    var employer by remember { mutableStateOf("") }
    var period by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Draft") }
    var notes by remember { mutableStateOf("") }
    var selectedExpenses by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var claimAmounts by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var selectedDocs by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var editingId by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }

    var activePacketId by remember { mutableStateOf("") }
    var redactTerms by remember { mutableStateOf("") }
    var redactExport by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf("") }
    var previewBuilt by remember { mutableStateOf(false) }
    val packets = records.list(PeopleCareerStore.REIMBURSEMENTS).sortedByDescending { it.optLong("updatedAt") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("Reimbursement packets", style = MaterialTheme.typography.titleLarge)
        Text(
            "Select recorded work expenses and receipt documents. A partial claim is allowed; Atlas never submits a packet or sends it to an employer.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(title, { title = it }, singleLine = true, label = { Text("Packet name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(employer, { employer = it }, singleLine = true, label = { Text("Employer / recipient") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(period, { period = it }, singleLine = true, label = { Text("Claim period") }, modifier = Modifier.fillMaxWidth())
        RecordPicker(
            "Packet status (a note only; no submission occurs)",
            listOf("Draft", "Reviewed", "Submitted manually", "Reimbursed", "Rejected").map { it to it },
            status,
            { status = it }
        )
        OutlinedTextField(notes, { notes = it }, label = { Text("Claim notes") }, modifier = Modifier.fillMaxWidth().height(72.dp))

        Text("Recorded expenses", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
        if (expenses.isEmpty()) EmptyHint("No Finance expense records are available to select.")
        expenses.forEach { txn ->
            val checked = txn.id in selectedExpenses
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = {
                        selectedExpenses = if (it) selectedExpenses + txn.id else selectedExpenses - txn.id
                        if (it && claimAmounts[txn.id].isNullOrBlank()) {
                            claimAmounts = claimAmounts + (txn.id to txn.amount.toString())
                        }
                    }
                )
                Column(Modifier.weight(1f)) {
                    Text("${txn.localDate} · ${txn.category} · ${money(txn.amount)} · Finance #${txn.id}", style = MaterialTheme.typography.bodySmall)
                    if (txn.note.isNotBlank()) Text(txn.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (checked) {
                OutlinedTextField(
                    claimAmounts[txn.id].orEmpty(),
                    { claimAmounts = claimAmounts + (txn.id to it) },
                    singleLine = true,
                    label = { Text("Claim amount (up to ${money(txn.amount)})") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Text("Receipt / supporting documents", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.titleSmall)
        if (documents.isEmpty()) EmptyHint("No Documents records found. Packets can be drafted, but will be marked as missing receipt evidence.")
        documents.take(80).forEach { document ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = document.id in selectedDocs,
                    onCheckedChange = { selectedDocs = if (it) selectedDocs + document.id else selectedDocs - document.id }
                )
                Text(
                    "${dynamicTitle(document)} · ${document.value("category").ifBlank { "document" }} · Documents #${document.id}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        val selectedRows = expenses.filter { it.id in selectedExpenses }
        val validRows = selectedRows.filter { txn ->
            val amount = claimAmounts[txn.id]?.toDoubleOrNull()
            amount != null && PeopleCareerRules.validClaimAmount(amount, txn.amount)
        }
        val validPacket = title.isNotBlank() && employer.isNotBlank() && selectedRows.isNotEmpty() &&
            validRows.size == selectedRows.size
        val requestedTotal = validRows.sumOf { claimAmounts[it.id]?.toDoubleOrNull() ?: 0.0 }
        Text("Requested total: ${money(requestedTotal)}", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.titleSmall)
        formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = validPacket && !records.loading,
                onClick = {
                    val items = JSONArray().apply {
                        validRows.forEach { txn ->
                            put(
                                JSONObject()
                                    .put("txnId", txn.id)
                                    .put("claimAmount", claimAmounts[txn.id]?.toDoubleOrNull() ?: 0.0)
                                    .put("expenseAmount", txn.amount)
                                    .put("date", txn.localDate)
                                    .put("category", txn.category)
                                    .put("note", txn.note)
                            )
                        }
                    }
                    records.save(
                        PeopleCareerStore.REIMBURSEMENTS,
                        JSONObject()
                            .put("id", editingId)
                            .put("title", title.trim())
                            .put("employer", employer.trim())
                            .put("period", period.trim())
                            .put("status", status)
                            .put("notes", notes.trim())
                            .put("items", items)
                            .put("receiptDocumentIds", longArrayJson(selectedDocs))
                            .put("requestedTotal", requestedTotal)
                            .put("provenance", "user-selected Finance expenses and Documents evidence")
                    ) { id ->
                        activePacketId = id
                        previewBuilt = false
                        editingId = ""
                        title = ""; employer = ""; period = ""; status = "Draft"; notes = ""
                        selectedExpenses = emptySet(); claimAmounts = emptyMap(); selectedDocs = emptySet()
                        formError = null
                    }
                }
            ) { Text(if (editingId.isBlank()) "Save claim packet" else "Save correction") }
            if (editingId.isNotBlank()) {
                OutlinedButton(onClick = {
                    editingId = ""; title = ""; employer = ""; period = ""; status = "Draft"; notes = ""
                    selectedExpenses = emptySet(); claimAmounts = emptyMap(); selectedDocs = emptySet()
                }) { Text("Cancel edit") }
            }
        }
        if (selectedRows.isNotEmpty() && validRows.size != selectedRows.size) {
            Text("Each claim amount must be greater than zero and no more than its recorded expense.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        Spacer(Modifier.height(18.dp))
        Text("Saved packets", style = MaterialTheme.typography.titleMedium)
        if (packets.isEmpty()) EmptyHint("No claim packets yet.")
        packets.forEach { packet ->
            val items = packet.optJSONArray("items") ?: JSONArray()
            val docs = longArray(packet, "receiptDocumentIds")
            WorkRecordCard(
                title = "${packet.optString("title")} · ${packet.optString("status")}",
                detail = "${packet.optString("employer")} · ${packet.optString("period")}\n" +
                    "${items.length()} expense lines · ${money(packet.optDouble("requestedTotal"))} requested · " +
                    if (docs.isEmpty()) "receipt evidence missing" else "${docs.size} linked receipt document(s)",
                provenance = provenanceLabel(packet),
                onEdit = {
                    editingId = packet.optString("id")
                    activePacketId = editingId
                    previewBuilt = false
                    title = packet.optString("title")
                    employer = packet.optString("employer")
                    period = packet.optString("period")
                    status = packet.optString("status", "Draft")
                    notes = packet.optString("notes")
                    val parsedItems = packetItems(packet)
                    selectedExpenses = parsedItems.mapNotNull { it.optLong("txnId").takeIf { id -> id > 0 } }.toSet()
                    claimAmounts = parsedItems.mapNotNull { item ->
                        val transactionId = item.optLong("txnId")
                        if (transactionId > 0) transactionId to item.optDouble("claimAmount").toString() else null
                    }.toMap()
                    selectedDocs = docs.toSet()
                },
                onDelete = { requestDelete(PeopleCareerStore.REIMBURSEMENTS to packet.optString("id")) }
            )
            OutlinedButton(onClick = {
                activePacketId = packet.optString("id")
                val raw = buildClaimPacket(packet, transactions, documents)
                preview = if (redactExport) PeopleCareerRules.redact(raw, filterRedactionTerms(redactTerms)) else raw
                previewBuilt = true
            }) { Text(if (activePacketId == packet.optString("id")) "Refresh editable preview" else "Preview / export") }
        }

        val activePacket = packets.firstOrNull { it.optString("id") == activePacketId }
        if (activePacket != null) {
            Spacer(Modifier.height(12.dp))
            Text("Reviewed export", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                redactTerms, { redactTerms = it; previewBuilt = false },
                label = { Text("Terms to redact (comma separated)") },
                modifier = Modifier.fillMaxWidth().height(72.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = redactExport, onCheckedChange = { redactExport = it; previewBuilt = false })
                Text("Apply redactions when refreshing the preview")
            }
            if (redactExport && filterRedactionTerms(redactTerms).isEmpty()) {
                Text("No redaction terms are set. Review names and details before saving.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = {
                val raw = buildClaimPacket(activePacket, transactions, documents)
                preview = if (redactExport) PeopleCareerRules.redact(raw, filterRedactionTerms(redactTerms)) else raw
                previewBuilt = true
            }) { Text(if (previewBuilt) "Refresh preview" else "Build editable preview") }
            if (previewBuilt) {
                OutlinedTextField(
                    preview, { preview = it },
                    label = { Text("Claim packet preview") },
                    modifier = Modifier.fillMaxWidth().height(250.dp)
                )
                Button(
                    enabled = preview.isNotBlank(),
                    onClick = { export("reimbursement-packet.md", preview) }
                ) { Text("Save reviewed packet…") }
            }
            exportStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Spacer(Modifier.height(40.dp))
    }
}

private fun packetItems(packet: JSONObject): List<JSONObject> {
    val items = packet.optJSONArray("items") ?: return emptyList()
    return (0 until items.length()).map { items.getJSONObject(it) }
}

private fun buildClaimPacket(
    packet: JSONObject,
    transactions: List<Txn>,
    documents: List<ModuleEntry>
): String = buildString {
    appendLine("# Reimbursement packet — ${packet.optString("title")}")
    appendLine("Recipient: ${packet.optString("employer")}")
    appendLine("Period: ${packet.optString("period").ifBlank { "not specified" }}")
    appendLine("Status recorded in Atlas: ${packet.optString("status")} (manual status only; nothing was submitted)")
    appendLine("Requested total: ${money(packet.optDouble("requestedTotal"))}")
    appendLine()
    appendLine("## Expense lines")
    packetItems(packet).forEach { item ->
        val txnId = item.optLong("txnId")
        val txn = findTransaction(transactions, txnId)
        val amount = item.optDouble("claimAmount")
        val full = item.optDouble("expenseAmount")
        appendLine("- ${item.optString("date")} · ${item.optString("category")} · claim ${money(amount)} " +
            "of recorded ${money(full)} · Finance #$txnId${if (amount < full) " (partial claim)" else ""}")
        appendLine("  Note: ${txn?.note ?: item.optString("note").ifBlank { "not entered" }}")
    }
    val evidenceIds = longArray(packet, "receiptDocumentIds")
    appendLine()
    appendLine("## Supporting documents")
    if (evidenceIds.isEmpty()) appendLine("- Missing receipt/document evidence — attach or review before sharing.")
    evidenceIds.forEach { id ->
        val doc = documents.firstOrNull { it.id == id }
        appendLine("- ${doc?.let(::dynamicTitle) ?: "Missing Documents record"} · Documents #$id")
    }
    appendLine()
    appendLine("Claim notes: ${packet.optString("notes").ifBlank { "none" }}")
    appendLine("Source links: " + packetItems(packet).joinToString { "finance:${it.optLong("txnId")}" })
    appendLine("Check the editable preview, totals, and redactions before saving or sharing this file.")
}

private fun <K> toggled(current: Set<K>, value: K): Set<K> =
    if (value in current) current - value else current + value
