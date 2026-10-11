package com.nizam.app.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * One place for "something happened". Any screen can call Feedback.show("Saved") and the person
 * sees it, wherever they are in the app. Paired with a haptic tick on every pressable control, so a
 * tap always confirms itself.
 */
object Feedback {
    private var host: SnackbarHostState? = null
    private var scope: CoroutineScope? = null

    fun attach(h: SnackbarHostState, s: CoroutineScope) { host = h; scope = s }

    fun show(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
        val h = host ?: return
        scope?.launch {
            val result = h.showSnackbar(message, action, duration = if (action == null) SnackbarDuration.Short else SnackbarDuration.Long)
            if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onAction?.invoke()
        }
    }

    /** Something was removed: say so, and offer to put it back. */
    fun undo(message: String, restore: () -> Unit) = show(message, "Undo", restore)

    /** For work that takes a moment: show it started, then report how it ended. */
    fun working(message: String) = show(message)
    fun failed(what: String, error: Throwable?) = show("$what didn't work: ${friendly(error)}")

    /** Turns provider noise into something a person can act on. */
    fun friendly(e: Throwable?): String {
        val m = e?.message.orEmpty()
        return when {
            m.contains("503") || m.contains("UNAVAILABLE", true) -> "the AI is busy right now — try again in a moment"
            m.contains("429") || m.contains("rate limit", true) -> "rate limit reached — wait a minute or switch provider in Settings"
            m.contains("401") || m.contains("403") -> "your API key was rejected — check it in Settings"
            e is java.net.UnknownHostException || m.contains("Unable to resolve host") -> "no internet connection"
            e is java.net.SocketTimeoutException || m.contains("timeout", true) -> "the connection timed out — try again"
            e is java.net.ConnectException -> "couldn't reach the server — try again in a moment"
            e is javax.net.ssl.SSLException -> "a secure connection couldn't be made — check the date/time on your phone"
            e is org.json.JSONException -> "the service sent back something unexpected — try again"
            e is SecurityException -> "Atlas needs a permission for that — allow it in the phone's settings"
            e is java.io.FileNotFoundException -> if (m.startsWith("http")) "that link no longer exists" else "the file couldn't be found"
            m.contains("404") -> "that wasn't found on the server"
            m.contains("500") || m.contains("502") || m.contains("504") -> "the service is having problems — try again later"
            m.isBlank() -> "something went wrong — try again"
            else -> m.take(120)
        }
    }
}
