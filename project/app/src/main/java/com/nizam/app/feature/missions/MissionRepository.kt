package com.nizam.app.feature.missions

import com.nizam.app.AppContainer
import com.nizam.app.core.fmtAmount
import com.nizam.app.core.today
import com.nizam.app.feature.progress.Xp
import com.nizam.app.net.Ai
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

class MissionRepository(
    private val dao: MissionDao,
    private val container: () -> AppContainer
) {
    fun goals() = dao.observeGoals()
    fun allGoals() = dao.observeAllGoals()
    fun today() = dao.observeForDate(com.nizam.app.core.today())
    fun forGoal(goalId: Long) = dao.observeForGoal(goalId)
    fun since(from: String) = dao.observeSince(from)


    /** Everything the planner needs to know about the user, in a compact block. */
    private suspend fun profile(): String {
        val c = container()
        val summary = Xp.summarise(c)
        val sets = c.gymRepository.sets().first()
        val sessions = c.learningRepository.sessions().first()
        val month = c.financeRepository.since(LocalDate.now().withDayOfMonth(1).toString()).first()
        val goals = dao.observeGoals().first()
        val recentMissions = dao.observeSince(LocalDate.now().minusDays(13).toString()).first()

        val weekSets = sets.filter { it.localDate >= LocalDate.now().minusDays(6).toString() }
        val weekStudy = sessions.filter { it.localDate >= LocalDate.now().minusDays(6).toString() }
            .sumOf { it.minutes }
        val income = month.filter { it.kind == "INCOME" }.sumOf { it.amount }
        val spend = month.filter { it.kind == "EXPENSE" }.sumOf { it.amount }
        val completionRate = if (recentMissions.isEmpty()) "no history"
        else "${recentMissions.count { it.completedAt != null } * 100 / recentMissions.size}%"

        val exerciseBests = weekSets.groupBy { it.exercise }
            .map { (name, s) -> "$name best ${s.maxOf { it.weight }}kg x ${s.maxOf { it.reps }}" }
            .take(6)

        return buildString {
            appendLine("Today: ${com.nizam.app.core.today()} (${LocalDate.now().dayOfWeek})")
            appendLine("Level ${summary.level}, streak ${summary.streak} days, best ${summary.bestStreak}.")
            appendLine("Last 7 days: ${weekSets.size} gym sets, ${weekStudy} study minutes, " +
                "${summary.last30.take(7).sumOf { it.prayers }} prayers logged of 35.")
            if (exerciseBests.isNotEmpty()) appendLine("Recent lifts: ${exerciseBests.joinToString("; ")}")
            appendLine("This month money: income ${fmtAmount(income)} PKR, spend ${fmtAmount(spend)} PKR.")
            appendLine("Mission completion rate over last 14 days: $completionRate")
            if (goals.isNotEmpty()) {
                appendLine("Active goals: " + goals.joinToString("; ") {
                    it.title + (if (it.deadline.isBlank()) "" else " by ${it.deadline}")
                })
            }
            appendLine("Currency is PKR. The user is in Pakistan.")
        }
    }

    private val metricRules = """
        Each mission MUST carry a verification rule so the app can check it automatically.
        Allowed "metric" values and what "target" means for each:
          TASKS_DONE      target = number of tasks to complete today
          HABITS_DONE     target = number of habits to tick today
          PRAYERS_LOGGED  target = prayers logged today (max 5)
          PRAYERS_ON_TIME target = prayers on time or in jamaah today (max 5)
          SETS_LOGGED     target = gym sets to log today
          STUDY_MINUTES   target = minutes of study to log today
          LESSONS_DONE    target = course lessons to finish today
          WATER_ML        target = millilitres of water
          KCAL_UNDER      target = calorie ceiling for the day
          PROTEIN_G       target = grams of protein
          SPEND_UNDER     target = maximum PKR to spend today
          INCOME_LOGGED   target = PKR of income to record today
          DIARY_WRITTEN   target = 1
          NOTES_WRITTEN   target = number of notes to write
          MANUAL          target = 0, for anything the app cannot measure
        Use MANUAL only when nothing else fits. Prefer a measurable metric.
        "difficulty" is one of: easy, medium, hard, very_hard — judged against THIS user's own
        history above, not against an average person.
    """.trimIndent()

    /** Breaks a goal into a dated campaign of concrete missions. */
    suspend fun createGoal(title: String, why: String, deadline: String): Long {
        val prompt = """
            The user's goal: $title
            Why it matters to them: ${why.ifBlank { "not stated" }}
            Deadline: ${deadline.ifBlank { "not stated — assume 14 days" }}

            Here is everything known about them:
            ${profile()}

            Break this goal into a campaign of concrete, dated missions. Rules:
            - Every mission is a single specific ACTION they can finish in one day. Not "work on it",
              not "research options" — an action with a number attached and a clear finish line.
            - Front-load the missions that actually move the needle. Order matters.
            - If the goal needs money, skills, or people, include the missions that acquire those.
            - Be honest about what the first day looks like: it is usually setup, contact, or listing.
            - Between 6 and 20 missions depending on the deadline. Spread them across dates starting
              today (${com.nizam.app.core.today()}).
            - Write "rationale" as one sentence explaining why this mission earns its place.

            $metricRules

            Return ONLY valid JSON:
            {
              "strategy": "3-4 sentences: the actual plan, the bottleneck, and what makes it work",
              "missions": [
                {
                  "date": "YYYY-MM-DD",
                  "title": "short imperative, under 60 chars",
                  "detail": "exactly what to do, with numbers, names and amounts where relevant",
                  "rationale": "one sentence",
                  "difficulty": "medium",
                  "metric": "MANUAL",
                  "target": 0,
                  "unit": ""
                }
              ]
            }
        """.trimIndent()

        val raw = Ai.generate(
            settings = container().settingsRepository,
            prompt = prompt,
            system = "You are a ruthless, practical strategist who turns vague goals into dated, " +
                "checkable actions. You know what actually works and you skip motivational filler. " +
                "You return only valid JSON.",
            maxOutputTokens = 16384
        )
        val json = JSONObject(Ai.cleanJson(raw))
        val goalId = dao.insertGoal(
            Goal(
                title = title.trim(),
                why = why.trim(),
                deadline = deadline.trim(),
                strategy = json.optString("strategy", "")
            )
        )
        val array = json.optJSONArray("missions") ?: JSONArray()
        dao.insertAll(parseMissions(array, goalId))
        return goalId
    }

    /** Today's missions, generated from current state and any active goals. */
    suspend fun generateDaily(force: Boolean = false, intensity: String = "NORMAL"): Int {
        val date = com.nizam.app.core.today()
        if (!force && dao.countDailyFor(date) > 0) return 0
        val intensityRules = when (intensity.uppercase()) {
            "GENTLE" -> """
                - Generate 2 to 3 missions for today only (${date}); protect recovery and make the first
                  action small enough to complete on a low-energy day.
                - Keep missions easy or medium; do not create a very_hard mission.
            """.trimIndent()
            "FOCUSED" -> """
                - Generate 4 to 6 missions for today only (${date}); make the first one the
                  highest-priority action and give it a realistic timebox.
                - Use focused, specific actions but calibrate difficulty to the user's actual history.
            """.trimIndent()
            else -> "- Generate 4 to 6 missions for today only (${date})."
        }

        val prompt = """
            Generate today's missions for this user.

            ${profile()}

            Rules:
            $intensityRules
            - They must connect to what this person is actually doing: their training, their study,
              their prayers, their money, their goals above. Nothing generic.
            - Calibrate difficulty to their own recent numbers. If they log 12 sets a session,
              15 sets is medium, not hard. If their streak is 0, day one should be winnable.
            - Include at least one mission that repairs whatever looks weakest in the data.
            - In normal or focused modes, vary difficulty: at least one easy, at most one very_hard.

            $metricRules

            Return ONLY valid JSON:
            {"missions": [{"title": "...", "detail": "...", "rationale": "...",
              "difficulty": "medium", "metric": "SETS_LOGGED", "target": 12, "unit": "sets"}]}
        """.trimIndent()

        val raw = Ai.generate(
            settings = container().settingsRepository,
            prompt = prompt,
            system = "You set daily missions for one specific person whose data you can see. " +
                "Specific over generic, always. You return only valid JSON.",
        )
        val array = JSONObject(Ai.cleanJson(raw)).optJSONArray("missions") ?: JSONArray()
        val missions = parseMissions(array, null, defaultDate = date)
        dao.insertAll(missions)
        return missions.size
    }

    private fun parseMissions(array: JSONArray, goalId: Long?, defaultDate: String? = null): List<Mission> {
        val out = mutableListOf<Mission>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val metric = o.optString("metric", "MANUAL")
                .uppercase()
                .let { if (Verifier.METRICS.contains(it)) it else "MANUAL" }
            val difficulty = o.optString("difficulty", "medium").lowercase()
            out.add(
                Mission(
                    goalId = goalId,
                    localDate = o.optString("date", defaultDate ?: com.nizam.app.core.today()),
                    title = o.optString("title").ifBlank { "Mission ${i + 1}" },
                    detail = o.optString("detail"),
                    rationale = o.optString("rationale"),
                    difficulty = difficulty,
                    xp = Difficulty.xpFor(difficulty),
                    metric = metric,
                    target = o.optDouble("target", 0.0).let { if (it.isNaN()) 0.0 else it },
                    unit = o.optString("unit"),
                    orderIndex = i
                )
            )
        }
        return out
    }

    /** Runs verification over today's missions and completes the ones the data already satisfies. */
    suspend fun verifyToday(): Int {
        val date = com.nizam.app.core.today()
        val missions = dao.forDateOnce(date)
        var completed = 0
        missions.filter { it.completedAt == null && it.metric != "MANUAL" }.forEach { mission ->
            val progress = Verifier.measure(container(), mission, date)
            if (progress != null && progress.met) {
                dao.update(
                    mission.copy(completedAt = System.currentTimeMillis(), autoVerified = true)
                )
                completed++
            }
        }
        return completed
    }

    suspend fun progressFor(mission: Mission): Verifier.Progress? =
        Verifier.measure(container(), mission, mission.localDate)

    suspend fun toggle(mission: Mission) {
        val nowDone = mission.completedAt == null
        dao.update(
            mission.copy(
                completedAt = if (nowDone) System.currentTimeMillis() else null,
                autoVerified = false
            )
        )
        if (nowDone) com.nizam.app.core.spine.Pulse.emit(
            "missions", "done", mission.title,
            org.json.JSONObject().put("xp", mission.xp)
        )
    }

    suspend fun skip(mission: Mission) = dao.update(mission.copy(skipped = !mission.skipped))

    suspend fun deleteMission(mission: Mission) = dao.deleteMission(mission.id)

    suspend fun deleteGoal(goal: Goal) {
        dao.deleteMissionsForGoal(goal.id)
        dao.deleteGoal(goal.id)
    }

    suspend fun renameMission(mission: Mission, title: String, detail: String) =
        dao.update(mission.copy(title = title.trim(), detail = detail.trim()))

    suspend fun openMissions() = dao.openMissions(java.time.LocalDate.now().minusDays(30).toString())

    suspend fun closeGoal(goal: Goal, done: Boolean) {
        dao.updateGoal(goal.copy(status = if (done) "DONE" else "ABANDONED"))
        if (done) com.nizam.app.core.spine.Pulse.emit("goals", "achieved", goal.title)
    }
}
