package com.nizam.app.feature.atlaschat

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.TextButton
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Edit
import coil.compose.AsyncImage
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ───────────────────────── create a channel or group ─────────────────────────

@Composable
fun NewRoom(engine: RoomEngine, channel: Boolean, back: () -> Unit, open: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        RoomTopBar(if (channel) "New channel" else "New group", back)
        Column(Modifier.padding(20.dp)) {
            Box(Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                Icon(if (channel) Icons.Filled.Campaign else Icons.Filled.Groups, null, Modifier.size(34.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(name, { name = it }, label = { Text(if (channel) "Channel name" else "Group name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            RoomChoiceRow(Icons.Filled.Public, "Public", "Anyone with the link can join" + if (channel) " and read" else " and chat", !isPrivate) { isPrivate = false }
            RoomChoiceRow(Icons.Filled.Lock, "Private", "Only people you send the invite link to can join; messages are sealed with a key", isPrivate) { isPrivate = true }
            Spacer(Modifier.height(24.dp))
            Button(onClick = { val r = engine.create(name, channel, isPrivate); open(r.id) }, enabled = name.trim().length >= 2, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text(if (channel) "Create channel" else "Create group")
            }
        }
    }
}

@Composable
private fun RoomChoiceRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, sub: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
        .background(if (on) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else Color.Transparent)
        .clickable(onClick = onClick).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ───────────────────────── join by link ─────────────────────────

@Composable
fun JoinRoom(engine: RoomEngine, back: () -> Unit, open: (String) -> Unit) {
    var link by remember { mutableStateOf("") }
    var err by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        RoomTopBar("Join by link", back)
        Column(Modifier.padding(20.dp)) {
            Text("Paste an invite link to join a channel or group.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(link, { link = it; err = "" }, label = { Text("atlasroom:…") }, singleLine = true, isError = err.isNotEmpty(),
                supportingText = { if (err.isNotEmpty()) Text(err) }, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(18.dp))
            Button(onClick = { val r = engine.join(link); if (r == null) err = "That link doesn't look right." else open(r.id) },
                enabled = link.startsWith("atlasroom:"), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("Join") }
        }
    }
}

// ───────────────────────── a channel / group thread ─────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoomScreen(engine: RoomEngine, roomId: String, rev: Int, back: () -> Unit) {
    val r = engine.rooms[roomId] ?: return
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val msgs = remember(rev) { engine.msgs(roomId) }
    val list = rememberLazyListState()
    var text by remember(roomId) { mutableStateOf("") }
    var attachOpen by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var caption by remember { mutableStateOf("") }
    val owner = r.secret != null
    LaunchedEffect(roomId, msgs.size) { engine.markRead(roomId); if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1) }
    val canPost = engine.canPost(r)
    fun sendUri(uri: Uri, cap: String) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                val cr = ctx.contentResolver
                var mime = cr.getType(uri) ?: "application/octet-stream"
                val nm = mediaNameOf(cr, uri)
                var bytes = cr.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                if (mime.startsWith("image/")) downscaleJpeg(bytes)?.let { bytes = it; mime = "image/jpeg" }
                engine.sendMedia(roomId, bytes, mime, nm, cap)
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) { pendingUri = uri; caption = "" } }
    var editOpen by remember { mutableStateOf(false) }
    var editName by remember(roomId) { mutableStateOf(r.name) }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            runCatching {
                val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@launch
                val small = downscaleJpeg(bytes, max = 400, quality = 80) ?: bytes
                engine.setPhoto(roomId, small)
            }
        }
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        Surface(tonalElevation = 3.dp) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                RoomAvatar(r, 40.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(r.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (r.private) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.Lock, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.outline) }
                    }
                    Text((if (r.channel) "Channel" else "Group") + " · " + (if (r.private) "Private" else "Public"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { clip.setText(AnnotatedString(engine.inviteLink(r))); com.nizam.app.ui.components.Feedback.show("Invite link copied") }) { Icon(Icons.Filled.Link, "Invite link") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Copy invite link") }, leadingIcon = { Icon(Icons.Filled.ContentCopy, null) },
                            onClick = { menu = false; clip.setText(AnnotatedString(engine.inviteLink(r))); com.nizam.app.ui.components.Feedback.show("Invite link copied") })
                        if (owner) DropdownMenuItem(text = { Text("Edit " + (if (r.channel) "channel" else "group")) }, leadingIcon = { Icon(Icons.Filled.Edit, null) },
                            onClick = { menu = false; editName = r.name; editOpen = true })
                        DropdownMenuItem(text = { Text(if (r.pinned) "Unpin" else "Pin") }, leadingIcon = { Icon(Icons.Filled.PushPin, null) },
                            onClick = { menu = false; engine.setPinned(roomId, !r.pinned) })
                        DropdownMenuItem(text = { Text(if (r.muted) "Unmute" else "Mute") }, leadingIcon = { Icon(Icons.Filled.NotificationsOff, null) },
                            onClick = { menu = false; engine.setMuted(roomId, !r.muted) })
                        DropdownMenuItem(
                            text = { Text(if (owner) "Delete " + (if (r.channel) "channel" else "group") else "Leave", color = MaterialTheme.colorScheme.error) },
                            leadingIcon = { Icon(if (owner) Icons.Filled.Delete else Icons.Filled.Logout, null, tint = MaterialTheme.colorScheme.error) },
                            onClick = { menu = false; engine.leave(roomId); back() })
                    }
                }
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth().background(tgWallpaper()), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (msgs.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(if (r.channel) Icons.Filled.Campaign else Icons.Filled.Groups, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(10.dp))
                    Text(if (r.channel) "No posts yet" else "No messages yet", style = MaterialTheme.typography.titleMedium)
                    Text("Share the invite link to bring people in.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
            items(msgs, key = { it.id }) { m -> RoomBubble(m, r.channel, engine) }
        }
        if (canPost) {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = { attachOpen = true }, modifier = Modifier.size(52.dp)) { Icon(Icons.Filled.AttachFile, "Attach", tint = MaterialTheme.colorScheme.primary) }
                OutlinedTextField(text, { text = it }, placeholder = { Text(if (r.channel) "Post to channel" else "Message") }, shape = RoundedCornerShape(26.dp), maxLines = 5, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = { engine.send(roomId, text); text = "" }, enabled = text.isNotBlank(), modifier = Modifier.size(52.dp)) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send")
                }
            }
        } else {
            Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Campaign, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.outline)
                Spacer(Modifier.width(8.dp))
                Text("Only the owner can post in this channel", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    if (attachOpen) ModalBottomSheet(onDismissRequest = { attachOpen = false }) {
        Column(Modifier.padding(bottom = 24.dp)) {
            RoomSheetItem(Icons.Filled.Image, "Photo") { attachOpen = false; picker.launch("image/*") }
            RoomSheetItem(Icons.Filled.Videocam, "Video") { attachOpen = false; picker.launch("video/*") }
            RoomSheetItem(Icons.Filled.InsertDriveFile, "File") { attachOpen = false; picker.launch("*/*") }
        }
    }

    if (pendingUri != null) AlertDialog(
        onDismissRequest = { pendingUri = null },
        title = { Text("Add a caption") },
        text = { OutlinedTextField(caption, { caption = it }, placeholder = { Text("Caption (optional)") }, modifier = Modifier.fillMaxWidth(), maxLines = 3) },
        confirmButton = { TextButton(onClick = { pendingUri?.let { sendUri(it, caption.trim()) }; pendingUri = null }) { Text("Send") } },
        dismissButton = { TextButton(onClick = { pendingUri = null }) { Text("Cancel") } }
    )

    if (editOpen) AlertDialog(
        onDismissRequest = { editOpen = false },
        title = { Text("Edit " + if (r.channel) "channel" else "group") },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RoomAvatar(r, 56.dp)
                    Spacer(Modifier.width(12.dp))
                    TextButton(onClick = { photoPicker.launch("image/*") }) { Text("Change photo") }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(editName, { editName = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { engine.rename(roomId, editName); editOpen = false }) { Text("Save") } },
        dismissButton = { TextButton(onClick = { editOpen = false }) { Text("Cancel") } }
    )
}

@Composable
private fun RoomAvatar(r: Room, size: androidx.compose.ui.unit.Dp) {
    val path = r.photoPath
    Box(Modifier.size(size).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
        if (path != null) AsyncImage(model = java.io.File(path), contentDescription = r.name, modifier = Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
        else Icon(if (r.channel) Icons.Filled.Campaign else Icons.Filled.Groups, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun RoomSheetItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null); Spacer(Modifier.width(18.dp)); Text(label)
    }
}

@Composable
private fun RoomBubble(m: Msg, channel: Boolean, engine: RoomEngine) {
    val mine = m.mine
    val ctx = LocalContext.current
    val bg = tgBubble(mine)
    val fg = tgInk()
    val shape = RoundedCornerShape(18.dp, 18.dp, if (mine) 4.dp else 18.dp, if (mine) 18.dp else 4.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(Modifier.widthIn(max = 300.dp).clip(shape).background(bg).padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (!mine && !channel) Text(m.senderName ?: "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            if (m.hasMedia) {
                LaunchedEffect(m.id, m.mediaPath) { if (m.mediaPath == null && m.mediaUrl != null) runCatching { engine.fetchMedia(m) } }
                MediaBlock(m.mediaMime ?: "", m.mediaPath, m.mediaName, m.mediaSize, fg) { p -> openMedia(ctx, p, m.mediaMime ?: "") }
                if (m.text.isNotEmpty()) Spacer(Modifier.height(6.dp))
            }
            if (m.text.isNotEmpty()) Text(m.text, color = fg, fontSize = 16.sp)
            Text(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(m.ts)), fontSize = 10.sp, color = fg.copy(alpha = 0.55f), modifier = Modifier.align(Alignment.End).padding(top = 2.dp))
        }
    }
}

// ───────────────────────── a row in the chat list ─────────────────────────

@Composable
fun RoomRow(engine: RoomEngine, r: Room, rev: Int, onClick: () -> Unit) {
    val last = remember(rev) { engine.msgs(r.id).lastOrNull() }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        RoomAvatar(r, 48.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (r.private) { Spacer(Modifier.width(4.dp)); Icon(Icons.Filled.Lock, null, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.outline) }
            }
            Text(last?.let { (if (it.mine) "You: " else (it.senderName?.plus(": ") ?: "")) + it.text } ?: (if (r.channel) "Channel" else "Group"),
                maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        if (r.unread > 0) Box(Modifier.padding(start = 8.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).size(20.dp), contentAlignment = Alignment.Center) {
            Text("${r.unread}", color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun RoomTopBar(title: String, back: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = back) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
    }
}
