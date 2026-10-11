package com.nizam.app.feature.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import com.nizam.app.MainActivity
import com.nizam.app.NizamApplication
import com.nizam.app.R
import com.nizam.app.core.hourToClock
import com.nizam.app.feature.prayer.PrayerTimes
import com.nizam.app.ui.nav.Routes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime

/** A swipeable stack of cards on the home screen that puts whatever matters right now on top. It also turns by itself. */
class SmartStackWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id ->
            val svc = Intent(context, SmartStackService::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            svc.data = Uri.parse(svc.toUri(Intent.URI_INTENT_SCHEME))
            val v = RemoteViews(context.packageName, R.layout.widget_stack).apply {
                setRemoteAdapter(R.id.stack_view, svc)
                setEmptyView(R.id.stack_view, R.id.stack_empty)
                setPendingIntentTemplate(R.id.stack_view, PendingIntent.getActivity(context, 7070,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
            }
            manager.updateAppWidget(id, v)
        }
        @Suppress("DEPRECATION") manager.notifyAppWidgetViewDataChanged(ids, R.id.stack_view)
    }

    companion object {
        fun refresh(context: Context) {
            val m = AppWidgetManager.getInstance(context)
            val ids = m.getAppWidgetIds(ComponentName(context, SmartStackWidget::class.java))
            @Suppress("DEPRECATION") if (ids.isNotEmpty()) m.notifyAppWidgetViewDataChanged(ids, R.id.stack_view)
        }
    }
}

class SmartStackService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    data class Card(val label: String, val emoji: String, val value: String, val sub: String, val route: String, val score: Int)

    private class Factory(val c: Context) : RemoteViewsFactory {
        var cards: List<Card> = emptyList()
        override fun onCreate() {}
        override fun onDestroy() {}
        override fun getCount() = cards.size
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = position.toLong()
        override fun hasStableIds() = false

        override fun getViewAt(position: Int): RemoteViews {
            val k = cards.getOrNull(position) ?: return RemoteViews(c.packageName, R.layout.widget_stack_item)
            return RemoteViews(c.packageName, R.layout.widget_stack_item).apply {
                setTextViewText(R.id.card_label, k.label.uppercase())
                setTextViewText(R.id.card_emoji, k.emoji)
                setTextViewText(R.id.card_value, k.value)
                setTextViewText(R.id.card_sub, k.sub)
                setOnClickFillInIntent(R.id.card_root, Intent().putExtra("route", k.route))
            }
        }

        override fun onDataSetChanged() {
            cards = runBlocking { withTimeoutOrNull(8_000) { build() } } ?: cards
        }

        suspend fun build(): List<Card> {
            val app = (c.applicationContext as? NizamApplication)?.container ?: return emptyList()
            val s = app.settingsRepository
            val out = mutableListOf<Card>()
            val now = LocalTime.now().let { it.hour + it.minute / 60.0 }
            runCatching {
                val t = PrayerTimes.calculate(LocalDate.now(), s.latitude.first(), s.longitude.first(),
                    runCatching { PrayerTimes.Method.valueOf(s.prayerMethod.first()) }.getOrElse { PrayerTimes.Method.KARACHI }, s.hanafiAsr.first())
                val next = listOf("Fajr" to t.fajr, "Dhuhr" to t.dhuhr, "Asr" to t.asr, "Maghrib" to t.maghrib, "Isha" to t.isha)
                    .firstOrNull { !it.second.isNaN() && it.second > now }
                if (next != null) {
                    val mins = ((next.second - now) * 60).toInt()
                    out += Card("Next prayer", "🕌", next.first, "at ${hourToClock(next.second)} · in ${if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "$mins min"}",
                        Routes.PRAYER, if (mins <= 45) 100 else 60)
                } else out += Card("Prayer", "🌙", "Isha has passed", "Fajr tomorrow at ${hourToClock(t.fajr)}", Routes.PRAYER, 20)
            }
            runCatching {
                val open = app.taskRepository.all().first().filter { it.completedAt == null && it.deletedAt == null }
                out += Card("Tasks", "✅", if (open.isEmpty()) "All clear" else "${open.size} open",
                    open.take(3).joinToString(" · ") { it.title }.ifBlank { "Nothing waiting on you" }, Routes.TASKS,
                    if (now < 12) 80 else 50)
            }
            runCatching {
                val today = LocalDate.now().toString()
                val month = app.financeRepository.since(LocalDate.now().withDayOfMonth(1).toString()).first().filter { it.kind == "EXPENSE" }
                val day = month.filter { it.localDate == today }.sumOf { it.amount }
                out += Card("Spent today", "💸", "Rs " + "%,d".format(day.toLong()), "Rs " + "%,d".format(month.sumOf { it.amount }.toLong()) + " this month",
                    Routes.FINANCE, if (now > 18) 75 else 40)
            }
            runCatching {
                val p = com.nizam.app.feature.podcasts.PodcastStore(c).inProgress().firstOrNull()
                if (p != null) {
                    val left = (p.optLong("dur") - p.optLong("pos")) / 60000
                    out += Card("Keep listening", "🎧", p.optString("title").take(60), "${p.optString("show").take(30)} · $left min left", Routes.PODCASTS, 45)
                }
            }
            runCatching {
                val x = com.nizam.app.feature.progress.Xp.summarise(app)
                out += Card("Streak", "🔥", "${x.streak} days", "Level ${x.level} — keep it going today", Routes.TODAY, 30)
            }
            runCatching {
                val v = com.nizam.app.feature.hq.Hq.views(app.appContext)
                val need = v.filter { it.status == "needs" }; val busy = v.filter { it.status == "working" }
                out += Card("Atlas HQ", "🏢", when { need.isNotEmpty() -> "✋ ${need.size} need${if (need.size == 1) "s" else ""} you"; busy.isNotEmpty() -> "${busy.size} working"; else -> "All quiet" },
                    v.joinToString(" ") { (when (it.status) { "needs" -> "🔴"; "working" -> "🔵"; "done" -> "🟢"; else -> "⚪" }) + it.emoji }.take(80),
                    Routes.HQ, if (need.isNotEmpty()) 90 else if (busy.isNotEmpty()) 55 else 15)
            }
            out += Card("Ask Atlas", "🎙", "Say it, Atlas does it", "Tap to open the command bar", Routes.TODAY, 10)
            return out.sortedByDescending { it.score }
        }
    }
}
