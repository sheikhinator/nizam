package com.nizam.app.feature.apps2

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.future.Sealed
import com.nizam.app.feature.nx.Md
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.net.Ai
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Capsule
import com.nizam.app.ui.design.EmptySpec
import com.nizam.app.ui.design.EmptyState
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.ListRow
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.Space
import com.nizam.app.ui.design.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Properties

object MailApp {
    val all: List<Spec> = listOf(ScreenSpec("mail", "Mail", "Your email with AI summaries and replies (Gmail, Outlook, Yahoo)", Icons.Outlined.Mail, Hub.Work,
        "email gmail outlook inbox mail reply") { c, _ -> MailScreen(c) })

    data class Account(val email: String, val pass: String, val imap: String, val smtp: String)
    data class Msg(val n: Int, val from: String, val subject: String, val date: String, val seen: Boolean)
    val PRESETS = linkedMapOf("Gmail" to ("imap.gmail.com" to "smtp.gmail.com"), "Outlook" to ("outlook.office365.com" to "smtp.office365.com"), "Yahoo" to ("imap.mail.yahoo.com" to "smtp.mail.yahoo.com"))

    private fun p(c: Context) = c.getSharedPreferences("mail_app", 0)
    fun account(c: Context): Account? { val e = p(c).getString("email", null) ?: return null
        return Account(e, Sealed.openText(p(c).getString("pass", "").orEmpty()), p(c).getString("imap", "")!!, p(c).getString("smtp", "")!!) }
    fun save(c: Context, a: Account) = p(c).edit().putString("email", a.email).putString("pass", Sealed.sealText(a.pass)).putString("imap", a.imap).putString("smtp", a.smtp).apply()
    fun forget(c: Context) = p(c).edit().clear().apply()

    private fun store(a: Account): javax.mail.Store {
        val props = Properties().apply { put("mail.store.protocol", "imaps"); put("mail.imaps.timeout", "20000"); put("mail.imaps.connectiontimeout", "20000") }
        return javax.mail.Session.getInstance(props).getStore("imaps").also { it.connect(a.imap, 993, a.email, a.pass) }
    }

    suspend fun inbox(a: Account, count: Int = 40): List<Msg> = withContext(Dispatchers.IO) {
        val s = store(a)
        try {
            val f = s.getFolder("INBOX"); f.open(javax.mail.Folder.READ_ONLY)
            val total = f.messageCount; val from = (total - count + 1).coerceAtLeast(1)
            val ms: Array<javax.mail.Message> = if (total == 0) emptyArray() else f.getMessages(from, total)
            f.fetch(ms, javax.mail.FetchProfile().apply { add(javax.mail.FetchProfile.Item.ENVELOPE); add(javax.mail.FetchProfile.Item.FLAGS) })
            ms.reversed().map { m -> Msg(m.messageNumber, (m.from?.firstOrNull() as? javax.mail.internet.InternetAddress)?.let { it.personal ?: it.address } ?: "?",
                m.subject ?: "(no subject)", m.sentDate?.let { java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(it) } ?: "",
                m.isSet(javax.mail.Flags.Flag.SEEN)) }.also { f.close(false) }
        } finally { runCatching { s.close() } }
    }

    private fun text(p: javax.mail.Part): String = when {
        p.isMimeType("text/plain") -> p.content as? String ?: ""
        p.isMimeType("text/html") -> (p.content as? String ?: "").replace(Regex("(?is)<(style|script)[^>]*>.*?</\\1>"), "").replace(Regex("<br\\s*/?>|</p>"), "\n").replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").replace("&amp;", "&")
        p.isMimeType("multipart/alternative") -> (p.content as javax.mail.Multipart).let { mp -> (0 until mp.count).map { mp.getBodyPart(it) }.let { parts ->
            parts.firstOrNull { it.isMimeType("text/plain") }?.let { text(it) } ?: parts.firstOrNull()?.let { text(it) } ?: "" } }
        p.isMimeType("multipart/*") -> (p.content as javax.mail.Multipart).let { mp -> (0 until mp.count).joinToString("\n") { text(mp.getBodyPart(it)) } }
        else -> ""
    }

    suspend fun read(a: Account, n: Int): Pair<String, String> = withContext(Dispatchers.IO) {
        val s = store(a)
        try { val f = s.getFolder("INBOX"); f.open(javax.mail.Folder.READ_WRITE); val m = f.getMessage(n)
            val from = (m.from?.firstOrNull() as? javax.mail.internet.InternetAddress)?.address.orEmpty()
            val body = text(m).replace(Regex("\n{3,}"), "\n\n").trim(); m.setFlag(javax.mail.Flags.Flag.SEEN, true); f.close(false); from to body
        } finally { runCatching { s.close() } }
    }

    suspend fun send(a: Account, to: String, subject: String, body: String) = withContext(Dispatchers.IO) {
        val props = Properties().apply { put("mail.smtp.auth", "true"); put("mail.smtp.starttls.enable", "true"); put("mail.smtp.host", a.smtp); put("mail.smtp.port", "587")
            put("mail.smtp.timeout", "20000"); put("mail.smtp.connectiontimeout", "20000") }
        val session = javax.mail.Session.getInstance(props, object : javax.mail.Authenticator() {
            override fun getPasswordAuthentication() = javax.mail.PasswordAuthentication(a.email, a.pass) })
        val m = javax.mail.internet.MimeMessage(session).apply {
            setFrom(javax.mail.internet.InternetAddress(a.email)); setRecipients(javax.mail.Message.RecipientType.TO, javax.mail.internet.InternetAddress.parse(to))
            setSubject(subject, "UTF-8"); setText(body, "UTF-8") }
        javax.mail.Transport.send(m)
    }
}

@Composable
fun MailScreen(container: AppContainer) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var acct by remember { mutableStateOf(MailApp.account(ctx)) }
    var list by remember { mutableStateOf<List<MailApp.Msg>?>(null) }
    var err by remember { mutableStateOf<String?>(null) }
    var open by remember { mutableStateOf<MailApp.Msg?>(null) }
    var body by remember { mutableStateOf<Pair<String, String>?>(null) }
    var ai by remember { mutableStateOf<String?>(null) }
    var compose by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val a = acct
    if (a == null) {
        var email by remember { mutableStateOf("") }; var pass by remember { mutableStateOf("") }; var preset by remember { mutableStateOf("Gmail") }
        AtlasScreen(title = "Mail", subtitle = "Sign in with an app password", hub = Hub.Work) {
            item("f") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) { MailApp.PRESETS.keys.forEach { k -> Capsule(k, preset == k) { preset = k } } }
                OutlinedTextField(email, { email = it.trim() }, Modifier.fillMaxWidth().padding(top = Space.s), label = { Text("Email") }, singleLine = true)
                OutlinedTextField(pass, { pass = it.trim() }, Modifier.fillMaxWidth().padding(top = Space.s), label = { Text("App password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                Text("Gmail: Google Account › Security › 2-Step Verification › App passwords. Your normal password won't work. Stored encrypted on this phone only.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = Space.s))
                Button(onClick = {
                    val (im, sm) = MailApp.PRESETS[preset]!!; val acc = MailApp.Account(email, pass, im, sm)
                    scope.launch { runCatching { MailApp.inbox(acc, 1) }.onSuccess { MailApp.save(ctx, acc); acct = acc }.onFailure { Feedback.show("Sign-in failed: ${it.message?.take(100)}") } }
                }, enabled = email.contains('@') && pass.isNotBlank(), modifier = Modifier.padding(top = Space.s)) { Text("Sign in") }
            } }
        }
        return
    }
    LaunchedEffect(a, reload) { err = null; runCatching { MailApp.inbox(a) }.onSuccess { list = it }.onFailure { err = it.message } }
    val c = compose
    if (c != null) {
        var to by remember(c) { mutableStateOf(c.first) }; var subj by remember(c) { mutableStateOf(c.second) }; var text by remember(c) { mutableStateOf(c.third) }
        var sending by remember { mutableStateOf(false) }
        AtlasScreen(title = "Write", subtitle = a.email, hub = Hub.Work, actions = { Text("Cancel", Modifier.padding(Space.s).pressable { compose = null }, style = MaterialTheme.typography.labelLarge) }) {
            item("f") { Column(Modifier.padding(horizontal = Space.page)) {
                OutlinedTextField(to, { to = it }, Modifier.fillMaxWidth(), label = { Text("To") }, singleLine = true)
                OutlinedTextField(subj, { subj = it }, Modifier.fillMaxWidth().padding(top = Space.s), label = { Text("Subject") }, singleLine = true)
                OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth().heightIn(min = 200.dp).padding(top = Space.s), label = { Text("Message") })
                Row(Modifier.padding(top = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Capsule("✨ Polish", false) { scope.launch { text = runCatching { Ai.generate(container.settingsRepository, "Polish this email: clear, polite, same meaning and language. Only the email body:\n$text", maxOutputTokens = 800) }.getOrDefault(text) } }
                    Capsule("Formal", false) { scope.launch { text = runCatching { Ai.generate(container.settingsRepository, "Rewrite this email formally. Only the body:\n$text", maxOutputTokens = 800) }.getOrDefault(text) } }
                }
                Button(onClick = { sending = true; scope.launch { runCatching { MailApp.send(a, to, subj, text) }.onSuccess { Feedback.show("Sent"); compose = null }.onFailure { Feedback.show("Couldn't send: ${it.message?.take(100)}") }; sending = false } },
                    enabled = to.contains('@') && !sending, modifier = Modifier.padding(top = Space.s)) { Text(if (sending) "Sending…" else "Send") }
            } }
        }
        return
    }
    val o = open
    if (o != null) {
        LaunchedEffect(o) { body = null; ai = null; body = runCatching { MailApp.read(a, o.n) }.getOrElse { "" to "Couldn't open: ${it.message}" } }
        AtlasScreen(title = o.subject, subtitle = "${o.from} · ${o.date}", hub = Hub.Work, actions = { Text("Back", Modifier.padding(Space.s).pressable { open = null; reload++ }, style = MaterialTheme.typography.labelLarge) }) {
            item("ai") { Row(Modifier.padding(horizontal = Space.page), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Capsule("Summarise", false) { scope.launch { ai = runCatching { Ai.generate(container.settingsRepository, "Summarise this email in 3 bullets and list anything I must do or reply to, with dates:\n${body?.second?.take(8000)}", maxOutputTokens = 400) }.getOrElse { it.message } } }
                Capsule("Draft reply", false) { scope.launch {
                    val r = runCatching { Ai.generate(container.settingsRepository, "Draft a short, friendly, professional reply to this email from me. Only the body:\n${body?.second?.take(6000)}", maxOutputTokens = 500) }.getOrDefault("")
                    compose = Triple(body?.first.orEmpty(), "Re: ${o.subject}", r) } }
                Capsule("Reply", false) { compose = Triple(body?.first.orEmpty(), "Re: ${o.subject}", "") }
            } }
            ai?.let { s -> item("s") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) { Md(s) } } }
            item("b") { Text(body?.second ?: "Loading…", Modifier.padding(Space.page), style = MaterialTheme.typography.bodyLarge) }
        }
        return
    }
    AtlasScreen(title = "Inbox", subtitle = a.email, hub = Hub.Work, loading = list == null && err == null, actions = {
        Row { Text("Write", Modifier.padding(Space.s).pressable { compose = Triple("", "", "") }, style = MaterialTheme.typography.labelLarge, color = Hub.Work.tint())
            Text("Sign out", Modifier.padding(Space.s).pressable { MailApp.forget(ctx); acct = null; list = null }, style = MaterialTheme.typography.labelLarge) } }) {
        err?.let { e -> item("err") { Text("Couldn't load mail: ${e.take(150)}", Modifier.padding(Space.page), color = MaterialTheme.colorScheme.error) } }
        item("digest") { Row(Modifier.padding(horizontal = Space.page)) { Capsule("✨ What needs me?", false) {
            scope.launch { ai = runCatching { Ai.generate(container.settingsRepository, "From these email headers, list which likely need my action or reply, and which are promotions I can ignore:\n" +
                list.orEmpty().take(40).joinToString("\n") { "${if (it.seen) "" else "[unread] "}${it.from}: ${it.subject}" }, maxOutputTokens = 500) }.getOrElse { it.message } }
        } } }
        ai?.let { s -> item("dig") { Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) { Md(s) } } }
        if (list?.isEmpty() == true) item("e") { EmptyState(EmptySpec(Icons.Outlined.Mail, "Inbox empty", "Nothing here.")) }
        list.orEmpty().forEach { m -> item("m${m.n}") {
            ListRow(m.subject, supporting = "${m.from} · ${m.date}", icon = if (m.seen) Icons.Outlined.Drafts else Icons.Outlined.Mail, tint = if (m.seen) MaterialTheme.colorScheme.onSurfaceVariant else Hub.Work.tint()) { open = m }
        } }
    }
}
