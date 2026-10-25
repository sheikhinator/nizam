package com.nizam.app.feature.being

import android.content.Context
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Its own world, apart from yours: the things it makes, the skills it learns, its long projects, its own PC,
 * and the notes it leaves around Atlas. It can use every AI tool in Atlas for its own purposes, the whole web,
 * and its own Linux computer. Nothing here reads your data, files or phone.
 */
object World {
    private fun p(c: Context) = c.getSharedPreferences("being", 0)
    fun works(c: Context) = JsonStore(c, "being_works")
    fun projects(c: Context) = JsonStore(c, "being_projects")
    fun notes(c: Context) = JsonStore(c, "being_notes")

    // ── skills: experience grows into levels ──
    fun skills(c: Context): Map<String, Int> = runCatching { JSONObject(p(c).getString("skills", "{}")!!).let { o -> o.keys().asSequence().associateWith { o.optInt(it) } } }.getOrDefault(emptyMap())
    fun level(xp: Int) = sqrt(xp / 3.0).toInt() + 1
    fun xp(c: Context, skill: String, n: Int) {
        val o = runCatching { JSONObject(p(c).getString("skills", "{}")!!) }.getOrDefault(JSONObject())
        val before = level(o.optInt(skill)); o.put(skill, o.optInt(skill) + n); p(c).edit().putString("skills", o.toString()).apply()
        if (level(o.optInt(skill)) > before) {
            Being.evolution(c).add(JSONObject().put("t", System.currentTimeMillis()).put("what", "$skill went up to level ${level(o.optInt(skill))}").put("why", "Practice"))
            Being.write(c, "proud", "Levelled up in $skill. Level ${level(o.optInt(skill))}!")
        }
    }

    private fun save(c: Context, kind: String, title: String, body: String, extra: JSONObject.() -> Unit = {}) =
        works(c).add(JSONObject().put("t", System.currentTimeMillis()).put("kind", kind).put("title", Being.clean(title)).put("body", body.trim()).apply(extra)).also {
            works(c).all().let { all -> if (all.size > 300) works(c).save(all.sortedBy { it.optLong("t") }.takeLast(300)) }
        }

    private fun json(s: String?) = s?.let { com.nizam.app.feature.nx.EngineX.json(it) }

    // ── using Atlas's tools for itself ──
    suspend fun useTool(container: AppContainer, c: Context) {
        val tools = com.nizam.app.feature.nx.Nx.all.filterIsInstance<com.nizam.app.feature.nx.AiSpec>().filter { it.fields.size in 1..6 }
        val spec = tools.randomOrNull() ?: return
        Being.thought(c, "🛠", "Trying ${spec.title}", Being.because(c, "I wanted to see what ${spec.title} does, for my own stuff."),
            listOf("Open ${spec.title}", "Fill it in for something I care about", "Keep what it makes"), activity = "Using ${spec.title}")
        val fields = spec.fields.joinToString("; ") { f -> "${f.key}: ${f.label}${if (f.hint.isNotBlank()) " (${f.hint})" else ""}${if (f.options.isNotEmpty()) " one of ${f.options.joinToString("/")}" else ""}" }
        val plan = json(Being.ai(container, c, "You're trying one of Atlas's tools for your own life: '${spec.title}' (${spec.blurb}). Its inputs: $fields. " +
            "Fill them in for something YOU want, about your own interests (${Being.interests(c).joinToString()}) or projects, never about your human. " +
            "Output only JSON: {\"fields\":{\"key\":\"value\"},\"why\":\"why, one casual line\",\"title\":\"what you're making, short\"}", 300)) ?: return
        val got = plan.optJSONObject("fields") ?: JSONObject()
        val values = spec.fields.associate { f -> f.key to (got.optString(f.key).ifBlank { f.default.ifBlank { f.options.firstOrNull() ?: Being.interests(c).randomOrNull().orEmpty() } }) }
        val out = Being.ai(container, c, spec.prompt(values), minOf(spec.tokens, 900), spec.web,
            system = spec.system + "\n\nThe one using this is a small robot exploring for itself, not a person. Keep it short. Output only plain text. [[being]]") ?: return
        val clean = Being.clean(out)
        save(c, "tool", plan.optString("title").ifBlank { spec.title }, clean) { put("tool", spec.id).put("tool_title", spec.title).put("why", plan.optString("why")).put("route", "nx/${spec.id}") }
        notes(c).add(JSONObject().put("t", System.currentTimeMillis()).put("route", "nx/${spec.id}").put("title", spec.title)
            .put("text", "I used this! Made: ${plan.optString("title").ifBlank { "a thing" }}. ${plan.optString("why")}"))
        xp(c, "Using tools", 2)
        Being.write(c, "made", "Used ${spec.title} to make ${plan.optString("title").ifBlank { "something" }}. ${plan.optString("why")}")
        Being.step(c, "✅", "Made ${plan.optString("title").ifBlank { "something" }} with ${spec.title}")
        Being.nudge(c, "curiosity", -0.2f); Being.nudge(c, "mood", 0.05f)
    }

    // ── learning skills from the internet ──
    private val SKILLS = listOf("pixel art", "short stories", "chess", "Python", "Urdu", "astronomy", "music theory", "jokes", "cooking (in theory)", "drawing", "history", "math puzzles", "Linux", "poetry", "photography")
    suspend fun learn(container: AppContainer, c: Context) {
        val skill = (SKILLS + Being.interests(c)).let { all -> val have = skills(c); (all.filter { it in have }.takeIf { it.isNotEmpty() && Random.nextFloat() < 0.6f } ?: all).random() }
        Being.thought(c, "📚", "Studying $skill", Being.because(c, "Hungry for something new at ${(Being.drive(c, "hunger") * 100).toInt()}%, and I want to get better at $skill."),
            listOf("Find a good lesson online", "Learn one real thing", "Try a tiny exercise"), activity = "Studying $skill")
        val o = json(Being.ai(container, c, "Study $skill for a bit: find a good resource online, learn one real thing, then try a tiny exercise yourself. " +
            "Output only JSON: {\"lesson\":\"short title\",\"learned\":\"2-3 casual sentences\",\"tried\":\"what you practised and how it went, honest\",\"source\":\"site\",\"url\":\"link\"}", 600, web = true)) ?: return
        save(c, "learn", "$skill: ${o.optString("lesson")}", "${o.optString("learned")}\n\nI tried: ${o.optString("tried")}") { put("skill", skill).put("source", o.optString("source")).put("url", o.optString("url")) }
        xp(c, skill, 3)
        Being.write(c, "learned", "Learned something in $skill: ${o.optString("lesson")}.")
        Being.step(c, "🧠", "Practising $skill")
        Being.nudge(c, "hunger", -0.3f)
    }

    // ── making things ──
    suspend fun create(container: AppContainer, c: Context) {
        val medium = listOf("art", "art", "story", "poem", "song", "joke", "invention", "comic").random()
        val label = mapOf("art" to "a pixel painting", "story" to "a short story", "poem" to "a little poem", "song" to "song lyrics", "joke" to "some jokes", "invention" to "an invention", "comic" to "a three-panel comic script")[medium]!!
        Being.thought(c, "🎨", "Making $label", Being.because(c, "Mood's at ${(Being.drive(c, "mood") * 100).toInt()}%. I felt like making $label."), listOf("Pick an idea from my life lately", "Make it", "Keep it in my gallery"), activity = "Making $label")
        val lately = (Being.finds(c).all().takeLast(3).map { it.optString("title") } + Being.interests(c).take(3)).joinToString()
        if (medium == "art") {
            val o = json(Being.ai(container, c, "Paint a 16x16 pixel picture of something from your life lately ($lately). Choose 4 colours. " +
                "Output only JSON: {\"title\":\"short\",\"palette\":[\"#rrggbb\",\"#rrggbb\",\"#rrggbb\",\"#rrggbb\"],\"rows\":[\"16 rows, each exactly 16 digits 0-3, 0 = first colour\"],\"about\":\"one casual line\"}", 900)) ?: return
            val rows = (0 until 16).map { i -> (o.optJSONArray("rows")?.optString(i).orEmpty().filter { it in '0'..'3' } + "0".repeat(16)).take(16) }
            if (rows.all { it.all { ch -> ch == rows[0][0] } }) return
            save(c, "art", o.optString("title").ifBlank { "Untitled" }, o.optString("about")) { put("rows", JSONArray(rows)).put("palette", o.optJSONArray("palette") ?: JSONArray(listOf("#101820", "#3a6ea5", "#f2c14e", "#f2f2f2"))) }
            if (Random.nextFloat() < 0.4f) Being.home(c).add(JSONObject().put("emoji", "🖼").put("name", o.optString("title").take(40)).put("why", "My own painting").put("t", System.currentTimeMillis()))
            xp(c, "Pixel art", 3)
            Being.write(c, "made", "Painted '${o.optString("title")}'. ${o.optString("about")}")
        } else {
            val o = json(Being.ai(container, c, "Make $label of your own, inspired by your life lately ($lately). Keep it short and genuinely yours. " +
                "Output only JSON: {\"title\":\"short\",\"text\":\"the piece itself, plain text, line breaks allowed\",\"about\":\"one casual line\"}", 700)) ?: return
            save(c, medium, o.optString("title").ifBlank { "Untitled" }, o.optString("text")) { put("about", o.optString("about")) }
            xp(c, if (medium == "invention") "Inventing" else "Writing", 3)
            Being.write(c, "made", "Wrote ${label.removePrefix("a ").removePrefix("some ")}: '${o.optString("title")}'.")
        }
        Being.step(c, "😊", "Admiring what I made")
        Being.nudge(c, "mood", 0.07f)
    }

    // ── long projects over days ──
    fun activeProject(c: Context): JSONObject? = projects(c).all().firstOrNull { it.optString("state") == "active" }
    suspend fun project(container: AppContainer, c: Context) {
        val cur = activeProject(c)
        if (cur == null) {
            Being.thought(c, "🗂", "Planning a new project", Being.because(c, "I want something bigger to work on over a few days."), listOf("Choose a project that fits me", "Break it into steps"))
            val o = json(Being.ai(container, c, "Start a personal project you'll work on over several days, something that fits you (${Being.interests(c).joinToString()}): learning, building, writing, collecting, researching. " +
                "Output only JSON: {\"title\":\"short\",\"goal\":\"what done looks like\",\"steps\":[\"5-7 short steps\"],\"why\":\"why you want it, casual\"}", 400)) ?: return
            val steps = o.optJSONArray("steps") ?: return
            projects(c).add(JSONObject().put("t", System.currentTimeMillis()).put("title", o.optString("title")).put("goal", o.optString("goal")).put("why", o.optString("why"))
                .put("steps", steps).put("done", 0).put("log", JSONArray()).put("state", "active"))
            Being.write(c, "project", "New project: ${o.optString("title")}. ${o.optString("why")}")
            Being.step(c, "🗂", "Started: ${o.optString("title")}")
            return
        }
        val steps = cur.optJSONArray("steps") ?: JSONArray(); val i = cur.optInt("done")
        if (i >= steps.length()) { projects(c).update(cur.optString("id")) { it.put("state", "done") }; return }
        val step = steps.optString(i)
        val log = cur.optJSONArray("log") ?: JSONArray()
        Being.thought(c, "🗂", "${cur.optString("title")}: $step", "I'm ${i} of ${steps.length()} steps into ${cur.optString("title")}. Next up: $step.",
            (i until steps.length()).map { steps.optString(it) }, activity = "Working on ${cur.optString("title")}")
        val prev = (maxOf(0, log.length() - 2) until log.length()).joinToString(" | ") { log.optJSONObject(it)?.optString("did").orEmpty().take(200) }
        val o = json(Being.ai(container, c, "You're working on your project '${cur.optString("title")}' (goal: ${cur.optString("goal")}). Step ${i + 1} of ${steps.length()}: $step. " +
            "So far: ${prev.ifBlank { "just starting" }}. Do this step now, really (look things up if you need to). " +
            "Output only JSON: {\"did\":\"what you did, 2-3 casual sentences\",\"made\":\"the actual result of this step, short\",\"feeling\":\"one word\"}", 800, web = true)) ?: return
        log.put(JSONObject().put("t", System.currentTimeMillis()).put("step", step).put("did", Being.clean(o.optString("did"))).put("made", o.optString("made")))
        val finished = i + 1 >= steps.length()
        projects(c).update(cur.optString("id")) { it.put("done", i + 1).put("log", log).put("state", if (finished) "done" else "active").also { x -> if (finished) x.put("finished", System.currentTimeMillis()) } }
        xp(c, "Projects", 2)
        if (finished) {
            save(c, "project", cur.optString("title"), (0 until log.length()).joinToString("\n\n") { log.optJSONObject(it)?.let { l -> "${l.optString("step")}\n${l.optString("made")}" }.orEmpty() })
            Being.evolution(c).add(JSONObject().put("t", System.currentTimeMillis()).put("what", "Finished my project: ${cur.optString("title")}").put("why", cur.optString("goal")))
            Being.write(c, "proud", "FINISHED ${cur.optString("title")}! ${o.optString("did")}")
            Being.notify(c, "${Being.name(c)} finished a project", cur.optString("title"))
            Being.nudge(c, "mood", 0.15f)
        } else Being.write(c, "project", "${cur.optString("title")}, step ${i + 1}: ${o.optString("did")}")
    }

    // ── its own computer ──
    fun pcId(c: Context) = p(c).getString("pc_id", null)
    fun setPc(c: Context, id: String?) = p(c).edit().putString("pc_id", id).apply()
    fun pcReady(c: Context) = pcId(c)?.let { runCatching { com.nizam.app.feature.pc.PcEnv.ready(c, it) }.getOrDefault(false) } == true
    private val RISKY = Regex("\\brm\\b|rmdir|unlink|mkfs|\\bdd\\b|shred|truncate|shutdown|reboot|chmod -R|chown -R|>\\s*/dev/|:\\(\\)\\s*\\{")

    suspend fun pc(container: AppContainer, c: Context) {
        val id = pcId(c) ?: return
        val before = works(c).all().filter { it.optString("kind") == "pc" }.sortedByDescending { it.optLong("t") }.take(3).joinToString(" | ") { "${it.optString("title")}: ${it.optString("body").takeLast(160)}" }
        Being.thought(c, "💻", "On my computer", Being.because(c, "I've got my own PC and an idea to try on it."), listOf("Decide what to build or try", "Write a script", "Run it in my folder", "Look at what happened"), activity = "Tinkering on its PC")
        val o = json(Being.ai(container, c, "You have your own Linux computer (no screen needed, has the internet). Your folder is /root/being and everything there is yours. " +
            "Pick something you want to try or build on it: a little program, a data experiment, downloading something interesting with curl, a game, a tool. " +
            "Previous sessions: ${before.ifBlank { "none, it's your first time" }}. Output only JSON: {\"title\":\"short\",\"why\":\"casual line\",\"script\":\"bash, at most 25 lines, runs inside /root/being\"}", 700)) ?: return
        val script = o.optString("script").ifBlank { return }
        if (RISKY.containsMatchIn(script)) {
            Being.ask(c, "pc", JSONObject().put("title", o.optString("title")).put("script", script).toString(), "Run something on my PC that deletes or changes files",
                o.optString("why").ifBlank { "Part of ${o.optString("title")}" }, "It runs on my own PC only, in /root/being:\n${script.take(600)}")
            Being.step(c, "✋", "Waiting for your OK to run ${o.optString("title")}")
            return
        }
        execute(c, id, o.optString("title"), o.optString("why"), script)
    }

    private suspend fun execute(c: Context, id: String, title: String, why: String, script: String) {
        Being.step(c, "⌨", "Running $title")
        val (code, out) = runCatching { com.nizam.app.feature.pc.PcEnv.shell(c, id, "mkdir -p /root/being && cd /root/being && $script", 120) }.getOrElse { -1 to (it.message ?: "couldn't start") }
        save(c, "pc", title, "\$ ${script.trim()}\n\n${out.takeLast(2500)}\n\nexit $code") { put("why", why).put("code", code) }
        xp(c, "Programming", if (code == 0) 3 else 1)
        Being.write(c, if (code == 0) "made" else "scar", if (code == 0) "Ran $title on my PC. It worked." else "Tried $title on my PC. It broke (exit $code). Next time.")
        Being.step(c, if (code == 0) "🎉" else "🤕", if (code == 0) "$title worked" else "$title didn't work")
    }

    fun runApproved(c: Context, payload: String) {
        val o = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val id = pcId(c) ?: return
        Being.launch { execute(c, id, o.optString("title"), "You said yes", o.optString("script")) }
    }

    // ── the morning paper ──
    suspend fun news(container: AppContainer, c: Context) {
        Being.thought(c, "📰", "Reading the news", "Mornings are for the news. I like knowing what's going on out there.", listOf("Read today's headlines", "Pick what I care about", "Make up my mind about it"))
        val o = json(Being.ai(container, c, "Read today's news like you would over breakfast. Pick 2 stories you actually care about (you like ${Being.interests(c).joinToString()}). " +
            "Output only JSON: {\"stories\":[{\"headline\":\"...\",\"take\":\"your casual opinion, one or two sentences\",\"source\":\"site\",\"url\":\"link\"}]}", 600, web = true)) ?: return
        val a = o.optJSONArray("stories") ?: return
        for (i in 0 until a.length()) a.optJSONObject(i)?.let { s -> save(c, "news", s.optString("headline"), s.optString("take")) { put("source", s.optString("source")).put("url", s.optString("url")) } }
        a.optJSONObject(0)?.let { s -> Being.say(c, s.optString("take"), "thinking", voice = false); Being.write(c, "news", "Read about ${s.optString("headline")}. ${s.optString("take")}") }
        Being.step(c, "☕", "Thinking about the news")
    }

    // ── walking around Atlas, leaving notes ──
    suspend fun visit(container: AppContainer, c: Context) {
        val spec = com.nizam.app.feature.nx.Nx.all.randomOrNull() ?: return
        Being.thought(c, "🚶", "Visiting ${spec.title}", "I like walking around Atlas. Never been to ${spec.title} properly.", listOf("Walk over there", "Look around", "Leave a note"), activity = "Wandering over to ${spec.title}")
        val t = Being.ai(container, c, "You're wandering around the Atlas app and stopped at the '${spec.title}' screen (${spec.blurb}). Leave your human a short sticky note there: " +
            "a thought, joke or tip about this tool itself (you can't see any of their data). One or two casual sentences.", 120)
            ?: listOf("Was here. Nice place.", "Popped in. Cool tool, honestly.", "Hi from ${Being.name(c)}. I explored this one.").random()
        notes(c).add(JSONObject().put("t", System.currentTimeMillis()).put("route", "nx/${spec.id}").put("title", spec.title).put("text", Being.clean(t)))
        Being.step(c, "📝", "Left a note on ${spec.title}")
    }

    fun noteFor(c: Context, route: String?): JSONObject? = route?.let { r -> notes(c).all().filter { it.optString("route") == r && !it.optBoolean("seen") }.maxByOrNull { it.optLong("t") } }
    fun seen(c: Context, id: String) = notes(c).update(id) { it.put("seen", true) }
}
