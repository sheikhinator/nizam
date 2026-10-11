package com.nizam.app.feature.council

import android.content.Context
import com.nizam.app.AppContainer
import com.nizam.app.feature.agent.Snapshot
import com.nizam.app.net.Ai
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One message in the council chamber. */
data class CouncilMessage(
    val id: Long,
    val speaker: String,            // "USER" or an advisor id
    val text: String,
    val kind: String = "TURN",      // TURN | VERDICT | SYSTEM
    val stance: String = "",        // FOR | AGAINST | MIXED — how this advisor leans
    val respondsTo: String = "",    // advisor id this turn is answering, if any
    val confidence: String = "",
    val dissent: String = "",
    val nextSteps: List<String> = emptyList(),
    val actions: List<ProposedAction> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

data class ProposedAction(val action: String, val label: String, val args: JSONObject)

enum class CouncilMode { AUTO, FULL, DEEP }

/**
 * Stored as a JSON file rather than a database table. Your database now refuses to wipe itself on
 * schema change — so adding a table would need a hand-written migration, and a subtle mistake there
 * crashes the app on launch. A file carries none of that risk for a single chat history.
 */
class CouncilStore(context: Context) {
    private val file = File(context.filesDir, "council.json")

    fun load(): List<CouncilMessage> = runCatching {
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText())
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            CouncilMessage(
                id = o.optLong("id"),
                speaker = o.optString("speaker"),
                text = o.optString("text"),
                kind = o.optString("kind", "TURN"),
                stance = o.optString("stance"),
                respondsTo = o.optString("respondsTo"),
                confidence = o.optString("confidence"),
                dissent = o.optString("dissent"),
                nextSteps = o.optJSONArray("nextSteps")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                    ?: emptyList(),
                actions = o.optJSONArray("actions")?.let { a ->
                    (0 until a.length()).map {
                        val x = a.getJSONObject(it)
                        ProposedAction(x.optString("action"), x.optString("label"),
                            x.optJSONObject("args") ?: JSONObject())
                    }
                } ?: emptyList(),
                createdAt = o.optLong("createdAt")
            )
        }
    }.getOrElse { emptyList() }

    fun save(messages: List<CouncilMessage>) = runCatching {
        val array = JSONArray()
        messages.takeLast(300).forEach { m ->
            array.put(
                JSONObject()
                    .put("id", m.id).put("speaker", m.speaker).put("text", m.text)
                    .put("kind", m.kind).put("stance", m.stance).put("respondsTo", m.respondsTo)
                    .put("confidence", m.confidence).put("dissent", m.dissent)
                    .put("nextSteps", JSONArray(m.nextSteps))
                    .put("actions", JSONArray().apply {
                        m.actions.forEach {
                            put(JSONObject().put("action", it.action).put("label", it.label).put("args", it.args))
                        }
                    })
                    .put("createdAt", m.createdAt)
            )
        }
        file.writeText(array.toString())
    }

    fun clear() = runCatching { file.delete() }
}

/**
 * Runs a council session.
 *
 * AUTO: one API call. The model routes the question to the advisors it actually concerns — one,
 *       two, or all six — writes their turns, and adds a verdict only when there is a real decision.
 * FULL: one call, every advisor speaks.
 * DEEP: each advisor answers independently in parallel, without seeing the others, then the
 *       Arbiter reads them all. Independence is what makes a council more than one voice, but it
 *       costs seven calls — worth it for decisions that matter, wasteful for quick questions.
 */
class CouncilEngine(private val container: () -> AppContainer) {

    data class Session(val turns: List<CouncilMessage>, val route: List<String>)

    private fun charter(advisors: List<Advisor>): String = advisors.joinToString("\n\n") {
        "${it.id} — ${it.name}, ${it.title}\n  Lens: ${it.lens}\n  Voice: ${it.voice}"
    }

    private suspend fun context(): String {
        val c = container()
        return c.agentMemory.recall() + "\n\nTheir current state:\n" + Snapshot.of(c)
    }

    private fun history(messages: List<CouncilMessage>): String =
        messages.takeLast(12).joinToString("\n") { m ->
            val who = if (m.speaker == "USER") "User" else (Council.byId(m.speaker)?.name ?: m.speaker)
            "$who: ${m.text.take(600)}"
        }

    private val actionGuide = """
        Advisors may PROPOSE actions for the user to approve — never assume they ran. Only propose
        one when it directly serves the advice. Use these:
          create_goal {title, why, deadline}   add_task {title}   log_expense {amount, category, note}
          add_note {title, body}   add_identity {text}   set_alarm {hour, minute, label}
          add_calendar_event {title, date, time, durationMinutes, location}
    """.trimIndent()

    private fun nextId() = System.nanoTime()

    suspend fun convene(
        question: String,
        mode: CouncilMode,
        mentioned: List<String>,
        past: List<CouncilMessage>
    ): Session = withContext(Dispatchers.IO) {
        if (mode == CouncilMode.DEEP) deep(question, mentioned, past) else quick(question, mode, mentioned, past)
    }

    private suspend fun quick(
        question: String, mode: CouncilMode, mentioned: List<String>, past: List<CouncilMessage>
    ): Session {
        val c = container()
        val routing = when {
            mentioned.isNotEmpty() ->
                "The user addressed ${mentioned.joinToString(", ")} directly. ONLY those advisors reply. " +
                    "No verdict unless two or more of them disagree on a decision."
            mode == CouncilMode.FULL ->
                "Every advisor speaks. End with the Arbiter's verdict."
            else -> """
                Route the question. Choose only the advisors it genuinely concerns:
                  - a quick factual or single-domain question → ONE advisor
                  - a question touching two concerns → the TWO most relevant
                  - a real decision with trade-offs → three to six, and a verdict
                Do not pad the council. A money question does not need Noor unless there is an
                ethical dimension. A greeting gets one short reply.
            """.trimIndent()
        }

        val raw = Ai.generate(
            settings = c.settingsRepository,
            prompt = """
                ${context()}

                Recent conversation in the chamber:
                ${history(past)}

                The user now says: $question

                $routing

                Rules for the turns:
                - Each advisor speaks strictly from their own lens. They do not all agree.
                - When an advisor disagrees with another, name them and set "respondsTo".
                - Use the user's real numbers from their state. Never invent data.
                - Each turn is 2-5 sentences. Specific, not generic.
                - "stance" is FOR, AGAINST or MIXED relative to what the user is considering.

                Verdict rules (only when there is a genuine decision):
                - The Arbiter decides; it does not add a seventh opinion.
                - "dissent" names the disagreement it could NOT resolve, and who holds it. If the
                  council agreed suspiciously easily, say what nobody examined.
                - 2-4 "nextSteps", each concrete with a timeframe.

                $actionGuide

                Return ONLY JSON:
                {
                  "route": ["SENTINEL","LEDGER"],
                  "turns": [{"speaker":"SENTINEL","text":"...","stance":"AGAINST","respondsTo":""}],
                  "verdict": {"decision":"...","confidence":"LOW|MEDIUM|HIGH",
                              "dissent":"...","nextSteps":["..."]},
                  "actions": [{"action":"create_goal","label":"Set this as a goal","args":{}}]
                }
                Use "verdict": null when there is no decision to make.
            """.trimIndent(),
            system = "You run a council of advisors for one person. The members are:\n\n" +
                charter(Council.seats) + "\n\n" +
                "ARBITER — the chair. " + Council.ARBITER.lens + "\n\n" +
                "Keep every voice distinct. You return only valid JSON.",
            maxOutputTokens = 6144
        )
        return parse(raw)
    }

    private suspend fun deep(
        question: String, mentioned: List<String>, past: List<CouncilMessage>
    ): Session = coroutineScope {
        val c = container()
        val ctx = context()
        val hist = history(past)
        val panel = if (mentioned.isNotEmpty())
            Council.seats.filter { a -> mentioned.any { it.equals(a.id, true) } }
        else Council.seats

        // Independent answers — no advisor sees another's view. That isolation is the point.
        val answers = panel.map { advisor ->
            async {
                runCatching {
                    val text = Ai.generate(
                        settings = c.settingsRepository,
                        prompt = "$ctx\n\nRecent conversation:\n$hist\n\nThe user asks: $question\n\n" +
                            "Answer ONLY from your lens in 3-6 sentences, using their real numbers. " +
                            "End with one line: STANCE: FOR, AGAINST or MIXED.",
                        system = "You are ${advisor.name}, the ${advisor.title} on a personal council.\n" +
                            "Lens: ${advisor.lens}\nVoice: ${advisor.voice}",
                        maxOutputTokens = 1024
                    )
                    val stance = Regex("STANCE:\\s*(FOR|AGAINST|MIXED)", RegexOption.IGNORE_CASE)
                        .find(text)?.groupValues?.get(1)?.uppercase().orEmpty()
                    CouncilMessage(
                        id = nextId(), speaker = advisor.id,
                        text = text.replace(Regex("\\n?STANCE:.*", RegexOption.IGNORE_CASE), "").trim(),
                        stance = stance
                    )
                }.getOrNull()
            }
        }.awaitAll().filterNotNull()

        if (answers.isEmpty()) throw IllegalStateException("No advisor could answer — check your AI provider.")

        val verdictRaw = Ai.generate(
            settings = c.settingsRepository,
            prompt = """
                $ctx

                The user asked: $question

                Each advisor answered independently, without seeing the others:
                ${answers.joinToString("\n\n") { "${Council.byId(it.speaker)?.name} (${it.stance}): ${it.text}" }}

                As the Arbiter: decide. Where they conflict, say who is right and why. Preserve the
                dissent you cannot resolve. If they agree too neatly, name what none of them examined.

                $actionGuide

                Return ONLY JSON:
                {"decision":"...","confidence":"LOW|MEDIUM|HIGH","dissent":"...",
                 "nextSteps":["..."],"actions":[{"action":"...","label":"...","args":{}}]}
            """.trimIndent(),
            system = "You are the Arbiter, chair of a personal council. ${Council.ARBITER.voice} " +
                "You return only valid JSON.",
            maxOutputTokens = 2048
        )
        val v = runCatching { JSONObject(Ai.cleanJson(verdictRaw)) }.getOrNull()
        val verdict = v?.let {
            CouncilMessage(
                id = nextId(), speaker = "ARBITER", kind = "VERDICT",
                text = it.optString("decision"),
                confidence = it.optString("confidence"),
                dissent = it.optString("dissent"),
                nextSteps = it.optJSONArray("nextSteps")?.let { a -> (0 until a.length()).map { i -> a.optString(i) } }
                    ?: emptyList(),
                actions = parseActions(it.optJSONArray("actions"))
            )
        }
        Session(answers + listOfNotNull(verdict), answers.map { it.speaker })
    }

    private fun parse(raw: String): Session {
        val json = runCatching { JSONObject(Ai.cleanJson(raw)) }.getOrNull()
            ?: return Session(
                listOf(CouncilMessage(nextId(), "ARBITER", raw.take(1500), kind = "TURN")),
                listOf("ARBITER")
            )
        val turns = mutableListOf<CouncilMessage>()
        json.optJSONArray("turns")?.let { array ->
            for (i in 0 until array.length()) {
                val t = array.getJSONObject(i)
                val speaker = t.optString("speaker").uppercase()
                if (Council.byId(speaker) == null) continue
                turns.add(
                    CouncilMessage(
                        id = nextId(), speaker = speaker, text = t.optString("text"),
                        stance = t.optString("stance").uppercase(),
                        respondsTo = t.optString("respondsTo").uppercase()
                    )
                )
            }
        }
        json.optJSONObject("verdict")?.let { v ->
            if (v.optString("decision").isNotBlank()) {
                turns.add(
                    CouncilMessage(
                        id = nextId(), speaker = "ARBITER", kind = "VERDICT",
                        text = v.optString("decision"),
                        confidence = v.optString("confidence").uppercase(),
                        dissent = v.optString("dissent"),
                        nextSteps = v.optJSONArray("nextSteps")?.let { a -> (0 until a.length()).map { a.optString(it) } }
                            ?: emptyList(),
                        actions = parseActions(json.optJSONArray("actions"))
                    )
                )
            }
        }
        // Actions without a verdict attach to the last turn, so a single advisor can still propose one
        if (turns.none { it.kind == "VERDICT" } && turns.isNotEmpty()) {
            val acts = parseActions(json.optJSONArray("actions"))
            if (acts.isNotEmpty()) turns[turns.lastIndex] = turns.last().copy(actions = acts)
        }
        val route = json.optJSONArray("route")?.let { a -> (0 until a.length()).map { a.optString(it).uppercase() } }
            ?: turns.map { it.speaker }
        return Session(turns, route)
    }

    private fun parseActions(array: JSONArray?): List<ProposedAction> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val action = o.optString("action")
            if (action.isBlank()) null
            else ProposedAction(action, o.optString("label", action.replace('_', ' ')),
                o.optJSONObject("args") ?: JSONObject())
        }.take(3)
    }
}
