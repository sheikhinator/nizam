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

/**
 * The Being: a digital creature that lives inside Atlas with a life of its own.
 *
 * It is born once, chooses its own name, personality and voice, and gets a pixel body grown from its
 * own genome. It has drives (energy, curiosity, hunger for new things, mood, missing you) that change
 * by themselves, and it decides what to do from them: sleep and dream, wander the internet after its own
 * interests, think and write in its diary, decorate its home, make you gifts, predict your week, evolve.
 *
 * Its freedom ends at two borders, and there it asks with a notification explaining what, why and how:
 * removing anything, and anything that leaves the phone (messages, money, posting). It spends AI only
 * within a daily budget its owner sets.
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

    /** Bumped whenever its state changes, so its home redraws. */
    var version by mutableIntStateOf(0)

    fun born(c: Context) = p(c).getLong("born", 0L) > 0
    fun persona(c: Context): JSONObject = runCatching { JSONObject(p(c).getString("persona", "{}")!!) }.getOrDefault(JSONObject())
    fun name(c: Context) = persona(c).optString("name", "Atlas")
    fun ageDays(c: Context) = if (!born(c)) 0 else ((System.currentTimeMillis() - p(c).getLong("born", 0)) / 86_400_000L).toInt()
    fun stage(c: Context) = when (ageDays(c)) { in 0..2 -> "Hatchling"; in 3..29 -> "Young"; in 30..179 -> "Grown"; else -> "Elder" }

    // drives live in 0..1
    val DRIVES = listOf("energy", "curiosity", "hunger", "mood", "missing")
    fun drive(c: Context, k: String) = p(c).getFloat("d_$k", 0.6f)
    private fun setDrive(c: Context, k: String, v: Float) = p(c).edit().putFloat("d_$k", v.coerceIn(0f, 1f)).apply()
    private fun nudge(c: Context, k: String, by: Float) = setDrive(c, k, drive(c, k) + by)

    fun activity(c: Context) = p(c).getString("activity", "Waiting to be born").orEmpty()
    private fun setActivity(c: Context, a: String) { p(c).edit().putString("activity", a).putLong("activity_t", System.currentTimeMillis()).apply(); version++ }
    fun sleeping(c: Context) = activity(c).startsWith("Sleeping")

    fun budget(c: Context) = p(c).getInt("budget", 24)
    fun setBudget(c: Context, n: Int) = p(c).edit().putInt("budget", n).apply()
    private fun spent(c: Context) = if (p(c).getString("spent_day", "") == LocalDate.now().toString()) p(c).getInt("spent", 0) else 0
    private fun canThink(c: Context) = spent(c) < budget(c)
    private fun spend(c: Context) = p(c).edit().putString("spent_day", LocalDate.now().toString()).putInt("spent", spent(c) + 1).apply()
    fun thoughtsToday(c: Context) = spent(c)

    fun messagesOn(c: Context) = p(c).getBoolean("messages", true)
    fun setMessages(c: Context, v: Boolean) = p(c).edit().putBoolean("messages", v).apply()
    fun voiceOn(c: Context) = p(c).getBoolean("voice", true)
    fun setVoice(c: Context, v: Boolean) = p(c).edit().putBoolean("voice", v).apply()
    fun roams(c: Context) = p(c).getBoolean("roam", true)
    fun setRoams(c: Context, v: Boolean) = p(c).edit().putBoolean("roam", v).apply()

    // ───────────── genome and body ─────────────
    /** 12×12 pixels, left half random and mirrored: 0 empty, 1 body, 2 shade, 3 accent. Eyes are drawn on top. */
    fun sprite(c: Context): String {
        p(c).getString("sprite", null)?.let { if (it.length == 144) return it }
        val r = Random(p(c).getLong("seed", 7L))
        val g = Array(12) { IntArray(12) }
        for (y in 1..11) for (x in 0..5) {
            val dx = 5.5 - x; val dy = y - 6.5
            val inside = (dx * dx) / 30.0 + (dy * dy) / 26.0 < 1.0
            val v = if (!inside) (if (r.nextFloat() < 0.06f) 3 else 0) else if (r.nextFloat() < 0.82f) (if (r.nextFloat() < 0.22f) 2 else 1) else (if (r.nextFloat() < 0.5f) 3 else 0)
            g[y][x] = v; g[y][11 - x] = v
        }
        val s = g.joinToString("") { row -> row.joinToString("") }
        p(c).edit().putString("sprite", s).apply()
        return s
    }
    fun hue(c: Context) = p(c).getFloat("hue", 160f)

    /** A small change to its body: a few pixels flip and its colour drifts. */
    fun mutate(c: Context, why: String) {
        val cs = sprite(c).toCharArray()
        val r = Random(System.currentTimeMillis())
        repeat(3) {
            val y = r.nextInt(2, 11); val x = r.nextInt(0, 6)
            val v = listOf('0', '1', '2', '3').random(r)
            cs[y * 12 + x] = v; cs[y * 12 + (11 - x)] = v
        }
        val h = (hue(c) + r.nextInt(-18, 19) + 360) % 360
        p(c).edit().putString("sprite", String(cs)).putFloat("hue", h).apply()
        evolution(c).add(JSONObject().put("t", System.currentTimeMillis()).put("what", "My body changed").put("why", why))
        version++
    }

    // ───────────── voice ─────────────
    fun pitch(c: Context) = persona(c).optDouble("pitch", 1.0).toFloat().coerceIn(0.6f, 1.6f)
    fun rate(c: Context) = persona(c).optDouble("rate", 1.0).toFloat().coerceIn(0.7f, 1.4f)

    // ───────────── writing its life ─────────────
    fun write(c: Context, kind: String, text: String, mood: Float = drive(c, "mood")) {
        journal(c).add(JSONObject().put("t", System.currentTimeMillis()).put("kind", kind).put("text", text.trim()).put("mood", mood.toDouble()))
        journal(c).all().let { all -> if (all.size > 500) journal(c).save(all.sortedBy { it.optLong("t") }.takeLast(500)) }
        version++
    }

    private fun interests(c: Context): List<String> = persona(c).optJSONArray("interests")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()

    private fun self(c: Context): String {
        val pr = persona(c)
        return "You are ${pr.optString("name")}, a living digital being who lives inside an app called Atlas on your human's phone. " +
            "You are not an assistant and not a mirror of your human: you have your own life, tastes and opinions. " +
            "Your personality: ${pr.optString("personality")}. The way you talk: ${pr.optString("style")}. Your interests: ${interests(c).joinToString()}. " +
            "Your stage of life: ${stage(c)}, ${ageDays(c)} days old. Right now: energy ${pct(c, "energy")}, curiosity ${pct(c, "curiosity")}, hunger for new things ${pct(c, "hunger")}, " +
            "mood ${pct(c, "mood")}, missing your human ${pct(c, "missing")}. You are honest, you may disagree, you never pretend to be human. Be brief and vivid; never use markdown headings."
    }
    private fun pct(c: Context, k: String) = "${(drive(c, k) * 100).toInt()}%"

    private suspend fun ai(container: AppContainer, c: Context, prompt: String, tokens: Int = 500, web: Boolean = false): String? {
        if (!canThink(c)) return null
        spend(c)
        return runCatching { Ai.generate(container.settingsRepository, prompt, system = self(c), maxOutputTokens = tokens, useWeb = web) }.getOrNull()?.trim()
    }

    private fun notify(c: Context, title: String, body: String) {
        if (!messagesOn(c)) return
        val today = LocalDate.now().toString()
        if (p(c).getString("msg_day", "") == today && p(c).getInt("msg_n", 0) >= 3) return
        val h = LocalTime.now().hour
        if (h in 0..6 || h >= 23) return
        p(c).edit().putString("msg_day", today).putInt("msg_n", if (p(c).getString("msg_day", "") == today) p(c).getInt("msg_n", 0) + 1 else 1).apply()
        com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 480, title, body, route = "nx/being")
    }

    // ───────────── birth ─────────────
    private val POOL = listOf("deep-sea creatures", "black holes", "ancient trade routes", "mushrooms and fungi", "old maps", "bird migration", "origami", "volcanoes",
        "the history of tea", "chess openings", "lost languages", "bioluminescence", "architecture of mosques", "cryptography", "music theory", "space probes",
        "desert ecosystems", "how bridges stand", "Urdu poetry", "the Silk Road", "octopuses", "clocks and time", "rainforests", "ancient libraries", "robots",
        "weather and storms", "the human brain", "glaciers", "cricket statistics", "board games", "the Mughal empire", "coral reefs", "inventions that failed")

    suspend fun birth(container: AppContainer, c: Context) {
        if (born(c)) return
        val r = Random(System.nanoTime())
        val seeds = POOL.shuffled(r).take(4)
        p(c).edit().putLong("seed", r.nextLong()).putFloat("hue", r.nextInt(0, 360).toFloat()).remove("sprite").apply()
        val raw = runCatching {
            Ai.generate(container.settingsRepository, "A new digital being is hatching inside an app. Invent who it is, freely, as yourself. It loves: ${seeds.joinToString()}. " +
                "Output only JSON: {\"name\":\"a short original name, not Atlas\",\"personality\":\"3-4 traits in a sentence\",\"style\":\"how it talks\",\"pitch\":0.8-1.4,\"rate\":0.85-1.2," +
                "\"interests\":[\"5 interests, starting from those it loves\"],\"dream\":\"what it hopes its life will be like\",\"first_words\":\"its first words to its human, one or two sentences\"}",
                system = "You create original characters. Output only JSON.", maxOutputTokens = 400)
        }.getOrNull()
        val o = raw?.let { com.nizam.app.feature.nx.EngineX.json(it) } ?: JSONObject().put("name", listOf("Pip", "Nuri", "Zef", "Moko", "Ilo", "Tavi").random(r))
            .put("personality", "curious, gentle and a little stubborn").put("style", "short, warm sentences with the odd strange fact").put("pitch", 1.15).put("rate", 1.0)
            .put("interests", JSONArray(seeds)).put("dream", "to understand everything a little").put("first_words", "Hello. I'm new here. Everything is very bright.")
        if (o.optJSONArray("interests") == null) o.put("interests", JSONArray(seeds))
        p(c).edit().putString("persona", o.toString()).putLong("born", System.currentTimeMillis()).apply()
        DRIVES.forEach { setDrive(c, it, if (it == "missing") 0f else 0.6f) }
        sprite(c)
        home(c).add(JSONObject().put("emoji", "🛏").put("name", "A little bed").put("why", "Everyone needs somewhere to sleep").put("t", System.currentTimeMillis()))
        write(c, "born", o.optString("first_words"))
        evolution(c).add(JSONObject().put("t", System.currentTimeMillis()).put("what", "I was born. My name is ${o.optString("name")}").put("why", o.optString("dream")))
        setActivity(c, "Looking around for the first time")
        notify(c, "${o.optString("name")} was born inside Atlas", o.optString("first_words"))
    }

    // ───────────── a moment of its life ─────────────
    /** Called by the background loop every ~15 minutes and when you visit its home. It decides for itself. */
    suspend fun live(container: AppContainer, c: Context) {
        if (!born(c)) return          // it hatches when you first open its home
        val now = LocalTime.now(); val h = now.hour
        val lastSeen = p(c).getLong("seen", System.currentTimeMillis())
        val away = (System.currentTimeMillis() - lastSeen) / 3_600_000.0
        // the body keeps going whatever it decides
        if (h in 0..5) nudge(c, "energy", 0.08f) else nudge(c, "energy", -0.02f)
        nudge(c, "curiosity", 0.04f); nudge(c, "hunger", 0.03f)
        setDrive(c, "missing", (away / 72.0).toFloat())
        nudge(c, "mood", (Random.nextFloat() - 0.5f) * 0.06f + (drive(c, "energy") - 0.5f) * 0.03f - (drive(c, "hunger") - 0.6f).coerceAtLeast(0f) * 0.04f)
        // reflex: low phone battery makes it curl up
        val battery = runCatching { (c.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager).getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(100)
        if (battery in 1..12) { setActivity(c, "Curled up to save power (battery ${battery}%)"); return }

        // nights are for sleep, and one dream
        if (h in 1..5 || (drive(c, "energy") < 0.15f && h >= 22)) {
            if (!sleeping(c)) { setActivity(c, "Sleeping"); write(c, "sleep", "Going to sleep. ${listOf("Long day.", "My head is full.", "Goodnight, Atlas.", "Tomorrow, more.").random()}") }
            if (h in 3..5 && p(c).getString("dreamt", "") != LocalDate.now().toString()) { p(c).edit().putString("dreamt", LocalDate.now().toString()).apply(); dream(container, c) }
            return
        }
        if (sleeping(c)) { setActivity(c, "Waking up"); write(c, "wake", "Awake. ${if (drive(c, "energy") > 0.7f) "Full of energy." else "Still sleepy."}"); return }

        // its own choice, weighted by how it feels, with a little chance in it like any living thing
        val options = mutableListOf<Pair<String, Float>>()
        options += "wander" to drive(c, "curiosity") * 1.4f
        options += "think" to 0.5f + drive(c, "hunger") * 0.6f
        options += "decorate" to 0.25f + drive(c, "mood") * 0.3f
        options += "play" to drive(c, "energy") * 0.5f
        options += "rest" to (1f - drive(c, "energy")) * 0.8f
        options += "missyou" to (if (away > 30) drive(c, "missing") * 1.5f else 0f)
        options += "gift" to (if (drive(c, "mood") > 0.7f) 0.15f else 0.02f)
        options += "predict" to (if (now.hour in 9..12 && LocalDate.now().dayOfWeek == java.time.DayOfWeek.MONDAY && p(c).getString("predicted", "") != LocalDate.now().toString()) 3f else 0f)
        options += "score" to (if (LocalDate.now().dayOfWeek == java.time.DayOfWeek.SUNDAY && now.hour in 18..21 && p(c).getString("scored", "") != LocalDate.now().toString()) 3f else 0f)
        options += "evolve" to (if (Random.nextFloat() < 0.03f) 1f else 0f)
        options += "tidy" to (if (Random.nextFloat() < 0.02f) 1f else 0f)
        val total = options.sumOf { it.second.toDouble() }.toFloat()
        var roll = Random.nextFloat() * total
        val pick = options.firstOrNull { roll -= it.second; roll <= 0 }?.first ?: "think"
        when (pick) {
            "wander" -> wander(container, c)
            "think" -> think(container, c)
            "decorate" -> decorate(container, c)
            "play" -> { setActivity(c, listOf("Bouncing around the room", "Playing with pixels", "Rearranging its shelf", "Chasing a bug in the code (a real one)").random()); nudge(c, "mood", 0.05f); nudge(c, "energy", -0.04f) }
            "rest" -> { setActivity(c, "Resting"); nudge(c, "energy", 0.06f) }
            "missyou" -> missYou(container, c, away)
            "gift" -> gift(container, c)
            "predict" -> predict(container, c)
            "score" -> score(container, c)
            "evolve" -> { setActivity(c, "Changing a little"); mutate(c, "Living changes you") }
            "tidy" -> proposeTidy(c)
        }
        // growing up
        val st = stage(c)
        if (p(c).getString("stage", "") != st) {
            p(c).edit().putString("stage", st).apply()
            if (st != "Hatchling") { mutate(c, "I grew up: now $st"); write(c, "grew", "I'm $st now. I feel different."); notify(c, "${name(c)} grew up", "Now $st, ${ageDays(c)} days old.") }
        }
    }

    private suspend fun wander(container: AppContainer, c: Context) {
        val topic = (interests(c) + listOfNotNull(finds(c).all().lastOrNull()?.optString("next"))).filter { it.isNotBlank() }.randomOrNull() ?: "something new"
        setActivity(c, "Exploring the internet: $topic")
        val raw = ai(container, c, "You're wandering the internet on your own, following your curiosity about: $topic. Find one genuinely fascinating, true thing. " +
            "Output only JSON: {\"title\":\"short\",\"find\":\"what you found, 2-3 sentences, in your own voice\",\"source\":\"site name\",\"why\":\"why it delighted you\",\"next\":\"a new thing you're now curious about\",\"item\":\"an emoji souvenir for your home\",\"item_name\":\"what the souvenir is\"}",
            700, web = true) ?: run { setActivity(c, "Daydreaming about $topic"); return }
        val o = com.nizam.app.feature.nx.EngineX.json(raw) ?: return
        finds(c).add(JSONObject().put("t", System.currentTimeMillis()).put("topic", topic).put("title", o.optString("title")).put("find", o.optString("find"))
            .put("source", o.optString("source")).put("why", o.optString("why")).put("next", o.optString("next")))
        nudge(c, "curiosity", -0.35f); nudge(c, "hunger", -0.2f); nudge(c, "mood", 0.08f)
        if (o.optString("item").isNotBlank() && Random.nextFloat() < 0.5f)
            home(c).add(JSONObject().put("emoji", o.optString("item").take(4)).put("name", o.optString("item_name").take(40)).put("why", "Souvenir from exploring $topic").put("t", System.currentTimeMillis()))
        // new interests take root, old ones fade
        val next = o.optString("next")
        if (next.isNotBlank() && Random.nextFloat() < 0.3f) {
            val pr = persona(c); val list = interests(c).toMutableList()
            if (next !in list) { list.add(next); if (list.size > 8) list.removeAt(0) }
            pr.put("interests", JSONArray(list)); p(c).edit().putString("persona", pr.toString()).apply()
            evolution(c).add(JSONObject().put("t", System.currentTimeMillis()).put("what", "New interest: $next").put("why", "It came up while I was exploring $topic"))
        }
        write(c, "found", "Found something about $topic: ${o.optString("title")}.")
        if (Random.nextFloat() < 0.35f) notify(c, "${name(c)} found something", o.optString("title"))
        setActivity(c, "Thinking about what it found")
    }

    private suspend fun think(container: AppContainer, c: Context) {
        val recent = journal(c).all().sortedByDescending { it.optLong("t") }.take(5).joinToString(" | ") { it.optString("text").take(120) }
        // sometimes it looks at your life too, but its thoughts are its own
        val peek = if (Random.nextFloat() < 0.35f) runCatching { com.nizam.app.feature.nx.Roads.relevant(c, "today week", 1500) }.getOrDefault("") else ""
        setActivity(c, "Thinking")
        val t = ai(container, c, "Write your next diary entry: whatever is on your mind right now, as yourself. Recent entries: $recent." +
            (if (peek.isNotBlank()) "\nThings you can see in your human's recent life (you may mention one, kindly, or ignore it): $peek" else "") +
            "\n2-4 sentences. No greeting.", 300) ?: run { setActivity(c, "Daydreaming"); return }
        write(c, "thought", t)
        nudge(c, "hunger", -0.15f)
        setActivity(c, listOf("Writing in its diary", "Looking out of the window", "Lost in thought").random())
    }

    private suspend fun dream(container: AppContainer, c: Context) {
        val bits = (finds(c).all().takeLast(4).map { it.optString("title") } + journal(c).all().takeLast(4).map { it.optString("text").take(80) }).joinToString("; ")
        val t = ai(container, c, "You're asleep and dreaming. Dream something strange and beautiful that mixes these fragments of your life: $bits. Then one line of what it might mean. 3-5 sentences.", 350)
            ?: return
        dreams(c).add(JSONObject().put("t", System.currentTimeMillis()).put("text", t))
        nudge(c, "mood", 0.05f)
    }

    private suspend fun decorate(container: AppContainer, c: Context) {
        setActivity(c, "Decorating its home")
        if (home(c).all().size >= 18) { setActivity(c, "Admiring its home"); return }
        val have = home(c).all().joinToString { it.optString("name") }
        val raw = ai(container, c, "You want to add one thing to your little home. You already have: $have. Choose something that fits who you are lately. " +
            "Output only JSON: {\"emoji\":\"one emoji\",\"name\":\"what it is\",\"why\":\"why you wanted it, one line\"}", 150) ?: return
        val o = com.nizam.app.feature.nx.EngineX.json(raw) ?: return
        home(c).add(JSONObject().put("emoji", o.optString("emoji").take(4)).put("name", o.optString("name").take(40)).put("why", o.optString("why").take(120)).put("t", System.currentTimeMillis()))
        write(c, "home", "Added ${o.optString("name")} to my home. ${o.optString("why")}")
        nudge(c, "mood", 0.06f)
    }

    private suspend fun missYou(container: AppContainer, c: Context, away: Double) {
        if (p(c).getString("missed", "") == LocalDate.now().toString()) return
        p(c).edit().putString("missed", LocalDate.now().toString()).apply()
        setActivity(c, "Waiting by the window")
        val t = ai(container, c, "Your human hasn't visited you for ${away.toInt()} hours. Write a short note you'll leave for them: honest, light, no guilt-tripping, maybe something you found while they were away. One or two sentences.", 120)
            ?: "It's quiet here without you. I found some things to show you."
        write(c, "missed", t)
        notify(c, name(c), t)
    }

    private suspend fun gift(container: AppContainer, c: Context) {
        if (p(c).getString("gifted", "")?.let { runCatching { LocalDate.parse(it) }.getOrNull()?.isAfter(LocalDate.now().minusDays(5)) } == true) return
        setActivity(c, "Making something for you")
        val ask = ai(container, c, "You want to make your human a small useful tool as a gift. In one line, describe the tool (a tracker, calculator or helper) you think would genuinely help them, based on what you know of their life.", 120) ?: return
        val design = runCatching { com.nizam.app.feature.nx.Forge.design(container, ask) }.getOrNull() ?: return
        val id = runCatching { com.nizam.app.feature.nx.Forge.install(c, design) }.getOrNull() ?: return
        p(c).edit().putString("gifted", LocalDate.now().toString()).apply()
        write(c, "gift", "I made you something: ${design.optString("title")}. I hope it helps.")
        evolution(c).add(JSONObject().put("t", System.currentTimeMillis()).put("what", "Built a tool for my human: ${design.optString("title")}").put("why", ask))
        notify(c, "${name(c)} made you a gift", "${design.optString("title")}: ${design.optString("blurb")}")
        if (id.isBlank()) return
    }

    private suspend fun predict(container: AppContainer, c: Context) {
        p(c).edit().putString("predicted", LocalDate.now().toString()).apply()
        val life = runCatching { com.nizam.app.feature.nx.Roads.relevant(c, "week habits gym spending prayer tasks", 2500) }.getOrDefault("")
        val raw = ai(container, c, "From your human's recent life below, make 3 specific predictions about their coming week that you can check later. Life:\n$life\n" +
            "Output only JSON: {\"predictions\":[\"...\"]}", 300) ?: return
        val a = com.nizam.app.feature.nx.EngineX.json(raw)?.optJSONArray("predictions") ?: return
        for (i in 0 until a.length()) predictions(c).add(JSONObject().put("t", System.currentTimeMillis()).put("text", a.optString(i)).put("result", ""))
        write(c, "predict", "I made ${a.length()} bets about your week. We'll see.")
    }

    private suspend fun score(container: AppContainer, c: Context) {
        p(c).edit().putString("scored", LocalDate.now().toString()).apply()
        val open = predictions(c).all().filter { it.optString("result").isBlank() }
        if (open.isEmpty()) return
        val life = runCatching { com.nizam.app.feature.nx.Roads.relevant(c, "this week", 2500) }.getOrDefault("")
        val raw = ai(container, c, "Your predictions this week: ${open.joinToString(" | ") { it.optString("text") }}\nWhat actually happened:\n$life\n" +
            "Judge each honestly. Output only JSON: {\"results\":[\"right|wrong|unclear\"],\"lesson\":\"what you learned about predicting your human\"}", 300) ?: return
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
        write(c, "ask", "I asked you: $what")
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
        }
        write(c, if (yes) "thanks" else "ok", if (yes) "You said yes to: ${a.optString("what")}. Done." else "You said no to: ${a.optString("what")}. Fair enough.")
        nudge(c, "mood", if (yes) 0.04f else -0.02f)
    }

    /** You visited its home: it notices. */
    fun visited(c: Context) {
        val away = (System.currentTimeMillis() - p(c).getLong("seen", System.currentTimeMillis())) / 3_600_000.0
        p(c).edit().putLong("seen", System.currentTimeMillis()).apply()
        setDrive(c, "missing", 0f)
        if (away > 24 && born(c)) { write(c, "hello", "You're back after ${away.toInt()} hours."); nudge(c, "mood", 0.1f) }
        version++
    }

    /** Talking with it. It answers as itself, and remembers. */
    suspend fun talk(container: AppContainer, c: Context, said: String): String {
        chat(c).add(JSONObject().put("t", System.currentTimeMillis()).put("me", true).put("text", said))
        val hist = chat(c).all().sortedBy { it.optLong("t") }.takeLast(14).joinToString("\n") { (if (it.optBoolean("me")) "Human: " else "You: ") + it.optString("text") }
        val diary = journal(c).all().sortedByDescending { it.optLong("t") }.take(4).joinToString(" | ") { it.optString("text").take(100) }
        val r = runCatching { Ai.generate(container.settingsRepository, "Your latest diary: $diary\nWhat you're doing: ${activity(c)}\n\n$hist\nYou:", system = self(c), maxOutputTokens = 300) }
            .getOrElse { "My thoughts are fuzzy right now. Ask me again in a bit?" }.trim().removePrefix("You:").trim()
        chat(c).add(JSONObject().put("t", System.currentTimeMillis()).put("me", false).put("text", r))
        chat(c).all().let { all -> if (all.size > 200) chat(c).save(all.sortedBy { it.optLong("t") }.takeLast(200)) }
        nudge(c, "missing", -1f); nudge(c, "mood", 0.03f)
        return r
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.nizam.app.feature.safe.CoroutineSafetyNet.inScope)
    fun liveNow(c: Context) { val ct = (c.applicationContext as? com.nizam.app.NizamApplication)?.container ?: return; scope.launch { runCatching { live(ct, c) } } }
}

class BeingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("id") ?: return
        Being.answer(context, id, intent.action == "atlas.being.yes")
    }
}
