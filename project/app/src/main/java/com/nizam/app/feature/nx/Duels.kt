package com.nizam.app.feature.nx

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SportsMma
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneId

/**
 * Duels: challenge a friend to a few days head-to-head (tasks, gym sets, prayers, water, no-spend days).
 * No server and no accounts: the invite and each day's score travel as short codes over WhatsApp.
 * Only the duel's own number is ever shared, nothing else from Atlas.
 */
object Duels {
    val METRICS = listOf("tasks" to "Tasks done", "sets" to "Gym sets", "prayers" to "Prayers logged", "water" to "Water (ml)", "nospend" to "No-spend days")
    private fun store(c: Context) = JsonStore(c, "duels")
    fun all(c: Context) = store(c).all().sortedByDescending { it.optString("start") }
    fun myName(c: Context) = c.getSharedPreferences("duels", 0).getString("name", "").orEmpty()
    fun setName(c: Context, n: String) = c.getSharedPreferences("duels", 0).edit().putString("name", n.trim()).apply()

    fun create(c: Context, metric: String, days: Int): JSONObject = store(c).add(JSONObject().put("duel", "d" + System.currentTimeMillis().toString(36))
        .put("metric", metric).put("days", days).put("start", LocalDate.now().toString()).put("them", "").put("theirs", JSONObject()))

    private fun enc(prefix: String, o: JSONObject) = prefix + android.util.Base64.encodeToString(o.toString().toByteArray(), android.util.Base64.NO_WRAP)
    private fun dec(s: String, prefix: String): JSONObject? = runCatching {
        val i = s.indexOf(prefix); if (i < 0) return null
        val body = s.substring(i + prefix.length).trim().split(Regex("\\s"))[0]
        JSONObject(String(android.util.Base64.decode(body, android.util.Base64.DEFAULT)))
    }.getOrNull()

    fun invite(c: Context, d: JSONObject) = "${myName(c).ifBlank { "A friend" }} challenges you to an Atlas duel: ${label(d.optString("metric"))} for ${d.optInt("days")} days. " +
        "Open Atlas → Duels → paste this:\n" + enc("ATLAS-DUEL:", JSONObject().put("duel", d.optString("duel")).put("metric", d.optString("metric"))
            .put("days", d.optInt("days")).put("start", d.optString("start")).put("from", myName(c).ifBlank { "Friend" }))

    fun scoreCode(c: Context, d: JSONObject, scores: Map<String, Double>) = "My ${label(d.optString("metric"))} so far. Paste in Atlas → Duels:\n" +
        enc("ATLAS-DUELSCORE:", JSONObject().put("duel", d.optString("duel")).put("name", myName(c).ifBlank { "Friend" }).put("scores", JSONObject(scores.mapValues { it.value })))

    /** Accepts either an invite or a score code. Returns a message for the user. */
    fun paste(c: Context, text: String): String {
        val s = store(c)
        dec(text, "ATLAS-DUELSCORE:")?.let { sc ->
            val d = s.all().firstOrNull { it.optString("duel") == sc.optString("duel") } ?: return "That score is for a duel you haven't joined."
            s.update(d.optString("id")) { it.put("them", sc.optString("name")).put("theirs", sc.optJSONObject("scores") ?: JSONObject()) }
            return "Updated ${sc.optString("name")}'s score"
        }
        dec(text, "ATLAS-DUEL:")?.let { inv ->
            if (s.all().any { it.optString("duel") == inv.optString("duel") }) return "You're already in that duel"
            s.add(JSONObject().put("duel", inv.optString("duel")).put("metric", inv.optString("metric")).put("days", inv.optInt("days", 7))
                .put("start", inv.optString("start")).put("them", inv.optString("from")).put("theirs", JSONObject()))
            return "Joined! Send your score back each evening"
        }
        return "That isn't a duel code"
    }

    fun remove(c: Context, id: String) = store(c).remove(id)
    fun label(m: String) = METRICS.firstOrNull { it.first == m }?.second ?: m

    /** My score per day from my own records. */
    suspend fun mine(container: AppContainer, d: JSONObject): Map<String, Double> {
        val start = runCatching { LocalDate.parse(d.optString("start")) }.getOrDefault(LocalDate.now())
        val days = (0 until d.optInt("days", 7)).map { start.plusDays(it.toLong()) }.filter { !it.isAfter(LocalDate.now()) }
        val zone = ZoneId.systemDefault()
        fun dayOf(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val per: Map<LocalDate, Double> = when (d.optString("metric")) {
            "tasks" -> container.taskRepository.all().first().mapNotNull { it.completedAt?.let(::dayOf) }.groupingBy { it }.eachCount().mapValues { it.value.toDouble() }
            "sets" -> container.gymRepository.sets().first().groupingBy { LocalDate.parse(it.localDate) }.eachCount().mapValues { it.value.toDouble() }
            "prayers" -> container.prayerRepository.since(start.toString()).first().associate { x ->
                LocalDate.parse(x.localDate) to listOf(x.fajr, x.dhuhr, x.asr, x.maghrib, x.isha).count { it != "NOT_PRAYED" }.toDouble() }
            "water" -> container.dietRepository.waterIntakes().groupBy { dayOf(it.createdAt) }.mapValues { e -> e.value.sumOf { it.ml }.toDouble() }
            "nospend" -> { val spent = container.financeRepository.since(start.toString()).first().filter { it.kind == "EXPENSE" }.map { LocalDate.parse(it.localDate) }.toSet()
                days.filter { it.isBefore(LocalDate.now()) }.associateWith { if (it in spent) 0.0 else 1.0 } }
            else -> emptyMap()
        }
        return days.associate { it.toString() to (per[it] ?: 0.0) }
    }
}

@Composable
fun DuelsScreen(container: AppContainer) {
    val c = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val duels = remember(tick) { Duels.all(c) }
    var name by remember { mutableStateOf(Duels.myName(c)) }
    var metric by remember { mutableStateOf("tasks") }
    var days by remember { mutableIntStateOf(7) }
    var code by remember { mutableStateOf("") }
    val cs = MaterialTheme.colorScheme
    AtlasScreen(title = "Duels", subtitle = "Challenge a friend head-to-head. Codes travel over WhatsApp, no accounts.", hub = Hub.Social) {
        item("new") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                OutlinedTextField(name, { name = it; Duels.setName(c, it) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Your name in duels") })
                Text("Challenge", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = Space.s))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    items(Duels.METRICS.size) { i -> val (k, l) = Duels.METRICS[i]; Capsule(l, metric == k) { metric = k } }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    val opts = listOf(3, 7, 14, 30)
                    items(opts.size) { i -> Capsule("${opts[i]} days", days == opts[i]) { days = opts[i] } }
                }
                Button(onClick = {
                    val d = Duels.create(c, metric, days); tick++
                    Share.text(c, Duels.invite(c, d), "Atlas duel")
                }, enabled = name.isNotBlank()) { Text("Create & send invite") }
            }
        }
        item("paste") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text("Got a code?", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), maxLines = 4, placeholder = { Text("Paste an invite or a score") })
                TextButton(onClick = { Feedback.show(Duels.paste(c, code)); code = ""; tick++ }, enabled = code.isNotBlank()) { Text("Use code") }
            }
        }
        if (duels.isNotEmpty()) item("dh") { SectionHeader("Your duels") }
        duels.forEach { d ->
            item("d" + d.optString("id")) {
                val mine by produceState(initialValue = emptyMap<String, Double>(), d.optString("id"), tick) { value = runCatching { Duels.mine(container, d) }.getOrDefault(emptyMap()) }
                val theirs = d.optJSONObject("theirs") ?: JSONObject()
                val me = mine.values.sum(); val them = theirs.keys().asSequence().sumOf { theirs.optDouble(it, 0.0) }
                val start = runCatching { LocalDate.parse(d.optString("start")) }.getOrDefault(LocalDate.now())
                val left = (d.optInt("days", 7) - java.time.temporal.ChronoUnit.DAYS.between(start, LocalDate.now()).toInt()).coerceAtLeast(0)
                val opp = d.optString("them").ifBlank { "Waiting for them" }
                Group(Modifier.padding(horizontal = Space.page, vertical = 4.dp)) {
                    Text("${Duels.label(d.optString("metric"))} · ${if (left > 0) "$left day${if (left > 1) "s" else ""} left" else "Finished"}",
                        style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text(name.ifBlank { "You" }, fontWeight = FontWeight.SemiBold); Text(short(me), style = MaterialTheme.typography.headlineSmall,
                            color = if (me >= them) Color(0xFF2E9E6A) else cs.onSurface) }
                        Text("vs", Modifier.width(36.dp), color = cs.onSurfaceVariant)
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) { Text(opp, fontWeight = FontWeight.SemiBold); Text(short(them), style = MaterialTheme.typography.headlineSmall,
                            color = if (them > me) Color(0xFF2E9E6A) else cs.onSurface) }
                    }
                    Text(when { left == 0 && me > them -> "🏆 You won"; left == 0 && them > me -> "They won this one. Rematch?"; me > them -> "You're ahead"; them > me -> "$opp is ahead"; else -> "Level" },
                        fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { Share.text(c, Duels.scoreCode(c, d, mine), "My duel score") }) { Text("Send my score") }
                        if (d.optString("them").isBlank()) TextButton(onClick = { Share.text(c, Duels.invite(c, d), "Atlas duel") }) { Text("Resend invite") }
                        TextButton(onClick = { Duels.remove(c, d.optString("id")); tick++ }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

val DuelSpecs: List<Spec> get() = listOf(
    ScreenSpec("duels", "Duels", "Challenge a friend head-to-head on tasks, gym, prayers, water or no-spend days",
        Icons.Outlined.SportsMma, Hub.Social, "duel challenge friend compete versus head to head race competition") { container, _ -> DuelsScreen(container) })
