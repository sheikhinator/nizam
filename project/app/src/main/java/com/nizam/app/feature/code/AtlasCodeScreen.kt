package com.nizam.app.feature.code

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.feature.linux.LinuxEnv
import com.nizam.app.feature.next.JsonStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private val INK = Color(0xFF0D1117)
private val PANEL = Color(0xFF161B22)
private val LINE = Color(0xFF30363D)
private val TEXT = Color(0xFFE6EDF3)
private val MUTED = Color(0xFF8B949E)
private val ACCENT = Color(0xFFD97757)
private val GREEN = Color(0xFF3FB950)
private val RED = Color(0xFFF85149)

/** One coding conversation, kept while Atlas runs and saved to the chat history. */
class CodeSession(val id: String, val project: String) {
    sealed class Entry {
        data class User(val text: String) : Entry()
        data class Say(val text: String) : Entry()
        data class Tool(val name: String, val title: String, val detail: String, val ok: Boolean, val path: String?) : Entry()
        class Ask(val kind: String, val title: String, val detail: String, val path: String?, val before: String?, val after: String?) : Entry() {
            var answer by mutableStateOf<Boolean?>(null)
            val reply = CompletableDeferred<Boolean>()
        }
        class Question(val text: String) : Entry() { val reply = CompletableDeferred<String>(); var answered by mutableStateOf(false) }
    }
    val entries = mutableStateListOf<Entry>()
    var todos by mutableStateOf(JSONArray())
    var status by mutableStateOf("")
    var running by mutableStateOf(false)
    var mode by mutableStateOf(CodeAgent.Mode.ASK)
    var job: Job? = null
    var agent: CodeAgent? = null
    var title by mutableStateOf("")
}

object AtlasCode {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + com.nizam.app.feature.safe.CoroutineSafetyNet.inScope)
    val sessions = HashMap<String, CodeSession>()
    var open by mutableStateOf<CodeSession?>(null)
    /** A session to reopen from the chat drawer. */
    @Volatile var pending: String? = null

    private fun store(c: Context) = JsonStore(c, "code_sessions", sealed = true)

    fun start(c: Context, container: AppContainer, project: String): CodeSession {
        Workspace.touch(c, project)
        val s = CodeSession("code${System.currentTimeMillis()}", project)
        s.mode = runCatching { CodeAgent.Mode.valueOf(c.getSharedPreferences("atlascode", 0).getString("mode", "ASK")!!) }.getOrDefault(CodeAgent.Mode.ASK)
        attach(container, s); sessions[s.id] = s; open = s
        return s
    }

    fun restore(c: Context, container: AppContainer, id: String): CodeSession? {
        sessions[id]?.let { open = it; return it }
        val o = runCatching { store(c).all().firstOrNull { it.optString("id") == id } }.getOrNull() ?: return null
        val s = CodeSession(id, o.optString("project")); s.title = o.optString("title")
        val e = o.optJSONArray("entries") ?: JSONArray()
        for (i in 0 until e.length()) e.optJSONObject(i)?.let { x -> when (x.optString("k")) {
            "u" -> s.entries += CodeSession.Entry.User(x.optString("t"))
            "s" -> s.entries += CodeSession.Entry.Say(x.optString("t"))
            "t" -> s.entries += CodeSession.Entry.Tool(x.optString("n"), x.optString("t"), x.optString("d"), x.optBoolean("ok", true), x.optString("p").ifBlank { null })
        } }
        attach(container, s)
        val tr = o.optJSONArray("transcript") ?: JSONArray()
        for (i in 0 until tr.length()) tr.optJSONObject(i)?.let { s.agent?.record(it.optString("r"), it.optString("t")) }
        sessions[id] = s; open = s
        return s
    }

    private fun attach(container: AppContainer, s: CodeSession) {
        s.agent = CodeAgent(container, s.project, object : CodeAgent.Ui {
            override val mode get() = s.mode
            override fun say(text: String) { s.entries += CodeSession.Entry.Say(text) }
            override fun tool(name: String, title: String, detail: String, ok: Boolean, diffPath: String?) { s.entries += CodeSession.Entry.Tool(name, title, detail, ok, diffPath) }
            override fun todos(items: JSONArray) { s.todos = items }
            override suspend fun approve(kind: String, title: String, detail: String, diffPath: String?, before: String?, after: String?): Boolean {
                val a = CodeSession.Entry.Ask(kind, title, detail, diffPath, before, after)
                withContext(Dispatchers.Main) { s.entries += a; s.status = "Waiting for you" }
                return a.reply.await()
            }
            override suspend fun askUser(question: String): String {
                val q = CodeSession.Entry.Question(question)
                withContext(Dispatchers.Main) { s.entries += q; s.status = "Waiting for your answer" }
                return q.reply.await()
            }
            override fun status(text: String) { s.status = text }
        })
    }

    fun send(c: Context, s: CodeSession, text: String) {
        if (s.running || text.isBlank()) return
        val prompt = Slash.expand(c, s, text) ?: return
        s.entries += CodeSession.Entry.User(text)
        if (s.title.isBlank()) s.title = text.take(60)
        s.running = true
        s.job = scope.launch {
            runCatching { s.agent?.handle(prompt) }
            s.running = false; s.status = ""
            save(c, s)
        }
    }

    fun stop(s: CodeSession) {
        s.agent?.stopped = true
        s.entries.filterIsInstance<CodeSession.Entry.Ask>().filter { it.answer == null }.forEach { it.answer = false; it.reply.complete(false) }
        s.entries.filterIsInstance<CodeSession.Entry.Question>().filter { !it.answered }.forEach { it.answered = true; it.reply.complete("Stop here.") }
        s.job?.cancel(); s.running = false; s.status = ""
    }

    fun save(c: Context, s: CodeSession) = runCatching {
        val e = JSONArray()
        s.entries.takeLast(400).forEach { x -> when (x) {
            is CodeSession.Entry.User -> e.put(JSONObject().put("k", "u").put("t", x.text))
            is CodeSession.Entry.Say -> e.put(JSONObject().put("k", "s").put("t", x.text))
            is CodeSession.Entry.Tool -> e.put(JSONObject().put("k", "t").put("n", x.name).put("t", x.title).put("d", x.detail.take(4000)).put("ok", x.ok).put("p", x.path ?: ""))
            else -> {}
        } }
        val tr = JSONArray(); s.agent?.transcript?.takeLast(80)?.forEach { (r, t) -> tr.put(JSONObject().put("r", r).put("t", t.take(12_000))) }
        val o = JSONObject().put("id", s.id).put("project", s.project).put("title", s.title).put("updated", System.currentTimeMillis()).put("entries", e).put("transcript", tr)
        val st = store(c)
        if (st.all().any { it.optString("id") == s.id }) st.update(s.id) { old -> o.keys().forEach { k -> old.put(k, o.get(k)) } } else st.add(o)
        val msgs = s.entries.mapNotNull { when (it) { is CodeSession.Entry.User -> true to it.text; is CodeSession.Entry.Say -> false to it.text; else -> null } }
        com.nizam.app.feature.chats.ChatHistory.syncCode(c, s.id, s.project, msgs)
    }
}

/** Slash commands: built in, plus your own in .atlas/commands/<name>.md */
object Slash {
    val BUILT_IN = listOf(
        "/init" to "Study this project and write ATLAS.md (what it is, how to build, test and run it, conventions)",
        "/plan" to "Switch to Plan mode for the next request",
        "/review" to "Review the uncommitted changes for bugs, style and risks",
        "/test" to "Find and run the project's tests, then fix failures",
        "/commit" to "Write a good commit message and commit + push",
        "/pr" to "Commit to a new branch and open a pull request",
        "/ci" to "Check the latest CI run and fix it if it failed",
        "/explain" to "Explain how this project is put together",
        "/clear" to "Start a fresh session",
        "/help" to "What Atlas Code can do")

    fun custom(c: Context, project: String): List<Pair<String, String>> = File(Workspace.dir(c, project), ".atlas/commands").listFiles().orEmpty()
        .filter { it.name.endsWith(".md") }.map { "/" + it.nameWithoutExtension to it.readText().lineSequence().firstOrNull().orEmpty().take(80) }

    /** The request to give the agent, or null when the command was handled here. */
    fun expand(c: Context, s: CodeSession, text: String): String? {
        val t = text.trim()
        if (!t.startsWith("/")) return t
        val cmd = t.substringBefore(' '); val rest = t.substringAfter(' ', "")
        return when (cmd) {
            "/init" -> "Explore this project thoroughly, then create or update ATLAS.md at the root: what the project is, its structure, how to build/test/run it, coding conventions and gotchas. Keep it concise. $rest"
            "/plan" -> { s.mode = CodeAgent.Mode.PLAN; if (rest.isBlank()) { s.entries += CodeSession.Entry.Say("Plan mode is on: I'll read and plan, and change nothing until you approve."); null } else rest }
            "/review" -> "Review the uncommitted changes (git_diff) like a senior engineer: bugs, edge cases, security, style. Don't change anything; give findings ranked by severity. $rest"
            "/test" -> "Find how this project runs its tests, run them, and fix any failures (then run again). $rest"
            "/commit" -> "Look at git_diff, write a clear conventional commit message, and commit + push. $rest"
            "/pr" -> "Commit the current changes to a new descriptively named branch and open a pull request with a good title and description. $rest"
            "/ci" -> "Check ci_status. If the latest run failed, read its ci_log, fix the cause, and commit the fix. $rest"
            "/explain" -> "Explain how this project is put together: main parts, data flow, where to start reading. $rest"
            "/clear" -> { AtlasCode.stop(s); AtlasCode.open = null; null }
            "/help" -> { s.entries += CodeSession.Entry.Say(BUILT_IN.joinToString("\n") { "**${it.first}** — ${it.second}" } + "\n\nModes: Ask · Auto-edit · Plan · Auto. Tools: read, search, edit, run in Linux, web, git commit/push, pull requests, CI, helpers."); null }
            else -> File(Workspace.dir(c, s.project), ".atlas/commands/${cmd.removePrefix("/")}.md").takeIf { it.isFile }?.readText()?.replace("\$ARGUMENTS", rest) ?: t
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AtlasCodeScreen(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { AtlasCode.pending?.let { AtlasCode.pending = null; AtlasCode.restore(c, container, it) } } }
    androidx.compose.material3.MaterialTheme(colorScheme = androidx.compose.material3.darkColorScheme(primary = ACCENT, background = INK, surface = PANEL, onSurface = TEXT, onSurfaceVariant = MUTED, outlineVariant = LINE)) {
        Box(Modifier.fillMaxSize().background(INK).statusBarsPadding()) {
            val s = AtlasCode.open
            if (s == null) Home(container, open) else SessionView(container, s, open)
        }
    }
}

// ── home: projects, repos and recent sessions ─────────────────────────────────────────
@Composable
private fun Home(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var tick by remember { mutableIntStateOf(0) }
    val local = remember(tick) { Workspace.local(c) }
    var repos by remember { mutableStateOf<List<JSONObject>?>(null) }
    var busy by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var showRepos by remember { mutableStateOf(false) }
    var newRepo by remember { mutableStateOf(false) }
    var tokenOpen by remember { mutableStateOf(Workspace.token(c).isBlank()) }
    var token by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    val recent = remember(tick) { runCatching { JsonStore(c, "code_sessions", sealed = true).all().sortedByDescending { it.optLong("updated") }.take(8) }.getOrDefault(emptyList()) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✳", color = ACCENT, fontSize = 28.sp, modifier = Modifier.padding(end = 10.dp))
            Column(Modifier.weight(1f)) {
                Text("Atlas Code", color = TEXT, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black)
                Text("Your coding agent: any repo, any language", color = MUTED, style = MaterialTheme.typography.bodySmall)
            }
            Text("❯_", color = GREEN, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(PANEL).clickable { open(com.nizam.app.feature.nx.Nx.route("terminal")) }.padding(10.dp))
        }
        if (LinuxEnv.preferred(c) == null) Card {
            Text("Linux isn't installed yet", color = TEXT, fontWeight = FontWeight.SemiBold)
            Text("Atlas Code can read, edit, commit and follow CI without it; install Alpine or Ubuntu in Terminal to run tests, scripts and builds on the phone.", color = MUTED, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { open(com.nizam.app.feature.nx.Nx.route("terminal")) }) { Text("Open Terminal") }
        }
        if (tokenOpen) Card {
            Text("Connect GitHub", color = TEXT, fontWeight = FontWeight.SemiBold)
            Text("A token lets Atlas Code download your repos, push commits and open pull requests. GitHub › Settings › Developer settings › Fine-grained tokens: pick the repos, give Contents, Pull requests and Actions read & write. Stored encrypted on this phone.",
                color = MUTED, style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(token, { token = it }, placeholder = { Text("github_pat_…") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation())
            Row { TextButton(enabled = token.isNotBlank(), onClick = { Workspace.setToken(c, token); token = ""; scope.launch { error = runCatching { "Connected as @" + Workspace.me(c) }.getOrElse { "Token didn't work: ${it.message}" }; tokenOpen = error.startsWith("Token") } }) { Text("Save") }
                if (Workspace.token(c).isNotBlank()) TextButton(onClick = { tokenOpen = false }) { Text("Cancel") } }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { showRepos = true; scope.launch { error = ""; repos = runCatching { Workspace.repos(c) }.getOrElse { error = it.message ?: "Couldn't list repos"; emptyList() } } }, modifier = Modifier.weight(1f)) { Text("Open a repo") }
            OutlinedButton(onClick = { newRepo = true }, modifier = Modifier.weight(1f)) { Text("New project") }
        }
        if (!tokenOpen) TextButton(onClick = { tokenOpen = true }) { Text("GitHub token", color = MUTED) }
        if (busy.isNotBlank()) Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Text("  $busy", color = MUTED) }
        if (error.isNotBlank()) Text(error, color = if (error.startsWith("Connected")) GREEN else RED, style = MaterialTheme.typography.bodySmall)

        if (local.isNotEmpty()) {
            Label("ON THIS PHONE")
            local.forEach { p ->
                val n = p.optString("name")
                val changes = remember(tick, n) { runCatching { Workspace.status(c, n).size }.getOrDefault(0) }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PANEL).border(1.dp, LINE, RoundedCornerShape(14.dp))
                    .clickable { AtlasCode.start(c, container, n) }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("📁 $n", color = TEXT, fontWeight = FontWeight.SemiBold)
                        Text(p.optString("repo").ifBlank { "Local folder" } + (p.optString("branch").takeIf { it.isNotBlank() }?.let { " · $it" } ?: "") + if (changes > 0) " · $changes changed" else "",
                            color = MUTED, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                    Text("›", color = ACCENT, fontSize = 22.sp)
                }
            }
        }
        if (recent.isNotEmpty()) {
            Label("RECENT SESSIONS")
            recent.forEach { o ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { AtlasCode.restore(c, container, o.optString("id")) }.padding(vertical = 8.dp, horizontal = 4.dp)) {
                    Text(o.optString("title").ifBlank { "Session" }, color = TEXT, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${o.optString("project")} · ${com.nizam.app.feature.chats.ChatHistory.bucket(o.optLong("updated"))}", color = MUTED, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }

    if (showRepos) AlertDialog(onDismissRequest = { showRepos = false }, title = { Text("Your GitHub repos") }, text = {
        Column {
            OutlinedTextField(q, { q = it }, placeholder = { Text("Filter, or owner/repo") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            val list = repos
            if (list == null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            Column(Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState())) {
                val shown = list.orEmpty().filter { q.isBlank() || it.optString("full_name").contains(q, true) }
                if (q.contains('/') && shown.none { it.optString("full_name").equals(q, true) }) Text("Open $q", color = ACCENT, modifier = Modifier.fillMaxWidth().clickable {
                    showRepos = false; scope.launch { busy = "Downloading $q…"; runCatching { val n = Workspace.clone(c, q.trim()) { busy = it }; tick++; AtlasCode.start(c, container, n) }.onFailure { error = it.message ?: "Failed" }; busy = "" } }.padding(vertical = 10.dp))
                shown.forEach { r -> Column(Modifier.fillMaxWidth().clickable {
                    showRepos = false; scope.launch { busy = "Downloading ${r.optString("full_name")}…"; runCatching { val n = Workspace.clone(c, r.optString("full_name")) { busy = it }; tick++; AtlasCode.start(c, container, n) }.onFailure { error = it.message ?: "Failed" }; busy = "" }
                }.padding(vertical = 8.dp)) {
                    Text((if (r.optBoolean("private")) "🔒 " else "") + r.optString("full_name"), fontWeight = FontWeight.SemiBold)
                    Text(listOf(r.optString("language"), r.optString("description")).filter { it.isNotBlank() && it != "null" }.joinToString(" · "), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } }
            }
        }
    }, confirmButton = { TextButton(onClick = { showRepos = false }) { Text("Close") } })

    if (newRepo) {
        var name by remember { mutableStateOf("") }; var desc by remember { mutableStateOf("") }; var priv by remember { mutableStateOf(true) }; var github by remember { mutableStateOf(Workspace.token(c).isNotBlank()) }
        AlertDialog(onDismissRequest = { newRepo = false }, title = { Text("New project") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it.replace(Regex("[^A-Za-z0-9._-]"), "-") }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(desc, { desc = it }, label = { Text("What it is (Atlas Code will start from this)") }, minLines = 2)
                Row(verticalAlignment = Alignment.CenterVertically) { Switch(github, { github = it }); Text("  Create it on GitHub") }
                if (github) Row(verticalAlignment = Alignment.CenterVertically) { Switch(priv, { priv = it }); Text("  Private") }
            }
        }, confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
            newRepo = false
            scope.launch {
                busy = "Creating $name…"
                runCatching {
                    val n = if (github) Workspace.createRepo(c, name, desc, priv) else Workspace.createLocal(c, name)
                    tick++
                    val s = AtlasCode.start(c, container, n)
                    if (desc.isNotBlank()) AtlasCode.send(c, s, "Set up this new project: $desc. Create a sensible structure, a README and ATLAS.md, and (for an Android app) a GitHub Actions workflow that builds the APK and publishes it as a release.")
                }.onFailure { error = it.message ?: "Failed" }
                busy = ""
            }
        }) { Text("Create") } }, dismissButton = { TextButton(onClick = { newRepo = false }) { Text("Cancel") } })
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) = Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(PANEL).border(1.dp, LINE, RoundedCornerShape(16.dp)).padding(14.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }

@Composable
private fun Label(t: String) = Text(t, color = MUTED, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp))

// ── a session ─────────────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionView(container: AppContainer, s: CodeSession, open: (String) -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var input by remember(s.id) { mutableStateOf("") }
    var sheet by remember { mutableStateOf<String?>(null) }
    var modeMenu by remember { mutableStateOf(false) }
    var diffFor by remember { mutableStateOf<Pair<String?, Pair<String?, String?>>?>(null) }
    val list = rememberLazyListState()
    val info = remember(s.project, sheet) { Workspace.info(c, s.project) }
    val changed = remember(s.entries.size, sheet) { runCatching { Workspace.status(c, s.project).size }.getOrDefault(0) }
    LaunchedEffect(s.entries.size) { if (s.entries.isNotEmpty()) runCatching { list.animateScrollToItem(s.entries.size) } }

    Column(Modifier.fillMaxSize().imePadding()) {
        // header
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", color = TEXT, fontSize = 26.sp, modifier = Modifier.clip(CircleShape).clickable { AtlasCode.save(c, s); AtlasCode.open = null }.padding(horizontal = 10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.project, color = TEXT, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text((info?.optString("repo")?.ifBlank { null } ?: "local") + (info?.optString("branch")?.takeIf { it.isNotBlank() }?.let { " · ⎇ $it" } ?: ""),
                    color = MUTED, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1)
            }
            Box {
                Text(s.mode.label + " ▾", color = if (s.mode == CodeAgent.Mode.AUTO) RED else ACCENT, style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.clip(CircleShape).border(1.dp, LINE, CircleShape).clickable { modeMenu = true }.padding(horizontal = 12.dp, vertical = 6.dp))
                DropdownMenu(modeMenu, { modeMenu = false }) {
                    CodeAgent.Mode.values().forEach { m -> DropdownMenuItem(text = { Column { Text(m.label, fontWeight = FontWeight.SemiBold); Text(m.blurb, style = MaterialTheme.typography.labelSmall) } },
                        onClick = { s.mode = m; c.getSharedPreferences("atlascode", 0).edit().putString("mode", m.name).apply(); modeMenu = false }) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("files" to "Files", "changes" to "Changes${if (changed > 0) " · $changed" else ""}", "ci" to "CI", "terminal" to "Terminal").forEach { (k, l) ->
                Text(l, color = if (k == "changes" && changed > 0) ACCENT else TEXT, style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(PANEL).border(1.dp, LINE, RoundedCornerShape(8.dp))
                        .clickable { if (k == "terminal") { com.nizam.app.feature.linux.Terminals.nextCwd = "/root/projects/${s.project}"; com.nizam.app.feature.linux.Terminals.open(c, LinuxEnv.preferred(c)); open(com.nizam.app.feature.nx.Nx.route("terminal")) } else sheet = k }
                        .padding(horizontal = 10.dp, vertical = 6.dp))
            }
        }
        // the task list
        if (s.todos.length() > 0) Column(Modifier.fillMaxWidth().padding(10.dp).clip(RoundedCornerShape(12.dp)).background(PANEL).padding(10.dp)) {
            for (i in 0 until s.todos.length()) s.todos.optJSONObject(i)?.let { t ->
                val st = t.optString("status")
                Text((when (st) { "done", "completed" -> "☑ "; "in_progress" -> "◐ "; else -> "☐ " }) + t.optString("content"),
                    color = if (st == "done" || st == "completed") MUTED else if (st == "in_progress") ACCENT else TEXT, style = MaterialTheme.typography.bodySmall)
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (s.entries.isEmpty()) item("hello") {
                Column(Modifier.padding(vertical = 30.dp)) {
                    Text("What should we build?", color = TEXT, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Ask for a feature, a fix, a refactor, tests, or an explanation. Type / for commands.", color = MUTED, style = MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("/init", "/explain", "/test", "/review", "/ci").forEach { cmd -> Text(cmd, color = ACCENT, fontFamily = FontFamily.Monospace,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).border(1.dp, LINE, RoundedCornerShape(8.dp)).clickable { AtlasCode.send(c, s, cmd) }.padding(horizontal = 10.dp, vertical = 6.dp)) }
                    }
                }
            }
            itemsIndexed(s.entries) { _, e -> EntryView(c, s, e) { path, before, after -> diffFor = path to (before to after) } }
            item("end") { Spacer(Modifier.height(8.dp)) }
        }
        if (s.running || s.status.isNotBlank()) Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (s.running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = ACCENT)
            Text("  " + s.status.ifBlank { "Working…" }, color = MUTED, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (s.running) Text("■ Stop", color = RED, style = MaterialTheme.typography.labelMedium, modifier = Modifier.clickable { AtlasCode.stop(s) }.padding(6.dp))
        }
        // slash suggestions
        if (input.startsWith("/") && !input.contains(' ')) Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).clip(RoundedCornerShape(12.dp)).background(PANEL).heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            (Slash.BUILT_IN + Slash.custom(c, s.project)).filter { it.first.startsWith(input) }.forEach { (cmd, what) ->
                Row(Modifier.fillMaxWidth().clickable { input = "$cmd " }.padding(10.dp)) {
                    Text(cmd, color = ACCENT, fontFamily = FontFamily.Monospace, modifier = Modifier.width(90.dp)); Text(what, color = MUTED, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(input, { input = it }, placeholder = { Text("Ask Atlas Code… ( / for commands)") }, modifier = Modifier.weight(1f).heightIn(max = 200.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = TEXT), shape = RoundedCornerShape(16.dp))
            Box(Modifier.padding(start = 8.dp).size(48.dp).clip(CircleShape).background(if (input.isBlank() || s.running) PANEL else ACCENT).clickable(enabled = input.isNotBlank() && !s.running) {
                val t = input; input = ""; AtlasCode.send(c, s, t)
            }, contentAlignment = Alignment.Center) { Text("↑", color = if (input.isBlank() || s.running) MUTED else Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Bold) }
        }
    }

    diffFor?.let { (path, ba) -> ModalBottomSheet(onDismissRequest = { diffFor = null }, containerColor = INK) { DiffView(path ?: "", ba.first, ba.second) } }
    when (sheet) {
        "files" -> ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = INK) { FilesSheet(s.project) }
        "changes" -> ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = INK) { ChangesSheet(container, s) { sheet = null } }
        "ci" -> ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = INK) { CiSheet(s) { sheet = null } }
    }
}

@Composable
private fun EntryView(c: Context, s: CodeSession, e: CodeSession.Entry, showDiff: (String?, String?, String?) -> Unit) {
    when (e) {
        is CodeSession.Entry.User -> Row(Modifier.fillMaxWidth()) {
            Text("❯ ", color = ACCENT, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            Text(e.text, color = TEXT, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        }
        is CodeSession.Entry.Say -> SelectionContainer { androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides TEXT) { com.nizam.app.ui.components.RichText(e.text) } }
        is CodeSession.Entry.Tool -> {
            var openD by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PANEL).clickable {
                if (e.path != null) Changes.of(s.project).lastOrNull { it.path == e.path }?.let { showDiff(e.path, it.before, it.after) } ?: run { openD = !openD } else openD = !openD
            }.padding(horizontal = 10.dp, vertical = 7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (e.ok) "●" else "✕", color = if (e.ok) GREEN else RED, fontSize = 10.sp)
                    Text("  " + e.title, color = TEXT, style = MaterialTheme.typography.labelMedium, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (e.name in setOf("write", "edit") && e.detail.isNotBlank()) Text(e.detail, color = MUTED, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
                if (openD && e.detail.isNotBlank() && e.name !in setOf("write", "edit")) SelectionContainer {
                    Text(e.detail.takeLast(4000), color = MUTED, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()))
                }
            }
        }
        is CodeSession.Entry.Ask -> Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PANEL).border(1.dp, if (e.answer == null) ACCENT else LINE, RoundedCornerShape(14.dp)).padding(12.dp)) {
            Text(when (e.kind) { "edit" -> "Allow this change?"; "run" -> "Run this command?"; "commit" -> "Commit and push?"; else -> "Allow?" }, color = TEXT, fontWeight = FontWeight.SemiBold)
            Text(e.detail.take(1500), color = MUTED, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            if (e.kind == "edit" && (e.before != null || e.after != null)) {
                val h = remember(e) { Diff.hunks(e.before, e.after).take(60) }
                Column(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(INK).padding(6.dp).horizontalScroll(rememberScrollState())) { h.forEach { DiffLine(it) } }
                TextButton(onClick = { showDiff(e.path, e.before, e.after) }) { Text("See full diff") }
            }
            when (e.answer) {
                null -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    Button(onClick = { e.answer = true; e.reply.complete(true) }) { Text("Yes") }
                    OutlinedButton(onClick = { e.answer = true; s.mode = if (e.kind == "edit" && s.mode == CodeAgent.Mode.ASK) CodeAgent.Mode.EDITS else s.mode; e.reply.complete(true) }) { Text(if (e.kind == "edit") "Yes, and auto-edit" else "Yes") }
                    TextButton(onClick = { e.answer = false; e.reply.complete(false) }) { Text("No", color = RED) }
                }
                true -> Text("✓ Allowed", color = GREEN, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                false -> Text("✕ Declined", color = RED, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
            }
        }
        is CodeSession.Entry.Question -> {
            var ans by remember { mutableStateOf("") }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(PANEL).border(1.dp, if (e.answered) LINE else ACCENT, RoundedCornerShape(14.dp)).padding(12.dp)) {
                Text("❓ " + e.text, color = TEXT)
                if (!e.answered) Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(ans, { ans = it }, modifier = Modifier.weight(1f), singleLine = true)
                    TextButton(enabled = ans.isNotBlank(), onClick = { e.answered = true; s.entries += CodeSession.Entry.User(ans); e.reply.complete(ans) }) { Text("Send") }
                }
            }
        }
    }
}

@Composable
private fun DiffLine(l: Diff.Line?) {
    if (l == null) { Text("⋯", color = MUTED, fontFamily = FontFamily.Monospace, fontSize = 11.sp); return }
    val (bg, fg) = when (l.kind) { '+' -> GREEN.copy(alpha = 0.15f) to Color(0xFFAFF5B4); '-' -> RED.copy(alpha = 0.15f) to Color(0xFFFFDCD7); else -> Color.Transparent to MUTED }
    Row(Modifier.background(bg)) {
        Text((if (l.kind == '-') l.oldNo else l.newNo).toString().padStart(4), color = MUTED.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
        Text(" ${l.kind} ${l.text}", color = fg, fontFamily = FontFamily.Monospace, fontSize = 11.sp, softWrap = false)
    }
}

@Composable
private fun DiffView(path: String, before: String?, after: String?) {
    val h = remember(path, before, after) { Diff.hunks(before, after, 4) }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(12.dp)) {
        Text(path, color = TEXT, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(Diff.stat(before, after), color = MUTED, style = MaterialTheme.typography.labelSmall)
        LazyColumn(Modifier.fillMaxSize().padding(top = 8.dp).horizontalScroll(rememberScrollState())) { items(h.size) { DiffLine(h[it]) } }
    }
}

// ── files ──────────────────────────────────────────────────────────────────────────────
@Composable
private fun FilesSheet(project: String) {
    val c = LocalContext.current
    var path by remember { mutableStateOf("") }
    var viewing by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val root = Workspace.dir(c, project)
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(12.dp)) {
        val v = viewing
        if (v == null) {
            Text("/" + path, color = TEXT, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            LazyColumn(Modifier.fillMaxSize()) {
                if (path.isNotEmpty()) item { Text("‹ ..", color = ACCENT, fontFamily = FontFamily.Monospace, modifier = Modifier.fillMaxWidth().clickable { path = path.substringBeforeLast('/', "") }.padding(10.dp)) }
                val kids = File(root, path).listFiles().orEmpty().filter { it.name != ".git" }.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                items(kids.size) { i -> val f = kids[i]
                    Text((if (f.isDirectory) "📁 " else "📄 ") + f.name, color = TEXT, fontFamily = FontFamily.Monospace, fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().clickable { val r = if (path.isEmpty()) f.name else "$path/${f.name}"; if (f.isDirectory) path = r else { viewing = r; text = runCatching { f.readText() }.getOrDefault(""); editing = false } }.padding(10.dp))
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("‹", color = ACCENT, fontSize = 22.sp, modifier = Modifier.clickable { viewing = null }.padding(end = 10.dp))
                Text(v, color = TEXT, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = {
                    if (editing) { val f = File(root, v); val before = runCatching { f.readText() }.getOrNull(); f.writeText(text); Changes.record(project, v, before, text) }
                    editing = !editing }) { Text(if (editing) "Save" else "Edit") }
            }
            if (editing) OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxSize(), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, color = TEXT))
            else {
                val lines = remember(text) { text.lines() }
                SelectionContainer { LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) { items(lines.size) { i ->
                    Row { Text((i + 1).toString().padStart(4), color = MUTED.copy(alpha = 0.5f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        Text("  " + lines[i], color = TEXT, fontFamily = FontFamily.Monospace, fontSize = 12.sp, softWrap = false) }
                } } }
            }
        }
    }
}

// ── changes, commit and pull requests ──────────────────────────────────────────────────
@Composable
private fun ChangesSheet(container: AppContainer, s: CodeSession, close: () -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var tick by remember { mutableIntStateOf(0) }
    val changes = remember(tick) { runCatching { Workspace.status(c, s.project) }.getOrDefault(emptyList()) }
    var openPath by remember { mutableStateOf<String?>(null) }
    var msg by remember { mutableStateOf("") }
    var branch by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val linked = Workspace.info(c, s.project)?.optString("repo")?.isNotBlank() == true
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Changes", color = TEXT, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        if (changes.isEmpty()) Text("Nothing changed since the last commit.", color = MUTED)
        changes.forEach { ch ->
            val now = File(Workspace.dir(c, s.project), ch.path).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
            val was = Workspace.original(c, s.project, ch.path)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(PANEL).clickable { openPath = if (openPath == ch.path) null else ch.path }.padding(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(when (ch.kind) { "added" -> "A"; "deleted" -> "D"; else -> "M" }, color = when (ch.kind) { "added" -> GREEN; "deleted" -> RED; else -> ACCENT }, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text("  " + ch.path, color = TEXT, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Diff.stat(was, now), color = MUTED, fontSize = 11.sp)
                    Text("  ↺", color = RED, modifier = Modifier.clickable { Workspace.revert(c, s.project, ch.path); tick++ }.padding(4.dp))
                }
                if (openPath == ch.path) Column(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState())) { Diff.hunks(was, now).take(400).forEach { DiffLine(it) } }
            }
        }
        if (changes.isNotEmpty() && linked) {
            OutlinedTextField(msg, { msg = it }, label = { Text("Commit message") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            OutlinedTextField(branch, { branch = it.replace(' ', '-') }, label = { Text("New branch (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = { scope.launch { busy = true
                    val m = msg.ifBlank { runCatching { com.nizam.app.net.Ai.generate(container.settingsRepository,
                        "Write a concise conventional commit message (subject line, optional short body) for these changes:\n" + changes.joinToString("\n") { "${it.kind} ${it.path}" } + "\n\n" +
                        changes.take(8).joinToString("\n\n") { ch -> Diff.unified(Workspace.original(c, s.project, ch.path), File(Workspace.dir(c, s.project), ch.path).takeIf { it.isFile }?.readText()).take(1500) },
                        system = "[[code-agent]] Output only the commit message.", maxOutputTokens = 200).trim().trim('`') }.getOrDefault("Update from Atlas Code") }
                    result = runCatching { "✓ Committed " + Workspace.commit(c, s.project, m, branch.ifBlank { null }) + " and pushed" }.getOrElse { "✕ " + (it.message ?: "Failed") }
                    busy = false; tick++ } }) { Text(if (msg.isBlank()) "Commit & push (AI message)" else "Commit & push") }
                OutlinedButton(enabled = !busy, onClick = { close(); AtlasCode.send(c, s, "/pr") }) { Text("Pull request") }
            }
        }
        if (linked) TextButton(enabled = !busy, onClick = { scope.launch { busy = true; result = runCatching { Workspace.sync(c, s.project) }.getOrElse { "✕ " + it.message }; busy = false; tick++ } }) { Text("Sync from GitHub") }
        if (!linked) Text("This folder isn't on GitHub yet. Ask Atlas Code to create a repo for it, or start a project from a repo.", color = MUTED, style = MaterialTheme.typography.bodySmall)
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (result.isNotBlank()) Text(result, color = if (result.startsWith("✓") || result.startsWith("Brought") || result.startsWith("Already")) GREEN else RED)
    }
}

// ── CI and installing builds ───────────────────────────────────────────────────────────
@Composable
private fun CiSheet(s: CodeSession, close: () -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var runs by remember { mutableStateOf<List<JSONObject>?>(null) }
    var log by remember { mutableStateOf<Pair<Long, String>?>(null) }
    var apk by remember { mutableStateOf<Pair<String, String>?>(null) }
    var pct by remember { mutableIntStateOf(-1) }
    var err by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { runs = runCatching { Workspace.runs(c, s.project, 8) }.getOrElse { err = it.message ?: ""; emptyList() }; apk = Workspace.latestApk(c, s.project) } }
    Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).padding(14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("CI · GitHub Actions", color = TEXT, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        apk?.let { (tag, url) -> Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PANEL).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("📦 Latest release $tag", color = TEXT, modifier = Modifier.weight(1f))
            Button(enabled = pct !in 0..99, onClick = { scope.launch { pct = 0
                runCatching { val f = Workspace.downloadAsset(c, url, File(File(c.cacheDir, "update").apply { mkdirs() }, "code-${s.project}.apk")) { pct = it }; pct = 100; com.nizam.app.feature.platform2.Updater.install(c, f) }
                    .onFailure { err = it.message ?: "Download failed"; pct = -1 } } }) { Text(if (pct in 0..99) "$pct%" else "Install") }
        } }
        if (runs == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (err.isNotBlank()) Text(err, color = RED, style = MaterialTheme.typography.bodySmall)
        runs.orEmpty().forEach { r ->
            val concl = r.optString("conclusion").ifBlank { "null" }
            val icon = when { r.optString("status") != "completed" -> "◐"; concl == "success" -> "✓"; concl == "failure" -> "✕"; else -> "•" }
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(PANEL).clickable { scope.launch { log = r.optLong("id") to "Loading…"; log = r.optLong("id") to runCatching { Workspace.runLog(c, s.project, r.optLong("id")) }.getOrElse { it.message ?: "" } } }.padding(12.dp)) {
                Text("$icon  ${r.optString("display_title").take(70)}", color = when (icon) { "✓" -> GREEN; "✕" -> RED; else -> ACCENT }, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text("#${r.optInt("run_number")} · ${r.optString("name")} · ${r.optString("head_sha").take(7)} · ${com.nizam.app.feature.chats.ChatHistory.bucket(java.time.Instant.parse(r.optString("created_at")).toEpochMilli())}",
                    color = MUTED, style = MaterialTheme.typography.labelSmall)
                if (concl == "failure") TextButton(onClick = { close(); AtlasCode.send(c, s, "CI run ${r.optLong("id")} failed. Read its ci_log, find the cause, fix it, and commit the fix.") }) { Text("Fix this failure") }
            }
        }
        log?.let { (id, t) -> Text("Log $id", color = TEXT, fontWeight = FontWeight.Bold)
            SelectionContainer { Text(t.takeLast(8000), color = MUTED, fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.horizontalScroll(rememberScrollState())) } }
    }
}

val CodeSpecs: List<com.nizam.app.feature.nx.Spec> get() = listOf(
    com.nizam.app.feature.nx.ScreenSpec("atlascode", "Atlas Code", "A coding agent like Claude Code: any repo, edits, terminal, git, pull requests and CI",
        androidx.compose.material.icons.Icons.Outlined.Code, com.nizam.app.ui.design.Hub.Work,
        "code coding developer programming github repo git commit pull request ci build app claude code agent ide") { c, o -> AtlasCodeScreen(c, o) },
    com.nizam.app.feature.nx.ScreenSpec("terminal", "Terminal", "A real Linux on your phone (Alpine or Ubuntu), no root",
        androidx.compose.material.icons.Icons.Outlined.Terminal, com.nizam.app.ui.design.Hub.Work,
        "terminal linux shell bash command line console alpine ubuntu termux apt python node git") { _, _ -> com.nizam.app.feature.linux.TerminalScreen() },
)
