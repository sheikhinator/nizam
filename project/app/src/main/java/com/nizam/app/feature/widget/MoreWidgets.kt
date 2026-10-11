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
import com.nizam.app.ui.nav.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate

private fun open(c: Context, route: String, code: Int) = PendingIntent.getActivity(c, code,
    Intent(c, MainActivity::class.java).setAction("atlas.widget.$route").putExtra("route", route)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

/** A search-style bar for the home screen: tap to ask, the mic to talk, the camera to scan. */
class AskWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val v = RemoteViews(context.packageName, R.layout.widget_ask).apply {
            setOnClickPendingIntent(R.id.ask_root, open(context, Routes.ASSISTANT, 41))
            setOnClickPendingIntent(R.id.ask_text, open(context, Routes.ASSISTANT, 41))
            setOnClickPendingIntent(R.id.ask_mic, open(context, Routes.VOICE, 42))
            setOnClickPendingIntent(R.id.ask_scan, open(context, Routes.SCAN, 43))
        }
        manager.updateAppWidget(ids, v)
    }
}

/** Today's spending and the month so far, with a one-tap way to add an expense. */
class MoneyWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch { try { refresh(context) } finally { pending.finish() } }
    }

    companion object {
        suspend fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MoneyWidget::class.java))
            if (ids.isEmpty()) return
            val c = (context.applicationContext as? NizamApplication)?.container ?: return
            val today = LocalDate.now().toString()
            val month = runCatching { c.financeRepository.since(LocalDate.now().withDayOfMonth(1).toString()).first() }.getOrElse { emptyList() }
                .filter { it.kind == "EXPENSE" }
            fun rs(v: Double) = "Rs " + "%,d".format(v.toLong())
            val v = RemoteViews(context.packageName, R.layout.widget_money).apply {
                setTextViewText(R.id.money_today, rs(month.filter { it.localDate == today }.sumOf { it.amount }))
                setTextViewText(R.id.money_month, "${rs(month.sumOf { it.amount })} this month")
                setOnClickPendingIntent(R.id.money_root, open(context, Routes.FINANCE, 44))
                setOnClickPendingIntent(R.id.money_add, open(context, Routes.FINANCE, 45))
            }
            manager.updateAppWidget(ids, v)
        }
    }
}
