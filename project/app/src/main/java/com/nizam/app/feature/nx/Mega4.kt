package com.nizam.app.feature.nx

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nizam.app.ui.design.Hub
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Mega batch 4: food, vocabulary, writing, promises, focus switches, maintenance history, and the Trust Center. */
object Mega4 {
    private val LANG = listOf("English", "Urdu", "Roman Urdu")
    private fun today() = LocalDay.today().toString()
    private fun d(o: JSONObject, k: String) = runCatching { LocalDate.parse(o.optString(k)) }.getOrNull()

    val all: List<Spec> by lazy { listOf(
        AiSpec("pantryplan", "Pantry meal plan", "A week of meals from what's already in your kitchen", Icons.Outlined.Kitchen, Hub.Home,
            "meal plan pantry week menu groceries leftovers",
            listOf(F("have", "What you have", K.LONG, hint = "rice 5kg, chicken 1kg, daal, eggs, potatoes, tomatoes, yoghurt"), F("people", "People", K.NUMBER, default = "4"),
                F("prefs", "Preferences", optional = true, hint = "desi, less oil, one veg day"), F("budget", "Extra budget for the week (PKR)", K.NUMBER, optional = true)),
            "You plan practical Pakistani home menus that use up what's in the pantry first. 7 days × lunch and dinner, what each uses, a short shopping list for gaps with rough PKR prices, and which leftovers become the next meal.") { v ->
            "Pantry: ${v["have"]}\nPeople: ${v["people"]}\nPreferences: ${v["prefs"]}\nExtra budget: ${v["budget"]}" },
        AiSpec("nutrition", "Nutrition deep-dive", "Protein, fibre, iron, calcium and more in what you ate", Icons.Outlined.MonitorHeart, Hub.Health,
            "nutrition micronutrients vitamins iron calcium fibre protein diet analysis",
            listOf(F("ate", "What you ate today or this week", K.LONG, hint = "paratha + egg, daal chawal, 2 cups chai, an apple, chicken karahi with 2 roti")),
            "You are a registered dietitian familiar with Pakistani food. Estimate calories, protein, fibre, iron, calcium, vitamin D, B12 and sodium with honest ranges, compare to adult daily needs in a table, flag the biggest gaps, and suggest easy local foods to fill them. Not medical advice.") { v -> v["ate"].orEmpty() },
        LogSpec("mealcost", "Meal cost", "What each dish really costs per serving", Icons.Outlined.RestaurantMenu, Hub.Home, "meal cost per serving recipe grocery price",
            listOf(F("dish", "Dish"), F("cost", "Ingredient cost", K.NUMBER), F("serves", "Servings", K.NUMBER, default = "4"), F("d", "Date", K.DATE, default = today())),
            line = { o -> o.optString("dish") to "${rs(num(o.optString("cost")) / maxOf(1.0, num(o.optString("serves"))))} per serving · ${o.optString("d")}" },
            summary = { l -> l.groupBy { it.optString("dish").trim().lowercase() }.map { (k, e) ->
                "${k.replaceFirstChar { it.uppercase() }}: avg ${rs(e.map { num(it.optString("cost")) / maxOf(1.0, num(it.optString("serves"))) }.average())}/serving" }.take(6) }),
        LogSpec("vocab", "Vocabulary review", "Words you met, reviewed on a spaced schedule", Icons.Outlined.Translate, Hub.Learn, "vocabulary words review spaced repetition flashcards",
            listOf(F("word", "Word"), F("meaning", "Meaning"), F("ex", "Example sentence", optional = true),
                F("next", "Next review", K.DATE, default = LocalDate.now().plusDays(1).toString()),
                F("box", "Box (1 new … 5 known)", K.CHOICE, options = listOf("1", "2", "3", "4", "5"), default = "1")),
            line = { o -> "${o.optString("word")} — ${o.optString("meaning")}" to "box ${o.optString("box")} · review ${o.optString("next")}" },
            summary = { l -> val due = l.filter { d(it, "next")?.isAfter(LocalDate.now()) == false }
                listOf("${due.size} due today: ${due.take(8).joinToString { it.optString("word") }}",
                    "After reviewing, move a word up a box and set its next date: box 2 = +3 days, 3 = +7, 4 = +21, 5 = +60") }),
        LogSpec("drafts", "Draft revisions", "Named versions of a piece, so nothing good is lost", Icons.Outlined.History, Hub.Create, "draft revision version writing history",
            listOf(F("piece", "Piece"), F("ver", "Version name", hint = "v2 tighter intro"), F("text", "Text", K.LONG), F("d", "Date", K.DATE, default = today())),
            line = { o -> "${o.optString("piece")} · ${o.optString("ver")}" to "${o.optString("d")} · ${o.optString("text").length} chars" },
            summary = { l -> l.groupBy { it.optString("piece").trim() }.map { (p, e) -> "$p: ${e.size} versions" } }),
        AiSpec("voicedraft", "Voice to draft", "Ramble out loud; get a clean, structured draft", Icons.Outlined.KeyboardVoice, Hub.Create,
            "dictation voice draft ramble clean up structure",
            listOf(F("talk", "Paste or dictate (mic on your keyboard)", K.LONG), F("into", "Turn it into", K.CHOICE, options = listOf("Article", "Email", "Speech", "Notes", "Social post", "Letter"), default = "Notes"),
                F("lang", "Language", K.CHOICE, options = LANG, default = "English")),
            "You turn messy spoken thoughts into a clean draft in the speaker's own voice: remove filler and repetition, keep their ideas and phrasing, add structure and headings where they help. Do not add new claims.") { v ->
            "Turn this into a ${v["into"]} in ${v["lang"]}:\n\n${v["talk"]}" },
        AiSpec("reflectmap", "Reflection map", "Map a period of your life: themes, turning points, lessons", Icons.Outlined.Hub, Hub.Memory,
            "reflection map life review themes lessons journal",
            listOf(F("period", "Which period", hint = "my first year at the job, 2025, since Ramadan"), F("notes", "What happened (journal bits, events)", K.LONG)),
            "You help someone reflect. From their notes, draw a reflection map: 3-5 themes with evidence, turning points in order, what helped and what hurt, recurring patterns, and 3 lessons to carry forward. Gentle, specific, no clichés.") { v ->
            "Period: ${v["period"]}\nNotes:\n${v["notes"]}" },
        AiSpec("promise", "Promise to project", "Turn a promise you made into a plan you'll keep", Icons.Outlined.Handshake, Hub.Work,
            "promise commitment plan project steps deadline",
            listOf(F("promise", "What you promised, and to whom"), F("by", "By when", K.DATE, optional = true)),
            "Turn a promise into a small project: the outcome that counts as 'kept', milestones with dates working back from the deadline, the first step for today, what could make it slip, and a check-in message to send the person.") { v ->
            "Promise: ${v["promise"]}. Due: ${v["by"]}. Today is ${today()}." },
        LogSpec("switches", "Context-switch map", "Every time you jump between work and phone, and what it costs", Icons.Outlined.SwapCalls, Hub.Work,
            "context switch focus interruption distraction log",
            listOf(F("from", "From"), F("to", "To"), F("why", "Why", K.CHOICE, options = listOf("Notification", "Someone asked", "Bored", "Stuck", "Planned"), default = "Notification"),
                F("d", "Date", K.DATE, default = today())),
            line = { o -> "${o.optString("from")} → ${o.optString("to")}" to "${o.optString("why")} · ${o.optString("d")}" },
            summary = { l -> val t = l.filter { it.optString("d") == today() }
                listOf("Today: ${t.size} switches ≈ ${t.size * 23} minutes of refocusing", "Top cause: " + (l.groupBy { it.optString("why") }.maxByOrNull { it.value.size }?.key ?: "—")) }),
        LogSpec("maintlog", "Maintenance history", "Every repair and service, per item, with cost", Icons.Outlined.Build, Hub.Home,
            "maintenance repair service history appliance ac fridge car cost",
            listOf(F("item", "Item", hint = "AC bedroom, fridge, car"), F("what", "What was done"), F("cost", "Cost", K.NUMBER, optional = true),
                F("who", "Who did it", optional = true), F("d", "Date", K.DATE, default = today()), F("next", "Next due", K.DATE, optional = true)),
            line = { o -> "${o.optString("item")} · ${o.optString("what")}" to "${o.optString("d")}${if (o.optString("cost").isNotBlank()) " · ${rs(num(o.optString("cost")))}" else ""}${if (o.optString("next").isNotBlank()) " · next ${o.optString("next")}" else ""}" },
            summary = { l -> l.groupBy { it.optString("item").trim() }.map { (i, e) -> "$i: ${e.size} jobs, ${rs(e.sumOf { num(it.optString("cost")) })} total" }.take(6) +
                l.filter { d(it, "next")?.let { n -> ChronoUnit.DAYS.between(LocalDate.now(), n) in 0..14 } == true }.map { "Due soon: ${it.optString("item")} (${it.optString("next")})" } }),
        ScreenSpec("trust", "Trust Center", "What Atlas can access, what it keeps, and what it's done for you", Icons.Outlined.VerifiedUser, Hub.Tools,
            "trust privacy permissions data stored ai keys approvals audit") { c, open -> TrustCenter(open) },
    ) }
}

@Composable
private fun TrustCenter(open: (String) -> Unit) {
    val c = LocalContext.current
    val perms = remember {
        listOf(Manifest.permission.READ_SMS to "SMS (bank messages)", Manifest.permission.READ_CALENDAR to "Calendar", Manifest.permission.READ_CONTACTS to "Contacts",
            Manifest.permission.RECORD_AUDIO to "Microphone", Manifest.permission.CAMERA to "Camera", Manifest.permission.ACCESS_FINE_LOCATION to "Location",
            Manifest.permission.POST_NOTIFICATIONS to "Notifications").map { (p, l) -> l to (ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED) }
    }
    val files = remember { c.filesDir.walkTopDown().filter { it.isFile }.toList() }
    val mb = files.sumOf { it.length() } / 1_048_576.0
    val keys = remember { com.nizam.app.net.ApiKeys.SLOTS.count { com.nizam.app.net.ApiKeys.enabled(c, it.id) } }
    val pending = remember { runCatching { com.nizam.app.feature.agent.ApprovalInbox.pending(c).size }.getOrDefault(0) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Everything stays on this phone unless you send it somewhere.", style = MaterialTheme.typography.bodyMedium)
        Text("Permissions", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        perms.forEach { (l, on) -> Text("${if (on) "🟢" else "⚪"} $l ${if (on) "· allowed" else "· not allowed"}", style = MaterialTheme.typography.bodyMedium) }
        Text("Data on this phone", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Text("${files.size} files · ${"%.1f".format(mb)} MB · the main database is encrypted", style = MaterialTheme.typography.bodyMedium)
        Text("AI", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        Text("$keys AI keys switched on (stored sealed on this phone) · $pending actions waiting for your approval", style = MaterialTheme.typography.bodyMedium)
        Text("Protected screens", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
        com.nizam.app.ui.nav.ReAuthSettings()
        Row {
            TextButton(onClick = { open(com.nizam.app.ui.nav.Routes.SETTINGS) }) { Text("Settings") }
            TextButton(onClick = { open("nx/ailab") }) { Text("AI memory audit") }
            TextButton(onClick = { open("nx/autorules") }) { Text("Automations") }
        }
    }
}
