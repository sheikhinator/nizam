package com.nizam.app.feature.sci

import android.content.Context
import com.nizam.app.AppContainer
import com.nizam.app.feature.omni.O
import com.nizam.app.feature.pro.Promises
import com.nizam.app.feature.pro.Sleep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * The life graph: one connected picture of your data that every scientific view draws from.
 *  - series: one row of numbers per day for the last 30 days (prayers, tasks, spending, sleep and so on)
 *  - nodes: your areas, plus real things inside them (open tasks, promises, people, linked notes)
 *  - edges: how they connect: an area holds an item, a promise belongs to a person, a note links to a note with [[its name]],
 *    and two areas "move together" when 14+ days of your own numbers show it
 */
object LifeGraph {
    class Node(val id: String, val label: String, val kind: String, val route: String?, val value: String = "")
    class Edge(val a: String, val b: String, val kind: String, val weight: Float = 0f, val label: String = "")
    class Corr(val a: String, val b: String, val r: Float, val n: Int)
    class Snapshot(val dates: List<LocalDate>, val days: List<JSONObject>, val nodes: List<Node>, val edges: List<Edge>, val corr: List<Corr>, val at: Long) {
        /** The last [last] days of one metric, oldest first. */
        fun series(key: String, last: Int = 7): List<Float> = days.takeLast(last).map { it.optDouble(key, 0.0).toFloat() }
        fun today(key: String): Float = days.lastOrNull()?.optDouble(key, 0.0)?.toFloat() ?: 0f
        fun neighbours(id: String): List<Pair<Node, Edge>> = edges.filter { it.a == id || it.b == id }.mapNotNull { e -> nodes.firstOrNull { it.id == (if (e.a == id) e.b else e.a) }?.let { it to e } }
    }

    val METRICS = listOf("prayers", "tasks_done", "spent", "sleep", "water_ml", "gym_sets", "habits", "mood", "screen_unlocks")
    val NAMES = mapOf("prayers" to "Prayers", "tasks_done" to "Tasks", "spent" to "Money", "sleep" to "Sleep", "water_ml" to "Water", "gym_sets" to "Gym",
        "habits" to "Habits", "mood" to "Mood", "screen_unlocks" to "Phone")
    val ROUTES = mapOf("prayers" to "prayer", "tasks_done" to "tasks", "spent" to "finance", "sleep" to "nx/sleep", "water_ml" to "diet", "gym_sets" to "gym",
        "habits" to "habits2", "mood" to "diary", "screen_unlocks" to "insights")
    const val MIN_DAYS = 14
    const val MIN_R = 0.35f

    @Volatile var cached: Snapshot? = null
        private set

    fun pearson(x: List<Float>, y: List<Float>): Float? {
        if (x.size < 3 || x.size != y.size) return null
        val mx = x.average().toFloat(); val my = y.average().toFloat()
        var sxy = 0f; var sxx = 0f; var syy = 0f
        for (i in x.indices) { val a = x[i] - mx; val b = y[i] - my; sxy += a * b; sxx += a * a; syy += b * b }
        if (sxx < 1e-6f || syy < 1e-6f) return null
        return (sxy / sqrt(sxx * syy)).coerceIn(-1f, 1f)
    }

    private val WIKI = Regex("\\[\\[([^\\]|#]+)")

    suspend fun load(container: AppContainer, c: Context, daysBack: Int = 30, force: Boolean = false): Snapshot = withContext(Dispatchers.IO) {
        cached?.let { if (!force && System.currentTimeMillis() - it.at < 60_000L) return@withContext it }
        val today = LocalDate.now()
        val dates = (daysBack - 1 downTo 0).map { today.minusDays(it.toLong()) }
        val sleepBy = runCatching { Sleep.store(c).all().associate { it.optString("date") to Sleep.hours(it) } }.getOrDefault(emptyMap())
        val days = dates.map { d -> (runCatching { O.day(container, d) }.getOrNull() ?: JSONObject().put("date", d.toString())).also { o -> sleepBy[d.toString()]?.let { o.put("sleep", it) } } }

        // which areas move together, from days where both have a number
        val corr = ArrayList<Corr>()
        for (i in METRICS.indices) for (j in i + 1 until METRICS.size) {
            val a = METRICS[i]; val b = METRICS[j]
            val both = days.filter { it.has(a) && it.has(b) }
            if (both.size < MIN_DAYS) continue
            val r = pearson(both.map { it.optDouble(a).toFloat() }, both.map { it.optDouble(b).toFloat() }) ?: continue
            if (abs(r) >= MIN_R) corr += Corr(a, b, r, both.size)
        }
        corr.sortByDescending { abs(it.r) }

        val nodes = ArrayList<Node>(); val edges = ArrayList<Edge>()
        val last = days.lastOrNull() ?: JSONObject()
        fun fmt(k: String): String = if (!last.has(k)) "–" else last.optDouble(k).let { v -> when (k) { "sleep" -> Sleep.fmt(v); "spent" -> "%,.0f".format(v); "mood" -> "%.1f".format(v); else -> v.toInt().toString() } }
        METRICS.forEach { k -> nodes += Node("m:$k", NAMES[k] ?: k, "area", ROUTES[k], fmt(k)) }
        corr.take(8).forEach { edges += Edge("m:${it.a}", "m:${it.b}", "corr", it.r, "%+.2f".format(it.r)) }

        val people = HashMap<String, String>()
        fun person(name: String): String { val key = name.trim().lowercase(); return people.getOrPut(key) { val id = "p:$key"; nodes += Node(id, name.trim(), "person", "nx/promises"); id } }
        runCatching {
            Promises.all(c).filter { Promises.open(it) }.take(8).forEach { p ->
                val id = "pr:" + p.optString("id")
                if (nodes.none { it.id == "m:promises" }) nodes += Node("m:promises", "Promises", "area", "nx/promises")
                nodes += Node(id, p.optString("text").take(28), "promise", "nx/promises", Promises.dueLabel(p))
                edges += Edge("m:promises", id, "has")
                p.optString("who").takeIf { it.isNotBlank() }?.let { edges += Edge(id, person(it), "person") }
            }
        }
        runCatching {
            container.taskRepository.all().first().filter { it.completedAt == null && it.deletedAt == null }.take(8).forEach { t ->
                val id = "t:${t.id}"
                nodes += Node(id, t.title.take(28), "task", "tasks")
                edges += Edge("m:tasks_done", id, "has")
                people.forEach { (key, pid) -> if (key.length >= 3 && t.title.lowercase().contains(key)) edges += Edge(id, pid, "person") }
            }
        }
        runCatching {
            val notes = container.noteRepository.everything()
            val byTitle = notes.associateBy { it.title.trim().lowercase() }
            val linked = LinkedHashMap<Long, com.nizam.app.feature.notes.Note>(); val pairs = ArrayList<Pair<Long, Long>>()
            notes.forEach { n -> WIKI.findAll(n.body).forEach { m -> byTitle[m.groupValues[1].trim().lowercase()]?.let { t -> if (t.id != n.id && linked.size < 14) { linked[n.id] = n; linked[t.id] = t; pairs += n.id to t.id } } } }
            if (linked.isNotEmpty()) nodes += Node("m:notes", "Notes", "area", "notes", "${notes.size}")
            linked.values.forEach { n -> nodes += Node("n:${n.id}", n.title.take(28), "note", "notes"); edges += Edge("m:notes", "n:${n.id}", "has") }
            pairs.distinct().forEach { (a, b) -> if (linked.containsKey(a) && linked.containsKey(b)) edges += Edge("n:$a", "n:$b", "wiki") }
        }
        Snapshot(dates, days, nodes, edges, corr, System.currentTimeMillis()).also { cached = it }
    }
}
