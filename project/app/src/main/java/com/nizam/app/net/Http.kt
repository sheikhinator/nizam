package com.nizam.app.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** Minimal HTTP helper — no third-party networking library needed. */
object Http {
    // Every call is forced onto IO here as well. A blocking request off this dispatcher throws
    // NetworkOnMainThreadException, whose message is null — which is how this hid for so long.
    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): String =
        request(url, "GET", null, headers, "application/json")

    suspend fun getText(url: String, headers: Map<String, String> = emptyMap()): String =
        request(url, "GET", null, headers, "*/*")

    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String =
        request(url, "POST", body, headers, "application/json")

    /** POST that also hands back response headers — MCP carries its session id in one. */
    suspend fun postJsonWithHeaders(
        url: String, body: String, headers: Map<String, String>, onHeader: (String, String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val connection = (java.net.URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 8000          // a PC server that's off should fail fast
            readTimeout = 300000           // but a real scrape or agent task may take minutes
            setRequestProperty("User-Agent", "Atlas/1.0")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            doOutput = true
        }
        connection.outputStream.use { it.write(body.toByteArray()) }
        val code = connection.responseCode
        connection.headerFields.forEach { (k, v) -> if (k != null && v.isNotEmpty()) onHeader(k, v.first()) }
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(300)}")
        text
    }

    private suspend fun request(
        url: String,
        method: String,
        body: String?,
        headers: Map<String, String>,
        accept: String
    ): String = withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10000
            readTimeout = 120000
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", "Atlas/1.0")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code !in 200..299) throw IllegalStateException("HTTP $code: ${text.take(300)}")
            // no disconnect(): reading the body to the end returns the socket to the keep-alive pool, so the next
            // call to the same service skips DNS, TCP and TLS setup (often half a second on mobile)
            text
        } catch (t: Throwable) {
            connection.disconnect(); throw t
        }
    }
}
