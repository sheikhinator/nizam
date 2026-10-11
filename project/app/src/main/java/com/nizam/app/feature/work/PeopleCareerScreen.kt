package com.nizam.app.feature.work

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.nizam.app.feature.dynamic.ModuleEntry
import com.nizam.app.feature.finance.Txn
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Entry point intended for central registration by the integration task. */
object PeopleCareerFeature {
    val screen = ScreenSpec(
        "peoplecareer",
        "People & Career",
        "Relationship timelines, career evidence, applications, client runbooks, and claims",
        Icons.Outlined.Work,
        Hub.Work,
        "people relationships meeting actions job applications offers skills evidence clients reimbursement"
    ) { container, _ -> PeopleCareerRecordsScreen(container) }
}

private val WORK_PAGES = listOf("People", "Applications", "Career", "Offers", "Clients", "Claims")

@Composable
fun PeopleCareerRecordsScreen(container: AppContainer) {
    val context = LocalContext.current
    val store = remember(context) { PeopleCareerStore(context) }
    val scope = rememberCoroutineScope()
    val records = remember(store, scope) { WorkRecordsState(store, scope) }
    LaunchedEffect(records) { records.refresh() }

    val people by container.dynamicRepository.entries("people").collectAsState(initial = emptyList())
    val career by container.dynamicRepository.entries("career").collectAsState(initial = emptyList())
    val skills by container.dynamicRepository.entries("skills").collectAsState(initial = emptyList())
    val projects by container.dynamicRepository.entries("projects").collectAsState(initial = emptyList())
    val shifts by container.dynamicRepository.entries("shifts").collectAsState(initial = emptyList())
    val documents by container.dynamicRepository.entries("documents").collectAsState(initial = emptyList())
    val transactions by container.financeRepository.since("1900-01-01").collectAsState(initial = emptyList())

    var selectedPage by remember { mutableStateOf(0) }
    var pendingDelete by remember { mutableStateOf<Pair<String, String>?>(null) }
    var exportText by remember { mutableStateOf("") }
    var exportName by remember { mutableStateOf("atlas-career-packet.md") }
    var exportStatus by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/markdown")
    ) { uri ->
        if (uri == null) {
            exportStatus = "Export cancelled."
        } else {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(exportText) }
                    ?: throw IllegalStateException("The selected location could not be opened.")
            }.onSuccess {
                exportStatus = "Reviewed packet saved."
            }.onFailure {
                exportStatus = it.message ?: "The packet could not be saved."
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            ModuleTitle("People & Career", "Your records, linked and reviewable")
            Text(
                "New records stay encrypted on this device. Existing People, Career, Finance, and document records are only linked when you select them.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        ScrollableTabRow(selectedTabIndex = selectedPage) {
            WORK_PAGES.forEachIndexed { index, title ->
                Tab(
                    selected = selectedPage == index,
                    onClick = { selectedPage = index },
                    text = { Text(title) }
                )
            }
        }
        records.error?.let {
            Text(
                it,
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error
            )
        }
        if (records.loading && records.collections.isEmpty()) {
            Text("Loading private records…", Modifier.padding(18.dp))
        } else {
            when (selectedPage) {
                0 -> PeopleTimelinePage(people, records, pendingDelete = { pendingDelete = it })
                1 -> ApplicationsPage(people, records, pendingDelete = { pendingDelete = it })
                2 -> CareerPage(
                    career = career,
                    skills = skills,
                    records = records,
                    requestDelete = { pendingDelete = it },
                    export = { name, text ->
                        exportName = name
                        exportText = text
                        exportStatus = null
                        exportLauncher.launch(name)
                    },
                    exportStatus = exportStatus
                )
                3 -> OffersPage(records, pendingDelete = { pendingDelete = it })
                4 -> ClientsPage(
                    people, projects, shifts, career, transactions, records,
                    requestDelete = { pendingDelete = it }
                )
                else -> ClaimsPage(
                    transactions, documents, records,
                    requestDelete = { pendingDelete = it },
                    export = { name, text ->
                        exportName = name
                        exportText = text
                        exportStatus = null
                        exportLauncher.launch(name)
                    },
                    exportStatus = exportStatus
                )
            }
        }
    }

    pendingDelete?.let { (collection, id) ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this Atlas record?") },
            text = { Text("Only this People & Career record will be deleted. Linked source records will remain unchanged.") },
            confirmButton = {
                TextButton(onClick = {
                    records.delete(collection, id) { pendingDelete = null }
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Cancel") } }
        )
    }
}

internal class WorkRecordsState(
    private val store: PeopleCareerStore,
    private val scope: CoroutineScope
) {
    var collections by mutableStateOf<Map<String, List<JSONObject>>>(emptyMap())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loading by mutableStateOf(false)
        private set

    fun list(collection: String): List<JSONObject> = collections[collection].orEmpty()

    fun refresh() {
        scope.launch {
            loading = true
            runCatching { withContext(Dispatchers.IO) { store.allCollections() } }
                .onSuccess { collections = it; error = null }
                .onFailure { error = it.message ?: "Private records are unavailable." }
            loading = false
        }
    }

    fun save(collection: String, record: JSONObject, onSaved: (String) -> Unit = {}) {
        scope.launch {
            loading = true
            runCatching {
                withContext(Dispatchers.IO) {
                    val id = store.upsert(collection, record)
                    id to store.allCollections()
                }
            }.onSuccess { (id, all) ->
                collections = all
                error = null
                onSaved(id)
            }.onFailure { error = it.message ?: "The record could not be saved." }
            loading = false
        }
    }

    fun delete(collection: String, id: String, onDeleted: () -> Unit = {}) {
        scope.launch {
            loading = true
            runCatching {
                withContext(Dispatchers.IO) {
                    store.delete(collection, id)
                    store.allCollections()
                }
            }.onSuccess {
                collections = it
                error = null
                onDeleted()
            }.onFailure { error = it.message ?: "The record could not be deleted." }
            loading = false
        }
    }
}

@Composable
private fun PeopleTimelinePage(
    people: List<ModuleEntry>,
    records: WorkRecordsState,
    pendingDelete: (Pair<String, String>) -> Unit
) {
    var personId by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    var kind by remember { mutableStateOf("Conversation") }
    var note by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }
    val events = records.list(PeopleCareerStore.RELATIONSHIP_EVENTS)
        .sortedByDescending { it.optString("date") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("Interaction timeline", style = MaterialTheme.typography.titleLarge)
        Text(
            "Each event links to an existing People record by record ID, not by matching names.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (people.isEmpty()) {
            Text("Add a person in the existing People module before recording a linked event.", Modifier.padding(vertical = 12.dp))
        } else {
            RecordPicker(
                title = "Person",
                options = people.map { it.id.toString() to it.title.ifBlank { "Person #${it.id}" } },
                selected = personId,
                onSelect = { personId = it }
            )
        }
        RecordPicker(
            title = "Interaction type",
            options = listOf("Conversation", "Call", "Message", "Meeting", "Other").map { it to it },
            selected = kind,
            onSelect = { kind = it }
        )
        OutlinedTextField(
            date, { date = it }, singleLine = true,
            label = { Text("Date (YYYY-MM-DD)") }, modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            note, { note = it }, label = { Text("What happened?") },
            modifier = Modifier.fillMaxWidth().height(112.dp)
        )
        formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = personId.isNotBlank() && validIsoDate(date) && note.isNotBlank() && !records.loading,
                onClick = {
                    val record = JSONObject()
                        .put("id", editingId)
                        .put("personEntryId", personId)
                        .put("date", date.trim())
                        .put("kind", kind)
                        .put("note", note.trim())
                        .put("sourceRef", "people:$personId")
                        .put("provenance", "user-entered")
                    records.save(PeopleCareerStore.RELATIONSHIP_EVENTS, record) {
                        editingId = ""; note = ""; formError = null
                    }
                }
            ) { Text(if (editingId.isBlank()) "Save interaction" else "Save correction") }
            if (editingId.isNotBlank()) {
                OutlinedButton(onClick = { editingId = ""; note = ""; formError = null }) { Text("Cancel edit") }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Timeline", style = MaterialTheme.typography.titleMedium)
        if (events.isEmpty()) EmptyHint("No interactions recorded yet.")
        events.forEach { event ->
            val person = people.firstOrNull { it.id.toString() == event.optString("personEntryId") }
            WorkRecordCard(
                title = "${event.optString("date")} · ${person?.title ?: "Missing person record #${event.optString("personEntryId")}"}",
                detail = "${event.optString("kind")} · ${event.optString("note")}",
                provenance = provenanceLabel(event),
                onEdit = {
                    editingId = event.optString("id")
                    personId = event.optString("personEntryId")
                    date = event.optString("date")
                    kind = event.optString("kind", "Conversation")
                    note = event.optString("note")
                },
                onDelete = { pendingDelete(PeopleCareerStore.RELATIONSHIP_EVENTS to event.optString("id")) }
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun ApplicationsPage(
    people: List<ModuleEntry>,
    records: WorkRecordsState,
    pendingDelete: (Pair<String, String>) -> Unit
) {
    val stages = listOf("Saved", "Applied", "Screening", "Interview", "Offer", "Accepted", "Declined", "Rejected", "Withdrawn")
    var company by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var stage by remember { mutableStateOf(stages.first()) }
    var rounds by remember { mutableStateOf("") }
    var followUp by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf("") }
    var contactId by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf("") }
    var formError by remember { mutableStateOf<String?>(null) }
    val applications = records.list(PeopleCareerStore.APPLICATIONS)
        .sortedByDescending { it.optLong("updatedAt") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("Job applications", style = MaterialTheme.typography.titleLarge)
        Text(
            "This is a separate application pipeline; it does not change the general Opportunities module.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(company, { company = it }, singleLine = true, label = { Text("Company") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(role, { role = it }, singleLine = true, label = { Text("Role") }, modifier = Modifier.fillMaxWidth())
        RecordPicker("Stage", stages.map { it to it }, stage, { stage = it })
        OutlinedTextField(
            rounds, { rounds = it }, label = { Text("Interview rounds / dated notes") },
            modifier = Modifier.fillMaxWidth().height(88.dp)
        )
        OutlinedTextField(followUp, { followUp = it }, singleLine = true, label = { Text("Follow-up date (YYYY-MM-DD, optional)") }, modifier = Modifier.fillMaxWidth())
        if (people.isNotEmpty()) {
            RecordPicker(
                "Optional linked contact",
                listOf("" to "No contact linked") + people.map { it.id.toString() to it.title },
                contactId,
                { contactId = it }
            )
        }
        OutlinedTextField(
            outcome, { outcome = it }, label = { Text("Outcome or next step") },
            modifier = Modifier.fillMaxWidth().height(88.dp)
        )
        formError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = company.isNotBlank() && role.isNotBlank() &&
                    (followUp.isBlank() || validIsoDate(followUp)) && !records.loading,
                onClick = {
                    val record = JSONObject()
                        .put("id", editingId)
                        .put("company", company.trim())
                        .put("role", role.trim())
                        .put("stage", stage)
                        .put("rounds", rounds.trim())
                        .put("followUpDate", followUp.trim())
                        .put("outcome", outcome.trim())
                        .put("contactEntryId", contactId)
                        .put("source", "user-entered")
                        .put("provenance", "user-entered")
                    records.save(PeopleCareerStore.APPLICATIONS, record) {
                        editingId = ""; company = ""; role = ""; rounds = ""; followUp = ""
                        outcome = ""; contactId = ""; stage = stages.first(); formError = null
                    }
                }
            ) { Text(if (editingId.isBlank()) "Save application" else "Save correction") }
            if (editingId.isNotBlank()) {
                OutlinedButton(onClick = {
                    editingId = ""; company = ""; role = ""; rounds = ""; followUp = ""; outcome = ""; contactId = ""
                }) { Text("Cancel edit") }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("Pipeline", style = MaterialTheme.typography.titleMedium)
        if (applications.isEmpty()) EmptyHint("No applications tracked yet.")
        applications.forEach { app ->
            val contact = people.firstOrNull { it.id.toString() == app.optString("contactEntryId") }
            WorkRecordCard(
                title = "${app.optString("company")} · ${app.optString("role")}",
                detail = buildString {
                    append(app.optString("stage"))
                    if (app.optString("followUpDate").isNotBlank()) append(" · follow up ${app.optString("followUpDate")}")
                    if (contact != null) append(" · contact ${contact.title}")
                    if (app.optString("rounds").isNotBlank()) append("\n${app.optString("rounds")}")
                    if (app.optString("outcome").isNotBlank()) append("\n${app.optString("outcome")}")
                    if (app.optString("contactEntryId").isNotBlank() && contact == null) {
                        append("\nLinked contact record missing: #${app.optString("contactEntryId")}")
                    }
                },
                provenance = provenanceLabel(app),
                onEdit = {
                    editingId = app.optString("id")
                    company = app.optString("company")
                    role = app.optString("role")
                    stage = app.optString("stage", stages.first())
                    rounds = app.optString("rounds")
                    followUp = app.optString("followUpDate")
                    outcome = app.optString("outcome")
                    contactId = app.optString("contactEntryId")
                },
                onDelete = { pendingDelete(PeopleCareerStore.APPLICATIONS to app.optString("id")) }
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun OffersPage(records: WorkRecordsState, pendingDelete: (Pair<String, String>) -> Unit) {
    var employer by remember { mutableStateOf("") }
    var role by remember { mutableStateOf("") }
    var monthlyPay by remember { mutableStateOf("") }
    var annualBonus by remember { mutableStateOf("0") }
    var monthlyBenefits by remember { mutableStateOf("0") }
    var hoursPerWeek by remember { mutableStateOf("40") }
    var commuteMinutes by remember { mutableStateOf("0") }
    var flexibility by remember { mutableStateOf("") }
    var priorities by remember { mutableStateOf("") }
    var assumptions by remember { mutableStateOf("") }
    var editingId by remember { mutableStateOf("") }
    val offers = records.list(PeopleCareerStore.OFFERS).sortedByDescending { it.optLong("updatedAt") }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp)) {
        Text("Offer comparison", style = MaterialTheme.typography.titleLarge)
        Text(
            "Compare offers field by field. Monthly cash is salary + annual bonus ÷ 12 + estimated monthly benefits; gross hourly estimate divides that by hours/week × 52 ÷ 12. This does not rank or choose for you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedTextField(employer, { employer = it }, singleLine = true, label = { Text("Company / offer name") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(role, { role = it }, singleLine = true, label = { Text("Role") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(monthlyPay, { monthlyPay = it }, singleLine = true, label = { Text("Monthly base pay (PKR)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(annualBonus, { annualBonus = it }, singleLine = true, label = { Text("Annual bonus estimate (PKR)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(monthlyBenefits, { monthlyBenefits = it }, singleLine = true, label = { Text("Monthly benefits estimate (PKR)") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(hoursPerWeek, { hoursPerWeek = it }, singleLine = true, label = { Text("Hours per week") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(commuteMinutes, { commuteMinutes = it }, singleLine = true, label = { Text("Commute minutes each way") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(flexibility, { flexibility = it }, label = { Text("Flexibility / benefits details") }, modifier = Modifier.fillMaxWidth().height(80.dp))
        OutlinedTextField(priorities, { priorities = it }, label = { Text("Your priorities for this offer") }, modifier = Modifier.fillMaxWidth().height(80.dp))
        OutlinedTextField(assumptions, { assumptions = it }, label = { Text("Other assumptions / unknowns") }, modifier = Modifier.fillMaxWidth().height(80.dp))

        val parsedMonthly = monthlyPay.toDoubleOrNull()
        val parsedBonus = annualBonus.toDoubleOrNull()
        val parsedBenefits = monthlyBenefits.toDoubleOrNull()
        val parsedHours = hoursPerWeek.toDoubleOrNull()
        val parsedCommute = commuteMinutes.toDoubleOrNull()
        val validOffer = employer.isNotBlank() && role.isNotBlank() &&
            parsedMonthly != null && parsedMonthly.isFinite() && parsedMonthly >= 0 &&
            parsedBonus != null && parsedBonus.isFinite() && parsedBonus >= 0 &&
            parsedBenefits != null && parsedBenefits.isFinite() && parsedBenefits >= 0 &&
            parsedHours != null && parsedHours.isFinite() && parsedHours > 0 &&
            parsedCommute != null && parsedCommute.isFinite() && parsedCommute >= 0
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = validOffer && !records.loading,
                onClick = {
                    val record = JSONObject()
                        .put("id", editingId)
                        .put("employer", employer.trim())
                        .put("role", role.trim())
                        .put("monthlyPay", parsedMonthly!!)
                        .put("annualBonus", parsedBonus!!)
                        .put("monthlyBenefits", parsedBenefits!!)
                        .put("hoursPerWeek", parsedHours!!)
                        .put("commuteOneWayMinutes", parsedCommute!!)
                        .put("flexibility", flexibility.trim())
                        .put("priorities", priorities.trim())
                        .put("assumptions", assumptions.trim())
                        .put("provenance", "user-entered")
                    records.save(PeopleCareerStore.OFFERS, record) {
                        editingId = ""; employer = ""; role = ""; monthlyPay = ""; annualBonus = "0"
                        monthlyBenefits = "0"; hoursPerWeek = "40"; commuteMinutes = "0"
                        flexibility = ""; priorities = ""; assumptions = ""
                    }
                }
            ) { Text(if (editingId.isBlank()) "Save offer" else "Save correction") }
            if (editingId.isNotBlank()) {
                OutlinedButton(onClick = { editingId = "" }) { Text("Cancel edit") }
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("Compared offers", style = MaterialTheme.typography.titleMedium)
        if (offers.isEmpty()) EmptyHint("Add offers to compare their stated terms.")
        offers.forEach { offer ->
            val monthly = PeopleCareerRules.monthlyOfferCash(
                offer.optDouble("monthlyPay"),
                offer.optDouble("annualBonus"),
                offer.optDouble("monthlyBenefits")
            ) ?: 0.0
            val hourly = PeopleCareerRules.estimatedGrossHourly(monthly, offer.optDouble("hoursPerWeek"))
            val commute = offer.optDouble("commuteOneWayMinutes")
            WorkRecordCard(
                title = "${offer.optString("employer")} · ${offer.optString("role")}",
                detail = buildString {
                    appendLine("Base ${money(offer.optDouble("monthlyPay"))}/month · bonus ${money(offer.optDouble("annualBonus"))}/year")
                    appendLine("Benefits estimate ${money(offer.optDouble("monthlyBenefits"))}/month")
                    appendLine("Estimated monthly cash ${money(monthly)} · estimated gross ${hourly?.let { money(it) } ?: "n/a"}/hour")
                    appendLine("Commute ${commute.toInt()} min each way · flexibility: ${offer.optString("flexibility").ifBlank { "not entered" }}")
                    appendLine("Priority: ${offer.optString("priorities").ifBlank { "not entered" }}")
                    append("Assumptions: ${offer.optString("assumptions").ifBlank { "none recorded" }}")
                },
                provenance = provenanceLabel(offer),
                onEdit = {
                    editingId = offer.optString("id")
                    employer = offer.optString("employer")
                    role = offer.optString("role")
                    monthlyPay = offer.optDouble("monthlyPay").toString()
                    annualBonus = offer.optDouble("annualBonus").toString()
                    monthlyBenefits = offer.optDouble("monthlyBenefits").toString()
                    hoursPerWeek = offer.optDouble("hoursPerWeek").toString()
                    commuteMinutes = offer.optDouble("commuteOneWayMinutes").toString()
                    flexibility = offer.optString("flexibility")
                    priorities = offer.optString("priorities")
                    assumptions = offer.optString("assumptions")
                },
                onDelete = { pendingDelete(PeopleCareerStore.OFFERS to offer.optString("id")) }
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
internal fun RecordPicker(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(label) }
                )
            }
        }
    }
}

@Composable
internal fun WorkRecordCard(
    title: String,
    detail: String,
    provenance: String,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(detail, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            Text(provenance, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEdit) { Text("Edit") }
                OutlinedButton(onClick = onDelete) { Text("Delete") }
            }
        }
    }
}

@Composable
internal fun EmptyHint(text: String) {
    Text(text, Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
}

internal fun provenanceLabel(record: JSONObject): String {
    val created = localDateTime(record.optLong("createdAt"))
    val updated = localDateTime(record.optLong("updatedAt"))
    val source = record.optString("provenance").ifBlank { "user-entered" }
    return if (created == updated) "$source · created $created" else "$source · created $created · edited $updated"
}

internal fun validIsoDate(value: String): Boolean =
    runCatching { LocalDate.parse(value.trim()); true }.getOrDefault(false)

internal fun localDateTime(epochMillis: Long): String =
    if (epochMillis <= 0) "unknown date" else runCatching {
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate().toString()
    }.getOrDefault("unknown date")

internal fun money(value: Double): String =
    "PKR " + String.format(Locale.US, "%,.0f", value)

internal fun stringArray(json: JSONObject, key: String): List<String> {
    val array = json.optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).map { array.optString(it) }.filter(String::isNotBlank)
}

internal fun longArray(json: JSONObject, key: String): List<Long> =
    stringArray(json, key).mapNotNull(String::toLongOrNull)

internal fun longArrayJson(values: Collection<Long>): JSONArray =
    JSONArray().apply { values.distinct().forEach { put(it) } }

internal fun stringArrayJson(values: Collection<String>): JSONArray =
    JSONArray().apply { values.distinct().forEach { put(it) } }

internal fun dynamicTitle(entry: ModuleEntry): String =
    entry.title.ifBlank { "${entry.moduleKey} record #${entry.id}" }

internal fun filterRedactionTerms(text: String): List<String> =
    text.split(',', '\n').map(String::trim).filter(String::isNotBlank).distinctBy { it.lowercase() }

internal fun findTransaction(transactions: List<Txn>, id: Long): Txn? =
    transactions.firstOrNull { it.id == id }