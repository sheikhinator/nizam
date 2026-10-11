package com.nizam.app.feature.security

import android.app.Activity
import android.app.AlarmManager
import android.app.AppOpsManager
import android.app.KeyguardManager
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Debug
import android.os.Process
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.NizamApplication
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.data.db.NizamDatabase
import com.nizam.app.feature.backup.Backup
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Atlas's privacy and security: what protects your data, what's weak on this phone, and the switches
 * you control — the privacy screen (no screenshots, blank in recent apps) and how soon Atlas re-locks.
 *
 * Built in regardless: no automatic cloud backup or sync; portable archives are created only when the user
 * starts them. Only the phone's own trusted certificates are accepted (no user-installed ones, so traffic
 * can't be quietly intercepted); keys and data live in Atlas's private storage, which Android encrypts on
 * disk whenever the phone has a screen lock.
 */
object Security {
    private fun p(c: Context) = c.getSharedPreferences("security", 0)
    fun privacyScreen(c: Context) = p(c).getBoolean("privacy_screen", false)
    fun setPrivacyScreen(c: Context, on: Boolean) = p(c).edit().putBoolean("privacy_screen", on).apply()
    fun lockAfterMs(c: Context) = p(c).getLong("lock_after", 60_000L)
    fun setLockAfter(c: Context, ms: Long) = p(c).edit().putLong("lock_after", ms).apply()

    fun applyPrivacyScreen(a: Activity) {
        if (privacyScreen(a)) a.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else a.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    data class Check(val ok: Boolean, val title: String, val detail: String)

    fun checks(c: Context): List<Check> {
        val km = c.getSystemService(KeyguardManager::class.java)
        val rooted = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/app/Superuser.apk", "/data/local/xbin/su")
            .any { File(it).exists() } || Build.TAGS?.contains("test-keys") == true
        val unknownAccess = runCatching {
            Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').filter { it.isNotBlank() && !it.startsWith(c.packageName) }
        }.getOrElse { emptyList() }
        val adb = runCatching { Settings.Global.getInt(c.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1 }.getOrDefault(false)
        return listOf(
            Check(km.isDeviceSecure, "Screen lock", if (km.isDeviceSecure) "On — Android encrypts Atlas's data on disk with it."
                else "Off — set a PIN or pattern so your data is encrypted at rest and the phone can't just be opened."),
            Check(
                true,
                "No automatic cloud backup or sync",
                "Atlas does not upload your records in the background. Device transfer happens only through a backup you start."
            ),
            Check(true, "Trusted connections only", "Only the phone's built-in certificates are trusted; user-installed ones can't intercept Atlas."),
            Check(!rooted, "Device integrity", if (rooted) "This phone looks rooted — any app with root can read Atlas's private files." else "No signs of root."),
            Check(!Debug.isDebuggerConnected(), "No debugger attached", if (Debug.isDebuggerConnected()) "A debugger is attached to Atlas right now." else "Nothing is attached to Atlas."),
            Check(!adb, "USB debugging", if (adb) "On — a computer you've allowed can read the phone. Turn it off in Developer options when you're not using it." else "Off."),
            Check(unknownAccess.isEmpty(), "Other apps reading the screen",
                if (unknownAccess.isEmpty()) "Only Atlas has screen-reading access." else "These can read everything on screen: " +
                    unknownAccess.joinToString { it.substringBefore('/') }),
        )
    }
}

/** Settings › Privacy & security. */
@Composable
fun SecurityScreen() {
    val c = LocalContext.current
    val app = c.applicationContext as NizamApplication
    val settingsRepository = app.container.settingsRepository
    var privacy by remember { mutableStateOf(Security.privacyScreen(c)) }
    var lockAfter by remember { mutableStateOf(Security.lockAfterMs(c)) }
    val checks = remember { Security.checks(c) }
    val provider by settingsRepository.aiProvider.collectAsState(initial = "GEMINI")
    val geminiKey by settingsRepository.geminiApiKey.collectAsState(initial = "")
    val openRouterKey by settingsRepository.openRouterKey.collectAsState(initial = "")
    val customKey by settingsRepository.customKey.collectAsState(initial = "")
    val secretError by settingsRepository.secretProtectionError.collectAsState(initial = null)
    var permissionRows by remember(c) { mutableStateOf(permissionStatuses(c)) }
    var specialAccessRows by remember(c) { mutableStateOf(specialAccessStatuses(c)) }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                permissionRows = permissionStatuses(c)
                specialAccessRows = specialAccessStatuses(c)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val lastBackupMillis = remember(c) { Backup.lastExportMillis(c) }
    val databaseHealth by produceState(initialValue = "Checking…", key1 = c) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val database = NizamDatabase.get(c).openHelper.writableDatabase
                val cipherReady = database.query("PRAGMA cipher_version").use { cursor ->
                    cursor.moveToFirst() && cursor.getString(0).isNotBlank()
                }
                val integrity = database.query("PRAGMA integrity_check").use { cursor ->
                    var rowCount = 0
                    var healthy = true
                    while (cursor.moveToNext()) {
                        rowCount++
                        if (cursor.getString(0) != "ok") healthy = false
                    }
                    cipherReady && healthy && rowCount == 1
                }
                if (integrity) "Healthy — encrypted database passed its integrity check."
                else "Needs attention — the database encryption or integrity check did not pass."
            }.getOrElse {
                "Could not verify the database. Do not uninstall or reset Atlas; export a backup if possible."
            }
        }
    }
    val activeProvider = when (provider) {
        "OPENROUTER" -> "OpenRouter"
        "CUSTOM" -> "your configured custom endpoint"
        else -> "Google Gemini"
    }
    val activeKeyConfigured = when (provider) {
        "OPENROUTER" -> openRouterKey.isNotBlank()
        "CUSTOM" -> customKey.isNotBlank()
        else -> geminiKey.isNotBlank()
    }
    val lastBackupText = lastBackupMillis?.let {
        DateFormat.getDateTimeInstance().format(Date(it))
    } ?: "No successful manual backup is recorded on this phone."
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Privacy & security", "${checks.count { it.ok }} of ${checks.size} protections in place")
        Label("DATA & BACKUP HEALTH", Modifier.padding(top = 16.dp))
        Group {
            Text("Local database", style = MaterialTheme.typography.titleSmall)
            Text(databaseHealth, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text("Saved API credentials", style = MaterialTheme.typography.titleSmall)
            Text(
                secretError ?: "Credentials are encrypted with this phone’s Android Keystore. Secret values are not shown here.",
                style = MaterialTheme.typography.bodySmall,
                color = if (secretError == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(10.dp))
            Text("AI data access", style = MaterialTheme.typography.titleSmall)
            Text(
                "When you use AI features, prompts may be sent to $activeProvider. " +
                    if (activeKeyConfigured) "A provider credential is saved." else "No credential is saved for the selected provider.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Text("Automatic sync", style = MaterialTheme.typography.titleSmall)
            Text(
                "Off. Atlas keeps records on this phone; backup and device transfer are manual.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(10.dp))
            Text("Last manual backup", style = MaterialTheme.typography.titleSmall)
            Text(lastBackupText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            Text(
                "Records live in their feature stores; some temporary logs are cleaned automatically. " +
                    "Use a feature’s delete control or Settings › Reset to erase Atlas data more broadly.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Label("ANDROID PERMISSIONS", Modifier.padding(top = 16.dp))
        Text(
            "These are the current permission grants. Android can revoke them at any time; Atlas asks for optional access only when a related feature needs it.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Group {
            permissionRows.forEach { (title, granted) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                        null,
                        Modifier.size(18.dp),
                        tint = if (granted) Color(0xFF3DAA6D) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "$title · ${if (granted) "Granted" else "Not granted"}",
                        modifier = Modifier.padding(start = 10.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            OutlinedButton(
                onClick = {
                    c.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", c.packageName, null)
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            ) { Text("Manage app permissions") }
        }
        Label("SPECIAL APP ACCESS", Modifier.padding(top = 16.dp))
        Group {
            specialAccessRows.forEach { access ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 7.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        if (access.enabled) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                        null,
                        Modifier.size(18.dp).padding(top = 2.dp),
                        tint = if (access.enabled) Color(0xFF3DAA6D) else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(
                            "${access.title} · ${if (access.enabled) "On" else "Off"}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            access.detail,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(
                        onClick = {
                            val intent = Intent(access.settingsAction)
                            if (access.packageScopedAction) {
                                intent.data = Uri.fromParts("package", c.packageName, null)
                            }
                            c.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    ) { Text("Manage") }
                }
            }
        }

        Group {
            checks.forEach { ch ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
                    Icon(if (ch.ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning, null, Modifier.size(20.dp),
                        tint = if (ch.ok) Color(0xFF3DAA6D) else Color(0xFFE0A030))
                    Column(Modifier.padding(start = 12.dp)) {
                        Text(ch.title, style = MaterialTheme.typography.titleSmall)
                        Text(ch.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Label("Privacy screen", Modifier.padding(top = 16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Block screenshots and hide Atlas in recent apps. Applies next time Atlas opens.", style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f))
            Switch(privacy, { privacy = it; Security.setPrivacyScreen(c, it); (c as? Activity)?.let(Security::applyPrivacyScreen) })
        }
        Label("Lock again after leaving Atlas", Modifier.padding(top = 16.dp))
        val options = listOf(0L to "Right away", 60_000L to "1 min", 300_000L to "5 min", 1_800_000L to "30 min")
        ChipRow(options.map { it.second }, options.firstOrNull { it.first == lockAfter }?.second ?: "1 min", { v ->
            options.first { it.second == v }.first.let { lockAfter = it; Security.setLockAfter(c, it) } })
        Text("Needs an app PIN (Settings › App lock).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(96.dp))
    }
}

private fun permissionStatuses(context: Context): List<Pair<String, Boolean>> {
    val permissions = listOf(
        "Notifications" to "android.permission.POST_NOTIFICATIONS",
        "Camera" to "android.permission.CAMERA",
        "Microphone" to "android.permission.RECORD_AUDIO",
        "Precise location" to "android.permission.ACCESS_FINE_LOCATION",
        "Approximate location" to "android.permission.ACCESS_COARSE_LOCATION",
        "Background location" to "android.permission.ACCESS_BACKGROUND_LOCATION",
        "Calendar read" to "android.permission.READ_CALENDAR",
        "Calendar write" to "android.permission.WRITE_CALENDAR",
        "Contacts" to "android.permission.READ_CONTACTS",
        "Phone calls" to "android.permission.CALL_PHONE",
        "SMS read" to "android.permission.READ_SMS",
        "SMS receive" to "android.permission.RECEIVE_SMS",
        "SMS send" to "android.permission.SEND_SMS",
        "Health Connect: steps" to "android.permission.health.READ_STEPS",
        "Health Connect: sleep" to "android.permission.health.READ_SLEEP",
        "Health Connect: skin temperature" to "android.permission.health.READ_SKIN_TEMPERATURE",
        "Health Connect: exercise write" to "android.permission.health.WRITE_EXERCISE",
        "Health Connect: weight write" to "android.permission.health.WRITE_WEIGHT",
        "Health Connect: hydration write" to "android.permission.health.WRITE_HYDRATION",
        "Bluetooth connect" to "android.permission.BLUETOOTH_CONNECT",
        "Bluetooth scan" to "android.permission.BLUETOOTH_SCAN",
        "Bluetooth advertise" to "android.permission.BLUETOOTH_ADVERTISE",
        "Nearby Wi-Fi devices" to "android.permission.NEARBY_WIFI_DEVICES",
        "Photos" to "android.permission.READ_MEDIA_IMAGES",
        "Audio files" to "android.permission.READ_MEDIA_AUDIO",
        "Video files" to "android.permission.READ_MEDIA_VIDEO",
        "Legacy shared storage" to "android.permission.READ_EXTERNAL_STORAGE",
        "Installed apps list" to "android.permission.QUERY_ALL_PACKAGES",
        "NFC" to "android.permission.NFC"
    )
    return permissions.map { (label, permission) ->
        label to (context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED)
    }
}

private data class SpecialAccessStatus(
    val title: String,
    val enabled: Boolean,
    val detail: String,
    val settingsAction: String,
    val packageScopedAction: Boolean = false
)

private fun specialAccessStatuses(context: Context): List<SpecialAccessStatus> {
    fun serviceIsEnabled(settingName: String): Boolean =
        Settings.Secure.getString(context.contentResolver, settingName).orEmpty()
            .split(':')
            .any { ComponentName.unflattenFromString(it)?.packageName == context.packageName }

    val appOps = context.getSystemService(AppOpsManager::class.java)
    val usageAccess = appOps?.checkOpNoThrow(
        AppOpsManager.OPSTR_GET_USAGE_STATS,
        Process.myUid(),
        context.packageName
    ) == AppOpsManager.MODE_ALLOWED
    val alarmManager = context.getSystemService(AlarmManager::class.java)
    return listOf(
        SpecialAccessStatus(
            "Notification reader",
            serviceIsEnabled("enabled_notification_listeners"),
            "When on, Atlas can inspect notifications from your apps.",
            Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS
        ),
        SpecialAccessStatus(
            "Screen control",
            serviceIsEnabled(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
            "When on, Atlas can read and interact with screen content for requested actions.",
            Settings.ACTION_ACCESSIBILITY_SETTINGS
        ),
        SpecialAccessStatus(
            "App usage",
            usageAccess,
            "When on, Atlas can see which apps are in use.",
            Settings.ACTION_USAGE_ACCESS_SETTINGS
        ),
        SpecialAccessStatus(
            "Draw over other apps",
            Settings.canDrawOverlays(context),
            "When on, Atlas can display its floating overlay above other apps.",
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            packageScopedAction = true
        ),
        SpecialAccessStatus(
            "Exact alarms",
            Build.VERSION.SDK_INT < 31 || alarmManager?.canScheduleExactAlarms() == true,
            "When allowed, Atlas can schedule time-sensitive alarms.",
            Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
            packageScopedAction = true
        ),
        SpecialAccessStatus(
            "Install other apps",
            context.packageManager.canRequestPackageInstalls(),
            "When allowed, Atlas can install an app package after your confirmation.",
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            packageScopedAction = true
        )
    ).filter {
        Build.VERSION.SDK_INT >= 31 ||
            it.settingsAction != Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM
    }
}
