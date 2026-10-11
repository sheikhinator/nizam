package com.nizam.app.feature.chief

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.feature.platform2.Updater
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.launch

private fun label(name: String) = when (name) {
    "list_dir" -> "Looked in"; "read_file" -> "Read"; "search_code" -> "Searched"; "edit_file" -> "Edited"; "create_file" -> "Wrote"; else -> name
}

/** Lines added and removed, roughly, for the review card. */
private fun diffLines(orig: String?, now: String): List<String> {
    val a = (orig ?: "").lines(); val b = now.lines()
    val aCount = a.groupingBy { it }.eachCount().toMutableMap()
    val out = ArrayList<String>()
    val bCount = b.groupingBy { it }.eachCount().toMutableMap()
    for (l in a) { val n = bCount[l] ?: 0; if (n > 0) bCount[l] = n - 1 else if (l.isNotBlank()) out += "- " + l.trim().take(120) }
    for (l in b) { val n = aCount[l] ?: 0; if (n > 0) aCount[l] = n - 1 else if (l.isNotBlank()) out += "+ " + l.trim().take(120) }
    return out
}

@Composable
fun ChiefScreen(container: com.nizam.app.AppContainer) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    val runnerTick by ChiefRunner.tick.collectAsState()
    var showSetup by remember { mutableStateOf(!ChiefStore.ready(c)) }
    var claudeKey by remember { mutableStateOf("") }
    var ghToken by remember { mutableStateOf("") }
    var capText by remember { mutableStateOf("%.0f".format(ChiefStore.cap(c))) }
    var input by remember { mutableStateOf("") }
    val running by ChiefRunner.running.collectAsState()
    var checking by remember { mutableStateOf(false) }
    val busy = running || checking
    val status = if (checking) "Checking the app…" else "Working…"
    val lines = ChiefRunner.log
    val costNow by ChiefRunner.costNow.collectAsState()
    val listState = rememberLazyListState()
    var expanded by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var reviewOpen by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(-1) }
    val phase = remember(runnerTick, tick) { ChiefStore.phase(c) }
    val pendingSummary = remember(runnerTick, tick) { ChiefStore.pendingSummary(c) }
    val pendingFiles = remember(runnerTick, tick) { ChiefStore.pendingFiles(c) }
    val month = remember(costNow, tick, running) { ChiefStore.spent(c) }

    LaunchedEffect(Unit) { ChiefRunner.resume(c); ChiefRunner.resumeJob(c) }
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Chief", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("Talk to Claude about this app. It reads the code, makes the change, and builds you an update.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { showSetup = !showSetup }) { Text("$" + "%.2f".format(month) + " / $" + "%.0f".format(ChiefStore.cap(c))) }
        }

        if (showSetup) Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Setup", fontWeight = FontWeight.SemiBold)
            Text("Your Anthropic key pays for Claude's work. Your GitHub token lets it save changes and build the app. Both are kept encrypted on this phone.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(claudeKey, { claudeKey = it }, label = { Text(if (ChiefStore.claudeKey(c).isBlank()) "Anthropic API key" else "Anthropic key saved (type to replace)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(ghToken, { ghToken = it }, label = { Text(if (ChiefStore.githubToken(c).isBlank()) "GitHub token" else "GitHub token saved (type to replace)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Text("Make the GitHub token at github.com/settings/personal-access-tokens: only the nizam repo, with Contents and Actions access.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(capText, { capText = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Monthly spending limit (US dollars)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Stronger brain (more accurate, costs more)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                var smart by remember { mutableStateOf(ChiefStore.smart(c)) }
                androidx.compose.material3.Switch(smart, { smart = it; ChiefStore.setSmart(c, it) })
            }
            Button(onClick = {
                if (claudeKey.isNotBlank()) ChiefStore.setClaudeKey(c, claudeKey.trim())
                if (ghToken.isNotBlank()) ChiefStore.setGithubToken(c, ghToken.trim())
                capText.toDoubleOrNull()?.let { ChiefStore.setCap(c, it) }
                claudeKey = ""; ghToken = ""; showSetup = !ChiefStore.ready(c); tick++
            }) { Text("Save") }
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (lines.isEmpty()) item {
                Column(Modifier.padding(vertical = 12.dp)) {
                    Text("Tell me what to add or fix. For example: \"On the Today screen, show my next prayer bigger\" or \"The Budget Doctor result is too long\".",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = { scope.launch {
                        checking = true
                        val findings = runCatching { com.nizam.app.feature.nx.SelfCheck.run(container) }.getOrDefault(emptyList())
                        val report = findings.joinToString("\n") { "[${it.level}] ${it.area}: ${it.text}" }
                        val crash = com.nizam.app.feature.safe.CrashGuard.report(c).take(1500)
                        input = "Please look at the app's own self-check below and fix anything you safely can in the code. If there is nothing to fix in the code, say so.\n\n$report\n\nRecent crashes:\n$crash"
                        checking = false
                    } }) { Text("Let Chief check the app and fix problems") }
                }
            }
            items(lines.size) { i ->
                when (val l = lines[i]) {
                    is Step.User -> Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(12.dp)).padding(10.dp)) {
                        Text("You", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant); Text(l.text) }
                    is Step.Say -> Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Text("Claude", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary); Text(l.text) }
                    is Step.Tool -> Column(Modifier.fillMaxWidth().clickable { expanded = if (i in expanded) expanded - i else expanded + i }
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text("● ${label(l.name)}  ${l.detail.take(60)}", fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                        if (i in expanded) Text(l.result.take(1500), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                    }
                    is Step.Note -> Text(l.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    is Step.Cost -> {}
                }
            }
            if (busy) item { Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            if (pendingFiles.isNotEmpty()) item {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(14.dp)).padding(12.dp)) {
                    Text("Ready for your OK", fontWeight = FontWeight.SemiBold)
                    Text(pendingSummary.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    pendingFiles.forEach { f ->
                        val d = remember(f.path) { diffLines(f.original, f.content) }
                        Text("${f.path.substringAfterLast('/')}  +${d.count { it.startsWith("+") }} −${d.count { it.startsWith("-") }}",
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                        if (reviewOpen) d.take(30).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                            color = if (it.startsWith("+")) androidx.compose.ui.graphics.Color(0xFF2E7D32) else androidx.compose.ui.graphics.Color(0xFFC62828)) }
                    }
                    Row {
                        TextButton(onClick = { reviewOpen = !reviewOpen }) { Text(if (reviewOpen) "Hide changes" else "See changes") }
                        TextButton(onClick = { ChiefStore.clearPending(c); tick++ }) { Text("Discard") }
                        Button(onClick = { ChiefRunner.approve(c, pendingSummary.orEmpty().ifBlank { "Update" }); tick++ }) { Text("Approve and build") }
                    }
                }
            }

            if (phase != "idle") item {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(14.dp)).padding(12.dp)) {
                    when (phase) {
                        "pushing" -> Text("Saving your change to GitHub…")
                        "building" -> { Text("Building your update. This takes about ten minutes. You can leave this screen; I will tell you when it is ready.")
                            TextButton(onClick = { ChiefRunner.resume(c) }) { Text("Keep watching") } }
                        "ready" -> {
                            Text("Your update is ready.", fontWeight = FontWeight.SemiBold)
                            if (progress in 0..99) Text("Downloading… $progress%")
                            Row {
                                Button(onClick = { scope.launch {
                                    val info = ChiefRunner.updateInfo(c, ChiefStore.detail(c))
                                    if (info == null) { ChiefRunner.log += Step.Note("Could not find the build."); return@launch }
                                    runCatching { progress = 0; val f = Updater.download(c, info) { progress = it }; progress = 100; Updater.install(c, f) }
                                        .onFailure { ChiefRunner.log += Step.Note("Download failed: ${it.message}") }
                                } }) { Text("Update now") }
                                TextButton(onClick = { ChiefStore.setPhase(c, "idle"); ChiefRunner.tick.value++ }) { Text("Dismiss") }
                            }
                        }
                        "failed" -> {
                            Text("The build did not pass.", fontWeight = FontWeight.SemiBold)
                            Text(ChiefStore.detail(c).take(600), fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                            TextButton(onClick = {
                                input = "The last build failed with these errors. Please fix them:\n" + ChiefStore.detail(c).take(1200)
                                ChiefStore.setPhase(c, "idle"); ChiefRunner.tick.value++
                            }) { Text("Ask Claude to fix it") }
                        }
                        else -> Text(phase)
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(input, { input = it }, placeholder = { Text("What should change?") }, modifier = Modifier.weight(1f), maxLines = 4, enabled = !busy)
            Button(enabled = !busy && input.isNotBlank() && ChiefStore.ready(c), onClick = {
                val text = input.trim(); input = ""
                ChiefRunner.startJob(c, text)
            }) { Text("Send") }
        }
    }
}

object ChiefSpecs {
    val all: List<Spec> = listOf(
        ScreenSpec("chief", "Chief", "Talk to Claude to add, fix or change anything in this app", Icons.Outlined.Handyman, Hub.Tools,
            "claude chief engineer build update fix change add feature developer code assistant github") { container, _ -> ChiefScreen(container) },
    )
}
