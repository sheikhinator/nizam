package com.nizam.app.feature.wellbeing

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.core.ChipRow
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.theme.DisplayFamily
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Muse-style meditation, with the phone as the sensor. Rest the phone on your chest or lap: while you're
 * still, the soundscape settles and birds start to sing; when you fidget, the weather picks up — so you
 * hear your mind wander and come back. Each session counts your calm time, recoveries and birds.
 * Soundscapes are made live on the phone (no downloads). Sleep mode plays a soundscape that fades out.
 */
object Soundscape {
    val KINDS = listOf("Rain", "Ocean", "Forest", "Wind")
    @Volatile var intensity = 0.5f          // 0 calm … 1 active
    @Volatile var volume = 0.8f
    @Volatile private var running = false
    @Volatile private var chirp = 0
    private var thread: Thread? = null

    fun bird() { chirp = 1 }

    fun start(kind: String) {
        stop()
        running = true
        thread = Thread {
            val rate = 22050
            val buf = ShortArray(1024)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2).build()
            track.play()
            var brown = 0.0; var pink = 0.0; var t = 0L; var smoothI = intensity.toDouble()
            var birdT = -1; var birdF = 3000.0
            val rnd = Random(7)
            while (running) {
                for (i in buf.indices) {
                    smoothI += (intensity - smoothI) * 0.00005
                    val white = rnd.nextDouble() * 2 - 1
                    brown = (brown + white * 0.02).coerceIn(-1.0, 1.0)
                    pink = 0.97 * pink + 0.03 * white
                    val sec = t / rate.toDouble()
                    var s = when (kind) {
                        "Ocean" -> brown * 2.2 * (0.35 + 0.65 * (0.5 + 0.5 * sin(2 * PI * sec / (9 - 4 * smoothI))))
                        "Forest" -> pink * 0.9 + brown * 0.6
                        "Wind" -> pink * (0.6 + 0.8 * (0.5 + 0.5 * sin(2 * PI * sec / 7))) * (0.6 + smoothI)
                        else -> white * 0.18 * (0.4 + smoothI) + (if (rnd.nextDouble() < 0.0004 + 0.002 * smoothI) rnd.nextDouble() * 0.6 else 0.0) + brown * 0.5
                    } * (0.45 + 0.9 * smoothI)
                    // birds sing while you're calm
                    if (chirp == 1 && birdT < 0) { birdT = 0; birdF = 2600.0 + rnd.nextDouble() * 1400; chirp = 0 }
                    if (birdT >= 0) {
                        val bt = birdT / rate.toDouble()
                        if (bt < 0.9) {
                            val env = sin(PI * ((bt * 6) % 1.0)) * (if ((bt * 6).toInt() % 2 == 0) 1.0 else 0.0)
                            s += 0.25 * env * sin(2 * PI * (birdF + 900 * sin(2 * PI * bt * 12)) * bt)
                            birdT++
                        } else birdT = -1
                    }
                    buf[i] = (s.coerceIn(-1.0, 1.0) * 12000 * volume).toInt().toShort()
                    t++
                }
                track.write(buf, 0, buf.size)
            }
            runCatching { track.stop(); track.release() }
        }.apply { isDaemon = true; start() }
    }

    fun stop() { running = false; thread = null }
}

private fun history(c: Context) = runCatching { JSONArray(c.getSharedPreferences("meditate", 0).getString("h", "[]")) }.getOrElse { JSONArray() }
private fun record(c: Context, minutes: Int, calmPct: Int, recoveries: Int, birds: Int, kind: String) {
    val h = history(c).put(JSONObject().put("at", System.currentTimeMillis()).put("min", minutes).put("calm", calmPct)
        .put("rec", recoveries).put("birds", birds).put("kind", kind))
    c.getSharedPreferences("meditate", 0).edit().putString("h", h.toString()).apply()
}

@Composable
fun MeditateScreen() {
    var tab by remember { mutableStateOf("Meditate") }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) {
            Text("Mind", style = MaterialTheme.typography.headlineMedium.copy(fontFamily = DisplayFamily))
            ChipRow(listOf("Meditate", "Breathe", "Sleep", "History"), tab, { tab = it })
        }
        when (tab) {
            "Meditate" -> Session(sleep = false)
            "Breathe" -> CalmScreen()
            "Sleep" -> Session(sleep = true)
            else -> History()
        }
    }
}

@Composable
private fun Session(sleep: Boolean) {
    val c = LocalContext.current
    var kind by remember { mutableStateOf(if (sleep) "Ocean" else "Rain") }
    var minutes by remember { mutableIntStateOf(if (sleep) 30 else 10) }
    var running by remember { mutableStateOf(false) }
    var left by remember { mutableIntStateOf(0) }
    var calm by remember { mutableFloatStateOf(0.5f) }
    var calmSec by remember { mutableIntStateOf(0) }
    var recoveries by remember { mutableIntStateOf(0) }
    var birds by remember { mutableIntStateOf(0) }
    var summary by remember { mutableStateOf<String?>(null) }

    // stillness from the accelerometer: little movement = calm
    DisposableEffect(running) {
        if (!running) return@DisposableEffect onDispose { }
        val sm = c.getSystemService(SensorManager::class.java)
        var last = 0f; var energy = 0f
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                energy = energy * 0.95f + abs(m - last) * 0.05f; last = m
                val active = (energy / 0.25f).coerceIn(0f, 1f)
                calm = 1f - active
                Soundscape.intensity = if (sleep) 0.2f else active
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_GAME) }
        onDispose { sm.unregisterListener(l); Soundscape.stop() }
    }
    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        left = minutes * 60; calmSec = 0; recoveries = 0; birds = 0; summary = null
        Soundscape.volume = 0.8f; Soundscape.start(kind)
        var wasCalm = true; var streak = 0
        while (left > 0 && running) {
            delay(1000); left--
            val isCalm = calm > 0.6f
            if (isCalm) { calmSec++; streak++ } else streak = 0
            if (isCalm && !wasCalm) recoveries++
            if (!sleep && streak > 0 && streak % 15 == 0) { birds++; Soundscape.bird() }
            wasCalm = isCalm
            if (sleep && left < 300) Soundscape.volume = 0.8f * left / 300f          // fade out over the last five minutes
        }
        Soundscape.stop()
        if (running) {
            val total = minutes * 60 - left
            val pct = if (total > 0) calmSec * 100 / total else 0
            if (!sleep) record(c, total / 60, pct, recoveries, birds, kind)
            summary = if (sleep) "Sleep well." else "$pct% calm · $recoveries recoveries · $birds birds"
            running = false
        }
    }

    val glow by animateFloatAsState(if (running) calm else 0.5f, tween(800), label = "glow")
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(240.dp).padding(8.dp), contentAlignment = Alignment.Center) {
            val a = MaterialTheme.colorScheme.primary
            Canvas(Modifier.fillMaxSize()) {
                val r = size.minDimension / 2
                drawCircle(Brush.radialGradient(listOf(a.copy(alpha = 0.15f + 0.35f * glow), Color.Transparent)), r)
                drawCircle(a.copy(alpha = 0.25f + 0.5f * glow), r * (0.35f + 0.2f * glow))
                if (running && !sleep) repeat(birds.coerceAtMost(24)) { i ->
                    val ang = i * 0.52f; drawCircle(Color(0xFFF2C14E), 4f, Offset(center.x + kotlin.math.cos(ang) * r * 0.85f, center.y + sin(ang) * r * 0.85f))
                }
            }
            Text(if (running) "%d:%02d".format(left / 60, left % 60) else summary ?: "${minutes} min",
                style = MaterialTheme.typography.headlineSmall)
        }
        if (running) Text(if (sleep) "Let go. It will fade out on its own." else if (calm > 0.6f) "Settled — the birds can hear you." else "Noticed a wander — come back to the breath.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        else {
            Label("Soundscape", Modifier.fillMaxWidth().padding(top = 8.dp))
            ChipRow(Soundscape.KINDS, kind, { kind = it })
            Label("Length", Modifier.fillMaxWidth().padding(top = 8.dp))
            val lengths = if (sleep) listOf(15, 30, 45, 60) else listOf(3, 5, 10, 20)
            ChipRow(lengths.map { "$it min" }, "$minutes min", { minutes = it.substringBefore(' ').toInt() })
            if (!sleep) Text("Rest the phone on your chest or lap and close your eyes. Stillness calms the weather; birds sing when you stay settled.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { running = !running; if (!running) Soundscape.stop() }) { Text(if (running) "End session" else if (sleep) "Start sleep sounds" else "Begin") }
        Spacer(Modifier.height(96.dp))
    }
}

@Composable
private fun History() {
    val c = LocalContext.current
    val h = remember { history(c).let { a -> (0 until a.length()).map { a.getJSONObject(it) } }.reversed() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        val total = h.sumOf { it.optInt("min") }
        Group {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("${h.size}" to "sessions", "$total" to "minutes", "${h.sumOf { it.optInt("birds") }}" to "birds",
                    "${if (h.isEmpty()) 0 else h.sumOf { it.optInt("calm") } / h.size}%" to "avg calm").forEach { (v, l) ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(v, style = MaterialTheme.typography.titleLarge); Text(l, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (h.isEmpty()) Text("Your sessions will show here.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
        h.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                Text(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(s.optLong("at"))),
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text("${s.optInt("min")} min · ${s.optInt("calm")}% · ${s.optInt("birds")} 🐦", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(96.dp))
    }
}
