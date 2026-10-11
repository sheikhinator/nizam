package com.nizam.app.feature.jarvis

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.nizam.app.AppContainer
import com.nizam.app.feature.insight.HealthCache
import com.nizam.app.feature.prayer.PrayerTimes
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Barakah score: one number for how well a day was lived by the standards the user holds —
 * prayer on time, Quran, charity, rest, gratitude, family, keeping your word. Measured where
 * Atlas can measure (prayers, spending, sleep, diary, tasks) and a one-tap tick where it can't.
 *
 * Then the useful part: across your own days, which parts actually lift the rest of the day.
 */
data class BarakahPart(val key: String, val label: String, val weight: Int, val earned: Double, val auto: Boolean, val note: String)
data class BarakahDay(val date: LocalDate, val parts: List<BarakahPart>) {
    val score: Int get() = parts.sumOf { it.earned * it.weight }.toInt().coerceIn(0, 100)
}

object Barakah {
    val MANUAL = listOf("quran" to "Quran today", "dhikr" to "Morning / evening adhkar", "family" to "Real time with family")

    private fun ticks(c: Context) = c.getSharedPreferences("barakah_ticks", 0)
    fun ticked(c: Context, day: LocalDate, key: String) = ticks(c).getBoolean("$day|$key", false)
    fun tick(c: Context, day: LocalDate, key: String, on: Boolean) = ticks(c).edit().putBoolean("$day|$key", on).apply()

    private val CHARITY = Regex("charity|sadaq|zakat|donat|khairat|masjid|mosque", RegexOption.IGNORE_CASE)

    suspend fun days(container: AppContainer, count: Int): List<BarakahDay> {
        val c = container.appContext
        val from = LocalDate.now().minusDays(count - 1L)
        val prayers = container.prayerRepository.since(from.minusDays(1).toString()).first().associateBy { it.localDate }
        val txns = container.financeRepository.since(from.minusDays(7).toString()).first()
        val diary = container.diaryRepository.entries().first().map { it.localDate }.toSet()
        val tasks = container.taskRepository.all().first()
        val health = HealthCache(c).load()
        val zone = ZoneId.systemDefault()
        return (0 until count).map { i ->
            val d = from.plusDays(i.toLong())
            val p = prayers[d.toString()]
            val statuses = listOfNotNull(p?.fajr, p?.dhuhr, p?.asr, p?.maghrib, p?.isha)
            val onTime = statuses.count { it == "ON_TIME" || it == "JAMAAH" }
            val prayed = statuses.count { it != "NOT_PRAYED" }
            val prayerScore = (onTime + (prayed - onTime) * 0.5) / 5.0
            val gaveThisWeek = txns.any { t -> t.kind == "EXPENSE" && CHARITY.containsMatchIn(t.category + " " + t.note) &&
                runCatching { LocalDate.parse(t.localDate) }.getOrNull()?.let { !it.isAfter(d) && it.isAfter(d.minusDays(7)) } == true }
            val sleep = health[d.toString()]?.first
            val sleepScore = when { sleep == null -> 0.5; sleep in 7.0..9.0 -> 1.0; sleep in 6.0..10.0 -> 0.6; else -> 0.2 }
            val done = tasks.count { t -> t.completedAt?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() == d } == true }
            BarakahDay(d, listOf(
                BarakahPart("prayer", "Prayer on time", 35, prayerScore, true, "$onTime on time, $prayed prayed"),
                BarakahPart("quran", "Quran", 15, if (ticked(c, d, "quran")) 1.0 else 0.0, false, ""),
                BarakahPart("charity", "Sadaqah this week", 10, if (gaveThisWeek || ticked(c, d, "charity")) 1.0 else 0.0,
                    gaveThisWeek, if (gaveThisWeek) "logged in Finance" else "log it as Charity, or tick"),
                BarakahPart("sleep", "Rest", 10, sleepScore, sleep != null, sleep?.let { "%.1f h".format(it) } ?: "no sleep data"),
                BarakahPart("dhikr", "Adhkar", 10, if (ticked(c, d, "dhikr")) 1.0 else 0.0, false, ""),
                BarakahPart("gratitude", "Reflection / gratitude", 5, if (d.toString() in diary) 1.0 else 0.0, true, "a diary entry"),
                BarakahPart("family", "Family time", 10, if (ticked(c, d, "family")) 1.0 else 0.0, false, ""),
                BarakahPart("word", "Kept your word", 5, (done / 3.0).coerceAtMost(1.0), true, "$done tasks finished")
            ))
        }
    }

    /** Which part, when present, goes with a better rest-of-day — from your own last 30 days. */
    fun drivers(days: List<BarakahDay>): List<Pair<String, Int>> {
        if (days.size < 10) return emptyList()
        val keys = days.first().parts.map { it.key to it.label }
        return keys.mapNotNull { (key, label) ->
            val with = days.filter { d -> d.parts.first { it.key == key }.earned >= 0.8 }
            val without = days.filter { d -> d.parts.first { it.key == key }.earned < 0.8 }
            if (with.size < 3 || without.size < 3) return@mapNotNull null
            // score of everything EXCEPT this part, so a part can't prove itself by its own weight
            fun rest(d: BarakahDay) = d.parts.filter { it.key != key }.sumOf { it.earned * it.weight }
            val lift = (with.map(::rest).average() - without.map(::rest).average()).toInt()
            if (lift >= 4) label to lift else null
        }.sortedByDescending { it.second }
    }

    suspend fun forAi(container: AppContainer): String = runCatching {
        val today = days(container, 1).first()
        "Barakah today: ${today.score}/100 (" + today.parts.joinToString { "${it.label} ${(it.earned * 100).toInt()}%" } + ")"
    }.getOrElse { "" }
}

/**
 * Salah-aware time. Prayer is the fixed point the day bends around: the phone quiets itself for
 * each prayer, and anything Atlas schedules that lands on one is moved to just after it.
 */
object SalahGuard {
    private fun p(c: Context) = c.getSharedPreferences("salah_guard", 0)
    fun silenceOn(c: Context) = p(c).getBoolean("silence", false)
    fun setSilence(c: Context, on: Boolean) = p(c).edit().putBoolean("silence", on).apply()
    fun shiftOn(c: Context) = p(c).getBoolean("shift", true)
    fun setShift(c: Context, on: Boolean) = p(c).edit().putBoolean("shift", on).apply()
    fun minutes(c: Context) = p(c).getInt("minutes", 20)
    fun setMinutes(c: Context, m: Int) = p(c).edit().putInt("minutes", m).apply()

    private val NAMES = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")

    /** Start/end of each prayer's protected window on a date. Friday Dhuhr is Jumu'ah: longer, starts earlier. */
    suspend fun windows(container: AppContainer, date: LocalDate): List<Triple<String, LocalTime, LocalTime>> {
        val s = container.settingsRepository
        val t = PrayerTimes.calculate(date, s.latitude.first(), s.longitude.first(),
            runCatching { PrayerTimes.Method.valueOf(s.prayerMethod.first()) }.getOrElse { PrayerTimes.Method.KARACHI },
            s.hanafiAsr.first())
        val mins = minutes(container.appContext).toLong()
        return listOf(t.fajr, t.dhuhr, t.asr, t.maghrib, t.isha).mapIndexedNotNull { i, h ->
            if (h.isNaN()) return@mapIndexedNotNull null
            val at = LocalTime.MIDNIGHT.plusMinutes((h * 60).toLong())
            val jumuah = i == 1 && date.dayOfWeek == DayOfWeek.FRIDAY
            Triple(if (jumuah) "Jumu'ah" else NAMES[i], at.minusMinutes(if (jumuah) 20 else 5), at.plusMinutes(if (jumuah) 50 else mins))
        }
    }

    /** If [start, start+duration) overlaps a prayer, returns the prayer and a time just after it. */
    suspend fun clash(container: AppContainer, date: LocalDate, start: LocalTime, durationMin: Int): Pair<String, LocalTime>? {
        val end = start.plusMinutes(durationMin.toLong().coerceAtLeast(1))
        return windows(container, date).firstOrNull { (_, from, to) -> start.isBefore(to) && end.isAfter(from) }
            ?.let { (name, _, to) -> name to to.plusMinutes(5) }
    }

    /** Called from the prayer alarm: quiet the phone, and schedule the restore. */
    fun onPrayer(c: Context, name: String, friday: Boolean) {
        if (!silenceOn(c)) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.isNotificationPolicyAccessGranted) return
        val before = nm.currentInterruptionFilter
        if (before == NotificationManager.INTERRUPTION_FILTER_ALL) {
            nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
            p(c).edit().putBoolean("restore", true).apply()
        }
        val mins = if (friday && name == "Dhuhr") 50 else minutes(c)
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + mins * 60_000L,
            PendingIntent.getBroadcast(c, 399, Intent(c, SalahRestoreReceiver::class.java), PendingIntent.FLAG_IMMUTABLE))
        Timeline.log(c, "prayer", "$name — phone silenced for $mins min")
    }

    fun restore(c: Context) {
        if (!p(c).getBoolean("restore", false)) return    // DND was already on before prayer: leave it alone
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
        p(c).edit().putBoolean("restore", false).apply()
    }
}

class SalahRestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = SalahGuard.restore(context)
}
