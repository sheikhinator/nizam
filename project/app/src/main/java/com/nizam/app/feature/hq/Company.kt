package com.nizam.app.feature.hq

import android.content.Context
import com.nizam.app.AppContainer
import com.nizam.app.feature.agent.ApprovalInbox
import com.nizam.app.feature.agent.Crew
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.wild.AgentMeter
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.IsoFields

/**
 * Atlas HQ as a company: departments, an org chart, levels and personalities, performance records,
 * a working day with stand-ups and all-hands, a task board, a company wiki and office chat.
 * Everything here is worked out from what the agent team really did; nothing costs AI calls
 * except real work, meetings you call and hiring.
 */
object Company {
    data class Dept(val id: String, val name: String, val emoji: String, val floor: Int, val color: String)
    val DEPTS = listOf(
        Dept("exec", "Executive", "🏢", 0, "#6366f1"), Dept("plan", "Planning & Home", "🗓️", 1, "#10b981"),
        Dept("fin", "Finance", "💰", 2, "#f59e0b"), Dept("deen", "Deen", "🕌", 3, "#14b8a6"),
        Dept("research", "Research & Comms", "🔎", 4, "#0ea5e9"), Dept("ops", "Operations", "🛠️", 5, "#8b5cf6"))
    const val OWNER_FLOOR = 6
    val LEVELS = listOf("Intern", "Associate", "Senior", "Lead", "Chief")
    val LEVEL_COLORS = listOf(0x22C55E, 0x0EA5E9, 0x8B5CF6, 0xF59E0B, 0xEF4444)

    data class Persona(val id: String, val name: String, val style: String, val idle: List<String>, val greet: List<String>)
    val PERSONAS = listOf(
        Persona("careful", "Careful", "precise, double-checks, cautious about risk",
            listOf("Let me check that twice.", "Did we source this?", "Small steps.", "I'll note that in the wiki."), listOf("Salam, boss. All in order.", "Good to see you, boss.")),
        Persona("bold", "Bold", "confident, decisive, pushes for action",
            listOf("Let's ship it.", "Bigger goals!", "I say we go for it.", "Done by lunch."), listOf("Boss! Big things today.", "Salam! Let's win today.")),
        Persona("funny", "Funny", "warm and witty, light jokes, never at the user's expense",
            listOf("Who ate my chai?", "My laptop needs a holiday.", "Coffee first, genius later.", "The dog is the real CEO."), listOf("Boss in the building! 🎉", "Salam! Chai's on me.")),
        Persona("blunt", "Blunt", "direct, short sentences, says what's wrong",
            listOf("That plan is weak.", "Numbers don't lie.", "Skip the fluff.", "Fix it now."), listOf("Boss.", "Salam. Need anything?")),
        Persona("warm", "Warm", "kind, encouraging, family-minded",
            listOf("How's everyone doing?", "Nice work, team!", "Take a break, yaar.", "Ammi would love this plan."), listOf("Assalam o alaikum! How are you?", "Salam, boss! Hope your day's good.")),
        Persona("quiet", "Quiet", "thoughtful, few words, deep focus",
            listOf("Hmm.", "Thinking…", "Interesting.", "One sec."), listOf("Salam.", "Hi, boss.")))
    fun persona(id: String) = PERSONAS.firstOrNull { it.id == id } ?: PERSONAS[0]

    // ───────────── people ─────────────
    data class Profile(val id: String, val dept: String, val level: Int, val reportsTo: String?, val persona: String,
                       val hiredAt: Long, val up: Int = 0, val down: Int = 0, val promotedAt: Long = 0L) {
        fun json(): JSONObject = JSONObject().put("id", id).put("dept", dept).put("level", level).put("reportsTo", reportsTo ?: "")
            .put("persona", persona).put("hiredAt", hiredAt).put("up", up).put("down", down).put("promotedAt", promotedAt)
        companion object {
            fun of(o: JSONObject) = Profile(o.optString("id"), o.optString("dept", "research"), o.optInt("level", 1), o.optString("reportsTo").ifBlank { null },
                o.optString("persona", "careful"), o.optLong("hiredAt"), o.optInt("up"), o.optInt("down"), o.optLong("promotedAt"))
        }
    }
    private fun people(c: Context) = JsonStore(c, "company_people")

    private val DEFAULTS = mapOf(
        "lead" to Triple("exec", 4, "warm"), "critic" to Triple("exec", 3, "blunt"), "planner" to Triple("plan", 3, "careful"),
        "analyst" to Triple("fin", 3, "careful"), "scout" to Triple("research", 3, "bold"), "writer" to Triple("research", 2, "funny"),
        "operator" to Triple("ops", 3, "quiet"))

    fun deptFor(a: Crew.Agent): String {
        val s = (a.role + " " + a.instructions + " " + a.name).lowercase()
        return when {
            a.lead -> "exec"
            listOf("money", "financ", "budget", "spend", "invest", "bill", "tax").any { it in s } -> "fin"
            listOf("islam", "deen", "quran", "salah", "prayer", "dua", "hadith", "fiqh").any { it in s } -> "deen"
            listOf("plan", "family", "home", "health", "cook", "remind", "calendar", "schedule").any { it in s } -> "plan"
            listOf("operat", "apps", "connector", "automat", "code", "pc", "server", "device").any { it in s } -> "ops"
            listOf("review", "critic", "quality").any { it in s } -> "exec"
            else -> "research"
        }
    }

    fun profile(c: Context, a: Crew.Agent): Profile {
        people(c).all().firstOrNull { it.optString("id") == a.id }?.let { return Profile.of(it) }
        val d = DEFAULTS[a.id]
        val dept = d?.first ?: deptFor(a)
        val p = Profile(a.id, dept, d?.second ?: 1, null, d?.third ?: PERSONAS[(a.id.hashCode() and 0x7fffffff) % PERSONAS.size].id, System.currentTimeMillis())
        save(c, p)
        return p
    }
    fun save(c: Context, p: Profile) { val s = people(c); s.save(s.all().filter { it.optString("id") != p.id } + p.json()); Crew.version++ }

    /** Who each person reports to: team members to their department's head, heads to the CEO, the CEO to you. */
    fun manager(c: Context, id: String): String? {
        val team = Crew.agents(c)
        val me = team.firstOrNull { it.id == id } ?: return null
        if (me.lead) return null
        val p = profile(c, me)
        p.reportsTo?.let { r -> if (team.any { it.id == r }) return r }
        val head = head(c, p.dept)
        return if (head != null && head != id) head else team.firstOrNull { it.lead }?.id
    }
    fun head(c: Context, dept: String): String? =
        Crew.agents(c).map { it to profile(c, it) }.filter { it.second.dept == dept }.maxByOrNull { it.second.level * 1000 - (it.second.hiredAt / 1_000_000_000L).toInt() }?.first?.id

    // ───────────── performance ─────────────
    data class Stats(val done: Int, val ok: Int, val failed: Int, val weekTokens: Int, val lastActive: Long, val rating: Float, val stars: Float)
    fun stats(c: Context, a: Crew.Agent): Stats {
        var done = 0; var ok = 0; var failed = 0; var last = 0L
        Crew.threads(c).all().forEach { t ->
            val m = t.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until m.length()) {
                val x = m.getJSONObject(i)
                if (x.optString("from") != a.id) continue
                last = maxOf(last, x.optLong("t"))
                when (x.optString("kind")) {
                    "final" -> done++
                    "tool" -> if (x.optString("text").startsWith("✓")) ok++ else if (x.optString("text").startsWith("✕")) failed++
                    "msg" -> if (!a.lead) done++
                }
            }
        }
        val p = profile(c, a)
        val rating = if (p.up + p.down == 0) 0f else p.up.toFloat() / (p.up + p.down)
        val success = if (ok + failed == 0) 0.8f else ok.toFloat() / (ok + failed)
        val stars = (2.5f + success * 1.5f + (if (p.up + p.down > 0) (rating - 0.5f) * 2f else 0f) + minOf(1f, done / 20f)).coerceIn(1f, 5f)
        return Stats(done, ok, failed, AgentMeter.week(c, a.name), last, rating, stars)
    }
    fun rate(c: Context, id: String, up: Boolean) {
        val a = Crew.agents(c).firstOrNull { it.id == id } ?: return
        val p = profile(c, a)
        save(c, if (up) p.copy(up = p.up + 1) else p.copy(down = p.down + 1))
    }

    // ───────────── levels, hiring and letting go ─────────────
    /** What a level may use: interns draft and research only; leads can hand work out. */
    private fun toolsFor(level: Int, tools: Set<String>): Set<String> = when {
        level <= 0 -> tools - setOf("apps", "connectors", "delegate")
        level >= 3 -> tools + "delegate"
        else -> tools
    }
    fun setLevel(c: Context, id: String, level: Int) {
        val a = Crew.agents(c).firstOrNull { it.id == id } ?: return
        val p = profile(c, a)
        val l = level.coerceIn(0, LEVELS.size - 1)
        save(c, p.copy(level = l, promotedAt = System.currentTimeMillis()))
        if (!a.lead) Crew.saveAgent(c, a.copy(tools = toolsFor(l, a.tools)))
        note(c, "people", "${a.name} is now ${LEVELS[l]}", "${a.emoji} ${a.name} moved from ${LEVELS[p.level]} to ${LEVELS[l]}.")
        say(a.id, "", if (l > p.level) "Alhamdulillah, thank you boss! 🙏" else "Understood. I'll do better.")
    }
    fun letGo(c: Context, id: String) {
        val a = Crew.agents(c).firstOrNull { it.id == id } ?: return
        if (a.lead) return
        Crew.deleteAgent(c, id)
        people(c).remove(id)
        note(c, "people", "${a.name} left the company", "${a.emoji} ${a.name} (${a.role}) was let go.")
        leaving[id] = System.currentTimeMillis()
    }
    /** Agents who just left, so the office can show them walking out with a box. */
    val leaving = HashMap<String, Long>()

    suspend fun hire(container: AppContainer, purpose: String, dept: String?): List<Crew.Agent> {
        val c = container.appContext
        val made = Crew.design(container, purpose)
        made.forEach { a ->
            save(c, Profile(a.id, dept ?: deptFor(a), 1, null, PERSONAS[(a.id.hashCode() and 0x7fffffff) % PERSONAS.size].id, System.currentTimeMillis()))
            note(c, "people", "Welcome ${a.name}", "${a.emoji} ${a.name} joined as ${a.role}.")
            say(a.id, "", "Salam everyone! Happy to be here.")
        }
        return made
    }

    data class Proposal(val kind: String, val id: String, val text: String)
    /** Suggestions for you: promotions earned by good work, and hiring when work piles up. */
    fun proposals(c: Context): List<Proposal> {
        val out = ArrayList<Proposal>()
        val team = Crew.agents(c)
        team.filter { !it.lead }.forEach { a ->
            val p = profile(c, a); val s = stats(c, a)
            val need = listOf(3, 10, 25, 50).getOrElse(p.level) { 999 }
            if (p.level < 3 && s.done >= need && s.stars >= 3.8f) out += Proposal("promote", a.id, "${a.emoji} ${a.name} has done ${s.done} tasks at ${"%.1f".format(s.stars)}★ — promote to ${LEVELS[p.level + 1]}?")
            if (p.level > 0 && s.stars < 2.2f && s.ok + s.failed >= 6) out += Proposal("coach", a.id, "${a.emoji} ${a.name} is struggling (${"%.1f".format(s.stars)}★). Coach, move or let go?")
        }
        val backlog = Hq.queued(c).size
        if (backlog >= 4) out += Proposal("hire", "", "$backlog tasks are waiting — hire another teammate?")
        return out
    }

    // ───────────── the working day ─────────────
    private fun p(c: Context) = c.getSharedPreferences("company", 0)
    fun startHour(c: Context) = p(c).getInt("start", 9)
    fun endHour(c: Context) = p(c).getInt("end", 22)
    fun setHours(c: Context, s: Int, e: Int) = p(c).edit().putInt("start", s).putInt("end", e).apply()
    fun flag(c: Context, k: String, def: Boolean = true) = p(c).getBoolean(k, def)
    fun setFlag(c: Context, k: String, v: Boolean) = p(c).edit().putBoolean(k, v).apply()
    fun dailyCap(c: Context) = p(c).getInt("cap", 0)
    fun setDailyCap(c: Context, v: Int) {
        p(c).edit().putInt("cap", v).apply()
        val team = Crew.agents(c)
        if (team.isNotEmpty()) team.forEach { AgentMeter.setCap(c, it.name, if (v <= 0) 0 else v / team.size) }
    }
    fun open(c: Context, now: LocalTime = LocalTime.now()): Boolean { val h = now.hour; val s = startHour(c); val e = endHour(c); return if (s <= e) h in s until e else h >= s || h < e }

    /** Today's stand-up, run once a day after opening: everyone gathers at the table and gives a one-liner. */
    fun standupDue(c: Context): Boolean {
        if (!flag(c, "standup") || !open(c)) return false
        val now = LocalTime.now()
        return p(c).getString("standup_day", "") != LocalDate.now().toString() && now.hour >= startHour(c) && (now.hour > startHour(c) || now.minute >= 15)
    }
    fun standupLines(c: Context): List<Pair<String, String>> {
        val day = System.currentTimeMillis() - 24 * 3_600_000L
        val threads = Crew.threads(c).all()
        return Crew.agents(c).map { a ->
            var y = 0
            threads.forEach { t -> val m = t.optJSONArray("messages") ?: JSONArray(); for (i in 0 until m.length()) { val x = m.getJSONObject(i); if (x.optString("from") == a.id && x.optLong("t") > day && x.optString("kind") in setOf("final", "msg", "tool")) y++ } }
            val waiting = ApprovalInbox.pending(c).count { ap -> threads.any { it.optString("id") == ap.optString("thread") && (it.optJSONArray("members")?.toString()?.contains("\"${a.id}\"") == true) } }
            val today = Hq.queued(c).count { it.optString("agent") == a.id }
            a.id to buildString {
                append(if (y > 0) "Yesterday: $y updates. " else "Quiet yesterday. ")
                append(if (today > 0) "Today: $today queued. " else "Today: ready for work. ")
                append(if (waiting > 0) "Blocked on $waiting approval${if (waiting > 1) "s" else ""}." else "No blockers.")
            }
        }
    }
    fun markStandup(c: Context, lines: List<Pair<String, String>>) {
        p(c).edit().putString("standup_day", LocalDate.now().toString()).apply()
        val names = Crew.agents(c).associate { it.id to "${it.emoji} ${it.name}" }
        note(c, "standup", "Stand-up ${LocalDate.now()}", lines.joinToString("\n") { "• ${names[it.first] ?: it.first}: ${it.second}" })
    }

    /** Friday afternoon: an all-hands recap of the week. */
    fun allHandsDue(c: Context): Boolean {
        if (!flag(c, "allhands")) return false
        val d = LocalDate.now()
        val wk = "${d.get(IsoFields.WEEK_BASED_YEAR)}-${d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"
        return d.dayOfWeek == DayOfWeek.FRIDAY && LocalTime.now().hour >= 16 && p(c).getString("allhands_week", "") != wk
    }
    fun allHandsRecap(c: Context): String {
        val week = System.currentTimeMillis() - 7 * 24 * 3_600_000L
        val threads = Crew.threads(c).all()
        val done = threads.count { it.optString("status") == "done" && it.optLong("updated") > week }
        val team = Crew.agents(c)
        val best = team.maxByOrNull { stats(c, it).stars * 10 + stats(c, it).done }
        val tokens = team.sumOf { AgentMeter.week(c, it.name) }
        return "This week: $done goals finished · ${"%,d".format(tokens)} tokens used · ${ApprovalInbox.pending(c).size} approvals waiting." +
            (best?.let { "\nStar of the week: ${it.emoji} ${it.name}" } ?: "") +
            CompanyPlus.okrLine(c).let { if (it.isBlank()) "" else "\n$it" } + (CompanyPlus.occasion()?.let { "\n$it" } ?: "")
    }
    fun markAllHands(c: Context, recap: String) {
        val d = LocalDate.now()
        p(c).edit().putString("allhands_week", "${d.get(IsoFields.WEEK_BASED_YEAR)}-${d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}").apply()
        note(c, "allhands", "All-hands, week ${d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}", recap)
    }

    // ───────────── the task board ─────────────
    data class Card(val title: String, val who: String, val thread: String?, val at: Long, val urgent: Boolean)
    data class Board(val backlog: List<Card>, val doing: List<Card>, val review: List<Card>, val done: List<Card>)
    private val URGENT = Regex("\\b(urgent|asap|now|today|immediately|jaldi)\\b", RegexOption.IGNORE_CASE)
    fun isUrgent(text: String) = URGENT.containsMatchIn(text)
    fun board(c: Context): Board {
        val names = Crew.agents(c).associate { it.id to "${it.emoji} ${it.name}" }
        val threads = Crew.threads(c).all()
        val pending = ApprovalInbox.pending(c).map { it.optString("thread") }.toSet()
        fun card(t: JSONObject): Card {
            val goal = t.optString("goal").substringAfter("Topic: ").lines().first().take(80)
            val busy = Crew.busyAgents.filter { it.value == t.optString("id") }.keys.firstOrNull()
            return Card(goal, busy?.let { names[it] } ?: "🧭 Atlas", t.optString("id"), t.optLong("updated", t.optLong("created")), isUrgent(goal))
        }
        val backlog = Hq.queued(c).map { q -> Card(q.optString("text").take(80), names[q.optString("agent")] ?: "Unassigned", null, q.optLong("at"), isUrgent(q.optString("text"))) }
        val doing = threads.filter { it.optString("status") == "running" }.map(::card)
        val review = threads.filter { it.optString("status") in setOf("needs you", "waiting") || it.optString("id") in pending }.map(::card)
        val done = threads.filter { it.optString("status") == "done" && System.currentTimeMillis() - it.optLong("updated") < 48 * 3_600_000L }.map(::card)
        return Board(backlog, doing.sortedByDescending { it.at }, review.sortedByDescending { it.at }, done.sortedByDescending { it.at })
    }

    // ───────────── company memory ─────────────
    fun wiki(c: Context) = JsonStore(c, "company_wiki")
    fun note(c: Context, kind: String, title: String, body: String) {
        wiki(c).add(JSONObject().put("id", System.nanoTime().toString()).put("kind", kind).put("title", title).put("body", body.take(4000)).put("at", System.currentTimeMillis()))
    }
    /** Files a meeting's minutes once its thread has finished. */
    fun fileMinutes(c: Context) {
        val filed = p(c).getStringSet("minutes", emptySet()) ?: emptySet()
        val tid = Hq.tableThread(c) ?: return
        if (tid in filed) return
        val t = Crew.thread(c, tid) ?: return
        if (t.optString("status") != "done") return
        val m = t.optJSONArray("messages") ?: JSONArray()
        val final = (0 until m.length()).map { m.getJSONObject(it) }.lastOrNull { it.optString("kind") == "final" }?.optString("text").orEmpty()
        note(c, "minutes", "Minutes: " + t.optString("goal").substringAfter("Topic: ").take(60), final.ifBlank { "Meeting ended without a written decision." })
        p(c).edit().putStringSet("minutes", filed + tid).apply()
    }

    /** What an agent knows about its place in the company; added to its instructions for real work. */
    fun promptFor(c: Context, a: Crew.Agent): String {
        val p = profile(c, a)
        val dept = DEPTS.firstOrNull { it.id == p.dept }?.name ?: "Research"
        val boss = manager(c, a.id)?.let { m -> Crew.agents(c).firstOrNull { it.id == m }?.name } ?: "the owner (the user)"
        val per = persona(p.persona)
        return "\n\nCompany: you are ${LEVELS[p.level]} in $dept at Atlas HQ and report to $boss. Your manner: ${per.style} — let it show in your wording, but stay useful." +
            (if (p.level == 0) " As an intern, propose rather than act, and ask your manager before anything risky." else "")
    }

    /** Adds the quality gate and useful company notes to a new goal. */
    fun decorateGoal(c: Context, goal: String): String {
        val words = goal.lowercase().split(Regex("\\W+")).filter { it.length > 4 }.toSet()
        val notes = wiki(c).all().filter { it.optString("kind") in setOf("minutes", "decision", "lesson") }
            .map { it to it.optString("title").lowercase().split(Regex("\\W+")).count { w -> w in words } }.filter { it.second > 0 }
            .sortedByDescending { it.second }.take(2).map { it.first }
        return buildString {
            append(goal)
            if (flag(c, "quality")) append("\n\n(Company rule: before the final answer, have Critic review anything important.)")
            if (notes.isNotEmpty()) append("\n\nCompany notes that may help:\n" + notes.joinToString("\n") { "- " + it.optString("title") + ": " + it.optString("body").take(300) })
        }
    }

    // ───────────── office chat ─────────────
    data class Chat(val from: String, val to: String, val text: String, val at: Long)
    val chat = ArrayDeque<Chat>()
    @Synchronized fun say(from: String, to: String, text: String) {
        if (chat.lastOrNull()?.let { it.from == from && it.text == text } == true) return
        chat.addLast(Chat(from, to, text, System.currentTimeMillis()))
        while (chat.size > 60) chat.removeFirst()
    }
    /** Turns what the team really did into short office chatter, like a team channel. */
    private val seenMsgs = HashSet<String>()
    fun syncChat(c: Context) {
        val names = Crew.agents(c).associate { it.id to it.name }
        Crew.threads(c).all().sortedByDescending { it.optLong("updated") }.take(4).forEach { t ->
            val m = t.optJSONArray("messages") ?: return@forEach
            for (i in maxOf(0, m.length() - 6) until m.length()) {
                val x = m.getJSONObject(i)
                val key = t.optString("id") + ":" + x.optLong("t") + ":" + x.optString("from")
                if (!seenMsgs.add(key)) continue
                if (System.currentTimeMillis() - x.optLong("t") > 10 * 60_000L) continue
                val from = x.optString("from"); val to = x.optString("to")
                val line = when (x.optString("kind")) {
                    "delegate" -> "${names[to] ?: "someone"}, can you take this? " + x.optString("text").take(70)
                    "final" -> "Done ✅ " + x.optString("text").take(70)
                    "question" -> "Boss, quick question: " + x.optString("text").take(70)
                    "hire" -> x.optString("text").replace("*", "").take(80)
                    "msg" -> x.optString("text").replace("*", "").take(90)
                    else -> null
                } ?: continue
                say(from, to, line)
            }
        }
        if (seenMsgs.size > 2000) seenMsgs.clear()
    }

    /** Who works well together: how often two people handed each other work. */
    fun friends(c: Context): Map<String, List<String>> {
        val count = HashMap<Pair<String, String>, Int>()
        Crew.threads(c).all().forEach { t ->
            val m = t.optJSONArray("messages") ?: return@forEach
            for (i in 0 until m.length()) { val x = m.getJSONObject(i); if (x.optString("kind") == "delegate") {
                val a = x.optString("from"); val b = x.optString("to"); if (a.isNotBlank() && b.isNotBlank()) { val k = if (a < b) a to b else b to a; count[k] = (count[k] ?: 0) + 1 } } }
        }
        val out = HashMap<String, MutableList<Pair<String, Int>>>()
        count.forEach { (k, n) -> out.getOrPut(k.first) { ArrayList() } += k.second to n; out.getOrPut(k.second) { ArrayList() } += k.first to n }
        return out.mapValues { (_, v) -> v.sortedByDescending { it.second }.take(3).map { it.first } }
    }
}
