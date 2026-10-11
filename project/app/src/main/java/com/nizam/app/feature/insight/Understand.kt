package com.nizam.app.feature.insight

import android.content.Context
import com.nizam.app.AppContainer
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sqrt

/** One day of your life as numbers. Missing values stay null — never guessed as zero. */
data class DayVector(val date: LocalDate, val values: Map<String, Double?>)

val METRICS = linkedMapOf(
    "mood" to "Mood", "sleep" to "Sleep (h)", "steps" to "Steps", "sets" to "Gym sets",
    "study" to "Study (min)", "prayers" to "Prayers", "spend" to "Spending", "focus" to "Focus (min)"
)

object Days {
    /** Builds day vectors from every source Atlas has, over any window — not just the XP engine's 30 days. */
    suspend fun build(c: AppContainer, context: Context, days: Int): List<DayVector> {
        val from = LocalDate.now().minusDays(days - 1L)
        val zone = ZoneId.systemDefault()
        fun dayOf(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

        val moods = HashMap<LocalDate, MutableList<Int>>()
        c.diaryRepository.entries().first().forEach { e ->
            runCatching { LocalDate.parse(e.localDate) }.getOrNull()?.let { moods.getOrPut(it) { mutableListOf() } += e.mood }
        }
        c.diaryRepository.checkIns().first().forEach { e ->
            runCatching { LocalDate.parse(e.localDate) }.getOrNull()?.let { moods.getOrPut(it) { mutableListOf() } += e.mood }
        }
        val sets = c.gymRepository.sets().first().groupingBy { it.localDate }.eachCount()
        val study = c.learningRepository.sessions().first().groupBy { it.localDate }.mapValues { it.value.sumOf { s -> s.minutes } }
        val spend = c.financeRepository.since(from.toString()).first()
            .filter { it.kind == "EXPENSE" }.groupBy { it.localDate }.mapValues { it.value.sumOf { t -> t.amount } }
        val prayers = c.prayerRepository.since(from.toString()).first().associate { d ->
            d.localDate to listOf(d.fajr, d.dhuhr, d.asr, d.maghrib, d.isha).count { it != "NOT_PRAYED" }
        }
        val health = HealthCache(context).load()
        val focus = FocusStore(context).minutesByDay()

        return (0 until days).map { i ->
            val d = from.plusDays(i.toLong()); val k = d.toString()
            DayVector(d, mapOf(
                "mood" to moods[d]?.average(),
                "sleep" to health[k]?.first,
                "steps" to health[k]?.second,
                "sets" to sets[k]?.toDouble(),
                "study" to study[k]?.toDouble(),
                "prayers" to prayers[k]?.toDouble(),
                "spend" to spend[k],
                "focus" to focus[k]?.toDouble()
            ))
        }
    }
}

data class Finding(val a: String, val b: String, val r: Double, val n: Int, val sentence: String)

/**
 * Pearson correlation across every pair of metrics, only over days where both were recorded.
 * Findings need 10+ shared days and |r| ≥ 0.3 — below that it's noise dressed up as insight.
 */
object Correlations {
    fun pearson(xs: List<Double>, ys: List<Double>): Double? {
        val n = xs.size
        if (n < 3) return null
        val mx = xs.average(); val my = ys.average()
        var num = 0.0; var dx = 0.0; var dy = 0.0
        for (i in 0 until n) {
            val a = xs[i] - mx; val b = ys[i] - my
            num += a * b; dx += a * a; dy += b * b
        }
        if (dx == 0.0 || dy == 0.0) return null
        return num / sqrt(dx * dy)
    }

    /** Pairs each day's X with the NEXT day's Y too, because sleep predicts tomorrow's mood, not today's. */
    fun findings(days: List<DayVector>): List<Finding> {
        val keys = METRICS.keys.toList()
        val out = mutableListOf<Finding>()
        for (i in keys.indices) for (j in keys.indices) {
            if (i == j) continue
            val a = keys[i]; val b = keys[j]
            for (lag in 0..1) {
                if (lag == 0 && j < i) continue   // same-day pairs are symmetric; count once
                val pairs = days.indices.mapNotNull { d ->
                    val x = days[d].values[a]; val y = days.getOrNull(d + lag)?.values?.get(b)
                    if (x != null && y != null) x to y else null
                }
                if (pairs.size < 10) continue
                val r = pearson(pairs.map { it.first }, pairs.map { it.second }) ?: continue
                if (abs(r) < 0.3) continue
                val strength = when { abs(r) >= 0.6 -> "strongly"; abs(r) >= 0.45 -> "clearly"; else -> "somewhat" }
                val dir = if (r > 0) "higher" else "lower"
                val when_ = if (lag == 1) "the next day" else "that day"
                out += Finding(a, b, r, pairs.size,
                    "On days with more ${METRICS[a]!!.lowercase()}, your ${METRICS[b]!!.lowercase()} is $strength $dir $when_.")
            }
        }
        return out.sortedByDescending { abs(it.r) }.distinctBy { setOf(it.a, it.b) }.take(8)
    }
}

// ── Self-experiments ─────────────────────────────────────────────────────────
data class Experiment(
    val id: Long, val title: String, val hypothesis: String, val metric: String,
    val start: String, val days: Int, val closed: Boolean = false, val verdict: String = ""
) {
    val end: LocalDate get() = LocalDate.parse(start).plusDays(days.toLong())
}

data class ExperimentResult(val baseline: Double?, val during: Double?, val baseDays: Int, val duringDays: Int) {
    val change: Double? get() = if (baseline != null && during != null && baseline != 0.0) (during - baseline) / abs(baseline) * 100 else null
}

object Experiments {
    /** Compares the experiment window against the same number of days just before it started. */
    fun evaluate(e: Experiment, days: List<DayVector>): ExperimentResult {
        val start = LocalDate.parse(e.start)
        val base = days.filter { it.date < start && it.date >= start.minusDays(e.days.toLong()) }
            .mapNotNull { it.values[e.metric] }
        val during = days.filter { it.date >= start && it.date < e.end && it.date <= LocalDate.now() }
            .mapNotNull { it.values[e.metric] }
        return ExperimentResult(base.takeIf { it.isNotEmpty() }?.average(), during.takeIf { it.isNotEmpty() }?.average(),
            base.size, during.size)
    }
}

private fun File.readJsonArray(): JSONArray = runCatching { JSONArray(readText()) }.getOrElse { JSONArray() }

class ExperimentStore(context: Context) {
    private val file = File(context.filesDir, "experiments.json")
    fun all(): List<Experiment> = file.readJsonArray().let { a ->
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Experiment(o.optLong("id"), o.optString("title"), o.optString("hypothesis"), o.optString("metric"),
                o.optString("start"), o.optInt("days", 14), o.optBoolean("closed"), o.optString("verdict"))
        }
    }
    fun save(list: List<Experiment>) = file.writeText(JSONArray().apply {
        list.forEach {
            put(JSONObject().put("id", it.id).put("title", it.title).put("hypothesis", it.hypothesis)
                .put("metric", it.metric).put("start", it.start).put("days", it.days)
                .put("closed", it.closed).put("verdict", it.verdict))
        }
    }.toString())
}

// ── Focus sessions ───────────────────────────────────────────────────────────
class FocusStore(context: Context) {
    private val file = File(context.filesDir, "focus.json")
    fun log(minutes: Int, label: String) {
        val a = file.readJsonArray()
        a.put(JSONObject().put("date", LocalDate.now().toString()).put("minutes", minutes).put("label", label))
        file.writeText(a.toString())
    }
    fun minutesByDay(): Map<String, Int> = file.readJsonArray().let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.groupBy { it.optString("date") }
            .mapValues { it.value.sumOf { o -> o.optInt("minutes") } }
    }
}

/** Health Connect results cached per day: date → (sleep hours, steps). */
class HealthCache(context: Context) {
    private val file = File(context.filesDir, "health.json")
    fun load(): Map<String, Pair<Double?, Double?>> = runCatching {
        val o = JSONObject(file.readText())
        o.keys().asSequence().associateWith { k ->
            val d = o.getJSONObject(k)
            (if (d.has("sleep")) d.getDouble("sleep") else null) to (if (d.has("steps")) d.getDouble("steps") else null)
        }
    }.getOrElse { emptyMap() }
    fun save(map: Map<String, Pair<Double?, Double?>>) = file.writeText(JSONObject().apply {
        map.forEach { (k, v) ->
            put(k, JSONObject().apply { v.first?.let { put("sleep", it) }; v.second?.let { put("steps", it) } })
        }
    }.toString())
}
