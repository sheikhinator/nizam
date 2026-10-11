package com.nizam.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gemini client. The key is yours, entered in Settings and stored on the device.
 * Note it ships inside the APK's storage — fine for a personal build, not for a shared one.
 */
object Gemini {

    class MissingKeyException : Exception("No Gemini API key. Add one in Settings.")

    suspend fun raw(
        apiKey: String,
        model: String,
        prompt: String,
        system: String? = null,
        maxOutputTokens: Int = 8192,
        useWeb: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw MissingKeyException()

        val parts = JSONArray().put(JSONObject().put("text", prompt))
        val contents = JSONArray().put(
            JSONObject().put("role", "user").put("parts", parts)
        )
        val body = JSONObject().apply {
            put("contents", contents)
            if (!system.isNullOrBlank()) {
                put(
                    "systemInstruction",
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system)))
                )
            }
            if (useWeb) {
                // Native search grounding — real web results, no scraping, no server.
                put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
            }
            put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.7)
                    .put("maxOutputTokens", maxOutputTokens)
            )
        }.toString()

        val url = "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey"
        val response = Http.postJson(url, body)
        val root = JSONObject(response)

        val candidates = root.optJSONArray("candidates")
            ?: throw IllegalStateException(
                root.optJSONObject("error")?.optString("message") ?: "Empty response from Gemini."
            )
        if (candidates.length() == 0) throw IllegalStateException("Gemini returned nothing.")

        val partsOut = candidates.getJSONObject(0)
            .optJSONObject("content")?.optJSONArray("parts")
            ?: throw IllegalStateException("Gemini returned no text.")

        val builder = StringBuilder()
        for (i in 0 until partsOut.length()) {
            builder.append(partsOut.getJSONObject(i).optString("text", ""))
        }
        builder.toString().trim()
    }

    /** Asks the API which models this key can actually use, newest first. */
    suspend fun listModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) throw MissingKeyException()
        val response = Http.getJson(
            "https://generativelanguage.googleapis.com/v1beta/models?key=$apiKey&pageSize=200"
        )
        val array = JSONObject(response).optJSONArray("models") ?: return@withContext emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val m = array.getJSONObject(i)
            val methods = m.optJSONArray("supportedGenerationMethods")
            val supportsGenerate = methods != null &&
                (0 until methods.length()).any { methods.optString(it) == "generateContent" }
            val name = m.optString("name").removePrefix("models/")
            if (supportsGenerate && name.startsWith("gemini") &&
                !name.contains("embedding") && !name.contains("audio") && !name.contains("image")
            ) {
                out.add(name)
            }
        }
        out.sortedDescending()
    }

    /** Strips markdown fences so model output can be parsed as JSON. */
    fun cleanJson(raw: String): String {
        var s = raw.trim()
        if (s.startsWith("```")) {
            s = s.substringAfter('\n', "")
            s = s.substringBeforeLast("```")
        }
        val start = s.indexOfFirst { it == '{' || it == '[' }
        val end = s.indexOfLast { it == '}' || it == ']' }
        return if (start >= 0 && end > start) s.substring(start, end + 1) else s
    }
}
