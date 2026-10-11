package com.nizam.app.feature.deen

import android.app.Activity
import android.content.Context
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nizam.app.AppContainer
import com.nizam.app.feature.quran.Ayah
import com.nizam.app.feature.quran.SURAHS
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.components.Thinking
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import java.time.LocalDate

/**
 * Hifz: recite from memory and Atlas checks it word by word against the Quran text (from the
 * Quran module's own download, never generated), then schedules revision so what you've learned
 * stays learned — spaced out further each time you recite it cleanly.
 *
 * Speech recognition for Quranic Arabic is imperfect, especially with tajweed elongation; treat a
 * flagged word as "check this", not as a verdict. It does not judge tajweed.
 */
object Recitation {
    private val MARKS = Regex("[\\u064B-\\u065F\\u0670\\u06D6-\\u06ED\\u0640\\u08D3-\\u08FF]")

    fun normalise(s: String): String = MARKS.replace(s, "")
        .replace('ٱ', 'ا').replace('أ', 'ا').replace('إ', 'ا').replace('آ', 'ا')
        .replace('ى', 'ي').replace('ة', 'ه').replace('ؤ', 'و').replace('ئ', 'ي')
        .replace(Regex("[^\\u0621-\\u064A\\s]"), " ").replace(Regex("\\s+"), " ").trim()

    /** For each expected word: was it heard? Longest-common-subsequence alignment tolerates skips and extras. */
    fun check(expected: String, heard: String): List<Pair<String, Boolean>> {
        val shown = expected.split(Regex("\\s+")).filter { it.isNotBlank() && normalise(it).isNotBlank() }
        val e = shown.map { normalise(it) }
        val h = normalise(heard).split(" ").filter { it.isNotBlank() }
        val dp = Array(e.size + 1) { IntArray(h.size + 1) }
        for (i in e.indices.reversed()) for (j in h.indices.reversed())
            dp[i][j] = if (close(e[i], h[j])) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val hit = BooleanArray(e.size)
        var i = 0; var j = 0
        while (i < e.size && j < h.size) {
            when {
                close(e[i], h[j]) -> { hit[i] = true; i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> i++
                else -> j++
            }
        }
        return shown.mapIndexed { k, w -> w to hit[k] }
    }

    // the recogniser often drops or adds an alif/waw of elongation; one edit is still the same word
    private fun close(a: String, b: String): Boolean {
        if (a == b) return true
        if (kotlin.math.abs(a.length - b.length) > 1 || a.length < 3) return false
        var edits = 0; var x = 0; var y = 0
        while (x < a.length && y < b.length) {
            if (a[x] == b[y]) { x++; y++; continue }
            if (++edits > 1) return false
            when { a.length > b.length -> x++; a.length < b.length -> y++; else -> { x++; y++ } }
        }
        return edits + (a.length - x) + (b.length - y) <= 1
    }
}

data class HifzEntry(val surah: Int, val memorised: Int, val strength: Int, val lastReview: String)

object HifzStore {
    private fun p(c: Context) = c.getSharedPreferences("hifz", 0)
    private val GAPS = listOf(1, 3, 7, 14, 30, 60)

    fun all(c: Context): List<HifzEntry> = p(c).all.keys.filter { it.startsWith("s") }.mapNotNull { k ->
        runCatching { JSONObject(p(c).getString(k, "{}")!!).let {
            HifzEntry(k.drop(1).toInt(), it.optInt("m"), it.optInt("st"), it.optString("lr")) } }.getOrNull()
    }.sortedBy { it.surah }

    fun get(c: Context, surah: Int) = all(c).firstOrNull { it.surah == surah }

    fun save(c: Context, e: HifzEntry) = p(c).edit().putString("s${e.surah}",
        JSONObject().put("m", e.memorised).put("st", e.strength).put("lr", e.lastReview).toString()).apply()

    fun due(e: HifzEntry): LocalDate = runCatching { LocalDate.parse(e.lastReview) }.getOrElse { LocalDate.now() }
        .plusDays(GAPS[e.strength.coerceIn(0, GAPS.lastIndex)].toLong())

    /** A clean recitation pushes the next review further out; a shaky one brings it back to tomorrow. */
    fun reviewed(c: Context, surah: Int, score: Int, ayat: Int) {
        val old = get(c, surah) ?: HifzEntry(surah, 0, 0, "")
        save(c, old.copy(memorised = maxOf(old.memorised, ayat),
            strength = if (score >= 90) old.strength + 1 else 0, lastReview = LocalDate.now().toString()))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HifzTab(container: AppContainer) {
    val c = LocalContext.current
    var surah by remember { mutableIntStateOf(HifzStore.all(c).minByOrNull { HifzStore.due(it) }?.surah ?: 1) }
    var ayat by remember { mutableStateOf<List<Ayah>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var from by remember { mutableStateOf("1") }
    var to by remember { mutableStateOf("") }
    var hidden by remember { mutableStateOf(true) }
    var result by remember { mutableStateOf<List<Pair<String, Boolean>>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    val meta = SURAHS.first { it.number == surah }

    LaunchedEffect(surah) {
        ayat = emptyList(); error = null; result = emptyList()
        error = container.quranRepository.ensureDownloaded(surah)
        ayat = container.quranRepository.surah(surah).first()
        from = "1"; to = minOf(meta.ayahCount, 7).toString()
    }
    val a = (from.toIntOrNull() ?: 1).coerceIn(1, meta.ayahCount)
    val b = (to.toIntOrNull() ?: a).coerceIn(a, meta.ayahCount)
    val passage = ayat.filter { it.number in a..b }
    val expected = passage.joinToString(" ") { it.arabic }

    val listen = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val heard = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull().orEmpty()
        result = Recitation.check(expected, heard)
        val score = if (result.isEmpty()) 0 else result.count { it.second } * 100 / result.size
        HifzStore.reviewed(c, surah, score, b)
        com.nizam.app.feature.jarvis.Barakah.tick(c, LocalDate.now(), "quran", true)
        tick++
        Feedback.show("$score% recited as written")
    }

    // Revision due today, most overdue first
    val due = remember(tick) { HifzStore.all(c).filter { !HifzStore.due(it).isAfter(LocalDate.now()) } }
    if (due.isNotEmpty()) {
        Label("Due for revision")
        androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(due.size) { i -> val s = SURAHS.first { it.number == due[i].surah }
                com.nizam.app.ui.design.Capsule(s.nameEn, s.number == surah) { surah = s.number } }
        }
    }
    Label("Surah")
    var search by remember { mutableStateOf("") }
    OutlinedTextField(search, { search = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        placeholder = { Text("${meta.number}. ${meta.nameEn} — type to change") })
    if (search.isNotBlank()) SURAHS.filter { it.nameEn.contains(search, true) || it.number.toString() == search.trim() }.take(6).forEach { s ->
        Text("${s.number}. ${s.nameEn}  ${s.nameAr}", style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth().clickable(role = Role.Button) { surah = s.number; search = "" }.padding(vertical = 8.dp))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(from, { from = it.filter(Char::isDigit) }, singleLine = true, label = { Text("From ayah") }, modifier = Modifier.weight(1f))
        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        OutlinedTextField(to, { to = it.filter(Char::isDigit) }, singleLine = true, label = { Text("To (of ${meta.ayahCount})") }, modifier = Modifier.weight(1f))
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 6.dp)) {
        Switch(!hidden, { hidden = !it }); androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        Text(if (hidden) "Text hidden — recite from memory" else "Text shown", style = MaterialTheme.typography.bodyMedium)
    }
    error?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error) }
    if (ayat.isEmpty() && error == null) Thinking(label = "Loading ${meta.nameEn}")

    if (result.isNotEmpty()) {
        Group(Modifier.padding(vertical = 8.dp)) {
            val score = result.count { it.second } * 100 / result.size
            Text("$score% matched · ${result.count { !it.second }} to check", style = MaterialTheme.typography.titleMedium)
            // right-to-left, so the words run in reading order
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalLayoutDirection provides androidx.compose.ui.unit.LayoutDirection.Rtl) {
                FlowRow(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    result.forEach { (w, ok) ->
                        Text(w, style = TextStyle(fontSize = 22.sp, lineHeight = 40.sp, textDirection = TextDirection.Rtl),
                            color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    }
                }
            }
            Text("Words in red weren't heard as written — check them against the mushaf. Tajweed isn't judged.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        }
    } else if (!hidden && passage.isNotEmpty()) {
        Group(Modifier.padding(vertical = 8.dp)) { passage.forEach { Arabic("${it.arabic} ﴿${it.number}﴾", 22) } }
    }
    Row(Modifier.padding(top = 8.dp)) {
        Button(enabled = passage.isNotEmpty(), onClick = {
            result = emptyList()
            listen.launch(android.content.Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ar-SA")
                .putExtra(RecognizerIntent.EXTRA_PROMPT, "Recite ${meta.nameEn} $a–$b"))
        }) { Text("Recite") }
        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        if (result.isNotEmpty()) OutlinedButton(onClick = { result = emptyList() }) { Text("Try again") }
    }

    val mine = remember(tick) { HifzStore.all(c) }
    if (mine.isNotEmpty()) {
        Label("Your hifz")
        mine.forEach { e -> val s = SURAHS.first { it.number == e.surah }
            Row(Modifier.fillMaxWidth().clickable(role = Role.Button) { surah = s.number }.padding(vertical = 8.dp)) {
                Text("${s.number}. ${s.nameEn}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text("${e.memorised}/${s.ayahCount} · next ${HifzStore.due(e)}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val total = mine.sumOf { it.memorised }
        Text("$total ayat reviewed of 6,236.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
