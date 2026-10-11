package com.nizam.app.feature.agent

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.setValue
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.net.Ai
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agent teams. You give a goal to a crew; the lead breaks it down and hands pieces to specialists, who work in
 * parallel and report back in one shared thread. Agents can create new specialists when the job needs one,
 * use Atlas's tools, connected services, saved skills and your other apps — and anything risky goes through
 * the reviewer and waits in the approval inbox. You can talk into the thread at any time to steer.
 */
object Crew {
    data class Agent(val id: String, val name: String, val emoji: String, val role: String, val instructions: String,
                     val tools: Set<String>, val color: Long, val lead: Boolean, val createdBy: String = "you") {
        fun json(): JSONObject = JSONObject().put("id", id).put("name", name).put("emoji", emoji).put("role", role).put("instructions", instructions)
            .put("tools", JSONArray(tools.toList())).put("color", color).put("lead", lead).put("createdBy", createdBy)
        companion object {
            fun of(o: JSONObject) = Agent(o.optString("id"), o.optString("name"), o.optString("emoji").ifBlank { "🤖" }, o.optString("role"),
                o.optString("instructions"), o.optJSONArray("tools")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet(),
                o.optLong("color", 0xFF6366F1), o.optBoolean("lead"), o.optString("createdBy", "you"))
        }
    }

    /** What an agent may touch. */
    val TOOLS = linkedMapOf("web" to "Search the web", "actions" to "Atlas actions (tasks, notes, money, reminders…)", "apps" to "Operate your other apps",
        "connectors" to "Connected services (Notion, GitHub, Slack…)", "skills" to "Run saved skills", "delegate" to "Hand work to others / hire agents")

    private val PALETTE = listOf(0xFF6366F1, 0xFF0EA5E9, 0xFF10B981, 0xFFF59E0B, 0xFFEC4899, 0xFF8B5CF6, 0xFFEF4444, 0xFF14B8A6, 0xFFF97316, 0xFF84CC16)

    private fun agentsStore(c: Context) = JsonStore(c, "crew_agents")
    fun threads(c: Context) = JsonStore(c, "crew_threads")

    fun agents(c: Context): List<Agent> {
        val s = agentsStore(c)
        if (s.all().isEmpty()) s.save(DEFAULT.map { it.json() })
        return s.all().map { Agent.of(it) }
    }
    fun saveAgent(c: Context, a: Agent) { val s = agentsStore(c); s.save(s.all().filter { it.optString("id") != a.id } + a.json()); version++ }
    fun deleteAgent(c: Context, id: String) { agentsStore(c).remove(id); version++ }
    fun resetTeam(c: Context) { agentsStore(c).save(DEFAULT.map { it.json() }); version++ }
    fun newColor(c: Context) = PALETTE[agents(c).size % PALETTE.size]

    private val DEFAULT = listOf(
        Agent("lead", "Atlas", "🧭", "Team lead", "Break the goal into pieces, hand each to the right teammate (in parallel when you can), check what comes back, " +
            "ask the Critic to review anything important, and finish with one clear answer for the user. Hire a new specialist if nobody on the team fits.",
            setOf("web", "actions", "apps", "connectors", "skills", "delegate"), 0xFF6366F1, true, "Atlas"),
        Agent("scout", "Scout", "🔎", "Researcher", "Find current, reliable information on the web. Compare options with prices, pros and cons. Name your sources. " +
            "When a page needs real scraping (listings, prices across pages, JavaScript sites) and Scrapling is connected, use it through Atlas's tools.",
            setOf("web", "connectors"), 0xFF0EA5E9, false, "Atlas"),
        Agent("planner", "Planner", "🗓️", "Planner", "Turn plans into dated tasks, reminders and calendar events inside Atlas. Keep plans realistic around prayer times and work.",
            setOf("actions"), 0xFF10B981, false, "Atlas"),
        Agent("writer", "Quill", "✍️", "Writer", "Draft messages, emails and posts in the user's voice — warm, direct, no filler. Offer a Roman Urdu version when it's for family.",
            emptySet(), 0xFFEC4899, false, "Atlas"),
        Agent("operator", "Operator", "🤖", "Operator", "Do things: in Atlas, in the user's apps, in connected services, and by running saved skills. Say exactly what you did.",
            setOf("actions", "apps", "connectors", "skills"), 0xFFF59E0B, false, "Atlas"),
        Agent("analyst", "Hisaab", "📊", "Money & data analyst", "Read the user's spending, budgets and numbers in Atlas; calculate exactly; spot leaks and trends.",
            setOf("actions"), 0xFF14B8A6, false, "Atlas"),
        Agent("critic", "Critic", "🧐", "Reviewer", "Check the team's work before it reaches the user: wrong facts, weak reasoning, missing steps, risky actions. Be specific and short.",
            setOf("web"), 0xFFEF4444, false, "Atlas"),
    )

    // ───────────── live state for the UI ─────────────
    var version by mutableIntStateOf(0)
    val working = mutableStateMapOf<String, String>()        // threadId → who is working right now
    val busyAgents = mutableStateMapOf<String, String>()     // agentId → the thread it is working on
    private val jobs = HashMap<String, Job>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()

    fun thread(c: Context, id: String) = threads(c).all().firstOrNull { it.optString("id") == id }

    private suspend fun post(c: Context, tid: String, from: String, to: String, text: String, kind: String, extra: JSONObject? = null) = lock.withLock {
        threads(c).update(tid) { t ->
            val m = JSONObject().put("from", from).put("to", to).put("text", Secrets.redact(text)).put("kind", kind).put("t", System.currentTimeMillis())
            extra?.keys()?.forEach { k -> m.put(k, extra.get(k)) }
            t.put("messages", (t.optJSONArray("messages") ?: JSONArray()).put(m)).put("updated", System.currentTimeMillis())
        }
        version++
    }
    private suspend fun setStatus(c: Context, tid: String, s: String) = lock.withLock { threads(c).update(tid) { it.put("status", s) }; version++ }
    private suspend fun enqueue(c: Context, tid: String, to: String, task: String, from: String) = lock.withLock {
        threads(c).update(tid) { t -> t.put("queue", (t.optJSONArray("queue") ?: JSONArray()).put(JSONObject().put("to", to).put("task", task).put("from", from))) }
    }
    private suspend fun takeQueue(c: Context, tid: String): List<JSONObject> = lock.withLock {
        var out = emptyList<JSONObject>()
        threads(c).update(tid) { t -> val q = t.optJSONArray("queue") ?: JSONArray(); out = (0 until q.length()).map { q.getJSONObject(it) }; t.put("queue", JSONArray()) }
        out
    }

    /** Starts a new thread for a goal and sets the team to work. Returns the thread id. */
    fun start(container: AppContainer, goal: String, members: List<String>? = null, approvalPolicy: String? = null): String {
        val c = container.appContext
        val id = System.currentTimeMillis().toString()
        val safeApprovalPolicy = when (approvalPolicy) {
            null -> ""
            "ask", "auto_low", "auto_all" -> approvalPolicy
            else -> "ask"
        }
        threads(c).add(JSONObject().put("id", id).put("goal", goal.trim()).put("status", "running").put("created", System.currentTimeMillis())
            .put("members", JSONArray(members ?: agents(c).map { it.id })).put("messages", JSONArray()).put("queue", JSONArray())
            .put("approvalPolicy", safeApprovalPolicy))
        scope.launch {
            post(c, id, "you", "lead", goal, "user")
            enqueue(c, id, "lead", "New goal from the user: " + runCatching { com.nizam.app.feature.hq.Company.decorateGoal(c, goal) }.getOrDefault(goal), "you")
            drive(container, id)
        }
        version++
        return id
    }

    /** The user speaks into a thread — to the lead, or @Name to someone specific. */
    fun say(container: AppContainer, tid: String, text: String) {
        val c = container.appContext
        val target = Regex("^@(\\S+)").find(text.trim())?.groupValues?.get(1)?.let { n -> agents(c).firstOrNull { it.name.equals(n, true) }?.id } ?: "lead"
        scope.launch {
            post(c, tid, "you", target, text, "user")
            enqueue(c, tid, target, "The user says: $text", "you")
            setStatus(c, tid, "running")
            if (jobs[tid]?.isActive != true) drive(container, tid)
        }
    }

    fun stop(c: Context, tid: String) {
        jobs[tid]?.cancel(); working.remove(tid)
        scope.launch { setStatus(c, tid, "stopped"); post(c, tid, "system", "", "Stopped by you.", "system") }
    }

    suspend fun onApproved(container: AppContainer, tid: String, action: String, r: Actions.Result) {
        val c = container.appContext
        post(c, tid, "you", "lead", "${if (r.ok) "✓" else "✕"} Approved ${action.replace('_', ' ')} — ${r.summary}", "approval_result")
        enqueue(c, tid, "lead", "The user approved $action. Result: ${r.summary}. Continue.", "you")
        setStatus(c, tid, "running")
        if (jobs[tid]?.isActive != true) scope.launch { drive(container, tid) }
    }

    /** Runs a goal to the end and returns the final answer — used by routines. */
    suspend fun runToEnd(container: AppContainer, goal: String, approvalPolicy: String? = null): String {
        val id = start(container, goal, approvalPolicy = approvalPolicy)
        // wait for the team to finish (or stop to wait on the user), up to 20 minutes
        val until = System.currentTimeMillis() + 20 * 60_000L
        kotlinx.coroutines.delay(1500)
        while (System.currentTimeMillis() < until) {
            val st = thread(container.appContext, id)?.optString("status")
            if (st != "running" && working[id] == null) break
            kotlinx.coroutines.delay(2000)
        }
        val t = thread(container.appContext, id) ?: return "The team didn't start."
        val msgs = t.optJSONArray("messages") ?: JSONArray()
        val final = (0 until msgs.length()).map { msgs.getJSONObject(it) }.lastOrNull { it.optString("kind") == "final" }
        return final?.optString("text") ?: "Waiting on you: ${t.optString("status")}"
    }

    private suspend fun drive(container: AppContainer, tid: String) {
        val c = container.appContext
        val job = scope.launch {
            try {
                var rounds = 0
                while (rounds < 16) {
                    val queue = takeQueue(c, tid)
                    if (queue.isEmpty()) break
                    rounds++
                    coroutineScope {
                        queue.groupBy { it.optString("to") }.map { (to, tasks) -> async {
                            val a = agents(c).firstOrNull { it.id == to } ?: agents(c).first { it.lead }
                            working[tid] = a.name
                            busyAgents[a.id] = tid
                            try { turn(container, tid, a, tasks.joinToString("\n\n") { it.optString("task") }) } finally { busyAgents.remove(a.id) }
                        } }.awaitAll()
                    }
                    val t = thread(c, tid) ?: break
                    if (t.optString("status") == "done" || t.optString("status") == "stopped") break
                }
                val t = thread(c, tid)
                if (t != null && t.optString("status") == "running") {
                    val waiting = ApprovalInbox.pending(c).any { it.optString("thread") == tid }
                    setStatus(c, tid, if (waiting) "waiting" else if (rounds >= 16) "paused" else "idle")
                    if (rounds >= 16) post(c, tid, "system", "", "Paused after 16 rounds so it doesn't run away. Say \"continue\" to keep going.", "system")
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e
            } catch (e: Exception) {
                post(c, tid, "system", "", "Stopped: ${com.nizam.app.ui.components.Feedback.friendly(e)}", "system"); setStatus(c, tid, "failed")
            } finally { working.remove(tid) }
        }
        jobs[tid] = job
        job.join()
    }

    private fun transcript(t: JSONObject, agents: List<Agent>): String {
        val names = agents.associate { it.id to it.name } + ("you" to "User") + ("system" to "System")
        val m = t.optJSONArray("messages") ?: JSONArray()
        return (0 until m.length()).map { m.getJSONObject(it) }.takeLast(28).joinToString("\n") { x ->
            val to = x.optString("to").takeIf { it.isNotBlank() }?.let { " → ${names[it] ?: it}" }.orEmpty()
            "[${names[x.optString("from")] ?: x.optString("from")}$to] ${x.optString("text").take(700)}"
        }
    }

    private suspend fun turn(container: AppContainer, tid: String, a: Agent, task: String) {
        val c = container.appContext
        val t = thread(c, tid) ?: return
        val team = agents(c)
        val roster = team.joinToString("\n") { "- ${it.name} (${it.role})" + if (it.tools.isEmpty()) " — drafts only" else " — tools: ${it.tools.joinToString()}" }
        val skills = runCatching { SkillsPro.all(c).take(20).joinToString { it.optString("name") } }.getOrDefault("")
        val connected = runCatching { com.nizam.app.feature.connect.Connectors.connectedNames(c) }.getOrDefault("")
        val system = buildString {
            append("You are ${a.name}, the ${a.role} on the user's personal AI team inside the Atlas app. ${a.instructions}")
            append(runCatching { com.nizam.app.feature.hq.Company.promptFor(c, a) }.getOrDefault(""))
            append("\n\n")
            append("Team:\n$roster\n\n")
            append("Your tools: ${a.tools.joinToString().ifBlank { "none — you reason and write only" }}.")
            if (skills.isNotBlank()) append("\nSaved skills: $skills.")
            if (connected.isNotBlank()) append("\nConnected services: $connected.")
            append("\n\nReturn ONLY JSON: {\"say\":\"your message to the team (markdown, concise)\"," +
                "\"delegate\":[{\"to\":\"teammate name\",\"task\":\"a complete, self-contained task\"}]," +
                "\"hire\":{\"name\":\"\",\"emoji\":\"\",\"role\":\"\",\"instructions\":\"\",\"tools\":[\"web\"]}," +
                "\"use_atlas\":\"one concrete instruction for Atlas's tools, or empty\"," +
                "\"ask_user\":\"a question only the user can answer, or empty\"," +
                "\"done\":\"${if (a.lead) "the final answer for the user when the goal is fully met, else empty" else "always empty"}\"}\n")
            if (a.lead) append("As lead: delegate independent pieces in the same turn so they run in parallel. Don't do specialists' work yourself. " +
                "Use hire only when no teammate fits. When everything is back and checked, put the full answer in done.")
            else append("Answer your task directly in say — it goes back to the lead. Only delegate if you really need help.")
            if ("delegate" !in a.tools && !a.lead) append(" You cannot delegate or hire.")
        }
        if (com.nizam.app.feature.wild.AgentMeter.overCap(c, a.name)) {
            post(c, tid, "system", "", "${a.name} has used today's budget. Raise it in Agent budget, or say continue tomorrow.", "system"); return
        }
        val raw = runCatching {
            kotlinx.coroutines.withContext(com.nizam.app.feature.wild.AgentMeter.Tag(a.name)) {
                Ai.generate(container.settingsRepository, "Goal: ${t.optString("goal")}\n\nThread so far:\n${transcript(t, team)}\n\nYour task now:\n$task",
                    system, 1800, useWeb = "web" in a.tools)
            }
        }.getOrElse { post(c, tid, a.id, "", "I couldn't think just now: ${com.nizam.app.ui.components.Feedback.friendly(it)}", "msg"); return }
        val o = runCatching { JSONObject(Ai.cleanJson(raw)) }.getOrElse { JSONObject().put("say", raw) }

        o.optString("say").takeIf { it.isNotBlank() }?.let { post(c, tid, a.id, if (a.lead) "" else "lead", it, "msg") }

        // hire a new specialist
        o.optJSONObject("hire")?.takeIf { it.optString("name").isNotBlank() && (a.lead || "delegate" in a.tools) && team.size < 14 }?.let { h ->
            if (team.none { it.name.equals(h.optString("name"), true) }) {
                val tools = h.optJSONArray("tools")?.let { x -> (0 until x.length()).map { x.optString(it) }.filter { it in TOOLS.keys && it != "delegate" }.toSet() } ?: setOf("web")
                val n = Agent("a" + System.currentTimeMillis(), h.optString("name").take(20), h.optString("emoji").ifBlank { "✨" }, h.optString("role").take(40),
                    h.optString("instructions").take(600), tools, newColor(c), false, a.name)
                saveAgent(c, n)
                post(c, tid, a.id, "", "Hired **${n.emoji} ${n.name}** — ${n.role}.", "hire")
            }
        }

        // hand work to teammates (they run in parallel next round)
        val del = o.optJSONArray("delegate") ?: JSONArray()
        if (a.lead || "delegate" in a.tools) for (i in 0 until del.length()) {
            val d = del.optJSONObject(i) ?: continue
            val who = agents(c).firstOrNull { it.name.equals(d.optString("to"), true) || it.role.equals(d.optString("to"), true) } ?: continue
            if (who.id == a.id) continue
            post(c, tid, a.id, who.id, d.optString("task"), "delegate")
            enqueue(c, tid, who.id, "From ${a.name}: ${d.optString("task")}", a.id)
        }

        // use Atlas's tools
        val use = o.optString("use_atlas")
        if (use.isNotBlank() && a.tools.any { it in setOf("actions", "apps", "connectors", "skills") }) {
            val out = runCatching { kotlinx.coroutines.withContext(com.nizam.app.feature.wild.AgentMeter.Tag(a.name)) { container.agentLoop.run(use, useWeb = "web" in a.tools, autoApprove = false) } }
                .getOrElse { AgentLoop.Outcome("Tools failed: ${com.nizam.app.ui.components.Feedback.friendly(it)}", emptyList()) }
            post(c, tid, a.id, "", out.reply, "tool", JSONObject().put("instruction", use)
                .put("steps", JSONArray(out.steps.map { (if (it.ok) "✓ " else "✕ ") + it.summary }))
                .put("toolEvidence", JSONArray(out.steps.map { step ->
                    JSONObject().put("action", step.action).put("target", step.target)
                        .put("summary", Secrets.redact(step.summary).take(220)).put("ok", step.ok)
                })))
            for ((name, args) in out.pendingApproval) {
                val v = Reviewer.review(container, name, args, "${t.optString("goal")} — ${a.name}: $use")
                val routinePolicy = t.optString("approvalPolicy")
                val mayRunAlone = if (routinePolicy in setOf("ask", "auto_low", "auto_all")) {
                    Reviewer.mayRunAlone(routinePolicy, v)
                } else {
                    Reviewer.mayRunAlone(c, v)
                }
                when {
                    v.decision == "block" -> post(c, tid, "system", "", "🛑 Reviewer blocked ${name.replace('_', ' ')}: ${v.why}", "system")
                    mayRunAlone -> { val r = Actions.run(container, name, args)
                        post(c, tid, a.id, "", "${if (r.ok) "✓" else "✕"} ${name.replace('_', ' ')} (auto-approved, ${v.risk} risk) — ${r.summary}", "tool") }
                    else -> { val item = ApprovalInbox.add(c, "Team · ${a.name}", name, args, v, use, tid)
                        post(c, tid, a.id, "", "Needs your OK: ${name.replace('_', ' ')}", "approval", JSONObject().put("approval", item.optString("id"))
                            .put("risk", v.risk).put("why", v.why).put("args", Secrets.redact(args.toString()).take(400))) }
                }
            }
            if (!a.lead) enqueue(c, tid, "lead", "${a.name} used tools: ${out.reply.take(800)}", a.id)
        } else if (!a.lead && o.optString("say").isNotBlank() && del.length() == 0) {
            enqueue(c, tid, "lead", "Report from ${a.name}: ${o.optString("say").take(2000)}", a.id)
        }

        o.optString("ask_user").takeIf { it.isNotBlank() }?.let {
            post(c, tid, a.id, "you", it, "question"); setStatus(c, tid, "needs you")
            runCatching { com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 4300 + (tid.hashCode() % 50),
                "${a.emoji} ${a.name} has a question", it.take(200)) }
        }

        o.optString("done").takeIf { it.isNotBlank() && a.lead }?.let {
            post(c, tid, a.id, "you", it, "final"); setStatus(c, tid, "done")
            runCatching { com.nizam.app.feature.studio.Deliverables.fromTeamResult(c, t.optString("goal"), it) }
            runCatching { com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 4200 + (tid.hashCode() % 50),
                "✅ Team finished", t.optString("goal").take(80)) }
        }
    }

    /** A whole team as a shareable Atlas card. */
    fun exportTeam(c: Context): String = com.nizam.app.feature.nx.AtlasCard.encode(JSONObject().put("v", 1).put("kind", "Team").put("title", "Agent team")
        .put("agents", JSONArray(agents(c).filter { !it.lead }.map { it.json() })))
    fun importTeam(c: Context, card: JSONObject): Int {
        val a = card.optJSONArray("agents") ?: return 0
        var n = 0
        for (i in 0 until a.length()) { val x = Agent.of(a.getJSONObject(i))
            if (agents(c).none { it.name.equals(x.name, true) }) { saveAgent(c, x.copy(id = "a" + System.nanoTime(), lead = false, createdBy = "shared")); n++ } }
        return n
    }

    /** Designs a team for a kind of work and adds it. */
    suspend fun design(container: AppContainer, purpose: String): List<Agent> {
        val c = container.appContext
        val raw = Ai.generate(container.settingsRepository, purpose, "Design 3–5 specialist AI agents for this kind of work, to join an existing team that already has a lead, " +
            "a researcher, a planner, a writer, an operator, a money analyst and a critic. Don't duplicate those. Return ONLY JSON " +
            "[{\"name\":\"short first name\",\"emoji\":\"one emoji\",\"role\":\"2-4 words\",\"instructions\":\"how they work, 2 sentences\",\"tools\":[\"web\",\"actions\",\"apps\",\"connectors\",\"skills\"]}]", 1200)
        val a = JSONArray(Ai.cleanJson(raw))
        return (0 until a.length()).map { a.getJSONObject(it) }.map { h ->
            val tools = h.optJSONArray("tools")?.let { x -> (0 until x.length()).map { x.optString(it) }.filter { it in TOOLS.keys }.toSet() } ?: setOf("web")
            Agent("a" + System.nanoTime(), h.optString("name").take(20), h.optString("emoji").ifBlank { "✨" }, h.optString("role").take(40),
                h.optString("instructions").take(600), tools, newColor(c), false, "Atlas").also { saveAgent(c, it) }
        }
    }
}
