package com.nizam.app.feature.semantic

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.AppContainer
import com.nizam.app.feature.nx.Roads
import com.nizam.app.feature.nx.ScreenSpec
import com.nizam.app.feature.nx.Spec
import com.nizam.app.ui.design.Hub
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.text.Normalizer
import kotlin.math.sqrt

/**
 * Search by meaning, on the phone: a small sentence model (all-MiniLM-L6, 90 MB, downloaded once) turns
 * what you recorded into numbers, and a question finds the closest ones even when no word matches.
 */
object Semantic {
    private const val BASE = "https://huggingface.co/Qdrant/all-MiniLM-L6-v2-onnx/resolve/main/"
    private const val MODEL_SIZE = 90_387_630L
    private const val VOCAB_SIZE = 231_508L
    private const val DIM = 384
    private const val MAXLEN = 128

    private fun dir(c: Context) = File(c.filesDir, "semantic").also { it.mkdirs() }
    private fun model(c: Context) = File(dir(c), "model.onnx")
    private fun vocabFile(c: Context) = File(dir(c), "vocab.txt")
    fun ready(c: Context) = model(c).length() == MODEL_SIZE && vocabFile(c).length() == VOCAB_SIZE
    fun remove(c: Context) { synchronized(this) { runCatching { session?.close() }; session = null; vocab = null }; dir(c).deleteRecursively() }

    @Volatile private var session: OrtSession? = null
    @Volatile private var vocab: HashMap<String, Int>? = null

    suspend fun download(c: Context, progress: (Float) -> Unit): String? = withContext(Dispatchers.IO) {
        try {
            val http = OkHttpClient.Builder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
            val total = MODEL_SIZE + VOCAB_SIZE
            var done = 0L
            for ((name, dest, size) in listOf(Triple("vocab.txt", vocabFile(c), VOCAB_SIZE), Triple("model.onnx", model(c), MODEL_SIZE))) {
                if (dest.length() == size) { done += size; continue }
                val part = File(dest.parentFile, dest.name + ".part")
                http.newCall(Request.Builder().url(BASE + name).build()).execute().use { r ->
                    if (!r.isSuccessful) return@withContext "Download failed (${r.code})"
                    val body = r.body ?: return@withContext "Download failed (empty)"
                    body.byteStream().use { ins -> part.outputStream().use { os ->
                        val buf = ByteArray(1 shl 16)
                        while (true) { val n = ins.read(buf); if (n < 0) break; os.write(buf, 0, n); done += n; progress((done.toFloat() / total).coerceIn(0f, 1f)) }
                    } }
                }
                if (part.length() != size) { part.delete(); return@withContext "The download was cut short. Try again." }
                dest.delete(); part.renameTo(dest)
            }
            null
        } catch (t: Throwable) { "Download stopped: ${t.message ?: t.javaClass.simpleName}" }
    }

    // ---- tokenizer (BERT uncased WordPiece) ----
    private fun vocab(c: Context): HashMap<String, Int> = vocab ?: synchronized(this) {
        vocab ?: HashMap<String, Int>(32_000).also { m -> vocabFile(c).useLines { ls -> ls.forEachIndexed { i, l -> m[l] = i } }; vocab = m }
    }

    private fun words(text: String): List<String> {
        val n = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        val out = ArrayList<String>(); val cur = StringBuilder()
        for (ch in n) {
            if (Character.getType(ch) == Character.NON_SPACING_MARK.toInt()) continue
            when {
                ch.isWhitespace() -> if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }
                ch.isLetterOrDigit() -> cur.append(ch)
                else -> { if (cur.isNotEmpty()) { out += cur.toString(); cur.setLength(0) }; out += ch.toString() }
            }
        }
        if (cur.isNotEmpty()) out += cur.toString()
        return out
    }

    private fun pieces(w: String, v: Map<String, Int>): List<Int> {
        val unk = v["[UNK]"] ?: 100
        if (w.length > 100) return listOf(unk)
        val ids = ArrayList<Int>(); var s = 0
        while (s < w.length) {
            var e = w.length; var cur: Int? = null
            while (s < e) {
                val sub = (if (s > 0) "##" else "") + w.substring(s, e)
                val id = v[sub]
                if (id != null) { cur = id; break }
                e--
            }
            if (cur == null) return listOf(unk)
            ids += cur; s = e
        }
        return ids
    }

    private fun encode(c: Context, text: String): List<Int> {
        val v = vocab(c)
        val ids = ArrayList<Int>(); ids += v["[CLS]"] ?: 101
        for (w in words(text)) ids += pieces(w, v)
        val cut = if (ids.size > MAXLEN - 1) ids.subList(0, MAXLEN - 1).toMutableList() else ids
        cut += v["[SEP]"] ?: 102
        return cut
    }

    private fun open(c: Context): OrtSession = session ?: synchronized(this) {
        session ?: OrtEnvironment.getEnvironment().createSession(model(c).absolutePath, OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }).also { session = it }
    }

    /** Unit-length meaning vectors for [texts]. */
    @Suppress("UNCHECKED_CAST")
    fun embed(c: Context, texts: List<String>): List<FloatArray> {
        if (texts.isEmpty()) return emptyList()
        val enc = texts.map { encode(c, it) }
        val m = enc.maxOf { it.size }
        val ids = Array(enc.size) { r -> LongArray(m) { k -> enc[r].getOrElse(k) { 0 }.toLong() } }
        val mask = Array(enc.size) { r -> LongArray(m) { k -> if (k < enc[r].size) 1L else 0L } }
        val types = Array(enc.size) { LongArray(m) }
        val env = OrtEnvironment.getEnvironment()
        val a = OnnxTensor.createTensor(env, ids); val b = OnnxTensor.createTensor(env, mask); val t = OnnxTensor.createTensor(env, types)
        try {
            open(c).run(mapOf("input_ids" to a, "attention_mask" to b, "token_type_ids" to t)).use { res ->
                val hidden = res.get(0).value as Array<Array<FloatArray>>
                return hidden.mapIndexed { r, tokens ->
                    val v = FloatArray(DIM); var n = 0
                    for (k in tokens.indices) if (mask[r][k] == 1L) { n++; for (d in 0 until DIM) v[d] += tokens[k][d] }
                    var norm = 0.0
                    for (d in 0 until DIM) { v[d] /= maxOf(1, n); norm += v[d] * v[d] }
                    val s = sqrt(norm).toFloat().let { if (it < 1e-9f) 1f else it }
                    for (d in 0 until DIM) v[d] /= s
                    v
                }
            }
        } finally { a.close(); b.close(); t.close() }
    }

    // ---- index over everything Roads can see ----
    class Hit(val text: String, val source: String, val score: Float)
    private val cache = HashMap<Long, FloatArray>()
    private var loaded = false
    private fun indexFile(c: Context) = File(dir(c), "index.bin")
    private fun key(s: String): Long { var h = 1125899906842597L; for (ch in s) h = 31 * h + ch.code; return h }

    @Synchronized private fun load(c: Context) {
        if (loaded) return
        loaded = true
        runCatching {
            val f = indexFile(c); if (!f.exists()) return
            DataInputStream(f.inputStream().buffered()).use { i ->
                repeat(i.readInt()) { val k = i.readLong(); cache[k] = FloatArray(DIM) { i.readFloat() } }
            }
        }
    }

    @Synchronized private fun save(c: Context) {
        runCatching {
            DataOutputStream(indexFile(c).outputStream().buffered()).use { o ->
                o.writeInt(cache.size); cache.forEach { (k, v) -> o.writeLong(k); v.forEach { o.writeFloat(it) } }
            }
        }
    }

    suspend fun search(c: Context, container: AppContainer, question: String, limit: Int = 8): List<Hit> = withContext(Dispatchers.Default) {
        if (!ready(c)) return@withContext emptyList()
        Roads.warm(container)
        load(c)
        val items = Roads.items(c, 120).filter { it.text.length > 8 }.take(600)
        val fresh = items.filter { key(it.text) !in cache }
        fresh.chunked(16).forEach { batch ->
            embed(c, batch.map { it.text }).forEachIndexed { i, v -> synchronized(this@Semantic) { cache[key(batch[i].text)] = v } }
        }
        if (fresh.isNotEmpty()) save(c)
        val q = embed(c, listOf(question)).first()
        items.mapNotNull { it ->
            val v = cache[key(it.text)] ?: return@mapNotNull null
            var dot = 0f; for (d in 0 until DIM) dot += q[d] * v[d]
            Hit(it.text, it.source, dot)
        }.sortedByDescending { it.score }.take(limit)
    }
}

@Composable
internal fun SemanticScreen(container: AppContainer) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(Semantic.ready(c)) }
    var prog by remember { mutableStateOf<Float?>(null) }
    var msg by remember { mutableStateOf("") }
    var q by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf<List<Semantic.Hit>>(emptyList()) }
    // No verticalScroll: this screen is reached through the generic tool-screen shell, which already
    // scrolls its content in a LazyColumn — nesting another scrollable Column crashes Compose.
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Find things by what they mean", style = MaterialTheme.typography.titleMedium)
        Text("Ask in your own words, like \"what did I spend on food\" or \"when was my knee hurting\". Atlas finds the closest things you recorded, even if the words are different.",
            style = MaterialTheme.typography.bodyMedium)
        if (!ready) {
            Text("This needs a small model downloaded once (about 90 MB). After that it works offline.", style = MaterialTheme.typography.bodyMedium)
            val p = prog
            if (p != null) { LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth()); Text("${(p * 100).toInt()}%") }
            else Button(onClick = { prog = 0f; msg = ""; scope.launch { val e = Semantic.download(c) { prog = it }; prog = null; ready = Semantic.ready(c); msg = e ?: "Ready." } }) { Text("Download the search model") }
        } else {
            OutlinedTextField(q, { q = it }, label = { Text("Ask about your life") }, modifier = Modifier.fillMaxWidth())
            Button(enabled = q.isNotBlank() && !busy, onClick = {
                busy = true; msg = ""
                scope.launch { hits = runCatching { Semantic.search(c, container, q) }.getOrElse { msg = "Search failed: ${it.message}"; emptyList() }; busy = false }
            }) { Text(if (busy) "Searching…" else "Search") }
            hits.forEach { h ->
                Column(Modifier.fillMaxWidth()) {
                    Text(h.text, style = MaterialTheme.typography.bodyMedium)
                    Text("${h.source} · match ${(h.score * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!busy && hits.isEmpty() && q.isNotBlank() && msg.isBlank()) Text("Nothing yet. Add more to Atlas and try again.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { Semantic.remove(c); ready = false; hits = emptyList() }) { Text("Remove the model") }
        }
        if (msg.isNotBlank()) Text(msg, style = MaterialTheme.typography.bodySmall)
    }
}

object SemanticSpecs {
    val all: List<Spec> = listOf(
        ScreenSpec("semantic", "Search by meaning", "Find what you recorded by what it means, on your phone", Icons.Outlined.Psychology, Hub.Memory,
            "semantic search meaning find similar embedding vector smart search") { c, _ -> SemanticScreen(c) },
    )
}
