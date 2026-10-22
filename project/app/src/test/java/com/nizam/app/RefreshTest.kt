package com.nizam.app

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.nizam.app.feature.safe.AtlasRefresh
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Refresh clears what it should, keeps what it must, and finishes. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RefreshTest {
    @Test fun refresh() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        com.nizam.app.net.Router.appContext = app
        val container = AppContainer(app)
        val old = System.currentTimeMillis() - 60 * 60_000L
        val junk = File(app.cacheDir, "old_share.jpg").apply { writeBytes(ByteArray(4096)); setLastModified(old) }
        val fresh = File(app.cacheDir, "in_use.jpg").apply { writeBytes(ByteArray(10)) }
        val part = File(app.filesDir, "arcade/roms/gb/game.part").apply { parentFile?.mkdirs(); writeBytes(ByteArray(2048)); setLastModified(old) }
        val note = File(app.filesDir, "vault/secret.md").apply { parentFile?.mkdirs(); writeText("keep me"); setLastModified(old) }
        val summary = kotlinx.coroutines.runBlocking { AtlasRefresh.run(container, null, quiet = true) }
        val log = "summary=$summary junk=${junk.exists()} fresh=${fresh.exists()} part=${part.exists()} vault=${note.exists()}"
        File(System.getProperty("user.dir"), "build/audit").apply { mkdirs() }.resolve("refresh.txt").writeText(log)
        assertTrue(log, summary.contains("top shape"))
        assertFalse(log, junk.exists()); assertTrue(log, fresh.exists()); assertFalse(log, part.exists()); assertTrue(log, note.exists())
    }
}
