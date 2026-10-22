package com.nizam.app.feature.next

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.nizam.app.AppContainer
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.SectionHeader
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime

// ── Privacy alerts ────────────────────────────────────────────────────────────────────────────────

/** Which installed apps can reach your microphone, camera or location — and which can do it in the background. */
object PrivacyAudit {
    data class Finding(val pkg: String, val label: String, val mic: Boolean, val cam: Boolean, val loc: Boolean, val bgLoc: Boolean, val system: Boolean)

    fun scan(c: Context): List<Finding> {
        val pm = c.packageManager
        @Suppress("DEPRECATION")
        val all: List<PackageInfo> = runCatching { pm.getInstalledPackages(PackageManager.GET_PERMISSIONS) }.getOrElse { emptyList() }
        fun granted(pkg: String, perm: String) = pm.checkPermission(perm, pkg) == PackageManager.PERMISSION_GRANTED
        return all.filter { it.packageName != c.packageName }.mapNotNull { pi ->
            val pkg = pi.packageName
            val mic = granted(pkg, android.Manifest.permission.RECORD_AUDIO)
            val cam = granted(pkg, android.Manifest.permission.CAMERA)
            val loc = granted(pkg, android.Manifest.permission.ACCESS_FINE_LOCATION) || granted(pkg, android.Manifest.permission.ACCESS_COARSE_LOCATION)
            val bg = android.os.Build.VERSION.SDK_INT >= 29 && granted(pkg, android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            if (!mic && !cam && !loc) return@mapNotNull null
            val sys = (pi.applicationInfo?.flags ?: 0) and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
            Finding(pkg, pi.applicationInfo?.loadLabel(pm)?.toString() ?: pkg, mic, cam, loc, bg, sys)
        }.sortedWith(compareByDescending<Finding> { it.bgLoc }.thenByDescending { (if (it.mic) 1 else 0) + (if (it.cam) 1 else 0) + (if (it.loc) 1 else 0) })
    }
}

@Composable
fun PrivacyAlertsScreen() {
    val ctx = LocalContext.current
    var list by remember { mutableStateOf<List<PrivacyAudit.Finding>?>(null) }
    var showSystem by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { list = withContext(Dispatchers.IO) { PrivacyAudit.scan(ctx) } } }
    val shown = list?.filter { showSystem || !it.system }
    AtlasScreen(title = "Privacy alerts", subtitle = "Apps that can use your mic, camera or location", loading = list == null) {
        item("sys") {
            ListRow("Include system apps", trailing = { Switch(showSystem, { showSystem = it }) })
        }
        val bg = shown.orEmpty().filter { it.bgLoc }
        if (bg.isNotEmpty()) item("bg") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text("${bg.size} app${if (bg.size == 1) "" else "s"} can track your location even when closed: ${bg.take(5).joinToString { it.label }}",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        shown.orEmpty().forEach { f ->
            item(f.pkg) {
                val what = listOfNotNull(if (f.mic) "mic" else null, if (f.cam) "camera" else null,
                    if (f.bgLoc) "location (always)" else if (f.loc) "location" else null).joinToString(" · ")
                ListRow(f.label, supporting = what, icon = when { f.bgLoc || f.loc -> Icons.Outlined.LocationOn; f.mic -> Icons.Outlined.Mic; else -> Icons.Outlined.CameraAlt },
                    tint = if (f.bgLoc) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary) {
                    runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${f.pkg}"))) }
                }
            }
        }
    }
}

// ── Load-shedding planner ─────────────────────────────────────────────────────────────────────────

/** Your area's power cuts, typed once. Atlas reminds you before each one: charge up, run the pump, save your work. */
object LoadShedding {
    private fun p(c: Context) = c.getSharedPreferences("loadshedding", 0)
    fun on(c: Context) = p(c).getBoolean("on", false)
    fun set(c: Context, v: Boolean) = p(c).edit().putBoolean("on", v).apply()
    /** Times like "08:00-10:00, 14:00-15:00, 20:00-21:00". */
    fun schedule(c: Context) = p(c).getString("times", "").orEmpty()
    fun setSchedule(c: Context, v: String) = p(c).edit().putString("times", v).apply()
    fun lead(c: Context) = p(c).getInt("lead", 20)

    fun cuts(c: Context): List<Pair<LocalTime, LocalTime>> = schedule(c).split(",").mapNotNull { part ->
        val t = part.trim().split("-")
        if (t.size != 2) null else runCatching { LocalTime.parse(t[0].trim().padStart(5, '0')) to LocalTime.parse(t[1].trim().padStart(5, '0')) }.getOrNull()
    }

    fun next(c: Context): Pair<LocalTime, LocalTime>? = LocalTime.now().let { now -> cuts(c).firstOrNull { it.first.isAfter(now) } }

    fun check(c: Context) {
        if (!on(c)) return
        val now = LocalTime.now()
        cuts(c).forEach { (start, end) ->
            val mins = java.time.Duration.between(now, start).toMinutes()
            if (mins in 0..lead(c).toLong()) {
                val key = "${LocalDate.now()}|$start"
                if (p(c).getBoolean(key, false)) return@forEach
                p(c).edit().putBoolean(key, true).apply()
                Notifications.post(c, Notifications.CHANNEL_NUDGE, 795, "Power cut at $start (in $mins min)",
                    "Until $end. Charge your phone and power bank, run the water pump, save anything you're working on.")
            }
        }
    }
}

@Composable
fun LoadSheddingScreen() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(LoadShedding.on(ctx)) }
    var times by remember { mutableStateOf(LoadShedding.schedule(ctx)) }
    AtlasScreen(title = "Load-shedding", subtitle = LoadShedding.next(ctx)?.let { "Next cut ${it.first}–${it.second}" } ?: "No cuts left today") {
        item("on") {
            ListRow("Remind me before each cut", supporting = "About ${LoadShedding.lead(ctx)} minutes ahead",
                icon = Icons.Outlined.BatteryChargingFull, tint = Hub.Home.tint(), trailing = { Switch(on, { on = it; LoadShedding.set(ctx, it) }) })
        }
        item("t") {
            Column(Modifier.padding(horizontal = Space.page)) {
                OutlinedTextField(times, { times = it }, Modifier.fillMaxWidth(), label = { Text("Your schedule") },
                    placeholder = { Text("08:00-10:00, 14:00-15:00, 20:00-21:00") })
                Button(onClick = { LoadShedding.setSchedule(ctx, times); com.nizam.app.ui.components.Feedback.show("Saved ${LoadShedding.cuts(ctx).size} cuts") },
                    modifier = Modifier.padding(top = Space.s)) { Text("Save") }
            }
        }
        val cuts = LoadShedding.cuts(ctx)
        if (cuts.isNotEmpty()) {
            item("h") { SectionHeader("Today") }
            cuts.forEach { (a, b) -> item("c$a") { ListRow("$a – $b", supporting = if (b.isBefore(LocalTime.now())) "Done" else "Upcoming") } }
        }
    }
}

// ── Family Day Ahead ──────────────────────────────────────────────────────────────────────────────

/** Today in a few lines — prayers, your plan, bills due — ready to drop into the family WhatsApp group. */
@Composable
fun FamilyBriefScreen(container: AppContainer) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard {
        text = withContext(Dispatchers.IO) {
            val s = container.settingsRepository
            val t = com.nizam.app.feature.prayer.PrayerTimes.calculate(LocalDate.now(), s.latitude.first(), s.longitude.first(),
                runCatching { com.nizam.app.feature.prayer.PrayerTimes.Method.valueOf(s.prayerMethod.first()) }.getOrElse { com.nizam.app.feature.prayer.PrayerTimes.Method.KARACHI },
                s.hanafiAsr.first())
            val tasks = runCatching { container.taskRepository.all().first().filter { it.completedAt == null }.take(5) }.getOrElse { emptyList() }
            val cuts = LoadShedding.cuts(ctx)
            buildString {
                append("🌅 ${LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEEE d MMMM"))}\n")
                append("🕌 Fajr ${com.nizam.app.core.hourToClock(t.fajr)} · Dhuhr ${com.nizam.app.core.hourToClock(t.dhuhr)} · Asr ${com.nizam.app.core.hourToClock(t.asr)} · " +
                    "Maghrib ${com.nizam.app.core.hourToClock(t.maghrib)} · Isha ${com.nizam.app.core.hourToClock(t.isha)}\n")
                if (tasks.isNotEmpty()) append("✅ Today: " + tasks.joinToString(", ") { it.title } + "\n")
                if (cuts.isNotEmpty()) append("⚡ Power cuts: " + cuts.joinToString(", ") { "${it.first}–${it.second}" } + "\n")
                append("— from Atlas")
            }
        }
    } }
    AtlasScreen(title = "Family Day Ahead", subtitle = "Today for everyone, in one message") {
        item("t") {
            Group(Modifier.padding(horizontal = Space.page)) {
                SelectionContainer { Text(text, style = MaterialTheme.typography.bodyLarge) }
                Row(Modifier.padding(top = Space.m), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Button(onClick = {
                        val wa = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).setPackage("com.whatsapp")
                        runCatching { ctx.startActivity(wa) }.onFailure {
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Send"))
                        }
                    }, enabled = text.isNotBlank()) { Text("Send to family") }
                }
            }
        }
    }
}
