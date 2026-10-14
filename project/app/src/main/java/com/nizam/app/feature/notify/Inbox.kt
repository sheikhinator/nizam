package com.nizam.app.feature.notify

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.feature.next.JsonStore
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Everything Atlas tells you also lands here, so nothing is lost if you swipe it away. With digests on,
 * routine reminders are held and delivered as one summary at 8am, 2pm and 8pm; prayer alerts and anything urgent are never held.
 */
object Inbox {
    private fun store(c: Context) = JsonStore(c, "nx_inbox")
    private fun p(c: Context) = c.getSharedPreferences("inbox", 0)
    fun digestOn(c: Context) = p(c).getBoolean("digest", false)
    fun setDigest(c: Context, v: Boolean) = p(c).edit().putBoolean("digest", v).apply()
    @Volatile var bypass = false
    private val URGENT = Regex("prayer|salah|azan|adhan|sos|emergency|alert|scam|intruder|lost|stolen|fall|safe arrival|medicine|medicines|jamaat|iftar|sehri|fajr|maghrib", RegexOption.IGNORE_CASE)

    fun all(c: Context) = store(c).all().sortedByDescending { it.optLong("t") }
    fun unread(c: Context) = runCatching { store(c).all().count { !it.optBoolean("read") } }.getOrDefault(0)

    /** Records a notification; returns true if it should be held back for the next digest. */
    fun record(c: Context, channel: String, title: String, body: String): Boolean {
        val held = !bypass && digestOn(c) && channel == Notifications.CHANNEL_NUDGE && !URGENT.containsMatchIn("$title $body")
        runCatching {
            val s = store(c)
            s.add(JSONObject().put("t", System.currentTimeMillis()).put("title", title).put("body", body).put("read", false).put("held", held))
            s.all().let { l -> l.sortedBy { it.optLong("t") }.take(maxOf(0, l.size - 300)).forEach { s.remove(it.optString("id")) } }
        }
        return held
    }

    /** Sends the held items as one summary at the digest hours. */
    fun digestTick(c: Context, hour: Double) {
        if (!digestOn(c)) return
        val slot = listOf(8.0, 14.0, 20.0).lastOrNull { hour >= it } ?: return
        val key = "${java.time.LocalDate.now()}-${slot.toInt()}"
        if (p(c).getString("lastSlot", "") == key) return
        val held = runCatching { store(c).all().filter { it.optBoolean("held") } }.getOrDefault(emptyList())
        p(c).edit().putString("lastSlot", key).apply()
        if (held.isEmpty()) return
        val s = store(c); held.forEach { h -> s.update(h.optString("id")) { it.put("held", false) } }
        bypass = true
        try { Notifications.post(c, Notifications.CHANNEL_NUDGE, 7600, "${held.size} updates from Atlas", held.take(5).joinToString("\n") { "• ${it.optString("title")}" }) } finally { bypass = false }
    }
}

@Composable
fun InboxScreen(open: (String) -> Unit = {}) {
    val c = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var digest by remember { mutableStateOf(Inbox.digestOn(c)) }
    val list = remember(tick) { Inbox.all(c) }
    val fmt = DateTimeFormatter.ofPattern("d MMM, h:mm a")
    val pending = com.nizam.app.feature.chief.ChiefStore.pendingSummary(c)
    val chiefPhase = com.nizam.app.feature.chief.ChiefStore.phase(c)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        Text("Notifications", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 2.dp))
        Text("Everything Atlas told you, gathered here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(12.dp))

        // things that need an OK from you
        if (pending != null) com.nizam.app.ui.design.Group(Modifier.padding(bottom = 10.dp)) {
            Text("Needs your OK", fontWeight = FontWeight.SemiBold)
            Text(pending.take(280), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { open("nx/chief") }) { Text("Changes") }
                TextButton(onClick = { com.nizam.app.feature.chief.ChiefRunner.approve(c, pending); tick++ }) { Text("Approve") }
                TextButton(onClick = { com.nizam.app.feature.chief.ChiefStore.clearPending(c); tick++ }) { Text("Discard") }
            }
        }
        if (chiefPhase == "ready") com.nizam.app.ui.design.Group(Modifier.padding(bottom = 10.dp)) {
            Text("An update is ready", fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { open("nx/chief") }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) { Text("Open Chief to install it") }
        }
        if (chiefPhase == "building") Text("Chief is building an update…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))

        // digest setting
        com.nizam.app.ui.design.Group(Modifier.padding(bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text("Daily digests", fontWeight = FontWeight.SemiBold)
                    Text("Hold routine reminders and send one summary at 8am, 2pm and 8pm. Prayer and urgent alerts still arrive at once.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(digest, { digest = it; Inbox.setDigest(c, it) })
            }
        }

        // header row: count + actions
        Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (list.isEmpty()) "No notifications" else "${list.size} notification${if (list.size == 1) "" else "s"}",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (list.isNotEmpty()) {
                TextButton(onClick = { val s = JsonStore(c, "nx_inbox"); s.all().forEach { o -> s.update(o.optString("id")) { it.put("read", true) } }; tick++ }) { Text("Mark read") }
                TextButton(onClick = { JsonStore(c, "nx_inbox").save(emptyList()); tick++ }) { Text("Clear") }
            }
        }

        if (list.isEmpty()) {
            Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🔔", style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.height(8.dp))
                Text("Nothing yet", style = MaterialTheme.typography.titleMedium)
                Text("Reminders, approvals and updates gather here.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        } else com.nizam.app.ui.design.Group(padding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
            list.forEachIndexed { i, o ->
                val unread = !o.optBoolean("read")
                Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp, end = 10.dp).size(8.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(if (unread) MaterialTheme.colorScheme.primary else androidx.compose.ui.graphics.Color.Transparent))
                    Column(Modifier.weight(1f)) {
                        Text(o.optString("title") + if (o.optBoolean("held")) "  (held for digest)" else "",
                            fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal)
                        if (o.optString("body").isNotBlank()) Text(o.optString("body"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, modifier = Modifier.padding(top = 2.dp))
                        Text(Instant.ofEpochMilli(o.optLong("t")).atZone(ZoneId.systemDefault()).format(fmt),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
                    }
                }
                if (i < list.lastIndex) HorizontalDivider(Modifier.padding(start = 32.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
