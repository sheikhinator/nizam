package com.nizam.app.feature.phone

import kotlinx.coroutines.launch
import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Tier 2: Atlas can see incoming notifications, so "what did I miss?" has a real answer, and it
 * can reply to messages through the notification's own reply action — the same mechanism Wear OS
 * and car displays use.
 *
 * Nothing is stored on disk. The last 60 live only in memory and vanish when the service stops.
 */
class AtlasNotificationListener : NotificationListenerService() {

    data class Seen(
        val key: String,
        val app: String,
        val title: String,
        val text: String,
        val postedAt: Long,
        val canReply: Boolean
    )

    companion object {
        private val recent = ConcurrentLinkedDeque<Seen>()
        private val live = mutableMapOf<String, StatusBarNotification>()
        @Volatile var connected = false
            private set

        fun recent(limit: Int = 30): List<Seen> = recent.take(limit)

        fun clear() { recent.clear() }

        @Volatile var instance: AtlasNotificationListener? = null
        /** Bumps whenever the shade changes, so the launcher's notification panel redraws. */
        val version = kotlinx.coroutines.flow.MutableStateFlow(0)
        private fun bump() { version.value++ }

        /** What's in the notification shade right now, newest first (ongoing/system noise left out). */
        fun active(): List<StatusBarNotification> = synchronized(live) { live.values.toList() }
            .filter { !it.isOngoing && it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) != null }
            .sortedByDescending { it.postTime }

        fun open(context: android.content.Context, key: String) {
            val sbn = synchronized(live) { live[key] } ?: return
            runCatching { sbn.notification.contentIntent?.send() }
                .onFailure { context.packageManager.getLaunchIntentForPackage(sbn.packageName)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
            if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) dismiss(key)
        }
        fun dismiss(key: String) { runCatching { instance?.cancelNotification(key) }; synchronized(live) { live.remove(key) }; bump() }
        fun dismissAll() { runCatching { instance?.cancelAllNotifications() }; synchronized(live) { live.entries.removeAll { !it.value.isOngoing } }; bump() }

        /** Replies through the notification's own inline-reply action. */
        fun reply(context: android.content.Context, match: String, message: String): String {
            val target = synchronized(live) {
                live.values.firstOrNull { sbn ->
                    val extras = sbn.notification.extras
                    val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                    title.contains(match, ignoreCase = true) ||
                        sbn.packageName.contains(match, ignoreCase = true)
                }
            } ?: return "No active notification from \"$match\" to reply to."

            val action = target.notification.actions?.firstOrNull { a ->
                a.remoteInputs?.isNotEmpty() == true
            } ?: return "That notification doesn't support replying."

            return runCatching {
                val input = action.remoteInputs.first()
                val results = Bundle().apply { putCharSequence(input.resultKey, message) }
                val intent = Intent()
                RemoteInput.addResultsToIntent(action.remoteInputs, intent, results)
                action.actionIntent.send(context, 0, intent)
                "Replied: \"$message\""
            }.getOrElse { "Reply failed: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
        }
    }

    override fun onListenerConnected() {
        connected = true; instance = this
        // pick up what's already in the shade, so the launcher's panel isn't empty until something new arrives
        runCatching { activeNotifications?.forEach { sbn -> synchronized(live) { live[sbn.key] = sbn } }; bump() }
    }
    override fun onListenerDisconnected() { connected = false; instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return       // ignore Atlas's own
        if (sbn.isOngoing) return                          // music players, downloads, etc.
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        runCatching { com.nizam.app.feature.wild.SocialDigest.collect(this, sbn.packageName, title, text) }
        runCatching { com.nizam.app.feature.flows.FlowEngine.onEvent(this, "notification", mapOf("app" to sbn.packageName, "title" to title, "text" to text.take(400))) }

        val appName = runCatching {
            packageManager.getApplicationLabel(
                packageManager.getApplicationInfo(sbn.packageName, 0)
            ).toString()
        }.getOrElse { sbn.packageName }

        val canReply = sbn.notification.actions?.any { it.remoteInputs?.isNotEmpty() == true } == true
        runCatching { com.nizam.app.feature.future.Earbuds.onNotification(this, appName, sbn.packageName, title, text) }
        runCatching { com.nizam.app.feature.future.Twin.onNotification(this, sbn, appName) }
        runCatching { com.nizam.app.feature.future.Guardian.onMessage(this, appName, title, text) }
        runCatching { com.nizam.app.feature.future.MoneyPilot.onNotification(this, sbn.packageName, title, text) }

        recent.addFirst(Seen(sbn.key, appName, title, text.take(400), sbn.postTime, canReply))
        val att = com.nizam.app.feature.attention.Attention
        if (att.digestOn(this) && sbn.packageName in att.heldApps(this)) {
            att.hold(this, appName, title, text)
            runCatching { cancelNotification(sbn.key) }   // silenced now, summarised later
        }
        runCatching {
            com.nizam.app.feature.jarvis.Timeline.log(this, "message", "$appName: $title".trim(), text.take(140))
            com.nizam.app.feature.jarvis.StandingOrders.onEvent(this, "notification", appName, sbn.packageName, "$title $text")
            com.nizam.app.feature.jarvis.Recall.onMessage(this, appName, sbn.packageName, title)
            val ctx = applicationContext
            val pkg = sbn.packageName
            com.nizam.app.feature.jarvis.Background.scope.launch {
                runCatching { com.nizam.app.feature.jarvis.AutoReply.onMessage(ctx, appName, pkg, title, canReply) }
            }
        }
        while (recent.size > 60) recent.removeLast()
        synchronized(live) { live[sbn.key] = sbn }; bump()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        synchronized(live) { live.remove(sbn.key) }; bump()
    }
}
