package com.nizam.app.feature.deen.library

import android.content.Context
import com.nizam.app.data.prefs.SettingsRepository
import com.nizam.app.net.Ai
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * The daily Quranic path: one carefully chosen passage a day, read with its tafsir and a matching hadith
 * from the books on the phone, turned into a short lesson and one concrete thing to do today.
 *
 * Lessons are written by AI only from the texts shown with them, and always show those texts and their
 * references. They are reminders and reflections, never rulings: questions of fiqh go to a scholar.
 */
object DailyPath {

    data class Theme(
        val title: String, val s: Int, val from: Int, val to: Int,
        val en: String, val ur: String, val action: String,
    )

    /** A year-round cycle of passages that speak to everyday life. Searched words find a matching hadith. */
    val THEMES = listOf(
        Theme("The conversation in every prayer", 1, 1, 7, "prayer", "نماز", "Pray one salah today slowly, pausing after each ayah of al-Fatiha and meaning it."),
        Theme("He remembers those who remember Him", 2, 152, 157, "patience", "صبر", "Say 'Inna lillahi wa inna ilayhi raji'un' for the next loss, however small, and hold back your complaint."),
        Theme("I am near", 2, 186, 186, "supplication", "دعا", "Make one long, personal dua today in your own language, for something you've never asked for."),
        Theme("Not more than you can bear", 2, 286, 286, "hardship", "مشقت", "Write down the heaviest thing on you now, then recite the last two ayahs of al-Baqarah tonight."),
        Theme("Those who swallow their anger", 3, 133, 136, "anger", "غصہ", "The next time you feel anger rising today, stay silent and change your position (sit if standing)."),
        Theme("Gentleness, counsel, trust", 3, 159, 159, "gentle", "نرمی", "Soften your tone with one person you're usually sharp with. Ask their opinion before deciding something."),
        Theme("Signs for people who reflect", 3, 190, 191, "reflect", "غور", "Spend five minutes tonight looking at the sky with your phone away, and say what these ayahs say."),
        Theme("Justice, even against yourself", 4, 135, 135, "justice", "انصاف", "Admit one mistake you made to the person it affected, without excuses."),
        Theme("Kindness to parents and neighbours", 4, 36, 36, "neighbour", "پڑوسی", "Do one unasked kindness for a neighbour or a relative today."),
        Theme("All of me is for Allah", 6, 162, 163, "intention", "نیت", "Before three ordinary tasks today, renew your intention so they become worship."),
        Theme("Forgive, enjoin good, turn away", 7, 199, 199, "forgive", "معاف", "Let one argument go unanswered today, online or offline."),
        Theme("Do not grieve, Allah is with us", 9, 40, 40, "trust", "توکل", "Hand one worry to Allah in dua, then do the one practical step you've been avoiding."),
        Theme("Hearts find rest", 13, 28, 28, "remembrance", "ذکر", "Replace ten minutes of scrolling with dhikr: SubhanAllah, Alhamdulillah, Allahu Akbar 33 times each."),
        Theme("If you are grateful, I will increase you", 14, 7, 7, "grateful", "شکر", "Write down three specific blessings from today before you sleep, and thank one person by name."),
        Theme("Justice, excellence and giving to relatives", 16, 90, 90, "kinship", "رشتہ", "Call a relative you haven't spoken to in a month."),
        Theme("Invite with wisdom and good words", 16, 125, 125, "wisdom", "حکمت", "If you need to correct someone today, do it privately and gently, starting with something good about them."),
        Theme("Not even 'uff' to your parents", 17, 23, 24, "parents", "والدین", "Serve your parents with your own hands today, and make the dua 'Rabbir-hamhuma kama rabbayani saghira'."),
        Theme("Don't follow what you don't know", 17, 36, 36, "knowledge", "علم", "Before forwarding any message today, verify it or don't send it."),
        Theme("Don't walk the earth arrogantly", 17, 37, 37, "arrogance", "تکبر", "Greet first today: say salam first to everyone you meet, including those 'below' you."),
        Theme("Say 'In sha Allah'", 18, 23, 24, "Allah wills", "ان شاء اللہ", "Say 'In sha Allah' consciously for every plan you mention today, and mean it."),
        Theme("What remains", 18, 46, 46, "good deeds", "نیکی", "Do one good deed today that nobody will ever know about."),
        Theme("Keep company with those who call on their Lord", 18, 28, 28, "companion", "صحبت", "Spend time with someone who reminds you of Allah, or listen to a reminder with a friend."),
        Theme("Musa's prayer before a hard task", 20, 25, 28, "ease", "آسانی", "Recite 'Rabbish-rahli sadri...' before your hardest conversation or task today."),
        Theme("Yunus's prayer from the darkness", 21, 87, 87, "distress", "پریشانی", "Repeat 'La ilaha illa anta subhanaka inni kuntu minaz-zalimin' 100 times today."),
        Theme("Successful are the believers", 23, 1, 11, "successful", "فلاح", "Pick one quality from these ayahs you lack and practise it all day."),
        Theme("Don't you love that Allah forgives you?", 24, 22, 22, "pardon", "درگزر", "Forgive someone in your heart today and make dua for them by name."),
        Theme("The servants of the Most Merciful", 25, 63, 67, "humble", "عاجزی", "Pray two rak'ahs at night before sleeping, even if short."),
        Theme("Evil deeds turned into good", 25, 68, 70, "repent", "توبہ", "Make sincere tawbah for one specific sin and remove one thing that leads you to it."),
        Theme("Do people think they won't be tested?", 29, 2, 3, "test", "آزمائش", "Name today's test to yourself, then ask: what would patience look like here?"),
        Theme("Prayer restrains from evil", 29, 45, 45, "prayer", "نماز", "Pray every salah on time today; set alarms now."),
        Theme("Luqman's advice to his son", 31, 12, 19, "advice", "نصیحت", "Lower your voice in every conversation today, and give one piece of sincere advice."),
        Theme("The best example", 33, 21, 21, "example", "اسوہ", "Revive one sunnah today you usually skip: miswak, right-hand eating, or smiling at others."),
        Theme("Never despair of Allah's mercy", 39, 53, 53, "mercy", "رحمت", "Return to one good habit you dropped. Start small, but start today."),
        Theme("Call on Me, I will answer", 40, 60, 60, "supplication", "دعا", "Make dua in sujood today for three things: one for you, one for family, one for the ummah."),
        Theme("Repel evil with what is better", 41, 34, 35, "good", "بھلائی", "Answer one rude person today with kindness."),
        Theme("Forgiving even when angry", 42, 36, 43, "forgive", "معاف", "Decide one important matter today by consulting the people it affects."),
        Theme("Verify the news", 49, 6, 6, "verify", "تحقیق", "Check the source of the next news you hear before believing or sharing it."),
        Theme("Brotherhood, mockery, suspicion, backbiting", 49, 10, 12, "backbiting", "غیبت", "Go the whole day without saying anything about anyone who isn't present."),
        Theme("Created in nations to know one another", 49, 13, 13, "piety", "تقوی", "Treat the person you look down on most today exactly as you would someone you respect."),
        Theme("Closer than the jugular vein", 50, 16, 18, "tongue", "زبان", "Before you speak today, ask: would I want this recorded? (It is.)"),
        Theme("Which of His favours will you deny?", 55, 1, 13, "blessings", "نعمت", "Recite or listen to Surah ar-Rahman and pause at every refrain to name one blessing."),
        Theme("Nothing happens except it is written", 57, 22, 23, "decree", "تقدیر", "When something goes wrong today, say 'Qaddarallahu wa ma sha'a fa'al' and move on."),
        Theme("The world is play and amusement", 57, 20, 20, "world", "دنیا", "Give away one thing you own but don't use."),
        Theme("Look at what you've sent ahead", 59, 18, 19, "hereafter", "آخرت", "Take five minutes tonight for muhasabah: what did I send ahead today?"),
        Theme("When the call to Jumu'ah is made", 62, 9, 11, "Friday", "جمعہ", "On Friday, go early to Jumu'ah, recite al-Kahf, and send abundant salawat on the Prophet ﷺ."),
        Theme("As much as you are able", 64, 16, 16, "able", "طاقت", "Choose one act of worship you can keep up daily, however small, and do it today."),
        Theme("A way out, and provision unexpected", 65, 2, 3, "provision", "رزق", "Leave one doubtful source of income or habit, trusting Allah with the gap."),
        Theme("Who is best in deeds", 67, 1, 2, "deeds", "اعمال", "Do your ordinary work today with ihsan, as if it is being presented to Allah."),
        Theme("Stand in the night", 73, 1, 8, "night prayer", "تہجد", "Wake 15 minutes before Fajr for tahajjud, even two rak'ahs."),
        Theme("Your Lord has not abandoned you", 93, 1, 11, "orphan", "یتیم", "Help someone in need today, and speak about one of Allah's favours on you."),
        Theme("With hardship comes ease", 94, 1, 8, "ease", "آسانی", "When you finish a task today, turn to the next good work, and to your Lord."),
        Theme("An atom's weight", 99, 7, 8, "small deed", "چھوٹی نیکی", "Do three tiny good deeds today: remove something harmful from a path, smile, give water."),
        Theme("By time", 103, 1, 3, "time", "وقت", "Plan tomorrow tonight: one hour for Allah, one for learning, one for someone else."),
        Theme("Small kindnesses", 107, 1, 7, "kindness", "مہربانی", "Lend or give something small today without being asked."),
        Theme("Say: He is Allah, One", 112, 1, 4, "oneness", "توحید", "Recite al-Ikhlas three times after each salah today, reflecting on one name of Allah."),
        Theme("A kind word is better", 2, 261, 263, "charity", "صدقہ", "Give sadaqah today, even small, and never mention it."),
        Theme("What righteousness really is", 2, 177, 177, "righteous", "نیک", "Keep one promise today that you've been postponing."),
        Theme("Seek help through patience and prayer", 2, 45, 46, "patience", "صبر", "Before your next big decision, pray two rak'ahs of istikhara."),
        Theme("Every soul will taste death", 3, 185, 185, "death", "موت", "Visit a graveyard or remember someone who has passed, and make dua for them."),
        Theme("Don't weaken, don't grieve", 3, 139, 139, "grief", "غم", "Write one thing you're grieving, then one way Allah has carried you before."),
        Theme("Hold firmly together", 3, 103, 103, "unity", "اتحاد", "Reconcile with one person you've drifted from, starting with a salam message."),
        Theme("Hearts that tremble", 8, 2, 4, "faith", "ایمان", "Listen to the Quran for 15 minutes today with full attention and nothing else."),
        Theme("Good deeds erase bad ones", 11, 114, 115, "erase", "مٹانا", "After any slip today, immediately follow it with a good deed."),
        Theme("I only complain to Allah", 12, 86, 87, "complain", "شکایت", "Pour out what hurts to Allah alone today, and to no one else."),
        Theme("You may dislike what is good for you", 2, 216, 216, "good for you", "بہتر", "Accept one outcome you didn't want today with 'Alhamdulillah ala kulli hal'."),
        Theme("If you help Allah's cause, He helps you", 47, 7, 7, "help", "مدد", "Spend 20 minutes today helping a good cause, with your time, skill or money."),
        Theme("A sound heart", 26, 88, 89, "heart", "دل", "Remove one grudge, envy or ill thought from your heart today, and pray for that person."),
        Theme("The ten commandments of al-An'am", 6, 151, 153, "commandments", "احکام", "Check your day against these ayahs tonight; fix one thing tomorrow."),
        Theme("Whoever does wrong then seeks forgiveness", 4, 110, 110, "forgiveness", "استغفار", "Say 'Astaghfirullah' 100 times today, slowly."),
        Theme("Patience, perseverance, steadfastness", 3, 200, 200, "steadfast", "ثابت قدم", "Keep one hard commitment today no matter how you feel."),
    )

    fun dayIndex(date: LocalDate = LocalDate.now()) = date.toEpochDay().toInt()
    fun themeFor(date: LocalDate = LocalDate.now()): Theme = THEMES[dayIndex(date).mod(THEMES.size)]

    data class Sources(
        val theme: Theme,
        val arabic: List<Pair<Int, String>>,
        val translation: List<Pair<Int, String>>,
        val translationName: String,
        val tafsir: String?, val tafsirName: String?,
        val hadith: DeenLibrary.Hadith?, val hadithName: String?,
    )

    /** Everything for today's passage, read from the downloaded books only. */
    fun sources(c: Context, theme: Theme, urdu: Boolean): Sources {
        val qp = DeenLibrary.quranPacks(c)
        val arabic = qp.firstOrNull { it.lang == "Arabic" && !it.slug.endsWith("-la") && !it.slug.contains("-la") }
        val trans = qp.filter { it.lang != "Arabic" }.let { list -> list.firstOrNull { (it.lang == "Urdu") == urdu } ?: list.firstOrNull() }
        fun verses(p: DeenLibrary.Pack?) = p?.let { pk -> (theme.from..theme.to).mapNotNull { a -> DeenLibrary.ayahText(c, pk.slug, theme.s, a)?.let { a to it } } }.orEmpty()

        val tp = DeenLibrary.tafsirPacks(c).let { list -> list.firstOrNull { (it.lang == "Urdu") == urdu } ?: list.firstOrNull() }
        val tafsir = tp?.let { p ->
            (theme.from..theme.to).mapNotNull { a -> DeenLibrary.tafsir(c, p.slug, theme.s, a) }.distinctBy { it.first }
                .joinToString("\n\n") { it.second }.take(6000).ifBlank { null }
        }

        val hp = DeenLibrary.hadithPacks(c)
        val hadithPacks = hp.sortedBy { if ((it.lang == "Urdu") == urdu) 0 else 1 }
        var hadith: DeenLibrary.Hadith? = null
        var hadithName: String? = null
        for (p in hadithPacks) {
            val word = if (p.lang == "Urdu") theme.ur else theme.en
            val hits = DeenLibrary.search(c, word, 200).filter { it.ed == p.slug && it.ref.startsWith("h:") }
            if (hits.isNotEmpty()) {
                val pick = hits[dayIndex().mod(hits.size)]
                hadith = DeenLibrary.hadithAt(c, p.slug, pick.ref.removePrefix("h:").toInt())
                hadithName = p.name
                break
            }
        }
        return Sources(theme, verses(arabic), verses(trans), trans?.name ?: "", tafsir, tp?.name, hadith, hadithName)
    }

    // ---------------------------------------------------------------- lesson, journal, streak

    private fun prefs(c: Context) = c.getSharedPreferences("deen_path", Context.MODE_PRIVATE)

    fun cachedLesson(c: Context, date: LocalDate = LocalDate.now()) = prefs(c).getString("lesson_$date", null)

    private const val GUARD = """You are a careful Islamic teacher writing for one Muslim's daily reminder.
Rules you must follow:
- Use ONLY the Quran text, tafsir and hadith given below. Quote them and name the reference (surah:ayah, book and number).
- Never invent or paraphrase a hadith that is not given, never attribute words to scholars that aren't in the tafsir given.
- Give no fatwas or rulings. If something needs a ruling, say: ask a qualified scholar.
- Be warm, direct and practical, like a sincere friend who knows the deen. No exaggeration, no fear-mongering."""

    suspend fun lesson(c: Context, settings: SettingsRepository, src: Sources, urdu: Boolean, refresh: Boolean = false): String {
        val key = "lesson_${LocalDate.now()}"
        if (!refresh) prefs(c).getString(key, null)?.let { return it }
        val t = src.theme
        val prompt = buildString {
            append("Today's theme: ${t.title}\nPassage: Surah ${Surahs.get(t.s).name} ${t.s}:${t.from}")
            if (t.to != t.from) append("-${t.to}")
            append("\n\nArabic:\n"); src.arabic.forEach { (a, x) -> append("($a) $x\n") }
            append("\nTranslation (${src.translationName}):\n"); src.translation.forEach { (a, x) -> append("($a) $x\n") }
            src.tafsir?.let { append("\nTafsir (${src.tafsirName}):\n$it\n") }
            src.hadith?.let { append("\nHadith (${src.hadithName} ${it.n}):\n${it.text.take(1500)}\n") }
            append("\nSuggested action: ${t.action}\n\n")
            append("Write today's lesson in ${if (urdu) "Urdu (simple, clear Urdu script)" else "English"} with these headings:\n")
            append("1. The message (3-4 sentences, quoting the key ayah)\n2. What the tafsir teaches (only from the tafsir given)\n")
            append("3. From the Sunnah (only the hadith given, if any)\n4. Three questions to ask myself today\n")
            append("5. Today's mission (one concrete action I can finish today; you may refine the suggested one)\n6. A dua to carry (from the passage if possible)\n")
            append("Keep it under 450 words.")
        }
        val out = Ai.generate(settings, prompt, GUARD, maxOutputTokens = 1400)
        prefs(c).edit().putString(key, out).apply()
        return out
    }

    /** A Jumu'ah-style khutbah drawn from this week's passages, for reading or listening. */
    suspend fun khutbah(c: Context, settings: SettingsRepository, urdu: Boolean, refresh: Boolean = false): String {
        val friday = LocalDate.now().let { d -> d.minusDays(((d.dayOfWeek.value - DayOfWeek.FRIDAY.value + 7) % 7).toLong()) }
        val key = "khutbah_$friday"
        if (!refresh) prefs(c).getString(key, null)?.let { return it }
        val week = (0..6).map { themeFor(friday.minusDays(it.toLong())) }.distinct()
        val src = week.take(4).map { sources(c, it, urdu) }
        val prompt = buildString {
            append("Write a Friday reminder (khutbah-style talk, about 8 minutes when read aloud) tying together these passages from this week.\n\n")
            src.forEach { s ->
                append("== ${s.theme.title} (${s.theme.s}:${s.theme.from}${if (s.theme.to != s.theme.from) "-${s.theme.to}" else ""})\n")
                s.translation.forEach { (a, x) -> append("($a) $x\n") }
                s.tafsir?.let { append("Tafsir (${s.tafsirName}): ${it.take(1500)}\n") }
                s.hadith?.let { append("Hadith (${s.hadithName} ${it.n}): ${it.text.take(700)}\n") }
                append("\n")
            }
            append("Structure: opening praise and salawat; one clear central message; the passages explained with their references; ")
            append("a story or example only if it is in the texts given; practical steps for the coming week; closing dua. ")
            append("Write in ${if (urdu) "Urdu" else "English"}. Speak directly to the listener.")
        }
        val out = Ai.generate(settings, prompt, GUARD, maxOutputTokens = 2600)
        prefs(c).edit().putString(key, out).apply()
        return out
    }

    /** A lecture on any topic the user asks for, built from what the offline search finds in the books. */
    suspend fun lecture(c: Context, settings: SettingsRepository, topic: String, urdu: Boolean): String {
        val hits = DeenLibrary.search(c, topic, 40)
        if (hits.isEmpty()) throw IllegalStateException("Nothing about \"$topic\" in your downloaded books yet. Try other words, or download more books.")
        val quran = hits.filter { it.ref.startsWith("q:") }.take(8).mapNotNull { h ->
            val (_, s, a) = h.ref.split(":")
            DeenLibrary.ayahText(c, h.ed, s.toInt(), a.toInt())?.let { "($s:$a, ${DeenLibrary.packName(c, h.ed)}) $it" }
        }
        val hadith = hits.filter { it.ref.startsWith("h:") }.take(6).mapNotNull { h ->
            DeenLibrary.hadithAt(c, h.ed, h.ref.removePrefix("h:").toInt())?.let { "(${DeenLibrary.packName(c, h.ed)} ${it.n}) ${it.text.take(900)}" }
        }
        val prompt = buildString {
            append("Topic: $topic\n\nQuran:\n"); quran.forEach { append(it).append('\n') }
            append("\nHadith:\n"); hadith.forEach { append(it).append('\n') }
            append("\nWrite a heartfelt, structured lecture on this topic in ${if (urdu) "Urdu" else "English"} (about 600 words) using only these texts, ")
            append("citing each one you use. Skip any text that isn't actually relevant. End with three practical steps and a dua.")
        }
        return Ai.generate(settings, prompt, GUARD, maxOutputTokens = 2000)
    }

    fun isDone(c: Context, date: LocalDate = LocalDate.now()) = prefs(c).getBoolean("done_$date", false)

    fun markDone(c: Context, reflection: String) {
        val today = LocalDate.now()
        prefs(c).edit().putBoolean("done_$today", true).putString("note_$today", reflection).apply()
    }

    fun reflection(c: Context, date: LocalDate = LocalDate.now()) = prefs(c).getString("note_$date", "").orEmpty()

    fun streak(c: Context): Int {
        var d = LocalDate.now().let { if (isDone(c, it)) it else it.minusDays(1) }
        var n = 0
        while (isDone(c, d)) { n++; d = d.minusDays(1) }
        return n
    }

    /** Past reflections, newest first, for the journal. */
    fun journal(c: Context, days: Int = 60): List<Triple<LocalDate, Theme, String>> =
        (0 until days).map { LocalDate.now().minusDays(it.toLong()) }
            .filter { isDone(c, it) }
            .map { Triple(it, themeFor(it), reflection(c, it)) }

    // reading plan: a steady number of ayahs a day, remembered between days
    fun planPerDay(c: Context) = prefs(c).getInt("plan_per_day", 20)
    fun setPlanPerDay(c: Context, n: Int) = prefs(c).edit().putInt("plan_per_day", n.coerceIn(5, 300)).apply()
    fun planPosition(c: Context) = prefs(c).getInt("plan_pos", 0)
    fun advancePlan(c: Context) = prefs(c).edit().putInt("plan_pos", (planPosition(c) + planPerDay(c)) % 6236)
        .putInt("plan_khatams", prefs(c).getInt("plan_khatams", 0) + if (planPosition(c) + planPerDay(c) >= 6236) 1 else 0).apply()
    fun khatams(c: Context) = prefs(c).getInt("plan_khatams", 0)

    fun bookmark(c: Context, s: Int, a: Int) {
        val o = JSONObject(prefs(c).getString("bookmarks", "{}") ?: "{}")
        val k = "$s:$a"
        if (o.has(k)) o.remove(k) else o.put(k, System.currentTimeMillis())
        prefs(c).edit().putString("bookmarks", o.toString()).apply()
    }
    fun bookmarks(c: Context): Set<String> = JSONObject(prefs(c).getString("bookmarks", "{}") ?: "{}").keys().asSequence().toSet()
}
