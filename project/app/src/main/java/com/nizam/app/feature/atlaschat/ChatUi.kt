package com.nizam.app.feature.atlaschat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.nizam.app.feature.nx.Share
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue

private val EMOJI = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
private val TIMERS = listOf("Off" to 0, "1 hour" to 3600, "1 day" to 86400, "1 week" to 604800)

private fun hue(name: String): Float = (name.hashCode().absoluteValue % 360).toFloat()

@Composable
private fun Avatar(name: String, size: Int = 46, verified: Boolean = false) {
    val h = hue(name)
    val a = Color.hsv(h, 0.55f, 0.85f); val b = Color.hsv((h + 40f) % 360f, 0.65f, 0.65f)
    Box(Modifier.size(size.dp).clip(CircleShape).background(Brush.linearGradient(listOf(a, b))), contentAlignment = Alignment.Center) {
        Text(name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?", color = Color.White, fontWeight = FontWeight.Bold, fontSize = (size * 0.42f).sp)
    }
}

private fun timeText(ts: Long): String {
    val now = Calendar.getInstance(); val t = Calendar.getInstance().apply { timeInMillis = ts }
    return when {
        now.get(Calendar.YEAR) == t.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == t.get(Calendar.DAY_OF_YEAR) -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ts))
        now.get(Calendar.YEAR) == t.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) - t.get(Calendar.DAY_OF_YEAR) == 1 -> "Yesterday"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ts))
    }
}

private fun dayText(ts: Long): String = SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date(ts))
private fun sameDay(a: Long, b: Long): Boolean { val x = Calendar.getInstance().apply { timeInMillis = a }; val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR) }

private fun qr(text: String): androidx.compose.ui.graphics.ImageBitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 512, 512)
    val bmp = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ARGB_8888)
    for (x in 0 until m.width) for (y in 0 until m.height) bmp.setPixel(x, y, if (m.get(x, y)) AColor.BLACK else AColor.WHITE)
    return bmp.asImageBitmap()
}

/** Atlas Messenger: private chats between Atlas users. No phone number, no company server. */
@Composable
fun AtlasMessengerScreen() {
    val ctx = LocalContext.current
    val engine = remember { AtlasChat.get(ctx) }
    val rev by engine.rev.collectAsState()
    var page by rememberSaveable { mutableStateOf("home") }
    var chatPub by rememberSaveable { mutableStateOf("") }
    DisposableEffect(Unit) { AtlasChat.appInForeground = true; onDispose { AtlasChat.appInForeground = false; engine.openChat = null } }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(engine.registered) { com.nizam.app.ui.design.guard {
        if (engine.registered) { AtlasChat.schedulePolling(ctx); if (Build.VERSION.SDK_INT >= 33) notifPerm.launch(android.Manifest.permission.POST_NOTIFICATIONS) }
    } }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { Box(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
        when {
            !engine.registered -> Onboarding(engine)
            page == "chat" && engine.contacts[chatPub] != null -> { BackHandler { page = "home"; engine.openChat = null }; ChatScreen(engine, chatPub, rev, { page = "home"; engine.openChat = null }, { page = "info" }) }
            page == "info" && engine.contacts[chatPub] != null -> { BackHandler { page = "chat" }; ContactInfo(engine, chatPub, rev, { page = "chat" }, { engine.removeContact(chatPub); page = "home" }) }
            page == "new" -> { BackHandler { page = "home" }; NewChat(engine, { page = "home" }) { pub -> chatPub = pub; page = "chat"; engine.openChat = pub } }
            page == "me" -> { BackHandler { page = "home" }; MyId(engine) { page = "home" } }
            page == "requests" -> { BackHandler { page = "home" }; Requests(engine, rev, { page = "home" }) { pub -> chatPub = pub; page = "chat"; engine.openChat = pub } }
            page == "settings" -> { BackHandler { page = "home" }; SettingsPage(engine, rev) { page = "home" } }
            else -> Home(engine, rev, { page = it }) { pub -> chatPub = pub; page = "chat"; engine.openChat = pub }
        }
      } }
    }
}

// ───────────────────────── first run ─────────────────────────

@Composable
private fun Onboarding(engine: ChatEngine) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var name by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val rev by engine.rev.collectAsState()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        Box(Modifier.size(96.dp).clip(CircleShape).background(Brush.linearGradient(listOf(Color(0xFF6C7FE0), Color(0xFFB36AD8)))), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Lock, null, tint = Color.White, modifier = Modifier.size(44.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("Atlas Messenger", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(6.dp))
        Text("Private chats with other Atlas users.\nNo phone number. No company server.", textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        listOf(Icons.Filled.Lock to "End-to-end encrypted with the same kind of ratchet Signal uses",
            Icons.Filled.Shield to "Your keys never leave this phone",
            Icons.Filled.Timer to "Disappearing messages, read receipts, reactions, replies").forEach { (ic, t) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(ic, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp)); Text(t, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(name, { name = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' }.take(20); err = "" }, label = { Text("Pick a username") }, prefix = { Text("@") },
            singleLine = true, isError = err.isNotEmpty(), supportingText = { Text(err.ifEmpty { "Friends find you with this. 3–20 letters, numbers or _" }) },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        Button(onClick = { busy = true; scope.launch { err = engine.register(name).orEmpty(); busy = false } }, enabled = name.length >= 3 && !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) else Text("Create my Atlas ID")
        }
        Spacer(Modifier.height(10.dp))
        Text(if (engine.relays.isEmpty()) "" else "Network: ${relayStatus(engine, rev)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun relayStatus(engine: ChatEngine, @Suppress("UNUSED_PARAMETER") rev: Int): String = "${engine.relays.size} relays configured"

// ───────────────────────── chat list ─────────────────────────

@Composable
private fun Home(engine: ChatEngine, rev: Int, go: (String) -> Unit, openChat: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    val list = remember(rev, q) {
        engine.contacts.values.filter { q.isBlank() || it.name.contains(q, true) }
            .sortedWith(compareByDescending<Contact> { it.pinned }.thenByDescending { it.lastTs })
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        floatingActionButton = { ExtendedFloatingActionButton(onClick = { go("new") }, icon = { Icon(Icons.Filled.Edit, null) }, text = { Text("New chat") }) },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Messenger", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("@${engine.myName}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { go("me") }) { Icon(Icons.Filled.QrCode, "My Atlas ID") }
                IconButton(onClick = { go("settings") }) { Icon(Icons.Filled.Settings, "Settings") }
            }
            OutlinedTextField(q, { q = it }, placeholder = { Text("Search chats") }, leadingIcon = { Icon(Icons.Filled.Search, null) }, singleLine = true,
                shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp))
            if (engine.requests.isNotEmpty()) {
                Card(onClick = { go("requests") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.PersonAdd, null); Spacer(Modifier.width(12.dp))
                        Text("${engine.requests.size} message request${if (engine.requests.size == 1) "" else "s"}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Filled.ChevronRight, null)
                    }
                }
            }
            if (list.isEmpty()) {
                Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(Icons.Filled.Forum, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(12.dp))
                    Text(if (q.isBlank()) "No chats yet" else "Nothing found", style = MaterialTheme.typography.titleMedium)
                    if (q.isBlank()) Text("Tap New chat and enter a friend's username, or share your Atlas ID with them.", textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 96.dp)) {
                items(list, key = { it.pub }) { c -> ChatRow(engine, c, rev) { openChat(c.pub) } }
            }
        }
    }
}

@Composable
private fun ChatRow(engine: ChatEngine, c: Contact, rev: Int, onClick: () -> Unit) {
    val last = remember(rev) { engine.msgs(c.pub).lastOrNull() }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Avatar(c.name)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(c.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (c.verified) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.Verified, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary) }
                if (c.muted) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.NotificationsOff, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline) }
                if (c.pinned) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.PushPin, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.outline) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (last != null && last.mine && !last.deleted && !last.system) { Ticks(last.status, Modifier.padding(end = 4.dp)) }
                Text(when { last == null -> "Say hello"; last.deleted -> "Message deleted"; else -> last.text }, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            if (last != null) Text(timeText(last.ts), style = MaterialTheme.typography.labelSmall, color = if (c.unread > 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            if (c.unread > 0) Box(Modifier.padding(top = 4.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).defaultMinSize(minWidth = 20.dp, minHeight = 20.dp).padding(horizontal = 6.dp), contentAlignment = Alignment.Center) {
                Text("${c.unread}", color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun Ticks(status: Int, modifier: Modifier = Modifier) {
    val (icon, tint) = when (status) {
        0 -> Icons.Filled.Schedule to MaterialTheme.colorScheme.outline
        1 -> Icons.Filled.Done to MaterialTheme.colorScheme.outline
        2 -> Icons.Filled.DoneAll to MaterialTheme.colorScheme.outline
        else -> Icons.Filled.DoneAll to Color(0xFF34B7F1)
    }
    Icon(icon, null, modifier.size(15.dp), tint = tint)
}

// ───────────────────────── a conversation ─────────────────────────

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(engine: ChatEngine, pub: String, rev: Int, back: () -> Unit, info: () -> Unit) {
    val c = engine.contacts[pub] ?: return
    val msgs = remember(rev) { engine.msgs(pub) }
    val list = rememberLazyListState()
    var text by remember(pub) { mutableStateOf("") }
    var replyTo by remember(pub) { mutableStateOf<Msg?>(null) }
    var editing by remember(pub) { mutableStateOf<Msg?>(null) }
    var menuFor by remember { mutableStateOf<Msg?>(null) }
    val clip = LocalClipboardManager.current
    LaunchedEffect(pub, msgs.size) { com.nizam.app.ui.design.guard { engine.markRead(pub); if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) } }
    val byId = remember(msgs) { msgs.associateBy { it.id } }
    val me = engine.myPub.orEmpty()

    Column(Modifier.fillMaxSize().imePadding()) {
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                Row(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable(onClick = info).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(c.name, 40)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(c.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (c.verified) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.Verified, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary) }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Lock, null, Modifier.size(11.dp), tint = MaterialTheme.colorScheme.outline)
                            Spacer(Modifier.width(3.dp))
                            Text(if (c.disappear > 0) "End-to-end encrypted · disappears in ${engine.describe(c.disappear)}" else "End-to-end encrypted", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                IconButton(onClick = info) { Icon(Icons.Filled.MoreVert, "Chat info") }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (msgs.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Filled.Lock, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(8.dp))
                    Text("Messages are end-to-end encrypted.\nNo one else can read them, not even the relays that carry them.", textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            items(msgs.size, key = { msgs[it].id }) { i ->
                val m = msgs[i]
                if (i == 0 || !sameDay(msgs[i - 1].ts, m.ts)) Text(dayText(m.ts), Modifier.fillMaxWidth().padding(vertical = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (m.system) Text(m.text, Modifier.fillMaxWidth().padding(vertical = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else Bubble(m, byId[m.reply], me, c.name, onLong = { menuFor = m })
            }
        }
        AnimatedVisibility(replyTo != null || editing != null) {
            val r = replyTo; val e = editing
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(3.dp).height(34.dp).background(MaterialTheme.colorScheme.primary))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (e != null) "Edit message" else if (r?.mine == true) "You" else c.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Text((e ?: r)?.text.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { replyTo = null; editing = null; if (e != null) text = "" }) { Icon(Icons.Filled.Close, "Cancel") }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(text, { text = it }, placeholder = { Text("Message") }, shape = RoundedCornerShape(26.dp), maxLines = 5, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            FilledIconButton(onClick = {
                val e = editing
                if (e != null) { engine.edit(pub, e.id, text); editing = null } else { engine.send(pub, text, replyTo?.id); replyTo = null }
                text = ""
            }, enabled = text.isNotBlank(), modifier = Modifier.size(52.dp)) { Icon(if (editing != null) Icons.Filled.Check else Icons.AutoMirrored.Filled.Send, "Send") }
        }
    }

    menuFor?.let { m ->
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                if (!m.deleted) Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    EMOJI.forEach { e -> Text(e, fontSize = 28.sp, modifier = Modifier.clip(CircleShape).clickable { engine.react(pub, m.id, e); menuFor = null }.padding(8.dp)) }
                }
                if (!m.deleted) {
                    SheetItem(Icons.AutoMirrored.Filled.Reply, "Reply") { replyTo = m; editing = null; menuFor = null }
                    SheetItem(Icons.Filled.ContentCopy, "Copy") { clip.setText(AnnotatedString(m.text)); menuFor = null }
                    if (m.mine) SheetItem(Icons.Filled.Edit, "Edit") { editing = m; replyTo = null; text = m.text; menuFor = null }
                }
                SheetItem(Icons.Filled.Delete, "Delete for me") { engine.delete(pub, m.id, false); menuFor = null }
                if (m.mine && !m.deleted) SheetItem(Icons.Filled.DeleteForever, "Delete for everyone") { engine.delete(pub, m.id, true); menuFor = null }
            }
        }
    }
}

@Composable
private fun SheetItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null); Spacer(Modifier.width(18.dp)); Text(label)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bubble(m: Msg, quoted: Msg?, me: String, otherName: String, onLong: () -> Unit) {
    val mine = m.mine
    val bg = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (mine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    val shape = RoundedCornerShape(18.dp, 18.dp, if (mine) 4.dp else 18.dp, if (mine) 18.dp else 4.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(Modifier.widthIn(max = 300.dp).clip(shape).background(bg).combinedClickable(onClick = {}, onLongClick = onLong).padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (quoted != null) Row(Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(8.dp)).background(fg.copy(alpha = 0.08f)).height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                Column(Modifier.padding(8.dp)) {
                    Text(if (quoted.mine) "You" else otherName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Text(if (quoted.deleted) "Message deleted" else quoted.text, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = fg)
                }
            }
            if (m.deleted) Text("🚫 This message was deleted", color = fg.copy(alpha = 0.6f), fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
            else Text(m.text, color = fg, fontSize = 16.sp)
            Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (m.expiresAt > 0) { Icon(Icons.Filled.Timer, null, Modifier.size(11.dp), tint = fg.copy(alpha = 0.55f)); Spacer(Modifier.width(3.dp)) }
                if (m.edited && !m.deleted) Text("edited  ", fontSize = 10.sp, color = fg.copy(alpha = 0.55f))
                Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.ts)), fontSize = 10.sp, color = fg.copy(alpha = 0.55f))
                if (mine && !m.deleted) { Spacer(Modifier.width(4.dp)); Ticks(m.status) }
            }
        }
        if (m.reactions.isNotEmpty()) {
            val grouped = m.reactions.values.groupingBy { it }.eachCount()
            Row(Modifier.offset(y = (-6).dp).padding(horizontal = 8.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 6.dp, vertical = 1.dp)) {
                grouped.forEach { (e, n) -> Text(if (n > 1) "$e$n " else "$e ", fontSize = 13.sp) }
            }
        }
    }
}

// ───────────────────────── add people ─────────────────────────

@Composable
private fun TopBar(title: String, back: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        actions()
    }
}

@Composable
private fun NewChat(engine: ChatEngine, back: () -> Unit, open: (String) -> Unit) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val clip = LocalClipboardManager.current
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<Found?>(null) }
    var msg by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        TopBar("New chat", back)
        Column(Modifier.padding(20.dp)) {
            Text("Enter a username or paste an Atlas ID", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(q, { q = it; found = null; msg = "" }, label = { Text("@username or atlas:…") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                trailingIcon = { IconButton(onClick = { clip.getText()?.text?.let { q = it.trim(); found = null; msg = "" } }) { Icon(Icons.Filled.ContentPaste, "Paste") } })
            Spacer(Modifier.height(12.dp))
            Button(onClick = {
                busy = true; msg = ""; found = null
                scope.launch {
                    val f = engine.find(q)
                    if (f == null) msg = "Nobody found. Check the spelling, or try again in a moment: the relays may still be connecting."
                    else if (f.pub == engine.myPub) msg = "That's you."
                    else found = f
                    busy = false
                }
            }, enabled = q.length >= 3 && !busy, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary) else Text("Find")
            }
            if (msg.isNotEmpty()) Text(msg, Modifier.padding(top = 14.dp), color = MaterialTheme.colorScheme.error)
            found?.let { f ->
                Card(Modifier.fillMaxWidth().padding(top = 18.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(f.name.ifEmpty { f.pub }, 52)
                            Spacer(Modifier.width(14.dp))
                            Column {
                                Text("@${f.name}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("ID ${f.pub.take(8)}…${f.pub.takeLast(6)}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        Text("Usernames are first come, first served. If this matters, check the ID with them, or compare the safety number once you are chatting.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { val c = engine.addContact(f); open(c.pub) }, modifier = Modifier.fillMaxWidth()) { Text("Start chat") }
                    }
                }
            }
        }
    }
}

@Composable
private fun Requests(engine: ChatEngine, rev: Int, back: () -> Unit, open: (String) -> Unit) {
    val list = remember(rev) { engine.requests.toList() }
    Column(Modifier.fillMaxSize()) {
        TopBar("Message requests", back)
        if (list.isEmpty()) Text("No requests", Modifier.padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            items(list, key = { it.contact.pub }) { r ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Avatar(r.contact.name, 44); Spacer(Modifier.width(12.dp))
                            Column { Text(r.contact.name, fontWeight = FontWeight.Bold); Text("ID ${r.contact.pub.take(8)}…", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace) }
                        }
                        r.msgs.lastOrNull()?.let { Text("“${it.text}”", Modifier.padding(top = 10.dp), maxLines = 3, overflow = TextOverflow.Ellipsis) }
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            OutlinedButton(onClick = { engine.declineRequest(r) }, modifier = Modifier.weight(1f)) { Text("Delete") }
                            Button(onClick = { engine.acceptRequest(r); open(r.contact.pub) }, modifier = Modifier.weight(1f)) { Text("Accept") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MyId(engine: ChatEngine, back: () -> Unit) {
    val ctx = LocalContext.current
    val clip = LocalClipboardManager.current
    val id = engine.atlasId()
    val bmp = remember(id) { qr(id) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
        TopBar("My Atlas ID", back)
        Spacer(Modifier.height(12.dp))
        Avatar(engine.myName, 72)
        Spacer(Modifier.height(10.dp))
        Text("@${engine.myName}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(18.dp))
        Box(Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White).padding(14.dp)) { Image(bmp, "Your Atlas ID as a QR code", Modifier.size(230.dp)) }
        Spacer(Modifier.height(14.dp))
        Text(id.take(14) + "…" + id.takeLast(8), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 24.dp)) {
            OutlinedButton(onClick = { clip.setText(AnnotatedString(id)) }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.ContentCopy, null); Spacer(Modifier.width(8.dp)); Text("Copy") }
            Button(onClick = { Share.text(ctx, "Chat with me on Atlas Messenger: @${engine.myName}\n$id", "Atlas Messenger") }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Share, null); Spacer(Modifier.width(8.dp)); Text("Share") }
        }
        Text("Anyone with your ID can message you, but only you can read what is sent to you. You can block anyone at any time.", Modifier.padding(24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

// ───────────────────────── chat info & settings ─────────────────────────

@Composable
private fun ContactInfo(engine: ChatEngine, pub: String, rev: Int, back: () -> Unit, removed: () -> Unit) {
    val c = engine.contacts[pub] ?: return
    var confirm by remember { mutableStateOf(false) }
    val safety = remember(rev, pub) { engine.safety(pub).orEmpty() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar(c.name, back)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Avatar(c.name, 84); Spacer(Modifier.height(10.dp))
            Text(c.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("ID ${c.pub.take(10)}…${c.pub.takeLast(6)}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(16.dp))
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Shield, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Text("Safety number", fontWeight = FontWeight.SemiBold) }
                Text("Compare this with ${c.name} in person or on a call. If it matches on both phones, nobody is in the middle.", Modifier.padding(vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(safety, fontFamily = FontFamily.Monospace, fontSize = 17.sp, lineHeight = 26.sp, fontWeight = FontWeight.Medium)
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Mark as verified", Modifier.weight(1f)); Switch(c.verified, { engine.setVerified(pub, it) })
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Filled.Timer, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Text("Disappearing messages", fontWeight = FontWeight.SemiBold) }
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TIMERS.forEach { (label, sec) -> FilterChip(c.disappear == sec, { engine.setDisappear(pub, sec) }, label = { Text(label) }) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column {
                SwitchRow(Icons.Filled.NotificationsOff, "Mute notifications", c.muted) { engine.setMuted(pub, it) }
                SwitchRow(Icons.Filled.PushPin, "Pin chat", c.pinned) { engine.setPinned(pub, it) }
                SwitchRow(Icons.Filled.Block, "Block", c.blocked) { engine.setBlocked(pub, it) }
                SheetItem(Icons.Filled.CleaningServices, "Clear chat") { engine.clearChat(pub) }
                SheetItem(Icons.Filled.Delete, "Delete chat and contact") { confirm = true }
            }
        }
        Spacer(Modifier.height(32.dp))
    }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false }, title = { Text("Delete this chat?") }, text = { Text("The messages and the secure connection are removed from this phone.") },
        confirmButton = { TextButton(onClick = { confirm = false; removed() }) { Text("Delete") } }, dismissButton = { TextButton(onClick = { confirm = false }) { Text("Cancel") } })
}

@Composable
private fun SwitchRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null); Spacer(Modifier.width(18.dp)); Text(label, Modifier.weight(1f)); Switch(on, change)
    }
}

@Composable
private fun SettingsPage(engine: ChatEngine, rev: Int, back: () -> Unit) {
    var relayText by remember(rev) { mutableStateOf(engine.relays.joinToString("\n")) }
    var rr by remember { mutableStateOf(engine.sendReadReceipts) }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        TopBar("Messenger settings", back)
        Card(Modifier.fillMaxWidth().padding(16.dp)) {
            Column {
                SwitchRow(Icons.Filled.DoneAll, "Send read receipts", rr) { rr = it; engine.sendReadReceipts = it; engine.save() }
            }
        }
        Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Relays", fontWeight = FontWeight.SemiBold)
                Text("Public mailboxes that carry sealed messages between phones. They can't read anything. More relays means more reliable delivery.", Modifier.padding(vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(relayText, { relayText = it }, modifier = Modifier.fillMaxWidth(), minLines = 4, textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp))
                Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { engine.setRelays(relayText.lines()) }) { Text("Save relays") }
                    OutlinedButton(onClick = { engine.setRelays(DEFAULT_RELAYS) }) { Text("Reset") }
                }
            }
        }
        Card(Modifier.fillMaxWidth().padding(16.dp)) {
            Column(Modifier.padding(16.dp)) {
                Text("Your identity", fontWeight = FontWeight.SemiBold)
                Text("Keys are stored only on this phone, sealed by the phone's secure hardware. If you uninstall Atlas or lose the phone, the identity and chats cannot be recovered.", Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
