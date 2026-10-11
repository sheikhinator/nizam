package com.nizam.app.feature.growth

/**
 * Streaks with insurance. Every 7 active days in a row earns a freeze (max 3). A missed day spends
 * a freeze instead of breaking the run — so one sick day doesn't wipe a month of work.
 *
 * Today is never held against you: if you haven't logged yet, the streak counts from yesterday.
 */
data class StreakState(val current: Int, val best: Int, val freezes: Int, val bridged: Int)

object Streak {
    /** [activeNewestFirst] index 0 = today. */
    fun compute(activeNewestFirst: List<Boolean>): StreakState {
        val chrono = activeNewestFirst.drop(if (activeNewestFirst.firstOrNull() == false) 1 else 0).reversed()
        var run = 0; var best = 0; var freezes = 0; var sinceEarn = 0; var bridged = 0; var bridgedInRun = 0
        for (active in chrono) {
            if (active) {
                run++; sinceEarn++
                if (sinceEarn >= 7) { freezes = minOf(3, freezes + 1); sinceEarn = 0 }
            } else if (run > 0 && freezes > 0) {
                freezes--; bridged++; bridgedInRun++          // the run survives; the day itself isn't counted
            } else {
                run = 0; sinceEarn = 0; bridgedInRun = 0
            }
            best = maxOf(best, run)
        }
        return StreakState(run, best, freezes, bridgedInRun)
    }
}
