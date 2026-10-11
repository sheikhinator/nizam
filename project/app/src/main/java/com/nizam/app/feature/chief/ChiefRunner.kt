package com.nizam.app.feature.chief

import android.content.Context
import com.nizam.app.feature.notify.Notifications
import com.nizam.app.feature.platform2.Updater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateListOf
import java.io.File
import java.time.Instant

/** Pushes the approved change, watches the build, and tells the user when the new version can be installed. */
object ChiefRunner {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var watching = false
    /** Bumped whenever the phase changes, so screens can refresh. */
    val tick = MutableStateFlow(0)

    /** What the Chief screen shows. It lives here, not in the screen, so work carries on if you leave the screen. */
    val log = mutableStateListOf<Step>()
    val running = MutableStateFlow(false)
    val costNow = MutableStateFlow(0.0)
    private var workspace: Workspace? = null
    private val conversation = ArrayList<Msg>()

    private fun ws(c: Context): Workspace = workspace ?: Workspace(Workspace.dir(c)).also { workspace = it }

    /** Starts a request in the background. */
    fun startJob(c: Context, request: String) {
        if (running.value) return
        val app = c.applicationContext
        running.value = true
        log += Step.User(request)
        scope.launch {
            try {
                val err = sync(app)
                if (err != null) { log += Step.Note(err); return@launch }
                val w = ws(app); w.changes.clear()
                val history = ArrayList<Msg>(conversation.takeLast(6)); history += Msg.User(request)
                execute(app, w, request, history, 0.0)
            } catch (t: Throwable) {
                log += Step.Note("That did not work: ${t.message}")
            } finally { running.value = false }
        }
    }

    /** Picks up a job that was interrupted (app closed, phone restarted). */
    fun resumeJob(c: Context) {
        val app = c.applicationContext
        if (running.value || !ChiefStore.hasJob(app)) return
        val saved = ChiefStore.job(app) ?: run { ChiefStore.clearJob(app); return }
        running.value = true
        scope.launch {
            try {
                val err = sync(app)
                if (err != null) { log += Step.Note(err); return@launch }
                val w = ws(app); w.changes.clear()
                saved.files.forEach { w.changes[it.path] = it }
                log += Step.Note("Picking up where I left off.")
                execute(app, w, saved.request, saved.history, saved.cost)
            } catch (t: Throwable) {
                log += Step.Note("That did not work: ${t.message}")
            } finally { running.value = false }
        }
    }

    private suspend fun execute(app: Context, w: Workspace, request: String, history: MutableList<Msg>, startCost: Double) {
        val agent = ChiefAgent(app, w, { step ->
            when (step) { is Step.Cost -> costNow.value = step.month; else -> log += step }
        }, { h, cost -> ChiefStore.saveJob(app, request, h, cost, w.changes.values) })
        ChiefStore.saveJob(app, request, history, startCost, w.changes.values)
        val summary = agent.run(history, startCost)
        log += Step.Say(summary)
        conversation += Msg.User(request); conversation += Msg.Asst(summary, emptyList())
        while (conversation.size > 8) conversation.removeAt(0)
        ChiefStore.clearJob(app)
        if (w.changes.isNotEmpty()) {
            ChiefStore.savePending(app, summary, w.changes.values)
            Notifications.post(app, Notifications.CHANNEL_NUDGE, 7902, "Chief has a change for your OK", summary.take(120))
        }
        tick.value = tick.value + 1
    }

    /** Called by the background worker: keeps unfinished work moving. */
    fun tick(c: Context) { resume(c); resumeJob(c) }

    private fun phase(c: Context, p: String, detail: String = "") { ChiefStore.setPhase(c, p, detail); tick.value = tick.value + 1 }

    /** Downloads the code if the branch moved. Returns an error text, or null when the workspace is ready. */
    suspend fun sync(c: Context, force: Boolean = false): String? = runCatching {
        val gh = GitHubApi(ChiefStore.githubToken(c))
        val branch = if (gh.headSha(ChiefStore.BRANCH) != null) ChiefStore.BRANCH else ChiefStore.SOURCE_BRANCH
        val head = gh.headSha(branch) ?: return "I could not find the code on GitHub (branch $branch)."
        val prefs = c.getSharedPreferences("chief", 0)
        val dir = Workspace.dir(c)
        if (force || prefs.getString("ws_sha", "") != head || !Workspace(dir).ready()) {
            val zip = File(c.cacheDir, "chief/src.zip")
            gh.downloadZip(branch, zip)
            Workspace.unpack(zip, dir)
            zip.delete()
            prefs.edit().putString("ws_sha", head).apply()
        }
        if (!Workspace(dir).ready()) return "The code on GitHub is not in the editable layout yet. Ask for the next Atlas build to publish it."
        null
    }.getOrElse { "Could not get the code: ${it.message}" }

    fun approve(c: Context, summary: String) {
        val app = c.applicationContext
        val files = ChiefStore.pendingFiles(app)
        if (files.isEmpty()) return
        phase(app, "pushing")
        scope.launch {
            runCatching {
                val gh = GitHubApi(ChiefStore.githubToken(app))
                gh.ensureBranch(ChiefStore.BRANCH, ChiefStore.SOURCE_BRANCH)
                val bytes = files.associate { "project/${it.path}" to it.content.toByteArray(Charsets.UTF_8) }
                val msg = "Chief: " + summary.lineSequence().first().take(70) + " [release]"
                val sha = gh.push(ChiefStore.BRANCH, bytes, msg)
                ChiefStore.setLastSha(app, sha)
                ChiefStore.clearPending(app)
                phase(app, "building", sha)
                watch(app, sha)
            }.onFailure { phase(app, "failed", it.message.orEmpty()) }
        }
    }

    fun resume(c: Context) {
        val app = c.applicationContext
        if (ChiefStore.phase(app) == "building" && !watching) scope.launch { watch(app, ChiefStore.lastSha(app)) }
    }

    private suspend fun watch(app: Context, sha: String) {
        if (watching) return
        watching = true
        try {
            val gh = GitHubApi(ChiefStore.githubToken(app))
            repeat(100) {
                delay(20_000)
                val run = runCatching { gh.runFor(sha) }.getOrNull() ?: return@repeat
                if (run.first == "completed") {
                    if (run.second == "success") {
                        var tag = ""
                        repeat(6) { if (tag.isBlank()) { delay(5_000); if (gh.releaseByTag("build-${run.third}") != null) tag = "build-${run.third}" } }
                        if (tag.isBlank()) tag = "build-${run.third}"
                        phase(app, "ready", tag)
                        Notifications.post(app, Notifications.CHANNEL_NUDGE, 7900, "Atlas update ready", "Your change is built. Open Chief to install it.")
                    } else {
                        val hint = gh.failureHint(sha)
                        phase(app, "failed", "The build did not pass.\n$hint".trim())
                        Notifications.post(app, Notifications.CHANNEL_NUDGE, 7901, "The build did not pass", "Open Chief and I will explain and fix it.")
                    }
                    return
                }
            }
            phase(app, "failed", "The build is taking too long. Check again in a few minutes.")
        } finally { watching = false }
    }

    /** The release to install, ignoring the usual 24-hour wait. */
    suspend fun updateInfo(c: Context, tag: String): Updater.Info? = runCatching {
        val r = GitHubApi(ChiefStore.githubToken(c)).releaseByTag(tag) ?: return null
        val a = r.optJSONArray("assets") ?: return null
        val asset = (0 until a.length()).map { a.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
        Updater.Info(tag, Instant.parse(r.optString("published_at")).toEpochMilli(), if (ChiefStore.githubToken(c).isBlank()) asset.optString("browser_download_url") else asset.optString("url"), r.optString("body").take(300), asset.optLong("size"))
    }.getOrNull()
}
