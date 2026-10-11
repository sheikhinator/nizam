package com.nizam.app.feature.cinema

import android.app.Activity
import android.content.pm.ActivityInfo
import android.net.Uri
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.AspectRatio
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nizam.app.ui.components.Feedback
import kotlinx.coroutines.delay

/**
 * Cinema's player. Every kind of source plays here: direct links, HLS/DASH, saved files, and
 * torrents (through Atlas's engine, with live peer/speed status while it buffers). Landscape and
 * immersive; double-tap either side to jump 10 s; speed and picture fit in the top bar; subtitles
 * from your addons; remembers where you stopped; "Next episode" for series.
 */
@Composable
fun PlayerScreen(onBack: () -> Unit = {}) {
    val c = LocalContext.current
    val magnet = remember { CinemaNav.playMagnet }
    var url by remember { mutableStateOf(if (magnet == null) CinemaNav.playUrl else "") }
    var torrent by remember { mutableStateOf<Torrents.Live?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf("Finding peers…") }

    Immersive()
    if (magnet != null) {
        LaunchedEffect(magnet) {
            runCatching { Torrents.open(c, magnet, CinemaNav.playFileIdx, keep = false) }
                .onSuccess { l -> l.streams++; torrent = l; url = TorrentServer.url(l) }
                .onFailure { failed = Feedback.friendly(it) }
        }
        LaunchedEffect(torrent) { while (true) { torrent?.let { status = it.status() }; delay(1000) } }
        DisposableEffect(Unit) { onDispose { torrent?.let { Torrents.release(it) } } }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            failed != null -> Message(failed!!, onBack)
            url.isBlank() -> Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(34.dp))
                Text(CinemaNav.playTitle, color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Text(status, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelMedium)
            }
            else -> VideoPlayer(url, torrent, { status }, onBack)
        }
    }
}

@Composable
private fun Immersive() {
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.let { w ->
            WindowCompat.getInsetsController(w, w.decorView).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        onDispose {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { w -> WindowCompat.getInsetsController(w, w.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
}

@Composable
private fun Message(text: String, onBack: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Text("Couldn't play this source", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Text(text, color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 6.dp))
            TextButton(onClick = onBack, modifier = Modifier.padding(top = 12.dp)) { Text("Choose another source") }
        }
    }
}

@Composable
private fun VideoPlayer(url: String, torrent: Torrents.Live?, torrentStatus: () -> String, onBack: () -> Unit) {
    val c = LocalContext.current
    val store = remember { CinemaStore(c) }
    val videoId = CinemaNav.playVideoId.ifBlank { url }
    val meta = CinemaNav.playMeta
    val next = remember(meta, videoId) {
        meta?.videos?.sortedWith(compareBy({ it.season }, { it.episode }))?.let { eps ->
            eps.indexOfFirst { it.id == videoId }.takeIf { it >= 0 }?.let { eps.getOrNull(it + 1) }
        }
    }
    var error by remember { mutableStateOf<String?>(null) }
    var controls by remember { mutableStateOf(true) }
    var buffering by remember { mutableStateOf(true) }
    var speed by remember { mutableFloatStateOf(1f) }
    var fit by remember { mutableIntStateOf(AspectRatioFrameLayout.RESIZE_MODE_FIT) }
    var view by remember { mutableStateOf<PlayerView?>(null) }

    val player = remember(url) {
        // one thing plays at a time
        com.nizam.app.feature.music.MusicPlayback.player?.pause()
        com.nizam.app.feature.podcasts.Playback.player?.pause()
        val subs = CinemaNav.playSubs.take(12).map { s ->
            MediaItem.SubtitleConfiguration.Builder(Uri.parse(s.url))
                .setMimeType(if (s.url.substringBefore('?').endsWith(".vtt")) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_SUBRIP)
                .setLanguage(s.lang).setLabel(s.lang)
                .setSelectionFlags(if (s.lang == "eng") C.SELECTION_FLAG_DEFAULT else 0).build()
        }
        val source = if (CinemaNav.playCached) VideoDownloads.cachedSource(c, CinemaNav.playHeaders)
            else DefaultDataSource.Factory(c, VideoDownloads.http(CinemaNav.playHeaders))
        ExoPlayer.Builder(c)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(10_000)
            .build().apply {
                setMediaItem(MediaItem.Builder().setUri(url).setSubtitleConfigurations(subs).build())
                prepare()
                (Library.progress(c, videoId).first.takeIf { it > 5_000 } ?: store.position(url).takeIf { it > 5_000 })?.let { seekTo(it) }
                playWhenReady = true
            }
    }
    fun saveProgressNow() {
        val pos = player.currentPosition
        store.savePosition(url, pos)
        if (player.duration > 0) Library.saveProgress(c, meta, videoId, CinemaNav.playTitle, pos, player.duration)
    }
    fun playNext() {
        next ?: return
        saveProgressNow()
        CinemaNav.pendingEpisode = next.id
        onBack()
    }

    val activity = remember(c) { var x: android.content.Context? = c
        while (x is android.content.ContextWrapper && x !is android.app.Activity) x = x.baseContext
        x as? android.app.Activity }
    LaunchedEffect(Pip.active) { view?.useController = !Pip.active; if (Pip.active) view?.hideController() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                Pip.arm(activity, playing, player.videoSize.width, player.videoSize.height)
            }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED && next != null) playNext()
            }
            override fun onPlayerError(e: PlaybackException) {
                error = when (e.errorCode) {
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                        "The source didn't answer. Check your connection, or pick another source."
                    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The source refused the request — the link may have expired. Pick another source."
                    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "The saved file is missing. Delete it from Downloads and save it again."
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED ->
                        "This phone can't decode this video's format. A different source (e.g. 1080p x264) usually works."
                    else -> "Playback stopped (${e.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ')}). Try another source."
                }
            }
        }
        player.addListener(listener)
        val observer = LifecycleEventObserver { _, ev -> if (ev == Lifecycle.Event.ON_STOP) { player.pause(); saveProgressNow() } }
        lifecycle.addObserver(observer)
        store.logWatched(CinemaNav.playTitle)
        onDispose {
            lifecycle.removeObserver(observer)
            player.removeListener(listener); Pip.arm(activity, false)
            saveProgressNow(); player.release()
        }
    }

    var outOfTime by remember { mutableStateOf(false) }
    LaunchedEffect(player) {
        var tick = 0
        while (true) {
            delay(10_000); saveProgressNow()
            if (Leisure.on(c) && player.isPlaying && ++tick % 6 == 0) {       // charge a minute every minute watched
                Leisure.spend(c, 1)
                if (Leisure.balance(c) <= 0) { player.pause(); outOfTime = true }
            }
        }
    }
    if (outOfTime) androidx.compose.material3.AlertDialog(onDismissRequest = { outOfTime = false },
        confirmButton = { TextButton(onClick = { outOfTime = false }) { Text("OK") } },
        title = { Text("Leisure time used up") },
        text = { Text("Each focus minute earns two minutes of film. A 25-minute focus session buys the next 50 minutes.") })

    if (error != null) { Message(error!!, onBack); return }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player; keepScreenOn = true
                setShowSubtitleButton(true); setShowNextButton(false); setShowPreviousButton(false)
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> controls = v == View.VISIBLE })
                view = this
            }
        }, update = { it.resizeMode = fit }, modifier = Modifier.fillMaxSize())

        // while the controls are hidden: tap shows them, double-tap a side skips 10 s
        if (!controls && !Pip.active) Box(Modifier.fillMaxSize().pointerInput(player) {
            detectTapGestures(
                onTap = { view?.showController() },
                onDoubleTap = { o -> if (o.x < size.width / 2) player.seekBack() else player.seekForward() })
        })

        AnimatedVisibility(controls && !Pip.active, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.TopCenter)) {
            Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent)))
                .padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White) }
                Text(CinemaNav.playTitle, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    speed = listOf(1f, 1.25f, 1.5f, 2f, 0.75f).let { l -> l[(l.indexOf(speed) + 1) % l.size] }
                    player.setPlaybackSpeed(speed)
                }) { Text(if (speed == 1f) "1×" else "${speed}×", color = Color.White) }
                IconButton(onClick = {
                    fit = when (fit) {
                        AspectRatioFrameLayout.RESIZE_MODE_FIT -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_FILL
                        else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                    Feedback.show(when (fit) { AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> "Zoom to fill"
                        AspectRatioFrameLayout.RESIZE_MODE_FILL -> "Stretch"; else -> "Fit" })
                }) { Icon(Icons.Outlined.AspectRatio, "Picture fit", tint = Color.White) }
                IconButton(onClick = {
                    Pip.arm(activity, true, player.videoSize.width, player.videoSize.height)
                    if (!Pip.enter(activity)) Feedback.show("Picture-in-picture is off for Atlas in system settings")
                }) { Icon(Icons.Outlined.PictureInPictureAlt, "Picture in picture", tint = Color.White) }
                if (next != null) TextButton(onClick = ::playNext) {
                    Icon(Icons.Filled.SkipNext, null, tint = Color.White)
                    Text(" Next episode", color = Color.White)
                }
            }
        }

        // torrent status while it fills its buffer
        if (torrent != null && buffering && !Pip.active) Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 90.dp)
            .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.6f)).padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.Center) {
            Text(torrentStatus(), color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}
