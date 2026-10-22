package com.nizam.app

import android.app.Application
import com.nizam.app.feature.agent.ArgFix
import com.nizam.app.feature.agent.LocalCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate

/** What the model or the user writes, against what Atlas's actions must receive. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MappingTest {
    private val log = StringBuilder()
    private fun <T> check(what: String, expected: T, actual: T) {
        log.append(if (expected == actual) "ok   " else "FAIL ").append(what).append(" → ").append(actual).append(" (want ").append(expected).append(")\n")
    }

    @Test fun mapping() {
        val today = LocalDate.now()
        check("number ~280", 280.0, ArgFix.number("~280"))
        check("number 280 kcal", 280.0, ArgFix.number("280 kcal"))
        check("number Rs 1,200", 1200.0, ArgFix.number("Rs 1,200"))
        check("number 2.5k", 2500.0, ArgFix.number("2.5k"))
        check("number 1.5 lakh", 150000.0, ArgFix.number("1.5 lakh"))
        check("number 250-300", 275.0, ArgFix.number("250-300"))
        check("number two", 2.0, ArgFix.number("two"))
        check("slot breakfast", "Breakfast", ArgFix.slot("breakfast"))
        check("slot LUNCH", "Lunch", ArgFix.slot("LUNCH"))
        check("slot iftar", "Dinner", ArgFix.slot("iftar"))
        check("slot snacks", "Snack", ArgFix.slot("snacks"))
        check("prayer Fajr", "fajr", ArgFix.prayer("Fajr"))
        check("prayer Zuhr", "dhuhr", ArgFix.prayer("Zuhr"))
        check("prayer Asar", "asr", ArgFix.prayer("Asar"))
        check("prayer Magrib", "maghrib", ArgFix.prayer("Magrib"))
        check("prayer Esha", "isha", ArgFix.prayer("Esha"))
        check("prayer nonsense", null, ArgFix.prayer("hello"))
        check("status on time", "ON_TIME", ArgFix.status("on time"))
        check("status missed", "NOT_PRAYED", ArgFix.status("missed"))
        check("status jamaat", "JAMAAH", ArgFix.status("with jamaat"))
        check("status qaza", "QADA", ArgFix.status("qaza"))
        check("mood good", 4, ArgFix.mood("good"))
        check("mood 8/10", 4, ArgFix.mood(8))
        check("date tomorrow", today.plusDays(1).toString(), ArgFix.date("tomorrow"))
        check("date 25/12/2026", "2026-12-25", ArgFix.date("25/12/2026"))
        check("date 5 Nov 2026", "2026-11-05", ArgFix.date("5 Nov 2026"))
        check("date ISO time", "2026-10-22", ArgFix.date("2026-10-22T10:00"))
        check("time 5pm", "17:00", ArgFix.time("5pm").toString())
        check("time 5:30 PM", "17:30", ArgFix.time("5:30 PM").toString())
        check("time 09:15:00", "09:15", ArgFix.time("09:15:00").toString())

        // phrases Atlas handles on the phone without the AI
        fun local(s: String) = LocalCommands.parse(s)?.let { it.action + " " + it.args }
        check("local: message Aisha", null, local("message Aisha I'm late"))
        check("local: how much water should I drink", null, local("how much water should I drink daily?"))
        check("local: spent 2 hours on homework", null, local("spent 2 hours on homework"))
        check("local: buy 12 eggs for 3", null, LocalCommands.parse("buy eggs 12 for 3")?.action)
        check("local: prayed fajr", "log_prayer", LocalCommands.parse("prayed fajr")?.action)
        check("local: missed asr", "NOT_PRAYED", LocalCommands.parse("missed asr")?.args?.optString("status"))
        check("local: drank 2 glasses of water", 500, LocalCommands.parse("drank 2 glasses of water")?.args?.optInt("ml"))
        check("local: bench 60 x 8", "log_set", LocalCommands.parse("bench 60 x 8")?.action)
        check("local: spent 500 on food", 500.0, LocalCommands.parse("spent 500 on food")?.args?.optDouble("amount"))

        val dir = File(System.getProperty("user.dir"), "build/audit").apply { mkdirs() }
        File(dir, "mapping.txt").writeText(log.toString())
        val fails = log.lines().filter { it.startsWith("FAIL") }
        assertEquals("Mapping failures:\n" + fails.joinToString("\n"), 0, fails.size)
        assertNull(null)
    }
}
