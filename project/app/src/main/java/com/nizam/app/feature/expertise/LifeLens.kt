package com.nizam.app.feature.expertise

import android.content.Context
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.nizam.app.AppContainer
import com.nizam.app.core.BarChart
import com.nizam.app.core.Point
import com.nizam.app.feature.agent.ApprovalInbox
import com.nizam.app.feature.agent.Reviewer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.Md
import com.nizam.app.feature.nx.ResultActions
import com.nizam.app.feature.nx.Roads
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.StatTile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * A lens: one way of reading across the user's whole life. It takes what Roads carries from every hub
 * (money, prayer, mood, tasks, habits, workouts, notes, every tracker), keeps only the parts it needs,
 * shows the numbers worked out on the phone, then lets Atlas write the reading. Anything the reading
 * suggests doing goes to the approval inbox, never straight into the app.
 */
data class Lens(
    val id: String,
    val title: String,
    val what: String,
    val icon: ImageVector,
    val hub: Hub,
    /** Roads sources (or hub names) this lens reads; empty reads everything. */
    val reads: List<String>,
    /** What Atlas writes from the data. */
    val ask: String,
    /** The existing feature this one makes more powerful, opened from the lens and from that feature's ✦ dock. */
    val upgrades: String? = null,
    val upgradesName: String? = null,
    val words: String = "",
    /** A question the user can add (e.g. "What are you thinking of buying?"). */
    val input: String? = null,
    val web: Boolean = false,
    val stats: (List<Roads.Item>) -> List<Pair<String, String>> = { emptyList() },
) {
    fun spec() = ScreenSpec(id, title, what, icon, hub, "$words ${title.lowercase()} ${upgradesName?.lowercase().orEmpty()}") { c, open -> LensScreen(this, c, open) }
}

object Lenses {
    private const val DAY = 86_400_000L

    fun matches(lens: Lens, i: Roads.Item): Boolean =
        lens.reads.isEmpty() || lens.reads.any { k -> i.source.contains(k, true) || i.hub.label.equals(k, true) }

    fun itemsFor(c: Context, lens: Lens): List<Roads.Item> = Roads.items(c, 60).filter { matches(lens, it) }

    /** Entries per day for the last [days] days, oldest first. */
    fun perDay(items: List<Roads.Item>, days: Int = 14): List<Point> {
        val today = LocalDate.now()
        return (days - 1 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            val from = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            Point(d.dayOfMonth.toString(), items.count { it.ts in from until from + DAY }.toDouble())
        }
    }

    fun dataText(items: List<Roads.Item>, max: Int = 4500): String {
        val sb = StringBuilder()
        var last = ""
        for (i in items.sortedWith(compareBy({ it.source }, { -it.ts }))) {
            if (sb.length > max) break
            if (i.source != last) { sb.append("## ").append(i.source).append('\n'); last = i.source }
            sb.append("- ").append(i.text.take(200)).append('\n')
        }
        return sb.toString()
    }

    const val RULES = "Use only the data given and say plainly when it is thin (one line on what to log to sharpen it). " +
        "Be specific: numbers, dates, names of days. Short headings and bullets, under 260 words. Never invent data. " +
        "Never write Quran verses or hadith text yourself; point to Atlas's Quran and Hadith screens instead. Medical points are never a diagnosis. " +
        "End with one last line exactly like 'Confidence: high — why' (high, medium or low, from how much data there was)."

    /** One reading, written by Atlas from the user's own data. */
    suspend fun read(container: AppContainer, lens: Lens, question: String): String {
        val c = container.appContext
        runCatching { Roads.warm(container) }
        val items = withContext(Dispatchers.IO) { itemsFor(c, lens) }
        val stats = runCatching { lens.stats(items) }.getOrDefault(emptyList())
        val prompt = buildString {
            append("Today is ").append(LocalDate.now()).append(" (").append(LocalDate.now().dayOfWeek.name.lowercase()).append(").\n")
            if (stats.isNotEmpty()) append("Worked out on the phone: ").append(stats.joinToString("; ") { "${it.first} ${it.second}" }).append("\n")
            append("The user's recorded data, shared by them (newest first):\n")
            append(dataText(items).ifBlank { "(nothing recorded yet in these parts of Atlas)" })
            if (question.isNotBlank()) append("\nWhat they asked: ").append(question.trim())
            append("\n\nTask: ").append(lens.ask)
        }
        Pulse.emit(Pulse.Kind.WORKING, lens.title, "Reading ${items.size} entries", lens.hub.label)
        val out = Ai.generate(container.settingsRepository, prompt,
            "You are Atlas, reading across the user's life for one feature: ${lens.title} (${lens.what}). $RULES", 900, lens.web)
        JsonStore(c, "lens_${lens.id}").let { st -> st.save((st.all() + JSONObject().put("id", System.currentTimeMillis().toString())
            .put("q", question).put("text", out).put("at", System.currentTimeMillis())).takeLast(30)) }
        Pulse.emit(Pulse.Kind.DONE, lens.title, out.lineSequence().firstOrNull { it.isNotBlank() }?.trim('#', ' ', '*')?.take(90).orEmpty(), lens.hub.label)
        return out
    }

    /** Next actions from a reading, each waiting in the approval inbox as a task. */
    suspend fun proposeTasks(container: AppContainer, source: String, text: String): Int {
        val raw = Ai.generate(container.settingsRepository,
            "From this, list up to 4 concrete next actions as short imperative task titles (max 8 words), one per line, nothing else:\n\n${text.take(2500)}",
            "Output only the lines.", 160)
        val tasks = raw.lines().map { it.trim().trimStart('-', '•', '*', ' ').replace(Regex("^\\d+[.)]\\s*"), "").trim() }
            .filter { it.length in 3..90 }.take(4)
        tasks.forEach { t ->
            ApprovalInbox.add(container.appContext, "Atlas · $source", "add_task", JSONObject().put("title", t),
                Reviewer.Verdict("low", "approve", "Adds a task to your own list"), "Suggested by $source")
        }
        if (tasks.isNotEmpty()) Pulse.emit(Pulse.Kind.CHANGED, "${tasks.size} tasks waiting for you", source, Hub.Work.label)
        return tasks.size
    }

    /** Keep a reading in Notes (after approval). */
    fun proposeNote(container: AppContainer, title: String, text: String) {
        ApprovalInbox.add(container.appContext, "Atlas · $title", "add_note", JSONObject().put("title", "$title · ${LocalDate.now()}").put("body", text),
            Reviewer.Verdict("low", "approve", "Saves a note in your own Notes"), "Keep this reading")
    }

    fun history(c: Context, id: String) = JsonStore(c, "lens_$id").all().sortedByDescending { it.optLong("at") }
}

@Composable
fun LensScreen(lens: Lens, container: AppContainer, open: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var items by remember { mutableStateOf<List<Roads.Item>>(emptyList()) }
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var version by remember { mutableIntStateOf(0) }
    val past = remember(version) { Lenses.history(ctx, lens.id) }
    LaunchedEffect(lens.id) { com.nizam.app.ui.design.guard {
        runCatching { Roads.warm(container) }
        items = withContext(Dispatchers.IO) { Lenses.itemsFor(ctx, lens) }
        if (result == null) result = past.firstOrNull()?.optString("text")?.ifBlank { null }
    } }
    val stats = remember(items) { runCatching { lens.stats(items) }.getOrDefault(emptyList()) }
    val sources = remember(items) { items.groupingBy { it.source }.eachCount().entries.sortedByDescending { it.value } }

    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(if (sources.isEmpty()) "Reads from: ${lens.reads.joinToString(", ").ifBlank { "everything" }} (nothing logged yet)"
            else "Reads from ${sources.size} part${if (sources.size == 1) "" else "s"} of Atlas", style = MaterialTheme.typography.labelLarge,
            color = lens.hub.tint())
        if (sources.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            sources.take(10).forEach { (s, n) -> Capsule("$s · $n", false) {} }
        }
        if (stats.isNotEmpty()) stats.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                row.forEach { (k, v) -> StatTile(k, v, Modifier.weight(1f), tint = lens.hub.tint()) }
                repeat(3 - row.size) { androidx.compose.foundation.layout.Spacer(Modifier.weight(1f)) }
            }
        }
        if (items.isNotEmpty()) {
            Text("Last 14 days", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            BarChart(Lenses.perDay(items), lens.hub.tint(), 80)
        }
        lens.input?.let { label ->
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), label = { Text(label) }, minLines = 2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Button(onClick = {
                busy = true
                scope.launch {
                    runCatching { Lenses.read(container, lens, q) }
                        .onSuccess { result = it; version++ }
                        .onFailure { Feedback.show(it.message ?: "Atlas couldn't read it just now") }
                    busy = false
                }
            }, enabled = !busy && (lens.input == null || q.isNotBlank() || items.isNotEmpty())) {
                Text(if (busy) "Reading…" else if (result == null) "Read it" else "Read again")
            }
            lens.upgrades?.let { r -> OutlinedButton(onClick = { open(r) }) { Text("Open ${lens.upgradesName ?: "it"}") } }
        }
        if (busy) com.nizam.app.ui.components.Thinking(label = "Reading across your life")
        result?.let { text ->
            Group {
                Md(text)
            }
            ResultActions(lens.title, text)
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val n = runCatching { Lenses.proposeTasks(container, lens.title, text) }.getOrDefault(0)
                        Feedback.show(if (n == 0) "Nothing to turn into tasks" else "$n tasks are waiting for your approval", "Review") { open(com.nizam.app.feature.nx.Nx.route("approvalinbox")) }
                    }
                }) { Text("Turn into tasks") }
                OutlinedButton(onClick = {
                    Lenses.proposeNote(container, lens.title, text)
                    Feedback.show("Waiting for your approval", "Review") { open(com.nizam.app.feature.nx.Nx.route("approvalinbox")) }
                }) { Text("Keep in Notes") }
            }
        }
        if (past.size > 1) {
            Text("Earlier readings", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = Space.m))
            past.drop(1).take(5).forEach { o ->
                val day = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(java.util.Date(o.optLong("at")))
                com.nizam.app.ui.design.ListRow(day, supporting = o.optString("text").lineSequence().firstOrNull { it.isNotBlank() }?.trim('#', ' ', '*')?.take(90),
                    icon = lens.icon, tint = lens.hub.tint()) { result = o.optString("text") }
            }
        }
    }
}

/** A lens opened from inside the feature it upgrades (its ✦ dock), as a sheet over that screen. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun LensSheet(lens: Lens, open: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val container = (ctx.applicationContext as com.nizam.app.NizamApplication).container
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().androidx_verticalScroll().padding(horizontal = Space.page).padding(bottom = Space.xxl),
            verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Text(lens.title, style = MaterialTheme.typography.headlineSmall)
            Text(lens.what, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LensScreen(lens, container) { r -> onDismiss(); open(r) }
        }
    }
}

@Composable
private fun Modifier.androidx_verticalScroll(): Modifier = this.then(Modifier.verticalScroll(rememberScrollState()))
