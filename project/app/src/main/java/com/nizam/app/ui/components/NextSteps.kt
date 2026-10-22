package com.nizam.app.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * A chat screen that can take a follow-up message in place provides this, so an "Ask" or "Search" step is sent
 * right there. Anywhere else the step opens the Atlas chat with the message already sent.
 */
val LocalChatAsk = staticCompositionLocalOf<((String) -> Unit)?> { null }

/**
 * Next steps: tappable buttons under an answer that actually do what it suggests.
 * Do → Atlas performs it with its own tools · Open → the right screen · Search → the web · Ask → a follow-up.
 */
object NextSteps {
    enum class Kind(val icon: String, val verb: String) { DO("⚡", "Do"), OPEN("📂", "Open"), SEARCH("🔍", "Search"), ASK("💬", "Ask") }
    data class Step(val label: String, val kind: Kind, val detail: String)

    /** Added to every chat's instructions (inside [RichFormat.GUIDE]). */
    const val GUIDE = " When your answer leads to things the user could do next, end it with a ```next block: a JSON array of 2–4 steps " +
        "[{\"label\":\"Save the budget\",\"kind\":\"do\",\"detail\":\"Create a budget 'Gold launch' of PKR 340,000 for November\"}]. " +
        "label: a short button text (2–6 words, starts with a verb). kind: \"do\" = Atlas performs it with its tools (add a task, reminder, event or alarm; " +
        "log an expense, income, meal, water, workout or prayer; set a budget or goal; save a note; start a timer; create a tracker) and detail must hold every " +
        "value needed (amounts, dates, names); \"open\" = show a screen or tool of Atlas, detail = its name (Tasks, Calendar, Budget, Prayer, Notes, Arcade…); " +
        "\"search\" = look it up on the web, detail = the query; \"ask\" = a follow-up for you to answer or write, detail = the message as the user would send it. " +
        "Only steps that truly follow from this answer and that the user will likely want next, never invented figures, none for small talk or a simple fact, " +
        "and never anything about the vault. Do not also list the same steps as plain text buttons."

    private val LANGS = setOf("next", "actions", "steps", "nextsteps")
    fun isNextFence(lang: String) = lang.lowercase() in LANGS

    /** The steps a model wrote in a ```next block (null when it isn't valid). */
    fun fromFence(body: String): List<Step>? = runCatching {
        val arr = JSONArray(body.trim().let { if (it.startsWith("{")) "[$it]" else it })
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val label = o.optString("label").ifBlank { o.optString("title") }.trim()
            val detail = o.optString("detail").ifBlank { o.optString("query").ifBlank { o.optString("screen").ifBlank { o.optString("prompt") } } }.trim().ifBlank { label }
            if (label.isBlank()) null else Step(label, kindOf(o.optString("kind").ifBlank { o.optString("type") }, detail), detail)
        }
    }.getOrNull()

    private fun kindOf(k: String, detail: String) = when (k.lowercase().trim()) {
        "do", "action", "run" -> Kind.DO
        "open", "screen", "navigate", "go" -> Kind.OPEN
        "search", "web", "lookup" -> Kind.SEARCH
        "ask", "chat", "follow", "followup", "write" -> Kind.ASK
        else -> guess(detail)
    }

    /** For a plain "Next steps" list the model wrote without buttons: what each line most likely means. */
    fun guess(text: String): Kind {
        val t = text.lowercase()
        return when {
            Regex("\\b(search|look up|google|find out|research|check the latest|web)\\b").containsMatchIn(t) -> Kind.SEARCH
            Regex("^(open|go to|view|show|see)\\b").containsMatchIn(t) -> Kind.OPEN
            Regex("^(add|log|save|set|create|schedule|remind|book|track|record|start|approve|plan|block|mark)\\b").containsMatchIn(t) -> Kind.DO
            else -> Kind.ASK
        }
    }

    /** Makes the list sensible before it is shown: real screens only, no duplicates, no vault, at most four. */
    fun clean(steps: List<Step>): List<Step> = steps.asSequence()
        .filterNot { Regex("\\b(vault|hidden)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.label + " " + it.detail) }
        .map { s -> s.copy(label = s.label.trim().trimEnd('.', ':').let { if (it.length > 48) it.take(46).trimEnd() + "…" else it }) }
        .map { s -> if (s.kind == Kind.OPEN && screen(s.detail) == null && screen(s.label) == null) s.copy(kind = Kind.ASK, detail = s.label) else s }
        .distinctBy { it.label.lowercase() }
        .take(4).toList()

    /** The route of an Atlas screen or tool with this name, if there is one. */
    fun screen(name: String): String? {
        val want = name.trim().removePrefix("the ").removeSuffix(" screen").removeSuffix(" tool").trim()
        if (want.length < 3) return null
        com.nizam.app.ui.screens.search.DESTINATIONS.firstOrNull { it.name.equals(want, true) }?.let { return it.route }
        val all = com.nizam.app.feature.nx.Nx.all
        val tool = all.firstOrNull { it.title.equals(want, true) || it.id.equals(want, true) }
            ?: com.nizam.app.ui.screens.search.DESTINATIONS.firstOrNull { want.length >= 4 && (it.name.contains(want, true) || want.contains(it.name, true)) }?.let { return it.route }
            ?: all.firstOrNull { want.length >= 4 && it.title.contains(want, true) }
        return tool?.let { com.nizam.app.feature.nx.Nx.route(it.id) }
    }

    /** When the model listed "Next steps" as text but wrote no ```next block, those lines become buttons anyway. */
    fun fromList(lines: List<String>): List<Step> = lines.map { l ->
        val clean = l.replace(Regex("\\*\\*|__|`"), "").replace(Regex("\\s+"), " ").trim()
        val head = clean.substringBefore(" — ").substringBefore(" - ").substringBefore(": ").substringBefore(" (").trim().trimEnd('.')
        val label = (if (head.length in 3..48) head else clean.take(44).trimEnd() + if (clean.length > 44) "…" else "")
        val kind = guess(clean)
        Step(label, kind, if (kind == Kind.SEARCH) clean.replace(Regex("(?i)^(authori[sz]e (a )?(quick )?web search for|search (the web )?for|look up)\\s*"), "").trim('"', '“', '”', ' ') else clean)
    }

    // ── running a step ─────────────────────────────────────────────────────────

    data class Status(val busy: Boolean = false, val ok: Boolean? = null, val note: String = "", val undo: JSONObject? = null, val route: String? = null)

    /** What each tapped step became, kept while Atlas is open so a ✓ stays on the button. */
    val status = mutableStateMapOf<String, Status>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + com.nizam.app.feature.safe.CoroutineSafetyNet.inScope)

    fun key(context: String, s: Step) = "${context.hashCode()}|${s.label}"

    private fun container(c: Context): AppContainer? = (c.applicationContext as? com.nizam.app.NizamApplication)?.container

    private fun go(route: String) { com.nizam.app.ui.screens.search.AtlasNav.go?.invoke(route) }

    /** Sends a message to a chat: this one if it can take it, otherwise the Atlas chat. */
    private fun ask(text: String, here: ((String) -> Unit)?) {
        if (here != null) { here(text); return }
        com.nizam.app.feature.expertise.Expertise.queueAsk(text, send = true)
        go(com.nizam.app.ui.nav.Routes.ASSISTANT)
    }

    fun run(c: Context, s: Step, context: String, here: ((String) -> Unit)?) {
        val k = key(context, s)
        if (status[k]?.busy == true) return
        when (s.kind) {
            Kind.ASK -> { ask(s.detail, here); status[k] = Status(ok = true, note = "Sent") }
            Kind.SEARCH -> { ask("Search the web for: ${s.detail}. Give me the key facts with sources.", here); status[k] = Status(ok = true, note = "Searching…") }
            Kind.OPEN -> {
                val r = screen(s.detail) ?: screen(s.label)
                if (r != null) { go(r); status[k] = Status(ok = true, note = "Opened", route = r) }
                else { ask(s.label, here); status[k] = Status(ok = true, note = "Sent") }
            }
            Kind.DO -> doIt(c, s, context, k)
        }
    }

    private fun doIt(c: Context, s: Step, context: String, k: String) {
        val container = container(c) ?: return
        status[k] = Status(busy = true, note = "Working…")
        scope.launch {
            val r = runCatching {
                withContext(Dispatchers.IO) {
                    val snap = com.nizam.app.feature.agentic.ActionUndo.snap(container)
                    // the quick way first: a plain command Atlas understands without any model
                    val local = com.nizam.app.feature.agent.LocalCommands.parse(s.detail)
                    val (ok, note, pending) = if (local != null) {
                        val res = com.nizam.app.feature.agent.Actions.run(container, local.action, local.args)
                        Triple(res.ok, if (res.ok) local.spoken.ifBlank { res.summary } else res.summary, false)
                    } else {
                        // otherwise the agent works it out with its tools; your tap is the OK for ordinary changes,
                        // anything that speaks for you, spends or deletes still waits in Approvals
                        val instruction = "The user tapped this next step under your earlier answer. Do it now with your tools, then reply in one short line saying exactly what you did " +
                            "(or what is missing). Step: ${s.detail}\n\nYour earlier answer, for context:\n${context.take(1800)}"
                        val out = com.nizam.app.feature.agent.AgentLoop { container }.run(instruction, useWeb = false, autoApprove = false)
                        val did = out.steps.any { it.ok }
                        val waiting = out.pendingApproval.isNotEmpty()
                        Triple(did || waiting, when {
                            waiting -> "Waiting for your OK in Approvals"
                            did -> out.reply.lineSequence().firstOrNull { it.isNotBlank() }?.take(140) ?: out.steps.filter { it.ok }.joinToString(" · ") { it.summary }
                            else -> out.reply.lineSequence().firstOrNull { it.isNotBlank() }?.take(160) ?: "Couldn't do that one"
                        }, waiting)
                    }
                    if (ok && !pending) com.nizam.app.feature.agentic.ActionUndo.record(container, snap, s.label)
                    val undo = if (ok && !pending) com.nizam.app.feature.agentic.ActionUndo.recent(c).firstOrNull { it.optString("label") == s.label && !it.optBoolean("undone") && System.currentTimeMillis() - it.optLong("t") < 60_000 } else null
                    Status(ok = ok, note = note, undo = undo, route = if (pending) com.nizam.app.feature.nx.Nx.route("approvals").takeIf { screen("approvals") != null } else null)
                }
            }.getOrElse { Status(ok = false, note = Feedback.friendly(it)) }
            status[k] = r
            if (r.ok == true) {
                val u = r.undo
                if (u != null) Feedback.undo("✓ ${r.note}") { scope.launch { if (withContext(Dispatchers.IO) { com.nizam.app.feature.agentic.ActionUndo.undo(container, u) }) status[k] = Status(ok = null, note = "Undone") } }
                else Feedback.show("✓ ${r.note}")
            }
        }
    }

    fun undo(c: Context, k: String) {
        val container = container(c) ?: return
        val u = status[k]?.undo ?: return
        scope.launch { if (withContext(Dispatchers.IO) { com.nizam.app.feature.agentic.ActionUndo.undo(container, u) }) status[k] = Status(ok = null, note = "Undone") }
    }
}

/** The "Next steps" card under an answer. */
@Composable
internal fun NextStepsCard(steps: List<NextSteps.Step>, context: String) {
    if (steps.isEmpty()) return
    val c = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val here = LocalChatAsk.current
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(cs.primary.copy(alpha = 0.06f))
        .border(1.dp, cs.primary.copy(alpha = 0.25f), RoundedCornerShape(20.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("NEXT STEPS", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = cs.primary, modifier = Modifier.padding(start = 4.dp))
        steps.forEach { s ->
            val k = NextSteps.key(context, s)
            val st = NextSteps.status[k]
            val done = st?.ok == true
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (done) cs.primary.copy(alpha = 0.14f) else cs.surface.copy(alpha = 0.7f))
                .border(1.dp, if (done) cs.primary.copy(alpha = 0.5f) else cs.outlineVariant.copy(alpha = 0.6f), RoundedCornerShape(14.dp))
                .clickable(enabled = st?.busy != true) {
                    val r = st?.route
                    if (done && r != null) com.nizam.app.ui.screens.search.AtlasNav.go?.invoke(r) else NextSteps.run(c, s, context, here)
                }
                .padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(30.dp).clip(CircleShape).background(cs.primary.copy(alpha = if (done) 0.9f else 0.14f)), contentAlignment = Alignment.Center) {
                        when {
                            st?.busy == true -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            done -> Text("✓", color = cs.onPrimary, fontWeight = FontWeight.Bold)
                            else -> Text(s.kind.icon)
                        }
                    }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(s.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(when {
                            st != null && st.note.isNotBlank() -> st.note
                            else -> s.kind.verb + when (s.kind) { NextSteps.Kind.DO -> " it for me"; NextSteps.Kind.OPEN -> " in Atlas"; NextSteps.Kind.SEARCH -> " the web"; NextSteps.Kind.ASK -> " Atlas" }
                        }, style = MaterialTheme.typography.labelSmall, color = if (st?.ok == false) cs.error else cs.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                    if (st == null || st.ok == false) Text(if (st?.ok == false) "Retry" else "›", style = MaterialTheme.typography.titleMedium, color = cs.primary, modifier = Modifier.padding(start = 6.dp))
                }
                if (done && st?.undo != null) TextButton(onClick = { NextSteps.undo(c, k) }, modifier = Modifier.padding(start = 34.dp)) { Text("Undo") }
            }
        }
    }
}
