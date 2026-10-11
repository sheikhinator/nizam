package com.nizam.app.feature.chief

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** What the screen shows as Chief works, like the steps in a coding session. */
sealed class Step {
    data class Say(val text: String) : Step()
    data class Tool(val name: String, val detail: String, val result: String) : Step()
    data class Cost(val job: Double, val month: Double) : Step()
}

/**
 * Chief's loop: Claude reads and edits a copy of the app's code through a handful of tools, inside a
 * spending limit and a step limit. It never pushes anything: the user approves first.
 */
class ChiefAgent(private val c: Context, private val ws: Workspace, private val onStep: (Step) -> Unit) {
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
""".trimIndent()

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

    private fun msg(role: String, text: String) = JSONObject().put("role", role).put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))

    private fun exec(name: String, input: JSONObject): Pair<String, String> = when (name) {
        "list_dir" -> input.optString("path") to ws.list(input.optString("path"))
        "read_file" -> input.optString("path") to ws.read(input.optString("path"), input.optInt("start_line", 0).takeIf { it > 0 }, input.optInt("end_line", 0).takeIf { it > 0 })
        "search_code" -> input.optString("pattern") to ws.search(input.optString("pattern"), input.optString("path").ifBlank { null })
        "edit_file" -> input.optString("path") to ws.edit(input.optString("path"), input.optString("old_text"), input.optString("new_text"))
        "create_file" -> input.optString("path") to ws.create(input.optString("path"), input.optString("content"))
        else -> name to "Unknown tool."
    }

    /** Keep only the freshest tool results in full; older ones shrink so a long job stays cheap. */
    private fun compact(messages: JSONArray) {
        val lastTwo = (messages.length() - 3).coerceAtLeast(0)
        for (i in 0 until lastTwo) {
            val content = messages.getJSONObject(i).optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val b = content.getJSONObject(j)
                if (b.optString("type") == "tool_result") {
                    val t = b.optString("content")
                    if (t.length > 500) b.put("content", t.take(400) + "\n…(shortened)")
                }
            }
        }
    }

    /** Runs one request. Returns Claude's final summary, or why it stopped. */
    suspend fun run(history: List<Pair<String, String>>, user: String): String {
        val key = ChiefStore.claudeKey(c)
        if (key.isBlank()) return "Add your Anthropic key first."
        val model = ChiefStore.model(c)
        val messages = JSONArray()
        history.takeLast(6).forEach { (role, text) -> messages.put(msg(role, text.take(2000))) }
        messages.put(msg("user", user))
        val tools = tools()
        var job = 0.0
        var summary = ""
        for (step in 0 until MAX_STEPS) {
            if (ChiefStore.overBudget(c)) return "I stopped because this month's spending limit is reached. You can raise it in Chief settings."
            if (job >= ChiefStore.jobCap(c)) return "I stopped because this job reached its spending limit. Say continue if you want me to carry on."
            val reply = Claude.call(key, model, SYSTEM, messages, tools)
            val cost = Claude.cost(model, reply.usage)
            job += cost; ChiefStore.addSpend(c, cost); onStep(Step.Cost(job, ChiefStore.spent(c)))
            messages.put(JSONObject().put("role", "assistant").put("content", reply.content))
            val results = JSONArray()
            var finished = false
            for (i in 0 until reply.content.length()) {
                val b = reply.content.getJSONObject(i)
                when (b.optString("type")) {
                    "text" -> if (b.optString("text").isNotBlank()) onStep(Step.Say(b.optString("text")))
                    "tool_use" -> {
                        val name = b.optString("name"); val input = b.optJSONObject("input") ?: JSONObject()
                        if (name == "finish") {
                            summary = input.optString("summary"); finished = true
                            results.put(JSONObject().put("type", "tool_result").put("tool_use_id", b.optString("id")).put("content", "ok"))
                        } else {
                            val (detail, out) = runCatching { exec(name, input) }.getOrElse { input.optString("path") to "That failed: ${it.message}" }
                            onStep(Step.Tool(name, detail, out))
                            results.put(JSONObject().put("type", "tool_result").put("tool_use_id", b.optString("id")).put("content", out))
                        }
                    }
                }
            }
            if (finished) return summary.ifBlank { "Done." }
            if (results.length() == 0) return reply.content.let { arr -> (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("type") == "text" }.joinToString("\n") { it.optString("text") } }.ifBlank { "Done." }
            messages.put(JSONObject().put("role", "user").put("content", results))
            compact(messages)
        }
        return "I reached the step limit for one job. Here is where I got to; say continue and I will pick up."
    }
}
