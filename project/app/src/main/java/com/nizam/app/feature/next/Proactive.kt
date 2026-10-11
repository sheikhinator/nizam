package com.nizam.app.feature.next

import android.app.Notification
import android.content.Context
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.feature.phone.AtlasNotificationListener
import java.time.LocalDate

/**
 * Proactive follow-ups: a message you haven't answered is still sitting in the notification shade
 * hours later. Atlas nudges once per person per day, never at night, and only for messaging apps.
 */
object FollowUps {
    private val MESSAGING = listOf("whatsapp", "telegram", "messag", "signal", "instagram", "messenger", "sms", "mms", "viber", "imo")
    private fun p(c: Context) = c.getSharedPreferences("followups", 0)
    fun on(c: Context) = p(c).getBoolean("on", true)
    fun set(c: Context, v: Boolean) = p(c).edit().putBoolean("on", v).apply()
    fun afterHours(c: Context) = p(c).getInt("hours", 3)
    fun setAfterHours(c: Context, h: Int) = p(c).edit().putInt("hours", h).apply()

    data class Waiting(val who: String, val app: String, val text: String, val hours: Long, val key: String)

    /** Unanswered conversations still in the shade, oldest first. */
    fun waiting(c: Context, minHours: Int = afterHours(c)): List<Waiting> {
        val now = System.currentTimeMillis()
        return AtlasNotificationListener.active().filter { sbn ->
            val pkg = sbn.packageName.lowercase()
            MESSAGING.any { pkg.contains(it) } && sbn.notification.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true
        }.mapNotNull { sbn ->
            val x = sbn.notification.extras
            val who = x.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty().substringBefore(":").trim()
            val text = x.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
            val hours = (now - sbn.postTime) / 3_600_000
            if (who.isBlank() || hours < minHours) null
            else Waiting(who, runCatching { c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() }
                .getOrElse { sbn.packageName }, text.take(120), hours, sbn.key)
        }.sortedByDescending { it.hours }
    }

    /** Called from the regular background check. */
    fun check(c: Context, hourOfDay: Double) {
        if (!on(c) || hourOfDay !in 9.0..21.5) return
        val list = waiting(c)
        if (list.isEmpty()) return
        val today = LocalDate.now().toString()
        val fresh = list.filter { !p(c).getBoolean("$today|${it.who.lowercase()}", false) }
        if (fresh.isEmpty()) return
        fresh.forEach { p(c).edit().putBoolean("$today|${it.who.lowercase()}", true).apply() }
        val first = fresh.first()
        val body = if (fresh.size == 1) "${first.who} (${first.app}) wrote ${first.hours} h ago: \"${first.text}\""
            else fresh.take(4).joinToString("\n") { "${it.who} · ${it.hours} h ago" }
        Notifications.post(c, Notifications.CHANNEL_NUDGE, 780,
            if (fresh.size == 1) "Still waiting on you: ${first.who}" else "${fresh.size} conversations waiting on you", body)
    }
}
