package com.nizam.app.feature.hq

import android.content.Context
import android.os.BatteryManager
import android.os.PowerManager
import com.nizam.app.AppContainer
import com.nizam.app.feature.agent.ApprovalInbox
import com.nizam.app.feature.agent.Crew
import com.nizam.app.feature.agent.Schedules
import com.nizam.app.feature.connect.Connectors
import com.nizam.app.feature.prayer.PrayerTimes
import com.nizam.app.feature.wild.AgentMeter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Atlas HQ's status hub: one place that knows what every agent is doing, who is waiting on you,
 * and what the building around them looks like (time, weather, salah, load-shedding, budget).
 * The 3D office, the status pill and the "who needs me" button all read from here.
 */
object Hq {
    data class Room(val id: String, val name: String, val emoji: String, val color: String)
    val ROOMS = listOf(
        Room("hq", "HQ", "🏢", "#6366f1"), Room("home", "Home", "🏠", "#10b981"), Room("money", "Money", "💰", "#f59e0b"),
        Room("deen", "Deen", "🕌", "#14b8a6"), Room("work", "Work", "💼", "#0ea5e9"), Room("lab", "PC Lab", "🖥️", "#8b5cf6"),
        Room("owner", "Your office", "👑", "#eab308"))

    data class View(
        val id: String, val name: String, val emoji: String, val role: String, val color: String, val room: Int, val slot: Int,
        val status: String, val pose: String, val card: String, val line: String, val thread: String?, val since: Long, val say: String = "",
        val level: Int = 1, val persona: String = "careful", val dept: String = "research", val visit: String = "", val urgent: Boolean = false)

    private fun p(c: Context) = c.getSharedPreferences("hq", 0)

    // ───────────── rooms ─────────────
    fun roomOf(c: Context, a: Crew.Agent): Int {
        p(c).getInt("room:${a.id}", -1).takeIf { it in 0 until Company.OWNER_FLOOR }?.let { return it }
        val dept = Company.profile(c, a).dept
        return Company.DEPTS.firstOrNull { it.id == dept }?.floor ?: 4
    }
    fun setRoom(c: Context, id: String, room: Int) { p(c).edit().putInt("room:$id", room).apply(); Crew.version++ }

    fun markSeen(c: Context, tid: String) = p(c).edit().putLong("seen:$tid", System.currentTimeMillis()).apply()
    private fun seen(c: Context, tid: String) = p(c).getLong("seen:$tid", 0)

    // ───────────── agents ─────────────
    private fun msgs(t: JSONObject): List<JSONObject> = (t.optJSONArray("messages") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
    private fun clean(s: String, n: Int) = s.replace(Regex("[*_`#>]"), "").replace(Regex("\\s+"), " ").trim().let { if (it.length > n) it.take(n - 1) + "…" else it }
    private fun hex(v: Long) = String.format("#%06X", v and 0xFFFFFF)

    private fun poseOf(a: Crew.Agent, m: JSONObject?): String {
        val kind = m?.optString("kind").orEmpty()
        val txt = ((m?.optString("text") ?: "") + " " + (m?.optString("instruction") ?: "")).lowercase()
        return when {
            kind == "delegate" && m?.optString("from") == a.id || kind == "hire" -> "talk"
            listOf("search", "http", "web", "online", "price", "scrap").any { it in txt } -> "web"
            listOf("rs ", "rs.", "money", "expense", "spend", "budget", "txn", "finance", "bill").any { it in txt } -> "money"
            listOf("call", "whatsapp", "message", "sms", "email", "send", "slack", "reply").any { it in txt } -> "call"
            listOf("note", "read", "document", "pdf", "summar", "diary", "book").any { it in txt } -> "read"
            a.id == "analyst" -> "money"
            a.id == "scout" -> "web"
            a.lead -> "talk"
            else -> "type"
        }
    }

    fun views(c: Context): List<View> {
        val agents = Crew.agents(c)
        val threads = Crew.threads(c).all().sortedByDescending { it.optLong("updated", it.optLong("created")) }.take(40)
        val byId = threads.associateBy { it.optString("id") }
        val pending = ApprovalInbox.pending(c)
        val now = System.currentTimeMillis()
        val slotsUsed = HashMap<Int, Int>()
        return agents.map { a ->
            val room = roomOf(c, a)
            val slot = slotsUsed.getOrDefault(room, 0).also { slotsUsed[room] = it + 1 }
            var status = "idle"; var pose = "idle"; var card = ""; var line = ""; var tid: String? = null; var since = 0L
            val busy = Crew.busyAgents[a.id]
            if (busy != null) {
                val t = byId[busy] ?: Crew.thread(c, busy)
                val last = t?.let { msgs(it).lastOrNull { m -> m.optString("from") == a.id || m.optString("to") == a.id } }
                status = "working"; pose = poseOf(a, last); tid = busy
                card = clean(t?.optString("goal").orEmpty(), 34)
                line = last?.let { m -> if (m.optString("from") == a.id) clean(m.optString("text"), 60) else "On it: " + clean(m.optString("text"), 52) } ?: "Thinking…"
            } else {
                // waiting on you: a question or an approval from this agent
                val waiting = threads.firstOrNull { t ->
                    t.optString("status") in setOf("needs you", "waiting") &&
                        msgs(t).lastOrNull { it.optString("kind") in setOf("question", "approval") }?.optString("from") == a.id
                }
                if (waiting != null) {
                    val m = msgs(waiting).last { it.optString("kind") in setOf("question", "approval") }
                    status = "needs"; tid = waiting.optString("id"); since = m.optLong("t", now)
                    card = clean(waiting.optString("goal"), 34); line = clean(m.optString("text"), 60)
                } else {
                    val done = threads.firstOrNull { t ->
                        t.optString("status") == "done" && msgs(t).lastOrNull { it.optString("kind") == "final" }?.let { f ->
                            f.optString("from") == a.id && f.optLong("t") > seen(c, t.optString("id")) && now - f.optLong("t") < 12 * 3_600_000L
                        } == true
                    }
                    if (done != null) {
                        status = "done"; tid = done.optString("id"); card = clean(done.optString("goal"), 34); line = "Done — tap to read"
                        since = msgs(done).last().optLong("t", now)
                    } else if (AgentMeter.overCap(c, a.name)) { status = "asleep"; line = "Out of today's budget" }
                }
            }
            // the lead also stands for Atlas itself: chat turns and approvals from outside the team
            if (a.lead && status == "idle") {
                com.nizam.app.ui.design.Island.agentBusy?.let { status = "working"; pose = "talk"; card = "Chat"; line = clean(it, 60) }
                pending.firstOrNull { it.optString("thread").isBlank() }?.let {
                    status = "needs"; since = it.optLong("at", now); card = "Needs your OK"
                    line = clean(it.optString("action").replace('_', ' ') + " · " + it.optString("source"), 60)
                }
            }
            // what it said in the last few seconds, for its speech bubble
            val said = threads.asSequence().take(6).flatMap { msgs(it).asReversed().asSequence().take(12) }
                .firstOrNull { it.optString("from") == a.id && it.optString("kind") in SPOKEN && now - it.optLong("t") < SAY_MS }
            // walking over to hand work to a colleague
            val handoff = threads.asSequence().take(4).flatMap { msgs(it).asReversed().asSequence().take(10) }
                .firstOrNull { it.optString("from") == a.id && it.optString("kind") == "delegate" && now - it.optLong("t") < 25_000L }
            val prof = Company.profile(c, a)
            View(a.id, a.name, a.emoji, a.role, hex(a.color), room, slot, status, pose, card, line, tid, since, said?.let { clean(it.optString("text"), 110) } ?: "",
                prof.level, prof.persona, prof.dept, handoff?.optString("to")?.takeIf { to -> agents.any { it.id == to } && to != a.id }.orEmpty(),
                status == "working" && Company.isUrgent(card))
        }
    }

    /** Who is waiting on you, longest-waiting first. */
    fun needs(list: List<View>) = list.filter { it.status == "needs" }.sortedBy { it.since } + list.filter { it.status == "done" }.sortedBy { it.since }

    /** Cheap count for the status pill. */
    fun quickNeeds(c: Context): Int = runCatching {
        val day = System.currentTimeMillis() - 24 * 3_600_000L
        val threads = Crew.threads(c).all()
        threads.count { it.optString("status") in setOf("needs you", "waiting") && it.optLong("updated", it.optLong("created")) > day } + ApprovalInbox.pending(c).count { it.optString("thread").isBlank() }
    }.getOrDefault(0)

    fun summary(list: List<View>): String {
        val w = list.count { it.status == "working" }; val n = list.count { it.status == "needs" }; val d = list.count { it.status == "done" }
        val parts = listOfNotNull(w.takeIf { it > 0 }?.let { "$it working" }, n.takeIf { it > 0 }?.let { "$it need${if (it == 1) "s" else ""} you" }, d.takeIf { it > 0 }?.let { "$it done" })
        return parts.joinToString(" · ").ifBlank { "All quiet · ${list.size} agents ready" }
    }

    // ───────────── the meeting table ─────────────
    data class Pattern(val id: String, val emoji: String, val name: String, val blurb: String, val prompt: String)
    val PATTERNS = listOf(
        Pattern("debate", "🗣️", "Debate", "Everyone proposes, then critiques; the lead writes the decision.",
            "Run this as a DEBATE: each member proposes an answer, then each critiques the others once, then the lead writes one clear decision with the reasons."),
        Pattern("lead", "🧭", "Lead & team", "The lead splits the job, each does a part, the lead merges it.",
            "Run this as LEAD & TEAM: the lead splits the job into parts, gives one part to each member in parallel, then merges their work into one answer."),
        Pattern("split", "🧩", "Split the job", "The same job over many items at once; the lead combines.",
            "Run this as MAP-REDUCE: split the items between the members, each does the same job on their items in parallel, then the lead combines the results."),
        Pattern("redblue", "🛡️", "Attack & defend", "One attacks the plan (risks, scams, weak spots), one fixes it.",
            "Run this as RED/BLUE: one member attacks the plan hard (risks, scams, costs, weak spots), another fixes what holds up, for two rounds; the lead writes the safer final plan."),
        Pattern("panel", "🔍", "Review panel", "Each reviews through their own lens; the lead merges one review.",
            "Run this as a REVIEW PANEL: each member reviews it through their own role's lens, then the lead merges everything into one short review with the top fixes."))

    fun startMeeting(container: AppContainer, pattern: Pattern, members: List<String>, topic: String) {
        val c = container.appContext
        val all = (listOf("lead") + members).distinct()
        val names = Crew.agents(c).filter { it.id in all }.joinToString { it.name }
        p(c).edit().putString("table_pending", JSONObject().put("members", JSONArray(all)).put("topic", topic).put("at", System.currentTimeMillis()).toString())
            .putString("table_topic", topic).putString("table_pattern", pattern.id).remove("table_tid").apply()
        Company.say("lead", "", "Everyone to the table please — ${pattern.name.lowercase()}: ${topic.take(50)}")
        // give everyone time to walk over, then begin
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch {
            kotlinx.coroutines.delay(GATHER_MS)
            val tid = Crew.start(container, "${pattern.prompt}\nAt the table: $names.\n\nTopic: $topic", all)
            p(c).edit().putString("table_tid", tid).remove("table_pending").apply()
            Crew.version++
        }
    }
    private const val GATHER_MS = 9_000L
    private fun pendingMeeting(c: Context): JSONObject? = p(c).getString("table_pending", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
        ?.takeIf { System.currentTimeMillis() - it.optLong("at") < 60_000L }

    // ───────────── stand-ups and all-hands: everyone gathers, each speaks in turn ─────────────
    class Event(val kind: String, val title: String, val lines: List<Pair<String, String>>, val start: Long) {
        val turn = 5_000L
        val walk = 8_000L
        fun over(now: Long) = now - start > walk + lines.size * turn + 4_000L
        fun speaker(now: Long): Pair<String, String>? { val i = ((now - start - walk) / turn).toInt(); return if (now - start < walk) null else lines.getOrNull(i) }
    }
    @Volatile var event: Event? = null
    fun runStandup(c: Context) {
        val lines = Company.standupLines(c)
        event = Event("standup", "Daily stand-up", lines, System.currentTimeMillis())
        Company.markStandup(c, lines)
        Company.say("lead", "", "Stand-up time! Everyone to the table ☕")
    }
    fun runAllHands(c: Context) {
        val recap = Company.allHandsRecap(c)
        val team = Crew.agents(c)
        val lead = team.firstOrNull { it.lead }?.id ?: "lead"
        val lines = listOf(lead to "Week in review: " + recap.replace("\n", " · ")) + team.filter { !it.lead }.take(6).map { it.id to "Thanks everyone — great week." }
        event = Event("allhands", "All-hands", lines, System.currentTimeMillis())
        Company.markAllHands(c, recap)
        Company.say(lead, "", "All-hands in the boardroom now 🎉")
    }
    fun tableThread(c: Context): String? = p(c).getString("table_tid", null)
    fun endMeeting(c: Context) { tableThread(c)?.let { Crew.stop(c, it) }; p(c).edit().remove("table_tid").apply() }

    private val SPOKEN = setOf("msg", "delegate", "question", "final", "hire", "user")
    private const val SAY_MS = 14_000L

    /**
     * The team conversation happening right now: one started at the table, or else the most recent running
     * team thread with at least two agents taking part. Its agents gather at the HQ table while it runs.
     */
    private fun meeting(c: Context): JSONObject? {
        val now = System.currentTimeMillis()
        // a stand-up or all-hands in progress
        event?.let { e ->
            if (e.over(now)) event = null
            else {
                val sp = e.speaker(now)
                val names = Crew.agents(c).associateBy { it.id }
                val log = JSONArray()
                if (sp != null) names[sp.first]?.let { log.put(JSONObject().put("who", "${it.emoji} ${it.name}").put("text", sp.second).put("kind", "msg")) }
                return JSONObject().put("members", JSONArray(e.lines.map { it.first })).put("topic", e.title).put("speaking", sp?.first ?: "")
                    .put("kind", e.kind).put("gathering", sp == null).put("log", log).put("sayId", sp?.first ?: "").put("sayText", sp?.second ?: "")
            }
        }
        // a meeting you called: everyone invited walks to the table first
        pendingMeeting(c)?.let { pm ->
            return JSONObject().put("members", pm.optJSONArray("members") ?: JSONArray()).put("topic", pm.optString("topic")).put("speaking", "")
                .put("kind", "meeting").put("gathering", true).put("log", JSONArray().put(JSONObject().put("who", "🧭 Atlas").put("text", "Gathering everyone at the table…").put("kind", "msg")))
        }
        fun liveOf(t: JSONObject): Boolean { val id = t.optString("id"); return t.optString("status") == "running" || Crew.busyAgents.values.any { it == id } }
        val table = tableThread(c)?.let { Crew.thread(c, it) }?.takeIf { liveOf(it) }
        val t = table ?: Crew.threads(c).all().filter { liveOf(it) && now - it.optLong("updated", it.optLong("created")) < 10 * 60_000L }
            .maxByOrNull { it.optLong("updated", it.optLong("created")) } ?: return null
        val tid = t.optString("id")
        val ids = Crew.agents(c).map { it.id }.toSet()
        val m = msgs(t)
        // who has taken part: anyone who spoke or was handed work, plus whoever is busy on it now
        val members = LinkedHashSet<String>()
        // everyone invited to a table meeting sits down, not only those who've spoken
        if (table != null) (t.optJSONArray("members") ?: JSONArray()).let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it in ids }?.let { members += it } }
        m.forEach { x -> listOf(x.optString("from"), x.optString("to")).forEach { if (it in ids) members += it } }
        Crew.busyAgents.filter { it.value == tid }.keys.forEach { members += it }
        if (table == null && members.size < 2) return null
        val names = Crew.agents(c).associateBy { it.id }
        val log = JSONArray()
        m.filter { it.optString("kind") in SPOKEN || it.optString("kind") == "tool" }.takeLast(4).forEach { x ->
            val who = names[x.optString("from")]?.let { "${it.emoji} ${it.name}" } ?: if (x.optString("from") == "you") "🙂 You" else "Atlas"
            log.put(JSONObject().put("who", who).put("text", clean(x.optString("text"), 160)).put("kind", x.optString("kind")))
        }
        val speaking = members.firstOrNull { Crew.busyAgents[it] == tid }
        val topic = if (table != null) p(c).getString("table_topic", "").orEmpty() else clean(t.optString("goal"), 60)
        return JSONObject().put("members", JSONArray(members.toList())).put("topic", topic).put("speaking", speaking ?: "").put("thread", tid).put("log", log)
            .put("kind", if (table != null) "meeting" else "huddle").put("gathering", false)
    }

    // ───────────── the inbox tray's task queue ─────────────
    private fun queue(c: Context): MutableList<JSONObject> = runCatching { JSONArray(p(c).getString("queue", "[]")) }.getOrElse { JSONArray() }
        .let { a -> (0 until a.length()).map { a.getJSONObject(it) }.toMutableList() }
    private fun saveQueue(c: Context, l: List<JSONObject>) = p(c).edit().putString("queue", JSONArray(l).toString()).apply()
    fun queued(c: Context) = queue(c)
    fun enqueue(container: AppContainer, text: String, agent: String? = null) {
        val c = container.appContext
        saveQueue(c, queue(c) + JSONObject().put("text", text.trim()).put("agent", agent ?: "").put("at", System.currentTimeMillis()))
        pump(container)
    }
    fun dequeue(c: Context, at: Long) = saveQueue(c, queue(c).filter { it.optLong("at") != at })
    fun reorderQueued(c: Context, at: Long, direction: Int) {
        val list = queue(c)
        val from = list.indexOfFirst { it.optLong("at") == at }
        if (from < 0) return
        val to = (from + direction).coerceIn(0, list.lastIndex)
        if (from == to) return
        val item = list.removeAt(from)
        list.add(to, item)
        saveQueue(c, list)
    }
    fun assignQueued(c: Context, at: Long, agent: String?) {
        val list = queue(c)
        list.firstOrNull { it.optLong("at") == at }?.put("agent", agent.orEmpty())
        saveQueue(c, list)
    }
    fun limit(c: Context) = p(c).getInt("limit", 2)
    fun setLimit(c: Context, n: Int) = p(c).edit().putInt("limit", n).apply()

    /** Starts queued tasks while fewer than [limit] team threads are running. */
    fun pump(container: AppContainer) {
        val c = container.appContext
        val q = queue(c); if (q.isEmpty() || limit(c) == 0) return
        val running = Crew.threads(c).all().count { it.optString("status") == "running" }
        var free = limit(c) - running
        while (free > 0 && q.isNotEmpty()) {
            val t = q.removeAt(0); free--
            val agent = t.optString("agent").ifBlank { routeTo(c, t.optString("text")) ?: "" }
            val name = Crew.agents(c).firstOrNull { it.id == agent }?.name
            Crew.start(container, if (name != null) "Ask $name to handle this: ${t.optString("text")}" else t.optString("text"),
                if (name != null) listOf("lead", agent) else null)
        }
        saveQueue(c, q)
    }

    /** Picks the department head whose work this sounds like, and who isn't swamped. */
    private fun routeTo(c: Context, text: String): String? {
        val probe = Crew.Agent("probe", "", "", text, text, emptySet(), 0, false)
        val dept = Company.deptFor(probe)
        if (dept == "exec" || dept == "research" && text.isBlank()) return null
        val head = Company.head(c, dept) ?: return null
        return if (Crew.busyAgents.containsKey(head)) null else head
    }

    // ───────────── changes and plugs ─────────────
    data class Change(val who: String, val text: String, val t: Long)
    fun changes(c: Context): List<Change> {
        val names = Crew.agents(c).associate { it.id to "${it.emoji} ${it.name}" }
        val since = System.currentTimeMillis() - 24 * 3_600_000L
        val out = ArrayList<Change>()
        Crew.threads(c).all().forEach { t -> msgs(t).forEach { m ->
            val txt = m.optString("text")
            if (m.optLong("t") > since && (m.optString("kind") == "approval_result" || m.optString("kind") == "tool") && txt.startsWith("✓"))
                out += Change(names[m.optString("from")] ?: "You", clean(txt.removePrefix("✓"), 120), m.optLong("t"))
        } }
        ApprovalInbox.store(c).all().filter { it.optString("state") == "done" && it.optLong("at") > since && it.optString("thread").isBlank() }
            .forEach { out += Change(it.optString("source").ifBlank { "Atlas" }, clean(it.optString("action").replace('_', ' ') + " — " + it.optString("result"), 120), it.optLong("at")) }
        return out.sortedByDescending { it.t }
    }

    @Volatile var health: Map<String, Pair<Boolean, String>> = emptyMap()
    @Volatile private var healthAt = 0L
    suspend fun refreshPlugs(c: Context, force: Boolean = false) {
        if (!force && System.currentTimeMillis() - healthAt < 10 * 60_000L) return
        healthAt = System.currentTimeMillis()
        health = runCatching { Connectors.testAll(c) }.getOrDefault(health)
    }

    // ───────────── the whole picture for the 3D office ─────────────
    /** The agents as the last snapshot saw them, so the screen doesn't work them out twice. */
    @Volatile var lastViews: List<View> = emptyList()
    private var doneCount = -1
    suspend fun snapshot(container: AppContainer): JSONObject {
        val c = container.appContext
        runCatching { pump(container) }
        runCatching {
            Company.syncChat(c); Company.fileMinutes(c)
            if (event == null && Company.standupDue(c)) runStandup(c)
            if (event == null && Company.allHandsDue(c)) runAllHands(c)
        }
        var list = views(c)
        // whoever has the floor at a stand-up says their line
        event?.speaker(System.currentTimeMillis())?.let { (id, text) -> list = list.map { if (it.id == id) it.copy(say = text) else it } }
        lastViews = list
        val o = JSONObject()
        o.put("rooms", JSONArray(ROOMS.map { JSONObject().put("id", it.id).put("name", it.name).put("emoji", it.emoji).put("color", it.color) }))
        o.put("agents", JSONArray(list.map {
            JSONObject().put("id", it.id).put("name", it.name).put("emoji", it.emoji).put("color", it.color).put("room", it.room).put("slot", it.slot)
                .put("status", it.status).put("pose", it.pose).put("card", it.card).put("line", it.line).put("say", it.say)
                .put("level", it.level).put("persona", it.persona).put("dept", it.dept).put("visit", it.visit).put("urgent", it.urgent)
        }))
        o.put("needs", JSONArray(needs(list).filter { it.status == "needs" }.map { it.id }))
        o.put("approvals", ApprovalInbox.pending(c).size + queue(c).size)
        o.put("changes", changes(c).size)
        o.put("summary", summary(list))
        runCatching { val b = Company.board(c); o.put("board", "Doing ${b.doing.size} · Review ${b.review.size} · Done ${b.done.size}") }
        // the company around them
        o.put("open", Company.open(c)); o.put("escalate", Company.flag(c, "escalate")); o.put("idleLife", Company.flag(c, "idle"))
        o.put("friends", JSONObject(Company.friends(c).mapValues { JSONArray(it.value) }))
        val gone = Company.leaving.filterValues { System.currentTimeMillis() - it < 30_000L }.keys
        o.put("leaving", JSONArray(gone.toList()))
        val team = Crew.agents(c).associateBy { it.id }
        o.put("chat", JSONArray(Company.chat.toList().takeLast(8).map { ch ->
            JSONObject().put("from", team[ch.from]?.let { "${it.emoji} ${it.name}" } ?: "Atlas").put("to", team[ch.to]?.name ?: "").put("text", ch.text).put("at", ch.at)
        }))
        // budget jar
        val names = Crew.agents(c).map { it.name }
        val capped = names.filter { AgentMeter.cap(c, it) > 0 }
        val jar = if (capped.isNotEmpty()) 1.0 - capped.sumOf { AgentMeter.today(c, it).first }.toDouble() / capped.sumOf { AgentMeter.cap(c, it) }
        else 1.0 - (AgentMeter.names(c).sumOf { AgentMeter.today(c, it).first } / 300_000.0).coerceAtMost(1.0)
        o.put("jar", jar.coerceIn(0.0, 1.0))
        // plugs
        val conn = Connectors.connected(c)
        o.put("plugsKnown", health.isNotEmpty())
        o.put("plugs", JSONArray(conn.take(12).map { s -> JSONObject().put("name", s.name).apply { health[s.id]?.let { put("ok", it.first) } } }))
        o.put("pcOnline", conn.any { (it.id.contains("hermes") || it.id.contains("scrapling")) && health[it.id]?.first == true })
        meeting(c)?.let { o.put("meeting", it) } ?: o.put("meeting", JSONObject.NULL)
        // time, sky, weather
        val now = LocalTime.now()
        o.put("hour", now.hour + now.minute / 60.0)
        o.put("sky", com.nizam.app.ui.design.AmbienceEngine.sky(now).name)
        val amb = com.nizam.app.ui.design.CurrentAmbience.value
        o.put("weather", amb?.weather ?: "clear")
        o.put("event", amb?.event ?: com.nizam.app.ui.design.AmbienceEngine.event() ?: JSONObject.NULL)
        // salah
        val s = container.settingsRepository
        val lat = s.latitude.first(); val lon = s.longitude.first()
        runCatching {
            val method = runCatching { PrayerTimes.Method.valueOf(s.prayerMethod.first()) }.getOrDefault(PrayerTimes.Method.KARACHI)
            val t = PrayerTimes.calculate(LocalDate.now(), lat, lon, method, s.hanafiAsr.first())
            val h = now.toSecondOfDay() / 3600.0
            val times = listOf("Fajr" to t.fajr, "Dhuhr" to t.dhuhr, "Asr" to t.asr, "Maghrib" to t.maghrib, "Isha" to t.isha)
            val nowPrayer = times.firstOrNull { h >= it.second && h < it.second + 0.25 }
            val next = times.firstOrNull { it.second > h }
            o.put("salah", nowPrayer != null)
            o.put("prayer", when {
                nowPrayer != null -> "${nowPrayer.first} — the team is praying"
                next != null -> "${next.first} in ${((next.second - h) * 60).toInt().let { m -> if (m >= 60) "${m / 60}h ${m % 60}m" else "$m min" }}"
                else -> "Fajr tomorrow"
            })
        }
        o.put("qibla", qibla(lat, lon))
        // load-shedding
        val ls = com.nizam.app.feature.next.LoadShedding
        o.put("loadshed", ls.on(c) && ls.cuts(c).any { (a, b) -> if (a.isBefore(b)) !now.isBefore(a) && now.isBefore(b) else !now.isBefore(a) || now.isBefore(b) })
        // battery saver → the office goes still
        val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
        val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        o.put("low", pm.isPowerSaveMode || bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) in 1..15)
        // routines
        val fmt = DateTimeFormatter.ofPattern("EEE h:mm a")
        val next = runCatching { Schedules.all(c).filter { it.enabled }.mapNotNull { j -> Schedules.nextAt(j)?.let { j to it } }.minByOrNull { it.second } }.getOrNull()
        o.put("routine", next?.let { (j, at) -> "${j.name.ifBlank { j.prompt.take(40) }} · ${Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(fmt)}" } ?: "None scheduled")
        // celebrations: a team thread finished since last time
        val finished = Crew.threads(c).all().filter { it.optString("status") == "done" }
        if (doneCount < 0) doneCount = finished.size
        if (finished.size > doneCount) o.put("celebrateText", clean(finished.maxByOrNull { it.optLong("updated") }?.optString("goal").orEmpty(), 40))
        doneCount = finished.size
        o.put("celebrate", doneCount)
        return o
    }

    fun qibla(lat: Double, lon: Double): Double {
        val p1 = Math.toRadians(lat); val p2 = Math.toRadians(21.4225); val dl = Math.toRadians(39.8262 - lon)
        val th = Math.atan2(Math.sin(dl) * Math.cos(p2), Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl))
        return (Math.toDegrees(th) + 360) % 360
    }
}
