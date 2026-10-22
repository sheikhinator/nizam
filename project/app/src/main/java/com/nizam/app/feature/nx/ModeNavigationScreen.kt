package com.nizam.app.feature.nx

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.ui.nav.Routes
import com.nizam.app.ui.screens.search.Dest
import com.nizam.app.ui.screens.search.DESTINATIONS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

@Composable
fun ModeNavigationScreen(container: AppContainer, open: (String) -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { ModeNavigationStore.get(context) }
    val state by store.state.collectAsState()
    val notificationsOn by container.settingsRepository.notificationsOn.collectAsState(initial = false)
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val specs by container.dynamicRepository.specs().collectAsState(initial = emptyList())
    val destinations = remember(specs) {
        (DESTINATIONS + specs.map { Dest(it.emoji, it.name, Routes.dynamic(it.key), it.name) })
            .distinctBy { it.route }
    }
    val rtl = state.language == "Urdu"
    var newModeName by rememberSaveable { mutableStateOf(state.savedDrafts["new-mode-name"].orEmpty()) }
    var newGroupName by rememberSaveable { mutableStateOf(state.savedDrafts["new-group-name"].orEmpty()) }
    var addDestination by rememberSaveable { mutableStateOf(false) }
    var notice by rememberSaveable { mutableStateOf("") }

    LaunchedEffect(newModeName) { com.nizam.app.ui.design.guard {
        if (newModeName.isNotBlank()) {
            delay(450)
            runCatching { withContext(Dispatchers.IO) { store.saveDraft("new-mode-name", newModeName) } }
        }
    } }
    LaunchedEffect(newGroupName) { com.nizam.app.ui.design.guard {
        if (newGroupName.isNotBlank()) {
            delay(450)
            runCatching { withContext(Dispatchers.IO) { store.saveDraft("new-group-name", newGroupName) } }
        }
    } }

    fun safeUpdate(block: () -> Unit) {
        runCatching(block).onSuccess { notice = "Saved on this device." }
            .onFailure { notice = it.message ?: "This change could not be saved." }
    }
    fun applyNotificationChange(value: Boolean?) {
        if (value != null) scope.launch {
            runCatching { container.settingsRepository.setNotifications(value) }
                .onFailure { notice = it.message ?: "Notification preference could not be applied." }
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                when (state.language) {
                    "Urdu" -> "اٹلس موڈز اور نیویگیشن"
                    "Roman Urdu" -> "Atlas modes aur navigation"
                    else -> "Modes & navigation"
                },
                style = MaterialTheme.typography.headlineMedium
            )
            Text(
                when (state.language) {
                    "Urdu" -> "کام، آرام اور سفر کے لیے اپنی ترجیحات بنائیں۔"
                    "Roman Urdu" -> "Kaam, aaram aur safar ke liye apni settings banayein."
                    else -> "Set up Atlas for work, rest, travel, and the way you find things."
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.storageError?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            notice.takeIf(String::isNotBlank)?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { open(Routes.TODAY) }, modifier = Modifier.fillMaxWidth()) {
                Text(if (state.language == "Urdu") "مکمل Atlas پر واپس جائیں" else "Return to the full Atlas")
            }

            SectionTitle("Modes", "اپنا موڈ منتخب کریں")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.modes.forEach { mode ->
                    Row(
                        Modifier.fillMaxWidth().clickable(role = Role.RadioButton) {
                            safeUpdate { applyNotificationChange(store.selectMode(mode.id, notificationsOn)) }
                        }.padding(vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = mode.id == state.activeModeId,
                            onCheckedChange = { selected ->
                                if (selected) safeUpdate { applyNotificationChange(store.selectMode(mode.id, notificationsOn)) }
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(mode.name, style = MaterialTheme.typography.titleSmall)
                            Text("${mode.missionIntensity.lowercase().replaceFirstChar { it.uppercase() }} missions · " +
                                if (mode.notificationsEnabled == null) "Atlas notification setting" else
                                    if (mode.notificationsEnabled) "notifications on" else "notifications quiet",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (mode.id != "full") {
                            TextButton(onClick = {
                                safeUpdate { applyNotificationChange(store.deleteMode(mode.id)) }
                            }) { Text("Remove") }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = newModeName,
                onValueChange = {
                    newModeName = it.take(48)
                },
                label = { Text("Create a mode") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = newModeName.isNotBlank() && state.storageError == null, onClick = {
                    val mode = AtlasModeProfile(UUID.randomUUID().toString(), newModeName.trim())
                    safeUpdate {
                        store.update { current -> current.copy(modes = current.modes + mode, activeModeId = mode.id) }
                        store.discardDraft("new-mode-name")
                    }
                    newModeName = ""
                }) { Text("Create & select") }
                if (newModeName.isNotBlank()) TextButton(onClick = {
                    newModeName = ""
                    safeUpdate { store.discardDraft("new-mode-name") }
                }) { Text("Discard draft") }
            }

            val activeMode = state.activeMode
            SectionTitle("This mode", "The choices below affect Today and Modules")
            Text("Today cards", style = MaterialTheme.typography.titleSmall)
            ATLAS_TODAY_CARD_LABELS.forEach { (key, label) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = key in activeMode.visibleCards,
                        onCheckedChange = { checked ->
                            safeUpdate { store.update { current ->
                                current.copy(modes = current.modes.map { mode ->
                                    if (mode.id != activeMode.id) mode else mode.copy(
                                        visibleCards = if (checked) mode.visibleCards + key else mode.visibleCards - key
                                    )
                                })
                            } }
                        }
                    )
                    Text(label)
                }
            }
            Text("Mission intensity", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("GENTLE", "NORMAL", "FOCUSED").forEach { intensity ->
                    FilterChip(
                        selected = activeMode.missionIntensity == intensity,
                        onClick = { safeUpdate { updateMode(store, activeMode.id) { it.copy(missionIntensity = intensity) } } },
                        label = { Text(intensity.lowercase().replaceFirstChar { it.uppercase() }) }
                    )
                }
            }
            Text("Notification behavior", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Follow Atlas", "Quiet", "Allow").forEachIndexed { index, label ->
                    val value = when (index) { 0 -> null; 1 -> false; else -> true }
                    FilterChip(
                        selected = activeMode.notificationsEnabled == value,
                        onClick = {
                            safeUpdate {
                                applyNotificationChange(store.setModeNotifications(activeMode.id, value, notificationsOn))
                            }
                        },
                        label = { Text(label) }
                    )
                }
            }
            Text("Offline priorities", style = MaterialTheme.typography.titleSmall)
            Text("Mark modules to prioritize for travel. Records already stored on this device remain available offline.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            specs.take(30).forEach { spec ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = spec.key in activeMode.offlineContent,
                        onCheckedChange = { checked ->
                            safeUpdate { updateMode(store, activeMode.id) {
                                it.copy(offlineContent = if (checked) it.offlineContent + spec.key else it.offlineContent - spec.key)
                            } }
                        }
                    )
                    Text("${spec.emoji} ${spec.name}")
                }
            }

            SectionTitle("Today layout", "Hide cards and choose their order")
            state.todayCardOrder.forEachIndexed { index, key ->
                val label = ATLAS_TODAY_CARD_LABELS.firstOrNull { it.first == key }?.second ?: key
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = key in state.visibleTodayCards,
                        onCheckedChange = { checked ->
                            safeUpdate { store.update { current ->
                                current.copy(visibleTodayCards = if (checked) current.visibleTodayCards + key else current.visibleTodayCards - key)
                            } }
                        }
                    )
                    Text(label, Modifier.weight(1f))
                    TextButton(enabled = index > 0, onClick = {
                        safeUpdate { store.update { current -> current.copy(todayCardOrder = move(current.todayCardOrder, index, -1)) } }
                    }) { Text("↑") }
                    TextButton(enabled = index < state.todayCardOrder.lastIndex, onClick = {
                        safeUpdate { store.update { current -> current.copy(todayCardOrder = move(current.todayCardOrder, index, 1)) } }
                    }) { Text("↓") }
                    Switch(
                        checked = key in state.compactTodayCards,
                        onCheckedChange = { compact ->
                            safeUpdate { store.update { current ->
                                current.copy(compactTodayCards = if (compact) current.compactTodayCards + key else current.compactTodayCards - key)
                            } }
                        }
                    )
                }
            }

            SectionTitle("Module groups", "Your custom modules stay in their original record store")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newGroupName, { newGroupName = it.take(32) },
                    modifier = Modifier.weight(1f), label = { Text("New group name") }, singleLine = true)
                Button(enabled = newGroupName.isNotBlank() && state.customGroups.size < ModeNavigationStore.MAX_GROUPS, onClick = {
                    val value = newGroupName.trim()
                    safeUpdate { store.update { current ->
                        current.copy(customGroups = (current.customGroups + value).distinct().take(ModeNavigationStore.MAX_GROUPS))
                    }; store.discardDraft("new-group-name") }
                    newGroupName = ""
                }) { Text("Add") }
            }
            if (newGroupName.isNotBlank()) {
                TextButton(onClick = {
                    newGroupName = ""
                    safeUpdate { store.discardDraft("new-group-name") }
                }) { Text("Discard group draft") }
            }
            val groups = (listOf("Tracked") + state.customGroups).distinct()
            specs.forEach { spec ->
                Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Text("${spec.emoji} ${spec.name}", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        groups.forEach { group ->
                            FilterChip(
                                selected = state.moduleGroups[spec.key] == group ||
                                    (state.moduleGroups[spec.key] == null && group == "Tracked"),
                                onClick = { safeUpdate { store.update { current ->
                                    current.copy(moduleGroups = current.moduleGroups + (spec.key to group))
                                } } },
                                label = { Text(group) }
                            )
                        }
                    }
                }
            }

            SectionTitle("Pinned destinations", "Keep favorites beside the regular tabs")
            state.pinnedDestinations.forEach { route ->
                val title = destinations.firstOrNull { it.route == route }?.name ?: route
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f))
                    TextButton(onClick = { safeUpdate { store.togglePin(route) } }) { Text("Unpin") }
                    TextButton(onClick = {
                        safeUpdate { store.recordRecent(route) }
                        open(route)
                    }) { Text("Open") }
                }
            }
            OutlinedButton(onClick = { addDestination = !addDestination }) {
                Text(if (addDestination) "Done" else "Pin a destination")
            }
            if (addDestination) destinations.filterNot { it.route in state.pinnedDestinations }.take(24).forEach { destination ->
                Text(
                    "${destination.emoji} ${destination.name}",
                    modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) {
                        safeUpdate { store.togglePin(destination.route) }
                    }.padding(vertical = 7.dp)
                )
            }

            SectionTitle("Recently opened", "One-tap return to your last few modules")
            if (state.recentDestinations.isEmpty()) {
                Text("Opened destinations will appear here.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else state.recentDestinations.forEach { route ->
                val title = destinations.firstOrNull { it.route == route }?.name ?: route
                TextButton(onClick = { safeUpdate { store.recordRecent(route) }; open(route) }) { Text(title) }
            }

            SectionTitle("Language and comfort", "Urdu is right-to-left; Roman Urdu stays left-to-right")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ModeNavigationStore.LANGUAGES.forEach { language ->
                    FilterChip(
                        selected = state.language == language,
                        onClick = { safeUpdate { store.update { it.copy(language = language) } } },
                        label = { Text(language) }
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.urduNumerals, onCheckedChange = { value ->
                    safeUpdate { store.update { it.copy(urduNumerals = value) } }
                })
                Text("Use Urdu numerals where supported")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.hijriDates, onCheckedChange = { value ->
                    safeUpdate { store.update { it.copy(hijriDates = value) } }
                })
                Text("Show Hijri dates where available")
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = state.lowCognitiveLoad, onCheckedChange = { value ->
                    safeUpdate { store.update { it.copy(lowCognitiveLoad = value) } }
                })
                Text("Low-cognitive-load layout")
            }

            SectionTitle("More Atlas tools", "Existing experiences remain available")
            OutlinedButton(onClick = { open(Routes.SEARCH) }, modifier = Modifier.fillMaxWidth()) {
                Text("Open full-record search and saved views")
            }
            OutlinedButton(onClick = { open("nx/themestudio") }, modifier = Modifier.fillMaxWidth()) {
                Text("Open theme composer")
            }
            OutlinedButton(onClick = { open("nx/lifeeventworkspace") }, modifier = Modifier.fillMaxWidth()) {
                Text("Open life-event workspace")
            }

            SectionTitle("Keyboard and switch shortcuts", "Standard Compose controls remain available to TalkBack and switch access")
            listOf(
                "Ctrl+K — open the command palette from Search; app-wide after shell integration",
                "Enter — open the selected result",
                "Escape — close a panel or return to the previous screen",
                "Tab / Shift+Tab and D-pad — move between controls"
            ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
            Spacer(Modifier.height(40.dp))
        }
    }

}

@Composable
private fun SectionTitle(title: String, supporting: String) {
    Column(Modifier.padding(top = 10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(supporting, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun updateMode(
    store: ModeNavigationStore,
    id: String,
    change: (AtlasModeProfile) -> AtlasModeProfile
) = store.update { current ->
    current.copy(modes = current.modes.map { if (it.id == id) change(it) else it })
}

private fun move(values: List<String>, index: Int, delta: Int): List<String> {
    val target = (index + delta).coerceIn(0, values.lastIndex)
    if (target == index) return values
    return values.toMutableList().apply { add(target, removeAt(index)) }
}