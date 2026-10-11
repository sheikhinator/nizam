package com.nizam.app.feature.lab

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.agent.Snapshot
import com.nizam.app.feature.brain.DecisionStore
import com.nizam.app.feature.insight.DayVector
import com.nizam.app.feature.insight.Days
import com.nizam.app.feature.insight.METRICS
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.IsoFields

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) { content() } }
}

private fun Context_file(c: android.content.Context, n: String) = File(c.filesDir, n)

@Composable
fun LabScreen(container: AppContainer) {
    var tab by remember { mutableStateOf("Course") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Lab", "Courses, consequences, versions, bets and your constitution")
        ChipRow(listOf("Course", "Consequences", "Versions", "Bets", "Constitution"), tab, { tab = it })
        Spacer(Modifier.height(8.dp))
        when (tab) {
            "Course" -> CourseMaker(container)
            "Consequences" -> Consequences(container)
            "Versions" -> Versions(container)
            "Bets" -> Bets()
            else -> Constitution(container)
        }
        Spacer(Modifier.height(60.dp))
    }
}

// 1 ── Everything becomes a course ────────────────────────────────────────────
@Composable
private fun CourseMaker(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var topic by remember { mutableStateOf("") }
    var weeks by remember { mutableStateOf("4") }
    var out by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Card {
        Text("Turn anything into a course", style = MaterialTheme.typography.titleMedium)
        Text("A topic, a book, a skill — Atlas builds lessons, exercises and review questions, saves them to Notes, " +
            "and puts the first week's lessons in your Tasks.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(topic, { topic = it }, label = { Text("Topic, book or skill") }, modifier = Modifier.fillMaxWidth())
        ChipRow(listOf("2", "4", "8", "12"), weeks, { weeks = it }, labels = { "$it weeks" })
        Button(enabled = topic.isNotBlank() && !busy, onClick = {
            scope.launch {
                busy = true; Feedback.show("Designing the course…")
                runCatching {
                    val text = Ai.generate(container.settingsRepository,
                        "${Snapshot.of(container)}\n\nBuild a $weeks-week self-study course on: ${topic.trim()}.\n" +
                            "For each week: a clear objective, 3 lessons with what to study and why it matters, one practical " +
                            "exercise, and 3 review questions with answers. Fit it to roughly 3 hours a week. Plain text with " +
                            "WEEK headings. Be concrete — name real resources where you're confident they exist.",
                        maxOutputTokens = 3000)
                    container.noteRepository.createFull("Course: ${topic.trim()}", text)
                    text.lines().filter { Regex("^\\s*(Lesson|·|-)\\s").containsMatchIn(it) }.take(3)
                        .forEach { container.taskRepository.add("🎓 " + it.trim().trimStart('-', '·', ' ').take(80), null, 1) }
                    out = text; Feedback.show("Course saved to Notes · first lessons added to Tasks")
                }.onFailure { Feedback.failed("Course", it) }
                busy = false
            }
        }) { Text(if (busy) "Designing…" else "Build the course") }
    }
    out?.let { Card { Text(it, style = MaterialTheme.typography.bodyMedium) } }
}

// 2 ── Consequence graph: which past choices actually paid off ────────────────
@Composable
private fun Consequences(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var report by remember { mutableStateOf(c.getSharedPreferences("lab", 0).getString("consequences", "").orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    val decisions = remember { DecisionStore(c).all() }
    Card {
        Text("Consequence graph", style = MaterialTheme.typography.titleMedium)
        Text("${decisions.size} decisions recorded. Atlas traces each one forward through your data — what changed in the " +
            "weeks after, and whether it was worth it.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(enabled = decisions.isNotEmpty() && !busy, onClick = {
            scope.launch {
                busy = true
                runCatching {
                    val days = Days.build(container, c, 365)
                    fun window(from: LocalDate, to: LocalDate) = METRICS.keys.mapNotNull { k ->
                        days.filter { it.date in from..to }.mapNotNull { it.values[k] }.takeIf { it.isNotEmpty() }
                            ?.average()?.let { "${METRICS[k]}=${"%.1f".format(it)}" }
                    }.joinToString(", ")
                    val traced = decisions.takeLast(12).joinToString("\n") { d ->
                        val date = runCatching { LocalDate.parse(d.date) }.getOrNull() ?: return@joinToString ""
                        "${d.date}: ${d.decision}\n  before: ${window(date.minusDays(30), date)}\n  after: ${window(date, date.plusDays(30))}"
                    }
                    report = Ai.generate(container.settingsRepository,
                        "These are the user's decisions with their own measured data from the 30 days before and after each:\n$traced\n\n" +
                            "For each decision say plainly whether it paid off, stayed neutral, or cost them, citing the numbers. " +
                            "Then name the pattern: what kind of decision tends to work out for this person, and what kind doesn't. " +
                            "Be honest — if the data is too thin to judge one, say so.", maxOutputTokens = 1800)
                    c.getSharedPreferences("lab", 0).edit().putString("consequences", report).apply()
                }.onFailure { Feedback.failed("Consequence graph", it) }
                busy = false
            }
        }) { Text(if (busy) "Tracing…" else "Trace my decisions") }
    }
    if (report.isNotBlank()) Card { Text(report, style = MaterialTheme.typography.bodyMedium) }
}

// 3 ── Life version control: commits, branches and diffs ──────────────────────
@Composable
private fun Versions(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    val f = remember { Context_file(c, "commits.json") }
    var commits by remember { mutableStateOf(runCatching { JSONArray(f.readText()) }.getOrElse { JSONArray() }) }
    var message by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("main") }
    var days by remember { mutableStateOf<List<DayVector>>(emptyList()) }
    var diff by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { days = runCatching { Days.build(container, c, 200) }.getOrElse { emptyList() } }
    Card {
        Text("Commit a change", style = MaterialTheme.typography.titleMedium)
        Text("Every meaningful change to your life, recorded like a commit. Big shifts get their own branch name.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(message, { message = it }, label = { Text("What changed?") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(branch, { branch = it }, singleLine = true, label = { Text("Branch (main, new-job, cutting…)") },
            modifier = Modifier.fillMaxWidth())
        Button(enabled = message.isNotBlank(), onClick = {
            val a = JSONArray(commits.toString())
            a.put(JSONObject().put("date", LocalDate.now().toString()).put("msg", message.trim()).put("branch", branch.trim()))
            f.writeText(a.toString()); commits = a; message = ""; Feedback.show("Committed")
        }) { Text("Commit") }
    }
    Card {
        Text("Diff: this month vs last", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = {
            val thisM = YearMonth.now(); val lastM = thisM.minusMonths(1)
            fun avg(m: YearMonth, k: String) = days.filter { YearMonth.from(it.date) == m }.mapNotNull { it.values[k] }
                .takeIf { it.isNotEmpty() }?.average()
            diff = METRICS.keys.mapNotNull { k ->
                val a = avg(lastM, k); val b = avg(thisM, k)
                if (a == null || b == null || a == 0.0) null else {
                    val pct = (b - a) / kotlin.math.abs(a) * 100
                    if (kotlin.math.abs(pct) < 5) null
                    else "${if (pct > 0) "+" else ""}${"%.0f".format(pct)}%  ${METRICS[k]}  (${"%.1f".format(a)} > ${"%.1f".format(b)})"
                }
            }.sortedByDescending { kotlin.math.abs(it.substringBefore("%").trim().toDoubleOrNull() ?: 0.0) }
                .joinToString("\n").ifBlank { "Nothing moved more than 5% between the two months." }
        }) { Text("Show the diff") }
        diff?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
    }
    (0 until commits.length()).reversed().take(30).forEach { i ->
        commits.getJSONObject(i).let { o ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(o.optString("date").takeLast(5), style = com.nizam.app.ui.theme.MonoNumber,
                    color = MaterialTheme.colorScheme.primary, modifier = Modifier.width(48.dp))
                Column {
                    Text(o.optString("msg"), style = MaterialTheme.typography.bodyMedium)
                    Text(o.optString("branch"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

// 4 ── Attention futures: bet your hours, see the return ──────────────────────
@Composable
private fun Bets() {
    val c = LocalContext.current
    val f = remember { Context_file(c, "bets.json") }
    var bets by remember { mutableStateOf(runCatching { JSONArray(f.readText()) }.getOrElse { JSONArray() }) }
    var what by remember { mutableStateOf("") }
    var hours by remember { mutableStateOf("5") }
    val week = LocalDate.now().let { "${it.year}-W${it.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}" }
    fun save(a: JSONArray) { f.writeText(a.toString()); bets = JSONArray(a.toString()) }
    Card {
        Text("Bet your hours", style = MaterialTheme.typography.titleMedium)
        Text("At the start of the week, stake your hours on what you think will matter. At the end, rate the return. " +
            "Time becomes something you invest, not something that leaks.", style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(what, { what = it }, label = { Text("What are you betting on?") }, modifier = Modifier.fillMaxWidth())
        ChipRow(listOf("2", "5", "10", "20"), hours, { hours = it }, labels = { "${it}h" })
        Button(enabled = what.isNotBlank(), onClick = {
            val a = JSONArray(bets.toString())
            a.put(JSONObject().put("week", week).put("what", what.trim()).put("hours", hours.toInt()).put("return", -1))
            save(a); what = ""; Feedback.show("Bet placed")
        }) { Text("Place the bet") }
    }
    (0 until bets.length()).reversed().forEach { i ->
        val b = bets.getJSONObject(i)
        Card {
            Text("${b.optInt("hours")}h on ${b.optString("what")}", style = MaterialTheme.typography.titleMedium)
            Text(b.optString("week") + if (b.optInt("return") >= 0) " · returned ${b.optInt("return")}/5" else " · open",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (b.optInt("return") < 0) Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                (0..5).forEach { r ->
                    Text("$r", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable(role = Role.Button) {
                        val a = JSONArray(bets.toString()); a.getJSONObject(i).put("return", r); save(a)
                        Feedback.show(if (r <= 1) "Noted — that one didn't pay" else "Noted")
                    }.padding(6.dp))
                }
            }
        }
    }
    val settled = (0 until bets.length()).map { bets.getJSONObject(it) }.filter { it.optInt("return") >= 0 }
    if (settled.isNotEmpty()) Card {
        val hoursTotal = settled.sumOf { it.optInt("hours") }
        val weighted = settled.sumOf { it.optInt("hours") * it.optInt("return") }.toDouble() / hoursTotal.coerceAtLeast(1)
        Text("Portfolio: ${hoursTotal}h staked · average return ${"%.1f".format(weighted)}/5",
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        settled.filter { it.optInt("return") <= 1 }.take(3).forEach {
            Text("Poor return: ${it.optString("what")} (${it.optInt("hours")}h)", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// 5 ── Personal constitution, drafted from behaviour rather than intention ────
@Composable
private fun Constitution(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var text by remember { mutableStateOf(c.getSharedPreferences("lab", 0).getString("constitution", "").orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    Card {
        Text("Your constitution", style = MaterialTheme.typography.titleMedium)
        Text("Not what you say you value — what your own data says you do. Drafted from your logs, decisions and " +
            "follow-through. It can be uncomfortable reading; that's rather the point.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(enabled = !busy, onClick = {
            scope.launch {
                busy = true
                runCatching {
                    val days = Days.build(container, c, 180)
                    val evidence = METRICS.keys.mapNotNull { k ->
                        val v = days.mapNotNull { it.values[k] }
                        if (v.isEmpty()) null else "${METRICS[k]}: logged ${v.size} days, average ${"%.1f".format(v.average())}"
                    }.joinToString("; ")
                    text = Ai.generate(container.settingsRepository,
                        "${Snapshot.of(container)}\nSix months of evidence: $evidence\n\n" +
                            "Write this person's personal constitution in their own voice: 5-7 articles describing what they " +
                            "ACTUALLY prioritise, tolerate and abandon, judged from the evidence rather than their stated goals. " +
                            "Name the gap between the two where it exists. Direct, specific, never cruel, and end with one " +
                            "article about what they should protect above everything. Under 400 words.", maxOutputTokens = 1200)
                    c.getSharedPreferences("lab", 0).edit().putString("constitution", text).apply()
                    container.noteRepository.createFull("My constitution · ${LocalDate.now()}", text)
                    Feedback.show("Drafted and saved to Notes")
                }.onFailure { Feedback.failed("Constitution", it) }
                busy = false
            }
        }) { Text(if (busy) "Reading six months…" else if (text.isBlank()) "Draft it" else "Redraft for this quarter") }
    }
    if (text.isNotBlank()) Card { Text(text, style = MaterialTheme.typography.bodyLarge) }
}
