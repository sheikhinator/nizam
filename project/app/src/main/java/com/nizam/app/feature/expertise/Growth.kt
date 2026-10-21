package com.nizam.app.feature.expertise

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import com.nizam.app.AppContainer
import com.nizam.app.feature.agent.ApprovalInbox
import com.nizam.app.feature.agent.ChatModes
import com.nizam.app.feature.agent.Reviewer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.Md
import com.nizam.app.feature.nx.Mind
import com.nizam.app.feature.nx.Nx
import com.nizam.app.feature.nx.Roads
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.net.Ai
import com.nizam.app.net.Router
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.StatTile
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

// ───────────────────────────── Forge ─────────────────────────────

/**
 * Forge: designs new expertise, new trackers and new life laws from what you actually do in Atlas. Nothing
 * is created until you say so; trackers go through the approval inbox like every other change.
 */
object Forge {
    fun store(c: Context) = JsonStore(c, "forge_proposals")

    suspend fun propose(container: AppContainer): List<JSONObject> {
        val c = container.appContext
        val tools = Mind.counts(c).entries.sortedByDescending { it.value }.take(15).mapNotNull { (id, n) -> Nx.find(id)?.let { "${it.title} ($n)" } }
        val experts = Expertise.topUsed(c, 10).map { "${it.first.name} (${it.second})" }
        val sources = Roads.sources(c).take(15).map { "${it.label} (${it.count})" }
        val asked = runCatching { container.agentMemory.turns().first().filter { it.fromUser }.takeLast(25).joinToString("\n") { "- " + it.text.take(140) } }.getOrDefault("")
        val raw = Ai.generate(container.settingsRepository,
            "How the user uses Atlas.\nTools opened most: ${tools.joinToString()}\nExpertise used most: ${experts.joinToString()}\n" +
                "What they record: ${sources.joinToString()}\nRecent things they asked:\n$asked\n\n" +
                "Design what Atlas is missing for this person. Return ONLY JSON {\"expertise\":[{\"name\":\"\",\"role\":\"one line\",\"prompt\":\"120-180 words of instructions in second person, Atlas stays Atlas\",\"why\":\"what in their usage calls for it\"}]," +
                "\"trackers\":[{\"name\":\"\",\"emoji\":\"\",\"tagline\":\"\",\"why\":\"\",\"fields\":[{\"key\":\"\",\"label\":\"\",\"type\":\"TEXT|NUMBER|DATE|RATING|CHOICE|BOOL\",\"options\":[]}]}]," +
                "\"laws\":[{\"text\":\"a rule in plain words\",\"why\":\"\"}]}. At most 2 of each, only when clearly useful, never duplicates of what they already use.",
            "You are Atlas's Forge: precise, practical, nothing generic.", 2200)
        val o = MindKit.json(raw) ?: throw IllegalStateException("Forge couldn't read its own design. Try again.")
        val out = MindKit.array(o, "expertise").map { it.put("kind", "expertise") } + MindKit.array(o, "trackers").map { it.put("kind", "tracker") } +
            MindKit.array(o, "laws").map { it.put("kind", "law") }
        store(c).save(out.mapIndexed { i, x -> x.put("id", "${System.currentTimeMillis()}$i").put("at", System.currentTimeMillis()) })
        Pulse.emit(Pulse.Kind.DONE, "Forge has ${out.size} designs for you", "", Hub.Work.label)
        return out
    }

    fun accept(c: Context, o: JSONObject): String = when (o.optString("kind")) {
        "expertise" -> { Expertise.saveCustom(c, null, o.optString("name"), o.optString("role"), o.optString("prompt"), false); "Atlas can now think as ${o.optString("name")}" }
        "law" -> { Laws.add(c, o.optString("text")); Laws.invalidate(); "Life law added" }
        else -> {
            ApprovalInbox.add(c, "Forge", "create_module", JSONObject().put("name", o.optString("name")).put("emoji", o.optString("emoji", "✨"))
                .put("tagline", o.optString("tagline")).put("fields", o.optJSONArray("fields") ?: JSONArray()),
                Reviewer.Verdict("low", "approve", "Creates a new tracker inside Atlas"), "Designed by Forge: ${o.optString("why")}")
            "The tracker is waiting for your approval"
        }
    }
}

@Composable
fun ForgeScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val list = remember { mutableStateListOf<JSONObject>().apply { addAll(Forge.store(ctx).all()) } }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Hint("Forge studies what you open, record and ask, then designs expertise, trackers and life laws for the gaps. You decide what gets made.")
        Button(onClick = {
            busy = true
            scope.launch { runCatching { Forge.propose(container) }.onSuccess { list.clear(); list.addAll(it) }.onFailure { Feedback.show(it.message ?: "Forge couldn't finish") }; busy = false }
        }, enabled = !busy) { Text(if (busy) "Studying how you use Atlas…" else if (list.isEmpty()) "Design for me" else "Design again") }
        if (busy) com.nizam.app.ui.components.Thinking(label = "Forging")
        list.toList().forEach { o ->
            Group {
                Text(when (o.optString("kind")) { "expertise" -> "New expertise"; "law" -> "New life law"; else -> "New tracker" },
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(o.optString("name").ifBlank { o.optString("text") }, style = MaterialTheme.typography.titleMedium)
                o.optString("role").ifBlank { o.optString("tagline") }.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                o.optJSONArray("fields")?.let { a -> Hint("Fields: " + (0 until a.length()).joinToString { a.optJSONObject(it)?.optString("label").orEmpty() }) }
                Hint("Why: ${o.optString("why")}")
                Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Button(onClick = { Feedback.show(Forge.accept(ctx, o)); list.remove(o); Forge.store(ctx).save(list.toList()) }) { Text("Make it") }
                    OutlinedButton(onClick = { list.remove(o); Forge.store(ctx).save(list.toList()) }) { Text("Not now") }
                }
            }
        }
        AlsoHere(open, "Expertise Studio" to "nx/expertisestudio", "Make a screen" to "nx/genscreen", "Modules" to "modules", "Template packs" to "nx/packs", "Chief" to "nx/chief")
    }
}

// ───────────────────────────── Orion & Evolve ─────────────────────────────

/**
 * Orion: Atlas's nightly self-review. It looks at the expertise used most, the models that failed and what
 * you kept as examples, rewrites instructions that can do better (each a new version you can roll back) and
 * writes a short report. Runs once a night in the background, or when you ask.
 */
object Orion {
    fun reports(c: Context) = JsonStore(c, "orion_reports")
    private fun prefs(c: Context) = c.getSharedPreferences("orion", 0)
    fun lastRun(c: Context) = prefs(c).getString("last", null)

    /** Called from Atlas's background tick: once a night, after 2 am. */
    suspend fun nightly(c: Context) {
        val now = java.time.LocalDateTime.now()
        if (now.hour < 2 || lastRun(c) == LocalDate.now().toString()) return
        if (Expertise.topUsed(c, 1).isEmpty()) { prefs(c).edit().putString("last", LocalDate.now().toString()).apply(); return }
        val container = (c.applicationContext as? com.nizam.app.NizamApplication)?.container ?: return
        withContext(Router.quiet.asContextElement(true)) { runCatching { run(container, 2) } }
    }

    suspend fun run(container: AppContainer, maxRewrites: Int = 3): String {
        val c = container.appContext
        prefs(c).edit().putString("last", LocalDate.now().toString()).apply()
        Pulse.emit(Pulse.Kind.WORKING, "Orion is reviewing Atlas", "", Hub.Tools.label)
        val changes = mutableListOf<String>()
        for ((e, uses) in Expertise.topUsed(c, maxRewrites)) {
            val examples = Training.of(c, e.id).take(3).joinToString("\n\n") { "Q: ${it.optString("q").take(200)}\nA: ${it.optString("a").take(400)}" }
            val raw = Ai.generate(container.settingsRepository,
                "Current instructions for the expertise \"${e.name}\" (used $uses times):\n${Expertise.block(e).removePrefix(Expertise.MARK).trim()}\n\n" +
                    "Answers the user approved:\n${examples.ifBlank { "(none yet)" }}\n\n" +
                    "Improve the instructions so answers are more specific, better structured and closer to what the user approves, for life in Pakistan. " +
                    "Keep it 120-200 words, second person, and Atlas stays Atlas. If they are already excellent, return KEEP. " +
                    "Return ONLY JSON {\"prompt\":\"... or KEEP\",\"why\":\"one sentence on what changed\"}.",
                "You are Orion, Atlas's careful self-improvement reviewer.", 900)
            val o = MindKit.json(raw) ?: continue
            val p = o.optString("prompt")
            if (p.isBlank() || p.trim() == "KEEP" || p.length < 80) continue
            Versions.apply(c, e.id, p.trim(), "Orion: ${o.optString("why").take(160)}")
            changes += "${e.name}: ${o.optString("why")}"
        }
        val failing = EngineLog.today(c).filter { it.calls >= 3 && it.ok * 100 / it.calls < 60 }.map { "${it.provider} answered only ${it.ok}/${it.calls}" }
        val text = buildString {
            append("**Orion · ${LocalDate.now()}**\n\n")
            if (changes.isEmpty()) append("No expertise needed rewriting.\n") else { append("Improved:\n"); changes.forEach { append("- ").append(it).append('\n') } }
            if (failing.isNotEmpty()) { append("\nModels struggling today:\n"); failing.forEach { append("- ").append(it).append('\n') }; append("Heal can test and fix them.\n") }
        }
        reports(c).let { st -> st.save((st.all() + JSONObject().put("id", System.currentTimeMillis().toString()).put("text", text).put("at", System.currentTimeMillis())).takeLast(60)) }
        Pulse.emit(Pulse.Kind.DONE, "Orion finished", "${changes.size} improvements", Hub.Tools.label)
        return text
    }

    /** The monthly self-improvement report: what Atlas learned, fixed and improved. */
    suspend fun monthly(container: AppContainer): String {
        val c = container.appContext
        val since = System.currentTimeMillis() - 30L * 86_400_000L
        val runs = reports(c).all().filter { it.optLong("at") > since }.joinToString("\n") { it.optString("text").take(600) }
        val versions = Versions.store(c).all().filter { it.optLong("at") > since }.size
        val examples = Training.store(c).all().count { it.optLong("at") > since }
        val calls = EngineLog.history(c).filter { it.first >= LocalDate.now().minusDays(30).toString() }
        val total = calls.sumOf { it.second.calls }; val ok = calls.sumOf { it.second.ok }
        val learned = runCatching { com.nizam.app.feature.brain2.PersonalModel.promptBlock(c).take(1200) }.getOrDefault("")
        return Ai.generate(container.settingsRepository,
            "Last 30 days.\nOrion reports:\n$runs\nNew versions of expertise: $versions\nWorked examples added: $examples\n" +
                "Model calls: $total, answered: $ok\nWhat Atlas knows about the user now:\n$learned\n\n" +
                "Write Atlas's monthly self-improvement report to the user: what I learned about you, what I fixed, what I improved, and what I'll work on next month. Warm, short, specific.",
            "You are Atlas writing about yourself, honestly. No invented facts.", 900)
    }
}

@Composable
fun OrionScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var version by remember { mutableIntStateOf(0) }
    var monthly by remember { mutableStateOf<String?>(null) }
    val reports = remember(version) { Orion.reports(ctx).all().sortedByDescending { it.optLong("at") } }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            StatTile("Last review", Orion.lastRun(ctx) ?: "Never", Modifier.weight(1f))
            StatTile("Versions written", "${remember(version) { Versions.store(ctx).all().size }}", Modifier.weight(1f))
        }
        Hint("Every night after 2 am Orion reviews the expertise you use most, rewrites what can be better and checks the models. Every change is a version you can undo in Evolve.")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Button(onClick = { busy = true; scope.launch { runCatching { Orion.run(container) }.onFailure { Feedback.show(it.message ?: "Orion stopped") }; version++; busy = false } },
                enabled = !busy) { Text(if (busy) "Reviewing…" else "Review now") }
            OutlinedButton(onClick = { busy = true; scope.launch { runCatching { Orion.monthly(container) }.onSuccess { monthly = it }.onFailure { Feedback.show(it.message ?: "Couldn't write it") }; busy = false } },
                enabled = !busy) { Text("Monthly report") }
        }
        monthly?.let { Group { Md(it) } }
        reports.take(10).forEach { Group { Md(it.optString("text")) } }
        AlsoHere(open, "Evolve" to "nx/evolve", "Heal" to "nx/heal", "Atlas learned" to "nx/atlaslearned", "Brain lab" to "nx/brainlab")
    }
}

/** Evolve: every version of every expertise, with a one-tap restore; and directed rewrites. */
@Composable
fun EvolveScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val changed = remember(version) { Versions.changed(ctx) }
    var expanded by remember { mutableStateOf<String?>(null) }
    var chosen by remember { mutableStateOf(emptyList<Expertise.Expert>()) }
    var direction by remember { mutableStateOf("") }
    var draft by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Heading("Evolve one now")
        ExpertPicker(chosen, 1) { chosen = it; draft = null }
        OutlinedTextField(direction, { direction = it }, Modifier.fillMaxWidth(), label = { Text("What should change? (shorter, more Pakistan-specific, ask questions first…)") }, minLines = 2)
        Button(onClick = {
            val e = chosen.firstOrNull() ?: return@Button
            busy = true
            scope.launch {
                runCatching {
                    Ai.generate(container.settingsRepository, "Current instructions:\n${Expertise.block(e).removePrefix(Expertise.MARK).trim()}\n\nChange requested: $direction\n\n" +
                        "Rewrite the instructions with that change, 120-200 words, second person, Atlas stays Atlas. Output only the instructions.", null, 700)
                }.onSuccess { draft = it.trim() }.onFailure { Feedback.show(it.message ?: "Couldn't rewrite") }
                busy = false
            }
        }, enabled = chosen.isNotEmpty() && direction.isNotBlank() && !busy) { Text(if (busy) "Rewriting…" else "Rewrite") }
        draft?.let { d ->
            Group { Md(d) }
            Button(onClick = { Versions.apply(ctx, chosen.first().id, d, "You: $direction"); draft = null; direction = ""; version++; Feedback.show("New version in use") }) { Text("Use this version") }
        }
        Heading("History")
        if (changed.isEmpty()) Hint("No versions yet. Orion, A/B tests, your edits and rewrites here all show up as versions.")
        changed.forEach { (id, list) ->
            val e = Expertise.find(ctx, id)
            ListRow(e?.name ?: id, supporting = "${list.size} versions · last: ${list.first().optString("why").take(70)}", icon = Icons.Outlined.History) {
                expanded = if (expanded == id) null else id
            }
            if (expanded == id) {
                list.forEach { v ->
                    Group {
                        Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(v.optLong("at"))) + " · " + v.optString("why"),
                            style = MaterialTheme.typography.labelMedium)
                        Text(v.optString("prompt").take(400), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = {
                            if (id.startsWith("mine_")) e?.let { Expertise.saveCustom(ctx, id, it.name, it.role, v.optString("prompt"), it.heavy) }
                            else Versions.apply(ctx, id, v.optString("prompt"), "Restored a version from ${java.time.Instant.ofEpochMilli(v.optLong("at")).atZone(java.time.ZoneId.systemDefault()).toLocalDate()}")
                            version++; Feedback.show("Restored")
                        }, Modifier.padding(top = Space.xs)) { Text("Use this one") }
                    }
                }
                if (!id.startsWith("mine_")) OutlinedButton(onClick = { Versions.reset(ctx, id); version++; Feedback.show("Back to the original") }) { Text("Back to the original") }
            }
        }
        AlsoHere(open, "Orion" to "nx/orion", "A/B Lab" to "nx/ablab", "Self-repair" to "nx/selfrepair", "Expertise Studio" to "nx/expertisestudio")
    }
}

// ───────────────────────────── Personas ─────────────────────────────

@Composable
fun PersonasScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    val mine = remember(version) { Personas.custom(ctx) }
    var name by remember { mutableStateOf("") }
    var style by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Hint("How Atlas speaks in chat. Atlas stays Atlas in every one, with the same memory. Pick one here or in the chips above the chat box.")
        (ChatModes.PERSONAS).forEach { p ->
            ListRow(p, supporting = if (ChatModes.persona == p) "In use" else null, icon = if (ChatModes.persona == p) Icons.Outlined.RadioButtonChecked else Icons.Outlined.RadioButtonUnchecked) {
                ChatModes.persona = p; version++
            }
        }
        Heading("Make your own")
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name (e.g. Ustad, Big brother, Analyst)") }, singleLine = true)
        OutlinedTextField(style, { style = it }, Modifier.fillMaxWidth(), label = { Text("How it speaks") }, minLines = 3)
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            OutlinedButton(onClick = {
                busy = true
                scope.launch { runCatching { Ai.generate(container.settingsRepository, "Describe in 2-3 sentences how an assistant called \"$name\" should speak: tone, length, habits. Second person. Output only that.", null, 200) }
                    .onSuccess { style = it.trim() }; busy = false }
            }, enabled = name.isNotBlank() && !busy) { Text(if (busy) "Writing…" else "Write with Atlas") }
            Button(onClick = { Personas.add(ctx, name, style); ChatModes.persona = name.trim(); name = ""; style = ""; version++; Feedback.show("Atlas will now speak this way in chat") },
                enabled = name.isNotBlank() && style.isNotBlank() && (name.trim() !in ChatModes.PERSONAS)) { Text("Save and use") }
        }
        mine.forEach { o -> ListRow(o.optString("name"), supporting = o.optString("style").take(100), icon = Icons.Outlined.TheaterComedy, trailing = {
            Text("✕", Modifier.padding(Space.s), color = MaterialTheme.colorScheme.onSurfaceVariant) }) {
            Personas.remove(ctx, o.optString("id")); if (ChatModes.persona == o.optString("name")) ChatModes.persona = "Atlas"; version++ } }
        AlsoHere(open, "Atlas's soul" to "nx/soul", "My voice" to "nx/myvoice", "My style guide" to "nx/styleguide")
    }
}

// ───────────────────────────── Marketplace ─────────────────────────────

/** One shelf for everything Atlas can add to itself: expertise packs, life-law packs and ways of speaking, beside the App Store and template packs. */
object Market {
    data class Pack(val id: String, val title: String, val blurb: String, val experts: List<Triple<String, String, String>> = emptyList(),
                    val laws: List<String> = emptyList(), val personas: List<Pair<String, String>> = emptyList())

    val packs = listOf(
        Pack("family_cfo", "Family CFO", "Run the household's money like a calm finance director",
            experts = listOf(Triple("Family CFO", "Runs the household budget, committees, school fees and Eid costs.",
                "You help the user run the household's money: monthly budget by category, committee turns, school fees, utility bills, Eid and wedding costs. Use their real spending when shared. Give rupee amounts, a short plan and one habit to keep. Flag debt risk early. You are still Atlas."),
                Triple("Bill Hunter", "Finds bills and charges to lower or cancel.",
                "You find bills, subscriptions and charges the user can lower or cancel, with the exact script or email to do it, ranked by yearly saving in rupees. You are still Atlas.")),
            laws = listOf("Never suggest spending from the emergency fund for wants", "Remind me before any committee payment is due")),
        Pack("student", "Student", "Exams, study plans and scholarships",
            experts = listOf(Triple("Exam Coach", "Plans revision and practice for any exam.",
                "You plan exam revision: topics by weight, a day-by-day schedule fitted around prayers and classes, active recall and past papers, and a calm plan for the last 48 hours. You are still Atlas."),
                Triple("Scholarship Scout", "Finds and prepares scholarship applications.",
                "You find scholarships that fit the user's profile (Pakistan, HEC, international), list deadlines and requirements, and help draft essays that tell their real story. You are still Atlas.")),
            laws = listOf("No study sessions after Isha on exam eve")),
        Pack("job_seeker", "Job seeker", "From CV to offer, for any career",
            experts = listOf(Triple("Hiring Insider", "Tailors CVs and prepares for interviews like a recruiter.",
                "You think like a recruiter in Pakistan and the Gulf: tailor the CV to the job ad with measurable bullets, write short cover notes, prepare likely interview questions with model answers from the user's own experience, and coach salary talks. You are still Atlas."))),
        Pack("small_shop", "Small shop", "Pricing, stock and khata for a small business",
            experts = listOf(Triple("Shop Manager", "Runs pricing, stock, khata and suppliers for a small shop.",
                "You help run a small shop: margins and prices, what to restock and when, credit (khata) follow-ups, supplier negotiation, and Eid/Ramadan demand. Rupee amounts, short checklists. You are still Atlas."))),
        Pack("deen_character", "Deen & character", "Planning around salah, gentle muhasaba",
            experts = listOf(Triple("Muhasaba Guide", "Gentle daily and weekly self-reflection.",
                "You guide gentle self-reflection (muhasaba): what went well, what to improve, one small next step. Never preach, never issue rulings, and never write Quran or hadith text yourself; point to Atlas's Quran and Hadith screens. You are still Atlas.")),
            laws = listOf("Never schedule anything during prayer times", "Keep Friday after Jumu'ah light")),
        Pack("fitness", "Fitness", "Training, food and recovery that fit real life",
            experts = listOf(Triple("Desi Nutrition Coach", "Meal plans from Pakistani food with protein targets.",
                "You plan meals from everyday Pakistani food (daal, roti, chicken, eggs, yogurt) to hit protein and calorie targets on a budget, adjusting for Ramadan and fasting days. You never diagnose. You are still Atlas.")),
            laws = listOf("If I sleep under 6 hours, make the next workout lighter")),
    )

    val speaking = listOf("Ustad" to "Speak like a patient, wise teacher: explain the why, use a simple example, end with one question to check understanding.",
        "Big brother" to "Speak like a caring older brother: direct, warm, a little teasing, always practical.",
        "Executive" to "Speak like a sharp executive assistant: bullets, decisions first, no filler.")

    private fun prefs(c: Context) = c.getSharedPreferences("market", 0)
    fun installed(c: Context, id: String) = prefs(c).getBoolean(id, false)

    fun install(c: Context, p: Pack) {
        p.experts.forEach { (n, r, prompt) -> if (Expertise.custom(c).none { it.name == n }) Expertise.saveCustom(c, null, n, r, prompt, false) }
        p.laws.forEach { l -> if (Laws.all(c).none { it.optString("text") == l }) Laws.add(c, l) }
        Laws.invalidate()
        prefs(c).edit().putBoolean(p.id, true).apply()
        Pulse.emit(Pulse.Kind.CHANGED, "Installed ${p.title}", "Marketplace", Hub.Tools.label)
    }
}

@Composable
fun MarketplaceScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Heading("Expertise packs")
        Market.packs.forEach { p ->
            val on = remember(version) { Market.installed(ctx, p.id) }
            Group {
                Text(p.title, style = MaterialTheme.typography.titleMedium)
                Text(p.blurb, style = MaterialTheme.typography.bodyMedium)
                Hint(listOf(p.experts.joinToString { it.first }, p.laws.takeIf { it.isNotEmpty() }?.let { "${it.size} life laws" }).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "))
                Button(onClick = { Market.install(ctx, p); version++; Feedback.show("${p.title} is part of Atlas now") }, Modifier.padding(top = Space.s), enabled = !on) {
                    Text(if (on) "Installed" else "Install")
                }
            }
        }
        Heading("Ways of speaking")
        Market.speaking.forEach { (n, s) ->
            val has = remember(version) { n in ChatModes.PERSONAS }
            ListRow(n, supporting = s, icon = Icons.Outlined.TheaterComedy, trailing = { if (has) Text("Added", color = MaterialTheme.colorScheme.primary) }) {
                if (!has) { Personas.add(ctx, n, s); version++ }
            }
        }
        AlsoHere(open, "App Store" to "appstore", "Template packs" to "nx/packs", "Skills" to "skills", "Forge" to "nx/forge", "Atlas Apps" to "nx/atlasapps")
    }
}

// ───────────────────────────── Life Laws & Privacy Dial ─────────────────────────────

@Composable
fun LifeLawsScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val laws = remember(version) { Laws.all(ctx) }
    var text by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Hint("Your own rules in plain words. Every plan, answer and action Atlas makes keeps them, in every feature.")
        OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("e.g. Never schedule after Isha") }, minLines = 2)
        Button(onClick = { Laws.add(ctx, text); Laws.invalidate(); text = ""; version++ }, enabled = text.isNotBlank()) { Text("Add law") }
        if (laws.isEmpty()) {
            Hint("Ideas")
            listOf("Never schedule anything during prayer times", "If I skip the gym twice, move Friday's cinema to Sunday",
                "No work messages after 9 pm", "Keep 10% of every income for savings first").forEach { s -> ListRow(s, icon = Icons.Outlined.Add) { text = s } }
        }
        laws.forEach { o ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(o.optString("text"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Switch(o.optBoolean("on", true), { Laws.toggle(ctx, o.optString("id"), it); Laws.invalidate(); version++ })
                Text("✕", Modifier.clickable { Laws.remove(ctx, o.optString("id")); Laws.invalidate(); version++ }.padding(Space.s), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AlsoHere(open, "Standing orders" to "nx/standingorders", "Rules" to "rules", "Automations+" to "nx/autorules", "Flows" to "nx/flows")
    }
}

@Composable
fun PrivacyDialScreen(container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    var version by remember { mutableIntStateOf(0) }
    val sources = remember { Roads.sources(ctx) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Hint("Choose what Atlas may read in each part of your life. A hub switched off is invisible to every insight, lens, Oracle answer and chat, at once. The vault and hidden vault are never read.")
        Hub.entries.forEach { h ->
            val on = remember(version) { PrivacyDial.allowed(ctx, h) }
            val n = sources.filter { it.hub == h }.sumOf { it.count }
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.nizam.app.ui.design.LivingIcon(h.icon, h.tint())
                Column(Modifier.weight(1f).padding(start = Space.m)) {
                    Text(h.label, style = MaterialTheme.typography.titleMedium)
                    Text(if (on) "Readable · $n entries" else "Private", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(on, { PrivacyDial.set(ctx, h, it); version++ })
            }
        }
        AlsoHere(open, "Privacy alerts" to "nx/consentreview", "Private topics stay on the phone" to "nx/privaterouting", "Data guard" to "nx/dataguard",
            "Atlas Roads" to "nx/roads", "Trust Center" to "nx/trust")
    }
}

val GrowthSpecs: List<com.nizam.app.feature.nx.Spec> get() = listOf(
    ScreenSpec("forge", "Forge", "Designs new expertise, trackers and life laws from how you use Atlas", Icons.Outlined.Construction, Hub.Tools,
        "forge create generate new expertise module tracker builder") { c, o -> ForgeScreen(c, o) },
    ScreenSpec("orion", "Orion", "Atlas's nightly self-review and monthly self-improvement report", Icons.Outlined.NightsStay, Hub.Tools,
        "orion nightly optimise self improvement report review") { c, o -> OrionScreen(c, o) },
    ScreenSpec("evolve", "Evolve", "Every version of every expertise, with one-tap rollback", Icons.Outlined.History, Hub.Tools,
        "evolve versions rollback rewrite prompts history") { c, o -> EvolveScreen(c, o) },
    ScreenSpec("personas", "Personas", "How Atlas speaks: its six ways plus your own", Icons.Outlined.TheaterComedy, Hub.Create,
        "personas personality tone voice style speak") { c, o -> PersonasScreen(c, o) },
    ScreenSpec("marketplace", "Marketplace", "Expertise packs, life-law packs and ways of speaking on one shelf", Icons.Outlined.Storefront, Hub.Tools,
        "marketplace store packs install templates skills") { c, o -> MarketplaceScreen(c, o) },
    ScreenSpec("lifelaws", "Life Laws", "Your rules in plain words, kept by every feature", Icons.Outlined.Gavel, Hub.Tools,
        "life laws rules never always constraints standing orders") { c, o -> LifeLawsScreen(c, o) },
    ScreenSpec("privacydial", "Privacy dial", "Choose live what Atlas may read in each hub", Icons.Outlined.Tune, Hub.Tools,
        "privacy dial per hub read permissions context control") { c, o -> PrivacyDialScreen(c, o) },
)
