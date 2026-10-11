package com.nizam.app.feature.shield

import android.content.Context
import android.content.Intent

/**
 * Focus shield: while active, opening a blocked app sends you straight back home.
 * Stored in SharedPreferences because the accessibility service reads it on every app switch,
 * synchronously — DataStore's async reads would let the blocked app flash open first.
 */
object Shield {
    private fun prefs(c: Context) = c.getSharedPreferences("shield", Context.MODE_PRIVATE)

    fun blocked(c: Context): Set<String> = prefs(c).getStringSet("apps", emptySet()) ?: emptySet()
    fun setBlocked(c: Context, apps: Set<String>) = prefs(c).edit().putStringSet("apps", apps).apply()
    fun activeUntil(c: Context) = prefs(c).getLong("until", 0L)
    fun isActive(c: Context) = System.currentTimeMillis() < activeUntil(c)
    fun activate(c: Context, minutes: Int) =
        prefs(c).edit().putLong("until", maxOf(activeUntil(c), System.currentTimeMillis() + minutes * 60_000L)).apply()
    fun stop(c: Context) = prefs(c).edit().putLong("until", 0L).apply()
    fun duringFocus(c: Context) = prefs(c).getBoolean("focus", true)
    fun setDuringFocus(c: Context, on: Boolean) = prefs(c).edit().putBoolean("focus", on).apply()
    fun duringPrayer(c: Context) = prefs(c).getBoolean("prayer", false)
    fun setDuringPrayer(c: Context, on: Boolean) = prefs(c).edit().putBoolean("prayer", on).apply()

    /** Launchable apps as (label, package), Atlas itself excluded. */
    fun apps(c: Context): List<Pair<String, String>> {
        val pm = c.packageManager
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .filter { it.second != c.packageName }.distinctBy { it.second }.sortedBy { it.first.lowercase() }
    }
}
