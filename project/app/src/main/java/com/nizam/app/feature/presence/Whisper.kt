package com.nizam.app.feature.presence

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.nizam.app.feature.atlascore.AtlasCore
import com.nizam.app.feature.capsule.loadCapsules
import com.nizam.app.feature.mind.Neurgraph
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalTime

data class Whisper(val id: String, val text: String)

/**
 * The whisper layer.
 *
 * Atlas speaks first, rarely, and only about something real that is already on this
 * phone. No model call, no notification, no badge: one line at the edge of the screen
 * that leaves on its own. It is quiet at night, silent in solitude, and off if you say so.
 */
object WhisperEngine {
    private const val PREFS = "whispers"
    private const val THREE_HOURS = 3L * 60L * 60L * 1000L

    private val _current = MutableStateFlow<Whisper?>(null)
    val current: StateFlow<Whisper?> = _current.asStateFlow()

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", true)

    fun setEnabled(context: Context, on: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("on", on).apply()

    fun dismiss() {
        _current.value = null
    }

    /** Called on a slow loop. It decides for itself whether there is anything worth saying. */
    fun consider(context: Context) {
        if (_current.value != null) return
        if (AtlasCore.state.value.solitude) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean("on", true)) return

        val hour = LocalTime.now().hour
        if (hour < 7 || hour >= 23) return

        val now = System.currentTimeMillis()
        if (now - prefs.getLong("last", 0L) < THREE_HOURS) return

        val line = pick(context) ?: return
        prefs.edit().putLong("last", now).apply()
        AtlasCore.whisper()
        _current.value = Whisper(now.toString(), line)
    }

    private fun pick(context: Context): String? {
        val ready = runCatching {
            loadCapsules(context).count { it.sealedUntil <= System.currentTimeMillis() }
        }.getOrDefault(0)
        if (ready == 1) return "A letter you wrote to yourself is ready to open."
        if (ready > 1) return ready.toString() + " letters to yourself are ready."

        val core = runCatching { Neurgraph.core(context, 3) }.getOrElse { emptyList() }
        val subject = core.firstOrNull()?.label
        if (!subject.isNullOrBlank()) {
            val hour = LocalTime.now().hour
            return when {
                hour < 11 -> "Morning. " + subject + " keeps coming back. Ten minutes on it?"
                hour < 16 -> "Still carrying " + subject + ". One small step is enough."
                hour < 20 -> "The day is nearly done. " + subject + " can wait until tomorrow."
                else -> "Before you sleep: " + subject + " is still here. It can wait until morning."
            }
        }

        val count = runCatching { Neurgraph.stats(context).first }.getOrDefault(0)
        return if (count == 0) "I am here. Nothing needs you right now."
        else count.toString() + " things kept so far. All of it stays on this phone."
    }
}

/**
 * Sits above the whole app and shows one line when Atlas has something to say.
 * It never blocks the screen: the bubble is the only thing that takes touches.
 */
@Composable
fun PresenceHost(enabled: Boolean = true) {
    val context = LocalContext.current
    val whisper by WhisperEngine.current.collectAsState()

    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        delay(9000)
        while (true) {
            runCatching { WhisperEngine.consider(context) }
            delay(600000)
        }
    }

    val current = whisper
    if (enabled && current != null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 172.dp)) {
                MicroPresence.WhisperBubble(
                    message = current.text,
                    onDismiss = { WhisperEngine.dismiss() },
                    onTap = { WhisperEngine.dismiss() }
                )
            }
        }
    }
}
