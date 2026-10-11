package com.nizam.app.feature.town

import android.content.Context
import com.nizam.app.AppContainer
import com.nizam.app.feature.progress.Xp
import com.nizam.app.net.Ai
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.math.hypot
import kotlin.random.Random

/** Everything the town knows about you — pulled from your real data, so the gossip is true. */
data class TownFacts(
    val level: Int, val streak: Int, val best: Int, val xpToday: Int,
    val prayers30: Int, val sets30: Int, val study30: Int, val lessons30: Int,
    val diaryDays30: Int, val activeDays30: Int, val inactiveRecent: Int, val name: String
)

enum class Kind(val label: String) {
    HOME("Home"), MASJID("Masjid"), LIBRARY("Library"), GYM("Training yard"),
    MARKET("Market"), SCHOOL("School"), FOUNTAIN("Fountain"), TOWER("Watchtower"), BAKERY("Bakery")
}

data class Building(val kind: Kind, val x: Int, val y: Int, val w: Int, val h: Int, val height: Int)

/** Why each building exists — the town is a mirror of what you've actually done. */
data class Unlock(val kind: Kind, val need: String, val met: (TownFacts) -> Boolean)

val UNLOCKS = listOf(
    Unlock(Kind.BAKERY, "Reach level 2") { it.level >= 2 },
    Unlock(Kind.MASJID, "Log 40 prayers in 30 days") { it.prayers30 >= 40 },
    Unlock(Kind.GYM, "Log 30 sets in 30 days") { it.sets30 >= 30 },
    Unlock(Kind.LIBRARY, "Study 300 minutes in 30 days") { it.study30 >= 300 },
    Unlock(Kind.MARKET, "Be active 15 of the last 30 days") { it.activeDays30 >= 15 },
    Unlock(Kind.SCHOOL, "Finish 5 course lessons") { it.lessons30 >= 5 },
    Unlock(Kind.FOUNTAIN, "Hold a 7-day streak") { it.best >= 7 },
    Unlock(Kind.TOWER, "Reach level 10") { it.level >= 10 }
)

enum class Personality(val label: String) {
    OPTIMIST("Optimist"), GOSSIP("Gossip"), GRUMBLER("Grumbler"),
    DREAMER("Dreamer"), ELDER("Elder"), JOKER("Joker")
}

class Sim(
    val id: Int, val name: String, val age: Int, val job: String,
    val personality: Personality, val home: Building, val work: Building?,
    val shirt: Long, val skin: Long
) {
    var x = home.x + 0.5f; var y = home.y + home.h + 0.3f
    var tx = x; var ty = y
    var activity = "home"
    var thought: String? = null
    var thoughtUntil = 0L
    var friend: Int = -1
    var visible = true
}

data class Dialogue(val a: String, val b: String, val lines: List<Pair<String, String>>)

private val NAMES = listOf(
    "Ahmed", "Ayesha", "Bilal", "Fatima", "Hamza", "Zainab", "Usman", "Sara", "Ali", "Maryam",
    "Hassan", "Nadia", "Omar", "Khadija", "Yusuf", "Hina", "Tariq", "Rabia", "Saad", "Iqra",
    "Faisal", "Zara", "Imran", "Mehwish", "Asad", "Sana", "Danish", "Nida", "Waqas", "Amna",
    "Kamran", "Areeba", "Junaid", "Laiba", "Shahid", "Mahnoor", "Adeel", "Rida", "Zohaib", "Hafsa"
)
private val SHIRTS = listOf(0xFF4FC3A1, 0xFFE0B14A, 0xFF9B8CD6, 0xFFE06A4C, 0xFF58A6C9, 0xFFE08AA8, 0xFF6FB98A, 0xFFD98E4A)
private val SKINS = listOf(0xFFF6D7C3, 0xFFEBC09A, 0xFFD6A27A, 0xFFB57F55, 0xFF8C5A3C)

class Town(val seed: Long, val facts: TownFacts) {

    val size: Int = (10 + facts.level * 2).coerceIn(10, 34)
    val buildings = mutableListOf<Building>()
    val sims = mutableListOf<Sim>()
    val trees = mutableListOf<Pair<Int, Int>>()
    val road = size / 2
    var gentle = false
    val neglected: Boolean get() = !gentle && facts.inactiveRecent >= 3
    val unlocked: Set<Kind> = UNLOCKS.filter { it.met(facts) }.map { it.kind }.toSet()

    init { generate() }

    private fun free(x: Int, y: Int, w: Int, h: Int): Boolean {
        if (x < 1 || y < 1 || x + w >= size - 1 || y + h >= size - 1) return false
        // keep the crossroads clear
        if ((road - 1..road + 1).any { it in x until x + w } || (road - 1..road + 1).any { it in y until y + h }) return false
        return buildings.none { b -> x < b.x + b.w + 1 && x + w + 1 > b.x && y < b.y + b.h + 1 && y + h + 1 > b.y }
    }

    private fun place(kind: Kind, w: Int, h: Int, height: Int, rnd: Random): Building? {
        repeat(300) {
            // civic buildings gather near the centre, homes spread outward
            val spread = if (kind == Kind.HOME) size / 2 - 2 else (size / 3).coerceAtLeast(3)
            // a zone you chose in the planner pulls the building to that side of town
            val (zx, zy) = when (TownLayout.zones[kind.name]) { "North" -> 0 to -1; "South" -> 0 to 1; "East" -> 1 to 0; "West" -> -1 to 0; else -> 0 to 0 }
            val half = (spread / 2).coerceAtLeast(1)
            val x = road + zx * half + rnd.nextInt(-if (zx == 0) spread else half, (if (zx == 0) spread else half) + 1)
            val y = road + zy * half + rnd.nextInt(-if (zy == 0) spread else half, (if (zy == 0) spread else half) + 1)
            if (free(x, y, w, h)) return Building(kind, x, y, w, h, height).also { buildings.add(it) }
        }
        return null
    }

    private fun generate() {
        val rnd = Random(seed)
        // civic buildings first so they claim the centre
        if (Kind.MASJID in unlocked) place(Kind.MASJID, 3, 3, 3, rnd)
        if (Kind.FOUNTAIN in unlocked) place(Kind.FOUNTAIN, 1, 1, 1, rnd)
        if (Kind.MARKET in unlocked) place(Kind.MARKET, 3, 2, 2, rnd)
        if (Kind.LIBRARY in unlocked) place(Kind.LIBRARY, 2, 2, 3, rnd)
        if (Kind.SCHOOL in unlocked) place(Kind.SCHOOL, 3, 2, 2, rnd)
        if (Kind.GYM in unlocked) place(Kind.GYM, 2, 2, 2, rnd)
        if (Kind.BAKERY in unlocked) place(Kind.BAKERY, 2, 1, 2, rnd)
        if (Kind.TOWER in unlocked) place(Kind.TOWER, 1, 1, 5, rnd)

        val homeCount = (1 + facts.level).coerceIn(1, 18)
        repeat(homeCount) { place(Kind.HOME, rnd.nextInt(1, 3), rnd.nextInt(1, 3), rnd.nextInt(1, 3), rnd) }

        repeat(size * 2) {
            val x = rnd.nextInt(0, size); val y = rnd.nextInt(0, size)
            if (x != road && y != road && buildings.none { x in it.x - 1..it.x + it.w && y in it.y - 1..it.y + it.h })
                trees.add(x to y)
        }

        val homes = buildings.filter { it.kind == Kind.HOME }.ifEmpty { return }
        val jobsFor = mapOf(
            Kind.MASJID to "Imam", Kind.LIBRARY to "Librarian", Kind.GYM to "Coach",
            Kind.MARKET to "Trader", Kind.SCHOOL to "Teacher", Kind.BAKERY to "Baker", Kind.TOWER to "Guard"
        )
        val workplaces = buildings.filter { it.kind in jobsFor }
        val population = (2 + facts.level * 2).coerceIn(2, 36)
        val nameOrder = NAMES.shuffled(Random(seed))
        repeat(population) { i ->
            val work = workplaces.getOrNull(i % (workplaces.size + 2))
            val sim = Sim(
                id = i, name = TownLayout.names[i] ?: nameOrder[i % nameOrder.size], age = 18 + Random(seed + i).nextInt(0, 55),
                job = TownLayout.jobs[i] ?: work?.let { jobsFor[it.kind] } ?: listOf("Farmer", "Builder", "Tailor", "Student")[i % 4],
                personality = Personality.entries[Random(seed * 7 + i).nextInt(Personality.entries.size)],
                home = homes[i % homes.size], work = work,
                shirt = SHIRTS[i % SHIRTS.size], skin = SKINS[(i * 3) % SKINS.size]
            )
            sims.add(sim)
        }
        sims.forEachIndexed { i, s -> s.friend = (i + 1 + (seed % 3).toInt()) % sims.size }
    }

    /** Where each sim wants to be right now — their day follows your real clock. */
    var coworking = false

    fun schedule(hour: Double, prayerHours: List<Double>, rnd: Random) {
        val masjid = buildings.firstOrNull { it.kind == Kind.MASJID }
        val square = buildings.firstOrNull { it.kind == Kind.FOUNTAIN || it.kind == Kind.MARKET }
        val atPrayer = masjid != null && prayerHours.any { !it.isNaN() && hour >= it - 0.1 && hour <= it + 0.33 }
        sims.forEach { s ->
            val (activity, target) = when {
                hour < 5.5 || hour >= 23 -> "asleep" to s.home
                atPrayer && (s.job == "Imam" || s.personality != Personality.JOKER || s.id % 3 != 0) -> "praying" to masjid
                coworking && hour in 6.0..23.0 -> "co-working" to (s.work ?: square ?: s.home)
                hour in 9.0..17.0 && s.work != null -> "working" to s.work
                hour in 17.0..21.0 && square != null -> "socialising" to square
                hour in 17.0..21.0 -> "strolling" to null
                else -> "at home" to s.home
            }
            s.activity = activity
            s.visible = activity != "asleep"
            if (target != null) {
                s.tx = target.x + target.w / 2f + rnd.nextFloat() * 0.8f - 0.4f
                s.ty = target.y + target.h + 0.4f + rnd.nextFloat() * 0.6f
            } else if (hypot(s.x - s.tx, s.y - s.ty) < 0.2f) {
                s.tx = (road + rnd.nextInt(-size / 3, size / 3 + 1)).toFloat() + 0.5f
                s.ty = road + 0.5f
            }
        }
    }

    fun step(dt: Float) {
        sims.forEach { s ->
            val dx = s.tx - s.x; val dy = s.ty - s.y
            val d = hypot(dx, dy)
            if (d > 0.05f) {
                val speed = 1.2f * dt
                // walk the grid axes like real streets rather than cutting diagonally
                if (kotlin.math.abs(dx) > 0.05f) s.x += dx.coerceIn(-speed, speed) else s.y += dy.coerceIn(-speed, speed)
            }
        }
    }
}

/**
 * The ambient chatter — written on the phone from your real numbers, flavoured by personality.
 * Free, offline, and always current. AI dialogues sit on top of this, not instead of it.
 */
object Chatter {
    var gentle = false
    fun line(s: Sim, f: TownFacts, rnd: Random): String {
        val who = f.name.ifBlank { "our founder" }
        val topics = mutableListOf<String>()
        if (s.activity == "co-working") return listOf(
            "Heads down with $who. Focus!", "Working alongside $who today.", "No phones — $who is in a focus session.",
            "Quiet, everyone. $who is doing deep work.", "One more hour and we all take a break."
        ).random(rnd)
        when (s.activity) {
            "praying" -> topics += listOf("Masha'Allah, the rows are fuller today.", "I pray $who keeps this up.")
            "working" -> topics += listOf("Work first, talk later.", "Busy day at the ${s.work?.kind?.label?.lowercase() ?: "fields"}.")
            "asleep" -> topics += listOf("Zzz…")
        }
        if (f.streak >= 3) topics += "${f.streak} days straight. $who is building something here."
        if (!gentle && f.streak == 0 && f.inactiveRecent >= 2) topics += "Haven't heard from $who in ${f.inactiveRecent} days. The town feels quieter."
        if (f.sets30 < 10 && !gentle) topics += "The training yard won't build itself — $who needs to lift."
        else topics += "${f.sets30} sets this month. Our coach is proud."
        if (f.prayers30 < 40) topics += "${40 - f.prayers30} more prayers logged and we'll finally raise the masjid."
        if (f.study30 in 1..299) topics += "Only ${300 - f.study30} study minutes until the library opens."
        if (f.xpToday == 0 && !gentle) topics += "No progress today yet. Still time, $who."
        else topics += "+${f.xpToday} XP today. I can feel the town growing."
        if (f.level >= 5) topics += "Remember when this was one hut? Level ${f.level} now."

        val base = topics[rnd.nextInt(topics.size)]
        return when (s.personality) {
            Personality.GRUMBLER -> listOf("Hmph. ", "Honestly? ", "Typical. ").random(rnd) + base
            Personality.OPTIMIST -> base + listOf(" 🌱", " Alhamdulillah.", " Good things coming.").random(rnd)
            Personality.GOSSIP -> listOf("Did you hear? ", "Between us… ", "Word is: ").random(rnd) + base
            Personality.DREAMER -> base + " Imagine what we'll look like at level ${f.level + 5}."
            Personality.ELDER -> "In my day… " + base.replaceFirstChar { it.lowercase() }
            Personality.JOKER -> base + listOf(" 😄", " (I'd help, but my back.)", " No pressure!").random(rnd)
        }
    }
}

class TownStore(context: Context) {
    private val file = File(context.filesDir, "town.json")

    fun seed(): Long = runCatching { JSONObject(file.readText()).optLong("seed") }.getOrNull()
        ?.takeIf { it != 0L } ?: System.currentTimeMillis().also { saveSeed(it) }

    private fun saveSeed(seed: Long) = runCatching {
        val o = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        file.writeText(o.put("seed", seed).toString())
    }

    fun dialogues(): Pair<String, List<Dialogue>> = runCatching {
        val o = JSONObject(file.readText())
        val arr = o.optJSONArray("dialogues") ?: JSONArray()
        o.optString("dialogueDate") to (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            val lines = d.optJSONArray("lines") ?: JSONArray()
            Dialogue(d.optString("a"), d.optString("b"), (0 until lines.length()).map { j ->
                val l = lines.getJSONObject(j); l.optString("who") to l.optString("text")
            })
        }
    }.getOrElse { "" to emptyList() }

    fun saveDialogues(list: List<Dialogue>) = runCatching {
        val o = runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() }
        o.put("dialogueDate", LocalDate.now().toString())
        o.put("dialogues", JSONArray().apply {
            list.forEach { d ->
                put(JSONObject().put("a", d.a).put("b", d.b).put("lines", JSONArray().apply {
                    d.lines.forEach { (w, t) -> put(JSONObject().put("who", w).put("text", t)) }
                }))
            }
        })
        file.writeText(o.toString())
    }
}

object TownData {
    suspend fun facts(c: AppContainer): TownFacts {
        val s = Xp.summarise(c)
        val d = s.last30
        var inactive = 0
        for (day in d) { if (day.active) break; inactive++ }
        return TownFacts(
            level = s.level, streak = s.streak, best = s.bestStreak, xpToday = s.today.xp,
            prayers30 = d.sumOf { it.prayers }, sets30 = d.sumOf { it.sets },
            study30 = d.sumOf { it.studyMinutes }, lessons30 = d.sumOf { it.lessons },
            diaryDays30 = d.count { it.wroteDiary }, activeDays30 = d.count { it.active },
            inactiveRecent = inactive, name = c.settingsRepository.displayName.first()
        )
    }

    /** The real conversations — AI-written about you, once a day, cached so the town costs nothing to open. */
    suspend fun writeDialogues(c: AppContainer, town: Town): List<Dialogue> {
        val f = town.facts
        val cast = town.sims.take(8).joinToString("\n") {
            "${it.name}, ${it.age}, ${it.job}, ${it.personality.label.lowercase()}"
        }
        val raw = Ai.generate(
            settings = c.settingsRepository,
            prompt = """
                You write conversations between residents of a tiny pixel town that grows as its
                founder, ${f.name.ifBlank { "the founder" }}, improves their real life.

                The founder's real last 30 days: level ${f.level}, streak ${f.streak} days (best ${f.best}),
                ${f.prayers30} prayers logged, ${f.sets30} gym sets, ${f.study30} study minutes,
                ${f.lessons30} lessons finished, diary on ${f.diaryDays30} days, active ${f.activeDays30}/30 days,
                ${if (f.inactiveRecent > 0) "inactive for the last ${f.inactiveRecent} days" else "active today"}.
                Buildings still locked: ${UNLOCKS.filterNot { it.kind in town.unlocked }.joinToString { "${it.kind.label} (${it.need})" }}.

                Residents:
                $cast

                Write 4 short conversations of 3-4 lines each between pairs of residents. They talk
                about the founder's actual progress using the real numbers, what the town could
                become, and they nudge — some proud, some teasing, one doubting, all warm underneath.
                Stay in character. Mention a specific locked building and what would unlock it.
                Never cruel, never guilt-tripping. Short lines, like thought bubbles.

                Return ONLY JSON: {"dialogues":[{"a":"Name","b":"Name","lines":[{"who":"Name","text":"..."}]}]}
            """.trimIndent(),
            system = "You write warm, funny, specific dialogue for a cosy life-sim. You return only valid JSON.",
            maxOutputTokens = 3000
        )
        val arr = JSONObject(Ai.cleanJson(raw)).optJSONArray("dialogues") ?: JSONArray()
        return (0 until arr.length()).map { i ->
            val d = arr.getJSONObject(i)
            val lines = d.optJSONArray("lines") ?: JSONArray()
            Dialogue(d.optString("a"), d.optString("b"), (0 until lines.length()).map { j ->
                val l = lines.getJSONObject(j); l.optString("who") to l.optString("text")
            })
        }
    }
}


/**
 * Adventures (after Finch): a strong day sends one resident out exploring. They come back with a
 * story and a find that stays in the town. Written locally from your real day — no AI call needed.
 */
data class Adventure(val date: String, val sim: String, val story: String, val find: String, val emoji: String)

object Adventures {
    private val FINDS = listOf("🏮" to "a brass lantern", "🗺️" to "an old map", "🌳" to "a fig sapling", "🔔" to "a bronze bell",
        "🔭" to "a telescope", "🪁" to "a bright kite", "🪑" to "a carved bench", "🐝" to "a beehive", "🪧" to "a signpost",
        "🌻" to "sunflower seeds", "🏺" to "a clay pot", "📜" to "a scroll of poems", "🕯️" to "a tall candle", "🧭" to "a compass")
    private val PLACES = listOf("the river bend", "the far hills", "the old caravan road", "the cedar woods", "the dry lake",
        "the ruined watchtower", "the orchard past the wall", "the stone bridge")

    fun all(c: Context): List<Adventure> = runCatching {
        val a = JSONObject(File(c.filesDir, "town.json").readText()).optJSONArray("adventures") ?: JSONArray()
        (0 until a.length()).map { a.getJSONObject(it) }.map {
            Adventure(it.optString("date"), it.optString("sim"), it.optString("story"), it.optString("find"), it.optString("emoji")) }
    }.getOrElse { emptyList() }

    /** One adventure per strong day (40+ XP). Returns the new one, or null. */
    fun maybeSend(c: Context, town: Town): Adventure? {
        val today = LocalDate.now().toString()
        val past = all(c)
        if (town.facts.xpToday < 40 || past.any { it.date == today } || town.sims.isEmpty()) return null
        val rnd = Random(today.hashCode().toLong())
        val sim = town.sims[rnd.nextInt(town.sims.size)]
        val (emoji, find) = FINDS[(past.size + rnd.nextInt(3)) % FINDS.size]
        val place = PLACES[rnd.nextInt(PLACES.size)]
        val who = town.facts.name.ifBlank { "our founder" }
        val because = when {
            town.facts.streak >= 7 -> "$who's ${town.facts.streak}-day streak gave everyone the nerve to go further"
            town.facts.prayers30 > 60 -> "the town has been steady and calm lately, and it showed on the road"
            town.facts.sets30 > 40 -> "all that training in the yard made the climb feel easy"
            else -> "$who had a strong day, and the whole town felt lighter for it"
        }
        val adv = Adventure(today, sim.name, "${sim.name} set out for $place and came home at dusk with $find. They say $because.", find, emoji)
        runCatching {
            val f = File(c.filesDir, "town.json")
            val o = runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
            val arr = o.optJSONArray("adventures") ?: JSONArray()
            arr.put(JSONObject().put("date", adv.date).put("sim", adv.sim).put("story", adv.story).put("find", adv.find).put("emoji", adv.emoji))
            f.writeText(o.put("adventures", arr).toString())
        }
        return adv
    }
}


/** Choices from the town planner: where civic buildings go, the architecture, and residents you've named. */
object TownLayout {
    var zones: Map<String, String> = emptyMap()
    var style: String = "Classic"
    var names: Map<Int, String> = emptyMap()
    var jobs: Map<Int, String> = emptyMap()

    fun load(c: Context) = runCatching {
        val o = JSONObject(File(c.filesDir, "town.json").readText()).optJSONObject("layout") ?: return@runCatching
        zones = o.optJSONObject("zones")?.let { z -> z.keys().asSequence().associateWith { z.optString(it) } } ?: emptyMap()
        style = o.optString("style", "Classic")
        names = o.optJSONObject("names")?.let { z -> z.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to z.optString(k) } }.toMap() } ?: emptyMap()
        jobs = o.optJSONObject("jobs")?.let { z -> z.keys().asSequence().mapNotNull { k -> k.toIntOrNull()?.let { it to z.optString(k) } }.toMap() } ?: emptyMap()
    }

    fun save(c: Context) = runCatching {
        val f = File(c.filesDir, "town.json")
        val o = runCatching { JSONObject(f.readText()) }.getOrElse { JSONObject() }
        o.put("layout", JSONObject().put("zones", JSONObject(zones)).put("style", style)
            .put("names", JSONObject(names.mapKeys { it.key.toString() })).put("jobs", JSONObject(jobs.mapKeys { it.key.toString() })))
        f.writeText(o.toString())
    }
}
