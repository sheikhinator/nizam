package com.nizam.app.feature.iptv

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import com.nizam.app.ui.design.Button
import androidx.compose.material3.MaterialTheme
import com.nizam.app.ui.design.OutlinedButton
import com.nizam.app.ui.design.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.nizam.app.core.ChipRow
import com.nizam.app.core.ModuleTitle
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.launch

@Composable
private fun Logo(url: String?, name: String, modifier: Modifier = Modifier) {
    Box(modifier.aspectRatio(16f / 10f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surface),
        contentAlignment = Alignment.Center) {
        if (url != null) AsyncImage(url, name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
        else Text(name.take(18), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(6.dp))
    }
}

@Composable
fun IptvScreen(onPlay: () -> Unit) {
    val c = LocalContext.current
    val scope = com.nizam.app.ui.design.rememberSafeScope()
    val store = remember { IptvStore(c) }
    var tab by remember { mutableStateOf("Channels") }
    var refresh by remember { mutableStateOf(0) }
    var group by remember { mutableStateOf("All") }
    var query by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    val playlists = remember(refresh) { store.playlists() }
    val all = remember(refresh) { store.allChannels() }
    val favourites = remember(refresh) { store.favourites() }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            runCatching {
                // big playlists (tens of thousands of channels) must not be read or parsed on the main thread
                val channels = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val text = c.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                        ?: throw IllegalStateException("The file couldn't be opened")
                    M3u.parse(text)
                }
                store.addPlaylist(name.ifBlank { "My playlist" }, uri.toString(), channels)
                status = "Added ${channels.size} channels"; refresh++
            }.onFailure { status = "Couldn't read that file: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
            busy = false
        }
    }

    fun openChannel(ch: Channel, list: List<Channel>) {
        IptvNav.channel = ch; IptvNav.list = list; store.markWatched(ch.url); refresh++; onPlay()
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            ModuleTitle("Live TV", "Your M3U playlists, channels and favourites")
            ChipRow(listOf("Channels", "Favourites", "Playlists"), tab, { tab = it })
        }
        Box(Modifier.weight(1f).padding(horizontal = 18.dp)) {
            when (tab) {
                "Playlists" -> LazyColumn {
                    item {
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Add a playlist", style = MaterialTheme.typography.titleMedium)
                                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                                OutlinedTextField(url, { url = it }, singleLine = true, label = { Text("M3U / M3U8 URL") }, modifier = Modifier.fillMaxWidth())
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                    Button(enabled = url.startsWith("http") && !busy, onClick = {
                                        scope.launch {
                                            busy = true
                                            runCatching {
                                                val channels = M3u.fetch(url.trim())
                                                if (channels.isEmpty()) throw IllegalStateException("No channels found in that playlist")
                                                store.addPlaylist(name.ifBlank { "Playlist ${playlists.size + 1}" }, url.trim(), channels)
                                                status = "Added ${channels.size} channels"; url = ""; name = ""; refresh++
                                            }.onFailure { status = "Couldn't load it: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
                                            busy = false
                                        }
                                    }) { Text(if (busy) "Loading…" else "Add from URL") }
                                    OutlinedButton(onClick = { filePicker.launch(arrayOf("*/*")) }) { Text("From file") }
                                }
                                status?.let { Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium) }
                                Text("Playlists are parsed once and cached, so they open instantly afterwards.",
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    items(playlists) { p ->
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                            Column(Modifier.padding(14.dp)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                Text("${p.channels} channels · ${p.source.take(48)}", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 6.dp)) {
                                    Text("refresh", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.clickable(role = Role.Button) {
                                            scope.launch {
                                                if (!p.source.startsWith("http")) { status = "File playlists can't refresh — add the file again."; return@launch }
                                                runCatching { store.addPlaylist(p.name, p.source, M3u.fetch(p.source)); refresh++; status = "Refreshed ${p.name}" }
                                                    .onFailure { status = "Refresh failed: ${com.nizam.app.ui.components.Feedback.friendly(it)}" }
                                            }
                                        })
                                    Text("remove", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium,
                                        modifier = Modifier.clickable(role = Role.Button) { store.remove(p); refresh++ })
                                }
                            }
                        }
                    }
                }
                else -> {
                    val base = if (tab == "Favourites") all.filter { it.url in favourites } else all
                    val groups = listOf("All") + base.map { it.group }.distinct().sorted()
                    val shown = base.filter { (group == "All" || it.group == group) && (query.isBlank() || it.name.contains(query, true)) }
                    LazyVerticalGrid(GridCells.Fixed(2), horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                if (all.isEmpty()) Text("No channels yet — add an M3U playlist under Playlists.",
                                    style = MaterialTheme.typography.bodyMedium)
                                else {
                                    OutlinedTextField(query, { query = it }, singleLine = true, label = { Text("Search channels") },
                                        modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                        keyboardActions = KeyboardActions())
                                    if (groups.size > 1) ChipRow(groups.take(30), group, { group = it })
                                    Text("${shown.size} channels", style = MonoNumber, modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                        items(shown.take(400), key = { it.url }) { ch ->
                            Column(Modifier.clickable(role = Role.Button) { openChannel(ch, shown) }) {
                                Box {
                                    Logo(ch.logo, ch.name, Modifier.fillMaxWidth())
                                    Text(if (ch.url in favourites) "★" else "☆", color = Color(0xFFE0B14A),
                                        modifier = Modifier.align(Alignment.TopEnd)
                                            .clickable(role = Role.Button) { store.toggleFavourite(ch.url); refresh++ }.padding(6.dp))
                                }
                                Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelMedium)
                                Text(ch.group, maxLines = 1, style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(50.dp)) }
                    }
                }
            }
        }
    }
}

/** Fullscreen channel player with a zapping list — HLS, DASH and plain streams. */
@Composable
fun IptvPlayerScreen() {
    val c = LocalContext.current
    val activity = c as? Activity
    val store = remember { IptvStore(c) }
    var channel by remember { mutableStateOf(IptvNav.channel) }
    var showList by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val player = remember {
        ExoPlayer.Builder(c).build().apply {
            addListener(object : androidx.media3.common.Player.Listener {
                override fun onPlayerError(e: androidx.media3.common.PlaybackException) {
                    error = "This stream didn't load (${e.errorCodeName}). It may be offline or geo-blocked."
                }
            })
        }
    }
    LaunchedEffectChannel(channel, player) { error = null }
    DisposableEffect(Unit) {
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        activity?.window?.let { w -> WindowCompat.getInsetsController(w, w.decorView).hide(WindowInsetsCompat.Type.systemBars()) }
        onDispose {
            player.release()
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            activity?.window?.let { w -> WindowCompat.getInsetsController(w, w.decorView).show(WindowInsetsCompat.Type.systemBars()) }
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { PlayerView(it).apply { this.player = player; keepScreenOn = true } }, modifier = Modifier.fillMaxSize())
        Row(Modifier.align(Alignment.TopStart).padding(12.dp)) {
            Text(channel?.name.orEmpty(), color = Color.White, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp))
            Spacer(Modifier.width(8.dp))
            Text("Channels", color = Color.White, style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(10.dp))
                    .clickable(role = Role.Button) { showList = !showList }.padding(horizontal = 10.dp, vertical = 8.dp))
        }
        error?.let {
            Text(it, color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center).background(Color(0xCC000000), RoundedCornerShape(12.dp)).padding(16.dp))
        }
        if (showList) {
            LazyColumn(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(260.dp).background(Color(0xE60B0F1A)).padding(10.dp)) {
                items(IptvNav.list.take(300), key = { it.url }) { ch ->
                    Row(Modifier.fillMaxWidth().clickable(role = Role.Button) {
                        channel = ch; IptvNav.channel = ch; store.markWatched(ch.url); showList = false
                    }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Logo(ch.logo, ch.name, Modifier.width(54.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(ch.name, color = if (ch.url == channel?.url) Color(0xFF4FC3A1) else Color.White,
                            style = MaterialTheme.typography.labelMedium, maxLines = 2)
                    }
                }
            }
        }
    }
}

/** Loads the chosen channel whenever it changes. */
@Composable
private fun LaunchedEffectChannel(channel: Channel?, player: ExoPlayer, onLoad: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(channel?.url) { com.nizam.app.ui.design.guard {
        val ch = channel ?: return@LaunchedEffect
        onLoad()
        player.setMediaItem(MediaItem.fromUri(ch.url))
        player.prepare(); player.playWhenReady = true
    } }
}
