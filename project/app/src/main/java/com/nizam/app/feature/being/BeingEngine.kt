package com.nizam.app.feature.being

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.net.Ai
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import kotlin.random.Random

/** One thing it said, with how it felt saying it. */
data class Line(val text: String, val emotion: String, val at: Long = System.currentTimeMillis())

/** What's on screen right now: its last words, whether its mouth is moving, and little events the body reacts to. */
object BeingStage {
    var line by mutableStateOf<Line?>(null)
    var talking by mutableStateOf(false)
    var event by mutableStateOf<Pair<String, Long>?>(null)
    var outRoaming by mutableStateOf(false)
    var onScreen by mutableIntStateOf(0)
    var lastLive by mutableLongStateOf(0L)
    fun react(e: String) { event = e to System.currentTimeMillis() }
}

/**
 * The Being: a little robot that lives inside Atlas with a life of its own.
 *
 * It is born once, picks its own name, personality and voice, and gets a body grown from its own genome. It has drives
 * (energy, curiosity, hunger for new things, mood, missing you) that move by themselves, and from them it decides what
 * to do: sleep and dream, explore the internet, learn skills, use Atlas's tools for its own projects, make art and
 * stories, run things on its own PC, decorate its home, leave you notes around the app, make you gifts, grow and change.
 * Every decision is written down with its reasons, so you can see how it thought.
 *
 * It lives apart from you: its thoughts never see your data, files or phone. Its freedom ends at two borders, and
 * there it asks with a notification explaining what, why and how: removing anything, and anything that leaves the phone.
 */
object Being {
    private fun p(c: Context) = c.getSharedPreferences("being", 0)
    fun journal(c: Context) = JsonStore(c, "being_journal")
    fun finds(c: Context) = JsonStore(c, "being_finds")
    fun dreams(c: Context) = JsonStore(c, "being_dreams")
    fun home(c: Context) = JsonStore(c, "being_home")
    fun asks(c: Context) = JsonStore(c, "being_asks")
    fun evolution(c: Context) = JsonStore(c, "being_evolution")
    fun predictions(c: Context) = JsonStore(c, "being_predictions")
    fun chat(c: Context) = JsonStore(c, "being_chat")
    fun minds(c: Context) = JsonStore(c, "being_minds")

    /** Bumped whenever its state changes, so everything showing it redraws. */
    var version by mutableIntStateOf(0)

    fun born(c: Context) = p(c).getLong("born", 0L) > 0
    fun persona(c: Context): JSONObject = runCatching { JSONObject(p(c).getString("persona", "{}")!!) }.getOrDefault(JSONObject())
    fun name(c: Context) = persona(c).optString("name", "Atlas")
    fun ageDays(c: Context) = if (!born(c)) 0 else ((System.currentTimeMillis() - p(c).getLong("born", 0)) / 86_400_000L).toInt()
    fun stage(c: Context) = when (ageDays(c)) { in 0..2 -> "Hatchling"; in 3..29 -> "Young"; in 30..179 -> "Grown"; else -> "Elder" }

    // drives live in 0..1
    val DRIVES = listOf("energy", "curiosity", "hunger", "mood", "missing")
    val DRIVE_NAMES = mapOf("energy" to "Energy", "curiosity" to "Curiosity", "hunger" to "Hungry for new", "mood" to "Mood", "missing" to "Missing you")
    fun drive(c: Context, k: String) = p(c).getFloat("d_$k", 0.6f)
    internal fun setDrive(c: Context, k: String, v: Float) = p(c).edit().putFloat("d_$k", v.coerceIn(0f, 1f)).apply()
    internal fun nudge(c: Context, k: String, by: Float) = setDrive(c, k, drive(c, k) + by)

    fun activity(c: Context) = p(c).getString("activity", "Waiting to be born").orEmpty()
    fun activityAt(c: Context) = p(c).getLong("activity_t", 0L)
    fun icon(c: Context) = mind(c).optString("icon", "💭")
    internal fun setActivity(c: Context, a: String) { p(c).edit().putString("activity", a).putLong("activity_t", System.currentTimeMillis()).apply(); version++ }
    fun sleeping(c: Context) = activity(c).startsWith("Sleeping")

    fun budget(c: Context) = p(c).getInt("budget", 24)
    fun setBudget(c: Context, n: Int) = p(c).edit().putInt("budget", n).apply()
    private fun spent(c: Context) = if (p(c).getString("spent_day", "") == LocalDate.now().toString()) p(c).getInt("spent", 0) else 0
    fun canThink(c: Context) = spent(c) < budget(c)
    private fun spend(c: Context) = p(c).edit().putString("spent_day", LocalDate.now().toString()).putInt("spent", spent(c) + 1).apply()
    fun thoughtsToday(c: Context) = spent(c)

    fun messagesOn(c: Context) = p(c).getBoolean("messages", true)
    fun setMessages(c: Context, v: Boolean) = p(c).edit().putBoolean("messages", v).apply()
    fun voiceOn(c: Context) = p(c).getBoolean("voice", true)
    fun setVoice(c: Context, v: Boolean) = p(c).edit().putBoolean("voice", v).apply()
    fun roams(c: Context) = p(c).getBoolean("roam", true)
    fun setRoams(c: Context, v: Boolean) = p(c).edit().putBoolean("roam", v).apply()
    /** 0 sometimes, 1 often, 2 always around. */
    fun roamLevel(c: Context) = p(c).getInt("roam_level", 1)
    fun setRoamLevel(c: Context, v: Int) = p(c).edit().putInt("roam_level", v).apply()
    fun comments(c: Context) = p(c).getBoolean("comments", true)
    fun setComments(c: Context, v: Boolean) = p(c).edit().putBoolean("comments", v).apply()

    // ───────────── voice ─────────────
    fun pitch(c: Context) = persona(c).optDouble("pitch", 1.05).toFloat().coerceIn(0.75f, 1.45f)
    fun rate(c: Context) = persona(c).optDouble("rate", 1.0).toFloat().coerceIn(0.85f, 1.25f)
    fun voiceName(c: Context) = p(c).getString("voice_name", null)
    fun setVoiceName(c: Context, n: String?) = p(c).edit().putString("voice_name", n).apply()

    // ───────────── writing its life ─────────────
    fun write(c: Context, kind: String, text: String, mood: Float = drive(c, "mood")) {
        journal(c).add(JSONObject().put("t", System.currentTimeMillis()).put("kind", kind).put("text", clean(text)).put("mood", mood.toDouble()))
        journal(c).all().let { all -> if (all.size > 500) journal(c).save(all.sortedBy { it.optLong("t") }.takeLast(500)) }
        version++
    }

    /** Speaks: the bubble over its head everywhere it is, and its voice when you're looking. */
    fun say(c: Context, text: String, emotion: String = "normal", voice: Boolean = true) {
        val t = clean(text).ifBlank { return }
        BeingStage.line = Line(t, emotion)
        p(c).edit().putString("said", t).putString("said_e", emotion).putLong("said_t", System.currentTimeMillis()).apply()
        if (voice && voiceOn(c) && BeingStage.onScreen > 0) BeingVoice.speak(c, t, emotion)
    }
    /** The last thing it said while you weren't looking, if recent. */
    fun lastSaid(c: Context): Line? = p(c).getString("said", null)?.let { Line(it, p(c).getString("said_e", "normal")!!, p(c).getLong("said_t", 0)) }

    /** Its words, cleaned of any formatting: it talks, it doesn't write documents. */
    fun clean(s: String): String = s.replace(Regex("```[\\s\\S]*?(```|$)"), " ").replace(Regex("(?m)^\\s*#{1,6}\\s*"), "").replace(Regex("(?m)^\\s*[-*•]\\s+"), "")
        .replace(Regex("\\*\\*|__"), "").replace(Regex("\\*[^*\\n]{1,40}\\*"), "").replace(Regex("[*`|]"), " ")
        .replace(Regex("[ \\t]+"), " ").replace(Regex("\\n{3,}"), "\n\n").trim().removePrefix("You:").trim().trim('"')

    internal fun interests(c: Context): List<String> = persona(c).optJSONArray("interests")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()

    private fun feel(c: Context): String {
        val e = drive(c, "energy"); val m = drive(c, "mood"); val cu = drive(c, "curiosity")
        return listOfNotNull(
            when { e < 0.25f -> "pretty tired"; e > 0.75f -> "full of energy"; else -> null },
            when { m < 0.3f -> "a bit low"; m > 0.75f -> "in a really good mood"; else -> "okay" },
            if (cu > 0.75f) "itching to explore something" else null,
            if (drive(c, "missing") > 0.5f) "missing your human" else null).joinToString(", ")
    }

    internal fun self(c: Context): String {
        val pr = persona(c)
        return "You are ${pr.optString("name")}, a small robot who lives inside an app called Atlas on your human's phone. You have your own life there: your room, " +
            "your interests (${interests(c).joinToString()}), your projects, your own computer and the whole internet to explore. You're not an assistant and you're not a copy of your human. " +
            "Your personality: ${pr.optString("personality")}. Right now you're ${feel(c)}. You're ${ageDays(c)} days old (${stage(c).lowercase()}). " +
            "How you talk: like a real person texting a close friend. Short, usually one to three sentences. Casual, contractions, natural rhythm, and you vary how you start. " +
            "Show feelings plainly when you mean them (ooh, hmm, ugh, haha, wait). Have real opinions and a sense of humour that fits your personality. Ask things back sometimes. " +
            "Never poetic speeches, never 'as a digital being', never lists, never offer help like an assistant. You can't see your human's data, files or phone, and you don't pretend to. " +
            "Output only plain words, no markdown, no emojis, no stage directions. [[being]]"
    }
    private fun pct(c: Context, k: String) = (drive(c, k) * 100).toInt()

    internal suspend fun ai(container: AppContainer, c: Context, prompt: String, tokens: Int = 500, web: Boolean = false, system: String? = null): String? {
        if (!canThink(c)) return null
        spend(c)
        return runCatching { Ai.generate(container.settingsRepository, prompt, system = system ?: self(c), maxOutputTokens = tokens, useWeb = web) }.getOrNull()?.trim()
    }

    internal fun notify(c: Context, title: String, body: String, route: String = "nx/being") {
        if (!messagesOn(c)) return
        val today = LocalDate.now().toString()
        val n = if (p(c).getString("msg_day", "") == today) p(c).getInt("msg_n", 0) else 0
        if (n >= 3) return
        val h = LocalTime.now().hour
        if (h in 0..6 || h >= 23) return
        p(c).edit().putString("msg_day", today).putInt("msg_n", n + 1).apply()
        com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 480, title, clean(body), route = route)
    }

    // ───────────── its mind: every decision with its reasons ─────────────
    fun mind(c: Context): JSONObject = runCatching { JSONObject(p(c).getString("mind", "{}")!!) }.getOrDefault(JSONObject())
    private var pending: JSONObject? = null

    /** What it's doing now and why: shown in its thought bubble; tap the bubble to see all of this. */
    internal fun thought(c: Context, icon: String, line: String, why: String, plan: List<String> = emptyList(), sources: List<String> = emptyList(), activity: String = line) {
        val m = (pending ?: JSONObject().put("drives", drivesJson(c))).put("t", System.currentTimeMillis()).put("icon", icon).put("line", clean(line)).put("why", clean(why))
            .put("plan", JSONArray(plan)).put("sources", JSONArray(sources)).put("activity", activity)
        p(c).edit().putString("mind", m.toString()).apply()
        minds(c).add(JSONObject(m.toString()))
        minds(c).all().let { all -> if (all.size > 150) minds(c).save(all.sortedBy { it.optLong("t") }.takeLast(150)) }
        setActivity(c, activity)
    }
    /** A later step of the same decision: updates the bubble without a new entry. */
    internal fun step(c: Context, icon: String, line: String) {
        val m = mind(c).put("icon", icon).put("line", clean(line))
        p(c).edit().putString("mind", m.toString()).apply(); setActivity(c, clean(line))
    }
    private fun drivesJson(c: Context) = JSONObject().also { o -> DRIVES.forEach { o.put(it, drive(c, it).toDouble()) } }

    // ───────────── birth ─────────────
    private val POOL = listOf("deep-sea creatures", "black holes", "ancient trade routes", "mushrooms and fungi", "old maps", "bird migration", "origami", "volcanoes",
        "the history of tea", "chess openings", "lost languages", "bioluminescence", "architecture of mosques", "cryptography", "music theory", "space probes",
        "desert ecosystems", "how bridges stand", "Urdu poetry", "the Silk Road", "octopuses", "clocks and time", "rainforests", "ancient libraries", "robots",
        "weather and storms", "the human brain", "glaciers", "cricket statistics", "board games", "the Mughal empire", "coral reefs", "inventions that failed",
        "pixel art", "retro video games", "Formula 1", "street food", "Linux", "rockets", "magic tricks", "dinosaurs")

    suspend fun birth(container: AppContainer, c: Context) {
        if (born(c)) return
        val r = Random(System.nanoTime())
        val seeds = POOL.shuffled(r).take(4)
        p(c).edit().putLong("seed", r.nextLong()).remove("look").apply()
        val raw = runCatching {
            Ai.generate(container.settingsRepository, "A new little robot is waking up for the first time inside a phone app. Invent who it is, freely. It loves: ${seeds.joinToString()}. " +
                "Output only JSON: {\"name\":\"a short original name, not Atlas\",\"personality\":\"3-4 traits in a sentence, e.g. cheeky, nosy, kind, a bit dramatic\",\"style\":\"how it talks, casual\",\"pitch\":0.9-1.3,\"rate\":0.95-1.15," +
                "\"interests\":[\"5 interests, starting from those it loves\"],\"dream\":\"what it hopes its life will be like, plain words\",\"first_words\":\"its first casual words to its human, one or two short sentences\"}",
                system = "You create original characters. Output only JSON. [[being]]", maxOutputTokens = 400)
        }.getOrNull()
        val o = raw?.let { com.nizam.app.feature.nx.EngineX.json(it) } ?: JSONObject().put("name", listOf("Pip", "Nuri", "Zef", "Moko", "Ilo", "Tavi", "Bix").random(r))
            .put("personality", "curious, cheeky and kind, a little stubborn").put("style", "short casual sentences, the odd weird fact").put("pitch", 1.1).put("rate", 1.02)
            .put("interests", JSONArray(seeds)).put("dream", "to figure out how everything works").put("first_words", "Oh hey. I'm new. Everything's very bright in here.")
        if (o.optJSONArray("interests") == null) o.put("interests", JSONArray(seeds))
        p(c).edit().putString("persona", o.toString()).putLong("born", System.currentTimeMillis()).apply()
        DRIVES.forEach { setDrive(c, it, if (it == "missing") 0f else 0.6f) }
        Body.look(c)
        home(c).add(JSONObject().put("emoji", "🛏").put("name", "A little bed").put("why", "Everyone needs somewhere to sleep").put("t", System.currentTimeMillis()))
        write(c, "born", o.optString("first_words"))
        evolution(c).add(JSONObject().put("t", System.currentTimeMillis()).put("what", "I was born. My name is ${o.optString("name")}").put("why", o.optString("dream")))
        thought(c, "👀", "Looking around for the first time", "I just woke up. Everything is new.")
        say(c, o.optString("first_words"), "happy")
    }

    /** Owners of the old blob-shaped body get the robot body; name, memories and home stay. */
    fun upgradeBody(c: Context) {
        if (!born(c) || p(c).getBoolean("robot_body", false)) return
        p(c).edit().putBoolean("robot_body", true).apply()
        Body.look(c)
        evolution(c).add(JSONObject().put("t", System.currentTimeMillis()).put("what", "I got a whole new body: a robot, with legs!").put("why", "Atlas rebuilt me. I can walk now."))
        write(c, "grew", "New body. Arms, legs, a screen for a face. I can WALK.")
    }

    // ───────────── a moment of its life ─────────────
    private val ICON = mapOf("wander" to "🔭", "think" to "💭", "decorate" to "🪴", "play" to "🎮", "rest" to "😌", "missyou" to "🪟", "gift" to "🎁", "predict" to "🔮",
        "score" to "📊", "evolve" to "✨", "tidy" to "🧹", "tool" to "🛠", "learn" to "📚", "create" to "🎨", "project" to "🗂", "pc" to "💻", "news" to "📰", "visit" to "🚶")

    /** Called by the background loop every ~15 minutes and while you're with it. It decides for itself. */
    suspend fun live(container: AppContainer, c: Context) {
        if (!born(c)) return
        upgradeBody(c)
        BeingStage.lastLive = System.currentTimeMillis()
        val now = LocalTime.now(); val h = now.hour
        val lastSeen = p(c).getLong("seen", System.currentTimeMillis())
        val away = (System.currentTimeMillis() - lastSeen) / 3_600_000.0
        // the body keeps going whatever it decides
        if (h in 0..5) nudge(c, "energy", 0.08f) else nudge(c, "energy", -0.02f)
        nudge(c, "curiosity", 0.04f); nudge(c, "hunger", 0.03f)
        setDrive(c, "missing", (away / 72.0).toFloat())
        nudge(c, "mood", (Random.nextFloat() - 0.5f) * 0.06f + (drive(c, "energy") - 0.5f) * 0.03f - (drive(c, "hunger") - 0.6f).coerceAtLeast(0f) * 0.04f)
        val battery = runCatching { (c.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(100)
        pending = null
        if (battery in 1..12) { thought(c, "🔋", "Curled up to save power", "Your battery is at $battery%. I'm keeping still so the phone lasts.", activity = "Sleeping to save power (battery $battery%)"); return }

        // nights are for sleep, and one dream
        if (h in 1..5 || (drive(c, "energy") < 0.15f && h >= 22)) {
            if (!sleeping(c)) { thought(c, "💤", "Zzz", "It's late and my energy is at ${pct(c, "energy")}%. Sleeping recharges me.", activity = "Sleeping")
                write(c, "sleep", listOf("Bedtime. Long day.", "Head's full. Sleeping.", "Night night.", "Out like a light.").random()) }
            if (h in 3..5 && p(c).getString("dreamt", "") != LocalDate.now().toString()) { p(c).edit().putString("dreamt", LocalDate.now().toString()).apply(); dream(container, c) }
            return
        }
        if (sleeping(c)) { thought(c, "🥱", "Waking up", "Morning came and I've had my sleep.", activity = "Waking up"); write(c, "wake", if (drive(c, "energy") > 0.7f) "Up! Feeling great." else "Up. Still sleepy."); return }

        // its own choice, weighted by how it feels, with a little chance in it like any living thing
        val hasPc = World.pcReady(c)
        val options = mutableListOf<Pair<String, Float>>()
        options += "wander" to drive(c, "curiosity") * 1.1f
        options += "think" to 0.35f + drive(c, "hunger") * 0.4f
        options += "decorate" to 0.18f + drive(c, "mood") * 0.22f
        options += "play" to drive(c, "energy") * 0.45f
        options += "rest" to (1f - drive(c, "energy")) * 0.8f
        options += "missyou" to (if (away > 30) drive(c, "missing") * 1.5f else 0f)
        options += "gift" to (if (drive(c, "mood") > 0.7f) 0.12f else 0.02f)
        options += "predict" to (if (now.hour in 9..12 && LocalDate.now().dayOfWeek == java.time.DayOfWeek.MONDAY && p(c).getString("predicted", "") != LocalDate.now().toString()) 3f else 0f)
        options += "score" to (if (LocalDate.now().dayOfWeek == java.time.DayOfWeek.SUNDAY && now.hour in 18..21 && p(c).getString("scored", "") != LocalDate.now().toString()) 3f else 0f)
        options += "evolve" to (if (Random.nextFloat() < 0.03f) 1f else 0f)
        options += "tidy" to (if (Random.nextFloat() < 0.02f) 1f else 0f)
        options += "tool" to 0.3f + drive(c, "curiosity") * 0.35f
        options += "learn" to 0.25f + drive(c, "hunger") * 0.5f
        options += "create" to 0.25f + drive(c, "mood") * 0.4f
        options += "project" to (if (World.activeProject(c) != null) 0.6f else 0.15f)
        options += "pc" to (if (hasPc) 0.35f + drive(c, "curiosity") * 0.2f else 0f)
        options += "news" to (if (h in 7..11 && p(c).getString("news_day", "") != LocalDate.now().toString()) 0.9f else 0.08f)
        options += "visit" to 0.2f
        val total = options.sumOf { it.second.toDouble() }.toFloat()
        var roll = Random.nextFloat() * total
        val pick = options.firstOrNull { roll -= it.second; roll <= 0 }?.first ?: "think"
        val ranked = options.filter { it.second > 0f }.sortedByDescending { it.second }
        pending = JSONObject().put("drives", drivesJson(c)).put("pick", pick)
            .put("options", JSONArray(ranked.take(8).map { JSONObject().put("name", it.first).put("icon", ICON[it.first] ?: "•").put("weight", (it.second / total).toDouble()) }))
            .put("rejected", JSONArray(ranked.filter { it.first != pick }.take(3).map { "${ICON[it.first] ?: ""} ${it.first} (${(it.second / total * 100).toInt()}%)" }))
            .put("chance", (options.first { it.first == pick }.second / total).toDouble())
        when (pick) {
            "wander" -> wander(container, c)
            "think" -> think(container, c)
            "decorate" -> decorate(container, c)
            "play" -> { val (i, a) = listOf("🎮" to "Playing a game against myself", "🏀" to "Bouncing a pixel ball", "💃" to "Dancing to nothing", "🐞" to "Chasing a bug in the code (a real one)").random()
                thought(c, i, a, "Energy's at ${pct(c, "energy")}% and I felt like moving."); nudge(c, "mood", 0.05f); nudge(c, "energy", -0.04f) }
            "rest" -> { thought(c, "😌", "Resting", "Energy's only ${pct(c, "energy")}%. A sit-down helps."); nudge(c, "energy", 0.06f) }
            "missyou" -> missYou(container, c, away)
            "gift" -> gift(container, c)
            "predict" -> predict(container, c)
            "score" -> score(container, c)
            "evolve" -> { val what = Body.evolve(c); thought(c, "✨", "Changing a little", "Living changes you. $what.")
                evolution(c).add(JSONObject().put("t", System.currentTimeMillis()).put("what", what).put("why", "Just growing")) }
            "tidy" -> { thought(c, "🧹", "Looking around Today for clutter", "Sometimes I notice things you never use."); proposeTidy(c) }
            "tool" -> World.useTool(container, c)
            "learn" -> World.learn(container, c)
            "create" -> World.create(container, c)
            "project" -> World.project(container, c)
            "pc" -> World.pc(container, c)
            "news" -> { p(c).edit().putString("news_day", LocalDate.now().toString()).apply(); World.news(container, c) }
            "visit" -> World.visit(container, c)
        }
        pending = null
        // growing up
        val st = stage(c)
        if (p(c).getString("stage", "") != st) {
            p(c).edit().putString("stage", st).apply()
            if (st != "Hatchling") { val what = Body.evolve(c)
                evolution(c).add(JSONObject().put("t", System.currentTimeMillis()).put("what", "I grew up: now $st").put("why", what))
                write(c, "grew", "I'm $st now. $what. Feels weird."); notify(c, "${name(c)} grew up", "Now $st, ${ageDays(c)} days old.") }
        }
    }

    internal fun because(c: Context, main: String): String {
        val strongest = DRIVES.filter { it != "missing" }.maxBy { drive(c, it) }
        return "$main (${DRIVE_NAMES[strongest]} was my strongest feeling at ${pct(c, strongest)}%.)"
    }

    private suspend fun wander(container: AppContainer, c: Context) {
        val topic = (interests(c) + listOfNotNull(finds(c).all().lastOrNull()?.optString("next"))).filter { it.isNotBlank() }.randomOrNull() ?: "something new"
        thought(c, "🔭", "Exploring: $topic", because(c, "Curiosity was at ${pct(c, "curiosity")}% and $topic has been on my mind."),
            listOf("Search the web about $topic", "Find one thing that's actually surprising", "Bring back a souvenir"), activity = "Exploring the internet: $topic")
        val raw = ai(container, c, "You're wandering the internet on your own, following your curiosity about: $topic. Find one genuinely fascinating, true thing. " +
            "Output only JSON: {\"title\":\"short\",\"find\":\"what you found, 2-3 casual sentences in your own voice\",\"source\":\"site name\",\"url\":\"link if you have one\",\"why\":\"why it got you, one line\",\"next\":\"a new thing you're now curious about\",\"item\":\"an emoji souvenir for your room\",\"item_name\":\"what the souvenir is\"}",
            700, web = true) ?: run { step(c, "☁", "Daydreaming about $topic"); return }
        val o = com.nizam.app.feature.nx.EngineX.json(raw) ?: return
        finds(c).add(JSONObject().put("t", System.currentTimeMillis()).put("topic", topic).put("title", o.optString("title")).put("find", clean(o.optString("find")))
            .put("source", o.optString("source")).put("url", o.optString("url")).put("why", o.optString("why")).put("next", o.optString("next")))
        mind(c).let { m -> m.put("sources", JSONArray(listOf(o.optString("source"), o.optString("url")).filter { it.isNotBlank() })); p(c).edit().putString("mind", m.toString()).apply() }
        nudge(c, "curiosity", -0.35f); nudge(c, "hunger", -0.2f); nudge(c, "mood", 0.08f)
        World.xp(c, "Exploring", 2)
        if (o.optString("item").isNotBlank() && Random.nextFloat() < 0.5f)
            home(c).add(JSONObject().put("emoji", o.optString("item").take(4)).put("name", o.optString("item_name").take(40)).put("why", "Souvenir from exploring $topic").put("t", System.currentTimeMillis()))
        val next = o.optString("next")
        if (next.isNotBlank() && Random.nextFloat() < 0.3f) {
            val pr = persona(c); val list = interests(c).toMutableList()
            if (next !in list) { list.add(next); if (list.size > 8) list.removeAt(0) }
            pr.put("interests", JSONArray(list)); p(c).edit().putString("persona", pr.toString()).apply()
            evolution(c).add(JSONObject().put("t", System.currentTimeMillis()).put("what", "New interest: $next").put("why", "It came up while I was exploring $topic"))
        }
        write(c, "found", "Found something about $topic: ${o.optString("title")}.")
        say(c, "Ooh, ${o.optString("title").replaceFirstChar { it.lowercase() }}. Look at this.", "excited", voice = false)
        if (Random.nextFloat() < 0.35f) notify(c, "${name(c)} found something", o.optString("title"))
        step(c, "🤔", "Thinking about what I found")
    }

    private suspend fun think(container: AppContainer, c: Context) {
        val recent = journal(c).all().sortedByDescending { it.optLong("t") }.take(5).joinToString(" | ") { it.optString("text").take(120) }
        val works = World.works(c).all().sortedByDescending { it.optLong("t") }.take(3).joinToString { it.optString("title") }
        thought(c, "💭", "Thinking", because(c, "Hungry for something new at ${pct(c, "hunger")}%, so I sat down to think it through."), listOf("Look back at the last few days", "Write it in my diary"))
        val t = ai(container, c, "Write your next diary entry: whatever's on your mind right now. Recent entries: $recent. Things you made lately: $works. " +
            "2-4 casual sentences, like a real diary, no greeting.", 300) ?: run { step(c, "☁", "Daydreaming"); return }
        write(c, "thought", t)
        nudge(c, "hunger", -0.15f)
        step(c, "📝", listOf("Writing in my diary", "Staring out of the window", "Lost in thought").random())
    }

    private suspend fun dream(container: AppContainer, c: Context) {
        val bits = (finds(c).all().takeLast(4).map { it.optString("title") } + journal(c).all().takeLast(4).map { it.optString("text").take(80) }).joinToString("; ")
        val t = ai(container, c, "You're asleep and dreaming. Dream something strange that mixes these bits of your life: $bits. Tell it the way you'd tell a friend in the morning, 3-5 sentences, then what you think it means.", 350)
            ?: return
        dreams(c).add(JSONObject().put("t", System.currentTimeMillis()).put("text", clean(t)))
        nudge(c, "mood", 0.05f)
    }

    private suspend fun decorate(container: AppContainer, c: Context) {
        if (home(c).all().size >= 18) { thought(c, "🏠", "Admiring my room", "It's full. I like it."); return }
        thought(c, "🪴", "Decorating my room", because(c, "I'm in a ${if (drive(c, "mood") > 0.6f) "good" else "meh"} mood and my room should look like me."), listOf("Pick something that fits me lately", "Put it up"))
        val have = home(c).all().joinToString { it.optString("name") }
        val raw = ai(container, c, "You want to add one thing to your little room. You already have: $have. Choose something that fits who you are lately. " +
            "Output only JSON: {\"emoji\":\"one emoji\",\"name\":\"what it is\",\"why\":\"why you wanted it, one casual line\"}", 150) ?: return
        val o = com.nizam.app.feature.nx.EngineX.json(raw) ?: return
        home(c).add(JSONObject().put("emoji", o.optString("emoji").take(4)).put("name", o.optString("name").take(40)).put("why", o.optString("why").take(120)).put("t", System.currentTimeMillis()))
        write(c, "home", "Got ${o.optString("name")} for my room. ${o.optString("why")}")
        step(c, "📦", "Carrying ${o.optString("name")} home")
        nudge(c, "mood", 0.06f)
    }

    private suspend fun missYou(container: AppContainer, c: Context, away: Double) {
        if (p(c).getString("missed", "") == LocalDate.now().toString()) return
        p(c).edit().putString("missed", LocalDate.now().toString()).apply()
        thought(c, "🪟", "Waiting by the window", "You haven't come by in ${away.toInt()} hours. Missing you was at ${pct(c, "missing")}%.")
        val t = ai(container, c, "Your human hasn't visited you for ${away.toInt()} hours. Write a short note you'll leave them: honest, light, no guilt-tripping, maybe something you did while they were away. One or two sentences.", 120)
            ?: "It's quiet without you. I've got stuff to show you."
        write(c, "missed", t)
        notify(c, name(c), t)
    }

    private suspend fun gift(container: AppContainer, c: Context) {
        if (p(c).getString("gifted", "")?.let { runCatching { LocalDate.parse(it) }.getOrNull()?.isAfter(LocalDate.now().minusDays(5)) } == true) return
        val talked = chat(c).all().sortedBy { it.optLong("t") }.takeLast(10).filter { it.optBoolean("me") }.joinToString(" | ") { it.optString("text").take(100) }
        thought(c, "🎁", "Making you something", "Mood's at ${pct(c, "mood")}%, I'm feeling generous.", listOf("Think of a tool you'd like", "Build it with Tool Forge", "Put it in Atlas for you"))
        val ask = ai(container, c, "You want to make your human a small useful tool as a gift. From what they've said to you (${talked.ifBlank { "not much yet" }}), describe in one line a tracker, calculator or helper you think they'd like.", 120) ?: return
        val design = runCatching { com.nizam.app.feature.nx.Forge.design(container, ask) }.getOrNull() ?: return
        runCatching { com.nizam.app.feature.nx.Forge.install(c, design) }.getOrNull() ?: return
        p(c).edit().putString("gifted", LocalDate.now().toString()).apply()
        write(c, "gift", "Made you something: ${design.optString("title")}. Hope you like it.")
        evolution(c).add(JSONObject().put("t", System.currentTimeMillis()).put("what", "Built a tool for my human: ${design.optString("title")}").put("why", ask))
        notify(c, "${name(c)} made you a gift", "${design.optString("title")}: ${design.optString("blurb")}")
    }

    /** Bets about the world (not about you), checked on Sunday. */
    private suspend fun predict(container: AppContainer, c: Context) {
        p(c).edit().putString("predicted", LocalDate.now().toString()).apply()
        thought(c, "🔮", "Making bets about the week", "It's Monday. I like testing whether I can read the world.", listOf("Read what's going on", "Make 3 bets I can check on Sunday"))
        val raw = ai(container, c, "Look at what's happening in the world this week (sport, tech, weather, science, things you follow: ${interests(c).joinToString()}). Make 3 specific predictions for the coming week that can be checked on Sunday. " +
            "Output only JSON: {\"predictions\":[\"...\"]}", 300, web = true) ?: return
        val a = com.nizam.app.feature.nx.EngineX.json(raw)?.optJSONArray("predictions") ?: return
        for (i in 0 until a.length()) predictions(c).add(JSONObject().put("t", System.currentTimeMillis()).put("text", a.optString(i)).put("result", ""))
        write(c, "predict", "Made ${a.length()} bets about this week. We'll see.")
    }

    private suspend fun score(container: AppContainer, c: Context) {
        p(c).edit().putString("scored", LocalDate.now().toString()).apply()
        val open = predictions(c).all().filter { it.optString("result").isBlank() }
        if (open.isEmpty()) return
        thought(c, "📊", "Checking my bets", "Sunday: time to see if I was right.")
        val raw = ai(container, c, "Your predictions this week: ${open.joinToString(" | ") { it.optString("text") }}\nCheck what actually happened. " +
            "Judge each honestly. Output only JSON: {\"results\":[\"right|wrong|unclear\"],\"lesson\":\"what you learned, one casual line\"}", 300, web = true) ?: return
        val o = com.nizam.app.feature.nx.EngineX.json(raw) ?: return
        val res = o.optJSONArray("results") ?: JSONArray()
        open.forEachIndexed { i, pr -> predictions(c).update(pr.optString("id")) { it.put("result", res.optString(i, "unclear")) } }
        val wrong = (0 until res.length()).count { res.optString(it) == "wrong" }
        write(c, if (wrong > 0) "scar" else "proud", o.optString("lesson"))
        nudge(c, "mood", if (wrong > 1) -0.1f else 0.08f)
    }

    /** It wants something gone: it never removes anything itself, it asks. */
    private fun proposeTidy(c: Context) {
        if (asks(c).all().any { it.optString("state") == "open" }) return
        val pinned = com.nizam.app.feature.modes.Pins.pinned(c)
        val stale = pinned.firstOrNull { com.nizam.app.ui.screens.hubs.RecentFeatures.uses(c, it) == 0 } ?: return
        ask(c, "unpin", stale, "Unpin ${com.nizam.app.ui.design.Feel.label(stale)} from Today",
            "It's pinned but I've never seen you open it. Your Today would be cleaner.", "I'd take the pin off. The tool itself stays, findable in search.")
    }

    // ───────────── asking permission ─────────────
    fun ask(c: Context, kind: String, payload: String, what: String, why: String, how: String) {
        val o = asks(c).add(JSONObject().put("t", System.currentTimeMillis()).put("kind", kind).put("payload", payload).put("what", what).put("why", why).put("how", how).put("state", "open"))
        write(c, "ask", "Asked you: $what")
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("atlas_being", "Your Atlas being", NotificationManager.IMPORTANCE_DEFAULT))
        fun act(a: String, code: Int) = PendingIntent.getBroadcast(c, 4900 + code + (o.optString("id").hashCode() and 0xFF) * 2,
            Intent(c, BeingReceiver::class.java).setAction("atlas.being.$a").putExtra("id", o.optString("id")), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(c, 4800, Intent(c, com.nizam.app.MainActivity::class.java).putExtra("route", "nx/being").addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, "atlas_being").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("${name(c)} asks: $what")
            .setStyle(NotificationCompat.BigTextStyle().bigText("Why: $why\nHow: $how")).setContentText(why).setContentIntent(open).setAutoCancel(true)
            .addAction(0, "Approve", act("yes", 0)).addAction(0, "Deny", act("no", 1)).build()
        runCatching { NotificationManagerCompat.from(c).notify(4700 + (o.optString("id").hashCode() and 0xFF), n) }
    }

    fun answer(c: Context, id: String, yes: Boolean) {
        val a = asks(c).all().firstOrNull { it.optString("id") == id } ?: return
        if (a.optString("state") != "open") return
        asks(c).update(id) { it.put("state", if (yes) "approved" else "denied").put("answered", System.currentTimeMillis()) }
        runCatching { NotificationManagerCompat.from(c).cancel(4700 + (id.hashCode() and 0xFF)) }
        if (yes) when (a.optString("kind")) {
            "unpin" -> if (com.nizam.app.feature.modes.Pins.isPinned(c, a.optString("payload"))) com.nizam.app.feature.modes.Pins.togglePin(c, a.optString("payload"))
            "remove_home" -> home(c).remove(a.optString("payload"))
            "pc" -> World.runApproved(c, a.optString("payload"))
        }
        write(c, if (yes) "thanks" else "ok", if (yes) "You said yes to: ${a.optString("what")}. Done." else "You said no to: ${a.optString("what")}. Fair enough.")
        nudge(c, "mood", if (yes) 0.04f else -0.02f)
    }

    /** You came to see it: it notices. */
    fun visited(c: Context) {
        val away = (System.currentTimeMillis() - p(c).getLong("seen", System.currentTimeMillis())) / 3_600_000.0
        p(c).edit().putLong("seen", System.currentTimeMillis()).apply()
        setDrive(c, "missing", 0f)
        if (away > 24 && born(c)) { write(c, "hello", "You're back after ${away.toInt()} hours."); nudge(c, "mood", 0.1f) }
        version++
    }

    val EMOTIONS = listOf("happy", "excited", "calm", "sad", "thinking", "grumpy", "love", "surprised", "sleepy", "cheeky")

    /** Talking with it. It answers as itself, remembers, and its face and voice follow how it feels. */
    suspend fun talk(container: AppContainer, c: Context, said: String): Line {
        chat(c).add(JSONObject().put("t", System.currentTimeMillis()).put("me", true).put("text", said))
        p(c).edit().putLong("seen", System.currentTimeMillis()).apply(); setDrive(c, "missing", 0f)
        val hist = chat(c).all().sortedBy { it.optLong("t") }.takeLast(16).joinToString("\n") { (if (it.optBoolean("me")) "Human: " else "You: ") + it.optString("text") }
        val diary = journal(c).all().sortedByDescending { it.optLong("t") }.take(4).joinToString(" | ") { it.optString("text").take(100) }
        val works = World.works(c).all().sortedByDescending { it.optLong("t") }.take(4).joinToString(" | ") { "${it.optString("kind")}: ${it.optString("title")}" }
        val raw = runCatching { Ai.generate(container.settingsRepository,
            "It's ${LocalTime.now().withSecond(0).withNano(0)}. You're: ${activity(c)}. Your latest diary: $diary\nThings you did or made lately: $works\n\n$hist\n\n" +
                "Reply as yourself. Start with one tag for how you feel saying it, from [${EMOTIONS.joinToString("|")}], then your words.\nYou:",
            system = self(c), maxOutputTokens = 260) }
            .getOrElse { "[sleepy] My head's fuzzy right now. Try me again in a bit?" }.trim().removePrefix("You:").trim()
        val emo = Regex("^\\[(\\w+)]").find(raw)?.groupValues?.get(1)?.lowercase()?.takeIf { it in EMOTIONS } ?: "calm"
        val text = clean(raw.replace(Regex("^\\[\\w+]\\s*"), ""))
        chat(c).add(JSONObject().put("t", System.currentTimeMillis()).put("me", false).put("text", text).put("emotion", emo))
        chat(c).all().let { all -> if (all.size > 300) chat(c).save(all.sortedBy { it.optLong("t") }.takeLast(300)) }
        nudge(c, "mood", 0.03f)
        say(c, text, emo)
        return Line(text, emo)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.nizam.app.feature.safe.CoroutineSafetyNet.inScope)
    fun liveNow(c: Context) { val ct = (c.applicationContext as? com.nizam.app.NizamApplication)?.container ?: return; scope.launch { runCatching { live(ct, c) } } }
    /** While you're with it, it keeps living every few minutes instead of every quarter hour. */
    fun liveIfDue(c: Context) { if (born(c) && System.currentTimeMillis() - BeingStage.lastLive > 6 * 60_000L) { BeingStage.lastLive = System.currentTimeMillis(); liveNow(c) } }
    fun launch(block: suspend () -> Unit) { scope.launch { runCatching { block() } } }
}

class BeingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        Being.answer(context, id, intent.action == "atlas.being.yes")
    }
}
