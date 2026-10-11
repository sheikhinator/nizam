package com.nizam.app.feature.future

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.net.Ai
import com.nizam.app.net.Router
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.components.Thinking
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * Your digital twin for messages. It learns how you write — your Roman Urdu, your tone with family versus
 * friends — and then either suggests replies you send with one tap from the notification, or (only while you've
 * said you're busy, and only to the people you choose) answers for you, signed so nobody is misled.
 */
object Twin {
    data class Chat(val key: String, val app: String, val pkg: String, val title: String, val lines: List<Pair<String, String>>,
                    val action: Notification.Action, val group: Boolean, val at: Long)

    private val chats = ConcurrentHashMap<String, Chat>()
    private val lastSuggest = ConcurrentHashMap<String, Long>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val APPS = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.google.android.apps.messaging",
        "com.facebook.orca", "org.thoughtcrime.securesms", "com.instagram.android", "com.imo.android.imoim")

    private fun p(c: Context) = c.getSharedPreferences("twin", 0)
    fun mode(c: Context) = p(c).getString("mode", "Off")!!                     // Off · Suggest · Busy autopilot
    fun setMode(c: Context, m: String) = p(c).edit().putString("mode", m).apply()
    fun busyUntil(c: Context) = p(c).getLong("busy_until", 0)
    fun setBusy(c: Context, until: Long, why: String) = p(c).edit().putLong("busy_until", until).putString("busy_why", why).apply()
    fun busyWhy(c: Context) = p(c).getString("busy_why", "busy").orEmpty()
    fun people(c: Context) = p(c).getString("people", "").orEmpty().split(',').map { it.trim().lowercase() }.filter { it.isNotBlank() }
    fun setPeople(c: Context, v: String) = p(c).edit().putString("people", v).apply()
    fun signed(c: Context) = p(c).getBoolean("signed", true)
    fun setSigned(c: Context, v: Boolean) = p(c).edit().putBoolean("signed", v).apply()
    fun style(c: Context) = p(c).getString("style", "").orEmpty()
    fun samples(c: Context): List<String> = runCatching { JSONArray(p(c).getString("samples", "[]")) }.getOrElse { JSONArray() }
        .let { a -> (0 until a.length()).map { a.optString(it) } }
    private fun addSamples(c: Context, more: List<String>) = p(c).edit().putString("samples",
        JSONArray((samples(c) + more).filter { it.length in 2..300 }.distinct().takeLast(400)).toString()).apply()

    fun recentChats(): List<Chat> = chats.values.sortedByDescending { it.at }

    /** Every messaging notification passes through here (from Atlas's notification listener). */
    fun onNotification(c: Context, sbn: StatusBarNotification, appName: String) {
        if (sbn.packageName !in APPS) return
        val action = sbn.notification.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true } ?: return
        val ms = runCatching { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(sbn.notification) }.getOrNull()
        val title = sbn.notification.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
            ?: sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val lines = ms?.messages?.map { (it.person?.name?.toString() ?: "Me") to it.text.toString() }
            ?: listOf(title to sbn.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
        if (lines.isEmpty() || title.isBlank()) return
        // your own messages in the history teach the twin how you write
        lines.filter { it.first == "Me" || it.first.equals("You", true) }.map { it.second }.takeIf { it.isNotEmpty() }?.let { addSamples(c, it) }
        val group = ms?.isGroupConversation == true
        val chat = Chat(sbn.key, appName, sbn.packageName, title, lines.takeLast(12), action, group, System.currentTimeMillis())
        chats[title] = chat
        if (chats.size > 40) chats.entries.minByOrNull { it.value.at }?.let { chats.remove(it.key) }
        val last = lines.last()
        if (last.first == "Me" || group) return
        val m = mode(c)
        if (m == "Off") return
        if (System.currentTimeMillis() - (lastSuggest[title] ?: 0) < 90_000) return
        lastSuggest[title] = System.currentTimeMillis()
        val busy = m == "Busy autopilot" && System.currentTimeMillis() < busyUntil(c) &&
            (people(c).isEmpty() || people(c).any { title.lowercase().contains(it) })
        scope.launch(Router.quiet.asContextElement(true)) {
            val container = com.nizam.app.feature.jarvis.Background.container(c)
            if (busy) {
                val reply = runCatching { write(c, container, chat, "I'm ${busyWhy(c)} right now. Reply briefly for me; if it's important, say I'll get back soon.") }.getOrNull() ?: return@launch
                val text = if (signed(c)) "$reply\n— Atlas (my assistant)" else reply
                if (send(c, chat, text)) com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE,
                    1100 + (title.hashCode() and 0xFF), "Atlas replied to $title", text)
            } else suggest(c, container, chat)
        }
    }

    private suspend fun write(c: Context, container: AppContainer, chat: Chat, instruction: String): String {
        val ex = samples(c).takeLast(40).joinToString("\n")
        return Ai.generate(container.settingsRepository,
            "Conversation with ${chat.title} on ${chat.app}:\n" + chat.lines.joinToString("\n") { "${it.first}: ${it.second}" } +
                "\n\n$instruction\nWrite only the message text.",
            "You write WhatsApp replies exactly as the user would — same language mix (Urdu / Roman Urdu / English), same length, " +
                "same tone and emoji habits. Never invent facts, promises, money or plans.\nTheir style: ${style(c).ifBlank { "casual, short" }}\n" +
                if (ex.isNotBlank()) "Examples of how they write:\n$ex" else "", 200).trim().trim('"')
    }

    private suspend fun suggest(c: Context, container: AppContainer, chat: Chat) {
        val raw = runCatching { Ai.generate(container.settingsRepository,
            "Conversation with ${chat.title}:\n" + chat.lines.joinToString("\n") { "${it.first}: ${it.second}" } +
                "\n\nGive 3 different short replies I might send next, in my style. JSON array of 3 strings only.",
            "You suggest replies in the user's own style: ${style(c).ifBlank { "casual, short" }}. Examples:\n${samples(c).takeLast(25).joinToString("\n")}", 250) }
            .getOrNull() ?: return
        val options = runCatching { JSONArray(Ai.cleanJson(raw)).let { a -> (0 until a.length()).map { a.optString(it) } } }.getOrNull()?.filter { it.isNotBlank() }?.take(3) ?: return
        val nm = androidx.core.app.NotificationManagerCompat.from(c)
        val id = 1200 + (chat.title.hashCode() and 0xFF)
        val b = NotificationCompat.Builder(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE)
            .setSmallIcon(android.R.drawable.ic_dialog_email).setContentTitle("Reply to ${chat.title}?").setContentText(chat.lines.last().second.take(80))
            .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT)
        options.forEachIndexed { i, o ->
            val pi = PendingIntent.getBroadcast(c, id * 10 + i, Intent(c, TwinReceiver::class.java).putExtra("chat", chat.title).putExtra("text", o).putExtra("nid", id),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            b.addAction(0, o.take(40), pi)
        }
        runCatching { nm.notify(id, b.build()) }
    }

    fun send(c: Context, chat: Chat, text: String): Boolean = runCatching {
        val inputs = chat.action.remoteInputs
        val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
        val intent = Intent(); RemoteInput.addResultsToIntent(inputs, intent, results)
        chat.action.actionIntent.send(c, 0, intent); true
    }.getOrDefault(false)

    fun sendTo(c: Context, title: String, text: String) = chats[title]?.let { send(c, it, text) } ?: false

    /** Reads a WhatsApp "Export chat" file and keeps the lines written by [me]. */
    fun importExport(c: Context, uri: Uri): Pair<List<String>, Map<String, List<String>>> {
        val text = c.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }.orEmpty()
        val by = mutableMapOf<String, MutableList<String>>()
        val line = Regex("^\\[?\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2,4},? \\d{1,2}:\\d{2}(?::\\d{2})?\\s?(?:[ap]\\.?m\\.?)?\\]?\\s?-?\\s?([^:]{1,40}): (.+)$", RegexOption.IGNORE_CASE)
        text.lineSequence().forEach { l -> line.find(l.trim())?.let { m ->
            val msg = m.groupValues[2]
            if (!msg.contains("<Media omitted>") && !msg.contains("omitted")) by.getOrPut(m.groupValues[1].trim()) { mutableListOf() } += msg } }
        return by.keys.sortedByDescending { by[it]!!.size } to by
    }
    fun learnFrom(c: Context, lines: List<String>) = addSamples(c, lines.shuffled().take(250))

    suspend fun buildStyle(c: Context, container: AppContainer): String {
        val s = samples(c)
        if (s.size < 10) error("I need at least 10 of your messages — import a chat export first")
        val profile = Ai.generate(container.settingsRepository, "Here are messages written by one person:\n" + s.takeLast(150).joinToString("\n") +
            "\n\nDescribe their texting style in 6 short bullet points: language mix, length, tone, emoji use, greetings/sign-offs, typical phrases.", maxOutputTokens = 400)
        p(c).edit().putString("style", profile).apply()
        return profile
    }
}

class TwinReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        val ok = Twin.sendTo(c, i.getStringExtra("chat").orEmpty(), i.getStringExtra("text").orEmpty())
        androidx.core.app.NotificationManagerCompat.from(c).cancel(i.getIntExtra("nid", 0))
        if (!ok) android.widget.Toast.makeText(c, "Couldn't send — open the chat to reply", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
fun TwinScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var mode by remember { mutableStateOf(Twin.mode(c)) }
    var people by remember { mutableStateOf(Twin.people(c).joinToString(", ")) }
    var signed by remember { mutableStateOf(Twin.signed(c)) }
    var style by remember { mutableStateOf(Twin.style(c)) }
    var names by remember { mutableStateOf<Pair<List<String>, Map<String, List<String>>>?>(null) }
    var busyUntil by remember { mutableStateOf(Twin.busyUntil(c)) }
    var missed by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) names = runCatching { Twin.importExport(c, uri) }.getOrNull()?.takeIf { it.first.isNotEmpty() }
            ?: run { Feedback.show("Couldn't read that chat export"); null }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Digital twin", "Replies in your own words — with you in control")
        Label("Mode")
        ChipRow(listOf("Off", "Suggest", "Busy autopilot"), mode, { mode = it; Twin.setMode(c, it) })
        Text(when (mode) {
            "Suggest" -> "New messages get three replies in your style on the notification — tap one to send. Nothing is sent without your tap."
            "Busy autopilot" -> "While you're marked busy, Atlas answers the people below for you (never groups), and tells you what it sent."
            else -> "Atlas doesn't touch your messages." }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (mode == "Busy autopilot") Group(Modifier.padding(top = 10.dp)) {
            val on = System.currentTimeMillis() < busyUntil
            Text(if (on) "Busy until ${java.time.Instant.ofEpochMilli(busyUntil).atZone(java.time.ZoneId.systemDefault()).toLocalTime().toString().take(5)}" else "Not busy",
                style = MaterialTheme.typography.titleSmall)
            ChipRow(listOf("Driving · 1h", "In a meeting · 1h", "Praying · 20m", "Sleeping · 8h", "Busy · 2h", "Stop"), null, { v ->
                if (v == "Stop") { Twin.setBusy(c, 0, "busy"); busyUntil = 0 }
                else { val mins = when { v.endsWith("20m") -> 20L; v.endsWith("8h") -> 480L; v.endsWith("2h") -> 120L; else -> 60L }
                    val until = System.currentTimeMillis() + mins * 60_000; Twin.setBusy(c, until, v.substringBefore(" ·").lowercase()); busyUntil = until }
            })
            OutlinedTextField(people, { people = it; Twin.setPeople(c, it) }, singleLine = true, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                label = { Text("Only for (names, comma-separated — empty = everyone 1-to-1)") })
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text("Sign replies “— Atlas (my assistant)”", Modifier.weight(1f)); Switch(signed, { signed = it; Twin.setSigned(c, it) })
            }
        }
        Label("Teach it your style · ${Twin.samples(c).size} of your messages learned")
        Group {
            Text("In WhatsApp: open a chat › ⋮ › More › Export chat › Without media, save it, then pick it here. Do a few chats with different people.",
                style = MaterialTheme.typography.bodySmall)
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pick.launch(arrayOf("text/plain", "*/*")) }) { Text("Import a chat") }
                Button(enabled = !working, onClick = { working = true; scope.launch {
                    runCatching { Twin.buildStyle(c, container) }.onSuccess { style = it }.onFailure { Feedback.failed("Learning", it) }; working = false } }) { Text("Learn my style") }
            }
            names?.let { (list, by) ->
                Text("Which one is you?", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                ChipRow(list.take(6), null, { me -> Twin.learnFrom(c, by[me].orEmpty()); Feedback.show("Learned ${by[me]?.size ?: 0} of your messages"); names = null })
            }
            if (working) Thinking(label = "Studying how you write")
            if (style.isNotBlank()) Text(style, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
        Label("What did I miss?")
        Group {
            Button(onClick = {
                missed = "…"
                scope.launch {
                    val chats = Twin.recentChats().take(15)
                    missed = if (chats.isEmpty()) "Nothing new in your chats since Atlas started listening." else runCatching {
                        Ai.generate(container.settingsRepository, chats.joinToString("\n\n") { ch -> "${ch.title}${if (ch.group) " (group)" else ""}:\n" +
                            ch.lines.takeLast(8).joinToString("\n") { "${it.first}: ${it.second}" } } +
                            "\n\nSummarise what I missed: who needs a reply from me first, anything urgent or about money/plans, then the rest in a line each.", maxOutputTokens = 600) }
                        .getOrElse { "Couldn't summarise: ${Feedback.friendly(it)}" }
                }
            }) { Text("Catch me up") }
            if (missed.isNotBlank()) Text(missed, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
        Text("Needs Atlas's notification access. Messages are only read from notifications on this phone.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 14.dp))
        Spacer(Modifier.height(40.dp))
    }
}
