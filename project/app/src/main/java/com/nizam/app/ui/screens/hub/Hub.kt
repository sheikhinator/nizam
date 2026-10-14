package com.nizam.app.ui.screens.hub

import androidx.compose.foundation.layout.size

import androidx.compose.foundation.background

import androidx.compose.ui.draw.clip

import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.ui.nav.Routes

/**
 * Wire-style lists: no cards, no shadows, no borders. Just generous space, a hairline between rows,
 * and one accent colour. Everything in Atlas that used to be a grid of boxes reads like this now.
 */
data class HubRow(val label: String, val detail: String, val route: String)
data class HubSection(val title: String, val rows: List<HubRow>)

@Composable
fun HubScreen(title: String, subtitle: String, sections: List<HubSection>, onOpen: (String) -> Unit) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 8.dp)) {
                androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
                    if (title == "Atlas") com.nizam.app.ui.design.AtlasOrb(com.nizam.app.ui.design.OrbState.Idle, diameter = 52.dp)
                }
                if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }
        sections.forEach { section ->
            item {
                Text(section.title, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = if (section.title.isBlank()) 12.dp else 24.dp, bottom = 8.dp))
                // One soft block per section; rows separated by an inset hairline, never boxed individually
                com.nizam.app.ui.design.Group(padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                    section.rows.forEachIndexed { i, row ->
                        Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { onOpen(row.route) }
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            HubGlyph(row.label)
                            androidx.compose.foundation.layout.Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(row.label, style = MaterialTheme.typography.bodyLarge)
                                if (row.detail.isNotBlank()) Text(row.detail, style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            Spacer(Modifier.width(12.dp))
                            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.KeyboardArrowRight, null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
                        }
                        if (i < section.rows.lastIndex) HorizontalDivider(Modifier.padding(start = 16.dp),
                            color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(96.dp)) }
    }
}

// ── The four hubs of Me, and the Atlas tab ──────────────────────────────────

@Composable
fun AtlasHub(onOpen: (String) -> Unit) = HubScreen("Atlas", "Everything that acts for you", listOf(
    HubSection("", listOf(
        HubRow("Chat", "Ask, log, search, act", Routes.ASSISTANT),
        HubRow("Jarvis", "Standing orders, autopilot, wake word, co-pilot, replay", Routes.JARVIS),
        HubRow("Bots", "Separate threads that remember — work, money, study, a project", Routes.BOTS),
        HubRow("Council", "Six advisors when a decision deserves debate", Routes.COUNCIL),
        HubRow("Decision court", "Argued, cross-examined, judged — and checked later", Routes.COURT),
        HubRow("Agents", "Delegate a goal; they work it daily", Routes.WORKFORCE)
    )),
    HubSection("Handle it", listOf(
        HubRow("Receipts", "Photo a bill: logged, split, warranty remembered", Routes.RECEIPTS),
        HubRow("Call coach", "Prep before, commitments captured after", Routes.CALLCOACH)
    )),
    HubSection("Automatic", listOf(
        HubRow("Automations", "Rules, schedules, watchers", Routes.SCHEDULES),
        HubRow("Rules", "If this, then that", Routes.RULES),
        HubRow("Connections", "MCP servers and their tools", Routes.PLAYGROUND)
    )),
    HubSection("Behaviour", listOf(
        HubRow("Presence", "Autonomy, intentions, interruptions, what it knows", Routes.PRESENCE),
        HubRow("Teach and scripts", "Corrections it keeps, sequences you run", Routes.YOU),
        HubRow("Plan my day", "Time-blocked around prayers and work", Routes.PLAN),
HubRow("Agent memory", "What it remembers from your chats", Routes.AGENT),
        HubRow("What Atlas knows", "Beliefs about you and lessons it learned — edit or forget any", Routes.KNOWSME),
        HubRow("Skills", "Saved action sequences, run in one tap or by name", Routes.SKILLS),
        HubRow("Agent firewall", "Categories Atlas is never allowed to do", Routes.FIREWALL),
        HubRow("Activity", "Receipts for everything Atlas did", Routes.ATLAS_ACTIVITY)
    ))
), onOpen)

@Composable
fun UnderstandHub(onOpen: (String) -> Unit) = HubScreen("Understand", "What the evidence says", listOf(
    HubSection("", listOf(
        HubRow("Ledger of You", "One number, and what's moving it", Routes.YOU),
        HubRow("Life simulator", "Your averages run 1, 5 and 10 years forward — and with one habit changed", Routes.LIFESIM),
        HubRow("Patterns and charts", "Correlations, experiments, the year", Routes.UNDERSTAND),
        HubRow("Radar", "Small trends caught before they become problems", Routes.RADAR),
        HubRow("Mirror", "Compounding, integrity, excuses, regret", Routes.MIRROR),
        HubRow("Lab", "Courses, consequences, versions, bets", Routes.LAB),
        HubRow("Mind", "Charts on demand, life wheel, values, ideas", Routes.MIND)
    ))
), onOpen)

@Composable
fun StoryHub(onOpen: (String) -> Unit) = HubScreen("Story", "The record of your days", listOf(
    HubSection("", listOf(
        HubRow("Memory", "Read later, letters, recap, photos", Routes.MEMORY),
        HubRow("Reflect", "Wrapped, newspaper, life in weeks, dreams", Routes.REFLECT),
        HubRow("Studio", "Your book, legacy, drafts, triage", Routes.STUDIO),
        HubRow("Second brain", "Search everything you've saved", Routes.BRAIN),
        HubRow("Day", "Any single day, in full", Routes.DAY),
        HubRow("People and practice", "Promises, mentor, sparring, season", Routes.PEOPLE),
        HubRow("Meeting notes", "Decisions and actions", Routes.MEETING)
    ))
), onOpen)

@Composable
fun GrowHub(onOpen: (String) -> Unit) = HubScreen("Grow", "Missions, streaks and commitments", listOf(
    HubSection("", listOf(
        HubRow("Missions", "Today's, and your goals", Routes.MISSIONS),
        HubRow("Barakah", "Today's score, what lifts your days, salah-aware time", Routes.BARAKAH),
        HubRow("Deen", "Duas, adhkar, tasbih, hifz, Ramadan, zakat", Routes.DEEN),
        HubRow("Growth", "Challenges, stacks, badges, streak freezes", Routes.GROWTH),
        HubRow("Commit", "48-hour cart, stakes, soundscapes", Routes.COMMIT),
        HubRow("Progress", "Level, XP and history", Routes.SELF),
        HubRow("Insights", "What your numbers add up to", Routes.INSIGHTS),
        HubRow("Pixel Town", "Your world, growing with you", Routes.TOWN)
    ))
), onOpen)

@Composable
fun WellbeingHub(onOpen: (String) -> Unit) = HubScreen("Wellbeing", "Attention, rest and your own head", listOf(
    HubSection("", listOf(
        HubRow("Focus", "Sessions, flip-to-focus, soundscapes", Routes.FLIP),
        HubRow("Pause and screen fade", "Before the feeds, and gentle mode", Routes.PAUSE),
        HubRow("Attention", "Digest, smart replies, wind-down", Routes.ATTENTION),
        HubRow("Calm", "Breathing you can feel", Routes.CALM),
        HubRow("Smart wake", "Woken in light sleep", Routes.WAKE),
        HubRow("Bedside", "A dim clock for the night", Routes.BEDSIDE)
    )),
    HubSection("Your head", listOf(
        HubRow("Future You", "Talk to yourself in five years", Routes.FUTURE),
        HubRow("Thought reframe", "Catch it, check it, rewrite it", Routes.REFRAME),
        HubRow("Night companion", "Set the day down", Routes.NIGHT),
        HubRow("Voice", "Bubble, driving, journal, coach", Routes.VOICE),
        HubRow("Voice journal", "Talk it out; Atlas files it", Routes.VOICE_JOURNAL),
        HubRow("Driving mode", "Big buttons, spoken answers", Routes.DRIVE),
        HubRow("Rehearsal coach", "Pace, fillers, structure", Routes.REHEARSE),
        HubRow("Focus shield", "Block apps for a while", Routes.SHIELD)
    ))
), onOpen)


private val GLYPHS = listOf(
    "chat" to "💬", "jarvis" to "🤖", "bot" to "🧠", "council" to "🏛️", "court" to "⚖️", "agent" to "🕴️", "receipt" to "🧾",
    "call" to "📞", "understand" to "🔍", "story" to "📖", "grow" to "🌱", "wellbeing" to "🧘", "apps" to "📱", "profile" to "🪪",
    "phone" to "📲", "setting" to "⚙️", "money" to "💰", "finance" to "💰", "prayer" to "🕌", "quran" to "📗", "hadith" to "📜",
    "dua" to "🤲", "deen" to "🕋", "health" to "❤️", "gym" to "🏋️", "diet" to "🥗", "sleep" to "😴", "task" to "✅", "plan" to "🗓️",
    "note" to "📝", "learn" to "🎓", "course" to "🎓", "music" to "🎵", "cinema" to "🎬", "podcast" to "🎙️", "tv" to "📺",
    "map" to "🗺️", "travel" to "✈️", "weather" to "⛅", "news" to "📰", "book" to "📚", "diary" to "📔", "memory" to "🧩",
    "mission" to "🎯", "focus" to "🎯", "habit" to "🔁", "family" to "👨‍👩‍👧", "people" to "👥", "work" to "💼", "deliverable" to "📦",
    "library" to "📚", "search" to "🔎", "shield" to "🛡️", "security" to "🔐", "privacy" to "🔐", "scan" to "📷", "voice" to "🎤",
    "browser" to "🌐", "store" to "🛍️", "shop" to "🛒", "game" to "🎮", "barakah" to "✨", "calendar" to "📅", "timer" to "⏱️",
    "backup" to "💾", "model" to "🧠", "key" to "🔑", "theme" to "🎨", "widget" to "🧱", "hq" to "🏢", "company" to "🏢",
    "connect" to "🔌", "presence" to "📍", "teach" to "👩‍🏫", "skill" to "🧰", "firewall" to "🧱", "activity" to "📈", "automation" to "⚡",
    "rule" to "📏", "know" to "🧠", "pet" to "🐾", "buddy" to "🐾", "dash" to "📊", "ledger" to "📒", "simulator" to "🧪", "radar" to "📡",
    "mirror" to "🪞", "lab" to "🔬", "mind" to "🧠", "reflect" to "🪞", "studio" to "🎨", "brain" to "🧠", "day" to "🌤️", "meeting" to "🧑‍💼",
    "mail" to "✉️", "inbox" to "📥", "contact" to "📇", "photo" to "🖼️", "video" to "🎞️", "audio" to "🔊", "radio" to "📻", "anime" to "🍥",
    "sport" to "⚽", "cricket" to "🏏", "recipe" to "🍳", "garden" to "🌿", "car" to "🚗", "home" to "🏠", "pantry" to "🥫", "bill" to "🧾",
    "tax" to "🧮", "zakat" to "🕌", "sadaqa" to "🤲", "budget" to "💰", "invest" to "📈", "currency" to "💱", "expense" to "💸",
    "water" to "💧", "medic" to "💊", "body" to "🫀", "mood" to "🙂", "cycle" to "🌸", "language" to "🗣️", "vocab" to "🔤", "translate" to "🌐",
    "quiz" to "❓", "chess" to "♟️", "write" to "✍️", "draw" to "✏️", "print" to "🖨️", "pdf" to "📄", "file" to "🗂️", "export" to "📤",
    "import" to "📥", "sync" to "🔄", "update" to "⬆️", "crash" to "🛠️", "check" to "🩺", "speed" to "⚡", "trust" to "🤝", "log" to "📜",
    "time" to "⏳", "goal" to "🏁", "forecast" to "🔮", "season" to "🍂", "wizard" to "🧙", "mode" to "🎛️", "town" to "🏘️", "adventure" to "🧭",
    "pattern" to "📊", "chart" to "📊", "commit" to "🤞", "progress" to "📈", "insight" to "💡", "pause" to "⏸️", "attention" to "👁️", "calm" to "🌊",
    "wake" to "⏰", "bedside" to "🛏️", "future" to "🔭", "reframe" to "🔄", "night" to "🌙", "rehears" to "🎭", "thought" to "💭",
)

/** A soft tinted tile with a glyph, so every list row has a face instead of being a wall of grey text. */
@androidx.compose.runtime.Composable
private fun HubGlyph(label: String) {
    val l = label.lowercase()
    val g = GLYPHS.firstOrNull { (k, _) -> k in l }?.second
    val hue = ((label.hashCode() and 0x7fffffff) % 360).toFloat()
    val tint = androidx.compose.ui.graphics.Color.hsv(hue, 0.45f, 0.75f)
    androidx.compose.foundation.layout.Box(
        Modifier.size(40.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).background(tint.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        if (g != null) Text(g, style = MaterialTheme.typography.titleMedium)
        else Text("✨", style = MaterialTheme.typography.titleMedium)
    }
}

