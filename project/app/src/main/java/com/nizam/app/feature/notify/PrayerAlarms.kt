package com.nizam.app.feature.notify

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.nizam.app.NizamApplication
import com.nizam.app.core.hourToClock
import com.nizam.app.core.today
import com.nizam.app.feature.prayer.PrayerTimes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Exact alarms at each prayer time.
 *
 * The 15-minute WorkManager check can't do this: WorkManager batches and Doze defers it, so a
 * prayer could be missed by half an hour. AlarmManager fires at the minute, even in Doze.
 */
object PrayerAlarms {

    private val names = listOf("Fajr", "Dhuhr", "Asr", "Maghrib", "Isha")

    suspend fun scheduleToday(context: Context) {
        val container = (context.applicationContext as? NizamApplication)?.container ?: return
        val settings = container.settingsRepository
        if (!settings.notificationsOn.first()) return

        val times = PrayerTimes.calculate(
            date = LocalDate.now(),
            latitude = settings.latitude.first(),
            longitude = settings.longitude.first(),
            method = runCatching { PrayerTimes.Method.valueOf(settings.prayerMethod.first()) }
                .getOrElse { PrayerTimes.Method.KARACHI },
            hanafiAsr = settings.hanafiAsr.first()
        )
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarm.canScheduleExactAlarms()

        listOf(times.fajr, times.dhuhr, times.asr, times.maghrib, times.isha)
            .forEachIndexed { index, hour ->
                if (hour.isNaN()) return@forEachIndexed
                val minutes = (hour * 60).toLong()
                val at = LocalDateTime.of(LocalDate.now(), java.time.LocalTime.MIDNIGHT)
                    .plusMinutes(minutes)
                val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (millis <= System.currentTimeMillis()) return@forEachIndexed

                val pending = PendingIntent.getBroadcast(
                    context, 300 + index,
                    Intent(context, PrayerAlarmReceiver::class.java)
                        .putExtra("name", names[index])
                        .putExtra("index", index)
                        .putExtra("time", hourToClock(hour)),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                if (canExact) {
                    alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
                } else {
                    alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending)
                }
            }
    }
}

class PrayerAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("name") ?: return
        val index = intent.getIntExtra("index", 0)
        val time = intent.getStringExtra("time").orEmpty()
        if (com.nizam.app.feature.shield.Shield.duringPrayer(context)) com.nizam.app.feature.shield.Shield.activate(context, 20)
        runCatching { com.nizam.app.feature.jarvis.SalahGuard.onPrayer(context, name, LocalDate.now().dayOfWeek == java.time.DayOfWeek.FRIDAY) }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val container = (context.applicationContext as? NizamApplication)?.container
                val day = container?.prayerRepository?.day(today())?.first()
                val status = when (index) {
                    0 -> day?.fajr; 1 -> day?.dhuhr; 2 -> day?.asr; 3 -> day?.maghrib; else -> day?.isha
                }
                if (status == null || status == "NOT_PRAYED") {
                    Notifications.post(
                        context, Notifications.CHANNEL_PRAYER, 100 + index,
                        "$name — $time", "Time for $name. Tap to log it."
                    )
                }
            } finally {
                pending.finish()
            }
        }
    }
}
