package com.nizam.app.feature.jarvis

import android.content.Context
import com.nizam.app.AppContainer
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.net.Ai
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Autopilot: hand over an outcome ("plan my Lahore → Hunza trip under 60k", "find me three used
 * Civics under 35 lakh"), and Atlas works it to the end. It writes a plan you can see, then runs one
 * step per background cycle with the full agent — web search, your data, your tools — keeping what
 * each step found so the next builds on it. When a step needs a decision only you can make, it
 * stops and asks, and carries on from your answer.
 */
data class PilotStep(val title: String, val how: String, val status: String = "todo", val result: String = "")
data class Pilot(
    val id: Long, val goal: String, val steps: List<PilotStep>,
    val status: String = "running",          // running | waiting | done | paused
    val question: String = "",               // what it needs from you when waiting
    val lastRun: Long = 0, val created: Long = System.currentTimeMillis()
) {
    val progress: Float get() = if (steps.isEmpty()) 0f else steps.count { it.status == "done" }.toFloat() / steps.size
}

object Autopilot {
    private fun f(c: Context) = File(c.filesDir, "autopilot.json")
    private const val GAP_MS = 12 * 60_000L    // at most one step per ~15-minute cycle per pilot

    fun all(c: Context): List<Pilot> = runCatching { JSONArray(f(c).readText()) }.getOrElse { JSONArray() }.let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            val st = o.optJSONArray("steps") ?: JSONArray()
            Pilot(o.optLong("id"), o.optString("goal"),
                (0 until st.length()).map { st.getJSONObject(it) }.map {
                    PilotStep(it.optString("title"), it.optString("how"), it.optString("status", "todo"), it.optString("result")) },
                o.optString("status", "running"), o.optString("question"), o.optLong("lastRun"), o.optLong("created"))
        }
    }

    @Synchronized
    fun save(c: Context, list: List<Pilot>) = f(c).writeText(JSONArray().apply {
        list.forEach { p -> put(JSONObject().put("id", p.id).put("goal", p.goal).put("status", p.status)
            .put("question", p.question).put("lastRun", p.lastRun).put("created", p.created)
            .put("steps", JSONArray().apply { p.steps.forEach { s -> put(JSONObject().put("title", s.title).put("how", s.how)
                .put("status", s.status).put("result", s.result)) } })) }
    }.toString())

    private fun update(c: Context, p: Pilot) = save(c, all(c).map { if (it.id == p.id) p else it })
    fun delete(c: Context, id: Long) = save(c, all(c).filterNot { it.id == id })
    fun pause(c: Context, id: Long, paused: Boolean) = all(c).firstOrNull { it.id == id }?.let {
        update(c, it.copy(status = if (paused) "paused" else "running"))
    }

    /** Breaks the goal into 3–8 concrete steps. */
    suspend fun start(c: Context, goal: String): Pilot {
        val container = Background.container(c)
        val raw = Ai.generate(container.settingsRepository,
            prompt = "Goal: \"$goal\"\n\nWhat you know about them:\n" + com.nizam.app.feature.agent.Snapshot.of(container) +
                "\n\nPlan this as 3 to 8 concrete steps an assistant with web search, their app data and phone actions can " +
                "carry out one at a time. Each step must produce something real (found options with prices, a created task, " +
                "a drafted message) — not \"think about\". Put any step needing their decision where it naturally falls.\n" +
                "JSON only: {\"steps\":[{\"title\":\"short\",\"how\":\"exactly what to do and what to hand back\"}]}",
            system = "You plan work precisely and realistically.", maxOutputTokens = 1200)
        val arr = JSONObject(Ai.cleanJson(raw)).optJSONArray("steps") ?: JSONArray()
        val steps = (0 until arr.length()).map { arr.getJSONObject(it) }
            .map { PilotStep(it.optString("title").take(80), it.optString("how")) }.filter { it.title.isNotBlank() }.take(8)
        val pilot = Pilot(System.currentTimeMillis(), goal.trim(), steps.ifEmpty { listOf(PilotStep(goal.take(80), goal)) })
        save(c, all(c) + pilot)
        Timeline.log(c, "autopilot", "Autopilot started: ${goal.take(60)}")
        return pilot
    }

    /** Your answer to what it asked — the waiting step reruns with it. */
    fun answer(c: Context, id: Long, reply: String) {
        val p = all(c).firstOrNull { it.id == id } ?: return
        val i = p.steps.indexOfFirst { it.status == "waiting" }.takeIf { it >= 0 } ?: return
        val steps = p.steps.toMutableList()
        steps[i] = steps[i].copy(status = "todo", how = steps[i].how + "\nTheir answer: " + reply.trim())
        update(c, p.copy(steps = steps, status = "running", question = "", lastRun = 0))
    }

    /** Runs the next step of every active pilot. Called from the background cycle, or "Run now". */
    suspend fun runDue(c: Context, container: AppContainer, force: Boolean = false) {
        all(c).filter { it.status == "running" && (force || System.currentTimeMillis() - it.lastRun > GAP_MS) }
            .forEach { runCatching { step(c, container, it) } }
    }

    private suspend fun step(c: Context, container: AppContainer, p: Pilot) {
        val i = p.steps.indexOfFirst { it.status == "todo" }
        if (i < 0) { update(c, p.copy(status = "done")); return }
        val s = p.steps[i]
        update(c, p.copy(lastRun = System.currentTimeMillis()))
        val done = p.steps.take(i).filter { it.status == "done" }
            .joinToString("\n") { "✓ ${it.title}: ${it.result.take(400)}" }
        val prompt = "You are running an autopilot for the user's goal: \"${p.goal}\".\n" +
            (if (done.isNotBlank()) "Done so far:\n$done\n\n" else "") +
            "Current step ${i + 1} of ${p.steps.size}: ${s.title}\n${s.how}\n\n" +
            "Do this step now with your actions (search the web, use their data, add tasks, draft). Then reply with what you " +
            "found or did — concrete names, numbers, links. If you truly cannot continue without a decision only they can " +
            "make, start your reply with NEED: followed by one short question."
        val out = container.agentLoop.run(prompt, useWeb = true, autoApprove = false)
        val reply = out.reply.trim()
        val steps = p.steps.toMutableList()
        val fresh = all(c).firstOrNull { it.id == p.id } ?: return      // deleted while running
        if (reply.startsWith("NEED:", true)) {
            steps[i] = s.copy(status = "waiting", result = reply)
            val q = reply.substringAfter(":").trim()
            update(c, fresh.copy(steps = steps, status = "waiting", question = q, lastRun = System.currentTimeMillis()))
            Notifications.post(c, Notifications.CHANNEL_NUDGE, (400 + p.id % 50).toInt(), "Autopilot needs you", "${p.goal.take(40)}: $q")
            return
        }
        val pending = if (out.pendingApproval.isNotEmpty()) "\n(Waiting for your approval: " +
            out.pendingApproval.joinToString { it.first } + ")" else ""
        steps[i] = s.copy(status = "done", result = (reply + pending + "\n" + out.steps.joinToString("\n") {
            (if (it.ok) "✓ " else "✕ ") + it.summary }).trim())
        val finished = steps.none { it.status == "todo" || it.status == "waiting" }
        update(c, fresh.copy(steps = steps, status = if (finished) "done" else "running", lastRun = System.currentTimeMillis()))
        Timeline.log(c, "autopilot", "Autopilot: ${s.title}", reply.take(160))
        if (finished) {
            Notifications.post(c, Notifications.CHANNEL_NUDGE, (400 + p.id % 50).toInt(), "Autopilot finished",
                "${p.goal.take(50)} — open Atlas to see everything it found.")
        }
    }
}
