package com.nizam.app.feature.hq

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.agent.Crew
import com.nizam.app.net.Ai
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.IsoFields

/**
 * The company grows up: quarterly goals with key results, monthly reviews with promotions you sign
 * off, a task marketplace where agents bid, a boardroom vote, morale and burnout, a red-team day and
 * office occasions.
 */
object CompanyPlus {
    private fun p(c: Context) = c.getSharedPreferences("company_plus", Context.MODE_PRIVATE)
    fun quarter(d: LocalDate = LocalDate.now()) = "${d.year}-Q${d.get(IsoFields.QUARTER_OF_YEAR)}"

    // ---------------------------------------------------------------- OKRs

    data class KeyResult(val text: String, val target: Double, val now: Double)
    data class Okr(val id: String, val objective: String, val krs: List<KeyResult>) {
        val progress get() = if (krs.isEmpty()) 0f else krs.map { (it.now / it.target.coerceAtLeast(1.0)).coerceIn(0.0, 1.0) }.average().toFloat()
    }

    fun okrs(c: Context, q: String = quarter()): List<Okr> = runCatching {
        val a = JSONArray(p(c).getString("okr_$q", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val k = o.optJSONArray("krs") ?: JSONArray()
            Okr(o.optString("id"), o.optString("o"), (0 until k.length()).map { k.getJSONObject(it) }.map { KeyResult(it.optString("t"), it.optDouble("target", 1.0), it.optDouble("now", 0.0)) })
        }
    }.getOrDefault(emptyList())

    fun saveOkrs(c: Context, list: List<Okr>, q: String = quarter()) {
        p(c).edit().putString("okr_$q", JSONArray(list.map { o ->
            JSONObject().put("id", o.id).put("o", o.objective).put("krs", JSONArray(o.krs.map { JSONObject().put("t", it.text).put("target", it.target).put("now", it.now) }))
        }).toString()).apply()
    }

    /** Departments propose measurable key results for an objective (one AI call). */
    suspend fun proposeKrs(container: AppContainer, objective: String): List<KeyResult> {
        val c = container.appContext
        val depts = Company.DEPTS.joinToString { it.name }
        val raw = Ai.generate(container.settingsRepository,
            "Company objective for this quarter: $objective\nDepartments: $depts\nPropose 3 measurable key results. Reply as lines: <key result> | <numeric target>",
            "You are a pragmatic COO. Key results must be measurable numbers a personal AI company can track.", 400)
        return raw.lines().mapNotNull { l -> val parts = l.trim().trimStart('-', '*', ' ', '1', '2', '3', '.', ')').split('|')
            val t = parts.getOrNull(0)?.trim().orEmpty(); val n = parts.getOrNull(1)?.filter { it.isDigit() || it == '.' }?.toDoubleOrNull()
            if (t.length > 4 && n != null && n > 0) KeyResult(t, n, 0.0) else null }.take(3)
            .also { runCatching { Company.note(c, "okr", "Key results proposed", it.joinToString("\n") { k -> "• ${k.text} → ${k.target}" }) } }
    }

    fun okrLine(c: Context): String = okrs(c).takeIf { it.isNotEmpty() }?.joinToString("\n") { "🎯 ${it.objective}: ${(it.progress * 100).toInt()}%" } ?: ""

    // ---------------------------------------------------------------- morale and burnout

    data class Mood(val score: Int, val label: String, val why: String)

    fun morale(c: Context, a: Crew.Agent): Mood {
        val s = Company.stats(c, a)
        val busy = a.id in Crew.busyAgents
        val weekLoad = s.weekTokens / 20_000
        var m = 70 + ((s.stars - 3f) * 12).toInt() - weekLoad * 6 - (if (busy) 4 else 0) + (if (s.failed > s.ok && s.failed > 2) -15 else 0)
        val since = System.currentTimeMillis() - s.lastActive
        if (s.lastActive > 0 && since > 3 * 86_400_000L) m -= 8
        m = m.coerceIn(5, 100)
        val label = when { m >= 80 -> "Thriving"; m >= 60 -> "Steady"; m >= 40 -> "Stretched"; else -> "Burning out" }
        val why = when {
            weekLoad >= 3 -> "Heavy week: ${"%,d".format(s.weekTokens)} tokens. Give them a lighter task or a break."
            s.failed > s.ok && s.failed > 2 -> "Several tools failed for them. Check their permissions."
            s.stars >= 4.2f -> "Doing great work and getting good ratings."
            s.lastActive > 0 && since > 3 * 86_400_000L -> "Idle for days. Give them something meaningful."
            else -> "Normal workload."
        }
        return Mood(m, label, why)
    }

    fun giveBreak(c: Context, a: Crew.Agent) { p(c).edit().putLong("break_${a.id}", System.currentTimeMillis() + 4 * 3_600_000L).apply() }
    fun onBreak(c: Context, id: String) = p(c).getLong("break_$id", 0L) > System.currentTimeMillis()

    // ---------------------------------------------------------------- marketplace

    data class Bid(val agent: Crew.Agent, val score: Int, val why: String)

    /** Every agent bids on a task by fit (department and role words), workload, rating and morale. */
    fun bids(c: Context, task: String): List<Bid> {
        val words = task.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 3 }.toSet()
        return Crew.agents(c).filter { !it.lead }.map { a ->
            val prof = Company.profile(c, a)
            val dept = Company.DEPTS.firstOrNull { it.id == prof.dept }
            val roleHits = words.count { w -> a.role.lowercase().contains(w) || a.instructions.lowercase().contains(w) || (dept?.name?.lowercase()?.contains(w) == true) }
            val s = Company.stats(c, a)
            val mood = morale(c, a)
            val busy = a.id in Crew.busyAgents || onBreak(c, a.id)
            val score = (roleHits * 18 + (s.stars * 8).toInt() + prof.level * 6 + mood.score / 5 - (if (busy) 35 else 0)).coerceAtLeast(0)
            val why = buildList {
                if (roleHits > 0) add("$roleHits skill match${if (roleHits > 1) "es" else ""}")
                add("${"%.1f".format(s.stars)}★")
                add(Company.LEVELS[prof.level.coerceIn(0, Company.LEVELS.lastIndex)])
                if (busy) add("busy") else add(mood.label.lowercase())
            }.joinToString(" · ")
            Bid(a, score, why)
        }.sortedByDescending { it.score }
    }

    // ---------------------------------------------------------------- reviews

    data class Review(val agent: Crew.Agent, val month: String, val verdict: String, val text: String, val promote: Boolean)

    /** Monthly reviews written from the record (no AI cost): output, quality, ratings, reliability. */
    fun reviews(c: Context): List<Review> {
        val month = LocalDate.now().toString().take(7)
        return Crew.agents(c).filter { !it.lead }.map { a ->
            val s = Company.stats(c, a); val prof = Company.profile(c, a)
            val reliability = if (s.ok + s.failed == 0) 100 else s.ok * 100 / (s.ok + s.failed)
            val promote = s.stars >= 4.2f && s.done >= 8 && reliability >= 85 && prof.level < Company.LEVELS.lastIndex
            val verdict = when { promote -> "Exceeds expectations"; s.stars >= 3.4f -> "Meets expectations"; s.done == 0 -> "Not enough work to judge"; else -> "Needs support" }
            Review(a, month, verdict,
                "${s.done} pieces of work · ${reliability}% tools succeeded · rated ${"%.1f".format(s.stars)}★ · ${"%,d".format(s.weekTokens)} tokens this week." +
                    if (promote) " Recommended for promotion to ${Company.LEVELS[prof.level + 1]}." else "", promote)
        }
    }

    // ---------------------------------------------------------------- boardroom vote

    data class Vote(val who: String, val choice: String, val reason: String)

    suspend fun boardVote(container: AppContainer, question: String, options: List<String>): List<Vote> {
        val c = container.appContext
        val members = Crew.agents(c).take(7)
        val roster = members.joinToString("\n") { "${it.id}|${it.emoji} ${it.name}|${it.role}" }
        val raw = Ai.generate(container.settingsRepository,
            "Decision: $question\nOptions: ${options.joinToString(" / ")}\nBoard members (id|name|role):\n$roster\n\n" +
                "Each member votes from their own role's perspective. Reply one line per member: <id> | <exact option> | <one-sentence reason>",
            "You simulate a thoughtful board with distinct, honest viewpoints. Disagreement is welcome.", 900)
        return raw.lines().mapNotNull { l -> val parts = l.split('|').map { it.trim() }
            val m = members.firstOrNull { it.id == parts.getOrNull(0) } ?: return@mapNotNull null
            val choice = options.firstOrNull { it.equals(parts.getOrNull(1), true) } ?: options.firstOrNull { parts.getOrNull(1)?.contains(it, true) == true } ?: return@mapNotNull null
            Vote("${m.emoji} ${m.name}", choice, parts.getOrNull(2).orEmpty()) }
            .also { v -> runCatching { Company.note(c, "vote", "Board vote: $question", v.joinToString("\n") { "${it.who}: ${it.choice} — ${it.reason}" }) } }
    }

    // ---------------------------------------------------------------- red-team day

    suspend fun redTeam(container: AppContainer): String {
        val c = container.appContext
        val agents = Crew.agents(c).joinToString("\n") { "${it.name} (${Company.LEVELS[Company.profile(c, it).level.coerceIn(0, 4)]}): tools ${it.tools.joinToString()}" }
        val orders = runCatching { c.getSharedPreferences("standing_orders", 0).all.values.joinToString("\n").take(3000) }.getOrDefault("")
        val out = Ai.generate(container.settingsRepository,
            "Agents and their tool permissions:\n$agents\n\nStanding orders / automations (raw):\n$orders\n\nPending approvals: ${com.nizam.app.feature.agent.ApprovalInbox.pending(c).size}",
            "You are a red-team security reviewer for a personal AI company. Find concrete weak spots: over-broad permissions, automations that could misfire or loop, approvals that should be required, prompt-injection risks from untrusted inputs. Give a ranked list with the fix for each. Be specific and brief.", 1400)
        Company.note(c, "redteam", "Red-team day ${LocalDate.now()}", out)
        p(c).edit().putString("redteam_last", LocalDate.now().toString()).apply()
        return out
    }

    // ---------------------------------------------------------------- occasions

    /** The occasion the office is dressed for today, if any. */
    fun occasion(): String? {
        val hijri = runCatching { java.time.chrono.HijrahDate.now() }.getOrNull()
        val hm = hijri?.get(java.time.temporal.ChronoField.MONTH_OF_YEAR); val hd = hijri?.get(java.time.temporal.ChronoField.DAY_OF_MONTH)
        val d = LocalDate.now()
        return when {
            hm == 9 -> "🌙 Ramadan Kareem: iftar table on the ground floor, office closes early"
            hm == 10 && (hd ?: 9) <= 3 -> "🎉 Eid Mubarak: lights, sweets and a half day"
            hm == 12 && (hd ?: 0) in 10..13 -> "🐐 Eid al-Adha Mubarak"
            hm == 3 && hd == 12 -> "✨ 12 Rabi' al-Awwal: green lights in the lobby"
            d.monthValue == 8 && d.dayOfMonth == 14 -> "🇵🇰 Independence Day: flags on every floor"
            d.monthValue == 3 && d.dayOfMonth == 23 -> "🇵🇰 Pakistan Day"
            d.dayOfWeek == java.time.DayOfWeek.FRIDAY -> "🕌 Jumu'ah: the office pauses for prayer at noon"
            else -> null
        }
    }
}

// ====================================================================== UI

@Composable
fun GoalsTab(container: AppContainer) {
    val c = LocalContext.current
    val work = com.nizam.app.ui.design.rememberWorkScope()
    var list by remember { mutableStateOf(CompanyPlus.okrs(c)) }
    var objective by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Goals for ${CompanyPlus.quarter()}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Set an objective; departments propose key results; the all-hands reports progress.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(objective, { objective = it }, Modifier.fillMaxWidth().padding(top = 6.dp), placeholder = { Text("e.g. Get my finances in order") }, singleLine = true)
            Button(onClick = {
                val o = objective.trim(); busy = true
                work.launch {
                    val krs = runCatching { CompanyPlus.proposeKrs(container, o) }.getOrDefault(emptyList())
                    list = list + CompanyPlus.Okr("o" + System.currentTimeMillis(), o, krs.ifEmpty { listOf(CompanyPlus.KeyResult("Progress", 10.0, 0.0)) })
                    CompanyPlus.saveOkrs(c, list); objective = ""; busy = false
                }
            }, enabled = objective.isNotBlank() && !busy, modifier = Modifier.padding(top = 6.dp)) { Text(if (busy) "Departments are proposing…" else "Set objective") }
        }
        items(list, key = { it.id }) { o ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("🎯 ${o.objective}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(progress = { o.progress }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    o.krs.forEachIndexed { i, kr ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${kr.text} · ${kr.now.toInt()}/${kr.target.toInt()}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = {
                                list = list.map { x -> if (x.id != o.id) x else x.copy(krs = x.krs.mapIndexed { j, k -> if (j == i) k.copy(now = k.now + 1) else k }) }
                                CompanyPlus.saveOkrs(c, list)
                            }) { Text("+1") }
                        }
                    }
                    TextButton(onClick = { list = list.filter { it.id != o.id }; CompanyPlus.saveOkrs(c, list) }) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
fun PeopleOpsTab(container: AppContainer, openAgent: (String) -> Unit) {
    val c = LocalContext.current
    val work = com.nizam.app.ui.design.rememberWorkScope()
    val version = Crew.version
    var task by remember { mutableStateOf("") }
    var bids by remember { mutableStateOf<List<CompanyPlus.Bid>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    val reviews = remember(version, tick) { CompanyPlus.reviews(c) }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        CompanyPlus.occasion()?.let { o -> item { Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.tertiaryContainer).padding(12.dp)) { Text(o) } } }
        item {
            Text("🏷️ Task marketplace", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Post a task; agents bid on fit, rating, level and workload. The winner starts it.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(task, { task = it }, Modifier.fillMaxWidth().padding(top = 6.dp), placeholder = { Text("e.g. Research the best home internet in Lahore") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { bids = CompanyPlus.bids(c, task) }, enabled = task.isNotBlank()) { Text("Collect bids") }
                if (bids.isNotEmpty()) Button(onClick = { Crew.start(container, task.trim(), listOf(Crew.agents(c).first { it.lead }.id, bids.first().agent.id)); task = ""; bids = emptyList() }) {
                    Text("Give it to ${bids.first().agent.name}")
                }
            }
        }
        items(bids.take(5)) { b ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("${b.agent.emoji} ${b.agent.name}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Text("${b.score} · ${b.why}", style = MaterialTheme.typography.labelSmall)
            }
        }
        item { Text("💚 Morale", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
        items(Crew.agents(c).filter { !it.lead }) { a ->
            val m = CompanyPlus.morale(c, a)
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${a.emoji} ${a.name}", Modifier.weight(1f))
                    Text("${m.label} ${m.score}", style = MaterialTheme.typography.labelMedium,
                        color = if (m.score < 40) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
                }
                LinearProgressIndicator(progress = { m.score / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(m.why, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (m.score < 60) TextButton(onClick = { CompanyPlus.giveBreak(c, a); tick++ }) { Text(if (CompanyPlus.onBreak(c, a.id)) "On break" else "Give a break") }
                }
            }
        }
        item { Text("📝 Monthly reviews", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp)) }
        items(reviews) { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("${r.agent.emoji} ${r.agent.name} · ${r.verdict}", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    Text(r.text, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { openAgent(r.agent.id) }) { Text("Open profile") }
                        if (r.promote) Button(onClick = { Company.setLevel(c, r.agent.id, Company.profile(c, r.agent).level + 1); tick++ }) { Text("Approve promotion") }
                    }
                }
            }
        }
    }
}

@Composable
fun BoardroomTab(container: AppContainer) {
    val work = com.nizam.app.ui.design.rememberWorkScope()
    var q by remember { mutableStateOf("") }
    var opts by remember { mutableStateOf("Yes, No") }
    var votes by remember { mutableStateOf<List<CompanyPlus.Vote>>(emptyList()) }
    var report by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("🏛️ Boardroom vote", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Should I buy a car this year?") })
            OutlinedTextField(opts, { opts = it }, Modifier.fillMaxWidth().padding(top = 4.dp), label = { Text("Options, comma separated") }, singleLine = true)
            Button(onClick = {
                busy = true
                work.launch { votes = runCatching { CompanyPlus.boardVote(container, q, opts.split(',').map { it.trim() }.filter { it.isNotEmpty() }) }.getOrDefault(emptyList()); busy = false }
            }, enabled = q.isNotBlank() && !busy, modifier = Modifier.padding(top = 6.dp)) { Text(if (busy) "The board is deliberating…" else "Call the vote") }
        }
        if (votes.isNotEmpty()) item {
            val tally = votes.groupingBy { it.choice }.eachCount().entries.sortedByDescending { it.value }
            Text("Result: " + tally.joinToString(" · ") { "${it.key} ${it.value}" }, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text("You have the final say.", style = MaterialTheme.typography.labelSmall)
        }
        items(votes) { v -> Text("${v.who}: ${v.choice} — ${v.reason}", style = MaterialTheme.typography.bodySmall) }
        item {
            Text("🛡️ Red-team day", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Text("One agent tries to break your permissions and automations, then reports the weak spots. Filed in the wiki.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { busy = true; work.launch { report = runCatching { CompanyPlus.redTeam(container) }.getOrElse { it.message }; busy = false } }, enabled = !busy) { Text("Run the red team") }
            report?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
        }
        item {
            Text("📈 Quarterly review", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Text("A written review of the quarter, delivered to your shelf as a PDF.", style = MaterialTheme.typography.bodySmall)
            val c = LocalContext.current
            OutlinedButton(onClick = {
                val facts = Company.allHandsRecap(c) + "\n" + CompanyPlus.okrLine(c) + "\n" + CompanyPlus.reviews(c).joinToString("\n") { "${it.agent.name}: ${it.verdict} — ${it.text}" }
                com.nizam.app.feature.studio.Deliverables.order(container,
                    "Write the quarterly review for my personal AI company (${CompanyPlus.quarter()}) from these facts only:\n$facts\nSections: highlights, goals and key results, people (best performer, promotions), problems, next quarter's priorities.",
                    com.nizam.app.feature.studio.Deliverables.Kind.DOCUMENT)
                com.nizam.app.ui.components.Feedback.show("The quarterly review is being written. It'll be on your Deliverables shelf.")
            }) { Text("Write the quarterly review") }
        }
    }
}
