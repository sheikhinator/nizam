package com.nizam.app.feature.attention

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Settings + held notifications, in SharedPreferences so the listener can read them synchronously. */
object Attention {
    private fun p(c: Context) = c.getSharedPreferences("attention", Context.MODE_PRIVATE)
    fun digestOn(c: Context) = p(c).getBoolean("digest", false)
    fun setDigest(c: Context, on: Boolean) = p(c).edit().putBoolean("digest", on).apply()
    fun heldApps(c: Context): Set<String> = p(c).getStringSet("heldApps", emptySet()) ?: emptySet()
    fun setHeldApps(c: Context, s: Set<String>) = p(c).edit().putStringSet("heldApps", s).apply()
    fun windDownHour(c: Context) = p(c).getInt("windHour", -1)
    fun setWindDown(c: Context, hour: Int) = p(c).edit().putInt("windHour", hour).apply()
    fun eyeBreaks(c: Context) = p(c).getBoolean("eyes", false)
    fun setEyeBreaks(c: Context, on: Boolean) = p(c).edit().putBoolean("eyes", on).apply()

    fun hold(c: Context, app: String, title: String, text: String) {
        val a = runCatching { JSONArray(p(c).getString("held", "[]")) }.getOrElse { JSONArray() }
        a.put(JSONObject().put("app", app).put("title", title).put("text", text.take(200)))
        p(c).edit().putString("held", a.toString()).apply()
    }
    fun takeHeld(c: Context): List<String> {
        val a = runCatching { JSONArray(p(c).getString("held", "[]")) }.getOrElse { JSONArray() }
        p(c).edit().putString("held", "[]").apply()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { "${it.optString("app")}: ${it.optString("title")} — ${it.optString("text")}" }
    }
    fun lastEyeBreak(c: Context) = p(c).getLong("lastEye", 0)
    fun markEyeBreak(c: Context) = p(c).edit().putLong("lastEye", System.currentTimeMillis()).apply()
    fun windDoneOn(c: Context) = p(c).getString("windDone", "")
    fun markWind(c: Context, day: String) = p(c).edit().putString("windDone", day).apply()
}
