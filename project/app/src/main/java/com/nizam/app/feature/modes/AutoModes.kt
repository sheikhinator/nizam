package com.nizam.app.feature.modes

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.BatteryManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.composed
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.AtlasModeProfile
import com.nizam.app.feature.nx.ModeNavigationStore
import com.nizam.app.feature.nx.PlanEngine
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField

/** What each context mode is for, and which suites it brings forward. */
data class ModeInfo(val id: String, val name: String, val emoji: String, val blurb: String, val hubs: List<Hub>, val cards: Set<String>, val quiet: Boolean = false)

object AutoModes {
    /** Every Today card, so a mode changes emphasis (tools, order, sound), never hides your buddy or prayer times. */
    val FULL_CARDS = setOf("day-rings", "usually-now", "wind-down", "bento", "companion", "next-prayer", "briefing")
    val ALL = listOf(
        ModeInfo("full", "Full Atlas", "🌐", "Everything visible", emptyList(), FULL_CARDS),
        ModeInfo("morning", "Morning", "🌅", "Prayer, plan and briefing", listOf(Hub.Deen, Hub.Health, Hub.Work), FULL_CARDS),
        ModeInfo("work", "Work", "💼", "Tasks, money and tools", listOf(Hub.Work, Hub.Money, Hub.Tools), FULL_CARDS),
        ModeInfo("family", "Family", "🏠", "Home, people and shared time", listOf(Hub.Home, Hub.Social, Hub.Media), FULL_CARDS),
        ModeInfo("study", "Study", "📚", "Courses, revision and focus", listOf(Hub.Learn, Hub.Work), FULL_CARDS),
        ModeInfo("worship", "Worship", "🕌", "Prayer first; sounds off", listOf(Hub.Deen), FULL_CARDS, quiet = true),
        ModeInfo("night", "Night", "🌙", "Wind down and sleep", listOf(Hub.Memory, Hub.Health, Hub.Media), FULL_CARDS, quiet = true),
        ModeInfo("travel", "Travel", "✈️", "Maps, translator and trip tools", listOf(Hub.Travel, Hub.Money), FULL_CARDS),
        ModeInfo("focus", "Focus", "🎯", "Deep work, nothing else", listOf(Hub.Work, Hub.Learn), FULL_CARDS, quiet = true),
        ModeInfo("ramadan", "Ramadan", "🌙", "Iftar, sehri and Quran target", listOf(Hub.Deen, Hub.Home, Hub.Health), FULL_CARDS),
        ModeInfo("driving", "Driving", "🚗", "Big buttons, voice, no distractions", listOf(Hub.Travel, Hub.Deen, Hub.Media), FULL_CARDS, quiet = true),
        ModeInfo("exam", "Exam", "📝", "Past papers and revision", listOf(Hub.Learn, Hub.Deen), FULL_CARDS, quiet = true),
        ModeInfo("recovery", "Recovery", "🛌", "Rest, medicines, fewer tasks", listOf(Hub.Health, Hub.Deen), FULL_CARDS),
        ModeInfo("guest", "Guest", "🔒", "Private screens hidden", emptyList(), FULL_CARDS, quiet = true),
    )
    fun info(id: String) = ALL.firstOrNull { it.id == id } ?: ALL.first()

    private fun p(c: Context) = c.getSharedPreferences("context_mode", 0)
    fun auto(c: Context) = p(c).getBoolean("auto", true)
    fun setAuto(c: Context, v: Boolean) = p(c).edit().putBoolean("auto", v).apply()
    fun current(c: Context) = p(c).getString("current", "full") ?: "full"
    fun reason(c: Context) = p(c).getString("reason", "").orEmpty()
    fun isQuiet(c: Context) = info(current(c)).quiet
    /** A manual choice pauses automatic switching for a few hours. */
    fun pin(c: Context, hours: Int = 3) = p(c).edit().putLong("pinUntil", System.currentTimeMillis() + hours * 3_600_000L).apply()
    private fun pinned(c: Context) = System.currentTimeMillis() < p(c).getLong("pinUntil", 0L)

    /** Makes sure every mode exists in the saved profile list (older installs only have the first few). */
    fun ensure(c: Context) {
        val store = ModeNavigationStore.get(c)
        val have = store.state.value.modes.map { it.id }.toSet()
        val missing = ALL.filter { it.id !in have }.map { AtlasModeProfile(it.id, it.name, visibleCards = it.cards) }
        val migrate = !p(c).getBoolean("cards_v2", false)
        if (missing.isNotEmpty() || migrate) runCatching { store.update { s ->
            if (migrate) p(c).edit().putBoolean("cards_v2", true).apply()
            val modes = if (migrate) s.modes.map { it.copy(visibleCards = it.visibleCards + FULL_CARDS) } else s.modes
            s.copy(modes = modes + missing, visibleTodayCards = if (migrate) s.visibleTodayCards + FULL_CARDS else s.visibleTodayCards)
        } }
    }

    fun select(c: Context, id: String, manual: Boolean, why: String) {
        ensure(c)
        runCatching { ModeNavigationStore.get(c).selectMode(id, true) }
        p(c).edit().putString("current", id).putString("reason", why).apply()
        if (manual) pin(c)
    }

    /** Works out which mode fits right now, with the reason in one line. */
    suspend fun decide(container: AppContainer): Pair<String, String> = withContext(Dispatchers.IO) {
        val c = container.appContext
        runCatching { ensure(c) }
        val now = LocalTime.now(); val nowMin = now.hour * 60 + now.minute
        val prayers = runCatching { PlanEngine.prayers(container) }.getOrDefault(emptyList()).associate { it.label to it.startMin }
        val events = runCatching { PlanEngine.events(c) }.getOrDefault(emptyList())
        val ui = c.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager
        val charging = (c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging
        val today = LocalDate.now(); val weekend = today.dayOfWeek == DayOfWeek.SUNDAY
        val ramadan = runCatching { HijrahDate.from(today).get(ChronoField.MONTH_OF_YEAR) == 9 }.getOrDefault(false)
        val fever = runCatching { JsonStore(c, "nxlog_tempready").all().maxByOrNull { it.optString("d") }?.let { it.optString("d") >= today.minusDays(1).toString() && (it.optString("temp").toDoubleOrNull() ?: 0.0) >= 37.8 } }.getOrNull() == true

        if (ui.currentModeType == Configuration.UI_MODE_TYPE_CAR) return@withContext "driving" to "Car mode is on"
        prayers.entries.firstOrNull { nowMin >= it.value && nowMin < it.value + 25 }?.let { return@withContext "worship" to "${it.key} time" }
        val inEvent = events.firstOrNull { nowMin in it.startMin until it.endMin }
        if (inEvent != null && Regex("exam|test|paper|viva", RegexOption.IGNORE_CASE).containsMatchIn(inEvent.label)) return@withContext "exam" to "Calendar: ${inEvent.label}"
        if (fever) return@withContext "recovery" to "Your temperature log shows a fever"
        if (ramadan) {
            val fajr = prayers["Fajr"]; val asr = prayers["Asr"]; val mag = prayers["Maghrib"]
            if (fajr != null && nowMin in (fajr - 90) until fajr) return@withContext "ramadan" to "Sehri window"
            if (asr != null && mag != null && nowMin in asr until (mag + 30)) return@withContext "ramadan" to "Iftar is near"
        }
        if (inEvent != null) return@withContext "work" to "In a meeting: ${inEvent.label}"
        val isha = prayers["Isha"]
        if ((charging && (now.hour >= 22 || now.hour < 5)) || (isha != null && nowMin > isha + 90) || now.hour in 0..4) return@withContext "night" to if (charging) "Charging at night" else "Late evening"
        val fajr = prayers["Fajr"]
        if (fajr != null && nowMin in (fajr + 25) until 600) return@withContext "morning" to "After Fajr"
        val mag = prayers["Maghrib"]
        if (weekend) return@withContext "family" to "Weekend"
        if (mag != null && nowMin in (mag + 25) until ((isha ?: 1260) + 60)) return@withContext "family" to "Evening at home"
        if (today.dayOfWeek.value in 1..5 && nowMin in 600 until 1020) return@withContext "work" to "Weekday daytime"
        "full" to "No special context"
    }

    /** Called on a timer while Atlas is open and from the background worker. */
    suspend fun tick(container: AppContainer) {
        val c = container.appContext
        ensure(c)
        if (!auto(c) || pinned(c)) return
        val (id, why) = decide(container)
        if (id != current(c)) select(c, id, manual = false, why = why) else p(c).edit().putString("reason", why).apply()
    }
}

/** The strip at the top of Today: which mode you're in, why, and a way to change it. */
@Composable
fun ModeBar(container: AppContainer, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    var cur by remember { mutableStateOf(AutoModes.current(c)) }
    var why by remember { mutableStateOf(AutoModes.reason(c)) }
    var open by remember { mutableStateOf(false) }
    var auto by remember { mutableStateOf(AutoModes.auto(c)) }
    LaunchedEffect(Unit) { while (true) { AutoModes.tick(container); cur = AutoModes.current(c); why = AutoModes.reason(c); delay(60_000) } }
    val info = AutoModes.info(cur)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickableNoRipple { open = !open }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(info.emoji, style = MaterialTheme.typography.titleMedium)
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text("${info.name} mode${if (auto) " · auto" else ""}", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(if (why.isNotBlank()) why else info.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text(if (open) "▴" else "▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(AutoModes.ALL) { m -> FilterChip(selected = m.id == cur, onClick = {
                    val choose = { AutoModes.select(c, m.id, manual = true, why = "Chosen by you"); cur = m.id; why = "Chosen by you (auto resumes in 3 h)" }
                    // leaving Guest mode needs your fingerprint or screen lock
                    if (cur == "guest" && m.id != "guest") com.nizam.app.ui.nav.ReAuth.prompt(c) { ok -> if (ok) choose() } else choose() }, label = { Text("${m.emoji} ${m.name}") }) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text("Switch modes automatically", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Switch(auto, { auto = it; AutoModes.setAuto(c, it) })
            }
        }
    }
}

private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = composed {
    clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
}
