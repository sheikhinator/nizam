package com.nizam.app.feature.nx

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Row
import kotlinx.coroutines.launch
import com.nizam.app.net.Ai
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

    /** Context handoff: the last tool opened in the past half hour (not Atlas's own chat), so the next screen can carry on from it. */
    fun lastVisited(c: Context): Spec? = events(c).lastOrNull { it.first !in setOf("atlastalk", "find", "pulse") }
        ?.takeIf { System.currentTimeMillis() - it.second < 30 * 60_000L }?.let { Nx.find(it.first) }

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
    // a feature the merge upgraded gains its new power here, in place
    val power = remember(spec.id) { com.nizam.app.feature.expertise.LifeFeatures.attachedTo(spec.id) }
    var lensOpen by remember { mutableStateOf(false) }
    if (lensOpen && power != null) com.nizam.app.feature.expertise.LensSheet(power, open) { lensOpen = false }
    Box(modifier) {
        if (!shown) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.88f), CircleShape)
                .clickable { shown = true }, contentAlignment = Alignment.Center) {
                Text("✦", color = MaterialTheme.colorScheme.onPrimary)
            }
            if (power != null) Box(Modifier.align(Alignment.TopEnd).size(10.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
        } else {
            Column(Modifier.fillMaxWidth(0.86f).background(MaterialTheme.colorScheme.surface, RoundedCornerShape(18.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (power != null) {
                    Text("Across your life", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    Text(power.what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { shown = false; lensOpen = true }) { Text("✦ ${power.title}") }
                }
                Text("What Atlas knows here", fontWeight = FontWeight.SemiBold)
                if (learned.isEmpty() && next.isEmpty() && related.isEmpty()) Text("Still learning how you use this. Use it a few times and I will have more to say.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                learned.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (next.isNotEmpty()) Text("You often open next", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                next.forEach { s -> TextButton(onClick = { shown = false; open("nx/${s.id}") }) { Text(s.title) } }
                if (related.isNotEmpty()) Text("Related", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                related.forEach { s -> TextButton(onClick = { shown = false; open("nx/${s.id}") }) { Text(s.title) } }
                ScreenPowers(spec, open) { shown = false }
                TextButton(onClick = { shown = false }) { Text("Close") }
            }
        }
    }
}

/**
 * Powers every screen gets from the ✦ button: ask Atlas about this screen with your own data,
 * pin it to Today, put it on the phone's home screen, get a daily reminder to use it, share it.
 */
@Composable
internal fun ScreenPowers(spec: Spec, open: (String) -> Unit, close: () -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val route = "nx/${spec.id}"
    var pinned by remember(spec.id) { mutableStateOf(com.nizam.app.feature.modes.Pins.isPinned(c, route)) }
    var q by remember(spec.id) { mutableStateOf("") }
    var answer by remember(spec.id) { mutableStateOf<String?>(null) }
    var busy by remember(spec.id) { mutableStateOf(false) }
    val remindPrefs = remember { c.getSharedPreferences("screen_remind", 0) }
    var remind by remember(spec.id) { mutableStateOf(remindPrefs.getInt(spec.id, -1)) }
    Text("Do more here", fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = { com.nizam.app.feature.modes.Pins.togglePin(c, route); pinned = !pinned
            com.nizam.app.ui.components.Feedback.show(if (pinned) "Pinned to Today" else "Unpinned") }) { Text(if (pinned) "📌 Unpin" else "📌 Pin to Today") }
        TextButton(onClick = {
            runCatching {
                val sm = c.getSystemService(android.content.pm.ShortcutManager::class.java)
                val info = android.content.pm.ShortcutInfo.Builder(c, "nx_" + spec.id).setShortLabel(spec.title.take(24)).setLongLabel(spec.title)
                    .setIcon(android.graphics.drawable.Icon.createWithResource(c, com.nizam.app.R.mipmap.ic_launcher))
                    .setIntent(android.content.Intent(c, com.nizam.app.MainActivity::class.java).setAction(android.content.Intent.ACTION_VIEW).putExtra("route", route)).build()
                if (sm?.isRequestPinShortcutSupported == true) sm.requestPinShortcut(info, null) else com.nizam.app.ui.components.Feedback.show("Your launcher doesn't allow shortcuts")
            }.onFailure { com.nizam.app.ui.components.Feedback.show("Couldn't add the shortcut") }
        }) { Text("📱 Home screen") }
        TextButton(onClick = {
            val next = when (remind) { -1 -> 9; 9 -> 13; 13 -> 20; else -> -1 }
            remind = next
            if (next < 0) remindPrefs.edit().remove(spec.id).apply() else remindPrefs.edit().putInt(spec.id, next).apply()
            com.nizam.app.ui.components.Feedback.show(if (next < 0) "Reminder off" else "I'll remind you daily at ${if (next > 12) next - 12 else next}${if (next >= 12) "pm" else "am"}")
        }) { Text(if (remind < 0) "⏰ Remind me" else "⏰ ${if (remind > 12) remind - 12 else remind}${if (remind >= 12) "pm" else "am"}") }
        TextButton(onClick = { Share.text(c, "${spec.title}: ${spec.blurb}\nIn Atlas: search \"${spec.title}\".", spec.title) }) { Text("↗ Share") }
    }
    val container = remember { (c.applicationContext as? com.nizam.app.NizamApplication)?.container }
    if (container != null) Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.OutlinedTextField(q, { q = it }, Modifier.weight(1f), singleLine = true, placeholder = { Text("Ask about ${spec.title}") },
            trailingIcon = { MicButton({ heard -> q = heard }) })
        TextButton(onClick = {
            busy = true; answer = null
            scope.launch {
                runCatching {
                    val known = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Roads.relevant(c, q + " " + spec.title + " " + spec.words, 3000) }
                    Ai.generate(container.settingsRepository, "The user is on the \"${spec.title}\" screen of their Atlas app (${spec.blurb}).\n" +
                        (if (known.isNotBlank()) "The user's own recorded data, shared by them for this question (private, use only to answer):\n$known\n\n" else "") +
                        "Question: $q\nAnswer briefly and practically, using their data where it helps.",
                        system = "You are Atlas, a practical personal assistant inside the user's own app.", maxOutputTokens = 700)
                }.onSuccess { answer = it }.onFailure { com.nizam.app.ui.components.Feedback.show(it.message ?: "Couldn't answer") }
                busy = false
            }
        }, enabled = q.isNotBlank() && !busy) { Text(if (busy) "…" else "Ask") }
    }
    answer?.let { Box(Modifier.heightIn(max = 260.dp).verticalScroll(androidx.compose.foundation.rememberScrollState())) { Md(it) } }
}

/** Daily reminders set from any screen's ✦ panel. Runs from the background loop. */
object ScreenReminders {
    fun tick(c: android.content.Context, now: Double) {
        val p = c.getSharedPreferences("screen_remind", 0)
        val sent = c.getSharedPreferences("screen_remind_sent", 0)
        val today = java.time.LocalDate.now().toString()
        p.all.forEach { (id, v) ->
            val h = (v as? Int) ?: return@forEach
            if (now < h || now > h + 0.5 || sent.getString(id, "") == today) return@forEach
            sent.edit().putString(id, today).apply()
            val spec = Nx.find(id) ?: return@forEach
            com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 400 + (id.hashCode() and 63),
                spec.title, "Your daily reminder: ${spec.blurb}", route = "nx/$id")
        }
    }
}
