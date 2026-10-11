package com.nizam.app.feature.phone

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.nizam.app.MainActivity

/**
 * Registering these makes Atlas selectable as the phone's default digital assistant — so a
 * long-press on the power or home button opens Atlas's Curator instead of Google, exactly the
 * way Gemini takes over that gesture.
 */
class AtlasVoiceService : VoiceInteractionService()

class AtlasSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = AtlasSession(this)
}

class AtlasSession(context: Context) : VoiceInteractionSession(context) {
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var screenText = ""
    private var done = false

    /** Long-press power: Atlas gets the screen (when "Use screenshot" is allowed) and opens Circle to Search on it. */
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        done = false
        // no screenshot within a second (not allowed, or a secure screen): open the Atlas chat instead
        handler.postDelayed({ if (!done) { done = true; openCurator() } }, 1200)
    }

    override fun onHandleAssist(state: AssistState) {
        runCatching {
            val st = state.assistStructure ?: return
            val sb = StringBuilder()
            fun walk(n: android.app.assist.AssistStructure.ViewNode?) {
                n ?: return
                n.text?.toString()?.takeIf { it.isNotBlank() }?.let { sb.append(it).append('\n') }
                for (i in 0 until n.childCount) walk(n.getChildAt(i))
            }
            for (w in 0 until st.windowNodeCount) walk(st.getWindowNodeAt(w).rootViewNode)
            screenText = sb.toString()
        }
    }

    override fun onHandleScreenshot(screenshot: android.graphics.Bitmap?) {
        if (done) return
        if (screenshot == null) return                     // the fallback timer opens the chat
        done = true
        runCatching { com.nizam.app.feature.future.CircleShot.save(context, screenshot, screenText) }
        startAssistantActivity(Intent(context, com.nizam.app.feature.future.CircleActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        hide()
    }

    private fun openCurator() {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_CURATOR)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startAssistantActivity(intent)
        hide()
    }
}

/**
 * Android only lists an app as a selectable digital assistant if it also declares a
 * RecognitionService. Atlas does its own speech capture inside the Curator, so this defers to
 * the system recogniser rather than implementing one.
 */
class AtlasRecognitionService : android.speech.RecognitionService() {
    override fun onStartListening(intent: Intent?, listener: Callback?) {
        runCatching { listener?.error(android.speech.SpeechRecognizer.ERROR_CLIENT) }
    }
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
