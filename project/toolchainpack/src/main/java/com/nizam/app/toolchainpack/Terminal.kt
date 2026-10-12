package com.nizam.app.toolchainpack

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

private const val DEMO_SOURCE = """public class AtlasDemo {
    public static void main(String[] args) {
        System.out.println("Hello from a build compiled right here on-device.");
        int total = 0;
        for (int i = 1; i <= 10; i++) total += i;
        System.out.println("1 to 10 added up on-device: " + total);
    }
}"""

/**
 * A real command runner, scoped to what this module actually controls: whatever is already on the
 * phone (ls, pwd, the Android shell), not binaries downloaded at runtime — those still need the
 * toolchain's own build-time packaging before Android will run them, which is the next stage.
 */
private object Shell {
    suspend fun run(cmd: String, workDir: java.io.File): Pair<Int, String> = withContext(Dispatchers.IO) {
        runCatching {
            val p = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).directory(workDir).start()
            val out = BufferedReader(InputStreamReader(p.inputStream)).use { it.readText() }
            val code = p.waitFor()
            code to out
        }.getOrElse { -1 to "Couldn't run that: ${it.message}" }
    }
}

@Composable
fun TerminalScreen() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var cmd by remember { mutableStateOf("") }
    var lines by remember { mutableStateOf(listOf("Atlas terminal — runs what the phone already has, plus a real Java compiler and dexer bundled in below.")) }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val workDir = remember { java.io.File("/sdcard") }

    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Terminal", style = MaterialTheme.typography.titleMedium)
        LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState) {
            items(lines) { l -> Text(l, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall) }
        }
        OutlinedTextField(cmd, { cmd = it }, label = { Text("Command") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = cmd.isNotBlank() && !busy, onClick = {
            val c2 = cmd; lines = lines + "$ $c2"; cmd = ""; busy = true
            scope.launch {
                val (code, out) = Shell.run(c2, workDir)
                lines = lines + out.lines().filter { it.isNotBlank() } + "(exit $code)"
                busy = false
            }
        }) { Text(if (busy) "Running…" else "Run") }
        OutlinedButton(enabled = !busy, onClick = {
            lines = lines + "$ atlas build --demo"; busy = true
            scope.launch {
                val result = withContext(Dispatchers.Default) {
                    BuildEngine.compile(java.io.File(c.cacheDir, "demo-build"), "AtlasDemo", DEMO_SOURCE)
                }
                lines = lines + result.steps.map { "  ${if (it.ok) "✓" else "✕"} ${it.name}: ${it.detail}" }
                val dex = result.dex
                if (dex != null) {
                    val ran = runCatching { withContext(Dispatchers.Default) { BuildEngine.runDex(dex, "AtlasDemo") } }
                    lines = lines + (ran.getOrNull()?.lines()?.filter { it.isNotBlank() }?.map { "  > $it" }
                        ?: listOf("  ✕ Run: ${ran.exceptionOrNull()?.message}"))
                }
                busy = false
            }
        }) { Text("Run a demo build (compiles real Java on this phone)") }
    }
}
