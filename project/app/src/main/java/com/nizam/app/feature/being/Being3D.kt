package com.nizam.app.feature.being

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.nizam.app.AppContainer
import com.nizam.app.ui.design.rememberSafeScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime

/**
 * The Being's 3D home. The room is drawn with WebGL (three.js, bundled in the app, works offline) inside a WebView.
 * Atlas sends it how the Being feels; the room sends back what the Being did, and what you tapped or asked.
 */
private class Bridge(
    private val c: Context, private val container: AppContainer, private val scope: CoroutineScope,
    private val web: () -> WebView?, private val open: (String) -> Unit, private val back: () -> Unit
) {
    private val main = Handler(Looper.getMainLooper())

    fun js(code: String) { main.post { web()?.evaluateJavascript(code, null) } }
    fun say(text: String, emotion: String = "happy") { js("Being3D.say(${JSONObject.quote(text)}, ${JSONObject.quote(emotion)})") }
    fun log(text: String) { js("Being3D.log(${JSONObject.quote(text)})") }

    fun pushState() {
        val look = runCatching { Body.look(c) }.getOrNull()
        val now = LocalTime.now()
        val wants = JSONArray()
        if (Being.drive(c, "hunger") > 0.65f) wants.put("learn something new")
        if (Being.drive(c, "curiosity") > 0.7f) wants.put("try one of Atlas's tools")
        if (Being.drive(c, "missing") > 0.4f) wants.put("hear about your day")
        if (Being.drive(c, "mood") > 0.7f) wants.put("show you what it made")
        if (Being.drive(c, "energy") < 0.3f) wants.put("rest for a bit")
        val o = JSONObject().put("name", Being.name(c)).put("stage", Being.stage(c)).put("age", Being.ageDays(c))
            .put("mood", Being.drive(c, "mood").toDouble()).put("energy", Being.drive(c, "energy").toDouble())
            .put("curiosity", Being.drive(c, "curiosity").toDouble()).put("hunger", Being.drive(c, "hunger").toDouble())
            .put("bond", 1.0 - Being.drive(c, "missing").toDouble()).put("hour", now.hour + now.minute / 60.0)
            .put("wants", wants)
        if (look != null) o.put("hue", (look.hue / 360f).toDouble())
        js("Being3D.setState(${o})")
    }

    private val TASKY = Regex("^(please |can you |could you )?(add|log|remind|send|call|open|delete|schedule|buy|find|check|make|write|set|create|note|plan|summar|tidy|clean|look up|search|track|book|cancel|move|pay|text|email)\\b", RegexOption.IGNORE_CASE)

    @JavascriptInterface
    fun onEvent(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        when (o.optString("type")) {
            "ready" -> pushState()
            "back" -> main.post { back() }
            "open" -> main.post { open(o.optString("route")) }
            "done" -> {
                val fx = o.optJSONObject("fx")
                fx?.keys()?.forEach { k -> if (k in Being.DRIVES) Being.nudge(c, k, fx.optDouble(k, 0.0).toFloat()) }
                Being.version++
                if (o.optBoolean("agent")) scope.launch {
                    log("${Being.name(c)} is working on something for you…")
                    val reply = BeingAgent.autonomous(container, c, o.optString("act"))
                    say(reply.take(220)); log(reply.take(300)); pushState()
                } else pushState()
            }
            "ask" -> {
                val text = o.optString("text").trim(); if (text.isEmpty()) return
                scope.launch {
                    if (TASKY.containsMatchIn(text)) {
                        log("${Being.name(c)} is on it…")
                        val reply = BeingAgent.run(container, c, text)
                        say(reply.take(220)); log(reply.take(400))
                    } else {
                        val line = runCatching { Being.talk(container, c, text) }.getOrNull()
                        say(line?.text ?: "…", line?.emotion ?: "happy")
                    }
                    pushState()
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun Being3DScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val c = LocalContext.current
    val scope = rememberSafeScope()
    val holder = remember { arrayOfNulls<WebView>(1) }
    val activity = c as? android.app.Activity
    val bridge = remember { Bridge(c, container, scope, { holder[0] }, onOpen) { activity?.onBackPressedDispatcher?.onBackPressed() } }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { Being.visited(c); Being.liveNow(c) } }
    LaunchedEffect(Unit) { while (true) { delay(4000); bridge.pushState() } }
    DisposableEffect(Unit) { onDispose { holder[0]?.destroy(); holder[0] = null } }
    AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx ->
        WebView(ctx).apply {
            holder[0] = this
            setBackgroundColor(0)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(bridge, "Android")
            webViewClient = object : WebViewClient() { override fun onPageFinished(view: WebView?, url: String?) { bridge.pushState() } }
            loadUrl("file:///android_asset/being3d/index.html")
        }
    })
}
