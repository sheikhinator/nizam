package com.nizam.app.feature.nx

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AltRoute
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.launch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.brain2.PersonalModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.ui.design.Hub
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * The roads: one place where everything the user records in Atlas can be read the same way.
 * Each tracker is a "source" feeding the network. Insight tools ask the roads for what matters to
 * their question instead of every tool reading its own files, so AI answers use the whole picture
 * and send less text. Private (sealed) logs are never exposed.
 */
object Roads {
    data class Item(val source: String, val hub: Hub, val ts: Long, val text: String)
    data class Source(val id: String, val label: String, val hub: Hub, val count: Int, val lastTs: Long)

    private const val DAY = 86_400_000L

    private fun settings(c: Context) = c.getSharedPreferences("roads", 0)
    fun chatEnabled(c: Context) = settings(c).getBoolean("chat", false)
    fun setChatEnabled(c: Context, on: Boolean) = settings(c).edit().putBoolean("chat", on).apply()

    /**
     * For ordinary questions to Atlas: when the user allows it, the matching part of their own data,
     * so answers use their real life. Off by default. Never used for background work.
     */
    suspend fun chatContext(c: Context, question: String): String {
        if (!chatEnabled(c)) return ""
        (c as? com.nizam.app.NizamApplication)?.container?.let { warm(it) }
        val body = relevant(c, question, 3500)
        return if (body.isBlank()) "" else "The user allowed you to use their own recorded data. Use it only when it helps answer; never recite it back.\n$body"
    }

    private fun dateMillis(s: String): Long? =
        runCatching { LocalDate.parse(s.take(10)).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()

    private fun stamp(o: JSONObject, spec: LogSpec): Long {
        spec.fields.firstOrNull { it.kind == K.DATE }?.let { f -> dateMillis(o.optString(f.key))?.let { return it } }
        return o.optString("id").take(13).toLongOrNull() ?: 0L
    }

    private fun logSpecs(c: Context): List<LogSpec> =
        (c.filesDir.listFiles() ?: emptyArray<File>()).mapNotNull { f ->
            val n = f.name
            if (!n.startsWith("nxlog_") || !n.endsWith(".json")) return@mapNotNull null
            (Nx.find(n.removePrefix("nxlog_").removeSuffix(".json")) as? LogSpec)?.takeIf { !it.sealed }
        }.sortedBy { it.id }

    private fun entries(c: Context, spec: LogSpec): List<JSONObject> =
        runCatching { JsonStore(c, "nxlog_${spec.id}").all() }.getOrDefault(emptyList())

    private fun lineOf(o: JSONObject, spec: LogSpec): String =
        spec.fields.mapNotNull { f -> o.optString(f.key).takeIf { it.isNotBlank() }?.let { "${f.label}: ${it.take(120)}" } }.joinToString("; ")

    @Volatile private var cache: List<Item> = emptyList()
    @Volatile private var warmedAt = 0L

    /**
     * Reads the big built-in modules (money, notes, diary, prayer, tasks, habits, gym, diet) once and
     * keeps a copy in memory, so tools can use them without waiting. Call before building an AI prompt.
     */
    suspend fun warm(c: AppContainer, force: Boolean = false) {
        if (!force && System.currentTimeMillis() - warmedAt < 120_000 && cache.isNotEmpty()) return
        cache = withContext(Dispatchers.IO) { runCatching { databaseItems(c) }.getOrDefault(emptyList()) }
        warmedAt = System.currentTimeMillis()
    }

    private suspend fun <T> grab(block: suspend () -> List<T>): List<T> = runCatching { block() }.getOrDefault(emptyList())

    private suspend fun databaseItems(c: AppContainer): List<Item> {
        val from = LocalDate.now().minusDays(90).toString()
        val out = mutableListOf<Item>()
        grab { c.financeRepository.since(from).first() }.forEach { t ->
            out += Item("Money", Hub.Money, dateMillis(t.localDate) ?: t.createdAt,
                "${t.kind.lowercase()} Rs ${"%,.0f".format(t.amount)} on ${t.category}${if (t.note.isBlank()) "" else " (" + t.note.take(60) + ")"} on ${t.localDate}")
        }
        grab { c.noteRepository.everything() }.sortedByDescending { it.updatedAt }.take(60).forEach { n ->
            out += Item("Notes", Hub.Memory, n.updatedAt, "${n.title.take(80)}: ${n.body.replace('\n', ' ').take(160)}")
        }
        grab { c.diaryRepository.entries().first() }.take(40).forEach { d ->
            out += Item("Diary", Hub.Memory, d.createdAt, "${d.localDate} mood ${d.mood}${if (d.emotions.isBlank()) "" else " (" + d.emotions + ")"}: ${d.body.replace('\n', ' ').take(160)}")
        }
        grab { c.diaryRepository.checkIns().first() }.take(40).forEach { m ->
            out += Item("Mood check-ins", Hub.Health, m.timestamp, "${m.localDate} mood ${m.mood}${if (m.emotions.isBlank()) "" else " (" + m.emotions + ")"}")
        }
        grab { c.prayerRepository.since(from).first() }.forEach { p ->
            val parts = listOf("Fajr" to p.fajr, "Dhuhr" to p.dhuhr, "Asr" to p.asr, "Maghrib" to p.maghrib, "Isha" to p.isha)
            val done = parts.count { it.second != "NOT_PRAYED" }
            out += Item("Prayer", Hub.Deen, dateMillis(p.localDate) ?: 0L, "${p.localDate}: $done of 5 prayed (" + parts.joinToString(", ") { "${it.first} ${it.second.lowercase().replace('_', ' ')}" } + ")")
        }
        grab { c.taskRepository.all().first() }.filter { it.deletedAt == null }.take(120).forEach { t ->
            val state = if (t.completedAt != null) "done" else "open"
            out += Item("Tasks", Hub.Work, t.completedAt ?: t.due ?: t.createdAt, "${t.title.take(80)} [$state]${if (t.due != null) " due " + java.time.Instant.ofEpochMilli(t.due).atZone(ZoneId.systemDefault()).toLocalDate() else ""}")
        }
        val habits = grab { c.habitRepository.habits().first() }
        val names = habits.associate { it.id to it.name }
        grab { c.habitRepository.entriesSince(from).first() }.forEach { e ->
            out += Item("Habits", Hub.Health, dateMillis(e.localDate) ?: 0L, "${names[e.habitId] ?: "habit"} done on ${e.localDate}")
        }
        grab { c.gymRepository.sets().first() }.take(60).forEach { g ->
            out += Item("Workouts", Hub.Health, dateMillis(g.localDate) ?: g.createdAt, "${g.exercise} ${g.weight}kg x ${g.reps} on ${g.localDate}")
        }
        grab { c.gymRepository.cardio().first() }.take(40).forEach { g ->
            out += Item("Cardio", Hub.Health, dateMillis(g.localDate) ?: 0L, "${g.type} ${g.minutes} min${if (g.distanceKm > 0) " " + g.distanceKm + " km" else ""} on ${g.localDate}")
        }
        grab { c.dietRepository.loggedDaysSince(from).first() }.forEach { d ->
            out += Item("Food log", Hub.Health, dateMillis(d) ?: 0L, "Logged meals on $d")
        }
        return out
    }

    /** Every connected source that has something in it. */
    fun sources(c: Context): List<Source> = (logSpecs(c).mapNotNull { spec ->
        val e = entries(c, spec)
        if (e.isEmpty()) null else Source(spec.id, spec.title, spec.hub, e.size, e.maxOf { stamp(it, spec) })
    } + cache.groupBy { it.source }.map { (name, list) -> Source(name.lowercase(), name, list.first().hub, list.size, list.maxOf { it.ts }) })
        .sortedByDescending { it.lastTs }

    /** Recent items from every source, newest first. */
    fun items(c: Context, perSource: Int = 40): List<Item> = (logSpecs(c).flatMap { spec ->
        entries(c, spec).takeLast(perSource).mapNotNull { o ->
            val t = lineOf(o, spec)
            if (t.isBlank()) null else Item(spec.title, spec.hub, stamp(o, spec), t)
        }
    } + cache).sortedByDescending { it.ts }

    /** Entries made on each of the last [days] days, oldest first. */
    fun pulse(c: Context, days: Int = 7): List<Int> {
        val today = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val all = items(c, 200)
        return (days - 1 downTo 0).map { back ->
            val from = today - back * DAY
            all.count { it.ts in from until from + DAY }
        }
    }

    /**
     * The part of the user's recorded life that matters for [hint] (the words of the question), as
     * short text for an AI prompt. Items that share words with the question and recent items rank first.
     */
    fun relevant(c: Context, hint: String, maxChars: Int = 5500): String {
        val words = hint.lowercase().split(Regex("[^a-z0-9؀-ۿ]+")).filter { it.length > 3 }.toSet()
        val now = System.currentTimeMillis()
        val ranked = items(c, 40).map { it to score(it, words, now) }.sortedByDescending { it.second }
        val sb = StringBuilder()
        PersonalModel.promptBlock(c).takeIf { it.isNotBlank() }?.let { sb.append(it).append("\n\n") }
        var lastSource = ""
        for ((it, _) in ranked.sortedWith(compareBy({ s -> ranked.indexOfFirst { r -> r.first.source == s.first.source } }, { s -> -s.first.ts }))) {
            if (sb.length > maxChars) break
            if (it.source != lastSource) { sb.append("## ").append(it.source).append('\n'); lastSource = it.source }
            sb.append("- ").append(it.text).append('\n')
        }
        return sb.toString()
    }

    private fun score(i: Item, words: Set<String>, now: Long): Double {
        val low = (i.source + " " + i.text).lowercase()
        val overlap = words.count { low.contains(it) }
        val age = (now - i.ts) / DAY.toDouble()
        val recent = when { i.ts <= 0 -> 0.0; age < 7 -> 2.0; age < 30 -> 1.2; age < 90 -> 0.5; else -> 0.1 }
        return overlap * 3.0 + recent
    }
}

@Composable
internal fun RoadsScreen(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current
    var ready by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { Roads.warm(container, force = true); ready++ }
    val sources = remember(ready) { Roads.sources(c) }
    val pulse = remember(ready) { Roads.pulse(c) }
    val max = (pulse.maxOrNull() ?: 0).coerceAtLeast(1)
    var on by remember { mutableStateOf(Noticer.enabled(c)) }
    val notices = remember(ready, on) { Noticer.scan(c) }
    var report by remember { mutableStateOf("") }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Everything you record in Atlas feeds one network. Insight tools use it, so their answers come from your whole picture, not one tracker.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Atlas points things out", fontWeight = FontWeight.SemiBold)
                Text("It only suggests, never acts. Each hint opens the tool that helps.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(on, { v -> Noticer.setEnabled(c, v); on = v })
        }
        var chatOn by remember { mutableStateOf(Roads.chatEnabled(c)) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Use my data when I chat", fontWeight = FontWeight.SemiBold)
                Text("Atlas adds the part of your own records that matches your question. It goes to your AI provider, so keep it off if you prefer.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            androidx.compose.material3.Switch(chatOn, { v -> Roads.setChatEnabled(c, v); chatOn = v })
        }
        if (on && notices.isEmpty()) Text("Nothing needs attention right now.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        notices.forEach { n ->
            Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(12.dp)) {
                Text(n.title, fontWeight = FontWeight.SemiBold)
                Text(n.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { open(n.route) }) { Text("Open the helper") }
            }
        }
        val usual = remember(ready) { Mind.now(c) }
        if (usual.isNotEmpty()) {
            Text("Around this time you usually open", fontWeight = FontWeight.SemiBold)
            usual.forEach { sp -> TextButton(onClick = { open("nx/${sp.id}") }) { Text(sp.title) } }
        }
        Text("Last 7 days", fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth().height(60.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
            pulse.forEach { n ->
                Box(Modifier.weight(1f).height((6 + 50 * n / max).dp).background(MaterialTheme.colorScheme.primary.copy(alpha = if (n == 0) 0.25f else 1f), RoundedCornerShape(4.dp)))
            }
        }
        Text("${pulse.sum()} entries this week across ${sources.size} connected trackers.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Connected now", fontWeight = FontWeight.SemiBold)
        if (sources.isEmpty()) Text("Nothing yet. Start any tracker (money, health, habits...) and it joins the network automatically.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        sources.forEach { s ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(s.label, fontWeight = FontWeight.Medium)
                    Text(s.hub.name, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${s.count}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text("How Atlas is doing", fontWeight = FontWeight.SemiBold)
        TextButton(onClick = {
            scope.launch {
                val findings = SelfCheck.run(container)
                val sb = StringBuilder("Atlas report\n")
                sb.append("Features: ").append(Nx.all.size).append("; connected sources: ").append(sources.size).append("\n")
                findings.forEach { sb.append("[").append(it.level).append("] ").append(it.area).append(": ").append(it.text).append("\n") }
                sb.append("Crashes: ").append(com.nizam.app.feature.safe.CrashGuard.report(c).take(1500))
                report = sb.toString()
                (c.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("Atlas report", report))
            }
        }) { Text("Check myself and copy a report") }
        if (report.isNotBlank()) Text(report.take(900), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Joining next", fontWeight = FontWeight.SemiBold)
        Text("The calendar, health-app readings, chats and notifications. Until they join, tools that ask for those will say the data is thin.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

object RoadsSpecs {
    val all: List<Spec> = listOf(
        ScreenSpec("roads", "Atlas Roads", "See how everything you record connects into one network", Icons.Outlined.AltRoute, Hub.Tools,
            "connected data network pulse sources central shared") { c, open -> RoadsScreen(c, open) },
    )
}
