package com.nizam.app.feature.phone

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Process
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Settings
import android.view.KeyEvent
import android.media.AudioManager
import java.time.LocalDate
import java.time.ZoneId

/** The second wave of phone control: screen time, media, clipboard, battery, deep links, calendar edits. */
object PhoneActions2 {

    private fun launch(context: Context, intent: Intent) = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true
    }.getOrElse { false }

    // ── Screen time ───────────────────────────────────────────────────────
    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun screenTime(context: Context, days: Int): PhoneActions.Outcome {
        if (!hasUsageAccess(context)) {
            launch(context, Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
            return PhoneActions.Outcome(false, "I need Usage access to see screen time — I've opened the screen, allow Atlas there.")
        }
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val start = LocalDate.now().minusDays((days - 1).coerceAtLeast(0).toLong())
            .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val stats = usm.queryAndAggregateUsageStats(start, System.currentTimeMillis())
        val pm = context.packageManager
        val top = stats.values
            .filter { it.totalTimeInForeground > 60_000 && it.packageName != context.packageName }
            .sortedByDescending { it.totalTimeInForeground }
            .take(12)
            .map { s ->
                val label = runCatching {
                    pm.getApplicationLabel(pm.getApplicationInfo(s.packageName, 0)).toString()
                }.getOrElse { s.packageName }
                val m = s.totalTimeInForeground / 60_000
                "$label ${m / 60}h ${m % 60}m"
            }
        val total = stats.values.sumOf { it.totalTimeInForeground } / 60_000
        return PhoneActions.Outcome(true,
            "Screen time over $days day(s): ${total / 60}h ${total % 60}m total. " + top.joinToString(" | "))
    }

    // ── Media ─────────────────────────────────────────────────────────────
    fun media(context: Context, command: String): PhoneActions.Outcome {
        val code = when (command.lowercase()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next", "skip" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val audio = context.getSystemService(AudioManager::class.java)
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return PhoneActions.Outcome(true, "Media: $command")
    }

    // ── Clipboard ─────────────────────────────────────────────────────────
    fun copy(context: Context, text: String): PhoneActions.Outcome {
        context.getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("Atlas", text))
        return PhoneActions.Outcome(true, "Copied to clipboard")
    }

    fun paste(context: Context): PhoneActions.Outcome {
        // Android only lets the app in the foreground read the clipboard — fine, the Curator is.
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        return if (text.isBlank()) PhoneActions.Outcome(true, "Clipboard is empty")
        else PhoneActions.Outcome(true, "Clipboard: ${text.take(1500)}")
    }

    // ── Device ────────────────────────────────────────────────────────────
    fun battery(context: Context): PhoneActions.Outcome {
        val bm = context.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        return PhoneActions.Outcome(true, "Battery $level%" + if (charging) ", charging" else "")
    }

    fun camera(context: Context): PhoneActions.Outcome =
        if (launch(context, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)))
            PhoneActions.Outcome(true, "Camera open") else PhoneActions.Outcome(false, "No camera app found")

    // ── Deep links into other apps ────────────────────────────────────────
    fun deepLink(context: Context, service: String, query: String): PhoneActions.Outcome {
        val q = Uri.encode(query)
        val url = when (service.lowercase()) {
            "youtube" -> "https://www.youtube.com/results?search_query=$q"
            "spotify" -> "spotify:search:$q"
            "careem" -> "careem://"
            "foodpanda" -> "https://www.foodpanda.pk/"
            "daraz" -> "https://www.daraz.pk/catalog/?q=$q"
            "maps", "nearby" -> "geo:0,0?q=$q"
            "google" -> "https://www.google.com/search?q=$q"
            "play store", "playstore" -> "market://search?q=$q"
            else -> return PhoneActions.Outcome(false, "I don't know how to open $service yet.")
        }
        val ok = launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return if (ok) PhoneActions.Outcome(true, "Opened $service" + if (query.isNotBlank()) " for \"$query\"" else "")
        else PhoneActions.Outcome(false, "$service isn't installed.")
    }

    fun email(context: Context, to: String, subject: String, body: String): PhoneActions.Outcome {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:${Uri.encode(to)}"))
            .putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body)
        return if (launch(context, intent)) PhoneActions.Outcome(true, "Email draft ready — tap send")
        else PhoneActions.Outcome(false, "No email app found.")
    }

    // ── Calendar edits ────────────────────────────────────────────────────
    fun findEventId(context: Context, title: String): Long? {
        val now = System.currentTimeMillis()
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(builder, now - 7 * 86_400_000L)
        ContentUris.appendId(builder, now + 60 * 86_400_000L)
        context.contentResolver.query(
            builder.build(),
            arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE),
            "${CalendarContract.Instances.TITLE} LIKE ?", arrayOf("%$title%"),
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { if (it.moveToFirst()) return it.getLong(0) }
        return null
    }

    fun deleteEvent(context: Context, title: String): PhoneActions.Outcome {
        val id = runCatching { findEventId(context, title) }.getOrNull()
            ?: return PhoneActions.Outcome(false, "No upcoming event matching \"$title\".")
        val rows = context.contentResolver.delete(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null
        )
        return PhoneActions.Outcome(rows > 0, if (rows > 0) "Deleted \"$title\"" else "Couldn't delete it.")
    }

    fun renameEvent(context: Context, title: String, newTitle: String): PhoneActions.Outcome {
        val id = runCatching { findEventId(context, title) }.getOrNull()
            ?: return PhoneActions.Outcome(false, "No upcoming event matching \"$title\".")
        val rows = context.contentResolver.update(
            ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id),
            ContentValues().apply { put(CalendarContract.Events.TITLE, newTitle) }, null, null
        )
        return PhoneActions.Outcome(rows > 0, if (rows > 0) "Renamed to \"$newTitle\"" else "Couldn't update it.")
    }
}
