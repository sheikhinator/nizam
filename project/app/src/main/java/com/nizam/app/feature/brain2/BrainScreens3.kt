package com.nizam.app.feature.brain2

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.FactCheck
import androidx.compose.material.icons.outlined.TravelExplore
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.notes.MarkdownView
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.EmptySpec
import com.nizam.app.ui.design.EmptyState
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.pressable
import kotlinx.coroutines.launch
import org.json.JSONObject

@Composable
private fun ReportView(md: String) {
    val ctx = LocalContext.current
    MarkdownView(md, onLink = {}, onTag = {}, onUrl = { u ->
        runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }, onToggleTask = {}, embed = { null })
}

@Composable
fun ResearchScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var q by remember { mutableStateOf("") }
    var step by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<JSONObject?>(null) }
    var list by remember { mutableStateOf(Research.store(ctx).all().reversed()) }
    val o = open
    if (o != null) {
        AtlasScreen(title = o.optString("q").take(60), subtitle = "Research report", hub = Hub.Learn, actions = {
            Text("Close", Modifier.padding(Space.s).pressable { open = null }, style = MaterialTheme.typography.labelLarge)
        }) {
            item("r") { Column(Modifier.padding(horizontal = Space.page)) { SelectionContainer { ReportView(o.optString("report")) } } }
            item("del") {
                Text("Delete report", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(Space.page).pressable { Research.store(ctx).remove(o.optString("id")); list = Research.store(ctx).all().reversed(); open = null })
            }
        }
        return
    }
    AtlasScreen(title = "Deep research", subtitle = "Atlas splits your question, searches every angle at once and writes one report with sources", hub = Hub.Learn) {
        item("ask") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("e.g. Is solar worth it for a 5 marla house in Lahore in 2026?") }, minLines = 2)
                Button(onClick = {
                    val question = q.trim()
                    step = "Starting"
                    scope.launch {
                        runCatching { Research.run(ctx, container, question) { step = it } }
                            .onSuccess { open = it; q = ""; list = Research.store(ctx).all().reversed() }
                            .onFailure { Feedback.show(it.message ?: "Research failed") }
                        step = null
                    }
                }, enabled = q.isNotBlank() && step == null, modifier = Modifier.padding(top = Space.s)) { Text(step?.let { "$it…" } ?: "Research") }
            }
        }
        if (list.isEmpty() && step == null) item("e") { EmptyState(EmptySpec(Icons.Outlined.TravelExplore, "No reports yet", "Ask something that needs more than one search.")) }
        if (list.isNotEmpty()) item("h") { SectionHeader("Reports") }
        list.forEach { r ->
            item(r.optString("id")) {
                ListRow(r.optString("q"), supporting = java.text.DateFormat.getDateInstance().format(java.util.Date(r.optLong("t"))),
                    icon = Icons.Outlined.Description) { open = r }
            }
        }
    }
}

@Composable
fun FactCheckScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var claim by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<FactCheck.Verdict?>(null) }
    var history by remember { mutableStateOf(FactCheck.store(ctx).all().reversed()) }
    fun colour(v: String) = when (v) { "TRUE" -> Color(0xFF3FA37C); "FALSE" -> Color(0xFFD9534F); "MISLEADING" -> Color(0xFFE0A030); else -> Color(0xFF8E97B8) }
    AtlasScreen(title = "Fact check", subtitle = "Paste a forward, a headline or a claim. Atlas checks it against current sources.", hub = Hub.Learn) {
        item("in") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                OutlinedTextField(claim, { claim = it }, Modifier.fillMaxWidth().heightIn(min = 96.dp), placeholder = { Text("Paste the message here") })
                Button(onClick = {
                    busy = true; result = null
                    scope.launch {
                        runCatching { FactCheck.check(ctx, container, claim.trim()) }
                            .onSuccess { result = it; history = FactCheck.store(ctx).all().reversed() }
                            .onFailure { Feedback.show(it.message ?: "Couldn't check") }
                        busy = false
                    }
                }, enabled = claim.isNotBlank() && !busy, modifier = Modifier.padding(top = Space.s)) { Text(if (busy) "Checking sources…" else "Check it") }
            }
        }
        result?.let { v ->
            item("v") {
                Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Text(v.verdict, color = colour(v.verdict), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                        Text(
                            if (v.confidenceReported && v.confidence != null) "${v.confidence}% model-reported"
                            else "No confidence score returned",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(v.summary, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = Space.s))
                    if (v.detail.isNotBlank()) Text(v.detail, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = Space.s))
                    v.sources.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)) }
                }
            }
        }
        if (history.isNotEmpty()) item("hh") { SectionHeader("Checked before") }
        history.take(30).forEach { h ->
            item(h.optString("id")) {
                ListRow(h.optString("claim").take(90), supporting = h.optString("verdict") + " · " + h.optString("summary"),
                    icon = Icons.Outlined.FactCheck, tint = colour(h.optString("verdict"))) {
                    val confidence = h.optInt("confidence", -1).takeIf { it in 0..100 }
                    result = FactCheck.Verdict(
                        verdict = h.optString("verdict"),
                        confidence = confidence,
                        summary = h.optString("summary"),
                        detail = h.optString("detail"),
                        sources = h.optJSONArray("sources")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty(),
                        confidenceReported = h.optBoolean("confidenceReported", false) && confidence != null
                    )
                }
            }
        }
    }
}

@Composable
fun CalcScreen(container: AppContainer) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var sheet by remember { mutableStateOf("price = 2.5 lakh\ndiscount = price * 12%\nprice - discount\nemi(15 lakh, 18, 36)") }
    var words by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val lines = remember(sheet) { Calc.sheet(sheet) }
    AtlasScreen(title = "Calculator", subtitle = "Worked out on your phone — exact, never guessed. Name values, reuse them, use lakh and crore.", hub = Hub.Tools) {
        item("sheet") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                OutlinedTextField(sheet, { sheet = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace))
            }
        }
        item("res") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                lines.forEach { l ->
                    if (l.result != null) Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text(l.src.trim(), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Text(if (l.error) l.result else "= ${l.result}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                            color = if (l.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        item("fx") {
            Row(Modifier.padding(horizontal = Space.page, vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                listOf("sqrt(", "emi(", "grow(", "round(", "%", "^").forEach { t -> Capsule(t, false) { sheet += t } }
            }
        }
        item("wh") { SectionHeader("Or ask in words") }
        item("w") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                OutlinedTextField(words, { words = it }, Modifier.fillMaxWidth(), placeholder = { Text("e.g. Zakat on 7 tola gold at 2.9 lakh a tola plus 3 lakh cash") }, minLines = 2)
                Button(onClick = {
                    busy = true
                    scope.launch {
                        runCatching { Calc.fromWords(container, words.trim()) }.onSuccess { sheet = "# ${words.trim()}\n$it"; words = "" }
                            .onFailure { Feedback.show(it.message ?: "Couldn't set it up") }
                        busy = false
                    }
                }, enabled = words.isNotBlank() && !busy, modifier = Modifier.padding(top = Space.s)) { Text(if (busy) "Setting up…" else "Set it up") }
                Text("The AI only writes the steps; every number is calculated on the phone, so you can see and edit each line.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.s))
            }
        }
    }
}

