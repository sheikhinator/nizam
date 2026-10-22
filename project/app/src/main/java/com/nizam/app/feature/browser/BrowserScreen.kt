package com.nizam.app.feature.browser

import androidx.compose.foundation.clickable

import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.nizam.app.AppContainer
import kotlinx.coroutines.launch

/**
 * A real browser inside Atlas. Pages can be handed straight to the Curator, so "read this and
 * add it to my notes" works without copy-paste.
 */
@Composable
fun BrowserScreen(container: AppContainer) {
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    var url by remember { mutableStateOf("https://duckduckgo.com") }
    var address by remember { mutableStateOf(url) }
    var progress by remember { mutableStateOf(0) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageText by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var space by remember { mutableStateOf(Workspaces.current(ctx)) }
    var pages by remember(space) { mutableStateOf(Workspaces.pages(ctx, space)) }
    var spaceMenu by remember { mutableStateOf(false) }
    var newSpace by remember { mutableStateOf("") }

    fun go(raw: String) {
        val target = raw.trim()
        url = when {
            target.startsWith("http://") || target.startsWith("https://") -> target
            target.contains(".") && !target.contains(" ") -> "https://$target"
            else -> "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(target, "UTF-8")
        }
        webView?.loadUrl(url)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text("Search or type a URL") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go(address) }),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { go(address) }) { Text("Go") }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedButton(onClick = { if (webView?.canGoBack() == true) webView?.goBack() }) { Text("<") }
            OutlinedButton(onClick = { if (webView?.canGoForward() == true) webView?.goForward() }) { Text(">") }
            OutlinedButton(onClick = { webView?.reload() }) { Text("⟳") }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    val capturedUrl = webView?.url ?: url
                    val capturedTitle = webView?.title.orEmpty()
                    // evaluateJavascript is async: the text only exists inside this callback.
                    // Saving outside it stored the previous page, or nothing.
                    webView?.evaluateJavascript(
                        "(function(){return document.body.innerText.slice(0,12000)})()"
                    ) { raw ->
                        val text = raw.orEmpty()
                            .removeSurrounding("\"")
                            .replace("\\n", "\n").replace("\\t", " ").replace("\\\"", "\"")
                        pageText = text
                        scope.launch {
                            container.agentMemory.remember(
                                "Page the user shared: $capturedTitle ($capturedUrl)\n" + text.take(4000)
                            )
                            status = "Captured \"${capturedTitle.ifBlank { capturedUrl }}\" — ask the Curator about it."
                            busy = false
                        }
                    }
                }
            ) { Text("↗ Send to Curator") }
        }

        // workspaces: named sets of pages (Study, Work, Shopping), each remembered separately
        androidx.compose.foundation.lazy.LazyRow(
            Modifier.fillMaxWidth().padding(horizontal = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                androidx.compose.foundation.layout.Box {
                    OutlinedButton(onClick = { spaceMenu = true }) { Text("📁 $space") }
                    androidx.compose.material3.DropdownMenu(spaceMenu, { spaceMenu = false }) {
                        Workspaces.names(ctx).forEach { n ->
                            androidx.compose.material3.DropdownMenuItem(text = { Text(n) }, onClick = {
                                Workspaces.setCurrent(ctx, n); space = n; spaceMenu = false
                                Workspaces.pages(ctx, n).firstOrNull()?.let { go(it.first) }
                            })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                            OutlinedTextField(newSpace, { newSpace = it }, label = { Text("New workspace") }, singleLine = true, modifier = Modifier.width(170.dp))
                            androidx.compose.material3.TextButton(onClick = {
                                if (newSpace.isNotBlank()) { Workspaces.setCurrent(ctx, newSpace.trim()); space = newSpace.trim(); newSpace = ""; spaceMenu = false }
                            }) { Text("Add") }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = {
                    Workspaces.add(ctx, space, webView?.url ?: url, webView?.title.orEmpty()); pages = Workspaces.pages(ctx, space)
                }) { Text("+ Keep page") }
            }
            pages.forEach { (u, t) ->
                item {
                    androidx.compose.material3.AssistChip(onClick = { address = u; go(u) },
                        label = { Text(t.ifBlank { u.removePrefix("https://").take(24) }.take(24), maxLines = 1) },
                        trailingIcon = { Text("✕", Modifier.clickable { Workspaces.remove(ctx, space, u); pages = Workspaces.pages(ctx, space) }) })
                }
            }
        }

        if (progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }
        run {
            val host = runCatching { android.net.Uri.parse(address).host }.getOrNull().orEmpty()
            var shieldOn by remember { mutableStateOf(AdBlock.on(ctx)) }
            var siteOff by remember(host) { mutableStateOf(AdBlock.siteOff(ctx, host)) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (shieldOn && !siteOff) "🛡 Ad block on · ${AdBlock.blocked} stopped" else "🛡 Ad block off", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                Text(if (shieldOn) "Turn off" else "Turn on", Modifier.clickable { shieldOn = !shieldOn; AdBlock.setOn(ctx, shieldOn); webView?.reload() }.padding(8.dp), style = MaterialTheme.typography.labelMedium)
                if (shieldOn && host.isNotBlank()) Text(if (siteOff) "Block here" else "Allow this site", Modifier.clickable { siteOff = !siteOff; AdBlock.setSiteOff(ctx, host, siteOff); webView?.reload() }.padding(8.dp), style = MaterialTheme.typography.labelMedium)
            }
        }
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 12.dp)
            )
        }

        AndroidView(
            factory = { context ->
                WebView(context).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.builtInZoomControls = true
                    settings.displayZoomControls = false
                    settings.javaScriptCanOpenWindowsAutomatically = false
                    webViewClient = AdBlockClient(context)
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            progress = newProgress
                            view?.url?.let { address = it }
                        }
                    }
                    loadUrl(url)
                    webView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

/** Named sets of pages, kept on the phone. */
object Workspaces {
    private fun p(c: android.content.Context) = c.getSharedPreferences("browser_spaces", 0)
    private fun all(c: android.content.Context) = runCatching { org.json.JSONObject(p(c).getString("all", "{}")!!) }.getOrElse { org.json.JSONObject() }
    fun current(c: android.content.Context) = p(c).getString("cur", "General")!!
    fun setCurrent(c: android.content.Context, n: String) {
        val a = all(c); if (!a.has(n)) a.put(n, org.json.JSONArray())
        p(c).edit().putString("cur", n).putString("all", a.toString()).apply()
    }
    fun names(c: android.content.Context): List<String> = (all(c).keys().asSequence().toList() + "General").distinct()
    fun pages(c: android.content.Context, n: String): List<Pair<String, String>> = all(c).optJSONArray(n)?.let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("u") to it.optString("t") } } ?: emptyList()
    fun add(c: android.content.Context, n: String, u: String, t: String) {
        val a = all(c); val l = pages(c, n).filter { it.first != u } + (u to t)
        a.put(n, org.json.JSONArray().apply { l.takeLast(30).forEach { put(org.json.JSONObject().put("u", it.first).put("t", it.second)) } })
        p(c).edit().putString("all", a.toString()).apply()
    }
    fun remove(c: android.content.Context, n: String, u: String) {
        val a = all(c); a.put(n, org.json.JSONArray().apply { pages(c, n).filter { it.first != u }.forEach { put(org.json.JSONObject().put("u", it.first).put("t", it.second)) } })
        p(c).edit().putString("all", a.toString()).apply()
    }
}
