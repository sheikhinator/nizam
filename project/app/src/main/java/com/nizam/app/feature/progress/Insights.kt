package com.nizam.app.feature.progress

import java.time.LocalDate

/**
 * Insight templates fire from real patterns in the user's own history. Tokens in the body are
 * filled with the actual numbers, so nothing is generic and nothing is invented.
 */
data class Insight(
    val id: String,
    val headline: String,
    val body: String,
    val glyph: String
)

object Insights {

    fun compute(summary: ProgressSummary): List<Insight> {
        val days = summary.last30.filter { it.date <= LocalDate.now().toString() }
        val out = mutableListOf<Insight>()
        val active = days.filter { it.active }
        if (active.size < 4) {
            return listOf(
                Insight(
                    "warmup",
                    "Not enough history yet",
                    "Log a few more days and this space fills with patterns from your own data — " +
                        "your best weekday, what you keep dropping, what moves together.",
                    "◇"
                )
            )
        }

        // Best weekday by average XP
        val byWeekday = active.groupBy { LocalDate.parse(it.date).dayOfWeek }
            .filter { it.value.size >= 2 }
            .map { it.key to it.value.map { d -> d.xp }.average() }
        if (byWeekday.size >= 3) {
            val best = byWeekday.maxByOrNull { it.second }!!
            val rest = byWeekday.filter { it.first != best.first }.map { it.second }.average()
            if (rest > 0 && best.second > rest * 1.2) {
                val pct = (((best.second - rest) / rest) * 100).toInt()
                out.add(
                    Insight(
                        "best_weekday",
                        "${best.first.name.lowercase().replaceFirstChar { it.uppercase() }} is your engine.",
                        "You get through $pct% more on that day than any other. Put the hard thing there.",
                        "^"
                    )
                )
            }
        }

        // Training and prayer moving together
        val trainDays = active.filter { it.sets > 0 }
        if (trainDays.size >= 3) {
            val withTraining = trainDays.map { it.prayers }.average()
            val withoutTraining = active.filter { it.sets == 0 }.map { it.prayers }.average()
            if (!withoutTraining.isNaN() && withTraining > withoutTraining + 0.8) {
                out.add(
                    Insight(
                        "train_prayer",
                        "Training days hold together.",
                        "On days you train you log ${"%.1f".format(withTraining)} prayers against " +
                            "${"%.1f".format(withoutTraining)} on days you don't. The structure carries.",
                        "◆"
                    )
                )
            }
        }

        // Study consistency
        val studied = active.filter { it.studyMinutes > 0 }
        if (studied.isNotEmpty()) {
            val avg = studied.map { it.studyMinutes }.average().toInt()
            out.add(
                Insight(
                    "study_rate",
                    "You study in ${avg}-minute blocks.",
                    "Across ${studied.size} of the last ${days.size} days. " +
                        if (studied.size < days.size / 3)
                            "The length is fine — the frequency is what's costing you."
                        else "That frequency is what compounds. Protect it.",
                    "●"
                )
            )
        }

        // Dropping the last prayer of the day
        val ishaMisses = active.count { it.prayers in 1..4 }
        if (ishaMisses >= 3 && active.size >= 6) {
            out.add(
                Insight(
                    "partial_days",
                    "Your days end incomplete.",
                    "$ishaMisses of your last ${active.size} logged days had some prayers missing rather " +
                        "than none. The gap is at the edges of the day, not the middle.",
                    "◐"
                )
            )
        }

        // Streak framing
        if (summary.streak >= 3) {
            out.add(
                Insight(
                    "streak",
                    "${summary.streak} days unbroken.",
                    if (summary.streak >= summary.bestStreak)
                        "This is your longest run so far. Don't get clever — just do tomorrow the same."
                    else "Your best is ${summary.bestStreak}. You're ${summary.bestStreak - summary.streak} days off it.",
                    "■"
                )
            )
        }

        // Quiet week warning
        val week = days.take(7)
        val prior = days.drop(7).take(7)
        if (week.isNotEmpty() && prior.isNotEmpty()) {
            val now = week.map { it.xp }.average()
            val before = prior.map { it.xp }.average()
            if (before > 0 && now < before * 0.6) {
                out.add(
                    Insight(
                        "slowdown",
                        "This week is lighter than last.",
                        "Down ${(((before - now) / before) * 100).toInt()}% on your own recent average. " +
                            "Worth knowing whether that was a choice or a drift.",
                        "△"
                    )
                )
            }
        }

        return out.take(4)
    }
}
