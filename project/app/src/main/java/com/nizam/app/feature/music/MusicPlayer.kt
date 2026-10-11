package com.nizam.app.feature.music

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Music playback: one ExoPlayer on the application context, a real queue (the player's own
 * playlist), shuffle and repeat, gapless transitions, and a media session so the lock screen,
 * notification, headphones and car controls all work. Podcasts pause when music starts.
 */
object MusicPlayback {
    var player: ExoPlayer? = null
        private set
    var queue: List<Track> by mutableStateOf(emptyList())
        private set
    var index: Int by mutableStateOf(0)
        private set
    var isPlaying: Boolean by mutableStateOf(false)
        private set
    var positionMs: Long by mutableStateOf(0L)
        private set
    var durationMs: Long by mutableStateOf(0L)
        private set
    var shuffle: Boolean by mutableStateOf(false)
        private set
    var repeat: Int by mutableStateOf(Player.REPEAT_MODE_OFF)
        private set
    val current: Track? get() = queue.getOrNull(index)
    /** Keep playing similar music when the queue runs out. */
    var autoplay: Boolean by mutableStateOf(true)
    /** Sleep timer: pause at this time (ms), or at the end of the current song. */
    var sleepAt: Long by mutableStateOf(0L)
    var sleepEndOfTrack: Boolean by mutableStateOf(false)
    var speed: Float by mutableStateOf(1f)
        private set
    val isLive: Boolean get() = current?.source == "radio"
    private val bg = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main)
    private var extending = false

    private fun extendIfNeeded(c: Context) {
        val t = current ?: return
        if (!autoplay || extending || t.source == "radio" || index < queue.size - 1) return
        extending = true
        bg.launch {
            runCatching { Mixes.similar(c, t, queue.map { it.id }.toSet()) }.getOrElse { emptyList() }.forEach { enqueue(c, it, false) }
            extending = false
        }
    }

    fun sleepIn(c: Context, minutes: Int) { sleepEndOfTrack = false; sleepAt = if (minutes <= 0) 0L else System.currentTimeMillis() + minutes * 60_000L }
    fun changeSpeed(f: Float) { speed = f; player?.setPlaybackSpeed(f) }

    fun ensure(c: Context): ExoPlayer {
        player?.let { return it }
        val app = c.applicationContext
        val p = ExoPlayer.Builder(app)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        p.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing; MusicService.refresh(app) }
            override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                index = p.currentMediaItemIndex
                if (sleepEndOfTrack && reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) { p.pause(); sleepEndOfTrack = false }
                extendIfNeeded(app)
                current?.let { MusicLibrary.played(app, it.id) }
                MusicService.refresh(app)
            }
            override fun onPlaybackStateChanged(state: Int) { durationMs = p.duration.coerceAtLeast(0) }
            override fun onShuffleModeEnabledChanged(on: Boolean) { shuffle = on }
            override fun onRepeatModeChanged(mode: Int) { repeat = mode }
            override fun onAudioSessionIdChanged(id: Int) { MusicFx.attach(app, id) }
            override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                com.nizam.app.ui.components.Feedback.show("Couldn't play ${current?.title ?: "that"} — skipping")
                if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare(); p.play() }
            }
        })
        CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                delay(400)
                positionMs = p.currentPosition.coerceAtLeast(0)
                if (p.duration > 0) durationMs = p.duration
                if (sleepAt > 0 && System.currentTimeMillis() >= sleepAt) { sleepAt = 0; p.pause() }
            }
        }
        player = p
        autoplay = app.getSharedPreferences("music", 0).getBoolean("autoplay", true)
        MusicFx.attach(app, p.audioSessionId)
        return p
    }

    private fun item(t: Track) = MediaItem.Builder().setMediaId(t.id).setUri(Uri.parse(t.uri))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
            .setArtworkUri(t.art.takeIf { it.isNotBlank() }?.let { Uri.parse(it) }).build()).build()

    /** Plays [list] starting at [start] — the list becomes the queue. */
    fun play(c: Context, list: List<Track>, start: Int = 0) {
        if (list.isEmpty()) return
        com.nizam.app.feature.podcasts.Playback.player?.pause()          // one thing at a time
        val p = ensure(c)
        queue = list; index = start.coerceIn(0, list.lastIndex)
        p.setMediaItems(list.map(::item), index, 0)
        p.prepare(); p.play()
        MusicService.start(c)
        extendIfNeeded(c.applicationContext)
    }

    fun setAutoplay(c: Context, on: Boolean) { autoplay = on; c.getSharedPreferences("music", 0).edit().putBoolean("autoplay", on).apply() }

    fun toggle() { player?.let { if (it.isPlaying) it.pause() else it.play() } }
    fun next() { player?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
    fun previous() { player?.let { if (it.currentPosition > 3000 || !it.hasPreviousMediaItem()) it.seekTo(0) else it.seekToPreviousMediaItem() } }
    fun seek(ms: Long) { player?.seekTo(ms) }
    fun jump(i: Int) { player?.let { it.seekTo(i, 0); it.play() } }
    fun shuffleOn(on: Boolean) { player?.shuffleModeEnabled = on }
    fun cycleRepeat() { player?.let { it.repeatMode = when (it.repeatMode) {
        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL; Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE; else -> Player.REPEAT_MODE_OFF } } }

    /** Adds after the current song ("play next") or at the end ("add to queue"). */
    fun enqueue(c: Context, t: Track, next: Boolean) {
        val p = player
        if (p == null || queue.isEmpty()) { play(c, listOf(t)); return }
        val at = if (next) index + 1 else queue.size
        p.addMediaItem(at, item(t))
        queue = queue.toMutableList().apply { add(at, t) }
    }

    fun removeAt(i: Int) {
        val p = player ?: return
        if (i == index || i !in queue.indices) return
        p.removeMediaItem(i)
        queue = queue.toMutableList().apply { removeAt(i) }
        index = p.currentMediaItemIndex
    }

    /** Reflects an edit (tags, art) in the queue without interrupting playback. */
    fun refreshTrack(t: Track) {
        val i = queue.indexOfFirst { it.id == t.id }.takeIf { it >= 0 } ?: return
        queue = queue.toMutableList().apply { set(i, t) }
        player?.replaceMediaItem(i, item(t))
    }
}

class MusicService : Service() {
    private var session: MediaSession? = null
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundNow()                                    // within five seconds, always first
        when (intent?.action) {
            "toggle" -> MusicPlayback.toggle()
            "next" -> MusicPlayback.next()
            "prev" -> MusicPlayback.previous()
            "stop" -> { MusicPlayback.player?.pause(); stopForeground(STOP_FOREGROUND_DETACH); stopSelf(); return START_NOT_STICKY }
        }
        if (session == null) MusicPlayback.player?.let { session = MediaSession.Builder(this, it).setId("atlas-music").build() }
        return START_STICKY
    }

    private fun action(name: String, label: String, icon: Int, code: Int) = Notification.Action.Builder(
        android.graphics.drawable.Icon.createWithResource(this, icon), label,
        PendingIntent.getService(this, code, Intent(this, MusicService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Music", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        val t = MusicPlayback.current
        val playing = MusicPlayback.player?.isPlaying == true
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_OPEN_MUSIC).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(t?.title ?: "Music").setContentText(t?.artist ?: "")
            .setContentIntent(open)
            .addAction(action("prev", "Previous", android.R.drawable.ic_media_previous, 1))
            .addAction(action("toggle", if (playing) "Pause" else "Play",
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play, 2))
            .addAction(action("next", "Next", android.R.drawable.ic_media_next, 3))
            .setOngoing(playing)
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
        MusicArt.bitmap(this, t)?.let { b.setLargeIcon(it) }
        val n = b.build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(11, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(11, n)
    }

    override fun onDestroy() { session?.release(); session = null; super.onDestroy() }

    companion object {
        private const val CHANNEL = "music"
        fun start(c: Context) = runCatching { c.startForegroundService(Intent(c, MusicService::class.java)) }
        fun refresh(c: Context) { if (MusicPlayback.current != null) start(c) }
    }
}

/** The phone's equaliser on Atlas's music: its built-in presets (Rock, Pop, Bass boost…), remembered. */
object MusicFx {
    private var eq: android.media.audiofx.Equalizer? = null
    private var bass: android.media.audiofx.BassBoost? = null
    var preset: Int by mutableStateOf(-1)
        private set
    var bassOn: Boolean by mutableStateOf(false)
        private set

    fun attach(c: Context, session: Int) {
        if (session == 0) return
        runCatching { eq?.release(); bass?.release() }
        eq = runCatching { android.media.audiofx.Equalizer(0, session) }.getOrNull()
        bass = runCatching { android.media.audiofx.BassBoost(0, session) }.getOrNull()
        val p = c.getSharedPreferences("music", 0)
        use(c, p.getInt("eq", -1)); boost(c, p.getBoolean("bass", false))
    }
    fun presets(): List<String> = eq?.let { e -> runCatching { (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) } }.getOrNull() } ?: emptyList()
    fun use(c: Context, i: Int) {
        eq?.let { e -> runCatching { if (i < 0) e.enabled = false else { e.usePreset(i.toShort()); e.enabled = true } } }
        preset = i; c.getSharedPreferences("music", 0).edit().putInt("eq", i).apply()
    }
    fun boost(c: Context, on: Boolean) {
        bass?.let { b -> runCatching { if (b.strengthSupported) b.setStrength(if (on) 700 else 0); b.enabled = on } }
        bassOn = on; c.getSharedPreferences("music", 0).edit().putBoolean("bass", on).apply()
    }
}
