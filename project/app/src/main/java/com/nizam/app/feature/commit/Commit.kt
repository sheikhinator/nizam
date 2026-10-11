package com.nizam.app.feature.commit

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.core.fmtAmount
import com.nizam.app.feature.growth.Challenge
import com.nizam.app.feature.growth.ChallengeStore
import com.nizam.app.feature.insight.DayVector
import com.nizam.app.feature.insight.Days
import com.nizam.app.feature.insight.METRICS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.random.Random

private fun Context.arr(n: String) = runCatching { JSONArray(File(filesDir, n).readText()) }.getOrElse { JSONArray() }
private fun Context.put(n: String, a: JSONArray) = File(filesDir, n).writeText(a.toString())

@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) { Column(Modifier.padding(16.dp)) { content() } }
}

@Composable
fun CommitScreen(container: AppContainer) {
    var tab by remember { mutableStateOf("48-hour cart") }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Commit", "Wait before buying, stake your goals, and sound for focus")
        ChipRow(listOf("48-hour cart", "Stake it", "Soundscapes"), tab, { tab = it })
        Spacer(Modifier.height(8.dp))
        when (tab) { "48-hour cart" -> Cart(); "Stake it" -> Stakes(container); else -> Sounds() }
        Spacer(Modifier.height(60.dp))
    }
}

// ── 48-hour cart: most wants evaporate if you let them wait ───────────────────
@Composable
private fun Cart() {
    val c = LocalContext.current
    var items by remember { mutableStateOf(c.arr("cart.json")) }
    var name by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }
    fun save(a: JSONArray) { c.put("cart.json", a); items = JSONArray(a.toString()) }
    val list = (0 until items.length()).map { items.getJSONObject(it) }
    val skipped = list.filter { it.optString("state") == "skipped" }.sumOf { it.optDouble("price") }
    Card {
        Text("💰 ${fmtAmount(skipped)} PKR saved by waiting", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Text("Add a want instead of buying it. Atlas asks again after 48 hours — most of the time, the want is gone.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(name, { name = it }, label = { Text("What do you want?") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(price, { price = it.filter { ch -> ch.isDigit() } }, label = { Text("Price (PKR)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Button(enabled = name.isNotBlank(), onClick = {
            val a = JSONArray(items.toString()); a.put(JSONObject().put("name", name.trim()).put("price", price.toDoubleOrNull() ?: 0.0)
                .put("added", System.currentTimeMillis()).put("state", "waiting")); save(a); name = ""; price = ""
        }) { Text("Add to cart — decide in 48h") }
    }
    list.forEachIndexed { i, it ->
        val hours = (System.currentTimeMillis() - it.optLong("added")) / 3_600_000
        val state = it.optString("state")
        Card {
            Text("${it.optString("name")} · ${fmtAmount(it.optDouble("price"))} PKR", style = MaterialTheme.typography.titleMedium)
            when {
                state == "bought" -> Text("Bought — after thinking it over.", style = MaterialTheme.typography.labelMedium)
                state == "skipped" -> Text("Skipped. Money kept.", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                hours < 48 -> Text("Waiting… ask again in ${48 - hours}h", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Text("It's been ${hours / 24} days. Do you still want it?", color = MaterialTheme.colorScheme.primary)
                    Row {
                        OutlinedButton(onClick = { val a = JSONArray(items.toString()); a.getJSONObject(i).put("state", "skipped"); save(a) }) { Text("Not anymore") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { val a = JSONArray(items.toString()); a.getJSONObject(i).put("state", "bought"); save(a) }) { Text("Yes, buying it") }
                    }
                }
            }
        }
    }
}

// ── Stake it: a goal with something on the line, verified from your own data ─
@Composable
private fun Stakes(container: AppContainer) {
    val c = LocalContext.current
    var stakes by remember { mutableStateOf(c.arr("stakes.json")) }
    var days by remember { mutableStateOf<List<DayVector>>(emptyList()) }
    LaunchedEffect(Unit) { days = runCatching { Days.build(container, c, 120) }.getOrElse { emptyList() } }
    var title by remember { mutableStateOf("") }
    var metric by remember { mutableStateOf("prayers") }
    var target by remember { mutableStateOf("5") }
    var length by remember { mutableStateOf("14") }
    var amount by remember { mutableStateOf("1000") }
    var cause by remember { mutableStateOf("") }
    Card {
        Text("Stake a goal", style = MaterialTheme.typography.titleMedium)
        Text("Pledge an amount you'll give away if you don't make it — to charity, or to a cause you'd hate supporting, " +
            "for extra bite. Atlas judges from your data, so there's no fudging. You hold yourself to paying.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(title, { title = it }, label = { Text("Goal, e.g. two weeks of all five prayers") }, modifier = Modifier.fillMaxWidth())
        ChipRow(METRICS.keys.filter { it != "spend" }, metric, { metric = it }, labels = { METRICS[it] ?: it })
        Row {
            OutlinedTextField(target, { target = it }, label = { Text("Daily target") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(length, { length = it.filter(Char::isDigit) }, label = { Text("Days") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Row {
            OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text("Stake (PKR)") }, singleLine = true, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            OutlinedTextField(cause, { cause = it }, label = { Text("Goes to…") }, singleLine = true, modifier = Modifier.weight(1.4f))
        }
        Button(enabled = title.isNotBlank() && cause.isNotBlank(), onClick = {
            Sign.withFingerprint(c, "Sign your commitment", "“${title.trim()}” — ${amount} PKR on the line") { signed ->
            if (!signed) return@withFingerprint
            val a = JSONArray(stakes.toString())
            a.put(JSONObject().put("id", System.currentTimeMillis()).put("title", title.trim()).put("metric", metric)
                .put("target", target.toDoubleOrNull() ?: 1.0).put("days", (length.toIntOrNull() ?: 14).coerceIn(3, 90))
                .put("start", LocalDate.now().toString()).put("amount", amount.toIntOrNull() ?: 0).put("cause", cause.trim())
                .put("signed", System.currentTimeMillis()))
            c.put("stakes.json", a); stakes = a; title = ""; cause = ""
            }
        }) { Text("✍ Sign & commit") }
    }
    (0 until stakes.length()).map { stakes.getJSONObject(it) }.reversed().forEach { s ->
        val ch = Challenge(s.optLong("id"), s.optString("title"), s.optString("metric"), s.optDouble("target"), s.optString("start"), s.optInt("days"))
        val p = ChallengeStore.progress(ch, days)
        val needed = (ch.days * 0.8).toInt()
        Card {
            Text(ch.title, style = MaterialTheme.typography.titleMedium)
            if (s.optLong("signed") > 0) Text("✍ Signed " + java.time.Instant.ofEpochMilli(s.optLong("signed")).atZone(java.time.ZoneId.systemDefault()).toLocalDate(),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text("${s.optInt("amount")} PKR to ${s.optString("cause")} if you miss · need $needed of ${ch.days} days",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${p.hit} days hit so far · day ${p.elapsed}", style = MaterialTheme.typography.bodyMedium)
            if (p.done) Text(if (p.won) "✅ Kept. Your ${s.optInt("amount")} PKR stays with you."
                else "❌ Missed (${p.hit}/$needed). Time to pay ${s.optInt("amount")} PKR to ${s.optString("cause")} — keep your word.",
                color = if (p.won) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            else if (p.elapsed - p.hit > ch.days - needed) Text("⚠ You can't make it now without a perfect run — but a perfect run is still possible.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
    }
}

// ── Soundscapes: generated live, so no files and fully offline ────────────────
@Composable
private fun Sounds() {
    var playing by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(playing) {
        val kind = playing ?: return@LaunchedEffect
        withContext(Dispatchers.Default) {
            val rate = 22050
            val buf = ShortArray(rate / 10)
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(buf.size * 4).build()
            track.play()
            val rnd = Random(1); var brown = 0.0; var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var drops = 0.0
            try {
                while (isActive) {
                    for (i in buf.indices) {
                        val white = rnd.nextDouble() * 2 - 1
                        val v = when (kind) {
                            "Brown" -> { brown = (brown + 0.02 * white) / 1.02; brown * 3.2 }
                            "Pink" -> { b0 = 0.99765 * b0 + white * 0.099; b1 = 0.963 * b1 + white * 0.2965; b2 = 0.57 * b2 + white * 1.0526
                                (b0 + b1 + b2 + white * 0.1848) * 0.11 }
                            else -> {   // rain: soft brown bed plus random droplets
                                brown = (brown + 0.02 * white) / 1.02
                                if (rnd.nextDouble() < 0.0009) drops = 0.5 + rnd.nextDouble() * 0.4
                                drops *= 0.985
                                brown * 2.2 + white * drops * 0.35
                            }
                        }
                        buf[i] = (v.coerceIn(-1.0, 1.0) * 9000).toInt().toShort()
                    }
                    track.write(buf, 0, buf.size)
                }
            } finally { track.stop(); track.release() }
        }
    }
    Card {
        Text("Focus soundscapes", style = MaterialTheme.typography.titleMedium)
        Text("Generated live on your phone — no files, works offline. Plays while this screen is open.",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipRow(listOf("Brown", "Pink", "Rain"), playing ?: "", { playing = if (playing == it) null else it },
            labels = { mapOf("Brown" to "🌊 Brown noise", "Pink" to "🌸 Pink noise", "Rain" to "🌧 Rain")[it] ?: it })
        if (playing != null) OutlinedButton(onClick = { playing = null }) { Text("Stop") }
        Text("Brown is deep and rumbly (great for ADHD-style focus); pink is softer and even; rain adds gentle droplets.",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}


/** Seals a commitment with your fingerprint (Android's own prompt — no library). Phones without one just confirm. */
object Sign {
    fun withFingerprint(c: Context, title: String, subtitle: String, done: (Boolean) -> Unit) {
        if (android.os.Build.VERSION.SDK_INT < 28) { done(true); return }
        val prompt = android.hardware.biometrics.BiometricPrompt.Builder(c).setTitle(title).setSubtitle(subtitle)
            .setDescription("Your fingerprint seals this promise to yourself.")
            .setNegativeButton("Not yet", c.mainExecutor) { _, _ -> done(false) }.build()
        runCatching {
            prompt.authenticate(android.os.CancellationSignal(), c.mainExecutor,
                object : android.hardware.biometrics.BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(r: android.hardware.biometrics.BiometricPrompt.AuthenticationResult) = done(true)
                    override fun onAuthenticationError(code: Int, msg: CharSequence) {
                        // no fingerprint enrolled → don't block the commitment, just proceed unsigned-by-biometric
                        if (code == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_NO_BIOMETRICS ||
                            code == android.hardware.biometrics.BiometricPrompt.BIOMETRIC_ERROR_HW_NOT_PRESENT) done(true)
                    }
                })
        }.onFailure { done(true) }
    }
}
