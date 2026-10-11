package com.nizam.app.feature.nx

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.util.Base64
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.TranslateRemoteModel
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.nizam.app.data.prefs.SettingsRepository
import com.nizam.app.net.Engine
import com.nizam.app.net.Http
import com.nizam.app.net.Router
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/**
 * Gemini as eyes and ears: audio, video and images go in as inline data. Needs a Gemini key
 * (free from Google AI Studio). Inline requests are capped around 20 MB, so long videos are
 * reduced to their audio track first.
 */
object Multimodal {
    const val LIMIT = 18 * 1024 * 1024

    private suspend fun gemini(settings: SettingsRepository) =
        Router.endpoints(settings, Router.appContext).filter { it.kind == "GEMINI" }.ifEmpty {
            throw IllegalStateException("This needs a Gemini key (free at aistudio.google.com) in Settings › API keys.")
        }

    suspend fun ask(settings: SettingsRepository, prompt: String, mime: String, bytes: ByteArray, maxTokens: Int = 4000): String {
        if (bytes.size > LIMIT) throw IllegalStateException("That file is too big (${bytes.size / 1_000_000} MB). Keep it under 18 MB.")
        val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return Engine.run(gemini(settings), hedgeAfterMs = null) { e ->
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", b64)))
                .put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("maxOutputTokens", maxTokens))
            val r = JSONObject(Http.postJson("${e.url}/${e.model}:generateContent?key=${e.key}", body.toString()))
            r.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                ?.let { p -> (0 until p.length()).joinToString("") { p.getJSONObject(it).optString("text") } }
                ?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("No answer came back.")
        }
    }

    /** Image in, edited image out, through whichever Gemini image model the key can use. */
    suspend fun editImage(settings: SettingsRepository, prompt: String, bmp: Bitmap): Bitmap = withContext(Dispatchers.IO) {
        val key = gemini(settings).first().key
        val models = runCatching {
            val a = JSONObject(Http.getJson("https://generativelanguage.googleapis.com/v1beta/models?key=$key&pageSize=200")).optJSONArray("models") ?: JSONArray()
            (0 until a.length()).map { a.getJSONObject(it).optString("name").removePrefix("models/") }
                .filter { it.startsWith("gemini") && it.contains("image") }
                .sortedByDescending { (if (it.contains("flash")) 2 else 0) + (if (it.contains("preview")) 0 else 1) }
        }.getOrDefault(emptyList()).ifEmpty { listOf("gemini-2.5-flash-image", "gemini-2.0-flash-preview-image-generation") }
        val scale = 1536f / maxOf(bmp.width, bmp.height)
        val src = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream(); src.compress(Bitmap.CompressFormat.JPEG, 90, out)
        val b64 = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        var last: Exception? = null
        for (m in models.take(4)) {
            try {
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))))))
                    .put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
                val r = JSONObject(Http.postJson("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent?key=$key", body.toString()))
                val parts = r.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts") ?: JSONArray()
                for (i in 0 until parts.length()) {
                    val d = parts.getJSONObject(i).let { it.optJSONObject("inlineData") ?: it.optJSONObject("inline_data") } ?: continue
                    val bytes = Base64.decode(d.optString("data"), Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { return@withContext it }
                }
                last = IllegalStateException("The model answered without an image.")
            } catch (e: Exception) { last = e }
        }
        throw IllegalStateException("Couldn't edit the photo: ${last?.message?.take(160)}")
    }
}

/** Offline translation on the phone (Google ML Kit). Each language is a one-time ~30 MB download. */
object OfflineTranslate {
    val LANGS = linkedMapOf("English" to TranslateLanguage.ENGLISH, "Urdu" to TranslateLanguage.URDU, "Arabic" to TranslateLanguage.ARABIC,
        "Chinese" to TranslateLanguage.CHINESE, "Hindi" to TranslateLanguage.HINDI, "Japanese" to TranslateLanguage.JAPANESE, "Korean" to TranslateLanguage.KOREAN,
        "Turkish" to TranslateLanguage.TURKISH, "Persian" to TranslateLanguage.PERSIAN, "Spanish" to TranslateLanguage.SPANISH, "French" to TranslateLanguage.FRENCH,
        "German" to TranslateLanguage.GERMAN, "Russian" to TranslateLanguage.RUSSIAN, "Bengali" to TranslateLanguage.BENGALI, "Indonesian" to TranslateLanguage.INDONESIAN,
        "Malay" to TranslateLanguage.MALAY, "Italian" to TranslateLanguage.ITALIAN, "Portuguese" to TranslateLanguage.PORTUGUESE)

    fun code(tagOrName: String): String? = LANGS[tagOrName] ?: TranslateLanguage.fromLanguageTag(tagOrName.substringBefore('-').lowercase())

    suspend fun downloaded(): Set<String> = runCatching {
        RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).await().map { it.language }.toSet()
    }.getOrDefault(emptySet())

    suspend fun download(code: String, wifiOnly: Boolean = false) {
        val cond = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        RemoteModelManager.getInstance().download(TranslateRemoteModel.Builder(code).build(), cond).await()
    }
    suspend fun delete(code: String) { RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).await() }

    private val clients = HashMap<String, com.google.mlkit.nl.translate.Translator>()
    suspend fun translate(from: String, to: String, text: String, allowDownload: Boolean = true): String {
        if (from == to) return text
        val key = "$from>$to"
        val t = synchronized(clients) { clients.getOrPut(key) { Translation.getClient(TranslatorOptions.Builder().setSourceLanguage(from).setTargetLanguage(to).build()) } }
        if (allowDownload) t.downloadModelIfNeeded().await()
        return t.translate(text).await()
    }
}

/** Cuts the subject out of a photo, on the phone (ML Kit via Play services). */
object Cutout {
    suspend fun foreground(bmp: Bitmap): Bitmap {
        val seg = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundBitmap().build())
        val r = try { seg.process(InputImage.fromBitmap(bmp, 0)).await() }
        catch (e: Exception) { throw IllegalStateException(if (e.message.orEmpty().contains("download", true)) "The cut-out model is still downloading — try again in a minute." else e.message ?: "Couldn't cut out") }
        return r.foregroundBitmap ?: throw IllegalStateException("No subject found in that photo.")
    }
    fun onColour(fg: Bitmap, colour: Int): Bitmap {
        val b = Bitmap.createBitmap(fg.width, fg.height, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(b).apply { drawColor(colour); drawBitmap(fg, 0f, 0f, null) }
        return b
    }
    /** 512×512 with a transparent margin — WhatsApp's sticker size. */
    fun sticker(fg: Bitmap): Bitmap {
        val s = 512; val pad = 16
        val scale = (s - 2 * pad).toFloat() / maxOf(fg.width, fg.height)
        val w = (fg.width * scale).toInt(); val h = (fg.height * scale).toInt()
        val b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(b).drawBitmap(Bitmap.createScaledBitmap(fg, w, h, true), ((s - w) / 2).toFloat(), ((s - h) / 2).toFloat(), null)
        return b
    }
}

/** Pulls the audio track out of a video without re-encoding, so a long video fits in one AI request. */
object SoundTrack {
    fun extract(c: Context, uri: Uri): File {
        val ex = MediaExtractor(); ex.setDataSource(c, uri, null)
        val idx = (0 until ex.trackCount).firstOrNull { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/") }
            ?: throw IllegalStateException("That video has no sound track.")
        ex.selectTrack(idx)
        val fmt = ex.getTrackFormat(idx)
        val out = Share.out(c, "audio_${System.currentTimeMillis()}.m4a")
        val mux = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val t = mux.addTrack(fmt); mux.start()
        val buf = ByteBuffer.allocate(1 shl 20); val info = MediaCodec.BufferInfo()
        while (true) {
            val n = ex.readSampleData(buf, 0); if (n < 0) break
            info.set(0, n, ex.sampleTime, if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            mux.writeSampleData(t, buf, info); ex.advance()
        }
        mux.stop(); mux.release(); ex.release()
        return out
    }
}

/** Trims a video on the phone (Media3 Transformer). Must be started on the main thread. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
object Trim {
    fun start(c: Context, uri: Uri, startMs: Long, endMs: Long, done: (File?, String?) -> Unit) {
        val out = Share.out(c, "clip_${System.currentTimeMillis()}.mp4")
        val item = androidx.media3.common.MediaItem.Builder().setUri(uri).setClippingConfiguration(
            androidx.media3.common.MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build()).build()
        val t = androidx.media3.transformer.Transformer.Builder(c).addListener(object : androidx.media3.transformer.Transformer.Listener {
            override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: androidx.media3.transformer.ExportResult) { done(out, null) }
            override fun onError(composition: androidx.media3.transformer.Composition, exportResult: androidx.media3.transformer.ExportResult,
                                 exportException: androidx.media3.transformer.ExportException) { done(null, exportException.message) }
        }).build()
        t.start(item, out.absolutePath)
    }
}

/** A voice that sounds like you, through ElevenLabs with your own key (instant cloning needs their paid plan). */
object VoiceClone {
    private fun p(c: Context) = c.getSharedPreferences("voiceclone", 0)
    fun key(c: Context) = p(c).getString("key", "").orEmpty()
    fun setKey(c: Context, k: String) = p(c).edit().putString("key", k.trim()).apply()
    fun voice(c: Context) = p(c).getString("voice", "").orEmpty()

    suspend fun create(c: Context, sample: File, name: String): String = withContext(Dispatchers.IO) {
        val boundary = "----atlas${System.currentTimeMillis()}"
        val conn = (java.net.URL("https://api.elevenlabs.io/v1/voices/add").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = 120000
            setRequestProperty("xi-api-key", key(c)); setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        conn.outputStream.use { o ->
            fun w(s: String) = o.write(s.toByteArray())
            w("--$boundary\r\nContent-Disposition: form-data; name=\"name\"\r\n\r\n$name\r\n")
            w("--$boundary\r\nContent-Disposition: form-data; name=\"files\"; filename=\"sample.m4a\"\r\nContent-Type: audio/mp4\r\n\r\n")
            o.write(sample.readBytes()); w("\r\n--$boundary--\r\n")
        }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) throw IllegalStateException("ElevenLabs said: ${text.take(200)}")
        JSONObject(text).optString("voice_id").also { p(c).edit().putString("voice", it).apply() }
    }

    suspend fun speak(c: Context, text: String): File = withContext(Dispatchers.IO) {
        val conn = (java.net.URL("https://api.elevenlabs.io/v1/text-to-speech/${voice(c)}").openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = 120000
            setRequestProperty("xi-api-key", key(c)); setRequestProperty("Content-Type", "application/json"); setRequestProperty("Accept", "audio/mpeg")
        }
        conn.outputStream.use { it.write(JSONObject().put("text", text).put("model_id", "eleven_multilingual_v2").toString().toByteArray()) }
        if (conn.responseCode !in 200..299) throw IllegalStateException("ElevenLabs said: ${conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200)}")
        Share.out(c, "my_voice_${System.currentTimeMillis()}.mp3").also { f -> conn.inputStream.use { i -> f.outputStream().use { i.copyTo(it) } } }
    }
}

internal suspend fun geminiKeyPresent(s: SettingsRepository) = runCatching { s.geminiApiKey.first().isNotBlank() }.getOrDefault(false)
