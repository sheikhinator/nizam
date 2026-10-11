package com.nizam.app.feature.self

import com.nizam.app.AppContainer
import com.nizam.app.core.today
import com.nizam.app.feature.progress.Insights
import com.nizam.app.feature.progress.Xp
import com.nizam.app.net.Ai
import kotlinx.coroutines.flow.first
import java.time.LocalDate

class SelfRepository(
    private val dao: SelfDao,
    private val container: () -> AppContainer
) {
    fun reviews() = dao.observeReviews()
    fun identity() = dao.observeIdentity()

    suspend fun addIdentity(text: String) {
        if (text.isBlank()) return
        dao.insertIdentity(IdentityLine(text = text.trim()))
    }

    suspend fun removeIdentity(line: IdentityLine) = dao.deleteIdentity(line.id)

    suspend fun character() = CharacterEngine.build(container())


    /** A portrait rather than a scoreboard: what the data says about who they're becoming. */
    suspend fun readCharacter(): String {
        val c = container()
        val sheet = CharacterEngine.build(c)
        val summary = Xp.summarise(c)
        val identity = dao.observeIdentity().first()
        val reviews = dao.observeReviews().first().take(6)

        val prompt = """
            Read this person from their own last 30 days and tell them what it says about who they
            are becoming. Not a summary of the numbers — a reading.

            Attribute levels (higher = more of that built recently):
            ${sheet.scores.joinToString("\n") {
                "  ${it.attribute.label}: level ${it.level}, active ${it.last30Days} of 30 days"
            }}
            Overall level ${summary.level}, streak ${summary.streak}, best streak ${summary.bestStreak}.
            Strongest: ${sheet.strongest.attribute.label}. Weakest: ${sheet.weakest.attribute.label}.

            ${if (identity.isEmpty()) "They have not written any identity statements yet."
            else "Who they say they want to be:\n" + identity.joinToString("\n") { "  - ${it.text}" }}

            ${if (reviews.isEmpty()) "No written reviews yet."
            else "Recent reviews:\n" + reviews.joinToString("\n") {
                "  ${it.localDate} went well: ${it.went_well} | badly: ${it.went_badly} | change: ${it.oneChange}"
            }}

            Write 4 short paragraphs:
            1. What the shape of this profile actually says about them.
            2. The gap between who they say they want to be and what the data shows. Be honest and
               specific; do not soften it into nothing, and do not moralise either.
            3. The one strength they are underusing.
            4. One concrete change for the next seven days, with a number in it.

            Plain prose, no headings, no bullet points, no flattery.
        """.trimIndent()

        return Ai.generate(
            settings = container().settingsRepository,
            prompt = prompt,
            system = "You read people honestly from their own behavioural data. You are direct and " +
                "perceptive, never flattering and never harsh for effect. You write plainly.",
        )
    }

    /** Evening review: their words first, then a short reading of the day. */
    suspend fun saveDaily(wentWell: String, wentBadly: String, oneChange: String): Review {
        val c = container()
        val summary = Xp.summarise(c)
        val day = summary.today
        val missions = c.missionRepository.today().first()

        val reading = runCatching {
            Ai.generate(
            settings = container().settingsRepository,
                prompt = """
                    The user's evening review for ${today()}.
                    They wrote — went well: ${wentWell.ifBlank { "nothing written" }}
                    went badly: ${wentBadly.ifBlank { "nothing written" }}
                    one change tomorrow: ${oneChange.ifBlank { "nothing written" }}

                    What the data says about the same day: ${day.tasks} tasks done, ${day.habits} habits,
                    ${day.prayers} prayers logged, ${day.sets} sets, ${day.studyMinutes} study minutes,
                    ${day.lessons} lessons, diary ${if (day.wroteDiary) "written" else "not written"}.
                    Missions: ${missions.count { it.completedAt != null }} of ${missions.size} done.
                    Current streak ${summary.streak} days.

                    Reply in 3 to 5 sentences. Where their account and the data disagree, say so.
                    Name one thing worth repeating tomorrow and one thing to drop. No praise padding.
                """.trimIndent(),
                system = "You close out someone's day briefly and honestly. Short, specific, useful.",
            )
        }.getOrElse { "" }

        val review = Review(
            kind = "DAILY",
            localDate = today(),
            went_well = wentWell.trim(),
            went_badly = wentBadly.trim(),
            oneChange = oneChange.trim(),
            aiReading = reading
        )
        dao.insertReview(review)
        return review
    }

    /** Weekly review: the week read back, and what to change. */
    suspend fun runWeekly(): Review {
        val c = container()
        val summary = Xp.summarise(c)
        val week = summary.last30.take(7)
        val prior = summary.last30.drop(7).take(7)
        val insights = Insights.compute(summary)
        val goals = c.missionRepository.goals().first()
        val missions = c.missionRepository
            .since(LocalDate.now().minusDays(6).toString()).first()

        val reading = Ai.generate(
            settings = container().settingsRepository,
            prompt = """
                Weekly review for the week ending ${today()}.

                This week, day by day (date, xp, tasks, habits, prayers, sets, study minutes, lessons):
                ${week.joinToString("\n") {
                    "  ${it.date} ${it.xp} ${it.tasks} ${it.habits} ${it.prayers} ${it.sets} ${it.studyMinutes} ${it.lessons}"
                }}
                The week before, same format:
                ${prior.joinToString("\n") {
                    "  ${it.date} ${it.xp} ${it.tasks} ${it.habits} ${it.prayers} ${it.sets} ${it.studyMinutes} ${it.lessons}"
                }}
                Missions this week: ${missions.count { it.completedAt != null }} completed of ${missions.size}.
                ${if (goals.isEmpty()) "No active goals." else "Active goals: " + goals.joinToString("; ") { it.title }}
                Patterns already detected: ${insights.joinToString("; ") { it.headline }}

                Write the review in four short parts, plain prose with no headings:
                what actually happened this week compared to last, the one pattern that matters most,
                the thing they are avoiding, and exactly three changes for next week — each with a
                number and a day attached. Be specific enough that they could act on it tonight.
            """.trimIndent(),
            system = "You run a weekly review for someone serious about changing. You compare weeks " +
                "honestly, name avoidance without lecturing, and end with concrete numbered changes.",
        )

        val review = Review(kind = "WEEKLY", localDate = today(), aiReading = reading)
        dao.insertReview(review)
        return review
    }

    /** Built-in growth topics — generated on demand rather than shipped as fixed content. */
    val growthTopics = listOf(
        "Building discipline that survives bad days",
        "Beating procrastination at the trigger",
        "Deep focus and attention control",
        "Sleep, energy and recovery",
        "Anger and emotional regulation",
        "Patience and long-horizon thinking",
        "Money psychology and restraint",
        "Talking to people you find difficult",
        "Consistency in worship",
        "Recovering after you break a streak"
    )
}
