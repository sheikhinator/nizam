package com.nizam.app.feature.phone

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import com.nizam.app.AppContainer
import com.nizam.app.core.ModuleTitle
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One place to grant, see and revoke everything Atlas can do on the phone. */
@Composable
fun PhoneControlScreen(container: AppContainer) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val screenOn by container.settingsRepository.screenControl.collectAsState(initial = false)

    var tick by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1500); tick++ } }   // refresh status after returning

    val runtime = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { tick++ }

    fun open(intent: Intent) = runCatching {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    val notificationAccess = remember(tick) {
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    }
    val screenService = remember(tick) { AtlasAccessibilityService.instance != null }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
    ) {
        ModuleTitle("Phone control", "What Atlas is allowed to do on this phone")

        Section(
            title = "1 · Direct actions",
            body = "Alarms, timers, calendar, calls, texts, contacts. Standard Android permissions — " +
                "you can revoke any of them in system settings."
        ) {
            Button(onClick = {
                runtime.launch(
                    arrayOf(
                        Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR,
                        Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE,
                        Manifest.permission.SEND_SMS
                    )
                )
            }) { Text("Grant direct actions") }
            Spacer(Modifier.height(6.dp))
            Row {
                OutlinedButton(onClick = {
                    open(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }) { Text("Do Not Disturb") }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = {
                    open(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}")))
                }) { Text("Brightness") }
            }
        }

        Section(
            title = "2 · Awareness",
            body = "Notification access lets Atlas summarise what you missed and reply through a " +
                "notification's reply button. Nothing is written to disk — the last 60 live in " +
                "memory only."
        ) {
            Status(notificationAccess, "Notification access")
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }) { Text(if (notificationAccess) "Manage notification access" else "Allow notification access") }

            Spacer(Modifier.height(10.dp))
            val usage = remember(tick) { PhoneActions2.hasUsageAccess(context) }
            Status(usage, "Screen time (usage access)")
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = { open(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }) {
                Text(if (usage) "Manage usage access" else "Allow screen time")
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Default assistant: set Atlas as the digital assistant app and a long-press on " +
                    "power or home opens the Curator instead of Google.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                open(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            }) { Text("Choose default assistant") }
        }

        Section(
            title = "3 · Screen control",
            body = "Atlas reads the screen and taps, types and scrolls inside other apps. This is " +
                "the most powerful permission in the app, so it runs with hard limits:"
        ) {
            listOf(
                "Never types into password fields — refused, always",
                "Refuses to operate inside banking, payment, crypto and password apps",
                "Every tap and type waits for your approval unless ⚡ Auto is on",
                "Everything it does is logged below",
                "What's on screen may be sent to your AI provider to decide the next step"
            ).forEach {
                Text("•  $it", style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 2.dp))
            }
            Spacer(Modifier.height(10.dp))
            Status(screenService, "System accessibility service")
            Spacer(Modifier.height(6.dp))
            OutlinedButton(onClick = {
                open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }) { Text(if (screenService) "Manage in accessibility" else "Enable in accessibility") }

            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(
                    checked = screenOn,
                    onCheckedChange = { on ->
                        AtlasAccessibilityService.enabledByUser = on
                        scope.launch { container.settingsRepository.setScreenControl(on) }
                    }
                )
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("Allow Atlas to control the screen", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "The kill switch. Off means every screen action refuses, even with the " +
                            "service enabled.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            val entries = remember(tick) { AtlasAccessibilityService.log.toList() }
            if (entries.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text("ACTIVITY LOG", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                entries.take(25).forEach {
                    Text(it, style = MonoNumber, modifier = Modifier.padding(vertical = 1.dp))
                }
            }
        }

        Spacer(Modifier.height(60.dp))
    }
}

@Composable
private fun Section(title: String, body: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(10.dp))
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Status(on: Boolean, label: String) {
    Text(
        (if (on) "● " else "○ ") + label + (if (on) " — on" else " — off"),
        style = MaterialTheme.typography.bodyMedium,
        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    )
}
