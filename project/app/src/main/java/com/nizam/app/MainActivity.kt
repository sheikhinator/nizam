package com.nizam.app

import android.os.Bundle
import androidx.compose.ui.graphics.luminance
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.foundation.isSystemInDarkTheme
import com.nizam.app.data.prefs.ThemeMode
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.setValue
import com.nizam.app.feature.backup.ShareInboxScreen
import com.nizam.app.feature.backup.sharedPayloadFrom
import com.nizam.app.feature.lock.LockScreen
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.ui.nav.NizamApp
import com.nizam.app.ui.theme.NizamTheme

class MainActivity : ComponentActivity() {

    companion object {
        const val ACTION_OPEN_CURATOR = "com.nizam.app.OPEN_CURATOR"
        const val ACTION_OPEN_MUSIC = "com.nizam.app.OPEN_MUSIC"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        if (savedInstanceState == null) window.decorView.post { nfcTag(intent) }
        super.onCreate(savedInstanceState)
        com.nizam.app.feature.security.Security.applyPrivacyScreen(this)
        enableEdgeToEdge()
        val container = (application as NizamApplication).container
        // non-essential start-up work runs after the first frame, off the main thread
        val app = applicationContext
        window.decorView.post {
            Thread {
                runCatching { Notifications.createChannels(app) }
                runCatching { com.nizam.app.feature.system.Shortcuts.publish(app) }
                runCatching { com.nizam.app.feature.world.Legacy.touch(app) }
            }.apply { priority = Thread.MIN_PRIORITY }.start()
        }
        val shared = sharedPayloadFrom(intent)
        val openCurator = intent?.action == ACTION_OPEN_CURATOR ||
            intent?.action == android.content.Intent.ACTION_ASSIST
        // Hands-free resumes whenever the app is opened (Android won't start a mic service from the background)
        if (com.nizam.app.feature.jarvis.WakeService.isOn(this)) com.nizam.app.feature.jarvis.WakeService.start(this)
        // offline AI: set it up once on Wi-Fi if there's none, and preload it so the first offline answer is quick
        window.decorView.postDelayed({ runCatching { com.nizam.app.net.ModelHub.autoSetup(this) } }, 2500)
        if (com.nizam.app.feature.future.Guardian.crash(this)) com.nizam.app.feature.future.GuardianService.start(this)
        lifecycleScope.launch { runCatching { com.nizam.app.net.ApiKeys.seed(this@MainActivity, container.settingsRepository); com.nizam.app.net.ApiKeys.migrateModels(container.settingsRepository) }
            // warm the connection to the first AI source and learn each provider's models (once a day)
            runCatching { com.nizam.app.net.Router.warm(container.settingsRepository, applicationContext) } }
        if (com.nizam.app.net.LocalModel.available(this) && com.nizam.app.net.ModelHub.ramGb(this) >= 5.5)
            Thread { runCatching { Thread.sleep(4000); com.nizam.app.net.LocalModel.warm(applicationContext) } }.apply { priority = Thread.MIN_PRIORITY }.start()
        lifecycleScope.launch {
            com.nizam.app.feature.phone.AtlasAccessibilityService.enabledByUser =
                container.settingsRepository.screenControl.first()
        }

        setContent {
            val mode by container.settingsRepository.themeMode.collectAsState(initial = ThemeMode.SYSTEM)
            val dark = when (mode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
            }
            val pin by container.settingsRepository.appPin.collectAsState(initial = "")
            var unlocked by remember { mutableStateOf(false) }
            // re-lock when Atlas has been in the background for more than a minute
            val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
            androidx.compose.runtime.DisposableEffect(lifecycle) {
                var leftAt = 0L
                val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                    if (e == androidx.lifecycle.Lifecycle.Event.ON_STOP) leftAt = System.currentTimeMillis()
                    if (e == androidx.lifecycle.Lifecycle.Event.ON_START && leftAt > 0 && System.currentTimeMillis() - leftAt > com.nizam.app.feature.security.Security.lockAfterMs(this@MainActivity)) unlocked = false
                }
                lifecycle.addObserver(obs); onDispose { lifecycle.removeObserver(obs) }
            }

            val palette by container.settingsRepository.accentHex.collectAsState(initial = "")
            val customPalette by container.settingsRepository.customPalette.collectAsState(initial = "")
            // An explicit Light/Dark choice overrides a palette of the opposite brightness, so
            // "switch to dark" never silently does nothing.
            val paletteIsLight = palette.isNotBlank() && palette != "custom" &&
                com.nizam.app.ui.theme.paletteFor(palette).surface.luminance() > 0.5f
            val effectivePalette = when {
                palette.isBlank() -> ""
                mode == ThemeMode.LIGHT && !paletteIsLight && palette != "custom" -> "paper"
                mode == ThemeMode.DARK && paletteIsLight -> "void"
                else -> palette
            }
            NizamTheme(darkTheme = dark, paletteKey = effectivePalette, customPaletteJson = customPalette) {
              // A Surface supplies background AND text colour. Screens drawn outside the Scaffold
              // (onboarding, lock, share) had neither, so their text rendered black on black.
              androidx.compose.material3.Surface(
                  modifier = androidx.compose.ui.Modifier.fillMaxSize().systemBarsPadding(),
                  color = androidx.compose.material3.MaterialTheme.colorScheme.background
              ) {
                var sharedHandled by remember { mutableStateOf(false) }
                var lost by remember { mutableStateOf(com.nizam.app.feature.next.LostMode.active(this@MainActivity)) }
                var safeDone by remember { mutableStateOf(false) }
                androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(25_000); com.nizam.app.feature.safe.CrashGuard.markStable(this@MainActivity) }
                // On tablets, foldables and landscape the app stays a readable column in the middle
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter) {
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.widthIn(max = 760.dp).fillMaxSize()) {
                when {
                    !safeDone && com.nizam.app.feature.safe.CrashGuard.shouldSafeMode(this@MainActivity) ->
                        com.nizam.app.feature.safe.SafeModeScreen(container) { safeDone = true }
                    shared != null && !sharedHandled ->
                        ShareInboxScreen(container, shared) { sharedHandled = true }
                    lost -> com.nizam.app.feature.next.LostScreen { lost = false }
                    pin.isNotBlank() && !unlocked ->
                        LockScreen(correctPin = pin, onUnlocked = { unlocked = true })
                    else -> androidx.compose.foundation.layout.Box {
                        NizamApp(container, startOnCurator = openCurator,
                            startRoute = intent?.getStringExtra("route") ?: com.nizam.app.feature.agentic.ToolServer.routeFrom(this@MainActivity, intent) ?: if (intent?.action == ACTION_OPEN_MUSIC) com.nizam.app.ui.nav.Routes.MUSIC else null)
                        com.nizam.app.feature.agentic.HaloHost(androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.TopCenter))
                    }
                }
                }
                }
              }
            }
        }
    }

    // a video that is playing floats in a small window when you leave the app
    // hardware keyboards (tablets, DeX-style docks, Bluetooth keyboards): Ctrl+K search, Alt+1–5 tabs, Esc back
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        (event.action == android.view.KeyEvent.ACTION_DOWN && com.nizam.app.ui.nav.KeyNav.handle(event)) || super.dispatchKeyEvent(event)

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (com.nizam.app.feature.cinema.Pip.armed && android.os.Build.VERSION.SDK_INT < 31) com.nizam.app.feature.cinema.Pip.enter(this)
    }
    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        com.nizam.app.feature.cinema.Pip.active = isInPictureInPictureMode
    }
    /** An Atlas NFC sticker was tapped: run what it's bound to. */
    private fun nfcTag(i: android.content.Intent?) {
        val u = i?.data ?: return
        if (u.scheme == "atlas" && u.host == "tag") u.lastPathSegment?.let { com.nizam.app.feature.wild.Triggers.fire(this, "nfc", it, "connect") }
    }

    // a widget card or notification tapped while Atlas is already open goes straight to its screen
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (intent.getStringExtra("route") ?: com.nizam.app.feature.agentic.ToolServer.routeFrom(this, intent))?.let { r -> com.nizam.app.ui.screens.search.AtlasNav.go?.invoke(r) }
        nfcTag(intent)
    }

    // Ambient mode: plugging in while Atlas is open turns it into a bedside display (if chosen)
    private val power = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: android.content.Intent) {
            if (com.nizam.app.ui.design.Comfort.get(c).ambientOnCharge) com.nizam.app.ui.screens.search.AtlasNav.go?.invoke(com.nizam.app.ui.nav.Routes.AMBIENT)
        }
    }
    override fun onResume() { super.onResume(); runCatching { com.nizam.app.feature.wild.Clip.capture(this) }; runCatching { registerReceiver(power, android.content.IntentFilter(android.content.Intent.ACTION_POWER_CONNECTED)) } }
    override fun onPause() { runCatching { unregisterReceiver(power) }; super.onPause() }

    // keep home-screen widgets current with whatever changed while Atlas was open
    override fun onStop() {
        super.onStop()
        lifecycleScope.launch { runCatching { com.nizam.app.feature.widget.MoneyWidget.refresh(applicationContext) }
            runCatching { com.nizam.app.feature.widget.MyWidget.refresh(applicationContext) }
            runCatching { com.nizam.app.feature.widget.SmartStackWidget.refresh(applicationContext) } }
    }
}
