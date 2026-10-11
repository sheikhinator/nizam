package com.nizam.app.feature.nx

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.ui.design.Hub
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** Mega batch 3: day rehearsal, regret shield, Jumu'ah mode, handoff packets, commute lessons, meeting tax. */
object Mega3 {
    private val LANG = listOf("English", "Urdu", "Roman Urdu")
    private fun today() = LocalDay.today().toString()

    val all: List<Spec> by lazy { listOf(
        AiSpec("rehearsal", "Day rehearsal", "Run tomorrow against your real patterns; fix weak spots tonight", Icons.Outlined.TheaterComedy, Hub.Work,
            "tomorrow plan rehearse schedule failure points prayer gaps",
            listOf(F("plan", "Tomorrow's plan", K.LONG, hint = "9 standup, 11-1 client, 3:30 meeting across town, gym 7, dinner at Ammi's"),
                F("habits", "Your usual pitfalls", K.LONG, optional = true, hint = "skip lunch, late for Asr, phone after midnight"),
                F("prayers", "Prayer times tomorrow", optional = true, hint = "Fajr 5:10, Zuhr 12:15, Asr 3:40, Maghrib 5:55, Isha 7:15")),
            "You rehearse someone's next day like a chief of staff: walk the timeline, flag clashes with salah times, travel buffers, energy dips, no meal breaks and known pitfalls, then give a fixed schedule and 3 if-then plans.") { v ->
            "Plan:\n${v["plan"]}\nPitfalls:\n${v["habits"]}\nPrayer times: ${v["prayers"]}" },
        LogSpec("regrets", "Regret shield", "Notes on choices you regretted, shown before you repeat them", Icons.Outlined.Shield, Hub.Memory,
            "regret purchase commitment pattern lesson",
            listOf(F("what", "What you regret"), F("kind", "Type", K.CHOICE, options = listOf("Purchase", "Commitment", "Food", "Words", "Time"), default = "Purchase"),
                F("cue", "What it looked like at the time", hint = "late-night sale, 'just this once'"), F("lesson", "Note to future me", K.LONG)),
            line = { o -> o.optString("what") to "${o.optString("kind")} · ${o.optString("lesson").take(60)}" },
            summary = { l -> l.groupBy { it.optString("cue").trim().lowercase() }.filterKeys { it.isNotBlank() }.entries.sortedByDescending { it.value.size }.take(3)
                .map { (cue, e) -> "Pattern: \"$cue\" (${e.size}×). Wait before saying yes." } }),
        ScreenSpec("jumuah", "Jumu'ah mode", "Ghusl, early arrival, al-Kahf, salawat and the khutbah, ticked off", Icons.Outlined.Mosque, Hub.Deen,
            "friday jummah jumuah kahf ghusl salawat khutbah") { _, open -> JumuahScreen(open) },
        AiSpec("handoffpacket", "Handoff packet", "Hand a task to someone with everything they need", Icons.Outlined.ForwardToInbox, Hub.Social,
            "delegate handoff task family packet instructions",
            listOf(F("task", "Task"), F("to", "To whom"), F("know", "What they need to know", K.LONG), F("by", "Deadline", K.DATE, optional = true),
                F("lang", "Language", K.CHOICE, options = LANG, default = "Roman Urdu")),
            "You write clear handoff packets: goal, steps in order, contacts and documents, money involved, deadline, what 'done' looks like, and what to send back as a receipt. Short and friendly, ready to paste in WhatsApp.") { v ->
            "Task: ${v["task"]} → ${v["to"]}, by ${v["by"]}.\nContext:\n${v["know"]}\nIn ${v["lang"]}." },
        AiSpec("commute", "Commute classroom", "A lesson cut to exactly your commute length", Icons.Outlined.DirectionsBus, Hub.Learn,
            "commute lesson learn travel time short lesson listen",
            listOf(F("mins", "Commute minutes", K.NUMBER, default = "25"), F("topic", "Topic or course"), F("lang", "Language", K.CHOICE, options = LANG, default = "English")),
            "You write spoken micro-lessons that fit an exact duration (about 140 words per minute): hook, 3 key ideas, one example, a recap and one question to think about. No visuals needed.") { v ->
            "A ${v["mins"]}-minute listening lesson on ${v["topic"]} in ${v["lang"]}." },
        CalcSpec("meetingtax", "Meeting tax", "What a meeting really costs in focus and money", Icons.Outlined.Groups, Hub.Work, "meeting cost time focus async",
            listOf(F("people", "People", K.NUMBER, default = "6"), F("mins", "Minutes", K.NUMBER, default = "60"), F("rate", "Average hourly cost per person", K.NUMBER, default = "1500"),
                F("switch", "Refocus minutes lost per person", K.NUMBER, default = "15")),
            listOf("Person-hours" to "people * mins / 60", "Focus hours lost (incl. refocusing)" to "people * (mins + switch) / 60",
                "Cost" to "people * (mins + switch) / 60 * rate", "If it were a 10-min async update instead, saved" to "people * (mins + switch - 10) / 60 * rate"),
            "Could it be a voice note or a written update with a 15-minute call only for decisions?"),
    ) }
}

private fun jp(c: Context) = c.getSharedPreferences("jumuah", Context.MODE_PRIVATE)

@Composable
private fun JumuahScreen(open: (String) -> Unit) {
    val c = LocalContext.current
    val friday = LocalDate.now().with(TemporalAdjusters.nextOrSame(DayOfWeek.FRIDAY))
    val key = friday.toString()
    val items = listOf(
        "Ghusl (bath) before Jumu'ah", "Clean clothes, perfume (for men), miswak", "Recite Surah al-Kahf (Thursday night or Friday)",
        "Go early to the masjid", "Two rak'ah tahiyyat al-masjid", "Listen to the khutbah in silence, phone off",
        "Abundant salawat on the Prophet ﷺ", "Dua in the last hour before Maghrib", "Give some sadaqah",
    )
    var done by remember { mutableStateOf(jp(c).getStringSet(key, emptySet())!!.toSet()) }
    var kahf by remember { mutableIntStateOf(jp(c).getInt("kahf_$key", 0)) }
    Column(Modifier.fillMaxWidth().padding(4.dp)) {
        Text(if (LocalDate.now().dayOfWeek == DayOfWeek.FRIDAY) "Jumu'ah Mubarak" else "This Friday · $friday",
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("${done.size} of ${items.size} done", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(item in done, { on -> done = if (on) done + item else done - item; jp(c).edit().putStringSet(key, done).apply() })
                Text(item, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Text("Surah al-Kahf: $kahf of 110 ayahs", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
        Row {
            listOf(10, 25, 55, 110).forEach { n -> TextButton(onClick = { kahf = n; jp(c).edit().putInt("kahf_$key", n).apply() }) { Text("$n") } }
        }
        TextButton(onClick = { open(com.nizam.app.ui.nav.Routes.QURAN) }) { Text("Open the Quran at al-Kahf →") }
        TextButton(onClick = { open("dailypath") }) { Text("This week's khutbah reminder →") }
    }
}
