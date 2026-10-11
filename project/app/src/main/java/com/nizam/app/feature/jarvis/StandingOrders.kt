package com.nizam.app.feature.jarvis

import android.content.Context
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.net.Ai
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Standing orders: plain-language rules that fire on real events, not just on a clock.
 *
 *   "Whenever my bank texts about a spend over 10,000, log it and warn me if I'm over budget."
 *   "When Ali messages me on WhatsApp, tell me what he wants in one line."
 *   "Every time I open Instagram after 11pm, remind me I have Fajr."
 *
 * The AI compiles the sentence once into a trigger (event + app + keywords + threshold + hours).
 * Matching then runs on the phone for every event — free and instant — and the agent is only
 * woken when an order actually matches.
 */
data class Order(
    val id: Long,
    val text: String,              // what the user said
    val event: String,             // notification | app_open
    val apps: List<String>,        // package or app-name fragments; empty = any
    val keywords: List<String>,    // any of these must appear; empty = any
    val minAmount: Double,         // 0 = no threshold; otherwise a number in the text must reach it
    val fromHour: Int, val toHour: Int,   // active window, 0..24
    val instruction: String,       // what the agent does when it fires
    val auto: Boolean,             // may act without asking
    val enabled: Boolean = true,
    val fired: Int = 0,
    val lastFired: Long = 0,
    val lastResult: String = ""
)

object StandingOrders {
    private fun f(c: Context) = File(c.filesDir, "standing_orders.json")
    private const val COOLDOWN_MS = 90_000L

    fun all(c: Context): List<Order> = runCatching { JSONArray(f(c).readText()) }.getOrElse { JSONArray() }.let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            fun list(k: String) = o.optJSONArray(k)?.let { l -> (0 until l.length()).map { l.optString(it) } } ?: emptyList()
            Order(o.optLong("id"), o.optString("text"), o.optString("event", "notification"), list("apps"), list("keywords"),
                o.optDouble("minAmount", 0.0), o.optInt("fromHour", 0), o.optInt("toHour", 24), o.optString("instruction"),
                o.optBoolean("auto", false), o.optBoolean("enabled", true), o.optInt("fired"), o.optLong("lastFired"),
                o.optString("lastResult"))
        }
    }

    @Synchronized
    fun save(c: Context, list: List<Order>) = f(c).writeText(JSONArray().apply {
        list.forEach { o -> put(JSONObject().put("id", o.id).put("text", o.text).put("event", o.event)
            .put("apps", JSONArray(o.apps)).put("keywords", JSONArray(o.keywords)).put("minAmount", o.minAmount)
            .put("fromHour", o.fromHour).put("toHour", o.toHour).put("instruction", o.instruction).put("auto", o.auto)
            .put("enabled", o.enabled).put("fired", o.fired).put("lastFired", o.lastFired).put("lastResult", o.lastResult)) }
    }.toString())

    /** Turns a sentence into an order. One AI call, at creation time only. */
    suspend fun compile(c: Context, sentence: String, auto: Boolean): Order {
        val raw = Ai.generate(Background.container(c).settingsRepository,
            prompt = """Turn this standing order into a trigger. Reply with JSON only:
{"event":"notification" or "app_open" or "place",
 "apps":["lowercase app name or package fragments, e.g. whatsapp, instagram, hbl, meezan, messages"],
 "keywords":["lowercase words that must appear in the notification, any one is enough; empty if none"],
 "minAmount": number or 0,
 "fromHour": 0-24, "toHour": 0-24,
 "instruction":"what Atlas should do when it fires, written as a direct instruction to Atlas"}
For "place": apps = the saved place name(s) in lowercase (e.g. "office", "gym", "masjid"), and keywords = ["arrived"]
for arriving or ["left"] for leaving.
Bank and SMS alerts arrive as notifications from the SMS app or the bank's app — use keywords like
"debited","spent","purchase","transaction" and leave apps empty unless a bank is named.
Order: "$sentence"""",
            system = "You convert automation requests into strict JSON triggers.", maxOutputTokens = 500)
        val o = JSONObject(Ai.cleanJson(raw))
        fun list(k: String) = o.optJSONArray(k)?.let { l -> (0 until l.length()).map { l.optString(it).lowercase().trim() }
            .filter { it.isNotBlank() } } ?: emptyList()
        return Order(System.currentTimeMillis(), sentence.trim(), o.optString("event", "notification").ifBlank { "notification" },
            list("apps"), list("keywords"), o.optDouble("minAmount", 0.0).takeIf { !it.isNaN() } ?: 0.0,
            o.optInt("fromHour", 0).coerceIn(0, 24), o.optInt("toHour", 24).coerceIn(0, 24),
            o.optString("instruction").ifBlank { sentence }, auto)
    }

    private val NUMBER = Regex("(?:rs\\.?|pkr|\\$|usd)?\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)", RegexOption.IGNORE_CASE)

    private fun matches(o: Order, event: String, app: String, pkg: String, text: String): Boolean {
        if (!o.enabled || o.event != event) return false
        if (System.currentTimeMillis() - o.lastFired < COOLDOWN_MS) return false
        val h = java.time.LocalTime.now().hour
        val inWindow = if (o.fromHour <= o.toHour) h >= o.fromHour && h < o.toHour.coerceAtLeast(o.fromHour + 1)
            else h >= o.fromHour || h < o.toHour           // overnight windows, e.g. 23 → 5
        if (!inWindow) return false
        val a = app.lowercase(); val p = pkg.lowercase(); val t = text.lowercase()
        if (o.apps.isNotEmpty() && o.apps.none { a.contains(it) || p.contains(it) }) return false
        if (o.keywords.isNotEmpty() && o.keywords.none { t.contains(it) }) return false
        if (o.minAmount > 0) {
            val biggest = NUMBER.findAll(text).mapNotNull { it.groupValues[1].replace(",", "").toDoubleOrNull() }.maxOrNull() ?: 0.0
            if (biggest < o.minAmount) return false
        }
        return true
    }

    /** Called for every notification and app switch. Cheap: string matching only. */
    fun onEvent(c: Context, event: String, app: String, pkg: String, text: String) {
        if (pkg == c.packageName) return
        val orders = runCatching { all(c) }.getOrElse { return }
        val hit = orders.filter { matches(it, event, app, pkg, text) }
        if (hit.isEmpty()) return
        // mark fired now so a burst of notifications can't trigger the same order twice
        save(c, orders.map { o -> if (hit.any { it.id == o.id }) o.copy(lastFired = System.currentTimeMillis()) else o })
        hit.forEach { order -> Background.scope.launch { fire(c, order, app, text) } }
    }

    private suspend fun fire(c: Context, order: Order, app: String, text: String) {
        val container = Background.container(c)
        val prompt = "STANDING ORDER from the user: \"${order.text}\"\nIt just fired because of this event in $app:\n\"${text.take(600)}\"\n\n" +
            "Do this now: ${order.instruction}\nBe brief in your reply — it goes into a notification."
        val result = runCatching {
            val out = container.agentLoop.run(prompt, useWeb = false, autoApprove = order.auto)
            buildString {
                append(out.reply.take(350))
                if (out.pendingApproval.isNotEmpty()) append("\nNeeds your OK: " + out.pendingApproval.joinToString { it.first } +
                    " — open Atlas to approve, or switch this order to act on its own.")
            }
        }.getOrElse { "Couldn't run: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
        Notifications.post(c, Notifications.CHANNEL_NUDGE, (300 + order.id % 50).toInt(), "⚡ ${order.text.take(50)}", result)
        Timeline.log(c, "order", "Standing order fired: ${order.text.take(60)}", result.take(200))
        save(c, all(c).map { if (it.id == order.id) it.copy(fired = it.fired + 1, lastResult = result.take(300)) else it })
    }
}
