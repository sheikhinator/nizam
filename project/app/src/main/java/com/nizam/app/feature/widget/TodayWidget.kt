package com.nizam.app.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.nizam.app.MainActivity
import com.nizam.app.NizamApplication
import com.nizam.app.R
import com.nizam.app.core.hourToClock
import com.nizam.app.feature.prayer.PrayerTimes
import com.nizam.app.feature.progress.Xp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/** Next prayer, today's missions and your streak — on the home screen, without opening Atlas. */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { refresh(context) } finally { pending.finish() }
        }
    }

    companion object {
        suspend fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
            if (ids.isEmpty()) return
            val c = (context.applicationContext as? NizamApplication)?.container ?: return
            val s = c.settingsRepository

            val prayerLine = runCatching {
                val t = PrayerTimes.calculate(LocalDate.now(), s.latitude.first(), s.longitude.first(),
                    runCatching { PrayerTimes.Method.valueOf(s.prayerMethod.first()) }.getOrElse { PrayerTimes.Method.KARACHI },
                    s.hanafiAsr.first())
                val now = LocalTime.now().let { it.hour + it.minute / 60.0 }
                listOf("Fajr" to t.fajr, "Dhuhr" to t.dhuhr, "Asr" to t.asr, "Maghrib" to t.maghrib, "Isha" to t.isha)
                    .firstOrNull { !it.second.isNaN() && it.second > now }
                    ?.let { "${it.first} at ${hourToClock(it.second)}" } ?: "Isha has passed"
            }.getOrElse { "Prayer times unavailable" }
            val missions = runCatching { c.missionRepository.today().first() }.getOrElse { emptyList() }
            val summary = runCatching { Xp.summarise(c) }.getOrNull()

            val views = RemoteViews(context.packageName, R.layout.widget_today).apply {
                setTextViewText(R.id.w_title, "ATLAS · ${LocalDate.now().dayOfWeek.name.take(3)}")
                setTextViewText(R.id.w_prayer, prayerLine)
                setTextViewText(R.id.w_missions,
                    if (missions.isEmpty()) "No missions yet"
                    else "Missions ${missions.count { it.completedAt != null }}/${missions.size}" +
                        (missions.firstOrNull { it.completedAt == null }?.let { " · next: ${it.title}" } ?: " · all done"))
                setTextViewText(R.id.w_streak, summary?.let { "🔥 ${it.streak}-day streak · Level ${it.level}" } ?: "")
                setOnClickPendingIntent(R.id.widget_root, PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            }
            manager.updateAppWidget(ids, views)
        }
    }
}
