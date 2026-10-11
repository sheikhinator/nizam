package com.nizam.app.feature.dynamic

import com.nizam.app.core.Formula
import com.nizam.app.core.Point
import java.time.LocalDate

/**
 * Analytics derived from a module's own schema. Because every module declares its field types,
 * one engine can give all of them real depth: trends from NUMBER fields, distributions from
 * CHOICE fields, averages from RATING, and consistency from the dates.
 *
 * This is the payoff for making modules data rather than code — depth arrives everywhere at once
 * instead of being hand-built per module.
 */
data class NumericSummary(
    val field: SpecField,
    val total: Double,
    val average: Double,
    val best: Double,
    val latest: Double,
    val trend: List<Point>,
    val changePercent: Double?
)

data class ChoiceSummary(val field: SpecField, val counts: List<Pair<String, Int>>)

data class ModuleStats(
    val entryCount: Int,
    val daysCovered: Int,
    val perWeek: Double,
    val currentStreak: Int,
    val lastEntry: String?,
    val numerics: List<NumericSummary>,
    val choices: List<ChoiceSummary>,
    val ratings: List<Pair<SpecField, Double>>,
    val activity: List<Double>
) {
    val isEmpty: Boolean get() = entryCount == 0
}

object StatsEngine {

    fun compute(spec: ModuleSpec, entries: List<ModuleEntry>): ModuleStats {
        if (entries.isEmpty()) {
            return ModuleStats(0, 0, 0.0, 0, null, emptyList(), emptyList(), emptyList(), emptyList())
        }

        val sorted = entries.sortedBy { it.localDate }
        val dates = sorted.mapNotNull { runCatching { LocalDate.parse(it.localDate) }.getOrNull() }
        val first = dates.minOrNull() ?: LocalDate.now()
        val daysCovered = (java.time.temporal.ChronoUnit.DAYS.between(first, LocalDate.now()) + 1)
            .toInt().coerceAtLeast(1)
        val perWeek = entries.size * 7.0 / daysCovered

        // Consecutive days ending today or yesterday — a gap of one day doesn't break it, since
        // most of these are not daily habits.
        val dateSet = dates.toSet()
        var streak = 0
        var cursor = LocalDate.now()
        if (!dateSet.contains(cursor)) cursor = cursor.minusDays(1)
        while (dateSet.contains(cursor)) { streak++; cursor = cursor.minusDays(1) }

        // A computed field behaves like a number once evaluated, so it gets the same treatment.
        val numerics = spec.fields.filter { it.type == "NUMBER" || it.type == "COMPUTED" }
            .mapNotNull { field ->
            val pairs = sorted.mapNotNull { entry ->
                val raw = if (field.type == "COMPUTED") {
                    Formula.evaluate(field.formula, entry.numericValues(spec))
                } else entry.value(field.key).toDoubleOrNull()
                raw?.let { entry.localDate to it }
            }
            if (pairs.isEmpty()) return@mapNotNull null
            val values = pairs.map { it.second }
            val half = values.size / 2
            val change = if (values.size >= 4 && half > 0) {
                val older = values.take(half).average()
                val newer = values.drop(half).average()
                if (older != 0.0) (newer - older) / older * 100 else null
            } else null

            NumericSummary(
                field = field,
                total = values.sum(),
                average = values.average(),
                best = values.max(),
                latest = values.last(),
                trend = pairs.takeLast(30).map { Point(it.first.takeLast(5), it.second) },
                changePercent = change
            )
        }

        val choices = spec.fields.filter { it.type in setOf("CHOICE", "MULTI_SELECT", "TAGS") }.mapNotNull { field ->
            val counts = sorted.flatMap { entry ->
                val raw = entry.value(field.key)
                when (field.type) {
                    "MULTI_SELECT" -> StructuredFieldValues.decodeMultiSelect(raw)
                    "TAGS" -> StructuredFieldValues.parseTags(raw)
                    else -> listOf(raw).filter { it.isNotBlank() }
                }
            }
                .groupingBy { it }.eachCount()
                .toList().sortedByDescending { it.second }
            if (counts.isEmpty()) null else ChoiceSummary(field, counts)
        }

        val ratings = spec.fields.filter { it.type == "RATING" }.mapNotNull { field ->
            val values = sorted.mapNotNull { it.value(field.key).toDoubleOrNull() }
            if (values.isEmpty()) null else field to values.average()
        }

        // Last 30 days of entry counts, oldest first
        val activity = (29 downTo 0).map { offset ->
            val day = LocalDate.now().minusDays(offset.toLong()).toString()
            sorted.count { it.localDate == day }.toDouble()
        }

        return ModuleStats(
            entryCount = entries.size,
            daysCovered = daysCovered,
            perWeek = perWeek,
            currentStreak = streak,
            lastEntry = sorted.lastOrNull()?.localDate,
            numerics = numerics,
            choices = choices,
            ratings = ratings,
            activity = activity
        )
    }

    /** Derived metrics that only make sense when two numeric fields relate to each other. */
    fun ratios(stats: ModuleStats): List<Pair<String, String>> {
        if (stats.numerics.size < 2) return emptyList()
        val out = mutableListOf<Pair<String, String>>()
        val cost = stats.numerics.firstOrNull {
            it.field.suffix.equals("PKR", true) || it.field.key.contains("cost", true) ||
                it.field.key.contains("price", true) || it.field.key.contains("amount", true)
        }
        val quantity = stats.numerics.firstOrNull { it != cost }
        if (cost != null && quantity != null && quantity.total > 0) {
            out.add(
                "${cost.field.label} per ${quantity.field.label.lowercase()}" to
                    "%.2f".format(cost.total / quantity.total)
            )
        }
        return out
    }
}
