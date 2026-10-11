package com.nizam.app.feature.insight

import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

internal data class ReadinessEntry(
    val date: LocalDate,
    val sleepHours: Double?,
    val energy: Int,
    val stress: Int,
    val bodyStrain: Int
)

internal enum class ReadinessLevel(val title: String, val advice: String) {
    PUSH("Push", "Consider a harder-than-usual workout or focused work block if it fits your plan."),
    MAINTAIN("Maintain", "Keep your usual workload and adjust if your check-in changes."),
    RECOVER("Recover", "Choose a lighter training or work load and protect time for rest.")
}

internal data class ReadinessReport(
    val level: ReadinessLevel?,
    val comparedSignals: Int,
    val reasons: List<String>,
    val coverage: String
)

internal data class SkinTemperatureTrend(
    val date: LocalDate,
    val deviationCelsius: Double,
    val baselineDays: Int
)

internal fun personalSkinTemperatureTrend(
    today: LocalDate,
    dailyDeltas: Map<String, Double>
): SkinTemperatureTrend? {
    val byDate = dailyDeltas.mapNotNull { (key, value) ->
        val date = runCatching { LocalDate.parse(key) }.getOrNull() ?: return@mapNotNull null
        if (!value.isFinite() || value !in -20.0..20.0) null else date to value
    }.toMap()
    val sampleDate = listOf(today, today.minusDays(1)).firstOrNull(byDate::containsKey) ?: return null
    val baseline = (1..28).mapNotNull { day ->
        byDate[sampleDate.minusDays(day.toLong())]
    }
    if (baseline.size < 7) return null
    val sorted = baseline.sorted()
    val middle = sorted.size / 2
    val median = if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    } else {
        sorted[middle]
    }
    val current = byDate.getValue(sampleDate)
    return SkinTemperatureTrend(sampleDate, current - median, baseline.size)
}

internal fun readReadinessEntries(records: List<JSONObject>): List<ReadinessEntry> =
    records.mapNotNull { record ->
        val date = runCatching { LocalDate.parse(record.optString("date")) }.getOrNull()
            ?: return@mapNotNull null
        val energy = record.optInt("energy", -1)
        val stress = record.optInt("stress", -1)
        val bodyStrain = record.optInt("bodyStrain", -1)
        val sleep = if (record.has("sleepHours")) record.optDouble("sleepHours", Double.NaN) else null
        if (energy !in 1..3 || stress !in 1..3 || bodyStrain !in 1..3 ||
            (sleep != null && (!sleep.isFinite() || sleep !in 0.0..18.0))) return@mapNotNull null
        ReadinessEntry(date, sleep, energy, stress, bodyStrain)
    }.distinctBy { it.date }.sortedBy { it.date }

internal fun ReadinessEntry.toJson(): JSONObject = JSONObject()
    .put("id", date.toString())
    .put("date", date.toString())
    .put("energy", energy)
    .put("stress", stress)
    .put("bodyStrain", bodyStrain)
    .apply { sleepHours?.let { put("sleepHours", it) } }

internal object ReadinessEngine {
    private const val WINDOW_DAYS = 28
    private const val MIN_BASELINE_DAYS = 7

    fun evaluate(
        today: LocalDate,
        entries: List<ReadinessEntry>,
        health: Map<String, Pair<Double?, Double?>>,
        skinTemperatureDeltas: Map<String, Double> = emptyMap()
    ): ReadinessReport {
        val byDate = entries.associateBy { it.date }
        val current = byDate[today]
            ?: return ReadinessReport(null, 0, emptyList(), "Save today's check-in to compare with your own history.")
        val previousDays = (1..WINDOW_DAYS).map { today.minusDays(it.toLong()) }
        val reasons = mutableListOf<String>()
        val missing = mutableListOf<String>()
        var compared = 0
        var cautions = 0
        var favorable = 0
        var severe = false
        var highSubjectiveStrain = false

        fun median(values: List<Double>): Double? {
            if (values.size < MIN_BASELINE_DAYS) return null
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 0) (sorted[middle - 1] + sorted[middle]) / 2.0 else sorted[middle]
        }

        fun number(value: Double) = String.format(Locale.US, "%.1f", value)
        fun ordinal(value: Double, body: Boolean = false): String {
            return if (body) when {
                value < 1.5 -> "No strain"
                value < 2.5 -> "Some strain"
                else -> "Strong strain"
            } else when {
                value < 1.5 -> "Low"
                value < 2.5 -> "Typical"
                else -> "High"
            }
        }

        val sleepValues = previousDays.mapNotNull { day ->
            health[day.toString()]?.first?.takeIf { it.isFinite() && it in 0.0..18.0 }
                ?: byDate[day]?.sleepHours
        }
        val sleepBaseline = median(sleepValues)
        if (current.sleepHours != null && sleepBaseline != null) {
            val delta = current.sleepHours - sleepBaseline
            reasons += "Sleep: ${number(current.sleepHours)} h vs your 28-day median of ${number(sleepBaseline)} h (${sleepValues.size} nights; ${if (delta >= 0) "+" else ""}${number(delta)} h)."
            compared++
            if (delta <= -1.0) cautions++
            if (delta <= -2.0) severe = true
            if (delta >= -0.25) favorable++
        } else if (current.sleepHours == null) {
            reasons += "Sleep: no value saved for today, so it did not affect this suggestion."
        } else {
            missing += "Sleep: ${sleepValues.size}/$MIN_BASELINE_DAYS days"
        }

        fun compareOrdinal(
            name: String,
            currentValue: Int,
            baselineValues: List<Double>,
            lowerIsWorse: Boolean,
            body: Boolean = false
        ) {
            val baseline = median(baselineValues)
            if (baseline == null) {
                missing += "$name: ${baselineValues.size}/$MIN_BASELINE_DAYS check-ins"
                return
            }
            val delta = currentValue - baseline
            reasons += "$name: ${ordinal(currentValue.toDouble(), body)} vs your personal median of ${ordinal(baseline, body)} (${baselineValues.size} check-ins)."
            compared++
            val caution = if (lowerIsWorse) delta <= -0.75 else delta >= 0.75
            if (caution) cautions++
            if (currentValue == 1 && lowerIsWorse && caution) severe = true
            if (body && currentValue == 3 && caution) severe = true
            if ((currentValue == 3 && !lowerIsWorse) || (currentValue == 1 && lowerIsWorse)) {
                highSubjectiveStrain = true
            }
            val supportsPush = if (lowerIsWorse) currentValue >= 2 && delta >= 0.0
                else currentValue <= 2 && delta <= 0.0
            if (!caution && supportsPush) favorable++
        }

        compareOrdinal(
            "Energy",
            current.energy,
            previousDays.mapNotNull { byDate[it]?.energy?.toDouble() },
            lowerIsWorse = true
        )
        compareOrdinal(
            "Stress",
            current.stress,
            previousDays.mapNotNull { byDate[it]?.stress?.toDouble() },
            lowerIsWorse = false
        )
        compareOrdinal(
            "Body strain",
            current.bodyStrain,
            previousDays.mapNotNull { byDate[it]?.bodyStrain?.toDouble() },
            lowerIsWorse = false,
            body = true
        )

        val yesterday = today.minusDays(1)
        val yesterdaySteps = health[yesterday.toString()]?.second?.takeIf { it.isFinite() && it >= 0.0 }
        val stepDays = (2..(WINDOW_DAYS + 1)).map { today.minusDays(it.toLong()) }
        val stepValues = stepDays.mapNotNull {
            health[it.toString()]?.second?.takeIf { value -> value.isFinite() && value >= 0.0 }
        }
        val stepBaseline = median(stepValues)
        var highActivity = false
        if (yesterdaySteps != null && stepBaseline != null && stepBaseline > 0.0) {
            val ratio = yesterdaySteps / stepBaseline
            reasons += "Yesterday's steps were ${number(ratio)}× your 28-day median (${stepValues.size} days). This is a rough activity clue and may miss workouts."
            compared++
            highActivity = ratio >= 1.5
        } else if (yesterdaySteps != null && stepBaseline == null) {
            missing += "Steps: ${stepValues.size}/$MIN_BASELINE_DAYS days"
        }

        val temperatureTrend = personalSkinTemperatureTrend(today, skinTemperatureDeltas)
        if (temperatureTrend != null) {
            val deviation = String.format(Locale.US, "%+.1f", temperatureTrend.deviationCelsius)
            reasons += "Peripheral skin-temperature trend: ${deviation}°C versus your own preceding 28-day median (${temperatureTrend.baselineDays} measured days; latest ${temperatureTrend.date}). This is context only, not core body temperature, and it does not change the readiness level by itself."
        } else if (skinTemperatureDeltas.isNotEmpty()) {
            reasons += "Peripheral skin temperature needs a reading today or yesterday and at least 7 earlier measured days before Atlas can compare it."
        }

        val level = when {
            compared == 0 -> null
            severe || cautions >= 2 -> ReadinessLevel.RECOVER
            highActivity || highSubjectiveStrain || cautions > 0 -> ReadinessLevel.MAINTAIN
            favorable >= 2 -> ReadinessLevel.PUSH
            else -> ReadinessLevel.MAINTAIN
        }
        val coverage = when {
            compared > 0 -> {
                val readinessCoverage =
                    "Compared $compared personal readiness signal${if (compared == 1) "" else "s"} from the last 28 days. Each baseline needs at least $MIN_BASELINE_DAYS recorded days. No population averages are used."
                if (temperatureTrend != null) {
                    "$readinessCoverage Skin-temperature trend is context only."
                } else {
                    readinessCoverage
                }
            }
            temperatureTrend != null ->
                "Skin-temperature trend adds personal context only; readiness baselines still need at least $MIN_BASELINE_DAYS recorded days."
            else ->
                "No readiness comparison is ready yet. Each baseline needs at least $MIN_BASELINE_DAYS recorded days in the last 28. No population averages are used."
        }
        val evidence = reasons + missing.map { "$it recorded; Atlas is still building that baseline." }
        return ReadinessReport(level, compared, evidence, coverage)
    }
}