package com.nizam.app.feature.forecast

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.nx.Roads
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.FloatBuffer
import java.security.MessageDigest
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A prediction: the middle path plus a low and a high band. [engine] says who made it. */
class Prediction(val median: List<Double>, val low: List<Double>, val high: List<Double>, val engine: String)

/**
 * Forecasting for anything Atlas records over time. Google's TimesFM model runs on the phone once it is
 * downloaded; until then (or if it fails) a simple weekly-pattern forecaster gives the answer, so a
 * forecast is always available.
 */
object Forecast {
    /** Fill gaps (NaN) by drawing a straight line between neighbours; leading gaps are dropped. */
    fun clean(raw: List<Double>): List<Double> {
        val start = raw.indexOfFirst { !it.isNaN() }
        if (start < 0) return emptyList()
        val x = raw.drop(start).toMutableList()
        var i = 0
        while (i < x.size) {
            if (x[i].isNaN()) {
                var j = i
                while (j < x.size && x[j].isNaN()) j++
                val a = x[i - 1]; val b = if (j < x.size) x[j] else a
                for (k in i until j) x[k] = a + (b - a) * (k - i + 1) / (j - i + 1)
                i = j
            } else i++
        }
        return x
    }

    suspend fun predict(c: Context, history: List<Double>, horizon: Int): Prediction {
        val x = clean(history)
        if (x.size < 5) return basic(x, horizon)
        if (TimesFm.ready(c)) {
            runCatching { withContext(Dispatchers.Default) { TimesFm.predict(c, x, horizon) } }.getOrNull()?.let { return it }
        }
        return basic(x, horizon)
    }

    /** Weekly pattern plus a damped recent trend. Needs no download. */
    fun basic(x: List<Double>, horizon: Int): Prediction {
        if (x.isEmpty()) return Prediction(List(horizon) { 0.0 }, List(horizon) { 0.0 }, List(horizon) { 0.0 }, "Basic")
        val n = x.size
        val recent = x.takeLast(min(7, n)).average()
        val older = if (n >= 14) x.subList(n - 14, n - 7).average() else recent
        val trendPerDay = (recent - older) / 7.0
        val season = DoubleArray(7)
        if (n >= 21) {
            val ma = x.windowed(7) { it.average() }
            val sums = DoubleArray(7); val cnt = IntArray(7)
            for (k in ma.indices) { val idx = k + 3; sums[idx % 7] += x[idx] - ma[k]; cnt[idx % 7]++ }
            for (d in 0 until 7) if (cnt[d] > 0) season[d] = sums[d] / cnt[d]
        }
        val resid = x.takeLast(min(28, n)).let { w -> val m = w.average(); sqrt(w.sumOf { (it - m) * (it - m) } / max(1, w.size - 1)) }
        val nonNeg = x.all { it >= 0 }
        val mid = (0 until horizon).map { h ->
            val damp = (1..(h + 1)).sumOf { Math.pow(0.9, it.toDouble()) }
            val v = recent + trendPerDay * damp + season[(n + h) % 7]
            if (nonNeg) max(0.0, v) else v
        }
        val band = (0 until horizon).map { h -> 1.28 * resid * sqrt(1.0 + h / 14.0) }
        return Prediction(mid, mid.indices.map { val v = mid[it] - band[it]; if (nonNeg) max(0.0, v) else v }, mid.indices.map { mid[it] + band[it] }, "Basic")
    }
}

/** TimesFM 2.5 (200M, 8-bit) run through ONNX Runtime. A one-time 249 MB download. */
object TimesFm {
    private const val URL = "https://huggingface.co/bakshinghampalace/timesfm-2.5-200m-onnx/resolve/main/onnx/model_quantized.onnx"
    private const val SHA = "03c0de47701fab96bca31c9e4004f6977572e1d399d8cf4e8b2cf88651f783bb"
    private const val SIZE = 261301673L
    private const val CTX = 512
    private const val MAXH = 128

    private fun file(c: Context) = File(c.filesDir, "timesfm/model_quantized.onnx")
    fun ready(c: Context) = file(c).let { it.exists() && it.length() == SIZE }
    fun remove(c: Context) { close(); file(c).delete() }

    @Volatile private var session: OrtSession? = null
    private fun close() { runCatching { session?.close() }; session = null }

    /** Downloads the model, reporting 0..1 progress. Returns an error text, or null on success. */
    suspend fun download(c: Context, progress: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
        val out = file(c); out.parentFile?.mkdirs()
        val part = File(out.parentFile, "model.part")
        try {
            val http = OkHttpClient.Builder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
            http.newCall(Request.Builder().url(URL).build()).execute().use { r ->
                if (!r.isSuccessful) return@withContext "Download failed (${r.code})"
                val body = r.body ?: return@withContext "Download failed (empty)"
                val md = MessageDigest.getInstance("SHA-256")
                var got = 0L
                body.byteStream().use { ins -> part.outputStream().use { os ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        os.write(buf, 0, n); md.update(buf, 0, n); got += n
                        progress((got.toFloat() / SIZE).coerceIn(0f, 1f))
                    }
                } }
                val hex = md.digest().joinToString("") { "%02x".format(it) }
                if (hex != SHA) { part.delete(); return@withContext "The download was damaged. Try again." }
            }
            close(); out.delete()
            if (!part.renameTo(out)) return@withContext "Could not save the model."
            null
        } catch (t: Throwable) {
            part.delete(); "Download stopped: ${t.message ?: t.javaClass.simpleName}"
        }
    }

    @Synchronized private fun open(c: Context): OrtSession {
        session?.let { return it }
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        return env.createSession(file(c).absolutePath, opts).also { session = it }
    }

    /** Run the model on the series and on its mirror image, then average, as the official library does. */
    @Suppress("UNCHECKED_CAST")
    fun predict(c: Context, x: List<Double>, horizon: Int): Prediction {
        val h = horizon.coerceIn(1, MAXH)
        val w = x.takeLast(CTX)
        val mean = w.average()
        val sd = sqrt(w.sumOf { (it - mean) * (it - mean) } / max(1, w.size - 1)).let { if (it < 1e-9) 1.0 else it }
        val pad = CTX - w.size
        val ins = FloatArray(2 * CTX); val msk = FloatArray(2 * CTX)
        for (k in 0 until CTX) {
            val padded = k < pad
            val v = if (padded) 0f else ((w[k - pad] - mean) / sd).toFloat()
            ins[k] = v; ins[CTX + k] = -v
            val m = if (padded) 1f else 0f
            msk[k] = m; msk[CTX + k] = m
        }
        val env = OrtEnvironment.getEnvironment()
        val s = open(c)
        val a = OnnxTensor.createTensor(env, FloatBuffer.wrap(ins), longArrayOf(2, CTX.toLong()))
        val b = OnnxTensor.createTensor(env, FloatBuffer.wrap(msk), longArrayOf(2, CTX.toLong()))
        val point: Array<Array<FloatArray>> = try {
            s.run(mapOf("inputs" to a, "masks" to b)).use { res -> res.get(0).value as Array<Array<FloatArray>> }
        } finally { a.close(); b.close() }
        // channel 0 = mean, 1..9 = q10..q90. The mirrored run pairs channel q with 10-q and flips sign.
        fun ch(step: Int, q: Int): Double {
            val direct = point[0][step][q].toDouble()
            val mirror = -point[1][step][if (q == 0) 0 else 10 - q].toDouble()
            return (direct + mirror) / 2.0 * sd + mean
        }
        val nonNeg = x.all { it >= 0 }
        fun fix(v: Double) = if (nonNeg) max(0.0, v) else v
        val mid = (0 until h).map { fix(ch(it, 5)) }
        val lo = (0 until h).map { fix(min(ch(it, 1), mid[it])) }
        val hi = (0 until h).map { fix(max(ch(it, 9), mid[it])) }
        return Prediction(mid, lo, hi, "TimesFM")
    }
}

internal enum class Series(val label: String, val unit: String, val sum: Boolean) {
    Spend("Spending per day", "Rs", true), Mood("Mood", "/5", false), Prayers("Prayers per day", "of 5", false), Activity("Things you log per day", "", true)
}

internal suspend fun loadSeries(c: AppContainer, s: Series, days: Int = 120): List<Double> = withContext(Dispatchers.IO) {
    val today = LocalDate.now()
    val from = today.minusDays(days - 1L)
    val idx = { d: String -> runCatching { java.time.temporal.ChronoUnit.DAYS.between(from, LocalDate.parse(d)).toInt() }.getOrDefault(-1) }
    val out = DoubleArray(days) { if (s.sum) 0.0 else Double.NaN }
    val cnt = IntArray(days)
    when (s) {
        Series.Spend -> runCatching { c.financeRepository.since(from.toString()).first() }.getOrDefault(emptyList())
            .filter { it.kind == "EXPENSE" }.forEach { t -> idx(t.localDate).takeIf { it in 0 until days }?.let { out[it] += t.amount } }
        Series.Mood -> runCatching { c.diaryRepository.checkIns().first() }.getOrDefault(emptyList()).forEach { m ->
            idx(m.localDate).takeIf { it in 0 until days }?.let { i -> out[i] = (if (out[i].isNaN()) 0.0 else out[i]) + m.mood; cnt[i]++ } }
        Series.Prayers -> runCatching { c.prayerRepository.since(from.toString()).first() }.getOrDefault(emptyList()).forEach { p ->
            idx(p.localDate).takeIf { it in 0 until days }?.let { i ->
                out[i] = listOf(p.fajr, p.dhuhr, p.asr, p.maghrib, p.isha).count { it != "NOT_PRAYED" }.toDouble() } }
        Series.Activity -> Roads.pulse(c.appContext, days).forEachIndexed { i, n -> out[i] = n.toDouble() }
    }
    if (s == Series.Mood) for (i in 0 until days) if (cnt[i] > 1) out[i] /= cnt[i]
    out.toList()
}

@Composable
internal fun ForecastScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var series by remember { mutableStateOf(Series.Spend) }
    var horizon by remember { mutableStateOf(14) }
    var hist by remember { mutableStateOf<List<Double>>(emptyList()) }
    var pred by remember { mutableStateOf<Prediction?>(null) }
    var busy by remember { mutableStateOf(false) }
    var ready by remember { mutableStateOf(TimesFm.ready(c)) }
    var prog by remember { mutableStateOf<Float?>(null) }
    var msg by remember { mutableStateOf("") }
    var tick by remember { mutableStateOf(0) }

    LaunchedEffect(series, horizon, ready, tick) {
        busy = true
        val h = Forecast.clean(loadSeries(container, series))
        hist = h
        pred = if (h.size >= 3) runCatching { Forecast.predict(c, h, horizon) }.getOrNull() else null
        busy = false
    }

    // No verticalScroll: this screen is reached through the generic tool-screen shell, which already
    // scrolls its content in a LazyColumn — nesting another scrollable Column crashes Compose.
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("What your numbers are heading toward", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Series.values().forEach { s -> FilterChip(selected = series == s, onClick = { series = s }, label = { Text(s.label) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(7, 14, 30, 60).forEach { d -> FilterChip(selected = horizon == d, onClick = { horizon = d }, label = { Text("$d days") }) }
        }
        val p = pred
        if (p == null) {
            Text(if (busy) "Working it out…" else "Not enough ${series.label.lowercase()} yet. Keep using Atlas for a few days and this fills in.",
                style = MaterialTheme.typography.bodyMedium)
        } else {
            val tail = hist.takeLast(60)
            val line = MaterialTheme.colorScheme.onSurfaceVariant
            val acc = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxWidth().height(180.dp)) {
                val all = tail + p.high + p.low
                val lo = all.minOrNull() ?: 0.0; val hi = (all.maxOrNull() ?: 1.0).let { if (it - lo < 1e-9) lo + 1 else it }
                val total = tail.size + p.median.size
                fun px(i: Int) = size.width * i / max(1, total - 1)
                fun py(v: Double) = (size.height * (1 - (v - lo) / (hi - lo))).toFloat()
                val hp = Path().also { path -> tail.forEachIndexed { i, v -> if (i == 0) path.moveTo(px(i), py(v)) else path.lineTo(px(i), py(v)) } }
                drawPath(hp, line, style = Stroke(3f))
                val band = Path().also { path ->
                    p.high.forEachIndexed { i, v -> val x = px(tail.size + i); if (i == 0) path.moveTo(x, py(v)) else path.lineTo(x, py(v)) }
                    for (i in p.low.indices.reversed()) path.lineTo(px(tail.size + i), py(p.low[i]))
                    path.close()
                }
                drawPath(band, acc.copy(alpha = 0.18f))
                val fp = Path().also { path ->
                    path.moveTo(px(tail.size - 1), py(tail.last()))
                    p.median.forEachIndexed { i, v -> path.lineTo(px(tail.size + i), py(v)) }
                }
                drawPath(fp, acc, style = Stroke(4f))
                drawLine(Color.Gray.copy(alpha = 0.4f), Offset(px(tail.size - 1), 0f), Offset(px(tail.size - 1), size.height))
            }
            val avgNow = hist.takeLast(7).average(); val avgNext = p.median.take(7).average()
            val u = series.unit
            val dir = when { avgNext > avgNow * 1.08 -> "going up"; avgNext < avgNow * 0.92 -> "going down"; else -> "staying about the same" }
            val summary = "Last 7 days averaged ${"%.1f".format(avgNow)} $u. The next week looks like ${"%.1f".format(avgNext)} $u, so it is $dir."
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text(summary, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                com.nizam.app.feature.voice.SpeakButton(summary)
            }
            if (series == Series.Spend) Text("Over the next $horizon days you would spend about Rs ${"%,.0f".format(p.median.sum())} (between ${"%,.0f".format(p.low.sum())} and ${"%,.0f".format(p.high.sum())}).",
                style = MaterialTheme.typography.bodyMedium)
            Text("Made by: ${if (p.engine == "TimesFM") "TimesFM, running on your phone" else "the basic weekly-pattern forecaster"}. The shaded band is the likely range.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!ready) {
            Text("For sharper forecasts, download Google's TimesFM model once (about 250 MB, Wi-Fi recommended). After that it works offline.", style = MaterialTheme.typography.bodyMedium)
            val pr = prog
            if (pr != null) { LinearProgressIndicator(progress = { pr }, modifier = Modifier.fillMaxWidth()); Text("${(pr * 100).toInt()}%") }
            else Button(onClick = {
                msg = ""; prog = 0f
                scope.launch {
                    val err = TimesFm.download(c) { prog = it }
                    prog = null; msg = err ?: "Done. Forecasts now use TimesFM."; ready = TimesFm.ready(c)
                }
            }) { Text("Download TimesFM") }
        } else {
            OutlinedButton(onClick = { TimesFm.remove(c); ready = false; msg = "Model removed." }) { Text("Remove the downloaded model") }
        }
        if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall)
    }
}

/** Looks ahead for the Noticer: a heads-up when spending or mood is about to move the wrong way. */
object Outlook {
    @Volatile var notices: List<com.nizam.app.feature.nx.Noticer.Notice> = emptyList()
    @Volatile private var at = 0L

    suspend fun refresh(container: AppContainer) {
        if (System.currentTimeMillis() - at < 6 * 3_600_000L) return
        at = System.currentTimeMillis()
        val out = mutableListOf<com.nizam.app.feature.nx.Noticer.Notice>()
        runCatching {
            val spend = Forecast.clean(loadSeries(container, Series.Spend))
            if (spend.count { it > 0 } >= 7) {
                val p = Forecast.predict(container.appContext, spend, 7)
                val now = spend.takeLast(7).sum(); val next = p.median.sum()
                if (next > now * 1.3 && next - now > 2000) out += com.nizam.app.feature.nx.Noticer.Notice("fc_spend", "Spending is heading up",
                    "The next 7 days look like about Rs ${"%,.0f".format(next)}, against Rs ${"%,.0f".format(now)} over the last 7.", "nx/forecastlive", 2)
            }
            val mood = Forecast.clean(loadSeries(container, Series.Mood))
            if (mood.size >= 14) {
                val p = Forecast.predict(container.appContext, mood, 7)
                val now = mood.takeLast(7).average(); val next = p.median.average()
                if (now > 0 && next < now * 0.85) out += com.nizam.app.feature.nx.Noticer.Notice("fc_mood", "Mood may dip this week",
                    "Your check-ins point to a lower week ahead. A little planning now can soften it.", "nx/forecastlive", 1)
            }
        }
        notices = out
    }
}

object ForecastSpecs {
    val all: List<Spec> = listOf(
        ScreenSpec("forecastlive", "Forecast", "Where your spending, mood and habits are heading, with TimesFM on your phone", Icons.Outlined.Timeline, Hub.Tools,
            "forecast predict future trend spending mood prediction timesfm projection") { c, _ -> ForecastScreen(c) },
    )
}
