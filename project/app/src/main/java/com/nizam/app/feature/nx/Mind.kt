package com.nizam.app.feature.nx

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.feature.next.JsonStore
import org.json.JSONArray

/**
 * The mind of the app. It watches how Atlas is used (which tool, when, and what came next) and
 * uses that to suggest what to open, show what is related, and tell the user what it has learned
 * about this tool. It runs on the phone, learns from use only, and never records private areas.
 */
object Mind {
    private const val DAY = 86_400_000L
    private const val KEEP = 600
    private val PRIVATE = setOf("joytime")

    private fun prefs(c: Context) = c.getSharedPreferences("mind", 0)

    private fun events(c: Context): List<Pair<String, Long>> = runCatching {
        val a = JSONArray(prefs(c).getString("ev", "[]"))
        (0 until a.length()).map { a.getJSONArray(it).let { e -> e.getString(0) to e.getLong(1) } }
    }.getOrDefault(emptyList())

    private fun private(id: String): Boolean {
        if (id in PRIVATE) return true
        return (Nx.find(id) as? LogSpec)?.sealed == true
    }

    fun opened(c: Context, id: String) {
        if (private(id)) return
        runCatching {
            val list = events(c)
            // opening the same tool twice in a row is one visit
            if (list.lastOrNull()?.first == id && System.currentTimeMillis() - list.last().second < 60_000L) return
            val a = JSONArray()
            (list + (id to System.currentTimeMillis())).takeLast(KEEP).forEach { a.put(JSONArray().put(it.first).put(it.second)) }
            prefs(c).edit().putString("ev", a.toString()).apply()
        }
    }

    @Volatile private var countsAt = 0L
    @Volatile private var countsCache: Map<String, Int> = emptyMap()

    /** How many times each tool has been opened (remembered for a few seconds, since hubs ask often). */
    fun counts(c: Context): Map<String, Int> {
        val now = System.currentTimeMillis()
        if (now - countsAt < 5_000L) return countsCache
        countsCache = events(c).groupingBy { it.first }.eachCount(); countsAt = now
        return countsCache
    }

    // facts about the person that many tools ask for: remembered once, offered everywhere
    private val PROFILE = listOf("city", "age", "salary", "income", "job", "role", "skill", "family", "people at home", "weight", "height", "country", "language")
    private fun profileKey(f: F): String? {
        val l = f.label.lowercase()
        if (f.kind == K.LONG || f.kind == K.DATE || f.kind == K.CHOICE) return null
        return PROFILE.firstOrNull { l.contains(it) }?.let { "p_" + l.replace(Regex("[^a-z]"), "").take(30) }
    }
    fun shared(c: Context, f: F): String? = profileKey(f)?.let { prefs(c).getString(it, null) }?.takeIf { it.isNotBlank() }
    fun remember(c: Context, f: F, value: String) {
        val k = profileKey(f) ?: return
        if (value.isBlank() || value.length > 60) return
        prefs(c).edit().putString(k, value.trim()).apply()
    }

    /** A thumbs up or down on an answer. Repeated thumbs down teaches Atlas to be more specific for that tool. */
    fun rate(c: Context, id: String, title: String, good: Boolean) {
        val p = c.getSharedPreferences("mind_pref", 0)
        val key = (if (good) "up_" else "down_") + id
        val n = p.getInt(key, 0) + 1
        p.edit().putInt(key, n).apply()
        if (!good && n == 2) com.nizam.app.feature.brain2.PersonalModel.setLessons(c,
            com.nizam.app.feature.brain2.PersonalModel.lessons(c) + "Answers in \"$title\" were not hitting the mark twice: be more specific and concrete there")
        if (good && n == 3) com.nizam.app.feature.brain2.PersonalModel.addFact(c, "Finds \"$title\" useful")
    }

    /** Tools opened soonest after [id], most common first. */
    fun after(c: Context, id: String, n: Int = 3): List<Spec> {
        val ev = events(c)
        val next = HashMap<String, Int>()
        for (i in 0 until ev.size - 1) {
            if (ev[i].first == id && ev[i + 1].first != id && ev[i + 1].second - ev[i].second < 30 * 60_000L)
                next.merge(ev[i + 1].first, 1, Int::plus)
        }
        return next.entries.sortedByDescending { it.value }.mapNotNull { Nx.find(it.key) }.take(n)
    }

    /** Tools this person usually opens around this hour. */
    fun now(c: Context, n: Int = 3): List<Spec> {
        val hour = java.time.LocalTime.now().hour
        val counts = HashMap<String, Int>()
        events(c).forEach { (id, ts) ->
            val h = java.time.Instant.ofEpochMilli(ts).atZone(java.time.ZoneId.systemDefault()).hour
            if (kotlin.math.abs(h - hour) <= 1 || kotlin.math.abs(h - hour) >= 23) counts.merge(id, 1, Int::plus)
        }
        return counts.entries.filter { it.value >= 2 }.sortedByDescending { it.value }.mapNotNull { Nx.find(it.key) }.take(n)
    }

    fun related(spec: Spec, n: Int = 3): List<Spec> {
        fun words(s: Spec) = (s.title + " " + s.words).lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 3 }.toSet()
        val mine = words(spec)
        return Nx.all.asSequence().filter { it.id != spec.id && !private(it.id) }
            .map { o -> o to (words(o).count { it in mine } * 2 + (if (o.hub == spec.hub) 1 else 0)) }
            .filter { it.second >= 3 }.sortedByDescending { it.second }.take(n).map { it.first }.toList()
    }

    /** What the app has learned about this one tool, in a line or two. */
    fun learned(c: Context, spec: Spec): List<String> {
        val out = mutableListOf<String>()
        val mine = events(c).filter { it.first == spec.id }
        if (mine.isNotEmpty()) {
            val last = (System.currentTimeMillis() - mine.last().second) / DAY
            out += "You have opened this ${mine.size} time${if (mine.size == 1) "" else "s"}" +
                if (mine.size > 1) ", last ${if (last <= 0) "earlier today" else "$last day${if (last == 1L) "" else "s"} ago"}." else "."
        }
        if (spec is LogSpec && !spec.sealed) runCatching {
            val e = JsonStore(c, "nxlog_${spec.id}").all()
            if (e.isNotEmpty()) {
                val week = e.count { System.currentTimeMillis() - it.optLong("t", 0L) < 7 * DAY }
                val prev = e.count { val age = System.currentTimeMillis() - it.optLong("t", 0L); age in (7 * DAY)..(14 * DAY) }
                out += "${e.size} entries so far; $week this week" + if (prev > 0) " (${prev} the week before)." else "."
            }
        }
        return out
    }
}

@Composable
internal fun MindDock(spec: Spec, open: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    var shown by remember { mutableStateOf(false) }
    val learned = remember(spec.id, shown) { if (shown) Mind.learned(c, spec) else emptyList() }
    val next = remember(spec.id, shown) { if (shown) Mind.after(c, spec.id) else emptyList() }
    val related = remember(spec.id, shown) { if (shown) Mind.related(spec) else emptyList() }
    Box(modifier) {
        if (!shown) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.88f), CircleShape)
                .clickable { shown = true }, contentAlignment = Alignment.Center) {
                Text("✦", color = MaterialTheme.colorScheme.onPrimary)
            }
        } else {
            Column(Modifier.fillMaxWidth(0.86f).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("What Atlas knows here", fontWeight = FontWeight.SemiBold)
                if (learned.isEmpty() && next.isEmpty() && related.isEmpty()) Text("Still learning how you use this. Use it a few times and I will have more to say.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                learned.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (next.isNotEmpty()) Text("You often open next", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                next.forEach { s -> TextButton(onClick = { shown = false; open("nx/${s.id}") }) { Text(s.title) } }
                if (related.isNotEmpty()) Text("Related", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                related.forEach { s -> TextButton(onClick = { shown = false; open("nx/${s.id}") }) { Text(s.title) } }
                TextButton(onClick = { shown = false }) { Text("Close") }
            }
        }
    }
}
