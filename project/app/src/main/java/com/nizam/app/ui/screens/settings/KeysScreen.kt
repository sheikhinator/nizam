package com.nizam.app.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import com.nizam.app.net.ApiKeys
import com.nizam.app.net.Providers
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.launch

/** Every API key in one list: see which are set, switch any on or off, change one, with a clear "saved". */
@Composable
fun KeysScreen(container: AppContainer) {
    val c = LocalContext.current
    val s = container.settingsRepository
    val scope = rememberWorkScope()
    var refresh by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("API keys", "Switch any key off to stop Atlas using it — it's kept, not deleted")
        ClipboardKey(container) { refresh++ }
        var paste by remember { mutableStateOf("") }
        Group(Modifier.padding(vertical = 5.dp)) {
            Text("Paste all your keys at once", style = MaterialTheme.typography.titleSmall)
            Text("Gemini, OpenRouter, Groq, Hugging Face, Hadith — in any order. Atlas recognises each one and sets itself up for speed.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(paste, { paste = it }, modifier = Modifier.fillMaxWidth(), minLines = 3, placeholder = { Text("Paste here") })
            Button(enabled = paste.isNotBlank(), onClick = { scope.launch {
                val got = ApiKeys.importAll(c, s, paste); paste = ""; refresh++
                Feedback.show(if (got.isEmpty()) "No keys recognised in that text" else "Set up: ${got.joinToString()} ✓")
            } }) { Text("Import keys") }
        }
        Text("Tip: put several keys in one slot, separated by commas — Atlas rotates through them, and when a key or model " +
            "hits its limit it moves to the next one on its own.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
        EngineStatus(container, refresh)
        var results by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
        var testing by remember { mutableStateOf(false) }
        Button(enabled = !testing, onClick = { testing = true; results = emptyMap(); scope.launch {
            ApiKeys.SLOTS.forEach { sl -> results = results + (sl.id to "Testing…"); results = results + (sl.id to ApiKeys.test(c, s, sl)) }
            testing = false
            Feedback.show("Tested ${results.count { it.value.startsWith("Works") }} working · ${results.count { it.value.startsWith("Failed") }} failing")
        } }) { Text(if (testing) "Testing…" else "Test all keys") }
        ApiKeys.SLOTS.forEach { slot ->
            var value by remember(refresh) { mutableStateOf("") }
            var on by remember(refresh) { mutableStateOf(ApiKeys.enabled(c, slot.id)) }
            var editing by remember { mutableStateOf(false) }
            var draft by remember { mutableStateOf("") }
            LaunchedEffect(refresh) { value = ApiKeys.value(c, s, slot) }
            Group(Modifier.padding(vertical = 5.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(role = Role.Button) { editing = !editing; draft = value }) {
                        Text(slot.label, style = MaterialTheme.typography.titleSmall)
                        Text(slot.what, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(when {
                            value.isBlank() -> "Not set — tap to add"
                            !on -> "Off · ${ApiKeys.mask(value)}"
                            else -> "On · ${ApiKeys.mask(value)}" + if (value == slot.builtIn) " · built in" else ""
                        }, style = MaterialTheme.typography.labelMedium,
                            color = if (on && value.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp))
                    }
                    Switch(checked = on && value.isNotBlank(), enabled = value.isNotBlank(), onCheckedChange = { v ->
                        on = v
                        scope.launch { ApiKeys.setEnabled(c, s, slot, v); Feedback.show("${slot.label} ${if (v) "on" else "off"}"); refresh++ }
                    })
                }
                results[slot.id]?.let { r -> Text(r, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
                    color = if (r.startsWith("Works")) androidx.compose.ui.graphics.Color(0xFF3DAA6D) else if (r.startsWith("Failed")) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant) }
                AnimatedVisibility(editing) {
                    Column(Modifier.padding(top = 8.dp)) {
                        OutlinedTextField(draft, { draft = it.trim() }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                            placeholder = { Text("Paste the key — or several, comma-separated") }, modifier = Modifier.fillMaxWidth())
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = { scope.launch {
                                ApiKeys.set(c, s, slot, draft); editing = false; refresh++
                                Feedback.show(if (draft.isBlank()) "${slot.label} key removed" else "${slot.label} key saved ✓")
                            } }) { Text("Save") }
                            if (slot.builtIn.isNotBlank()) com.nizam.app.ui.design.OutlinedButton(onClick = { draft = slot.builtIn }) { Text("Use built-in") }
                        }
                    }
                }
            }
        }
        com.nizam.app.ui.design.Label("More free AI — Atlas falls back on these", Modifier.padding(top = 18.dp))
        Text("Stacked free tiers mean there's nearly always one with room. Only a model you run yourself (offline model, Ollama) is truly unlimited.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Providers.PRESETS.forEach { pr -> ProviderRow(pr) }
        Text("Built-in keys come from the build's secrets (GitHub → Settings → Secrets → Actions, named ATLAS_KEY_GEMINI, " +
            "ATLAS_KEY_OPENROUTER, ATLAS_KEY_TMDB …), so they're in every new build without being written into the code.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 14.dp))
        Spacer(Modifier.height(80.dp))
    }
}

@Composable
private fun ProviderRow(pr: com.nizam.app.net.Providers.Preset) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var on by remember { mutableStateOf(com.nizam.app.net.Providers.on(c, pr)) }
    var open by remember { mutableStateOf(false) }
    var key by remember { mutableStateOf(com.nizam.app.net.Providers.key(c, pr)) }
    var model by remember { mutableStateOf(com.nizam.app.net.Providers.model(c, pr)) }
    var url by remember { mutableStateOf(com.nizam.app.net.Providers.url(c, pr)) }
    var result by remember { mutableStateOf<String?>(null) }
    Group(Modifier.padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(role = Role.Button) { open = !open }) {
                Text(pr.name, style = MaterialTheme.typography.titleSmall)
                Text(pr.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(when { pr.needsKey && key.isBlank() -> "Needs a free key — tap"; on -> "On · ${model.ifBlank { "auto model" }}"; else -> "Off" },
                    style = MaterialTheme.typography.labelMedium, color = if (on && (!pr.needsKey || key.isNotBlank())) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }
            Switch(on, { on = it; com.nizam.app.net.Providers.set(c, pr, on = it) })
        }
        result?.let { r -> Text(r, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
            color = if (r.startsWith("Works")) androidx.compose.ui.graphics.Color(0xFF3DAA6D) else MaterialTheme.colorScheme.error) }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 8.dp)) {
                if (pr.needsKey) OutlinedTextField(key, { key = it.trim() }, singleLine = true, visualTransformation = PasswordVisualTransformation(),
                    placeholder = { Text("API key") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(model, { model = it.trim() }, singleLine = true, placeholder = { Text("Model (blank = automatic)") }, modifier = Modifier.fillMaxWidth())
                if (pr.id == "ollama") OutlinedTextField(url, { url = it.trim() }, singleLine = true, placeholder = { Text("http://PC-IP:11434/v1/chat/completions") }, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { com.nizam.app.net.Providers.set(c, pr, on = true, key = key, model = model, url = url); on = true
                        result = "Testing…"; scope.launch { result = com.nizam.app.net.Providers.test(c, pr); model = com.nizam.app.net.Providers.model(c, pr) } }) { Text("Save & test") }
                    if (pr.needsKey) com.nizam.app.ui.design.OutlinedButton(onClick = { com.nizam.app.net.Providers.setPending(c, pr.id); runCatching { c.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(pr.keyPage)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } }) { Text("Get a free key") }
                }
            }
        }
    }
}

/** Live view of the AI engine: every key × model Atlas can use, which are ready, which are resting and why. */
@Composable
private fun EngineStatus(container: AppContainer, refresh: Int) {
    val c = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(refresh, tick, open) {
        if (open) lines = com.nizam.app.net.Health.report(com.nizam.app.net.Router.endpoints(container.settingsRepository, c))
    }
    Group(Modifier.padding(vertical = 5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(role = Role.Button) { open = !open }) {
            Column(Modifier.weight(1f)) {
                Text("AI engine", style = MaterialTheme.typography.titleSmall)
                Text("Background AI today: ${com.nizam.app.net.Budget.usedToday()} of ${com.nizam.app.net.Budget.LIMIT} · " +
                    "repeat questions answered from memory · ${if (open) "tap to hide" else "tap to see every source"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        AnimatedVisibility(open) {
            Column(Modifier.padding(top = 8.dp)) {
                if (lines.isEmpty()) Text("No AI sources yet — add a key below.", style = MaterialTheme.typography.bodySmall)
                lines.forEach { l ->
                    Text(l, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp),
                        color = if ("ready" in l) androidx.compose.ui.graphics.Color(0xFF3DAA6D) else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                    com.nizam.app.ui.design.OutlinedButton(onClick = { tick++ }) { Text("Refresh") }
                    com.nizam.app.ui.design.OutlinedButton(onClick = {
                        com.nizam.app.net.Health.clear(); com.nizam.app.net.AnswerCache.clear(); tick++
                        Feedback.show("Every key and model is ready to try again")
                    }) { Text("Retry all now") }
                }
            }
        }
    }
}

/**
 * The "automatic" part of getting a key: tap Get a free key, sign in on the provider's page, copy the key —
 * when you come back, Atlas spots it on the clipboard, knows which provider it's for, saves it, switches it on
 * and tests it. (No provider lets an app create an account for you, so the sign-in itself stays yours.)
 */
@Composable
private fun ClipboardKey(container: AppContainer, onSaved: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var found by remember { mutableStateOf<Pair<String, String>?>(null) }       // key to label
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) scope.launch {
                kotlinx.coroutines.delay(500)                                   // the window needs focus before Android lets us read the clipboard
                found = runCatching { detect(c) }.getOrNull()
            }
        }
        lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
    }
    val f = found ?: return
    Group(Modifier.padding(vertical = 5.dp)) {
        Text("Key found on your clipboard", style = MaterialTheme.typography.titleSmall)
        Text("${ApiKeys.mask(f.first)} — looks like a ${f.second} key.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { val (k, _) = f; found = null; scope.launch {
                c.getSharedPreferences("providers", 0).edit().putString("_last_clip", k.hashCode().toString()).apply()
                val got = ApiKeys.importAll(c, container.settingsRepository, k)
                val pr = if (got.isEmpty()) com.nizam.app.net.Providers.PRESETS.firstOrNull { it.id == com.nizam.app.net.Providers.pending(c) } else null
                if (pr != null) {
                    com.nizam.app.net.Providers.set(c, pr, on = true, key = (com.nizam.app.net.Router.keys(com.nizam.app.net.Providers.key(c, pr)) + k).distinct().joinToString(","))
                    Feedback.show("${pr.name} key saved — testing…")
                    Feedback.show(com.nizam.app.net.Providers.test(c, pr))
                } else Feedback.show(if (got.isEmpty()) "Couldn't tell which service that key is for — paste it in its row below" else "Saved: ${got.joinToString()} ✓")
                onSaved()
            } }) { Text("Use it") }
            com.nizam.app.ui.design.OutlinedButton(onClick = {
                c.getSharedPreferences("providers", 0).edit().putString("_last_clip", f.first.hashCode().toString()).apply(); found = null }) { Text("Ignore") }
        }
    }
}

/** A key-shaped string on the clipboard that Atlas hasn't seen, and who it's probably for. */
private fun detect(c: android.content.Context): Pair<String, String>? {
    val cm = c.getSystemService(android.content.ClipboardManager::class.java)
    val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(c)?.toString()?.trim() ?: return null
    if (text.length > 400) return null
    val token = Regex("[A-Za-z0-9_.\\-$/]{20,}").find(text)?.value ?: return null
    if (c.getSharedPreferences("providers", 0).getString("_last_clip", "") == token.hashCode().toString()) return null
    val known = mapOf("AIza" to "Gemini", "AQ." to "Gemini", "sk-or-" to "OpenRouter", "gsk_" to "Groq", "hf_" to "Hugging Face",
        "csk-" to "Cerebras", "nvapi-" to "NVIDIA", "github_pat_" to "GitHub Models")
    known.entries.firstOrNull { token.startsWith(it.key) }?.let { return token to it.value }
    val pending = com.nizam.app.net.Providers.PRESETS.firstOrNull { it.id == com.nizam.app.net.Providers.pending(c) } ?: return null
    return if (token.length in 24..120 && !token.contains("http")) token to pending.name else null
}
