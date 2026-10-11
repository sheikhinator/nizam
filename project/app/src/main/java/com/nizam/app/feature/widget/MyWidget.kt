package com.nizam.app.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.nizam.app.AppContainer
import com.nizam.app.MainActivity
import com.nizam.app.NizamApplication
import com.nizam.app.R
import com.nizam.app.core.hourToClock
import com.nizam.app.feature.prayer.PrayerTimes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime

/**
 * "My Atlas": a widget whose four lines you choose — next prayer, money, tasks, water, streak,
 * Hijri date, or a line of wisdom. Set it up in Tools › My widget.
 */
class MyWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { refresh(context) } finally { pending.finish() } }
    }

    companion object {
        /** What a line can show, in the order offered. */
        val SOURCES = linkedMapOf(
            "prayer" to "Next prayer", "spent" to "Spent today", "tasks" to "Open tasks", "water" to "Water today",
            "hijri" to "Hijri date", "date" to "Today's date", "quote" to "A line of wisdom", "none" to "Nothing"
        )
        private fun p(c: Context) = c.getSharedPreferences("mywidget", 0)
        fun lines(c: Context): List<String> = listOf("prayer", "spent", "tasks", "hijri").mapIndexed { i, d -> p(c).getString("l$i", d) ?: d }
        fun setLine(c: Context, i: Int, v: String) = p(c).edit().putString("l$i", v).apply()
        fun title(c: Context) = p(c).getString("title", "ATLAS") ?: "ATLAS"
        fun setTitle(c: Context, v: String) = p(c).edit().putString("title", v.take(24)).apply()

        suspend fun value(c: Context, container: AppContainer, key: String): String = runCatching {
            val s = container.settingsRepository
            when (key) {
                "prayer" -> {
                    val t = PrayerTimes.calculate(LocalDate.now(), s.latitude.first(), s.longitude.first(),
                        runCatching { PrayerTimes.Method.valueOf(s.prayerMethod.first()) }.getOrElse { PrayerTimes.Method.KARACHI }, s.hanafiAsr.first())
                    val now = LocalTime.now().let { it.hour + it.minute / 60.0 }
                    listOf("Fajr" to t.fajr, "Dhuhr" to t.dhuhr, "Asr" to t.asr, "Maghrib" to t.maghrib, "Isha" to t.isha)
                        .firstOrNull { !it.second.isNaN() && it.second > now }?.let { "${it.first} ${hourToClock(it.second)}" } ?: "Fajr tomorrow"
                }
                "spent" -> {
                    val today = LocalDate.now().toString()
                    val v = container.financeRepository.since(today).first().filter { it.kind == "EXPENSE" && it.localDate == today }.sumOf { it.amount }
                    "Spent Rs " + "%,d".format(v.toLong())
                }
                "tasks" -> {
                    val open = container.taskRepository.all().first().filter { it.completedAt == null }
                    if (open.isEmpty()) "No open tasks" else "${open.size} open · ${open.first().title}"
                }
                "water" -> {
                    val ml = container.dietRepository.water(LocalDate.now().toString()).first()?.ml ?: 0
                    "Water ${"%.1f".format(ml / 1000.0)} of ${"%.1f".format(s.waterTarget.first() / 1000.0)} L"
                }
                "hijri" -> java.time.chrono.HijrahDate.from(LocalDate.now()).format(java.time.format.DateTimeFormatter.ofPattern("d MMMM y"))
                "date" -> LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM"))
                "quote" -> container.quoteRepository.all().first().randomOrNull()?.text?.take(90) ?: "Small steps, every day."
                else -> ""
            }
        }.getOrDefault("")

        suspend fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MyWidget::class.java))
            if (ids.isEmpty()) return
            val c = (context.applicationContext as? NizamApplication)?.container ?: return
            val vals = lines(context).map { value(context, c, it) }
            val v = RemoteViews(context.packageName, R.layout.widget_mine).apply {
                setTextViewText(R.id.mine_head, title(context).uppercase())
                listOf(R.id.mine_1, R.id.mine_2, R.id.mine_3, R.id.mine_4).forEachIndexed { i, id -> setTextViewText(id, vals.getOrElse(i) { "" }) }
                setOnClickPendingIntent(R.id.mine_root, PendingIntent.getActivity(context, 46,
                    Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            }
            manager.updateAppWidget(ids, v)
        }
    }
}
