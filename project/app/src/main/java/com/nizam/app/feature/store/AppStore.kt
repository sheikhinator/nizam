package com.nizam.app.feature.store

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.nizam.app.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Atlas's app store: one catalogue of the apps you want, drawn from several sources.
 *
 *  F-Droid and IzzyOnDroid  — open-source repositories with public APIs: search, versions, and the APK
 *                             itself, downloaded and installed directly
 *  GitHub                    — the latest release's APK from any repository
 *  Google Play / Aurora      — Play has no public API and its app protocol isn't Atlas's to use, so Play
 *                             apps are added by name or link, and installed/updated by handing off to
 *                             the Play Store or Aurora Store with one tap
 *  Any link                  — any direct .apk URL
 *
 * Android installs every APK through its own confirmation screen; Atlas never installs silently.
 */
data class StoreApp(
    val pkg: String, val name: String, val summary: String, val icon: String, val source: String,
    val apkUrl: String = "", val version: String = "", val versionCode: Long = 0, val repo: String = "", val note: String = ""
)

object AppStore {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private fun f(c: Context) = File(c.filesDir, "app_catalogue.json")

    // ── the catalogue ────────────────────────────────────────────────────────────────────────────

    fun catalogue(c: Context): List<StoreApp> = runCatching { JSONArray(f(c).readText()) }.getOrElse { JSONArray() }.let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            StoreApp(o.optString("pkg"), o.optString("name"), o.optString("summary"), o.optString("icon"), o.optString("source"),
                o.optString("apkUrl"), o.optString("version"), o.optLong("versionCode"), o.optString("repo"), o.optString("note"))
        }
    }

    private fun write(c: Context, list: List<StoreApp>) = f(c).writeText(JSONArray().apply {
        list.forEach { s -> put(JSONObject().put("pkg", s.pkg).put("name", s.name).put("summary", s.summary).put("icon", s.icon)
            .put("source", s.source).put("apkUrl", s.apkUrl).put("version", s.version).put("versionCode", s.versionCode)
            .put("repo", s.repo).put("note", s.note)) }
    }.toString())

    fun add(c: Context, app: StoreApp) = write(c, catalogue(c).filterNot { it.pkg == app.pkg && it.source == app.source } + app)
    fun update(c: Context, app: StoreApp) = write(c, catalogue(c).map { if (it.pkg == app.pkg && it.source == app.source) app else it })
    fun remove(c: Context, app: StoreApp) = write(c, catalogue(c).filterNot { it.pkg == app.pkg && it.source == app.source })

    // ── what's on the phone ──────────────────────────────────────────────────────────────────────

    fun installedVersion(c: Context, pkg: String): Pair<String, Long>? = runCatching {
        val i = c.packageManager.getPackageInfo(pkg, 0)
        (i.versionName ?: "") to (if (Build.VERSION.SDK_INT >= 28) i.longVersionCode else @Suppress("DEPRECATION") i.versionCode.toLong())
    }.getOrNull()

    fun launch(c: Context, pkg: String): Boolean = c.packageManager.getLaunchIntentForPackage(pkg)
        ?.let { c.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } ?: false

    fun appInfo(c: Context, pkg: String) = c.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** Android shows its own "Uninstall?" confirmation. */
    fun uninstall(c: Context, pkg: String) = c.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    // ── F-Droid and IzzyOnDroid ──────────────────────────────────────────────────────────────────

    private val REPOS = mapOf("F-Droid" to "https://f-droid.org", "IzzyOnDroid" to "https://apt.izzysoft.de/fdroid")

    /** Full-text search over F-Droid's catalogue. */
    suspend fun searchFdroid(query: String): List<StoreApp> {
        val o = JSONObject(Http.getJson("https://search.f-droid.org/api/search_apps?q=${URLEncoder.encode(query, "UTF-8")}&lang=en"))
        val apps = o.optJSONArray("apps") ?: JSONArray()
        return (0 until apps.length()).map { apps.getJSONObject(it) }.mapNotNull { a ->
            val pkg = a.optString("url").substringAfter("/packages/").trim('/').ifBlank { return@mapNotNull null }
            StoreApp(pkg, a.optString("name"), a.optString("summary"), a.optString("icon"), "F-Droid")
        }
    }

    /** The version a repository suggests, and its APK. Works for F-Droid and IzzyOnDroid alike. */
    suspend fun resolveRepo(app: StoreApp, repo: String = app.source): StoreApp {
        val base = REPOS[repo] ?: throw IllegalArgumentException("Unknown repository $repo")
        val o = JSONObject(Http.getJson("$base/api/v1/packages/${app.pkg}"))
        val code = o.optLong("suggestedVersionCode")
        val pkgs = o.optJSONArray("packages") ?: JSONArray()
        val chosen = (0 until pkgs.length()).map { pkgs.getJSONObject(it) }.firstOrNull { it.optLong("versionCode") == code }
            ?: pkgs.optJSONObject(0) ?: throw IllegalStateException("No versions published")
        val vc = chosen.optLong("versionCode")
        return app.copy(source = repo, versionCode = vc, version = chosen.optString("versionName"),
            apkUrl = "$base/repo/${app.pkg}_$vc.apk")
    }

    /** Looks a package name up in both open repositories. */
    suspend fun lookup(pkg: String): StoreApp? {
        for (repo in REPOS.keys) {
            val r = runCatching { resolveRepo(StoreApp(pkg, pkg, "", "", repo), repo) }.getOrNull()
            if (r != null) return r
        }
        return null
    }

    // ── GitHub releases ──────────────────────────────────────────────────────────────────────────

    /** "owner/repo" or a github.com link → the newest release's APK (arm64 build preferred). */
    suspend fun github(input: String): StoreApp {
        val repo = input.trim().removePrefix("https://").removePrefix("http://").removePrefix("github.com/").trim('/')
            .split('/').take(2).joinToString("/")
        require(repo.count { it == '/' } == 1) { "Use owner/repo, e.g. ImranR98/Obtainium" }
        val o = JSONObject(Http.getJson("https://api.github.com/repos/$repo/releases/latest"))
        val assets = o.optJSONArray("assets") ?: JSONArray()
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }.filter { it.optString("name").endsWith(".apk", true) }
        val pick = apks.firstOrNull { it.optString("name").contains("arm64", true) } ?: apks.firstOrNull { !it.optString("name").contains("x86", true) }
            ?: apks.firstOrNull() ?: throw IllegalStateException("The latest release of $repo has no APK")
        return StoreApp("", repo.substringAfter('/'), o.optString("name").ifBlank { o.optString("tag_name") }, "https://github.com/${repo.substringBefore('/')}.png",
            "GitHub", pick.optString("browser_download_url"), o.optString("tag_name"), 0, repo)
    }

    // ── Google Play and Aurora ───────────────────────────────────────────────────────────────────

    /** Accepts a package name or any Play link (…details?id=com.example). */
    fun playPackage(input: String): String? {
        val t = input.trim()
        val id = Regex("[?&]id=([A-Za-z0-9_.]+)").find(t)?.groupValues?.get(1) ?: t
        return id.takeIf { Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$").matches(it) }
    }

    fun auroraInstalled(c: Context) = installedVersion(c, "com.aurora.store") != null

    /** Opens the app's page in Aurora Store if you have it, otherwise the Play Store. */
    fun openInStore(c: Context, pkg: String, preferAurora: Boolean = true) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (preferAurora && auroraInstalled(c)) market.setPackage("com.aurora.store")
        else if (installedVersion(c, "com.android.vending") != null) market.setPackage("com.android.vending")
        runCatching { c.startActivity(market) }.onFailure {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun searchInStore(c: Context, q: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=${Uri.encode(q)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (auroraInstalled(c)) i.setPackage("com.aurora.store")
        runCatching { c.startActivity(i) }.onFailure {
            c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/search?q=${Uri.encode(q)}&c=apps")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ── downloading and installing APKs ─────────────────────────────────────────────────────────

    data class Progress(val name: String, val percent: Int, val state: String, val error: String = "")
    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress

    fun canInstall(c: Context) = Build.VERSION.SDK_INT < 26 || c.packageManager.canRequestPackageInstalls()
    fun askInstallPermission(c: Context) = c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /** Downloads the APK, then hands it to Android's installer (which asks you to confirm). */
    fun install(c: Context, app: StoreApp) {
        val url = app.apkUrl.ifBlank { return }
        val ctx = c.applicationContext
        scope.launch {
            val dir = File(ctx.cacheDir, "apks").apply { mkdirs() }
            val out = File(dir, (app.pkg.ifBlank { app.name }).replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk")
            try {
                _progress.value = Progress(app.name, 0, "downloading")
                com.nizam.app.net.Transfers.progress(ctx, "apk:${app.name}", "App · ${app.name}", -1)
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true; connectTimeout = 20000; readTimeout = 60000
                    setRequestProperty("User-Agent", "Atlas/1.0")
                }
                if (conn.responseCode !in 200..299) throw IllegalStateException("Download failed (HTTP ${conn.responseCode})")
                val total = conn.contentLengthLong
                conn.inputStream.use { input -> out.outputStream().use { o ->
                    val buf = ByteArray(1 shl 16); var done = 0L; var shown = -1
                    while (isActive) {
                        val n = input.read(buf); if (n < 0) break
                        o.write(buf, 0, n); done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != shown) { shown = pct; _progress.value = Progress(app.name, pct, "downloading")
                            com.nizam.app.net.Transfers.progress(ctx, "apk:${app.name}", "App · ${app.name}", if (total > 0) pct else -1, "${done / 1_048_576} MB") }
                    }
                } }
                conn.disconnect()
                _progress.value = Progress(app.name, 100, "installing")
                com.nizam.app.net.Transfers.cancelled("apk:${app.name}")
                withContext(Dispatchers.Main) {
                    val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", out)
                    ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                _progress.value = Progress(app.name, 100, "done")
            } catch (e: Exception) {
                _progress.value = Progress(app.name, 0, "failed", com.nizam.app.ui.components.Feedback.friendly(e))
                com.nizam.app.net.Transfers.failed(ctx, "apk:${app.name}", app.name, com.nizam.app.ui.components.Feedback.friendly(e))
            }
        }
    }

    /** Catalogue entries whose repository offers a newer version than the one installed. */
    suspend fun updates(c: Context): List<StoreApp> = withContext(Dispatchers.IO) {
        catalogue(c).filter { it.source in REPOS.keys }.mapNotNull { app ->
            val have = installedVersion(c, app.pkg) ?: return@mapNotNull null
            val latest = runCatching { resolveRepo(app) }.getOrNull() ?: return@mapNotNull null
            latest.takeIf { it.versionCode > have.second }
        }
    }

    fun isInstalledPm(c: Context, pkg: String) = runCatching { c.packageManager.getPackageInfo(pkg, PackageManager.GET_META_DATA); true }.getOrElse { false }
}
