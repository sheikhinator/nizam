package com.nizam.app.ui.read

import android.annotation.SuppressLint
import android.graphics.Color as AndroidColor
import android.webkit.WebView
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Real math rendering. KaTeX ships inside the APK (assets/katex), so formulas typeset properly
 * with no network — the raw `\sum_{i=1}^{5}` you were seeing is exactly what this replaces.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MathView(latex: String, textColor: Color, modifier: Modifier = Modifier) {
    val hex = String.format("#%06X", 0xFFFFFF and textColor.toArgb())
    // Escape for embedding inside a JS string literal
    val escaped = latex
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", " ")
        .removePrefix("$$").removeSuffix("$$")
        .removePrefix("$").removeSuffix("$")

    val html = """
        <!DOCTYPE html><html><head>
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <link rel="stylesheet" href="file:///android_asset/katex/katex.min.css">
        <script src="file:///android_asset/katex/katex.min.js"></script>
        <style>
          html,body{margin:0;padding:6px 2px;background:transparent;color:$hex;
                    font-size:19px;overflow-x:auto;overflow-y:hidden}
          .katex-display{margin:0}
          #err{font-family:monospace;font-size:15px;opacity:.85}
        </style></head><body>
        <div id="m"></div>
        <script>
          try {
            katex.render('$escaped', document.getElementById('m'),
              {displayMode:true, throwOnError:false, output:'html'});
          } catch(e) {
            document.getElementById('m').innerHTML = '<div id="err"></div>';
            document.getElementById('err').textContent = '$escaped';
          }
        </script></body></html>
    """.trimIndent()

    AndroidView(
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                setBackgroundColor(AndroidColor.TRANSPARENT)
                isVerticalScrollBarEnabled = false
                loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
            }
        },
        update = { view ->
            view.loadDataWithBaseURL("file:///android_asset/", html, "text/html", "utf-8", null)
        },
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp, max = 220.dp)
    )
}
