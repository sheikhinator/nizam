package com.nizam.app.feature.agent

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.ui.components.Feedback
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * A small team rather than one assistant. You hand over a goal; each agent works it a little each
 * day in the background, keeps its own running log, and reports when it has something.
 *
 * Each agent has the full Curator toolset, so they can actually act — not just write about acting.
 */
data class Agent(val key: String, val name: String, val emoji: String, val brief: String)

val WORKFORCE = listOf(
    Agent("researcher", "Researcher", "🔎",
        "You research. Each day, advance the goal by finding something concrete and new: facts, options, prices, " +
            "people, sources. Use search_web. Report findings, never plans to find things."),
    Agent("planner", "Planner", "🗺",
        "You break goals into moves. Each day, look at what's been done and set the next concrete step, sized to the " +
            "time and energy they actually have. Add tasks for the step you choose."),
    Agent("bookkeeper", "Bookkeeper", "📒",
        "You watch the money. Each day, check spending against the goal, flag leaks and unusual charges, and report " +
            "the number that matters. Use their real finance data."),
    Agent("coach", "Coach", "🏋",
        "You hold them to it. Each day, check the evidence for this goal, say plainly whether they moved, and give one " +
            "specific instruction for tomorrow. Encouraging but never soft on the facts.")
)

data class Assignment(val id: Long, val agent: String, val goal: String, val lastRun: String, val log: List<String>)

object Workforce {
    private fun f(c: Context) = File(c.filesDir, "workforce.json")
    fun all(c: Context): List<Assignment> = runCatching { JSONArray(f(c).readText()) }.getOrElse { JSONArray() }
        .let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            Assignment(o.optLong("id"), o.optString("agent"), o.optString("goal"), o.optString("lastRun"),
                o.optJSONArray("log")?.let { l -> (0 until l.length()).map { l.optString(it) } } ?: emptyList()) } }
    fun save(c: Context, list: List<Assignment>) = f(c).writeText(JSONArray().apply {
        list.forEach { put(JSONObject().put("id", it.id).put("agent", it.agent).put("goal", it.goal)
            .put("lastRun", it.lastRun).put("log", JSONArray(it.log.takeLast(20)))) } }.toString())

    /** One agent works one goal, once a day, and only reports when it has something to say. */
    suspend fun runDue(c: Context, container: AppContainer, now: Double) {
        if (now < 7.0 || now > 9.0) return                       // the team works first thing
        val today = LocalDate.now().toString()
        val list = all(c)
        val due = list.filter { it.lastRun != today }
        if (due.isEmpty()) return
        val updated = list.toMutableList()
        due.take(2).forEach { a ->                               // at most two agents a day, to stay inside AI quota
            val agent = WORKFORCE.firstOrNull { it.key == a.agent } ?: return@forEach
            val result = runCatching {
                container.agentLoop.run(
                    "You are the ${agent.name}. ${agent.brief}\n\nGoal you've been given: ${a.goal}\n" +
                        "Your log so far:\n" + a.log.takeLast(6).joinToString("\n").ifBlank { "(nothing yet)" } +
                        "\n\nDo today's work on this goal now, then report in under 80 words what you did and found. " +
                        "If there is genuinely nothing useful to do today, say exactly: NOTHING TODAY.",
                    useWeb = true, autoApprove = true
                ).reply.trim()
            }.getOrElse { "Couldn't work today: ${Feedback.friendly(it)}" }
            val i = updated.indexOfFirst { it.id == a.id }
            updated[i] = a.copy(lastRun = today, log = a.log + "$today — $result")
            if (!result.startsWith("NOTHING TODAY", true) && com.nizam.app.feature.presence.Presence.canSpeak(c)) {
                Notifications.post(c, Notifications.CHANNEL_NUDGE, (280 + a.id % 10).toInt(),
                    "${agent.emoji} ${agent.name}", result.take(300))
                com.nizam.app.feature.presence.Presence.noteSpoke(c)
            }
        }
        save(c, updated)
    }
}

@Composable
fun WorkforceScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = remember { kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.SupervisorJob()) }
    var list by remember { mutableStateOf(Workforce.all(c)) }
    var agent by remember { mutableStateOf("researcher") }
    var goal by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Workforce", "Delegate a goal — they work it daily and report back")
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                ChipRow(WORKFORCE.map { it.key }, agent, { agent = it },
                    labels = { k -> WORKFORCE.first { it.key == k }.let { "${it.emoji} ${it.name}" } })
                Text(WORKFORCE.first { it.key == agent }.brief.substringBefore(". Each day"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(goal, { goal = it }, label = { Text("What should they work on?") }, modifier = Modifier.fillMaxWidth())
                Button(enabled = goal.isNotBlank(), onClick = {
                    list = list + Assignment(System.currentTimeMillis(), agent, goal.trim(), "", emptyList())
                    Workforce.save(c, list); goal = ""; Feedback.show("Assigned — they start tomorrow morning")
                }) { Text("Delegate it") }
                Text("They work between 7 and 9am, at most two a day to stay inside your AI quota, and stay silent on days " +
                    "with nothing useful to report.", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        list.forEach { a ->
            val who = WORKFORCE.firstOrNull { it.key == a.agent }
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(14.dp)) {
                    Text("${who?.emoji ?: ""} ${who?.name ?: a.agent}", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary)
                    Text(a.goal, style = MaterialTheme.typography.titleMedium)
                    a.log.takeLast(4).reversed().forEach {
                        Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
                    }
                    if (a.log.isEmpty()) Text("No work logged yet.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.padding(top = 8.dp)) {
                        Text(if (busy) "working…" else "run now", color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clickable(role = Role.Button) {
                                scope.launch {
                                    busy = true; Feedback.show("${who?.name} is working…")
                                    val fresh = a.copy(lastRun = "")
                                    Workforce.save(c, list.map { if (it.id == a.id) fresh else it })
                                    Workforce.runDue(c, container, 8.0)
                                    list = Workforce.all(c); busy = false
                                }
                            }.padding(end = 18.dp))
                        Text("remove", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.clickable(role = Role.Button) { list = list - a; Workforce.save(c, list) })
                    }
                }
            }
        }
        Spacer(Modifier.height(60.dp))
    }
}
