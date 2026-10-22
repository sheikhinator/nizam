package com.nizam.app.feature.code

import android.content.Context
import android.util.Base64
import com.nizam.app.feature.linux.LinuxEnv
import com.nizam.app.feature.next.JsonStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Your repositories on the phone. A project is a plain folder (shared with the terminal at /root/projects/<name>);
 * Atlas remembers the GitHub commit it came from, so it can show what changed, commit and push through GitHub's API
 * (no git needed), open pull requests and follow CI.
 */
object Workspace {
    private val http = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private const val API = "https://api.github.com"

    // ── the token ─────────────────────────────────────────────────────────────────
    private fun secret(c: Context) = JsonStore(c, "code_secret", sealed = true)
    fun token(c: Context): String = runCatching { secret(c).all().firstOrNull()?.optString("t") }.getOrNull()?.ifBlank { null }
        ?: com.nizam.app.feature.chief.ChiefStore.githubToken(c)
    fun setToken(c: Context, t: String) { secret(c).save(listOf(JSONObject().put("id", "t").put("t", t.trim()))) }

    private fun req(c: Context, path: String): Request.Builder = Request.Builder().url(if (path.startsWith("http")) path else API + path)
        .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28").header("User-Agent", "Atlas-Code")
        .apply { token(c).takeIf { it.isNotBlank() }?.let { header("Authorization", "Bearer $it") } }

    private suspend fun call(c: Context, path: String, method: String = "GET", body: JSONObject? = null): String = withContext(Dispatchers.IO) {
        val b = req(c, path).method(method, body?.toString()?.toRequestBody("application/json".toMediaType())).build()
        http.newCall(b).execute().use { r ->
            val text = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(text).optString("message") }.getOrDefault("").ifBlank { text.take(200) }
                throw IllegalStateException("GitHub ${r.code}: $msg" + if (r.code == 401) " (check your token)" else "")
            }
            text
        }
    }
    suspend fun get(c: Context, path: String) = call(c, path)
    suspend fun post(c: Context, path: String, body: JSONObject) = call(c, path, "POST", body)
    suspend fun patch(c: Context, path: String, body: JSONObject) = call(c, path, "PATCH", body)

    suspend fun me(c: Context): String = JSONObject(get(c, "/user")).optString("login")

    // ── projects on this phone ──────────────────────────────────────────────────────
    fun dir(c: Context, name: String) = File(LinuxEnv.projects(c), name)
    private fun meta(c: Context, name: String) = File(File(c.filesDir, "code/meta").apply { mkdirs() }, "$name.json")
    private fun baseDir(c: Context, name: String) = File(c.filesDir, "code/base/$name")
    fun info(c: Context, name: String): JSONObject? = runCatching { JSONObject(meta(c, name).readText()) }.getOrNull()
    private fun saveInfo(c: Context, name: String, o: JSONObject) = meta(c, name).writeText(o.toString())

    /** Every project folder, with its GitHub repo if it has one. */
    fun local(c: Context): List<JSONObject> = LinuxEnv.projects(c).listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith(".") }
        .map { f -> info(c, f.name) ?: JSONObject().put("name", f.name) }.sortedByDescending { it.optLong("opened") }

    fun touch(c: Context, name: String) { val o = info(c, name) ?: JSONObject().put("name", name); saveInfo(c, name, o.put("opened", System.currentTimeMillis())) }

    suspend fun repos(c: Context): List<JSONObject> {
        val a = JSONArray(get(c, "/user/repos?per_page=100&sort=updated&affiliation=owner,collaborator,organization_member"))
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    /** Downloads a repository (one branch) into its project folder. */
    suspend fun clone(c: Context, full: String, branch: String? = null, progress: (String) -> Unit = {}): String = withContext(Dispatchers.IO) {
        val repo = JSONObject(get(c, "/repos/$full"))
        val br = branch ?: repo.optString("default_branch", "main")
        val head = JSONObject(get(c, "/repos/$full/branches/$br")).getJSONObject("commit").optString("sha")
        val name = full.substringAfter('/')
        val d = dir(c, name); val base = baseDir(c, name)
        progress("Downloading $full ($br)…")
        val zip = File(c.cacheDir, "code-$name.zip")
        http.newCall(req(c, "/repos/$full/zipball/$head").build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("GitHub ${r.code} while downloading")
            r.body!!.byteStream().use { i -> zip.outputStream().use { o -> i.copyTo(o) } }
        }
        progress("Unpacking…")
        d.deleteRecursively(); base.deleteRecursively(); d.mkdirs(); base.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val rel = e.name.substringAfter('/', "")
                if (rel.isNotBlank() && !rel.contains("..")) {
                    if (e.isDirectory) { File(d, rel).mkdirs(); File(base, rel).mkdirs() }
                    else { val f = File(d, rel); f.parentFile?.mkdirs(); f.outputStream().use { z.copyTo(it) }; File(base, rel).also { it.parentFile?.mkdirs() }.let { f.copyTo(it, true) }
                        if (rel.endsWith(".sh") || rel == "gradlew") f.setExecutable(true) }
                }
                e = z.nextEntry
            }
        }
        zip.delete()
        saveInfo(c, name, JSONObject().put("name", name).put("repo", full).put("branch", br).put("base", head).put("private", repo.optBoolean("private")).put("opened", System.currentTimeMillis()))
        name
    }

    /** A brand-new repository on GitHub (with a README), downloaded and ready. */
    suspend fun createRepo(c: Context, name: String, description: String, private: Boolean): String {
        val o = JSONObject(post(c, "/user/repos", JSONObject().put("name", name).put("description", description).put("private", private).put("auto_init", true)))
        return clone(c, o.optString("full_name"))
    }

    /** A folder that is not on GitHub yet. */
    fun createLocal(c: Context, name: String): String { dir(c, name).mkdirs(); touch(c, name); return name }

    // ── what changed ────────────────────────────────────────────────────────────────
    private val SKIP_DIRS = setOf(".git", "node_modules", "build", ".gradle", "dist", "__pycache__", ".idea", "venv", ".venv", "target", ".next", ".cache", ".pytest_cache")

    private fun ignores(root: File): List<Regex> = runCatching { File(root, ".gitignore").readLines() }.getOrDefault(emptyList())
        .map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("!") }
        .map { p -> Regex("(^|.*/)" + glob(p.trimStart('/').trimEnd('/')) + "(/.*)?$") }

    /** A gitignore-style pattern as a regular expression (* within a folder, ** across folders, ? one character). */
    fun glob(g: String): String = buildString {
        var i = 0
        while (i < g.length) {
            val ch = g[i]
            when {
                ch == '*' && i + 1 < g.length && g[i + 1] == '*' -> { append(".*"); i++ ; if (i + 1 < g.length && g[i + 1] == '/') i++ }
                ch == '*' -> append("[^/]*")
                ch == '?' -> append("[^/]")
                ch.isLetterOrDigit() || ch == '/' -> append(ch)
                else -> append('\\').append(ch)
            }
            i++
        }
    }

    /** Every file in the project (relative paths), skipping build output and ignored files. */
    fun files(root: File): List<String> {
        val ig = ignores(root)
        val out = mutableListOf<String>()
        fun walk(f: File, rel: String) {
            f.listFiles().orEmpty().sortedBy { it.name }.forEach { ch ->
                val r = if (rel.isEmpty()) ch.name else "$rel/${ch.name}"
                if (ch.isDirectory) { if (ch.name !in SKIP_DIRS && ig.none { it.matches(r) }) walk(ch, r) }
                else if (ig.none { it.matches(r) }) out += r
            }
        }
        walk(root, ""); return out
    }

    private fun same(a: File, b: File): Boolean = a.length() == b.length() && (a.length() > 4_000_000 || a.readBytes().contentEquals(b.readBytes()))

    data class Change(val path: String, val kind: String) // added | modified | deleted

    fun status(c: Context, name: String): List<Change> {
        val d = dir(c, name); val base = baseDir(c, name)
        if (!base.exists()) return files(d).map { Change(it, "added") }
        val now = files(d).toSet(); val was = files(base).toSet()
        return (now - was).map { Change(it, "added") } + now.intersect(was).filter { !same(File(d, it), File(base, it)) }.map { Change(it, "modified") } +
            (was - now).map { Change(it, "deleted") }
    }

    fun original(c: Context, name: String, path: String): String? = File(baseDir(c, name), path).takeIf { it.isFile }?.readText()

    /** Puts a file back the way it was at the last commit. */
    fun revert(c: Context, name: String, path: String) {
        val o = File(baseDir(c, name), path); val f = File(dir(c, name), path)
        if (o.isFile) { f.parentFile?.mkdirs(); o.copyTo(f, true) } else f.delete()
    }

    // ── committing through GitHub ─────────────────────────────────────────────────────
    /** Commits every change and pushes it (to the project's branch, or a new one). Returns the commit's short id. */
    suspend fun commit(c: Context, name: String, message: String, newBranch: String? = null): String = withContext(Dispatchers.IO) {
        val i = info(c, name) ?: throw IllegalStateException("This project isn't linked to GitHub. Create a repo for it first.")
        val full = i.optString("repo"); val baseSha = i.optString("base")
        val changes = status(c, name)
        if (changes.isEmpty()) throw IllegalStateException("Nothing to commit.")
        val baseCommit = JSONObject(get(c, "/repos/$full/git/commits/$baseSha"))
        val entries = JSONArray()
        changes.forEach { ch ->
            if (ch.kind == "deleted") entries.put(JSONObject().put("path", ch.path).put("mode", "100644").put("type", "blob").put("sha", JSONObject.NULL))
            else {
                val f = File(dir(c, name), ch.path)
                val blob = JSONObject(post(c, "/repos/$full/git/blobs", JSONObject().put("content", Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)).put("encoding", "base64")))
                entries.put(JSONObject().put("path", ch.path).put("mode", if (f.canExecute() && !f.name.contains('.')) "100755" else "100644").put("type", "blob").put("sha", blob.optString("sha")))
            }
        }
        val tree = JSONObject(post(c, "/repos/$full/git/trees", JSONObject().put("base_tree", baseCommit.getJSONObject("tree").optString("sha")).put("tree", entries)))
        val commit = JSONObject(post(c, "/repos/$full/git/commits", JSONObject().put("message", message).put("tree", tree.optString("sha")).put("parents", JSONArray().put(baseSha))))
        val sha = commit.optString("sha")
        val branch = newBranch?.takeIf { it.isNotBlank() } ?: i.optString("branch")
        if (newBranch.isNullOrBlank()) {
            try { patch(c, "/repos/$full/git/refs/heads/$branch", JSONObject().put("sha", sha).put("force", false)) }
            catch (e: IllegalStateException) { if (e.message?.contains("422") == true) throw IllegalStateException("GitHub has newer commits on $branch. Tap Sync to bring them in, then commit again."); throw e }
        } else post(c, "/repos/$full/git/refs", JSONObject().put("ref", "refs/heads/$branch").put("sha", sha))
        // this commit is now the base
        val d = dir(c, name); val base = baseDir(c, name)
        changes.forEach { ch -> val b = File(base, ch.path); if (ch.kind == "deleted") b.delete() else { b.parentFile?.mkdirs(); File(d, ch.path).copyTo(b, true) } }
        saveInfo(c, name, i.put("base", sha).put("branch", branch))
        sha.take(7)
    }

    /** Brings in new commits from GitHub. Your own unsaved changes are kept; files changed on both sides are listed. */
    suspend fun sync(c: Context, name: String): String = withContext(Dispatchers.IO) {
        val i = info(c, name) ?: throw IllegalStateException("Not linked to GitHub.")
        val full = i.optString("repo"); val br = i.optString("branch")
        val head = JSONObject(get(c, "/repos/$full/branches/$br")).getJSONObject("commit").optString("sha")
        if (head == i.optString("base")) return@withContext "Already up to date."
        val cmp = JSONObject(get(c, "/repos/$full/compare/${i.optString("base")}...$head"))
        val files = cmp.optJSONArray("files") ?: JSONArray()
        val mine = status(c, name).map { it.path }.toSet()
        val conflicts = mutableListOf<String>(); var n = 0
        for (k in 0 until files.length()) {
            val f = files.getJSONObject(k); val path = f.optString("filename"); val st = f.optString("status")
            val prev = f.optString("previous_filename")
            if (path in mine) { conflicts += path; continue }
            val target = File(dir(c, name), path); val b = File(baseDir(c, name), path)
            if (st == "removed") { target.delete(); b.delete() }
            else {
                val raw = withContext(Dispatchers.IO) { http.newCall(req(c, "/repos/$full/contents/${path}?ref=$head").header("Accept", "application/vnd.github.raw").build()).execute().use { it.body!!.bytes() } }
                target.parentFile?.mkdirs(); target.writeBytes(raw); b.parentFile?.mkdirs(); b.writeBytes(raw)
                if (st == "renamed" && prev.isNotBlank()) { File(dir(c, name), prev).delete(); File(baseDir(c, name), prev).delete() }
            }
            n++
        }
        saveInfo(c, name, i.put("base", head))
        "Brought in $n file${if (n == 1) "" else "s"} from GitHub" + if (conflicts.isNotEmpty()) ". Changed on both sides (kept yours): ${conflicts.joinToString()}" else "."
    }

    suspend fun pullRequest(c: Context, name: String, title: String, body: String, base: String? = null): String {
        val i = info(c, name) ?: throw IllegalStateException("Not linked to GitHub.")
        val full = i.optString("repo")
        val target = base ?: JSONObject(get(c, "/repos/$full")).optString("default_branch")
        if (target == i.optString("branch")) throw IllegalStateException("You're on $target itself. Commit to a new branch first, then open the pull request.")
        val pr = JSONObject(post(c, "/repos/$full/pulls", JSONObject().put("title", title).put("body", body).put("head", i.optString("branch")).put("base", target)))
        return pr.optString("html_url")
    }

    // ── CI ─────────────────────────────────────────────────────────────────────────────
    suspend fun runs(c: Context, name: String, n: Int = 6): List<JSONObject> {
        val i = info(c, name) ?: return emptyList()
        val a = JSONObject(get(c, "/repos/${i.optString("repo")}/actions/runs?per_page=$n&branch=${i.optString("branch")}")).optJSONArray("workflow_runs") ?: JSONArray()
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    /** The end of a run's log (failed steps first), for reading or for the agent to fix. */
    suspend fun runLog(c: Context, name: String, runId: Long, tail: Int = 12_000): String = withContext(Dispatchers.IO) {
        val i = info(c, name) ?: return@withContext ""
        val full = i.optString("repo")
        val jobs = JSONObject(get(c, "/repos/$full/actions/runs/$runId/jobs")).optJSONArray("jobs") ?: JSONArray()
        buildString {
            for (k in 0 until jobs.length()) {
                val j = jobs.getJSONObject(k)
                append("== ${j.optString("name")}: ${j.optString("conclusion").ifBlank { j.optString("status") }}\n")
                val failed = (j.optJSONArray("steps") ?: JSONArray()).let { s -> (0 until s.length()).map { s.getJSONObject(it) }.filter { it.optString("conclusion") == "failure" }.map { it.optString("name") } }
                if (failed.isNotEmpty()) append("Failed steps: ${failed.joinToString()}\n")
                val log = runCatching { http.newCall(req(c, "/repos/$full/actions/jobs/${j.optLong("id")}/logs").build()).execute().use { it.body?.string().orEmpty() } }.getOrDefault("")
                val errors = log.lines().filter { Regex("(?i)(error|failed|exception|e: )").containsMatchIn(it) }.takeLast(60).joinToString("\n")
                if (errors.isNotBlank()) append("-- error lines --\n$errors\n")
                append("-- end of log --\n").append(log.takeLast(tail / maxOf(1, jobs.length()))).append('\n')
            }
        }
    }

    /** The newest release's APK, if the repo publishes one. */
    suspend fun latestApk(c: Context, name: String): Pair<String, String>? = runCatching {
        val i = info(c, name) ?: return null
        val r = JSONObject(get(c, "/repos/${i.optString("repo")}/releases/latest"))
        val a = r.optJSONArray("assets") ?: return null
        (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") }?.let { r.optString("tag_name") to it.optString("url") }
    }.getOrNull()

    suspend fun downloadAsset(c: Context, assetApiUrl: String, out: File, progress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        http.newCall(req(c, assetApiUrl).header("Accept", "application/octet-stream").build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("GitHub ${r.code} while downloading")
            val total = r.body!!.contentLength()
            r.body!!.byteStream().use { i -> out.outputStream().use { o -> val buf = ByteArray(1 shl 16); var done = 0L
                while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; if (total > 0) progress((done * 100 / total).toInt()) } } }
        }
        out
    }

    fun sha1(b: ByteArray): String = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }
}
