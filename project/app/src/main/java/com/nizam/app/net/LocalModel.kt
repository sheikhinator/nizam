package com.nizam.app.net

import android.content.Context
import android.net.Uri
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Offline AI: a small model running on the phone itself (MediaPipe LLM Inference), used when there's
 * no internet — or when every online provider has failed. No data leaves the device.
 *
 * The model file isn't bundled (they're hundreds of MB); you import a MediaPipe `.task` model once,
 * e.g. Gemma 3 1B IT (int4) from Hugging Face's litert-community. Expect short, simple answers —
 * a 1B model is a pocket assistant, not a replacement for the online ones.
 */
object LocalModel {
    private var engine: LlmInference? = null
    private var enginePath = ""

    fun dir(c: Context) = File(c.filesDir, "models").apply { mkdirs() }

    /** Every usable model on the phone (half-finished downloads end in .part and are skipped). */
    fun models(c: Context): List<File> = dir(c).listFiles()?.filter { it.isFile && !it.name.endsWith(".part") && it.length() > 10_000_000 }
        ?.sortedBy { it.name }.orEmpty()

    fun activeName(c: Context) = c.getSharedPreferences("local_model", 0).getString("active", "").orEmpty()
    fun setActive(c: Context, name: String) = c.getSharedPreferences("local_model", 0).edit().putString("active", name).apply()

    /** The chosen model, or the first one if none was chosen. */
    fun file(c: Context): File? = models(c).let { list -> list.firstOrNull { it.name == activeName(c) } ?: list.firstOrNull() }
    fun available(c: Context) = file(c) != null
    fun describe(c: Context) = file(c)?.let { "${it.name} · ${it.length() / 1_048_576} MB" }

    @Synchronized
    fun delete(c: Context, name: String) {
        if (enginePath.endsWith("/$name")) { runCatching { engine?.close() }; engine = null; enginePath = "" }
        File(dir(c), name).delete()
    }

    /** Copies the chosen model into the app's own storage (large files: runs on IO, can take a minute). */
    suspend fun import(c: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val name = com.nizam.app.core.displayNameFor(c, uri).replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "model.task" }
        val out = File(dir(c), name)
        c.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it, 1 shl 20) } }
            ?: throw IllegalStateException("Couldn't open that file")
        if (out.length() < 10_000_000) { out.delete(); throw IllegalStateException("That doesn't look like a model file") }
        setActive(c, name)
        name
    }

    @Synchronized
    fun remove(c: Context) {
        runCatching { engine?.close() }; engine = null; enginePath = ""
        dir(c).listFiles()?.forEach { it.delete() }
    }

    /** Each model family has its own chat format; using the wrong one makes answers ramble. */
    private fun format(name: String, system: String?, prompt: String, budgetChars: Int): String {
        val n = name.lowercase()
        val sys = system?.trim().orEmpty().take(budgetChars / 3)
        val user = prompt.takeLast((budgetChars - sys.length).coerceAtLeast(500))
        return when {
            "qwen" in n || "deepseek" in n || "smol" in n -> buildString {
                if (sys.isNotBlank()) append("<|im_start|>system\n").append(sys).append("<|im_end|>\n")
                append("<|im_start|>user\n").append(user).append("<|im_end|>\n<|im_start|>assistant\n")
            }
            "phi" in n -> buildString {
                if (sys.isNotBlank()) append("<|system|>").append(sys).append("<|end|>")
                append("<|user|>").append(user).append("<|end|><|assistant|>")
            }
            "llama" in n -> buildString {
                append("<|begin_of_text|>")
                if (sys.isNotBlank()) append("<|start_header_id|>system<|end_header_id|>\n\n").append(sys).append("<|eot_id|>")
                append("<|start_header_id|>user<|end_header_id|>\n\n").append(user).append("<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n")
            }
            else -> buildString {                                                  // Gemma
                append("<start_of_turn>user\n")
                if (sys.isNotBlank()) append(sys).append("\n\n")
                append(user).append("<end_of_turn>\n<start_of_turn>model\n")
            }
        }
    }

    /** The context the file was built for (litert files say it: "ekv1280", "ekv4096"); prompt + answer must fit in it. */
    private fun contextOf(f: File) = Regex("ekv(\\d+)").find(f.name)?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(512, 4096) ?: 2048

    /** How the last answer ran — shown in Settings › Offline models so you can see the speed. */
    @Volatile var backend = ""
    @Volatile var lastSpeed = 0.0            // words per second

    private fun open(c: Context, f: File) {
        runCatching { engine?.close() }
        engine = null
        // a phone chat needs ~1k tokens: a smaller window means much faster reading of the prompt and less memory
        val ctx = contextOf(f).coerceAtMost(2048)
        fun build(gpu: Boolean) = LlmInference.createFromOptions(c, LlmInference.LlmInferenceOptions.builder()
            .setModelPath(f.path).setMaxTokens(ctx).setMaxTopK(40)
            .setPreferredBackend(if (gpu) LlmInference.Backend.GPU else LlmInference.Backend.CPU).build())
        // the phone's GPU is several times faster; if this model or phone can't use it, the CPU always works.
        // A GPU failure is remembered per app version, so an update (or a better model file) gets another try.
        val prefs = c.getSharedPreferences("local_model", 0)
        val flag = "gpu_ok_${f.name}_${com.nizam.app.BuildConfig.VERSION_CODE}"
        val gpuOk = prefs.getBoolean(flag, true)
        engine = if (gpuOk) runCatching { build(true).also { backend = "GPU" } }.getOrElse {
            prefs.edit().putBoolean(flag, false).apply(); build(false).also { backend = "CPU" } } else build(false).also { backend = "CPU" }
        enginePath = f.path
    }

    /** Loads the model ahead of time (a few seconds), so the first offline answer isn't slow. */
    @Synchronized
    fun warm(c: Context) {
        val f = file(c) ?: return
        if (engine == null || enginePath != f.path) open(c, f)
    }

    /**
     * Answers on the phone, streaming words as they're made (onDelta). Small models are fast only with short
     * prompts: Atlas's long context (memory, app state) is cut to what matters — the question and a little
     * instruction — and answers are kept to a few paragraphs.
     */
    @Synchronized
    fun generate(c: Context, prompt: String, system: String?, maxTokens: Int, onDelta: ((String) -> Unit)? = null): String {
        val f = file(c) ?: throw IllegalStateException("No offline model yet — Settings › Offline models sets one up in one tap")
        if (engine == null || enginePath != f.path) open(c, f)
        val shortSystem = system?.lineSequence()?.filter { it.isNotBlank() }?.take(4)?.joinToString("\n")?.take(400)
        val text = format(f.name, shortSystem, prompt.takeLast(1600), 2400)
        val t0 = System.currentTimeMillis()
        val out = StringBuilder()
        val raw = try {
            if (onDelta == null) engine!!.generateResponse(text)
            else engine!!.generateResponseAsync(text) { partial, _ ->
                if (!partial.isNullOrEmpty()) { out.append(partial); onDelta(clean(partial)) }
            }.get()
        } catch (e: Exception) {
            if (e.message?.contains("too long", true) == true || e.message?.contains("exceed", true) == true)
                engine!!.generateResponse(format(f.name, null, prompt.takeLast(700), 1000))
            else throw e
        }
        val answer = clean(raw.ifBlank { out.toString() })
        val secs = (System.currentTimeMillis() - t0) / 1000.0
        if (secs > 0.2) lastSpeed = answer.split(Regex("\\s+")).size / secs
        c.getSharedPreferences("local_model", 0).edit().putString("last_run", "$backend · ${"%.1f".format(lastSpeed)} words/s").apply()
        return answer
    }

    fun lastRun(c: Context) = c.getSharedPreferences("local_model", 0).getString("last_run", "").orEmpty()

    private fun clean(s: String) = s.replace(Regex("<\\|im_end\\|>|<end_of_turn>|<\\|end\\|>|<\\|eot_id\\|>"), "")
}
