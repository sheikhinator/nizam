package com.nizam.app.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.KeyboardArrowRight
import com.nizam.app.ui.design.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.outlined.FormatSize
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.data.prefs.ThemeMode
import com.nizam.app.feature.backup.Backup
import com.nizam.app.feature.backup.BackupRestorePreview
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.net.Ai
import com.nizam.app.ui.theme.ATLAS_THEMES
import com.nizam.app.ui.components.SectionHeader
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(container: AppContainer, onOpen: (String) -> Unit = {}) {
    val scope = rememberCoroutineScope()
    val faceCode by container.settingsRepository.face.collectAsState(initial = "2-1-0-2-0-0-0")
    val displayName by container.settingsRepository.displayName.collectAsState(initial = "")
    var resetStage by remember { mutableStateOf(0) }
    var resetText by remember { mutableStateOf("") }
    var resetVault by remember { mutableStateOf(false) }
    val settings = container.settingsRepository
    val secretProtectionError by settings.secretProtectionError.collectAsState(initial = null)
    val theme by settings.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
    val currency by settings.currency.collectAsState(initial = "PKR")
    val hadithKey by settings.hadithApiKey.collectAsState(initial = "")
    val geminiKey by settings.geminiApiKey.collectAsState(initial = "")
    val geminiModel by settings.geminiModel.collectAsState(initial = "gemini-flash-lite-latest")

    var hadithDraft by remember(hadithKey) { mutableStateOf(hadithKey) }
    var geminiDraft by remember(geminiKey) { mutableStateOf(geminiKey) }
    val kcalTarget by settings.kcalTarget.collectAsState(initial = 2200)
    val waterTarget by settings.waterTarget.collectAsState(initial = 3000)
    var kcalDraft by remember(kcalTarget) { mutableStateOf(kcalTarget.toString()) }
    var waterDraft by remember(waterTarget) { mutableStateOf(waterTarget.toString()) }
    var targetError by remember { mutableStateOf<String?>(null) }
    val provider by settings.aiProvider.collectAsState(initial = "GEMINI")
    val openRouterKey by settings.openRouterKey.collectAsState(initial = "")
    val openRouterModel by settings.openRouterModel.collectAsState(initial = "openrouter/free")
    val pin by settings.appPin.collectAsState(initial = "")
    var openRouterDraft by remember(openRouterKey) { mutableStateOf(openRouterKey) }
    var pinDraft by remember { mutableStateOf("") }
    val context = LocalContext.current
    var backupBusy by remember { mutableStateOf(false) }
    var backupStatus by remember(context) { mutableStateOf(Backup.consumeRestoreStatus(context)) }
    var hasUndoRestore by remember(context) { mutableStateOf(Backup.canUndoLastRestore(context)) }
    var showUndoRestoreConfirmation by remember { mutableStateOf(false) }
    var showUnencryptedBackupWarning by remember { mutableStateOf(false) }
    var includeVault by remember { mutableStateOf(true) }
    var encryptBackup by remember { mutableStateOf(true) }
    var recoveryPhrase by remember { mutableStateOf("") }
    var recoveryPhraseConfirm by remember { mutableStateOf("") }
    var restorePreview by remember { mutableStateOf<BackupRestorePreview?>(null) }
    var selectedRestorePaths by remember { mutableStateOf<Set<String>>(emptySet()) }

    val notifyPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) scope.launch {
            backupBusy = true
            try {
                backupStatus = runCatching {
                    Backup.export(
                        context,
                        uri,
                        includeVault = includeVault,
                        recoveryPhrase = recoveryPhrase.takeIf { encryptBackup }?.toCharArray()
                    )
                }.getOrElse { "Backup failed: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
            } finally {
                recoveryPhrase = ""
                recoveryPhraseConfirm = ""
                backupBusy = false
            }
        }
    }

    val restorePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) scope.launch {
            backupBusy = true
            val result = runCatching {
                Backup.stageRestore(context, uri, recoveryPhrase.takeIf { it.isNotBlank() }?.toCharArray())
            }
            result.onSuccess { preview ->
                restorePreview = preview
                selectedRestorePaths = preview.files.map { it.path }.toSet()
                backupStatus = "Backup staged. Review its contents before restoring."
            }.onFailure {
                backupStatus = "Restore could not be staged: ${com.nizam.app.ui.components.Feedback.friendly(it)}"
            }
            backupBusy = false
        }
    }
    val customUrl by settings.customUrl.collectAsState(initial = "")
    val customKey by settings.customKey.collectAsState(initial = "")
    val customModel by settings.customModel.collectAsState(initial = "")
    var customUrlDraft by remember(customUrl) { mutableStateOf(customUrl) }
    var customKeyDraft by remember(customKey) { mutableStateOf(customKey) }
    var customModelDraft by remember(customModel) { mutableStateOf(customModel) }
    var availableModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var loadingModels by remember { mutableStateOf(false) }
    var modelError by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
    ) {
        Spacer(Modifier.height(16.dp))
        Text("Settings", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button) { onOpen("profile") }
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                com.nizam.app.feature.profile.FaceAvatar(
                    com.nizam.app.feature.profile.Face.decode(faceCode), 52.dp
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(displayName.ifBlank { "Set up your profile" }, style = MaterialTheme.typography.titleMedium)
                    Text("Avatar, bio and stats — every AI reads this",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Filled.KeyboardArrowRight, contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(20.dp))

        SectionHeader("THEME")
        val currentPalette by settings.accentHex.collectAsState(initial = "")
        ATLAS_THEMES.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { palette ->
                    Surface(
                        shape = MaterialTheme.shapes.medium,
                        color = palette.card,
                        modifier = Modifier
                            .weight(1f)
                            .height(76.dp)
                            .clickable { scope.launch { settings.setAccent(palette.key) } }
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                listOf(palette.primary, palette.accent, palette.inkMuted).forEach { c ->
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = c,
                                        modifier = Modifier.width(18.dp).height(18.dp)
                                    ) { Text("") }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Text(
                                (if (currentPalette == palette.key) "✓ " else "") + palette.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = palette.ink
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        com.nizam.app.ui.design.ListRow("Look & comfort", supporting = "Text size, true black, colours from wallpaper, reduce motion",
            icon = androidx.compose.material.icons.Icons.Outlined.FormatSize) { onOpen(com.nizam.app.ui.nav.Routes.APPEARANCE) }
        // ── Theme studio: any colour, any combination ──
        val customJson by settings.customPalette.collectAsState(initial = "")
        var studioOpen by remember { mutableStateOf(false) }
        Row(
            Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button) { studioOpen = !studioOpen }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text((if (currentPalette == "custom") "✓ " else "") + "Make your own",
                    style = MaterialTheme.typography.titleMedium)
                Text("Any colour for the background, buttons and highlights",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(if (studioOpen) "close" else "open", color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium)
        }
        if (studioOpen) ThemeStudio(settings, currentPalette, customJson)
        if (currentPalette.isNotBlank()) {
            OutlinedButton(onClick = { scope.launch { settings.setAccent(""); com.nizam.app.ui.components.Feedback.show("Saved ✓") } }) {
                Text("Follow system light/dark instead")
            }
        }


        SectionHeader("APPEARANCE")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = theme == mode,
                    onClick = { scope.launch { settings.setThemeMode(mode) } },
                    label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }



        Spacer(Modifier.height(28.dp))

        // ── App icon ──
        Text("APP ICON", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 26.dp))
        Text("Same Atlas mark, your colours. Your launcher may take a few seconds to redraw it.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        com.nizam.app.feature.icon.IconPicker()
        Spacer(Modifier.height(12.dp))


        Spacer(Modifier.height(28.dp))

        SectionHeader("AI PROVIDER")
        secretProtectionError?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "GEMINI" to "Gemini",
                "OPENROUTER" to "OpenRouter",
                "CUSTOM" to "Custom"
            ).forEach { (value, label) ->
                FilterChip(
                    selected = provider == value,
                    onClick = { scope.launch { settings.setProvider(value) } },
                    label = { Text(label) }
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            if (provider == "GEMINI")
                "Gemini's free tier returns 503 under load. A Gemini Pro subscription does not cover " +
                    "API keys — that is billed separately in Google Cloud."
            else if (provider == "OPENROUTER")
                "Free models here are heavily rate-limited. Fine for testing, painful for daily use."
            else
                "Any OpenAI-compatible endpoint. Groq (https://api.groq.com/openai/v1) has a far " +
                    "more generous free tier than OpenRouter. Also works with Cerebras, DeepSeek, " +
                    "Mistral, or Ollama running on your own PC over WiFi.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Either way, every request retries with backoff and falls through to a backup model " +
                "before it gives up.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )


        if (provider == "CUSTOM") {
            // One-tap presets for the free providers worth using, so you aren't hunting base URLs
            Text("PRESETS", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
            val presets = listOf(
                Triple("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
                Triple("Cerebras", "https://api.cerebras.ai/v1", "llama-3.3-70b"),
                Triple("Mistral", "https://api.mistral.ai/v1", "mistral-large-latest"),
                Triple("NVIDIA NIM", "https://integrate.api.nvidia.com/v1", "meta/llama-3.3-70b-instruct"),
                Triple("Hugging Face", "https://router.huggingface.co/v1", "Qwen/Qwen3-8B"),
                Triple("OpenRouter", "https://openrouter.ai/api/v1", "meta-llama/llama-3.3-70b-instruct"),
                Triple("Ollama (your PC)", "http://192.168.1.5:11434/v1", "llama3.1")
            )
            com.nizam.app.core.ChipRow(presets.map { it.first }, "", { name ->
                presets.firstOrNull { it.first == name }?.let { (_, url, model) ->
                    customUrlDraft = url; customModelDraft = model
                    com.nizam.app.ui.components.Feedback.show("$name set — paste your key and save")
                }
            })
            Text("Free tiers differ: Mistral is the largest volume (opt into training), Cerebras the fastest, " +
                "NVIDIA the widest model list, Groq the best all-rounder. Not every model supports tools — " +
                "if actions stop working after a switch, that's usually why.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = customUrlDraft, onValueChange = { customUrlDraft = it },
                label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = customKeyDraft, onValueChange = { customKeyDraft = it },
                label = { Text("API key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = customModelDraft, onValueChange = { customModelDraft = it },
                label = { Text("Model name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                scope.launch {
                    settings.setCustomEndpoint(customUrlDraft, customKeyDraft, customModelDraft)
                }
            }) { Text("Save endpoint") }
            Spacer(Modifier.height(6.dp))
            Text(
                "Groq example — URL: https://api.groq.com/openai/v1   model: llama-3.3-70b-versatile",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(10.dp))
        Text("Model", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Text(
            when (provider) {
                "GEMINI" -> geminiModel
                "CUSTOM" -> customModel.ifBlank { "not set" }
                else -> openRouterModel
            },
            style = com.nizam.app.ui.theme.MonoNumber
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                enabled = !loadingModels,
                onClick = {
                    scope.launch {
                        loadingModels = true
                        modelError = null
                        try {
                            availableModels = Ai.listModels(settings)
                        } catch (e: Ai.MissingKeyException) {
                            modelError = "Save your API key first."
                        } catch (e: Exception) {
                            modelError = e.message
                        }
                        loadingModels = false
                    }
                }
            ) { Text(if (loadingModels) "Loading…" else "Fetch models for my key") }
        }
        modelError?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
        if (availableModels.isEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Defaults to gemini-flash-latest, an alias Google keeps pointed at the current Flash " +
                    "model — so this keeps working when model names change.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            val chosenModel = when (provider) { "GEMINI" -> geminiModel; "CUSTOM" -> customModel; else -> openRouterModel }
            availableModels.take(14).forEach { m ->
                Text(
                    (if (chosenModel == m) "✓  " else "     ") + m,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (chosenModel == m) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            scope.launch {
                                when (provider) {
                                    "GEMINI" -> settings.setGeminiModel(m)
                                    "CUSTOM" -> settings.setCustomEndpoint(customUrl, customKey, m)
                                    else -> settings.setOpenRouterModel(m)
                                }
                            }
                        }
                        .padding(vertical = 8.dp)
                )
            }
        }



        Spacer(Modifier.height(28.dp))

        SectionHeader("PRIVACY & SECURITY")
        Button(onClick = { onOpen(com.nizam.app.ui.nav.Routes.SECURITY) }, modifier = Modifier.padding(bottom = 8.dp)) { Text("Security check, privacy screen, auto-lock") }

        SectionHeader("COMPANION & SKY")
        Button(onClick = { onOpen(com.nizam.app.ui.nav.Routes.COMPANION) }, modifier = Modifier.padding(bottom = 8.dp)) { Text("Customise your companion") }
        var livingSky by remember { mutableStateOf(com.nizam.app.ui.design.AmbienceEngine.on(context)) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Living sky", style = MaterialTheme.typography.titleSmall)
                Text("The backdrop follows the time of day, the weather outside, Ramadan, Eid and Jumu'ah. Takes effect on restart.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(livingSky, { livingSky = it; com.nizam.app.ui.design.AmbienceEngine.setOn(context, it) })
        }

        SectionHeader("API KEYS")
        Button(onClick = { onOpen(com.nizam.app.ui.nav.Routes.KEYS) }, modifier = Modifier.padding(bottom = 8.dp)) { Text("All keys — switch each on or off") }
        Text(
            "Keys are stored on this device only and are never sent anywhere except the service they belong to.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = hadithDraft,
                onValueChange = { hadithDraft = it },
                label = { Text("Hadith API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch { settings.setHadithApiKey(hadithDraft); com.nizam.app.ui.components.Feedback.show("Saved ✓") } }) { Text("Save") }
        }
        Text(
            "Get a free key at hadithapi.com. Without it, the Hadith module shows nothing rather than unsourced text.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = geminiDraft,
                onValueChange = { geminiDraft = it },
                label = { Text("Gemini API key (optional)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch { settings.setGeminiApiKey(geminiDraft); com.nizam.app.ui.components.Feedback.show("Saved ✓") } }) { Text("Save") }
        }
        Text(
            "Powers courses, the assistant, and the Ask panels in Diet, Gym, Finance, Diary, Survival and News. " +
                "Get a key at aistudio.google.com.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = openRouterDraft,
                onValueChange = { openRouterDraft = it },
                label = { Text("OpenRouter API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { scope.launch { settings.setOpenRouterKey(openRouterDraft); com.nizam.app.ui.components.Feedback.show("Saved ✓") } }) { Text("Save") }
        }

        // ── Fast lane: a second, very fast endpoint just for conversation ──
        Text("FAST LANE", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 26.dp))
        Text("Optional. Questions and chat go here first for near-instant replies; actions still use your main " +
            "provider. Every provider you've set up also backs the others up when one is rate-limited.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val lane0 = remember { com.nizam.app.net.Router.fastLaneParts(context) }
        var laneUrl by remember { mutableStateOf(lane0.first) }
        var laneKey by remember { mutableStateOf(lane0.second) }
        var laneModel by remember { mutableStateOf(lane0.third) }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
                Triple("Cerebras", "https://api.cerebras.ai/v1", "llama-3.3-70b"),
                Triple("Mistral", "https://api.mistral.ai/v1", "mistral-small-latest"),
                Triple("Off", "", "")
            ).forEach { (name, url, model) ->
                FilterChip(selected = laneUrl == url && (url.isNotBlank() || laneModel.isBlank()),
                    onClick = { laneUrl = url; laneModel = model; if (url.isBlank()) laneKey = "" },
                    label = { Text(name) })
            }
        }
        if (laneUrl.isNotBlank()) {
            OutlinedTextField(laneKey, { laneKey = it }, singleLine = true, label = { Text("API key") },
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(laneModel, { laneModel = it }, singleLine = true, label = { Text("Model") },
                modifier = Modifier.fillMaxWidth())
        }
        Button(onClick = {
            com.nizam.app.net.Router.setFastLane(context, laneUrl, laneKey, laneModel)
            com.nizam.app.ui.components.Feedback.show(if (laneUrl.isBlank()) "Fast lane off" else "Fast lane saved")
        }, modifier = Modifier.padding(top = 8.dp)) { Text("Save fast lane") }

        // ── Offline AI: a small model on the phone for when there's no internet ──
        Text("OFFLINE AI", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 26.dp))
        Text("A model that runs on the phone itself — when you're offline, or when every online provider fails. " +
            "Browse and download picks the best one for this phone in one tap. Nothing leaves the device.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        var localModel by remember { mutableStateOf(com.nizam.app.net.LocalModel.describe(context)) }
        var importing by remember { mutableStateOf(false) }
        val importScope = com.nizam.app.ui.design.rememberWorkScope()
        val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importing = true
                importScope.launch {
                    runCatching { com.nizam.app.net.LocalModel.import(context, uri) }
                        .onSuccess { localModel = com.nizam.app.net.LocalModel.describe(context); com.nizam.app.ui.components.Feedback.show("Offline model ready") }
                        .onFailure { com.nizam.app.ui.components.Feedback.failed("Importing the model", it) }
                    importing = false
                }
            }
        }
        Text(localModel ?: "No offline model", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
        Button(onClick = { onOpen(com.nizam.app.ui.nav.Routes.MODELS) }, modifier = Modifier.padding(top = 6.dp)) {
            Text("Browse and download models")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
            Button(enabled = !importing, onClick = { pickModel.launch(arrayOf("*/*")) }) {
                Text(if (importing) "Copying… (can take a minute)" else "Import a file")
            }
            if (localModel != null) OutlinedButton(onClick = { onOpen(com.nizam.app.ui.nav.Routes.MODELS) }) { Text("Manage") }
        }

        // ── Folax: Infinix's built-in assistant as the very last backup ──
        val hasFolax = remember { com.nizam.app.feature.jarvis.Folax.available(context) }
        var folaxOn by remember { mutableStateOf(com.nizam.app.net.Router.folaxBackup(context)) }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 18.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Folax as the last backup", style = MaterialTheme.typography.titleSmall)
                Text(if (hasFolax) "If no other AI answers, Atlas asks Folax (Infinix's assistant) on screen and brings back its reply. " +
                    "Needs Atlas's accessibility service on." else "Folax isn't on this phone — it comes with Infinix, Tecno and itel.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = folaxOn && hasFolax, enabled = hasFolax, onCheckedChange = { folaxOn = it; com.nizam.app.net.Router.setFolaxBackup(context, it) })
        }


        Spacer(Modifier.height(28.dp))

        SectionHeader("CURRENCY")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("PKR", "USD", "AED", "SAR").forEach { code ->
                FilterChip(
                    selected = currency == code,
                    onClick = { scope.launch { settings.setCurrency(code) } },
                    label = { Text(code) }
                )
            }
        }



        Spacer(Modifier.height(28.dp))

        SectionHeader("DAILY TARGETS")
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = kcalDraft, onValueChange = { kcalDraft = it; targetError = null },
                label = { Text("Calories") }, singleLine = true, modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = waterDraft, onValueChange = { waterDraft = it; targetError = null },
                label = { Text("Water (ml)") }, singleLine = true, modifier = Modifier.weight(1f)
            )
        }
        targetError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val kcalValue = kcalDraft.toIntOrNull()
            val waterValue = waterDraft.toIntOrNull()
            if (kcalValue == null || waterValue == null ||
                !settings.targetsAreValid(kcalValue, waterValue)
            ) {
                targetError = "Use whole-number targets between 1 and 100,000."
            } else {
                scope.launch {
                    settings.setTargets(kcalValue, waterValue)
                    targetError = null
                }
            }
        }) { Text("Save targets") }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { scope.launch { settings.resetOnboarding() } }) {
            Text("Run setup again")
        }


        SectionHeader("NOTIFICATIONS")
        val notifyOn by settings.notificationsOn.collectAsState(initial = false)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = notifyOn,
                onCheckedChange = { on ->
                    scope.launch { settings.setNotifications(on) }
                    if (on) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifyPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        Notifications.schedule(context)
                    } else Notifications.cancel(context)
                }
            )
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Prayer times and reminders", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Alerts at each prayer you haven't logged, open missions at 7pm, and the " +
                        "evening review at 9:30pm. Checked every 15 minutes.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }


        SectionHeader("BACKUP")
        Text(
            "Create a portable copy of Atlas data and choose where to save it. The archive includes " +
                "the database encryption key and saved API credentials; encrypting it is strongly recommended.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Encrypt backup with a recovery phrase", modifier = Modifier.weight(1f))
            Switch(checked = encryptBackup, onCheckedChange = {
                encryptBackup = it
                if (!it) {
                    recoveryPhrase = ""
                    recoveryPhraseConfirm = ""
                }
            })
        }
        if (encryptBackup) {
            OutlinedTextField(
                value = recoveryPhrase,
                onValueChange = { recoveryPhrase = it.take(256) },
                label = { Text("Recovery phrase") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = recoveryPhraseConfirm,
                onValueChange = { recoveryPhraseConfirm = it.take(256) },
                label = { Text("Confirm recovery phrase") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Text(
            if (encryptBackup) {
                "Use at least 12 characters. Atlas does not store this phrase; without it, an encrypted backup cannot be opened."
            } else {
                "Unencrypted: this ZIP contains the database key and saved API credentials. Anyone with the file can read them."
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Include Vault files", modifier = Modifier.weight(1f))
            Switch(checked = includeVault, onCheckedChange = { includeVault = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !backupBusy && (
                    !encryptBackup ||
                        (recoveryPhrase.length >= 12 && recoveryPhrase == recoveryPhraseConfirm)
                    ),
                onClick = {
                    if (encryptBackup) {
                        exportPicker.launch("atlas-backup-" + java.time.LocalDate.now() + ".zip")
                    } else {
                        showUnencryptedBackupWarning = true
                    }
                }
            ) { Text(if (backupBusy) "Working…" else "Back up now") }
            OutlinedButton(enabled = !backupBusy, onClick = {
                restorePicker.launch(arrayOf("application/zip", "*/*"))
            }) { Text("Restore") }
        }
        if (hasUndoRestore) {
            OutlinedButton(
                enabled = !backupBusy,
                onClick = { showUndoRestoreConfirmation = true }
            ) { Text("Undo last restore") }
        }
        backupStatus?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = MaterialTheme.typography.bodyMedium)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Restore first verifies and stages the archive. After you review the file list, Atlas " +
                "keeps a recoverable snapshot and applies the selected archive files on restart. " +
                "Files not in the archive are left alone.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (showUnencryptedBackupWarning) {
            AlertDialog(
                onDismissRequest = { showUnencryptedBackupWarning = false },
                title = { Text("Create an unencrypted backup?") },
                text = {
                    Text(
                        "This ZIP will include the database encryption key and saved API credentials. " +
                            "Anyone who gets the file can read those secrets and the selected Atlas data. " +
                            "Continue only if the save location is private."
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showUnencryptedBackupWarning = false
                            exportPicker.launch("atlas-backup-" + java.time.LocalDate.now() + ".zip")
                        }
                    ) { Text("Export unencrypted ZIP") }
                },
                dismissButton = {
                    TextButton(onClick = { showUnencryptedBackupWarning = false }) { Text("Cancel") }
                }
            )
        }

        restorePreview?.let { preview ->
            AlertDialog(
                onDismissRequest = {
                    Backup.discardStage(context, preview.id)
                    restorePreview = null
                    selectedRestorePaths = emptySet()
                    recoveryPhrase = ""
                    recoveryPhraseConfirm = ""
                },
                title = { Text("Review backup contents") },
                text = {
                    Column(
                        Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("${selectedRestorePaths.size} of ${preview.files.size} files selected · ${preview.totalBytes / (1024 * 1024)} MB total")
                        Text(
                            when {
                                !preview.verified -> "Older backup: it has no integrity manifest or file checksums."
                                preview.encrypted -> "Encrypted archive and every listed file passed integrity checks."
                                else -> "Every listed file passed integrity checks, but this archive is not encrypted."
                            },
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Only checked files will be replaced. Files not included remain unchanged. " +
                                "The database and its key are selected together; saved credentials travel " +
                                "with Settings and are re-sealed on this phone."
                        )
                        TextButton(onClick = {
                            selectedRestorePaths = preview.files.map { it.path }.toSet()
                        }) { Text("Select all") }
                        preview.files.forEach { file ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = file.path in selectedRestorePaths,
                                    onCheckedChange = { checked ->
                                        val databaseGroup = preview.files
                                            .filter {
                                                it.path.startsWith("db/") ||
                                                    it.path == "keys/db-passphrase"
                                            }
                                            .map { it.path }
                                            .toSet()
                                        val isDatabaseGroupFile =
                                            file.path.startsWith("db/") || file.path == "keys/db-passphrase"
                                        val credentialGroup = preview.files
                                            .filter {
                                                it.path == "keys/api-credentials" ||
                                                    it.path == "prefs/nizam_settings.preferences_pb"
                                            }
                                            .map { it.path }
                                            .toSet()
                                        val isCredentialGroupFile =
                                            file.path in credentialGroup
                                        selectedRestorePaths = when {
                                            isDatabaseGroupFile && checked ->
                                                selectedRestorePaths + databaseGroup
                                            isDatabaseGroupFile ->
                                                selectedRestorePaths - databaseGroup
                                            isCredentialGroupFile && checked ->
                                                selectedRestorePaths + credentialGroup
                                            isCredentialGroupFile ->
                                                selectedRestorePaths - credentialGroup
                                            checked -> selectedRestorePaths + file.path
                                            else -> selectedRestorePaths - file.path
                                        }
                                    }
                                )
                                val displayPath = when (file.path) {
                                    "keys/db-passphrase" -> "Database encryption key"
                                    "keys/api-credentials" -> "Saved API credentials"
                                    "prefs/nizam_settings.preferences_pb" -> "Settings"
                                    else -> file.path
                                }
                                Text(
                                    "$displayPath · ${(file.size / 1024).coerceAtLeast(1)} KB",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(
                        enabled = !backupBusy && selectedRestorePaths.isNotEmpty(),
                        onClick = {
                            backupBusy = true
                            val queued = runCatching {
                                Backup.queueRestore(context, preview.id, selectedRestorePaths)
                            }
                            queued.onSuccess {
                                backupStatus = "Restore queued. Atlas will save a pre-restore snapshot and restart."
                                restorePreview = null
                                selectedRestorePaths = emptySet()
                                recoveryPhrase = ""
                                recoveryPhraseConfirm = ""
                                scope.launch {
                                    kotlinx.coroutines.delay(900)
                                    android.os.Process.killProcess(android.os.Process.myPid())
                                }
                            }.onFailure {
                                backupStatus = "Restore could not be queued: ${com.nizam.app.ui.components.Feedback.friendly(it)}"
                            }
                            backupBusy = false
                        }
                    ) { Text("Restore these files") }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            Backup.discardStage(context, preview.id)
                            restorePreview = null
                            selectedRestorePaths = emptySet()
                            recoveryPhrase = ""
                            recoveryPhraseConfirm = ""
                        }
                    ) { Text("Cancel") }
                }
            )
        }
        if (showUndoRestoreConfirmation) {
            AlertDialog(
                onDismissRequest = { showUndoRestoreConfirmation = false },
                title = { Text("Undo the last restore?") },
                text = {
                    Text(
                        "Atlas will replace the files touched by that restore with their saved pre-restore versions. " +
                            "Other files will remain unchanged."
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !backupBusy,
                        onClick = {
                            backupBusy = true
                            runCatching { Backup.queueUndoLastRestore(context) }
                                .onSuccess {
                                    showUndoRestoreConfirmation = false
                                    hasUndoRestore = false
                                    backupStatus = "Undo queued. Atlas will restore the saved files and restart."
                                    scope.launch {
                                        kotlinx.coroutines.delay(900)
                                        android.os.Process.killProcess(android.os.Process.myPid())
                                    }
                                }
                                .onFailure {
                                    backupStatus = "Undo could not be queued: ${com.nizam.app.ui.components.Feedback.friendly(it)}"
                                }
                            backupBusy = false
                        }
                    ) { Text("Undo restore") }
                },
                dismissButton = {
                    TextButton(onClick = { showUndoRestoreConfirmation = false }) { Text("Cancel") }
                }
            )
        }


        SectionHeader("APP LOCK")
        Text(
            if (pin.isBlank()) "No PIN set — the app opens straight away."
            else "PIN is on. Atlas asks for it every time it starts.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = pinDraft,
                onValueChange = { pinDraft = it.filter { c -> c.isDigit() }.take(8) },
                label = { Text("New PIN (4-8 digits)") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = pinDraft.length >= 4,
                onClick = { scope.launch { settings.setPin(pinDraft); pinDraft = "" } }
            ) { Text("Set") }
        }
        if (pin.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { scope.launch { settings.setPin(""); com.nizam.app.ui.components.Feedback.show("Saved ✓") } }) { Text("Remove PIN") }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "This stops someone picking up your phone. It is not encryption — your data sits in the " +
                "app's private storage either way.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )


        SectionHeader("RESET")
        if (resetStage == 0) {
            OutlinedButton(onClick = { resetStage = 1 }) { Text("Reset all data…") }
        } else {
            Text(
                "This deletes every log, goal, note, course, chat and setting, and returns Atlas to a " +
                    "fresh install. It cannot be undone. Back up first if there's anything you want.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = resetVault, onCheckedChange = { resetVault = it })
                Spacer(Modifier.width(8.dp))
                Text("Also delete encrypted vault files", style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(
                value = resetText, onValueChange = { resetText = it },
                label = { Text("Type RESET to confirm") }, singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = resetText.trim() == "RESET",
                    onClick = {
                        scope.launch {
                            com.nizam.app.feature.backup.Reset.everything(context, settings, resetVault)
                            android.os.Process.killProcess(android.os.Process.myPid())
                        }
                    }
                ) { Text("Erase everything") }
                OutlinedButton(onClick = { resetStage = 0; resetText = "" }) { Text("Cancel") }
            }
        }


        SectionHeader("ABOUT")
        Text("Atlas V1.0 Beta", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Your data stays on this device. The only network calls are Quran text (Al Quran Cloud) and Hadith (hadithapi.com), both cached after first use.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(40.dp))
    }
}
