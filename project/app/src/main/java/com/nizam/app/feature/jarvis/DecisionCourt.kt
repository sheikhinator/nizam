package com.nizam.app.feature.jarvis

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import com.nizam.app.feature.agent.Snapshot
import com.nizam.app.feature.brain.Calibration
import com.nizam.app.feature.brain.Decision
import com.nizam.app.feature.brain.DecisionStore
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.ChatText
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.components.Thinking
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate

/**
 * Decision court. The case is argued for and against, you're cross-examined on the three things
 * that would actually decide it, and a verdict comes back with a confidence — which is then
 * adjusted by your own track record: if your 80% calls have only come true 55% of the time, the
 * court says so. The verdict goes into your decision journal, and on review day Atlas asks how it
 * turned out, so the calibration sharpens with every decision you make.
 */
object Court {
    fun trackRecord(c: Context): String {
        val bands = Calibration.bands(DecisionStore(c).all())
        return if (bands.isEmpty()) "No scored past decisions yet." else
            "Their past calibration: " + bands.joinToString { "calls at ${it.label} came true ${(it.right * 100).toInt()}% (n=${it.n})" }
    }

    suspend fun hearing(container: AppContainer, decision: String): JSONObject {
        val raw = Ai.generate(container.settingsRepository,
            prompt = "${Snapshot.of(container)}\n${com.nizam.app.feature.mind.Values.forAi(container.appContext)}\n\n" +
                "Decision before the court: \"$decision\"\n\nArgue it properly. JSON only:\n" +
                "{\"for\":[\"3 strongest arguments for, specific to their situation\"],\"against\":[\"3 strongest against\"]," +
                "\"questions\":[\"exactly 3 cross-examination questions whose answers would actually decide this — no generic ones\"]}",
            system = "You are a sharp, fair court: prosecution and defence in one. No fence-sitting, no platitudes.",
            maxOutputTokens = 1100)
        return JSONObject(Ai.cleanJson(raw))
    }

    suspend fun verdict(container: AppContainer, decision: String, hearing: JSONObject, answers: List<Pair<String, String>>): JSONObject {
        val raw = Ai.generate(container.settingsRepository,
            prompt = "${Snapshot.of(container)}\n${com.nizam.app.feature.mind.Values.forAi(container.appContext)}\n" +
                "${trackRecord(container.appContext)}\n\nDecision: \"$decision\"\nArguments: $hearing\n" +
                "Cross-examination:\n" + answers.joinToString("\n") { "Q: ${it.first}\nA: ${it.second}" } +
                "\n\nDeliver the verdict. If their calibration shows overconfidence, lower the confidence and say so. JSON only:\n" +
                "{\"verdict\":\"DO IT | DON'T | DO IT IF … | WAIT UNTIL …\",\"confidence\":0-100,\"reasoning\":\"3-5 sentences, " +
                "tied to their answers\",\"conditions\":\"what must be true for this to work\",\"changeMind\":\"the one piece of evidence " +
                "that should reverse this\",\"expectation\":\"a concrete, checkable prediction of what happens if they follow it\"," +
                "\"reviewInDays\":number between 7 and 90}",
            system = "You are a judge who is honest about uncertainty. You never flatter.", maxOutputTokens = 1100)
        return JSONObject(Ai.cleanJson(raw))
    }

    fun record(c: Context, decision: String, v: JSONObject) {
        val store = DecisionStore(c)
        val review = LocalDate.now().plusDays(v.optInt("reviewInDays", 30).coerceIn(7, 90).toLong())
        store.save(store.all() + Decision(System.currentTimeMillis(), LocalDate.now().toString(),
            "$decision → ${v.optString("verdict")}", v.optString("expectation"), v.optInt("confidence", 60), review.toString()))
        Timeline.log(c, "decision", "Court verdict: ${v.optString("verdict")}", decision.take(120))
    }

    /** Called daily from the background cycle: one nudge per decision that's due for review. */
    fun remindDue(c: Context) {
        val p = c.getSharedPreferences("court", 0)
        DecisionStore(c).due().forEach { d ->
            if (p.getBoolean("asked_${d.id}", false)) return@forEach
            Notifications.post(c, Notifications.CHANNEL_NUDGE, (500 + d.id % 40).toInt(), "How did it turn out?",
                "${d.decision.take(80)} — you expected: ${d.expectation.take(120)}. Score it in Second brain → Decisions.")
            p.edit().putBoolean("asked_${d.id}", true).apply()
        }
    }
}

@Composable
fun DecisionCourtScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberWorkScope()
    var decision by remember { mutableStateOf("") }
    var hearing by remember { mutableStateOf<JSONObject?>(null) }
    val answers = remember { mutableStateListOf<String>() }
    var verdict by remember { mutableStateOf<JSONObject?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Decision court", "Argued both ways, cross-examined, judged — then checked against reality")
        Text(Court.trackRecord(c), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(decision, { decision = it }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), minLines = 2,
            enabled = hearing == null, label = { Text("The decision") },
            placeholder = { Text("Should I take the Dubai offer or stay in Lahore another year?") })
        if (hearing == null) Button(enabled = decision.isNotBlank() && !busy, modifier = Modifier.padding(top = 8.dp), onClick = {
            busy = true
            scope.launch {
                runCatching { Court.hearing(container, decision) }.onSuccess { h ->
                    hearing = h; answers.clear()
                    repeat(h.optJSONArray("questions")?.length() ?: 0) { answers.add("") }
                }.onFailure { Feedback.failed("The hearing", it) }
                busy = false
            }
        }) { Text("Open the hearing") }
        if (busy) Thinking(label = if (hearing == null) "Arguing both sides" else "The court is deliberating")

        hearing?.let { h ->
            fun list(k: String) = h.optJSONArray(k)?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
            Section("FOR"); list("for").forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp)) }
            Section("AGAINST"); list("against").forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 3.dp)) }
            Section("CROSS-EXAMINATION")
            list("questions").forEachIndexed { i, q ->
                Text(q, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
                if (i < answers.size) OutlinedTextField(answers[i], { answers[i] = it }, modifier = Modifier.fillMaxWidth(),
                    enabled = verdict == null, label = { Text("Your answer") })
            }
            if (verdict == null) Button(enabled = !busy && answers.all { it.isNotBlank() }, modifier = Modifier.padding(top = 10.dp), onClick = {
                busy = true
                scope.launch {
                    runCatching { Court.verdict(container, decision, h, list("questions").zip(answers)) }
                        .onSuccess { verdict = it; Court.record(c, decision, it); Feedback.show("Verdict recorded — review date set") }
                        .onFailure { Feedback.failed("The verdict", it) }
                    busy = false
                }
            }) { Text("Deliver the verdict") }
        }

        verdict?.let { v ->
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), modifier = Modifier.padding(vertical = 16.dp))
            Text(v.optString("verdict"), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
            Text("Confidence ${v.optInt("confidence")}% · review in ${v.optInt("reviewInDays", 30)} days",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChatText(v.optString("reasoning"), Modifier.padding(top = 8.dp))
            Section("ONLY IF"); Text(v.optString("conditions"), style = MaterialTheme.typography.bodyMedium)
            Section("WHAT SHOULD CHANGE YOUR MIND"); Text(v.optString("changeMind"), style = MaterialTheme.typography.bodyMedium)
            Section("THE PREDICTION WE'LL CHECK"); Text(v.optString("expectation"), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(modifier = Modifier.padding(top = 14.dp), onClick = {
                decision = ""; hearing = null; verdict = null; answers.clear()
            }) { Text("New case") }
        }
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp, bottom = 2.dp))
