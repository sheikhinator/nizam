package com.nizam.app.feature.platform2

import androidx.compose.foundation.lazy.LazyRow

import androidx.compose.foundation.lazy.items

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nizam.app.AppContainer
import com.nizam.app.feature.modes.AutoModes
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.nav.ReAuth
import com.nizam.app.ui.nav.Routes
import com.nizam.app.ui.screens.hubs.HubRegistry
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Hides money amounts as dots until you tap. Reads are cheap and observable, so every screen refreshes when it changes. */
object AmountPrivacy {
    private var state by mutableStateOf(-1)          // -1 unknown, 0 shown, 1 hidden
    private var peek by mutableStateOf(0L)
    private fun p(c: Context?) = c?.getSharedPreferences("amountprivacy", 0)
    fun hidden(): Boolean {
        val c = com.nizam.app.net.Router.appContext
        if (state < 0) state = if (p(c)?.getBoolean("hide", false) == true) 1 else 0
        return state == 1 && System.currentTimeMillis() - peek > 8_000L
    }
    fun on(): Boolean { hidden(); return state == 1 }
    fun set(c: Context, v: Boolean) { p(c)?.edit()?.putBoolean("hide", v)?.apply(); state = if (v) 1 else 0 }
    /** Shows the figures for a few seconds after you authenticate. */
    fun reveal(c: Context) = ReAuth.prompt(c) { ok -> if (ok) peek = System.currentTimeMillis() }
}

/** Labs: switches for features still being tried. Real switches only. */
object Labs {
    val FLAGS = listOf(
        Triple("proactive", "Proactive suggestions on Today", true),
        Triple("beta_updates", "Beta update channel (new builds right away)", false),
    )
    fun on(c: Context, key: String) = c.getSharedPreferences("labs", 0).getBoolean(key, FLAGS.firstOrNull { it.first == key }?.third ?: false)
    fun set(c: Context, key: String, v: Boolean) = c.getSharedPreferences("labs", 0).edit().putBoolean(key, v).apply()
}

/** Restricted profiles on one phone: a child, an elder or a guest sees only the suites you allow. */
object Profiles {
    data class Prof(val id: String, val name: String, val emoji: String, val hubs: List<Hub>)
    private fun p(c: Context) = c.getSharedPreferences("profiles", 0)
    private fun load(c: Context): List<Prof> = runCatching { JSONArray(p(c).getString("list", "[]")) }.getOrElse { JSONArray() }.let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { o -> Prof(o.optString("id"), o.optString("name"), o.optString("emoji"),
            o.optString("hubs").split(",").mapNotNull { n -> Hub.entries.firstOrNull { it.name == n } }) } }
    fun all(c: Context) = load(c)
    fun save(c: Context, l: List<Prof>) = p(c).edit().putString("list", JSONArray(l.map { JSONObject().put("id", it.id).put("name", it.name).put("emoji", it.emoji).put("hubs", it.hubs.joinToString(",") { h -> h.name }) }).toString()).apply()
    fun add(c: Context, name: String, emoji: String, hubs: List<Hub>) = save(c, load(c) + Prof("p" + System.currentTimeMillis(), name, emoji, hubs))
    fun remove(c: Context, id: String) { save(c, load(c).filter { it.id != id }); if (activeId(c) == id) setActive(c, "") }
    fun activeId(c: Context) = p(c).getString("active", "").orEmpty()
    fun active(c: Context): Prof? = load(c).firstOrNull { it.id == activeId(c) }
    fun setActive(c: Context, id: String) = p(c).edit().putString("active", id).apply()
    fun allowedHubs(c: Context): List<Hub>? = active(c)?.hubs
    private val ALWAYS = setOf(Routes.TODAY, Routes.ASK, Routes.MODULES, Routes.ME, "nx/profiles")

    /** Why the current screen can't be opened, or null if it can. */
    fun blockedReason(c: Context, route: String?): String? {
        if (route == null) return null
        val prof = active(c)
        if (prof != null && route !in ALWAYS) {
            val hubName = if (route.startsWith("hub/")) route.removePrefix("hub/") else null
            val hub = hubName?.let { n -> Hub.entries.firstOrNull { it.name == n } } ?: HubRegistry.byRoute(route)?.hub
            if (hub == null || hub !in prof.hubs) return "${prof.name}'s profile can't open this screen."
        }
        if (AutoModes.current(c) == "guest" && ReAuth.GROUPS.any { (_, _, rs) -> rs.any { route == it || route.startsWith("$it/") } }) return "Guest mode hides this screen."
        return null
    }

    /** Switching back to the owner (or leaving Guest) needs the fingerprint or screen lock. */
    fun leave(c: Context, then: () -> Unit) = ReAuth.prompt(c) { ok -> if (ok) { setActive(c, ""); then() } }
}

/** A short, local log of when Atlas used a sensitive permission, so you can check nothing reaches further than expected. */
object PermLog {
    private fun store(c: Context) = JsonStore(c, "nx_permlog")
    fun note(c: Context?, kind: String, why: String) { if (c == null) return
        runCatching { val s = store(c); s.add(JSONObject().put("t", System.currentTimeMillis()).put("kind", kind).put("why", why))
            val l = s.all(); if (l.size > 400) l.sortedBy { it.optLong("t") }.take(l.size - 400).forEach { s.remove(it.optString("id")) } } }
    fun all(c: Context) = runCatching { store(c).all().sortedByDescending { it.optLong("t") } }.getOrDefault(emptyList())
}

/** One or two gentle suggestions based on your own data. Each can be done, snoozed to tomorrow, or silenced for good. */
object Suggestions {
    data class S(val id: String, val text: String, val route: String, val label: String)
    private fun p(c: Context) = c.getSharedPreferences("suggestions", 0)
    fun snooze(c: Context, id: String) = p(c).edit().putString("snooze:$id", LocalDate.now().toString()).apply()
    fun never(c: Context, id: String) = p(c).edit().putBoolean("never:$id", true).apply()
    private fun muted(c: Context, id: String) = p(c).getBoolean("never:$id", false) || p(c).getString("snooze:$id", "") == LocalDate.now().toString()

    suspend fun compute(container: AppContainer): List<S> {
        val c = container.appContext; if (!Labs.on(c, "proactive")) return emptyList()
        val hour = java.time.LocalTime.now().hour; val out = mutableListOf<S>()
        val now = System.currentTimeMillis()
        val overdue = runCatching { container.taskRepository.all().first().count { it.completedAt == null && it.due != null && it.due < now - 3_600_000L } }.getOrDefault(0)
        if (overdue > 0) out += S("overdue", "$overdue tasks are overdue. Move or drop them?", "nx/carryover", "Review")
        val sum = runCatching { com.nizam.app.feature.progress.Xp.summarise(container) }.getOrNull()
        if (hour >= 20 && sum != null && sum.today.xp == 0 && sum.streak > 0) out += S("streak", "Your ${sum.streak}-day streak ends tonight. One small task keeps it alive.", Routes.TASKS, "Add a task")
        if (hour >= 20 && runCatching { container.financeRepository.since(LocalDate.now().toString()).first().none { it.kind == "EXPENSE" } }.getOrDefault(false)) out += S("noexpense", "No expenses logged today. Anything to add?", Routes.ASK, "Log one")
        val due = runCatching { com.nizam.app.feature.deen.HifzStore.all(c).count { !com.nizam.app.feature.deen.HifzStore.due(it).isAfter(LocalDate.now()) } }.getOrDefault(0)
        if (due > 0) out += S("hifz", "$due surahs are due for revision.", Routes.QURAN, "Revise")
        val last = c.getSharedPreferences("backup", 0).getLong("last", 0L)
        if (System.currentTimeMillis() - last > 14 * 86_400_000L && !com.nizam.app.feature.backup.AutoBackup.on(c)) out += S("backup", "No recent backup. Turn on the weekly one?", "nx/autobackup", "Set up")
        return out.filter { !muted(c, it.id) }.take(2)
    }
}

@Composable
fun SuggestionsCard(container: AppContainer, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = LocalContext.current
    var list by remember { mutableStateOf<List<Suggestions.S>>(emptyList()) }
    LaunchedEffect(Unit) { list = runCatching { Suggestions.compute(container) }.getOrDefault(emptyList()) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        list.forEach { s -> Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text("💡 ${s.text}", style = MaterialTheme.typography.bodyMedium)
            Row { TextButton(onClick = { onOpen(s.route) }) { Text(s.label) }
                TextButton(onClick = { Suggestions.snooze(c, s.id); list = list - s }) { Text("Not now") }
                TextButton(onClick = { Suggestions.never(c, s.id); list = list - s }) { Text("Never") } } } }
    }
}

@Composable
fun BlockedCover(reason: String, onBack: () -> Unit) {
    val c = LocalContext.current
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🔒", style = MaterialTheme.typography.displayMedium)
        Text(reason, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(12.dp))
        Button(onClick = { Profiles.leave(c) { onBack() } }) { Text("Unlock with fingerprint or screen lock") }
        TextButton(onClick = onBack) { Text("Go back") }
    }
}

@Composable
private fun ProfilesScreen() {
    val c = LocalContext.current
    var list by remember { mutableStateOf(Profiles.all(c)) }
    var active by remember { mutableStateOf(Profiles.activeId(c)) }
    var name by remember { mutableStateOf("") }; var emoji by remember { mutableStateOf("🧒") }
    var picked by remember { mutableStateOf(setOf(Hub.Learn, Hub.Deen)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("A restricted profile lets someone use Atlas on your phone with only the suites you choose. Leaving it needs your fingerprint or screen lock. Profiles change what is visible; everyone still shares this phone's data store.", style = MaterialTheme.typography.bodyMedium)
        Text(if (active.isBlank()) "Now: you (full access)" else "Now: ${Profiles.active(c)?.name}", fontWeight = FontWeight.SemiBold)
        if (active.isNotBlank()) TextButton(onClick = { Profiles.leave(c) { active = "" } }) { Text("Switch back to me") }
        list.forEach { pr -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${pr.emoji} ${pr.name} · ${pr.hubs.joinToString { it.label }}", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
            if (active != pr.id) TextButton(onClick = { Profiles.setActive(c, pr.id); active = pr.id }) { Text("Use") }
            TextButton(onClick = { Profiles.remove(c, pr.id); list = Profiles.all(c); active = Profiles.activeId(c) }) { Text("✕") } } }
        Text("New profile", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Row { listOf("🧒" to "Child", "🧓" to "Elder", "👤" to "Guest").forEach { (e, l) -> TextButton(onClick = { emoji = e; name = l
            picked = when (l) { "Child" -> setOf(Hub.Learn, Hub.Deen, Hub.Create); "Elder" -> setOf(Hub.Deen, Hub.Health, Hub.Home); else -> setOf(Hub.Media, Hub.Travel) } }) { Text("$e $l") } } }
        OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { items(Hub.entries.toList()) { h ->
            FilterChip(selected = h in picked, onClick = { picked = if (h in picked) picked - h else picked + h }, label = { Text(h.label) }) } }
        TextButton(enabled = name.isNotBlank() && picked.isNotEmpty(), onClick = { Profiles.add(c, name, emoji, picked.toList()); list = Profiles.all(c); name = "" }) { Text("Create profile") }
    }
}

@Composable
private fun LabsScreen() {
    val c = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Switches for things still being tried. Turn one off if it ever gets in the way.", style = MaterialTheme.typography.bodyMedium)
        Labs.FLAGS.forEach { (k, label, _) -> Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f)); Switch(Labs.on(c, k), { Labs.set(c, k, it); tick++ }) } }
        Row(verticalAlignment = Alignment.CenterVertically) { Text("Hide money amounts (tap to reveal)", Modifier.weight(1f)); Switch(AmountPrivacy.on(), { AmountPrivacy.set(c, it); tick++ }) }
    }
}

@Composable
private fun PermTimeline() {
    val c = LocalContext.current
    val perms = listOf("Camera" to Manifest.permission.CAMERA, "Microphone" to Manifest.permission.RECORD_AUDIO, "Location" to Manifest.permission.ACCESS_FINE_LOCATION, "SMS" to Manifest.permission.READ_SMS,
        "Contacts" to Manifest.permission.READ_CONTACTS, "Calendar" to Manifest.permission.READ_CALENDAR)
    val log = remember { PermLog.all(c) }
    val fmt = java.time.format.DateTimeFormatter.ofPattern("d MMM, h:mm a")
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("What Atlas is allowed to use right now, and when it last did.", style = MaterialTheme.typography.bodyMedium)
        perms.forEach { (n, p) -> val ok = ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED
            val last = log.firstOrNull { it.optString("kind").equals(n, true) }
            Text("${if (ok) "🟢" else "⚪"} $n · ${if (ok) "allowed" else "not allowed"}${last?.let { " · last used ${java.time.Instant.ofEpochMilli(it.optLong("t")).atZone(java.time.ZoneId.systemDefault()).format(fmt)} (${it.optString("why")})" } ?: ""}", style = MaterialTheme.typography.bodySmall) }
        TextButton(onClick = { c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${c.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }) { Text("Open Android permission settings") }
        Text("Recent uses", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        log.take(30).forEach { Text("${java.time.Instant.ofEpochMilli(it.optLong("t")).atZone(java.time.ZoneId.systemDefault()).format(fmt)} · ${it.optString("kind")} · ${it.optString("why")}", style = MaterialTheme.typography.bodySmall) }
        if (log.isEmpty()) Text("Nothing logged yet. Uses are recorded from now on.", style = MaterialTheme.typography.bodySmall)
    }
}

object Platform1Specs {
    val all = listOf(
        ScreenSpec("profiles", "Profiles on this phone", "Child, elder or guest profiles that see only the suites you allow", Icons.Outlined.SupervisedUserCircle, Hub.Social, "profiles child elder guest family restricted parental controls kids mode share phone") { _, _ -> ProfilesScreen() },
        ScreenSpec("labs", "Labs & privacy switches", "Beta features, proactive suggestions and hide-amounts mode", Icons.Outlined.Science, Hub.Tools, "labs beta experimental proactive suggestions hide amounts privacy dots") { _, _ -> LabsScreen() },
        ScreenSpec("permtimeline", "Permission timeline", "What Atlas may use, and when it last did", Icons.Outlined.History, Hub.Tools, "permission timeline privacy camera microphone location sms contacts calendar audit") { _, _ -> PermTimeline() },
    )
}
