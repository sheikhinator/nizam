package com.nizam.app

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
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
            val routes = (System.getenv("SHOT_ROUTES") ?: "debts,subscriptions,committees,medicines,docexpiry,duas,hadith,vehicle,finance")
                .split(',').map { it.trim() }.filter { it.isNotEmpty() }
            return routes.map { arrayOf<Any>(it, true) } + listOf("today", "modules", "rich").filter { it in routes }.map { arrayOf<Any>(it, false) }
        }
    }

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun shot() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val container = AppContainer(app)
        // "style:deck" etc: the home in that UI style
        val styled = route.startsWith("style:")
        com.nizam.app.ui.style.UiStyles.set(app, if (styled) com.nizam.app.ui.style.UiStyle.valueOf(route.removePrefix("style:").uppercase()) else com.nizam.app.ui.style.UiStyle.CLASSIC)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            NizamTheme(darkTheme = dark) {
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (route.startsWith("rich")) androidx.compose.foundation.layout.Box(Modifier.padding(18.dp)) {
                        com.nizam.app.ui.components.ChatText(if (route == "rich") RICH_A else RICH_B)
                    } else NizamApp(container, startRoute = if (styled) com.nizam.app.ui.nav.Routes.TODAY else route)
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
        File(dir, "${route.replace('/', '_').replace(':', '_')}-${if (dark) "dark" else "light"}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}

private const val RICH_A = """## Freelance writing start-up
You can start for about **Rs 9,200** and break even in the *second* month.

| Item | Cost (Rs) | Note |
|---|---|---|
| Domain & hosting | 2,500 | .pk domain |
| Branding | 3,500 | Fiverr designer |
| Accounting app | 1,200 | Monthly |
| Total | 7,200 | |

> [!TIP]
> Start with three sample articles in your niche before pitching.

```chart
{"type":"bar","title":"Monthly income plan","unit":"Rs","labels":["Month 1","Month 2","Month 3"],"values":[15000,32000,55000]}
```

```stats
[{"label":"Start-up cost","value":"Rs 9.2k"},{"label":"Break-even","value":"Month 2","note":"at 4 clients"}]
```"""

private const val RICH_B = """### How a client order flows
```mermaid
flowchart TD
A[Client brief] --> B{Clear?}
B -->|yes| C[Write draft]
B -->|no| D[Ask questions]
C --> E((Deliver))
```

```chart
{"type":"donut","title":"Where your time goes","labels":["Writing","Research","Admin"],"values":[60,25,15]}
```

```timeline
[{"when":"Week 1","title":"Portfolio","detail":"Three samples"},{"when":"Week 2","title":"Pitch 10 clients"},{"when":"Month 2","title":"Raise rates"}]
```

- [x] Register domain
- [ ] Open bank account

```next
[{"label":"Add pitch task for Monday","kind":"do","detail":"Add a task: pitch 10 clients, due next Monday"},{"label":"Open Tasks","kind":"open","detail":"Tasks"},{"label":"Search freelance rates","kind":"search","detail":"freelance writing rates Pakistan 2026"},{"label":"Draft my pitch email","kind":"ask","detail":"Draft a short pitch email for my writing services"}]
```"""
