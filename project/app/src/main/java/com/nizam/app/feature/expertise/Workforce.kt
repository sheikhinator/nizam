package com.nizam.app.feature.expertise

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.agentic.AgentHalo
import com.nizam.app.feature.flows.Feeds
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.Md
import com.nizam.app.feature.nx.ResultActions
import com.nizam.app.feature.nx.Roads
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.net.Ai
import com.nizam.app.net.Router
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.StatTile
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ln

/** Who worked with whom, how they scored and who won: the record Society reads. */
object Record {
    fun runs(c: Context) = JsonStore(c, "mind_runs")
    fun scores(c: Context) = JsonStore(c, "mind_scores")

    fun saveRun(c: Context, kind: String, question: String, experts: List<Expertise.Expert>, text: String) {
        runs(c).let { st -> st.save((st.all() + JSONObject().put("id", System.currentTimeMillis().toString()).put("kind", kind).put("q", question)
            .put("experts", JSONArray(experts.map { it.id })).put("text", text).put("at", System.currentTimeMillis())).takeLast(80)) }
        Feeds.add(c, kind, "${question.take(80)} → ${text.replace('\n', ' ').take(240)}")
    }

    fun score(c: Context, expertId: String, kind: String, points: Int) {
        scores(c).add(JSONObject().put("expert", expertId).put("kind", kind).put("points", points).put("at", System.currentTimeMillis()))
    }

    fun points(c: Context): Map<String, Int> = scores(c).all().groupBy { it.optString("expert") }.mapValues { e -> e.value.sumOf { it.optInt("points") } }

    fun pairs(c: Context): Map<Pair<String, String>, Int> {
        val out = HashMap<Pair<String, String>, Int>()
        runs(c).all().forEach { r -> val ids = r.optJSONArray("experts")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty().sorted()
            for (i in ids.indices) for (j in i + 1 until ids.size) out.merge(ids[i] to ids[j], 1, Int::plus) }
        return out
    }
}

// ───────────────────────────── Swarm ─────────────────────────────

/** Swarm: several kinds of expertise answer at once, then Atlas weighs them into one answer. */
@Composable
fun SwarmScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var q by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(listOf("planner", "analyst", "compliance").mapNotNull { Expertise.find(ctx, it) }) }
    val answers = remember { mutableStateListOf<Pair<Expertise.Expert, String>>() }
    var synthesis by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("The question for the swarm") }, minLines = 2)
        OutlinedButton(onClick = { Expertise.pick(ctx, q)?.let { e -> if (e !in chosen && chosen.size < 6) chosen = chosen + e } }, enabled = q.length > 20) {
            Text("Add the best-fit expertise")
        }
        ExpertPicker(chosen, 6) { chosen = it }
        Button(onClick = {
            busy = true; answers.clear(); synthesis = null
            scope.launch {
                AgentHalo.begin("The swarm is thinking")
                runCatching {
                    val life = Roads.relevant(ctx, q, 2000)
                    val got = coroutineScope {
                        chosen.map { e -> async { e to runCatching { MindKit.ask(container, e,
                            "The user's recorded data, shared by them (use only if relevant):\n$life\n\nQuestion: $q\n\nAnswer from your expertise in under 180 words.", 500) }
                            .getOrElse { "(couldn't answer: ${it.message})" } } }.awaitAll()
                    }
                    answers.addAll(got)
                    AgentHalo.step("Weighing ${got.size} answers")
                    val s = MindKit.ask(container, MindKit.core(ctx, "planner"),
                        "Question: $q\n\n" + got.joinToString("\n\n") { (e, a) -> "### ${e.name}\n$a" } +
                            "\n\nSynthesise: where they agree, where they disagree (and who is more convincing), then one final answer and the next step.", 900)
                    synthesis = s
                    Record.saveRun(ctx, "Swarm", q, chosen, s)
                }.onFailure { Feedback.show(it.message ?: "The swarm couldn't finish") }
                AgentHalo.end(); busy = false
            }
        }, enabled = q.isNotBlank() && chosen.size >= 2 && !busy) { Text(if (busy) "Thinking together…" else "Ask ${chosen.size} at once") }
        synthesis?.let { Heading("One answer"); Group { Md(it) }; ResultActions("Swarm", it) }
        answers.forEach { (e, a) -> Group { Text(e.name, style = MaterialTheme.typography.titleSmall, color = e.hub.tint()); Md(a) } }
        AlsoHere(open, "Council" to "council", "Model debate" to "nx/debate", "Second opinion" to "nx/second", "Board of mentors" to "nx/board", "Decision court" to "court")
    }
}

// ───────────────────────────── Syndicate ─────────────────────────────

/** Syndicate: kinds of expertise consult each other in turns, and you can read every thread. */
@Composable
fun SyndicateScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var topic by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(listOf("research", "analyst").mapNotNull { Expertise.find(ctx, it) }) }
    val thread = remember { mutableStateListOf<Pair<String, String>>() }
    var busy by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    val past = remember(version) { Record.runs(ctx).all().filter { it.optString("kind") == "Syndicate" }.sortedByDescending { it.optLong("at") }.take(10) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        OutlinedTextField(topic, { topic = it }, Modifier.fillMaxWidth(), label = { Text("What should they work out together?") }, minLines = 2)
        ExpertPicker(chosen, 4) { chosen = it }
        Button(onClick = {
            busy = true; thread.clear()
            scope.launch {
                AgentHalo.begin("Experts are consulting")
                runCatching {
                    repeat(2) { round ->
                        for (e in chosen) {
                            AgentHalo.step("${e.name}, round ${round + 1}")
                            val so = thread.joinToString("\n\n") { "${it.first}: ${it.second}" }
                            val reply = MindKit.ask(container, e, "Topic: $topic\n\nThe consultation so far:\n${so.ifBlank { "(you speak first)" }}\n\n" +
                                "Add your view in under 120 words. Address the others by expertise name, build on or challenge their points, and ask one question if useful.", 350)
                            thread.add(e.name to reply)
                        }
                    }
                    val wrap = MindKit.ask(container, MindKit.core(ctx, "planner"), "Topic: $topic\n\n" + thread.joinToString("\n\n") { "${it.first}: ${it.second}" } +
                        "\n\nWrite the conclusion of this consultation: what was agreed, open questions, and what the user should do.", 600)
                    thread.add("Conclusion" to wrap)
                    Record.saveRun(ctx, "Syndicate", topic, chosen, thread.joinToString("\n\n") { "**${it.first}:** ${it.second}" })
                    version++
                }.onFailure { Feedback.show(it.message ?: "The consultation stopped") }
                AgentHalo.end(); busy = false
            }
        }, enabled = topic.isNotBlank() && chosen.size >= 2 && !busy) { Text(if (busy) "Consulting…" else "Start the consultation") }
        thread.forEach { (who, said) -> Group { Text(who, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary); Md(said) } }
        if (thread.isEmpty() && past.isNotEmpty()) {
            Heading("Earlier threads")
            past.forEach { r -> ListRow(r.optString("q"), icon = Icons.Outlined.Forum) {
                thread.clear(); thread.add("Thread" to r.optString("text")); topic = r.optString("q") } }
        }
        AlsoHere(open, "Agent teams" to "nx/crew", "Atlas HQ" to "nx/hq", "Group brain" to "nx/groupbrain")
    }
}

// ───────────────────────────── Nexus ─────────────────────────────

/** Nexus: Atlas plans a job, hands each step to the right expertise, and reports back; changes wait for approval. */
@Composable
fun NexusScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var goal by remember { mutableStateOf("") }
    val steps = remember { mutableStateListOf<Triple<String, String, String>>() }
    var report by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        OutlinedTextField(goal, { goal = it }, Modifier.fillMaxWidth(), label = { Text("A job for Atlas (a goal, a project, a problem)") }, minLines = 3)
        Button(onClick = {
            busy = true; steps.clear(); report = null
            scope.launch {
                AgentHalo.begin("Nexus is planning")
                runCatching {
                    val life = Roads.relevant(ctx, goal, 2500)
                    val plan = MindKit.json(MindKit.ask(container, MindKit.core(ctx, "planner"),
                        "Job: $goal\n\nThe user's recorded data, shared by them:\n$life\n\nExpertise available:\n${MindKit.directory(ctx).take(3500)}\n\n" +
                            "Plan 3 to 6 steps. Return ONLY JSON {\"steps\":[{\"step\":\"what to do\",\"expertise\":\"exact name from the list\"}]}.", 700))
                    val list = MindKit.array(plan, "steps").ifEmpty { listOf(JSONObject().put("step", goal).put("expertise", "Chief Planner")) }
                    var so = ""
                    list.forEachIndexed { i, s ->
                        val e = MindKit.byName(ctx, s.optString("expertise")) ?: MindKit.core(ctx, "planner")
                        AgentHalo.step("Step ${i + 1}: ${e.name}")
                        Pulse.emit(Pulse.Kind.WORKING, "Nexus step ${i + 1}", "${e.name}: ${s.optString("step").take(60)}")
                        val out = MindKit.ask(container, e, "Overall job: $goal\nYour step: ${s.optString("step")}\n\nWork so far:\n${so.takeLast(3000).ifBlank { "(first step)" }}\n\n" +
                            "Do your step fully and concretely. Under 220 words.", 700)
                        steps.add(Triple(e.name, s.optString("step"), out))
                        so += "\n\n## ${e.name}: ${s.optString("step")}\n$out"
                    }
                    AgentHalo.step("Writing the report")
                    val r = MindKit.ask(container, MindKit.core(ctx, "planner"), "Job: $goal\n\nWhat each step produced:$so\n\n" +
                        "Report back: the result in a few lines, decisions the user must make, and the next actions as a checklist.", 900)
                    report = r
                    Record.saveRun(ctx, "Nexus", goal, steps.mapNotNull { MindKit.byName(ctx, it.first) }, r)
                }.onFailure { Feedback.show(it.message ?: "Nexus stopped") }
                AgentHalo.end(); busy = false
            }
        }, enabled = goal.isNotBlank() && !busy) { Text(if (busy) "Working…" else "Run the job") }
        steps.forEachIndexed { i, (who, what, out) ->
            Group {
                Text("${i + 1}. $what", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(who, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Md(out)
            }
        }
        report?.let { r ->
            Heading("Report")
            Group { Md(r) }
            ResultActions("Nexus", r)
            OutlinedButton(onClick = { scope.launch {
                val n = runCatching { Lenses.proposeTasks(container, "Nexus", r) }.getOrDefault(0)
                Feedback.show("$n tasks are waiting for your approval", "Review") { open("nx/approvalinbox") }
            } }) { Text("Send the actions to my tasks") }
        }
        AlsoHere(open, "Agent teams" to "nx/crew", "Mission control" to "nx/missioncontrol", "Flows" to "nx/flows", "Overnight life ops" to "nx/lifeops", "Approvals" to "nx/approvalinbox")
    }
}

// ───────────────────────────── Simulate & Compete ─────────────────────────────

/** Simulate: dry-run an expertise on scenarios and score it before you rely on it. */
@Composable
fun SimulateScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var chosen by remember { mutableStateOf(listOfNotNull(Expertise.find(ctx, "analyst"))) }
    var scenarios by remember { mutableStateOf("") }
    val results = remember { mutableStateListOf<Triple<String, Int, String>>() }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        ExpertPicker(chosen, 1) { chosen = it }
        OutlinedTextField(scenarios, { scenarios = it }, Modifier.fillMaxWidth(), label = { Text("Scenarios, one per line (or leave empty and Atlas writes 3)") }, minLines = 3)
        Button(onClick = {
            val e = chosen.firstOrNull() ?: return@Button
            busy = true; results.clear()
            scope.launch {
                runCatching {
                    val list = scenarios.lines().filter { it.isNotBlank() }.ifEmpty {
                        Ai.generate(container.settingsRepository, "Write 3 realistic, tricky questions someone in Pakistan might ask a ${e.name.lowercase()} (${e.role}). One per line, no numbering.", null, 300)
                            .lines().map { it.trim().trimStart('-', '•', ' ') }.filter { it.length > 10 }.take(3)
                    }
                    for (sc in list) {
                        val a = MindKit.ask(container, e, sc, 500)
                        val (score, why) = MindKit.judge(container, sc, a)
                        results.add(Triple(sc, score, why))
                        Record.score(ctx, e.id, "simulate", score)
                    }
                }.onFailure { Feedback.show(it.message ?: "The simulation stopped") }
                busy = false
            }
        }, enabled = chosen.isNotEmpty() && !busy) { Text(if (busy) "Running…" else "Run the simulation") }
        if (results.isNotEmpty()) StatTile("Average score", "%.1f / 10".format(results.map { it.second }.average()), Modifier.fillMaxWidth())
        results.forEach { (sc, s, why) -> ListRow(sc, supporting = "$s/10 · $why", icon = Icons.Outlined.Science) }
        AlsoHere(open, "Day rehearsal" to "nx/rehearsal", "Shadow run" to "nx/shadowmode", "Life simulator" to "lifesim", "Decision court" to "court")
    }
}

/** Compete: two kinds of expertise answer the same question; a judge (or you) picks the winner. */
@Composable
fun CompeteScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var q by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(listOf("research", "analyst").mapNotNull { Expertise.find(ctx, it) }) }
    var a by remember { mutableStateOf<String?>(null) }
    var b by remember { mutableStateOf<String?>(null) }
    var verdict by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    val board = remember(version) { Record.points(ctx).entries.sortedByDescending { it.value }.take(10).mapNotNull { (id, p) -> Expertise.find(ctx, id)?.let { it to p } } }
    fun win(e: Expertise.Expert, loser: Expertise.Expert) { Record.score(ctx, e.id, "compete", 3); Record.score(ctx, loser.id, "compete", 1); version++ }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("The question") }, minLines = 2)
        ExpertPicker(chosen, 2) { chosen = it }
        Button(onClick = {
            if (chosen.size < 2) return@Button
            busy = true; a = null; b = null; verdict = null
            scope.launch {
                runCatching {
                    val (x, y) = coroutineScope { listOf(async { MindKit.ask(container, chosen[0], q, 600) }, async { MindKit.ask(container, chosen[1], q, 600) }).awaitAll() }
                    a = x; b = y
                    val raw = Ai.generate(container.settingsRepository, "Question: $q\n\nAnswer A (${chosen[0].name}):\n$x\n\nAnswer B (${chosen[1].name}):\n$y\n\n" +
                        "Which is better for the user? Return ONLY JSON {\"winner\":\"A|B\",\"why\":\"two short sentences\"}.", "You are a fair judge.", 200)
                    val o = MindKit.json(raw)
                    val w = if (o?.optString("winner") == "B") 1 else 0
                    win(chosen[w], chosen[1 - w])
                    verdict = "${chosen[w].name} wins: ${o?.optString("why").orEmpty()}"
                    Record.saveRun(ctx, "Compete", q, chosen, verdict.orEmpty())
                }.onFailure { Feedback.show(it.message ?: "The match stopped") }
                busy = false
            }
        }, enabled = q.isNotBlank() && chosen.size == 2 && !busy) { Text(if (busy) "Competing…" else "Face off") }
        verdict?.let { Text(it, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
        if (chosen.size == 2) {
            a?.let { Group { Text(chosen[0].name, style = MaterialTheme.typography.titleSmall); Md(it); OutlinedButton(onClick = { win(chosen[0], chosen[1]); Feedback.show("Point to ${chosen[0].name}") }) { Text("I prefer this") } } }
            b?.let { Group { Text(chosen[1].name, style = MaterialTheme.typography.titleSmall); Md(it); OutlinedButton(onClick = { win(chosen[1], chosen[0]); Feedback.show("Point to ${chosen[1].name}") }) { Text("I prefer this") } } }
        }
        if (board.isNotEmpty()) {
            Heading("Leaderboard")
            board.forEachIndexed { i, (e, p) -> ListRow("${i + 1}. ${e.name}", supporting = "$p points · ${e.sector}", icon = Icons.Outlined.EmojiEvents, tint = e.hub.tint()) }
        }
        AlsoHere(open, "Model arena" to "nx/arena", "Model debate" to "nx/debate", "Debate dojo" to "nx/debatedojo", "Skill duel" to "nx/skillduel")
    }
}

// ───────────────────────────── Training & A/B Lab ─────────────────────────────

/** Training: teach any expertise with worked examples; Atlas can draft practice examples for you to approve. */
@Composable
fun TrainingScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var chosen by remember { mutableStateOf(emptyList<Expertise.Expert>()) }
    var version by remember { mutableIntStateOf(0) }
    val drafts = remember { mutableStateListOf<Pair<String, String>>() }
    var busy by remember { mutableStateOf(false) }
    val counts = remember(version) { Training.count(ctx) }
    val e = chosen.firstOrNull()
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            StatTile("Examples", "${counts.values.sum()}", Modifier.weight(1f))
            StatTile("Expertise trained", "${counts.size}", Modifier.weight(1f))
        }
        ExpertPicker(chosen, 1) { chosen = it; drafts.clear() }
        if (e != null) {
            val list = remember(e.id, version) { Training.of(ctx, e.id) }
            Heading("${e.name} · ${list.size} examples")
            list.forEach { o ->
                ListRow(o.optString("q").take(100), supporting = o.optString("a").take(120), icon = Icons.Outlined.ModelTraining, trailing = {
                    Text("✕", Modifier.padding(Space.s), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }) { Training.store(ctx).remove(o.optString("id")); version++ }
            }
            OutlinedButton(onClick = {
                busy = true; drafts.clear()
                scope.launch {
                    runCatching {
                        val raw = MindKit.ask(container, e, "Write 3 practice examples of excellent answers in your expertise for someone in Pakistan. " +
                            "Return ONLY JSON {\"examples\":[{\"q\":\"question\",\"a\":\"ideal answer under 150 words\"}]}.", 1200)
                        MindKit.array(MindKit.json(raw), "examples").forEach { drafts.add(it.optString("q") to it.optString("a")) }
                    }.onFailure { Feedback.show(it.message ?: "Couldn't draft") }
                    busy = false
                }
            }, enabled = !busy) { Text(if (busy) "Drafting…" else "Draft practice examples") }
            drafts.toList().forEach { (q, a) ->
                Group {
                    Text(q, style = MaterialTheme.typography.titleSmall); Md(a)
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Button(onClick = { Training.add(ctx, e.id, q, a); drafts.remove(q to a); version++ }) { Text("Keep") }
                        OutlinedButton(onClick = { drafts.remove(q to a) }) { Text("Drop") }
                    }
                }
            }
        }
        AlsoHere(open, "Agent apprenticeship" to "nx/apprentice", "Expertise Studio" to "nx/expertisestudio", "Versions" to "nx/evolve")
    }
}

/**
 * A/B Lab: compare two sets of instructions, or two models, on the same question, side by side, and keep the
 * winner. A winning instruction set becomes that expertise's new version (with rollback in Evolve).
 */
@Composable
fun AbLabScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var mode by remember { mutableStateOf("instructions") }
    var q by remember { mutableStateOf("") }
    var chosen by remember { mutableStateOf(emptyList<Expertise.Expert>()) }
    var variantB by remember { mutableStateOf("") }
    var outA by remember { mutableStateOf<String?>(null) }
    var outB by remember { mutableStateOf<String?>(null) }
    var meta by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var endpoints by remember { mutableStateOf<List<Router.Endpoint>>(emptyList()) }
    var pickA by remember { mutableIntStateOf(0) }
    var pickB by remember { mutableIntStateOf(1) }
    androidx.compose.runtime.LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { endpoints = runCatching { Router.endpoints(container.settingsRepository, ctx, Router.Lane.CHAT) }.getOrDefault(emptyList()).distinctBy { it.name + it.model }.take(12) } }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            Capsule("Instructions A vs B", mode == "instructions") { mode = "instructions" }
            Capsule("Model A vs B", mode == "models") { mode = "models" }
        }
        OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text("Test question") }, minLines = 2)
        if (mode == "instructions") {
            ExpertPicker(chosen, 1) { chosen = it; variantB = it.firstOrNull()?.let { e -> Expertise.block(e) }.orEmpty() }
            if (chosen.isNotEmpty()) OutlinedTextField(variantB, { variantB = it }, Modifier.fillMaxWidth(), label = { Text("Instructions B (edit them)") }, minLines = 6)
        } else {
            if (endpoints.size < 2) Hint("Add at least two keys or models in Settings › API keys to compare models.")
            else {
                Text("Model A", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    endpoints.forEachIndexed { i, e -> Capsule("${e.name} · ${e.model.substringAfterLast('/')}", pickA == i) { pickA = i } }
                }
                Text("Model B", style = MaterialTheme.typography.labelLarge)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    endpoints.forEachIndexed { i, e -> Capsule("${e.name} · ${e.model.substringAfterLast('/')}", pickB == i) { pickB = i } }
                }
            }
        }
        Button(onClick = {
            busy = true; outA = null; outB = null; meta = ""
            scope.launch {
                runCatching {
                    if (mode == "instructions") {
                        val e = chosen.first()
                        val (x, y) = coroutineScope { listOf(
                            async { Ai.generate(container.settingsRepository, q, Expertise.block(e), 700) },
                            async { Ai.generate(container.settingsRepository, q, if (variantB.contains(Expertise.MARK)) variantB else "${Expertise.MARK}\n$variantB", 700) }).awaitAll() }
                        outA = x; outB = y
                    } else {
                        val ea = endpoints[pickA]; val eb = endpoints[pickB]
                        val t0 = System.currentTimeMillis()
                        val x = async0 { Ai.call(ea, q, "You are Atlas, a helpful assistant.", 700) }
                        val t1 = System.currentTimeMillis()
                        val y = async0 { Ai.call(eb, q, "You are Atlas, a helpful assistant.", 700) }
                        val t2 = System.currentTimeMillis()
                        outA = x; outB = y
                        meta = "A took ${"%.1f".format((t1 - t0) / 1000.0)} s · B took ${"%.1f".format((t2 - t1) / 1000.0)} s"
                    }
                }.onFailure { Feedback.show(it.message ?: "The test stopped") }
                busy = false
            }
        }, enabled = q.isNotBlank() && !busy && (if (mode == "instructions") chosen.isNotEmpty() && variantB.isNotBlank() else endpoints.size >= 2 && pickA != pickB)) {
            Text(if (busy) "Testing…" else "Run A and B")
        }
        if (meta.isNotBlank()) Hint(meta)
        outA?.let { Group { Text("A", style = MaterialTheme.typography.titleSmall); Md(it) } }
        outB?.let { Group { Text("B", style = MaterialTheme.typography.titleSmall); Md(it) } }
        if (mode == "instructions" && outB != null && chosen.isNotEmpty()) Button(onClick = {
            Versions.apply(ctx, chosen.first().id, variantB.removePrefix(Expertise.MARK).trim(), "Won an A/B test")
            Feedback.show("B is now ${chosen.first().name}'s instructions. Undo any time in Evolve.")
        }) { Text("Keep B as the new version") }
        AlsoHere(open, "AI lab" to "nx/ailab", "Model arena" to "nx/arena", "AI routing" to "nx/hybridrouter", "Engine room" to "nx/engineroom")
    }
}

private suspend fun <T> async0(block: suspend () -> T): T = block()

// ───────────────────────────── Society & World ─────────────────────────────

object Society {
    data class Member(val e: Expertise.Expert, val level: Int, val uses: Int, val points: Int, val examples: Int)

    fun members(c: Context): List<Member> {
        val p = c.getSharedPreferences("expertise", 0)
        val pts = Record.points(c)
        val ex = Training.count(c)
        return Expertise.all(c).map { e ->
            val u = p.getInt("uses|${e.id}", 0); val s = pts[e.id] ?: 0; val x = ex[e.id] ?: 0
            Member(e, 1 + (ln(u + 1.0) * 1.6 + s / 6.0 + x / 2.0).toInt(), u, s, x)
        }
    }

    val TITLES = listOf("Trainee", "Associate", "Specialist", "Senior", "Lead", "Principal", "Partner", "Fellow")
    fun title(level: Int) = TITLES[(level - 1).coerceIn(0, TITLES.lastIndex)]
}

/** Society: all of Atlas's expertise as one organisation: departments, levels, who works with whom. */
@Composable
fun SocietyScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val members = remember { Society.members(ctx) }
    val pairs = remember { Record.pairs(ctx).entries.sortedByDescending { it.value }.take(6) }
    var dept by remember { mutableStateOf<String?>(null) }
    val star = members.maxByOrNull { it.uses + it.points }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            StatTile("Kinds of expertise", "${members.size}", Modifier.weight(1f))
            StatTile("Departments", "${members.map { it.e.sector }.distinct().size}", Modifier.weight(1f))
            StatTile("Active", "${members.count { it.uses > 0 }}", Modifier.weight(1f))
        }
        if (star != null && star.uses > 0) Group {
            Text("Most relied on", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(star.e.name, style = MaterialTheme.typography.titleLarge)
            Text("${Society.title(star.level)} · used ${star.uses} times · ${star.points} points", style = MaterialTheme.typography.bodySmall)
        }
        if (pairs.isNotEmpty()) {
            Heading("Who works together")
            pairs.forEach { (k, n) ->
                val a = Expertise.find(ctx, k.first)?.name ?: k.first; val b = Expertise.find(ctx, k.second)?.name ?: k.second
                ListRow("$a + $b", supporting = "$n jobs together", icon = Icons.Outlined.Handshake)
            }
        }
        Heading("Departments")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            Capsule("All", dept == null) { dept = null }
            members.map { it.e.sector }.distinct().forEach { s -> Capsule(s, dept == s) { dept = s } }
        }
        members.filter { dept == null || it.e.sector == dept }.sortedWith(compareByDescending<Society.Member> { it.level }.thenByDescending { it.uses }).take(if (dept == null) 25 else 60).forEach { m ->
            ListRow(m.e.name, supporting = "${Society.title(m.level)} · level ${m.level} · ${m.e.sector}", icon = m.e.hub.icon, tint = m.e.hub.tint()) {
                Expertise.openDesk(m.e.id); open("nx/expertise")
            }
        }
        AlsoHere(open, "Atlas HQ" to "hq", "Atlas World" to "nx/atlasworld", "Town" to "town", "Agent teams" to "nx/crew")
    }
}

/** Atlas World: a living tower, one floor per department; lit windows are expertise working lately. Tap a floor to walk in. */
@Composable
fun WorldScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val members = remember { Society.members(ctx) }
    val floors = remember { members.groupBy { it.e.sector }.entries.sortedBy { it.key }.map { it.key to it.value } }
    var floor by remember { mutableStateOf<String?>(null) }
    val t = com.nizam.app.ui.design.rememberLoop(8000)
    val sky = MaterialTheme.colorScheme.primary
    val glass = MaterialTheme.colorScheme.surfaceVariant
    val lit = Color(0xFFE9C46A)
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        val h = 26f
        Canvas(Modifier.fillMaxWidth().height((floors.size * 26 + 60).dp).pointerInput(floors) {
            detectTapGestures { p ->
                val fh = h.dp.toPx(); val top = 40.dp.toPx()
                val i = ((p.y - top) / fh).toInt()
                if (i in floors.indices) floor = floors[floors.size - 1 - i].first
            }
        }) {
            val fh = h.dp.toPx(); val top = 40.dp.toPx()
            val w = size.width * 0.62f; val x0 = (size.width - w) / 2f
            drawCircle(Brush.radialGradient(listOf(sky.copy(alpha = 0.25f), Color.Transparent), Offset(size.width / 2, top), size.width * 0.6f), size.width * 0.6f, Offset(size.width / 2, top))
            floors.reversed().forEachIndexed { i, (sector, list) ->
                val y = top + i * fh
                val sway = kotlin.math.sin((t * 6.283f) + i * 0.4f) * 1.5f
                val tint = Expertise.hubFor(sector).spine
                drawRoundRect(glass, Offset(x0 + sway, y), Size(w, fh - 4f), CornerRadius(8f))
                drawRect(tint.copy(alpha = 0.55f), Offset(x0 + sway, y), Size(6f, fh - 4f))
                val n = list.size.coerceAtMost(14)
                val active = list.sortedByDescending { it.uses }.take(n)
                active.forEachIndexed { k, m ->
                    val wx = x0 + 18f + k * ((w - 30f) / n) + sway
                    val on = m.uses > 0
                    val flicker = if (on) 0.7f + 0.3f * kotlin.math.sin(t * 6.283f * 2 + k + i).toFloat() else 0f
                    drawRoundRect(if (on) lit.copy(alpha = flicker) else tint.copy(alpha = 0.18f), Offset(wx, y + 6f), Size(((w - 30f) / n) * 0.6f, fh - 16f), CornerRadius(3f))
                }
                if (sector == floor) drawRoundRect(sky, Offset(x0 - 4f + sway, y - 2f), Size(w + 8f, fh), CornerRadius(10f), style = androidx.compose.ui.graphics.drawscope.Stroke(3f))
            }
            drawRect(glass.copy(alpha = 0.6f), Offset(x0 - 20f, top + floors.size * fh), Size(w + 40f, 10f))
        }
        Hint("Tap a floor. Lit windows are expertise Atlas has used.")
        floor?.let { s ->
            Heading(s)
            floors.firstOrNull { it.first == s }?.second?.sortedByDescending { it.uses }?.forEach { m ->
                ListRow(m.e.name, supporting = "${Society.title(m.level)} · ${m.e.role.take(80)}", icon = m.e.hub.icon, tint = m.e.hub.tint()) {
                    Expertise.openDesk(m.e.id); open("nx/expertise")
                }
            }
        }
        AlsoHere(open, "Atlas HQ in 3D" to "hq", "Town" to "town", "Pet world" to "companion", "Society" to "nx/society")
    }
}

val WorkforceSpecs: List<com.nizam.app.feature.nx.Spec> get() = listOf(
    ScreenSpec("swarm", "Swarm", "Many kinds of expertise answer at once, then one synthesis", Icons.Outlined.Diversity3, Hub.Work,
        "swarm many experts council parallel synthesis multiple opinions") { c, o -> SwarmScreen(c, o) },
    ScreenSpec("syndicate", "Syndicate", "Experts consult each other in turns; read every thread", Icons.Outlined.Forum, Hub.Work,
        "syndicate experts discuss consult thread talk to each other") { c, o -> SyndicateScreen(c, o) },
    ScreenSpec("nexus", "Nexus", "Atlas plans a job, hands each step to the right expertise, and reports back", Icons.Outlined.Hub, Hub.Work,
        "nexus job plan delegate steps orchestrate prime project goal") { c, o -> NexusScreen(c, o) },
    ScreenSpec("simulate", "Simulate", "Dry-run an expertise on scenarios and score it", Icons.Outlined.Science, Hub.Work,
        "simulate test scenarios score dry run evaluate") { c, o -> SimulateScreen(c, o) },
    ScreenSpec("compete", "Compete", "Two kinds of expertise face off; a leaderboard keeps score", Icons.Outlined.EmojiEvents, Hub.Work,
        "compete face off versus leaderboard winner judge") { c, o -> CompeteScreen(c, o) },
    ScreenSpec("training", "Training", "Teach any expertise with worked examples", Icons.Outlined.ModelTraining, Hub.Learn,
        "training examples teach few shot improve expertise") { c, o -> TrainingScreen(c, o) },
    ScreenSpec("ablab", "A/B Lab", "Compare instructions or models side by side and keep the winner", Icons.Outlined.CompareArrows, Hub.Tools,
        "a/b test compare prompts models side by side lab experiment") { c, o -> AbLabScreen(c, o) },
    ScreenSpec("society", "Society", "All of Atlas's expertise as one organisation: departments, levels, partnerships", Icons.Outlined.Groups, Hub.Work,
        "society org chart staff levels departments company") { c, o -> SocietyScreen(c, o) },
    ScreenSpec("atlasworld", "Atlas World", "A living tower with a floor for every department; tap to walk in", Icons.Outlined.LocationCity, Hub.Work,
        "world tower 3d building floors departments lycho world") { c, o -> WorldScreen(c, o) },
)
