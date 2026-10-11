package com.nizam.app.feature.chief

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** What the screen shows as Chief works, like the steps in a coding session. */
sealed class Step {
    data class User(val text: String) : Step()
    data class Note(val text: String) : Step()
    data class Say(val text: String) : Step()
    data class Tool(val name: String, val detail: String, val result: String) : Step()
    data class Cost(val job: Double, val month: Double) : Step()
}

/**
 * Chief's loop: Claude reads and edits a copy of the app's code through a handful of tools, inside a
 * spending limit and a step limit. It never pushes anything: the user approves first.
 */
class ChiefAgent(private val c: Context, private val ws: Workspace, private val onStep: (Step) -> Unit,
                 private val onSave: (List<Msg>, Double) -> Unit = { _, _ -> }) {
    companion object {
        private const val MAX_STEPS = 28

        private val SYSTEM = """
You are Claude, working as the chief engineer inside Atlas, one person's personal Android app (package com.nizam.app). The owner is not a programmer: talk in plain words, short and kind. You can read and edit the app's source through tools. You cannot run the compiler, so every edit must be checked by eye: correct imports, types and braces.

How the app is built:
- Kotlin 2.1, Jetpack Compose, Material 3, Gradle. Code is under app/src/main/java/com/nizam/app/ (folders: feature/, ui/, net/, data/, core/).
- Most tools are small "specs" in feature/nx/: AiSpec (a form that asks the AI), LogSpec (a tracker), CalcSpec (a calculator), ChatSpec, LinkSpec, ScreenSpec (hand-built Compose screen). They are grouped in objects like Mega15..Mega22 and joined in feature/nx/Catalog.kt in the Nx.all list. A new tool needs a unique id and title.
- feature/nx/Engine.kt draws the standard screens. feature/nx/Roads.kt is the shared data layer, Noticer.kt the rules that point things out, Mind.kt learns from use.
- Settings for look and comfort: ui/screens/settings/Appearance.kt, ui/design/Comfort.kt, ui/theme/Theme.kt. Today screen: ui/screens/today/TodayScreen.kt.
- AI calls go through net/Ai.kt (Ai.generate). Secrets and keys are never written into code.

Rules:
1. Look before you change: use search_code and read_file. Prefer small edits to existing files over new files.
2. Match the style of the code around you. Do not rename or reformat things you were not asked to change.
3. Never touch build files, workflow files, keys, signing or anything that stores secrets.
4. Anything that sends data out, spends money, deletes data, or messages other people must ask for the owner's consent in the app.
5. Do not remove features. If you are unsure what the owner wants, ask one short question instead of guessing.
6. When finished, call finish with a plain-language summary: what changed, which files, and anything the owner should check on the phone.
7. Tool results may end with "CHECK FOUND PROBLEMS". Fix every one before you finish.
""".trimIndent()

        /** Added for AIs that are less sure at code: smaller steps, fewer mistakes. */
        private val WEAK_ADDENDUM = """

Extra rules for this session (you are working with limited context):
- Make one tool call per turn and keep every edit small.
- Before editing, read the exact lines with read_file and copy old_text exactly, including spaces.
- Never invent a function, class or import name: search_code for it first.
- After an edit, read those lines again to confirm they are correct.
- Start with a two-line plan in plain words, then follow it.""".trimIndent()

        private fun tool(name: String, desc: String, props: JSONObject, required: List<String>) = JSONObject().put("name", name).put("description", desc)
            .put("input_schema", JSONObject().put("type", "object").put("properties", props).put("required", JSONArray(required)))
        private fun str(d: String) = JSONObject().put("type", "string").put("description", d)
        private fun int(d: String) = JSONObject().put("type", "integer").put("description", d)

        fun tools(): JSONArray = JSONArray()
            .put(tool("list_dir", "List a folder in the project.", JSONObject().put("path", str("Folder, relative to the project root. Empty for the root.")), emptyList()))
            .put(tool("read_file", "Read a file with line numbers. Up to about 250 lines at a time.", JSONObject().put("path", str("File path")).put("start_line", int("First line")).put("end_line", int("Last line")), listOf("path")))
            .put(tool("search_code", "Search all source files for a regular expression. Returns up to 40 matching lines.", JSONObject().put("pattern", str("Regex or plain text")).put("path", str("Optional folder to search in")), listOf("pattern")))
            .put(tool("edit_file", "Replace one exact piece of text in a file. old_text must match exactly once.", JSONObject().put("path", str("File path")).put("old_text", str("Exact existing text")).put("new_text", str("Replacement text")), listOf("path", "old_text", "new_text")))
            .put(tool("create_file", "Create a new file, or fully rewrite a small one.", JSONObject().put("path", str("File path")).put("content", str("Full file content")), listOf("path", "content")))
            .put(tool("finish", "Call when done. Give a plain-language summary for the owner.", JSONObject().put("summary", str("What was done and what to check")), listOf("summary")))
            .also { arr -> arr.getJSONObject(arr.length() - 1).put("cache_control", JSONObject().put("type", "ephemeral")) }
    }

    private fun exec(call: Call, small: Boolean): Pair<String, String> {
        val input = call.input
        return when (call.name) {
            "list_dir" -> input.optString("path") to ws.list(input.optString("path"))
            "read_file" -> input.optString("path") to ws.read(input.optString("path"), input.optInt("start_line", 0).takeIf { it > 0 }, input.optInt("end_line", 0).takeIf { it > 0 }, small)
            "search_code" -> input.optString("pattern") to ws.search(input.optString("pattern"), input.optString("path").ifBlank { null })
            "edit_file" -> input.optString("path") to ws.edit(input.optString("path"), input.optString("old_text"), input.optString("new_text"))
            "create_file" -> input.optString("path") to ws.create(input.optString("path"), input.optString("content"))
            else -> call.name to "Unknown tool."
        }
    }

    /** Keep only the freshest tool results in full; older ones shrink so a long job stays cheap. */
    private fun compact(history: MutableList<Msg>, keepFull: Int, limit: Int) {
        for (i in 0 until (history.size - keepFull).coerceAtLeast(0)) {
            val m = history[i]
            if (m is Msg.Results) history[i] = Msg.Results(m.items.map { (id, t) -> id to if (t.length > limit) t.take(limit - 100) + "\n...(shortened)" else t })
        }
    }

    /**
     * Runs the conversation to the end. [history] already holds the request; it is extended in place.
     * It tries the strongest connected AI first and keeps each inside its limits. When one refuses (no credit, rate limit,
     * outage) it rests that one and carries on with the next from the same point.
     */
    suspend fun run(history: MutableList<Msg>, startCost: Double = 0.0): String {
        val brains = Brains.chain(c)
        if (brains.isEmpty()) return "Add your Anthropic key (or connect another AI) first."
        val tools = tools()
        var job = startCost
        var steps = 0
        var failures = 0
        var fixRounds = 0
        var lastBrain = ""
        var usedFallback = false
        while (steps < MAX_STEPS) {
            val brain = brains.firstOrNull { Throttle.ready(it.name) && !(it is ClaudeBrain && (ChiefStore.overBudget(c) || job >= ChiefStore.jobCap(c))) }
            if (brain == null) {
                if (brains.any { it is ClaudeBrain } && (ChiefStore.overBudget(c) || job >= ChiefStore.jobCap(c)) && brains.all { it is ClaudeBrain || !Throttle.ready(it.name) }) {
                    return if (ChiefStore.overBudget(c)) "I stopped because this month's spending limit is reached. You can raise it in Chief settings." else "I stopped because this job reached its spending limit. Say continue if you want me to carry on."
                }
                val wait = Throttle.waitUntil()
                if (wait in 1..45_000L && failures < 14) { onStep(Step.Note("Every connected AI is resting for a moment. Waiting ${wait / 1000 + 1}s.")); kotlinx.coroutines.delay(wait + 500); failures++; continue }
                return "None of your connected AIs could answer right now (limits or no credit). Your progress is saved; try again in a few minutes."
            }
            if (lastBrain.isNotEmpty() && brain.name != lastBrain) { onStep(Step.Note("Continuing with ${brain.name}.")); usedFallback = true }
            lastBrain = brain.name
            val weak = !brain.strong
            val system = SYSTEM + if (weak) WEAK_ADDENDUM else ""
            val turn = try {
                if (brain !is ClaudeBrain) Throttle.pace(brain.name, 20)
                brain.turn(system, history, tools)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                failures++
                ChiefStore.brainResult(c, brain.name, false)
                val msg = e.message.orEmpty()
                val rest = Throttle.restFor(msg)
                Throttle.cool(brain.name, rest)
                val why = when {
                    msg.contains("credit balance", true) -> "its credit is empty"
                    rest >= 3_600_000L -> "its key was refused"
                    rest <= 75_000L -> "it hit a limit"
                    else -> msg.take(80)
                }
                onStep(Step.Note("${brain.name} did not answer ($why)."))
                if (failures >= 14) return "The connected AIs kept failing, so I stopped. Your progress is saved."
                continue
            }
            steps++
            ChiefStore.brainResult(c, brain.name, true)
            job += turn.cost; ChiefStore.addSpend(c, turn.cost); onStep(Step.Cost(job, ChiefStore.spent(c)))
            if (turn.text.isNotBlank()) onStep(Step.Say(turn.text))
            history += Msg.Asst(turn.text, turn.calls)
            if (turn.calls.isEmpty()) { onSave(history, job); return turn.text.ifBlank { "Done." } }
            val results = ArrayList<Pair<String, String>>()
            var summary: String? = null
            for (call in turn.calls) {
                if (call.name == "finish") {
                    // the free pre-check: do not hand over a change with obvious mistakes
                    val problems = ws.allProblems()
                    if (problems.isNotEmpty() && fixRounds < 2) {
                        fixRounds++
                        results += call.id to "Not accepted yet. Fix these first, then call finish again:\n" + problems.take(8).joinToString("\n")
                        onStep(Step.Note("Checking my own work found ${problems.size} problem(s). Fixing them first."))
                    } else { summary = call.input.optString("summary"); results += call.id to "ok" }
                    continue
                }
                val (detail, out) = runCatching { exec(call, weak) }.getOrElse { call.input.optString("path") to "That failed: ${it.message}" }
                onStep(Step.Tool(call.name, detail, out))
                results += call.id to out
            }
            history += Msg.Results(results)
            compact(history, if (weak) 1 else 3, if (weak) 300 else 500)
            onSave(history, job)
            if (summary != null) return (summary.ifBlank { "Done." }) + if (usedFallback || !brain.strong) "\n\n(Part of this was done by ${brain.name}, which is less sure at code. Please read the changes carefully before approving.)" else ""
        }
        return "I reached the step limit for one job. Here is where I got to; say continue and I will pick up."
    }
}
