package com.nizam.app.feature.safe

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * Atlas's safety net for background work started from a button (a download, an AI call, a file import): if
 * it fails in a way the screen didn't expect, Atlas records it in the crash log and tells you in one line,
 * instead of closing. Registered with kotlinx.coroutines through META-INF/services. Real errors (out of
 * memory and the like) still go to the normal crash handler.
 */
class CoroutineSafetyNet : AbstractCoroutineContextElement(CoroutineExceptionHandler), CoroutineExceptionHandler {
    override fun handleException(context: CoroutineContext, exception: Throwable) {
        if (exception is Error) return
        report(exception)
        // tell kotlinx.coroutines the exception is handled, so it doesn't close the app
        val handled = runCatching {
            Class.forName("kotlinx.coroutines.internal.ExceptionSuccessfullyProcessed").getField("INSTANCE").get(null) as? Throwable
        }.getOrNull()
        if (handled != null) throw handled
    }

    companion object {
        /** For a screen's own scope: handles a failed coroutine in place (logs it, says it in one line). */
        val inScope = CoroutineExceptionHandler { _, e -> if (e !is kotlinx.coroutines.CancellationException) report(e) }

        fun report(exception: Throwable) {
        if (exception is kotlinx.coroutines.CancellationException) return
        val c = com.nizam.app.net.Router.appContext ?: return
        runCatching {
            val f = java.io.File(c.filesDir, "crash.log")
            val old = if (f.exists() && f.length() < 40_000) f.readText() else ""
            f.writeText("${java.time.Instant.now()} [caught] ${exception::class.java.name}: ${exception.message}\n" +
                exception.stackTrace.take(10).joinToString("\n") { "  at $it" } + "\n\n" + old)
        }
        runCatching {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                runCatching { com.nizam.app.ui.components.Feedback.show(friendly(exception)) }
            }
        }
        }

    private fun friendly(e: Throwable): String {
        val m = e.message.orEmpty()
        return when {
            e is java.net.UnknownHostException || m.contains("Unable to resolve host", true) -> "No internet connection right now."
            e is java.net.SocketTimeoutException -> "That took too long. Try again."
            e is java.io.IOException -> "Couldn't finish: ${m.take(80).ifBlank { "a connection or file problem" }}"
            else -> "Something went wrong: ${m.take(90).ifBlank { e::class.java.simpleName }}"
        }
    }
}
}
