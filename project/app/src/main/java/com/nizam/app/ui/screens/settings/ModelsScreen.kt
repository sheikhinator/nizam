package com.nizam.app.ui.screens.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.net.HubFile
import com.nizam.app.net.HubModel
import com.nizam.app.net.LocalModel
import com.nizam.app.net.ModelHub
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.components.Thinking
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.launch

/** Offline models: what's on the phone, what's downloading, and where to get more. */
@Composable
fun ModelsScreen() {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var refresh by remember { mutableIntStateOf(0) }
    val models = remember(refresh) { LocalModel.models(c) }
    val active = remember(refresh) { LocalModel.file(c)?.name }
    val dl by ModelHub.download.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Offline models", "AI that runs on this phone — no internet, nothing leaves the device")

        // ── Download in progress: always first, so you can see it's working ──
        dl?.let { d -> DownloadCard(d) { refresh++ } }

        // ── Speed: how the model on this phone is running, and a one-tap test ──
        if (models.isNotEmpty()) {
            var run by remember { mutableStateOf(LocalModel.lastRun(c)) }
            var testing by remember { mutableStateOf(false) }
            var sample by remember { mutableStateOf("") }
            Label("Speed")
            Group {
                Text(if (run.isBlank()) "Not measured yet" else "Last answer: $run", style = MaterialTheme.typography.titleMedium)
                Text("Online AI is used whenever you're connected — it's faster and smarter. The offline model is the backup for no signal. " +
                    "Speed comes from model size and the GPU, not RAM: a 4-bit 1B model on the GPU gives the quickest replies.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
                if (sample.isNotBlank()) Text(sample, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 6.dp))
                Button(enabled = !testing, onClick = {
                    testing = true; sample = ""
                    scope.launch {
                        runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                            LocalModel.generate(c, "In two sentences, what makes a good morning routine?", null, 120) { sample += it } } }
                            .onFailure { Feedback.failed("Testing the model", it) }
                        run = LocalModel.lastRun(c); testing = false
                    }
                }) { Text(if (testing) "Testing…" else "Test speed") }
            }
        }

        // ── Recommended for this phone ──
        Label(if (models.isEmpty()) "Recommended for this phone" else "Faster models")
        val picks = remember { listOf(
            ModelHub.Pick("litert-community/Gemma3-1B-IT", "Gemma 3 · 1B · 4-bit — fastest", "Google's phone model on the GPU. ~550 MB. Needs your Hugging Face token (Settings › API keys) and one tap on “Agree” on its Hugging Face page."),
            ModelHub.Pick("litert-community/Qwen2.5-1.5B-Instruct", "Qwen 2.5 · 1.5B", "Smarter, a little slower. ~1.5 GB, no account needed."),
            ModelHub.Pick("litert-community/Qwen2.5-0.5B-Instruct", "Qwen 2.5 · 0.5B — quickest without an account", "Very fast, simpler answers. ~500 MB."),
        ) }
        picks.forEach { pick ->
            Group(Modifier.padding(vertical = 4.dp)) {
                Text(pick.label, style = MaterialTheme.typography.titleMedium)
                Text(pick.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !ModelHub.busy, onClick = { ModelHub.install(c, pick); Feedback.show("Downloading ${pick.label} — the notification shows progress") }) { Text("Download and use") }
                    if ("Gemma" in pick.repo) OutlinedButton(onClick = { runCatching { c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse("https://huggingface.co/${pick.repo}")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Agree on Hugging Face") }
                }
            }
        }

        // ── On this phone ──
        Label("On this phone")
        if (models.isEmpty()) Text("None yet. Download one below — a 1B-parameter model (about 500–700 MB) runs well on most phones.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Group(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
            models.forEachIndexed { i, f ->
                Row(Modifier.fillMaxWidth().clickable(role = Role.RadioButton) { LocalModel.setActive(c, f.name); refresh++ },
                    verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(f.name == active, { LocalModel.setActive(c, f.name); refresh++ })
                    Column(Modifier.weight(1f)) {
                        Text(f.name, style = MaterialTheme.typography.bodyLarge)
                        Text("${f.length() / 1_048_576} MB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("Delete", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable(role = Role.Button) { LocalModel.delete(c, f.name); refresh++ }.padding(12.dp))
                }
                if (i < models.lastIndex) HorizontalDivider(Modifier.padding(start = 48.dp), thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        // ── Browse Hugging Face ──
        Label("Get models from Hugging Face")
        var where by remember { mutableStateOf("Phone-ready") }
        var query by remember { mutableStateOf("") }
        var results by remember { mutableStateOf<List<HubModel>?>(null) }
        var searching by remember { mutableStateOf(false) }
        var openId by remember { mutableStateOf<String?>(null) }
        var files by remember { mutableStateOf<List<HubFile>?>(null) }
        ChipRow(listOf("Phone-ready", "All MediaPipe"), where, { where = it; results = null })
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(query, { query = it }, singleLine = true, modifier = Modifier.weight(1f),
                placeholder = { Text("gemma, qwen, phi, deepseek…") })
            Spacer(Modifier.width(8.dp))
            Button(enabled = !searching, onClick = {
                searching = true; openId = null; files = null
                scope.launch {
                    results = runCatching { ModelHub.search(c, query, where == "Phone-ready") }
                        .onFailure { Feedback.failed("Searching Hugging Face", it) }.getOrElse { emptyList() }
                    searching = false
                }
            }) { Text("Search") }
        }
        if (searching) Thinking(label = "Searching Hugging Face")
        results?.filter { !it.gated || ModelHub.token(c).isNotBlank() }?.let { list ->
            if (list.isEmpty()) Text("Nothing found. Try another word, or switch to All MediaPipe.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            list.forEach { m ->
                Column(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                    if (openId == m.id) { openId = null; files = null } else {
                        openId = m.id; files = null
                        scope.launch { files = runCatching { ModelHub.files(c, m.id) }
                            .onFailure { Feedback.failed("Reading ${m.id}", it) }.getOrElse { emptyList() } }
                    }
                }.padding(vertical = 10.dp)) {
                    Text(m.id.substringAfter('/'), style = MaterialTheme.typography.bodyLarge)
                    Text(m.id.substringBefore('/') + " · ${m.downloads} downloads" + (if (m.gated) " · licence required" else ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (openId == m.id) {
                        val fs = files
                        when {
                            fs == null -> Thinking(label = "Listing files")
                            fs.isEmpty() -> Text("No .task or .bin files here that this runtime can load.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                            else -> fs.forEach { f ->
                                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${f.path.substringAfterLast('/')}  ·  ${f.sizeMb} MB", style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.weight(1f))
                                    OutlinedButton(enabled = !ModelHub.busy, onClick = {
                                        ModelHub.start(c, ModelHub.urlFor(m.id, f.path), f.path.substringAfterLast('/'))
                                        Feedback.show("Downloading in the background — you can leave this screen")
                                    }) { Text("Download") }
                                }
                            }
                        }
                        if (m.gated) Text("Gated: open huggingface.co/${m.id}, accept the licence, then add your token below.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp))
                    }
                }
                HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant)
            }
        }

        // ── Any other source ──
        Label("From any link")
        var link by remember { mutableStateOf("") }
        OutlinedTextField(link, { link = it.trim() }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("https://…/model.task") })
        Button(enabled = link.startsWith("http") && !ModelHub.busy, modifier = Modifier.padding(top = 6.dp), onClick = {
            ModelHub.start(c, link); Feedback.show("Downloading in the background")
        }) { Text("Download") }

        Label("Hugging Face token (for gated models)")
        var token by remember { mutableStateOf(ModelHub.token(c)) }
        OutlinedTextField(token, { token = it; ModelHub.setToken(c, it) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(), placeholder = { Text("hf_…") })
        Text("A free read token from huggingface.co → Settings → Access Tokens. Stored only on this phone.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun DownloadCard(d: com.nizam.app.net.Download, onDone: () -> Unit) {
    Label("Download")
    Group {
        Text(d.name, style = MaterialTheme.typography.titleSmall)
        when (d.state) {
            "downloading", "starting" -> {
                if (d.total > 0) LinearProgressIndicator(progress = { d.percent / 100f }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant)
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), trackColor = MaterialTheme.colorScheme.surfaceVariant)
                Row {
                    val left = if (d.speed > 0 && d.total > d.done) (d.total - d.done) / d.speed else -1
                    Text(if (d.total <= 0) "Connecting…" else "${d.done / 1_048_576} of ${d.total / 1_048_576} MB · ${d.percent}%" +
                        (if (d.speed > 0) " · %.1f MB/s".format(d.speed / 1_048_576.0) else "") +
                        (if (left > 0) " · ${if (left >= 60) "${left / 60} min" else "$left s"} left" else ""),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text("Cancel", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable(role = Role.Button) { ModelHub.cancel() })
                }
            }
            "done" -> Text("Installed and active — Atlas answers offline now.", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary)
            "failed" -> Text(d.error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            else -> Text("Cancelled — download again to resume where it stopped.", style = MaterialTheme.typography.bodyMedium)
        }
    }
    androidx.compose.runtime.LaunchedEffect(d.state) { if (d.state == "done") onDone() }
}
