package com.nizam.app.feature.vault

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.nizam.app.ui.design.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.displayNameFor
import com.nizam.app.core.formatBytes
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun VaultScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by container.vaultRepository.items().collectAsState(initial = emptyList())

    var passphrase by remember { mutableStateOf("") }
    var playing by remember { mutableStateOf<Pair<VaultItem, File>?>(null) }

    // Plaintext must never outlive the viewing. Sweep any decrypted leftovers on entry.
    LaunchedEffect(Unit) {
        context.cacheDir.listFiles()?.filter { it.name.startsWith("open_") }?.forEach {
            runCatching { it.delete() }
        }
    }
    var unlocked by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true; status = null
            try {
                val name = displayNameFor(context, uri)
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val fileName = "v_${System.currentTimeMillis()}.enc"
                val out = File(VaultCrypto.vaultDir(context), fileName)
                val size = VaultCrypto.encryptInto(context, uri, passphrase, out)
                container.vaultRepository.add(
                    VaultItem(name = name, mime = mime, fileName = fileName, sizeBytes = size)
                )
                status = "Encrypted and stored. The original is untouched — delete it yourself if you want it gone."
            } catch (e: Exception) {
                status = "Failed: ${com.nizam.app.ui.components.Feedback.friendly(e)}"
            }
            busy = false
        }
    }

    val current = playing
    if (current != null) {
        val item = current.first
        val file = current.second
        when {
            item.mime.startsWith("image") ->
                PhotoViewer(file, item.name) { runCatching { file.delete() }; playing = null }
            item.mime.startsWith("video") ->
                VideoPlayer(file, item.name) { runCatching { file.delete() }; playing = null }
            item.mime.startsWith("audio") ->
                AudioPlayer(file, item.name) { runCatching { file.delete() }; playing = null }
            else -> {
                LaunchedEffect(file) {
                    runCatching {
                        val uri = FileProvider.getUriForFile(
                            context, "${context.packageName}.fileprovider", file
                        )
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW).apply {
                                setDataAndType(uri, item.mime)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        )
                    }
                    playing = null
                }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Vault", "Photos, video and audio — decrypted only while you watch")

        if (!unlocked) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Enter your vault passphrase", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = passphrase,
                        onValueChange = { passphrase = it },
                        label = { Text("Passphrase") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(
                        enabled = passphrase.length >= 6,
                        onClick = { unlocked = true }
                    ) { Text("Unlock") }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "This passphrase is never stored anywhere. It derives the encryption key each " +
                            "time. If you forget it, the files cannot be recovered — by you, by me, by anyone. " +
                            "That is what makes it a vault rather than a folder.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Column
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                onClick = { picker.launch(arrayOf("image/*", "video/*", "application/pdf", "*/*")) }
            ) { Text(if (busy) "Encrypting…" else "＋  Add file") }
            Button(onClick = {
                unlocked = false; passphrase = ""
                context.cacheDir.listFiles()?.filter { it.name.startsWith("open_") }
                    ?.forEach { runCatching { it.delete() } }
            }) { Text("Lock") }
        }

        if (busy) {
            Spacer(Modifier.height(12.dp))
            CircularProgressIndicator()
        }
        status?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(14.dp))
        Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)

        if (items.isEmpty()) {
            Spacer(Modifier.height(16.dp))
            Text(
                "Vault is empty. Files you add here are encrypted and won't show in your gallery.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Column(Modifier.fillMaxWidth()) {
            items.forEach { item ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        when {
                            item.mime.startsWith("image") -> "🖼️"
                            item.mime.startsWith("video") -> "🎞️"
                            item.mime.startsWith("audio") -> "🎵"
                            item.mime.contains("pdf") -> "📄"
                            else -> "📁"
                        },
                        style = MaterialTheme.typography.titleLarge
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            formatBytes(item.sizeBytes),
                            style = MonoNumber,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "open",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(role = Role.Button) {
                            scope.launch {
                                busy = true; status = null
                                try {
                                    val encrypted = File(VaultCrypto.vaultDir(context), item.fileName)
                                    val suffix = item.name.substringAfterLast('.', "")
                                    val cache = File(
                                        context.cacheDir,
                                        "open_${item.id}" + if (suffix.isBlank()) "" else ".$suffix"
                                    )
                                    VaultCrypto.decryptToCache(encrypted, passphrase, cache)
                                    playing = item to cache
                                } catch (e: Exception) {
                                    status = "Could not open — wrong passphrase, or the file is damaged."
                                }
                                busy = false
                            }
                        }
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        "delete",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.clickable(role = Role.Button) {
                            scope.launch { container.vaultRepository.remove(item, context) }
                        }
                    )
                }
                Divider(thickness = 1.dp, color = MaterialTheme.colorScheme.surfaceVariant)
            }
            run { Spacer(Modifier.height(40.dp)) }
        }
    }
}
