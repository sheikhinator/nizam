package com.nizam.app.ui.nav

import android.content.Context
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Screens you choose stay locked behind your fingerprint, face or screen lock. One unlock lasts five minutes.
 */
object ReAuth {
    val GROUPS = listOf(
        Triple("money", "Money and bank texts", listOf(Routes.FINANCE, "nx/moneydesk", "nx/networth")),
        Triple("private", "Diary and notes", listOf(Routes.DIARY, Routes.NOTES)),
        Triple("capsules", "Time capsules", listOf("nx/capsule")),
        Triple("trust", "Trust Center, AI keys and settings", listOf("nx/trust", Routes.SETTINGS)),
    )
    private fun p(c: Context) = c.getSharedPreferences("reauth", 0)
    fun on(c: Context, group: String) = p(c).getBoolean(group, false)
    fun set(c: Context, group: String, v: Boolean) = p(c).edit().putBoolean(group, v).apply()
    var unlockedAt by mutableLongStateOf(0L)

    fun guarded(c: Context, route: String?): Boolean {
        if (route == null || System.currentTimeMillis() - unlockedAt < 5 * 60_000L) return false
        return GROUPS.any { (k, _, routes) -> on(c, k) && routes.any { route == it || route.startsWith("$it/") } }
    }

    @Suppress("DEPRECATION")
    private fun legacyCredential(b: android.hardware.biometrics.BiometricPrompt.Builder) { b.setDeviceCredentialAllowed(true) }

    fun prompt(c: Context, done: (Boolean) -> Unit) {
        val b = android.hardware.biometrics.BiometricPrompt.Builder(c).setTitle("Unlock").setSubtitle("This part of Atlas is protected")
        if (Build.VERSION.SDK_INT >= 30) b.setAllowedAuthenticators(android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_WEAK or
            android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL)
        else legacyCredential(b)
        runCatching {
            b.build().authenticate(android.os.CancellationSignal(), c.mainExecutor, object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(r: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) { unlockedAt = System.currentTimeMillis(); done(true) }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    // no screen lock at all: nothing to check against, so let them in
                    if (code == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_NO_DEVICE_CREDENTIAL ||
                        code == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT) { unlockedAt = System.currentTimeMillis(); done(true) } else done(false)
                }
            })
        }.onFailure { done(false) }
    }
}

/** Drawn over a protected screen until you unlock; it swallows every touch underneath. */
@Composable
fun ReAuthCover(onBack: () -> Unit) {
    val c = LocalContext.current
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
        .clickable(remember { MutableInteractionSource() }, null) {}.padding(32.dp),
        verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("🔒", style = MaterialTheme.typography.displayMedium)
        Text("Locked", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("You asked Atlas to protect this screen.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp))
        Button(onClick = { ReAuth.prompt(c) { } }) { Text("Unlock") }
        androidx.compose.material3.TextButton(onClick = onBack) { Text("Go back") }
    }
}

/** Settings rows for choosing what's protected. */
@Composable
fun ReAuthSettings() {
    val c = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Ask for your fingerprint or screen lock before opening:", style = MaterialTheme.typography.bodyMedium)
        remember(tick) { ReAuth.GROUPS }.forEach { (k, label, _) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f)); Switch(ReAuth.on(c, k), { ReAuth.set(c, k, it); tick++ })
            }
        }
        Text("One unlock covers five minutes.", style = MaterialTheme.typography.bodySmall)
    }
}
