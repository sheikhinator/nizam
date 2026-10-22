package com.nizam.app.feature.nx

import android.content.Context
import android.util.Base64
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import com.nizam.app.feature.bots.Bots
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.ui.design.AmbienceEngine
import com.nizam.app.ui.design.Comfort
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/**
 * A new bot learns from a senior one before it may use tools on its own: while apprenticed every tool
 * action waits for your approval, its mentor's way of working is in its context, and after enough
 * supervised runs it graduates (or you graduate or dismiss it yourself).
 */
object Apprentice {
    private fun p(c: Context) = c.getSharedPreferences("apprentice", 0)
    const val NEEDED = 5
    fun mentor(c: Context, botId: Long): Long? = p(c).getLong("m:$botId", -1L).takeIf { it >= 0 && p(c).getBoolean("on:$botId", false) }
    fun learning(c: Context, botId: Long) = p(c).getBoolean("on:$botId", false)
    fun runs(c: Context, botId: Long) = p(c).getInt("r:$botId", 0)
    fun start(c: Context, botId: Long, mentorId: Long) = p(c).edit().putBoolean("on:$botId", true).putLong("m:$botId", mentorId).putInt("r:$botId", 0).apply()
    fun graduate(c: Context, botId: Long) = p(c).edit().putBoolean("on:$botId", false).apply()

    /** Called after an apprentice finishes a tool-using turn under supervision. */
    fun recordRun(c: Context, botId: Long) {
        if (!learning(c, botId)) return
        val n = runs(c, botId) + 1
        p(c).edit().putInt("r:$botId", n).apply()
        if (n >= NEEDED) { graduate(c, botId)
            runCatching { com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 7400 + botId.toInt() % 100,
                "🎓 A bot graduated", "${Bots.all(c).firstOrNull { it.id == botId }?.name ?: "Your bot"} finished $NEEDED supervised runs and can now use tools on its own.") } }
    }

    /** Extra context for the apprentice: how its mentor works. */
    fun note(c: Context, botId: Long): String {
        val m = mentor(c, botId)?.let { id -> Bots.all(c).firstOrNull { it.id == id } } ?: return ""
        return "\n\nYou are an apprentice learning from ${m.name}. Work the way they do: ${m.brief.take(800)}\nEvery tool action you propose will be checked by the user first."
    }
}

/** A care pack: set up a family member's Atlas from your own phone, applied only after they review and accept it. */
object CarePack {
    private const val HEAD = "ATLAS-CARE-PACK:"
    fun encode(o: JSONObject) = HEAD + Base64.encodeToString(o.toString().toByteArray(), Base64.NO_WRAP)
    fun decode(s: String): JSONObject? = runCatching { JSONObject(String(Base64.decode(s.trim().substringAfter(HEAD), Base64.DEFAULT))) }
        .getOrNull()?.takeIf { s.trim().startsWith(HEAD) }

    fun describe(o: JSONObject): List<String> = buildList {
        add("From: ${o.optString("from").ifBlank { "a family member" }}")
        if (o.optBoolean("elder")) add("Switch on elder mode (bigger, simpler screens)")
        o.optDouble("text", 0.0).takeIf { it > 0 }?.let { add("Text size ${(it * 100).toInt()}%") }
        o.optString("lang").takeIf { it.isNotBlank() }?.let { add("Language: ${when (it) { "ur" -> "Urdu"; "roman" -> "Roman Urdu"; else -> "English" }}") }
        o.optJSONArray("meds")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.forEach { add("Daily reminder: ${it.optString("what")} at ${it.optString("at")}") } }
        o.optJSONArray("contacts")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.forEach { add("Emergency contact: ${it.optString("name")} ${it.optString("phone")}") } }
        o.optString("note").takeIf { it.isNotBlank() }?.let { add("Note: $it") }
    }

    suspend fun apply(container: AppContainer, o: JSONObject) {
        val c = container.appContext
        Comfort.update(c) { s -> s.copy(elder = o.optBoolean("elder", s.elder),
            textScale = o.optDouble("text", 0.0).takeIf { it > 0 }?.toFloat() ?: s.textScale) }
        o.optString("lang").takeIf { it in setOf("en", "ur", "roman") }?.let { com.nizam.app.feature.future.Lang.set(c, it) }
        o.optJSONArray("meds")?.let { a -> (0 until a.length()).map { a.getJSONObject(it) }.forEach { m ->
            val t = runCatching { LocalTime.parse(m.optString("at")) }.getOrNull() ?: return@forEach
            var at = LocalDate.now().atTime(t); if (at.isBefore(java.time.LocalDateTime.now())) at = at.plusDays(1)
            container.taskRepository.add("💊 ${m.optString("what")} (daily)", at.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), 2)
        } }
        o.optJSONArray("contacts")?.let { a -> JsonStore(c, "nx_carecontacts").let { s -> (0 until a.length()).forEach { s.add(JSONObject(a.getJSONObject(it).toString())) } } }
        JsonStore(c, "nx_carepack_log").add(JSONObject().put("at", System.currentTimeMillis()).put("from", o.optString("from")).put("changes", JSONArray(describe(o))))
    }
}

object Mega10 {
    val all: List<Spec> by lazy { listOf(
        ScreenSpec("seasons", "Seasons", "The whole app follows the season — spring, summer, monsoon, autumn, winter", Icons.Outlined.Park, Hub.Tools,
            "seasonal reskin theme monsoon winter ramadan eid calm") { c, open -> SeasonsScreen() },
        ScreenSpec("apprentice", "Agent apprenticeship", "New bots shadow a senior bot before they're trusted with tools", Icons.Outlined.School, Hub.Work,
            "agent apprentice mentor trust tools bot shadow supervised") { c, open -> ApprenticeScreen(open) },
        ScreenSpec("carepack", "Care pack", "Set up an elder's Atlas from your phone — they see and accept every change", Icons.Outlined.Elderly, Hub.Social,
            "elder remote caregiver family setup simplified medicine reminders consent") { c, open -> CarePackScreen(c) },
    ) }
}

@Composable
private fun SeasonsScreen() {
    val c = LocalContext.current
    var on by remember { mutableStateOf(AmbienceEngine.season(c) != null) }
    var calm by remember { mutableStateOf(AmbienceEngine.calm(c)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Now: ${AmbienceEngine.season(null)?.replaceFirstChar { it.uppercase() }}${AmbienceEngine.event()?.let { " · $it" }.orEmpty()}", fontWeight = FontWeight.SemiBold)
        Text("Spring brings blossom, summer a heat shimmer, monsoon a soft drizzle, autumn falling leaves and winter frost. Ramadan, Eid and Jumu'ah take over on their days.", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Follow the seasons", Modifier.weight(1f)); Switch(on, { on = it; AmbienceEngine.setSeasons(c, on, calm) }) }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Calm (colour only, nothing moving)", Modifier.weight(1f)); Switch(calm, { calm = it; AmbienceEngine.setSeasons(c, on, calm) }, enabled = on) }
        Text("Changes show the next time a screen opens.", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ApprenticeScreen(open: (String) -> Unit) {
    val c = LocalContext.current
    val bots = remember { Bots.all(c) }
    var tick by remember { mutableStateOf(0) }
    var pick by remember { mutableStateOf<Long?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (bots.size < 2) { Text("Make at least two bots first — one to mentor and one to learn.", style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = { open("bots") }) { Text("Open bots") }; return@Column }
        Text("While apprenticed, a bot never runs tools by itself — you approve each action. After ${Apprentice.NEEDED} supervised runs it graduates.", style = MaterialTheme.typography.bodyMedium)
        remember(tick) { bots }.forEach { b ->
            val learning = Apprentice.learning(c, b.id)
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${b.emoji} ${b.name}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    if (learning) TextButton(onClick = { Apprentice.graduate(c, b.id); tick++ }) { Text("Graduate now") }
                    else TextButton(onClick = { pick = if (pick == b.id) null else b.id }) { Text("Apprentice…") }
                }
                if (learning) {
                    val m = Apprentice.mentor(c, b.id)?.let { id -> bots.firstOrNull { it.id == id }?.name } ?: "—"
                    val n = Apprentice.runs(c, b.id)
                    Text("Learning from $m · $n of ${Apprentice.NEEDED} supervised runs", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { n / Apprentice.NEEDED.toFloat() }, modifier = Modifier.fillMaxWidth())
                } else Text("Trusted", style = MaterialTheme.typography.bodySmall)
                if (pick == b.id) {
                    Text("Who mentors ${b.name}?", style = MaterialTheme.typography.bodySmall)
                    bots.filter { it.id != b.id && !Apprentice.learning(c, it.id) }.forEach { m ->
                        TextButton(onClick = { Apprentice.start(c, b.id, m.id); pick = null; tick++ }) { Text("${m.emoji} ${m.name}") } }
                }
            }
        }
    }
}

@Composable
private fun CarePackScreen(container: AppContainer) {
    val c = LocalContext.current; val scope = com.nizam.app.ui.design.rememberSafeScope()
    var mode by remember { mutableStateOf("make") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row { listOf("make" to "For someone else", "receive" to "I received one", "log" to "What's been applied").forEach { (k, l) ->
            TextButton(onClick = { mode = k }) { Text(l, fontWeight = if (mode == k) FontWeight.Bold else FontWeight.Normal) } } }
        when (mode) {
            "make" -> {
                var from by remember { mutableStateOf("") }
                var elder by remember { mutableStateOf(true) }
                var big by remember { mutableStateOf(true) }
                var lang by remember { mutableStateOf("ur") }
                var meds by remember { mutableStateOf("BP tablet 09:00\nSugar tablet 21:00") }
                var contacts by remember { mutableStateOf("") }
                var note by remember { mutableStateOf("") }
                OutlinedTextField(from, { from = it }, label = { Text("Your name (shown to them)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Elder mode", Modifier.weight(1f)); Switch(elder, { elder = it }) }
                Row(verticalAlignment = Alignment.CenterVertically) { Text("Large text (130%)", Modifier.weight(1f)); Switch(big, { big = it }) }
                Row { listOf("en" to "English", "ur" to "اردو", "roman" to "Roman Urdu").forEach { (k, l) ->
                    TextButton(onClick = { lang = k }) { Text(l, fontWeight = if (lang == k) FontWeight.Bold else FontWeight.Normal) } } }
                OutlinedTextField(meds, { meds = it }, label = { Text("Daily reminders, one per line: what HH:MM") }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
                OutlinedTextField(contacts, { contacts = it }, label = { Text("Emergency contacts, one per line: name number") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(note, { note = it }, label = { Text("A note to them") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = {
                    val o = JSONObject().put("from", from).put("elder", elder).put("lang", lang).put("note", note)
                    if (big) o.put("text", 1.3)
                    o.put("meds", JSONArray().apply { meds.lines().mapNotNull { l -> Regex("(.+?)\\s+(\\d{1,2}:\\d{2})$").find(l.trim())?.let { m ->
                        put(JSONObject().put("what", m.groupValues[1]).put("at", m.groupValues[2].padStart(5, '0'))) } } })
                    o.put("contacts", JSONArray().apply { contacts.lines().mapNotNull { l -> Regex("(.+?)\\s+(\\+?[0-9 -]{7,})$").find(l.trim())?.let { m ->
                        put(JSONObject().put("name", m.groupValues[1]).put("phone", m.groupValues[2].trim())) } } })
                    Share.text(c, "Open Atlas › Care pack › I received one, and paste this:\n\n" + CarePack.encode(o), "Atlas care pack")
                }) { Text("Send care pack") }
                Text("Nothing changes on their phone until they read the list and tap Accept.", style = MaterialTheme.typography.bodySmall)
            }
            "receive" -> {
                var text by remember { mutableStateOf("") }
                var done by remember { mutableStateOf("") }
                val pack = CarePack.decode(text.substringAfter("\n\n", text))
                OutlinedTextField(text, { text = it; done = "" }, label = { Text("Paste the care pack") }, modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp))
                if (text.isNotBlank() && pack == null) Text("That isn't a care pack.", style = MaterialTheme.typography.bodySmall)
                pack?.let { o ->
                    Text("This pack will:", fontWeight = FontWeight.SemiBold)
                    CarePack.describe(o).forEach { Text("• $it") }
                    if (done.isBlank()) Row {
                        TextButton(onClick = { scope.launch { done = runCatching { CarePack.apply(container, o); "Done. You can see and undo this under \"What's been applied\"." }
                            .getOrElse { "Couldn't apply: ${it.message}" } } }) { Text("Accept") }
                        TextButton(onClick = { text = "" }) { Text("Decline") }
                    } else Text(done)
                }
            }
            else -> {
                val log = remember { JsonStore(c, "nx_carepack_log").all().reversed() }
                if (log.isEmpty()) Text("No care packs applied.", style = MaterialTheme.typography.bodyMedium)
                log.forEach { e -> Text("${java.time.Instant.ofEpochMilli(e.optLong("at")).atZone(java.time.ZoneId.systemDefault()).toLocalDate()} · from ${e.optString("from").ifBlank { "family" }}", fontWeight = FontWeight.SemiBold)
                    e.optJSONArray("changes")?.let { a -> (0 until a.length()).forEach { Text("• ${a.optString(it)}", style = MaterialTheme.typography.bodySmall) } } }
                val contacts = remember { JsonStore(c, "nx_carecontacts").all() }
                if (contacts.isNotEmpty()) { Text("Emergency contacts", fontWeight = FontWeight.SemiBold)
                    contacts.forEach { ct -> TextButton(onClick = { runCatching { c.startActivity(android.content.Intent(android.content.Intent.ACTION_DIAL,
                        android.net.Uri.parse("tel:" + ct.optString("phone").filter { it.isDigit() || it == '+' })).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) {
                        Text("📞 ${ct.optString("name")} · ${ct.optString("phone")}") } } }
                TextButton(onClick = { Comfort.update(c) { it.copy(elder = false, textScale = 1f) } }) { Text("Turn elder mode and large text off") }
            }
        }
    }
}
