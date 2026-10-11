package com.nizam.app.feature.agent

import com.nizam.app.AppContainer
import com.nizam.app.core.fmtAmount
import com.nizam.app.core.today
import com.nizam.app.feature.progress.Xp
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/** Everything the agent should know about the app's current state, in one compact block. */
object Snapshot {

    private var cached: String = ""
    private var cachedAt: Long = 0
    private var cachedPolicy: String = ""

    /** Rebuilding this costs ~8 database queries and a 90-day XP summary, so it's cached briefly. */
    suspend fun of(c: AppContainer): String {
        val policy = ContextControls.read(c.appContext)
        val policyKey = policy.toString()
        if (policyKey == cachedPolicy && System.currentTimeMillis() - cachedAt < 60_000 && cached.isNotBlank()) return cached
        return ContextControls.filter(build(c), policy).also {
            cached = it
            cachedAt = System.currentTimeMillis()
            cachedPolicy = policyKey
        }
    }

    /** Forces a rebuild — call after anything that changes the user's state. */
    fun invalidate() { cachedAt = 0; cachedPolicy = "" }
    fun lastBuiltAt(): Long = cachedAt

    private suspend fun build(c: AppContainer): String {
        val summary = Xp.summarise(c)
        val tasks = c.taskRepository.all().first().filter { it.completedAt == null }
        val month = c.financeRepository.since(LocalDate.now().withDayOfMonth(1).toString()).first()
        val meals = c.dietRepository.day(today()).first()
        val sets = c.gymRepository.sets().first().filter { it.localDate == today() }
        val prayer = c.prayerRepository.day(today()).first()
        val goals = c.missionRepository.goals().first()
        val missions = c.missionRepository.today().first()
        val specs = c.dynamicRepository.specs().first()
        val books = c.bookRepository.all().first()
        val courses = c.courseRepository.courses().first()
        val theme = c.settingsRepository.accentHex.first()

        val spent = month.filter { it.kind == "EXPENSE" }.sumOf { it.amount }
        val income = month.filter { it.kind == "INCOME" }.sumOf { it.amount }

        val profile = runCatching { org.json.JSONObject(c.settingsRepository.profileJson.first()) }
            .getOrElse { org.json.JSONObject() }
        return buildString {
            if (profile.length() > 0) {
                appendLine("WHO THEY ARE (their own profile — use it, don't ask for it again):")
                profile.keys().forEach { k ->
                    val v = profile.optString(k)
                    if (v.isNotBlank()) appendLine("  $k: $v")
                }
                appendLine()
            }
            appendLine("Today: ${today()} (${LocalDate.now().dayOfWeek}). Lahore, Pakistan. PKR.")
            appendLine("Level ${summary.level}, streak ${summary.streak} days, best ${summary.bestStreak}.")
            appendLine("Open tasks (${tasks.size}): " + tasks.take(10).joinToString("; ") { it.title })
            appendLine("This month: income ${fmtAmount(income)}, spent ${fmtAmount(spent)}.")
            appendLine("Today: ${meals.sumOf { it.kcal }.toInt()} kcal, ${sets.size} sets logged.")
            prayer?.let {
                val logged = listOf(it.fajr, it.dhuhr, it.asr, it.maghrib, it.isha)
                    .count { s -> s != "NOT_PRAYED" }
                appendLine("Prayers logged today: $logged of 5.")
            }
            if (goals.isNotEmpty()) appendLine("Goals: " + goals.joinToString("; ") { it.title })
            if (missions.isNotEmpty()) {
                appendLine("Missions today: " + missions.joinToString("; ") {
                    (if (it.completedAt != null) "[done] " else "[open] ") + it.title
                })
            }
            appendLine("Modules installed: " + specs.joinToString(", ") { "${it.name}(${it.key})" })
            if (books.isNotEmpty()) {
                appendLine("Books: " + books.take(8).joinToString("; ") { "${it.title} [${it.status}]" })
            }
            if (courses.isNotEmpty()) appendLine("Courses: " + courses.joinToString("; ") { it.title })
            appendLine("Theme: " + theme.ifBlank { "system default" })
            runCatching {
                com.nizam.app.feature.mcp.Mcp.servers(c.appContext).filter { it.enabled }
                    .takeIf { it.isNotEmpty() }?.let { srv ->
                        appendLine("Connected services (MCP): " + srv.joinToString { it.name } +
                            ". Use mcp_tools to see what they offer, then mcp_call to use one.")
                    }
            }
            com.nizam.app.feature.finale.Teach.forAi(c.appContext).takeIf { it.isNotBlank() }?.let { appendLine(it) }
            runCatching { appendLine(com.nizam.app.feature.presence.Presence.forAi(c.appContext)) }
            com.nizam.app.feature.jarvis.Barakah.forAi(c).takeIf { it.isNotBlank() }?.let { appendLine(it) }
            com.nizam.app.feature.world.Season.forAi(c.appContext).takeIf { it.isNotBlank() }?.let { appendLine(it) }
            com.nizam.app.feature.body.Energy.forAi(c.appContext).takeIf { it.isNotBlank() }?.let { appendLine(it) }
            com.nizam.app.feature.daily.AirCache.forAi(c.appContext).takeIf { it.isNotBlank() }?.let { appendLine(it) }
            com.nizam.app.feature.mind.Values.forAi(c.appContext).takeIf { it.isNotBlank() }?.let { appendLine(it) }
            runCatching { com.nizam.app.feature.cinema.CinemaStore(c.appContext).summaryForAi() }.getOrNull()
                ?.takeIf { it.isNotBlank() }?.let { append(it) }
        }
    }
}
