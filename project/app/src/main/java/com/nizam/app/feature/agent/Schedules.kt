package com.nizam.app.feature.agent

import android.content.Context
import com.nizam.app.AppContainer
import com.nizam.app.feature.notify.Notifications
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Routines: work Atlas does on its own, on a schedule. Each routine is an agent task, a watcher that only
 * speaks when its answer turns to yes, a saved skill, or a goal for an agent team. Every routine keeps a run
 * history, shows its next run, can be paused, tested on the spot, and has its own approval boundary —
 * risky steps go through the reviewer and wait in the approval inbox instead of running unattended.
 */
data class RunRecord(val at: Long, val ok: Boolean, val result: String, val ms: Long, val test: Boolean = false)

data class AgentJob(
    val id: Long, val kind: String, val prompt: String, val cadence: String, val hour: Int,
    val lastRun: String = "", val lastResult: String = "",
    val minute: Int = 0, val days: Set<Int> = setOf(7), val dayOfMonth: Int = 1, val everyHours: Int = 3,
    val enabled: Boolean = true, val approval: String = "auto_low", val name: String = "", val params: String = "{}",
    val lastAt: Long = 0L, val created: Long = System.currentTimeMillis(), val history: List<RunRecord> = emptyList(),
    val onlyNotifyChanges: Boolean = false
) {
    val title get() = name.ifBlank { prompt.take(50) }
}

object Schedules {
    val KINDS = linkedMapOf("task" to "Agent task", "watcher" to "Watcher", "skill" to "Skill", "crew" to "Team goal")
    val CADENCES = linkedMapOf("hourly" to "Every hour", "every_n" to "Every few hours", "daily" to "Every day", "weekdays" to "Weekdays",
        "weekly" to "Chosen days", "monthly" to "Monthly", "once" to "Once")

    private fun f(c: Context) = File(c.filesDir, "schedules.json")

    fun all(c: Context): List<AgentJob> = runCatching { JSONArray(f(c).readText()) }.getOrElse { JSONArray() }.let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            AgentJob(o.optLong("id"), o.optString("kind", "task"), o.optString("prompt"), o.optString("cadence", "daily"), o.optInt("hour", 9),
                o.optString("lastRun"), o.optString("lastResult"), o.optInt("minute"),
                o.optJSONArray("days")?.let { d -> (0 until d.length()).map { d.optInt(it) }.toSet() } ?: setOf(7),
                o.optInt("dom", 1), o.optInt("everyH", 3), o.optBoolean("enabled", true), o.optString("approval", "auto_low"),
                o.optString("name"), o.optString("params", "{}"), o.optLong("lastAt"), o.optLong("created", o.optLong("id")),
                o.optJSONArray("history")?.let { h -> (0 until h.length()).map { h.getJSONObject(it) }.map { r ->
                    RunRecord(r.optLong("at"), r.optBoolean("ok"), r.optString("r"), r.optLong("ms"), r.optBoolean("test")) } } ?: emptyList(),
                o.optBoolean("onlyChanges"))
        }
    }

    fun save(c: Context, list: List<AgentJob>) = f(c).writeText(JSONArray().apply {
        list.forEach { j -> put(JSONObject().put("id", j.id).put("kind", j.kind).put("prompt", j.prompt).put("cadence", j.cadence).put("hour", j.hour)
            .put("lastRun", j.lastRun).put("lastResult", j.lastResult).put("minute", j.minute).put("days", JSONArray(j.days.toList())).put("dom", j.dayOfMonth)
            .put("everyH", j.everyHours).put("enabled", j.enabled).put("approval", j.approval).put("name", j.name).put("params", j.params)
            .put("lastAt", j.lastAt).put("created", j.created).put("onlyChanges", j.onlyNotifyChanges)
            .put("history", JSONArray(j.history.take(30).map { r -> JSONObject().put("at", r.at).put("ok", r.ok).put("r", r.result).put("ms", r.ms).put("test", r.test) }))) }
    }.toString())

    fun upsert(c: Context, j: AgentJob) = save(c, all(c).filter { it.id != j.id } + j)

    private fun ldt(ms: Long) = LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
    private fun ms(t: LocalDateTime) = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** The next time this routine should run after [after]. */
    fun nextAt(j: AgentJob, after: Long = maxOf(j.lastAt, j.created)): Long? {
        if (!j.enabled) return null
        val base = ldt(after)
        return when (j.cadence) {
            "hourly" -> ms(base.withSecond(0).withNano(0).withMinute(j.minute).let { if (it.isAfter(base)) it else it.plusHours(1) })
            "every_n" -> after + j.everyHours.coerceIn(1, 24) * 3_600_000L
            "once" -> if (j.lastAt > 0) null else ms(base.toLocalDate().atTime(j.hour, j.minute).let { if (it.isAfter(base)) it else it.plusDays(1) })
            else -> {
                var d = base.toLocalDate()
                repeat(400) {
                    val t = d.atTime(j.hour.coerceIn(0, 23), j.minute.coerceIn(0, 59))
                    val okDay = when (j.cadence) {
                        "weekdays" -> d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY
                        "weekly" -> d.dayOfWeek.value in j.days.ifEmpty { setOf(7) }
                        "monthly" -> d.dayOfMonth == j.dayOfMonth.coerceAtMost(d.lengthOfMonth())
                        else -> true
                    }
                    if (okDay && t.isAfter(base)) return ms(t)
                    d = d.plusDays(1)
                }
                null
            }
        }
    }

    private fun allowed(policy: String, v: Reviewer.Verdict) = Reviewer.mayRunAlone(policy, v)

    /** Runs one routine now and records the result. */
    suspend fun runOne(c: Context, container: AppContainer, j: AgentJob, test: Boolean = false): RunRecord {
        val started = System.currentTimeMillis()
        val source = "Routine · ${j.title}"
        val (ok, result) = runCatching {
            when (j.kind) {
                "watcher" -> {
                    val answer = com.nizam.app.net.Ai.generate(container.settingsRepository,
                        "Check this and answer in one short line, starting with YES: or NO: — YES only if the thing has happened.\n\n${j.prompt}",
                        system = "You check a condition using current information and answer tersely.", maxOutputTokens = 300, useWeb = true)
                    if (answer.trim().startsWith("YES", true) && (test || answer.trim() != j.lastResult))
                        Notifications.post(c, Notifications.CHANNEL_NUDGE, (250 + j.id % 20).toInt(), "👁 ${j.title.take(40)}", answer.take(300))
                    true to answer.take(300)
                }
                "skill" -> {
                    val sk = SkillsPro.find(c, j.prompt) ?: return@runCatching false to "No saved skill called “${j.prompt}”."
                    val params = runCatching { JSONObject(j.params) }.getOrElse { JSONObject() }.let { p -> p.keys().asSequence().associateWith { p.optString(it) } }
                    val r = SkillsPro.runBackground(container, sk, params, 0, source, j.approval)
                    r.ok to r.log.joinToString(" · ").take(400)
                }
                "crew" -> true to Crew.runToEnd(container, j.prompt, j.approval).take(600)
                else -> {
                    val out = container.agentLoop.run(j.prompt, useWeb = true, autoApprove = false)
                    val extra = mutableListOf<String>()
                    for ((name, args) in out.pendingApproval) {
                        val v = Reviewer.review(container, name, args, j.prompt)
                        when {
                            v.decision == "block" -> extra += "🛑 ${name.replace('_', ' ')} blocked: ${v.why}"
                            allowed(j.approval, v) -> Actions.run(container, name, args).let { extra += (if (it.ok) "✓ " else "✕ ") + it.summary }
                            else -> { ApprovalInbox.add(c, source, name, args, v, j.prompt); extra += "⏸ ${name.replace('_', ' ')} waits for your OK" }
                        }
                    }
                    true to (out.reply + if (extra.isEmpty()) "" else "\n" + extra.joinToString("\n")).take(600)
                }
            }
        }.getOrElse { false to "Failed: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
        val rec = RunRecord(started, ok, result, System.currentTimeMillis() - started, test)
        val fresh = all(c).firstOrNull { it.id == j.id } ?: j
        upsert(c, fresh.copy(lastRun = LocalDate.now().toString(), lastResult = result, lastAt = if (test) fresh.lastAt else started,
            history = listOf(rec) + fresh.history, enabled = if (j.cadence == "once" && !test) false else fresh.enabled))
        if (j.kind != "watcher" && (!j.onlyNotifyChanges || result != j.lastResult) && !test)
            runCatching { Notifications.post(c, Notifications.CHANNEL_NUDGE, (250 + j.id % 20).toInt(), (if (ok) "✦ " else "⚠ ") + j.title.take(40), result.take(400)) }
        return rec
    }

    /** Called by the background worker every ~15 minutes. Runs whatever has come due (once, even if several were missed). */
    suspend fun runDue(c: Context, container: AppContainer, now: Double) {
        val t = System.currentTimeMillis()
        all(c).filter { j -> j.enabled && (nextAt(j)?.let { it <= t } ?: false) }.forEach { j -> runOne(c, container, j) }
    }
}
