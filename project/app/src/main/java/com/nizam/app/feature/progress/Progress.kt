package com.nizam.app.feature.progress

import com.nizam.app.AppContainer
import com.nizam.app.core.Levels
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * XP is derived from what you actually did, not stored separately — so it can never drift out of
 * sync with your data, and there is no migration to get wrong.
 */
data class DayScore(
    val date: String,
    val xp: Int,
    val tasks: Int,
    val habits: Int,
    val prayers: Int,
    val sets: Int,
    val studyMinutes: Int,
    val lessons: Int,
    val wroteDiary: Boolean,
    val loggedFood: Boolean
) {
    val active: Boolean
        get() = xp > 0
}

data class ProgressSummary(
    val totalXp: Int,
    val level: Int,
    val levelProgress: Int,
    val levelNeed: Int,
    val title: String,
    val streak: Int,
    val bestStreak: Int,
    val today: DayScore,
    val last30: List<DayScore>,
    val freezes: Int = 0
)

object Xp {
    const val TASK = 10
    const val HABIT = 8
    const val PRAYER = 12
    const val SET = 3
    const val STUDY_PER_15MIN = 6
    const val LESSON = 30
    const val DIARY = 15
    const val FOOD_LOGGED = 8

    suspend fun summarise(container: AppContainer): ProgressSummary {
        val from = LocalDate.now().minusDays(89)
        val fromIso = from.toString()

        val tasks = container.taskRepository.all().first()
        val habitEntries = container.habitRepository.entriesSince(fromIso).first()
        val foodDays = container.dietRepository.loggedDaysSince(fromIso).first().toSet()
        val prayerDays = container.prayerRepository.since(fromIso).first()
        val sets = container.gymRepository.sets().first()
        val sessions = container.learningRepository.sessions().first()
        val diary = container.diaryRepository.entries().first()
        val lessons = container.courseRepository.allLessons().first()

        val days = (0..89).map { offset ->
            val date = LocalDate.now().minusDays(offset.toLong())
            val iso = date.toString()

            val taskCount = tasks.count {
                it.completedAt != null &&
                    LocalDate.ofEpochDay(it.completedAt / 86_400_000L).toString() == iso
            }
            val habitCount = habitEntries.count { it.localDate == iso }
            val prayerCount = prayerDays.firstOrNull { it.localDate == iso }?.let { day ->
                listOf(day.fajr, day.dhuhr, day.asr, day.maghrib, day.isha).count { it != "NOT_PRAYED" }
            } ?: 0
            val setCount = sets.count { it.localDate == iso }
            val minutes = sessions.filter { it.localDate == iso }.sumOf { it.minutes }
            val lessonCount = lessons.count {
                it.completedAt != null &&
                    LocalDate.ofEpochDay(it.completedAt / 86_400_000L).toString() == iso
            }
            val wrote = diary.any { it.localDate == iso }
            val ate = iso in foodDays

            val xp = taskCount * TASK +
                habitCount * HABIT +
                prayerCount * PRAYER +
                setCount * SET +
                (minutes / 15) * STUDY_PER_15MIN +
                lessonCount * LESSON +
                (if (wrote) DIARY else 0) +
                (if (ate) FOOD_LOGGED else 0)

            DayScore(iso, xp, taskCount, habitCount, prayerCount, setCount, minutes, lessonCount, wrote, ate)
        }

        val streakState = com.nizam.app.feature.growth.Streak.compute(days.map { it.active })
        val streak = streakState.current
        val best = streakState.best

        val total = days.sumOf { it.xp }
        val (progress, need) = Levels.progressInLevel(total)

        return ProgressSummary(
            totalXp = total,
            level = Levels.levelFor(total),
            levelProgress = progress,
            levelNeed = need,
            title = Levels.title(Levels.levelFor(total)),
            streak = streak,
            bestStreak = best,
            today = days.first(),
            last30 = days.take(30),
            freezes = streakState.freezes
        )
    }
}
