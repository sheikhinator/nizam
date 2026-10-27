package com.nizam.app.feature.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nizam.app.MainActivity
import com.nizam.app.NizamApplication
import com.nizam.app.core.hourToClock
import com.nizam.app.core.today
import com.nizam.app.feature.prayer.PrayerTimes
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.TimeUnit

object Notifications {

    const val CHANNEL_PRAYER = "atlas_prayer"
    const val CHANNEL_NUDGE = "atlas_nudge"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PRAYER, "Prayer times", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Alerts at each prayer time" }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_NUDGE, "Reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Missions, habits and the evening review" }
        )
    }

    fun post(context: Context, channel: String, id: Int, title: String, body: String, route: String? = null) {
        // everything is also kept in the in-app inbox; routine reminders can be held for a digest
        val held = runCatching { Inbox.record(context, channel, title, body) }.getOrDefault(false)
        if (held) return
        if (NotificationManagerCompat.from(context).areNotificationsEnabled().not()) return
        runCatching { com.nizam.app.feature.wild.Haptics.forNotification(context, title, body) }
        val intent = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP).apply { if (route != null) putExtra("route", route) },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(id, notification) }
    }

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "atlas-checks",
            ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<AtlasWorker>(15, TimeUnit.MINUTES).build()
        )
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("atlas-checks")
    }
}

/**
 * The ambient loop. Runs every cycle, decides entirely on-device, and stays silent unless something
 * is genuinely worth saying. At most one interruption per hour, so presence never becomes noise.
 */
private suspend fun ambient(c: Context, container: com.nizam.app.AppContainer, now: Double) {
    val p = c.getSharedPreferences("ambient", 0)
    if (System.currentTimeMillis() - p.getLong("last", 0) < 60 * 60_000L) return
    if (!com.nizam.app.feature.presence.Presence.canSpeak(c)) return        // the interruption budget is a hard cap
    val today = java.time.LocalDate.now().toString()
    val energy = com.nizam.app.feature.body.Energy.today(c)
    val air = com.nizam.app.feature.daily.AirCache.get(c)
    val summary = runCatching { com.nizam.app.feature.progress.Xp.summarise(container) }.getOrNull()
    val promises = runCatching {
        val a = org.json.JSONArray(java.io.File(c.filesDir, "promises.json").readText())
        (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("state") == "open" && it.optString("due") <= today }
    }.getOrElse { emptyList() }

    val line = when {
        promises.isNotEmpty() -> "You said you'd ${promises.first().optString("what")}" +
            (promises.first().optString("who").takeIf { it.isNotBlank() }?.let { " — to $it" } ?: "") + ". Still time today."
        air != null && air.aqi > 180 && now in 15.0..18.0 -> "Air is bad out there (AQI ${air.aqi}). Train indoors this evening."
        energy == "Empty" && now in 20.0..22.0 -> "Low-energy day. Close the loop early and sleep — tomorrow can carry it."
        summary != null && summary.streak >= 3 && summary.last30.firstOrNull()?.active == false && now in 19.0..21.5 ->
            "Your ${summary.streak}-day streak is still alive, but today hasn't counted yet. One small thing keeps it."
        summary != null && summary.freezes > 0 && summary.streak == 0 && now in 9.0..11.0 ->
            "Fresh start today — you've got ${summary.freezes} streak freeze${if (summary.freezes == 1) "" else "s"} banked when you need them."
        else -> com.nizam.app.feature.nx.Noticer.nudge(c, container) ?: return
    }
    Notifications.post(c, Notifications.CHANNEL_NUDGE, 260, com.nizam.app.feature.presence.Presence.atlasName(c), line)
    com.nizam.app.feature.presence.Presence.logNudge(c, line)
    com.nizam.app.feature.presence.Presence.noteSpoke(c)
    p.edit().putLong("last", System.currentTimeMillis()).apply()
}

private suspend fun legacyAndNudges(c: Context, now: Double, container: com.nizam.app.AppContainer) {
    // Legacy vault: warn at 7, 3 and 1 days out; deliver only after the full silence.
    val lp = com.nizam.app.feature.world.Legacy.p(c)
    if (lp.getBoolean("on", false)) {
        val daysSilent = (System.currentTimeMillis() - lp.getLong("seen", System.currentTimeMillis())) / 86_400_000L
        val limit = lp.getInt("days", 60).toLong(); val warned = lp.getStringSet("warned", emptySet())!!.toMutableSet()
        listOf(7L, 3L, 1L).forEach { w ->
            if (daysSilent >= limit - w && "$w" !in warned) {
                Notifications.post(c, Notifications.CHANNEL_NUDGE, 240, "Legacy vault: $w day${if (w > 1) "s" else ""} left",
                    "You haven't opened Atlas in $daysSilent days. Open it once to cancel delivery."); warned += "$w"
            }
        }
        if (daysSilent >= limit && "sent" !in warned) {
            lp.getString("to", "").orEmpty().lines().filter { it.isNotBlank() }.forEach {
                com.nizam.app.feature.phone.PhoneActions.sms(c, it.trim(), lp.getString("letter", "").orEmpty(), sendDirectly = true)
            }
            warned += "sent"
        }
        lp.edit().putStringSet("warned", warned).apply()
    }
    // Flashcard nudges: three a day, from your Vocabulary module
    val slots = listOf(10.0, 15.0, 20.0)
    if (slots.any { now >= it && now < it + 0.25 }) {
        val words = container.dynamicRepository.entries("vocab").first()
        if (words.isNotEmpty()) {
            val w = words[(System.currentTimeMillis() / 900_000L % words.size).toInt()]
            val meaning = w.values().values.firstOrNull { it.isNotBlank() }.orEmpty()
            Notifications.post(c, Notifications.CHANNEL_NUDGE, 241, "Recall: ${w.title}", "Think of it first… then: $meaning")
        }
    }
}

private fun attention(c: Context, now: Double) {
    val a = com.nizam.app.feature.attention.Attention
    // digest at 13:00, 18:00, 21:00 (the worker runs every 15 min, so each window is 15 min wide)
    if (listOf(13.0, 18.0, 21.0).any { now >= it && now < it + 0.25 }) {
        val held = a.takeHeld(c)
        if (held.isNotEmpty()) Notifications.post(c, Notifications.CHANNEL_NUDGE, 210,
            "${held.size} held notifications", held.take(12).joinToString("\n"))
    }
    val wind = a.windDownHour(c)
    val today = java.time.LocalDate.now().toString()
    if (wind >= 0 && now.toInt() == wind && a.windDoneOn(c) != today) {
        a.markWind(c, today)
        com.nizam.app.feature.phone.PhoneActions.doNotDisturb(c, true)
        com.nizam.app.feature.shield.Shield.activate(c, 120)
        Notifications.post(c, Notifications.CHANNEL_NUDGE, 211, "Wind-down", "Screens are winding down for the night. Rest well.")
    }
    if (a.eyeBreaks(c) && System.currentTimeMillis() - a.lastEyeBreak(c) > 20 * 60_000L &&
        c.getSystemService(android.os.PowerManager::class.java).isInteractive &&
        com.nizam.app.feature.phone.PhoneActions2.hasUsageAccess(c)) {
        val usm = c.getSystemService(android.app.usage.UsageStatsManager::class.java)
        val used = usm.queryAndAggregateUsageStats(System.currentTimeMillis() - 20 * 60_000L, System.currentTimeMillis())
            .values.sumOf { it.totalTimeInForeground }
        if (used > 15 * 60_000L) {
            a.markEyeBreak(c)
            Notifications.post(c, Notifications.CHANNEL_NUDGE, 212, "Eye break", "Look at something 20 feet away for 20 seconds. Stand up.")
        }
    }
}

/**
 * Runs every 15 minutes. Checks whether a prayer just came in, whether missions are still open
 * late in the day, and whether the evening review is outstanding.
 */
class AtlasWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? NizamApplication)?.container ?: return Result.success()
        val settings = container.settingsRepository
        // unfinished Chief work (a build being watched, an interrupted job) keeps moving even when the app is closed
        runCatching { com.nizam.app.feature.chief.ChiefRunner.tick(applicationContext) }
        runCatching { com.nizam.app.feature.flows.FlowEngine.tick(applicationContext) }
        if (!settings.notificationsOn.first()) return Result.success()

        val now = LocalTime.now().let { it.hour + it.minute / 60.0 }
        // Superpowers: document expiry and bill due-date reminders (each fires once, on its day)
        runCatching { com.nizam.app.feature.future.Documents.remind(applicationContext) }
        runCatching { com.nizam.app.feature.future.PakistanData.remind(applicationContext) }
        runCatching { com.nizam.app.feature.future.AgentSchedule.check(applicationContext, container) }

        runCatching {
            val times = PrayerTimes.calculate(
                date = LocalDate.now(),
                latitude = settings.latitude.first(),
                longitude = settings.longitude.first(),
                method = runCatching {
                    PrayerTimes.Method.valueOf(settings.prayerMethod.first())
                }.getOrElse { PrayerTimes.Method.KARACHI },
                hanafiAsr = settings.hanafiAsr.first()
            )
            val day = container.prayerRepository.day(today()).first()
            listOf(
                Triple("Fajr", times.fajr, day?.fajr),
                Triple("Dhuhr", times.dhuhr, day?.dhuhr),
                Triple("Asr", times.asr, day?.asr),
                Triple("Maghrib", times.maghrib, day?.maghrib),
                Triple("Isha", times.isha, day?.isha)
            ).forEachIndexed { index, (name, time, status) ->
                // Fired if the prayer came in within the last 16 minutes and isn't logged yet
                if (!time.isNaN() && now >= time && now - time < 0.27 &&
                    (status == null || status == "NOT_PRAYED")
                ) {
                    Notifications.post(
                        applicationContext, Notifications.CHANNEL_PRAYER, 100 + index,
                        "$name — ${hourToClock(time)}",
                        "Time for $name. Tap to log it."
                    )
                }
            }
        }

        runCatching { com.nizam.app.feature.widget.TodayWidget.refresh(applicationContext) }
        runCatching { com.nizam.app.feature.widget.MoneyWidget.refresh(applicationContext) }
        runCatching { com.nizam.app.feature.widget.MyWidget.refresh(applicationContext) }
        runCatching { attention(applicationContext, now) }
        runCatching { com.nizam.app.feature.next.FollowUps.check(applicationContext, now) }
        runCatching { com.nizam.app.feature.next.LostMode.tick(applicationContext) }
        runCatching { com.nizam.app.feature.next.LoadShedding.check(applicationContext) }
        runCatching { com.nizam.app.feature.next.Medicines.check(applicationContext) }
        runCatching { if (now in 2.0..4.5) com.nizam.app.feature.brain2.PersonalModel.nightlyReview(applicationContext, container) }
        runCatching { if (now in 18.0..20.0) com.nizam.app.feature.brain2.Radar.weekly(applicationContext, container) }
        runCatching { if (now in 9.0..10.0) com.nizam.app.feature.next.DocExpiry.check(applicationContext) }
        runCatching { com.nizam.app.feature.nx.Specials.tick(applicationContext, now) }
        runCatching { com.nizam.app.feature.expertise.MindModules.tick(applicationContext) }
        runCatching { com.nizam.app.feature.companion.PetWorld.tick(applicationContext, now) }
        runCatching { if (now in 10.0..20.0) com.nizam.app.feature.next.PriceWatch.check(applicationContext) }
        runCatching { legacyAndNudges(applicationContext, now, container) }
        runCatching { com.nizam.app.feature.agent.Schedules.runDue(applicationContext, container, now) }
        runCatching { com.nizam.app.feature.nx.AutoRuns.tick(applicationContext, container, now) }
        runCatching { com.nizam.app.feature.prayer.PrayerPlus.tick(applicationContext, now) }
        runCatching { com.nizam.app.feature.nx.NightShift.tick(applicationContext, container, now) }
        runCatching { com.nizam.app.feature.nx.Ghost.tick(applicationContext, container, now) }
        runCatching { com.nizam.app.feature.nx.AtlasCalls.ensure(applicationContext) }
        runCatching { com.nizam.app.feature.nx.Constitution.tick(applicationContext, container, now) }
        runCatching { com.nizam.app.feature.nx.ScreenReminders.tick(applicationContext, now) }
        runCatching { com.nizam.app.ui.screens.tasks.TaskAlarms.tick(applicationContext, container, now) }
        runCatching { com.nizam.app.feature.next.Debts.tick(applicationContext, now) }
        runCatching { com.nizam.app.feature.next.Committees.tick(applicationContext, now) }
        runCatching { com.nizam.app.feature.next.Subs.tick(applicationContext, now) }
        runCatching { com.nizam.app.feature.earn.Earn.tick(applicationContext, container, now) }
        runCatching { com.nizam.app.feature.being.Being.live(container, applicationContext) }
        runCatching { com.nizam.app.feature.omni.OmniTick.run(applicationContext) }
        runCatching { com.nizam.app.feature.agent.AutoRules.tick(applicationContext, container) }
        runCatching { com.nizam.app.feature.nx.Allowances.tick(applicationContext) }
        runCatching { Inbox.digestTick(applicationContext, now) }
        runCatching { com.nizam.app.feature.nx.PlaceReminders.tick(applicationContext) }
        runCatching { com.nizam.app.feature.platform2.Updater.tick(applicationContext) }
        runCatching { com.nizam.app.feature.platform2.AiQueue.tick(applicationContext, container) }
        runCatching { com.nizam.app.feature.nx.EyeBreaks.tick(applicationContext) }
        runCatching { com.nizam.app.feature.backup.AutoBackup.tick(applicationContext) }
        runCatching { com.nizam.app.feature.modes.AutoModes.tick(container) }
        runCatching { com.nizam.app.feature.companion.PetLock.tick(applicationContext) }
        runCatching { if (now in 2.0..5.0) com.nizam.app.feature.wild.LifeOps.nightly(applicationContext, container) }
        runCatching { com.nizam.app.feature.wild.MemorySnapshots.take(applicationContext, container) }
        runCatching { com.nizam.app.feature.wild.Triggers.wifiTick(applicationContext) }
        runCatching { com.nizam.app.feature.wild.SimData.checkExpiry(applicationContext) }
        runCatching { com.nizam.app.feature.wild.Outage.check(applicationContext) }
        runCatching { if (now in 9.0..10.0) com.nizam.app.feature.wild.Warranty.check(applicationContext) }
        runCatching { com.nizam.app.feature.wild.WatchBridge.post(applicationContext, null) }
        runCatching { if (now in 11.0..19.0) com.nizam.app.feature.wild.PriceHunter.check(applicationContext, container) }
        runCatching { ambient(applicationContext, container, now) }
        runCatching { com.nizam.app.feature.final.Rules.evaluate(applicationContext, container) }
        runCatching { com.nizam.app.feature.agent.Workforce.runDue(applicationContext, container, now) }
        runCatching { com.nizam.app.feature.jarvis.Autopilot.runDue(applicationContext, container) }
        runCatching { if (now >= 9.0) com.nizam.app.feature.jarvis.Court.remindDue(applicationContext) }
        runCatching { com.nizam.app.feature.deen.Ramadan.remind(applicationContext, container, now) }
        runCatching { com.nizam.app.feature.jarvis.Silah.remind(applicationContext, now) }
        runCatching { com.nizam.app.feature.jarvis.BillRadar.remind(applicationContext, container, now) }
        runCatching { com.nizam.app.feature.jarvis.Briefing.runDue(applicationContext, container, now) }
        runCatching { com.nizam.app.feature.jarvis.Accountability.runDue(applicationContext, container, now) }
        runCatching { if (now >= 9.0) com.nizam.app.feature.deen.Zakat.remind(applicationContext) }
        runCatching {
            // new episodes of shows marked auto-download, Wi-Fi only
            val c = applicationContext
            val store = com.nizam.app.feature.podcasts.PodcastStore(c)
            val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
            val wifi = cm?.getNetworkCapabilities(cm.activeNetwork)
                ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            val p = c.getSharedPreferences("autodl", 0)
            val pol = com.nizam.app.feature.podcasts.PodcastPolicy
            if ((wifi || pol.mobileData(c)) && now >= 6.0 && p.getString("day", "") != java.time.LocalDate.now().toString()) {
                store.shows().filter { store.autoDownload(it.feed) }.take(6).forEach { show ->
                    runCatching {
                        val (_, eps) = com.nizam.app.feature.podcasts.Rss.load(show.feed)
                        eps.take(pol.keep(c, show.feed)).filter { store.downloaded(it) == null && !store.played(it.id) }
                            .forEach { if (pol.roomFor(c, store)) store.download(it) }
                    }
                }
                p.edit().putString("day", java.time.LocalDate.now().toString()).apply()
            }
            pol.tidy(c, store)
        }
        runCatching {
            // bank texts, logged automatically when you've allowed it
            val c = applicationContext
            if (com.nizam.app.feature.money.BankSms.enabled(c) && com.nizam.app.feature.money.BankSms.auto(c)) {
                com.nizam.app.feature.money.BankSms.scan(c, days = 2).forEach {
                    com.nizam.app.feature.money.BankSms.log(container, c, it)
                }
            }
        }
        runCatching {
            val c = applicationContext
            val prev = com.nizam.app.feature.daily.AirCache.get(c)
            if (prev == null || System.currentTimeMillis() - prev.at > 55 * 60_000L) {
                val air = com.nizam.app.feature.daily.Open.air(settings.latitude.first(), settings.longitude.first())
                com.nizam.app.feature.daily.AirCache.put(c, air)
                val today = java.time.LocalDate.now().toString(); val p = c.getSharedPreferences("air_alert", 0)
                if (air.aqi > 150 && now in 6.0..11.0 && p.getString("day", "") != today) {
                    Notifications.post(c, Notifications.CHANNEL_NUDGE, 230, "Air is ${com.nizam.app.feature.daily.Open.aqiLabel(air.aqi).lowercase()} (AQI ${air.aqi})",
                        "Train indoors today and mask up outside.")
                    p.edit().putString("day", today).apply()
                }
            }
        }
        runCatching {
            val c = applicationContext; val today = java.time.LocalDate.now().toString()
            val p = c.getSharedPreferences("maint_alert", 0)
            if (now >= 9.0 && p.getString("day", "") != today) {
                // one radar for everything with a date: services, refills, renewals, documents, follow-ups
                val soon = java.time.LocalDate.now().plusDays(14).toString()
                val watch = mapOf("maintenance" to "due", "meds" to "refill", "subscriptions" to "renews",
                    "documents" to "expiry", "network" to "followUp", "projects" to "deadline")
                val hits = watch.flatMap { (mod, field) ->
                    runCatching {
                        container.dynamicRepository.entries(mod).first()
                            .filter { val d = it.value(field); d.isNotBlank() && d <= soon && d >= today }
                            .map { "${it.title} (${it.value(field)})" }
                    }.getOrElse { emptyList() }
                }
                if (hits.isNotEmpty()) Notifications.post(c, Notifications.CHANNEL_NUDGE, 231,
                    "📅 ${hits.size} coming up", hits.take(6).joinToString(" · "))
                p.edit().putString("day", today).apply()
            }
        }
        runCatching {
            val c = applicationContext; val prefs = c.getSharedPreferences("memory_notes", 0)
            val today = java.time.LocalDate.now().toString()
            if (now >= 9.0 && prefs.getString("letters", "") != today) {
                val due = com.nizam.app.feature.memory.Letters.dueToday(c)
                if (due.isNotEmpty()) Notifications.post(c, Notifications.CHANNEL_NUDGE, 220, "✉ A letter from your past self",
                    "Written ${due.first().written}. Open Atlas > Me > Memory > Letters.")
                prefs.edit().putString("letters", today).apply()
            }
            if (java.time.LocalDate.now().dayOfWeek == java.time.DayOfWeek.SUNDAY && now >= 20.0 && prefs.getString("recap", "") != today) {
                Notifications.post(c, Notifications.CHANNEL_NUDGE, 221, "Your week, in two minutes", "Me > Memory > Weekly recap")
                prefs.edit().putString("recap", today).apply()
            }
            if (now >= 20.0 && now < 20.25 && !com.nizam.app.feature.memory.DailyPhoto.file(c, today).exists() &&
                com.nizam.app.feature.memory.DailyPhoto.dates(c).isNotEmpty()) {
                Notifications.post(c, Notifications.CHANNEL_NUDGE, 222, "📷 Photo of the day", "One picture to remember today by.")
            }
        }

        // Missions complete themselves in the background now, not only when the screen opens.
        runCatching { container.missionRepository.verifyToday() }

        // First run after 6am generates the day's missions if none exist yet.
        runCatching {
            if (now >= 6.0 && settings.geminiApiKey.first().isNotBlank() ||
                now >= 6.0 && settings.openRouterKey.first().isNotBlank() ||
                now >= 6.0 && settings.customUrl.first().isNotBlank()
            ) container.missionRepository.generateDaily(force = false)
        }

        // Keep the exact prayer alarms current (they're rescheduled daily).
        runCatching { PrayerAlarms.scheduleToday(applicationContext) }

        runCatching {
            val missions = container.missionRepository.today().first()
            val open = missions.count { it.completedAt == null }
            if (open > 0 && now >= 19.0 && now < 19.3) {
                Notifications.post(
                    applicationContext, Notifications.CHANNEL_NUDGE, 200,
                    "$open missions still open",
                    missions.firstOrNull { it.completedAt == null }?.title.orEmpty()
                )
            }
        }

        runCatching {
            if (now >= 21.5 && now < 21.8) {
                val reviewed = container.selfRepository.reviews().first()
                    .any { it.kind == "DAILY" && it.localDate == today() }
                if (!reviewed) {
                    Notifications.post(
                        applicationContext, Notifications.CHANNEL_NUDGE, 201,
                        "Close the day",
                        "Two minutes: what went well, what didn't, one change tomorrow."
                    )
                }
            }
        }

        return Result.success()
    }
}
