package com.nizam.app.feature.wild

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import com.nizam.app.NizamApplication
import com.nizam.app.feature.future.Imagine
import com.nizam.app.feature.nx.Multimodal
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Media-compatible routes backed by Atlas's existing image and on-device speech features. */
internal object LaptopMediaApi {
    private const val MAX_BODY_BYTES = 2 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    private const val MAX_VIDEO_BYTES = 80 * 1024 * 1024
    private const val VIDEO_TIMEOUT_MS = 5 * 60_000L
    private const val VIDEO_HTTP_TIMEOUT_MS = 20_000
    private val openAiVoices = setOf("alloy", "ash", "ballad", "coral", "echo", "fable", "onyx", "nova", "sage", "shimmer", "verse", "marin", "cedar")
    private data class VideoModel(val id: String, val name: String, val providerModelId: String, val quota: String)
    private val hfVideoModels = listOf(
        VideoModel("Lightricks/LTX-Video-0.9.5", "LTX-Video 0.9.5", "fal-ai/ltx-video-v095",
            "\$0.10/month shared Hugging Face Inference Providers credits"),
        VideoModel("Lightricks/LTX-Video-0.9.7-distilled", "LTX-Video 0.9.7 Distilled", "fal-ai/ltx-video-13b-distilled",
            "\$0.10/month shared Hugging Face Inference Providers credits")
    )
    private val supportedVideoFields = setOf("prompt", "model", "n", "seed")
    private val videoGenerationLock = Semaphore(1)
    private val defaultHfVideoModel = hfVideoModels.last()

    fun images(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val request = readJson(session)
        val prompt = request.optString("prompt").trim()
        if (prompt.isBlank() || prompt.length > 5000) throw IllegalArgumentException("prompt must be 1–5,000 characters.")
        val size = when (request.optString("size", "1024x1024")) {
            "1024x1024" -> "Square"
            "1024x1792", "1024x1536" -> "Portrait"
            "1792x1024", "1536x1024" -> "Landscape"
            "1080x2340" -> "Wallpaper"
            else -> throw IllegalArgumentException("Supported image sizes are 1024x1024, 1024x1792, 1792x1024, and 1080x2340.")
        }
        val n = request.optInt("n", 1)
        if (n !in 1..4) throw IllegalArgumentException("n must be between 1 and 4.")
        val format = request.optString("response_format", "url")
        if (format != "url" && format != "b64_json") throw IllegalArgumentException("response_format must be url or b64_json.")
        val seed = request.optLong("seed", System.currentTimeMillis())
        val data = JSONArray()
        repeat(n) { index ->
            val imageUrl = Imagine.url(prompt, size, (seed + index).toInt())
            data.put(if (format == "url") JSONObject().put("url", imageUrl)
                else JSONObject().put("b64_json", Base64.encodeToString(downloadImage(imageUrl), Base64.NO_WRAP)))
        }
        return json(JSONObject().put("created", System.currentTimeMillis() / 1000).put("data", data))
    }

    fun videoModels(context: Context): NanoHTTPD.Response {
        val hfPreset = com.nizam.app.net.Providers.PRESETS.first { it.id == "hf" }
        val configured = com.nizam.app.net.Providers.on(context, hfPreset) &&
            com.nizam.app.net.Providers.key(context, hfPreset).isNotBlank()
        val data = JSONArray()
        hfVideoModels.forEach { model ->
            data.put(JSONObject()
                .put("id", model.id)
                .put("object", "model")
                .put("owned_by", "huggingface")
                .put("display_name", model.name)
                .put("modality", "video")
                .put("available", configured)
                .put("quota_label", model.quota))
        }
        return json(JSONObject().put("object", "list").put("data", data))
    }

    fun videos(context: Context, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        var temporaryVideo: File? = null
        return try {
            val request = readJson(session)
            val prompt = request.optString("prompt").trim()
            if (prompt.isBlank() || prompt.length > 5000) {
                throw IllegalArgumentException("prompt must be 1–5,000 characters.")
            }

            val requestedModel = request.optString("model", "auto").trim()
            val model = if (requestedModel.isBlank() || requestedModel.equals("auto", true)) {
                defaultHfVideoModel
            } else {
                hfVideoModels.firstOrNull {
                    it.id.equals(requestedModel, true) || it.id.substringAfter('/').equals(requestedModel, true)
                } ?: throw IllegalArgumentException(
                    "Unsupported video model. Use /v1/videos/models to list the available Hugging Face models."
                )
            }

            val n = request.optInt("n", 1)
            if (n != 1) throw IllegalArgumentException("Hugging Face video generation supports n=1.")
            val unsupported = request.keys().asSequence()
                .filterNot { it in supportedVideoFields }
                .sorted()
                .toList()
            if (unsupported.isNotEmpty()) {
                throw IllegalArgumentException(
                    "This Hugging Face video model supports prompt, model, and optional seed only; unsupported field(s): ${unsupported.joinToString(", ")}."
                )
            }
            val seed = if (request.has("seed") && !request.isNull("seed")) {
                val value = request.opt("seed")
                if (value !is Number || value.toDouble() % 1.0 != 0.0 || value.toLong() !in 0L..4_294_967_295L) {
                    throw IllegalArgumentException("seed must be an integer between 0 and 4294967295.")
                }
                value.toLong()
            } else null

            val hfPreset = com.nizam.app.net.Providers.PRESETS.first { it.id == "hf" }
            if (!com.nizam.app.net.Providers.on(context, hfPreset)) {
                throw VideoServiceException("Turn on Hugging Face in Settings → API keys to generate video.")
            }
            val key = com.nizam.app.net.Providers.key(context, hfPreset)
            if (key.isBlank()) {
                throw VideoServiceException("Add a Hugging Face token in Settings → API keys to generate video.")
            }

            val payload = JSONObject().put("prompt", prompt).apply { seed?.let { put("seed", it) } }
            if (!videoGenerationLock.tryAcquire()) {
                throw VideoServiceException("Another video generation is already running. Try again when it finishes.")
            }
            val generated = try {
                generateHfVideo(context.cacheDir, model, key, payload)
            } finally {
                videoGenerationLock.release()
            }
            temporaryVideo = generated.file
            val responseStream = object : FilterInputStream(FileInputStream(generated.file)) {
                override fun close() {
                    try {
                        super.close()
                    } finally {
                        generated.file.delete()
                    }
                }
            }
            val response = try {
                NanoHTTPD.newFixedLengthResponse(
                    NanoHTTPD.Response.Status.OK,
                    generated.contentType,
                    responseStream,
                    generated.file.length()
                ).apply {
                    addHeader("Cache-Control", "no-store")
                    addHeader("X-Provider", "huggingface")
                    addHeader("X-Model", model.id)
                }
            } catch (e: Exception) {
                runCatching { responseStream.close() }
                throw e
            }
            temporaryVideo = null
            response
        } catch (e: IllegalArgumentException) {
            mediaError(NanoHTTPD.Response.Status.BAD_REQUEST, e.message ?: "Invalid video request.")
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            mediaError(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, "Hugging Face video generation was interrupted.")
        } catch (e: VideoServiceException) {
            mediaError(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, e.message ?: "Hugging Face video generation failed.")
        } catch (e: Exception) {
            mediaError(NanoHTTPD.Response.Status.SERVICE_UNAVAILABLE, e.message?.take(300) ?: "Hugging Face video generation failed.")
        } finally {
            temporaryVideo?.delete()
        }
    }

    fun speech(context: Context, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val request = readJson(session)
        val text = request.optString("input")
        if (text.isBlank() || text.length > 4096) throw IllegalArgumentException("input must be 1–4,096 characters.")
        val format = request.optString("response_format", "wav")
        if (!format.equals("wav", true)) {
            throw IllegalArgumentException("Atlas local speech currently supports response_format=wav.")
        }
        val voice = request.optString("voice", "alloy").lowercase()
        if (voice !in openAiVoices) throw IllegalArgumentException("Unsupported voice. Use an OpenAI-compatible voice name such as alloy, nova, or shimmer.")
        val speed = request.optDouble("speed", 1.0)
        if (speed !in 0.25..4.0) throw IllegalArgumentException("speed must be between 0.25 and 4.0.")
        val (bytes, deviceVoice) = synthesize(context, text, speed.toFloat())
        return NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "audio/wav",
            ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Atlas-Voice", deviceVoice)
        }
    }

    fun transcriptions(context: Context, session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val declaredLength = session.headers.entries.firstOrNull { it.key.equals("content-length", true) }
            ?.value?.toLongOrNull() ?: 0L
        if (declaredLength > 20L * 1024 * 1024) throw IllegalArgumentException("Audio upload exceeds the 18 MB file limit.")
        val fields = HashMap<String, String>()
        try {
            session.parseBody(fields)
        } catch (_: Exception) {
            fields.values.mapNotNull { it.takeIf { path -> File(path).isFile }?.let(::File) }.forEach { it.delete() }
            throw IllegalArgumentException("Multipart audio upload could not be read.")
        }
        val uploadPath = fields.entries.firstOrNull { it.key.equals("file", true) }?.value
            ?: session.parameters.entries.firstOrNull { it.key.equals("file", true) }?.value?.firstOrNull()
            ?: throw IllegalArgumentException("Multipart field 'file' is required.")
        val upload = File(uploadPath)
        try {
            if (!upload.isFile) throw IllegalArgumentException("Multipart field 'file' was not received as a file.")
            if (upload.length() !in 1..(18L * 1024 * 1024)) throw IllegalArgumentException("Audio file must be between 1 byte and 18 MB.")
            val bytes = upload.readBytes()
            val params = session.parameters
            fun field(name: String) = params.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull().orEmpty()
            val mime = field("mime_type").ifBlank { sniffAudioMime(bytes) }
            if (!mime.startsWith("audio/")) throw IllegalArgumentException("The uploaded file must contain a supported audio format.")
            val responseFormat = field("response_format").ifBlank { "json" }.lowercase()
            if (responseFormat !in setOf("json", "text")) {
                throw IllegalArgumentException("response_format must be json or text.")
            }
            val language = field("language").take(40)
            val hint = field("prompt").take(4000)
            val instruction = buildString {
                append("Transcribe the speech in this audio accurately in its original language. Return only the transcript, without a summary or commentary.")
                if (language.isNotBlank()) append(" The spoken language is ").append(language).append(".")
                if (hint.isNotBlank()) append(" Context: ").append(hint)
            }
            val app = context.applicationContext as? NizamApplication
                ?: throw IllegalStateException("Atlas is not ready.")
            val text = runBlocking {
                Multimodal.ask(app.container.settingsRepository, instruction, mime, bytes, 8192)
            }.trim()
            if (text.isBlank()) throw IllegalStateException("The audio provider returned an empty transcript.")
            return if (responseFormat == "text") {
                NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "text/plain; charset=utf-8", text)
            } else json(JSONObject().put("text", text))
        } finally {
            upload.delete()
        }
    }

    private class VideoServiceException(message: String, cause: Throwable? = null) : Exception(message, cause)
    private data class DownloadedVideo(val file: File, val contentType: String)

    private fun generateHfVideo(
        cacheDir: File,
        model: VideoModel,
        key: String,
        payload: JSONObject
    ): DownloadedVideo {
        val deadline = System.currentTimeMillis() + VIDEO_TIMEOUT_MS
        fun remainingMs() = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)

        val queueUrl = "https://router.huggingface.co/fal-ai/${model.providerModelId}?_subdomain=queue"
        val submitted = huggingFaceJson(queueUrl, key, "POST", payload)
        val responseUrl = submitted.optString("response_url").trim()
        if (submitted.optString("request_id").isBlank() || responseUrl.isBlank()) {
            throw VideoServiceException("Hugging Face returned no video job URL.")
        }
        val responsePath = try {
            val resolved = URL(URL("https://queue.fal.run"), responseUrl).toExternalForm()
            URI(resolved).rawPath.orEmpty()
        } catch (e: Exception) {
            throw VideoServiceException("Hugging Face returned an invalid video job URL.", e)
        }
        if (!responsePath.matches(Regex("^/fal-ai/[A-Za-z0-9._/-]{1,900}$")) || responsePath.contains("..")) {
            throw VideoServiceException("Hugging Face returned an invalid video job path.")
        }

        val jobUrl = "https://router.huggingface.co/fal-ai$responsePath"
        var status = submitted.optString("status", "IN_QUEUE").uppercase()
        while (status != "COMPLETED") {
            if (status == "FAILED" || status == "CANCELLED") {
                throw VideoServiceException("Hugging Face video job ${status.lowercase()}.")
            }
            val remaining = remainingMs()
            if (remaining <= 1L) throw VideoServiceException("Hugging Face video generation timed out after five minutes.")
            Thread.sleep(minOf(500L, remaining))
            val state = huggingFaceJson(
                "$jobUrl/status?_subdomain=queue",
                key,
                "GET",
                timeoutMs = minOf(VIDEO_HTTP_TIMEOUT_MS.toLong(), remainingMs()).toInt().coerceAtLeast(1)
            )
            status = state.optString("status").uppercase()
            if (status.isBlank()) throw VideoServiceException("Hugging Face returned an invalid video job status.")
        }

        val completed = huggingFaceJson(
            "$jobUrl?_subdomain=queue",
            key,
            "GET",
            timeoutMs = minOf(VIDEO_HTTP_TIMEOUT_MS.toLong(), remainingMs()).toInt().coerceAtLeast(1)
        )
        val video = completed.optJSONObject("video")
            ?: throw VideoServiceException("Hugging Face returned no generated video.")
        val videoUrl = video.optString("url").trim()
        if (videoUrl.isBlank()) throw VideoServiceException("Hugging Face returned no video URL.")
        val declaredType = video.optString("content_type").takeIf { it.startsWith("video/") }
        return downloadVideo(cacheDir, videoUrl, declaredType, deadline)
    }

    private fun huggingFaceJson(
        endpoint: String,
        key: String,
        method: String,
        payload: JSONObject? = null,
        timeoutMs: Int = VIDEO_HTTP_TIMEOUT_MS
    ): JSONObject {
        val connection = try {
            URL(endpoint).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            throw VideoServiceException("Could not connect to Hugging Face video service.", e)
        }
        try {
            connection.requestMethod = method
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.setRequestProperty("Authorization", "Bearer $key")
            connection.setRequestProperty("Accept", "application/json")
            if (payload != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val responseBytes = stream?.use { readLimited(it, 1024 * 1024) } ?: ByteArray(0)
            val responseText = responseBytes.toString(Charsets.UTF_8)
            if (status !in 200..299) {
                val detail = responseText.take(240).trim().takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty()
                throw VideoServiceException("Hugging Face video service returned HTTP $status$detail")
            }
            return try {
                JSONObject(responseText)
            } catch (e: Exception) {
                throw VideoServiceException("Hugging Face returned an invalid JSON response.", e)
            }
        } catch (e: VideoServiceException) {
            throw e
        } catch (e: Exception) {
            throw VideoServiceException("Hugging Face video request failed: ${e.message ?: e.javaClass.simpleName}.", e)
        } finally {
            connection.disconnect()
        }
    }

    private fun downloadVideo(cacheDir: File, rawUrl: String, declaredType: String?, deadline: Long): DownloadedVideo {
        val file = try {
            File.createTempFile("atlas_video_", ".mp4", cacheDir)
        } catch (e: Exception) {
            throw VideoServiceException("Could not reserve temporary storage for the generated video.", e)
        }
        try {
            return downloadVideoToFile(file, rawUrl, declaredType, deadline)
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    private fun downloadVideoToFile(
        file: File,
        rawUrl: String,
        declaredType: String?,
        deadline: Long
    ): DownloadedVideo {
        var target = validatePublicHttps(rawUrl)
        var redirects = 0
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 1L) throw VideoServiceException("Hugging Face video generation timed out after five minutes.")
            val connection = try {
                target.openConnection() as HttpURLConnection
            } catch (e: Exception) {
                throw VideoServiceException("Could not connect to the Hugging Face video file.", e)
            }
            try {
                connection.instanceFollowRedirects = false
                connection.connectTimeout = minOf(15_000L, remaining).toInt().coerceAtLeast(1)
                connection.readTimeout = minOf(30_000L, remaining).toInt().coerceAtLeast(1)
                connection.setRequestProperty("User-Agent", "Atlas/1.0")
                val status = connection.responseCode
                if (status in 300..399) {
                    if (++redirects > 4) throw VideoServiceException("Video download exceeded the redirect limit.")
                    val location = connection.getHeaderField("Location")
                        ?: throw VideoServiceException("Video download returned an invalid redirect.")
                    target = validatePublicHttps(URL(target, location).toExternalForm())
                    continue
                }
                if (status !in 200..299) throw VideoServiceException("Video download returned HTTP $status.")
                if (connection.contentLengthLong > MAX_VIDEO_BYTES.toLong()) {
                    throw VideoServiceException("Generated video exceeds the 80 MB limit.")
                }
                connection.inputStream.use { input ->
                    FileOutputStream(file).use { output -> copyLimited(input, output, MAX_VIDEO_BYTES) }
                }
                if (file.length() == 0L) throw VideoServiceException("Hugging Face returned an empty video.")
                val headerType = connection.contentType?.substringBefore(';')?.trim()
                val contentType = when {
                    headerType?.startsWith("video/") == true -> headerType
                    headerType.isNullOrBlank() || headerType.equals("application/octet-stream", true) ->
                        declaredType ?: "video/mp4"
                    else -> throw VideoServiceException("Hugging Face returned a non-video file.")
                }
                return DownloadedVideo(file, contentType)
            } catch (e: VideoServiceException) {
                throw e
            } catch (e: Exception) {
                throw VideoServiceException("Video download failed: ${e.message ?: e.javaClass.simpleName}.", e)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun validatePublicHttps(rawUrl: String): URL {
        val uri = try {
            URI(rawUrl)
        } catch (e: Exception) {
            throw VideoServiceException("Hugging Face returned an invalid video URL.", e)
        }
        val host = uri.host
        if (!uri.scheme.equals("https", true) || host.isNullOrBlank() || uri.userInfo != null ||
            (uri.port != -1 && uri.port != 443)) {
            throw VideoServiceException("Video download URL must use public HTTPS.")
        }
        val addresses = try {
            InetAddress.getAllByName(host)
        } catch (e: Exception) {
            throw VideoServiceException("Could not resolve the video download host.", e)
        }
        if (addresses.isEmpty() || addresses.any(::isNonPublicAddress)) {
            throw VideoServiceException("Video download URL resolved to a private or reserved address.")
        }
        return try {
            uri.toURL()
        } catch (e: Exception) {
            throw VideoServiceException("Hugging Face returned an invalid video URL.", e)
        }
    }

    private fun isNonPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return true
        val bytes = address.address.map { it.toInt() and 0xff }
        return when (address) {
            is Inet4Address -> {
                val first = bytes[0]
                val second = bytes[1]
                first == 0 || first == 100 && second in 64..127 || first >= 224 ||
                    first == 198 && second in 18..19
            }
            is Inet6Address -> {
                val first = bytes[0]
                val second = bytes[1]
                (first and 0xfe) == 0xfc || first == 0xfe && (second and 0xc0) == 0x80
            }
            else -> false
        }
    }

    private fun readLimited(input: java.io.InputStream, maximum: Int): ByteArray {
        val output = ByteArrayOutputStream()
        copyLimited(input, output, maximum)
        return output.toByteArray()
    }

    private fun copyLimited(input: java.io.InputStream, output: java.io.OutputStream, maximum: Int) {
        val buffer = ByteArray(8192)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > maximum) throw VideoServiceException("Response exceeds the allowed size limit.")
            output.write(buffer, 0, count)
        }
    }

    private fun mediaError(status: NanoHTTPD.Response.Status, message: String): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json; charset=utf-8",
            JSONObject().put("error", JSONObject()
                .put("message", message)
                .put("type", if (status == NanoHTTPD.Response.Status.BAD_REQUEST) "invalid_request_error" else "server_error"))
                .toString())

    private fun synthesize(context: Context, text: String, speed: Float): Pair<ByteArray, String> {
        val initialized = CountDownLatch(1)
        val initStatus = AtomicReference(TextToSpeech.ERROR)
        val engine = TextToSpeech(context.applicationContext) { status ->
            initStatus.set(status)
            initialized.countDown()
        }
        val file = File(context.cacheDir, "api_tts_${UUID.randomUUID()}.wav")
        val utteranceId = "atlas_${UUID.randomUUID()}"
        try {
            if (!initialized.await(15, TimeUnit.SECONDS)) throw IllegalStateException("Android speech engine did not initialize in time.")
            if (initStatus.get() != TextToSpeech.SUCCESS) throw IllegalStateException("No Android text-to-speech engine is available.")
            engine.setSpeechRate(speed)
            val deviceVoice = engine.voice?.name.orEmpty().ifBlank { "system-default" }
            val completed = CountDownLatch(1)
            val failure = AtomicReference<String?>(null)
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) = Unit
                override fun onDone(id: String?) { if (id == utteranceId) completed.countDown() }
                override fun onError(id: String?) {
                    if (id == utteranceId) { failure.set("Android speech synthesis failed."); completed.countDown() }
                }
                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId) { failure.set("Android speech synthesis failed ($errorCode)."); completed.countDown() }
                }
            })
            val queued = engine.synthesizeToFile(text, Bundle(), file, utteranceId)
            if (queued != TextToSpeech.SUCCESS) throw IllegalStateException("Android speech engine could not start synthesis.")
            if (!completed.await(60, TimeUnit.SECONDS)) throw IllegalStateException("Android speech synthesis timed out.")
            failure.get()?.let { throw IllegalStateException(it) }
            if (!file.isFile || file.length() == 0L) throw IllegalStateException("Android speech engine produced no audio.")
            return file.readBytes() to deviceVoice
        } finally {
            runCatching { engine.stop() }
            engine.shutdown()
            file.delete()
        }
    }

    private fun downloadImage(imageUrl: String): ByteArray {
        val connection = URL(imageUrl).openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 60000
        connection.setRequestProperty("User-Agent", "Atlas/1.0")
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("Image service returned HTTP ${connection.responseCode}.")
            val output = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_IMAGE_BYTES) throw IllegalStateException("Generated image exceeds the 8 MB limit.")
                    output.write(buffer, 0, count)
                }
            }
            return output.toByteArray()
        } finally {
            connection.disconnect()
        }
    }

    private fun sniffAudioMime(bytes: ByteArray): String {
        if (bytes.size >= 12 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
            bytes.copyOfRange(8, 12).contentEquals("WAVE".toByteArray())) return "audio/wav"
        if (bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals("fLaC".toByteArray())) return "audio/flac"
        if (bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals("OggS".toByteArray())) return "audio/ogg"
        if (bytes.size >= 4 && bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))) return "audio/webm"
        if (bytes.size >= 8 && bytes.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())) return "audio/mp4"
        if (bytes.size >= 3 && bytes.copyOfRange(0, 3).contentEquals("ID3".toByteArray())) return "audio/mpeg"
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0) return "audio/mpeg"
        throw IllegalArgumentException("Audio type could not be identified. Use WAV, MP3, AAC, FLAC, OGG, WebM, or M4A.")
    }

    private fun readJson(session: NanoHTTPD.IHTTPSession): JSONObject {
        val length = session.headers.entries.firstOrNull { it.key.equals("content-length", true) }?.value?.toLongOrNull() ?: 0L
        if (length > MAX_BODY_BYTES) throw IllegalArgumentException("Request exceeds the 2 MB limit.")
        val fields = HashMap<String, String>()
        session.parseBody(fields)
        val raw = fields["postData"].orEmpty()
        if (raw.isBlank() || raw.toByteArray(Charsets.UTF_8).size > MAX_BODY_BYTES) {
            throw IllegalArgumentException("A JSON request body of at most 2 MB is required.")
        }
        return try { JSONObject(raw) } catch (_: Exception) { throw IllegalArgumentException("Request body must be valid JSON.") }
    }

    private fun json(payload: JSONObject): NanoHTTPD.Response =
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.OK, "application/json; charset=utf-8", payload.toString())
}