package com.nizam.app.feature.chief

import android.content.Context
import com.nizam.app.feature.next.JsonStore
import com.nizam.app.net.Providers
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/** Chief's settings, secrets, spending and the change waiting for approval. */
object ChiefStore {
    const val BRANCH = "atlas/live"
    const val SOURCE_BRANCH = "claude/amazing-mccarthy-xb78ta"

    private fun p(c: Context) = c.getSharedPreferences("chief", 0)
    private fun secret(c: Context) = JsonStore(c, "chief_secret", sealed = true)
    private val claudePreset get() = Providers.PRESETS.first { it.id == "claude" }

    fun githubToken(c: Context): String = runCatching { secret(c).all().firstOrNull()?.optString("gh") }.getOrNull().orEmpty()
    fun setGithubToken(c: Context, t: String) { runCatching { secret(c).save(listOf(JSONObject().put("id", "gh").put("gh", t.trim()))) } }
    fun claudeKey(c: Context): String = Providers.key(c, claudePreset)
    fun setClaudeKey(c: Context, k: String) = Providers.set(c, claudePreset, key = k)
    fun ready(c: Context) = claudeKey(c).isNotBlank() && githubToken(c).isNotBlank()

    // spending, in dollars, counted from what the API reports
    fun cap(c: Context) = p(c).getFloat("cap", 5f).toDouble()
    fun setCap(c: Context, v: Double) = p(c).edit().putFloat("cap", v.toFloat()).apply()
    fun jobCap(c: Context) = p(c).getFloat("jobcap", 1.5f).toDouble()
    private fun monthKey() = "spend_" + LocalDate.now().toString().take(7)
    fun spent(c: Context) = p(c).getFloat(monthKey(), 0f).toDouble()
    fun addSpend(c: Context, d: Double) = p(c).edit().putFloat(monthKey(), (spent(c) + d).toFloat()).apply()
    fun overBudget(c: Context) = spent(c) >= cap(c)

    fun smart(c: Context) = p(c).getBoolean("smart", true)
    fun setSmart(c: Context, v: Boolean) = p(c).edit().putBoolean("smart", v).apply()
    fun model(c: Context) = if (smart(c)) Claude.SONNET else Claude.HAIKU

    /** Last ready-to-install build and the last commit being built, so the screen can pick up after the app is closed. */
    fun lastSha(c: Context) = p(c).getString("sha", "").orEmpty()
    fun setLastSha(c: Context, s: String) = p(c).edit().putString("sha", s).apply()
    fun phase(c: Context) = p(c).getString("phase", "idle").orEmpty()
    fun setPhase(c: Context, s: String, detail: String = "") = p(c).edit().putString("phase", s).putString("detail", detail).apply()
    fun detail(c: Context) = p(c).getString("detail", "").orEmpty()

    // the change set waiting for an OK
    fun savePending(c: Context, summary: String, changes: Collection<Workspace.Change>) {
        val arr = JSONArray()
        changes.forEach { arr.put(JSONObject().put("path", it.path).put("content", it.content).put("orig", it.original ?: JSONObject.NULL)) }
        p(c).edit().putString("pending", JSONObject().put("summary", summary).put("files", arr).toString()).apply()
    }
    fun pendingSummary(c: Context): String? = runCatching { JSONObject(p(c).getString("pending", null) ?: return null).optString("summary") }.getOrNull()
    fun pendingFiles(c: Context): List<Workspace.Change> = runCatching {
        val a = JSONObject(p(c).getString("pending", null) ?: return emptyList()).getJSONArray("files")
        (0 until a.length()).map { a.getJSONObject(it) }.map { Workspace.Change(it.getString("path"), if (it.isNull("orig")) null else it.getString("orig"), it.getString("content")) }
    }.getOrDefault(emptyList())
    fun clearPending(c: Context) = p(c).edit().remove("pending").apply()
}
