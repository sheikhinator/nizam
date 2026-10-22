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
class Audit(private val route: String, private val seeded: Boolean) {

    companion object {
        private val failures = java.util.Collections.synchronizedList(ArrayList<String>())
        private var opened = 0

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} seeded={1}")
        fun params(): List<Array<Any>> {
            val from = System.getenv("AUDIT_FROM")?.toIntOrNull() ?: 0
            val to = System.getenv("AUDIT_TO")?.toIntOrNull() ?: Int.MAX_VALUE
            val all = (HubRegistry.all.map { it.route } + Nx.all.map { "nx/${it.id}" }).distinct()
            // every screen twice: empty (a first-time user) and with real-looking data in every hub
            return all.drop(from).take((to - from).coerceAtLeast(0)).flatMap { listOf(arrayOf<Any>(it, false), arrayOf<Any>(it, true)) }
        }

        @JvmStatic
        @AfterClass
        fun report() {
            val dir = File(System.getProperty("user.dir"), "build/audit").apply { mkdirs() }
            val ok = File(dir, "screens_ok.txt").takeIf { it.exists() }?.readLines().orEmpty()
            val bad = File(dir, "screens_crash.txt").takeIf { it.exists() }?.readLines().orEmpty()
            File(dir, "screens.txt").writeText("Opened ${ok.size} screens, ${bad.size} crashed.\n" + bad.joinToString("\n"))
        }
    }

    /** Robolectric loads each test in its own class loader, so results go straight to files, not static lists. */
    private fun log(kind: String, line: String) {
        val dir = File(System.getProperty("user.dir"), "build/audit").apply { mkdirs() }
        synchronized(Audit::class.java) { File(dir, "screens_$kind.txt").appendText(line + "\n") }
    }

    private fun seed(c: AppContainer) = kotlinx.coroutines.runBlocking {
        val today = java.time.LocalDate.now()
        runCatching {
            for (d in 0 until 20) {
                val day = today.minusDays(d.toLong()).toString()
                c.financeRepository.addOn(day, 300.0 + d * 75, "EXPENSE", listOf("Food", "Fuel", "Bills", "Shopping")[d % 4], "seed $d")
                if (d % 7 == 0) c.financeRepository.addOn(day, 90000.0, "INCOME", "Salary", "")
            }
        }
        runCatching { listOf("Pay rent", "Call Ammi", "Finish report", "Buy groceries").forEachIndexed { i, t -> c.taskRepository.add(t, System.currentTimeMillis() + i * 86_400_000L, 1) } }
        runCatching { c.habitRepository.addHabit("Read 10 pages", 7); c.habitRepository.addHabit("Walk", 5) }
        runCatching { c.noteRepository.createFull("Meeting with Ali", "Discussed the Lahore project budget and next steps with Ali on Monday.") }
        runCatching { c.noteRepository.createFull("Ideas", "Start a small online shop. Learn Arabic. Visit Hunza in summer.") }
        // every tracker gets two entries
        com.nizam.app.feature.nx.Nx.all.filterIsInstance<com.nizam.app.feature.nx.LogSpec>().forEach { spec ->
            runCatching {
                val st = com.nizam.app.feature.next.JsonStore(c.appContext, "nxlog_${spec.id}", spec.sealed)
                repeat(2) { k ->
                    val o = org.json.JSONObject()
                    spec.fields.forEachIndexed { i, f ->
                        val v = when (f.kind) {
                            com.nizam.app.feature.nx.K.DATE -> today.minusDays(k.toLong()).toString()
                            com.nizam.app.feature.nx.K.NUMBER -> "${12 + k * 5}"
                            com.nizam.app.feature.nx.K.CHOICE -> f.options.firstOrNull().orEmpty()
                            else -> "Sample ${f.label} $k"
                        }
                        o.put(f.key, v); if (i == 0) o.put("_0", v)
                    }
                    st.add(o.put("t", System.currentTimeMillis()))
                }
            }
        }
    }

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun opens() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        try {
            val container = AppContainer(app)
            if (seeded) seed(container)
            rule.mainClock.autoAdvance = false
            rule.setContent {
                NizamTheme(darkTheme = true) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { NizamApp(container, startRoute = route) }
                }
            }
            repeat(12) { rule.mainClock.advanceTimeBy(100) }
            rule.waitForIdle()
            log("ok", "$route seeded=$seeded")
        } catch (t: Throwable) {
            val root = generateSequence(t) { it.cause }.last()
            val frames = root.stackTrace.filter { it.className.startsWith("com.nizam.app") }.take(3)
                .joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }.ifBlank { "-" }
            log("crash", "$route${if (seeded) " (with data)" else ""} | ${root.javaClass.simpleName}: ${root.message.orEmpty().lineSequence().firstOrNull().orEmpty().take(160)} | $frames")
        }
    }
}
