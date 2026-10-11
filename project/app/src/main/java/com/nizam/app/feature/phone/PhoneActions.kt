package com.nizam.app.feature.phone

import android.Manifest
import android.app.NotificationManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.TimeZone

/**
 * Tier 1: things Atlas does on the phone directly, through standard Android APIs.
 * Each returns a plain-language result the agent can report back — including, when a permission
 * is missing, exactly which one and where to grant it.
 */
object PhoneActions {

    data class Outcome(val ok: Boolean, val message: String)

    private fun has(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun missing(what: String) =
        Outcome(false, "I need the $what permission. Grant it in Settings > Phone control.")

    private fun launch(context: Context, intent: Intent): Boolean = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    }.getOrElse { false }

    // ─── Alarms & timers ────────────────────────────────────────────────────────────────

    fun setAlarm(context: Context, hour: Int, minute: Int, label: String, days: List<Int>): Outcome {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Atlas" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (days.isNotEmpty()) putExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        }
        return if (launch(context, intent))
            Outcome(true, "Alarm set for %02d:%02d%s".format(hour, minute,
                if (label.isBlank()) "" else " — $label"))
        else Outcome(false, "No clock app accepted the alarm.")
    }

    fun setTimer(context: Context, seconds: Int, label: String): Outcome {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86_400))
            putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Atlas" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        }
        return if (launch(context, intent)) {
            val m = seconds / 60; val s = seconds % 60
            Outcome(true, "Timer running: " + (if (m > 0) "${m}m " else "") + (if (s > 0) "${s}s" else ""))
        } else Outcome(false, "No clock app accepted the timer.")
    }

    // ─── Calendar ───────────────────────────────────────────────────────────────────────

    fun addEvent(
        context: Context, title: String, date: String, time: String,
        durationMinutes: Int, location: String
    ): Outcome {
        if (!has(context, Manifest.permission.WRITE_CALENDAR)) return missing("calendar")
        val day = runCatching { LocalDate.parse(date) }.getOrElse { LocalDate.now() }
        val at = runCatching { LocalTime.parse(time) }.getOrElse { LocalTime.of(9, 0) }
        val start = LocalDateTime.of(day, at).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        val calendarId = context.contentResolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            arrayOf(CalendarContract.Calendars._ID),
            "${CalendarContract.Calendars.IS_PRIMARY} = 1", null, null
        )?.use { if (it.moveToFirst()) it.getLong(0) else null }
            ?: context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID), null, null, null
            )?.use { if (it.moveToFirst()) it.getLong(0) else null }
            ?: return Outcome(false, "No calendar account found on this phone.")

        val values = ContentValues().apply {
            put(CalendarContract.Events.DTSTART, start)
            put(CalendarContract.Events.DTEND, start + durationMinutes.coerceAtLeast(5) * 60_000L)
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.EVENT_LOCATION, location)
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        }
        return runCatching {
            context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            Outcome(true, "Added \"$title\" on $day at $at")
        }.getOrElse { Outcome(false, "Couldn't write the event: ${com.nizam.app.ui.components.Feedback.friendly(it)}") }
    }

    fun readEvents(context: Context, days: Int): Outcome {
        if (!has(context, Manifest.permission.READ_CALENDAR)) return missing("calendar")
        val now = System.currentTimeMillis()
        val end = now + days.coerceIn(1, 60) * 86_400_000L
        val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        android.content.ContentUris.appendId(builder, now)
        android.content.ContentUris.appendId(builder, end)
        val events = mutableListOf<String>()
        context.contentResolver.query(
            builder.build(),
            arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN),
            null, null, "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { c ->
            while (c.moveToNext() && events.size < 25) {
                val whenAt = java.time.Instant.ofEpochMilli(c.getLong(1))
                    .atZone(ZoneId.systemDefault()).toLocalDateTime()
                events.add("${whenAt.toLocalDate()} ${whenAt.toLocalTime().withSecond(0)} ${c.getString(0)}")
            }
        }
        return Outcome(true, if (events.isEmpty()) "Nothing on your calendar." else events.joinToString(" | "))
    }

    // ─── Contacts, calls, SMS ───────────────────────────────────────────────────────────

    private val relations = mapOf(
        "mother" to listOf("ammi", "amma", "ammu", "mom", "mama", "mum", "maa", "mother", "ami"),
        "mom" to listOf("ammi", "amma", "mom", "mama", "mum", "maa", "mother", "ami"),
        "father" to listOf("abbu", "abba", "abu", "dad", "papa", "baba", "father", "abbo"),
        "dad" to listOf("abbu", "abba", "abu", "dad", "papa", "baba", "father"),
        "brother" to listOf("bhai", "bhaiya", "brother", "bro"),
        "sister" to listOf("baji", "api", "aapi", "sister", "sis"),
        "wife" to listOf("wife", "begum", "jaan", "hubby"),
        "grandmother" to listOf("nani", "dadi", "nano", "grandma"),
        "grandfather" to listOf("nana", "dada", "grandpa")
    )

    fun findNumber(context: Context, name: String): Pair<String, String>? {
        if (!has(context, Manifest.permission.READ_CONTACTS)) return null
        val key = name.trim().lowercase().removePrefix("my ")
        relations[key]?.forEach { alias -> lookup(context, alias)?.let { return it } }
        return lookup(context, name)
    }

    private fun lookup(context: Context, name: String): Pair<String, String>? {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
            arrayOf("%$name%"), null
        )?.use { c -> if (c.moveToFirst()) return c.getString(0) to c.getString(1) }
        return null
    }

    fun call(context: Context, who: String): Outcome {
        val target = if (who.any { it.isDigit() } && who.count { it.isDigit() } >= 5) who to who
        else findNumber(context, who)
            ?: return if (!has(context, Manifest.permission.READ_CONTACTS)) missing("contacts")
            else Outcome(false, "No contact matching \"$who\".")
        val direct = has(context, Manifest.permission.CALL_PHONE)
        val intent = Intent(
            if (direct) Intent.ACTION_CALL else Intent.ACTION_DIAL,
            Uri.parse("tel:${Uri.encode(target.second)}")
        )
        return if (launch(context, intent))
            Outcome(true, (if (direct) "Calling " else "Dialler open for ") + target.first)
        else Outcome(false, "Couldn't start the call.")
    }

    fun sms(context: Context, who: String, body: String, sendDirectly: Boolean): Outcome {
        val target = if (who.count { it.isDigit() } >= 5) who to who
        else findNumber(context, who)
            ?: return if (!has(context, Manifest.permission.READ_CONTACTS)) missing("contacts")
            else Outcome(false, "No contact matching \"$who\".")

        if (sendDirectly && has(context, Manifest.permission.SEND_SMS)) {
            return runCatching {
                @Suppress("DEPRECATION")
                val manager = context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
                manager.sendMultipartTextMessage(
                    target.second, null, manager.divideMessage(body), null, null
                )
                Outcome(true, "Sent to ${target.first}: \"$body\"")
            }.getOrElse { Outcome(false, "SMS failed: ${com.nizam.app.ui.components.Feedback.friendly(it)}") }
        }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:${target.second}"))
            .putExtra("sms_body", body)
        return if (launch(context, intent)) Outcome(true, "Message ready for ${target.first} — tap send")
        else Outcome(false, "No messaging app found.")
    }

    fun whatsapp(context: Context, who: String, body: String): Outcome {
        val number = if (who.count { it.isDigit() } >= 5) who
        else findNumber(context, who)?.second
            ?: return Outcome(false, "No contact matching \"$who\".")
        // wa.me wants international format without + or spaces
        val digits = number.filter { it.isDigit() }.let { if (it.startsWith("0")) "92" + it.drop(1) else it }
        val intent = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://wa.me/$digits?text=${Uri.encode(body)}"))
        return if (launch(context, intent)) Outcome(true, "WhatsApp open with your message — tap send")
        else Outcome(false, "WhatsApp isn't installed.")
    }

    // ─── Apps, maps, sharing ────────────────────────────────────────────────────────────

    fun openApp(context: Context, name: String): Outcome {
        val wanted = name.trim().removeSuffix(" app").trim()
        if (wanted.equals("settings", true) || wanted.equals("system settings", true)) {
            return openSettings(context, "")
        }
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
        // What people actually say, mapped to what apps are actually called
        val target = ALIASES[wanted.lowercase()] ?: wanted
        val match = apps.firstOrNull { it.first.equals(target, true) }
            ?: apps.firstOrNull { it.second.equals(target, true) }                       // a package name
            ?: apps.filter { it.first.startsWith(target, true) }.minByOrNull { it.first.length }
            ?: apps.firstOrNull { a -> a.first.split(" ", "-").any { it.equals(target, true) } }
            ?: apps.filter { it.first.contains(target, true) }.minByOrNull { it.first.length }
            ?: apps.filter { it.second.contains(target.lowercase().replace(" ", "")) }.minByOrNull { it.first.length }
            ?: bestFuzzy(apps, target)                                                    // typos and near-misses
            ?: return Outcome(false, "No app called \"$wanted\" is installed. Ask me to list your apps if you're unsure.")
        val intent = pm.getLaunchIntentForPackage(match.second)
            ?: return Outcome(false, "${match.first} can't be opened directly.")
        return if (launch(context, intent)) Outcome(true, "Opened ${match.first}")
        else Outcome(false, "Couldn't open ${match.first}.")
    }

    /** Spoken shorthand people use for apps. */
    private val ALIASES = mapOf(
        "whatsapp" to "WhatsApp", "wa" to "WhatsApp", "insta" to "Instagram", "ig" to "Instagram",
        "fb" to "Facebook", "messenger" to "Messenger", "yt" to "YouTube", "youtube" to "YouTube",
        "maps" to "Maps", "google maps" to "Maps", "gmail" to "Gmail", "mail" to "Gmail",
        "chrome" to "Chrome", "browser" to "Chrome", "camera" to "Camera", "gallery" to "Photos",
        "photos" to "Photos", "music" to "YouTube Music", "spotify" to "Spotify", "x" to "X",
        "twitter" to "X", "tiktok" to "TikTok", "telegram" to "Telegram", "snapchat" to "Snapchat",
        "calculator" to "Calculator", "clock" to "Clock", "calendar" to "Calendar", "files" to "Files",
        "play store" to "Play Store", "playstore" to "Play Store", "careem" to "Careem",
        "foodpanda" to "foodpanda", "easypaisa" to "Easypaisa", "jazzcash" to "JazzCash",
        "netflix" to "Netflix", "linkedin" to "LinkedIn", "excel" to "Excel", "word" to "Word",
        "notes" to "Notes", "phone" to "Phone", "dialer" to "Phone", "contacts" to "Contacts",
        "messages" to "Messages", "sms" to "Messages", "stremio" to "Stremio"
    )

    /** Character-overlap score, for "instgram" and similar near-misses. */
    private fun bestFuzzy(apps: List<Pair<String, String>>, wanted: String): Pair<String, String>? {
        val w = wanted.lowercase().filter { it.isLetterOrDigit() }
        if (w.length < 3) return null
        fun score(label: String): Double {
            val l = label.lowercase().filter { it.isLetterOrDigit() }
            if (l.isEmpty()) return 0.0
            val shared = w.toSet().intersect(l.toSet()).size.toDouble()
            val prefix = w.commonPrefixWith(l).length.toDouble()
            return shared / maxOf(w.length, l.length) + prefix * 0.25
        }
        return apps.map { it to score(it.first) }.filter { it.second > 0.55 }.maxByOrNull { it.second }?.first
    }

    fun listApps(context: Context): Outcome {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val names = pm.queryIntentActivities(launcher, 0).map { it.loadLabel(pm).toString() }.sorted()
        return Outcome(true, names.joinToString(", ").take(2500))
    }

    fun navigate(context: Context, destination: String, mode: String): Outcome {
        val m = when (mode.lowercase()) { "walk", "walking" -> "w"; "bike", "bicycle" -> "b"; else -> "d" }
        val intent = Intent(Intent.ACTION_VIEW,
            Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=$m"))
        if (launch(context, intent)) return Outcome(true, "Navigating to $destination")
        val fallback = Intent(Intent.ACTION_VIEW,
            Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}"))
        return if (launch(context, fallback)) Outcome(true, "Maps open for $destination")
        else Outcome(false, "No maps app found.")
    }

    fun openUrl(context: Context, url: String): Outcome {
        val fixed = if (url.startsWith("http")) url else "https://$url"
        return if (launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(fixed))))
            Outcome(true, "Opened $fixed") else Outcome(false, "Couldn't open that link.")
    }

    fun share(context: Context, text: String): Outcome {
        val intent = Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            "Share from Atlas"
        )
        return if (launch(context, intent)) Outcome(true, "Share sheet open") else Outcome(false, "Couldn't share.")
    }

    // ─── Device controls ────────────────────────────────────────────────────────────────

    fun flashlight(context: Context, on: Boolean): Outcome = runCatching {
        val camera = context.getSystemService(CameraManager::class.java)
        val id = camera.cameraIdList.firstOrNull {
            camera.getCameraCharacteristics(it)
                .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return Outcome(false, "This phone has no flash.")
        camera.setTorchMode(id, on)
        Outcome(true, "Flashlight ${if (on) "on" else "off"}")
    }.getOrElse { Outcome(false, "Flashlight failed: ${com.nizam.app.ui.components.Feedback.friendly(it)}") }

    fun volume(context: Context, stream: String, percent: Int): Outcome {
        val audio = context.getSystemService(AudioManager::class.java)
        val type = when (stream.lowercase()) {
            "ring", "ringer" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "notification" -> AudioManager.STREAM_NOTIFICATION
            else -> AudioManager.STREAM_MUSIC
        }
        return runCatching {
            val max = audio.getStreamMaxVolume(type)
            audio.setStreamVolume(type, (max * percent.coerceIn(0, 100) / 100), 0)
            Outcome(true, "${stream.ifBlank { "Media" }} volume at $percent%")
        }.getOrElse { Outcome(false, "Couldn't change volume — Do Not Disturb may be blocking it.") }
    }

    fun doNotDisturb(context: Context, on: Boolean): Outcome {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) {
            launch(context, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
            return Outcome(false, "I need Do Not Disturb access — I've opened the screen, allow Atlas there.")
        }
        nm.setInterruptionFilter(
            if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL
        )
        return Outcome(true, "Do Not Disturb ${if (on) "on" else "off"}")
    }

    fun brightness(context: Context, percent: Int): Outcome {
        if (!Settings.System.canWrite(context)) {
            launch(context, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                Uri.parse("package:${context.packageName}")))
            return Outcome(false, "I need permission to change system settings — allow it on the screen I opened.")
        }
        return runCatching {
            Settings.System.putInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS, (255 * percent.coerceIn(1, 100) / 100))
            Outcome(true, "Brightness at $percent%")
        }.getOrElse { Outcome(false, "Couldn't change brightness.") }
    }

    fun openSettings(context: Context, page: String): Outcome {
        val action = when (page.lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        return if (launch(context, Intent(action))) Outcome(true, "Opened ${page.ifBlank { "settings" }}")
        else Outcome(false, "Couldn't open that settings page.")
    }
}
