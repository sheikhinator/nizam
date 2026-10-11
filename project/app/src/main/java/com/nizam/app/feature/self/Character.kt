package com.nizam.app.feature.self

import androidx.compose.ui.graphics.Color
import com.nizam.app.AppContainer
import com.nizam.app.core.Levels
import com.nizam.app.feature.progress.DayScore
import com.nizam.app.feature.progress.Xp
import com.nizam.app.ui.theme.Spine
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Character attributes. Each one is earned by a different kind of day, so the shape of the profile
 * tells you what you've actually been building — and what you've been avoiding.
 */
enum class Attribute(val label: String, val colour: Color, val meaning: String) {
    DISCIPLINE("Discipline", Spine.Tasks, "Doing the thing you said you would"),
    HEALTH("Health", Spine.Gym, "Training, food, water, weight"),
    KNOWLEDGE("Knowledge", Spine.Learning, "Study, lessons, reading, notes"),
    FAITH("Faith", Spine.Prayer, "Prayer on time and consistency"),
    WEALTH("Wealth", Spine.Finance, "Tracking, restraint, income"),
    MIND("Mind", Spine.Diary, "Reflection, mood, writing")
}

data class AttributeScore(
    val attribute: Attribute,
    val xp: Int,
    val level: Int,
    val progress: Int,
    val need: Int,
    val last30Days: Int
)

data class CharacterSheet(
    val scores: List<AttributeScore>,
    val strongest: AttributeScore,
    val weakest: AttributeScore,
    val totalLevel: Int
)

object CharacterEngine {

    suspend fun build(container: AppContainer): CharacterSheet {
        val summary = Xp.summarise(container)
        val days = summary.last30

        val month = container.financeRepository
            .since(LocalDate.now().minusDays(29).toString()).first()
        val notes = container.noteRepository.all().first()
        val books = container.bookRepository.all().first()

        val financeDays = month.map { it.localDate }.distinct().size
        val incomeDays = month.filter { it.kind == "INCOME" }.map { it.localDate }.distinct().size
        val noteCount = notes.count {
            LocalDate.ofEpochDay(it.createdAt / 86_400_000L) >= LocalDate.now().minusDays(29)
        }
        val readingBooks = books.count { it.status == "READING" || it.status == "FINISHED" }

        fun sum(selector: (DayScore) -> Int) = days.sumOf(selector)

        val raw = mapOf(
            Attribute.DISCIPLINE to (sum { it.tasks } * 10 + sum { it.habits } * 8 + summary.streak * 12),
            Attribute.HEALTH to (sum { it.sets } * 4 + days.count { it.loggedFood } * 10),
            Attribute.KNOWLEDGE to (sum { it.studyMinutes } / 5 + sum { it.lessons } * 25 +
                noteCount * 6 + readingBooks * 15),
            Attribute.FAITH to (sum { it.prayers } * 10),
            Attribute.WEALTH to (financeDays * 12 + incomeDays * 20),
            Attribute.MIND to (days.count { it.wroteDiary } * 18)
        )

        val scores = Attribute.entries.map { attribute ->
            val xp = raw[attribute] ?: 0
            val (progress, need) = Levels.progressInLevel(xp)
            AttributeScore(
                attribute = attribute,
                xp = xp,
                level = Levels.levelFor(xp),
                progress = progress,
                need = need,
                last30Days = when (attribute) {
                    Attribute.DISCIPLINE -> days.count { it.tasks > 0 || it.habits > 0 }
                    Attribute.HEALTH -> days.count { it.sets > 0 || it.loggedFood }
                    Attribute.KNOWLEDGE -> days.count { it.studyMinutes > 0 || it.lessons > 0 }
                    Attribute.FAITH -> days.count { it.prayers > 0 }
                    Attribute.WEALTH -> financeDays
                    Attribute.MIND -> days.count { it.wroteDiary }
                }
            )
        }

        return CharacterSheet(
            scores = scores,
            strongest = scores.maxByOrNull { it.xp }!!,
            weakest = scores.minByOrNull { it.xp }!!,
            totalLevel = scores.sumOf { it.level }
        )
    }
}
