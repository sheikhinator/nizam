package com.nizam.app.feature.code

import android.content.Context
import com.nizam.app.AppContainer
import com.nizam.app.feature.linux.LinuxEnv
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Atlas Code's agent: it explores the project, plans, edits files, runs commands in the terminal, tests,
 * commits, opens pull requests and follows CI — asking you first according to the permission mode.
 */
class CodeAgent(private val container: AppContainer, val project: String, private val ui: Ui, private val readOnly: Boolean = false) {

    enum class Mode(val label: String, val blurb: String) {
        ASK("Ask", "Asks before every edit, command and commit"),
        EDITS("Auto-edit", "Edits files freely; asks before commands and commits"),
        PLAN("Plan", "Reads and plans only; nothing changes until you approve the plan"),
        AUTO("Auto", "Does everything without asking (risky commands still ask)")
    }

    /** What the screen shows and answers. */
    interface Ui {
        val mode: Mode
        fun say(text: String)
        fun tool(name: String, title: String, detail: String, ok: Boolean, diffPath: String? = null)
        fun todos(items: JSONArray)
        suspend fun approve(kind: String, title: String, detail: String, diffPath: String? = null, before: String? = null, after: String? = null): Boolean
        suspend fun askUser(question: String): String
        fun status(text: String)
    }

    private val c: Context get() = container.appContext
    private val root: File get() = Workspace.dir(c, project)
    val transcript = mutableListOf<Pair<String, String>>() // role -> text
    @Volatile var stopped = false
    private val allowedThisSession = mutableSetOf<String>()

    private fun rel(p: String): File {
        val clean = p.trim().removePrefix("/root/projects/$project").removePrefix("./").trimStart('/')
        val f = File(root, clean).canonicalFile
        require(f.path.startsWith(root.canonicalPath)) { "That path is outside the project." }
        return f
    }

    private fun memory(): String = listOf("ATLAS.md", "CLAUDE.md", "AGENTS.md", ".atlas/ATLAS.md").mapNotNull { n -> File(root, n).takeIf { it.isFile }?.readText()?.take(8000)?.let { "### $n\n$it" } }.joinToString("\n\n")

    private fun tree(): String {
        val all = Workspace.files(root)
        val shown = if (all.size <= 250) all else all.filter { it.count { ch -> ch == '/' } <= 2 }.take(250)
        return shown.joinToString("\n") + if (all.size > shown.size) "\n… (${all.size} files in total; use glob/grep to find more)" else ""
    }

    private fun system(): String = buildString {
        append("[[code-agent]] You are Atlas Code, an expert software engineer working in the user's project \"$project\" on their phone, like Claude Code. ")
        append("Work autonomously: explore before editing, keep changes minimal and correct, follow the project's existing style, and verify (run tests/builds when you can). ")
        append("Reply with exactly ONE JSON object per turn and nothing else:\n")
        append("{\"say\":\"short note to the user (optional)\",\"tool\":\"<name>\",\"args\":{...}}\n")
        append("When finished: {\"tool\":\"done\",\"args\":{\"summary\":\"what you did, in Markdown\"}}\n\nTools:\n")
        append("- list(path, depth=2): files and folders\n- glob(pattern): files matching e.g. src/**/*.kt\n- grep(pattern, glob=\"\", ignore_case=false): regex search, returns file:line: text\n")
        append("- read(path, start=1, end=400): file with line numbers\n")
        if (!readOnly) append("- write(path, content): create or overwrite a whole file\n- edit(path, old, new, all=false): replace an exact snippet (must match exactly once unless all=true; copy whitespace exactly)\n- delete(path)\n- move(from, to)\n")
        append("- run(command, timeout=120): shell command in the project folder inside Linux (bash/ash). Use for tests, builds, installs, git log, scripts\n")
        append("- web_search(query): look something up (docs, errors, APIs)\n- fetch(url): read a web page or raw file\n")
        if (!readOnly) append("- todo(items:[{\"content\":\"…\",\"status\":\"pending|in_progress|done\"}]): your task list, shown to the user; keep it updated for multi-step work\n")
        append("- task(prompt): give a self-contained research question to a helper that can read but not edit; it returns its findings\n")
        append("- git_status(), git_diff(path=\"\")\n")
        if (!readOnly) append("- commit(message, branch=\"\"): commit all changes and push to GitHub (new branch if given)\n- pull_request(title, body)\n- sync(): bring in new commits from GitHub\n")
        append("- ci_status(): latest GitHub Actions runs\n- ci_log(run_id): failing steps and log tail\n- ask(question): ask the user and wait for the answer\n")
        if (!readOnly) append("- atlas(action, args): use one of Atlas's own actions (tasks, reminders, notes, MCP tools…)\n")
        if (ui.mode == Mode.PLAN && !readOnly) append("\nPLAN MODE: only read and research. Do not write, edit, run commands that change things, or commit. Finish with done whose summary is a clear numbered plan.\n")
        append("\nRules: paths are relative to the project root. Read a file before editing it. Never print secrets. Prefer edit over write for existing files. ")
        append("If a command needs Linux and it isn't installed, tell the user to install it in Terminal. Keep 'say' to one or two sentences.\n")
        val mem = memory(); if (mem.isNotBlank()) append("\nProject memory (follow it):\n$mem\n")
        append("\nProject files:\n${tree().take(9000)}\n")
        Workspace.info(c, project)?.let { i -> if (i.optString("repo").isNotBlank()) append("\nGitHub: ${i.optString("repo")} · branch ${i.optString("branch")}\n") }
    }

    private fun render(): String {
        // keep the request a sensible size: older tool results shrink first
        val parts = transcript.mapIndexed { i, (role, text) ->
            val recent = i >= transcript.size - 10
            val t = if (!recent && role == "result" && text.length > 1500) text.take(1200) + "\n…(trimmed)" else text
            "### ${role.uppercase()}\n$t"
        }
        var s = parts.joinToString("\n\n")
        if (s.length > 90_000) s = "(earlier steps trimmed)\n\n" + s.takeLast(90_000)
        return "$s\n\n### YOU (one JSON object)\n"
    }

    private fun parse(raw: String): JSONObject? {
        val t = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = t.indexOf('{'); if (start < 0) return null
        // the first complete JSON object (strings may contain braces)
        var depth = 0; var inStr = false; var esc = false
        for (i in start until t.length) {
            val ch = t[i]
            if (inStr) { if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false; continue }
            when (ch) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) return runCatching { JSONObject(t.substring(start, i + 1)) }.getOrNull() } }
        }
        return null
    }

    /** One user request, worked through to the end. */
    suspend fun handle(request: String, maxSteps: Int = 60): String {
        stopped = false
        transcript += "user" to request
        var lastRaw = ""
        for (step in 1..maxSteps) {
            if (stopped) { ui.say("Stopped."); return "Stopped." }
            ui.status("Thinking… (step $step)")
            val raw = runCatching {
                com.nizam.app.net.Ai.generate(container.settingsRepository, render(), system = system(), maxOutputTokens = 8000)
            }.getOrElse { e -> ui.say("The AI didn't answer: ${com.nizam.app.ui.components.Feedback.friendly(e)}"); return "error" }
            val call = parse(raw)
            if (call == null) {
                // a plain answer is a final answer
                if (raw.isNotBlank() && raw == lastRaw) break
                lastRaw = raw
                transcript += "assistant" to raw
                ui.say(raw.trim()); return raw
            }
            transcript += "assistant" to call.toString()
            call.optString("say").takeIf { it.isNotBlank() }?.let { ui.say(it) }
            val tool = call.optString("tool")
            val args = call.optJSONObject("args") ?: JSONObject()
            if (tool == "done" || tool.isBlank()) {
                val sum = args.optString("summary").ifBlank { call.optString("say") }
                if (args.optString("summary").isNotBlank()) ui.say(sum)
                return sum
            }
            val result = runCatching { exec(tool, args) }.getOrElse { e -> "ERROR: ${e.message ?: e.javaClass.simpleName}" }
            transcript += "result" to "[$tool] " + result.take(24_000)
        }
        ui.say("I stopped after many steps. Say \"continue\" to keep going.")
        return "limit"
    }

    private fun risky(cmd: String) = Regex("(?i)(rm\\s+-[a-z]*r[a-z]*f?\\s+(/|~|\\*|\\.\\s*$)|mkfs|dd\\s+if=|:\\(\\)\\s*\\{|chmod\\s+-R\\s+777\\s+/|git\\s+push\\s+.*--force|curl[^|]*\\|\\s*(ba)?sh)").containsMatchIn(cmd)

    private suspend fun gate(kind: String, title: String, detail: String, diffPath: String? = null, before: String? = null, after: String? = null, risky: Boolean = false): Boolean {
        val m = ui.mode
        val auto = when (kind) {
            "edit" -> m == Mode.EDITS || m == Mode.AUTO
            "run" -> m == Mode.AUTO && !risky
            "commit" -> m == Mode.AUTO
            else -> m == Mode.AUTO
        } || ("$kind:$title" in allowedThisSession && !risky)
        if (auto) return true
        return ui.approve(kind, title, detail, diffPath, before, after)
    }

    private fun writes(tool: String) = tool in setOf("write", "edit", "delete", "move", "commit", "pull_request", "sync", "atlas", "todo")

    private suspend fun exec(tool: String, a: JSONObject): String = withContext(Dispatchers.IO) {
        if (readOnly && writes(tool)) return@withContext "Not allowed: this helper can only read."
        if (ui.mode == Mode.PLAN && !readOnly && writes(tool) && tool != "todo") return@withContext "Not allowed in Plan mode. Finish with done and a numbered plan; the user will approve it."
        when (tool) {
            "list" -> {
                val d = rel(a.optString("path", "."))
                val depth = a.optInt("depth", 2).coerceIn(1, 5)
                fun walk(f: File, lvl: Int, pre: String, sb: StringBuilder) {
                    f.listFiles().orEmpty().filter { it.name !in setOf(".git", "node_modules", "build", ".gradle") }.sortedWith(compareBy({ !it.isDirectory }, { it.name })).take(300).forEach { ch ->
                        sb.append(pre).append(ch.name).append(if (ch.isDirectory) "/" else "  (${ch.length()} B)").append('\n')
                        if (ch.isDirectory && lvl < depth) walk(ch, lvl + 1, "$pre  ", sb)
                    }
                }
                val sb = StringBuilder(); walk(d, 1, "", sb)
                withContext(Dispatchers.Main) { ui.tool(tool, "Listed ${a.optString("path", ".")}", "", true) }
                sb.toString().ifBlank { "(empty)" }
            }
            "glob" -> {
                val rx = Regex("^" + Workspace.glob(a.optString("pattern")) + "$")
                val hits = Workspace.files(root).filter { rx.matches(it) || rx.matches(it.substringAfterLast('/')) }
                withContext(Dispatchers.Main) { ui.tool(tool, "Found ${hits.size} files for ${a.optString("pattern")}", "", true) }
                hits.take(400).joinToString("\n").ifBlank { "(no matches)" }
            }
            "grep" -> {
                val rx = Regex(a.optString("pattern"), if (a.optBoolean("ignore_case")) setOf(RegexOption.IGNORE_CASE) else emptySet())
                val g = a.optString("glob").takeIf { it.isNotBlank() }?.let { Regex("^" + Workspace.glob(it) + "$") }
                val out = StringBuilder(); var n = 0
                for (p in Workspace.files(root)) {
                    if (g != null && !g.matches(p) && !g.matches(p.substringAfterLast('/'))) continue
                    val f = File(root, p); if (f.length() > 2_000_000) continue
                    val lines = runCatching { f.readLines() }.getOrNull() ?: continue
                    lines.forEachIndexed { i, l -> if (n < 300 && rx.containsMatchIn(l)) { out.append("$p:${i + 1}: ${l.trim().take(220)}\n"); n++ } }
                    if (n >= 300) break
                }
                withContext(Dispatchers.Main) { ui.tool(tool, "Searched for ${a.optString("pattern")} · $n hits", "", true) }
                out.toString().ifBlank { "(no matches)" }
            }
            "read" -> {
                val f = rel(a.optString("path"))
                if (!f.isFile) return@withContext "No such file: ${a.optString("path")}"
                val lines = f.readLines()
                val s = a.optInt("start", 1).coerceAtLeast(1); val e = a.optInt("end", s + 399).coerceAtMost(lines.size)
                withContext(Dispatchers.Main) { ui.tool(tool, "Read ${a.optString("path")}" + if (lines.size > e - s + 1) " (lines $s–$e of ${lines.size})" else "", "", true) }
                (s..e).joinToString("\n") { "%5d  %s".format(it, lines[it - 1]) } + if (e < lines.size) "\n… ${lines.size - e} more lines (read with start=${e + 1})" else ""
            }
            "write" -> {
                val path = a.optString("path"); val f = rel(path)
                val before = if (f.isFile) f.readText() else null; val after = a.optString("content")
                if (!gate("edit", path, if (before == null) "Create $path" else "Rewrite $path", path, before, after)) return@withContext "The user declined this change."
                f.parentFile?.mkdirs(); f.writeText(after); Changes.record(project, path, before, after)
                hook("after_edit", path)
                withContext(Dispatchers.Main) { ui.tool(tool, (if (before == null) "Created " else "Wrote ") + path, Diff.stat(before, after), true, path) }
                "OK, wrote $path (${after.lines().size} lines)"
            }
            "edit" -> {
                val path = a.optString("path"); val f = rel(path)
                if (!f.isFile) return@withContext "No such file: $path. Use write to create it."
                val before = f.readText(); val old = a.optString("old"); val new = a.optString("new")
                if (old.isEmpty()) return@withContext "old must not be empty."
                val count = run { var n = 0; var i = before.indexOf(old); while (i >= 0 && n < 50) { n++; i = before.indexOf(old, i + old.length) }; n }
                if (count == 0) {
                    // forgiving match: same text with different indentation/trailing spaces
                    val hint = before.lines().withIndex().filter { (_, l) -> l.trim().isNotBlank() && old.lines().firstOrNull { it.isNotBlank() }?.trim()?.let { l.trim() == it } == true }.joinToString { "line ${it.index + 1}" }
                    return@withContext "old text not found in $path." + if (hint.isNotBlank()) " Its first line appears at $hint; read the file and copy the snippet exactly." else " Read the file first."
                }
                if (count > 1 && !a.optBoolean("all")) return@withContext "old text appears $count times in $path; add more surrounding lines or set all=true."
                val after = if (a.optBoolean("all")) before.replace(old, new) else before.replaceFirst(old, new)
                if (!gate("edit", path, "Edit $path", path, before, after)) return@withContext "The user declined this change."
                f.writeText(after); Changes.record(project, path, before, after)
                hook("after_edit", path)
                withContext(Dispatchers.Main) { ui.tool(tool, "Edited $path", Diff.stat(before, after), true, path) }
                "OK, edited $path"
            }
            "delete" -> {
                val path = a.optString("path"); val f = rel(path)
                val before = if (f.isFile) f.readText() else null
                if (!gate("edit", path, "Delete $path", path, before, "")) return@withContext "The user declined."
                f.deleteRecursively(); Changes.record(project, path, before, null)
                withContext(Dispatchers.Main) { ui.tool(tool, "Deleted $path", "", true) }
                "OK, deleted $path"
            }
            "move" -> {
                val from = rel(a.optString("from")); val to = rel(a.optString("to"))
                if (!gate("edit", a.optString("from"), "Move ${a.optString("from")} → ${a.optString("to")}")) return@withContext "The user declined."
                to.parentFile?.mkdirs(); if (!from.renameTo(to)) return@withContext "Couldn't move it."
                withContext(Dispatchers.Main) { ui.tool(tool, "Moved ${a.optString("from")} → ${a.optString("to")}", "", true) }
                "OK"
            }
            "run" -> {
                val cmd = a.optString("command")
                val risky = risky(cmd)
                if (!gate("run", cmd, (if (risky) "⚠ This command could delete or overwrite a lot. " else "") + "Run in ${project}:\n$cmd", risky = risky)) return@withContext "The user declined to run it."
                if (LinuxEnv.preferred(c) == null) return@withContext "Linux isn't installed. Ask the user to open Terminal and install Alpine or Ubuntu."
                withContext(Dispatchers.Main) { ui.status("Running: $cmd") }
                val r = LinuxEnv.run(c, cmd, "/root/projects/$project", a.optLong("timeout", 120).coerceIn(5, 1800))
                withContext(Dispatchers.Main) { ui.tool(tool, "$ $cmd", r.out.takeLast(4000), r.code == 0) }
                "exit ${r.code}${if (r.timedOut) " (timed out)" else ""}\n" + r.out.takeLast(16_000)
            }
            "web_search" -> {
                val q = a.optString("query")
                val ans = com.nizam.app.net.Ai.generate(container.settingsRepository, "Search the web and answer precisely for a programmer, with code where useful and source links: $q",
                    system = "[[code-agent]] You are a research assistant.", maxOutputTokens = 1800, useWeb = true)
                withContext(Dispatchers.Main) { ui.tool(tool, "Searched the web: $q", ans.take(600), true) }
                ans
            }
            "fetch" -> {
                val url = a.optString("url")
                val text = runCatching { okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "Atlas-Code").build()).execute().use { it.body?.string().orEmpty() } }.getOrElse { "Couldn't fetch: ${it.message}" }
                val clean = if (text.contains("<html", true)) text.replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), "").replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ") else text
                withContext(Dispatchers.Main) { ui.tool(tool, "Read $url", "", true) }
                clean.take(20_000)
            }
            "todo" -> { val items = a.optJSONArray("items") ?: JSONArray(); withContext(Dispatchers.Main) { ui.todos(items) }; "Task list updated." }
            "task" -> {
                withContext(Dispatchers.Main) { ui.tool(tool, "Helper is researching: ${a.optString("prompt").take(80)}", "", true) }
                val helper = CodeAgent(container, project, object : Ui {
                    override val mode = Mode.PLAN
                    override fun say(text: String) {}
                    override fun tool(name: String, title: String, detail: String, ok: Boolean, diffPath: String?) { ui.status("Helper: $title") }
                    override fun todos(items: JSONArray) {}
                    override suspend fun approve(kind: String, title: String, detail: String, diffPath: String?, before: String?, after: String?) = false
                    override suspend fun askUser(question: String) = "Decide yourself."
                    override fun status(text: String) { ui.status("Helper: $text") }
                }, readOnly = true)
                helper.handle(a.optString("prompt"), maxSteps = 15)
            }
            "git_status" -> {
                val ch = Workspace.status(c, project)
                withContext(Dispatchers.Main) { ui.tool(tool, "${ch.size} changed file${if (ch.size == 1) "" else "s"}", ch.joinToString("\n") { "${it.kind}  ${it.path}" }, true) }
                ch.joinToString("\n") { "${it.kind}  ${it.path}" }.ifBlank { "No changes since the last commit." }
            }
            "git_diff" -> {
                val only = a.optString("path")
                Workspace.status(c, project).filter { only.isBlank() || it.path == only }.joinToString("\n\n") { ch ->
                    val now = File(root, ch.path).takeIf { it.isFile }?.readText()
                    "--- ${ch.path} (${ch.kind})\n" + Diff.unified(Workspace.original(c, project, ch.path), now).take(8000)
                }.ifBlank { "No changes." }
            }
            "commit" -> {
                val msg = a.optString("message").ifBlank { "Update from Atlas Code" }; val br = a.optString("branch")
                val ch = Workspace.status(c, project)
                if (!gate("commit", "commit", "Commit and push ${ch.size} file(s)" + (if (br.isNotBlank()) " to new branch $br" else "") + ":\n$msg\n\n" + ch.joinToString("\n") { "${it.kind}  ${it.path}" })) return@withContext "The user declined the commit."
                val sha = Workspace.commit(c, project, msg, br.ifBlank { null })
                withContext(Dispatchers.Main) { ui.tool(tool, "Committed $sha and pushed", msg, true) }
                "Committed $sha and pushed."
            }
            "pull_request" -> {
                if (!gate("commit", "pr", "Open a pull request: ${a.optString("title")}")) return@withContext "The user declined."
                val url = Workspace.pullRequest(c, project, a.optString("title"), a.optString("body"))
                withContext(Dispatchers.Main) { ui.tool(tool, "Opened a pull request", url, true) }
                "Opened $url"
            }
            "sync" -> Workspace.sync(c, project).also { r -> withContext(Dispatchers.Main) { ui.tool(tool, r, "", true) } }
            "ci_status" -> Workspace.runs(c, project).joinToString("\n") { r -> "${r.optLong("id")}  ${r.optString("name")}  ${r.optString("status")}/${r.optString("conclusion")}  ${r.optString("head_sha").take(7)}  ${r.optString("display_title").take(60)}" }
                .ifBlank { "No CI runs found." }.also { s -> withContext(Dispatchers.Main) { ui.tool(tool, "Checked CI", s.take(800), true) } }
            "ci_log" -> Workspace.runLog(c, project, a.optLong("run_id")).also { withContext(Dispatchers.Main) { ui.tool(tool, "Read CI log ${a.optLong("run_id")}", "", true) } }
            "ask" -> ui.askUser(a.optString("question"))
            "atlas" -> {
                val name = a.optString("action"); val args = a.optJSONObject("args") ?: JSONObject()
                if (!gate("atlas", name, "Use Atlas: $name $args")) return@withContext "The user declined."
                val r = com.nizam.app.feature.agent.Actions.run(container, name, args)
                withContext(Dispatchers.Main) { ui.tool(tool, "Atlas · $name", r.summary, r.ok) }
                r.summary
            }
            else -> "Unknown tool $tool. Use one of the listed tools."
        }
    }

    /** .atlas/hooks.json: {"after_edit": "command"} runs after each edit (when Linux is installed). */
    private suspend fun hook(event: String, path: String) {
        val cmd = runCatching { JSONObject(File(root, ".atlas/hooks.json").readText()).optString(event) }.getOrDefault("")
        if (cmd.isBlank() || LinuxEnv.preferred(c) == null) return
        val r = LinuxEnv.run(c, cmd.replace("\$FILE", path), "/root/projects/$project", 60)
        if (r.code != 0) transcript += "result" to "[hook $event] exit ${r.code}\n${r.out.takeLast(3000)}"
    }

    fun record(role: String, text: String) { transcript += role to text }
}

/** Every change the agent makes in a session, for review and undo. */
object Changes {
    data class Edit(val path: String, val before: String?, val after: String?, val at: Long = System.currentTimeMillis())
    private val byProject = HashMap<String, MutableList<Edit>>()
    fun record(project: String, path: String, before: String?, after: String?) = synchronized(byProject) { byProject.getOrPut(project) { mutableListOf() } += Edit(path, before, after) }
    fun of(project: String): List<Edit> = synchronized(byProject) { byProject[project]?.toList().orEmpty() }
    fun clear(project: String) = synchronized(byProject) { byProject.remove(project) }
}

/** Line diffs for the review cards. */
object Diff {
    data class Line(val kind: Char, val text: String, val oldNo: Int, val newNo: Int)

    fun lines(before: String?, after: String?): List<Line> {
        val a = before?.lines() ?: emptyList(); val b = after?.lines() ?: emptyList()
        var p = 0; while (p < a.size && p < b.size && a[p] == b[p]) p++
        var s = 0; while (s < a.size - p && s < b.size - p && a[a.size - 1 - s] == b[b.size - 1 - s]) s++
        val am = a.subList(p, a.size - s); val bm = b.subList(p, b.size - s)
        val out = mutableListOf<Line>()
        (0 until p).forEach { out += Line(' ', a[it], it + 1, it + 1) }
        if (am.size * bm.size <= 2_500_000) {
            val dp = Array(am.size + 1) { IntArray(bm.size + 1) }
            for (i in am.size - 1 downTo 0) for (j in bm.size - 1 downTo 0) dp[i][j] = if (am[i] == bm[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
            var i = 0; var j = 0
            while (i < am.size || j < bm.size) {
                when {
                    i < am.size && j < bm.size && am[i] == bm[j] -> { out += Line(' ', am[i], p + i + 1, p + j + 1); i++; j++ }
                    j < bm.size && (i >= am.size || dp[i][j + 1] >= dp[i + 1][j]) -> { out += Line('+', bm[j], 0, p + j + 1); j++ }
                    else -> { out += Line('-', am[i], p + i + 1, 0); i++ }
                }
            }
        } else { am.forEachIndexed { i, l -> out += Line('-', l, p + i + 1, 0) }; bm.forEachIndexed { j, l -> out += Line('+', l, 0, p + j + 1) } }
        (0 until s).forEach { k -> out += Line(' ', a[a.size - s + k], a.size - s + k + 1, b.size - s + k + 1) }
        return out
    }

    /** Only the changed lines with a little context. */
    fun hunks(before: String?, after: String?, context: Int = 3): List<Line?> {
        val all = lines(before, after)
        val keep = BooleanArray(all.size)
        all.forEachIndexed { i, l -> if (l.kind != ' ') for (k in maxOf(0, i - context)..minOf(all.lastIndex, i + context)) keep[k] = true }
        val out = mutableListOf<Line?>(); var gap = false
        all.forEachIndexed { i, l -> if (keep[i]) { if (gap && out.isNotEmpty()) out += null; out += l; gap = false } else gap = true }
        return out
    }

    fun stat(before: String?, after: String?): String { val l = lines(before, after); return "+${l.count { it.kind == '+' }} −${l.count { it.kind == '-' }}" }

    fun unified(before: String?, after: String?): String = hunks(before, after).joinToString("\n") { it?.let { l -> "${l.kind}${l.text}" } ?: "@@" }
}
