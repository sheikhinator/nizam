@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.nizam.app.feature.podcasts

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.BookmarkAdd
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Forward30
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.PlaylistAdd
import androidx.compose.material.icons.outlined.Replay10
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.nizam.app.ui.design.Art3D
import com.nizam.app.ui.design.OutlinedTextField
import com.nizam.app.ui.design.liquidGlass
import com.nizam.app.ui.components.Feedback
import com.nizam.app.ui.theme.DisplayFamily
import com.nizam.app.ui.theme.MonoNumber
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/** Where the app hands a show to its detail screen. The show itself travels too, so its header paints instantly. */
object PodcastNav { var feed: String = ""; var title: String = ""; var show: Show? = null }

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0); return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}
private fun human(sec: Int): String = when { sec <= 0 -> ""; sec >= 3600 -> "${sec / 3600}h ${sec % 3600 / 60}m"; else -> "${sec / 60} min" }
private val RFC = SimpleDateFormat("EEE, dd MMM yyyy", Locale.US)
private fun dateOf(s: String): Long = runCatching { RFC.parse(s.take(16))?.time }.getOrNull() ?: 0L
private fun ago(s: String): String {
    val t = dateOf(s); if (t == 0L) return s.drop(5).take(11)
    val d = ((System.currentTimeMillis() - t) / 86_400_000L).toInt()
    return when { d <= 0 -> "Today"; d == 1 -> "Yesterday"; d < 7 -> "$d days ago"; d < 30 -> "${d / 7}w ago"; else -> s.drop(5).take(11) }
}

// ═════════════════════════════ building blocks ═════════════════════════════

@Composable
private fun brand(): Brush { val c = MaterialTheme.colorScheme; return Brush.linearGradient(listOf(c.primary, c.tertiary)) }

/** Cover art with a soft shadow; a gradient with a 3D mic when a show has no art. */
@Composable
private fun Cover(url: String?, title: String, modifier: Modifier = Modifier, corner: Dp = 14.dp, elevation: Dp = 6.dp) {
    val shape = RoundedCornerShape(corner)
    Box(modifier.aspectRatio(1f).shadow(elevation, shape, clip = false).clip(shape).background(brand()), contentAlignment = Alignment.Center) {
        Art3D("microphone", 44.dp, float = false, shadow = false)
        if (url != null) AsyncImage(url, title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun pressScale(src: MutableInteractionSource): Float {
    val pressed by src.collectIsPressedAsState()
    return animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = 0.45f, stiffness = 600f), label = "press").value
}

/** The play button: a gradient orb with a ring that shows how far you are, a breathing glow while playing. */
@Composable
fun PlayOrb(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 48.dp, progress: Float = 0f,
            buffering: Boolean = false) {
    val c = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val glow = if (playing) rememberInfiniteTransition(label = "glow").animateFloat(0.25f, 0.6f,
        infiniteRepeatable(tween(1100), RepeatMode.Reverse), label = "g").value else 0f
    Box(modifier.size(size).scale(pressScale(src)).clickable(src, null, role = Role.Button) { com.nizam.app.ui.design.UiSound.tick(); onClick() },
        contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            if (glow > 0f) drawCircle(Brush.radialGradient(listOf(c.primary.copy(alpha = glow), Color.Transparent)), radius = this.size.minDimension * 0.62f)
            val stroke = this.size.minDimension * 0.07f
            if (progress > 0.01f) {
                drawArc(c.onSurface.copy(alpha = 0.12f), 0f, 360f, false, style = Stroke(stroke))
                drawArc(c.tertiary, -90f, 360f * progress.coerceIn(0f, 1f), false, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }
        Box(Modifier.fillMaxSize(if (progress > 0.01f) 0.78f else 1f).shadow(if (playing) 10.dp else 4.dp, CircleShape,
            ambientColor = c.primary, spotColor = c.primary).clip(CircleShape).background(brand()), contentAlignment = Alignment.Center) {
            if (buffering) CircularProgressIndicator(Modifier.size(size * 0.42f), color = Color.White, strokeWidth = 2.dp)
            else Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play",
                tint = Color.White, modifier = Modifier.size(size * 0.5f))
        }
    }
}

/** Download: an outlined circle, then a filling ring with the percentage, then a green tick (hold the tick to delete). */
@Composable
fun DownloadOrb(ep: Episode, store: PodcastStore, modifier: Modifier = Modifier, size: Dp = 40.dp, onChanged: () -> Unit = {}) {
    val c = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val v = PodcastDl.version
    val pct = PodcastDl.pct[ep.id]
    val done = remember(ep.id, v, pct) { store.downloaded(ep) != null }
    val src = remember { MutableInteractionSource() }
    val ring by animateFloatAsState(((pct ?: 0).coerceAtLeast(0)) / 100f, tween(300), label = "dl")
    Box(modifier.size(size).scale(pressScale(src)).clip(CircleShape)
        .background(if (done) Color(0xFF22C55E).copy(alpha = 0.16f) else c.onSurface.copy(alpha = 0.06f))
        .clickable(src, null, role = Role.Button) {
            com.nizam.app.ui.design.UiSound.tick()
            when {
                pct != null -> Feedback.show("Downloading… ${if (pct >= 0) "$pct%" else ""}")
                done -> Feedback.show("Saved for offline", "Delete") { store.downloaded(ep)?.delete(); store.forgetDownload(ep.id); PodcastDl.version++; onChanged() }
                else -> { Feedback.show("Downloading ${ep.title.take(30)}"); scope.launch { runCatching { store.download(ep) }
                    .onFailure { Feedback.failed("Download", it) }; onChanged() } }
            }
        }, contentAlignment = Alignment.Center) {
        if (pct != null) {
            Canvas(Modifier.fillMaxSize().padding(3.dp)) {
                drawArc(c.onSurface.copy(alpha = 0.12f), 0f, 360f, false, style = Stroke(3.dp.toPx()))
                drawArc(c.primary, -90f, if (pct < 0) 40f else 360f * ring, false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
            Text(if (pct < 0) "…" else "$pct", fontSize = (size.value * 0.26f).sp, fontWeight = FontWeight.Bold, color = c.primary)
        } else Icon(if (done) Icons.Filled.CheckCircle else Icons.Outlined.Download, if (done) "Downloaded" else "Download",
            tint = if (done) Color(0xFF22C55E) else c.onSurface, modifier = Modifier.size(size * 0.52f))
    }
}

/** A round tonal icon button with a springy press. */
@Composable
private fun RoundIcon(icon: ImageVector, desc: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 40.dp,
                      active: Boolean = false, tint: Color? = null) {
    val c = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val bg by animateColorAsState(if (active) c.primary.copy(alpha = 0.18f) else c.onSurface.copy(alpha = 0.06f), label = "bg")
    Box(modifier.size(size).scale(pressScale(src)).clip(CircleShape).background(bg)
        .clickable(src, null, role = Role.Button) { com.nizam.app.ui.design.UiSound.tick(); onClick() }, contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = tint ?: if (active) c.primary else c.onSurface, modifier = Modifier.size(size * 0.5f))
    }
}

/** A pill with an icon and a label; filled with the brand gradient when on. */
@Composable
private fun PillButton(text: String, icon: ImageVector?, on: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(50)
    Row(modifier.scale(pressScale(src)).clip(shape)
        .then(if (on) Modifier.background(brand()) else Modifier.background(c.onSurface.copy(alpha = 0.06f)).border(0.8.dp, c.outline.copy(alpha = 0.25f), shape))
        .clickable(src, null, role = Role.Button) { com.nizam.app.ui.design.UiSound.tick(); onClick() }
        .padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Icon(icon, null, tint = if (on) Color.White else c.onSurface, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (on) Color.White else c.onSurface, maxLines = 1)
    }
}

/** Scrollable segmented tabs with a gradient pill under the chosen one. */
@Composable
private fun SegTabs(tabs: List<String>, sel: String, onSel: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tabs.forEach { t -> PillButton(t, null, on = t == sel) { onSel(t) } }
    }
}

@Composable
private fun SectionHead(title: String, sub: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.SemiBold))
            if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) Text(action, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button) { onAction() }.padding(6.dp))
    }
}

/** Shimmering placeholders so a list never shows a bare "Loading…". */
@Composable
private fun SkeletonTiles(n: Int = 4) {
    val a = rememberInfiniteTransition(label = "sk").animateFloat(0.05f, 0.14f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a").value
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(n) { Column(Modifier.width(136.dp)) {
            Box(Modifier.size(136.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = a)))
            Box(Modifier.padding(top = 8.dp).width(110.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = a)))
        } }
    }
}

@Composable
private fun SkeletonRows(n: Int = 5) {
    val a = rememberInfiniteTransition(label = "sk").animateFloat(0.05f, 0.14f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "a").value
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(n) { Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(60.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = a)))
            Column(Modifier.padding(start = 12.dp)) {
                Box(Modifier.width(200.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = a)))
                Box(Modifier.padding(top = 8.dp).width(120.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = a)))
            }
        } }
    }
}

@Composable
private fun ShowTile(s: Show, onClick: () -> Unit, rank: Int? = null) {
    val src = remember { MutableInteractionSource() }
    Column(Modifier.width(136.dp).scale(pressScale(src)).clickable(src, null, role = Role.Button) { onClick() }) {
        Box {
            Cover(s.image, s.title, Modifier.size(136.dp), corner = 18.dp, elevation = 8.dp)
            if (rank != null) Text("$rank", color = Color.White, fontWeight = FontWeight.Black, fontSize = 13.sp,
                modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f)).padding(horizontal = 8.dp, vertical = 2.dp))
        }
        Text(s.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(s.author, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ShowRow(s: Show, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button) { onClick() }.padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Cover(s.image, s.title, Modifier.size(64.dp), corner = 14.dp)
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(s.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOf(s.author, if (s.episodeCount > 0) "${s.episodeCount} eps" else "").filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (s.sources.isNotBlank()) Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                s.sources.split(" · ").take(4).forEach { Text(it, fontSize = 10.sp, color = c.primary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(c.primary.copy(alpha = 0.10f)).padding(horizontal = 7.dp, vertical = 1.dp)) }
            }
        }
    }
}

/** One episode, the way every list shows it: art, title, when and how long, a progress line and the three main buttons. */
@Composable
private fun EpisodeCard(ep: Episode, store: PodcastStore, refresh: Int, onChange: () -> Unit, showArt: Boolean = true,
                        trailing: (@Composable () -> Unit)? = null) {
    val c = MaterialTheme.colorScheme
    val c2 = LocalContext.current
    val pos = remember(ep.id, refresh, Playback.current) { store.position(ep.id) }
    val dur = remember(ep.id, refresh) { store.duration(ep.id) }.takeIf { it > 0 } ?: ep.durationSec * 1000L
    val played = remember(ep.id, refresh) { store.played(ep.id) }
    val starred = remember(ep.id, refresh) { ep.id in store.starred() }
    val isCurrent = Playback.current?.id == ep.id
    var open by remember { mutableStateOf(false) }
    val livePos = if (isCurrent) Playback.positionMs else pos
    val progress = if (dur > 0) (livePos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(20.dp))
        .background(if (isCurrent) c.primary.copy(alpha = 0.08f) else c.onSurface.copy(alpha = 0.035f))
        .clickable(role = Role.Button) { open = !open }.padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (showArt) { Cover(ep.image, ep.show, Modifier.size(56.dp), corner = 12.dp, elevation = 3.dp); Spacer(Modifier.width(12.dp)) }
            Column(Modifier.weight(1f)) {
                Text(listOf(ago(ep.published), human(ep.durationSec)).filter { it.isNotBlank() }.joinToString(" · ").uppercase(),
                    style = MaterialTheme.typography.labelSmall, color = c.primary, maxLines = 1)
                Text(ep.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), maxLines = 2,
                    overflow = TextOverflow.Ellipsis, color = if (played) c.onSurfaceVariant else c.onSurface)
                if (showArt && ep.show.isNotBlank()) Text(ep.show, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.width(8.dp))
            PlayOrb(isCurrent && Playback.isPlaying, onClick = {
                if (isCurrent) Playback.toggle() else { Playback.start(c2, ep, store); onChange() }
            }, size = 46.dp, progress = if (played) 0f else progress, buffering = isCurrent && Playback.buffering && !Playback.isPlaying)
        }
        if (progress > 0.01f && !played) Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.onSurface.copy(alpha = 0.08f))) {
            Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(brand()))
        }
        Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DownloadOrb(ep, store, size = 36.dp, onChanged = onChange)
            RoundIcon(Icons.Outlined.PlaylistAdd, "Add to Up Next", { store.enqueue(ep, next = true); Feedback.show("Playing next"); onChange() }, size = 36.dp)
            RoundIcon(if (starred) Icons.Filled.Star else Icons.Outlined.StarBorder, "Star", { store.star(ep.id); onChange() }, size = 36.dp,
                active = starred, tint = if (starred) Color(0xFFF5B301) else null)
            RoundIcon(Icons.Outlined.Share, "Share", {
                c2.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_TEXT, "${ep.title} — ${ep.show}\n${ep.audio}"), "Share episode"))
            }, size = 36.dp)
            Spacer(Modifier.weight(1f))
            if (played) Text("PLAYED", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
            else if (pos > 0 && dur > 0) Text("${clock(dur - livePos)} left", style = MonoNumber, color = c.onSurfaceVariant)
            trailing?.invoke()
        }
        AnimatedVisibility(open && ep.description.isNotBlank()) {
            Text(ep.description.take(900), style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
        }
    }
}

// ═════════════════════════════ main screen ═════════════════════════════

@Composable
fun PodcastsScreen(onShow: (String, String) -> Unit, onOpenPlayer: () -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PodcastStore(c) }
    remember { PodcastCache.init(c); 0 }
    var tab by remember { mutableStateOf("Listen") }
    var refresh by remember { mutableStateOf(0) }
    val open: (Show) -> Unit = { s -> PodcastNav.show = s; onShow(s.feed, s.title) }
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            // ── hero ──
            Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)) {
                Column {
                    Text("Podcasts", style = MaterialTheme.typography.headlineMedium.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold))
                    val (listened, saved) = remember(refresh) { store.stats() }
                    Text(if (listened > 0) "${listened / 3600}h ${listened % 3600 / 60}m listened · ${saved / 60} min saved by speed"
                        else "Four directories, one search. Plays with the screen off.",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Art3D("headphone", 64.dp, Modifier.align(Alignment.CenterEnd))
            }
            Box(Modifier.padding(horizontal = 18.dp)) { SegTabs(listOf("Listen", "Discover", "Library", "Up Next", "Downloads"), tab) { tab = it } }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    "Listen" -> ListenTab(store, refresh, { refresh++ }, open) { tab = "Discover" }
                    "Discover" -> DiscoverTab(open)
                    "Library" -> LibraryTab(store, refresh, open)
                    "Up Next" -> QueueTab(store, refresh) { refresh++ }
                    else -> DownloadsTab(store, refresh) { refresh++ }
                }
            }
        }
        MiniPlayer(onOpenPlayer, Modifier.align(Alignment.BottomCenter))
    }
}

private val BOTTOM = PaddingValues(start = 18.dp, end = 18.dp, bottom = 110.dp)

@Composable
private fun ListenTab(store: PodcastStore, refresh: Int, onChange: () -> Unit, open: (Show) -> Unit, goDiscover: () -> Unit) {
    val c = LocalContext.current
    val inProgress = remember(refresh) { store.inProgress() }
    val shows = remember(refresh) { store.shows() }
    // new episodes from subscriptions: cached parses first (instant), then a parallel refresh
    var fresh by remember { mutableStateOf<List<Episode>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    LaunchedEffect(shows.map { it.feed }) {
        fun newest(l: List<Pair<Show, List<Episode>>>) = l.flatMap { it.second.take(6) }.sortedByDescending { dateOf(it.published) }.take(30)
        val cached = withContext(Dispatchers.Default) { shows.mapNotNull { Rss.cached(it.feed) } }
        if (cached.isNotEmpty()) fresh = newest(cached).distinctBy { it.id }
        if (shows.isNotEmpty()) { loading = true; fresh = newest(Rss.loadMany(shows.map { it.feed })).distinctBy { it.id }; loading = false }
    }
    LazyColumn(contentPadding = BOTTOM) {
        if (inProgress.isNotEmpty()) {
            item { SectionHead("Continue listening", "${inProgress.size} on the go") }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(inProgress.take(12)) { p ->
                        val ep = Episode(p.optString("id"), p.optString("feed"), p.optString("show"), p.optString("title"),
                            p.optString("audio"), "", (p.optLong("dur") / 1000).toInt(), "", p.optString("image").ifBlank { null })
                        ContinueCard(ep, p.optLong("pos"), p.optLong("dur")) { if (Playback.current?.id == ep.id) Playback.toggle() else { Playback.start(c, ep, store); onChange() } }
                    }
                }
            }
        }
        if (shows.isEmpty() && inProgress.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(top = 30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Art3D("microphone", 120.dp)
                Text("Nothing here yet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 10.dp))
                Text("Follow a few shows and their new episodes land here.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                PillButton("Find shows", Icons.Outlined.Search, on = true, modifier = Modifier.padding(top = 14.dp)) { goDiscover() }
            }
        }
        if (shows.isNotEmpty()) {
            item { SectionHead("Your shows") }
            item { LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(shows) { s ->
                Column(Modifier.width(76.dp).clickable(role = Role.Button) { open(s) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Cover(s.image, s.title, Modifier.size(72.dp), corner = 36.dp, elevation = 4.dp)
                    Text(s.title, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                } } } }
            item { SectionHead("New episodes", if (loading) "checking your shows…" else "from ${shows.size} shows") }
            if (fresh.isEmpty() && loading) item { SkeletonRows() }
            items(fresh, key = { it.id }) { ep -> EpisodeCard(ep, store, refresh, onChange) }
        }
    }
}

@Composable
private fun ContinueCard(ep: Episode, pos: Long, dur: Long, onPlay: () -> Unit) {
    val playing = Playback.current?.id == ep.id && Playback.isPlaying
    Box(Modifier.width(260.dp).height(150.dp).shadow(8.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp))) {
        AsyncImage(ep.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(24.dp))
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.25f), Color.Black.copy(alpha = 0.78f)))))
        Row(Modifier.fillMaxSize().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(ep.image, ep.show, Modifier.size(78.dp), corner = 14.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(ep.show.uppercase(), color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp, maxLines = 1)
                Text(ep.title, color = Color.White, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text("${clock(dur - pos)} left", color = Color.White.copy(alpha = 0.8f), style = MonoNumber, modifier = Modifier.padding(top = 4.dp))
            }
            PlayOrb(playing, onPlay, size = 48.dp, progress = (pos.toFloat() / dur.coerceAtLeast(1)))
        }
    }
}

@Composable
private fun DiscoverTab(open: (Show) -> Unit) {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Show>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var country by remember { mutableStateOf(PodcastSources.country(c)) }
    var genre by remember { mutableStateOf(PodcastDirectory.GENRES.first()) }
    var top by remember { mutableStateOf<List<Show>?>(null) }
    var genreTop by remember { mutableStateOf<List<Show>?>(null) }
    var community by remember { mutableStateOf<List<Show>?>(null) }
    var showSources by remember { mutableStateOf(false) }
    LaunchedEffect(country) {
        top = null; top = PodcastDirectory.chart(c, country, null, 30)
        // warm the first few feeds so opening them is instant
        top?.take(4)?.map { it.feed }?.let { f -> launch { Rss.loadMany(f, 12 * 3_600_000L) } }
    }
    LaunchedEffect(country, genre) { genreTop = null; genreTop = PodcastDirectory.chart(c, country, genre.second, 25) }
    LaunchedEffect(Unit) { community = PodcastDirectory.communityTop(c, 25) }
    fun run(q: String) {
        if (q.isBlank()) { results = null; return }
        job?.cancel(); searching = true; results = emptyList(); focus.clearFocus()
        job = scope.launch { PodcastDirectory.searchFlow(c, q.trim()).collect { results = it }; searching = false }
    }
    LazyColumn(contentPadding = BOTTOM) {
        item {
            val cs = MaterialTheme.colorScheme
            Row(Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(26.dp)).background(cs.onSurface.copy(alpha = 0.06f))
                .border(0.8.dp, cs.outline.copy(alpha = 0.2f), RoundedCornerShape(26.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Search, null, tint = cs.primary, modifier = Modifier.size(22.dp)); Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) Text("Shows, topics, people…", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    BasicTextField(query, { query = it; if (it.isBlank()) results = null }, singleLine = true,
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = cs.onSurface), cursorBrush = SolidColor(cs.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { run(query) }),
                        modifier = Modifier.fillMaxWidth())
                }
                if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                else if (query.isNotEmpty()) Icon(Icons.Outlined.Close, "Clear", Modifier.size(20.dp).clickable { query = ""; results = null; job?.cancel() })
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.Tune, "Sources", Modifier.size(20.dp).clickable { showSources = !showSources }, tint = cs.onSurfaceVariant)
            }
        }
        if (showSources) item { SourcesCard(country, { country = it; PodcastSources.setCountry(c, it) }) }
        val r = results
        if (r != null) {
            item { SectionHead(if (searching && r.isEmpty()) "Searching 4 directories…" else "${r.size} shows",
                if (searching) "results stream in as each source answers" else "Apple · fyyd · gpodder" + if (PodcastSources.indexKeys(c) != null) " · Podcast Index" else "") }
            if (r.isEmpty() && searching) item { SkeletonRows() }
            if (r.isEmpty() && !searching) item { Text("Nothing found. Try fewer words.", style = MaterialTheme.typography.bodyMedium) }
            items(r.distinctBy { it.feed }, key = { it.feed }) { s -> ShowRow(s) { open(s) } }
        } else {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Islam", "Urdu", "Pakistan", "Tech", "Startups", "Self improvement", "Cricket", "History", "Quran").forEach { q ->
                        PillButton(q, null) { query = q; run(q) }
                    }
                }
            }
            item { SectionHead("Top in ${COUNTRIES.firstOrNull { it.first == country }?.second ?: country.uppercase()}", "Apple chart · refreshed every 6 hours") }
            item { val t = top
                if (t == null) SkeletonTiles() else if (t.isEmpty()) Text("Chart unavailable offline.", style = MaterialTheme.typography.bodyMedium)
                else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { itemsIndexed(t) { i, s -> ShowTile(s, { open(s) }, rank = i + 1) } } }
            item { SectionHead("Browse by genre") }
            item { Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PodcastDirectory.GENRES.forEach { g -> PillButton(g.first, null, on = g == genre) { genre = g } } } }
            item { Spacer(Modifier.height(10.dp)); val g = genreTop
                if (g == null) SkeletonTiles() else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(g) { s -> ShowTile(s, { open(s) }) } } }
            item { SectionHead("Community favourites", "What gpodder listeners subscribe to") }
            item { val g = community
                if (g == null) SkeletonTiles() else if (g.isEmpty()) Text("Unavailable right now.", style = MaterialTheme.typography.bodyMedium)
                else LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) { items(g) { s -> ShowTile(s, { open(s) }) } } }
        }
    }
}

private val COUNTRIES = listOf("pk" to "Pakistan", "us" to "the US", "gb" to "the UK", "in" to "India", "ae" to "the UAE", "sa" to "Saudi Arabia",
    "ca" to "Canada", "au" to "Australia", "tr" to "Türkiye", "my" to "Malaysia")

@Composable
private fun SourcesCard(country: String, onCountry: (String) -> Unit) {
    val c = LocalContext.current
    var key by remember { mutableStateOf(PodcastSources.indexKeys(c)?.first ?: "") }
    var secret by remember { mutableStateOf(PodcastSources.indexKeys(c)?.second ?: "") }
    Column(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).padding(14.dp)) {
        Text("Chart country", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            COUNTRIES.forEach { (k, _) -> PillButton(k.uppercase(), Icons.Outlined.Language.takeIf { k == country }, on = k == country) { onCountry(k) } }
        }
        Text("Podcast Index (optional, free key from podcastindex.org). Adds a fourth source; the key stays on this phone.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
        OutlinedTextField(key, { key = it }, singleLine = true, label = { Text("API key") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(secret, { secret = it }, singleLine = true, label = { Text("API secret") }, modifier = Modifier.fillMaxWidth())
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PillButton("Save", Icons.Outlined.Check, on = true) { PodcastSources.setIndexKeys(c, key, secret); Feedback.show("Sources saved") }
            PillButton("Clear cache · ${PodcastCache.size() / 1024} KB", Icons.Outlined.DeleteOutline) { PodcastCache.clear(); Feedback.show("Cache cleared") }
        }
    }
}

@Composable
private fun LibraryTab(store: PodcastStore, refresh: Int, open: (Show) -> Unit) {
    val shows = remember(refresh) { store.shows() }
    val marks = remember(refresh) { store.allBookmarks() }
    LazyColumn(contentPadding = BOTTOM) {
        item { SectionHead("Following", "${shows.size} shows") }
        if (shows.isEmpty()) item { Text("Follow shows from Discover and they live here.", style = MaterialTheme.typography.bodyMedium) }
        items(shows.chunked(3)) { row ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { s -> Column(Modifier.weight(1f).clickable(role = Role.Button) { open(s) }) {
                    Cover(s.image, s.title, Modifier.fillMaxWidth(), corner = 18.dp, elevation = 6.dp)
                    Text(s.title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                } }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
        if (marks.isNotEmpty()) {
            item { SectionHead("Bookmarks", "${marks.size} saved moments") }
            items(marks.take(40)) { (_, at, note) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(clock(at), style = MonoNumber, color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)).padding(horizontal = 10.dp, vertical = 3.dp))
                    Spacer(Modifier.width(10.dp)); Text(note.ifBlank { "bookmark" }, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun QueueTab(store: PodcastStore, refresh: Int, onChange: () -> Unit) {
    var queue by remember(refresh) { mutableStateOf(store.queue()) }
    val c = LocalContext.current
    LazyColumn(contentPadding = BOTTOM) {
        item { SectionHead("Up Next", if (queue.isEmpty()) null else "${queue.size} episodes · ${human(queue.sumOf { it.durationSec })}",
            if (queue.isNotEmpty()) "Play all" else null) { queue.firstOrNull()?.let { Playback.start(c, it, store); onChange() } } }
        if (queue.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Art3D("clipboard", 100.dp)
                Text("Your queue is empty. Tap + on any episode.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
        }
        itemsIndexed(queue.distinctBy { it.id }, key = { _, e -> e.id }) { i, ep ->
            EpisodeCard(ep, store, refresh, onChange) {
                RoundIcon(Icons.Outlined.KeyboardArrowUp, "Move up", { if (i > 0) { val m = queue.toMutableList(); m.add(i - 1, m.removeAt(i)); queue = m; store.setQueue(m) } }, size = 32.dp)
                RoundIcon(Icons.Outlined.Close, "Remove", { queue = queue - ep; store.setQueue(queue) }, size = 32.dp)
            }
        }
    }
}

@Composable
private fun DownloadsTab(store: PodcastStore, refresh: Int, onChange: () -> Unit) {
    val v = PodcastDl.version
    val eps = remember(refresh, v) { store.downloadedEpisodes() }
    val files = remember(refresh, v) { store.downloads() }
    val active = PodcastDl.pct.keys.toList()
    LazyColumn(contentPadding = BOTTOM) {
        item { SectionHead("Downloads", "${files.size} episodes · ${files.sumOf { it.length() } / 1_048_576} MB on this phone" +
            if (active.isNotEmpty()) " · ${active.size} downloading" else "") }
        if (files.isEmpty() && active.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(top = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Art3D("inbox_tray", 100.dp)
                Text("Downloaded episodes play offline, even on a plane.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
            }
        }
        items(eps.distinctBy { it.id }, key = { it.id }) { ep -> EpisodeCard(ep, store, refresh, onChange) }
        val known = eps.mapNotNull { store.downloaded(it)?.name }.toSet()
        val orphans = files.filter { it.name !in known && it.name.endsWith(".mp3") }
        if (orphans.isNotEmpty()) item { SectionHead("Older downloads", "saved before titles were kept") }
        items(orphans) { f ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(f.name, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, maxLines = 1)
                RoundIcon(Icons.Outlined.DeleteOutline, "Delete", { f.delete(); onChange() }, size = 34.dp, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

// ═════════════════════════════ mini player ═════════════════════════════

/** The glass bar that follows you around the podcast screens. Reads playback state; does no work itself. */
@Composable
fun NowPlaying(onOpenPlayer: () -> Unit = {}) = MiniPlayer(onOpenPlayer, Modifier)

@Composable
private fun MiniPlayer(onOpenPlayer: () -> Unit, modifier: Modifier) {
    val ep = Playback.current
    AnimatedVisibility(ep != null, modifier, enter = fadeIn(), exit = fadeOut()) {
        if (ep == null) return@AnimatedVisibility
        val pos = Playback.positionMs
        val dur = if (Playback.durationMs > 0) Playback.durationMs else ep.durationSec * 1000L
        val shape = RoundedCornerShape(24.dp)
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).fillMaxWidth().shadow(14.dp, shape).liquidGlass(shape)
            .clickable(role = Role.Button) { onOpenPlayer() }) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Cover(ep.image, ep.show, Modifier.size(48.dp), corner = 12.dp, elevation = 2.dp); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(ep.title, maxLines = 1, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold), modifier = Modifier.basicMarquee())
                    Text(if (Playback.buffering) "buffering…" else "${ep.show.take(22)} · ${clock(dur - pos)} left", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                RoundIcon(Icons.Outlined.Replay10, "Back 15 seconds", { Playback.skip(-15) }, size = 38.dp)
                Spacer(Modifier.width(6.dp))
                PlayOrb(Playback.isPlaying, { Playback.toggle() }, size = 46.dp, buffering = Playback.buffering && !Playback.isPlaying)
                Spacer(Modifier.width(6.dp))
                RoundIcon(Icons.Outlined.Forward30, "Forward 30 seconds", { Playback.skip(30) }, size = 38.dp)
            }
            Box(Modifier.fillMaxWidth().height(3.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))) {
                Box(Modifier.fillMaxWidth((pos.toFloat() / dur.coerceAtLeast(1)).coerceIn(0f, 1f)).fillMaxHeight().background(brand()))
            }
        }
    }
}

// ═════════════════════════════ full player ═════════════════════════════

/** The full player, on its own screen so nothing is clipped by the tab bar. */
@Composable
fun PodcastPlayerScreen() {
    val c = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { PodcastStore(c) }
    val ep = Playback.current
    if (ep == null) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Art3D("headphone", 120.dp); Text("Nothing playing", style = MaterialTheme.typography.titleMedium) }
        return
    }
    val cs = MaterialTheme.colorScheme
    val pos = Playback.positionMs
    val dur = if (Playback.durationMs > 0) Playback.durationMs else ep.durationSec * 1000L
    var note by remember(ep.id) { mutableStateOf("") }
    var marks by remember(ep.id) { mutableStateOf(store.bookmarks(ep.id)) }
    var summary by remember(ep.id) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var scrub by remember { mutableStateOf<Float?>(null) }
    var panel by remember { mutableStateOf<String?>(null) }
    val artScale by animateFloatAsState(if (Playback.isPlaying) 1f else 0.86f, spring(dampingRatio = 0.55f, stiffness = 180f), label = "art")

    Box(Modifier.fillMaxSize()) {
        // blurred cover as a backdrop, fading to the page colour
        AsyncImage(ep.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().height(520.dp).blur(60.dp))
        Box(Modifier.fillMaxWidth().height(520.dp).background(Brush.verticalGradient(listOf(cs.background.copy(alpha = 0.35f), cs.background))))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp)) {
            Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 2.sp), color = cs.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 8.dp))
            Spacer(Modifier.height(18.dp))
            Cover(ep.image, ep.show, Modifier.fillMaxWidth(0.8f).align(Alignment.CenterHorizontally).scale(artScale), corner = 28.dp, elevation = 24.dp)
            Spacer(Modifier.height(22.dp))
            Text(ep.title, style = MaterialTheme.typography.titleLarge.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(ep.show, style = MaterialTheme.typography.bodyMedium, color = cs.primary, modifier = Modifier.padding(top = 2.dp))
            Slider(value = scrub ?: pos.toFloat(), valueRange = 0f..dur.coerceAtLeast(1L).toFloat(),
                onValueChange = { scrub = it }, onValueChangeFinished = { scrub?.let { Playback.seekTo(it.toLong()) }; scrub = null },
                colors = SliderDefaults.colors(thumbColor = cs.primary, activeTrackColor = cs.primary, inactiveTrackColor = cs.onSurface.copy(alpha = 0.12f)),
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(clock((scrub ?: pos.toFloat()).toLong()), style = MonoNumber, color = cs.onSurfaceVariant)
                Text("-${clock(dur - (scrub ?: pos.toFloat()).toLong())}", style = MonoNumber, color = cs.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                val speeds = listOf(0.8f, 1f, 1.2f, 1.5f, 1.8f, 2f, 2.5f)
                PillButton("${"%.1f".format(Playback.speed).removeSuffix(".0")}×", null, on = Playback.speed != 1f) {
                    val next = speeds[(speeds.indexOfFirst { it >= Playback.speed - 0.01f } + 1) % speeds.size]
                    Playback.applySpeed(next); store.setShowSpeed(ep.feed, next)
                }
                RoundIcon(Icons.Outlined.Replay10, "Back 15 seconds", { Playback.skip(-15) }, size = 54.dp)
                PlayOrb(Playback.isPlaying, { Playback.toggle() }, size = 84.dp, buffering = Playback.buffering && !Playback.isPlaying)
                RoundIcon(Icons.Outlined.Forward30, "Forward 30 seconds", { Playback.skip(30) }, size = 54.dp)
                RoundIcon(Icons.Outlined.Bedtime, "Sleep timer", { panel = if (panel == "sleep") null else "sleep" }, size = 44.dp,
                    active = Playback.sleepMinutesLeft > 0)
            }
            if (Playback.sleepMinutesLeft > 0) Text("Sleeping in ${Playback.sleepMinutesLeft} min", style = MaterialTheme.typography.labelMedium,
                color = cs.primary, modifier = Modifier.align(Alignment.CenterHorizontally))
            AnimatedVisibility(panel == "sleep") {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("0", "5", "15", "30", "45", "60", "end").forEach { v ->
                        PillButton(if (v == "0") "Off" else if (v == "end") "End of episode" else "$v min", null) {
                            if (v == "end") { Playback.sleepAtEpisodeEnd(scope); Feedback.show("Will pause at the end of this episode") }
                            else { Playback.sleep(v.toInt(), scope); Feedback.show(if (v == "0") "Sleep timer off" else "Sleeping in $v min") }
                            panel = null
                        }
                    }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PillButton("Trim silence", Icons.Outlined.ContentCut, on = Playback.trimSilence) { Playback.setTrim(!Playback.trimSilence) }
                PillButton("Boost", Icons.Outlined.VolumeUp, on = Playback.boost) { Playback.applyBoost(!Playback.boost) }
                PillButton("Bookmark", Icons.Outlined.BookmarkAdd, on = panel == "mark") { panel = if (panel == "mark") null else "mark" }
                PillButton("Ask AI", Icons.Outlined.AutoAwesome, on = panel == "ai") { panel = if (panel == "ai") null else "ai" }
                PillButton("Share", Icons.Outlined.Share) {
                    c.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_TEXT, "${ep.title} — ${ep.show} (at ${clock(pos)})\n${ep.audio}"), "Share"))
                }
            }
            AnimatedVisibility(panel == "mark") {
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(note, { note = it }, singleLine = true, label = { Text("Note for ${clock(pos)}") }, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    PillButton("Save", Icons.Filled.Star, on = true) {
                        store.addBookmark(ep.id, pos, note); marks = store.bookmarks(ep.id); note = ""; Feedback.show("Bookmarked at ${clock(pos)}")
                    }
                }
            }
            if (marks.isNotEmpty()) {
                SectionHead("Bookmarks")
                marks.forEach { (at, text) ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { Playback.seekTo(at) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(clock(at), style = MonoNumber, color = cs.primary, modifier = Modifier.clip(RoundedCornerShape(50))
                            .background(cs.primary.copy(alpha = 0.1f)).padding(horizontal = 10.dp, vertical = 3.dp))
                        Spacer(Modifier.width(10.dp)); Text(text.ifBlank { "bookmark" }, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            AnimatedVisibility(panel == "ai" || summary != null) {
                Column(Modifier.padding(top = 14.dp).fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(cs.primary.copy(alpha = 0.06f)).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Art3D("sparkles", 30.dp, float = false, shadow = false)
                        Text("Atlas on this episode", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 6.dp))
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PillButton(if (busy) "Reading…" else "Summarise", Icons.Outlined.AutoAwesome, on = true) {
                            if (busy) return@PillButton
                            scope.launch {
                                busy = true
                                runCatching {
                                    com.nizam.app.net.Ai.generate(
                                        (c.applicationContext as com.nizam.app.NizamApplication).container.settingsRepository,
                                        "Podcast: ${ep.show}\nEpisode: ${ep.title}\nShow notes: ${ep.description.take(6000)}\n\n" +
                                            "Give the key points, who it's for, and three things worth remembering. If the notes are too thin " +
                                            "to be sure, say so rather than inventing content.", maxOutputTokens = 900)
                                }.onSuccess { text ->
                                    summary = text
                                    (c.applicationContext as com.nizam.app.NizamApplication).container.noteRepository
                                        .createFull("Podcast: ${ep.title}", text + "\n\n" + ep.show)
                                    Feedback.show("Summary saved to Notes")
                                }.onFailure { Feedback.failed("Summary", it) }
                                busy = false
                            }
                        }
                        if (summary != null) PillButton("Flashcards", null) {
                            val text = summary ?: return@PillButton
                            scope.launch {
                                val container = (c.applicationContext as com.nizam.app.NizamApplication).container
                                runCatching {
                                    val cards = com.nizam.app.net.Ai.generate(container.settingsRepository,
                                        "From this episode summary, write up to 6 flashcards that test understanding.\n" +
                                            "Return ONLY JSON [{\"front\":\"...\",\"back\":\"...\"}]\n\n$text", maxOutputTokens = 800)
                                    val arr = org.json.JSONArray(com.nizam.app.net.Ai.cleanJson(cards))
                                    val spec = container.dynamicRepository.spec("vocab")
                                    val backKey = spec?.fields?.firstOrNull { it.type == "TEXT" || it.type == "LONGTEXT" }?.key
                                    var n = 0
                                    for (i in 0 until arr.length()) {
                                        val card = arr.getJSONObject(i)
                                        if (spec != null && backKey != null) {
                                            container.dynamicRepository.addEntry("vocab", card.optString("front"), mapOf(backKey to card.optString("back"))); n++
                                        }
                                    }
                                    Feedback.show("$n flashcards saved")
                                }.onFailure { Feedback.failed("Flashcards", it) }
                            }
                        }
                        if (summary != null) PillButton("Clip card", Icons.Outlined.Share) {
                            val text = summary ?: return@PillButton
                            com.nizam.app.feature.reflect.shareCard(c, ep.title.take(40),
                                listOf(ep.show, "at ${clock(pos)}", "") + text.lines().filter { it.isNotBlank() }.take(6))
                        }
                    }
                    summary?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 10.dp)) }
                    var question by remember(ep.id) { mutableStateOf("") }
                    var answer by remember(ep.id) { mutableStateOf<String?>(null) }
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(question, { question = it }, label = { Text("What did they say about…?") }, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        PillButton("Ask", null, on = question.isNotBlank()) {
                            if (question.isBlank() || busy) return@PillButton
                            scope.launch {
                                busy = true
                                answer = runCatching {
                                    com.nizam.app.net.Ai.generate(
                                        (c.applicationContext as com.nizam.app.NizamApplication).container.settingsRepository,
                                        "Episode: ${ep.title} from ${ep.show}\nShow notes: ${ep.description.take(8000)}\n" +
                                            "Your bookmarks: " + marks.joinToString { "${clock(it.first)}: ${it.second}" } +
                                            "\n\nQuestion: ${question.trim()}\n\nAnswer from the notes. If the notes don't cover it, " +
                                            "say so plainly rather than guessing — you don't have the audio.", maxOutputTokens = 700)
                                }.getOrElse { "Couldn't answer: " + Feedback.friendly(it) }
                                busy = false
                            }
                        }
                    }
                    answer?.let { Text(it, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp)) }
                }
            }
            if (ep.description.isNotBlank()) {
                SectionHead("Show notes")
                Text(ep.description.take(3000), style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant)
            }
            val (listened, saved) = remember(Playback.current) { store.stats() }
            if (listened > 0) Text("Listened ${listened / 3600}h · speed saved you ${saved / 60} min",
                style = MaterialTheme.typography.labelMedium, color = cs.primary, modifier = Modifier.padding(top = 14.dp))
            Spacer(Modifier.height(60.dp))
        }
    }
}

// ═════════════════════════════ show page ═════════════════════════════

/** A show and its episodes. Paints from the cached feed instantly, then refreshes. */
@Composable
fun PodcastShowScreen(onOpenPlayer: () -> Unit) {
    val c = LocalContext.current
    val store = remember { PodcastStore(c) }
    remember { PodcastCache.init(c); 0 }
    val feed = PodcastNav.feed
    val seed = PodcastNav.show?.takeIf { it.feed == feed }
    var show by remember(feed) { mutableStateOf(seed) }
    var episodes by remember(feed) { mutableStateOf<List<Episode>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf("All") }
    var newestFirst by remember { mutableStateOf(true) }
    var subscribed by remember { mutableStateOf(store.subscribed(feed)) }
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(feed) {
        withContext(Dispatchers.Default) { Rss.cached(feed) }?.let { (s, eps) -> show = s.copy(image = s.image ?: seed?.image); episodes = eps }
        if (Rss.cacheAge(feed) > 20 * 60_000L || episodes == null) {
            updating = true
            runCatching { Rss.load(feed) }.onSuccess { (s, eps) -> show = s.copy(image = s.image ?: seed?.image); episodes = eps }
                .onFailure { if (episodes == null) error = "Couldn't read this feed: ${Feedback.friendly(it)}" }
            updating = false
        }
    }
    val s = show
    val cs = MaterialTheme.colorScheme
    Box(Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(bottom = 110.dp)) {
            item {
                Box(Modifier.fillMaxWidth().height(340.dp)) {
                    AsyncImage(s?.image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(50.dp))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(cs.background.copy(alpha = 0.2f), cs.background))))
                    Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 18.dp)) {
                        Cover(s?.image, s?.title ?: PodcastNav.title, Modifier.size(150.dp), corner = 24.dp, elevation = 18.dp)
                        Text(s?.title ?: PodcastNav.title, style = MaterialTheme.typography.headlineSmall.copy(fontFamily = DisplayFamily, fontWeight = FontWeight.Bold),
                            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
                        Text(listOf(s?.author.orEmpty(), episodes?.let { "${it.size} episodes" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.labelLarge, color = cs.onSurfaceVariant)
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val latest = episodes?.firstOrNull()
                    if (latest != null) PlayOrb(Playback.current?.id == latest.id && Playback.isPlaying, {
                        if (Playback.current?.id == latest.id) Playback.toggle() else { Playback.start(c, latest, store); refresh++ }
                    }, size = 54.dp)
                    PillButton(if (subscribed) "Following" else "Follow", if (subscribed) Icons.Outlined.Check else null, on = !subscribed) {
                        val sh = s ?: return@PillButton
                        store.toggle(sh); subscribed = store.subscribed(sh.feed)
                        Feedback.show(if (subscribed) "Following ${sh.title.take(30)}" else "Unfollowed")
                    }
                    if (subscribed && s != null) {
                        var auto by remember(s.feed) { mutableStateOf(store.autoDownload(s.feed)) }
                        PillButton("Auto-download", Icons.Outlined.Download, on = auto) {
                            store.toggleAutoDownload(s.feed); auto = store.autoDownload(s.feed)
                            Feedback.show(if (auto) "New episodes will download on Wi-Fi" else "Auto-download off")
                        }
                    }
                    if (updating) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }
            if (s != null && s.description.isNotBlank()) item {
                var more by remember { mutableStateOf(false) }
                Text(if (more) s.description else s.description.take(220) + if (s.description.length > 220) "…" else "",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 18.dp).clickable { more = !more })
            }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Unplayed", "In progress", "Starred", "Downloaded").forEach { f -> PillButton(f, null, on = f == filter) { filter = f } }
                    PillButton(if (newestFirst) "Newest" else "Oldest", if (newestFirst) Icons.Outlined.KeyboardArrowDown else Icons.Outlined.KeyboardArrowUp) { newestFirst = !newestFirst }
                }
            }
            val eps = episodes
            if (eps == null) item { Box(Modifier.padding(18.dp)) { if (error != null) Text(error!!, color = cs.error) else SkeletonRows(6) } }
            else {
                val starred = store.starred()
                val visible = eps.filter {
                    when (filter) {
                        "Unplayed" -> !store.played(it.id) && store.position(it.id) == 0L
                        "In progress" -> store.position(it.id) > 30_000 && !store.played(it.id)
                        "Starred" -> it.id in starred
                        "Downloaded" -> store.downloaded(it) != null
                        else -> true
                    }
                }.let { if (newestFirst) it else it.reversed() }
                items(visible.distinctBy { it.id }.take(300), key = { it.id }) { ep ->
                    Box(Modifier.padding(horizontal = 14.dp)) { EpisodeCard(ep, store, refresh, { refresh++ }, showArt = ep.image != s?.image) }
                }
            }
        }
        MiniPlayer(onOpenPlayer, Modifier.align(Alignment.BottomCenter))
    }
}
