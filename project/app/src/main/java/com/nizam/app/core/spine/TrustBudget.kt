package com.nizam.app.core.spine

import android.content.Context

/**
 * TrustBudget — what Atlas is allowed to do on its own, per domain.
 *
 * This is the thing that lets a living agent be *usable* rather than scary: the owner sets the
 * ceiling for each part of life, and Atlas never crosses it. Defaults are the safest reading —
 * Atlas may quietly notice and, at most, surface a gentle nudge; it never acts by itself until the
 * owner raises the ceiling. Even at ACT, anything irreversible still asks first (that rule lives in
 * the agent's approval loop, not here).
 */
object TrustBudget {
    /** observe = record only · notify = may surface a nudge · act = may take the action itself. */
    enum class Level { OBSERVE, NOTIFY, ACT }

    private fun prefs(c: Context) = c.getSharedPreferences("trust_budget", 0)

    /** Conservative by default everywhere: notice and nudge, never act unprompted. */
    private fun default(domain: String): Level = Level.NOTIFY

    fun level(c: Context, domain: String): Level =
        runCatching { Level.valueOf(prefs(c).getString("lvl_$domain", null) ?: default(domain).name) }
            .getOrElse { default(domain) }

    fun set(c: Context, domain: String, level: Level) {
        prefs(c).edit().putString("lvl_$domain", level.name).apply()
    }

    /** True if the owner has granted at least [need] for [domain]. */
    fun allows(c: Context, domain: String, need: Level): Boolean =
        level(c, domain).ordinal >= need.ordinal
}
