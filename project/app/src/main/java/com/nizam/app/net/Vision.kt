package com.nizam.app.net

import android.graphics.Bitmap
import android.util.Base64
import com.nizam.app.data.prefs.SettingsRepository
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Ask the AI about a photo. Downscaled to 1024px JPEG first — plenty for a plate of food, and it keeps
 * the request small. Works with Gemini natively, and with any OpenAI-compatible provider whose chosen
 * model accepts images (Groq's Llama 4 models, many on OpenRouter and Hugging Face).
 */
object Vision {
    fun encode(bmp: Bitmap): String {
        val scale = 1024f / maxOf(bmp.width, bmp.height)
        val small = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        val out = ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG, 82, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    suspend fun ask(settings: SettingsRepository, prompt: String, bmp: Bitmap): String {
        val b64 = encode(bmp)
        // every Gemini key and model first (all see images), then the OpenAI-style sources; a text-only model fails over
        val list = Router.endpoints(settings, Router.appContext).filter { it.kind == "GEMINI" || it.kind == "OPENAI" }
            .sortedBy { if (it.kind == "GEMINI") 0 else 1 }
        return Engine.run(list, hedgeAfterMs = null) { e ->
            if (e.kind == "GEMINI") {
                val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                    .put(JSONObject().put("text", prompt))
                    .put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", b64))))))
                val r = JSONObject(Http.postJson("${e.url}/${e.model}:generateContent?key=${e.key}", body.toString()))
                r.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
                    ?.let { p -> (0 until p.length()).joinToString("") { p.getJSONObject(it).optString("text") } }
                    ?.takeIf { it.isNotBlank() } ?: throw IllegalStateException("No answer about the photo.")
            } else {
                val body = JSONObject().put("model", e.model).put("max_tokens", 800).put("messages", JSONArray().put(
                    JSONObject().put("role", "user").put("content", JSONArray()
                        .put(JSONObject().put("type", "text").put("text", prompt))
                        .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))))))
                val r = JSONObject(Http.postJson(e.url, body.toString(), if (e.key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer ${e.key}")))
                r.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content")?.takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("This model didn't answer about the photo — pick one that accepts images.")
            }
        }
    }
}
