package com.nizam.app.feature.brain2

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nizam.app.feature.nx.Md
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.EmptySpec
import com.nizam.app.ui.design.EmptyState
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

private data class EvidenceReceipt(
    val id: String,
    val kind: String,
    val title: String,
    val capturedAt: Long,
    val outcome: String,
    val coverage: String,
    val uncertainty: String,
    val references: List<String>,
    val details: String
)

private fun JSONArray?.strings(): List<String> =
    this?.let { array -> (0 until array.length()).map { array.optString(it).trim() } }
        .orEmpty()
        .filter { it.isNotBlank() }

private fun cleanHeading(line: String) =
    line.trim().trim('#', '*', ' ', ':').lowercase()

private fun sectionText(report: String, heading: String): String {
    val lines = report.lines()
    val start = lines.indexOfFirst { cleanHeading(it).startsWith(heading.lowercase()) }
    if (start < 0) return ""
    val result = mutableListOf<String>()
    for (line in lines.drop(start + 1)) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#") || (trimmed.startsWith("**") && trimmed.endsWith("**"))) break
        if (trimmed.isNotBlank()) result += trimmed
    }
    return result.joinToString("\n")
}

private val citationMarker = Regex("""https?://[^\s)\]]+|\[[^\]]{2,100}\]""", RegexOption.IGNORE_CASE)

private fun researchReferences(report: String, findings: JSONArray?): List<String> {
    val inSources = sectionText(report, "sources")
        .lineSequence()
        .map { it.trim().removePrefix("-").removePrefix("*").removePrefix("•").trim() }
        .filter { it.isNotBlank() }
        .toList()
    val searchNotes = findings?.let { array ->
        (0 until array.length()).mapNotNull { array.optJSONObject(it)?.optString("answer") }
            .joinToString("\n")
    }.orEmpty()
    val inline = citationMarker.findAll("$report\n$searchNotes").map { it.value }.toList()
    return (inSources + inline).distinct().take(24)
}

private fun researchReceipt(record: JSONObject): EvidenceReceipt {
    val report = record.optString("report")
    val sub = record.optJSONArray("sub")
    val findings = record.optJSONArray("findings")
    val planned = sub?.length() ?: findings?.length() ?: 0
    val returned = findings?.let { array ->
        (0 until array.length()).count { array.optJSONObject(it)?.optBoolean("returned", false) == true }
    }
    val coverage = if (findings != null) {
        "Search responses returned for ${returned ?: 0} of ${findings.length()} planned angles. This measures returned text, not source quality."
    } else {
        "$planned search angle${if (planned == 1) "" else "s"} were planned, but this older report did not save per-angle results."
    }
    val disagreement = sectionText(report, "where sources disagree")
    val uncertainty = if (disagreement.isNotBlank()) {
        "Report's disagreement notes:\n$disagreement\n\nNo calibrated confidence score was recorded."
    } else {
        "No structured confidence score or disagreement section was recorded. Treat the report as provisional; citations are not independently verified by Atlas."
    }
    val id = record.optString("id").ifBlank { record.optLong("t").toString() }
    return EvidenceReceipt(
        id = "research-$id",
        kind = "Deep Research",
        title = record.optString("q").ifBlank { "Research report" },
        capturedAt = record.optLong("t"),
        outcome = report.lineSequence().firstOrNull { it.isNotBlank() }?.take(500).orEmpty(),
        coverage = coverage,
        uncertainty = uncertainty,
        references = researchReferences(report, findings),
        details = report
    )
}

private fun factCheckReceipt(record: JSONObject): EvidenceReceipt {
    val references = record.optJSONArray("sources").strings().distinct().take(24)
    val confidence = record.optInt("confidence", -1).takeIf { it in 0..100 }
    val confidenceReported = record.optBoolean("confidenceReported", false)
    val coverage = "One claim submitted; web search was requested; ${references.size} source reference${if (references.size == 1) "" else "s"} returned by Atlas. Provider search records are not exposed."
    val uncertainty = when {
        confidence == null ->
            "No valid confidence value was recorded. The app does not independently verify the cited references."
        confidenceReported ->
            "Atlas reported ${confidence}% confidence. This is a model estimate, not a calibrated probability; the app does not independently verify the cited references."
        else ->
            "This older record stores ${confidence}%, but does not show whether Atlas returned it or the app used a fallback. Treat it as unverified; citations are not independently verified either."
    }
    val id = record.optString("id").ifBlank { record.optLong("t").toString() }
    val summary = record.optString("summary")
    val detail = record.optString("detail")
    return EvidenceReceipt(
        id = "factcheck-$id",
        kind = "Fact Check · ${record.optString("verdict", "UNPROVEN")}",
        title = record.optString("claim").ifBlank { "Fact check" },
        capturedAt = record.optLong("t"),
        outcome = summary,
        coverage = coverage,
        uncertainty = uncertainty,
        references = references,
        details = listOf(summary, detail).filter { it.isNotBlank() }.joinToString("\n\n")
    )
}

private fun loadReceipts(context: android.content.Context): List<EvidenceReceipt> {
    val research = Research.store(context).all().map(::researchReceipt)
    val checks = FactCheck.store(context).all().map(::factCheckReceipt)
    return (research + checks).sortedByDescending { it.capturedAt }
}

@Composable
fun EvidenceReceiptsScreen() {
    val context = LocalContext.current
    val receipts = remember(context) { loadReceipts(context) }
    AtlasScreen(
        title = "Evidence Receipts",
        subtitle = "Dated references and coverage for Fact Check and Deep Research.",
        hub = Hub.Learn
    ) {
        item("receipt-disclosure") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text(
                    "Dates below are when Atlas saved each run, not source publication dates. References and confidence are AI-reported; Atlas does not validate them against the provider's raw search records.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (receipts.isEmpty()) {
            item("receipt-empty") {
                EmptyState(EmptySpec(Icons.Outlined.FactCheck, "No evidence receipts yet", "Run Fact Check or Deep Research to create a dated record."))
            }
        } else {
            item("receipt-heading") { SectionHeader("${receipts.size} saved run${if (receipts.size == 1) "" else "s"}") }
            receipts.forEach { receipt ->
                item(receipt.id) {
                    var expanded by remember(receipt.id) { mutableStateOf(false) }
                    Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                        Text(receipt.kind, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(
                            if (receipt.capturedAt > 0) {
                                "Saved ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(receipt.capturedAt))}"
                            } else "Date not recorded",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(receipt.title, style = MaterialTheme.typography.titleMedium)
                        if (receipt.outcome.isNotBlank()) Text(receipt.outcome, style = MaterialTheme.typography.bodyMedium)

                        Text("Data coverage", style = MaterialTheme.typography.labelLarge)
                        Text(receipt.coverage, style = MaterialTheme.typography.bodySmall)
                        Text("Uncertainty", style = MaterialTheme.typography.labelLarge)
                        Text(receipt.uncertainty, style = MaterialTheme.typography.bodySmall)

                        Text("References reported by Atlas", style = MaterialTheme.typography.labelLarge)
                        if (receipt.references.isEmpty()) {
                            Text("No references were captured for this run.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            receipt.references.forEach { reference ->
                                Text("• $reference", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        if (receipt.details.isNotBlank()) {
                            OutlinedButton(onClick = { expanded = !expanded }) {
                                Text(if (expanded) "Hide full result" else "Show full result")
                            }
                            if (expanded) {
                                Column {
                                    Md(receipt.details)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}