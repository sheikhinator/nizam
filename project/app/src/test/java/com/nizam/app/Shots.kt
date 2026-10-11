package com.nizam.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Canvas
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.nizam.app.ui.nav.NizamApp
import com.nizam.app.ui.theme.NizamTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders Atlas's main screens to PNGs on the build server (no phone needed), so the design can be
 * reviewed screen by screen. Run: gradle testDebugUnitTest --tests 'com.nizam.app.Shots'
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class Shots(private val route: String, private val dark: Boolean) {

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-{1}")
        fun params(): List<Array<Any>> {
            val routes = (System.getenv("SHOT_ROUTES") ?: "today,nx/chief,nx/appchat,nx/flows,nx/roads,nx/trustboard,nx/moodweather,nx/loan2,life")
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            return routes.map { arrayOf<Any>(it, true) } + listOf("today", "modules").filter { it in routes }.map { arrayOf<Any>(it, false) }
        }
    }

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun shot() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val container = AppContainer(app)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            NizamTheme(darkTheme = dark) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    NizamApp(container, startRoute = route)
                }
            }
        }
        repeat(30) { rule.mainClock.advanceTimeBy(100) }
        // copy the window's hardware-rendered pixels; waiting for "idle" never ends on screens with living animations
        val view = rule.activity.window.decorView
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        var done = false
        android.view.PixelCopy.request(rule.activity.window, bmp, { done = true }, android.os.Handler(android.os.Looper.getMainLooper()))
        repeat(50) { if (!done) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); rule.mainClock.advanceTimeBy(16) } }
        val dir = File(System.getProperty("user.dir"), "build/shots").apply { mkdirs() }
        File(dir, "${route.replace('/', '_')}-${if (dark) "dark" else "light"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
