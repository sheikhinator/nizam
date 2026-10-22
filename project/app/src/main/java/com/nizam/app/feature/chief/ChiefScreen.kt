package com.nizam.app.feature.chief

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.speech.RecognizerIntent
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.feature.nx.Md
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.feature.platform2.Updater
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the transcript shows: a message, a note, or a run of tool steps. */
private sealed class Block {
    data class Me(val text: String) : Block()
    data class Claude(val text: String) : Block()
    data class Note(val text: String) : Block()
    data class Work(val steps: List<Step.Tool>, val key: Int) : Block()
}

private fun toBlocks(steps: List<Step>): List<Block> {
    val out = ArrayList<Block>()
    var run = ArrayList<Step.Tool>()
    var runKey = 0
    fun flush() { if (run.isNotEmpty()) { out += Block.Work(run.toList(), runKey); run = ArrayList() } }
    steps.forEachIndexed { i, s ->
        when (s) {
            is Step.Tool -> { if (run.isEmpty()) runKey = i; run += s }
            is Step.User -> { flush(); out += Block.Me(s.text) }
            is Step.Say -> { flush(); out += Block.Claude(s.text) }
            is Step.Note -> { flush(); out += Block.Note(s.text) }
            else -> {}
        }
    }
    flush()
    return out
}

private fun verb(name: String) = when (name) {
    "list_dir" -> "Looked in"; "read_file" -> "Read"; "search_code" -> "Searched"; "edit_file" -> "Edited"; "create_file" -> "Wrote"; else -> name
}

private fun encodeImage(c: Context, uri: android.net.Uri): String? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / sample > 1400 || bounds.outHeight / sample > 1400) sample *= 2
    val bmp = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) } ?: return null
    val out = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 72, out)
    Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
}.getOrNull()

private fun diffLines(orig: String?, now: String): List<String> {
    val a = (orig ?: "").lines(); val b = now.lines()
    val aCount = a.groupingBy { it }.eachCount().toMutableMap()
    val bCount = b.groupingBy { it }.eachCount().toMutableMap()
    val out = ArrayList<String>()
    for (l in a) { val n = bCount[l] ?: 0; if (n > 0) bCount[l] = n - 1 else if (l.isNotBlank()) out += "- " + l.trim().take(120) }
    for (l in b) { val n = aCount[l] ?: 0; if (n > 0) aCount[l] = n - 1 else if (l.isNotBlank()) out += "+ " + l.trim().take(120) }
    return out
}

private val GREEN = Color(0xFF3FA96B)
private val RED = Color(0xFFD9534F)

@Composable
private fun DiffBox(diff: String) {
    Column(Modifier.fillMaxWidth().background(Color(0x22000000), RoundedCornerShape(8.dp)).padding(8.dp)) {
        diff.lines().forEach { l -> Text(l, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (l.startsWith("+")) GREEN else RED) }
    }
}

@Composable
fun ChiefScreen(container: AppContainer, open: (String) -> Unit = {}) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var tick by remember { mutableIntStateOf(0) }
    val runnerTick by ChiefRunner.tick.collectAsState()
    val running by ChiefRunner.running.collectAsState()
    val status by ChiefRunner.status.collectAsState()
    val costNow by ChiefRunner.costNow.collectAsState()
    var view by remember { mutableStateOf("chat") }
    var showSetup by remember { mutableStateOf(ChiefStore.githubToken(c).isBlank()) }
    var claudeKey by remember { mutableStateOf("") }
    var ghToken by remember { mutableStateOf("") }
    var capText by remember { mutableStateOf("%.0f".format(ChiefStore.cap(c))) }
    var input by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var more by remember { mutableStateOf(false) }
    var reviewOpen by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(-1) }
    var elapsed by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf<Set<Int>>(emptySet()) }
    val attachText = remember { mutableStateListOf<Pair<String, String>>() }
    val attachImages = remember { mutableStateListOf<String>() }
    val steps = ChiefRunner.log
    val blocks = remember(steps.size, running) { toBlocks(steps.toList()) }
    val listState = rememberLazyListState()
    val month = remember(costNow, tick, running) { ChiefStore.spent(c) }
    val phase = remember(runnerTick, tick) { ChiefStore.phase(c) }
    val pendingSummary = remember(runnerTick, tick, running) { ChiefStore.pendingSummary(c) }
    val pendingFiles = remember(runnerTick, tick, running) { ChiefStore.pendingFiles(c) }
    val canContinue = remember(running, tick, runnerTick) { !running && ChiefStore.hasJob(c) }
    val fmt = remember { DateTimeFormatter.ofPattern("d MMM, h:mm a") }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch { encodeImage(c, uri)?.let { attachImages += it } }
    }
    val speak = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { input = (input + " " + it).trim() }
    }

    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { ChiefRunner.resume(c) } }
    LaunchedEffect(running) { com.nizam.app.ui.design.guard { elapsed = 0; while (running) { delay(1000); elapsed++ } } }
    LaunchedEffect(blocks.size, running) { com.nizam.app.ui.design.guard { if (blocks.isNotEmpty()) listState.animateScrollToItem(blocks.size + 1) } }

    fun send() {
        if (ChiefStore.githubToken(c).isBlank()) { showSetup = true; ChiefRunner.log += Step.Note("Add your GitHub token in Setup first, so I can save changes and build them."); return }
        val text = input.trim()
        if (text.isBlank() && attachImages.isEmpty() && attachText.isEmpty()) return
        val full = buildString {
            append(text)
            attachText.forEach { (label, body) -> append("\n\n--- ").append(label).append(" ---\n").append(body) }
        }
        val imgs = attachImages.toList()
        input = ""; attachText.clear(); attachImages.clear()
        ChiefRunner.startJob(c, full, imgs)
    }

    fun command(cmd: String): Boolean {
        when (cmd.trim().lowercase().substringBefore(' ')) {
            "/new", "/clear" -> ChiefRunner.newChat(c)
            "/stop" -> if (running) ChiefRunner.stop()
            "/history" -> view = "history"
            "/setup" -> showSetup = true
            "/undo" -> { ChiefStore.clearPending(c); tick++; ChiefRunner.log += Step.Note("Discarded the waiting change.") }
            "/cost" -> ChiefRunner.log += Step.Note("This month: $" + "%.2f".format(ChiefStore.spent(c)) + " of $" + "%.0f".format(ChiefStore.cap(c)) + ". One job is capped at $" + "%.2f".format(ChiefStore.jobCap(c)) + ".")
            "/model" -> { ChiefStore.setSmart(c, !ChiefStore.smart(c)); tick++; ChiefRunner.log += Step.Note("Brain: " + if (ChiefStore.smart(c)) "stronger (Sonnet)" else "lighter (Haiku)") }
            "/status" -> ChiefRunner.log += Step.Note("Build: $phase" + (ChiefStore.detail(c).takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""))
            "/help" -> ChiefRunner.log += Step.Note("Commands: /new  /history  /stop  /undo  /cost  /model  /status  /setup  /help")
            else -> return false
        }
        input = ""
        return true
    }

    Column(Modifier.fillMaxSize().imePadding().padding(horizontal = 14.dp)) {
        // ---- header ----
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            ClaudeBadge(38.dp, thinking = running)
            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                Text("Chief", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                val brain = if (ChiefStore.claudeKey(c).isBlank()) "Your connected AIs" else if (ChiefStore.smart(c)) "Claude Sonnet" else "Claude Haiku"
                Text(brain, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { command("/model") })
            }
            TextButton(onClick = { showSetup = !showSetup }) { Text("$" + "%.2f".format(month) + " / $" + "%.0f".format(ChiefStore.cap(c)), style = MaterialTheme.typography.labelMedium) }
            Box {
                TextButton(onClick = { more = true }) { Text("⋯", fontSize = 20.sp) }
                DropdownMenu(more, { more = false }) {
                    DropdownMenuItem(text = { Text("New chat") }, onClick = { more = false; command("/new") })
                    DropdownMenuItem(text = { Text("Past chats") }, onClick = { more = false; view = "history" })
                    DropdownMenuItem(text = { Text("Past builds") }, onClick = { more = false; view = "builds" })
                    DropdownMenuItem(text = { Text("Setup and limits") }, onClick = { more = false; showSetup = true })
                    DropdownMenuItem(text = { Text("Commands (/help)") }, onClick = { more = false; command("/help") })
                }
            }
        }

        if (view == "history") {
            val list = remember(tick, runnerTick, view) { ChiefRunner.sessionList(c) }
            TextButton(onClick = { view = "chat" }) { Text("← Back to chat") }
            if (list.isEmpty()) Text("No past chats yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            LazyColumn(Modifier.weight(1f)) {
                items(list.size) { i ->
                    val s = list[i]
                    Row(Modifier.fillMaxWidth().clickable { ChiefRunner.openSession(c, s.optString("id")); view = "chat" }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.optString("title"), fontWeight = FontWeight.Medium, maxLines = 2)
                            Text(Instant.ofEpochMilli(s.optLong("t")).atZone(ZoneId.systemDefault()).format(fmt), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { ChiefRunner.deleteSession(c, s.optString("id")); tick++ }) { Text("Delete") }
                    }
                }
            }
            return@Column
        }

        if (view == "builds") {
            var releases by remember { mutableStateOf<List<org.json.JSONObject>?>(null) }
            var installingTag by remember { mutableStateOf<String?>(null) }
            var rollProgress by remember { mutableStateOf<Int?>(null) }
            LaunchedEffect(view) { com.nizam.app.ui.design.guard { releases = runCatching { GitHubApi(ChiefStore.githubToken(c)).recentReleases() }.getOrNull() } }
            TextButton(onClick = { view = "chat" }) { Text("← Back to chat") }
            Text("Any past build still works — pick one to go back to it. This never changes the code, only which build is installed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
            when {
                releases == null -> Text("Loading…")
                releases!!.isEmpty() -> Text("No past builds found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> LazyColumn(Modifier.weight(1f)) {
                    items(releases!!.size) { i ->
                        val r = releases!![i]
                        val tag = r.optString("tag_name")
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tag, fontWeight = FontWeight.Medium)
                                Text(runCatching { Instant.parse(r.optString("published_at")).atZone(ZoneId.systemDefault()).format(fmt) }.getOrDefault(""),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (installingTag == tag && rollProgress != null) Text("${rollProgress}%")
                            else TextButton(onClick = {
                                installingTag = tag
                                scope.launch {
                                    val info = ChiefRunner.updateInfo(c, tag)
                                    if (info == null) { ChiefRunner.log += Step.Note("Could not find $tag."); installingTag = null; return@launch }
                                    runCatching { rollProgress = 0; val f = Updater.download(c, info) { rollProgress = it }; Updater.install(c, f) }
                                        .onFailure { ChiefRunner.log += Step.Note("Download failed: ${it.message}") }
                                    rollProgress = null; installingTag = null
                                }
                            }) { Text("Install") }
                        }
                    }
                }
            }
            return@Column
        }

        // ---- setup ----
        if (showSetup) Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp)).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Setup", fontWeight = FontWeight.SemiBold)
            Text("A Claude Pro or Max subscription does not include API credit: the API is billed separately (console.anthropic.com, Billing). Or leave the key empty and Chief uses the other AIs you have connected in Atlas. They are weaker at code, so you review changes carefully.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(claudeKey, { claudeKey = it }, label = { Text(if (ChiefStore.claudeKey(c).isBlank()) "Anthropic API key (optional)" else "Anthropic key saved (type to replace)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(ghToken, { ghToken = it }, label = { Text(if (ChiefStore.githubToken(c).isBlank()) "GitHub token" else "GitHub token saved (type to replace)") },
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Text("Make the GitHub token at github.com/settings/personal-access-tokens: only the nizam repo, with Contents (read and write) and Actions (read).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(capText, { capText = it.filter { ch -> ch.isDigit() || ch == '.' } }, label = { Text("Monthly spending limit on Claude (US dollars)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Stronger Claude model (more accurate, costs more)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                var smart by remember { mutableStateOf(ChiefStore.smart(c)) }
                Switch(smart, { smart = it; ChiefStore.setSmart(c, it) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Build automatically when my own checks pass (otherwise I ask first)", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                var auto by remember { mutableStateOf(ChiefStore.autoBuild(c)) }
                Switch(auto, { auto = it; ChiefStore.setAutoBuild(c, it) })
            }
            Button(onClick = {
                if (claudeKey.isNotBlank()) ChiefStore.setClaudeKey(c, claudeKey.trim())
                if (ghToken.isNotBlank()) ChiefStore.setGithubToken(c, ghToken.trim())
                capText.toDoubleOrNull()?.let { ChiefStore.setCap(c, it) }
                claudeKey = ""; ghToken = ""; showSetup = false; tick++
            }) { Text("Save") }
        }

        // ---- transcript ----
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (blocks.isEmpty()) item {
                Column(Modifier.fillMaxWidth().padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    ClaudeBadge(84.dp, thinking = false)
                    Text("What should we change?", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 14.dp))
                    Text("I read the code, make the change, and build you an update.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    listOf("Check the app and fix any problems", "Make the greeting on Today bigger", "Add a tracker for my water intake", "Why does the app feel slow?").forEach { s ->
                        TextButton(onClick = {
                            if (s.startsWith("Check")) scope.launch {
                                val findings = runCatching { com.nizam.app.feature.nx.SelfCheck.run(container) }.getOrDefault(emptyList())
                                attachText += "Self-check" to findings.joinToString("\n") { "[${it.level}] ${it.area}: ${it.text}" }
                                input = s
                            } else input = s
                        }) { Text(s) }
                    }
                }
            }
            items(blocks.size) { i ->
                when (val b = blocks[i]) {
                    is Block.Me -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Box(Modifier.widthIn(max = 300.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)).padding(horizontal = 12.dp, vertical = 8.dp)) {
                            Text(b.text.take(1500))
                        }
                    }
                    is Block.Claude -> Row(Modifier.fillMaxWidth()) {
                        ClaudeBadge(26.dp, modifier = Modifier.padding(top = 2.dp, end = 8.dp))
                        Column(Modifier.weight(1f)) {
                            Md(b.text)
                            Text("Copy", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.clickable { (c.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Chief", b.text)) }.padding(vertical = 4.dp))
                        }
                    }
                    is Block.Note -> Text(b.text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 34.dp))
                    is Block.Work -> Column(Modifier.fillMaxWidth().padding(start = 34.dp).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp)).padding(8.dp)) {
                        val open = b.key in expanded
                        val edits = b.steps.count { it.name == "edit_file" || it.name == "create_file" }
                        Row(Modifier.fillMaxWidth().clickable { expanded = if (open) expanded - b.key else expanded + b.key }, verticalAlignment = Alignment.CenterVertically) {
                            Text("●", color = GREEN, fontSize = 10.sp)
                            Text("  ${b.steps.size} step${if (b.steps.size == 1) "" else "s"}" + if (edits > 0) " · $edits edit${if (edits == 1) "" else "s"}" else " · looked around",
                                Modifier.weight(1f), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                            Text(if (open) "▾" else "▸", fontSize = 12.sp)
                        }
                        // edits are always visible, like a diff in a code review
                        b.steps.filter { it.diff.isNotBlank() }.forEach { st ->
                            Text(st.detail.substringAfterLast('/'), fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp))
                            DiffBox(st.diff)
                        }
                        if (open) b.steps.forEach { st ->
                            Text("${verb(st.name)}  ${st.detail.take(70)}", fontFamily = FontFamily.Monospace, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                            Text(st.result.take(700), fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (running) item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    ClaudeBadge(26.dp, modifier = Modifier.padding(end = 8.dp), thinking = true)
                    Column {
                        Text(status.ifBlank { "Thinking" } + "…", style = MaterialTheme.typography.bodyMedium)
                        Text("${elapsed}s · tap ■ to stop", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (canContinue) item {
                TextButton(onClick = { ChiefRunner.resumeJob(c); tick++ }) { Text("Continue the unfinished job") }
            }

            if (pendingFiles.isNotEmpty()) item {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f), RoundedCornerShape(14.dp)).padding(12.dp)) {
                    Text("Ready for your OK", fontWeight = FontWeight.SemiBold)
                    Text(pendingSummary.orEmpty(), style = MaterialTheme.typography.bodySmall)
                    pendingFiles.forEach { f ->
                        val d = remember(f.path) { diffLines(f.original, f.content) }
                        Text("${f.path.substringAfterLast('/')}  +${d.count { it.startsWith("+") }} −${d.count { it.startsWith("-") }}", fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
                        if (reviewOpen) d.take(30).forEach { Text(it, fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (it.startsWith("+")) GREEN else RED) }
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
                        "building" -> { Text("Building your update. About ten minutes. You can leave this screen; I will tell you when it is ready.")
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

        // ---- composer ----
        if (input.startsWith("/")) Row(Modifier.fillMaxWidth()) {
            listOf("/new", "/history", "/cost", "/model", "/undo", "/status", "/help").filter { it.startsWith(input.trim().lowercase()) }.take(4).forEach { cmd ->
                TextButton(onClick = { command(cmd) }) { Text(cmd, fontFamily = FontFamily.Monospace) }
            }
        }
        if (attachText.isNotEmpty() || attachImages.isNotEmpty()) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            attachText.toList().forEach { (label, _) -> TextButton(onClick = { attachText.removeAll { it.first == label } }) { Text("📎 $label ✕", fontSize = 12.sp) } }
            if (attachImages.isNotEmpty()) TextButton(onClick = { attachImages.clear() }) { Text("🖼 ${attachImages.size} image${if (attachImages.size == 1) "" else "s"} ✕", fontSize = 12.sp) }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.Add, "Attach") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Self-check report") }, onClick = { menu = false; scope.launch {
                        val f = runCatching { com.nizam.app.feature.nx.SelfCheck.run(container) }.getOrDefault(emptyList())
                        attachText += "Self-check" to f.joinToString("\n") { "[${it.level}] ${it.area}: ${it.text}" } } })
                    DropdownMenuItem(text = { Text("Recent crash log") }, onClick = { menu = false; attachText += "Crash log" to com.nizam.app.feature.safe.CrashGuard.report(c).take(2500) })
                    DropdownMenuItem(text = { Text("What Atlas noticed") }, onClick = { menu = false; attachText += "Atlas notices" to
                        (runCatching { com.nizam.app.feature.nx.Noticer.scan(c).joinToString("\n") { "${it.title}: ${it.detail}" } }.getOrDefault("").ifBlank { "Nothing right now." }) })
                    DropdownMenuItem(text = { Text("Screenshot or photo") }, onClick = { menu = false; pickImage.launch("image/*") })
                }
            }
            OutlinedTextField(input, { input = it }, placeholder = { Text("Message Chief…  (type / for commands)") }, modifier = Modifier.weight(1f),
                maxLines = 5, shape = RoundedCornerShape(22.dp))
            IconButton(onClick = {
                runCatching { speak.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) }
            }) { Icon(Icons.Outlined.Mic, "Speak") }
            IconButton(onClick = {
                if (running) ChiefRunner.stop()
                else if (input.startsWith("/") && command(input)) { }
                else send()
            }, modifier = Modifier.background(MaterialTheme.colorScheme.primary, CircleShape)) {
                Icon(if (running) Icons.Outlined.Stop else Icons.Outlined.ArrowUpward, if (running) "Stop" else "Send", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

object ChiefSpecs {
    val all: List<Spec> = listOf(
        ScreenSpec("chief", "Chief", "Talk to Claude to add, fix or change anything in this app", Icons.Outlined.Handyman, Hub.Tools,
            "claude chief engineer build update fix change add feature developer code assistant github") { container, open -> ChiefScreen(container, open) },
    )
}
