package com.nizam.app.feature.omni

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.net.Ai
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.LocalDate

/** Small shared pieces for the Omni features: AI calls, JSON, a day of your life as numbers, sound in and out. */
object O {
    fun container(c: Context) = (c.applicationContext as com.nizam.app.NizamApplication).container

    suspend fun ai(c: Context, prompt: String, system: String = "Be concise and concrete.", tokens: Int = 900, web: Boolean = false): String =
        runCatching { Ai.generate(container(c).settingsRepository, prompt, system = system, maxOutputTokens = tokens, useWeb = web) }.getOrElse { "Couldn't think that through: ${it.message}" }.trim()

    suspend fun aiJson(c: Context, prompt: String, tokens: Int = 1200, web: Boolean = false): JSONObject? =
        runCatching { com.nizam.app.feature.nx.EngineX.json(Ai.generate(container(c).settingsRepository, prompt, system = "Output only JSON.", maxOutputTokens = tokens, useWeb = web)) }.getOrNull()

    fun arr(a: JSONArray?): List<JSONObject> = a?.let { x -> (0 until x.length()).mapNotNull { x.optJSONObject(it) } }.orEmpty()
    fun strs(a: JSONArray?): List<String> = a?.let { x -> (0 until x.length()).map { x.optString(it) } }.orEmpty()

    // ── a day of your life, as numbers ──
    val METRICS = listOf("tasks_done", "tasks_open", "spent", "income", "prayers", "habits", "water_ml", "gym_sets", "mood", "diary_words", "screen_unlocks")
    val METRIC_NAMES = mapOf("tasks_done" to "Tasks done", "tasks_open" to "Tasks left", "spent" to "Spent (Rs)", "income" to "Income (Rs)", "prayers" to "Prayers prayed", "fajr" to "Fajr prayed",
        "habits" to "Habits ticked", "water_ml" to "Water (ml)", "gym_sets" to "Gym sets", "mood" to "Mood (1-5)", "diary_words" to "Words written", "screen_unlocks" to "Phone unlocks")
    /** Higher is better for these; for the rest lower is better. */
    val GOOD_UP = setOf("tasks_done", "income", "prayers", "habits", "water_ml", "gym_sets", "mood", "diary_words")

    suspend fun day(ct: AppContainer, date: LocalDate): JSONObject = withContext(Dispatchers.IO) {
        val d = date.toString()
        val zone = java.time.ZoneId.systemDefault()
        val start = date.atStartOfDay(zone).toInstant().toEpochMilli(); val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        val o = JSONObject().put("date", d)
        runCatching { val tasks = ct.taskRepository.all().first()
            o.put("tasks_done", tasks.count { (it.completedAt ?: 0) in start until end })
            o.put("tasks_open", tasks.count { it.completedAt == null && it.deletedAt == null && (it.due ?: Long.MAX_VALUE) < end }) }
        runCatching { val tx = ct.financeRepository.since(d).first().filter { it.localDate == d }
            o.put("spent", tx.filter { it.kind == "EXPENSE" }.sumOf { it.amount }); o.put("income", tx.filter { it.kind == "INCOME" }.sumOf { it.amount }) }
        runCatching { ct.prayerRepository.day(d).first()?.let { p -> o.put("prayers", listOf(p.fajr, p.dhuhr, p.asr, p.maghrib, p.isha).count { it != "NOT_PRAYED" && it != "MISSED" && it != "QAZA" })
            o.put("fajr", if (p.fajr != "NOT_PRAYED" && p.fajr != "MISSED" && p.fajr != "QAZA") 1 else 0) } ?: o.put("prayers", 0).put("fajr", 0) }
        runCatching { o.put("habits", ct.habitRepository.entriesFor(d).first().size) }
        runCatching { o.put("water_ml", ct.dietRepository.water(d).first()?.ml ?: 0) }
        runCatching { o.put("gym_sets", ct.gymRepository.sets().first().count { it.localDate == d }) }
        runCatching { val e = ct.diaryRepository.entries().first().filter { it.localDate == d }
            if (e.isNotEmpty()) o.put("mood", e.map { it.mood }.average()); o.put("diary_words", e.sumOf { it.body.split(Regex("\\s+")).size }) }
        runCatching { o.put("screen_unlocks", Aura.unlocks(ct.appContext, d)) }
        o
    }

    suspend fun days(ct: AppContainer, n: Int, end: LocalDate = LocalDate.now()): List<JSONObject> = (n - 1 downTo 0).map { day(ct, end.minusDays(it.toLong())) }
    fun num(o: JSONObject, k: String) = o.optDouble(k, 0.0).let { if (it.isNaN()) 0.0 else it }
    fun fmt(v: Double) = if (v >= 1000) "%,.0f".format(v) else if (v % 1.0 == 0.0) v.toInt().toString() else "%.1f".format(v)

    // ── sound ──
    /** Records [seconds] of mono 16 kHz audio from the mic and returns it as a WAV file. Needs RECORD_AUDIO. */
    @android.annotation.SuppressLint("MissingPermission")
    suspend fun recordWav(seconds: Int): ByteArray = withContext(Dispatchers.IO) {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.MIC, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate * 2))
        val pcm = ByteArrayOutputStream(); val buf = ByteArray(maxOf(min, 4096))
        rec.startRecording()
        val until = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < until) { val n = rec.read(buf, 0, buf.size); if (n > 0) pcm.write(buf, 0, n) }
        rec.stop(); rec.release()
        wav(pcm.toByteArray(), rate)
    }
    fun wav(pcm: ByteArray, rate: Int): ByteArray {
        val out = ByteArrayOutputStream()
        fun i32(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff); out.write(v shr 16 and 0xff); out.write(v shr 24 and 0xff) }
        fun i16(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff) }
        out.write("RIFF".toByteArray()); i32(36 + pcm.size); out.write("WAVE".toByteArray()); out.write("fmt ".toByteArray())
        i32(16); i16(1); i16(1); i32(rate); i32(rate * 2); i16(2); i16(16); out.write("data".toByteArray()); i32(pcm.size); out.write(pcm)
        return out.toByteArray()
    }
    fun play(c: Context, wav: ByteArray) {
        val f = java.io.File(c.cacheDir, "omni_play.wav"); f.writeBytes(wav)
        runCatching { android.media.MediaPlayer().apply { setDataSource(f.path); setOnCompletionListener { it.release() }; prepare(); start() } }
    }

    /** Asks an AI that can hear (Gemini) about a recording. */
    suspend fun hear(c: Context, prompt: String, wav: ByteArray): String = withContext(Dispatchers.IO) {
        val settings = container(c).settingsRepository
        val list = com.nizam.app.net.Router.endpoints(settings, com.nizam.app.net.Router.appContext).filter { it.kind == "GEMINI" }
        if (list.isEmpty()) return@withContext "Listening needs a Gemini key (Settings › AI). Gemini can hear audio; the other providers can't."
        val b64 = Base64.encodeToString(wav, Base64.NO_WRAP)
        runCatching {
            com.nizam.app.net.Engine.run(list, hedgeAfterMs = null) { e ->
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                    .put(JSONObject().put("text", prompt)).put(JSONObject().put("inline_data", JSONObject().put("mime_type", "audio/wav").put("data", b64))))))
                val r = JSONObject(com.nizam.app.net.Http.postJson("${e.url}/${e.model}:generateContent?key=${e.key}", body.toString()))
                r.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    ?.let { p -> (0 until p.length()).joinToString("") { p.getJSONObject(it).optString("text") } }?.takeIf { it.isNotBlank() } ?: error("No answer")
            }
        }.getOrElse { "Couldn't listen: ${it.message}" }
    }

    suspend fun see(c: Context, prompt: String, bmp: Bitmap): String =
        runCatching { com.nizam.app.net.Vision.ask(container(c).settingsRepository, prompt, bmp) }.getOrElse { "Couldn't look at it: ${it.message}" }

    fun notify(c: Context, id: Int, title: String, body: String, route: String? = null) =
        runCatching { com.nizam.app.feature.notify.Notifications.post(c, com.nizam.app.feature.notify.Notifications.CHANNEL_NUDGE, id, title, body, route = route) }

    @android.annotation.SuppressLint("MissingPermission")
    fun location(c: Context): android.location.Location? = runCatching {
        val lm = c.getSystemService(android.location.LocationManager::class.java)
        lm.getProviders(true).mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }.getOrNull()
}

// ─────────────────────────── a tiny UI kit so every Omni screen looks the same ───────────────────────────
@Composable
fun OCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
        .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = content)
}
@Composable fun OTitle(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
@Composable fun OMuted(t: String) = Text(t, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
@Composable fun OText(t: String) = Text(t, style = MaterialTheme.typography.bodyMedium)
@Composable fun OBtn(t: String, enabled: Boolean = true, onClick: () -> Unit) = com.nizam.app.ui.design.Button(onClick = onClick, enabled = enabled) { Text(t) }
@Composable fun OBtn2(t: String, enabled: Boolean = true, onClick: () -> Unit) = com.nizam.app.ui.design.OutlinedButton(onClick = onClick, enabled = enabled) { Text(t) }
@Composable fun OField(v: String, label: String, lines: Int = 1, onChange: (String) -> Unit) =
    OutlinedTextField(v, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, minLines = lines, maxLines = maxOf(lines, 6),
        trailingIcon = { com.nizam.app.feature.nx.MicButton({ onChange(if (v.isBlank()) it else "$v $it") }) })
@Composable fun ORow(content: @Composable () -> Unit) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
@Composable fun OBar(frac: Float, color: Color = MaterialTheme.colorScheme.primary) = Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))) {
    Box(Modifier.fillMaxWidth(frac.coerceIn(0.01f, 1f)).height(8.dp).background(color)) }
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable fun OChips(options: List<String>, selected: String, onPick: (String) -> Unit) =
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        options.forEach { com.nizam.app.ui.design.Capsule(it, it == selected) { onPick(it) } } }
@Composable fun OBusy(on: Boolean, label: String = "Thinking…") { if (on) Row { androidx.compose.material3.CircularProgressIndicator(Modifier.height(18.dp)); Text("  $label", style = MaterialTheme.typography.bodySmall) } }
@Composable fun OMd(t: String) = com.nizam.app.feature.nx.Md(t)
