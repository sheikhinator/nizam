package com.nizam.app.ui.screens.tasks

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nizam.app.data.db.Task
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Tasks that actually ring: each open task with a date fires a notification at its time, with
 * Done, +1 hour and Tomorrow buttons right on it. Plus auto-postpone of yesterday's unfinished tasks
 * and a daily "plan your day" check-in, both optional.
 */
object TaskAlarms {
    const val CHANNEL = "atlas_tasks"
    private fun p(c: Context) = c.getSharedPreferences("task_alarms", 0)
    fun on(c: Context) = p(c).getBoolean("on", true)
    fun setOn(c: Context, v: Boolean) = p(c).edit().putBoolean("on", v).apply()
    fun autoPostpone(c: Context) = p(c).getBoolean("autopostpone", false)
    fun setAutoPostpone(c: Context, v: Boolean) = p(c).edit().putBoolean("autopostpone", v).apply()
    fun checkIn(c: Context) = p(c).getInt("checkin", -1)
    fun setCheckIn(c: Context, h: Int) = p(c).edit().putInt("checkin", h).apply()
    fun simple(c: Context) = p(c).getBoolean("simple", false)
    fun setSimple(c: Context, v: Boolean) = p(c).edit().putBoolean("simple", v).apply()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + com.nizam.app.feature.safe.CoroutineSafetyNet.inScope)
    private fun container(c: Context) = (c.applicationContext as? com.nizam.app.NizamApplication)?.container

    private fun pi(c: Context, id: Long) = PendingIntent.getBroadcast(c, (7000 + id % 100000).toInt(),
        Intent(c, TaskAlarmReceiver::class.java).putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Sets an exact alarm for every open task due in the next two days. Cheap; called whenever tasks change. */
    fun sync(c: Context, tasks: List<Task>) {
        if (!on(c)) return
        val alarm = c.getSystemService(AlarmManager::class.java) ?: return
        val now = System.currentTimeMillis()
        val exact = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
        tasks.forEach { t ->
            val due = t.due ?: return@forEach
            val p = pi(c, t.id)
            if (t.completedAt != null || t.deletedAt != null || due <= now || due > now + 48 * 3_600_000L) { alarm.cancel(p); return@forEach }
            runCatching { if (exact) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, p) else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, p) }
        }
    }

    fun ring(c: Context, t: Task) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Task reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A task's time has come" })
        fun act(a: String, code: Int) = PendingIntent.getBroadcast(c, (8000 + t.id % 100000).toInt() * 4 + code,
            Intent(c, TaskActionReceiver::class.java).setAction("atlas.task.$a").putExtra("id", t.id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(c, (9000 + t.id % 100000).toInt(),
            Intent(c, com.nizam.app.MainActivity::class.java).putExtra("route", com.nizam.app.ui.nav.Routes.TASKS).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(t.title).setContentText(if (t.priority == 2) "High priority · due now" else "Due now")
            .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_REMINDER).setAutoCancel(true).setContentIntent(open)
            .addAction(0, "Done", act("done", 0)).addAction(0, "+1 hour", act("hour", 1)).addAction(0, "Tomorrow", act("tomorrow", 2)).build()
        runCatching { NotificationManagerCompat.from(c).notify(notifId(t.id), n) }
        runCatching { com.nizam.app.feature.notify.Inbox.record(c, CHANNEL, t.title, "Task due") }
    }
    fun notifId(id: Long) = (60000 + id % 100000).toInt()

    fun onAlarm(c: Context, id: Long) = scope.launch {
        val ct = container(c) ?: return@launch
        val t = ct.taskRepository.all().first().firstOrNull { it.id == id } ?: return@launch
        if (t.completedAt == null) ring(c, t)
    }

    fun onAction(c: Context, action: String, id: Long) = scope.launch {
        val ct = container(c) ?: return@launch
        val t = ct.taskRepository.all().first().firstOrNull { it.id == id } ?: return@launch
        runCatching { NotificationManagerCompat.from(c).cancel(notifId(id)) }
        when (action) {
            "atlas.task.done" -> if (t.completedAt == null) {
                ct.taskRepository.toggleComplete(t); TaskSmart.next(t)?.let { ct.taskRepository.insert(it) }
            }
            "atlas.task.hour" -> ct.taskRepository.save(t.copy(due = System.currentTimeMillis() + 3_600_000L, updatedAt = System.currentTimeMillis()))
            "atlas.task.tomorrow" -> ct.taskRepository.save(TaskSmart.withDateTime(t, LocalDate.now().plusDays(1), TaskSmart.timeOf(t) ?: LocalTime.of(9, 0)))
        }
        sync(c, ct.taskRepository.all().first())
    }

    /** From the background loop: keep alarms set, roll unfinished tasks forward, and the daily check-in. */
    suspend fun tick(c: Context, container: com.nizam.app.AppContainer, now: Double) {
        val tasks = container.taskRepository.all().first()
        sync(c, tasks)
        val today = LocalDate.now()
        if (autoPostpone(c) && p(c).getString("rolled", "") != today.toString()) {
            p(c).edit().putString("rolled", today.toString()).apply()
            tasks.filter { it.completedAt == null && it.recurrenceRule == null && TaskSmart.date(it)?.isBefore(today) == true }
                .forEach { container.taskRepository.save(TaskSmart.withDate(it, today)) }
        }
        val h = checkIn(c)
        if (h >= 0 && now >= h && now < h + 0.5 && p(c).getString("checked", "") != today.toString()) {
            p(c).edit().putString("checked", today.toString()).apply()
            val zone = ZoneId.systemDefault()
            val due = tasks.count { t -> t.completedAt == null && t.due?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }?.let { !it.isAfter(today) } == true }
            com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, 390, "What do you need to do today?",
                if (due == 0) "Nothing planned yet. Tap to plan your day." else "$due task${if (due > 1) "s" else ""} on today's list. Tap to look.", route = com.nizam.app.ui.nav.Routes.TASKS)
        }
    }
}

class TaskAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1); if (id < 0) return
        val pending = goAsync()
        TaskAlarms.onAlarm(context, id).invokeOnCompletion { pending.finish() }
    }
}

class TaskActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getLongExtra("id", -1); val a = intent.action ?: return
        if (id < 0) return
        val pending = goAsync()
        TaskAlarms.onAction(context, a, id).invokeOnCompletion { pending.finish() }
    }
}
