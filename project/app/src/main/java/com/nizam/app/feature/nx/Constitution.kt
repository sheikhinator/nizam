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
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Your Life Constitution: the rules you choose to live by, written once. Each evening Atlas holds the
 * day up against them from what you actually logged (kept, broken or no evidence), keeps the record,
 * and before a big decision tells you which of your own articles it honours or breaks.
 */
object Constitution {
    private fun articles(c: Context) = JsonStore(c, "constitution")
    private fun verdicts(c: Context) = JsonStore(c, "constitution_verdicts")
    private fun p(c: Context) = c.getSharedPreferences("constitution", 0)
    fun list(c: Context) = articles(c).all().sortedBy { it.optLong("t") }
    fun add(c: Context, text: String) = articles(c).add(JSONObject().put("text", text.trim()).put("t", System.currentTimeMillis()))
    fun remove(c: Context, id: String) = articles(c).remove(id)
    fun history(c: Context) = verdicts(c).all().sortedByDescending { it.optString("date") }
    fun nightly(c: Context) = p(c).getBoolean("nightly", true)
    fun setNightly(c: Context, v: Boolean) = p(c).edit().putBoolean("nightly", v).apply()

    suspend fun judge(c: Context, container: AppContainer): JSONObject {
        val arts = list(c)
        require(arts.isNotEmpty()) { "Write at least one article first" }
        val today = LocalDate.now().toString()
        val data = LifeContext.digest(LifeContext.YES, "today " + arts.joinToString(" ") { it.optString("text") }, c)
        val raw = Ai.generate(container.settingsRepository, "$data\n\nToday is $today. The user's own life constitution:\n" +
            arts.mapIndexed { i, a -> "Article ${i + 1}: ${a.optString("text")}" }.joinToString("\n") +
            "\n\nJudge TODAY only, using only the recorded data above. For each article decide kept, broken, or unknown (no evidence). Be fair and brief. " +
            "Output only JSON: {\"verdicts\":[{\"article\":1,\"result\":\"kept|broken|unknown\",\"why\":\"one short line citing the data\"}],\"summary\":\"one kind, honest sentence\"}",
            system = "You are a fair judge of one person's own rules. Never invent events. Output only JSON.", maxOutputTokens = 900)
        val o = EngineX.json(raw) ?: throw IllegalStateException("Couldn't reach a verdict. Try again.")
        val v = o.optJSONArray("verdicts") ?: JSONArray()
        val kept = (0 until v.length()).count { v.optJSONObject(it)?.optString("result") == "kept" }
        val broken = (0 until v.length()).count { v.optJSONObject(it)?.optString("result") == "broken" }
        val rec = JSONObject().put("date", today).put("verdicts", v).put("summary", o.optString("summary")).put("kept", kept).put("broken", broken)
            .put("articles", JSONArray(arts.map { it.optString("text") }))
        val s = verdicts(c)
        s.all().filter { it.optString("date") == today }.forEach { s.remove(it.optString("id")) }
        s.add(rec)
        s.all().sortedBy { it.optString("date") }.let { all -> if (all.size > 120) s.save(all.takeLast(120)) }
        return rec
    }

    suspend fun check(container: AppContainer, arts: List<JSONObject>, decision: String): String =
        Ai.generate(container.settingsRepository, "The user's own life constitution:\n" +
            arts.mapIndexed { i, a -> "Article ${i + 1}: ${a.optString("text")}" }.joinToString("\n") +
            "\n\nThey are considering: $decision\n\nWhich articles does this honour, which does it break, and what version of the decision would keep all of them? Short and direct.",
            system = "You hold the user to their own rules, kindly and honestly. You don't add rules of your own.", maxOutputTokens = 600)

    suspend fun tick(c: Context, container: AppContainer, now: Double) {
        if (now !in 21.5..23.0 || !nightly(c) || list(c).isEmpty()) return
        val today = LocalDate.now().toString()
        if (p(c).getString("judged", "") == today) return
        p(c).edit().putString("judged", today).apply()
        runCatching { judge(c, container) }.onSuccess { r ->
            Notifications.post(c, Notifications.CHANNEL_NUDGE, 383, "Today's verdict: ${r.optInt("kept")} kept, ${r.optInt("broken")} broken",
                r.optString("summary"), route = "nx/constitution")
        }
    }
}

@Composable
fun ConstitutionScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var tick by remember { mutableIntStateOf(0) }
    val arts = remember(tick) { Constitution.list(c) }
    val history = remember(tick) { Constitution.history(c) }
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var decision by remember { mutableStateOf("") }
    var advice by remember { mutableStateOf<String?>(null) }
    var nightly by remember { mutableStateOf(Constitution.nightly(c)) }
    val cs = MaterialTheme.colorScheme
    AtlasScreen(title = "My constitution", subtitle = "The rules you live by. Atlas holds each day up against them.", hub = Hub.Memory) {
        item("arts") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                if (arts.isEmpty()) Text("Write the rules you want to live by. Start with two or three.", style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
                arts.forEachIndexed { i, a ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}.", Modifier.width(28.dp), fontWeight = FontWeight.Bold, color = cs.primary)
                        Text(a.optString("text"), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                        TextButton(onClick = { Constitution.remove(c, a.optString("id")); tick++ }) { Text("✕") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(text, { text = it }, Modifier.weight(1f), placeholder = { Text("Add an article") }, maxLines = 3,
                        trailingIcon = { MicButton({ heard -> text = heard }) })
                    TextButton(onClick = { Constitution.add(c, text); text = ""; tick++ }, enabled = text.isNotBlank()) { Text("Add") }
                }
                LazyRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    val ideas = listOf("Family before work after 8pm", "Pray all five, on time", "No debt for wants, only needs", "Sleep before midnight",
                        "Move my body every day", "No phone in the first hour", "Save before I spend", "Reply to my parents the same day")
                    items(ideas.size) { i -> Capsule("+ " + ideas[i], false) { Constitution.add(c, ideas[i]); tick++ } }
                }
            }
        }
        item("judge") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = {
                        busy = true
                        scope.launch { runCatching { Constitution.judge(c, container) }.onFailure { Feedback.show(it.message ?: "Couldn't judge") }; busy = false; tick++ }
                    }, enabled = arts.isNotEmpty() && !busy) { Text(if (busy) "Weighing the day…" else "⚖ Judge today") }
                }
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Judge automatically each night at 9:30", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    Switch(nightly, { nightly = it; Constitution.setNightly(c, it) })
                }
                Text("Uses what you logged in Atlas (never private logs). No evidence counts as unknown, not broken.", style = MaterialTheme.typography.labelSmall,
                    color = cs.onSurfaceVariant)
            }
        }
        if (history.isNotEmpty()) {
            item("hh") { SectionHeader("Verdicts") }
            val week = history.take(7)
            item("score") {
                val k = week.sumOf { it.optInt("kept") }; val b = week.sumOf { it.optInt("broken") }
                Text("Last ${week.size} day${if (week.size > 1) "s" else ""}: $k kept · $b broken" + if (k + b > 0) " · ${k * 100 / (k + b)}% faithful" else "",
                    Modifier.padding(horizontal = Space.page), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            history.take(10).forEach { h ->
                item("v" + h.optString("id")) {
                    Group(Modifier.padding(horizontal = Space.page, vertical = 4.dp)) {
                        Text(com.nizam.app.core.prettyDate(h.optString("date")), style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                        Text(h.optString("summary"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 4.dp))
                        val v = h.optJSONArray("verdicts") ?: JSONArray()
                        val names = h.optJSONArray("articles") ?: JSONArray()
                        for (i in 0 until v.length()) v.optJSONObject(i)?.let { x ->
                            val n = x.optInt("article")
                            val res = x.optString("result")
                            Row(Modifier.padding(vertical = 2.dp)) {
                                Text(when (res) { "kept" -> "✓"; "broken" -> "✗"; else -> "?" }, Modifier.width(22.dp),
                                    color = when (res) { "kept" -> Color(0xFF2E9E6A); "broken" -> cs.error; else -> cs.onSurfaceVariant }, fontWeight = FontWeight.Bold)
                                Column {
                                    Text(names.optString(n - 1, "Article $n"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                                    Text(x.optString("why"), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        item("decide") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text("Before a big decision", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(decision, { decision = it }, Modifier.fillMaxWidth().padding(top = 4.dp), placeholder = { Text("Take the job offer in Karachi?") }, maxLines = 3,
                    trailingIcon = { MicButton({ heard -> decision = heard }) })
                TextButton(onClick = {
                    busy = true
                    scope.launch { runCatching { Constitution.check(container, arts, decision) }.onSuccess { advice = it }.onFailure { Feedback.show(it.message ?: "Couldn't check") }; busy = false }
                }, enabled = decision.isNotBlank() && arts.isNotEmpty() && !busy) { Text("Check against my constitution") }
                advice?.let { Md(it) }
            }
        }
    }
}

val ConstitutionSpecs: List<Spec> get() = listOf(
    ScreenSpec("constitution", "My constitution", "Write the rules you live by; Atlas judges each day against them and checks big decisions",
        Icons.Outlined.Gavel, Hub.Memory, "constitution rules principles values live by verdict judge decision") { container, _ -> ConstitutionScreen(container) })
