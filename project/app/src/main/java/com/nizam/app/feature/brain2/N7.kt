package com.nizam.app.feature.brain2

import android.content.Context
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.net.Ai
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt

/** "Where did I put…": things and where they are, found by any word you remember. */
object Places {
    fun store(c: Context) = JsonStore(c, "object_places")

    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 1 && it !in STOP }.toSet()
    private val STOP = setOf("my", "the", "a", "an", "of", "is", "where", "did", "put", "i", "kahan", "hai", "mera", "meri")

    /** Adds or moves an item; the latest place wins, the old one is kept as history. */
    fun remember(c: Context, item: String, place: String, photo: String? = null): JSONObject {
        val existing = find(c, item).firstOrNull()?.takeIf { words(it.optString("item")) == words(item) }
        if (existing != null) {
            store(c).update(existing.optString("id")) { o ->
                o.put("before", o.optString("place")).put("place", place).put("t", System.currentTimeMillis())
                if (photo != null) o.put("photo", photo)
            }
            return store(c).all().first { it.optString("id") == existing.optString("id") }
        }
        return store(c).add(JSONObject().put("item", item.trim()).put("place", place.trim()).put("t", System.currentTimeMillis()).apply { if (photo != null) put("photo", photo) })
    }

    fun find(c: Context, query: String): List<JSONObject> {
        val q = words(query)
        if (q.isEmpty()) return store(c).all().sortedByDescending { it.optLong("t") }
        return store(c).all().map { o -> o to (words(o.optString("item")) intersect q).size * 3 + (words(o.optString("place")) intersect q).size }
            .filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first }
    }

    fun answer(c: Context, query: String): String = find(c, query).firstOrNull()?.let {
        val ago = ((System.currentTimeMillis() - it.optLong("t")) / 86_400_000L).toInt()
        "${it.optString("item")}: ${it.optString("place")}" + when (ago) { 0 -> " (noted today)"; 1 -> " (noted yesterday)"; else -> " (noted $ago days ago)" }
    } ?: "I don't have \"$query\" noted anywhere yet."
}

/**
 * Memory palace: flashcards that come back just before you'd forget them (SM-2 spacing).
 * Atlas can make a deck from any topic or pasted text.
 */
object Cards {
    fun store(c: Context) = JsonStore(c, "memory_cards")
    private fun today() = LocalDate.now().toEpochDay()

    fun decks(c: Context): Map<String, List<JSONObject>> = store(c).all().groupBy { it.optString("deck", "General") }
    fun due(c: Context, deck: String? = null) = store(c).all().filter { (deck == null || it.optString("deck") == deck) && it.optLong("due") <= today() }

    fun add(c: Context, deck: String, front: String, back: String) =
        store(c).add(JSONObject().put("deck", deck).put("front", front).put("back", back).put("due", today()).put("ivl", 0).put("ease", 2.5).put("reps", 0))

    /** grade: 0 forgot, 1 hard, 2 good, 3 easy. */
    fun review(c: Context, card: JSONObject, grade: Int) {
        store(c).update(card.optString("id")) { o ->
            var ease = o.optDouble("ease", 2.5); var ivl = o.optInt("ivl"); var reps = o.optInt("reps")
            if (grade == 0) { reps = 0; ivl = 1; ease = max(1.3, ease - 0.2) }
            else {
                reps++
                ivl = when (reps) { 1 -> if (grade == 3) 3 else 1; 2 -> if (grade == 1) 3 else 6; else -> (ivl * ease * (if (grade == 1) 0.8 else if (grade == 3) 1.3 else 1.0)).roundToInt() }
                ease = max(1.3, ease + (0.1 - (3 - grade) * (0.08 + (3 - grade) * 0.02)))
            }
            o.put("ease", ease).put("ivl", ivl).put("reps", reps).put("due", today() + ivl).put("seen", System.currentTimeMillis())
        }
    }

    suspend fun generate(c: Context, container: AppContainer, deck: String, source: String, count: Int = 12): Int {
        val raw = Ai.generate(container.settingsRepository,
            "Make $count flashcards to learn this. Short question on the front, precise answer on the back (max 25 words). " +
                "Cover the most important ideas first.\n\n${Injection.guard(source.take(8000))}\n\nReply JSON only: {\"cards\":[{\"f\":\"...\",\"b\":\"...\"}]}",
            system = "You are an expert teacher who writes clear, testable flashcards.", maxOutputTokens = 1600)
        val a = JSONObject(Ai.cleanJson(raw)).optJSONArray("cards") ?: return 0
        var n = 0
        for (i in 0 until a.length()) { val o = a.optJSONObject(i) ?: continue
            if (o.optString("f").isNotBlank() && o.optString("b").isNotBlank()) { add(c, deck, o.optString("f"), o.optString("b")); n++ } }
        return n
    }
}

/**
 * Financial twin: your real last 90 days of money, run forward. Change a lever (spend less,
 * save more, a big purchase, a raise) and see where you land month by month.
 */
object Twin {
    data class Base(val income: Double, val spend: Double, val topCats: List<Pair<String, Double>>, val days: Int)
    data class Levers(val start: Double = 0.0, val spendCut: Int = 0, val raise: Int = 0, val extraSave: Double = 0.0,
                      val purchase: Double = 0.0, val purchaseMonth: Int = 6, val returnPct: Double = 0.0, val months: Int = 24)

    suspend fun base(container: AppContainer): Base {
        val now = LocalDate.now()
        val tx = runCatching { container.financeRepository.since(now.minusDays(90).toString()).first() }.getOrDefault(emptyList())
        val first = tx.minOfOrNull { runCatching { LocalDate.parse(it.localDate).toEpochDay() }.getOrDefault(now.toEpochDay()) } ?: now.toEpochDay()
        val days = (now.toEpochDay() - first + 1).toInt().coerceIn(30, 90)
        val perMonth = 30.0 / days
        val exp = tx.filter { it.kind == "EXPENSE" }
        val inc = tx.filter { it.kind == "INCOME" }
        val cats = exp.groupBy { it.category }.mapValues { e -> e.value.sumOf { it.amount } * perMonth }.toList().sortedByDescending { it.second }.take(5)
        return Base(inc.sumOf { it.amount } * perMonth, exp.sumOf { it.amount } * perMonth, cats, days)
    }

    /** Balance at the end of each month, month 0 = today. */
    fun project(b: Base, l: Levers): List<Double> {
        val r = l.returnPct / 1200
        var bal = l.start
        val out = mutableListOf(bal)
        for (m in 1..l.months) {
            val income = b.income * (1 + l.raise / 100.0)
            val spend = b.spend * (1 - l.spendCut / 100.0)
            bal = bal * (1 + r) + income - spend
            if (m == l.purchaseMonth) bal -= l.purchase
            out += bal
        }
        return out
    }

    fun monthsTo(b: Base, l: Levers, goal: Double): Int? = project(b, l.copy(months = 240)).indexOfFirst { it >= goal }.takeIf { it >= 0 }
}

/**
 * Universal inbox: every message waiting in your notification shade, from every app, grouped by
 * person and sorted by what actually needs you. Triage runs on the phone first; AI only if you ask.
 */
object Inbox {
    data class Thread(val who: String, val app: String, val pkg: String, val keys: List<String>, val lines: List<String>, val t: Long, val canReply: Boolean, val tier: Int)

    private val CHAT = listOf("whatsapp", "telegram", "messag", "sms", "signal", "instagram", "messenger", "gmail", "outlook", "slack", "teams", "discord", "viber", "imo")
    private val NOISE = Regex("(?i)(\\d+% off|sale|offer|promo|deal|cashback|win |reward|subscribe|recommended|trending|new video|live now|otp|verification code)")
    private val URGENT = Regex("(?i)(urgent|asap|emergency|call me|jaldi|zaroori|please reply|\\?\\s*$|kahan ho|where are you)")

    fun threads(c: Context): List<Thread> {
        val pm = c.packageManager
        return com.nizam.app.feature.phone.AtlasNotificationListener.active().groupBy { sbn ->
            val ex = sbn.notification.extras
            val title = ex.getCharSequence(android.app.Notification.EXTRA_CONVERSATION_TITLE)?.toString()
                ?: ex.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString().orEmpty()
            sbn.packageName + "|" + title.replace(Regex("\\s*\\(\\d+ messages?\\)"), "")
        }.map { (k, list) ->
            val pkg = k.substringBefore('|'); val who = k.substringAfter('|')
            val app = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg.substringAfterLast('.'))
            val lines = list.flatMap { sbn ->
                val ex = sbn.notification.extras
                ex.getCharSequenceArray(android.app.Notification.EXTRA_TEXT_LINES)?.map { it.toString() }
                    ?: listOfNotNull(ex.getCharSequence(android.app.Notification.EXTRA_BIG_TEXT)?.toString() ?: ex.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString())
            }.filter { it.isNotBlank() }.distinct()
            val reply = list.any { sbn -> sbn.notification.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true }
            val chat = CHAT.any { pkg.contains(it, true) } || reply
            val text = lines.joinToString(" ")
            val tier = when { chat && URGENT.containsMatchIn(text) -> 0; chat -> 1; NOISE.containsMatchIn(text + " " + who) -> 3; else -> 2 }
            Thread(who.ifBlank { app }, app, pkg, list.map { it.key }, lines.takeLast(6), list.maxOf { it.postTime }, reply, tier)
        }.sortedWith(compareBy<Thread> { it.tier }.thenByDescending { it.t })
    }

    suspend fun draft(container: AppContainer, t: Thread): String = Ai.generate(container.settingsRepository,
        "Draft a short reply from me to ${t.who} on ${t.app}. Their messages:\n${Injection.guard(t.lines.joinToString("\n"))}\n" +
            "Match their language (English, Urdu or Roman Urdu) and tone. Reply with the message text only.",
        system = "You write natural, brief replies as the user. Never promise money or share private details.", maxOutputTokens = 200).trim().trim('"')
}
