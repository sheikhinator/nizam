package com.nizam.app

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import com.nizam.app.feature.nx.Nx
import com.nizam.app.ui.nav.NizamApp
import com.nizam.app.ui.screens.hubs.HubRegistry
import com.nizam.app.ui.theme.NizamTheme
import org.junit.AfterClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Opens every screen in the app on the build server and records which ones crash, with the first line of
 * the app's own code in the stack. Run: gradle testDebugUnitTest --tests 'com.nizam.app.Audit'
 * Results are written to app/build/audit/screens.txt
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class Audit(private val route: String) {

    companion object {
        private val failures = java.util.Collections.synchronizedList(ArrayList<String>())
        private var opened = 0

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun params(): List<Array<Any>> {
            val from = System.getenv("AUDIT_FROM")?.toIntOrNull() ?: 0
            val to = System.getenv("AUDIT_TO")?.toIntOrNull() ?: Int.MAX_VALUE
            val all = (HubRegistry.all.map { it.route } + Nx.all.map { "nx/${it.id}" }).distinct()
            return all.drop(from).take((to - from).coerceAtLeast(0)).map { arrayOf<Any>(it) }
        }

        @JvmStatic
        @AfterClass
        fun report() {
            val dir = File(System.getProperty("user.dir"), "build/audit").apply { mkdirs() }
            File(dir, "screens.txt").writeText("Opened $opened screens, ${failures.size} crashed.\n" + failures.joinToString("\n"))
        }
    }

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun opens() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        try {
            val container = AppContainer(app)
            rule.mainClock.autoAdvance = false
            rule.setContent {
                NizamTheme(darkTheme = true) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { NizamApp(container, startRoute = route) }
                }
            }
            repeat(12) { rule.mainClock.advanceTimeBy(100) }
            opened++
        } catch (t: Throwable) {
            val root = generateSequence(t) { it.cause }.last()
            val frame = root.stackTrace.firstOrNull { it.className.startsWith("com.nizam.app") }?.let { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" } ?: "-"
            failures += "$route | ${root.javaClass.simpleName}: ${root.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(140)} | $frame"
        }
    }
}
