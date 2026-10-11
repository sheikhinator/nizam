package com.nizam.app.feature.future

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.data.prefs.SettingsRepository
import com.nizam.app.net.Ai
import com.nizam.app.net.Http
import com.nizam.app.net.Router
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.components.Thinking
import com.nizam.app.ui.design.Button
import com.nizam.app.ui.design.Group
import com.nizam.app.ui.design.Label
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.rememberWorkScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** Turns a YouTube video or any web page into what matters: its transcript or text, then a summary you choose. */
object Summariser {
    data class Source(val title: String, val text: String, val youtube: String? = null)

    private val UA = mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/126 Mobile Safari/537.36", "Accept-Language" to "en-US,en;q=0.9")

    fun youtubeId(url: String): String? = Regex("(?:youtu\\.be/|v=|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})").find(url)?.groupValues?.get(1)

    suspend fun fetch(url: String): Source {
        youtubeId(url)?.let { id -> return youtube(id) }
        val html = Http.getText(url, UA)
        val title = Regex("<title[^>]*>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(html)?.groupValues?.get(1)?.trim().orEmpty()
        val body = html.replace(Regex("<(script|style|noscript|svg|nav|footer|header)[^>]*>.*?</\\1>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
            .replace(Regex("<br\\s*/?>|</p>|</h\\d>|</li>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">")
            .lines().map { it.replace(Regex("\\s+"), " ").trim() }.filter { it.length > 40 }.joinToString("\n")
        if (body.length < 200) error("Couldn't read that page — it may need a login")
        return Source(title.ifBlank { url }, body.take(24_000))
    }

    /** The video's own captions when YouTube offers them; otherwise Gemini watches it (needs a Gemini key). */
    private suspend fun youtube(id: String): Source {
        val page = runCatching { Http.getText("https://www.youtube.com/watch?v=$id&hl=en", UA) }.getOrDefault("")
        val title = Regex("\"title\":\"(.*?)\"").find(page)?.groupValues?.get(1)?.replace("\\u0026", "&").orEmpty().ifBlank { "YouTube video" }
        val transcript = runCatching {
            val tracks = JSONArray(Regex("\"captionTracks\":(\\[.*?\\])").find(page)!!.groupValues[1])
            val list = (0 until tracks.length()).map { tracks.getJSONObject(it) }
            val pick = list.firstOrNull { it.optString("languageCode").startsWith("en") } ?: list.first()
            val base = pick.optString("baseUrl").replace("\\u0026", "&")
            val j = JSONObject(Http.getText("$base&fmt=json3", UA))
            val ev = j.optJSONArray("events") ?: JSONArray()
            (0 until ev.length()).mapNotNull { ev.getJSONObject(it).optJSONArray("segs") }
                .joinToString(" ") { s -> (0 until s.length()).joinToString("") { s.getJSONObject(it).optString("utf8") } }
                .replace(Regex("\\s+"), " ").trim()
        }.getOrNull()
        return Source(title, transcript?.takeIf { it.length > 100 }?.take(30_000) ?: "", youtube = "https://www.youtube.com/watch?v=$id")
    }

    val MODES = linkedMapOf(
        "Key points" to "The key points as 5–8 short bullets, most important first, then one line: the takeaway.",
        "Short" to "A 3-sentence summary.",
        "Detailed" to "A detailed summary with headings, every important idea, numbers and names kept.",
        "Action steps" to "Only the practical steps someone should take, as a numbered list.",
        "اردو" to "خلاصہ اردو میں، اہم نکات بلٹس میں۔",
    )

    suspend fun summarise(s: SettingsRepository, src: Source, mode: String, question: String? = null): String {
        val ask = question?.let { "Answer this question about it: $it" } ?: (MODES[mode] ?: MODES.getValue("Key points"))
        if (src.text.isNotBlank()) return Ai.generate(s, "Title: ${src.title}\n\nContent:\n${src.text}\n\n$ask",
            "You summarise faithfully. Never invent anything that isn't in the content.", 1400)
        // no captions: let Gemini watch the video itself
        val key = Router.firstKey(s.geminiApiKey.first()).ifBlank { error("This video has no captions — add a Gemini key and Atlas can watch it for you") }
        val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
            .put(JSONObject().put("file_data", JSONObject().put("file_uri", src.youtube)))
            .put(JSONObject().put("text", ask)))))
        for (m in Router.GEMINI_CHAIN.take(3)) {
            val r = runCatching { JSONObject(Http.postJson("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=$key", body.toString())) }.getOrNull() ?: continue
            val parts = r.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: continue
            val t = (0 until parts.length()).joinToString("") { parts.getJSONObject(it).optString("text") }
            if (t.isNotBlank()) return t
        }
        error("Couldn't watch this video right now — try again in a minute")
    }
}

@Composable
fun SummariseScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberWorkScope()
    var link by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("Key points") }
    var src by remember { mutableStateOf<Summariser.Source?>(null) }
    var out by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    val speaker = remember { com.nizam.app.feature.voice.Speaker(c) }
    DisposableEffect(Unit) { onDispose { speaker.release() } }
    // a link on the clipboard is almost always the one you came to summarise
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(400)
        runCatching { c.getSystemService(android.content.ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.toString()?.trim() }.getOrNull()
            ?.takeIf { it.startsWith("http") && link.isBlank() }?.let { link = it }
    }
    fun run(q: String? = null) {
        busy = true; if (q == null) out = ""
        scope.launch {
            runCatching {
                val s = src?.takeIf { q != null } ?: Summariser.fetch(link.trim()).also { src = it }
                Summariser.summarise(container.settingsRepository, s, mode, q)
            }.onSuccess { out = if (q != null) "Q: $q\n\n$it" else it }.onFailure { Feedback.failed("Summarising", it) }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)) {
        ModuleTitle("Summarise", "Paste a YouTube or web link")
        OutlinedTextField(link, { link = it; src = null }, singleLine = true, modifier = Modifier.fillMaxWidth(), placeholder = { Text("https://…") })
        Label("How"); ChipRow(Summariser.MODES.keys.toList(), mode, { mode = it })
        Button(enabled = link.startsWith("http") && !busy, onClick = { run() }, modifier = Modifier.padding(top = 12.dp)) { Text("Summarise") }
        if (busy) Thinking(label = if (Summariser.youtubeId(link) != null) "Watching the video" else "Reading the page")
        if (out.isNotBlank()) {
            src?.let { Label(it.title) }
            Group { SelectionContainer { Text(out, style = MaterialTheme.typography.bodyLarge) } }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { speaker.speak(out, com.nizam.app.feature.future.Lang.locale(c)) }) { Text("🔊 Read aloud") }
                OutlinedButton(onClick = { speaker.stop() }) { Text("Stop") }
                OutlinedButton(onClick = { scope.launch {
                    container.noteRepository.createFull("Summaries/${src?.title?.take(60) ?: "Summary"}", "Source: $link\n\n$out"); Feedback.show("Saved to Notes") } }) { Text("Save to Notes") }
            }
            Label("Ask about it")
            Row {
                OutlinedTextField(question, { question = it }, singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("What did they say about…") })
                Spacer(Modifier.padding(4.dp))
                Button(enabled = question.isNotBlank() && !busy, onClick = { run(question.trim()); question = "" }) { Text("Ask") }
            }
        }
        Spacer(Modifier.height(60.dp))
    }
}
