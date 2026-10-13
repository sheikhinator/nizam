package com.nizam.app.core.spine

import android.content.Context
import com.nizam.app.NizamApplication
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.time.LocalTime

/**
 * SelfReview — the organism dreams.
 *
 * Once each night, Atlas reflects on the day's signals and leaves a short reflection + a morning
 * brief, so it grows a little wiser about this life while the owner sleeps. Self-gated (at most once
 * a night, after the evening hour), best-effort, and logged to [Receipts] — never silent, never a
 * second time the same night. Reads the whole organism through [Self] and [Pulse]; no new wiring.
 */
object SelfReview {
    private const val EVENING_HOUR = 22

    private fun prefs(c: Context) = c.getSharedPreferences("self_review", 0)
    private fun file(c: Context) = File(c.filesDir, "self_review.json")

    /** The most recent reflection, for a morning-brief surface to show. */
    fun latest(c: Context): JSONObject? =
        runCatching { JSONObject(file(c).readText()) }.getOrNull()

    /** Runs at most once per night, after [EVENING_HOUR]. Safe to call from the 15-minute worker. */
    suspend fun runIfDue(context: Context) {
        val c = context.applicationContext
        val today = LocalDate.now().toString()
        if (prefs(c).getString("lastDate", "") == today) return
        if (LocalTime.now().hour < EVENING_HOUR) return

        val container = (c as? NizamApplication)?.container ?: return

        val situation = runCatching { Self.brief() }.getOrDefault("")
        val signals = Pulse.recent(40)
        val activity = signals.groupingBy { it.domain }.eachCount()
            .entries.sortedByDescending { it.value }
            .joinToString(", ") { "${it.key} ×${it.value}" }

        val promptBody = buildString {
            appendLine("Reflect on the owner's day as Atlas — their calm, warm companion.")
            appendLine("Write 2-3 short sentences: what went well, one gentle observation, and one")
            appendLine("kind suggestion for tomorrow. Warm, never preachy. Use their language preference.")
            appendLine()
            appendLine("Situation:")
            appendLine(situation.ifBlank { "(a quiet day)" })
            if (activity.isNotBlank()) {
                appendLine()
                appendLine("Today's activity: $activity")
            }
        }

        val reflection = runCatching {
            com.nizam.app.net.Ai.generate(container.settingsRepository, promptBody, maxOutputTokens = 400)
        }.getOrNull()?.trim().orEmpty().ifBlank {
            // No AI key or offline: a plain, honest summary still gets left.
            "Today: ${situation.ifBlank { "a quiet day" }}." +
                (if (activity.isNotBlank()) " Activity — $activity." else "")
        }

        runCatching {
            file(c).writeText(
                JSONObject()
                    .put("date", today)
                    .put("reflection", reflection)
                    .put("situation", situation)
                    .put("at", System.currentTimeMillis())
                    .toString()
            )
        }
        prefs(c).edit().putString("lastDate", today).apply()
        Receipts.record(c, "self-review", "reflected", "Reflected on the day and left a morning brief")
    }

    /** Deliver last night's reflection as a gentle morning brief — once, in the morning window. */
    suspend fun deliverMorningBriefIfDue(context: Context) {
        val c = context.applicationContext
        if (!TrustBudget.allows(c, "self-review", TrustBudget.Level.NOTIFY)) return
        val hour = LocalTime.now().hour
        if (hour < 6 || hour >= 11) return
        val today = LocalDate.now().toString()
        if (prefs(c).getString("deliveredDate", "") == today) return

        val latest = latest(c) ?: return
        val writtenOn = latest.optString("date")
        if (writtenOn != today && writtenOn != LocalDate.now().minusDays(1).toString()) return
        val reflection = latest.optString("reflection").trim()
        if (reflection.isBlank()) return

        prefs(c).edit().putString("deliveredDate", today).apply()
        runCatching {
            com.nizam.app.feature.notify.Notifications.post(
                c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 271,
                "Good morning ☀️", reflection.take(240)
            )
        }
        Receipts.record(c, "self-review", "morning-brief", "Delivered the morning brief", reversible = false)
    }
}
