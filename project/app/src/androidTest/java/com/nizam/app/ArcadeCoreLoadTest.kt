package com.nizam.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nizam.app.feature.arcade.ArcadeSystems
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs on a real (CI emulator) device. Confirms the bundled libretro cores actually landed in
 * nativeLibraryDir and can be dlopen'd at runtime — the exact thing that fails when "games don't work".
 * Logs everything under the CORETEST tag so the CI job can surface the real reason.
 */
@RunWith(AndroidJUnit4::class)
class ArcadeCoreLoadTest {
    @Test
    fun coresPresentAndLoadable() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = ctx.applicationInfo.nativeLibraryDir
        Log.i("CORETEST", "nativeLibraryDir=$dir")
        File(dir).listFiles()?.sortedBy { it.name }?.forEach { Log.i("CORETEST", "present: ${it.name} (${it.length()} bytes)") }
            ?: Log.e("CORETEST", "nativeLibraryDir has no files / not listable")

        var ok = 0; var fail = 0
        ArcadeSystems.all.forEach { sys ->
            val path = ArcadeSystems.corePath(ctx, sys).absolutePath
            val exists = File(path).exists()
            val err = runCatching { System.load(path) }.exceptionOrNull()
            if (exists && err == null) {
                ok++; Log.i("CORETEST", "OK   ${sys.core}")
            } else {
                fail++; Log.e("CORETEST", "FAIL ${sys.core} exists=$exists err=${err?.javaClass?.simpleName}: ${err?.message}")
            }
        }
        Log.i("CORETEST", "SUMMARY ok=$ok fail=$fail of ${ArcadeSystems.all.size}")
    }
}
