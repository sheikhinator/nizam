package com.nizam.app.feature.agentic

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.dash.Dash
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.AiSpec
import com.nizam.app.feature.nx.F
import com.nizam.app.feature.nx.K
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.net.Ai
import com.nizam.app.net.Http
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.time.LocalDate

// Ideas adapted from the awesome-llm-apps collection: tools Atlas did not have yet.
// Nothing here replaces an existing feature.

// ───────────────────────── Prompt compaction (fewer tokens for the same question) ─────────────────────────

object Compact {
    private fun p(c: Context) = c.getSharedPreferences("agentic", 0)
    fun prompt(c: Context?, text: String): String {
        if (text.length < 3000) return text
        val out = text.replace(Regex("[ \\t]+"), " ").replace(Regex("(?m)^ +"), "").replace(Regex("\\n{3,}"), "\n\n").trim()
        if (c != null && out.length < text.length) runCatching { p(c).edit().putLong("saved_chars", p(c).getLong("saved_chars", 0L) + (text.length - out.length)).apply() }
        return out
    }
    fun savedTokens(c: Context): Long = p(c).getLong("saved_chars", 0L) / 4
}

// ───────────────────────── Tamper-evident log (hash-chained) ─────────────────────────

/** Every agent action is chained to the one before it with SHA-256, so an edited or deleted entry is detectable. */
object ChainLog {
    private fun store(c: Context) = JsonStore(c, "chainlog")
    private fun p(c: Context) = c.getSharedPreferences("chainlog_meta", 0)
    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun entries(c: Context) = store(c).all().sortedBy { it.optLong("n") }

    @Synchronized fun append(c: Context, action: String, detail: String) {
        val list = entries(c)
        val prev = list.lastOrNull()?.optString("h") ?: p(c).getString("anchor", "GENESIS").orEmpty()
        val n = p(c).getLong("n", 0L) + 1; p(c).edit().putLong("n", n).apply()
        val t = System.currentTimeMillis()
        val h = sha("$prev|$n|$t|$action|$detail")
        store(c).add(JSONObject().put("n", n).put("t", t).put("a", action).put("d", detail.take(200)).put("p", prev).put("h", h))
        if (list.size + 1 > 600) {
            // keep the chain verifiable after trimming: the hash of the last removed entry becomes the new starting anchor
            val drop = list.take(list.size + 1 - 600)
            p(c).edit().putString("anchor", drop.last().optString("h")).apply()
            drop.forEach { store(c).remove(it.optString("id")) }
        }
    }

    /** Returns null when the whole chain is intact, otherwise the sequence number of the first broken entry. */
    fun firstBroken(c: Context): Long? {
        var prev = p(c).getString("anchor", "GENESIS").orEmpty()
        for (e in entries(c)) {
            val ok = e.optString("p") == prev && e.optString("h") == sha("$prev|${e.optLong("n")}|${e.optLong("t")}|${e.optString("a")}|${e.optString("d")}")
            if (!ok) return e.optLong("n")
            prev = e.optString("h")
        }
        return null
    }
    fun recent(c: Context, n: Int) = entries(c).takeLast(n).reversed()
    fun count(c: Context) = entries(c).size
}

@Composable
private fun ChainLogScreen() {
    val c = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var verdict by remember { mutableStateOf("") }
    val rows = remember(tick) { ChainLog.recent(c, 30) }
    val fmt = remember { java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Every action an agent takes is sealed into a chain: each entry carries a fingerprint of the one before it. If anyone edits or deletes an entry later, the chain breaks and this page tells you where.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Card { Text("${ChainLog.count(c)} sealed entries", style = MaterialTheme.typography.titleSmall); if (verdict.isNotBlank()) Text(verdict, style = MaterialTheme.typography.bodyMedium) }
        Button(onClick = { val b = ChainLog.firstBroken(c); verdict = if (b == null) "✓ Chain intact. Nothing has been altered." else "✕ Chain broken at entry #$b. That entry or one before it was changed."; tick++ }) { Text("Verify now") }
        rows.forEach { e -> Text("#${e.optLong("n")} · ${fmt.format(java.util.Date(e.optLong("t")))} · ${e.optString("a")}: ${e.optString("d")}", style = MaterialTheme.typography.bodySmall) }
        if (rows.isEmpty()) Text("Nothing sealed yet. Entries appear as agents act.", style = MaterialTheme.typography.bodySmall)
    }
}

// ───────────────────────── Ask my data (CSV) ─────────────────────────

private fun parseCsv(text: String): List<List<String>> {
    val rows = ArrayList<List<String>>(); val cur = ArrayList<String>(); val sb = StringBuilder(); var q = false; var i = 0
    while (i < text.length) {
        val ch = text[i]
        if (q) {
            if (ch == '"') { if (i + 1 < text.length && text[i + 1] == '"') { sb.append('"'); i++ } else q = false } else sb.append(ch)
        } else if (ch == '"') q = true
        else if (ch == ',' || ch == '\t') { cur.add(sb.toString()); sb.setLength(0) }
        else if (ch == '\n') { cur.add(sb.toString().trimEnd('\r')); sb.setLength(0); rows.add(ArrayList(cur)); cur.clear() }
        else sb.append(ch)
        i++
    }
    if (sb.isNotEmpty() || cur.isNotEmpty()) { cur.add(sb.toString()); rows.add(ArrayList(cur)) }
    return rows.filter { r -> r.any { it.isNotBlank() } }
}

private fun summarise(rows: List<List<String>>): String {
    if (rows.size < 2) return ""
    val head = rows[0]; val body = rows.drop(1)
    val sb = StringBuilder("Rows: ${body.size}. Columns: ${head.size}.\n")
    head.forEachIndexed { i, name ->
        val vals = body.mapNotNull { it.getOrNull(i)?.trim() }.filter { it.isNotEmpty() }
        val nums = vals.mapNotNull { it.replace(",", "").toDoubleOrNull() }
        if (vals.isNotEmpty() && nums.size * 10 >= vals.size * 7) {
            sb.append("- $name (number): count ${nums.size}, sum ${"%.2f".format(nums.sum())}, average ${"%.2f".format(nums.average())}, min ${nums.min()}, max ${nums.max()}\n")
        } else {
            val top = vals.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).joinToString { "${it.key} (${it.value})" }
            sb.append("- $name (text): ${vals.toSet().size} distinct; most common: $top\n")
        }
    }
    return sb.toString()
}

@Composable
private fun AskDataScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var raw by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var out by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch { raw = withContext(Dispatchers.IO) { runCatching { c.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }?.take(400_000).orEmpty() }.getOrDefault("") } }
    }
    val rows = remember(raw) { parseCsv(raw) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Open a CSV or spreadsheet export (or paste rows), then ask questions in plain words. The sums and averages are worked out on your phone, so the numbers are exact.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onClick = { pick.launch(arrayOf("*/*")) }) { Text("Open file") }; if (raw.isNotBlank()) TextButton(onClick = { raw = ""; out = "" }) { Text("Clear") } }
        OutlinedTextField(raw, { raw = it.take(400_000) }, label = { Text("or paste CSV here") }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
        if (rows.size >= 2) Card { Text("${rows.size - 1} rows, ${rows[0].size} columns", style = MaterialTheme.typography.titleSmall); Text(rows[0].joinToString(" · ").take(240), style = MaterialTheme.typography.bodySmall) }
        OutlinedTextField(q, { q = it }, label = { Text("Your question, e.g. which month had the highest total?") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = rows.size >= 2 && q.isNotBlank() && !busy, onClick = {
            busy = true; out = ""
            scope.launch {
                val sample = rows.take(26).joinToString("\n") { it.joinToString(" | ") }
                out = runCatching { Ai.generate(container.settingsRepository,
                    "Data summary (exact, computed on the phone):\n${summarise(rows)}\nFirst rows:\n$sample\n\nQuestion: $q\nAnswer plainly. Use the exact figures from the summary where they apply. If the question needs rows you were not shown, say what to filter or check instead of guessing.",
                    "You are a careful data analyst. Never invent numbers.", 900) }.getOrElse { "Couldn't answer: ${it.message}" }
                busy = false
            }
        }) { Text(if (busy) "Thinking…" else "Ask") }
        if (out.isNotBlank()) SelectionContainer { Text(out, style = MaterialTheme.typography.bodyMedium) }
    }
}

// ───────────────────────── Chat with a web page ─────────────────────────

private fun htmlToText(h: String): String = h
    .replace(Regex("(?is)<(script|style|noscript|svg)[^>]*>.*?</\\1>"), " ")
    .replace(Regex("(?s)<[^>]+>"), " ")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
    .replace(Regex("\\s+"), " ").trim()

@Composable
private fun WebChatScreen(container: AppContainer) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var url by remember { mutableStateOf("") }
    var page by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val log = remember { mutableStateListOf<Pair<String, String>>() }
    fun ask(question: String) {
        busy = true
        scope.launch {
            val a = runCatching { Ai.generate(container.settingsRepository,
                "Web page text:\n${page.take(12_000)}\n\nQuestion: $question\nAnswer only from this page. If the page does not say, answer that it does not say. Quote a short phrase when it helps.",
                "You answer questions about a web page strictly from its text.", 800) }.getOrElse { "Couldn't answer: ${it.message}" }
            log.add(0, question to a); busy = false
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Paste a link, load it, then ask anything about the page. Atlas answers only from what the page actually says.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(url, { url = it }, label = { Text("Web address") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = url.isNotBlank() && !busy, onClick = {
            busy = true; status = "Loading…"; page = ""; log.clear()
            scope.launch {
                val u = url.trim().let { if (it.startsWith("http")) it else "https://$it" }
                val r = runCatching { htmlToText(Http.getText(u)) }.getOrNull()
                busy = false
                if (r.isNullOrBlank()) status = "Couldn't read that page. Check the link and your connection." else { page = r; status = "Loaded ${r.length} characters." }
            }
        }) { Text("Load page") }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall)
        if (page.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = false, onClick = { if (!busy) ask("Summarise this page in 5 bullet points.") }, label = { Text("Summary") })
                FilterChip(selected = false, onClick = { if (!busy) ask("List every date, number, price and name mentioned, as a table.") }, label = { Text("Pull the facts") })
            }
            OutlinedTextField(q, { q = it }, label = { Text("Ask about this page") }, modifier = Modifier.fillMaxWidth())
            Button(enabled = q.isNotBlank() && !busy, onClick = { ask(q); q = "" }) { Text(if (busy) "Thinking…" else "Ask") }
        }
        log.forEach { (qq, aa) -> Card { Text(qq, style = MaterialTheme.typography.titleSmall); SelectionContainer { Text(aa, style = MaterialTheme.typography.bodyMedium) } } }
    }
}

// ───────────────────────── Research papers (arXiv) ─────────────────────────

private data class Paper(val title: String, val summary: String, val link: String, val date: String)

private fun tagOf(b: String, n: String) = Regex("(?s)<$n[^>]*>(.*?)</$n>").find(b)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim().orEmpty()

@Composable
private fun PapersScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val pr = remember { c.getSharedPreferences("agentic", 0) }
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var papers by remember { mutableStateOf<List<Paper>>(emptyList()) }
    var explain by remember { mutableStateOf<Pair<String, String>?>(null) }
    var interests by remember { mutableStateOf(pr.getString("arxiv_q", "").orEmpty().split("|").filter { it.isNotBlank() }) }
    fun search(query: String) {
        busy = true; msg = ""; papers = emptyList()
        scope.launch {
            val xml = runCatching { Http.getText("https://export.arxiv.org/api/query?search_query=all:${java.net.URLEncoder.encode(query, "UTF-8")}&max_results=8&sortBy=submittedDate&sortOrder=descending") }.getOrNull()
            busy = false
            if (xml == null) { msg = "Couldn't reach arXiv. Check your connection."; return@launch }
            papers = Regex("(?s)<entry>(.*?)</entry>").findAll(xml).map { m -> val b = m.groupValues[1]; Paper(tagOf(b, "title"), tagOf(b, "summary"), tagOf(b, "id"), tagOf(b, "published").take(10)) }.toList()
            if (papers.isEmpty()) msg = "No papers found."
            interests = (listOf(query) + interests).distinct().take(8); pr.edit().putString("arxiv_q", interests.joinToString("|")).apply()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Search the newest research papers on any topic, then have Atlas explain one in plain words. Your recent topics are remembered.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(q, { q = it }, label = { Text("Topic, e.g. small language models") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = q.isNotBlank() && !busy, onClick = { search(q) }) { Text(if (busy) "Searching…" else "Search") }
        if (interests.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { interests.take(3).forEach { i -> FilterChip(selected = false, onClick = { q = i; search(i) }, label = { Text(i.take(24)) }) } }
        if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall)
        papers.forEach { p ->
            Card {
                Text(p.title, style = MaterialTheme.typography.titleSmall)
                Text(p.date, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(p.summary.take(260) + "…", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(onClick = { scope.launch { explain = p.title to "Reading…"
                        explain = p.title to runCatching { Ai.generate(container.settingsRepository, "Explain this research paper abstract to a smart non-expert: what problem, what they did, why it matters, and one limitation.\nTitle: ${p.title}\nAbstract: ${p.summary}", "You explain research clearly and honestly.", 700) }.getOrElse { "Couldn't explain: ${it.message}" } } }) { Text("Explain simply") }
                    TextButton(onClick = { runCatching { c.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(p.link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Open") }
                }
            }
        }
        explain?.let { (t, a) -> Card { Text("In plain words: $t", style = MaterialTheme.typography.titleSmall); SelectionContainer { Text(a, style = MaterialTheme.typography.bodyMedium) } } }
    }
}

// ───────────────────────── Cited answers over my notes (self-checking retrieval) ─────────────────────────

private fun terms(q: String): Set<String> = q.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()

private fun retrieve(notes: List<com.nizam.app.feature.notes.Note>, q: String, k: Int): List<com.nizam.app.feature.notes.Note> {
    val t = terms(q)
    return notes.map { n ->
        val title = n.title.lowercase(); val text = (n.title + " " + n.body).lowercase()
        n to t.sumOf { w -> (if (w in title) 3 else 0) + minOf(5, text.split(w).size - 1) }
    }.filter { it.second > 0 }.sortedByDescending { it.second }.take(k).map { it.first }
}

@Composable
private fun CitedAnswerScreen(container: AppContainer) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf("") }
    var sources by remember { mutableStateOf<List<String>>(emptyList()) }
    var check by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Ask a question about your own notes. Atlas answers only from them, numbers every source, then checks its own answer and searches wider if the first try looks weak.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(q, { q = it }, label = { Text("e.g. What did I decide about the Karachi trip?") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = q.isNotBlank() && !busy, onClick = {
            busy = true; answer = ""; sources = emptyList(); check = ""
            scope.launch {
                runCatching {
                    val notes = container.noteRepository.all().first()
                    var used = retrieve(notes, q, 5)
                    if (used.isEmpty()) { answer = "No note mentions that. Try other words, or write it down first."; return@runCatching }
                    suspend fun answerFrom(list: List<com.nizam.app.feature.notes.Note>): String = Ai.generate(container.settingsRepository,
                        "Sources:\n" + list.mapIndexed { i, n -> "[${i + 1}] ${n.title}: ${n.body.take(700)}" }.joinToString("\n\n") +
                            "\n\nQuestion: $q\nAnswer using ONLY these sources. Cite like [1]. If they do not contain the answer, say exactly what is missing.",
                        "You answer from the user's own notes and always cite.", 800)
                    var a = answerFrom(used)
                    var verdict = StructuredAi.obj(container, "Question: $q\nAnswer: $a\nSources: " + used.joinToString { it.title } +
                        "\nIs the answer fully supported by the sources? verdict is \"supported\" or \"weak\"; reason is one short sentence.", listOf("verdict", "reason"), tokens = 150)
                    if (verdict?.optString("verdict") == "weak") {
                        val wider = retrieve(notes, q, 10)
                        if (wider.size > used.size) { used = wider; a = answerFrom(used)
                            verdict = StructuredAi.obj(container, "Question: $q\nAnswer: $a\nIs the answer fully supported by the sources? verdict is \"supported\" or \"weak\"; reason is one short sentence.", listOf("verdict", "reason"), tokens = 150) }
                    }
                    answer = a; sources = used.mapIndexed { i, n -> "[${i + 1}] ${n.title}" }
                    check = if (verdict?.optString("verdict") == "supported") "✓ Self-check: supported by your notes" else "⚠ Self-check: only partly supported" + (verdict?.optString("reason")?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: "")
                }.onFailure { answer = "Couldn't answer: ${it.message}" }
                busy = false
            }
        }) { Text(if (busy) "Searching and checking…" else "Answer with sources") }
        if (answer.isNotBlank()) SelectionContainer { Text(answer, style = MaterialTheme.typography.bodyMedium) }
        if (check.isNotBlank()) Text(check, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        sources.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// ───────────────────────── Money coach (uses your real numbers) ─────────────────────────

@Composable
private fun MoneyCoachScreen(container: AppContainer) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var goal by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var out by remember { mutableStateOf("") }
    var facts by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("A coach that reads your logged income and spending for the last 30 days, finds the leaks and gives you a 30-day plan.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(goal, { goal = it }, label = { Text("Your goal (optional), e.g. save Rs 50,000 by December") }, modifier = Modifier.fillMaxWidth())
        Button(enabled = !busy, onClick = {
            busy = true; out = ""
            scope.launch {
                runCatching {
                    val d = Dash.load(container)
                    val from = LocalDate.now().minusDays(30).toString()
                    val m = d.txns.filter { it.localDate >= from }
                    val spent = m.filter { it.kind == "EXPENSE" }.sumOf { it.amount }; val income = m.filter { it.kind == "INCOME" }.sumOf { it.amount }
                    val cats = m.filter { it.kind == "EXPENSE" }.groupBy { it.category }.mapValues { e -> e.value.sumOf { t -> t.amount } }.entries.sortedByDescending { it.value }.take(6)
                    facts = "Last 30 days: income Rs ${income.toLong()}, spent Rs ${spent.toLong()}, net Rs ${(income - spent).toLong()}. Top categories: " + cats.joinToString { "${it.key} Rs ${it.value.toLong()}" }
                    if (m.isEmpty()) { out = "No transactions logged in the last 30 days. Log a few expenses first and the coach will have something to work with."; return@runCatching }
                    out = Ai.generate(container.settingsRepository, "$facts\nGoal: ${goal.ifBlank { "none stated" }}\nGive: 3 things going well, 3 leaks with the rupee amount at stake, a concrete 30-day plan week by week, and ONE number to watch. Short, practical, no lecture. Mention zakat and riba only if relevant.",
                        "You are a practical, kind personal finance coach for someone in Pakistan. Use only the figures given.", 1000)
                }.onFailure { out = "Couldn't coach right now: ${it.message}" }
                busy = false
            }
        }) { Text(if (busy) "Reading your numbers…" else "Coach me") }
        if (facts.isNotBlank()) Card { Text(facts, style = MaterialTheme.typography.bodySmall) }
        if (out.isNotBlank()) SelectionContainer { Text(out, style = MaterialTheme.typography.bodyMedium) }
    }
}

// ───────────────────────── Kanban board ─────────────────────────

@Composable
private fun KanbanScreen() {
    val c = LocalContext.current
    val store = remember { JsonStore(c, "kanban") }
    var tick by remember { mutableStateOf(0) }
    var title by remember { mutableStateOf("") }
    val cols = listOf("To do", "Doing", "Done")
    val cards = remember(tick) { store.all().sortedBy { it.optLong("t") } }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("New card") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(enabled = title.isNotBlank(), onClick = { store.add(JSONObject().put("t", System.currentTimeMillis()).put("title", title.trim()).put("col", 0)); title = ""; tick++ }) { Text("Add") }
        }
        cols.forEachIndexed { ci, name ->
            val here = cards.filter { it.optInt("col") == ci }
            Text("$name (${here.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 6.dp))
            if (here.isEmpty()) Text("Empty", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            here.forEach { card ->
                Card { Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(card.optString("title"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    if (ci > 0) TextButton(onClick = { card.put("col", ci - 1); store.save(store.all().map { if (it.optString("id") == card.optString("id")) card else it }); tick++ }) { Text("◀") }
                    if (ci < 2) TextButton(onClick = { card.put("col", ci + 1); store.save(store.all().map { if (it.optString("id") == card.optString("id")) card else it }); tick++ }) { Text("▶") }
                    TextButton(onClick = { store.remove(card.optString("id")); tick++ }) { Text("✕") }
                } }
            }
        }
    }
}

// ───────────────────────── The registry ─────────────────────────

private fun team(role: String) = "You are a panel of specialists answering together. Label each voice clearly. $role Be honest, specific and practical. Not a substitute for a professional where one is needed."

object LlmAppsSpecs {
    val all: List<com.nizam.app.feature.nx.Spec> = listOf(
        ScreenSpec("askdata", "Ask my data", "Open a CSV or spreadsheet and ask questions in plain words; sums are exact", Icons.Outlined.TableChart, Hub.Work,
            "csv excel spreadsheet data analysis ask questions numbers") { c, _ -> AskDataScreen(c) },
        ScreenSpec("webchat", "Chat with a web page", "Load any link and ask questions answered only from that page", Icons.Outlined.Public, Hub.Learn,
            "web page article url chat summarise extract scrape") { c, _ -> WebChatScreen(c) },
        ScreenSpec("papers", "Research papers", "Search the newest arXiv papers and have one explained simply", Icons.Outlined.Science, Hub.Learn,
            "arxiv research papers science ai explain") { c, _ -> PapersScreen(c) },
        ScreenSpec("citedanswer", "Cited answers from my notes", "Answers from your own notes with numbered sources and a self-check", Icons.Outlined.FactCheck, Hub.Memory,
            "notes rag retrieval cited sources answer search verify") { c, _ -> CitedAnswerScreen(c) },
        ScreenSpec("moneycoach", "Money coach", "Reads your last 30 days and gives leaks, wins and a 30-day plan", Icons.Outlined.Savings, Hub.Money,
            "financial coach budget savings leaks plan advice") { c, _ -> MoneyCoachScreen(c) },
        ScreenSpec("chainlog", "Tamper-evident agent log", "Every agent action sealed in a hash chain; edits or deletions are detected", Icons.Outlined.VerifiedUser, Hub.Tools,
            "audit hash chain tamper log verify trust agents") { _, _ -> ChainLogScreen() },
        ScreenSpec("kanban", "Board", "Plain To do, Doing, Done cards", Icons.Outlined.ViewColumn, Hub.Work,
            "kanban board cards todo doing done project") { _, _ -> KanbanScreen() },

        AiSpec("heartbreak", "Heartbreak support team", "A listener, a reframer, a friend and a planner each answer you", Icons.Outlined.Favorite, Hub.Health, "breakup heartbreak relationship support sad recovery",
            listOf(F("story", "What happened", K.LONG), F("when", "How long ago", default = "recently"), F("faith", "Include a faith perspective", K.CHOICE, options = listOf("Yes, gently", "No"), default = "Yes, gently")),
            team("Voices: The Listener (validates feelings), The Reframer (healthy perspective), The Friend (warm, human), The Planner (a 7-day recovery routine). If there is any sign of self-harm, put urgent real-world help first and encourage reaching a trusted person or local helpline.")) { v ->
            "Situation: ${v["story"]}\nHow long ago: ${v["when"]}\nFaith perspective: ${v["faith"]}" },
        AiSpec("meme", "Meme maker", "Caption ideas in classic meme formats, ready to paste on an image", Icons.Outlined.EmojiEmotions, Hub.Create, "meme caption funny joke template",
            listOf(F("topic", "What is it about"), F("style", "Style", K.CHOICE, options = listOf("Desi humour", "Office", "Student life", "Dry/sarcastic"), default = "Desi humour")),
            "You write funny, clean, shareable memes. Never mock religion, ethnicity or a real person's tragedy.") { v ->
            "Give 8 meme ideas about: ${v["topic"]}. Style: ${v["style"]}. For each: a well-known template name, top text, bottom text." },
        AiSpec("stockreport", "Stock comparison report", "Side-by-side view of companies with risks, and a halal-screen note", Icons.Outlined.ShowChart, Hub.Money, "stock compare investment report psx shares analysis",
            listOf(F("names", "Companies or tickers (2 to 4)"), F("horizon", "Your horizon", K.CHOICE, options = listOf("1 year", "3 to 5 years", "10+ years"), default = "3 to 5 years")),
            "You are a careful investment analyst. Use current public information. Compare fundamentals, valuation, risks and catalysts. State uncertainty. This is information, not advice.", web = true, tokens = 2200) { v ->
            "Compare: ${v["names"]}. Horizon: ${v["horizon"]}. Include a table, key risks, a Shariah-screening note to verify with a scholar-approved screen, and what would change your mind." },
        AiSpec("redesign", "Room redesign advisor", "A budgeted plan to fix or restyle a room, with DIY versus contractor", Icons.Outlined.Home, Hub.Home, "renovation interior design room makeover budget",
            listOf(F("room", "Which room and what bothers you", K.LONG), F("budget", "Budget (Rs)", K.NUMBER), F("style", "Style you like", optional = true)),
            "You are an interior designer and site supervisor in Pakistan. Give realistic local material costs and phase the work.") { v ->
            "Room: ${v["room"]}\nBudget: Rs ${v["budget"]}\nStyle: ${v["style"]}\nGive: a plan in phases, cost per phase, what to DIY, what needs a contractor, materials and where to look, and mistakes to avoid." },
        AiSpec("diligence", "Startup due diligence", "A team of analysts checks market, product, money, risks and gives a verdict", Icons.Outlined.Apartment, Hub.Work, "due diligence startup investor vc analysis invest",
            listOf(F("co", "Company or idea", K.LONG), F("stage", "Stage", K.CHOICE, options = listOf("Idea", "Early revenue", "Growing"), default = "Idea")),
            team("Voices: Market analyst, Product analyst, Financial analyst, Risk analyst, then a Partner who gives a verdict (invest, watch, pass) with conditions."), web = true, tokens = 2400) { v ->
            "Evaluate: ${v["co"]}. Stage: ${v["stage"]}." },
        AiSpec("consultant", "Business consultant", "Market view, options and a recommendation for a real business question", Icons.Outlined.Business, Hub.Work, "consultant strategy business market recommendation",
            listOf(F("biz", "Your business", K.LONG), F("q", "The decision or problem", K.LONG)),
            "You are a senior management consultant. Structure the answer: situation, options with trade-offs, recommendation, first 30 days, risks. Use current market data.", web = true, tokens = 2200) { v ->
            "Business: ${v["biz"]}\nQuestion: ${v["q"]}" },
        AiSpec("shortfilm", "Short film maker", "Logline, script outline, cast and a shot list from one idea", Icons.Outlined.Movie, Hub.Create, "film movie script screenplay cast shot list",
            listOf(F("idea", "Your idea", K.LONG), F("len", "Length", K.CHOICE, options = listOf("2 minutes", "5 minutes", "10 minutes"), default = "5 minutes")),
            "You are a screenwriter and director. Keep it shootable on a phone with a small cast.", tokens = 2200) { v ->
            "Idea: ${v["idea"]}. Length: ${v["len"]}. Give a logline, 3-act outline, the key scenes with dialogue samples, cast descriptions, locations, and a shot list." },
        AiSpec("teardown", "Competitor teardown", "How a rival makes money, where it is weak, and how to beat it", Icons.Outlined.Compare, Hub.Work, "competitor analysis teardown market rival battle card",
            listOf(F("rival", "Competitor"), F("mine", "Your product", K.LONG)),
            "You are a competitive intelligence analyst. Use current public information and say when a claim is unverified.", web = true, tokens = 2000) { v ->
            "Competitor: ${v["rival"]}\nMy product: ${v["mine"]}\nGive: their model and pricing, strengths, weak spots, how customers talk about them, and a one-page battle card for my sales pitch." },
        AiSpec("contractreview", "Contract reviewer", "Plain-language summary of a contract, risky clauses and questions to ask", Icons.Outlined.Gavel, Hub.Work, "contract agreement legal review clauses lease terms",
            listOf(F("role", "You are the", K.CHOICE, options = listOf("Tenant", "Landlord", "Employee", "Employer", "Client", "Contractor", "Buyer", "Seller"), default = "Tenant"), F("text", "Paste the contract", K.LONG)),
            "You are a careful contracts reviewer. Not a lawyer; say so briefly at the end. Never invent clauses.", tokens = 2200) { v ->
            "My role: ${v["role"]}\nContract:\n${v["text"]?.take(14000)}\n\nGive: a 5-line plain summary, the risky or one-sided clauses quoted and explained, anything missing that is normally included, and 8 questions to ask a lawyer before signing." },
        AiSpec("buyrent", "Buy or rent advisor", "Property decision with the real numbers worked through", Icons.Outlined.Apartment, Hub.Money, "property buy rent house plot real estate decision",
            listOf(F("city", "City and area"), F("price", "Property price (Rs)", K.NUMBER), F("rent", "Monthly rent for something similar (Rs)", K.NUMBER), F("cash", "Cash you could put down (Rs)", K.NUMBER), F("stay", "Years you will stay", K.NUMBER, default = "5")),
            "You are a property advisor in Pakistan. Work through buy-versus-rent with the figures given, including costs of buying, opportunity cost of cash and likely appreciation as a range. Avoid riba-based advice unless asked.", web = true, tokens = 1800) { v ->
            "City: ${v["city"]}. Price Rs ${v["price"]}. Rent Rs ${v["rent"]}/month. Cash Rs ${v["cash"]}. Stay ${v["stay"]} years." },
        AiSpec("scoper", "Project scoper", "Turn a client brief into scope, assumptions, milestones and a price range", Icons.Outlined.Description, Hub.Work, "project scope proposal client freelance estimate milestones",
            listOf(F("brief", "Client brief", K.LONG), F("rate", "Your hourly rate (Rs)", K.NUMBER, optional = true)),
            "You are a project manager for a small agency. Be explicit about what is NOT included.") { v ->
            "Brief: ${v["brief"]}\nHourly rate: ${v["rate"]}\nGive: scope, exclusions, assumptions, milestones with hours, a price range, risks, and 5 questions to ask the client." },
        AiSpec("gamedesign", "Game concept studio", "Concept, core loop, art direction and a first prototype plan", Icons.Outlined.SportsEsports, Hub.Create, "game design concept mechanics prototype indie",
            listOf(F("idea", "Game idea", K.LONG), F("platform", "Platform", K.CHOICE, options = listOf("Phone", "PC", "Browser"), default = "Phone")),
            team("Voices: Game designer, Artist, Level designer, Producer.")) { v -> "Idea: ${v["idea"]}. Platform: ${v["platform"]}. Give a one-page concept, core loop, 5 mechanics, art direction, monetisation that is fair, and a 2-week prototype plan." },
        AiSpec("critique", "Design critique panel", "Three reviewers critique your screen, poster or page from a description", Icons.Outlined.Brush, Hub.Create, "design critique ux ui feedback landing page poster",
            listOf(F("what", "Describe the design", K.LONG), F("goal", "What it must achieve")),
            team("Voices: UX researcher, Visual designer, Conversion specialist. End with the top 3 fixes in order.")) { v -> "Design: ${v["what"]}\nGoal: ${v["goal"]}" },
        AiSpec("graveyard", "Project graveyard", "Decide which abandoned projects to revive, merge or bury", Icons.Outlined.Replay, Hub.Work, "abandoned projects revive side projects unfinished",
            listOf(F("list", "List your unfinished projects, one per line, with a note each", K.LONG), F("time", "Hours per week you really have", K.NUMBER, default = "5")),
            "You are a blunt but kind coach. Rank by payoff versus effort. Recommend burying at least one.") { v ->
            "Projects:\n${v["list"]}\nHours per week: ${v["time"]}\nFor each: revive, merge, pause or bury, why, and the smallest next step. Then say which ONE to do this month." },
        AiSpec("firstreader", "First reader", "Where a reader would get bored, confused or stop reading your draft", Icons.Outlined.Visibility, Hub.Create, "draft reader feedback writing edit engagement",
            listOf(F("audience", "Who will read it"), F("draft", "Paste your draft", K.LONG)),
            "You simulate a real first reader. Quote the exact lines where attention drops. Do not rewrite the whole piece.", tokens = 1600) { v ->
            "Audience: ${v["audience"]}\nDraft:\n${v["draft"]?.take(12000)}\nGive: the strongest 2 moments, the exact places a reader would drift or stop (quoted) with why, anything confusing, and 3 specific edits." },
        AiSpec("scopecheck", "Scope-creep check", "Compare new requests against the original brief and see what is extra", Icons.Outlined.Feedback, Hub.Work, "scope creep change request brief extra work client",
            listOf(F("orig", "Original brief or agreement", K.LONG), F("new", "New requests so far", K.LONG)),
            "You protect a freelancer or team from unpaid extras while staying polite.") { v ->
            "Original:\n${v["orig"]}\nNew requests:\n${v["new"]}\nClassify each request as in scope, grey area or out of scope, estimate the extra effort, and draft a short polite message proposing a change order." },
        AiSpec("screener", "Candidate screener", "Score resumes against a job description and suggest interview questions", Icons.Outlined.Engineering, Hub.Work, "hiring recruit resume cv screen interview candidates",
            listOf(F("job", "Job description", K.LONG), F("cvs", "Paste one or more resumes, separated by ---", K.LONG)),
            "You are a fair recruiter. Judge only job-relevant evidence. Ignore name, gender, age, religion and photo.", tokens = 2200) { v ->
            "Job:\n${v["job"]}\nCandidates:\n${v["cvs"]?.take(14000)}\nFor each: a score out of 10 with evidence, gaps, and 3 interview questions. Then rank them." },
    )
}
