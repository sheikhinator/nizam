package com.nizam.app.feature.notify

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
    val cs = MaterialTheme.colorScheme
    var tick by remember { mutableStateOf(0) }
    var digest by remember { mutableStateOf(Inbox.digestOn(c)) }
    val list = remember(tick) { Inbox.all(c) }
    val unread = list.count { !it.optBoolean("read") }
    val fmt = DateTimeFormatter.ofPattern("d MMM, h:mm a")
    val card = androidx.compose.foundation.shape.RoundedCornerShape(18.dp)
    fun Modifier.box() = this.fillMaxWidth().clip(card).background(cs.surfaceVariant.copy(alpha = 0.45f)).padding(14.dp)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 4.dp, bottom = 40.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Notifications", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(if (unread > 0) "$unread unread" else "You're all caught up", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            if (list.isNotEmpty()) {
                TextButton(onClick = { val s = JsonStore(c, "nx_inbox"); s.all().forEach { o -> s.update(o.optString("id")) { it.put("read", true) } }; tick++ }) { Text("Mark read") }
                TextButton(onClick = { JsonStore(c, "nx_inbox").save(emptyList()); tick++ }) { Text("Clear") }
            }
        }
        // things that need an OK from you: changes Chief has prepared, and finished updates
        val pending = com.nizam.app.feature.chief.ChiefStore.pendingSummary(c)
        val chiefPhase = com.nizam.app.feature.chief.ChiefStore.phase(c)
        if (pending != null) Column(Modifier.box()) {
            Text("Needs your OK", fontWeight = FontWeight.SemiBold)
            Text(pending.take(280), style = MaterialTheme.typography.bodySmall)
            Row {
                TextButton(onClick = { open("nx/chief") }) { Text("See changes") }
                TextButton(onClick = { com.nizam.app.feature.chief.ChiefRunner.approve(c, pending); tick++ }) { Text("Approve") }
                TextButton(onClick = { com.nizam.app.feature.chief.ChiefStore.clearPending(c); tick++ }) { Text("Discard") }
            }
        }
        if (chiefPhase == "ready") Column(Modifier.box()) {
            Text("An update is ready", fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { open("nx/chief") }) { Text("Open Chief to install it") }
        }
        if (chiefPhase == "building") Text("Chief is building an update…", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.box(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text("Daily digests", fontWeight = FontWeight.SemiBold)
                Text("Hold routine reminders and send one summary at 8am, 2pm and 8pm. Prayer and urgent alerts still arrive at once.",
                    style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Switch(digest, { digest = it; Inbox.setDigest(c, it) })
        }
        if (list.isEmpty()) Column(Modifier.fillMaxWidth().padding(vertical = 36.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔔", style = MaterialTheme.typography.displaySmall)
            Text("Nothing yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp))
            Text("Reminders, approvals and updates will gather here.", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        list.forEach { o ->
            val read = o.optBoolean("read")
            Row(Modifier.box(), verticalAlignment = Alignment.Top) {
                androidx.compose.foundation.layout.Box(Modifier.padding(top = 6.dp, end = 10.dp).size(8.dp).clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (read) androidx.compose.ui.graphics.Color.Transparent else cs.primary))
                Column(Modifier.weight(1f)) {
                    Text(o.optString("title") + if (o.optBoolean("held")) "  · held for digest" else "", fontWeight = if (read) FontWeight.Normal else FontWeight.SemiBold)
                    if (o.optString("body").isNotBlank()) Text(o.optString("body"), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant, maxLines = 3)
                    Text(Instant.ofEpochMilli(o.optLong("t")).atZone(ZoneId.systemDefault()).format(fmt), style = MaterialTheme.typography.labelSmall,
                        color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
