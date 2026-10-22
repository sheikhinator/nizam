package com.nizam.app.feature.safe

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.ui.design.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate

/**
 * Refresh: Atlas's F5. Clears leftover files and caches, tidies the database, gives resting AI models a fresh
 * chance, unsticks jobs and approvals, restarts background routines and warms the next screen, then reports what
 * it did. It never touches your notes, money, chats, the vault or the hidden vault.
 */
object AtlasRefresh {
    data class Step(val label: String, val done: Boolean = false, val detail: String = "")
    data class State(val running: Boolean = false, val steps: List<Step> = emptyList(), val summary: String = "", val shownAt: Long = 0)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state

    /** Bumped when a refresh finishes, so the screen you're on reloads its data. */
    private val _tick = MutableStateFlow(0)
    val tick: StateFlow<Int> = _tick

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + CoroutineSafetyNet.inScope)

    fun start(container: AppContainer, screen: String? = null) {
        if (_state.value.running) return
        scope.launch { run(container, screen, quiet = false) }
    }

    private fun step(label: String) { _state.value = _state.value.copy(steps = _state.value.steps + Step(label)) }
    private fun done(detail: String = "") {
        val s = _state.value.steps.toMutableList()
        if (s.isNotEmpty()) s[s.lastIndex] = s.last().copy(done = true, detail = detail)
        _state.value = _state.value.copy(steps = s)
    }

    private fun sizeOf(f: File): Long = if (f.isDirectory) (f.listFiles()?.sumOf { sizeOf(it) } ?: 0L) else f.length()
    private fun mb(b: Long) = if (b < 1024 * 1024) "${b / 1024} KB" else "%.1f MB".format(b / 1048576.0)

    /** The whole refresh. [quiet] runs it without the on-screen card (the nightly one). */
    suspend fun run(container: AppContainer, screen: String?, quiet: Boolean): String {
        val c = container.appContext
        val t0 = System.currentTimeMillis()
        if (!quiet) _state.value = State(running = true, shownAt = t0)
        var freed = 0L
        var fixed = 0
        fun s(label: String) { if (!quiet) step(label) }
        fun d(detail: String = "") { if (!quiet) done(detail) }

        s("Clearing leftover files")
        freed += withContext(Dispatchers.IO) {
            var n = 0L
            val now = System.currentTimeMillis()
            // the app's cache folder: images, web pages, temporary scans and shares (anything not used in the last few minutes)
            // (the image cache and the web view's own cache are cleared through their owners, never pulled out from under them)
            val owned = setOf("image_cache", "WebView", "org.chromium.android_webview", "http_cache")
            c.cacheDir.listFiles()?.filter { it.name !in owned }?.forEach { f -> if (now - f.lastModified() > 5 * 60_000L) { val sz = sizeOf(f); if (f.deleteRecursively()) n += sz } }
            runCatching { coil.Coil.imageLoader(c).diskCache?.let { dc -> n += dc.size; dc.clear() } }
            c.externalCacheDir?.listFiles()?.forEach { f -> if (now - f.lastModified() > 5 * 60_000L) { val sz = sizeOf(f); if (f.deleteRecursively()) n += sz } }
            // half-finished downloads and temporary files left behind by an interrupted job
            c.filesDir.walkTopDown().onEnter { it.name !in setOf("vault", "hidden", "datastore") }
                .filter { it.isFile && (it.name.endsWith(".part") || it.name.endsWith(".tmp") || it.name.endsWith(".new")) && now - it.lastModified() > 10 * 60_000L }
                .forEach { val sz = it.length(); if (it.delete()) n += sz }
            // the crash log keeps only its recent part
            File(c.filesDir, "crash.log").takeIf { it.exists() && it.length() > 24_000 }?.let { f -> val keep = f.readText().take(16_000); n += f.length() - keep.length; f.writeText(keep) }
            n
        }
        runCatching { coil.Coil.imageLoader(c).memoryCache?.clear() }
        d(mb(freed))

        s("Tidying the database")
        val dbFixed = withContext(Dispatchers.IO) {
            runCatching {
                val db = com.nizam.app.data.db.NizamDatabase.get(c).openHelper.writableDatabase
                db.query("PRAGMA optimize").close()
                if (!quiet || LocalDate.now().dayOfWeek == java.time.DayOfWeek.FRIDAY) db.execSQL("VACUUM")
                true
            }.getOrDefault(false)
        }
        d(if (dbFixed) "compacted" else "")

        s("Checking saved lists")
        val repaired = withContext(Dispatchers.IO) {
            listOf("expertise_versions", "expertise_examples", "life_laws", "personas", "kb_docs", "mind_runs", "scout_findings", "pulse_log",
                "approval_inbox", "roads_ext", "oracle_answers", "forge_proposals", "channel_drafts", "engine_days").count { n ->
                val st = com.nizam.app.feature.next.JsonStore(c, n)
                runCatching { st.all(); false }.getOrElse { runCatching { st.recoverLastGood() }.getOrDefault(false) }
            }
        }
        fixed += repaired
        d(if (repaired > 0) "repaired $repaired" else "all healthy")

        s("Refreshing the AI engine")
        val rested = com.nizam.app.net.Health.freshStart()
        com.nizam.app.net.AnswerCache.clear()
        fixed += rested
        d(if (rested > 0) "$rested models back in rotation" else "ready")

        s("Unsticking jobs")
        val unstuck = withContext(Dispatchers.IO) {
            val inbox = com.nizam.app.feature.agent.ApprovalInbox.store(c)
            val stuck = inbox.all().filter { it.optString("state") == "executing" && System.currentTimeMillis() - it.optLong("at") > 10 * 60_000L }
            stuck.forEach { o -> inbox.update(o.optString("id")) { it.put("state", "waiting") } }
            stuck.size
        }
        runCatching { com.nizam.app.feature.notify.Notifications.schedule(c) }
        fixed += unstuck
        d(if (unstuck > 0) "$unstuck back in Approvals" else "routines running")

        s(if (screen != null) "Reloading this screen" else "Warming up")
        runCatching { com.nizam.app.feature.agent.Snapshot.invalidate() }
        withContext(Dispatchers.IO) {
            runCatching { com.nizam.app.feature.nx.Roads.warm(container, force = true) }
            runCatching { com.nizam.app.feature.agent.Snapshot.of(container) }
            runCatching { com.nizam.app.net.Router.warm(container.settingsRepository, c) }
        }
        System.gc()
        _tick.value = _tick.value + 1
        d()

        val ms = System.currentTimeMillis() - t0
        val summary = "Freed ${mb(freed)}" + (if (fixed > 0) " · fixed $fixed" else "") + " · Atlas is in top shape"
        c.getSharedPreferences("refresh", 0).edit().putLong("last", System.currentTimeMillis()).putString("summary", summary).apply()
        runCatching { com.nizam.app.feature.expertise.Pulse.emit(com.nizam.app.feature.expertise.Pulse.Kind.DONE, "Refreshed Atlas", "$summary (${ms / 100 / 10.0} s)") }
        if (!quiet) {
            _state.value = _state.value.copy(running = false, summary = summary)
            delay(3200)
            if (!_state.value.running) _state.value = State()
        }
        return summary
    }

    /** The light nightly refresh: once a night after 3 am, quietly. */
    suspend fun nightly(c: Context) {
        val p = c.getSharedPreferences("refresh", 0)
        if (java.time.LocalTime.now().hour !in 3..5 || p.getString("night", "") == LocalDate.now().toString()) return
        p.edit().putString("night", LocalDate.now().toString()).apply()
        val container = (c.applicationContext as? com.nizam.app.NizamApplication)?.container ?: return
        runCatching { run(container, null, quiet = true) }
    }

    fun dismiss() { if (!_state.value.running) _state.value = State() }
}

/** The refresh button: a circular arrow wrapped round a tiny Atlas gem; it spins while Atlas refreshes. */
@Composable
fun RefreshButton(container: AppContainer, screen: String?, modifier: Modifier = Modifier, size: Dp = 40.dp, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val st by AtlasRefresh.state.collectAsState()
    val t = rememberInfiniteTransition(label = "refresh")
    val spin by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing)), label = "spin")
    val pulse by t.animateFloat(0.85f, 1f, infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse")
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.size(size).clip(CircleShape).clickable { AtlasRefresh.start(container, screen) }.semantics { contentDescription = "Refresh Atlas" },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size * 0.62f).rotate(if (st.running && !Motion.still) spin else 0f)) {
            val r = this.size.minDimension / 2 - 2f
            val col = if (st.running) accent else tint
            // the circular arrow
            drawArc(col, startAngle = -60f, sweepAngle = 300f, useCenter = false, topLeft = Offset(center.x - r, center.y - r),
                size = androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(2.4f * density, cap = StrokeCap.Round))
            val a = Math.toRadians(-60.0)
            val tip = Offset(center.x + (r * kotlin.math.cos(a)).toFloat(), center.y + (r * kotlin.math.sin(a)).toFloat())
            val head = Path().apply {
                moveTo(tip.x + 3.6f * density, tip.y - 1.2f * density); lineTo(tip.x - 1.8f * density, tip.y - 3.8f * density); lineTo(tip.x - 0.4f * density, tip.y + 2.6f * density); close()
            }
            drawPath(head, col)
            // the tiny gem in the middle
            val g = r * 0.42f * (if (st.running) pulse else 1f)
            val gem = Path().apply { moveTo(center.x, center.y - g); lineTo(center.x + g * 0.75f, center.y); lineTo(center.x, center.y + g); lineTo(center.x - g * 0.75f, center.y); close() }
            drawPath(gem, if (st.running) accent else col.copy(alpha = 0.85f))
        }
    }
}

/** The card that shows a refresh happening, over whatever screen you're on. */
@Composable
fun RefreshOverlay(modifier: Modifier = Modifier) {
    val st by AtlasRefresh.state.collectAsState()
    val visible = st.running || st.summary.isNotBlank()
    AnimatedVisibility(visible, modifier, enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
        val c = MaterialTheme.colorScheme
        Column(Modifier.statusBarsPadding().padding(top = 8.dp, start = 16.dp, end = 16.dp).widthIn(max = 460.dp).fillMaxWidth()
            .clip(RoundedCornerShape(22.dp)).background(c.surface.copy(alpha = 0.97f)).border(1.dp, c.primary.copy(alpha = 0.3f), RoundedCornerShape(22.dp))
            .clickable { AtlasRefresh.dismiss() }.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (st.running) "Refreshing Atlas…" else "All fresh ✨", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            st.steps.forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if (s.done) "✓" else "•", color = if (s.done) c.primary else c.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                    Text(s.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (s.detail.isNotBlank()) Text(s.detail, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant)
                }
            }
            if (st.summary.isNotBlank()) Text(st.summary, style = MaterialTheme.typography.labelLarge, color = c.primary, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
