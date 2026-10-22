package com.nizam.app.feature.insight

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import com.nizam.app.AppContainer
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.ui.design.AtlasScreen
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Hub
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.Segmented
import com.nizam.app.ui.design.Space
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

private val readinessChoices = listOf("low", "typical", "high")

private fun choiceLabel(value: String, body: Boolean = false) = when (value) {
    "low" -> if (body) "No strain" else "Low"
    "typical" -> if (body) "Some strain" else "Typical"
    else -> if (body) "Strong strain" else "High"
}

private fun choiceNumber(value: String) = when (value) {
    "low" -> 1
    "high" -> 3
    else -> 2
}

private fun choiceFrom(number: Int) = when (number.coerceIn(1, 3)) {
    1 -> "low"
    3 -> "high"
    else -> "typical"
}

private fun readHours(value: Double?) = value?.let { String.format(Locale.US, "%.1f", it) }.orEmpty()

private fun savedSleep(value: String): Double? {
    if (value.isBlank()) return null
    return value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..18.0 }
}

@Composable
fun ReadinessScreen(container: AppContainer) {
    val context = LocalContext.current
    val today = LocalDate.now()
    val todayKey = today.toString()
    val store = remember(context) { JsonStore(context, "readiness_daily", sealed = true) }
    var entries by remember(context) { mutableStateOf(readReadinessEntries(store.all())) }
    var health by remember(context) { mutableStateOf(HealthCache(context).load()) }
    val savedToday = entries.firstOrNull { it.date == today }

    var sleepDraft by remember(todayKey) {
        mutableStateOf(readHours(savedToday?.sleepHours ?: health[todayKey]?.first))
    }
    var sleepTouched by remember(todayKey) { mutableStateOf(savedToday?.sleepHours != null) }
    var energy by remember(todayKey) { mutableStateOf(savedToday?.energy?.let(::choiceFrom)) }
    var stress by remember(todayKey) { mutableStateOf(savedToday?.stress?.let(::choiceFrom)) }
    var bodyStrain by remember(todayKey) { mutableStateOf(savedToday?.bodyStrain?.let(::choiceFrom)) }
    var status by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }
    val skinTemperatureCache = remember(context) { SkinTemperatureCache(context) }
    val useSkinTemperature by container.settingsRepository.healthReadSkinTemperature.collectAsState(initial = false)
    var skinTemperatureDeltas by remember(context) { mutableStateOf(emptyMap<String, Double>()) }
    var skinTemperatureSupported by remember(context) { mutableStateOf<Boolean?>(null) }
    var skinTemperatureSyncing by remember { mutableStateOf(false) }
    var skinTemperatureStatus by remember { mutableStateOf<String?>(null) }
    val scope = com.nizam.app.ui.design.rememberSafeScope()

    LaunchedEffect(context) { com.nizam.app.ui.design.guard {
        skinTemperatureSupported = HealthTemperatureLink.supported(context)
    } }

    LaunchedEffect(context, useSkinTemperature, skinTemperatureSupported) { com.nizam.app.ui.design.guard {
        when {
            !useSkinTemperature -> skinTemperatureDeltas = emptyMap()
            skinTemperatureSupported == false -> {
                skinTemperatureDeltas = emptyMap()
                skinTemperatureStatus = "This Health Connect provider doesn't support skin temperature."
            }
            skinTemperatureSupported == true && HealthTemperatureLink.hasReadPermission(context) -> {
                skinTemperatureDeltas = skinTemperatureCache.load()
            }
            skinTemperatureSupported == true -> {
                skinTemperatureDeltas = emptyMap()
                skinTemperatureStatus = "Health Connect skin-temperature access is off. Turn this option off and on to request access again."
            }
        }
    } }

    LaunchedEffect(todayKey, health, entries) { com.nizam.app.ui.design.guard {
        if (!sleepTouched) {
            val latest = entries.firstOrNull { it.date == today }
            val prefill = latest?.sleepHours ?: health[todayKey]?.first
            if (prefill != null) sleepDraft = readHours(prefill)
        }
    } }

    suspend fun syncHealth() {
        syncing = true
        status = runCatching { HealthLink.sync(context) }
            .getOrElse { "Sync failed: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
        health = HealthCache(context).load()
        syncing = false
    }

    suspend fun syncSkinTemperature() {
        skinTemperatureSyncing = true
        try {
            val readings = HealthTemperatureLink.sync(context, container.settingsRepository)
            skinTemperatureCache.save(readings)
            skinTemperatureDeltas = readings
            skinTemperatureStatus = if (readings.isEmpty()) {
                "Health Connect returned no daily skin-temperature readings in the last 30 days."
            } else {
                "Synced ${readings.size} daily skin-temperature values. A comparison needs at least 7 earlier measured days and a reading today or yesterday."
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            if (error is SecurityException) skinTemperatureDeltas = emptyMap()
            skinTemperatureStatus = when (error) {
                is SecurityException -> "Health Connect access is missing. Turn this option off and on to request it again."
                else -> "Temperature sync failed: ${com.nizam.app.ui.components.Feedback.friendly(error)}"
            }
        } finally {
            skinTemperatureSyncing = false
        }
    }

    val permissionRequest = rememberLauncherForActivityResult(HealthLink.contract) { granted ->
        if (granted.containsAll(HealthLink.permissions)) {
            scope.launch { syncHealth() }
        } else {
            status = "Health Connect access wasn't granted. You can still enter sleep manually."
        }
    }

    val skinTemperaturePermissionRequest =
        rememberLauncherForActivityResult(HealthLink.contract) { granted ->
            if (granted.contains(HealthTemperatureLink.readPermission)) {
                scope.launch {
                    container.settingsRepository.setHealthReadSkinTemperature(true)
                    skinTemperatureStatus = "Enabled. Atlas won't read anything until you tap Sync skin-temperature trend."
                }
            } else {
                skinTemperatureStatus = "Skin-temperature access stays off because permission wasn't granted."
            }
        }

    fun changeSkinTemperatureConsent(enabled: Boolean) {
        if (!enabled) {
            scope.launch {
                container.settingsRepository.setHealthReadSkinTemperature(false)
                skinTemperatureCache.clear()
                skinTemperatureDeltas = emptyMap()
                skinTemperatureStatus = "Trend use is off and Atlas's encrypted local cache was cleared. Health Connect data and its permission are unchanged."
            }
            return
        }
        if (skinTemperatureSupported != true) {
            skinTemperatureStatus = if (skinTemperatureSupported == false) {
                "This Health Connect provider doesn't support skin temperature."
            } else {
                "Checking Health Connect support. Try again in a moment."
            }
            return
        }
        scope.launch {
            if (HealthTemperatureLink.hasReadPermission(context)) {
                container.settingsRepository.setHealthReadSkinTemperature(true)
                skinTemperatureStatus = "Enabled. Atlas won't read anything until you tap Sync skin-temperature trend."
            } else {
                skinTemperaturePermissionRequest.launch(setOf(HealthTemperatureLink.readPermission))
            }
        }
    }

    val hours = savedSleep(sleepDraft)
    val invalidSleep = sleepDraft.isNotBlank() && hours == null
    val readyToSave = !invalidSleep && energy != null && stress != null && bodyStrain != null
    val skinTemperatureTrend = if (useSkinTemperature) {
        personalSkinTemperatureTrend(today, skinTemperatureDeltas)
    } else null
    val report = if (savedToday != null) {
        ReadinessEngine.evaluate(
            today,
            entries,
            health,
            if (useSkinTemperature) skinTemperatureDeltas else emptyMap()
        )
    } else null

    AtlasScreen(
        title = "Readiness",
        subtitle = "A daily planning cue based on your own recent records.",
        hub = Hub.Health
    ) {
        item("readiness-safety") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text(
                    "This is not medical advice or a diagnosis. Atlas compares your check-in with your own past 28 days; it does not use population averages. Check-ins stay encrypted on this phone.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item("readiness-health-connect") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text("Optional Health Connect", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Read-only sleep and step history can fill in your personal baseline. Today's steps are ignored because the day is incomplete; yesterday's steps are only a rough activity clue.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        if (!HealthLink.available(context)) {
                            status = "Health Connect isn't installed or up to date on this phone."
                        } else {
                            scope.launch {
                                if (HealthLink.granted(context)) syncHealth()
                                else permissionRequest.launch(HealthLink.permissions)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !syncing
                ) { Text(if (syncing) "Syncing…" else "Connect or sync sleep and steps") }
                status?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        item("readiness-skin-temperature") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text("Optional skin-temperature trend", style = MaterialTheme.typography.titleMedium)
                Text(
                    "If supported, Health Connect can provide peripheral skin-temperature changes from a wearable. Atlas compares a daily average only with your own preceding 28-day median; it shows no absolute temperature and does not diagnose or change the readiness suggestion.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth().toggleable(
                        value = useSkinTemperature,
                        enabled = skinTemperatureSupported == true && !skinTemperatureSyncing,
                        role = Role.Switch,
                        onValueChange = ::changeSkinTemperatureConsent
                    ).padding(vertical = Space.xs),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Use my personal trend", modifier = Modifier.weight(1f))
                    Switch(
                        checked = useSkinTemperature,
                        onCheckedChange = null,
                        enabled = skinTemperatureSupported == true && !skinTemperatureSyncing
                    )
                }
                when (skinTemperatureSupported) {
                    false -> Text(
                        "This Health Connect provider doesn't support skin temperature; Atlas won't request access.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    null -> Text(
                        "Checking Health Connect feature support…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> Unit
                }
                if (useSkinTemperature) {
                    if (skinTemperatureTrend != null) {
                        val deviation = String.format(Locale.US, "%+.1f", skinTemperatureTrend.deviationCelsius)
                        Text(
                            "Latest ${skinTemperatureTrend.date}: ${deviation}°C versus your own preceding 28-day median (${skinTemperatureTrend.baselineDays} measured days).",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        Text(
                            "No personal comparison yet. Atlas needs a reading today or yesterday and at least 7 earlier measured days.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    OutlinedButton(
                        onClick = { scope.launch { syncSkinTemperature() } },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !skinTemperatureSyncing && skinTemperatureSupported == true
                    ) {
                        Text(if (skinTemperatureSyncing) "Syncing…" else "Sync skin-temperature trend")
                    }
                }
                skinTemperatureStatus?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                Text(
                    "Turning this off clears Atlas's encrypted trend cache. It does not revoke Health Connect permission or remove source records.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item("readiness-checkin") {
            Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                Text("Today's check-in", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (health[todayKey]?.first != null && savedToday?.sleepHours == null)
                        "Sleep was prefilled from Health Connect. Edit it if the value is incomplete."
                    else "Enter sleep hours if you have them; this field is optional.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = sleepDraft,
                    onValueChange = { sleepDraft = it.take(5); sleepTouched = true },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Sleep last night (hours)") },
                    placeholder = { Text("e.g. 7.5") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = invalidSleep
                )
                if (invalidSleep) {
                    Text("Use a number from 0 to 18, or leave sleep blank.", color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }

                Text(
                    "Choose one for each. Sleep is optional; your ratings are saved only after you tap Save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text("Energy", style = MaterialTheme.typography.labelLarge)
                Segmented(readinessChoices, energy, { energy = it }, { choiceLabel(it) })
                Text("Stress", style = MaterialTheme.typography.labelLarge)
                Segmented(readinessChoices, stress, { stress = it }, { choiceLabel(it) })
                Text("Body strain", style = MaterialTheme.typography.labelLarge)
                Segmented(readinessChoices, bodyStrain, { bodyStrain = it }, { choiceLabel(it, body = true) })

                Button(
                    onClick = {
                        val selectedEnergy = energy
                        val selectedStress = stress
                        val selectedBodyStrain = bodyStrain
                        if (selectedEnergy != null && selectedStress != null && selectedBodyStrain != null) {
                            val record = ReadinessEntry(
                                date = today,
                                sleepHours = hours,
                                energy = choiceNumber(selectedEnergy),
                                stress = choiceNumber(selectedStress),
                                bodyStrain = choiceNumber(selectedBodyStrain)
                            )
                            val records = (entries.filterNot { it.date == today } + record)
                                .sortedBy { it.date }
                                .takeLast(180)
                                .map { it.toJson() }
                            store.save(records)
                            entries = readReadinessEntries(records)
                            status = "Today's check-in is saved on this phone."
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = readyToSave
                ) { Text(if (savedToday == null) "Save today's check-in" else "Update today's check-in") }
            }
        }

        if (savedToday == null) {
            item("readiness-no-result") {
                Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                    Text("Save today's check-in to see a personal comparison.", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Baselines need at least 7 recorded days per measure. You can build them with daily check-ins, optional Health Connect sync, or both.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else if (report != null) {
            item("readiness-result") {
                Group(Modifier.padding(horizontal = Space.page, vertical = Space.s)) {
                    Text(
                        report.level?.title ?: "Building your baseline",
                        style = MaterialTheme.typography.headlineSmall,
                        color = when (report.level) {
                            ReadinessLevel.RECOVER -> MaterialTheme.colorScheme.error
                            ReadinessLevel.PUSH -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurface
                        }
                    )
                    report.level?.let { Text(it.advice, style = MaterialTheme.typography.bodyMedium) }
                    Text(report.coverage, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    report.reasons.forEach { reason ->
                        Text("• $reason", style = MaterialTheme.typography.bodySmall)
                    }
                    if (report.level == null) {
                        Text(
                            "Keep saving daily check-ins or sync Health Connect. Atlas will wait for your own history instead of guessing from a general standard.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}