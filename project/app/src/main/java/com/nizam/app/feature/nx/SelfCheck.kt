package com.nizam.app.feature.nx

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.ManageHistory
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Self-Check: the database, every saved store, permissions, AI setup, failed jobs and offline readiness,
 * each with a one-tap repair where one exists.
 */
object SelfCheck {
    enum class Level { OK, WARN, BAD }
    data class Finding(val area: String, val level: Level, val text: String, val fix: String? = null, val action: String? = null, val arg: String = "")

    suspend fun run(container: AppContainer): List<Finding> = withContext(Dispatchers.IO) {
        val c = container.appContext; val out = mutableListOf<Finding>()
        // database
        out += runCatching { val n = container.taskRepository.all().first().size; Finding("Database", Level.OK, "Encrypted database opens · $n tasks") }
            .getOrElse { Finding("Database", Level.BAD, "The database didn't open: ${it.message}") }
        // stores
        val stores = c.filesDir.listFiles().orEmpty().filter { it.name.endsWith(".json") && it.name.removeSuffix(".json").matches(Regex("[A-Za-z0-9_-]{1,64}")) }
        var broken = 0
        stores.forEach { f -> val name = f.name.removeSuffix(".json")
            val head = runCatching { f.inputStream().use { s -> ByteArray(20).let { b -> String(b, 0, s.read(b).coerceAtLeast(0)) } } }.getOrDefault("")
            if (!head.startsWith("ATLAS_JSON_STORE")) return@forEach   // only stores written by JsonStore
            val st = JsonStore(c, name); st.all()
            if (st.lastReadError != null) { broken++
                out += Finding("Saved data", Level.BAD, "\"$name\" can't be read", "Restore last good copy", "recover", name) }
        }
        if (broken == 0) out += Finding("Saved data", Level.OK, "${stores.size} saved files checked, all readable")
        // storage
        val freeMb = c.filesDir.usableSpace / 1_048_576
        out += if (freeMb < 300) Finding("Storage", Level.WARN, "Only $freeMb MB free on the phone", "Free up space", "storage") else Finding("Storage", Level.OK, "$freeMb MB free")
        // permissions
        val notif = android.os.Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(c, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        out += if (notif) Finding("Permissions", Level.OK, "Notifications allowed") else Finding("Permissions", Level.WARN, "Notifications are off — prayer alerts and reminders can't reach you", "Open settings", "appsettings")
        val pm = c.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        out += if (pm.isIgnoringBatteryOptimizations(c.packageName)) Finding("Background", Level.OK, "Battery saver won't stop reminders")
            else Finding("Background", Level.WARN, "Battery optimisation may delay reminders and automations", "Allow background", "battery")
        // AI
        val keys = runCatching { com.nizam.app.net.ApiKeys.SLOTS.count { com.nizam.app.net.ApiKeys.enabled(c, it.id) } }.getOrDefault(0)
        val local = runCatching { com.nizam.app.net.LocalModel.available(c) }.getOrDefault(false)
        out += when { keys == 0 && !local -> Finding("AI", Level.BAD, "No AI key and no offline model — AI features can't answer", "Add a key", "open", "settings")
            !local -> Finding("AI", Level.WARN, "$keys AI keys on · no offline model, so nothing works without internet", "Get offline model", "open", "settings")
            else -> Finding("AI", Level.OK, "$keys AI keys on · offline model ready") }
        val online = runCatching { com.nizam.app.net.Router.online(c) }.getOrDefault(true)
        if (!online) out += Finding("Network", Level.WARN, "Offline right now — online models are skipped")
        // jobs
        val failed = runCatching { com.nizam.app.feature.agent.AutoRules.failures(c).size }.getOrDefault(0)
        out += if (failed > 0) Finding("Automations", Level.WARN, "$failed automation runs failed", "Review", "open", "nx/autorules") else Finding("Automations", Level.OK, "No failed automation runs")
        val pending = runCatching { com.nizam.app.feature.agent.ApprovalInbox.pending(c).size }.getOrDefault(0)
        if (pending > 0) out += Finding("Approvals", Level.WARN, "$pending actions are waiting for your OK", "Open", "open", "nx/trust")
        val acts = runCatching { com.nizam.app.feature.next.Activity.all(c).take(50) }.getOrDefault(emptyList())
        val bad = acts.count { !it.optBoolean("ok") }
        if (bad >= 5) out += Finding("Agent actions", Level.WARN, "$bad of the last ${acts.size} actions Atlas took failed", "See activity", "open", "activity")
        // backups
        val lastBackup = c.getSharedPreferences("backup", 0).getLong("last", 0L)
        out += if (lastBackup == 0L || System.currentTimeMillis() - lastBackup > 14 * 86_400_000L)
            Finding("Backup", Level.WARN, if (lastBackup == 0L) "No backup recorded on this phone" else "Last backup was over two weeks ago", "Back up now", "open", "settings")
            else Finding("Backup", Level.OK, "Backed up recently")
        // history
        JsonStore(c, "nx_selfcheck").let { s -> s.add(JSONObject().put("at", System.currentTimeMillis()).put("bad", out.count { it.level == Level.BAD }).put("warn", out.count { it.level == Level.WARN }))
            s.all().let { l -> l.take(maxOf(0, l.size - 20)).forEach { s.remove(it.optString("id")) } } }
        out.sortedBy { it.level != Level.BAD }.sortedBy { it.level == Level.OK }
    }
}

@Composable
private fun SelfCheckScreen(container: AppContainer, open: (String) -> Unit) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<List<SelfCheck.Finding>?>(null) }
    var msg by remember { mutableStateOf("") }
    fun rerun() = scope.launch { list = null; list = SelfCheck.run(container) }
    LaunchedEffect(Unit) { list = SelfCheck.run(container) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val l = list ?: run { Text("Checking everything…"); return@Column }
        val bad = l.count { it.level == SelfCheck.Level.BAD }; val warn = l.count { it.level == SelfCheck.Level.WARN }
        Text(when { bad > 0 -> "🔴 $bad problems need fixing"; warn > 0 -> "🟡 Healthy, with $warn things worth a look"; else -> "🟢 Everything is healthy" },
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall)
        l.forEach { f -> Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${when (f.level) { SelfCheck.Level.OK -> "🟢"; SelfCheck.Level.WARN -> "🟡"; else -> "🔴" }} ${f.area}", fontWeight = FontWeight.SemiBold)
                Text(f.text, style = MaterialTheme.typography.bodySmall)
            }
            if (f.fix != null) TextButton(onClick = {
                when (f.action) {
                    "recover" -> { msg = runCatching { if (JsonStore(c, f.arg).recoverLastGood()) "Restored \"${f.arg}\" from its last good copy." else "No earlier copy of \"${f.arg}\" exists." }
                        .getOrElse { "Couldn't restore: ${it.message}" }; rerun() }
                    "storage" -> runCatching { c.startActivity(Intent(Settings.ACTION_INTERNAL_STORAGE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    "appsettings" -> runCatching { c.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, c.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    "battery" -> runCatching { c.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    "open" -> open(f.arg)
                }
            }) { Text(f.fix) }
        } }
        TextButton(onClick = { rerun() }) { Text("Check again") }
        val hist = remember(list) { JsonStore(c, "nx_selfcheck").all().takeLast(10) }
        if (hist.size > 1) Text("Recent checks: " + hist.joinToString(" ") { if (it.optInt("bad") > 0) "🔴" else if (it.optInt("warn") > 0) "🟡" else "🟢" }, style = MaterialTheme.typography.bodySmall)
    }
}

/** One place for every change Atlas or an automation made: the activity receipts, automation runs and applied care packs. */
@Composable
private fun ChangeLedger() {
    val c = LocalContext.current
    val rows = remember {
        val acts = com.nizam.app.feature.next.Activity.all(c).map { Triple(it.optLong("t"), "${if (it.optBoolean("ok")) "✓" else "✗"} Atlas · ${it.optString("a").replace('_', ' ')}", it.optString("s")) }
        val fails = runCatching { com.nizam.app.feature.agent.AutoRules.failures(c) }.getOrDefault(emptyList())
            .map { Triple(it.optLong("at", it.optLong("t")), "✗ Automation · ${it.optString("rule", it.optString("name"))}", it.optString("error", it.optString("why"))) }
        val packs = JsonStore(c, "nx_carepack_log").all().map { Triple(it.optLong("at"), "✓ Care pack · from ${it.optString("from")}", it.optJSONArray("changes")?.length()?.let { n -> "$n changes" }.orEmpty()) }
        (acts + fails + packs).sortedByDescending { it.first }.take(200)
    }
    val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM, h:mm a")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (rows.isEmpty()) Text("No changes recorded yet.", style = MaterialTheme.typography.bodyMedium)
        rows.forEach { (t, who, what) -> Column(Modifier.padding(vertical = 3.dp)) {
            Text(who, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
            Text((if (t > 0) java.time.Instant.ofEpochMilli(t).atZone(java.time.ZoneId.systemDefault()).format(fmt) + " · " else "") + what, style = MaterialTheme.typography.bodySmall)
        } }
    }
}

object SelfCheckSpecs {
    val all: List<Spec> by lazy { listOf(
        ScreenSpec("selfcheck", "Self-Check", "Database, saved data, permissions, AI, failed jobs and backups — with one-tap repairs", Icons.Outlined.HealthAndSafety, Hub.Tools,
            "self check health diagnostics repair broken fix doctor it department") { c, open -> SelfCheckScreen(c, open) },
        ScreenSpec("ledger", "Change ledger", "Every change Atlas, automations and care packs made, newest first", Icons.Outlined.ManageHistory, Hub.Tools,
            "change ledger audit trail history log who changed what") { _, _ -> ChangeLedger() },
    ) }
}
