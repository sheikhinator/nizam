package com.nizam.app.feature.chats

import android.content.Context
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Every conversation, kept: Atlas chats (with or without an expert) and the chats inside Atlas's tools.
 * New chat no longer throws the old one away; it goes here, titled and labelled, ready to reopen.
 * Stored sealed on the phone. The vault is never part of it.
 */
object ChatHistory {
    data class Label(val key: String, val emoji: String, val words: Regex)
    val LABELS = listOf(
        Label("Money", "💰", Regex("(?i)\\b(budget|pkr|rs\\.?|rupee|money|expense|income|salary|invest|price|cost|loan|bank|profit|gold|stock|trading|savings?)\\b")),
        Label("Deen", "🕌", Regex("(?i)\\b(prayer|namaz|salah|quran|hadith|allah|islam|dua|ramadan|fast|zakat|masjid|sunnah)\\b")),
        Label("Health", "💪", Regex("(?i)\\b(health|gym|workout|diet|calorie|protein|sleep|doctor|medicine|pharmacy|weight|run|steps|water)\\b")),
        Label("Work", "💼", Regex("(?i)\\b(work|office|meeting|manager|report|dashboard|kpi|client|email|career|job|interview|maf|carrefour|supply chain)\\b")),
        Label("Code", "💻", Regex("(?i)\\b(code|kotlin|python|javascript|html|css|bug|error|api|function|app|github|build|apk|compile|sql|repo)\\b")),
        Label("Travel", "✈️", Regex("(?i)\\b(travel|trip|flight|hotel|visa|tour|itinerary|holiday)\\b")),
        Label("Study", "📚", Regex("(?i)\\b(study|learn|exam|course|book|explain|lesson|homework|research|essay)\\b")),
        Label("Fun", "🎮", Regex("(?i)\\b(game|movie|music|song|anime|joke|play|fun|story|series)\\b")),
        Label("Home", "🏠", Regex("(?i)\\b(home|house|rent|cook|recipe|clean|grocery|family|kids?)\\b")),
        Label("Ideas", "💡", Regex("(?i)\\b(idea|plan|startup|business|launch|brainstorm|strategy)\\b")),
    )
    fun emoji(label: String) = LABELS.firstOrNull { it.key == label }?.emoji ?: "💬"

    private fun store(c: Context) = JsonStore(c, "chat_history", sealed = true)
    private fun projects(c: Context) = JsonStore(c, "chat_projects", sealed = true)
    private fun prefs(c: Context) = c.getSharedPreferences("chat_history", 0)
    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.nizam.app.feature.safe.CoroutineSafetyNet.inScope)

    /** Bumped whenever anything changes, so the drawer re-reads. */
    private val _tick = MutableStateFlow(0)
    val tick: StateFlow<Int> = _tick
    private fun changed() { _tick.value = _tick.value + 1 }

    // ── reading ──────────────────────────────────────────────────────────────────
    fun all(c: Context): List<JSONObject> = runCatching { store(c).all() }.getOrDefault(emptyList())
    fun get(c: Context, id: String) = all(c).firstOrNull { it.optString("id") == id }
    fun projectList(c: Context): List<JSONObject> = runCatching { projects(c).all().sortedBy { it.optString("name").lowercase() } }.getOrDefault(emptyList())
    fun project(c: Context, id: String) = projectList(c).firstOrNull { it.optString("id") == id }

    /** The Atlas chat open right now (its id, or "" before its first message). */
    fun currentId(c: Context) = prefs(c).getString("current", "").orEmpty()

    /** A project's instructions for the chat that's open, so Atlas answers inside that context. */
    @Volatile var activeProjectBlock: String = ""
    /** True while the Atlas chat is on screen (project context applies only there). */
    @Volatile var atlasOpen: Boolean = false

    private fun refreshActive(c: Context) {
        val p = get(c, currentId(c))?.optString("project").orEmpty().let { if (it.isBlank()) null else project(c, it) }
        activeProjectBlock = if (p == null) "" else buildString {
            append("This conversation belongs to the user's project \"${p.optString("name")}\".")
            p.optString("instructions").takeIf { it.isNotBlank() }?.let { append(" Project instructions from the user: $it") }
            p.optString("notes").takeIf { it.isNotBlank() }?.let { append("\nPinned project notes:\n${it.take(3000)}") }
        }
    }
    fun warm(c: Context) = refreshActive(c)

    // ── Atlas chats ───────────────────────────────────────────────────────────────
    /** Keeps the open Atlas chat saved as it grows. */
    fun syncAtlas(c: Context, turns: List<Pair<Boolean, String>>, agent: String?) {
        if (turns.none { it.first }) return
        var id = currentId(c)
        val st = store(c)
        val now = System.currentTimeMillis()
        val msgs = JSONArray().apply { turns.takeLast(300).forEach { (u, t) -> put(JSONObject().put("u", u).put("t", t.take(20000))) } }
        val existing = if (id.isBlank()) null else st.all().firstOrNull { it.optString("id") == id }
        if (existing == null) {
            id = "c$now"
            prefs(c).edit().putString("current", id).apply()
            val first = turns.first { it.first }.second
            st.add(JSONObject().put("id", id).put("source", "atlas").put("agent", agent.orEmpty()).put("title", quickTitle(first)).put("label", labelFor(turns))
                .put("created", now).put("updated", now).put("msgs", msgs).put("titled", false).put("project", pendingProject ?: "").also { pendingProject = null })
        } else {
            if (existing.optJSONArray("msgs")?.length() == msgs.length()) return
            st.update(id) { o -> o.put("msgs", msgs).put("updated", now).put("archived", false)
                if (!agent.isNullOrBlank()) o.put("agent", agent)
                if (!o.optBoolean("labelSet")) o.put("label", labelFor(turns)) }
        }
        changed(); refreshActive(c)
        maybeTitle(c, id)
    }

    /** "New chat": the open one is already saved; start empty. */
    suspend fun newAtlas(container: AppContainer, inProject: String? = null) {
        val c = container.appContext
        prefs(c).edit().putString("current", "").apply()
        pendingProject = inProject
        container.agentMemory.clearChat()
        activeProjectBlock = inProject?.let { project(c, it) }?.let { "This conversation belongs to the user's project \"${it.optString("name")}\". Project instructions from the user: ${it.optString("instructions")}" }.orEmpty()
        changed()
    }
    @Volatile private var pendingProject: String? = null

    /** Opens a saved Atlas chat to carry on: its messages become the live conversation again. */
    suspend fun openAtlas(container: AppContainer, id: String): Boolean {
        val c = container.appContext
        val o = get(c, id) ?: return false
        container.agentMemory.clearChat()
        val a = o.optJSONArray("msgs") ?: JSONArray()
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { container.agentMemory.addTurn(it.optBoolean("u"), it.optString("t")) }
        prefs(c).edit().putString("current", id).apply()
        store(c).update(id) { it.put("archived", false) }
        changed(); refreshActive(c)
        return true
    }

    // ── chats inside tools ────────────────────────────────────────────────────────
    /** A tool's chat (Oracle, a coach, a story…) is kept too; reopening it brings the conversation back. */
    fun syncTool(c: Context, toolId: String, toolTitle: String, sessionId: String, msgs: List<Pair<Boolean, String>>) {
        if (msgs.none { it.first }) return
        val id = "t_${toolId}_$sessionId"
        val st = store(c); val now = System.currentTimeMillis()
        val arr = JSONArray().apply { msgs.takeLast(200).forEach { (u, t) -> put(JSONObject().put("u", u).put("t", t.take(12000))) } }
        val existing = st.all().firstOrNull { it.optString("id") == id }
        if (existing == null) st.add(JSONObject().put("id", id).put("source", "tool:$toolId").put("agent", toolTitle).put("title", quickTitle(msgs.first { it.first }.second))
            .put("label", labelFor(msgs)).put("created", now).put("updated", now).put("msgs", arr).put("titled", false))
        else if (existing.optJSONArray("msgs")?.length() != arr.length()) st.update(id) { it.put("msgs", arr).put("updated", now) } else return
        changed()
        maybeTitle(c, id)
    }

    /** An Atlas Code session is kept in history too (label Code), and reopens in Atlas Code. */
    fun syncCode(c: Context, sessionId: String, project: String, msgs: List<Pair<Boolean, String>>) {
        if (msgs.none { it.first }) return
        val id = "code_$sessionId"
        val st = store(c); val now = System.currentTimeMillis()
        val arr = JSONArray().apply { msgs.takeLast(200).forEach { (u, t) -> put(JSONObject().put("u", u).put("t", t.take(8000))) } }
        val existing = st.all().firstOrNull { it.optString("id") == id }
        if (existing == null) st.add(JSONObject().put("id", id).put("source", "code:$sessionId").put("agent", "Atlas Code · $project").put("title", quickTitle(msgs.first { it.first }.second))
            .put("label", "Code").put("labelSet", true).put("created", now).put("updated", now).put("msgs", arr).put("titled", false))
        else st.update(id) { it.put("msgs", arr).put("updated", now) }
        changed()
        maybeTitle(c, id)
    }

    private val restore = HashMap<String, List<Pair<Boolean, String>>>()
    /** Hands a saved tool chat to the tool screen that is about to open. */
    fun takeRestore(toolId: String): Pair<String, List<Pair<Boolean, String>>>? = synchronized(restore) {
        val k = restore.keys.firstOrNull { it.startsWith("$toolId|") } ?: return null
        val v = restore.remove(k) ?: return null
        k.substringAfter('|') to v
    }
    fun openTool(c: Context, id: String): String? {
        val o = get(c, id) ?: return null
        val tool = o.optString("source").removePrefix("tool:")
        val a = o.optJSONArray("msgs") ?: JSONArray()
        val msgs = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { it.optBoolean("u") to it.optString("t") }
        synchronized(restore) { restore["$tool|${id.removePrefix("t_${tool}_")}"] = msgs }
        return com.nizam.app.feature.nx.Nx.route(tool)
    }

    // ── editing ───────────────────────────────────────────────────────────────────
    fun rename(c: Context, id: String, title: String) { store(c).update(id) { it.put("title", title.trim().take(80)).put("titled", true).put("renamed", true) }; changed() }
    fun star(c: Context, id: String, on: Boolean) { store(c).update(id) { it.put("starred", on) }; changed() }
    fun archive(c: Context, id: String, on: Boolean) { store(c).update(id) { it.put("archived", on) }; changed() }
    fun setLabel(c: Context, id: String, label: String) { store(c).update(id) { it.put("label", label).put("labelSet", true) }; changed() }
    fun move(c: Context, id: String, projectId: String?) { store(c).update(id) { it.put("project", projectId.orEmpty()) }; changed(); refreshActive(c) }
    fun delete(c: Context, id: String) {
        store(c).remove(id)
        if (currentId(c) == id) prefs(c).edit().putString("current", "").apply()
        changed()
    }

    fun newProject(c: Context, name: String, emoji: String = "📁", instructions: String = ""): String {
        val id = "p${System.currentTimeMillis()}"
        projects(c).add(JSONObject().put("id", id).put("name", name.trim().take(60)).put("emoji", emoji).put("instructions", instructions).put("notes", "").put("created", System.currentTimeMillis()))
        changed(); return id
    }
    fun editProject(c: Context, id: String, name: String, emoji: String, instructions: String, notes: String) {
        projects(c).update(id) { it.put("name", name.trim().take(60)).put("emoji", emoji).put("instructions", instructions).put("notes", notes) }
        changed(); refreshActive(c)
    }
    fun deleteProject(c: Context, id: String) {
        projects(c).remove(id)
        store(c).save(store(c).all().map { if (it.optString("project") == id) it.put("project", "") else it })
        changed(); refreshActive(c)
    }

    // ── searching and grouping ────────────────────────────────────────────────────
    fun matches(o: JSONObject, q: String): Boolean {
        if (q.isBlank()) return true
        val words = q.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        val hay = buildString {
            append(o.optString("title")).append(' ').append(o.optString("agent")).append(' ').append(o.optString("label")).append(' ')
            val a = o.optJSONArray("msgs"); if (a != null) for (i in 0 until a.length()) append(a.optJSONObject(i)?.optString("t").orEmpty()).append(' ')
        }.lowercase()
        return words.all { hay.contains(it) }
    }

    fun lastLine(o: JSONObject): String {
        val a = o.optJSONArray("msgs") ?: return ""
        val t = a.optJSONObject(a.length() - 1)?.optString("t").orEmpty()
        return com.nizam.app.ui.components.RichFormat.plain(t).replace(Regex("```[\\s\\S]*?```"), " ").replace(Regex("[#*_`>|]"), "").replace(Regex("\\s+"), " ").trim().take(120)
    }

    fun bucket(t: Long): String {
        val z = java.time.ZoneId.systemDefault()
        val d = java.time.Instant.ofEpochMilli(t).atZone(z).toLocalDate()
        val today = java.time.LocalDate.now(z)
        return when {
            d == today -> "Today"
            d == today.minusDays(1) -> "Yesterday"
            d.isAfter(today.minusDays(7)) -> "Previous 7 days"
            d.isAfter(today.minusDays(30)) -> "Previous 30 days"
            d.year == today.year -> d.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())
            else -> d.year.toString()
        }
    }

    /** When three or more loose chats clearly share a subject, suggest gathering them into a project. */
    fun suggestion(c: Context): Pair<String, List<String>>? {
        if (prefs(c).getBoolean("noSuggest", false)) return null
        val loose = all(c).filter { it.optString("project").isBlank() && !it.optBoolean("archived") && System.currentTimeMillis() - it.optLong("updated") < 30L * 86_400_000 }
        val stop = setOf("the", "and", "for", "with", "how", "what", "my", "your", "a", "an", "of", "to", "in", "on", "plan", "help", "chat", "about", "new", "me", "is", "it")
        val byWord = HashMap<String, MutableList<String>>()
        loose.forEach { o -> o.optString("title").lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 4 && it !in stop }.toSet()
            .forEach { w -> byWord.getOrPut(w) { mutableListOf() }.add(o.optString("id")) } }
        val dismissed = prefs(c).getStringSet("dismissed", emptySet()).orEmpty()
        return byWord.entries.filter { it.value.size >= 3 && it.key !in dismissed }.maxByOrNull { it.value.size }?.let { it.key.replaceFirstChar { ch -> ch.uppercase() } to it.value }
    }
    fun dismissSuggestion(c: Context, word: String) {
        val s = prefs(c).getStringSet("dismissed", emptySet()).orEmpty() + word.lowercase()
        prefs(c).edit().putStringSet("dismissed", s).apply(); changed()
    }

    // ── titles and labels ─────────────────────────────────────────────────────────
    fun quickTitle(first: String): String {
        val t = first.replace(Regex("\\s+"), " ").trim().trimEnd('?', '.', '!')
        if (t.isBlank()) return "New chat"
        return (if (t.length > 42) t.take(40).substringBeforeLast(' ').ifBlank { t.take(40) } + "…" else t).replaceFirstChar { it.uppercase() }
    }

    fun labelFor(turns: List<Pair<Boolean, String>>): String {
        val text = turns.take(8).joinToString(" ") { it.second.take(800) }
        return LABELS.maxByOrNull { l -> l.words.findAll(text).count() }?.takeIf { l -> l.words.containsMatchIn(text) }?.key ?: "Chat"
    }

    /** After the first answer, Atlas names the chat and picks its label (a short, cheap call; once per chat). */
    private fun maybeTitle(c: Context, id: String) {
        val o = get(c, id) ?: return
        if (o.optBoolean("titled")) return
        val a = o.optJSONArray("msgs") ?: return
        if (a.length() < 2) return
        store(c).update(id) { it.put("titled", true) }
        val convo = (0 until minOf(a.length(), 6)).mapNotNull { a.optJSONObject(it) }.joinToString("\n") { (if (it.optBoolean("u")) "User: " else "Assistant: ") + it.optString("t").take(600) }
        io.launch {
            val container = (c.applicationContext as? com.nizam.app.NizamApplication)?.container ?: return@launch
            val raw = runCatching {
                com.nizam.app.net.Ai.generate(container.settingsRepository,
                    "Name this conversation. Output only JSON {\"title\":\"3 to 6 words, no quotes or emoji\",\"label\":\"one of: ${LABELS.joinToString(", ") { it.key }}, Chat\"}.\n\n$convo",
                    system = "Output only JSON.", maxOutputTokens = 60)
            }.getOrNull() ?: return@launch
            val j = runCatching { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) }.getOrNull() ?: return@launch
            val title = j.optString("title").trim().trim('"').take(60)
            val label = j.optString("label").trim().let { l -> LABELS.firstOrNull { it.key.equals(l, true) }?.key ?: if (l.equals("chat", true)) "Chat" else null }
            store(c).update(id) { o2 ->
                if (title.length >= 3 && !o2.optBoolean("renamed")) o2.put("title", title)
                if (label != null && !o2.optBoolean("labelSet")) o2.put("label", label)
            }
            changed()
        }
    }

    /** Your one conversation from before history existed becomes the first saved chat. */
    suspend fun migrate(container: AppContainer) {
        val c = container.appContext
        if (prefs(c).getBoolean("migrated", false)) return
        prefs(c).edit().putBoolean("migrated", true).apply()
        val turns = runCatching { container.agentMemory.turns().first() }.getOrDefault(emptyList())
        if (turns.isNotEmpty()) syncAtlas(c, turns.map { it.fromUser to it.text }, null)
    }
}
