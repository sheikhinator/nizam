package com.nizam.app.feature.modes

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.data.db.Task
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.nav.Routes
import com.nizam.app.ui.screens.hubs.HubRegistry
import com.nizam.app.ui.screens.hubs.RecentFeatures
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/** Tools you pinned to Today, and tools you've hidden from the suite lists (still found by search, restorable anytime). */
object Pins {
    private fun p(c: Context) = c.getSharedPreferences("tool_pins", 0)
    private fun list(c: Context, k: String) = p(c).getString(k, "").orEmpty().split("\u0001").filter { it.isNotBlank() }
    fun pinned(c: Context) = list(c, "pins")
    fun isPinned(c: Context, route: String) = route in pinned(c)
    fun togglePin(c: Context, route: String) = p(c).edit().putString("pins", (if (isPinned(c, route)) pinned(c) - route else (pinned(c) + route).takeLast(8)).joinToString("\u0001")).apply()
    fun hidden(c: Context) = list(c, "hidden").toSet()
    fun setHidden(c: Context, route: String, hide: Boolean) = p(c).edit().putString("hidden", (if (hide) hidden(c) + route else hidden(c) - route).joinToString("\u0001")).apply()
    fun firstSeen(c: Context): Long { val v = p(c).getLong("first", 0L); if (v > 0) return v; val n = System.currentTimeMillis(); p(c).edit().putLong("first", n).apply(); return n }
}

object QuickActions {
    data class Action(val label: String, val route: String)
    private val BASE = listOf(Action("➕ Task", Routes.TASKS), Action("💸 Expense", Routes.FINANCE), Action("🕌 Prayer", Routes.PRAYER), Action("⏱ Timer", Routes.TIMER), Action("✨ Ask", Routes.ASK))
    private val BY_MODE = mapOf(
        "morning" to listOf(Action("🕌 Prayer", Routes.PRAYER), Action("📖 Quran", Routes.QURAN), Action("🗓 Plan day", "nx/planengine"), Action("💧 Water", Routes.DIET), Action("✨ Ask", Routes.ASK)),
        "work" to listOf(Action("➕ Task", Routes.TASKS), Action("⏱ Timer", Routes.TIMER), Action("💸 Expense", Routes.FINANCE), Action("📝 Notes", Routes.NOTES), Action("✨ Ask", Routes.ASK)),
        "family" to listOf(Action("🏠 Household", "nx/household"), Action("🛒 Pantry", "nx/pantry"), Action("🎬 Watch", Routes.PODCASTS), Action("📸 Memories", Routes.DIARY), Action("✨ Ask", Routes.ASK)),
        "study" to listOf(Action("⏱ Timer", Routes.TIMER), Action("📚 Courses", Routes.COURSES), Action("🔤 Vocab", "nx/vocab"), Action("📝 Notes", Routes.NOTES), Action("✨ Ask", Routes.ASK)),
        "worship" to listOf(Action("🕌 Prayer", Routes.PRAYER), Action("📖 Quran", Routes.QURAN), Action("🤲 Duas", Routes.DUAS), Action("📿 Hadith", Routes.HADITH)),
        "night" to listOf(Action("🌙 Wind down", Routes.NIGHT), Action("📓 Diary", Routes.DIARY), Action("🤲 Duas", Routes.DUAS), Action("🗓 Tomorrow", "nx/planengine")),
        "ramadan" to listOf(Action("📖 Quran", Routes.QURAN), Action("🕌 Prayer", Routes.PRAYER), Action("🍽 Meal plan", "nx/pantryplan"), Action("🤲 Duas", Routes.DUAS)),
        "travel" to listOf(Action("🗺 Maps", Routes.MAPS), Action("🌐 Translate", "power/translator"), Action("💸 Expense", Routes.FINANCE), Action("🕌 Prayer", Routes.PRAYER)),
        "exam" to listOf(Action("⏱ Timer", Routes.TIMER), Action("📚 Learning", Routes.LEARNING), Action("🤲 Duas", Routes.DUAS)),
        "recovery" to listOf(Action("💊 Medicines", "nx/meds"), Action("🌡 Temp & pulse", "nx/tempready"), Action("🕌 Prayer", Routes.PRAYER), Action("📓 Diary", Routes.DIARY)),
        "driving" to listOf(Action("🗺 Maps", Routes.MAPS), Action("🎵 Music", Routes.MUSIC), Action("🤲 Duas", Routes.DUAS), Action("🎙 Voice", Routes.VOICE)),
    )
    fun forMode(c: Context): List<Action> {
        val pinned = Pins.pinned(c).mapNotNull { r -> HubRegistry.byRoute(r)?.let { Action("📌 ${it.title}", r) } }
        val always = listOf(Action("🐾 Buddy", Routes.COMPANION), Action("🏢 HQ", Routes.HQ), Action("🏘 Town", Routes.TOWN), Action("🛰 Missions", "nx/missioncontrol"))
        return (pinned + (BY_MODE[AutoModes.current(c)] ?: BASE) + always).distinctBy { it.route }
    }
}

@Composable
fun QuickActionsBar(onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    val actions = remember(AutoModes.current(c), Pins.pinned(c)) { QuickActions.forMode(c) }
    val hidden = com.nizam.app.feature.platform2.AmountPrivacy.on()
    LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item { Capsule(if (hidden) "🙈 Amounts hidden" else "👁 Amounts", false) { if (hidden) com.nizam.app.feature.platform2.AmountPrivacy.reveal(c) else com.nizam.app.feature.platform2.AmountPrivacy.set(c, true) } }
        items(actions) { a -> Capsule(a.label, false) { onOpen(a.route) } }
    }
}

/** Evening carry-over: what's still open from today, with one tap to move, reschedule or drop each. */
@Composable
fun CarryOver(container: AppContainer) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var tasks by remember { mutableStateOf<List<Task>?>(null) }
    var tick by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(tick) { com.nizam.app.ui.design.guard {
        val end = LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        tasks = runCatching { container.taskRepository.openThrough(end).first() }.getOrDefault(emptyList())
    } }
    val list = tasks ?: run { Text("Looking at today…"); return }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (list.isEmpty()) { Text("Nothing carried over. A clean slate for tomorrow.", style = MaterialTheme.typography.bodyMedium); return@Column }
        Text("${list.size} not finished today", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        list.forEach { t ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(t.title, style = MaterialTheme.typography.bodyLarge)
                Row {
                    val tomorrow = LocalDate.now().plusDays(1).atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    val weekend = LocalDate.now().let { d -> d.plusDays(((6 - d.dayOfWeek.value + 7) % 7).let { if (it == 0) 7 else it }.toLong()) }.atTime(9, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    TextButton(onClick = { scope.launch { container.taskRepository.save(t.copy(due = tomorrow)); tick++ } }) { Text("Tomorrow") }
                    TextButton(onClick = { scope.launch { container.taskRepository.save(t.copy(due = weekend)); tick++ } }) { Text("Weekend") }
                    TextButton(onClick = { scope.launch { container.taskRepository.softDelete(t); tick++ } }) { Text("Drop") }
                }
            }
        }
    }
}

/** Lists tools you never open so you can hide them; hidden tools disappear from suite lists but search still finds them. */
@Composable
fun ToolTidy() {
    val c = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    val ageDays = remember { ((System.currentTimeMillis() - Pins.firstSeen(c)) / 86_400_000L).toInt() }
    val hidden = remember(tick) { Pins.hidden(c) }
    val unused = remember(tick) { HubRegistry.all.filter { RecentFeatures.uses(c, it.route) == 0 && it.route !in hidden }.groupBy { it.hub } }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Hiding a tool only removes it from suite lists. Search and Ask can still open it, and you can bring it back here.", style = MaterialTheme.typography.bodySmall)
        if (ageDays < 30) Text("You've used Atlas for $ageDays days; suggestions get better after 30.", style = MaterialTheme.typography.bodySmall)
        if (hidden.isNotEmpty()) {
            Text("Hidden (${hidden.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            hidden.forEach { r -> val f = HubRegistry.byRoute(r); Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f?.title ?: r, Modifier.weight(1f)); TextButton(onClick = { Pins.setHidden(c, r, false); tick++ }) { Text("Restore") } } }
        }
        Text("Never opened", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        unused.entries.sortedBy { it.key.label }.forEach { (hub, list) ->
            Text("${hub.label} · ${list.size}", style = MaterialTheme.typography.labelLarge, color = hub.tint())
            list.take(12).forEach { f -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(f.title, Modifier.weight(1f), maxLines = 1); TextButton(onClick = { Pins.setHidden(c, f.route, true); tick++ }) { Text("Hide") } } }
        }
    }
}

object ModeSpecs {
    val all = listOf(
        ScreenSpec("carryover", "Evening carry-over", "What's unfinished today: move, reschedule or drop in one tap", Icons.Outlined.EventRepeat, Hub.Work,
            "carry over evening unfinished tasks tomorrow reschedule day close") { c, _ -> CarryOver(c) },
        ScreenSpec("tooltidy", "Tidy my tools", "Hide tools you never open (search still finds them)", Icons.Outlined.CleaningServices, Hub.Tools,
            "tidy hide unused tools declutter clean menus") { _, _ -> ToolTidy() },
    )
}
