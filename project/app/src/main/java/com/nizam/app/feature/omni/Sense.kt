package com.nizam.app.feature.omni

import android.accessibilityservice.AccessibilityService
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.nizam.app.feature.next.JsonStore
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** What the phone notices, through the screen-control service, only when you've switched each sense on. */
object Sense {
    private val PRIVATE = listOf("bank", "wallet", "pay", "jazzcash", "easypaisa", "sadapay", "nayapay", "meezan", "hbl", "ubl", "mcb", "alfalah", "binance",
        "authenticator", "password", "bitwarden", "lastpass", "whatsapp", "messenger", "telegram", "signal", "messaging", "mms", "gmail", "outlook", "inputmethod", "systemui", "keyboard")
    private var lastPkg = ""; private var lastCapture = HashMap<String, Long>()
    fun onEvent(svc: AccessibilityService, e: AccessibilityEvent) {
        val p = e.packageName?.toString() ?: return
        if (p == svc.packageName) return
        if (Aura.on(svc)) Aura.event(svc, p, e.eventType)
        if (ScreenMemory.on(svc) && PRIVATE.none { p.lowercase().contains(it) } && ScreenMemory.excluded(svc).none { p.contains(it, true) }) {
            val now = System.currentTimeMillis()
            if (now - (lastCapture[p] ?: 0) > 15_000 || p != lastPkg) { lastCapture[p] = now; lastPkg = p; runCatching { ScreenMemory.capture(svc, p) } }
        }
    }
}

/** Screen memory: privately remembers the text you see in other apps, on this phone only, so you can ask about it later. */
object ScreenMemory {
    private fun p(c: Context) = c.getSharedPreferences("omni_screenmem", 0)
    fun on(c: Context) = p(c).getBoolean("on", false)
    fun setOn(c: Context, v: Boolean) = p(c).edit().putBoolean("on", v).apply()
    fun excluded(c: Context) = p(c).getString("excluded", "")!!.split(',').map { it.trim() }.filter { it.isNotBlank() }
    fun setExcluded(c: Context, s: String) = p(c).edit().putString("excluded", s).apply()
    fun store(c: Context) = JsonStore(c, "omni_screenmem", sealed = true)
    private var lastHash = 0

    fun capture(svc: AccessibilityService, pkg: String) {
        val root = svc.rootInActiveWindow ?: return
        val sb = StringBuilder()
        fun walk(n: AccessibilityNodeInfo?, d: Int) { if (n == null || d > 30 || sb.length > 3000) return
            if (n.isPassword) return
            n.text?.toString()?.trim()?.takeIf { it.length in 2..300 }?.let { sb.append(it).append(" · ") }
            for (i in 0 until n.childCount) walk(n.getChild(i), d + 1) }
        walk(root, 0)
        val text = sb.toString().trim(); if (text.length < 20) return
        val h = text.hashCode(); if (h == lastHash) return; lastHash = h
        val app = runCatching { svc.packageManager.getApplicationLabel(svc.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrElse { pkg }
        val s = store(svc)
        val all = s.all()
        s.save((all + JSONObject().put("id", System.nanoTime().toString()).put("t", System.currentTimeMillis()).put("app", app).put("text", text.take(3000)))
            .filter { System.currentTimeMillis() - it.optLong("t") < 14 * 86_400_000L }.takeLast(2500))
    }

    fun search(c: Context, q: String): List<JSONObject> {
        val words = q.lowercase().split(Regex("\\W+")).filter { it.length > 2 && it !in setOf("what", "the", "was", "that", "saw", "where", "when", "which", "did") }
        return store(c).all().map { it to words.count { w -> it.optString("text").lowercase().contains(w) || it.optString("app").lowercase().contains(w) } }
            .filter { it.second > 0 }.sortedWith(compareByDescending<Pair<JSONObject, Int>> { it.second }.thenByDescending { it.first.optLong("t") }).take(12).map { it.first }
    }
}

/** Phone aura: a private sense of how you're doing from how you use the phone (unlocks, app-hopping, feed scrolling, late nights). */
object Aura {
    private fun p(c: Context) = c.getSharedPreferences("omni_aura", 0)
    fun on(c: Context) = p(c).getBoolean("on", false)
    fun setOn(c: Context, v: Boolean) = p(c).edit().putBoolean("on", v).apply()
    private val FEEDS = listOf("instagram", "facebook", "tiktok", "youtube", "twitter", "x.android", "reddit", "snapchat", "likee", "threads")
    private var lastPkg = ""; private var switches = ArrayDeque<Long>(); private var feedTicks = ArrayDeque<Long>(); private var feedSince = 0L

    fun event(c: Context, pkg: String, type: Int) {
        val now = System.currentTimeMillis()
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != lastPkg) { switches.addLast(now); lastPkg = pkg
            feedSince = if (FEEDS.any { pkg.contains(it) }) now else 0L }
        if (type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && FEEDS.any { pkg.contains(it) }) feedTicks.addLast(now)
        while (switches.isNotEmpty() && now - switches.first() > 15 * 60_000L) switches.removeFirst()
        while (feedTicks.isNotEmpty() && now - feedTicks.first() > 5 * 60_000L) feedTicks.removeFirst()
        // the right moment: deep in a feed for a long time, fast, late
        if (feedSince > 0 && now - feedSince > 25 * 60_000L && now - p(c).getLong("nudged", 0) > 90 * 60_000L) {
            p(c).edit().putLong("nudged", now).apply()
            O.notify(c, 5850, "You've been scrolling for ${(now - feedSince) / 60_000} minutes", if (LocalTime.now().hour >= 23) "It's late. Your future self says sleep." else "A 5-minute walk would reset you. Atlas will be here.", "nx/aura")
        }
    }

    fun unlocks(c: Context, date: String): Int = runCatching {
        val d = LocalDate.parse(date); val z = ZoneId.systemDefault()
        val um = c.getSystemService(UsageStatsManager::class.java)
        val ev = um.queryEvents(d.atStartOfDay(z).toInstant().toEpochMilli(), d.plusDays(1).atStartOfDay(z).toInstant().toEpochMilli())
        var n = 0; val e = UsageEvents.Event()
        while (ev.hasNextEvent()) { ev.getNextEvent(e); if (e.eventType == UsageEvents.Event.KEYGUARD_HIDDEN) n++ }
        n
    }.getOrDefault(0)

    /** 0 calm … 100 frazzled, with the reasons. */
    fun now(c: Context): Pair<Int, List<String>> {
        val why = mutableListOf<String>(); var s = 15
        val sw = switches.size; if (sw > 25) { s += 25; why += "Hopping between apps a lot ($sw switches in 15 min)" } else if (sw > 12) { s += 12; why += "Switching apps often" }
        val ft = feedTicks.size; if (ft > 200) { s += 25; why += "Fast feed scrolling" } else if (ft > 80) { s += 10; why += "Scrolling feeds" }
        val un = unlocks(c, LocalDate.now().toString()); val h = LocalTime.now().hour.coerceAtLeast(1)
        if (un / h > 8) { s += 15; why += "Unlocked the phone $un times today" }
        if (LocalTime.now().hour >= 0 && LocalTime.now().hour < 5) { s += 15; why += "Awake and on the phone very late" }
        if (why.isEmpty()) why += "Steady. Nothing unusual right now."
        return s.coerceIn(0, 100) to why
    }
}

@Composable
fun ScreenMemoryScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var on by remember { mutableStateOf(ScreenMemory.on(c)) }
    var ex by remember { mutableStateOf(ScreenMemory.excluded(c).joinToString(", ")) }
    var q by remember { mutableStateOf("") }; var ans by remember { mutableStateOf("") }; var hits by remember { mutableStateOf<List<JSONObject>>(emptyList()) }; var busy by remember { mutableStateOf(false) }
    Column {
        OCard { OTitle("Screen memory")
            OMuted("When on, Atlas privately remembers the text on screens you look at in other apps, encrypted on this phone, for 14 days. Banking, payment, password apps, chats, email and keyboards are never recorded. Needs Atlas screen control on.")
            androidx.compose.foundation.layout.Row { OText("Remember what I see  "); Switch(on, { on = it; ScreenMemory.setOn(c, it) }) }
            OField(ex, "Also never record (app names, comma separated)") { ex = it; ScreenMemory.setExcluded(c, it) }
            OMuted("${ScreenMemory.store(c).all().size} moments remembered")
            OBtn2("Forget everything") { ScreenMemory.store(c).save(emptyList()); com.nizam.app.ui.components.Feedback.show("Forgotten") } }
        OCard { OField(q, "What did I see? e.g. that shoe price on Daraz") { q = it }
            OBtn(if (busy) "Looking…" else "Ask", q.isNotBlank() && !busy) { busy = true; scope.launch {
                hits = ScreenMemory.search(c, q)
                ans = if (hits.isEmpty()) "Nothing like that in the last 14 days." else O.ai(c, "Question: $q\nThings the user saw on their phone (app, time, text):\n" +
                    hits.joinToString("\n") { "[${it.optString("app")} ${java.text.SimpleDateFormat("EEE d MMM HH:mm").format(java.util.Date(it.optLong("t")))}] ${it.optString("text").take(600)}" } +
                    "\nAnswer from these only, say which app and when.", "recorded data, shared by them. Answer briefly.", 500)
                busy = false } }
            if (ans.isNotBlank()) OMd(ans) }
        hits.take(6).forEach { h -> OCard { OMuted("${h.optString("app")} · ${java.text.SimpleDateFormat("EEE d MMM, HH:mm").format(java.util.Date(h.optLong("t")))}"); OText(h.optString("text").take(300)) } }
    }
}

@Composable
fun AuraScreen() {
    val c = LocalContext.current
    var on by remember { mutableStateOf(Aura.on(c)) }
    var v by remember { mutableIntStateOf(0) }
    val (score, why) = remember(v) { Aura.now(c) }
    Column {
        OCard { OTitle("Phone aura"); OMuted("A private read of how you're doing from how you use the phone, never what you read. It nudges at the right moment: deep in a feed, late at night, hopping between apps.")
            androidx.compose.foundation.layout.Row { OText("Sense my aura  "); Switch(on, { on = it; Aura.setOn(c, it) }) } }
        OCard {
            val label = when { score < 30 -> "Calm"; score < 55 -> "Busy"; score < 75 -> "Scattered"; else -> "Frazzled" }
            OTitle("$label · $score"); OBar(score / 100f, when { score < 30 -> androidx.compose.ui.graphics.Color(0xFF4FC3A1); score < 55 -> androidx.compose.ui.graphics.Color(0xFFFFC37A); else -> androidx.compose.ui.graphics.Color(0xFFFF6B6B) })
            why.forEach { OText("• $it") }
            if (score >= 55) ORow { OBtn("Calm mode (do not disturb 30 min)") { kotlinx.coroutines.MainScope().launch { com.nizam.app.feature.agent.Actions.run(O.container(c), "do_not_disturb", JSONObject().put("on", true))
                com.nizam.app.feature.agent.Actions.run(O.container(c), "shield_on", JSONObject().put("minutes", 30)) } } }
            OBtn2("Refresh") { v++ }
        }
        OMuted("Unlock counts need Usage access (the same permission as Screen time).")
    }
}
