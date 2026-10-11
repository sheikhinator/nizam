package com.nizam.app.feature.nx

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Biotech
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.bots.Bot
import com.nizam.app.feature.bots.Bots
import com.nizam.app.net.Ai
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * The AI lab: give bots reference packs and tool limits, fork a bot's thread, ask two bots the same
 * thing side by side, chain agents into pipelines with acceptance checks and output contracts, keep a
 * prompt regression suite, see the AI operations floor, and keep Atlas's memory honest.
 */
object AiLab {
    private fun p(c: Context) = c.getSharedPreferences("ai_lab", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- bot extras

    fun pack(c: Context, botId: Long) = p(c).getString("pack_$botId", "").orEmpty()
    fun setPack(c: Context, botId: Long, text: String) = p(c).edit().putString("pack_$botId", text.take(12_000)).apply()
    /** "all", "read" (writes need approval) or "none" (talk only). */
    fun tools(c: Context, botId: Long) = p(c).getString("tools_$botId", "all").orEmpty()
    fun setTools(c: Context, botId: Long, v: String) = p(c).edit().putString("tools_$botId", v).apply()

    fun fork(c: Context, bot: Bot): Bot {
        val id = System.currentTimeMillis()
        val copy = bot.copy(id = id, name = bot.name + " (fork)", lastUsed = id)
        Bots.save(c, Bots.all(c) + copy)
        Bots.turns(c, bot.id).forEach { Bots.addTurn(c, id, it.fromUser, it.text) }
        setPack(c, id, pack(c, bot.id)); setTools(c, id, tools(c, bot.id))
        return copy
    }

    // ---------------------------------------------------------------- pipelines

    data class Step(val role: String, val instruction: String, val mustInclude: List<String>, val jsonKeys: List<String>)
    data class Pipeline(val name: String, val steps: List<Step>)

    fun pipelines(c: Context): List<Pipeline> = runCatching {
        val a = JSONArray(p(c).getString("pipelines", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val s = o.getJSONArray("steps")
            Pipeline(o.optString("name"), (0 until s.length()).map { s.getJSONObject(it) }.map { x ->
                Step(x.optString("role"), x.optString("ins"), x.optString("must").split(',').map { it.trim() }.filter { it.isNotEmpty() },
                    x.optString("keys").split(',').map { it.trim() }.filter { it.isNotEmpty() }) })
        }
    }.getOrDefault(emptyList())

    fun savePipelines(c: Context, list: List<Pipeline>) = p(c).edit().putString("pipelines", JSONArray(list.map { pl ->
        JSONObject().put("name", pl.name).put("steps", JSONArray(pl.steps.map {
            JSONObject().put("role", it.role).put("ins", it.instruction).put("must", it.mustInclude.joinToString(",")).put("keys", it.jsonKeys.joinToString(",")) }))
    }).toString()).apply()

    /** Checks a step's output against its acceptance test and output contract. Empty list = passed. */
    fun check(step: Step, out: String): List<String> {
        val problems = mutableListOf<String>()
        step.mustInclude.filter { !out.contains(it, true) }.forEach { problems += "missing \"$it\"" }
        if (step.jsonKeys.isNotEmpty()) {
            val o = runCatching { JSONObject(out.substring(out.indexOf('{'), out.lastIndexOf('}') + 1)) }.getOrNull()
            if (o == null) problems += "not valid JSON" else step.jsonKeys.filter { !o.has(it) }.forEach { problems += "JSON lacks \"$it\"" }
        }
        return problems
    }

    suspend fun run(container: AppContainer, pl: Pipeline, input: String, onStep: (Int, String, List<String>) -> Unit): String {
        var carry = input
        pl.steps.forEachIndexed { i, step ->
            val sys = "You are the ${step.role}. ${step.instruction}" +
                (if (step.jsonKeys.isNotEmpty()) " Reply ONLY with a JSON object with keys: ${step.jsonKeys.joinToString()}." else "")
            var out = Ai.generate(container.settingsRepository, "Input from the previous stage:\n$carry", sys, 2000)
            var problems = check(step, out)
            if (problems.isNotEmpty()) {     // one retry with the failures spelled out
                out = Ai.generate(container.settingsRepository, "Input:\n$carry\n\nYour previous answer failed these checks: ${problems.joinToString()}. Fix them.\n\nPrevious answer:\n$out", sys, 2000)
                problems = check(step, out)
            }
            onStep(i, out, problems)
            carry = out
        }
        return carry
    }

    // ---------------------------------------------------------------- regression lab

    data class Case(val prompt: String, val expect: List<String>, val history: String)

    fun cases(c: Context): List<Case> = runCatching {
        val a = JSONArray(p(c).getString("cases", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { Case(it.optString("p"), it.optString("e").split(',').map { s -> s.trim() }.filter { s -> s.isNotEmpty() }, it.optString("h")) }
    }.getOrDefault(emptyList())

    fun saveCases(c: Context, list: List<Case>) = p(c).edit().putString("cases",
        JSONArray(list.map { JSONObject().put("p", it.prompt).put("e", it.expect.joinToString(",")).put("h", it.history.takeLast(60)) }).toString()).apply()

    suspend fun runSuite(container: AppContainer): List<Pair<Case, Boolean>> = coroutineScope {
        val c = container.appContext
        val res = cases(c).map { k -> async {
            val out = runCatching { Ai.generate(container.settingsRepository, k.prompt, null, 600) }.getOrDefault("")
            k to (out.isNotBlank() && k.expect.all { out.contains(it, true) })
        } }.awaitAll()
        saveCases(c, res.map { (k, ok) -> k.copy(history = k.history + if (ok) "✓" else "✕") })
        res
    }

    // ---------------------------------------------------------------- memory honesty

    fun reviewOn(c: Context, noteId: Long) = p(c).getString("rev_$noteId", "").orEmpty()
    fun setReview(c: Context, noteId: Long, date: String) = p(c).edit().putString("rev_$noteId", date).apply()

    suspend fun conflicts(container: AppContainer, notes: List<Pair<Long, String>>): List<String> {
        if (notes.size < 2) return emptyList()
        val raw = Ai.generate(container.settingsRepository,
            notes.joinToString("\n") { "${it.first}: ${it.second.take(200)}" } +
                "\n\nList pairs of these memory notes that contradict each other or one that is clearly outdated by another. One per line: <id> vs <id> — <why>. If none, reply NONE.",
            "You audit a personal assistant's memory for contradictions. Be precise; don't invent conflicts.", 600)
        return if (raw.trim().uppercase().startsWith("NONE")) emptyList() else raw.lines().map { it.trim() }.filter { it.contains(" vs ") }
    }
}

object AiLabSpecs {
    val all: List<Spec> = listOf(
        ScreenSpec("ailab", "AI lab", "Bot packs and tool limits, fork & compare, pipelines with checks, regression tests, ops floor, memory audit",
            Icons.Outlined.Biotech, Hub.Tools, "bot reference pack tool allowlist fork compare pipeline handoff acceptance test contract json regression prompts ai ops tokens memory conflict review") { c, _ -> AiLabScreen(c) }
    )
}

@Composable
private fun AiLabScreen(container: AppContainer) {
    var tab by remember { mutableStateOf("Bots") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Bots", "Compare", "Pipelines", "Tests", "Ops", "Memory").forEach { t -> FilterChip(selected = tab == t, onClick = { tab = t }, label = { Text(t) }) }
        }
        when (tab) {
            "Bots" -> BotsPane()
            "Compare" -> ComparePane(container)
            "Pipelines" -> PipelinesPane(container)
            "Tests" -> TestsPane(container)
            "Ops" -> OpsPane()
            else -> MemoryPane(container)
        }
    }
}

@Composable
private fun BotsPane() {
    val c = LocalContext.current
    var bots by remember { mutableStateOf(Bots.all(c)) }
    var sel by remember { mutableStateOf(bots.firstOrNull()) }
    if (bots.isEmpty()) { Text("Create a bot in Atlas → Bots first."); return }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { bots.take(5).forEach { b -> FilterChip(selected = sel?.id == b.id, onClick = { sel = b }, label = { Text("${b.emoji} ${b.name}".take(16)) }) } }
    val b = sel ?: return
    var pack by remember(b.id) { mutableStateOf(AiLab.pack(c, b.id)) }
    var tools by remember(b.id) { mutableStateOf(AiLab.tools(c, b.id)) }
    Text("Reference pack", style = MaterialTheme.typography.titleSmall)
    Text("Facts, rules and documents this bot always has in mind.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(pack, { pack = it }, Modifier.fillMaxWidth(), minLines = 4, maxLines = 10)
    Text("What it may do", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("all" to "Use tools", "read" to "Ask before changes", "none" to "Talk only").forEach { (k, l) -> FilterChip(selected = tools == k, onClick = { tools = k }, label = { Text(l) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { AiLab.setPack(c, b.id, pack); AiLab.setTools(c, b.id, tools); com.nizam.app.ui.components.Feedback.show("Saved for ${b.name}") }) { Text("Save") }
        OutlinedButton(onClick = { val f = AiLab.fork(c, b); bots = Bots.all(c); sel = f; com.nizam.app.ui.components.Feedback.show("Forked into ${f.name}") }) { Text("Fork this thread") }
    }
}

@Composable
private fun ComparePane(container: AppContainer) {
    val c = LocalContext.current
    val work = com.nizam.app.ui.design.rememberWorkScope()
    val bots = remember { Bots.all(c) }
    var a by remember { mutableStateOf(bots.getOrNull(0)) }
    var b by remember { mutableStateOf(bots.getOrNull(1)) }
    var q by remember { mutableStateOf("") }
    var answers by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    if (bots.size < 2) { Text("You need two bots (fork one in the Bots tab)."); return }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Text("A:"); bots.take(4).forEach { x -> FilterChip(selected = a?.id == x.id, onClick = { a = x }, label = { Text(x.name.take(12)) }) } }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { Text("B:"); bots.take(4).forEach { x -> FilterChip(selected = b?.id == x.id, onClick = { b = x }, label = { Text(x.name.take(12)) }) } }
    OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Ask both the same thing") })
    Button(onClick = {
        val x = a ?: return@Button; val y = b ?: return@Button; busy = true
        work.launch {
            answers = coroutineScope {
                val r1 = async { runCatching { Ai.generate(container.settingsRepository, Bots.contextFor(c, x) + "\n\n" + AiLab.pack(c, x.id) + "\n\nThem: $q", null, 900) }.getOrElse { it.message.orEmpty() } }
                val r2 = async { runCatching { Ai.generate(container.settingsRepository, Bots.contextFor(c, y) + "\n\n" + AiLab.pack(c, y.id) + "\n\nThem: $q", null, 900) }.getOrElse { it.message.orEmpty() } }
                r1.await() to r2.await()
            }
            busy = false
        }
    }, enabled = q.isNotBlank() && !busy && a != null && b != null && a?.id != b?.id) { Text(if (busy) "Asking both…" else "Compare") }
    answers?.let { (x, y) ->
        Text("${a?.name}", style = MaterialTheme.typography.titleSmall); Text(x, style = MaterialTheme.typography.bodyMedium)
        Text("${b?.name}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)); Text(y, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PipelinesPane(container: AppContainer) {
    val c = LocalContext.current
    val work = com.nizam.app.ui.design.rememberWorkScope()
    var list by remember { mutableStateOf(AiLab.pipelines(c).ifEmpty {
        listOf(AiLab.Pipeline("Research → write → review", listOf(
            AiLab.Step("Researcher", "List the key facts, numbers and open questions about the input.", emptyList(), emptyList()),
            AiLab.Step("Writer", "Write a clear, well-structured piece from the research.", emptyList(), emptyList()),
            AiLab.Step("Reviewer", "Check the piece for errors and return the corrected final version.", emptyList(), emptyList())))) }) }
    var sel by remember { mutableIntStateOf(0) }
    var input by remember { mutableStateOf("") }
    var log by remember { mutableStateOf<List<Triple<Int, String, List<String>>>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var newRole by remember { mutableStateOf("") }; var newIns by remember { mutableStateOf("") }; var newMust by remember { mutableStateOf("") }; var newKeys by remember { mutableStateOf("") }
    val pl = list.getOrNull(sel) ?: return
    Text(pl.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    pl.steps.forEachIndexed { i, s -> Text("${i + 1}. ${s.role}${if (s.mustInclude.isNotEmpty()) " · must include ${s.mustInclude}" else ""}${if (s.jsonKeys.isNotEmpty()) " · JSON ${s.jsonKeys}" else ""}", style = MaterialTheme.typography.bodySmall) }
    OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), placeholder = { Text("Input for the first agent") }, minLines = 2)
    Button(onClick = { busy = true; log = emptyList(); work.launch { runCatching { AiLab.run(container, pl, input) { i, out, pr -> log = log + Triple(i, out, pr) } }; busy = false } },
        enabled = input.isNotBlank() && !busy) { Text(if (busy) "Running step ${log.size + 1}…" else "Run the pipeline") }
    log.forEach { (i, out, pr) ->
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
            Text("${pl.steps[i].role} ${if (pr.isEmpty()) "✓ passed" else "⚠ ${pr.joinToString()}"}", style = MaterialTheme.typography.labelLarge)
            Text(out.take(1500), style = MaterialTheme.typography.bodySmall)
        }
    }
    Text("Add a step", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    OutlinedTextField(newRole, { newRole = it }, Modifier.fillMaxWidth(), placeholder = { Text("Role, e.g. Translator") }, singleLine = true)
    OutlinedTextField(newIns, { newIns = it }, Modifier.fillMaxWidth(), placeholder = { Text("What this step does") })
    OutlinedTextField(newMust, { newMust = it }, Modifier.fillMaxWidth(), placeholder = { Text("Acceptance test: words it must include (comma separated)") }, singleLine = true)
    OutlinedTextField(newKeys, { newKeys = it }, Modifier.fillMaxWidth(), placeholder = { Text("Output contract: JSON keys (optional)") }, singleLine = true)
    OutlinedButton(onClick = {
        list = list.mapIndexed { i, x -> if (i != sel) x else x.copy(steps = x.steps + AiLab.Step(newRole, newIns, newMust.split(',').map { it.trim() }.filter { it.isNotEmpty() }, newKeys.split(',').map { it.trim() }.filter { it.isNotEmpty() })) }
        AiLab.savePipelines(c, list); newRole = ""; newIns = ""; newMust = ""; newKeys = ""
    }, enabled = newRole.isNotBlank() && newIns.isNotBlank()) { Text("Add step") }
}

@Composable
private fun TestsPane(container: AppContainer) {
    val c = LocalContext.current
    val work = com.nizam.app.ui.design.rememberWorkScope()
    var cases by remember { mutableStateOf(AiLab.cases(c)) }
    var prompt by remember { mutableStateOf("") }; var expect by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Text("Prompts you care about, with words a good answer must contain. Run them after changing models or keys to catch regressions.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(prompt, { prompt = it }, Modifier.fillMaxWidth(), placeholder = { Text("e.g. What is the zakat rate?") })
    OutlinedTextField(expect, { expect = it }, Modifier.fillMaxWidth(), placeholder = { Text("Must contain, e.g. 2.5") }, singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { cases = cases + AiLab.Case(prompt, expect.split(',').map { it.trim() }.filter { it.isNotEmpty() }, ""); AiLab.saveCases(c, cases); prompt = ""; expect = "" }, enabled = prompt.isNotBlank()) { Text("Add test") }
        Button(onClick = { busy = true; work.launch { AiLab.runSuite(container); cases = AiLab.cases(c); busy = false } }, enabled = cases.isNotEmpty() && !busy) { Text(if (busy) "Running…" else "Run all") }
    }
    cases.forEachIndexed { i, k ->
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(k.prompt.take(80), style = MaterialTheme.typography.bodyMedium)
                Text("expects ${k.expect} · history ${k.history.takeLast(12).ifBlank { "—" }}", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = { cases = cases.filterIndexed { j, _ -> j != i }; AiLab.saveCases(c, cases) }) { Text("✕") }
        }
    }
}

@Composable
private fun OpsPane() {
    val c = LocalContext.current
    val names = remember { com.nizam.app.feature.wild.AgentMeter.names(c).sorted() }
    val ppm = com.nizam.app.feature.wild.AgentMeter.pricePerMillion(c)
    Text("AI operations floor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    val total = names.sumOf { com.nizam.app.feature.wild.AgentMeter.week(c, it) }
    Text("This week: ${"%,d".format(total)} tokens${if (ppm > 0) " · about $${"%.2f".format(total / 1e6 * ppm)}" else ""}", style = MaterialTheme.typography.bodyLarge)
    names.sortedByDescending { com.nizam.app.feature.wild.AgentMeter.week(c, it) }.forEach { n ->
        val (t, r) = com.nizam.app.feature.wild.AgentMeter.today(c, n)
        val cap = com.nizam.app.feature.wild.AgentMeter.cap(c, n)
        Text("$n · today ${"%,d".format(t)} tokens, $r runs · week ${"%,d".format(com.nizam.app.feature.wild.AgentMeter.week(c, n))}${if (cap > 0) " · cap ${"%,d".format(cap)}/day" else ""}",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp))
    }
    if (names.isEmpty()) Text("No AI activity recorded yet.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun MemoryPane(container: AppContainer) {
    val c = LocalContext.current
    val work = com.nizam.app.ui.design.rememberWorkScope()
    val notes by container.agentMemory.notes().collectAsState(initial = emptyList())
    var conflicts by remember { mutableStateOf<List<String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    val today = LocalDate.now().toString()
    val due = notes.filter { val r = AiLab.reviewOn(c, it.id); r.isNotBlank() && r <= today }
    Text("What Atlas remembers: ${notes.size} notes", style = MaterialTheme.typography.titleMedium)
    Button(onClick = { busy = true; work.launch { conflicts = runCatching { AiLab.conflicts(container, notes.map { it.id to it.text }) }.getOrDefault(emptyList()); busy = false } }, enabled = notes.size > 1 && !busy) {
        Text(if (busy) "Checking…" else "Find contradictions")
    }
    conflicts?.let { list -> if (list.isEmpty()) Text("No conflicts found. ✓") else list.forEach { Text("⚠ $it", style = MaterialTheme.typography.bodySmall) } }
    if (due.isNotEmpty()) {
        Text("Due for a check", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        due.forEach { n -> Row(Modifier.fillMaxWidth()) {
            Text(n.text.take(100), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { AiLab.setReview(c, n.id, ""); tick++ }) { Text("Still true") }
            TextButton(onClick = { work.launch { container.agentMemory.forget(n) } }) { Text("Forget") }
        } }
    }
    Text("All notes · set a 'check again' date for anything that may change", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    notes.takeLast(40).reversed().forEach { n -> key(tick) {
        val r = AiLab.reviewOn(c, n.id)
        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
            Text("[${n.kind}] ${n.text.take(140)}", style = MaterialTheme.typography.bodySmall)
            Row {
                listOf(1L to "1 mo", 3L to "3 mo", 12L to "1 yr").forEach { (m, l) -> TextButton(onClick = { AiLab.setReview(c, n.id, LocalDate.now().plusMonths(m).toString()); tick++ }) { Text(l) } }
                if (r.isNotBlank()) Text("check on $r", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 14.dp))
                TextButton(onClick = { work.launch { container.agentMemory.forget(n) } }) { Text("Forget") }
            }
        }
    } }
}

@Composable
private fun key(k: Int, content: @Composable () -> Unit) = androidx.compose.runtime.key(k) { content() }
