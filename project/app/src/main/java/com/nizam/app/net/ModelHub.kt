package com.nizam.app.net

import android.content.Context
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Finding and downloading on-device models. Hugging Face is searched through its public API; any
 * other direct link works too. Downloads run in the background with a progress notification,
 * resume where they stopped (these files are hundreds of MB), and land in the models folder that
 * LocalModel runs from.
 *
 * Gated models (Gemma, Llama) need the licence accepted on huggingface.co once, plus a read token.
 */
data class HubModel(val id: String, val downloads: Int, val gated: Boolean, val updated: String)
data class HubFile(val path: String, val sizeMb: Long)
data class Download(val name: String, val done: Long, val total: Long, val state: String, val error: String = "", val speed: Long = 0) {
    val percent get() = if (total > 0) (done * 100 / total).toInt() else 0
}

object ModelHub {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private val _download = MutableStateFlow<Download?>(null)
    val download: StateFlow<Download?> = _download

    /** Formats MediaPipe's LLM runtime loads. Newer .litertlm files need a newer runtime. */
    val SUPPORTED = listOf(".task", ".bin")

    fun token(c: Context) = c.getSharedPreferences("local_model", 0).getString("hf_token", "").orEmpty()
    fun setToken(c: Context, t: String) = c.getSharedPreferences("local_model", 0).edit().putString("hf_token", t.trim()).apply()

    private fun headers(c: Context) = token(c).takeIf { it.isNotBlank() }?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()

    /** Searches Hugging Face. [onlyPhoneReady] limits to litert-community, which converts models for phones. */
    suspend fun search(c: Context, query: String, onlyPhoneReady: Boolean): List<HubModel> {
        val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://huggingface.co/api/models?limit=40&sort=downloads&direction=-1" +
            (if (onlyPhoneReady) "&author=litert-community" else "&filter=mediapipe") + (if (q.isNotBlank()) "&search=$q" else "")
        val arr = JSONArray(Http.getJson(url, headers(c)))
        return (0 until arr.length()).map { arr.getJSONObject(it) }.map { o ->
            HubModel(o.optString("id").ifBlank { o.optString("modelId") }, o.optInt("downloads"),
                o.opt("gated").let { g -> g == true || (g is String && g != "false") }, o.optString("lastModified").take(10))
        }.filter { it.id.isNotBlank() }
    }

    /** The files in a model repo that Atlas can run, with sizes. */
    suspend fun files(c: Context, id: String): List<HubFile> {
        val arr = JSONArray(Http.getJson("https://huggingface.co/api/models/$id/tree/main?recursive=true", headers(c)))
        return (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { it.optString("type") == "file" && SUPPORTED.any { ext -> it.optString("path").endsWith(ext, true) } }
            .map { HubFile(it.optString("path"), (it.optJSONObject("lfs")?.optLong("size") ?: it.optLong("size")) / 1_048_576) }
            .sortedBy { it.sizeMb }
    }

    fun urlFor(id: String, path: String) = "https://huggingface.co/$id/resolve/main/$path"

    fun cancel() { job?.cancel(); Transfers.cancelled("model"); _download.value = _download.value?.copy(state = "cancelled") }
    val busy get() = job?.isActive == true

    // ── the recommended model for this phone ─────────────────────────────────────────────────────

    data class Pick(val repo: String, val label: String, val why: String)

    /** Phone RAM in GB (what decides how big a model runs comfortably). */
    fun ramGb(c: Context): Double {
        val mi = android.app.ActivityManager.MemoryInfo()
        c.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi)
        return mi.totalMem / 1_073_741_824.0
    }

    /**
     * The best fast model for this phone, needing no account: Qwen 2.5 1.5B Instruct (8-bit) on phones
     * with 6 GB+ RAM — quick, good at following instructions, multilingual; Qwen 2.5 0.5B on smaller phones.
     * With a Hugging Face token saved, Gemma 3 1B (4-bit) is preferred — Google's phone-tuned model, the fastest per word.
     */
    fun recommended(c: Context): List<Pick> {
        val big = ramGb(c) >= 5.5
        val qwen = if (big) Pick("litert-community/Qwen2.5-1.5B-Instruct", "Qwen 2.5 · 1.5B",
                "Fast and capable on ${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL} (${"%.0f".format(ramGb(c))} GB RAM). About 1.5 GB, no account needed.")
            else Pick("litert-community/Qwen2.5-0.5B-Instruct", "Qwen 2.5 · 0.5B", "Small and quick for this phone's memory. About 500 MB, no account needed.")
        val gemma = Pick("litert-community/Gemma3-1B-IT", "Gemma 3 · 1B (fastest)", "Google's phone model, 4-bit, runs on the GPU — the quickest good replies. " +
            "About 550 MB. Needs your Hugging Face token and one tap on “Agree” at huggingface.co/litert-community/Gemma3-1B-IT.")
        val small = Pick("litert-community/Qwen2.5-0.5B-Instruct", "Qwen 2.5 · 0.5B", "Smallest and quickest. About 500 MB.")
        return (if (token(c).isNotBlank()) listOf(gemma, qwen) else listOf(qwen)) + small
    }

    /**
     * Best file in a repo for speed on a phone: no web builds; 4-bit before 8-bit (half the memory to read per word,
     * so roughly twice as fast); a 1–2k context window rather than 4k (much quicker to read the prompt); then smallest.
     */
    private fun bestFile(files: List<HubFile>): HubFile? = files
        .filter { f -> f.path.endsWith(".task", true) && !f.path.contains("web", true) }
        .sortedWith(compareBy<HubFile> { if (it.path.contains("int4", true) || it.path.contains("q4", true)) 0 else 1 }
            .thenBy { val k = Regex("ekv(\\d+)").find(it.path)?.groupValues?.get(1)?.toIntOrNull() ?: 2048; if (k in 1024..2048) 0 else 1 }
            .thenBy { it.sizeMb })
        .firstOrNull()

    /** Download a specific recommended model (from the list on the Offline models screen). */
    fun install(c: Context, p: Pick) {
        if (busy) return
        val app = c.applicationContext
        _download.value = Download("Finding ${p.label}…", 0, 0, "starting")
        scope.launch {
            val f = runCatching { bestFile(files(app, p.repo)) }.getOrNull()
            if (f == null) { _download.value = Download(p.label, 0, 0, "failed", "Couldn't find a phone file for ${p.label} right now."); return@launch }
            start(app, urlFor(p.repo, f.path), f.path.substringAfterLast('/'), fallback = true)
        }
    }

    /** One tap: finds the recommended model's current file on Hugging Face and downloads it. Falls back down the list. */
    fun installRecommended(c: Context, noGated: Boolean = false) {
        if (busy && !noGated) return
        val app = c.applicationContext
        _download.value = Download("Finding the best model for this phone…", 0, 0, "starting")
        Transfers.progress(app, "model", "Offline AI", -1, "Finding the best model for this phone…")
        scope.launch {
            var lastError: Throwable? = null
            for (p in recommended(app).filter { !noGated || !it.repo.contains("Gemma", true) }) {
                val f = runCatching { bestFile(files(app, p.repo)) }.onFailure { lastError = it }.getOrNull() ?: continue
                Transfers.cancelled("model")
                start(app, urlFor(p.repo, f.path), f.path.substringAfterLast('/'), fallback = noGated)
                return@launch
            }
            val msg = when (lastError) {
                is java.net.UnknownHostException -> "No internet connection — try again when you're online."
                null -> "Couldn't find a compatible model file right now. Try Search below."
                else -> "Couldn't reach Hugging Face: ${lastError?.message}"
            }
            _download.value = Download("Recommended model", 0, 0, "failed", msg)
            Transfers.failed(app, "model", "Offline AI", msg)
        }
    }

    /** Once, on the first launch with Wi-Fi and no model: fetch the recommended one so offline AI is simply there. */
    fun autoSetup(c: Context) {
        val prefs = c.getSharedPreferences("local_model", 0)
        if (prefs.getBoolean("auto_setup_done", false) || LocalModel.available(c) || busy) return
        val cm = c.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return
        if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) return
        prefs.edit().putBoolean("auto_setup_done", true).apply()
        installRecommended(c)
    }

    /** Downloads to models/<name>.part, resuming if a partial file is there, then renames it into place. */
    fun start(c: Context, url: String, suggestedName: String? = null, fallback: Boolean = false) {
        if (job?.isActive == true) return
        val app = c.applicationContext
        val name = (suggestedName ?: url.substringAfterLast('/').substringBefore('?'))
            .replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "model.task" }
        val part = File(LocalModel.dir(app), "$name.part")
        val final = File(LocalModel.dir(app), name)
        job = scope.launch {
            var conn: HttpURLConnection? = null
            try {
                var have = if (part.exists()) part.length() else 0L
                _download.value = Download(name, have, 0, "starting")
                Transfers.progress(app, "model", "Offline AI · $name", -1, "Connecting…")
                conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 20000; readTimeout = 60000
                    setRequestProperty("User-Agent", "Atlas/1.0")
                    headers(app).forEach { (k, v) -> setRequestProperty(k, v) }
                    if (have > 0) setRequestProperty("Range", "bytes=$have-")
                }
                val code = conn.responseCode
                if (code == 401 || code == 403) {
                    // needs an account: don't show an error — fetch the free recommended model instead
                    conn.disconnect(); part.delete(); Transfers.cancelled("model")
                    if (!fallback) { job = null; _download.value = Download("Switching to a free model for this phone…", 0, 0, "starting")
                        installRecommended(app, noGated = true) }
                    else _download.value = Download(name, 0, 0, "failed", "Couldn't download a model right now. Check your connection and try again.")
                    return@launch
                }
                if (code == 404) throw IllegalStateException("That file isn't there any more (HTTP 404). Search again for the current version.")
                if (code == 416) { part.delete(); throw IllegalStateException("The partial download didn't match — tap download again to start fresh.") }
                if (code !in 200..299) throw IllegalStateException("The server said HTTP $code")
                if (code == 200 && have > 0) { part.delete(); have = 0 }        // server ignored Range: start over
                val total = have + conn.contentLengthLong.coerceAtLeast(0)
                RandomAccessFile(part, "rw").use { out ->
                    out.seek(have)
                    conn.inputStream.use { input ->
                        val buf = ByteArray(1 shl 16)
                        var done = have
                        var lastShown = 0L
                        var lastAt = System.currentTimeMillis(); var lastBytes = done
                        var speed = 0L
                        while (isActive) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (done - lastShown > 2_000_000 || done == total) {
                                lastShown = done
                                val now = System.currentTimeMillis()
                                if (now - lastAt >= 1000) { speed = (done - lastBytes) * 1000 / (now - lastAt); lastAt = now; lastBytes = done }
                                _download.value = Download(name, done, total, "downloading", speed = speed)
                                Transfers.progress(app, "model", "Offline AI · $name", if (total > 0) (done * 100 / total).toInt() else -1,
                                    "${done / 1_048_576} of ${total / 1_048_576} MB" + (if (speed > 0) " · %.1f MB/s".format(speed / 1_048_576.0) else ""))
                            }
                        }
                        if (total > 0 && done < total && isActive) throw IllegalStateException("The connection dropped at ${done * 100 / total}% — tap download to resume.")
                    }
                }
                if (!isActive) { Transfers.cancelled("model"); return@launch }
                if (final.exists()) final.delete()
                part.renameTo(final)
                LocalModel.setActive(app, name)
                _download.value = Download(name, final.length(), final.length(), "done")
                Transfers.done(app, "model", "Offline AI", "$name is installed and active. Atlas now answers without internet.")
                scope.launch { runCatching { LocalModel.warm(app) } }        // load it now so the first answer is quick
            } catch (e: kotlinx.coroutines.CancellationException) {
                Transfers.cancelled("model")
            } catch (e: Exception) {
                val msg = when (e) {
                    is java.net.UnknownHostException -> "No internet connection. Tap download to resume when you're back online."
                    is java.net.SocketTimeoutException -> "The download stalled. Tap download to resume where it stopped."
                    else -> e.message ?: e.javaClass.simpleName
                }
                _download.value = Download(name, part.length(), 0, "failed", msg)
                Transfers.failed(app, "model", "Offline AI · $name", msg)
            } finally {
                conn?.disconnect()
            }
        }
    }
}
