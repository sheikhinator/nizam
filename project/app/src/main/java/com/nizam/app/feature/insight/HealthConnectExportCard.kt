package com.nizam.app.feature.insight

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.HealthConnectClient
import androidx.compose.ui.platform.LocalContext
import com.nizam.app.AppContainer
import com.nizam.app.feature.insight.HealthConnectWriteBack.Type
import kotlinx.coroutines.launch

@Composable
fun HealthConnectExportCard(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = container.settingsRepository
    val workouts by settings.healthWriteWorkouts.collectAsState(initial = false)
    val weight by settings.healthWriteWeight.collectAsState(initial = false)
    val hydration by settings.healthWriteHydration.collectAsState(initial = false)
    var pendingConsent by remember { mutableStateOf<Type?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }

    val permissionRequest = rememberLauncherForActivityResult(HealthLink.contract) { granted ->
        val type = pendingConsent
        pendingConsent = null
        if (type == null) return@rememberLauncherForActivityResult
        if (granted.containsAll(HealthConnectWriteBack.permissionsFor(setOf(type)))) {
            scope.launch {
                setConsent(settings, type, true)
                status = "${type.label} export is enabled. Nothing is sent until you tap Send selected records."
            }
        } else {
            status = "${type.label} stays off because Health Connect access wasn't granted."
        }
    }

    fun changeConsent(type: Type, enabled: Boolean) {
        if (!enabled) {
            scope.launch {
                setConsent(settings, type, false)
                status = "${type.label} export is off. Existing Health Connect records are not removed."
            }
            return
        }
        if (!HealthLink.available(context)) {
            status = "Health Connect isn't installed or up to date on this phone."
            return
        }
        scope.launch {
            runCatching {
                val required = HealthConnectWriteBack.permissionsFor(setOf(type))
                val alreadyGranted = HealthConnectClient.getOrCreate(context)
                    .permissionController.getGrantedPermissions().containsAll(required)
                if (alreadyGranted) {
                    setConsent(settings, type, true)
                    status = "${type.label} export is enabled. Nothing is sent until you tap Send selected records."
                } else {
                    pendingConsent = type
                    permissionRequest.launch(required)
                }
            }.onFailure {
                status = "Couldn't check Health Connect access: ${com.nizam.app.ui.components.Feedback.friendly(it)}"
            }
        }
    }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Send Atlas records to Health Connect", style = MaterialTheme.typography.titleMedium)
            Text(
                "Nothing is sent automatically. Choose each type, grant its Health Connect permission, then send selected records when you choose.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))

            ConsentRow(
                title = "Workouts",
                detail = "Strength sets are grouped by their logged times. Cardio isn't sent because Atlas doesn't store its start time.",
                checked = workouts,
                enabled = !sending,
                onCheckedChange = { changeConsent(Type.WORKOUTS, it) }
            )
            ConsentRow(
                title = "Weight",
                detail = "New entries include when you save them. Older date-only weights stay local.",
                checked = weight,
                enabled = !sending,
                onCheckedChange = { changeConsent(Type.WEIGHT, it) }
            )
            ConsentRow(
                title = "Hydration",
                detail = "New water entries are sent individually. Existing daily totals aren't split into drinks.",
                checked = hydration,
                enabled = !sending,
                onCheckedChange = { changeConsent(Type.HYDRATION, it) }
            )

            Text(
                "Atlas marks these as manual entries from this phone. Turning a type off stops future Atlas writes, but doesn't delete existing records or revoke Android's Health Connect permission.",
                modifier = Modifier.padding(top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            val selectedTypes = buildSet {
                if (workouts) add(Type.WORKOUTS)
                if (weight) add(Type.WEIGHT)
                if (hydration) add(Type.HYDRATION)
            }
            Button(
                onClick = {
                    scope.launch {
                        sending = true
                        status = null
                        try {
                            status = HealthConnectWriteBack.send(context, container, selectedTypes)
                        } catch (error: Exception) {
                            status = when (error) {
                                is SecurityException -> "Health Connect access changed. Turn the affected type off and on to request its permission again."
                                else -> "Couldn't send Atlas records: ${com.nizam.app.ui.components.Feedback.friendly(error)}"
                            }
                        } finally {
                            sending = false
                        }
                    }
                },
                enabled = selectedTypes.isNotEmpty() && !sending,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (sending) "Sending…" else "Send selected records")
            }
            status?.let {
                Text(
                    it,
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun ConsentRow(
    title: String,
    detail: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange
        ).padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

private suspend fun setConsent(
    settings: com.nizam.app.data.prefs.SettingsRepository,
    type: Type,
    enabled: Boolean
) {
    when (type) {
        Type.WORKOUTS -> settings.setHealthWriteWorkouts(enabled)
        Type.WEIGHT -> settings.setHealthWriteWeight(enabled)
        Type.HYDRATION -> settings.setHealthWriteHydration(enabled)
    }
}