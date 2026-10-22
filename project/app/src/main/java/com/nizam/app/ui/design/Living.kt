package com.nizam.app.ui.design

import android.content.Context
import android.os.CountDownTimer
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.outlined.Call
import androidx.compose.material.icons.outlined.LocalPharmacy
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Mosque
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.sin

/** One focus timer for the whole app, so the tab bar can show its countdown anywhere. */
object FocusTimer {
    var endsAt by mutableLongStateOf(0L)
        private set
    var totalMs by mutableLongStateOf(0L)
        private set
    private var timer: CountDownTimer? = null
    val running get() = endsAt > System.currentTimeMillis()
    fun start(minutes: Int) {
        timer?.cancel()
        totalMs = minutes * 60_000L; endsAt = System.currentTimeMillis() + totalMs
        timer = object : CountDownTimer(totalMs, 1000) {
            override fun onTick(ms: Long) {}
            override fun onFinish() { endsAt = 0L; Celebrate.now() }
        }.start()
    }
    fun stop() { timer?.cancel(); endsAt = 0L }
}

/**
 * The strip that grows above the tab bar when something is live: music playing, or a focus timer
 * running. Tap to open it; the controls work in place.
 */
@Composable
fun ContextStrip(currentRoute: String?, onOpen: (String) -> Unit) {
    val track = com.nizam.app.feature.music.MusicPlayback.current
    val timerOn = FocusTimer.endsAt > 0L
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timerOn) { com.nizam.app.ui.design.guard { while (FocusTimer.endsAt > 0L) { now = System.currentTimeMillis(); delay(1000) } } }
    val c = MaterialTheme.colorScheme
    val showMusic = track != null && currentRoute != "music"
    if (!showMusic && !timerOn) return
    Column(Modifier.fillMaxWidth().padding(horizontal = Space.l).padding(top = Space.xs)) {
        if (timerOn) {
            val left = (FocusTimer.endsAt - now).coerceAtLeast(0)
            Row(Modifier.fillMaxWidth().liquidGlass(Radius.pill).pressable { onOpen("timer") }.padding(horizontal = Space.l, vertical = Space.s),
                verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Timer, null, tint = c.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.s))
                Text("Focus", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                Text("%02d:%02d".format(left / 60_000, (left % 60_000) / 1000), style = MaterialTheme.typography.titleMedium, color = c.primary)
            }
            Spacer(Modifier.height(Space.xs))
        }
        if (showMusic && track != null) {
            val playing = com.nizam.app.feature.music.MusicPlayback.isPlaying
            Row(Modifier.fillMaxWidth().liquidGlass(Radius.pill).pressable { onOpen("music") }.padding(start = Space.xs, end = Space.s, top = Space.xs, bottom = Space.xs),
                verticalAlignment = Alignment.CenterVertically) {
                coil.compose.AsyncImage(track.art.ifBlank { null }, null, Modifier.size(36.dp).clip(Radius.pill).background(c.surfaceVariant))
                Spacer(Modifier.width(Space.s))
                Column(Modifier.weight(1f)) {
                    Text(track.title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (playing) "Pause" else "Play",
                    Modifier.size(36.dp).clip(Radius.pill).pressable { com.nizam.app.feature.music.MusicPlayback.toggle() }.padding(6.dp))
                Icon(Icons.Filled.SkipNext, "Next", Modifier.size(36.dp).clip(Radius.pill).pressable { com.nizam.app.feature.music.MusicPlayback.next() }.padding(6.dp))
            }
        }
    }
}

/** A faint eight-pointed star lattice, the classic Islamic geometric motif, behind Deen screens. */
@Composable
fun GeometricPattern(modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary.copy(alpha = 0.07f)) {
    Canvas(modifier) {
        val cell = 64.dp.toPx()
        val r = cell * 0.42f
        var y = 0f
        var row = 0
        while (y < size.height + cell) {
            var x = if (row % 2 == 0) 0f else cell / 2
            while (x < size.width + cell) {
                val star = Path()
                for (i in 0 until 16) {
                    val a = Math.PI / 8 * i
                    val rr = if (i % 2 == 0) r else r * 0.62f
                    val px = x + (cos(a) * rr).toFloat(); val py = y + (sin(a) * rr).toFloat()
                    if (i == 0) star.moveTo(px, py) else star.lineTo(px, py)
                }
                star.close()
                drawPath(star, color, style = Stroke(1.2f))
                drawCircle(color, r * 0.22f, Offset(x, y), style = Stroke(1f))
                x += cell
            }
            y += cell * 0.86f; row++
        }
    }
}

/** Ambient mode: a dim bedside display while charging — time, date, next prayer. */
@Composable
fun AmbientScreen(nextPrayer: String?, onExit: () -> Unit) {
    var t by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) { com.nizam.app.ui.design.guard { while (true) { t = LocalDateTime.now(); delay(1000) } } }
    val night = t.hour >= 22 || t.hour < 6
    val ink = if (night) Color(0xFFB0413E) else Color(0xFFE8E6E1)
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } }
    Box(Modifier.fillMaxSize().background(Color.Black).pressable(haptic = false, onClick = onExit), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(t.format(DateTimeFormatter.ofPattern("h:mm")), color = ink, fontSize = 96.sp, style = MaterialTheme.typography.displayLarge)
            Text(t.format(DateTimeFormatter.ofPattern("EEEE d MMMM")), color = ink.copy(alpha = 0.7f), style = MaterialTheme.typography.titleMedium)
            if (nextPrayer != null) Text(nextPrayer, color = ink.copy(alpha = 0.6f), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = Space.l))
            Text("Tap to leave", color = ink.copy(alpha = 0.3f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = Space.xxl))
        }
    }
}

/**
 * Elder mode home: four huge tiles and voice first — for parents and grandparents.
 */
@Composable
fun ElderHome(greeting: String, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val c = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Text(greeting, style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(vertical = Space.l))
        val tiles = listOf(
            Triple("Talk to Atlas", Icons.Outlined.Mic, "voice"),
            Triple("Prayer times", Icons.Outlined.Mosque, "prayer"),
            Triple("Medicines", Icons.Outlined.LocalPharmacy, "medicines"),
            Triple("Call family", Icons.Outlined.Call, "power/guardian")
        )
        tiles.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                row.forEach { (label, icon, route) ->
                    Column(Modifier.weight(1f).fillMaxSize().clip(Radius.tile).background(wash(c.primary, 0.16f)).pressable { onOpen(route) }.padding(Space.l),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(icon, null, tint = c.primary, modifier = Modifier.size(64.dp))
                        Text(label, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = Space.m))
                    }
                }
            }
        }
        Text("Switch off Elder mode in Look & comfort", style = MaterialTheme.typography.bodyMedium, color = c.onSurfaceVariant,
            modifier = Modifier.pressable { onOpen("appearance") }.padding(Space.s))
    }
}
