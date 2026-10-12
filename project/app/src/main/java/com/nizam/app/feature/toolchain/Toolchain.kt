package com.nizam.app.feature.toolchain

import android.content.Context
import android.os.Build
import android.os.StatFs
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Groundwork for building Android apps on the phone itself. Android will only ever run a binary that
 * was packaged inside an app at build time — never one downloaded afterwards — so this screen checks
 * the phone can take the toolchain and fetches it, verified, ready for the companion app that will
 * actually package and run it. It does not run any code; it only prepares.
 */
object Toolchain {
    private const val URL = "https://github.com/termux/termux-packages/releases/download/bootstrap-2026.10.11-r1%2Bapt.android-7/bootstrap-aarch64.zip"
    private const val SHA256 = "b7827bc99710293d0e871df8b96d9d3a8ab6b22ee4b4a6f3ac076754818990ab"
    private const val SIZE = 33_044_364L

    fun compatible(): Boolean = Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }
    fun freeStorageOk(c: Context): Boolean = StatFs(c.filesDir.path).let { it.availableBytes > 500L * 1024 * 1024 }
    fun androidVersionOk(): Boolean = Build.VERSION.SDK_INT >= 26

    private fun file(c: Context) = File(c.filesDir, "toolchain/bootstrap-aarch64.zip")
    fun ready(c: Context) = file(c).length() == SIZE

    suspend fun download(c: Context, progress: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
        val out = file(c); out.parentFile?.mkdirs()
        val part = File(out.parentFile, "bootstrap.part")
        try {
            val http = OkHttpClient.Builder().readTimeout(90, java.util.concurrent.TimeUnit.SECONDS).build()
            http.newCall(Request.Builder().url(URL).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext "Download failed (${r.code})"
                val body = r.body ?: return@withContext "Download failed (empty)"
                val md = MessageDigest.getInstance("SHA-256")
                var got = 0L
                body.byteStream().use { ins -> part.outputStream().use { os ->
                    val buf = ByteArray(1 shl 16)
                    while (true) { val n = ins.read(buf); if (n < 0) break; os.write(buf, 0, n); md.update(buf, 0, n); got += n; progress((got.toFloat() / SIZE).coerceIn(0f, 1f)) }
                } }
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (hex != SHA256) { part.delete(); return@withContext "The download didn't match what was expected. Try again." }
            }
            out.delete(); part.renameTo(out)
            null
        } catch (t: Throwable) { part.delete(); "Download stopped: ${t.message ?: t.javaClass.simpleName}" }
    }

    fun remove(c: Context) { file(c).delete() }
}

@Composable
internal fun ToolchainScreen() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var prog by remember { mutableStateOf<Float?>(null) }
    var msg by remember { mutableStateOf("") }
    var ready by remember { mutableStateOf(Toolchain.ready(c)) }

    // No verticalScroll here: this screen is reached through the app's generic tool-screen shell,
    // which already places its content inside a scrolling LazyColumn — nesting another scrollable
    // Column inside that crashes Compose ("infinity maximum height constraints").
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Build Atlas on this phone — groundwork", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text("Android only ever runs code that was packaged inside an app when it was installed — never something downloaded afterwards. " +
            "So a real on-phone build needs a second, small companion app with the Java tools built in, which this screen is the first step toward. " +
            "This page checks your phone is ready and fetches the base files, verified byte-for-byte. It does not run anything yet.",
            style = MaterialTheme.typography.bodyMedium)

        Check("Phone processor (arm64)", Toolchain.compatible())
        Check("Android version (8 or newer)", Toolchain.androidVersionOk())
        Check("Free storage (500 MB+)", Toolchain.freeStorageOk(c))

        if (ready) {
            Text("The base files are downloaded and verified.", color = MaterialTheme.colorScheme.primary)
            Button(onClick = {
                runCatching {
                    c.startActivity(android.content.Intent(c, TerminalActivity::class.java))
                }.onFailure { com.nizam.app.ui.components.Feedback.show("Couldn't open the terminal: ${it.message}") }
            }) { Text("Open the terminal") }
            OutlinedButton(onClick = { Toolchain.remove(c); ready = false }) { Text("Remove them") }
        } else {
            val p = prog
            if (p != null) { LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()); Text("${(p * 100).toInt()}%") }
            else Button(enabled = Toolchain.compatible() && Toolchain.androidVersionOk() && Toolchain.freeStorageOk(c), onClick = {
                msg = ""; prog = 0f
                scope.launch { val e = Toolchain.download(c) { prog = it }; prog = null; ready = Toolchain.ready(c); msg = e ?: "Downloaded and verified." }
            }) { Text("Download the base files (33 MB)") }
        }
        if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall)
        Text("Next: a separate Atlas Toolchain app, built with these tools packaged in from the start, so Android will actually run them. " +
            "That's a real, separate build — it isn't part of this screen yet.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Check(label: String, ok: Boolean) {
    Text((if (ok) "✓ " else "✕ ") + label, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
}

object ToolchainSpecs {
    val all: List<Spec> = listOf(
        ScreenSpec("toolchain", "Build Atlas on this phone", "Groundwork for compiling Atlas without the cloud", Icons.Outlined.Build,
            Hub.Tools, "toolchain compiler build on device local offline gradle jdk termux") { _, _ -> ToolchainScreen() },
    )
}
