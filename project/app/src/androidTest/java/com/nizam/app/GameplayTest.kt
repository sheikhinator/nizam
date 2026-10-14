package com.nizam.app

import android.content.Intent
import android.util.Log
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.nizam.app.feature.arcade.ArcadeSystems
import com.swordfish.libretrodroid.GLRetroView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs a real Game Boy game through the actual GLRetroView on the CI emulator and reports whether the
 * surface is created, frames render, and any error code — the exact "does a game actually play" path.
 */
@RunWith(AndroidJUnit4::class)
class GameplayTest {

    private val GB_LOGO = intArrayOf(
        0xCE,0xED,0x66,0x66,0xCC,0x0D,0x00,0x0B,0x03,0x73,0x00,0x83,0x00,0x0C,0x00,0x0D,
        0x00,0x08,0x11,0x1F,0x88,0x89,0x00,0x0E,0xDC,0xCC,0x6E,0xE6,0xDD,0xDD,0xD9,0x99,
        0xBB,0xBB,0x67,0x63,0x6E,0x0E,0xEC,0xCC,0xDD,0xDC,0x99,0x9F,0xBB,0xB9,0x33,0x3E
    )

    /** A minimal, valid 32KB Game Boy ROM: boots and loops forever. */
    private fun minimalGbRom(): ByteArray {
        val rom = ByteArray(0x8000)
        // entry at 0x100: nop; jp 0x0150
        rom[0x100] = 0x00; rom[0x101] = 0xC3.toByte(); rom[0x102] = 0x50; rom[0x103] = 0x01
        for (i in GB_LOGO.indices) rom[0x104 + i] = GB_LOGO[i].toByte()
        rom[0x147] = 0x00 // ROM only
        rom[0x148] = 0x00 // 32KB
        rom[0x149] = 0x00 // no RAM
        // header checksum over 0x134..0x14C
        var x = 0
        for (i in 0x134..0x14C) x = (x - (rom[i].toInt() and 0xFF) - 1) and 0xFF
        rom[0x14D] = x.toByte()
        // program at 0x150: jr -2 (infinite loop)
        rom[0x150] = 0x18; rom[0x151] = 0xFE.toByte()
        return rom
    }

    @Test
    fun gambattePlaysAGame() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val sys = ArcadeSystems.all.first { it.id == "gb" }
        val corePath = ArcadeSystems.corePath(ctx, sys).absolutePath
        Log.i("GAMETEST", "core=$corePath exists=${File(corePath).exists()}")
        val rom = File(ctx.cacheDir, "test.gb").apply { writeBytes(minimalGbRom()) }
        Log.i("GAMETEST", "rom=${rom.absolutePath} size=${rom.length()}")

        val events = mutableListOf<String>()
        val errors = mutableListOf<Int>()
        val scope = CoroutineScope(Dispatchers.Main)
        var jobs = listOf<Job>()

        val intent = Intent(ctx, GLTestActivity::class.java).apply {
            putExtra("core", corePath); putExtra("rom", rom.absolutePath)
        }
        val scenario = ActivityScenario.launch<GLTestActivity>(intent)
        scenario.onActivity { act ->
            val v = act.view
            if (v == null) { Log.e("GAMETEST", "view is null — GLRetroView did not construct"); return@onActivity }
            jobs = listOf(
                scope.launch { v.getGLRetroEvents().collect { e -> events.add(e.toString()); Log.i("GAMETEST", "event: $e") } },
                scope.launch { v.getGLRetroErrors().collect { c -> errors.add(c); Log.e("GAMETEST", "error code: $c (ERROR_LOAD_LIBRARY=${GLRetroView.ERROR_LOAD_LIBRARY})") } }
            )
        }
        // let it run ~6 seconds
        Thread.sleep(6000)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { jobs.forEach { it.cancel() } }
        Log.i("GAMETEST", "SUMMARY events=${events.size} (${events.distinct().take(6)}) errors=${errors.distinct()}")
        scenario.close()
    }
}
