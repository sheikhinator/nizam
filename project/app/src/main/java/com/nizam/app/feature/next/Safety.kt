package com.nizam.app.feature.next

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.ContactsContract
import android.provider.Telephony
import android.telecom.Call
import android.telecom.CallScreeningService
import com.nizam.app.feature.future.Guardian
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.feature.phone.PhoneActions
import kotlinx.coroutines.launch

/**
 * Lost mode. From one of your Guardian emergency contacts, text this phone:
 *   ATLAS LOST <word>   — Atlas covers itself with a "this phone is lost" screen and replies with the
 *                         location, then keeps sending it every half hour.
 *   ATLAS RING <word>   — rings loudly for a minute, even on silent.
 *   ATLAS FOUND <word>  — back to normal.
 * <word> is a secret you choose, so nobody else can trigger it.
 */
object LostMode {
    private fun p(c: Context) = c.getSharedPreferences("lostmode", 0)
    fun word(c: Context) = p(c).getString("word", "").orEmpty()
    fun setWord(c: Context, w: String) = p(c).edit().putString("word", w.trim().lowercase()).apply()
    fun active(c: Context) = p(c).getBoolean("active", false)
    fun owner(c: Context) = p(c).getString("owner", "").orEmpty()
    fun setOwner(c: Context, s: String) = p(c).edit().putString("owner", s.trim()).apply()
    private fun setActive(c: Context, v: Boolean, from: String = "") =
        p(c).edit().putBoolean("active", v).putString("from", from).putLong("lastPing", 0L).apply()

    private fun digits(s: String) = s.filter(Char::isDigit).takeLast(10)

    fun onSms(c: Context, from: String, body: String) {
        val w = word(c)
        if (w.isBlank()) return
        val parts = body.trim().split(Regex("\\s+"))
        if (parts.size < 3 || !parts[0].equals("atlas", true) || !parts[2].equals(w, true)) return
        val trusted = Guardian.contacts(c).any { digits(it.phone).isNotEmpty() && digits(it.phone) == digits(from) }
        if (!trusted) return
        when (parts[1].lowercase()) {
            "lost" -> { setActive(c, true, from); reply(c, from, "Atlas lost mode is on. Location: ${Guardian.where(c)}") }
            "ring" -> { ring(c); reply(c, from, "Ringing now. Location: ${Guardian.where(c)}") }
            "found" -> { setActive(c, false); reply(c, from, "Atlas lost mode is off.") }
            "where" -> reply(c, from, "Location: ${Guardian.where(c)}")
        }
    }

    /** From the regular background check: while lost, the location goes out every 30 minutes. */
    fun tick(c: Context) {
        if (!active(c)) return
        val last = p(c).getLong("lastPing", 0L)
        if (System.currentTimeMillis() - last < 30 * 60_000L) return
        p(c).edit().putLong("lastPing", System.currentTimeMillis()).apply()
        p(c).getString("from", null)?.let { reply(c, it, "Atlas lost mode: ${Guardian.where(c)}") }
    }

    fun unlock(c: Context, attempt: String): Boolean =
        (attempt.trim().lowercase() == word(c) && word(c).isNotBlank()).also { if (it) setActive(c, false) }

    private fun reply(c: Context, to: String, text: String) { runCatching { PhoneActions.sms(c, to, text, sendDirectly = true) } }

    private fun ring(c: Context) = runCatching {
        val am = c.getSystemService(android.media.AudioManager::class.java)
        am.setStreamVolume(android.media.AudioManager.STREAM_ALARM, am.getStreamMaxVolume(android.media.AudioManager.STREAM_ALARM), 0)
        val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_ALARM)
        val r = android.media.RingtoneManager.getRingtone(c, uri)
        r.audioAttributes = android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_ALARM).build()
        r.play()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ runCatching { r.stop() } }, 60_000)
    }
}

/** Receives texts for lost mode. Nothing else about your messages is read here. */
class LostModeSms : BroadcastReceiver() {
    override fun onReceive(c: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val msgs = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        val from = msgs.firstOrNull()?.originatingAddress ?: return
        val body = msgs.joinToString("") { it.messageBody.orEmpty() }
        if (body.trim().startsWith("atlas", true)) LostMode.onSms(c, from, body)
    }
}

/**
 * Caller check. When a call comes in, Atlas looks at the number: a known bank or wallet helpline is
 * named; an unknown number gets a quiet reminder that banks never ask for your PIN, OTP or CVV.
 * The call itself is never blocked.
 */
object CallCheck {
    /** Official helplines (UAN numbers). Matched on the last digits so +92 and 021 prefixes both work. */
    val HELPLINES = listOf(
        "HBL" to "111111425", "UBL" to "111825888", "MCB" to "111000622", "Meezan Bank" to "111331331",
        "Allied Bank" to "111225225", "Bank Alfalah" to "111225111", "National Bank" to "111627627",
        "Faysal Bank" to "111747747", "Askari Bank" to "111000787", "Standard Chartered" to "111002002",
        "Bank Al Habib" to "111014014", "JS Bank" to "111654321"
    )
    private fun p(c: Context) = c.getSharedPreferences("callcheck", 0)
    fun on(c: Context) = p(c).getBoolean("on", true)
    fun set(c: Context, v: Boolean) = p(c).edit().putBoolean("on", v).apply()

    fun helpline(number: String): String? {
        val d = number.filter(Char::isDigit)
        return HELPLINES.firstOrNull { d.endsWith(it.second) }?.first
    }

    fun inContacts(c: Context, number: String): Boolean = runCatching {
        val uri = android.net.Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
        c.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)?.use { it.count > 0 } ?: false
    }.getOrDefault(false)
}

class AtlasCallCheck : CallScreeningService() {
    override fun onScreenCall(details: Call.Details) {
        respondToCall(details, CallResponse.Builder().build())      // always let the call through
        if (!CallCheck.on(this)) return
        val number = details.handle?.schemeSpecificPart.orEmpty()
        if (number.isBlank()) return
        val bank = CallCheck.helpline(number)
        when {
            bank != null -> Notifications.post(this, Notifications.CHANNEL_NUDGE, 790, "Verified: $bank helpline",
                "This number is $bank's official helpline. Still: never share your PIN, OTP or CVV on a call.")
            !CallCheck.inContacts(this, number) -> Notifications.post(this, Notifications.CHANNEL_NUDGE, 790, "Unknown caller: $number",
                "If they say they're your bank or JazzCash/Easypaisa: hang up and call the number on your card. Banks never ask for your PIN, OTP or CVV.")
            else -> CallBrief.show(this, number)
        }
    }
}

/**
 * Call Brief: when someone you know calls, a card with what's open between you — promises, tasks,
 * when you last spoke — so you pick up already in context.
 */
object CallBrief {
    private fun p(c: Context) = c.getSharedPreferences("callbrief", 0)
    fun on(c: Context) = p(c).getBoolean("on", true)
    fun set(c: Context, v: Boolean) = p(c).edit().putBoolean("on", v).apply()

    fun name(c: Context, number: String): String? = runCatching {
        val uri = android.net.Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, android.net.Uri.encode(number))
        c.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    fun show(c: Context, number: String) {
        if (!on(c)) return
        val who = name(c, number) ?: return
        com.nizam.app.feature.jarvis.Background.scope.launch {
            val container = com.nizam.app.feature.jarvis.Background.container(c)
            val loops = runCatching { com.nizam.app.feature.jarvis.Recall.loops(container, who) }.getOrElse { emptyList() }
            val last = runCatching { com.nizam.app.feature.jarvis.Silah.lastContact(c, who) }.getOrNull()
            val lines = buildList {
                addAll(loops)
                last?.let { add("Last message: " + java.time.temporal.ChronoUnit.DAYS.between(it, java.time.LocalDate.now()).let { d -> if (d == 0L) "today" else "$d days ago" }) }
            }
            if (lines.isNotEmpty()) Notifications.post(c, Notifications.CHANNEL_NUDGE, 791, "$who is calling", lines.joinToString("\n"))
        }
    }
}
