package com.nizam.app.feature.chief

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/** The few GitHub calls the Chief needs: read the code, push a change, watch the build, find the new APK. */
class GitHubApi(private val token: String, private val owner: String = OWNER, private val repo: String = REPO) {
    companion object {
        const val OWNER = "sheikhinator"
        const val REPO = "nizam"
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(180, TimeUnit.SECONDS).build()

    private fun builder(url: String) = Request.Builder().url(url)
        .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "Atlas-Chief")
        .apply { if (token.isNotBlank()) header("Authorization", "Bearer $token") }

    private suspend fun run(r: Request): String = withContext(Dispatchers.IO) {
        http.newCall(r).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw IllegalStateException("GitHub ${resp.code}: ${body.take(240)}")
            body
        }
    }

    private fun repoUrl(path: String) = "https://api.github.com/repos/$owner/$repo$path"
    suspend fun get(path: String): String = run(builder(repoUrl(path)).get().build())
    private suspend fun post(path: String, json: JSONObject): String = run(builder(repoUrl(path)).post(json.toString().toRequestBody(JSON)).build())
    private suspend fun patch(path: String, json: JSONObject): String = run(builder(repoUrl(path)).patch(json.toString().toRequestBody(JSON)).build())

    suspend fun headSha(branch: String): String? = runCatching { JSONObject(get("/git/ref/heads/$branch")).getJSONObject("object").getString("sha") }.getOrNull()

    /** Creates [branch] pointing at the same commit as [from], if it does not exist yet. */
    suspend fun ensureBranch(branch: String, from: String) {
        if (headSha(branch) != null) return
        val sha = headSha(from) ?: throw IllegalStateException("Branch $from was not found.")
        post("/git/refs", JSONObject().put("ref", "refs/heads/$branch").put("sha", sha))
    }

    /** Downloads the whole repo at [ref] as a zip into [dest]. */
    suspend fun downloadZip(ref: String, dest: File) = withContext(Dispatchers.IO) {
        val req = builder(repoUrl("/zipball/$ref")).header("Accept", "application/vnd.github+json").get().build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("GitHub ${resp.code} while downloading the code.")
            dest.parentFile?.mkdirs()
            resp.body!!.byteStream().use { input -> dest.outputStream().use { out -> input.copyTo(out) } }
        }
    }

    /** Commits [files] (path in repo -> content) on top of [branch] and moves the branch forward. Returns the new commit. */
    suspend fun push(branch: String, files: Map<String, ByteArray>, message: String): String {
        val parent = headSha(branch) ?: throw IllegalStateException("Branch $branch was not found.")
        val baseTree = JSONObject(get("/git/commits/$parent")).getJSONObject("tree").getString("sha")
        val tree = JSONArray()
        for ((path, bytes) in files) {
            val blob = JSONObject(post("/git/blobs", JSONObject().put("content", Base64.encodeToString(bytes, Base64.NO_WRAP)).put("encoding", "base64"))).getString("sha")
            tree.put(JSONObject().put("path", path).put("mode", "100644").put("type", "blob").put("sha", blob))
        }
        val newTree = JSONObject(post("/git/trees", JSONObject().put("base_tree", baseTree).put("tree", tree))).getString("sha")
        val commit = JSONObject(post("/git/commits", JSONObject().put("message", message).put("tree", newTree).put("parents", JSONArray().put(parent)))).getString("sha")
        patch("/git/refs/heads/$branch", JSONObject().put("sha", commit))
        return commit
    }

    /** Build runs for a commit: (status, conclusion, run number) of the newest one, or null if none has started. */
    suspend fun runFor(sha: String): Triple<String, String, Int>? {
        val arr = JSONObject(get("/actions/runs?head_sha=$sha")).optJSONArray("workflow_runs") ?: return null
        if (arr.length() == 0) return null
        val r = arr.getJSONObject(0)
        return Triple(r.optString("status"), r.optString("conclusion"), r.optInt("run_number"))
    }

    /** Short text from the failed build's error lines, so Chief can explain what went wrong. */
    suspend fun failureHint(sha: String): String = runCatching {
        val runId = JSONObject(get("/actions/runs?head_sha=$sha")).getJSONArray("workflow_runs").getJSONObject(0).getLong("id")
        val jobs = JSONObject(get("/actions/runs/$runId/jobs")).getJSONArray("jobs")
        val job = jobs.getJSONObject(0).getLong("id")
        val logs = withContext(Dispatchers.IO) {
            http.newCall(builder(repoUrl("/actions/jobs/$job/logs")).get().build()).execute().use { it.body?.string().orEmpty() }
        }
        logs.lineSequence().filter { it.contains("e: ") || it.contains("error:") }.map { it.substringAfter("Z ").trim() }.distinct().take(12).joinToString("\n")
    }.getOrDefault("")

    suspend fun releaseByTag(tag: String): JSONObject? = runCatching { JSONObject(get("/releases/tags/$tag")) }.getOrNull()

    /** The most recent published builds, newest first — for picking one to roll back to. */
    suspend fun recentReleases(limit: Int = 10): List<JSONObject> = runCatching {
        val arr = org.json.JSONArray(get("/releases?per_page=$limit"))
        (0 until arr.length()).map { arr.getJSONObject(it) }
    }.getOrDefault(emptyList())
}
