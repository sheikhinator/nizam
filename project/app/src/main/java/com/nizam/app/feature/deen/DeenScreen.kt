package com.nizam.app.feature.deen

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.jarvis.Barakah
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.components.Thinking
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedTextField
import java.time.LocalDate

/** Deen: duas, adhkar, tasbih, daily sunnah, hifz, Ramadan, zakat and questions — in one quiet place. */
@Composable
fun DeenScreen(container: AppContainer) {
    var tab by remember { mutableStateOf("Today") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Deen", "Duas, adhkar, Quran and the small sunnahs of the day")
        ChipRow(listOf("Today", "Duas", "Adhkar", "Tasbih", "Names", "Sunnahs", "My duas", "Deeds", "Seerah", "Hifz", "Ramadan", "Zakat", "Ask"), tab, { tab = it })
        Spacer(Modifier.height(10.dp))
        when (tab) {
            "Today" -> SunnahToday(container) { tab = it }
            "Duas" -> DuasTab()
            "Adhkar" -> AdhkarTab()
            "Tasbih" -> TasbihTab()
            "Names" -> NamesTab()
            "Sunnahs" -> SunnahsTab()
            "My duas" -> MyDuasTab()
            "Deeds" -> DeedsTab()
            "Seerah" -> SeerahTab()
            "Hifz" -> HifzTab(container)
            "Ramadan" -> RamadanTab(container)
            "Zakat" -> ZakatTab(container)
            else -> AskTab(container)
        }
        Spacer(Modifier.height(96.dp))
    }
}

/** Arabic, set generously: larger, right-to-left, with room between lines for the harakat. */
@Composable
fun Arabic(text: String, size: Int = 24) = Text(
    text, modifier = Modifier.fillMaxWidth(),
    style = TextStyle(fontSize = size.sp, lineHeight = (size * 1.9).sp, textDirection = TextDirection.Rtl, textAlign = TextAlign.Right),
    color = MaterialTheme.colorScheme.onSurface
)

// ── Today: the day's small sunnahs, feeding the Barakah score ────────────────────────────────────

private data class Sunnah(val key: String, val label: String, val barakahKey: String? = null, val opens: String? = null)

private val SUNNAHS = listOf(
    Sunnah("morning", "Morning adhkar", "dhikr", "Adhkar"),
    Sunnah("quran", "Read some Quran", "quran", "Hifz"),
    Sunnah("kursi", "Ayat al-Kursi after each prayer"),
    Sunnah("sadaqah", "Give something in sadaqah", "charity"),
    Sunnah("family", "Call or sit with family", "family"),
    Sunnah("evening", "Evening adhkar", "dhikr", "Adhkar"),
    Sunnah("dua", "Make dua for someone else"),
    Sunnah("sleep", "Sleep in wudu, with the sleep adhkar", null, "Duas")
)

@Composable
private fun SunnahToday(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current
    val p = remember { c.getSharedPreferences("sunnah", 0) }
    var tick by remember { mutableIntStateOf(0) }
    val day = LocalDate.now()
    fun done(k: String) = p.getBoolean("$day|$k", false)
    val count = SUNNAHS.count { done(it.key) }.also { tick }
    Text("$count of ${SUNNAHS.size} today", style = MaterialTheme.typography.titleMedium)
    LinearProgressIndicator(progress = { count / SUNNAHS.size.toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        trackColor = MaterialTheme.colorScheme.surfaceVariant)
    Group(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp)) {
        SUNNAHS.forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().clickable(role = Role.Checkbox) {
                val on = !done(s.key)
                p.edit().putBoolean("$day|${s.key}", on).apply()
                // the Barakah score reads the same ticks
                s.barakahKey?.let { Barakah.tick(c, day, it, on || SUNNAHS.any { o -> o.barakahKey == it && o.key != s.key && done(o.key) }) }
                tick++
            }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(done(s.key).also { tick }, null)
                Text(s.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                s.opens?.let { tab -> Text("open", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(role = Role.Button) { open(tab) }.padding(12.dp)) }
            }
            if (i < SUNNAHS.lastIndex) HorizontalDivider(Modifier.padding(start = 52.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
    Text("Small and steady — ticks here also count toward today's Barakah score.", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
}

// ── Duas: Hisn al-Muslim, searchable ─────────────────────────────────────────────────────────────

@Composable
private fun DuasTab() {
    val c = LocalContext.current
    var chapters by remember { mutableStateOf(Duas.cached(c)) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var open by remember { mutableStateOf<DuaChapter?>(null) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { if (chapters.isEmpty()) runCatching { Duas.load(c) }.onSuccess { chapters = it }.onFailure { error = it.message } } }

    open?.let { ch ->
        Text("‹ All duas", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clickable(role = Role.Button) { open = null; com.nizam.app.ui.design.QuickAudio.stop() }.padding(vertical = 8.dp))
        Text(ch.title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp))
        ch.items.forEach { d -> DhikrCard(d) }
        Text("Source: Hisn al-Muslim (hisnmuslim.com). Shown exactly as published.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        return
    }
    if (chapters.isEmpty()) {
        if (error != null) Text("Couldn't load the duas yet — ${error}. They download once, then work offline.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Thinking(label = "Fetching Hisn al-Muslim")
        return
    }
    OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("Search — travel, worry, rain, illness…") })
    if (query.isBlank()) {
        Label("For now")
        Duas.forNow(chapters).forEach { ch -> ChapterRow(ch) { open = ch } }
        Label("All")
    }
    val shown = if (query.isBlank()) chapters else chapters.filter { ch ->
        ch.title.contains(query, true) || ch.items.any { it.translation.contains(query, true) }
    }
    shown.forEach { ch -> ChapterRow(ch) { open = ch } }
}

@Composable
private fun ChapterRow(ch: DuaChapter, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(ch.title.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text("${ch.items.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DhikrCard(d: Dhikr) {
    Group(Modifier.padding(vertical = 6.dp)) {
        Arabic(d.arabic)
        if (d.note.isNotBlank()) Text(d.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 8.dp))
        Text(d.translation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (d.repeat > 1) Text("×${d.repeat}", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(end = 16.dp))
            if (d.audio.isNotBlank()) Text("Listen", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(role = Role.Button) { com.nizam.app.ui.design.QuickAudio.play(d.audio) })
        }
    }
}

// ── Adhkar: guided, counted, one at a time ───────────────────────────────────────────────────────

@Composable
private fun AdhkarTab() {
    val c = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var chapters by remember { mutableStateOf(Duas.cached(c)) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { if (chapters.isEmpty()) runCatching { Duas.load(c) }.onSuccess { chapters = it } } }
    val morningEvening = chapters.firstOrNull { it.title.contains("morning and evening", true) }
    val sleep = chapters.firstOrNull { it.title.contains("before sleeping", true) }
    val afterPrayer = chapters.firstOrNull { it.title.contains("after completing the prayer", true) }
    var set by remember { mutableStateOf<DuaChapter?>(null) }
    var index by remember { mutableIntStateOf(0) }
    var count by remember { mutableIntStateOf(0) }

    val current = set
    if (current == null) {
        if (chapters.isEmpty()) { Thinking(label = "Fetching the adhkar"); return }
        Text("Choose a set. Tap anywhere on the card to count; it moves on by itself when a dhikr is complete.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        listOfNotNull(
            morningEvening?.let { (if (java.time.LocalTime.now().hour < 14) "Morning adhkar" else "Evening adhkar") to it },
            afterPrayer?.let { "After prayer" to it }, sleep?.let { "Before sleep" to it }
        ).forEach { (label, ch) ->
            Group(Modifier.padding(top = 10.dp).clickable(role = Role.Button) { set = ch; index = 0; count = 0 }) {
                Text(label, style = MaterialTheme.typography.titleMedium)
                Text("${ch.items.size} adhkar", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        return
    }
    if (index >= current.items.size) {
        Text("Complete. May Allah accept it.", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(vertical = 20.dp))
        Text("Back to sets", color = MaterialTheme.colorScheme.primary, modifier = Modifier.clickable { set = null })
        return
    }
    val d = current.items[index]
    val press = remember { MutableInteractionSource() }
    val bump by animateFloatAsState(if (count % 2 == 0) 1f else 0.985f, label = "tap")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("${index + 1} of ${current.items.size}", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("Stop", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clickable { set = null })
    }
    LinearProgressIndicator(progress = { index / current.items.size.toFloat() }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        trackColor = MaterialTheme.colorScheme.surfaceVariant)
    Group(Modifier.scale(bump).clickable(interactionSource = press, indication = null) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        count++
        if (count >= d.repeat) {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
            count = 0; index++
            if (index >= current.items.size) {
                Barakah.tick(c, LocalDate.now(), "dhikr", true)
                val key = if (java.time.LocalTime.now().hour < 14) "morning" else "evening"
                c.getSharedPreferences("sunnah", 0).edit().putBoolean("${LocalDate.now()}|$key", true).apply()
                Feedback.show("Adhkar complete")
            }
        }
    }) {
        Arabic(d.arabic, 26)
        Text(d.translation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp))
        Box(Modifier.fillMaxWidth().padding(top = 20.dp), contentAlignment = Alignment.Center) {
            Text("$count / ${d.repeat}", style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

// ── Tasbih ───────────────────────────────────────────────────────────────────────────────────────

private val PHRASES = listOf(
    "سُبْحَانَ اللَّهِ" to "SubhanAllah",
    "الْحَمْدُ لِلَّهِ" to "Alhamdulillah",
    "اللَّهُ أَكْبَرُ" to "Allahu Akbar",
    "لَا إِلَٰهَ إِلَّا اللَّهُ" to "La ilaha illallah",
    "أَسْتَغْفِرُ اللَّهَ" to "Astaghfirullah",
    "اللَّهُمَّ صَلِّ عَلَىٰ مُحَمَّدٍ" to "Salawat"
)

@Composable
private fun TasbihTab() {
    val c = LocalContext.current
    val haptic = LocalHapticFeedback.current
    var phrase by remember { mutableStateOf(PHRASES.first()) }
    var target by remember { mutableStateOf("33") }
    var session by remember { mutableIntStateOf(0) }
    var todayCount by remember(phrase) { mutableIntStateOf(Duas.today(c, phrase.second)) }
    ChipRow(PHRASES.map { it.second }, phrase.second, { name -> phrase = PHRASES.first { it.second == name }; session = 0 })
    ChipRow(listOf("33", "100", "∞"), target, { target = it; session = 0 })
    val goal = target.toIntOrNull()
    Box(
        Modifier.fillMaxWidth().padding(top = 16.dp).height(300.dp)
            .background(MaterialTheme.colorScheme.surface, MaterialTheme.shapes.large)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                session++; todayCount++
                Duas.addCount(c, phrase.second)
                if (goal != null && session % goal == 0) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                else haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            },
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(phrase.first, style = TextStyle(fontSize = 30.sp, textDirection = TextDirection.Rtl))
            Text("$session", style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 10.dp))
            if (goal != null) {
                Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(progress = { (session % goal) / goal.toFloat() }, modifier = Modifier.size(48.dp),
                        strokeWidth = 3.dp, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                    Text("${session / goal}", style = MaterialTheme.typography.labelMedium)
                }
            }
            Text("Tap anywhere", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text("Today: $todayCount · all time: ${Duas.total(c, phrase.second)}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text("Reset", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.clickable { session = 0 })
    }
}

@Composable
internal fun Dot(on: Boolean) = Box(Modifier.size(8.dp).background(
    if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, CircleShape))

@Composable
internal fun Gap(h: Int = 8) = Spacer(Modifier.height(h.dp))

@Composable
internal fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))

@Composable
internal fun RowGap() = Spacer(Modifier.width(10.dp))

internal val Arrangement8 = Arrangement.spacedBy(8.dp)
