package com.nizam.app.feature.nx

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.DirectionsRun
import androidx.compose.material.icons.outlined.PhoneCallback
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

// ══════════════════════════════ Night Shift ══════════════════════════════

/**
 * Night Shift: queue jobs before bed ("research the best 5G phone under 80k", "draft my leave email",
 * "plan next week's meals") and Atlas works them between 1 and 5am, then leaves a morning report.
 * Jobs only produce text: nothing is sent, bought or changed. In the morning you turn any result into
 * tasks or notes with one tap.
 */
object NightShift {
    private fun store(c: Context) = JsonStore(c, "nightshift")
    private fun p(c: Context) = c.getSharedPreferences("nightshift", 0)
    fun all(c: Context) = store(c).all().sortedByDescending { it.optLong("t") }
    fun add(c: Context, text: String, useData: Boolean, web: Boolean) =
        store(c).add(JSONObject().put("text", text.trim()).put("state", "queued").put("data", useData).put("web", web).put("t", System.currentTimeMillis()))
    fun remove(c: Context, id: String) = store(c).remove(id)
    fun clearDone(c: Context) { val s = store(c); s.all().filter { it.optString("state") != "queued" }.forEach { s.remove(it.optString("id")) } }

    suspend fun run(c: Context, container: AppContainer, job: JSONObject) {
        val s = store(c)
        val id = job.optString("id")
        val ctx = if (job.optBoolean("data")) LifeContext.digest(LifeContext.YES, job.optString("text"), c) else ""
        val r = runCatching {
            Ai.generate(container.settingsRepository, (if (ctx.isBlank()) "" else "$ctx\n\n") +
                "Overnight job from the user (they're asleep; do it thoroughly so it's ready in the morning): ${job.optString("text")}\n\n" +
                "Deliver the finished result, not a plan to do it. Use headings and short bullets. If it needs facts that may have changed, check the web. End with 'Next:' and up to three concrete next steps.",
                system = "You are Atlas working the night shift for one person. Produce finished, practical work. Never claim to have sent, bought or booked anything.",
                maxOutputTokens = 2200, useWeb = job.optBoolean("web", true))
        }
        s.update(id) { o -> r.onSuccess { o.put("state", "done").put("out", it) }.onFailure { o.put("state", "failed").put("out", it.message ?: "Failed") }; o.put("done_t", System.currentTimeMillis()) }
    }

    /** From the background loop: work at night, report in the morning. */
    suspend fun tick(c: Context, container: AppContainer, now: Double) {
        if (now in 1.0..5.5) {
            store(c).all().filter { it.optString("state") == "queued" }.take(3).forEach { run(c, container, it) }
        }
        val today = LocalDate.now().toString()
        if (now in 6.5..10.0 && p(c).getString("reported", "") != today) {
            val since = System.currentTimeMillis() - 14 * 3_600_000L
            val done = store(c).all().filter { it.optString("state") == "done" && it.optLong("done_t") > since }
            if (done.isNotEmpty()) {
                p(c).edit().putString("reported", today).apply()
                Notifications.post(c, Notifications.CHANNEL_NUDGE, 380, "Night Shift: ${done.size} job${if (done.size > 1) "s" else ""} done",
                    done.joinToString(" · ") { it.optString("text").take(40) }, route = "nx/nightshift")
            }
        }
    }
}

@Composable
fun NightShiftScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var tick by remember { mutableIntStateOf(0) }
    val jobs = remember(tick) { NightShift.all(c) }
    var text by remember { mutableStateOf("") }
    var useData by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf<String?>(null) }
    var openId by remember { mutableStateOf<String?>(null) }
    AtlasScreen(title = "Night Shift", subtitle = "Queue work before bed. Atlas does it between 1 and 5am and reports in the morning.", hub = Hub.Tools) {
        item("add") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), minLines = 2, placeholder = { Text("What should Atlas work on tonight?") },
                    trailingIcon = { MicButton({ heard -> text = heard }) })
                LazyRow(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    val presets = listOf("Research the best phone under 80k in Pakistan", "Plan next week's meals on a budget", "Summarise my week and what to fix",
                        "Draft a polite leave application for Thursday", "Find 5 weekend plans in Lahore", "Make a 4-week gym plan for me")
                    items(presets.size) { i -> Capsule(presets[i], false) { text = presets[i]; if (presets[i].contains("my week")) useData = true } }
                }
                Row(Modifier.fillMaxWidth().padding(top = Space.s), verticalAlignment = Alignment.CenterVertically) {
                    Text("Let it use my Atlas data", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(useData, { useData = it })
                }
                Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Button(onClick = { NightShift.add(c, text, useData, true); text = ""; tick++; Feedback.show("Queued for tonight") }, enabled = text.isNotBlank()) { Text("Queue for tonight") }
                    TextButton(onClick = {
                        val job = NightShift.add(c, text, useData, true); text = ""; tick++
                        running = job.optString("id")
                        scope.launch { NightShift.run(c, container, job); running = null; tick++ }
                    }, enabled = text.isNotBlank() && running == null) { Text("Do it now") }
                }
            }
        }
        if (jobs.isEmpty()) item("e") { Text("Nothing queued. Add a job above, then sleep on it.", Modifier.padding(Space.page), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        val queued = jobs.filter { it.optString("state") == "queued" }
        if (queued.isNotEmpty()) item("qh") { SectionHeader("Tonight (${queued.size})") }
        queued.forEach { j ->
            item("q" + j.optString("id")) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Space.page, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text((if (running == j.optString("id")) "⏳ " else "🌙 ") + j.optString("text"), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = { NightShift.remove(c, j.optString("id")); tick++ }) { Text("✕") }
                }
            }
        }
        val done = jobs.filter { it.optString("state") != "queued" }
        if (done.isNotEmpty()) item("dh") {
            Row(Modifier.fillMaxWidth().padding(end = Space.page), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionHeader("Morning report") }
                TextButton(onClick = { NightShift.clearDone(c); tick++ }) { Text("Clear") }
            }
        }
        done.forEach { j ->
            item("d" + j.optString("id")) {
                val id = j.optString("id")
                Group(Modifier.padding(horizontal = Space.page, vertical = 4.dp)) {
                    Text((if (j.optString("state") == "done") "☀ " else "⚠ ") + j.optString("text"), fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
                    if (openId == id) {
                        Md(j.optString("out"))
                        ResultActions(j.optString("text").take(40), j.optString("out"))
                        ActOnIt(container, "Night Shift", j.optString("out"))
                        TextButton(onClick = { openId = null }) { Text("Collapse") }
                    } else {
                        Text(com.nizam.app.ui.components.RichFormat.plain(j.optString("out")).replace(Regex("[#*_]"), "").take(160) + "…",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = { openId = id }) { Text("Read") }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════ Race your ghost ══════════════════════════════

/** Today so far against yesterday at exactly this time of day. */
object Ghost {
    class Row(val label: String, val you: Double, val ghost: Double, val unit: String, val lowerIsBetter: Boolean = false)

    suspend fun rows(container: AppContainer): List<Row> {
        val zone = ZoneId.systemDefault()
        val todayStart = LocalDate.now().atStartOfDay(zone).toInstant().toEpochMilli()
        val yStart = LocalDate.now().minusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val elapsed = System.currentTimeMillis() - todayStart
        fun inToday(t: Long) = t in todayStart..System.currentTimeMillis()
        fun inGhost(t: Long) = t in yStart..(yStart + elapsed)
        val out = ArrayList<Row>()
        runCatching {
            val tasks = container.taskRepository.all().first()
            out += Row("Tasks done", tasks.count { it.completedAt?.let(::inToday) == true }.toDouble(), tasks.count { it.completedAt?.let(::inGhost) == true }.toDouble(), "")
        }
        runCatching {
            val w = container.dietRepository.waterIntakes()
            out += Row("Water", w.filter { inToday(it.createdAt) }.sumOf { it.ml }.toDouble(), w.filter { inGhost(it.createdAt) }.sumOf { it.ml }.toDouble(), " ml")
        }
        runCatching {
            val sets = container.gymRepository.sets().first()
            out += Row("Gym sets", sets.count { inToday(it.createdAt) }.toDouble(), sets.count { inGhost(it.createdAt) }.toDouble(), "")
        }
        runCatching {
            val tx = container.financeRepository.since(LocalDate.now().minusDays(1).toString()).first().filter { it.kind == "EXPENSE" }
            out += Row("Spent", tx.filter { inToday(it.createdAt) }.sumOf { it.amount }, tx.filter { inGhost(it.createdAt) }.sumOf { it.amount }, " Rs", lowerIsBetter = true)
        }
        runCatching {
            val days = container.prayerRepository.since(LocalDate.now().minusDays(1).toString()).first().associateBy { it.localDate }
            fun count(d: String) = days[d]?.let { x -> listOf(x.fajr, x.dhuhr, x.asr, x.maghrib, x.isha).count { it != "NOT_PRAYED" } } ?: 0
            out += Row("Prayers", count(LocalDate.now().toString()).toDouble(), count(LocalDate.now().minusDays(1).toString()).toDouble(), " (yesterday, whole day)")
        }
        return out.filter { it.you > 0 || it.ghost > 0 }
    }

    fun score(rows: List<Row>): Pair<Int, Int> = rows.count { if (it.lowerIsBetter) it.you < it.ghost else it.you > it.ghost } to
        rows.count { if (it.lowerIsBetter) it.you > it.ghost else it.you < it.ghost }

    /** An evening nudge once a day if you're behind your ghost. */
    suspend fun tick(c: Context, container: AppContainer, now: Double) {
        if (now !in 19.0..20.5) return
        val p = c.getSharedPreferences("ghost", 0)
        if (!p.getBoolean("on", true)) return
        val today = LocalDate.now().toString()
        if (p.getString("sent", "") == today) return
        val r = rows(container); if (r.size < 2) return
        val (ahead, behind) = score(r)
        p.edit().putString("sent", today).apply()
        if (behind > ahead) {
            val worst = r.filter { !it.lowerIsBetter && it.you < it.ghost }.maxByOrNull { it.ghost - it.you }
            Notifications.post(c, Notifications.CHANNEL_NUDGE, 381, "Yesterday-you is winning $behind–$ahead",
                worst?.let { "${it.label}: you ${short(it.you)}${it.unit}, ghost ${short(it.ghost)}${it.unit}. Still time." } ?: "Still time to catch up.", route = "nx/raceghost")
        }
    }
}

@Composable
fun GhostScreen(container: AppContainer) {
    val c = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val rows by produceState(initialValue = emptyList<Ghost.Row>(), tick) { value = runCatching { Ghost.rows(container) }.getOrDefault(emptyList()) }
    val p = remember { c.getSharedPreferences("ghost", 0) }
    var on by remember { mutableStateOf(p.getBoolean("on", true)) }
    val (ahead, behind) = Ghost.score(rows)
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Text(when { rows.isEmpty() -> "Log a few things today and yesterday-you will show up to race."
            ahead > behind -> "You're beating yesterday-you $ahead–$behind 👻"
            ahead < behind -> "Yesterday-you is ahead $behind–$ahead. Catch them."
            else -> "Neck and neck with yesterday-you" }, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Yesterday at this exact time vs you now", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
        rows.forEach { r ->
            val max = maxOf(r.you, r.ghost, 1.0)
            val winning = if (r.lowerIsBetter) r.you <= r.ghost else r.you >= r.ghost
            Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                Text(r.label, fontWeight = FontWeight.Medium)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("You", Modifier.width(48.dp), style = MaterialTheme.typography.labelMedium)
                    Box(Modifier.weight(1f).height(12.dp).background(cs.surfaceVariant, RoundedCornerShape(6.dp))) {
                        Box(Modifier.fillMaxWidth((r.you / max).toFloat().coerceIn(0.02f, 1f)).height(12.dp)
                            .background(if (winning) Color(0xFF2E9E6A) else Color(0xFFE8A13A), RoundedCornerShape(6.dp)))
                    }
                    Text(short(r.you) + r.unit.substringBefore(" ("), Modifier.width(80.dp).padding(start = 8.dp), style = MaterialTheme.typography.labelMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Text("👻", Modifier.width(48.dp))
                    Box(Modifier.weight(1f).height(12.dp).background(cs.surfaceVariant, RoundedCornerShape(6.dp))) {
                        Box(Modifier.fillMaxWidth((r.ghost / max).toFloat().coerceIn(0.02f, 1f)).height(12.dp).background(cs.onSurfaceVariant.copy(alpha = 0.45f), RoundedCornerShape(6.dp)))
                    }
                    Text(short(r.ghost) + r.unit.substringBefore(" ("), Modifier.width(80.dp).padding(start = 8.dp), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Evening nudge if the ghost is winning", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Switch(on, { on = it; p.edit().putBoolean("on", it).apply() })
        }
        TextButton(onClick = { tick++ }) { Text("Refresh") }
    }
}

// ══════════════════════════════ After-call catch ══════════════════════════════

/**
 * When a phone call ends, one quiet notification: "Anything agreed?" Tap, say it, and Atlas turns it into
 * tasks, reminders or money entries you tick. Uses the screen-control permission Atlas already has to
 * notice the call screen closing; nothing about the call itself is read or recorded.
 */
object AfterCall {
    private fun p(c: Context) = c.getSharedPreferences("aftercall", 0)
    fun on(c: Context) = p(c).getBoolean("on", false)
    fun setOn(c: Context, v: Boolean) = p(c).edit().putBoolean("on", v).apply()
    private var callStart = 0L
    private fun isCallUi(pkg: String) = pkg.contains("incallui") || pkg.contains("dialer") || pkg == "com.android.server.telecom" || pkg.contains("contacts.call")

    /** Called by the accessibility service whenever the foreground app changes. */
    fun onWindow(c: Context, from: String, to: String) {
        if (!on(c)) return
        if (isCallUi(to) && !isCallUi(from)) callStart = System.currentTimeMillis()
        if (isCallUi(from) && !isCallUi(to) && callStart > 0) {
            val dur = System.currentTimeMillis() - callStart
            callStart = 0
            if (dur > 25_000L) Notifications.post(c, Notifications.CHANNEL_NUDGE, 382, "Anything agreed on that call?",
                "Tap to say it. Atlas turns it into tasks and reminders.", route = "nx/aftercall")
        }
    }
}

@Composable
fun AfterCallScreen(container: AppContainer) {
    val c = LocalContext.current
    var on by remember { mutableStateOf(AfterCall.on(c)) }
    var text by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("What was agreed?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        OutlinedTextField(text, { text = it; ready = false }, Modifier.fillMaxWidth(), minLines = 3,
            placeholder = { Text("\"Ali will send the quote Friday, I pay 5k advance, call him Monday\"") },
            trailingIcon = { MicButton({ heard -> text = (text.trimEnd() + " " + heard).trim(); ready = false }) })
        if (text.isNotBlank()) { if (!ready) Capsule("Sort it", false) { ready = true } else ActOnIt(container, "Phone call", text) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ask me after every call", style = MaterialTheme.typography.bodyMedium)
                Text("Needs Atlas's screen control on (Phone control). Only the call screen closing is noticed.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(on, { on = it; AfterCall.setOn(c, it) })
        }
    }
}

// ══════════════════════════════ Atlas calls you ══════════════════════════════

/**
 * Atlas phones you: a real incoming-call screen with a ringtone, even on the lock screen. Pick up and it
 * talks you through your morning, gets you up for Fajr, or runs your evening review, and you can talk back.
 */
object AtlasCalls {
    const val CHANNEL = "atlas_call"
    val KINDS = listOf("morning" to "Morning briefing", "fajr" to "Fajr wake-up", "evening" to "Evening review")
    private fun p(c: Context) = c.getSharedPreferences("atlas_calls", 0)
    fun on(c: Context, k: String) = p(c).getBoolean("on_$k", false)
    fun time(c: Context, k: String) = p(c).getString("t_$k", if (k == "evening") "21:30" else "07:30")!!
    fun set(c: Context, k: String, on: Boolean, t: String) { p(c).edit().putBoolean("on_$k", on).putString("t_$k", t).apply() }

    private suspend fun fajr(c: Context): LocalTime? = runCatching {
        val s = (c.applicationContext as com.nizam.app.NizamApplication).container.settingsRepository
        val t = com.nizam.app.feature.prayer.PrayerTimes.calculate(LocalDate.now().plusDays(1), s.latitude.first(), s.longitude.first(),
            runCatching { com.nizam.app.feature.prayer.PrayerTimes.Method.valueOf(s.prayerMethod.first()) }.getOrElse { com.nizam.app.feature.prayer.PrayerTimes.Method.KARACHI },
            s.hanafiAsr.first()).fajr
        LocalTime.of(t.toInt() % 24, ((t - t.toInt()) * 60).toInt().coerceIn(0, 59))
    }.getOrNull()

    /** Sets the next ring for each call that's on. Safe to call often. */
    suspend fun ensure(c: Context) {
        val alarm = c.getSystemService(AlarmManager::class.java) ?: return
        KINDS.forEachIndexed { i, (k, _) ->
            val pi = PendingIntent.getBroadcast(c, 900 + i, Intent(c, AtlasCallReceiver::class.java).putExtra("kind", k), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            if (!on(c, k)) { alarm.cancel(pi); return@forEachIndexed }
            val lt = if (k == "fajr") fajr(c) ?: return@forEachIndexed else runCatching { LocalTime.parse(time(c, k)) }.getOrNull() ?: return@forEachIndexed
            var at = LocalDateTime.of(LocalDate.now(), lt)
            if (!at.isAfter(LocalDateTime.now())) at = at.plusDays(1)
            val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val exact = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()
            if (exact) alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi) else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
        }
    }

    fun ring(c: Context, kind: String) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Atlas calls", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When Atlas phones you"; setSound(null, null) })
        val open = Intent(c, AtlasCallActivity::class.java).putExtra("kind", kind).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = PendingIntent.getActivity(c, 950, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(c, CHANNEL).setSmallIcon(android.R.drawable.sym_call_incoming)
            .setContentTitle("Atlas is calling").setContentText(KINDS.firstOrNull { it.first == kind }?.second ?: "Atlas")
            .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true).setContentIntent(pi).setAutoCancel(true).setTimeoutAfter(60_000).build()
        runCatching { NotificationManagerCompat.from(c).notify(951, n) }
    }

    /** What Atlas says when you pick up: built on the phone from your own day, no internet needed. */
    suspend fun script(c: Context, kind: String): String {
        val container = (c.applicationContext as com.nizam.app.NizamApplication).container
        val name = runCatching { com.nizam.app.feature.presence.Presence.atlasName(c) }.getOrDefault("Atlas")
        val tasks = runCatching { container.taskRepository.all().first().filter { it.completedAt == null } }.getOrDefault(emptyList())
        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()
        val due = tasks.filter { t -> t.due?.let { java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate() }?.let { !it.isAfter(today) } == true }
        val spentY = runCatching { container.financeRepository.since(today.minusDays(1).toString()).first()
            .filter { it.kind == "EXPENSE" && it.localDate == today.minusDays(1).toString() }.sumOf { it.amount } }.getOrDefault(0.0)
        val greet = when (LocalTime.now().hour) { in 3..11 -> "Good morning"; in 12..16 -> "Good afternoon"; else -> "Good evening" }
        return when (kind) {
            "fajr" -> "Assalamu alaikum. It's time for Fajr. Get up, make wudu, and pray. The day starts better from here. I'll be quiet now."
            "evening" -> {
                val doneToday = runCatching { container.taskRepository.all().first().count { it.completedAt?.let { t -> java.time.Instant.ofEpochMilli(t).atZone(zone).toLocalDate() } == today } }.getOrDefault(0)
                "$greet. Quick review. You finished $doneToday task${if (doneToday == 1) "" else "s"} today. " +
                    (if (due.isNotEmpty()) "Still open: ${due.take(3).joinToString(", ") { it.title }}. Move them to tomorrow or let them go. " else "Nothing urgent left open. ") +
                    "What's one thing that went well today? Tell me, or just say goodnight."
            }
            else -> "$greet. This is $name with your morning briefing. It's ${today.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }}. " +
                (if (due.isEmpty()) "Nothing is due today, so pick one thing that matters and start it. " else "You have ${due.size} thing${if (due.size > 1) "s" else ""} due: ${due.take(3).joinToString(", ") { it.title }}. ") +
                (if (spentY > 0) "Yesterday you spent ${"%,d".format(spentY.toLong())} rupees. " else "") +
                "Drink some water before your phone. Anything you want me to note? Just tell me."
        }
    }
}

class AtlasCallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val kind = intent.getStringExtra("kind") ?: "morning"
        AtlasCalls.ring(context, kind)
        val pending = goAsync()
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default + com.nizam.app.feature.safe.CoroutineSafetyNet.inScope).launch { runCatching { AtlasCalls.ensure(context) }; pending.finish() }
    }
}

class AtlasCallActivity : ComponentActivity() {
    private var ringtone: android.media.Ringtone? = null
    private var speaker: com.nizam.app.feature.voice.Speaker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        val kind = intent.getStringExtra("kind") ?: "morning"
        runCatching { NotificationManagerCompat.from(this).cancel(951) }
        ringtone = runCatching { android.media.RingtoneManager.getRingtone(this, android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_RINGTONE)) }.getOrNull()
        runCatching { ringtone?.play() }
        speaker = com.nizam.app.feature.voice.Speaker(this)
        setContent {
            com.nizam.app.ui.theme.NizamTheme(darkTheme = true) {
                var answered by remember { mutableStateOf(false) }
                var said by remember { mutableStateOf("") }
                val lines = remember { mutableStateListOf<Pair<Boolean, String>>() }
                var busy by remember { mutableStateOf(false) }
                val scope = com.nizam.app.ui.design.rememberSafeScope()
                LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { delay(45_000); if (!answered) finish() } }
                val pulse = rememberInfiniteTransition(label = "ring").animateFloat(1f, 1.12f, infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse), label = "p")
                Column(Modifier.fillMaxSize().background(Color(0xFF070B14)).padding(28.dp).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(60.dp))
                    Text(if (answered) "On call with Atlas" else "Atlas is calling…", color = Color(0xFFB8C4D8), fontSize = 16.sp)
                    Text(AtlasCalls.KINDS.firstOrNull { it.first == kind }?.second ?: "Atlas", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 6.dp))
                    Spacer(Modifier.height(36.dp))
                    Box(Modifier.size(150.dp).scale(if (answered) 1f else pulse.value), contentAlignment = Alignment.Center) {
                        com.nizam.app.ui.style.AtlasLogo(if (busy) com.nizam.app.ui.design.PulseState.THINKING else com.nizam.app.ui.design.PulseState.IDLE,
                            Modifier.size(150.dp), 150.dp)
                    }
                    Spacer(Modifier.height(30.dp))
                    lines.forEach { (me, t) ->
                        Text(t, color = if (me) Color(0xFF9FD3FF) else Color(0xFFEDEFF5), fontSize = 16.sp, textAlign = if (me) TextAlign.End else TextAlign.Start,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    }
                    Spacer(Modifier.height(24.dp))
                    if (!answered) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        CallButton("Decline", Color(0xFFE5484D)) { finish() }
                        CallButton("Answer", Color(0xFF2E9E6A)) {
                            answered = true; runCatching { ringtone?.stop() }
                            scope.launch {
                                val s = AtlasCalls.script(this@AtlasCallActivity, kind)
                                lines += false to s; speaker?.speak(s)
                            }
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (busy) "Thinking…" else "Talk back", color = Color(0xFFB8C4D8))
                            MicButton({ heard ->
                                lines += true to heard; busy = true
                                scope.launch {
                                    val container = (applicationContext as com.nizam.app.NizamApplication).container
                                    val r = runCatching {
                                        Ai.generate(container.settingsRepository, lines.takeLast(8).joinToString("\n") { (me, t) -> (if (me) "User: " else "Atlas: ") + t } + "\nAtlas:",
                                            system = "You are Atlas on a short phone call with the user. Reply in one to three spoken sentences, warm and practical. " +
                                                "If they ask you to note or remind something, say you've noted it and repeat it back.", maxOutputTokens = 220)
                                    }.getOrElse { "I couldn't reach my brain just now. I'll keep it short: you've got this." }
                                    lines += false to r.trim(); speaker?.speak(r); busy = false
                                    if (Regex("(?i)remind|note|task|todo|don't forget").containsMatchIn(heard)) runCatching { container.taskRepository.add(heard.take(140), null, 1) }
                                }
                            })
                        }
                        Spacer(Modifier.height(16.dp))
                        CallButton("End call", Color(0xFFE5484D)) { finish() }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { ringtone?.stop() }; runCatching { speaker?.release() }
        super.onDestroy()
    }
}

@Composable
private fun CallButton(label: String, color: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).background(color, CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
            Text(if (label == "Answer") "📞" else "✕", fontSize = 26.sp, color = Color.White)
        }
        Text(label, color = Color(0xFFB8C4D8), modifier = Modifier.padding(top = 8.dp))
    }
}


@Composable
fun AtlasCallsScreen() {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var tick by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Atlas rings like a real call, even on the lock screen. Pick up and it talks you through it; you can talk back.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        AtlasCalls.KINDS.forEach { (k, label) ->
            var on by remember(tick) { mutableStateOf(AtlasCalls.on(c, k)) }
            var t by remember(tick) { mutableStateOf(AtlasCalls.time(c, k)) }
            Group {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(label, fontWeight = FontWeight.SemiBold)
                        Text(if (k == "fajr") "Rings at Fajr time each day" else "Rings daily at $t", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(on, { v -> on = v; AtlasCalls.set(c, k, v, t); scope.launch { runCatching { AtlasCalls.ensure(c) } } })
                }
                if (k != "fajr") LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
                    val opts = if (k == "evening") listOf("20:30", "21:00", "21:30", "22:00", "22:30", "23:00") else listOf("06:00", "06:30", "07:00", "07:30", "08:00", "08:30", "09:00")
                    items(opts.size) { i -> Capsule(opts[i], t == opts[i]) { t = opts[i]; AtlasCalls.set(c, k, on, t); scope.launch { runCatching { AtlasCalls.ensure(c) } } } }
                }
            }
        }
        Button(onClick = { AtlasCalls.ring(c, "morning") }) { Text("Test: call me now") }
        Text("If the call only shows as a notification, allow Atlas to show full-screen alerts in Android settings.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

val NewPowerSpecs: List<Spec> get() = listOf(
    ScreenSpec("nightshift", "Night Shift", "Queue jobs before bed; Atlas works them overnight and leaves a morning report",
        Icons.Outlined.Bedtime, Hub.Tools, "night shift overnight while i sleep queue jobs research draft morning report") { container, _ -> NightShiftScreen(container) },
    ScreenSpec("raceghost", "Race your ghost", "You today vs yesterday-you at this exact time",
        Icons.Outlined.DirectionsRun, Hub.Tools, "ghost race yesterday compare beat myself progress") { container, _ -> GhostScreen(container) },
    ScreenSpec("aftercall", "After-call catch", "When a call ends, say what was agreed and it becomes tasks",
        Icons.Outlined.PhoneCallback, Hub.Work, "after call agreed promise phone call tasks follow up") { container, _ -> AfterCallScreen(container) },
    ScreenSpec("atlascalls", "Atlas calls you", "A real incoming call for your morning briefing, Fajr wake-up or evening review",
        Icons.Outlined.Call, Hub.Tools, "atlas calls me phone call ring morning briefing fajr wake up evening review talk") { _, _ -> AtlasCallsScreen() },
)
