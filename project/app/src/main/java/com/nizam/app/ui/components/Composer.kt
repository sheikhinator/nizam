package com.nizam.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.nizam.app.feature.attach.Attachment
import com.nizam.app.feature.attach.Attachments
import com.nizam.app.feature.mcp.Mcp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A switch in the composer's "+" sheet — web search, voice replies, auto-approve. */
data class ComposerToggle(val label: String, val hint: String, val icon: ImageVector, val on: Boolean, val onChange: (Boolean) -> Unit)

/**
 * The message box, shaped after Claude's: attachments sit inside the box above the text, "+" opens a
 * sheet for camera, photos, files, tools and connections, and send turns into a spinner while Atlas works.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    attachments: List<Attachment>,
    onAttachments: (List<Attachment>) -> Unit,
    busy: Boolean,
    placeholder: String,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
    onMic: (() -> Unit)? = null,
    toggles: List<ComposerToggle> = emptyList(),
    showConnections: Boolean = true
) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    fun add(uris: List<android.net.Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            loading = true
            val added = withContext(Dispatchers.IO) { uris.take(10).mapNotNull { Attachments.fromUri(c, it) } }
            loading = false
            if (added.size < uris.size) Feedback.show("Some files couldn't be attached (20 MB limit)")
            onAttachments((attachments + added).takeLast(10))
        }
    }

    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { add(it) }
    val photos = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(10)) { add(it) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bmp ->
        if (bmp != null) scope.launch {
            val a = withContext(Dispatchers.IO) { Attachments.fromBitmap(c, bmp) }
            onAttachments(attachments + a)
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) camera.launch(null) else Feedback.show("Camera permission is needed to take a photo")
    }
    fun openCamera() {
        if (ContextCompat.checkSelfPermission(c, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera.launch(null)
        else cameraPermission.launch(Manifest.permission.CAMERA)
    }

    val canSend = (draft.isNotBlank() || attachments.isNotEmpty()) && !busy && !loading

    Column(
        modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f), RoundedCornerShape(24.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        AnimatedVisibility(attachments.isNotEmpty() || loading) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                attachments.forEach { a -> AttachmentChip(a) { onAttachments(attachments - a) } }
                if (loading) Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
        }

        Box(Modifier.fillMaxWidth().heightIn(min = 36.dp).padding(horizontal = 6.dp, vertical = 6.dp)) {
            if (draft.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            BasicTextField(
                value = draft, onValueChange = onDraft, maxLines = 6,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            )
        }

        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.Filled.Add, "Add files, photos and tools", filled = false) { sheet = true }
            Spacer(Modifier.width(6.dp))
            // Active tools show as pills — tap to switch off, like Claude's
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                toggles.filter { it.on }.forEach { t ->
                    Row(
                        Modifier.clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f))
                            .clickable(role = Role.Button) { t.onChange(false) }
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(t.icon, null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(4.dp))
                        Text(t.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            if (onMic != null) { RoundButton(Icons.Filled.Mic, "Speak", filled = false) { onMic() }; Spacer(Modifier.width(6.dp)) }
            Box(
                Modifier.size(38.dp).clip(CircleShape)
                    .background(if (canSend) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    .clickable(enabled = canSend, role = Role.Button) { onSend() },
                contentAlignment = Alignment.Center
            ) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                else Icon(Icons.Filled.ArrowUpward, "Send",
                    tint = if (canSend) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (sheet) ModalBottomSheet(onDismissRequest = { sheet = false }) {
        var servers by remember { mutableStateOf(Mcp.servers(c)) }
        Column(Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState())
            .padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SheetTile(Icons.Filled.PhotoCamera, "Camera", Modifier.weight(1f)) { sheet = false; openCamera() }
                SheetTile(Icons.Filled.PhotoLibrary, "Photos", Modifier.weight(1f)) {
                    sheet = false
                    photos.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                SheetTile(Icons.Filled.AttachFile, "Files", Modifier.weight(1f)) { sheet = false; files.launch(arrayOf("*/*")) }
            }
            Text("Images, PDFs, Word, PowerPoint, Excel, text and code — up to 10 files, 20 MB each",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))

            if (toggles.isNotEmpty()) {
                SheetHeader("TOOLS")
                toggles.forEach { t -> SwitchRow(t.icon, t.label, t.hint, t.on) { t.onChange(it) } }
            }

            if (showConnections) {
                val apps = remember { runCatching { com.nizam.app.feature.connect.Connectors.connected(c).filter { it.kind == "api" } }.getOrDefault(emptyList()) }
                SheetHeader("CONNECTIONS · ${servers.count { it.enabled }} of ${servers.size} MCP on" + if (apps.isNotEmpty()) " · ${apps.size} apps" else "")
                if (servers.isEmpty() && apps.isEmpty()) Text("Nothing connected yet — add apps and MCP servers in Connectors.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp))
                servers.forEach { s ->
                    // a server saved without a name shows its address instead of a blank row
                    val host = s.url.substringAfter("://").substringBefore("/").ifBlank { s.url }
                    val title = s.name.ifBlank { host }
                    SwitchRow(Icons.Filled.Hub, title, (if (Mcp.isDown(s.url)) "Offline right now · " else "MCP · ") + s.url.substringAfter("://").take(44), s.enabled) { on ->
                        servers = servers.map { if (it.url == s.url && it.name == s.name) it.copy(enabled = on) else it }
                        Mcp.save(c, servers)
                    }
                }
                if (apps.isNotEmpty()) {
                    Text("Apps Atlas can use: " + apps.joinToString { "${it.emoji} ${it.name}" }, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
                }
                Text("Manage connectors →", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp).clip(RoundedCornerShape(8.dp)).clickable {
                        sheet = false; com.nizam.app.ui.screens.search.AtlasNav.go?.invoke("nx/connectors")
                    }.padding(vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, filled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(38.dp).clip(CircleShape)
            .background(if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { Icon(icon, description, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface) }
}

@Composable
private fun SheetTile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun SheetHeader(text: String) {
    Spacer(Modifier.height(12.dp))
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
}

@Composable
private fun SwitchRow(icon: ImageVector, title: String, hint: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!on) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Switch(on, onChange)
    }
}

/** An attached file inside the composer: a thumbnail for photos, a small card for documents. */
@Composable
fun AttachmentChip(a: Attachment, onRemove: (() -> Unit)? = null) {
    Box {
        if (a.isImage) {
            val bmp = remember(a.file.path) {
                runCatching {
                    val o = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
                    android.graphics.BitmapFactory.decodeFile(a.file.path, o)?.asImageBitmap()
                }.getOrNull()
            }
            Box(Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))) {
                bmp?.let { Image(it, a.name, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp)) }
            }
        } else {
            Row(
                Modifier.height(56.dp).widthIn(max = 200.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(if (a.isPdf) Icons.Filled.PictureAsPdf else Icons.Filled.Description, null,
                    Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.padding(end = 12.dp)) {
                    Text(a.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(a.name.substringAfterLast('.', "file").uppercase() + " · " + a.sizeLabel(),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (onRemove != null) Box(
            Modifier.align(Alignment.TopEnd).padding(3.dp).size(18.dp).clip(CircleShape)
                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
                .clickable(role = Role.Button) { onRemove() },
            contentAlignment = Alignment.Center
        ) { Icon(Icons.Filled.Close, "Remove", Modifier.size(12.dp), tint = MaterialTheme.colorScheme.surface) }
    }
}
