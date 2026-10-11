package com.nizam.app.feature.jarvis

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nizam.app.AppContainer
import com.nizam.app.core.fmtAmount
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.net.Ai
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private val MESSAGING = listOf("whatsapp", "messag", "telegram", "signal", "messenger", "sms", "mms", "viber", "imo")
private fun isMessaging(pkg: String, app: String) = MESSAGING.any { pkg.contains(it, true) || app.contains(it, true) }
private fun prefs(c: Context) = c.getSharedPreferences("radar", 0)

/** Settings for everything on this page, each off or on by the user's choice. */
object RadarSettings {
    fun on(c: Context, k: String, default: Boolean = false) = prefs(c).getBoolean("on_$k", default)
    fun set(c: Context, k: String, v: Boolean) = prefs(c).edit().putBoolean("on_$k", v).apply()
    fun text(c: Context, k: String, default: String = "") = prefs(c).getString("t_$k", default).orEmpty()
    fun setText(c: Context, k: String, v: String) = prefs(c).edit().putString("t_$k", v).apply()
}

// ── Context recall: when someone messages, what's still open between you ──────────────────────────

object Recall {
    /** Every open loop with this person across promises, tasks, splits and money owed. */
    suspend fun loops(container: AppContainer, person: String): List<String> {
        val c = container.appContext
        val first = person.trim().split(" ").firstOrNull()?.takeIf { it.length >= 3 } ?: return emptyList()
        val out = mutableListOf<String>()
        runCatching {
            val a = JSONArray(File(c.filesDir, "promises.json").readText())
            (0 until a.length()).map { a.getJSONObject(it) }
                .filter { it.optString("state") == "open" && it.optString("who").contains(first, true) }
                .forEach { out += "You promised to ${it.optString("what")}" + it.optString("due").takeIf { d -> d.isNotBlank() }?.let { d -> " (by $d)" }.orEmpty() }
        }
        runCatching {
            container.taskRepository.all().first().filter { it.completedAt == null && it.title.contains(first, true) }
                .take(3).forEach { out += "Task: ${it.title}" }
        }
        return out.distinct().take(4)
    }

    /** From the notification listener. Speaks at most once per person per day, and only when there's something open. */
    fun onMessage(c: Context, app: String, pkg: String, sender: String) {
        if (!RadarSettings.on(c, "recall", true) || !isMessaging(pkg, app)) return
        val who = sender.substringBefore(":").substringBefore("(").trim()
        if (who.length < 3 || who.any { it.isDigit() }) return
        val key = "recall|${LocalDate.now()}|${who.lowercase()}"
        if (prefs(c).getBoolean(key, false)) return
        Background.scope.launch {
            val loops = runCatching { loops(Background.container(c), who) }.getOrElse { emptyList() }
            if (loops.isEmpty()) return@launch
            prefs(c).edit().putBoolean(key, true).apply()
            Notifications.post(c, Notifications.CHANNEL_NUDGE, 700 + (who.hashCode() and 0x3F), "Before you reply to $who",
                loops.joinToString("\n"))
        }
    }
}

// ── Silah rahmi: family you haven't spoken to lately ─────────────────────────────────────────────

object Silah {
    fun family(c: Context): List<String> = RadarSettings.text(c, "family").split(",").map { it.trim() }.filter { it.length >= 2 }

    /** Last time a message from or about them reached the phone, from the private timeline. */
    fun lastContact(c: Context, name: String): LocalDate? =
        Timeline.search(c, name.split(" ").first(), 1).firstOrNull()?.first

    fun status(c: Context): List<Pair<String, Long?>> = family(c).map { n ->
        n to lastContact(c, n)?.let { ChronoUnit.DAYS.between(it, LocalDate.now()) }
    }

    fun remind(c: Context, now: Double) {
        if (now !in 17.0..20.0) return
        val gap = RadarSettings.text(c, "familyDays", "7").toLongOrNull() ?: 7
        status(c).filter { (_, days) -> days == null || days >= gap }.forEach { (name, days) ->
            val key = "silah|${LocalDate.now().with(DayOfWeek.MONDAY)}|$name"   // at most weekly per person
            if (prefs(c).getBoolean(key, false)) return@forEach
            prefs(c).edit().putBoolean(key, true).apply()
            Notifications.post(c, Notifications.CHANNEL_NUDGE, 720 + (name.hashCode() and 0x1F), "Call $name?",
                if (days == null) "No messages from $name in the last two months." else "It's been $days days. A two-minute call is silah rahmi.")
        }
    }
}

// ── Bills and subscriptions: recurring charges, found in your own spending ───────────────────────

data class Recurring(val name: String, val amount: Double, val everyDays: Int, val last: LocalDate, val next: LocalDate, val count: Int)

object BillRadar {
    private fun label(note: String, category: String): String =
        note.substringBefore(" — ").substringBefore(",").replace(Regex("[0-9]+"), "").trim().lowercase().ifBlank { category.lowercase() }

    /** Same payee, similar amount (±15%), at a steady 25–35 day (or weekly / yearly) rhythm. */
    suspend fun find(container: AppContainer): List<Recurring> {
        val since = LocalDate.now().minusDays(400).toString()
        val txns = container.financeRepository.since(since).first().filter { it.kind == "EXPENSE" }
        return txns.groupBy { label(it.note, it.category) }.mapNotNull { (name, list) ->
            if (list.size < 2) return@mapNotNull null
            val sorted = list.sortedBy { it.localDate }
            val dates = sorted.mapNotNull { runCatching { LocalDate.parse(it.localDate) }.getOrNull() }
            if (dates.size < 2) return@mapNotNull null
            val gaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b).toInt() }.filter { it > 0 }
            if (gaps.isEmpty()) return@mapNotNull null
            val avgGap = gaps.average()
            val steady = gaps.all { kotlin.math.abs(it - avgGap) <= maxOf(4.0, avgGap * 0.2) }
            val cycle = avgGap in 6.0..8.0 || avgGap in 25.0..35.0 || avgGap in 350.0..380.0
            val amounts = sorted.map { it.amount }
            val avgAmt = amounts.average()
            val similar = amounts.all { kotlin.math.abs(it - avgAmt) <= avgAmt * 0.15 }
            if (!(steady && cycle && similar)) return@mapNotNull null
            val every = avgGap.toInt()
            val last = dates.last()
            Recurring(name.replaceFirstChar { it.uppercase() }, amounts.last(), every, last, last.plusDays(every.toLong()), list.size)
        }.filter { !it.next.isBefore(LocalDate.now().minusDays(10)) }.sortedBy { it.next }
    }

    suspend fun remind(c: Context, container: AppContainer, now: Double) {
        if (now !in 9.0..11.0 || !RadarSettings.on(c, "bills", true)) return
        find(container).filter { ChronoUnit.DAYS.between(LocalDate.now(), it.next) in 0..2 }.forEach { r ->
            val key = "bill|${r.name}|${r.next}"
            if (prefs(c).getBoolean(key, false)) return@forEach
            prefs(c).edit().putBoolean(key, true).apply()
            Notifications.post(c, Notifications.CHANNEL_NUDGE, 740 + (r.name.hashCode() and 0x1F), "${r.name} due ${r.next}",
                "Usually ${fmtAmount(r.amount)} every ${r.everyDays} days. Still want it? Now's the time to cancel.")
        }
    }
}

// ── Busy auto-reply: during prayer or a focus session, people hear back instead of wondering ─────

object AutoReply {
    suspend fun onMessage(c: Context, app: String, pkg: String, sender: String, canReply: Boolean) {
        if (!RadarSettings.on(c, "autoreply") || !canReply || !isMessaging(pkg, app)) return
        val who = sender.substringBefore(":").trim().ifBlank { return }
        val container = Background.container(c)
        val now = java.time.LocalTime.now()
        val inPrayer = runCatching { SalahGuard.windows(container, LocalDate.now()).any { (_, from, to) -> !now.isBefore(from) && now.isBefore(to) } }
            .getOrElse { false }
        val focusing = com.nizam.app.feature.shield.Shield.isActive(c)
        if (!inPrayer && !focusing) return
        val key = "auto|${who.lowercase()}"
        if (System.currentTimeMillis() - prefs(c).getLong(key, 0) < 2 * 60 * 60_000L) return    // once per person per two hours
        prefs(c).edit().putLong(key, System.currentTimeMillis()).apply()
        val text = if (inPrayer) RadarSettings.text(c, "prayerReply", "At prayer right now — I'll get back to you shortly.")
            else RadarSettings.text(c, "focusReply", "In a focus session — I'll reply when I'm done.")
        val r = com.nizam.app.feature.phone.AtlasNotificationListener.reply(c, who, text)
        Timeline.log(c, "message", "Auto-replied to $who", r)
    }
}

// ── The overnight briefing: prepared before Fajr, instant when you wake ──────────────────────────

object Briefing {
    private fun f(c: Context) = File(c.filesDir, "briefing.json")
    fun today(c: Context): String? = runCatching { JSONObject(f(c).readText()) }.getOrNull()
        ?.takeIf { it.optString("date") == LocalDate.now().toString() }?.optString("text")

    suspend fun prepare(c: Context, container: AppContainer): String {
        val events = runCatching { com.nizam.app.feature.phone.PhoneActions.readEvents(c, 1).message }.getOrElse { "" }
        val bills = runCatching { BillRadar.find(container).filter { ChronoUnit.DAYS.between(LocalDate.now(), it.next) in 0..3 } }.getOrElse { emptyList() }
        val waiting = Autopilot.all(c).filter { it.status == "waiting" }
        val due = runCatching { com.nizam.app.feature.brain.DecisionStore(c).due() }.getOrElse { emptyList() }
        val text = Ai.generate(container.settingsRepository,
            prompt = com.nizam.app.feature.agent.Snapshot.of(container) + "\n\nCalendar today: $events\n" +
                (if (bills.isNotEmpty()) "Bills due soon: " + bills.joinToString { "${it.name} ${fmtAmount(it.amount)} on ${it.next}" } + "\n" else "") +
                (if (waiting.isNotEmpty()) "Autopilots waiting on them: " + waiting.joinToString { "${it.goal}: ${it.question}" } + "\n" else "") +
                (if (due.isNotEmpty()) "Decisions due for review: " + due.joinToString { it.decision } + "\n" else "") +
                "\nWrite their morning briefing for today in under 120 words: the one thing that matters most, what's on, what's due, " +
                "and one line of encouragement. Short lines, no headings, no filler.",
            system = "You are a calm chief of staff writing a morning brief.", maxOutputTokens = 500)
        f(c).writeText(JSONObject().put("date", LocalDate.now().toString()).put("text", text.trim()).toString())
        return text
    }

    suspend fun runDue(c: Context, container: AppContainer, now: Double) {
        if (!RadarSettings.on(c, "briefing", true)) return
        if (today(c) == null && now in 3.0..9.0) runCatching { prepare(c, container) }
        val key = "brief|${LocalDate.now()}"
        if (today(c) != null && now in 7.0..9.0 && !prefs(c).getBoolean(key, false)) {
            prefs(c).edit().putBoolean(key, true).apply()
            Notifications.post(c, Notifications.CHANNEL_NUDGE, 760, "Your morning briefing", today(c)!!.take(300))
        }
    }
}

// ── Accountability partner: a weekly note to someone who keeps you honest ────────────────────────

object Accountability {
    suspend fun summary(container: AppContainer): String {
        val days = runCatching { Barakah.days(container, 7) }.getOrElse { emptyList() }
        val tasks = runCatching { container.taskRepository.all().first() }.getOrElse { emptyList() }
        val zone = ZoneId.systemDefault()
        val done = tasks.count { t -> t.completedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate().isAfter(LocalDate.now().minusDays(7)) } == true }
        val prayers = days.sumOf { d -> (d.parts.firstOrNull { it.key == "prayer" }?.earned ?: 0.0) * 5 }.toInt()
        val barakah = if (days.isEmpty()) 0 else days.map { it.score }.average().toInt()
        return "My week with Atlas: $prayers/35 prayers, $done tasks finished, Barakah average $barakah/100. Hold me to it next week!"
    }

    suspend fun runDue(c: Context, container: AppContainer, now: Double) {
        val number = RadarSettings.text(c, "partner").filter { it.isDigit() || it == '+' }
        if (number.length < 8 || LocalDate.now().dayOfWeek != DayOfWeek.SUNDAY || now !in 20.0..21.0) return
        val key = "partner|${LocalDate.now()}"
        if (prefs(c).getBoolean(key, false)) return
        prefs(c).edit().putBoolean(key, true).apply()
        val text = summary(container)
        // Android won't open another app from the background, so the message waits one tap away
        val wa = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${number.trimStart('+')}?text=${Uri.encode(text)}"))
        val pi = PendingIntent.getActivity(c, 780, wa, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, Notifications.CHANNEL_NUDGE).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Send your week to your partner").setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text)).setContentIntent(pi).setAutoCancel(true).build()
        runCatching { NotificationManagerCompat.from(c).notify(780, n) }
    }
}

// ── Earn your scroll: paused apps unlock with minutes you've earned today ────────────────────────

object Earn {
    private val MIN_PER_TASK = 5
    private val MIN_PER_PRAYER = 5
    fun on(c: Context) = RadarSettings.on(c, "earn")
    private fun spentKey() = "earnSpent|${LocalDate.now()}"
    fun spent(c: Context) = prefs(c).getInt(spentKey(), 0)
    fun spend(c: Context, m: Int) = prefs(c).edit().putInt(spentKey(), spent(c) + m).apply()

    suspend fun earned(container: AppContainer): Int {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now()
        val tasks = runCatching { container.taskRepository.all().first()
            .count { t -> t.completedAt?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == today } == true } }.getOrElse { 0 }
        val prayers = runCatching { container.prayerRepository.day(today.toString()).first()
            ?.let { d -> listOf(d.fajr, d.dhuhr, d.asr, d.maghrib, d.isha).count { it == "ON_TIME" || it == "JAMAAH" } } ?: 0 }.getOrElse { 0 }
        val sunnah = com.nizam.app.feature.jarvis.Barakah.ticked(container.appContext, today, "dhikr").let { if (it) 10 else 0 }
        return tasks * MIN_PER_TASK + prayers * MIN_PER_PRAYER + sunnah
    }

    suspend fun balance(container: AppContainer) = (earned(container) - spent(container.appContext)).coerceAtLeast(0)
}
