package com.nizam.app.feature.podcasts

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import com.nizam.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Podcast playback that survives the screen going off, with notification and lock-screen controls.
 *
 * The player is built here, on the application context, BEFORE the service starts. The service then
 * posts its notification in the first moment of onStartCommand. That ordering is the whole fix: a
 * foreground service that doesn't show a notification within five seconds is killed by Android,
 * which is what crashed the app on every play.
 */
object Playback {
    var player: ExoPlayer? = null
        private set
    // Compose state: touch these and the UI updates itself. Plain fields did not, which is why the
    // play button looked broken — it worked, but never redrew.
    var current: Episode? by mutableStateOf(null)
        private set
    var isPlaying: Boolean by mutableStateOf(false)
        private set
    var positionMs: Long by mutableStateOf(0L)
        private set
    var durationMs: Long by mutableStateOf(0L)
        private set
    var buffering: Boolean by mutableStateOf(false)
        private set
    var speed: Float by mutableStateOf(1f)
        private set
    var boost: Boolean by mutableStateOf(false)
        private set
    var trimSilence: Boolean by mutableStateOf(false)
        private set
    var sleepMinutesLeft: Int by mutableStateOf(0)
        private set
    private var sleepJob: Job? = null
    private var store: PodcastStore? = null
    private var ticks = 0

    fun ensure(c: Context): ExoPlayer {
        player?.let { return it }
        val app = c.applicationContext
        val p = ExoPlayer.Builder(app)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing; PodcastService.refresh(app) }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) { PodcastService.refresh(app) }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                durationMs = p.duration.coerceAtLeast(0)
                PodcastService.refresh(app)
                if (state == Player.STATE_ENDED) playNextFromQueue(app)
            }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                isPlaying = false
                com.nizam.app.ui.components.Feedback.show(when (e.errorCode) {
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "No connection — download episodes to listen offline"
                    androidx.media3.common.PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The podcast's server refused this episode — try again later"
                    else -> "Couldn't play that episode"
                })
            }
        })
        // one cheap ticker for the whole app, instead of every screen polling
        kotlinx.coroutines.CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                delay(500)
                positionMs = p.currentPosition.coerceAtLeast(0)
                if (p.duration > 0) durationMs = p.duration
                val ep = current
                ticks++
                if (ep != null && isPlaying && ticks % 20 == 0) {            // disk write every 10s, not every second
                    store?.savePosition(ep, positionMs, durationMs)
                    store?.addListened(10, speed)
                }
            }
        }
        player = p
        return p
    }

    fun start(c: Context, episode: Episode, store: PodcastStore) {
        this.store = store
        com.nizam.app.feature.music.MusicPlayback.player?.pause()          // one thing at a time
        val p = ensure(c)
        val local = store.downloaded(episode)
        p.setMediaItem(
            MediaItem.Builder()
                .setUri(local?.let { android.net.Uri.fromFile(it) } ?: android.net.Uri.parse(episode.audio))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(episode.title).setArtist(episode.show)
                    .setArtworkUri(episode.image?.let { android.net.Uri.parse(it) }).build())
                .build()
        )
        p.prepare()
        val resume = store.position(episode.id).takeIf { it > 5_000 }
        val intro = store.skipIntro(episode.feed) * 1000L
        p.seekTo(resume ?: intro)                       // skip the show's intro when there's nothing to resume
        store.showSpeed(episode.feed).takeIf { it > 0f }?.let { speed = it }
        applyAudio(p)
        p.playWhenReady = true
        current = episode
        positionMs = 0; durationMs = 0; buffering = true
        PodcastService.start(c)       // notification appears immediately — see the class below
    }

    /** Auto-play: when an episode ends, the next in Up Next starts. */
    private fun playNextFromQueue(c: Context) {
        val s = store ?: return
        val queue = s.queue().filter { it.id != current?.id }
        s.setQueue(queue)
        queue.firstOrNull()?.let { start(c, it, s) }
    }

    private fun applyAudio(p: ExoPlayer) {
        p.setPlaybackSpeed(speed)
        p.skipSilenceEnabled = trimSilence          // Pocket Casts' "trim silence"
        p.volume = if (boost) 1f else 0.85f          // headroom, then boost to full
    }

    fun toggle() = player?.let { if (it.isPlaying) it.pause() else it.play(); isPlaying = it.isPlaying }
    /** Called when the user leaves the podcast screens — keeps the last position safe. */
    fun persist() { val ep = current ?: return; if (durationMs > 0) store?.savePosition(ep, positionMs, durationMs) }
    fun skip(seconds: Int) = player?.let { it.seekTo((it.currentPosition + seconds * 1000L).coerceAtLeast(0)) }
    fun seekTo(ms: Long) = player?.seekTo(ms)
    fun applySpeed(v: Float) { speed = v; player?.setPlaybackSpeed(v) }
    fun setTrim(on: Boolean) { trimSilence = on; player?.skipSilenceEnabled = on }
    fun applyBoost(on: Boolean) { boost = on; player?.volume = if (on) 1f else 0.85f }

    fun stop(c: Context) {
        player?.pause()
        sleepJob?.cancel(); sleepMinutesLeft = 0
        c.stopService(Intent(c, PodcastService::class.java))
    }

    fun sleep(minutes: Int, scope: CoroutineScope) {
        sleepJob?.cancel(); sleepMinutesLeft = minutes
        if (minutes <= 0) return
        sleepJob = scope.launch(Dispatchers.Main) {
            while (sleepMinutesLeft > 0) { delay(60_000); sleepMinutesLeft-- }
            player?.pause()
        }
    }

    /** Sleep at the end of this episode rather than after a fixed time. */
    fun sleepAtEpisodeEnd(scope: CoroutineScope) {
        sleepJob?.cancel(); sleepMinutesLeft = 0
        sleepJob = scope.launch(Dispatchers.Main) {
            while (true) {
                delay(5_000)
                val p = player ?: break
                if (p.duration > 0 && p.currentPosition >= p.duration - 1_000) { p.pause(); break }
            }
        }
    }
}

class PodcastService : Service() {
    private var session: MediaSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // notification first, before anything can go wrong
        startForegroundNow()
        when (intent?.action) {
            "toggle" -> Playback.toggle()
            "back" -> Playback.skip(-15)
            "forward" -> Playback.skip(30)
            "stop" -> { Playback.player?.pause(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        }
        if (session == null) Playback.player?.let { session = MediaSession.Builder(this, it).build() }
        return START_STICKY
    }

    private fun action(name: String, label: String, code: Int, icon: Int) = Notification.Action.Builder(
        android.graphics.drawable.Icon.createWithResource(this, icon), label, PendingIntent.getService(this, code, Intent(this, PodcastService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Podcasts", NotificationManager.IMPORTANCE_LOW)
            .apply { setShowBadge(false) })
        val ep = Playback.current
        val playing = Playback.player?.isPlaying == true
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(ep?.title ?: "Podcast")
            .setContentText(ep?.show ?: "Loading…")
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE))
            .addAction(action("back", "Back 15 s", 1, android.R.drawable.ic_media_rew))
            .addAction(action("toggle", if (playing) "Pause" else "Play", 2,
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play))
            .addAction(action("forward", "Forward 30 s", 3, android.R.drawable.ic_media_ff))
            .addAction(action("stop", "Stop", 4, android.R.drawable.ic_menu_close_clear_cancel))
            .setOngoing(playing)
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(9, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(9, n)
    }

    override fun onDestroy() {
        session?.release(); session = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "podcasts"
        fun start(c: Context) = runCatching { c.startForegroundService(Intent(c, PodcastService::class.java)) }
        /** Redraw the notification whenever playback state changes. */
        fun refresh(c: Context) { if (Playback.current != null) runCatching { c.startForegroundService(Intent(c, PodcastService::class.java)) } }
    }
}
