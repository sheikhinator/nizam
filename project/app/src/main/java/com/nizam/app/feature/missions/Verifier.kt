package com.nizam.app.feature.missions

import com.nizam.app.AppContainer
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Checks missions against what the user actually logged. A mission the app can measure completes
 * itself — no honesty-based ticking, which is what makes most quest systems hollow.
 */
object Verifier {

    /** Metrics the generator is allowed to use. Anything else falls back to MANUAL. */
    val METRICS = listOf(
        "TASKS_DONE",        // tasks completed today
        "HABITS_DONE",       // habit entries today
        "PRAYERS_LOGGED",    // prayers logged today
        "PRAYERS_ON_TIME",   // on time or in jamaah today
        "SETS_LOGGED",       // gym sets today
        "STUDY_MINUTES",     // study session minutes today
        "LESSONS_DONE",      // course lessons completed today
        "WATER_ML",          // water logged today
        "KCAL_UNDER",        // calories today must stay below target
        "PROTEIN_G",         // protein grams today
        "SPEND_UNDER",       // today's spend must stay below target
        "INCOME_LOGGED",     // income recorded today, at or above target
        "DIARY_WRITTEN",     // a diary entry today
        "NOTES_WRITTEN",     // notes created today
        "MANUAL"             // the app can't see it — you tick it
    )

    data class Progress(val current: Double, val target: Double, val met: Boolean)

    suspend fun measure(container: AppContainer, mission: Mission, date: String): Progress? {
        if (mission.metric == "MANUAL") return null
        val target = mission.target

        val value: Double = when (mission.metric) {
            "TASKS_DONE" -> container.taskRepository.all().first().count {
                it.completedAt != null &&
                    LocalDate.ofEpochDay(it.completedAt / 86_400_000L).toString() == date
            }.toDouble()

            "HABITS_DONE" -> container.habitRepository.entriesFor(date).first().size.toDouble()

            "PRAYERS_LOGGED" -> container.prayerRepository.day(date).first()?.let { day ->
                listOf(day.fajr, day.dhuhr, day.asr, day.maghrib, day.isha)
                    .count { it != "NOT_PRAYED" }.toDouble()
            } ?: 0.0

            "PRAYERS_ON_TIME" -> container.prayerRepository.day(date).first()?.let { day ->
                listOf(day.fajr, day.dhuhr, day.asr, day.maghrib, day.isha)
                    .count { it == "ON_TIME" || it == "JAMAAH" }.toDouble()
            } ?: 0.0

            "SETS_LOGGED" -> container.gymRepository.sets().first()
                .count { it.localDate == date }.toDouble()

            "STUDY_MINUTES" -> container.learningRepository.sessions().first()
                .filter { it.localDate == date }.sumOf { it.minutes }.toDouble()

            "LESSONS_DONE" -> container.courseRepository.allLessons().first().count {
                it.completedAt != null &&
                    LocalDate.ofEpochDay(it.completedAt / 86_400_000L).toString() == date
            }.toDouble()

            "WATER_ML" -> (container.dietRepository.water(date).first()?.ml ?: 0).toDouble()

            "KCAL_UNDER" -> container.dietRepository.day(date).first().sumOf { it.kcal }

            "PROTEIN_G" -> container.dietRepository.day(date).first().sumOf { it.protein }

            "SPEND_UNDER" -> container.financeRepository.since(date).first()
                .filter { it.kind == "EXPENSE" && it.localDate == date }.sumOf { it.amount }

            "INCOME_LOGGED" -> container.financeRepository.since(date).first()
                .filter { it.kind == "INCOME" && it.localDate == date }.sumOf { it.amount }

            "DIARY_WRITTEN" -> container.diaryRepository.entries().first()
                .count { it.localDate == date }.toDouble()

            "NOTES_WRITTEN" -> container.noteRepository.all().first().count {
                LocalDate.ofEpochDay(it.createdAt / 86_400_000L).toString() == date
            }.toDouble()

            else -> return null
        }

        // "under" metrics invert the comparison, and only count once something is logged
        val met = when (mission.metric) {
            "KCAL_UNDER", "SPEND_UNDER" -> value > 0 && value <= target
            else -> value >= target
        }
        return Progress(value, target, met)
    }
}
