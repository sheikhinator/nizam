package com.nizam.app.feature.safe

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.nx.Share
import com.nizam.app.feature.nx.SelfCheck
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

/** Records crashes locally. Two crashes within three minutes start Atlas in a minimal recovery screen instead of looping. */
object CrashGuard {
    private fun log(c: Context) = File(c.filesDir, "crash.log")
    private fun p(c: Context) = c.getSharedPreferences("crashguard", 0)

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                val now = System.currentTimeMillis()
                val recent = (p(app).getString("times", "").orEmpty().split(",").mapNotNull { it.toLongOrNull() } + now).filter { now - it < 3 * 60_000L }
                p(app).edit().putString("times", recent.joinToString(",")).commit()
                val f = log(app)
                val old = if (f.exists() && f.length() < 40_000) f.readText() else ""
                f.writeText("${Instant.ofEpochMilli(now)} [${t.name}] ${e::class.java.name}: ${e.message}\n${e.stackTrace.take(14).joinToString("\n") { "  at $it" }}\n\n$old".take(60_000))
            }
            previous?.uncaughtException(t, e)
        }
    }

    fun shouldSafeMode(c: Context): Boolean {
        val now = System.currentTimeMillis()
        return p(c).getString("times", "").orEmpty().split(",").mapNotNull { it.toLongOrNull() }.count { now - it < 3 * 60_000L } >= 2 && !p(c).getBoolean("skip", false)
    }
    fun markStable(c: Context) = p(c).edit().putString("times", "").putBoolean("skip", false).apply()
    fun skip(c: Context) = p(c).edit().putBoolean("skip", true).apply()
    fun report(c: Context): String = runCatching { log(c).readText() }.getOrDefault("No crashes recorded.")
    fun clear(c: Context) { log(c).delete(); markStable(c) }
}

@Composable
fun SafeModeScreen(container: AppContainer, onContinue: () -> Unit) {
    val c = LocalContext.current; val scope = rememberCoroutineScope()
    var findings by remember { mutableStateOf<List<SelfCheck.Finding>?>(null) }
    LaunchedEffect(Unit) { findings = runCatching { SelfCheck.run(container) }.getOrNull() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Safe mode", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Atlas stopped unexpectedly twice in a row, so it opened here instead of crashing again. Your data is untouched.", style = MaterialTheme.typography.bodyMedium)
        Text("Check results", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        val f = findings
        if (f == null) Text("Checking…") else f.filter { it.level != SelfCheck.Level.OK }.ifEmpty { listOf(SelfCheck.Finding("All", SelfCheck.Level.OK, "Nothing wrong found in your saved data.")) }
            .forEach { Text("${if (it.level == SelfCheck.Level.BAD) "🔴" else if (it.level == SelfCheck.Level.WARN) "🟡" else "🟢"} ${it.area}: ${it.text}") }
        Button(onClick = { CrashGuard.skip(c); onContinue() }) { Text("Open Atlas anyway") }
        TextButton(onClick = { Share.text(c, CrashGuard.report(c), "Atlas crash report") }) { Text("Share crash report") }
        TextButton(onClick = { CrashGuard.clear(c); onContinue() }) { Text("Clear the report and continue") }
        Text(CrashGuard.report(c).take(900), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
